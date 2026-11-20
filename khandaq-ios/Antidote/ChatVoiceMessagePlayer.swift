// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation

extension Notification.Name {
    static let chatVoiceMessagePlayerStateDidChange = Notification.Name("chatVoiceMessagePlayerStateDidChange")
}

struct ChatVoiceMessagePlayerState {
    let messageId: String
    let isPlaying: Bool
    let progress: Float
    let currentTime: TimeInterval
    let duration: TimeInterval
}

/// KHANDAQ (ideas 1): how fast voice notes play. A tap on the speed of the note that plays goes from 1x to
/// 1.5x, 2x and back to 1x; the choice is the app's, kept for every note after it (UserDefaultsManager).
enum VoicePlaybackSpeed {
    static let steps: [Float] = [1, 1.5, 2]

    /// The speed after `speed`; a value that is not a step goes back to the first.
    static func next(after speed: Float) -> Float {
        guard let index = steps.firstIndex(of: speed) else {
            return steps[0]
        }
        return steps[(index + 1) % steps.count]
    }

    /// `speed` when it is a step, else the first: a stored value is read back through it.
    static func valid(_ speed: Float) -> Float {
        return steps.contains(speed) ? speed : steps[0]
    }

    /// "1.5x", the number in the digits of the app's language, as a file entry writes its size.
    static func text(for speed: Float, locale: Locale = AppLanguage.locale) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 1
        let number = formatter.string(from: NSNumber(value: speed)) ?? String(speed)
        return String(localized: "air_voice_speed_format", number)
    }
}

final class ChatVoiceMessagePlayer: NSObject, AVAudioPlayerDelegate {
    static let shared = ChatVoiceMessagePlayer()

    private(set) var activeMessageId: String?
    private var player: AVAudioPlayer?
    private var progressTimer: Timer?

    // KHANDAQ (Android parity): per-message resume positions — pausing (or being displaced by
    // another voice) remembers the spot for the session; the bubble renders it and the next play
    // resumes from it instead of starting over. Durations are cached so idle bubbles can show
    // a meaningful progress for a partially-played note.
    private var savedPositions = [String: TimeInterval]()
    private var knownDurations = [String: TimeInterval]()

    private override init() {
        super.init()
    }

    /// KHANDAQ (ideas 1): the speed every note plays at, the app's one choice (VoicePlaybackSpeed).
    var speed: Float {
        return VoicePlaybackSpeed.valid(UserDefaultsManager().voicePlaybackSpeed)
    }

    /// KHANDAQ (ideas 1): the next speed, kept for the app and applied at once to the note that plays; its
    /// bubble hears of it as of any change of the player.
    func cycleSpeed() {
        let next = VoicePlaybackSpeed.next(after: speed)
        UserDefaultsManager().voicePlaybackSpeed = next
        guard let messageId = activeMessageId, let player = player else {
            return
        }
        player.rate = next
        postState(for: messageId, player: player)
    }

    /// The speed the note with `messageId` shows: while it plays, or waits paused part of the way through.
    /// Nil on every other note, and once it has played to the end.
    func speedShown(for messageId: String) -> Float? {
        guard activeMessageId == messageId, let player = player, player.isPlaying || player.currentTime > 0 else {
            return nil
        }
        return speed
    }

    /// The rate the note that plays goes at; nil while none does. Read by the tests.
    var activeRate: Float? {
        return player?.rate
    }

    func state(for messageId: String) -> ChatVoiceMessagePlayerState? {
        guard activeMessageId == messageId, let player = player else {
            // KHANDAQ: a non-active note that was partially played still shows its remembered spot
            if let saved = savedPositions[messageId], saved > 0,
               let duration = knownDurations[messageId], duration > 0 {
                return ChatVoiceMessagePlayerState(
                    messageId: messageId,
                    isPlaying: false,
                    progress: Float(saved / duration),
                    currentTime: saved,
                    duration: duration
                )
            }
            return nil
        }

        let duration = player.duration
        let current = player.currentTime
        let progress = duration > 0 ? Float(current / duration) : 0
        return ChatVoiceMessagePlayerState(
            messageId: messageId,
            isPlaying: player.isPlaying,
            progress: progress,
            currentTime: current,
            duration: duration
        )
    }

    func togglePlayback(messageId: String, filePath: String) {
        if activeMessageId == messageId, let player = player {
            if player.isPlaying {
                player.pause()
                savedPositions[messageId] = player.currentTime
            }
            else {
                player.play()
            }
            postState(for: messageId, player: player)
            updateTimer(for: player)
            return
        }

        stop()

        guard FileManager.default.fileExists(atPath: filePath) else {
            return
        }

        // NB: `.defaultToSpeaker` is only valid for `.playAndRecord`; combining it with `.playback`
        // makes setCategory throw, leaving the session in the recorder's category → silent playback.
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(AVAudioSessionCategoryPlayback, with: [.allowBluetoothA2DP])
            try session.setActive(true)
        }
        catch {
            try? session.setCategory(AVAudioSessionCategoryPlayback)
            try? session.setActive(true)
        }

        guard let audioPlayer = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: filePath)) else {
            return
        }

        audioPlayer.delegate = self
        // KHANDAQ (ideas 1): a player takes a new rate only when told so before it is prepared.
        audioPlayer.enableRate = true
        audioPlayer.prepareToPlay()
        audioPlayer.rate = speed
        // UI tests play the notes of the demo world without a sound: the walks run on a Mac someone uses.
        if ProcessInfo.processInfo.arguments.contains("UI_TESTING") {
            audioPlayer.volume = 0
        }

        // KHANDAQ (Android parity): resume from the remembered spot (unless it is right at the end)
        knownDurations[messageId] = audioPlayer.duration
        if let resume = savedPositions[messageId], resume > 0,
           audioPlayer.duration > 0, resume < audioPlayer.duration - 1 {
            audioPlayer.currentTime = resume
        }

        audioPlayer.play()

        player = audioPlayer
        activeMessageId = messageId
        postState(for: messageId, player: audioPlayer)
        updateTimer(for: audioPlayer)
    }

    func stop() {
        progressTimer?.invalidate()
        progressTimer = nil
        let previous = activeMessageId
        // KHANDAQ (Android parity): being displaced/stopped remembers the spot for later resume
        if let previous = previous, let player = player {
            savedPositions[previous] = player.currentTime
        }
        player?.stop()
        player = nil
        activeMessageId = nil

        // KHANDAQ (#36): notify the previously-active cell so it resets to the play icon (now with
        // its remembered position instead of a hard 0).
        if let previous = previous {
            let saved = savedPositions[previous] ?? 0
            let duration = knownDurations[previous] ?? 0
            NotificationCenter.default.post(
                name: .chatVoiceMessagePlayerStateDidChange,
                object: self,
                userInfo: ["state": ChatVoiceMessagePlayerState(
                    messageId: previous, isPlaying: false,
                    progress: duration > 0 ? Float(saved / duration) : 0,
                    currentTime: saved, duration: duration)]
            )
        }
    }

    /// KHANDAQ: pause whatever is playing, remembering its spot (recording starts, etc).
    func pauseActive() {
        guard let messageId = activeMessageId, let player = player, player.isPlaying else {
            return
        }
        player.pause()
        savedPositions[messageId] = player.currentTime
        postState(for: messageId, player: player)
        progressTimer?.invalidate()
        progressTimer = nil
    }

    /// KHANDAQ (Android parity): scrub. Seeks the active note live; for a non-active note the
    /// position is staged so the next play starts there (duration probed from the file if unknown).
    func seek(messageId: String, filePath: String, fraction: Float) {
        let clamped = TimeInterval(max(0, min(1, fraction)))

        if activeMessageId == messageId, let player = player {
            player.currentTime = clamped * player.duration
            savedPositions[messageId] = player.currentTime
            postState(for: messageId, player: player)
            return
        }

        var duration = knownDurations[messageId]
        if duration == nil, FileManager.default.fileExists(atPath: filePath),
           let probe = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: filePath)) {
            duration = probe.duration
            knownDurations[messageId] = probe.duration
        }
        guard let total = duration, total > 0 else {
            return
        }
        let target = clamped * total
        savedPositions[messageId] = target
        NotificationCenter.default.post(
            name: .chatVoiceMessagePlayerStateDidChange,
            object: self,
            userInfo: ["state": ChatVoiceMessagePlayerState(
                messageId: messageId, isPlaying: false,
                progress: Float(target / total), currentTime: target, duration: total)]
        )
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard let messageId = activeMessageId else {
            return
        }

        // finished: forget the resume spot so the next play starts from the beginning
        savedPositions[messageId] = nil
        player.currentTime = 0
        postState(for: messageId, player: player)
        progressTimer?.invalidate()
        progressTimer = nil
    }

    private func updateTimer(for player: AVAudioPlayer) {
        progressTimer?.invalidate()

        guard player.isPlaying, let messageId = activeMessageId else {
            progressTimer = nil
            return
        }

        progressTimer = Timer.scheduledTimer(withTimeInterval: 0.05, repeats: true) { [weak self] _ in
            guard let self = self, let activePlayer = self.player, self.activeMessageId == messageId else {
                return
            }

            self.postState(for: messageId, player: activePlayer)

            if !activePlayer.isPlaying {
                self.progressTimer?.invalidate()
                self.progressTimer = nil
            }
        }
    }

    private func postState(for messageId: String, player: AVAudioPlayer) {
        let duration = player.duration
        let current = player.currentTime
        let progress = duration > 0 ? Float(current / duration) : 0
        let state = ChatVoiceMessagePlayerState(
            messageId: messageId,
            isPlaying: player.isPlaying,
            progress: progress,
            currentTime: current,
            duration: duration
        )

        NotificationCenter.default.post(
            name: .chatVoiceMessagePlayerStateDidChange,
            object: self,
            userInfo: ["state": state]
        )
    }
}
