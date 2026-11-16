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
 * partialFilePathForResultFilePath:ownerIdentifier:, next to the result in the profile rather than in the
 * temporary directory, and a cancel keeps them: they are where the transfer goes on from when the friend offers
 * the file again. The partial file becomes the result when the last byte is in.
 *
 * @param ownerIdentifier The unique identifier of the message the file belongs to: the partial file is named for
 *        it, so it can never be a file another message holds.
 */
- (nullable instancetype)initWithResultFilePath:(nonnull NSString *)resultFilePath
                                ownerIdentifier:(nonnull NSString *)ownerIdentifier;

/**
 * KHANDAQ (0.2.57): where the bytes of a download into `resultFilePath` for the message `ownerIdentifier` wait
 * until the last one is in: ".<ownerIdentifier>.part" in the folder of the result.
 */
+ (nonnull NSString *)partialFilePathForResultFilePath:(nonnull NSString *)resultFilePath
                                       ownerIdentifier:(nonnull NSString *)ownerIdentifier;

/**
 * KHANDAQ (0.2.57): the message a partial file belongs to, from the name partialFilePathForResultFilePath:
 * ownerIdentifier: gives it; nil for a name of another shape.
 */
+ (nullable NSString *)ownerIdentifierOfPartialFileName:(nonnull NSString *)fileName;

- (nullable instancetype)init NS_UNAVAILABLE;
+ (nullable instancetype)new NS_UNAVAILABLE;

@end
