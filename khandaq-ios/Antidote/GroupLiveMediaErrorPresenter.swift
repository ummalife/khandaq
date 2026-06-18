// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import UIKit

enum GroupLiveMediaErrorPresenter {
    static var isSimulator: Bool {
        #if targetEnvironment(simulator)
        return true
        #else
        return false
        #endif
    }

    static func presentSimulatorUnavailable(forAudio: Bool) {
        let message = forAudio
            ? String(localized: "group_live_audio_simulator_unavailable")
            : String(localized: "group_live_video_simulator_unavailable")
        UIAlertController.showWithTitle(String(localized: "error_title"), message: message, retryBlock: nil)
    }

    static func present(_ error: NSError) {
        let title = String(localized: "error_title")
        let message = localizedMessage(for: error)
        UIAlertController.showWithTitle(title, message: message, retryBlock: nil)
    }

    static func localizedMessage(for error: NSError) -> String {
        switch error.domain {
        case "OCTSubmanagerGroupsErrorDomain":
            return String(localized: "group_live_media_not_connected")

        case "OCTNgcGroupLiveVideo":
            switch error.code {
            case 1:
                return String(localized: "group_live_video_simulator_unavailable")
            case 2:
                return String(localized: "group_live_video_camera_denied")
            case 3:
                return String(localized: "group_live_video_camera_denied")
            case 4:
                return String(localized: "group_live_video_no_camera")
            default:
                return nonEmptyDescription(error)
            }

        case "OCTNgcGroupLiveAudio":
            switch error.code {
            case 1:
                return String(localized: "call_error_microphone_denied")
            case 4:
                return String(localized: "group_live_audio_codec_unavailable")
            case 5:
                return String(localized: "group_live_audio_simulator_unavailable")
            default:
                return nonEmptyDescription(error)
            }

        default:
            return nonEmptyDescription(error)
        }
    }

    private static func nonEmptyDescription(_ error: NSError) -> String {
        let description = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
        if !description.isEmpty {
            return description
        }
        return String(localized: "group_live_media_start_failed")
    }
}
