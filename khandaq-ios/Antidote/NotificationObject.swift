// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import UIKit

enum NotificationAction {
    case openChat(chatUniqueIdentifier: String)
    case openRequest(requestUniqueIdentifier: String)
    /// An invitation to a group from a contact: Requests, where it waits as a card (E12, E22).
    case openGroupInvite(inviteIdentifier: String)
    case answerIncomingCall(userInfo: String)
}

extension NotificationAction {
    fileprivate struct Constants {
        static let ValueKey = "ValueKey"
        static let ArgumentKey = "ArgumentKey"

        static let OpenChatValue = "OpenChatValue"
        static let OpenRequestValue = "OpenRequestValue"
        static let OpenGroupInviteValue = "OpenGroupInviteValue"
        static let AnswerIncomingCallValue = "AnswerIncomingCallValue"
    }

    init?(dictionary: [String: String]) {
        guard let value = dictionary[Constants.ValueKey] else {
            return nil
        }

        switch value {
            case Constants.OpenChatValue:
                guard let argument = dictionary[Constants.ArgumentKey] else {
                    return nil
                }
                self = .openChat(chatUniqueIdentifier: argument)
            case Constants.OpenRequestValue:
                guard let argument = dictionary[Constants.ArgumentKey] else {
                    return nil
                }
                self = .openRequest(requestUniqueIdentifier: argument)
            case Constants.OpenGroupInviteValue:
                guard let argument = dictionary[Constants.ArgumentKey] else {
                    return nil
                }
                self = .openGroupInvite(inviteIdentifier: argument)
            case Constants.AnswerIncomingCallValue:
                guard let argument = dictionary[Constants.ArgumentKey] else {
                    return nil
                }
                self = .answerIncomingCall(userInfo: argument)
            default:
                return nil
        }
    }

    /// What a tap on the in-app band does, for VoiceOver.
    var bandHint: String {
        switch self {
            case .openChat:
                return String(localized: "air_banner_hint")
            case .openRequest:
                return String(localized: "air_banner_hint_request")
            case .openGroupInvite:
                return String(localized: "air_banner_hint_invite")
            case .answerIncomingCall:
                return ""
        }
    }

    func archive() -> [String: String] {
        switch self {
            case .openChat(let identifier):
                return [
                    Constants.ValueKey: Constants.OpenChatValue,
                    Constants.ArgumentKey: identifier,
                ]
            case .openRequest(let identifier):
                return [
                    Constants.ValueKey: Constants.OpenRequestValue,
                    Constants.ArgumentKey: identifier,
                ]
            case .openGroupInvite(let identifier):
                return [
                    Constants.ValueKey: Constants.OpenGroupInviteValue,
                    Constants.ArgumentKey: identifier,
                ]
            case .answerIncomingCall(let userInfo):
                return [
                    Constants.ValueKey: Constants.AnswerIncomingCallValue,
                    Constants.ArgumentKey: userInfo,
                ]
        }
    }
}

struct NotificationObject {
    /// Title of notification
    let title: String

    /// Body text of notification
    let body: String

    /// Action to be fired when user interacts with notification
    let action: NotificationAction

    /// Sound to play when notification is fired. Valid only for local notifications.
    let soundName: String

    /// The sender's own photo for the in-app band, when they chose one.
    let icon: UIImage?

    /// The tile of the in-app band (E22): the sender's or the group's initials (an invitation shows the
    /// contact who sent it), or the dashed tile of a request; nil for a notification that never comes
    /// as a band.
    let tile: AirTileView.Style?
}
