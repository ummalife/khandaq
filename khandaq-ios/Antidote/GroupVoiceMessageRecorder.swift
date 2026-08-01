// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation

final class GroupVoiceMessageRecorder: NSObject, AVAudioRecorderDelegate {
    private var recorder: AVAudioRecorder?
    private var outputURL: URL?
    private var finishCompletion: ((URL?) -> Void)?

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

    private static func fileSize(_ url: URL) -> Int {
        return ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.intValue ?? 0
    }

    func startRecording() throws {
        cancelRecording()

        // KHANDAQ (Android parity): starting a recording pauses any playing voice note,
        // keeping its resume position.
        ChatVoiceMessagePlayer.shared.pauseActive()

        let session = AVAudioSession.sharedInstance()
        try session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            with: [.defaultToSpeaker, .allowBluetooth]
        )
        // KHANDAQ (#voice-iphone11): DEFAULT mode (not VoiceChat) records reliably across devices.
        try session.setMode(AVAudioSessionModeDefault)
        try session.setActive(true)

        let url = GroupVoiceMessageRecorder.recordingsDirectory()
            .appendingPathComponent(VoiceMessageHelper.makeOutgoingFileName())

        // Match the encoder to the sample rate the session actually negotiated with the hardware.
        let hardwareRate = session.sampleRate
        let sampleRate = hardwareRate >= 8_000 ? hardwareRate : 44_100.0

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatMPEG4AAC),
            AVSampleRateKey: sampleRate,
            AVNumberOfChannelsKey: 1,
            AVEncoderAudioQualityKey: AVAudioQuality.high.rawValue,
        ]

        let audioRecorder = try AVAudioRecorder(url: url, settings: settings)
        audioRecorder.delegate = self
        audioRecorder.prepareToRecord()

        let started = audioRecorder.record()
        NSLog("KHQVoice: start rate=%.0f started=%@ url=%@", sampleRate, started ? "YES" : "NO", url.lastPathComponent)
        guard started else {
            try? session.setActive(false)
            throw NSError(domain: "GroupVoiceMessageRecorder", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "Could not start recording",
            ])
        }

        recorder = audioRecorder
        outputURL = url
    }

    /// KHANDAQ (#voice-iphone11): discard the in-progress recording synchronously (slide-to-cancel or
    /// starting a fresh one). No completion — the file is deleted.
    func cancelRecording() {
        finishCompletion = nil
        if let rec = recorder {
            rec.delegate = nil
            rec.stop()
        }
        recorder = nil
        try? AVAudioSession.sharedInstance().setActive(false)
        if let url = outputURL {
            try? FileManager.default.removeItem(at: url)
        }
        outputURL = nil
    }

    /// KHANDAQ (#voice-iphone11): finish the recording and deliver the FINALIZED file URL via the
    /// delegate callback. The old code read the file size immediately after stop(), but AAC
    /// finalization is async — on slower hardware (iPhone 11 / A13) the container wasn't flushed yet,
    /// so the size read < 1 KB and the note was wrongly dropped ("nothing sends"). Waiting for
    /// audioRecorderDidFinishRecording guarantees the file is complete before we check/send it.
    func finishRecording(completion: @escaping (URL?) -> Void) {
        guard let rec = recorder else {
            completion(nil)
            return
        }
        finishCompletion = completion
        rec.stop() // → audioRecorderDidFinishRecording(_:successfully:)
    }

    // MARK: - AVAudioRecorderDelegate

    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        try? AVAudioSession.sharedInstance().setActive(false)
        let url = outputURL
        outputURL = nil
        self.recorder = nil
        let completion = finishCompletion
        finishCompletion = nil

        guard let completion = completion else {
            // cancelled path — clean up any file
            if let url = url { try? FileManager.default.removeItem(at: url) }
            return
        }
        guard flag, let url = url else {
            NSLog("KHQVoice: finish FAILED flag=%@", flag ? "YES" : "NO")
            if let url = url { try? FileManager.default.removeItem(at: url) }
            completion(nil)
            return
        }
        let size = GroupVoiceMessageRecorder.fileSize(url)
        NSLog("KHQVoice: finished size=%d bytes url=%@", size, url.lastPathComponent)
        // A finalized AAC header alone is a few hundred bytes; a real note is KBs. Only drop a truly
        // empty capture now that the file is guaranteed complete.
        if size < 512 {
            try? FileManager.default.removeItem(at: url)
            completion(nil)
            return
        }
        completion(url)
    }

    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        NSLog("KHQVoice: encode ERROR %@", error?.localizedDescription ?? "nil")
    }
}
