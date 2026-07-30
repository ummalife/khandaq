// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// KHANDAQ (Figma): cells for the custom media gallery — a zoomable photo /
// tap-to-play AVPlayer video page, and a thumbnail cell for the strip.

import AVFoundation
import SnapKit
import UIKit

final class MediaPageCell: UICollectionViewCell, UIScrollViewDelegate {
    static let reuseId = "MediaPageCell"

    private let scrollView = UIScrollView()
    private let imageView = UIImageView()
    private let playButton = UIButton(type: .system)
    private let unavailableLabel = UILabel()
    private var player: AVPlayer?
    private var playerLayer: AVPlayerLayer?

    private(set) var isVideoCell = false
    private var videoURL: URL?

    // KHANDAQ (#207): single tap toggles the gallery chrome (top bar + action row) so a photo
    // can be viewed edge-to-edge; the owning gallery VC supplies the toggle.
    var onSingleTap: (() -> Void)?

    override init(frame: CGRect) {
        super.init(frame: frame)
        contentView.backgroundColor = .black

        scrollView.delegate = self
        scrollView.minimumZoomScale = 1
        scrollView.maximumZoomScale = 4
        scrollView.showsHorizontalScrollIndicator = false
        scrollView.showsVerticalScrollIndicator = false
        if #available(iOS 11.0, *) {
            scrollView.contentInsetAdjustmentBehavior = .never
        }
        contentView.addSubview(scrollView)
        scrollView.snp.makeConstraints { $0.edges.equalToSuperview() }

        imageView.contentMode = .scaleAspectFit
        imageView.isUserInteractionEnabled = true
        scrollView.addSubview(imageView)
        imageView.snp.makeConstraints { $0.edges.equalToSuperview(); $0.size.equalTo(scrollView) }

        let doubleTap = UITapGestureRecognizer(target: self, action: #selector(handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        scrollView.addGestureRecognizer(doubleTap)

        // KHANDAQ (#207): single tap → toggle chrome; must wait out the double-tap-to-zoom.
        let singleTap = UITapGestureRecognizer(target: self, action: #selector(handleSingleTap(_:)))
        singleTap.numberOfTapsRequired = 1
        singleTap.require(toFail: doubleTap)
        scrollView.addGestureRecognizer(singleTap)

        playButton.setTitle("▶", for: .normal)
        playButton.titleLabel?.font = .systemFont(ofSize: 40, weight: .bold)
        playButton.setTitleColor(.white, for: .normal)
        playButton.backgroundColor = UIColor.black.withAlphaComponent(0.4)
        playButton.layer.cornerRadius = 36
        playButton.isHidden = true
        playButton.addTarget(self, action: #selector(playTapped), for: .touchUpInside)
        contentView.addSubview(playButton)
        playButton.snp.makeConstraints { make in
            make.center.equalToSuperview()
            make.width.height.equalTo(72)
        }

        unavailableLabel.text = String(localized: "media_file_unavailable")
        unavailableLabel.textColor = UIColor.white.withAlphaComponent(0.6)
        unavailableLabel.font = .systemFont(ofSize: 15)
        unavailableLabel.textAlignment = .center
        unavailableLabel.numberOfLines = 0
        unavailableLabel.isHidden = true
        contentView.addSubview(unavailableLabel)
        unavailableLabel.snp.makeConstraints { make in
            make.center.equalToSuperview()
            make.leading.greaterThanOrEqualToSuperview().offset(24)
            make.trailing.lessThanOrEqualToSuperview().offset(-24)
        }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func layoutSubviews() {
        super.layoutSubviews()
        playerLayer?.frame = contentView.bounds
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        teardownVideo()
        imageView.image = nil
        scrollView.zoomScale = 1
        isVideoCell = false
        videoURL = nil
        playButton.isHidden = true
        unavailableLabel.isHidden = true
    }

    func configure(with item: GalleryItem) {
        isVideoCell = item.isVideo
        videoURL = item.url
        unavailableLabel.isHidden = true
        if item.isVideo {
            let frame = Self.videoFrame(for: item.url)
            imageView.image = frame
            let missing = (frame == nil) && !FileManager.default.fileExists(atPath: item.url.path)
            playButton.isHidden = missing
            unavailableLabel.isHidden = !missing
            scrollView.maximumZoomScale = 1
        } else {
            scrollView.maximumZoomScale = 4
            playButton.isHidden = true
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                let img = UIImage(contentsOfFile: item.url.path)
                DispatchQueue.main.async {
                    guard let self = self, !self.isVideoCell else { return }
                    self.imageView.image = img
                    self.unavailableLabel.isHidden = (img != nil)
                }
            }
        }
    }

    @objc private func playTapped() {
        guard let url = videoURL else { return }
        teardownVideo()
        let p = AVPlayer(url: url)
        let layer = AVPlayerLayer(player: p)
        layer.frame = contentView.bounds
        layer.videoGravity = .resizeAspect
        contentView.layer.insertSublayer(layer, above: scrollView.layer)
        player = p
        playerLayer = layer
        playButton.isHidden = true
        imageView.isHidden = true
        p.play()
    }

    func pauseVideo() {
        player?.pause()
    }

    private func teardownVideo() {
        player?.pause()
        playerLayer?.removeFromSuperlayer()
        player = nil
        playerLayer = nil
        imageView.isHidden = false
    }

    func viewForZooming(in scrollView: UIScrollView) -> UIView? {
        return isVideoCell ? nil : imageView
    }

    @objc private func handleDoubleTap(_ gesture: UITapGestureRecognizer) {
        guard !isVideoCell else { return }
        scrollView.setZoomScale(scrollView.zoomScale > 1 ? 1 : 2.5, animated: true)
    }

    @objc private func handleSingleTap(_ gesture: UITapGestureRecognizer) {
        onSingleTap?()
    }

    static func videoFrame(for url: URL) -> UIImage? {
        guard FileManager.default.fileExists(atPath: url.path) else { return nil }
        let asset = AVURLAsset(url: url, options: [AVURLAssetPreferPreciseDurationAndTimingKey: false])
        let generator = AVAssetImageGenerator(asset: asset)
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSize(width: 1280, height: 1280)
        generator.requestedTimeToleranceBefore = kCMTimePositiveInfinity
        generator.requestedTimeToleranceAfter = kCMTimePositiveInfinity
        return (try? generator.copyCGImage(at: kCMTimeZero, actualTime: nil)).map(UIImage.init)
    }
}

final class ThumbCell: UICollectionViewCell {
    static let reuseId = "ThumbCell"

    private let imageView = UIImageView()
    private let videoBadge = UILabel()

    override init(frame: CGRect) {
        super.init(frame: frame)
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.layer.cornerRadius = 6
        imageView.layer.borderColor = UIColor(red: 0.01, green: 0.61, blue: 0.49, alpha: 1).cgColor
        contentView.addSubview(imageView)
        imageView.snp.makeConstraints { $0.edges.equalToSuperview() }

        videoBadge.text = "▶"
        videoBadge.font = .systemFont(ofSize: 12)
        videoBadge.textColor = .white
        videoBadge.textAlignment = .center
        videoBadge.isHidden = true
        contentView.addSubview(videoBadge)
        videoBadge.snp.makeConstraints { $0.center.equalToSuperview() }
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func prepareForReuse() {
        super.prepareForReuse()
        imageView.image = nil
    }

    func configure(with item: GalleryItem, selected: Bool) {
        imageView.layer.borderWidth = selected ? 2 : 0
        videoBadge.isHidden = !item.isVideo
        let url = item.url
        let isVideo = item.isVideo
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let img = isVideo ? MediaPageCell.videoFrame(for: url) : UIImage(contentsOfFile: url.path)
            DispatchQueue.main.async { self?.imageView.image = img }
        }
    }
}
