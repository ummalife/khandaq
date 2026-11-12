// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

/// A file, a photo or a video, or a voice note as an entry of the transcript (redesign stage 3, E8):
/// the time column and the author as for a text (AirEntryFrame), then the attachment. A photo or a
/// video shows its preview with the caption under it; a file shows its name and size, and while it
/// moves the bar, what is left and the actions (AirFileRowView); a voice note shows "Listen", its wave
/// and its length. Our own entries carry the status mark at the far end. ChatIncomingFileCell and
/// ChatOutgoingFileCell only say which way the file goes.
class ChatGenericFileCell: ChatMovableDateCell {
    /// The preview of a photo or a video.
    var loadingView: LoadingImageView!
    /// The name, the size and the transfer of a file; under a preview, the transfer alone.
    var fileRow: AirFileRowView!
    var voiceMessageView: ChatVoiceMessageView!
    // KHANDAQ: the caption sent with a photo, shown under it in the same entry.
    var captionLabel: UILabel!
    // KHANDAQ (#192): the reactions line under the attachment.
    var reactionsLabel: UILabel!
    var onReactionsTap: (() -> Void)?

    let entry = AirEntryFrame()
    /// The attachment, top to bottom: the preview or the voice note, the file row (under them, only the
    /// transfer), the caption and the reactions. A hidden part takes no room.
    private let body = UIStackView()
    private let previewHolder = UIView()
    private let videoBadge = UIView()
    private let videoBadgeLabel = UILabel()
    private lazy var summaryElement = ChatFileEntryElement(cell: self)
    private var fileModel: ChatGenericFileCellModel?
    /// The height of the first line of the body, for the time column and the mark.
    private var firstLine: CGFloat = 0
    private var personName: String?
    private var personOnline = true

    /// The way the file goes; ChatOutgoingFileCell says false.
    var isIncoming: Bool {
        return true
    }

    // KHANDAQ (#36): remembered after a full voice configure, so a refresh driven by the player (timer
    // or notification) changes the button and the wave without building the model again, which would
    // drop `onPlayTapped` after the first tick.
    private var voicePlaybackMessageId: String?
    private var voicePlaybackDuration: TimeInterval = 0
    private var voicePlaybackEnabled = false

    static var captionFont: UIFont {
        return KQFont.font(.body)
    }

    static var reactionsFont: UIFont {
        return KQFont.font(.label)
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

    /// The preview has come: the photo or the first frame of the video, filling its box. Call it after
    /// setupWithTheme(_:model:).
    func setButtonImage(_ image: UIImage) {
        canBeCopied = true

        // KHANDAQ (#15): aspect-filled in a box of the media's own shape, neither stretched nor squashed.
        loadingView.imageButton.setBackgroundImage(nil, for: UIControlState())
        loadingView.imageButton.imageView?.contentMode = .scaleAspectFill
        loadingView.imageButton.imageView?.clipsToBounds = true
        loadingView.imageButton.contentHorizontalAlignment = .fill
        loadingView.imageButton.contentVerticalAlignment = .fill
        loadingView.imageButton.setImage(image, for: UIControlState())
        loadingView.centerImageView.image = nil
        loadingView.progressView.isHidden = true
        loadingView.topLabel.isHidden = true
        loadingView.bottomLabel.isHidden = true

        // The box was sized from the file before the image came; an image of another shape (a photo
        // turned by its EXIF orientation) reshapes it only when no size was known.
        if fileModel?.previewSize == nil {
            loadingView.setPreviewSize(image.size)
        }
        previewHolder.isHidden = false
        if fileModel?.isVoiceMessage == false, state == .done {
            fileRow.isHidden = true
        }
    }

    /// A video: the play mark in the middle of its preview.
    func setVideoPlayOverlay() {
        loadingView.centerImageView.image = UIImage.templateNamed("chat-file-play-big")
        loadingView.centerImageView.tintColor = KQColor.fixedWhite
    }

    /// A video: its length in the corner of its preview.
    func setVideoDurationLabel(_ text: String) {
        videoBadgeLabel.text = text
        videoBadge.isHidden = text.isEmpty
        refreshSpokenText()
    }

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let fileModel = model as? ChatGenericFileCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }
        self.fileModel = fileModel

        state = fileModel.state
        transferBytesTotal = fileModel.fileSizeBytes
        startLoadingHandle = fileModel.startLoadingHandle
        cancelHandle = fileModel.cancelHandle
        retryHandle = fileModel.retryHandle
        pauseOrResumeHandle = fileModel.pauseOrResumeHandle
        openHandle = fileModel.openHandle
        personName = fileModel.personName
        personOnline = fileModel.personOnline

        let isVoice = fileModel.isVoiceMessage
        let previewSize = isVoice ? nil : fileModel.previewSize

        // The preview: sized now, the image comes with setButtonImage.
        loadingView.imageButton.setImage(nil, for: UIControlState())
        loadingView.imageButton.setBackgroundImage(nil, for: UIControlState())
        loadingView.centerImageView.image = nil
        loadingView.progressView.isHidden = true
        loadingView.topLabel.isHidden = true
        loadingView.bottomLabel.isHidden = true
        loadingView.backgroundColor = KQColor.sunken
        videoBadgeLabel.text = nil
        videoBadge.isHidden = true
        if let size = previewSize {
            loadingView.setPreviewSize(size)
        }
        else {
            loadingView.resetPreviewSize()
        }
        previewHolder.isHidden = previewSize == nil

        // The file row: the whole row for a file, the transfer alone under a preview or a voice note.
        let showsHead = previewSize == nil && !isVoice
        let text = entryText(progress: fileModel.transferProgress)
        let saysSomething = text.progress != nil || !(text.note ?? "").isEmpty || !text.actions.isEmpty
        fileRow.isHidden = !showsHead && (state == .done || !saysSomething)
        fileRow.configure(name: fileModel.fileName ?? "",
                          size: fileModel.fileSize ?? "",
                          text: text,
                          incoming: isIncoming,
                          showsHead: showsHead)

        voiceMessageView.isHidden = !isVoice
        configureVoiceMessagePresentation(fileModel: fileModel, theme: theme)

        // KHANDAQ: the caption sent with a photo; none for a voice note.
        let caption = isVoice ? nil : fileModel.caption
        captionLabel.text = caption
        captionLabel.font = ChatGenericFileCell.captionFont
        captionLabel.textColor = KQColor.ink
        captionLabel.isHidden = (caption ?? "").isEmpty

        canBeCopied = false
        // KHANDAQ (#192): files, photos and voice notes take reactions too.
        canBeReacted = true
        bindReactions(fileModel.reactionsDisplay)
        onReactionsTap = { [weak self] in
            guard let cell = self else {
                return
            }
            cell.delegate?.chatMovableDateCellReactPressed(cell)
        }

        if previewSize != nil {
            firstLine = ceil(KQFont.lineHeight(.caption))
        }
        else if isVoice {
            firstLine = KQSize.targetIos
        }
        else {
            firstLine = AirFileRowView.firstLineHeight
        }
        entry.configure(fileModel, firstLine: firstLine)
        let status = entryStatus()
        entry.mark.set(status, animated: false)
        entry.mark.isHidden = isIncoming || status == .none

        refreshSpokenText()
    }

    override func createViews() {
        revealsTime = false
        super.createViews()

        loadingView = LoadingImageView()
        loadingView.layer.cornerRadius = KQRadius.card
        loadingView.clipsToBounds = true
        loadingView.pressedHandle = { [weak self] in
            self?.loadingViewPressed()
        }
        // For the UI tests only: VoiceOver reaches the preview through the entry.
        loadingView.imageButton.accessibilityIdentifier = "air.file.preview"
        previewHolder.addSubview(loadingView)

        videoBadge.backgroundColor = KQColor.mediaShade
        videoBadge.layer.cornerRadius = KQRadius.small
        videoBadge.isUserInteractionEnabled = false
        videoBadge.isHidden = true
        videoBadgeLabel.font = KQFont.tabular(KQFont.font(.caption))
        videoBadgeLabel.textColor = KQColor.fixedWhite
        videoBadge.addSubview(videoBadgeLabel)
        previewHolder.addSubview(videoBadge)

        fileRow = AirFileRowView()
        fileRow.onAction = { [weak self] action in
            self?.perform(action)
        }

        voiceMessageView = ChatVoiceMessageView()
        voiceMessageView.isHidden = true
        voiceMessageView.onPlayTapped = { [weak self] in
            self?.voicePlayTogglePressed()
        }

        captionLabel = UILabel()
        captionLabel.font = ChatGenericFileCell.captionFont
        captionLabel.adjustsFontForContentSizeCategory = true
        captionLabel.numberOfLines = 0
        captionLabel.isHidden = true
        captionLabel.isAccessibilityElement = false

        reactionsLabel = UILabel()
        reactionsLabel.font = ChatGenericFileCell.reactionsFont
        reactionsLabel.adjustsFontForContentSizeCategory = true
        reactionsLabel.numberOfLines = 1
        reactionsLabel.isHidden = true
        reactionsLabel.isUserInteractionEnabled = true
        reactionsLabel.isAccessibilityElement = false
        reactionsLabel.addGestureRecognizer(
            UITapGestureRecognizer(target: self, action: #selector(ChatGenericFileCell.reactionsLabelTapped)))

        body.axis = .vertical
        body.alignment = .fill
        body.spacing = KQSpace.x6
        [previewHolder, voiceMessageView, fileRow, captionLabel, reactionsLabel].forEach {
            body.addArrangedSubview($0)
        }
        movableContentView.addSubview(body)
    }

    override func installConstraints() {
        super.installConstraints()

        entry.install(in: self, body: body)
        loadingView.snp.makeConstraints {
            $0.top.leading.equalToSuperview()
            $0.trailing.lessThanOrEqualToSuperview()
            // Gives way while the stack hides the preview, instead of fighting its zero height.
            $0.bottom.equalToSuperview().priority(999)
        }
        videoBadge.snp.makeConstraints {
            $0.leading.equalTo(loadingView).offset(KQSpace.x8)
            $0.bottom.equalTo(loadingView).offset(-KQSpace.x8)
        }
        videoBadgeLabel.snp.makeConstraints {
            $0.top.bottom.equalToSuperview().inset(KQSpace.x2)
            $0.leading.trailing.equalToSuperview().inset(KQSpace.x6)
        }
    }

    override func prepareForReuse() {
        super.prepareForReuse()
        progressObject = nil
        fileModel = nil
    }

    override func setEditing(_ editing: Bool, animated: Bool) {
        super.setEditing(editing, animated: animated)

        loadingView.isUserInteractionEnabled = !editing
        fileRow.isUserInteractionEnabled = !editing
        voiceMessageView.isUserInteractionEnabled = !editing
    }

    func updateProgress(_ progress: CGFloat) {
        guard state == .loading else {
            return
        }
        let share = Float(progress)
        fileModel?.transferProgress = share
        fileRow.updateProgress(share, note: AirFileEntryText.leftText(bytesTotal: transferBytesTotal, progress: share))
        refreshSpokenText()
    }

    // The note says what is left; the time left and the speed would change it every second.
    func updateEta(_ eta: String) {}

    func updateBytesPerSecond(_ bytesPerSecond: OCTToxFileSize) {}

    // KHANDAQ (#192): the reactions under the attachment, or none.
    func bindReactions(_ display: String?) {
        let text = display ?? ""
        reactionsLabel.font = ChatGenericFileCell.reactionsFont
        reactionsLabel.textColor = KQColor.ink2
        reactionsLabel.text = text
        reactionsLabel.isHidden = text.isEmpty
    }

    @objc func reactionsLabelTapped() {
        onReactionsTap?()
    }

    /// A tap on the preview: open it once it is here.
    func loadingViewPressed() {
        guard state == .done else {
            return
        }
        openHandle?()
    }

    func voicePlayTogglePressed() {
        guard state == .done else {
            return
        }
        fileModel?.voicePlayToggleHandle?()
    }

    func configureVoiceMessagePresentation(fileModel: ChatGenericFileCellModel, theme: Theme) {
        guard fileModel.isVoiceMessage else {
            return
        }

        let enabled = fileModel.state == .done
        voiceMessageView.unknownLengthText = fileModel.fileSize
        voiceMessageView.onPlayTapped = { [weak self] in
            self?.voicePlayTogglePressed()
        }
        // KHANDAQ (Android parity): scrub only a note that is here.
        voiceMessageView.onSeek = enabled ? fileModel.voiceSeekHandle : nil

        // KHANDAQ (#36): remembered for the refresh that keeps the handles.
        voicePlaybackMessageId = fileModel.voiceMessageId
        voicePlaybackDuration = fileModel.voiceDuration
        voicePlaybackEnabled = enabled

        if let messageId = fileModel.voiceMessageId,
           let state = ChatVoiceMessagePlayer.shared.state(for: messageId) {
            voiceMessageView.update(isPlaying: state.isPlaying,
                                    progress: state.progress,
                                    currentTime: state.currentTime,
                                    duration: state.duration,
                                    enabled: enabled)
        }
        else {
            voiceMessageView.update(isPlaying: false,
                                    progress: 0,
                                    currentTime: 0,
                                    duration: fileModel.voiceDuration,
                                    enabled: enabled)
        }
    }

    func refreshVoiceMessagePresentation(theme: Theme, fileModel: ChatGenericFileCellModel) {
        guard fileModel.isVoiceMessage else {
            return
        }

        configureVoiceMessagePresentation(fileModel: fileModel, theme: theme)
        refreshSpokenText()
    }

    /// KHANDAQ (#36): only the button and the wave, from the shared player, keeping `onPlayTapped`.
    /// Driven by the player's timer and notifications.
    func refreshVoicePlaybackState(theme: Theme) {
        guard !voiceMessageView.isHidden, let messageId = voicePlaybackMessageId else {
            return
        }

        if let state = ChatVoiceMessagePlayer.shared.state(for: messageId) {
            voiceMessageView.update(isPlaying: state.isPlaying,
                                    progress: state.progress,
                                    currentTime: state.currentTime,
                                    duration: state.duration,
                                    enabled: voicePlaybackEnabled)
        }
        else {
            voiceMessageView.update(isPlaying: false,
                                    progress: 0,
                                    currentTime: 0,
                                    duration: voicePlaybackDuration,
                                    enabled: voicePlaybackEnabled)
        }
        refreshSpokenText()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        guard let model = fileModel else {
            return
        }
        // The fixed-size font of the time is made again for a new text size.
        entry.configure(model, firstLine: firstLine)
    }
}

// The transfer.
private extension ChatGenericFileCell {
    func availableActions() -> Set<AirFileAction> {
        var actions = Set<AirFileAction>()
        if startLoadingHandle != nil {
            actions.insert(.accept)
        }
        if cancelHandle != nil {
            actions.formUnion([.decline, .cancel])
        }
        if pauseOrResumeHandle != nil {
            actions.formUnion([.pause, .resume])
        }
        if retryHandle != nil {
            actions.insert(.retry)
        }
        return actions
    }

    func entryText(progress: Float) -> AirFileEntryText {
        return AirFileEntryText.make(state: state,
                                     incoming: isIncoming,
                                     bytesTotal: transferBytesTotal,
                                     progress: progress,
                                     available: availableActions(),
                                     personName: personName,
                                     personOnline: personOnline)
    }

    func entryStatus() -> AirEntryStatus {
        guard !isIncoming else {
            return .none
        }
        let delivered = (fileModel as? ChatOutgoingFileCellModel)?.delivered ?? false
        return AirFileEntryText.status(state: state, delivered: delivered, personOnline: personOnline)
    }

    func perform(_ action: AirFileAction) {
        switch action {
            case .accept:
                startLoadingHandle?()
            case .decline, .cancel:
                cancelHandle?()
            case .pause, .resume:
                pauseOrResumeHandle?()
            case .retry:
                retryHandle?()
        }
    }

    /// One entry for VoiceOver: who and when, then what the file is and how it goes; the voice note's
    /// button and the actions of the transfer follow as their own buttons, in the order they are seen.
    func refreshSpokenText() {
        guard let model = fileModel else {
            return
        }
        var value: [String] = []
        if let marks = transcriptMarksSpoken {
            value.append(marks)
        }
        if model.isVoiceMessage {
            value.append(String(localized: "air_voice_spoken"))
            value.append(voiceMessageView.spokenText)
        }
        else if !previewHolder.isHidden {
            value.append(model.fileName ?? "")
            if let length = videoBadgeLabel.text, !videoBadge.isHidden {
                value.append(length)
            }
        }
        let row = fileRow.isHidden ? "" : fileRow.spokenText
        if !row.isEmpty {
            value.append(row)
        }
        if let caption = captionLabel.text, !captionLabel.isHidden {
            value.append(caption)
        }
        if let reactions = reactionsLabel.text, !reactionsLabel.isHidden {
            value.append(reactions)
        }
        if !isIncoming, let spoken = entryStatus().spokenText {
            value.append(spoken)
        }
        summaryElement.accessibilityLabel = entry.spokenLabel(model)
        summaryElement.accessibilityValue = value.filter { !$0.isEmpty }.joined(separator: ", ")
        summaryElement.accessibilityTraits = state == .done && !model.isVoiceMessage ? UIAccessibilityTraitButton : UIAccessibilityTraitNone
    }
}

// Accessibility: the entry, then the buttons of the voice note and of the transfer.
extension ChatGenericFileCell {
    override var isAccessibilityElement: Bool {
        get {
            return false
        }
        set {}
    }

    override var accessibilityElements: [Any]? {
        get {
            summaryElement.accessibilityFrameInContainerSpace = bounds
            var elements: [Any] = [summaryElement]
            if !voiceMessageView.isHidden {
                elements.append(voiceMessageView)
            }
            if !fileRow.isHidden {
                elements.append(fileRow)
            }
            return elements
        }
        set {}
    }

    override func accessibilityActivate() -> Bool {
        guard state == .done, fileModel?.isVoiceMessage == false else {
            return false
        }
        openHandle?()
        return true
    }

    /// Where a tap on the entry acts, on the screen: the middle of the preview, or of the attachment. The
    /// entry is the whole row, so its own middle could fall beside the preview (at the large text sizes).
    /// A voice note keeps the middle of the row: its button is an element of its own.
    fileprivate var entryActivationPoint: CGPoint? {
        guard let window = window, voiceMessageView.isHidden else {
            return nil
        }
        let target: UIView = previewHolder.isHidden ? body : loadingView
        let frame = window.convert(target.convert(target.bounds, to: nil), to: window.screen.coordinateSpace)
        return CGPoint(x: frame.midX, y: frame.midY)
    }
}

/// The spoken entry of a file. On a double tap VoiceOver asks the focused element, this one, to act, not
/// the cell: unanswered, it tapped the middle of the row, which opened nothing where that middle fell
/// beside the preview (at the large text sizes). The entry now gives the cell's own answer.
private final class ChatFileEntryElement: UIAccessibilityElement {
    private weak var cell: ChatGenericFileCell?

    init(cell: ChatGenericFileCell) {
        self.cell = cell
        super.init(accessibilityContainer: cell)
    }

    override var accessibilityActivationPoint: CGPoint {
        get {
            return cell?.entryActivationPoint ?? super.accessibilityActivationPoint
        }
        set {
            super.accessibilityActivationPoint = newValue
        }
    }

    override func accessibilityActivate() -> Bool {
        return cell?.accessibilityActivate() ?? false
    }
}

// ChatEditable
extension ChatGenericFileCell {
    override func shouldShowMenu() -> Bool {
        return true
    }

    override func menuTargetRect() -> CGRect {
        return body.frame
    }
}
