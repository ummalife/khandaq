// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * KHANDAQ (external audit #2, finding 1, step 2) — this profile's history-signing key (HSK).
 *
 * A history-sync packet names its author in bytes nobody signed, so the fix needs a key we sign
 * with. It is Ed25519 and cannot be the Tox identity key, which is Curve25519.
 *
 * ONE KEY PER GROUP, not one per profile. NGC deliberately gives a member a different public key in
 * every group; a single HSK announced everywhere would re-link the same person across any two groups
 * that share an observer. Fixing authorship must not quietly install a correlator.
 *
 * WHAT IT IS BOUND TO: the Tox public key of the profile that generated it, so a changed identity
 * re-mints (§4.1 — and Android's #244 proved the identity really can change under a live profile).
 * The owner row is written LAST and is the commit marker: a crash midway leaves the key looking
 * absent, and it is simply regenerated rather than half-loaded.
 *
 * The secret is never logged and never leaves the encrypted profile database.
 */
/** Row-key prefixes; the group identifier is appended by +rowKeyWithPrefix:groupId:. */
extern NSString *const kOCTNgcHskRowPrefixSec;
extern NSString *const kOCTNgcHskRowPrefixPub;
extern NSString *const kOCTNgcHskRowPrefixOwner;

@interface OCTNgcHskStore : NSObject

/** @return the row key, or nil when either part is unusable. */
+ (nullable NSString *)rowKeyWithPrefix:(NSString *)prefix groupId:(nullable NSString *)groupId;

/**
 * Should a fresh keypair be minted rather than the stored one reused?
 *
 * Pure, so every branch is unit-testable without a database. Fails towards regenerating: a key we
 * cannot fully trust is worthless anyway, since signatures made with it would be rejected by
 * everyone, whereas regenerating costs one announcement.
 *
 * @param currentToxPubHex who this profile is right now. When that is unusable the answer is NO —
 *                         a key must never be minted with nothing to bind it to.
 */
+ (BOOL)needsFreshKeyWithStoredOwner:(nullable NSString *)storedOwnerHex
                           storedPub:(nullable NSString *)storedPubHex
                           storedSec:(nullable NSString *)storedSecHex
                      currentToxPub:(nullable NSString *)currentToxPubHex;

/** YES for exactly 64 hex characters. */
+ (BOOL)isToxPubHex:(nullable NSString *)string;

/** The public-key half of a Tox ID, which also carries nospam and a checksum. */
+ (nullable NSString *)toxPubFromToxId:(nullable NSString *)toxId;

+ (nullable NSString *)hexFromData:(nullable NSData *)data;
+ (nullable NSData *)dataFromHex:(nullable NSString *)hex;

/**
 * Loads this group's key, minting and persisting one on first use or after the identity changed.
 *
 * @param getBlock reads a row, returning nil when absent.
 * @param setBlock writes a row, returning NO when the write did not survive a read-back.
 * @param outPub/outSec receive the key on success and are untouched otherwise.
 * @return NO when there is no usable identity yet (the caller must then sign nothing), or when
 *         generation or persistence failed. Never a partial key.
 */
+ (BOOL)ensureKeypairForGroup:(nullable NSString *)groupId
                 currentToxPub:(nullable NSString *)currentToxPubHex
                      getBlock:(NSString *_Nullable (^)(NSString *key))getBlock
                      setBlock:(BOOL (^)(NSString *key, NSString *value))setBlock
                        outPub:(NSData *_Nullable *_Nonnull)outPub
                        outSec:(NSData *_Nullable *_Nonnull)outSec;

@end

NS_ASSUME_NONNULL_END
