// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcHistSig.h"

#import <CommonCrypto/CommonDigest.h>

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

NSData *OCTNgcHistSigAnnouncePreimage(NSData *toxPub, NSData *hskPub, uint64_t validFromTs)
{
    if (toxPub.length != kOCTNgcHistSigPubKeySize || hskPub.length != kOCTNgcHistSigPubKeySize) {
        return nil;
    }

    NSData *domain = [kOCTNgcHistSigAnnounceDomain dataUsingEncoding:NSASCIIStringEncoding];
    NSMutableData *out = [NSMutableData dataWithCapacity:kOCTNgcHistSigAnnouncePreimageSize];
    [out appendData:domain];
    [out appendData:toxPub];
    [out appendData:hskPub];
    appendBigEndian64(out, validFromTs);

    return out.length == kOCTNgcHistSigAnnouncePreimageSize ? [out copy] : nil;
}
