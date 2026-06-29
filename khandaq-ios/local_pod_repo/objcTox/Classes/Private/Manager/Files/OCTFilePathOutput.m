// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTFilePathOutput.h"
#import "OCTLogging.h"
#import "OCTFileTools.h"

@interface OCTFilePathOutput ()

@property (copy, nonatomic, readonly, nonnull) NSString *tempFilePath;

@property (strong, nonatomic) NSFileHandle *handle;

@end

@implementation OCTFilePathOutput

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

#pragma mark -  OCTFileOutputProtocol

- (BOOL)prepareToWrite
{
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

    // Remove the dummy placeholder ONLY if it's still there — when the result directory was missing it
    // may never have been created, and a missing placeholder must NOT abort an otherwise-fine download.
    if ([fileManager fileExistsAtPath:self.resultFilePath]) {
        NSError *removeError = nil;
        if (! [fileManager removeItemAtPath:self.resultFilePath error:&removeError]) {
            OCTLogWarn(@"OCTFilePathOutput cannot remove placeholder at %@: %@", self.resultFilePath, removeError);
            return NO;
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
    self.handle = nil;

    [[NSFileManager defaultManager] removeItemAtPath:self.tempFilePath error:nil];
}

@end
