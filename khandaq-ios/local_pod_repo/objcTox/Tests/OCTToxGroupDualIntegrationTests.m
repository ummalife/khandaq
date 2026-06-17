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

@interface OCTGroupDualTestPeer : NSObject <OCTToxDelegate>

@property (strong, nonatomic) NSString *label;
@property (strong, nonatomic) XCTestExpectation *dhtConnectedExpectation;
@property (strong, nonatomic) XCTestExpectation *groupConnectedExpectation;
@property (strong, nonatomic) XCTestExpectation *peerJoinExpectation;
@property (strong, nonatomic) XCTestExpectation *messageExpectation;

@property (assign, nonatomic) int32_t lastGroupConnectionStatus;
@property (copy, nonatomic) NSString *receivedMessage;
@property (assign, nonatomic) uint32_t receivedMessageId;

@end

@implementation OCTGroupDualTestPeer

- (void)tox:(OCTTox *)tox connectionStatus:(OCTToxConnectionStatus)connectionStatus
{
    if (connectionStatus != OCTToxConnectionStatusNone && self.dhtConnectedExpectation) {
        [self.dhtConnectedExpectation fulfill];
        self.dhtConnectedExpectation = nil;
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
    struct {
        const char *host;
        const char *key;
    } nodes[] = {
        {"bootstrap1.khandaq.org", "74AE9E62A2AE51983CF9C6B526CD89ABD8AA91864B35FC0CF7AC60454CBDDD6D"},
        {"bootstrap2.khandaq.org", "5C6F3903FB1EC4AC386843D8FB584CC34567E045EC26939A6034C3A2746A9B6B"},
        {"bootstrap3.khandaq.org", "A181DD1F8C9A9D41BE1875A5C2687A89C3CB4F0F76ED9C390E7270B01BF24665"},
    };

    for (size_t i = 0; i < sizeof(nodes) / sizeof(nodes[0]); i++) {
        NSString *host = [NSString stringWithUTF8String:nodes[i].host];
        NSString *key = [NSString stringWithUTF8String:nodes[i].key];
        NSError *error = nil;

        XCTAssertTrue([tox bootstrapFromHost:host port:33445 publicKey:key error:&error], @"[%@] bootstrap %@ failed: %@", label, host, error);
        XCTAssertTrue([tox addTCPRelayWithHost:host port:33445 publicKey:key error:&error], @"[%@] tcp33445 %@ failed: %@", label, host, error);
        XCTAssertTrue([tox addTCPRelayWithHost:host port:3389 publicKey:key error:&error], @"[%@] tcp3389 %@ failed: %@", label, host, error);
    }
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

@end
