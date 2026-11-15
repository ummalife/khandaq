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
// Silent rounds IN A ROW after which an assembly is dropped (24 x 2.5 s = the 60 s of kOCTNgcAssemblyStaleTimeout),
// and the most requests one file sends.
static const int kOCTNgcNackMaxRounds = 24;
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
// Resend jobs allowed to wait on the resend queue.
static const NSInteger kOCTNgcMaxPendingResendJobs = 4;

// KHANDAQ (0.2.56): the pace of the packets of the files this client sends into groups, the desktop's
// (Air::UploadPace in airgroupfilequeue.h). toxcore tells nothing of how full a member's send array is: a
// packet to the whole group is taken even when some member's array (GCC_BUFFER_SIZE, 8192 entries, a chunk
// takes 28 of them) has no room for it, and it is then lost for that member. iOS sent all the chunks of a
// file at once, so past about 290 chunks (some 11 MB) the rest was lost and the file never arrived. The rate
// counts what this device uploads, a packet once for every member it goes to: the desktop measured it, two of
// its nodes on one Mac got a file of 14 MB whole at this rate, and at 1 and 2 MiB/s the array filled.
static const double kOCTNgcUploadBytesPerSecond = 512.0 * 1024.0;
static const NSTimeInterval kOCTNgcUploadBurst = 0.25;
// KHANDAQ (0.2.56): how long a file waits for somebody in its group to send to before it is not sent, the
// desktop's aloneLimitMs. A member that drops off stays a peer for toxcore for about a minute.
static const NSTimeInterval kOCTNgcAloneLimit = 120.0;
static const NSTimeInterval kOCTNgcAlonePoll = 0.5;
// KHANDAQ (0.2.56): how many times a file goes again from its BEGIN after the group had nobody to send to.
// On a link slower than the pace such a gap comes back about every minute: toxcore drops a member whose
// oldest packet stays unacknowledged for 58 s (GC_CONFIRMED_PEER_TIMEOUT), and a file that started over at
// every gap would never end (QA-Live 15.11, an iOS simulator and an Android emulator). After these, a gap
// sends the BEGIN again and the file goes on from where it was; a member that lacks chunks asks for them.
static const NSUInteger kOCTNgcMaxRestartsFromBegin = 2;
// KHANDAQ (0.2.56): a chunk asked for again goes to the member that asked, as a private packet, which
// toxcore refuses while that member's send array is full. It is tried again a little later, for this long
// at most: Android's CHUNK_SEND_STALL_MS, and its back-off of 10 ms and 2 ms more a try, 120 ms at most.
static const NSTimeInterval kOCTNgcPrivateSendStall = 20.0;
// KHANDAQ (0.2.56): a request for the whole file (Android sends one while it has nothing of the file) is
// served once in this long, whoever sends it: Android's RESEND_SERVICE_THROTTLE_MS.
static const NSTimeInterval kOCTNgcWholeResendThrottle = 4.0;

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
// KHANDAQ (0.2.56): the requests for missing chunks this assembly sent. Each one goes to the whole group.
@property (nonatomic, assign) int nackRequestsSent;
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
// KHANDAQ (0.2.56): the name its BEGIN carries, which goes again before the chunks asked for.
@property (nonatomic, copy) NSString *fileName;
// KHANDAQ (0.2.56): how many of its chunks went so far. A chunk asked for before it went is not sent again
// (an iOS receiver asks for the chunks still to come too): it comes in its turn.
@property (atomic, assign) int chunksSent;
@end

@implementation OCTNgcSentChunkedFile
@end

/**
 * KHANDAQ (0.2.56): the upload pace (kOCTNgcUploadBytesPerSecond), shared by the files this client sends
 * and the chunks it sends again. A packet may go whenever the credit is positive, however much it costs:
 * a costly one is paid for by the wait after it, and after a pause at most a burst goes at once.
 */
@interface OCTNgcUploadPace : NSObject
@property (atomic, assign) double bytesPerSecond;
@property (atomic, assign) double burstBytes;
/** Waits on the calling thread until a packet may go, and spends what it costs. */
- (void)waitToSpend:(double)cost;
@end

@implementation OCTNgcUploadPace
{
    double _credit;
    NSTimeInterval _lastTime;
}

- (instancetype)init
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _bytesPerSecond = kOCTNgcUploadBytesPerSecond;
    _burstBytes = kOCTNgcUploadBytesPerSecond * kOCTNgcUploadBurst;
    _lastTime = -1.0;

    return self;
}

- (void)waitToSpend:(double)cost
{
    while (YES) {
        NSTimeInterval wait = 0.0;

        @synchronized (self) {
            const NSTimeInterval now = [NSProcessInfo processInfo].systemUptime;
            const double rate = self.bytesPerSecond;

            if (_lastTime < 0.0) {
                _credit = self.burstBytes;
            }
            else if (now > _lastTime) {
                _credit = MIN(self.burstBytes, _credit + (now - _lastTime) * rate);
            }

            _lastTime = MAX(_lastTime, now);

            if (_credit > 0.0) {
                _credit -= cost;
                return;
            }

            wait = (1.0 - _credit) / rate;
        }

        [NSThread sleepForTimeInterval:wait];
    }
}

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
// KHANDAQ (0.2.56): the files this client sends go from a queue of their own, at the upload pace, and the
// chunks other members ask for again from another, at the same pace. A paced file of 25 MB takes most of a
// minute for each member, and on sendQueue it would have held up every file coming in meanwhile.
@property (nonatomic, strong) dispatch_queue_t uploadQueue;
@property (nonatomic, strong) dispatch_queue_t resendQueue;
@property (nonatomic, strong) OCTNgcUploadPace *pace;
// KHANDAQ (0.2.56): kOCTNgcAloneLimit, which the tests shorten.
@property (atomic, assign) NSTimeInterval aloneLimit;
// KHANDAQ (0.2.56): when a request for a whole file was last served (kOCTNgcWholeResendThrottle).
@property (nonatomic, assign) NSTimeInterval lastWholeResendServedAt;
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
 *
 * KHANDAQ (0.2.56): the chunks asked for now go to the member that asked (sendPrivatePacketBlock), at the
 * upload pace; they go to the whole group only where no private path is set.
 */
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *lastResendServedAt;
/** Resend jobs waiting on resendQueue. Bounded: a flood of requests must not queue up resends for long. */
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
    _uploadQueue = dispatch_queue_create("ngc-group-file-upload", DISPATCH_QUEUE_SERIAL);
    _resendQueue = dispatch_queue_create("ngc-group-file-resend", DISPATCH_QUEUE_SERIAL);
    _pace = [OCTNgcUploadPace new];
    _aloneLimit = kOCTNgcAloneLimit;

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

    // KHANDAQ (0.2.56): read by the upload and resend queues, so it is locked rather than kept on one queue.
    @synchronized (self.cancelledMsgIdHexes) {
        [self.cancelledMsgIdHexes addObject:msgIdHex.lowercaseString];
    }
}

- (BOOL)isSendCancelledForMsgIdHex:(NSString *)msgIdHex
{
    if (msgIdHex.length == 0) {
        return NO;
    }

    @synchronized (self.cancelledMsgIdHexes) {
        return [self.cancelledMsgIdHexes containsObject:msgIdHex.lowercaseString];
    }
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

    // KHANDAQ (0.2.56): the directory block reads the Realm (it looks the group's chat up), and the Realm
    // lives on the main thread, the thread of this callback. It ran on sendQueue with the handlers below
    // and threw "Realm accessed from incorrect thread" on EVERY incoming group file, a photo of a few
    // kilobytes as much as a video. Look it up here, before the hop: only a SINGLE or a BEGIN stores a file.
    NSString *directory = nil;

    if (pkt == kOCTNgcPktFileSingle || pkt == kOCTNgcPktFileBegin) {
        directory = self.incomingFilesDirectoryBlock(groupNumber) ?: @"";
    }

    // KHANDAQ (audit): the Single/Chunk handlers do synchronous file I/O (writeToFile:) that would jank
    // the UI, so all incoming assembly runs on the serial sendQueue: it keeps the chunks in order and the
    // @synchronized guards make the handlers thread-safe. Nothing on that queue may touch the Realm: the
    // directory is looked up above, and every block that updates the chat is handed to the main queue.
    dispatch_async(self.sendQueue, ^{
        switch (pkt) {
            case kOCTNgcPktFileSingle:
                [self handleIncomingSingleWithGroupNumber:groupNumber
                                                   peerId:peerId
                                         peerPublicKeyHex:peerPublicKeyHex
                                                     data:data
                                                directory:directory];
                break;
            case kOCTNgcPktFileBegin:
                [self handleIncomingBeginWithGroupNumber:groupNumber
                                                  peerId:peerId
                                        peerPublicKeyHex:peerPublicKeyHex
                                                    data:data
                                               directory:directory];
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

    dispatch_async(self.uploadQueue, ^{
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

    NSString *msgIdHex = [OCTTox binToHexString:(uint8_t *)msgId.bytes length:msgId.length];
    NSUInteger members = [self waitForMembersInGroupNumber:groupNumber msgIdHex:msgIdHex waited:NULL];

    if (members == 0) {
        [self fillAloneError:error msgIdHex:msgIdHex];
        return NO;
    }

    return [self sendPacedPacket:packet groupNumber:groupNumber members:members error:error];
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
    NSString *msgIdHex = [OCTTox binToHexString:(uint8_t *)msgId.bytes length:msgId.length];

    // KHANDAQ (#15): remember this send so we can re-serve chunks a receiver NACKs for.
    OCTNgcSentChunkedFile *sentContext = [OCTNgcSentChunkedFile new];
    sentContext.filePath = filePath;
    sentContext.msgId = msgId;
    sentContext.fileSize = fileSize;
    sentContext.totalChunks = totalChunks;
    sentContext.groupNumber = groupNumber;
    sentContext.fileName = fileName;
    @synchronized (self.sentChunkedFiles) {
        self.sentChunkedFiles[msgIdHex.lowercaseString] = sentContext;
        while (self.sentChunkedFiles.count > 24) {
            [self.sentChunkedFiles removeObjectForKey:self.sentChunkedFiles.allKeys.firstObject];
        }
    }

    NSData *beginPacket = [self beginPacketForSentFile:sentContext];
    NSFileHandle *handle = [NSFileHandle fileHandleForReadingAtPath:filePath];

    if (! handle) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorMissingFile reason:@"Cannot read file"];
        return NO;
    }

    // KHANDAQ (0.2.56): the BEGIN, then the chunks in order, each once the upload pace lets it go (see
    // kOCTNgcUploadBytesPerSecond); -1 stands for the BEGIN.
    int next = -1;
    NSUInteger restartsFromBegin = 0;

    while (next < totalChunks) {
        if ([self isSendCancelledForMsgIdHex:msgIdHex]) {
            [handle closeFile];
            [self fillError:error code:OCTNgcGroupFileTransferErrorCancelled reason:@"Transfer cancelled"];
            return NO;
        }

        BOOL waited = NO;
        NSUInteger members = [self waitForMembersInGroupNumber:groupNumber msgIdHex:msgIdHex waited:&waited];

        if (members == 0) {
            [handle closeFile];
            [self fillAloneError:error msgIdHex:msgIdHex];
            return NO;
        }

        if (waited && next > -1 && restartsFromBegin < kOCTNgcMaxRestartsFromBegin) {
            // KHANDAQ (0.2.56): the group had nobody to send to. What went before was lost with the members
            // that left, a member that comes now had none of it, and a receiver drops a file it gets no
            // packet of for a minute: the file goes again from its BEGIN, as the desktop sends it then. A
            // receiver that kept its part keeps it, as a BEGIN of a file it holds does not wipe it.
            OCTLogInfo(@"NGC group file %@ goes again from its BEGIN: the group had nobody to send to", msgIdHex);
            restartsFromBegin++;
            next = -1;
        }
        else if (waited && next > -1) {
            // KHANDAQ (0.2.56): it went again from its BEGIN kOCTNgcMaxRestartsFromBegin times already. The
            // BEGIN goes again, so that a member that comes now knows the chunks, and the file goes on.
            OCTLogInfo(@"NGC group file %@ goes on from chunk %d: it went again from its BEGIN %lu times",
                       msgIdHex, next, (unsigned long)restartsFromBegin);

            if (! [self sendPacedPacket:beginPacket groupNumber:groupNumber members:members error:error]) {
                [handle closeFile];
                return NO;
            }
        }

        NSData *packet = beginPacket;

        if (next >= 0) {
            packet = [self chunkPacketForSentFile:sentContext chunkIndex:next handle:handle];

            if (! packet) {
                [handle closeFile];
                [self fillError:error code:OCTNgcGroupFileTransferErrorMissingFile reason:@"Unexpected EOF"];
                return NO;
            }
        }

        if (! [self sendPacedPacket:packet groupNumber:groupNumber members:members error:error]) {
            [handle closeFile];
            return NO;
        }

        if (next < 0) {
            next++;
            continue;
        }

        sentContext.chunksSent = MAX(sentContext.chunksSent, next + 1);

        if (progressBlock || self.transferProgressBlock) {
            float chunkProgress = (float)(next + 1) / (float)totalChunks;

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

        next++;
    }

    [handle closeFile];
    return YES;
}

- (NSData *)beginPacketForSentFile:(OCTNgcSentChunkedFile *)sentFile
{
    NSMutableData *beginPacket = [NSMutableData dataWithCapacity:kOCTNgcFileBeginHeader];
    [self appendMagicToData:beginPacket];
    [beginPacket appendBytes:&(uint8_t){kOCTNgcPktFileBegin} length:1];
    [self appendMsgId:sentFile.msgId toData:beginPacket];
    [self appendCreateTimestampToData:beginPacket];
    [self appendFilename:sentFile.fileName toData:beginPacket];
    [self appendU64BE:sentFile.fileSize toData:beginPacket];
    [self appendU32BE:(uint32_t)kOCTNgcChunkPayloadMax toData:beginPacket];
    [self appendU32BE:(uint32_t)sentFile.totalChunks toData:beginPacket];
    return beginPacket;
}

// nil when the file no longer holds the chunk.
- (NSData *)chunkPacketForSentFile:(OCTNgcSentChunkedFile *)sentFile
                                 chunkIndex:(int)chunkIndex
                                     handle:(NSFileHandle *)handle
{
    uint64_t offset = (uint64_t)chunkIndex * (uint64_t)kOCTNgcChunkPayloadMax;

    if (chunkIndex < 0 || offset >= sentFile.fileSize) {
        return nil;
    }

    NSUInteger toRead = (NSUInteger)MIN((uint64_t)kOCTNgcChunkPayloadMax, sentFile.fileSize - offset);
    [handle seekToFileOffset:offset];
    NSData *payload = [handle readDataOfLength:toRead];

    if (payload.length != toRead) {
        return nil;
    }

    NSMutableData *chunkPacket = [NSMutableData dataWithCapacity:kOCTNgcFileChunkHeader + payload.length];
    [self appendMagicToData:chunkPacket];
    [chunkPacket appendBytes:&(uint8_t){kOCTNgcPktFileChunk} length:1];
    [self appendMsgId:sentFile.msgId toData:chunkPacket];
    [self appendU32BE:(uint32_t)chunkIndex toData:chunkPacket];
    [self appendU32BE:(uint32_t)payload.length toData:chunkPacket];
    [chunkPacket appendData:payload];
    return chunkPacket;
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

// KHANDAQ (0.2.56): a packet to the whole group once the upload pace lets it go. It costs the pace its bytes
// once for every member it goes to.
- (BOOL)sendPacedPacket:(NSData *)packet groupNumber:(uint32_t)groupNumber members:(NSUInteger)members error:(NSError **)error
{
    [self.pace waitToSpend:(double)packet.length * (double)MAX(members, (NSUInteger)1)];
    return [self sendPacket:packet groupNumber:groupNumber error:error];
}

- (NSUInteger)memberCountForGroupNumber:(uint32_t)groupNumber
{
    OCTNgcGroupFileTransferMemberCountBlock memberCountBlock = self.memberCountBlock;
    return memberCountBlock ? memberCountBlock(groupNumber) : 1;
}

// KHANDAQ (0.2.56): how many members a packet into the group goes to, once there is one: toxcore takes a
// packet for nobody and says it went, and a file that reached nobody was shown sent. Waits for one for
// aloneLimit at most; 0 when none came, or when the send was called off meanwhile. *waited says whether the
// group had nobody when it was asked.
- (NSUInteger)waitForMembersInGroupNumber:(uint32_t)groupNumber msgIdHex:(NSString *)msgIdHex waited:(BOOL *)waited
{
    NSTimeInterval aloneSince = 0.0;

    if (waited) {
        *waited = NO;
    }

    while (YES) {
        NSUInteger members = [self memberCountForGroupNumber:groupNumber];

        if (members > 0) {
            return members;
        }

        if (waited) {
            *waited = YES;
        }

        const NSTimeInterval now = [NSProcessInfo processInfo].systemUptime;

        if (aloneSince == 0.0) {
            aloneSince = now;
        }

        if ((now - aloneSince) >= self.aloneLimit || [self isSendCancelledForMsgIdHex:msgIdHex]) {
            return 0;
        }

        [NSThread sleepForTimeInterval:MIN(kOCTNgcAlonePoll, self.aloneLimit)];
    }
}

- (void)fillAloneError:(NSError **)error msgIdHex:(NSString *)msgIdHex
{
    if ([self isSendCancelledForMsgIdHex:msgIdHex]) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorCancelled reason:@"Transfer cancelled"];
        return;
    }

    [self fillError:error code:OCTNgcGroupFileTransferErrorSendFailed reason:@"Nobody in the group to send the file to"];
}

#pragma mark - Receive

- (void)handleIncomingSingleWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                           peerPublicKeyHex:(NSString *)peerPublicKeyHex
                                       data:(NSData *)data
                                  directory:(NSString *)directory
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
    [self reportIncomingFileWithGroupNumber:groupNumber
                                     peerId:peerId
                           peerPublicKeyHex:peerPublicKeyHex
                                   fileName:[localName lastPathComponent]
                                   filePath:localName
                                   fileSize:fileSize
                                   msgIdHex:msgIdHex];
}

// KHANDAQ (0.2.56): the complete block writes the file's row in the Realm, which lives on the main thread.
// It was called right here on sendQueue and threw, so it goes to the main queue, as the begin and progress
// blocks already do. That queue is FIFO, so the row still sees its BEGIN and its progress before this.
- (void)reportIncomingFileWithGroupNumber:(uint32_t)groupNumber
                                   peerId:(uint32_t)peerId
                         peerPublicKeyHex:(NSString *)peerPublicKeyHex
                                 fileName:(NSString *)fileName
                                 filePath:(NSString *)filePath
                                 fileSize:(uint64_t)fileSize
                                 msgIdHex:(NSString *)msgIdHex
{
    OCTNgcGroupFileTransferIncomingFileBlock completeBlock = self.incomingCompleteBlock;

    dispatch_async(dispatch_get_main_queue(), ^{
        completeBlock(groupNumber, peerId, peerPublicKeyHex, fileName, filePath, fileSize, msgIdHex);
    });
}

- (void)handleIncomingBeginWithGroupNumber:(uint32_t)groupNumber
                                    peerId:(uint32_t)peerId
                          peerPublicKeyHex:(NSString *)peerPublicKeyHex
                                      data:(NSData *)data
                                 directory:(NSString *)directory
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
        [self reportIncomingFileWithGroupNumber:groupNumber
                                         peerId:peerId
                               peerPublicKeyHex:assembly.originPublicKeyHex
                                       fileName:displayName
                                       filePath:assembly.outPath
                                       fileSize:assembly.totalSize
                                       msgIdHex:msgIdHex];
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
        // KHANDAQ (0.2.56): a chunk came, so the count of silent rounds starts again: an assembly is dropped
        // after kOCTNgcNackMaxRounds silent rounds in a row, as kOCTNgcAssemblyStaleTimeout says. The count
        // never went back to zero, so a sender that paces its chunks for a big group (one every few seconds:
        // the desktop waits up to 40 s) had every file of more than about a dozen chunks dropped half way,
        // and nothing sent it again.
        assembly.nackRounds = 0;
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

    // KHANDAQ (0.2.56): a slowly paced transfer is silent at most rounds, and a request goes to the whole
    // group. Now that such a transfer lives on, one file still asks kOCTNgcNackMaxRounds times at most, as
    // many as the rounds that ended it before.
    if (assembly.nackRequestsSent >= kOCTNgcNackMaxRounds) {
        return;
    }

    assembly.nackRequestsSent++;
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
    NSString *msgIdHex = [[OCTTox binToHexString:(uint8_t *)msgId.bytes length:msgId.length] lowercaseString];

    OCTNgcSentChunkedFile *context = nil;
    @synchronized (self.sentChunkedFiles) {
        context = self.sentChunkedFiles[msgIdHex];
    }

    // KHANDAQ (0.2.56): and only to the group the file went to. The chunks now go to the peer number that
    // asked, and the same number names somebody else in another group.
    if (! context || context.groupNumber != groupNumber) {
        return;  // not the original sender of this file (or it has been forgotten)
    }

    BOOL wholeFile = NO;
    NSIndexSet *asked = [self chunksAskedForInRequest:data totalChunks:context.totalChunks wholeFile:&wholeFile];

    if (asked.count == 0) {
        return;
    }

    const NSTimeInterval now = [NSDate timeIntervalSinceReferenceDate];

    // KHANDAQ (0.2.56): a request for the whole file is served once in kOCTNgcWholeResendThrottle, whoever
    // asks, as Android serves one: it costs the whole file again.
    if (wholeFile) {
        @synchronized (self.lastResendServedAt) {
            if (self.lastWholeResendServedAt > 0.0 && (now - self.lastWholeResendServedAt) < kOCTNgcWholeResendThrottle) {
                return;
            }
        }
    }

    // KHANDAQ (audit 2026-08-20): who is asking, and how often. Both were unchecked; see
    // `lastResendServedAt`. peerId is what makes the cooldown per-requester rather than global, so
    // one peer spamming cannot deny an honest peer its retransmission.
    NSString *cooldownKey = [NSString stringWithFormat:@"%@|%u", msgIdHex, peerId];
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

        if (wholeFile) {
            self.lastWholeResendServedAt = now;
        }
    }

    // KHANDAQ (0.2.56): only the chunks that went. An iOS receiver lists the chunks still to come with the
    // missing ones, and those come in their turn.
    const int chunksSent = context.chunksSent;
    NSMutableIndexSet *wanted = [asked mutableCopy];
    [wanted removeIndexesInRange:NSMakeRange((NSUInteger)MAX(chunksSent, 0), NSNotFound - (NSUInteger)MAX(chunksSent, 0))];

    if (wanted.count == 0) {
        return;
    }

    // An unbounded backlog of resend jobs would keep serving a flood long after it stopped. Refuse rather
    // than queue.
    @synchronized (self) {
        if (self.pendingResendJobs >= kOCTNgcMaxPendingResendJobs) {
            return;
        }
        self.pendingResendJobs++;
    }

    dispatch_async(self.resendQueue, ^{
        [self resendChunks:wanted toPeerId:peerId forContext:context];
        @synchronized (self) {
            if (self.pendingResendJobs > 0) {
                self.pendingResendJobs--;
            }
        }
    });
}

/**
 * KHANDAQ (0.2.56): the chunks a 0x14 request asks for, in either layout; nil for one that fits neither.
 *   iOS:     u32 count (1...256), then count u32 indices.
 *   Android: u32 total chunks of the file, then a bitmap of (total + 7) / 8 bytes, bit i (least significant
 *            first) set for a chunk still missing (NgcGroupFileTransfer.buildFileRequestPacket). With total 0
 *            and no bitmap it asks for the whole file (*wholeFile).
 * iOS read only its own layout, so it answered no request from Android, and a file Android lost chunks of
 * never arrived. The lengths tell the two apart: four bytes an index against one bit a chunk, so a request
 * of the iOS layout is exactly 4 * count bytes long and one of Android's never is. Duplicates and indices
 * past the file cost nothing.
 */
- (NSIndexSet *)chunksAskedForInRequest:(NSData *)data totalChunks:(int)totalChunks wholeFile:(BOOL *)wholeFile
{
    *wholeFile = NO;

    if (data.length < 44 || totalChunks < 1) {
        return nil;
    }

    const NSUInteger bodyLength = data.length - 44;
    const uint32_t first = [self readU32BEFromData:data offset:40];
    NSMutableIndexSet *asked = [NSMutableIndexSet indexSet];

    if (first >= 1 && first <= (uint32_t)kOCTNgcNackMaxIndicesPerPacket && bodyLength == (NSUInteger)first * 4) {
        for (uint32_t i = 0; i < first; i++) {
            uint32_t index = [self readU32BEFromData:data offset:(44 + i * 4)];

            if (index < (uint32_t)totalChunks) {
                [asked addIndex:(NSUInteger)index];
            }
        }

        return asked;
    }

    if (first == 0 && bodyLength == 0) {
        *wholeFile = YES;
        [asked addIndexesInRange:NSMakeRange(0, (NSUInteger)totalChunks)];
        return asked;
    }

    if (first == (uint32_t)totalChunks && bodyLength >= ((NSUInteger)first + 7) / 8) {
        const uint8_t *bitmap = (const uint8_t *)data.bytes + 44;

        for (uint32_t i = 0; i < first; i++) {
            if (bitmap[i >> 3] & (1u << (i & 7))) {
                [asked addIndex:(NSUInteger)i];
            }
        }

        return asked;
    }

    return nil;
}

// KHANDAQ (0.2.56): the chunks go to the member that asked for them, at the upload pace, its BEGIN first, as
// Android sends them: a receiver whose minute ran out dropped the file and opens it again from the BEGIN,
// one that still has it keeps what it has. They went to the whole group, at once, so a receiver that lost
// chunks to a full send array lost the copies too.
- (void)resendChunks:(NSIndexSet *)indices toPeerId:(uint32_t)peerId forContext:(OCTNgcSentChunkedFile *)context
{
    // KHANDAQ (audit 2026-08-20): a cancelled send must stay cancelled. A peer asking for chunks of
    // a transfer the user has stopped would otherwise resurrect it — the same rule Android enforces
    // before servicing a resend.
    NSString *msgIdHex = [[OCTTox binToHexString:(uint8_t *)context.msgId.bytes length:context.msgId.length] lowercaseString];
    if ([self isSendCancelledForMsgIdHex:msgIdHex]) {
        return;
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForReadingAtPath:context.filePath];
    if (! handle) {
        return;
    }

    if (! [self sendResendPacket:[self beginPacketForSentFile:context] toPeerId:peerId context:context msgIdHex:msgIdHex]) {
        [handle closeFile];
        return;
    }

    __block NSUInteger sent = 0;
    [indices enumerateIndexesUsingBlock:^(NSUInteger index, BOOL *stop) {
        NSData *chunkPacket = [self chunkPacketForSentFile:context chunkIndex:(int)index handle:handle];

        if (! chunkPacket) {
            return;
        }

        if ([self isSendCancelledForMsgIdHex:msgIdHex] ||
            ! [self sendResendPacket:chunkPacket toPeerId:peerId context:context msgIdHex:msgIdHex]) {
            *stop = YES;
            return;
        }

        sent++;
    }];

    [handle closeFile];
    OCTLogInfo(@"NGC group file %@: %lu of %lu chunks sent again to peer %u",
               msgIdHex, (unsigned long)sent, (unsigned long)indices.count, peerId);
}

// KHANDAQ (0.2.56): one packet to the member that asked for it, at the upload pace (it goes to one member).
// toxcore refuses a private packet while that member's send array is full, so it is tried again a little
// later, for kOCTNgcPrivateSendStall at most. NO when it cannot go: the member left, or its array stayed full.
- (BOOL)sendResendPacket:(NSData *)packet
                toPeerId:(uint32_t)peerId
                 context:(OCTNgcSentChunkedFile *)context
                msgIdHex:(NSString *)msgIdHex
{
    OCTNgcGroupFileTransferSendPrivatePacketBlock sendPrivatePacketBlock = self.sendPrivatePacketBlock;

    if (! sendPrivatePacketBlock) {
        NSUInteger members = [self memberCountForGroupNumber:context.groupNumber];
        return [self sendPacedPacket:packet groupNumber:context.groupNumber members:members error:nil];
    }

    [self.pace waitToSpend:(double)packet.length];

    const NSTimeInterval deadline = [NSProcessInfo processInfo].systemUptime + kOCTNgcPrivateSendStall;

    for (int attempt = 0; ; attempt++) {
        NSError *error = nil;

        if (sendPrivatePacketBlock(context.groupNumber, peerId, packet, &error)) {
            return YES;
        }

        BOOL arrayFull = [error.domain isEqualToString:kOCTToxErrorDomain] &&
                         error.code == OCTToxErrorGroupSendCustomPacketUnknown;

        if (! arrayFull || [NSProcessInfo processInfo].systemUptime >= deadline ||
            [self isSendCancelledForMsgIdHex:msgIdHex]) {
            OCTLogInfo(@"NGC group file %@: peer %u takes no more chunks (%@)", msgIdHex, peerId, error);
            return NO;
        }

        [NSThread sleepForTimeInterval:MIN(0.120, 0.010 + 0.002 * attempt)];
    }
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
