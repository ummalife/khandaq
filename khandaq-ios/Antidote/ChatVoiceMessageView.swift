// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

/// A voice note in the transcript (redesign stage 3, E8): the round play button (0.2.56, as in Telegram),
/// the wave that fills as it plays, and the length in tabular figures, so the line keeps its width while
/// the seconds run. A tap or a drag on the wave moves to that point. While the note plays, its speed
/// stands after the length (ideas 1).
final class ChatVoiceMessageView: UIView {
    var onPlayTapped: (() -> Void)?
    // KHANDAQ (Android parity): scrub, a drag or a tap on the wave seeks; reports the share 0 to 1.
    var onSeek: ((Float) -> Void)?
    /// KHANDAQ (ideas 1): a tap on the speed, which goes on to the next one.
    var onSpeedTapped: (() -> Void)?
    /// Said instead of "0:00" while the length is not known, as for a note that is not here yet: its size.
    var unknownLengthText: String?

    private let playButton = AirPlayButton()
    private let waveformView = VoiceWaveformView()
    private let durationLabel = UILabel()
    private let speedButton = VoiceSpeedButton()
    /// The length and, while the note plays, its speed: a hidden speed takes no room.
    private let trailingStack = UIStackView()
    private var fontCategory = UIApplication.shared.preferredContentSizeCategory
    private var isPlaying = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        createViews()
        installConstraints()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// The wave, where a drag scrubs; the swipe that answers the message leaves a drag there to it.
    var scrubArea: UIView {
        return waveformView
    }

    /// The button plays only a note that is on this device.
    func apply(enabled: Bool) {
        playButton.isEnabled = enabled
        waveformView.isEnabled = enabled
        waveformView.isUserInteractionEnabled = enabled
    }

    func update(isPlaying: Bool, progress: Float, currentTime: TimeInterval, duration: TimeInterval, enabled: Bool) {
        self.isPlaying = isPlaying
        playButton.isPlaying = isPlaying
        // The words are not drawn: they are what VoiceOver says for the button.
        playButton.accessibilityLabel = String(localized: isPlaying ? "air_voice_pause" : "air_voice_listen")
        waveformView.progress = progress
        // At rest the length of the note; while it plays the time played so far.
        if isPlaying {
            durationLabel.text = Self.formatTime(currentTime)
        }
        else if duration > 0 || (unknownLengthText ?? "").isEmpty {
            durationLabel.text = Self.formatTime(max(0, duration))
        }
        else {
            durationLabel.text = unknownLengthText
        }
        playButton.accessibilityValue = durationLabel.text
        apply(enabled: enabled)
    }

    /// What VoiceOver says about the note: its length, or how far it has played.
    var spokenText: String {
        return durationLabel.text ?? ""
    }

    /// KHANDAQ (ideas 1): the speed of the note while it plays or waits paused (ChatVoiceMessagePlayer
    /// .speedShown); nil hides it.
    func showSpeed(_ speed: Float?) {
        let text = speed.map { VoicePlaybackSpeed.text(for: $0) }
        guard text != shownSpeedText else {
            return
        }
        if let text = text {
            speedButton.text = text
        }
        speedButton.isHidden = text == nil
        // The wave gives the speed its room, or takes it back, on the next pass.
        setNeedsLayout()
    }

    /// The speed as it shows, nil while it is hidden.
    var shownSpeedText: String? {
        return speedButton.isHidden ? nil : speedButton.text
    }

    static func formatTime(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else {
            return "0:00"
        }
        let total = Int(seconds.rounded(.down))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        let category = UIApplication.shared.preferredContentSizeCategory
        if category != fontCategory {
            fontCategory = category
            durationLabel.font = KQFont.tabular(KQFont.font(.caption))
        }
        durationLabel.textColor = KQColor.ink3
        waveformView.setNeedsDisplay()
    }

    @objc private func playButtonTapped() {
        onPlayTapped?()
    }

    @objc private func speedButtonTapped() {
        onSpeedTapped?()
    }

    private func createViews() {
        playButton.accessibilityIdentifier = "air.voice.play"
        playButton.accessibilityLabel = String(localized: "air_voice_listen")
        playButton.addTarget(self, action: #selector(playButtonTapped), for: .touchUpInside)
        addSubview(playButton)

        // KHANDAQ (Android parity): scrub the note by dragging or tapping on the wave.
        waveformView.isAccessibilityElement = false
        let pan = UIPanGestureRecognizer(target: self, action: #selector(waveformScrubbed(_:)))
        waveformView.addGestureRecognizer(pan)
        let tap = UITapGestureRecognizer(target: self, action: #selector(waveformScrubbed(_:)))
        waveformView.addGestureRecognizer(tap)
        addSubview(waveformView)

        durationLabel.font = KQFont.tabular(KQFont.font(.caption))
        durationLabel.textColor = KQColor.ink3
        durationLabel.isAccessibilityElement = false
        durationLabel.setContentHuggingPriority(.required, for: .horizontal)
        durationLabel.setContentCompressionResistancePriority(.required, for: .horizontal)

        speedButton.addTarget(self, action: #selector(speedButtonTapped), for: .touchUpInside)

        trailingStack.axis = .horizontal
        trailingStack.alignment = .center
        trailingStack.spacing = KQSpace.x8
        trailingStack.addArrangedSubview(durationLabel)
        trailingStack.addArrangedSubview(speedButton)
        addSubview(trailingStack)
        speedButton.isHidden = true
    }

    @objc private func waveformScrubbed(_ recognizer: UIGestureRecognizer) {
        if let pan = recognizer as? UIPanGestureRecognizer,
           pan.state != .changed && pan.state != .ended {
            return
        }
        let width = waveformView.bounds.width
        guard width > 0 else {
            return
        }
        var x = recognizer.location(in: waveformView).x
        if waveformView.effectiveUserInterfaceLayoutDirection == .rightToLeft {
            x = width - x
        }
        let fraction = Float(max(0, min(1, x / width)))
        waveformView.progress = fraction
        onSeek?(fraction)
    }

    private func installConstraints() {
        playButton.snp.makeConstraints {
            $0.leading.top.equalToSuperview()
            // Gives way while a stack hides the note, instead of fighting its zero height.
            $0.bottom.equalToSuperview().priority(999)
        }
        waveformView.snp.makeConstraints {
            // The target reaches past the disc; the wave keeps its distance from the disc itself.
            $0.leading.equalTo(playButton.snp.trailing).offset(KQSpace.x10 - (KQSize.targetIos - KQSize.control))
            $0.trailing.equalTo(trailingStack.snp.leading).offset(-KQSpace.x10)
            $0.centerY.equalTo(playButton)
            $0.height.equalTo(KQSize.controlSmall)
        }
        trailingStack.snp.makeConstraints {
            $0.trailing.equalToSuperview()
            $0.centerY.equalTo(playButton)
        }
    }
}

/// KHANDAQ (ideas 1): the speed of the note that plays, "1.5x" in tabular figures on a small raised pill,
/// as the filter pills are drawn. It is drawn 28 high and takes a touch over 44.
private final class VoiceSpeedButton: UIControl {
    private let label = UILabel()
    private var fontCategory = UIApplication.shared.preferredContentSizeCategory

    var text: String? {
        get {
            return label.text
        }
        set {
            label.text = newValue
            accessibilityValue = newValue
            invalidateIntrinsicContentSize()
        }
    }

    /// The words and 6 on each side, at least as wide as high: what the stack beside the length gives it.
    override var intrinsicContentSize: CGSize {
        let words = label.intrinsicContentSize
        let height = max(KQSize.controlSmall, words.height + KQSpace.x2 * 2)
        return CGSize(width: max(height, words.width + KQSpace.x6 * 2), height: height)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        accessibilityLabel = String(localized: "air_voice_speed")
        accessibilityIdentifier = "air.voice.speed"
        layer.borderWidth = KQStroke.hairline

        label.font = KQFont.tabular(KQFont.font(.caption))
        label.isUserInteractionEnabled = false
        addSubview(label)
        label.snp.makeConstraints {
            $0.center.equalToSuperview()
        }
        setContentHuggingPriority(.required, for: .horizontal)
        setContentCompressionResistancePriority(.required, for: .horizontal)
        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            alpha = isHighlighted ? 0.7 : 1
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layer.cornerRadius = bounds.height / 2
    }

    /// The pill is drawn 28 high; the area that takes a touch is 44, so a thumb never aims at 28.
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        let growX = max(0, (KQSize.targetIos - bounds.width) / 2)
        let growY = max(0, (KQSize.targetIos - bounds.height) / 2)
        return bounds.insetBy(dx: -growX, dy: -growY).contains(point)
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        let category = UIApplication.shared.preferredContentSizeCategory
        if category != fontCategory {
            fontCategory = category
            label.font = KQFont.tabular(KQFont.font(.caption))
            invalidateIntrinsicContentSize()
        }
        apply()
    }

    private func apply() {
        backgroundColor = KQColor.raised
        layer.borderColor = airCGColor(KQColor.lineStrong)
        label.textColor = KQColor.ink2
    }
}

// KHANDAQ (Figma): the wave of a voice note. There are no samples per message, so the bar heights are
// a fixed pseudo-wave, the same on every draw; the part played is drawn in ink.
private final class VoiceWaveformView: UIView {
    var progress: Float = 0 {
        didSet {
            setNeedsDisplay()
        }
    }

    var isEnabled = true {
        didSet {
            setNeedsDisplay()
        }
    }

    private static let heights: [CGFloat] = (0..<44).map { i in
        let a = sin(Double(i) * 1.3)
        let b = sin(Double(i) * 0.7 + 1.1)
        let v = abs(a * 0.6 + b * 0.4)
        return CGFloat(0.25 + 0.7 * v)
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .clear
        isOpaque = false
        contentMode = .redraw
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func draw(_ rect: CGRect) {
        let heights = Self.heights
        let count = heights.count
        guard count > 0, bounds.width > 0, bounds.height > 0 else {
            return
        }
        let spacing = KQSpace.x2
        let barWidth = max(KQStroke.strong, (bounds.width - spacing * CGFloat(count - 1)) / CGFloat(count))
        let step = barWidth + spacing
        let playedCount = Int((Float(count) * max(0, min(1, progress))).rounded())
        let rightToLeft = effectiveUserInterfaceLayoutDirection == .rightToLeft
        let played = isEnabled ? KQColor.ink : KQColor.ink3
        let rest = isEnabled ? KQColor.lineStrong : KQColor.line
        for i in 0..<count {
            let h = max(KQSpace.x2, heights[i] * bounds.height)
            let slot = rightToLeft ? count - 1 - i : i
            let barRect = CGRect(x: CGFloat(slot) * step, y: (bounds.height - h) / 2, width: barWidth, height: h)
            let path = UIBezierPath(roundedRect: barRect, cornerRadius: barWidth / 2)
            (i < playedCount ? played : rest).setFill()
            path.fill()
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        setNeedsDisplay()
    }
}
