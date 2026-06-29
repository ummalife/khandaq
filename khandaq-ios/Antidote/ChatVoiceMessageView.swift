// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

final class ChatVoiceMessageView: UIView {
    var onPlayTapped: (() -> Void)?

    private let playButton = UIButton(type: .system)
    private let progressSlider = UISlider()
    private let elapsedLabel = UILabel()
    private let durationLabel = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        createViews()
        installConstraints()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func apply(theme: Theme, enabled: Bool) {
        playButton.tintColor = theme.colorForType(.LinkText)
        elapsedLabel.textColor = theme.colorForType(.NormalText)
        durationLabel.textColor = theme.colorForType(.FriendCellStatus)
        progressSlider.minimumTrackTintColor = theme.colorForType(.LinkText)
        progressSlider.maximumTrackTintColor = theme.colorForType(.NormalText).withAlphaComponent(0.25)
        progressSlider.isUserInteractionEnabled = false
        progressSlider.isEnabled = enabled
        playButton.isEnabled = enabled
        alpha = enabled ? 1 : 0.55
    }

    func update(isPlaying: Bool, progress: Float, currentTime: TimeInterval, duration: TimeInterval, enabled: Bool) {
        let imageName = isPlaying ? "chat-file-pause-big" : "chat-file-play-big"
        playButton.setImage(UIImage.templateNamed(imageName), for: .normal)
        progressSlider.value = progress
        elapsedLabel.text = Self.formatTime(currentTime)
        durationLabel.text = Self.formatTime(duration > 0 ? duration : 0)
        progressSlider.isEnabled = enabled && duration > 0
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

        progressSlider.minimumValue = 0
        progressSlider.maximumValue = 1
        addSubview(progressSlider)

        elapsedLabel.font = UIFont.monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        durationLabel.font = UIFont.monospacedDigitSystemFont(ofSize: 12, weight: .regular)
        addSubview(elapsedLabel)
        addSubview(durationLabel)
    }

    private func installConstraints() {
        playButton.snp.makeConstraints {
            $0.leading.centerY.equalToSuperview()
            $0.size.equalTo(40)
        }

        elapsedLabel.snp.makeConstraints {
            $0.leading.equalTo(playButton.snp.trailing).offset(8)
            $0.centerY.equalToSuperview()
            $0.width.greaterThanOrEqualTo(34)
        }

        durationLabel.snp.makeConstraints {
            $0.trailing.equalToSuperview()
            $0.centerY.equalToSuperview()
            $0.width.greaterThanOrEqualTo(34)
        }

        progressSlider.snp.makeConstraints {
            $0.leading.equalTo(elapsedLabel.snp.trailing).offset(8)
            $0.trailing.equalTo(durationLabel.snp.leading).offset(-8)
            $0.centerY.equalToSuperview()
        }

        snp.makeConstraints {
            $0.height.equalTo(44)
        }
    }
}
