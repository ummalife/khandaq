// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let TopBorderHeight = 0.5
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

    fileprivate var topBorder: UIView!
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
    fileprivate var myHeight: Constraint!
    fileprivate var didconstraint = 0

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

    override func becomeFirstResponder() -> Bool {
        return textView.becomeFirstResponder()
    }

    override func resignFirstResponder() -> Bool {
        return textView.resignFirstResponder()
    }
}

// MARK: Actions
extension ChatInputView {
    @objc func cameraButtonPressed() {
        delegate?.chatInputViewCameraButtonPressed(self, cameraView: cameraButton)
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
                showRecordingBar()
                delegate?.chatInputViewVoiceRecordDidStart(self)
            case .ended:
                guard isVoiceRecording, !recordingEndedByControl else { return }
                finishVoiceRecording(cancelled: false)
            case .cancelled, .failed:
                guard isVoiceRecording, !recordingEndedByControl else { return }
                finishVoiceRecording(cancelled: true)
            default:
                break
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
        addSubview(topBorder)

        let cameraImage = UIImage.templateNamed("chat-camera")

        cameraButton = UIButton()
        cameraButton.setImage(cameraImage, for: UIControlState())
        cameraButton.tintColor = theme.colorForType(.LinkText)
        cameraButton.addTarget(self, action: #selector(ChatInputView.cameraButtonPressed), for: .touchUpInside)
        cameraButton.setContentCompressionResistancePriority(UILayoutPriority.required, for: .horizontal)
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
        textView.layer.cornerRadius = 5.0
        textView.layer.borderWidth = 0.5
        textView.layer.borderColor = theme.colorForType(.SeparatorsAndBorders).cgColor
        textView.layer.masksToBounds = true
        textView.setContentHuggingPriority(UILayoutPriority(rawValue: 0.0), for: .horizontal)
        textView.autocapitalizationType = .sentences

        addSubview(textView)

        sendButton = UIButton(type: .system)
        sendButton.setTitle(String(localized: "chat_send_button"), for: UIControlState())
        sendButton.titleLabel?.font = UIFont.khandaqFontWithSize(16.0, weight: .bold)
        sendButton.addTarget(self, action: #selector(ChatInputView.sendButtonPressed), for: .touchUpInside)
        sendButton.setContentCompressionResistancePriority(UILayoutPriority.required, for: .horizontal)
        addSubview(sendButton)

        recordingBar = UIView()
        recordingBar.isHidden = true
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

        recordingCancelButton = UIButton(type: .system)
        recordingCancelButton.setTitle(String(localized: "voice_recording_cancel"), for: .normal)
        recordingCancelButton.setTitleColor(UIColor(red: 0.165, green: 0.671, blue: 0.933, alpha: 1.0), for: .normal)
        recordingCancelButton.titleLabel?.font = UIFont.systemFont(ofSize: 16.0)
        recordingCancelButton.addTarget(self, action: #selector(ChatInputView.recordingCancelTapped), for: .touchUpInside)
        recordingBar.addSubview(recordingCancelButton)

        recordingSendButton = UIButton(type: .system)
        recordingSendButton.backgroundColor = UIColor(red: 0.165, green: 0.671, blue: 0.933, alpha: 1.0)
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

        cameraButton.snp.makeConstraints {
            $0.leading.equalTo(self).offset(Constants.CameraHorizontalOffset)
            $0.bottom.equalTo(self).offset(Constants.CameraBottomOffset)
        }

        voiceButton.snp.makeConstraints {
            $0.leading.equalTo(cameraButton.snp.trailing).offset(Constants.Offset)
            $0.bottom.equalTo(self).offset(Constants.CameraBottomOffset)
        }

        textView.snp.makeConstraints {
            $0.leading.equalTo(voiceButton.snp.trailing).offset(Constants.CameraHorizontalOffset)
            $0.top.equalTo(self).offset(Constants.Offset)
            $0.bottom.equalTo(self).offset(-Constants.Offset)
            $0.height.greaterThanOrEqualTo(Constants.TextViewMinHeight)
        }

        sendButton.snp.makeConstraints {
            $0.leading.equalTo(textView.snp.trailing).offset(Constants.Offset)
            $0.trailing.equalTo(self).offset(-Constants.Offset)
            $0.bottom.equalTo(self).offset(-Constants.Offset)
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

    func updateViews() {
        textView.isScrollEnabled = true
        textView.autocapitalizationType = .sentences
        cameraButton.isEnabled = cameraButtonEnabled
        voiceButton.isHidden = !voiceButtonEnabled
        voiceButton.isEnabled = voiceButtonEnabled
        sendButton.isEnabled = !textView.text.isEmpty
    }

    func showRecordingBar() {
        cameraButton.isHidden = true
        voiceButton.isHidden = true
        textView.isHidden = true
        sendButton.isHidden = true
        recordingBar.isHidden = false
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
        updateViews()
    }

    func finishVoiceRecording(cancelled: Bool) {
        guard isVoiceRecording else { return }
        isVoiceRecording = false
        hideRecordingBar()
        delegate?.chatInputViewVoiceRecordDidEnd(self, cancelled: cancelled)
    }

    func updateRecordingTimerLabel() {
        guard let startedAt = recordingStartedAt else { return }
        let elapsed = max(0.0, Date().timeIntervalSince(startedAt))
        let totalCentiseconds = Int(elapsed * 100.0)
        let minutes = totalCentiseconds / 6000
        let seconds = (totalCentiseconds / 100) % 60
        let centiseconds = totalCentiseconds % 100
        recordingTimerLabel.text = String(format: "%d:%02d,%02d", minutes, seconds, centiseconds)
    }
}
