// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>
#import <OCMock/OCMock.h>
#import <QuartzCore/QuartzCore.h>
#import <Realm/Realm.h>
#import <Realm/RLMRealm_Dynamic.h>
#import <objc/runtime.h>

#import "OCTChat.h"
#import "OCTFriend.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageFile.h"
#import "OCTMessageText.h"
#import "OCTRealmManager.h"
#import "OCTSeenReceipt.h"
#import "OCTSubmanagerChatsImpl.h"
#import "OCTSubmanagerDataSource.h"
#import "OCTSubmanagerFilesImpl.h"
#import "OCTSubmanagerFriendsImpl.h"
#import "OCTTox.h"
#import "OCTTox+Private.h"
#import "OCTToxDelegate.h"

// Realm's own private API, as its private headers declare it (Pods/Headers/Private/Realm, which this target does
// not see): enough to write a profile with the schema that version 42 wrote.
@interface RLMSchema (SeenReceiptTests)

+ (instancetype)sharedSchema;
- (void)setObjectSchema:(NSArray<RLMObjectSchema *> *)objectSchema;

@end

@interface RLMObjectSchema (SeenReceiptTests)

- (void)setProperties:(NSArray<RLMProperty *> *)properties;

@end

@interface RLMRealmConfiguration (SeenReceiptTests)

- (void)setCustomSchema:(RLMSchema *)customSchema;

@end

static const OCTToxFriendNumber kSeenTestsFriendNumber = 5;

/// 64 hex digits of the 32 bytes first, first + 1, and on: an id, or a public key.
static NSString *OCTSeenTestsHex(uint8_t first)
{
    NSMutableString *hex = [NSMutableString stringWithCapacity:64];
    for (NSUInteger i = 0; i < 32; i++) {
        [hex appendFormat:@"%02X", (uint8_t)(first + i)];
    }
    return hex;
}

/// The bytes of "BD 4B 51 ...", as the SPEC writes its test vectors.
static NSData *OCTSeenTestsBytes(NSString *spaced)
{
    NSMutableData *data = [NSMutableData new];
    for (NSString *pair in [spaced componentsSeparatedByString:@" "]) {
        unsigned int value = 0;
        [[NSScanner scannerWithString:pair] scanHexInt:&value];
        uint8_t byte = (uint8_t)value;
        [data appendBytes:&byte length:1];
    }
    return data;
}

static NSString *const kSeenTestsTextVector = @"BD 4B 51 01 01 00 01 02 03 04 05 06 07 08 09 0A 0B 0C 0D 0E 0F 10 11 12 13 14 15 16 17 18 19 1A 1B 1C 1D 1E 1F 65 53 F1 00";
// The file vector: id = 32 x 0xFF, time = 0.
static NSString *const kSeenTestsFileVector = @"BD 4B 51 01 02 FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF FF 00 00 00 00";

/**
 * What OCTManagerImpl gives its submanagers. A plain object, not a mock, so that nothing but the test holds the
 * realm manager it hands out, and a profile the test closes is closed for Realm too.
 */
@interface OCTSeenTestsDataSource : NSObject <OCTSubmanagerDataSource>

@property (strong, nonatomic) OCTTox *tox;
@property (strong, nonatomic) OCTRealmManager *realmManager;
@property (strong, nonatomic) NSNotificationCenter *notificationCenter;
@property (assign, nonatomic) BOOL toxConnected;

@end

@implementation OCTSeenTestsDataSource

- (OCTTox *)managerGetTox
{
    return self.tox;
}

- (BOOL)managerIsToxConnected
{
    return self.toxConnected;
}

- (void)managerSaveTox
{}

- (OCTRealmManager *)managerGetRealmManager
{
    return self.realmManager;
}

- (id<OCTFileStorageProtocol>)managerGetFileStorage
{
    return nil;
}

- (NSNotificationCenter *)managerGetNotificationCenter
{
    return self.notificationCenter;
}

- (BOOL)managerUseFauxOfflineMessaging
{
    return NO;
}

- (id<OCTSubmanagerChats>)managerGetChats
{
    return nil;
}

- (id<OCTSubmanagerFriends>)managerGetFriends
{
    return nil;
}

- (id<OCTSubmanagerFiles>)managerGetFiles
{
    return nil;
}

@end

/**
 * KHANDAQ (0.2.57): read receipts between two people, the "KQ" lossless packet 189 of protocol v1, the same
 * bytes on iOS, Android and the desktop.
 *
 * The packet: both test vectors of the SPEC built and read byte for byte, and anything of another shape
 * dropped. The side that sent the messages: the seen watermark moves only forward, only to an id of our own
 * in the 1:1 chat, and lives in the profile. The side that read them: the newest of their messages with an
 * id is reported once, at most one receipt a second leaves, the later replacing the one that waits, and one
 * for a friend who is offline waits in the profile until they connect; nothing leaves for a group or the
 * Saved chat. And a profile of schema 42 opens with nothing seen and all it had, even after another profile
 * was opened in the same process.
 *
 * Against a real Realm, opened on the main thread as the app opens it; toxcore is a mock.
 */
@interface OCTSeenReceiptTests : XCTestCase

@property (copy, nonatomic) NSString *root;
@property (copy, nonatomic) NSString *friendKey;
@property (strong, nonatomic) OCTRealmManager *realmManager;
@property (strong, nonatomic) NSNotificationCenter *notificationCenter;
@property (strong, nonatomic) id tox;
@property (strong, nonatomic) OCTSeenTestsDataSource *dataSource;
@property (strong, nonatomic) OCTSubmanagerChatsImpl *chats;

/// Whether toxcore takes a lossless packet for the friend; it refuses while they are offline.
@property (assign, nonatomic) BOOL friendReachable;
@property (strong, nonatomic) NSMutableArray<NSData *> *sentPackets;
@property (strong, nonatomic) NSMutableArray<NSNumber *> *sendTimes;

@end

@implementation OCTSeenReceiptTests

- (void)setUp
{
    [super setUp];

    self.root = [NSTemporaryDirectory() stringByAppendingPathComponent:[[NSUUID UUID] UUIDString]];
    [[NSFileManager defaultManager] createDirectoryAtPath:self.root withIntermediateDirectories:YES attributes:nil error:nil];
    self.friendKey = OCTSeenTestsHex(0x80);
    self.sentPackets = [NSMutableArray new];
    self.sendTimes = [NSMutableArray new];

    [self openProfile];
}

- (void)tearDown
{
    [self closeProfile];
    [[NSFileManager defaultManager] removeItemAtPath:self.root error:nil];

    [super tearDown];
}

#pragma mark - The packet

- (void)testTheTextVectorIsBuiltAndReadByteForByte
{
    NSData *vector = OCTSeenTestsBytes(kSeenTestsTextVector);
    XCTAssertEqual(vector.length, kOCTSeenReceiptPacketLength);

    XCTAssertEqualObjects([OCTSeenReceipt packetWithAnchorType:OCTSeenAnchorTypeText
                                                     anchorHex:OCTSeenTestsHex(0x00)
                                                      seenTime:0x6553F100],
                          vector);

    OCTSeenReceipt *receipt = [OCTSeenReceipt receiptWithPacket:vector];
    XCTAssertNotNil(receipt);
    XCTAssertEqual(receipt.anchorType, OCTSeenAnchorTypeText);
    XCTAssertEqualObjects(receipt.anchorHex, OCTSeenTestsHex(0x00));
    XCTAssertEqual(receipt.seenTime, 0x6553F100u);
}

- (void)testTheFileVectorIsBuiltAndReadByteForByte
{
    NSData *vector = OCTSeenTestsBytes(kSeenTestsFileVector);
    XCTAssertEqual(vector.length, kOCTSeenReceiptPacketLength);
    NSString *allOnes = [@"" stringByPaddingToLength:64 withString:@"F" startingAtIndex:0];

    XCTAssertEqualObjects([OCTSeenReceipt packetWithAnchorType:OCTSeenAnchorTypeFile anchorHex:allOnes seenTime:0], vector);
    // A file id is kept in either case; the packet is the same.
    XCTAssertEqualObjects([OCTSeenReceipt packetWithAnchorType:OCTSeenAnchorTypeFile
                                                     anchorHex:[allOnes lowercaseString]
                                                      seenTime:0],
                          vector);

    OCTSeenReceipt *receipt = [OCTSeenReceipt receiptWithPacket:vector];
    XCTAssertNotNil(receipt);
    XCTAssertEqual(receipt.anchorType, OCTSeenAnchorTypeFile);
    XCTAssertEqualObjects(receipt.anchorHex, allOnes);
    XCTAssertEqual(receipt.seenTime, 0u);
}

- (void)testAPacketOfAnyOtherShapeIsDropped
{
    NSData *vector = OCTSeenTestsBytes(kSeenTestsTextVector);

    XCTAssertNil([OCTSeenReceipt receiptWithPacket:[vector subdataWithRange:NSMakeRange(0, 40)]], @"one byte short");
    NSMutableData *longer = [vector mutableCopy];
    uint8_t zero = 0;
    [longer appendBytes:&zero length:1];
    XCTAssertNil([OCTSeenReceipt receiptWithPacket:longer], @"one byte over");
    XCTAssertNil([OCTSeenReceipt receiptWithPacket:[NSData data]]);

    // Offset, then the value put there: another id, magic, version or anchor type.
    NSArray<NSArray<NSNumber *> *> *changes = @[
        @[@0, @0xBC], @[@1, @0x4C], @[@2, @0x52], @[@3, @0], @[@3, @2], @[@4, @0], @[@4, @3],
    ];
    for (NSArray<NSNumber *> *change in changes) {
        NSMutableData *packet = [vector mutableCopy];
        uint8_t value = change[1].unsignedCharValue;
        [packet replaceBytesInRange:NSMakeRange(change[0].unsignedIntegerValue, 1) withBytes:&value];
        XCTAssertNil([OCTSeenReceipt receiptWithPacket:packet], @"byte %@ set to %@", change[0], change[1]);
    }

    // Nothing is built for an anchor of no known type, or an id that is not 32 bytes of hex.
    NSString *anchor = OCTSeenTestsHex(0x00);
    XCTAssertNil([OCTSeenReceipt packetWithAnchorType:(OCTSeenAnchorType)0 anchorHex:anchor seenTime:1]);
    XCTAssertNil([OCTSeenReceipt packetWithAnchorType:(OCTSeenAnchorType)3 anchorHex:anchor seenTime:1]);
    XCTAssertNil([OCTSeenReceipt packetWithAnchorType:OCTSeenAnchorTypeText anchorHex:[anchor substringFromIndex:2] seenTime:1]);
    XCTAssertNil([OCTSeenReceipt packetWithAnchorType:OCTSeenAnchorTypeText anchorHex:[anchor stringByAppendingString:@"00"] seenTime:1]);
    XCTAssertNil([OCTSeenReceipt packetWithAnchorType:OCTSeenAnchorTypeText
                                            anchorHex:[@"0G" stringByAppendingString:[anchor substringFromIndex:2]]
                                             seenTime:1]);
}

- (void)testTheManagerHandsAReceiptToTheChats
{
    // OCTManagerImpl forwards to its submanagers only the optional methods of OCTToxDelegate.
    SEL selector = @selector(tox:friendSeenWithAnchorType:anchorHex:seenTime:friendNumber:);
    struct objc_method_description description = protocol_getMethodDescription(@protocol(OCTToxDelegate), selector, NO, YES);
    XCTAssertTrue(description.name != NULL);
    XCTAssertTrue([OCTSubmanagerChatsImpl instancesRespondToSelector:selector]);
}

#pragma mark - The watermark, where our messages were sent

- (void)testAReceiptMovesTheWatermarkToOurOwnMessageAndNeverBack
{
    OCTFriend *friend = [self addFriendConnected:YES];
    OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];
    OCTMessageAbstract *first = [self addTextInChat:chat from:nil hash:OCTSeenTestsHex(0x10) arrived:100 sent:100];
    OCTMessageAbstract *second = [self addTextInChat:chat from:nil hash:OCTSeenTestsHex(0x20) arrived:200 sent:200];
    OCTMessageAbstract *later = [self addTextInChat:chat from:nil hash:OCTSeenTestsHex(0x40) arrived:400 sent:400];
    OCTMessageAbstract *theirs = [self addTextInChat:chat from:friend hash:OCTSeenTestsHex(0x30) arrived:300 sent:300];

    // An id of theirs, or one nobody here has, names nothing of ours.
    [self receiveSeenOfType:OCTSeenAnchorTypeText anchor:OCTSeenTestsHex(0x30)];
    [self receiveSeenOfType:OCTSeenAnchorTypeText anchor:OCTSeenTestsHex(0x50)];
    XCTAssertEqual(friend.seenWatermark, 0);

    [self receiveSeenOfType:OCTSeenAnchorTypeText anchor:OCTSeenTestsHex(0x20)];
    XCTAssertEqual(friend.seenWatermark, 200);

    // An older receipt that comes late does not take it back.
    [self receiveSeenOfType:OCTSeenAnchorTypeText anchor:OCTSeenTestsHex(0x10)];
    XCTAssertEqual(friend.seenWatermark, 200);

    // What we sent up to it is seen; what we sent after it is not, and nothing of theirs ever is.
    XCTAssertTrue([friend hasSeenOutgoingMessage:first]);
    XCTAssertTrue([friend hasSeenOutgoingMessage:second]);
    XCTAssertFalse([friend hasSeenOutgoingMessage:later]);
    XCTAssertFalse([friend hasSeenOutgoingMessage:theirs]);
}

- (void)testAFileReceiptFindsOurFileByItsToxIdInEitherCase
{
    OCTFriend *friend = [self addFriendConnected:YES];
    OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];
    // A row from before sortTimestamp: it counts by when it was stored.
    OCTMessageAbstract *file = [self addFileInChat:chat from:nil fileIdHex:[OCTSeenTestsHex(0xA0) lowercaseString] arrived:150 sent:0];

    // A text receipt does not name a file.
    [self receiveSeenOfType:OCTSeenAnchorTypeText anchor:OCTSeenTestsHex(0xA0)];
    XCTAssertEqual(friend.seenWatermark, 0);

    [self receiveSeenOfType:OCTSeenAnchorTypeFile anchor:OCTSeenTestsHex(0xA0)];
    XCTAssertEqual(friend.seenWatermark, 150);
    XCTAssertTrue([friend hasSeenOutgoingMessage:file]);
}

- (void)testAReceiptFromAStrangerOrForAGroupMovesNothingAndMakesNoChat
{
    // Nobody under that friend number.
    [self.chats tox:self.tox friendSeenWithAnchorType:OCTSeenAnchorTypeText
                                            anchorHex:OCTSeenTestsHex(0x10)
                                             seenTime:0
                                         friendNumber:9];

    // A friend without a chat: the receipt makes none.
    OCTFriend *friend = [self addFriendConnected:YES];
    [self receiveSeenOfType:OCTSeenAnchorTypeText anchor:OCTSeenTestsHex(0x10)];
    XCTAssertEqual([OCTChat allObjectsInRealm:self.realmManager.realm].count, 0u);

    // Our message in a group they are in: groups have no seen.
    OCTChat *group = [OCTChat new];
    group.isGroup = YES;
    group.groupNumber = 3;
    [group.friends addObject:friend];
    [self.realmManager addObject:group];
    [self addTextInChat:group from:nil hash:OCTSeenTestsHex(0x10) arrived:100 sent:100];
    [self receiveSeenOfType:OCTSeenAnchorTypeText anchor:OCTSeenTestsHex(0x10)];
    XCTAssertEqual(friend.seenWatermark, 0);
}

- (void)testTheWatermarkSurvivesARestartAndGoesWithTheContact
{
    __weak RLMRealm *firstRealm = nil;
    @autoreleasepool {
        OCTFriend *friend = [self addFriendConnected:YES];
        OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];
        [self addTextInChat:chat from:nil hash:OCTSeenTestsHex(0x10) arrived:100 sent:100];
        [self receiveSeenOfType:OCTSeenAnchorTypeText anchor:OCTSeenTestsHex(0x10)];
        XCTAssertEqual(friend.seenWatermark, 100);

        firstRealm = self.realmManager.realm;
        [self closeProfile];
    }
    XCTAssertNil(firstRealm, @"the profile was not closed, so this would not read it from disk");

    [self openProfile];
    OCTFriend *friend = [self.realmManager friendWithPublicKey:self.friendKey];
    XCTAssertEqual(friend.seenWatermark, 100);

    // Removing the contact removes the row, and its watermark with it: added again, they start from nothing.
    OCMStub([self.tox deleteFriendWithFriendNumber:kSeenTestsFriendNumber error:[OCMArg anyObjectRef]]).andReturn(YES);
    OCTSubmanagerFriendsImpl *friends = [OCTSubmanagerFriendsImpl new];
    friends.dataSource = self.dataSource;
    XCTAssertTrue([friends removeFriend:friend error:nil]);
    XCTAssertEqual([self addFriendConnected:YES].seenWatermark, 0);
}

- (void)testAFileThatWaitedForAnOfflineFriendTakesTheIdOfItsTransfer
{
    OCTFriend *friend = [self addFriendConnected:YES];
    OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];
    NSString *path = [self.root stringByAppendingPathComponent:@"note.txt"];
    XCTAssertTrue([@"salam" writeToFile:path atomically:YES encoding:NSUTF8StringEncoding error:nil]);

    // Sent while they were offline: no transfer yet, so no Tox file id.
    OCTMessageAbstract *message = [self addFileInChat:chat from:nil fileIdHex:nil arrived:100 sent:100];
    [self.realmManager updateObject:message.messageFile withBlock:^(OCTMessageFile *file) {
        file.fileType = OCTMessageFileTypeWaitingConfirmation;
        file.internalFileNumber = kOCTToxFileNumberFailure;
        file.internalFilePath = path;
        file.fileName = @"note.txt";
    }];

    uint8_t idBytes[32];
    for (NSUInteger i = 0; i < 32; i++) {
        idBytes[i] = (uint8_t)(0xC0 + i);
    }
    OCMStub([self.tox fileSendWithFriendNumber:kSeenTestsFriendNumber
                                          kind:OCTToxFileKindData
                                      fileSize:5
                                        fileId:[OCMArg isNil]
                                      fileName:@"note.txt"
                                         error:[OCMArg anyObjectRef]]).andReturn((OCTToxFileNumber)3);
    OCMStub([self.tox fileGetFileIdForFileNumber:3 friendNumber:kSeenTestsFriendNumber error:[OCMArg anyObjectRef]])
    .andReturn([NSData dataWithBytes:idBytes length:sizeof(idBytes)]);

    // They come online and the file leaves: it is known by the id of that transfer.
    OCTSubmanagerFilesImpl *files = [OCTSubmanagerFilesImpl new];
    files.dataSource = self.dataSource;
    [files retrySendingFile:message failureBlock:^(NSError *error) {
        XCTFail(@"the file did not leave: %@", error);
    }];
    XCTAssertEqualObjects(message.messageFile.fileIdHex, OCTSeenTestsHex(0xC0));

    // So their receipt for it finds it.
    [self receiveSeenOfType:OCTSeenAnchorTypeFile anchor:OCTSeenTestsHex(0xC0)];
    XCTAssertEqual(friend.seenWatermark, 100);
}

#pragma mark - The receipts, where their messages are read

- (void)testTheNewestOfTheirMessagesWithAnIdIsReportedOnce
{
    self.friendReachable = YES;
    OCTFriend *friend = [self addFriendConnected:YES];
    OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];
    // Their text came first, stamped by a clock ahead of ours, so it sorts last in the chat; their file came
    // after it, then a text from a client without msgV3 ids. Our own text is the newest of all.
    [self addTextInChat:chat from:friend hash:OCTSeenTestsHex(0x10) arrived:100 sent:500];
    [self addFileInChat:chat from:friend fileIdHex:OCTSeenTestsHex(0xA0) arrived:200 sent:200];
    [self addTextInChat:chat from:friend hash:nil arrived:300 sent:300];
    [self addTextInChat:chat from:nil hash:OCTSeenTestsHex(0x40) arrived:400 sent:400];

    uint32_t before = (uint32_t)[[NSDate date] timeIntervalSince1970];
    [self.chats reportSeenInChat:chat];
    uint32_t after = (uint32_t)[[NSDate date] timeIntervalSince1970];

    XCTAssertEqual(self.sentPackets.count, 1u);
    [self assertPacket:self.sentPackets.firstObject anchorType:OCTSeenAnchorTypeFile anchor:OCTSeenTestsHex(0xA0) seenFrom:before to:after];
    XCTAssertEqual(friend.seenReportedDateInterval, 200);
    XCTAssertNil(friend.seenPendingPacket, @"a receipt that left does not wait");

    // Nothing new: nothing leaves, also once the second is over.
    [self spinFor:1.2];
    [self.chats reportSeenInChat:chat];
    [self spinFor:0.2];
    XCTAssertEqual(self.sentPackets.count, 1u);

    // Something new with an id: it leaves.
    [self addTextInChat:chat from:friend hash:OCTSeenTestsHex(0x60) arrived:600 sent:600];
    [self.chats reportSeenInChat:chat];
    XCTAssertEqual(self.sentPackets.count, 2u);
    [self assertPacket:self.sentPackets.lastObject anchorType:OCTSeenAnchorTypeText anchor:OCTSeenTestsHex(0x60) seenFrom:before to:UINT32_MAX];
}

- (void)testReceiptsWithinASecondLeaveAsOneThatCarriesTheNewest
{
    self.friendReachable = YES;
    OCTFriend *friend = [self addFriendConnected:YES];
    OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];

    [self addTextInChat:chat from:friend hash:OCTSeenTestsHex(0x10) arrived:100 sent:100];
    [self.chats reportSeenInChat:chat];
    [self addTextInChat:chat from:friend hash:OCTSeenTestsHex(0x20) arrived:101 sent:101];
    [self.chats reportSeenInChat:chat];
    [self addTextInChat:chat from:friend hash:OCTSeenTestsHex(0x30) arrived:102 sent:102];
    [self.chats reportSeenInChat:chat];

    XCTAssertEqual(self.sentPackets.count, 1u, @"the second is not over");
    XCTAssertEqualObjects([OCTSeenReceipt receiptWithPacket:friend.seenPendingPacket].anchorHex, OCTSeenTestsHex(0x30),
                          @"the newest replaced the one that waited");

    XCTAssertTrue([self waitUntil:^BOOL { return self.sentPackets.count == 2; } timeout:3]);
    XCTAssertEqualObjects([OCTSeenReceipt receiptWithPacket:self.sentPackets[1]].anchorHex, OCTSeenTestsHex(0x30));
    XCTAssertGreaterThanOrEqual(self.sendTimes[1].doubleValue - self.sendTimes[0].doubleValue, 0.95);
    XCTAssertNil(friend.seenPendingPacket);

    [self spinFor:1.5];
    XCTAssertEqual(self.sentPackets.count, 2u, @"the one in between never leaves");
}

- (void)testAReceiptForAnOfflineFriendWaitsInTheProfileAndLeavesWhenTheyConnect
{
    __weak RLMRealm *firstRealm = nil;
    @autoreleasepool {
        self.friendReachable = NO;
        OCTFriend *friend = [self addFriendConnected:NO];
        OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];
        [self addTextInChat:chat from:friend hash:OCTSeenTestsHex(0x10) arrived:100 sent:100];

        [self.chats reportSeenInChat:chat];
        XCTAssertEqual(self.sentPackets.count, 0u);
        XCTAssertEqualObjects([OCTSeenReceipt receiptWithPacket:friend.seenPendingPacket].anchorHex, OCTSeenTestsHex(0x10));

        firstRealm = self.realmManager.realm;
        [self closeProfile];
    }
    XCTAssertNil(firstRealm, @"the profile was not closed, so this would not read it from disk");

    // The app starts again; the receipt waited in the profile.
    [self openProfile];
    OCTFriend *friend = [self.realmManager friendWithPublicKey:self.friendKey];
    XCTAssertEqualObjects([OCTSeenReceipt receiptWithPacket:friend.seenPendingPacket].anchorHex, OCTSeenTestsHex(0x10));

    // They connect.
    self.friendReachable = YES;
    [self.realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
        theFriend.isConnected = YES;
        theFriend.connectionStatus = OCTToxConnectionStatusUDP;
    }];
    [self.notificationCenter postNotificationName:kOCTFriendConnectionStatusChangeNotification object:friend];

    XCTAssertEqual(self.sentPackets.count, 1u);
    XCTAssertEqualObjects([OCTSeenReceipt receiptWithPacket:self.sentPackets.firstObject].anchorHex, OCTSeenTestsHex(0x10));
    XCTAssertNil(friend.seenPendingPacket);
}

- (void)testAWaitingReceiptLeavesOnTheWatchdogOnceTheFriendIsConnected
{
    OCTFriend *friend = [self addFriendConnected:YES];
    OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];
    [self addTextInChat:chat from:friend hash:OCTSeenTestsHex(0x10) arrived:100 sent:100];

    // Connected, but the link would not take it.
    self.friendReachable = NO;
    [self.chats reportSeenInChat:chat];
    XCTAssertNotNil(friend.seenPendingPacket);

    self.dataSource.toxConnected = YES;
    self.friendReachable = YES;
    [self.chats tickDeliveryWatchdog];

    XCTAssertEqual(self.sentPackets.count, 1u);
    XCTAssertNil(friend.seenPendingPacket);
}

- (void)testNothingIsReportedInAGroupOrTheSavedChat
{
    self.friendReachable = YES;
    OCTFriend *friend = [self addFriendConnected:YES];

    OCTChat *group = [OCTChat new];
    group.isGroup = YES;
    group.groupNumber = 3;
    [group.friends addObject:friend];
    [self.realmManager addObject:group];
    [self addTextInChat:group from:friend hash:OCTSeenTestsHex(0x10) arrived:100 sent:100];

    OCTChat *saved = [OCTChat new];
    saved.isSavedMessages = YES;
    [saved.friends addObject:friend];
    [self.realmManager addObject:saved];
    [self addTextInChat:saved from:friend hash:OCTSeenTestsHex(0x20) arrived:200 sent:200];

    [self.chats reportSeenInChat:group];
    [self.chats reportSeenInChat:saved];
    [self.chats reportSeenInChat:nil];

    XCTAssertEqual(self.sentPackets.count, 0u);
    XCTAssertEqual(friend.seenReportedDateInterval, 0);
    XCTAssertNil(friend.seenPendingPacket);
}

- (void)testAReportedTimeFromAClockSetBackIsLetGo
{
    self.friendReachable = YES;
    OCTFriend *friend = [self addFriendConnected:YES];
    OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];
    NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
    // Reported an hour ahead of the clock: the clock was set back since.
    [self.realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
        theFriend.seenReportedDateInterval = now + 3600;
    }];
    [self addTextInChat:chat from:friend hash:OCTSeenTestsHex(0x10) arrived:now sent:now];

    [self.chats reportSeenInChat:chat];

    XCTAssertEqual(self.sentPackets.count, 1u);
    XCTAssertEqual(friend.seenReportedDateInterval, now);
}

#pragma mark - A profile of schema 42

- (void)testAProfileOfSchema42OpensWithNothingSeenAndKeepsWhatItHad
{
    NSURL *url = [NSURL fileURLWithPath:[self.root stringByAppendingPathComponent:@"schema42.realm"]];
    NSString *messageId = [self writeSchema42ProfileAtURL:url];
    XCTAssertEqual([RLMRealm schemaVersionAtURL:url encryptionKey:nil error:nil], 42u);

    // Another profile is open in this process first, as after switching profiles or logging in again.
    XCTAssertNotNil([[OCTRealmManager alloc] initWithDatabaseFileURL:[NSURL fileURLWithPath:[self.root stringByAppendingPathComponent:@"other.realm"]]
                                                       encryptionKey:nil]);

    __weak RLMRealm *migratedRealm = nil;
    @autoreleasepool {
        OCTRealmManager *manager = [[OCTRealmManager alloc] initWithDatabaseFileURL:url encryptionKey:nil];
        migratedRealm = manager.realm;
        XCTAssertEqual([RLMRealm schemaVersionAtURL:url encryptionKey:nil error:nil], 43u);
        XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:[url.path stringByAppendingPathExtension:@"corrupt"]]);

        OCTFriend *friend = [manager friendWithPublicKey:self.friendKey];
        XCTAssertNotNil(friend, @"the profile was emptied instead of migrated");
        XCTAssertEqualObjects(friend.nickname, @"Bilal");
        XCTAssertEqual(friend.friendNumber, kSeenTestsFriendNumber);
        XCTAssertEqual(friend.seenWatermark, 0);
        XCTAssertEqual(friend.seenReportedDateInterval, 0);
        XCTAssertNil(friend.seenPendingPacket);

        OCTMessageAbstract *message = [manager objectWithUniqueIdentifier:messageId class:[OCTMessageAbstract class]];
        XCTAssertEqualObjects(message.messageText.text, @"salam");
        XCTAssertEqualObjects(message.messageText.msgv3HashHex, OCTSeenTestsHex(0x10));

        // The new columns keep what is written to them.
        NSData *packet = [OCTSeenReceipt packetWithAnchorType:OCTSeenAnchorTypeText anchorHex:OCTSeenTestsHex(0x10) seenTime:7];
        [manager updateObject:friend withBlock:^(OCTFriend *theFriend) {
            theFriend.seenWatermark = 250;
            theFriend.seenReportedDateInterval = 300;
            theFriend.seenPendingPacket = packet;
        }];
    }
    XCTAssertNil(migratedRealm, @"the profile was not closed, so this would not read it from disk");

    OCTRealmManager *reopened = [[OCTRealmManager alloc] initWithDatabaseFileURL:url encryptionKey:nil];
    OCTFriend *friend = [reopened friendWithPublicKey:self.friendKey];
    XCTAssertEqual(friend.seenWatermark, 250);
    XCTAssertEqual(friend.seenReportedDateInterval, 300);
    XCTAssertEqual(friend.seenPendingPacket.length, kOCTSeenReceiptPacketLength);
}

#pragma mark - Private

- (void)openProfile
{
    // In a pool of its own: Realm hands out the realm autoreleased, and a test that closes the profile must be
    // able to let go of it.
    @autoreleasepool {
        NSURL *url = [NSURL fileURLWithPath:[self.root stringByAppendingPathComponent:@"profile.realm"]];
        self.realmManager = [[OCTRealmManager alloc] initWithDatabaseFileURL:url encryptionKey:nil];
        self.notificationCenter = [NSNotificationCenter new];

        self.tox = OCMClassMock([OCTTox class]);
        OCMStub([self.tox publicKeyFromFriendNumber:kSeenTestsFriendNumber error:[OCMArg anyObjectRef]]).andReturn(self.friendKey);
        OCMStub([self.tox friendNumberWithPublicKey:self.friendKey error:[OCMArg anyObjectRef]])
        .andReturn((OCTToxFriendNumber)kSeenTestsFriendNumber);

        __weak OCTSeenReceiptTests *weakSelf = self;
        OCMStub([self.tox sendLosslessPacketWithFriendNumber:kSeenTestsFriendNumber bytes:[OCMArg any] error:[OCMArg anyObjectRef]])
        .andDo(^(NSInvocation *invocation) {
            __strong OCTSeenReceiptTests *strongSelf = weakSelf;
            __unsafe_unretained NSData *bytes = nil;
            [invocation getArgument:&bytes atIndex:3];
            BOOL sent = strongSelf.friendReachable;
            if (sent) {
                [strongSelf.sentPackets addObject:[bytes copy]];
                [strongSelf.sendTimes addObject:@(CACurrentMediaTime())];
            }
            [invocation setReturnValue:&sent];
        });

        self.dataSource = [OCTSeenTestsDataSource new];
        self.dataSource.tox = self.tox;
        self.dataSource.realmManager = self.realmManager;
        self.dataSource.notificationCenter = self.notificationCenter;

        self.chats = [OCTSubmanagerChatsImpl new];
        self.chats.dataSource = self.dataSource;
        [self.chats configure];
    }
}

- (void)closeProfile
{
    [self.notificationCenter removeObserver:self.chats];
    self.chats = nil;
    self.dataSource = nil;
    [self.tox stopMocking];
    self.tox = nil;
    self.notificationCenter = nil;
    self.realmManager = nil;
}

- (OCTFriend *)addFriendConnected:(BOOL)connected
{
    OCTFriend *friend = [OCTFriend new];
    friend.publicKey = self.friendKey;
    friend.nickname = @"Bilal";
    friend.friendNumber = kSeenTestsFriendNumber;
    friend.isConnected = connected;
    friend.connectionStatus = connected ? OCTToxConnectionStatusUDP : OCTToxConnectionStatusNone;
    [self.realmManager addObject:friend];
    return friend;
}

/// A text as objcTox keeps it: ours without a sender; `hash` is its msgV3 id, nil from a client without them.
- (OCTMessageAbstract *)addTextInChat:(OCTChat *)chat
                                 from:(OCTFriend *)sender
                                 hash:(NSString *)hash
                              arrived:(NSTimeInterval)arrived
                                 sent:(NSTimeInterval)sent
{
    OCTMessageText *text = [OCTMessageText new];
    text.text = @"salam";
    text.msgv3HashHex = hash;
    text.isDelivered = YES;
    text.messageId = 7;

    OCTMessageAbstract *message = [OCTMessageAbstract new];
    message.chatUniqueIdentifier = chat.uniqueIdentifier;
    message.senderUniqueIdentifier = sender.uniqueIdentifier;
    message.dateInterval = arrived;
    message.sortTimestamp = sent;
    message.tssent = sent;
    message.messageText = text;
    [self.realmManager addObject:message];
    return message;
}

/// A file that has gone all the way; `sent` 0 is a row from before sortTimestamp.
- (OCTMessageAbstract *)addFileInChat:(OCTChat *)chat
                                 from:(OCTFriend *)sender
                            fileIdHex:(NSString *)fileIdHex
                              arrived:(NSTimeInterval)arrived
                                 sent:(NSTimeInterval)sent
{
    OCTMessageFile *file = [OCTMessageFile new];
    file.fileName = @"photo.jpg";
    file.fileType = OCTMessageFileTypeReady;
    file.fileSize = 5;
    file.fileIdHex = fileIdHex;
    file.isDelivered = YES;

    OCTMessageAbstract *message = [OCTMessageAbstract new];
    message.chatUniqueIdentifier = chat.uniqueIdentifier;
    message.senderUniqueIdentifier = sender.uniqueIdentifier;
    message.dateInterval = arrived;
    message.sortTimestamp = sent;
    message.messageFile = file;
    [self.realmManager addObject:message];
    return message;
}

- (void)receiveSeenOfType:(OCTSeenAnchorType)anchorType anchor:(NSString *)anchorHex
{
    [self.chats tox:self.tox friendSeenWithAnchorType:anchorType anchorHex:anchorHex seenTime:0 friendNumber:kSeenTestsFriendNumber];
}

- (void)assertPacket:(NSData *)packet
          anchorType:(OCTSeenAnchorType)anchorType
              anchor:(NSString *)anchorHex
            seenFrom:(uint32_t)from
                  to:(uint32_t)to
{
    OCTSeenReceipt *receipt = [OCTSeenReceipt receiptWithPacket:packet];
    XCTAssertNotNil(receipt, @"not a read receipt: %@", packet);
    XCTAssertEqual(receipt.anchorType, anchorType);
    XCTAssertEqualObjects(receipt.anchorHex, anchorHex);
    XCTAssertGreaterThanOrEqual(receipt.seenTime, from);
    XCTAssertLessThanOrEqual(receipt.seenTime, to);
}

- (void)spinFor:(NSTimeInterval)seconds
{
    [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:seconds]];
}

- (BOOL)waitUntil:(BOOL (^)(void))condition timeout:(NSTimeInterval)timeout
{
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:timeout];
    while (! condition() && deadline.timeIntervalSinceNow > 0) {
        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.05]];
    }
    return condition();
}

/**
 * A profile as 0.2.56 (schema 42) wrote it: today's classes without the three columns of 43, at version 42,
 * holding the friend and one text from them. Returns the text's id.
 */
- (NSString *)writeSchema42ProfileAtURL:(NSURL *)url
{
    NSSet<NSString *> *columnsOf43 = [NSSet setWithArray:@[@"seenWatermark", @"seenReportedDateInterval", @"seenPendingPacket"]];
    NSString *messageId = [[NSUUID UUID] UUIDString];

    @autoreleasepool {
        NSMutableArray<RLMObjectSchema *> *objectSchemas = [NSMutableArray new];
        for (RLMObjectSchema *shared in [RLMSchema sharedSchema].objectSchema) {
            RLMObjectSchema *objectSchema = [shared copy];
            if ([objectSchema.className isEqualToString:@"OCTFriend"]) {
                NSMutableArray<RLMProperty *> *properties = [NSMutableArray new];
                for (RLMProperty *property in objectSchema.properties) {
                    if (! [columnsOf43 containsObject:property.name]) {
                        [properties addObject:property];
                    }
                }
                XCTAssertEqual(properties.count + columnsOf43.count, objectSchema.properties.count);
                [objectSchema setProperties:properties];
            }
            [objectSchemas addObject:objectSchema];
        }
        RLMSchema *schema = [RLMSchema new];
        [schema setObjectSchema:objectSchemas];

        RLMRealmConfiguration *configuration = [RLMRealmConfiguration new];
        configuration.fileURL = url;
        configuration.schemaVersion = 42;
        [configuration setCustomSchema:schema];

        NSError *error = nil;
        RLMRealm *realm = [RLMRealm realmWithConfiguration:configuration error:&error];
        XCTAssertNotNil(realm, @"%@", error);
        XCTAssertNil(realm.schema[@"OCTFriend"][@"seenWatermark"]);

        OCTFriend *friend = [OCTFriend new];
        friend.publicKey = self.friendKey;
        friend.nickname = @"Bilal";
        friend.friendNumber = kSeenTestsFriendNumber;

        OCTMessageText *text = [OCTMessageText new];
        text.text = @"salam";
        text.msgv3HashHex = OCTSeenTestsHex(0x10);
        OCTMessageAbstract *message = [OCTMessageAbstract new];
        message.uniqueIdentifier = messageId;
        message.chatUniqueIdentifier = [[NSUUID UUID] UUIDString];
        message.senderUniqueIdentifier = friend.uniqueIdentifier;
        message.dateInterval = 100;
        message.sortTimestamp = 100;
        message.messageText = text;

        [realm transactionWithBlock:^{
            [realm createObject:@"OCTFriend" withValue:friend];
            [realm createObject:@"OCTMessageAbstract" withValue:message];
        }];
    }

    return messageId;
}

@end
