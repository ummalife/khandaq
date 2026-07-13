// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

class ChatIncomingTextCell: ChatBaseTextCell {
    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        bubbleNormalBackground = theme.colorForType(.ChatIncomingBubble)
        bubbleView.backgroundColor = bubbleNormalBackground
        bubbleView.tintColor = theme.colorForType(.LinkText)
        bubbleView.font = UIFont.preferredFont(forTextStyle: .body)
        // Last: the font assignment above wipes per-range attributes.
        applySenderHeaderStyling(theme, model: model)
    }

    override func installConstraints() {
        super.installConstraints()

        bubbleView.snp.makeConstraints {
            // KHANDAQ (#162): anchor to movableContentView, NOT contentView. The day-pill /
            // «Непрочитанные сообщения» reserved band pushes movableContentView down; a bubble pinned
            // to contentView ignored that offset, so the separators were drawn ON TOP of the bubble
            // and the self-sized row never grew to fit them.
            $0.top.equalTo(movableContentView).offset(ChatBaseTextCell.Constants.BubbleVerticalOffset)
            $0.bottom.equalTo(movableContentView).offset(-ChatBaseTextCell.Constants.BubbleVerticalOffset)
            // Horizontal stays on contentView: movableContentView is pre-shifted -39 in
            // DateonmessageMode, which would clip the bubble off the left screen edge.
            $0.leading.equalTo(contentView).offset(ChatBaseTextCell.Constants.BubbleHorizontalOffset)
        }
    }
}

// Accessibility
extension ChatIncomingTextCell {
    override var accessibilityLabel: String? {
        get {
            return String(localized: "accessibility_incoming_message_label")
        }
        set {}
    }
}
