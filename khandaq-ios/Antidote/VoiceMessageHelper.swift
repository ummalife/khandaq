// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation

/// Cross-platform voice message detection (Android uses `.file.m4a` in the wire filename).
enum VoiceMessageHelper {
    static func isVoiceMessage(fileName: String?, filePath: String?) -> Bool {
        if isVoiceMessagePath(fileName) || isVoiceMessagePath(filePath) {
            return true
        }

        guard let fileName = fileName?.lowercased() else {
            return false
        }

        return fileName.hasSuffix(".file.m4a")
    }

    static func isVoiceMessagePath(_ path: String?) -> Bool {
        guard let path = path?.lowercased(), !path.isEmpty else {
            return false
        }

        return path.contains(".file.m4a")
    }

    static func displayFileName(for fileName: String?) -> String {
        isVoiceMessage(fileName: fileName, filePath: nil)
            ? String(localized: "voice_message_label")
            : (fileName ?? "")
    }

    /// Outgoing voice files must use the Android-compatible suffix.
    static func makeOutgoingFileName(prefix: String = "voice") -> String {
        "\(prefix)_\(UUID().uuidString.prefix(8)).file.m4a"
    }

    static func audioDuration(at path: String) -> TimeInterval {
        guard FileManager.default.fileExists(atPath: path),
              let player = try? AVAudioPlayer(contentsOf: URL(fileURLWithPath: path)) else {
            return 0
        }

        return player.duration
    }
}
