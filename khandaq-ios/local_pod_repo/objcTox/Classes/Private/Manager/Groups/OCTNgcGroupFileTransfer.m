// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcGroupFileTransfer.h"
#import "OCTTox+Private.h"
#import "OCTFileTools.h"
#import "OCTLogging.h"
#import <Security/Security.h>
#import <time.h>

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
// KHANDAQ (0.2.57): a member's request for the whole of a file is taken once in this time at most, counted from its
// request taken before (Android's RESEND_SERVICE_THROTTLE_MS); and one such resend of a file goes at a time.
static const uint64_t kOCTNgcWholeFileRequestThrottleMs = 4000;
// KHANDAQ (0.2.57): the pace of the group file packets this device sends (OCTNgcUploadPace): Android's NgcUploadPace
// and the desktop's Air::UploadPace, with their numbers. About 4 Mbit/s, shared by the members a packet goes to.
static const uint64_t kOCTNgcUploadBytesPerSecond = 512 * 1024;
// KHANDAQ (0.2.57): and the pace of the packets that go to a member toxcore reaches only through a TCP relay: the
// desktop's UploadPace::relayedBytesPerSecond (cbeb1d1f), a packet's bytes counted once however many such members it
// goes to. toxcore sends every part of a chunk again about 3 s later, the parts of the same second at once, and a relay
// passes a packet on only while its connection has room for it: over a relay those bursts are lost first. Measured live
// by the desktop: chunks lost at 256 and 128 KiB/s a member over a relay, whole at 64 and 32; direct paths whole at 512.
static const uint64_t kOCTNgcRelayedUploadBytesPerSecond = 64 * 1024;
// After a pause, at most this much of the rate goes at once.
static const uint64_t kOCTNgcUploadBurstMs = 250;
// Longer pauses fill the credit all the same; the bound keeps the product in range.
static const int64_t kOCTNgcUploadLongestPauseMs = 60 * 60 * 1000;
// The longest a paced send sleeps in one go, so that a cancel stops it soon.
static const uint64_t kOCTNgcUploadLongestSleepMs = 50;

static const size_t kOCTNgcMaxFileSize = 36701;
static const size_t kOCTNgcMaxPacketSize = 37000;
static const uint64_t kOCTNgcMaxFileTransferBytes = 200ULL * 1024ULL * 1024ULL;

static const size_t kOCTNgcSingleFileHeader = 6 + 1 + 1 + 32 + 4 + 255;
static const size_t kOCTNgcFileBeginHeader = 6 + 1 + 1 + 32 + 4 + 255 + 8 + 4 + 4;
static const size_t kOCTNgcFileChunkHeader = 6 + 1 + 1 + 32 + 4 + 4;
// A request for missing chunks: the magic, the type, the msg id, a count; what follows depends on its form
// (chunksWantedByRequest:totalChunks:).
static const size_t kOCTNgcFileRequestHeader = 6 + 1 + 1 + 32 + 4;
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
// KHANDAQ (0.2.57): an incoming file of several chunks is remembered complete this long, and this many of them at most
// (the oldest go first). A BEGIN of one in that time is its sender's again, not a new file: Android sends the BEGIN
// once more with each resend it serves, and a resend queued behind others can go after the file was complete here.
// That BEGIN opened a new assembly, which took the whole file again and stored it under a name of its own (seen live:
// "qa057-grp-3m 2.bin", which no message showed). The requests for a file end within minutes; half an hour leaves
// room for a resend that waited behind others.
static const uint64_t kOCTNgcCompletedAssemblyMemoryMs = 30 * 60 * 1000;
static const NSUInteger kOCTNgcMaxCompletedAssemblies = 512;

NSString *const kOCTNgcGroupFileTransferErrorDomain = @"OCTNgcGroupFileTransferErrorDomain";

// The first 8 hex digits of a msg id, in lower case: how the logs of every client name a group file.
static NSString *OCTNgcShortMsgId(NSString *msgIdHex)
{
    return [(msgIdHex.length > 8 ? [msgIdHex substringToIndex:8] : (msgIdHex ?: @"")) lowercaseString];
}

typedef NS_ENUM(NSInteger, OCTNgcGroupFileTransferErrorPrivate) {
    OCTNgcGroupFileTransferErrorPrivateFileTooLarge = OCTNgcGroupFileTransferErrorFileTooLarge,
    OCTNgcGroupFileTransferErrorPrivateMissingFile = OCTNgcGroupFileTransferErrorMissingFile,
    OCTNgcGroupFileTransferErrorPrivateSendFailed = OCTNgcGroupFileTransferErrorSendFailed,
    OCTNgcGroupFileTransferErrorPrivateCancelled = OCTNgcGroupFileTransferErrorCancelled,
};

@interface OCTNgcIncomingAssembly : NSObject
@property (nonatomic, copy) NSString *displayFilename;
@property (nonatomic, copy) NSString *filename;
// Where the file goes once complete. Nothing is there before: the chunks go to partialPath.
@property (nonatomic, copy) NSString *outPath;
// KHANDAQ (0.2.57): the hidden file the chunks are written to (partialPathForGroupFilePath:msgIdHex:).
@property (nonatomic, copy) NSString *partialPath;
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
// KHANDAQ (0.2.57): the chunks members asked for again that have not gone again yet, and whether they are going
// (resendQueue). A request that comes meanwhile adds to them, so a chunk asked for twice goes once more, not twice:
// at the pace a resend takes seconds, and members ask again every few. Guarded by the object.
@property (nonatomic, strong) NSMutableIndexSet *pendingResends;
@property (nonatomic, assign) BOOL resendUnderWay;
// KHANDAQ (0.2.57): whether this device still sends the file, and how many of its chunks it has handed over so far.
// The chunks it has not are not sent again for a request: they come all the same. At the pace a chunk to a big group
// takes seconds, and a member that hears nothing for a round asks for every chunk it lacks. Guarded by the object.
@property (nonatomic, assign) BOOL uploadUnderWay;
@property (nonatomic, assign) NSUInteger chunksHandedOver;
// KHANDAQ (0.2.57): the BEGIN the file went with: it goes first to a member that asks for the whole file.
@property (nonatomic, copy) NSData *beginPacket;
// KHANDAQ (0.2.57): the member the whole file goes to now, alone, for its request (wholeFileQueue), and the chunks still
// to go to it: all of them at first, and those its requests for chunks add meanwhile. Guarded by the object.
@property (nonatomic, assign) BOOL wholeFileUnderWay;
@property (nonatomic, assign) uint32_t wholeFilePeerId;
@property (nonatomic, strong) NSMutableIndexSet *wholeFilePending;
// When (nowMsBlock) the request of a member for the whole file was last taken, by its peer id. Guarded by the object.
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, NSNumber *> *wholeFileTakenAtMs;
@end

@implementation OCTNgcSentChunkedFile
@end

// KHANDAQ (0.2.60): the requests for files of ours this transfer no longer holds, while the app looks them up
// (sentFileLookupBlock); the desktop's Air::SentFileLookups, with its numbers. A request for such a file asks the app
// once; those that come meanwhile wait for its answer, the newest of each member, a few at most, and are answered
// once the file is found: a member asks again only some seconds later. This client asks the whole group, so most of
// these files are another member's: one the app does not know is not looked up again for half an hour.
static const NSUInteger kOCTNgcSentFileLookupMaxRequests = 4;
static const NSUInteger kOCTNgcSentFileLookupMaxKept = 256;
static const uint64_t kOCTNgcSentFileLookupAnswerWaitMs = 30000;
static const uint64_t kOCTNgcSentFileLookupNotOursMs = 30ULL * 60ULL * 1000ULL;
// The steps of 2 s in which FAT keeps the time of a change.
static const NSTimeInterval kOCTNgcSentFileModifiedSlack = 2.0;

typedef NS_ENUM(NSInteger, OCTNgcSentFileLookupStep) {
    OCTNgcSentFileLookupStepAsk,
    OCTNgcSentFileLookupStepWait,
    OCTNgcSentFileLookupStepDrop,
};

@interface OCTNgcSentFileLookup : NSObject
// Whether the app is asked now; NO once it said the file is not ours.
@property (nonatomic, assign) BOOL asked;
// When the app was asked, or said the file is not ours (nowMsBlock).
@property (nonatomic, assign) uint64_t sinceMs;
// The order in which the lookups were made: the lowest is the oldest.
@property (nonatomic, assign) uint64_t order;
// The requests that wait: each a peer id (NSNumber) and its packet (NSData).
@property (nonatomic, strong) NSMutableArray<NSArray *> *requests;
@end

@implementation OCTNgcSentFileLookup
@end

/**
 * KHANDAQ (0.2.57): the pace of the group file packets this device sends: Android's NgcUploadPace and the desktop's
 * Air::UploadPace, with their numbers.
 *
 * toxcore tells nothing of how full a member's send array is (GCC_BUFFER_SIZE, 8192 entries; a chunk takes 28 of
 * them, all sent at once), keeps each part until the member acknowledges it, and the member drops every part after a
 * lost one until that one comes again. iOS handed the chunks of a file to toxcore back to back (a pause of 2 ms every
 * 8): of a file of 86 chunks an Android member got 27 and the desktop none, and neither got the rest.
 *
 * The rate counts what this device uploads, a packet once for every member it goes to. A packet may go whenever the
 * credit is positive, however much it costs: a costly one is paid for by the wait after it. Its methods hold its lock;
 * a caller that wants readyAtMs: and msUntilReadyAtMs: as one answer holds it around both.
 */
@interface OCTNgcUploadPace : NSObject
- (instancetype)initWithBytesPerSecond:(uint64_t)bytesPerSecond burstMs:(uint64_t)burstMs;
/// What a packet of this size costs when it goes to this many members: at least one.
+ (uint64_t)costOfPacketBytes:(uint64_t)packetBytes members:(NSUInteger)members;
/// Whether a packet may go at `nowMs`, a monotonic clock.
- (BOOL)readyAtMs:(uint64_t)nowMs;
/// A packet went that cost this much (costOfPacketBytes:members:).
- (void)spend:(uint64_t)cost;
/// How long after `nowMs` readyAtMs: first says a packet may go: 0 when it may at `nowMs`.
- (uint64_t)msUntilReadyAtMs:(uint64_t)nowMs;
@end

@implementation OCTNgcUploadPace {
    int64_t _bytesPerSecond;
    int64_t _burstBytes;
    int64_t _credit;
    // -1 before the first packet.
    int64_t _lastMs;
}

- (instancetype)initWithBytesPerSecond:(uint64_t)bytesPerSecond burstMs:(uint64_t)burstMs
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _bytesPerSecond = MAX((int64_t)bytesPerSecond, (int64_t)1);
    _burstBytes = MAX(_bytesPerSecond * (int64_t)burstMs / 1000, (int64_t)1);
    _credit = 0;
    _lastMs = -1;

    return self;
}

+ (uint64_t)costOfPacketBytes:(uint64_t)packetBytes members:(NSUInteger)members
{
    return packetBytes * MAX((uint64_t)members, (uint64_t)1);
}

- (BOOL)readyAtMs:(uint64_t)nowMs
{
    @synchronized (self) {
        const int64_t now = (int64_t)nowMs;

        if (_lastMs < 0) {
            _credit = _burstBytes;
        }
        else if (now > _lastMs) {
            const int64_t pauseMs = MIN(now - _lastMs, kOCTNgcUploadLongestPauseMs);
            _credit = MIN(_burstBytes, _credit + pauseMs * _bytesPerSecond / 1000);
        }

        _lastMs = MAX(_lastMs, now);
        return _credit > 0;
    }
}

- (void)spend:(uint64_t)cost
{
    @synchronized (self) {
        _credit -= (int64_t)MIN(cost, (uint64_t)INT32_MAX);
    }
}

- (uint64_t)msUntilReadyAtMs:(uint64_t)nowMs
{
    @synchronized (self) {
        if (_lastMs < 0 || _credit > 0) {
            return 0;
        }

        // The shortest pause whose share of the rate, rounded down as readyAtMs: rounds it, makes the credit positive.
        const int64_t pauseMs = ((1 - _credit) * 1000 + _bytesPerSecond - 1) / _bytesPerSecond;
        return (uint64_t)MAX((int64_t)0, _lastMs + pauseMs - (int64_t)nowMs);
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
// KHANDAQ (0.2.57): the assembly keys of the incoming files complete lately, and when (nowMsBlock); see
// kOCTNgcCompletedAssemblyMemoryMs. Guarded by the lock of `assemblies`: a key leaves that map for this one in one
// step, so a BEGIN always finds its file in one of the two.
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *completedAssemblyKeys;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSMutableArray<OCTNgcOrphanChunk *> *> *orphanChunks;
@property (nonatomic, strong) NSMutableSet<NSString *> *cancelledMsgIdHexes;
@property (nonatomic, strong) NSMutableDictionary<NSString *, OCTNgcSentChunkedFile *> *sentChunkedFiles;
// KHANDAQ (0.2.60): the files of ours looked up now or lately (sentFileLookupBlock), by msg id hex in lower case.
// Guarded by the object.
@property (nonatomic, strong) NSMutableDictionary<NSString *, OCTNgcSentFileLookup *> *sentFileLookups;
@property (nonatomic, assign) uint64_t sentFileLookupsMade;
// Incoming packets, and the requests for missing chunks this device sends. Never waits for the pace.
@property (nonatomic, strong) dispatch_queue_t sendQueue;
// KHANDAQ (0.2.57): the group files this device sends, one at a time, at the pace (uploadPace); and the chunks members
// ask for again, at the same pace. On sendQueue a paced file held up every packet that came for seconds.
@property (nonatomic, strong) dispatch_queue_t uploadQueue;
@property (nonatomic, strong) dispatch_queue_t resendQueue;
// KHANDAQ (0.2.57): the whole files members ask for, each to its member alone, one at a time, at the same pace. Apart
// from resendQueue, so a big file for one member never holds up the chunks the others ask for.
@property (nonatomic, strong) dispatch_queue_t wholeFileQueue;
@property (nonatomic, strong) OCTNgcUploadPace *uploadPace;
// KHANDAQ (0.2.57): the pace of the relays (kOCTNgcRelayedUploadBytesPerSecond), which a packet to a member over a
// relay waits for too. Its lock is taken inside the lock of uploadPace, never the other way round.
@property (nonatomic, strong) OCTNgcUploadPace *relayPace;
// The monotonic clock of the pace, in milliseconds, and how a paced send waits. Tests give their own.
@property (atomic, copy) uint64_t (^nowMsBlock)(void);
@property (atomic, copy) void (^sleepMsBlock)(uint64_t ms);
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
    _completedAssemblyKeys = [NSMutableDictionary new];
    _orphanChunks = [NSMutableDictionary new];
    _cancelledMsgIdHexes = [NSMutableSet set];
    _sentChunkedFiles = [NSMutableDictionary new];
    _sentFileLookups = [NSMutableDictionary new];
    _sendQueue = dispatch_queue_create("ngc-group-file-send", DISPATCH_QUEUE_SERIAL);
    _uploadQueue = dispatch_queue_create("ngc-group-file-upload", DISPATCH_QUEUE_SERIAL);
    _resendQueue = dispatch_queue_create("ngc-group-file-resend", DISPATCH_QUEUE_SERIAL);
    _wholeFileQueue = dispatch_queue_create("ngc-group-file-whole", DISPATCH_QUEUE_SERIAL);
    _uploadPace = [[OCTNgcUploadPace alloc] initWithBytesPerSecond:kOCTNgcUploadBytesPerSecond burstMs:kOCTNgcUploadBurstMs];
    _relayPace = [[OCTNgcUploadPace alloc] initWithBytesPerSecond:kOCTNgcRelayedUploadBytesPerSecond
                                                          burstMs:kOCTNgcUploadBurstMs];
    _nowMsBlock = ^uint64_t {
        return clock_gettime_nsec_np(CLOCK_UPTIME_RAW) / NSEC_PER_MSEC;
    };
    _sleepMsBlock = ^(uint64_t ms) {
        usleep((useconds_t)(ms * 1000));
    };

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

        // KHANDAQ (0.2.57): the bytes of a file still coming are in its hidden file; nothing is under its name.
        if (assembly.partialPath.length > 0) {
            [[NSFileManager defaultManager] removeItemAtPath:assembly.partialPath error:nil];
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

- (NSSet<NSString *> *)incomingFilePathsInProgress
{
    NSMutableSet<NSString *> *paths = [NSMutableSet new];

    @synchronized (self.assemblies) {
        for (OCTNgcIncomingAssembly *assembly in self.assemblies.allValues) {
            if (assembly.outPath.length > 0) {
                [paths addObject:assembly.outPath];
            }
            if (assembly.partialPath.length > 0) {
                [paths addObject:assembly.partialPath];
            }
        }
    }

    return paths;
}

+ (NSString *)partialPathForGroupFilePath:(NSString *)filePath msgIdHex:(NSString *)msgIdHex
{
    NSString *name = [NSString stringWithFormat:@".%@.part", msgIdHex.lowercaseString];
    return [[filePath stringByDeletingLastPathComponent] stringByAppendingPathComponent:name];
}

/**
 * KHANDAQ (0.2.57): the names, in lower case, a file that comes now cannot take: those the messages of the group give
 * their files (namesInUse), and those of the files still coming, which are not on disk before their last chunk.
 */
- (NSSet<NSString *> *)namesTakenWithNamesInUse:(NSSet<NSString *> *)namesInUse
{
    NSMutableSet<NSString *> *taken = namesInUse ? [namesInUse mutableCopy] : [NSMutableSet new];

    @synchronized (self.assemblies) {
        for (OCTNgcIncomingAssembly *assembly in self.assemblies.allValues) {
            if (assembly.outPath.length > 0) {
                [taken addObject:[[assembly.outPath lastPathComponent] lowercaseString]];
            }
        }
    }

    return taken;
}

- (void)cancelSendForMsgIdHex:(NSString *)msgIdHex
{
    if (msgIdHex.length == 0) {
        return;
    }

    // KHANDAQ (0.2.57): at once: the file may be waiting for the pace on another queue, and stops at its next packet.
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
    NSSet<NSString *> *namesInUse = nil;

    if (pkt == kOCTNgcPktFileSingle || pkt == kOCTNgcPktFileBegin) {
        directory = self.incomingFilesDirectoryBlock(groupNumber) ?: @"";
        NSSet<NSString *> *(^namesInUseBlock)(uint32_t) = self.incomingFileNamesInUseBlock;
        namesInUse = namesInUseBlock ? namesInUseBlock(groupNumber) : nil;
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
                                                directory:directory
                                               namesInUse:namesInUse];
                break;
            case kOCTNgcPktFileBegin:
                [self handleIncomingBeginWithGroupNumber:groupNumber
                                                  peerId:peerId
                                        peerPublicKeyHex:peerPublicKeyHex
                                                    data:data
                                               directory:directory
                                              namesInUse:namesInUse];
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
                                  msgIdHex:msgIdHex
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
                    msgIdHex:(NSString *)msgIdHex
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

    return [self sendPacedPacket:packet groupNumber:groupNumber msgIdHex:msgIdHex error:error];
}

- (BOOL)sendChunkedFileAtPath:(NSString *)filePath
                  groupNumber:(uint32_t)groupNumber
                        msgId:(NSData *)msgId
                     fileSize:(uint64_t)fileSize
                     fileName:(NSString *)fileName
                     progress:(void (^)(float))progressBlock
                        error:(NSError **)error
{
    NSString *msgIdHex = [OCTTox binToHexString:msgId.bytes length:msgId.length];

    // KHANDAQ (#15): remember this send so we can re-serve chunks a receiver NACKs for.
    OCTNgcSentChunkedFile *sentContext = [self sentContextOfFileAtPath:filePath
                                                          groupNumber:groupNumber
                                                                msgId:msgId
                                                             fileSize:fileSize
                                                             fileName:fileName];
    const int totalChunks = sentContext.totalChunks;
    NSData *beginPacket = sentContext.beginPacket;
    sentContext.uploadUnderWay = YES;
    BOOL (^endUpload)(BOOL) = ^BOOL (BOOL sent) {
        @synchronized (sentContext) {
            sentContext.uploadUnderWay = NO;
        }
        return sent;
    };
    [self keepSentContext:sentContext msgIdHex:msgIdHex];

    if (! [self sendPacedPacket:beginPacket groupNumber:groupNumber msgIdHex:msgIdHex error:error]) {
        return endUpload(NO);
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForReadingAtPath:filePath];

    if (! handle) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorMissingFile reason:@"Cannot read file"];
        return endUpload(NO);
    }

    for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
        uint64_t offset = (uint64_t)chunkIndex * kOCTNgcChunkPayloadMax;
        NSUInteger toRead = (NSUInteger)MIN(kOCTNgcChunkPayloadMax, fileSize - offset);
        [handle seekToFileOffset:offset];
        NSData *payload = [handle readDataOfLength:toRead];

        if (payload.length != toRead) {
            [handle closeFile];
            [self fillError:error code:OCTNgcGroupFileTransferErrorMissingFile reason:@"Unexpected EOF"];
            return endUpload(NO);
        }

        NSMutableData *chunkPacket = [NSMutableData dataWithCapacity:kOCTNgcFileChunkHeader + payload.length];
        [self appendMagicToData:chunkPacket];
        [chunkPacket appendBytes:&(uint8_t){kOCTNgcPktFileChunk} length:1];
        [self appendMsgId:msgId toData:chunkPacket];
        [self appendU32BE:(uint32_t)chunkIndex toData:chunkPacket];
        [self appendU32BE:(uint32_t)payload.length toData:chunkPacket];
        [chunkPacket appendData:payload];

        // KHANDAQ (0.2.57): at the pace, so a member's send array never fills.
        if (! [self sendPacedPacket:chunkPacket groupNumber:groupNumber msgIdHex:msgIdHex error:error]) {
            [handle closeFile];
            return endUpload(NO);
        }
        @synchronized (sentContext) {
            sentContext.chunksHandedOver = (NSUInteger)chunkIndex + 1;
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
    }

    [handle closeFile];
    return endUpload(YES);
}

/**
 * KHANDAQ (0.2.57): a packet of the group file `msgIdHex` to its group, once the pace lets it go (OCTNgcUploadPace),
 * and the pace of the relays too while a member of the group is over a relay (anyMemberRelayedBlock).
 * NO, with the error "cancelled", once that file is cancelled, while it waits too.
 */
- (BOOL)sendPacedPacket:(NSData *)packet groupNumber:(uint32_t)groupNumber msgIdHex:(NSString *)msgIdHex error:(NSError **)error
{
    BOOL (^anyMemberRelayedBlock)(uint32_t) = self.anyMemberRelayedBlock;
    const BOOL relayed = anyMemberRelayedBlock ? anyMemberRelayedBlock(groupNumber) : NO;
    [self logRelayedPacket:packet relayed:relayed groupNumber:groupNumber toPeerId:nil msgIdHex:msgIdHex];

    if (! [self awaitPaceForMsgIdHex:msgIdHex relayed:relayed]) {
        [self fillError:error code:OCTNgcGroupFileTransferErrorCancelled reason:@"Transfer cancelled"];
        return NO;
    }

    if (! [self sendPacket:packet groupNumber:groupNumber error:error]) {
        return NO;
    }

    NSUInteger (^memberCountBlock)(uint32_t) = self.memberCountBlock;
    const NSUInteger members = memberCountBlock ? memberCountBlock(groupNumber) : 1;
    [self spendPacedPacket:packet members:members relayed:relayed];

    return YES;
}

/**
 * KHANDAQ (0.2.57): as sendPacedPacket:groupNumber:msgIdHex:error:, to the member `peerId` alone (sendPrivatePacketBlock),
 * which the pace counts as one member. It waits for the pace of the relays only when that member is over a relay
 * (memberRelayedBlock), whatever the other members are. NO once the file is cancelled, or when the packet did not go.
 */
- (BOOL)sendPacedPacket:(NSData *)packet groupNumber:(uint32_t)groupNumber toPeerId:(uint32_t)peerId msgIdHex:(NSString *)msgIdHex
{
    OCTNgcGroupFileTransferSendPrivatePacketBlock sendPrivatePacketBlock = self.sendPrivatePacketBlock;

    if (! sendPrivatePacketBlock) {
        return NO;
    }

    BOOL (^memberRelayedBlock)(uint32_t, uint32_t) = self.memberRelayedBlock;
    const BOOL relayed = memberRelayedBlock ? memberRelayedBlock(groupNumber, peerId) : NO;
    [self logRelayedPacket:packet relayed:relayed groupNumber:groupNumber toPeerId:@(peerId) msgIdHex:msgIdHex];

    if (! [self awaitPaceForMsgIdHex:msgIdHex relayed:relayed]) {
        return NO;
    }

    NSError *error = nil;
    if (! sendPrivatePacketBlock(groupNumber, peerId, packet, &error)) {
        OCTLogWarn(@"NGC group file: a packet of %@ did not go to peer %u: %@", OCTNgcShortMsgId(msgIdHex), peerId, error);
        return NO;
    }

    [self spendPacedPacket:packet members:1 relayed:relayed];

    return YES;
}

/// KHANDAQ (0.2.57): the packet went to `members` members, over a relay to at least one of them when `relayed`.
- (void)spendPacedPacket:(NSData *)packet members:(NSUInteger)members relayed:(BOOL)relayed
{
    [self.uploadPace spend:[OCTNgcUploadPace costOfPacketBytes:packet.length members:members]];

    if (relayed) {
        [self.relayPace spend:packet.length];
    }
}

/// KHANDAQ (0.2.57): a line for the first packet of a file (its BEGIN, or the file in one packet) that waits for the
/// pace of the relays, so a log tells which pace a file went at.
- (void)logRelayedPacket:(NSData *)packet
                 relayed:(BOOL)relayed
             groupNumber:(uint32_t)groupNumber
                toPeerId:(nullable NSNumber *)peerId
                msgIdHex:(NSString *)msgIdHex
{
    const uint8_t type = packet.length > 7 ? ((const uint8_t *)packet.bytes)[7] : 0;

    if (! relayed || (type != kOCTNgcPktFileBegin && type != kOCTNgcPktFileSingle)) {
        return;
    }

    if (peerId) {
        OCTLogInfo(@"NGC group file %@ goes to peer %@ of group %u at the pace of the relays: it is over a relay",
                   OCTNgcShortMsgId(msgIdHex), peerId, groupNumber);
    }
    else {
        OCTLogInfo(@"NGC group file %@ goes to group %u at the pace of the relays: a member is over a relay",
                   OCTNgcShortMsgId(msgIdHex), groupNumber);
    }
}

/// Waits until the pace lets a packet go, and the pace of the relays too when `relayed`. NO once the file is cancelled.
- (BOOL)awaitPaceForMsgIdHex:(NSString *)msgIdHex relayed:(BOOL)relayed
{
    while (YES) {
        if ([self isSendCancelledForMsgIdHex:msgIdHex]) {
            return NO;
        }

        uint64_t waitMs = 0;
        @synchronized (self.uploadPace) {
            @synchronized (self.relayPace) {
                const uint64_t now = self.nowMsBlock();
                const BOOL ready = [self.uploadPace readyAtMs:now];
                const BOOL relayReady = ! relayed || [self.relayPace readyAtMs:now];

                if (ready && relayReady) {
                    return YES;
                }

                waitMs = MAX(ready ? 0 : [self.uploadPace msUntilReadyAtMs:now],
                             relayReady ? 0 : [self.relayPace msUntilReadyAtMs:now]);
            }
        }

        self.sleepMsBlock(MAX((uint64_t)1, MIN(waitMs, kOCTNgcUploadLongestSleepMs)));
    }
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
                                  directory:(NSString *)directory
                                 namesInUse:(NSSet<NSString *> *)namesInUse
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

    // KHANDAQ (0.2.57): never a hidden name, never the name of a file a message of the group still names, nor of one
    // still coming.
    NSString *localName = [OCTFileTools createNewGroupFilePathInDirectory:directory
                                                                 fileName:fileName
                                                               namesInUse:[self namesTakenWithNamesInUse:namesInUse]];
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
                                namesInUse:(NSSet<NSString *> *)namesInUse
{
    if (data.length < kOCTNgcFileBeginHeader) {
        return;
    }

    NSData *msgId = [data subdataWithRange:NSMakeRange(8, 32)];
    NSString *msgIdHex = [OCTTox binToHexString:msgId.bytes length:msgId.length];
    NSString *assemblyKey = [self assemblyKeyForGroupNumber:groupNumber msgId:msgId];

    // KHANDAQ (0.2.57): a BEGIN of a file that comes now, or came lately, is its sender's again (Android sends one with
    // each resend it serves): the assembly stays as it is, and a file complete here is not taken a second time.
    NSString *repeated = nil;
    @synchronized (self.assemblies) {
        OCTNgcIncomingAssembly *coming = self.assemblies[assemblyKey];

        if (coming) {
            repeated = [NSString stringWithFormat:@"while it comes (%d of %d chunks)", coming.receivedCount, coming.totalChunks];
        }
        else if ([self isAssemblyKeyCompletedLately:assemblyKey atMs:self.nowMsBlock()]) {
            repeated = @"complete here already";
        }
    }

    if (repeated) {
        OCTLogInfo(@"NGC group file: BEGIN of %@ again from peer %u, %@: ignored", OCTNgcShortMsgId(msgIdHex), peerId, repeated);
        return;
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

    // KHANDAQ (0.2.57): never a hidden name, never the name of a file a message of the group still names, nor of one
    // still coming.
    NSString *outPath = [OCTFileTools createNewGroupFilePathInDirectory:directory
                                                               fileName:fileName
                                                             namesInUse:[self namesTakenWithNamesInUse:namesInUse]];
    // KHANDAQ (0.2.57): the chunks go to a hidden file next to it, which takes the name once the last one is in. The
    // file was made under its name, as long as the whole file: zeros where nothing had come yet, under the real name
    // while it came (seen: 3 MiB of zeros as g-a.bin), and a start of the app took one that stopped for complete.
    NSString *partialPath = [[self class] partialPathForGroupFilePath:outPath msgIdHex:msgIdHex];

    if (! [[NSFileManager defaultManager] createFileAtPath:partialPath contents:nil attributes:nil]) {
        return;
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:partialPath];

    if (! handle) {
        // KHANDAQ (audit A38): the empty file already exists — don't leave it behind in the group's
        // incoming directory when the assembly never starts.
        [[NSFileManager defaultManager] removeItemAtPath:partialPath error:nil];
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
    assembly.partialPath = partialPath;
    assembly.totalSize = totalSize;
    assembly.totalChunks = (int)totalChunks;
    assembly.chunkPayload = (int)chunkPayload;
    assembly.msgId = [msgId mutableCopy];
    assembly.groupNumber = groupNumber;
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
    BOOL completedLately = NO;

    @synchronized (self.assemblies) {
        assembly = self.assemblies[assemblyKey];

        if (! assembly) {
            completedLately = [self isAssemblyKeyCompletedLately:assemblyKey atMs:self.nowMsBlock()];
        }
    }

    // KHANDAQ (0.2.57): a chunk of a file complete here already goes with the BEGIN that came again: no BEGIN opens
    // that file now, and kept as an orphan the chunk would only push out those of a file still to come.
    if (completedLately) {
        return;
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

    NSFileHandle *handle = [NSFileHandle fileHandleForWritingAtPath:assembly.partialPath];

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
        // KHANDAQ (0.2.57): the file takes its name now, and a file it ends under is never a partial one.
        if (! [self moveCompleteAssemblyToItsName:assembly]) {
            [self discardIncomingAssembly:assembly forKey:assemblyKey];
            return;
        }

        [self stopNackTimerForAssembly:assembly];

        @synchronized (self.assemblies) {
            [self.assemblies removeObjectForKey:assemblyKey];
            [self rememberCompletedAssemblyKey:assemblyKey atMs:self.nowMsBlock()];
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

/**
 * KHANDAQ (0.2.57): the bytes of a complete file leave their hidden file for the file's name. A file that came to have
 * that name meanwhile stays, and this one takes another name in the folder (outPath says which).
 */
- (BOOL)moveCompleteAssemblyToItsName:(OCTNgcIncomingAssembly *)assembly
{
    NSFileManager *fileManager = [NSFileManager defaultManager];

    if ([fileManager fileExistsAtPath:assembly.outPath]) {
        NSString *taken = assembly.outPath;
        assembly.outPath = [OCTFileTools createNewGroupFilePathInDirectory:[taken stringByDeletingLastPathComponent]
                                                                  fileName:assembly.filename
                                                                namesInUse:[self namesTakenWithNamesInUse:nil]];
        assembly.filename = [assembly.outPath lastPathComponent];
        OCTLogInfo(@"NGC group file: %@ is another file now, this one goes to %@", taken, assembly.outPath);
    }

    NSError *error = nil;
    if (! [fileManager moveItemAtPath:assembly.partialPath toPath:assembly.outPath error:&error]) {
        OCTLogWarn(@"NGC group file: cannot move %@ to %@: %@", assembly.partialPath, assembly.outPath, error);
        return NO;
    }

    return YES;
}

// KHANDAQ (audit #13 / A38): the single teardown path for an incoming assembly that will never complete.
// Stops the NACK timer (it retains the assembly, so leaving it running leaks both and keeps broadcasting
// requests), drops the assembly and any orphan chunks queued under its key, and removes the partial file.
// Never call it for a COMPLETED assembly: its file under outPath is the one handed to incomingCompleteBlock.
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

    // KHANDAQ (0.2.57): its bytes are in the hidden file alone; nothing of it is under its name.
    if (assembly.partialPath.length > 0) {
        [[NSFileManager defaultManager] removeItemAtPath:assembly.partialPath error:nil];
    }

    // KHANDAQ (0.2.57): the file's card said it was coming, and nothing ended it until the next start of the
    // app: the sender does not send this file again, and a BEGIN of it would open a new assembly anyway.
    OCTNgcGroupFileTransferIncomingDroppedBlock droppedBlock = self.incomingDroppedBlock;
    if (droppedBlock && assembly.msgId.length > 0) {
        uint32_t groupNumber = assembly.groupNumber;
        NSString *senderPublicKeyHex = assembly.originPublicKeyHex;
        NSString *msgIdHex = [OCTTox binToHexString:assembly.msgId.bytes length:assembly.msgId.length];
        dispatch_async(dispatch_get_main_queue(), ^{
            droppedBlock(groupNumber, senderPublicKeyHex, msgIdHex);
        });
    }
}

// KHANDAQ (0.2.57): whether the file of `assemblyKey` was complete here within kOCTNgcCompletedAssemblyMemoryMs of
// `nowMs`. With self.assemblies locked, as the next one.
- (BOOL)isAssemblyKeyCompletedLately:(NSString *)assemblyKey atMs:(uint64_t)nowMs
{
    NSNumber *completedAt = self.completedAssemblyKeys[assemblyKey];

    if (! completedAt) {
        return NO;
    }

    const uint64_t at = completedAt.unsignedLongLongValue;

    if (nowMs > at && nowMs - at > kOCTNgcCompletedAssemblyMemoryMs) {
        [self.completedAssemblyKeys removeObjectForKey:assemblyKey];
        return NO;
    }

    return YES;
}

// KHANDAQ (0.2.57): the file of `assemblyKey` is complete at `nowMs`. Beyond kOCTNgcMaxCompletedAssemblies the files
// remembered past their time go, then the one complete first.
- (void)rememberCompletedAssemblyKey:(NSString *)assemblyKey atMs:(uint64_t)nowMs
{
    self.completedAssemblyKeys[assemblyKey] = @(nowMs);

    if (self.completedAssemblyKeys.count <= kOCTNgcMaxCompletedAssemblies) {
        return;
    }

    NSMutableArray<NSString *> *expired = [NSMutableArray new];
    NSString *oldestKey = nil;
    uint64_t oldestAt = UINT64_MAX;

    for (NSString *key in self.completedAssemblyKeys) {
        const uint64_t at = self.completedAssemblyKeys[key].unsignedLongLongValue;

        if (nowMs > at && nowMs - at > kOCTNgcCompletedAssemblyMemoryMs) {
            [expired addObject:key];
        }
        else if (at < oldestAt) {
            oldestKey = key;
            oldestAt = at;
        }
    }

    [self.completedAssemblyKeys removeObjectsForKeys:expired];

    if (self.completedAssemblyKeys.count > kOCTNgcMaxCompletedAssemblies && oldestKey) {
        [self.completedAssemblyKeys removeObjectForKey:oldestKey];
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
    NSString *msgIdHex = [[OCTTox binToHexString:msgId.bytes length:msgId.length] lowercaseString];

    OCTNgcSentChunkedFile *context = nil;
    @synchronized (self.sentChunkedFiles) {
        context = self.sentChunkedFiles[msgIdHex];
    }

    if (! context) {
        // KHANDAQ (0.2.60): not a file of ours, whose sender answers, or one of ours this transfer no longer holds (a
        // restart, or more than it keeps): the app says, and the request waits for it.
        [self lookUpSentFileWithGroupNumber:groupNumber msgId:msgId msgIdHex:msgIdHex peerId:peerId data:data];
        return;
    }

    // KHANDAQ (0.2.57): the count 0 and nothing after it asks for the whole file. Android 0.2.57 asks so when it has the
    // file's message and no assembly of it (no BEGIN came, or the assembly was dropped). It was read here as a request
    // for no chunk, so such a member never got the file from an iPhone.
    if (data.length == kOCTNgcFileRequestHeader && [self readU32BEFromData:data offset:40] == 0) {
        [self takeWholeFileRequestOfContext:context msgIdHex:msgIdHex peerId:peerId];
        return;
    }

    // Duplicates are free to send and expensive to serve: 256 copies of index 0 used to mean 256
    // full chunk broadcasts. An index set collapses them, and keeps the result ordered, which is
    // also what the receiver's assembler prefers.
    NSIndexSet *wanted = [self chunksWantedByRequest:data totalChunks:context.totalChunks];
    if (wanted.count == 0) {
        return;
    }

    // KHANDAQ (0.2.57): the chunks the member the whole file goes to now asks for go with it, to it alone, lowest first.
    // The chunks the resend has not reached are in it already, and one it sent and the member still lacks goes again.
    BOOL joinedWholeFile = NO;
    @synchronized (context) {
        if (context.wholeFileUnderWay && context.wholeFilePeerId == peerId) {
            [context.wholeFilePending addIndexes:wanted];
            joinedWholeFile = YES;
        }
    }

    if (joinedWholeFile) {
        return;
    }

    // KHANDAQ (0.2.57): the chunks go at the pace on resendQueue, never on sendQueue, which carries what comes in. A
    // request that comes while they go adds to them: each chunk asked for goes once more, however often it is asked
    // for, and the work a file can make is its count of chunks.
    //
    // No request is dropped for coming soon after another one. The audit of 2026-08-20 allowed one request per member
    // and file in 2 s, when each request had its chunks broadcast again at once. That limit also dropped all packets but
    // the first of a request for more than 256 chunks, which Android 0.2.57 sends together; Android dropped its own
    // limit for that reason. The load stays bounded without it: the requests for a file merge into one set, one resend
    // of a file runs at a time, on one queue for all files, and the pace caps what this device sends. One request could
    // already name 256 chunks, more than the pace sends in 2 s.
    BOOL startResend = NO;
    @synchronized (context) {
        if (! context.pendingResends) {
            context.pendingResends = [NSMutableIndexSet indexSet];
        }
        [context.pendingResends addIndexes:wanted];

        if (! context.resendUnderWay) {
            context.resendUnderWay = YES;
            startResend = YES;
        }
    }

    if (startResend) {
        dispatch_async(self.resendQueue, ^{
            [self resendPendingChunksOfContext:context];
        });
    }
}

#pragma mark - KHANDAQ (0.2.60): files of ours from the app

/// What this transfer keeps of a chunked file it sends: its BEGIN (the create timestamp is 0, so it is the same one
/// whenever it is made) and what the requests of members need of it.
- (OCTNgcSentChunkedFile *)sentContextOfFileAtPath:(NSString *)filePath
                                       groupNumber:(uint32_t)groupNumber
                                             msgId:(NSData *)msgId
                                          fileSize:(uint64_t)fileSize
                                          fileName:(NSString *)fileName
{
    const int totalChunks = (int)((fileSize + kOCTNgcChunkPayloadMax - 1) / kOCTNgcChunkPayloadMax);

    NSMutableData *beginPacket = [NSMutableData dataWithCapacity:kOCTNgcFileBeginHeader];
    [self appendMagicToData:beginPacket];
    [beginPacket appendBytes:&(uint8_t){kOCTNgcPktFileBegin} length:1];
    [self appendMsgId:msgId toData:beginPacket];
    [self appendCreateTimestampToData:beginPacket];
    [self appendFilename:fileName toData:beginPacket];
    [self appendU64BE:fileSize toData:beginPacket];
    [self appendU32BE:(uint32_t)kOCTNgcChunkPayloadMax toData:beginPacket];
    [self appendU32BE:(uint32_t)totalChunks toData:beginPacket];

    OCTNgcSentChunkedFile *context = [OCTNgcSentChunkedFile new];
    context.filePath = filePath;
    context.msgId = msgId;
    context.fileSize = fileSize;
    context.totalChunks = totalChunks;
    context.groupNumber = groupNumber;
    context.beginPacket = beginPacket;
    return context;
}

- (void)keepSentContext:(OCTNgcSentChunkedFile *)context msgIdHex:(NSString *)msgIdHex
{
    @synchronized (self.sentChunkedFiles) {
        self.sentChunkedFiles[msgIdHex.lowercaseString] = context;
        while (self.sentChunkedFiles.count > 24) {
            [self.sentChunkedFiles removeObjectForKey:self.sentChunkedFiles.allKeys.firstObject];
        }
    }
}

/// A request of `peerId` for `msgIdHex`, a file this transfer does not hold: whether to ask the app (Ask), to wait for
/// the answer it is asked for already (Wait: the request waits with it), or to drop it (a file not ours, lately).
- (OCTNgcSentFileLookupStep)sentFileLookupStepForMsgIdHex:(NSString *)msgIdHex
                                                   peerId:(uint32_t)peerId
                                                     data:(NSData *)data
                                                    nowMs:(uint64_t)nowMs
{
    NSArray *request = @[@(peerId), data];

    @synchronized (self.sentFileLookups) {
        OCTNgcSentFileLookup *lookup = self.sentFileLookups[msgIdHex];

        if (lookup) {
            if (lookup.asked && nowMs - lookup.sinceMs < kOCTNgcSentFileLookupAnswerWaitMs) {
                // The newest request of each member waits: it names what that member misses now.
                for (NSUInteger i = 0; i < lookup.requests.count; i++) {
                    if ([lookup.requests[i][0] unsignedIntValue] == peerId) {
                        lookup.requests[i] = request;
                        return OCTNgcSentFileLookupStepWait;
                    }
                }
                if (lookup.requests.count < kOCTNgcSentFileLookupMaxRequests) {
                    [lookup.requests addObject:request];
                }
                return OCTNgcSentFileLookupStepWait;
            }
            if (! lookup.asked && nowMs - lookup.sinceMs < kOCTNgcSentFileLookupNotOursMs) {
                return OCTNgcSentFileLookupStepDrop;
            }
            [self.sentFileLookups removeObjectForKey:msgIdHex];
        }

        while (self.sentFileLookups.count >= kOCTNgcSentFileLookupMaxKept) {
            NSString *oldestKey = nil;
            uint64_t oldestOrder = UINT64_MAX;
            for (NSString *key in self.sentFileLookups) {
                if (self.sentFileLookups[key].order < oldestOrder) {
                    oldestOrder = self.sentFileLookups[key].order;
                    oldestKey = key;
                }
            }
            [self.sentFileLookups removeObjectForKey:oldestKey];
        }

        lookup = [OCTNgcSentFileLookup new];
        lookup.asked = YES;
        lookup.sinceMs = nowMs;
        lookup.order = ++self.sentFileLookupsMade;
        lookup.requests = [NSMutableArray arrayWithObject:request];
        self.sentFileLookups[msgIdHex] = lookup;
        return OCTNgcSentFileLookupStepAsk;
    }
}

/// The app found `msgIdHex` ours: the requests that waited for it, each a peer id and its packet.
- (NSArray<NSArray *> *)takeSentFileLookupRequestsForMsgIdHex:(NSString *)msgIdHex
{
    @synchronized (self.sentFileLookups) {
        NSArray<NSArray *> *requests = [self.sentFileLookups[msgIdHex].requests copy] ?: @[];
        [self.sentFileLookups removeObjectForKey:msgIdHex];
        return requests;
    }
}

/// The app keeps no file of ours with `msgIdHex` that may go again: its requests go, and it is dropped a while.
- (void)forgetSentFileLookupForMsgIdHex:(NSString *)msgIdHex nowMs:(uint64_t)nowMs
{
    @synchronized (self.sentFileLookups) {
        OCTNgcSentFileLookup *lookup = self.sentFileLookups[msgIdHex];
        lookup.asked = NO;
        lookup.sinceMs = nowMs;
        [lookup.requests removeAllObjects];
    }
}

/// On sendQueue: a request for a file this transfer does not hold asks the app, on the main thread where the Realm
/// lives, whether it is ours; the answer comes back here (restoreSentFileAtPath:...).
- (void)lookUpSentFileWithGroupNumber:(uint32_t)groupNumber
                                msgId:(NSData *)msgId
                             msgIdHex:(NSString *)msgIdHex
                               peerId:(uint32_t)peerId
                                 data:(NSData *)data
{
    void (^lookupBlock)(uint32_t, NSString *, void (^)(NSString *)) = self.sentFileLookupBlock;

    if (! lookupBlock || [self isSendCancelledForMsgIdHex:msgIdHex]) {
        return;
    }

    if ([self sentFileLookupStepForMsgIdHex:msgIdHex peerId:peerId data:data nowMs:self.nowMsBlock()]
        != OCTNgcSentFileLookupStepAsk) {
        return;
    }

    __weak typeof(self) weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        lookupBlock(groupNumber, msgIdHex, ^(NSString *filePath) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return;
            }

            dispatch_async(self.sendQueue, ^{
                [self restoreSentFileAtPath:filePath groupNumber:groupNumber msgId:msgId msgIdHex:msgIdHex];
            });
        });
    });
}

/// On sendQueue: the answer of the app. A file of ours is kept again, as when it went, and the requests that waited
/// for it are answered.
- (void)restoreSentFileAtPath:(nullable NSString *)filePath
                  groupNumber:(uint32_t)groupNumber
                        msgId:(NSData *)msgId
                     msgIdHex:(NSString *)msgIdHex
{
    NSDictionary *attributes = filePath.length > 0
                               ? [[NSFileManager defaultManager] attributesOfItemAtPath:filePath error:nil]
                               : nil;
    const uint64_t fileSize = [attributes[NSFileSize] unsignedLongLongValue];

    // Only a file that went in chunks: a member gets a file of one packet whole or not at all.
    if (! attributes || fileSize > kOCTNgcMaxFileTransferBytes
        || ! [self.class shouldUseChunkedTransferForFileSize:fileSize]
        || [self isSendCancelledForMsgIdHex:msgIdHex]) {
        [self forgetSentFileLookupForMsgIdHex:msgIdHex nowMs:self.nowMsBlock()];
        return;
    }

    NSArray<NSArray *> *requests = [self takeSentFileLookupRequestsForMsgIdHex:msgIdHex];
    BOOL held = NO;
    @synchronized (self.sentChunkedFiles) {
        held = self.sentChunkedFiles[msgIdHex] != nil;
    }

    if (! held) {
        // As this device named it when it went (sendFileAtPath: takes the name of the file it sends).
        OCTNgcSentChunkedFile *context = [self sentContextOfFileAtPath:filePath
                                                           groupNumber:groupNumber
                                                                 msgId:msgId
                                                              fileSize:fileSize
                                                              fileName:[filePath lastPathComponent]];
        [self keepSentContext:context msgIdHex:msgIdHex];
        OCTLogInfo(@"NGC group file %@ is ours, from the app: the members that ask for it get it again",
                   OCTNgcShortMsgId(msgIdHex));
    }

    for (NSArray *request in requests) {
        [self handleIncomingRequestWithGroupNumber:groupNumber peerId:[request[0] unsignedIntValue] data:request[1]];
    }
}

+ (BOOL)sentFileAtPath:(NSString *)filePath servableWithSize:(uint64_t)fileSize sentAt:(NSTimeInterval)sentAt
{
    BOOL isDirectory = NO;

    if (filePath.length == 0 || fileSize == 0 || sentAt <= 0
        || ! [[NSFileManager defaultManager] fileExistsAtPath:filePath isDirectory:&isDirectory] || isDirectory) {
        return NO;
    }

    NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:filePath error:nil];
    NSDate *modified = attributes[NSFileModificationDate];

    return [attributes[NSFileSize] unsignedLongLongValue] == fileSize && modified != nil
           && modified.timeIntervalSince1970 <= sentAt + kOCTNgcSentFileModifiedSlack;
}

/**
 * KHANDAQ (0.2.57): the chunks a request asks for, of a file of `totalChunks` chunks; empty for a request of no form
 * known here. After the magic, the type and the msg id come one of two forms:
 * - a list, as this client sends it (sendNackForAssembly): a count n of 1 to kOCTNgcNackMaxIndicesPerPacket (u32),
 *   then n chunk indices (u32 each);
 * - a bitmap, as Android sends it: the file's count of chunks (u32), then (count + 7) / 8 bytes, the bit i & 7 of
 *   byte i >> 3 set when chunk i is missing.
 * A bitmap of n chunks is (n + 7) / 8 bytes, never 4 n, so the two never read as each other. Android's request was
 * dropped here as a list too short for its count: an iPhone never sent the chunks an Android member missed. The request
 * for the whole file, the count 0 and nothing after it, asks for no chunk here: it is served apart
 * (takeWholeFileRequestOfContext:msgIdHex:peerId:).
 */
- (NSIndexSet *)chunksWantedByRequest:(NSData *)data totalChunks:(int)totalChunks
{
    NSMutableIndexSet *wanted = [NSMutableIndexSet indexSet];

    if (data.length < kOCTNgcFileRequestHeader || totalChunks < 1) {
        return wanted;
    }

    const uint32_t count = [self readU32BEFromData:data offset:40];

    if (count >= 1 && count <= kOCTNgcNackMaxIndicesPerPacket && data.length >= kOCTNgcFileRequestHeader + (NSUInteger)count * 4) {
        for (uint32_t i = 0; i < count; i++) {
            uint32_t idx = [self readU32BEFromData:data offset:(kOCTNgcFileRequestHeader + i * 4)];
            if (idx < (uint32_t)totalChunks) {
                [wanted addIndex:(NSUInteger)idx];
            }
        }
        return wanted;
    }

    if (count != (uint32_t)totalChunks || data.length < kOCTNgcFileRequestHeader + ((NSUInteger)count + 7) / 8) {
        return wanted;
    }

    const uint8_t *bitmap = (const uint8_t *)data.bytes + kOCTNgcFileRequestHeader;
    for (uint32_t i = 0; i < count; i++) {
        if (bitmap[i >> 3] & (1u << (i & 7))) {
            [wanted addIndex:(NSUInteger)i];
        }
    }

    return wanted;
}

/**
 * KHANDAQ (0.2.57): the chunks of `context` members asked for again, lowest first, at the pace, until none is left.
 * On resendQueue.
 */
- (void)resendPendingChunksOfContext:(OCTNgcSentChunkedFile *)context
{
    // KHANDAQ (audit 2026-08-20): a cancelled send must stay cancelled. A peer asking for chunks of
    // a transfer the user has stopped would otherwise resurrect it — the same rule Android enforces
    // before servicing a resend. Checked again before each chunk, and while one waits for the pace.
    NSString *msgIdHex = [[OCTTox binToHexString:context.msgId.bytes length:context.msgId.length] lowercaseString];
    NSFileHandle *handle = nil;

    while (YES) {
        NSUInteger chunkIndex = NSNotFound;
        @synchronized (context) {
            if (context.uploadUnderWay) {
                const NSUInteger handedOver = context.chunksHandedOver;
                [context.pendingResends removeIndexesInRange:NSMakeRange(handedOver, NSUIntegerMax - handedOver)];
            }
            if (! [self isSendCancelledForMsgIdHex:msgIdHex]) {
                chunkIndex = context.pendingResends.firstIndex;
            }

            if (chunkIndex == NSNotFound) {
                [context.pendingResends removeAllIndexes];
                context.resendUnderWay = NO;
                break;
            }

            [context.pendingResends removeIndex:chunkIndex];
        }

        if (! handle) {
            handle = [NSFileHandle fileHandleForReadingAtPath:context.filePath];
        }
        if (! handle) {
            // The file is gone: nothing of it can go again.
            @synchronized (context) {
                [context.pendingResends removeAllIndexes];
                context.resendUnderWay = NO;
            }
            break;
        }

        NSData *chunkPacket = [self chunkPacketOfContext:context index:chunkIndex handle:handle];
        if (! chunkPacket) {
            continue;
        }

        [self sendPacedPacket:chunkPacket groupNumber:context.groupNumber msgIdHex:msgIdHex error:nil];
    }

    [handle closeFile];
}

/// The packet of chunk `chunkIndex` of the file of `context`, read with `handle`; nil when the file has no such chunk
/// (any more).
- (NSData *)chunkPacketOfContext:(OCTNgcSentChunkedFile *)context index:(NSUInteger)chunkIndex handle:(NSFileHandle *)handle
{
    const uint64_t offset = (uint64_t)chunkIndex * (uint64_t)kOCTNgcChunkPayloadMax;
    if (offset >= context.fileSize) {
        return nil;
    }

    NSUInteger toRead = (NSUInteger)MIN((uint64_t)kOCTNgcChunkPayloadMax, context.fileSize - offset);
    [handle seekToFileOffset:offset];
    NSData *payload = [handle readDataOfLength:toRead];

    if (payload.length != toRead) {
        return nil;
    }

    NSMutableData *chunkPacket = [NSMutableData dataWithCapacity:kOCTNgcFileChunkHeader + payload.length];
    [self appendMagicToData:chunkPacket];
    [chunkPacket appendBytes:&(uint8_t){kOCTNgcPktFileChunk} length:1];
    [self appendMsgId:context.msgId toData:chunkPacket];
    [self appendU32BE:(uint32_t)chunkIndex toData:chunkPacket];
    [self appendU32BE:(uint32_t)payload.length toData:chunkPacket];
    [chunkPacket appendData:payload];
    return chunkPacket;
}

/**
 * KHANDAQ (0.2.57): the request of the member `peerId` for the whole file of `context`. It is taken unless the whole
 * file goes to a member already (one such resend of a file at a time), or a request of this member for it was taken
 * less than kOCTNgcWholeFileRequestThrottleMs ago: Android serves this request so (RESEND_SERVICE_THROTTLE_MS), and
 * asks again every few seconds while it still lacks the file.
 */
- (void)takeWholeFileRequestOfContext:(OCTNgcSentChunkedFile *)context msgIdHex:(NSString *)msgIdHex peerId:(uint32_t)peerId
{
    NSString *file = OCTNgcShortMsgId(msgIdHex);
    NSString *refusal = nil;

    if (! self.sendPrivatePacketBlock) {
        refusal = @"nothing here sends to one member";
    }
    else if ([self isSendCancelledForMsgIdHex:msgIdHex]) {
        refusal = @"its send was cancelled";
    }
    else {
        refusal = [self startWholeFileOfContext:context forPeerId:peerId atMs:self.nowMsBlock()];
    }

    if (refusal) {
        OCTLogInfo(@"NGC group file: peer %u asks for the whole of %@, %@: dropped", peerId, file, refusal);
        return;
    }

    OCTLogInfo(@"NGC group file: peer %u asks for the whole of %@ (%d chunks): it goes to that peer alone",
               peerId, file, context.totalChunks);
    dispatch_async(self.wholeFileQueue, ^{
        [self sendWholeFileOfContext:context msgIdHex:msgIdHex];
    });
}

/// KHANDAQ (0.2.57): marks the whole file of `context` as going to `peerId` and returns nil; or, when it may not go
/// now, returns why.
- (NSString *)startWholeFileOfContext:(OCTNgcSentChunkedFile *)context forPeerId:(uint32_t)peerId atMs:(uint64_t)nowMs
{
    @synchronized (context) {
        if (context.wholeFileUnderWay) {
            return [NSString stringWithFormat:@"it goes to peer %u now", context.wholeFilePeerId];
        }

        NSNumber *takenAt = context.wholeFileTakenAtMs[@(peerId)];
        const uint64_t since = (takenAt && nowMs > takenAt.unsignedLongLongValue) ? nowMs - takenAt.unsignedLongLongValue : 0;

        if (takenAt && since < kOCTNgcWholeFileRequestThrottleMs) {
            return [NSString stringWithFormat:@"its request before was taken %llu ms ago", since];
        }

        if (! context.wholeFileTakenAtMs) {
            context.wholeFileTakenAtMs = [NSMutableDictionary new];
        }

        // The requests taken longer ago than the throttle hold nothing back now.
        for (NSNumber *member in context.wholeFileTakenAtMs.allKeys) {
            const uint64_t at = context.wholeFileTakenAtMs[member].unsignedLongLongValue;
            if (nowMs > at && nowMs - at >= kOCTNgcWholeFileRequestThrottleMs) {
                [context.wholeFileTakenAtMs removeObjectForKey:member];
            }
        }

        context.wholeFileTakenAtMs[@(peerId)] = @(nowMs);
        context.wholeFileUnderWay = YES;
        context.wholeFilePeerId = peerId;
        context.wholeFilePending = [NSMutableIndexSet indexSetWithIndexesInRange:NSMakeRange(0, (NSUInteger)context.totalChunks)];
        return nil;
    }
}

/**
 * KHANDAQ (0.2.57): the whole file of `context` to the member that asked for it (wholeFilePeerId), alone: its BEGIN,
 * then its chunks lowest first, with those the member's requests add meanwhile, at the pace. A chunk the file's own
 * upload has not reached is left to the upload, as for any request. It stops at a cancel and at the first packet that
 * does not go (the member left, or toxcore holds too much for it already): the member asks again while it lacks the
 * file. On wholeFileQueue.
 */
- (void)sendWholeFileOfContext:(OCTNgcSentChunkedFile *)context msgIdHex:(NSString *)msgIdHex
{
    uint32_t peerId = 0;
    @synchronized (context) {
        peerId = context.wholeFilePeerId;
    }

    NSUInteger chunksSent = 0;
    NSFileHandle *handle = nil;
    BOOL stopped = (context.beginPacket.length == 0) ||
                   ! [self sendPacedPacket:context.beginPacket groupNumber:context.groupNumber toPeerId:peerId msgIdHex:msgIdHex];

    while (! stopped) {
        NSUInteger chunkIndex = NSNotFound;
        @synchronized (context) {
            if (context.uploadUnderWay) {
                const NSUInteger handedOver = context.chunksHandedOver;
                [context.wholeFilePending removeIndexesInRange:NSMakeRange(handedOver, NSUIntegerMax - handedOver)];
            }
            chunkIndex = context.wholeFilePending.firstIndex;

            // In the same step as the check: a request of the member that comes after this goes the usual way.
            if (chunkIndex == NSNotFound) {
                context.wholeFileUnderWay = NO;
                break;
            }

            [context.wholeFilePending removeIndex:chunkIndex];
        }

        if (! handle) {
            handle = [NSFileHandle fileHandleForReadingAtPath:context.filePath];
        }
        if (! handle) {
            stopped = YES;
            break;
        }

        NSData *chunkPacket = [self chunkPacketOfContext:context index:chunkIndex handle:handle];
        if (! chunkPacket) {
            continue;
        }

        if (! [self sendPacedPacket:chunkPacket groupNumber:context.groupNumber toPeerId:peerId msgIdHex:msgIdHex]) {
            stopped = YES;
            break;
        }
        chunksSent++;
    }

    [handle closeFile];

    if (stopped) {
        @synchronized (context) {
            [context.wholeFilePending removeAllIndexes];
            context.wholeFileUnderWay = NO;
        }
    }

    OCTLogInfo(@"NGC group file: the whole of %@ to peer %u %@, %lu chunks",
               OCTNgcShortMsgId(msgIdHex), peerId, stopped ? @"stopped" : @"went", (unsigned long)chunksSent);
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
    // KHANDAQ (0.2.57): cut at 255 bytes in the middle of a letter, the name did not read back as UTF-8 and came as
    // "file"; it keeps its extension and its whole characters, as for a file offered to a friend.
    NSData *nameData = OCTToxFileNameBytes(fileName) ?: [NSData data];

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
