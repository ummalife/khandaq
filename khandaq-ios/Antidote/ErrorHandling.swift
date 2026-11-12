// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

enum PasswordValidationFailure {
    case empty
    case tooShort
    case missingLetter
    case missingDigit
}

struct PasswordPolicy {
    static let minimumLength = 8

    static func validate(_ password: String) -> PasswordValidationFailure? {
        if password.isEmpty {
            return .empty
        }
        if password.count < minimumLength {
            return .tooShort
        }
        if password.rangeOfCharacter(from: .letters) == nil {
            return .missingLetter
        }
        if password.rangeOfCharacter(from: .decimalDigits) == nil {
            return .missingDigit
        }
        return nil
    }
}

func handlePasswordValidationFailure(_ failure: PasswordValidationFailure) {
    switch failure {
        case .empty:
            handleErrorWithType(.passwordIsEmpty)
        case .tooShort:
            handleErrorWithType(.passwordTooShort)
        case .missingLetter, .missingDigit:
            handleErrorWithType(.passwordTooWeak)
    }
}

enum ErrorHandlerType: CaseIterable {
    case cannotLoadHTML
    case createOCTManager
    case toxSetInfoCodeName
    case toxSetInfoCodeStatusMessage
    case toxAddFriend
    case removeFriend
    case callToChat
    case exportProfile
    case deleteProfile
    case passwordIsEmpty
    case wrongOldPassword
    case passwordsDoNotMatch
    case passwordTooShort
    case passwordTooWeak
    case answerCall
    case routeAudioToSpeaker
    case enableVideoSending
    case callSwitchCamera
    case convertImageToPNG
    case changeAvatar
    case sendMessageToFriend
    case acceptGroupInvite
    case inviteFriendToGroup
    case kickGroupPeer
    case leaveGroup
    case setGroupPeerRole
    case setGroupTopic
    case setGroupSelfPeerName
    case setGroupPassword
    case setGroupTopicLock
    case setGroupPrivacy
    case setGroupVoice
    case setGroupPeerLimit
    case sendGroupPrivateMessage
    case sendFileToFriend
    case acceptIncomingFile
    case cancelFileTransfer
    case pauseFileTransfer
    case pinLogOut

    /// What failed, said where nothing more specific is known (redesign stage 3, E21): the title in
    /// place of a bare "Error".
    var failureTitle: String {
        switch self {
            case .cannotLoadHTML:
                return String(localized: "error_failed_open_page")
            case .createOCTManager:
                return String(localized: "error_failed_open_profile")
            case .toxSetInfoCodeName:
                return String(localized: "error_failed_name")
            case .toxSetInfoCodeStatusMessage:
                return String(localized: "error_failed_status")
            case .toxAddFriend:
                return String(localized: "error_failed_add_contact")
            case .removeFriend:
                return String(localized: "error_failed_remove_contact")
            case .callToChat:
                return String(localized: "error_failed_call")
            case .exportProfile:
                return String(localized: "error_failed_export")
            case .deleteProfile:
                return String(localized: "error_failed_delete_profile")
            case .passwordIsEmpty, .wrongOldPassword, .passwordsDoNotMatch, .passwordTooShort, .passwordTooWeak:
                return String(localized: "error_failed_password")
            case .answerCall:
                return String(localized: "error_failed_answer")
            case .routeAudioToSpeaker:
                return String(localized: "error_failed_speaker")
            case .enableVideoSending:
                return String(localized: "error_failed_camera")
            case .callSwitchCamera:
                return String(localized: "error_failed_switch_camera")
            case .convertImageToPNG, .changeAvatar:
                return String(localized: "error_failed_photo")
            case .sendMessageToFriend, .sendGroupPrivateMessage:
                return String(localized: "error_failed_send_message")
            case .acceptGroupInvite:
                return String(localized: "error_failed_join_group")
            case .inviteFriendToGroup:
                return String(localized: "error_failed_invite")
            case .kickGroupPeer:
                return String(localized: "error_failed_kick")
            case .leaveGroup:
                return String(localized: "error_failed_leave_group")
            case .setGroupPeerRole:
                return String(localized: "error_failed_role")
            case .setGroupTopic:
                return String(localized: "error_failed_topic")
            case .setGroupSelfPeerName:
                return String(localized: "error_failed_group_name")
            case .setGroupPassword, .setGroupTopicLock, .setGroupPrivacy, .setGroupVoice, .setGroupPeerLimit:
                return String(localized: "error_failed_group_settings")
            case .sendFileToFriend:
                return String(localized: "error_failed_send_file")
            case .acceptIncomingFile:
                return String(localized: "error_failed_accept_file")
            case .cancelFileTransfer:
                return String(localized: "error_failed_cancel_file")
            case .pauseFileTransfer:
                return String(localized: "error_failed_pause_file")
            case .pinLogOut:
                return String(localized: "error_pin_logout_title")
        }
    }
}

/// What an error says (redesign stage 3, E21): what failed, then what to do. The technical code is
/// for support and lies under "Details", never in the words.
struct ErrorAlert: Equatable {
    let title: String
    let message: String?
    /// The domain and code of the error, nil without one.
    let details: String?

    static func details(of error: NSError) -> String {
        return "\(error.domain) \(error.code)"
    }
}

/**
    Show alert for given error.

    - Parameters:
      - type: Type of error to handle.
      - error: Optional erro to get code from.
      - retryBlock: If set user will be asked to retry request once again.
 */
func handleErrorWithType(_ type: ErrorHandlerType, error: NSError? = nil, retryBlock: (() -> Void)? = nil) {
    guard let alert = errorAlert(for: type, error: error) else {
        return
    }
    UIAlertController.showWithTitle(alert.title, message: alert.message, details: alert.details, retryBlock: retryBlock)
}

/// The words of an error; nil for a failure that needs none (a transfer the person cancelled). An
/// error without a code, or with a code this build does not know, still has words: it never crashes.
func errorAlert(for type: ErrorHandlerType, error: NSError?) -> ErrorAlert? {
    guard let words = errorWords(for: type, error: error) else {
        return nil
    }
    // A bare "Error" says nothing: the title says what failed. "Internal error" says nothing to do:
    // the words ask to try again, and the code waits under "Details".
    let generic = String(localized: "error_title")
    let empty = [String(localized: "error_internal_message"), String(localized: "error_general_unknown_message")]
    let title = (words.title.isEmpty || words.title == generic) ? type.failureTitle : words.title
    var message = words.message
    if let text = message, empty.contains(text) || text.isEmpty {
        // With a code under "Details" there is something to take to support.
        message = String(localized: error == nil ? "error_try_again_message" : "error_try_again_support_message")
    }
    if message == title {
        message = nil
    }
    return ErrorAlert(title: title, message: message, details: error.map { ErrorAlert.details(of: $0) })
}

/// The words a system error carries are the system's own and readable; other errors' descriptions are
/// technical, so they go no further than "Details".
private func systemDescription(of error: NSError?) -> String? {
    guard let error = error, error.domain == NSCocoaErrorDomain || error.domain == NSPOSIXErrorDomain else {
        return nil
    }
    return error.localizedDescription
}

private func errorWords(for type: ErrorHandlerType, error: NSError?) -> (title: String, message: String?)? {
    let generic = String(localized: "error_title")
    let internalMessage = String(localized: "error_internal_message")
    let code = error?.code

    switch type {
        case .cannotLoadHTML:
            return (generic, internalMessage)
        case .createOCTManager:
            return code.flatMap { OCTManagerInitError(rawValue: $0) }?.strings() ?? (generic, internalMessage)
        case .toxSetInfoCodeName:
            return code.flatMap { OCTToxErrorSetInfoCode(rawValue: $0) }?.nameStrings() ?? (generic, internalMessage)
        case .toxSetInfoCodeStatusMessage:
            return code.flatMap { OCTToxErrorSetInfoCode(rawValue: $0) }?.statusMessageStrings() ?? (generic, internalMessage)
        case .toxAddFriend:
            return code.flatMap { OCTToxErrorFriendAdd(rawValue: $0) }?.strings() ?? (generic, internalMessage)
        case .removeFriend:
            return code.flatMap { OCTToxErrorFriendDelete(rawValue: $0) }?.strings() ?? (generic, internalMessage)
        case .callToChat:
            // KHANDAQ (redesign stage 3, E9): a call refused without a toxav code (it used to be the
            // denied microphone) crashed here on a force unwrap.
            return code.flatMap { OCTToxAVErrorCall(rawValue: $0) }?.strings() ?? (generic, internalMessage)
        case .exportProfile, .deleteProfile:
            return (generic, systemDescription(of: error) ?? internalMessage)
        case .passwordIsEmpty:
            return (String(localized: "password_is_empty_error"), nil)
        case .wrongOldPassword:
            return (String(localized: "wrong_old_password"), nil)
        case .passwordsDoNotMatch:
            return (String(localized: "passwords_do_not_match"), nil)
        case .passwordTooShort:
            return (String(localized: "password_too_short_error"), nil)
        case .passwordTooWeak:
            return (String(localized: "password_too_weak_error"), nil)
        case .answerCall:
            if let answerError = code.flatMap({ OCTToxAVErrorAnswer(rawValue: $0) }) {
                return answerError.strings()
            }
            // No toxav code: the microphone was refused.
            return (String(localized: "air_call_mic_title"), String(localized: "call_error_microphone_denied"))
        case .routeAudioToSpeaker, .enableVideoSending, .callSwitchCamera:
            return (generic, internalMessage)
        case .convertImageToPNG:
            return (generic, String(localized: "change_avatar_error_convert_image"))
        case .changeAvatar:
            return code.flatMap { OCTSetUserAvatarError(rawValue: $0) }?.strings() ?? (generic, internalMessage)
        case .sendMessageToFriend:
            if let error = error, error.domain == "OCTSubmanagerGroupsErrorDomain", error.code == 1 {
                return (generic, String(localized: "group_send_observer_role"))
            }
            if let groupError = code.flatMap({ OCTToxErrorGroupSendMessage(rawValue: $0) }) {
                return groupError.strings()
            }
            return code.flatMap { OCTToxErrorFriendSendMessage(rawValue: $0) }?.strings() ?? (generic, internalMessage)
        case .acceptGroupInvite:
            return code.flatMap { OCTToxErrorGroupInviteAccept(rawValue: $0) }?.strings()
                ?? (String(localized: "group_invite_accept_failed"), nil)
        case .inviteFriendToGroup:
            return code.flatMap { OCTToxErrorGroupInviteFriend(rawValue: $0) }?.strings()
                ?? (String(localized: "group_invite_friend_failed"), nil)
        case .kickGroupPeer:
            return code.flatMap { OCTToxErrorGroupKickPeer(rawValue: $0) }?.strings()
                ?? (String(localized: "group_peer_kick_failed"), nil)
        case .leaveGroup:
            return (String(localized: "group_leave_failed"), nil)
        case .setGroupPeerRole:
            return code.flatMap { OCTToxErrorGroupSetRole(rawValue: $0) }?.strings()
                ?? (String(localized: "group_role_set_failed"), nil)
        case .setGroupTopic:
            return code.flatMap { OCTToxErrorGroupTopicSet(rawValue: $0) }?.strings()
                ?? (String(localized: "group_topic_set_failed"), nil)
        case .setGroupSelfPeerName:
            return (String(localized: "group_my_peer_name_set_failed"), nil)
        case .setGroupPassword:
            return code.flatMap { OCTToxErrorGroupFounderSetPassword(rawValue: $0) }?.strings()
                ?? (String(localized: "group_password_set_failed"), nil)
        case .setGroupTopicLock:
            return code.flatMap { OCTToxErrorGroupFounderSetTopicLock(rawValue: $0) }?.strings()
                ?? (String(localized: "group_topic_lock_set_failed"), nil)
        case .setGroupPrivacy:
            return code.flatMap { OCTToxErrorGroupFounderSetPrivacyState(rawValue: $0) }?.strings()
                ?? (String(localized: "group_privacy_set_failed"), nil)
        case .setGroupVoice:
            return code.flatMap { OCTToxErrorGroupFounderSetVoiceState(rawValue: $0) }?.strings()
                ?? (String(localized: "group_voice_set_failed"), nil)
        case .setGroupPeerLimit:
            return code.flatMap { OCTToxErrorGroupFounderSetPeerLimit(rawValue: $0) }?.strings()
                ?? (String(localized: "group_peer_limit_set_failed"), String(localized: "group_peer_limit_invalid"))
        case .sendGroupPrivateMessage:
            return code.flatMap { OCTToxErrorGroupSendPrivateMessage(rawValue: $0) }?.strings()
                ?? (String(localized: "group_private_message_failed"), nil)
        case .sendFileToFriend:
            if let error = error, error.domain == "OCTSubmanagerGroupsErrorDomain", error.code == 1 {
                return (generic, String(localized: "group_send_observer_role"))
            }
            if let error = error, error.domain == "OCTNgcGroupFileTransferErrorDomain" {
                switch error.code {
                    case 4:
                        // Cancelled by the person: nothing to say.
                        return nil
                    case 1:
                        return (generic, String(localized: "group_file_too_large"))
                    default:
                        return (String(localized: "group_file_send_failed"), nil)
                }
            }
            return code.flatMap { OCTSendFileError(rawValue: $0) }?.strings() ?? (generic, internalMessage)
        case .acceptIncomingFile:
            return code.flatMap { OCTAcceptFileError(rawValue: $0) }?.strings() ?? (generic, internalMessage)
        case .cancelFileTransfer, .pauseFileTransfer:
            return code.flatMap { OCTFileTransferError(rawValue: $0) }?.strings() ?? (generic, internalMessage)
        case .pinLogOut:
            return (generic, String(localized: "pin_logout_message"))
    }
}

// KHANDAQ (redesign stage 3, E21): objcTox's enums are open. A code this build does not know (or a code
// of another domain read as one of these) still makes a value, and a switch without "@unknown default"
// traps on it: every switch below ends in one, so an unknown code gives words instead of a crash.

extension OCTManagerInitError {
    func strings() -> (title: String, message: String) {
        switch self {
            case .passphraseFailed:
                return (String(localized: "error_wrong_password_title"),
                        String(localized: "error_wrong_password_message"))
            case .cannotImportToxSave:
                return (String(localized: "error_import_not_exist_title"),
                        String(localized: "error_import_not_exist_message"))
            case .databaseKeyCannotCreateKey:
                fallthrough
            case .databaseKeyCannotReadKey:
                fallthrough
            case .databaseKeyMigrationToEncryptedFailed:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            case .toxFileDecryptNull:
                fallthrough
            case .databaseKeyDecryptNull:
                return (String(localized: "error_decrypt_title"),
                        String(localized: "error_decrypt_empty_data_message"))
            case .toxFileDecryptBadFormat:
                fallthrough
            case .databaseKeyDecryptBadFormat:
                return (String(localized: "error_decrypt_title"),
                        String(localized: "error_decrypt_bad_format_message"))
            case .toxFileDecryptFailed:
                fallthrough
            case .databaseKeyDecryptFailed:
                return (String(localized: "error_decrypt_title"),
                        String(localized: "error_decrypt_wrong_password_message"))
            case .createToxUnknown:
                return (String(localized: "error_title"),
                        String(localized: "error_general_unknown_message"))
            case .createToxMemoryError:
                return (String(localized: "error_title"),
                        String(localized: "error_general_no_memory_message"))
            case .createToxPortAlloc:
                return (String(localized: "error_title"),
                        String(localized: "error_general_bind_port_message"))
            case .createToxProxyBadType:
                return (String(localized: "error_proxy_title"),
                        String(localized: "error_internal_message"))
            case .createToxProxyBadHost:
                return (String(localized: "error_proxy_title"),
                        String(localized: "error_proxy_invalid_address_message"))
            case .createToxProxyBadPort:
                return (String(localized: "error_proxy_title"),
                        String(localized: "error_proxy_invalid_port_message"))
            case .createToxProxyNotFound:
                return (String(localized: "error_proxy_title"),
                        String(localized: "error_proxy_host_not_resolved_message"))
            case .createToxEncrypted:
                return (String(localized: "error_title"),
                        String(localized: "error_general_profile_encrypted_message"))
            case .createToxBadFormat:
                return (String(localized: "error_title"),
                        String(localized: "error_general_bad_format_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorSetInfoCode {
    func nameStrings() -> (title: String, message: String) {
        switch self {
            case .unknow:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            case .tooLong:
                return (String(localized: "error_title"),
                        String(localized: "error_name_too_long"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }

    func statusMessageStrings() -> (title: String, message: String) {
        switch self {
            case .unknow:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            case .tooLong:
                return (String(localized: "error_title"),
                        String(localized: "error_status_message_too_long"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorFriendAdd {
    func strings() -> (title: String, message: String) {
        switch self {
            case .tooLong:
                return (String(localized: "error_title"),
                        String(localized: "error_contact_request_too_long"))
            case .noMessage:
                return (String(localized: "error_title"),
                        String(localized: "error_contact_request_no_message"))
            case .ownKey:
                return (String(localized: "error_title"),
                        String(localized: "error_contact_request_own_key"))
            case .alreadySent:
                return (String(localized: "error_title"),
                        String(localized: "error_contact_request_already_sent"))
            case .badChecksum:
                return (String(localized: "error_title"),
                        String(localized: "error_contact_request_bad_checksum"))
            case .setNewNospam:
                return (String(localized: "error_title"),
                        String(localized: "error_contact_request_new_nospam"))
            case .malloc:
                fallthrough
            case .unknown:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorFriendDelete {
    func strings() -> (title: String, message: String) {
        switch self {
            case .notFound:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxAVErrorCall {
    func strings() -> (title: String, message: String) {
        switch self {
            case .alreadyInCall:
                return (String(localized: "error_title"),
                        String(localized: "call_error_already_in_call"))
            case .friendNotConnected:
                return (String(localized: "error_title"),
                        String(localized: "call_error_contact_is_offline"))
            case .friendNotFound:
                fallthrough
            case .invalidBitRate:
                fallthrough
            case .malloc:
                fallthrough
            case .sync:
                fallthrough
            case .unknown:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxAVErrorAnswer {
    func strings() -> (title: String, message: String) {
        switch self {
            case .friendNotCalling:
                return (String(localized: "error_title"),
                        String(localized: "call_error_no_active_call"))
            case .codecInitialization:
                return (String(localized: "error_title"),
                        String(localized: "call_error_codec"))
            case .sync:
                return (String(localized: "error_title"),
                        String(localized: "call_error_connection"))
            case .invalidBitRate:
                return (String(localized: "error_title"),
                        String(localized: "call_error_bitrate"))
            case .friendNotFound:
                return (String(localized: "error_title"),
                        String(localized: "call_error_contact_unreachable"))
            case .unknown:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTSetUserAvatarError {
    func strings() -> (title: String, message: String) {
        switch self {
            case .tooBig:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorFriendSendMessage {
    func strings() -> (title: String, message: String) {
        switch self {
            case .friendNotConnected:
                return (String(localized: "error_title"),
                        String(localized: "error_contact_not_connected"))
            case .tooLong:
                return (String(localized: "error_title"),
                        String(localized: "error_status_message_too_long"))
            case .friendNotFound:
                fallthrough
            case .alloc:
                fallthrough
            case .empty:
                fallthrough
            case .unknown:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupSendMessage {
    func strings() -> (title: String, message: String) {
        switch self {
            case .disconnected:
                return (String(localized: "error_title"),
                        String(localized: "group_error_disconnected"))
            case .groupNotFound:
                return (String(localized: "error_title"),
                        String(localized: "group_error_not_found"))
            case .tooLong:
                return (String(localized: "error_title"),
                        String(localized: "error_status_message_too_long"))
            case .permissions:
                return (String(localized: "error_title"),
                        String(localized: "group_error_permissions"))
            case .failSend:
                return (String(localized: "error_title"),
                        String(localized: "group_error_send_failed"))
            case .empty:
                fallthrough
            case .badType:
                fallthrough
            case .unknown:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupInviteAccept {
    func strings() -> (title: String, message: String) {
        switch self {
            case .badInvite:
                return (String(localized: "group_invite_accept_failed"),
                        String(localized: "group_invite_accept_bad_invite"))
            case .initFailed:
                fallthrough
            case .core:
                return (String(localized: "group_invite_accept_failed"),
                        String(localized: "group_join_failed"))
            case .tooLong:
                return (String(localized: "error_title"),
                        String(localized: "error_name_too_long"))
            case .empty:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            case .password:
                return (String(localized: "group_invite_accept_failed"),
                        String(localized: "group_error_password"))
            case .failSend:
                return (String(localized: "group_invite_accept_failed"),
                        String(localized: "group_error_send_failed"))
            case .unknown:
                return (String(localized: "group_invite_accept_failed"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupInviteFriend {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_invite_friend_failed"),
                        String(localized: "group_error_not_found"))
            case .friendNotFound:
                return (String(localized: "group_invite_friend_failed"),
                        String(localized: "group_invite_friend_not_found"))
            case .disconnected:
                return (String(localized: "group_invite_friend_failed"),
                        String(localized: "group_error_disconnected"))
            case .failSend:
                fallthrough
            case .inviteFail:
                return (String(localized: "group_invite_friend_failed"),
                        String(localized: "group_error_send_failed"))
            case .unknown:
                return (String(localized: "group_invite_friend_failed"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupKickPeer {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_peer_kick_failed"),
                        String(localized: "group_error_not_found"))
            case .peerNotFound:
                return (String(localized: "group_peer_kick_failed"),
                        String(localized: "group_peer_kick_not_found"))
            case .permissions:
                return (String(localized: "group_peer_kick_failed"),
                        String(localized: "group_error_permissions"))
            case .failSend:
                return (String(localized: "group_peer_kick_failed"),
                        String(localized: "group_error_send_failed"))
            case .disconnected:
                return (String(localized: "group_peer_kick_failed"),
                        String(localized: "group_error_disconnected"))
            case .unknown:
                return (String(localized: "group_peer_kick_failed"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupSetRole {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_role_set_failed"),
                        String(localized: "group_error_not_found"))
            case .peerNotFound:
                return (String(localized: "group_role_set_failed"),
                        String(localized: "group_peer_kick_not_found"))
            case .permissions:
                return (String(localized: "group_role_set_failed"),
                        String(localized: "group_error_permissions"))
            case .assignment:
                return (String(localized: "group_role_set_failed"),
                        String(localized: "group_role_set_invalid"))
            case .failAction:
                return (String(localized: "group_role_set_failed"),
                        String(localized: "group_error_send_failed"))
            case .self:
                return (String(localized: "group_role_set_failed"),
                        String(localized: "group_role_set_self"))
            case .unknown:
                return (String(localized: "group_role_set_failed"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupTopicSet {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_topic_set_failed"), String(localized: "group_error_not_found"))
            case .tooLong:
                return (String(localized: "group_topic_set_failed"), String(localized: "group_topic_too_long"))
            case .permissions:
                return (String(localized: "group_topic_set_failed"), String(localized: "group_error_permissions"))
            case .failCreate, .failSend:
                return (String(localized: "group_topic_set_failed"), String(localized: "group_error_send_failed"))
            case .disconnected:
                return (String(localized: "group_topic_set_failed"), String(localized: "group_error_disconnected"))
            case .unknown:
                return (String(localized: "group_topic_set_failed"), String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupFounderSetPassword {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_password_set_failed"), String(localized: "group_error_not_found"))
            case .permissions:
                return (String(localized: "group_password_set_failed"), String(localized: "group_error_permissions"))
            case .tooLong:
                return (String(localized: "group_password_set_failed"), String(localized: "group_password_too_long"))
            case .failSend, .malloc:
                return (String(localized: "group_password_set_failed"), String(localized: "group_error_send_failed"))
            case .disconnected:
                return (String(localized: "group_password_set_failed"), String(localized: "group_error_disconnected"))
            case .unknown:
                return (String(localized: "group_password_set_failed"), String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupFounderSetTopicLock {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_topic_lock_set_failed"), String(localized: "group_error_not_found"))
            case .invalid:
                return (String(localized: "group_topic_lock_set_failed"), String(localized: "error_internal_message"))
            case .permissions:
                return (String(localized: "group_topic_lock_set_failed"), String(localized: "group_error_permissions"))
            case .failSet, .failSend:
                return (String(localized: "group_topic_lock_set_failed"), String(localized: "group_error_send_failed"))
            case .disconnected:
                return (String(localized: "group_topic_lock_set_failed"), String(localized: "group_error_disconnected"))
            case .unknown:
                return (String(localized: "group_topic_lock_set_failed"), String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupFounderSetPeerLimit {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_peer_limit_set_failed"), String(localized: "group_error_not_found"))
            case .permissions:
                return (String(localized: "group_peer_limit_set_failed"), String(localized: "group_error_permissions"))
            case .failSet, .failSend:
                return (String(localized: "group_peer_limit_set_failed"), String(localized: "group_error_send_failed"))
            case .disconnected:
                return (String(localized: "group_peer_limit_set_failed"), String(localized: "group_error_disconnected"))
            case .unknown:
                return (String(localized: "group_peer_limit_set_failed"), String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupFounderSetPrivacyState {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_privacy_set_failed"), String(localized: "group_error_not_found"))
            case .permissions:
                return (String(localized: "group_privacy_set_failed"), String(localized: "group_error_permissions"))
            case .failSet, .failSend:
                return (String(localized: "group_privacy_set_failed"), String(localized: "group_error_send_failed"))
            case .disconnected:
                return (String(localized: "group_privacy_set_failed"), String(localized: "group_error_disconnected"))
            case .unknown:
                return (String(localized: "group_privacy_set_failed"), String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupFounderSetVoiceState {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_voice_set_failed"), String(localized: "group_error_not_found"))
            case .permissions:
                return (String(localized: "group_voice_set_failed"), String(localized: "group_error_permissions"))
            case .failSet, .failSend:
                return (String(localized: "group_voice_set_failed"), String(localized: "group_error_send_failed"))
            case .disconnected:
                return (String(localized: "group_voice_set_failed"), String(localized: "group_error_disconnected"))
            case .unknown:
                return (String(localized: "group_voice_set_failed"), String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupSendPrivateMessage {
    func strings() -> (title: String, message: String) {
        switch self {
            case .groupNotFound:
                return (String(localized: "group_private_message_failed"), String(localized: "group_error_not_found"))
            case .peerNotFound:
                return (String(localized: "group_private_message_failed"), String(localized: "group_peer_kick_not_found"))
            case .tooLong:
                return (String(localized: "group_private_message_failed"), String(localized: "group_file_too_large"))
            case .empty:
                return (String(localized: "group_private_message_failed"), String(localized: "error_internal_message"))
            case .badType:
                return (String(localized: "group_private_message_failed"), String(localized: "error_internal_message"))
            case .failSend:
                return (String(localized: "group_private_message_failed"), String(localized: "group_error_send_failed"))
            case .disconnected:
                return (String(localized: "group_private_message_failed"), String(localized: "group_error_disconnected"))
            case .unknown:
                return (String(localized: "group_private_message_failed"), String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTToxErrorGroupSendCustomPacket {
    func strings() -> (title: String, message: String) {
        switch self {
            case .disconnected:
                return (String(localized: "error_title"),
                        String(localized: "group_error_disconnected"))
            case .groupNotFound:
                return (String(localized: "error_title"),
                        String(localized: "group_error_not_found"))
            case .tooLong:
                return (String(localized: "error_title"),
                        String(localized: "group_file_too_large"))
            case .permissions:
                return (String(localized: "error_title"),
                        String(localized: "group_error_permissions"))
            case .empty:
                return (String(localized: "error_title"),
                        String(localized: "group_file_send_failed"))
            case .unknown:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTSendFileError {
    func strings() -> (title: String, message: String) {
        switch self {
            case .internalError:
                fallthrough
            case .cannotReadFile:
                fallthrough
            case .cannotSaveFileToUploads:
                fallthrough
            case .nameTooLong:
                fallthrough
            case .friendNotFound:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            case .friendNotConnected:
                return (String(localized: "error_title"),
                        String(localized: "error_contact_not_connected"))
            case .tooMany:
                return (String(localized: "error_title"),
                        String(localized: "error_too_many_files"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTAcceptFileError {
    func strings() -> (title: String, message: String) {
        switch self {
            case .internalError:
                fallthrough
            case .cannotWriteToFile:
                fallthrough
            case .friendNotFound:
                fallthrough
            case .wrongMessage:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            case .friendNotConnected:
                return (String(localized: "error_title"),
                        String(localized: "error_contact_not_connected"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}

extension OCTFileTransferError {
    func strings() -> (title: String, message: String) {
        switch self {
            case .wrongMessage:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
            @unknown default:
                return (String(localized: "error_title"), String(localized: "error_internal_message"))
        }
    }
}
