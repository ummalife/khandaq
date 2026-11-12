// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol ChatsTabCoordinatorDelegate: class {
    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, chatWillAppear chat: OCTChat)
    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, chatWillDisapper chat: OCTChat)
    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, callToChat chat: OCTChat, enableVideo: Bool)
    // The Air screen (phone): what used to be the other tabs.
    func chatsTabCoordinatorShowProfile(_ coordinator: ChatsTabCoordinator)
    func chatsTabCoordinatorShowSettings(_ coordinator: ChatsTabCoordinator)
    func chatsTabCoordinator(_ coordinator: ChatsTabCoordinator, showAddContactWithAddress address: String?)
}

class ChatsTabCoordinator: ActiveSessionNavigationCoordinator {
    weak var delegate: ChatsTabCoordinatorDelegate?
    weak var externalPresentingController: UIViewController?
    var chatDisplayHandler: ((OCTChat, Bool) -> Void)?
    /// The Air screen at the root of the stack, once started.
    private(set) weak var listController: ChatListController?

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
         submanagerFriends: OCTSubmanagerFriends,
         navigationController: UINavigationController? = nil) {
        self.submanagerObjects = submanagerObjects
        self.submanagerChats = submanagerChats
        self.submanagerGroups = submanagerGroups
        self.submanagerFiles = submanagerFiles
        self.submanagerUser = submanagerUser
        self.submanagerFriends = submanagerFriends

        if let navigationController = navigationController {
            super.init(theme: theme, navigationController: navigationController)
        }
        else {
            super.init(theme: theme)
        }
    }

    override func startWithOptions(_ options: CoordinatorOptions?) {
        let controller = ChatListController(
                theme: theme,
                submanagerChats: submanagerChats,
                submanagerGroups: submanagerGroups,
                submanagerObjects: submanagerObjects,
                submanagerFriends: submanagerFriends,
                submanagerUser: submanagerUser)
        controller.delegate = self
        listController = controller

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
        presentJoinGroupSheet(key: nil, from: controller)
    }

    func chatListController(_ controller: ChatListController, didRequestJoinGroupWithKey key: String) {
        presentJoinGroupSheet(key: key, from: controller)
    }

    func chatListControllerDidRequestScanGroupQR(_ controller: ChatListController) {
        presentJoinGroupSheet(key: nil, from: controller)
    }

    func chatListControllerDidRequestProfile(_ controller: ChatListController) {
        delegate?.chatsTabCoordinatorShowProfile(self)
    }

    func chatListControllerDidRequestSettings(_ controller: ChatListController) {
        delegate?.chatsTabCoordinatorShowSettings(self)
    }

    func chatListController(_ controller: ChatListController, didRequestAddContactWithAddress address: String?) {
        delegate?.chatsTabCoordinator(self, showAddContactWithAddress: address)
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
        // (PDF, doc...) has no gallery index, so fall through to QuickLook, which previews everything.
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

    // KHANDAQ (user request 17.08): the chat's attachments, split into files / audio / voice.
    func chatPrivateControllerShowAttachments(_ controller: ChatPrivateController, forChat chat: OCTChat) {
        let attachments = ChatAttachmentsController(theme: theme, chat: chat,
                                                    submanagerObjects: submanagerObjects)
        navigationController.pushViewController(attachments, animated: true)
    }

    // KHANDAQ (Figma): a tap on the chat header opens the friend profile.
    func chatPrivateControllerShowFriendProfile(_ controller: ChatPrivateController, forFriend friend: OCTFriend) {
        let card = FriendCardController(theme: theme, friend: friend, submanagerObjects: submanagerObjects)
        card.delegate = self

        navigationController.pushViewController(card, animated: true)
    }

    // KHANDAQ (redesign stage 2): "Remove" in the dossier. The conversation closes first; the contact
    // and the chat go once it is off screen, so it never draws them half removed.
    func chatPrivateControllerRemoveContact(_ controller: ChatPrivateController, friend: OCTFriend) {
        if let index = navigationController.viewControllers.firstIndex(of: controller), index > 0 {
            navigationController.popToViewController(navigationController.viewControllers[index - 1], animated: true)
        }
        AirContactRemoval.afterTransition(of: navigationController) { [weak self] in
            guard let self = self else {
                return
            }
            AirContactRemoval.remove(friend, submanagerChats: self.submanagerChats, submanagerFriends: self.submanagerFriends)
        }
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
        // We navigated here FROM the chat: pop back to it.
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
            switch joinGroup(withChatIdHex: chatIdHex, password: password) {
                case .joined(let chat):
                    showChat(chat, animated: true)
                case .refused(let words):
                    UIAlertController.showErrorWithMessage(words, retryBlock: nil)
            }
            return
        }

        // A group link or QR code from elsewhere: the join sheet with the key in it, so the password
        // and how joining works are said before anything is sent.
        presentJoinGroupSheet(key: chatIdHex, from: alertPresentingController())
    }
}

private extension ChatsTabCoordinator {
    /// KHANDAQ (redesign stage 3, E14): the group dossier; the old settings screen opens from its
    /// "Group settings" row.
    func showGroupInfo(_ chat: OCTChat, animated: Bool) {
        let dossier = AirGroupDossierController(
                theme: theme,
                chat: chat,
                submanagerGroups: submanagerGroups,
                submanagerObjects: submanagerObjects,
                submanagerChats: submanagerChats,
                submanagerFiles: submanagerFiles,
                submanagerFriends: submanagerFriends)
        dossier.onOpenFriend = { [weak self] friend in
            guard let self = self, let chat = self.submanagerChats.getOrCreateChat(with: friend) else {
                return
            }
            self.showChat(chat, animated: true)
        }
        navigationController.pushViewController(dossier, animated: animated)
    }

    func defaultPeerName() -> String {
        if let name = submanagerUser.userName(), !name.isEmpty {
            return name
        }
        // KHANDAQ (#15): the Tox self-name can be empty (e.g. an account created without an explicit
        // username), in which case we used to publish the literal app name "Khandaq" as our NGC peer
        // name, so every group member saw "Khandaq" and messages were attributed to "Khandaq"
        // instead of the real profile. Fall back to the active profile name before the app name.
        if let profile = UserDefaultsManager().lastActiveProfile, !profile.isEmpty {
            return profile
        }
        return "Khandaq"
    }

    /// KHANDAQ (redesign stage 3, E15): "New group" as a sheet the height of the screen: the name, who can
    /// join, the contacts to invite, then "Create group". Who can join is chosen on the sheet, so both
    /// entries of the old menu open the same sheet with their choice preselected. A password is set later
    /// in the group settings: everyone invited would need it too.
    func presentCreateGroupAlert(from controller: UIViewController, isPrivate: Bool) {
        submanagerGroups.setGroupBackgroundWorkPaused(true)

        let candidates = AirInviteCandidates.load(from: submanagerObjects, memberKeys: [])
        let newGroup = AirNewGroupController(candidates: candidates, inviteOnly: isPrivate)
        let navigation = PortraitNavigationController(rootViewController: newGroup)
        newGroup.onCancel = { [weak self, weak navigation] in
            self?.submanagerGroups.setGroupBackgroundWorkPaused(false)
            navigation?.dismiss(animated: true, completion: nil)
        }
        newGroup.onCreate = { [weak self, weak navigation] name, inviteOnly, invite in
            guard let self = self else {
                return
            }
            self.submanagerGroups.setGroupBackgroundWorkPaused(false)
            let friends = invite.compactMap { candidate -> OCTFriend? in
                let found = self.submanagerObjects.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", candidate.publicKey))
                return found.count > 0 ? found.firstObject : nil
            }
            navigation?.dismiss(animated: true) {
                self.createGroupAndOpen(isPrivate: inviteOnly, groupName: name, password: nil, inviteFriends: friends)
            }
        }
        navigation.modalPresentationStyle = .formSheet
        if let sheet = navigation.sheetPresentationController {
            sheet.detents = [.large()]
            sheet.prefersGrabberVisible = true
        }
        // Swiping the sheet away is a cancel too.
        navigation.presentationController?.delegate = newGroup
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

            // KHANDAQ (Figma): invite the contacts selected on step 1 (best-effort: an offline
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

    /// KHANDAQ (redesign stage 5, E15): "Join a group" as a sheet: the key (typed, pasted or scanned),
    /// the password if the group has one, how joining works, then "Join". A refusal stays on the sheet
    /// where the action is; once toxcore takes the key the group opens and says how it connects.
    func presentJoinGroupSheet(key: String?, from controller: UIViewController) {
        let join = AirJoinGroupController(key: key)
        let navigation = PortraitNavigationController(rootViewController: join)
        join.onCancel = { [weak navigation] in
            navigation?.dismiss(animated: true, completion: nil)
        }
        join.existingGroup = { [weak self] key in
            guard let chat = self?.existingGroupChat(for: key), chat.groupNumber >= 0 else {
                return nil
            }
            return chat
        }
        join.onOpenExisting = { [weak self, weak navigation] chat in
            navigation?.dismiss(animated: true) {
                self?.showChat(chat, animated: true)
            }
        }
        join.onScan = { [weak self, weak navigation] found in
            guard let self = self, let navigation = navigation else {
                return
            }
            self.presentScanGroupQR(from: navigation, found: found)
        }
        join.onJoin = { [weak self, weak navigation] key, password in
            guard let self = self else {
                return nil
            }
            switch self.joinGroup(withChatIdHex: key, password: password) {
                case .joined(let chat):
                    navigation?.dismiss(animated: true) {
                        self.showChat(chat, animated: true)
                    }
                    return nil
                case .refused(let words):
                    return words
            }
        }
        navigation.modalPresentationStyle = .formSheet
        if let sheet = navigation.sheetPresentationController {
            sheet.detents = [.large()]
            sheet.prefersGrabberVisible = true
        }
        // Swiping the sheet away is a cancel too.
        navigation.presentationController?.delegate = join
        controller.present(navigation, animated: true, completion: nil)
    }

    /// The camera over the join sheet; a code with a group key fills the key field.
    func presentScanGroupQR(from controller: UIViewController, found: @escaping (String) -> Void) {
        let scanner = QRScannerController(theme: theme)

        scanner.didScanStringsBlock = { [weak controller, scanner] strings in
            if let key = strings.compactMap({ GroupJoinHelper.normalizedGroupChatIdHex(from: $0) }).first {
                controller?.dismiss(animated: true) {
                    found(key)
                }
            }
            else {
                scanner.pauseScanning = true

                let alert = UIAlertController(title: nil,
                                              message: String(localized: "group_join_invalid_qr"),
                                              preferredStyle: .alert)
                alert.addAction(UIAlertAction(title: String(localized: "error_ok_button"), style: .default) { _ in
                    scanner.pauseScanning = false
                })
                scanner.present(alert, animated: true, completion: nil)
            }
        }

        scanner.cancelBlock = { [weak controller] in
            controller?.dismiss(animated: true, completion: nil)
        }

        let scannerNavCon = UINavigationController(rootViewController: scanner)
        controller.present(scannerNavCon, animated: true, completion: nil)
    }

    func alertPresentingController() -> UIViewController {
        if let external = externalPresentingController {
            return external
        }
        return navigationController.topViewController ?? navigationController
    }

    enum JoinResult {
        case joined(OCTChat)
        case refused(String)
    }

    /// The join by key as objcTox does it: toxcore looks for the group, objcTox asks the contacts among
    /// its members for an invitation. Only a key or a password toxcore cannot take is refused at once.
    func joinGroup(withChatIdHex chatIdHex: String, password: String?) -> JoinResult {
        var error: NSError?
        let groupNumber = submanagerGroups.joinGroup(withChatIdHex: chatIdHex, peerName: defaultPeerName(), password: password, error: &error)

        if error != nil || groupNumber == kOCTToxGroupNumberFailure {
            return .refused(AirGroupJoinError.text(for: error))
        }

        submanagerGroups.syncGroupsWithTox()

        guard let chat = submanagerGroups.chat(forGroupNumber: groupNumber) else {
            return .refused(String(localized: "group_join_failed"))
        }

        submanagerGroups.refreshPeers(for: chat)
        return .joined(chat)
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
