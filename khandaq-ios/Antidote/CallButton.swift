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
            case .answerVideo:
                setImage(UIImage.templateNamed("video-call-30"), for: .normal)
            case .video:
                // KHANDAQ (calls stage, 26.11): the camera off is the camera crossed out, as the muted
                // microphone is (R7); on, the plain camera on the white fill.
                let camera = UIImage.templateNamed("video-call-30")
                setImage(CallButton.crossedOut(camera), for: .normal)
                setSelectedImage(camera)
            case .mute:
                setImage(UIImage.templateNamed("mute"), for: .normal)
                setSelectedImage(UIImage.templateNamed("mute-selected"))
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

    /// KHANDAQ (calls stage, 26.11): a switch that is on keeps the glyph of its state while pressed and
    /// while disabled at the end of a call; UIButton takes the normal image for those mixed states.
    private func setSelectedImage(_ image: UIImage) {
        setImage(image, for: .selected)
        setImage(image, for: [.selected, .highlighted])
        setImage(image, for: [.selected, .disabled])
    }

    /// KHANDAQ (calls stage, 26.11): a glyph crossed out corner to corner, drawn as mute-selected crosses
    /// the microphone: a slash with a gap cut along its upper side, so off reads at a glance (R7).
    static func crossedOut(_ glyph: UIImage) -> UIImage {
        let size = glyph.size
        // The proportions of mute-selected: the slash about a ninth of the side thick, the gap a twelfth,
        // its ends a twelfth in from the corners.
        let slash = size.width / 9
        let cut = size.width / 12
        let end = size.width / 12
        let start = CGPoint(x: end, y: end)
        let finish = CGPoint(x: size.width - end, y: size.height - end)
        // Towards the upper right of the slash, across it.
        let normal = CGPoint(x: sqrt(0.5), y: -sqrt(0.5))

        return UIGraphicsImageRenderer(size: size).image { context in
            glyph.draw(in: CGRect(origin: .zero, size: size))
            let cg = context.cgContext
            cg.setLineCap(.round)

            // The gap: the slash and the cut beside it cleared, then the slash drawn back.
            let shift = cut / 2
            cg.setBlendMode(.clear)
            cg.setLineWidth(slash + cut)
            cg.move(to: CGPoint(x: start.x + normal.x * shift, y: start.y + normal.y * shift))
            cg.addLine(to: CGPoint(x: finish.x + normal.x * shift, y: finish.y + normal.y * shift))
            cg.strokePath()

            cg.setBlendMode(.normal)
            cg.setStrokeColor(UIColor.black.cgColor)
            cg.setLineWidth(slash)
            cg.move(to: start)
            cg.addLine(to: finish)
            cg.strokePath()
        }.withRenderingMode(.alwaysTemplate)
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
