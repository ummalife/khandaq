#!/usr/bin/env python3
# KHANDAQ (audit F2): NGC group video decodes attacker-supplied H.264 with ffmpeg's software decoder
# and no pixel-format restriction. toxav_ngc_video_decode() copies the DECODER's chroma stride into
# the caller's u/v buffers, which are only (width/2)*(height/2) bytes. A group peer that sends a
# High 4:4:4 Predictive SPS (chroma_format_idc=3) makes ffmpeg emit YUV444P with
# linesize[1]=linesize[2]=width, so the two chroma memcpys write (height/2)*width bytes each --
# on Android that is 163840 bytes into an 81920 byte pinned Java array. 80 KB heap overflow, twice,
# reachable from any peer in a public group. 4:2:2 and 10 bit SPS are the same class of bug.
#
# Fix (receiver-side only, no wire change): drop the frame unless it is exactly what the caller
# allocated for -- 8 bit 4:2:0 (YUV420P / YUVJ420P, the full-range variant ffmpeg's h264 decoder
# still emits) with a chroma stride that fits width/2. The NV12 sibling branch de-interleaves
# instead of memcpy'ing, so there we bound what that loop actually writes (and, while we are here,
# port the planar branch's stride/height guard into it -- audit A29, which only ever landed in the
# desktop copy of this file).
#
# The Android CI clones upstream c-toxcore fresh (circle_scripts/deps.sh), so the desktop tree's copy
# of toxav.c never reaches the shipped .so -- this patch is how the fix gets built.
#
# Context-independent substring replace (robust to line drift), same convention as
# apply_khandaq_media_resend.py. Run during the native build against a freshly-cloned toxcore tree:
#   python3 apply_khandaq_toxav_ngc_chroma.py toxav/toxav.c
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "toxav/toxav.c"
src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

if "KHANDAQ (audit F2)" in src:
    print("apply_khandaq_toxav_ngc_chroma: already patched")
    sys.exit(0)

# --- planar branch (YUV420P & friends): bound the two chroma memcpys ---
ORIG_PLANAR = ("                if ((width < frame->linesize[0]) || (height != frame->height)) {\n"
               "                    // log error: video frame stride and height do no match input buffer stride and height\n"
               "                    //printf(\"toxav_ngc_video_decode:error:012\\n\");\n"
               "                    av_frame_free(&frame);\n"
               "                    continue;\n"
               "                } else {\n"
               "                    *ystride = frame->linesize[0];\n"
               "                    *ustride = frame->linesize[1];\n"
               "                    *vstride = frame->linesize[2];")

REPL_PLANAR = ("                if ((width < frame->linesize[0]) || (height != frame->height)) {\n"
               "                    // log error: video frame stride and height do no match input buffer stride and height\n"
               "                    //printf(\"toxav_ngc_video_decode:error:012\\n\");\n"
               "                    av_frame_free(&frame);\n"
               "                    continue;\n"
               "                } else if (((frame->format != AV_PIX_FMT_YUV420P) && (frame->format != AV_PIX_FMT_YUVJ420P))\n"
               "                        || (frame->linesize[1] < 1) || (frame->linesize[2] < 1)\n"
               "                        || (frame->linesize[1] > (width / 2)) || (frame->linesize[2] > (width / 2))) {\n"
               "                    // KHANDAQ (audit F2): the caller's u/v buffers are only (width/2)*(height/2) bytes, but the\n"
               "                    // memcpys below use the DECODER's chroma stride. A peer sending a 4:4:4 (or 4:2:2 / 10 bit) SPS\n"
               "                    // makes ffmpeg emit chroma planes as wide as the luma plane, so we would copy far past both\n"
               "                    // buffers (heap overflow). Only accept 8 bit 4:2:0 with a chroma stride that actually fits.\n"
               "                    //printf(\"toxav_ngc_video_decode:error:012a\\n\");\n"
               "                    av_frame_free(&frame);\n"
               "                    continue;\n"
               "                } else {\n"
               "                    *ystride = frame->linesize[0];\n"
               "                    *ustride = frame->linesize[1];\n"
               "                    *vstride = frame->linesize[2];")

# --- NV12 branch: de-interleave loop, bound what it writes (u, and v which starts one byte in) ---
ORIG_NV12 = ("            } else if ((frame->format == 23) && (frame->linesize[0] > 1)\n"
             "                    && (frame->linesize[1] > 1) && (frame->data[0]) && (frame->data[1])) {\n"
             "                    *ystride = frame->linesize[0];\n"
             "                    *ustride = frame->linesize[1] / 2;")

REPL_NV12 = ("            } else if ((frame->format == 23) && (frame->linesize[0] > 1)\n"
             "                    && (frame->linesize[1] > 1) && (frame->data[0]) && (frame->data[1])) {\n"
             "                    // KHANDAQ (audit A29): same bounds guard as the planar branch - reject a peer frame\n"
             "                    // whose stride/height exceed the caller's y/u/v buffers before the memcpy (heap overflow).\n"
             "                    if ((width < frame->linesize[0]) || (height != frame->height)) {\n"
             "                        av_frame_free(&frame);\n"
             "                        continue;\n"
             "                    }\n"
             "                    // KHANDAQ (audit F2): the de-interleave loop below writes (height/2) rows of (linesize[1]/2)\n"
             "                    // bytes into u, and the same amount into v starting one byte in, while both buffers are only\n"
             "                    // (width/2)*(height/2) bytes. A peer frame with a wide chroma stride overflows them, so bound\n"
             "                    // what this branch actually copies.\n"
             "                    if ((((frame->height / 2) * (frame->linesize[1] / 2)) + 1) > ((width / 2) * (height / 2))) {\n"
             "                        av_frame_free(&frame);\n"
             "                        continue;\n"
             "                    }\n"
             "                    *ystride = frame->linesize[0];\n"
             "                    *ustride = frame->linesize[1] / 2;")

if ORIG_PLANAR not in src:
    sys.stderr.write("apply_khandaq_toxav_ngc_chroma: ORIGINAL planar decode block NOT FOUND in "
                     + path + " -- branch may have changed; aborting so the build does not ship unpatched.\n")
    sys.exit(1)

if ORIG_NV12 not in src:
    sys.stderr.write("apply_khandaq_toxav_ngc_chroma: ORIGINAL NV12 decode block NOT FOUND in "
                     + path + " -- branch may have changed; aborting so the build does not ship unpatched.\n")
    sys.exit(1)

src = src.replace(ORIG_PLANAR, REPL_PLANAR, 1)
src = src.replace(ORIG_NV12, REPL_NV12, 1)
open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
print("apply_khandaq_toxav_ngc_chroma: patched " + path + " OK")
