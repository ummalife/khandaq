// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <float.h>

#import "OCTVideoBitRateAdapter.h"

static const OCTToxAVVideoBitRate kCallStartBitRate = 600;
static const OCTToxAVVideoBitRate kCallFloorBitRate = 150;
static const OCTToxAVVideoBitRate kCallCeilingBitRate = 1200;

/// The reports that come within this time of a drop describe what was sent before it.
static const NSTimeInterval kHoldAfterDrop = 1.5;
/// The time without loss reports before the rate climbs.
static const NSTimeInterval kQuietBeforeClimb = 4.0;
/// The time between two climbs.
static const NSTimeInterval kClimbInterval = 2.0;
/// The smallest climb.
static const OCTToxAVVideoBitRate kMinimumClimb = 20;

@interface OCTVideoBitRateAdapter ()

@property (nonatomic, assign, readwrite) OCTToxAVVideoBitRate bitRate;
@property (nonatomic, assign) NSTimeInterval lastLossAt;
@property (nonatomic, assign) NSTimeInterval lastDropAt;
@property (nonatomic, assign) NSTimeInterval lastChangeAt;

@end

@implementation OCTVideoBitRateAdapter

+ (instancetype)adapterForCalls
{
    return [[self alloc] initWithStartBitRate:kCallStartBitRate
                                 floorBitRate:kCallFloorBitRate
                               ceilingBitRate:kCallCeilingBitRate];
}

- (instancetype)initWithStartBitRate:(OCTToxAVVideoBitRate)startBitRate
                        floorBitRate:(OCTToxAVVideoBitRate)floorBitRate
                      ceilingBitRate:(OCTToxAVVideoBitRate)ceilingBitRate
{
    self = [super init];

    if (! self) {
        return nil;
    }

    NSParameterAssert(floorBitRate > 0);
    NSParameterAssert(floorBitRate <= startBitRate);
    NSParameterAssert(startBitRate <= ceilingBitRate);

    _startBitRate = startBitRate;
    _floorBitRate = floorBitRate;
    _ceilingBitRate = ceilingBitRate;
    [self restartAt:0];

    return self;
}

- (void)restartAt:(NSTimeInterval)now
{
    self.bitRate = self.startBitRate;
    self.lastLossAt = now;
    self.lastChangeAt = now;
    self.lastDropAt = -DBL_MAX;
}

- (BOOL)takeLossSuggestion:(OCTToxAVVideoBitRate)suggestedBitRate at:(NSTimeInterval)now
{
    self.lastLossAt = now;

    if ((now - self.lastDropAt) < kHoldAfterDrop) {
        return NO;
    }

    const OCTToxAVVideoBitRate next = MAX(self.floorBitRate, suggestedBitRate);

    if (next >= self.bitRate) {
        return NO;
    }

    self.bitRate = next;
    self.lastDropAt = now;
    self.lastChangeAt = now;

    return YES;
}

- (BOOL)tickAt:(NSTimeInterval)now
{
    if (self.bitRate >= self.ceilingBitRate) {
        return NO;
    }

    if (((now - self.lastLossAt) < kQuietBeforeClimb) || ((now - self.lastChangeAt) < kClimbInterval)) {
        return NO;
    }

    const OCTToxAVVideoBitRate climb = MAX(kMinimumClimb, self.bitRate / 10);
    self.bitRate = MIN(self.ceilingBitRate, self.bitRate + climb);
    self.lastChangeAt = now;

    return YES;
}

@end
