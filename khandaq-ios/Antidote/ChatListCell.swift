// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

class ChatListCell: BaseCell {
    struct Constants {
        // KHANDAQ design (Figma): 56pt avatar, 16pt side margin, 12pt gap to text.
        static let AvatarSize = 56.0
        static let AvatarLeftOffset = 16.0
        static let AvatarRightOffset = 12.0

        static let NicknameLabelHeight = 20.0
        static let PresenceLabelHeight = 16.0
        static let MessageLabelHeight = 18.0

        static let NicknameToDateMinOffset = 5.0
        static let DateToArrowOffset = 5.0

        static let RightOffset = -16.0
        static let VerticalOffset = 8.0
    }

    fileprivate var avatarView: ImageViewWithStatus!
    fileprivate var nicknameLabel: UILabel!
    fileprivate var presenceLabel: UILabel!
    fileprivate var messageLabel: UILabel!
    fileprivate var dateLabel: UILabel!
    fileprivate var arrowImageView: UIImageView!
    // KHANDAQ (#iOS favorite-marker): star shown after the name for favorited chats.
    fileprivate var favoriteImageView: UIImageView!
    // KHANDAQ (#30): numeric unread-count badge (Telegram-style pill) on the message row.
    fileprivate var unreadBadge: PaddedLabel!
    // KHANDAQ design: collapses to 0 height for chat rows (name + message only).
    fileprivate var presenceHeightConstraint: Constraint?

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let chatModel = model as? ChatListCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        separatorInset.left = CGFloat(Constants.AvatarLeftOffset + Constants.AvatarSize + Constants.AvatarRightOffset)

        avatarView.imageView.image = chatModel.avatar
        avatarView.userStatusView.isHidden = true

        nicknameLabel.text = chatModel.nickname
        nicknameLabel.textColor = theme.colorForType(.NormalText)

        // KHANDAQ (#iOS favorite-marker): small star after the name for favorited chats (nil image = no
        // star = 0 width, so the row is unchanged for non-favorites). SF Symbol → iOS 13+, nil on older.
        if chatModel.isFavorite, #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 12.0, weight: .semibold)
            favoriteImageView.image = UIImage(systemName: "star.fill", withConfiguration: config)
            favoriteImageView.tintColor = theme.colorForType(.LinkText)
        }
        else {
            favoriteImageView.image = nil
        }

        // KHANDAQ design (Figma): a chat row is exactly two lines — name + last message. The online /
        // last-seen presence is shown in Контакты, not here, so the presence line is always collapsed.
        presenceLabel.text = nil
        presenceLabel.isHidden = true
        presenceHeightConstraint?.update(offset: 0.0)

        messageLabel.text = chatModel.message
        messageLabel.textColor = chatModel.isDraft
            ? theme.colorForType(.BusyStatus)
            : theme.colorForType(.ChatListCellMessage)

        dateLabel.text = chatModel.dateText
        dateLabel.textColor = theme.colorForType(.ChatListCellMessage)

        // KHANDAQ design (Figma): rows have no unread tint and no trailing chevron — the green count
        // badge alone marks an unread chat.
        backgroundColor = .clear
        arrowImageView.isHidden = true

        // KHANDAQ (#30): numeric unread badge (caps at 99+).
        let unread = chatModel.unreadCount
        if unread > 0 {
            unreadBadge.isHidden = false
            unreadBadge.text = unread > 99 ? "99+" : "\(unread)"
            unreadBadge.backgroundColor = theme.colorForType(.ChatListCellUnreadArrowBackground)
        } else {
            unreadBadge.isHidden = true
            unreadBadge.text = nil
        }
    }

    override func createViews() {
        super.createViews()

        avatarView = ImageViewWithStatus()
        contentView.addSubview(avatarView)

        nicknameLabel = UILabel()
        nicknameLabel.font = UIFont.systemFont(ofSize: 16.0, weight: .semibold)
        contentView.addSubview(nicknameLabel)

        presenceLabel = UILabel()
        presenceLabel.font = UIFont.systemFont(ofSize: 14.0)
        contentView.addSubview(presenceLabel)

        messageLabel = UILabel()
        messageLabel.font = UIFont.systemFont(ofSize: 14.0)
        contentView.addSubview(messageLabel)

        dateLabel = UILabel()
        dateLabel.font = UIFont.systemFont(ofSize: 12.0)
        contentView.addSubview(dateLabel)

        // KHANDAQ (#iOS favorite-marker): small star after the name for favorited chats. No explicit width,
        // so a nil image (non-favorite) collapses to 0 and the date sits where it does today.
        favoriteImageView = UIImageView()
        favoriteImageView.contentMode = .scaleAspectFit
        favoriteImageView.setContentHuggingPriority(.required, for: .horizontal)
        favoriteImageView.setContentCompressionResistancePriority(.required, for: .horizontal)
        contentView.addSubview(favoriteImageView)

        let image = UIImage(named: "right-arrow")!.flippedToCorrectLayout()

        arrowImageView = UIImageView(image: image)
        arrowImageView.setContentCompressionResistancePriority(UILayoutPriority.required, for: .horizontal)
        contentView.addSubview(arrowImageView)

        unreadBadge = PaddedLabel()
        unreadBadge.font = UIFont.systemFont(ofSize: 12.0, weight: .semibold)
        unreadBadge.textColor = .white
        unreadBadge.textAlignment = .center
        unreadBadge.layer.masksToBounds = true
        unreadBadge.layer.cornerRadius = 9.0
        unreadBadge.isHidden = true
        unreadBadge.setContentCompressionResistancePriority(UILayoutPriority.required, for: .horizontal)
        contentView.addSubview(unreadBadge)
    }

    override func installConstraints() {
        super.installConstraints()

        avatarView.snp.makeConstraints {
            $0.leading.equalTo(contentView).offset(Constants.AvatarLeftOffset)
            $0.centerY.equalTo(contentView)
            $0.size.equalTo(Constants.AvatarSize)
        }

        nicknameLabel.snp.makeConstraints {
            $0.leading.equalTo(avatarView.snp.trailing).offset(Constants.AvatarRightOffset)
            $0.top.equalTo(contentView).offset(Constants.VerticalOffset)
            $0.height.equalTo(Constants.NicknameLabelHeight)
        }

        messageLabel.snp.makeConstraints {
            $0.leading.equalTo(nicknameLabel)
            $0.trailing.equalTo(contentView).offset(Constants.RightOffset)
            $0.top.equalTo(presenceLabel.snp.bottom).offset(4.0)
            $0.bottom.equalTo(contentView).offset(-Constants.VerticalOffset)
            $0.height.equalTo(Constants.MessageLabelHeight)
        }

        presenceLabel.snp.makeConstraints {
            $0.leading.trailing.equalTo(nicknameLabel)
            $0.top.equalTo(nicknameLabel.snp.bottom)
            presenceHeightConstraint = $0.height.equalTo(Constants.PresenceLabelHeight).constraint
        }

        favoriteImageView.snp.makeConstraints {
            $0.leading.equalTo(nicknameLabel.snp.trailing).offset(4.0)
            $0.centerY.equalTo(nicknameLabel)
        }

        dateLabel.snp.makeConstraints {
            $0.leading.greaterThanOrEqualTo(favoriteImageView.snp.trailing).offset(Constants.NicknameToDateMinOffset)
            $0.trailing.equalTo(contentView).offset(Constants.RightOffset)
            $0.top.equalTo(nicknameLabel)
            $0.height.equalTo(nicknameLabel)
        }

        arrowImageView.snp.makeConstraints {
            $0.centerY.equalTo(dateLabel)
            $0.leading.greaterThanOrEqualTo(dateLabel.snp.trailing).offset(Constants.DateToArrowOffset)
            $0.trailing.equalTo(contentView).offset(Constants.RightOffset)
        }

        // KHANDAQ (#30): unread pill pinned to the trailing edge of the message row.
        unreadBadge.snp.makeConstraints {
            $0.trailing.equalTo(contentView).offset(Constants.RightOffset)
            $0.centerY.equalTo(messageLabel)
            $0.height.equalTo(18)
            $0.width.greaterThanOrEqualTo(18)
        }
    }
}

// Accessibility
extension ChatListCell {
    override var isAccessibilityElement: Bool {
        get {
            return true
        }
        set {}
    }

    override var accessibilityLabel: String? {
        get {
            var label = nicknameLabel.text ?? ""
            if let presence = presenceLabel.text, !presence.isEmpty {
                label += ", " + presence
            }

            return label
        }
        set {}
    }

    override var accessibilityValue: String? {
        get {
            return messageLabel.text! + ", " + dateLabel.text!
        }
        set {}
    }

    override var accessibilityTraits: UIAccessibilityTraits {
        get {
                return UIAccessibilityTraitSelected
        }
        set {}
    }
}

/// KHANDAQ (#30): UILabel with horizontal padding so the unread pill looks right for 2+ digits / "99+".
private class PaddedLabel: UILabel {
    private let hPad: CGFloat = 6.0

    override func drawText(in rect: CGRect) {
        super.drawText(in: rect.insetBy(dx: hPad, dy: 0))
    }

    override var intrinsicContentSize: CGSize {
        let size = super.intrinsicContentSize
        return CGSize(width: size.width + hPad * 2, height: size.height)
    }
}
