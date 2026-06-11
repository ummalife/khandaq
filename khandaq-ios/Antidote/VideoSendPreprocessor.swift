// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
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
    static let maxOutputBytes: Int64 = 100 * 1024 * 1024
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

        let durationSeconds = CMTimeGetSeconds(asset.duration)
        if durationSeconds.isFinite, durationSeconds > Self.maxDurationSeconds {
            completion(.failure(VideoSendError.durationTooLong))
            return
        }

        let needsTranscode = inputBytes > Self.skipCompressBelowBytes
            || videoExceedsTargetResolution(asset)
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
            DispatchQueue.main.async {
                progress(export.progress)
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

/// Lightweight modal progress while a video is being prepared for send.
final class VideoSendProgressOverlay {
    static let shared = VideoSendProgressOverlay()

    private var containerView: UIView?
    private var progressView: UIProgressView?
    private var label: UILabel?

    private init() {}

    func show(on viewController: UIViewController, message: String) {
        DispatchQueue.main.async {
            self.hide()

            let container = UIView()
            container.backgroundColor = UIColor.black.withAlphaComponent(0.55)
            container.layer.cornerRadius = 12
            container.translatesAutoresizingMaskIntoConstraints = false

            let messageLabel = UILabel()
            messageLabel.text = message
            messageLabel.textColor = .white
            messageLabel.font = UIFont.preferredFont(forTextStyle: .subheadline)
            messageLabel.textAlignment = .center
            messageLabel.numberOfLines = 0
            messageLabel.translatesAutoresizingMaskIntoConstraints = false

            let progress = UIProgressView(progressViewStyle: .default)
            progress.progressTintColor = .white
            progress.trackTintColor = UIColor.white.withAlphaComponent(0.35)
            progress.translatesAutoresizingMaskIntoConstraints = false

            container.addSubview(messageLabel)
            container.addSubview(progress)
            viewController.view.addSubview(container)

            NSLayoutConstraint.activate([
                container.centerXAnchor.constraint(equalTo: viewController.view.centerXAnchor),
                container.centerYAnchor.constraint(equalTo: viewController.view.centerYAnchor),
                container.widthAnchor.constraint(lessThanOrEqualToConstant: 280),
                container.leadingAnchor.constraint(greaterThanOrEqualTo: viewController.view.leadingAnchor, constant: 32),
                container.trailingAnchor.constraint(lessThanOrEqualTo: viewController.view.trailingAnchor, constant: -32),

                messageLabel.topAnchor.constraint(equalTo: container.topAnchor, constant: 16),
                messageLabel.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
                messageLabel.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16),

                progress.topAnchor.constraint(equalTo: messageLabel.bottomAnchor, constant: 12),
                progress.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: 16),
                progress.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -16),
                progress.bottomAnchor.constraint(equalTo: container.bottomAnchor, constant: -16)
            ])

            self.containerView = container
            self.progressView = progress
            self.label = messageLabel
        }
    }

    func update(progress: Float) {
        DispatchQueue.main.async {
            self.progressView?.setProgress(progress, animated: true)
        }
    }

    func hide() {
        DispatchQueue.main.async {
            self.containerView?.removeFromSuperview()
            self.containerView = nil
            self.progressView = nil
            self.label = nil
        }
    }
}
