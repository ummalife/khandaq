// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcGroupLiveVideo.h"
#import "OCTLogging.h"

#include <string.h>

@import AVFoundation;
@import CoreImage;
@import QuartzCore;

#include "toxav.h"
#include "tox.h"

static const uint8_t kOCTNgcMagic[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35};
static const size_t kOCTNgcVideoHeaderLength = 14;
static const uint8_t kOCTNgcPktVideo = 0x21;
static const uint16_t kOCTNgcVideoWidth = 480;
static const uint16_t kOCTNgcVideoHeight = 640;
static const uint16_t kOCTNgcDefaultVideoBitrate = 90;
static const uint16_t kOCTNgcHighVideoBitrate = 400;
static const uint16_t kOCTNgcDefaultMaxQuantizer = 51;
static const uint16_t kOCTNgcHighVideoMaxQuantizer = 38;
static const NSTimeInterval kOCTNgcVideoFrameIntervalSec = 0.12;
static const CFTimeInterval kOCTNgcRemoteFrameTimeoutSec = 5.0;

@interface OCTNgcGroupLiveVideo () <AVCaptureVideoDataOutputSampleBufferDelegate>
@property (nonatomic, copy) OCTNgcGroupLiveVideoSendPacketBlock sendPacketBlock;
@property (nonatomic, copy, nullable) OCTNgcGroupLiveVideoFrameBlock remoteFrameBlock;
@property (nonatomic, copy, nullable) OCTNgcGroupLiveVideoFrameBlock localFrameBlock;
@property (nonatomic, copy, nullable) OCTNgcGroupLiveVideoActivityBlock incomingVideoActivityBlock;
@property (nonatomic, assign) void *videoCoder;
@property (nonatomic, assign) uint32_t captureGroupNumber;
@property (nonatomic, assign) BOOL captureActive;
@property (nonatomic, assign) uint16_t sequenceNumber;
@property (nonatomic, assign) uint16_t videoBitrate;
@property (nonatomic, assign) uint16_t maxQuantizer;
@property (nonatomic, strong) dispatch_queue_t videoQueue;
@property (nonatomic, strong, nullable) AVCaptureSession *captureSession;
@property (nonatomic, strong, nullable) AVCaptureVideoDataOutput *videoOutput;
@property (nonatomic, strong, nullable) dispatch_source_t sendTimer;
@property (nonatomic, strong) NSLock *frameLock;
@property (nonatomic, strong) NSMutableData *pendingY;
@property (nonatomic, strong) NSMutableData *pendingU;
@property (nonatomic, strong) NSMutableData *pendingV;
@property (nonatomic, assign) BOOL hasPendingFrame;
@property (nonatomic, copy, nullable) NSString *showingPeerPublicKeyHex;
@property (nonatomic, assign) uint32_t showingPeerId;
@property (nonatomic, assign) uint8_t flushDecoder;
@property (nonatomic, assign) CFTimeInterval lastRemoteFrameTimestamp;
@property (nonatomic, strong) NSMutableDictionary<NSNumber *, NSNumber *> *lastIncomingPacketTimestampByGroup;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *incomingPeerLastSeen;
@end

@implementation OCTNgcGroupLiveVideo

- (instancetype)initWithSendPacketBlock:(OCTNgcGroupLiveVideoSendPacketBlock)sendPacketBlock
{
    NSParameterAssert(sendPacketBlock);

    self = [super init];

    if (! self) {
        return nil;
    }

    _sendPacketBlock = [sendPacketBlock copy];
    _videoQueue = dispatch_queue_create("khandaq.ngc.livevideo", DISPATCH_QUEUE_SERIAL);
    _frameLock = [[NSLock alloc] init];
    _pendingY = [NSMutableData dataWithLength:(NSUInteger)kOCTNgcVideoWidth * kOCTNgcVideoHeight];
    _pendingU = [NSMutableData dataWithLength:(NSUInteger)kOCTNgcVideoWidth * kOCTNgcVideoHeight / 4];
    _pendingV = [NSMutableData dataWithLength:(NSUInteger)kOCTNgcVideoWidth * kOCTNgcVideoHeight / 4];
    _videoBitrate = kOCTNgcDefaultVideoBitrate;
    _maxQuantizer = kOCTNgcDefaultMaxQuantizer;
    _videoCoder = toxav_ngc_video_init(_videoBitrate, _maxQuantizer);
    _showingPeerId = UINT32_MAX;
    _lastRemoteFrameTimestamp = -1;
    _lastIncomingPacketTimestampByGroup = [NSMutableDictionary dictionary];
    _incomingPeerLastSeen = [NSMutableDictionary dictionary];

    return self;
}

- (void)dealloc
{
    [self stopLiveCapture];

    if (_videoCoder) {
        toxav_ngc_video_kill(_videoCoder);
        _videoCoder = NULL;
    }
}

- (BOOL)isLiveCaptureActive
{
    return _captureActive;
}

- (uint32_t)captureGroupNumber
{
    return _captureGroupNumber;
}

- (BOOL)matchesNgcMagic:(const uint8_t *)bytes length:(NSUInteger)length
{
    if (length < kOCTNgcVideoHeaderLength + 1) {
        return NO;
    }

    return memcmp(bytes, kOCTNgcMagic, sizeof(kOCTNgcMagic)) == 0;
}

- (uint8_t)calcCRC8:(const uint8_t *)data length:(NSUInteger)length
{
    const uint8_t polynomial = 0x9c;
    uint8_t crc = 0xFF;

    for (NSUInteger i = 0; i < length; i++) {
        crc ^= data[i];

        for (int j = 0; j < 8; j++) {
            if (crc & 0x01) {
                crc = (uint8_t)((crc >> 1) ^ polynomial);
            } else {
                crc = (uint8_t)(crc >> 1);
            }
        }
    }

    return crc;
}

- (NSString *)incomingPeerKeyForGroupNumber:(uint32_t)groupNumber peerPublicKeyHex:(NSString *)peerPublicKeyHex
{
    return [NSString stringWithFormat:@"%u:%@", groupNumber, peerPublicKeyHex ?: @""];
}

- (void)pruneIncomingPeersOlderThan:(NSTimeInterval)seconds
{
    const CFTimeInterval now = CACurrentMediaTime();
    NSMutableArray<NSString *> *staleKeys = [NSMutableArray array];

    // KHANDAQ (data race): these dictionaries are written on the tox callback queue and read on the
    // main thread — serialize every access on the stable incomingPeerLastSeen token (allocated once in
    // init). @synchronized is re-entrant, so the note->prune nesting below is safe.
    @synchronized (self.incomingPeerLastSeen) {
        [self.incomingPeerLastSeen enumerateKeysAndObjectsUsingBlock:^(NSString *key, NSNumber *timestamp, BOOL *stop) {
            (void)stop;

            if ((now - timestamp.doubleValue) >= seconds) {
                [staleKeys addObject:key];
            }
        }];

        for (NSString *key in staleKeys) {
            [self.incomingPeerLastSeen removeObjectForKey:key];
        }
    }
}

- (void)noteIncomingVideoPacketForGroupNumber:(uint32_t)groupNumber
                          peerPublicKeyHex:(NSString *)peerPublicKeyHex
{
    const CFTimeInterval now = CACurrentMediaTime();

    @synchronized (self.incomingPeerLastSeen) {
        self.lastIncomingPacketTimestampByGroup[@(groupNumber)] = @(now);

        if (peerPublicKeyHex.length > 0) {
            self.incomingPeerLastSeen[[self incomingPeerKeyForGroupNumber:groupNumber peerPublicKeyHex:peerPublicKeyHex]] = @(now);
            [self pruneIncomingPeersOlderThan:5.0];
        }
    }

    // never invoke an out-of-module block while holding the lock
    if (self.incomingVideoActivityBlock) {
        self.incomingVideoActivityBlock(groupNumber);
    }
}

- (void)setIncomingVideoActivityBlock:(OCTNgcGroupLiveVideoActivityBlock)block
{
    _incomingVideoActivityBlock = [block copy];
}

- (BOOL)hasRecentIncomingVideoForGroupNumber:(uint32_t)groupNumber withinSeconds:(NSTimeInterval)seconds
{
    NSNumber *timestamp;
    @synchronized (self.incomingPeerLastSeen) {
        timestamp = self.lastIncomingPacketTimestampByGroup[@(groupNumber)];
    }

    if (! timestamp) {
        return NO;
    }

    return (CACurrentMediaTime() - timestamp.doubleValue) < seconds;
}

- (NSUInteger)recentIncomingVideoPeerCountForGroupNumber:(uint32_t)groupNumber withinSeconds:(NSTimeInterval)seconds
{
    const CFTimeInterval now = CACurrentMediaTime();
    const NSString *prefix = [NSString stringWithFormat:@"%u:", groupNumber];
    NSUInteger count = 0;

    @synchronized (self.incomingPeerLastSeen) {
        for (NSString *key in self.incomingPeerLastSeen) {
            NSNumber *timestamp = self.incomingPeerLastSeen[key];

            if ([key hasPrefix:prefix] && (now - timestamp.doubleValue) < seconds) {
                count++;
            }
        }
    }

    return count;
}

- (NSString *)primaryRecentIncomingVideoPeerPublicKeyHexForGroupNumber:(uint32_t)groupNumber
                                                         withinSeconds:(NSTimeInterval)seconds
{
    const CFTimeInterval now = CACurrentMediaTime();
    const NSString *prefix = [NSString stringWithFormat:@"%u:", groupNumber];
    NSString *bestKey = nil;
    CFTimeInterval bestTimestamp = 0;

    @synchronized (self.incomingPeerLastSeen) {
        for (NSString *key in self.incomingPeerLastSeen) {
            if (! [key hasPrefix:prefix]) {
                continue;
            }

            const CFTimeInterval seenAt = self.incomingPeerLastSeen[key].doubleValue;

            if ((now - seenAt) >= seconds) {
                continue;
            }

            if (! bestKey || seenAt >= bestTimestamp) {
                bestKey = key;
                bestTimestamp = seenAt;
            }
        }
    }

    if (! bestKey) {
        return nil;
    }

    return [bestKey substringFromIndex:prefix.length];
}

- (UIImage *)imageFromY:(const uint8_t *)y u:(const uint8_t *)u v:(const uint8_t *)v
                 width:(NSUInteger)width height:(NSUInteger)height yStride:(int32_t)yStride
{
    if (! y || ! u || ! v || width == 0 || height == 0) {
        return nil;
    }

    const size_t rgbBytes = width * height * 4;
    uint8_t *rgb = malloc(rgbBytes);

    if (! rgb) {
        return nil;
    }

    for (NSUInteger row = 0; row < height; row++) {
        for (NSUInteger col = 0; col < width; col++) {
            // KHANDAQ (audit #1): the Y plane is packed TIGHTLY at `width` by the native decode
            // callback, but yStride is the decoder's PADDED source stride (e.g. 512 for a 480-wide
            // plane). Indexing with yStride over a width-packed buffer reads out of bounds every frame
            // (and skews the image). Index with the packed width; u/v already use width/2 below.
            const int32_t yIndex = (int32_t)row * (int32_t)width + (int32_t)col;
            const int32_t uvRow = (int32_t)(row / 2);
            const int32_t uvCol = (int32_t)(col / 2);
            const int32_t uvIndex = uvRow * (int32_t)(width / 2) + uvCol;
            const int32_t Y = y[yIndex];
            const int32_t U = u[uvIndex] - 128;
            const int32_t V = v[uvIndex] - 128;
            const int32_t R = Y + ((1436 * V) >> 10);
            const int32_t G = Y - ((352 * U + 731 * V) >> 10);
            const int32_t B = Y + ((1814 * U) >> 10);
            const size_t out = (row * width + col) * 4;

            rgb[out + 0] = (uint8_t)(R < 0 ? 0 : (R > 255 ? 255 : R));
            rgb[out + 1] = (uint8_t)(G < 0 ? 0 : (G > 255 ? 255 : G));
            rgb[out + 2] = (uint8_t)(B < 0 ? 0 : (B > 255 ? 255 : B));
            rgb[out + 3] = 255;
        }
    }

    CGColorSpaceRef colorSpace = CGColorSpaceCreateDeviceRGB();
    CGContextRef context = CGBitmapContextCreate(rgb, width, height, 8, width * 4, colorSpace,
                                                 kCGImageAlphaPremultipliedLast | kCGBitmapByteOrderDefault);
    CGColorSpaceRelease(colorSpace);

    if (! context) {
        free(rgb);
        return nil;
    }

    CGImageRef cgImage = CGBitmapContextCreateImage(context);
    CGContextRelease(context);
    free(rgb);

    if (! cgImage) {
        return nil;
    }

    UIImage *image = [UIImage imageWithCGImage:cgImage];
    CGImageRelease(cgImage);
    return image;
}

- (void)storeI420FromPixelBuffer:(CVPixelBufferRef)pixelBuffer
{
    CVPixelBufferLockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);

    const uint8_t *srcY = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0);
    const size_t srcYStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0);
    uint8_t *dstY = self.pendingY.mutableBytes;

    for (uint16_t row = 0; row < kOCTNgcVideoHeight; row++) {
        memcpy(dstY + row * kOCTNgcVideoWidth, srcY + row * srcYStride, kOCTNgcVideoWidth);
    }

    uint8_t *dstU = self.pendingU.mutableBytes;
    uint8_t *dstV = self.pendingV.mutableBytes;

    if (CVPixelBufferGetPlaneCount(pixelBuffer) >= 3) {
        const uint8_t *srcU = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1);
        const uint8_t *srcV = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 2);
        const size_t srcUStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1);
        const size_t srcVStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 2);
        const uint16_t chromaH = kOCTNgcVideoHeight / 2;
        const uint16_t chromaW = kOCTNgcVideoWidth / 2;

        for (uint16_t row = 0; row < chromaH; row++) {
            memcpy(dstU + row * chromaW, srcU + row * srcUStride, chromaW);
            memcpy(dstV + row * chromaW, srcV + row * srcVStride, chromaW);
        }
    } else {
        const uint8_t *srcUV = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 1);
        const size_t srcUVStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 1);
        const uint16_t chromaH = kOCTNgcVideoHeight / 2;
        const uint16_t chromaW = kOCTNgcVideoWidth / 2;

        for (uint16_t row = 0; row < chromaH; row++) {
            for (uint16_t col = 0; col < chromaW; col++) {
                dstU[row * chromaW + col] = srcUV[row * srcUVStride + col * 2];
                dstV[row * chromaW + col] = srcUV[row * srcUVStride + col * 2 + 1];
            }
        }
    }

    CVPixelBufferUnlockBaseAddress(pixelBuffer, kCVPixelBufferLock_ReadOnly);
    self.hasPendingFrame = YES;
}

- (CVPixelBufferRef)scaledPixelBufferFromSampleBuffer:(CMSampleBufferRef)sampleBuffer
{
    CVImageBufferRef imageBuffer = CMSampleBufferGetImageBuffer(sampleBuffer);

    if (! imageBuffer) {
        return NULL;
    }

    CVPixelBufferRef output = NULL;
    const OSType format = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange;
    NSDictionary *attrs = @{(__bridge NSString *)kCVPixelBufferIOSurfacePropertiesKey: @{}};

    if (CVPixelBufferCreate(kCFAllocatorDefault, kOCTNgcVideoWidth, kOCTNgcVideoHeight, format,
                            (__bridge CFDictionaryRef)attrs, &output) != kCVReturnSuccess) {
        return NULL;
    }

    CIImage *inputImage = [CIImage imageWithCVPixelBuffer:imageBuffer];
    CIFilter *scaleFilter = [CIFilter filterWithName:@"CILanczosScaleTransform"];

    if (! scaleFilter) {
        CVPixelBufferRelease(output);
        return NULL;
    }

    const CGFloat inputWidth = (CGFloat)CVPixelBufferGetWidth(imageBuffer);
    const CGFloat inputHeight = (CGFloat)CVPixelBufferGetHeight(imageBuffer);
    const CGFloat scale = MAX(kOCTNgcVideoWidth / inputWidth, kOCTNgcVideoHeight / inputHeight);

    [scaleFilter setValue:inputImage forKey:kCIInputImageKey];
    [scaleFilter setValue:@(scale) forKey:kCIInputScaleKey];
    [scaleFilter setValue:@(1.0) forKey:kCIInputAspectRatioKey];

    CIImage *scaled = scaleFilter.outputImage;

    if (! scaled) {
        CVPixelBufferRelease(output);
        return NULL;
    }

    const CGRect crop = CGRectMake((scaled.extent.size.width - kOCTNgcVideoWidth) / 2.0,
                                   (scaled.extent.size.height - kOCTNgcVideoHeight) / 2.0,
                                   kOCTNgcVideoWidth, kOCTNgcVideoHeight);
    CIImage *cropped = [scaled imageByCroppingToRect:crop];
    static CIContext *ciContext;
    static dispatch_once_t onceToken;

    dispatch_once(&onceToken, ^{
        ciContext = [CIContext contextWithOptions:nil];
    });

    [ciContext render:cropped toCVPixelBuffer:output];
    return output;
}

- (BOOL)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                       peerPublicKeyHex:(NSString *)peerPublicKeyHex
                                       data:(NSData *)data
{
    (void)groupNumber;

    const uint8_t *bytes = data.bytes;
    const NSUInteger length = data.length;

    if (! [self matchesNgcMagic:bytes length:length]) {
        return NO;
    }

    if (bytes[6] != 0x02 || bytes[7] != kOCTNgcPktVideo) {
        return NO;
    }

    [self noteIncomingVideoPacketForGroupNumber:groupNumber peerPublicKeyHex:peerPublicKeyHex];

    if (length <= kOCTNgcVideoHeaderLength || ! self.captureActive) {
        return YES;
    }

    if (self.showingPeerId == UINT32_MAX) {
        self.showingPeerId = peerId;
        self.showingPeerPublicKeyHex = [peerPublicKeyHex copy];
    } else if (peerId != self.showingPeerId) {
        return YES;
    }

    const uint32_t payloadLength = (uint32_t)(length - kOCTNgcVideoHeaderLength);
    const uint8_t *payload = bytes + kOCTNgcVideoHeaderLength;

    if (! _videoCoder || payloadLength < 1) {
        return YES;
    }

    const size_t yBytes = (size_t)kOCTNgcVideoWidth * kOCTNgcVideoHeight;
    const size_t uvBytes = yBytes / 4;
    uint8_t *yBuf = malloc(yBytes);
    uint8_t *uBuf = malloc(uvBytes);
    uint8_t *vBuf = malloc(uvBytes);

    if (! yBuf || ! uBuf || ! vBuf) {
        free(yBuf);
        free(uBuf);
        free(vBuf);
        return YES;
    }

    int32_t yStride = 0;
    int32_t uStride = 0;
    int32_t vStride = 0;
    const int decodeWidth = (int)kOCTNgcVideoWidth + 32;
    const bool decoded = toxav_ngc_video_decode(_videoCoder, (uint8_t *)payload, payloadLength,
                                                decodeWidth, kOCTNgcVideoHeight,
                                                yBuf, uBuf, vBuf,
                                                &yStride, &uStride, &vStride,
                                                self.flushDecoder);
    self.flushDecoder = 0;

    if (! decoded || yStride <= 0) {
        free(yBuf);
        free(uBuf);
        free(vBuf);
        return YES;
    }

    UIImage *frame = [self imageFromY:yBuf u:uBuf v:vBuf width:kOCTNgcVideoWidth height:kOCTNgcVideoHeight
                              yStride:yStride];
    free(yBuf);
    free(uBuf);
    free(vBuf);

    if (frame) {
        self.lastRemoteFrameTimestamp = CACurrentMediaTime();

        if (self.remoteFrameBlock) {
            dispatch_async(dispatch_get_main_queue(), ^{
                self.remoteFrameBlock(frame);
            });
        }
    }

    return YES;
}

- (void)checkRemoteFrameTimeout
{
    if (! self.captureActive || self.lastRemoteFrameTimestamp < 0) {
        return;
    }

    const CFTimeInterval now = CACurrentMediaTime();

    if ((now - self.lastRemoteFrameTimestamp) < kOCTNgcRemoteFrameTimeoutSec) {
        return;
    }

    self.lastRemoteFrameTimestamp = -1;
    self.showingPeerId = UINT32_MAX;
    self.showingPeerPublicKeyHex = nil;
    self.flushDecoder = 1;

    if (self.remoteFrameBlock) {
        dispatch_async(dispatch_get_main_queue(), ^{
            self.remoteFrameBlock(nil);
        });
    }
}

- (void)sendPendingFrame
{
    if (! self.captureActive || ! self.hasPendingFrame || ! _videoCoder || ! self.sendPacketBlock) {
        return;
    }

    [self.frameLock lock];
    const uint8_t *y = self.pendingY.bytes;
    const uint8_t *u = self.pendingU.bytes;
    const uint8_t *v = self.pendingV.bytes;
    uint8_t encoded[37000];
    uint32_t encodedSize = 0;

    const bool ok = toxav_ngc_video_encode(_videoCoder, self.videoBitrate, self.maxQuantizer,
                                           kOCTNgcVideoWidth, kOCTNgcVideoHeight,
                                           y, u, v, encoded, &encodedSize);
    [self.frameLock unlock];

    if (! ok || encodedSize < 1) {
        return;
    }

    const uint8_t crc = [self calcCRC8:encoded length:encodedSize];
    const size_t packetLength = kOCTNgcVideoHeaderLength + encodedSize;
    NSMutableData *packet = [NSMutableData dataWithLength:packetLength];
    uint8_t *out = packet.mutableBytes;

    memcpy(out, kOCTNgcMagic, sizeof(kOCTNgcMagic));
    out[6] = 0x02;
    out[7] = kOCTNgcPktVideo;
    out[8] = (uint8_t)kOCTNgcVideoWidth;
    out[9] = (uint8_t)kOCTNgcVideoHeight;
    out[10] = 0x01;
    out[11] = (uint8_t)(self.sequenceNumber & 0xFF);
    out[12] = (uint8_t)((self.sequenceNumber >> 8) & 0xFF);
    out[13] = crc;
    memcpy(out + kOCTNgcVideoHeaderLength, encoded, encodedSize);
    self.sequenceNumber++;

    dispatch_async(self.videoQueue, ^{
        NSError *sendError = nil;
        const BOOL lossless = packetLength >= TOX_MAX_CUSTOM_PACKET_SIZE;
        self.sendPacketBlock(self.captureGroupNumber, packet, lossless, &sendError);

        if (sendError) {
            OCTLogWarn(@"NGC live video send failed: %@", sendError);
        }
    });

    if (self.localFrameBlock) {
        UIImage *preview = [self imageFromY:self.pendingY.bytes
                                          u:self.pendingU.bytes
                                          v:self.pendingV.bytes
                                      width:kOCTNgcVideoWidth
                                     height:kOCTNgcVideoHeight
                                    yStride:kOCTNgcVideoWidth];

        if (preview) {
            dispatch_async(dispatch_get_main_queue(), ^{
                self.localFrameBlock(preview);
            });
        }
    }
}

- (void)startSendTimer
{
    if (self.sendTimer) {
        return;
    }

    self.sendTimer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, self.videoQueue);
    dispatch_source_set_timer(self.sendTimer,
                              dispatch_time(DISPATCH_TIME_NOW, (int64_t)(kOCTNgcVideoFrameIntervalSec * NSEC_PER_SEC)),
                              (uint64_t)(kOCTNgcVideoFrameIntervalSec * NSEC_PER_SEC),
                              (uint64_t)(0.01 * NSEC_PER_SEC));
    __weak typeof(self) weakSelf = self;

    dispatch_source_set_event_handler(self.sendTimer, ^{
        [weakSelf checkRemoteFrameTimeout];
        [weakSelf sendPendingFrame];
    });
    dispatch_resume(self.sendTimer);
}

- (void)stopSendTimer
{
    if (self.sendTimer) {
        dispatch_source_cancel(self.sendTimer);
        self.sendTimer = nil;
    }
}

- (BOOL)startLiveCaptureForGroupNumber:(uint32_t)groupNumber
                      remoteFrameBlock:(OCTNgcGroupLiveVideoFrameBlock)remoteFrameBlock
                       localFrameBlock:(OCTNgcGroupLiveVideoFrameBlock)localFrameBlock
                                 error:(NSError **)error
{
    if (self.captureActive) {
        return YES;
    }

#if TARGET_OS_SIMULATOR
    if (error) {
        *error = [NSError errorWithDomain:@"OCTNgcGroupLiveVideo"
                                     code:1
                                 userInfo:@{NSLocalizedDescriptionKey: @"NGC live video is not available on Simulator"}];
    }
    return NO;
#else
    AVAuthorizationStatus auth = [AVCaptureDevice authorizationStatusForMediaType:AVMediaTypeVideo];

    if (auth == AVAuthorizationStatusDenied || auth == AVAuthorizationStatusRestricted) {
        if (error) {
            *error = [NSError errorWithDomain:@"OCTNgcGroupLiveVideo"
                                         code:2
                                     userInfo:@{NSLocalizedDescriptionKey: @"Camera permission not granted"}];
        }
        return NO;
    }

    if (auth == AVAuthorizationStatusNotDetermined) {
        if (error) {
            *error = [NSError errorWithDomain:@"OCTNgcGroupLiveVideo"
                                         code:3
                                     userInfo:@{NSLocalizedDescriptionKey: @"Camera permission not determined"}];
        }
        return NO;
    }

    self.remoteFrameBlock = [remoteFrameBlock copy];
    self.localFrameBlock = [localFrameBlock copy];
    self.captureGroupNumber = groupNumber;
    self.captureActive = YES;
    self.sequenceNumber = 0;
    self.showingPeerId = UINT32_MAX;
    self.showingPeerPublicKeyHex = nil;
    self.flushDecoder = 1;
    self.hasPendingFrame = NO;
    self.lastRemoteFrameTimestamp = -1;

    NSError *sessionError = nil;
    [[AVAudioSession sharedInstance] setCategory:AVAudioSessionCategoryPlayAndRecord
                                     withOptions:AVAudioSessionCategoryOptionDefaultToSpeaker | AVAudioSessionCategoryOptionAllowBluetooth
                                           error:&sessionError];
    [[AVAudioSession sharedInstance] setActive:YES error:&sessionError];

    AVCaptureSession *session = [[AVCaptureSession alloc] init];
    session.sessionPreset = AVCaptureSessionPreset640x480;

    AVCaptureDevice *device = nil;

    if (@available(iOS 10.0, *)) {
        device = [AVCaptureDevice defaultDeviceWithDeviceType:AVCaptureDeviceTypeBuiltInWideAngleCamera
                                                    mediaType:AVMediaTypeVideo
                                                     position:AVCaptureDevicePositionFront];
    }

    if (! device) {
        device = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
    }

    if (! device) {
        if (error) {
            *error = [NSError errorWithDomain:@"OCTNgcGroupLiveVideo"
                                         code:4
                                     userInfo:@{NSLocalizedDescriptionKey: @"No camera available"}];
        }
        self.captureActive = NO;
        return NO;
    }

    NSError *inputError = nil;
    AVCaptureDeviceInput *input = [AVCaptureDeviceInput deviceInputWithDevice:device error:&inputError];

    if (! input) {
        if (error) {
            *error = inputError;
        }
        self.captureActive = NO;
        return NO;
    }

    if ([session canAddInput:input]) {
        [session addInput:input];
    }

    AVCaptureVideoDataOutput *output = [[AVCaptureVideoDataOutput alloc] init];
    output.alwaysDiscardsLateVideoFrames = YES;
    output.videoSettings = @{
        (NSString *)kCVPixelBufferPixelFormatTypeKey: @(kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange),
    };
    [output setSampleBufferDelegate:self queue:self.videoQueue];

    if ([session canAddOutput:output]) {
        [session addOutput:output];
    }

    AVCaptureConnection *connection = [output connectionWithMediaType:AVMediaTypeVideo];

    if (connection.isVideoOrientationSupported) {
        connection.videoOrientation = AVCaptureVideoOrientationPortrait;
    }

    if (connection.isVideoMirroringSupported) {
        connection.videoMirrored = YES;
    }

    self.captureSession = session;
    self.videoOutput = output;
    [session startRunning];
    [self startSendTimer];
    return YES;
#endif
}

- (void)captureOutput:(AVCaptureOutput *)output
didOutputSampleBuffer:(CMSampleBufferRef)sampleBuffer
       fromConnection:(AVCaptureConnection *)connection
{
    (void)output;
    (void)connection;

    if (! self.captureActive) {
        return;
    }

    CVPixelBufferRef scaled = [self scaledPixelBufferFromSampleBuffer:sampleBuffer];

    if (! scaled) {
        return;
    }

    [self.frameLock lock];
    [self storeI420FromPixelBuffer:scaled];
    [self.frameLock unlock];
    CVPixelBufferRelease(scaled);
}

- (void)stopLiveCapture
{
    self.captureActive = NO;
    self.captureGroupNumber = 0;
    self.remoteFrameBlock = nil;
    self.localFrameBlock = nil;
    self.showingPeerId = UINT32_MAX;
    self.showingPeerPublicKeyHex = nil;
    self.hasPendingFrame = NO;
    self.lastRemoteFrameTimestamp = -1;
    [self setHighQualityEnabled:NO];
    [self stopSendTimer];

    if (self.captureSession.isRunning) {
        [self.captureSession stopRunning];
    }

    self.captureSession = nil;
    self.videoOutput = nil;
}

- (void)setHighQualityEnabled:(BOOL)enabled
{
    if (enabled) {
        self.videoBitrate = kOCTNgcHighVideoBitrate;
        self.maxQuantizer = kOCTNgcHighVideoMaxQuantizer;
    } else {
        self.videoBitrate = kOCTNgcDefaultVideoBitrate;
        self.maxQuantizer = kOCTNgcDefaultMaxQuantizer;
    }
}

- (BOOL)isHighQualityEnabled
{
    return self.videoBitrate >= kOCTNgcHighVideoBitrate;
}

@end
