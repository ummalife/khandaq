// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcGroupLiveAudio.h"
#import "OCTLogging.h"

#include <string.h>

@import AVFoundation;

#include "toxav.h"
#include "tox.h"

static const uint8_t kOCTNgcMagic[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35};
static const size_t kOCTNgcAudioHeaderLength = 10;
static const uint8_t kOCTNgcPktAudio = 0x31;
static const int32_t kOCTNgcAudioBitrate = 64000;
static const int32_t kOCTNgcAudioSampleRate = 48000;
static const int32_t kOCTNgcAudioChannels = 1;
static const int32_t kOCTNgcAudioFrameSamples = 5760;

@interface OCTNgcGroupLiveAudio ()
@property (nonatomic, copy) OCTNgcGroupLiveAudioSendPacketBlock sendPacketBlock;
@property (nonatomic, assign) void *audioCoder;
@property (nonatomic, strong, nullable) AVAudioEngine *audioEngine;
@property (nonatomic, strong, nullable) AVAudioPlayerNode *playerNode;
@property (nonatomic, assign) uint32_t captureGroupNumber;
@property (nonatomic, assign) BOOL captureActive;
@property (nonatomic, strong) dispatch_queue_t audioQueue;
@property (nonatomic, strong) NSMutableData *capturePcmBuffer;
@property (nonatomic, strong, nullable) AVAudioConverter *captureConverter;
@property (nonatomic, strong, nullable) AVAudioFormat *captureTargetFormat;
@end

@implementation OCTNgcGroupLiveAudio

- (instancetype)initWithSendPacketBlock:(OCTNgcGroupLiveAudioSendPacketBlock)sendPacketBlock
{
    NSParameterAssert(sendPacketBlock);

    self = [super init];

    if (! self) {
        return nil;
    }

    _sendPacketBlock = [sendPacketBlock copy];
    _audioQueue = dispatch_queue_create("khandaq.ngc.liveaudio", DISPATCH_QUEUE_SERIAL);
    _capturePcmBuffer = [NSMutableData data];
    _audioCoder = toxav_ngc_audio_init(kOCTNgcAudioBitrate, kOCTNgcAudioSampleRate, kOCTNgcAudioChannels);

    if (! _audioCoder) {
        OCTLogWarn(@"NGC live audio codec init failed");
    }

    return self;
}

- (void)dealloc
{
    [self stopLiveCapture];

    if (_audioCoder) {
        toxav_ngc_audio_kill(_audioCoder);
        _audioCoder = NULL;
    }
}

- (BOOL)isLiveCaptureActive
{
    return _captureActive;
}

- (void)prepareIncomingPlayback
{
    [self ensurePlaybackEngineStarted];
}

- (BOOL)matchesNgcMagic:(const uint8_t *)bytes length:(NSUInteger)length
{
    if (length < kOCTNgcAudioHeaderLength + 1) {
        return NO;
    }

    return memcmp(bytes, kOCTNgcMagic, sizeof(kOCTNgcMagic)) == 0;
}

- (BOOL)handleIncomingPacketWithGroupNumber:(uint32_t)groupNumber
                                     peerId:(uint32_t)peerId
                                       data:(NSData *)data
{
    (void)peerId;

    const uint8_t *bytes = data.bytes;
    const NSUInteger length = data.length;

    if (! [self matchesNgcMagic:bytes length:length]) {
        return NO;
    }

    if (bytes[6] != 0x01 || bytes[7] != kOCTNgcPktAudio) {
        return NO;
    }

    if (length <= kOCTNgcAudioHeaderLength) {
        return YES;
    }

    const uint32_t payloadLength = (uint32_t)(length - kOCTNgcAudioHeaderLength);
    const uint8_t *payload = bytes + kOCTNgcAudioHeaderLength;

    if (! _audioCoder) {
        return YES;
    }

    int16_t pcm[kOCTNgcAudioFrameSamples];
    const int32_t samples = toxav_ngc_audio_decode(_audioCoder, payload, payloadLength, pcm);

    if (samples <= 0) {
        return YES;
    }

    [self schedulePlaybackPcm:pcm sampleCount:(NSUInteger)samples groupNumber:groupNumber];
    return YES;
}

- (void)schedulePlaybackPcm:(const int16_t *)pcm sampleCount:(NSUInteger)sampleCount groupNumber:(uint32_t)groupNumber
{
    (void)groupNumber;

    if (sampleCount == 0) {
        return;
    }

    AVAudioFormat *format = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:kOCTNgcAudioSampleRate channels:1];
    AVAudioPCMBuffer *buffer = [[AVAudioPCMBuffer alloc] initWithPCMFormat:format frameCapacity:(AVAudioFrameCount)sampleCount];

    if (! buffer) {
        return;
    }

    buffer.frameLength = (AVAudioFrameCount)sampleCount;
    memcpy(buffer.int16ChannelData[0], pcm, sampleCount * sizeof(int16_t));

    dispatch_async(dispatch_get_main_queue(), ^{
        [self ensurePlaybackEngineStarted];
        [self.playerNode scheduleBuffer:buffer completionHandler:nil];

        if (! self.playerNode.isPlaying) {
            [self.playerNode play];
        }
    });
}

- (void)ensurePlaybackEngineStarted
{
    if (self.audioEngine) {
        return;
    }

    self.audioEngine = [[AVAudioEngine alloc] init];
    self.playerNode = [[AVAudioPlayerNode alloc] init];
    [self.audioEngine attachNode:self.playerNode];

    AVAudioFormat *format = [[AVAudioFormat alloc] initStandardFormatWithSampleRate:kOCTNgcAudioSampleRate channels:1];
    [self.audioEngine connect:self.playerNode to:self.audioEngine.mainMixerNode format:format];

    NSError *error = nil;
    AVAudioSession *session = [AVAudioSession sharedInstance];
    [session setCategory:AVAudioSessionCategoryPlayAndRecord
             withOptions:AVAudioSessionCategoryOptionDefaultToSpeaker | AVAudioSessionCategoryOptionAllowBluetooth
                   error:&error];
    [session setActive:YES error:&error];

    [self.audioEngine prepare];
    [self.audioEngine startAndReturnError:&error];

    if (error) {
        OCTLogWarn(@"NGC live audio playback start failed: %@", error);
    }
}

- (BOOL)startLiveCaptureForGroupNumber:(uint32_t)groupNumber error:(NSError **)error
{
    if (self.captureActive) {
        return YES;
    }

    if (! _audioCoder) {
        if (error) {
            *error = [NSError errorWithDomain:@"OCTNgcGroupLiveAudio"
                                         code:4
                                     userInfo:@{NSLocalizedDescriptionKey: @"NGC live audio codec unavailable"}];
        }
        return NO;
    }

    AVAudioSession *session = [AVAudioSession sharedInstance];

    if ([session recordPermission] != AVAudioSessionRecordPermissionGranted) {
        if (error) {
            *error = [NSError errorWithDomain:@"OCTNgcGroupLiveAudio"
                                           code:1
                                       userInfo:@{NSLocalizedDescriptionKey: @"Microphone permission not granted"}];
        }
        return NO;
    }

#if TARGET_OS_SIMULATOR
    if (error) {
        *error = [NSError errorWithDomain:@"OCTNgcGroupLiveAudio"
                                     code:5
                                 userInfo:@{NSLocalizedDescriptionKey: @"Microphone capture is not available in the simulator"}];
    }
    return NO;
#endif

    [self stopLiveCapture];
    @synchronized (self) {
        [self.capturePcmBuffer setLength:0]; // KHANDAQ (data race): guard vs. the input-tap thread's drain
    }

    NSError *sessionError = nil;
    [session setCategory:AVAudioSessionCategoryPlayAndRecord
             withOptions:AVAudioSessionCategoryOptionDefaultToSpeaker | AVAudioSessionCategoryOptionAllowBluetooth
                   error:&sessionError];
    [session setActive:YES error:&sessionError];

    if (sessionError) {
        OCTLogWarn(@"NGC live audio session activation failed: %@", sessionError);
    }

    if (self.audioEngine.isRunning) {
        [self.audioEngine stop];
    }
    [self removeInputTapIfNeeded];

    if (! self.audioEngine) {
        [self ensurePlaybackEngineStarted];
    }

    if (! self.audioEngine) {
        if (error) {
            *error = [NSError errorWithDomain:@"OCTNgcGroupLiveAudio"
                                         code:3
                                     userInfo:@{NSLocalizedDescriptionKey: @"Audio engine unavailable"}];
        }
        return NO;
    }

    if (self.playerNode.isPlaying) {
        [self.playerNode stop];
    }

    AVAudioInputNode *input = self.audioEngine.inputNode;
    AVAudioFormat *inputFormat = [input inputFormatForBus:0];

    if (! inputFormat || inputFormat.sampleRate <= 0 || inputFormat.channelCount == 0) {
        if (error) {
            *error = [NSError errorWithDomain:@"OCTNgcGroupLiveAudio"
                                         code:3
                                     userInfo:@{NSLocalizedDescriptionKey: @"Microphone input unavailable"}];
        }
        return NO;
    }

    self.captureTargetFormat = [[AVAudioFormat alloc] initWithCommonFormat:AVAudioPCMFormatInt16
                                                                sampleRate:kOCTNgcAudioSampleRate
                                                                  channels:1
                                                               interleaved:YES];
    self.captureConverter = [[AVAudioConverter alloc] initFromFormat:inputFormat toFormat:self.captureTargetFormat];

    if (! self.captureConverter) {
        if (error) {
            *error = [NSError errorWithDomain:@"OCTNgcGroupLiveAudio"
                                         code:2
                                     userInfo:@{NSLocalizedDescriptionKey: @"Could not create audio converter"}];
        }
        self.captureConverter = nil;
        self.captureTargetFormat = nil;
        return NO;
    }

    @try {
        __weak typeof(self) weakSelf = self;
        [input installTapOnBus:0 bufferSize:4096 format:inputFormat block:^(AVAudioPCMBuffer *buffer, AVAudioTime *when) {
            (void)when;
            __strong typeof(weakSelf) self = weakSelf;

            if (! self || ! self.captureActive) {
                return;
            }

            [self processCaptureBuffer:buffer];
        }];
    }
    @catch (NSException *exception) {
        OCTLogWarn(@"NGC live audio tap install failed: %@", exception);
        self.captureConverter = nil;
        self.captureTargetFormat = nil;

        if (error) {
            *error = [NSError errorWithDomain:@"OCTNgcGroupLiveAudio"
                                         code:3
                                     userInfo:@{NSLocalizedDescriptionKey: exception.reason ?: @"Could not start microphone capture"}];
        }
        return NO;
    }

    if (! self.audioEngine.isRunning) {
        [self.audioEngine prepare];
        if (! [self.audioEngine startAndReturnError:&sessionError]) {
            [self removeInputTapIfNeeded];
            self.captureConverter = nil;
            self.captureTargetFormat = nil;

            if (error) {
                *error = sessionError ?: [NSError errorWithDomain:@"OCTNgcGroupLiveAudio"
                                                             code:3
                                                         userInfo:@{NSLocalizedDescriptionKey: @"Could not start audio engine"}];
            }
            return NO;
        }
    }

    self.captureGroupNumber = groupNumber;
    self.captureActive = YES;
    return YES;
}

- (void)processCaptureBuffer:(AVAudioPCMBuffer *)buffer
{
    if (buffer.frameLength == 0 || ! self.captureConverter || ! self.captureTargetFormat) {
        return;
    }

    const double ratio = self.captureTargetFormat.sampleRate / buffer.format.sampleRate;
    const AVAudioFrameCount outputCapacity = (AVAudioFrameCount)ceil((double)buffer.frameLength * ratio) + 64;
    AVAudioPCMBuffer *converted = [[AVAudioPCMBuffer alloc] initWithPCMFormat:self.captureTargetFormat
                                                                 frameCapacity:outputCapacity];

    if (! converted) {
        return;
    }

    NSError *convertError = nil;
    __block BOOL inputConsumed = NO;

    [self.captureConverter convertToBuffer:converted
                                     error:&convertError
                        withInputFromBlock:^AVAudioBuffer *(AVAudioPacketCount inNumberOfPackets, AVAudioConverterInputStatus *outStatus) {
        if (inputConsumed) {
            *outStatus = AVAudioConverterInputStatus_NoDataNow;
            return nil;
        }

        inputConsumed = YES;
        *outStatus = AVAudioConverterInputStatus_HaveData;
        return buffer;
    }];

    if (convertError || converted.frameLength == 0 || ! converted.int16ChannelData) {
        return;
    }

    const NSUInteger byteCount = converted.frameLength * sizeof(int16_t);
    const NSUInteger frameBytes = (NSUInteger)kOCTNgcAudioFrameSamples * sizeof(int16_t);
    const uint32_t groupNumber = self.captureGroupNumber;

    // KHANDAQ (data race): NSMutableData is not thread-safe. This runs on the AVAudioEngine input-tap
    // thread while startLiveCapture/stopLiveCapture clear the same buffer on the main thread — concurrent
    // mutation corrupted the backing store (crash on call teardown). Append + drain under a lock, then
    // encode/send OUTSIDE the lock so the real-time tap thread never blocks on toxav.
    NSMutableArray<NSData *> *frames = nil;
    @synchronized (self) {
        [self.capturePcmBuffer appendBytes:converted.int16ChannelData[0] length:byteCount];
        while (self.capturePcmBuffer.length >= frameBytes) {
            if (! frames) {
                frames = [NSMutableArray array];
            }
            [frames addObject:[self.capturePcmBuffer subdataWithRange:NSMakeRange(0, frameBytes)]];
            [self.capturePcmBuffer replaceBytesInRange:NSMakeRange(0, frameBytes) withBytes:NULL length:0];
        }
    }
    for (NSData *frame in frames) {
        [self sendEncodedFrame:(const int16_t *)frame.bytes groupNumber:groupNumber];
    }
}

- (void)sendEncodedFrame:(const int16_t *)pcm groupNumber:(uint32_t)groupNumber
{
    if (! _audioCoder || ! self.sendPacketBlock) {
        return;
    }

    uint8_t encoded[TOX_MAX_CUSTOM_PACKET_SIZE];
    uint32_t encodedSize = 0;

    if (! toxav_ngc_audio_encode(_audioCoder, pcm, kOCTNgcAudioFrameSamples, encoded, &encodedSize)) {
        return;
    }

    if (encodedSize < 1) {
        return;
    }

    const size_t packetLength = kOCTNgcAudioHeaderLength + encodedSize;
    NSMutableData *packet = [NSMutableData dataWithLength:packetLength];
    uint8_t *out = packet.mutableBytes;

    memcpy(out, kOCTNgcMagic, sizeof(kOCTNgcMagic));
    out[6] = 0x01;
    out[7] = kOCTNgcPktAudio;
    out[8] = 1;
    out[9] = 48;
    memcpy(out + kOCTNgcAudioHeaderLength, encoded, encodedSize);

    dispatch_async(self.audioQueue, ^{
        NSError *sendError = nil;
        const BOOL lossless = packetLength >= TOX_MAX_CUSTOM_PACKET_SIZE;
        self.sendPacketBlock(groupNumber, packet, lossless, &sendError);

        if (sendError) {
            OCTLogWarn(@"NGC live audio send failed: %@", sendError);
        }
    });
}

- (void)removeInputTapIfNeeded
{
    AVAudioEngine *engine = self.audioEngine;

    if (! engine) {
        return;
    }

    @try {
        [engine.inputNode removeTapOnBus:0];
    }
    @catch (__unused NSException *exception) {
    }
}

- (void)stopLiveCapture
{
    self.captureActive = NO;
    self.captureGroupNumber = 0;
    self.captureConverter = nil;
    self.captureTargetFormat = nil;
    @synchronized (self) {
        [self.capturePcmBuffer setLength:0]; // KHANDAQ (data race): guard vs. the input-tap thread's drain
    }
    [self removeInputTapIfNeeded];

    if (self.audioEngine.isRunning) {
        [self.audioEngine stop];
    }
}

@end
