// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcHskDirectory.h"
#import "OCTNgcHskStore.h"
#import "OCTNgcHistSig.h"

static NSString *const kRowPrefix = @"kqhskdir_";

@interface OCTNgcHskRecord ()
@property (nonatomic, copy) NSData *hskPub;
@property (nonatomic, assign) uint64_t firstSeenMs;
@property (nonatomic, assign) uint64_t lastSeenMs;
@end

@implementation OCTNgcHskRecord

- (instancetype)initWithHskPub:(NSData *)hskPub
                   firstSeenMs:(uint64_t)firstSeenMs
                    lastSeenMs:(uint64_t)lastSeenMs
{
    self = [super init];
    if (self) {
        _hskPub = [hskPub copy];
        _firstSeenMs = firstSeenMs;
        _lastSeenMs = lastSeenMs;
    }
    return self;
}

@end

@implementation OCTNgcHskDirectory

+ (uint64_t)replaceGraceMs
{
    return 24ULL * 60ULL * 60ULL * 1000ULL;
}

+ (OCTNgcHskDecision)decideWithExisting:(nullable OCTNgcHskRecord *)existing
                            incomingPub:(nullable NSData *)incomingHskPub
                       peerConnectedNow:(BOOL)peerConnectedNow
                                  nowMs:(uint64_t)nowMs
{
    if (incomingHskPub.length != kOCTNgcHistSigPubKeySize) {
        return OCTNgcHskDecisionIgnore;
    }
    if (existing == nil || existing.hskPub.length != kOCTNgcHistSigPubKeySize) {
        return OCTNgcHskDecisionLearn;
    }
    if ([existing.hskPub isEqualToData:incomingHskPub]) {
        return OCTNgcHskDecisionRefresh;
    }

    // A different key for a peer we already know. Everything below is the impersonation guard.
    if (! peerConnectedNow) {
        // Relayed, or from a peer that is not live: unattributable, which is exactly the property
        // a self-signature cannot supply.
        return OCTNgcHskDecisionRecordOnly;
    }
    // Unsigned arithmetic, so a clock that moved backwards produces a huge value rather than a
    // negative one; guarded by comparing the stamps directly instead of subtracting blindly.
    if (nowMs <= existing.lastSeenMs || (nowMs - existing.lastSeenMs) < [self replaceGraceMs]) {
        // The key we trust is still in active use. A second key appearing beside it is the shape of
        // an attack, not of a rotation.
        return OCTNgcHskDecisionRecordOnly;
    }
    return OCTNgcHskDecisionReplace;
}

+ (nullable NSString *)rowKeyWithGroupId:(nullable NSString *)groupId
                         peerGroupPubHex:(nullable NSString *)peerGroupPubHex
{
    if (groupId.length == 0 || peerGroupPubHex.length == 0) {
        return nil;
    }
    return [NSString stringWithFormat:@"%@%@|%@", kRowPrefix, groupId.lowercaseString,
            peerGroupPubHex.lowercaseString];
}

+ (nullable NSString *)encodeRecord:(nullable OCTNgcHskRecord *)record
{
    if (record.hskPub.length != kOCTNgcHistSigPubKeySize) {
        return nil;
    }
    return [NSString stringWithFormat:@"%@:%llu:%llu",
            [OCTNgcHskStore hexFromData:record.hskPub], record.firstSeenMs, record.lastSeenMs];
}

+ (nullable OCTNgcHskRecord *)decodeRecord:(nullable NSString *)stored
{
    if (stored.length == 0) {
        return nil;
    }

    NSArray<NSString *> *parts = [stored componentsSeparatedByString:@":"];
    if (parts.count != 3) {
        return nil;
    }

    NSData *pub = [OCTNgcHskStore dataFromHex:parts[0]];
    if (pub.length != kOCTNgcHistSigPubKeySize) {
        return nil;
    }

    // Reject anything that is not purely digits: strtoull would happily read "12abc" as 12 and a
    // corrupted row would become a plausible-looking timestamp.
    NSCharacterSet *nonDigits = [[NSCharacterSet decimalDigitCharacterSet] invertedSet];
    for (NSUInteger i = 1; i < 3; i++) {
        if (parts[i].length == 0 || [parts[i] rangeOfCharacterFromSet:nonDigits].location != NSNotFound) {
            return nil;
        }
    }

    return [[OCTNgcHskRecord alloc] initWithHskPub:pub
                                       firstSeenMs:strtoull(parts[1].UTF8String, NULL, 10)
                                        lastSeenMs:strtoull(parts[2].UTF8String, NULL, 10)];
}

@end
