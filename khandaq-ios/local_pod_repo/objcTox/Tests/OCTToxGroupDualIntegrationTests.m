// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>

#import "OCTTox.h"
#import "OCTToxOptions.h"
#import "OCTTox+Private.h"

static const NSTimeInterval kDHTConnectTimeout = 90.0;
static const NSTimeInterval kGroupMeshTimeout = 120.0;
static const NSTimeInterval kMessageTimeout = 60.0;
static const NSTimeInterval kFriendOnlineTimeout = 180.0;
static const NSTimeInterval kInviteTimeout = 60.0;
static const NSTimeInterval kNoInviteWindow = 15.0;

@interface OCTGroupDualTestPeer : NSObject <OCTToxDelegate>

@property (strong, nonatomic) NSString *label;
@property (strong, nonatomic) XCTestExpectation *dhtConnectedExpectation;
@property (strong, nonatomic) XCTestExpectation *groupConnectedExpectation;
@property (strong, nonatomic) XCTestExpectation *peerJoinExpectation;
@property (strong, nonatomic) XCTestExpectation *messageExpectation;
@property (strong, nonatomic) XCTestExpectation *friendOnlineExpectation;
@property (strong, nonatomic) XCTestExpectation *groupInviteExpectation;

@property (assign, nonatomic) int32_t lastGroupConnectionStatus;
@property (copy, nonatomic) NSString *receivedMessage;
@property (assign, nonatomic) uint32_t receivedMessageId;
@property (strong, nonatomic) NSData *pendingInviteData;
@property (assign, nonatomic) OCTToxFriendNumber pendingInviteFriendNumber;

@end

@implementation OCTGroupDualTestPeer

- (void)tox:(OCTTox *)tox connectionStatus:(OCTToxConnectionStatus)connectionStatus
{
    if (connectionStatus != OCTToxConnectionStatusNone && self.dhtConnectedExpectation) {
        [self.dhtConnectedExpectation fulfill];
        self.dhtConnectedExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox friendConnectionStatusChanged:(OCTToxConnectionStatus)status friendNumber:(OCTToxFriendNumber)friendNumber
{
    NSLog(@"NGC_QA [%@] friend_conn friend=%u status=%ld", self.label, friendNumber, (long)status);

    if (status != OCTToxConnectionStatusNone && self.friendOnlineExpectation) {
        [self.friendOnlineExpectation fulfill];
        self.friendOnlineExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox groupInviteFromFriendNumber:(OCTToxFriendNumber)friendNumber
 inviteData:(NSData *)inviteData
  groupName:(NSString *)groupName
{
    NSLog(@"NGC_QA [%@] group_invite friend=%u name=%@", self.label, friendNumber, groupName);

    self.pendingInviteData = inviteData;
    self.pendingInviteFriendNumber = friendNumber;

    if (self.groupInviteExpectation) {
        [self.groupInviteExpectation fulfill];
        self.groupInviteExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox groupConnectionStatusChanged:(int32_t)status groupNumber:(OCTToxGroupNumber)groupNumber
{
    self.lastGroupConnectionStatus = status;
    NSLog(@"NGC_QA [%@] group_conn group=%u status=%d", self.label, groupNumber, status);

    if (status == 1 && self.groupConnectedExpectation) {
        [self.groupConnectedExpectation fulfill];
        self.groupConnectedExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox groupPeerJoinWithGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId
{
    NSLog(@"NGC_QA [%@] peer_join group=%u peer=%u", self.label, groupNumber, peerId);

    if (self.groupConnectedExpectation) {
        [self.groupConnectedExpectation fulfill];
        self.groupConnectedExpectation = nil;
    }

    if (self.peerJoinExpectation) {
        [self.peerJoinExpectation fulfill];
        self.peerJoinExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox groupMessage:(NSString *)message
       type:(OCTToxMessageType)type
groupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
  messageId:(uint32_t)messageId
{
    NSLog(@"NGC_QA [%@] group_msg group=%u peer=%u msgId=%u text=%@", self.label, groupNumber, peerId, messageId, message);

    self.receivedMessage = message;
    self.receivedMessageId = messageId;

    if (self.messageExpectation) {
        [self.messageExpectation fulfill];
        self.messageExpectation = nil;
    }
}

@end

@interface OCTToxGroupDualIntegrationTests : XCTestCase

@property (strong, nonatomic) OCTTox *founderTox;
@property (strong, nonatomic) OCTTox *joinerTox;
@property (strong, nonatomic) OCTGroupDualTestPeer *founderPeer;
@property (strong, nonatomic) OCTGroupDualTestPeer *joinerPeer;

@end

@implementation OCTToxGroupDualIntegrationTests

- (void)setUp
{
    [super setUp];

    if ([[NSProcessInfo processInfo].environment[@"SKIP_NGC_NETWORK_TESTS"] boolValue]) {
        NSLog(@"SKIP_NGC_NETWORK_TESTS=1, skipping network integration tests");
        return;
    }

    self.founderPeer = [OCTGroupDualTestPeer new];
    self.founderPeer.label = @"founder";
    self.joinerPeer = [OCTGroupDualTestPeer new];
    self.joinerPeer.label = @"joiner";

    OCTToxOptions *founderOptions = [OCTToxOptions new];
    founderOptions.udpEnabled = YES;
    founderOptions.ipv6Enabled = YES;
    founderOptions.localDiscoveryEnabled = YES;
    founderOptions.startPort = 33445;
    founderOptions.endPort = 33544;

    OCTToxOptions *joinerOptions = [OCTToxOptions new];
    joinerOptions.udpEnabled = YES;
    joinerOptions.ipv6Enabled = YES;
    joinerOptions.localDiscoveryEnabled = YES;
    joinerOptions.startPort = 33545;
    joinerOptions.endPort = 33644;

    self.founderTox = [[OCTTox alloc] initWithOptions:founderOptions savedData:nil error:nil];
    self.joinerTox = [[OCTTox alloc] initWithOptions:joinerOptions savedData:nil error:nil];

    self.founderTox.delegate = self.founderPeer;
    self.joinerTox.delegate = self.joinerPeer;

    [self bootstrapTox:self.founderTox label:@"founder"];
    [self bootstrapTox:self.joinerTox label:@"joiner"];

    [self.founderTox start];
    [self.joinerTox start];
}

- (void)tearDown
{
    [self.founderTox stop];
    [self.joinerTox stop];
    self.founderTox = nil;
    self.joinerTox = nil;
    self.founderPeer = nil;
    self.joinerPeer = nil;
    [super tearDown];
}

- (void)bootstrapTox:(OCTTox *)tox label:(NSString *)label
{
    // KHANDAQ: bootstrap*.khandaq.org was RETIRED (config/khandaq_bootstrap_nodes.json, status=retired)
    // and its DNS no longer resolves, so this suite could not get past setUp — it failed on the
    // bootstrap assertions long before reaching the group logic it exists to test. Same public nodes
    // the shipped clients use (BootstrapNodeEntryDB.java), and one unreachable node is not a failure.
    struct {
        const char *host;
        uint16_t port;
        const char *key;
    } nodes[] = {
        {"tox.abilinski.com", 33445, "10C00EB250C3233E343E2AEBA07115A5C28920E9C8D29492F6D00B29049EDC7E"},
        {"205.185.115.131", 53, "3091C6BEB2A993F1C6300C16549FABA67098FF3D62C6D253828B531470B53D68"},
        {"tox.initramfs.io", 33445, "3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25"},
        {"tox.plastiras.org", 33445, "8E8B63299B3D520FB377FE5100E65E3322F7AE5B20A0ACED2981769FC5B43725"},
    };

    NSUInteger reached = 0;
    for (size_t i = 0; i < sizeof(nodes) / sizeof(nodes[0]); i++) {
        NSString *host = [NSString stringWithUTF8String:nodes[i].host];
        NSString *key = [NSString stringWithUTF8String:nodes[i].key];
        NSError *error = nil;

        if ([tox bootstrapFromHost:host port:nodes[i].port publicKey:key error:&error]) {
            reached++;
        }
        else {
            NSLog(@"NGC_QA [%@] bootstrap %@ unavailable: %@", label, host, error);
        }

        error = nil;
        [tox addTCPRelayWithHost:host port:nodes[i].port publicKey:key error:&error];
    }

    XCTAssertGreaterThan(reached, 0u, @"[%@] not a single bootstrap node could be reached", label);
}

- (void)waitForDHTOnPeers
{
    self.founderPeer.dhtConnectedExpectation = [self expectationWithDescription:@"founder DHT"];
    self.joinerPeer.dhtConnectedExpectation = [self expectationWithDescription:@"joiner DHT"];

    if (self.founderTox.connectionStatus != OCTToxConnectionStatusNone) {
        [self.founderPeer.dhtConnectedExpectation fulfill];
        self.founderPeer.dhtConnectedExpectation = nil;
    }
    if (self.joinerTox.connectionStatus != OCTToxConnectionStatusNone) {
        [self.joinerPeer.dhtConnectedExpectation fulfill];
        self.joinerPeer.dhtConnectedExpectation = nil;
    }

    [self waitForExpectationsWithTimeout:kDHTConnectTimeout handler:nil];
    NSLog(@"NGC_QA DHT connected founder=%ld joiner=%ld",
          (long)self.founderTox.connectionStatus, (long)self.joinerTox.connectionStatus);
}

- (void)testDualToxPublicGroupMeshAndMessage
{
    [self waitForDHTOnPeers];

    NSError *error = nil;
    NSString *ts = [NSString stringWithFormat:@"%lu", (unsigned long)[[NSDate date] timeIntervalSince1970]];
    NSString *groupName = [NSString stringWithFormat:@"iOSQA_%@", ts];
    NSString *founderName = [NSString stringWithFormat:@"F_%@", ts];
    NSString *joinerName = [NSString stringWithFormat:@"J_%@", ts];
    NSString *messageText = [NSString stringWithFormat:@"ios_qa_msg_%@", ts];

    OCTToxGroupNumber groupNumber = [self.founderTox groupNewWithPrivacyState:OCTToxGroupPrivacyStatePublic
                                                                    groupName:groupName
                                                                     peerName:founderName
                                                                        error:&error];
    XCTAssertNotEqual(groupNumber, kOCTToxGroupNumberFailure);
    XCTAssertNil(error, @"groupNew failed: %@", error);

    NSString *chatId = [self.founderTox groupChatIdHexForGroupNumber:groupNumber error:&error];
    XCTAssertNil(error);
    XCTAssertEqual(chatId.length, kOCTToxGroupChatIdHexLength);
    NSLog(@"NGC_QA created public group=%u chatId=%@", groupNumber, chatId);

    self.founderPeer.groupConnectedExpectation = [self expectationWithDescription:@"founder group connected"];
    if ([self.founderTox groupConnectionStatusForGroupNumber:groupNumber error:nil] == 1) {
        [self.founderPeer.groupConnectedExpectation fulfill];
        self.founderPeer.groupConnectedExpectation = nil;
    }

    OCTToxGroupNumber joinGroupNumber = [self.joinerTox groupJoinWithChatIdHex:chatId
                                                                      peerName:joinerName
                                                                      password:nil
                                                                         error:&error];
    XCTAssertNotEqual(joinGroupNumber, kOCTToxGroupNumberFailure, @"groupJoin failed: %@", error);
    XCTAssertNil(error);
    NSLog(@"NGC_QA joiner joined group=%u", joinGroupNumber);

    self.joinerPeer.groupConnectedExpectation = [self expectationWithDescription:@"joiner group connected"];
    self.founderPeer.peerJoinExpectation = [self expectationWithDescription:@"founder saw remote peer"];

    [self waitForExpectationsWithTimeout:kGroupMeshTimeout handler:^(NSError *waitError) {
        if (waitError) {
            int32_t founderConn = [self.founderTox groupConnectionStatusForGroupNumber:groupNumber error:nil];
            int32_t joinerConn = [self.joinerTox groupConnectionStatusForGroupNumber:joinGroupNumber error:nil];
            NSLog(@"NGC_QA mesh timeout founder_group_conn=%d joiner_group_conn=%d", founderConn, joinerConn);
        }
    }];

    self.joinerPeer.messageExpectation = [self expectationWithDescription:@"joiner received message"];

    uint32_t sentMessageId = 0;
    BOOL sent = [self.founderTox groupSendMessage:messageText
                                             type:OCTToxMessageTypeNormal
                                      groupNumber:groupNumber
                                        messageId:&sentMessageId
                                            error:&error];
    if (! sent) {
        NSLog(@"NGC_QA send failed (may retry after mesh): %@", error);
        error = nil;
        sent = [self.founderTox groupSendMessage:messageText
                                            type:OCTToxMessageTypeNormal
                                     groupNumber:groupNumber
                                       messageId:&sentMessageId
                                           error:&error];
    }

    XCTAssertTrue(sent, @"groupSendMessage failed: %@", error);
    [self waitForExpectationsWithTimeout:kMessageTimeout handler:nil];

    XCTAssertEqualObjects(self.joinerPeer.receivedMessage, messageText);
    XCTAssertGreaterThan(self.joinerPeer.receivedMessageId, 0u);
    NSLog(@"NGC_QA PASS chatId=%@ msgId=%u text=%@", chatId, self.joinerPeer.receivedMessageId, self.joinerPeer.receivedMessage);

    [self.founderTox groupLeaveWithGroupNumber:groupNumber partMessage:nil error:&error];
    [self.joinerTox groupLeaveWithGroupNumber:joinGroupNumber partMessage:nil error:&error];
}

/**
 * KHANDAQ (join by ID): tox_group_join() adds the group locally before any peer has answered, and
 * Messenger dropped every friend invite to a group it already had. So a join by ID that the DHT
 * announce never completed could not be finished by a friend either. The invite must now reach a
 * group known only by its ID, taking it in place of the stuck join must reach the founder, and once
 * the group has state from a peer a further invite must be filtered as before.
 *
 * objcTox takes the invite with the stuck join still in place: tox does not list a group no peer has
 * answered, so there is nothing for it to leave first.
 */
- (void)testFriendInviteReachesAGroupJoinedOnlyById
{
    [self runFriendInviteToGroupJoinedOnlyByIdLeavingItFirst:NO];
}

/**
 * The same with the stuck join left just before the invite is taken, in one pass on the tox queue, as
 * the app replaces a group it already has with the invited one (OCTSubmanagerGroupsImpl). A left chat
 * lingers until the next iteration and must not take the join either.
 */
- (void)testFriendInviteTakenRightAfterLeavingTheStuckJoin
{
    [self runFriendInviteToGroupJoinedOnlyByIdLeavingItFirst:YES];
}

- (void)runFriendInviteToGroupJoinedOnlyByIdLeavingItFirst:(BOOL)leaveFirst
{
    if (! self.founderTox) {
        return;
    }

    // XCTAssert does not stop an ObjC test method, and every step below needs the one before it.
    self.continueAfterFailure = NO;

    [self waitForDHTOnPeers];

    NSError *error = nil;
    NSString *ts = [NSString stringWithFormat:@"%lu", (unsigned long)[[NSDate date] timeIntervalSince1970]];
    NSString *groupName = [NSString stringWithFormat:@"iOSQA_byid_%@", ts];
    NSString *joinerName = [NSString stringWithFormat:@"J_%@", ts];

    NSString *founderPub = [[self.founderTox userAddress] substringToIndex:kOCTToxPublicKeyLength];
    NSString *joinerPub = [[self.joinerTox userAddress] substringToIndex:kOCTToxPublicKeyLength];
    OCTToxFriendNumber founderSideFriend = [self.founderTox addFriendWithNoRequestWithPublicKey:joinerPub error:&error];
    XCTAssertNotEqual(founderSideFriend, kOCTToxFriendNumberFailure, @"founder could not add the joiner: %@", error);
    OCTToxFriendNumber joinerSideFriend = [self.joinerTox addFriendWithNoRequestWithPublicKey:founderPub error:&error];
    XCTAssertNotEqual(joinerSideFriend, kOCTToxFriendNumberFailure, @"joiner could not add the founder: %@", error);

    self.founderPeer.friendOnlineExpectation = [self expectationWithDescription:@"founder sees the joiner online"];
    self.joinerPeer.friendOnlineExpectation = [self expectationWithDescription:@"joiner sees the founder online"];
    [self waitForExpectationsWithTimeout:kFriendOnlineTimeout handler:nil];

    // The group calls below run on the instance's tox queue, as the app makes them (OCTSubmanagerGroupsImpl
    // -performSyncOnToxQueue:). toxcore is not thread-safe, and a join or an accept resizes the lists that
    // tox_iterate walks on that queue.
    __block OCTToxGroupNumber founderGroup = kOCTToxGroupNumberFailure;
    __block NSString *chatId = nil;
    __block NSError *queueError = nil;
    [self.founderTox performSyncBlockOnToxQueue:^{
        NSError *blockError = nil;
        founderGroup = [self.founderTox groupNewWithPrivacyState:OCTToxGroupPrivacyStatePublic
                                                       groupName:groupName
                                                        peerName:[@"F_" stringByAppendingString:ts]
                                                           error:&blockError];
        chatId = [self.founderTox groupChatIdHexForGroupNumber:founderGroup error:nil];
        queueError = blockError;
    }];
    XCTAssertNotEqual(founderGroup, kOCTToxGroupNumberFailure, @"groupNew failed: %@", queueError);
    XCTAssertEqual(chatId.length, kOCTToxGroupChatIdHexLength);

    // The joiner now knows the group only by its ID: added locally, nothing from any peer yet.
    __block OCTToxGroupNumber stuckGroup = kOCTToxGroupNumberFailure;
    [self.joinerTox performSyncBlockOnToxQueue:^{
        NSError *blockError = nil;
        stuckGroup = [self.joinerTox groupJoinWithChatIdHex:chatId peerName:joinerName password:nil error:&blockError];
        queueError = blockError;
    }];
    XCTAssertNotEqual(stuckGroup, kOCTToxGroupNumberFailure, @"groupJoin failed: %@", queueError);

    self.joinerPeer.groupInviteExpectation = [self expectationWithDescription:@"joiner received the invite"];
    XCTAssertTrue([self inviteJoinerToFounderGroup:founderGroup friendNumber:founderSideFriend], @"group invite failed");
    [self waitForExpectationsWithTimeout:kInviteTimeout handler:nil];
    XCTAssertNotNil(self.joinerPeer.pendingInviteData, @"the invite to a group known only by its ID was dropped");

    __block BOOL leftStuckGroup = NO;
    __block OCTToxGroupNumber joinerGroup = kOCTToxGroupNumberFailure;
    [self.joinerTox performSyncBlockOnToxQueue:^{
        if (leaveFirst) {
            leftStuckGroup = [self.joinerTox groupLeaveWithGroupNumber:stuckGroup partMessage:nil error:nil];
        }

        NSError *blockError = nil;
        joinerGroup = [self.joinerTox groupInviteAcceptWithFriendNumber:self.joinerPeer.pendingInviteFriendNumber
                                                             inviteData:self.joinerPeer.pendingInviteData
                                                               peerName:joinerName
                                                               password:nil
                                                                  error:&blockError];
        queueError = blockError;
    }];
    XCTAssertTrue(! leaveFirst || leftStuckGroup, @"leaving the stuck join failed");
    XCTAssertNotEqual(joinerGroup, kOCTToxGroupNumberFailure, @"accepting the invite failed: %@", queueError);

    self.founderPeer.peerJoinExpectation = [self expectationWithDescription:@"founder saw the joiner"];
    [self waitForExpectationsWithTimeout:kGroupMeshTimeout handler:nil];

    // The group name lives in the shared state, so reading it back means the joiner holds real state.
    __block NSString *joinerGroupName = nil;
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:kInviteTimeout];
    while (YES) {
        [self.joinerTox performSyncBlockOnToxQueue:^{
            joinerGroupName = [self.joinerTox groupNameForGroupNumber:joinerGroup error:nil];
        }];

        if ([joinerGroupName isEqualToString:groupName] || [deadline timeIntervalSinceNow] <= 0) {
            break;
        }

        [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:0.5]];
    }
    XCTAssertEqualObjects(joinerGroupName, groupName, @"the joiner never received the group's shared state");

    // A group with state stays filtered: a second invite to it does not surface.
    self.joinerPeer.pendingInviteData = nil;
    XCTestExpectation *noInvite = [self expectationWithDescription:@"no invite for a group with state"];
    noInvite.inverted = YES;
    self.joinerPeer.groupInviteExpectation = noInvite;
    XCTAssertTrue([self inviteJoinerToFounderGroup:founderGroup friendNumber:founderSideFriend], @"second group invite failed");
    [self waitForExpectationsWithTimeout:kNoInviteWindow handler:nil];
    XCTAssertNil(self.joinerPeer.pendingInviteData, @"an invite to a group with state was surfaced");

    NSLog(@"NGC_QA PASS join-by-id invite leaveFirst=%d stuckGroup=%u joinerGroup=%u chatId=%@",
          leaveFirst, stuckGroup, joinerGroup, chatId);

    [self.founderTox performSyncBlockOnToxQueue:^{
        [self.founderTox groupLeaveWithGroupNumber:founderGroup partMessage:nil error:nil];
    }];
    [self.joinerTox performSyncBlockOnToxQueue:^{
        [self.joinerTox groupLeaveWithGroupNumber:joinerGroup partMessage:nil error:nil];
    }];
}

- (BOOL)inviteJoinerToFounderGroup:(OCTToxGroupNumber)founderGroup friendNumber:(OCTToxFriendNumber)friendNumber
{
    __block BOOL invited = NO;
    [self.founderTox performSyncBlockOnToxQueue:^{
        invited = [self.founderTox groupInviteFriendWithGroupNumber:founderGroup friendNumber:friendNumber error:nil];
    }];
    return invited;
}

@end
