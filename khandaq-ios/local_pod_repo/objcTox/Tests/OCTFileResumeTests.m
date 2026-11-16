// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>
#import <Realm/Realm.h>
#import <Realm/RLMRealm_Dynamic.h>

#import "OCTChat.h"
#import "OCTFilePathOutput.h"
#import "OCTFileStorageProtocol.h"
#import "OCTFriend.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageFile.h"
#import "OCTMessageText.h"
#import "OCTRealmManager.h"
#import "OCTSubmanagerDataSource.h"
#import "OCTSubmanagerFilesImpl.h"
#import "OCTTox.h"

// Realm's own private API, as its private headers declare it (Pods/Headers/Private/Realm, which this target does
// not see): enough to write a profile with the schema that version 43 wrote.
@interface RLMSchema (FileResumeTests)

+ (instancetype)sharedSchema;
- (void)setObjectSchema:(NSArray<RLMObjectSchema *> *)objectSchema;

@end

@interface RLMObjectSchema (FileResumeTests)

- (void)setProperties:(NSArray<RLMProperty *> *)properties;

@end

@interface RLMRealmConfiguration (FileResumeTests)

- (void)setCustomSchema:(RLMSchema *)customSchema;

@end

static const OCTToxFriendNumber kResumeTestsFriendNumber = 5;
// Numbers as toxcore gives them: ours from 0, theirs from 1 << 16.
static const OCTToxFileNumber kResumeTestsTheirFile = 1 << 16;
static const OCTToxFileNumber kResumeTestsTheirNextFile = 2 << 16;
static const OCTToxFileSize kResumeTestsFileSize = 10;

/// 32 bytes first, first + 1, and on: a Tox file id, or a public key.
static NSData *OCTResumeTestsId(uint8_t first)
{
    NSMutableData *data = [NSMutableData new];
    for (NSUInteger i = 0; i < 32; i++) {
        uint8_t byte = (uint8_t)(first + i);
        [data appendBytes:&byte length:1];
    }
    return data;
}

static NSString *OCTResumeTestsHex(NSData *data)
{
    const uint8_t *bytes = data.bytes;
    NSMutableString *hex = [NSMutableString new];
    for (NSUInteger i = 0; i < data.length; i++) {
        [hex appendFormat:@"%02X", bytes[i]];
    }
    return hex;
}

/// The bytes of the test file: 0x30, 0x31, ... ("0123456789").
static NSData *OCTResumeTestsContent(void)
{
    return [@"0123456789" dataUsingEncoding:NSASCIIStringEncoding];
}

/**
 * toxcore as the files submanager meets it, for one friend: what it is asked is written down, offers get numbers
 * and ids as toxcore gives them, and the friend is online or not as the test says.
 */
@interface OCTResumeTestsTox : OCTTox

@property (copy, atomic) NSString *friendKey;
@property (assign, atomic) OCTToxConnectionStatus friendStatus;
/// Seeks, or accepts, fail as they do once the transfer is gone.
@property (assign, atomic) BOOL seekFails;
@property (assign, atomic) BOOL resumeFails;
/// So many seeks, and accepts, are refused first, as a full send queue to the friend refuses them.
@property (assign, atomic) NSUInteger refusedSeeks;
@property (assign, atomic) NSUInteger refusedResumes;
/// Its send queue to the friend is full, as when the friend stops taking packets: every chunk is refused.
@property (assign, atomic) BOOL sendQueueFull;
@property (assign, atomic) NSUInteger refusedChunks;

- (void)setFileId:(NSData *)fileId forFileNumber:(OCTToxFileNumber)fileNumber;
- (NSArray<NSString *> *)events;
/// The id passed with each offer of a data file, NSNull for none.
- (NSArray *)offeredIds;
- (NSArray<NSData *> *)sentChunks;

@end

@implementation OCTResumeTestsTox
{
    NSMutableDictionary<NSNumber *, NSData *> *_fileIds;
    NSMutableArray<NSString *> *_events;
    NSMutableArray *_offeredIds;
    NSMutableArray<NSData *> *_sentChunks;
    OCTToxFileNumber _nextFileNumber;
}

- (instancetype)init
{
    self = [super init];
    if (! self) {
        return nil;
    }

    _fileIds = [NSMutableDictionary new];
    _events = [NSMutableArray new];
    _offeredIds = [NSMutableArray new];
    _sentChunks = [NSMutableArray new];
    return self;
}

- (void)setFileId:(NSData *)fileId forFileNumber:(OCTToxFileNumber)fileNumber
{
    @synchronized(self) {
        _fileIds[@(fileNumber)] = fileId;
    }
}

- (NSArray<NSString *> *)events
{
    @synchronized(self) {
        return [_events copy];
    }
}

- (NSArray *)offeredIds
{
    @synchronized(self) {
        return [_offeredIds copy];
    }
}

- (NSArray<NSData *> *)sentChunks
{
    @synchronized(self) {
        return [_sentChunks copy];
    }
}

- (void)record:(NSString *)event
{
    @synchronized(self) {
        [_events addObject:event];
    }
}

- (NSString *)publicKeyFromFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    return (friendNumber == kResumeTestsFriendNumber) ? self.friendKey : nil;
}

- (OCTToxConnectionStatus)friendConnectionStatusWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    return self.friendStatus;
}

- (OCTToxFileNumber)fileSendWithFriendNumber:(OCTToxFriendNumber)friendNumber
                                        kind:(OCTToxFileKind)kind
                                    fileSize:(OCTToxFileSize)fileSize
                                      fileId:(NSData *)fileId
                                    fileName:(NSString *)fileName
                                       error:(NSError **)error
{
    // The avatar that goes to every friend who connects is not what these tests look at.
    if (kind != OCTToxFileKindData) {
        return kOCTToxFileNumberFailure;
    }

    @synchronized(self) {
        [_offeredIds addObject:fileId ?: (id)[NSNull null]];
    }

    if (self.friendStatus == OCTToxConnectionStatusNone) {
        if (error) {
            *error = [NSError errorWithDomain:kOCTToxErrorDomain code:OCTToxErrorFileSendFriendNotConnected userInfo:nil];
        }
        return kOCTToxFileNumberFailure;
    }

    @synchronized(self) {
        OCTToxFileNumber number = _nextFileNumber++;
        // toxcore makes 32 random bytes when no id is given.
        _fileIds[@(number)] = (fileId.length == kOCTToxFileIdLength) ? fileId : OCTResumeTestsId((uint8_t)arc4random());
        [_events addObject:[NSString stringWithFormat:@"offer %u", number]];
        return number;
    }
}

- (NSData *)fileGetFileIdForFileNumber:(OCTToxFileNumber)fileNumber
                          friendNumber:(OCTToxFriendNumber)friendNumber
                                 error:(NSError **)error
{
    @synchronized(self) {
        return _fileIds[@(fileNumber)];
    }
}

- (BOOL)fileSeekForFileNumber:(OCTToxFileNumber)fileNumber
                 friendNumber:(OCTToxFriendNumber)friendNumber
                     position:(OCTToxFileSize)position
                        error:(NSError **)error
{
    [self record:[NSString stringWithFormat:@"seek %u %lld", fileNumber, position]];

    OCTToxErrorFileSeek code = OCTToxErrorFileSeekNotFound;
    @synchronized(self) {
        if (self.refusedSeeks > 0) {
            self.refusedSeeks -= 1;
            code = OCTToxErrorFileSeekSendq;
        }
        else if (! self.seekFails) {
            return YES;
        }
    }
    if (error) {
        *error = [NSError errorWithDomain:kOCTToxErrorDomain code:code userInfo:nil];
    }
    return NO;
}

- (BOOL)fileSendControlForFileNumber:(OCTToxFileNumber)fileNumber
                        friendNumber:(OCTToxFriendNumber)friendNumber
                             control:(OCTToxFileControl)control
                               error:(NSError **)error
{
    NSString *name = (control == OCTToxFileControlResume) ? @"resume" : (control == OCTToxFileControlPause) ? @"pause" : @"cancel";
    [self record:[NSString stringWithFormat:@"%@ %u", name, fileNumber]];

    if (control != OCTToxFileControlResume) {
        return YES;
    }

    OCTToxErrorFileControl code = OCTToxErrorFileControlNotFound;
    @synchronized(self) {
        if (self.refusedResumes > 0) {
            self.refusedResumes -= 1;
            code = OCTToxErrorFileControlSendq;
        }
        else if (! self.resumeFails) {
            return YES;
        }
    }
    if (error) {
        *error = [NSError errorWithDomain:kOCTToxErrorDomain code:code userInfo:nil];
    }
    return NO;
}

- (BOOL)fileSendChunkForFileNumber:(OCTToxFileNumber)fileNumber
                      friendNumber:(OCTToxFriendNumber)friendNumber
                          position:(OCTToxFileSize)position
                              data:(NSData *)data
                             error:(NSError **)error
{
    if (self.sendQueueFull) {
        @synchronized(self) {
            self.refusedChunks += 1;
        }
        if (error) {
            *error = [NSError errorWithDomain:kOCTToxErrorDomain code:OCTToxErrorFileSendChunkSendq userInfo:nil];
        }
        return NO;
    }

    @synchronized(self) {
        [_events addObject:[NSString stringWithFormat:@"chunk %u %lld %lu", fileNumber, position, (unsigned long)data.length]];
        [_sentChunks addObject:[data copy]];
    }
    return YES;
}

@end

@interface OCTResumeTestsFileStorage : NSObject <OCTFileStorageProtocol>

@property (copy, nonatomic) NSString *root;

@end

@implementation OCTResumeTestsFileStorage

- (NSString *)pathForToxSaveFile
{
    return [self.root stringByAppendingPathComponent:@"save.tox"];
}

- (NSString *)pathForDatabase
{
    return [self.root stringByAppendingPathComponent:@"database"];
}

- (NSString *)pathForDatabaseEncryptionKey
{
    return [self.root stringByAppendingPathComponent:@"database.encryptionkey"];
}

- (NSString *)pathForDownloadedFilesDirectory
{
    return [self.root stringByAppendingPathComponent:@"files"];
}

- (NSString *)pathForUploadedFilesDirectory
{
    return [self.root stringByAppendingPathComponent:@"files"];
}

- (NSString *)pathForTemporaryFilesDirectory
{
    return [self.root stringByAppendingPathComponent:@"tmp"];
}

@end

/// What OCTManagerImpl gives its submanagers, as the app configures it (faux offline messaging on).
@interface OCTResumeTestsDataSource : NSObject <OCTSubmanagerDataSource>

@property (strong, nonatomic) OCTTox *tox;
@property (strong, nonatomic) OCTRealmManager *realmManager;
@property (strong, nonatomic) NSNotificationCenter *notificationCenter;
@property (strong, nonatomic) OCTResumeTestsFileStorage *fileStorage;

@end

@implementation OCTResumeTestsDataSource

- (OCTTox *)managerGetTox
{
    return self.tox;
}

- (BOOL)managerIsToxConnected
{
    return YES;
}

- (void)managerSaveTox
{}

- (OCTRealmManager *)managerGetRealmManager
{
    return self.realmManager;
}

- (id<OCTFileStorageProtocol>)managerGetFileStorage
{
    return self.fileStorage;
}

- (NSNotificationCenter *)managerGetNotificationCenter
{
    return self.notificationCenter;
}

- (BOOL)managerUseFauxOfflineMessaging
{
    return YES;
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
 * KHANDAQ (0.2.57): a 1:1 file goes on where it stopped when the connection comes back, the standard toxcore way
 * that Android and the desktop do too: the sender offers the same file id again, and the receiver seeks to the
 * bytes it has before it accepts.
 *
 * The receiver: an offer of a file it has (the friend, the id in any case, the size) goes into that message, from
 * the bytes kept; one complete here takes its last byte again and changes nothing; another id or size is a new
 * message, and the card an old client left waiting ends interrupted. A file waits for the person's tap unless they
 * had accepted it or the auto-download rule takes it; one they cancelled while nothing was live is refused when
 * offered again. The bytes outlive a disconnect and a restart, and a file the friend never offers again ends once
 * they have been online for the grace. The sender: one id for the life of the file, through a re-queue, a restart
 * and "Send again", and the bytes read where the receiver asks. Neither side shows a dropped connection as failed.
 * And a profile of schema 43 opens at 44 with all it had, even after another profile was opened in the process.
 *
 * Against a real Realm, opened on the main thread as the app opens it, and the files on disk; toxcore is a fake.
 */
@interface OCTFileResumeTests : XCTestCase

@property (copy, nonatomic) NSString *root;
@property (strong, nonatomic) OCTRealmManager *realmManager;
@property (strong, nonatomic) OCTResumeTestsTox *tox;
@property (strong, nonatomic) OCTResumeTestsDataSource *dataSource;
@property (strong, nonatomic) OCTSubmanagerFilesImpl *files;
@property (strong, nonatomic) OCTFriend *friend;
@property (strong, nonatomic) OCTChat *chat;

@end

@implementation OCTFileResumeTests

- (void)setUp
{
    [super setUp];

    // Under the home directory, where OCTMessageFile re-roots the paths it stores.
    self.root = [NSHomeDirectory() stringByAppendingPathComponent:
                 [@"tmp" stringByAppendingPathComponent:[[NSUUID UUID] UUIDString]]];
    [[NSFileManager defaultManager] createDirectoryAtPath:[self.root stringByAppendingPathComponent:@"files"]
                              withIntermediateDirectories:YES
                                               attributes:nil
                                                    error:nil];

    @autoreleasepool {
        NSURL *url = [NSURL fileURLWithPath:[self.root stringByAppendingPathComponent:@"profile.realm"]];
        self.realmManager = [[OCTRealmManager alloc] initWithDatabaseFileURL:url encryptionKey:nil];

        self.tox = [OCTResumeTestsTox new];
        self.tox.friendKey = OCTResumeTestsHex(OCTResumeTestsId(0x80));
        self.tox.friendStatus = OCTToxConnectionStatusUDP;

        self.dataSource = [OCTResumeTestsDataSource new];
        self.dataSource.tox = self.tox;
        self.dataSource.realmManager = self.realmManager;
        self.dataSource.notificationCenter = [NSNotificationCenter new];
        self.dataSource.fileStorage = [OCTResumeTestsFileStorage new];
        self.dataSource.fileStorage.root = self.root;

        self.friend = [OCTFriend new];
        self.friend.publicKey = self.tox.friendKey;
        self.friend.nickname = @"Bilal";
        self.friend.friendNumber = kResumeTestsFriendNumber;
        self.friend.isConnected = YES;
        self.friend.connectionStatus = OCTToxConnectionStatusUDP;
        [self.realmManager addObject:self.friend];
        self.chat = [self.realmManager getOrCreateChatWithFriend:self.friend];
    }

    [self startFiles];
}

- (void)tearDown
{
    [self stopFiles];
    self.chat = nil;
    self.friend = nil;
    self.dataSource = nil;
    self.tox = nil;
    self.realmManager = nil;
    [[NSFileManager defaultManager] removeItemAtPath:self.root error:nil];

    [super tearDown];
}

#pragma mark - The receiver recognises the file

- (void)testAKnownFileNotCompleteSeeksToTheBytesKeptAndMakesNoNewMessage
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:[OCTResumeTestsHex(fileId) lowercaseString]
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];

    // The id comes in capitals: it is the same file.
    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    NSArray<NSString *> *events = self.tox.events;
    XCTAssertEqualObjects(events, (@[@"seek 65536 4", @"resume 65536"]), @"the seek comes before the accept");
    XCTAssertEqual([self messageCount], 1u);
    XCTAssertEqual(message.messageFile.internalFileNumber, (int)kResumeTestsTheirFile);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeLoading);

    [self receiveTheRestOfTheirFile:kResumeTestsTheirFile from:4];

    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:message.messageFile.filePath], OCTResumeTestsContent());
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:[self partialPathOf:message]]);
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByNone);
    XCTAssertEqual(message.messageFile.transferredBytes, 0);
    XCTAssertEqual([self messageCount], 1u);
}

- (void)testAKnownFileCompleteTakesItsLastByteAgainAndChangesNothing
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeReady
                                                        pausedBy:OCTMessageFilePausedByNone
                                                       keptBytes:-1];
    int number = message.messageFile.internalFileNumber;
    NSString *storedPath = message.messageFile.internalFilePath;
    NSTimeInterval dateInterval = message.dateInterval;

    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    XCTAssertEqualObjects(self.tox.events, (@[@"seek 65536 9", @"resume 65536"]));

    // The last byte comes again and goes nowhere: the sender's side ends as done.
    [self.files tox:self.tox fileReceiveChunk:[OCTResumeTestsContent() subdataWithRange:NSMakeRange(9, 1)]
         fileNumber:kResumeTestsTheirFile
       friendNumber:kResumeTestsFriendNumber
           position:9];
    [self.files tox:self.tox fileReceiveChunk:nil fileNumber:kResumeTestsTheirFile friendNumber:kResumeTestsFriendNumber position:10];
    [self spinFor:0.3];

    XCTAssertEqual([self messageCount], 1u, @"no second card");
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeReady);
    XCTAssertEqual(message.messageFile.internalFileNumber, number, @"the card was not touched");
    XCTAssertEqualObjects(message.messageFile.internalFilePath, storedPath);
    XCTAssertEqual(message.dateInterval, dateInterval);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:message.messageFile.filePath], OCTResumeTestsContent());
    XCTAssertEqualObjects([self filesOnDisk], (@[@"doc.bin"]), @"no second copy");
    XCTAssertFalse([self.tox.events containsObject:@"cancel 65536"]);
}

- (void)testAnotherIdIsANewMessageAndTheCardAnOldClientLeftWaitingEnds
{
    OCTMessageAbstract *waiting = [self addIncomingFileWithIdHex:OCTResumeTestsHex(OCTResumeTestsId(0x10))
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];

    // A client without resume sends the file again with a new id.
    [self offerTheirFile:kResumeTestsTheirFile fileId:OCTResumeTestsId(0x20) size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertEqual([self messageCount], 2u);
    OCTMessageAbstract *fresh = [self newestIncomingMessage];
    XCTAssertNotEqualObjects(fresh.uniqueIdentifier, waiting.uniqueIdentifier);
    XCTAssertEqualObjects(fresh.messageFile.fileIdHex, OCTResumeTestsHex(OCTResumeTestsId(0x20)));
    XCTAssertEqual(fresh.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation);
    XCTAssertEqual(fresh.messageFile.internalFileNumber, (int)kResumeTestsTheirFile);

    XCTAssertEqual(waiting.messageFile.fileType, OCTMessageFileTypeCanceled, @"interrupted, not waiting for ever");
    XCTAssertEqual(waiting.messageFile.pausedBy, OCTMessageFilePausedByNone);
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:[self partialPathOf:waiting]]);
    XCTAssertEqualObjects(self.tox.events, @[], @"nothing is accepted by itself");
}

- (void)testAnotherSizeIsANewMessage
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *waiting = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];

    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize + 1 name:@"doc.bin"];

    XCTAssertEqual([self messageCount], 2u);
    XCTAssertNotEqualObjects([self newestIncomingMessage].uniqueIdentifier, waiting.uniqueIdentifier);
    XCTAssertEqual(waiting.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation);
    XCTAssertEqual(waiting.messageFile.internalFileNumber, (int)kOCTToxFileNumberFailure);
    XCTAssertTrue([[NSFileManager defaultManager] fileExistsAtPath:[self partialPathOf:waiting]]);
    XCTAssertEqualObjects(self.tox.events, @[]);
}

- (void)testNoBytesKeptGoesFromTheFirstByteIntoTheSameMessage
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:0];

    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    XCTAssertEqualObjects(self.tox.events, (@[@"resume 65536"]), @"no seek to 0");
    XCTAssertEqual([self messageCount], 1u);

    [self receiveTheRestOfTheirFile:kResumeTestsTheirFile from:0];

    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:message.messageFile.filePath], OCTResumeTestsContent());
    XCTAssertEqual([self messageCount], 1u);
}

- (void)testMoreBytesThanTheFileGoFromTheFirstByte
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];
    NSMutableData *tooLong = [NSMutableData dataWithLength:kResumeTestsFileSize + 5];
    [tooLong writeToFile:[self partialPathOf:message] atomically:NO];

    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    XCTAssertEqualObjects(self.tox.events, (@[@"resume 65536"]));

    [self receiveTheRestOfTheirFile:kResumeTestsTheirFile from:0];

    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:message.messageFile.filePath], OCTResumeTestsContent());
}

- (void)testAFileThePersonHadNotAcceptedWaitsForTheTapAndSeeksOnIt
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByNone
                                                       keptBytes:4];

    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];
    [self spinFor:0.3];

    XCTAssertEqualObjects(self.tox.events, @[], @"waits for the tap");
    XCTAssertEqual([self messageCount], 1u);
    XCTAssertEqual(message.messageFile.internalFileNumber, (int)kResumeTestsTheirFile);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation);
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByNone);

    [self.files acceptFileTransfer:message failureBlock:nil];

    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    XCTAssertEqualObjects(self.tox.events, (@[@"seek 65536 4", @"resume 65536"]));
}

- (void)testTheAutoDownloadRuleTakesAFileOfferedAgainByItself
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByNone
                                                       keptBytes:0];
    NSMutableArray<NSString *> *asked = [NSMutableArray new];
    self.files.reofferAutoAcceptPolicy = ^BOOL (OCTMessageAbstract *theMessage) {
        XCTAssertTrue([NSThread isMainThread]);
        [asked addObject:theMessage.uniqueIdentifier];
        return YES;
    };

    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    XCTAssertEqualObjects(asked, @[message.uniqueIdentifier]);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeLoading);
}

- (void)testAFileThePersonCancelledWhileAwayIsRefusedWhenOfferedAgain
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];
    [self setFriendConnected:NO notify:YES];

    XCTAssertTrue([self.files cancelFileTransfer:message error:nil]);

    XCTAssertEqualObjects(self.tox.events, @[], @"nothing live to cancel");
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeCanceled);
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:[self partialPathOf:message]]);

    [self setFriendConnected:YES notify:YES];
    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertEqualObjects(self.tox.events, (@[@"cancel 65536"]));
    XCTAssertEqual([self messageCount], 1u);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeCanceled);
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByNone, @"refused once");
}

// KHANDAQ (0.2.57): a cancel while the file came marked nothing: the cancel may never reach the friend (the
// connection goes first), they offer the file again, and the auto-download rule took it by itself.
- (void)testAFileThePersonCancelledWhileItCameIsRefusedWhenOfferedAgain
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];
    NSMutableArray<NSString *> *asked = [NSMutableArray new];
    self.files.reofferAutoAcceptPolicy = ^BOOL (OCTMessageAbstract *theMessage) {
        [asked addObject:theMessage.uniqueIdentifier];
        return YES;
    };
    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];
    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeLoading);

    XCTAssertTrue([self.files cancelFileTransfer:message error:nil]);

    XCTAssertEqualObjects(self.tox.events, (@[@"seek 65536 4", @"resume 65536", @"cancel 65536"]));
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeCanceled);
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:[self partialPathOf:message]]);

    [self offerTheirFile:kResumeTestsTheirNextFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];
    [self spinFor:0.3];

    XCTAssertEqualObjects(self.tox.events, (@[@"seek 65536 4", @"resume 65536", @"cancel 65536", @"cancel 131072"]));
    XCTAssertEqualObjects(asked, @[], @"never taken by itself");
    XCTAssertEqual([self messageCount], 1u);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeCanceled);
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByNone, @"refused once");
}

#pragma mark - The receiver keeps the bytes

- (void)testADisconnectLeavesTheirFileWaitingWithTheBytesThatCame
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:0];
    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];
    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    [self.files tox:self.tox fileReceiveChunk:[OCTResumeTestsContent() subdataWithRange:NSMakeRange(0, 6)]
         fileNumber:kResumeTestsTheirFile
       friendNumber:kResumeTestsFriendNumber
           position:0];

    [self setFriendConnected:NO notify:YES];

    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation, @"never failed");
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByConnection);
    XCTAssertEqual(message.messageFile.internalFileNumber, (int)kOCTToxFileNumberFailure);
    XCTAssertEqual(message.messageFile.transferredBytes, 6);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:[self partialPathOf:message]],
                          [OCTResumeTestsContent() subdataWithRange:NSMakeRange(0, 6)]);

    // Back online: the same id again goes on from the 6 bytes.
    [self setFriendConnected:YES notify:YES];
    [self offerTheirFile:kResumeTestsTheirNextFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];
    XCTAssertTrue([self waitForEvent:@"resume 131072"]);
    XCTAssertTrue([self.tox.events containsObject:@"seek 131072 6"]);

    [self receiveTheRestOfTheirFile:kResumeTestsTheirNextFile from:6];
    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:message.messageFile.filePath], OCTResumeTestsContent());
}

- (void)testAFailureWhileTheFriendIsAwayWaitsInsteadOfFailing
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:0];
    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];
    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    [self.files tox:self.tox fileReceiveChunk:[OCTResumeTestsContent() subdataWithRange:NSMakeRange(0, 3)]
         fileNumber:kResumeTestsTheirFile
       friendNumber:kResumeTestsFriendNumber
           position:0];

    // toxcore knows they are gone before the notification comes, and a chunk out of place fails the transfer.
    self.tox.friendStatus = OCTToxConnectionStatusNone;
    [self.files tox:self.tox fileReceiveChunk:[OCTResumeTestsContent() subdataWithRange:NSMakeRange(5, 1)]
         fileNumber:kResumeTestsTheirFile
       friendNumber:kResumeTestsFriendNumber
           position:5];

    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.internalFileNumber == (int)kOCTToxFileNumberFailure; }]);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation, @"never failed");
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByConnection);
    XCTAssertEqual(message.messageFile.transferredBytes, 3);
    XCTAssertTrue([[NSFileManager defaultManager] fileExistsAtPath:[self partialPathOf:message]]);
}

// KHANDAQ (0.2.57): a seek that failed took the file from its first byte: the bytes kept were emptied before the
// accept, which failed in turn when the friend had gone a moment after the offer.
- (void)testASeekThatFailsLeavesTheBytesKeptForTheNextOffer
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];
    NSData *kept = [OCTResumeTestsContent() subdataWithRange:NSMakeRange(0, 4)];
    self.tox.seekFails = YES;
    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertTrue([self waitForEvent:@"seek 65536 4"]);
    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.internalFileNumber == (int)kOCTToxFileNumberFailure; }]);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation, @"never failed");
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByConnection);
    XCTAssertEqual(message.messageFile.transferredBytes, 4);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:[self partialPathOf:message]], kept);
    XCTAssertEqualObjects(self.tox.events, (@[@"seek 65536 4"]), @"no accept, and nothing cancelled");

    // The next offer goes on from them.
    self.tox.seekFails = NO;
    [self offerTheirFile:kResumeTestsTheirNextFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];
    XCTAssertTrue([self waitForEvent:@"resume 131072"]);
    XCTAssertEqualObjects(self.tox.events, (@[@"seek 65536 4", @"seek 131072 4", @"resume 131072"]));
    [self receiveTheRestOfTheirFile:kResumeTestsTheirNextFile from:4];

    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:message.messageFile.filePath], OCTResumeTestsContent());
    XCTAssertEqual([self messageCount], 1u);
}

- (void)testASeekAndAnAcceptTheSendQueueRefusesAreTriedAgain
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];
    self.tox.refusedSeeks = 2;
    self.tox.refusedResumes = 1;
    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    NSArray<NSString *> *expected = @[@"seek 65536 4", @"seek 65536 4", @"seek 65536 4", @"resume 65536", @"resume 65536"];
    XCTAssertTrue([self waitUntil:^BOOL { return [self.tox.events isEqualToArray:expected]; }], @"%@", self.tox.events);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeLoading);
    [self receiveTheRestOfTheirFile:kResumeTestsTheirFile from:4];

    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:message.messageFile.filePath], OCTResumeTestsContent());
    XCTAssertEqualObjects(self.tox.events, expected, @"nothing cancelled");
}

- (void)testAnAcceptThatFailsAfterTheSeekLeavesTheBytesKeptUntilTheGraceEnds
{
    self.files.reofferGraceInterval = 0.3;
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];
    self.tox.resumeFails = YES;
    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.internalFileNumber == (int)kOCTToxFileNumberFailure; }]);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation, @"never failed");
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByConnection);
    XCTAssertEqual(message.messageFile.transferredBytes, 4);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:[self partialPathOf:message]],
                          [OCTResumeTestsContent() subdataWithRange:NSMakeRange(0, 4)]);

    // The friend is online and never offers it again.
    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeCanceled; }]);
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:[self partialPathOf:message]]);
}

- (void)testARestartKeepsTheBytesOfTheirFileAndGoesOnFromThem
{
    NSData *fileId = OCTResumeTestsId(0x10);
    // The last run had it coming in on a number this run's toxcore does not know.
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeLoading
                                                        pausedBy:OCTMessageFilePausedByNone
                                                       keptBytes:4];
    [self.realmManager updateObject:message.messageFile withBlock:^(OCTMessageFile *file) {
        file.internalFileNumber = (int)kResumeTestsTheirNextFile;
    }];
    // What an older version left in the temporary directory goes, as before.
    NSString *oldTemp = [self.root stringByAppendingPathComponent:@"tmp/me.dvor.objcTox.downloads/doc.bin"];
    [[NSFileManager defaultManager] createDirectoryAtPath:[oldTemp stringByDeletingLastPathComponent]
                              withIntermediateDirectories:YES
                                               attributes:nil
                                                    error:nil];
    [[NSData dataWithBytes:"x" length:1] writeToFile:oldTemp atomically:NO];

    [self restartFiles];

    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation, @"not cancelled at the start");
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByConnection);
    XCTAssertEqual(message.messageFile.internalFileNumber, (int)kOCTToxFileNumberFailure);
    XCTAssertEqual(message.messageFile.transferredBytes, 4);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:[self partialPathOf:message]],
                          [OCTResumeTestsContent() subdataWithRange:NSMakeRange(0, 4)], @"the cleanup kept them");
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:oldTemp]);

    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];
    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    XCTAssertEqualObjects(self.tox.events, (@[@"seek 65536 4", @"resume 65536"]));
}

// KHANDAQ (0.2.57): the files of groups live in the folder "group" (a folder of each group's incoming files, ours
// sent to a group at its top). Seen live: the sweep at the start took that folder for a file no message holds and
// removed it, with every photo, video and file of every group in it.
- (void)testARestartKeepsTheFilesOfGroups
{
    NSString *files = [self.root stringByAppendingPathComponent:@"files"];
    NSString *received = [files stringByAppendingPathComponent:@"group/3c5e9a0b7d214f6a/photo.jpg"];
    NSString *sent = [files stringByAppendingPathComponent:@"group/clip.mp4"];
    NSString *orphan = [files stringByAppendingPathComponent:@"orphan.bin"];

    for (NSString *path in @[received, sent, orphan]) {
        [[NSFileManager defaultManager] createDirectoryAtPath:[path stringByDeletingLastPathComponent]
                                  withIntermediateDirectories:YES
                                                   attributes:nil
                                                        error:nil];
        XCTAssertTrue([[NSData dataWithBytes:"x" length:1] writeToFile:path atomically:NO]);
    }

    [self restartFiles];

    XCTAssertTrue([[NSFileManager defaultManager] fileExistsAtPath:received], @"a file a group sent us went at the start");
    XCTAssertTrue([[NSFileManager defaultManager] fileExistsAtPath:sent], @"a file we sent to a group went at the start");
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:orphan], @"the sweep did not run");
}

// KHANDAQ (0.2.57): the bytes of a download used to wait in "<name>.part", a name a file of another message can
// have in the same folder (a browser names a download it has not finished so): a download into "doc.bin" emptied
// the file "doc.bin.part" of another message, or went on from it, and moved it away as its own.
- (void)testAFreshDownloadNeverEmptiesAFileOfAnotherMessageNamedLikeItsBytes
{
    NSData *foreign = [@"from a browser" dataUsingEncoding:NSASCIIStringEncoding];
    NSString *foreignPath = [self writeFileOfAnotherMessageNamed:@"doc.bin.part" content:foreign];

    [self offerTheirFile:kResumeTestsTheirFile fileId:OCTResumeTestsId(0x10) size:kResumeTestsFileSize name:@"doc.bin"];
    OCTMessageAbstract *message = [self newestIncomingMessage];
    [self.files acceptFileTransfer:message failureBlock:nil];
    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    [self receiveTheRestOfTheirFile:kResumeTestsTheirFile from:0];

    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:message.messageFile.filePath], OCTResumeTestsContent());
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:foreignPath], foreign, @"the file of another message was taken");
}

- (void)testADownloadGoesOnFromItsOwnBytesNotFromAFileOfAnotherMessageNamedLikeThem
{
    NSData *fileId = OCTResumeTestsId(0x10);
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(fileId)
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];
    NSData *foreign = [@"from a browser" dataUsingEncoding:NSASCIIStringEncoding];
    NSString *foreignPath = [self writeFileOfAnotherMessageNamed:@"doc.bin.part" content:foreign];

    [self offerTheirFile:kResumeTestsTheirFile fileId:fileId size:kResumeTestsFileSize name:@"doc.bin"];

    XCTAssertTrue([self waitForEvent:@"resume 65536"]);
    XCTAssertEqualObjects(self.tox.events, (@[@"seek 65536 4", @"resume 65536"]), @"from its own 4 bytes");
    [self receiveTheRestOfTheirFile:kResumeTestsTheirFile from:4];

    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:message.messageFile.filePath], OCTResumeTestsContent());
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:foreignPath], foreign, @"the file of another message was taken");
}

- (void)testTheCleanupAndACancelTakeOnlyTheBytesOfTheirOwnMessage
{
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(OCTResumeTestsId(0x10))
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];
    NSData *foreign = [@"from a browser" dataUsingEncoding:NSASCIIStringEncoding];
    NSString *foreignPath = [self writeFileOfAnotherMessageNamed:@"doc.bin.part" content:foreign];
    NSString *orphan = [OCTFilePathOutput partialFilePathForResultFilePath:message.messageFile.filePath
                                                           ownerIdentifier:[[NSUUID UUID] UUIDString]];
    XCTAssertTrue([[NSData dataWithBytes:"x" length:1] writeToFile:orphan atomically:NO]);

    // The cleanup at the start keeps the bytes of a message, takes those whose message is gone, and keeps a file
    // another message holds, whatever its name.
    [self restartFiles];

    XCTAssertEqualObjects([NSData dataWithContentsOfFile:[self partialPathOf:message]],
                          [OCTResumeTestsContent() subdataWithRange:NSMakeRange(0, 4)]);
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:orphan], @"the bytes of no message stayed");
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:foreignPath], foreign);

    XCTAssertTrue([self.files cancelFileTransfer:message error:nil]);

    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:[self partialPathOf:message]]);
    XCTAssertEqualObjects([NSData dataWithContentsOfFile:foreignPath], foreign, @"a cancel took the file of another message");
}

- (void)testAFileTheFriendNeverOffersAgainEndsOnceTheyHaveBeenOnlineForTheGrace
{
    [self setFriendConnected:NO notify:YES];
    OCTMessageAbstract *message = [self addIncomingFileWithIdHex:OCTResumeTestsHex(OCTResumeTestsId(0x10))
                                                            type:OCTMessageFileTypeWaitingConfirmation
                                                        pausedBy:OCTMessageFilePausedByConnection
                                                       keptBytes:4];
    self.files.reofferGraceInterval = 0.3;

    // Nothing ends while they are away.
    [self spinFor:0.5];
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation);

    [self setFriendConnected:YES notify:YES];
    [self spinFor:0.1];
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation, @"the grace has not ended");

    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeCanceled; }]);
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByNone);
    XCTAssertFalse([[NSFileManager defaultManager] fileExistsAtPath:[self partialPathOf:message]]);
}

#pragma mark - The sender

- (void)testOurFileKeepsItsIdThroughARequeueARestartAndSendAgain
{
    OCTMessageAbstract *message = [self sendOurFile];
    NSData *fileId = self.tox.offeredIds.count == 1 ? [self.tox fileGetFileIdForFileNumber:0 friendNumber:kResumeTestsFriendNumber error:nil] : nil;
    XCTAssertEqualObjects(self.tox.offeredIds, @[[NSNull null]], @"the first offer makes the id");
    XCTAssertEqualObjects(message.messageFile.fileIdHex, OCTResumeTestsHex(fileId));

    // They take it, 4 bytes go, and the connection drops.
    [self.files tox:self.tox fileReceiveControl:OCTToxFileControlResume friendNumber:kResumeTestsFriendNumber fileNumber:0];
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeLoading);
    [self.files tox:self.tox fileChunkRequestForFileNumber:0 friendNumber:kResumeTestsFriendNumber position:0 length:4];
    XCTAssertTrue([self waitForEvent:@"chunk 0 0 4"]);
    [self setFriendConnected:NO notify:YES];

    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation, @"never failed");
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByConnection);
    XCTAssertEqual(message.messageFile.transferredBytes, 4);

    // A re-queue: offered again with its id once they are back, and it still says how far it got.
    [self setFriendConnected:YES notify:YES];
    [self waitForUploadsToOpenTheirFiles];
    XCTAssertEqualObjects(self.tox.offeredIds.lastObject, fileId);
    XCTAssertEqual(message.messageFile.internalFileNumber, 1);
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByConnection);
    XCTAssertEqual(message.messageFile.transferredBytes, 4);

    // A restart: the same id again.
    [self restartFiles];
    XCTAssertEqual(message.messageFile.internalFileNumber, (int)kOCTToxFileNumberFailure);
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByConnection);
    [self setFriendConnected:YES notify:YES];
    [self waitForUploadsToOpenTheirFiles];
    XCTAssertEqual(self.tox.offeredIds.count, 3u);
    XCTAssertEqualObjects(self.tox.offeredIds.lastObject, fileId);

    // They cancel it, and the person sends it again: the same id again.
    [self.files tox:self.tox fileReceiveControl:OCTToxFileControlCancel friendNumber:kResumeTestsFriendNumber fileNumber:2];
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeCanceled);
    [self.files retrySendingFile:message failureBlock:nil];
    XCTAssertEqual(self.tox.offeredIds.count, 4u);
    XCTAssertEqualObjects(self.tox.offeredIds.lastObject, fileId);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation);
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByNone);
}

- (void)testOurFileReadsTheBytesWhereTheReceiverAsks
{
    OCTMessageAbstract *message = [self sendOurFile];

    // The receiver had 6 bytes and sought there: toxcore asks for the rest from byte 6.
    [self.files tox:self.tox fileReceiveControl:OCTToxFileControlResume friendNumber:kResumeTestsFriendNumber fileNumber:0];
    [self.files tox:self.tox fileChunkRequestForFileNumber:0 friendNumber:kResumeTestsFriendNumber position:6 length:4];
    XCTAssertTrue([self waitForEvent:@"chunk 0 6 4"]);
    XCTAssertEqualObjects(self.tox.sentChunks.lastObject, [OCTResumeTestsContent() subdataWithRange:NSMakeRange(6, 4)]);

    [self.files tox:self.tox fileChunkRequestForFileNumber:0 friendNumber:kResumeTestsFriendNumber position:10 length:0];
    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
}

- (void)testOurChunksWaitWhileTheFriendTakesNoPacketsAndThenGoInOrder
{
    OCTMessageAbstract *message = [self sendOurFile];
    [self.files tox:self.tox fileReceiveControl:OCTToxFileControlResume friendNumber:kResumeTestsFriendNumber fileNumber:0];
    [self.files tox:self.tox fileChunkRequestForFileNumber:0 friendNumber:kResumeTestsFriendNumber position:0 length:4];
    XCTAssertTrue([self waitForEvent:@"chunk 0 0 4"]);

    // Their network goes: toxcore's queue to them fills before it drops them (32 s), and toxcore asks for more.
    self.tox.sendQueueFull = YES;
    [self.files tox:self.tox fileChunkRequestForFileNumber:0 friendNumber:kResumeTestsFriendNumber position:4 length:3];
    [self.files tox:self.tox fileChunkRequestForFileNumber:0 friendNumber:kResumeTestsFriendNumber position:7 length:3];
    [self spinFor:1.5];
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeLoading, @"never failed");
    XCTAssertFalse([self.tox.events containsObject:@"cancel 0"]);
    XCTAssertEqual(self.tox.sentChunks.count, 1u);
    XCTAssertGreaterThan(self.tox.refusedChunks, 2u, @"tried again");

    // They take packets again: the chunks go in the order toxcore asked for them, and the file ends as one.
    self.tox.sendQueueFull = NO;
    XCTAssertTrue([self waitForEvent:@"chunk 0 7 3"]);
    NSPredicate *chunks = [NSPredicate predicateWithFormat:@"SELF BEGINSWITH 'chunk'"];
    XCTAssertEqualObjects([self.tox.events filteredArrayUsingPredicate:chunks], (@[@"chunk 0 0 4", @"chunk 0 4 3", @"chunk 0 7 3"]));
    XCTAssertEqualObjects(self.tox.sentChunks.lastObject, [OCTResumeTestsContent() subdataWithRange:NSMakeRange(7, 3)]);

    [self.files tox:self.tox fileChunkRequestForFileNumber:0 friendNumber:kResumeTestsFriendNumber position:10 length:0];
    XCTAssertTrue([self waitUntil:^BOOL { return message.messageFile.fileType == OCTMessageFileTypeReady; }]);
}

- (void)testOurFileWhoseFriendTakesNoPacketsWaitsForTheConnectionOnceTheyAreDropped
{
    OCTMessageAbstract *message = [self sendOurFile];
    NSData *fileId = [self.tox fileGetFileIdForFileNumber:0 friendNumber:kResumeTestsFriendNumber error:nil];
    [self.files tox:self.tox fileReceiveControl:OCTToxFileControlResume friendNumber:kResumeTestsFriendNumber fileNumber:0];
    [self.files tox:self.tox fileChunkRequestForFileNumber:0 friendNumber:kResumeTestsFriendNumber position:0 length:4];
    XCTAssertTrue([self waitForEvent:@"chunk 0 0 4"]);

    self.tox.sendQueueFull = YES;
    [self.files tox:self.tox fileChunkRequestForFileNumber:0 friendNumber:kResumeTestsFriendNumber position:4 length:3];
    [self spinFor:0.3];

    // toxcore drops them: the file waits for the connection with what went, and what waited is not sent.
    [self setFriendConnected:NO notify:YES];
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation, @"never failed");
    XCTAssertEqual(message.messageFile.pausedBy, OCTMessageFilePausedByConnection);
    XCTAssertEqual(message.messageFile.transferredBytes, 4);
    self.tox.sendQueueFull = NO;
    [self spinFor:0.3];
    XCTAssertEqual(self.tox.sentChunks.count, 1u);
    XCTAssertFalse([self.tox.events containsObject:@"cancel 0"]);

    // Back: offered again with its id, to go on from where the receiver is.
    [self setFriendConnected:YES notify:YES];
    [self waitForUploadsToOpenTheirFiles];
    XCTAssertEqualObjects(self.tox.offeredIds.lastObject, fileId);
    XCTAssertEqual(message.messageFile.internalFileNumber, 1);
}

- (void)testOurFileQueuedWhileTheyAreAwayAndSentAgainWhileAwayStaysQueued
{
    [self setFriendConnected:NO notify:YES];
    OCTMessageAbstract *message = [self sendOurFile];
    XCTAssertEqual(message.messageFile.internalFileNumber, (int)kOCTToxFileNumberFailure);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation);
    XCTAssertNil(message.messageFile.fileIdHex, @"no offer made one yet");

    // Cancelled, then "Send again" while they are still away: queued as a new one is.
    XCTAssertTrue([self.files cancelFileTransfer:message error:nil]);
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeCanceled);
    [self.files retrySendingFile:message failureBlock:nil];
    XCTAssertEqual(message.messageFile.fileType, OCTMessageFileTypeWaitingConfirmation);
    XCTAssertEqual(message.messageFile.internalFileNumber, (int)kOCTToxFileNumberFailure);

    [self setFriendConnected:YES notify:YES];
    XCTAssertEqual(message.messageFile.internalFileNumber, 0);
    XCTAssertNotNil(message.messageFile.fileIdHex);
}

#pragma mark - A profile of schema 43

- (void)testAProfileOfSchema43OpensAt44AndKeepsItsFiles
{
    NSURL *url = [NSURL fileURLWithPath:[self.root stringByAppendingPathComponent:@"schema43.realm"]];
    NSString *fileMessageId = [[NSUUID UUID] UUIDString];
    NSString *textMessageId = [[NSUUID UUID] UUIDString];
    NSString *fileIdHex = OCTResumeTestsHex(OCTResumeTestsId(0x40));
    [self writeSchema43ProfileAtURL:url fileMessageId:fileMessageId textMessageId:textMessageId fileIdHex:fileIdHex];
    XCTAssertEqual([RLMRealm schemaVersionAtURL:url encryptionKey:nil error:nil], 43u);

    // Another profile is open in this process first, as after switching profiles or logging in again.
    XCTAssertNotNil([[OCTRealmManager alloc] initWithDatabaseFileURL:[NSURL fileURLWithPath:[self.root stringByAppendingPathComponent:@"other.realm"]]
                                                       encryptionKey:nil]);

    __weak RLMRealm *migratedRealm = nil;
    @autoreleasepool {
        OCTRealmManager *manager = [[OCTRealmManager alloc] initWithDatabaseFileURL:url encryptionKey:nil];
        migratedRealm = manager.realm;
        XCTAssertEqual([RLMRealm schemaVersionAtURL:url encryptionKey:nil error:nil], 44u);

        OCTFriend *friend = [manager friendWithPublicKey:self.tox.friendKey];
        XCTAssertNotNil(friend, @"the profile was emptied instead of migrated");
        XCTAssertEqualObjects(friend.nickname, @"Bilal");

        OCTMessageAbstract *text = [manager objectWithUniqueIdentifier:textMessageId class:[OCTMessageAbstract class]];
        XCTAssertEqualObjects(text.messageText.text, @"salam");

        OCTMessageAbstract *file = [manager objectWithUniqueIdentifier:fileMessageId class:[OCTMessageAbstract class]];
        XCTAssertEqual(file.messageFile.fileType, OCTMessageFileTypeReady);
        XCTAssertEqualObjects(file.messageFile.fileName, @"doc.bin");
        XCTAssertEqual(file.messageFile.fileSize, kResumeTestsFileSize);
        XCTAssertEqualObjects(file.messageFile.fileIdHex, fileIdHex);
        XCTAssertEqualObjects(file.messageFile.internalFilePath, @"Documents/files/doc.bin");
        XCTAssertEqual(file.messageFile.transferredBytes, 0);

        [manager updateObject:file.messageFile withBlock:^(OCTMessageFile *theFile) {
            theFile.transferredBytes = 1234;
        }];
    }
    XCTAssertNil(migratedRealm, @"the profile was not closed, so this would not read it from disk");

    OCTRealmManager *reopened = [[OCTRealmManager alloc] initWithDatabaseFileURL:url encryptionKey:nil];
    OCTMessageAbstract *file = [reopened objectWithUniqueIdentifier:fileMessageId class:[OCTMessageAbstract class]];
    XCTAssertEqual(file.messageFile.transferredBytes, 1234);
}

#pragma mark - Private

- (void)startFiles
{
    self.files = [OCTSubmanagerFilesImpl new];
    self.files.dataSource = self.dataSource;
    [self.files configure];
    [self waitForTheCleanup];
}

- (void)stopFiles
{
    [self.dataSource.notificationCenter removeObserver:self.files];
    [[self.files valueForKey:@"queue"] cancelAllOperations];
    self.files = nil;
}

/// The app quits and starts again: toxcore forgets every transfer, the submanager configures anew.
- (void)restartFiles
{
    [self stopFiles];
    [self startFiles];
}

/// configure clears the files no message holds on a queue of its own; the tests look at disk after it.
- (void)waitForTheCleanup
{
    [self spinFor:0.05];
    XCTAssertTrue([self waitUntil:^BOOL {
        return ! [[self.files valueForKey:@"filesCleanupInProgress"] boolValue];
    }]);
}

- (void)setFriendConnected:(BOOL)connected notify:(BOOL)notify
{
    self.tox.friendStatus = connected ? OCTToxConnectionStatusUDP : OCTToxConnectionStatusNone;
    [self.realmManager updateObject:self.friend withBlock:^(OCTFriend *theFriend) {
        theFriend.isConnected = connected;
        theFriend.connectionStatus = connected ? OCTToxConnectionStatusUDP : OCTToxConnectionStatusNone;
    }];
    if (notify) {
        [self.dataSource.notificationCenter postNotificationName:kOCTFriendConnectionStatusChangeNotification object:self.friend];
    }
}

/**
 * A file of theirs. `keptBytes` < 0: complete, at its path. 0: accepted, nothing kept. n > 0: the first n bytes in
 * its partial file, and the placeholder at its path, as an interrupted download leaves them.
 */
- (OCTMessageAbstract *)addIncomingFileWithIdHex:(NSString *)fileIdHex
                                            type:(OCTMessageFileType)type
                                        pausedBy:(OCTMessageFilePausedBy)pausedBy
                                       keptBytes:(OCTToxFileSize)keptBytes
{
    return [self addIncomingFileNamed:@"doc.bin" fileIdHex:fileIdHex type:type pausedBy:pausedBy keptBytes:keptBytes];
}

- (OCTMessageAbstract *)addIncomingFileNamed:(NSString *)name
                                   fileIdHex:(NSString *)fileIdHex
                                        type:(OCTMessageFileType)type
                                    pausedBy:(OCTMessageFilePausedBy)pausedBy
                                   keptBytes:(OCTToxFileSize)keptBytes
{
    NSString *path = [[self.root stringByAppendingPathComponent:@"files"] stringByAppendingPathComponent:name];
    NSData *content = OCTResumeTestsContent();
    OCTMessageAbstract *message = [OCTMessageAbstract new];

    OCTMessageFile *file = [OCTMessageFile new];
    file.fileName = name;
    file.fileSize = kResumeTestsFileSize;
    file.fileType = type;
    file.pausedBy = pausedBy;
    file.fileIdHex = fileIdHex;
    file.internalFileNumber = (type == OCTMessageFileTypeReady) ? (int)kResumeTestsTheirNextFile : (int)kOCTToxFileNumberFailure;
    file.transferredBytes = (pausedBy & OCTMessageFilePausedByConnection) ? MAX(keptBytes, 0) : 0;
    [file internalSetFilePath:path];

    if (keptBytes < 0) {
        [content writeToFile:path atomically:NO];
    }
    else {
        [[NSData data] writeToFile:path atomically:NO];
        if (keptBytes > 0) {
            NSString *partial = [OCTFilePathOutput partialFilePathForResultFilePath:path ownerIdentifier:message.uniqueIdentifier];
            [[content subdataWithRange:NSMakeRange(0, (NSUInteger)keptBytes)] writeToFile:partial atomically:NO];
        }
    }

    message.chatUniqueIdentifier = self.chat.uniqueIdentifier;
    message.senderUniqueIdentifier = self.friend.uniqueIdentifier;
    message.dateInterval = 100;
    message.sortTimestamp = 100;
    message.messageFile = file;
    [self.realmManager addObject:message];

    XCTAssertEqualObjects(message.messageFile.filePath, path, @"the path must re-root where it was written");
    return message;
}

/// Ours, sent now: the 10 bytes copied into the uploads.
- (OCTMessageAbstract *)sendOurFile
{
    NSString *source = [self.root stringByAppendingPathComponent:@"doc.bin"];
    [OCTResumeTestsContent() writeToFile:source atomically:NO];
    [self.files sendFileAtPath:source moveToUploads:YES toChat:self.chat failureBlock:nil];
    [self waitForUploadsToOpenTheirFiles];

    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"senderUniqueIdentifier == nil AND messageFile != nil"];
    RLMResults *results = [self.realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    XCTAssertEqual(results.count, 1u);
    return results.firstObject;
}

/// toxcore asks for bytes only after the receiver accepted, long after the upload opened its file; here at once.
- (void)waitForUploadsToOpenTheirFiles
{
    XCTAssertTrue([self waitUntil:^BOOL {
        for (NSOperation *operation in [[self.files valueForKey:@"queue"] operations]) {
            if ([operation isKindOfClass:NSClassFromString(@"OCTFileUploadOperation")] &&
                ! [[operation valueForKey:@"input"] valueForKey:@"handle"]) {
                return NO;
            }
        }
        return YES;
    }]);
}

- (void)offerTheirFile:(OCTToxFileNumber)fileNumber fileId:(NSData *)fileId size:(OCTToxFileSize)size name:(NSString *)name
{
    [self.tox setFileId:fileId forFileNumber:fileNumber];
    [self.files tox:self.tox fileReceiveForFileNumber:fileNumber
       friendNumber:kResumeTestsFriendNumber
               kind:OCTToxFileKindData
           fileSize:size
           fileName:name];
}

- (void)receiveTheRestOfTheirFile:(OCTToxFileNumber)fileNumber from:(OCTToxFileSize)position
{
    NSData *content = OCTResumeTestsContent();
    while (position < kResumeTestsFileSize) {
        NSUInteger length = MIN((NSUInteger)3, (NSUInteger)(kResumeTestsFileSize - position));
        [self.files tox:self.tox fileReceiveChunk:[content subdataWithRange:NSMakeRange((NSUInteger)position, length)]
             fileNumber:fileNumber
           friendNumber:kResumeTestsFriendNumber
               position:position];
        position += length;
    }
    [self.files tox:self.tox fileReceiveChunk:nil fileNumber:fileNumber friendNumber:kResumeTestsFriendNumber position:position];
}

- (NSString *)partialPathOf:(OCTMessageAbstract *)message
{
    return [OCTFilePathOutput partialFilePathForResultFilePath:message.messageFile.filePath
                                               ownerIdentifier:message.uniqueIdentifier];
}

/// A file another message holds, complete, at `name` in the folder of the files.
- (NSString *)writeFileOfAnotherMessageNamed:(NSString *)name content:(NSData *)content
{
    NSString *path = [[self.root stringByAppendingPathComponent:@"files"] stringByAppendingPathComponent:name];
    XCTAssertTrue([content writeToFile:path atomically:NO]);

    OCTMessageFile *file = [OCTMessageFile new];
    file.fileName = name;
    file.fileSize = (OCTToxFileSize)content.length;
    file.fileType = OCTMessageFileTypeReady;
    file.internalFileNumber = (int)kOCTToxFileNumberFailure;
    [file internalSetFilePath:path];

    OCTMessageAbstract *message = [OCTMessageAbstract new];
    message.chatUniqueIdentifier = self.chat.uniqueIdentifier;
    message.senderUniqueIdentifier = self.friend.uniqueIdentifier;
    message.dateInterval = 50;
    message.sortTimestamp = 50;
    message.messageFile = file;
    [self.realmManager addObject:message];
    return path;
}

- (NSArray<NSString *> *)filesOnDisk
{
    NSArray *names = [[NSFileManager defaultManager] contentsOfDirectoryAtPath:[self.root stringByAppendingPathComponent:@"files"] error:nil];
    return [names sortedArrayUsingSelector:@selector(compare:)];
}

- (NSUInteger)messageCount
{
    return [self.realmManager objectsWithClass:[OCTMessageAbstract class] predicate:nil].count;
}

- (OCTMessageAbstract *)newestIncomingMessage
{
    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"senderUniqueIdentifier != nil"];
    RLMResults *results = [self.realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    return [[results sortedResultsUsingKeyPath:@"dateInterval" ascending:NO] firstObject];
}

- (BOOL)waitForEvent:(NSString *)event
{
    return [self waitUntil:^BOOL {
        return [self.tox.events containsObject:event];
    }];
}

- (void)spinFor:(NSTimeInterval)seconds
{
    [[NSRunLoop currentRunLoop] runUntilDate:[NSDate dateWithTimeIntervalSinceNow:seconds]];
}

- (BOOL)waitUntil:(BOOL (^)(void))condition
{
    NSDate *deadline = [NSDate dateWithTimeIntervalSinceNow:5];
    while (! condition() && deadline.timeIntervalSinceNow > 0) {
        [[NSRunLoop currentRunLoop] runMode:NSDefaultRunLoopMode beforeDate:[NSDate dateWithTimeIntervalSinceNow:0.02]];
    }
    return condition();
}

/**
 * A profile as 0.2.57 before this change (schema 43) wrote it: today's classes without OCTMessageFile's
 * transferredBytes, at version 43, holding the friend, a text and a file of theirs.
 */
- (void)writeSchema43ProfileAtURL:(NSURL *)url
                    fileMessageId:(NSString *)fileMessageId
                    textMessageId:(NSString *)textMessageId
                        fileIdHex:(NSString *)fileIdHex
{
    @autoreleasepool {
        NSMutableArray<RLMObjectSchema *> *objectSchemas = [NSMutableArray new];
        for (RLMObjectSchema *shared in [RLMSchema sharedSchema].objectSchema) {
            RLMObjectSchema *objectSchema = [shared copy];
            if ([objectSchema.className isEqualToString:@"OCTMessageFile"]) {
                NSMutableArray<RLMProperty *> *properties = [NSMutableArray new];
                for (RLMProperty *property in objectSchema.properties) {
                    if (! [property.name isEqualToString:@"transferredBytes"]) {
                        [properties addObject:property];
                    }
                }
                XCTAssertEqual(properties.count + 1, objectSchema.properties.count);
                [objectSchema setProperties:properties];
            }
            [objectSchemas addObject:objectSchema];
        }
        RLMSchema *schema = [RLMSchema new];
        [schema setObjectSchema:objectSchemas];

        RLMRealmConfiguration *configuration = [RLMRealmConfiguration new];
        configuration.fileURL = url;
        configuration.schemaVersion = 43;
        [configuration setCustomSchema:schema];

        NSError *error = nil;
        RLMRealm *realm = [RLMRealm realmWithConfiguration:configuration error:&error];
        XCTAssertNotNil(realm, @"%@", error);
        XCTAssertNil(realm.schema[@"OCTMessageFile"][@"transferredBytes"]);

        OCTFriend *friend = [OCTFriend new];
        friend.publicKey = self.tox.friendKey;
        friend.nickname = @"Bilal";
        friend.friendNumber = kResumeTestsFriendNumber;
        NSString *chatId = [[NSUUID UUID] UUIDString];

        OCTMessageText *text = [OCTMessageText new];
        text.text = @"salam";
        OCTMessageAbstract *textMessage = [OCTMessageAbstract new];
        textMessage.uniqueIdentifier = textMessageId;
        textMessage.chatUniqueIdentifier = chatId;
        textMessage.senderUniqueIdentifier = friend.uniqueIdentifier;
        textMessage.dateInterval = 100;
        textMessage.sortTimestamp = 100;
        textMessage.messageText = text;

        OCTMessageFile *file = [OCTMessageFile new];
        file.fileType = OCTMessageFileTypeReady;
        file.fileName = @"doc.bin";
        file.fileSize = kResumeTestsFileSize;
        file.fileIdHex = fileIdHex;
        file.internalFilePath = @"Documents/files/doc.bin";
        OCTMessageAbstract *fileMessage = [OCTMessageAbstract new];
        fileMessage.uniqueIdentifier = fileMessageId;
        fileMessage.chatUniqueIdentifier = chatId;
        fileMessage.senderUniqueIdentifier = friend.uniqueIdentifier;
        fileMessage.dateInterval = 200;
        fileMessage.sortTimestamp = 200;
        fileMessage.messageFile = file;

        [realm transactionWithBlock:^{
            [realm createObject:@"OCTFriend" withValue:friend];
            [realm createObject:@"OCTMessageAbstract" withValue:textMessage];
            [realm createObject:@"OCTMessageAbstract" withValue:fileMessage];
        }];
    }
}

@end
