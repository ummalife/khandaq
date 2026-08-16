// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTObject.h"

/**
 * KHANDAQ (external audit #2, finding 1) — a small key/value table inside the profile database.
 *
 * The signed-history work needs to persist a handful of things that are not messages: this
 * profile's per-group signing key, which key we believe belongs to which peer, and which rows a
 * signature has proved. None of them fit an existing model, and giving each its own typed table
 * would mean a schema change per concept.
 *
 * WHY A NEW OBJECT RATHER THAN NEW PROPERTIES: adding a class is additive — Realm creates an empty
 * table and no existing row is touched, so the migration case is genuinely empty. Adding properties
 * to a table that already holds a user's messages is the shape of change that needs a backfill and
 * that has cost this project profiles before. The Android side stores exactly the same rows in its
 * own key/value table (g_opts) for the same reason, which also keeps the two ports readable
 * side by side.
 *
 * The secret key lives here, in the ENCRYPTED Realm — the same protection the profile's messages
 * get, and the mirror of Android keeping it inside its SQLCipher database.
 *
 * The key is the inherited uniqueIdentifier, so callers build namespaced strings
 * ("kqhsksec_<groupId>", "kqhskdir_<groupId>|<peerPub>", ...) exactly as Android does.
 */
@interface OCTNgcKeyValue : OCTObject

/** Opaque to this class; every consumer defines and parses its own encoding. */
@property NSString *value;

@end
