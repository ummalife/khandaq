// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTFilePathOutput.h"
#import "OCTLogging.h"
#import "OCTFileTools.h"

static NSString *const kPartialFileExtension = @"part";
// KHANDAQ (0.2.57): a partial file is hidden and named for its message, ".<message id>.part", never for the result:
// "<name>.part" could be the name of a file of another message, received or sent, in the same folder.
static NSString *const kPartialFilePrefix = @".";

@interface OCTFilePathOutput ()

@property (copy, nonatomic, readwrite, nonnull) NSString *resultFilePath;
@property (copy, nonatomic, readonly, nonnull) NSString *tempFilePath;
/// The partial file outlives a cancel (initWithResultFilePath:ownerIdentifier:).
@property (assign, nonatomic, readonly) BOOL keepsPartialData;

// Atomic: a cancel on the main thread lets go of it while a chunk is written on the transfer queue.
@property (strong, atomic) NSFileHandle *handle;

@end

@implementation OCTFilePathOutput

#pragma mark -  Class methods

+ (nonnull NSString *)partialFilePathForResultFilePath:(nonnull NSString *)resultFilePath
                                       ownerIdentifier:(nonnull NSString *)ownerIdentifier
{
    NSString *name = [[kPartialFilePrefix stringByAppendingString:ownerIdentifier]
                      stringByAppendingPathExtension:kPartialFileExtension];
    return [[resultFilePath stringByDeletingLastPathComponent] stringByAppendingPathComponent:name];
}

+ (BOOL)isPlaceholderAtPath:(nonnull NSString *)path
{
    NSDictionary<NSFileAttributeKey, id> *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil];
    return [attributes[NSFileType] isEqualToString:NSFileTypeRegular] && [attributes fileSize] == 0;
}

+ (nullable NSString *)ownerIdentifierOfPartialFileName:(nonnull NSString *)fileName
{
    if (! [fileName hasPrefix:kPartialFilePrefix] || ! [[fileName pathExtension] isEqualToString:kPartialFileExtension]) {
        return nil;
    }

    NSString *owner = [[fileName stringByDeletingPathExtension] substringFromIndex:kPartialFilePrefix.length];
    return (owner.length > 0) ? owner : nil;
}

#pragma mark -  Lifecycle

- (nullable instancetype)initWithTempFolder:(nonnull NSString *)tempFolder
                               resultFolder:(nonnull NSString *)resultFolder
                                   fileName:(nonnull NSString *)fileName
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _tempFilePath = [OCTFileTools createNewFilePathInDirectory:tempFolder fileName:fileName];
    _resultFilePath = [OCTFileTools createNewFilePathInDirectory:resultFolder fileName:fileName];

    // Create dummy file to reserve fileName.
    [[NSFileManager defaultManager] createFileAtPath:_resultFilePath contents:[NSData data] attributes:nil];

    OCTLogInfo(@"temp path %@", _tempFilePath);
    OCTLogInfo(@"result path %@", _resultFilePath);

    return self;
}

- (nullable instancetype)initWithResultFilePath:(nonnull NSString *)resultFilePath
                                ownerIdentifier:(nonnull NSString *)ownerIdentifier
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _resultFilePath = [resultFilePath copy];
    _tempFilePath = [[self class] partialFilePathForResultFilePath:resultFilePath ownerIdentifier:ownerIdentifier];
    _keepsPartialData = YES;

    // KHANDAQ (0.2.57): nothing goes under the real name before the last byte is in. An empty file reserved it,
    // and stayed there after a cancel. The message holds the name meanwhile (its path, set before this), and
    // finishWriting takes another one if a file came to have it.

    OCTLogInfo(@"partial path %@", _tempFilePath);
    OCTLogInfo(@"result path %@", _resultFilePath);

    return self;
}

#pragma mark -  OCTFileOutputProtocol

- (BOOL)prepareToWrite
{
    if (self.keepsPartialData) {
        return [self prepareToWriteFromOffset:0];
    }

    // KHANDAQ (#48): the temp directory (NSTemporaryDirectory) is volatile and may have been purged
    // since this path was reserved — its absence makes createFileAtPath fail and surfaces to the user
    // as a generic "internal error" on (re)download. Recreate the parent directory first.
    NSString *tempDir = [self.tempFilePath stringByDeletingLastPathComponent];
    if (tempDir.length > 0) {
        [[NSFileManager defaultManager] createDirectoryAtPath:tempDir
                                  withIntermediateDirectories:YES
                                                   attributes:nil
                                                        error:nil];
    }

    if (! [[NSFileManager defaultManager] createFileAtPath:self.tempFilePath contents:nil attributes:nil]) {
        OCTLogWarn(@"OCTFilePathOutput cannot create temp file at %@", self.tempFilePath);
        return NO;
    }

    self.handle = [NSFileHandle fileHandleForWritingAtPath:self.tempFilePath];

    if (! self.handle) {
        OCTLogWarn(@"OCTFilePathOutput cannot open handle for temp file at %@", self.tempFilePath);
        return NO;
    }

    return YES;
}

- (BOOL)prepareToWriteFromOffset:(OCTToxFileSize)offset
{
    NSFileManager *fileManager = [NSFileManager defaultManager];
    [fileManager createDirectoryAtPath:[self.tempFilePath stringByDeletingLastPathComponent]
           withIntermediateDirectories:YES
                            attributes:nil
                                 error:nil];

    if (! [fileManager fileExistsAtPath:self.tempFilePath] &&
        ! [fileManager createFileAtPath:self.tempFilePath contents:nil attributes:nil]) {
        OCTLogWarn(@"OCTFilePathOutput cannot create partial file at %@", self.tempFilePath);
        return NO;
    }

    // Truncating to a point past the end would pad the file with zeros, bytes the friend never sent.
    OCTToxFileSize length = [[fileManager attributesOfItemAtPath:self.tempFilePath error:nil] fileSize];
    if (length < offset) {
        OCTLogWarn(@"OCTFilePathOutput holds %lld bytes, cannot go on from %lld", length, offset);
        return NO;
    }

    NSFileHandle *handle = [NSFileHandle fileHandleForUpdatingAtPath:self.tempFilePath];
    if (! handle) {
        OCTLogWarn(@"OCTFilePathOutput cannot open handle for partial file at %@", self.tempFilePath);
        return NO;
    }

    @try {
        // Drops what lies past `offset` and leaves the file pointer there.
        [handle truncateFileAtOffset:offset];
    }
    @catch (NSException *ex) {
        OCTLogWarn(@"catched exception %@", ex);
        return NO;
    }

    self.handle = handle;
    return YES;
}

- (BOOL)writeData:(nonnull NSData *)data
{
    @try {
        [self.handle writeData:data];
        return YES;
    }
    @catch (NSException *ex) {
        OCTLogWarn(@"catched exception %@", ex);
    }

    return NO;
}

- (BOOL)finishWriting
{
    @try {
        [self.handle synchronizeFile];
    }
    @catch (NSException *ex) {
        OCTLogWarn(@"catched exception %@", ex);
        return NO;
    }

    NSFileManager *fileManager = [NSFileManager defaultManager];

    // KHANDAQ (#107a): make sure the RESULT directory exists too. Like the temp dir (see #48 above), the
    // downloads directory can be absent on (re)download — which made the placeholder creation in init
    // and/or the move below fail, surfacing to the user as "download failed" with the real error
    // swallowed (errors were passed as nil here).
    NSString *resultDir = [self.resultFilePath stringByDeletingLastPathComponent];
    if (resultDir.length > 0) {
        [fileManager createDirectoryAtPath:resultDir withIntermediateDirectories:YES attributes:nil error:nil];
    }

    // KHANDAQ (0.2.57): an empty file there is the placeholder an earlier version reserved the name with, and
    // goes. Any other file is someone's: it stays, and this one takes a name of its own.
    if ([fileManager fileExistsAtPath:self.resultFilePath]) {
        if ([[self class] isPlaceholderAtPath:self.resultFilePath]) {
            NSError *removeError = nil;
            if (! [fileManager removeItemAtPath:self.resultFilePath error:&removeError]) {
                OCTLogWarn(@"OCTFilePathOutput cannot remove placeholder at %@: %@", self.resultFilePath, removeError);
                return NO;
            }
        }
        else {
            NSString *taken = self.resultFilePath;
            self.resultFilePath = [OCTFileTools createNewFilePathInDirectory:[taken stringByDeletingLastPathComponent]
                                                                    fileName:[taken lastPathComponent]];
            OCTLogInfo(@"OCTFilePathOutput: %@ is another file now, the result goes to %@", taken, self.resultFilePath);
        }
    }

    NSError *moveError = nil;
    if (! [fileManager moveItemAtPath:self.tempFilePath toPath:self.resultFilePath error:&moveError]) {
        OCTLogWarn(@"OCTFilePathOutput cannot move %@ -> %@: %@", self.tempFilePath, self.resultFilePath, moveError);
        return NO;
    }

    return YES;
}

- (void)cancel
{
    NSFileHandle *handle = self.handle;
    self.handle = nil;
    @try {
        [handle closeFile];
    }
    @catch (NSException *ex) {
        OCTLogWarn(@"catched exception %@", ex);
    }

    if (self.keepsPartialData) {
        return;
    }

    [[NSFileManager defaultManager] removeItemAtPath:self.tempFilePath error:nil];
}

@end
