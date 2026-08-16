// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcSignedHistory.h"
#import "OCTNgcHistSig.h"
#import "OCTNgcHskStore.h"
#import "OCTNgcHskDirectory.h"
#import "OCTLogging.h"

static NSString *const kVerifiedRowPrefix = @"kqhistver_";

@interface OCTNgcSignedHistory ()
@property (nonatomic, copy) OCTNgcSignedHistorySendPrivateBlock sendPrivateBlock;
@property (nonatomic, copy) OCTNgcSignedHistoryGroupIdBlock groupIdBlock;
@property (nonatomic, copy) OCTNgcSignedHistorySelfGroupPubBlock selfGroupPubBlock;
@property (nonatomic, copy) OCTNgcSignedHistorySelfToxPubBlock selfToxPubBlock;
@property (nonatomic, copy) OCTNgcSignedHistoryGetValueBlock getValueBlock;
@property (nonatomic, copy) OCTNgcSignedHistorySetValueBlock setValueBlock;

/** groupId(lowercase) -> secret key, resolved outside the database queue by -prepareKeyForGroupNumber:. */
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSData *> *secByGroup;
@end

@implementation OCTNgcSignedHistory

- (instancetype)initWithSendPrivateBlock:(OCTNgcSignedHistorySendPrivateBlock)sendPrivateBlock
                            groupIdBlock:(OCTNgcSignedHistoryGroupIdBlock)groupIdBlock
                       selfGroupPubBlock:(OCTNgcSignedHistorySelfGroupPubBlock)selfGroupPubBlock
                         selfToxPubBlock:(OCTNgcSignedHistorySelfToxPubBlock)selfToxPubBlock
                           getValueBlock:(OCTNgcSignedHistoryGetValueBlock)getValueBlock
                           setValueBlock:(OCTNgcSignedHistorySetValueBlock)setValueBlock
{
    self = [super init];
    if (self) {
        _sendPrivateBlock = [sendPrivateBlock copy];
        _groupIdBlock = [groupIdBlock copy];
        _selfGroupPubBlock = [selfGroupPubBlock copy];
        _selfToxPubBlock = [selfToxPubBlock copy];
        _getValueBlock = [getValueBlock copy];
        _setValueBlock = [setValueBlock copy];
        _secByGroup = [NSMutableDictionary dictionary];
    }
    return self;
}

/** Four bytes big-endian — the same value and width the unsigned packet carries. */
static NSData *msgIdData(uint32_t messageId)
{
    uint8_t bytes[4] = {
        (uint8_t)((messageId >> 24) & 0xFF), (uint8_t)((messageId >> 16) & 0xFF),
        (uint8_t)((messageId >> 8) & 0xFF), (uint8_t)(messageId & 0xFF),
    };
    return [NSData dataWithBytes:bytes length:sizeof(bytes)];
}

/** Exactly 25 bytes: truncated or null-padded, as the wire format fixes it. */
static NSData *peerNameField(NSString *peerName)
{
    NSMutableData *out = [NSMutableData dataWithLength:kOCTNgcHistSigPeerNameSize];
    NSData *raw = [peerName dataUsingEncoding:NSUTF8StringEncoding];
    if (raw.length > 0) {
        [out replaceBytesInRange:NSMakeRange(0, MIN(raw.length, kOCTNgcHistSigPeerNameSize))
                       withBytes:raw.bytes
                          length:MIN(raw.length, kOCTNgcHistSigPeerNameSize)];
    }
    return out;
}

- (BOOL)prepareKeyForGroupNumber:(uint32_t)groupNumber
{
    NSString *groupId = self.groupIdBlock ? self.groupIdBlock(groupNumber) : nil;
    NSString *ownerToxPub = self.selfToxPubBlock ? self.selfToxPubBlock() : nil;
    if (groupId.length == 0 || ownerToxPub == nil) {
        return NO;
    }

    NSData *pub = nil;
    NSData *sec = nil;
    if (! [OCTNgcHskStore ensureKeypairForGroup:groupId
                                  currentToxPub:ownerToxPub
                                       getBlock:self.getValueBlock
                                       setBlock:self.setValueBlock
                                         outPub:&pub
                                         outSec:&sec]) {
        OCTLogInfo(@"signedHistory: no signing key for group %u; rows will go out unsigned", groupNumber);
        return NO;
    }

    @synchronized(self) {
        self.secByGroup[groupId.lowercaseString] = sec;
    }
    return YES;
}

#pragma mark - emitting

- (nullable NSData *)buildSignedTextForGroupNumber:(uint32_t)groupNumber
                                      authorPubHex:(nullable NSString *)authorPubHex
                                         messageId:(uint32_t)messageId
                                         timestamp:(uint64_t)timestampSeconds
                                          peerName:(nullable NSString *)peerName
                                              text:(nullable NSString *)text
{
    NSString *groupId = self.groupIdBlock ? self.groupIdBlock(groupNumber) : nil;
    NSString *selfGroupPub = self.selfGroupPubBlock ? self.selfGroupPubBlock(groupNumber) : nil;
    NSString *ownerToxPub = self.selfToxPubBlock ? self.selfToxPubBlock() : nil;
    if (groupId.length == 0 || selfGroupPub.length == 0 || ownerToxPub == nil || text == nil) {
        return nil;
    }

    // Only our own rows. A signature is a claim about who wrote something, and this is the only
    // claim we are in a position to make.
    if (authorPubHex.length == 0
        || [authorPubHex caseInsensitiveCompare:selfGroupPub] != NSOrderedSame) {
        return nil;
    }

    NSData *textUtf8 = [text dataUsingEncoding:NSUTF8StringEncoding];
    if (textUtf8.length == 0 || textUtf8.length > kOCTNgcHistSigMaxTextBytes) {
        return nil;
    }

    NSData *groupIdData = [OCTNgcHskStore dataFromHex:groupId];
    NSData *authorPub = [OCTNgcHskStore dataFromHex:authorPubHex];
    NSData *msgId = msgIdData(messageId);
    NSData *preimage = OCTNgcHistSigHistPreimage(groupIdData, authorPub, msgId, timestampSeconds, textUtf8);
    if (preimage == nil) {
        return nil;
    }

    NSData *sec = nil;
    @synchronized(self) {
        sec = self.secByGroup[groupId.lowercaseString];
    }
    if (sec == nil) {
        // -prepareKeyForGroupNumber: was not called, or found no key. Deliberately NOT resolved here:
        // this runs inside the database's own queue and reaching the store would deadlock it.
        return nil;
    }

    NSData *signature = OCTNgcHistSigSign(preimage, sec);
    if (signature == nil) {
        return nil;
    }

    return OCTNgcHistSigBuildSignedText(msgId, authorPub, timestampSeconds,
                                        peerNameField(peerName), textUtf8, signature);
}

- (BOOL)sendSignedTextToGroupNumber:(uint32_t)groupNumber
                             peerId:(uint32_t)peerId
                       authorPubHex:(nullable NSString *)authorPubHex
                          messageId:(uint32_t)messageId
                          timestamp:(uint64_t)timestampSeconds
                           peerName:(nullable NSString *)peerName
                               text:(nullable NSString *)text
{
    NSData *packet = [self buildSignedTextForGroupNumber:groupNumber
                                            authorPubHex:authorPubHex
                                               messageId:messageId
                                               timestamp:timestampSeconds
                                                peerName:peerName
                                                    text:text];
    if (packet == nil) {
        // Not an error: most rows in a sync are other people's, and those are not ours to sign.
        return NO;
    }
    return self.sendPrivateBlock && self.sendPrivateBlock(groupNumber, peerId, packet);
}

#pragma mark - verdict store

- (nullable NSString *)verifiedRowKeyForGroupId:(NSString *)groupId
                                          msgId:(NSData *)msgId
                                      authorPub:(NSData *)authorPub
{
    if (groupId.length == 0 || msgId.length == 0 || authorPub.length == 0) {
        return nil;
    }
    return [NSString stringWithFormat:@"%@%@|%@|%@", kVerifiedRowPrefix, groupId.lowercaseString,
            [OCTNgcHskStore hexFromData:msgId], [OCTNgcHskStore hexFromData:authorPub]];
}

- (BOOL)isAuthorVerifiedForGroupId:(nullable NSString *)groupId
                         messageId:(uint32_t)messageId
                      authorPubHex:(nullable NSString *)authorPubHex
{
    NSData *authorPub = [OCTNgcHskStore dataFromHex:authorPubHex];
    if (groupId.length == 0 || authorPub.length != kOCTNgcHistSigPubKeySize) {
        return NO;
    }
    NSString *row = [self verifiedRowKeyForGroupId:groupId
                                             msgId:msgIdData(messageId)
                                         authorPub:authorPub];
    return row != nil && [@"1" isEqualToString:(self.getValueBlock(row) ?: @"")];
}

#pragma mark - receiving

- (BOOL)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                                       data:(nullable NSData *)data
{
    if (! OCTNgcHistSigIsSignedPacket(data.bytes, (NSInteger)data.length,
                                      kOCTNgcHistSigPktSignedText)) {
        return NO;
    }

    OCTNgcHistSigSignedText *st = OCTNgcHistSigParseSignedText(data.bytes, (NSInteger)data.length);
    if (st == nil) {
        // Ours by magic and type but malformed: consumed, so it cannot fall into the 0x01 arms.
        return YES;
    }

    NSString *groupId = self.groupIdBlock ? self.groupIdBlock(groupNumber) : nil;
    NSData *groupIdData = [OCTNgcHskStore dataFromHex:groupId];
    if (groupIdData.length != kOCTNgcHistSigGroupIdSize) {
        return YES;
    }

    // The author is a claim in the packet — that IS the defect being fixed — so it is worth
    // something only once the signature over it checks out against the key we learned for that
    // author from a live announcement.
    NSString *authorHex = [OCTNgcHskStore hexFromData:st.authorPub];
    NSString *dirRow = [OCTNgcHskDirectory rowKeyWithGroupId:groupId peerGroupPubHex:authorHex];
    OCTNgcHskRecord *known = [OCTNgcHskDirectory decodeRecord:dirRow ? self.getValueBlock(dirRow) : nil];
    if (known.hskPub.length != kOCTNgcHistSigPubKeySize) {
        // Never heard this author announce a key, so there is nothing to check against. Not a
        // failure — just an author we cannot vouch for yet.
        return YES;
    }

    NSData *preimage = OCTNgcHistSigHistPreimage(groupIdData, st.authorPub, st.msgId,
                                                 st.timestamp, st.textUtf8);
    if (! OCTNgcHistSigVerify(preimage, st.signature, known.hskPub)) {
        OCTLogInfo(@"signedHistory: BAD SIGNATURE from peer %u in group %u", peerId, groupNumber);
        return YES;
    }

    NSString *row = [self verifiedRowKeyForGroupId:groupId msgId:st.msgId authorPub:st.authorPub];
    if (row != nil) {
        self.setValueBlock(row, @"1");
        OCTLogInfo(@"signedHistory: verified a row in group %u", groupNumber);
    }
    return YES;
}

@end
