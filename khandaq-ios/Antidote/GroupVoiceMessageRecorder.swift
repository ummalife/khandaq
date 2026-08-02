// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation
import UIKit

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
        VoiceDebugHUD.shared.log("▶︎ start rate=\(Int(sampleRate)) ok=\(started ? "Y" : "N")")
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
            VoiceDebugHUD.shared.log("✖︎ finish FAILED flag=\(flag ? "Y" : "N") → NOT sent")
            if let url = url { try? FileManager.default.removeItem(at: url) }
            completion(nil)
            return
        }
        let size = GroupVoiceMessageRecorder.fileSize(url)
        // A finalized AAC header alone is a few hundred bytes; a real note is KBs. Only drop a truly
        // empty capture now that the file is guaranteed complete.
        if size < 512 {
            VoiceDebugHUD.shared.log("✖︎ DROPPED size=\(size)B <512 (too short?) → NOT sent")
            try? FileManager.default.removeItem(at: url)
            completion(nil)
            return
        }
        VoiceDebugHUD.shared.log("■ finished size=\(size)B → handing to send")
        completion(url)
    }

    func audioRecorderEncodeErrorDidOccur(_ recorder: AVAudioRecorder, error: Error?) {
        VoiceDebugHUD.shared.log("✖︎ encode ERROR \(error?.localizedDescription ?? "nil")")
    }
}

/// TEMPORARY on-screen diagnostic overlay for voice-note send failures on real devices
/// (esp. iPhone 11 / A13) where there is no Mac/Xcode console. The tester reads the trace off the
/// screen — start → finalized file size → sent/dropped — and sends a screenshot, or taps the panel
/// to copy the full log. Lives here (not a standalone file) so no .pbxproj change is needed.
/// Gated by `VoiceDebugHUD.enabled`; revert this block + its call sites once confirmed fixed.
/// KHANDAQ: DISABLED — iPhone-11 voice-send issue is resolved, so the on-screen diag HUD is off.
/// The log() calls become no-ops; flip `enabled` back to true to re-arm field diagnosis.
final class VoiceDebugHUD {
    static let shared = VoiceDebugHUD()
    static var enabled = false

    private var lines: [String] = []
    private var hideWork: DispatchWorkItem?

    private lazy var container: UIView = {
        let v = UIView()
        v.backgroundColor = UIColor.black.withAlphaComponent(0.78)
        v.layer.cornerRadius = 10
        v.layer.borderColor = UIColor(red: 0.2, green: 0.85, blue: 0.4, alpha: 0.9).cgColor
        v.layer.borderWidth = 1
        v.isUserInteractionEnabled = true
        v.translatesAutoresizingMaskIntoConstraints = false
        v.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(copyAndHide)))
        return v
    }()

    private lazy var titleLabel: UILabel = {
        let l = UILabel()
        l.text = "KHQVoice diag — tap to copy"
        l.font = UIFont.boldSystemFont(ofSize: 10)
        l.textColor = UIColor(red: 0.2, green: 0.85, blue: 0.4, alpha: 1)
        l.translatesAutoresizingMaskIntoConstraints = false
        return l
    }()

    private lazy var label: UILabel = {
        let l = UILabel()
        l.numberOfLines = 0
        l.font = UIFont(name: "Menlo", size: 11) ?? UIFont.systemFont(ofSize: 11, weight: .medium)
        l.textColor = .white
        l.translatesAutoresizingMaskIntoConstraints = false
        return l
    }()

    func log(_ message: String) {
        guard VoiceDebugHUD.enabled else { return }
        NSLog("KHQVoice: %@", message)
        if Thread.isMainThread { append(message) }
        else { DispatchQueue.main.async { self.append(message) } }
    }

    private func append(_ message: String) {
        lines.append("\(VoiceDebugHUD.clock())  \(message)")
        if lines.count > 9 { lines.removeFirst(lines.count - 9) }
        render()
    }

    private static func clock() -> String {
        let f = DateFormatter(); f.dateFormat = "HH:mm:ss"
        return f.string(from: Date())
    }

    private func currentWindow() -> UIWindow? {
        if #available(iOS 13.0, *) {
            let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
            for scene in scenes where scene.activationState == .foregroundActive {
                if let key = scene.windows.first(where: { $0.isKeyWindow }) ?? scene.windows.first { return key }
            }
        }
        return UIApplication.shared.windows.first { $0.isKeyWindow } ?? UIApplication.shared.windows.first
    }

    private func render() {
        guard let window = currentWindow() else { return }
        if container.superview !== window {
            container.removeFromSuperview()
            window.addSubview(container)
            container.addSubview(titleLabel)
            container.addSubview(label)
            NSLayoutConstraint.activate([
                container.leadingAnchor.constraint(equalTo: window.safeAreaLayoutGuide.leadingAnchor, constant: 8),
                container.trailingAnchor.constraint(equalTo: window.safeAreaLayoutGuide.trailingAnchor, constant: -8),
                container.topAnchor.constraint(equalTo: window.safeAreaLayoutGuide.topAnchor, constant: 6),
                titleLabel.topAnchor.constraint(equalTo: container.topAnchor, constant: 6),
                titleLabel.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 10),
                label.topAnchor.constraint(equalTo: titleLabel.bottomAnchor, constant: 4),
                label.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 10),
                label.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -10),
                label.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -8),
            ])
        }
        window.bringSubview(toFront: container)
        label.text = lines.joined(separator: "\n")
        container.isHidden = false
        container.alpha = 1
        hideWork?.cancel()
        let work = DispatchWorkItem { [weak self] in
            UIView.animate(withDuration: 0.4) { self?.container.alpha = 0 }
        }
        hideWork = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 20, execute: work)
    }

    @objc private func copyAndHide() {
        UIPasteboard.general.string = lines.joined(separator: "\n")
        titleLabel.text = "copied ✓"
        hideWork?.cancel()
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self] in
            UIView.animate(withDuration: 0.3) { self?.container.alpha = 0 }
        }
    }
}
