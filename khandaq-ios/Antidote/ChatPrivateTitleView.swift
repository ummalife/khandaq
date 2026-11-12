// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

/// The header of the conversation with one person, next to the back button: the person's tile, the
/// name on one line and a chevron that turns over while the dossier is open. The whole view is one
/// button that opens and closes the dossier; the presence line lives in the band under the bar.
final class ChatPrivateTitleView: UIControl {
    /// A navigation bar measures a custom bar item once; the controller re-installs the item when the
    /// size of this view changes.
    var onSizeChanged: (() -> Void)?

    /// False for "For yourself" and for a removed contact: nothing to unfold, so no chevron and no
    /// button.
    var opensDossier = true {
        didSet {
            chevron.isHidden = !opensDossier
            isUserInteractionEnabled = opensDossier
            updateAccessibility()
            reportSizeIfChanged()
        }
    }

    /// What the band under the header says, read with the name.
    var spokenPresence = "" {
        didSet {
            updateAccessibility()
        }
    }

    /// The widest the name may run before it is cut short, so the buttons on the right keep their room.
    var maximumNameWidth: CGFloat = 160 {
        didSet {
            guard abs(maximumNameWidth - oldValue) > 0.5 else {
                return
            }
            nameWidthConstraint?.update(offset: maximumNameWidth)
            reportSizeIfChanged()
        }
    }

    private(set) var isExpanded = false
    private(set) var name = ""

    private let tile = AirTileView(side: KQSize.tileDesk)
    private let nameLabel = UILabel()
    private let chevron = UIImageView(image: AirIcon.image("chevron-down"))
    private var nameWidthConstraint: Constraint?
    private var lastReportedSize: CGSize = .zero

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        layer.cornerRadius = KQRadius.control
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityIdentifier = "air.chat.title"

        nameLabel.font = ChatPrivateTitleView.nameFont()
        nameLabel.textColor = KQColor.ink
        nameLabel.numberOfLines = 1
        nameLabel.lineBreakMode = .byTruncatingTail
        nameLabel.textAlignment = .natural

        chevron.tintColor = KQColor.ink3
        chevron.contentMode = .scaleAspectFit

        let stack = UIStackView(arrangedSubviews: [tile, nameLabel, chevron])
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = KQSpace.x8
        stack.setCustomSpacing(KQSpace.x4, after: nameLabel)
        stack.isUserInteractionEnabled = false
        addSubview(stack)

        stack.snp.makeConstraints {
            $0.top.bottom.equalToSuperview()
            $0.leading.equalToSuperview().offset(KQSpace.x4)
            $0.trailing.equalToSuperview().offset(-KQSpace.x4)
        }
        tile.snp.makeConstraints {
            $0.size.equalTo(KQSize.tileDesk)
        }
        chevron.snp.makeConstraints {
            $0.size.equalTo(KQSize.iconSmall)
        }
        nameLabel.snp.makeConstraints {
            nameWidthConstraint = $0.width.lessThanOrEqualTo(maximumNameWidth).constraint
        }
        snp.makeConstraints {
            $0.height.equalTo(KQSize.targetIos)
        }
        // The name grows only as far as the bar has room for and is cut short with "...": at the
        // accessibility text sizes a long press shows it whole (the Large Content Viewer). A tap still opens
        // the dossier.
        AirLargeContent.show(on: self, title: nil, image: nil)
        updateAccessibility()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// The view of a bar item: the bar shows the viewer from the properties of the view.
    override func didMoveToSuperview() {
        super.didMoveToSuperview()
        AirLargeContent.placeInteraction(on: self)
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        AirLargeContent.placeInteraction(on: self)
    }

    override var isHighlighted: Bool {
        didSet {
            backgroundColor = isHighlighted ? KQColor.selected : .clear
        }
    }

    func configure(name: String, style: AirTileView.Style, photo: UIImage?) {
        self.name = name
        nameLabel.text = name
        largeContentTitle = name
        tile.configure(style: style, photo: photo)
        updateAccessibility()
        reportSizeIfChanged()
    }

    /// The chevron turns over with the dossier, over the same 440 ms (a fade of 120 ms with Reduce Motion).
    func setExpanded(_ expanded: Bool, animated: Bool) {
        guard expanded != isExpanded else {
            return
        }
        isExpanded = expanded
        let turn = {
            self.chevron.transform = expanded ? CGAffineTransform(rotationAngle: .pi) : .identity
        }
        if animated {
            if UIAccessibilityIsReduceMotionEnabled() {
                UIView.transition(with: chevron, duration: KQMotion.duration(KQMotion.reveal), options: [.transitionCrossDissolve], animations: turn, completion: nil)
            }
            else {
                UIView.animate(withDuration: KQMotion.reveal, delay: 0, options: [.curveEaseOut, .beginFromCurrentState], animations: turn, completion: nil)
            }
        }
        else {
            turn()
        }
        updateAccessibility()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            nameLabel.font = ChatPrivateTitleView.nameFont()
            reportSizeIfChanged()
        }
    }

    /// The heading role, grown with the text size only as far as the 44 point bar has room for.
    private static func nameFont() -> UIFont {
        let font = KQFont.font(.heading)
        return font.pointSize > 22 ? font.withSize(22) : font
    }

    private func updateAccessibility() {
        accessibilityLabel = name
        accessibilityValue = spokenPresence.isEmpty ? nil : spokenPresence
        accessibilityTraits = opensDossier ? UIAccessibilityTraitButton : UIAccessibilityTraitHeader
        if opensDossier {
            accessibilityHint = String(localized: isExpanded ? "air_dossier_close_hint" : "air_chat_dossier_hint")
        }
        else {
            accessibilityHint = nil
        }
    }

    private func reportSizeIfChanged() {
        invalidateIntrinsicContentSize()
        setNeedsLayout()
        layoutIfNeeded()

        let newSize = systemLayoutSizeFitting(UILayoutFittingCompressedSize)
        if abs(newSize.width - lastReportedSize.width) > 0.5 || abs(newSize.height - lastReportedSize.height) > 0.5 {
            lastReportedSize = newSize
            onSizeChanged?()
        }
    }
}
