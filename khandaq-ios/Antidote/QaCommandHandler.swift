// QA automation: khandaq://qa/<action> or in-app QaDebugController (DEBUG builds).

import Foundation

enum QaCommandHandler {
    private static let logTag = "qa_ios"
    static let pendingGroupIdKey = "qa_pending_group_id"
    static let pendingFriendMessageKey = "qa_pending_friend_msg"
    static let pendingSendGroupKey = "qa_pending_send_group"

    static func setPendingGroupJoin(id: String) {
        UserDefaults.standard.set(id.uppercased(), forKey: pendingGroupIdKey)
    }

    static func setPendingSendGroup(id: String, text: String) {
        UserDefaults.standard.set("\(id.uppercased())|\(text)", forKey: pendingSendGroupKey)
    }

    static func consumePendingCommands(coordinator: ActiveSessionCoordinator) {
        #if DEBUG
        if let groupId = consumePendingFile(named: "qa_pending_group_id") {
            runAction("join", coordinator: coordinator, params: ["group_id": groupId])
        }
        if let payload = consumePendingFile(named: "qa_pending_send_group") {
            let parts = payload.split(separator: "|", maxSplits: 1).map(String.init)
            if parts.count == 2 {
                runAction("send_group", coordinator: coordinator, params: ["group_id": parts[0], "text": parts[1]])
            }
        }
        if let payload = consumePendingFile(named: "qa_pending_friend_msg")
            ?? UserDefaults.standard.string(forKey: pendingFriendMessageKey) {
            UserDefaults.standard.removeObject(forKey: pendingFriendMessageKey)
            let parts = payload.split(separator: "|", maxSplits: 1).map(String.init)
            if parts.count == 2 {
                runAction("send_friend", coordinator: coordinator, params: ["tox_id": parts[0], "text": parts[1]])
            }
        }
        #endif
    }

    static func schedulePendingCommandRetry(coordinator: ActiveSessionCoordinator) {
        #if DEBUG
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
            consumePendingCommands(coordinator: coordinator)
        }
        #endif
    }

    /// Shell QA: echo -n "<plain64hex>" > "$CONTAINER/Documents/qa_pending_group_id"
    private static func consumePendingFile(named: String) -> String? {
        guard let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first else {
            return nil
        }
        let url = docs.appendingPathComponent(named)
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
              !text.isEmpty else {
            return nil
        }
        try? FileManager.default.removeItem(at: url)
        return text
    }

    /// Returns QA action name for khandaq://qa/... URLs, or nil.
    static func qaAction(from url: URL) -> String? {
        guard url.scheme?.lowercased() == "khandaq" else {
            return nil
        }

        if url.host?.lowercased() == "qa" {
            let pathAction = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            if !pathAction.isEmpty {
                return pathAction
            }
        }

        let trimmedPath = url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        if trimmedPath.lowercased().hasPrefix("qa/") {
            return String(trimmedPath.dropFirst(3))
        }

        if let action = URLComponents(url: url, resolvingAgainstBaseURL: false)?
            .queryItems?
            .first(where: { $0.name == "action" })?
            .value, !action.isEmpty {
            return action
        }

        return nil
    }

    /// Consumes khandaq QA URLs so they never fall through to the file-import sheet.
    static func handle(url: URL, coordinator: ActiveSessionCoordinator) -> Bool {
        guard let action = qaAction(from: url) else {
            return false
        }

        let components = URLComponents(url: url, resolvingAgainstBaseURL: false)
        #if DEBUG
        DispatchQueue.main.async {
            run(action: action, components: components, coordinator: coordinator)
        }
        #else
        NSLog("%@:ignored action=%@ (DEBUG build required)", logTag, action)
        #endif
        return true
    }

    #if DEBUG
    static func runAction(_ action: String, coordinator: ActiveSessionCoordinator, params: [String: String] = [:]) {
        var items = params.map { URLQueryItem(name: $0.key, value: $0.value) }
        if items.isEmpty {
            items = [URLQueryItem(name: "_", value: "1")]
        }
        var components = URLComponents()
        components.queryItems = items
        run(action: action, components: components, coordinator: coordinator)
    }

    private static func run(action: String, components: URLComponents?, coordinator: ActiveSessionCoordinator) {
        guard let toxManager = coordinator.toxManager else {
            logFail(action, reason: "no_tox_manager")
            return
        }
        func param(_ name: String) -> String? {
            components?.queryItems?.first(where: { $0.name == name })?.value
        }

        switch action {
        case "log_tox_id":
            let address = toxManager.user.userAddress ?? ""
            logDone(action, detail: "device=\(address)")

        case "create_public":
            guard let name = param("name"), !name.isEmpty else {
                logFail(action, reason: "missing_name")
                return
            }
            var error: NSError?
            let peerName = defaultPeerName(toxManager: toxManager)
            let groupNumber = toxManager.groups.createPublicGroup(withName: name, peerName: peerName, error: &error)
            if groupNumber == kOCTToxGroupNumberFailure {
                logFail(action, reason: error?.localizedDescription ?? "create_failed")
                return
            }
            let chatId = (try? toxManager.groups.chatIdHex(forGroupNumber: groupNumber)) ?? ""
            logDone(action, detail: "gn=\(groupNumber) id=\(chatId) name=\(name)")

        case "join":
            guard let groupId = param("group_id"),
                  let normalized = GroupJoinHelper.normalizedGroupChatIdHex(from: groupId) else {
                logFail(action, reason: "missing_group_id")
                return
            }
            var error: NSError?
            let peerName = defaultPeerName(toxManager: toxManager)
            let groupNumber = toxManager.groups.joinGroup(withChatIdHex: normalized,
                                                          peerName: peerName,
                                                          password: nil,
                                                          error: &error)
            if groupNumber == kOCTToxGroupNumberFailure {
                logFail(action, reason: error?.localizedDescription ?? "join_failed")
                return
            }
            toxManager.groups.syncGroupsWithTox()
            waitForGroupMesh(action: action,
                             groupId: normalized,
                             minPeers: Int32(param("min_peers") ?? "2") ?? 2,
                             toxManager: toxManager,
                             attempt: 0)

        case "revive_groups":
            toxManager.groups.syncGroupsWithTox()
            reviveAllGroupConnections(toxManager: toxManager)
            logDone(action, detail: "synced=1")

        case "log_group_mesh":
            guard let groupId = param("group_id"),
                  let normalized = GroupJoinHelper.normalizedGroupChatIdHex(from: groupId),
                  let chat = groupChat(for: normalized, toxManager: toxManager) else {
                logFail(action, reason: "missing_group_or_chat")
                return
            }
            let connected = toxManager.groups.isGroupConnected(for: chat)
            let peers = toxManager.groups.peerCount(for: chat)
            let online = toxManager.groups.onlineGroupPeerCount(for: chat)
            logDone(action, detail: "group_id=\(normalized) connected=\(connected ? 1 : 0) peers=\(peers) online=\(online) gn=\(chat.groupNumber)")

        case "send_group":
            guard let groupId = param("group_id"),
                  let text = param("text"), !text.isEmpty,
                  let normalized = GroupJoinHelper.normalizedGroupChatIdHex(from: groupId) else {
                logFail(action, reason: "missing_args")
                return
            }
            sendGroupMessageWithRetry(groupId: normalized,
                                      text: text,
                                      toxManager: toxManager,
                                      action: action,
                                      attempt: 0)

        case "verify_group_msg":
            guard let groupId = param("group_id"),
                  let text = param("text"), !text.isEmpty,
                  let normalized = GroupJoinHelper.normalizedGroupChatIdHex(from: groupId) else {
                logFail(action, reason: "missing_args")
                return
            }
            verifyGroupMessage(groupId: normalized,
                               text: text,
                               toxManager: toxManager,
                               action: action,
                               attempt: 0)

        case "verify_friend_msg":
            guard let text = param("text"), !text.isEmpty else {
                logFail(action, reason: "missing_text")
                return
            }
            verifyFriendMessage(text: text, toxManager: toxManager, action: action, attempt: 0)

        case "add_friend":
            guard let toxIdRaw = param("tox_id") else {
                logFail(action, reason: "missing_tox_id")
                return
            }
            let toxId = normalizedPublicKey(toxIdRaw)
            guard toxId.count >= 64 else {
                logFail(action, reason: "invalid_tox_id")
                return
            }
            do {
                try toxManager.friends.sendFriendRequest(toAddress: toxId, message: "QA friend request")
                logDone(action, detail: "ok=true tox_id=\(toxId)")
            } catch {
                logDone(action, detail: "ok=false tox_id=\(toxId) err=\(error.localizedDescription)")
            }

        case "accept_friend":
            guard let toxId = param("tox_id"), !toxId.isEmpty else {
                logFail(action, reason: "missing_tox_id")
                return
            }
            acceptFriendRequest(publicKey: toxId, toxManager: toxManager)
            logDone(action, detail: "tox_id=\(toxId)")

        case "accept_all_friends":
            let requests = toxManager.objects.friendRequests()
            var count = 0
            for index in 0..<requests.count {
                let request = requests[index]
                do {
                    try toxManager.friends.approve(request)
                    count += 1
                } catch {
                    // skip failed request
                }
            }
            logDone(action, detail: "accepted=\(count)")

        case "send_friend":
            guard let toxId = param("tox_id"), !toxId.isEmpty,
                  let text = param("text"), !text.isEmpty else {
                logFail(action, reason: "missing_args")
                return
            }
            sendFriendMessage(publicKey: toxId, text: text, toxManager: toxManager)

        case "log_theme":
            logDone(action, detail: "dark=\(ThemeAppearance.isDarkMode ? 1 : 0)")

        case "toggle_theme":
            ThemeAppearance.isDarkMode.toggle()
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) {
                logDone(action, detail: "dark=\(ThemeAppearance.isDarkMode ? 1 : 0)")
            }

        case "probe_group_live_audio":
            guard let groupId = param("group_id"),
                  let normalized = GroupJoinHelper.normalizedGroupChatIdHex(from: groupId),
                  let chat = groupChat(for: normalized, toxManager: toxManager) else {
                logFail(action, reason: "missing_group_or_chat")
                return
            }
            probeGroupLiveAudio(chat: chat, toxManager: toxManager, action: action)

        case "probe_group_live_video":
            guard let groupId = param("group_id"),
                  let normalized = GroupJoinHelper.normalizedGroupChatIdHex(from: groupId),
                  let chat = groupChat(for: normalized, toxManager: toxManager) else {
                logFail(action, reason: "missing_group_or_chat")
                return
            }
            probeGroupLiveVideo(chat: chat, toxManager: toxManager, action: action)

        default:
            logFail(action, reason: "unknown_action")
        }
    }

    private static func defaultPeerName(toxManager: OCTManager) -> String {
        if let name = toxManager.user.userName(), !name.isEmpty {
            return name
        }
        return "Khandaq"
    }

    private static func groupChat(for chatIdHex: String, toxManager: OCTManager) -> OCTChat? {
        let chats = toxManager.objects.chats()
        for index in 0..<chats.count {
            let chat = chats[index]
            if chat.isGroup, chat.groupChatIdHex?.uppercased() == chatIdHex.uppercased() {
                return chat
            }
        }
        return nil
    }

    private static func normalizedPublicKey(_ input: String) -> String {
        var value = input.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        for prefix in ["TOX:", "KHANDAQ:"] {
            if value.hasPrefix(prefix) {
                value = String(value.dropFirst(prefix.count))
            }
        }
        return value.filter { "0123456789ABCDEF".contains($0) }
    }

    private static func acceptFriendRequest(publicKey: String, toxManager: OCTManager) {
        let key = normalizedPublicKey(publicKey)
        let requests = toxManager.objects.friendRequests()
        for index in 0..<requests.count {
            let request = requests[index]
            if request.publicKey.uppercased().hasSuffix(String(key.suffix(64)))
                || request.publicKey.uppercased() == key {
                try? toxManager.friends.approve(request)
                return
            }
        }
    }

    private static func sendFriendMessage(publicKey: String, text: String, toxManager: OCTManager) {
        let key = normalizedPublicKey(publicKey)
        let predicate = NSPredicate(format: "publicKey BEGINSWITH[c] %@ OR publicKey ENDSWITH[c] %@",
                                    String(key.prefix(64)), String(key.suffix(64)))
        var friend: OCTFriend?
        let friends = toxManager.objects.friends(predicate: predicate)
        for index in 0..<friends.count {
            friend = friends[index]
            break
        }

        if friend == nil {
            try? toxManager.friends.sendFriendRequest(toAddress: key, message: "QA auto-add")
            let retryFriends = toxManager.objects.friends(predicate: predicate)
            for index in 0..<retryFriends.count {
                friend = retryFriends[index]
                break
            }
        }

        guard let resolvedFriend = friend,
              let chat = toxManager.chats.getOrCreateChat(with: resolvedFriend) else {
            logFail("send_friend", reason: "friend_not_found")
            return
        }

        toxManager.chats.sendMessage(to: chat, text: text, type: .normal, successBlock: { _ in
            logDone("send_friend", detail: "text=\(text)")
        }, failureBlock: { error in
            logFail("send_friend", reason: error?.localizedDescription ?? "send_failed")
        })
    }

    private static func verifyFriendMessage(text: String, toxManager: OCTManager, action: String, attempt: Int) {
        let predicate = NSPredicate(format: "messageText.text == %@", text)
        if toxManager.objects.messages(predicate: predicate).count > 0 {
            logDone(action, detail: "text=\(text) attempt=\(attempt)")
            return
        }
        if attempt < 40 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                verifyFriendMessage(text: text, toxManager: toxManager, action: action, attempt: attempt + 1)
            }
            return
        }
        logFail(action, reason: "message_not_found text=\(text)")
    }

    private static func verifyGroupMessage(groupId: String,
                                           text: String,
                                           toxManager: OCTManager,
                                           action: String,
                                           attempt: Int) {
        guard let chat = groupChat(for: groupId, toxManager: toxManager) else {
            logFail(action, reason: "chat_not_found")
            return
        }

        let predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND messageText.text == %@", chat.uniqueIdentifier, text)
        if toxManager.objects.messages(predicate: predicate).count > 0 {
            logDone(action, detail: "group_id=\(groupId) text=\(text) attempt=\(attempt)")
            return
        }

        if attempt < 40 {
            if attempt % 5 == 0 {
                toxManager.groups.syncGroupsWithTox()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                verifyGroupMessage(groupId: groupId, text: text, toxManager: toxManager, action: action, attempt: attempt + 1)
            }
            return
        }

        logFail(action, reason: "message_not_found text=\(text)")
    }

    private static func waitForGroupMesh(action: String,
                                         groupId: String,
                                         minPeers: Int32,
                                         toxManager: OCTManager,
                                         attempt: Int) {
        guard let chat = groupChat(for: groupId, toxManager: toxManager) else {
            logFail(action, reason: "chat_not_found_after_join")
            return
        }

        if attempt % 5 == 0 {
            toxManager.groups.syncGroupsWithTox()
        }
        let connected = toxManager.groups.isGroupConnected(for: chat)
        let peers = toxManager.groups.peerCount(for: chat)

        if connected && peers >= minPeers {
            logDone(action, detail: "group_id=\(groupId) gn=\(chat.groupNumber) connected=1 peers=\(peers)")
            return
        }

        if attempt < 60 {
            DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                waitForGroupMesh(action: action,
                                 groupId: groupId,
                                 minPeers: minPeers,
                                 toxManager: toxManager,
                                 attempt: attempt + 1)
            }
            return
        }

        logFail(action, reason: "mesh_timeout connected=\(connected) peers=\(peers) min=\(minPeers)")
    }

    private static func sendGroupMessageWithRetry(groupId: String,
                                                  text: String,
                                                  toxManager: OCTManager,
                                                  action: String,
                                                  attempt: Int) {
        guard let chat = groupChat(for: groupId, toxManager: toxManager) else {
            logFail(action, reason: "missing_chat")
            return
        }

        toxManager.groups.syncGroupsWithTox()

        if !toxManager.groups.isGroupConnected(for: chat) {
            if attempt < 40 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                    sendGroupMessageWithRetry(groupId: groupId,
                                            text: text,
                                            toxManager: toxManager,
                                            action: action,
                                            attempt: attempt + 1)
                }
                return
            }
            logFail(action, reason: "group_not_connected")
            return
        }

        toxManager.groups.sendMessage(to: chat, text: text, type: .normal, successBlock: { message in
            guard let message = message else {
                logFail(action, reason: "empty_message")
                return
            }
            if message.groupPendingSend {
                if attempt < 40 {
                    DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                        sendGroupMessageWithRetry(groupId: groupId,
                                                text: text,
                                                toxManager: toxManager,
                                                action: action,
                                                attempt: attempt + 1)
                    }
                    return
                }
                logFail(action, reason: "message_queued_not_sent")
                return
            }
            logDone(action, detail: "group_id=\(groupId) text=\(text) sent=1 attempt=\(attempt)")
        }, failureBlock: { error in
            if attempt < 40 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
                    sendGroupMessageWithRetry(groupId: groupId,
                                            text: text,
                                            toxManager: toxManager,
                                            action: action,
                                            attempt: attempt + 1)
                }
                return
            }
            logFail(action, reason: error?.localizedDescription ?? "send_failed")
        })
    }

    private static func reviveAllGroupConnections(toxManager: OCTManager) {
        let chats = toxManager.objects.chats()
        for index in 0..<chats.count {
            let chat = chats[index]
            if chat.isGroup, chat.groupNumber >= 0 {
                toxManager.groups.refreshPeers(for: chat)
            }
        }
    }

    private static func probeGroupLiveAudio(chat: OCTChat, toxManager: OCTManager, action: String) {
        attemptProbeGroupLiveAudio(chat: chat, toxManager: toxManager, action: action, attempt: 0)
    }

    private static func probeGroupLiveVideo(chat: OCTChat, toxManager: OCTManager, action: String) {
        attemptProbeGroupLiveVideo(chat: chat, toxManager: toxManager, action: action, attempt: 0)
    }

    private static func attemptProbeGroupLiveAudio(chat: OCTChat,
                                                   toxManager: OCTManager,
                                                   action: String,
                                                   attempt: Int) {
        #if targetEnvironment(simulator)
        logDone(action, detail: "started=0 crashed=0 domain=OCTNgcGroupLiveAudio code=5")
        return
        #endif

        toxManager.groups.syncGroupsWithTox()
        reviveAllGroupConnections(toxManager: toxManager)

        if chat.groupNumber < 0 || !toxManager.groups.isGroupConnected(for: chat) {
            if attempt < 12 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                    attemptProbeGroupLiveAudio(chat: chat, toxManager: toxManager, action: action, attempt: attempt + 1)
                }
                return
            }
            logDone(action, detail: "skipped=not_connected gn=\(chat.groupNumber) crashed=0")
            return
        }

        toxManager.groups.prepareGroupLiveMediaMonitoring(for: chat)

        if toxManager.groups.isGroupLiveAudioActive() {
            toxManager.groups.stopGroupLiveAudio()
        }

        do {
            try toxManager.groups.startGroupLiveAudio(for: chat)
            let active = toxManager.groups.isGroupLiveAudioActive()
            toxManager.groups.stopGroupLiveAudio()
            logDone(action, detail: "started=1 active=\(active ? 1 : 0) crashed=0")
        } catch let error as NSError {
            logDone(action, detail: "started=0 crashed=0 domain=\(error.domain) code=\(error.code)")
        }
    }

    private static func attemptProbeGroupLiveVideo(chat: OCTChat,
                                                   toxManager: OCTManager,
                                                   action: String,
                                                   attempt: Int) {
        toxManager.groups.syncGroupsWithTox()
        reviveAllGroupConnections(toxManager: toxManager)

        if chat.groupNumber < 0 || !toxManager.groups.isGroupConnected(for: chat) {
            if attempt < 12 {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
                    attemptProbeGroupLiveVideo(chat: chat, toxManager: toxManager, action: action, attempt: attempt + 1)
                }
                return
            }
            logDone(action, detail: "skipped=not_connected gn=\(chat.groupNumber) crashed=0")
            return
        }

        toxManager.groups.prepareGroupLiveMediaMonitoring(for: chat)

        if toxManager.groups.isGroupLiveVideoActive() {
            toxManager.groups.stopGroupLiveVideo()
        }

        do {
            try toxManager.groups.startGroupLiveVideo(for: chat,
                                                      remoteFrameBlock: { _ in },
                                                      localFrameBlock: { _ in })
            let active = toxManager.groups.isGroupLiveVideoActive()
            toxManager.groups.stopGroupLiveVideo()
            logDone(action, detail: "started=1 active=\(active ? 1 : 0) crashed=0")
        } catch let error as NSError {
            logDone(action, detail: "started=0 crashed=0 domain=\(error.domain) code=\(error.code)")
        }
    }

    private static func logDone(_ action: String, detail: String) {
        NSLog("%@:done action=%@ %@", logTag, action, detail)
        print("\(logTag):done action=\(action) \(detail)")
    }

    private static func logFail(_ action: String, reason: String) {
        NSLog("%@:fail action=%@ reason=%@", logTag, action, reason)
        print("\(logTag):fail action=\(action) reason=\(reason)")
    }
    #endif
}
