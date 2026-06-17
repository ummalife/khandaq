// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

final class GroupNgcVideoOverlayView: UIView {
    var onQualityToggle: (() -> Void)?

    private let remoteImageView = UIImageView()
    private let localPreviewView = UIImageView()
    private let qualityButton = UIButton(type: .system)
    private var waitingIndicator: UIActivityIndicatorView!
    private let streamInfoLabel = UILabel()
    private var highQuality = false

    override init(frame: CGRect) {
        super.init(frame: frame)
        setupViews()
    }

    required init?(coder: NSCoder) {
        super.init(coder: coder)
        setupViews()
    }

    private func setupViews() {
        backgroundColor = UIColor.black.withAlphaComponent(0.85)
        isHidden = true
        clipsToBounds = true

        remoteImageView.contentMode = .scaleAspectFit
        remoteImageView.backgroundColor = .black
        addSubview(remoteImageView)

        if #available(iOS 13.0, *) {
            waitingIndicator = UIActivityIndicatorView(activityIndicatorStyle: .large)
        } else {
            waitingIndicator = UIActivityIndicatorView(activityIndicatorStyle: .whiteLarge)
        }
        waitingIndicator.color = .white
        waitingIndicator.hidesWhenStopped = true
        addSubview(waitingIndicator)

        localPreviewView.contentMode = .scaleAspectFill
        localPreviewView.clipsToBounds = true
        localPreviewView.layer.cornerRadius = 8
        localPreviewView.layer.borderWidth = 1
        localPreviewView.layer.borderColor = UIColor.white.withAlphaComponent(0.6).cgColor
        localPreviewView.backgroundColor = UIColor(white: 0.1, alpha: 1)
        addSubview(localPreviewView)

        qualityButton.tintColor = .white
        qualityButton.backgroundColor = UIColor.black.withAlphaComponent(0.45)
        qualityButton.layer.cornerRadius = 8
        qualityButton.contentEdgeInsets = UIEdgeInsets(top: 6, left: 10, bottom: 6, right: 10)
        qualityButton.addTarget(self, action: #selector(qualityButtonTapped), for: .touchUpInside)
        addSubview(qualityButton)
        updateQualityButtonTitle()

        streamInfoLabel.font = UIFont.monospacedDigitSystemFont(ofSize: 12, weight: .medium)
        streamInfoLabel.textColor = UIColor.white.withAlphaComponent(0.85)
        streamInfoLabel.numberOfLines = 2
        streamInfoLabel.textAlignment = .center
        streamInfoLabel.isHidden = true
        addSubview(streamInfoLabel)

        remoteImageView.snp.makeConstraints { make in
            make.edges.equalToSuperview()
        }

        waitingIndicator.snp.makeConstraints { make in
            make.center.equalToSuperview()
        }

        localPreviewView.snp.makeConstraints { make in
            make.width.equalTo(96)
            make.height.equalTo(128)
            make.trailing.equalToSuperview().inset(12)
            make.top.equalTo(safeAreaLayoutGuide.snp.top).offset(12)
        }

        qualityButton.snp.makeConstraints { make in
            make.leading.equalToSuperview().inset(12)
            make.top.equalTo(safeAreaLayoutGuide.snp.top).offset(12)
        }

        streamInfoLabel.snp.makeConstraints { make in
            make.leading.trailing.equalToSuperview().inset(12)
            make.bottom.equalTo(safeAreaLayoutGuide.snp.bottom).inset(12)
        }
    }

    @objc private func qualityButtonTapped() {
        onQualityToggle?()
    }

    func setVisible(_ visible: Bool) {
        isHidden = !visible

        if !visible {
            waitingIndicator.stopAnimating()
        }
    }

    func setHighQuality(_ enabled: Bool) {
        highQuality = enabled
        updateQualityButtonTitle()
    }

    func updateRemoteFrame(_ image: UIImage?) {
        if let image = image {
            remoteImageView.image = image
            waitingIndicator.stopAnimating()
        } else {
            remoteImageView.image = nil
            waitingIndicator.startAnimating()
        }
    }

    func updateLocalFrame(_ image: UIImage?) {
        localPreviewView.image = image
    }

    func clearFrames() {
        remoteImageView.image = nil
        localPreviewView.image = nil
        waitingIndicator.stopAnimating()
        setStreamInfo(nil)
    }

    func setStreamInfo(_ text: String?) {
        if let text = text, !text.isEmpty {
            streamInfoLabel.text = text
            streamInfoLabel.isHidden = false
        } else {
            streamInfoLabel.text = nil
            streamInfoLabel.isHidden = true
        }
    }

    private func updateQualityButtonTitle() {
        qualityButton.setTitle(
            String(localized: highQuality ? "group_live_video_quality_high" : "group_live_video_quality_low"),
            for: .normal)
        qualityButton.accessibilityLabel = String(localized: "group_live_video_quality_toggle")
    }
}
