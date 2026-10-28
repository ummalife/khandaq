#!/usr/bin/env python3
# KHANDAQ (join by ID): let a friend's invite finish a join by group ID.
#
# tox_group_join() adds the group at once, before any peer has answered. When the DHT announce never
# completes, the join just sits there. The app asks every online friend for an invite (lossless
# packet 184, HelperGroup.send_group_invite_request_to_friends) and the friends answer, but Messenger
# dropped each answer because the group was "already added", so this fallback could never work.
#
# The same two changes the iOS copy of toxcore carries (khandaq-ios/local_pod_repo/toxcore, listed in
# install-tox-ngc.sh there):
# - Messenger.c passes an invite on when the group is known only by its ID: no shared state yet and no
#   founder key, see gc_group_known_only_by_id() (declared in group_chats.h).
# - gc_accept_invite() deletes the chat with the invite's ID that the accepted invite replaces: one
#   joined by ID that no peer has answered, or one being left. Either stayed ahead of the new chat in
#   gc_get_group_by_public_key(), so the inviter's confirmation reached the old chat and was dropped.
#   A left chat is only deleted on the next do_gc(), and the confirmation arrives before that.
#
# Context-independent substring replace (robust to line drift), same convention as the other
# apply_khandaq_*.py scripts. A target that is missing or not unique fails the build, so an .so
# cannot ship without the change. Run during the native build against a freshly-cloned toxcore tree:
#   python3 apply_khandaq_join_by_id.py toxcore
import os
import sys

MARKER = "KHANDAQ (join by ID)"

src_dir = sys.argv[1] if len(sys.argv) > 1 else "toxcore"

# --- Messenger.c: the invite filter ---
ORIG_FILTER = ("                if (group_not_added(m->group_handler, join_data, join_data_len)) {\n"
               "                    m->group_invite(m, i, join_data, GC_JOIN_DATA_LENGTH,\n")

REPL_FILTER = ("                // KHANDAQ (join by ID): also pass an invite to a group we know only by its ID, see\n"
               "                // gc_group_known_only_by_id().\n"
               "                if (group_not_added(m->group_handler, join_data, join_data_len)\n"
               "                        || gc_group_known_only_by_id(m->group_handler, join_data, join_data_len)) {\n"
               "                    m->group_invite(m, i, join_data, GC_JOIN_DATA_LENGTH,\n")

# --- group_chats.h: declare the helper next to group_not_added() ---
ORIG_DECL = "bool group_not_added(const GC_Session *c, const uint8_t *chat_id, uint32_t length);\n"

REPL_DECL = (ORIG_DECL
             + "\n"
             "/** @brief Return true if `chat_id` belongs to a group we know only by its ID.\n"
             " *\n"
             " * KHANDAQ (join by ID): tox_group_join() adds the group before any peer has answered, so a friend's\n"
             " * invite to it was dropped as \"already added\" and a join the DHT announce never completed could not\n"
             " * be finished by a friend either. Such a group has no shared state yet (version 0) and no founder\n"
             " * key, so taking an invite in its place loses nothing. A group with any shared state, or one we\n"
             " * founded, is not matched and its invites stay filtered. Android: patches/apply_khandaq_join_by_id.py.\n"
             " *\n"
             " * `length` must be at least CHAT_ID_SIZE bytes in length.\n"
             " */\n"
             "non_null()\n"
             "bool gc_group_known_only_by_id(const GC_Session *c, const uint8_t *chat_id, uint32_t length);\n")

# --- group_chats.c: the helper, defined after group_not_added() ---
ORIG_HELPER = ("bool group_not_added(const GC_Session *c, const uint8_t *chat_id, uint32_t length)\n"
               "{\n"
               "    if (length < CHAT_ID_SIZE) {\n"
               "        return false;\n"
               "    }\n"
               "\n"
               "    return !group_exists(c, chat_id);\n"
               "}\n")

REPL_HELPER = (ORIG_HELPER
               + "\n"
               "// KHANDAQ (join by ID): see the declaration in group_chats.h.\n"
               "bool gc_group_known_only_by_id(const GC_Session *c, const uint8_t *chat_id, uint32_t length)\n"
               "{\n"
               "    if (length < CHAT_ID_SIZE) {\n"
               "        return false;\n"
               "    }\n"
               "\n"
               "    static const uint8_t no_founder[EXT_PUBLIC_KEY_SIZE] = {0};\n"
               "\n"
               "    for (uint32_t i = 0; i < c->chats_index; ++i) {\n"
               "        const GC_Chat *chat = &c->chats[i];\n"
               "\n"
               "        if (chat->connection_state == CS_NONE) {\n"
               "            continue;\n"
               "        }\n"
               "\n"
               "        if (memcmp(get_chat_id(chat->chat_public_key), chat_id, CHAT_ID_SIZE) != 0) {\n"
               "            continue;\n"
               "        }\n"
               "\n"
               "        return chat->shared_state.version == 0\n"
               "               && memeq(chat->shared_state.founder_public_key, EXT_PUBLIC_KEY_SIZE, no_founder, EXT_PUBLIC_KEY_SIZE);\n"
               "    }\n"
               "\n"
               "    return false;\n"
               "}\n")

# --- group_chats.c: gc_accept_invite() replaces the stale chat with the invite's ID ---
ORIG_ACCEPT = ("    const uint8_t *chat_id = data;\n"
               "    const uint8_t *invite_chat_pk = data + CHAT_ID_SIZE;\n"
               "\n"
               "    const int group_number = create_new_group(c, nick, nick_length, false, GI_PUBLIC);\n")

REPL_ACCEPT = ("    const uint8_t *chat_id = data;\n"
               "    const uint8_t *invite_chat_pk = data + CHAT_ID_SIZE;\n"
               "\n"
               "    // KHANDAQ (join by ID): packets for the group find their chat by its ID, and the first chat with\n"
               "    // that ID wins. A chat we just left is only deleted on the next do_gc(), and one we joined by its ID\n"
               "    // that no peer has answered yet stays; either would take the inviter's confirmation and drop it, so\n"
               "    // the join never completed. Neither has anything worth keeping. Android: apply_khandaq_join_by_id.py.\n"
               "    GC_Chat *stale_chat = gc_get_group_by_public_key(c, chat_id);\n"
               "\n"
               "    if (stale_chat != nullptr && (stale_chat->flag_exit || !gc_group_is_valid(stale_chat))) {\n"
               "        group_delete(c, stale_chat);\n"
               "    }\n"
               "\n"
               "    const int group_number = create_new_group(c, nick, nick_length, false, GI_PUBLIC);\n")

PATCHES = (
    ("Messenger.c", ((ORIG_FILTER, REPL_FILTER),)),
    ("group_chats.h", ((ORIG_DECL, REPL_DECL),)),
    ("group_chats.c", ((ORIG_HELPER, REPL_HELPER), (ORIG_ACCEPT, REPL_ACCEPT))),
)

for name, edits in PATCHES:
    path = os.path.join(src_dir, name)
    src = open(path, "r", encoding="utf-8", errors="surrogateescape").read()

    if MARKER in src:
        print("apply_khandaq_join_by_id: " + path + " already patched")
        continue

    for orig, repl in edits:
        count = src.count(orig)
        if count != 1:
            sys.stderr.write("apply_khandaq_join_by_id: expected exactly one target in " + path + ", found "
                             + str(count) + " -- toxcore may have changed; aborting so the build does not"
                             " ship without the join-by-ID fix.\n")
            sys.exit(1)
        src = src.replace(orig, repl, 1)

    open(path, "w", encoding="utf-8", errors="surrogateescape").write(src)
    print("apply_khandaq_join_by_id: patched " + path + " OK")
