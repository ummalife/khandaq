// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let TextViewMinWidth = 5.0
    static let TextViewMaxWidth = 260.0
    static let TextViewMinHeight = 10.0
    static let MapHeight = 120.0

    static let TextViewVerticalOffset = 1.0
    static let TextViewHorizontalOffset = 5.0
}

class BubbleView: UIView {
    fileprivate var textView: UITextView!
    fileprivate var mapImageView: UIImageView?
    fileprivate var locationTapRecognizer: UITapGestureRecognizer?
    let replyQuoteView = ChatReplyQuoteView()
    // KHANDAQ (#192): reaction chips rendered as a compact line under the text ("❤️ 2  👍").
    fileprivate let reactionsLabel = UILabel()
    // KHANDAQ (#iOS edited-marker): «изменено» rendered as its OWN bottom metadata sub-row (like the
    // reactions line) instead of being appended inline into the body — so it reads as a system note,
    // not as text the sender typed onto the end of their message.
    fileprivate let editedLabel = UILabel()
    // KHANDAQ (audit#2 finding 1): "relayed from history — sender not verified", its own bottom
    // sub-row under the edited marker. The transport authenticates whoever RELAYED a history row,
    // never the author it names, so until a signature says otherwise the name on the bubble is a
    // claim and the UI has to say so in words rather than let it read as established.
    fileprivate let unverifiedLabel = UILabel()
    var onReactionsTap: (() -> Void)?
    // KHANDAQ (#100): keep the quote view's tap handler in sync no matter the assignment order.
    // The cell sets onReplyQuoteTap AFTER calling bindReplyQuote, so without this didSet the quote's
    // onTap was left nil (set to the then-nil handler inside bindReplyQuote) and tapping a reply quote
    // did nothing in both 1:1 and group chats.
    var onReplyQuoteTap: (() -> Void)? {
        didSet {
            replyQuoteView.onTap = onReplyQuoteTap
        }
    }
    var onLocationTap: (() -> Void)?

    var text: String? {
        get {
            return textView.text
        }
        set {
            textView.text = newValue
        }
    }

    var attributedText: NSAttributedString? {
        get {
            return textView.attributedText
        }
        set {
            textView.attributedText = newValue
        }
    }

    var textColor: UIColor {
        get {
            return textView.textColor!
        }
        set {
            textView.textColor = newValue
            reactionsLabel.textColor = newValue.withAlphaComponent(0.9)
            // subdued so «изменено» reads as metadata (mirrors the timestamp/status tint intent).
            editedLabel.textColor = newValue.withAlphaComponent(0.5)
        }
    }

    var font: UIFont? {
        get {
            return textView.font
        }
        set {
            textView.font = newValue
        }
    }

    override var tintColor: UIColor! {
        didSet {
            textView.linkTextAttributes = [
                NSAttributedStringKey.foregroundColor.rawValue: tintColor,
                NSAttributedStringKey.underlineStyle.rawValue: NSUnderlineStyle.styleSingle.rawValue,
            ]
        }
    }

    var selectable: Bool {
        get {
            return textView.isSelectable
        }
        set {
            textView.isSelectable = newValue
        }
    }

    func setLocationMapImage(_ image: UIImage?) {
        if let image = image {
            ensureMapImageView()
            mapImageView?.image = image
            mapImageView?.isHidden = false
            textView.dataDetectorTypes = []
            installLocationTapRecognizer()
        }
        else {
            mapImageView?.isHidden = true
            mapImageView?.image = nil
            textView.dataDetectorTypes = .all
            removeLocationTapRecognizer()
        }

        updateTextConstraints(hasMap: image != nil)
    }

    func bindReplyQuote(_ meta: MessageReplyHelper.ReplyMeta?, theme: Theme) {
        replyQuoteView.bind(meta: meta, theme: theme)
        replyQuoteView.onTap = onReplyQuoteTap
        updateTextConstraints(hasMap: mapImageView?.isHidden == false)
    }

    // KHANDAQ (#192): show/hide the reaction chips line. nil/empty hides it.
    func bindReactions(_ display: String?) {
        let text = display ?? ""
        reactionsLabel.text = text
        reactionsLabel.isHidden = text.isEmpty
        updateTextConstraints(hasMap: mapImageView?.isHidden == false)
    }

    // KHANDAQ (#iOS edited-marker): show/hide the «изменено» metadata row (its own bottom sub-row).
    func bindEdited(_ isEdited: Bool) {
        editedLabel.text = String(localized: "message_edited_marker")
        editedLabel.isHidden = !isEdited
        updateTextConstraints(hasMap: mapImageView?.isHidden == false)
    }

    // KHANDAQ (audit#2 finding 1): show/hide the "sender not verified" note on relayed history rows.
    func bindUnverifiedSender(_ isUnverified: Bool) {
        unverifiedLabel.text = String(localized: "group_msg_sender_unverified")
        unverifiedLabel.isHidden = !isUnverified
        // Read out by VoiceOver as part of the bubble: the wording IS the feature, an icon alone
        // would say nothing to anyone who cannot see it.
        unverifiedLabel.accessibilityLabel = unverifiedLabel.text
        updateTextConstraints(hasMap: mapImageView?.isHidden == false)
    }

    fileprivate func ensureMapImageView() {
        guard mapImageView == nil else {
            return
        }

        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.isUserInteractionEnabled = true
        addSubview(imageView)
        mapImageView = imageView

        imageView.snp.makeConstraints {
            $0.top.equalTo(self).offset(Constants.TextViewVerticalOffset)
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset)
            $0.trailing.equalTo(self).offset(-Constants.TextViewHorizontalOffset)
            $0.width.lessThanOrEqualTo(Constants.TextViewMaxWidth)
            $0.height.equalTo(Constants.MapHeight)
        }
    }

    fileprivate func updateTextConstraints(hasMap: Bool) {
        replyQuoteView.snp.remakeConstraints {
            $0.top.equalTo(self).offset(Constants.TextViewVerticalOffset)
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset)
            $0.trailing.equalTo(self).offset(-Constants.TextViewHorizontalOffset)
        }

        textView.snp.remakeConstraints {
            if replyQuoteView.isHidden {
                if hasMap, let mapImageView = mapImageView {
                    $0.top.equalTo(mapImageView.snp.bottom).offset(Constants.TextViewVerticalOffset)
                }
                else {
                    $0.top.equalTo(self).offset(Constants.TextViewVerticalOffset)
                }
            }
            else {
                $0.top.equalTo(replyQuoteView.snp.bottom).offset(Constants.TextViewVerticalOffset)
            }

            // KHANDAQ (#iOS edited-marker): the bubble bottom is now the lowest visible of
            // {textView, reactionsLabel, editedLabel, unverifiedLabel} — only pin textView to the
            // bottom when every one of them is hidden.
            if reactionsLabel.isHidden && editedLabel.isHidden && unverifiedLabel.isHidden {
                $0.bottom.equalTo(self).offset(-Constants.TextViewVerticalOffset)
            }
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset)
            $0.trailing.equalTo(self).offset(-Constants.TextViewHorizontalOffset)

            $0.width.greaterThanOrEqualTo(Constants.TextViewMinWidth)
            $0.width.lessThanOrEqualTo(Constants.TextViewMaxWidth)
            $0.height.greaterThanOrEqualTo(Constants.TextViewMinHeight)
        }

        reactionsLabel.snp.remakeConstraints {
            $0.top.equalTo(textView.snp.bottom)
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset + 4.0)
            $0.trailing.lessThanOrEqualTo(self).offset(-Constants.TextViewHorizontalOffset)
            // pin to bottom only when it's the last visible row (nothing below it).
            if !reactionsLabel.isHidden && editedLabel.isHidden && unverifiedLabel.isHidden {
                $0.bottom.equalTo(self).offset(-4.0)
            }
        }

        // KHANDAQ (#iOS edited-marker): «изменено» sits on its OWN row below the text (and below the
        // reactions line when present), left-aligned so it never collides with the outgoing checkmark.
        editedLabel.snp.remakeConstraints {
            if reactionsLabel.isHidden {
                $0.top.equalTo(textView.snp.bottom).offset(1.0)
            }
            else {
                $0.top.equalTo(reactionsLabel.snp.bottom).offset(1.0)
            }
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset + 4.0)
            $0.trailing.lessThanOrEqualTo(self).offset(-Constants.TextViewHorizontalOffset)
            if !editedLabel.isHidden && unverifiedLabel.isHidden {
                $0.bottom.equalTo(self).offset(-4.0)
            }
        }

        // KHANDAQ (audit#2 finding 1): the unverified note is the LAST sub-row, so it owns the bubble
        // bottom whenever it is visible.
        unverifiedLabel.snp.remakeConstraints {
            if !editedLabel.isHidden {
                $0.top.equalTo(editedLabel.snp.bottom).offset(1.0)
            }
            else if !reactionsLabel.isHidden {
                $0.top.equalTo(reactionsLabel.snp.bottom).offset(1.0)
            }
            else {
                $0.top.equalTo(textView.snp.bottom).offset(1.0)
            }
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset + 4.0)
            $0.trailing.lessThanOrEqualTo(self).offset(-Constants.TextViewHorizontalOffset)
            if !unverifiedLabel.isHidden {
                $0.bottom.equalTo(self).offset(-4.0)
            }
        }
    }

    fileprivate func installLocationTapRecognizer() {
        guard locationTapRecognizer == nil else {
            return
        }

        let recognizer = UITapGestureRecognizer(target: self, action: #selector(handleLocationTap))
        addGestureRecognizer(recognizer)
        locationTapRecognizer = recognizer
    }

    fileprivate func removeLocationTapRecognizer() {
        if let recognizer = locationTapRecognizer {
            removeGestureRecognizer(recognizer)
            locationTapRecognizer = nil
        }
    }

    @objc fileprivate func handleLocationTap() {
        onLocationTap?()
    }

    @objc fileprivate func handleReactionsTap() {
        onReactionsTap?()
    }

    override init(frame: CGRect) {
        super.init(frame: frame)

        // KHANDAQ design (Figma): message bubbles use a softer ~16pt corner radius.
        layer.cornerRadius = 16.0
        layer.masksToBounds = true

        textView = UITextView()
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isScrollEnabled = false
        textView.dataDetectorTypes = .all
        textView.font = UIFont.systemFont(ofSize: 16.0)

        addSubview(replyQuoteView)
        addSubview(textView)

        reactionsLabel.font = UIFont.systemFont(ofSize: 13.0)
        reactionsLabel.isHidden = true
        reactionsLabel.isUserInteractionEnabled = true
        reactionsLabel.addGestureRecognizer(
            UITapGestureRecognizer(target: self, action: #selector(handleReactionsTap)))
        addSubview(reactionsLabel)

        editedLabel.font = UIFont.italicSystemFont(ofSize: 11.0)
        editedLabel.isHidden = true
        addSubview(editedLabel)

        // KHANDAQ (audit#2 finding 1): wraps rather than truncates — a marker nobody can read in full
        // is a marker that does not warn. Coloured, not dimmed like «изменено»: this one is a caveat
        // about the message, not a note about its history.
        unverifiedLabel.font = UIFont.systemFont(ofSize: 11.0)
        unverifiedLabel.numberOfLines = 0
        unverifiedLabel.textColor = UIColor(red: 0.85, green: 0.47, blue: 0.10, alpha: 1.0)
        unverifiedLabel.isHidden = true
        addSubview(unverifiedLabel)

        textView.snp.makeConstraints {
            $0.top.equalTo(self).offset(Constants.TextViewVerticalOffset)
            $0.bottom.equalTo(self).offset(-Constants.TextViewVerticalOffset)
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset)
            $0.trailing.equalTo(self).offset(-Constants.TextViewHorizontalOffset)

            $0.width.greaterThanOrEqualTo(Constants.TextViewMinWidth)
            $0.width.lessThanOrEqualTo(Constants.TextViewMaxWidth)
            $0.height.greaterThanOrEqualTo(Constants.TextViewMinHeight)
        }
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}


// MARK: - KHANDAQ (#192) Telegram-style reaction picker

/// Horizontal floating reaction pill (Telegram-style): a rounded capsule of quick-emoji buttons +
/// a trailing "expand" button for arbitrary emoji. Replaces the old vertical action-sheet picker.
/// Uses only pre-iOS-13 UIKit (target-action, solid background) for deployment-target compatibility.
final class ChatReactionBar: UIView {
    static let quickReactions = ["❤️", "👍", "👎", "😂", "😮", "😢", "🔥"]

    var onPick: ((String) -> Void)?
    var onExpand: (() -> Void)?

    static let barHeight: CGFloat = 52
    static func barWidth() -> CGFloat {
        // leading(12) + N*44 + N*4 spacing + expand(34) + trailing(10)
        let n = CGFloat(quickReactions.count)
        return 12 + n * 44 + n * 4 + 34 + 10
    }

    init(currentEmoji: String?, dark: Bool) {
        super.init(frame: .zero)

        backgroundColor = dark ? UIColor(white: 0.16, alpha: 0.98) : UIColor(white: 0.97, alpha: 0.98)
        layer.cornerRadius = ChatReactionBar.barHeight / 2
        layer.masksToBounds = true

        let stack = UIStackView()
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 4
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.centerY.equalToSuperview()
            $0.leading.equalToSuperview().offset(12)
            $0.trailing.equalToSuperview().offset(-10)
        }

        for emoji in ChatReactionBar.quickReactions {
            let b = UIButton(type: .custom)
            b.setTitle(emoji, for: .normal)
            b.titleLabel?.font = UIFont.systemFont(ofSize: 30)
            b.snp.makeConstraints { $0.width.height.equalTo(44) }
            if emoji == currentEmoji {
                b.backgroundColor = (dark ? UIColor.white : UIColor.black).withAlphaComponent(0.14)
                b.layer.cornerRadius = 22
            }
            b.addTarget(self, action: #selector(emojiTapped(_:)), for: .touchUpInside)
            stack.addArrangedSubview(b)
        }

        // trailing "expand" — opens the full emoji picker for any emoji ("⌄" like Telegram)
        let expand = UIButton(type: .custom)
        expand.setTitle("⌄", for: .normal)
        expand.titleLabel?.font = UIFont.systemFont(ofSize: 22, weight: .semibold)
        expand.setTitleColor(dark ? UIColor.white.withAlphaComponent(0.8) : UIColor.black.withAlphaComponent(0.5), for: .normal)
        expand.backgroundColor = (dark ? UIColor.white : UIColor.black).withAlphaComponent(0.10)
        expand.layer.cornerRadius = 17
        expand.snp.makeConstraints { $0.width.height.equalTo(34) }
        expand.addTarget(self, action: #selector(expandTapped), for: .touchUpInside)
        stack.addArrangedSubview(expand)
    }

    @objc private func emojiTapped(_ sender: UIButton) {
        if let emoji = sender.title(for: .normal) {
            onPick?(emoji)
        }
    }

    @objc private func expandTapped() {
        onExpand?()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
}

/// KHANDAQ (#G5 reaction-row): one row of the Telegram-style vertical action menu shown beneath the
/// message bubble, alongside the floating reaction bar. Plain UIKit (target-action) for iOS-12 compat.
struct ChatContextMenuAction {
    let title: String
    let systemImageName: String
    let destructive: Bool
    let handler: () -> Void
    init(title: String, systemImageName: String, destructive: Bool = false, handler: @escaping () -> Void) {
        self.title = title; self.systemImageName = systemImageName; self.destructive = destructive; self.handler = handler
    }
}

/// Presents a `ChatReactionBar` as a floating overlay anchored above (or below) a message bubble,
/// with a tap-catcher backdrop. Also handles "expand" → arbitrary-emoji entry via a hidden
/// emoji-keyboard text field. One instance per chat controller.
final class ChatReactionPopup: NSObject, UITextFieldDelegate {
    private var backdrop: UIView?
    private var bar: ChatReactionBar?
    private var menuCard: UIView?
    private var actionHandlers: [() -> Void] = []
    private var emojiField: UITextField?
    private var pickHandler: ((String) -> Void)?
    private var dismissHandler: (() -> Void)?

    var isVisible: Bool { return backdrop != nil }

    /// Show the bar above `rect` (message-bubble frame in `host` coordinates).
    func present(in host: UIView, aboveRect rect: CGRect, currentEmoji: String?, dark: Bool,
                 onPick: @escaping (String) -> Void) {
        dismiss(animated: false)
        pickHandler = onPick

        let backdrop = UIView(frame: host.bounds)
        backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        backdrop.backgroundColor = UIColor.black.withAlphaComponent(0.28) // Telegram-style dim
        backdrop.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(backdropTapped)))
        host.addSubview(backdrop)
        self.backdrop = backdrop

        let bar = ChatReactionBar(currentEmoji: currentEmoji, dark: dark)
        bar.onPick = { [weak self] emoji in self?.finish(with: emoji) }
        bar.onExpand = { [weak self] in self?.showEmojiKeyboard() }
        bar.layer.shadowColor = UIColor.black.cgColor
        bar.layer.shadowOpacity = 0.22
        bar.layer.shadowRadius = 12
        bar.layer.shadowOffset = CGSize(width: 0, height: 4)
        bar.layer.masksToBounds = false
        backdrop.addSubview(bar)
        self.bar = bar

        let barW = min(ChatReactionBar.barWidth(), host.bounds.width - 24)
        let barH = ChatReactionBar.barHeight

        var x = rect.midX - barW / 2
        x = max(12, min(x, host.bounds.width - barW - 12))
        let topSafe = host.safeAreaInsets.top + 8
        var y = rect.minY - barH - 8
        if y < topSafe { y = rect.maxY + 8 }
        bar.frame = CGRect(x: x, y: y, width: barW, height: barH)

        bar.alpha = 0
        bar.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
        UIView.animate(withDuration: 0.28, delay: 0, usingSpringWithDamping: 0.7,
                       initialSpringVelocity: 0.5, options: [], animations: {
            bar.alpha = 1
            bar.transform = .identity
        }, completion: nil)
    }

    /// KHANDAQ (#G5 reaction-row): full Telegram-style context popup — the reaction bar ABOVE the
    /// bubble and a vertical action menu BELOW it, over one dimmed tap-to-dismiss backdrop. Reuses the
    /// tested bar/backdrop/expand path; the message stays visible under the light dim (no snapshot).
    func presentMenu(in host: UIView, aroundRect rect: CGRect, currentEmoji: String?, dark: Bool,
                     actions: [ChatContextMenuAction], bubbleSnapshot: UIView? = nil,
                     onPick: @escaping (String) -> Void,
                     onDismiss: (() -> Void)? = nil) {
        dismiss(animated: false)
        pickHandler = onPick
        dismissHandler = onDismiss

        let backdrop = UIView(frame: host.bounds)
        backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        backdrop.backgroundColor = UIColor.black.withAlphaComponent(0.28)
        backdrop.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(backdropTapped)))
        host.addSubview(backdrop)
        self.backdrop = backdrop

        // --- lifted bubble snapshot (Telegram-style): the message sits ABOVE the dim, un-dimmed, between
        // the reaction bar and the action card. Additive — if no snapshot is supplied the real (lightly
        // dimmed) bubble shows through as before. Added first so the bar/card render on top. ---
        if let snapshot = bubbleSnapshot {
            snapshot.frame = rect
            backdrop.addSubview(snapshot)
        }

        // --- reaction bar (above) ---
        let bar = ChatReactionBar(currentEmoji: currentEmoji, dark: dark)
        bar.onPick = { [weak self] emoji in self?.finish(with: emoji) }
        bar.onExpand = { [weak self] in self?.showEmojiKeyboard() }
        bar.layer.shadowColor = UIColor.black.cgColor
        bar.layer.shadowOpacity = 0.22
        bar.layer.shadowRadius = 12
        bar.layer.shadowOffset = CGSize(width: 0, height: 4)
        bar.layer.masksToBounds = false
        backdrop.addSubview(bar)
        self.bar = bar

        // --- action menu card (below) ---
        let card = buildMenuCard(actions: actions, dark: dark)
        backdrop.addSubview(card)
        self.menuCard = card

        // --- layout ---
        let hostW = host.bounds.width
        let topSafe = host.safeAreaInsets.top + 8
        let bottomSafe = host.bounds.height - host.safeAreaInsets.bottom - 8

        let barW = min(ChatReactionBar.barWidth(), hostW - 24)
        let barH = ChatReactionBar.barHeight
        var barX = rect.midX - barW / 2
        barX = max(12, min(barX, hostW - barW - 12))
        var barY = rect.minY - barH - 8
        if barY < topSafe { barY = topSafe }
        bar.frame = CGRect(x: barX, y: barY, width: barW, height: barH)

        let cardW: CGFloat = 250
        let cardH = card.frame.height
        var cardX = rect.minX
        // align the card to the bubble side, clamped on screen
        cardX = max(12, min(cardX, hostW - cardW - 12))
        // KHANDAQ (context-menu-overlap): for the newest/last bubble there is no room BELOW it for the
        // tall action card; the old `bottomSafe - cardH` clamp slid the card UP over the message and
        // covered it. Instead flip the card ABOVE the reaction bar so the last bubble stays fully visible.
        let cardY: CGFloat
        if bottomSafe - rect.maxY >= cardH + 8 {
            cardY = max(rect.maxY + 8, barY + barH + 8)   // room below: keep below the bubble (unchanged)
        }
        else {
            cardY = max(topSafe, barY - 8 - cardH)        // no room below: place above the reaction bar
        }
        card.frame = CGRect(x: cardX, y: cardY, width: cardW, height: cardH)

        for (v, t) in [(bar as UIView, CGAffineTransform(scaleX: 0.6, y: 0.6)),
                       (card as UIView, CGAffineTransform(translationX: 0, y: -12))] {
            v.alpha = 0
            v.transform = t
        }
        UIView.animate(withDuration: 0.28, delay: 0, usingSpringWithDamping: 0.78,
                       initialSpringVelocity: 0.5, options: [], animations: {
            bar.alpha = 1; bar.transform = .identity
            card.alpha = 1; card.transform = .identity
        }, completion: nil)
    }

    private func buildMenuCard(actions: [ChatContextMenuAction], dark: Bool) -> UIView {
        let cardW: CGFloat = 250
        let rowH: CGFloat = 48
        let container = UIView()
        container.backgroundColor = dark ? UIColor(white: 0.16, alpha: 0.98) : UIColor(white: 0.98, alpha: 0.98)
        container.layer.cornerRadius = 14
        container.layer.masksToBounds = true

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 0
        container.addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: container.topAnchor),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        actionHandlers = actions.map { $0.handler }
        let sepColor = (dark ? UIColor.white : UIColor.black).withAlphaComponent(0.08)
        for (i, action) in actions.enumerated() {
            let row = UIButton(type: .system)
            row.tag = i
            row.contentHorizontalAlignment = .fill
            row.addTarget(self, action: #selector(menuRowTapped(_:)), for: .touchUpInside)

            let tint = action.destructive ? UIColor(red: 1.0, green: 0.23, blue: 0.19, alpha: 1.0)
                                          : (dark ? UIColor.white : UIColor.black)
            let label = UILabel()
            label.text = action.title
            label.font = UIFont.systemFont(ofSize: 16)
            label.textColor = tint

            let icon = UIImageView()
            icon.contentMode = .scaleAspectFit
            if #available(iOS 13.0, *) {
                icon.image = UIImage(systemName: action.systemImageName)?.withRenderingMode(.alwaysTemplate)
            }
            icon.tintColor = tint

            row.addSubview(label); row.addSubview(icon)
            label.translatesAutoresizingMaskIntoConstraints = false
            icon.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                label.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 16),
                label.centerYAnchor.constraint(equalTo: row.centerYAnchor),
                icon.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -16),
                icon.centerYAnchor.constraint(equalTo: row.centerYAnchor),
                icon.widthAnchor.constraint(equalToConstant: 22),
                icon.heightAnchor.constraint(equalToConstant: 22),
            ])
            row.translatesAutoresizingMaskIntoConstraints = false
            row.heightAnchor.constraint(equalToConstant: rowH).isActive = true
            stack.addArrangedSubview(row)

            if i < actions.count - 1 {
                let sep = UIView(); sep.backgroundColor = sepColor
                container.addSubview(sep)
                sep.frame = CGRect(x: 0, y: rowH * CGFloat(i + 1) - 0.5, width: cardW, height: 0.5)
            }
        }
        container.frame = CGRect(x: 0, y: 0, width: cardW, height: rowH * CGFloat(actions.count))
        return container
    }

    @objc private func menuRowTapped(_ sender: UIButton) {
        let idx = sender.tag
        let handlers = actionHandlers
        dismiss(animated: true)
        if idx >= 0, idx < handlers.count { handlers[idx]() }
    }

    @objc private func backdropTapped() { dismiss(animated: true) }

    private func finish(with emoji: String) {
        let handler = pickHandler
        dismiss(animated: true)
        handler?(emoji)
    }

    private func showEmojiKeyboard() {
        guard let backdrop = backdrop else { return }
        // a visually-hidden but on-screen field so the keyboard reliably comes up; the emoji
        // keyboard is requested explicitly via a custom EmojiTextField (keyboard type Default,
        // but we prime it) — the user taps any emoji and it's applied.
        let field = EmojiOnlyTextField(frame: CGRect(x: 20, y: backdrop.bounds.midY, width: 2, height: 20))
        field.delegate = self
        field.autocorrectionType = .no
        field.autocapitalizationType = .none
        field.tintColor = .clear
        field.textColor = .clear
        field.backgroundColor = .clear
        backdrop.addSubview(field)
        emojiField = field
        DispatchQueue.main.async {
            field.becomeFirstResponder()
        }
    }

    // capture the first emoji the user types, apply it
    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange,
                   replacementString string: String) -> Bool {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        if let emoji = trimmed.first.map(String.init), !emoji.isEmpty {
            textField.resignFirstResponder()
            finish(with: emoji)
        }
        return false
    }

    func dismiss(animated: Bool) {
        emojiField?.resignFirstResponder()
        emojiField?.removeFromSuperview()
        emojiField = nil
        pickHandler = nil
        actionHandlers = []
        let onDismiss = dismissHandler
        dismissHandler = nil
        onDismiss?()
        let bd = backdrop
        let b = bar
        let card = menuCard
        backdrop = nil
        bar = nil
        menuCard = nil
        guard animated, let bar = b else { bd?.removeFromSuperview(); return }
        UIView.animate(withDuration: 0.18, animations: {
            bar.alpha = 0
            bar.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
            card?.alpha = 0
            card?.transform = CGAffineTransform(translationX: 0, y: -12)
        }, completion: { _ in bd?.removeFromSuperview() })
    }
}

/// A text field that opens directly on the emoji keyboard (Telegram-style "expand" for any emoji).
final class EmojiOnlyTextField: UITextField {
    override var textInputMode: UITextInputMode? {
        for mode in UITextInputMode.activeInputModes where mode.primaryLanguage == "emoji" {
            return mode
        }
        return super.textInputMode
    }
}
