// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import MobileCoreServices
import Photos
import UIKit
import os

fileprivate struct Constants {
    static let inactivityTimeout = 4.0
}

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
        endUserInteraction()
    }
}

extension ChatInputViewManager: ChatInputViewDelegate {
    func chatInputViewCameraButtonPressed(_ view: ChatInputView, cameraView: UIView) {
        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.sourceView = cameraView
        alert.popoverPresentationController?.sourceRect = CGRect(x: cameraView.frame.size.width / 2, y: cameraView.frame.size.height / 2, width: 1.0, height: 1.0)

        func addAction(title: String, sourceType: UIImagePickerControllerSourceType) {
            if UIImagePickerController.isSourceTypeAvailable(sourceType) {
                alert.addAction(UIAlertAction(title: title, style: .default) { [unowned self] _ -> Void in
                    let controller = UIImagePickerController()
                    controller.delegate = self
                    controller.sourceType = sourceType
                    controller.mediaTypes = [kUTTypeImage as String, kUTTypeMovie as String]
                    controller.videoQuality = .typeHigh
                    self.presentingViewController.present(controller, animated: true, completion: nil)
                })
            }
        }

        addAction(title: String(localized: "photo_from_camera"), sourceType: .camera)
        addAction(title: String(localized: "photo_from_photo_library"), sourceType: .photoLibrary)
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

        if isImageMediaType(type) {
            sendImage(imagePickerInfo: info)
        } else if isMovieMediaType(type) {
            sendMovie(imagePickerInfo: info)
        } else {
            showMediaPickFailed()
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        presentingViewController.dismiss(animated: true, completion: nil)
    }
}

extension ChatInputViewManager: UINavigationControllerDelegate {}

fileprivate extension ChatInputViewManager {
    func endUserInteraction() {
        try? submanagerChats.setIsTyping(false, in: chat)
        inactivityTimer?.invalidate()

        if let inputView = inputView {
            submanagerObjects.change(chat, enteredText: inputView.text)
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
            sendMovieFile(at: url)
            return
        }

        if let asset = imagePickerInfo[UIImagePickerControllerPHAsset] as? PHAsset, asset.mediaType == .video {
            sendMovieFromPHAsset(asset)
            return
        }

        showMediaPickFailed()
    }

    func sendMovieFile(at url: URL) {
        let ext = url.pathExtension.isEmpty ? "mov" : url.pathExtension
        let tempURL = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(ext)

        let accessing = url.startAccessingSecurityScopedResource()
        defer {
            if accessing {
                url.stopAccessingSecurityScopedResource()
            }
        }

        do {
            if FileManager.default.fileExists(atPath: tempURL.path) {
                try FileManager.default.removeItem(at: tempURL)
            }
            try FileManager.default.copyItem(at: url, to: tempURL)
        } catch {
            os_log("sendMovieFile:copy_failed %{public}@, trying direct path", error.localizedDescription)
            submanagerFiles.sendFile(atPath: url.path, moveToUploads: true, to: chat) { (error: Error) in
                handleErrorWithType(.sendFileToFriend, error: error as NSError)
            }
            return
        }

        submanagerFiles.sendFile(atPath: tempURL.path, moveToUploads: true, to: chat) { (error: Error) in
            handleErrorWithType(.sendFileToFriend, error: error as NSError)
        }
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

            DispatchQueue.main.async {
                if let error = error {
                    os_log("sendMovieFromPHAsset:export_failed %{public}@", error.localizedDescription)
                    strongSelf.showMediaPickFailed()
                    return
                }

                strongSelf.submanagerFiles.sendFile(atPath: tempURL.path, moveToUploads: true, to: strongSelf.chat) { (error: Error) in
                    handleErrorWithType(.sendFileToFriend, error: error as NSError)
                }
            }
        }
    }

    func isImageMediaType(_ type: String) -> Bool {
        UTTypeConformsTo(type as CFString, kUTTypeImage)
    }

    func isMovieMediaType(_ type: String) -> Bool {
        let cfType = type as CFString
        return UTTypeConformsTo(cfType, kUTTypeMovie) || UTTypeConformsTo(cfType, kUTTypeVideo)
    }

    func showMediaPickFailed() {
        UIAlertController.showErrorWithMessage(String(localized: "error_internal_message"), retryBlock: nil)
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
