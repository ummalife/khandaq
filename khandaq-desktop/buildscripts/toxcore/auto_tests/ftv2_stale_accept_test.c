/* KHANDAQ (FTV2 stale accept): an FTV2 offer is left alone until the receiver accepts it.
 *
 * do_reqchunk_filecb() in Messenger.c sends SEEK + ACCEPT to the sender of a receiving FTV2 transfer that has had
 * no data for STALE_CYCLES_ON_FTV2_RECEIVER iterations, in case the receiver's ACCEPT was lost while the sender was
 * away. Upstream did that for every receiving FTV2 transfer. An offer still waiting for the person was started by
 * it: the sender sent the whole file, and the receiver dropped every chunk of a transfer it had not accepted, once a
 * stale period for as long as the offer waited. A transfer the receiver had paused was resumed by it.
 *
 * Tox 0 sends, tox 1 receives:
 * 1. an offer tox 1 leaves alone for longer than the stale period starts nothing, and accepted late it arrives whole;
 * 2. a transfer tox 1 paused stays paused for longer than the stale period;
 * 3. a transfer tox 1 accepted whose chunks never came is still asked again from where tox 1 is, so the stale step
 *    keeps doing its job for a transfer that is owed data.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "../testing/misc_tools.h"
#include "../toxcore/ccompat.h"
#include "../toxcore/tox.h"
#include "check_compat.h"

typedef struct State {
    uint8_t file_id[TOX_FILE_ID_LENGTH];

    /* tox 0, the sender */
    uint32_t resumes;
    uint32_t pauses;
    uint32_t cancels;
    uint32_t chunk_requests;
    bool drop_chunks;
    uint32_t dropped_chunks;
    bool asked_again;
    bool sent_all;

    /* tox 1, the receiver */
    uint32_t offers;
    uint32_t file_number;
    uint64_t received;
    bool received_all;
} State;

#include "auto_test_support.h"

/* STALE_CYCLES_ON_FTV2_RECEIVER in Messenger.c, and room for the stale step of an unfixed toxcore to fire. */
#define STALE_WAIT_ITERATIONS (2000 + 1000)
/* Long enough for whatever the sender had queued before it saw a pause to arrive. */
#define QUIET_ITERATIONS 200
#define MAX_ITERATIONS 20000

#define SMALL_FILE_SIZE (64 * 1024)
/* Far more than can be on its way when the receiver pauses, so the transfer cannot finish by itself. */
#define LARGE_FILE_SIZE (64 * 1024 * 1024)
#define PAUSE_AFTER (256 * 1024)

static uint8_t file_byte(uint64_t position)
{
    return (uint8_t)(position % 251);
}

static void sender_file_recv_control(Tox *tox, uint32_t friend_number, uint32_t file_number, Tox_File_Control control,
                                     void *user_data)
{
    const AutoTox *autotox = (const AutoTox *)user_data;
    State *state = (State *)autotox->state;

    if (control == TOX_FILE_CONTROL_RESUME) {
        ++state->resumes;
    } else if (control == TOX_FILE_CONTROL_PAUSE) {
        ++state->pauses;
    } else if (control == TOX_FILE_CONTROL_CANCEL) {
        ++state->cancels;
    }
}

static void sender_file_chunk_request(Tox *tox, uint32_t friend_number, uint32_t file_number, uint64_t position,
                                      size_t length, void *user_data)
{
    const AutoTox *autotox = (const AutoTox *)user_data;
    State *state = (State *)autotox->state;

    if (length == 0) {
        state->sent_all = true;
        return;
    }

    ++state->chunk_requests;

    if (state->drop_chunks) {
        if (position == 0 && state->dropped_chunks > 0) {
            /* The receiver's stale step sought back to where it is: from here on the chunks go out. */
            state->drop_chunks = false;
            state->asked_again = true;
        } else {
            ++state->dropped_chunks;
            return;
        }
    }

    /* An FTV2 chunk goes out with the file id in front of the data. */
    uint8_t *chunk = (uint8_t *)malloc(TOX_FILE_ID_LENGTH + length);
    ck_assert(chunk != nullptr);
    memcpy(chunk, state->file_id, TOX_FILE_ID_LENGTH);

    for (size_t i = 0; i < length; ++i) {
        chunk[TOX_FILE_ID_LENGTH + i] = file_byte(position + i);
    }

    Tox_Err_File_Send_Chunk err;
    tox_file_send_chunk(tox, friend_number, file_number, position, chunk, TOX_FILE_ID_LENGTH + length, &err);
    free(chunk);
    ck_assert_msg(err == TOX_ERR_FILE_SEND_CHUNK_OK, "could not send the chunk at %llu: error %d",
                  (unsigned long long)position, err);
}

static void receiver_file_recv(Tox *tox, uint32_t friend_number, uint32_t file_number, uint32_t kind,
                               uint64_t file_size, const uint8_t *filename, size_t filename_length, void *user_data)
{
    const AutoTox *autotox = (const AutoTox *)user_data;
    State *state = (State *)autotox->state;

    ck_assert_msg(kind == TOX_FILE_KIND_FTV2, "the offer is of kind %u, not FTV2", kind);
    ++state->offers;
    state->file_number = file_number;
    /* Nothing else: the offer waits, as it does in an app until the person taps it. */
}

static void receiver_file_recv_chunk(Tox *tox, uint32_t friend_number, uint32_t file_number, uint64_t position,
                                     const uint8_t *data, size_t length, void *user_data)
{
    const AutoTox *autotox = (const AutoTox *)user_data;
    State *state = (State *)autotox->state;

    if (length == 0) {
        state->received_all = true;
        return;
    }

    /* An FTV2 chunk reaches the client with the file id in front of the data. */
    ck_assert_msg(length > TOX_FILE_ID_LENGTH, "a chunk of %u bytes carries no data", (unsigned)length);
    ck_assert_msg(memcmp(data, state->file_id, TOX_FILE_ID_LENGTH) == 0, "a chunk of another file");
    ck_assert_msg(position == state->received, "a chunk at %llu, expected one at %llu",
                  (unsigned long long)position, (unsigned long long)state->received);

    for (size_t i = TOX_FILE_ID_LENGTH; i < length; ++i) {
        const uint64_t at = position + (i - TOX_FILE_ID_LENGTH);
        ck_assert_msg(data[i] == file_byte(at), "corrupt byte at %llu", (unsigned long long)at);
    }

    state->received += length - TOX_FILE_ID_LENGTH;
}

static void iterate(AutoTox *autotoxes, uint32_t iterations)
{
    for (uint32_t i = 0; i < iterations; ++i) {
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }
}

static void offer_file(AutoTox *autotoxes, uint8_t tag, uint64_t size)
{
    State *sender = (State *)autotoxes[0].state;
    State *receiver = (State *)autotoxes[1].state;

    memset(sender, 0, sizeof(State));
    memset(receiver, 0, sizeof(State));
    memset(sender->file_id, tag, TOX_FILE_ID_LENGTH);
    memcpy(receiver->file_id, sender->file_id, TOX_FILE_ID_LENGTH);

    Tox_Err_File_Send err;
    tox_file_send(autotoxes[0].tox, 0, TOX_FILE_KIND_FTV2, size, sender->file_id, (const uint8_t *)"ftv2.bin",
                  sizeof("ftv2.bin") - 1, &err);
    ck_assert_msg(err == TOX_ERR_FILE_SEND_OK, "tox_file_send failed: error %d", err);

    for (uint32_t i = 0; receiver->offers == 0; ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "the offer never reached the receiver");
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    ck_assert(receiver->offers == 1);
}

static void control_file(AutoTox *autotoxes, Tox_File_Control control)
{
    const State *receiver = (const State *)autotoxes[1].state;

    Tox_Err_File_Control err;
    tox_file_control(autotoxes[1].tox, 0, receiver->file_number, control, &err);
    ck_assert_msg(err == TOX_ERR_FILE_CONTROL_OK, "tox_file_control(%d) failed: error %d", control, err);
}

static void wait_for_whole_file(AutoTox *autotoxes, uint64_t size)
{
    const State *sender = (const State *)autotoxes[0].state;
    const State *receiver = (const State *)autotoxes[1].state;

    for (uint32_t i = 0; !(receiver->received_all && sender->sent_all); ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "the file did not arrive: %llu of %llu bytes",
                      (unsigned long long)receiver->received, (unsigned long long)size);
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    ck_assert_msg(receiver->received == size, "%llu of %llu bytes arrived",
                  (unsigned long long)receiver->received, (unsigned long long)size);
}

static void test_offer_waiting_for_the_person(AutoTox *autotoxes)
{
    const State *sender = (const State *)autotoxes[0].state;
    const State *receiver = (const State *)autotoxes[1].state;

    printf("1. an FTV2 offer the receiver leaves alone starts nothing\n");
    offer_file(autotoxes, 1, SMALL_FILE_SIZE);
    iterate(autotoxes, STALE_WAIT_ITERATIONS);

    ck_assert_msg(sender->resumes == 0 && sender->chunk_requests == 0,
                  "an offer nobody accepted was started: the sender got %u resume(s) and %u chunk request(s)",
                  sender->resumes, sender->chunk_requests);

    printf("   accepted late, it arrives whole\n");
    control_file(autotoxes, TOX_FILE_CONTROL_RESUME);
    wait_for_whole_file(autotoxes, SMALL_FILE_SIZE);
    ck_assert_msg(sender->resumes == 1, "the sender got %u resume(s) for one accept", sender->resumes);
    ck_assert(receiver->offers == 1);
}

static void test_transfer_paused_by_the_receiver(AutoTox *autotoxes)
{
    const State *sender = (const State *)autotoxes[0].state;
    const State *receiver = (const State *)autotoxes[1].state;

    printf("2. an FTV2 transfer the receiver paused stays paused\n");
    offer_file(autotoxes, 2, LARGE_FILE_SIZE);
    control_file(autotoxes, TOX_FILE_CONTROL_RESUME);

    for (uint32_t i = 0; receiver->received < PAUSE_AFTER; ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "only %llu bytes arrived before the pause",
                      (unsigned long long)receiver->received);
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    control_file(autotoxes, TOX_FILE_CONTROL_PAUSE);

    for (uint32_t i = 0; sender->pauses == 0; ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "the pause never reached the sender");
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }

    uint64_t arrived = receiver->received;

    for (uint32_t i = 0, quiet = 0; quiet < QUIET_ITERATIONS; ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "data kept arriving after the sender saw the pause");
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
        quiet = receiver->received == arrived ? quiet + 1 : 0;
        arrived = receiver->received;
    }

    ck_assert_msg(!receiver->received_all, "the whole file arrived before the pause; make LARGE_FILE_SIZE larger");

    const uint32_t resumes = sender->resumes;
    const uint32_t chunk_requests = sender->chunk_requests;
    iterate(autotoxes, STALE_WAIT_ITERATIONS);

    ck_assert_msg(sender->resumes == resumes && sender->chunk_requests == chunk_requests,
                  "a transfer the receiver paused was resumed: the sender got %u more resume(s) and %u more chunk "
                  "request(s)", sender->resumes - resumes, sender->chunk_requests - chunk_requests);
    ck_assert_msg(receiver->received == arrived, "%llu bytes reached a paused transfer",
                  (unsigned long long)(receiver->received - arrived));

    control_file(autotoxes, TOX_FILE_CONTROL_CANCEL);

    for (uint32_t i = 0; sender->cancels == 0; ++i) {
        ck_assert_msg(i < MAX_ITERATIONS, "the cancel never reached the sender");
        iterate_all_wait(autotoxes, 2, ITERATION_INTERVAL);
    }
}

static void test_accepted_transfer_without_data(AutoTox *autotoxes)
{
    State *sender = (State *)autotoxes[0].state;

    printf("3. an accepted FTV2 transfer whose chunks never came is asked again\n");
    offer_file(autotoxes, 3, SMALL_FILE_SIZE);
    sender->drop_chunks = true;
    control_file(autotoxes, TOX_FILE_CONTROL_RESUME);
    wait_for_whole_file(autotoxes, SMALL_FILE_SIZE);
    ck_assert_msg(sender->asked_again && sender->dropped_chunks > 0,
                  "the receiver never asked again for the %u chunk(s) that did not come", sender->dropped_chunks);
}

static void ftv2_stale_accept_test(AutoTox *autotoxes)
{
    tox_callback_file_recv_control(autotoxes[0].tox, sender_file_recv_control);
    tox_callback_file_chunk_request(autotoxes[0].tox, sender_file_chunk_request);
    tox_callback_file_recv(autotoxes[1].tox, receiver_file_recv);
    tox_callback_file_recv_chunk(autotoxes[1].tox, receiver_file_recv_chunk);

    test_offer_waiting_for_the_person(autotoxes);
    test_transfer_paused_by_the_receiver(autotoxes);
    test_accepted_transfer_without_data(autotoxes);
}

int main(void)
{
    setvbuf(stdout, nullptr, _IONBF, 0);

    Run_Auto_Options options = default_run_auto_options();
    options.graph = GRAPH_LINEAR;

    run_auto_test(nullptr, 2, ftv2_stale_accept_test, sizeof(State), &options);

    return 0;
}
