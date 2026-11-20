// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation
import MobileCoreServices
import Photos
import PhotosUI
import UIKit

/// KHANDAQ (large media): photos are prepared for a group (scaled, encoded as JPEG) here, one after
/// another, off the main queue.
fileprivate let groupImageEncodeQueue = DispatchQueue(label: "khandaq.group.image.encode", qos: .userInitiated)

class ChatGroupInputViewManager: NSObject {
    fileprivate var chat: OCTChat!
    fileprivate weak var inputView: ChatInputView?
    fileprivate weak var submanagerGroups: OCTSubmanagerGroups!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate weak var presentingViewController: UIViewController!
    /// KHANDAQ (review 0.2.56): the video this group prepares, by its banner and its preparation. The
    /// preprocessor and the banner serve every chat: this one stops and takes down only its own.
    fileprivate var videoSend: (banner: Int, preparation: Int)?
    fileprivate var isVideoSendInProgress: Bool {
        return videoSend != nil
    }
    fileprivate let voiceRecorder = GroupVoiceMessageRecorder()
    fileprivate var voiceHold = VoiceHoldGate()

    var outgoingTextComposer: ((String) -> String)?

    init(inputView: ChatInputView,
         chat: OCTChat,
         submanagerGroups: OCTSubmanagerGroups,
         submanagerObjects: OCTSubmanagerObjects,
         presentingViewController: UIViewController) {

        self.chat = chat
        self.inputView = inputView
        self.submanagerGroups = submanagerGroups
        self.submanagerObjects = submanagerObjects
        self.presentingViewController = presentingViewController

        super.init()

        inputView.delegate = self
        inputView.text = chat.enteredText ?? ""
        inputView.cameraButtonEnabled = true
        inputView.voiceButtonEnabled = true
        // KHANDAQ (0.2.60): a pasted photo goes through the preview, as one from the library: one at a time, as
        // the library gives a group.
        inputView.onPasteImages = { [weak self] images in
            self?.presentMediaPreview(items: images.prefix(1).map { .image($0, fileName: nil) })
        }
    }
}

extension ChatGroupInputViewManager: ChatInputViewDelegate {
    func chatInputViewVoiceRecordDidStart(_ view: ChatInputView) {
        // Stage 5: with the microphone denied the hold sent nothing, without a word; it says why and
        // offers Settings, as a call does (E9, E21). The first hold asks iOS, and the answer comes after
        // the hold: the recording starts only while the hold is on (VoiceHoldGate).
        let hold = voiceHold.begin()
        MediaPermission.requestMicrophoneAccess(from: presentingViewController) { [weak self] granted in
            guard granted, let self = self, self.voiceHold.mayRecord(for: hold) else {
                return
            }

            do {
                try self.voiceRecorder.startRecording()
            }
            catch {
                handleErrorWithType(.sendFileToFriend, error: error as NSError)
            }
        }
    }

    func chatInputViewVoiceRecordDidEnd(_ view: ChatInputView, cancelled: Bool) {
        voiceHold.end()
        if cancelled {
            voiceRecorder.cancelRecording()
            return
        }
        // KHANDAQ (#voice-iphone11): send only the FINALIZED recording (delegate callback).
        voiceRecorder.finishRecording { [weak self] url in
            guard let self = self, let url = url else {
                return
            }
            self.sendFileAtPath(url.path)
        }
    }

    func chatInputViewVoiceButtonTapped(_ view: ChatInputView) {
        UIAlertController.showWithTitle("",
                                        message: String(localized: "group_voice_hold_to_record"),
                                        retryBlock: nil)
    }

    func chatInputViewCameraButtonPressed(_ view: ChatInputView, cameraView: UIView) {
        // KHANDAQ (redesign stage 3, E8): the size a group takes is said before a file is chosen, not
        // only in the refusal after it.
        let alert = UIAlertController(title: nil, message: AirFileLimits.groupHint, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.sourceView = cameraView
        alert.popoverPresentationController?.sourceRect = CGRect(x: cameraView.frame.size.width / 2,
                                                                 y: cameraView.frame.size.height / 2,
                                                                 width: 1.0,
                                                                 height: 1.0)

        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            alert.addAction(UIAlertAction(title: String(localized: "photo_from_camera"), style: .default) { [unowned self] _ in
                MediaPermission.requestCameraAccess(from: self.presentingViewController) { granted in
                    guard granted else {
                        return
                    }

                    let controller = UIImagePickerController()
                    controller.delegate = self
                    controller.sourceType = .camera
                    controller.mediaTypes = [kUTTypeImage as String, kUTTypeMovie as String]
                    controller.videoQuality = .typeMedium
                    self.presentingViewController.present(controller, animated: true, completion: nil)
                }
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "photo_from_photo_library"), style: .default) { [unowned self] _ in
            self.presentPhotoLibraryPicker()
        })

        alert.addAction(UIAlertAction(title: String(localized: "group_file_attach"), style: .default) { [unowned self] _ in
            self.presentDocumentPicker()
        })

        alert.addAction(UIAlertAction(title: String(localized: "group_share_location"), style: .default) { [unowned self] _ in
            LocationSharingCoordinator.shared.shareCurrentLocationOnce(to: self)
        })

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        presentingViewController.present(alert, animated: true, completion: nil)
    }

    func chatInputViewSendButtonPressed(_ view: ChatInputView) {
        let text = view.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return
        }

        let outgoing = outgoingTextComposer?(text) ?? text

        // KHANDAQ (long texts): toxcore refuses a group message longer than 1372 bytes, so a longer text
        // goes as several, in order.
        sendParts(AirComposeLimit.parts(ofComposed: outgoing, body: text, maxBytes: AirGroupComposeLimit.bytes),
                  from: 0,
                  clearing: view)
    }

    /// Sends `parts` from `index` on, one after another. The field is cleared once the first is taken;
    /// a part refused after that puts what was not sent back into it.
    private func sendParts(_ parts: [String], from index: Int, clearing view: ChatInputView) {
        guard parts.indices.contains(index) else {
            return
        }
        submanagerGroups.sendMessage(to: chat, text: parts[index], type: .normal, successBlock: { _ in
            DispatchQueue.main.async {
                if index == 0 {
                    view.text = ""
                    self.saveEnteredText()
                }
                self.sendParts(parts, from: index + 1, clearing: view)
            }
        }, failureBlock: { error in
            DispatchQueue.main.async {
                if index > 0 {
                    view.text = parts[index...].joined(separator: "\n")
                    self.saveEnteredText()
                }
                // E21: without an error the words still say what failed.
                handleErrorWithType(.sendMessageToFriend, error: error as NSError?)
            }
        })
    }

    func chatInputViewTextDidChange(_ view: ChatInputView) {
        saveEnteredText()
    }
}

extension ChatGroupInputViewManager: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [String: Any]) {
        picker.dismiss(animated: true, completion: nil)

        let mediaType = info[UIImagePickerControllerMediaType] as? String ?? kUTTypeImage as String

        if isMovieMediaType(mediaType) {
            sendMovie(imagePickerInfo: info)
            return
        }

        if let image = (info[UIImagePickerControllerEditedImage] ?? info[UIImagePickerControllerOriginalImage]) as? UIImage {
            presentMediaPreview(items: [.image(image, fileName: nil)])
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true, completion: nil)
    }
}

extension ChatGroupInputViewManager: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            return
        }

        let accessed = url.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                url.stopAccessingSecurityScopedResource()
            }
        }

        // KHANDAQ (ideas 1): it leaves on Send under its name and size (AirPickedFile).
        guard let source = (inputView as UIView?) ?? presentingViewController?.view else {
            return
        }
        let sheet = AirPickedFile.confirmation(for: url, source: source) { [weak self] in
            self?.sendPickedFile(at: url)
        }
        presentingViewController.present(sheet, animated: true, completion: nil)
    }

    /// The file picked with File, once Send was tapped under its name.
    private func sendPickedFile(at url: URL) {
        // The group may have been left while the question was up.
        guard submanagerGroups != nil, !chat.isInvalidated else {
            return
        }
        let accessed = url.startAccessingSecurityScopedResource()
        defer {
            if accessed {
                url.stopAccessingSecurityScopedResource()
            }
        }

        if isVideoFileURL(url) {
            enqueueVideoSend(from: url)
            return
        }

        sendFileAtPath(url.path)
    }
}

@available(iOS 14.0, *)
extension ChatGroupInputViewManager: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        picker.dismiss(animated: true, completion: nil)

        guard let result = results.first else {
            return
        }

        // KHANDAQ (large media): the banner while it loads, unless a video is being prepared and its
        // banner is up. The picker's temporary URL is only valid until the provider's completion
        // returns, so the loader copies a video into app storage inside it (PickedMediaLoader).
        PickedMediaLoader.load([result],
                               movieTypes: movieTypeIdentifiers(),
                               bannerHost: isVideoSendInProgress ? nil : presentingViewController,
                               message: String(localized: "media_pick_loading")) { [weak self] loaded in
            guard let self = self else {
                return
            }
            if !loaded.items.isEmpty {
                self.presentMediaPreview(items: loaded.items)
            }
            else if let error = loaded.stagingError {
                self.showVideoSendError(error, retryURL: nil)
            }
        }
    }
}

extension ChatGroupInputViewManager: MediaSendPreviewControllerDelegate {
    func mediaSendPreviewControllerDidCancel(_ controller: MediaSendPreviewController) {
        controller.dismiss(animated: true, completion: nil)
    }

    func mediaSendPreviewController(_ controller: MediaSendPreviewController,
                                    didConfirm items: [MediaSendPreviewItem],
                                    caption: String) {
        controller.dismiss(animated: true) { [weak self] in
            self?.sendConfirmedPreviewItems(items, caption: caption)
        }
    }

    func presentMediaPreview(items: [MediaSendPreviewItem]) {
        presentingViewController.view.endEditing(true)
        let controller = MediaSendPreviewController(items: items)
        controller.delegate = self
        presentingViewController.present(controller, animated: true, completion: nil)
    }

    func sendConfirmedPreviewItems(_ items: [MediaSendPreviewItem], caption: String) {
        let trimmedCaption = caption.trimmingCharacters(in: .whitespacesAndNewlines)

        // The caption rides along with the LAST media item and is sent from that file's send-success
        // callback (so it always lands right after the file message — reliable even for async video).
        for (index, item) in items.enumerated() {
            let itemCaption = (index == items.count - 1) ? trimmedCaption : ""
            switch item {
            case .image(let image, _):
                sendImageData(image, caption: itemCaption)
            case .video(let url, _):
                enqueueVideoSend(from: url, caption: itemCaption)
            }
        }
    }

    // Sends the caption text right after the media and flags it so it renders merged into the bubble.
    // A caption longer than one message goes as several: the first is the caption, the rest follow it.
    func sendCaptionMessage(_ text: String) {
        let outgoing = outgoingTextComposer?(text) ?? text
        sendCaptionParts(AirComposeLimit.parts(ofComposed: outgoing, body: text, maxBytes: AirGroupComposeLimit.bytes), from: 0)
    }

    private func sendCaptionParts(_ parts: [String], from index: Int) {
        guard parts.indices.contains(index) else {
            return
        }
        submanagerGroups.sendMessage(to: chat, text: parts[index], type: .normal, successBlock: { [weak self] message in
            if index == 0, let message = message {
                self?.submanagerObjects.markMessage(asCaption: message)
            }
            DispatchQueue.main.async {
                self?.sendCaptionParts(parts, from: index + 1)
            }
        }, failureBlock: { error in
            DispatchQueue.main.async {
                if let error = error as NSError? {
                    handleErrorWithType(.sendMessageToFriend, error: error)
                }
            }
        })
    }
}

private extension ChatGroupInputViewManager {
    func presentPhotoLibraryPicker() {
        if #available(iOS 14.0, *) {
            var configuration = PHPickerConfiguration()
            configuration.filter = .any(of: [.images, .videos])
            configuration.selectionLimit = 1
            // KHANDAQ (large media): the file as it is in the library, as in a chat with a person: the
            // app transcodes a large video itself (VideoSendPreprocessor) and prepares every photo for
            // the group (GroupImagePreparer), so a transcode by the system first is only a long wait.
            configuration.preferredAssetRepresentationMode = .current

            let picker = PHPickerViewController(configuration: configuration)
            picker.delegate = self
            presentingViewController.present(picker, animated: true, completion: nil)
            return
        }

        let controller = UIImagePickerController()
        controller.delegate = self
        controller.sourceType = .photoLibrary
        controller.mediaTypes = [kUTTypeImage as String, kUTTypeMovie as String]
        controller.videoQuality = .typeMedium
        presentingViewController.present(controller, animated: true, completion: nil)
    }

    func presentDocumentPicker() {
        let types = [kUTTypeItem as String]
        let controller = UIDocumentPickerViewController(documentTypes: types, in: .import)
        controller.delegate = self
        presentingViewController.present(controller, animated: true, completion: nil)
    }

    func sendFileData(_ data: Data, fileName: String, caption: String = "") {
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        do {
            try data.write(to: tempURL)
            sendFileAtPath(tempURL.path, caption: caption)
        }
        catch {
            handleErrorWithType(.sendFileToFriend, error: error as NSError)
        }
    }

    func sendImageData(_ image: UIImage, caption: String = "") {
        // KHANDAQ (large media): scaling and encoding a full-size photo takes a noticeable moment; it
        // runs off the main queue, and the file goes out from the main queue as before.
        groupImageEncodeQueue.async {
            let data = autoreleasepool {
                GroupImagePreparer.jpegDataForGroupUpload(from: image)
            }
            DispatchQueue.main.async {
                guard self.submanagerGroups != nil, !self.chat.isInvalidated else {
                    return
                }
                guard let data = data else {
                    UIAlertController.showErrorWithMessage(String(localized: "group_file_send_failed"), retryBlock: nil)
                    return
                }

                // KHANDAQ (#47): unique per-send name. A fixed "photo.jpg" collided across sends (the temp
                // write path, and any name-keyed bookkeeping), which let a later photo appear in an earlier
                // photo's message. Videos already use a unique name and never swapped — mirror that.
                self.sendFileData(data, fileName: "photo_\(UUID().uuidString.prefix(8)).jpg", caption: caption)
            }
        }
    }

    func sendMovie(imagePickerInfo: [String: Any]) {
        if let url = imagePickerInfo[UIImagePickerControllerMediaURL] as? URL {
            // KHANDAQ (ideas 1): to the preview as in a chat with one person (ChatInputViewManager
            // .loadPreviewItems): copied into the app's storage first, off the main queue, as the file is
            // the camera's. The preview had been handed the camera's own file, and a video too large to
            // prepare was refused only after Send.
            let originalName = url.lastPathComponent
            DispatchQueue.global(qos: .userInitiated).async {
                let staged = Result { try VideoSendPreprocessor.shared.stagePickerVideo(at: url) }
                DispatchQueue.main.async { [weak self] in
                    switch staged {
                    case .success(let stagedURL):
                        self?.presentMediaPreview(items: [.video(stagedURL, fileName: originalName)])
                    case .failure(let error):
                        self?.showVideoSendError(error, retryURL: nil)
                    }
                }
            }
            return
        }

        if let asset = imagePickerInfo[UIImagePickerControllerPHAsset] as? PHAsset, asset.mediaType == .video {
            sendMovieFromPHAsset(asset)
            return
        }

        UIAlertController.showErrorWithMessage(String(localized: "group_file_send_failed"), retryBlock: nil)
    }

    func sendMovieFromPHAsset(_ asset: PHAsset) {
        guard let resource = PHAssetResource.assetResources(for: asset).first(where: { $0.type == .video || $0.type == .fullSizeVideo }) else {
            UIAlertController.showErrorWithMessage(String(localized: "group_file_send_failed"), retryBlock: nil)
            return
        }

        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".mov")
        let originalName = resource.originalFilename

        PHAssetResourceManager.default().writeData(for: resource, toFile: tempURL, options: nil) { [weak self] error in
            DispatchQueue.main.async {
                guard let self = self else {
                    return
                }

                if let error = error {
                    self.showVideoSendError(error, retryURL: nil)
                    return
                }

                self.presentMediaPreview(items: [.video(tempURL, fileName: originalName)])
            }
        }
    }

    /// `completion` runs on the main queue once the video went, failed or was cancelled (true).
    func enqueueVideoSend(from sourceURL: URL, caption: String = "", completion: ((_ cancelled: Bool) -> Void)? = nil) {
        guard !isVideoSendInProgress else {
            showVideoSendError(VideoSendError.busy, retryURL: sourceURL)
            completion?(false)
            return
        }

        // Read only after show() returned, by Cancel, the progress and the end of the job: by then it
        // holds the banner's number.
        var banner = 0
        banner = VideoSendProgressOverlay.shared.show(on: presentingViewController,
                                                      message: String(localized: "video_send_preparing"),
                                                      onCancel: { [weak self] in
            self?.cancelVideoSend(banner: banner)
        })

        let preparation = VideoSendPreprocessor.shared.prepareVideo(at: sourceURL, progress: { progress in
            VideoSendProgressOverlay.shared.update(progress: progress, banner: banner)
        }, completion: { [weak self] result in
            DispatchQueue.main.async {
                VideoSendProgressOverlay.shared.hide(banner: banner)
                // Over, unless Cancel let another video of this group start already.
                if self?.videoSend?.banner == banner {
                    self?.videoSend = nil
                }

                // KHANDAQ (large media): a cancelled preparation has nothing to send, and a cancelled
                // video ends its sequence.
                if case .failure(let error) = result, VideoSendError.isCancellation(error) {
                    completion?(true)
                    return
                }

                guard let self = self else {
                    completion?(false)
                    return
                }

                switch result {
                case .success(let preparedURL):
                    self.sendPreparedVideo(at: preparedURL, caption: caption)
                case .failure(let error):
                    self.showVideoSendError(error, retryURL: sourceURL)
                }

                completion?(false)
            }
        })
        videoSend = (banner: banner, preparation: preparation)
    }

    /// Stops the video this group prepares and takes its banner down, so that another one can start at
    /// once; with `banner`, only while that banner's video is the one in progress.
    func cancelVideoSend(banner: Int? = nil) {
        guard let videoSend = videoSend, banner == nil || banner == videoSend.banner else {
            return
        }
        self.videoSend = nil
        VideoSendPreprocessor.shared.cancelPreparation(videoSend.preparation)
        VideoSendProgressOverlay.shared.hide(banner: videoSend.banner)
    }

    func sendPreparedVideo(at url: URL, caption: String = "") {
        sendFileAtPath(url.path, caption: caption)
    }

    func showVideoSendError(_ error: Error, retryURL: URL?) {
        let message = (error as? LocalizedError)?.errorDescription ?? String(localized: "video_send_preparation_failed")
        let retryBlock: (() -> Void)? = retryURL.map { url in
            { [weak self] in
                self?.enqueueVideoSend(from: url)
            }
        }

        UIAlertController.showErrorWithMessage(message, retryBlock: retryBlock)
    }

    func sendFileAtPath(_ filePath: String, caption: String = "") {
        submanagerGroups.sendFile(atPath: filePath, to: chat, moveToUploads: true, successBlock: { [weak self] _ in
            // The file message now exists, so the caption sent here lands right after it and merges
            // into its bubble (reliable for async video, not just synchronous photos).
            let trimmed = caption.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                DispatchQueue.main.async {
                    self?.sendCaptionMessage(trimmed)
                }
            }
        }, failureBlock: { error in
            DispatchQueue.main.async {
                guard let error = error as NSError? else {
                    UIAlertController.showErrorWithMessage(String(localized: "group_file_send_failed"), retryBlock: nil)
                    return
                }

                if error.domain == "OCTNgcGroupFileTransferErrorDomain", error.code == 1 {
                    UIAlertController.showErrorWithMessage(String(localized: "group_file_too_large"), retryBlock: nil)
                    return
                }

                if let customError = OCTToxErrorGroupSendCustomPacket(rawValue: error.code) {
                    let (title, message) = customError.strings()
                    UIAlertController.showWithTitle(title, message: message, retryBlock: nil)
                    return
                }

                handleErrorWithType(.sendFileToFriend, error: error)
            }
        })
    }

    func isMovieMediaType(_ type: String) -> Bool {
        let cfType = type as CFString
        return UTTypeConformsTo(cfType, kUTTypeMovie)
            || UTTypeConformsTo(cfType, kUTTypeVideo)
            || UTTypeConformsTo(cfType, kUTTypeMPEG4)
    }

    func movieTypeIdentifiers() -> [String] {
        [kUTTypeMovie as String, kUTTypeMPEG4 as String, kUTTypeVideo as String]
    }

    func isVideoFileURL(_ url: URL) -> Bool {
        let ext = url.pathExtension.lowercased()
        let videoExtensions = ["mp4", "mov", "m4v", "avi", "mkv", "webm", "3gp"]
        return videoExtensions.contains(ext)
    }

    func requestMicrophoneAccess(completion: @escaping (Bool) -> Void) {
        let session = AVAudioSession.sharedInstance()
        let permission = session.recordPermission()

        if permission == .granted {
            completion(true)
            return
        }

        if permission == .denied {
            completion(false)
            return
        }

        session.requestRecordPermission { granted in
            DispatchQueue.main.async {
                completion(granted)
            }
        }
    }

    func saveEnteredText() {
        let text = inputView?.text ?? ""
        submanagerObjects.change(chat, enteredText: text.isEmpty ? nil : text)
    }
}

// KHANDAQ: send a one-shot location into the group (parity with 1:1 + Android).
extension ChatGroupInputViewManager: LocationSharingChat {
    func sendLocationMessage(_ payload: String) {
        submanagerGroups.sendMessage(to: chat, text: payload, type: .normal, successBlock: { _ in }, failureBlock: { error in
            DispatchQueue.main.async {
                if let error = error as NSError? {
                    handleErrorWithType(.sendMessageToFriend, error: error)
                }
            }
        })
    }
}
