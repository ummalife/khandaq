// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTToxConstants.h"

@class OCTChat;
@class OCTMessageAbstract;
@class OCTNgcSignedHistory;

typedef BOOL (^OCTNgcGroupHistSyncIsBlockedPeerBlock)(uint32_t groupNumber, uint32_t peerId);
typedef void (^OCTNgcGroupHistSyncDeliveryReceiptBlock)(OCTChat *chat, uint32_t messageId, uint32_t peerId);
typedef void (^OCTNgcGroupHistSyncFileSyncConfirmationBlock)(OCTChat *chat, NSString *msgIdHashHex, uint32_t syncerPeerId);

typedef BOOL (^OCTNgcGroupHistSyncSendPrivatePacketBlock)(uint32_t groupNumber,
                                                          uint32_t peerId,
                                                          NSData *packet,
                                                          NSError **error);

typedef NSString *(^OCTNgcGroupHistSyncPeerNameBlock)(uint32_t groupNumber, uint32_t peerId);
typedef NSString *(^OCTNgcGroupHistSyncDefaultPeerNameBlock)(void);
typedef NSString *(^OCTNgcGroupHistSyncPeerPublicKeyBlock)(uint32_t groupNumber, uint32_t peerId);
typedef NSString *(^OCTNgcGroupHistSyncSelfPublicKeyBlock)(uint32_t groupNumber);
typedef uint32_t (^OCTNgcGroupHistSyncPeerIdForPublicKeyBlock)(uint32_t groupNumber, NSString *publicKeyHex);
typedef BOOL (^OCTNgcGroupHistSyncIsPublicGroupBlock)(uint32_t groupNumber);
typedef uint32_t (^OCTNgcGroupHistSyncSelfPeerIdBlock)(uint32_t groupNumber);
typedef NSArray<NSNumber *> *(^OCTNgcGroupHistSyncPeerIdsBlock)(uint32_t groupNumber);
typedef OCTChat *(^OCTNgcGroupHistSyncChatForGroupBlock)(uint32_t groupNumber, BOOL createIfNeeded);
typedef NSArray<OCTMessageAbstract *> *(^OCTNgcGroupHistSyncMessagesToSyncBlock)(OCTChat *chat);
typedef BOOL (^OCTNgcGroupHistSyncMessageExistsBlock)(OCTChat *chat,
                                                      uint32_t messageId,
                                                      uint32_t peerId,
                                                      NSString *senderPubkeyHex,
                                                      NSString *peerName,
                                                      NSString *text,
                                                      NSTimeInterval dateInterval);
typedef BOOL (^OCTNgcGroupHistSyncFileExistsBlock)(OCTChat *chat, NSString *msgIdHashHex);
typedef OCTMessageAbstract *(^OCTNgcGroupHistSyncInsertSyncedMessageBlock)(OCTChat *chat,
                                                                           NSString *text,
                                                                           OCTToxMessageType type,
                                                                           uint32_t peerId,
                                                                           NSString *peerName,
                                                                           uint32_t messageId,
                                                                           NSTimeInterval dateInterval);
typedef OCTMessageAbstract *(^OCTNgcGroupHistSyncInsertSyncedFileBlock)(OCTChat *chat,
                                                                         NSString *fileName,
                                                                         NSString *filePath,
                                                                         uint64_t fileSize,
                                                                         NSString *fileUTI,
                                                                         uint32_t peerId,
                                                                         NSString *peerName,
                                                                         NSString *senderPubkeyHex,
                                                                         NSString *msgIdHashHex,
                                                                         NSTimeInterval dateInterval);
typedef NSString *(^OCTNgcGroupHistSyncIncomingFilesDirectoryBlock)(uint32_t groupNumber);
typedef NSString *(^OCTNgcGroupHistSyncFileUTIBlock)(NSString *fileName);

@interface OCTNgcGroupHistSync : NSObject

@property (nonatomic, copy, nullable) NSArray<NSData *> *(^historySyncPacketsForGroupBlock)(uint32_t groupNumber);

/**
 * KHANDAQ (external audit #2, finding 1, step 4) — signed history, wired in as an OPTIONAL twin.
 *
 * Nil means the whole version-0x02 layer is off: nothing is signed on the way out and 0x02 records
 * are dropped on the way in exactly as before this property existed. That is deliberate — it keeps
 * the signing layer switchable from the one place that owns it (the submanager) without a second
 * flag that could disagree with it.
 */
@property (nonatomic, strong, nullable) OCTNgcSignedHistory *signedHistory;

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
              fileSyncConfirmationBlock:(OCTNgcGroupHistSyncFileSyncConfirmationBlock)fileSyncConfirmationBlock;

- (void)handleIncomingPrivatePacketWithGroupNumber:(uint32_t)groupNumber
                                            peerId:(uint32_t)peerId
                                              data:(NSData *)data;

- (void)handlePeerJoinedWithGroupNumber:(uint32_t)groupNumber peerId:(uint32_t)peerId;

- (void)handleGroupConnectedWithGroupNumber:(uint32_t)groupNumber;

- (void)sendDeliveryReceiptForMessageId:(uint32_t)messageId
                            groupNumber:(uint32_t)groupNumber
                           senderPeerId:(uint32_t)senderPeerId;

- (void)scheduleBroadcastHistoryToAllPeersWithGroupNumber:(uint32_t)groupNumber;

- (nullable NSData *)buildSyncPacketForMessage:(OCTMessageAbstract *)message groupNumber:(uint32_t)groupNumber;

/**
 * The same build, additionally handing back the signed (version 0x02) twin of the very same row.
 *
 * The out-parameter exists so that the signed record can NEVER be derived from a second look at the
 * message. Author, peer name, message id and timestamp would each have to be resolved again — through
 * the volatile groupSenderPeerId, the live roster, the self-key fallback — and any of those can answer
 * differently a moment later (NGC re-issues peer ids, a peer renames, a row is re-resolved after the
 * author left). The signature would then attest to a row nobody sent, which is strictly worse than no
 * signature at all: a receiver counts it as a FAILED verification, not as an old client.
 *
 * @param signedTwin receives the signed packet, or nil when there is none — a file row, someone else's
 *                   text, or signedHistory being unset. NULL is allowed and means "unsigned only".
 * @return the unsigned packet exactly as the selector above returns it. It, not the twin, is what
 *         inserts the row on the far side, so it is emitted first and is never withheld because
 *         signing failed.
 */
- (nullable NSData *)buildSyncPacketForMessage:(OCTMessageAbstract *)message
                                    groupNumber:(uint32_t)groupNumber
                                     signedTwin:(NSData **)signedTwin;

@end

