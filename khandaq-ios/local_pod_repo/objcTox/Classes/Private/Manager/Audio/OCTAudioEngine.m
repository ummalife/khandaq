// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTAudioEngine+Private.h"
#import "OCTToxAV+Private.h"
#import "OCTAudioQueue.h"
#import "OCTLogging.h"

@import AVFoundation;
@import AudioToolbox;

#if TARGET_OS_IPHONE

// KHANDAQ (#112/#166): call audio ran on software AudioQueues — no VoiceProcessingIO, so no echo
// cancellation and no system voice volume handling (calls were quiet and echoed). OCTVoiceUnitIO
// below is a VoiceProcessingIO-based replacement. The AudioQueue path stays as an automatic
// fallback (VPIO start failure) and as a user-visible escape hatch: set this NSUserDefaults key
// to YES (Settings → Advanced) to force the legacy engine.
NSString *const kOCTCallAudioVPIODisabledKey = @"KhandaqCallAudioVPIODisabled";

// toxav accepts only 8/12/16/24/48 kHz; the VPIO unit runs at the session rate (usually 48 kHz,
// but e.g. 44.1 kHz hardware or 16 kHz Bluetooth HFP happen) — resample mic chunks to 48 kHz
// whenever the unit rate is not toxav-legal.
static BOOL OCTIsToxLegalRate(double rate)
{
    return (rate == 8000.0) || (rate == 12000.0) || (rate == 16000.0) || (rate == 24000.0) || (rate == 48000.0);
}

// Plain linear interpolation; quality is fine for voice and it has no state/latency.
static int32_t OCTLinearResampleMono(const SInt16 *src, int32_t srcFrames, double srcRate,
                                     SInt16 *dst, int32_t dstCapacity, double dstRate)
{
    if ((srcFrames <= 0) || (srcRate <= 0) || (dstRate <= 0)) {
        return 0;
    }
    int32_t dstFrames = (int32_t)((double)srcFrames * dstRate / srcRate);
    if (dstFrames > dstCapacity) {
        dstFrames = dstCapacity;
    }
    for (int32_t i = 0; i < dstFrames; i++) {
        double srcPos = (double)i * srcRate / dstRate;
        int32_t idx = (int32_t)srcPos;
        double frac = srcPos - idx;
        SInt16 a = src[(idx < srcFrames) ? idx : (srcFrames - 1)];
        SInt16 b = src[(idx + 1 < srcFrames) ? (idx + 1) : (srcFrames - 1)];
        dst[i] = (SInt16)((1.0 - frac) * a + frac * b);
    }
    return dstFrames;
}

@interface OCTVoiceUnitIO : NSObject

@property (copy, nonatomic) void (^sendDataBlock)(void *, OCTToxAVSampleCount, OCTToxAVSampleRate, OCTToxAVChannels);
@property (assign, nonatomic, readonly) BOOL running;

- (BOOL)begin:(NSError **)error;
- (void)stop;
- (void)provideIncomingPCM:(const SInt16 *)pcm
               sampleCount:(OCTToxAVSampleCount)sampleCount
                  channels:(OCTToxAVChannels)channels
                sampleRate:(OCTToxAVSampleRate)sampleRate;

@end

@implementation OCTVoiceUnitIO
{
    AudioUnit _unit;
    TPCircularBuffer _micBuffer;      // mic (render thread) → tox chunks
    TPCircularBuffer _speakerBuffer;  // tox thread → speaker render callback
    double _unitRate;
    BOOL _unitCreated;
    SInt16 *_renderScratch;           // input-callback pull target
    SInt16 *_chunkScratch;            // 20ms chunk + resample staging
    int32_t _renderScratchFrames;
}

@synthesize running = _running;

static const int32_t kOCTVoiceScratchFrames = 8192;

- (instancetype)init
{
    self = [super init];
    if (! self) {
        return nil;
    }
    TPCircularBufferInit(&_micBuffer, kBufferLength);
    TPCircularBufferInit(&_speakerBuffer, kBufferLength);
    _renderScratchFrames = kOCTVoiceScratchFrames;
    _renderScratch = malloc(sizeof(SInt16) * kOCTVoiceScratchFrames);
    _chunkScratch = malloc(sizeof(SInt16) * kOCTVoiceScratchFrames);
    return self;
}

- (void)dealloc
{
    [self stop];
    TPCircularBufferCleanup(&_micBuffer);
    TPCircularBufferCleanup(&_speakerBuffer);
    free(_renderScratch);
    free(_chunkScratch);
}

// Speaker: pull PCM queued by provideIncomingPCM; zero-fill on underrun (silence, not garbage).
static OSStatus OCTVoiceRenderOutput(void *inRefCon,
                                     AudioUnitRenderActionFlags *ioActionFlags,
                                     const AudioTimeStamp *inTimeStamp,
                                     UInt32 inBusNumber,
                                     UInt32 inNumberFrames,
                                     AudioBufferList *ioData)
{
    OCTVoiceUnitIO *__unsafe_unretained io = (__bridge OCTVoiceUnitIO *)inRefCon;

    for (UInt32 b = 0; b < ioData->mNumberBuffers; b++) {
        uint8_t *target = ioData->mBuffers[b].mData;
        UInt32 wanted = ioData->mBuffers[b].mDataByteSize;

        int32_t availableBytes = 0;
        void *tail = TPCircularBufferTail(&io->_speakerBuffer, &availableBytes);

        UInt32 cpy = 0;
        if (tail && (availableBytes > 0)) {
            cpy = MIN((UInt32)availableBytes, wanted);
            memcpy(target, tail, cpy);
            TPCircularBufferConsume(&io->_speakerBuffer, (int32_t)cpy);
        }
        if (cpy < wanted) {
            memset(target + cpy, 0, wanted - cpy);
        }
    }

    return noErr;
}

// Mic: render the hardware input into scratch, buffer it, then hand 20ms chunks to toxav
// (resampled to 48 kHz when the unit runs at a rate toxav does not accept).
static OSStatus OCTVoiceInputAvailable(void *inRefCon,
                                       AudioUnitRenderActionFlags *ioActionFlags,
                                       const AudioTimeStamp *inTimeStamp,
                                       UInt32 inBusNumber,
                                       UInt32 inNumberFrames,
                                       AudioBufferList *ioData)
{
    OCTVoiceUnitIO *__unsafe_unretained io = (__bridge OCTVoiceUnitIO *)inRefCon;

    if (inNumberFrames > (UInt32)io->_renderScratchFrames) {
        return noErr;
    }

    AudioBufferList abl;
    abl.mNumberBuffers = 1;
    abl.mBuffers[0].mNumberChannels = 1;
    abl.mBuffers[0].mDataByteSize = inNumberFrames * sizeof(SInt16);
    abl.mBuffers[0].mData = io->_renderScratch;

    OSStatus status = AudioUnitRender(io->_unit, ioActionFlags, inTimeStamp, inBusNumber, inNumberFrames, &abl);
    if (status != noErr) {
        return status;
    }

    TPCircularBufferProduceBytes(&io->_micBuffer, abl.mBuffers[0].mData, abl.mBuffers[0].mDataByteSize);

    double rate = io->_unitRate;
    int32_t chunkFrames = (int32_t)(rate / 50.0);   // 20ms — a toxav-legal frame duration
    int32_t chunkBytes = chunkFrames * (int32_t)sizeof(SInt16);
    if (chunkBytes <= 0) {
        return noErr;
    }

    int32_t availableBytes = 0;
    SInt16 *tail = TPCircularBufferTail(&io->_micBuffer, &availableBytes);

    while (tail && (availableBytes >= chunkBytes)) {
        void (^send)(void *, OCTToxAVSampleCount, OCTToxAVSampleRate, OCTToxAVChannels) = io->_sendDataBlock;
        if (send) {
            if (OCTIsToxLegalRate(rate)) {
                send(tail, (OCTToxAVSampleCount)chunkFrames, (OCTToxAVSampleRate)rate, 1);
            }
            else {
                int32_t out = OCTLinearResampleMono(tail, chunkFrames, rate,
                                                    io->_chunkScratch, kOCTVoiceScratchFrames, 48000.0);
                if (out == 960) {
                    send(io->_chunkScratch, 960, 48000, 1);
                }
                else if (out > 0) {
                    // Rounded resample output — pad to the exact 20ms frame toxav expects.
                    while (out < 960) {
                        io->_chunkScratch[out] = io->_chunkScratch[out - 1];
                        out++;
                    }
                    send(io->_chunkScratch, 960, 48000, 1);
                }
            }
        }
        TPCircularBufferConsume(&io->_micBuffer, chunkBytes);
        tail = TPCircularBufferTail(&io->_micBuffer, &availableBytes);
    }

    return noErr;
}

- (BOOL)begin:(NSError **)error
{
    if (_running) {
        return YES;
    }

    // KHANDAQ (#166): a VoiceProcessingIO unit is invalidated by the system on an audio-session
    // interruption (incoming PSTN call / Siri) or a media-services reset, but nothing here would
    // notice — _running stays YES and audio dies for the rest of the call. Observe both and rebuild
    // the unit. Remove-then-add keeps observers single even if begin is retried.
    NSNotificationCenter *nc = [NSNotificationCenter defaultCenter];
    [nc removeObserver:self name:AVAudioSessionInterruptionNotification object:nil];
    [nc removeObserver:self name:AVAudioSessionMediaServicesWereResetNotification object:nil];
    [nc addObserver:self selector:@selector(handleAudioInterruption:)
               name:AVAudioSessionInterruptionNotification object:nil];
    [nc addObserver:self selector:@selector(handleMediaServicesReset:)
               name:AVAudioSessionMediaServicesWereResetNotification object:nil];

    return [self startUnit:error];
}

- (BOOL)startUnit:(NSError **)error
{
    OSStatus status = noErr;

    AudioComponentDescription desc;
    desc.componentType = kAudioUnitType_Output;
    desc.componentSubType = kAudioUnitSubType_VoiceProcessingIO;
    desc.componentManufacturer = kAudioUnitManufacturer_Apple;
    desc.componentFlags = 0;
    desc.componentFlagsMask = 0;

    AudioComponent component = AudioComponentFindNext(NULL, &desc);
    if (! component) {
        return [self failBegin:error status:-1 stage:@"component"];
    }

    status = AudioComponentInstanceNew(component, &_unit);
    if (status != noErr) {
        return [self failBegin:error status:status stage:@"instance"];
    }
    _unitCreated = YES;

    UInt32 enable = 1;
    status = AudioUnitSetProperty(_unit, kAudioOutputUnitProperty_EnableIO,
                                  kAudioUnitScope_Input, 1, &enable, sizeof(enable));
    if (status != noErr) {
        return [self failBegin:error status:status stage:@"enable-input"];
    }
    status = AudioUnitSetProperty(_unit, kAudioOutputUnitProperty_EnableIO,
                                  kAudioUnitScope_Output, 0, &enable, sizeof(enable));
    if (status != noErr) {
        return [self failBegin:error status:status stage:@"enable-output"];
    }

    _unitRate = [AVAudioSession sharedInstance].sampleRate;
    if (_unitRate <= 0) {
        _unitRate = kDefaultSampleRate;
    }

    AudioStreamBasicDescription fmt;
    memset(&fmt, 0, sizeof(fmt));
    fmt.mSampleRate = _unitRate;
    fmt.mFormatID = kAudioFormatLinearPCM;
    fmt.mFormatFlags = kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked;
    fmt.mChannelsPerFrame = 1;
    fmt.mBytesPerFrame = sizeof(SInt16);
    fmt.mBitsPerChannel = 8 * sizeof(SInt16);
    fmt.mFramesPerPacket = 1;
    fmt.mBytesPerPacket = sizeof(SInt16);

    // Client format on both app-facing scopes; the unit converts to/from the hardware format.
    status = AudioUnitSetProperty(_unit, kAudioUnitProperty_StreamFormat,
                                  kAudioUnitScope_Output, 1, &fmt, sizeof(fmt));
    if (status != noErr) {
        return [self failBegin:error status:status stage:@"format-input"];
    }
    status = AudioUnitSetProperty(_unit, kAudioUnitProperty_StreamFormat,
                                  kAudioUnitScope_Input, 0, &fmt, sizeof(fmt));
    if (status != noErr) {
        return [self failBegin:error status:status stage:@"format-output"];
    }

    AURenderCallbackStruct renderCallback;
    renderCallback.inputProc = OCTVoiceRenderOutput;
    renderCallback.inputProcRefCon = (__bridge void *)self;
    status = AudioUnitSetProperty(_unit, kAudioUnitProperty_SetRenderCallback,
                                  kAudioUnitScope_Input, 0, &renderCallback, sizeof(renderCallback));
    if (status != noErr) {
        return [self failBegin:error status:status stage:@"render-callback"];
    }

    AURenderCallbackStruct inputCallback;
    inputCallback.inputProc = OCTVoiceInputAvailable;
    inputCallback.inputProcRefCon = (__bridge void *)self;
    status = AudioUnitSetProperty(_unit, kAudioOutputUnitProperty_SetInputCallback,
                                  kAudioUnitScope_Global, 1, &inputCallback, sizeof(inputCallback));
    if (status != noErr) {
        return [self failBegin:error status:status stage:@"input-callback"];
    }

    // AGC directly addresses the "very quiet call" complaint; ignore failure (older devices).
    UInt32 agc = 1;
    AudioUnitSetProperty(_unit, kAUVoiceIOProperty_VoiceProcessingEnableAGC,
                         kAudioUnitScope_Global, 1, &agc, sizeof(agc));

    status = AudioUnitInitialize(_unit);
    if (status != noErr) {
        return [self failBegin:error status:status stage:@"initialize"];
    }

    status = AudioOutputUnitStart(_unit);
    if (status != noErr) {
        AudioUnitUninitialize(_unit);
        return [self failBegin:error status:status stage:@"start"];
    }

    OCTLogInfo(@"VPIO call audio started (unit rate %.0f Hz)", _unitRate);
    _running = YES;
    return YES;
}

- (BOOL)failBegin:(NSError **)error status:(OSStatus)status stage:(NSString *)stage
{
    OCTLogError(@"VPIO setup failed at %@: %d", stage, (int)status);
    if (_unitCreated) {
        AudioComponentInstanceDispose(_unit);
        _unit = NULL;
        _unitCreated = NO;
    }
    if (error) {
        *error = [NSError errorWithDomain:NSOSStatusErrorDomain
                                     code:status
                                 userInfo:@{NSLocalizedDescriptionKey :
                                                [NSString stringWithFormat:@"VoiceProcessingIO setup failed at %@", stage]}];
    }
    return NO;
}

- (void)stop
{
    // Unregister first so no interruption/reset notification can restart the unit during teardown.
    [[NSNotificationCenter defaultCenter] removeObserver:self];
    [self disposeUnit];
    _running = NO;
}

// Tears down the AudioUnit but keeps the object + ring buffers alive (used by stop and restartUnit).
- (void)disposeUnit
{
    if (_unitCreated) {
        if (_running) {
            AudioOutputUnitStop(_unit);
            AudioUnitUninitialize(_unit);
        }
        AudioComponentInstanceDispose(_unit);
        _unit = NULL;
        _unitCreated = NO;
    }
}

- (void)handleAudioInterruption:(NSNotification *)note
{
    NSNumber *type = note.userInfo[AVAudioSessionInterruptionTypeKey];
    // On .began the system already stopped our unit; rebuild it when the interruption ends.
    if (type.unsignedIntegerValue == AVAudioSessionInterruptionTypeEnded) {
        [self restartUnit];
    }
}

- (void)handleMediaServicesReset:(NSNotification *)note
{
    // After a media-services reset EVERY audio object is invalid — full rebuild.
    [self restartUnit];
}

- (void)restartUnit
{
    OCTLogInfo(@"VPIO restarting unit after audio-session event");
    [self disposeUnit];
    _running = NO;

    // Best-effort: the call session may need reactivating before the unit can start.
    NSError *sessionErr = nil;
    [[AVAudioSession sharedInstance] setActive:YES error:&sessionErr];

    NSError *startErr = nil;
    if (! [self startUnit:&startErr]) {
        OCTLogError(@"VPIO restart failed: %@", startErr);
    }
}

- (void)provideIncomingPCM:(const SInt16 *)pcm
               sampleCount:(OCTToxAVSampleCount)sampleCount
                  channels:(OCTToxAVChannels)channels
                sampleRate:(OCTToxAVSampleRate)sampleRate
{
    if ((! pcm) || (sampleCount == 0) || (channels == 0)) {
        return;
    }

    // Downmix to mono in a local buffer (frames from toxav are ≤ 60ms, well under the scratch cap).
    static const int32_t kMaxIncomingFrames = kOCTVoiceScratchFrames;
    SInt16 mono[kMaxIncomingFrames];
    int32_t frames = (int32_t)MIN((int32_t)sampleCount, kMaxIncomingFrames);

    if (channels == 1) {
        memcpy(mono, pcm, frames * sizeof(SInt16));
    }
    else {
        for (int32_t i = 0; i < frames; i++) {
            int32_t sum = 0;
            for (int32_t c = 0; c < (int32_t)channels; c++) {
                sum += pcm[i * channels + c];
            }
            mono[i] = (SInt16)(sum / (int32_t)channels);
        }
    }

    if ((double)sampleRate == _unitRate) {
        TPCircularBufferProduceBytes(&_speakerBuffer, mono, frames * (int32_t)sizeof(SInt16));
    }
    else {
        SInt16 resampled[kMaxIncomingFrames];
        int32_t out = OCTLinearResampleMono(mono, frames, (double)sampleRate,
                                            resampled, kMaxIncomingFrames, _unitRate);
        if (out > 0) {
            TPCircularBufferProduceBytes(&_speakerBuffer, resampled, out * (int32_t)sizeof(SInt16));
        }
    }
}

@end

#endif

@interface OCTAudioEngine ()

@property (nonatomic, assign) OCTToxAVSampleRate outputSampleRate;
@property (nonatomic, assign) OCTToxAVChannels outputNumberOfChannels;
#if TARGET_OS_IPHONE
// KHANDAQ (#166): ATOMIC on purpose — provideAudioFrames runs on the toxav receive thread and reads
// voiceIO while stopAudioFlow nils it on the main thread. A nonatomic getter would hand back a pointer
// that stopAudioFlow can dealloc mid-use (freeing the ring buffers under provideIncomingPCM →
// use-after-free crash). The atomic getter retains+autoreleases, so a local strong capture keeps the
// object alive across the call. Every read below takes a local strong ref before use.
@property (atomic, strong) OCTVoiceUnitIO *voiceIO;
#endif

@end

@implementation OCTAudioEngine

#pragma mark - LifeCycle
- (instancetype)init
{
    self = [super init];
    if (! self) {
        return nil;
    }

    _outputSampleRate = kDefaultSampleRate;
    _outputNumberOfChannels = kNumberOfChannels;
    _enableMicrophone = YES;

    return self;
}

#pragma mark - SPI

#if ! TARGET_OS_IPHONE

- (BOOL)setInputDeviceID:(NSString *)inputDeviceID error:(NSError **)error
{
    // if audio is not active, we can't really be bothered to check that the
    // device exists; we rely on startAudioFlow: to fail later.
    if (! self.inputQueue) {
        _inputDeviceID = inputDeviceID;
        return YES;
    }

    if ([self.inputQueue setDeviceID:inputDeviceID error:error]) {
        _inputDeviceID = inputDeviceID;
        return YES;
    }

    return NO;
}

- (BOOL)setOutputDeviceID:(NSString *)outputDeviceID error:(NSError **)error
{
    if (! self.outputQueue) {
        _outputDeviceID = outputDeviceID;
        return YES;
    }

    if ([self.outputQueue setDeviceID:outputDeviceID error:error]) {
        _outputDeviceID = outputDeviceID;
        return YES;
    }

    return NO;
}

#else

- (BOOL)routeAudioToSpeaker:(BOOL)speaker error:(NSError **)error
{
    AVAudioSession *session = [AVAudioSession sharedInstance];

    AVAudioSessionPortOverride override = speaker
        ? AVAudioSessionPortOverrideSpeaker
        : AVAudioSessionPortOverrideNone;

    // KHANDAQ: a speaker toggle must be a *pure* output-port override. We previously also
    // re-applied setMode: on every toggle ("keep the active call mode"), but the audio path
    // here is a software AudioQueue (no VoiceProcessingIO/AEC unit whose mode actually matters),
    // and OCTAudioQueue does NOT observe route-change/interruption notifications — so the extra
    // setMode: reconfiguration tore down the still-running input and output queues on rapid
    // toggles, leaving the mic and speaker both dead after a couple of taps mid-call.
    // overrideOutputAudioPort: alone reroutes output without disturbing the live queues.
    return [session overrideOutputAudioPort:override error:error];
}

#endif

#if TARGET_OS_IPHONE
- (BOOL)ensureRecordPermission:(NSError **)error
{
    AVAudioSessionRecordPermission permission = [[AVAudioSession sharedInstance] recordPermission];

    if (permission == AVAudioSessionRecordPermissionGranted) {
        return YES;
    }

    if (error) {
        NSString *message = (permission == AVAudioSessionRecordPermissionDenied)
            ? @"Microphone permission denied"
            : @"Microphone permission not granted";
        *error = [NSError errorWithDomain:@"OCTAudioEngine"
                                     code:1
                                 userInfo:@{NSLocalizedDescriptionKey : message}];
    }

    return NO;
}

- (BOOL)configureAudioSessionForVideoCall:(BOOL)videoCall
                            reconfigure:(BOOL)reconfigure
                                  error:(NSError **)error
{
    AVAudioSession *session = [AVAudioSession sharedInstance];
    AVAudioSessionCategoryOptions options = AVAudioSessionCategoryOptionAllowBluetooth;
    if (videoCall) {
        options |= AVAudioSessionCategoryOptionDefaultToSpeaker;
    }
    NSString *mode = videoCall ? AVAudioSessionModeVideoChat : AVAudioSessionModeVoiceChat;
    AVAudioSessionPortOverride portOverride = videoCall
        ? AVAudioSessionPortOverrideSpeaker
        : AVAudioSessionPortOverrideNone;

    // Always apply category/mode/route — even when CallKit already activated the session.
    if (! [session setCategory:AVAudioSessionCategoryPlayAndRecord
                   withOptions:options
                         error:error]) {
        return NO;
    }
    if (! [session setPreferredSampleRate:kDefaultSampleRate error:error]) {
        return NO;
    }
    if (! [session setPreferredIOBufferDuration:0.005 error:error]) {
        return NO;
    }
    if (! [session setMode:mode error:error]) {
        return NO;
    }
    if (! [session overrideOutputAudioPort:portOverride error:error]) {
        return NO;
    }

    if (reconfigure) {
        if (! [session setActive:YES error:error]) {
            return NO;
        }
    }

    return YES;
}
#endif

- (BOOL)startAudioFlow:(NSError **)error
{
    return [self startAudioFlowForVideoCall:NO reconfigureSession:YES error:error];
}

- (BOOL)startAudioFlowForVideoCall:(BOOL)videoCall
              reconfigureSession:(BOOL)reconfigureSession
                           error:(NSError **)error
{
#if TARGET_OS_IPHONE
    if (! [self ensureRecordPermission:error]) {
        OCTLogError(@"Cannot start audio flow without microphone permission: %@", error ? *error : nil);
        return NO;
    }

    if (! [self configureAudioSessionForVideoCall:videoCall
                                    reconfigure:reconfigureSession
                                          error:error]) {
        OCTLogError(@"Failed to configure AVAudioSession: %@", error ? *error : nil);
        return NO;
    }
#endif

#if TARGET_OS_IPHONE
    // KHANDAQ (#112/#166): prefer the VoiceProcessingIO engine (AEC + AGC + proper voice volume);
    // fall back to the legacy AudioQueue path automatically if it fails to start, or when the
    // user disabled it in Settings → Advanced.
    if (! [[NSUserDefaults standardUserDefaults] boolForKey:kOCTCallAudioVPIODisabledKey]) {
        OCTVoiceUnitIO *voiceIO = [[OCTVoiceUnitIO alloc] init];

        OCTAudioEngine *__weak welfVoice = self;
        voiceIO.sendDataBlock = ^(void *data, OCTToxAVSampleCount samples, OCTToxAVSampleRate rate, OCTToxAVChannels channelCount) {
            OCTAudioEngine *aoi = welfVoice;

            if (aoi.enableMicrophone) {
                NSError *sendError = nil;
                [aoi.toxav sendAudioFrame:data
                              sampleCount:samples
                                 channels:channelCount
                               sampleRate:rate
                                 toFriend:aoi.friendNumber
                                    error:&sendError];
                if (sendError) {
                    OCTLogVerbose(@"sendAudioFrame to friend %u failed: %@", aoi.friendNumber, sendError);
                }
            }
        };

        NSError *voiceError = nil;
        if ([voiceIO begin:&voiceError]) {
            self.voiceIO = voiceIO;
            return YES;
        }

        OCTLogError(@"VPIO engine failed to start (%@) — falling back to AudioQueue call audio", voiceError);
    }
#endif

    [self makeQueues:error];

    if (! (self.outputQueue && self.inputQueue)) {
        return NO;
    }

    OCTAudioEngine *__weak welf = self;
    self.inputQueue.sendDataBlock = ^(void *data, OCTToxAVSampleCount samples, OCTToxAVSampleRate rate, OCTToxAVChannels channelCount) {
        OCTAudioEngine *aoi = welf;

        if (aoi.enableMicrophone) {
            // TOXAUDIO: -outgoing-audio-
            NSError *sendError = nil;
            [aoi.toxav sendAudioFrame:data
                          sampleCount:samples
                             channels:channelCount
                           sampleRate:rate
                             toFriend:aoi.friendNumber
                                error:&sendError];
            if (sendError) {
                OCTLogVerbose(@"sendAudioFrame to friend %u failed: %@", aoi.friendNumber, sendError);
            }
        }
    };

    // TOXAUDIO: -incoming-audio-
    [self.outputQueue updateSampleRate:self.outputSampleRate numberOfChannels:self.outputNumberOfChannels error:nil];

    if (! [self.inputQueue begin:error] || ! [self.outputQueue begin:error]) {
        OCTLogError(@"Failed to start audio queues: %@", error ? *error : nil);
        return NO;
    }

    return YES;
}

- (BOOL)stopAudioFlow:(NSError **)error
{
#if TARGET_OS_IPHONE
    if (self.voiceIO) {
        [self.voiceIO stop];
        self.voiceIO = nil;

        AVAudioSession *session = [AVAudioSession sharedInstance];
        return [session setActive:NO error:error];
    }
#endif

    if (! [self.inputQueue stop:error] || ! [self.outputQueue stop:error]) {
        return NO;
    }

#if TARGET_OS_IPHONE
    AVAudioSession *session = [AVAudioSession sharedInstance];
    BOOL ret = [session setActive:NO error:error];
#else
    BOOL ret = YES;
#endif

    self.inputQueue = nil;
    self.outputQueue = nil;
    return ret;
}

- (void)provideAudioFrames:(OCTToxAVPCMData *)pcm sampleCount:(OCTToxAVSampleCount)sampleCount channels:(OCTToxAVChannels)channels sampleRate:(OCTToxAVSampleRate)sampleRate fromFriend:(OCTToxFriendNumber)friendNumber
{
#if TARGET_OS_IPHONE
    OCTVoiceUnitIO *io = self.voiceIO; // atomic getter → strong ref, safe against concurrent stop
    if (io) {
        [io provideIncomingPCM:pcm sampleCount:sampleCount channels:channels sampleRate:sampleRate];
        return;
    }
#endif

    // TOXAUDIO: -incoming-audio-
    // KHANDAQ (audit): take a local strong ref so a concurrent stopAudioFlow (main thread) can't
    // dealloc the queue between the nil-check and the buffer write on this (toxav) thread.
    OCTAudioQueue *outQ = self.outputQueue;
    if (outQ == nil) {
        return;
    }
    int32_t len = (int32_t)(channels * sampleCount * sizeof(OCTToxAVPCMData));
    TPCircularBuffer *buf = [outQ getBufferPointer];
    if (buf) {
        TPCircularBufferProduceBytes(buf, pcm, len);
    }

    if ((self.outputSampleRate != sampleRate) || (self.outputNumberOfChannels != channels)) {
        // failure is logged by OCTAudioQueue.
        [outQ updateSampleRate:sampleRate numberOfChannels:channels error:nil];

        self.outputSampleRate = sampleRate;
        self.outputNumberOfChannels = channels;
    }
}

- (BOOL)isAudioRunning:(NSError **)error
{
#if TARGET_OS_IPHONE
    OCTVoiceUnitIO *io = self.voiceIO;
    if (io) {
        return io.running;
    }
#endif
    return self.inputQueue.running && self.outputQueue.running;
}

- (void)makeQueues:(NSError **)error
{
    // Note: OCTAudioQueue handles the case where the device ids are nil - in that case
    // we don't set the device explicitly, and the default is used.
#if TARGET_OS_IPHONE
    // TOXAUDIO: -incoming-audio-
    self.outputQueue = [[OCTAudioQueue alloc] initWithOutputDeviceID:nil error:error];
    // TOXAUDIO: -outgoing-audio-
    self.inputQueue = [[OCTAudioQueue alloc] initWithInputDeviceID:nil error:error];
#else
    self.outputQueue = [[OCTAudioQueue alloc] initWithOutputDeviceID:self.outputDeviceID error:error];
    self.inputQueue = [[OCTAudioQueue alloc] initWithInputDeviceID:self.inputDeviceID error:error];
#endif
}

@end
