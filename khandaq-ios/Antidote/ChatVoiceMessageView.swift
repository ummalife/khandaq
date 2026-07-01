// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

final class ChatVoiceMessageView: UIView {
    var onPlayTapped: (() -> Void)?
    // KHANDAQ (Figma voice bubble): tinted per direction, matching the text bubbles
    var isOutgoing = false

    private let playButton = UIButton(type: .system)
    private let waveformView = VoiceWaveformView()
    private let durationLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        layer.cornerRadius = 18
        clipsToBounds = true
        createViews()
        installConstraints()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func apply(theme: Theme, enabled: Bool) {
        backgroundColor = theme.colorForType(isOutgoing ? .ChatOutgoingBubble : .ChatIncomingBubble)
        playButton.tintColor = theme.colorForType(.LinkText)
        durationLabel.textColor = theme.colorForType(.FriendCellStatus)
        // KHANDAQ (Figma voice bubble): waveform bars instead of a plain slider
        waveformView.playedColor = theme.colorForType(.LinkText)
        waveformView.unplayedColor = theme.colorForType(.NormalText).withAlphaComponent(0.3)
        playButton.isEnabled = enabled
        alpha = enabled ? 1 : 0.55
    }

    func update(isPlaying: Bool, progress: Float, currentTime: TimeInterval, duration: TimeInterval, enabled: Bool) {
        let imageName = isPlaying ? "chat-file-pause-big" : "chat-file-play-big"
        playButton.setImage(UIImage.templateNamed(imageName), for: .normal)
        waveformView.progress = progress
        // idle shows the total duration (Figma), playing counts the elapsed time up
        durationLabel.text = Self.formatTime(isPlaying ? currentTime : (duration > 0 ? duration : 0))
        playButton.isEnabled = enabled
        alpha = enabled ? 1 : 0.55
    }

    private static func formatTime(_ seconds: TimeInterval) -> String {
        guard seconds.isFinite, seconds >= 0 else {
            return "0:00"
        }

        let total = Int(seconds.rounded(.down))
        return String(format: "%d:%02d", total / 60, total % 60)
    }

    @objc private func playButtonTapped() {
        onPlayTapped?()
    }

    private func createViews() {
        playButton.contentEdgeInsets = UIEdgeInsets(top: 6, left: 6, bottom: 6, right: 6)
        playButton.addTarget(self, action: #selector(playButtonTapped), for: .touchUpInside)
        addSubview(playButton)

        addSubview(waveformView)

        durationLabel.font = UIFont.monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        addSubview(durationLabel)
    }

    private func installConstraints() {
        playButton.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(10)
            $0.centerY.equalToSuperview()
            $0.size.equalTo(40)
        }

        durationLabel.snp.makeConstraints {
            $0.trailing.equalToSuperview().offset(-12)
            $0.centerY.equalToSuperview()
            $0.width.greaterThanOrEqualTo(34)
        }

        waveformView.snp.makeConstraints {
            $0.leading.equalTo(playButton.snp.trailing).offset(10)
            $0.trailing.equalTo(durationLabel.snp.leading).offset(-10)
            $0.centerY.equalToSuperview()
            $0.height.equalTo(28)
        }

        snp.makeConstraints {
            $0.height.equalTo(44)
        }
    }
}

// KHANDAQ (Figma): static waveform visualisation for voice messages. We have no per-message
// samples, so the bar heights are a deterministic pseudo-waveform (stable across redraws);
// the "played" portion up to `progress` is tinted with the accent colour.
private final class VoiceWaveformView: UIView {
    var progress: Float = 0 {
        didSet { setNeedsDisplay() }
    }
    var playedColor: UIColor = .systemTeal {
        didSet { setNeedsDisplay() }
    }
    var unplayedColor: UIColor = UIColor(white: 0.5, alpha: 0.35) {
        didSet { setNeedsDisplay() }
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

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func draw(_ rect: CGRect) {
        let heights = Self.heights
        let count = heights.count
        guard count > 0, rect.width > 0, rect.height > 0 else {
            return
        }

        let spacing: CGFloat = 2
        let barWidth = max(1.5, (rect.width - spacing * CGFloat(count - 1)) / CGFloat(count))
        let step = barWidth + spacing
        let playedCount = Int((Float(count) * max(0, min(1, progress))).rounded())

        for i in 0 ..< count {
            let h = max(2, heights[i] * rect.height)
            let x = CGFloat(i) * step
            let y = (rect.height - h) / 2
            let barRect = CGRect(x: x, y: y, width: barWidth, height: h)
            let path = UIBezierPath(roundedRect: barRect, cornerRadius: barWidth / 2)
            (i < playedCount ? playedColor : unplayedColor).setFill()
            path.fill()
        }
    }
}
