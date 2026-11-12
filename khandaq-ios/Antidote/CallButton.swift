// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// A button of the call screens (redesign stage 3, E9): a 64 pt circle for the thumb. Answer is filled
/// green and decline or end filled red, both with a white icon; the switches (microphone, speaker,
/// camera) are translucent over the dark ground and turn white with a dark icon while they are on.
/// AirCallControl puts its word under it.
class CallButton: UIButton {
    /// Both sizes are 64 pt now; the size is kept for the callers.
    enum ButtonSize {
        case small
        case big
    }

    enum ButtonType {
        case decline
        case answerAudio
        case answerVideo
        case mute
        case speaker
        case video
    }

    let type: ButtonType

    override var isSelected: Bool {
        didSet {
            applyState()
        }
    }

    override var isHighlighted: Bool {
        didSet {
            applyState()
        }
    }

    override var isEnabled: Bool {
        didSet {
            applyState()
        }
    }

    /// `theme` is no longer read: the colours come from the design tokens.
    init(theme: Theme?, type: ButtonType, buttonSize: ButtonSize) {
        self.type = type
        super.init(frame: .zero)

        layer.cornerRadius = KQSpace.x64 / 2
        layer.masksToBounds = true

        switch type {
            case .decline:
                setImage(UIImage.templateNamed("end-call"), for: .normal)
            case .answerAudio:
                setImage(UIImage.templateNamed("start-call-30"), for: .normal)
            case .answerVideo, .video:
                setImage(UIImage.templateNamed("video-call-30"), for: .normal)
            case .mute:
                setImage(UIImage.templateNamed("mute"), for: .normal)
                setImage(UIImage.templateNamed("mute-selected"), for: .selected)
            case .speaker:
                setImage(UIImage.templateNamed("speaker"), for: .normal)
        }
        accessibilityLabel = CallButton.title(for: type)
        applyState()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: CGSize {
        return CGSize(width: KQSpace.x64, height: KQSpace.x64)
    }

    /// The word for the button where nothing more particular is said.
    static func title(for type: ButtonType) -> String {
        switch type {
            case .decline:
                return String(localized: "air_call_end")
            case .answerAudio:
                return String(localized: "air_call_answer")
            case .answerVideo:
                return String(localized: "air_call_answer_video")
            case .mute:
                return String(localized: "air_call_mic")
            case .speaker:
                return String(localized: "air_call_speaker")
            case .video:
                return String(localized: "air_call_camera")
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyState()
    }

    private var isSwitch: Bool {
        switch type {
            case .mute, .speaker, .video:
                return true
            case .decline, .answerAudio, .answerVideo:
                return false
        }
    }

    private func applyState() {
        switch type {
            case .decline:
                backgroundColor = KQColor.dangerFill
                tintColor = KQColor.fixedWhite
            case .answerAudio, .answerVideo:
                backgroundColor = KQColor.liveFill
                tintColor = KQColor.fixedWhite
            case .mute, .speaker, .video:
                backgroundColor = isSelected ? KQColor.fixedWhite : KQColor.mediaControl
                tintColor = isSelected ? KQColor.lockTop : KQColor.fixedWhite
        }
        alpha = isEnabled ? 1 : 0.4
        transform = isHighlighted ? CGAffineTransform(scaleX: 0.97, y: 0.97) : .identity

        if isSwitch {
            accessibilityValue = switchValue()
        }
    }

    /// The microphone switch is on while the microphone is off (muted); the others while they work.
    private func switchValue() -> String {
        switch type {
            case .mute:
                return String(localized: isSelected ? "air_call_mic_off" : "air_call_mic_on")
            case .speaker:
                return String(localized: isSelected ? "air_call_speaker_on" : "air_call_speaker_off")
            case .video:
                return String(localized: isSelected ? "air_call_camera_on" : "air_call_camera_off")
            case .decline, .answerAudio, .answerVideo:
                return ""
        }
    }
}
