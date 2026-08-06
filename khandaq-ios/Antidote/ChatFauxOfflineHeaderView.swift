// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import SnapKit

fileprivate struct Constants {
    static let verticalOffset = 10.0
    static let maxLabelWidth: CGFloat = 290.0
    static let chipHInset: CGFloat = 12.0
    static let chipVInset: CGFloat = 6.0
}

class ChatFauxOfflineHeaderView: UIView {
    fileprivate var chip: UIView!
    fileprivate var label: UILabel!

    init(theme: Theme) {
        super.init(frame: CGRect.zero)

        // KHANDAQ (#iOS offline-note): NO white block on light theme — the note sits over the doodle
        // wallpaper as a subtle centered rounded chip (same surface as the floating date pill), so it
        // reads as a Telegram-style system note rather than a full-width white bar.
        backgroundColor = .clear
        createViews(theme: theme)
        installConstraints()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

private extension ChatFauxOfflineHeaderView {
    func createViews(theme: Theme) {
        chip = UIView()
        // KHANDAQ (#iOS offline-note): a SUBTLE translucent dark pill — light enough that it doesn't
        // stand out as a dark blob over the chat doodle, but still legible. Small, quiet system note.
        chip.backgroundColor = UIColor(white: 0.0, alpha: 0.28)
        chip.layer.cornerRadius = 13.0
        chip.layer.masksToBounds = true
        addSubview(chip)

        label = UILabel()
        label.text = String(localized: "chat_pending_faux_offline_messages")
        // Smaller + light weight so it reads as a low-key hint, not a headline.
        label.font = UIFont.khandaqFontWithSize(12.0, weight: .light)
        label.textAlignment = .center
        label.textColor = UIColor(white: 1.0, alpha: 0.9)
        label.numberOfLines = 0
        label.preferredMaxLayoutWidth = Constants.maxLabelWidth
        chip.addSubview(label)
    }

    func installConstraints() {
        // KHANDAQ (#iOS offline-note): measure/wrap at the real width and never clip the last line —
        // the chip hugs the label, the label is width-capped, and the whole thing centers with side
        // margins so long text wraps instead of being cut off ("…Ему" truncation).
        label.snp.makeConstraints {
            $0.edges.equalTo(chip).inset(UIEdgeInsets(top: Constants.chipVInset, left: Constants.chipHInset,
                                                      bottom: Constants.chipVInset, right: Constants.chipHInset))
            $0.width.lessThanOrEqualTo(Constants.maxLabelWidth)
        }
        chip.snp.makeConstraints {
            $0.top.equalTo(self).offset(Constants.verticalOffset)
            $0.bottom.equalTo(self).offset(-Constants.verticalOffset)
            $0.centerX.equalTo(self)
            $0.leading.greaterThanOrEqualTo(self).offset(16)
            $0.trailing.lessThanOrEqualTo(self).offset(-16)
        }
    }
}
