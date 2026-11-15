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

@interface OCTSubmanagerChatsImpl (TextPartsTests)

- (void)wakeFriendWithPushToken:(NSString *)pushToken forMessageWithHash:(NSString *)msgv3HashHex inChat:(OCTChat *)chat;

@end

@interface OCTSubmanagerGroupsImpl (GroupSendTests)

- (NSString *)defaultGroupPeerName;
- (BOOL)prepareChatForGroupActivity:(OCTChat *)chat peerName:(NSString *)peerName error:(NSError **)error;
- (BOOL)precheckCanSendInChat:(OCTChat *)chat error:(NSError **)error;
- (void)scheduleHistSyncBroadcastForGroupNumber:(OCTToxGroupNumber)groupNumber;

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

    // Part i is stamped i seconds after the first, so whole-second stamps cannot tie at the receiver.
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

@end
