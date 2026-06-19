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

final class ChatVoiceMessagePlayer: NSObject, AVAudioPlayerDelegate {
    static let shared = ChatVoiceMessagePlayer()

    private(set) var activeMessageId: String?
    private var player: AVAudioPlayer?
    private var progressTimer: Timer?

    private override init() {
        super.init()
    }

    func state(for messageId: String) -> ChatVoiceMessagePlayerState? {
        guard activeMessageId == messageId, let player = player else {
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
        audioPlayer.prepareToPlay()
        audioPlayer.play()

        player = audioPlayer
        activeMessageId = messageId
        postState(for: messageId, player: audioPlayer)
        updateTimer(for: audioPlayer)
    }

    func stop() {
        progressTimer?.invalidate()
        progressTimer = nil
        player?.stop()
        player = nil
        activeMessageId = nil
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        guard let messageId = activeMessageId else {
            return
        }

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
