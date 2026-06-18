// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

protocol GroupSelectControllerDelegate: class {
    func groupSelectController(_ controller: GroupSelectController, didSelectGroup chat: OCTChat)
    func groupSelectControllerCancel(_ controller: GroupSelectController)
}

class GroupSelectController: UIViewController {
    weak var delegate: GroupSelectControllerDelegate?

    var userInfo: AnyObject?

    fileprivate let theme: Theme
    fileprivate let avatarManager: AvatarManager
    fileprivate var groups: [OCTChat] = []

    fileprivate var placeholderView: UITextView!
    fileprivate var tableView: UITableView!

    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects, userInfo: AnyObject? = nil) {
        self.theme = theme
        self.userInfo = userInfo
        self.avatarManager = AvatarManager(theme: theme)

        let chats = submanagerObjects.chats()
        var groupList = [OCTChat]()
        for index in 0..<chats.count {
            let chat = chats[index]
            if chat.isGroup {
                groupList.append(chat)
            }
        }
        self.groups = groupList.sorted { $0.lastActivityDateInterval > $1.lastActivityDateInterval }

        super.init(nibName: nil, bundle: nil)

        addNavigationButtons()
        edgesForExtendedLayout = UIRectEdge()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        createTableView()
        createPlaceholderView()
        installConstraints()
        updateViewsVisibility()
    }
}

extension GroupSelectController {
    @objc func cancelButtonPressed() {
        delegate?.groupSelectControllerCancel(self)
    }
}

extension GroupSelectController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return groups.count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let chat = groups[indexPath.row]
        let cell = tableView.dequeueReusableCell(withIdentifier: ChatListCell.staticReuseIdentifier, for: indexPath) as! ChatListCell
        let model = ChatListCellModel()
        let title = chat.groupName ?? String(localized: "group_chat_default_title")
        model.avatar = avatarManager.avatarFromString(title, diameter: CGFloat(ChatListCell.Constants.AvatarSize))
        model.nickname = title
        model.message = chat.groupTopic ?? ""
        cell.setupWithTheme(theme, model: model)
        return cell
    }
}

extension GroupSelectController: UITableViewDelegate {
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)
        delegate?.groupSelectController(self, didSelectGroup: groups[indexPath.row])
    }
}

private extension GroupSelectController {
    func addNavigationButtons() {
        navigationItem.leftBarButtonItem = UIBarButtonItem(
                barButtonSystemItem: .cancel,
                target: self,
                action: #selector(GroupSelectController.cancelButtonPressed))
    }

    func updateViewsVisibility() {
        placeholderView.isHidden = !groups.isEmpty
    }

    func createTableView() {
        tableView = UITableView()
        tableView.estimatedRowHeight = 44.0
        tableView.dataSource = self
        tableView.delegate = self
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        ThemeChrome.installZeroHeightTableFooter(in: tableView, theme: theme)
        view.addSubview(tableView)
        tableView.register(ChatListCell.self, forCellReuseIdentifier: ChatListCell.staticReuseIdentifier)
    }

    func createPlaceholderView() {
        placeholderView = UITextView()
        placeholderView.text = String(localized: "group_select_no_groups")
        placeholderView.isEditable = false
        placeholderView.isScrollEnabled = false
        placeholderView.textAlignment = .center
        placeholderView.backgroundColor = theme.colorForType(.NormalBackground)
        view.addSubview(placeholderView)
    }

    func installConstraints() {
        tableView.snp.makeConstraints {
            $0.edges.equalTo(view)
        }

        placeholderView.snp.makeConstraints {
            $0.center.equalTo(view)
            $0.size.equalTo(placeholderView.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)))
        }
    }
}
