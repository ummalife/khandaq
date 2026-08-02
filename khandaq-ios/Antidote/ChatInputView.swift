// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let TopBorderHeight = 0.5
    static let PlusCircleSize: CGFloat = 32.0
    static let Offset: CGFloat = 5.0
    static let CameraHorizontalOffset: CGFloat = 10.0
    static let CameraBottomOffset: CGFloat = -10.0
    static let TextViewMinHeight: CGFloat = 35.0
    static let MIN_MYHEIGHT: CGFloat = 45
    static let MAX_MYHEIGHT: CGFloat = 90
    static let MARGIN_MYHEIGHT: CGFloat = 5
    static let MAX_TEXT_INPUT_CHARS = 1000
}

protocol ChatInputViewDelegate: class {
    func chatInputViewCameraButtonPressed(_ view: ChatInputView, cameraView: UIView)
    func chatInputViewSendButtonPressed(_ view: ChatInputView)
    func chatInputViewTextDidChange(_ view: ChatInputView)
    func chatInputViewVoiceRecordDidStart(_ view: ChatInputView)
    func chatInputViewVoiceRecordDidEnd(_ view: ChatInputView, cancelled: Bool)
    func chatInputViewVoiceButtonTapped(_ view: ChatInputView)
}

extension ChatInputViewDelegate {
    func chatInputViewVoiceRecordDidStart(_ view: ChatInputView) {}
    func chatInputViewVoiceRecordDidEnd(_ view: ChatInputView, cancelled: Bool) {}
    func chatInputViewVoiceButtonTapped(_ view: ChatInputView) {}
}

class ChatInputView: UIView {
    weak var delegate: ChatInputViewDelegate?

    var text: String {
        get {
            return textView.text
        }
        set {
            textView.text = newValue
            updateViews()
        }
    }

    var maxHeight: CGFloat {
        didSet {
            updateViews()
        }
    }

    var cameraButtonEnabled: Bool = true{
        didSet {
            updateViews()
        }
    }

    var voiceButtonEnabled: Bool = false {
        didSet {
            updateViews()
        }
    }

    // KHANDAQ (#113): hide the "+" attach button entirely for chats that can't attach (NGC group-peer
    // private messages are text-only). Default false → identical layout for normal chats.
    var attachmentButtonHidden: Bool = false {
        didSet {
            guard attachmentButtonHidden != oldValue, textViewLeadingConstraint != nil else {
                return
            }
            applyAttachmentButtonHidden()
        }
    }
    fileprivate var textViewLeadingConstraint: Constraint?

    fileprivate var topBorder: UIView!
    fileprivate var emojiButton: UIButton!
    fileprivate var cameraButton: UIButton!
    fileprivate var voiceButton: UIButton!
    fileprivate var textView: UITextView!
    fileprivate var sendButton: UIButton!
    fileprivate var recordingBar: UIView!
    fileprivate var recordingDot: UIView!
    fileprivate var recordingTimerLabel: UILabel!
    fileprivate var recordingCancelButton: UIButton!
    fileprivate var recordingSendButton: UIButton!
    fileprivate var theme: Theme!
    fileprivate var recordingTimer: Timer?
    fileprivate var recordingStartedAt: Date?
    fileprivate var isVoiceRecording = false
    fileprivate var recordingEndedByControl = false
    // KHANDAQ (#35): Telegram-style slide-to-cancel. While holding the mic, drag left past the
    // threshold to arm cancellation; releasing then discards instead of sending.
    fileprivate var slideHintLabel: UILabel!
    fileprivate var recordingTouchStartX: CGFloat = 0
    fileprivate var recordingWillCancel = false
    fileprivate static let slideCancelThreshold: CGFloat = 90.0
    // KHANDAQ (#G7): a release faster than this is an accidental tap, not a real note — discard it
    // (Telegram behaviour) and show the "hold to record" hint instead of sending a 0.x s clip.
    fileprivate var recordingStartTime: Date?
    fileprivate static let minRecordingDuration: TimeInterval = 0.7
    fileprivate var myHeight: Constraint!
    fileprivate var didconstraint = 0
    fileprivate var isEmojiActive = false
    fileprivate lazy var emojiKeyboard: EmojiKeyboardView = {
        let keyboard = EmojiKeyboardView(theme: theme)
        keyboard.onPick = { [weak self] emoji in
            guard let self = self else { return }
            self.textView.insertText(emoji)
            self.textViewDidChange(self.textView)
        }
        keyboard.onBackspace = { [weak self] in
            guard let self = self else { return }
            self.textView.deleteBackward()
            self.textViewDidChange(self.textView)
        }
        keyboard.onSwitchToKeyboard = { [weak self] in
            self?.showSystemKeyboard()
        }
        return keyboard
    }()

    init(theme: Theme) {
        self.maxHeight = 0.0
        self.theme = theme

        super.init(frame: CGRect.zero)

        backgroundColor = theme.colorForType(.ChatInputBackground)

        createViews(theme)
        installConstraints()
        updateViews()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    // KHANDAQ (leak): this view had no deinit — a mid-recording teardown left the 0.2s repeating
    // recordingTimer firing forever on the run loop. Invalidate it on dealloc (block already [weak self]).
    deinit {
        recordingTimer?.invalidate()
    }

    override func becomeFirstResponder() -> Bool {
        return textView.becomeFirstResponder()
    }

    override func resignFirstResponder() -> Bool {
        let result = textView.resignFirstResponder()
        if isEmojiActive {
            isEmojiActive = false
            textView.inputView = nil
            updateEmojiButtonIcon()
        }
        return result
    }
}

// MARK: Actions
extension ChatInputView {
    @objc func cameraButtonPressed() {
        // Anchor the attachment menu to the visible "+" (emojiButton), not the hidden paperclip.
        delegate?.chatInputViewCameraButtonPressed(self, cameraView: emojiButton)
    }

    @objc func emojiButtonPressed() {
        if isEmojiActive {
            showSystemKeyboard()
        }
        else {
            isEmojiActive = true
            textView.inputView = emojiKeyboard
            textView.reloadInputViews()
            _ = textView.becomeFirstResponder()
            updateEmojiButtonIcon()
        }
    }

    func showSystemKeyboard() {
        isEmojiActive = false
        textView.inputView = nil
        textView.reloadInputViews()
        _ = textView.becomeFirstResponder()
        updateEmojiButtonIcon()
    }

    func updateEmojiButtonIcon() {
        // KHANDAQ design (Figma): the left button is a fixed green "+" (opens attachments), so there is
        // no emoji/keyboard toggle icon to swap anymore.
    }

    @objc func sendButtonPressed() {
        delegate?.chatInputViewSendButtonPressed(self)
        updateTextviewHeight(textView)
    }

    @objc func voiceButtonTapped() {
        delegate?.chatInputViewVoiceButtonTapped(self)
    }

    @objc func voiceLongPress(_ gesture: UILongPressGestureRecognizer) {
        switch gesture.state {
            case .began:
                guard !isVoiceRecording else { return }
                isVoiceRecording = true
                recordingEndedByControl = false
                recordingWillCancel = false
                recordingStartTime = Date()
                recordingTouchStartX = gesture.location(in: self).x
                showRecordingBar(showControls: false)
                delegate?.chatInputViewVoiceRecordDidStart(self)
            case .changed:
                guard isVoiceRecording, !recordingEndedByControl else { return }
                // Drag left from the press origin to arm cancellation (Telegram slide-to-cancel).
                let dx = gesture.location(in: self).x - recordingTouchStartX
                let willCancel = dx < -ChatInputView.slideCancelThreshold
                if willCancel != recordingWillCancel {
                    recordingWillCancel = willCancel
                    updateSlideToCancelHint()
                }
            case .ended:
                guard isVoiceRecording, !recordingEndedByControl else { return }
                finishVoiceRecording(cancelled: recordingWillCancel)
            case .cancelled, .failed:
                guard isVoiceRecording, !recordingEndedByControl else { return }
                finishVoiceRecording(cancelled: true)
            default:
                break
        }
    }

    /// KHANDAQ (#35): reflect the armed/disarmed slide-to-cancel state — red "release to cancel" plus a
    /// dimmed timer once armed, the neutral "slide to cancel" hint otherwise.
    func updateSlideToCancelHint() {
        if recordingWillCancel {
            slideHintLabel.text = String(localized: "voice_recording_release_to_cancel")
            slideHintLabel.textColor = UIColor(red: 1.0, green: 0.23, blue: 0.19, alpha: 1.0)
            recordingTimerLabel.alpha = 0.4
        }
        else {
            slideHintLabel.text = String(localized: "voice_recording_slide_to_cancel")
            slideHintLabel.textColor = theme.colorForType(.ChatInformationText)
            recordingTimerLabel.alpha = 1.0
        }
    }

    @objc func recordingCancelTapped() {
        guard isVoiceRecording else { return }
        recordingEndedByControl = true
        finishVoiceRecording(cancelled: true)
    }

    @objc func recordingSendTapped() {
        guard isVoiceRecording else { return }
        recordingEndedByControl = true
        finishVoiceRecording(cancelled: false)
    }

    /// KHANDAQ design (Figma): start a voice recording from the "+" attachment menu (tap to start;
    /// the recording bar's send/cancel controls finish it — no hold required).
    func beginVoiceRecordingFromMenu() {
        guard !isVoiceRecording else { return }
        isVoiceRecording = true
        recordingEndedByControl = false
        recordingWillCancel = false
        showRecordingBar(showControls: true)
        delegate?.chatInputViewVoiceRecordDidStart(self)
    }
}

extension ChatInputView: UITextViewDelegate {
    func textViewDidChange(_ textView: UITextView) {
        updateViews()
        updateTextviewHeight(textView)
        delegate?.chatInputViewTextDidChange(self)
    }

    override func didMoveToWindow() {
        updateTextviewHeight(textView)
    }

    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
        let currentText = textView.text ?? ""
        let nsCurrent = currentText as NSString

        guard NSMaxRange(range) <= nsCurrent.length else {
            return true
        }

        let updatedText = nsCurrent.replacingCharacters(in: range, with: text)
        return updatedText.count <= Constants.MAX_TEXT_INPUT_CHARS
    }
}

private extension ChatInputView {

    func createViews(_ theme: Theme) {
        topBorder = UIView()
        topBorder.backgroundColor = theme.colorForType(.SeparatorsAndBorders)
        // KHANDAQ (#155): hide the hard separator above the input bar — it sat right at the table's
        // bottom edge and clipped the last message (e.g. a call-log row with no bottom padding). The
        // input field's own border + the background contrast give enough separation (Telegram-style).
        topBorder.isHidden = true
        addSubview(topBorder)

        // KHANDAQ (#15): input bar laid out like the Android/Telegram build:
        // [emoji] [text…] [attach 📎] [mic 🎤 ⇄ send ➤]. Emoji just focuses the field (the system
        // keyboard provides the emoji panel); attach opens the camera/library/file menu.
        // KHANDAQ design (Figma): the left button is a grey circle with a "+" glyph that opens the
        // attachment menu (was a smiling-face that just focused the field).
        emojiButton = UIButton(type: .system)
        if #available(iOS 13.0, *) {
            let plusConfig = UIImage.SymbolConfiguration(pointSize: 18.0, weight: .semibold)
            emojiButton.setImage(UIImage(systemName: "plus", withConfiguration: plusConfig), for: .normal)
        } else {
            emojiButton.setTitle("+", for: .normal)
        }
        emojiButton.tintColor = theme.colorForType(.NormalText)
        emojiButton.backgroundColor = theme.colorForType(.TabSelection)
        emojiButton.layer.cornerRadius = Constants.PlusCircleSize / 2.0
        emojiButton.layer.masksToBounds = true
        emojiButton.addTarget(self, action: #selector(ChatInputView.cameraButtonPressed), for: .touchUpInside)
        emojiButton.setContentCompressionResistancePriority(UILayoutPriority.required, for: .horizontal)
        addSubview(emojiButton)

        cameraButton = UIButton()
        if #available(iOS 13.0, *) {
            cameraButton.setImage(UIImage(systemName: "paperclip"), for: .normal)
        } else {
            cameraButton.setImage(UIImage.templateNamed("chat-camera"), for: UIControlState())
        }
        cameraButton.tintColor = theme.colorForType(.LinkText)
        cameraButton.addTarget(self, action: #selector(ChatInputView.cameraButtonPressed), for: .touchUpInside)
        cameraButton.setContentCompressionResistancePriority(UILayoutPriority.required, for: .horizontal)
        // KHANDAQ design (Figma): attachments are reached via the left "+", so the separate paperclip
        // on the right is hidden.
        cameraButton.isHidden = true
        addSubview(cameraButton)

        voiceButton = UIButton()
        if #available(iOS 13.0, *) {
            voiceButton.setImage(UIImage(systemName: "mic.fill"), for: .normal)
        } else {
            voiceButton.setTitle("🎤", for: .normal)
        }
        voiceButton.tintColor = theme.colorForType(.LinkText)
        voiceButton.addTarget(self, action: #selector(ChatInputView.voiceButtonTapped), for: .touchUpInside)
        voiceButton.setContentCompressionResistancePriority(UILayoutPriority.required, for: .horizontal)
        let longPress = UILongPressGestureRecognizer(target: self, action: #selector(ChatInputView.voiceLongPress(_:)))
        longPress.minimumPressDuration = 0.25
        voiceButton.addGestureRecognizer(longPress)
        addSubview(voiceButton)

        textView = UITextView()
        textView.delegate = self
        textView.font = UIFont.systemFont(ofSize: 16.0)
        textView.backgroundColor = theme.colorForType(.NormalBackground)
        // KHANDAQ: bind the input text + caret colors to the theme. Without an explicit textColor the
        // UITextView used the default system .label color, which on the dark theme's dark input field
        // rendered (near-)invisible while typing. NormalText flips correctly per light/dark theme.
        textView.textColor = theme.colorForType(.NormalText)
        textView.tintColor = theme.colorForType(.LinkText)
        // KHANDAQ design (Figma): rounded pill input field.
        textView.layer.cornerRadius = 18.0
        textView.layer.borderWidth = 0.5
        textView.layer.borderColor = theme.colorForType(.SeparatorsAndBorders).cgColor
        textView.layer.masksToBounds = true
        textView.setContentHuggingPriority(UILayoutPriority(rawValue: 0.0), for: .horizontal)
        textView.autocapitalizationType = .sentences

        addSubview(textView)

        sendButton = UIButton(type: .system)
        if #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 30.0, weight: .regular)
            // KHANDAQ design (Figma): Telegram-style paper-plane on a green circle.
            sendButton.setImage(UIImage(systemName: "paperplane.circle.fill", withConfiguration: config), for: .normal)
        } else {
            sendButton.setTitle(String(localized: "chat_send_button"), for: UIControlState())
            sendButton.titleLabel?.font = UIFont.khandaqFontWithSize(16.0, weight: .bold)
        }
        sendButton.tintColor = theme.colorForType(.LinkText)
        sendButton.accessibilityLabel = String(localized: "chat_send_button")
        sendButton.addTarget(self, action: #selector(ChatInputView.sendButtonPressed), for: .touchUpInside)
        sendButton.setContentCompressionResistancePriority(UILayoutPriority.required, for: .horizontal)
        addSubview(sendButton)

        recordingBar = UIView()
        recordingBar.isHidden = true
        // KHANDAQ (#116): opaque background so the chat wallpaper doesn't show through the recording UI.
        recordingBar.backgroundColor = theme.colorForType(.ChatInputBackground)
        addSubview(recordingBar)

        recordingDot = UIView()
        recordingDot.backgroundColor = UIColor(red: 1.0, green: 0.23, blue: 0.19, alpha: 1.0)
        recordingDot.layer.cornerRadius = 4.0
        recordingBar.addSubview(recordingDot)

        recordingTimerLabel = UILabel()
        recordingTimerLabel.font = UIFont.monospacedDigitSystemFont(ofSize: 16.0, weight: .regular)
        recordingTimerLabel.textColor = theme.colorForType(.NormalText)
        recordingTimerLabel.text = "0:00"
        recordingBar.addSubview(recordingTimerLabel)

        // KHANDAQ (#35): slide-to-cancel hint, centered in the recording bar during a hold.
        slideHintLabel = UILabel()
        slideHintLabel.font = UIFont.systemFont(ofSize: 15.0)
        slideHintLabel.textColor = theme.colorForType(.ChatInformationText)
        slideHintLabel.textAlignment = .center
        slideHintLabel.text = String(localized: "voice_recording_slide_to_cancel")
        recordingBar.addSubview(slideHintLabel)

        recordingCancelButton = UIButton(type: .system)
        recordingCancelButton.setTitle(String(localized: "voice_recording_cancel"), for: .normal)
        recordingCancelButton.setTitleColor(theme.colorForType(.LinkText), for: .normal)
        recordingCancelButton.titleLabel?.font = UIFont.systemFont(ofSize: 16.0)
        recordingCancelButton.addTarget(self, action: #selector(ChatInputView.recordingCancelTapped), for: .touchUpInside)
        recordingBar.addSubview(recordingCancelButton)

        recordingSendButton = UIButton(type: .system)
        recordingSendButton.backgroundColor = theme.colorForType(.LinkText)
        recordingSendButton.layer.cornerRadius = 20.0
        recordingSendButton.clipsToBounds = true
        recordingSendButton.tintColor = .white
        recordingSendButton.accessibilityLabel = String(localized: "voice_recording_send")
        if #available(iOS 13.0, *) {
            recordingSendButton.setImage(UIImage(systemName: "arrow.up"), for: .normal)
        } else {
            recordingSendButton.setTitle("↑", for: .normal)
        }
        recordingSendButton.addTarget(self, action: #selector(ChatInputView.recordingSendTapped), for: .touchUpInside)
        recordingBar.addSubview(recordingSendButton)
    }

    func installConstraints() {
        topBorder.snp.makeConstraints {
            $0.top.leading.trailing.equalTo(self)
            $0.height.equalTo(Constants.TopBorderHeight)
        }

        // [emoji] [text…] [attach] [mic ⇄ send]
        emojiButton.snp.makeConstraints {
            $0.leading.equalTo(self).offset(Constants.CameraHorizontalOffset)
            $0.bottom.equalTo(self).offset(Constants.CameraBottomOffset)
            $0.size.equalTo(Constants.PlusCircleSize)
        }

        voiceButton.snp.makeConstraints {
            $0.trailing.equalTo(self).offset(-Constants.CameraHorizontalOffset)
            $0.bottom.equalTo(self).offset(Constants.CameraBottomOffset)
            $0.width.height.equalTo(34.0)
        }

        // Send shares the mic's slot; only one is visible at a time (toggled by text emptiness).
        sendButton.snp.makeConstraints {
            $0.center.equalTo(voiceButton)
            $0.width.height.equalTo(voiceButton)
        }

        cameraButton.snp.makeConstraints {
            $0.trailing.equalTo(voiceButton.snp.leading).offset(-Constants.Offset)
            $0.bottom.equalTo(self).offset(Constants.CameraBottomOffset)
        }

        textView.snp.makeConstraints {
            textViewLeadingConstraint = $0.leading.equalTo(emojiButton.snp.trailing).offset(Constants.Offset).constraint
            // KHANDAQ design: field runs to the mic (the paperclip is hidden; "+" handles attachments).
            $0.trailing.equalTo(voiceButton.snp.leading).offset(-Constants.Offset)
            $0.top.equalTo(self).offset(Constants.Offset)
            $0.bottom.equalTo(self).offset(-Constants.Offset)
            $0.height.greaterThanOrEqualTo(Constants.TextViewMinHeight)
        }

        recordingBar.snp.makeConstraints {
            $0.leading.trailing.equalTo(self)
            $0.top.equalTo(self).offset(Constants.Offset)
            $0.bottom.equalTo(self).offset(-Constants.Offset)
            $0.height.greaterThanOrEqualTo(Constants.TextViewMinHeight)
        }

        recordingDot.snp.makeConstraints {
            $0.leading.equalTo(recordingBar).offset(Constants.CameraHorizontalOffset)
            $0.centerY.equalTo(recordingBar)
            $0.width.height.equalTo(8.0)
        }

        recordingTimerLabel.snp.makeConstraints {
            $0.leading.equalTo(recordingDot.snp.trailing).offset(8.0)
            $0.centerY.equalTo(recordingBar)
        }

        recordingSendButton.snp.makeConstraints {
            $0.trailing.equalTo(recordingBar).offset(-Constants.Offset)
            $0.centerY.equalTo(recordingBar)
            $0.width.height.equalTo(40.0)
        }

        recordingCancelButton.snp.makeConstraints {
            $0.trailing.equalTo(recordingSendButton.snp.leading).offset(-12.0)
            $0.centerY.equalTo(recordingBar)
        }

        slideHintLabel.snp.makeConstraints {
            $0.centerY.equalTo(recordingBar)
            $0.trailing.equalTo(recordingBar).offset(-16.0)
            $0.leading.greaterThanOrEqualTo(recordingTimerLabel.snp.trailing).offset(8.0)
        }
    }

    func updateTextviewHeight(_ t : UITextView)
    {
        if (self.didconstraint == 1)
        {
            self.myHeight.uninstall()
            self.didconstraint = 0
        }

        let text_needs_size  = t.sizeThatFits(
            CGSize(width: t.frame.size.width,
                   height: CGFloat.greatestFiniteMagnitude))
        var new_height = text_needs_size.height + Constants.MARGIN_MYHEIGHT
        if (text_needs_size.height > Constants.MAX_MYHEIGHT)
        {
            new_height = Constants.MAX_MYHEIGHT
        }
        else if (text_needs_size.height < Constants.MIN_MYHEIGHT) {
            new_height = Constants.MIN_MYHEIGHT
        }

        if (self.didconstraint == 0) {
            self.didconstraint = 1
            self.snp.makeConstraints {
                self.myHeight = $0.height.equalTo(new_height).constraint
            }
        }
    }

    func applyAttachmentButtonHidden() {
        emojiButton.isHidden = attachmentButtonHidden
        textViewLeadingConstraint?.deactivate()
        textView.snp.makeConstraints {
            if attachmentButtonHidden {
                textViewLeadingConstraint = $0.leading.equalTo(self).offset(Constants.CameraHorizontalOffset).constraint
            } else {
                textViewLeadingConstraint = $0.leading.equalTo(emojiButton.snp.trailing).offset(Constants.Offset).constraint
            }
        }
    }

    func updateViews() {
        textView.isScrollEnabled = true
        textView.autocapitalizationType = .sentences

        // While recording, the recording bar owns the layout — don't let a stray updateViews() re-show
        // the input buttons over it (that caused emoji/attach to overlap the timer).
        guard !isVoiceRecording else {
            return
        }

        // Always visible outside the recording state (showRecordingBar hides them, this restores them).
        textView.isHidden = false
        emojiButton.isHidden = attachmentButtonHidden

        let hasText = !textView.text.isEmpty
        cameraButton.isHidden = !cameraButtonEnabled
        cameraButton.isEnabled = cameraButtonEnabled
        // Typing turns the mic into the send button (Telegram/Android behaviour); empty text shows
        // the mic (when voice is available for this chat).
        sendButton.isHidden = !hasText
        sendButton.isEnabled = hasText
        voiceButton.isHidden = hasText || !voiceButtonEnabled
        voiceButton.isEnabled = voiceButtonEnabled && !hasText
    }

    func showRecordingBar(showControls: Bool = false) {
        emojiButton.isHidden = true
        cameraButton.isHidden = true
        voiceButton.isHidden = true
        textView.isHidden = true
        sendButton.isHidden = true
        recordingBar.isHidden = false
        // KHANDAQ (#35/#116): hold-to-record uses slide-to-cancel (the finger holds the mic, so the tap
        // buttons are unreachable); tap-to-record from the "+" menu shows explicit cancel/send buttons
        // instead (you can't slide a tap). The two control sets are mutually exclusive → never overlap.
        recordingCancelButton.isHidden = !showControls
        recordingSendButton.isHidden = !showControls
        slideHintLabel.isHidden = showControls
        recordingTimerLabel.alpha = 1.0
        updateSlideToCancelHint()
        recordingStartedAt = Date()
        recordingTimerLabel.text = "0:00"
        recordingTimer?.invalidate()
        recordingTimer = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { [weak self] _ in
            self?.updateRecordingTimerLabel()
        }
    }

    func hideRecordingBar() {
        recordingTimer?.invalidate()
        recordingTimer = nil
        recordingStartedAt = nil
        recordingBar.isHidden = true
        // KHANDAQ (#35): reset slide-to-cancel visuals for the next recording.
        recordingWillCancel = false
        recordingTimerLabel.alpha = 1.0
        updateViews()
    }

    func finishVoiceRecording(cancelled: Bool) {
        guard isVoiceRecording else { return }
        isVoiceRecording = false
        hideRecordingBar()

        // KHANDAQ (#G7): a hold released quicker than minRecordingDuration is an accidental tap —
        // discard instead of firing a 0.x s note, and nudge with the "hold to record" hint. Only
        // applies to the hold gesture (recordingStartTime set); the menu path (nil) is unaffected.
        var effectiveCancel = cancelled
        if !cancelled, let start = recordingStartTime,
           Date().timeIntervalSince(start) < ChatInputView.minRecordingDuration {
            effectiveCancel = true
            delegate?.chatInputViewVoiceButtonTapped(self)
        }
        recordingStartTime = nil

        delegate?.chatInputViewVoiceRecordDidEnd(self, cancelled: effectiveCancel)
    }

    func updateRecordingTimerLabel() {
        guard let startedAt = recordingStartedAt else { return }
        let elapsed = max(0.0, Date().timeIntervalSince(startedAt))
        let total = Int(elapsed)
        recordingTimerLabel.text = String(format: "%d:%02d", total / 60, total % 60)
    }
}

/// KHANDAQ (#15): emoji panel used as the text field's inputView. iOS has no public API to switch the
/// system keyboard to its emoji plane, so the 😀 button toggles this instead. Kept in this file so it
/// doesn't need a separate pbxproj entry.
final class EmojiKeyboardView: UIView {
    var onPick: ((String) -> Void)?
    var onBackspace: (() -> Void)?
    var onSwitchToKeyboard: (() -> Void)?

    private let collectionView: UICollectionView
    private let bottomBar = UIView()
    private let abcButton = UIButton(type: .system)
    private let backspaceButton = UIButton(type: .system)

    fileprivate static let emojis: [String] = {
        let groups = [
            "😀😃😄😁😆😅😂🤣😊😇🙂🙃😉😌😍🥰😘😗😙😚😋😛😝😜🤪🤨🧐🤓😎🥳🤩",
            "😏😒😞😔😟😕🙁☹️😣😖😫😩🥺😢😭😤😠😡🤬🤯😳🥵🥶😱😨😰😥😓🤗🤔🤭🤫",
            "😶😐😑😬🙄😯😦😧😮😲🥱😴🤤😪😵🤐🥴🤢🤮🤧😷🤒🤕🤑🤠😈👿👹👺🤡💩👻💀",
            "👍👎👌🤌🤏✌️🤞🤟🤘🤙👈👉👆👇☝️✋🤚🖐️🖖👋🤝🙏✊👊🤛🤜👏🙌👐🤲💪",
            "❤️🧡💛💚💙💜🤎🖤🤍💔❣️💕💞💓💗💖💘💝☮️✝️☪️🕉️☸️✡️🔯🕎",
            "🔥✨🎉🎊🎈🎁🏆🥇🥈🥉⚽️🏀🏈⚾️🎾🏐🎱🎮🎯🎲🎸🎹🎺🎻🥁🎬🎤🎧",
            "🌹🌷🌸🌼🌻🌺🍀🌿🍃🌱🌳🌲🌴🌵🍎🍊🍋🍌🍉🍇🍓🍒🍑🥭🍍🥥🌍🌎🌟",
            "☕️🍵🍺🍻🥂🍷🍕🍔🍟🌭🍿🍩🍪🎂🍰🧁🍫🍬🍭🚗✈️🚀⛵️🏠⭐️💫⚡️☀️🌈",
        ]
        return groups.flatMap { $0.map(String.init) }
    }()

    init(theme: Theme) {
        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection = .vertical
        layout.minimumInteritemSpacing = 2
        layout.minimumLineSpacing = 6
        layout.itemSize = CGSize(width: 38, height: 38)
        layout.sectionInset = UIEdgeInsets(top: 8, left: 8, bottom: 8, right: 8)
        collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)

        super.init(frame: CGRect(x: 0, y: 0, width: UIScreen.main.bounds.width, height: 260))

        autoresizingMask = [.flexibleWidth, .flexibleHeight]
        backgroundColor = theme.colorForType(.ChatInputBackground)

        collectionView.backgroundColor = .clear
        collectionView.alwaysBounceVertical = true
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(EmojiCell.self, forCellWithReuseIdentifier: EmojiCell.reuseId)
        addSubview(collectionView)

        bottomBar.backgroundColor = theme.colorForType(.NormalBackground)
        addSubview(bottomBar)

        abcButton.setTitle("ABC", for: .normal)
        abcButton.titleLabel?.font = UIFont.systemFont(ofSize: 15, weight: .semibold)
        abcButton.tintColor = theme.colorForType(.LinkText)
        abcButton.addTarget(self, action: #selector(abcTapped), for: .touchUpInside)
        bottomBar.addSubview(abcButton)

        if #available(iOS 13.0, *) {
            backspaceButton.setImage(UIImage(systemName: "delete.left"), for: .normal)
        } else {
            backspaceButton.setTitle("⌫", for: .normal)
        }
        backspaceButton.tintColor = theme.colorForType(.LinkText)
        backspaceButton.addTarget(self, action: #selector(backspaceTapped), for: .touchUpInside)
        bottomBar.addSubview(backspaceButton)

        bottomBar.snp.makeConstraints {
            $0.leading.trailing.bottom.equalToSuperview()
            $0.height.equalTo(44)
        }
        collectionView.snp.makeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(bottomBar.snp.top)
        }
        abcButton.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(16)
            $0.centerY.equalToSuperview()
        }
        backspaceButton.snp.makeConstraints {
            $0.trailing.equalToSuperview().offset(-16)
            $0.centerY.equalToSuperview()
        }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    @objc private func abcTapped() {
        onSwitchToKeyboard?()
    }

    @objc private func backspaceTapped() {
        onBackspace?()
    }
}

extension EmojiKeyboardView: UICollectionViewDataSource, UICollectionViewDelegate {
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return EmojiKeyboardView.emojis.count
    }

    func collectionView(_ collectionView: UICollectionView, cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: EmojiCell.reuseId, for: indexPath) as! EmojiCell
        cell.label.text = EmojiKeyboardView.emojis[indexPath.item]
        return cell
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        onPick?(EmojiKeyboardView.emojis[indexPath.item])
    }
}

private final class EmojiCell: UICollectionViewCell {
    static let reuseId = "EmojiCell"
    let label = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        label.font = UIFont.systemFont(ofSize: 30)
        label.textAlignment = .center
        contentView.addSubview(label)
        label.snp.makeConstraints { $0.edges.equalToSuperview() }
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}
