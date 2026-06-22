// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

protocol FriendListControllerDelegate: class {
    func friendListController(_ controller: FriendListController, didSelectFriend friend: OCTFriend)
    func friendListController(_ controller: FriendListController, didSelectRequest request: OCTFriendRequest)
    func friendListControllerAddFriend(_ controller: FriendListController)
    func friendListController(_ controller: FriendListController, showQRCodeWithText text: String)
}

class FriendListController: UIViewController {
    weak var delegate: FriendListControllerDelegate?

    fileprivate let theme: Theme

    fileprivate var dataSource: FriendListDataSource!

    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate weak var submanagerFriends: OCTSubmanagerFriends!
    fileprivate weak var submanagerChats: OCTSubmanagerChats!
    fileprivate weak var submanagerUser: OCTSubmanagerUser!

    fileprivate var placeholderView: UITextView!
    // KHANDAQ design (Figma): explicit MyID actions in the empty contacts state.
    fileprivate var emptyActionsStack: UIStackView!
    fileprivate var tableView: UITableView!

    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects, submanagerFriends: OCTSubmanagerFriends, submanagerChats: OCTSubmanagerChats, submanagerUser: OCTSubmanagerUser) {
        self.theme = theme

        self.submanagerObjects = submanagerObjects
        self.submanagerFriends = submanagerFriends
        self.submanagerChats = submanagerChats
        self.submanagerUser = submanagerUser

        super.init(nibName: nil, bundle: nil)

        addNavigationButtons()

        edgesForExtendedLayout = UIRectEdge()
        title = String(localized: "contacts_title")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        createTableView()
        createPlaceholderView()
        installConstraints()
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        let friends = submanagerObjects.friends()
        let requests = submanagerObjects.friendRequests()
        dataSource = FriendListDataSource(theme: theme, friends: friends, requests: requests)
        dataSource.delegate = self

        ThemeChrome.installZeroHeightTableFooter(in: tableView, theme: theme)

        updateViewsVisibility()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        view.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        ThemeChrome.installZeroHeightTableFooter(in: tableView, theme: theme)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        updateViewsVisibility()
    }

    override func setEditing(_ editing: Bool, animated: Bool) {
        super.setEditing(editing, animated: animated)

        tableView.setEditing(editing, animated: animated)
    }
}

extension FriendListController {
    @objc func addFriendButtonPressed() {
        delegate?.friendListControllerAddFriend(self)
    }
}

extension FriendListController: FriendListDataSourceDelegate {
    func friendListDataSourceBeginUpdates() {
        tableView.beginUpdates()
    }

    func friendListDataSourceEndUpdates() {
        tableView.endUpdates()
        updateViewsVisibility()
    }

    func friendListDataSourceInsertRowsAtIndexPaths(_ indexPaths: [IndexPath]) {
        tableView.insertRows(at: indexPaths, with: .automatic)
    }

    func friendListDataSourceDeleteRowsAtIndexPaths(_ indexPaths: [IndexPath]) {
        tableView.deleteRows(at: indexPaths, with: .automatic)
    }

    func friendListDataSourceReloadRowsAtIndexPaths(_ indexPaths: [IndexPath]) {
        tableView.reloadRows(at: indexPaths, with: .automatic)
    }

    func friendListDataSourceInsertSections(_ sections: IndexSet) {
        tableView.insertSections(sections, with: .automatic)
    }

    func friendListDataSourceDeleteSections(_ sections: IndexSet) {
        tableView.deleteSections(sections, with: .automatic)
    }

    func friendListDataSourceReloadSections(_ sections: IndexSet) {
        tableView.reloadSections(sections, with: .automatic)
    }

    func friendListDataSourceReloadTable() {
        tableView.reloadData()
    }
}

extension FriendListController: UITableViewDataSource {
    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: FriendListCell.staticReuseIdentifier) as! FriendListCell
        let model = dataSource.modelAtIndexPath(indexPath)

        cell.setupWithTheme(theme, model: model)

        return cell
    }

    func numberOfSections(in tableView: UITableView) -> Int {
        return dataSource.numberOfSections()
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return dataSource.numberOfRowsInSection(section)
    }

    func sectionIndexTitles(for tableView: UITableView) -> [String]? {
        return dataSource.sectionIndexTitles()
    }

    func tableView(_ tableView: UITableView, titleForHeaderInSection section: Int) -> String? {
        return dataSource.titleForHeaderInSection(section)
    }

    func tableView(_ tableView: UITableView, commit editingStyle: UITableViewCellEditingStyle, forRowAt indexPath: IndexPath) {
        if editingStyle == .delete {
            let title: String

            switch dataSource.objectAtIndexPath(indexPath) {
                case .request:
                    title = String(localized:"delete_contact_request_title")
                case .friend:
                    title = String(localized:"delete_contact_title")
            }

            let alert = UIAlertController(title: title, message: nil, preferredStyle: .alert)

            alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .default, handler: nil))
            alert.addAction(UIAlertAction(title: String(localized: "alert_delete"), style: .destructive) { [unowned self] _ -> Void in
                switch self.dataSource.objectAtIndexPath(indexPath) {
                    case .request(let request):
                        self.submanagerFriends.remove(request)
                    case .friend(let friend):
                        do {
                            let chat = self.submanagerChats.getOrCreateChat(with: friend)

                            try self.submanagerFriends.remove(friend)

                            self.submanagerChats.removeAllMessages(in: chat, removeChat: true)
                        }
                        catch let error as NSError {
                            handleErrorWithType(.removeFriend, error: error)
                        }
                }
            })

            present(alert, animated: true, completion: nil)
        }
    }
}

extension FriendListController: UITableViewDelegate {
    func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        tableView.deselectRow(at: indexPath, animated: true)

        switch dataSource.objectAtIndexPath(indexPath) {
            case .request(let request):
                didSelectFriendRequest(request)
            case .friend(let friend):
                delegate?.friendListController(self, didSelectFriend: friend)
        }
    }
}

extension FriendListController : UITextViewDelegate {
    func textView(_ textView: UITextView, shouldInteractWith URL: URL, in characterRange: NSRange) -> Bool {
        if textView === placeholderView {
            let toxId = submanagerUser.userAddress
            let alert = UIAlertController(title: String(localized: "my_tox_id"), message: toxId, preferredStyle: .alert)

            alert.addAction(UIAlertAction(title: String(localized: "copy"), style: .default) { _ -> Void in
                UIPasteboard.general.string = toxId
            })

            alert.addAction(UIAlertAction(title: String(localized: "show_qr_code"), style: .default) { [weak self] _ -> Void in
                self?.delegate?.friendListController(self!, showQRCodeWithText: toxId)
            })

            alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

            present(alert, animated: true, completion: nil)
        }

        return false
    }
}

private extension FriendListController {
    func addNavigationButtons() {
        // KHANDAQ design (Figma): "+" sits in a grey circle; the Edit/Done button gets a grey pill
        // (keeping the system editButtonItem for its localization + toggle behaviour).
        navigationItem.rightBarButtonItem = ThemeChrome.makeCircleNavButton(
                theme: theme,
                systemImage: "plus",
                fallback: nil,
                target: self,
                action: #selector(FriendListController.addFriendButtonPressed))

        let capsule = ThemeChrome.navCapsuleBackgroundImage(theme: theme)
        editButtonItem.setBackgroundImage(capsule, for: .normal, barMetrics: .default)
        editButtonItem.setBackgroundImage(capsule, for: .highlighted, barMetrics: .default)
    }

    func updateViewsVisibility() {
        var isEmpty = true

        for section in 0..<dataSource.numberOfSections() {
            if dataSource.numberOfRowsInSection(section) > 0 {
                isEmpty = false
                break
            }
        }

        navigationItem.leftBarButtonItem = isEmpty ? nil : editButtonItem
        placeholderView.isHidden = !isEmpty
        emptyActionsStack?.isHidden = !isEmpty
    }

    func createTableView() {
        tableView = UITableView()
        tableView.estimatedRowHeight = 44.0
        tableView.dataSource = self
        tableView.delegate = self
        tableView.backgroundColor = theme.colorForType(.NormalBackground)
        tableView.sectionIndexColor = theme.colorForType(.LinkText)

        view.addSubview(tableView)

        tableView.register(FriendListCell.self, forCellReuseIdentifier: FriendListCell.staticReuseIdentifier)
    }

    func createPlaceholderView() {
        let top = String(localized: "contact_no_contacts_add_contact")
        let bottom = String(localized: "contact_no_contacts_share_tox_id")

        // KHANDAQ design (Figma): plain centered grey caption (~15pt), no inline link — the two cards
        // below provide the actions.
        let text = NSMutableAttributedString(string: "\(top)\(bottom)")
        let fullRange = NSRange(location: 0, length: text.length)
        text.addAttribute(NSAttributedStringKey.foregroundColor, value: theme.colorForType(.EmptyScreenPlaceholderText), range: fullRange)
        text.addAttribute(NSAttributedStringKey.font, value: UIFont.systemFont(ofSize: 15.0), range: fullRange)

        placeholderView = UITextView()
        placeholderView.delegate = self
        placeholderView.attributedText = text
        placeholderView.isEditable = false
        placeholderView.isScrollEnabled = false
        placeholderView.textAlignment = .center
        placeholderView.backgroundColor = theme.colorForType(.NormalBackground)
        view.addSubview(placeholderView)

        // KHANDAQ design (Figma): two grey cards side-by-side, icon on top + dark label.
        let copyButton = makeEmptyActionButton(title: String(localized: "contacts_copy_myid"),
                                               systemImage: "doc.on.doc",
                                               action: #selector(emptyCopyMyIdPressed))
        let qrButton = makeEmptyActionButton(title: String(localized: "show_qr_code"),
                                             systemImage: "qrcode",
                                             action: #selector(emptyShowQRPressed))
        emptyActionsStack = UIStackView(arrangedSubviews: [copyButton, qrButton])
        emptyActionsStack.axis = .horizontal
        emptyActionsStack.spacing = 12.0
        emptyActionsStack.distribution = .fillEqually
        view.addSubview(emptyActionsStack)
    }

    private func makeEmptyActionButton(title: String, systemImage: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.backgroundColor = theme.colorForType(.ChatInputBackground)
        button.layer.cornerRadius = 14.0
        button.addTarget(self, action: action, for: .touchUpInside)
        button.snp.makeConstraints { $0.height.equalTo(76.0) }

        let icon = UIImageView()
        icon.isUserInteractionEnabled = false
        icon.contentMode = .scaleAspectFit
        icon.tintColor = theme.colorForType(.NormalText)
        if #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 22.0, weight: .regular)
            icon.image = UIImage(systemName: systemImage, withConfiguration: config)
        }
        button.addSubview(icon)

        let label = UILabel()
        label.isUserInteractionEnabled = false
        label.text = title
        label.font = UIFont.systemFont(ofSize: 15.0)
        label.textColor = theme.colorForType(.NormalText)
        label.textAlignment = .center
        label.numberOfLines = 2
        button.addSubview(label)

        icon.snp.makeConstraints {
            $0.top.equalTo(button).offset(14.0)
            $0.leading.equalTo(button).offset(14.0)
        }
        label.snp.makeConstraints {
            $0.leading.equalTo(button).offset(14.0)
            $0.trailing.equalTo(button).offset(-14.0)
            $0.bottom.equalTo(button).offset(-12.0)
        }
        return button
    }

    @objc private func emptyCopyMyIdPressed() {
        UIPasteboard.general.string = submanagerUser.userAddress
    }

    @objc private func emptyShowQRPressed() {
        delegate?.friendListController(self, showQRCodeWithText: submanagerUser.userAddress)
    }

    func installConstraints() {
        tableView.snp.makeConstraints {
            $0.edges.equalTo(view)
        }

        placeholderView.snp.makeConstraints {
            $0.centerX.equalTo(view)
            $0.centerY.equalTo(view).offset(-48.0)
            $0.size.equalTo(placeholderView.sizeThatFits(CGSize(width: CGFloat.greatestFiniteMagnitude, height: CGFloat.greatestFiniteMagnitude)))
        }

        emptyActionsStack.snp.makeConstraints {
            $0.top.equalTo(placeholderView.snp.bottom).offset(24.0)
            $0.leading.trailing.equalTo(view).inset(40.0)
        }
    }

    func didSelectFriendRequest(_ request: OCTFriendRequest) {
        delegate?.friendListController(self, didSelectRequest: request)
    }
}
