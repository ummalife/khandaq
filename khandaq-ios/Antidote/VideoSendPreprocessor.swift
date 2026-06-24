// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import MobileCoreServices
import UIKit

enum VideoSendError: LocalizedError {
    case fileTooLarge
    case preparationFailed
    case busy
    case durationTooLong

    var errorDescription: String? {
        switch self {
        case .fileTooLarge:
            return String(localized: "video_send_too_large")
        case .preparationFailed:
            return String(localized: "video_send_preparation_failed")
        case .busy:
            return String(localized: "video_send_busy")
        case .durationTooLong:
            return String(localized: "video_send_too_long")
        }
    }
}

/// Compresses and stages video files for Tox file transfer without loading the whole file into RAM.
/// All work runs on a single serial background queue — never more than one export at a time.
final class VideoSendPreprocessor {
    static let shared = VideoSendPreprocessor()

    /// Reject originals larger than this before any processing.
    static let maxInputBytes: Int64 = 500 * 1024 * 1024
    /// Transcode when the file exceeds this size or resolution/duration thresholds.
    static let skipCompressBelowBytes: Int64 = 8 * 1024 * 1024
    /// Hard cap on exported file size; retry with a lower preset when exceeded.
    static let maxOutputBytes: Int64 = 200 * 1024 * 1024
    /// Reject clips longer than this before transcoding.
    static let maxDurationSeconds: Double = 600

    private let processingQueue = DispatchQueue(label: "khandaq.video.preprocess", qos: .userInitiated)
    private var isProcessing = false
    private var activeExportSession: AVAssetExportSession?

    private init() {}

    func prepareVideo(at sourceURL: URL,
                      progress: @escaping (Float) -> Void,
                      completion: @escaping (Result<URL, Error>) -> Void) {
        processingQueue.async { [weak self] in
            guard let self = self else {
                DispatchQueue.main.async {
                    completion(.failure(VideoSendError.preparationFailed))
                }
                return
            }

            if self.isProcessing {
                DispatchQueue.main.async {
                    completion(.failure(VideoSendError.busy))
                }
                return
            }

            self.isProcessing = true
            autoreleasepool {
                self.prepareVideoOnBackground(at: sourceURL, progress: progress) { result in
                    self.isProcessing = false
                    self.activeExportSession = nil
                    DispatchQueue.main.async {
                        completion(result)
                    }
                }
            }
        }
    }

    func cancelActivePreparation() {
        processingQueue.async { [weak self] in
            self?.activeExportSession?.cancelExport()
            self?.activeExportSession = nil
            self?.isProcessing = false
        }
    }

    /// Copy a picker-owned temporary URL into app-controlled storage before preview/send.
    func stagePickerVideo(at sourceURL: URL) throws -> URL {
        let accessing = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessing {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        if let fileSize = fileByteCount(at: sourceURL), fileSize > Self.maxInputBytes {
            throw VideoSendError.fileTooLarge
        }

        let ext = sourceURL.pathExtension.isEmpty ? "mov" : sourceURL.pathExtension
        let stagedURL = temporaryOutputURL(extension: ext)

        if FileManager.default.fileExists(atPath: stagedURL.path) {
            try FileManager.default.removeItem(at: stagedURL)
        }
        try FileManager.default.copyItem(at: sourceURL, to: stagedURL)
        return stagedURL
    }

    private func prepareVideoOnBackground(at sourceURL: URL,
                                          progress: @escaping (Float) -> Void,
                                          completion: @escaping (Result<URL, Error>) -> Void) {
        let accessing = sourceURL.startAccessingSecurityScopedResource()
        defer {
            if accessing {
                sourceURL.stopAccessingSecurityScopedResource()
            }
        }

        guard let inputBytes = fileByteCount(at: sourceURL) else {
            completion(.failure(VideoSendError.preparationFailed))
            return
        }

        if inputBytes > Self.maxInputBytes {
            completion(.failure(VideoSendError.fileTooLarge))
            return
        }

        let asset = AVURLAsset(url: sourceURL, options: [AVURLAssetPreferPreciseDurationAndTimingKey: false])

        let durationSeconds = Self.durationSeconds(for: asset)
        if durationSeconds.isFinite, durationSeconds > Self.maxDurationSeconds {
            completion(.failure(VideoSendError.durationTooLong))
            return
        }

        let needsTranscode = inputBytes > Self.skipCompressBelowBytes
            || Self.videoExceedsTargetResolution(asset)
            || (durationSeconds.isFinite && durationSeconds > 120)

        if !needsTranscode {
            stageFile(at: sourceURL, deleteSourceIfTemporary: true, completion: completion)
            return
        }

        let presets = exportPresetCandidates()
        tryExport(asset: asset,
                  presets: presets,
                  sourceURL: sourceURL,
                  progress: progress,
                  completion: completion)
    }

    private func tryExport(asset: AVURLAsset,
                           presets: [String],
                           sourceURL: URL,
                           progress: @escaping (Float) -> Void,
                           completion: @escaping (Result<URL, Error>) -> Void) {
        guard let preset = presets.first else {
            if let inputBytes = fileByteCount(at: sourceURL), inputBytes <= Self.skipCompressBelowBytes {
                stageFile(at: sourceURL, deleteSourceIfTemporary: true, completion: completion)
            } else {
                completion(.failure(VideoSendError.preparationFailed))
            }
            return
        }

        let remainingPresets = Array(presets.dropFirst())
        let compatiblePresets = Set(AVAssetExportSession.exportPresets(compatibleWith: asset))

        guard compatiblePresets.contains(preset),
              let export = AVAssetExportSession(asset: asset, presetName: preset) else {
            tryExport(asset: asset,
                      presets: remainingPresets,
                      sourceURL: sourceURL,
                      progress: progress,
                      completion: completion)
            return
        }

        let outputURL = temporaryOutputURL(extension: "mp4")
        export.outputURL = outputURL
        export.outputFileType = .mp4
        export.shouldOptimizeForNetworkUse = true
        activeExportSession = export

        let progressTimer = DispatchSource.makeTimerSource(queue: processingQueue)
        progressTimer.schedule(deadline: .now(), repeating: .milliseconds(200))
        progressTimer.setEventHandler {
            let value = export.progress
            DispatchQueue.main.async {
                progress(value)
            }
        }
        progressTimer.resume()

        export.exportAsynchronously { [weak self] in
            progressTimer.cancel()
            self?.activeExportSession = nil

            if export.status == .completed {
                if let outputBytes = self?.fileByteCount(at: outputURL),
                   outputBytes > Self.maxOutputBytes,
                   !remainingPresets.isEmpty {
                    try? FileManager.default.removeItem(at: outputURL)
                    self?.tryExport(asset: asset,
                                    presets: remainingPresets,
                                    sourceURL: sourceURL,
                                    progress: progress,
                                    completion: completion)
                    return
                }

                if sourceURL.path.hasPrefix(NSTemporaryDirectory()) {
                    try? FileManager.default.removeItem(at: sourceURL)
                }
                completion(.success(outputURL))
                return
            }

            try? FileManager.default.removeItem(at: outputURL)

            if !remainingPresets.isEmpty {
                self?.tryExport(asset: asset,
                                presets: remainingPresets,
                                sourceURL: sourceURL,
                                progress: progress,
                                completion: completion)
                return
            }

            if let inputBytes = self?.fileByteCount(at: sourceURL),
               inputBytes <= Self.skipCompressBelowBytes {
                self?.stageFile(at: sourceURL, deleteSourceIfTemporary: true, completion: completion)
            } else {
                completion(.failure(VideoSendError.preparationFailed))
            }
        }
    }

    private func exportPresetCandidates() -> [String] {
        var presets: [String] = []
        if #available(iOS 11.0, *) {
            presets.append(AVAssetExportPresetHEVC1920x1080)
        }
        presets.append(contentsOf: [
            AVAssetExportPreset1280x720,
            AVAssetExportPresetMediumQuality,
            AVAssetExportPreset640x480
        ])
        return presets
    }

    private func videoExceedsTargetResolution(_ asset: AVURLAsset) -> Bool {
        return Self.videoExceedsTargetResolution(asset)
    }

    private static func durationSeconds(for asset: AVURLAsset) -> Double {
        let status = asset.statusOfValue(forKey: "duration", error: nil)
        if status == .loaded {
            return CMTimeGetSeconds(asset.duration)
        }

        let group = DispatchGroup()
        group.enter()
        asset.loadValuesAsynchronously(forKeys: ["duration"]) {
            group.leave()
        }
        _ = group.wait(timeout: .now() + 15)

        return CMTimeGetSeconds(asset.duration)
    }

    static func videoExceedsTargetResolution(_ asset: AVURLAsset) -> Bool {
        guard let track = asset.tracks(withMediaType: .video).first else {
            return false
        }

        let transformed = track.naturalSize.applying(track.preferredTransform)
        let width = abs(transformed.width)
        let height = abs(transformed.height)
        return max(width, height) > 1280
    }

    private func stageFile(at sourceURL: URL,
                           deleteSourceIfTemporary: Bool,
                           completion: @escaping (Result<URL, Error>) -> Void) {
        let ext = sourceURL.pathExtension.isEmpty ? "mov" : sourceURL.pathExtension
        let stagedURL = temporaryOutputURL(extension: ext)

        do {
            if FileManager.default.fileExists(atPath: stagedURL.path) {
                try FileManager.default.removeItem(at: stagedURL)
            }
            try FileManager.default.copyItem(at: sourceURL, to: stagedURL)
            if deleteSourceIfTemporary, sourceURL.path.hasPrefix(NSTemporaryDirectory()) {
                try? FileManager.default.removeItem(at: sourceURL)
            }
            completion(.success(stagedURL))
        } catch {
            completion(.failure(error))
        }
    }

    private func temporaryOutputURL(extension ext: String) -> URL {
        URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent(UUID().uuidString)
            .appendingPathExtension(ext)
    }

    private func fileByteCount(at url: URL) -> Int64? {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        if let size = values?.fileSize {
            return Int64(size)
        }
        let attributes = try? FileManager.default.attributesOfItem(atPath: url.path)
        return attributes?[.size] as? Int64
    }
}

/// Lightweight banner while a video is being prepared — does not block the chat UI.
final class VideoSendProgressOverlay {
    static let shared = VideoSendProgressOverlay()

    private var passthroughHost: TouchPassthroughView?
    private var bannerView: UIView?
    private var progressView: UIProgressView?
    private var label: UILabel?
    private var cancelHandler: (() -> Void)?

    private init() {}

    func show(on viewController: UIViewController,
              message: String,
              onCancel: (() -> Void)? = nil) {
        DispatchQueue.main.async {
            self.hide()

            let host = TouchPassthroughView(frame: viewController.view.bounds)
            host.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            host.backgroundColor = .clear

            let banner = UIView()
            banner.backgroundColor = UIColor.black.withAlphaComponent(0.82)
            banner.layer.cornerRadius = 12
            banner.translatesAutoresizingMaskIntoConstraints = false

            let messageLabel = UILabel()
            messageLabel.text = message
            messageLabel.textColor = .white
            messageLabel.font = UIFont.preferredFont(forTextStyle: .subheadline)
            messageLabel.numberOfLines = 2
            messageLabel.translatesAutoresizingMaskIntoConstraints = false

            let progress = UIProgressView(progressViewStyle: .default)
            progress.progressTintColor = .white
            progress.trackTintColor = UIColor.white.withAlphaComponent(0.35)
            progress.translatesAutoresizingMaskIntoConstraints = false

            let cancelButton = UIButton(type: .system)
            cancelButton.setTitle(String(localized: "video_send_cancel"), for: .normal)
            cancelButton.setTitleColor(.white, for: .normal)
            cancelButton.titleLabel?.font = UIFont.preferredFont(forTextStyle: .subheadline)
            cancelButton.translatesAutoresizingMaskIntoConstraints = false
            cancelButton.addTarget(self, action: #selector(VideoSendProgressOverlay.cancelTapped), for: .touchUpInside)

            banner.addSubview(messageLabel)
            banner.addSubview(progress)
            banner.addSubview(cancelButton)
            host.addSubview(banner)
            viewController.view.addSubview(host)

            let guide = viewController.view.safeAreaLayoutGuide
            NSLayoutConstraint.activate([
                banner.leadingAnchor.constraint(equalTo: guide.leadingAnchor, constant: 12),
                banner.trailingAnchor.constraint(equalTo: guide.trailingAnchor, constant: -12),
                banner.bottomAnchor.constraint(equalTo: guide.bottomAnchor, constant: -72),

                messageLabel.topAnchor.constraint(equalTo: banner.topAnchor, constant: 12),
                messageLabel.leadingAnchor.constraint(equalTo: banner.leadingAnchor, constant: 14),
                messageLabel.trailingAnchor.constraint(lessThanOrEqualTo: cancelButton.leadingAnchor, constant: -8),

                cancelButton.centerYAnchor.constraint(equalTo: messageLabel.centerYAnchor),
                cancelButton.trailingAnchor.constraint(equalTo: banner.trailingAnchor, constant: -12),

                progress.topAnchor.constraint(equalTo: messageLabel.bottomAnchor, constant: 10),
                progress.leadingAnchor.constraint(equalTo: banner.leadingAnchor, constant: 14),
                progress.trailingAnchor.constraint(equalTo: banner.trailingAnchor, constant: -14),
                progress.bottomAnchor.constraint(equalTo: banner.bottomAnchor, constant: -12)
            ])

            self.passthroughHost = host
            self.bannerView = banner
            self.progressView = progress
            self.label = messageLabel
            self.cancelHandler = onCancel
        }
    }

    func update(progress: Float) {
        DispatchQueue.main.async {
            self.progressView?.setProgress(progress, animated: true)
        }
    }

    func hide() {
        DispatchQueue.main.async {
            self.passthroughHost?.removeFromSuperview()
            self.passthroughHost = nil
            self.bannerView = nil
            self.progressView = nil
            self.label = nil
            self.cancelHandler = nil
        }
    }

    @objc private func cancelTapped() {
        cancelHandler?()
    }
}

/// Passes touches through except on interactive subviews (banner).
private final class TouchPassthroughView: UIView {
    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return hit === self ? nil : hit
    }
}

/// Background thumbnail + duration for inline video bubbles in chat.
enum ChatFileMediaLoader {
    private static let queue = DispatchQueue(label: "khandaq.chat.filemedia", qos: .utility)
    private static let imageCache = NSCache<NSString, UIImage>()

    struct Preview {
        let image: UIImage
        let durationText: String?
    }

    static func isVideoFile(uti: String?, fileName: String?) -> Bool {
        if let uti = uti {
            let cf = uti as CFString
            if UTTypeConformsTo(cf, kUTTypeMovie) || UTTypeConformsTo(cf, kUTTypeVideo) || UTTypeConformsTo(cf, kUTTypeMPEG4) {
                return true
            }
        }

        guard let ext = fileName?.split(separator: ".").last?.lowercased() else {
            return false
        }

        switch ext {
            // KHANDAQ (#120): include MPEG-1/2 (.mpg/.mpeg) and other common containers — a sent .MPG
            // with no stored UTI was falling through to the generic-file bubble.
            case "mov", "mp4", "m4v", "avi", "mkv", "webm", "mpg", "mpeg", "mpe", "3gp", "3gpp", "wmv", "flv", "ts", "m2ts", "mts":
                return true
            default:
                return false
        }
    }

    static func cachedPreview(for path: String) -> Preview? {
        guard let image = imageCache.object(forKey: path as NSString) else {
            return nil
        }
        return Preview(image: image, durationText: nil)
    }

    static func loadPreview(at path: String, completion: @escaping (Preview?) -> Void) {
        if let cached = imageCache.object(forKey: path as NSString) {
            DispatchQueue.main.async {
                completion(Preview(image: cached, durationText: nil))
            }
            return
        }

        queue.async {
            let url = URL(fileURLWithPath: path)
            guard FileManager.default.fileExists(atPath: path) else {
                DispatchQueue.main.async { completion(nil) }
                return
            }

            let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: false])
            let generator = AVAssetImageGenerator(asset: asset)
            generator.appliesPreferredTrackTransform = true
            generator.maximumSize = CGSize(width: 360, height: 360)
            generator.requestedTimeToleranceBefore = kCMTimePositiveInfinity
            generator.requestedTimeToleranceAfter = kCMTimePositiveInfinity

            var frame: UIImage?
            do {
                let cgImage = try generator.copyCGImage(at: kCMTimeZero, actualTime: nil)
                frame = UIImage(cgImage: cgImage)
            } catch {
                frame = nil
            }

            guard let image = frame else {
                DispatchQueue.main.async { completion(nil) }
                return
            }

            imageCache.setObject(image, forKey: path as NSString)

            let seconds = CMTimeGetSeconds(asset.duration)
            let durationText = seconds.isFinite && seconds > 0 ? String(timeInterval: seconds) : nil
            let preview = Preview(image: image, durationText: durationText)

            DispatchQueue.main.async {
                completion(preview)
            }
        }
    }
}

/// Alerts when an active file transfer stops reporting progress.
final class FileTransferStallWatcher {
    static let shared = FileTransferStallWatcher()

    static let stallInterval: TimeInterval = 30

    private var timers: [String: Timer] = [:]
    private var handlers: [String: () -> Void] = [:]

    private init() {}

    func track(messageId: String, onStall: @escaping () -> Void) {
        DispatchQueue.main.async {
            self.handlers[messageId] = onStall
            self.resetTimer(for: messageId)
        }
    }

    func progressReceived(for messageId: String) {
        DispatchQueue.main.async {
            guard self.handlers[messageId] != nil else {
                return
            }
            self.resetTimer(for: messageId)
        }
    }

    func stop(messageId: String) {
        DispatchQueue.main.async {
            self.timers[messageId]?.invalidate()
            self.timers.removeValue(forKey: messageId)
            self.handlers.removeValue(forKey: messageId)
        }
    }

    private func resetTimer(for messageId: String) {
        timers[messageId]?.invalidate()
        timers[messageId] = Timer.scheduledTimer(withTimeInterval: Self.stallInterval, repeats: false) { [weak self] _ in
            guard let self = self else {
                return
            }
            let handler = self.handlers[messageId]
            self.stop(messageId: messageId)
            handler?()
        }
    }
}
