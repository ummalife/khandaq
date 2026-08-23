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

    /// Strict test used ONLY to decide whether a file may be fetched WITHOUT the user asking.
    ///
    /// KHANDAQ (internal audit 2026-08-22). `isVoiceMessage` matches the marker anywhere in the
    /// name, which is right for deciding how to LABEL a bubble and wrong for deciding what to
    /// download by itself: `photo.file.m4a.jpg` satisfied it, so any accepted contact could have a
    /// file of arbitrary type and up to 20 MB written to the device over cellular data while the
    /// attachment setting said Never. The name is attacker-chosen and cannot carry that weight.
    ///
    /// So the auto-fetch test requires the exact suffix the sending side produces
    /// (`makeOutgoingFileName`), not a substring, and a size a voice note actually has. Labelling
    /// keeps the loose test — mislabelling a bubble is a cosmetic problem, fetching is not.
    static func isAutoFetchableVoiceNote(fileName: String?, fileSize: OCTToxFileSize) -> Bool {
        guard let name = fileName?.lowercased(), name.hasSuffix(".file.m4a") else {
            return false
        }
        return fileSize > 0 && fileSize <= maxAutoFetchVoiceNoteSize
    }

    /// A minute of the AAC the recorder produces is well under 1 MB; 4 MB is generous for a voice
    /// note and small enough that abusing the exemption is not worth anyone's while.
    static let maxAutoFetchVoiceNoteSize: OCTToxFileSize = 4 * 1024 * 1024

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
