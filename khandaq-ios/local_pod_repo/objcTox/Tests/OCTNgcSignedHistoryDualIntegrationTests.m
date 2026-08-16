// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>

#import "OCTTox.h"
#import "OCTToxOptions.h"
#import "OCTTox+Private.h"
#import "OCTNgcHskAnnounce.h"
#import "OCTNgcHskDirectory.h"
#import "OCTNgcHskStore.h"
#import "OCTNgcSignedHistory.h"

/**
 * KHANDAQ (external audit #2, finding 1) — the iOS signed-history path ON THE WIRE.
 *
 * Every other test of this feature feeds both halves from the same process and the same values,
 * which is exactly the shape that hid the Android key-space bug for a day: unit tests and even a
 * device test passed while every real announcement was rejected, because the signature was made
 * over the profile's Tox ID key and verified against the per-group key. Nothing but two separate
 * Tox instances exchanging real packets can catch that class of mistake, so this raises two, puts
 * them in one group, and makes the announcement and the signed record travel between them.
 *
 * Network test: opt out with SKIP_NGC_NETWORK_TESTS=1, like the sibling dual-tox suite.
 */

// A public group has to announce itself into the DHT before the joiner can find it, and on a
// simulator behind a home NAT that has taken well over two minutes — the sibling QA script waits
// 300 s for the same reason (WAIT_MESH). Short timeouts here fail the mesh, not the feature.
static const NSTimeInterval kDHTConnectTimeout = 120.0;
static const NSTimeInterval kGroupMeshTimeout = 300.0;
static const NSTimeInterval kFriendOnlineTimeout = 180.0;
static const NSTimeInterval kPacketTimeout = 90.0;

#pragma mark - one side of the wire

@interface OCTSignedHistoryTestPeer : NSObject <OCTToxDelegate>

@property (copy, nonatomic) NSString *label;
@property (strong, nonatomic) OCTTox *tox;
/** Stands in for the profile database the real blocks read and write. */
@property (strong, nonatomic) NSMutableDictionary<NSString *, NSString *> *kv;
@property (strong, nonatomic) OCTNgcHskAnnounce *announce;
@property (strong, nonatomic) OCTNgcSignedHistory *signedHistory;

@property (assign, nonatomic) OCTToxGroupNumber groupNumber;
@property (assign, nonatomic) uint32_t remotePeerId;

@property (strong, nonatomic) XCTestExpectation *dhtExpectation;
@property (strong, nonatomic) XCTestExpectation *friendOnlineExpectation;
@property (strong, nonatomic) XCTestExpectation *groupInviteExpectation;
@property (strong, nonatomic) XCTestExpectation *groupConnectedExpectation;
@property (strong, nonatomic) XCTestExpectation *peerJoinExpectation;
/** Set by the invite delegate so the test can accept it on its own thread. */
@property (strong, nonatomic) NSData *pendingInviteData;
@property (assign, nonatomic) OCTToxFriendNumber pendingInviteFriendNumber;
/** Fulfilled when a 0x02/0x50 announcement was consumed AND stored. */
@property (strong, nonatomic) XCTestExpectation *announcementLearnedExpectation;
/** Fulfilled when a 0x02/0x02 signed record was consumed. */
@property (strong, nonatomic) XCTestExpectation *signedRecordExpectation;

@end

@implementation OCTSignedHistoryTestPeer

- (instancetype)initWithLabel:(NSString *)label tox:(OCTTox *)tox
{
    self = [super init];
    if (self) {
        _label = [label copy];
        _tox = tox;
        _kv = [NSMutableDictionary dictionary];
        _groupNumber = 0;
        _remotePeerId = 0;
        [self buildHelpers];
    }
    return self;
}

- (void)buildHelpers
{
    __weak typeof(self) weakSelf = self;

    _announce = [[OCTNgcHskAnnounce alloc]
        initWithSendBroadcastBlock:^BOOL(uint32_t groupNumber, NSData *packet) {
            __strong typeof(weakSelf) self = weakSelf;
            NSError *error = nil;
            BOOL ok = [self.tox groupSendCustomPacket:packet
                                          groupNumber:groupNumber
                                             lossless:YES
                                                error:&error];
            NSLog(@"NGC_SIG [%@] broadcast announce ok=%d err=%@", self.label, ok, error);
            return ok;
        }
        selfGroupPubBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;
            return [self.tox groupSelfPublicKeyHexForGroupNumber:groupNumber error:nil];
        }
        peerGroupPubBlock:^NSString *(uint32_t groupNumber, uint32_t peerId) {
            __strong typeof(weakSelf) self = weakSelf;
            return [self.tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil];
        }
        groupIdBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;
            return [self.tox groupChatIdHexForGroupNumber:groupNumber error:nil];
        }
        selfToxPubBlock:^NSString *{
            __strong typeof(weakSelf) self = weakSelf;
            return [OCTNgcHskStore toxPubFromToxId:[self.tox userAddress]];
        }
        peerConnectedBlock:^BOOL(uint32_t groupNumber, uint32_t peerId) {
            return YES;
        }
        getValueBlock:^NSString *(NSString *key) {
            __strong typeof(weakSelf) self = weakSelf;
            return self.kv[key];
        }
        setValueBlock:^BOOL(NSString *key, NSString *value) {
            __strong typeof(weakSelf) self = weakSelf;
            self.kv[key] = value;
            return YES;
        }];

    _signedHistory = [[OCTNgcSignedHistory alloc]
        initWithSendPrivateBlock:^BOOL(uint32_t groupNumber, uint32_t peerId, NSData *packet) {
            __strong typeof(weakSelf) self = weakSelf;
            NSError *error = nil;
            BOOL ok = [self.tox groupSendCustomPrivatePacket:packet
                                                 groupNumber:groupNumber
                                                      peerId:peerId
                                                    lossless:YES
                                                       error:&error];
            NSLog(@"NGC_SIG [%@] private signed-text ok=%d err=%@", self.label, ok, error);
            return ok;
        }
        groupIdBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;
            return [self.tox groupChatIdHexForGroupNumber:groupNumber error:nil];
        }
        selfGroupPubBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;
            return [self.tox groupSelfPublicKeyHexForGroupNumber:groupNumber error:nil];
        }
        selfToxPubBlock:^NSString *{
            __strong typeof(weakSelf) self = weakSelf;
            return [OCTNgcHskStore toxPubFromToxId:[self.tox userAddress]];
        }
        getValueBlock:^NSString *(NSString *key) {
            __strong typeof(weakSelf) self = weakSelf;
            return self.kv[key];
        }
        setValueBlock:^BOOL(NSString *key, NSString *value) {
            __strong typeof(weakSelf) self = weakSelf;
            self.kv[key] = value;
            return YES;
        }];
}

/** The key we learned for a peer in a group, or nil — i.e. did the announcement land. */
- (nullable OCTNgcHskRecord *)learnedRecordForPeerGroupPub:(NSString *)peerGroupPubHex
{
    NSString *groupId = [self.tox groupChatIdHexForGroupNumber:self.groupNumber error:nil];
    NSString *row = [OCTNgcHskDirectory rowKeyWithGroupId:groupId peerGroupPubHex:peerGroupPubHex];
    return row == nil ? nil : [OCTNgcHskDirectory decodeRecord:self.kv[row]];
}

#pragma mark - OCTToxDelegate

- (void)tox:(OCTTox *)tox connectionStatus:(OCTToxConnectionStatus)connectionStatus
{
    if (connectionStatus != OCTToxConnectionStatusNone && self.dhtExpectation) {
        [self.dhtExpectation fulfill];
        self.dhtExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox friendConnectionStatusChanged:(OCTToxConnectionStatus)status friendNumber:(OCTToxFriendNumber)friendNumber
{
    NSLog(@"NGC_SIG [%@] friend_conn friend=%u status=%ld", self.label, friendNumber, (long)status);

    if (status != OCTToxConnectionStatusNone && self.friendOnlineExpectation) {
        [self.friendOnlineExpectation fulfill];
        self.friendOnlineExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox groupInviteFromFriendNumber:(OCTToxFriendNumber)friendNumber
 inviteData:(NSData *)inviteData
  groupName:(NSString *)groupName
{
    NSLog(@"NGC_SIG [%@] group_invite from friend=%u name=%@", self.label, friendNumber, groupName);

    self.pendingInviteData = inviteData;
    self.pendingInviteFriendNumber = friendNumber;

    if (self.groupInviteExpectation) {
        [self.groupInviteExpectation fulfill];
        self.groupInviteExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox groupConnectionStatusChanged:(int32_t)status groupNumber:(OCTToxGroupNumber)groupNumber
{
    NSLog(@"NGC_SIG [%@] group_conn group=%u status=%d", self.label, groupNumber, status);

    if (status == 1 && self.groupConnectedExpectation) {
        [self.groupConnectedExpectation fulfill];
        self.groupConnectedExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox groupPeerJoinWithGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId
{
    NSLog(@"NGC_SIG [%@] peer_join group=%u peer=%u", self.label, groupNumber, peerId);
    self.remotePeerId = peerId;

    if (self.groupConnectedExpectation) {
        [self.groupConnectedExpectation fulfill];
        self.groupConnectedExpectation = nil;
    }
    if (self.peerJoinExpectation) {
        [self.peerJoinExpectation fulfill];
        self.peerJoinExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox groupCustomPacketWithGroupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
       data:(NSData *)data
{
    // Exactly the dispatch the app does: hand it to the announcer first, and only it decides whether
    // the packet was its own.
    BOOL consumed = [self.announce handleIncomingPacketWithGroupNumber:groupNumber peerId:peerId data:data];
    NSLog(@"NGC_SIG [%@] broadcast in peer=%u len=%lu consumed=%d",
          self.label, peerId, (unsigned long)data.length, consumed);

    if (! consumed) {
        return;
    }

    NSString *peerPub = [self.tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil];
    if ([self learnedRecordForPeerGroupPub:peerPub] != nil && self.announcementLearnedExpectation) {
        [self.announcementLearnedExpectation fulfill];
        self.announcementLearnedExpectation = nil;
    }
}

- (void)tox:(OCTTox *)tox groupCustomPrivatePacketWithGroupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
       data:(NSData *)data
{
    BOOL consumed = [self.signedHistory handleIncomingPacketWithGroupNumber:groupNumber peerId:peerId data:data];
    NSLog(@"NGC_SIG [%@] private in peer=%u len=%lu consumed=%d",
          self.label, peerId, (unsigned long)data.length, consumed);

    if (consumed && self.signedRecordExpectation) {
        [self.signedRecordExpectation fulfill];
        self.signedRecordExpectation = nil;
    }
}

@end

#pragma mark - the test

@interface OCTNgcSignedHistoryDualIntegrationTests : XCTestCase

@property (strong, nonatomic) OCTSignedHistoryTestPeer *founder;
@property (strong, nonatomic) OCTSignedHistoryTestPeer *joiner;
@property (assign, nonatomic) BOOL skipped;

@end

@implementation OCTNgcSignedHistoryDualIntegrationTests

- (void)setUp
{
    [super setUp];

    if ([[NSProcessInfo processInfo].environment[@"SKIP_NGC_NETWORK_TESTS"] boolValue]) {
        NSLog(@"SKIP_NGC_NETWORK_TESTS=1, skipping signed-history wire test");
        self.skipped = YES;
        return;
    }

    OCTToxOptions *founderOptions = [OCTToxOptions new];
    founderOptions.udpEnabled = YES;
    founderOptions.ipv6Enabled = YES;
    founderOptions.localDiscoveryEnabled = YES;
    founderOptions.startPort = 33745;
    founderOptions.endPort = 33844;

    OCTToxOptions *joinerOptions = [OCTToxOptions new];
    joinerOptions.udpEnabled = YES;
    joinerOptions.ipv6Enabled = YES;
    joinerOptions.localDiscoveryEnabled = YES;
    joinerOptions.startPort = 33845;
    joinerOptions.endPort = 33944;

    OCTTox *founderTox = [[OCTTox alloc] initWithOptions:founderOptions savedData:nil error:nil];
    OCTTox *joinerTox = [[OCTTox alloc] initWithOptions:joinerOptions savedData:nil error:nil];

    self.founder = [[OCTSignedHistoryTestPeer alloc] initWithLabel:@"founder" tox:founderTox];
    self.joiner = [[OCTSignedHistoryTestPeer alloc] initWithLabel:@"joiner" tox:joinerTox];

    founderTox.delegate = self.founder;
    joinerTox.delegate = self.joiner;

    [self bootstrapTox:founderTox label:@"founder"];
    [self bootstrapTox:joinerTox label:@"joiner"];

    [founderTox start];
    [joinerTox start];
}

- (void)tearDown
{
    [self.founder.tox stop];
    [self.joiner.tox stop];
    self.founder = nil;
    self.joiner = nil;
    [super tearDown];
}

- (void)bootstrapTox:(OCTTox *)tox label:(NSString *)label
{
    // KHANDAQ: the self-hosted bootstrap*.khandaq.org nodes were RETIRED (config/khandaq_bootstrap_nodes.json
    // marks all three status=retired, and their DNS no longer resolves), so a test that insists on
    // them fails before it reaches anything it means to test — which is why this list matches what
    // the shipped clients actually use (BootstrapNodeEntryDB.java). A single unreachable node is not
    // a failure either: the assertion is that we got at least one, not that the whole public DHT is
    // up on the morning the test runs.
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
            NSLog(@"NGC_SIG [%@] bootstrap %@ unavailable: %@", label, host, error);
        }

        error = nil;
        [tox addTCPRelayWithHost:host port:nodes[i].port publicKey:key error:&error];
    }

    XCTAssertGreaterThan(reached, 0u, @"[%@] not a single bootstrap node could be reached", label);
}

- (void)waitForDHT
{
    self.founder.dhtExpectation = [self expectationWithDescription:@"founder DHT"];
    self.joiner.dhtExpectation = [self expectationWithDescription:@"joiner DHT"];

    if (self.founder.tox.connectionStatus != OCTToxConnectionStatusNone) {
        [self.founder.dhtExpectation fulfill];
        self.founder.dhtExpectation = nil;
    }
    if (self.joiner.tox.connectionStatus != OCTToxConnectionStatusNone) {
        [self.joiner.dhtExpectation fulfill];
        self.joiner.dhtExpectation = nil;
    }

    [self waitForExpectationsWithTimeout:kDHTConnectTimeout handler:nil];
}

/**
 * Announcement out, key learned, signed record out, verdict stored — all of it between two
 * separate Tox instances, plus the two refusals that make the verdict worth anything.
 */
- (void)testSignedHistoryVerifiesAcrossTwoToxInstances
{
    if (self.skipped) {
        return;
    }

    [self waitForDHT];

    NSError *error = nil;
    NSString *stamp = [NSString stringWithFormat:@"%lu", (unsigned long)[[NSDate date] timeIntervalSince1970]];
    NSString *groupName = [NSString stringWithFormat:@"iOSSIG_%@", stamp];

    // Two instances are joined through a FRIEND INVITE, not through a public chat-id. A public group
    // has to announce itself into the DHT before anyone can find it by chat-id, and between two
    // instances on one machine behind one NAT that did not happen in 300 s (measured twice) — the
    // mesh, not the feature, is what failed. An invite travels over an existing friend link, so it
    // works wherever the two can already reach each other. Same code path afterwards: the group is a
    // real NGC group and every packet below is a real packet.
    NSString *founderPub = [[self.founder.tox userAddress] substringToIndex:kOCTToxPublicKeyLength];
    NSString *joinerPub = [[self.joiner.tox userAddress] substringToIndex:kOCTToxPublicKeyLength];

    OCTToxFriendNumber founderSideFriend = [self.founder.tox addFriendWithNoRequestWithPublicKey:joinerPub error:&error];
    XCTAssertNotEqual(founderSideFriend, kOCTToxFriendNumberFailure, @"founder could not add the joiner: %@", error);
    OCTToxFriendNumber joinerSideFriend = [self.joiner.tox addFriendWithNoRequestWithPublicKey:founderPub error:&error];
    XCTAssertNotEqual(joinerSideFriend, kOCTToxFriendNumberFailure, @"joiner could not add the founder: %@", error);

    self.founder.friendOnlineExpectation = [self expectationWithDescription:@"founder sees the joiner online"];
    self.joiner.friendOnlineExpectation = [self expectationWithDescription:@"joiner sees the founder online"];
    [self waitForExpectationsWithTimeout:kFriendOnlineTimeout handler:nil];

    OCTToxGroupNumber founderGroup = [self.founder.tox groupNewWithPrivacyState:OCTToxGroupPrivacyStatePrivate
                                                                      groupName:groupName
                                                                       peerName:[@"F_" stringByAppendingString:stamp]
                                                                          error:&error];
    XCTAssertNotEqual(founderGroup, kOCTToxGroupNumberFailure, @"groupNew failed: %@", error);
    self.founder.groupNumber = founderGroup;

    NSString *chatId = [self.founder.tox groupChatIdHexForGroupNumber:founderGroup error:&error];
    XCTAssertEqual(chatId.length, kOCTToxGroupChatIdHexLength);

    self.joiner.groupInviteExpectation = [self expectationWithDescription:@"joiner received the invite"];
    XCTAssertTrue([self.founder.tox groupInviteFriendWithGroupNumber:founderGroup
                                                        friendNumber:founderSideFriend
                                                               error:&error],
                  @"group invite failed: %@", error);
    [self waitForExpectationsWithTimeout:kGroupMeshTimeout handler:nil];
    XCTAssertNotNil(self.joiner.pendingInviteData, @"no invite arrived, nothing to accept");

    OCTToxGroupNumber joinerGroup = [self.joiner.tox groupInviteAcceptWithFriendNumber:self.joiner.pendingInviteFriendNumber
                                                                            inviteData:self.joiner.pendingInviteData
                                                                              peerName:[@"J_" stringByAppendingString:stamp]
                                                                              password:nil
                                                                                 error:&error];
    XCTAssertNotEqual(joinerGroup, kOCTToxGroupNumberFailure, @"accepting the invite failed: %@", error);
    self.joiner.groupNumber = joinerGroup;

    self.joiner.groupConnectedExpectation = [self expectationWithDescription:@"joiner group connected"];
    self.founder.peerJoinExpectation = [self expectationWithDescription:@"founder saw the joiner"];
    [self waitForExpectationsWithTimeout:kGroupMeshTimeout handler:nil];

    // ---- the announcement, over the group's live broadcast channel -------------------------------
    //
    // This is the step that failed silently on Android in both directions while every test passed:
    // the signature has to be made over the per-group public key, which is the ONLY identity a
    // receiver can reconstruct for a group peer.
    XCTAssertTrue([self.founder.signedHistory prepareKeyForGroupNumber:founderGroup],
                  @"founder could not resolve its signing key");

    self.joiner.announcementLearnedExpectation = [self expectationWithDescription:@"joiner learned the key"];
    XCTAssertTrue([self.founder.announce announceToGroupNumber:founderGroup force:YES],
                  @"announce failed to go out");
    [self waitForExpectationsWithTimeout:kPacketTimeout handler:nil];

    NSString *founderGroupPub = [self.founder.tox groupSelfPublicKeyHexForGroupNumber:founderGroup error:nil];
    OCTNgcHskRecord *learned = [self.joiner learnedRecordForPeerGroupPub:founderGroupPub];
    XCTAssertNotNil(learned, @"the joiner consumed an announcement but stored no key");
    XCTAssertEqual(learned.hskPub.length, 32u);

    // ---- the signed record, unicast to the peer that asked for history --------------------------
    uint32_t joinerPeerIdOnFounder = self.founder.remotePeerId;
    uint32_t messageId = 0xA1B2C3D4;
    uint64_t timestamp = (uint64_t)[[NSDate date] timeIntervalSince1970];
    NSString *text = [NSString stringWithFormat:@"signed history on the wire %@", stamp];

    self.joiner.signedRecordExpectation = [self expectationWithDescription:@"joiner got the signed record"];
    XCTAssertTrue([self.founder.signedHistory sendSignedTextToGroupNumber:founderGroup
                                                                   peerId:joinerPeerIdOnFounder
                                                             authorPubHex:founderGroupPub
                                                                messageId:messageId
                                                                timestamp:timestamp
                                                                 peerName:@"F"
                                                                     text:text],
                  @"sending the signed record failed");
    [self waitForExpectationsWithTimeout:kPacketTimeout handler:nil];

    // ---- the verdict ---------------------------------------------------------------------------
    NSString *joinerChatId = [self.joiner.tox groupChatIdHexForGroupNumber:joinerGroup error:nil];
    XCTAssertTrue([self.joiner.signedHistory isAuthorVerifiedForGroupId:joinerChatId
                                                              messageId:messageId
                                                           authorPubHex:founderGroupPub
                                                              timestamp:timestamp
                                                                   text:text],
                  @"the record arrived and verified, but the verdict does not read back");

    // A verdict must vouch for the message it covered and nothing else. Before the verdict carried
    // the timestamp and the text hash, both of these came back YES — a genuine signed record could
    // be relayed and then any row reusing its four-byte msg_id read as verified.
    XCTAssertFalse([self.joiner.signedHistory isAuthorVerifiedForGroupId:joinerChatId
                                                               messageId:messageId
                                                            authorPubHex:founderGroupPub
                                                               timestamp:timestamp
                                                                    text:[text stringByAppendingString:@" (forged)"]],
                   @"a verdict vouched for text the signature never covered");
    XCTAssertFalse([self.joiner.signedHistory isAuthorVerifiedForGroupId:joinerChatId
                                                               messageId:messageId
                                                            authorPubHex:founderGroupPub
                                                               timestamp:timestamp + 1
                                                                    text:text],
                   @"a verdict vouched for a timestamp the signature never covered");

    // An author we never heard announce a key cannot be vouched for at all.
    NSString *strangerPub = [@"" stringByPaddingToLength:64 withString:@"AB" startingAtIndex:0];
    XCTAssertFalse([self.joiner.signedHistory isAuthorVerifiedForGroupId:joinerChatId
                                                               messageId:messageId
                                                            authorPubHex:strangerPub
                                                               timestamp:timestamp
                                                                    text:text],
                   @"a stranger's row read as verified");

    // XCTAssert does not stop an ObjC test method, so a bare "PASS" here prints even when every
    // assertion above failed — exactly the log line that makes a broken run read as a good one.
    NSLog(@"NGC_SIG DONE (see assertions above) chatId=%@ author=%@ msgId=%u",
          chatId, founderGroupPub, messageId);

    [self.founder.tox groupLeaveWithGroupNumber:founderGroup partMessage:nil error:&error];
    [self.joiner.tox groupLeaveWithGroupNumber:joinerGroup partMessage:nil error:&error];
}

@end
