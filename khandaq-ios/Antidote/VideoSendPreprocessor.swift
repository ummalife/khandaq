// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import ImageIO
import MobileCoreServices
import PhotosUI
import UIKit

enum VideoSendError: LocalizedError {
    case fileTooLarge
    case preparationFailed
    case busy
    case durationTooLong
    /// Cancel on the banner: nothing to say, the person asked for it.
    case cancelled

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
        case .cancelled:
            return nil
        }
    }

    static func isCancellation(_ error: Error) -> Bool {
        return (error as? VideoSendError) == .cancelled
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
    // processingQueue only.
    private var isProcessing = false
    /// KHANDAQ (large media): Cancel on the banner for the preparation under way. Its export chain stops
    /// at the next step instead of going on with the next preset, and it ends as cancelled instead of
    /// handing a file over to be sent. The preparation stays the one in progress until then.
    private var isCancelled = false
    private var activeExportSession: AVAssetExportSession?
    /// KHANDAQ (review 0.2.56): the number of the preparation under way (processingQueue only), and the
    /// last number given out (under `preparationNumbers`). A chat cancels only its own preparation: one
    /// that cancelled whatever was under way stopped the video of another chat.
    private var activePreparation = 0
    private let preparationNumbers = NSLock()
    private var lastPreparation = 0

    private init() {}

    /// Prepares the video and returns the number of this preparation for `cancelPreparation`. One video
    /// is prepared at a time: another one asked for meanwhile ends as `busy`.
    @discardableResult
    func prepareVideo(at sourceURL: URL,
                      progress: @escaping (Float) -> Void,
                      completion: @escaping (Result<URL, Error>) -> Void) -> Int {
        preparationNumbers.lock()
        lastPreparation += 1
        let number = lastPreparation
        preparationNumbers.unlock()

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
            self.isCancelled = false
            self.activePreparation = number
            autoreleasepool {
                self.prepareVideoOnBackground(at: sourceURL, progress: progress) { result in
                    // An export ends on a queue of AVFoundation's; the state is kept on ours.
                    self.processingQueue.async {
                        let cancelled = self.isCancelled
                        self.isProcessing = false
                        self.isCancelled = false
                        self.activePreparation = 0
                        self.activeExportSession = nil
                        if cancelled, case .success(let preparedURL) = result {
                            try? FileManager.default.removeItem(at: preparedURL)
                        }
                        DispatchQueue.main.async {
                            completion(cancelled ? .failure(VideoSendError.cancelled) : result)
                        }
                    }
                }
            }
        }
        return number
    }

    /// Stops the preparation with this number while it is under way: its completion then reports
    /// `VideoSendError.cancelled`. Another preparation, or one that has ended, is left alone.
    func cancelPreparation(_ number: Int) {
        processingQueue.async { [weak self] in
            guard let self = self, self.isProcessing, self.activePreparation == number else {
                return
            }
            self.isCancelled = true
            self.activeExportSession?.cancelExport()
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
            // The handler runs on a queue of AVFoundation's: the rest goes on ours, where Cancel is kept.
            self?.processingQueue.async {
                progressTimer.cancel()
                self?.exportFinished(export,
                                     outputURL: outputURL,
                                     asset: asset,
                                     remainingPresets: remainingPresets,
                                     sourceURL: sourceURL,
                                     progress: progress,
                                     completion: completion)
            }
        }
    }

    /// The end of one export, on processingQueue: the file, or the next preset to try. After Cancel
    /// nothing more is tried.
    private func exportFinished(_ export: AVAssetExportSession,
                                outputURL: URL,
                                asset: AVURLAsset,
                                remainingPresets: [String],
                                sourceURL: URL,
                                progress: @escaping (Float) -> Void,
                                completion: @escaping (Result<URL, Error>) -> Void) {
        activeExportSession = nil

        if isCancelled {
            try? FileManager.default.removeItem(at: outputURL)
            completion(.failure(VideoSendError.cancelled))
            return
        }

        if export.status == .completed {
            if let outputBytes = fileByteCount(at: outputURL),
               outputBytes > Self.maxOutputBytes,
               !remainingPresets.isEmpty {
                try? FileManager.default.removeItem(at: outputURL)
                tryExport(asset: asset,
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
            tryExport(asset: asset,
                      presets: remainingPresets,
                      sourceURL: sourceURL,
                      progress: progress,
                      completion: completion)
            return
        }

        if let inputBytes = fileByteCount(at: sourceURL),
           inputBytes <= Self.skipCompressBelowBytes {
            stageFile(at: sourceURL, deleteSourceIfTemporary: true, completion: completion)
        } else {
            completion(.failure(VideoSendError.preparationFailed))
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
    /// KHANDAQ (large media): which banner is up (main queue), so that the end of an older job does not
    /// take down the banner of a newer one.
    private var shownBanner = 0
    private let bannerNumbers = NSLock()
    private var lastBanner = 0

    private init() {}

    /// Puts the banner up in place of any other and returns its number for `update` and `hide`.
    @discardableResult
    func show(on viewController: UIViewController,
              message: String,
              onCancel: (() -> Void)? = nil) -> Int {
        bannerNumbers.lock()
        lastBanner += 1
        let number = lastBanner
        bannerNumbers.unlock()
        DispatchQueue.main.async {
            // KHANDAQ (large media): taken down here and now. hide() goes through the main queue, so
            // called from here it ran after the new banner was up and took that one down at once.
            self.removeBanner()

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
            self.shownBanner = number
        }
        return number
    }

    /// The progress of the banner shown; with a number, only while that banner is the one shown.
    func update(progress: Float, banner: Int? = nil) {
        DispatchQueue.main.async {
            guard banner == nil || banner == self.shownBanner else {
                return
            }
            self.progressView?.setProgress(progress, animated: true)
        }
    }

    /// Takes the banner down; with a number, only while that banner is the one shown.
    func hide(banner: Int? = nil) {
        DispatchQueue.main.async {
            guard banner == nil || banner == self.shownBanner else {
                return
            }
            self.removeBanner()
        }
    }

    /// Main queue only.
    private func removeBanner() {
        passthroughHost?.removeFromSuperview()
        passthroughHost = nil
        bannerView = nil
        progressView = nil
        label = nil
        cancelHandler = nil
        shownBanner = 0
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

/// KHANDAQ (large media): what the photo picker hands over, loaded for the send preview. A big video,
/// or one still in iCloud, can take a long while to arrive: once that is more than a moment, the banner
/// says so, with the progress and Cancel. Each item keeps the place it was chosen in. A video is copied
/// into app storage inside its provider's completion, as the picker's temporary file is gone once that
/// returns.
@available(iOS 14.0, *)
enum PickedMediaLoader {
    struct Loaded {
        /// In the order of the picker's results; an item that could not be loaded is left out.
        let items: [MediaSendPreviewItem]
        /// Why the copy of a chosen video failed (too large, no room), for the first one that did.
        let stagingError: Error?
    }

    /// How long loading goes on before the banner comes up: a photo on the phone is there before that.
    static let bannerDelay: TimeInterval = 0.3

    /// `completion` runs on the main queue once every item has answered, unless the person cancelled
    /// from the banner: the videos copied by then are removed and nothing is reported. `bannerHost` nil
    /// loads without the banner (another one is up, for a video being prepared).
    static func load(_ results: [PHPickerResult],
                     movieTypes: [String],
                     bannerHost: UIViewController?,
                     message: String,
                     completion: @escaping (Loaded) -> Void) {
        let count = results.count
        var slots = [MediaSendPreviewItem?](repeating: nil, count: count)
        var stagingError: Error?
        let lock = NSLock()
        let group = DispatchGroup()
        let progress = Progress(totalUnitCount: Int64(max(count, 1)))

        func store(_ item: MediaSendPreviewItem?, at index: Int, error: Error? = nil) {
            lock.lock()
            slots[index] = item
            if stagingError == nil {
                stagingError = error
            }
            lock.unlock()
        }

        for (index, result) in results.enumerated() {
            let provider = result.itemProvider
            if let movieType = movieTypes.first(where: { provider.hasItemConformingToTypeIdentifier($0) }) {
                group.enter()
                let loading = provider.loadFileRepresentation(forTypeIdentifier: movieType) { url, _ in
                    defer { group.leave() }
                    guard let url = url else {
                        return
                    }
                    do {
                        let staged = try VideoSendPreprocessor.shared.stagePickerVideo(at: url)
                        store(.video(staged, fileName: provider.suggestedName), at: index)
                    }
                    catch {
                        store(nil, at: index, error: error)
                    }
                }
                progress.addChild(loading, withPendingUnitCount: 1)
            }
            else if provider.canLoadObject(ofClass: UIImage.self) {
                group.enter()
                let loading = provider.loadObject(ofClass: UIImage.self) { object, _ in
                    defer { group.leave() }
                    if let image = object as? UIImage {
                        store(.image(image, fileName: nil), at: index)
                    }
                }
                progress.addChild(loading, withPendingUnitCount: 1)
            }
            else {
                progress.completedUnitCount += 1
            }
        }

        // The rest on the main queue.
        var finished = false
        var cancelled = false
        var banner: Int?
        var ticker: Timer?
        if let host = bannerHost {
            DispatchQueue.main.asyncAfter(deadline: .now() + bannerDelay) {
                guard !finished, !cancelled else {
                    return
                }
                banner = VideoSendProgressOverlay.shared.show(on: host, message: message, onCancel: {
                    cancelled = true
                    progress.cancel()
                    VideoSendProgressOverlay.shared.hide()
                })
                ticker = Timer.scheduledTimer(withTimeInterval: 0.2, repeats: true) { _ in
                    VideoSendProgressOverlay.shared.update(progress: Float(progress.fractionCompleted), banner: banner)
                }
            }
        }

        group.notify(queue: .main) {
            finished = true
            ticker?.invalidate()
            if let banner = banner {
                VideoSendProgressOverlay.shared.hide(banner: banner)
            }
            let items = slots.compactMap { $0 }
            guard !cancelled else {
                for case .video(let url, _) in items {
                    try? FileManager.default.removeItem(at: url)
                }
                return
            }
            completion(Loaded(items: items, stagingError: stagingError))
        }
    }
}

/// Background thumbnail + duration for inline video bubbles in chat, and the reading of a photo's size
/// and preview. Neither depends on how large the file is.
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

    /// The pixel size of a photo as it is seen: a photo turned by its EXIF orientation (a portrait taken
    /// with the phone on its side) is as tall as it is shown, not as it is stored. Reads the header only.
    static func imagePixelSize(at path: String) -> CGSize? {
        let options = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, options),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, options) as? [CFString: Any],
              let width = (props[kCGImagePropertyPixelWidth] as? NSNumber)?.doubleValue,
              let height = (props[kCGImagePropertyPixelHeight] as? NSNumber)?.doubleValue,
              width > 0, height > 0 else {
            return nil
        }
        // Orientations 5 to 8 turn the picture by a quarter.
        let orientation = (props[kCGImagePropertyOrientation] as? NSNumber)?.intValue ?? 1
        return (5...8).contains(orientation) ? CGSize(width: height, height: width) : CGSize(width: width, height: height)
    }

    /// A photo of any size read down to `maxPixelSide` on its longer side and turned by its EXIF
    /// orientation. ImageIO decodes only what the smaller picture needs, so a camera original of tens of
    /// megabytes costs the memory of its preview, where UIImage(contentsOfFile:) decoded all of it.
    static func downsampledImage(at path: String, maxPixelSide: CGFloat) -> UIImage? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard maxPixelSide >= 1,
              let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, sourceOptions) else {
            return nil
        }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: Int(maxPixelSide.rounded(.up)),
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else {
            return nil
        }
        return UIImage(cgImage: image)
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

    /// KHANDAQ (0.2.57): longer than toxcore's friend timeout (32 s). A transfer whose connection went quiet then
    /// waits for the connection and goes on by itself once it is back (OCTSubmanagerFilesImpl); at 30 s the
    /// watcher cancelled it first. As long as objcTox's own download watchdog.
    static let stallInterval: TimeInterval = 90

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
