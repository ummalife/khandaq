// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * KHANDAQ (external audit #2, finding 1) — the bytes an NGC history-sync signature covers.
 *
 * A history-sync packet carries the alleged original author as raw bytes nobody signed: the
 * transport authenticates only the SYNCING peer, so any group member can manufacture a message
 * displayed as another member's. The fix is an original-author signature — design, decisions and
 * rollout live in DESIGN-ngc-signed-history-sync.md at the repository root.
 *
 * This file is ONLY the pre-image. The signature primitive is Ed25519 and will not diverge between
 * platforms; the byte string being signed absolutely can — field order, integer width, endianness,
 * and whether the body is hashed as UTF-8 or as the platform's native string encoding. NSString is
 * UTF-16 internally, which makes that last one the most likely way for iOS to disagree with Android
 * and the desktop, and such a disagreement fails open in production while every local test passes.
 *
 * So these functions are checked against frozen reference vectors: ngc_histsync_vectors.py at the
 * repository root, mirrored by the desktop's test/core/ngchistsig_test.cpp and Android's
 * NgcHistSigTest. All three must produce identical digests before anyone signs anything.
 *
 * Deliberately no signing and no verification here yet: those need libsodium's crypto_sign, which
 * on iOS comes through the toxcore pod, and are wired in a later step. Nothing calls this yet.
 */

/** Widths the wire format fixes. Anything else is a malformed packet, not a variant. */
extern const NSUInteger kOCTNgcHistSigGroupIdSize;   //!< 32
extern const NSUInteger kOCTNgcHistSigPubKeySize;    //!< 32
extern const NSUInteger kOCTNgcHistSigMsgIdSize;     //!< 4
extern const NSUInteger kOCTNgcHistSigSignatureSize; //!< 64

/** Fixed regardless of message length, because the body is hashed rather than embedded. */
extern const NSUInteger kOCTNgcHistSigHistPreimageSize;     //!< 121
extern const NSUInteger kOCTNgcHistSigAnnouncePreimageSize; //!< 89

/** Domain separators — they stop a history signature validating as an announcement one. */
extern NSString *const kOCTNgcHistSigHistDomain;     //!< @"KQ-HISTSYNC-1"
extern NSString *const kOCTNgcHistSigAnnounceDomain; //!< @"KQ-HSK-ANNOUNCE-1"

/**
 * "KQ-HISTSYNC-1" || groupId(32) || authorPub(32) || msgId(4) || ts(8 BE) || sha256(textUtf8)
 *
 * @param timestamp the FULL 64-bit value. Today's wire format carries only its low 4 bytes; a
 *                  client that signs the truncated value produces signatures nobody accepts.
 * @param textUtf8  the body ALREADY encoded as UTF-8. Taking NSData rather than NSString is
 *                  deliberate — it removes any chance of hashing UTF-16.
 * @return the pre-image, or nil if a fixed-width argument has the wrong size. Never a short buffer,
 *         so a malformed input cannot be silently signed.
 */
NSData *_Nullable OCTNgcHistSigHistPreimage(NSData *groupId, NSData *authorPub, NSData *msgId,
                                            uint64_t timestamp, NSData *textUtf8);

/** "KQ-HSK-ANNOUNCE-1" || toxPub(32) || hskPub(32) || validFromTs(8 BE) */
NSData *_Nullable OCTNgcHistSigAnnouncePreimage(NSData *toxPub, NSData *hskPub,
                                                uint64_t validFromTs);

/**
 * Verifies an Ed25519 signature over a pre-image, via libsodium (reached through the toxcore pod).
 *
 * Fails CLOSED on every malformed input — nil or empty pre-image, wrong signature length, wrong key
 * length. A packet carrying a signature we cannot even parse is an attack or a bug, never an old
 * client: old clients send version 0x01, which the dispatcher drops long before this point.
 *
 * @return YES only if the signature verifies.
 */
BOOL OCTNgcHistSigVerify(NSData *_Nullable preimage, NSData *_Nullable signature,
                         NSData *_Nullable signerPub);

NS_ASSUME_NONNULL_END
