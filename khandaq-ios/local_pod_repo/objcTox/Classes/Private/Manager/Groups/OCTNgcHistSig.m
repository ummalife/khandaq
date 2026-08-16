// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcHistSig.h"

#import <CommonCrypto/CommonDigest.h>
#import <string.h>

// libsodium, reached transitively (objcTox -> toxcore -> libsodium). The specific headers are
// imported rather than the <sodium.h> umbrella BECAUSE the umbrella is not a public header of the
// libsodium pod: it only resolves if a stale Pods project happens to carry libsodium's private
// header path, so the build worked on this machine and broke the moment `pod install` regenerated
// it. crypto_sign.h and utils.h are public, so this resolves from a clean checkout.
#import <libsodium/crypto_sign.h>
#import <libsodium/utils.h>

const NSUInteger kOCTNgcHistSigGroupIdSize = 32;
const NSUInteger kOCTNgcHistSigPubKeySize = 32;
const NSUInteger kOCTNgcHistSigMsgIdSize = 4;
const NSUInteger kOCTNgcHistSigSignatureSize = 64;

NSString *const kOCTNgcHistSigHistDomain = @"KQ-HISTSYNC-1";
NSString *const kOCTNgcHistSigAnnounceDomain = @"KQ-HSK-ANNOUNCE-1";

// 13 + 32 + 32 + 4 + 8 + 32
const NSUInteger kOCTNgcHistSigHistPreimageSize = 121;
// 17 + 32 + 32 + 8
const NSUInteger kOCTNgcHistSigAnnouncePreimageSize = 89;

/**
 * Appends a 64-bit value big-endian.
 *
 * Written out by hand rather than using CFSwapInt64HostToBig on a memcpy'd value: the byte order is
 * then visible at the point it matters instead of depending on a host-order assumption, and the
 * result cannot become architecture-dependent.
 */
static void appendBigEndian64(NSMutableData *out, uint64_t value)
{
    uint8_t bytes[8];
    for (int i = 0; i < 8; i++) {
        bytes[i] = (uint8_t)((value >> (56 - (i * 8))) & 0xffULL);
    }
    [out appendBytes:bytes length:sizeof(bytes)];
}

static NSData *sha256(NSData *data)
{
    uint8_t digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(data.bytes, (CC_LONG)data.length, digest);
    return [NSData dataWithBytes:digest length:sizeof(digest)];
}

NSData *OCTNgcHistSigHistPreimage(NSData *groupId, NSData *authorPub, NSData *msgId,
                                  uint64_t timestamp, NSData *textUtf8)
{
    if (groupId.length != kOCTNgcHistSigGroupIdSize || authorPub.length != kOCTNgcHistSigPubKeySize
        || msgId.length != kOCTNgcHistSigMsgIdSize || textUtf8 == nil) {
        return nil;
    }

    NSData *domain = [kOCTNgcHistSigHistDomain dataUsingEncoding:NSASCIIStringEncoding];
    NSMutableData *out = [NSMutableData dataWithCapacity:kOCTNgcHistSigHistPreimageSize];
    [out appendData:domain];
    [out appendData:groupId];
    [out appendData:authorPub];
    [out appendData:msgId];
    appendBigEndian64(out, timestamp);
    // The body is hashed, never embedded: a 1 MiB message and an empty one yield the same
    // pre-image length, so signature handling has no size-dependent path to get wrong.
    [out appendData:sha256(textUtf8)];

    return out.length == kOCTNgcHistSigHistPreimageSize ? [out copy] : nil;
}

NSData *OCTNgcHistSigAnnouncePreimage(NSData *senderGroupPub, NSData *hskPub, uint64_t validFromTs)
{
    if (senderGroupPub.length != kOCTNgcHistSigPubKeySize
        || hskPub.length != kOCTNgcHistSigPubKeySize) {
        return nil;
    }

    NSData *domain = [kOCTNgcHistSigAnnounceDomain dataUsingEncoding:NSASCIIStringEncoding];
    NSMutableData *out = [NSMutableData dataWithCapacity:kOCTNgcHistSigAnnouncePreimageSize];
    [out appendData:domain];
    [out appendData:senderGroupPub];
    [out appendData:hskPub];
    appendBigEndian64(out, validFromTs);

    return out.length == kOCTNgcHistSigAnnouncePreimageSize ? [out copy] : nil;
}

BOOL OCTNgcHistSigVerify(NSData *preimage, NSData *signature, NSData *signerPub)
{
    // Fail closed on anything malformed. An old client cannot reach here - it sends version 0x01,
    // which the dispatcher drops - so a malformed signature is never "legacy", it is an attack.
    if (preimage.length == 0 || signature.length != kOCTNgcHistSigSignatureSize
        || signerPub.length != kOCTNgcHistSigPubKeySize) {
        return NO;
    }

    return crypto_sign_verify_detached(signature.bytes, preimage.bytes,
                                       (unsigned long long)preimage.length, signerPub.bytes) == 0;
}

const NSUInteger kOCTNgcHistSigSecretKeySize = 64;

BOOL OCTNgcHistSigKeypair(NSData *_Nullable *_Nonnull outPub, NSData *_Nullable *_Nonnull outSec)
{
    if (outPub == NULL || outSec == NULL) {
        return NO;
    }

    unsigned char pub[crypto_sign_PUBLICKEYBYTES];
    unsigned char sec[crypto_sign_SECRETKEYBYTES];
    if (crypto_sign_keypair(pub, sec) != 0) {
        // Nothing is written to the out-params, so a failure cannot leave a caller holding a key
        // that libsodium never actually produced.
        return NO;
    }

    *outPub = [NSData dataWithBytes:pub length:sizeof(pub)];
    *outSec = [NSData dataWithBytes:sec length:sizeof(sec)];
    // The stack copy of the secret is wiped; the NSData copy is the one the caller must protect.
    sodium_memzero(sec, sizeof(sec));
    return YES;
}

NSData *OCTNgcHistSigSign(NSData *preimage, NSData *secretKey)
{
    if (preimage.length == 0 || secretKey.length != kOCTNgcHistSigSecretKeySize) {
        return nil;
    }

    unsigned char sig[crypto_sign_BYTES];
    unsigned long long sigLen = 0;
    if (crypto_sign_detached(sig, &sigLen, preimage.bytes,
                             (unsigned long long)preimage.length, secretKey.bytes) != 0) {
        return nil;
    }
    // Length is asserted rather than assumed: a signature of the wrong size would be built into a
    // packet whose every field after it is misaligned.
    if (sigLen != kOCTNgcHistSigSignatureSize) {
        return nil;
    }

    return [NSData dataWithBytes:sig length:(NSUInteger)sigLen];
}

#pragma mark - version-0x02 packet parsing

const NSUInteger kOCTNgcHistSigHeaderSize = 8;
const NSUInteger kOCTNgcHistSigPeerNameSize = 25;
const uint8_t kOCTNgcHistSigVersionSigned = 0x02;
const uint8_t kOCTNgcHistSigPktHskAnnounce = 0x50;
const uint8_t kOCTNgcHistSigPktSignedText = 0x02;
const NSUInteger kOCTNgcHistSigAnnouncePacketSize = 8 + 32 + 8 + 64; // 120
const NSUInteger kOCTNgcHistSigMaxTextBytes = 37000;

static const uint8_t kMagic[6] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35};

@implementation OCTNgcHistSigAnnouncement
- (instancetype)initWithHskPub:(NSData *)hskPub ts:(uint64_t)ts signature:(NSData *)signature
{
    self = [super init];
    if (self) { _hskPub = [hskPub copy]; _validFromTs = ts; _signature = [signature copy]; }
    return self;
}
@end

@implementation OCTNgcHistSigSignedText
- (instancetype)initWithMsgId:(NSData *)msgId author:(NSData *)author ts:(uint64_t)ts
                     peerName:(NSData *)peerName text:(NSData *)text signature:(NSData *)signature
{
    self = [super init];
    if (self) {
        _msgId = [msgId copy]; _authorPub = [author copy]; _timestamp = ts;
        _peerNameRaw = [peerName copy]; _textUtf8 = [text copy]; _signature = [signature copy];
    }
    return self;
}
@end

/** Reads 8 bytes big-endian; never memcpy'd, so host order cannot leak into a wire format. */
static uint64_t readBE64(const uint8_t *p)
{
    uint64_t v = 0;
    for (int i = 0; i < 8; i++) { v = (v << 8) | (uint64_t)p[i]; }
    return v;
}

/** Reads 4 bytes big-endian as UNSIGNED - a top-bit-set field must not become negative. */
static uint64_t readBE32Unsigned(const uint8_t *p)
{
    uint64_t v = 0;
    for (int i = 0; i < 4; i++) { v = (v << 8) | (uint64_t)p[i]; }
    return v;
}

BOOL OCTNgcHistSigIsSignedPacket(const uint8_t *data, NSInteger length, uint8_t pktId)
{
    if (data == NULL || length < (NSInteger)kOCTNgcHistSigHeaderSize) { return NO; }
    if (memcmp(data, kMagic, sizeof(kMagic)) != 0) { return NO; }
    return data[6] == kOCTNgcHistSigVersionSigned && data[7] == pktId;
}

OCTNgcHistSigAnnouncement *OCTNgcHistSigParseAnnouncement(const uint8_t *data, NSInteger length)
{
    // Exact length, not a minimum: no variable part, so anything longer is either a different
    // packet or an attempt to hide bytes after the signature.
    if (!OCTNgcHistSigIsSignedPacket(data, length, kOCTNgcHistSigPktHskAnnounce)
        || length != (NSInteger)kOCTNgcHistSigAnnouncePacketSize) {
        return nil;
    }

    NSUInteger pos = kOCTNgcHistSigHeaderSize;
    NSData *hsk = [NSData dataWithBytes:data + pos length:kOCTNgcHistSigPubKeySize];
    pos += kOCTNgcHistSigPubKeySize;
    uint64_t ts = readBE64(data + pos);
    pos += 8;
    NSData *sig = [NSData dataWithBytes:data + pos length:kOCTNgcHistSigSignatureSize];
    return [[OCTNgcHistSigAnnouncement alloc] initWithHskPub:hsk ts:ts signature:sig];
}

OCTNgcHistSigSignedText *OCTNgcHistSigParseSignedText(const uint8_t *data, NSInteger length)
{
    const NSInteger fixedBefore = (NSInteger)(kOCTNgcHistSigHeaderSize + kOCTNgcHistSigMsgIdSize
                                              + kOCTNgcHistSigPubKeySize + 8
                                              + kOCTNgcHistSigPeerNameSize + 4);
    if (!OCTNgcHistSigIsSignedPacket(data, length, kOCTNgcHistSigPktSignedText)
        || length < fixedBefore + (NSInteger)kOCTNgcHistSigSignatureSize) {
        return nil;
    }

    NSUInteger pos = kOCTNgcHistSigHeaderSize;
    NSData *msgId = [NSData dataWithBytes:data + pos length:kOCTNgcHistSigMsgIdSize];
    pos += kOCTNgcHistSigMsgIdSize;
    NSData *author = [NSData dataWithBytes:data + pos length:kOCTNgcHistSigPubKeySize];
    pos += kOCTNgcHistSigPubKeySize;
    uint64_t ts = readBE64(data + pos);
    pos += 8;
    NSData *peerName = [NSData dataWithBytes:data + pos length:kOCTNgcHistSigPeerNameSize];
    pos += kOCTNgcHistSigPeerNameSize;

    uint64_t declaredTextLen = readBE32Unsigned(data + pos);
    pos += 4;

    if (declaredTextLen > (uint64_t)kOCTNgcHistSigMaxTextBytes) { return nil; }
    // Bytes must be present AND the signature must follow them exactly - no trailing slack in which
    // to hide anything the signature does not cover.
    if ((uint64_t)pos + declaredTextLen + kOCTNgcHistSigSignatureSize != (uint64_t)length) {
        return nil;
    }

    NSData *text = [NSData dataWithBytes:data + pos length:(NSUInteger)declaredTextLen];
    pos += (NSUInteger)declaredTextLen;
    NSData *sig = [NSData dataWithBytes:data + pos length:kOCTNgcHistSigSignatureSize];
    return [[OCTNgcHistSigSignedText alloc] initWithMsgId:msgId author:author ts:ts
                                                peerName:peerName text:text signature:sig];
}

#pragma mark - version-0x02 packet building

static void appendBE32(NSMutableData *out, uint64_t value)
{
    uint8_t b[4];
    for (int i = 0; i < 4; i++) { b[i] = (uint8_t)((value >> (24 - (i * 8))) & 0xffULL); }
    [out appendBytes:b length:sizeof(b)];
}

static void appendHeader(NSMutableData *out, uint8_t pktId)
{
    [out appendBytes:kMagic length:sizeof(kMagic)];
    uint8_t v = kOCTNgcHistSigVersionSigned;
    [out appendBytes:&v length:1];
    [out appendBytes:&pktId length:1];
}

NSData *OCTNgcHistSigBuildAnnouncement(NSData *hskPub, uint64_t validFromTs, NSData *signature)
{
    if (hskPub.length != kOCTNgcHistSigPubKeySize
        || signature.length != kOCTNgcHistSigSignatureSize) {
        return nil;
    }

    NSMutableData *out = [NSMutableData dataWithCapacity:kOCTNgcHistSigAnnouncePacketSize];
    appendHeader(out, kOCTNgcHistSigPktHskAnnounce);
    [out appendData:hskPub];
    appendBigEndian64(out, validFromTs);
    [out appendData:signature];
    return out.length == kOCTNgcHistSigAnnouncePacketSize ? [out copy] : nil;
}

NSData *OCTNgcHistSigBuildSignedText(NSData *msgId, NSData *authorPub, uint64_t timestamp,
                                     NSData *peerNameRaw, NSData *textUtf8, NSData *signature)
{
    if (msgId.length != kOCTNgcHistSigMsgIdSize || authorPub.length != kOCTNgcHistSigPubKeySize
        || peerNameRaw.length != kOCTNgcHistSigPeerNameSize || textUtf8 == nil
        || textUtf8.length > kOCTNgcHistSigMaxTextBytes
        || signature.length != kOCTNgcHistSigSignatureSize) {
        return nil;
    }

    NSMutableData *out = [NSMutableData data];
    appendHeader(out, kOCTNgcHistSigPktSignedText);
    [out appendData:msgId];
    [out appendData:authorPub];
    appendBigEndian64(out, timestamp);
    [out appendData:peerNameRaw];
    appendBE32(out, (uint64_t)textUtf8.length);
    [out appendData:textUtf8];
    [out appendData:signature];
    return [out copy];
}

