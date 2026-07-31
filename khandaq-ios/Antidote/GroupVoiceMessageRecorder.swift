// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation

final class GroupVoiceMessageRecorder: NSObject {
    private var recorder: AVAudioRecorder?
    private var outputURL: URL?

    /// KHANDAQ (#15): persistent staging dir for voice recordings. NSTemporaryDirectory() is purged
    /// by iOS, so a recording left there (e.g. if the move-to-uploads is skipped/edge-cased) becomes
    /// unplayable "after a while". Application Support survives restarts and isn't user-visible.
    private static func recordingsDirectory() -> URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first
            ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = base.appendingPathComponent("KhandaqVoice", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: nil)
        return dir
    }

    func startRecording() throws {
        stopRecording(discard: true)

        // KHANDAQ (Android parity): starting a recording pauses any playing voice note,
        // keeping its resume position.
        ChatVoiceMessagePlayer.shared.pauseActive()

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            with: [.defaultToSpeaker, .allowBluetooth]
        )
        // KHANDAQ (#voice-iphone11): record voice notes in DEFAULT mode, NOT VoiceChat. VoiceChat is
        // for live two-way calls; on older hardware (iPhone 11 / A13) it clamps the input to a
        // voice-optimized hardware sample rate, which mismatched our hard-coded 48 kHz AAC recorder →
        // record() "succeeded" but captured an empty/invalid file → the note silently failed to send
        // (worked on A18/iPhone 16 where 48 kHz is native). Default mode records reliably everywhere.
        try session.setMode(AVAudioSessionModeDefault)
        try session.setActive(true)

        let url = GroupVoiceMessageRecorder.recordingsDirectory()
            .appendingPathComponent(VoiceMessageHelper.makeOutgoingFileName())

        // Match the encoder to the sample rate the session actually negotiated with the hardware, so
        // the AAC recorder never mismatches the route. Fall back to the universally-supported 44.1 kHz.
        let hardwareRate = session.sampleRate
        let sampleRate = hardwareRate >= 8_000 ? hardwareRate : 44_100.0

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]

        let audioRecorder = try AVAudioRecorder(url: url, settings: settings)
        audioRecorder.prepareToRecord()

        guard audioRecorder.record() else {
            try? session.setActive(false)
            throw NSError(domain: "GroupVoiceMessageRecorder", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Could not start recording",
            ])
        }

        recorder = audioRecorder
        outputURL = url
    }

    func stopRecording(discard: Bool) -> URL? {
        defer {
            recorder = nil
        }

        recorder?.stop()

        // KHANDAQ (#15): release the record session so playback can re-activate cleanly afterwards.
        try? AVAudioSession.sharedInstance().setActive(false)

        guard !discard, let url = outputURL else {
            if let stale = outputURL {
                try? FileManager.default.removeItem(at: stale)
            }
            outputURL = nil
            return nil
        }

        outputURL = nil

        // KHANDAQ (#voice-iphone11): never hand a zero/near-empty capture to the sender. If the device
        // produced no real audio (a bare AAC header is a few hundred bytes; a real note is KBs), drop it
        // instead of "sending" a note that fails silently downstream.
        let size = ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.intValue ?? 0
        if size < 1_024 {
            try? FileManager.default.removeItem(at: url)
            return nil
        }

        return url
    }
}
