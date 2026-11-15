// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>
#import <OCMock/OCMock.h>
#import <Realm/Realm.h>

#import "OCTRealmManager.h"
#import "OCTChat.h"
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

#pragma mark - Rig

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
