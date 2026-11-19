// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>
#import <OCMock/OCMock.h>
#import <Security/Security.h>

#import "OCTSubmanagerChatsImpl.h"
#import "OCTSubmanagerGroupsImpl.h"
#import "OCTSubmanagerDataSource.h"
#import "OCTTox.h"
#import "OCTTox+Private.h"
#import "OCTToxConstants.h"
#import "OCTRealmManager.h"
#import "OCTChat.h"
#import "OCTFriend.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageFile.h"
#import "OCTMessageText.h"
#import "OCTNgcGroupFileTransfer.h"
#import "OCTFileTools.h"
#import "OCTToxOptions.h"

// The bytes of a group file in one chunk: a packet of 37000 bytes, less the 48 of its header.
static const NSUInteger kGroupTestChunkPayload = 37000 - 48;

// The name toxcore was given for a file offered to a friend, by mocked_tox_file_send.
static NSData *offeredFileName;
static size_t offeredFileNameLength;
static uint32_t offeredFriendNumber;

static uint32_t mocked_tox_file_send(Tox *tox, uint32_t friend_number, uint32_t kind, uint64_t file_size, const uint8_t *file_id,
                                     const uint8_t *filename, size_t filename_length, Tox_Err_File_Send *error)
{
    offeredFriendNumber = friend_number;
    offeredFileName = filename ? [NSData dataWithBytes:filename length:filename_length] : nil;
    offeredFileNameLength = filename_length;

    if (error) {
        *error = TOX_ERR_FILE_SEND_OK;
    }

    return 3;
}

@interface OCTSubmanagerChatsImpl (TextPartsTests)

+ (UInt32)textStampAfterStamp:(UInt32)lastStamp now:(NSTimeInterval)now;
- (void)wakeFriendWithPushToken:(NSString *)pushToken
             forMessageWithHash:(NSString *)msgv3HashHex
                           text:(NSString *)text
                         inChat:(OCTChat *)chat;

@end

@interface OCTSubmanagerGroupsImpl (GroupSendTests)

- (NSString *)defaultGroupPeerName;
- (BOOL)prepareChatForGroupActivity:(OCTChat *)chat peerName:(NSString *)peerName error:(NSError **)error;
- (BOOL)precheckCanSendInChat:(OCTChat *)chat error:(NSError **)error;
- (void)scheduleHistSyncBroadcastForGroupNumber:(OCTToxGroupNumber)groupNumber;
- (BOOL)flushPendingGroupMessagesForChat:(OCTChat *)chat;

@end

@interface OCTNgcGroupFileTransfer (StallTests)

- (NSMutableDictionary<NSString *, id> *)assemblies;
- (void)setNowMsBlock:(uint64_t (^)(void))block;
- (void)setSleepMsBlock:(void (^)(uint64_t ms))block;
- (void)nackTickForAssembly:(id)assembly assemblyKey:(NSString *)assemblyKey;
- (void)rememberCompletedAssemblyKey:(NSString *)assemblyKey atMs:(uint64_t)nowMs;
- (void)appendFilename:(NSString *)fileName toData:(NSMutableData *)data;
- (NSString *)utf8FilenameFromData:(NSData *)data offset:(NSUInteger)offset length:(NSUInteger)maxLen;

@end

// OCTNgcUploadPace, private to OCTNgcGroupFileTransfer.m.
@interface NSObject (OCTNgcUploadPaceTests)

+ (uint64_t)costOfPacketBytes:(uint64_t)packetBytes members:(NSUInteger)members;
- (instancetype)initWithBytesPerSecond:(uint64_t)bytesPerSecond burstMs:(uint64_t)burstMs;
- (BOOL)readyAtMs:(uint64_t)nowMs;
- (void)spend:(uint64_t)cost;
- (uint64_t)msUntilReadyAtMs:(uint64_t)nowMs;

@end

static const uint64_t kPaceTestsRate = 512 * 1024;
static const uint64_t kPaceTestsBurst = 512 * 1024 / 4;
static const uint64_t kPaceTestsChunk = 37000;
static const uint64_t kPaceTestsT0 = 1000000;
// KHANDAQ (0.2.57): the pace of the relays (kOCTNgcRelayedUploadBytesPerSecond) and its burst, 250 ms of it.
static const uint64_t kRelayTestsRate = 64 * 1024;
static const uint64_t kRelayTestsBurst = 64 * 1024 / 4;

/// The clock of a transfer under test, which the test moves on and the transfer's waits move as long as they last.
@interface OCTGroupTestClock : NSObject
@property (atomic, assign) uint64_t nowMs;
@end

@implementation OCTGroupTestClock
@end

/**
 * KHANDAQ (long texts, group connection).
 *
 * A long text goes as several messages: each is cut after a whole character, the parts keep their order
 * at the receiver, and only the first one wakes a friend who is offline.
 *
 * toxcore's group connection callback passes its own connection state (1 disconnected, 2 connecting,
 * 3 connected), which was taken for the 1 / 0 / -1 of the poll: every state read as connected. And a
 * group with nobody confirmed, still connecting or connected, reports a message sent that reaches
 * nobody, so such a message now waits until somebody can get it.
 */
@interface OCTTextPartsAndGroupSendTests : XCTestCase

@end

@implementation OCTTextPartsAndGroupSendTests

#pragma mark - A message cut to its room

- (void)testAMessageIsCutAfterItsLastWholeCharacter
{
    // It fits: all of it.
    XCTAssertEqual([OCTTox utf8LengthOfMessage:@"salam" fittingLength:5], 5u);
    XCTAssertEqual([OCTTox utf8LengthOfMessage:@"salam" fittingLength:1372], 5u);

    // Five Cyrillic letters of two bytes each: nine bytes of room hold four of them.
    NSString *cyrillic = @"жжжжж";
    XCTAssertEqual([OCTTox utf8LengthOfMessage:cyrillic fittingLength:9], 8u);

    // A thumb with a skin tone is one character of eight bytes: it does not go in half.
    NSString *thumb = @"a\U0001F44D\U0001F3FD";
    XCTAssertEqual([OCTTox utf8LengthOfMessage:thumb fittingLength:8], 1u);
    XCTAssertEqual([OCTTox utf8LengthOfMessage:thumb fittingLength:9], 9u);

    // A family of four is one character of 25 bytes.
    NSString *family = @"ab\U0001F468‍\U0001F469‍\U0001F467‍\U0001F466";
    XCTAssertEqual([OCTTox utf8LengthOfMessage:family fittingLength:26], 2u);
    XCTAssertEqual([OCTTox utf8LengthOfMessage:family fittingLength:27], 27u);

    // Only a character longer than the room is cut, before one of its scalars: a letter under ten marks
    // (21 bytes) in six bytes of room keeps the letter and two marks.
    NSMutableString *monster = [NSMutableString stringWithString:@"a"];
    for (NSUInteger i = 0; i < 10; i++) {
        [monster appendString:@"́"];
    }
    XCTAssertEqual([OCTTox utf8LengthOfMessage:monster fittingLength:6], 5u);
}

#pragma mark - The group connection callback

- (void)testTheGroupConnectionCallbackSpeaksAsThePollDoes
{
    XCTAssertEqual([OCTTox groupConnectionStatusFromCConnectionState:3], 1, @"connected");
    XCTAssertEqual([OCTTox groupConnectionStatusFromCConnectionState:2], 0, @"connecting");
    XCTAssertEqual([OCTTox groupConnectionStatusFromCConnectionState:1], -1, @"disconnected");
    XCTAssertEqual([OCTTox groupConnectionStatusFromCConnectionState:0], -1, @"not a state");
}

#pragma mark - The parts of a long text

- (void)testThePartsOfALongTextKeepTheirOrderAndWakeAFriendOnce
{
    id dataSource = OCMProtocolMock(@protocol(OCTSubmanagerDataSource));
    id tox = OCMClassMock([OCTTox class]);
    id realmManager = OCMClassMock([OCTRealmManager class]);
    OCMStub([dataSource managerGetTox]).andReturn(tox);
    OCMStub([dataSource managerGetRealmManager]).andReturn(realmManager);
    OCMStub([dataSource managerGetNotificationCenter]).andReturn([NSNotificationCenter new]);
    OCMStub([dataSource managerUseFauxOfflineMessaging]).andReturn(YES);

    // Objects no Realm keeps, not class mocks: a class mock of a Realm object class forwards its class
    // methods, and Realm then recurses until the stack overflows once it builds the schema of that class.
    OCTFriend *friend = [OCTFriend new];
    friend.friendNumber = 5;
    friend.pushToken = @"https://push.khandaq.org/toxfcm/fcm.php?id=test";
    OCTChat *chat = [OCTChat new];
    [chat.friends addObject:friend];

    // The friend is offline: every part is refused as "not connected", which faux offline messaging
    // keeps for later.
    NSError *notConnected = [NSError errorWithDomain:kOCTToxErrorDomain
                                                code:OCTToxErrorFriendSendMessageFriendNotConnected
                                            userInfo:nil];
    NSMutableArray<NSString *> *sentTexts = [NSMutableArray new];
    NSMutableArray<NSNumber *> *sentStamps = [NSMutableArray new];
    [[[[tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        __unsafe_unretained NSString *text = nil;
        UInt32 stamp = 0;
        NSError *__autoreleasing *errorPointer = NULL;
        [invocation getArgument:&text atIndex:4];
        [invocation getArgument:&stamp atIndex:6];
        [invocation getArgument:&errorPointer atIndex:7];
        @synchronized(sentTexts) {
            [sentTexts addObject:[text copy]];
            [sentStamps addObject:@(stamp)];
        }
        if (errorPointer) {
            *errorPointer = notConnected;
        }
        OCTToxMessageId messageId = 0;
        [invocation setReturnValue:&messageId];
    }] sendMessageWithFriendNumber:0 type:0 message:[OCMArg any] msgv3HashHex:[OCMArg any] msgv3tssec:0 error:[OCMArg anyObjectRef]];

    NSMutableArray<NSNumber *> *storedStamps = [NSMutableArray new];
    NSMutableArray<NSNumber *> *storedDates = [NSMutableArray new];
    [[[[realmManager stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        UInt32 stamp = 0;
        NSTimeInterval date = 0;
        [invocation getArgument:&stamp atIndex:9];
        [invocation getArgument:&date atIndex:11];
        [storedStamps addObject:@(stamp)];
        [storedDates addObject:@(date)];
    }] addMessageWithText:[OCMArg any]
                     type:0
                     chat:[OCMArg any]
                   sender:[OCMArg any]
                messageId:0
             msgv3HashHex:[OCMArg any]
                 sentPush:NO
                   tssent:0
                   tsrcvd:0
         sentDateInterval:0];

    OCTSubmanagerChatsImpl *submanager = [OCTSubmanagerChatsImpl new];
    submanager.dataSource = dataSource;
    id partial = OCMPartialMock(submanager);
    __block NSUInteger wakes = 0;
    __block NSString *wakeText = nil;
    OCMStub([partial wakeFriendWithPushToken:[OCMArg any] forMessageWithHash:[OCMArg any] text:[OCMArg any] inChat:[OCMArg any]]).andDo(^(NSInvocation *invocation) {
        wakes++;
        __unsafe_unretained NSString *text = nil;
        [invocation getArgument:&text atIndex:4];
        wakeText = text;
    });

    NSArray<NSString *> *parts = @[@"first", @"second", @"third"];
    NSMutableArray<NSNumber *> *succeeded = [NSMutableArray new];
    XCTestExpectation *allSent = [self expectationWithDescription:@"every part answered"];
    NSTimeInterval before = [[NSDate date] timeIntervalSince1970];

    [submanager sendMessagePartsToChat:chat parts:parts type:OCTToxMessageTypeNormal successBlock:^(OCTMessageAbstract *message, NSInteger index) {
        [succeeded addObject:@(index)];
        if (succeeded.count == parts.count) {
            [allSent fulfill];
        }
    } failureBlock:^(NSError *error, NSInteger index) {
        XCTFail(@"part %ld failed: %@", (long)index, error);
    }];

    [self waitForExpectationsWithTimeout:5.0 handler:nil];

    XCTAssertEqualObjects(sentTexts, parts, @"the parts went out of order");
    XCTAssertEqualObjects(succeeded, (@[@0, @1, @2]));
    XCTAssertEqual(wakes, 1u, @"each part woke the friend: the relay counts every wake");
    XCTAssertEqualObjects(wakeText, @"first", @"the wake's preview is the text the friend reads first");

    // Each part is stamped a second after the one before it, so whole-second stamps cannot tie at the
    // receiver.
    UInt32 first = sentStamps.firstObject.unsignedIntValue;
    XCTAssertEqualWithAccuracy((NSTimeInterval)first, before, 2.0, @"the first part is stamped with the moment of sending");
    XCTAssertEqualObjects(sentStamps, (@[@(first), @(first + 1), @(first + 2)]));
    XCTAssertEqualObjects(storedStamps, sentStamps, @"stored with other stamps than they were sent with");

    // Here they sort by the moment of sending, in order.
    XCTAssertEqual(storedDates.count, 3u);
    XCTAssertLessThan(storedDates[0].doubleValue, storedDates[1].doubleValue);
    XCTAssertLessThan(storedDates[1].doubleValue, storedDates[2].doubleValue);
    XCTAssertLessThan(storedDates[2].doubleValue - storedDates[0].doubleValue, 1.0);

    [partial stopMocking];
}

#pragma mark - The stamps of the texts to a chat

- (void)testAStampIsTheClockOrASecondAfterTheStampBeforeIt
{
    // The first text to a chat, and one sent once the clock has passed the stamp before it: the clock.
    XCTAssertEqual([OCTSubmanagerChatsImpl textStampAfterStamp:0 now:1000.7], 1000u);
    XCTAssertEqual([OCTSubmanagerChatsImpl textStampAfterStamp:999 now:1000.0], 1000u);
    XCTAssertEqual([OCTSubmanagerChatsImpl textStampAfterStamp:1000 now:1005.2], 1005u);

    // One sent within the second of the stamp before it, or while the stamps run ahead of the clock:
    // a second after that stamp, so the two cannot tie at the receiver.
    XCTAssertEqual([OCTSubmanagerChatsImpl textStampAfterStamp:1000 now:1000.9], 1001u);
    XCTAssertEqual([OCTSubmanagerChatsImpl textStampAfterStamp:1040 now:1000.0], 1041u);

    // A clock set back by an hour or more starts the stamps from it again.
    XCTAssertEqual([OCTSubmanagerChatsImpl textStampAfterStamp:1000 + 3599 now:1000.0], 4600u);
    XCTAssertEqual([OCTSubmanagerChatsImpl textStampAfterStamp:1000 + 3600 now:1000.0], 1000u);
}

/// The parts of a long text ran some seconds ahead of the clock, and a text sent right after them was
/// stamped with the clock: at a receiver that got them all late it sorted among the last parts.
- (void)testATextSentRightAfterThePartsOfALongTextIsStampedAfterThem
{
    NSDictionary *rig = [self friendSendRig];
    OCTSubmanagerChatsImpl *submanager = rig[@"submanager"];
    OCTChat *chat = [self chatWithFriendNumber:5];
    NSTimeInterval before = [[NSDate date] timeIntervalSince1970];

    [self sendParts:@[@"first", @"second", @"third"] thenText:@"next" toChat:chat withSubmanager:submanager];

    NSMutableArray<NSNumber *> *stamps = [NSMutableArray new];
    for (NSArray<NSNumber *> *send in rig[@"sends"]) {
        [stamps addObject:send.lastObject];
    }
    XCTAssertEqual(stamps.count, 4u);
    UInt32 first = stamps.firstObject.unsignedIntValue;
    XCTAssertEqualWithAccuracy((NSTimeInterval)first, before, 2.0, @"the first part is stamped with the moment of sending");
    XCTAssertEqualObjects(stamps, (@[@(first), @(first + 1), @(first + 2), @(first + 3)]),
                          @"the text after the parts can tie with them or sort among them at the receiver");
}

- (void)testTheStampsOfTheTextsToOneChatDoNotRunAheadInAnother
{
    NSDictionary *rig = [self friendSendRig];
    OCTSubmanagerChatsImpl *submanager = rig[@"submanager"];
    OCTChat *chat = [self chatWithFriendNumber:5];
    OCTChat *otherChat = [self chatWithFriendNumber:6];

    [self sendParts:@[@"first", @"second", @"third"] thenText:@"next" toChat:chat withSubmanager:submanager];
    NSTimeInterval before = [[NSDate date] timeIntervalSince1970];
    [self sendParts:@[] thenText:@"hello" toChat:otherChat withSubmanager:submanager];

    NSArray<NSArray<NSNumber *> *> *sends = rig[@"sends"];
    XCTAssertEqual(sends.count, 5u);
    XCTAssertEqualObjects(sends.lastObject.firstObject, @6);
    XCTAssertEqualWithAccuracy(sends.lastObject.lastObject.doubleValue, before, 2.0,
                               @"a text to another chat took the stamps of the first one");
    XCTAssertLessThan(sends.lastObject.lastObject.unsignedIntValue, sends[3].lastObject.unsignedIntValue);
}

/**
 * A chats submanager whose tox sends every text at once and records, in order, the friend number and
 * the msgV3 stamp of each in "sends".
 */
- (NSDictionary *)friendSendRig
{
    id dataSource = OCMProtocolMock(@protocol(OCTSubmanagerDataSource));
    id tox = OCMClassMock([OCTTox class]);
    id realmManager = OCMClassMock([OCTRealmManager class]);
    OCMStub([dataSource managerGetTox]).andReturn(tox);
    OCMStub([dataSource managerGetRealmManager]).andReturn(realmManager);

    NSMutableArray<NSArray<NSNumber *> *> *sends = [NSMutableArray new];
    [[[[tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        OCTToxFriendNumber friendNumber = 0;
        UInt32 stamp = 0;
        [invocation getArgument:&friendNumber atIndex:2];
        [invocation getArgument:&stamp atIndex:6];
        @synchronized(sends) {
            [sends addObject:@[@(friendNumber), @(stamp)]];
        }
        OCTToxMessageId messageId = 1;
        [invocation setReturnValue:&messageId];
    }] sendMessageWithFriendNumber:0 type:0 message:[OCMArg any] msgv3HashHex:[OCMArg any] msgv3tssec:0 error:[OCMArg anyObjectRef]];

    OCTSubmanagerChatsImpl *submanager = [OCTSubmanagerChatsImpl new];
    submanager.dataSource = dataSource;

    // The data source is weak: the mocks live as long as the rig.
    return @{@"submanager" : submanager, @"sends" : sends, @"mocks" : @[dataSource, tox, realmManager]};
}

- (OCTChat *)chatWithFriendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTFriend *friend = [OCTFriend new];
    friend.friendNumber = friendNumber;
    OCTChat *chat = [OCTChat new];
    [chat.friends addObject:friend];
    return chat;
}

/// Sends `parts` as the parts of one text, then `text` on its own, and waits until all of them went.
- (void)sendParts:(NSArray<NSString *> *)parts
         thenText:(NSString *)text
           toChat:(OCTChat *)chat
   withSubmanager:(OCTSubmanagerChatsImpl *)submanager
{
    XCTestExpectation *sent = [self expectationWithDescription:@"every text sent"];
    sent.expectedFulfillmentCount = parts.count + 1;

    [submanager sendMessagePartsToChat:chat parts:parts type:OCTToxMessageTypeNormal successBlock:^(OCTMessageAbstract *message, NSInteger index) {
        [sent fulfill];
    } failureBlock:^(NSError *error, NSInteger index) {
        XCTFail(@"part %ld failed: %@", (long)index, error);
    }];
    [submanager sendMessageToChat:chat text:text type:OCTToxMessageTypeNormal successBlock:^(OCTMessageAbstract *message) {
        [sent fulfill];
    } failureBlock:^(NSError *error) {
        XCTFail(@"the text failed: %@", error);
    }];

    [self waitForExpectationsWithTimeout:5.0 handler:nil];
}

#pragma mark - A message to a group that is not connected

- (void)testAGroupConnectingWithNobodyConfirmedKeepsTheMessage
{
    NSDictionary *sent = [self sendToGroupWithStatus:0 peers:1];
    XCTAssertEqualObjects(sent[@"sends"], @0, @"sent into a group nobody in it could get it from");
    XCTAssertEqualObjects(sent[@"pending"], @1);
    XCTAssertEqualObjects(sent[@"reconnects"], @0, @"a connecting group must be left to connect");
}

- (void)testAGroupConnectingWithAPeerConfirmedSendsAtOnce
{
    NSDictionary *sent = [self sendToGroupWithStatus:0 peers:2];
    XCTAssertEqualObjects(sent[@"sends"], @1);
    XCTAssertEqualObjects(sent[@"pending"], @0);
    XCTAssertEqualObjects(sent[@"stored"], @1);
}

- (void)testAConnectedGroupSendsAtOnce
{
    NSDictionary *sent = [self sendToGroupWithStatus:1 peers:3];
    XCTAssertEqualObjects(sent[@"sends"], @1);
    XCTAssertEqualObjects(sent[@"pending"], @0);
}

- (void)testAConnectedGroupWithNobodyElseKeepsTheMessage
{
    // A group connects before any peer is confirmed, and stays connected once its peers timed out:
    // toxcore reports a message sent that reaches nobody.
    NSDictionary *sent = [self sendToGroupWithStatus:1 peers:1];
    XCTAssertEqualObjects(sent[@"sends"], @0, @"sent into a group nobody else is in");
    XCTAssertEqualObjects(sent[@"pending"], @1);
    XCTAssertEqualObjects(sent[@"stored"], @0);
    XCTAssertEqualObjects(sent[@"reconnects"], @0, @"a connected group must not be reconnected");
}

- (void)testADisconnectedGroupIsReconnectedAndKeepsTheMessage
{
    NSDictionary *sent = [self sendToGroupWithStatus:-1 peers:1];
    XCTAssertEqualObjects(sent[@"sends"], @0);
    XCTAssertEqualObjects(sent[@"pending"], @1);
    XCTAssertEqualObjects(sent[@"reconnects"], @1);
}

/**
 * Sends a text to a group whose poll answers `status` with `peers` peers (this client among them) and
 * counts what happened: "sends" to toxcore, "pending" messages kept for later, "stored" sent messages,
 * "reconnects".
 */
- (NSDictionary *)sendToGroupWithStatus:(int32_t)status peers:(uint32_t)peers
{
    id dataSource = OCMProtocolMock(@protocol(OCTSubmanagerDataSource));
    id tox = OCMClassMock([OCTTox class]);
    id realmManager = OCMClassMock([OCTRealmManager class]);
    OCMStub([dataSource managerGetTox]).andReturn(tox);
    OCMStub([dataSource managerGetRealmManager]).andReturn(realmManager);
    OCMStub([tox performSyncBlockOnToxQueue:[OCMArg any]]).andDo(^(NSInvocation *invocation) {
        void (^__unsafe_unretained block)(void) = nil;
        [invocation getArgument:&block atIndex:2];
        block();
    });
    [[[[tox stub] ignoringNonObjectArgs] andReturnValue:OCMOCK_VALUE(status)] groupConnectionStatusForGroupNumber:0 error:[OCMArg anyObjectRef]];
    [[[[tox stub] ignoringNonObjectArgs] andReturnValue:OCMOCK_VALUE(peers)] groupPeerCountForGroupNumber:0 error:[OCMArg anyObjectRef]];

    __block NSUInteger sends = 0;
    __block NSUInteger reconnects = 0;
    __block NSUInteger pending = 0;
    __block NSUInteger stored = 0;
    [[[[tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        sends++;
        BOOL result = YES;
        [invocation setReturnValue:&result];
    }] groupSendMessage:[OCMArg any] type:0 groupNumber:0 messageId:NULL error:[OCMArg anyObjectRef]];
    [[[[tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        reconnects++;
        BOOL result = YES;
        [invocation setReturnValue:&result];
    }] groupReconnectWithGroupNumber:0 error:[OCMArg anyObjectRef]];

    // Not a class mock, as in the test of the parts of a long text.
    OCTMessageAbstract *message = [OCTMessageAbstract new];
    [[[[realmManager stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        pending++;
        __unsafe_unretained id result = message;
        [invocation setReturnValue:&result];
    }] addGroupPendingMessageWithText:[OCMArg any] type:0 chat:[OCMArg any]];
    [[[[realmManager stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        stored++;
        __unsafe_unretained id result = message;
        [invocation setReturnValue:&result];
    }] addGroupMessageWithText:[OCMArg any] type:0 chat:[OCMArg any] peerId:0 peerName:[OCMArg any] messageId:0];

    OCTSubmanagerGroupsImpl *submanager = [OCTSubmanagerGroupsImpl new];
    submanager.dataSource = dataSource;
    id partial = OCMPartialMock(submanager);
    OCMStub([partial defaultGroupPeerName]).andReturn(@"me");
    OCMStub([partial prepareChatForGroupActivity:[OCMArg any] peerName:[OCMArg any] error:[OCMArg anyObjectRef]]).andReturn(YES);
    OCMStub([partial precheckCanSendInChat:[OCMArg any] error:[OCMArg anyObjectRef]]).andReturn(YES);
    [[[partial stub] ignoringNonObjectArgs] scheduleHistSyncBroadcastForGroupNumber:0];

    OCTChat *chat = [OCTChat new];
    chat.isGroup = YES;
    chat.groupNumber = 3;

    __block BOOL answered = NO;
    [submanager sendMessageToChat:chat text:@"salam" type:OCTToxMessageTypeNormal successBlock:^(OCTMessageAbstract *sentMessage) {
        answered = YES;
        XCTAssertEqual(sentMessage, message);
    } failureBlock:^(NSError *error) {
        XCTFail(@"the message was refused: %@", error);
    }];
    XCTAssertTrue(answered, @"the send did not answer");

    [partial stopMocking];

    return @{
        @"sends": @(sends),
        @"pending": @(pending),
        @"stored": @(stored),
        @"reconnects": @(reconnects),
    };
}

#pragma mark - A queued group file

- (void)testAQueuedFileGoesOnceWhileItIsBeingSent
{
    OCTMessageAbstract *held = [self queuedFileWithTransferId:@"a1"];
    NSDictionary *rig = [self rigWithQueue:@[held]];
    OCTSubmanagerGroupsImpl *submanager = rig[@"submanager"];
    NSMutableArray *completions = rig[@"completions"];

    // A peer joins, the connection changes, the maintenance ticks: three flushes while the file goes.
    [submanager flushPendingGroupMessagesForChat:rig[@"chat"]];
    [submanager flushPendingGroupMessagesForChat:rig[@"chat"]];
    [submanager flushPendingGroupMessagesForChat:rig[@"chat"]];
    XCTAssertEqual(completions.count, 1u, @"a flush sent the whole file again while it was being sent");

    void (^completion)(BOOL, NSError *) = completions.firstObject;
    completion(YES, nil);
    XCTAssertEqual(held.messageFile.fileType, OCTMessageFileTypeReady);
    XCTAssertTrue(held.messageFile.isDelivered);
    XCTAssertFalse(held.groupPendingSend);

    [submanager flushPendingGroupMessagesForChat:rig[@"chat"]];
    XCTAssertEqual(completions.count, 1u, @"a file that went was sent again");

    [rig[@"partial"] stopMocking];
}

- (void)testAQueuedFileWhoseSendFailedWhileNobodyCouldGetItGoesAgain
{
    OCTMessageAbstract *held = [self queuedFileWithTransferId:@"a2"];
    NSDictionary *rig = [self rigWithQueue:@[held]];
    OCTSubmanagerGroupsImpl *submanager = rig[@"submanager"];
    NSMutableArray *completions = rig[@"completions"];
    NSMutableDictionary *group = rig[@"group"];

    [submanager flushPendingGroupMessagesForChat:rig[@"chat"]];
    XCTAssertEqual(completions.count, 1u);

    // The peers time out while it goes, and the send fails.
    group[@"peers"] = @1;
    void (^completion)(BOOL, NSError *) = completions.firstObject;
    completion(NO, [NSError errorWithDomain:kOCTNgcGroupFileTransferErrorDomain
                                       code:OCTNgcGroupFileTransferErrorSendFailed
                                   userInfo:nil]);
    XCTAssertTrue(held.groupPendingSend, @"the file left the queue while nobody could get it");
    XCTAssertEqual(held.messageFile.fileType, OCTMessageFileTypeLoading);

    // Somebody is back: the next flush sends it again.
    group[@"peers"] = @2;
    [submanager flushPendingGroupMessagesForChat:rig[@"chat"]];
    XCTAssertEqual(completions.count, 2u, @"the file stayed marked as being sent");

    [rig[@"partial"] stopMocking];
}

- (void)testAFileThatWentIsNotTakenBackByASendThatFailed
{
    OCTMessageAbstract *held = [self queuedFileWithTransferId:@"a3"];
    NSDictionary *rig = [self rigWithQueue:@[held]];
    NSMutableArray *completions = rig[@"completions"];

    [rig[@"submanager"] flushPendingGroupMessagesForChat:rig[@"chat"]];
    XCTAssertEqual(completions.count, 1u);

    held.messageFile.fileType = OCTMessageFileTypeReady;
    held.messageFile.isDelivered = YES;
    void (^completion)(BOOL, NSError *) = completions.firstObject;
    completion(NO, [NSError errorWithDomain:kOCTNgcGroupFileTransferErrorDomain
                                       code:OCTNgcGroupFileTransferErrorSendFailed
                                   userInfo:nil]);
    XCTAssertEqual(held.messageFile.fileType, OCTMessageFileTypeReady, @"a file that went was marked failed");
    XCTAssertTrue(held.messageFile.isDelivered);
    XCTAssertFalse(held.groupPendingSend);

    [rig[@"partial"] stopMocking];
}

- (void)testAQueuedFileThatWentOrWasCalledOffLeavesTheQueueUnsent
{
    OCTMessageAbstract *went = [self queuedFileWithTransferId:@"a4"];
    went.messageFile.fileType = OCTMessageFileTypeReady;
    went.messageFile.isDelivered = YES;
    OCTMessageAbstract *calledOff = [self queuedFileWithTransferId:@"a5"];
    calledOff.messageFile.fileType = OCTMessageFileTypeCanceled;
    NSDictionary *rig = [self rigWithQueue:@[went, calledOff]];

    [rig[@"submanager"] flushPendingGroupMessagesForChat:rig[@"chat"]];
    XCTAssertEqual([rig[@"completions"] count], 0u, @"sent again");
    XCTAssertFalse(went.groupPendingSend);
    XCTAssertFalse(calledOff.groupPendingSend);
    XCTAssertEqual(went.messageFile.fileType, OCTMessageFileTypeReady);
    XCTAssertEqual(calledOff.messageFile.fileType, OCTMessageFileTypeCanceled);

    [rig[@"partial"] stopMocking];
}

- (void)testAQueuedFileCalledOffLeavesTheQueue
{
    OCTMessageAbstract *held = [self queuedFileWithTransferId:@"a6"];
    NSDictionary *rig = [self rigWithQueue:@[held]];
    // Nobody can get it, so it waits.
    NSMutableDictionary *group = rig[@"group"];
    group[@"peers"] = @1;

    NSError *error = nil;
    XCTAssertTrue([rig[@"submanager"] cancelGroupFileTransferForMessage:held error:&error], @"%@", error);
    XCTAssertEqual(held.messageFile.fileType, OCTMessageFileTypeCanceled);
    XCTAssertFalse(held.groupPendingSend, @"a file called off still waited to be sent");

    group[@"peers"] = @2;
    [rig[@"submanager"] flushPendingGroupMessagesForChat:rig[@"chat"]];
    XCTAssertEqual([rig[@"completions"] count], 0u, @"a file called off was sent");

    [rig[@"partial"] stopMocking];
}

- (OCTMessageAbstract *)queuedFileWithTransferId:(NSString *)transferId
{
    // Objects no Realm keeps, as in the test of the parts of a long text.
    OCTMessageFile *file = [OCTMessageFile new];
    file.fileType = OCTMessageFileTypeLoading;
    file.internalFilePath = @"Documents/Uploads/queued.jpg";
    file.groupMsgIdHashHex = transferId;
    OCTMessageAbstract *message = [OCTMessageAbstract new];
    message.messageFile = file;
    message.groupPendingSend = YES;
    message.dateInterval = [[NSDate date] timeIntervalSince1970];
    return message;
}

- (OCTMessageAbstract *)queuedText:(NSString *)text
{
    // Not kept by any Realm either.
    OCTMessageText *messageText = [OCTMessageText new];
    messageText.text = text;
    messageText.type = OCTToxMessageTypeNormal;
    OCTMessageAbstract *message = [OCTMessageAbstract new];
    message.messageText = messageText;
    message.groupPendingSend = YES;
    message.dateInterval = [[NSDate date] timeIntervalSince1970];
    return message;
}

#pragma mark - A flush of the group queue

- (void)testAFlushWithNothingQueuedLeavesTheToxQueueAlone
{
    NSDictionary *rig = [self rigWithQueue:@[]];

    XCTAssertTrue([rig[@"submanager"] flushPendingGroupMessagesForChat:rig[@"chat"]]);
    XCTAssertEqualObjects(rig[@"group"][@"passes"], @0, @"an empty queue waited for the tox queue");

    [rig[@"partial"] stopMocking];
}

- (void)testAQueuedTextGoesOnTheToxQueue
{
    OCTMessageAbstract *held = [self queuedText:@"salam"];
    NSDictionary *rig = [self rigWithQueue:@[held]];

    XCTAssertTrue([rig[@"submanager"] flushPendingGroupMessagesForChat:rig[@"chat"]]);
    XCTAssertEqualObjects(rig[@"sent"], @[@"salam"]);
    XCTAssertEqualObjects(rig[@"group"][@"outside"], @0, @"toxcore was called outside the tox queue");
    XCTAssertFalse(held.groupPendingSend);
    XCTAssertTrue(held.messageText.isDelivered);

    [rig[@"partial"] stopMocking];
}

- (void)testQueuedTextsWaitWhenTheLastPeerLeftAfterTheFlushLooked
{
    OCTMessageAbstract *first = [self queuedText:@"first"];
    OCTMessageAbstract *second = [self queuedText:@"second"];
    NSDictionary *rig = [self rigWithQueue:@[first, second]];
    // The flush finds a peer; by the time the first text goes, it has timed out.
    NSMutableDictionary *group = rig[@"group"];
    group[@"peers"] = @1;
    [group[@"peerScript"] addObject:@2];

    XCTAssertFalse([rig[@"submanager"] flushPendingGroupMessagesForChat:rig[@"chat"]]);
    XCTAssertEqualObjects(rig[@"sent"], @[], @"sent into a group nobody else was in any longer");
    XCTAssertTrue(first.groupPendingSend);
    XCTAssertTrue(second.groupPendingSend);

    [rig[@"partial"] stopMocking];
}

- (void)testAQueuedTextToxcoreCannotSendNowHoldsWhatWasWrittenAfterIt
{
    OCTMessageAbstract *first = [self queuedText:@"first"];
    OCTMessageAbstract *second = [self queuedText:@"second"];
    NSDictionary *rig = [self rigWithQueue:@[first, second]];
    rig[@"refusals"][@"first"] = @(OCTToxErrorGroupSendMessageDisconnected);

    XCTAssertFalse([rig[@"submanager"] flushPendingGroupMessagesForChat:rig[@"chat"]]);
    XCTAssertEqualObjects(rig[@"sent"], @[], @"the second text overtook the first");
    XCTAssertTrue(first.groupPendingSend);
    XCTAssertTrue(second.groupPendingSend);

    [rig[@"partial"] stopMocking];
}

- (void)testAQueuedTextToxcoreRefusesForGoodHoldsNothingUp
{
    OCTMessageAbstract *refused = [self queuedText:@"too long"];
    OCTMessageAbstract *after = [self queuedText:@"after"];
    NSDictionary *rig = [self rigWithQueue:@[refused, after]];
    rig[@"refusals"][@"too long"] = @(OCTToxErrorGroupSendMessageTooLong);

    XCTAssertTrue([rig[@"submanager"] flushPendingGroupMessagesForChat:rig[@"chat"]]);
    XCTAssertEqualObjects(rig[@"sent"], @[@"after"], @"a text toxcore will never take held up the next one");
    XCTAssertTrue(refused.groupPendingSend, @"kept, as before");
    XCTAssertFalse(after.groupPendingSend);

    [rig[@"partial"] stopMocking];
}

#pragma mark - A new message and what waits for the group

- (void)testANewMessageGoesAfterWhatWaitedForTheGroup
{
    OCTMessageAbstract *held = [self queuedText:@"first"];
    NSDictionary *rig = [self rigWithQueue:@[held]];

    OCTMessageAbstract *message = [self sendText:@"second" withRig:rig];
    XCTAssertEqualObjects(rig[@"sent"], (@[@"first", @"second"]), @"the new message overtook the one that waited");
    XCTAssertFalse(held.groupPendingSend);
    XCTAssertFalse(message.groupPendingSend, @"queued although the group could take it");

    [rig[@"partial"] stopMocking];
}

- (void)testANewMessageWaitsBehindWhatNobodyCanGetYet
{
    OCTMessageAbstract *held = [self queuedText:@"first"];
    NSDictionary *rig = [self rigWithQueue:@[held]];
    NSMutableDictionary *group = rig[@"group"];
    group[@"status"] = @0;
    group[@"peers"] = @1;

    OCTMessageAbstract *message = [self sendText:@"second" withRig:rig];
    XCTAssertEqualObjects(rig[@"sent"], @[]);
    XCTAssertTrue(message.groupPendingSend);

    // Somebody is there: both go, in the order they were written.
    group[@"peers"] = @2;
    XCTAssertTrue([rig[@"submanager"] flushPendingGroupMessagesForChat:rig[@"chat"]]);
    XCTAssertEqualObjects(rig[@"sent"], (@[@"first", @"second"]));

    [rig[@"partial"] stopMocking];
}

- (void)testATextToxcoreRefusedForGoodDoesNotHoldUpANewMessage
{
    OCTMessageAbstract *refused = [self queuedText:@"too long"];
    NSDictionary *rig = [self rigWithQueue:@[refused]];
    rig[@"refusals"][@"too long"] = @(OCTToxErrorGroupSendMessageTooLong);

    OCTMessageAbstract *message = [self sendText:@"next" withRig:rig];
    XCTAssertEqualObjects(rig[@"sent"], @[@"next"]);
    XCTAssertFalse(message.groupPendingSend, @"the new message waited behind one that can never go");

    [rig[@"partial"] stopMocking];
}

- (void)testARefusalOfANewMessageSentAfterTheQueueReachesTheCaller
{
    OCTMessageAbstract *held = [self queuedText:@"first"];
    NSDictionary *rig = [self rigWithQueue:@[held]];
    rig[@"refusals"][@"second"] = @(OCTToxErrorGroupSendMessagePermissions);

    __block NSError *refusal = nil;
    [rig[@"submanager"] sendMessageToChat:rig[@"chat"] text:@"second" type:OCTToxMessageTypeNormal successBlock:^(OCTMessageAbstract *message) {
        XCTFail(@"a message toxcore refused was taken: %@", message);
    } failureBlock:^(NSError *error) {
        refusal = error;
    }];
    XCTAssertEqual(refusal.code, OCTToxErrorGroupSendMessagePermissions);
    XCTAssertEqualObjects(rig[@"sent"], @[@"first"]);
    XCTAssertEqual([rig[@"queue"] count], 1u, @"the refused message was queued");

    [rig[@"partial"] stopMocking];
}

- (void)testATextLongerThanAGroupMessageIsRefusedEvenWhenItWouldWait
{
    NSDictionary *rig = [self rigWithQueue:@[]];
    NSMutableDictionary *group = rig[@"group"];
    group[@"status"] = @0;
    group[@"peers"] = @1;
    NSString *fits = [@"" stringByPaddingToLength:kOCTToxGroupMaxMessageLength withString:@"a" startingAtIndex:0];
    NSString *tooLong = [fits stringByAppendingString:@"a"];

    __block NSError *refusal = nil;
    [rig[@"submanager"] sendMessageToChat:rig[@"chat"] text:tooLong type:OCTToxMessageTypeNormal successBlock:^(OCTMessageAbstract *message) {
        XCTFail(@"a text toxcore can never take was kept to wait: %@", message);
    } failureBlock:^(NSError *error) {
        refusal = error;
    }];
    XCTAssertEqualObjects(refusal.domain, kOCTToxErrorDomain);
    XCTAssertEqual(refusal.code, OCTToxErrorGroupSendMessageTooLong);
    XCTAssertEqual([rig[@"queue"] count], 0u);

    // One that fits still waits for somebody to get it.
    OCTMessageAbstract *waiting = [self sendText:fits withRig:rig];
    XCTAssertTrue(waiting.groupPendingSend);
    XCTAssertEqualObjects(rig[@"sent"], @[]);

    [rig[@"partial"] stopMocking];
}

- (OCTMessageAbstract *)sendText:(NSString *)text withRig:(NSDictionary *)rig
{
    __block OCTMessageAbstract *answer = nil;
    [rig[@"submanager"] sendMessageToChat:rig[@"chat"] text:text type:OCTToxMessageTypeNormal successBlock:^(OCTMessageAbstract *message) {
        answer = message;
    } failureBlock:^(NSError *error) {
        XCTFail(@"the message was refused: %@", error);
    }];
    XCTAssertNotNil(answer, @"the send did not answer");
    return answer;
}

/**
 * Group 3, with two peers (this client among them) until "group" says otherwise ("peerScript" answers the
 * next peer counts first), whose queue holds what of "queue" is still queued, in its order, as the query
 * does. toxcore takes every text, into "sent", unless "refusals" names the error code it refuses it with;
 * "passes" counts the passes on the tox queue, "outside" the calls into toxcore made outside one. A text
 * queued is added to "queue", and so is a text stored as sent at once. The file transfer keeps the
 * completion of each send in "completions" instead of sending. Keeps every mock alive: the submanager
 * holds its data source weakly.
 */
- (NSDictionary *)rigWithQueue:(NSArray<OCTMessageAbstract *> *)messages
{
    NSMutableArray<OCTMessageAbstract *> *queue = [messages mutableCopy];
    NSMutableDictionary *group = [@{
        @"status": @1,
        @"peers": @2,
        @"peerScript": [NSMutableArray new],
        @"passes": @0,
        @"outside": @0,
        @"onToxQueue": @NO,
    } mutableCopy];
    NSMutableArray<NSString *> *sent = [NSMutableArray new];
    NSMutableDictionary<NSString *, NSNumber *> *refusals = [NSMutableDictionary new];
    void (^noteCall)(void) = ^{
        if (! [group[@"onToxQueue"] boolValue]) {
            group[@"outside"] = @([group[@"outside"] integerValue] + 1);
        }
    };

    id dataSource = OCMProtocolMock(@protocol(OCTSubmanagerDataSource));
    id tox = OCMClassMock([OCTTox class]);
    id realmManager = OCMClassMock([OCTRealmManager class]);
    id transfer = OCMClassMock([OCTNgcGroupFileTransfer class]);
    OCMStub([dataSource managerGetTox]).andReturn(tox);
    OCMStub([dataSource managerGetRealmManager]).andReturn(realmManager);
    OCMStub([dataSource managerIsToxConnected]).andReturn(YES);
    OCMStub([tox performSyncBlockOnToxQueue:[OCMArg any]]).andDo(^(NSInvocation *invocation) {
        void (^__unsafe_unretained block)(void) = nil;
        [invocation getArgument:&block atIndex:2];
        // A pass inside a pass runs inline, as on the tox queue itself.
        BOOL outer = ! [group[@"onToxQueue"] boolValue];
        if (outer) {
            group[@"passes"] = @([group[@"passes"] integerValue] + 1);
            group[@"onToxQueue"] = @YES;
        }
        block();
        if (outer) {
            group[@"onToxQueue"] = @NO;
        }
    });

    [[[[tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        noteCall();
        int32_t status = [group[@"status"] intValue];
        [invocation setReturnValue:&status];
    }] groupConnectionStatusForGroupNumber:0 error:[OCMArg anyObjectRef]];
    [[[[tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        noteCall();
        NSMutableArray<NSNumber *> *script = group[@"peerScript"];
        uint32_t peers = [group[@"peers"] unsignedIntValue];
        if (script.count > 0) {
            peers = script.firstObject.unsignedIntValue;
            [script removeObjectAtIndex:0];
        }
        [invocation setReturnValue:&peers];
    }] groupPeerCountForGroupNumber:0 error:[OCMArg anyObjectRef]];
    [[[[tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        noteCall();
        __unsafe_unretained NSString *text = nil;
        NSError *__autoreleasing *errorPointer = NULL;
        [invocation getArgument:&text atIndex:2];
        [invocation getArgument:&errorPointer atIndex:6];
        NSNumber *refusal = refusals[text];
        BOOL result = (refusal == nil);
        if (result) {
            [sent addObject:[text copy]];
        }
        else if (errorPointer) {
            *errorPointer = [NSError errorWithDomain:kOCTToxErrorDomain code:refusal.integerValue userInfo:nil];
        }
        [invocation setReturnValue:&result];
    }] groupSendMessage:[OCMArg any] type:0 groupNumber:0 messageId:NULL error:[OCMArg anyObjectRef]];

    // Every answer is kept: the invocation does not hold what it returns.
    NSMutableArray<NSArray *> *answers = [NSMutableArray new];
    OCMStub([realmManager pendingGroupMessagesForChat:[OCMArg any]]).andDo(^(NSInvocation *invocation) {
        NSPredicate *queued = [NSPredicate predicateWithBlock:^BOOL(OCTMessageAbstract *message, NSDictionary *bindings) {
            return message.groupPendingSend;
        }];
        NSArray *answer = [queue filteredArrayUsingPredicate:queued];
        [answers addObject:answer];
        __unsafe_unretained NSArray *result = answer;
        [invocation setReturnValue:&result];
    });
    OCMStub([realmManager updateObject:[OCMArg any] withBlock:[OCMArg any]]).andDo(^(NSInvocation *invocation) {
        __unsafe_unretained id object = nil;
        void (^__unsafe_unretained block)(id) = nil;
        [invocation getArgument:&object atIndex:2];
        [invocation getArgument:&block atIndex:3];
        block(object);
    });
    [[[[realmManager stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        __unsafe_unretained OCTMessageAbstract *message = nil;
        [invocation getArgument:&message atIndex:2];
        message.groupPendingSend = NO;
        message.messageText.isDelivered = YES;
    }] markGroupPendingMessageSent:[OCMArg any] messageId:0];
    [[[[realmManager stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        __unsafe_unretained NSString *text = nil;
        [invocation getArgument:&text atIndex:2];
        OCTMessageAbstract *message = [self queuedText:text];
        [queue addObject:message];
        __unsafe_unretained id result = message;
        [invocation setReturnValue:&result];
    }] addGroupPendingMessageWithText:[OCMArg any] type:0 chat:[OCMArg any]];
    [[[[realmManager stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        __unsafe_unretained NSString *text = nil;
        [invocation getArgument:&text atIndex:2];
        OCTMessageAbstract *message = [self queuedText:text];
        message.groupPendingSend = NO;
        message.messageText.isDelivered = YES;
        [queue addObject:message];
        __unsafe_unretained id result = message;
        [invocation setReturnValue:&result];
    }] addGroupMessageWithText:[OCMArg any] type:0 chat:[OCMArg any] peerId:0 peerName:[OCMArg any] messageId:0];

    NSMutableArray *completions = [NSMutableArray new];
    [[[[transfer stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        void (^__unsafe_unretained completion)(BOOL, NSError *) = nil;
        [invocation getArgument:&completion atIndex:6];
        [completions addObject:[completion copy]];
    }] sendFileAtPath:[OCMArg any] groupNumber:0 msgIdHex:[OCMArg any] progress:[OCMArg any] completion:[OCMArg any]];

    OCTSubmanagerGroupsImpl *submanager = [OCTSubmanagerGroupsImpl new];
    submanager.dataSource = dataSource;
    [submanager setValue:transfer forKey:@"fileTransfer"];
    id partial = OCMPartialMock(submanager);
    OCMStub([partial defaultGroupPeerName]).andReturn(@"me");
    OCMStub([partial prepareChatForGroupActivity:[OCMArg any] peerName:[OCMArg any] error:[OCMArg anyObjectRef]]).andReturn(YES);
    OCMStub([partial precheckCanSendInChat:[OCMArg any] error:[OCMArg anyObjectRef]]).andReturn(YES);
    [[[partial stub] ignoringNonObjectArgs] scheduleHistSyncBroadcastForGroupNumber:0];

    OCTChat *chat = [OCTChat new];
    chat.isGroup = YES;
    chat.groupNumber = 3;

    return @{
        @"submanager": submanager,
        @"partial": partial,
        @"chat": chat,
        @"group": group,
        @"queue": queue,
        @"sent": sent,
        @"refusals": refusals,
        @"completions": completions,
        @"mocks": @[dataSource, tox, realmManager, transfer, answers],
    };
}

#pragma mark - A group file that comes slowly

// KHANDAQ (0.2.56): an incoming chunked file is dropped after 24 silent rounds of 2.5 s IN A ROW (a minute).
// The count never went back to zero when a chunk came, so a file paced for a big group (a chunk every few
// seconds) was dropped half way. Its requests for missing chunks go to the whole group, so one file still
// sends 24 at most.

- (void)testAGroupFileThatKeepsComingSlowlyIsNotDropped
{
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *transfer = [self stallTestTransferCollectingPackets:packets];
    id assembly = [self stallTestAssemblyWithChunks:100];
    transfer.assemblies[@"slow"] = assembly;

    // A chunk, then two rounds without one, forty times: eighty silent rounds in all, never three in a row.
    for (int chunk = 0; chunk < 40; chunk++) {
        [self stallTestAssembly:assembly receiveChunk:chunk];

        for (int round = 0; round < 3; round++) {
            [transfer nackTickForAssembly:assembly assemblyKey:@"slow"];
        }
    }

    XCTAssertEqual(transfer.assemblies[@"slow"], assembly);
    XCTAssertEqual(packets.count, 24u);

    for (NSData *packet in packets) {
        XCTAssertGreaterThan(packet.length, 7u);
        XCTAssertEqual(((const uint8_t *)packet.bytes)[7], 0x14);
    }
}

- (void)testAGroupFileSilentForAMinuteInARowIsDropped
{
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *transfer = [self stallTestTransferCollectingPackets:packets];
    id assembly = [self stallTestAssemblyWithChunks:100];
    transfer.assemblies[@"silent"] = assembly;

    [self stallTestAssembly:assembly receiveChunk:0];
    [transfer nackTickForAssembly:assembly assemblyKey:@"silent"];

    for (int round = 0; round < 24; round++) {
        [transfer nackTickForAssembly:assembly assemblyKey:@"silent"];
    }

    XCTAssertEqual(transfer.assemblies[@"silent"], assembly);
    XCTAssertEqual(packets.count, 24u);

    [transfer nackTickForAssembly:assembly assemblyKey:@"silent"];

    XCTAssertNil(transfer.assemblies[@"silent"]);
    XCTAssertEqual(packets.count, 24u);
}

// KHANDAQ (0.2.57): a file dropped that way is told, once, on the main queue, with its group and its id: nothing
// else ended the card that said it was coming.
- (void)testAGroupFileSilentForAMinuteInARowIsReportedDroppedOnce
{
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *transfer = [self stallTestTransferCollectingPackets:packets];
    NSMutableArray<NSString *> *dropped = [NSMutableArray new];
    transfer.incomingDroppedBlock = ^(uint32_t groupNumber, NSString *senderPublicKeyHex, NSString *msgIdHex) {
        XCTAssertTrue([NSThread isMainThread]);
        XCTAssertNil(senderPublicKeyHex);
        [dropped addObject:[NSString stringWithFormat:@"%u:%@", groupNumber, msgIdHex.lowercaseString]];
    };
    id assembly = [self stallTestAssemblyWithChunks:100];
    transfer.assemblies[@"silent"] = assembly;

    [self stallTestAssembly:assembly receiveChunk:0];

    for (int round = 0; round < 25; round++) {
        [transfer nackTickForAssembly:assembly assemblyKey:@"silent"];
    }

    [self stallTestRunMainQueue];
    XCTAssertEqual(dropped.count, 0u, @"a file not yet given up was told dropped");

    for (int round = 0; round < 3; round++) {
        [transfer nackTickForAssembly:assembly assemblyKey:@"silent"];
    }

    [self stallTestRunMainQueue];
    XCTAssertNil(transfer.assemblies[@"silent"]);
    NSString *zeroId = [@"" stringByPaddingToLength:64 withString:@"0" startingAtIndex:0];
    XCTAssertEqualObjects(dropped, @[[@"5:" stringByAppendingString:zeroId]]);
}

#pragma mark - The name of a group file

// KHANDAQ (0.2.57, SPEC-community risk 3): the risk is a name whose UTF-16 length is taken for its count of bytes.
// Not for a group file: its name goes as UTF-8 in a field of 255 bytes, ended by zeroes, and is read back by its
// bytes, so a name in Cyrillic of up to 255 bytes arrives whole. A longer one is cut on a whole character with its
// extension kept, as for a file offered to a friend (OCTToxFileNameBytes); cut in the middle of a letter, it did not
// read back as UTF-8 and came as "file".

- (void)testAGroupFileCarriesANameInCyrillicWhole
{
    NSString *outbox = [self nameTestDirectory];
    NSString *inbox = [self nameTestDirectory];
    NSString *name = @"Отчёт.pdf";
    NSString *path = [outbox stringByAppendingPathComponent:name];
    XCTAssertTrue([[@"%PDF-1.4" dataUsingEncoding:NSUTF8StringEncoding] writeToFile:path atomically:YES]);

    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self stallTestTransferCollectingPackets:packets];
    XCTestExpectation *sent = [self expectationWithDescription:@"the file went"];
    [sender sendFileAtPath:path groupNumber:5 msgIdHex:[OCTNgcGroupFileTransfer generateMsgIdHex] progress:nil completion:^(BOOL success, NSError *error) {
        XCTAssertTrue(success, @"%@", error);
        [sent fulfill];
    }];
    [self waitForExpectations:@[sent] timeout:5.0];
    XCTAssertEqual(packets.count, 1u);

    // After the magic, the type, the msg id and the time: the 14 bytes of the 9 characters, then zeroes.
    NSData *bytes = [name dataUsingEncoding:NSUTF8StringEncoding];
    XCTAssertEqual(bytes.length, 14u);
    NSData *field = [packets.firstObject subdataWithRange:NSMakeRange(44, 255)];
    XCTAssertEqualObjects([field subdataWithRange:NSMakeRange(0, bytes.length)], bytes);
    XCTAssertEqualObjects([field subdataWithRange:NSMakeRange(bytes.length, 255 - bytes.length)],
                          [NSMutableData dataWithLength:255 - bytes.length]);

    // Another member reads it back whole.
    __block NSString *received;
    XCTestExpectation *came = [self expectationWithDescription:@"the file came"];
    OCTNgcGroupFileTransfer *receiver = [[OCTNgcGroupFileTransfer alloc] initWithSendPacketBlock:^BOOL (uint32_t groupNumber, NSData *packet, NSError **error) {
        return YES;
    } incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
        return inbox;
    } incomingBeginBlock:nil incomingCompleteBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                                                    NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
        received = fileName;
        [came fulfill];
    } transferProgressBlock:nil];
    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets.firstObject];
    [self waitForExpectations:@[came] timeout:5.0];
    XCTAssertEqualObjects(received, name);

    [[NSFileManager defaultManager] removeItemAtPath:outbox error:nil];
    [[NSFileManager defaultManager] removeItemAtPath:inbox error:nil];
}

- (void)testALongGroupFileNameKeepsItsExtensionAndIsCutOnAWholeCharacter
{
    OCTNgcGroupFileTransfer *transfer = [self stallTestTransferCollectingPackets:[NSMutableArray new]];
    // Two bytes a letter: 200 letters and ".pdf" make 404 bytes; 125 of them and ".pdf" make 254, one more 256.
    NSString *letters = [@"" stringByPaddingToLength:200 withString:@"я" startingAtIndex:0];
    NSMutableData *field = [NSMutableData new];
    [transfer appendFilename:[letters stringByAppendingString:@".pdf"] toData:field];

    XCTAssertEqual(field.length, 255u, @"the field keeps its size");
    XCTAssertEqualObjects([transfer utf8FilenameFromData:field offset:0 length:255],
                          [[letters substringToIndex:125] stringByAppendingString:@".pdf"]);

    // A name that fits goes whole, an empty one as zeroes the other side reads as "file".
    NSMutableData *whole = [NSMutableData new];
    [transfer appendFilename:@"Отчёт.pdf" toData:whole];
    XCTAssertEqualObjects([transfer utf8FilenameFromData:whole offset:0 length:255], @"Отчёт.pdf");
    NSMutableData *empty = [NSMutableData new];
    [transfer appendFilename:@"" toData:empty];
    XCTAssertEqualObjects(empty, [NSMutableData dataWithLength:255]);
}

#pragma mark - Where a group file is stored

// KHANDAQ (0.2.57, SPEC-community item 6): a file that comes in a group is never stored hidden (the person would never
// see it, nor the cleanup of Khandaq Community), and never under the name of a file a message of the group still
// names: once those bytes had gone, to make room or with a transfer that stopped, the new file landed on the old
// path and showed under the old message.

- (void)testAGroupFileIsNeverStoredHiddenNorUnderTheNameOfAnotherMessage
{
    NSString *folder = [self nameTestDirectory];

    XCTAssertEqualObjects([[OCTFileTools createNewGroupFilePathInDirectory:folder fileName:@".profile.jpg" namesInUse:nil] lastPathComponent],
                          @"_profile.jpg");
    XCTAssertEqualObjects([[OCTFileTools createNewGroupFilePathInDirectory:folder fileName:@"..." namesInUse:nil] lastPathComponent],
                          @"unsafe_filename");
    // A file of a friend keeps its name as it came.
    XCTAssertEqualObjects([[OCTFileTools createNewFilePathInDirectory:folder fileName:@".profile.jpg"] lastPathComponent], @".profile.jpg");

    // "photo.jpg" is gone from the disk, and its message still names it.
    NSSet<NSString *> *inUse = [NSSet setWithObjects:@"photo.jpg", @"photo 2.jpg", nil];
    XCTAssertEqualObjects([[OCTFileTools createNewGroupFilePathInDirectory:folder fileName:@"photo.jpg" namesInUse:inUse] lastPathComponent],
                          @"photo 3.jpg");
    XCTAssertEqualObjects([[OCTFileTools createNewGroupFilePathInDirectory:folder fileName:@"Photo.JPG" namesInUse:inUse] lastPathComponent],
                          @"Photo 3.JPG", @"in any case of its letters");
    // A name no message gives but a file on the disk has is taken as before.
    XCTAssertTrue([[NSData data] writeToFile:[folder stringByAppendingPathComponent:@"photo 3.jpg"] atomically:YES]);
    XCTAssertEqualObjects([[OCTFileTools createNewGroupFilePathInDirectory:folder fileName:@"photo.jpg" namesInUse:inUse] lastPathComponent],
                          @"photo 4.jpg");
    XCTAssertEqualObjects([[OCTFileTools createNewGroupFilePathInDirectory:folder fileName:@"other.jpg" namesInUse:inUse] lastPathComponent],
                          @"other.jpg");

    [[NSFileManager defaultManager] removeItemAtPath:folder error:nil];
}

- (void)testAGroupFileThatComesTakesNoNameAMessageOfTheGroupStillGives
{
    NSString *outbox = [self nameTestDirectory];
    NSString *inbox = [self nameTestDirectory];
    NSMutableArray<NSString *> *received = [NSMutableArray new];
    __block NSUInteger asked = 0;
    __block XCTestExpectation *came = nil;
    OCTNgcGroupFileTransfer *receiver = [[OCTNgcGroupFileTransfer alloc] initWithSendPacketBlock:^BOOL (uint32_t groupNumber, NSData *packet, NSError **error) {
        return YES;
    } incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
        return inbox;
    } incomingBeginBlock:nil incomingCompleteBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                                                    NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
        XCTAssertEqualObjects([filePath stringByDeletingLastPathComponent], inbox);
        [received addObject:[filePath lastPathComponent]];
        [came fulfill];
    } transferProgressBlock:nil];
    receiver.incomingFileNamesInUseBlock = ^NSSet<NSString *> *(uint32_t groupNumber) {
        XCTAssertTrue([NSThread isMainThread]);
        asked++;
        return [NSSet setWithObject:@"report.pdf"];
    };

    for (NSString *name in @[@"report.pdf", @".hidden.txt"]) {
        NSString *path = [outbox stringByAppendingPathComponent:name];
        XCTAssertTrue([[@"%PDF-1.4" dataUsingEncoding:NSUTF8StringEncoding] writeToFile:path atomically:YES]);
        NSMutableArray<NSData *> *packets = [NSMutableArray new];
        OCTNgcGroupFileTransfer *sender = [self stallTestTransferCollectingPackets:packets];
        XCTestExpectation *sent = [self expectationWithDescription:name];
        [sender sendFileAtPath:path groupNumber:5 msgIdHex:[OCTNgcGroupFileTransfer generateMsgIdHex] progress:nil completion:^(BOOL success, NSError *error) {
            [sent fulfill];
        }];
        [self waitForExpectations:@[sent] timeout:5.0];

        came = [self expectationWithDescription:[@"came " stringByAppendingString:name]];
        [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets.firstObject];
        [self waitForExpectations:@[came] timeout:5.0];
    }

    XCTAssertEqualObjects(received, (@[@"report 2.pdf", @"_hidden.txt"]));
    XCTAssertEqual(asked, 2u, @"once a file, on the main thread");

    [[NSFileManager defaultManager] removeItemAtPath:outbox error:nil];
    [[NSFileManager defaultManager] removeItemAtPath:inbox error:nil];
}

- (void)testThePathOfAGroupFileStillComingIsKnown
{
    OCTNgcGroupFileTransfer *transfer = [self stallTestTransferCollectingPackets:[NSMutableArray new]];
    XCTAssertEqualObjects([transfer incomingFilePathsInProgress], [NSSet set]);

    id assembly = [self stallTestAssemblyWithChunks:4];
    [assembly setValue:@"/files/group/abc/clip.mp4" forKey:@"outPath"];
    [assembly setValue:@"/files/group/abc/.00ff.part" forKey:@"partialPath"];
    transfer.assemblies[@"coming"] = assembly;
    XCTAssertEqualObjects([transfer incomingFilePathsInProgress],
                          ([NSSet setWithObjects:@"/files/group/abc/clip.mp4", @"/files/group/abc/.00ff.part", nil]));

    [transfer.assemblies removeObjectForKey:@"coming"];
    XCTAssertEqualObjects([transfer incomingFilePathsInProgress], [NSSet set]);
}

// KHANDAQ (0.2.57): a group file of several chunks was made under its name at its BEGIN, as long as the whole file:
// zeros under the real name while it came (seen: 3 MiB of zeros as group/<chat>/g-a.bin while the file waited), and
// a start of the app took one that had stopped for complete, as its size was right. The chunks go to a hidden file,
// which takes the name with the last one.
- (void)testAGroupFileComesInAHiddenFileAndTakesItsNameWithItsLastChunk
{
    NSString *inbox = [self nameTestDirectory];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    NSData *content = [self groupTestContentOfLength:80000];
    NSArray<NSData *> *packets = [self groupTestPacketsSendingContent:content named:@"clip.mp4" msgIdHex:msgIdHex];
    XCTAssertEqual(packets.count, 4u, @"a BEGIN and three chunks");
    NSMutableArray<NSString *> *completed = [NSMutableArray new];
    OCTNgcGroupFileTransfer *receiver = [self groupTestReceiverIn:inbox completed:completed];
    NSString *partialName = [NSString stringWithFormat:@".%@.part", msgIdHex.lowercaseString];
    NSString *path = [inbox stringByAppendingPathComponent:@"clip.mp4"];

    for (NSUInteger i = 0; i < 3; i++) {
        [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[i]];
    }
    [self groupTestDrainQueueOf:receiver];

    XCTAssertEqualObjects([self groupTestNamesIn:inbox], @[partialName], @"something under the name while it came");
    XCTAssertEqualObjects([receiver incomingFilePathsInProgress],
                          ([NSSet setWithObjects:path, [inbox stringByAppendingPathComponent:partialName], nil]));

    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[3]];
    [self groupTestDrainQueueOf:receiver];
    [self stallTestRunMainQueue];

    XCTAssertEqualObjects(completed, @[path]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:path], content);
    XCTAssertEqualObjects([self groupTestNamesIn:inbox], @[@"clip.mp4"]);
    XCTAssertEqualObjects([receiver incomingFilePathsInProgress], [NSSet set]);

    [[NSFileManager defaultManager] removeItemAtPath:inbox error:nil];
}

- (void)testAGroupFileThatStopsComingLeavesNothing
{
    NSString *inbox = [self nameTestDirectory];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    NSArray<NSData *> *packets = [self groupTestPacketsSendingContent:[self groupTestContentOfLength:80000]
                                                                named:@"clip.mp4"
                                                             msgIdHex:msgIdHex];
    OCTNgcGroupFileTransfer *receiver = [self groupTestReceiverIn:inbox completed:[NSMutableArray new]];

    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[0]];
    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[1]];
    [self groupTestDrainQueueOf:receiver];
    XCTAssertEqual([self groupTestNamesIn:inbox].count, 1u);

    NSString *key = receiver.assemblies.allKeys.firstObject;
    id assembly = receiver.assemblies[key];
    XCTAssertNotNil(assembly);
    dispatch_sync([receiver valueForKey:@"sendQueue"], ^{
        for (int round = 0; round < 27; round++) {
            [receiver nackTickForAssembly:assembly assemblyKey:key];
        }
    });

    XCTAssertNil(receiver.assemblies[key]);
    XCTAssertEqualObjects([self groupTestNamesIn:inbox], @[], @"a file that stopped left its bytes");

    [[NSFileManager defaultManager] removeItemAtPath:inbox error:nil];
}

// A file that came to have the name while this one came stays, and this one ends under a name of its own.
- (void)testAGroupFileWhoseNameAnotherFileTookMeanwhileEndsUnderAnother
{
    NSString *inbox = [self nameTestDirectory];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    NSData *content = [self groupTestContentOfLength:80000];
    NSArray<NSData *> *packets = [self groupTestPacketsSendingContent:content named:@"clip.mp4" msgIdHex:msgIdHex];
    NSMutableArray<NSString *> *completed = [NSMutableArray new];
    OCTNgcGroupFileTransfer *receiver = [self groupTestReceiverIn:inbox completed:completed];

    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[0]];
    [self groupTestDrainQueueOf:receiver];
    NSData *other = [@"someone else" dataUsingEncoding:NSASCIIStringEncoding];
    NSString *otherPath = [inbox stringByAppendingPathComponent:@"clip.mp4"];
    XCTAssertTrue([other writeToFile:otherPath atomically:YES]);

    for (NSUInteger i = 1; i < packets.count; i++) {
        [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[i]];
    }
    [self groupTestDrainQueueOf:receiver];
    [self stallTestRunMainQueue];

    NSString *path = [inbox stringByAppendingPathComponent:@"clip 2.mp4"];
    XCTAssertEqualObjects(completed, @[path]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:path], content);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:otherPath], other, @"the other file was taken");
    XCTAssertEqualObjects([self groupTestNamesIn:inbox], (@[@"clip 2.mp4", @"clip.mp4"]));

    [[NSFileManager defaultManager] removeItemAtPath:inbox error:nil];
}

// Two files of one name coming at once: the second is not given the name of the first, which is not on disk yet.
- (void)testTwoGroupFilesOfOneNameComingAtOnceTakeTwoNames
{
    NSString *inbox = [self nameTestDirectory];
    NSString *firstId = [OCTNgcGroupFileTransfer generateMsgIdHex];
    NSString *secondId = [OCTNgcGroupFileTransfer generateMsgIdHex];
    NSData *firstContent = [self groupTestContentOfLength:80000];
    NSData *secondContent = [self groupTestContentOfLength:75000];
    NSArray<NSData *> *first = [self groupTestPacketsSendingContent:firstContent named:@"clip.mp4" msgIdHex:firstId];
    NSArray<NSData *> *second = [self groupTestPacketsSendingContent:secondContent named:@"clip.mp4" msgIdHex:secondId];
    NSMutableArray<NSString *> *completed = [NSMutableArray new];
    OCTNgcGroupFileTransfer *receiver = [self groupTestReceiverIn:inbox completed:completed];

    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:first[0]];
    [receiver handleIncomingPacketWithGroupNumber:5 peerId:3 peerPublicKeyHex:nil data:second[0]];
    for (NSUInteger i = 1; i < second.count; i++) {
        [receiver handleIncomingPacketWithGroupNumber:5 peerId:3 peerPublicKeyHex:nil data:second[i]];
    }
    for (NSUInteger i = 1; i < first.count; i++) {
        [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:first[i]];
    }
    [self groupTestDrainQueueOf:receiver];
    [self stallTestRunMainQueue];

    NSString *firstPath = [inbox stringByAppendingPathComponent:@"clip.mp4"];
    NSString *secondPath = [inbox stringByAppendingPathComponent:@"clip 2.mp4"];
    XCTAssertEqualObjects(completed, (@[secondPath, firstPath]));
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:firstPath], firstContent);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:secondPath], secondContent);
    XCTAssertEqualObjects([self groupTestNamesIn:inbox], (@[@"clip 2.mp4", @"clip.mp4"]));

    [[NSFileManager defaultManager] removeItemAtPath:inbox error:nil];
}

// KHANDAQ (0.2.57): Android sends the BEGIN of a file once more with each resend it serves. While the file comes, its
// assembly stays as it is.
- (void)testABeginOfAGroupFileThatComesAgainLeavesItsAssemblyAsItIs
{
    NSString *inbox = [self nameTestDirectory];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    NSData *content = [self groupTestContentOfLength:80000];
    NSArray<NSData *> *packets = [self groupTestPacketsSendingContent:content named:@"clip.mp4" msgIdHex:msgIdHex];
    NSMutableArray<NSString *> *completed = [NSMutableArray new];
    OCTNgcGroupFileTransfer *receiver = [self groupTestReceiverIn:inbox completed:completed];

    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[0]];
    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[1]];
    [self groupTestDrainQueueOf:receiver];
    id assembly = receiver.assemblies.allValues.firstObject;
    XCTAssertNotNil(assembly);

    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[0]];
    [self groupTestDrainQueueOf:receiver];
    XCTAssertEqual(receiver.assemblies.count, 1u);
    XCTAssertEqual(receiver.assemblies.allValues.firstObject, assembly, @"the BEGIN opened the file anew");
    XCTAssertEqual([[assembly valueForKey:@"receivedCount"] intValue], 1);

    for (NSUInteger i = 2; i < packets.count; i++) {
        [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[i]];
    }
    [self groupTestDrainQueueOf:receiver];
    [self stallTestRunMainQueue];

    NSString *path = [inbox stringByAppendingPathComponent:@"clip.mp4"];
    XCTAssertEqualObjects(completed, @[path]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:path], content);
    XCTAssertEqualObjects([self groupTestNamesIn:inbox], @[@"clip.mp4"]);

    [[NSFileManager defaultManager] removeItemAtPath:inbox error:nil];
}

// ... and a resend queued behind others can go after the file was complete here. Its BEGIN opened a new assembly,
// which took the whole file again under a name of its own (seen live: "qa057-grp-3m 2.bin", which no message showed).
- (void)testABeginOfAGroupFileCompleteHereAlreadyOpensNothing
{
    NSString *inbox = [self nameTestDirectory];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    NSData *content = [self groupTestContentOfLength:80000];
    NSArray<NSData *> *packets = [self groupTestPacketsSendingContent:content named:@"clip.mp4" msgIdHex:msgIdHex];
    NSMutableArray<NSString *> *completed = [NSMutableArray new];
    OCTNgcGroupFileTransfer *receiver = [self groupTestReceiverIn:inbox completed:completed];
    __block uint64_t now = kPaceTestsT0;
    [receiver setNowMsBlock:^uint64_t {
        return now;
    }];
    NSString *path = [inbox stringByAppendingPathComponent:@"clip.mp4"];

    for (NSData *packet in packets) {
        [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packet];
    }
    [self groupTestDrainQueueOf:receiver];
    [self stallTestRunMainQueue];
    XCTAssertEqualObjects(completed, @[path]);

    // The sender's resend, ten minutes on: the BEGIN, then the chunks.
    now += 10 * 60 * 1000;
    for (NSData *packet in packets) {
        [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packet];
    }
    [self groupTestDrainQueueOf:receiver];
    [self stallTestRunMainQueue];

    XCTAssertEqualObjects(completed, @[path], @"the file came twice");
    XCTAssertEqualObjects([self groupTestNamesIn:inbox], @[@"clip.mp4"]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:path], content);
    XCTAssertEqual(receiver.assemblies.count, 0u);
    XCTAssertEqual([[receiver valueForKey:@"orphanChunks"] count], 0u, @"its chunks wait for a BEGIN");

    // Half an hour after it was complete the file is forgotten, and its id opens a file again.
    now += 20 * 60 * 1000 + 1;
    [receiver handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:packets[0]];
    [self groupTestDrainQueueOf:receiver];
    XCTAssertEqual(receiver.assemblies.count, 1u);

    receiver = nil;
    [[NSFileManager defaultManager] removeItemAtPath:inbox error:nil];
}

- (void)testTheGroupFilesRememberedCompleteAreBounded
{
    OCTNgcGroupFileTransfer *transfer = [self stallTestTransferCollectingPackets:[NSMutableArray new]];
    NSDictionary<NSString *, NSNumber *> *remembered = [transfer valueForKey:@"completedAssemblyKeys"];

    @synchronized (transfer.assemblies) {
        for (uint64_t i = 0; i < 600; i++) {
            [transfer rememberCompletedAssemblyKey:[NSString stringWithFormat:@"5:%llu", i] atMs:kPaceTestsT0 + i];
        }
    }

    XCTAssertEqual(remembered.count, 512u);
    XCTAssertNil(remembered[@"5:87"], @"the files complete first go first");
    XCTAssertNotNil(remembered[@"5:88"]);
    XCTAssertNotNil(remembered[@"5:599"]);
}

#pragma mark - A request for missing chunks

// KHANDAQ (0.2.57): Android asks the sender for the chunks it misses with a bitmap: the file's count of chunks (u32),
// then a bit a chunk, set when it is missing (seen: 55 bytes for a file of 86 chunks). iOS read only its own form, a
// list of indices, took the bitmap for a list too short for its count, and dropped it: an Android member never got
// from an iPhone the chunks it missed.

- (void)testAnAndroidRequestForMissingChunksGetsThem
{
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self stallTestTransferCollectingPackets:packets];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:4 * kGroupTestChunkPayload + 10] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    XCTAssertEqual(packets.count, 6u, @"a BEGIN and five chunks");
    [packets removeAllObjects];

    // Chunks 1 and 4 of the 5 are missing: 0b00010010.
    const uint8_t bitmap[] = {0x12};
    NSData *request = [self groupTestRequestFor:msgIdHex count:5 then:[NSData dataWithBytes:bitmap length:sizeof(bitmap)]];
    XCTAssertEqual(request.length, 45u);
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:request];
    [self groupTestDrainQueueOf:sender];

    XCTAssertEqualObjects([self groupTestChunkIndicesOf:packets], (@[@1, @4]));
    XCTAssertEqual(packets[0].length, 48u + kGroupTestChunkPayload);
    XCTAssertEqual(packets[1].length, 48u + 10u, @"the last chunk is short");
}

- (void)testAnAndroidRequestForTheChunksOfAFileOfManyGetsThem
{
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self stallTestTransferCollectingPackets:packets];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:11 * kGroupTestChunkPayload] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    XCTAssertEqual(packets.count, 12u);
    [packets removeAllObjects];

    // Chunks 0, 8 and 10 of 11: two bytes, low bit first.
    const uint8_t bitmap[] = {0x01, 0x05};
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil
                                           data:[self groupTestRequestFor:msgIdHex count:11 then:[NSData dataWithBytes:bitmap length:sizeof(bitmap)]]];
    [self groupTestDrainQueueOf:sender];

    XCTAssertEqualObjects([self groupTestChunkIndicesOf:packets], (@[@0, @8, @10]));
}

- (void)testARequestListingTheMissingChunksStillGetsThem
{
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self stallTestTransferCollectingPackets:packets];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:4 * kGroupTestChunkPayload + 10] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    [packets removeAllObjects];

    NSMutableData *list = [NSMutableData new];
    for (NSNumber *index in @[@3, @0, @3, @9]) {
        uint32_t value = CFSwapInt32HostToBig(index.unsignedIntValue);
        [list appendBytes:&value length:4];
    }
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:[self groupTestRequestFor:msgIdHex count:4 then:list]];
    [self groupTestDrainQueueOf:sender];

    XCTAssertEqualObjects([self groupTestChunkIndicesOf:packets], (@[@0, @3]), @"once each, none past the file");
}

- (void)testARequestOfNoKnownFormGetsNothing
{
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self stallTestTransferCollectingPackets:packets];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:4 * kGroupTestChunkPayload + 10] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    [packets removeAllObjects];
    sender.sendPrivatePacketBlock = ^BOOL (uint32_t groupNumber, uint32_t peerId, NSData *packet, NSError **error) {
        XCTFail(@"a packet to peer %u alone", peerId);
        return NO;
    };
    const uint8_t all[] = {0xff, 0xff};

    // A bitmap for a file of another count of chunks, one too short for its count, and the count 0 with something
    // after it (the request for the whole file is the count 0 alone).
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:[self groupTestRequestFor:msgIdHex count:9 then:[NSData dataWithBytes:all length:2]]];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:8 peerPublicKeyHex:nil data:[self groupTestRequestFor:msgIdHex count:5 then:[NSData data]]];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:9 peerPublicKeyHex:nil data:[self groupTestRequestFor:msgIdHex count:0 then:[NSData dataWithBytes:all length:1]]];
    // A file this member did not send.
    [sender handleIncomingPacketWithGroupNumber:5 peerId:10 peerPublicKeyHex:nil
                                           data:[self groupTestRequestFor:[OCTNgcGroupFileTransfer generateMsgIdHex] count:5 then:[NSData dataWithBytes:all length:1]]];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:11 peerPublicKeyHex:nil
                                           data:[self groupTestRequestFor:[OCTNgcGroupFileTransfer generateMsgIdHex] count:0 then:[NSData data]]];
    [self groupTestDrainQueueOf:sender];

    XCTAssertEqual(packets.count, 0u);
}

// What this client asks with stays a list: the members of 0.2.56 read no other form.
- (void)testTheRequestOfAnIPhoneListsTheMissingChunks
{
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *transfer = [self stallTestTransferCollectingPackets:packets];
    id assembly = [self stallTestAssemblyWithChunks:5];
    transfer.assemblies[@"coming"] = assembly;
    [self stallTestAssembly:assembly receiveChunk:0];
    [self stallTestAssembly:assembly receiveChunk:2];

    [transfer nackTickForAssembly:assembly assemblyKey:@"coming"];
    [transfer nackTickForAssembly:assembly assemblyKey:@"coming"];

    XCTAssertEqual(packets.count, 1u);
    NSData *request = packets.firstObject;
    XCTAssertEqual(request.length, 44u + 3u * 4u);
    XCTAssertEqual(((const uint8_t *)request.bytes)[7], 0x14);
    XCTAssertEqual([self groupTestU32In:request at:40], 3u);
    XCTAssertEqual([self groupTestU32In:request at:44], 1u);
    XCTAssertEqual([self groupTestU32In:request at:48], 3u);
    XCTAssertEqual([self groupTestU32In:request at:52], 4u);
}

// KHANDAQ (0.2.57): a member that misses more chunks than one request lists (256) sends several at once: Android
// 0.2.57 does (buildListedFileRequestPackets). A limit of one request in 2 s per member and file served the first
// packet and dropped the others.
- (void)testARequestInTwoPacketsAtOnceGetsAllTheChunksItLists
{
    OCTGroupTestClock *clock = [OCTGroupTestClock new];
    clock.nowMs = kPaceTestsT0;
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self wholeFileTestSenderWithClock:clock members:1 packets:packets privatePackets:[NSMutableArray new]];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:260 * kGroupTestChunkPayload] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    XCTAssertEqual(packets.count, 261u, @"a BEGIN and 260 chunks");
    [packets removeAllObjects];

    NSMutableArray<NSNumber *> *all = [NSMutableArray new];
    for (NSUInteger i = 0; i < 260; i++) {
        [all addObject:@(i)];
    }
    NSData *first = [self wholeFileTestListRequestFor:msgIdHex chunks:[all subarrayWithRange:NSMakeRange(0, 256)]];
    NSData *second = [self wholeFileTestListRequestFor:msgIdHex chunks:[all subarrayWithRange:NSMakeRange(256, 4)]];
    XCTAssertEqual(first.length, 44u + 256u * 4u);
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:first];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:second];
    [self groupTestDrainQueueOf:sender];

    XCTAssertEqualObjects([self groupTestChunkIndicesOf:packets], all);
}

#pragma mark - The pace of what this device sends to a group

// KHANDAQ (0.2.57): iOS handed every chunk of a group file to toxcore at once (a pause of 2 ms every 8). toxcore keeps
// each of the 28 parts of a chunk in the member's send array until the member acknowledges it, and the member drops
// every part after a lost one: of a file of 86 chunks an Android member got 27, the desktop none, and the rest never
// came. The packets go at the pace of Android's NgcUploadPace and the desktop's Air::UploadPace now: 512 KiB/s, what
// this device uploads, a packet once for each member it goes to.

- (id)paceTestsPace
{
    Class paceClass = NSClassFromString(@"OCTNgcUploadPace");
    XCTAssertNotNil(paceClass);
    return [[paceClass alloc] initWithBytesPerSecond:kPaceTestsRate burstMs:250];
}

/// Packets of `kPaceTestsChunk` bytes to `members`, as fast as the pace lets them, for `durationMs`: the bytes let go.
- (uint64_t)paceTestsBytesLetGoBy:(id)pace members:(NSUInteger)members durationMs:(uint64_t)durationMs
{
    uint64_t now = kPaceTestsT0;
    uint64_t bytes = 0;
    while (now < kPaceTestsT0 + durationMs) {
        if ([pace readyAtMs:now]) {
            const uint64_t cost = [NSClassFromString(@"OCTNgcUploadPace") costOfPacketBytes:kPaceTestsChunk members:members];
            [pace spend:cost];
            bytes += cost;
        }
        else {
            now += MAX((uint64_t)1, [pace msUntilReadyAtMs:now]);
        }
    }
    return bytes;
}

- (void)testThePaceLetsChunksGoAtItsRateHoweverFastTheyAreHandedOver
{
    const uint64_t bytes = [self paceTestsBytesLetGoBy:[self paceTestsPace] members:1 durationMs:10000];
    const uint64_t expected = kPaceTestsRate * 10;
    XCTAssertGreaterThanOrEqual(bytes, expected - kPaceTestsChunk);
    XCTAssertLessThanOrEqual(bytes, expected + kPaceTestsBurst + 2 * kPaceTestsChunk);
}

- (void)testAPacketToMoreMembersCostsMore
{
    Class paceClass = NSClassFromString(@"OCTNgcUploadPace");
    XCTAssertEqual([paceClass costOfPacketBytes:kPaceTestsChunk members:8], 8 * kPaceTestsChunk);
    XCTAssertEqual([paceClass costOfPacketBytes:kPaceTestsChunk members:1], kPaceTestsChunk);
    XCTAssertEqual([paceClass costOfPacketBytes:kPaceTestsChunk members:0], kPaceTestsChunk, @"what goes out is paid for");

    const uint64_t toOne = [self paceTestsBytesLetGoBy:[self paceTestsPace] members:1 durationMs:20000] / kPaceTestsChunk;
    const uint64_t toEight = [self paceTestsBytesLetGoBy:[self paceTestsPace] members:8 durationMs:20000] / (8 * kPaceTestsChunk);
    XCTAssertLessThanOrEqual(toEight * 8, toOne + 8);
    XCTAssertGreaterThanOrEqual(toEight * 8 + 16, toOne);
}

- (void)testThePaceLetsTheFirstPacketGoAtOnceAndAPauseGivesOneBurstAtMost
{
    id pace = [self paceTestsPace];
    XCTAssertTrue([pace readyAtMs:kPaceTestsT0]);
    XCTAssertEqual([pace msUntilReadyAtMs:kPaceTestsT0], 0u);

    // An hour of silence gives back one burst, not an hour of the rate.
    [pace spend:kPaceTestsBurst];
    XCTAssertFalse([pace readyAtMs:kPaceTestsT0]);
    const uint64_t later = kPaceTestsT0 + 60 * 60 * 1000;
    XCTAssertTrue([pace readyAtMs:later]);
    [pace spend:kPaceTestsBurst];
    XCTAssertFalse([pace readyAtMs:later]);
}

- (void)testTheWaitThePaceNamesIsTheWaitItKeeps
{
    for (uint64_t debt = 0; debt <= 12 * kPaceTestsChunk; debt += 997) {
        id (^afterFirstPacket)(void) = ^id {
            id pace = [self paceTestsPace];
            XCTAssertTrue([pace readyAtMs:kPaceTestsT0]);
            [pace spend:kPaceTestsBurst + debt];
            return pace;
        };
        const uint64_t wait = [afterFirstPacket() msUntilReadyAtMs:kPaceTestsT0];
        XCTAssertGreaterThan(wait, 0u, @"debt=%llu", debt);
        // Ready when it says, and not a millisecond earlier: the sender sleeps that long, not longer.
        XCTAssertTrue([afterFirstPacket() readyAtMs:kPaceTestsT0 + wait], @"debt=%llu wait=%llu", debt, wait);
        XCTAssertFalse([afterFirstPacket() readyAtMs:kPaceTestsT0 + wait - 1], @"debt=%llu wait=%llu", debt, wait);
    }

    id pace = [self paceTestsPace];
    XCTAssertTrue([pace readyAtMs:kPaceTestsT0]);
    [pace spend:1];
    XCTAssertEqual([pace msUntilReadyAtMs:kPaceTestsT0], 0u, @"credit left names no wait");
}

- (void)testAClockThatGoesBackGivesThePaceNothing
{
    id pace = [self paceTestsPace];
    XCTAssertTrue([pace readyAtMs:kPaceTestsT0]);
    [pace spend:kPaceTestsBurst + kPaceTestsChunk];
    const uint64_t wait = [pace msUntilReadyAtMs:kPaceTestsT0];
    XCTAssertGreaterThan(wait, 0u);
    XCTAssertFalse([pace readyAtMs:kPaceTestsT0 - 10000]);
    XCTAssertFalse([pace readyAtMs:kPaceTestsT0]);
    XCTAssertTrue([pace readyAtMs:kPaceTestsT0 + wait]);
}

/// A member that sends at a clock of its own, which only its waits move: the time of each packet goes to `sentAt`.
- (OCTNgcGroupFileTransfer *)paceTestsSenderRecording:(NSMutableArray<NSNumber *> *)sentAt
                                              packets:(NSMutableArray<NSData *> *)packets
                                              members:(NSUInteger)members
                                                sleep:(void (^)(uint64_t ms))onSleep
{
    __block uint64_t now = kPaceTestsT0;
    OCTNgcGroupFileTransfer *sender = [[OCTNgcGroupFileTransfer alloc] initWithSendPacketBlock:^BOOL (uint32_t groupNumber, NSData *packet, NSError **error) {
        [sentAt addObject:@(now)];
        [packets addObject:packet];
        return YES;
    } incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
        return NSTemporaryDirectory();
    } incomingBeginBlock:nil incomingCompleteBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                                                    NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
    } transferProgressBlock:nil];
    [sender setNowMsBlock:^uint64_t {
        return now;
    }];
    [sender setSleepMsBlock:^(uint64_t ms) {
        now += ms;
        if (onSleep) {
            onSleep(ms);
        }
    }];
    sender.memberCountBlock = ^NSUInteger (uint32_t groupNumber) {
        XCTAssertFalse([NSThread isMainThread]);
        return members;
    };
    return sender;
}

- (void)testTheChunksOfAGroupFileGoAtThePaceForTheMembersOfTheGroup
{
    NSMutableArray<NSNumber *> *sentAt = [NSMutableArray new];
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self paceTestsSenderRecording:sentAt packets:packets members:2 sleep:nil];

    [self groupTestSendContent:[self groupTestContentOfLength:12 * kGroupTestChunkPayload]
                         named:@"clip.mp4"
                      msgIdHex:[OCTNgcGroupFileTransfer generateMsgIdHex]
                            by:sender];

    XCTAssertEqual(packets.count, 13u);
    // The BEGIN and two chunks fit in the burst; the rest go at the rate, each costing 37000 bytes twice.
    XCTAssertEqual(sentAt[0].unsignedLongLongValue, kPaceTestsT0);
    XCTAssertEqual(sentAt[2].unsignedLongLongValue, kPaceTestsT0);
    XCTAssertGreaterThan(sentAt[3].unsignedLongLongValue, kPaceTestsT0);
    const uint64_t costBeforeTheLast = 2 * (packets[0].length + 11 * kPaceTestsChunk);
    const uint64_t expected = (costBeforeTheLast - kPaceTestsBurst) * 1000 / kPaceTestsRate;
    const uint64_t elapsed = sentAt.lastObject.unsignedLongLongValue - kPaceTestsT0;
    XCTAssertGreaterThanOrEqual(elapsed, expected);
    XCTAssertLessThanOrEqual(elapsed, expected + 50);
    for (NSUInteger i = 4; i < sentAt.count; i++) {
        const uint64_t gap = sentAt[i].unsignedLongLongValue - sentAt[i - 1].unsignedLongLongValue;
        XCTAssertGreaterThanOrEqual(gap, 2 * kPaceTestsChunk * 1000 / kPaceTestsRate - 1, @"chunk %lu", (unsigned long)i);
    }
}

- (void)testAGroupFileCancelledWhileItWaitsForThePaceStops
{
    NSMutableArray<NSNumber *> *sentAt = [NSMutableArray new];
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    __block OCTNgcGroupFileTransfer *sender = nil;
    sender = [self paceTestsSenderRecording:sentAt packets:packets members:50 sleep:^(uint64_t ms) {
        [sender cancelSendForMsgIdHex:msgIdHex];
    }];

    NSString *outbox = [self nameTestDirectory];
    NSString *path = [outbox stringByAppendingPathComponent:@"clip.mp4"];
    XCTAssertTrue([[self groupTestContentOfLength:5 * kGroupTestChunkPayload] writeToFile:path atomically:YES]);
    __block NSError *failure = nil;
    XCTestExpectation *ended = [self expectationWithDescription:@"the send ended"];
    [sender sendFileAtPath:path groupNumber:5 msgIdHex:msgIdHex progress:nil completion:^(BOOL success, NSError *error) {
        XCTAssertFalse(success);
        failure = error;
        [ended fulfill];
    }];
    [self waitForExpectations:@[ended] timeout:5.0];

    // The BEGIN and the first chunk went in the burst; the second waited, and the cancel stopped it.
    XCTAssertEqual(packets.count, 2u);
    XCTAssertEqual(failure.code, OCTNgcGroupFileTransferErrorCancelled);
    sender = nil;
    [[NSFileManager defaultManager] removeItemAtPath:outbox error:nil];
}

- (void)testChunksAskedForAgainGoAtThePaceOnceEachHoweverOftenTheyAreAskedFor
{
    NSMutableArray<NSNumber *> *sentAt = [NSMutableArray new];
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self paceTestsSenderRecording:sentAt packets:packets members:1 sleep:nil];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:4 * kGroupTestChunkPayload + 10] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    XCTAssertEqual(packets.count, 6u);
    [packets removeAllObjects];
    [sentAt removeAllObjects];

    // Two members ask while the resend has not started: chunks 1 and 4, then 1 and 2.
    dispatch_semaphore_t held = dispatch_semaphore_create(0);
    dispatch_async([sender valueForKey:@"resendQueue"], ^{
        dispatch_semaphore_wait(held, DISPATCH_TIME_FOREVER);
    });
    const uint8_t first[] = {0x12};
    const uint8_t second[] = {0x06};
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:[self groupTestRequestFor:msgIdHex count:5 then:[NSData dataWithBytes:first length:1]]];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:8 peerPublicKeyHex:nil data:[self groupTestRequestFor:msgIdHex count:5 then:[NSData dataWithBytes:second length:1]]];
    dispatch_sync([sender valueForKey:@"sendQueue"], ^{});
    dispatch_semaphore_signal(held);
    [self groupTestDrainQueueOf:sender];

    XCTAssertEqualObjects([self groupTestChunkIndicesOf:packets], (@[@1, @2, @4]));
    XCTAssertGreaterThan(sentAt.lastObject.unsignedLongLongValue, sentAt.firstObject.unsignedLongLongValue, @"at the pace");
}

// At the pace a chunk to a big group takes seconds, and a member that hears nothing for a round asks for every chunk it
// lacks: the ones still to come are not sent twice.
- (void)testChunksTheUploadHasNotReachedAreNotSentAgainForARequest
{
    NSMutableArray<NSNumber *> *sentAt = [NSMutableArray new];
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    dispatch_semaphore_t held = dispatch_semaphore_create(0);
    dispatch_semaphore_t waiting = dispatch_semaphore_create(0);
    __block BOOL heldOnce = NO;
    OCTNgcGroupFileTransfer *sender = [self paceTestsSenderRecording:sentAt packets:packets members:1 sleep:^(uint64_t ms) {
        const char *label = dispatch_queue_get_label(DISPATCH_CURRENT_QUEUE_LABEL);
        if (! heldOnce && strcmp(label, "ngc-group-file-upload") == 0) {
            heldOnce = YES;
            dispatch_semaphore_signal(waiting);
            dispatch_semaphore_wait(held, DISPATCH_TIME_FOREVER);
        }
    }];

    NSString *outbox = [self nameTestDirectory];
    NSString *path = [outbox stringByAppendingPathComponent:@"clip.mp4"];
    XCTAssertTrue([[self groupTestContentOfLength:8 * kGroupTestChunkPayload] writeToFile:path atomically:YES]);
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    XCTestExpectation *sent = [self expectationWithDescription:@"the file went"];
    [sender sendFileAtPath:path groupNumber:5 msgIdHex:msgIdHex progress:nil completion:^(BOOL success, NSError *error) {
        XCTAssertTrue(success, @"%@", error);
        [sent fulfill];
    }];
    XCTAssertEqual(dispatch_semaphore_wait(waiting, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)), 0, @"the file never waited");

    // The burst let the BEGIN and chunks 0 to 3 go. A member asks for chunk 1, lost, and 6, still to come.
    NSArray<NSNumber *> *handedOver = [self groupTestChunkIndicesOf:[packets subarrayWithRange:NSMakeRange(1, packets.count - 1)]];
    XCTAssertEqualObjects(handedOver, (@[@0, @1, @2, @3]));
    const uint8_t bitmap[] = {0x42};
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil
                                           data:[self groupTestRequestFor:msgIdHex count:8 then:[NSData dataWithBytes:bitmap length:1]]];
    dispatch_sync([sender valueForKey:@"sendQueue"], ^{});
    dispatch_sync([sender valueForKey:@"resendQueue"], ^{});
    XCTAssertEqualObjects([self groupTestChunkIndicesOf:[packets subarrayWithRange:NSMakeRange(5, packets.count - 5)]], (@[@1]));

    dispatch_semaphore_signal(held);
    [self waitForExpectations:@[sent] timeout:5.0];
    XCTAssertEqualObjects([self groupTestChunkIndicesOf:[packets subarrayWithRange:NSMakeRange(5, packets.count - 5)]],
                          (@[@1, @4, @5, @6, @7]), @"chunk 6 once, with the file");
    [[NSFileManager defaultManager] removeItemAtPath:outbox error:nil];
}

// A file that waits for the pace holds up nothing that comes: what comes is read on a queue of its own.
- (void)testWhatComesIsReadWhileAGroupFileWaitsForThePace
{
    dispatch_semaphore_t held = dispatch_semaphore_create(0);
    dispatch_semaphore_t waiting = dispatch_semaphore_create(0);
    __block BOOL heldOnce = NO;
    NSString *inbox = [self nameTestDirectory];
    __block XCTestExpectation *came = [self expectationWithDescription:@"the file came"];
    OCTNgcGroupFileTransfer *member = [[OCTNgcGroupFileTransfer alloc] initWithSendPacketBlock:^BOOL (uint32_t groupNumber, NSData *packet, NSError **error) {
        return YES;
    } incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
        return inbox;
    } incomingBeginBlock:nil incomingCompleteBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                                                    NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
        [came fulfill];
    } transferProgressBlock:nil];
    [member setSleepMsBlock:^(uint64_t ms) {
        if (! heldOnce) {
            heldOnce = YES;
            dispatch_semaphore_signal(waiting);
            dispatch_semaphore_wait(held, DISPATCH_TIME_FOREVER);
        }
        usleep((useconds_t)(ms * 1000));
    }];

    NSString *outbox = [self nameTestDirectory];
    NSString *path = [outbox stringByAppendingPathComponent:@"clip.mp4"];
    XCTAssertTrue([[self groupTestContentOfLength:8 * kGroupTestChunkPayload] writeToFile:path atomically:YES]);
    XCTestExpectation *sent = [self expectationWithDescription:@"the file went"];
    [member sendFileAtPath:path groupNumber:5 msgIdHex:[OCTNgcGroupFileTransfer generateMsgIdHex] progress:nil completion:^(BOOL success, NSError *error) {
        XCTAssertTrue(success, @"%@", error);
        [sent fulfill];
    }];
    XCTAssertEqual(dispatch_semaphore_wait(waiting, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)), 0, @"the file never waited");

    NSArray<NSData *> *photo = [self groupTestPacketsSendingContent:[self groupTestContentOfLength:2000]
                                                             named:@"photo.jpg"
                                                          msgIdHex:[OCTNgcGroupFileTransfer generateMsgIdHex]];
    [member handleIncomingPacketWithGroupNumber:5 peerId:2 peerPublicKeyHex:nil data:photo.firstObject];
    [self waitForExpectations:@[came] timeout:5.0];

    dispatch_semaphore_signal(held);
    [self waitForExpectations:@[sent] timeout:10.0];
    [[NSFileManager defaultManager] removeItemAtPath:inbox error:nil];
    [[NSFileManager defaultManager] removeItemAtPath:outbox error:nil];
}

#pragma mark - The pace of the relays

// KHANDAQ (0.2.57): over a TCP relay, group file chunks were lost at 256 and 128 KiB/s a member and came whole at 64
// and 32 (the desktop, cbeb1d1f): a relay passes a packet on only while its connection has room for it, and toxcore
// sends the parts of a second's chunks again at once. A packet that goes to a member over a relay waits for a second
// pace now, 64 KiB/s, which counts its bytes once however many members it goes to.

/// Twelve chunks of a file to a group of two members, one of them over a relay when `relayed`: the times the packets
/// went by the sender's clock, and the packets. Returns how many times the sender asked whether a member is relayed.
- (NSUInteger)relayTestsSendTwelveChunksRelayed:(BOOL)relayed
                                         sentAt:(NSMutableArray<NSNumber *> *)sentAt
                                        packets:(NSMutableArray<NSData *> *)packets
{
    OCTNgcGroupFileTransfer *sender = [self paceTestsSenderRecording:sentAt packets:packets members:2 sleep:nil];
    NSObject *lock = [NSObject new];
    __block NSUInteger asked = 0;
    sender.anyMemberRelayedBlock = ^BOOL (uint32_t groupNumber) {
        XCTAssertFalse([NSThread isMainThread]);
        XCTAssertEqual(groupNumber, 5u);
        @synchronized (lock) {
            asked++;
        }
        return relayed;
    };

    [self groupTestSendContent:[self groupTestContentOfLength:12 * kGroupTestChunkPayload]
                         named:@"clip.mp4"
                      msgIdHex:[OCTNgcGroupFileTransfer generateMsgIdHex]
                            by:sender];

    @synchronized (lock) {
        return asked;
    }
}

- (void)testTheChunksOfAGroupFileWithAMemberOverARelayGoAtThePaceOfTheRelays
{
    NSMutableArray<NSNumber *> *sentAt = [NSMutableArray new];
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    const NSUInteger asked = [self relayTestsSendTwelveChunksRelayed:YES sentAt:sentAt packets:packets];

    XCTAssertEqual(packets.count, 13u);
    XCTAssertEqual(asked, 13u, @"a packet did not ask whether a member is over a relay");
    // The BEGIN and one chunk fit in the burst of the relays, 16 KiB; the rest go at 64 KiB/s, a chunk counted once
    // though it goes to two members. Without a relay the BEGIN and two chunks go at once, then a chunk every 141 ms.
    XCTAssertEqual(sentAt[1].unsignedLongLongValue, kPaceTestsT0);
    XCTAssertGreaterThan(sentAt[2].unsignedLongLongValue, kPaceTestsT0);
    const uint64_t bytesBeforeTheLast = packets[0].length + 11 * kPaceTestsChunk;
    const uint64_t expected = (bytesBeforeTheLast - kRelayTestsBurst) * 1000 / kRelayTestsRate;
    const uint64_t elapsed = sentAt.lastObject.unsignedLongLongValue - kPaceTestsT0;
    XCTAssertGreaterThanOrEqual(elapsed, expected);
    XCTAssertLessThanOrEqual(elapsed, expected + 50);
    for (NSUInteger i = 3; i < sentAt.count; i++) {
        const uint64_t gap = sentAt[i].unsignedLongLongValue - sentAt[i - 1].unsignedLongLongValue;
        XCTAssertGreaterThanOrEqual(gap, kPaceTestsChunk * 1000 / kRelayTestsRate - 1, @"chunk %lu", (unsigned long)i);
    }
}

- (void)testTheChunksOfAGroupFileWhoseMembersAllHaveADirectPathKeepThePaceOfTheGroup
{
    NSMutableArray<NSNumber *> *sentAt = [NSMutableArray new];
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    const NSUInteger asked = [self relayTestsSendTwelveChunksRelayed:NO sentAt:sentAt packets:packets];

    XCTAssertEqual(packets.count, 13u);
    XCTAssertEqual(asked, 13u);
    // 512 KiB/s, a chunk counted for each of the two members, as testTheChunksOfAGroupFileGoAtThePaceForTheMembersOfTheGroup.
    XCTAssertEqual(sentAt[2].unsignedLongLongValue, kPaceTestsT0);
    const uint64_t costBeforeTheLast = 2 * (packets[0].length + 11 * kPaceTestsChunk);
    const uint64_t expected = (costBeforeTheLast - kPaceTestsBurst) * 1000 / kPaceTestsRate;
    const uint64_t elapsed = sentAt.lastObject.unsignedLongLongValue - kPaceTestsT0;
    XCTAssertGreaterThanOrEqual(elapsed, expected);
    XCTAssertLessThanOrEqual(elapsed, expected + 50);
}

// For a packet to one member alone only that member's path counts: a member with a direct path gets the whole file at
// the pace of the group, however spent the pace of the relays is; a member over a relay, at the pace of the relays.
- (void)testTheWholeFileToAMemberWithADirectPathIsNotHeldByThePaceOfTheRelays
{
    OCTGroupTestClock *clock = [OCTGroupTestClock new];
    clock.nowMs = kPaceTestsT0;
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    NSMutableArray<NSArray *> *privatePackets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self wholeFileTestSenderWithClock:clock members:2 packets:packets privatePackets:privatePackets];
    sender.anyMemberRelayedBlock = ^BOOL (uint32_t groupNumber) {
        return YES;
    };
    sender.memberRelayedBlock = ^BOOL (uint32_t groupNumber, uint32_t peerId) {
        XCTAssertFalse([NSThread isMainThread]);
        XCTAssertEqual(groupNumber, 5u);
        return peerId == 8;
    };
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:5 * kGroupTestChunkPayload] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    XCTAssertEqual(packets.count, 6u);
    NSData *whole = [self groupTestRequestFor:msgIdHex count:0 then:[NSData data]];

    // Member 7, with a direct path, asks for the whole file as the upload, at the pace of the relays, ends: that pace
    // owes a chunk. All of the file goes to it in less time than one chunk takes at that pace.
    const uint64_t askedAt = clock.nowMs;
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:whole];
    [self groupTestDrainQueueOf:sender];
    XCTAssertEqualObjects([self wholeFileTestContentsOf:privatePackets], (@[@"BEGIN", @0, @1, @2, @3, @4]));
    XCTAssertLessThan([privatePackets.lastObject[2] unsignedLongLongValue] - askedAt, kPaceTestsChunk * 1000 / kRelayTestsRate,
                      @"the whole file to a member with a direct path waited for the pace of the relays");

    // Member 8, over a relay, asks a while later: the BEGIN and a chunk fit in the burst, the rest go at 64 KiB/s.
    clock.nowMs += 5000;
    [privatePackets removeAllObjects];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:8 peerPublicKeyHex:nil data:whole];
    [self groupTestDrainQueueOf:sender];
    XCTAssertEqualObjects([self wholeFileTestContentsOf:privatePackets], (@[@"BEGIN", @0, @1, @2, @3, @4]));
    for (NSUInteger i = 3; i < privatePackets.count; i++) {
        const uint64_t gap = [privatePackets[i][2] unsignedLongLongValue] - [privatePackets[i - 1][2] unsignedLongLongValue];
        XCTAssertGreaterThanOrEqual(gap, kPaceTestsChunk * 1000 / kRelayTestsRate - 1, @"packet %lu", (unsigned long)i);
    }
    XCTAssertEqual(packets.count, 6u, @"something went to the whole group");
}

#pragma mark - The whole file a member asks for

// KHANDAQ (0.2.57): an Android that has the message of a file and no assembly of it asks the sender for the whole file:
// the count 0 and nothing after it. An iPhone read that as a request for no chunk, and such a member never got the file
// from it. The file goes to that member alone now, its BEGIN first, at the pace, a packet costing its size once.
- (void)testARequestForTheWholeFileSendsItToThatMemberAlone
{
    OCTGroupTestClock *clock = [OCTGroupTestClock new];
    clock.nowMs = kPaceTestsT0;
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    NSMutableArray<NSArray *> *privatePackets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self wholeFileTestSenderWithClock:clock members:3 packets:packets privatePackets:privatePackets];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:4 * kGroupTestChunkPayload + 10] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    NSArray<NSData *> *uploaded = [packets copy];
    XCTAssertEqual(uploaded.count, 6u);
    [packets removeAllObjects];

    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:[self groupTestRequestFor:msgIdHex count:0 then:[NSData data]]];
    [self groupTestDrainQueueOf:sender];

    XCTAssertEqual(packets.count, 0u, @"something went to the whole group");
    XCTAssertEqualObjects([self wholeFileTestContentsOf:privatePackets], (@[@"BEGIN", @0, @1, @2, @3, @4]));
    for (NSUInteger i = 0; i < privatePackets.count && i < uploaded.count; i++) {
        XCTAssertEqualObjects(privatePackets[i][0], @7);
        XCTAssertEqualObjects(privatePackets[i][1], uploaded[i], @"packet %lu is not as the upload sent it", (unsigned long)i);
    }
    // A chunk paid for once, not once for each of the three members: about 71 ms of the pace, not 212.
    for (NSUInteger i = 2; i < privatePackets.count; i++) {
        const uint64_t gap = [privatePackets[i][2] unsignedLongLongValue] - [privatePackets[i - 1][2] unsignedLongLongValue];
        XCTAssertGreaterThanOrEqual(gap, kPaceTestsChunk * 1000 / kPaceTestsRate - 1, @"packet %lu", (unsigned long)i);
        XCTAssertLessThan(gap, 2 * kPaceTestsChunk * 1000 / kPaceTestsRate, @"packet %lu", (unsigned long)i);
    }
}

// One resend of the whole file at a time, and a member's request for it taken once in 4 s at most, as Android serves it.
- (void)testTheWholeFileGoesOnceAtATimeAndToAMemberOnceInFourSeconds
{
    OCTGroupTestClock *clock = [OCTGroupTestClock new];
    clock.nowMs = kPaceTestsT0;
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    NSMutableArray<NSArray *> *privatePackets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self wholeFileTestSenderWithClock:clock members:1 packets:packets privatePackets:privatePackets];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:2 * kGroupTestChunkPayload] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    NSData *whole = [self groupTestRequestFor:msgIdHex count:0 then:[NSData data]];

    // The first request is taken and waits for its queue; the same member's next one and another member's are not.
    dispatch_semaphore_t held = dispatch_semaphore_create(0);
    dispatch_async([sender valueForKey:@"wholeFileQueue"], ^{
        dispatch_semaphore_wait(held, DISPATCH_TIME_FOREVER);
    });
    const uint64_t askedAt = clock.nowMs;
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:whole];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:whole];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:8 peerPublicKeyHex:nil data:whole];
    dispatch_sync([sender valueForKey:@"sendQueue"], ^{});
    dispatch_semaphore_signal(held);
    [self groupTestDrainQueueOf:sender];
    XCTAssertEqualObjects([self wholeFileTestContentsOf:privatePackets], (@[@"BEGIN", @0, @1]));
    XCTAssertEqualObjects([self wholeFileTestPeersOf:privatePackets], ([NSSet setWithObject:@7]));
    [privatePackets removeAllObjects];

    clock.nowMs = askedAt + 3999;
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:whole];
    [self groupTestDrainQueueOf:sender];
    XCTAssertEqual(privatePackets.count, 0u, @"taken again within 4 s");

    clock.nowMs = askedAt + 4000;
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:whole];
    [self groupTestDrainQueueOf:sender];
    XCTAssertEqualObjects([self wholeFileTestContentsOf:privatePackets], (@[@"BEGIN", @0, @1]));
    [privatePackets removeAllObjects];

    // The other member's request was not taken, so nothing holds its next one back.
    [sender handleIncomingPacketWithGroupNumber:5 peerId:8 peerPublicKeyHex:nil data:whole];
    [self groupTestDrainQueueOf:sender];
    XCTAssertEqualObjects([self wholeFileTestContentsOf:privatePackets], (@[@"BEGIN", @0, @1]));
    XCTAssertEqualObjects([self wholeFileTestPeersOf:privatePackets], ([NSSet setWithObject:@8]));
    XCTAssertEqual(packets.count, 3u, @"only the upload went to the whole group");
}

// The chunks the member asks for while the whole file goes to it are not lost to the resend that runs: they go with the
// whole file, to it alone, lowest first. Another member's request goes to the group as before.
- (void)testChunksTheMemberAsksForWhileTheWholeFileGoesToItGoWithIt
{
    OCTGroupTestClock *clock = [OCTGroupTestClock new];
    clock.nowMs = kPaceTestsT0;
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    NSMutableArray<NSArray *> *privatePackets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self wholeFileTestSenderWithClock:clock members:1 packets:packets privatePackets:privatePackets];
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    [self groupTestSendContent:[self groupTestContentOfLength:4 * kGroupTestChunkPayload + 10] named:@"clip.mp4" msgIdHex:msgIdHex by:sender];
    [packets removeAllObjects];

    // The whole file waits once chunk 2 went.
    dispatch_semaphore_t reached = dispatch_semaphore_create(0);
    dispatch_semaphore_t held = dispatch_semaphore_create(0);
    __block BOOL heldOnce = NO;
    OCTNgcGroupFileTransferSendPrivatePacketBlock record = sender.sendPrivatePacketBlock;
    sender.sendPrivatePacketBlock = ^BOOL (uint32_t groupNumber, uint32_t peerId, NSData *packet, NSError **error) {
        const BOOL sent = record(groupNumber, peerId, packet, error);
        uint32_t index = 0;
        if (packet.length > 44) {
            [packet getBytes:&index range:NSMakeRange(40, 4)];
        }
        if (! heldOnce && ((const uint8_t *)packet.bytes)[7] == 0x13 && CFSwapInt32BigToHost(index) == 2) {
            heldOnce = YES;
            dispatch_semaphore_signal(reached);
            dispatch_semaphore_wait(held, DISPATCH_TIME_FOREVER);
        }
        return sent;
    };

    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:[self groupTestRequestFor:msgIdHex count:0 then:[NSData data]]];
    XCTAssertEqual(dispatch_semaphore_wait(reached, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)), 0, @"the whole file never went");

    // The member lacks chunk 0, which went, and asks for it and for 3, still to go; then at once, in a second packet,
    // for 1. Another member asks for chunk 4.
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:[self wholeFileTestListRequestFor:msgIdHex chunks:@[@0, @3]]];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:[self wholeFileTestListRequestFor:msgIdHex chunks:@[@1]]];
    [sender handleIncomingPacketWithGroupNumber:5 peerId:8 peerPublicKeyHex:nil data:[self wholeFileTestListRequestFor:msgIdHex chunks:@[@4]]];
    dispatch_sync([sender valueForKey:@"sendQueue"], ^{});
    dispatch_semaphore_signal(held);
    [self groupTestDrainQueueOf:sender];

    XCTAssertEqualObjects([self wholeFileTestContentsOf:privatePackets], (@[@"BEGIN", @0, @1, @2, @0, @1, @3, @4]));
    XCTAssertEqualObjects([self wholeFileTestPeersOf:privatePackets], ([NSSet setWithObject:@7]));
    XCTAssertEqualObjects([self groupTestChunkIndicesOf:packets], (@[@4]), @"the other member's request");
}

// While the file's own upload goes, the whole file is its BEGIN and the chunks the upload handed over already: the
// rest reach the member with the upload.
- (void)testTheWholeFileAskedForDuringItsUploadLeavesTheRestToTheUpload
{
    OCTGroupTestClock *clock = [OCTGroupTestClock new];
    clock.nowMs = kPaceTestsT0;
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    NSMutableArray<NSArray *> *privatePackets = [NSMutableArray new];
    OCTNgcGroupFileTransfer *sender = [self wholeFileTestSenderWithClock:clock members:1 packets:packets privatePackets:privatePackets];
    dispatch_semaphore_t held = dispatch_semaphore_create(0);
    dispatch_semaphore_t waiting = dispatch_semaphore_create(0);
    __block BOOL heldOnce = NO;
    [sender setSleepMsBlock:^(uint64_t ms) {
        const char *label = dispatch_queue_get_label(DISPATCH_CURRENT_QUEUE_LABEL);
        if (! heldOnce && strcmp(label, "ngc-group-file-upload") == 0) {
            heldOnce = YES;
            dispatch_semaphore_signal(waiting);
            dispatch_semaphore_wait(held, DISPATCH_TIME_FOREVER);
        }
        clock.nowMs += ms;
    }];

    NSString *outbox = [self nameTestDirectory];
    NSString *path = [outbox stringByAppendingPathComponent:@"clip.mp4"];
    XCTAssertTrue([[self groupTestContentOfLength:8 * kGroupTestChunkPayload] writeToFile:path atomically:YES]);
    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    XCTestExpectation *sent = [self expectationWithDescription:@"the file went"];
    [sender sendFileAtPath:path groupNumber:5 msgIdHex:msgIdHex progress:nil completion:^(BOOL success, NSError *error) {
        XCTAssertTrue(success, @"%@", error);
        [sent fulfill];
    }];
    XCTAssertEqual(dispatch_semaphore_wait(waiting, dispatch_time(DISPATCH_TIME_NOW, 5 * NSEC_PER_SEC)), 0, @"the file never waited");

    // The burst let the BEGIN and chunks 0 to 3 go.
    [sender handleIncomingPacketWithGroupNumber:5 peerId:7 peerPublicKeyHex:nil data:[self groupTestRequestFor:msgIdHex count:0 then:[NSData data]]];
    dispatch_sync([sender valueForKey:@"sendQueue"], ^{});
    dispatch_sync([sender valueForKey:@"wholeFileQueue"], ^{});
    XCTAssertEqualObjects([self wholeFileTestContentsOf:privatePackets], (@[@"BEGIN", @0, @1, @2, @3]));

    dispatch_semaphore_signal(held);
    [self waitForExpectations:@[sent] timeout:5.0];
    XCTAssertEqualObjects([self wholeFileTestContentsOf:privatePackets], (@[@"BEGIN", @0, @1, @2, @3]));
    XCTAssertEqualObjects([self groupTestChunkIndicesOf:[packets subarrayWithRange:NSMakeRange(1, packets.count - 1)]],
                          (@[@0, @1, @2, @3, @4, @5, @6, @7]));
    [[NSFileManager defaultManager] removeItemAtPath:outbox error:nil];
}

#pragma mark - The name of a file offered to a friend

// KHANDAQ (0.2.57, SPEC-community risk 3): toxcore takes that name as UTF-8 bytes and their count, at most
// TOX_MAX_FILENAME_LENGTH of them. The count was the UTF-16 length of the name, so a name in Cyrillic, Arabic or
// Chinese reached the friend cut short: without its extension, or in the middle of a letter.

- (void)testAFileIsOfferedToAFriendWithTheWholeUTF8OfItsName
{
    OCTTox *tox = [self quietTox];

    XCTAssertEqualObjects([self nameOfferedBy:tox forFileName:@"report.pdf"], [@"report.pdf" dataUsingEncoding:NSUTF8StringEncoding]);

    // 9 characters, 14 bytes.
    XCTAssertEqualObjects([self nameOfferedBy:tox forFileName:@"Отчёт.pdf"], [@"Отчёт.pdf" dataUsingEncoding:NSUTF8StringEncoding]);
    XCTAssertEqualObjects([self stringOfferedBy:tox forFileName:@"تقرير.pdf"], @"تقرير.pdf");
    XCTAssertEqualObjects([self stringOfferedBy:tox forFileName:@"报告.pdf"], @"报告.pdf");
}

- (void)testALongNameOfferedToAFriendKeepsItsExtensionAndIsCutOnAWholeCharacter
{
    OCTTox *tox = [self quietTox];

    // Two bytes a letter: 125 letters and ".pdf" make 254 bytes, one more would make 256.
    NSString *letters = [@"" stringByPaddingToLength:200 withString:@"я" startingAtIndex:0];
    XCTAssertEqualObjects([self stringOfferedBy:tox forFileName:[letters stringByAppendingString:@".pdf"]],
                          [[letters substringToIndex:125] stringByAppendingString:@".pdf"]);

    NSMutableString *emoji = [NSMutableString new];
    NSMutableString *accents = [NSMutableString new];

    for (int i = 0; i < 100; i++) {
        [emoji appendString:@"\U0001F600"];
        [accents appendString:@"e\u0301"];
    }

    // Four bytes and two UTF-16 units an emoji: 62 of them and ".jpg" make 252 bytes.
    XCTAssertEqualObjects([self stringOfferedBy:tox forFileName:[emoji stringByAppendingString:@".jpg"]],
                          [[emoji substringToIndex:124] stringByAppendingString:@".jpg"]);

    // A letter is never parted from its accent: 83 of them, 3 bytes each, and ".txt" make 253 bytes.
    XCTAssertEqualObjects([self stringOfferedBy:tox forFileName:[accents stringByAppendingString:@".txt"]],
                          [[accents substringToIndex:166] stringByAppendingString:@".txt"]);

    // 255 bytes go as they are; a longer name without an extension loses its end.
    NSString *full = [@"" stringByPaddingToLength:255 withString:@"a" startingAtIndex:0];
    XCTAssertEqualObjects([self stringOfferedBy:tox forFileName:full], full);
    XCTAssertEqualObjects([self stringOfferedBy:tox forFileName:[full stringByAppendingString:@"bc"]], full);
}

- (void)testAnAvatarIsOfferedToAFriendWithoutAName
{
    OCTTox *tox = [self quietTox];
    _tox_file_send = mocked_tox_file_send;
    offeredFileName = [NSData data];
    offeredFileNameLength = 99;

    [tox fileSendWithFriendNumber:5 kind:OCTToxFileKindAvatar fileSize:0 fileId:nil fileName:nil error:nil];

    _tox_file_send = tox_file_send;
    XCTAssertNil(offeredFileName);
    XCTAssertEqual(offeredFileNameLength, 0u);
}

/// A Tox that is never started, without UDP: nothing leaves it.
- (OCTTox *)quietTox
{
    OCTToxOptions *options = [OCTToxOptions new];
    options.udpEnabled = NO;
    options.ipv6Enabled = NO;
    options.localDiscoveryEnabled = NO;
    options.holePunchingEnabled = NO;
    options.tcpPort = 0;

    NSError *error;
    OCTTox *tox = [[OCTTox alloc] initWithOptions:options savedData:nil error:&error];
    XCTAssertNotNil(tox, @"%@", error);
    return tox;
}

- (NSData *)nameOfferedBy:(OCTTox *)tox forFileName:(NSString *)fileName
{
    _tox_file_send = mocked_tox_file_send;
    offeredFileName = nil;
    offeredFileNameLength = 0;
    offeredFriendNumber = 0;

    NSError *error;
    OCTToxFileNumber fileNumber = [tox fileSendWithFriendNumber:5
                                                           kind:OCTToxFileKindData
                                                       fileSize:100
                                                         fileId:nil
                                                       fileName:fileName
                                                          error:&error];

    _tox_file_send = tox_file_send;
    XCTAssertEqual(fileNumber, 3u);
    XCTAssertNil(error);
    XCTAssertEqual(offeredFriendNumber, 5u);
    XCTAssertEqual(offeredFileName.length, offeredFileNameLength);
    XCTAssertLessThanOrEqual(offeredFileNameLength, (size_t)TOX_MAX_FILENAME_LENGTH);
    return offeredFileName;
}

- (NSString *)stringOfferedBy:(OCTTox *)tox forFileName:(NSString *)fileName
{
    NSData *name = [self nameOfferedBy:tox forFileName:fileName];
    return name ? [[NSString alloc] initWithData:name encoding:NSUTF8StringEncoding] : nil;
}

- (NSString *)nameTestDirectory
{
    NSString *directory = [NSTemporaryDirectory() stringByAppendingPathComponent:[NSUUID UUID].UUIDString];
    XCTAssertTrue([[NSFileManager defaultManager] createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil]);
    return directory;
}

- (void)stallTestRunMainQueue
{
    XCTestExpectation *drained = [self expectationWithDescription:@"the main queue ran what was sent to it"];
    dispatch_async(dispatch_get_main_queue(), ^{
        [drained fulfill];
    });
    [self waitForExpectations:@[drained] timeout:5.0];
}

- (NSData *)groupTestContentOfLength:(NSUInteger)length
{
    NSMutableData *content = [NSMutableData dataWithLength:length];
    XCTAssertEqual(SecRandomCopyBytes(kSecRandomDefault, length, content.mutableBytes), errSecSuccess);
    return content;
}

/// The packets a member sends `content` as, a file named `name`.
- (NSArray<NSData *> *)groupTestPacketsSendingContent:(NSData *)content named:(NSString *)name msgIdHex:(NSString *)msgIdHex
{
    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    [self groupTestSendContent:content named:name msgIdHex:msgIdHex by:[self stallTestTransferCollectingPackets:packets]];
    return [packets copy];
}

/// `sender` sends `content` to group 5 as a file named `name`; its file stays, for the chunks asked for again.
- (void)groupTestSendContent:(NSData *)content named:(NSString *)name msgIdHex:(NSString *)msgIdHex by:(OCTNgcGroupFileTransfer *)sender
{
    NSString *outbox = [self nameTestDirectory];
    NSString *path = [outbox stringByAppendingPathComponent:name];
    XCTAssertTrue([content writeToFile:path atomically:YES]);

    XCTestExpectation *sent = [self expectationWithDescription:@"the file went"];
    [sender sendFileAtPath:path groupNumber:5 msgIdHex:msgIdHex progress:nil completion:^(BOOL success, NSError *error) {
        XCTAssertTrue(success, @"%@", error);
        [sent fulfill];
    }];
    [self waitForExpectations:@[sent] timeout:5.0];
}

/// A request for chunks of the file `msgIdHex`: the magic, the layer and the type, the msg id, the count, then `tail`.
- (NSData *)groupTestRequestFor:(NSString *)msgIdHex count:(uint32_t)count then:(NSData *)tail
{
    const uint8_t head[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x14};
    NSMutableData *request = [NSMutableData dataWithBytes:head length:sizeof(head)];
    for (NSUInteger i = 0; i + 1 < msgIdHex.length; i += 2) {
        unsigned int byte = 0;
        [[NSScanner scannerWithString:[msgIdHex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&byte];
        uint8_t value = (uint8_t)byte;
        [request appendBytes:&value length:1];
    }
    uint32_t bigCount = CFSwapInt32HostToBig(count);
    [request appendBytes:&bigCount length:4];
    [request appendData:tail];
    return request;
}

- (uint32_t)groupTestU32In:(NSData *)data at:(NSUInteger)offset
{
    uint32_t value = 0;
    [data getBytes:&value range:NSMakeRange(offset, 4)];
    return CFSwapInt32BigToHost(value);
}

/// The index of each chunk in `packets`, in their order.
- (NSArray<NSNumber *> *)groupTestChunkIndicesOf:(NSArray<NSData *> *)packets
{
    NSMutableArray<NSNumber *> *indices = [NSMutableArray new];
    for (NSData *packet in packets) {
        XCTAssertEqual(((const uint8_t *)packet.bytes)[7], 0x13, @"not a chunk");
        [indices addObject:@([self groupTestU32In:packet at:40])];
    }
    return indices;
}

/// A member that stores what comes in `inbox`, and adds the path of each file complete to `completed`.
- (OCTNgcGroupFileTransfer *)groupTestReceiverIn:(NSString *)inbox completed:(NSMutableArray<NSString *> *)completed
{
    return [[OCTNgcGroupFileTransfer alloc] initWithSendPacketBlock:^BOOL (uint32_t groupNumber, NSData *packet, NSError **error) {
        return YES;
    } incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
        return inbox;
    } incomingBeginBlock:nil incomingCompleteBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                                                    NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
        [completed addObject:filePath];
    } transferProgressBlock:nil];
}

/// What came is handled on the transfer's queue, and a resend it asks for goes on another after.
- (void)groupTestDrainQueueOf:(OCTNgcGroupFileTransfer *)transfer
{
    for (int round = 0; round < 3; round++) {
        dispatch_sync([transfer valueForKey:@"sendQueue"], ^{});
        dispatch_sync([transfer valueForKey:@"resendQueue"], ^{});
        dispatch_sync([transfer valueForKey:@"wholeFileQueue"], ^{});
        dispatch_sync([transfer valueForKey:@"uploadQueue"], ^{});
    }
}

/// A member that sends to group 5 by `clock`, a packet to the group costing `members` times its size. What it sends to
/// the group goes to `packets`, and what it sends to one member to `privatePackets`, as @[peer id, packet, clock].
- (OCTNgcGroupFileTransfer *)wholeFileTestSenderWithClock:(OCTGroupTestClock *)clock
                                                  members:(NSUInteger)members
                                                  packets:(NSMutableArray<NSData *> *)packets
                                           privatePackets:(NSMutableArray<NSArray *> *)privatePackets
{
    OCTNgcGroupFileTransfer *sender = [[OCTNgcGroupFileTransfer alloc] initWithSendPacketBlock:^BOOL (uint32_t groupNumber, NSData *packet, NSError **error) {
        @synchronized (packets) {
            [packets addObject:packet];
        }
        return YES;
    } incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
        return NSTemporaryDirectory();
    } incomingBeginBlock:nil incomingCompleteBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                                                    NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
    } transferProgressBlock:nil];
    [sender setNowMsBlock:^uint64_t {
        return clock.nowMs;
    }];
    [sender setSleepMsBlock:^(uint64_t ms) {
        clock.nowMs += ms;
    }];
    sender.memberCountBlock = ^NSUInteger (uint32_t groupNumber) {
        return members;
    };
    sender.sendPrivatePacketBlock = ^BOOL (uint32_t groupNumber, uint32_t peerId, NSData *packet, NSError **error) {
        XCTAssertFalse([NSThread isMainThread]);
        XCTAssertEqual(groupNumber, 5u);
        @synchronized (privatePackets) {
            [privatePackets addObject:@[@(peerId), packet, @(clock.nowMs)]];
        }
        return YES;
    };
    return sender;
}

/// What `privatePackets` carry, in their order: "BEGIN" for a BEGIN, its index for a chunk.
- (NSArray *)wholeFileTestContentsOf:(NSArray<NSArray *> *)privatePackets
{
    NSMutableArray *contents = [NSMutableArray new];
    for (NSArray *sent in privatePackets) {
        NSData *packet = sent[1];
        const uint8_t type = ((const uint8_t *)packet.bytes)[7];
        [contents addObject:(type == 0x12) ? @"BEGIN" : (type == 0x13) ? @([self groupTestU32In:packet at:40]) : @"?"];
    }
    return contents;
}

- (NSSet<NSNumber *> *)wholeFileTestPeersOf:(NSArray<NSArray *> *)privatePackets
{
    NSMutableSet<NSNumber *> *peers = [NSMutableSet new];
    for (NSArray *sent in privatePackets) {
        [peers addObject:sent[0]];
    }
    return peers;
}

/// A request for the chunks `chunks` of the file `msgIdHex`, as a list.
- (NSData *)wholeFileTestListRequestFor:(NSString *)msgIdHex chunks:(NSArray<NSNumber *> *)chunks
{
    NSMutableData *list = [NSMutableData new];
    for (NSNumber *index in chunks) {
        uint32_t value = CFSwapInt32HostToBig(index.unsignedIntValue);
        [list appendBytes:&value length:4];
    }
    return [self groupTestRequestFor:msgIdHex count:(uint32_t)chunks.count then:list];
}

- (NSArray<NSString *> *)groupTestNamesIn:(NSString *)folder
{
    NSArray<NSString *> *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:folder error:nil];
    return [names sortedArrayUsingSelector:@selector(compare:)];
}

- (OCTNgcGroupFileTransfer *)stallTestTransferCollectingPackets:(NSMutableArray<NSData *> *)packets
{
    return [[OCTNgcGroupFileTransfer alloc] initWithSendPacketBlock:^BOOL (uint32_t groupNumber, NSData *packet, NSError **error) {
        [packets addObject:packet];
        return YES;
    } incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
        return NSTemporaryDirectory();
    } incomingBeginBlock:nil incomingCompleteBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                                                    NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
        XCTFail(@"The file is not complete");
    } transferProgressBlock:nil];
}

- (id)stallTestAssemblyWithChunks:(int)chunks
{
    Class assemblyClass = NSClassFromString(@"OCTNgcIncomingAssembly");
    XCTAssertNotNil(assemblyClass);

    id assembly = [assemblyClass new];
    NSMutableArray<NSNumber *> *received = [NSMutableArray arrayWithCapacity:chunks];

    for (int i = 0; i < chunks; i++) {
        [received addObject:@NO];
    }

    [assembly setValue:received forKey:@"received"];
    [assembly setValue:@(chunks) forKey:@"totalChunks"];
    [assembly setValue:@0 forKey:@"receivedCount"];
    // As -startNackTimerForAssembly:assemblyKey: leaves it.
    [assembly setValue:@(-1) forKey:@"lastReceivedCount"];
    [assembly setValue:[NSMutableData dataWithLength:32] forKey:@"msgId"];
    [assembly setValue:@5 forKey:@"groupNumber"];
    return assembly;
}

- (void)stallTestAssembly:(id)assembly receiveChunk:(int)chunk
{
    NSMutableArray<NSNumber *> *received = [assembly valueForKey:@"received"];
    received[chunk] = @YES;
    [assembly setValue:@([[assembly valueForKey:@"receivedCount"] intValue] + 1) forKey:@"receivedCount"];
}

@end
