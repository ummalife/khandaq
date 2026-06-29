// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

protocol GroupMembersDrawerViewDelegate: class {
    func groupMembersDrawerView(_ view: GroupMembersDrawerView, didSelectPeer peer: OCTGroupPeer, sourceRect: CGRect)
    func groupMembersDrawerViewDidRequestClose(_ view: GroupMembersDrawerView)
}

final class GroupMembersDrawerView: UIView {
    weak var delegate: GroupMembersDrawerViewDelegate?

    private let theme: Theme
    private let chat: OCTChat
    private weak var submanagerGroups: OCTSubmanagerGroups!

    private let headerView = UIView()
    private let titleLabel = UILabel()
    private let tableView = UITableView(frame: .zero, style: .plain)

    private var peers: Results<OCTGroupPeer>
    private var peersToken: RLMNotificationToken?
    private var selfPeerId: UInt32 = 0
    private var peersObserver: NSObjectProtocol?

    init(theme: Theme, chat: OCTChat, submanagerGroups: OCTSubmanagerGroups) {
        self.theme = theme
        self.chat = chat
        self.submanagerGroups = submanagerGroups
        self.peers = Results<OCTGroupPeer>(results: submanagerGroups.peers(for: chat))
        self.selfPeerId = submanagerGroups.groupSelfPeerId(for: chat)

        super.init(frame: .zero)

        backgroundColor = theme.colorForType(.NormalBackground)
        layer.shadowColor = UIColor.black.cgColor
        layer.shadowOpacity = 0.2
        layer.shadowRadius = 8
        layer.shadowOffset = CGSize(width: 2, height: 0)

        titleLabel.font = UIFont.preferredFont(forTextStyle: .headline)
        titleLabel.textColor = theme.colorForType(.NormalText)
        titleLabel.text = drawerTitle()

        headerView.backgroundColor = theme.colorForType(.NormalBackground)
        headerView.addSubview(titleLabel)
        addSubview(headerView)

        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.separatorInset = UIEdgeInsets(top: 0, left: 16, bottom: 0, right: 0)
        tableView.delegate = self
        tableView.dataSource = self
        addSubview(tableView)

        titleLabel.snp.makeConstraints {
            $0.leading.trailing.equalToSuperview().inset(16)
            $0.top.bottom.equalToSuperview().inset(12)
        }

        headerView.snp.makeConstraints {
            $0.top.leading.trailing.equalToSuperview()
        }

        tableView.snp.makeConstraints {
            $0.top.equalTo(headerView.snp.bottom)
            $0.leading.trailing.bottom.equalToSuperview()
        }

        peersToken = peers.addNotificationBlock { [weak self] _ in
            self?.reloadPeers()
        }

        peersObserver = NotificationCenter.default.addObserver(
                forName: NSNotification.Name.octGroupPeersUpdated,
                object: nil,
                queue: .main) { [weak self] notification in
            guard let self = self,
                  let chatId = notification.userInfo?[kOCTGroupPeersUpdatedChatUniqueIdentifierKey] as? String,
                  chatId == self.chat.uniqueIdentifier else {
                return
            }

            self.reloadPeers()
        }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        peersToken?.invalidate()
        if let observer = peersObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    func reloadFromTox() {
        submanagerGroups.refreshPeers(for: chat)
        reloadPeers()
    }

    private func reloadPeers() {
        selfPeerId = submanagerGroups.groupSelfPeerId(for: chat)
        peers = Results<OCTGroupPeer>(results: submanagerGroups.peers(for: chat))
        titleLabel.text = drawerTitle()
        tableView.reloadData()
    }

    private func drawerTitle() -> String {
        // KHANDAQ (#49): unify with the chat-list row — online from onlineGroupPeerCount, total from
        // peerCount (no offline term). Previously the drawer added offlinePeerCount, so its header (6)
        // exceeded both the rows it lists (5) and the chat-list total (5).
        let online = Int(submanagerGroups.onlineGroupPeerCount(for: chat))
        let total = max(Int(submanagerGroups.peerCount(for: chat)), peers.count)
        if total > online && online > 0 {
            return String(localized: "group_members_header_online_format", total, online)
        }
        if total > 0 {
            return String(localized: "group_members_header_format", total)
        }
        return String(localized: "group_member_list_title")
    }

    private func configureCell(_ cell: UITableViewCell, peer: OCTGroupPeer) {
        let peerName = peer.peerName ?? String(localized: "group_peer_fallback_format", peer.peerId)
        cell.textLabel?.text = peerName
        cell.textLabel?.font = UIFont.preferredFont(forTextStyle: .body)
        cell.textLabel?.numberOfLines = 1

        let status = GroupMemberStatusFormatter.onlineStatusLine(for: peer, chat: chat, submanagerGroups: submanagerGroups)
        cell.detailTextLabel?.text = status
        cell.detailTextLabel?.font = UIFont.preferredFont(forTextStyle: .caption1)
        cell.detailTextLabel?.numberOfLines = 2

        if UInt32(peer.peerId) == selfPeerId && selfPeerId > 0 {
            cell.textLabel?.textColor = UIColor(red: 1.0, green: 0.34, blue: 0.2, alpha: 1.0)
        }
        else if peer.peerNotificationsSilent {
            cell.textLabel?.textColor = UIColor(red: 0.84, green: 0.71, blue: 0.26, alpha: 1.0)
        }
        else {
            cell.textLabel?.textColor = theme.colorForType(.NormalText)
        }

        if submanagerGroups.isGroupPeerOnline(withId: UInt32(peer.peerId), in: chat) {
            cell.detailTextLabel?.textColor = UIColor(red: 0.39, green: 0.71, blue: 0.96, alpha: 1.0)
        }
        else {
            cell.detailTextLabel?.textColor = theme.colorForType(.FriendCellStatus)
        }

        // KHANDAQ (#15): the peer cell had no explicit background, so it rendered as a too-dark
        // default block that clashed with the themed drawer. Match the drawer background and give a
        // subtle themed selection highlight instead of the harsh default.
        cell.backgroundColor = theme.colorForType(.NormalBackground)
        cell.contentView.backgroundColor = theme.colorForType(.NormalBackground)
        let selectedBackground = UIView()
        selectedBackground.backgroundColor = theme.colorForType(.SeparatorsAndBorders)
        cell.selectedBackgroundView = selectedBackground
        cell.selectionStyle = .default
        cell.accessoryType = .disclosureIndicator
    }
}

extension GroupMembersDrawerView: UITableViewDataSource {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return peers.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let identifier = "peerCell"
        let cell = tableView.dequeueReusableCell(withIdentifier: identifier)
            ?? UITableViewCell(style: .subtitle, reuseIdentifier: identifier)
        // KHANDAQ (#40/#61): bounds-checked — the live peer list can shrink between reloadData calls.
        if let peer = peers.object(at: indexPath.row) {
            configureCell(cell, peer: peer)
        }
        return cell
    }
}

extension GroupMembersDrawerView: UITableViewDelegate {
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        presentPeer(at: indexPath, from: tableView)
    }
}

extension GroupMembersDrawerView {
    func presentPeer(at indexPath: IndexPath, from tableView: UITableView) {
        // KHANDAQ (#40/#61): bounds-checked — avoid trapping if the tapped row index is stale.
        guard let peer = peers.object(at: indexPath.row) else {
            return
        }
        let rect = tableView.rectForRow(at: indexPath)
        delegate?.groupMembersDrawerView(self, didSelectPeer: peer, sourceRect: rect)
    }
}
