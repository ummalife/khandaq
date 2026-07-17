// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

class ChatGenericFileCell: ChatMovableDateCell {
    var loadingView: LoadingImageView!
    var voiceMessageView: ChatVoiceMessageView!
    var cancelButton: UIButton!
    var retryButton: UIButton!
    // KHANDAQ: Telegram-style caption shown directly under the media, inside the same cell.
    var captionLabel: UILabel!
    var captionTopConstraint: Constraint?
    // KHANDAQ (#192): reaction chips line ("❤️ 2  👍") under the media/file/voice bubble.
    var reactionsLabel: UILabel!
    var onReactionsTap: (() -> Void)?
    // Bottom-of-bubble ownership: normally the caption (media/file) or the voice view owns
    // movableContentView's bottom; when reactions are visible they sit below and own it instead.
    var captionBottomConstraint: Constraint?
    var voiceBottomConstraint: Constraint?

    // KHANDAQ (#36): remembered after a full voice configure so a lightweight player-state refresh
    // (timer/notification driven) can update the play/pause icon + progress WITHOUT rebuilding the
    // model — rebuilding would drop `onPlayTapped` and kill the button after the first tick.
    private var voicePlaybackMessageId: String?
    private var voicePlaybackDuration: TimeInterval = 0
    private var voicePlaybackEnabled = false

    static let captionFont = UIFont.systemFont(ofSize: 15)
    static let captionTopSpacing: CGFloat = 5.0

    static func captionHeight(for text: String, width: CGFloat) -> CGFloat {
        guard !text.isEmpty, width > 0 else {
            return 0
        }
        let bounding = (text as NSString).boundingRect(
            with: CGSize(width: width, height: .greatestFiniteMagnitude),
            options: [.usesLineFragmentOrigin, .usesFontLeading],
            attributes: [.font: captionFont],
            context: nil)
        return ceil(bounding.height) + captionTopSpacing
    }

    var progressObject: ChatProgressProtocol? {
        didSet {
            progressObject?.updateProgress = { [weak self] (progress: Float) -> Void in
                self?.updateProgress(CGFloat(progress))
            }

            progressObject?.updateEta = { [weak self] (eta: CFTimeInterval, bytesPerSecond: OCTToxFileSize) -> Void in
                self?.updateEta(String(timeInterval: eta))
                self?.updateBytesPerSecond(bytesPerSecond)
            }
        }
    }

    var state: ChatGenericFileCellModel.State = .waitingConfirmation
    private var transferBytesTotal: Int64 = 0

    var startLoadingHandle: (() -> Void)?
    var cancelHandle: (() -> Void)?
    var retryHandle: (() -> Void)?
    var pauseOrResumeHandle: (() -> Void)?
    var openHandle: (() -> Void)?

    /**
        This method should be called after setupWithTheme:model:
     */
    func setButtonImage(_ image: UIImage) {
        canBeCopied = true

        // KHANDAQ (#15): show the photo preview aspect-FILLED in the square preview box. The old code
        // center-cropped the source to a square and set it as a button BACKGROUND image, which UIKit
        // then STRETCHED to the button bounds — producing a distorted wide/short strip. Using the
        // button's imageView with scaleAspectFill keeps the aspect ratio (cropped, not squished).
        loadingView.imageButton.setBackgroundImage(nil, for: UIControlState())
        loadingView.imageButton.imageView?.contentMode = .scaleAspectFill
        loadingView.imageButton.imageView?.clipsToBounds = true
        loadingView.imageButton.contentHorizontalAlignment = .fill
        loadingView.imageButton.contentVerticalAlignment = .fill
        loadingView.imageButton.setImage(image, for: UIControlState())

        // KHANDAQ (#15): shape the bubble to the photo/video aspect ratio (the box now matches the
        // image, so scaleAspectFill neither crops nor stretches).
        loadingView.setPreviewSize(image.size)

        if state == .waitingConfirmation || state == .done {
            loadingView.centerImageView.image = nil
        }
    }

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let fileModel = model as? ChatGenericFileCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        // KHANDAQ (#15): reset to the square box; setButtonImage() re-sizes to aspect for media.
        loadingView.resetPreviewSize()

        state = fileModel.state
        transferBytesTotal = fileModel.fileSizeBytes
        startLoadingHandle = fileModel.startLoadingHandle
        cancelHandle = fileModel.cancelHandle
        retryHandle = fileModel.retryHandle
        pauseOrResumeHandle = fileModel.pauseOrResumeHandle
        openHandle = fileModel.openHandle

        configureVoiceMessagePresentation(fileModel: fileModel, theme: theme)

        // Merged caption (Telegram-style). Hidden for voice notes and when there is no caption.
        let captionText = fileModel.isVoiceMessage ? nil : fileModel.caption
        if let captionText = captionText, !captionText.isEmpty {
            captionLabel.text = captionText
            captionLabel.textColor = theme.colorForType(.NormalText)
            captionLabel.isHidden = false
            captionTopConstraint?.update(offset: ChatGenericFileCell.captionTopSpacing)
        }
        else {
            captionLabel.text = nil
            captionLabel.isHidden = true
            captionTopConstraint?.update(offset: 0)
        }

        canBeCopied = false
        // KHANDAQ (#192): allow reactions on files / media / voice notes too.
        canBeReacted = true
        reactionsLabel.textColor = theme.colorForType(.NormalText)
        bindReactions(fileModel.reactionsDisplay)
        onReactionsTap = { [weak self] in
            guard let cell = self else { return }
            cell.delegate?.chatMovableDateCellReactPressed(cell)
        }

        switch state {
            case .loading:
                loadingView.centerImageView.image = UIImage.templateNamed("chat-file-pause-big")
            case .paused:
                loadingView.centerImageView.image = UIImage.templateNamed("chat-file-play-big")
            case .waitingConfirmation:
                fallthrough
            case .cancelled:
                fallthrough
            case .done:
                var fileExtension: String? = nil

                if let fileName = fileModel.fileName {
                    fileExtension = (fileName as NSString).pathExtension
                }

                loadingView.setImageWithUti(fileModel.fileUTI, fileExtension: fileExtension)
        }

        updateViewsWithState(fileModel.state, fileModel: fileModel)

        loadingView.imageButton.setImage(nil, for: UIControlState())

        let backgroundColor = theme.colorForType(.FileImageBackgroundActive)
        let backgroundImage = UIImage.imageWithColor(backgroundColor, size: CGSize(width: 1.0, height: 1.0))
        loadingView.imageButton.setBackgroundImage(backgroundImage, for: UIControlState())

        loadingView.progressView.backgroundLineColor = theme.colorForType(.FileImageAcceptButtonTint).withAlphaComponent(0.3)
        loadingView.progressView.lineColor = theme.colorForType(.FileImageAcceptButtonTint)

        loadingView.centerImageView.tintColor = theme.colorForType(.FileImageAcceptButtonTint)

        loadingView.topLabel.textColor = theme.colorForType(.FileImageCancelledText)
        loadingView.bottomLabel.textColor = theme.colorForType(.FileImageCancelledText)

        cancelButton.tintColor = theme.colorForType(.FileImageCancelButtonTint)
        retryButton.tintColor = theme.colorForType(.FileImageCancelButtonTint)
    }

    override func createViews() {
        super.createViews()

        loadingView = LoadingImageView()
        loadingView.pressedHandle = loadingViewPressed

        captionLabel = UILabel()
        captionLabel.font = ChatGenericFileCell.captionFont
        captionLabel.numberOfLines = 0
        captionLabel.isHidden = true

        reactionsLabel = UILabel()
        reactionsLabel.font = UIFont.systemFont(ofSize: 13.0)
        reactionsLabel.numberOfLines = 1
        reactionsLabel.isHidden = true
        reactionsLabel.isUserInteractionEnabled = true
        reactionsLabel.addGestureRecognizer(
            UITapGestureRecognizer(target: self, action: #selector(ChatGenericFileCell.reactionsLabelTapped)))

        voiceMessageView = ChatVoiceMessageView()
        voiceMessageView.isHidden = true
        voiceMessageView.onPlayTapped = { [weak self] in
            self?.voicePlayTogglePressed()
        }

        let cancelImage = UIImage.templateNamed("chat-file-cancel")

        cancelButton = UIButton()
        cancelButton.setImage(cancelImage, for: UIControlState())
        cancelButton.addTarget(self, action: #selector(ChatGenericFileCell.cancelButtonPressed), for: .touchUpInside)

        let retryImage = UIImage.templateNamed("chat-file-retry")

        retryButton = UIButton()
        retryButton.setImage(retryImage, for: UIControlState())
        retryButton.addTarget(self, action: #selector(ChatGenericFileCell.retryButtonPressed), for: .touchUpInside)
    }

    override func setEditing(_ editing: Bool, animated: Bool) {
        super.setEditing(editing, animated: animated)

        loadingView.isUserInteractionEnabled = !editing
        cancelButton.isUserInteractionEnabled = !editing
        retryButton.isUserInteractionEnabled = !editing
    }

    func updateProgress(_ progress: CGFloat) {
        loadingView.progressView.progress = progress

        guard state == .loading, progress > 0.001 else {
            return
        }

        loadingView.bottomLabel.isHidden = false
        let pct = Int(progress * 100)

        if transferBytesTotal > 0 {
            let done = Int64(Double(transferBytesTotal) * Double(progress))
            let doneStr = ByteCountFormatter.string(fromByteCount: done, countStyle: .file)
            let totalStr = ByteCountFormatter.string(fromByteCount: transferBytesTotal, countStyle: .file)
            loadingView.bottomLabel.text = "\(doneStr) / \(totalStr) · \(pct)%"
        }
        else {
            loadingView.bottomLabel.text = "\(pct)%"
        }
    }

    func updateEta(_ eta: String) {
        loadingView.topLabel.isHidden = false
        loadingView.topLabel.text = eta
    }

    func updateBytesPerSecond(_ bytesPerSecond: OCTToxFileSize) {
        guard state == .loading, bytesPerSecond > 0 else {
            return
        }

        let speed = ByteCountFormatter.string(fromByteCount: Int64(bytesPerSecond), countStyle: .file) + "/s"
        loadingView.topLabel.isHidden = false
        loadingView.topLabel.text = speed
    }

    // KHANDAQ (#192): show/hide the reaction chips under the bubble and reroute the bottom.
    // Anchors to the *visible* container (voice view vs media/caption) so a hidden 180px media
    // box never leaves a phantom gap above the chips on voice notes.
    func bindReactions(_ display: String?) {
        let text = display ?? ""
        reactionsLabel.text = text
        let hasReactions = !text.isEmpty
        reactionsLabel.isHidden = !hasReactions

        // Preserve today's exact no-reaction layout: both caption & voice bottoms stay active.
        // Only when reactions are visible do we hand the bubble bottom to the chips line.
        if hasReactions {
            captionBottomConstraint?.deactivate()
            voiceBottomConstraint?.deactivate()
        }
        else {
            captionBottomConstraint?.activate()
            voiceBottomConstraint?.activate()
        }

        let isVoice = !voiceMessageView.isHidden
        let anchorBottom = isVoice ? voiceMessageView.snp.bottom : captionLabel.snp.bottom

        reactionsLabel.snp.remakeConstraints {
            $0.top.equalTo(anchorBottom).offset(hasReactions ? 4.0 : 0.0)
            makeReactionsHorizontalConstraints($0)
            if hasReactions {
                $0.bottom.equalTo(movableContentView).offset(-8.0)
            }
        }
    }

    /// Horizontal placement of the reaction chips; overridden per direction (incoming left / outgoing right).
    func makeReactionsHorizontalConstraints(_ make: ConstraintMaker) {
        make.leading.equalTo(contentView).offset(20.0)
        make.trailing.lessThanOrEqualTo(contentView).offset(-20.0)
    }

    @objc func reactionsLabelTapped() {
        onReactionsTap?()
    }

    @objc func cancelButtonPressed() {
        cancelHandle?()
    }

    @objc func retryButtonPressed() {
        retryHandle?()
    }

    /// Override in subclass
    func updateViewsWithState(_ state: ChatGenericFileCellModel.State, fileModel: ChatGenericFileCellModel) {}

    /// Override in subclass
    func loadingViewPressed() {}

    func voicePlayTogglePressed() {
        // Subclasses wire model.voicePlayToggleHandle
    }

    func configureVoiceMessagePresentation(fileModel: ChatGenericFileCellModel, theme: Theme) {
        let showVoice = fileModel.isVoiceMessage
        voiceMessageView.isHidden = !showVoice
        loadingView.isHidden = showVoice
        // KHANDAQ (#15): the cancel/retry (⊗) buttons belong to the file box; hide them for voice
        // notes so they don't overlap the player's timer.
        if showVoice {
            cancelButton.isHidden = true
            retryButton.isHidden = true
        }

        guard showVoice else {
            return
        }

        let enabled = fileModel.state == .done
        voiceMessageView.apply(theme: theme, enabled: enabled)
        voiceMessageView.onPlayTapped = fileModel.voicePlayToggleHandle
        // KHANDAQ (Android parity): scrub support (only for downloaded/ready notes)
        voiceMessageView.onSeek = enabled ? fileModel.voiceSeekHandle : nil

        // KHANDAQ (#36): remember for the lightweight, handle-preserving state refresh.
        voicePlaybackMessageId = fileModel.voiceMessageId
        voicePlaybackDuration = fileModel.voiceDuration
        voicePlaybackEnabled = enabled

        if let messageId = fileModel.voiceMessageId,
           let state = ChatVoiceMessagePlayer.shared.state(for: messageId) {
            voiceMessageView.update(
                isPlaying: state.isPlaying,
                progress: state.progress,
                currentTime: state.currentTime,
                duration: state.duration,
                enabled: enabled
            )
        }
        else {
            voiceMessageView.update(
                isPlaying: false,
                progress: fileModel.state == .loading ? fileModel.voiceTransferProgress : 0,
                currentTime: 0,
                duration: fileModel.voiceDuration,
                enabled: enabled
            )
        }
    }

    func refreshVoiceMessagePresentation(theme: Theme, fileModel: ChatGenericFileCellModel) {
        guard fileModel.isVoiceMessage else {
            return
        }

        configureVoiceMessagePresentation(fileModel: fileModel, theme: theme)
    }

    /// KHANDAQ (#36): update ONLY the play/pause icon + progress from the shared player, reusing the
    /// already-configured `onPlayTapped`. Driven by the player's timer/notifications. Rebuilding the
    /// model here (the old path) reset `onPlayTapped` to nil, so the button died after the first tick
    /// and a finished/paused note could only be replayed by leaving and re-entering the chat.
    func refreshVoicePlaybackState(theme: Theme) {
        guard !voiceMessageView.isHidden, let messageId = voicePlaybackMessageId else {
            return
        }

        if let state = ChatVoiceMessagePlayer.shared.state(for: messageId) {
            voiceMessageView.update(
                isPlaying: state.isPlaying,
                progress: state.progress,
                currentTime: state.currentTime,
                duration: state.duration,
                enabled: voicePlaybackEnabled
            )
        }
        else {
            voiceMessageView.update(
                isPlaying: false,
                progress: 0,
                currentTime: 0,
                duration: voicePlaybackDuration,
                enabled: voicePlaybackEnabled
            )
        }
    }
}

// ChatEditable
extension ChatGenericFileCell {
    override func shouldShowMenu() -> Bool {
        return true
    }

    override func menuTargetRect() -> CGRect {
        // Voice notes hide the media box; anchor the menu/reaction bar to the visible player.
        return voiceMessageView.isHidden ? loadingView.frame : voiceMessageView.frame
    }
}
