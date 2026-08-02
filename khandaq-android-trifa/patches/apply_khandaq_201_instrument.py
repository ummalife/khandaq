#!/usr/bin/env python3
# KHANDAQ (#201 QA instrumentation): log every lossless retransmit in gcc_resend_packets to Android
# logcat (tag KHQ201) so the fix's timing can be MEASURED on the sender alone. Emits, per resend:
# msg_id, buffer index i, whether it's the head-of-line packet (i == send_array_start), and delta
# (seconds the packet has been un-acked). With the #201 fix applied, the head packet resends at
# delta = 1,2,3,4,... (every second) while non-head packets resend only at power-of-2 deltas
# (2,4,8,16) — a single build shows both behaviours side by side, proving the fix un-blocks the
# ordered lossless stream every second instead of on the exponential backoff.
#
# This is a QA/measurement-only patch. It requires apply_khandaq_media_resend.py to have run first
# (it keys off the "Khandaq fix (#201)" marker) and -llog to be added to the final .so link.
#
#   python3 apply_khandaq_201_instrument.py toxcore/group_connection.c
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "toxcore/group_connection.c"
src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

if "KHQ201 instrument" in src:
    print("apply_khandaq_201_instrument: already patched")
    sys.exit(0)

# 1) add the android/log.h include (once), right after the first #include line
inc_marker = "#include"
idx = src.find(inc_marker)
if idx == -1:
    print("apply_khandaq_201_instrument: no #include found — aborting", file=sys.stderr)
    sys.exit(1)
line_end = src.find("\n", idx)
src = src[:line_end + 1] + "#include <android/log.h> /* KHQ201 instrument */\n" + src[line_end + 1:]

# 2) insert the log call right after the resend send() inside the #201 fix if-block.
ORIG = ("        if ((khq_is_head && delta >= 1) || (delta > 1 && is_power_of_2(delta))) {\n"
        "            gcc_encrypt_and_send_lossless_packet(chat, gconn, array_entry->data, array_entry->data_length,\n"
        "                                                 array_entry->message_id, array_entry->packet_type);\n"
        "        }")

NEW = ("        if ((khq_is_head && delta >= 1) || (delta > 1 && is_power_of_2(delta))) {\n"
       "            gcc_encrypt_and_send_lossless_packet(chat, gconn, array_entry->data, array_entry->data_length,\n"
       "                                                 array_entry->message_id, array_entry->packet_type);\n"
       "            __android_log_print(6, \"KHQ201\", \"resend msg_id=%u i=%d head=%d delta=%llu\",\n"
       "                                (unsigned)array_entry->message_id, (int)i, (int)khq_is_head,\n"
       "                                (unsigned long long)delta); /* KHQ201 instrument */\n"
       "        }")

if ORIG not in src:
    print("apply_khandaq_201_instrument: ERROR — media-resend fix block not found; run "
          "apply_khandaq_media_resend.py first", file=sys.stderr)
    sys.exit(1)

src = src.replace(ORIG, NEW, 1)
open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
print("apply_khandaq_201_instrument: patched " + path + " OK")
