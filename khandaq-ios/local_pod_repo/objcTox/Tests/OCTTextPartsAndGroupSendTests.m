// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>
#import <OCMock/OCMock.h>

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
#import "OCTToxOptions.h"

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
- (void)wakeFriendWithPushToken:(NSString *)pushToken forMessageWithHash:(NSString *)msgv3HashHex inChat:(OCTChat *)chat;

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
- (void)nackTickForAssembly:(id)assembly assemblyKey:(NSString *)assemblyKey;
- (void)appendFilename:(NSString *)fileName toData:(NSMutableData *)data;
- (NSString *)utf8FilenameFromData:(NSData *)data offset:(NSUInteger)offset length:(NSUInteger)maxLen;

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
    OCMStub([partial wakeFriendWithPushToken:[OCMArg any] forMessageWithHash:[OCMArg any] inChat:[OCMArg any]]).andDo(^(NSInvocation *invocation) {
        wakes++;
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
