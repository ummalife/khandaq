// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

class GroupPeerPrivateController: PortraitChatController {
    let chat: OCTChat
    let peer: OCTGroupPeer

    fileprivate let theme: Theme
    fileprivate weak var submanagerGroups: OCTSubmanagerGroups!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    fileprivate var tableView: UITableView!
    fileprivate var chatInputView: ChatInputView!
    fileprivate var tableViewToChatInputConstraint: Constraint!
    fileprivate var chatInputViewBottomConstraint: Constraint?

    fileprivate let messages: Results<OCTMessageAbstract>
    fileprivate var messagesToken: RLMNotificationToken?
    fileprivate let timeFormatter = DateFormatter(type: .time)

    init(theme: Theme,
         chat: OCTChat,
         peer: OCTGroupPeer,
         submanagerGroups: OCTSubmanagerGroups,
         submanagerObjects: OCTSubmanagerObjects) {
        self.theme = theme
        self.chat = chat
        self.peer = peer
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects

        // KHANDAQ (#55): thread by the peer's STABLE pubkey when known, so volatile peer-id reuse can
        // never surface another person's private messages here. Exclusive fallback (pubkey OR peerId,
        // never both): lowercase to match the stored value (Realm == is case-sensitive). Legacy rows
        // with no stored pubkey resolve via the peerId branch.
        let predicate: NSPredicate
        if let pubkey = peer.peerPublicKeyHex, !pubkey.isEmpty {
            predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND groupPrivateMessage == YES AND groupPrivatePeerPubkey == %@",
                                    chat.uniqueIdentifier, pubkey.lowercased())
        }
        else {
            predicate = NSPredicate(format: "chatUniqueIdentifier == %@ AND groupPrivateMessage == YES AND groupPrivatePeerId == %d",
                                    chat.uniqueIdentifier, peer.peerId)
        }
        self.messages = submanagerObjects.messages(predicate: predicate)
            .sortedResultsUsingProperty("dateInterval", ascending: true)

        super.init()

        title = peer.peerName ?? String(localized: "group_peer_fallback_format", peer.peerId)
        edgesForExtendedLayout = UIRectEdge()
        hidesBottomBarWhenPushed = true
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        messagesToken?.invalidate()
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        tableView = UITableView()
        tableView.estimatedRowHeight = 44
        tableView.separatorStyle = .none
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.delegate = self
        tableView.dataSource = self
        tableView.register(ChatIncomingTextCell.self, forCellReuseIdentifier: ChatIncomingTextCell.staticReuseIdentifier)
        tableView.register(ChatOutgoingTextCell.self, forCellReuseIdentifier: ChatOutgoingTextCell.staticReuseIdentifier)
        view.addSubview(tableView)

        chatInputView = ChatInputView(theme: theme)
        chatInputView.cameraButtonEnabled = false
        chatInputView.delegate = self
        view.addSubview(chatInputView)

        tableView.snp.makeConstraints {
            $0.top.leading.trailing.equalTo(view)
            tableViewToChatInputConstraint = $0.bottom.equalTo(chatInputView.snp.top).constraint
        }

        chatInputView.snp.makeConstraints {
            $0.leading.trailing.equalTo(view)
            chatInputViewBottomConstraint = $0.bottom.equalTo(view.snp.bottom).constraint
        }
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        messagesToken = messages.addNotificationBlock { [weak self] _ in
            self?.markPrivateThreadRead()
            self?.tableView?.reloadData()
            self?.scrollToBottom(animated: false)
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        markPrivateThreadRead()
        scrollToBottom(animated: false)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        markPrivateThreadRead()
    }

    func markPrivateThreadRead() {
        submanagerGroups.setPrivateLastReadDateInterval(Date().timeIntervalSince1970,
                                                        peerId: UInt32(peer.peerId),
                                                        for: chat)
    }

    override func keyboardWillShowAnimated(keyboardFrame frame: CGRect) {
        super.keyboardWillShowAnimated(keyboardFrame: frame)
        chatInputViewBottomConstraint?.update(offset: -frame.size.height)
        view.layoutIfNeeded()
        scrollToBottom(animated: true)
    }

    override func keyboardWillHideAnimated(keyboardFrame frame: CGRect) {
        super.keyboardWillHideAnimated(keyboardFrame: frame)
        var offset: CGFloat = 0
        if #available(iOS 11.0, *) {
            offset = -(UIApplication.shared.keyWindow?.safeAreaInsets.bottom ?? 20)
        }
        chatInputViewBottomConstraint?.update(offset: offset)
        view.layoutIfNeeded()
    }

    func scrollToBottom(animated: Bool) {
        guard messages.count > 0 else {
            return
        }

        let indexPath = IndexPath(row: messages.count - 1, section: 0)
        tableView.scrollToRow(at: indexPath, at: .bottom, animated: animated)
    }
}

extension GroupPeerPrivateController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return messages.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let message = messages[indexPath.row]
        let dateText = timeFormatter.string(from: message.date() as Date)

        if message.isOutgoing() {
            let cell = tableView.dequeueReusableCell(withIdentifier: ChatOutgoingTextCell.staticReuseIdentifier, for: indexPath) as! ChatOutgoingTextCell
            let model = ChatOutgoingTextCellModel()
            model.message = message.messageText?.text ?? ""
            model.dateString = dateText
            model.delivered = true
            cell.setupWithTheme(theme, model: model)
            return cell
        }

        let cell = tableView.dequeueReusableCell(withIdentifier: ChatIncomingTextCell.staticReuseIdentifier, for: indexPath) as! ChatIncomingTextCell
        let model = ChatBaseTextCellModel()
        model.message = message.messageText?.text ?? ""
        model.dateString = dateText
        cell.setupWithTheme(theme, model: model)
        return cell
    }
}

extension GroupPeerPrivateController: UITableViewDelegate {
}

extension GroupPeerPrivateController: ChatInputViewDelegate {
    func chatInputViewSendButtonPressed(_ view: ChatInputView) {
        let text = view.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return
        }

        view.text = ""
        submanagerGroups.sendPrivateMessage(text,
                                            toPeerId: UInt32(peer.peerId),
                                            in: chat,
                                            successBlock: { _ in
                                                // Realm notification reloads table.
                                            },
                                            failureBlock: { error in
                                                handleErrorWithType(.sendGroupPrivateMessage, error: error as NSError?)
                                            })
    }

    func chatInputViewCameraButtonPressed(_ view: ChatInputView, cameraView: UIView) {
    }

    func chatInputViewTextDidChange(_ view: ChatInputView) {
    }
}
