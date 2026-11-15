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
/// KHANDAQ (large media): photos are encoded as JPEG here, one after another, so their file messages
/// keep the order they were chosen in.
fileprivate let imageEncodeQueue = DispatchQueue(label: "khandaq.image.encode", qos: .userInitiated)

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
    /// KHANDAQ (review 0.2.56): the video this chat prepares, by its banner and its preparation. The
    /// preprocessor and the banner serve every chat: this one stops and takes down only its own.
    fileprivate var videoSend: (banner: Int, preparation: Int)?
    fileprivate var isVideoSendInProgress: Bool {
        return videoSend != nil
    }
    fileprivate let voiceRecorder = GroupVoiceMessageRecorder()
    fileprivate var voiceHold = VoiceHoldGate()

    var outgoingTextComposer: ((String) -> String)?

    // KHANDAQ design (Figma): set by the chat controller to share the current location once from the
    // "+" attachment menu. When nil (e.g. groups), the Геолокация item is hidden.
    var onShareLocation: (() -> Void)?

    /// KHANDAQ (redesign stage 2): called on the main queue when the network refuses a text outright
    /// (a message for a person who is offline is not refused: the core keeps it for them). Return true
    /// when the screen keeps the text with "Not sent. Retry" itself; the field is then cleared and no
    /// alert is shown. The text is the one that went out, with its reply header.
    var onSendFailed: ((String, NSError?) -> Bool)?

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
        // KHANDAQ (review 0.2.56): only the video of this chat. A screen that went away stopped whatever
        // video was being prepared and took whatever banner was up down, those of another chat too.
        cancelVideoSend()
        endUserInteraction()
    }
}

extension ChatInputViewManager: ChatInputViewDelegate {
    func chatInputViewCameraButtonPressed(_ view: ChatInputView, cameraView: UIView) {
        // KHANDAQ design (Figma): a rounded popup anchored above the "+" (Галерея / Камера / Аудио /
        // Геолокация) instead of a native bottom action sheet.
        var items: [AttachmentMenuController.Item] = []

        items.append(.init(title: String(localized: "attach_gallery"), systemImage: "photo.on.rectangle",
                           identifier: "air.attach.gallery") { [weak self] in
            self?.presentPhotoLibraryPicker()
        })

        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            items.append(.init(title: String(localized: "photo_from_camera"), systemImage: "camera",
                               identifier: "air.attach.camera") { [weak self] in
                self?.presentCameraPicker()
            })
        }

        // KHANDAQ (#135): the "Аудио" menu item must pick an existing audio FILE, not start a
        // voice recording (hold-to-record on the voice button already covers voice notes).
        items.append(.init(title: String(localized: "attach_audio"), systemImage: "music.note",
                           identifier: "air.attach.audio") { [weak self] in
            self?.presentAudioFilePicker()
        })

        // KHANDAQ (Figma iOS page): generic "Файл" item — any document, like the group chat has.
        items.append(.init(title: String(localized: "attach_file"), systemImage: "doc",
                           identifier: "air.attach.file") { [weak self] in
            self?.presentDocumentFilePicker()
        })

        if let shareLocation = onShareLocation {
            items.append(.init(title: String(localized: "attach_location"), systemImage: "location",
                               identifier: "air.attach.location") {
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

        // KHANDAQ (long texts): a text longer than one message goes as several, in order, through
        // OCTSubmanagerChatsImpl.m -> sendMessagePartsToChat(). The field is cleared once the core has
        // taken a part or kept a refused one with "Retry"; one wake-up signal covers every part.
        let parts = AirComposeLimit.parts(ofComposed: outgoing, body: text)
        var cleared = false
        var alerted = false
        var pushScheduled = false
        let clearField = {
            guard !cleared else {
                return
            }
            cleared = true
            view.text = ""
            self.endUserInteraction()
        }
        submanagerChats.sendMessageParts(to: chat, parts: parts, type: .normal, successBlock: { _, _ in
            DispatchQueue.main.async {
                clearField()
                guard !pushScheduled else {
                    return
                }
                pushScheduled = true
                // The signal goes also once this screen is gone, and does not keep the screen until then.
                DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak submanagerChats = self.submanagerChats, chat = self.chat] in
                    // The contact (and with it the chat) may have been removed in the meantime.
                    guard let submanagerChats = submanagerChats, let chat = chat, !chat.isInvalidated else {
                        return
                    }
                    os_log("PUSH:10_seconds")
                    submanagerChats.sendMessagePush(to: chat)
                }
            }
        }, failureBlock: { error, index in
            DispatchQueue.main.async {
                let part = parts.indices.contains(index) ? parts[index] : outgoing
                if let keep = self.onSendFailed, keep(part, error as NSError?) {
                    clearField()
                    return
                }
                guard !alerted else {
                    return
                }
                alerted = true
                // E21: without an error the words still say what failed.
                handleErrorWithType(.sendMessageToFriend, error: error as NSError?)
            }
        })
    }

    /// KHANDAQ (redesign stage 2): "Retry" for a refused text: the same call as the send button, with
    /// the same wake-up signal ten seconds later. `completion` runs on the main queue with true once the
    /// core has taken the message.
    func resend(_ outgoing: String, completion: @escaping (Bool) -> Void) {
        guard !chat.isSavedMessages else {
            completion(false)
            return
        }
        submanagerChats.sendMessage(to: chat, text: outgoing, type: .normal, successBlock: { _ in
            DispatchQueue.main.async {
                completion(true)
            }
            // The signal goes also once this screen is gone, and does not keep the screen until then.
            DispatchQueue.main.asyncAfter(deadline: .now() + 10) { [weak submanagerChats = self.submanagerChats, chat = self.chat] in
                // The contact (and with it the chat) may have been removed in the meantime.
                guard let submanagerChats = submanagerChats, let chat = chat, !chat.isInvalidated else {
                    return
                }
                os_log("PUSH:10_seconds")
                submanagerChats.sendMessagePush(to: chat)
            }
        }, failureBlock: { _ in
            DispatchQueue.main.async {
                completion(false)
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
    // Stage 5: with the microphone denied the hold showed its recording bar and sent nothing, without a
    // word; it says why and offers Settings, as a call does (E9, E21). The first hold asks iOS, and the
    // answer comes after the hold: the recording starts only while the hold is on (VoiceHoldGate).
    func chatInputViewVoiceRecordDidStart(_ view: ChatInputView) {
        let hold = voiceHold.begin()
        MediaPermission.requestMicrophoneAccess(from: presentingViewController) { [weak self] granted in
            guard let self = self else { return }
            guard granted else {
                VoiceDebugHUD.shared.log("✖︎ mic DENIED")
                return
            }
            guard self.voiceHold.mayRecord(for: hold) else {
                VoiceDebugHUD.shared.log("mic allowed after the hold ended: not recording")
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
        voiceHold.end()
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
        // After the contact is removed from the conversation the chat is gone from Realm.
        guard !chat.isInvalidated else {
            inactivityTimer?.invalidate()
            return
        }
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

/// Saved Messages keeps its files on this device, in a folder of their own; a file shared from another
/// app into Saved Messages goes there too (stage 5).
extension ChatInputViewManager {
    /// Persistent dir for files kept only in Saved Messages (no Tox transfer).
    static func savedFilesDirectory() -> URL? {
        guard let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first else {
            return nil
        }
        let dir = base.appendingPathComponent("KhandaqSaved", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true, attributes: nil)
        return dir
    }

    /// A file already in the folder of Saved files, recorded as a message of the Saved Messages `chat`.
    static func recordSavedFile(atPath path: String, fileName: String, to chat: OCTChat, objects: OCTSubmanagerObjects) {
        let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? NSNumber)??.int64Value ?? 0
        let uti = (fileName as NSString).pathExtension.isEmpty ? nil :
            UTTypeCreatePreferredIdentifierForTag(kUTTagClassFilenameExtension,
                                                  (fileName as NSString).pathExtension as CFString, nil)?.takeRetainedValue() as String?
        objects.addSavedFileMessage(withPath: path, fileName: fileName, fileSize: size, fileUTI: uti, to: chat)
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
            // KHANDAQ (large media): the file as it is in the library. With the default the system may
            // transcode a video before handing it over, a long wait for a big one, while the app
            // transcodes it anyway when it is large (VideoSendPreprocessor, to HEVC) and re-encodes
            // every photo as JPEG.
            configuration.preferredAssetRepresentationMode = .current

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
                // Over, unless Cancel let another video of this chat start already.
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

    /// Stops the video this chat prepares and takes its banner down, so that another one can start at
    /// once; with `banner`, only while that banner's video is the one in progress.
    func cancelVideoSend(banner: Int? = nil) {
        guard let videoSend = videoSend, banner == nil || banner == videoSend.banner else {
            return
        }
        self.videoSend = nil
        VideoSendPreprocessor.shared.cancelPreparation(videoSend.preparation)
        VideoSendProgressOverlay.shared.hide(banner: videoSend.banner)
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
        // KHANDAQ (review 0.2.56): the sequence does not keep the screen. It did, so a screen that went
        // away stopped none of its videos, and the next one was put up on a screen no longer there.
        func sendNext(with manager: ChatInputViewManager) {
            guard !remaining.isEmpty else {
                return
            }
            let video = remaining.removeFirst()
            // The last video carries the caption so it lands right after that video's file message.
            let itemCaption = remaining.isEmpty ? caption : ""
            manager.enqueueVideoSend(from: video.url, caption: itemCaption) { [weak manager] cancelled in
                guard !cancelled, let manager = manager else {
                    return
                }
                sendNext(with: manager)
            }
        }
        sendNext(with: self)
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

    /// KHANDAQ (large media): the JPEG is encoded off the main queue (a full-size photo takes a
    /// noticeable moment, and the preview hands over up to ten); the file message is created on the main
    /// queue after the ones before it. `completion` runs there after it, unless the chat is gone by then.
    func sendImageData(_ image: UIImage, fileName: String?, completion: (() -> Void)? = nil) {
        imageEncodeQueue.async {
            let data = autoreleasepool {
                UIImageJPEGRepresentation(image, 0.9)
            }
            DispatchQueue.main.async {
                guard self.submanagerFiles != nil, !self.chat.isInvalidated else {
                    return
                }
                if let data = data {
                    self.sendFileData(data, fileName: Self.normalizedImageFileName(fileName))
                }
                else {
                    self.showMediaPickFailed()
                }
                completion?()
            }
        }
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
        Self.recordSavedFile(atPath: path, fileName: fileName, to: chat, objects: submanagerObjects)
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
        UIAlertController.showWithTitle(String(localized: "error_failed_pick_media"),
                                        message: String(localized: "error_try_again_message"),
                                        retryBlock: nil)
    }

    func presentMediaPreview(items: [MediaSendPreviewItem]) {
        // KHANDAQ: dismiss the chat input keyboard before showing the media-send preview. Otherwise the
        // still-active keyboard overlapped the preview / caption editor when sending video with a caption.
        presentingViewController.view.endEditing(true)
        let controller = MediaSendPreviewController(items: items)
        controller.delegate = self
        presentingViewController.present(controller, animated: true, completion: nil)
    }

    /// KHANDAQ (large media): the banner while the items load (unless a video is being prepared and its
    /// banner is up), in the order they were chosen. The providers answer on queues of their own, and
    /// all of them appended to one array here. Not called back after Cancel on the banner.
    @available(iOS 14.0, *)
    func loadPreviewItems(fromPickerResults results: [PHPickerResult], completion: @escaping ([MediaSendPreviewItem]) -> Void) {
        PickedMediaLoader.load(results,
                               movieTypes: movieTypeIdentifiers(),
                               bannerHost: isVideoSendInProgress ? nil : presentingViewController,
                               message: String(localized: "media_pick_loading")) { loaded in
            completion(loaded.items)
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

        var images: [(image: UIImage, fileName: String?)] = []
        var videos: [(url: URL, fileName: String?)] = []
        for item in items {
            switch item {
            case .image(let image, let fileName):
                images.append((image, fileName))
            case .video(let url, let fileName):
                videos.append((url, fileName))
            }
        }

        let afterPhotos = { [weak self] in
            guard let self = self else {
                return
            }
            if videos.isEmpty {
                // The photos' file messages exist by now, so the caption pairs with the last one.
                if !trimmedCaption.isEmpty {
                    self.sendCaptionMessage(trimmedCaption)
                }
                return
            }

            // Video prep is async — let the LAST video carry the caption so it follows the video's file message.
            self.enqueueVideoSendSequence(videos, caption: trimmedCaption)
        }

        guard let last = images.indices.last else {
            afterPhotos()
            return
        }
        for (index, photo) in images.enumerated() {
            sendImageData(photo.image, fileName: photo.fileName, completion: index == last ? afterPhotos : nil)
        }
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

        // KHANDAQ (long texts): a caption longer than one message goes as several; the first is the
        // caption of the media, the rest follow it as texts.
        let parts = AirComposeLimit.parts(ofComposed: outgoing, body: text)
        var alerted = false
        submanagerChats.sendMessageParts(to: chat, parts: parts, type: .normal, successBlock: { [weak self] message, index in
            // Mark as caption so it renders merged into the preceding media bubble (Telegram-style).
            if index == 0, let message = message {
                self?.submanagerObjects.markMessage(asCaption: message)
            }
        }, failureBlock: { error, _ in
            DispatchQueue.main.async {
                guard !alerted else {
                    return
                }
                alerted = true
                // E21: without an error the words still say what failed.
                handleErrorWithType(.sendMessageToFriend, error: error as NSError?)
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
