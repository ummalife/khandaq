#include "tox_extension_messages.h"

#include <toxext/toxext.h>
#include <toxext/toxext_util.h>

#include <assert.h>
#include <stdlib.h>
#include <string.h>

static uint8_t const uuid[16] = { 0x9e, 0x10, 0x03, 0x16, 0xd2, 0x6f,
				  0x45, 0x39, 0x8c, 0xdb, 0xae, 0x81,
				  0x00, 0x42, 0xf8, 0x64 };

enum Messages {
	MESSAGE_NEGOTIATE,
	MESSAGE_START,
	MESSAGE_PART,
	MESSAGE_FINISH,
	MESSAGE_RECEIVED,
};

struct IncomingMessage {
	uint8_t *message;
	size_t size;
	size_t capacity;
};

struct FriendData {
	uint32_t friend_id;
	/*
	 * Incoming message size is only available in the first part of a message.
	 * If we know the incoming message is too big we set this flag value to
	 * indicate that all incoming packets should be dropped until the next
	 * message starts
	 */
	bool drop_incoming_message;
	struct IncomingMessage message;
	uint64_t max_sending_size;
};

struct ToxExtensionMessages {
	struct ToxExtExtension *extension_handle;
	// Ideally we would use a better data structure for this but C doesn't have a ton available
	struct FriendData *friend_datas;
	size_t friend_datas_size;
	uint64_t next_receipt_id;
	tox_extension_messages_received_cb cb;
	tox_extension_messages_receipt_cb receipt_cb;
	tox_extension_messages_negotiate_cb negotiated_cb;
	void *userdata;
	uint64_t max_receiving_message_size;
};

static struct FriendData *
get_friend_data(struct ToxExtensionMessages *extension, uint32_t friend_id)
{
	for (size_t i = 0; i < extension->friend_datas_size; ++i) {
		if (extension->friend_datas[i].friend_id == friend_id) {
			return &extension->friend_datas[i];
		}
	}

	return NULL;
}

static struct FriendData *
get_or_insert_friend_data(struct ToxExtensionMessages *extension,
			  uint32_t friend_id)
{
	struct FriendData *friend_data = get_friend_data(extension, friend_id);

	if (friend_data) {
		return friend_data;
	}

	struct FriendData *new_friend_datas = realloc(
		extension->friend_datas,
		(extension->friend_datas_size + 1) * sizeof(struct FriendData));

	if (!new_friend_datas) {
		/* FIXME: We should probably tell the sender that we dropped a message here */
		return NULL;
	}

	extension->friend_datas = new_friend_datas;
	extension->friend_datas_size++;

	friend_data =
		&extension->friend_datas[extension->friend_datas_size - 1];
	friend_data->friend_id = friend_id;
	friend_data->drop_incoming_message = false;
	friend_data->message.message = NULL;
	friend_data->message.size = 0;
	friend_data->message.capacity = 0;
	friend_data->max_sending_size = 0;

	return friend_data;
}

static void clear_incoming_message(struct IncomingMessage *incoming_message)
{
	free(incoming_message->message);
	incoming_message->message = NULL;
	incoming_message->size = 0;
	incoming_message->capacity = 0;
}

struct MessagesPacket {
	enum Messages message_type;
	/* On start packets we flag how large the entire buffer will be */
	size_t total_message_size;
	uint8_t const *message_data;
	size_t message_size;
	size_t receipt_id;
	uint64_t max_sending_message_size;
};

bool parse_messages_packet(uint8_t const *data, size_t size,
			   struct MessagesPacket *messages_packet)
{
	uint8_t const *it = data;
	uint8_t const *end = data + size;

	if (it + 1 > end) {
		return false;
	}
	messages_packet->message_type = *it;
	it += 1;

	if (messages_packet->message_type == MESSAGE_RECEIVED) {
		messages_packet->receipt_id =
			toxext_read_from_buf(uint64_t, it, 8);
		return true;
	}
	else if (messages_packet->message_type == MESSAGE_START) {
		if (it + 8 > end) {
			return false;
		}

		messages_packet->total_message_size =
			toxext_read_from_buf(uint64_t, it, 8);
		it += 8;
	}
	else if (messages_packet->message_type == MESSAGE_FINISH) {
		messages_packet->receipt_id =
			toxext_read_from_buf(uint64_t, it, 8);
		it += 8;
	}
	else if (messages_packet->message_type == MESSAGE_NEGOTIATE) {
		messages_packet->max_sending_message_size =
			toxext_read_from_buf(uint64_t, it, 8);
		it += 8;
	}

	if (it > end) {
		return false;
	}

	messages_packet->message_data = it;
	messages_packet->message_size = end - it;

	return true;
}

void tox_extension_messages_negotiate_size(
	struct ToxExtensionMessages *extension,
	struct ToxExtPacketList *response_packet_list)
{
	uint8_t data[9];
	data[0] = MESSAGE_NEGOTIATE;
	toxext_write_to_buf(extension->max_receiving_message_size, data + 1, 8);
	toxext_segment_append(response_packet_list, extension->extension_handle,
			      data, 9);
	return;
}

void tox_extension_copy_in_message_data(struct MessagesPacket *parsed_packet,
					struct IncomingMessage *incoming_message)
{
	if (parsed_packet->message_size + incoming_message->size >
	    incoming_message->capacity) {
		/* FIXME: We should probably tell the sender that we dropped a message here */
		clear_incoming_message(incoming_message);
		return;
	}

	memcpy(incoming_message->message + incoming_message->size,
	       parsed_packet->message_data, parsed_packet->message_size);
	incoming_message->size += parsed_packet->message_size;
}

void tox_extension_messages_handle_message_start(
	struct ToxExtensionMessages *extension,
	struct MessagesPacket *parsed_packet, struct FriendData *friend_data)
{
	struct IncomingMessage *incoming_message = &friend_data->message;

	if (extension->max_receiving_message_size <
	    parsed_packet->total_message_size) {
		friend_data->drop_incoming_message = true;
		return;
	}

	/*
		* realloc here instead of malloc because we may have dropped half a message
		* if a user went offline half way through sending
		*/
	uint8_t *resized_message = realloc(incoming_message->message,
					   parsed_packet->total_message_size);

	if (!resized_message) {
		/* FIXME: We should probably tell the sender that we dropped a message here */
		clear_incoming_message(incoming_message);
		return;
	}

	incoming_message->message = resized_message;
	incoming_message->size = 0;
	incoming_message->capacity = parsed_packet->total_message_size;

	/*
	 * If we never got a finish packet we should still do our best to parse the
	 * next message. This means we need to reset the drop state as well
	 */
	friend_data->drop_incoming_message = false;

	tox_extension_copy_in_message_data(parsed_packet, incoming_message);
}

void tox_extension_messages_handle_message_finish(
	struct ToxExtensionMessages *extension, uint32_t friend_id,
	struct MessagesPacket *parsed_packet, struct FriendData *friend_data,
	struct ToxExtPacketList *response_packet_list)
{
	struct IncomingMessage *incoming_message = &friend_data->message;
	uint8_t const* message = NULL;
	size_t size = 0;

	bool end_of_dropped_message = friend_data->drop_incoming_message;
	friend_data->drop_incoming_message = false;

	/* We can skip the allocate/memcpy here */
	if (incoming_message->size == 0) {
		message = parsed_packet->message_data;
		size = parsed_packet->message_size;
	}
	else {
		tox_extension_copy_in_message_data(parsed_packet, incoming_message);
		message = incoming_message->message;
		size = incoming_message->size;
	}

	if (end_of_dropped_message ||
	    extension->max_receiving_message_size < size) {
		/* FIXME: We should probably tell the sender that we dropped a message here */
		clear_incoming_message(incoming_message);
		return;
	}


	if (extension->cb) {
		extension->cb(friend_id, message, size, extension->userdata);
	}

	uint8_t data[9];
	data[0] = MESSAGE_RECEIVED;
	toxext_write_to_buf(parsed_packet->receipt_id, data + 1, 8);
	toxext_segment_append(response_packet_list, extension->extension_handle,
			      data, 9);

	clear_incoming_message(incoming_message);
}

void tox_extension_messages_handle_message_part(
	struct MessagesPacket *parsed_packet, struct FriendData *friend_data)
{
	struct IncomingMessage *incoming_message = &friend_data->message;

	if (friend_data->drop_incoming_message) {
		/* FIXME: We should probably tell the sender that we dropped a message here */
		clear_incoming_message(incoming_message);
		return;
	}
	tox_extension_copy_in_message_data(parsed_packet, incoming_message);
}

static void
tox_extension_messages_recv(struct ToxExtExtension *extension,
			    uint32_t friend_id, void const *data, size_t size,
			    void *userdata,
			    struct ToxExtPacketList *response_packet_list)
{
	(void)extension;
	struct ToxExtensionMessages *ext_messages = userdata;
	struct FriendData *friend_data =
		get_friend_data(ext_messages, friend_id);

	struct MessagesPacket parsed_packet;
	if (!parse_messages_packet(data, size, &parsed_packet)) {
		/* FIXME: We should probably tell the sender that they gave us invalid data here */
		clear_incoming_message(&friend_data->message);
		return;
	}

	switch (parsed_packet.message_type) {
	case MESSAGE_NEGOTIATE:
		friend_data->max_sending_size =
			parsed_packet.max_sending_message_size;
		ext_messages->negotiated_cb(friend_id, true,
					    friend_data->max_sending_size,
					    ext_messages->userdata);
		return;
	case MESSAGE_START:
		tox_extension_messages_handle_message_start(
			ext_messages, &parsed_packet, friend_data);
		return;
	case MESSAGE_PART: {
		tox_extension_messages_handle_message_part(&parsed_packet,
							   friend_data);
		return;
	}
	case MESSAGE_FINISH:
		tox_extension_messages_handle_message_finish(
			ext_messages, friend_id, &parsed_packet, friend_data,
			response_packet_list);
		return;
	case MESSAGE_RECEIVED:
		ext_messages->receipt_cb(friend_id, parsed_packet.receipt_id,
					 ext_messages->userdata);
		return;
	}
}

static void
tox_extension_messages_neg(struct ToxExtExtension *extension,
			   uint32_t friend_id, bool compatible, void *userdata,
			   struct ToxExtPacketList *response_packet_list)
{
	(void)extension;
	struct ToxExtensionMessages *ext_messages = userdata;

	get_or_insert_friend_data(ext_messages, friend_id);

	if (!compatible) {
		ext_messages->negotiated_cb(friend_id, compatible, 0,
					    ext_messages->userdata);
	} else {
		/*
		 * Do not call the negotiation callback yet. We need to continue with
		 * our own internal negotiation of max message size. We consider
		 * ourselves negotiated when our peer has told us what their max packet
		 * size is
		 */
		tox_extension_messages_negotiate_size(ext_messages,
						      response_packet_list);
	}
}

struct ToxExtensionMessages *
tox_extension_messages_register(struct ToxExt *toxext,
				tox_extension_messages_received_cb cb,
				tox_extension_messages_receipt_cb receipt_cb,
				tox_extension_messages_negotiate_cb neg_cb,
				void *userdata, uint64_t max_receive_size)
{
	assert(cb);

	struct ToxExtensionMessages *extension =
		malloc(sizeof(struct ToxExtensionMessages));

	if (!extension) {
		return NULL;
	}

	extension->extension_handle =
		toxext_register(toxext, uuid, extension,
				tox_extension_messages_recv,
				tox_extension_messages_neg);
	extension->friend_datas = NULL;
	extension->friend_datas_size = 0;
	extension->next_receipt_id = 0;
	extension->cb = cb;
	extension->receipt_cb = receipt_cb;
	extension->negotiated_cb = neg_cb;
	extension->userdata = userdata;
	extension->max_receiving_message_size = max_receive_size;

	if (!extension->extension_handle) {
		free(extension);
		return NULL;
	}

	return extension;
}

void tox_extension_messages_free(struct ToxExtensionMessages *extension)
{
	for (size_t i = 0; i < extension->friend_datas_size; ++i) {
		free(extension->friend_datas[i].message.message);
	}
	free(extension->friend_datas);
	free(extension);
}

void tox_extension_messages_negotiate(struct ToxExtensionMessages *extension,
				      uint32_t friend_id)
{
	toxext_negotiate_connection(extension->extension_handle, friend_id);
}

static uint8_t const *
tox_extension_messages_chunk(bool first_chunk, uint8_t const *data, size_t size,
			     uint64_t receipt_id, uint8_t *extension_data,
			     size_t *output_size)
{
	uint8_t const *ret;
	bool last_chunk = size <= TOXEXT_MAX_SEGMENT_SIZE - 9;

	if (last_chunk) {
		extension_data[0] = MESSAGE_FINISH;
		toxext_write_to_buf(receipt_id, extension_data + 1, 8);

		size_t advance_size = size;
		*output_size = size + 9;
		ret = data + advance_size;
		memcpy(extension_data + 9, data, advance_size);
	} else if (first_chunk) {
		extension_data[0] = MESSAGE_START;
		toxext_write_to_buf(size, extension_data + 1, 8);
		size_t advance_size = TOXEXT_MAX_SEGMENT_SIZE - 9;
		memcpy(extension_data + 9, data, advance_size);
		*output_size = TOXEXT_MAX_SEGMENT_SIZE;
		ret = data + advance_size;
	} else {
		extension_data[0] = MESSAGE_PART;
		size_t advance_size = TOXEXT_MAX_SEGMENT_SIZE - 1;
		memcpy(extension_data + 1, data, advance_size);
		*output_size = TOXEXT_MAX_SEGMENT_SIZE;
		ret = data + advance_size;
	}

	return ret;
}

uint64_t tox_extension_messages_append(struct ToxExtensionMessages *extension,
				       struct ToxExtPacketList *packet_list,
				       uint8_t const *data, size_t size,
				       uint32_t friend_id,
				       enum Tox_Extension_Messages_Error *err)
{
	enum Tox_Extension_Messages_Error get_max_err;
	uint64_t max_sending_size = tox_extension_messages_get_max_sending_size(
		extension, friend_id, &get_max_err);
	if (get_max_err != TOX_EXTENSION_MESSAGES_SUCCESS ||
	    size > max_sending_size) {
		if (err) {
			*err = TOX_EXTENSION_MESSAGES_INVALID_ARG;
		}
		return -1;
	}

	uint8_t const *end = data + size;
	uint8_t const *next_chunk = data;
	bool first_chunk = true;
	uint64_t receipt_id = extension->next_receipt_id++;
	do {
		uint8_t extension_data[TOXEXT_MAX_SEGMENT_SIZE];
		size_t size_for_chunk;
		next_chunk = tox_extension_messages_chunk(
			first_chunk, next_chunk, end - next_chunk, receipt_id,
			extension_data, &size_for_chunk);
		first_chunk = false;

		toxext_segment_append(packet_list, extension->extension_handle,
				      extension_data, size_for_chunk);
	} while (end > next_chunk);

	if (err) {
		*err = TOX_EXTENSION_MESSAGES_SUCCESS;
	}
	return receipt_id;
}

uint64_t tox_extension_messages_get_max_receiving_size(
	struct ToxExtensionMessages *extension)
{
	return extension->max_receiving_message_size;
}

uint64_t tox_extension_messages_get_max_sending_size(
	struct ToxExtensionMessages *extension, uint32_t friend_id,
	enum Tox_Extension_Messages_Error *err)
{
	struct FriendData *friend_datas = get_friend_data(extension, friend_id);

	if (!friend_datas) {
		*err = TOX_EXTENSION_MESSAGES_INVALID_ARG;
		return 0;
	}

	*err = TOX_EXTENSION_MESSAGES_SUCCESS;
	return friend_datas->max_sending_size;
}
