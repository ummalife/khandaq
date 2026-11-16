// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTFileDownloadOperation.h"
#import "OCTFileBaseOperation+Private.h"
#import "OCTFileOutputProtocol.h"
#import "OCTLogging.h"
#import "NSError+OCTFile.h"
#import <QuartzCore/QuartzCore.h>

static const CFTimeInterval kDownloadStallTimeout = 90.0;

// KHANDAQ (0.2.57): how soon a seek or an accept that toxcore's send queue to the friend did not take is tried
// again. The queue is full while the friend takes no packets, or while a transfer of ours to them fills it.
static const NSTimeInterval kControlRetryInterval = 0.1;

@interface OCTFileDownloadOperation ()

@property (assign, nonatomic) CFTimeInterval lastChunkTime;
@property (strong, nonatomic) dispatch_block_t stallWatchdog;
@property (assign, nonatomic, readwrite) BOOL failedBeforeAccept;

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

    [self seekToResumePosition];
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

#pragma mark -  Private

/**
 * KHANDAQ (0.2.57): the seek must come before the Resume, while toxcore still waits for the accept. A seek the send
 * queue did not take is tried again. Any other failure leaves the bytes kept as they are and fails the transfer
 * before it was accepted (failedBeforeAccept), so it waits for the next offer. It used to take the file from its
 * first byte instead, which emptied the partial file before anyone knew the Resume would go out: with the friend
 * gone a moment after the offer, every byte that had come was lost.
 */
- (void)seekToResumePosition
{
    if (self.isCancelled || self.isFinished) {
        return;
    }

    if (self.resumePosition <= 0) {
        [self acceptFromPosition:0];
        return;
    }

    NSError *seekError;
    if ([self.tox fileSeekForFileNumber:self.fileNumber
                           friendNumber:self.friendNumber
                               position:self.resumePosition
                                  error:&seekError]) {
        [self acceptFromPosition:self.resumePosition];
        return;
    }

    if (seekError.code == OCTToxErrorFileSeekSendq) {
        [self retrySoon:^(OCTFileDownloadOperation *operation) {
            [operation seekToResumePosition];
        }];
        return;
    }

    if (self.cancelsWhenSeekFails) {
        OCTLogWarn(@"cannot seek to %lld, cancelling: %@", self.resumePosition, seekError);
        [self.tox fileSendControlForFileNumber:self.fileNumber
                                  friendNumber:self.friendNumber
                                       control:OCTToxFileControlCancel
                                         error:nil];
        [self finishWithError:[NSError acceptFileErrorInternalError]];
        return;
    }

    OCTLogWarn(@"cannot seek to %lld, the bytes kept wait for the next offer: %@", self.resumePosition, seekError);
    self.failedBeforeAccept = YES;
    [self finishWithError:[NSError acceptFileErrorInternalError]];
}

- (void)acceptFromPosition:(OCTToxFileSize)position
{
    BOOL prepared = (position > 0 && [self.output respondsToSelector:@selector(prepareToWriteFromOffset:)])
        ? [self.output prepareToWriteFromOffset:position]
        : [self.output prepareToWrite];

    if (! prepared) {
        // KHANDAQ (#48): MUST return — without it, execution fell through to the Resume control below
        // and called finishWithError a SECOND time (finishWithError has no already-finished guard),
        // double-firing the failure block.
        // KHANDAQ (0.2.57): and tell the sender, whose offer would otherwise wait for an accept that never comes.
        [self.tox fileSendControlForFileNumber:self.fileNumber
                                  friendNumber:self.friendNumber
                                       control:OCTToxFileControlCancel
                                         error:nil];
        [self finishWithError:[NSError acceptFileErrorCannotWriteToFile]];
        return;
    }

    if (position > 0) {
        [self beginAtBytes:position];
    }

    [self sendResumeFromPosition:position];
}

/// KHANDAQ (0.2.57): an accept the send queue did not take is tried again, as the seek before it is.
- (void)sendResumeFromPosition:(OCTToxFileSize)position
{
    if (self.isCancelled || self.isFinished) {
        return;
    }

    NSError *error;
    if (! [self.tox fileSendControlForFileNumber:self.fileNumber
                                    friendNumber:self.friendNumber
                                         control:OCTToxFileControlResume
                                           error:&error]) {
        if (error.code == OCTToxErrorFileControlSendq) {
            [self retrySoon:^(OCTFileDownloadOperation *operation) {
                [operation sendResumeFromPosition:position];
            }];
            return;
        }

        OCTLogWarn(@"cannot send control %@", error);
        // KHANDAQ (0.2.57): the bytes kept up to the seek are still there to go on from.
        self.failedBeforeAccept = (position > 0);
        [self finishWithError:[NSError acceptFileErrorFromToxFileControl:error.code]];
        return;
    }

    self.lastChunkTime = CACurrentMediaTime();
    [self resetStallWatchdog];
}

/// Runs `step` again shortly, on a global queue, unless the operation has ended meanwhile.
- (void)retrySoon:(void (^)(OCTFileDownloadOperation *operation))step
{
    __weak OCTFileDownloadOperation *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(kControlRetryInterval * NSEC_PER_SEC)),
                   dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        OCTFileDownloadOperation *strongSelf = weakSelf;
        if (! strongSelf || strongSelf.isCancelled || strongSelf.isFinished) {
            return;
        }
        step(strongSelf);
    });
}

@end
