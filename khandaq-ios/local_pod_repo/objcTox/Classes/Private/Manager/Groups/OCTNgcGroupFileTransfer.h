// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

FOUNDATION_EXPORT NSString *const kOCTNgcGroupFileTransferErrorDomain;

typedef NS_ENUM(NSInteger, OCTNgcGroupFileTransferError) {
    OCTNgcGroupFileTransferErrorFileTooLarge = 1,
    OCTNgcGroupFileTransferErrorMissingFile,
    OCTNgcGroupFileTransferErrorSendFailed,
    OCTNgcGroupFileTransferErrorCancelled,
};

typedef BOOL (^OCTNgcGroupFileTransferSendPacketBlock)(uint32_t groupNumber, NSData *packet, NSError **error);

// KHANDAQ (audit F-5): senderPublicKeyHex is the STABLE key of the peer this file really came from
// (the BEGIN opener for a chunked transfer, the delivering peer for a single-packet one). The msgId is
// public inside the group, so the row it lands on must be matched on the key, not on the msgId alone.
// nil when toxcore cannot resolve the peer right now — callers then keep the pre-existing behaviour.
typedef void (^OCTNgcGroupFileTransferIncomingFileBlock)(uint32_t groupNumber,
                                                         uint32_t peerId,
                                                         NSString *_Nullable senderPublicKeyHex,
                                                         NSString *fileName,
                                                         NSString *filePath,
                                                         uint64_t fileSize,
                                                         NSString *msgIdHex);

typedef void (^OCTNgcGroupFileTransferIncomingBeginBlock)(uint32_t groupNumber,
                                                          uint32_t peerId,
                                                          NSString *_Nullable senderPublicKeyHex,
                                                          NSString *fileName,
                                                          NSString *filePath,
                                                          uint64_t fileSize,
                                                          NSString *msgIdHex);

typedef void (^OCTNgcGroupFileTransferProgressBlock)(uint32_t groupNumber,
                                                     NSString *msgIdHex,
                                                     float progress);

// KHANDAQ (0.2.56): a lossless packet to ONE peer of the group. NO with the error toxcore gave; a packet
// toxcore cannot take now, because the peer's send array is full, comes back with the OCTTox error
// OCTToxErrorGroupSendCustomPacketUnknown (TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_FAIL_SEND).
typedef BOOL (^OCTNgcGroupFileTransferSendPrivatePacketBlock)(uint32_t groupNumber,
                                                              uint32_t peerId,
                                                              NSData *packet,
                                                              NSError **error);

// KHANDAQ (0.2.56): how many members a packet into the group goes to now: the peers toxcore confirmed, this
// client not counted. 0 when there is nobody to send to.
typedef NSUInteger (^OCTNgcGroupFileTransferMemberCountBlock)(uint32_t groupNumber);

@interface OCTNgcGroupFileTransfer : NSObject

/**
 * KHANDAQ (0.2.56): the chunks another member asks for again go to that member alone, through this block.
 * Unset, they go to the whole group as before.
 */
@property (nonatomic, copy, nullable) OCTNgcGroupFileTransferSendPrivatePacketBlock sendPrivatePacketBlock;

/**
 * KHANDAQ (0.2.56): what the packets of a file cost the upload pace, and whether anybody is there to get
 * them. Unset, every group counts one member. Called on the queues the transfer sends from.
 */
@property (nonatomic, copy, nullable) OCTNgcGroupFileTransferMemberCountBlock memberCountBlock;

- (instancetype)initWithSendPacketBlock:(OCTNgcGroupFileTransferSendPacketBlock)sendPacketBlock
                incomingFilesDirectory:(NSString *(^)(uint32_t groupNumber))incomingFilesDirectoryBlock
                   incomingBeginBlock:(nullable OCTNgcGroupFileTransferIncomingBeginBlock)incomingBeginBlock
                incomingCompleteBlock:(OCTNgcGroupFileTransferIncomingFileBlock)incomingCompleteBlock
                  transferProgressBlock:(nullable OCTNgcGroupFileTransferProgressBlock)transferProgressBlock;

- (instancetype)initWithSendPacketBlock:(OCTNgcGroupFileTransferSendPacketBlock)sendPacketBlock
                incomingFilesDirectory:(NSString *(^)(uint32_t groupNumber))incomingFilesDirectoryBlock
                    incomingFileBlock:(OCTNgcGroupFileTransferIncomingFileBlock)incomingFileBlock
    NS_UNAVAILABLE;

// KHANDAQ (audit F-5): peerPublicKeyHex identifies the peer that actually delivered the packet, so a
// chunked assembly can be bound to the peer that opened it (peerId alone is re-assigned on reconnect).
// KHANDAQ (0.2.56): call it on the main thread. The directory block is called right here, and the begin,
// complete and progress blocks come on the main queue: they may all use the Realm, which lives there.
- (void)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                           peerPublicKeyHex:(nullable NSString *)peerPublicKeyHex
                                       data:(NSData *)data;

- (void)sendFileAtPath:(NSString *)filePath
           groupNumber:(uint32_t)groupNumber
              msgIdHex:(NSString *)msgIdHex
              progress:(nullable void (^)(float progress))progressBlock
            completion:(void (^)(BOOL success, NSError *_Nullable error))completion;

+ (BOOL)shouldUseChunkedTransferForFileSize:(uint64_t)fileSize;
+ (NSString *)generateMsgIdHex;

- (void)cancelSendForMsgIdHex:(NSString *)msgIdHex;

@end

NS_ASSUME_NONNULL_END
