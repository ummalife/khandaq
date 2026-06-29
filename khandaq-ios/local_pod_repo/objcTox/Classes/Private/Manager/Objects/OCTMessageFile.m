// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTMessageFile.h"

@interface OCTMessageFile ()

@end

@implementation OCTMessageFile

#pragma mark -  Public

// KHANDAQ (#44): media became unreachable after an app relaunch — files persisted on disk but the
// stored ABSOLUTE path embedded the app's data-container UUID, which iOS can rotate between launches
// (and the legacy `stringByAbbreviatingWithTildeInPath` silently failed to relativise when the path
// carried the /private symlink prefix, so it stored the absolute path). We now store the path RELATIVE
// to the container (anchored at the well-known Documents/Library/tmp subtree) and re-root it under the
// CURRENT home on read, so attachments survive a container-UUID change. Legacy absolute/tilde values
// are still understood, and a stale absolute path is recovered by re-rooting its tail.
static NSString *OCTMessageFileContainerRelativeTail(NSString *path)
{
    if (path.length == 0) {
        return nil;
    }

    NSArray<NSString *> *anchors = @[@"/Documents/", @"/Library/", @"/tmp/"];
    NSUInteger bestLocation = NSNotFound;

    for (NSString *anchor in anchors) {
        NSRange range = [path rangeOfString:anchor options:NSBackwardsSearch];
        if (range.location != NSNotFound && (bestLocation == NSNotFound || range.location > bestLocation)) {
            bestLocation = range.location;
        }
    }

    if (bestLocation == NSNotFound) {
        return nil;
    }

    // Keep e.g. "Documents/.../file.jpg" (drop the leading slash so we can append to NSHomeDirectory()).
    return [path substringFromIndex:bestLocation + 1];
}

- (nullable NSString *)filePath
{
    NSString *stored = self.internalFilePath;

    if (stored.length == 0) {
        return nil;
    }

    // Legacy tilde-abbreviated form.
    if ([stored hasPrefix:@"~"]) {
        return [stored stringByExpandingTildeInPath];
    }

    // Container-relative form (new) — re-root under the current home.
    if (! [stored hasPrefix:@"/"]) {
        return [NSHomeDirectory() stringByAppendingPathComponent:stored];
    }

    // Legacy absolute path: use as-is if it still resolves, else recover by re-rooting its container
    // tail under the current home (handles a container-UUID change since the path was stored).
    if ([[NSFileManager defaultManager] fileExistsAtPath:stored]) {
        return stored;
    }

    NSString *tail = OCTMessageFileContainerRelativeTail(stored);
    if (tail.length > 0) {
        return [NSHomeDirectory() stringByAppendingPathComponent:tail];
    }

    return stored;
}

- (void)internalSetFilePath:(NSString *)path
{
    if (path.length == 0) {
        self.internalFilePath = path;
        return;
    }

    // Store container-relative when possible so the path re-roots under the current container on read.
    NSString *tail = OCTMessageFileContainerRelativeTail(path);
    self.internalFilePath = tail.length > 0 ? tail : path;
}

- (NSString *)description
{
    NSString *description = [super description];

    const NSUInteger maxSymbols = 3;
    NSString *fileName = self.fileName.length > maxSymbols ? ([self.fileName substringToIndex:maxSymbols]) : @"";

    return [description stringByAppendingFormat:@"OCTMessageFile with fileName = %@..., fileSize = %llu",
            fileName, self.fileSize];
}

@end
