// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

final class ChatReplyPreviewView: UIView {
    var onCancel: (() -> Void)?

    private let accentBar = UIView()
    private let headerLabel = UILabel()
    private let previewLabel = UILabel()
    private let cancelButton = UIButton(type: .system)

    /// KHANDAQ (beta 8262): holds the bar at no height while it is hidden.
    private var collapsedConstraint: Constraint?

    override init(frame: CGRect) {
        super.init(frame: frame)

        accentBar.layer.cornerRadius = 1.5
        accentBar.clipsToBounds = true

        headerLabel.font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        headerLabel.numberOfLines = 1

        previewLabel.font = UIFont.systemFont(ofSize: 13)
        previewLabel.numberOfLines = 2

        if #available(iOS 13.0, *) {
            cancelButton.setImage(UIImage(systemName: "xmark"), for: .normal)
        }
        else {
            cancelButton.setTitle("×", for: .normal)
        }
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)

        addSubview(accentBar)
        addSubview(headerLabel)
        addSubview(previewLabel)
        addSubview(cancelButton)

        accentBar.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(8)
            $0.top.equalToSuperview().offset(8)
            $0.bottom.equalToSuperview().offset(-8).priority(999)
            $0.width.equalTo(3)
        }

        cancelButton.snp.makeConstraints {
            $0.trailing.equalToSuperview().offset(-4)
            $0.centerY.equalToSuperview()
            $0.width.height.equalTo(36)
        }

        headerLabel.snp.makeConstraints {
            $0.leading.equalTo(accentBar.snp.trailing).offset(8)
            $0.trailing.equalTo(cancelButton.snp.leading).offset(-4)
            $0.top.equalToSuperview().offset(6)
        }

        previewLabel.snp.makeConstraints {
            $0.leading.trailing.equalTo(headerLabel)
            $0.top.equalTo(headerLabel.snp.bottom).offset(2)
            $0.bottom.equalToSuperview().offset(-6).priority(999)
        }

        // KHANDAQ (beta 8262): the bar stands between the log and the field, and hidden it still took the
        // height of its content: 16 pt before any reply and 45 pt after one, sent or cancelled, until the
        // chat was opened again. The log ended at that blank band, so a message scrolled near the field was
        // cut off above it. Hidden, the bar now takes no room and the log reaches the field.
        snp.makeConstraints {
            collapsedConstraint = $0.height.equalTo(0).constraint
        }
        isHidden = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func apply(theme: Theme) {
        backgroundColor = theme.colorForType(.ChatIncomingBubble)
        accentBar.backgroundColor = theme.colorForType(.LinkText)
        headerLabel.textColor = theme.colorForType(.LinkText)
        previewLabel.textColor = theme.colorForType(.ChatListCellMessage)
        cancelButton.tintColor = theme.colorForType(.ChatListCellMessage)
    }

    func show(meta: MessageReplyHelper.ReplyMeta, theme: Theme) {
        apply(theme: theme)
        // KHANDAQ (0.2.59): over a message of yours the bar says "In reply to yourself" ("In reply to" + "You" put
        // "You" in the wrong grammatical case in Russian); only a message of yours has a local name
        // (ChatReplyController).
        headerLabel.text = meta.localSenderName != nil
            ? String(localized: "chat_reply_header_self")
            : String(localized: "chat_reply_header", meta.senderName)
        previewLabel.text = MessageReplyHelper.previewForDisplay(meta.previewText, maxLen: 120)
        collapsedConstraint?.deactivate()
        isHidden = false
    }

    func hidePreview() {
        isHidden = true
        collapsedConstraint?.activate()
    }

    @objc private func cancelTapped() {
        onCancel?()
    }
}
