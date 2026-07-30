#!/usr/bin/env python3
# KHANDAQ (#201): NGC group media transfers stall (receiver stuck at a few %). Root cause is in
# toxcore's strictly-ordered lossless group stream: a single lost fragment head-of-line-blocks
# everything behind it, and the retransmit routine gcc_resend_packets() only resends an un-acked
# packet when its age in seconds is an exact power of 2 (delta = 2,4,8,16,...). So the ONE packet
# that is blocking the whole ordered stream is retried at 2s, then 4s, 8s, 16s — media transfers
# stall for seconds per loss.
#
# Fix (bounded, low blast-radius): resend the HEAD-of-line un-acked packet (i == send_array_start)
# EVERY second instead of only at power-of-2 boundaries, so the ordered stream un-blocks quickly on
# loss. All OTHER un-acked packets keep the exponential (power-of-2) backoff, so total resend traffic
# only grows by at most ~1 packet/second per connection — no congestion blow-up.
#
# Context-independent substring replace (robust to line drift), same convention as
# apply_khandaq_patch.py. Run during the native build against a freshly-cloned toxcore tree:
#   python3 apply_khandaq_media_resend.py toxcore/group_connection.c
#
# NOTE: this is a NATIVE toxcore transport change (affects ALL group lossless traffic — messages,
# media, calls signalling, history sync). It MUST be built into a release .so on x86_64 Linux/CI
# (the Docker build here time-kills the full 4-ABI set) AND field-verified on TWO real devices with
# a real media transfer on a lossy network (confirm faster recovery + NO regression to group
# calls/history-sync) BEFORE shipping to production. Do not ship blind.
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "toxcore/group_connection.c"
src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

if "Khandaq fix (#201)" in src:
    print("apply_khandaq_media_resend: already patched")
    sys.exit(0)

ORIG = ("        const uint64_t delta = array_entry->last_send_try - array_entry->time_added;\n"
        "        array_entry->last_send_try = tm;\n"
        "\n"
        "        /* if this occurrs less than once per second this won't be reliable */\n"
        "        if (delta > 1 && is_power_of_2(delta)) {\n"
        "            gcc_encrypt_and_send_lossless_packet(chat, gconn, array_entry->data, array_entry->data_length,\n"
        "                                                 array_entry->message_id, array_entry->packet_type);\n"
        "        }")

REPL = ("        const uint64_t delta = array_entry->last_send_try - array_entry->time_added;\n"
        "        array_entry->last_send_try = tm;\n"
        "\n"
        "        /* Khandaq fix (#201): the oldest un-acked packet (i == send_array_start) head-of-line-\n"
        "         * blocks the whole strictly-ordered lossless stream, so one lost media fragment stalls the\n"
        "         * transfer for 2^n seconds under the power-of-2 backoff. Resend the HEAD every second so\n"
        "         * recovery isn't clamped to ~1 packet / 2^n s; the rest keep exponential backoff. */\n"
        "        const bool khq_is_head = (i == start);\n"
        "        /* if this occurrs less than once per second this won't be reliable */\n"
        "        if ((khq_is_head && delta >= 1) || (delta > 1 && is_power_of_2(delta))) {\n"
        "            gcc_encrypt_and_send_lossless_packet(chat, gconn, array_entry->data, array_entry->data_length,\n"
        "                                                 array_entry->message_id, array_entry->packet_type);\n"
        "        }")

if ORIG not in src:
    sys.stderr.write("apply_khandaq_media_resend: ORIGINAL gcc_resend_packets resend block NOT FOUND in "
                     + path + " -- branch may have changed; aborting so the build does not ship unpatched.\n")
    sys.exit(1)

src = src.replace(ORIG, REPL, 1)
open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
print("apply_khandaq_media_resend: patched " + path + " OK")
