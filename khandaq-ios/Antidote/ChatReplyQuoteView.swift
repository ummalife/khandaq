// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

final class ChatReplyQuoteView: UIControl {
    var onTap: (() -> Void)?

    private let accentBar = UIView()
    private let nameLabel = UILabel()
    private let previewLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)

        layer.cornerRadius = 6
        clipsToBounds = true

        accentBar.layer.cornerRadius = 1.5
        accentBar.clipsToBounds = true

        nameLabel.font = UIFont.systemFont(ofSize: 13, weight: .semibold)
        nameLabel.numberOfLines = 1

        previewLabel.font = UIFont.systemFont(ofSize: 13)
        previewLabel.numberOfLines = 2

        addSubview(accentBar)
        addSubview(nameLabel)
        addSubview(previewLabel)

        accentBar.snp.makeConstraints {
            $0.leading.top.bottom.equalToSuperview()
            $0.width.equalTo(3)
        }

        nameLabel.snp.makeConstraints {
            $0.leading.equalTo(accentBar.snp.trailing).offset(8)
            $0.trailing.equalToSuperview().offset(-6)
            $0.top.equalToSuperview().offset(4)
        }

        previewLabel.snp.makeConstraints {
            $0.leading.trailing.equalTo(nameLabel)
            $0.top.equalTo(nameLabel.snp.bottom).offset(2)
            $0.bottom.equalToSuperview().offset(-4)
        }

        addTarget(self, action: #selector(tapped), for: .touchUpInside)
        isHidden = true
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func bind(meta: MessageReplyHelper.ReplyMeta?, theme: Theme) {
        guard let meta = meta, !meta.previewText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            isHidden = true
            onTap = nil
            return
        }

        backgroundColor = theme.colorForType(.ChatIncomingBubble).withAlphaComponent(0.35)
        accentBar.backgroundColor = theme.colorForType(.LinkText)
        nameLabel.textColor = theme.colorForType(.LinkText)

        let name = meta.senderName.isEmpty
            ? String(localized: "chat_reply_unknown_sender")
            : meta.senderName
        nameLabel.text = name
        previewLabel.textColor = theme.colorForType(.ChatListCellMessage)
        previewLabel.text = MessageReplyHelper.previewForDisplay(meta.previewText, maxLen: 160)
        isHidden = false
    }

    @objc private func tapped() {
        onTap?()
    }
}
