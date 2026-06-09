#include "../tox_extension_messages.c"

#include <toxext/toxext.h>
#include <toxext/mock_fixtures.h>
#include <assert.h>

static uint64_t last_received_length = -1;
static const uint64_t a_max_size = 1000;

static void test_cb(uint32_t friend_number, uint8_t const *message,
		    size_t length, void *user_data)
{
	(void)friend_number;
	(void)message;
	(void)user_data;
	last_received_length = length;
}

static void test_receipt_cb(uint32_t friend_number, const uint64_t receipt_id,
			    void *user_data)
{
	(void)friend_number;
	(void)user_data;
	(void)receipt_id;
}

static void test_neg_cb(uint32_t friend_number, bool compatible,
			uint64_t max_sending_size,
			void *user_data)
{
	(void)friend_number;
	(void)compatible;
	(void)max_sending_size;
	(void)user_data;
}

uint64_t test_tox_extension_messages_append_no_size_check(
	struct ToxExtensionMessages *extension,
	struct ToxExtPacketList *packet_list, uint8_t const *data, size_t size,
	uint32_t friend_id, enum Tox_Extension_Messages_Error *err)
{
	(void)friend_id;

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

void test_unnegotiated_size(struct ToxExtUser *user_a,
			    struct ToxExtUser *user_b,
			    struct ToxExtensionMessages *ext_a,
			    struct ToxExtensionMessages *ext_b)
{
	enum Tox_Extension_Messages_Error err;
	tox_extension_messages_get_max_sending_size(ext_a, user_b->tox_user.id,
						    &err);
	assert(err == TOX_EXTENSION_MESSAGES_INVALID_ARG);
	tox_extension_messages_get_max_sending_size(ext_b, user_a->tox_user.id,
						    &err);
	assert(err == TOX_EXTENSION_MESSAGES_INVALID_ARG);
}

void test_negotiated_size(struct ToxExtUser *user_a, struct ToxExtUser *user_b,
			  struct ToxExtensionMessages *ext_a,
			  struct ToxExtensionMessages *ext_b)
{
	enum Tox_Extension_Messages_Error err;
	uint64_t max_size = tox_extension_messages_get_max_sending_size(
		ext_a, user_b->tox_user.id, &err);
	assert(err == TOX_EXTENSION_MESSAGES_SUCCESS);
	assert(max_size ==
	       TOX_EXTENSION_MESSAGES_DEFAULT_MAX_RECEIVING_MESSAGE_SIZE);
	max_size = tox_extension_messages_get_max_sending_size(
		ext_b, user_a->tox_user.id, &err);
	assert(err == TOX_EXTENSION_MESSAGES_SUCCESS);
	assert(max_size == a_max_size);
}

void test_sending_over_max(struct ToxExtUser *user_a, struct ToxExtUser *user_b,
			   struct ToxExtensionMessages *ext_b)
{
	enum Tox_Extension_Messages_Error err;
	struct ToxExtPacketList *packet_list =
		toxext_packet_list_create(user_b->toxext, user_a->tox_user.id);
	uint8_t buffer[a_max_size + 1];
	tox_extension_messages_append(ext_b, packet_list, buffer,
				      a_max_size + 1, user_a->tox_user.id,
				      &err);
	assert(err == TOX_EXTENSION_MESSAGES_INVALID_ARG);
	toxext_send(packet_list);
	tox_iterate(user_b->tox_user.tox, &user_b->tox_user);
	tox_iterate(user_a->tox_user.tox, &user_a->tox_user);
}

void test_sending_equal_to_max(struct ToxExtUser *user_a,
			       struct ToxExtUser *user_b,
			       struct ToxExtensionMessages *ext_b)
{
	enum Tox_Extension_Messages_Error err;
	struct ToxExtPacketList *packet_list =
		toxext_packet_list_create(user_b->toxext, user_a->tox_user.id);
	uint8_t buffer[a_max_size];
	tox_extension_messages_append(ext_b, packet_list,
				      (uint8_t const *)buffer, sizeof(buffer),
				      user_a->tox_user.id, &err);
	assert(err == TOX_EXTENSION_MESSAGES_SUCCESS);
	toxext_send(packet_list);

	tox_iterate(user_b->tox_user.tox, &user_b->tox_user);
	tox_iterate(user_a->tox_user.tox, &user_a->tox_user);

	assert(last_received_length == sizeof(buffer));
}

void test_receiving_single_segment_over_max(struct ToxExtUser *user_a,
					    struct ToxExtUser *user_b,
					    struct ToxExtensionMessages *ext_b)
{
	enum Tox_Extension_Messages_Error err;
	struct ToxExtPacketList *packet_list =
		toxext_packet_list_create(user_b->toxext, user_a->tox_user.id);
	uint8_t buffer[a_max_size + 1];
	test_tox_extension_messages_append_no_size_check(
		ext_b, packet_list, (uint8_t const *)buffer, sizeof(buffer),
		user_a->tox_user.id, &err);
	assert(err == TOX_EXTENSION_MESSAGES_SUCCESS);

	last_received_length = 1;
	toxext_send(packet_list);

	tox_iterate(user_b->tox_user.tox, &user_b->tox_user);
	tox_iterate(user_a->tox_user.tox, &user_a->tox_user);

	assert(last_received_length == 1);
}

void test_receiving_multi_segment_over_max(struct ToxExtUser *user_a,
					   struct ToxExtUser *user_b,
					   struct ToxExtensionMessages *ext_b)
{
	enum Tox_Extension_Messages_Error err;
	struct ToxExtPacketList *packet_list =
		toxext_packet_list_create(user_b->toxext, user_a->tox_user.id);

	uint8_t buffer[TOXEXT_MAX_SEGMENT_SIZE * 3 -
		       TOXEXT_MAX_SEGMENT_SIZE / 2];

	test_tox_extension_messages_append_no_size_check(
		ext_b, packet_list, (uint8_t const *)buffer, sizeof(buffer),
		user_a->tox_user.id, &err);
	assert(err == TOX_EXTENSION_MESSAGES_SUCCESS);

	last_received_length = 1;
	toxext_send(packet_list);
	tox_iterate(user_b->tox_user.tox, &user_b->tox_user);
	tox_iterate(user_a->tox_user.tox, &user_a->tox_user);

	assert(last_received_length == 1);
}

/**
 * Just trying to ensure the logic of the few different packet cases are handled correctly
 */
int main(void)
{
	struct ToxExtUser user_a;
	struct ToxExtUser user_b;

	toxext_test_init_tox_ext_user(&user_a);
	toxext_test_init_tox_ext_user(&user_b);

	struct ToxExtensionMessages *ext_a =
		tox_extension_messages_register(user_a.toxext, test_cb,
						test_receipt_cb, test_neg_cb,
						NULL, a_max_size);
	struct ToxExtensionMessages *ext_b = tox_extension_messages_register(
		user_b.toxext, test_cb, test_receipt_cb, test_neg_cb, NULL,
		TOX_EXTENSION_MESSAGES_DEFAULT_MAX_RECEIVING_MESSAGE_SIZE);

	assert(a_max_size ==
	       tox_extension_messages_get_max_receiving_size(ext_a));
	assert(TOX_EXTENSION_MESSAGES_DEFAULT_MAX_RECEIVING_MESSAGE_SIZE ==
	       tox_extension_messages_get_max_receiving_size(ext_b));

	test_unnegotiated_size(&user_a, &user_b, ext_a, ext_b);

	tox_extension_messages_negotiate(ext_a, user_b.tox_user.id);
	tox_extension_messages_negotiate(ext_b, user_a.tox_user.id);

	tox_iterate(user_b.tox_user.tox, &user_b.tox_user);
	tox_iterate(user_a.tox_user.tox, &user_a.tox_user);
	tox_iterate(user_b.tox_user.tox, &user_b.tox_user);
	tox_iterate(user_a.tox_user.tox, &user_a.tox_user);

	test_negotiated_size(&user_a, &user_b, ext_a, ext_b);

	test_sending_over_max(&user_a, &user_b, ext_b);

	test_sending_equal_to_max(&user_a, &user_b, ext_b);

	test_receiving_single_segment_over_max(&user_a, &user_b, ext_b);

	test_receiving_multi_segment_over_max(&user_a, &user_b, ext_b);

	test_sending_equal_to_max(&user_a, &user_b, ext_b);

	tox_extension_messages_free(ext_b);
	tox_extension_messages_free(ext_a);

	toxext_test_cleanup_tox_ext_user(&user_b);
	toxext_test_cleanup_tox_ext_user(&user_a);

	return 0;
}
