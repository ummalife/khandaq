#!/usr/bin/env python3
# Context-independent application of the Khandaq NGC pure-TCP announce fix to toxcore/Messenger.c.
# Unlike `git apply`, this does an exact-substring replacement, so it is robust to line-number /
# surrounding-context drift in the freshly-cloned zoff99/zoxcore_local_fork branch tip.
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "toxcore/Messenger.c"
src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

if "Khandaq fix" in src:
    print("apply_khandaq_patch: already patched")
    sys.exit(0)

ORIG = ("    const int tcp_num = tcp_copy_connected_relays(chat->tcp_conn, announce.base_announce.tcp_relays,\n"
        "                        GCA_MAX_ANNOUNCED_TCP_RELAYS);")

REPL = ("    int tcp_num = tcp_copy_connected_relays(chat->tcp_conn, announce.base_announce.tcp_relays,\n"
        "                        GCA_MAX_ANNOUNCED_TCP_RELAYS);\n"
        "\n"
        "    // Khandaq fix: a node whose group connection is still riding UDP (or just lacks group-local\n"
        "    // TCP links) often has 0 connected relays on chat->tcp_conn, so the announce would be published\n"
        "    // with tcp_relays=0. A UDP-blocked / TCP-only joiner can then never reach this peer. Fall back to\n"
        "    // the global net_crypto TCP relay pool so the announce always carries a reachable TCP relay.\n"
        "    if (tcp_num == 0) {\n"
        "        tcp_num = tcp_copy_connected_relays(nc_get_tcp_c(m->net_crypto), announce.base_announce.tcp_relays,\n"
        "                        GCA_MAX_ANNOUNCED_TCP_RELAYS);\n"
        "    }")

if ORIG not in src:
    sys.stderr.write("apply_khandaq_patch: ORIGINAL self_announce_group tcp_num statement NOT FOUND in "
                     + path + " -- branch may have changed; aborting so the build does not ship unpatched.\n")
    sys.exit(1)

src = src.replace(ORIG, REPL, 1)
open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
print("apply_khandaq_patch: patched " + path + " OK")
