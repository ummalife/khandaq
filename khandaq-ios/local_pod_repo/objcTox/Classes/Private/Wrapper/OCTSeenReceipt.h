// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// KHANDAQ (0.2.57): what the anchor of a read receipt names.
typedef NS_ENUM(uint8_t, OCTSeenAnchorType) {
    /// A text, by its msgV3 id.
    OCTSeenAnchorTypeText = 1,
    /// A file, by its Tox file id.
    OCTSeenAnchorTypeFile = 2,
};

/// KHANDAQ (0.2.57): the lossless custom packet id of a read receipt.
extern const uint8_t kOCTSeenReceiptPacketId;
/// KHANDAQ (0.2.57): a read receipt is exactly this long; any other length is not one.
extern const NSUInteger kOCTSeenReceiptPacketLength;

/**
 * KHANDAQ (0.2.57): a read receipt between two friends, the "KQ" lossless packet 189 that the Android and
 * desktop clients send and read byte for byte the same (docs: 057-read-receipts SPEC, protocol v1).
 *
 * It says: "I have seen every message and file you sent me, up to and including the anchored one".
 *
 *   [0] 189   [1] 'K'   [2] 'Q'   [3] version 1   [4] anchor type (1 text, 2 file)
 *   [5..36] anchor id, 32 bytes   [37..40] seen time, the reader's clock, seconds since 1970, u32 big endian
 *
 * The family of the edit, delete and reaction packets (186, 187, 188). The seen time is information only.
 */
@interface OCTSeenReceipt : NSObject

@property (assign, nonatomic, readonly) OCTSeenAnchorType anchorType;
/// The anchor id, 64 uppercase hex characters.
@property (copy, nonatomic, readonly) NSString *anchorHex;
@property (assign, nonatomic, readonly) uint32_t seenTime;

/**
 * The packet for an anchor, nil when the anchor is not 64 hex characters (either case) or its type is
 * neither a text nor a file.
 */
+ (nullable NSData *)packetWithAnchorType:(OCTSeenAnchorType)anchorType
                                anchorHex:(NSString *)anchorHex
                                 seenTime:(uint32_t)seenTime;

/**
 * The receipt a packet carries, nil for anything else: another length, magic, version or anchor type.
 */
+ (nullable instancetype)receiptWithBytes:(const uint8_t *)bytes length:(size_t)length;

+ (nullable instancetype)receiptWithPacket:(NSData *)packet;

@end

NS_ASSUME_NONNULL_END
