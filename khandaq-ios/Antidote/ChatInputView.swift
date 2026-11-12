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
            if isAirStyle {
                updateTextviewHeight(textView)
            }
            onTextChanged?(newValue)
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
            // The Air composer lays its text out again itself (layoutAirText), without that constraint.
            guard attachmentButtonHidden != oldValue, isAirStyle || textViewLeadingConstraint != nil else {
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
    // KHANDAQ (#G7-lock): Telegram-style slide-UP-to-lock. While holding the mic, drag up past the
    // threshold to lock hands-free — the finger can lift and recording continues; the bar's send/cancel
    // buttons then finish it. Coexists with slide-LEFT-to-cancel (#35): the dominant axis wins.
    fileprivate var recordingTouchStartY: CGFloat = 0
    fileprivate var recordingLocked = false
    fileprivate static let slideLockThreshold: CGFloat = 80.0
    fileprivate var myHeight: Constraint!
    fileprivate var didconstraint = 0
    fileprivate var isEmojiActive = false

    // KHANDAQ (redesign stage 2): the composer of design/system/index.html for the conversation with one
    // person: one framed field with the paperclip, the text that grows to five lines and the send button
    // of the same height, then the line about what happens to the message. Opt-in through useAirStyle();
    // the group screens keep the old bar.
    private(set) var isAirStyle = false
    /// The line under the field; nil until useAirStyle().
    private(set) var hintView: AirComposeHintView?
    /// Called after every change of the text, typed or set.
    var onTextChanged: ((String) -> Void)?
    /// True while the text is longer than a message can be: the send button stays disabled.
    var sendBlocked = false {
        didSet {
            if sendBlocked != oldValue {
                updateViews()
            }
        }
    }
    /// False while nothing may be written here (an observer in the private messages of a group): the
    /// field takes neither text nor focus and goes quiet, and nothing can be sent or attached. The line
    /// under it says why. The composer of the redesign only.
    var fieldEnabled = true {
        didSet {
            if fieldEnabled != oldValue {
                applyFieldEnabled()
            }
        }
    }
    fileprivate var airBox: UIView?
    fileprivate var airAttachButton: AirIconButton?
    /// The hold on the microphone that records a voice message.
    fileprivate weak var voiceHold: UILongPressGestureRecognizer?
    fileprivate let voiceHoldWithLargeContent = AirHoldWithLargeContent()
    fileprivate var airPlaceholder: UILabel?
    fileprivate var airTextHeight: Constraint?
    fileprivate var airMeasuredWidth: CGFloat = 0
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

    override func layoutSubviews() {
        super.layoutSubviews()
        // The field's height depends on its width, known only after layout.
        if isAirStyle, abs(textView.bounds.width - airMeasuredWidth) > 0.5 {
            airMeasuredWidth = textView.bounds.width
            updateTextviewHeight(textView)
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if isAirStyle {
            applyAirFocus(textView.isFirstResponder)
            if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
                updateAirGlyphs()
                updateTextviewHeight(textView)
            }
        }
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
                recordingLocked = false
                recordingStartTime = Date()
                recordingTouchStartX = gesture.location(in: self).x
                recordingTouchStartY = gesture.location(in: self).y
                showRecordingBar(showControls: false)
                delegate?.chatInputViewVoiceRecordDidStart(self)
            case .changed:
                guard isVoiceRecording, !recordingEndedByControl, !recordingLocked else { return }
                let dx = gesture.location(in: self).x - recordingTouchStartX
                let dy = gesture.location(in: self).y - recordingTouchStartY
                // KHANDAQ (#G7-lock): dragging UP past the threshold (vertical dominant) locks hands-free.
                if dy < -ChatInputView.slideLockThreshold, abs(dy) > abs(dx) {
                    lockRecording()
                    return
                }
                // Drag left from the press origin to arm cancellation (Telegram slide-to-cancel).
                let willCancel = dx < -ChatInputView.slideCancelThreshold
                if willCancel != recordingWillCancel {
                    recordingWillCancel = willCancel
                    updateSlideToCancelHint()
                }
            case .ended:
                // KHANDAQ (#G7-lock): once locked, lifting the finger must NOT end the recording — the
                // bar's send/cancel buttons finish it (hands-free).
                guard isVoiceRecording, !recordingEndedByControl, !recordingLocked else { return }
                finishVoiceRecording(cancelled: recordingWillCancel)
            case .cancelled, .failed:
                guard isVoiceRecording, !recordingEndedByControl, !recordingLocked else { return }
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
            // KHANDAQ (#G7-lock): advertise both gestures — slide left to cancel, up to lock hands-free.
            slideHintLabel.text = String(localized: "voice_recording_slide_hint")
            slideHintLabel.textColor = theme.colorForType(.ChatInformationText)
            recordingTimerLabel.alpha = 1.0
        }
    }

    /// KHANDAQ (#G7-lock): switch the in-progress hold recording to hands-free — reveal the send/cancel
    /// buttons and drop the slide hint WITHOUT restarting the timer/recorder (unlike showRecordingBar).
    func lockRecording() {
        guard isVoiceRecording, !recordingLocked else { return }
        recordingLocked = true
        recordingWillCancel = false
        slideHintLabel.isHidden = true
        recordingCancelButton.isHidden = false
        recordingSendButton.isHidden = false
        recordingTimerLabel.alpha = 1.0
        // Haptic confirm the lock engaged (matches Telegram).
        if #available(iOS 10.0, *) {
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
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

    /// KHANDAQ (#G7-lock, review): abort an in-progress recording when the chat is torn down. Slide-up
    /// lock lets a recording survive a finger-lift, so leaving the chat (Back, push profile, etc.) would
    /// otherwise orphan the AVAudioSession + temp file with no way to stop it. Controllers call this
    /// from viewWillDisappear. Safe no-op when nothing is recording.
    func abortActiveRecordingIfNeeded() {
        guard isVoiceRecording else { return }
        recordingEndedByControl = true
        finishVoiceRecording(cancelled: true)
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
        onTextChanged?(textView.text)
    }

    func textViewDidBeginEditing(_ textView: UITextView) {
        applyAirFocus(true)
    }

    func textViewDidEndEditing(_ textView: UITextView) {
        applyAirFocus(false)
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

        let nsUpdated = nsCurrent.replacingCharacters(in: range, with: text) as NSString
        if nsUpdated.length <= Constants.MAX_TEXT_INPUT_CHARS {
            return true
        }

        // KHANDAQ (paste fix): an over-limit paste used to be dropped ENTIRELY (return false), which reads
        // as "paste doesn't work" for long clipboard content. Instead clamp — keep as much as fits up to the
        // limit, place the caret at the end, and drive the normal change handler (height + send-button).
        // Length math is UTF-16 (NSString) to match the incoming NSRange.
        let clamped = nsUpdated.substring(to: Constants.MAX_TEXT_INPUT_CHARS)
        textView.text = clamped
        textView.selectedRange = NSRange(location: (clamped as NSString).length, length: 0)
        textViewDidChange(textView)
        return false
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
        voiceHold = longPress
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
        slideHintLabel.text = String(localized: "voice_recording_slide_hint")
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
        if isAirStyle {
            updateAirTextHeight(t)
            return
        }
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
        if isAirStyle {
            airAttachButton?.isHidden = attachmentButtonHidden
            layoutAirText()
            return
        }
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
        if isAirStyle {
            emojiButton.isHidden = true
            airAttachButton?.isHidden = attachmentButtonHidden || !fieldEnabled
            airPlaceholder?.isHidden = !textView.text.isEmpty
        }
        else {
            emojiButton.isHidden = attachmentButtonHidden
        }

        let hasText = !textView.text.isEmpty
        // The composer of the redesign has its own paperclip in the field; the old one stays hidden.
        cameraButton.isHidden = isAirStyle || !cameraButtonEnabled
        cameraButton.isEnabled = cameraButtonEnabled
        // Typing turns the mic into the send button (Telegram/Android behaviour); empty text shows
        // the mic (when voice is available for this chat).
        sendButton.isHidden = !hasText
        sendButton.isEnabled = hasText && !sendBlocked && fieldEnabled
        if isAirStyle {
            sendButton.alpha = sendButton.isEnabled ? 1 : 0.4
            if hasText {
                sendButton.accessibilityHint = sendBlocked ? String(localized: "air_compose_send_blocked") : nil
            }
        }
        voiceButton.isHidden = hasText || !voiceButtonEnabled || !fieldEnabled
        voiceButton.isEnabled = voiceButtonEnabled && !hasText && fieldEnabled
    }

    func showRecordingBar(showControls: Bool = false) {
        airAttachButton?.isHidden = true
        airPlaceholder?.isHidden = true
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
        recordingLocked = false
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

// MARK: KHANDAQ (redesign stage 2) composer

extension ChatInputView {
    /// Switches the bar to the composer of the redesign. Call once, right after init.
    func useAirStyle() {
        guard !isAirStyle else {
            return
        }
        isAirStyle = true

        backgroundColor = KQColor.surface
        topBorder.backgroundColor = KQColor.line
        topBorder.isHidden = false

        let box = UIView()
        box.backgroundColor = KQColor.surface
        box.layer.cornerRadius = KQRadius.field
        box.layer.borderWidth = KQStroke.hairline
        box.isUserInteractionEnabled = false
        insertSubview(box, at: 0)
        airBox = box

        let attach = AirIconButton(icon: "paperclip", label: String(localized: "air_compose_attach"))
        attach.accessibilityIdentifier = "air.chat.attach"
        attach.addTarget(self, action: #selector(ChatInputView.airAttachPressed), for: .touchUpInside)
        addSubview(attach)
        airAttachButton = attach

        emojiButton.isHidden = true
        cameraButton.isHidden = true

        textView.font = KQFont.font(.body)
        textView.adjustsFontForContentSizeCategory = true
        textView.textColor = KQColor.ink
        textView.tintColor = KQColor.ink
        textView.backgroundColor = .clear
        textView.layer.cornerRadius = 0
        textView.layer.borderWidth = 0
        textView.textContainerInset = UIEdgeInsets(top: KQSpace.x10, left: KQSpace.x4, bottom: KQSpace.x10, right: KQSpace.x4)
        textView.textContainer.lineFragmentPadding = 0
        textView.accessibilityLabel = String(localized: "air_compose_placeholder")
        textView.accessibilityIdentifier = "air.chat.field"

        let placeholder = UILabel()
        placeholder.font = KQFont.font(.body)
        placeholder.adjustsFontForContentSizeCategory = true
        placeholder.textColor = KQColor.ink3
        placeholder.text = String(localized: "air_compose_placeholder")
        placeholder.isAccessibilityElement = false
        placeholder.isUserInteractionEnabled = false
        addSubview(placeholder)
        airPlaceholder = placeholder

        sendButton.setTitle(nil, for: .normal)
        sendButton.setImage(AirIcon.image("arrow-up"), for: .normal)
        sendButton.tintColor = KQColor.onInk
        sendButton.backgroundColor = KQColor.ink
        sendButton.layer.cornerRadius = KQRadius.control
        sendButton.clipsToBounds = true
        sendButton.accessibilityLabel = String(localized: "air_compose_send")
        sendButton.accessibilityIdentifier = "air.chat.send"
        sendButton.addTarget(self, action: #selector(ChatInputView.airPressDown(_:)), for: [.touchDown, .touchDragEnter])
        sendButton.addTarget(self, action: #selector(ChatInputView.airPressUp(_:)), for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])

        voiceButton.setTitle(nil, for: .normal)
        voiceButton.setImage(AirIcon.image("mic"), for: .normal)
        voiceButton.tintColor = KQColor.ink2
        voiceButton.layer.cornerRadius = KQRadius.control
        voiceButton.accessibilityLabel = String(localized: "air_compose_voice")
        voiceButton.accessibilityHint = String(localized: "group_voice_hold_to_record")
        voiceButton.accessibilityIdentifier = "air.chat.voice"
        voiceButton.addTarget(self, action: #selector(ChatInputView.airPressDown(_:)), for: [.touchDown, .touchDragEnter])
        voiceButton.addTarget(self, action: #selector(ChatInputView.airPressUp(_:)), for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])

        // At the accessibility text sizes the arrow and the microphone show large on a long press, as the
        // paperclip does. The microphone records while it is held: the viewer opens with the hold, not in
        // its place, and lifting the finger ends the recording only.
        AirLargeContent.show(on: sendButton, title: sendButton.accessibilityLabel, image: AirIcon.image("arrow-up"))
        AirLargeContent.show(on: voiceButton, title: voiceButton.accessibilityLabel, image: AirIcon.image("mic"),
                             delegate: voiceHoldWithLargeContent)
        voiceHoldWithLargeContent.interaction = voiceButton.interactions.compactMap { $0 as? UILargeContentViewerInteraction }.first
        voiceHold?.delegate = voiceHoldWithLargeContent
        attach.growsWithText = true

        recordingBar.backgroundColor = KQColor.surface
        recordingDot.backgroundColor = KQColor.danger
        recordingDot.layer.cornerRadius = KQSize.dot / 2
        recordingTimerLabel.font = KQFont.tabular(KQFont.font(.body))
        recordingTimerLabel.textColor = KQColor.ink
        slideHintLabel.font = KQFont.font(.label, weight: 400)
        slideHintLabel.textColor = KQColor.ink3
        recordingCancelButton.setTitleColor(KQColor.ink2, for: .normal)
        recordingCancelButton.titleLabel?.font = KQFont.font(.label)
        recordingSendButton.backgroundColor = KQColor.ink
        recordingSendButton.layer.cornerRadius = KQRadius.control
        recordingSendButton.tintColor = KQColor.onInk
        recordingSendButton.setTitle(nil, for: .normal)
        recordingSendButton.setImage(AirIcon.image("arrow-up"), for: .normal)

        let hint = AirComposeHintView()
        addSubview(hint)
        hintView = hint

        // The old bar sizes itself through one height constraint on the whole view; the composer sizes
        // itself from the height of the field.
        if didconstraint == 1 {
            myHeight.deactivate()
            didconstraint = 0
        }

        box.snp.makeConstraints {
            $0.top.equalTo(self).offset(KQSpace.x8)
            $0.leading.equalTo(safeAreaLayoutGuide).offset(KQSpace.x12)
            $0.trailing.equalTo(safeAreaLayoutGuide).offset(-KQSpace.x12)
        }
        attach.snp.makeConstraints {
            $0.leading.equalTo(box).offset(KQSpace.x4)
            $0.bottom.equalTo(box).offset(-KQSpace.x4)
        }
        voiceButton.snp.remakeConstraints {
            $0.trailing.equalTo(box).offset(-KQSpace.x4)
            $0.bottom.equalTo(box).offset(-KQSpace.x4)
            $0.width.height.equalTo(KQSize.targetIos)
        }
        sendButton.snp.remakeConstraints {
            $0.center.equalTo(voiceButton)
            $0.width.height.equalTo(KQSize.targetIos)
        }
        hint.snp.makeConstraints {
            $0.top.equalTo(box.snp.bottom).offset(KQSpace.x6)
            $0.leading.equalTo(box).offset(KQSpace.x4)
            $0.trailing.equalTo(box).offset(-KQSpace.x4)
            $0.bottom.equalTo(self).offset(-KQSpace.x8)
        }
        recordingBar.snp.remakeConstraints {
            $0.edges.equalTo(box).inset(KQStroke.hairline)
        }
        recordingDot.snp.remakeConstraints {
            $0.leading.equalTo(recordingBar).offset(KQSpace.x16)
            $0.centerY.equalTo(recordingBar)
            $0.width.height.equalTo(KQSize.dot)
        }
        recordingSendButton.snp.remakeConstraints {
            $0.trailing.equalTo(recordingBar).offset(-KQSpace.x4 + KQStroke.hairline)
            $0.centerY.equalTo(recordingBar)
            $0.width.height.equalTo(KQSize.targetIos)
        }
        layoutAirText()

        applyAirFocus(false)
        updateAirGlyphs()
        updateViews()
        updateTextviewHeight(textView)
    }

    /// At the accessibility text sizes the glyphs of the field grow to 28 points in their 44 point targets,
    /// beside text many times their size; at the other sizes they keep the size of the design, so nothing
    /// moves. The paperclip follows its own traits (AirIconButton.growsWithText).
    fileprivate func updateAirGlyphs() {
        let grown = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        let glyph = { (name: String) -> UIImage? in
            return grown ? AirIcon.image(name, side: AirLargeContent.grownGlyph) : AirIcon.image(name)
        }
        voiceButton.setImage(glyph("mic"), for: .normal)
        sendButton.setImage(glyph("arrow-up"), for: .normal)
    }

    /// The text runs from the paperclip (or the frame, when there is nothing to attach) to the button.
    fileprivate func layoutAirText() {
        guard let box = airBox else {
            return
        }
        let height = airTextHeight?.layoutConstraints.first?.constant ?? KQSize.targetIos
        textView.snp.remakeConstraints {
            if attachmentButtonHidden || airAttachButton == nil {
                $0.leading.equalTo(box).offset(KQSpace.x8)
            }
            else if let attach = airAttachButton {
                $0.leading.equalTo(attach.snp.trailing).offset(KQSpace.x6 - KQSpace.x4)
            }
            $0.trailing.equalTo(voiceButton.snp.leading).offset(-KQSpace.x6)
            $0.top.equalTo(box).offset(KQSpace.x4)
            $0.bottom.equalTo(box).offset(-KQSpace.x4)
            airTextHeight = $0.height.equalTo(height).constraint
        }
        textViewLeadingConstraint = nil
        airPlaceholder?.snp.remakeConstraints {
            $0.leading.equalTo(textView).offset(KQSpace.x4)
            $0.trailing.lessThanOrEqualTo(textView)
            $0.top.equalTo(textView).offset(KQSpace.x10)
        }
    }

    /// From one line (44 points with its padding) up to five, then the text scrolls inside the field.
    fileprivate func updateAirTextHeight(_ t: UITextView) {
        let line = ceil(KQFont.lineHeight(.body))
        let padding = t.textContainerInset.top + t.textContainerInset.bottom
        let maxHeight = max(KQSize.composeMax, 5 * line + padding)
        guard t.bounds.width > 0 else {
            airTextHeight?.update(offset: max(KQSize.targetIos, line + padding))
            return
        }
        let fitting = ceil(t.sizeThatFits(CGSize(width: t.bounds.width, height: .greatestFiniteMagnitude)).height)
        let height = min(max(KQSize.targetIos, fitting), maxHeight)
        if airTextHeight?.layoutConstraints.first?.constant != height {
            airTextHeight?.update(offset: height)
        }
        t.isScrollEnabled = true
        t.showsVerticalScrollIndicator = fitting > maxHeight
        airPlaceholder?.isHidden = !t.text.isEmpty || isVoiceRecording
    }

    /// The frame darkens to ink while the field has the focus, as .kq-compose:focus-within.
    fileprivate func applyAirFocus(_ focused: Bool) {
        guard let box = airBox else {
            return
        }
        box.layer.borderColor = airCGColor(focused ? KQColor.ink : KQColor.lineStrong)
    }

    /// `fieldEnabled` applied: off, the field gives up the focus and takes no touch, it and its frame
    /// fade as a disabled control does, and VoiceOver says it is dimmed.
    fileprivate func applyFieldEnabled() {
        if !fieldEnabled {
            _ = resignFirstResponder()
        }
        textView.isEditable = fieldEnabled
        textView.isSelectable = fieldEnabled
        if fieldEnabled {
            textView.accessibilityTraits &= ~UIAccessibilityTraitNotEnabled
        }
        else {
            textView.accessibilityTraits |= UIAccessibilityTraitNotEnabled
        }
        let alpha: CGFloat = fieldEnabled ? 1 : 0.4
        airBox?.alpha = alpha
        airPlaceholder?.alpha = alpha
        textView.alpha = alpha
        updateViews()
    }

    @objc fileprivate func airAttachPressed() {
        guard let attach = airAttachButton else {
            return
        }
        delegate?.chatInputViewCameraButtonPressed(self, cameraView: attach)
    }

    @objc fileprivate func airPressDown(_ sender: UIView) {
        UIView.animate(withDuration: KQMotion.duration(KQMotion.tap), delay: 0, options: [.beginFromCurrentState, .allowUserInteraction], animations: {
            sender.transform = UIAccessibilityIsReduceMotionEnabled() ? .identity : CGAffineTransform(scaleX: 0.97, y: 0.97)
        }, completion: nil)
    }

    @objc fileprivate func airPressUp(_ sender: UIView) {
        UIView.animate(withDuration: KQMotion.duration(KQMotion.tap), delay: 0, options: [.beginFromCurrentState, .allowUserInteraction], animations: {
            sender.transform = .identity
        }, completion: nil)
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

/// The microphone records while it is held, and the Large Content Viewer opens on a long press of the same
/// button: the two run together, so at the accessibility text sizes the microphone shows large while it
/// records, and the hold is never taken away by the viewer. Lifting the finger ends the recording, so the
/// viewer does not act on the button as well: its tap only asks to hold the button.
final class AirHoldWithLargeContent: NSObject, UIGestureRecognizerDelegate, UILargeContentViewerInteractionDelegate {
    weak var interaction: UILargeContentViewerInteraction?

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        if let viewer = interaction?.gestureRecognizerForExclusionRelationship, otherGestureRecognizer === viewer {
            return true
        }
        return otherGestureRecognizer.view === gestureRecognizer.view
    }

    func largeContentViewerInteraction(_ interaction: UILargeContentViewerInteraction,
                                       didEndOn item: UILargeContentViewerItem?, at point: CGPoint) {
        // The hold that ran with the viewer has sent or cancelled the recording when the finger lifted.
    }
}
