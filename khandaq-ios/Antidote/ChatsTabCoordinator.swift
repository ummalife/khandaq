// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol ChatsTabCoordinatorDelegate: class {
    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, chatWillAppear chat: OCTChat)
    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, chatWillDisapper chat: OCTChat)
    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, callToChat chat: OCTChat, enableVideo: Bool)
}

class ChatsTabCoordinator: ActiveSessionNavigationCoordinator {
    weak var delegate: ChatsTabCoordinatorDelegate?
    weak var externalPresentingController: UIViewController?
    var chatDisplayHandler: ((OCTChat, Bool) -> Void)?

    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate weak var submanagerChats: OCTSubmanagerChats!
    fileprivate weak var submanagerGroups: OCTSubmanagerGroups!
    fileprivate weak var submanagerFiles: OCTSubmanagerFiles!
    fileprivate weak var submanagerUser: OCTSubmanagerUser!
    fileprivate weak var submanagerFriends: OCTSubmanagerFriends!

    init(theme: Theme,
         submanagerObjects: OCTSubmanagerObjects,
         submanagerChats: OCTSubmanagerChats,
         submanagerGroups: OCTSubmanagerGroups,
         submanagerFiles: OCTSubmanagerFiles,
         submanagerUser: OCTSubmanagerUser,
         submanagerFriends: OCTSubmanagerFriends) {
        self.submanagerObjects = submanagerObjects
        self.submanagerChats = submanagerChats
        self.submanagerGroups = submanagerGroups
        self.submanagerFiles = submanagerFiles
        self.submanagerUser = submanagerUser
        self.submanagerFriends = submanagerFriends

        super.init(theme: theme)
    }

    override func startWithOptions(_ options: CoordinatorOptions?) {
        let controller = ChatListController(
                theme: theme,
                submanagerChats: submanagerChats,
                submanagerGroups: submanagerGroups,
                submanagerObjects: submanagerObjects)
        controller.delegate = self

        installRootViewController(controller)
    }

    func showChat(_ chat: OCTChat, animated: Bool) {
        if let chatDisplayHandler = chatDisplayHandler {
            chatDisplayHandler(chat, animated)
            return
        }

        if chat.isGroup {
            if let top = navigationController.topViewController as? ChatGroupController, top.chat == chat {
                return
            }

            let controller = ChatGroupController(
                    theme: theme,
                    chat: chat,
                    submanagerGroups: submanagerGroups,
                    submanagerObjects: submanagerObjects,
                    submanagerFriends: submanagerFriends,
                    submanagerChats: submanagerChats,
                    delegate: self)

            navigationController.popToRootViewController(animated: false)
            navigationController.pushViewController(controller, animated: animated)
            return
        }

        if let top = navigationController.topViewController as? ChatPrivateController {
            if top.chat == chat {
                return
            }
        }

        let controller = ChatPrivateController(
                theme: theme,
                chat: chat,
                submanagerChats: submanagerChats,
                submanagerObjects: submanagerObjects,
                submanagerFiles: submanagerFiles,
                delegate: self)

        navigationController.popToRootViewController(animated: false)
        navigationController.pushViewController(controller, animated: animated)
    }

    func activeChatController() -> ChatPrivateController? {
        return navigationController.topViewController as? ChatPrivateController
    }
}

extension ChatsTabCoordinator: ChatListControllerDelegate {
    func chatListController(_ controller: ChatListController, didSelectChat chat: OCTChat) {
        showChat(chat, animated: true)
    }

    func chatListController(_ controller: ChatListController, didRequestGroupInfo chat: OCTChat) {
        showGroupInfo(chat, animated: true)
    }

    func chatListControllerDidRequestCreateGroup(_ controller: ChatListController) {
        presentCreateGroupAlert(from: controller, isPrivate: false)
    }

    func chatListControllerDidRequestCreatePrivateGroup(_ controller: ChatListController) {
        presentCreateGroupAlert(from: controller, isPrivate: true)
    }

    func chatListControllerDidRequestJoinGroup(_ controller: ChatListController) {
        presentJoinGroupAlert(from: controller)
    }

    func chatListControllerDidRequestScanGroupQR(_ controller: ChatListController) {
        presentScanGroupQR(from: controller)
    }
}

extension ChatsTabCoordinator: ChatPrivateControllerDelegate {
    func chatPrivateControllerWillAppear(_ controller: ChatPrivateController) {
        delegate?.chatsTabCoordinator(self, chatWillAppear: controller.chat)
    }

    func chatPrivateControllerWillDisappear(_ controller: ChatPrivateController) {
        delegate?.chatsTabCoordinator(self, chatWillDisapper: controller.chat)
    }

    func chatPrivateControllerCallToChat(_ controller: ChatPrivateController, enableVideo: Bool) {
        delegate?.chatsTabCoordinator(self, callToChat: controller.chat, enableVideo: enableVideo)
    }

    func chatPrivateControllerShowQuickLookController(
            _ controller: ChatPrivateController,
            dataSource: QuickLookPreviewControllerDataSource,
            selectedIndex: Int)
    {
        // KHANDAQ (Figma / #204-C): custom media gallery for image/video; a non-media tap
        // (PDF, doc…) has no gallery index → fall through to QuickLook, which previews everything.
        if let fp = dataSource as? FilePreviewControllerDataSource,
           let start = fp.galleryStartIndex(forMessageIndex: selectedIndex) {
            let items = fp.galleryItems(myName: submanagerUser.userName() ?? "")
            if !items.isEmpty {
                let gallery = MediaGalleryViewController(items: items, startIndex: start)
                navigationController.present(gallery, animated: true, completion: nil)
                return
            }
        }

        let controller = QuickLookPreviewController()
        controller.dataSource = dataSource
        controller.dataSourceStorage = dataSource
        controller.currentPreviewItemIndex = selectedIndex

        navigationController.present(controller, animated: true, completion: nil)
    }

    // KHANDAQ (Figma): chat header tap → friend profile.
    func chatPrivateControllerShowFriendProfile(_ controller: ChatPrivateController, forFriend friend: OCTFriend) {
        let card = FriendCardController(theme: theme, friend: friend, submanagerObjects: submanagerObjects)
        card.delegate = self

        navigationController.pushViewController(card, animated: true)
    }
}

extension ChatsTabCoordinator: FriendCardControllerDelegate {
    func friendCardControllerChangeNickname(_ controller: FriendCardController, forFriend friend: OCTFriend) {
        let title = String(localized: "nickname")
        let defaultValue = friend.nickname

        let textController = TextEditController(theme: theme, title: title, defaultValue: defaultValue, changeTextHandler: {
            [unowned self] newValue -> Void in
            self.submanagerObjects.change(friend, nickname: newValue)

        }, userFinishedEditing: { [unowned self] in
            self.navigationController.popViewController(animated: true)
        })

        navigationController.pushViewController(textController, animated: true)
    }

    func friendCardControllerOpenChat(_ controller: FriendCardController, forFriend friend: OCTFriend) {
        // We navigated here FROM the chat — pop back to it.
        navigationController.popViewController(animated: true)
    }

    func friendCardControllerCall(_ controller: FriendCardController, toFriend friend: OCTFriend) {
        guard let chat = submanagerChats.getOrCreateChat(with: friend) else {
            return
        }
        delegate?.chatsTabCoordinator(self, callToChat: chat, enableVideo: false)
    }

    func friendCardControllerVideoCall(_ controller: FriendCardController, toFriend friend: OCTFriend) {
        guard let chat = submanagerChats.getOrCreateChat(with: friend) else {
            return
        }
        delegate?.chatsTabCoordinator(self, callToChat: chat, enableVideo: true)
    }
}

extension ChatsTabCoordinator: ChatGroupControllerDelegate {
    func chatGroupControllerWillAppear(_ controller: ChatGroupController) {
        delegate?.chatsTabCoordinator(self, chatWillAppear: controller.chat)
    }

    func chatGroupControllerWillDisappear(_ controller: ChatGroupController) {
        delegate?.chatsTabCoordinator(self, chatWillDisapper: controller.chat)
    }

    func chatGroupControllerDidRequestGroupInfo(_ controller: ChatGroupController) {
        showGroupInfo(controller.chat, animated: true)
    }

    func chatGroupController(_ controller: ChatGroupController, didRequestOpenFriend friend: OCTFriend) {
        guard let chat = submanagerChats.getOrCreateChat(with: friend) else {
            return
        }

        showChat(chat, animated: true)
    }
}

extension ChatsTabCoordinator {
    func joinGroupFromExternal(chatIdHex: String, password: String? = nil) {
        if let existing = existingGroupChat(for: chatIdHex), existing.groupNumber >= 0 {
            showChat(existing, animated: true)
            return
        }

        if password != nil {
            joinGroup(withChatIdHex: chatIdHex, password: password)
            return
        }

        presentJoinGroupPasswordAlert(chatIdHex: chatIdHex)
    }
}

private extension ChatsTabCoordinator {
    func showGroupInfo(_ chat: OCTChat, animated: Bool) {
        let infoController = GroupInfoController(
                theme: theme,
                chat: chat,
                submanagerGroups: submanagerGroups,
                submanagerObjects: submanagerObjects,
                submanagerChats: submanagerChats,
                submanagerFiles: submanagerFiles,
                submanagerFriends: submanagerFriends)
        navigationController.pushViewController(infoController, animated: animated)
    }

    func defaultPeerName() -> String {
        if let name = submanagerUser.userName(), !name.isEmpty {
            return name
        }
        // KHANDAQ (#15): the Tox self-name can be empty (e.g. an account created without an explicit
        // username), in which case we used to publish the literal app name "Khandaq" as our NGC peer
        // name — so every group member saw "Khandaq" and messages were attributed to "Khandaq"
        // instead of the real profile. Fall back to the active profile name before the app name.
        if let profile = UserDefaultsManager().lastActiveProfile, !profile.isEmpty {
            return profile
        }
        return "Khandaq"
    }

    func presentCreateGroupAlert(from controller: UIViewController, isPrivate: Bool) {
        submanagerGroups.setGroupBackgroundWorkPaused(true)

        let resumeBackgroundWork: () -> Void = { [weak self] in
            self?.submanagerGroups.setGroupBackgroundWorkPaused(false)
        }

        // KHANDAQ (Figma): two-step create — select contacts first, then the name/password form;
        // selected friends are invited right after the group is created.
        let navigation = PortraitNavigationController()

        let contactsController = GroupCreateContactsController(theme: theme, submanagerObjects: submanagerObjects) {
            [weak self, weak navigation] selectedFriends in
            guard let self = self else {
                return
            }

            let createController = GroupCreateNameController(theme: self.theme, isPrivate: isPrivate) { [weak self] groupName, password in
                resumeBackgroundWork()
                self?.createGroupAndOpen(isPrivate: isPrivate, groupName: groupName, password: password, inviteFriends: selectedFriends)
            }
            createController.onCancel = resumeBackgroundWork
            navigation?.pushViewController(createController, animated: true)
        }
        contactsController.onCancel = resumeBackgroundWork

        navigation.setViewControllers([contactsController], animated: false)
        navigation.modalPresentationStyle = .formSheet

        if #available(iOS 15.0, *) {
            if let sheet = navigation.sheetPresentationController {
                sheet.detents = [.large()]
                sheet.prefersGrabberVisible = true
            }
        }

        controller.present(navigation, animated: true, completion: nil)
    }

    func createGroupAndOpen(isPrivate: Bool, groupName: String, password: String?, inviteFriends: [OCTFriend] = []) {
        let hud = JGProgressHUD(style: .dark)
        hud?.textLabel.text = String(localized: "group_create_in_progress")
        hud?.show(in: navigationController.view)

        let peerName = defaultPeerName()
        let completion: (OCTToxGroupNumber, Error?) -> Void = { [weak self] groupNumber, error in
            guard let self = self else {
                hud?.dismiss(animated: false)
                return
            }

            hud?.dismiss(animated: true)

            if let error = error as NSError? {
                UIAlertController.showErrorWithMessage(error.localizedDescription, retryBlock: nil)
                return
            }

            if groupNumber == kOCTToxGroupNumberFailure {
                UIAlertController.showErrorWithMessage(
                        isPrivate ? String(localized: "group_create_private_failed") : String(localized: "group_create_failed"),
                        retryBlock: nil)
                return
            }

            guard let chat = self.submanagerGroups.chat(forGroupNumber: groupNumber) else {
                UIAlertController.showErrorWithMessage(String(localized: "group_create_failed"), retryBlock: nil)
                return
            }

            if isPrivate, let password = password, !password.isEmpty {
                do {
                    try self.submanagerGroups.setGroupPassword(password, for: chat)
                }
                catch let passwordError as NSError {
                    handleErrorWithType(.setGroupPassword, error: passwordError)
                }
            }

            // KHANDAQ (Figma): invite the contacts selected on step 1 (best-effort — an offline
            // friend's one-shot invite can fail; they can be re-invited from the group later).
            for friend in inviteFriends where friend.friendNumber >= 0 {
                _ = try? self.submanagerGroups.inviteFriend(withNumber: friend.friendNumber,
                                                            toGroupNumber: groupNumber)
            }

            DispatchQueue.main.async {
                self.showChat(chat, animated: true)
            }
        }

        if isPrivate {
            submanagerGroups.createPrivateGroup(withName: groupName, peerName: peerName, completion: completion)
        }
        else {
            submanagerGroups.createPublicGroup(withName: groupName, peerName: peerName, completion: completion)
        }
    }

    func presentJoinGroupAlert(from controller: UIViewController) {
        let clipboardGroupId = GroupJoinHelper.readGroupIdFromClipboard()

        let alert = UIAlertController(title: String(localized: "group_join_by_id"), message: String(localized: "group_join_hint"), preferredStyle: .alert)
        alert.addTextField { field in
            field.placeholder = String(localized: "group_chat_id_placeholder")
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
            if let clipboardGroupId = clipboardGroupId {
                field.text = clipboardGroupId
            }
        }
        alert.addTextField { field in
            field.placeholder = String(localized: "group_password_placeholder")
            field.isSecureTextEntry = true
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
        }

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "group_join_action"), style: .default) { [unowned self] _ in
            let chatIdRaw = alert.textFields?.first?.text ?? ""
            let passwordRaw = alert.textFields?.last?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""

            guard let chatIdHex = self.normalizedGroupChatIdHex(from: chatIdRaw) else {
                UIAlertController.showErrorWithMessage(String(localized: "group_join_invalid_id"), retryBlock: nil)
                return
            }

            let password = passwordRaw.isEmpty ? nil : passwordRaw
            self.joinGroup(withChatIdHex: chatIdHex, password: password)
        })

        controller.present(alert, animated: true, completion: nil)
    }

    func presentScanGroupQR(from controller: UIViewController) {
        let scanner = QRScannerController(theme: theme)

        scanner.didScanStringsBlock = { [unowned self, scanner] strings in
            let chatIdHex = strings.compactMap { self.normalizedGroupChatIdHex(from: $0) }.first

            if let chatIdHex = chatIdHex {
                self.navigationController.dismiss(animated: true) {
                    self.presentJoinGroupPasswordAlert(chatIdHex: chatIdHex)
                }
            }
            else {
                scanner.pauseScanning = true

                let alert = UIAlertController(title: String(localized: "error_title"),
                                              message: String(localized: "group_join_invalid_qr"),
                                              preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: String(localized: "error_ok_button"), style: .default) { _ in
                    scanner.pauseScanning = false
                })
                scanner.present(alert, animated: true, completion: nil)
            }
        }

        scanner.cancelBlock = { [unowned self] in
            self.navigationController.dismiss(animated: true, completion: nil)
        }

        let scannerNavCon = UINavigationController(rootViewController: scanner)
        navigationController.present(scannerNavCon, animated: true, completion: nil)
    }

    func presentJoinGroupPasswordAlert(chatIdHex: String) {
        let alert = UIAlertController(title: String(localized: "group_join_by_id"),
                                      message: String(localized: "group_join_password_hint"),
                                      preferredStyle: .alert)
        alert.addTextField { field in
            field.placeholder = String(localized: "group_password_placeholder")
            field.isSecureTextEntry = true
            field.autocapitalizationType = .none
            field.autocorrectionType = .no
        }

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "group_join_action"), style: .default) { [unowned self] _ in
            let passwordRaw = alert.textFields?.first?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let password = passwordRaw.isEmpty ? nil : passwordRaw
            self.joinGroup(withChatIdHex: chatIdHex, password: password)
        })

        alertPresentingController().present(alert, animated: true, completion: nil)
    }

    func alertPresentingController() -> UIViewController {
        if let external = externalPresentingController {
            return external
        }
        return navigationController.topViewController ?? navigationController
    }

    func joinGroup(withChatIdHex chatIdHex: String, password: String?) {
        var error: NSError?
        let groupNumber = submanagerGroups.joinGroup(withChatIdHex: chatIdHex, peerName: defaultPeerName(), password: password, error: &error)

        if let error = error {
            UIAlertController.showErrorWithMessage(error.localizedDescription, retryBlock: nil)
            return
        }

        if groupNumber == kOCTToxGroupNumberFailure {
            UIAlertController.showErrorWithMessage(String(localized: "group_join_failed"), retryBlock: nil)
            return
        }

        submanagerGroups.syncGroupsWithTox()

        guard let chat = submanagerGroups.chat(forGroupNumber: groupNumber) else {
            UIAlertController.showErrorWithMessage(String(localized: "group_join_failed"), retryBlock: nil)
            return
        }

        submanagerGroups.refreshPeers(for: chat)
        showChat(chat, animated: true)
    }

    func normalizedGroupChatIdHex(from string: String) -> String? {
        return GroupJoinHelper.normalizedGroupChatIdHex(from: string)
    }

    func existingGroupChat(for chatIdHex: String) -> OCTChat? {
        let chats = submanagerObjects.chats()
        for index in 0..<chats.count {
            let chat = chats[index]
            if chat.isGroup, chat.groupChatIdHex?.uppercased() == chatIdHex.uppercased() {
                return chat
            }
        }
        return nil
    }
}
