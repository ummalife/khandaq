// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTFileBaseOperation.h"

@class OCTTox;
@protocol OCTFileOutputProtocol;

/**
 * File operation for downloading file.
 *
 * When started will automatically send resume control to friend.
 */
@interface OCTFileDownloadOperation : OCTFileBaseOperation

@property (strong, nonatomic, readonly, nonnull) id<OCTFileOutputProtocol> output;

/**
 * KHANDAQ (0.2.57): where the transfer goes on from, set before the operation is queued. The receiver seeks
 * there before it accepts, so the sender sends from that byte on, and the output keeps what it holds up to it.
 * 0 starts from the first byte.
 */
@property (assign, nonatomic) OCTToxFileSize resumePosition;

/**
 * KHANDAQ (0.2.57): YES cancels the transfer when the seek to resumePosition fails, instead of leaving it to the
 * next offer (failedBeforeAccept). For a file that is complete here and wants only its last byte again.
 */
@property (assign, nonatomic) BOOL cancelsWhenSeekFails;

/**
 * KHANDAQ (0.2.57): the transfer failed before it was accepted here, with bytes kept to go on from
 * (resumePosition > 0) and left as they were: the seek, or the accept after it, did not go out for another reason
 * than a full send queue, which is tried again. The file waits for its next offer with them instead of failing
 * (OCTSubmanagerFilesImpl).
 */
@property (assign, nonatomic, readonly) BOOL failedBeforeAccept;

/**
 * Create operation.
 *
 * @param fileOutput Output to use as a destination for file transfer.
 *
 * For other parameters description see OCTFileBaseOperation.
 */
- (nullable instancetype)initWithTox:(nonnull OCTTox *)tox
                          fileOutput:(nonnull id<OCTFileOutputProtocol>)fileOutput
                        friendNumber:(OCTToxFriendNumber)friendNumber
                          fileNumber:(OCTToxFileNumber)fileNumber
                            fileSize:(OCTToxFileSize)fileSize
                            userInfo:(nullable NSDictionary *)userInfo
                       progressBlock:(nullable OCTFileBaseOperationProgressBlock)progressBlock
                      etaUpdateBlock:(nullable OCTFileBaseOperationProgressBlock)etaUpdateBlock
                        successBlock:(nullable OCTFileBaseOperationSuccessBlock)successBlock
                        failureBlock:(nullable OCTFileBaseOperationFailureBlock)failureBlock;

/**
 * Call this method to get next chunk to operation.
 *
 * @param chunk Next chunk of data to append to file.
 * @param position Position in file to append chunk.
 */
- (void)receiveChunk:(nullable NSData *)chunk position:(OCTToxFileSize)position;

@end
