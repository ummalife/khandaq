// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import MobileCoreServices
import os

private struct Constants {
    static let MaxFileSizeWiFi: OCTToxFileSize = 200 * 1024 * 1024
    static let MaxFileSizeWWAN: OCTToxFileSize = 200 * 1024 * 1024
}

class AutomationCoordinator: NSObject {
    fileprivate weak var submanagerFiles: OCTSubmanagerFiles!

    fileprivate var fileMessagesToken: RLMNotificationToken?
    fileprivate let userDefaults = UserDefaultsManager()
    fileprivate let reachability = Reach()

    init(submanagerObjects: OCTSubmanagerObjects, submanagerFiles: OCTSubmanagerFiles) {
        self.submanagerFiles = submanagerFiles

        super.init()

        let predicate = NSPredicate(format: "senderUniqueIdentifier != nil AND messageFile != nil")
        let results = submanagerObjects.messages(predicate: predicate)
        fileMessagesToken = results.addNotificationBlock { [unowned self] change in
            switch change {
                case .initial:
                    break
                case .update(let results, _, let insertions, _):
                    guard let results = results else {
                        break
                    }

                    for index in insertions {
                        let message = results[index]
                        self.proceedNewFileMessage(message)
                    }
                case .error(let error):
                    os_log("AutomationCoordinator:realm:error:%{public}@", String(describing: error))
            }
        }
    }

    // KHANDAQ (leak): invalidate the Realm observer on teardown. Without this the token (and its
    // [unowned self] block) leaked on every session logout, and a file message arriving mid-dealloc
    // could fire on a dead instance and crash.
    deinit {
        fileMessagesToken?.invalidate()
    }
}

extension AutomationCoordinator: CoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
        // nop
    }
}

private extension AutomationCoordinator {
    func proceedNewFileMessage(_ message: OCTMessageAbstract) {
        let usingWiFi = self.usingWiFi()
        os_log("AutomationCoordinator:usingWiFi=%d", usingWiFi)

        // KHANDAQ (#156): voice notes are part of core messaging (Telegram-style) — fetched from
        // accepted friends even when the attachment-autodownload setting says Never, which is its
        // default and would otherwise leave incoming voice bubbles permanently unloadable.
        //
        // KHANDAQ (internal audit 2026-08-22): the test is now the strict one, and the exemption is
        // narrower than it was.
        //
        // It used to accept any name CONTAINING ".file.m4a" up to 20 MB, so `photo.file.m4a.jpg`
        // qualified: a contact could push arbitrary content of any type onto the device, over
        // cellular, while the user's setting said Never. The name comes from the sender and decides
        // nothing on its own any more — the suffix must be exactly the one the sending side writes,
        // and the size must be one a voice note actually has.
        //
        // The exemption also no longer overrides "Wi-Fi only". Never is about consent and is worth
        // overriding for core messaging; Wi-Fi only is about the user's data plan, and silently
        // spending someone's cellular allowance is not a thing to do on their behalf.
        let isVoiceNote = VoiceMessageHelper.isAutoFetchableVoiceNote(
            fileName: message.messageFile!.fileName,
            fileSize: message.messageFile!.fileSize)

        switch userDefaults.autodownloadImages {
            case .Never:
                if !isVoiceNote {
                    return
                }
            case .UsingWiFi:
                if !usingWiFi {
                    return
                }
            case .Always:
                break
        }

        // HINT: now we apply autodownload to all files, not only images
        // if !UTTypeConformsTo(message.messageFile!.fileUTI as CFString? ?? "" as CFString, kUTTypeImage) {
        //    // download images only
        //    return
        // }

        // skip too large files
        if usingWiFi {
            if message.messageFile!.fileSize > Constants.MaxFileSizeWiFi {
                return
            }
        }
        else {
            if message.messageFile!.fileSize > Constants.MaxFileSizeWWAN {
                return
            }
        }

        // workaround for deadlock in objcTox https://github.com/Antidote-for-Tox/objcTox/issues/51
        let delayTime = DispatchTime.now() + Double(Int64(0.0 * Double(NSEC_PER_SEC))) / Double(NSEC_PER_SEC)
        DispatchQueue.main.asyncAfter(deadline: delayTime) { [weak self] in
            self?.submanagerFiles.acceptFileTransfer(message, failureBlock: nil)
        }
    }

    func usingWiFi() -> Bool
    {
        switch reachability.connectionStatus() {
            case .offline:
                return false
            case .unknown:
                return false
            case .online(let type):
                switch type {
                    case .wwan:
                        return false
                    case .wiFi:
                        return true
                }
        }
    }
}
