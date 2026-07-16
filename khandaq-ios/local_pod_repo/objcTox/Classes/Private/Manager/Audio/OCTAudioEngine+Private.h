// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTAudioEngine.h"
#import "TPCircularBuffer.h"

@import AVFoundation;

extern int kBufferLength;
extern int kNumberOfChannels;
extern int kDefaultSampleRate;
extern int kSampleCount_incoming_audio;
extern int kSampleCount_outgoing_audio;
extern int kBitsPerByte;
extern int kFramesPerPacket;
extern int kBytesPerSample;
extern int kNumberOfAudioQueueBuffers;

@class OCTAudioQueue;
@interface OCTAudioEngine ()

#if ! TARGET_OS_IPHONE
@property (strong, nonatomic, readonly) NSString *inputDeviceID;
@property (strong, nonatomic, readonly) NSString *outputDeviceID;
#endif

// KHANDAQ (audit): atomic — provideAudioFrames reads outputQueue on the toxav receive thread while
// stopAudioFlow nils it on the main thread; a nonatomic getter could hand back a queue that is then
// deallocated mid-use (use-after-free on the ring buffer). Mirrors the OCTVoiceUnitIO voiceIO fix.
@property (atomic, strong) OCTAudioQueue *outputQueue;
@property (atomic, strong) OCTAudioQueue *inputQueue;

- (void)makeQueues:(NSError **)error;

@end
