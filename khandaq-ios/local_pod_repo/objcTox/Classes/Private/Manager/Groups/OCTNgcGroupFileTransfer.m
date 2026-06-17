// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcGroupFileTransfer.h"
#import "OCTTox+Private.h"
#import "OCTFileTools.h"
#import "OCTLogging.h"
#import <Security/Security.h>

static const uint8_t kOCTNgcPktFileSingle = 0x11;
static const uint8_t kOCTNgcPktFileBegin = 0x12;
static const uint8_t kOCTNgcPktFileChunk = 0x13;

static const size_t kOCTNgcMaxFileSize = 36701;
static const size_t kOCTNgcMaxPacketSize = 37000;
static const uint64_t kOCTNgcMaxFileTransferBytes = 200ULL * 1024ULL * 1024ULL;

static const size_t kOCTNgcSingleFileHeader = 6 + 1 + 1 + 32 + 4 + 255;
static const size_t kOCTNgcFileBeginHeader = 6 + 1 + 1 + 32 + 4 + 255 + 8 + 4 + 4;
static const size_t kOCTNgcFileChunkHeader = 6 + 1 + 1 + 32 + 4 + 4;
static const size_t kOCTNgcChunkPayloadMax = kOCTNgcMaxPacketSize - kOCTNgcFileChunkHeader;
static const NSUInteger kOCTNgcMaxOrphanChunks = 64;

NSString *const kOCTNgcGroupFileTransferErrorDomain = @"OCTNgcGroupFileTransferErrorDomain";

typedef NS_ENUM(NSInteger, OCTNgcGroupFileTransferErrorPrivate) {
    OCTNgcGroupFileTransferErrorPrivateFileTooLarge = OCTNgcGroupFileTransferErrorFileTooLarge,
    OCTNgcGroupFileTransferErrorPrivateMissingFile = OCTNgcGroupFileTransferErrorMissingFile,
    OCTNgcGroupFileTransferErrorPrivateSendFailed = OCTNgcGroupFileTransferErrorSendFailed,
    OCTNgcGroupFileTransferErrorPrivateCancelled = OCTNgcGroupFileTransferErrorCancelled,
};

@interface OCTNgcIncomingAssembly : NSObject
@property (nonatomic, copy) NSString *displayFilename;
@property (nonatomic, copy) NSString *filename;
@property (nonatomic, copy) NSString *outPath;
@property (nonatomic, assign) uint64_t totalSize;
@property (nonatomic, assign) int totalChunks;
@property (nonatomic, assign) int chunkPayload;
@property (nonatomic, strong) NSMutableData *msgId;
@property (nonatomic, strong) NSMutableArray<NSNumber *> *received;
@property (nonatomic, assign) int receivedCount;
@end

@implementation OCTNgcIncomingAssembly
@end

@interface OCTNgcOrphanChunk : NSObject
@property (nonatomic, copy) NSData *data;
@end

@implementation OCTNgcOrphanChunk
@end

@interface OCTNgcGroupFileTransfer ()
@property (nonatomic, copy) OCTNgcGroupFileTransferSendPacketBlock sendPacketBlock;
@property (nonatomic, copy) NSString *(^incomingFilesDirectoryBlock)(uint32_t groupNumber);
@property (nonatomic, copy, nullable) OCTNgcGroupFileTransferIncomingBeginBlock incomingBeginBlock;
@property (nonatomic, copy) OCTNgcGroupFileTransferIncomingFileBlock incomingCompleteBlock;
@property (nonatomic, copy, nullable) OCTNgcGroupFileTransferProgressBlock transferProgressBlock;
@property (nonatomic, strong) NSMutableDictionary<NSString *, OCTNgcIncomingAssembly *> *assemblies;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSMutableArray<OCTNgcOrphanChunk *> *> *orphanChunks;
@property (nonatomic, strong) NSMutableSet<NSString *> *cancelledMsgIdHexes;
@property (nonatomic, strong) dispatch_queue_t sendQueue;
@end

@implementation OCTNgcGroupFileTransfer

- (instancetype)initWithSendPacketBlock:(OCTNgcGroupFileTransferSendPacketBlock)sendPacketBlock
                incomingFilesDirectory:(NSString *(^)(uint32_t groupNumber))incomingFilesDirectoryBlock
                   incomingBeginBlock:(OCTNgcGroupFileTransferIncomingBeginBlock)incomingBeginBlock
                incomingCompleteBlock:(OCTNgcGroupFileTransferIncomingFileBlock)incomingCompleteBlock
                  transferProgressBlock:(OCTNgcGroupFileTransferProgressBlock)transferProgressBlock
{
    NSParameterAssert(sendPacketBlock);
    NSParameterAssert(incomingFilesDirectoryBlock);
    NSParameterAssert(incomingCompleteBlock);

    self = [super init];

    if (! self) {
        return nil;
    }

    _sendPacketBlock = [sendPacketBlock copy];
    _incomingFilesDirectoryBlock = [incomingFilesDirectoryBlock copy];
    _incomingBeginBlock = [incomingBeginBlock copy];
    _incomingCompleteBlock = [incomingCompleteBlock copy];
    _transferProgressBlock = [transferProgressBlock copy];
    _assemblies = [NSMutableDictionary new];
    _orphanChunks = [NSMutableDictionary new];
    _cancelledMsgIdHexes = [NSMutableSet set];
    _sendQueue = dispatch_queue_create("ngc-group-file-send", DISPATCH_QUEUE_SERIAL);

    return self;
}

#pragma mark - Public

+ (BOOL)shouldUseChunkedTransferForFileSize:(uint64_t)fileSize
{
    return fileSize > kOCTNgcMaxFileSize;
}

+ (NSString *)generateMsgIdHex
{
    uint8_t bytes[32];

    if (SecRandomCopyBytes(kSecRandomDefault, sizeof(bytes), bytes) != errSecSuccess) {
        arc4random_buf(bytes, sizeof(bytes));
    }

    return [OCTTox binToHexString:bytes length:sizeof(bytes)];
}

- (void)cancelSendForMsgIdHex:(NSString *)msgIdHex
{
    if (msgIdHex.length == 0) {
        return;
    }

    dispatch_async(self.sendQueue, ^{
        [self.cancelledMsgIdHexes addObject:msgIdHex.lowercaseString];
    });
}

- (BOOL)isSendCancelledForMsgIdHex:(NSString *)msgIdHex
{
    return msgIdHex.length > 0 && [self.cancelledMsgIdHexes containsObject:msgIdHex.lowercaseString];
}

- (void)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                                       data:(NSData *)data
{
    if (data.length < 8) {
        return;
    }

    const uint8_t *bytes = data.bytes;

    if (! [self isKhandaqMagic:bytes]) {
        return;
    }

    if (bytes[6] != 0x01) {
        return;
    }

    switch (bytes[7]) {
        case kOCTNgcPktFileSingle:
            [self handleIncomingSingleWithGroupNumber:groupNumber peerId:peerId data:data];
            break;
        case kOCTNgcPktFileBegin:
            [self handleIncomingBeginWithGroupNumber:groupNumber peerId:peerId data:data];
            break;
        case kOCTNgcPktFileChunk:
            [self handleIncomingChunkWithGroupNumber:groupNumber peerId:peerId data:data];
            break;
        default:
            break;
    }
}

- (void)sendFileAtPath:(NSString *)filePath
           groupNumber:(uint32_t)groupNumber
              msgIdHex:(NSString *)msgIdHex
              progress:(void (^)(float))progressBlock
            completion:(void (^)(BOOL, NSError *))completion
{
    NSParameterAssert(filePath);
    NSParameterAssert(msgIdHex);

    dispatch_async(self.sendQueue, ^{
        NSError *error = nil;
        BOOL success = [self sendFileAtPathOnWorker:filePath
                                        groupNumber:groupNumber
                                           msgIdHex:msgIdHex
                                           progress:progressBlock
                                              error:&error];

        if (completion) {
            dispatch_async(dispatch_get_main_queue(), ^{
                completion(success, error);
            });
        }
    });
}

#pragma mark - Send

- (BOOL)sendFileAtPathOnWorker:(NSString *)filePath
                   groupNumber:(uint32_t)groupNumber
                      msgIdHex:(NSString *)msgIdHex
                      progress:(void (^)(float))progressBlock
                         error:(NSError **)error
{
    NSFileManager *fileManager = [NSFileManager defaultManager];
    BOOL isDirectory = NO;

    if (! [fileManager fileExistsAtPath:filePath isDirectory:&isDirectory] || isDirectory) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorMissingFile reason:@"File not found"];
        return NO;
    }

    NSDictionary *attributes = [fileManager attributesOfItemAtPath:filePath error:nil];
    uint64_t fileSize = [attributes[NSFileSize] unsignedLongLongValue];

    if (fileSize < 1 || fileSize > kOCTNgcMaxFileTransferBytes) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorFileTooLarge reason:@"File size is not supported"];
        return NO;
    }

    NSData *msgId = [self msgIdDataFromHex:msgIdHex];

    if ([self isSendCancelledForMsgIdHex:msgIdHex]) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorCancelled reason:@"Transfer cancelled"];
        return NO;
    }

    if ([self.class shouldUseChunkedTransferForFileSize:fileSize]) {
        return [self sendChunkedFileAtPath:filePath
                               groupNumber:groupNumber
                                     msgId:msgId
                                  fileSize:fileSize
                                  fileName:[filePath lastPathComponent]
                                  progress:progressBlock
                                     error:error];
    }

    if (progressBlock) {
        dispatch_async(dispatch_get_main_queue(), ^{
            progressBlock(0.5f);
        });
    }

    BOOL sent = [self sendSingleFileAtPath:filePath
                               groupNumber:groupNumber
                                     msgId:msgId
                                  fileSize:fileSize
                                  fileName:[filePath lastPathComponent]
                                     error:error];

    if (sent && progressBlock) {
        dispatch_async(dispatch_get_main_queue(), ^{
            progressBlock(1.0f);
        });
    }

    return sent;
}

- (BOOL)sendSingleFileAtPath:(NSString *)filePath
                 groupNumber:(uint32_t)groupNumber
                       msgId:(NSData *)msgId
                    fileSize:(uint64_t)fileSize
                    fileName:(NSString *)fileName
                       error:(NSError **)error
{
    NSData *fileData = [NSData dataWithContentsOfFile:filePath options:NSDataReadingMappedIfSafe error:error];

    if (! fileData) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorMissingFile reason:@"Cannot read file"];
        return NO;
    }

    if (fileData.length != fileSize) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorMissingFile reason:@"Unexpected file size"];
        return NO;
    }

    NSMutableData *packet = [NSMutableData dataWithCapacity:kOCTNgcSingleFileHeader + fileData.length];
    [self appendMagicToData:packet];
    [packet appendBytes:&(uint8_t){kOCTNgcPktFileSingle} length:1];
    [self appendMsgId:msgId toData:packet];
    [self appendCreateTimestampToData:packet];
    [self appendFilename:fileName toData:packet];
    [packet appendData:fileData];

    return [self sendPacket:packet groupNumber:groupNumber error:error];
}

- (BOOL)sendChunkedFileAtPath:(NSString *)filePath
                  groupNumber:(uint32_t)groupNumber
                        msgId:(NSData *)msgId
                     fileSize:(uint64_t)fileSize
                     fileName:(NSString *)fileName
                     progress:(void (^)(float))progressBlock
                        error:(NSError **)error
{
    int totalChunks = (int)((fileSize + kOCTNgcChunkPayloadMax - 1) / kOCTNgcChunkPayloadMax);
    NSString *msgIdHex = [OCTTox binToHexString:msgId.bytes length:msgId.length];
    NSMutableData *beginPacket = [NSMutableData dataWithCapacity:kOCTNgcFileBeginHeader];
    [self appendMagicToData:beginPacket];
    [beginPacket appendBytes:&(uint8_t){kOCTNgcPktFileBegin} length:1];
    [self appendMsgId:msgId toData:beginPacket];
    [self appendCreateTimestampToData:beginPacket];
    [self appendFilename:fileName toData:beginPacket];
    [self appendU64BE:fileSize toData:beginPacket];
    [self appendU32BE:(uint32_t)kOCTNgcChunkPayloadMax toData:beginPacket];
    [self appendU32BE:(uint32_t)totalChunks toData:beginPacket];

    if (! [self sendPacket:beginPacket groupNumber:groupNumber error:error]) {
        return NO;
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForReadingAtPath:filePath];

    if (! handle) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorMissingFile reason:@"Cannot read file"];
        return NO;
    }

    for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
        if ([self isSendCancelledForMsgIdHex:msgIdHex]) {
            [handle closeFile];
            [self fillError:error code:OCTNgcGroupFileTransferErrorCancelled reason:@"Transfer cancelled"];
            return NO;
        }

        uint64_t offset = (uint64_t)chunkIndex * kOCTNgcChunkPayloadMax;
        NSUInteger toRead = (NSUInteger)MIN(kOCTNgcChunkPayloadMax, fileSize - offset);
        [handle seekToFileOffset:offset];
        NSData *payload = [handle readDataOfLength:toRead];

        if (payload.length != toRead) {
            [handle closeFile];
            [self fillError:error code:OCTNgcGroupFileTransferErrorMissingFile reason:@"Unexpected EOF"];
            return NO;
        }

        NSMutableData *chunkPacket = [NSMutableData dataWithCapacity:kOCTNgcFileChunkHeader + payload.length];
        [self appendMagicToData:chunkPacket];
        [chunkPacket appendBytes:&(uint8_t){kOCTNgcPktFileChunk} length:1];
        [self appendMsgId:msgId toData:chunkPacket];
        [self appendU32BE:(uint32_t)chunkIndex toData:chunkPacket];
        [self appendU32BE:(uint32_t)payload.length toData:chunkPacket];
        [chunkPacket appendData:payload];

        if (! [self sendPacket:chunkPacket groupNumber:groupNumber error:error]) {
            [handle closeFile];
            return NO;
        }

        if (progressBlock || self.transferProgressBlock) {
            float chunkProgress = (float)(chunkIndex + 1) / (float)totalChunks;

            if (progressBlock) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    progressBlock(chunkProgress);
                });
            }

            if (self.transferProgressBlock) {
                dispatch_async(dispatch_get_main_queue(), ^{
                    self.transferProgressBlock(groupNumber, msgIdHex, chunkProgress);
                });
            }
        }

        if ((chunkIndex & 7) == 7) {
            usleep(2000);
        }
    }

    [handle closeFile];
    return YES;
}

- (BOOL)sendPacket:(NSData *)packet groupNumber:(uint32_t)groupNumber error:(NSError **)error
{
    if (! self.sendPacketBlock(groupNumber, packet, error)) {
        if (error && ! *error) {
            [self fillError:error code:OCTNgcGroupFileTransferErrorSendFailed reason:@"Custom packet send failed"];
        }
        return NO;
    }

    return YES;
}

#pragma mark - Receive

- (void)handleIncomingSingleWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                                       data:(NSData *)data
{
    if (data.length <= kOCTNgcSingleFileHeader || data.length > kOCTNgcMaxPacketSize) {
        return;
    }

    NSData *msgId = [data subdataWithRange:NSMakeRange(8, 32)];
    NSString *msgIdHex = [OCTTox binToHexString:msgId.bytes length:msgId.length];
    NSString *fileName = [self utf8FilenameFromData:data offset:44 length:255];
    uint64_t fileSize = data.length - kOCTNgcSingleFileHeader;

    if (fileSize < 1 || fileSize > kOCTNgcMaxFileTransferBytes) {
        return;
    }

    NSString *directory = self.incomingFilesDirectoryBlock(groupNumber);

    if (directory.length == 0) {
        return;
    }

    NSString *localName = [OCTFileTools createNewFilePathInDirectory:directory fileName:fileName];
    NSData *payload = [data subdataWithRange:NSMakeRange(kOCTNgcSingleFileHeader, fileSize)];

    if (! [payload writeToFile:localName atomically:YES]) {
        OCTLogWarn(@"NGC group file: cannot write incoming single file %@", localName);
        return;
    }

    self.incomingCompleteBlock(groupNumber, peerId, [localName lastPathComponent], localName, fileSize, msgIdHex);
}

- (void)handleIncomingBeginWithGroupNumber:(uint32_t)groupNumber
                                    peerId:(uint32_t)peerId
                                      data:(NSData *)data
{
    if (data.length < kOCTNgcFileBeginHeader) {
        return;
    }

    NSData *msgId = [data subdataWithRange:NSMakeRange(8, 32)];
    NSString *msgIdHex = [OCTTox binToHexString:msgId.bytes length:msgId.length];
    NSString *assemblyKey = [self assemblyKeyForGroupNumber:groupNumber msgId:msgId];

    @synchronized (self.assemblies) {
        if (self.assemblies[assemblyKey]) {
            return;
        }
    }

    NSString *fileName = [self utf8FilenameFromData:data offset:44 length:255];
    uint64_t totalSize = [self readU64BEFromData:data offset:299];
    uint32_t chunkPayload = [self readU32BEFromData:data offset:307];
    uint32_t totalChunks = [self readU32BEFromData:data offset:311];

    if (totalSize < 1 || totalSize > kOCTNgcMaxFileTransferBytes || totalChunks < 1 ||
        chunkPayload < 1 || chunkPayload > kOCTNgcChunkPayloadMax) {
        return;
    }

    NSString *directory = self.incomingFilesDirectoryBlock(groupNumber);

    if (directory.length == 0) {
        return;
    }

    NSString *localName = [OCTFileTools createNewFilePathInDirectory:directory fileName:fileName];
    NSString *outPath = localName;

    if (! [[NSFileManager defaultManager] createFileAtPath:outPath contents:nil attributes:nil]) {
        return;
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:outPath];

    if (! handle) {
        return;
    }

    if (totalSize > 0) {
        [handle truncateFileAtOffset:totalSize];
    }

    [handle closeFile];

    OCTNgcIncomingAssembly *assembly = [OCTNgcIncomingAssembly new];
    assembly.displayFilename = fileName;
    assembly.filename = [outPath lastPathComponent];
    assembly.outPath = outPath;
    assembly.totalSize = totalSize;
    assembly.totalChunks = (int)totalChunks;
    assembly.chunkPayload = (int)chunkPayload;
    assembly.msgId = [msgId mutableCopy];
    assembly.received = [NSMutableArray arrayWithCapacity:totalChunks];

    for (uint32_t i = 0; i < totalChunks; i++) {
        [assembly.received addObject:@NO];
    }

    @synchronized (self.assemblies) {
        self.assemblies[assemblyKey] = assembly;
    }

    [self flushOrphanChunksForAssemblyKey:assemblyKey groupNumber:groupNumber peerId:peerId];

    if (self.incomingBeginBlock) {
        NSString *displayName = assembly.displayFilename.length > 0 ? assembly.displayFilename : assembly.filename;
        dispatch_async(dispatch_get_main_queue(), ^{
            self.incomingBeginBlock(groupNumber,
                                    peerId,
                                    displayName,
                                    assembly.outPath,
                                    assembly.totalSize,
                                    msgIdHex);
        });
    }
}

- (void)handleIncomingChunkWithGroupNumber:(uint32_t)groupNumber
                                    peerId:(uint32_t)peerId
                                      data:(NSData *)data
{
    if (data.length < kOCTNgcFileChunkHeader + 1) {
        return;
    }

    NSData *msgId = [data subdataWithRange:NSMakeRange(8, 32)];
    NSString *assemblyKey = [self assemblyKeyForGroupNumber:groupNumber msgId:msgId];
    OCTNgcIncomingAssembly *assembly = nil;

    @synchronized (self.assemblies) {
        assembly = self.assemblies[assemblyKey];
    }

    if (! assembly) {
        [self queueOrphanChunkWithAssemblyKey:assemblyKey data:data];
        return;
    }

    [self applyIncomingChunkWithGroupNumber:groupNumber
                                     peerId:peerId
                                assemblyKey:assemblyKey
                                  assembly:assembly
                                       data:data];
}

- (void)applyIncomingChunkWithGroupNumber:(uint32_t)groupNumber
                                   peerId:(uint32_t)peerId
                              assemblyKey:(NSString *)assemblyKey
                                assembly:(OCTNgcIncomingAssembly *)assembly
                                     data:(NSData *)data
{
    uint32_t chunkIndex = [self readU32BEFromData:data offset:40];
    uint32_t chunkSize = [self readU32BEFromData:data offset:44];

    if (chunkIndex >= assembly.totalChunks || chunkSize < 1 ||
        (kOCTNgcFileChunkHeader + chunkSize) > data.length) {
        return;
    }

    if ([assembly.received[chunkIndex] boolValue]) {
        return;
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:assembly.outPath];

    if (! handle) {
        return;
    }

    uint64_t offset = (uint64_t)chunkIndex * assembly.chunkPayload;
    [handle seekToFileOffset:offset];
    NSData *payload = [data subdataWithRange:NSMakeRange(kOCTNgcFileChunkHeader, chunkSize)];
    [handle writeData:payload];
    [handle closeFile];

    assembly.received[chunkIndex] = @YES;
    assembly.receivedCount++;

    if (self.transferProgressBlock) {
        NSString *msgIdHex = [OCTTox binToHexString:assembly.msgId.bytes length:assembly.msgId.length];
        float chunkProgress = (float)assembly.receivedCount / (float)assembly.totalChunks;

        if ((assembly.receivedCount & 15) == 0 || assembly.receivedCount >= assembly.totalChunks) {
            dispatch_async(dispatch_get_main_queue(), ^{
                self.transferProgressBlock(groupNumber, msgIdHex, chunkProgress);
            });
        }
    }

    if (assembly.receivedCount >= assembly.totalChunks) {
        @synchronized (self.assemblies) {
            [self.assemblies removeObjectForKey:assemblyKey];
        }

        @synchronized (self.orphanChunks) {
            [self.orphanChunks removeObjectForKey:assemblyKey];
        }

        NSString *msgIdHex = [OCTTox binToHexString:assembly.msgId.bytes length:assembly.msgId.length];
        NSString *displayName = assembly.displayFilename.length > 0 ? assembly.displayFilename : assembly.filename;
        self.incomingCompleteBlock(groupNumber,
                                   peerId,
                                   displayName,
                                   assembly.outPath,
                                   assembly.totalSize,
                                   msgIdHex);
    }
}

- (void)queueOrphanChunkWithAssemblyKey:(NSString *)assemblyKey data:(NSData *)data
{
    @synchronized (self.orphanChunks) {
        NSMutableArray *list = self.orphanChunks[assemblyKey];

        if (! list) {
            list = [NSMutableArray array];
            self.orphanChunks[assemblyKey] = list;
        }

        if (list.count >= kOCTNgcMaxOrphanChunks) {
            [list removeObjectAtIndex:0];
        }

        OCTNgcOrphanChunk *orphan = [OCTNgcOrphanChunk new];
        orphan.data = [data copy];
        [list addObject:orphan];
    }
}

- (void)flushOrphanChunksForAssemblyKey:(NSString *)assemblyKey
                            groupNumber:(uint32_t)groupNumber
                                 peerId:(uint32_t)peerId
{
    NSMutableArray<OCTNgcOrphanChunk *> *list = nil;

    @synchronized (self.orphanChunks) {
        list = [self.orphanChunks[assemblyKey] mutableCopy];
        [self.orphanChunks removeObjectForKey:assemblyKey];
    }

    if (list.count == 0) {
        return;
    }

    OCTNgcIncomingAssembly *assembly = nil;

    @synchronized (self.assemblies) {
        assembly = self.assemblies[assemblyKey];
    }

    if (! assembly) {
        return;
    }

    for (OCTNgcOrphanChunk *orphan in list) {
        [self applyIncomingChunkWithGroupNumber:groupNumber
                                         peerId:peerId
                                    assemblyKey:assemblyKey
                                      assembly:assembly
                                           data:orphan.data];
    }
}

#pragma mark - Helpers

- (BOOL)isKhandaqMagic:(const uint8_t *)bytes
{
    return bytes[0] == 0x66 && bytes[1] == 0x77 && bytes[2] == 0x88 && bytes[3] == 0x11 &&
           bytes[4] == 0x34 && bytes[5] == 0x35;
}

- (void)appendMagicToData:(NSMutableData *)data
{
    const uint8_t magic[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01};
    [data appendBytes:magic length:sizeof(magic)];
}

- (void)appendMsgId:(NSData *)msgId toData:(NSMutableData *)data
{
    NSData *fixed = msgId;

    if (msgId.length != 32) {
        NSMutableData *padded = [NSMutableData dataWithLength:32];
        [padded replaceBytesInRange:NSMakeRange(0, MIN(msgId.length, 32)) withBytes:msgId.bytes];
        fixed = padded;
    }

    [data appendData:fixed];
}

- (void)appendCreateTimestampToData:(NSMutableData *)data
{
    uint8_t zeroes[4] = {0, 0, 0, 0};
    [data appendBytes:zeroes length:4];
}

- (void)appendFilename:(NSString *)fileName toData:(NSMutableData *)data
{
    NSData *nameData = [fileName dataUsingEncoding:NSUTF8StringEncoding];

    if (nameData.length > 255) {
        nameData = [nameData subdataWithRange:NSMakeRange(0, 255)];
    }

    [data appendData:nameData];

    if (nameData.length < 255) {
        NSMutableData *padding = [NSMutableData dataWithLength:255 - nameData.length];
        [data appendData:padding];
    }
}

- (void)appendU32BE:(uint32_t)value toData:(NSMutableData *)data
{
    uint8_t bytes[4] = {
        (uint8_t)((value >> 24) & 0xff),
        (uint8_t)((value >> 16) & 0xff),
        (uint8_t)((value >> 8) & 0xff),
        (uint8_t)(value & 0xff),
    };
    [data appendBytes:bytes length:4];
}

- (void)appendU64BE:(uint64_t)value toData:(NSMutableData *)data
{
    for (NSInteger i = 7; i >= 0; i--) {
        uint8_t byte = (uint8_t)((value >> (i * 8)) & 0xff);
        [data appendBytes:&byte length:1];
    }
}

- (uint32_t)readU32BEFromData:(NSData *)data offset:(NSUInteger)offset
{
    if (data.length < offset + 4) {
        return 0;
    }

    const uint8_t *bytes = data.bytes + offset;
    return ((uint32_t)bytes[0] << 24) | ((uint32_t)bytes[1] << 16) | ((uint32_t)bytes[2] << 8) | (uint32_t)bytes[3];
}

- (uint64_t)readU64BEFromData:(NSData *)data offset:(NSUInteger)offset
{
    if (data.length < offset + 8) {
        return 0;
    }

    const uint8_t *bytes = data.bytes + offset;
    uint64_t value = 0;

    for (NSUInteger i = 0; i < 8; i++) {
        value = (value << 8) | bytes[i];
    }

    return value;
}

- (NSString *)utf8FilenameFromData:(NSData *)data offset:(NSUInteger)offset length:(NSUInteger)maxLen
{
    if (data.length < offset + maxLen) {
        return @"file";
    }

    const uint8_t *bytes = data.bytes + offset;
    NSUInteger end = 0;

    while (end < maxLen && bytes[end] != 0) {
        end++;
    }

    if (end == 0) {
        return @"file";
    }

    return [[NSString alloc] initWithBytes:bytes length:end encoding:NSUTF8StringEncoding] ?: @"file";
}

- (NSString *)assemblyKeyForGroupNumber:(uint32_t)groupNumber msgId:(NSData *)msgId
{
    return [NSString stringWithFormat:@"%u:%@", groupNumber, [OCTTox binToHexString:msgId.bytes length:msgId.length]];
}

- (NSData *)msgIdDataFromHex:(NSString *)msgIdHex
{
    uint8_t *bytes = [OCTTox hexStringToBin:msgIdHex];
    NSData *data = [NSData dataWithBytes:bytes length:32];
    free(bytes);
    return data;
}

- (void)fillError:(NSError **)error code:(OCTNgcGroupFileTransferError)code reason:(NSString *)reason
{
    if (! error) {
        return;
    }

    *error = [NSError errorWithDomain:kOCTNgcGroupFileTransferErrorDomain
                                 code:code
                             userInfo:@{NSLocalizedFailureReasonErrorKey : reason}];
}

@end
