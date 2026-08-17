// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcHskAnnounce.h"
#import "OCTNgcHistSig.h"
#import "OCTNgcHskStore.h"
#import "OCTNgcHskDirectory.h"
#import "OCTLogging.h"

@interface OCTNgcHskAnnounce ()

@property (nonatomic, copy) OCTNgcHskAnnounceSendBroadcastBlock sendBroadcastBlock;
@property (nonatomic, copy) OCTNgcHskAnnounceSelfGroupPubBlock selfGroupPubBlock;
@property (nonatomic, copy) OCTNgcHskAnnouncePeerGroupPubBlock peerGroupPubBlock;
@property (nonatomic, copy) OCTNgcHskAnnounceGroupIdBlock groupIdBlock;
@property (nonatomic, copy) OCTNgcHskAnnounceSelfToxPubBlock selfToxPubBlock;
@property (nonatomic, copy) OCTNgcHskAnnouncePeerConnectedBlock peerConnectedBlock;
@property (nonatomic, copy) OCTNgcHskAnnounceGetValueBlock getValueBlock;
@property (nonatomic, copy) OCTNgcHskAnnounceSetValueBlock setValueBlock;

/** groupId -> last send. Serialised by @synchronized(self) with the heard-set below. */
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *lastSentByGroup;
/** groupId -> peer group pubkeys that have already received a broadcast from us. */
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSMutableSet<NSString *> *> *heardByGroup;

@end

@implementation OCTNgcHskAnnounce

+ (NSTimeInterval)minIntervalSeconds
{
    return 10.0 * 60.0;
}

- (instancetype)initWithSendBroadcastBlock:(OCTNgcHskAnnounceSendBroadcastBlock)sendBroadcastBlock
                         selfGroupPubBlock:(OCTNgcHskAnnounceSelfGroupPubBlock)selfGroupPubBlock
                         peerGroupPubBlock:(OCTNgcHskAnnouncePeerGroupPubBlock)peerGroupPubBlock
                              groupIdBlock:(OCTNgcHskAnnounceGroupIdBlock)groupIdBlock
                           selfToxPubBlock:(OCTNgcHskAnnounceSelfToxPubBlock)selfToxPubBlock
                        peerConnectedBlock:(OCTNgcHskAnnouncePeerConnectedBlock)peerConnectedBlock
                             getValueBlock:(OCTNgcHskAnnounceGetValueBlock)getValueBlock
                             setValueBlock:(OCTNgcHskAnnounceSetValueBlock)setValueBlock
{
    self = [super init];
    if (self) {
        _sendBroadcastBlock = [sendBroadcastBlock copy];
        _selfGroupPubBlock = [selfGroupPubBlock copy];
        _peerGroupPubBlock = [peerGroupPubBlock copy];
        _groupIdBlock = [groupIdBlock copy];
        _selfToxPubBlock = [selfToxPubBlock copy];
        _peerConnectedBlock = [peerConnectedBlock copy];
        _getValueBlock = [getValueBlock copy];
        _setValueBlock = [setValueBlock copy];
        _lastSentByGroup = [NSMutableDictionary dictionary];
        _heardByGroup = [NSMutableDictionary dictionary];
    }
    return self;
}

#pragma mark - building

- (nullable NSData *)buildAnnouncementForGroupId:(nullable NSString *)groupId
                                    selfGroupPub:(nullable NSString *)selfGroupPubHex
                                     validFromTs:(uint64_t)validFromTs
{
    NSString *ownerToxPub = self.selfToxPubBlock ? self.selfToxPubBlock() : nil;
    if (ownerToxPub == nil || groupId.length == 0 || selfGroupPubHex.length == 0) {
        return nil;
    }

    NSData *pub = nil;
    NSData *sec = nil;
    if (! [OCTNgcHskStore ensureKeypairForGroup:groupId
                                  currentToxPub:ownerToxPub
                                       getBlock:self.getValueBlock
                                       setBlock:self.setValueBlock
                                         outPub:&pub
                                         outSec:&sec]) {
        return nil;
    }

    // The signature binds our key IN THIS GROUP — the identity the receiver reads off the
    // transport. Signing the profile's Tox key instead is unverifiable by construction.
    NSData *selfGroupPub = [OCTNgcHskStore dataFromHex:selfGroupPubHex];
    NSData *preimage = OCTNgcHistSigAnnouncePreimage(selfGroupPub, pub, validFromTs);
    NSData *signature = OCTNgcHistSigSign(preimage, sec);
    if (signature == nil) {
        return nil;
    }

    return OCTNgcHistSigBuildAnnouncement(pub, validFromTs, signature);
}

#pragma mark - sending

/**
 * Per-client spread on top of the interval, 0…119 s.
 *
 * DESIGN-ngc-signed-history-sync.md §7.1 asks for this explicitly: without it, every member of a
 * group that came back together after an outage re-announces in the same second, which is the one
 * moment the network is least able to take it. Derived from the group id and our own key rather
 * than drawn at random, so it is stable across launches (a client does not drift earlier and
 * earlier) and different for every member of the same group.
 */
static NSTimeInterval announceJitterSeconds(NSString *groupKey, NSString *selfGroupPub)
{
    NSString *seed = [NSString stringWithFormat:@"%@|%@", groupKey ?: @"", selfGroupPub ?: @""];
    NSData *digest = OCTNgcHistSigSha256([seed dataUsingEncoding:NSUTF8StringEncoding]);
    if (digest.length < 2) {
        return 0.0;
    }

    const uint8_t *bytes = digest.bytes;
    uint16_t value = (uint16_t)((bytes[0] << 8) | bytes[1]);
    return (NSTimeInterval)(value % 120);
}

- (BOOL)announceToGroupNumber:(uint32_t)groupNumber force:(BOOL)force
{
    NSString *groupId = self.groupIdBlock ? self.groupIdBlock(groupNumber) : nil;
    if (groupId.length == 0) {
        return NO;
    }
    NSString *key = groupId.lowercaseString;
    NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
    NSString *selfGroupPub = self.selfGroupPubBlock ? self.selfGroupPubBlock(groupNumber) : nil;

    if (! force) {
        @synchronized(self) {
            NSNumber *last = self.lastSentByGroup[key];
            NSTimeInterval since = now - last.doubleValue;
            NSTimeInterval interval = [[self class] minIntervalSeconds]
                                      + announceJitterSeconds(key, selfGroupPub);
            // A clock moved backwards yields a negative interval, which fails this test and simply
            // announces again — harmless, and better than going silent until the clock catches up.
            if (last != nil && since >= 0 && since < interval) {
                return NO;
            }
        }
    }

    NSData *packet = [self buildAnnouncementForGroupId:groupId
                                          selfGroupPub:selfGroupPub
                                           validFromTs:(uint64_t)(now * 1000.0)];
    if (packet == nil) {
        // Silence here means nobody can ever verify our history, and nothing else would say so.
        OCTLogInfo(@"hskAnnounce: nothing built for group %u (have_group_pub=%d)",
                   groupNumber, selfGroupPub != nil);
        return NO;
    }

    @synchronized(self) {
        // Claim the slot before sending: a failed send must not free the limit for a retry storm
        // against a group that is simply unreachable.
        self.lastSentByGroup[key] = @(now);
    }

    if (! (self.sendBroadcastBlock && self.sendBroadcastBlock(groupNumber, packet))) {
        OCTLogInfo(@"hskAnnounce: send failed for group %u", groupNumber);
        return NO;
    }
    OCTLogInfo(@"hskAnnounce: sent %lu bytes to group %u", (unsigned long)packet.length, groupNumber);
    return YES;
}

- (BOOL)announceForNewPeerWithGroupNumber:(uint32_t)groupNumber peerId:(uint32_t)peerId
{
    NSString *groupId = self.groupIdBlock ? self.groupIdBlock(groupNumber) : nil;
    NSString *peerPub = self.peerGroupPubBlock ? self.peerGroupPubBlock(groupNumber, peerId) : nil;
    if (groupId.length == 0 || peerPub.length == 0) {
        return NO;
    }

    NSString *key = groupId.lowercaseString;
    NSString *peer = peerPub.lowercaseString;

    @synchronized(self) {
        if ([self.heardByGroup[key] containsObject:peer]) {
            return NO;
        }
    }

    if (! [self announceToGroupNumber:groupNumber force:YES]) {
        // Nothing went out, so nobody may be marked as having heard it — otherwise a failed send
        // would permanently convince us this peer knows a key it never received.
        return NO;
    }

    @synchronized(self) {
        NSMutableSet *heard = self.heardByGroup[key];
        if (heard == nil) {
            heard = [NSMutableSet set];
            self.heardByGroup[key] = heard;
        }
        // Only the joining peer is marked, so a group of N waking up at once costs N broadcasts
        // rather than one. That is a known cost, not an oversight: marking everyone present would
        // need a peer-list accessor this class does not have, and guessing at the roster risks
        // marking a peer that never received the packet — which would silence us towards it for
        // good. Correctness over chattiness; the packet is 112 bytes.
        [heard addObject:peer];
    }
    return YES;
}

- (void)resetGroupId:(nullable NSString *)groupId
{
    if (groupId.length == 0) {
        return;
    }
    NSString *key = groupId.lowercaseString;
    @synchronized(self) {
        [self.lastSentByGroup removeObjectForKey:key];
        // Everyone must hear the new key, including peers that already heard the old one.
        [self.heardByGroup removeObjectForKey:key];
    }
}

#pragma mark - receiving

- (BOOL)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                                       data:(nullable NSData *)data
{
    if (! OCTNgcHistSigIsSignedPacket(data.bytes, (NSInteger)data.length,
                                      kOCTNgcHistSigPktHskAnnounce)) {
        return NO;
    }

    OCTNgcHistSigAnnouncement *ann = OCTNgcHistSigParseAnnouncement(data.bytes, (NSInteger)data.length);
    if (ann == nil) {
        // Ours by magic and type but malformed: consumed, so it cannot be mistaken for 0x01 traffic.
        return YES;
    }

    NSString *groupId = self.groupIdBlock ? self.groupIdBlock(groupNumber) : nil;
    NSString *senderGroupPubHex = self.peerGroupPubBlock ? self.peerGroupPubBlock(groupNumber, peerId) : nil;
    if (groupId.length == 0 || senderGroupPubHex.length == 0) {
        return YES;
    }

    // Ignore our own announcement echoed back, compared in group-key space — the only space both
    // sides of this packet speak.
    NSString *selfGroupPub = self.selfGroupPubBlock ? self.selfGroupPubBlock(groupNumber) : nil;
    if (selfGroupPub.length > 0 && [selfGroupPub caseInsensitiveCompare:senderGroupPubHex] == NSOrderedSame) {
        return YES;
    }

    NSData *senderGroupPub = [OCTNgcHskStore dataFromHex:senderGroupPubHex];
    NSData *preimage = OCTNgcHistSigAnnouncePreimage(senderGroupPub, ann.hskPub, ann.validFromTs);
    // Self-signed: verified against the key being announced. That proves possession only — the
    // binding to this sender comes from the transport above, never from the payload.
    if (! OCTNgcHistSigVerify(preimage, ann.signature, ann.hskPub)) {
        OCTLogInfo(@"hskAnnounce: bad signature from peer %u in group %u", peerId, groupNumber);
        return YES;
    }

    NSString *row = [OCTNgcHskDirectory rowKeyWithGroupId:groupId peerGroupPubHex:senderGroupPubHex];
    if (row == nil) {
        return YES;
    }

    OCTNgcHskRecord *existing = [OCTNgcHskDirectory decodeRecord:self.getValueBlock(row)];
    uint64_t nowMs = (uint64_t)([[NSDate date] timeIntervalSince1970] * 1000.0);
    // The packet arrived over the live channel, so the peer is connected by construction; this only
    // guards against one that dropped between delivery and here.
    BOOL connected = self.peerConnectedBlock ? self.peerConnectedBlock(groupNumber, peerId) : NO;

    switch ([OCTNgcHskDirectory decideWithExisting:existing
                                       incomingPub:ann.hskPub
                                  peerConnectedNow:connected
                                             nowMs:nowMs]) {
        case OCTNgcHskDecisionLearn:
        case OCTNgcHskDecisionReplace: {
            OCTNgcHskRecord *fresh = [[OCTNgcHskRecord alloc] initWithHskPub:ann.hskPub
                                                                 firstSeenMs:nowMs
                                                                  lastSeenMs:nowMs];
            self.setValueBlock(row, [OCTNgcHskDirectory encodeRecord:fresh]);
            OCTLogInfo(@"hskAnnounce: learned key for peer %u in group %u", peerId, groupNumber);
            break;
        }
        case OCTNgcHskDecisionRefresh: {
            OCTNgcHskRecord *bumped = [[OCTNgcHskRecord alloc] initWithHskPub:existing.hskPub
                                                                  firstSeenMs:existing.firstSeenMs
                                                                   lastSeenMs:nowMs];
            self.setValueBlock(row, [OCTNgcHskDirectory encodeRecord:bumped]);
            break;
        }
        case OCTNgcHskDecisionRecordOnly:
            // Deliberately not stored anywhere verification reads. A rejected key is only a
            // diagnostic, and writing it beside the trusted one is how "recorded" quietly becomes
            // "usable" later.
            OCTLogInfo(@"hskAnnounce: kept existing key for peer %u in group %u", peerId, groupNumber);
            break;
        case OCTNgcHskDecisionIgnore:
            break;
    }
    return YES;
}

@end
