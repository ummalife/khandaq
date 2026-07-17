// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import MobileCoreServices
import SnapKit

class LoadingImageView: UIView {
    struct Constants {
        static let ImageButtonSize: CGFloat = 180.0
        static let LabelHorizontalOffset = 12.0
        static let LabelBottomOffset = -6.0
        static let CenterImageSize = 50.0
        static let ProgressViewSize = 70.0
        // KHANDAQ (#15): bounds for the aspect-ratio media preview bubble.
        static let PreviewMaxWidth: CGFloat = 240.0
        static let PreviewMaxHeight: CGFloat = 300.0
        // KHANDAQ (file bubble): compact Telegram-style row for non-preview file states —
        // action circle + progress ring on the LEFT, filename/size stacked on the right
        // (the old layout put a huge centered icon + ring on top of the labels).
        static let CompactWidth: CGFloat = 240.0
        static let CompactHeight: CGFloat = 64.0
        static let CompactIconSize: CGFloat = 26.0
        static let CompactRingSize: CGFloat = 44.0
    }

    private var isCompactLayout = false

    var imageButton: UIButton!
    var progressView: ProgressCircleView!
    var centerImageView: UIImageView!
    var topLabel: UILabel!
    var bottomLabel: UILabel!

    private var widthConstraint: Constraint?
    private var heightConstraint: Constraint?

    var pressedHandle: (() -> Void)?

    /// KHANDAQ (#15): size the preview box to the media's aspect ratio (bounded), so photos/videos
    /// render at their real shape instead of a square crop. Falls back to the square file box.
    func setPreviewSize(_ imageSize: CGSize) {
        applyPreviewLayout()
        let box = LoadingImageView.previewBox(for: imageSize)
        widthConstraint?.update(offset: box.width)
        heightConstraint?.update(offset: box.height)
    }

    /// Revert to the default box (non-image files, loading/placeholder, reused cells).
    func resetPreviewSize() {
        if isCompactLayout {
            widthConstraint?.update(offset: Constants.CompactWidth)
            heightConstraint?.update(offset: Constants.CompactHeight)
        }
        else {
            widthConstraint?.update(offset: Constants.ImageButtonSize)
            heightConstraint?.update(offset: Constants.ImageButtonSize)
        }
    }

    // KHANDAQ (file bubble): compact row layout for file states without a visual preview —
    // switches automatically (setImageWithUti/setCancelledImage → compact, photo preview → full).
    private func applyCompactFileLayout() {
        guard !isCompactLayout else {
            return
        }
        isCompactLayout = true

        widthConstraint?.update(offset: Constants.CompactWidth)
        heightConstraint?.update(offset: Constants.CompactHeight)

        centerImageView.snp.remakeConstraints {
            $0.leading.equalTo(self).offset(18)
            $0.centerY.equalTo(self)
            $0.size.equalTo(Constants.CompactIconSize)
        }
        progressView.snp.remakeConstraints {
            $0.center.equalTo(centerImageView)
            $0.size.equalTo(Constants.CompactRingSize)
        }
        topLabel.snp.remakeConstraints {
            $0.leading.equalTo(centerImageView.snp.trailing).offset(16)
            $0.trailing.lessThanOrEqualTo(self).offset(-Constants.LabelHorizontalOffset)
            $0.bottom.equalTo(self.snp.centerY).offset(1)
        }
        bottomLabel.snp.remakeConstraints {
            $0.leading.equalTo(topLabel)
            $0.trailing.lessThanOrEqualTo(self).offset(-Constants.LabelHorizontalOffset)
            $0.top.equalTo(self.snp.centerY).offset(1)
        }

        topLabel.font = UIFont.systemFont(ofSize: 14.0, weight: .semibold)
        topLabel.lineBreakMode = .byTruncatingMiddle
        bottomLabel.font = UIFont.systemFont(ofSize: 12.0)
    }

    private func applyPreviewLayout() {
        guard isCompactLayout else {
            return
        }
        isCompactLayout = false

        widthConstraint?.update(offset: Constants.ImageButtonSize)
        heightConstraint?.update(offset: Constants.ImageButtonSize)

        centerImageView.snp.remakeConstraints {
            $0.center.equalTo(self)
            $0.size.equalTo(Constants.CenterImageSize)
        }
        progressView.snp.remakeConstraints {
            $0.center.equalTo(self)
            $0.size.equalTo(Constants.ProgressViewSize)
        }
        topLabel.snp.remakeConstraints {
            $0.leading.equalTo(self).offset(Constants.LabelHorizontalOffset)
            $0.trailing.lessThanOrEqualTo(self).offset(-Constants.LabelHorizontalOffset)
            $0.bottom.equalTo(bottomLabel.snp.top)
        }
        bottomLabel.snp.remakeConstraints {
            $0.leading.equalTo(self).offset(Constants.LabelHorizontalOffset)
            $0.trailing.lessThanOrEqualTo(self).offset(-Constants.LabelHorizontalOffset)
            $0.bottom.equalTo(self).offset(Constants.LabelBottomOffset)
        }

        topLabel.font = UIFont.systemFont(ofSize: 14.0)
        topLabel.lineBreakMode = .byTruncatingTail
        bottomLabel.font = UIFont.systemFont(ofSize: 14.0)
    }

    static func previewBox(for imageSize: CGSize) -> CGSize {
        guard imageSize.width > 0, imageSize.height > 0 else {
            return CGSize(width: Constants.ImageButtonSize, height: Constants.ImageButtonSize)
        }
        let aspect = imageSize.width / imageSize.height
        var w = Constants.PreviewMaxWidth
        var h = w / aspect
        if h > Constants.PreviewMaxHeight {
            h = Constants.PreviewMaxHeight
            w = h * aspect
        }
        return CGSize(width: w.rounded(), height: h.rounded())
    }

    override init(frame: CGRect) {
        super.init(frame: frame)

        backgroundColor = .clear
        createViews()
        installConstraints()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setCancelledImage() {
        applyCompactFileLayout()
        centerImageView.image = UIImage.templateNamed("chat-file-type-canceled")
    }

    func setImageWithUti(_ uti: String?, fileExtension: String?) {
        applyCompactFileLayout()
        let imageName = imageNameWithUti(uti, fileExtension: fileExtension)
        centerImageView.image = UIImage.templateNamed(imageName)
    }
}

extension LoadingImageView {
    @objc func imageButtonPressed() {
        pressedHandle?()
    }
}

private extension LoadingImageView {
    func createViews() {
        imageButton = UIButton()
        imageButton.layer.cornerRadius = 12.0
        imageButton.clipsToBounds = true
        imageButton.addTarget(self, action: #selector(LoadingImageView.imageButtonPressed), for: .touchUpInside)
        addSubview(imageButton)

        centerImageView = UIImageView()
        centerImageView.contentMode = .center
        addSubview(centerImageView)

        progressView = ProgressCircleView()
        progressView.isUserInteractionEnabled = false
        addSubview(progressView)

        topLabel = UILabel()
        topLabel.font = UIFont.systemFont(ofSize: 14.0)
        addSubview(topLabel)

        bottomLabel = UILabel()
        bottomLabel.font = UIFont.systemFont(ofSize: 14.0)
        addSubview(bottomLabel)
    }

    func installConstraints() {
        snp.makeConstraints {
            self.widthConstraint = $0.width.equalTo(Constants.ImageButtonSize).constraint
            self.heightConstraint = $0.height.equalTo(Constants.ImageButtonSize).constraint
        }

        imageButton.snp.makeConstraints {
            $0.edges.equalTo(self)
        }

        centerImageView.snp.makeConstraints {
            $0.center.equalTo(self)
            $0.size.equalTo(Constants.CenterImageSize)
        }

        progressView.snp.makeConstraints {
            $0.center.equalTo(self)
            $0.size.equalTo(Constants.ProgressViewSize)
        }

        topLabel.snp.makeConstraints {
            $0.leading.equalTo(self).offset(Constants.LabelHorizontalOffset)
            $0.trailing.lessThanOrEqualTo(self).offset(-Constants.LabelHorizontalOffset)
            $0.bottom.equalTo(bottomLabel.snp.top)
        }

        bottomLabel.snp.makeConstraints {
            $0.leading.equalTo(self).offset(Constants.LabelHorizontalOffset)
            $0.trailing.lessThanOrEqualTo(self).offset(-Constants.LabelHorizontalOffset)
            $0.bottom.equalTo(self).offset(Constants.LabelBottomOffset)
        }
    }

    func imageNameWithUti(_ uti: String?, fileExtension: String?) -> String {
        guard let uti = uti else {
            return "chat-file-type-basic"
        }

        if UTTypeEqual(uti as CFString, kUTTypeGIF) {
            return "chat-file-type-gif"
        }
        else if UTTypeEqual(uti as CFString, kUTTypeHTML) {
            return "chat-file-type-html"
        }
        else if UTTypeEqual(uti as CFString, kUTTypeJPEG) {
            return "chat-file-type-jpg"
        }
        else if UTTypeEqual(uti as CFString, kUTTypeMP3) {
            return "chat-file-type-mp3"
        }
        else if UTTypeEqual(uti as CFString, kUTTypeMPEG) {
            return "chat-file-type-mpg"
        }
        else if UTTypeEqual(uti as CFString, kUTTypeMPEG4) {
            return "chat-file-type-mpg"
        }
        else if UTTypeEqual(uti as CFString, kUTTypePDF) {
            return "chat-file-type-pdf"
        }
        else if UTTypeEqual(uti as CFString, kUTTypePNG) {
            return "chat-file-type-png"
        }
        else if UTTypeEqual(uti as CFString, kUTTypeTIFF) {
            return "chat-file-type-tif"
        }
        else if UTTypeEqual(uti as CFString, kUTTypePlainText) {
            return "chat-file-type-txt"
        }

        guard let fileExtension = fileExtension else {
            return "chat-file-type-basic"
        }

        switch fileExtension {
            case "7z":
                return "chat-file-type-7zip"
            case "aac":
                return "chat-file-type-aac"
            case "avi":
                return "chat-file-type-avi"
            case "css":
                return "chat-file-type-css"
            case "csv":
                return "chat-file-type-csv"
            case "doc":
                return "chat-file-type-doc"
            case "ebup":
                return "chat-file-type-ebup"
            case "exe":
                return "chat-file-type-exe"
            case "fb2":
                return "chat-file-type-fb2"
            case "flv":
                return "chat-file-type-flv"
            case "mov":
                return "chat-file-type-mov"
            case "ogg":
                return "chat-file-type-ogg"
            case "otf":
                return "chat-file-type-otf"
            case "ppt":
                return "chat-file-type-ppt"
            case "psd":
                return "chat-file-type-psd"
            case "rar":
                return "chat-file-type-rar"
            case "tar":
                return "chat-file-type-tar"
            case "ttf":
                return "chat-file-type-ttf"
            case "wav":
                return "chat-file-type-wav"
            case "wma":
                return "chat-file-type-wma"
            case "xls":
                return "chat-file-type-xls"
            case "zip":
                return "chat-file-type-zip"
            default:
                return "chat-file-type-basic"
        }
    }
}
