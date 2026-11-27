// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTToxAVConstants.h"

NS_ASSUME_NONNULL_BEGIN

/**
 * KHANDAQ (calls, 27.11): the rate a call sends its video at, as the network lets it through.
 *
 * The call sent at a fixed 4000 kbit/s and did nothing with the loss toxav reports, so on a link that could not
 * carry it the video and the voice went in pieces. Now the rate starts low, goes down at once to what toxav
 * suggests when the other side reports a tenth or more of the call lost (never under the floor), and climbs back
 * by a tenth at a time after a quiet spell, up to the ceiling. The suggestions that come in just after a drop
 * still describe what was sent before it, so they do not take the rate down again.
 *
 * Rates are in kbit/s and times in seconds of a monotonic clock. Used on the main queue only.
 */
@interface OCTVideoBitRateAdapter : NSObject

/// The rate a call starts its video at.
@property (nonatomic, assign, readonly) OCTToxAVVideoBitRate startBitRate;
/// The lowest rate a suggestion takes the video to.
@property (nonatomic, assign, readonly) OCTToxAVVideoBitRate floorBitRate;
/// The highest rate the video climbs to.
@property (nonatomic, assign, readonly) OCTToxAVVideoBitRate ceilingBitRate;
/// The rate to send at now.
@property (nonatomic, assign, readonly) OCTToxAVVideoBitRate bitRate;

/// The rates of a call: 600 kbit/s to start, 150 to 1200.
+ (instancetype)adapterForCalls;

- (instancetype)initWithStartBitRate:(OCTToxAVVideoBitRate)startBitRate
                        floorBitRate:(OCTToxAVVideoBitRate)floorBitRate
                      ceilingBitRate:(OCTToxAVVideoBitRate)ceilingBitRate NS_DESIGNATED_INITIALIZER;

- (instancetype)init NS_UNAVAILABLE;

/// The video starts (a call, a resumed call, the camera turned on): back to the start rate; the first climb waits for
/// a quiet spell from now.
- (void)restartAt:(NSTimeInterval)now;

/// toxav's suggestion after the other side reported loss. Returns YES when the rate changed.
- (BOOL)takeLossSuggestion:(OCTToxAVVideoBitRate)suggestedBitRate at:(NSTimeInterval)now;

/// Called about once a second while the video is sent. Returns YES when the rate changed.
- (BOOL)tickAt:(NSTimeInterval)now;

@end

NS_ASSUME_NONNULL_END
