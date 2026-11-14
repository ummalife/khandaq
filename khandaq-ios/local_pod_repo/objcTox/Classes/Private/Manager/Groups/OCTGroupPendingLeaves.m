// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTGroupPendingLeaves.h"

// KHANDAQ (QA live, F11): how long a group stays in toxcore after its leave notice was queued. toxcore resends a
// lossless packet that no peer has acknowledged about 3, 5 and 9 s after it first sent it (gcc_resend_packets: the
// try after 2, 4 and 8 s); its next resend would only be at about +17 s. 12 s lets all three go out, the last one
// with time to arrive, and keeps the group no longer than that.
static const NSTimeInterval kOCTGroupPendingLeaveDelaySec = 12.0;

static NSString *const kOCTGroupPendingLeavesSeparator = @",";

@implementation OCTGroupPendingLeave

- (instancetype)initWithGroupNumber:(OCTToxGroupNumber)groupNumber
                          chatIdHex:(NSString *)chatIdHex
                        partMessage:(NSString *)partMessage
                              dueAt:(NSTimeInterval)dueAt
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _groupNumber = groupNumber;
    _chatIdHex = [chatIdHex.uppercaseString copy];
    _partMessage = [partMessage copy];
    _dueAt = dueAt;

    return self;
}

@end

@interface OCTGroupPendingLeaves ()

@property (nonatomic, strong, readonly) NSMutableArray<OCTGroupPendingLeave *> *entries;

@end

@implementation OCTGroupPendingLeaves

+ (NSTimeInterval)delay
{
    return kOCTGroupPendingLeaveDelaySec;
}

+ (BOOL)leaveWaitsForNoticeSent:(BOOL)noticeSent confirmedPeerCount:(uint32_t)confirmedPeerCount
{
    return noticeSent && confirmedPeerCount > 1;
}

/// Upper case, or nil when it is not 64 hex digits.
+ (nullable NSString *)normalizedChatIdHex:(nullable NSString *)chatIdHex
{
    if (chatIdHex.length != 64) {
        return nil;
    }

    NSCharacterSet *notHex = [[NSCharacterSet characterSetWithCharactersInString:@"0123456789ABCDEFabcdef"] invertedSet];

    if ([chatIdHex rangeOfCharacterFromSet:notHex].location != NSNotFound) {
        return nil;
    }

    return chatIdHex.uppercaseString;
}

+ (NSArray<NSString *> *)chatIdHexesFromSerialized:(NSString *)serialized
{
    NSMutableOrderedSet<NSString *> *chatIdHexes = [NSMutableOrderedSet orderedSet];

    for (NSString *part in [serialized componentsSeparatedByString:kOCTGroupPendingLeavesSeparator]) {
        NSString *chatIdHex = [self normalizedChatIdHex:[part stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]]];

        if (chatIdHex) {
            [chatIdHexes addObject:chatIdHex];
        }
    }

    return chatIdHexes.array;
}

- (instancetype)init
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _entries = [NSMutableArray array];

    return self;
}

- (NSUInteger)count
{
    @synchronized(self) {
        return self.entries.count;
    }
}

- (OCTGroupPendingLeave *)recordLeaveOfGroupNumber:(OCTToxGroupNumber)groupNumber
                                         chatIdHex:(NSString *)chatIdHex
                                       partMessage:(NSString *)partMessage
                                                at:(NSTimeInterval)now
{
    NSString *normalized = [OCTGroupPendingLeaves normalizedChatIdHex:chatIdHex];

    if (! normalized) {
        return nil;
    }

    OCTGroupPendingLeave *entry = [[OCTGroupPendingLeave alloc] initWithGroupNumber:groupNumber
                                                                         chatIdHex:normalized
                                                                       partMessage:partMessage
                                                                             dueAt:now + kOCTGroupPendingLeaveDelaySec];

    @synchronized(self) {
        // One instance holds a group number at a time: a second leave of it is the one that counts.
        NSIndexSet *replaced = [self.entries indexesOfObjectsPassingTest:^BOOL (OCTGroupPendingLeave *other, NSUInteger idx, BOOL *stop) {
            return other.groupNumber == groupNumber;
        }];
        [self.entries removeObjectsAtIndexes:replaced];
        [self.entries addObject:entry];
    }

    return entry;
}

- (BOOL)removeEntry:(OCTGroupPendingLeave *)entry
{
    @synchronized(self) {
        NSUInteger index = [self.entries indexOfObjectIdenticalTo:entry];

        if (index == NSNotFound) {
            return NO;
        }

        [self.entries removeObjectAtIndex:index];
        return YES;
    }
}

- (BOOL)containsGroupNumber:(OCTToxGroupNumber)groupNumber
{
    @synchronized(self) {
        for (OCTGroupPendingLeave *entry in self.entries) {
            if (entry.groupNumber == groupNumber) {
                return YES;
            }
        }
        return NO;
    }
}

- (NSArray<OCTGroupPendingLeave *> *)entriesForChatIdHex:(NSString *)chatIdHex
{
    NSString *normalized = [OCTGroupPendingLeaves normalizedChatIdHex:chatIdHex];

    if (! normalized) {
        return @[];
    }

    @synchronized(self) {
        return [self.entries filteredArrayUsingPredicate:[NSPredicate predicateWithBlock:^BOOL (OCTGroupPendingLeave *entry, NSDictionary *bindings) {
            return [entry.chatIdHex isEqualToString:normalized];
        }]];
    }
}

- (NSArray<OCTGroupPendingLeave *> *)dueEntriesAt:(NSTimeInterval)now
{
    NSArray<OCTGroupPendingLeave *> *due = nil;

    @synchronized(self) {
        due = [self.entries filteredArrayUsingPredicate:[NSPredicate predicateWithBlock:^BOOL (OCTGroupPendingLeave *entry, NSDictionary *bindings) {
            return entry.dueAt <= now;
        }]];
    }

    // A stable sort: leaves due at the same time keep the order they were recorded in.
    return [due sortedArrayWithOptions:NSSortStable usingComparator:^NSComparisonResult (OCTGroupPendingLeave *a, OCTGroupPendingLeave *b) {
        if (a.dueAt == b.dueAt) {
            return NSOrderedSame;
        }
        return a.dueAt < b.dueAt ? NSOrderedAscending : NSOrderedDescending;
    }];
}

- (NSArray<OCTGroupPendingLeave *> *)allEntries
{
    @synchronized(self) {
        return [self.entries copy];
    }
}

- (NSTimeInterval)nextDueAt
{
    @synchronized(self) {
        NSTimeInterval next = 0;
        BOOL found = NO;

        for (OCTGroupPendingLeave *entry in self.entries) {
            if (! found || entry.dueAt < next) {
                next = entry.dueAt;
                found = YES;
            }
        }
        return next;
    }
}

- (NSString *)serializedChatIds
{
    NSMutableOrderedSet<NSString *> *chatIdHexes = [NSMutableOrderedSet orderedSet];

    @synchronized(self) {
        for (OCTGroupPendingLeave *entry in self.entries) {
            [chatIdHexes addObject:entry.chatIdHex];
        }
    }

    return [chatIdHexes.array componentsJoinedByString:kOCTGroupPendingLeavesSeparator];
}

@end
