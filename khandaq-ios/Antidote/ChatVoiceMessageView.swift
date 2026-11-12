// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

/// A voice note in the transcript (redesign stage 3, E8): "Listen", the wave that fills as it plays,
/// and the length in tabular figures, so the line keeps its width while the seconds run. A tap or a
/// drag on the wave moves to that point.
final class ChatVoiceMessageView: UIView {
    var onPlayTapped: (() -> Void)?
    // KHANDAQ (Android parity): scrub, a drag or a tap on the wave seeks; reports the share 0 to 1.
    var onSeek: ((Float) -> Void)?
    /// Said instead of "0:00" while the length is not known, as for a note that is not here yet: its size.
    var unknownLengthText: String?

    private let playButton = AirButton(style: .normal, title: String(localized: "air_voice_listen"))
    private let waveformView = VoiceWaveformView()
    private let durationLabel = UILabel()
    private var playWidthConstraint: Constraint!
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

    /// The button plays only a note that is on this device.
    func apply(enabled: Bool) {
        playButton.isEnabled = enabled
        waveformView.isEnabled = enabled
        waveformView.isUserInteractionEnabled = enabled
    }

    func update(isPlaying: Bool, progress: Float, currentTime: TimeInterval, duration: TimeInterval, enabled: Bool) {
        self.isPlaying = isPlaying
        playButton.setTitle(String(localized: isPlaying ? "air_voice_pause" : "air_voice_listen"))
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
            playWidthConstraint.update(offset: playButtonWidth())
        }
        durationLabel.textColor = KQColor.ink3
        waveformView.setNeedsDisplay()
    }

    @objc private func playButtonTapped() {
        onPlayTapped?()
    }

    private func createViews() {
        playButton.accessibilityIdentifier = "air.voice.play"
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
        addSubview(durationLabel)
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

    /// "Listen" and "Pause" take the same width, so the wave does not move when the words change.
    private func playButtonWidth() -> CGFloat {
        let font = KQFont.font(.label)
        let widest = ["air_voice_listen", "air_voice_pause"]
            .map { (String(localized: $0) as NSString).size(withAttributes: [NSAttributedStringKey.font: font]).width }
            .max() ?? 0
        return ceil(widest) + 2 * KQSpace.x16
    }

    private func installConstraints() {
        playButton.snp.makeConstraints {
            $0.leading.top.equalToSuperview()
            // Gives way while a stack hides the note, instead of fighting its zero height.
            $0.bottom.equalToSuperview().priority(999)
            playWidthConstraint = $0.width.equalTo(playButtonWidth()).constraint
        }
        waveformView.snp.makeConstraints {
            $0.leading.equalTo(playButton.snp.trailing).offset(KQSpace.x10)
            $0.trailing.equalTo(durationLabel.snp.leading).offset(-KQSpace.x10)
            $0.centerY.equalTo(playButton)
            $0.height.equalTo(KQSize.controlSmall)
        }
        durationLabel.snp.makeConstraints {
            $0.trailing.equalToSuperview()
            $0.centerY.equalTo(playButton)
        }
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
