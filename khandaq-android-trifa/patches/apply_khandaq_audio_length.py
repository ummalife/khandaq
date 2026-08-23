#!/usr/bin/env python3
# KHANDAQ (2026-08-23): check the audio payload length before reading four bytes out of it.
#
# An audio RTP payload is four bytes of sampling rate followed by an Opus frame. Nothing on the path
# from the network enforces that: handle_rtp_packet_for_session takes data_length_lower straight from
# the attacker-controlled header, ac_queue_message checks only the payload type, and jbuf_write only
# sequence numbers. In ac_iterate the reads then happen unconditionally:
#
#     memcpy(&ac->lp_sampling_rate, msg->data, 4);
#     ac->lp_channel_count = opus_packet_get_nb_channels(msg->data + 4);
#     rc = opus_decode(ac->decoder, msg->data + 4, msg->len - 4, ...);
#
# With msg->len == 1 that reads four bytes out of a one-byte payload and hands opus_decode a length
# of -3. One packet, from a contact already in a call.
#
# WHY THIS EXISTS AS A PATCH. The same defect was fixed by editing the vendored copies for iOS
# (audio.m, which the pod compiles from this repository) and for the desktop. Android does NOT use a
# vendored copy — circle_scripts/deps.sh clones zoff99/c-toxcore fresh at a pinned commit — so an
# edit in this repository would never reach the shipped .so. This is the same mechanism the libvpx
# CVE backport uses, and for the same reason.
#
# Context-independent substring replace, same convention as apply_khandaq_media_resend.py. Run during
# the native build against a freshly-cloned toxcore tree:
#   python3 apply_khandaq_audio_length.py toxav/audio.c
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "toxav/audio.c"
src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

MARKER = "KHANDAQ audio length guard"

if MARKER in src:
    print("apply_khandaq_audio_length: already patched")
    sys.exit(0)

# The read that must be guarded. Upstream wraps it in `if (msg) {`, so the guard goes inside that
# block, immediately before the first read.
NEEDLE = """            if (msg) {
                memcpy(&ac->lp_sampling_rate, msg->data, 4);"""

GUARD = """            if (msg) {
                /* %s: an audio payload is four bytes of sampling rate plus an Opus
                 * frame, so four bytes or fewer carries no frame. Nothing upstream of here checks
                 * it — ac_queue_message looks only at the payload type — and the reads below take
                 * four bytes plus a fifth out of msg->data while msg->len - 4 goes to opus_decode
                 * as a negative length. */
                if (msg->len <= 4) {
                    free(msg);
                    msg = nullptr;
                    continue;
                }

                memcpy(&ac->lp_sampling_rate, msg->data, 4);""" % MARKER

count = src.count(NEEDLE)
if count != 1:
    # Fail loudly. A silent skip here means the shipped .so is built without the fix while the build
    # reports success — exactly the failure mode the libvpx patch site calls out.
    print("apply_khandaq_audio_length: expected exactly one occurrence of the guarded read in %s, "
          "found %d. The upstream file changed shape; fix this patch rather than skipping it."
          % (path, count), file=sys.stderr)
    sys.exit(1)

src = src.replace(NEEDLE, GUARD, 1)
open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
print("apply_khandaq_audio_length: patched %s" % path)
