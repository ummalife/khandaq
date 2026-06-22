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

        statusImageView.image = UIImage(named: "chat-delivered-checkmark")?.withRenderingMode(.alwaysTemplate)
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
        }

        statusImageView.snp.makeConstraints {
            $0.width.height.equalTo(14)
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
