// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTToxConstants.h"

@class OCTChat;
@class OCTMessageAbstract;

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
                                                      NSString *text);
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
                                                                         NSString *msgIdHashHex,
                                                                         NSTimeInterval dateInterval);
typedef NSString *(^OCTNgcGroupHistSyncIncomingFilesDirectoryBlock)(uint32_t groupNumber);
typedef NSString *(^OCTNgcGroupHistSyncFileUTIBlock)(NSString *fileName);

@interface OCTNgcGroupHistSync : NSObject

@property (nonatomic, copy, nullable) NSArray<NSData *> *(^historySyncPacketsForGroupBlock)(uint32_t groupNumber);

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

@end

