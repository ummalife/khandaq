#include "../tox_extension_messages.c"

#include <toxext/toxext.h>
#include <toxext/mock_fixtures.h>

static uint8_t *last_received_buffer = NULL;
static size_t last_received_buffer_size = 0;
static uint64_t last_received_receipt_id = 0;
static bool receipt_called = false;
static void test_cb(uint32_t friend_number, uint8_t const *message,
		    size_t length, void *user_data)
{
	(void)friend_number;
	(void)message;
	(void)user_data;

	free(last_received_buffer);
	last_received_buffer = malloc(length);
	last_received_buffer_size = length;
	memcpy(last_received_buffer, message, length);
}

static void test_receipt_cb(uint32_t friend_number, const uint64_t receipt_id,
			    void *user_data)
{
	(void)friend_number;
	(void)user_data;
	receipt_called = true;
	last_received_receipt_id = receipt_id;
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

static char const zero_sized_buffer[] = "";
static char const small_sized_buffer[] = "asdf";
static uint8_t med_sized_buffer[TOXEXT_MAX_SEGMENT_SIZE * 2 -
				TOXEXT_MAX_SEGMENT_SIZE / 2];
static uint8_t large_sized_buffer[TOXEXT_MAX_SEGMENT_SIZE * 3 -
				  TOXEXT_MAX_SEGMENT_SIZE / 2];

static void test_send_buffer(struct ToxExtUser *user_a,
			     struct ToxExtensionMessages *ext_a,
			     struct ToxExtUser *user_b, uint8_t const *buffer,
			     size_t buffer_size)
{
	struct ToxExtPacketList *packet_list =
		toxext_packet_list_create(user_a->toxext, user_b->tox_user.id);
	enum Tox_Extension_Messages_Error err;
	uint64_t id = tox_extension_messages_append(ext_a, packet_list, buffer,
						    buffer_size,
						    user_b->tox_user.id, &err);
	assert(err == TOX_EXTENSION_MESSAGES_SUCCESS);

	// if receipt_called is false any id is valid
	assert(id != last_received_receipt_id || !receipt_called);
	receipt_called = false;

	toxext_send(packet_list);

	tox_iterate(user_b->tox_user.tox, &user_b->tox_user);
	tox_iterate(user_a->tox_user.tox, &user_a->tox_user);

	assert(last_received_buffer_size == buffer_size);
	assert(memcmp(last_received_buffer, buffer,
		      last_received_buffer_size) == 0);
	assert(receipt_called);
	assert(id == last_received_receipt_id);
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

	struct ToxExtensionMessages *ext_a = tox_extension_messages_register(
		user_a.toxext, test_cb, test_receipt_cb, test_neg_cb, NULL,
		TOX_EXTENSION_MESSAGES_DEFAULT_MAX_RECEIVING_MESSAGE_SIZE);
	struct ToxExtensionMessages *ext_b = tox_extension_messages_register(
		user_b.toxext, test_cb, test_receipt_cb, test_neg_cb, NULL,
		TOX_EXTENSION_MESSAGES_DEFAULT_MAX_RECEIVING_MESSAGE_SIZE);

	tox_extension_messages_negotiate(ext_a, user_b.tox_user.id);

	tox_iterate(user_b.tox_user.tox, &user_b.tox_user);
	tox_iterate(user_a.tox_user.tox, &user_a.tox_user);
	tox_iterate(user_b.tox_user.tox, &user_b.tox_user);
	tox_iterate(user_a.tox_user.tox, &user_a.tox_user);

	test_send_buffer(&user_a, ext_a, &user_b,
			 (uint8_t const *)small_sized_buffer,
			 sizeof(small_sized_buffer));
	test_send_buffer(&user_a, ext_a, &user_b,
			 (uint8_t const *)med_sized_buffer,
			 sizeof(med_sized_buffer));
	test_send_buffer(&user_a, ext_a, &user_b,
			 (uint8_t const *)large_sized_buffer,
			 sizeof(large_sized_buffer));
	test_send_buffer(&user_a, ext_a, &user_b,
			 (uint8_t const *)zero_sized_buffer,
			 sizeof(zero_sized_buffer));

	free(last_received_buffer);

	tox_extension_messages_free(ext_b);
	tox_extension_messages_free(ext_a);

	toxext_test_cleanup_tox_ext_user(&user_b);
	toxext_test_cleanup_tox_ext_user(&user_a);

	return 0;
}
