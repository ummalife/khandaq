#!/usr/bin/env python3
# KHANDAQ (FTV2 stale accept): toxcore leaves an FTV2 offer alone until the receiver accepts it.
#
# do_reqchunk_filecb() sends SEEK + ACCEPT to the sender of a receiving FTV2 transfer that has had no data for
# STALE_CYCLES_ON_FTV2_RECEIVER iterations (about 53 s on Android), in case the receiver's ACCEPT was lost while the
# sender was offline. Upstream did that for every receiving FTV2 transfer. An offer still waiting for the person was
# started by it: the sender sent the whole file, and the receiver dropped every chunk of a transfer it had not
# accepted, once a stale period for as long as the offer waited (QA run 7, Android to Android: a file the person had
# refused went out twice, 20 MB each time). A transfer the receiver had paused was resumed by it. The step now runs
# only for a transfer the receiver accepted and has not paused; the sender's resend of an FTV2 request nobody has
# acknowledged is unchanged.
#
# The desktop copy of toxcore (khandaq-desktop/buildscripts/toxcore, both amalgamations) and the iOS copy
# (khandaq-ios/local_pod_repo/toxcore) carry the same change; scripts/check-ftv2-stale-accept.py holds them together,
# and khandaq-desktop/buildscripts/toxcore/auto_tests/ftv2_stale_accept_test.c runs it on two local toxes.
#
# Context-independent substring replace (robust to line drift), same convention as the other
# apply_khandaq_*.py scripts. A target that is missing or not unique fails the build, so an .so
# cannot ship without the change. Run during the native build against a freshly-cloned toxcore tree:
#   python3 apply_khandaq_ftv2_stale_accept.py toxcore
import os
import sys

MARKER = "KHANDAQ (FTV2 stale accept)"

src_dir = sys.argv[1] if len(sys.argv) > 1 else "toxcore"

# --- Messenger.c: the helper, defined right before do_reqchunk_filecb() ---
ORIG_HELPER = ("non_null(1) nullable(3)\n"
               "static void do_reqchunk_filecb(Messenger *m, int32_t friendnumber, void *userdata)\n"
               "{\n")

REPL_HELPER = ("/** KHANDAQ (FTV2 stale accept): whether a receiving FTV2 transfer is owed data by its sender.\n"
               " *\n"
               " * do_reqchunk_filecb() sends SEEK + ACCEPT to the sender of a receiving FTV2 transfer that has had no data\n"
               " * for STALE_CYCLES_ON_FTV2_RECEIVER iterations, in case our ACCEPT was lost while the sender was offline.\n"
               " * Upstream did that for every receiving FTV2 transfer. An offer still waiting for the person\n"
               " * (FILESTATUS_NOT_ACCEPTED) was started by it: the sender sent the whole file, and PACKET_ID_FILE_DATA\n"
               " * dropped every chunk of a transfer that is not FILESTATUS_TRANSFERRING, once a stale period (about a minute\n"
               " * on Android) for as long as the offer waited. A transfer we had paused was resumed by it. Only a transfer\n"
               " * we accepted and have not paused is owed data; the others get nothing from toxcore by itself.\n"
               " */\n"
               "non_null()\n"
               "static bool ftv2_receiver_expects_data(const struct File_Transfers *ft)\n"
               "{\n"
               "    return ft->status == FILESTATUS_TRANSFERRING && (ft->paused & FILE_PAUSE_US) == 0;\n"
               "}\n"
               "\n"
               + ORIG_HELPER)

# --- Messenger.c: do_reqchunk_filecb(), the stale-receiver step ---
ORIG_STALE = ("                if (ft->file_type == FILEKIND_FTV2) {\n"
              "                    if (ft->file_receiver_last_received_chunk_this_many_iterations_ago"
              " > (uint32_t)STALE_CYCLES_ON_FTV2_RECEIVER) {\n")

REPL_STALE = ("                if (ft->file_type == FILEKIND_FTV2) {\n"
              "                    if (!ftv2_receiver_expects_data(ft)) {\n"
              "                        // KHANDAQ (FTV2 stale accept): a transfer waiting for the person, or one we paused,\n"
              "                        // is owed nothing and is not stale; its count starts when it is owed data again.\n"
              "                        ft->file_receiver_last_received_chunk_this_many_iterations_ago = 0;\n"
              "                    } else if (ft->file_receiver_last_received_chunk_this_many_iterations_ago"
              " > (uint32_t)STALE_CYCLES_ON_FTV2_RECEIVER) {\n")

EDITS = (
    (ORIG_HELPER, REPL_HELPER),
    (ORIG_STALE, REPL_STALE),
)

path = os.path.join(src_dir, "Messenger.c")
src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

if MARKER in src:
    print("apply_khandaq_ftv2_stale_accept: " + path + " already patched")
    sys.exit(0)

for orig, repl in EDITS:
    count = src.count(orig)
    if count != 1:
        sys.stderr.write("apply_khandaq_ftv2_stale_accept: expected exactly one target in " + path + ", found "
                         + str(count) + " -- toxcore may have changed; aborting so the build does not ship a .so"
                         " that starts every FTV2 offer it was not asked to\n")
        sys.exit(1)
    src = src.replace(orig, repl, 1)

open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
print("apply_khandaq_ftv2_stale_accept: patched " + path + " OK")
