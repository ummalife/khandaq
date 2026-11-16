// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>
#import "OCTFileOutputProtocol.h"

@interface OCTFilePathOutput : NSObject <OCTFileOutputProtocol>

@property (copy, nonatomic, readonly, nonnull) NSString *resultFilePath;

- (nullable instancetype)initWithTempFolder:(nonnull NSString *)tempFolder
                               resultFolder:(nonnull NSString *)resultFolder
                                   fileName:(nonnull NSString *)fileName;

/**
 * KHANDAQ (0.2.57): a download that can go on after an interruption. The bytes go to
 * partialFilePathForResultFilePath:, next to the result in the profile rather than in the temporary directory,
 * and a cancel keeps them: they are where the transfer goes on from when the friend offers the file again.
 * The partial file becomes the result when the last byte is in.
 */
- (nullable instancetype)initWithResultFilePath:(nonnull NSString *)resultFilePath;

/**
 * KHANDAQ (0.2.57): where the bytes of a download into `resultFilePath` wait until the last one is in.
 */
+ (nonnull NSString *)partialFilePathForResultFilePath:(nonnull NSString *)resultFilePath;

- (nullable instancetype)init NS_UNAVAILABLE;
+ (nullable instancetype)new NS_UNAVAILABLE;

@end
