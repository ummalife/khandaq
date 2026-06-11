// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

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

    fileprivate weak var submanagerChats: OCTSubmanagerChats!
    fileprivate weak var submanagerFiles: OCTSubmanagerFiles!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    fileprivate weak var presentingViewController: UIViewController!

    fileprivate var inactivityTimer: Timer?
    fileprivate var isVideoSendInProgress = false

    init(inputView: ChatInputView,
         chat: OCTChat,
         submanagerChats: OCTSubmanagerChats,
         submanagerFiles: OCTSubmanagerFiles,
         submanagerObjects: OCTSubmanagerObjects,
         presentingViewController: UIViewController) {

        self.chat = chat
        self.inputView = inputView
        self.submanagerChats = submanagerChats
        self.submanagerFiles = submanagerFiles
        self.submanagerObjects = submanagerObjects
        self.presentingViewController = presentingViewController

        super.init()

        inputView.delegate = self
        inputView.text = chat.enteredText ?? ""
    }

    deinit {
        VideoSendPreprocessor.shared.cancelActivePreparation()
        VideoSendProgressOverlay.shared.hide()
        endUserInteraction()
    }
}

extension ChatInputViewManager: ChatInputViewDelegate {
    func chatInputViewCameraButtonPressed(_ view: ChatInputView, cameraView: UIView) {
        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.sourceView = cameraView
        alert.popoverPresentationController?.sourceRect = CGRect(x: cameraView.frame.size.width / 2, y: cameraView.frame.size.height / 2, width: 1.0, height: 1.0)

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
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        presentingViewController.present(alert, animated: true, completion: nil)
    }

    func chatInputViewSendButtonPressed(_ view: ChatInputView) {
        let text = view.text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else {
            return
        }

        // HINT: call OCTSubmanagerChatsImpl.m -> sendMessageToChat()
        submanagerChats.sendMessage(to: chat, text: text, type: .normal, successBlock: { _ in
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

fileprivate extension ChatInputViewManager {
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

    func enqueueVideoSend(from sourceURL: URL, completion: (() -> Void)? = nil) {
        guard !isVideoSendInProgress else {
            showVideoSendError(VideoSendError.busy, retryURL: sourceURL)
            completion?()
            return
        }

        isVideoSendInProgress = true
        VideoSendProgressOverlay.shared.show(on: presentingViewController,
                                             message: String(localized: "video_send_preparing"))

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
                    self.sendPreparedVideo(at: preparedURL)
                case .failure(let error):
                    self.showVideoSendError(error, retryURL: sourceURL)
                }

                completion?()
            }
        })
    }

    func enqueueVideoSendSequence(_ urls: [URL]) {
        guard !urls.isEmpty else {
            return
        }

        var remaining = urls
        func sendNext() {
            guard !remaining.isEmpty else {
                return
            }
            let url = remaining.removeFirst()
            enqueueVideoSend(from: url) {
                sendNext()
            }
        }
        sendNext()
    }

    func sendPreparedVideo(at url: URL) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else {
                return
            }

            self.submanagerFiles.sendFile(atPath: url.path, moveToUploads: true, to: self.chat) { error in
                handleErrorWithType(.sendFileToFriend, error: error as NSError)
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
        submanagerFiles.send(data, withFileName: fileName, to: chat) { (error: Error) in
            handleErrorWithType(.sendFileToFriend, error: error as NSError)
        }
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
                        loaded.append(.video(staged))
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
                stageVideoURL(url) { result in
                    switch result {
                    case .success(let staged):
                        completion([.video(staged)])
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
                            completion([.video(tempURL)])
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
        var videoURLs: [URL] = []

        for item in items {
            switch item {
            case .image(let image, let fileName):
                sendImageData(image, fileName: fileName)
            case .video(let url):
                videoURLs.append(url)
            }
        }

        enqueueVideoSendSequence(videoURLs)

        let trimmedCaption = caption.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedCaption.isEmpty {
            sendCaptionMessage(trimmedCaption)
        }
    }

    func sendCaptionMessage(_ text: String) {
        submanagerChats.sendMessage(to: chat, text: text, type: .normal, successBlock: { _ in
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
