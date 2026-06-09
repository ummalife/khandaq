#pragma once

#include <toxext/toxext.h>

struct ToxExtensionMessages;

#define TOX_EXTENSION_MESSAGES_DEFAULT_MAX_RECEIVING_MESSAGE_SIZE              \
	10 * 1024 * 1024

enum Tox_Extension_Messages_Error {
	TOX_EXTENSION_MESSAGES_SUCCESS = 0,
	TOX_EXTENSION_MESSAGES_INVALID_ARG,
	TOX_EXTENSION_MESSAGES_NOT_SUPPORTED
};

/**
 * Callback when message received from friend
 */
typedef void (*tox_extension_messages_received_cb)(uint32_t friend_number,
						   const uint8_t *message,
						   size_t length,
						   void *user_data);

/**
 * Callback when friend receives a message with receipt id receipt_id
 */
typedef void (*tox_extension_messages_receipt_cb)(uint32_t friend_number,
						  const uint64_t receipt_id,
						  void *user_data);

/**
 * Callback on negotiation completion
 */
typedef void (*tox_extension_messages_negotiate_cb)(uint32_t friend_number,
						    bool negotiated,
						    uint64_t max_sending_size,
						    void *user_data);

/**
 * Register a new extension instance with toxext
 */
struct ToxExtensionMessages *
tox_extension_messages_register(struct ToxExt *toxext,
				tox_extension_messages_received_cb cb,
				tox_extension_messages_receipt_cb receipt_cb,
				tox_extension_messages_negotiate_cb neg_cb,
				void *userdata, uint64_t max_receive_size);

/**
 * Free extension handle
 */
void tox_extension_messages_free(struct ToxExtensionMessages *extension);

/**
 * Initiate negotiation with friend_id
 */
void tox_extension_messages_negotiate(struct ToxExtensionMessages *extension,
				      uint32_t friend_id);

/**
 * Append message data to a packet list associated with this extension instance
 *
 * Returns an id which will be used in the receipt_cb to indicate the message
 * has been received by the friend
 */
uint64_t tox_extension_messages_append(struct ToxExtensionMessages *extension,
				       struct ToxExtPacketList *packet_list,
				       uint8_t const *data, size_t size,
				       uint32_t friend_id,
				       enum Tox_Extension_Messages_Error *err);

/**
 * The current max message size that will be accepted.
 */
uint64_t tox_extension_messages_get_max_receiving_size(
	struct ToxExtensionMessages *extension);

/**
 * The max message size that friend_id will accept from us.
 */
uint64_t tox_extension_messages_get_max_sending_size(
	struct ToxExtensionMessages *extension, uint32_t friend_id,
	enum Tox_Extension_Messages_Error *err);
