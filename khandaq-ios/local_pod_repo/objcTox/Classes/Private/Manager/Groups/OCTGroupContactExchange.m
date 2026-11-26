// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTGroupContactExchange.h"

const NSUInteger kOCTGroupContactPacketLength = 54;
NSString *const kOCTGroupContactRequestMarkerPrefix = @"[KQ|c|";

NSNotificationName const kOCTGroupContactAddedNotification = @"kOCTGroupContactAddedNotification";
NSString *const kOCTGroupContactAddedNameKey = @"name";
NSString *const kOCTGroupContactAddedPublicKeyKey = @"publicKey";

/// The header of every Khandaq NGC packet, then the version of this one.
static const uint8_t kOCTGroupContactHeader[7] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01};
static const NSUInteger kOCTGroupContactHeaderLength = 8;
static const NSUInteger kOCTGroupContactAddressLength = 38;
static const NSUInteger kOCTGroupContactNonceLength = 8;
/// The part of an address its checksum covers: the key (32) and the nospam (4).
static const NSUInteger kOCTGroupContactCheckedLength = 36;

static NSString *OCTGroupContactHex(const uint8_t *bytes, NSUInteger length, BOOL upper)
{
    static const char lowerDigits[] = "0123456789abcdef";
    static const char upperDigits[] = "0123456789ABCDEF";
    const char *digits = upper ? upperDigits : lowerDigits;
    NSMutableString *hex = [NSMutableString stringWithCapacity:length * 2];
    for (NSUInteger i = 0; i < length; i++) {
        [hex appendFormat:@"%c%c", digits[bytes[i] >> 4], digits[bytes[i] & 0x0F]];
    }
    return hex;
}

static int OCTGroupContactNibble(unichar c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

/// The bytes of exactly `length` bytes of hex, nil for anything else.
static NSData *OCTGroupContactBytes(NSString *hex, NSUInteger length)
{
    if (hex.length != length * 2) {
        return nil;
    }
    NSMutableData *data = [NSMutableData dataWithLength:length];
    uint8_t *bytes = data.mutableBytes;
    for (NSUInteger i = 0; i < length; i++) {
        int high = OCTGroupContactNibble([hex characterAtIndex:2 * i]);
        int low = OCTGroupContactNibble([hex characterAtIndex:2 * i + 1]);
        if (high < 0 || low < 0) {
            return nil;
        }
        bytes[i] = (uint8_t)((high << 4) | low);
    }
    return data;
}

/// The checksum of a Tox address, as toxcore's address_checksum computes it: the first 36 bytes XORed into two,
/// byte by byte in turn.
static BOOL OCTGroupContactChecksumHolds(const uint8_t *address)
{
    uint8_t check[2] = {0, 0};
    for (NSUInteger i = 0; i < kOCTGroupContactCheckedLength; i++) {
        check[i % 2] ^= address[i];
    }
    return check[0] == address[kOCTGroupContactCheckedLength] && check[1] == address[kOCTGroupContactCheckedLength + 1];
}

@implementation OCTGroupContactPacket

- (instancetype)initWithKind:(OCTGroupContactPacketKind)kind addressHex:(NSString *)addressHex nonceHex:(NSString *)nonceHex
{
    self = [super init];
    if (self) {
        _kind = kind;
        _addressHex = [addressHex.uppercaseString copy];
        _publicKeyHex = [[_addressHex substringToIndex:MIN((NSUInteger)64, _addressHex.length)] copy];
        _nonceHex = [nonceHex.lowercaseString copy];
    }
    return self;
}

@end

@implementation OCTGroupContactOffer

- (instancetype)initWithMemberName:(NSString *)memberName
                         groupName:(NSString *)groupName
                    groupChatIdHex:(NSString *)groupChatIdHex
                memberPublicKeyHex:(NSString *)memberPublicKeyHex
{
    self = [super init];
    if (self) {
        _memberName = [memberName copy];
        _groupName = [groupName copy];
        _groupChatIdHex = [groupChatIdHex.lowercaseString copy];
        _memberPublicKeyHex = [memberPublicKeyHex.lowercaseString copy];
    }
    return self;
}

@end

@implementation OCTGroupContactExchange

+ (nullable NSData *)packetWithKind:(OCTGroupContactPacketKind)kind addressHex:(NSString *)addressHex nonceHex:(NSString *)nonceHex
{
    if (kind != OCTGroupContactPacketKindOffer && kind != OCTGroupContactPacketKindAccept) {
        return nil;
    }
    NSData *address = OCTGroupContactBytes(addressHex, kOCTGroupContactAddressLength);
    NSData *nonce = OCTGroupContactBytes(nonceHex, kOCTGroupContactNonceLength);
    if (address == nil || nonce == nil || ! OCTGroupContactChecksumHolds(address.bytes)) {
        return nil;
    }

    NSMutableData *packet = [NSMutableData dataWithCapacity:kOCTGroupContactPacketLength];
    [packet appendBytes:kOCTGroupContactHeader length:sizeof(kOCTGroupContactHeader)];
    const uint8_t pktId = (uint8_t)kind;
    [packet appendBytes:&pktId length:1];
    [packet appendData:address];
    [packet appendData:nonce];
    return packet;
}

+ (BOOL)isContactPacket:(NSData *)data
{
    if (data.length < kOCTGroupContactHeaderLength) {
        return NO;
    }
    const uint8_t *bytes = data.bytes;
    if (memcmp(bytes, kOCTGroupContactHeader, sizeof(kOCTGroupContactHeader)) != 0) {
        return NO;
    }
    return bytes[7] == OCTGroupContactPacketKindOffer || bytes[7] == OCTGroupContactPacketKindAccept;
}

+ (nullable OCTGroupContactPacket *)parsePacket:(NSData *)data
{
    if (data.length != kOCTGroupContactPacketLength || ! [self isContactPacket:data]) {
        return nil;
    }
    const uint8_t *bytes = data.bytes;
    const uint8_t *address = bytes + kOCTGroupContactHeaderLength;
    if (! OCTGroupContactChecksumHolds(address)) {
        return nil;
    }
    NSString *addressHex = OCTGroupContactHex(address, kOCTGroupContactAddressLength, YES);
    NSString *nonceHex = OCTGroupContactHex(address + kOCTGroupContactAddressLength, kOCTGroupContactNonceLength, NO);
    return [[OCTGroupContactPacket alloc] initWithKind:(OCTGroupContactPacketKind)bytes[7] addressHex:addressHex nonceHex:nonceHex];
}

+ (BOOL)isValidAddressHex:(NSString *)addressHex
{
    NSData *address = OCTGroupContactBytes(addressHex, kOCTGroupContactAddressLength);
    return address != nil && OCTGroupContactChecksumHolds(address.bytes);
}

+ (NSString *)randomNonceHex
{
    uint8_t nonce[kOCTGroupContactNonceLength];
    arc4random_buf(nonce, sizeof(nonce));
    return OCTGroupContactHex(nonce, sizeof(nonce), NO);
}

+ (NSString *)requestMessageWithNonceHex:(NSString *)nonceHex text:(NSString *)text
{
    return [NSString stringWithFormat:@"%@%@]\n%@", kOCTGroupContactRequestMarkerPrefix, nonceHex.lowercaseString, text ?: @""];
}

+ (nullable NSString *)nonceHexInRequestMessage:(nullable NSString *)message
{
    NSUInteger prefixLength = kOCTGroupContactRequestMarkerPrefix.length;
    NSUInteger nonceLength = 2 * kOCTGroupContactNonceLength;
    if (! [message hasPrefix:kOCTGroupContactRequestMarkerPrefix] || message.length < prefixLength + nonceLength + 1) {
        return nil;
    }
    NSString *nonceHex = [message substringWithRange:NSMakeRange(prefixLength, nonceLength)];
    if (OCTGroupContactBytes(nonceHex, kOCTGroupContactNonceLength) == nil
        || [message characterAtIndex:prefixLength + nonceLength] != ']') {
        return nil;
    }
    return nonceHex.lowercaseString;
}

+ (nullable NSString *)requestMessageWithoutMarker:(nullable NSString *)message
{
    if (! [message hasPrefix:kOCTGroupContactRequestMarkerPrefix]) {
        return message;
    }
    NSRange lineEnd = [message rangeOfCharacterFromSet:[NSCharacterSet newlineCharacterSet]];
    if (lineEnd.location == NSNotFound) {
        return @"";
    }
    NSString *rest = [message substringFromIndex:NSMaxRange(lineEnd)];
    // A request written on Windows ends its marker line with CR LF.
    while ([rest hasPrefix:@"\n"] || [rest hasPrefix:@"\r"]) {
        rest = [rest substringFromIndex:1];
    }
    return rest;
}

@end
