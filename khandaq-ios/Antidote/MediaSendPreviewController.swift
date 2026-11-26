// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import MobileCoreServices
import Photos
import PhotosUI
import QuickLook
import SnapKit
import TOCropViewController
import UIKit

enum MediaSendPreviewItem {
    case image(UIImage, fileName: String?)
    // KHANDAQ (#204-B): carry the picked video's ORIGINAL name (PHPicker suggestedName /
    // PHAssetResource.originalFilename): the staged URL is a UUID temp, so lastPathComponent
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
    // Readable from outside (ideas 1): what a picker handed over is checked in the tests.
    private(set) var items: [MediaSendPreviewItem]
    /// KHANDAQ (tester 8265): the most items one send takes; "Add" picks more until there are this many. One
    /// where a send takes one item at a time, as in a group: "Add" is not shown there.
    let maxItemCount: Int
    private(set) var currentIndex = 0
    private var previewRequestToken = UUID()

    private static let previewQueue = DispatchQueue(label: "khandaq.media.preview", qos: .userInitiated)
    private static let captionMinHeight: CGFloat = 44
    private static let captionMaxHeight: CGFloat = 120

    private static let brandTeal = UIColor(red: 0.01, green: 0.61, blue: 0.49, alpha: 1)

    private let closeButton = UIButton(type: .system)
    /// KHANDAQ (tester 8265): more photos and videos for the same send, top right, where the crop button was.
    private let addButton = UIButton(type: .system)
    private let counterLabel = UILabel()
    private let imageView = UIImageView()
    private let playIconView = UILabel()
    private let captionField = UITextView()
    /// KHANDAQ (tester 8264): the pencil by the caption, in place of the smiley that only opened the keyboard
    /// (whose own emoji key stays): its menu crops and rotates a photo (TOCropViewController, parity with
    /// Android's uCrop) or draws and writes on it (MediaMarkupEditor). A video has no editing here.
    private let editButton = UIButton(type: .system)
    private let sendButton = UIButton(type: .system)
    private let bottomBar = UIView()
    /// The markup of the photo shown, while QuickLook has it.
    private var markupEditor: MediaMarkupEditor?

    private var bottomBarBottomConstraint: Constraint?
    private var bottomBarSafeInsetConstraint: Constraint?
    private var captionHeightConstraint: Constraint?
    /// KHANDAQ (ideas 1): Send hands the items over once. A second tap while the screen was going away
    /// could hand them over again.
    private var didConfirm = false
    /// KHANDAQ (tester 8265): Close was tapped; what "Add" was still loading is not wanted any more.
    private var didCancel = false

    init(items: [MediaSendPreviewItem], maxItemCount: Int = 1) {
        self.items = items
        self.maxItemCount = maxItemCount
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

        // The marks on this screen are icons, not text glyphs (redesign stage 3: no arrows, check marks
        // or triangles typed into the interface).
        let play = NSTextAttachment()
        play.image = UIImage(systemName: "play.fill", withConfiguration: UIImage.SymbolConfiguration(pointSize: 40, weight: .bold))?
            .withTintColor(UIColor.white.withAlphaComponent(0.92), renderingMode: .alwaysOriginal)
        playIconView.attributedText = NSAttributedString(attachment: play)
        playIconView.textAlignment = .center
        playIconView.isHidden = true

        // "Backward" turns with the layout in Arabic; a typed angle mark had no name for VoiceOver.
        closeButton.setImage(UIImage(systemName: "chevron.backward",
                                     withConfiguration: UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold)), for: .normal)
        closeButton.tintColor = .white
        closeButton.accessibilityLabel = String(localized: "alert_cancel")
        closeButton.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        closeButton.layer.cornerRadius = 22

        // KHANDAQ (ideas 1): Send is only the arrow by the caption. The round check at the top right
        // (Figma, 04.07) sent the same, and sat where the photo picker has its own button to finish
        // the choice: one tap too many on the picker sent the photos before a caption could be written.

        // KHANDAQ (tester 8265): a round dark "add" button, top right, as the close button is top left.
        addButton.setImage(UIImage(systemName: "plus",
                                   withConfiguration: UIImage.SymbolConfiguration(pointSize: 18, weight: .semibold)), for: .normal)
        addButton.tintColor = .white
        addButton.accessibilityLabel = String(localized: "media_send_add_more")
        addButton.accessibilityIdentifier = "media.preview.add"
        addButton.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        addButton.layer.cornerRadius = 22
        addButton.isHidden = true

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
        // KHANDAQ (accessibility): the caption field had no accessibility label. It is named by the hint
        // every shipped language already has; the field still shows no placeholder.
        captionField.accessibilityLabel = String(localized: "media_send_caption_hint")

        bottomBar.backgroundColor = UIColor(white: 0.08, alpha: 0.95)

        // KHANDAQ (tester 8264): the pencil opens its menu at once; round teal send arrow.
        editButton.setImage(UIImage(systemName: "pencil",
                                    withConfiguration: UIImage.SymbolConfiguration(pointSize: 20, weight: .regular)), for: .normal)
        editButton.tintColor = .white
        editButton.accessibilityLabel = String(localized: "chat_edit_action")
        editButton.accessibilityIdentifier = "media.preview.edit"
        editButton.menu = makeEditMenu()
        editButton.showsMenuAsPrimaryAction = true

        sendButton.setImage(AirIcon.image("arrow-up"), for: .normal)
        sendButton.tintColor = .white
        sendButton.accessibilityLabel = String(localized: "air_compose_send")
        sendButton.accessibilityIdentifier = "media.preview.send"
        sendButton.backgroundColor = Self.brandTeal
        sendButton.layer.cornerRadius = 24

        view.addSubview(imageView)
        view.addSubview(playIconView)
        view.addSubview(closeButton)
        view.addSubview(addButton)
        view.addSubview(counterLabel)
        view.addSubview(bottomBar)
        bottomBar.addSubview(editButton)
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

        addButton.snp.makeConstraints { make in
            make.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(8)
            make.trailing.equalToSuperview().offset(-8)
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

        editButton.snp.makeConstraints { make in
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
            make.leading.equalTo(editButton.snp.trailing).offset(6)
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
        sendButton.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
        addButton.addTarget(self, action: #selector(addMoreTapped), for: .touchUpInside)
    }

    /// KHANDAQ (tester 8264): what the pencil offers for a photo.
    private func makeEditMenu() -> UIMenu {
        let crop = UIAction(title: String(localized: "media_edit_crop"),
                            image: UIImage(systemName: "crop.rotate")) { [weak self] _ in
            self?.openCropEditor()
        }
        let markup = UIAction(title: String(localized: "media_edit_markup"),
                              image: UIImage(systemName: "pencil.tip.crop.circle")) { [weak self] _ in
            self?.openMarkupEditor()
        }
        return UIMenu(title: "", children: [crop, markup])
    }

    /// Crop, rotate and the aspect ratios (uCrop parity: the default toolbar has the presets, rotate and reset).
    func openCropEditor() {
        guard items.indices.contains(currentIndex), case .image(let image, _) = items[currentIndex] else {
            return
        }
        dismissKeyboard()
        let cropController = TOCropViewController(image: image)
        cropController.delegate = self
        cropController.modalPresentationStyle = .fullScreen
        present(cropController, animated: true)
    }

    /// KHANDAQ (tester 8264): drawing, text and shapes on the photo shown, in the markup of QuickLook. Its
    /// edit replaces the photo of this send; the photo it was made from, in the library, stays as it was.
    func openMarkupEditor() {
        guard items.indices.contains(currentIndex), case .image(let image, _) = items[currentIndex] else {
            return
        }
        dismissKeyboard()
        let index = currentIndex
        guard let editor = MediaMarkupEditor(image: image, onEdited: { [weak self] edited in
            self?.replaceImage(at: index, with: edited)
        }) else {
            UIAlertController.showWithTitle(String(localized: "error_failed_generic"),
                                            message: String(localized: "error_try_again_message"),
                                            retryBlock: nil)
            return
        }
        editor.onFinished = { [weak self, weak editor] in
            if let editor = editor, self?.markupEditor === editor {
                self?.markupEditor = nil
            }
        }
        markupEditor = editor
        present(editor.makePreviewController(), animated: true)
    }

    /// An edited photo in place of the one at `index`, under the same name.
    func replaceImage(at index: Int, with image: UIImage) {
        guard items.indices.contains(index), case .image(_, let fileName) = items[index] else {
            return
        }
        items[index] = .image(image, fileName: fileName)
        if index == currentIndex {
            showItem(at: index)
        }
    }

    /// KHANDAQ (tester 8265): the chat's own library picker, for as many more as this send still takes.
    @objc private func addMoreTapped() {
        let room = maxItemCount - items.count
        guard room > 0, !didConfirm, !didCancel else {
            return
        }
        dismissKeyboard()
        present(PickedMediaLoader.libraryPicker(selectionLimit: room, delegate: self), animated: true)
    }

    /// KHANDAQ (tester 8265): items picked with "Add", after those already here, and the first of them shown.
    /// Past the limit, or once Send or Close was tapped, they are left out, and a video copied for one of
    /// them is removed.
    func appendItems(_ newItems: [MediaSendPreviewItem]) {
        let room = (didConfirm || didCancel) ? 0 : max(0, maxItemCount - items.count)
        let taken = Array(newItems.prefix(room))
        for case .video(let url, _) in newItems.dropFirst(taken.count) {
            try? FileManager.default.removeItem(at: url)
        }
        guard !taken.isEmpty else {
            return
        }
        let first = items.count
        items.append(contentsOf: taken)
        showItem(at: first)
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
        addButton.isHidden = items.count >= maxItemCount

        switch items[index] {
        case .image(let image, _):
            imageView.image = image
            playIconView.isHidden = true
            editButton.isEnabled = true
        case .video(let url, _):
            playIconView.isHidden = false
            editButton.isEnabled = false
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
        didCancel = true
        dismissKeyboard()
        delegate?.mediaSendPreviewControllerDidCancel(self)
    }

    @objc private func sendTapped() {
        guard !didConfirm else {
            return
        }
        didConfirm = true
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

// KHANDAQ (iOS photo editor): TOCropViewController parity with Android's uCrop: crop/rotate/aspect ratios
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

// KHANDAQ (tester 8265): "Add" loads what was picked as the chat's Gallery does (PickedMediaLoader), with the
// banner while it takes long, unless the banner of a video being prepared is up.
extension MediaSendPreviewController: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true, completion: nil)

        guard !results.isEmpty else {
            return
        }

        PickedMediaLoader.load(results,
                               movieTypes: PickedMediaLoader.movieTypes,
                               bannerHost: VideoSendProgressOverlay.shared.isShown ? nil : self,
                               message: String(localized: "media_pick_loading")) { [weak self] loaded in
            guard let self = self else {
                // The preview is gone: what was copied for it goes too.
                for case .video(let url, _) in loaded.items {
                    try? FileManager.default.removeItem(at: url)
                }
                return
            }
            if loaded.items.isEmpty {
                UIAlertController.showWithTitle(String(localized: "error_failed_pick_media"),
                                                message: String(localized: "error_try_again_message"),
                                                retryBlock: nil)
                return
            }
            self.appendItems(loaded.items)
        }
    }
}

/**
 KHANDAQ (tester 8264): drawing, text, shapes and a signature on a photo before it is sent, in the markup of
 QuickLook (iOS 13 and later, no library). Crop and rotate stay with TOCropViewController, as the markup of
 QuickLook has no crop on every iOS the app runs on.

 The photo goes to QuickLook as a JPEG in a folder of its own in the temporary directory, which QuickLook
 overwrites when the markup is saved (or hands over as a copy when it cannot), and the edit comes back to
 the send in place of the photo. The photo in the library is never touched: the picker handed over a copy,
 and this is a copy of that copy. The folder goes when QuickLook closes.
 */
final class MediaMarkupEditor: NSObject {
    /// The photo as QuickLook marks it up.
    let fileURL: URL
    /// Called when QuickLook has closed and the folder is gone.
    var onFinished: (() -> Void)?

    private let directory: URL
    private let onEdited: (UIImage) -> Void

    /// `onEdited` gets each edit QuickLook saves, on the main queue. Nil when the photo could not be written.
    init?(image: UIImage, onEdited: @escaping (UIImage) -> Void) {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("media-markup-\(UUID().uuidString)", isDirectory: true)
        let fileURL = directory.appendingPathComponent("photo.jpg")
        guard let data = UIImageJPEGRepresentation(image, 0.95) else {
            return nil
        }
        do {
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
            try data.write(to: fileURL, options: .atomic)
        }
        catch {
            try? FileManager.default.removeItem(at: directory)
            return nil
        }
        self.directory = directory
        self.fileURL = fileURL
        self.onEdited = onEdited
        super.init()
    }

    deinit {
        try? FileManager.default.removeItem(at: directory)
    }

    func makePreviewController() -> QLPreviewController {
        let controller = QLPreviewController()
        controller.dataSource = self
        controller.delegate = self
        return controller
    }

    /// The edit at `url`, read at once: QuickLook may remove a copy as soon as its call returns.
    private func takeEdit(at url: URL) {
        guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else {
            return
        }
        if Thread.isMainThread {
            onEdited(image)
        }
        else {
            DispatchQueue.main.async { [onEdited] in
                onEdited(image)
            }
        }
    }

    private func finish() {
        try? FileManager.default.removeItem(at: directory)
        onFinished?()
    }
}

/// The photo as QuickLook shows it: titled by what is done to it, not by the name of the temporary copy.
private final class MarkupItem: NSObject, QLPreviewItem {
    let previewItemURL: URL?
    let previewItemTitle: String? = String(localized: "media_edit_markup")

    init(url: URL) {
        previewItemURL = url
        super.init()
    }
}

extension MediaMarkupEditor: QLPreviewControllerDataSource, QLPreviewControllerDelegate {
    func numberOfPreviewItems(in controller: QLPreviewController) -> Int {
        return 1
    }

    func previewController(_ controller: QLPreviewController, previewItemAt index: Int) -> QLPreviewItem {
        return MarkupItem(url: fileURL)
    }

    func previewController(_ controller: QLPreviewController, editingModeFor previewItem: QLPreviewItem) -> QLPreviewItemEditingMode {
        return .updateContents
    }

    func previewController(_ controller: QLPreviewController, didUpdateContentsOf previewItem: QLPreviewItem) {
        takeEdit(at: fileURL)
    }

    func previewController(_ controller: QLPreviewController, didSaveEditedCopyOf previewItem: QLPreviewItem, at modifiedContentsURL: URL) {
        takeEdit(at: modifiedContentsURL)
    }

    func previewControllerDidDismiss(_ controller: QLPreviewController) {
        finish()
    }
}
