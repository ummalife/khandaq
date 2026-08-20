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
// KHANDAQ (#15): selective NACK so chunked transfers recover from dropped chunks (NGC custom packets
// can be lossy/rate-limited). The receiver asks for the chunk indices it is still missing; the
// original sender re-sends just those. Without this a single lost chunk left the file stuck loading.
static const uint8_t kOCTNgcPktFileRequest = 0x14;
static const int kOCTNgcNackMaxRounds = 24;          // stop asking after this many stalled rounds
static const int kOCTNgcNackMaxIndicesPerPacket = 256;
// KHANDAQ (audit 2026-08-20): how often ONE peer may have us re-broadcast chunks of ONE file.
//
// Deliberately BELOW the honest NACK period rather than equal to it. The receiver's stall timer in
// this same file fires every 2.5s, so a cooldown of 2.5s would sit exactly on top of it and any
// jitter in the wrong direction would drop a legitimate retransmission request, costing the honest
// transfer a whole round. 2.0s always lets the real timer through and still bounds a flood to one
// service per two seconds per (file, requester) — Android picks 2500 for the same job, but keys it
// per group+msgId rather than per requester, so it does not have this edge to worry about.
static const NSTimeInterval kOCTNgcResendCooldown = 2.0;
// Bound on the cooldown map itself, so it cannot become the leak.
static const NSUInteger kOCTNgcMaxTrackedResendRequesters = 512;
// Resend jobs allowed to sit on the (serial) send queue, which also carries incoming parsing.
static const NSInteger kOCTNgcMaxPendingResendJobs = 4;

static const size_t kOCTNgcMaxFileSize = 36701;
static const size_t kOCTNgcMaxPacketSize = 37000;
static const uint64_t kOCTNgcMaxFileTransferBytes = 200ULL * 1024ULL * 1024ULL;

static const size_t kOCTNgcSingleFileHeader = 6 + 1 + 1 + 32 + 4 + 255;
static const size_t kOCTNgcFileBeginHeader = 6 + 1 + 1 + 32 + 4 + 255 + 8 + 4 + 4;
static const size_t kOCTNgcFileChunkHeader = 6 + 1 + 1 + 32 + 4 + 4;
static const size_t kOCTNgcChunkPayloadMax = kOCTNgcMaxPacketSize - kOCTNgcFileChunkHeader;
static const NSUInteger kOCTNgcMaxOrphanChunks = 64;
// KHANDAQ (audit F-4): orphan chunks (chunks that beat their BEGIN) were kept per assembly key with no
// TTL and no cap on the number of keys, so any peer could grow the map for the whole process lifetime
// by sending chunks under msgIds whose BEGIN never arrives. The legit race lasts milliseconds.
static const NSUInteger kOCTNgcMaxOrphanAssemblies = 8;
static const NSTimeInterval kOCTNgcOrphanChunkTTL = 60.0;
// KHANDAQ (audit A38): cap the number of concurrent incoming assemblies, like the desktop client does
// (core.cpp, same limit). Until now an assembly was only deduped on its key, so one group member could
// open as many DISTINCT assemblies as it liked — each one costs a received[] array, a pre-truncated
// partial file in the group's incoming directory, and a NACK timer that broadcasts a request packet to
// the whole group every 2.5s (that amplification is worse than the memory). Only files above
// kOCTNgcMaxFileSize are chunked, so a real slot is one member actively uploading a photo/video; a
// lively group has a handful of those at once and 16 leaves roughly double that headroom.
static const NSUInteger kOCTNgcMaxIncomingAssemblies = 16;
// Same 60s window the audit #13 stall eviction uses (kOCTNgcNackMaxRounds x 2.5s): an assembly that has
// not taken a chunk for this long is dead and is swept before the cap is measured.
static const NSTimeInterval kOCTNgcAssemblyStaleTimeout = 60.0;
// Floor for sacrificing a PARTIALLY received assembly to admit a new one: never touch a transfer that
// has moved recently — losing a file the user is already receiving is worse than missing the new one.
static const NSTimeInterval kOCTNgcAssemblyIdleTimeout = 20.0;

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
// KHANDAQ (audit A38): time of the BEGIN or of the last chunk actually stored, used to pick which
// assembly to drop when the cap is hit and to sweep dead ones.
@property (nonatomic, assign) NSTimeInterval lastActivityTime;
// KHANDAQ (audit F-5): the peer that sent the BEGIN owns this assembly; chunks from anyone else are dropped.
@property (nonatomic, assign) uint32_t originPeerId;
@property (nonatomic, copy) NSString *originPublicKeyHex;
// KHANDAQ (#15): NACK retransmit state.
@property (nonatomic, assign) uint32_t groupNumber;
@property (nonatomic, strong) dispatch_source_t nackTimer;
@property (nonatomic, assign) int lastReceivedCount;
@property (nonatomic, assign) int nackRounds;
@end

@implementation OCTNgcIncomingAssembly
@end

// KHANDAQ (#15): kept by the sender so it can re-send chunks the receiver asks for via NACK.
@interface OCTNgcSentChunkedFile : NSObject
@property (nonatomic, copy) NSString *filePath;
@property (nonatomic, strong) NSData *msgId;
@property (nonatomic, assign) uint64_t fileSize;
@property (nonatomic, assign) int totalChunks;
@property (nonatomic, assign) uint32_t groupNumber;
@end

@implementation OCTNgcSentChunkedFile
@end

@interface OCTNgcOrphanChunk : NSObject
@property (nonatomic, copy) NSData *data;
// KHANDAQ (audit F-5): remember who sent it — these are replayed once the BEGIN lands, and until then
// anyone in the group can queue chunks under a foreign msgId.
@property (nonatomic, assign) uint32_t originPeerId;
@property (nonatomic, copy) NSString *originPublicKeyHex;
// KHANDAQ (audit F-4): queue time, so stale entries can be evicted instead of living forever.
@property (nonatomic, assign) NSTimeInterval timestamp;
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
@property (nonatomic, strong) NSMutableDictionary<NSString *, OCTNgcSentChunkedFile *> *sentChunkedFiles;
@property (nonatomic, strong) dispatch_queue_t sendQueue;
/**
 * KHANDAQ (audit 2026-08-20): "msgIdHex|peerId" -> when we last honoured a resend request for it.
 *
 * Serving a 0x14 request re-broadcasts chunks TO THE WHOLE GROUP (the send block has no peer
 * parameter, so a unicast reply is not expressible), which makes this the cheapest amplifier in the
 * client: one 1068-byte request naming 256 chunk indices used to produce up to 256 * 37000 bytes of
 * broadcast, replicated by toxcore to every peer. Nothing checked who was asking — `peerId` was not
 * read at all — nothing removed duplicate indices, and nothing rate-limited the requests, so the
 * same index could be listed 256 times in one packet and the flood could repeat at line rate.
 *
 * Android has throttled this path since it was written (SELECTIVE_RESEND_COOLDOWN_MS = 2500 per
 * group+msgId, RESEND_SERVICE_THROTTLE_MS = 4000 overall, and it parses the request into a
 * boolean[] so duplicates cost nothing). Desktop has no resend path at all. iOS was the one client
 * that would serve this unbounded, which is what makes it an oversight rather than a trade-off.
 */
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *lastResendServedAt;
/** Resend jobs sitting on sendQueue. Bounded: the queue also carries incoming parsing. */
@property (nonatomic, assign) NSInteger pendingResendJobs;
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
    _sentChunkedFiles = [NSMutableDictionary new];
    _lastResendServedAt = [NSMutableDictionary new];
    _sendQueue = dispatch_queue_create("ngc-group-file-send", DISPATCH_QUEUE_SERIAL);

    return self;
}

// KHANDAQ (audit A38): a running NACK timer retains its assembly and the assembly retains the timer, so
// an assembly still in flight when this object goes away (logout / profile switch) would keep the
// dispatch source firing every 2.5s for the whole process lifetime. Cancelling releases the handler and
// breaks that cycle; the partial files go with it — an incomplete assembly can never be resumed.
- (void)dealloc
{
    NSArray<OCTNgcIncomingAssembly *> *pending = nil;

    @synchronized (_assemblies) {
        pending = _assemblies.allValues;
        [_assemblies removeAllObjects];
    }

    for (OCTNgcIncomingAssembly *assembly in pending) {
        [self stopNackTimerForAssembly:assembly];

        if (assembly.outPath.length > 0) {
            [[NSFileManager defaultManager] removeItemAtPath:assembly.outPath error:nil];
        }
    }
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
                           peerPublicKeyHex:(NSString *)peerPublicKeyHex
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

    const uint8_t pkt = bytes[7];

    // KHANDAQ (audit): this packet callback is delivered on the MAIN thread, but the Single/Chunk
    // handlers do synchronous file I/O (writeToFile:) that jank the UI. Run all incoming assembly on
    // the serial sendQueue — it preserves chunk order, the @synchronized guards make the handlers
    // thread-safe, and they already hop their Realm/delegate updates back to the main queue.
    dispatch_async(self.sendQueue, ^{
        switch (pkt) {
            case kOCTNgcPktFileSingle:
                [self handleIncomingSingleWithGroupNumber:groupNumber
                                                   peerId:peerId
                                         peerPublicKeyHex:peerPublicKeyHex
                                                     data:data];
                break;
            case kOCTNgcPktFileBegin:
                [self handleIncomingBeginWithGroupNumber:groupNumber
                                                  peerId:peerId
                                        peerPublicKeyHex:peerPublicKeyHex
                                                    data:data];
                break;
            case kOCTNgcPktFileChunk:
                [self handleIncomingChunkWithGroupNumber:groupNumber
                                                  peerId:peerId
                                        peerPublicKeyHex:peerPublicKeyHex
                                                    data:data];
                break;
            case kOCTNgcPktFileRequest:
                [self handleIncomingRequestWithGroupNumber:groupNumber peerId:peerId data:data];
                break;
            default:
                break;
        }
    });
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

    // KHANDAQ (#15): remember this send so we can re-serve chunks a receiver NACKs for.
    OCTNgcSentChunkedFile *sentContext = [OCTNgcSentChunkedFile new];
    sentContext.filePath = filePath;
    sentContext.msgId = msgId;
    sentContext.fileSize = fileSize;
    sentContext.totalChunks = totalChunks;
    sentContext.groupNumber = groupNumber;
    @synchronized (self.sentChunkedFiles) {
        self.sentChunkedFiles[msgIdHex.lowercaseString] = sentContext;
        while (self.sentChunkedFiles.count > 24) {
            [self.sentChunkedFiles removeObjectForKey:self.sentChunkedFiles.allKeys.firstObject];
        }
    }

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
                           peerPublicKeyHex:(NSString *)peerPublicKeyHex
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

    // KHANDAQ (audit F-5): a single-packet file carries an attacker-choosable msgId just like a chunked
    // one, so hand the delivering peer's key up with it — the row it lands on is matched on that key.
    self.incomingCompleteBlock(groupNumber,
                               peerId,
                               peerPublicKeyHex,
                               [localName lastPathComponent],
                               localName,
                               fileSize,
                               msgIdHex);
}

- (void)handleIncomingBeginWithGroupNumber:(uint32_t)groupNumber
                                    peerId:(uint32_t)peerId
                          peerPublicKeyHex:(NSString *)peerPublicKeyHex
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

    // KHANDAQ (audit #6): a crafted BEGIN with a huge totalChunks (e.g. 0xFFFFFFFF) passes the >=1
    // check, then the received[] pre-fill loop below tries to append ~4 billion NSNumbers -> OOM
    // crash (remotely triggerable by any group peer). Require totalChunks to exactly match the count
    // implied by the (already size-capped) totalSize / chunkPayload.
    uint64_t expectedChunks = (totalSize + chunkPayload - 1) / chunkPayload;
    // KHANDAQ (audit #6, re-verify): the equality alone is not enough — chunkPayload is attacker-controlled
    // (only validated >= 1), so totalSize=200MB + chunkPayload=1 still passes with expectedChunks=200M and
    // OOMs the received[] pre-fill. Every real Khandaq sender uses kOCTNgcChunkPayloadMax, so a legit 200MB
    // file needs at most ~5.7k chunks; cap generously so a tiny chunkPayload can't inflate the array.
    uint64_t maxLegitChunks = (kOCTNgcMaxFileTransferBytes + kOCTNgcChunkPayloadMax - 1) / kOCTNgcChunkPayloadMax;
    if ((uint64_t)totalChunks != expectedChunks || expectedChunks > maxLegitChunks) {
        return;
    }

    NSString *directory = self.incomingFilesDirectoryBlock(groupNumber);

    if (directory.length == 0) {
        return;
    }

    // KHANDAQ (audit A38): bound the number of concurrent assemblies BEFORE allocating anything for this
    // one. Last check before the file is created, so neither a malformed BEGIN nor one we cannot store
    // anyway can cause eviction churn.
    if (! [self makeRoomForNewIncomingAssembly]) {
        return;
    }

    NSString *localName = [OCTFileTools createNewFilePathInDirectory:directory fileName:fileName];
    NSString *outPath = localName;

    if (! [[NSFileManager defaultManager] createFileAtPath:outPath contents:nil attributes:nil]) {
        return;
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:outPath];

    if (! handle) {
        // KHANDAQ (audit A38): the empty file already exists — don't leave it behind in the group's
        // incoming directory when the assembly never starts.
        [[NSFileManager defaultManager] removeItemAtPath:outPath error:nil];
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
    // KHANDAQ (audit F-5): bind the assembly to the peer that opened it.
    assembly.originPeerId = peerId;
    assembly.originPublicKeyHex = peerPublicKeyHex;
    assembly.lastActivityTime = [NSDate timeIntervalSinceReferenceDate];   // KHANDAQ (audit A38)
    assembly.received = [NSMutableArray arrayWithCapacity:totalChunks];

    for (uint32_t i = 0; i < totalChunks; i++) {
        [assembly.received addObject:@NO];
    }

    @synchronized (self.assemblies) {
        self.assemblies[assemblyKey] = assembly;
    }

    [self flushOrphanChunksForAssemblyKey:assemblyKey groupNumber:groupNumber];

    // KHANDAQ (#15): start the NACK retransmit timer if the file is still incomplete after applying
    // any orphan chunks. The receiver periodically asks the sender to re-send missing chunks.
    BOOL stillIncomplete = NO;
    @synchronized (self.assemblies) {
        stillIncomplete = (self.assemblies[assemblyKey] == assembly) && (assembly.receivedCount < assembly.totalChunks);
    }
    if (stillIncomplete) {
        assembly.groupNumber = groupNumber;
        [self startNackTimerForAssembly:assembly assemblyKey:assemblyKey];
    }

    if (self.incomingBeginBlock) {
        NSString *displayName = assembly.displayFilename.length > 0 ? assembly.displayFilename : assembly.filename;
        dispatch_async(dispatch_get_main_queue(), ^{
            self.incomingBeginBlock(groupNumber,
                                    peerId,
                                    assembly.originPublicKeyHex,
                                    displayName,
                                    assembly.outPath,
                                    assembly.totalSize,
                                    msgIdHex);
        });
    }
}

- (void)handleIncomingChunkWithGroupNumber:(uint32_t)groupNumber
                                    peerId:(uint32_t)peerId
                          peerPublicKeyHex:(NSString *)peerPublicKeyHex
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
        [self queueOrphanChunkWithAssemblyKey:assemblyKey
                                       peerId:peerId
                             peerPublicKeyHex:peerPublicKeyHex
                                         data:data];
        return;
    }

    [self applyIncomingChunkWithGroupNumber:groupNumber
                                     peerId:peerId
                           peerPublicKeyHex:peerPublicKeyHex
                                assemblyKey:assemblyKey
                                  assembly:assembly
                                       data:data];
}

// KHANDAQ (audit F-5): an assembly belongs to exactly ONE peer — the one that started it. The msgId
// travels in the clear in every group message, so without this check any member could push chunks into
// another member's transfer and corrupt the file they receive. Match on the public key whenever both
// sides are known: peerId is toxcore's transient per-group index and is re-assigned when a peer
// reconnects, so a peerId-only match would reject the rest of a legitimate transfer after a reconnect.
- (BOOL)isChunkFromOriginPeerId:(uint32_t)peerId
               peerPublicKeyHex:(NSString *)peerPublicKeyHex
                       assembly:(OCTNgcIncomingAssembly *)assembly
{
    if (assembly.originPublicKeyHex.length > 0 && peerPublicKeyHex.length > 0) {
        return [assembly.originPublicKeyHex caseInsensitiveCompare:peerPublicKeyHex] == NSOrderedSame;
    }

    // Key unavailable on one side (peer left mid-transfer) — fall back to the peer number seen at BEGIN.
    return peerId == assembly.originPeerId;
}

- (void)applyIncomingChunkWithGroupNumber:(uint32_t)groupNumber
                                   peerId:(uint32_t)peerId
                         peerPublicKeyHex:(NSString *)peerPublicKeyHex
                              assemblyKey:(NSString *)assemblyKey
                                assembly:(OCTNgcIncomingAssembly *)assembly
                                     data:(NSData *)data
{
    // KHANDAQ (audit F-5): drop ONLY the foreign packet — the real transfer keeps running (nothing is
    // marked received, the NACK state is untouched), so an injector cannot kill the download either.
    if (! [self isChunkFromOriginPeerId:peerId peerPublicKeyHex:peerPublicKeyHex assembly:assembly]) {
        return;
    }

    uint32_t chunkIndex = [self readU32BEFromData:data offset:40];
    uint32_t chunkSize = [self readU32BEFromData:data offset:44];
    uint64_t offset = (uint64_t)chunkIndex * (uint64_t)assembly.chunkPayload;

    // KHANDAQ (audit F-4): the chunk has to fit the geometry the BEGIN declared *and* the packet we
    // actually received. Without the chunkPayload/totalSize bounds a peer can write past the size we
    // truncated the file to; offset is 64-bit so a high chunkIndex cannot wrap.
    if (chunkIndex >= (uint32_t)assembly.totalChunks || chunkSize < 1 ||
        chunkSize > (uint32_t)assembly.chunkPayload ||
        (kOCTNgcFileChunkHeader + (uint64_t)chunkSize) > data.length ||
        (offset + chunkSize) > assembly.totalSize) {
        return;
    }

    if ([assembly.received[chunkIndex] boolValue]) {
        return;
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:assembly.outPath];

    if (! handle) {
        return;
    }

    [handle seekToFileOffset:offset];
    NSData *payload = [data subdataWithRange:NSMakeRange(kOCTNgcFileChunkHeader, chunkSize)];
    [handle writeData:payload];
    [handle closeFile];

    @synchronized (assembly) {
        assembly.received[chunkIndex] = @YES;
        assembly.receivedCount++;
        assembly.lastActivityTime = [NSDate timeIntervalSinceReferenceDate];   // KHANDAQ (audit A38)
    }

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
        [self stopNackTimerForAssembly:assembly];

        @synchronized (self.assemblies) {
            [self.assemblies removeObjectForKey:assemblyKey];
        }

        @synchronized (self.orphanChunks) {
            [self.orphanChunks removeObjectForKey:assemblyKey];
        }

        NSString *msgIdHex = [OCTTox binToHexString:assembly.msgId.bytes length:assembly.msgId.length];
        NSString *displayName = assembly.displayFilename.length > 0 ? assembly.displayFilename : assembly.filename;
        // KHANDAQ (audit F-5): report the BEGIN opener, not whoever happened to deliver the last chunk
        // — they are the same peer by the guard above, but the opener is what the row was stamped with.
        self.incomingCompleteBlock(groupNumber,
                                   peerId,
                                   assembly.originPublicKeyHex,
                                   displayName,
                                   assembly.outPath,
                                   assembly.totalSize,
                                   msgIdHex);
    }
}

// KHANDAQ (audit #13 / A38): the single teardown path for an incoming assembly that will never complete.
// Stops the NACK timer (it retains the assembly, so leaving it running leaks both and keeps broadcasting
// requests), drops the assembly and any orphan chunks queued under its key, and removes the partial file.
// Never call it for a COMPLETED assembly: there outPath is the file handed to incomingCompleteBlock.
- (void)discardIncomingAssembly:(OCTNgcIncomingAssembly *)assembly forKey:(NSString *)assemblyKey
{
    [self stopNackTimerForAssembly:assembly];

    @synchronized (self.assemblies) {
        if (self.assemblies[assemblyKey] == assembly) {
            [self.assemblies removeObjectForKey:assemblyKey];
        }
    }

    @synchronized (self.orphanChunks) {
        [self.orphanChunks removeObjectForKey:assemblyKey];
    }

    if (assembly.outPath.length > 0) {
        [[NSFileManager defaultManager] removeItemAtPath:assembly.outPath error:nil];
    }
}

// KHANDAQ (audit A38): free a slot for an incoming BEGIN, or refuse it. Returns NO only when every slot
// is held by a transfer that has taken a chunk recently — killing one of those to admit a new file would
// lose a file the user is already receiving, which is worse than missing the new one.
- (BOOL)makeRoomForNewIncomingAssembly
{
    NSTimeInterval now = [NSDate timeIntervalSinceReferenceDate];
    NSDictionary<NSString *, OCTNgcIncomingAssembly *> *snapshot = nil;

    @synchronized (self.assemblies) {
        snapshot = [self.assemblies copy];
    }

    // 1) Sweep what is provably dead first, on the same 60s stall window the audit #13 eviction uses.
    // This also covers assemblies whose NACK timer never ran them out (its ticks only accumulate while
    // the transfer is stalled, and it stops firing while the app is suspended).
    NSMutableDictionary<NSString *, OCTNgcIncomingAssembly *> *live = [snapshot mutableCopy];

    for (NSString *key in snapshot) {
        OCTNgcIncomingAssembly *assembly = snapshot[key];

        if ((now - assembly.lastActivityTime) > kOCTNgcAssemblyStaleTimeout) {
            [self discardIncomingAssembly:assembly forKey:key];
            [live removeObjectForKey:key];
        }
    }

    if (live.count < kOCTNgcMaxIncomingAssemblies) {
        return YES;
    }

    // 2) Still full: sacrifice the least useful slot. A BEGIN that never took a single chunk is the
    // flood signature and costs the user nothing, so those go first, oldest one out.
    NSString *victimKey = nil;
    OCTNgcIncomingAssembly *victim = nil;

    for (NSString *key in live) {
        OCTNgcIncomingAssembly *candidate = live[key];

        if (candidate.receivedCount > 0) {
            continue;
        }

        if (! victim || candidate.lastActivityTime < victim.lastActivityTime) {
            victimKey = key;
            victim = candidate;
        }
    }

    // 3) Only if every slot has received data do we consider a partial transfer, and then only one that
    // has been silent for kOCTNgcAssemblyIdleTimeout — a peer sending BEGIN + one chunk each can pin the
    // cap for that long at most, while a transfer that is actually moving is never dropped.
    if (! victim) {
        for (NSString *key in live) {
            OCTNgcIncomingAssembly *candidate = live[key];

            if ((now - candidate.lastActivityTime) < kOCTNgcAssemblyIdleTimeout) {
                continue;
            }

            if (! victim || candidate.lastActivityTime < victim.lastActivityTime) {
                victimKey = key;
                victim = candidate;
            }
        }
    }

    if (! victim) {
        return NO;
    }

    [self discardIncomingAssembly:victim forKey:victimKey];
    return YES;
}

#pragma mark - NACK (selective retransmit)

- (void)startNackTimerForAssembly:(OCTNgcIncomingAssembly *)assembly assemblyKey:(NSString *)assemblyKey
{
    dispatch_source_t timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, self.sendQueue);
    uint64_t interval = 2500ull * NSEC_PER_MSEC;
    dispatch_source_set_timer(timer, dispatch_time(DISPATCH_TIME_NOW, (int64_t)interval), interval, 500ull * NSEC_PER_MSEC);

    __weak typeof(self) weakSelf = self;
    dispatch_source_set_event_handler(timer, ^{
        __strong typeof(weakSelf) self = weakSelf;
        if (! self) {
            return;
        }
        [self nackTickForAssembly:assembly assemblyKey:assemblyKey];
    });

    assembly.lastReceivedCount = -1;
    assembly.nackTimer = timer;
    dispatch_resume(timer);
}

- (void)stopNackTimerForAssembly:(OCTNgcIncomingAssembly *)assembly
{
    dispatch_source_t timer = assembly.nackTimer;
    if (timer) {
        assembly.nackTimer = nil;
        dispatch_source_cancel(timer);
    }
}

- (void)nackTickForAssembly:(OCTNgcIncomingAssembly *)assembly assemblyKey:(NSString *)assemblyKey
{
    BOOL active = NO;
    @synchronized (self.assemblies) {
        active = (self.assemblies[assemblyKey] == assembly);
    }

    if (! active || assembly.receivedCount >= assembly.totalChunks) {
        [self stopNackTimerForAssembly:assembly];
        return;
    }

    // Only ask for re-sends when the transfer has stalled (no new chunk since the last tick), so a
    // normally-progressing transfer is never disturbed.
    BOOL stalled = (assembly.receivedCount == assembly.lastReceivedCount);
    assembly.lastReceivedCount = assembly.receivedCount;

    if (! stalled) {
        return;
    }

    assembly.nackRounds++;
    if (assembly.nackRounds > kOCTNgcNackMaxRounds) {
        [self stopNackTimerForAssembly:assembly];
        // KHANDAQ (audit #13): evict the stalled state + partial file. Without this the assembly (its
        // received[] array) and the truncated on-disk file leak for the process lifetime, AND because
        // BEGIN dedups on assemblyKey a later re-broadcast of the same msgId returns early — so the
        // transfer can never recover and the bubble stays stuck 'loading' forever.
        [self discardIncomingAssembly:assembly forKey:assemblyKey];
        return;
    }

    [self sendNackForAssembly:assembly];
}

- (void)sendNackForAssembly:(OCTNgcIncomingAssembly *)assembly
{
    NSMutableData *indexData = [NSMutableData data];
    uint32_t count = 0;

    @synchronized (assembly) {
        for (int i = 0; i < assembly.totalChunks && count < kOCTNgcNackMaxIndicesPerPacket; i++) {
            if (! [assembly.received[i] boolValue]) {
                [self appendU32BE:(uint32_t)i toData:indexData];
                count++;
            }
        }
    }

    if (count == 0) {
        return;
    }

    NSMutableData *packet = [NSMutableData data];
    [self appendMagicToData:packet];
    [packet appendBytes:&(uint8_t){kOCTNgcPktFileRequest} length:1];
    [self appendMsgId:assembly.msgId toData:packet];
    [self appendU32BE:count toData:packet];
    [packet appendData:indexData];

    [self sendPacket:packet groupNumber:assembly.groupNumber error:nil];
}

- (void)handleIncomingRequestWithGroupNumber:(uint32_t)groupNumber
                                      peerId:(uint32_t)peerId
                                        data:(NSData *)data
{
    if (data.length < 8 + 32 + 4) {
        return;
    }

    NSData *msgId = [data subdataWithRange:NSMakeRange(8, 32)];
    NSString *msgIdHex = [[OCTTox binToHexString:msgId.bytes length:msgId.length] lowercaseString];
    uint32_t count = [self readU32BEFromData:data offset:40];

    if (count == 0 || count > kOCTNgcNackMaxIndicesPerPacket || data.length < 44 + (NSUInteger)count * 4) {
        return;
    }

    OCTNgcSentChunkedFile *context = nil;
    @synchronized (self.sentChunkedFiles) {
        context = self.sentChunkedFiles[msgIdHex];
    }

    if (! context) {
        return;  // not the original sender of this file (or it has been forgotten)
    }

    // KHANDAQ (audit 2026-08-20): who is asking, and how often. Both were unchecked; see
    // `lastResendServedAt`. peerId is what makes the cooldown per-requester rather than global, so
    // one peer spamming cannot deny an honest peer its retransmission.
    NSString *cooldownKey = [NSString stringWithFormat:@"%@|%u", msgIdHex, peerId];
    const NSTimeInterval now = [NSDate timeIntervalSinceReferenceDate];
    @synchronized (self.lastResendServedAt) {
        NSNumber *last = self.lastResendServedAt[cooldownKey];
        if (last != nil && (now - last.doubleValue) < kOCTNgcResendCooldown) {
            return;
        }
        self.lastResendServedAt[cooldownKey] = @(now);

        if (self.lastResendServedAt.count > kOCTNgcMaxTrackedResendRequesters) {
            // Crude bound, same shape as the other caps in this file: a group can have many peers
            // and many files, and this map must not become the leak.
            [self.lastResendServedAt removeAllObjects];
            self.lastResendServedAt[cooldownKey] = @(now);
        }
    }

    // Duplicates are free to send and expensive to serve: 256 copies of index 0 used to mean 256
    // full chunk broadcasts. An index set collapses them, and keeps the result ordered, which is
    // also what the receiver's assembler prefers.
    NSMutableIndexSet *wanted = [NSMutableIndexSet indexSet];
    for (uint32_t i = 0; i < count; i++) {
        uint32_t idx = [self readU32BEFromData:data offset:(44 + i * 4)];
        if (idx < (uint32_t)context.totalChunks) {
            [wanted addIndex:(NSUInteger)idx];
        }
    }
    if (wanted.count == 0) {
        return;
    }

    NSMutableArray<NSNumber *> *indices = [NSMutableArray arrayWithCapacity:wanted.count];
    [wanted enumerateIndexesUsingBlock:^(NSUInteger idx, BOOL *stop) {
        [indices addObject:@(idx)];
    }];

    // sendQueue is serial and also carries incoming SINGLE/BEGIN/CHUNK parsing, so an unbounded
    // backlog of resend jobs does not merely delay the resends — it starves reception, and it keeps
    // doing so after the flood stops. Refuse rather than queue.
    @synchronized (self) {
        if (self.pendingResendJobs >= kOCTNgcMaxPendingResendJobs) {
            return;
        }
        self.pendingResendJobs++;
    }

    dispatch_async(self.sendQueue, ^{
        [self resendChunks:indices forContext:context];
        @synchronized (self) {
            if (self.pendingResendJobs > 0) {
                self.pendingResendJobs--;
            }
        }
    });
}

- (void)resendChunks:(NSArray<NSNumber *> *)indices forContext:(OCTNgcSentChunkedFile *)context
{
    // KHANDAQ (audit 2026-08-20): a cancelled send must stay cancelled. A peer asking for chunks of
    // a transfer the user has stopped would otherwise resurrect it — the same rule Android enforces
    // before servicing a resend. This runs on sendQueue, which is the queue that mutates the set,
    // so reading it here needs no extra synchronisation.
    NSString *msgIdHex = [[OCTTox binToHexString:context.msgId.bytes length:context.msgId.length] lowercaseString];
    if ([self isSendCancelledForMsgIdHex:msgIdHex]) {
        return;
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForReadingAtPath:context.filePath];
    if (! handle) {
        return;
    }

    for (NSNumber *indexNum in indices) {
        int chunkIndex = indexNum.intValue;
        uint64_t offset = (uint64_t)chunkIndex * (uint64_t)kOCTNgcChunkPayloadMax;
        if (offset >= context.fileSize) {
            continue;
        }

        NSUInteger toRead = (NSUInteger)MIN((uint64_t)kOCTNgcChunkPayloadMax, context.fileSize - offset);
        [handle seekToFileOffset:offset];
        NSData *payload = [handle readDataOfLength:toRead];

        if (payload.length != toRead) {
            continue;
        }

        NSMutableData *chunkPacket = [NSMutableData dataWithCapacity:kOCTNgcFileChunkHeader + payload.length];
        [self appendMagicToData:chunkPacket];
        [chunkPacket appendBytes:&(uint8_t){kOCTNgcPktFileChunk} length:1];
        [self appendMsgId:context.msgId toData:chunkPacket];
        [self appendU32BE:(uint32_t)chunkIndex toData:chunkPacket];
        [self appendU32BE:(uint32_t)payload.length toData:chunkPacket];
        [chunkPacket appendData:payload];
        [self sendPacket:chunkPacket groupNumber:context.groupNumber error:nil];

        usleep(1500);
    }

    [handle closeFile];
}

- (void)queueOrphanChunkWithAssemblyKey:(NSString *)assemblyKey
                                 peerId:(uint32_t)peerId
                       peerPublicKeyHex:(NSString *)peerPublicKeyHex
                                   data:(NSData *)data
{
    NSTimeInterval now = [NSDate timeIntervalSinceReferenceDate];

    @synchronized (self.orphanChunks) {
        [self pruneExpiredOrphanChunksAtTime:now];

        NSMutableArray *list = self.orphanChunks[assemblyKey];

        if (! list) {
            // KHANDAQ (audit F-4): cap the number of assemblies we hold chunks for, and make the peer
            // that is filling the map pay for it: evict the oldest key fed by THIS peer first, so a
            // flood of msgIds that never get a BEGIN recycles its own slots instead of pushing out the
            // orphans of the honest sender racing its own BEGIN (a millisecond-scale race). Only when
            // this peer holds no slot at all do we fall back to the global least-recently-fed key.
            while (self.orphanChunks.count >= kOCTNgcMaxOrphanAssemblies) {
                NSString *staleKey = [self leastRecentlyFedOrphanKeyFromPeerId:peerId
                                                              peerPublicKeyHex:peerPublicKeyHex
                                                                     anyOrigin:NO];

                if (! staleKey) {
                    staleKey = [self leastRecentlyFedOrphanKeyFromPeerId:peerId
                                                        peerPublicKeyHex:peerPublicKeyHex
                                                               anyOrigin:YES];
                }

                if (! staleKey) {
                    break;
                }

                [self.orphanChunks removeObjectForKey:staleKey];
            }

            list = [NSMutableArray array];
            self.orphanChunks[assemblyKey] = list;
        }

        if (list.count >= kOCTNgcMaxOrphanChunks) {
            [list removeObjectAtIndex:0];
        }

        OCTNgcOrphanChunk *orphan = [OCTNgcOrphanChunk new];
        orphan.data = [data copy];
        orphan.originPeerId = peerId;
        orphan.originPublicKeyHex = peerPublicKeyHex;
        orphan.timestamp = now;
        [list addObject:orphan];
    }
}

// Both helpers below must be called with self.orphanChunks locked.
- (void)pruneExpiredOrphanChunksAtTime:(NSTimeInterval)now
{
    NSMutableArray<NSString *> *emptyKeys = nil;

    for (NSString *key in self.orphanChunks) {
        NSMutableArray<OCTNgcOrphanChunk *> *list = self.orphanChunks[key];

        // Chunks are appended in arrival order, so the head is always the oldest.
        while (list.count > 0 && (now - list.firstObject.timestamp) > kOCTNgcOrphanChunkTTL) {
            [list removeObjectAtIndex:0];
        }

        if (list.count == 0) {
            if (! emptyKeys) {
                emptyKeys = [NSMutableArray array];
            }

            [emptyKeys addObject:key];
        }
    }

    if (emptyKeys) {
        [self.orphanChunks removeObjectsForKeys:emptyKeys];
    }
}

// Returns the key whose most recent chunk is the OLDEST — restricted to keys last fed by the given
// peer unless anyOrigin is YES. Same peer match as the assembly binding: stable key when both sides
// know it, transient peer number otherwise.
- (NSString *)leastRecentlyFedOrphanKeyFromPeerId:(uint32_t)peerId
                                 peerPublicKeyHex:(NSString *)peerPublicKeyHex
                                        anyOrigin:(BOOL)anyOrigin
{
    NSString *staleKey = nil;
    NSTimeInterval staleTimestamp = 0;

    for (NSString *key in self.orphanChunks) {
        OCTNgcOrphanChunk *last = self.orphanChunks[key].lastObject;

        if (! last) {
            continue;
        }

        if (! anyOrigin) {
            BOOL sameOrigin = (last.originPublicKeyHex.length > 0 && peerPublicKeyHex.length > 0)
                ? ([last.originPublicKeyHex caseInsensitiveCompare:peerPublicKeyHex] == NSOrderedSame)
                : (last.originPeerId == peerId);

            if (! sameOrigin) {
                continue;
            }
        }

        if (! staleKey || last.timestamp < staleTimestamp) {
            staleKey = key;
            staleTimestamp = last.timestamp;
        }
    }

    return staleKey;
}

- (void)flushOrphanChunksForAssemblyKey:(NSString *)assemblyKey
                            groupNumber:(uint32_t)groupNumber
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

    NSTimeInterval now = [NSDate timeIntervalSinceReferenceDate];

    for (OCTNgcOrphanChunk *orphan in list) {
        if ((now - orphan.timestamp) > kOCTNgcOrphanChunkTTL) {
            continue;
        }

        // KHANDAQ (audit F-5): replay with the origin recorded at queue time, not the peer that delivered
        // the BEGIN — applyIncomingChunk drops the ones that came from somebody else.
        [self applyIncomingChunkWithGroupNumber:groupNumber
                                         peerId:orphan.originPeerId
                               peerPublicKeyHex:orphan.originPublicKeyHex
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
