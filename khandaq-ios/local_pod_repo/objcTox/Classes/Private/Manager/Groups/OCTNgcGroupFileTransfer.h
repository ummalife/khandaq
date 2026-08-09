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

@interface OCTNgcGroupFileTransfer : NSObject

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
