// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSeenReceipt.h"

const uint8_t kOCTSeenReceiptPacketId = 189;
const NSUInteger kOCTSeenReceiptPacketLength = 41;

static const uint8_t kOCTSeenReceiptVersion = 1;
static const NSUInteger kOCTSeenAnchorLength = 32;
static const NSUInteger kOCTSeenAnchorOffset = 5;
static const NSUInteger kOCTSeenTimeOffset = 37;

static int OCTSeenHexValue(unichar character)
{
    if (character >= '0' && character <= '9') {
        return character - '0';
    }
    if (character >= 'A' && character <= 'F') {
        return character - 'A' + 10;
    }
    if (character >= 'a' && character <= 'f') {
        return character - 'a' + 10;
    }
    return -1;
}

@interface OCTSeenReceipt ()

@property (assign, nonatomic, readwrite) OCTSeenAnchorType anchorType;
@property (copy, nonatomic, readwrite) NSString *anchorHex;
@property (assign, nonatomic, readwrite) uint32_t seenTime;

@end

@implementation OCTSeenReceipt

+ (nullable NSData *)packetWithAnchorType:(OCTSeenAnchorType)anchorType
                                anchorHex:(NSString *)anchorHex
                                 seenTime:(uint32_t)seenTime
{
    if ((anchorType != OCTSeenAnchorTypeText && anchorType != OCTSeenAnchorTypeFile)
        || anchorHex.length != kOCTSeenAnchorLength * 2) {
        return nil;
    }

    uint8_t packet[41] = { kOCTSeenReceiptPacketId, 'K', 'Q', kOCTSeenReceiptVersion, anchorType };

    for (NSUInteger i = 0; i < kOCTSeenAnchorLength; i++) {
        int high = OCTSeenHexValue([anchorHex characterAtIndex:i * 2]);
        int low = OCTSeenHexValue([anchorHex characterAtIndex:i * 2 + 1]);
        if (high < 0 || low < 0) {
            return nil;
        }
        packet[kOCTSeenAnchorOffset + i] = (uint8_t)((high << 4) | low);
    }

    packet[kOCTSeenTimeOffset] = (uint8_t)((seenTime >> 24) & 0xFF);
    packet[kOCTSeenTimeOffset + 1] = (uint8_t)((seenTime >> 16) & 0xFF);
    packet[kOCTSeenTimeOffset + 2] = (uint8_t)((seenTime >> 8) & 0xFF);
    packet[kOCTSeenTimeOffset + 3] = (uint8_t)(seenTime & 0xFF);

    return [NSData dataWithBytes:packet length:sizeof(packet)];
}

+ (nullable instancetype)receiptWithBytes:(const uint8_t *)bytes length:(size_t)length
{
    if (bytes == NULL || length != kOCTSeenReceiptPacketLength) {
        return nil;
    }
    if (bytes[0] != kOCTSeenReceiptPacketId || bytes[1] != 'K' || bytes[2] != 'Q'
        || bytes[3] != kOCTSeenReceiptVersion) {
        return nil;
    }
    if (bytes[4] != OCTSeenAnchorTypeText && bytes[4] != OCTSeenAnchorTypeFile) {
        return nil;
    }

    NSMutableString *anchorHex = [NSMutableString stringWithCapacity:kOCTSeenAnchorLength * 2];
    for (NSUInteger i = 0; i < kOCTSeenAnchorLength; i++) {
        [anchorHex appendFormat:@"%02X", bytes[kOCTSeenAnchorOffset + i]];
    }

    OCTSeenReceipt *receipt = [self new];
    receipt.anchorType = (OCTSeenAnchorType)bytes[4];
    receipt.anchorHex = anchorHex;
    receipt.seenTime = ((uint32_t)bytes[kOCTSeenTimeOffset] << 24)
                       | ((uint32_t)bytes[kOCTSeenTimeOffset + 1] << 16)
                       | ((uint32_t)bytes[kOCTSeenTimeOffset + 2] << 8)
                       | (uint32_t)bytes[kOCTSeenTimeOffset + 3];
    return receipt;
}

+ (nullable instancetype)receiptWithPacket:(NSData *)packet
{
    return [self receiptWithBytes:packet.bytes length:packet.length];
}

@end
