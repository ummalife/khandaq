// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTVideoView.h"
#import "OCTManagerConstants.h"
#import "OCTLogging.h"
@import Foundation;
@import AVFoundation;
@import CoreMedia;

@interface OCTVideoView ()

@property (strong, nonatomic) CIContext *coreImageContext;

@end

@implementation OCTVideoView

#if TARGET_OS_IPHONE

// KHANDAQ (remote-video-black fix): render incoming frames through an AVSampleBufferDisplayLayer.
// The previous GLKView/OpenGL-ES + CIContext(contextWithEAGLContext:) path built its context off the
// main thread and is deprecated/fragile on iOS 17/18/26 real devices — the layer's drawable stayed
// invalid so every decoded frame was silently dropped and the remote peer's video (e.g. from Android)
// showed permanently black. AVSampleBufferDisplayLayer is the modern, robust display path.

+ (Class)layerClass
{
    return [AVSampleBufferDisplayLayer class];
}

- (AVSampleBufferDisplayLayer *)displayLayer
{
    return (AVSampleBufferDisplayLayer *)self.layer;
}

+ (instancetype)view
{
    OCTVideoView *videoView = [[self alloc] initWithFrame:CGRectZero];
    [videoView finishInitializing];
    return videoView;
}

- (instancetype)initWithFrame:(CGRect)frame
{
    self = [super initWithFrame:frame];
    if (self) {
        self.backgroundColor = [UIColor blackColor];
        self.displayLayer.videoGravity = AVLayerVideoGravityResizeAspect;
    }
    return self;
}

- (void)finishInitializing
{
    // Nothing extra needed — the display layer is configured in initWithFrame:.
}

- (void)dealloc
{
    OCTLogVerbose(@"dealloc");
}

- (void)enqueuePixelBuffer:(CVPixelBufferRef)pixelBuffer
{
    if (! pixelBuffer) {
        return;
    }

    CMVideoFormatDescriptionRef formatDescription = NULL;
    if (CMVideoFormatDescriptionCreateForImageBuffer(kCFAllocatorDefault, pixelBuffer, &formatDescription) != noErr) {
        return;
    }

    // Display each frame immediately (no A/V sync clock needed for a live call feed).
    CMSampleTimingInfo timing = kCMTimingInfoInvalid;
    timing.duration = kCMTimeInvalid;
    timing.decodeTimeStamp = kCMTimeInvalid;
    timing.presentationTimeStamp = kCMTimeInvalid;

    CMSampleBufferRef sampleBuffer = NULL;
    OSStatus status = CMSampleBufferCreateReadyWithImageBuffer(kCFAllocatorDefault, pixelBuffer,
                                                               formatDescription, &timing, &sampleBuffer);
    CFRelease(formatDescription);

    if (status != noErr || ! sampleBuffer) {
        return;
    }

    // Mark for immediate display.
    CFArrayRef attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, YES);
    if (attachments && CFArrayGetCount(attachments) > 0) {
        CFMutableDictionaryRef dict = (CFMutableDictionaryRef)CFArrayGetValueAtIndex(attachments, 0);
        CFDictionarySetValue(dict, kCMSampleAttachmentKey_DisplayImmediately, kCFBooleanTrue);
    }

    AVSampleBufferDisplayLayer *layer = self.displayLayer;
    if (layer.status == AVQueuedSampleBufferRenderingStatusFailed) {
        [layer flush];
    }
    [layer enqueueSampleBuffer:sampleBuffer];
    CFRelease(sampleBuffer);
}

// setImage is unused on iOS now (frames go through enqueuePixelBuffer:); keep the property setter a no-op
// so any legacy caller doesn't crash.
- (void)setImage:(CIImage *)image
{
    _image = image;
}

#else

+ (instancetype)view
{
    OCTVideoView *videoView = [[self alloc] initWithFrame:CGRectZero pixelFormat:[self defaultPixelFormat]];
    [videoView finishInitializing];
    return videoView;
}

- (void)finishInitializing
{
}

- (void)dealloc
{
    OCTLogVerbose(@"dealloc");
}

- (void)setImage:(CIImage *)image
{
    _image = image;
    [self setNeedsDisplay:YES];
}

// OS X: we need to correct the viewport when the view size changes
- (void)reshape
{
    glViewport(0, 0, self.bounds.size.width, self.bounds.size.height);
}

- (void)drawRect:(CGRect)rect
{
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
}

#endif

@end
