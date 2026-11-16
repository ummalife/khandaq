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

    return [self newPathInDirectory:directory safeName:[self safeNameForFileName:fileName] isTaken:^BOOL (NSString *path) {
        return [self fileExistsAtPath:path];
    }];
}

+ (nonnull NSString *)createNewGroupFilePathInDirectory:(nonnull NSString *)directory
                                               fileName:(nonnull NSString *)fileName
                                             namesInUse:(nullable NSSet<NSString *> *)namesInUse
{
    NSParameterAssert(directory);
    NSParameterAssert(fileName);

    NSString *safeName = [self safeNameForFileName:fileName];
    NSUInteger dots = 0;

    while (dots < safeName.length && [safeName characterAtIndex:dots] == '.') {
        dots++;
    }

    if (dots > 0) {
        NSString *underscores = [@"" stringByPaddingToLength:dots withString:@"_" startingAtIndex:0];
        safeName = [underscores stringByAppendingString:[safeName substringFromIndex:dots]];
    }

    return [self newPathInDirectory:directory safeName:safeName isTaken:^BOOL (NSString *path) {
        return [namesInUse containsObject:[[path lastPathComponent] lowercaseString]] || [self fileExistsAtPath:path];
    }];
}

#pragma mark -  Private

+ (NSString *)safeNameForFileName:(NSString *)fileName
{
    NSString *safeName = [fileName lastPathComponent];
    if ((safeName.length == 0) || [safeName isEqualToString:@"."] || [safeName isEqualToString:@".."] ||
        [safeName containsString:@"/"] || [safeName containsString:@"\\"] || [safeName containsString:@".."])
    {
        safeName = @"unsafe_filename";
    }

    return safeName;
}

/**
 * `safeName` in `directory`, or with the first " N" suffix whose path `isTaken` says is free.
 */
+ (NSString *)newPathInDirectory:(NSString *)directory
                        safeName:(NSString *)safeName
                         isTaken:(BOOL (^)(NSString *path))isTaken
{
    NSString *path = [directory stringByAppendingPathComponent:safeName];

    if (! isTaken(path)) {
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

        if (! isTaken(path)) {
            return path;
        }

        suffix++;
    }
}

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
