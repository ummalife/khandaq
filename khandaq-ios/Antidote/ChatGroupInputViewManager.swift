// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation
import MobileCoreServices
import Photos
import PhotosUI
import UIKit

private let groupVideoSendQueue = DispatchQueue(label: "khandaq.group.video.send", qos: .userInitiated)

class ChatGroupInputViewManager: NSObject {
    fileprivate var chat: OCTChat!
    fileprivate weak var inputView: ChatInputView?
    fileprivate weak var submanagerGroups: OCTSubmanagerGroups!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate weak var presentingViewController: UIViewController!
    fileprivate var isVideoSendInProgress = false
    fileprivate let voiceRecorder = GroupVoiceMessageRecorder()

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
    }
}

extension ChatGroupInputViewManager: ChatInputViewDelegate {
    func chatInputViewVoiceRecordDidStart(_ view: ChatInputView) {
        requestMicrophoneAccess { [weak self] granted in
            guard granted, let self = self else {
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
        guard let url = voiceRecorder.stopRecording(discard: cancelled) else {
            return
        }

        sendFileAtPath(url.path)
    }

    func chatInputViewVoiceButtonTapped(_ view: ChatInputView) {
        UIAlertController.showWithTitle("",
                                        message: String(localized: "group_voice_hold_to_record"),
                                        retryBlock: nil)
    }

    func chatInputViewCameraButtonPressed(_ view: ChatInputView, cameraView: UIView) {
        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
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

        submanagerGroups.sendMessage(to: chat, text: outgoing, type: .normal, successBlock: { _ in
            DispatchQueue.main.async {
                view.text = ""
                self.saveEnteredText()
            }
        }, failureBlock: { error in
            DispatchQueue.main.async {
                if let error = error as NSError? {
                    handleErrorWithType(.sendMessageToFriend, error: error)
                }
                else {
                    UIAlertController.showErrorWithMessage(String(localized: "error_internal_message"), retryBlock: nil)
                }
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
            sendImageData(image)
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

        let provider = result.itemProvider
        let movieType = movieTypeIdentifiers().first(where: { provider.hasItemConformingToTypeIdentifier($0) })

        if let movieType = movieType {
            provider.loadFileRepresentation(forTypeIdentifier: movieType) { [weak self] url, _ in
                guard let self = self, let url = url else {
                    return
                }

                groupVideoSendQueue.async {
                    autoreleasepool {
                        do {
                            let staged = try VideoSendPreprocessor.shared.stagePickerVideo(at: url)
                            DispatchQueue.main.async {
                                self.enqueueVideoSend(from: staged)
                            }
                        }
                        catch {
                            DispatchQueue.main.async {
                                self.showVideoSendError(error, retryURL: nil)
                            }
                        }
                    }
                }
            }
            return
        }

        guard provider.canLoadObject(ofClass: UIImage.self) else {
            return
        }

        provider.loadObject(ofClass: UIImage.self) { [weak self] object, _ in
            guard let self = self,
                  let image = object as? UIImage else {
                return
            }

            DispatchQueue.main.async {
                self.sendImageData(image)
            }
        }
    }
}

private extension ChatGroupInputViewManager {
    func presentPhotoLibraryPicker() {
        if #available(iOS 14.0, *) {
            var configuration = PHPickerConfiguration()
            configuration.filter = .any(of: [.images, .videos])
            configuration.selectionLimit = 1

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

    func sendFileData(_ data: Data, fileName: String) {
        let tempURL = FileManager.default.temporaryDirectory.appendingPathComponent(fileName)

        do {
            try data.write(to: tempURL)
            sendFileAtPath(tempURL.path)
        }
        catch {
            handleErrorWithType(.sendFileToFriend, error: error as NSError)
        }
    }

    func sendImageData(_ image: UIImage) {
        guard let data = GroupImagePreparer.jpegDataForGroupUpload(from: image) else {
            UIAlertController.showErrorWithMessage(String(localized: "group_file_send_failed"), retryBlock: nil)
            return
        }

        sendFileData(data, fileName: "photo.jpg")
    }

    func sendMovie(imagePickerInfo: [String: Any]) {
        if let url = imagePickerInfo[UIImagePickerControllerMediaURL] as? URL {
            enqueueVideoSend(from: url)
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

        PHAssetResourceManager.default().writeData(for: resource, toFile: tempURL, options: nil) { [weak self] error in
            DispatchQueue.main.async {
                guard let self = self else {
                    return
                }

                if let error = error {
                    self.showVideoSendError(error, retryURL: nil)
                    return
                }

                self.enqueueVideoSend(from: tempURL)
            }
        }
    }

    func enqueueVideoSend(from sourceURL: URL, completion: (() -> Void)? = nil) {
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
                    self.sendPreparedVideo(at: preparedURL)
                case .failure(let error):
                    self.showVideoSendError(error, retryURL: sourceURL)
                }

                completion?()
            }
        })
    }

    func sendPreparedVideo(at url: URL) {
        sendFileAtPath(url.path)
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

    func sendFileAtPath(_ filePath: String) {
        submanagerGroups.sendFile(atPath: filePath, to: chat, moveToUploads: true, successBlock: { _ in
            // message persisted by submanager
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
