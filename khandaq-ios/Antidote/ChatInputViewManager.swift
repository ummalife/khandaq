// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation
import MobileCoreServices
import Photos
import PhotosUI
import UIKit
import os

fileprivate struct Constants {
    static let inactivityTimeout = 4.0
}

fileprivate let videoSendQueue = DispatchQueue(label: "khandaq.video.send", qos: .userInitiated)

/**
    Manager responsible for sending messages and files, updating typing notification,
    saving entered text in database.
 */
class ChatInputViewManager: NSObject {
    fileprivate var chat: OCTChat!
    fileprivate weak var inputView: ChatInputView?
    fileprivate let theme: Theme

    fileprivate weak var submanagerChats: OCTSubmanagerChats!
    fileprivate weak var submanagerFiles: OCTSubmanagerFiles!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    fileprivate weak var presentingViewController: UIViewController!

    fileprivate var inactivityTimer: Timer?
    fileprivate var isVideoSendInProgress = false
    fileprivate let voiceRecorder = GroupVoiceMessageRecorder()

    var outgoingTextComposer: ((String) -> String)?

    // KHANDAQ design (Figma): set by the chat controller to share the current location once from the
    // "+" attachment menu. When nil (e.g. groups), the Геолокация item is hidden.
    var onShareLocation: (() -> Void)?

    init(inputView: ChatInputView,
         theme: Theme,
         chat: OCTChat,
         submanagerChats: OCTSubmanagerChats,
         submanagerFiles: OCTSubmanagerFiles,
         submanagerObjects: OCTSubmanagerObjects,
         presentingViewController: UIViewController) {

        self.chat = chat
        self.theme = theme
        self.inputView = inputView
        self.submanagerChats = submanagerChats
        self.submanagerFiles = submanagerFiles
        self.submanagerObjects = submanagerObjects
        self.presentingViewController = presentingViewController

        super.init()

        inputView.delegate = self
        inputView.text = chat.enteredText ?? ""
        // KHANDAQ (#15): 1:1 chats get the same input bar as groups (attach + hold-to-record voice).
        inputView.cameraButtonEnabled = true
        inputView.voiceButtonEnabled = true
    }

    deinit {
        VideoSendPreprocessor.shared.cancelActivePreparation()
        VideoSendProgressOverlay.shared.hide()
        endUserInteraction()
    }
}

extension ChatInputViewManager: ChatInputViewDelegate {
    func chatInputViewCameraButtonPressed(_ view: ChatInputView, cameraView: UIView) {
        // KHANDAQ design (Figma): a rounded popup anchored above the "+" (Галерея / Камера / Аудио /
        // Геолокация) instead of a native bottom action sheet.
        var items: [AttachmentMenuController.Item] = []

        items.append(.init(title: String(localized: "attach_gallery"), systemImage: "photo.on.rectangle") { [weak self] in
            self?.presentPhotoLibraryPicker()
        })

        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            items.append(.init(title: String(localized: "photo_from_camera"), systemImage: "camera") { [weak self] in
                self?.presentCameraPicker()
            })
        }

        // KHANDAQ (#135): the "Аудио" menu item must pick an existing audio FILE, not start a
        // voice recording (hold-to-record on the voice button already covers voice notes).
        items.append(.init(title: String(localized: "attach_audio"), systemImage: "music.note") { [weak self] in
            self?.presentAudioFilePicker()
        })

        // KHANDAQ (Figma iOS page): generic "Файл" item — any document, like the group chat has.
        items.append(.init(title: String(localized: "attach_file"), systemImage: "doc") { [weak self] in
            self?.presentDocumentFilePicker()
        })

        if let shareLocation = onShareLocation {
            items.append(.init(title: String(localized: "attach_location"), systemImage: "location") {
                shareLocation()
            })
        }

        let menu = AttachmentMenuController(theme: theme, items: items, sourceView: cameraView)
        presentingViewController.present(menu, animated: true, completion: nil)
    }

    private func presentCameraPicker() {
        MediaPermission.requestCameraAccess(from: presentingViewController) { [weak self] granted in
            guard granted, let self = self else {
                return
            }

            let controller = UIImagePickerController()
            controller.delegate = self
            controller.sourceType = .camera
            controller.mediaTypes = [kUTTypeImage as String, kUTTypeMovie as String]
            controller.videoQuality = .typeMedium
            self.presentingViewController.present(controller, animated: true, completion: nil)
        }
    }

    func chatInputViewSendButtonPressed(_ view: ChatInputView) {
        let text = view.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return
        }

        let outgoing = outgoingTextComposer?(text) ?? text

        // KHANDAQ: Saved Messages is a friend-less local chat — store, don't transfer over Tox.
        if chat.isSavedMessages {
            submanagerObjects.addSavedTextMessage(outgoing, to: chat)
            view.text = ""
            endUserInteraction()
            return
        }

        // HINT: call OCTSubmanagerChatsImpl.m -> sendMessageToChat()
        submanagerChats.sendMessage(to: chat, text: outgoing, type: .normal, successBlock: { _ in
            DispatchQueue.main.async {
                view.text = ""
                self.endUserInteraction()
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) {
                os_log("PUSH:10_seconds")
                self.submanagerChats.sendMessagePush(to: self.chat)
            }
        }, failureBlock: { error in
            DispatchQueue.main.async {
                if let error = error as NSError? {
                    handleErrorWithType(.sendMessageToFriend, error: error)
                } else {
                    UIAlertController.showErrorWithMessage(String(localized: "error_internal_message"), retryBlock: nil)
                }
            }
        })
    }

    func chatInputViewTextDidChange(_ view: ChatInputView) {
        try? submanagerChats.setIsTyping(true, in: chat)
        inactivityTimer?.invalidate()

        inactivityTimer = Timer.scheduledTimer(timeInterval: Constants.inactivityTimeout, closure: {[weak self] _ -> Void in
            self?.endUserInteraction()
        }, repeats: false)
    }

    // KHANDAQ (#15): hold-to-record voice for 1:1 chats (mirrors the group input bar).
    func chatInputViewVoiceRecordDidStart(_ view: ChatInputView) {
        requestMicrophoneAccess { [weak self] granted in
            guard let self = self else { return }
            guard granted else {
                VoiceDebugHUD.shared.log("✖︎ mic DENIED")
                return
            }

            do {
                try self.voiceRecorder.startRecording()
            }
            catch {
                VoiceDebugHUD.shared.log("✖︎ startRecording threw: \(error.localizedDescription)")
                handleErrorWithType(.sendFileToFriend, error: error as NSError)
            }
        }
    }

    func chatInputViewVoiceRecordDidEnd(_ view: ChatInputView, cancelled: Bool) {
        if cancelled {
            VoiceDebugHUD.shared.log("• record ended: CANCELLED (slide/short)")
            voiceRecorder.cancelRecording()
            return
        }
        // KHANDAQ (#voice-iphone11): wait for the finalized file (delegate) before sending, so a
        // slow device (iPhone 11) doesn't hand over a half-written file that gets dropped.
        voiceRecorder.finishRecording { [weak self] url in
            guard let self = self, let url = url else {
                VoiceDebugHUD.shared.log("✖︎ no file from recorder → nothing sent")
                return
            }
            if self.chat.isSavedMessages {
                VoiceDebugHUD.shared.log("→ Saved: storing copy")
                self.storeSavedFileByCopying(atPath: url.path, fileName: url.lastPathComponent)
                try? FileManager.default.removeItem(at: url)
                return
            }
            VoiceDebugHUD.shared.log("→ sendFile to friend…")
            self.submanagerFiles.sendFile(atPath: url.path, moveToUploads: true, to: self.chat) { error in
                VoiceDebugHUD.shared.log("✖︎ sendFile error: \(error.localizedDescription)")
                handleErrorWithType(.sendFileToFriend, error: error as NSError)
            }
        }
    }

    func chatInputViewVoiceButtonTapped(_ view: ChatInputView) {
        UIAlertController.showWithTitle("",
                                        message: String(localized: "group_voice_hold_to_record"),
                                        retryBlock: nil)
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
}

extension ChatInputViewManager: UIImagePickerControllerDelegate {
    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [String : Any]) {
        presentingViewController.dismiss(animated: true, completion: nil)

        guard let type = info[UIImagePickerControllerMediaType] as? String else {
            showMediaPickFailed()
            return
        }

        loadPreviewItems(fromImagePickerInfo: info, mediaType: type) { [weak self] items in
            guard let self = self else {
                return
            }
            if items.isEmpty {
                self.showMediaPickFailed()
                return
            }
            self.presentMediaPreview(items: items)
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        presentingViewController.dismiss(animated: true, completion: nil)
    }
}

extension ChatInputViewManager: UINavigationControllerDelegate {}

@available(iOS 14.0, *)
extension ChatInputViewManager: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        presentingViewController.dismiss(animated: true, completion: nil)

        guard !results.isEmpty else {
            return
        }

        loadPreviewItems(fromPickerResults: results) { [weak self] items in
            guard let self = self else {
                return
            }
            if items.isEmpty {
                self.showMediaPickFailed()
                return
            }
            self.presentMediaPreview(items: items)
        }
    }
}

extension ChatInputViewManager {
    func endUserInteraction() {
        try? submanagerChats.setIsTyping(false, in: chat)
        inactivityTimer?.invalidate()

        if let inputView = inputView {
            submanagerObjects.change(chat, enteredText: inputView.text)
        }
    }
}

extension ChatInputViewManager: UIDocumentPickerDelegate {
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

        // KHANDAQ (#135): send the picked audio file. Saved Messages stores it locally.
        if chat.isSavedMessages {
            storeSavedFileByCopying(atPath: url.path, fileName: url.lastPathComponent)
            return
        }

        submanagerFiles.sendFile(atPath: url.path, moveToUploads: true, to: chat) { error in
            handleErrorWithType(.sendFileToFriend, error: error as NSError)
        }
    }
}

fileprivate extension ChatInputViewManager {
    // KHANDAQ (#135): pick an existing audio file (mp3/m4a/wav/…) and send it as a file.
    func presentAudioFilePicker() {
        let controller = UIDocumentPickerViewController(documentTypes: [kUTTypeAudio as String], in: .import)
        controller.delegate = self
        presentingViewController.present(controller, animated: true, completion: nil)
    }

    // KHANDAQ (Figma iOS page): pick any document and send it as a file (same delegate/send path).
    func presentDocumentFilePicker() {
        let controller = UIDocumentPickerViewController(documentTypes: [kUTTypeItem as String], in: .import)
        controller.delegate = self
        presentingViewController.present(controller, animated: true, completion: nil)
    }

    func presentPhotoLibraryPicker() {
        if #available(iOS 14.0, *) {
            var configuration = PHPickerConfiguration()
            configuration.filter = .any(of: [.images, .videos])
            configuration.selectionLimit = 10

            let controller = PHPickerViewController(configuration: configuration)
            controller.delegate = self
            presentingViewController.present(controller, animated: true, completion: nil)
        } else if UIImagePickerController.isSourceTypeAvailable(.photoLibrary) {
            let controller = UIImagePickerController()
            controller.delegate = self
            controller.sourceType = .photoLibrary
            controller.mediaTypes = [kUTTypeImage as String, kUTTypeMovie as String]
            controller.videoQuality = .typeMedium
            presentingViewController.present(controller, animated: true, completion: nil)
        }
    }

    func sendImage(imagePickerInfo: [String : Any]) {
        if let image = (imagePickerInfo[UIImagePickerControllerEditedImage] ?? imagePickerInfo[UIImagePickerControllerOriginalImage]) as? UIImage {
            sendImageData(image, fileName: fileNameFromImageInfo(imagePickerInfo))
            return
        }

        if let imageURL = imagePickerInfo[UIImagePickerControllerImageURL] as? URL {
            let accessing = imageURL.startAccessingSecurityScopedResource()
            defer {
                if accessing {
                    imageURL.stopAccessingSecurityScopedResource()
                }
            }

            if let data = try? Data(contentsOf: imageURL) {
                sendFileData(data, fileName: imageURL.lastPathComponent)
                return
            }
            if let image = UIImage(contentsOfFile: imageURL.path) {
                sendImageData(image, fileName: imageURL.lastPathComponent)
                return
            }
        }

        if let asset = imagePickerInfo[UIImagePickerControllerPHAsset] as? PHAsset {
            sendImageFromPHAsset(asset, fallbackFileName: fileNameFromImageInfo(imagePickerInfo))
            return
        }

        showMediaPickFailed()
    }

    func sendMovie(imagePickerInfo: [String : Any]) {
        if let url = imagePickerInfo[UIImagePickerControllerMediaURL] as? URL {
            enqueueVideoSend(from: url)
            return
        }

        if let asset = imagePickerInfo[UIImagePickerControllerPHAsset] as? PHAsset, asset.mediaType == .video {
            sendMovieFromPHAsset(asset)
            return
        }

        showMediaPickFailed()
    }

    func enqueueVideoSend(from sourceURL: URL, caption: String = "", completion: (() -> Void)? = nil) {
        guard !isVideoSendInProgress else {
            showVideoSendError(VideoSendError.busy, retryURL: sourceURL)
            completion?()
            return
        }

        isVideoSendInProgress = true
        VideoSendProgressOverlay.shared.show(on: presentingViewController,
                                             message: String(localized: "video_send_preparing"),
                                             onCancel: { [weak self] in
            VideoSendPreprocessor.shared.cancelActivePreparation()
            VideoSendProgressOverlay.shared.hide()
            self?.isVideoSendInProgress = false
        })

        VideoSendPreprocessor.shared.prepareVideo(at: sourceURL, progress: { progress in
            VideoSendProgressOverlay.shared.update(progress: progress)
        }, completion: { [weak self] result in
            DispatchQueue.main.async {
                VideoSendProgressOverlay.shared.hide()

                guard let self = self else {
                    completion?()
                    return
                }

                self.isVideoSendInProgress = false

                switch result {
                case .success(let preparedURL):
                    self.sendPreparedVideo(at: preparedURL, caption: caption)
                case .failure(let error):
                    self.showVideoSendError(error, retryURL: sourceURL)
                }

                completion?()
            }
        })
    }

    func enqueueVideoSendSequence(_ videos: [(url: URL, fileName: String?)], caption: String = "") {
        guard !videos.isEmpty else {
            if !caption.isEmpty {
                sendCaptionMessage(caption)
            }
            return
        }

        // KHANDAQ (#204-A): Saved Messages is a LOCAL self-chat with no Tox size limit, so a video does
        // NOT need the AVAssetExportSession transcode pipeline. That async pipeline was the silent-failure
        // point: a failed export (or a failed copy in @discardableResult storeSavedFileByCopying) left NO
        // message and NO error. Store the picked video directly, exactly like photos/documents do.
        if chat.isSavedMessages {
            for (index, video) in videos.enumerated() {
                // #204-B: prefer the original picked name over the UUID staging name.
                let name = video.fileName ?? video.url.lastPathComponent
                if !storeSavedFileByCopying(atPath: video.url.path, fileName: name) {
                    showMediaPickFailed()
                }
                if index == videos.count - 1 {
                    let trimmed = caption.trimmingCharacters(in: .whitespacesAndNewlines)
                    if !trimmed.isEmpty, let message = submanagerObjects.addSavedTextMessage(trimmed, to: chat) {
                        submanagerObjects.markMessage(asCaption: message)
                    }
                }
            }
            return
        }

        var remaining = videos
        func sendNext() {
            guard !remaining.isEmpty else {
                return
            }
            let video = remaining.removeFirst()
            // The last video carries the caption so it lands right after that video's file message.
            let itemCaption = remaining.isEmpty ? caption : ""
            enqueueVideoSend(from: video.url, caption: itemCaption) {
                sendNext()
            }
        }
        sendNext()
    }

    func sendPreparedVideo(at url: URL, caption: String = "") {
        let path = url.path
        videoSendQueue.async { [weak self] in
            guard let self = self else {
                return
            }

            DispatchQueue.main.async {
                let trimmed = caption.trimmingCharacters(in: .whitespacesAndNewlines)
                if self.chat.isSavedMessages {
                    self.storeSavedFileByCopying(atPath: path, fileName: url.lastPathComponent)
                    if !trimmed.isEmpty, let message = self.submanagerObjects.addSavedTextMessage(trimmed, to: self.chat) {
                        self.submanagerObjects.markMessage(asCaption: message)
                    }
                    return
                }
                // sendFile creates the file message synchronously, so the caption sent right after pairs.
                self.submanagerFiles.sendFile(atPath: path, moveToUploads: true, to: self.chat) { error in
                    handleErrorWithType(.sendFileToFriend, error: error as NSError)
                }
                if !trimmed.isEmpty {
                    self.sendCaptionMessage(trimmed)
                }
            }
        }
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

    func sendMovieFile(at url: URL) {
        enqueueVideoSend(from: url)
    }

    func sendImageData(_ image: UIImage, fileName: String?) {
        guard let data = UIImageJPEGRepresentation(image, 0.9) else {
            showMediaPickFailed()
            return
        }

        sendFileData(data, fileName: Self.normalizedImageFileName(fileName))
    }

    func sendFileData(_ data: Data, fileName: String) {
        if chat.isSavedMessages {
            guard let dir = Self.savedFilesDirectory() else { return }
            let dest = dir.appendingPathComponent("\(UUID().uuidString)_\(fileName)")
            guard (try? data.write(to: dest)) != nil else { return }
            storeSavedFile(atPath: dest.path, fileName: fileName)
            return
        }

        submanagerFiles.send(data, withFileName: fileName, to: chat) { (error: Error) in
            handleErrorWithType(.sendFileToFriend, error: error as NSError)
        }
    }

    /// Persistent dir for files kept only in Saved Messages (no Tox transfer).
    static func savedFilesDirectory() -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = base.appendingPathComponent("KhandaqSaved", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: nil)
        return dir
    }

    /// Copy `path` into the persistent saved dir and record a local file message. Returns true if stored.
    @discardableResult
    func storeSavedFileByCopying(atPath path: String, fileName: String) -> Bool {
        guard let dir = Self.savedFilesDirectory() else { return false }
        let dest = dir.appendingPathComponent("\(UUID().uuidString)_\(fileName)")
        guard (try? FileManager.default.copyItem(atPath: path, toPath: dest.path)) != nil else { return false }
        storeSavedFile(atPath: dest.path, fileName: fileName)
        return true
    }

    private func storeSavedFile(atPath path: String, fileName: String) {
        let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? NSNumber)??.int64Value ?? 0
        let uti = (fileName as NSString).pathExtension.isEmpty ? nil :
            UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension,
                                                  (fileName as NSString).pathExtension as CFString, nil)?.takeRetainedValue() as String?
        submanagerObjects.addSavedFileMessage(withPath: path, fileName: fileName, fileSize: size, fileUTI: uti, to: chat)
    }

    func sendImageFromPHAsset(_ asset: PHAsset, fallbackFileName: String?) {
        let options = PHImageRequestOptions()
        options.deliveryMode = .highQualityFormat
        options.isNetworkAccessAllowed = true

        PHImageManager.default().requestImageData(for: asset, options: options) { [weak self] data, _, _, info in
            guard let strongSelf = self else {
                return
            }

            DispatchQueue.main.async {
                if let data = data {
                    var fileName = fallbackFileName
                    if fileName == nil || fileName!.isEmpty {
                        if let resource = PHAssetResource.assetResources(for: asset).first {
                            fileName = resource.originalFilename
                        }
                    }
                    if let image = UIImage(data: data) {
                        strongSelf.sendImageData(image, fileName: fileName)
                        return
                    }
                    strongSelf.sendFileData(data, fileName: Self.normalizedImageFileName(fileName))
                    return
                }

                if (info?[PHImageCancelledKey] as? Bool) == true {
                    return
                }

                PHImageManager.default().requestImage(for: asset,
                                                      targetSize: PHImageManagerMaximumSize,
                                                      contentMode: .aspectFit,
                                                      options: options) { image, _ in
                    DispatchQueue.main.async {
                        guard let image = image else {
                            strongSelf.showMediaPickFailed()
                            return
                        }
                        strongSelf.sendImageData(image, fileName: fallbackFileName)
                    }
                }
            }
        }
    }

    func sendMovieFromPHAsset(_ asset: PHAsset) {
        guard let resource = PHAssetResource.assetResources(for: asset).first(where: { $0.type == .video || $0.type == .fullSizeVideo }) else {
            showMediaPickFailed()
            return
        }

        let ext = (resource.originalFilename as NSString).pathExtension
        let tempURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(ext.isEmpty ? "mov" : ext)

        let options = PHAssetResourceRequestOptions()
        options.isNetworkAccessAllowed = true

        PHAssetResourceManager.default().writeData(for: resource, toFile: tempURL, options: options) { [weak self] error in
            guard let strongSelf = self else {
                return
            }

            if let error = error {
                os_log("sendMovieFromPHAsset:export_failed %{public}@", error.localizedDescription)
                DispatchQueue.main.async {
                    strongSelf.showVideoSendError(error, retryURL: nil)
                }
                return
            }

            DispatchQueue.main.async {
                strongSelf.enqueueVideoSend(from: tempURL)
            }
        }
    }

    func isImageMediaType(_ type: String) -> Bool {
        UTTypeConformsTo(type as CFString, kUTTypeImage)
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

    func stageVideoURL(_ sourceURL: URL, completion: @escaping (Result<URL, Error>) -> Void) {
        videoSendQueue.async {
            autoreleasepool {
                do {
                    let staged = try VideoSendPreprocessor.shared.stagePickerVideo(at: sourceURL)
                    DispatchQueue.main.async {
                        completion(.success(staged))
                    }
                } catch {
                    DispatchQueue.main.async {
                        completion(.failure(error))
                    }
                }
            }
        }
    }

    func showMediaPickFailed() {
        UIAlertController.showErrorWithMessage(String(localized: "error_internal_message"), retryBlock: nil)
    }

    func presentMediaPreview(items: [MediaSendPreviewItem]) {
        // KHANDAQ: dismiss the chat input keyboard before showing the media-send preview. Otherwise the
        // still-active keyboard overlapped the preview / caption editor when sending video with a caption.
        presentingViewController.view.endEditing(true)
        let controller = MediaSendPreviewController(items: items)
        controller.delegate = self
        presentingViewController.present(controller, animated: true, completion: nil)
    }

    @available(iOS 14.0, *)
    func loadPreviewItems(fromPickerResults results: [PHPickerResult], completion: @escaping ([MediaSendPreviewItem]) -> Void) {
        var loaded: [MediaSendPreviewItem] = []
        let group = DispatchGroup()

        for result in results {
            let provider = result.itemProvider
            let movieType = movieTypeIdentifiers().first(where: { provider.hasItemConformingToTypeIdentifier($0) })

            if let movieType = movieType {
                group.enter()
                provider.loadFileRepresentation(forTypeIdentifier: movieType) { url, _ in
                    defer { group.leave() }
                    guard let url = url else {
                        return
                    }

                    do {
                        let staged = try VideoSendPreprocessor.shared.stagePickerVideo(at: url)
                        loaded.append(.video(staged, fileName: provider.suggestedName))
                    } catch {
                    }
                }
                continue
            }

            if provider.canLoadObject(ofClass: UIImage.self) {
                group.enter()
                provider.loadObject(ofClass: UIImage.self) { object, _ in
                    defer { group.leave() }
                    if let image = object as? UIImage {
                        loaded.append(.image(image, fileName: nil))
                    }
                }
            }
        }

        group.notify(queue: .main) {
            completion(loaded)
        }
    }

    func loadPreviewItems(fromImagePickerInfo info: [String: Any], mediaType: String, completion: @escaping ([MediaSendPreviewItem]) -> Void) {
        var items: [MediaSendPreviewItem] = []

        if isImageMediaType(mediaType) {
            if let image = (info[UIImagePickerControllerEditedImage] ?? info[UIImagePickerControllerOriginalImage]) as? UIImage {
                items.append(.image(image, fileName: fileNameFromImageInfo(info)))
                completion(items)
                return
            }

            if let imageURL = info[UIImagePickerControllerImageURL] as? URL {
                let accessing = imageURL.startAccessingSecurityScopedResource()
                defer {
                    if accessing {
                        imageURL.stopAccessingSecurityScopedResource()
                    }
                }

                if let image = UIImage(contentsOfFile: imageURL.path) {
                    items.append(.image(image, fileName: imageURL.lastPathComponent))
                    completion(items)
                    return
                }
            }

            if let asset = info[UIImagePickerControllerPHAsset] as? PHAsset {
                let options = PHImageRequestOptions()
                options.deliveryMode = .highQualityFormat
                options.isNetworkAccessAllowed = true
                PHImageManager.default().requestImage(for: asset,
                                                      targetSize: PHImageManagerMaximumSize,
                                                      contentMode: .aspectFit,
                                                      options: options) { image, _ in
                    DispatchQueue.main.async {
                        if let image = image {
                            completion([.image(image, fileName: self.fileNameFromImageInfo(info))])
                        } else {
                            completion([])
                        }
                    }
                }
                return
            }

            completion([])
            return
        }

        if isMovieMediaType(mediaType) {
            if let url = info[UIImagePickerControllerMediaURL] as? URL {
                let originalName = url.lastPathComponent
                stageVideoURL(url) { result in
                    switch result {
                    case .success(let staged):
                        completion([.video(staged, fileName: originalName)])
                    case .failure:
                        completion([])
                    }
                }
                return
            }

            if let asset = info[UIImagePickerControllerPHAsset] as? PHAsset, asset.mediaType == .video {
                guard let resource = PHAssetResource.assetResources(for: asset).first(where: { $0.type == .video || $0.type == .fullSizeVideo }) else {
                    completion([])
                    return
                }

                let ext = (resource.originalFilename as NSString).pathExtension
                let tempURL = URL(fileURLWithPath: NSTemporaryDirectory())
                    .appendingPathComponent(UUID().uuidString)
                    .appendingPathExtension(ext.isEmpty ? "mov" : ext)

                let options = PHAssetResourceRequestOptions()
                options.isNetworkAccessAllowed = true
                PHAssetResourceManager.default().writeData(for: resource, toFile: tempURL, options: options) { error in
                    DispatchQueue.main.async {
                        if error == nil {
                            completion([.video(tempURL, fileName: resource.originalFilename)])
                        } else {
                            completion([])
                        }
                    }
                }
                return
            }
        }

        completion([])
    }

    func sendConfirmedPreviewItems(_ items: [MediaSendPreviewItem], caption: String) {
        let trimmedCaption = caption.trimmingCharacters(in: .whitespacesAndNewlines)

        var videos: [(url: URL, fileName: String?)] = []
        for item in items {
            switch item {
            case .image(let image, let fileName):
                sendImageData(image, fileName: fileName)
            case .video(let url, let fileName):
                videos.append((url, fileName))
            }
        }

        if videos.isEmpty {
            // Photos create their file message synchronously, so the caption now pairs with the last one.
            if !trimmedCaption.isEmpty {
                sendCaptionMessage(trimmedCaption)
            }
            return
        }

        // Video prep is async — let the LAST video carry the caption so it follows the video's file message.
        enqueueVideoSendSequence(videos, caption: trimmedCaption)
    }

    func sendCaptionMessage(_ text: String) {
        let outgoing = outgoingTextComposer?(text) ?? text

        // Saved Messages: friend-less local chat — store + flag locally, no Tox send.
        if chat.isSavedMessages {
            if let message = submanagerObjects.addSavedTextMessage(outgoing, to: chat) {
                submanagerObjects.markMessage(asCaption: message)
            }
            return
        }

        submanagerChats.sendMessage(to: chat, text: outgoing, type: .normal, successBlock: { [weak self] message in
            // Mark as caption so it renders merged into the preceding media bubble (Telegram-style).
            if let message = message {
                self?.submanagerObjects.markMessage(asCaption: message)
            }
        }, failureBlock: { error in
            DispatchQueue.main.async {
                if let error = error as NSError? {
                    handleErrorWithType(.sendMessageToFriend, error: error)
                } else {
                    UIAlertController.showErrorWithMessage(String(localized: "error_internal_message"), retryBlock: nil)
                }
            }
        })
    }

    func fileNameFromImageInfo(_ info: [String: Any]) -> String? {
        guard let url = info[UIImagePickerControllerReferenceURL] as? URL else {
            return nil
        }

        let fetchResult = PHAsset.fetchAssets(withALAssetURLs: [url], options: nil)

        guard let asset = fetchResult.firstObject else {
            return nil
        }

        if #available(iOS 9.0, *) {
            if let resource = PHAssetResource.assetResources(for: asset).first {
                return resource.originalFilename
            }
        } else {
            // Fallback on earlier versions
            if let name = asset.value(forKey: "filename") as? String {
                return name
            }
        }

        return nil
    }

    /// Safe cross-platform name: ASCII, no spaces/colons, always `.jpg`.
    static func normalizedImageFileName(_ fileName: String?) -> String {
        if let fileName = fileName, !fileName.isEmpty {
            let base = (fileName as NSString).deletingPathExtension
            let sanitized = base
                .replacingOccurrences(of: "/", with: "-")
                .replacingOccurrences(of: ":", with: "-")
                .replacingOccurrences(of: " ", with: "_")
                .components(separatedBy: CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "._-")).inverted)
                .joined()
            if !sanitized.isEmpty {
                return sanitized + ".jpg"
            }
        }

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyyMMdd_HHmmss"
        return "Photo_\(formatter.string(from: Date())).jpg"
    }
}

extension ChatInputViewManager: MediaSendPreviewControllerDelegate {
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
}
