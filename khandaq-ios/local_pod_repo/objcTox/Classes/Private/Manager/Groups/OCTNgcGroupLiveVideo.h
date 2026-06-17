// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

NS_ASSUME_NONNULL_BEGIN

typedef BOOL (^OCTNgcGroupLiveVideoSendPacketBlock)(uint32_t groupNumber, NSData *packet, BOOL lossless, NSError **error);
typedef void (^OCTNgcGroupLiveVideoFrameBlock)(UIImage * _Nullable frame);
typedef void (^OCTNgcGroupLiveVideoActivityBlock)(uint32_t groupNumber);

@interface OCTNgcGroupLiveVideo : NSObject

- (instancetype)initWithSendPacketBlock:(OCTNgcGroupLiveVideoSendPacketBlock)sendPacketBlock;

- (BOOL)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                       peerPublicKeyHex:(nullable NSString *)peerPublicKeyHex
                                       data:(NSData *)data;

- (BOOL)startLiveCaptureForGroupNumber:(uint32_t)groupNumber
                      remoteFrameBlock:(nullable OCTNgcGroupLiveVideoFrameBlock)remoteFrameBlock
                       localFrameBlock:(nullable OCTNgcGroupLiveVideoFrameBlock)localFrameBlock
                                 error:(NSError **)error;

- (void)stopLiveCapture;

- (BOOL)isLiveCaptureActive;

@property (nonatomic, readonly, assign) uint32_t captureGroupNumber;

- (void)setHighQualityEnabled:(BOOL)enabled;

- (BOOL)isHighQualityEnabled;

- (void)setIncomingVideoActivityBlock:(nullable OCTNgcGroupLiveVideoActivityBlock)block;

- (BOOL)hasRecentIncomingVideoForGroupNumber:(uint32_t)groupNumber withinSeconds:(NSTimeInterval)seconds;

- (NSUInteger)recentIncomingVideoPeerCountForGroupNumber:(uint32_t)groupNumber withinSeconds:(NSTimeInterval)seconds;

- (nullable NSString *)primaryRecentIncomingVideoPeerPublicKeyHexForGroupNumber:(uint32_t)groupNumber
                                                                 withinSeconds:(NSTimeInterval)seconds;

@end

NS_ASSUME_NONNULL_END
