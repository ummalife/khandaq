// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import UIKit
import SnapKit

protocol CallActiveControllerDelegate: class {
    func callActiveController(_ controller: CallActiveController, mute: Bool)
    func callActiveController(_ controller: CallActiveController, speaker: Bool)
    func callActiveController(_ controller: CallActiveController, outgoingVideo: Bool)
    func callActiveControllerDecline(_ controller: CallActiveController)
    func callActiveControllerSwitchCamera(_ controller: CallActiveController)
}

private struct Constants {
    static let BigCenterContainerTopOffset = 50.0
    static let BigButtonOffset = 30.0

    static let SmallBottomOffset: CGFloat = -KQSpace.x32

    // KHANDAQ (calls stage, 26.11): the switch of the camera in the top trailing corner of the picture in
    // picture: a 32 pt circle with a 20 pt glyph, 6 in from the corner, its touch the full 44 pt target.
    static let SwitchCameraSide = KQSize.chip
    static let SwitchCameraOffset = KQSpace.x6
}

/// KHANDAQ (calls stage, 26.11): a small round button whose touch reaches the 44 pt target around it.
private final class SwitchCameraButton: UIButton {
    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        let grow = max(0, KQSize.targetIos - min(bounds.width, bounds.height)) / 2
        return bounds.insetBy(dx: -grow, dy: -grow).contains(point)
    }
}

/// A call that is ringing out or going on (redesign stage 3, E9): the person, "Calling" or the timer in
/// tabular figures, the link, and the row of 64 pt controls at the bottom with their words: microphone,
/// speaker, camera and end.
///
/// KHANDAQ (calls stage, 26.11): the video of the other person fills the stage in the shape of its frame
/// (R1); yours stands in a box of the shape of your camera in a corner clear of the head and the row
/// (R2 to R5), which you drag to another corner or tap to swap the two (R6). A call without video
/// gathers the person's tile, the name, the state and the link in the middle (R9).
class CallActiveController: CallBaseController {
    enum State {
        case none
        case reaching
        case active(duration: TimeInterval)
    }

    weak var delegate: CallActiveControllerDelegate?

    var state: State = .none {
        didSet {
            // load view
            _ = view

            switch state {
                case .none:
                    setInfo(nil, isTimer: false)
                    stopCallDurationTimer()
                case .reaching:
                    setInfo(String(localized: "air_call_calling"), isTimer: false)
                    stopCallDurationTimer()

                    bigVideoButton?.isEnabled = false
                    smallVideoButton?.isEnabled = false
                case .active(let duration):
                    bigVideoButton?.isEnabled = true
                    smallVideoButton?.isEnabled = true

                    // KHANDAQ (#121): drive the on-screen timer from a local 1s tick, synced once to
                    // toxav's callDuration. Previously the label updated only when callDuration changed,
                    // so it froze for a moment whenever a camera (video) toggle made toxav briefly stall
                    // its duration updates. The monotonic systemUptime base keeps it accurate (no drift)
                    // while ticking smoothly through such stalls.
                    if callDurationTimer == nil {
                        callDurationBase = duration
                        callDurationBaseTime = ProcessInfo.processInfo.systemUptime
                        setInfo(AirCallText.duration(duration), isTimer: true)
                        startCallDurationTimer()
                    }
            }
        }
    }

    // KHANDAQ (#121): local smooth call-duration timer (see the .active state case).
    fileprivate var callDurationTimer: Timer?
    fileprivate var callDurationBase: TimeInterval = 0
    fileprivate var callDurationBaseTime: TimeInterval = 0

    var mute: Bool = false {
        didSet {
            bigMuteButton?.isSelected = mute
            smallMuteButton?.isSelected = mute
        }
    }

    var speaker: Bool = false {
        didSet {
            bigSpeakerButton?.isSelected = speaker
            smallSpeakerButton?.isSelected = speaker
        }
    }

    var outgoingVideo: Bool = false {
        didSet {
            bigVideoButton?.isSelected = outgoingVideo
            smallVideoButton?.isSelected = outgoingVideo
            // KHANDAQ (calls stage, 26.11): the coordinator sets this with every update of the call, which
            // is also when the camera may have told its format or its turn.
            refreshOwnFrame()
            if outgoingVideo != oldValue {
                updateStage()
            }
        }
    }

    var videoFeed: UIView? {
        didSet {
            if oldValue === videoFeed {
                return
            }

            // KHANDAQ (calls stage, 26.11): the feed takes its place in a slot that keeps the shape of its
            // frames (R1); it was pinned to the edges of the screen and drew them aspect-fit.
            remoteSlot.show(videoFeed)
            updateStage()
            if isViewLoaded {
                updateViewsWithTraitCollection(self.traitCollection)
            }
        }
    }

    var videoPreviewLayer: CALayer? {
        didSet {
            if oldValue === videoPreviewLayer {
                return
            }

            // KHANDAQ (calls stage, 26.11): your camera covers its box, cropped, never stretched (R2);
            // the preview's own gravity put bars inside the box.
            (videoPreviewLayer as? AVCaptureVideoPreviewLayer)?.videoGravity = .resizeAspectFill
            ownSlot.show(layer: videoPreviewLayer)
            refreshOwnFrame()
            updateStage()
            if isViewLoaded {
                updateViewsWithTraitCollection(self.traitCollection)
            }
        }
    }

    fileprivate var showControls = true {
        didSet {
            applyControlsOffset()

            toggleTopContainer(hidden: !showControls)

            UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), animations: { [unowned self] in
                self.view.layoutIfNeeded()
            })
        }
    }

    // KHANDAQ (calls stage, 26.11): the two videos, each in its slot; which one is the stage and which
    // the box follows `swapped` (R6).
    fileprivate let remoteSlot = AirCallVideoSlot()
    fileprivate let ownSlot = AirCallVideoSlot()
    fileprivate var swapped = false
    /// The corner the box was last let go in; nil until then, bottom trailing.
    fileprivate var ownCorner: AirCallStage.Corner?
    fileprivate var draggingBox = false
    fileprivate var snapAnimator: UIViewPropertyAnimator?
    fileprivate var controlsTap: UITapGestureRecognizer!
    fileprivate var switchCameraButton: UIButton!

    fileprivate var bigContainerView: UIView!
    fileprivate var bigCenterContainer: UIView!
    fileprivate var bigMuteButton: CallButton?
    fileprivate var bigSpeakerButton: CallButton?
    fileprivate var bigVideoButton: CallButton?
    fileprivate var bigDeclineButton: CallButton?

    fileprivate var smallContainerViewBottomConstraint: Constraint!

    // KHANDAQ (remote-video diagnostic): tiny line under the timer showing incoming-frame count and
    // the video flags, so a screenshot of a broken video call pinpoints where the chain breaks.
    fileprivate var debugLabel: UILabel!
    var debugVideoInfo: String = "" {
        didSet {
            _ = view
            // KHANDAQ (calls stage, 26.11): never on the screen of a release build, whatever it is sent
            // (R10); UserDefaultsManager.DebugMode already keeps it empty there.
            #if DEBUG
            debugLabel?.text = debugVideoInfo
            #endif
            debugLabel?.isHidden = debugLabel?.text?.isEmpty ?? true
        }
    }

    fileprivate var smallContainerView: UIView!
    fileprivate var smallMuteButton: CallButton?
    fileprivate var smallSpeakerButton: CallButton?
    fileprivate var smallVideoButton: CallButton?
    fileprivate var smallDeclineButton: CallButton?

    override init(theme: Theme, callerName: String) {
        super.init(theme: theme, callerName: callerName)

        // KHANDAQ (calls stage, 26.11): the camera says its format once it runs and when it changes,
        // and the preview turns with the phone; the box takes the new shape (R2).
        let center = NotificationCenter.default
        for name in [NSNotification.Name.AVCaptureSessionDidStartRunning,
                     NSNotification.Name.AVCaptureInputPortFormatDescriptionDidChange,
                     NSNotification.Name.UIDeviceOrientationDidChange] {
            center.addObserver(self, selector: #selector(CallActiveController.cameraMayHaveChanged), name: name, object: nil)
        }
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        callDurationTimer?.invalidate()
        NotificationCenter.default.removeObserver(self)
    }

    override func loadView() {
        super.loadView()

        createStage()
        createGestureRecognizers()
        createBigViews()
        createSmallViews()
        installConstraints()
        createDebugLabel()

        view.bringSubview(toFront: topContainer)
        view.bringSubview(toFront: debugLabel)

        setButtonsInitValues()

        updateViewsWithTraitCollection(self.traitCollection)
        updateStage()
    }

    override func willTransition(to newCollection: UITraitCollection, with coordinator: UIViewControllerTransitionCoordinator) {
        updateViewsWithTraitCollection(newCollection)
        showControls = true
    }

    override func viewSafeAreaInsetsDidChange() {
        super.viewSafeAreaInsetsDidChange()
        // Hidden, the row and the head stay just past the safe edges, wherever those are now.
        if !showControls {
            applyControlsOffset()
            toggleTopContainer(hidden: true)
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        layoutStage()
    }

    override func prepareForRemoval() {
        super.prepareForRemoval()

        stopCallDurationTimer()
        bigMuteButton?.isEnabled = false
        bigSpeakerButton?.isEnabled = false
        bigVideoButton?.isEnabled = false
        bigDeclineButton?.isEnabled = false

        smallMuteButton?.isEnabled = false
        smallSpeakerButton?.isEnabled = false
        smallVideoButton?.isEnabled = false
        smallDeclineButton?.isEnabled = false
    }
}

// MARK: Actions
extension CallActiveController {
    @objc func tapOnView() {
        // KHANDAQ: the bottom row is now the ONLY layout, so tap-to-hide must stay a video-call
        // feature; otherwise any stray tap during an audio call hides the controls.
        guard videoFeed != nil || videoPreviewLayer != nil else {
            return
        }

        showControls = !showControls
    }

    @objc func muteButtonPressed(_ button: CallButton) {
        mute = !button.isSelected
        delegate?.callActiveController(self, mute: mute)
    }

    @objc func speakerButtonPressed(_ button: CallButton) {
        speaker = !button.isSelected
        delegate?.callActiveController(self, speaker: speaker)
    }

    @objc func videoButtonPressed(_ button: CallButton) {
        outgoingVideo = !button.isSelected
        delegate?.callActiveController(self, outgoingVideo: outgoingVideo)
    }

    @objc func declineButtonPressed() {
        delegate?.callActiveControllerDecline(self)
    }

    @objc func switchCameraButtonPressed() {
        delegate?.callActiveControllerSwitchCamera(self)
    }

    /// R6: a tap on the box swaps the big and the small video; a tap again swaps them back.
    @objc func boxTapped() {
        guard videoFeed != nil, videoPreviewLayer != nil else {
            return
        }
        swapped = !swapped
        updateStage()
    }

    /// R6: the box follows the finger anywhere on the stage and, let go, snaps to the corner nearest to
    /// where its speed carries it.
    @objc func boxPanned(_ pan: UIPanGestureRecognizer) {
        guard let box = pan.view else {
            return
        }
        switch pan.state {
            case .began:
                draggingBox = true
                snapAnimator?.stopAnimation(true)
                snapAnimator = nil
            case .changed:
                let translation = pan.translation(in: view)
                pan.setTranslation(.zero, in: view)
                var frame = box.frame.offsetBy(dx: translation.x, dy: translation.y)
                frame.origin.x = max(view.bounds.minX, min(frame.origin.x, view.bounds.maxX - frame.width))
                frame.origin.y = max(view.bounds.minY, min(frame.origin.y, view.bounds.maxY - frame.height))
                box.frame = frame
            case .ended, .cancelled, .failed:
                draggingBox = false
                let area = ownArea()
                let obstacles = ownObstacles()
                let corner = AirCallStage.snapCorner(center: box.center, velocity: pan.velocity(in: view), size: box.bounds.size,
                                                     area: area, obstacles: obstacles)
                ownCorner = corner
                let target = AirCallStage.ownRect(size: box.bounds.size, corner: corner, area: area, obstacles: obstacles)
                guard !UIAccessibilityIsReduceMotionEnabled() else {
                    box.frame = target
                    return
                }
                let animator = UIViewPropertyAnimator(duration: KQMotion.layout, timingParameters: KQMotion.easeOut)
                animator.addAnimations {
                    box.frame = target
                }
                animator.startAnimation()
                snapAnimator = animator
            default:
                break
        }
    }

    @objc func cameraMayHaveChanged() {
        DispatchQueue.main.async { [weak self] in
            self?.refreshOwnFrame()
        }
    }
}

// MARK: Gestures
extension CallActiveController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard let touched = touch.view else {
            return true
        }
        // A tap on a button (the switch of the camera, a control of the row) is the button's.
        if gestureRecognizer is UITapGestureRecognizer && touched is UIControl {
            return false
        }
        // A touch on the box is the box's: it swaps or drags the videos rather than hiding the controls.
        if gestureRecognizer === controlsTap, let box = currentBox {
            return !touched.isDescendant(of: box)
        }
        return true
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let slot = gestureRecognizer.view as? AirCallVideoSlot else {
            return true
        }
        // The slot on the stage has nothing to drag or swap.
        return slot === currentBox
    }
}

private extension CallActiveController {
    // KHANDAQ (#121): smooth, drift-free call-duration ticking (see the .active state case).
    func startCallDurationTimer() {
        callDurationTimer?.invalidate()
        callDurationTimer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            guard let self = self else {
                return
            }
            let elapsed = self.callDurationBase + (ProcessInfo.processInfo.systemUptime - self.callDurationBaseTime)
            self.setInfo(AirCallText.duration(elapsed), isTimer: true)
        }
    }

    func stopCallDurationTimer() {
        callDurationTimer?.invalidate()
        callDurationTimer = nil
    }

    func createGestureRecognizers() {
        let tapGR = UITapGestureRecognizer(target: self, action: #selector(CallActiveController.tapOnView))
        tapGR.delegate = self
        view.addGestureRecognizer(tapGR)
        controlsTap = tapGR

        // Either slot can be the box, so both listen; only the box's recognizers begin.
        for slot in [remoteSlot, ownSlot] {
            let pan = UIPanGestureRecognizer(target: self, action: #selector(CallActiveController.boxPanned(_:)))
            pan.delegate = self
            slot.addGestureRecognizer(pan)

            let tap = UITapGestureRecognizer(target: self, action: #selector(CallActiveController.boxTapped))
            tap.delegate = self
            slot.addGestureRecognizer(tap)
        }
    }

    /// KHANDAQ (calls stage, 26.11): the two slots of video, under the head and the controls, and the
    /// switch of the camera, which stays in the corner of whichever is the box.
    func createStage() {
        remoteSlot.isHidden = true
        ownSlot.isHidden = true
        remoteSlot.accessibilityIdentifier = "air.call.video.remote"
        ownSlot.accessibilityIdentifier = "air.call.video.own"
        view.insertSubview(ownSlot, at: 0)
        view.insertSubview(remoteSlot, at: 0)

        // The 25 pt glyph drawn at the icon size, in the middle of the 32 pt circle.
        let glyph = UIImage.templateNamed("switch-camera")
        let glyphBounds = CGRect(x: 0, y: 0, width: KQSize.icon, height: KQSize.icon)
        let image = UIGraphicsImageRenderer(bounds: glyphBounds).image { _ in
            glyph.draw(in: AVMakeRect(aspectRatio: glyph.size, insideRect: glyphBounds))
        }.withRenderingMode(.alwaysTemplate)

        switchCameraButton = SwitchCameraButton()
        switchCameraButton.tintColor = KQColor.fixedWhite
        // The dark scrim, as under the link chip, so the white glyph reads over any picture.
        switchCameraButton.backgroundColor = KQColor.scrim
        switchCameraButton.layer.cornerRadius = Constants.SwitchCameraSide / 2
        switchCameraButton.setImage(image, for: UIControlState())
        switchCameraButton.accessibilityLabel = String(localized: "air_call_switch_camera")
        switchCameraButton.accessibilityIdentifier = "air.call.switch.camera"
        switchCameraButton.addTarget(self, action: #selector(CallActiveController.switchCameraButtonPressed), for: .touchUpInside)
        ownSlot.addSubview(switchCameraButton)
    }

    /// The slot that is the box right now, if any.
    var currentBox: AirCallVideoSlot? {
        return slot(for: placement.box)
    }

    var placement: (stage: AirCallStage.Video?, box: AirCallStage.Video?) {
        return AirCallStage.placement(remote: videoFeed != nil, own: videoPreviewLayer != nil, swapped: swapped)
    }

    func slot(for video: AirCallStage.Video?) -> AirCallVideoSlot? {
        switch video {
            case .some(.remote):
                return remoteSlot
            case .some(.own):
                return ownSlot
            case .none:
                return nil
        }
    }

    /// KHANDAQ (calls stage, 26.11): which video is where, and what the head does about it: over video
    /// it gets its shadow (R8); with no video either way it gathers in the middle with the tile (R9),
    /// and the controls, which only a video call hides, come back.
    func updateStage() {
        guard isViewLoaded else {
            return
        }
        let remote = videoFeed != nil
        let own = videoPreviewLayer != nil
        if !(remote && own) {
            swapped = false
        }
        let noVideo = !remote && !own && !outgoingVideo
        centresHead = noVideo
        headOverVideo = placement.stage != nil
        if !remote && !own && !showControls {
            showControls = true
        }
        view.setNeedsLayout()
    }

    /// R2: the shape of your camera as its preview shows it, read again whenever it may have changed.
    func refreshOwnFrame() {
        let frame = videoPreviewLayer.flatMap { AirCallCamera.shownFrame(of: $0) }
        guard frame != ownSlot.layerFrame else {
            return
        }
        ownSlot.layerFrame = frame
        if isViewLoaded {
            view.setNeedsLayout()
        }
    }

    /// R5: the stage within its safe edges, 16 in.
    func ownArea() -> CGRect {
        return AirCallStage.ownArea(stage: view.bounds, safeInsets: view.safeAreaInsets)
    }

    /// R5: the row and what shows of the head, while they show.
    func ownObstacles() -> [CGRect] {
        guard showControls else {
            return []
        }
        return [smallContainerView.frame, headContentFrame()]
    }

    /// R1 to R5: the stage takes the whole screen behind the head and the controls; the box its size
    /// and corner, above them, unless a finger holds it.
    func layoutStage() {
        let current = placement
        remoteSlot.isHidden = videoFeed == nil
        ownSlot.isHidden = videoPreviewLayer == nil

        if let stage = slot(for: current.stage) {
            stage.isBox = false
            stage.frame = view.bounds
            if view.subviews.first !== stage {
                view.sendSubview(toBack: stage)
            }
        }

        // The switch of the camera belongs to the box; left in a slot that became the stage after a
        // swap, it would stand under the status bar.
        guard let box = slot(for: current.box) else {
            switchCameraButton.isHidden = true
            return
        }
        switchCameraButton.isHidden = false
        box.isBox = true
        let rightToLeft = view.effectiveUserInterfaceLayoutDirection == .rightToLeft
        let size = AirCallStage.ownSize(frame: ownSlot.layerFrame, stage: view.bounds.size)
        if draggingBox {
            let center = box.center
            box.bounds.size = size
            box.center = center
        }
        else if snapAnimator?.isRunning != true {
            let corner = ownCorner ?? .bottomTrailing(rightToLeft: rightToLeft)
            box.frame = AirCallStage.ownRect(size: size, corner: corner, area: ownArea(), obstacles: ownObstacles())
        }
        if view.subviews.last !== debugLabel || view.subviews.dropLast().last !== box {
            view.bringSubview(toFront: box)
            view.bringSubview(toFront: debugLabel)
        }

        if switchCameraButton.superview !== box {
            box.addSubview(switchCameraButton)
        }
        let side = Constants.SwitchCameraSide
        let inset = Constants.SwitchCameraOffset
        switchCameraButton.frame = CGRect(x: rightToLeft ? inset : box.bounds.width - inset - side, y: inset, width: side, height: side)
    }

    /// The row at the bottom while the controls show; hidden, just past the safe bottom, so no part of
    /// it peeks over the home indicator.
    func applyControlsOffset() {
        let offset = showControls ? Constants.SmallBottomOffset : smallContainerView.frame.size.height + view.safeAreaInsets.bottom
        smallContainerViewBottomConstraint.update(offset: offset)
    }

    func createBigViews() {
        bigContainerView = UIView()
        bigContainerView.backgroundColor = .clear
        view.addSubview(bigContainerView)

        bigCenterContainer = UIView()
        bigCenterContainer.backgroundColor = .clear
        bigContainerView.addSubview(bigCenterContainer)

        bigMuteButton = addButtonWithType(.mute, buttonSize: .big, action: #selector(CallActiveController.muteButtonPressed(_:)), container: bigCenterContainer)
        bigSpeakerButton = addButtonWithType(.speaker, buttonSize: .big, action: #selector(CallActiveController.speakerButtonPressed(_:)), container: bigCenterContainer)
        bigVideoButton = addButtonWithType(.video, buttonSize: .big, action: #selector(CallActiveController.videoButtonPressed(_:)), container: bigCenterContainer)
        bigDeclineButton = addButtonWithType(.decline, buttonSize: .small, action: #selector(CallActiveController.declineButtonPressed), container: bigContainerView)
    }

    /// The row at the bottom, in the thumb zone: microphone, speaker, camera, end, each with its word.
    func createSmallViews() {
        let row = UIStackView()
        row.axis = .horizontal
        row.alignment = .top
        // Equal columns: four words under the circles fit on the narrowest phone.
        row.distribution = .fillEqually
        smallContainerView = row
        view.addSubview(smallContainerView)

        smallMuteButton = addControl(.mute, action: #selector(CallActiveController.muteButtonPressed(_:)), to: row,
                                     identifier: "air.call.mic")
        smallSpeakerButton = addControl(.speaker, action: #selector(CallActiveController.speakerButtonPressed(_:)), to: row,
                                        identifier: "air.call.speaker")
        smallVideoButton = addControl(.video, action: #selector(CallActiveController.videoButtonPressed(_:)), to: row,
                                      identifier: "air.call.camera")
        smallDeclineButton = addControl(.decline, action: #selector(CallActiveController.declineButtonPressed), to: row,
                                        identifier: "air.call.end")
    }

    func addControl(_ type: CallButton.ButtonType, action: Selector, to row: UIStackView, identifier: String) -> CallButton {
        let button = CallButton(theme: theme, type: type, buttonSize: .small)
        button.accessibilityIdentifier = identifier
        button.addTarget(self, action: action, for: .touchUpInside)
        row.addArrangedSubview(AirCallControl(button: button, title: CallButton.title(for: type)))
        return button
    }

    func createDebugLabel() {
        debugLabel = UILabel()
        debugLabel.isHidden = true
        debugLabel.font = UIFont.monospacedDigitSystemFont(ofSize: 11.0, weight: .medium)
        debugLabel.textColor = .systemYellow
        debugLabel.textAlignment = .center
        debugLabel.numberOfLines = 0
        debugLabel.text = debugVideoInfo
        view.addSubview(debugLabel)
        debugLabel.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(60.0)
            $0.centerX.equalTo(view)
            $0.leading.greaterThanOrEqualTo(view).offset(8.0)
            $0.trailing.lessThanOrEqualTo(view).offset(-8.0)
        }
    }

    func addButtonWithType(_ type: CallButton.ButtonType, buttonSize: CallButton.ButtonSize, action: Selector, container: UIView) -> CallButton {
        let button = CallButton(theme: theme, type: type, buttonSize: buttonSize)
        button.addTarget(self, action: action, for: .touchUpInside)
        container.addSubview(button)

        return button
    }

    func installConstraints() {
        bigContainerView.snp.makeConstraints {
            $0.top.equalTo(topContainer.snp.bottom)
            $0.leading.trailing.bottom.equalTo(view)
        }

        bigCenterContainer.snp.makeConstraints {
            $0.centerX.equalTo(bigContainerView)
            $0.centerY.equalTo(view)
        }

        bigMuteButton!.snp.makeConstraints {
            $0.top.equalTo(bigCenterContainer)
            $0.leading.equalTo(bigCenterContainer)
        }

        bigSpeakerButton!.snp.makeConstraints {
            $0.top.equalTo(bigCenterContainer)
            $0.trailing.equalTo(bigCenterContainer)
            $0.leading.equalTo(bigMuteButton!.snp.trailing).offset(Constants.BigButtonOffset)
        }

        bigVideoButton!.snp.makeConstraints {
            $0.top.equalTo(bigMuteButton!.snp.bottom).offset(Constants.BigButtonOffset)
            $0.leading.equalTo(bigCenterContainer)
            $0.bottom.equalTo(bigCenterContainer)
        }

        bigDeclineButton!.snp.makeConstraints {
            $0.centerX.equalTo(bigContainerView)
            $0.top.greaterThanOrEqualTo(bigCenterContainer).offset(Constants.BigButtonOffset)
            $0.bottom.equalTo(bigContainerView).offset(-Constants.BigButtonOffset)
        }

        smallContainerView.snp.makeConstraints {
            // KHANDAQ: pin the control row above the home indicator (safe area), not the raw
            // screen bottom; on notch devices the row used to sit under the gesture bar.
            smallContainerViewBottomConstraint = $0.bottom.equalTo(view.safeAreaLayoutGuide.snp.bottom).offset(Constants.SmallBottomOffset).constraint
            // KHANDAQ (calls stage, 26.11): four columns of 96 at most, in the middle of a wide stage
            // (R7); on a phone the full width less 16 each side, as before.
            $0.centerX.equalTo(view.safeAreaLayoutGuide)
            $0.leading.greaterThanOrEqualTo(view.safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.lessThanOrEqualTo(view.safeAreaLayoutGuide).offset(-KQSpace.x16)
            $0.width.lessThanOrEqualTo(AirCallStage.rowMaxWidth)
            $0.width.equalTo(view.safeAreaLayoutGuide).offset(-2 * KQSpace.x16).priority(999)
        }

        // R9: a call without video centres its head between the safe top and the row.
        pinHeadArea(above: smallContainerView.snp.top)
    }

    func setButtonsInitValues() {
        bigMuteButton?.isSelected = mute
        smallMuteButton?.isSelected = mute

        bigSpeakerButton?.isSelected = speaker
        smallSpeakerButton?.isSelected = speaker

        bigVideoButton?.isSelected = outgoingVideo
        smallVideoButton?.isSelected = outgoingVideo
    }

    func updateViewsWithTraitCollection(_ traitCollection: UITraitCollection) {
        // KHANDAQ: one uniform bottom-row control layout (mic/speaker/video/hangup) on all devices
        // and orientations. The old size-class switch picked a scattered mid-screen layout on
        // hRegular phones, and stale .unspecified traits at loadView made the choice
        // device/timing-dependent (iPhone 13 got the scattered layout, iPhone 11 the row).
        bigContainerView.isHidden = true
        smallContainerView.isHidden = false
    }
}
