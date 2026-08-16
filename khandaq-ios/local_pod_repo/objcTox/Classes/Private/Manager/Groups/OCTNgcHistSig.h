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

/**
 * "KQ-HSK-ANNOUNCE-1" || senderGroupPub(32) || hskPub(32) || validFromTs(8 BE)
 *
 * @param senderGroupPub the announcer's public key IN THIS GROUP — what tox_group_peer_get_public_key
 *                       reports to the receiver, and the same identity a group message records as its
 *                       author. Emphatically NOT the profile's Tox ID key: NGC gives a member a
 *                       different key per group and never reveals anyone's Tox ID, so a signature
 *                       over the Tox ID key is one no receiver can reconstruct. Android shipped that
 *                       mistake first and every announcement failed verification in both directions
 *                       while each side looked correct in isolation.
 */
NSData *_Nullable OCTNgcHistSigAnnouncePreimage(NSData *senderGroupPub, NSData *hskPub,
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

/** Secret-key length libsodium uses for Ed25519; the public half lives in its tail. */
extern const NSUInteger kOCTNgcHistSigSecretKeySize; //!< 64

/**
 * Generates a fresh Ed25519 keypair.
 *
 * @param outPub  receives 32 bytes, @param outSec receives 64. Both are left untouched on failure —
 *                a caller must never be handed half a key it might store and later sign with.
 * @return YES on success.
 */
BOOL OCTNgcHistSigKeypair(NSData *_Nullable *_Nonnull outPub, NSData *_Nullable *_Nonnull outSec);

/**
 * Signs a pre-image with an HSK secret key.
 *
 * @return the 64-byte detached signature, or nil on any malformed input or libsodium failure. Never
 *         a partial signature: a receiver counts a bad signature as a failed verification, which is
 *         strictly worse than sending nothing at all.
 */
NSData *_Nullable OCTNgcHistSigSign(NSData *_Nullable preimage, NSData *_Nullable secretKey);


#pragma mark - version-0x02 packet parsing

/** Wire constants (see DESIGN-ngc-signed-history-sync.md §4). */
extern const NSUInteger kOCTNgcHistSigHeaderSize;      //!< 8 = magic(6) + version + pktid
extern const NSUInteger kOCTNgcHistSigPeerNameSize;    //!< 25
extern const uint8_t kOCTNgcHistSigVersionSigned;      //!< 0x02
extern const uint8_t kOCTNgcHistSigPktHskAnnounce;     //!< 0x50
extern const uint8_t kOCTNgcHistSigPktSignedText;      //!< 0x02
extern const NSUInteger kOCTNgcHistSigAnnouncePacketSize; //!< 112

extern const NSUInteger kOCTNgcHistSigSignedTextOverhead; //!< 145 = everything but the text

/**
 * Largest text a signed history packet may carry: the 37000-byte NGC packet ceiling minus the
 * 145 bytes of envelope. NOT the unsigned path's 37000-byte text limit — that one, applied here,
 * builds a packet Android discards before it reads the version byte.
 */
extern const NSUInteger kOCTNgcHistSigMaxTextBytes;    //!< 36855

/** A parsed announcement. Only produced when every bound held. */
@interface OCTNgcHistSigAnnouncement : NSObject
@property (nonatomic, copy, readonly) NSData *hskPub;
@property (nonatomic, assign, readonly) uint64_t validFromTs;
@property (nonatomic, copy, readonly) NSData *signature;
@end

/** A parsed signed-history text record. Only produced when every bound held. */
@interface OCTNgcHistSigSignedText : NSObject
@property (nonatomic, copy, readonly) NSData *msgId;
@property (nonatomic, copy, readonly) NSData *authorPub;
@property (nonatomic, assign, readonly) uint64_t timestamp;
@property (nonatomic, copy, readonly) NSData *peerNameRaw;
@property (nonatomic, copy, readonly) NSData *textUtf8;
@property (nonatomic, copy, readonly) NSData *signature;
@end

/**
 * Is this one of ours, of the signed version, of this kind?
 *
 * Exposed so a dispatcher can tell "ours but malformed" from "not ours at all" and consume the
 * former instead of letting it fall through into the version-0x01 arms, whose length arithmetic
 * would read it as something else entirely.
 */
BOOL OCTNgcHistSigIsSignedPacket(const uint8_t *_Nullable data, NSInteger length, uint8_t pktId);

/**
 * @param length bytes actually received; passed separately because the caller's buffer may be larger
 *               than the datagram, and trusting the buffer size would read stale bytes.
 * @return nil unless the packet is ours, of the signed version, of this kind, and EXACTLY the
 *         expected length — the announcement has no variable part.
 */
OCTNgcHistSigAnnouncement *_Nullable OCTNgcHistSigParseAnnouncement(const uint8_t *_Nullable data,
                                                                    NSInteger length);

/**
 * The declared text length is attacker-chosen and guarded three ways: read unsigned, range checked,
 * and required to account for the packet exactly so no slack can hide bytes the signature misses.
 */
OCTNgcHistSigSignedText *_Nullable OCTNgcHistSigParseSignedText(const uint8_t *_Nullable data,
                                                                NSInteger length);


#pragma mark - version-0x02 packet building

/**
 * Building is not sending: these produce the bytes, wiring them into an emit is a separate step.
 *
 * Both builders refuse exactly what the parsers reject — a wrong-sized field, a body above
 * kOCTNgcHistSigMaxTextBytes — so this client can never emit a packet its own peers must drop.
 *
 * @return the packet, or nil if any field is wrong. Never a short buffer.
 */
NSData *_Nullable OCTNgcHistSigBuildAnnouncement(NSData *hskPub, uint64_t validFromTs,
                                                 NSData *signature);

NSData *_Nullable OCTNgcHistSigBuildSignedText(NSData *msgId, NSData *authorPub, uint64_t timestamp,
                                               NSData *peerNameRaw, NSData *textUtf8,
                                               NSData *signature);

NS_ASSUME_NONNULL_END
