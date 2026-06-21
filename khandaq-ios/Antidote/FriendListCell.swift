// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

class FriendListCell: BaseCell {
    struct Constants {
        // KHANDAQ design (Figma): match the chat cell — 56pt avatar, 16pt side margin, 12pt gap.
        static let AvatarSize = 56.0
        static let AvatarLeftOffset = 16.0
        static let AvatarRightOffset = 12.0

        static let TopLabelHeight = 20.0
        static let MinimumBottomLabelHeight = 16.0

        static let VerticalOffset = 8.0
        static let RightOffset = -16.0
    }

    fileprivate var avatarView: ImageViewWithStatus!
    fileprivate var topLabel: UILabel!
    fileprivate var bottomLabel: UILabel!
    fileprivate var arrowImageView: UIImageView!

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let friendModel = model as? FriendListCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        separatorInset.left = CGFloat(Constants.AvatarLeftOffset + Constants.AvatarSize + Constants.AvatarRightOffset)

        avatarView.imageView.image = friendModel.avatar
        avatarView.userStatusView.isHidden = true

        topLabel.text = friendModel.topText
        topLabel.textColor = theme.colorForType(.NormalText)

        bottomLabel.text = friendModel.bottomText
        bottomLabel.textColor = friendModel.presenceIsOnline
            ? theme.colorForType(.OnlineStatus)
            : theme.colorForType(.FriendCellStatus)
        bottomLabel.numberOfLines = friendModel.multilineBottomtext ? 0 : 1

        // KHANDAQ design (Figma): contact rows have no trailing chevron — avatar + name + status only.
        arrowImageView.isHidden = true

        accessibilityLabel = friendModel.accessibilityLabel
        accessibilityValue = friendModel.accessibilityValue
    }

    override func createViews() {
        super.createViews()

        avatarView = ImageViewWithStatus()
        contentView.addSubview(avatarView)

        topLabel = UILabel()
        topLabel.font = UIFont.systemFont(ofSize: 16.0, weight: .semibold)
        contentView.addSubview(topLabel)

        bottomLabel = UILabel()
        bottomLabel.font = UIFont.systemFont(ofSize: 14.0)
        contentView.addSubview(bottomLabel)

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

        topLabel.snp.makeConstraints {
            $0.leading.equalTo(avatarView.snp.trailing).offset(Constants.AvatarRightOffset)
            $0.trailing.equalTo(contentView).offset(Constants.RightOffset)
            $0.top.equalTo(contentView).offset(Constants.VerticalOffset)
            $0.height.equalTo(Constants.TopLabelHeight)
        }

        bottomLabel.snp.makeConstraints {
            $0.leading.trailing.equalTo(topLabel)
            $0.top.equalTo(topLabel.snp.bottom).offset(4.0)
            $0.bottom.equalTo(contentView).offset(-Constants.VerticalOffset)
            $0.height.greaterThanOrEqualTo(Constants.MinimumBottomLabelHeight)
        }

        arrowImageView.snp.makeConstraints {
            $0.centerY.equalTo(contentView)
            $0.trailing.equalTo(contentView).offset(Constants.RightOffset)
        }
    }
}

// Accessibility
extension FriendListCell {
    override var isAccessibilityElement: Bool {
        get {
            return true
        }
        set {}
    }

    // Label and value are set in setupWithTheme:model: method
    // var accessibilityLabel: String?
    // var accessibilityValue: String?

    override var accessibilityTraits: UIAccessibilityTraits {
        get {
            return UIAccessibilityTraitButton
        }
        set {}
    }
}
