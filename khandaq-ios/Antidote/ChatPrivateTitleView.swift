// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let StatusViewLeftOffset: CGFloat = 5.0
    static let StatusViewSize: CGFloat = 10.0
    // KHANDAQ design (Figma): contact avatar to the left of the name in the chat header.
    static let AvatarSize: CGFloat = 30.0
    static let AvatarGap: CGFloat = 8.0
}

class ChatPrivateTitleView: UIView {
    // KHANDAQ (Figma): the header lives as a LEFT bar item (Telegram-style avatar+name next to the back
    // button), but a navigation bar only measures a custom bar-item view once. Whenever our content
    // changes the intrinsic size, fire this so the controller can re-install the item and force a
    // re-measure. No-op / no re-install when the size is unchanged (avoids flicker on status ticks).
    var onSizeChanged: (() -> Void)?

    var name: String {
        get {
            return nameLabel.text ?? ""
        }
        set {
            nameLabel.text = newValue
            reportSizeIfChanged()
        }
    }

    var userStatus: UserStatus {
        get {
            return statusView.userStatus
        }
        set {
            statusView.userStatus = newValue
        }
    }

    var presenceText: String {
        get {
            return statusLabel.text ?? ""
        }
        set {
            statusLabel.text = newValue
            reportSizeIfChanged()
        }
    }

    var presenceIsOnline: Bool = false {
        didSet {
            updatePresenceColor()
        }
    }

    var connectionStatus: ConnectionStatus {
        get {
            return statusView.connectionStatus
        }
        set {
            statusView.connectionStatus = newValue
        }
    }

    var avatar: UIImage? {
        get {
            return avatarView.image
        }
        set {
            avatarView.image = newValue
            avatarView.isHidden = newValue == nil
            reportSizeIfChanged()
        }
    }

    fileprivate var avatarView: UIImageView!
    fileprivate var nameLabel: UILabel!
    fileprivate var statusView: UserStatusView!
    fileprivate var statusLabel: UILabel!
    fileprivate var theme: Theme!
    fileprivate var lastReportedSize: CGSize = .zero

    init(theme: Theme) {
        super.init(frame: CGRect.zero)

        self.theme = theme
        backgroundColor = .clear

        createViews(theme)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

private extension ChatPrivateTitleView {
    // KHANDAQ (Figma): Telegram-style header — [avatar][name / status] laid out left-to-right with
    // stack views so the view has a real intrinsicContentSize. That lets it live as a LEFT bar item
    // (avatar+name pinned next to the back button) and a hidden avatar collapses cleanly (stack views
    // drop hidden arranged subviews). Replaces the old manual frame sizing, which broke outside the
    // centered titleView slot.
    func createViews(_ theme: Theme) {
        avatarView = UIImageView()
        avatarView.contentMode = .scaleAspectFill
        avatarView.layer.cornerRadius = Constants.AvatarSize / 2.0
        avatarView.layer.masksToBounds = true
        avatarView.isHidden = true

        nameLabel = UILabel()
        nameLabel.textAlignment = .natural
        nameLabel.textColor = theme.colorForType(.NormalText)
        nameLabel.font = UIFont.khandaqFontWithSize(16.0, weight: .bold)

        statusView = UserStatusView()
        statusView.showExternalCircle = false
        statusView.theme = theme
        statusView.isHidden = true

        statusLabel = UILabel()
        statusLabel.textAlignment = .natural
        statusLabel.textColor = theme.colorForType(.NormalText)
        statusLabel.font = UIFont.khandaqFontWithSize(12.0, weight: .light)

        let nameRow = UIStackView(arrangedSubviews: [nameLabel, statusView])
        nameRow.axis = .horizontal
        nameRow.alignment = .center
        nameRow.spacing = Constants.StatusViewLeftOffset

        let textStack = UIStackView(arrangedSubviews: [nameRow, statusLabel])
        textStack.axis = .vertical
        textStack.alignment = .leading
        textStack.spacing = 0

        let mainStack = UIStackView(arrangedSubviews: [avatarView, textStack])
        mainStack.axis = .horizontal
        mainStack.alignment = .center
        mainStack.spacing = Constants.AvatarGap
        addSubview(mainStack)

        mainStack.snp.makeConstraints {
            $0.edges.equalTo(self)
        }
        avatarView.snp.makeConstraints {
            $0.size.equalTo(Constants.AvatarSize)
        }
        statusView.snp.makeConstraints {
            $0.size.equalTo(Constants.StatusViewSize)
        }

        updatePresenceColor()
    }

    func updatePresenceColor() {
        guard let theme = theme else {
            return
        }

        statusLabel.textColor = presenceIsOnline
            ? theme.colorForType(.OnlineStatus)
            : theme.colorForType(.NormalText)
    }

    // Recompute intrinsic size; only ask the controller to re-install (re-measure) if it actually moved.
    func reportSizeIfChanged() {
        invalidateIntrinsicContentSize()
        setNeedsLayout()
        layoutIfNeeded()

        let newSize = systemLayoutSizeFitting(UILayoutFittingCompressedSize)
        if abs(newSize.width - lastReportedSize.width) > 0.5
            || abs(newSize.height - lastReportedSize.height) > 0.5 {
            lastReportedSize = newSize
            onSizeChanged?()
        }
    }
}
