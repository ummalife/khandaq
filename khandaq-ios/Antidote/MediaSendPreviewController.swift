// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import MobileCoreServices
import Photos
import SnapKit
import UIKit

enum MediaSendPreviewItem {
    case image(UIImage, fileName: String?)
    case video(URL)
}

protocol MediaSendPreviewControllerDelegate: AnyObject {
    func mediaSendPreviewControllerDidCancel(_ controller: MediaSendPreviewController)
    func mediaSendPreviewController(_ controller: MediaSendPreviewController,
                                    didConfirm items: [MediaSendPreviewItem],
                                    caption: String)
}

final class MediaSendPreviewController: UIViewController {
    weak var delegate: MediaSendPreviewControllerDelegate?

    private let items: [MediaSendPreviewItem]
    private var currentIndex = 0
    private var previewRequestToken = UUID()

    private static let previewQueue = DispatchQueue(label: "khandaq.media.preview", qos: .userInitiated)

    private let closeButton = UIButton(type: .system)
    private let counterLabel = UILabel()
    private let imageView = UIImageView()
    private let playIconView = UILabel()
    private let captionField = UITextView()
    private let cancelButton = UIButton(type: .system)
    private let sendButton = UIButton(type: .system)
    private let bottomBar = UIView()

    init(items: [MediaSendPreviewItem]) {
        self.items = items
        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = .black
        installViews()
        installConstraints()
        wireActions()
        showItem(at: currentIndex)
    }

    private func installViews() {
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true

        playIconView.text = "▶"
        playIconView.font = .systemFont(ofSize: 48, weight: .bold)
        playIconView.textColor = UIColor.white.withAlphaComponent(0.92)
        playIconView.textAlignment = .center
        playIconView.isHidden = true

        closeButton.setTitle("✕", for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 22, weight: .medium)
        closeButton.tintColor = .white

        counterLabel.textColor = .white
        counterLabel.font = .systemFont(ofSize: 14, weight: .medium)
        counterLabel.textAlignment = .center
        counterLabel.backgroundColor = UIColor.black.withAlphaComponent(0.45)
        counterLabel.layer.cornerRadius = 12
        counterLabel.clipsToBounds = true
        counterLabel.isHidden = items.count <= 1

        captionField.backgroundColor = UIColor(white: 0.15, alpha: 1)
        captionField.textColor = .white
        captionField.font = .systemFont(ofSize: 16)
        captionField.layer.cornerRadius = 10
        captionField.textContainerInset = UIEdgeInsets(top: 10, left: 8, bottom: 10, right: 8)
        captionField.isScrollEnabled = false

        bottomBar.backgroundColor = UIColor(white: 0.08, alpha: 0.95)

        cancelButton.setTitle(String(localized: "alert_cancel"), for: .normal)
        cancelButton.setTitleColor(.white, for: .normal)

        sendButton.setTitle(String(localized: "media_send_button"), for: .normal)
        sendButton.setTitleColor(.white, for: .normal)
        sendButton.backgroundColor = UIColor(red: 0.04, green: 0.45, blue: 0.32, alpha: 1)
        sendButton.layer.cornerRadius = 8
        sendButton.contentEdgeInsets = UIEdgeInsets(top: 8, left: 16, bottom: 8, right: 16)

        view.addSubview(imageView)
        view.addSubview(playIconView)
        view.addSubview(closeButton)
        view.addSubview(counterLabel)
        view.addSubview(bottomBar)
        bottomBar.addSubview(captionField)
        bottomBar.addSubview(cancelButton)
        bottomBar.addSubview(sendButton)

        let swipeLeft = UISwipeGestureRecognizer(target: self, action: #selector(showNextItem))
        swipeLeft.direction = .left
        view.addGestureRecognizer(swipeLeft)

        let swipeRight = UISwipeGestureRecognizer(target: self, action: #selector(showPreviousItem))
        swipeRight.direction = .right
        view.addGestureRecognizer(swipeRight)
    }

    private func installConstraints() {
        closeButton.snp.makeConstraints { make in
            make.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(8)
            make.leading.equalToSuperview().offset(8)
            make.width.height.equalTo(44)
        }

        counterLabel.snp.makeConstraints { make in
            make.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(16)
            make.centerX.equalToSuperview()
            make.height.equalTo(24)
            make.width.greaterThanOrEqualTo(56)
        }

        bottomBar.snp.makeConstraints { make in
            make.leading.trailing.bottom.equalToSuperview()
        }

        captionField.snp.makeConstraints { make in
            make.top.equalToSuperview().offset(12)
            make.leading.trailing.equalToSuperview().inset(12)
            make.height.greaterThanOrEqualTo(44)
        }

        cancelButton.snp.makeConstraints { make in
            make.top.equalTo(captionField.snp.bottom).offset(10)
            make.leading.equalToSuperview().offset(12)
            make.bottom.equalTo(view.safeAreaLayoutGuide.snp.bottom).offset(-12)
        }

        sendButton.snp.makeConstraints { make in
            make.centerY.equalTo(cancelButton)
            make.trailing.equalToSuperview().offset(-12)
        }

        imageView.snp.makeConstraints { make in
            make.top.equalTo(view.safeAreaLayoutGuide.snp.top)
            make.leading.trailing.equalToSuperview()
            make.bottom.equalTo(bottomBar.snp.top)
        }

        playIconView.snp.makeConstraints { make in
            make.center.equalTo(imageView)
            make.width.height.equalTo(72)
        }
    }

    private func wireActions() {
        closeButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        cancelButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        sendButton.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
    }

    private func showItem(at index: Int) {
        guard items.indices.contains(index) else {
            return
        }

        currentIndex = index
        let requestToken = UUID()
        previewRequestToken = requestToken

        counterLabel.text = String(format: String(localized: "media_send_counter_format"),
                                   index + 1,
                                   items.count)
        counterLabel.isHidden = items.count <= 1

        switch items[index] {
        case .image(let image, _):
            imageView.image = image
            playIconView.isHidden = true
        case .video(let url):
            playIconView.isHidden = false
            imageView.image = nil
            Self.previewQueue.async {
                let frame = Self.previewFrame(for: url)
                DispatchQueue.main.async { [weak self] in
                    guard let self = self,
                          self.previewRequestToken == requestToken,
                          self.currentIndex == index else {
                        return
                    }
                    self.imageView.image = frame
                }
            }
        }
    }

    @objc private func showNextItem() {
        guard currentIndex + 1 < items.count else {
            return
        }
        showItem(at: currentIndex + 1)
    }

    @objc private func showPreviousItem() {
        guard currentIndex > 0 else {
            return
        }
        showItem(at: currentIndex - 1)
    }

    @objc private func cancelTapped() {
        delegate?.mediaSendPreviewControllerDidCancel(self)
    }

    @objc private func sendTapped() {
        let caption = captionField.text.trimmingCharacters(in: .whitespacesAndNewlines)
        delegate?.mediaSendPreviewController(self, didConfirm: items, caption: caption)
    }

    private static func previewFrame(for url: URL) -> UIImage? {
        guard FileManager.default.fileExists(atPath: url.path) else {
            return nil
        }

        let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: false])
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 720, height: 720)
        generator.requestedTimeToleranceBefore = kCMTimePositiveInfinity
        generator.requestedTimeToleranceAfter = kCMTimePositiveInfinity

        do {
            let cgImage = try generator.copyCGImage(at: kCMTimeZero, actualTime: nil)
            return UIImage(cgImage: cgImage)
        } catch {
            return nil
        }
    }
}
