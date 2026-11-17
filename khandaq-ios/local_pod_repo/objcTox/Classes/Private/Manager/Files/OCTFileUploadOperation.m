// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTFileUploadOperation.h"
#import "OCTFileBaseOperation+Private.h"
#import "OCTFileInputProtocol.h"
#import "OCTLogging.h"
#import "NSError+OCTFile.h"

#import <QuartzCore/QuartzCore.h>

// KHANDAQ (0.2.57): how soon chunks toxcore's send queue did not take are tried again: at once while the queue is
// full for a moment, as a fast transfer's is, and less often once the friend has taken nothing for a while.
static const NSTimeInterval kPendingChunksRetryInterval = 0.01;
static const NSTimeInterval kPendingChunksSlowRetryInterval = 0.1;
static const NSTimeInterval kPendingChunksSlowRetryAfter = 1.0;

/// KHANDAQ (0.2.57): a chunk toxcore asked for that its send queue to the friend has not taken yet.
@interface OCTFileUploadPendingChunk : NSObject

@property (assign, nonatomic) OCTToxFileSize position;
@property (assign, nonatomic) size_t length;

@end

@implementation OCTFileUploadPendingChunk
@end

@interface OCTFileUploadOperation ()

/// KHANDAQ (0.2.57): the chunks toxcore asked for and its send queue has not taken yet, in the order it asked for
/// them: toxcore takes a chunk only at the position it expects next. Locked by itself, as are the two below.
@property (strong, nonatomic, readonly) NSMutableArray<OCTFileUploadPendingChunk *> *pendingChunks;
/// When the send queue was found full, 0 while it takes the chunks.
@property (assign, nonatomic) CFTimeInterval sendQueueFullSince;
/// The queue has stayed full long enough to say so in the log. A fast transfer finds it full many times a second.
@property (assign, nonatomic) BOOL sendQueueStallLogged;
@property (assign, nonatomic) BOOL pendingChunksRetryScheduled;

@end

@implementation OCTFileUploadOperation

#pragma mark -  Public

- (nullable instancetype)initWithTox:(nonnull OCTTox *)tox
                           fileInput:(nonnull id<OCTFileInputProtocol>)fileInput
                        friendNumber:(OCTToxFriendNumber)friendNumber
                          fileNumber:(OCTToxFileNumber)fileNumber
                            fileSize:(OCTToxFileSize)fileSize
                            userInfo:(nullable NSDictionary *)userInfo
                       progressBlock:(nullable OCTFileBaseOperationProgressBlock)progressBlock
                      etaUpdateBlock:(nullable OCTFileBaseOperationProgressBlock)etaUpdateBlock
                        successBlock:(nullable OCTFileBaseOperationSuccessBlock)successBlock
                        failureBlock:(nullable OCTFileBaseOperationFailureBlock)failureBlock
{
    NSParameterAssert(fileInput);

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

    _input = fileInput;
    _pendingChunks = [NSMutableArray new];

    return self;
}

#pragma mark -  Public

- (void)chunkRequestWithPosition:(OCTToxFileSize)position length:(size_t)length
{
    if (length == 0) {
        [self finishWithSuccess];
        return;
    }

    OCTFileUploadPendingChunk *chunk = [OCTFileUploadPendingChunk new];
    chunk.position = position;
    chunk.length = length;

    @synchronized(self.pendingChunks) {
        [self.pendingChunks addObject:chunk];
    }
    [self sendPendingChunks];
}

#pragma mark -  Private

/**
 * KHANDAQ (0.2.57): sends the chunks that wait, oldest first, as far as toxcore's send queue to the friend takes
 * them, and tries the rest again shortly. A queue that stays full means the friend has stopped taking packets:
 * their network went, or they are slow. The transfer goes on once they take packets again or, when toxcore drops
 * the friend after its timeout, waits for the connection as any transfer under way does
 * (OCTSubmanagerFilesImpl requeueInFlightTransfersForDisconnectedFriend:). This used to retry for five seconds and
 * then cancel the transfer as failed, before the friend timeout (32 s) came: a friend whose network went in the
 * middle of a file left "Not sent" on this side, and nothing offered the file again.
 *
 * Called on toxcore's chunk queue and on the retry below. Not on the tox queue: the chunks go there synchronously,
 * with pendingChunks locked.
 */
- (void)sendPendingChunks
{
    @synchronized(self.pendingChunks) {
        while (self.pendingChunks.count > 0) {
            if (self.isCancelled || self.isFinished) {
                [self.pendingChunks removeAllObjects];
                return;
            }

            OCTFileUploadPendingChunk *chunk = self.pendingChunks.firstObject;
            NSData *data = [self.input bytesWithPosition:chunk.position length:chunk.length];

            if (! data) {
                [self.pendingChunks removeAllObjects];
                [self finishWithError:[NSError sendFileErrorCannotReadFile]];
                return;
            }

            NSError *error = nil;
            BOOL sent = [self.tox fileSendChunkForFileNumber:self.fileNumber
                                                friendNumber:self.friendNumber
                                                    position:chunk.position
                                                        data:data
                                                       error:&error];

            if (sent) {
                [self.pendingChunks removeObjectAtIndex:0];
                if (self.sendQueueStallLogged) {
                    OCTLogInfo(@"the friend takes chunks again after %.1f s, from byte %lld",
                               CACurrentMediaTime() - self.sendQueueFullSince, chunk.position);
                }
                self.sendQueueFullSince = 0;
                self.sendQueueStallLogged = NO;
                [self updateBytesDone:chunk.position + chunk.length];
                continue;
            }

            if (error.code == OCTToxErrorFileSendChunkSendq) {
                CFTimeInterval now = CACurrentMediaTime();
                if (self.sendQueueFullSince == 0) {
                    self.sendQueueFullSince = now;
                }
                else if (! self.sendQueueStallLogged && now - self.sendQueueFullSince > kPendingChunksSlowRetryAfter) {
                    self.sendQueueStallLogged = YES;
                    OCTLogInfo(@"the friend has taken no chunk for %.1f s, chunks from byte %lld wait",
                               now - self.sendQueueFullSince, chunk.position);
                }
                [self scheduleSendingPendingChunks];
                return;
            }

            OCTLogWarn(@"upload error %@", error);
            [self.pendingChunks removeAllObjects];

            [self.tox fileSendControlForFileNumber:self.fileNumber
                                      friendNumber:self.friendNumber
                                           control:OCTToxFileControlCancel
                                             error:nil];

            [self finishWithError:[NSError acceptFileErrorFromToxFileSendChunkError:error.code]];
            return;
        }
    }
}

/// With pendingChunks locked. One retry at a time; on a global queue, never the tox queue (see sendPendingChunks).
- (void)scheduleSendingPendingChunks
{
    if (self.pendingChunksRetryScheduled) {
        return;
    }
    self.pendingChunksRetryScheduled = YES;

    BOOL slow = (CACurrentMediaTime() - self.sendQueueFullSince) > kPendingChunksSlowRetryAfter;
    NSTimeInterval interval = slow ? kPendingChunksSlowRetryInterval : kPendingChunksRetryInterval;

    __weak OCTFileUploadOperation *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(interval * NSEC_PER_SEC)),
                   dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        OCTFileUploadOperation *strongSelf = weakSelf;
        if (! strongSelf) {
            return;
        }
        @synchronized(strongSelf.pendingChunks) {
            strongSelf.pendingChunksRetryScheduled = NO;
        }
        [strongSelf sendPendingChunks];
    });
}

#pragma mark -  Override

- (void)operationStarted
{
    [super operationStarted];

    if (! [self.input prepareToRead]) {
        [self finishWithError:[NSError sendFileErrorCannotReadFile]];
    }
}

@end
