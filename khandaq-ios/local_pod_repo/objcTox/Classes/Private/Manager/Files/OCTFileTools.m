// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTFileTools.h"

@implementation OCTFileTools

#pragma mark -  Public

+ (nonnull NSString *)createNewFilePathInDirectory:(nonnull NSString *)directory fileName:(nonnull NSString *)fileName
{
    NSParameterAssert(directory);
    NSParameterAssert(fileName);

    NSString *safeName = [fileName lastPathComponent];
    if ((safeName.length == 0) || [safeName isEqualToString:@"."] || [safeName isEqualToString:@".."] ||
        [safeName containsString:@"/"] || [safeName containsString:@"\\"] || [safeName containsString:@".."])
    {
        safeName = @"unsafe_filename";
    }

    NSString *path = [directory stringByAppendingPathComponent:safeName];

    if (! [self fileExistsAtPath:path]) {
        return path;
    }

    NSString *base;
    NSString *pathExtension = [safeName pathExtension];
    NSInteger suffix;

    [self getBaseString:&base andSuffix:&suffix fromString:[safeName stringByDeletingPathExtension]];

    while (YES) {
        NSString *resultName = [base stringByAppendingFormat:@" %ld", (long)suffix];

        if (pathExtension.length > 0) {
            resultName = [resultName stringByAppendingPathExtension:pathExtension];
        }

        NSString *path = [directory stringByAppendingPathComponent:resultName];

        if (! [self fileExistsAtPath:path]) {
            return path;
        }

        suffix++;
    }
}

#pragma mark -  Private

+ (BOOL)fileExistsAtPath:(NSString *)filePath
{
    return [[NSFileManager defaultManager] fileExistsAtPath:filePath];
}

+ (void)getBaseString:(NSString **)baseString andSuffix:(NSInteger *)suffix fromString:(NSString *)original
{
    NSString *tempBase = original;
    NSInteger tempSuffix = 1;

    NSArray *components = [tempBase componentsSeparatedByString:@" "];

    if (components.count > 1) {
        NSString *lastComponent = components.lastObject;
        NSCharacterSet *set = [NSCharacterSet characterSetWithCharactersInString:lastComponent];

        if ([[NSCharacterSet decimalDigitCharacterSet] isSupersetOfSet:set]) {
            tempSuffix = [lastComponent integerValue];

            // -1 for space.
            NSInteger index = tempBase.length - lastComponent.length - 1;
            tempBase = [tempBase substringToIndex:index];
        }
    }
    tempSuffix++;

    *baseString = tempBase;
    *suffix = tempSuffix;
}

@end
