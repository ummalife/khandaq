// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcGroupHistSync.h"
#import "OCTTox+Private.h"
#import "OCTToxConstants.h"
#import "OCTManagerConstants.h"
#import "OCTLogging.h"
#import "OCTChat.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageText.h"
#import "OCTMessageFile.h"
#import "OCTFileTools.h"
#import <toxcore/tox.h>

static const uint8_t kOCTNgcHistMagic[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35};
static const uint8_t kOCTNgcHistLayer = 0x01;
static const uint8_t kOCTNgcHistPktRequest = 0x01;
static const uint8_t kOCTNgcHistPktSyncMsg = 0x02;
static const uint8_t kOCTNgcHistPktSyncFile = 0x03;
static const uint8_t kOCTNgcHistPktDeliveryReceipt = 0x04;

static const size_t kOCTNgcHistSyncMsgHeader = 6 + 1 + 1 + 4 + 32 + 4 + 25;
static const size_t kOCTNgcHistSyncFileHeader = 6 + 1 + 1 + 32 + 32 + 4 + 25 + 255;
static const size_t kOCTNgcHistMaxPeerNameBytes = 25;
static const size_t kOCTNgcHistMaxFilenameBytes = 255;
static const size_t kOCTNgcHistMagicLength = 6;
static const size_t kOCTNgcHistMaxFilePayload = 36701;
static const NSTimeInterval kOCTNgcHistRequestCooldown = 20.0;

@interface OCTNgcGroupHistSync ()
@property (nonatomic, copy) OCTNgcGroupHistSyncSendPrivatePacketBlock sendPrivatePacketBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncPeerPublicKeyBlock peerPublicKeyBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncPeerNameBlock peerNameForPeerIdBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncDefaultPeerNameBlock defaultPeerNameBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncSelfPublicKeyBlock selfPublicKeyBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncPeerIdForPublicKeyBlock peerIdForPublicKeyBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncIsPublicGroupBlock isPublicGroupBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncSelfPeerIdBlock selfPeerIdBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncPeerIdsBlock peerIdsBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncChatForGroupBlock chatForGroupBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncMessagesToSyncBlock messagesToSyncBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncMessageExistsBlock messageExistsBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncFileExistsBlock fileExistsBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncInsertSyncedMessageBlock insertSyncedMessageBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncInsertSyncedFileBlock insertSyncedFileBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncIncomingFilesDirectoryBlock incomingFilesDirectoryBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncFileUTIBlock fileUTIBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncIsBlockedPeerBlock isBlockedPeerBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncDeliveryReceiptBlock deliveryReceiptBlock;
@property (nonatomic, copy) OCTNgcGroupHistSyncFileSyncConfirmationBlock fileSyncConfirmationBlock;
@property (nonatomic, strong) dispatch_queue_t syncQueue;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *lastServedRequestTimes;
@end

@implementation OCTNgcGroupHistSync

- (instancetype)initWithSendPrivatePacketBlock:(OCTNgcGroupHistSyncSendPrivatePacketBlock)sendPrivatePacketBlock
                          peerPublicKeyBlock:(OCTNgcGroupHistSyncPeerPublicKeyBlock)peerPublicKeyBlock
                            peerNameForPeerIdBlock:(OCTNgcGroupHistSyncPeerNameBlock)peerNameForPeerIdBlock
                           defaultPeerNameBlock:(OCTNgcGroupHistSyncDefaultPeerNameBlock)defaultPeerNameBlock
                         selfPublicKeyBlock:(OCTNgcGroupHistSyncSelfPublicKeyBlock)selfPublicKeyBlock
                    peerIdForPublicKeyBlock:(OCTNgcGroupHistSyncPeerIdForPublicKeyBlock)peerIdForPublicKeyBlock
                           isPublicGroupBlock:(OCTNgcGroupHistSyncIsPublicGroupBlock)isPublicGroupBlock
                              selfPeerIdBlock:(OCTNgcGroupHistSyncSelfPeerIdBlock)selfPeerIdBlock
                                 peerIdsBlock:(OCTNgcGroupHistSyncPeerIdsBlock)peerIdsBlock
                           chatForGroupBlock:(OCTNgcGroupHistSyncChatForGroupBlock)chatForGroupBlock
                        messagesToSyncBlock:(OCTNgcGroupHistSyncMessagesToSyncBlock)messagesToSyncBlock
                           messageExistsBlock:(OCTNgcGroupHistSyncMessageExistsBlock)messageExistsBlock
                             fileExistsBlock:(OCTNgcGroupHistSyncFileExistsBlock)fileExistsBlock
                    insertSyncedMessageBlock:(OCTNgcGroupHistSyncInsertSyncedMessageBlock)insertSyncedMessageBlock
                       insertSyncedFileBlock:(OCTNgcGroupHistSyncInsertSyncedFileBlock)insertSyncedFileBlock
                  incomingFilesDirectoryBlock:(OCTNgcGroupHistSyncIncomingFilesDirectoryBlock)incomingFilesDirectoryBlock
                               fileUTIBlock:(OCTNgcGroupHistSyncFileUTIBlock)fileUTIBlock
                        isBlockedPeerBlock:(OCTNgcGroupHistSyncIsBlockedPeerBlock)isBlockedPeerBlock
                   deliveryReceiptBlock:(OCTNgcGroupHistSyncDeliveryReceiptBlock)deliveryReceiptBlock
              fileSyncConfirmationBlock:(OCTNgcGroupHistSyncFileSyncConfirmationBlock)fileSyncConfirmationBlock
{
    NSParameterAssert(sendPrivatePacketBlock);
    NSParameterAssert(peerPublicKeyBlock);
    NSParameterAssert(selfPublicKeyBlock);
    NSParameterAssert(peerIdForPublicKeyBlock);
    NSParameterAssert(isPublicGroupBlock);
    NSParameterAssert(selfPeerIdBlock);
    NSParameterAssert(peerIdsBlock);
    NSParameterAssert(chatForGroupBlock);
    NSParameterAssert(messagesToSyncBlock);
    NSParameterAssert(messageExistsBlock);
    NSParameterAssert(fileExistsBlock);
    NSParameterAssert(insertSyncedMessageBlock);
    NSParameterAssert(insertSyncedFileBlock);
    NSParameterAssert(incomingFilesDirectoryBlock);
    NSParameterAssert(fileUTIBlock);
    NSParameterAssert(isBlockedPeerBlock);
    NSParameterAssert(deliveryReceiptBlock);
    NSParameterAssert(fileSyncConfirmationBlock);

    self = [super init];

    if (! self) {
        return nil;
    }

    _sendPrivatePacketBlock = [sendPrivatePacketBlock copy];
    _peerPublicKeyBlock = [peerPublicKeyBlock copy];
    _peerNameForPeerIdBlock = [peerNameForPeerIdBlock copy];
    _defaultPeerNameBlock = [defaultPeerNameBlock copy];
    _selfPublicKeyBlock = [selfPublicKeyBlock copy];
    _peerIdForPublicKeyBlock = [peerIdForPublicKeyBlock copy];
    _isPublicGroupBlock = [isPublicGroupBlock copy];
    _selfPeerIdBlock = [selfPeerIdBlock copy];
    _peerIdsBlock = [peerIdsBlock copy];
    _chatForGroupBlock = [chatForGroupBlock copy];
    _messagesToSyncBlock = [messagesToSyncBlock copy];
    _messageExistsBlock = [messageExistsBlock copy];
    _fileExistsBlock = [fileExistsBlock copy];
    _insertSyncedMessageBlock = [insertSyncedMessageBlock copy];
    _insertSyncedFileBlock = [insertSyncedFileBlock copy];
    _incomingFilesDirectoryBlock = [incomingFilesDirectoryBlock copy];
    _fileUTIBlock = [fileUTIBlock copy];
    _isBlockedPeerBlock = [isBlockedPeerBlock copy];
    _deliveryReceiptBlock = [deliveryReceiptBlock copy];
    _fileSyncConfirmationBlock = [fileSyncConfirmationBlock copy];
    _syncQueue = dispatch_queue_create("ngc-group-hist-sync", DISPATCH_QUEUE_SERIAL);
    _lastServedRequestTimes = [NSMutableDictionary new];

    return self;
}

#pragma mark - Public

- (void)handleIncomingPrivatePacketWithGroupNumber:(uint32_t)groupNumber
                                            peerId:(uint32_t)peerId
                                              data:(NSData *)data
{
    if (data.length < 8 || ! [self packetMatchesMagic:data]) {
        return;
    }

    const uint8_t *bytes = data.bytes;

    if (bytes[6] != kOCTNgcHistLayer) {
        return;
    }

    uint32_t selfPeerId = self.selfPeerIdBlock(groupNumber);

    if (selfPeerId > 0 && peerId == selfPeerId) {
        return;
    }

    switch (bytes[7]) {
        case kOCTNgcHistPktRequest:
            [self handleHistoryRequestWithGroupNumber:groupNumber peerId:peerId];
            break;
        case kOCTNgcHistPktSyncMsg:
            [self handleIncomingSyncMessageWithGroupNumber:groupNumber peerId:peerId data:data];
            break;
        case kOCTNgcHistPktSyncFile:
            [self handleIncomingSyncFileWithGroupNumber:groupNumber peerId:peerId data:data];
            break;
        case kOCTNgcHistPktDeliveryReceipt:
            [self handleIncomingDeliveryReceiptWithGroupNumber:groupNumber peerId:peerId data:data];
            break;
        default:
            break;
    }
}

- (void)handlePeerJoinedWithGroupNumber:(uint32_t)groupNumber peerId:(uint32_t)peerId
{
    if (! self.isPublicGroupBlock(groupNumber)) {
        return;
    }

    uint32_t selfPeerId = self.selfPeerIdBlock(groupNumber);

    if (selfPeerId > 0 && peerId == selfPeerId) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    dispatch_async(self.syncQueue, ^{
        __strong typeof(weakSelf) self = weakSelf;

        if (! self) {
            return;
        }

        uint32_t delaySeconds = 1 + arc4random_uniform(7);
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delaySeconds * NSEC_PER_SEC)), self.syncQueue, ^{
            [self sendHistoryRequestToPeerWithGroupNumber:groupNumber peerId:peerId];
            [self syncHistoryToPeerWithGroupNumber:groupNumber peerId:peerId];
            [self syncHistoryToAllPeersWithGroupNumber:groupNumber excludingPeerId:peerId];
        });
    });
}

- (void)handleGroupConnectedWithGroupNumber:(uint32_t)groupNumber
{
    [self scheduleBroadcastHistoryToAllPeersWithGroupNumber:groupNumber];
}

- (void)sendDeliveryReceiptForMessageId:(uint32_t)messageId
                            groupNumber:(uint32_t)groupNumber
                           senderPeerId:(uint32_t)senderPeerId
{
    if (messageId == 0 || senderPeerId == 0) {
        return;
    }

    [self sendDeliveryReceiptWithGroupNumber:groupNumber peerId:senderPeerId messageId:messageId];
}

- (void)scheduleBroadcastHistoryToAllPeersWithGroupNumber:(uint32_t)groupNumber
{
    if (! self.isPublicGroupBlock(groupNumber)) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    dispatch_async(self.syncQueue, ^{
        __strong typeof(weakSelf) self = weakSelf;

        if (! self) {
            return;
        }

        uint32_t delaySeconds = 1 + arc4random_uniform(4);
        dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delaySeconds * NSEC_PER_SEC)), self.syncQueue, ^{
            [self syncHistoryToAllPeersWithGroupNumber:groupNumber excludingPeerId:UINT32_MAX];
        });
    });
}

#pragma mark - Private

- (BOOL)packetMatchesMagic:(NSData *)data
{
    if (data.length < kOCTNgcHistMagicLength) {
        return NO;
    }

    return memcmp(data.bytes, kOCTNgcHistMagic, kOCTNgcHistMagicLength) == 0;
}

- (BOOL)isTimestampValid:(uint32_t)timestamp
{
    NSTimeInterval now = [[NSDate date] timeIntervalSince1970];

    if (timestamp > (uint32_t)now + (60 * 5)) {
        return NO;
    }

    if (timestamp < (uint32_t)now - (60 * 200)) {
        return NO;
    }

    return YES;
}

- (void)handleHistoryRequestWithGroupNumber:(uint32_t)groupNumber peerId:(uint32_t)peerId
{
    if (! self.isPublicGroupBlock(groupNumber)) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    dispatch_async(self.syncQueue, ^{
        __strong typeof(weakSelf) self = weakSelf;

        if (! self) {
            return;
        }

        // KHANDAQ (audit F-6): each request makes us rebuild and re-send the WHOLE history to that
        // peer, and nothing stopped one peer from asking in a loop. An honest client asks once per
        // join, so ignore repeats from the same peer inside a short window. Serialised on syncQueue.
        // Keyed on the STABLE pubkey: NGC re-issues peer ids on leave/rejoin, so a peer-id key handed
        // a rejoin loop a fresh full dump every time. Fall back to the peer id while the roster has
        // not resolved the key yet, so an honest request is never dropped for lack of one.
        NSString *requestPubkey = [self.peerPublicKeyBlock(groupNumber, peerId) uppercaseString];
        NSString *requestKey = requestPubkey.length > 0
            ? [NSString stringWithFormat:@"%u:%@", groupNumber, requestPubkey]
            : [NSString stringWithFormat:@"%u:id%u", groupNumber, peerId];
        NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
        NSNumber *lastServed = self.lastServedRequestTimes[requestKey];

        if (lastServed && (now - lastServed.doubleValue) < kOCTNgcHistRequestCooldown) {
            OCTLogInfo(@"NGC hist request throttled group=%u peer=%u", groupNumber, peerId);
            return;
        }

        if (self.lastServedRequestTimes.count > 256) {
            // keep the bookkeeping bounded (one entry per peer per group)
            [self.lastServedRequestTimes removeAllObjects];
        }

        self.lastServedRequestTimes[requestKey] = @(now);

        [self syncHistoryToPeerWithGroupNumber:groupNumber peerId:peerId];
    });
}

- (void)sendHistoryRequestToPeerWithGroupNumber:(uint32_t)groupNumber peerId:(uint32_t)peerId
{
    uint8_t bytes[] = {
        kOCTNgcHistMagic[0], kOCTNgcHistMagic[1], kOCTNgcHistMagic[2],
        kOCTNgcHistMagic[3], kOCTNgcHistMagic[4], kOCTNgcHistMagic[5],
        kOCTNgcHistLayer,
        kOCTNgcHistPktRequest,
    };
    NSData *packet = [NSData dataWithBytes:bytes length:sizeof(bytes)];

    self.sendPrivatePacketBlock(groupNumber, peerId, packet, NULL);
}

- (void)syncHistoryToAllPeersWithGroupNumber:(uint32_t)groupNumber excludingPeerId:(uint32_t)excludePeerId
{
    uint32_t selfPeerId = self.selfPeerIdBlock(groupNumber);

    for (NSNumber *peerNumber in self.peerIdsBlock(groupNumber)) {
        uint32_t peerId = peerNumber.unsignedIntValue;

        if (peerId == selfPeerId || peerId == excludePeerId) {
            continue;
        }

        [self syncHistoryToPeerWithGroupNumber:groupNumber peerId:peerId];
    }
}

- (void)syncHistoryToPeerWithGroupNumber:(uint32_t)groupNumber peerId:(uint32_t)peerId
{
    uint32_t selfPeerId = self.selfPeerIdBlock(groupNumber);

    if (selfPeerId > 0 && peerId == selfPeerId) {
        return;
    }

    if (! self.historySyncPacketsForGroupBlock) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    dispatch_async(dispatch_get_main_queue(), ^{
        __strong typeof(weakSelf) self = weakSelf;

        if (! self) {
            return;
        }

        NSArray<NSData *> *packets = self.historySyncPacketsForGroupBlock(groupNumber);

        if (packets.count == 0) {
            return;
        }

        dispatch_async(self.syncQueue, ^{
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return;
            }

            for (NSData *packet in packets) {
                uint32_t delayMs = 50 + arc4random_uniform(51);
                usleep(delayMs * 1000);
                self.sendPrivatePacketBlock(groupNumber, peerId, packet, NULL);
            }
        });
    });
}

- (nullable NSData *)buildSyncPacketForMessage:(OCTMessageAbstract *)message groupNumber:(uint32_t)groupNumber
{
    if (message.messageText) {
        return [self buildSyncMessagePacketForMessage:message groupNumber:groupNumber];
    }

    if (message.messageFile) {
        return [self buildSyncFilePacketForMessage:message groupNumber:groupNumber];
    }

    return nil;
}

- (nullable NSData *)buildSyncMessagePacketForMessage:(OCTMessageAbstract *)message
                                          groupNumber:(uint32_t)groupNumber
{
    OCTMessageText *messageText = message.messageText;

    if (! messageText || messageText.text.length == 0) {
        return nil;
    }

    // KHANDAQ (audit F-6): only a genuinely OUTGOING row may leave under OUR OWN key. A history-synced
    // row is someone else's message, and its groupSenderPeerId is 0 whenever the author was not in our
    // roster at insert time — offline, already left, or a pubkey an attacker made up. Claiming self
    // there re-broadcast that row to the whole group signed-by-implication as ours (the receivers drop
    // packets carrying their own key, so this was the ONLY way to forge a message as us).
    //
    // KHANDAQ (audit F-6, round 4): never re-serve a synced TEXT row at all. The only author identity we
    // keep for one is the VOLATILE groupSenderPeerId captured at insert time, and NGC re-issues peer ids
    // on leave/rejoin — so resolving it here could hand the row to the whole group under a DIFFERENT,
    // currently present member's key. That is an honest client minting exactly the forged attribution
    // this finding is about. We cannot verify the author of a second-hand text row, so we do not pass it
    // on; the peers that received the message live still serve it, which is where history comes from.
    uint32_t senderPeerId = message.groupSenderPeerId;

    if (message.groupHistorySync) {
        return nil;
    }

    NSString *senderPubkeyHex = senderPeerId == 0
        ? self.selfPublicKeyBlock(groupNumber)
        : self.peerPublicKeyBlock(groupNumber, senderPeerId);

    if (senderPubkeyHex.length == 0) {
        return nil;
    }

    NSString *peerName = messageText.groupPeerName.length > 0 ? messageText.groupPeerName : [self peerNameForSenderPeerId:senderPeerId groupNumber:groupNumber];

    return [self buildSyncMessagePacketForMessageText:messageText
                                       senderPubkeyHex:senderPubkeyHex
                                              peerName:peerName
                                         dateInterval:message.dateInterval];
}

- (nullable NSData *)buildSyncMessagePacketForMessageText:(OCTMessageText *)messageText
                                           senderPubkeyHex:(NSString *)senderPubkeyHex
                                                  peerName:(NSString *)peerName
                                             dateInterval:(NSTimeInterval)dateInterval
{
    NSData *textData = [messageText.text dataUsingEncoding:NSUTF8StringEncoding];

    if (! textData || textData.length == 0 || textData.length > 37000) {
        return nil;
    }

    size_t totalLength = kOCTNgcHistSyncMsgHeader + textData.length;

    if (totalLength > 40000) {
        return nil;
    }

    NSMutableData *packet = [NSMutableData dataWithLength:totalLength];
    uint8_t *bytes = packet.mutableBytes;

    memcpy(bytes, kOCTNgcHistMagic, kOCTNgcHistMagicLength);
    bytes[6] = kOCTNgcHistLayer;
    bytes[7] = kOCTNgcHistPktSyncMsg;

    uint32_t messageId = messageText.messageId;
    bytes[8] = (uint8_t)((messageId >> 24) & 0xFF);
    bytes[9] = (uint8_t)((messageId >> 16) & 0xFF);
    bytes[10] = (uint8_t)((messageId >> 8) & 0xFF);
    bytes[11] = (uint8_t)(messageId & 0xFF);

    uint8_t *senderKey = [OCTTox hexStringToBin:senderPubkeyHex];

    if (! senderKey) {
        return nil;
    }

    memcpy(bytes + 12, senderKey, MIN((size_t)32, TOX_GROUP_PEER_PUBLIC_KEY_SIZE));
    free(senderKey);

    uint32_t timestamp = (uint32_t)dateInterval;
    bytes[44] = (uint8_t)((timestamp >> 24) & 0xFF);
    bytes[45] = (uint8_t)((timestamp >> 16) & 0xFF);
    bytes[46] = (uint8_t)((timestamp >> 8) & 0xFF);
    bytes[47] = (uint8_t)(timestamp & 0xFF);

    [self writePaddedUTF8String:peerName
                         maxBytes:kOCTNgcHistMaxPeerNameBytes
                          atOffset:48
                         inBuffer:bytes];

    memcpy(bytes + kOCTNgcHistSyncMsgHeader, textData.bytes, textData.length);

    return packet;
}

- (nullable NSData *)buildSyncFilePacketForMessage:(OCTMessageAbstract *)message
                                       groupNumber:(uint32_t)groupNumber
{
    OCTMessageFile *messageFile = message.messageFile;

    if (! messageFile || messageFile.fileType != OCTMessageFileTypeReady) {
        return nil;
    }

    if (messageFile.groupMsgIdHashHex.length == 0 || messageFile.fileSize == 0 || messageFile.fileSize > kOCTNgcHistMaxFilePayload) {
        return nil;
    }

    NSString *filePath = [messageFile filePath];

    if (filePath.length == 0) {
        return nil;
    }

    NSData *fileData = [NSData dataWithContentsOfFile:filePath];

    if (! fileData || fileData.length == 0 || fileData.length > kOCTNgcHistMaxFilePayload) {
        return nil;
    }

    // KHANDAQ (#170): attribute the served file to its REAL author. The old resolve went through the
    // VOLATILE groupSenderPeerId (NGC reuses peer ids across leave/rejoin), and any unresolved sender
    // (peerId 0 — e.g. a sync-inserted file whose author was offline at insert time) was served under
    // OUR OWN key. The receiver then saw a "new" message from us and stored a duplicate bubble.
    // Prefer the pubkey frozen at receipt (#82); claim self only for genuinely outgoing rows; if the
    // author cannot be determined reliably, do not serve the file at all.
    uint32_t senderPeerId = message.groupSenderPeerId;
    NSString *senderPubkeyHex = messageFile.groupSenderPubkey;

    // KHANDAQ (audit F-6, round 5): NEVER re-serve a second-hand file, matching the synced-TEXT rule
    // above and Android's was_synced skip. The author pubkey frozen on a synced row is an UNSIGNED claim
    // made by the peer that synced it to us; we cannot check it, so passing the file on would make us
    // corroborate that claim to the whole group (and bump the row's sync_confirmations on Android
    // receivers). Round 4 still served such a row while its claimed author was in the live peer list —
    // that tests presence, not authorship, and an attacker simply names a member who is present, so it
    // is gone. We only hand out history we received first-hand: live rows and our own.
    if (message.groupHistorySync) {
        return nil;
    }

    if (senderPubkeyHex.length == 0) {
        if (senderPeerId == 0) {
            // no frozen pubkey + peer id 0 => our own outgoing file
            senderPubkeyHex = self.selfPublicKeyBlock(groupNumber);
        }
        else {
            // legacy incoming row without frozen pubkey — best-effort volatile resolve
            senderPubkeyHex = self.peerPublicKeyBlock(groupNumber, senderPeerId);
        }
    }

    OCTLogInfo(@"NGC sync-file serve hash=%@ name=%@ senderPeerId=%u", messageFile.groupMsgIdHashHex, messageFile.fileName, senderPeerId);

    if (senderPubkeyHex.length == 0) {
        return nil;
    }

    size_t totalLength = kOCTNgcHistSyncFileHeader + fileData.length;

    if (totalLength > 40000) {
        return nil;
    }

    NSMutableData *packet = [NSMutableData dataWithLength:totalLength];
    uint8_t *bytes = packet.mutableBytes;

    memcpy(bytes, kOCTNgcHistMagic, kOCTNgcHistMagicLength);
    bytes[6] = kOCTNgcHistLayer;
    bytes[7] = kOCTNgcHistPktSyncFile;

    uint8_t *msgIdHash = [OCTTox hexStringToBin:messageFile.groupMsgIdHashHex];

    if (! msgIdHash) {
        return nil;
    }

    memcpy(bytes + 8, msgIdHash, MIN((size_t)32, 32));
    free(msgIdHash);

    uint8_t *senderKey = [OCTTox hexStringToBin:senderPubkeyHex];

    if (! senderKey) {
        return nil;
    }

    memcpy(bytes + 40, senderKey, MIN((size_t)32, TOX_GROUP_PEER_PUBLIC_KEY_SIZE));
    free(senderKey);

    uint32_t timestamp = (uint32_t)message.dateInterval;
    bytes[72] = (uint8_t)((timestamp >> 24) & 0xFF);
    bytes[73] = (uint8_t)((timestamp >> 16) & 0xFF);
    bytes[74] = (uint8_t)((timestamp >> 8) & 0xFF);
    bytes[75] = (uint8_t)(timestamp & 0xFF);

    NSString *peerName = [self peerNameForSenderPeerId:senderPeerId groupNumber:groupNumber];
    [self writePaddedUTF8String:peerName
                         maxBytes:kOCTNgcHistMaxPeerNameBytes
                          atOffset:76
                         inBuffer:bytes];

    NSString *fileName = messageFile.fileName.length > 0 ? messageFile.fileName : @"file";
    [self writePaddedUTF8String:fileName
                         maxBytes:kOCTNgcHistMaxFilenameBytes
                          atOffset:101
                         inBuffer:bytes];

    memcpy(bytes + kOCTNgcHistSyncFileHeader, fileData.bytes, fileData.length);

    return packet;
}

- (void)handleIncomingSyncMessageWithGroupNumber:(uint32_t)groupNumber
                                          peerId:(uint32_t)peerId
                                            data:(NSData *)data
{
    if (data.length <= kOCTNgcHistSyncMsgHeader) {
        return;
    }

    const uint8_t *bytes = data.bytes;
    NSString *senderPubkeyHex = [[OCTTox binToHexString:(uint8_t *)(bytes + 12) length:32] uppercaseString];

    if ([self isSelfPubkeyHex:senderPubkeyHex groupNumber:groupNumber]) {
        return;
    }

    uint32_t messageId = ((uint32_t)bytes[8] << 24) | ((uint32_t)bytes[9] << 16) | ((uint32_t)bytes[10] << 8) | (uint32_t)bytes[11];
    uint32_t timestamp = ((uint32_t)bytes[44] << 24) | ((uint32_t)bytes[45] << 16) | ((uint32_t)bytes[46] << 8) | (uint32_t)bytes[47];

    if (! [self isTimestampValid:timestamp]) {
        return;
    }

    NSString *peerName = [self peerNameFromBytes:bytes + 48 length:kOCTNgcHistMaxPeerNameBytes];
    NSData *textData = [data subdataWithRange:NSMakeRange(kOCTNgcHistSyncMsgHeader, data.length - kOCTNgcHistSyncMsgHeader)];
    NSString *text = [[NSString alloc] initWithData:textData encoding:NSUTF8StringEncoding];

    if (text.length == 0 || textData.length > 37000) {
        return;
    }

    uint32_t senderPeerId = self.peerIdForPublicKeyBlock(groupNumber, senderPubkeyHex);

    if (senderPeerId > 0 && self.isBlockedPeerBlock(groupNumber, senderPeerId)) {
        return;
    }

    OCTChat *chat = self.chatForGroupBlock(groupNumber, YES);

    if (! chat) {
        return;
    }

    if (self.messageExistsBlock(chat, messageId, senderPeerId, senderPubkeyHex, peerName, text, (NSTimeInterval)timestamp)) {
        if (self.deliveryReceiptBlock) {
            self.deliveryReceiptBlock(chat, messageId, peerId);
        }
        return;
    }

    self.insertSyncedMessageBlock(chat,
                                  text,
                                  OCTToxMessageTypeNormal,
                                  senderPeerId,
                                  peerName,
                                  messageId,
                                  (NSTimeInterval)timestamp);

    if (senderPeerId > 0) {
        [self sendDeliveryReceiptWithGroupNumber:groupNumber peerId:senderPeerId messageId:messageId];
    }
}

- (void)handleIncomingSyncFileWithGroupNumber:(uint32_t)groupNumber
                                       peerId:(uint32_t)peerId
                                         data:(NSData *)data
{
    if (data.length <= kOCTNgcHistSyncFileHeader) {
        return;
    }

    const uint8_t *bytes = data.bytes;
    NSString *msgIdHashHex = [[OCTTox binToHexString:(uint8_t *)(bytes + 8) length:32] uppercaseString];
    NSString *senderPubkeyHex = [[OCTTox binToHexString:(uint8_t *)(bytes + 40) length:32] uppercaseString];

    if ([self isSelfPubkeyHex:senderPubkeyHex groupNumber:groupNumber]) {
        return;
    }

    uint32_t timestamp = ((uint32_t)bytes[72] << 24) | ((uint32_t)bytes[73] << 16) | ((uint32_t)bytes[74] << 8) | (uint32_t)bytes[75];

    if (! [self isTimestampValid:timestamp]) {
        return;
    }

    NSString *peerName = [self peerNameFromBytes:bytes + 76 length:kOCTNgcHistMaxPeerNameBytes];
    NSString *fileName = [self peerNameFromBytes:bytes + 101 length:kOCTNgcHistMaxFilenameBytes];

    if ([fileName isEqualToString:@"peer"]) {
        fileName = @"file";
    }

    NSData *fileData = [data subdataWithRange:NSMakeRange(kOCTNgcHistSyncFileHeader, data.length - kOCTNgcHistSyncFileHeader)];

    if (fileData.length == 0 || fileData.length > kOCTNgcHistMaxFilePayload) {
        return;
    }

    OCTChat *chat = self.chatForGroupBlock(groupNumber, YES);

    if (! chat) {
        return;
    }

    if (self.fileExistsBlock(chat, msgIdHashHex)) {
        if (self.fileSyncConfirmationBlock) {
            self.fileSyncConfirmationBlock(chat, msgIdHashHex, peerId);
        }
        return;
    }

    NSString *directory = self.incomingFilesDirectoryBlock(groupNumber);

    if (directory.length == 0) {
        return;
    }

    NSString *filePath = [OCTFileTools createNewFilePathInDirectory:directory fileName:fileName];

    if (! [fileData writeToFile:filePath atomically:YES]) {
        return;
    }

    uint32_t senderPeerId = self.peerIdForPublicKeyBlock(groupNumber, senderPubkeyHex);

    if (senderPeerId > 0 && self.isBlockedPeerBlock(groupNumber, senderPeerId)) {
        [[NSFileManager defaultManager] removeItemAtPath:filePath error:nil];
        return;
    }

    NSString *fileUTI = self.fileUTIBlock(fileName);

    self.insertSyncedFileBlock(chat,
                               fileName,
                               filePath,
                               (uint64_t)fileData.length,
                               fileUTI,
                               senderPeerId,
                               peerName,
                               senderPubkeyHex,
                               msgIdHashHex,
                               (NSTimeInterval)timestamp);
}

- (void)handleIncomingDeliveryReceiptWithGroupNumber:(uint32_t)groupNumber
                                              peerId:(uint32_t)peerId
                                                data:(NSData *)data
{
    if (data.length < 12) {
        return;
    }

    const uint8_t *bytes = data.bytes;
    uint32_t messageId = ((uint32_t)bytes[8] << 24) | ((uint32_t)bytes[9] << 16) | ((uint32_t)bytes[10] << 8) | (uint32_t)bytes[11];
    OCTChat *chat = self.chatForGroupBlock(groupNumber, NO);

    if (! chat) {
        return;
    }

    if (self.deliveryReceiptBlock) {
        self.deliveryReceiptBlock(chat, messageId, peerId);
    }

    OCTLogInfo(@"NGC group delivery ack group=%u peer=%u messageId=%u", groupNumber, peerId, messageId);
}

- (void)sendDeliveryReceiptWithGroupNumber:(uint32_t)groupNumber
                                    peerId:(uint32_t)peerId
                                 messageId:(uint32_t)messageId
{
    __weak typeof(self) weakSelf = self;

    dispatch_async(self.syncQueue, ^{
        usleep(200 * 1000);

        __strong typeof(weakSelf) self = weakSelf;

        if (! self) {
            return;
        }

        uint8_t bytes[12];
        memcpy(bytes, kOCTNgcHistMagic, kOCTNgcHistMagicLength);
        bytes[6] = kOCTNgcHistLayer;
        bytes[7] = kOCTNgcHistPktDeliveryReceipt;
        bytes[8] = (uint8_t)((messageId >> 24) & 0xFF);
        bytes[9] = (uint8_t)((messageId >> 16) & 0xFF);
        bytes[10] = (uint8_t)((messageId >> 8) & 0xFF);
        bytes[11] = (uint8_t)(messageId & 0xFF);

        NSData *packet = [NSData dataWithBytes:bytes length:sizeof(bytes)];
        self.sendPrivatePacketBlock(groupNumber, peerId, packet, NULL);
    });
}

- (NSString *)peerNameForSenderPeerId:(uint32_t)senderPeerId groupNumber:(uint32_t)groupNumber
{
    if (senderPeerId == 0) {
        return self.defaultPeerNameBlock ? self.defaultPeerNameBlock() : @"peer";
    }

    NSString *name = self.peerNameForPeerIdBlock(groupNumber, senderPeerId);

    return name.length > 0 ? name : @"peer";
}

- (BOOL)isSelfPubkeyHex:(NSString *)pubkeyHex groupNumber:(uint32_t)groupNumber
{
    NSString *selfPubkeyHex = [[self.selfPublicKeyBlock(groupNumber) uppercaseString] copy];

    return selfPubkeyHex.length > 0 && [pubkeyHex isEqualToString:selfPubkeyHex];
}

- (void)writePaddedUTF8String:(NSString *)string
                      maxBytes:(size_t)maxBytes
                       atOffset:(size_t)offset
                      inBuffer:(uint8_t *)bytes
{
    NSData *data = [string dataUsingEncoding:NSUTF8StringEncoding];
    size_t length = data.length > maxBytes ? maxBytes : data.length;

    if (length > 0) {
        memcpy(bytes + offset, data.bytes, length);
    }
}

- (NSString *)peerNameFromBytes:(const uint8_t *)bytes length:(size_t)length
{
    size_t end = length;

    while (end > 0 && bytes[end - 1] == 0) {
        end--;
    }

    if (end == 0) {
        return @"peer";
    }

    NSString *name = [[NSString alloc] initWithBytes:bytes length:end encoding:NSUTF8StringEncoding];

    return name.length > 0 ? name : @"peer";
}

@end
