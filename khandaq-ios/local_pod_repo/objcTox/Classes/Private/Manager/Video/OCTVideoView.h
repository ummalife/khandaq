// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTView.h"

@import GLKit;
@import CoreVideo;

#if TARGET_OS_IPHONE
// KHANDAQ (remote-video-black fix): on iOS render incoming frames through an
// AVSampleBufferDisplayLayer instead of a GLKView/OpenGL-ES + CIContext. The GLKView path built its
// EAGL/CIContext off-main and is deprecated/fragile on iOS 17/18/26 real devices, leaving the remote
// peer's video permanently black. AVSampleBufferDisplayLayer is the modern, robust path.
@interface OCTVideoView : UIView
#else
@interface OCTVideoView : NSOpenGLView
#endif

@property (strong, nonatomic) CIImage *image;

/**
 * Allocs and calls the platform-specific initializers.
 */
+ (instancetype)view;

#if TARGET_OS_IPHONE
// Enqueue a decoded frame for display. Must be called on the main thread.
- (void)enqueuePixelBuffer:(CVPixelBufferRef)pixelBuffer;
#endif

@end
