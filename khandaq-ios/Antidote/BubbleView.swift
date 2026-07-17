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

            if reactionsLabel.isHidden {
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
            if !reactionsLabel.isHidden {
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

/// Presents a `ChatReactionBar` as a floating overlay anchored above (or below) a message bubble,
/// with a tap-catcher backdrop. Also handles "expand" → arbitrary-emoji entry via a hidden
/// emoji-keyboard text field. One instance per chat controller.
final class ChatReactionPopup: NSObject, UITextFieldDelegate {
    private var backdrop: UIView?
    private var bar: ChatReactionBar?
    private var emojiField: UITextField?
    private var pickHandler: ((String) -> Void)?

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
        let bd = backdrop
        let b = bar
        backdrop = nil
        bar = nil
        guard animated, let bar = b else { bd?.removeFromSuperview(); return }
        UIView.animate(withDuration: 0.18, animations: {
            bar.alpha = 0
            bar.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
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
