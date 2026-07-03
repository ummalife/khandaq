// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

private struct Constants {
    static let VerticalOffset = 12.0
}

class StaticTableButtonCell: StaticTableBaseCell {
    fileprivate var label: UILabel!
    fileprivate var iconImageView: UIImageView!

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let buttonModel = model as? StaticTableButtonCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        label.text = buttonModel.title
        let tint = buttonModel.destructive
            ? theme.colorForType(.DestructiveText)
            : theme.colorForType(.LinkText)
        label.textColor = tint

        // KHANDAQ (Figma): with an icon the icon+label form a centered pill button (MyID actions);
        // without one it stays the plain edge-to-edge label (e.g. "Выйти").
        if let iconName = buttonModel.iconName, !iconName.isEmpty {
            var image: UIImage?
            if #available(iOS 13.0, *) {
                image = UIImage(systemName: iconName)
            }
            iconImageView.image = image?.withRenderingMode(.alwaysTemplate)
            iconImageView.tintColor = tint
            iconImageView.isHidden = false
            label.textAlignment = .natural
            applyIconLayout()
        }
        else {
            iconImageView.image = nil
            iconImageView.isHidden = true
            label.textAlignment = .natural
            applyPlainLayout()
        }
    }

    override func createViews() {
        super.createViews()

        label = UILabel()
        customContentView.addSubview(label)

        iconImageView = UIImageView()
        iconImageView.contentMode = .scaleAspectFit
        iconImageView.setContentHuggingPriority(.required, for: .horizontal)
        customContentView.addSubview(iconImageView)
    }

    override func installConstraints() {
        super.installConstraints()
        applyPlainLayout()
    }

    /// Plain full-width label (default button look).
    fileprivate func applyPlainLayout() {
        iconImageView.snp.removeConstraints()
        label.snp.remakeConstraints {
            $0.leading.trailing.equalTo(customContentView)
            $0.top.equalTo(customContentView).offset(Constants.VerticalOffset)
            $0.bottom.equalTo(customContentView).offset(-Constants.VerticalOffset)
        }
    }

    /// Left-aligned [icon][label] group (Figma MyID pill buttons).
    fileprivate func applyIconLayout() {
        iconImageView.snp.remakeConstraints {
            $0.width.height.equalTo(20.0)
            $0.centerY.equalTo(customContentView)
            $0.leading.equalTo(customContentView)
        }
        label.snp.remakeConstraints {
            $0.leading.equalTo(iconImageView.snp.trailing).offset(10.0)
            $0.trailing.lessThanOrEqualTo(customContentView)
            $0.top.equalTo(customContentView).offset(Constants.VerticalOffset)
            $0.bottom.equalTo(customContentView).offset(-Constants.VerticalOffset)
        }
    }
}

// Accessibility
extension StaticTableButtonCell {
    override var isAccessibilityElement: Bool {
        get {
            return true
        }
        set {}
    }

    override var accessibilityLabel: String? {
        get {
            return label.text
        }
        set {}
    }

    override var accessibilityTraits: UIAccessibilityTraits {
        get {
            return UIAccessibilityTraitButton
        }
        set {}
    }
}
