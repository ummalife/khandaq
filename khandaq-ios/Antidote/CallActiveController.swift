// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

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

    static let VideoPreviewOffset = -20.0
    static let VideoPreviewSize = CGSize(width: 150.0, height: 110)
    static let SwitchCameraOffset = KQSpace.x6
}

/// A call that is ringing out or going on (redesign stage 3, E9): the person, "Calling" or the timer in
/// tabular figures, the link, and the row of 64 pt controls at the bottom with their words: microphone,
/// speaker, camera and end.
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
        }
    }

    var videoFeed: UIView? {
        didSet {
            if oldValue === videoFeed {
                return
            }

            if let old = oldValue {
                old.removeFromSuperview()
            }

            if let feed = videoFeed {
                view.insertSubview(feed, belowSubview: videoPreviewView)

                feed.bounds.size = view.bounds.size

                feed.snp.makeConstraints {
                    $0.edges.equalTo(view)
                }

                updateViewsWithTraitCollection(self.traitCollection)
            }
        }
    }

    var videoPreviewLayer: CALayer? {
        didSet {
            if oldValue === videoPreviewLayer {
                return
            }

            if let old = oldValue {
                old.removeFromSuperlayer()
                videoPreviewView.isHidden = true
            }

            if let layer = videoPreviewLayer {
                videoPreviewView.layer.addSublayer(layer)
                videoPreviewView.bringSubview(toFront: switchCameraButton)
                videoPreviewView.isHidden = false
                view.layoutIfNeeded()
            }

            updateViewsWithTraitCollection(self.traitCollection)
        }
    }

    fileprivate var showControls = true {
        didSet {
            let offset = showControls ? Constants.SmallBottomOffset : smallContainerView.frame.size.height
            smallContainerViewBottomConstraint.update(offset: offset)

            toggleTopContainer(hidden: !showControls)

            UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), animations: { [unowned self] in
                self.view.layoutIfNeeded()
            })
        }
    }

    fileprivate var videoPreviewView: UIView!
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
            debugLabel?.text = debugVideoInfo
        }
    }

    fileprivate var smallContainerView: UIView!
    fileprivate var smallMuteButton: CallButton?
    fileprivate var smallSpeakerButton: CallButton?
    fileprivate var smallVideoButton: CallButton?
    fileprivate var smallDeclineButton: CallButton?

    override init(theme: Theme, callerName: String) {
        super.init(theme: theme, callerName: callerName)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        callDurationTimer?.invalidate()
    }

    override func loadView() {
        super.loadView()

        createGestureRecognizers()
        createVideoPreviewView()
        createBigViews()
        createSmallViews()
        installConstraints()
        createDebugLabel()

        view.bringSubview(toFront: topContainer)
        view.bringSubview(toFront: debugLabel)

        setButtonsInitValues()

        updateViewsWithTraitCollection(self.traitCollection)
    }

    override func willTransition(to newCollection: UITraitCollection, with coordinator: UIViewControllerTransitionCoordinator) {
        updateViewsWithTraitCollection(newCollection)
        showControls = true
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()

        if let layer = videoPreviewLayer {
            layer.frame.size = videoPreviewView.frame.size
        }
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
        view.addGestureRecognizer(tapGR)
    }

    func createVideoPreviewView() {
        videoPreviewView = UIView()
        videoPreviewView.backgroundColor = KQColor.lockBottom
        videoPreviewView.layer.cornerRadius = KQRadius.card
        videoPreviewView.clipsToBounds = true
        view.addSubview(videoPreviewView)

        videoPreviewView.isHidden = !outgoingVideo

        let image = UIImage.templateNamed("switch-camera")

        switchCameraButton = UIButton()
        switchCameraButton.tintColor = KQColor.fixedWhite
        switchCameraButton.backgroundColor = KQColor.mediaControl
        switchCameraButton.layer.cornerRadius = KQSize.targetIos / 2
        switchCameraButton.setImage(image, for: UIControlState())
        switchCameraButton.accessibilityLabel = String(localized: "air_call_switch_camera")
        switchCameraButton.addTarget(self, action: #selector(CallActiveController.switchCameraButtonPressed), for: .touchUpInside)
        videoPreviewView.addSubview(switchCameraButton)
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
        videoPreviewView.snp.makeConstraints {
            $0.trailing.equalTo(view).offset(Constants.VideoPreviewOffset)
            $0.bottom.equalTo(smallContainerView.snp.top).offset(Constants.VideoPreviewOffset)
            $0.width.equalTo(Constants.VideoPreviewSize.width)
            $0.height.equalTo(Constants.VideoPreviewSize.height)
        }

        switchCameraButton.snp.makeConstraints {
            $0.top.equalTo(videoPreviewView).offset(Constants.SwitchCameraOffset)
            $0.trailing.equalTo(videoPreviewView).offset(-Constants.SwitchCameraOffset)
            $0.size.equalTo(KQSize.targetIos)
        }

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
            $0.leading.trailing.equalTo(view.safeAreaLayoutGuide).inset(KQSpace.x16)
        }
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
