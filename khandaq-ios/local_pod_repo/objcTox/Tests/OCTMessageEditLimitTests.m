// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>
#import <OCMock/OCMock.h>

#import "OCTChat.h"
#import "OCTFriend.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageText.h"
#import "OCTRealmManager.h"
#import "OCTSubmanagerChatsImpl.h"
#import "OCTSubmanagerDataSource.h"
#import "OCTSubmanagerGroupsImpl.h"
#import "OCTTox.h"
#import "OCTTox+Private.h"
#import "OCTToxConstants.h"

static const uint32_t kEditLimitTestsGroupNumber = 6;
static const OCTToxFriendNumber kEditLimitTestsFriendNumber = 5;
static NSString *const kEditLimitTestsChatIdHex = @"5A1C0E9B3D7F2468ACE13579BDF02468ACE13579BDF02468ACE13579BDF02468";
static NSString *const kEditLimitTestsFriendKeyHex = @"0F1E2D3C4B5A69788796A5B4C3D2E1F00F1E2D3C4B5A69788796A5B4C3D2E1F0";
static NSString *const kEditLimitTestsHashHex = @"00112233445566778899AABBCCDDEEFF00112233445566778899AABBCCDDEEFF";

// The headers in front of the new text: {186, 'K', 'Q', 1}, the 32-byte hash and the stamp with one person;
// the 8-byte magic, the message id and the stamp in a group.
static const NSUInteger kEditLimitTestsFriendHeaderBytes = 4 + 32 + 4;
static const NSUInteger kEditLimitTestsGroupHeaderBytes = 8 + 4 + 4;

/// A text of `bytes` UTF-8 bytes: Russian letters, two bytes each, as the long edits were written; an odd count
/// ends with one Latin letter.
static NSString *OCTEditLimitTestsText(NSUInteger bytes)
{
    NSMutableString *text = [NSMutableString new];
    for (NSUInteger index = 0; index < bytes / 2; index++) {
        [text appendString:@"ж"];
    }
    if (bytes % 2 == 1) {
        [text appendString:@"a"];
    }
    return text;
}

/**
 * KHANDAQ (ideas 1): an edit is no longer than the packet that carries it.
 *
 * An edit changed the text on this device first and only then went to the others. A new text longer than a
 * custom packet holds never reached anybody: in a group the send failed silently and the group kept the old
 * text while this device showed the new one; with one person the edit stayed pending and failed again on every
 * reconnect. Both now take at most Android's 1300 bytes (kOCTMaxEditTextBytes) and change nothing beyond it.
 */
@interface OCTMessageEditLimitTests : XCTestCase

@property (copy, nonatomic) NSString *root;
@property (strong, nonatomic) OCTRealmManager *realmManager;
@property (strong, nonatomic) id dataSource;
@property (strong, nonatomic) id tox;
/// The length of every packet an edit handed to Tox, with one person or in a group.
@property (strong, nonatomic) NSMutableArray<NSNumber *> *sentPacketLengths;

@end

@implementation OCTMessageEditLimitTests

- (void)setUp
{
    [super setUp];

    self.root = [NSTemporaryDirectory() stringByAppendingPathComponent:[[NSUUID UUID] UUIDString]];
    [[NSFileManager defaultManager] createDirectoryAtPath:self.root withIntermediateDirectories:YES attributes:nil error:nil];
    NSURL *databaseURL = [NSURL fileURLWithPath:[self.root stringByAppendingPathComponent:@"database.realm"]];
    self.realmManager = [[OCTRealmManager alloc] initWithDatabaseFileURL:databaseURL encryptionKey:nil];

    self.tox = OCMClassMock([OCTTox class]);
    self.dataSource = OCMProtocolMock(@protocol(OCTSubmanagerDataSource));
    OCMStub([self.dataSource managerGetRealmManager]).andReturn(self.realmManager);
    OCMStub([self.dataSource managerGetTox]).andReturn(self.tox);
    OCMStub([self.dataSource managerGetNotificationCenter]).andReturn([NSNotificationCenter new]);

    NSMutableArray<NSNumber *> *lengths = [NSMutableArray new];
    self.sentPacketLengths = lengths;

    [[[[self.tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        OCTToxFriendNumber friendNumber = kEditLimitTestsFriendNumber;
        [invocation setReturnValue:&friendNumber];
    }] friendNumberWithPublicKey:[OCMArg any] error:NULL];
    [[[[self.tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        __unsafe_unretained NSData *packet = nil;
        [invocation getArgument:&packet atIndex:3];
        [lengths addObject:@(packet.length)];
        BOOL sent = YES;
        [invocation setReturnValue:&sent];
    }] sendLosslessPacketWithFriendNumber:0 bytes:[OCMArg any] error:NULL];
    [[[[self.tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        __unsafe_unretained NSData *packet = nil;
        [invocation getArgument:&packet atIndex:2];
        [lengths addObject:@(packet.length)];
        BOOL sent = YES;
        [invocation setReturnValue:&sent];
    }] groupSendCustomPacket:[OCMArg any] groupNumber:0 lossless:NO error:NULL];
}

- (void)tearDown
{
    [self.tox stopMocking];
    self.tox = nil;
    self.dataSource = nil;
    self.realmManager = nil;
    [[NSFileManager defaultManager] removeItemAtPath:self.root error:nil];

    [super tearDown];
}

#pragma mark - With one person

- (void)testAnEditLongerThanItsPacketCarriesLeavesTheTextAndSendsNothing
{
    OCTMessageAbstract *message = [self storedOwnTextToFriend];

    [[self chats] editMessage:message newText:OCTEditLimitTestsText(kOCTMaxEditTextBytes + 1)];

    XCTAssertEqualObjects(message.messageText.text, @"salam");
    XCTAssertFalse(message.edited);
    XCTAssertFalse(message.editPending, @"an edit that can never go is not left waiting");
    XCTAssertEqualObjects(self.sentPacketLengths, @[]);
}

- (void)testAnEditAtTheLimitChangesTheTextAndGoesInOnePacket
{
    OCTMessageAbstract *message = [self storedOwnTextToFriend];
    NSString *text = OCTEditLimitTestsText(kOCTMaxEditTextBytes);

    [[self chats] editMessage:message newText:text];

    XCTAssertEqualObjects(message.messageText.text, text);
    XCTAssertTrue(message.edited);
    XCTAssertFalse(message.editPending);
    XCTAssertEqualObjects(self.sentPacketLengths, @[@(kEditLimitTestsFriendHeaderBytes + kOCTMaxEditTextBytes)]);
    XCTAssertLessThanOrEqual(kEditLimitTestsFriendHeaderBytes + kOCTMaxEditTextBytes, kOCTToxMaxCustomPacketSize);
}

- (void)testTheSpacesAroundAnEditDoNotCountTowardsTheLimit
{
    OCTMessageAbstract *message = [self storedOwnTextToFriend];
    NSString *text = OCTEditLimitTestsText(kOCTMaxEditTextBytes);

    [[self chats] editMessage:message newText:[NSString stringWithFormat:@"  %@\n", text]];

    XCTAssertEqualObjects(message.messageText.text, text);
    XCTAssertEqual(self.sentPacketLengths.count, 1u);
}

#pragma mark - In a group

- (void)testAGroupEditLongerThanItsPacketCarriesLeavesTheTextAndSendsNothing
{
    OCTChat *chat = [self groupChat];
    OCTMessageAbstract *message = [self storedOwnTextInGroup:chat];

    [[self groups] editGroupMessage:message inChat:chat newText:OCTEditLimitTestsText(kOCTMaxEditTextBytes + 1)];

    XCTAssertEqualObjects(message.messageText.text, @"salam");
    XCTAssertFalse(message.edited);
    XCTAssertEqualObjects(self.sentPacketLengths, @[]);
}

- (void)testAGroupEditAtTheLimitChangesTheTextAndGoesInOnePacket
{
    OCTChat *chat = [self groupChat];
    OCTMessageAbstract *message = [self storedOwnTextInGroup:chat];
    NSString *text = OCTEditLimitTestsText(kOCTMaxEditTextBytes);

    [[self groups] editGroupMessage:message inChat:chat newText:text];

    XCTAssertEqualObjects(message.messageText.text, text);
    XCTAssertTrue(message.edited);
    XCTAssertEqualObjects(self.sentPacketLengths, @[@(kEditLimitTestsGroupHeaderBytes + kOCTMaxEditTextBytes)]);
    XCTAssertLessThanOrEqual(kEditLimitTestsGroupHeaderBytes + kOCTMaxEditTextBytes, kOCTToxMaxCustomPacketSize);
}

#pragma mark - Private

- (OCTSubmanagerChatsImpl *)chats
{
    OCTSubmanagerChatsImpl *chats = [OCTSubmanagerChatsImpl new];
    chats.dataSource = self.dataSource;
    return chats;
}

- (OCTSubmanagerGroupsImpl *)groups
{
    OCTSubmanagerGroupsImpl *groups = [OCTSubmanagerGroupsImpl new];
    groups.dataSource = self.dataSource;
    return groups;
}

/// An own text to a friend, stored as the app stores it, with the hash an edit points at.
- (OCTMessageAbstract *)storedOwnTextToFriend
{
    OCTFriend *friend = [OCTFriend new];
    friend.nickname = @"";
    friend.publicKey = kEditLimitTestsFriendKeyHex;
    friend.friendNumber = kEditLimitTestsFriendNumber;
    [self.realmManager addObject:friend];
    OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];

    OCTMessageAbstract *message = [OCTMessageAbstract new];
    message.chatUniqueIdentifier = chat.uniqueIdentifier;
    message.dateInterval = [[NSDate date] timeIntervalSince1970];
    message.messageText = [OCTMessageText new];
    message.messageText.text = @"salam";
    message.messageText.msgv3HashHex = kEditLimitTestsHashHex;
    [self.realmManager addObject:message];

    return message;
}

- (OCTChat *)groupChat
{
    return [self.realmManager getOrCreateGroupChatWithGroupNumber:kEditLimitTestsGroupNumber
                                                        chatIdHex:kEditLimitTestsChatIdHex
                                                        groupName:@"edit-limit"
                                                     privacyState:OCTToxGroupPrivacyStatePublic];
}

/// An own text in the group, with the id the group shares for it.
- (OCTMessageAbstract *)storedOwnTextInGroup:(OCTChat *)chat
{
    OCTMessageAbstract *message = [OCTMessageAbstract new];
    message.chatUniqueIdentifier = chat.uniqueIdentifier;
    message.dateInterval = [[NSDate date] timeIntervalSince1970];
    message.groupSenderPeerId = 0;
    message.messageText = [OCTMessageText new];
    message.messageText.text = @"salam";
    message.messageText.messageId = 42;
    [self.realmManager addObject:message];

    return message;
}

@end
