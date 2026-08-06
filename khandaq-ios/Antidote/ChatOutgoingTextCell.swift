// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

class ChatOutgoingTextCell: ChatBaseTextCell {
    private var statusImageView: UIImageView!

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let textModel = model as? ChatOutgoingTextCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        bubbleNormalBackground = theme.colorForType(.ChatOutgoingBubble)
        if !textModel.delivered {
            if !textModel.sentpush {
                bubbleNormalBackground = theme.colorForType(.ChatOutgoingUnreadBubble)
            } else {
                bubbleNormalBackground = theme.colorForType(.ChatOutgoingSentPushBubble)
            }
        }

        // KHANDAQ design (Figma): outgoing bubbles are pale green with DARK text (was white-on-purple).
        bubbleView.textColor = theme.colorForType(.NormalText)
        bubbleView.backgroundColor = bubbleNormalBackground
        bubbleView.tintColor = theme.colorForType(.NormalText)
        bubbleView.font = UIFont.preferredFont(forTextStyle: .body)

        statusImageView.image = MessageStatusIcon.image(delivered: textModel.delivered)
        if textModel.delivered {
            // KHANDAQ design: green delivered checkmark (was a hard-coded blue).
            statusImageView.tintColor = theme.colorForType(.LinkText)
        } else {
            statusImageView.tintColor = theme.colorForType(.ChatInformationText)
        }
        statusImageView.isHidden = false
    }

    override func createViews() {
        super.createViews()
        statusImageView = UIImageView()
        statusImageView.contentMode = .scaleAspectFit
        bubbleView.addSubview(statusImageView)
    }

    override func installConstraints() {
        super.installConstraints()

        bubbleView.snp.makeConstraints {
            $0.top.equalTo(movableContentView).offset(ChatBaseTextCell.Constants.BubbleVerticalOffset)
            $0.bottom.equalTo(movableContentView).offset(-ChatBaseTextCell.Constants.BubbleVerticalOffset)
            $0.trailing.equalTo(movableContentView).offset(-ChatBaseTextCell.Constants.BubbleHorizontalOffset)
            // KHANDAQ (#iOS status-spacing): reserve a minimum bubble width so the delivery checkmark
            // (bottom-right overlay) never crowds a SHORT outgoing message. Long/multi-line bubbles are
            // already wider, so this only affects short texts. Outgoing-only — the shared BubbleView and
            // incoming cells are untouched.
            $0.width.greaterThanOrEqualTo(76)
        }

        statusImageView.snp.makeConstraints {
            // KHANDAQ (#107b): fix the height; width follows the glyph (single vs double check) intrinsically.
            $0.height.equalTo(14)
            $0.trailing.equalTo(bubbleView).offset(-6)
            $0.bottom.equalTo(bubbleView).offset(-4)
        }
    }
}

// Accessibility
extension ChatOutgoingTextCell {
    override var accessibilityLabel: String? {
        get {
            return String(localized: "accessibility_outgoing_message_label")
        }
        set {}
    }
}

/// KHANDAQ (#107b): outgoing message delivery indicator. Tox reports DELIVERY only (no "read" receipt),
/// so a SINGLE check = sent-not-yet-delivered and a DOUBLE check = delivered (WhatsApp-style). The
/// returned image is `.alwaysTemplate` so the cell tints it (grey = sent, accent = delivered).
/// Defined here (not a standalone file) so it's part of the app target without a project-file edit.
enum MessageStatusIcon {
    static func image(delivered: Bool) -> UIImage? {
        guard let base = UIImage(named: "chat-delivered-checkmark") else {
            return nil
        }

        let height: CGFloat = 14.0
        let width = (base.size.height > 0) ? height * base.size.width / base.size.height : height

        if !delivered {
            return render(CGSize(width: width, height: height)) {
                base.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
            }
        }

        // Double check: the single glyph drawn twice, the second offset to the right.
        let offset = width * 0.55
        return render(CGSize(width: width + offset, height: height)) {
            base.draw(in: CGRect(x: 0, y: 0, width: width, height: height))
            base.draw(in: CGRect(x: offset, y: 0, width: width, height: height))
        }
    }

    private static func render(_ size: CGSize, _ draw: () -> Void) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: size)
        return renderer.image { _ in draw() }.withRenderingMode(.alwaysTemplate)
    }
}
