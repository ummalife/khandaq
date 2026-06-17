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

enum ErrorHandlerType {
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
}


/**
    Show alert for given error.

    - Parameters:
      - type: Type of error to handle.
      - error: Optional erro to get code from.
      - retryBlock: If set user will be asked to retry request once again.
 */
func handleErrorWithType(_ type: ErrorHandlerType, error: NSError? = nil, retryBlock: (() -> Void)? = nil) {
    switch type {
        case .cannotLoadHTML:
            UIAlertController.showErrorWithMessage(String(localized: "error_internal_message"), retryBlock: retryBlock)
        case .createOCTManager:
            let (title, message) = OCTManagerInitError(rawValue: error!.code)!.strings()
            UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
        case .toxSetInfoCodeName:
            let (title, message) = OCTToxErrorSetInfoCode(rawValue: error!.code)!.nameStrings()
            UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
        case .toxSetInfoCodeStatusMessage:
            let (title, message) = OCTToxErrorSetInfoCode(rawValue: error!.code)!.statusMessageStrings()
            UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
        case .toxAddFriend:
            if let error = error, let friendAddError = OCTToxErrorFriendAdd(rawValue: error.code) {
                let (title, message) = friendAddError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                let message = error?.localizedDescription ?? String(localized: "error_internal_message")
                UIAlertController.showWithTitle(String(localized: "error_title"), message: message, retryBlock: retryBlock)
            }
        case .removeFriend:
            let (title, message) = OCTToxErrorFriendDelete(rawValue: error!.code)!.strings()
            UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
        case .callToChat:
            let (title, message) = OCTToxAVErrorCall(rawValue: error!.code)!.strings()
            UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
        case .exportProfile:
            UIAlertController.showWithTitle(String(localized: "error_title"), message: error!.localizedDescription, retryBlock: retryBlock)
        case .deleteProfile:
            UIAlertController.showWithTitle(String(localized: "error_title"), message: error!.localizedDescription, retryBlock: retryBlock)
        case .passwordIsEmpty:
            UIAlertController.showWithTitle(String(localized: "password_is_empty_error"), retryBlock: retryBlock)
        case .wrongOldPassword:
            UIAlertController.showWithTitle(String(localized: "wrong_old_password"), retryBlock: retryBlock)
        case .passwordsDoNotMatch:
            UIAlertController.showWithTitle(String(localized: "passwords_do_not_match"), retryBlock: retryBlock)
        case .passwordTooShort:
            UIAlertController.showWithTitle(String(localized: "password_too_short_error"), retryBlock: retryBlock)
        case .passwordTooWeak:
            UIAlertController.showWithTitle(String(localized: "password_too_weak_error"), retryBlock: retryBlock)
        case .answerCall:
            if let error = error, let answerError = OCTToxAVErrorAnswer(rawValue: error.code) {
                let (title, message) = answerError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            } else {
                UIAlertController.showWithTitle(
                    String(localized: "error_title"),
                    message: String(localized: "call_error_microphone_denied"),
                    retryBlock: retryBlock
                )
            }
        case .routeAudioToSpeaker:
            UIAlertController.showWithTitle(String(localized: "error_title"), message: String(localized: "error_internal_message"), retryBlock: retryBlock)
        case .enableVideoSending:
            UIAlertController.showWithTitle(String(localized: "error_title"), message: String(localized: "error_internal_message"), retryBlock: retryBlock)
        case .callSwitchCamera:
            UIAlertController.showWithTitle(String(localized: "error_title"), message: String(localized: "error_internal_message"), retryBlock: retryBlock)
        case .convertImageToPNG:
            UIAlertController.showWithTitle(String(localized: "error_title"), message: String(localized: "change_avatar_error_convert_image"), retryBlock: retryBlock)
        case .changeAvatar:
            let (title, message) = OCTSetUserAvatarError(rawValue: error!.code)!.strings()
            UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
        case .sendMessageToFriend:
            if let error = error, error.domain == "OCTSubmanagerGroupsErrorDomain", error.code == 1 {
                UIAlertController.showWithTitle(String(localized: "error_title"),
                                                message: String(localized: "group_send_observer_role"),
                                                retryBlock: retryBlock)
            }
            else if let error = error, let groupError = OCTToxErrorGroupSendMessage(rawValue: error.code) {
                let (title, message) = groupError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else if let error = error, let (title, message) = OCTToxErrorFriendSendMessage(rawValue: error.code)?.strings() {
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "error_title"), message: String(localized: "error_internal_message"), retryBlock: retryBlock)
            }
        case .acceptGroupInvite:
            if let error = error, let inviteError = OCTToxErrorGroupInviteAccept(rawValue: error.code) {
                let (title, message) = inviteError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_invite_accept_failed"), message: nil, retryBlock: retryBlock)
            }
        case .inviteFriendToGroup:
            if let error = error, let inviteError = OCTToxErrorGroupInviteFriend(rawValue: error.code) {
                let (title, message) = inviteError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_invite_friend_failed"), message: nil, retryBlock: retryBlock)
            }
        case .kickGroupPeer:
            if let error = error, let kickError = OCTToxErrorGroupKickPeer(rawValue: error.code) {
                let (title, message) = kickError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_peer_kick_failed"), message: nil, retryBlock: retryBlock)
            }
        case .leaveGroup:
            UIAlertController.showWithTitle(String(localized: "group_leave_failed"), message: nil, retryBlock: retryBlock)
        case .setGroupPeerRole:
            if let error = error, let roleError = OCTToxErrorGroupSetRole(rawValue: error.code) {
                let (title, message) = roleError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_role_set_failed"), message: nil, retryBlock: retryBlock)
            }
        case .setGroupTopic:
            if let error = error, let topicError = OCTToxErrorGroupTopicSet(rawValue: error.code) {
                let (title, message) = topicError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_topic_set_failed"), message: nil, retryBlock: retryBlock)
            }
        case .setGroupSelfPeerName:
            UIAlertController.showWithTitle(String(localized: "group_my_peer_name_set_failed"), message: nil, retryBlock: retryBlock)
        case .setGroupPassword:
            if let error = error, let passwordError = OCTToxErrorGroupFounderSetPassword(rawValue: error.code) {
                let (title, message) = passwordError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_password_set_failed"), message: nil, retryBlock: retryBlock)
            }
        case .setGroupTopicLock:
            if let error = error, let lockError = OCTToxErrorGroupFounderSetTopicLock(rawValue: error.code) {
                let (title, message) = lockError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_topic_lock_set_failed"), message: nil, retryBlock: retryBlock)
            }
        case .setGroupPrivacy:
            if let error = error, let privacyError = OCTToxErrorGroupFounderSetPrivacyState(rawValue: error.code) {
                let (title, message) = privacyError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_privacy_set_failed"), message: nil, retryBlock: retryBlock)
            }
        case .setGroupVoice:
            if let error = error, let voiceError = OCTToxErrorGroupFounderSetVoiceState(rawValue: error.code) {
                let (title, message) = voiceError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_voice_set_failed"), message: nil, retryBlock: retryBlock)
            }
        case .setGroupPeerLimit:
            if let error = error, let limitError = OCTToxErrorGroupFounderSetPeerLimit(rawValue: error.code) {
                let (title, message) = limitError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_peer_limit_set_failed"), message: String(localized: "group_peer_limit_invalid"), retryBlock: retryBlock)
            }
        case .sendGroupPrivateMessage:
            if let error = error, let privateError = OCTToxErrorGroupSendPrivateMessage(rawValue: error.code) {
                let (title, message) = privateError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "group_private_message_failed"), message: nil, retryBlock: retryBlock)
            }
        case .sendFileToFriend:
            if let error = error, error.domain == "OCTSubmanagerGroupsErrorDomain", error.code == 1 {
                UIAlertController.showWithTitle(String(localized: "error_title"),
                                                message: String(localized: "group_send_observer_role"),
                                                retryBlock: retryBlock)
            }
            else if let error = error, error.domain == "OCTNgcGroupFileTransferErrorDomain" {
                if error.code == 4 {
                    // cancelled — no alert
                }
                else if error.code == 1 {
                    UIAlertController.showWithTitle(String(localized: "error_title"),
                                                    message: String(localized: "group_file_too_large"),
                                                    retryBlock: retryBlock)
                }
                else {
                    UIAlertController.showWithTitle(String(localized: "group_file_send_failed"), message: nil, retryBlock: retryBlock)
                }
            }
            else if let error = error, let sendError = OCTSendFileError(rawValue: error.code) {
                let (title, message) = sendError.strings()
                UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
            }
            else {
                UIAlertController.showWithTitle(String(localized: "error_title"), message: String(localized: "error_internal_message"), retryBlock: retryBlock)
            }
        case .acceptIncomingFile:
            let (title, message) = OCTAcceptFileError(rawValue: error!.code)!.strings()
            UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
        case .cancelFileTransfer:
            let (title, message) = OCTFileTransferError(rawValue: error!.code)!.strings()
            UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
        case .pauseFileTransfer:
            let (title, message) = OCTFileTransferError(rawValue: error!.code)!.strings()
            UIAlertController.showWithTitle(title, message: message, retryBlock: retryBlock)
        case .pinLogOut:
            UIAlertController.showWithTitle(String(localized: "error_title"), message: String(localized: "pin_logout_message"), retryBlock: retryBlock)
    }
}

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
        }
    }
}

extension OCTToxErrorFriendDelete {
    func strings() -> (title: String, message: String) {
        switch self {
            case .notFound:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
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
        }
    }
}

extension OCTSetUserAvatarError {
    func strings() -> (title: String, message: String) {
        switch self {
            case .tooBig:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
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
        }
    }
}

extension OCTFileTransferError {
    func strings() -> (title: String, message: String) {
        switch self {
            case .wrongMessage:
                return (String(localized: "error_title"),
                        String(localized: "error_internal_message"))
        }
    }
}
