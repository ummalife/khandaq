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

typedef void (^OCTNgcGroupFileTransferIncomingFileBlock)(uint32_t groupNumber,
                                                         uint32_t peerId,
                                                         NSString *fileName,
                                                         NSString *filePath,
                                                         uint64_t fileSize,
                                                         NSString *msgIdHex);

typedef void (^OCTNgcGroupFileTransferIncomingBeginBlock)(uint32_t groupNumber,
                                                          uint32_t peerId,
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

- (void)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
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
