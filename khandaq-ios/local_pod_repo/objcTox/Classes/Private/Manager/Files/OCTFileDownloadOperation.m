// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTFileDownloadOperation.h"
#import "OCTFileBaseOperation+Private.h"
#import "OCTFileOutputProtocol.h"
#import "OCTLogging.h"
#import "NSError+OCTFile.h"

static const CFTimeInterval kDownloadStallTimeout = 90.0;

@interface OCTFileDownloadOperation ()

@property (assign, nonatomic) CFTimeInterval lastChunkTime;
@property (strong, nonatomic) dispatch_block_t stallWatchdog;

@end

@implementation OCTFileDownloadOperation

#pragma mark -  Lifecycle

- (nullable instancetype)initWithTox:(nonnull OCTTox *)tox
                          fileOutput:(nonnull id<OCTFileOutputProtocol>)fileOutput
                        friendNumber:(OCTToxFriendNumber)friendNumber
                          fileNumber:(OCTToxFileNumber)fileNumber
                            fileSize:(OCTToxFileSize)fileSize
                            userInfo:(NSDictionary *)userInfo
                       progressBlock:(nullable OCTFileBaseOperationProgressBlock)progressBlock
                      etaUpdateBlock:(nullable OCTFileBaseOperationProgressBlock)etaUpdateBlock
                        successBlock:(nullable OCTFileBaseOperationSuccessBlock)successBlock
                        failureBlock:(nullable OCTFileBaseOperationFailureBlock)failureBlock
{
    NSParameterAssert(fileOutput);

    self = [super initWithTox:tox
                 friendNumber:friendNumber
                   fileNumber:fileNumber
                     fileSize:fileSize
                     userInfo:userInfo
                progressBlock:progressBlock
               etaUpdateBlock:etaUpdateBlock
                 successBlock:successBlock
                 failureBlock:failureBlock];

    if (! self) {
        return nil;
    }

    _output = fileOutput;

    return self;
}

#pragma mark -  Public

- (void)resetStallWatchdog
{
    if (self.stallWatchdog) {
        dispatch_block_cancel(self.stallWatchdog);
        self.stallWatchdog = nil;
    }

    __weak OCTFileDownloadOperation *weakSelf = self;
    dispatch_block_t watchdog = dispatch_block_create(0, ^{
        OCTFileDownloadOperation *strongSelf = weakSelf;

        if (! strongSelf || strongSelf.isFinished) {
            return;
        }

        CFTimeInterval idle = CACurrentMediaTime() - strongSelf.lastChunkTime;

        if (idle < kDownloadStallTimeout || strongSelf.bytesDone >= strongSelf.fileSize) {
            return;
        }

        OCTLogWarn(@"download stalled at %lld/%lld bytes", strongSelf.bytesDone, strongSelf.fileSize);
        [strongSelf.tox fileSendControlForFileNumber:strongSelf.fileNumber
                                        friendNumber:strongSelf.friendNumber
                                             control:OCTToxFileControlCancel
                                               error:nil];
        [strongSelf finishWithError:[NSError acceptFileErrorInternalError]];
    });

    self.stallWatchdog = watchdog;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(kDownloadStallTimeout * NSEC_PER_SEC)),
                   dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0),
                   watchdog);
}

- (void)receiveChunk:(NSData *)chunk position:(OCTToxFileSize)position
{
    self.lastChunkTime = CACurrentMediaTime();
    [self resetStallWatchdog];

    if (! chunk) {
        if ([self.output finishWriting]) {
            [self finishWithSuccess];
        }
        else {
            [self finishWithError:[NSError acceptFileErrorCannotWriteToFile]];
        }
        return;
    }

    if (self.bytesDone != position) {
        OCTLogWarn(@"bytesDone doesn't match position");
        [self.tox fileSendControlForFileNumber:self.fileNumber
                                  friendNumber:self.friendNumber
                                       control:OCTToxFileControlCancel
                                         error:nil];
        [self finishWithError:[NSError acceptFileErrorInternalError]];
        return;
    }

    if (! [self.output writeData:chunk]) {
        [self finishWithError:[NSError acceptFileErrorCannotWriteToFile]];
        return;
    }

    [self updateBytesDone:self.bytesDone + chunk.length];
}

#pragma mark -  Override

- (void)operationStarted
{
    [super operationStarted];

    if (! [self.output prepareToWrite]) {
        [self finishWithError:[NSError acceptFileErrorCannotWriteToFile]];
    }

    NSError *error;
    if (! [self.tox fileSendControlForFileNumber:self.fileNumber
                                    friendNumber:self.friendNumber
                                         control:OCTToxFileControlResume
                                           error:&error]) {
        OCTLogWarn(@"cannot send control %@", error);
        [self finishWithError:[NSError acceptFileErrorFromToxFileControl:error.code]];
        return;
    }

    self.lastChunkTime = CACurrentMediaTime();
    [self resetStallWatchdog];
}

- (void)operationWasCanceled
{
    if (self.stallWatchdog) {
        dispatch_block_cancel(self.stallWatchdog);
        self.stallWatchdog = nil;
    }

    [super operationWasCanceled];

    [self.output cancel];
}

@end
