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

// KHANDAQ (0.2.57): a packet to the member `peerId` of the group alone.
typedef BOOL (^OCTNgcGroupFileTransferSendPrivatePacketBlock)(uint32_t groupNumber,
                                                              uint32_t peerId,
                                                              NSData *packet,
                                                              NSError **error);

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

// KHANDAQ (0.2.57): an incoming chunked file that will never complete was dropped, with its partial file: no
// chunk came for a minute (the sender's network went, or ours and the group took longer to come back), or its
// slot was needed for another file. senderPublicKeyHex is the opener's key, as in the begin block.
typedef void (^OCTNgcGroupFileTransferIncomingDroppedBlock)(uint32_t groupNumber,
                                                            NSString *_Nullable senderPublicKeyHex,
                                                            NSString *msgIdHex);

@interface OCTNgcGroupFileTransfer : NSObject

/// KHANDAQ (0.2.57): hears every incoming file dropped before it was complete, on the main queue like the
/// other blocks. Set it before the first packet comes.
@property (atomic, copy, nullable) OCTNgcGroupFileTransferIncomingDroppedBlock incomingDroppedBlock;

/// KHANDAQ (0.2.57): the names, in lower case, the messages of the group give their files in its folder, gone or
/// not (OCTFileTools createNewGroupFilePathInDirectory:fileName:namesInUse:). Called on the main thread with the
/// directory block, for a file that is about to be stored. Set it before the first packet comes.
@property (atomic, copy, nullable) NSSet<NSString *> *(^incomingFileNamesInUseBlock)(uint32_t groupNumber);

/// KHANDAQ (0.2.57): how many members a packet to the group goes to now, for the pace of the group file packets this
/// device sends: a packet costs its bytes once for each. Called on the queues that send, never on the main one. Set it
/// before the first file goes; without it a packet costs as if it went to one member.
@property (atomic, copy, nullable) NSUInteger (^memberCountBlock)(uint32_t groupNumber);

/// KHANDAQ (0.2.57): sends a packet of a group file to one member alone: the whole of a file this device sent, to a
/// member that asks for all of it (an Android that has the file's message and no BEGIN of it asks so). Called on the
/// queues that send, never on the main one. Set it before the first request comes; without it such a request is not
/// served.
@property (atomic, copy, nullable) OCTNgcGroupFileTransferSendPrivatePacketBlock sendPrivatePacketBlock;

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

/// KHANDAQ (0.2.57): the paths of the incoming files being written now, two per chunked file under way: where it
/// goes once complete, and its hidden file (partialPathForGroupFilePath:msgIdHex:). From any thread.
- (NSSet<NSString *> *)incomingFilePathsInProgress;

/**
 * KHANDAQ (0.2.57): the hidden file the chunks of the group file `msgIdHex`, to go to `filePath`, are written to until
 * the last one is in: ".<msgIdHex in lower case>.part" in the folder of `filePath`. Nothing is under the file's name
 * before that.
 */
+ (NSString *)partialPathForGroupFilePath:(NSString *)filePath msgIdHex:(NSString *)msgIdHex;

@end

NS_ASSUME_NONNULL_END
