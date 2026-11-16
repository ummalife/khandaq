// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>

#import "OCTFileStorageProtocol.h"
#import "OCTRealmManager.h"
#import "OCTSubmanagerDataSource.h"
#import "OCTSubmanagerFilesImpl.h"
#import "OCTTox.h"

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
 * The files submanager as the app starts it, against a real Realm and the files on disk: configure sweeps the files
 * no message holds. The harness of the suite of the same name in 0.2.57, cut down to what the sweep needs; toxcore
 * takes no part.
 */
@interface OCTFileResumeTests : XCTestCase

@property (copy, nonatomic) NSString *root;
@property (strong, nonatomic) OCTRealmManager *realmManager;
@property (strong, nonatomic) OCTResumeTestsDataSource *dataSource;
@property (strong, nonatomic) OCTSubmanagerFilesImpl *files;

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

        self.dataSource = [OCTResumeTestsDataSource new];
        self.dataSource.realmManager = self.realmManager;
        self.dataSource.notificationCenter = [NSNotificationCenter new];
        self.dataSource.fileStorage = [OCTResumeTestsFileStorage new];
        self.dataSource.fileStorage.root = self.root;
    }

    [self startFiles];
}

- (void)tearDown
{
    [self stopFiles];
    self.dataSource = nil;
    self.realmManager = nil;
    [[NSFileManager defaultManager] removeItemAtPath:self.root error:nil];

    [super tearDown];
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

/// The app quits and starts again: the submanager configures anew.
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

@end
