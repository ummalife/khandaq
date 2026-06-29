// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let AvatarVerticalOffset = 10.0
}

class StaticTableAvatarCell: StaticTableBaseCell {
    fileprivate var didTapOnAvatar: ((StaticTableAvatarCell) -> Void)?

    fileprivate var button: UIButton!
    fileprivate var cameraBadge: UIImageView!
    fileprivate var nameLabel: UILabel!
    fileprivate var nameToBottomConstraint: Constraint?
    fileprivate var avatarToBottomConstraint: Constraint?

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let avatarModel = model as? StaticTableAvatarCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        selectionStyle = .none
        backgroundColor = .clear
        customContentView.backgroundColor = .clear

        button.isUserInteractionEnabled = avatarModel.userInteractionEnabled
        button.setImage(avatarModel.avatar, for: UIControlState())
        didTapOnAvatar = avatarModel.didTapOnAvatar

        cameraBadge.isHidden = !avatarModel.showCameraBadge
        cameraBadge.backgroundColor = theme.colorForType(.NormalBackground)
        cameraBadge.tintColor = theme.colorForType(.NormalText)

        let name = avatarModel.name
        nameLabel.text = name
        nameLabel.textColor = theme.colorForType(.NormalText)
        let hasName = !(name?.isEmpty ?? true)
        nameLabel.isHidden = !hasName
        // Pin the cell bottom to the name when present, otherwise to the avatar (own-profile screen).
        if hasName {
            avatarToBottomConstraint?.deactivate()
            nameToBottomConstraint?.activate()
        } else {
            nameToBottomConstraint?.deactivate()
            avatarToBottomConstraint?.activate()
        }
    }

    override func createViews() {
        super.createViews()

        button = UIButton()
        button.layer.cornerRadius = StaticTableAvatarCellModel.Constants.AvatarImageSize / 2
        button.layer.masksToBounds = true
        button.addTarget(self, action: #selector(StaticTableAvatarCell.buttonPressed), for: .touchUpInside)
        customContentView.addSubview(button)

        cameraBadge = UIImageView()
        cameraBadge.isHidden = true
        cameraBadge.contentMode = .center
        cameraBadge.layer.cornerRadius = 16.0
        cameraBadge.layer.masksToBounds = true
        cameraBadge.isUserInteractionEnabled = false
        if #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 15.0, weight: .regular)
            cameraBadge.image = UIImage(systemName: "camera.fill", withConfiguration: config)
        }
        customContentView.addSubview(cameraBadge)

        nameLabel = UILabel()
        nameLabel.font = UIFont.systemFont(ofSize: 22.0, weight: .bold)
        nameLabel.textAlignment = .center
        nameLabel.isHidden = true
        customContentView.addSubview(nameLabel)
    }

    override func installConstraints() {
        super.installConstraints()

        button.snp.makeConstraints {
            $0.centerX.equalTo(customContentView)
            $0.top.equalTo(customContentView).offset(Constants.AvatarVerticalOffset)
            avatarToBottomConstraint = $0.bottom.equalTo(customContentView).offset(-Constants.AvatarVerticalOffset).constraint
            $0.size.equalTo(StaticTableAvatarCellModel.Constants.AvatarImageSize)
        }

        cameraBadge.snp.makeConstraints {
            $0.trailing.equalTo(button)
            $0.bottom.equalTo(button)
            $0.size.equalTo(32.0)
        }

        nameLabel.snp.makeConstraints {
            $0.top.equalTo(button.snp.bottom).offset(14.0)
            $0.leading.greaterThanOrEqualTo(customContentView).offset(16.0)
            $0.trailing.lessThanOrEqualTo(customContentView).offset(-16.0)
            $0.centerX.equalTo(customContentView)
            nameToBottomConstraint = $0.bottom.equalTo(customContentView).offset(-Constants.AvatarVerticalOffset).constraint
        }

        avatarToBottomConstraint?.activate()
        nameToBottomConstraint?.deactivate()
    }
}

// Accessibility
extension StaticTableAvatarCell {
    override var isAccessibilityElement: Bool {
        get {
            return true
        }
        set {}
    }

    override var accessibilityLabel: String? {
        get {
            return String(localized: "accessibility_avatar_button_label")
        }
        set {}
    }

    override var accessibilityHint: String? {
        get {
            return button.isUserInteractionEnabled ? String(localized: "accessibility_avatar_button_hint") : nil
        }
        set {}
    }

    override var accessibilityTraits: UIAccessibilityTraits {
        get {
            var traits = UIAccessibilityTraitImage

            if button.isUserInteractionEnabled {
                traits |= UIAccessibilityTraitButton
            }

            return traits
        }
        set {}
    }
}

extension StaticTableAvatarCell {
    @objc func buttonPressed() {
        didTapOnAvatar?(self)
    }
}
