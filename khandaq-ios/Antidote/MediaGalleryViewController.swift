// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
//
// KHANDAQ (Figma): custom in-chat media viewer replacing QuickLook.
// Fullscreen swipe gallery across all chat media, pinch-zoom photos, AVPlayer
// video, and an overlay with back + sender/date, forward/delete, and a
// thumbnail strip for multi-item sets.

import AVFoundation
import AVKit
import MobileCoreServices
import SnapKit
import UIKit

struct GalleryItem {
    let url: URL
    let isVideo: Bool
    let senderName: String
    let date: Date
}

protocol MediaGalleryViewControllerDelegate: AnyObject {
    func mediaGallery(_ controller: MediaGalleryViewController, didRequestForwardAt index: Int)
    func mediaGallery(_ controller: MediaGalleryViewController, didRequestDeleteAt index: Int)
}

final class MediaGalleryViewController: UIViewController {
    weak var delegate: MediaGalleryViewControllerDelegate?

    private let items: [GalleryItem]
    private var currentIndex: Int

    private static let brandTeal = UIColor(red: 0.01, green: 0.61, blue: 0.49, alpha: 1)
    private static let thumbSize: CGFloat = 48

    private let collectionView: UICollectionView
    private let topBar = UIView()
    private let backButton = UIButton(type: .system)
    private let titleLabel = UILabel()
    private let dateLabel = UILabel()
    private let titleStack = UIStackView()
    private let forwardButton = UIButton(type: .system)
    private let deleteButton = UIButton(type: .system)
    private let thumbsCollection: UICollectionView

    // KHANDAQ (#207): tap a photo to hide the top bar + action row for an edge-to-edge view.
    private var chromeHidden = false

    init(items: [GalleryItem], startIndex: Int) {
        self.items = items
        self.currentIndex = max(0, min(startIndex, max(0, items.count - 1)))

        let layout = UICollectionViewFlowLayout()
        layout.scrollDirection = .horizontal
        layout.minimumLineSpacing = 0
        layout.minimumInteritemSpacing = 0
        collectionView = UICollectionView(frame: .zero, collectionViewLayout: layout)

        let thumbLayout = UICollectionViewFlowLayout()
        thumbLayout.scrollDirection = .horizontal
        thumbLayout.itemSize = CGSize(width: MediaGalleryViewController.thumbSize,
                                      height: MediaGalleryViewController.thumbSize)
        thumbLayout.minimumLineSpacing = 6
        thumbsCollection = UICollectionView(frame: .zero, collectionViewLayout: thumbLayout)

        super.init(nibName: nil, bundle: nil)
        modalPresentationStyle = .fullScreen
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        installPager()
        installOverlay()
        DispatchQueue.main.async { [weak self] in
            guard let self = self, self.items.indices.contains(self.currentIndex) else { return }
            self.collectionView.scrollToItem(at: IndexPath(item: self.currentIndex, section: 0),
                                             at: .centeredHorizontally, animated: false)
            self.updateOverlayForCurrentItem()
            self.syncThumbStrip()
        }
    }

    override var prefersStatusBarHidden: Bool { true }

    private func installPager() {
        collectionView.isPagingEnabled = true
        collectionView.showsHorizontalScrollIndicator = false
        collectionView.backgroundColor = .black
        collectionView.dataSource = self
        collectionView.delegate = self
        collectionView.register(MediaPageCell.self, forCellWithReuseIdentifier: MediaPageCell.reuseId)
        if #available(iOS 11.0, *) {
            collectionView.contentInsetAdjustmentBehavior = .never
        }
        view.addSubview(collectionView)
        collectionView.snp.makeConstraints { make in
            make.edges.equalToSuperview()
        }
    }

    private func installOverlay() {
        // Top bar: back + (sender name / date)
        topBar.backgroundColor = .clear
        view.addSubview(topBar)
        topBar.snp.makeConstraints { make in
            make.top.leading.trailing.equalToSuperview()
        }

        backButton.setTitle("‹", for: .normal)
        backButton.setTitleColor(.white, for: .normal)
        backButton.titleLabel?.font = .systemFont(ofSize: 30, weight: .medium)
        backButton.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        backButton.layer.cornerRadius = 20
        backButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        topBar.addSubview(backButton)
        backButton.snp.makeConstraints { make in
            make.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(6)
            make.leading.equalToSuperview().offset(10)
            make.width.height.equalTo(40)
            make.bottom.equalToSuperview().offset(-10)
        }

        titleLabel.textColor = .white
        titleLabel.font = .systemFont(ofSize: 15, weight: .semibold)
        titleLabel.textAlignment = .center
        dateLabel.textColor = UIColor.white.withAlphaComponent(0.8)
        dateLabel.font = .systemFont(ofSize: 12)
        dateLabel.textAlignment = .center

        titleStack.axis = .vertical
        titleStack.alignment = .center
        titleStack.spacing = 1
        titleStack.addArrangedSubview(titleLabel)
        titleStack.addArrangedSubview(dateLabel)
        titleStack.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        titleStack.isLayoutMarginsRelativeArrangement = true
        titleStack.layoutMargins = UIEdgeInsets(top: 4, left: 14, bottom: 4, right: 14)
        titleStack.layer.cornerRadius = 16
        titleStack.clipsToBounds = true
        topBar.addSubview(titleStack)
        titleStack.snp.makeConstraints { make in
            make.centerX.equalToSuperview()
            make.centerY.equalTo(backButton)
        }

        // Bottom controls: forward (left) + delete (right), thumbnails above
        forwardButton.setTitle("↪", for: .normal)
        forwardButton.setTitleColor(.white, for: .normal)
        forwardButton.titleLabel?.font = .systemFont(ofSize: 22)
        forwardButton.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        forwardButton.layer.cornerRadius = 22
        forwardButton.addTarget(self, action: #selector(forwardTapped), for: .touchUpInside)
        view.addSubview(forwardButton)
        forwardButton.snp.makeConstraints { make in
            make.leading.equalToSuperview().offset(16)
            make.bottom.equalTo(view.safeAreaLayoutGuide.snp.bottom).offset(-14)
            make.width.height.equalTo(44)
        }

        deleteButton.setTitle("🗑", for: .normal)
        deleteButton.titleLabel?.font = .systemFont(ofSize: 20)
        deleteButton.backgroundColor = UIColor.black.withAlphaComponent(0.35)
        deleteButton.layer.cornerRadius = 22
        deleteButton.addTarget(self, action: #selector(deleteTapped), for: .touchUpInside)
        view.addSubview(deleteButton)
        deleteButton.snp.makeConstraints { make in
            make.trailing.equalToSuperview().offset(-16)
            make.centerY.equalTo(forwardButton)
            make.width.height.equalTo(44)
        }

        // forward/delete require message-level actions wired to the submanagers; hidden until
        // that logic is verified on-device (viewer replaces QuickLook's view/zoom/video meanwhile).
        forwardButton.isHidden = true
        deleteButton.isHidden = true

        // Thumbnail strip (only for multi-item sets)
        thumbsCollection.backgroundColor = .clear
        thumbsCollection.showsHorizontalScrollIndicator = false
        thumbsCollection.dataSource = self
        thumbsCollection.delegate = self
        thumbsCollection.register(ThumbCell.self, forCellWithReuseIdentifier: ThumbCell.reuseId)
        thumbsCollection.isHidden = items.count <= 1
        view.addSubview(thumbsCollection)
        thumbsCollection.snp.makeConstraints { make in
            make.leading.equalTo(forwardButton.snp.trailing).offset(10)
            make.trailing.equalTo(deleteButton.snp.leading).offset(-10)
            make.centerY.equalTo(forwardButton)
            make.height.equalTo(Self.thumbSize)
        }
    }

    private func updateOverlayForCurrentItem() {
        guard items.indices.contains(currentIndex) else { return }
        let item = items[currentIndex]
        titleLabel.text = item.senderName
        titleLabel.isHidden = item.senderName.isEmpty
        dateLabel.text = DateFormatter.galleryFormatter.string(from: item.date)
    }

    @objc private func closeTapped() {
        dismiss(animated: true, completion: nil)
    }

    // KHANDAQ (#207): fade the chrome in/out. Alpha (not isHidden) so already-hidden controls
    // (forward/delete when no delegate action, single-item thumb strip) stay hidden.
    private func toggleChrome() {
        chromeHidden.toggle()
        let a: CGFloat = chromeHidden ? 0 : 1
        UIView.animate(withDuration: 0.22) {
            self.topBar.alpha = a
            self.forwardButton.alpha = a
            self.deleteButton.alpha = a
            self.thumbsCollection.alpha = a
        }
    }

    @objc private func forwardTapped() {
        delegate?.mediaGallery(self, didRequestForwardAt: currentIndex)
    }

    @objc private func deleteTapped() {
        delegate?.mediaGallery(self, didRequestDeleteAt: currentIndex)
    }

    static func isVideoFile(_ name: String) -> Bool {
        let ext = (name as NSString).pathExtension.lowercased()
        return ["mp4", "mov", "m4v", "3gp", "3gpp", "mkv", "webm", "avi"].contains(ext)
    }
}

extension MediaGalleryViewController: UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        return items.count
    }

    func collectionView(_ collectionView: UICollectionView,
                        cellForItemAt indexPath: IndexPath) -> UICollectionViewCell {
        if collectionView == thumbsCollection {
            let cell = collectionView.dequeueReusableCell(withReuseIdentifier: ThumbCell.reuseId,
                                                          for: indexPath) as! ThumbCell
            cell.configure(with: items[indexPath.item], selected: indexPath.item == currentIndex)
            return cell
        }
        let cell = collectionView.dequeueReusableCell(withReuseIdentifier: MediaPageCell.reuseId,
                                                      for: indexPath) as! MediaPageCell
        cell.configure(with: items[indexPath.item])
        cell.onSingleTap = { [weak self] in self?.toggleChrome() }
        return cell
    }

    func collectionView(_ collectionView: UICollectionView,
                        layout collectionViewLayout: UICollectionViewLayout,
                        sizeForItemAt indexPath: IndexPath) -> CGSize {
        if collectionView == thumbsCollection {
            return CGSize(width: Self.thumbSize, height: Self.thumbSize)
        }
        return collectionView.bounds.size
    }

    func collectionView(_ collectionView: UICollectionView, didSelectItemAt indexPath: IndexPath) {
        guard collectionView == thumbsCollection else { return }
        collectionView.deselectItem(at: indexPath, animated: false)
        self.collectionView.scrollToItem(at: IndexPath(item: indexPath.item, section: 0),
                                         at: .centeredHorizontally, animated: true)
        currentIndex = indexPath.item
        updateOverlayForCurrentItem()
        syncThumbStrip()
    }

    // Telegram-style: the strip follows the page — selection ring + keep the
    // current thumb centered/visible (reloadData alone never scrolls the strip).
    private func syncThumbStrip() {
        thumbsCollection.reloadData()
        guard items.indices.contains(currentIndex) else { return }
        thumbsCollection.performBatchUpdates(nil) { [weak self] _ in
            guard let self = self else { return }
            self.thumbsCollection.scrollToItem(at: IndexPath(item: self.currentIndex, section: 0),
                                               at: .centeredHorizontally, animated: true)
        }
    }

    func scrollViewDidEndDecelerating(_ scrollView: UIScrollView) {
        guard scrollView == collectionView else { return }
        let page = Int(round(scrollView.contentOffset.x / max(1, scrollView.bounds.width)))
        if page != currentIndex, items.indices.contains(page) {
            currentIndex = page
            updateOverlayForCurrentItem()
            syncThumbStrip()
        }
        // Pause any video that scrolled off-screen.
        for case let cell as MediaPageCell in collectionView.visibleCells where cell.isVideoCell {
            if collectionView.indexPath(for: cell)?.item != currentIndex { cell.pauseVideo() }
        }
    }
}

private extension DateFormatter {
    static let galleryFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .medium
        f.timeStyle = .short
        return f
    }()
}
