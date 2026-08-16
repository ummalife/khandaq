// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcHskStore.h"
#import "OCTNgcHistSig.h"

NSString *const kOCTNgcHskRowPrefixSec = @"kqhsksec_";
NSString *const kOCTNgcHskRowPrefixPub = @"kqhskpub_";
NSString *const kOCTNgcHskRowPrefixOwner = @"kqhskowner_";

static const NSUInteger kToxPubHexLength = 64;

static int hexDigit(unichar c);

@implementation OCTNgcHskStore

+ (nullable NSString *)rowKeyWithPrefix:(NSString *)prefix groupId:(nullable NSString *)groupId
{
    if (prefix.length == 0 || groupId.length == 0) {
        return nil;
    }
    return [prefix stringByAppendingString:groupId.lowercaseString];
}

+ (BOOL)isToxPubHex:(nullable NSString *)string
{
    return string.length == kToxPubHexLength && [self dataFromHex:string] != nil;
}

+ (nullable NSString *)toxPubFromToxId:(nullable NSString *)toxId
{
    if (toxId.length < kToxPubHexLength) {
        return nil;
    }
    NSString *head = [toxId substringToIndex:kToxPubHexLength];
    return [self isToxPubHex:head] ? head.lowercaseString : nil;
}

+ (nullable NSString *)hexFromData:(nullable NSData *)data
{
    if (data == nil) {
        return nil;
    }
    static const char digits[] = "0123456789abcdef";
    const uint8_t *bytes = data.bytes;
    NSMutableString *out = [NSMutableString stringWithCapacity:data.length * 2];
    for (NSUInteger i = 0; i < data.length; i++) {
        [out appendFormat:@"%c%c", digits[(bytes[i] >> 4) & 0x0f], digits[bytes[i] & 0x0f]];
    }
    return out;
}

+ (nullable NSData *)dataFromHex:(nullable NSString *)hex
{
    if (hex.length == 0 || (hex.length % 2) != 0) {
        return nil;
    }

    NSMutableData *out = [NSMutableData dataWithLength:hex.length / 2];
    uint8_t *bytes = out.mutableBytes;
    for (NSUInteger i = 0; i < out.length; i++) {
        int hi = hexDigit([hex characterAtIndex:i * 2]);
        int lo = hexDigit([hex characterAtIndex:(i * 2) + 1]);
        if (hi < 0 || lo < 0) {
            // Never a partial buffer: a caller must not sign over half-decoded bytes.
            return nil;
        }
        bytes[i] = (uint8_t)((hi << 4) | lo);
    }
    return out;
}

static int hexDigit(unichar c)
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

+ (BOOL)needsFreshKeyWithStoredOwner:(nullable NSString *)storedOwnerHex
                           storedPub:(nullable NSString *)storedPubHex
                           storedSec:(nullable NSString *)storedSecHex
                       currentToxPub:(nullable NSString *)currentToxPubHex
{
    if (! [self isToxPubHex:currentToxPubHex]) {
        // Without knowing who we are a key cannot be bound to anything, and minting an orphan is
        // worse than having none — it would be announced under whatever identity appears later.
        return NO;
    }
    if (storedOwnerHex.length == 0 || storedPubHex.length == 0 || storedSecHex.length == 0) {
        return YES;
    }
    if ([storedOwnerHex caseInsensitiveCompare:currentToxPubHex] != NSOrderedSame) {
        return YES;
    }

    NSData *pub = [self dataFromHex:storedPubHex];
    NSData *sec = [self dataFromHex:storedSecHex];
    if (pub.length != kOCTNgcHistSigPubKeySize || sec.length != kOCTNgcHistSigSecretKeySize) {
        return YES;
    }

    // libsodium keeps the public key in the tail of the secret key. If those disagree the rows came
    // from different generations — a torn write, or a half-restored backup.
    NSData *tail = [sec subdataWithRange:NSMakeRange(sec.length - pub.length, pub.length)];
    return ! [tail isEqualToData:pub];
}

+ (BOOL)ensureKeypairForGroup:(nullable NSString *)groupId
                 currentToxPub:(nullable NSString *)currentToxPubHex
                      getBlock:(NSString *_Nullable (^)(NSString *key))getBlock
                      setBlock:(BOOL (^)(NSString *key, NSString *value))setBlock
                        outPub:(NSData *_Nullable *_Nonnull)outPub
                        outSec:(NSData *_Nullable *_Nonnull)outSec
{
    if (getBlock == nil || setBlock == nil || outPub == NULL || outSec == NULL) {
        return NO;
    }
    if (! [self isToxPubHex:currentToxPubHex]) {
        return NO;
    }

    NSString *ownerRow = [self rowKeyWithPrefix:kOCTNgcHskRowPrefixOwner groupId:groupId];
    NSString *pubRow = [self rowKeyWithPrefix:kOCTNgcHskRowPrefixPub groupId:groupId];
    NSString *secRow = [self rowKeyWithPrefix:kOCTNgcHskRowPrefixSec groupId:groupId];
    if (ownerRow == nil || pubRow == nil || secRow == nil) {
        return NO;
    }

    NSString *storedOwner = getBlock(ownerRow);
    NSString *storedPub = getBlock(pubRow);
    NSString *storedSec = getBlock(secRow);

    if (! [self needsFreshKeyWithStoredOwner:storedOwner
                                   storedPub:storedPub
                                   storedSec:storedSec
                               currentToxPub:currentToxPubHex]) {
        *outPub = [self dataFromHex:storedPub];
        *outSec = [self dataFromHex:storedSec];
        return *outPub != nil && *outSec != nil;
    }

    NSData *pub = nil;
    NSData *sec = nil;
    if (! OCTNgcHistSigKeypair(&pub, &sec)) {
        return NO;
    }

    // Owner LAST: it is the commit marker, so a process death between these writes leaves the key
    // looking absent and the next call regenerates rather than loading half of one.
    if (! setBlock(secRow, [self hexFromData:sec])
        || ! setBlock(pubRow, [self hexFromData:pub])
        || ! setBlock(ownerRow, currentToxPubHex.lowercaseString)) {
        return NO;
    }

    // Confirm by reading back. The setter already checks its own write, but this also catches a
    // store that accepts each row yet cannot return a consistent set of three.
    if ([self needsFreshKeyWithStoredOwner:getBlock(ownerRow)
                                 storedPub:getBlock(pubRow)
                                 storedSec:getBlock(secRow)
                             currentToxPub:currentToxPubHex]) {
        return NO;
    }

    *outPub = pub;
    *outSec = sec;
    return YES;
}

@end
