// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>
#import <OCMock/OCMock.h>
#import <Realm/Realm.h>

#import "OCTRealmManager.h"
#import "OCTChat.h"
#import "OCTFriend.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageFile.h"
#import "OCTMessageText.h"
#import "OCTNgcGroupFileTransfer.h"
#import "OCTNgcGroupHistSync.h"
#import "OCTSubmanagerGroupsImpl.h"
#import "OCTSubmanagerDataSource.h"
#import "OCTFileStorageProtocol.h"
#import "OCTTox.h"
#import "OCTTox+Private.h"

@interface OCTRealmManager (GroupRealmTests)

@property (strong, nonatomic) RLMRealm *realm;

@end

@interface OCTSubmanagerGroupsImpl (GroupRealmTests)

- (void)setupFileTransferIfNeeded;

@end

@interface OCTNgcGroupFileTransfer (GroupRealmTests)

- (NSMutableDictionary<NSString *, id> *)assemblies;
- (void)nackTickForAssembly:(id)assembly assemblyKey:(NSString *)assemblyKey;

@end

static const uint32_t kGroupRealmTestsGroupNumber = 4;
static const uint32_t kGroupRealmTestsPeerId = 2;
static NSString *const kGroupRealmTestsChatIdHex = @"3C5E9A0B7D214F6A8B0C1D2E3F405162738495A6B7C8D9E0F1021324354657A8";
static NSString *const kGroupRealmTestsPeerKeyHex = @"9F8E7D6C5B4A39281706F5E4D3C2B1A0918273645546372819A0B1C2D3E4F506";
static NSString *const kGroupRealmTestsSelfKeyHex = @"1A2B3C4D5E6F708192A3B4C5D6E7F8091A2B3C4D5E6F708192A3B4C5D6E7F809";

/**
 * KHANDAQ (0.2.56): group behaviour against a real Realm, opened on the main thread as the app opens it, so
 * the Realm belongs to that thread exactly as it does in the app.
 *
 * Every incoming group file crashed the app from 0.2.52 on: the file transfer parses incoming packets on its
 * own queue, and it called the directory block there, which looks the group's chat up in the Realm ("Realm
 * accessed from incorrect thread"); the complete block, which stores the file's row, ran there too.
 */
@interface OCTGroupRealmTests : XCTestCase

@property (copy, nonatomic) NSString *root;
@property (strong, nonatomic) OCTRealmManager *realmManager;

@end

@implementation OCTGroupRealmTests

- (void)setUp
{
    [super setUp];

    self.root = [NSTemporaryDirectory() stringByAppendingPathComponent:[[NSUUID UUID] UUIDString]];
    [[NSFileManager defaultManager] createDirectoryAtPath:self.root withIntermediateDirectories:YES attributes:nil error:nil];

    NSURL *databaseURL = [NSURL fileURLWithPath:[self.root stringByAppendingPathComponent:@"database.realm"]];
    self.realmManager = [[OCTRealmManager alloc] initWithDatabaseFileURL:databaseURL encryptionKey:nil];
}

- (void)tearDown
{
    self.realmManager = nil;
    [[NSFileManager defaultManager] removeItemAtPath:self.root error:nil];

    [super tearDown];
}

#pragma mark - An incoming group file

- (void)testAGroupFileInOnePacketTouchesTheRealmOnlyOnTheMainThread
{
    OCTChat *chat = [self groupChat];
    NSDictionary *rig = [self realmBackedTransfer];
    NSData *payload = [self generatedBytesOfLength:5000];
    NSData *msgId = [self generatedBytesOfLength:32];

    [rig[@"transfer"] handleIncomingPacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                                   peerId:kGroupRealmTestsPeerId
                                         peerPublicKeyHex:kGroupRealmTestsPeerKeyHex
                                                     data:[self singlePacketWithMsgId:msgId fileName:@"note.bin" payload:payload]];

    [self assertTransferRig:rig completesOneFileWithPayload:payload msgId:msgId inChat:chat];
}

- (void)testAGroupFileInChunksTouchesTheRealmOnlyOnTheMainThread
{
    OCTChat *chat = [self groupChat];
    NSDictionary *rig = [self realmBackedTransfer];
    NSData *payload = [self generatedBytesOfLength:2500];
    NSData *msgId = [self generatedBytesOfLength:32];
    OCTNgcGroupFileTransfer *transfer = rig[@"transfer"];
    const uint32_t chunkPayload = 1000;

    NSMutableArray<NSData *> *packets = [NSMutableArray new];
    [packets addObject:[self beginPacketWithMsgId:msgId fileName:@"clip.bin" size:payload.length chunkPayload:chunkPayload]];

    for (uint32_t index = 0; index * chunkPayload < payload.length; index++) {
        NSUInteger length = MIN((NSUInteger)chunkPayload, payload.length - index * chunkPayload);
        [packets addObject:[self chunkPacketWithMsgId:msgId
                                                index:index
                                              payload:[payload subdataWithRange:NSMakeRange(index * chunkPayload, length)]]];
    }

    for (NSData *packet in packets) {
        [transfer handleIncomingPacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                               peerId:kGroupRealmTestsPeerId
                                     peerPublicKeyHex:kGroupRealmTestsPeerKeyHex
                                                 data:packet];
    }

    [self assertTransferRig:rig completesOneFileWithPayload:payload msgId:msgId inChat:chat];
    XCTAssertEqualObjects(rig[@"begins"], @[@"clip.bin"], @"the BEGIN did not reach the chat");
}

// The blocks the app really gives the transfer, on a real Realm: a file from a peer becomes a ready row of its
// chat. Before the fix this test crashes the test process, as the app crashed.
- (void)testAGroupFileFromAPeerBecomesAReadyRowOfItsChat
{
    OCTChat *chat = [self groupChat];
    NSString *downloads = [self.root stringByAppendingPathComponent:@"Downloads"];

    id dataSource = OCMProtocolMock(@protocol(OCTSubmanagerDataSource));
    id tox = OCMClassMock([OCTTox class]);
    id fileStorage = OCMProtocolMock(@protocol(OCTFileStorageProtocol));
    OCMStub([dataSource managerGetTox]).andReturn(tox);
    OCMStub([dataSource managerGetRealmManager]).andReturn(self.realmManager);
    OCMStub([dataSource managerGetFileStorage]).andReturn(fileStorage);
    OCMStub([fileStorage pathForDownloadedFilesDirectory]).andReturn(downloads);

    OCTSubmanagerGroupsImpl *submanager = [OCTSubmanagerGroupsImpl new];
    submanager.dataSource = dataSource;
    [submanager setupFileTransferIfNeeded];
    OCTNgcGroupFileTransfer *transfer = [submanager valueForKey:@"fileTransfer"];
    XCTAssertNotNil(transfer);

    NSData *payload = [self generatedBytesOfLength:3000];
    NSData *msgId = [self generatedBytesOfLength:32];

    [transfer handleIncomingPacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                           peerId:kGroupRealmTestsPeerId
                                 peerPublicKeyHex:kGroupRealmTestsPeerKeyHex
                                             data:[self singlePacketWithMsgId:msgId fileName:@"photo.jpg" payload:payload]];

    __block OCTMessageAbstract *row = nil;
    [self waitUntil:^BOOL {
        row = [self fileRowWithMsgId:msgId inChat:chat];
        return row.messageFile.fileType == OCTMessageFileTypeReady;
    }];

    XCTAssertEqual(row.messageFile.fileType, OCTMessageFileTypeReady, @"the file did not become a ready row");
    XCTAssertEqual(row.messageFile.fileSize, (OCTToxFileSize)payload.length);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:row.messageFile.filePath], payload);
    XCTAssertTrue([row.messageFile.filePath hasPrefix:downloads], @"%@", row.messageFile.filePath);
    XCTAssertEqualObjects(row.messageFile.groupSenderPubkey, kGroupRealmTestsPeerKeyHex.lowercaseString);

    // Every mock lives until here: the submanager holds its data source weakly.
    NSArray *mocks = @[dataSource, tox, fileStorage];
    XCTAssertEqual(mocks.count, 3u);
}

// KHANDAQ (0.2.57, SPEC-community item 6): Khandaq Community deletes the bytes of a file that waited, to make room,
// and keeps its message, which then says the file went. A second file of the same name took the free path: the old
// message showed the new file as its own, with Accept again, and the cleanup took the two for one file.
- (void)testAGroupFileNeverTakesThePathOfAMessageWhoseFileWent
{
    OCTChat *chat = [self groupChat];
    NSString *downloads = [self.root stringByAppendingPathComponent:@"Downloads"];

    id dataSource = OCMProtocolMock(@protocol(OCTSubmanagerDataSource));
    id tox = OCMClassMock([OCTTox class]);
    id fileStorage = OCMProtocolMock(@protocol(OCTFileStorageProtocol));
    OCMStub([dataSource managerGetTox]).andReturn(tox);
    OCMStub([dataSource managerGetRealmManager]).andReturn(self.realmManager);
    OCMStub([dataSource managerGetFileStorage]).andReturn(fileStorage);
    OCMStub([fileStorage pathForDownloadedFilesDirectory]).andReturn(downloads);

    OCTSubmanagerGroupsImpl *submanager = [OCTSubmanagerGroupsImpl new];
    submanager.dataSource = dataSource;
    [submanager setupFileTransferIfNeeded];
    OCTNgcGroupFileTransfer *transfer = [submanager valueForKey:@"fileTransfer"];
    NSString *folder = [downloads stringByAppendingPathComponent:[@"group/" stringByAppendingString:kGroupRealmTestsChatIdHex.lowercaseString]];
    XCTAssertEqualObjects([submanager groupFilesDirectoryForChatIdHex:kGroupRealmTestsChatIdHex], folder,
                          @"the folder is known from the chat id alone");

    NSData *firstId = [self generatedBytesOfLength:32];
    [transfer handleIncomingPacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                           peerId:kGroupRealmTestsPeerId
                                 peerPublicKeyHex:kGroupRealmTestsPeerKeyHex
                                             data:[self singlePacketWithMsgId:firstId fileName:@"photo.jpg" payload:[self generatedBytesOfLength:3000]]];
    __block OCTMessageAbstract *first = nil;
    [self waitUntil:^BOOL {
        first = [self fileRowWithMsgId:firstId inChat:chat];
        return first.messageFile.fileType == OCTMessageFileTypeReady;
    }];
    NSString *firstPath = first.messageFile.filePath;
    XCTAssertEqualObjects(firstPath.lastPathComponent, @"photo.jpg");

    // Its bytes go to make room; its message stays.
    XCTAssertTrue([[NSFileManager defaultManager] removeItemAtPath:firstPath error:nil]);

    NSData *secondId = [self generatedBytesOfLength:32];
    NSData *second = [self generatedBytesOfLength:2000];
    [transfer handleIncomingPacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                           peerId:kGroupRealmTestsPeerId
                                 peerPublicKeyHex:kGroupRealmTestsPeerKeyHex
                                             data:[self singlePacketWithMsgId:secondId fileName:@"photo.jpg" payload:second]];
    NSData *hiddenId = [self generatedBytesOfLength:32];
    [transfer handleIncomingPacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                           peerId:kGroupRealmTestsPeerId
                                 peerPublicKeyHex:kGroupRealmTestsPeerKeyHex
                                             data:[self singlePacketWithMsgId:hiddenId fileName:@".profile" payload:[self generatedBytesOfLength:100]]];
    __block OCTMessageAbstract *next = nil;
    __block OCTMessageAbstract *hidden = nil;
    [self waitUntil:^BOOL {
        next = [self fileRowWithMsgId:secondId inChat:chat];
        hidden = [self fileRowWithMsgId:hiddenId inChat:chat];
        return next.messageFile.fileType == OCTMessageFileTypeReady && hidden.messageFile.fileType == OCTMessageFileTypeReady;
    }];

    XCTAssertEqualObjects(next.messageFile.filePath.lastPathComponent, @"photo 2.jpg");
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:next.messageFile.filePath], second);
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:first.messageFile.filePath], @"the first message got bytes again");
    XCTAssertEqualObjects(hidden.messageFile.filePath.lastPathComponent, @"_profile", @"a file of a group is never hidden");

    NSArray *mocks = @[dataSource, tox, fileStorage];
    XCTAssertEqual(mocks.count, 3u);
}

// KHANDAQ (0.2.57): seen live, a member's app away for 45 s in the middle of a 40 MB file. The group took two
// minutes to come back; a minute after the return the transfer dropped the file and its partial bytes, and the
// card said "41.9 MB left" until the next start of the app. It ends now as that start ends it.
- (void)testAGroupFileDroppedBeforeItIsCompleteEndsItsRowStopped
{
    OCTChat *chat = [self groupChat];
    NSString *downloads = [self.root stringByAppendingPathComponent:@"Downloads"];

    id dataSource = OCMProtocolMock(@protocol(OCTSubmanagerDataSource));
    id tox = OCMClassMock([OCTTox class]);
    id fileStorage = OCMProtocolMock(@protocol(OCTFileStorageProtocol));
    OCMStub([dataSource managerGetTox]).andReturn(tox);
    OCMStub([dataSource managerGetRealmManager]).andReturn(self.realmManager);
    OCMStub([dataSource managerGetFileStorage]).andReturn(fileStorage);
    OCMStub([fileStorage pathForDownloadedFilesDirectory]).andReturn(downloads);

    OCTSubmanagerGroupsImpl *submanager = [OCTSubmanagerGroupsImpl new];
    submanager.dataSource = dataSource;
    [submanager setupFileTransferIfNeeded];
    OCTNgcGroupFileTransfer *transfer = [submanager valueForKey:@"fileTransfer"];
    XCTAssertNotNil(transfer);

    NSData *payload = [self generatedBytesOfLength:3000];
    NSData *msgId = [self generatedBytesOfLength:32];
    const uint32_t chunkPayload = 1000;

    // The BEGIN and the first chunk come; the rest never does.
    [transfer handleIncomingPacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                           peerId:kGroupRealmTestsPeerId
                                 peerPublicKeyHex:kGroupRealmTestsPeerKeyHex
                                             data:[self beginPacketWithMsgId:msgId fileName:@"clip.mp4" size:payload.length chunkPayload:chunkPayload]];
    [transfer handleIncomingPacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                           peerId:kGroupRealmTestsPeerId
                                 peerPublicKeyHex:kGroupRealmTestsPeerKeyHex
                                             data:[self chunkPacketWithMsgId:msgId
                                                                       index:0
                                                                     payload:[payload subdataWithRange:NSMakeRange(0, chunkPayload)]]];

    __block NSString *assemblyKey = nil;
    __block id assembly = nil;
    [self waitUntil:^BOOL {
        NSMutableDictionary *assemblies = transfer.assemblies;
        @synchronized (assemblies) {
            assemblyKey = assemblies.allKeys.firstObject;
            assembly = assemblyKey ? assemblies[assemblyKey] : nil;
        }
        return [[assembly valueForKey:@"receivedCount"] intValue] == 1 && [self fileRowWithMsgId:msgId inChat:chat] != nil;
    }];

    OCTMessageAbstract *row = [self fileRowWithMsgId:msgId inChat:chat];
    XCTAssertEqual(row.messageFile.fileType, OCTMessageFileTypeLoading, @"the BEGIN made no loading row");
    NSString *partialPath = [assembly valueForKey:@"outPath"];
    XCTAssertTrue([[NSFileManager defaultManager] fileExistsAtPath:partialPath]);

    // A round that sees the chunk, then the silent rounds in a row that drop the file.
    for (int round = 0; round < 26; round++) {
        [transfer nackTickForAssembly:assembly assemblyKey:assemblyKey];
    }

    @synchronized (transfer.assemblies) {
        XCTAssertNil(transfer.assemblies[assemblyKey], @"the file was not dropped");
    }

    [self waitUntil:^BOOL {
        return [self fileRowWithMsgId:msgId inChat:chat].messageFile.fileType == OCTMessageFileTypeCanceled;
    }];

    row = [self fileRowWithMsgId:msgId inChat:chat];
    XCTAssertEqual(row.messageFile.fileType, OCTMessageFileTypeCanceled, @"the card of a dropped file still says it is coming");
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:partialPath]);

    // Every mock lives until here: the submanager holds its data source weakly.
    NSArray *mocks = @[dataSource, tox, fileStorage];
    XCTAssertEqual(mocks.count, 3u);
}

// KHANDAQ (0.2.57): the msg-id hash is public in the group. A dropped file ends only the loading row of the peer
// that opened it: never our own file under the same hash, never another member's row, never a file that came.
- (void)testADroppedGroupFileEndsOnlyTheLoadingRowOfThePeerThatOpenedIt
{
    OCTChat *chat = [self groupChat];
    OCTRealmManager *realmManager = self.realmManager;
    NSString *otherKeyHex = [kGroupRealmTestsPeerKeyHex stringByReplacingOccurrencesOfString:@"9F8E" withString:@"0000"];

    OCTMessageAbstract *theirs = [self fileRowNamed:@"theirs.mp4" type:OCTMessageFileTypeLoading peerId:kGroupRealmTestsPeerId
                                               hash:@"a1" inChat:chat];
    [realmManager updateObject:theirs.messageFile withBlock:^(OCTMessageFile *file) {
        file.groupSenderPubkey = kGroupRealmTestsPeerKeyHex.lowercaseString;
    }];
    OCTMessageAbstract *ours = [self fileRowNamed:@"ours.mp4" type:OCTMessageFileTypeLoading peerId:0 hash:@"b2" inChat:chat];
    OCTMessageAbstract *came = [self fileRowNamed:@"came.mp4" type:OCTMessageFileTypeReady peerId:kGroupRealmTestsPeerId
                                             hash:@"c3" inChat:chat];

    XCTAssertFalse([realmManager markGroupIncomingFileInterruptedInChat:chat msgIdHash:[self hashFrom:@"b2"] senderPubkey:nil]);
    XCTAssertFalse([realmManager markGroupIncomingFileInterruptedInChat:chat msgIdHash:[self hashFrom:@"c3"] senderPubkey:nil]);
    XCTAssertFalse([realmManager markGroupIncomingFileInterruptedInChat:chat msgIdHash:[self hashFrom:@"a1"] senderPubkey:otherKeyHex]);
    XCTAssertEqual(theirs.messageFile.fileType, OCTMessageFileTypeLoading, @"another member's drop ended this file");

    XCTAssertTrue([realmManager markGroupIncomingFileInterruptedInChat:chat
                                                             msgIdHash:[self hashFrom:@"a1"].uppercaseString
                                                          senderPubkey:kGroupRealmTestsPeerKeyHex]);
    XCTAssertFalse([realmManager markGroupIncomingFileInterruptedInChat:chat msgIdHash:[self hashFrom:@"a1"] senderPubkey:nil],
                   @"a file ended twice");

    XCTAssertEqual(theirs.messageFile.fileType, OCTMessageFileTypeCanceled);
    XCTAssertEqual(ours.messageFile.fileType, OCTMessageFileTypeLoading, @"our own file was ended by a drop");
    XCTAssertEqual(came.messageFile.fileType, OCTMessageFileTypeReady, @"a file that came was ended by a drop");
}

- (OCTMessageAbstract *)fileRowNamed:(NSString *)fileName
                                type:(OCTMessageFileType)fileType
                              peerId:(uint32_t)peerId
                                hash:(NSString *)hashSeed
                              inChat:(OCTChat *)chat
{
    return [self.realmManager addGroupMessageWithFileName:fileName
                                                 fileSize:3000
                                                 filePath:[self.root stringByAppendingPathComponent:fileName]
                                                 fileType:fileType
                                                     chat:chat
                                                   peerId:peerId
                                                  fileUTI:nil
                                        groupMsgIdHashHex:[self hashFrom:hashSeed]
                                    groupTransferProgress:0.0f];
}

- (NSString *)hashFrom:(NSString *)seed
{
    return [@"" stringByPaddingToLength:64 withString:seed startingAtIndex:0];
}

#pragma mark - The history sync

// KHANDAQ (0.2.56): a text written while nobody else was in the group waits in the outbox, with no id yet, and
// the history sync served it as well. The first member to join got it twice: the synced copy, at the time it
// was written and with id 0, and the live copy when the outbox sent it, so the two were out of order too, and
// no receiver could tell them for one message.
- (void)testATextWaitingInTheOutboxIsLeftOutOfTheHistorySyncUntilItWent
{
    OCTChat *chat = [self groupChat];
    OCTRealmManager *realmManager = self.realmManager;
    [realmManager addGroupMessageWithText:@"sent while a member was there"
                                     type:OCTToxMessageTypeNormal
                                     chat:chat
                                   peerId:0
                                 peerName:nil
                                messageId:71];
    OCTMessageAbstract *waiting = [realmManager addGroupPendingMessageWithText:@"written alone"
                                                                          type:OCTToxMessageTypeNormal
                                                                          chat:chat];
    OCTNgcGroupHistSync *histSync = [self historySyncForChat:chat];

    XCTAssertEqualObjects([self textsTheHistorySyncServesInChat:chat], @[@"sent while a member was there"],
                          @"the history sync served a text that has not gone yet");
    XCTAssertNil([histSync buildSyncPacketForMessage:waiting groupNumber:kGroupRealmTestsGroupNumber],
                 @"a text that has not gone yet was made a history packet");

    // It goes live, with the id toxcore gave it, and from then on the history sync serves it with that id.
    [realmManager markGroupPendingMessageSent:waiting messageId:72];

    XCTAssertEqualObjects([self textsTheHistorySyncServesInChat:chat], (@[@"sent while a member was there", @"written alone"]));
    NSData *packet = [histSync buildSyncPacketForMessage:waiting groupNumber:kGroupRealmTestsGroupNumber];
    XCTAssertGreaterThan(packet.length, 12u);
    if (packet.length > 12) {
        const uint8_t *bytes = packet.bytes;
        uint32_t messageId = ((uint32_t)bytes[8] << 24) | ((uint32_t)bytes[9] << 16) | ((uint32_t)bytes[10] << 8) | bytes[11];
        XCTAssertEqual(messageId, 72u, @"the text was served without the id it went with");
    }
}

#pragma mark - The order of a chat

// KHANDAQ (0.2.56): Android sends a long text as parts, one right after another, and msgV3 stamps each with
// its send time in whole seconds. The parts tied on sortTimestamp, which alone ordered the conversation, so
// they showed in no given order. They keep the order they came in.
- (void)testThePartsOfALongTextFromAFriendKeepTheOrderTheyCameIn
{
    OCTFriend *friend = [OCTFriend new];
    friend.nickname = @"friend";
    friend.publicKey = kGroupRealmTestsPeerKeyHex;
    [self.realmManager addObject:friend];
    OCTChat *chat = [self.realmManager getOrCreateChatWithFriend:friend];

    // A second before now, so that sortTimestamp is the send time and not the arrival.
    const UInt32 sentSecond = (UInt32)[[NSDate date] timeIntervalSince1970] - 1;
    NSArray<NSString *> *parts = [self partsOfALongText];

    for (NSUInteger index = 0; index < parts.count; index++) {
        [self.realmManager addMessageWithText:parts[index]
                                         type:OCTToxMessageTypeNormal
                                         chat:chat
                                       sender:friend
                                    messageId:(OCTToxMessageId)(index + 1)
                                 msgv3HashHex:nil
                                     sentPush:NO
                                       tssent:sentSecond
                                       tsrcvd:sentSecond];
    }

    XCTAssertEqualObjects([self textsInTheOrderOfChat:chat ascending:NO], parts.reverseObjectEnumerator.allObjects,
                          @"the parts of a long text are out of order, newest first");
    XCTAssertEqualObjects([self textsInTheOrderOfChat:chat ascending:YES], parts, @"the parts are out of order");
    XCTAssertEqualObjects(chat.lastMessage.messageText.text, parts.lastObject);
}

// KHANDAQ (0.2.56): the history sync carries the time of a message in whole seconds too. The parts of a long text
// a member wrote in the group, synced to somebody who joined later, tied on sortTimestamp and dateInterval alike.
- (void)testThePartsOfALongTextSyncedIntoAGroupKeepTheOrderTheyCameIn
{
    OCTChat *chat = [self groupChat];
    const NSTimeInterval sentSecond = floor([[NSDate date] timeIntervalSince1970]) - 60.0;
    NSArray<NSString *> *parts = [self partsOfALongText];
    NSMutableArray<OCTMessageAbstract *> *rows = [NSMutableArray new];

    for (NSUInteger index = 0; index < parts.count; index++) {
        [rows addObject:[self.realmManager addGroupSyncedMessageWithText:parts[index]
                                                                    type:OCTToxMessageTypeNormal
                                                                    chat:chat
                                                                  peerId:kGroupRealmTestsPeerId
                                                                peerName:@"member"
                                                         senderPubkeyHex:kGroupRealmTestsPeerKeyHex
                                                               messageId:(OCTToxMessageId)(index + 1)
                                                            dateInterval:sentSecond]];
    }

    XCTAssertEqualObjects([self textsInTheOrderOfChat:chat ascending:NO], parts.reverseObjectEnumerator.allObjects,
                          @"the synced parts of a long text are out of order, newest first");
    XCTAssertEqualObjects([self textsInTheOrderOfChat:chat ascending:YES], parts, @"the synced parts are out of order");

    // The time shown and the dedup keep the time the member sent them at.
    for (OCTMessageAbstract *row in rows) {
        XCTAssertEqual(row.dateInterval, sentSecond);
        XCTAssertEqual(row.groupSyncDedupTimestamp, sentSecond);
        XCTAssertLessThan(row.sortTimestamp, sentSecond + 1.0);
    }

    // With a part removed, the chat's last message is still the last part.
    [self.realmManager removeMessages:@[rows.firstObject]];
    XCTAssertEqualObjects(chat.lastMessage.messageText.text, parts.lastObject);
}

// KHANDAQ (0.2.57, review of the desktop twin): a record of the group's history whose author is our own key in
// that group (tox_group_self_get_public_key, not our Tox key). We hold our own messages already, so it is one of
// ours coming back from a member who re-serves it, or somebody claiming to be us. Kept as ours, its row would be
// outgoing and skip the Community hold; kept as somebody else's, our words would show under another name, and a
// file would be written and shown at once. It is dropped before anything: no row, no file on disk, no receipt.
// The same records under a member's key go in, so the key alone decides, whatever the case of the hex the core
// hands back for our key.
- (void)testAHistoryRecordUnderOurOwnGroupKeyIsDroppedBeforeAnything
{
    OCTChat *chat = [self groupChat];
    NSString *directory = [self.root stringByAppendingPathComponent:@"HistoryFiles"];
    [[NSFileManager defaultManager] createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil];
    NSMutableArray<NSNumber *> *receipts = [NSMutableArray new];
    const uint32_t now = (uint32_t)[[NSDate date] timeIntervalSince1970];
    NSData *payload = [self generatedBytesOfLength:2048];
    uint32_t messageId = 7100;

    for (NSString *selfKeyHex in @[kGroupRealmTestsSelfKeyHex, kGroupRealmTestsSelfKeyHex.lowercaseString]) {
        OCTNgcGroupHistSync *histSync = [self realmBackedHistorySyncForChat:chat
                                                                  directory:directory
                                                                 selfKeyHex:selfKeyHex
                                                                   receipts:receipts];
        NSUInteger rowsBefore = [self rowsOfChat:chat].count;
        NSUInteger filesBefore = [self filesInDirectory:directory].count;

        uint32_t ownTextId = ++messageId;
        NSData *ownFileHash = [self generatedBytesOfLength:32];
        [histSync handleIncomingPrivatePacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                                      peerId:kGroupRealmTestsPeerId
                                                        data:[self historyTextRecordWithMessageId:ownTextId
                                                                                     authorKeyHex:kGroupRealmTestsSelfKeyHex
                                                                                             time:now - 60
                                                                                             text:@"our own words"]];
        [histSync handleIncomingPrivatePacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                                      peerId:kGroupRealmTestsPeerId
                                                        data:[self historyFileRecordWithMsgIdHash:ownFileHash
                                                                                     authorKeyHex:kGroupRealmTestsSelfKeyHex
                                                                                             time:now - 60
                                                                                         fileName:@"ours.jpg"
                                                                                          payload:payload]];

        XCTAssertEqual([self rowsOfChat:chat].count, rowsBefore, @"a record under our own key made a row (%@)", selfKeyHex);
        XCTAssertEqual([self rowsOfChat:chat authoredBy:kGroupRealmTestsSelfKeyHex].count, 0u);
        XCTAssertEqual([self filesInDirectory:directory].count, filesBefore, @"a file under our own key reached the disk");

        // The same records under a member's key: they go in, and the text is receipted to its author.
        uint32_t memberTextId = ++messageId;
        [histSync handleIncomingPrivatePacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                                      peerId:kGroupRealmTestsPeerId
                                                        data:[self historyTextRecordWithMessageId:memberTextId
                                                                                     authorKeyHex:kGroupRealmTestsPeerKeyHex
                                                                                             time:now - 60
                                                                                             text:@"our own words"]];
        [histSync handleIncomingPrivatePacketWithGroupNumber:kGroupRealmTestsGroupNumber
                                                      peerId:kGroupRealmTestsPeerId
                                                        data:[self historyFileRecordWithMsgIdHash:[self generatedBytesOfLength:32]
                                                                                     authorKeyHex:kGroupRealmTestsPeerKeyHex
                                                                                             time:now - 60
                                                                                         fileName:@"ours.jpg"
                                                                                          payload:payload]];

        RLMResults *memberRows = [self rowsOfChat:chat authoredBy:kGroupRealmTestsPeerKeyHex];
        XCTAssertEqual([self rowsOfChat:chat].count, rowsBefore + 2, @"the member's records did not go in");
        XCTAssertEqual([self filesInDirectory:directory].count, filesBefore + 1);
        for (OCTMessageAbstract *row in memberRows) {
            XCTAssertTrue(row.groupHistorySync);
            XCTAssertFalse([row isOutgoing], @"a row from the history counts as ours");
        }

        // Receipts leave one after another from one queue: once the member's has gone, any for ours would have.
        [self waitUntil:^BOOL {
            @synchronized (receipts) {
                return [receipts containsObject:@(memberTextId)];
            }
        }];
        @synchronized (receipts) {
            XCTAssertTrue([receipts containsObject:@(memberTextId)], @"the member's text was not receipted");
            XCTAssertFalse([receipts containsObject:@(ownTextId)], @"a record under our own key was receipted");
        }
    }
}

#pragma mark - Rig

- (NSArray<NSString *> *)partsOfALongText
{
    NSMutableArray<NSString *> *parts = [NSMutableArray new];

    for (NSUInteger index = 1; index <= 12; index++) {
        [parts addObject:[NSString stringWithFormat:@"part %lu of a long generated text", (unsigned long)index]];
    }

    return parts;
}

- (NSArray<NSString *> *)textsInTheOrderOfChat:(OCTChat *)chat ascending:(BOOL)ascending
{
    RLMResults *timeline = [[OCTMessageAbstract objectsInRealm:self.realmManager.realm
                                                         where:@"chatUniqueIdentifier == %@", chat.uniqueIdentifier]
                            sortedResultsUsingDescriptors:[OCTMessageAbstract chatOrderSortDescriptorsAscending:ascending]];
    NSMutableArray<NSString *> *texts = [NSMutableArray new];

    for (OCTMessageAbstract *message in timeline) {
        [texts addObject:message.messageText.text ?: @"(no text)"];
    }

    return texts;
}

/**
 * A history sync for this client alone in the group of the chat, whose blocks store what it takes as the app's do,
 * through the realm manager, with files in `directory`. Every author resolves to the member kGroupRealmTestsPeerId,
 * so a record taken in would be receipted; the message ids of the receipts sent go to `receipts`.
 */
- (OCTNgcGroupHistSync *)realmBackedHistorySyncForChat:(OCTChat *)chat
                                             directory:(NSString *)directory
                                            selfKeyHex:(NSString *)selfKeyHex
                                              receipts:(NSMutableArray<NSNumber *> *)receipts
{
    OCTRealmManager *realmManager = self.realmManager;

    return [[OCTNgcGroupHistSync alloc] initWithSendPrivatePacketBlock:^BOOL (uint32_t groupNumber, uint32_t peerId, NSData *packet, NSError **error) {
        const uint8_t *bytes = packet.bytes;

        if (packet.length == 12 && bytes[7] == 0x04) {
            uint32_t receipted = ((uint32_t)bytes[8] << 24) | ((uint32_t)bytes[9] << 16) | ((uint32_t)bytes[10] << 8) | (uint32_t)bytes[11];
            @synchronized (receipts) {
                [receipts addObject:@(receipted)];
            }
        }
        return YES;
    } peerPublicKeyBlock:^NSString *(uint32_t groupNumber, uint32_t peerId) {
        return kGroupRealmTestsPeerKeyHex;
    } peerNameForPeerIdBlock:^NSString *(uint32_t groupNumber, uint32_t peerId) {
        return @"member";
    } defaultPeerNameBlock:^NSString *{
        return @"me";
    } selfPublicKeyBlock:^NSString *(uint32_t groupNumber) {
        return selfKeyHex;
    } peerIdForPublicKeyBlock:^uint32_t (uint32_t groupNumber, NSString *publicKeyHex) {
        return kGroupRealmTestsPeerId;
    } isPublicGroupBlock:^BOOL (uint32_t groupNumber) {
        return YES;
    } selfPeerIdBlock:^uint32_t (uint32_t groupNumber) {
        return 1;
    } peerIdsBlock:^NSArray<NSNumber *> *(uint32_t groupNumber) {
        return @[];
    } chatForGroupBlock:^OCTChat *(uint32_t groupNumber, BOOL createIfNeeded) {
        return chat;
    } messagesToSyncBlock:^NSArray<OCTMessageAbstract *> *(OCTChat *theChat) {
        return @[];
    } messageExistsBlock:^BOOL (OCTChat *theChat, uint32_t messageId, uint32_t peerId, NSString *senderPubkeyHex,
                                NSString *peerName, NSString *text, NSTimeInterval dateInterval) {
        return NO;
    } fileExistsBlock:^BOOL (OCTChat *theChat, NSString *msgIdHashHex) {
        return NO;
    } insertSyncedMessageBlock:^OCTMessageAbstract *(OCTChat *theChat, NSString *text, OCTToxMessageType type, uint32_t peerId,
                                                     NSString *peerName, NSString *senderPubkeyHex, uint32_t messageId,
                                                     NSTimeInterval dateInterval) {
        return [realmManager addGroupSyncedMessageWithText:text
                                                      type:type
                                                      chat:theChat
                                                    peerId:peerId
                                                  peerName:peerName
                                           senderPubkeyHex:senderPubkeyHex
                                                 messageId:(OCTToxMessageId)messageId
                                              dateInterval:dateInterval];
    } insertSyncedFileBlock:^OCTMessageAbstract *(OCTChat *theChat, NSString *fileName, NSString *filePath, uint64_t fileSize,
                                                  NSString *fileUTI, uint32_t peerId, NSString *peerName,
                                                  NSString *senderPubkeyHex, NSString *msgIdHashHex, NSTimeInterval dateInterval) {
        return [realmManager addGroupSyncedMessageWithFileName:fileName
                                                      fileSize:fileSize
                                                      filePath:filePath
                                                      fileType:OCTMessageFileTypeReady
                                                          chat:theChat
                                                        peerId:peerId
                                                      peerName:peerName
                                               senderPubkeyHex:senderPubkeyHex
                                                       fileUTI:fileUTI
                                             groupMsgIdHashHex:msgIdHashHex
                                                  dateInterval:dateInterval];
    } incomingFilesDirectoryBlock:^NSString *(uint32_t groupNumber) {
        return directory;
    } fileUTIBlock:^NSString *(NSString *fileName) {
        return nil;
    } isBlockedPeerBlock:^BOOL (uint32_t groupNumber, uint32_t peerId) {
        return NO;
    } deliveryReceiptBlock:^(OCTChat *theChat, uint32_t messageId, uint32_t peerId) {
    } fileSyncConfirmationBlock:^(OCTChat *theChat, NSString *msgIdHashHex, uint32_t syncerPeerId) {
    }];
}

- (RLMResults *)rowsOfChat:(OCTChat *)chat
{
    return [OCTMessageAbstract objectsInRealm:self.realmManager.realm where:@"chatUniqueIdentifier == %@", chat.uniqueIdentifier];
}

/** The rows of the chat whose text or file names `keyHex` as its author, in any case. */
- (RLMResults *)rowsOfChat:(OCTChat *)chat authoredBy:(NSString *)keyHex
{
    return [OCTMessageAbstract objectsInRealm:self.realmManager.realm
                                        where:@"chatUniqueIdentifier == %@ AND (messageText.groupSenderPubkey ==[c] %@ OR messageFile.groupSenderPubkey ==[c] %@)",
            chat.uniqueIdentifier, keyHex, keyHex];
}

- (NSArray<NSString *> *)filesInDirectory:(NSString *)directory
{
    return [[NSFileManager defaultManager] contentsOfDirectoryAtPath:directory error:nil] ?: @[];
}

/** The texts of both history sync queries, which must agree, in the order they are served. */
- (NSArray<NSString *> *)textsTheHistorySyncServesInChat:(OCTChat *)chat
{
    NSMutableArray<NSString *> *texts = [NSMutableArray new];

    for (OCTMessageAbstract *message in [self.realmManager groupMessagesForHistorySyncInChat:chat]) {
        [texts addObject:message.messageText.text ?: @"(file)"];
    }

    NSMutableArray<NSString *> *packetTexts = [NSMutableArray new];
    [self.realmManager groupHistorySyncPacketsForGroupNumber:kGroupRealmTestsGroupNumber
                                           packetFromMessage:^NSData *(OCTMessageAbstract *message) {
        [packetTexts addObject:message.messageText.text ?: @"(file)"];
        return [NSData dataWithBytes:"p" length:1];
    }];
    XCTAssertEqualObjects(packetTexts, texts, @"the two history sync queries disagree");

    return texts;
}

/** A history sync whose blocks answer for this client alone in the group of the chat. */
- (OCTNgcGroupHistSync *)historySyncForChat:(OCTChat *)chat
{
    return [[OCTNgcGroupHistSync alloc] initWithSendPrivatePacketBlock:^BOOL (uint32_t groupNumber, uint32_t peerId, NSData *packet, NSError **error) {
        return YES;
    } peerPublicKeyBlock:^NSString *(uint32_t groupNumber, uint32_t peerId) {
        return kGroupRealmTestsPeerKeyHex;
    } peerNameForPeerIdBlock:^NSString *(uint32_t groupNumber, uint32_t peerId) {
        return @"member";
    } defaultPeerNameBlock:^NSString *{
        return @"me";
    } selfPublicKeyBlock:^NSString *(uint32_t groupNumber) {
        return kGroupRealmTestsSelfKeyHex;
    } peerIdForPublicKeyBlock:^uint32_t (uint32_t groupNumber, NSString *publicKeyHex) {
        return 0;
    } isPublicGroupBlock:^BOOL (uint32_t groupNumber) {
        return YES;
    } selfPeerIdBlock:^uint32_t (uint32_t groupNumber) {
        return 1;
    } peerIdsBlock:^NSArray<NSNumber *> *(uint32_t groupNumber) {
        return @[];
    } chatForGroupBlock:^OCTChat *(uint32_t groupNumber, BOOL createIfNeeded) {
        return chat;
    } messagesToSyncBlock:^NSArray<OCTMessageAbstract *> *(OCTChat *theChat) {
        return @[];
    } messageExistsBlock:^BOOL (OCTChat *theChat, uint32_t messageId, uint32_t peerId, NSString *senderPubkeyHex,
                                NSString *peerName, NSString *text, NSTimeInterval dateInterval) {
        return NO;
    } fileExistsBlock:^BOOL (OCTChat *theChat, NSString *msgIdHashHex) {
        return NO;
    } insertSyncedMessageBlock:^OCTMessageAbstract *(OCTChat *theChat, NSString *text, OCTToxMessageType type, uint32_t peerId,
                                                     NSString *peerName, NSString *senderPubkeyHex, uint32_t messageId,
                                                     NSTimeInterval dateInterval) {
        return nil;
    } insertSyncedFileBlock:^OCTMessageAbstract *(OCTChat *theChat, NSString *fileName, NSString *filePath, uint64_t fileSize,
                                                  NSString *fileUTI, uint32_t peerId, NSString *peerName,
                                                  NSString *senderPubkeyHex, NSString *msgIdHashHex, NSTimeInterval dateInterval) {
        return nil;
    } incomingFilesDirectoryBlock:^NSString *(uint32_t groupNumber) {
        return @"";
    } fileUTIBlock:^NSString *(NSString *fileName) {
        return nil;
    } isBlockedPeerBlock:^BOOL (uint32_t groupNumber, uint32_t peerId) {
        return NO;
    } deliveryReceiptBlock:^(OCTChat *theChat, uint32_t messageId, uint32_t peerId) {
    } fileSyncConfirmationBlock:^(OCTChat *theChat, NSString *msgIdHashHex, uint32_t syncerPeerId) {
    }];
}

- (OCTChat *)groupChat
{
    return [self.realmManager getOrCreateGroupChatWithGroupNumber:kGroupRealmTestsGroupNumber
                                                        chatIdHex:kGroupRealmTestsChatIdHex
                                                        groupName:@"fix-ios-1"
                                                     privacyState:OCTToxGroupPrivacyStatePublic];
}

/**
 * A file transfer whose blocks use the Realm as the app's do: each one looks the group's chat up, and the
 * begin and complete blocks store the file's row. The lookup goes straight to the Realm, not through the realm
 * manager: an exception unwinding through the manager's dispatch_sync would leave its queue locked. From any
 * thread but the Realm's it throws "Realm accessed from incorrect thread", the exception that crashed the app;
 * here it is kept in "exceptions". "offMain" names every block called off the main thread, "begins" the files
 * whose BEGIN came, "completed" the paths the complete block got.
 */
- (NSDictionary *)realmBackedTransfer
{
    OCTRealmManager *realmManager = self.realmManager;
    RLMRealm *realm = realmManager.realm;
    NSString *directory = [self.root stringByAppendingPathComponent:@"Incoming"];
    [[NSFileManager defaultManager] createDirectoryAtPath:directory withIntermediateDirectories:YES attributes:nil error:nil];

    NSMutableArray<NSString *> *offMain = [NSMutableArray new];
    NSMutableArray<NSString *> *exceptions = [NSMutableArray new];
    NSMutableArray<NSString *> *begins = [NSMutableArray new];
    NSMutableArray<NSString *> *completed = [NSMutableArray new];

    void (^note)(NSMutableArray<NSString *> *, NSString *) = ^(NSMutableArray<NSString *> *list, NSString *entry) {
        @synchronized (list) {
            [list addObject:entry];
        }
    };

    OCTChat *(^chatFor)(NSString *, uint32_t) = ^OCTChat *(NSString *blockName, uint32_t groupNumber) {
        if (! [NSThread isMainThread]) {
            note(offMain, blockName);
        }

        @try {
            return [[OCTChat objectsInRealm:realm where:@"isGroup == YES AND groupNumber == %d", (int)groupNumber] firstObject];
        }
        @catch (NSException *exception) {
            note(exceptions, [NSString stringWithFormat:@"%@: %@", blockName, exception.reason]);
            return nil;
        }
    };

    OCTNgcGroupFileTransfer *transfer = [[OCTNgcGroupFileTransfer alloc] initWithSendPacketBlock:^BOOL (uint32_t groupNumber, NSData *packet, NSError **error) {
        return YES;
    } incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
        return chatFor(@"directory", groupNumber) ? directory : @"";
    } incomingBeginBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                           NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
        OCTChat *chat = chatFor(@"begin", groupNumber);

        if (chat) {
            [realmManager addGroupMessageWithFileName:fileName
                                             fileSize:fileSize
                                             filePath:filePath
                                             fileType:OCTMessageFileTypeLoading
                                                 chat:chat
                                               peerId:peerId
                                              fileUTI:nil
                                    groupMsgIdHashHex:msgIdHex
                                groupTransferProgress:0.0f];
            note(begins, fileName);
        }
    } incomingCompleteBlock:^(uint32_t groupNumber, uint32_t peerId, NSString *senderPublicKeyHex,
                              NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *msgIdHex) {
        OCTChat *chat = chatFor(@"complete", groupNumber);

        if (chat && ! [realmManager markGroupIncomingFileReadyInChat:chat
                                                            msgIdHash:msgIdHex
                                                               peerId:peerId
                                                         senderPubkey:senderPublicKeyHex
                                                             fileName:fileName
                                                             filePath:filePath
                                                             fileSize:fileSize]) {
            [realmManager addGroupMessageWithFileName:fileName
                                             fileSize:fileSize
                                             filePath:filePath
                                             fileType:OCTMessageFileTypeReady
                                                 chat:chat
                                               peerId:peerId
                                              fileUTI:nil
                                    groupMsgIdHashHex:msgIdHex
                                groupTransferProgress:1.0f];
        }

        note(completed, filePath);
    } transferProgressBlock:^(uint32_t groupNumber, NSString *msgIdHex, float progress) {
        if (! [NSThread isMainThread]) {
            note(offMain, @"progress");
        }
    }];

    return @{
        @"transfer": transfer,
        @"offMain": offMain,
        @"exceptions": exceptions,
        @"begins": begins,
        @"completed": completed,
    };
}

- (void)assertTransferRig:(NSDictionary *)rig
    completesOneFileWithPayload:(NSData *)payload
                          msgId:(NSData *)msgId
                         inChat:(OCTChat *)chat
{
    NSMutableArray<NSString *> *completed = rig[@"completed"];
    NSMutableArray<NSString *> *exceptions = rig[@"exceptions"];

    [self waitUntil:^BOOL {
        @synchronized (completed) {
            @synchronized (exceptions) {
                return completed.count > 0 || exceptions.count > 0;
            }
        }
    }];

    @synchronized (exceptions) {
        XCTAssertEqualObjects(exceptions, @[], @"the Realm was used off its thread");
    }
    @synchronized (rig[@"offMain"]) {
        XCTAssertEqualObjects(rig[@"offMain"], @[], @"a block was called off the main thread");
    }
    @synchronized (completed) {
        XCTAssertEqual(completed.count, 1u, @"the file was not completed once");
        XCTAssertEqualObjects([NSData dataWithContentsOfFile:completed.firstObject], payload);
    }

    OCTMessageAbstract *row = [self fileRowWithMsgId:msgId inChat:chat];
    XCTAssertEqual(row.messageFile.fileType, OCTMessageFileTypeReady, @"the file has no ready row");
    XCTAssertEqual(row.messageFile.fileSize, (OCTToxFileSize)payload.length);
}

- (OCTMessageAbstract *)fileRowWithMsgId:(NSData *)msgId inChat:(OCTChat *)chat
{
    NSString *msgIdHex = [OCTTox binToHexString:(uint8_t *)msgId.bytes length:msgId.length];

    // Straight from the Realm: the realm manager's lookup by this hash asks the default Realm.
    return [[OCTMessageAbstract objectsInRealm:self.realmManager.realm
                                         where:@"chatUniqueIdentifier == %@ AND messageFile.groupMsgIdHashHex ==[c] %@",
             chat.uniqueIdentifier, msgIdHex] firstObject];
}

- (void)waitUntil:(BOOL (^)(void))condition
{
    // The blocks the transfer hands to the main queue run while the main run loop turns.
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:5.0];

    while (! condition() && [deadline timeIntervalSinceNow] > 0) {
        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
    }
}

#pragma mark - Packets

- (NSData *)generatedBytesOfLength:(NSUInteger)length
{
    NSMutableData *data = [NSMutableData dataWithLength:length];
    arc4random_buf(data.mutableBytes, length);
    return data;
}

- (NSMutableData *)packetOfType:(uint8_t)type msgId:(NSData *)msgId
{
    const uint8_t head[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, type};
    NSMutableData *packet = [NSMutableData dataWithBytes:head length:sizeof(head)];
    [packet appendData:msgId];
    return packet;
}

- (void)appendFileName:(NSString *)fileName toPacket:(NSMutableData *)packet
{
    const uint8_t createTimestamp[4] = {0, 0, 0, 0};
    [packet appendBytes:createTimestamp length:sizeof(createTimestamp)];

    NSMutableData *name = [[fileName dataUsingEncoding:NSUTF8StringEncoding] mutableCopy];
    name.length = 255;
    [packet appendData:name];
}

- (void)appendU32:(uint32_t)value toPacket:(NSMutableData *)packet
{
    uint32_t bigEndian = CFSwapInt32HostToBig(value);
    [packet appendBytes:&bigEndian length:sizeof(bigEndian)];
}

/** A text of the group's history as a member serves it (OCTNgcGroupHistSync, layer 0x01, type 0x02). */
- (NSData *)historyTextRecordWithMessageId:(uint32_t)messageId
                              authorKeyHex:(NSString *)authorKeyHex
                                      time:(uint32_t)time
                                      text:(NSString *)text
{
    NSMutableData *messageIdBytes = [NSMutableData new];
    [self appendU32:messageId toPacket:messageIdBytes];
    NSMutableData *packet = [self packetOfType:0x02 msgId:messageIdBytes];
    [self appendKeyHex:authorKeyHex toPacket:packet];
    [self appendU32:time toPacket:packet];
    [self appendString:@"member" length:25 toPacket:packet];
    [packet appendData:[text dataUsingEncoding:NSUTF8StringEncoding]];
    return packet;
}

/** A file of the group's history as a member serves it (OCTNgcGroupHistSync, layer 0x01, type 0x03). */
- (NSData *)historyFileRecordWithMsgIdHash:(NSData *)msgIdHash
                              authorKeyHex:(NSString *)authorKeyHex
                                      time:(uint32_t)time
                                  fileName:(NSString *)fileName
                                   payload:(NSData *)payload
{
    NSMutableData *packet = [self packetOfType:0x03 msgId:msgIdHash];
    [self appendKeyHex:authorKeyHex toPacket:packet];
    [self appendU32:time toPacket:packet];
    [self appendString:@"member" length:25 toPacket:packet];
    [self appendString:fileName length:255 toPacket:packet];
    [packet appendData:payload];
    return packet;
}

- (void)appendKeyHex:(NSString *)keyHex toPacket:(NSMutableData *)packet
{
    for (NSUInteger index = 0; index + 1 < keyHex.length; index += 2) {
        uint8_t byte = (uint8_t)strtoul([keyHex substringWithRange:NSMakeRange(index, 2)].UTF8String, NULL, 16);
        [packet appendBytes:&byte length:1];
    }
}

- (void)appendString:(NSString *)string length:(NSUInteger)length toPacket:(NSMutableData *)packet
{
    NSMutableData *bytes = [[string dataUsingEncoding:NSUTF8StringEncoding] mutableCopy];
    bytes.length = length;
    [packet appendData:bytes];
}

- (NSData *)singlePacketWithMsgId:(NSData *)msgId fileName:(NSString *)fileName payload:(NSData *)payload
{
    NSMutableData *packet = [self packetOfType:0x11 msgId:msgId];
    [self appendFileName:fileName toPacket:packet];
    [packet appendData:payload];
    return packet;
}

- (NSData *)beginPacketWithMsgId:(NSData *)msgId fileName:(NSString *)fileName size:(uint64_t)size chunkPayload:(uint32_t)chunkPayload
{
    NSMutableData *packet = [self packetOfType:0x12 msgId:msgId];
    [self appendFileName:fileName toPacket:packet];

    uint64_t bigEndianSize = CFSwapInt64HostToBig(size);
    [packet appendBytes:&bigEndianSize length:sizeof(bigEndianSize)];
    [self appendU32:chunkPayload toPacket:packet];
    [self appendU32:(uint32_t)((size + chunkPayload - 1) / chunkPayload) toPacket:packet];
    return packet;
}

- (NSData *)chunkPacketWithMsgId:(NSData *)msgId index:(uint32_t)index payload:(NSData *)payload
{
    NSMutableData *packet = [self packetOfType:0x13 msgId:msgId];
    [self appendU32:index toPacket:packet];
    [self appendU32:(uint32_t)payload.length toPacket:packet];
    [packet appendData:payload];
    return packet;
}

@end
