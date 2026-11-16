#!/usr/bin/env python3
# KHANDAQ (group search key): look a group's announces up on the DHT by its chat id.
#
# m_create_group_connection() gives every group an onion friend with a random key, and onion_client walks
# the DHT toward that key. Each member of a group therefore announced to, and searched, a different part
# of the DHT, and members met only when they happened to ask the same node on the way. A group whose only
# online member is an always-online founder (the Khandaq Community bot) could not be joined by its chat id:
# on the public DHT a fresh join never found the founder in 5 minutes. With the chat id as the search key
# every member of a group walks to the same nodes, and the same join connects in about 30 seconds.
#
# The desktop copy of toxcore (khandaq-desktop/buildscripts/toxcore, both amalgamations) and the iOS copy
# (khandaq-ios/local_pod_repo/toxcore) carry the same change. A friend is still looked up by its real
# public key; only an onion friend that belongs to a group (is_groupchat) changes.
#
# Context-independent substring replace (robust to line drift), same convention as the other
# apply_khandaq_*.py scripts. A target that is missing or not unique fails the build, so an .so
# cannot ship without the change. Run during the native build against a freshly-cloned toxcore tree:
#   python3 apply_khandaq_group_search_key.py toxcore
import os
import sys

MARKER = "KHANDAQ (group search key)"

src_dir = sys.argv[1] if len(sys.argv) > 1 else "toxcore"

# --- onion_client.c: the helper, defined after onion_friend_is_groupchat() ---
ORIG_HELPER = ("bool onion_friend_is_groupchat(const Onion_Friend *const onion_friend)\n"
               "{\n"
               "    return onion_friend->is_groupchat;\n"
               "}\n")

REPL_HELPER = (ORIG_HELPER
               + "\n"
               + "/** KHANDAQ (group search key): the key this contact is looked up by on the DHT.\n"
               + " *\n"
               + " * A group's onion friend is created with a random key (m_create_group_connection), and upstream walks the\n"
               + " * DHT toward that key. Every member of a group therefore announced to, and searched, a different part of\n"
               + " * the DHT, and members met only when they happened to ask the same node on the way. A group with one\n"
               + " * always-online member and nobody else (the Khandaq Community founder) could not be joined by its chat id\n"
               + " * at all. Walking toward the chat id sends every member of a group to the same nodes, so a join by chat id\n"
               + " * finds the members that are online. A friend is still looked up by its real public key.\n"
               + " */\n"
               + "non_null()\n"
               + "static const uint8_t *onion_friend_search_key(const Onion_Friend *onion_friend)\n"
               + "{\n"
               + "    return onion_friend->is_groupchat ? onion_friend->gc_public_key : onion_friend->real_public_key;\n"
               + "}\n")

# --- onion_client.c: client_send_announce_request(), a group before its announce data is set ---
ORIG_FRIEND_REQUEST = ("                      onion_c->rng, request, sizeof(request), dest_pubkey, onion_friend->temp_public_key,\n"
                       "                      onion_friend->temp_secret_key, ping_id, onion_friend->real_public_key,\n"
                       "                      zero_ping_id, sendback);")

REPL_FRIEND_REQUEST = ("                      onion_c->rng, request, sizeof(request), dest_pubkey, onion_friend->temp_public_key,\n"
                       "                      onion_friend->temp_secret_key, ping_id, onion_friend_search_key(onion_friend),\n"
                       "                      zero_ping_id, sendback);")

# --- onion_client.c: client_send_announce_request(), a group with its announce data ---
ORIG_GC_REQUEST = ("                      onion_friend->temp_secret_key, ping_id, onion_friend->real_public_key,\n"
                   "                      zero_ping_id, sendback, onion_friend->gc_data,")

REPL_GC_REQUEST = ("                      onion_friend->temp_secret_key, ping_id, onion_friend_search_key(onion_friend),\n"
                   "                      zero_ping_id, sendback, onion_friend->gc_data,")

# --- onion_client.c: client_add_to_list() and client_ping_nodes() sort the nodes by the same key ---
ORIG_REFERENCE = "        reference_id = onion_c->friends_list[num - 1].real_public_key;\n"
REPL_REFERENCE = "        reference_id = onion_friend_search_key(&onion_c->friends_list[num - 1]);\n"

EDITS = (
    (ORIG_HELPER, REPL_HELPER, 1),
    (ORIG_FRIEND_REQUEST, REPL_FRIEND_REQUEST, 1),
    (ORIG_GC_REQUEST, REPL_GC_REQUEST, 1),
    (ORIG_REFERENCE, REPL_REFERENCE, 2),
)

path = os.path.join(src_dir, "onion_client.c")
src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

if MARKER in src:
    print("apply_khandaq_group_search_key: " + path + " already patched")
    sys.exit(0)

for orig, repl, expected in EDITS:
    count = src.count(orig)
    if count != expected:
        sys.stderr.write("apply_khandaq_group_search_key: expected " + str(expected) + " target(s) in " + path
                         + ", found " + str(count) + " -- toxcore may have changed; aborting so the build does not"
                         + " ship a .so that cannot join a group by its chat id\n")
        sys.exit(1)
    src = src.replace(orig, repl)

open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
print("apply_khandaq_group_search_key: patched " + path + " OK")
