// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

class ChatListCell: BaseCell {
    struct Constants {
        static let AvatarSize = 40.0
        static let AvatarLeftOffset = 10.0
        static let AvatarRightOffset = 16.0

        static let NicknameLabelHeight = 22.0
        static let PresenceLabelHeight = 16.0
        static let MessageLabelHeight = 18.0

        static let NicknameToDateMinOffset = 5.0
        static let DateToArrowOffset = 5.0

        static let RightOffset = -7.0
        static let VerticalOffset = 3.0
    }

    fileprivate var avatarView: ImageViewWithStatus!
    fileprivate var nicknameLabel: UILabel!
    fileprivate var presenceLabel: UILabel!
    fileprivate var messageLabel: UILabel!
    fileprivate var dateLabel: UILabel!
    fileprivate var arrowImageView: UIImageView!

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

        presenceLabel.text = chatModel.presenceText
        presenceLabel.textColor = chatModel.presenceIsOnline
            ? theme.colorForType(.OnlineStatus)
            : theme.colorForType(.ChatListCellMessage)
        presenceLabel.isHidden = chatModel.presenceText.isEmpty

        messageLabel.text = chatModel.message
        messageLabel.textColor = chatModel.isDraft
            ? theme.colorForType(.BusyStatus)
            : theme.colorForType(.ChatListCellMessage)

        dateLabel.text = chatModel.dateText
        dateLabel.textColor = theme.colorForType(.ChatListCellMessage)

        backgroundColor = chatModel.isUnread ? theme.colorForType(.ChatListCellUnreadBackground) : .clear

        if (chatModel.isUnread) {
            arrowImageView.backgroundColor = theme.colorForType(.ChatListCellUnreadArrowBackground)
        } else {
            arrowImageView.backgroundColor = .clear
        }

        // HINT: make the arrow image view a nice circle shape
        arrowImageView.layer.cornerRadius = arrowImageView.frame.height / 2
    }

    override func createViews() {
        super.createViews()

        avatarView = ImageViewWithStatus()
        contentView.addSubview(avatarView)

        nicknameLabel = UILabel()
        nicknameLabel.font = UIFont.systemFont(ofSize: 18.0)
        contentView.addSubview(nicknameLabel)

        presenceLabel = UILabel()
        presenceLabel.font = UIFont.systemFont(ofSize: 13.0)
        contentView.addSubview(presenceLabel)

        messageLabel = UILabel()
        messageLabel.font = UIFont.systemFont(ofSize: 12.0)
        contentView.addSubview(messageLabel)

        dateLabel = UILabel()
        dateLabel.font = UIFont.khandaqFontWithSize(12.0, weight: .light)
        contentView.addSubview(dateLabel)

        let image = UIImage(named: "right-arrow")!.flippedToCorrectLayout()

        arrowImageView = UIImageView(image: image)
        arrowImageView.setContentCompressionResistancePriority(UILayoutPriority.required, for: .horizontal)
        contentView.addSubview(arrowImageView)
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
            $0.top.equalTo(presenceLabel.snp.bottom)
            $0.bottom.equalTo(contentView).offset(-Constants.VerticalOffset)
            $0.height.equalTo(Constants.MessageLabelHeight)
        }

        presenceLabel.snp.makeConstraints {
            $0.leading.trailing.equalTo(nicknameLabel)
            $0.top.equalTo(nicknameLabel.snp.bottom)
            $0.height.equalTo(Constants.PresenceLabelHeight)
        }

        dateLabel.snp.makeConstraints {
            $0.leading.greaterThanOrEqualTo(nicknameLabel.snp.trailing).offset(Constants.NicknameToDateMinOffset)
            $0.top.equalTo(nicknameLabel)
            $0.height.equalTo(nicknameLabel)
        }

        arrowImageView.snp.makeConstraints {
            $0.centerY.equalTo(dateLabel)
            $0.leading.greaterThanOrEqualTo(dateLabel.snp.trailing).offset(Constants.DateToArrowOffset)
            $0.trailing.equalTo(contentView).offset(Constants.RightOffset)
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
