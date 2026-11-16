// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>
#import "OCTToxConstants.h"

@protocol OCTFileOutputProtocol <NSObject>

/**
 * Prepare input to write. This method will be called before first call to writeData:.
 *
 * @return YES on success, NO on failure.
 */
- (BOOL)prepareToWrite;

/**
 * Write data to output.
 *
 * @param data Data to write.
 *
 * @return YES on success, NO on failure.
 */
- (BOOL)writeData:(nonnull NSData *)data;

/**
 * This method is called after last writeData: method.
 *
 * @return YES on success, NO on failure.
 */
- (BOOL)finishWriting;

/**
 * This method is called if all progress was canceled. Do needed cleanup.
 */
- (void)cancel;

@optional

/**
 * KHANDAQ (0.2.57): called instead of prepareToWrite when the transfer goes on from `offset`: the output keeps
 * the bytes it holds up to it, drops what lies past it, and the next writeData: appends there.
 *
 * @return YES on success, NO when the output holds fewer bytes than `offset` or cannot be written.
 */
- (BOOL)prepareToWriteFromOffset:(OCTToxFileSize)offset;

@end
