// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation

final class GroupVoiceMessageRecorder: NSObject {
    private var recorder: AVAudioRecorder?
    private var outputURL: URL?

    func startRecording() throws {
        stopRecording(discard: true)

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            with: [.defaultToSpeaker, .allowBluetooth]
        )
        try session.setMode(AVAudioSessionModeVoiceChat)
        try session.setActive(true)

        let url = FileManager.default.temporaryDirectory
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

        guard !discard, let url = outputURL else {
            outputURL = nil
            return nil
        }

        outputURL = nil
        return url
    }
}
