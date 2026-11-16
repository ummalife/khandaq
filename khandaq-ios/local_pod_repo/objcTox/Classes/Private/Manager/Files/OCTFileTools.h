// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

@interface OCTFileTools : NSObject

/**
 * Creates filePath in directory for given fileName. In case if file already exists appends " N" suffix,
 * e.g. "file 2.txt", "file 3.txt".
 *
 * @param directory Directory part of filePath.
 * @param fileName Name of the file to use in path.
 *
 * @return File path to file that does not exist.
 */
+ (nonnull NSString *)createNewFilePathInDirectory:(nonnull NSString *)directory fileName:(nonnull NSString *)fileName;

/**
 * KHANDAQ (0.2.57): the path for a file that comes in a group, as createNewFilePathInDirectory:fileName: with two more
 * rules. Its name never starts with a dot: each leading dot becomes "_", so the file is never hidden from the person
 * nor from the cleanup of the group's files. And its name is none of `namesInUse`, names in lower case: a message of
 * the group still names that file, even when its bytes are gone, and a new file there would show under that message.
 *
 * @param directory Directory part of filePath.
 * @param fileName Name of the file to use in path.
 * @param namesInUse The names, in lower case, the messages of the group give their files in `directory`.
 *
 * @return File path to file that does not exist and that no message names.
 */
+ (nonnull NSString *)createNewGroupFilePathInDirectory:(nonnull NSString *)directory
                                               fileName:(nonnull NSString *)fileName
                                             namesInUse:(nullable NSSet<NSString *> *)namesInUse;

@end
