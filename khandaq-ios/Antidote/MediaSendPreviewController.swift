// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import MobileCoreServices
import Photos
import SnapKit
import TOCropViewController
import UIKit

enum MediaSendPreviewItem {
    case image(UIImage, fileName: String?)
    // KHANDAQ (#204-B): carry the picked video's ORIGINAL name (PHPicker suggestedName /
    // PHAssetResource.originalFilename) — the staged URL is a UUID temp, so lastPathComponent
    // would surface a UUID in Saved Messages instead of the real filename.
    case video(URL, fileName: String?)
}

protocol MediaSendPreviewControllerDelegate: AnyObject {
    func mediaSendPreviewControllerDidCancel(_ controller: MediaSendPreviewController)
    func mediaSendPreviewController(_ controller: MediaSendPreviewController,
                                    didConfirm items: [MediaSendPreviewItem],
                                    caption: String)
}

final class MediaSendPreviewController: KeyboardNotificationController, UITextViewDelegate {
    weak var delegate: MediaSendPreviewControllerDelegate?

    // KHANDAQ (iOS photo editor): `var` so the crop editor can write the edited image back in place.
    private var items: [MediaSendPreviewItem]
    private var currentIndex = 0
    private var previewRequestToken = UUID()

    private static let previewQueue = DispatchQueue(label: "khandaq.media.preview", qos: .userInitiated)
    private static let captionMinHeight: CGFloat = 44
    private static let captionMaxHeight: CGFloat = 120

    private static let brandTeal = UIColor(red: 0.01, green: 0.61, blue: 0.49, alpha: 1)

    private let closeButton = UIButton(type: .system)
    private let checkButton = UIButton(type: .system)
    // KHANDAQ (iOS photo editor): crop/rotate/aspect-ratio editor (TOCropViewController) — parity with
    // Android's uCrop. Shown only for photos.
    private let editButton = UIButton(type: .system)
    private let counterLabel = UILabel()
    private let imageView = UIImageView()
    private let playIconView = UILabel()
    private let captionField = UITextView()
    private let emojiButton = UIButton(type: .system)
    private let sendButton = UIButton(type: .system)
    private let bottomBar = UIView()

    private var bottomBarBottomConstraint: Constraint?
    private var bottomBarSafeInsetConstraint: Constraint?
    private var captionHeightConstraint: Constraint?

    init(items: [MediaSendPreviewItem]) {
        self.items = items
        super.init()
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

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        updateBottomBarSafeInset()
    }

    override func keyboardWillShowAnimated(keyboardFrame frame: CGRect) {
        let keyboardFrameInView = view.convert(frame, from: view.window)
        let overlap = max(0, view.bounds.maxY - keyboardFrameInView.minY)
        bottomBarBottomConstraint?.update(offset: -overlap)
    }

    override func keyboardWillHideAnimated(keyboardFrame frame: CGRect) {
        bottomBarBottomConstraint?.update(offset: 0)
    }

    private func installViews() {
        imageView.contentMode = .scaleAspectFit
        imageView.clipsToBounds = true
        imageView.isUserInteractionEnabled = true

        playIconView.text = "▶"
        playIconView.font = .systemFont(ofSize: 48, weight: .bold)
        playIconView.textColor = UIColor.white.withAlphaComponent(0.92)
        playIconView.textAlignment = .center
        playIconView.isHidden = true

        closeButton.setTitle("‹", for: .normal)
        closeButton.setTitleColor(.white, for: .normal)
        closeButton.titleLabel?.font = .systemFont(ofSize: 30, weight: .medium)
        closeButton.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        closeButton.layer.cornerRadius = 22

        // KHANDAQ (Figma): round teal confirm-check, top-right.
        checkButton.setTitle("✓", for: .normal)
        checkButton.setTitleColor(.white, for: .normal)
        checkButton.titleLabel?.font = .systemFont(ofSize: 22, weight: .bold)
        checkButton.backgroundColor = Self.brandTeal
        checkButton.layer.cornerRadius = 22

        // KHANDAQ (iOS photo editor): round dark "crop" button, top-right next to the confirm check.
        if #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 18, weight: .medium)
            editButton.setImage(UIImage(systemName: "crop.rotate", withConfiguration: config), for: .normal)
            editButton.tintColor = .white
        }
        else {
            editButton.setTitle("✂", for: .normal)
            editButton.setTitleColor(.white, for: .normal)
            editButton.titleLabel?.font = .systemFont(ofSize: 20, weight: .medium)
        }
        editButton.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        editButton.layer.cornerRadius = 22
        editButton.isHidden = true

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
        captionField.isScrollEnabled = true
        captionField.delegate = self
        captionField.keyboardAppearance = .dark
        captionField.returnKeyType = .done
        captionField.inputAccessoryView = makeCaptionKeyboardToolbar()

        bottomBar.backgroundColor = UIColor(white: 0.08, alpha: 0.95)

        // KHANDAQ (Figma): emoji icon focuses the caption; round teal send arrow.
        emojiButton.setTitle("😊", for: .normal)
        emojiButton.titleLabel?.font = .systemFont(ofSize: 24)

        sendButton.setTitle("↑", for: .normal)
        sendButton.setTitleColor(.white, for: .normal)
        sendButton.titleLabel?.font = .systemFont(ofSize: 24, weight: .bold)
        sendButton.backgroundColor = Self.brandTeal
        sendButton.layer.cornerRadius = 24

        view.addSubview(imageView)
        view.addSubview(playIconView)
        view.addSubview(closeButton)
        view.addSubview(checkButton)
        view.addSubview(editButton)
        view.addSubview(counterLabel)
        view.addSubview(bottomBar)
        bottomBar.addSubview(emojiButton)
        bottomBar.addSubview(captionField)
        bottomBar.addSubview(sendButton)

        let swipeLeft = UISwipeGestureRecognizer(target: self, action: #selector(showNextItem))
        swipeLeft.direction = .left
        imageView.addGestureRecognizer(swipeLeft)

        let swipeRight = UISwipeGestureRecognizer(target: self, action: #selector(showPreviousItem))
        swipeRight.direction = .right
        imageView.addGestureRecognizer(swipeRight)

        let tapToDismiss = UITapGestureRecognizer(target: self, action: #selector(dismissKeyboard))
        tapToDismiss.cancelsTouchesInView = false
        imageView.addGestureRecognizer(tapToDismiss)

        let swipeDown = UISwipeGestureRecognizer(target: self, action: #selector(dismissKeyboard))
        swipeDown.direction = .down
        bottomBar.addGestureRecognizer(swipeDown)
    }

    private func installConstraints() {
        closeButton.snp.makeConstraints { make in
            make.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(8)
            make.leading.equalToSuperview().offset(8)
            make.width.height.equalTo(44)
        }

        checkButton.snp.makeConstraints { make in
            make.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(8)
            make.trailing.equalToSuperview().offset(-8)
            make.width.height.equalTo(44)
        }

        editButton.snp.makeConstraints { make in
            make.centerY.equalTo(checkButton)
            make.trailing.equalTo(checkButton.snp.leading).offset(-10)
            make.width.height.equalTo(44)
        }

        counterLabel.snp.makeConstraints { make in
            make.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(16)
            make.centerX.equalToSuperview()
            make.height.equalTo(24)
            make.width.greaterThanOrEqualTo(56)
        }

        bottomBar.snp.makeConstraints { make in
            make.leading.trailing.equalToSuperview()
            bottomBarBottomConstraint = make.bottom.equalToSuperview().constraint
        }

        emojiButton.snp.makeConstraints { make in
            make.leading.equalToSuperview().offset(6)
            make.bottom.equalTo(captionField.snp.bottom)
            make.width.height.equalTo(44)
        }

        sendButton.snp.makeConstraints { make in
            make.trailing.equalToSuperview().offset(-8)
            make.bottom.equalTo(captionField.snp.bottom)
            make.width.height.equalTo(48)
        }

        captionField.snp.makeConstraints { make in
            make.top.equalToSuperview().offset(12)
            make.leading.equalTo(emojiButton.snp.trailing).offset(6)
            make.trailing.equalTo(sendButton.snp.leading).offset(-8)
            captionHeightConstraint = make.height.equalTo(Self.captionMinHeight).constraint
            bottomBarSafeInsetConstraint = make.bottom.equalToSuperview().offset(-12).constraint
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

        updateBottomBarSafeInset()
    }

    private func wireActions() {
        closeButton.addTarget(self, action: #selector(cancelTapped), for: .touchUpInside)
        checkButton.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
        sendButton.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
        emojiButton.addTarget(self, action: #selector(emojiTapped), for: .touchUpInside)
        editButton.addTarget(self, action: #selector(editTapped), for: .touchUpInside)
    }

    @objc private func editTapped() {
        guard case .image(let image, _) = items[currentIndex] else {
            return
        }
        dismissKeyboard()
        let cropController = TOCropViewController(image: image)
        cropController.delegate = self
        cropController.modalPresentationStyle = .fullScreen
        // uCrop parity: default toolbar exposes aspect-ratio presets + rotate + reset.
        present(cropController, animated: true)
    }

    @objc private func emojiTapped() {
        captionField.becomeFirstResponder()
    }

    private func makeCaptionKeyboardToolbar() -> UIToolbar {
        let toolbar = UIToolbar()
        toolbar.barStyle = .black
        toolbar.isTranslucent = true
        toolbar.sizeToFit()

        let flex = UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: nil, action: nil)
        let done = UIBarButtonItem(title: String(localized: "change_password_done"),
                                   style: .done,
                                   target: self,
                                   action: #selector(dismissKeyboard))
        done.tintColor = .white
        toolbar.items = [flex, done]
        return toolbar
    }

    private func updateBottomBarSafeInset() {
        bottomBarSafeInsetConstraint?.update(offset: -(12 + view.safeAreaInsets.bottom))
    }

    private func updateCaptionHeight() {
        let width = captionField.bounds.width > 0 ? captionField.bounds.width : view.bounds.width - 24
        let fittingSize = CGSize(width: width, height: .greatestFiniteMagnitude)
        let measured = captionField.sizeThatFits(fittingSize).height
        let clamped = min(max(measured, Self.captionMinHeight), Self.captionMaxHeight)
        captionHeightConstraint?.update(offset: clamped)
        captionField.isScrollEnabled = measured > Self.captionMaxHeight
    }

    func textViewDidChange(_ textView: UITextView) {
        updateCaptionHeight()
        view.layoutIfNeeded()
    }

    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
        if text == "\n" {
            dismissKeyboard()
            return false
        }
        return true
    }

    private func showItem(at index: Int) {
        guard items.indices.contains(index) else {
            return
        }

        currentIndex = index
        let requestToken = UUID()
        previewRequestToken = requestToken

        counterLabel.text = String(localized: "media_send_counter_format",
                                   index + 1,
                                   items.count)
        counterLabel.isHidden = items.count <= 1

        switch items[index] {
        case .image(let image, _):
            imageView.image = image
            playIconView.isHidden = true
            editButton.isHidden = false
        case .video(let url, _):
            playIconView.isHidden = false
            editButton.isHidden = true
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

    @objc private func dismissKeyboard() {
        view.endEditing(true)
    }

    @objc private func cancelTapped() {
        dismissKeyboard()
        delegate?.mediaSendPreviewControllerDidCancel(self)
    }

    @objc private func sendTapped() {
        dismissKeyboard()
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

// KHANDAQ (iOS photo editor): TOCropViewController parity with Android's uCrop — crop/rotate/aspect ratios
// on a photo before sending. The edited image is written back into the (now var) items array and flows out
// through the existing confirm path, so both 1:1 and group send get it with no send-path changes.
extension MediaSendPreviewController: TOCropViewControllerDelegate {
    func cropViewController(_ cropViewController: TOCropViewController,
                            didCropTo image: UIImage,
                            with cropRect: CGRect,
                            angle: Int) {
        if case .image(_, let fileName) = items[currentIndex] {
            items[currentIndex] = .image(image, fileName: fileName)
        }
        cropViewController.dismiss(animated: true) { [weak self] in
            guard let self = self else {
                return
            }
            self.showItem(at: self.currentIndex)
        }
    }

    func cropViewController(_ cropViewController: TOCropViewController, didFinishCancelled cancelled: Bool) {
        cropViewController.dismiss(animated: true)
    }
}
