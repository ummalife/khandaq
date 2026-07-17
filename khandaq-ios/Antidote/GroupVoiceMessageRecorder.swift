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
        try session.setMode(AVAudioSessionModeVoiceChat)
        try session.setActive(true)

        let url = GroupVoiceMessageRecorder.recordingsDirectory()
            .appendingPathComponent(VoiceMessageHelper.makeOutgoingFileName())

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: 48_000,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]

        let audioRecorder = try AVAudioRecorder(url: url, settings: settings)
        audioRecorder.prepareToRecord()

        guard audioRecorder.record() else {
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
        return url
    }
}
