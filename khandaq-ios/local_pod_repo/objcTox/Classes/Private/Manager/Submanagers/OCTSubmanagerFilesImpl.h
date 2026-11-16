// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerFiles.h"
#import "OCTSubmanagerProtocol.h"

@interface OCTSubmanagerFilesImpl : NSObject <OCTSubmanagerFiles, OCTSubmanagerProtocol>

/**
 * KHANDAQ (0.2.57): how long a friend has to be online before a file of theirs that waits for the connection
 * counts as interrupted for good: they did not offer it again (a client without resume, or the file is gone
 * there). It counts from the last time a transfer of theirs to us was under way: a sender may offer what waits
 * one file at a time. 120 seconds; the tests shorten it.
 */
@property (assign, nonatomic) NSTimeInterval reofferGraceInterval;

@end
