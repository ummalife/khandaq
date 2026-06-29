// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

typedef BOOL (^OCTNgcGroupLiveAudioSendPacketBlock)(uint32_t groupNumber, NSData *packet, BOOL lossless, NSError **error);

@interface OCTNgcGroupLiveAudio : NSObject

- (instancetype)initWithSendPacketBlock:(OCTNgcGroupLiveAudioSendPacketBlock)sendPacketBlock;

- (BOOL)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                                       data:(NSData *)data;

- (BOOL)startLiveCaptureForGroupNumber:(uint32_t)groupNumber error:(NSError **)error;
- (void)stopLiveCapture;

- (BOOL)isLiveCaptureActive;

- (void)prepareIncomingPlayback;

@end

NS_ASSUME_NONNULL_END
