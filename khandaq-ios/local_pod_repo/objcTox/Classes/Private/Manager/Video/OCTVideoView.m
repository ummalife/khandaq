// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTVideoView.h"
#import "OCTManagerConstants.h"
#import "OCTLogging.h"
@import Foundation;
@import AVFoundation;

@interface OCTVideoView ()

@property (strong, nonatomic) CIContext *coreImageContext;

@end

@implementation OCTVideoView

+ (instancetype)view
{
#if TARGET_OS_IPHONE
    OCTVideoView *videoView = [[self alloc] initWithFrame:CGRectZero];
#else
    OCTVideoView *videoView = [[self alloc] initWithFrame:CGRectZero pixelFormat:[self defaultPixelFormat]];
#endif
    [videoView finishInitializing];
    return videoView;
}

- (void)dealloc
{
    OCTLogVerbose(@"dealloc");
}

- (void)finishInitializing
{
#if TARGET_OS_IPHONE
    // KHANDAQ (remote-video-black fix): build the EAGLContext + CIContext SYNCHRONOUSLY on the main
    // thread. Previously this ran on a background global queue, which set self.context (a GLKView /
    // CAEAGLLayer-backed property) off the main thread. That could leave the layer's drawable invalid
    // (drawableWidth/Height == 0) or the contexts still nil when the first -display fired, so every
    // frame was silently dropped -> the remote peer's video (e.g. from Android) stayed permanently
    // black even though frames were arriving. Creating them on-main before any -display fixes it.
    void (^build)(void) = ^{
        self.context = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES2];
        self.coreImageContext = [CIContext contextWithEAGLContext:self.context];
        self.enableSetNeedsDisplay = NO;
    };
    if ([NSThread isMainThread]) {
        build();
    }
    else {
        dispatch_sync(dispatch_get_main_queue(), build);
    }
#endif
}

- (void)setImage:(CIImage *)image
{
    _image = image;
#if TARGET_OS_IPHONE
    [self display];
#else
    [self setNeedsDisplay:YES];
#endif
}

#if ! TARGET_OS_IPHONE
// OS X: we need to correct the viewport when the view size changes
- (void)reshape
{
    glViewport(0, 0, self.bounds.size.width, self.bounds.size.height);
}
#endif

- (void)drawRect:(CGRect)rect
{
#if TARGET_OS_IPHONE
    if (self.image && self.coreImageContext) {

        glClearColor(0, 0.0, 0.0, 1.0);
        glClear(GL_COLOR_BUFFER_BIT);

        // KHANDAQ: drawRect's `rect` is in POINTS, but the CIContext renders into this GLKView's
        // renderbuffer, whose coordinate space is in PIXELS (points * contentScaleFactor). Drawing
        // into the points-sized rect filled only a 1/scale corner of the buffer (bottom-left on
        // Retina), so the remote video showed up as a tiny image in the corner. Draw into the full
        // pixel-sized drawable instead so the feed fills the view (aspect-fit).
        CGRect drawableRect = CGRectMake(0.0, 0.0, self.drawableWidth, self.drawableHeight);
        if (CGRectIsEmpty(drawableRect)) {
            drawableRect = rect;
        }
        CGRect destRect = AVMakeRectWithAspectRatioInsideRect(self.image.extent.size, drawableRect);
        [self.coreImageContext drawImage:self.image inRect:destRect fromRect:self.image.extent];
    }
#else
    [self.openGLContext makeCurrentContext];

    if (self.image) {
        CIContext *ctx = [CIContext contextWithCGLContext:self.openGLContext.CGLContextObj pixelFormat:self.openGLContext.pixelFormat.CGLPixelFormatObj colorSpace:nil options:nil];
        // The GL coordinate system goes from -1 to 1 on all axes by default.
        // We didn't set a matrix so use that instead of bounds.
        [ctx drawImage:self.image inRect:(CGRect) {-1, -1, 2, 2} fromRect:self.image.extent];
    }
    else {
        glClearColor(0.0, 0.0, 0.0, 1.0);
        glClear(GL_COLOR_BUFFER_BIT);
    }
    glFlush();
#endif
}
@end
