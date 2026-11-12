// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

protocol CallIncomingControllerDelegate: class {
    func callIncomingControllerDecline(_ controller: CallIncomingController)
    func callIncomingControllerAnswerAudio(_ controller: CallIncomingController)
    func callIncomingControllerAnswerVideo(_ controller: CallIncomingController)
}

/// An incoming call (redesign stage 3, E9): it comes down from the top like a shade, the person and the
/// link first, Decline, Answer and Answer with video at the bottom. Once answered the whole screen
/// gives way to the call (CallCoordinator puts CallActiveController in its place).
class CallIncomingController: CallBaseController {
    weak var delegate: CallIncomingControllerDelegate?

    fileprivate var declineButton: CallButton!
    fileprivate var audioButton: CallButton!
    fileprivate var videoButton: CallButton!
    fileprivate let controls = UIStackView()
    fileprivate var hasAppeared = false

    override func loadView() {
        super.loadView()

        createViews()
        installConstraints()

        setInfo(String(localized: "air_call_incoming"), isTimer: false)
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        guard !hasAppeared else {
            return
        }
        hasAppeared = true
        // The shade: from above the screen down into place; with reduced motion it only fades in, with
        // the presentation.
        guard animated, !UIAccessibilityIsReduceMotionEnabled() else {
            return
        }
        let height = max(view.bounds.height, UIScreen.main.bounds.height)
        view.transform = CGAffineTransform(translationX: 0, y: -height)
        let animator = UIViewPropertyAnimator(duration: KQMotion.reveal, timingParameters: KQMotion.easeOut)
        animator.addAnimations {
            self.view.transform = .identity
        }
        animator.startAnimation()
    }

    override func prepareForRemoval() {
        super.prepareForRemoval()

        declineButton.isEnabled = false
        audioButton.isEnabled = false
        videoButton.isEnabled = false
    }
}

// MARK: Actions
extension CallIncomingController {
    @objc func declineButtonPressed() {
        delegate?.callIncomingControllerDecline(self)
    }

    @objc func audioButtonPressed() {
        delegate?.callIncomingControllerAnswerAudio(self)
    }

    @objc func videoButtonPressed() {
        delegate?.callIncomingControllerAnswerVideo(self)
    }
}

private extension CallIncomingController {
    func createViews() {
        declineButton = CallButton(theme: theme, type: .decline, buttonSize: .big)
        declineButton.accessibilityIdentifier = "air.call.decline"
        declineButton.addTarget(self, action: #selector(CallIncomingController.declineButtonPressed), for: .touchUpInside)

        audioButton = CallButton(theme: theme, type: .answerAudio, buttonSize: .big)
        audioButton.accessibilityIdentifier = "air.call.answer"
        audioButton.addTarget(self, action: #selector(CallIncomingController.audioButtonPressed), for: .touchUpInside)

        videoButton = CallButton(theme: theme, type: .answerVideo, buttonSize: .big)
        videoButton.accessibilityIdentifier = "air.call.answer.video"
        videoButton.addTarget(self, action: #selector(CallIncomingController.videoButtonPressed), for: .touchUpInside)

        controls.axis = .horizontal
        controls.alignment = .top
        controls.distribution = .fillEqually
        controls.addArrangedSubview(AirCallControl(button: declineButton, title: String(localized: "air_call_decline")))
        controls.addArrangedSubview(AirCallControl(button: videoButton, title: String(localized: "air_call_answer_video")))
        controls.addArrangedSubview(AirCallControl(button: audioButton, title: String(localized: "air_call_answer")))
        view.addSubview(controls)
    }

    func installConstraints() {
        controls.snp.makeConstraints {
            $0.leading.trailing.equalTo(view.safeAreaLayoutGuide).inset(KQSpace.x32)
            $0.top.greaterThanOrEqualTo(topContainer.snp.bottom).offset(KQSpace.x24)
            $0.bottom.equalTo(view.safeAreaLayoutGuide).offset(-KQSpace.x32)
        }
    }
}
