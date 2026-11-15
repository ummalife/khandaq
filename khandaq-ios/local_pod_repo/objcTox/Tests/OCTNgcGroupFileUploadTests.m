// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>

#import "OCTNgcGroupFileTransfer.h"
#import "OCTToxConstants.h"

// The chunk payload every Khandaq sender uses: a packet of 37000 bytes, its 48 bytes of header off.
static const NSUInteger kUploadTestsChunkPayload = 37000 - 48;
static const uint32_t kUploadTestsGroupNumber = 6;

/**
 * KHANDAQ (0.2.56): a big file sent from iOS into a group never arrived. iOS sent all its chunks at once,
 * so toxcore's send array of each member filled after some 290 chunks and dropped the rest, and it answered
 * no request for missing chunks from Android, whose layout it did not read. The chunks now go at the
 * desktop's upload pace, and a chunk somebody asks for again goes to that member alone, its BEGIN first,
 * in either layout.
 */
@interface OCTNgcGroupFileUploadTests : XCTestCase

@property (copy, nonatomic) NSString *root;

@end

@implementation OCTNgcGroupFileUploadTests

- (void)setUp
{
    [super setUp];

    self.root = [NSTemporaryDirectory() stringByAppendingPathComponent:[[NSUUID UUID] UUIDString]];
    [[NSFileManager defaultManager] createDirectoryAtPath:self.root withIntermediateDirectories:YES attributes:nil error:nil];
}

- (void)tearDown
{
    [[NSFileManager defaultManager] removeItemAtPath:self.root error:nil];

    [super tearDown];
}

#pragma mark - The pace

- (void)testTheChunksOfABigFileGoAtTheUploadPace
{
    NSMutableDictionary *rig = [self rigWithMembers:@[@1]];
    NSString *path = [self fileOfChunks:20 named:@"pace.bin"];

    NSTimeInterval start = [NSProcessInfo processInfo].systemUptime;
    NSDictionary *result = [self sendFileAtPath:path withRig:rig];
    NSTimeInterval took = [NSProcessInfo processInfo].systemUptime - start;

    XCTAssertEqualObjects(result[@"success"], @YES, @"%@", result[@"error"]);
    XCTAssertEqualObjects([self namesOfPackets:rig[@"broadcasts"]], [self beginAndChunks:20]);
    // The BEGIN and 20 chunks of up to 36952 bytes to one member at 512 KiB/s, a quarter of a second of
    // them at once: 1.09 s at least. They all went in a few milliseconds.
    XCTAssertGreaterThan(took, 1.0, @"the chunks went faster than the pace");
}

- (void)testAChunkCostsThePaceOnceForEveryMemberItGoesTo
{
    NSMutableDictionary *rig = [self rigWithMembers:@[@3]];
    NSString *path = [self fileOfChunks:8 named:@"members.bin"];

    NSTimeInterval start = [NSProcessInfo processInfo].systemUptime;
    NSDictionary *result = [self sendFileAtPath:path withRig:rig];
    NSTimeInterval took = [NSProcessInfo processInfo].systemUptime - start;

    XCTAssertEqualObjects(result[@"success"], @YES, @"%@", result[@"error"]);
    // 8 chunks to three members are 834 KB of upload: 1.23 s at least, against 0.25 s for one member.
    XCTAssertGreaterThan(took, 1.2, @"a chunk to three members cost the pace as one to one member");
}

#pragma mark - Nobody to send to

- (void)testAFileForAGroupWithNobodyElseIsNotSent
{
    NSMutableDictionary *rig = [self rigWithMembers:@[@0]];
    NSString *path = [self fileOfChunks:3 named:@"alone.bin"];
    [rig[@"transfer"] setValue:@0.3 forKey:@"aloneLimit"];

    NSDictionary *result = [self sendFileAtPath:path withRig:rig];

    XCTAssertEqualObjects(result[@"success"], @NO, @"a file that went to nobody was said sent");
    XCTAssertEqual([result[@"error"] code], OCTNgcGroupFileTransferErrorSendFailed);
    XCTAssertEqualObjects([self namesOfPackets:rig[@"broadcasts"]], @[], @"packets went to nobody");
}

- (void)testAFileGoesAgainFromItsBeginWhenSomebodyIsBackInAGroupThatHadNobody
{
    // The BEGIN and two chunks go, the group has nobody for two looks, then somebody is there again.
    NSMutableDictionary *rig = [self rigWithMembers:@[@1, @1, @1, @0, @0, @1]];
    NSString *path = [self fileOfChunks:4 named:@"back.bin"];

    NSDictionary *result = [self sendFileAtPath:path withRig:rig];

    XCTAssertEqualObjects(result[@"success"], @YES, @"%@", result[@"error"]);
    NSArray *expected = @[@"BEGIN", @"chunk 0", @"chunk 1", @"BEGIN", @"chunk 0", @"chunk 1", @"chunk 2", @"chunk 3"];
    XCTAssertEqualObjects([self namesOfPackets:rig[@"broadcasts"]], expected);
}

- (void)testAFileThatWentAgainFromItsBeginTwiceGoesOnAfterTheNextGap
{
    // Chunk 1 finds nobody three times; somebody is back each time after one look (QA-Live 15.11: on a link
    // slower than the pace toxcore drops the member about every minute).
    NSMutableDictionary *rig = [self rigWithMembers:@[@1, @1, @0, @1, @1, @0, @1, @1, @0, @1]];
    NSString *path = [self fileOfChunks:4 named:@"again.bin"];

    NSDictionary *result = [self sendFileAtPath:path withRig:rig];

    XCTAssertEqualObjects(result[@"success"], @YES, @"%@", result[@"error"]);
    NSArray *expected = @[@"BEGIN", @"chunk 0",
                          @"BEGIN", @"chunk 0",
                          @"BEGIN", @"chunk 0",
                          @"BEGIN", @"chunk 1", @"chunk 2", @"chunk 3"];
    XCTAssertEqualObjects([self namesOfPackets:rig[@"broadcasts"]], expected, @"the file started over at every gap");
}

#pragma mark - Chunks asked for again

- (void)testTheChunksAndroidAsksForGoToItAloneTheirBeginFirst
{
    NSMutableDictionary *rig = [self rigWithMembers:@[@2]];
    NSData *msgId = [self sentFileOfChunks:5 withRig:rig];

    // Android: the number of chunks, then a bitmap of the missing ones, least significant bit first.
    uint8_t bitmap = (1u << 1) | (1u << 3);
    [self receiveRequest:[self requestWithMsgId:msgId first:5 body:[NSData dataWithBytes:&bitmap length:1]] fromPeer:7 withRig:rig];

    NSArray *expected = @[@"7: BEGIN", @"7: chunk 1", @"7: chunk 3"];
    [self waitForPrivatePackets:expected.count withRig:rig];
    XCTAssertEqualObjects([self namesOfPrivatePackets:rig], expected);
    XCTAssertEqual([rig[@"broadcasts"] count], 6u, @"a chunk asked for went to the whole group");
}

- (void)testTheChunksAnIOSReceiverAsksForGoToItAloneTheirBeginFirst
{
    NSMutableDictionary *rig = [self rigWithMembers:@[@2]];
    NSData *msgId = [self sentFileOfChunks:5 withRig:rig];

    // iOS: the number of indices, then the indices; a repeated one and one past the file cost nothing.
    NSMutableData *indices = [NSMutableData data];
    for (NSNumber *index in @[@4, @0, @4, @9]) {
        [self appendU32:index.unsignedIntValue toData:indices];
    }
    [self receiveRequest:[self requestWithMsgId:msgId first:4 body:indices] fromPeer:3 withRig:rig];

    NSArray *expected = @[@"3: BEGIN", @"3: chunk 0", @"3: chunk 4"];
    [self waitForPrivatePackets:expected.count withRig:rig];
    XCTAssertEqualObjects([self namesOfPrivatePackets:rig], expected);
    XCTAssertEqual([rig[@"broadcasts"] count], 6u, @"a chunk asked for went to the whole group");
}

- (void)testARequestForTheWholeFileIsServedOnceInFourSeconds
{
    NSMutableDictionary *rig = [self rigWithMembers:@[@2]];
    NSData *msgId = [self sentFileOfChunks:3 withRig:rig];

    // Android asks for the whole file while it has nothing of it: no chunk count, no bitmap.
    [self receiveRequest:[self requestWithMsgId:msgId first:0 body:[NSData data]] fromPeer:5 withRig:rig];
    [self receiveRequest:[self requestWithMsgId:msgId first:0 body:[NSData data]] fromPeer:8 withRig:rig];

    NSArray *expected = @[@"5: BEGIN", @"5: chunk 0", @"5: chunk 1", @"5: chunk 2"];
    [self waitForPrivatePackets:expected.count withRig:rig];
    [self spinFor:0.3];
    XCTAssertEqualObjects([self namesOfPrivatePackets:rig], expected, @"the whole file went twice in four seconds");
}

- (void)testAChunkAskedForGoesOnceTheMembersSendArrayHasRoomAndStopsWhenTheMemberIsGone
{
    NSMutableDictionary *rig = [self rigWithMembers:@[@2]];
    NSData *msgId = [self sentFileOfChunks:5 withRig:rig];

    // Chunk 1 finds the array full three times, then goes; the member is gone when chunk 3 is to go.
    NSError *full = [NSError errorWithDomain:kOCTToxErrorDomain code:OCTToxErrorGroupSendCustomPacketUnknown userInfo:nil];
    NSError *gone = [NSError errorWithDomain:kOCTToxErrorDomain code:OCTToxErrorGroupSendCustomPacketGroupNotFound userInfo:nil];
    rig[@"refusals"] = [@{@"chunk 1": [@[full, full, full] mutableCopy], @"chunk 3": [@[gone] mutableCopy]} mutableCopy];

    uint8_t bitmap = (1u << 1) | (1u << 3) | (1u << 4);
    [self receiveRequest:[self requestWithMsgId:msgId first:5 body:[NSData dataWithBytes:&bitmap length:1]] fromPeer:4 withRig:rig];

    NSArray *expected = @[@"4: BEGIN", @"4: chunk 1"];
    [self waitForPrivatePackets:expected.count withRig:rig];
    [self spinFor:0.3];
    XCTAssertEqualObjects([self namesOfPrivatePackets:rig], expected);
    XCTAssertEqualObjects(rig[@"refused"], (@[@"chunk 1", @"chunk 1", @"chunk 1", @"chunk 3"]));
}

- (void)testAChunkAskedForBeforeItWentIsNotSentAgain
{
    NSMutableDictionary *rig = [self rigWithMembers:@[@1]];
    NSString *path = [self fileOfChunks:30 named:@"early.bin"];
    NSData *msgId = nil;
    __block NSDictionary *result = nil;

    [self startSendingFileAtPath:path withRig:rig msgId:&msgId result:^(NSDictionary *done) {
        result = done;
    }];

    // Asked while the first chunks go: only those that went come again.
    [self waitUntil:^BOOL {
        return [self countOfBroadcastChunksWithRig:rig] >= 3;
    } timeout:5.0];
    NSMutableData *indices = [NSMutableData data];
    for (NSNumber *index in @[@0, @29]) {
        [self appendU32:index.unsignedIntValue toData:indices];
    }
    [self receiveRequest:[self requestWithMsgId:msgId first:2 body:indices] fromPeer:2 withRig:rig];

    [self waitUntil:^BOOL {
        return result != nil;
    } timeout:10.0];
    [self spinFor:0.3];
    XCTAssertEqualObjects(result[@"success"], @YES, @"%@", result[@"error"]);
    XCTAssertEqualObjects([self namesOfPrivatePackets:rig], (@[@"2: BEGIN", @"2: chunk 0"]));
}

#pragma mark - Rig

/**
 * A transfer whose broadcasts go into "broadcasts" and private packets into "privates" ("peer: name"); a
 * private packet named in "refusals" is refused with the errors listed there first, in turn, and noted in
 * "refused". The members of the group are "members" in turn, the last one from then on.
 */
- (NSMutableDictionary *)rigWithMembers:(NSArray<NSNumber *> *)members
{
    NSMutableDictionary *rig = [NSMutableDictionary new];
    NSMutableArray<NSData *> *broadcasts = [NSMutableArray new];
    NSMutableArray<NSString *> *privates = [NSMutableArray new];
    NSMutableArray<NSString *> *refused = [NSMutableArray new];
    NSMutableArray<NSNumber *> *memberScript = [members mutableCopy];
    rig[@"broadcasts"] = broadcasts;
    rig[@"privates"] = privates;
    rig[@"refused"] = refused;
    rig[@"refusals"] = [NSMutableDictionary new];

    __weak NSMutableDictionary *weakRig = rig;

    OCTNgcGroupFileTransfer *transfer = [[OCTNgcGroupFileTransfer alloc] initWithSendPacketBlock:^BOOL (uint32_t groupNumber, NSData *packet, NSError **error) {
        @synchronized (broadcasts) {
            [broadcasts addObject:packet];
        }
        return YES;
    } incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
        return NSTemporaryDirectory();
    } incomingBeginBlock:nil incomingCompleteBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                                                    NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
    } transferProgressBlock:nil];

    transfer.sendPrivatePacketBlock = ^BOOL (uint32_t groupNumber, uint32_t peerId, NSData *packet, NSError **error) {
        NSString *name = [OCTNgcGroupFileUploadTests nameOfPacket:packet];
        NSMutableDictionary *strongRig = weakRig;

        @synchronized (privates) {
            NSMutableArray<NSError *> *refusals = strongRig[@"refusals"][name];

            if (refusals.count > 0) {
                if (error) {
                    *error = refusals.firstObject;
                }
                [refusals removeObjectAtIndex:0];
                [refused addObject:name];
                return NO;
            }

            [privates addObject:[NSString stringWithFormat:@"%u: %@", peerId, name]];
        }
        return YES;
    };
    transfer.memberCountBlock = ^NSUInteger (uint32_t groupNumber) {
        @synchronized (memberScript) {
            NSNumber *count = memberScript.firstObject;

            if (memberScript.count > 1) {
                [memberScript removeObjectAtIndex:0];
            }

            return count.unsignedIntegerValue;
        }
    };

    rig[@"transfer"] = transfer;
    return rig;
}

- (NSString *)fileOfChunks:(NSUInteger)chunks named:(NSString *)name
{
    // The last chunk is a half one.
    NSUInteger length = (chunks - 1) * kUploadTestsChunkPayload + kUploadTestsChunkPayload / 2;
    NSMutableData *data = [NSMutableData dataWithLength:length];
    arc4random_buf(data.mutableBytes, length);

    NSString *path = [self.root stringByAppendingPathComponent:name];
    XCTAssertTrue([data writeToFile:path atomically:YES]);
    return path;
}

- (void)startSendingFileAtPath:(NSString *)path
                       withRig:(NSMutableDictionary *)rig
                         msgId:(NSData **)msgId
                        result:(void (^)(NSDictionary *))resultBlock
{
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    *msgId = [self dataFromHex:msgIdHex];

    [rig[@"transfer"] sendFileAtPath:path groupNumber:kUploadTestsGroupNumber msgIdHex:msgIdHex progress:nil completion:^(BOOL success, NSError *error) {
        resultBlock(@{@"success": @(success), @"error": error ?: [NSNull null]});
    }];
}

- (NSDictionary *)sendFileAtPath:(NSString *)path withRig:(NSMutableDictionary *)rig
{
    NSData *msgId = nil;
    __block NSDictionary *result = nil;

    [self startSendingFileAtPath:path withRig:rig msgId:&msgId result:^(NSDictionary *done) {
        result = done;
    }];
    [self waitUntil:^BOOL {
        return result != nil;
    } timeout:20.0];

    rig[@"msgId"] = msgId;
    return result ?: @{@"success": @"no answer"};
}

/** A file of so many chunks that went whole to the group; its msg id. */
- (NSData *)sentFileOfChunks:(NSUInteger)chunks withRig:(NSMutableDictionary *)rig
{
    NSDictionary *result = [self sendFileAtPath:[self fileOfChunks:chunks named:@"asked.bin"] withRig:rig];
    XCTAssertEqualObjects(result[@"success"], @YES, @"%@", result[@"error"]);
    XCTAssertEqual([rig[@"broadcasts"] count], chunks + 1);
    return rig[@"msgId"];
}

- (NSData *)requestWithMsgId:(NSData *)msgId first:(uint32_t)first body:(NSData *)body
{
    const uint8_t head[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x14};
    NSMutableData *packet = [NSMutableData dataWithBytes:head length:sizeof(head)];
    [packet appendData:msgId];
    [self appendU32:first toData:packet];
    [packet appendData:body];
    return packet;
}

- (void)receiveRequest:(NSData *)request fromPeer:(uint32_t)peerId withRig:(NSMutableDictionary *)rig
{
    [rig[@"transfer"] handleIncomingPacketWithGroupNumber:kUploadTestsGroupNumber peerId:peerId peerPublicKeyHex:nil data:request];
}

- (void)waitForPrivatePackets:(NSUInteger)count withRig:(NSMutableDictionary *)rig
{
    NSMutableArray *privates = rig[@"privates"];

    [self waitUntil:^BOOL {
        @synchronized (privates) {
            return privates.count >= count;
        }
    } timeout:10.0];
}

- (NSArray<NSString *> *)namesOfPrivatePackets:(NSMutableDictionary *)rig
{
    NSMutableArray *privates = rig[@"privates"];

    @synchronized (privates) {
        return [privates copy];
    }
}

- (NSUInteger)countOfBroadcastChunksWithRig:(NSMutableDictionary *)rig
{
    NSUInteger chunks = 0;

    for (NSString *name in [self namesOfPackets:rig[@"broadcasts"]]) {
        if ([name hasPrefix:@"chunk"]) {
            chunks++;
        }
    }

    return chunks;
}

- (NSArray<NSString *> *)namesOfPackets:(NSMutableArray<NSData *> *)packets
{
    NSMutableArray<NSString *> *names = [NSMutableArray new];

    @synchronized (packets) {
        for (NSData *packet in packets) {
            [names addObject:[OCTNgcGroupFileUploadTests nameOfPacket:packet]];
        }
    }

    return names;
}

- (NSArray<NSString *> *)beginAndChunks:(NSUInteger)chunks
{
    NSMutableArray<NSString *> *names = [NSMutableArray arrayWithObject:@"BEGIN"];

    for (NSUInteger i = 0; i < chunks; i++) {
        [names addObject:[NSString stringWithFormat:@"chunk %lu", (unsigned long)i]];
    }

    return names;
}

+ (NSString *)nameOfPacket:(NSData *)packet
{
    const uint8_t *bytes = packet.bytes;

    if (packet.length < 8) {
        return @"short";
    }

    switch (bytes[7]) {
        case 0x11:
            return @"SINGLE";
        case 0x12:
            return @"BEGIN";
        case 0x13: {
            if (packet.length < 44) {
                return @"short chunk";
            }
            uint32_t index = ((uint32_t)bytes[40] << 24) | ((uint32_t)bytes[41] << 16) | ((uint32_t)bytes[42] << 8) | bytes[43];
            return [NSString stringWithFormat:@"chunk %u", index];
        }
        case 0x14:
            return @"REQUEST";
        default:
            return [NSString stringWithFormat:@"packet 0x%02x", bytes[7]];
    }
}

- (void)appendU32:(uint32_t)value toData:(NSMutableData *)data
{
    uint32_t bigEndian = CFSwapInt32HostToBig(value);
    [data appendBytes:&bigEndian length:sizeof(bigEndian)];
}

- (NSData *)dataFromHex:(NSString *)hex
{
    NSMutableData *data = [NSMutableData dataWithCapacity:hex.length / 2];

    for (NSUInteger i = 0; i + 1 < hex.length; i += 2) {
        unsigned int byte = 0;
        [[NSScanner scannerWithString:[hex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&byte];
        uint8_t value = (uint8_t)byte;
        [data appendBytes:&value length:1];
    }

    return data;
}

- (void)waitUntil:(BOOL (^)(void))condition timeout:(NSTimeInterval)timeout
{
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:timeout];

    while (! condition() && [deadline timeIntervalSinceNow] > 0) {
        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
    }
}

- (void)spinFor:(NSTimeInterval)duration
{
    [self waitUntil:^BOOL {
        return NO;
    } timeout:duration];
}

@end
