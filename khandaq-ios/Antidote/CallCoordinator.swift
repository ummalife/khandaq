// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import Foundation
import CallKit

protocol CallCoordinatorDelegate: class {
    func callCoordinator(_ coordinator: CallCoordinator, notifyAboutBackgroundCallFrom caller: String, userInfo: String)
    func callCoordinatorDidStartCall(_ coordinator: CallCoordinator)
    func callCoordinatorDidFinishCall(_ coordinator: CallCoordinator)
}

private struct Constants {
    static let DeclineAfterInterval = 1.5
    // KHANDAQ: number of consecutive call-update ticks (~1s each) with NO new video frames before we
    // detach the remote feed. The cumulative frame count never decreases, so we detect a stopped
    // stream by frames no longer arriving — a short grace avoids flicker on a brief network stall.
    static let NoVideoFrameTicksToDetach = 2
}

private class ActiveCall {
    var callToken: RLMNotificationToken?

    fileprivate let call: OCTCall
    fileprivate let navigation: UINavigationController

    fileprivate var usingFrontCamera: Bool = true

    init(call: OCTCall, navigation: UINavigationController) {
        self.call = call
        self.navigation = navigation
    }

    deinit {
        callToken?.invalidate()
    }
}

class CallCoordinator: NSObject {
    weak var delegate: CallCoordinatorDelegate?

    fileprivate let theme: Theme
    fileprivate weak var presentingController: UIViewController!
    fileprivate weak var submanagerCalls: OCTSubmanagerCalls!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate var providerdelegate: ProviderDelegate!

    fileprivate let audioPlayer = AudioPlayer()

    fileprivate var incomingCallKitUUID: UUID?
    fileprivate var preferredAnswerVideo: Bool?
    fileprivate var toxAnswerInProgress = false
    fileprivate var toxAnswerCompleted = false

    // KHANDAQ: track remote-video liveness by frame-count delta (see activeCallWasUpdated).
    fileprivate var lastReceivedVideoFrameCount = 0
    fileprivate var noNewVideoFrameTicks = Constants.NoVideoFrameTicksToDetach

    // KHANDAQ: the speaker default (on for video, earpiece for audio) is applied ONCE per call, not on
    // every update tick — otherwise the user's manual speaker toggle was clobbered ~1s later.
    fileprivate var appliedInitialSpeakerRoute = false

    fileprivate var activeCall: ActiveCall? {
        didSet {
            switch (oldValue, activeCall) {
                case (.none, .some):
                    // KHANDAQ: fresh call — reset remote-video frame tracking so a previous call's
                    // cumulative count doesn't leave the feed attached (last frame frozen) on this one.
                    lastReceivedVideoFrameCount = 0
                    noNewVideoFrameTicks = Constants.NoVideoFrameTicksToDetach
                    appliedInitialSpeakerRoute = false
                    delegate?.callCoordinatorDidStartCall(self)
                case (.some, .none):
                    delegate?.callCoordinatorDidFinishCall(self)
                default:
                    break
            }
        }
    }

    init(theme: Theme, presentingController: UIViewController, submanagerCalls: OCTSubmanagerCalls, submanagerObjects: OCTSubmanagerObjects) {
        self.theme = theme
        self.presentingController = presentingController
        self.submanagerCalls = submanagerCalls
        self.submanagerObjects = submanagerObjects

        super.init()

        // CALL:
        print("cc:controler:init:01")

        submanagerCalls.delegate = self
    }

    func callToChat(_ chat: OCTChat, enableVideo: Bool) {

        // CALL:
        print("cc:controler:callToChat:01")

        ensureMicrophonePermission { [weak self] granted in
            guard let self = self else { return }
            guard granted else {
                handleErrorWithType(.callToChat)
                return
            }
            // KHANDAQ (#112): a video call also needs camera access. Without this gate the camera was
            // opened blind and AVCaptureDeviceInput failed with a generic "Внутренняя ошибка" when
            // access was denied/not-yet-granted. Request it up front, then start the video call.
            guard enableVideo else {
                self.startCallToChat(chat, enableVideo: false)
                return
            }
            MediaPermission.requestCameraAccess(from: self.presentingController) { [weak self] cameraGranted in
                guard let self = self, cameraGranted else { return }
                self.startCallToChat(chat, enableVideo: true)
            }
        }
    }

    private func startCallToChat(_ chat: OCTChat, enableVideo: Bool) {
        do {
            let call = try submanagerCalls.call(to: chat, enableAudio: true, enableVideo: enableVideo)
            var nickname = String(localized: "contact_deleted")

            if let friend = chat.friends.lastObject() as? OCTFriend {
                nickname = friend.nickname
            }

            let controller = CallActiveController(theme: theme, callerName: nickname)
            controller.delegate = self

            // CALL:
            print("cc:controler:callToChat:02")

            startActiveCallWithCall(call, controller: controller)
        }
        catch let error as NSError {
            handleErrorWithType(.callToChat, error: error)
        }
    }

    func answerIncomingCallWithUserInfo(_ userInfo: String) {

        // CALL:
        print("cc:controler:answerIncomingCallWithUserInfo:01")

        guard let activeCall = activeCall else { return }
        guard activeCall.call.uniqueIdentifier == userInfo else { return }
        guard activeCall.call.status == .ringing else { return }

        answerCall(enableVideo: false)
    }
}

extension CallCoordinator: CoordinatorProtocol {
    func startWithOptions(_ options: CoordinatorOptions?) {
    }
}

extension CallCoordinator: OCTSubmanagerCallDelegate {
    func callSubmanager(_ callSubmanager: OCTSubmanagerCalls!, receive call: OCTCall!, audioEnabled: Bool, videoEnabled: Bool) {
        guard activeCall == nil else {
            // Currently we support only one call at a time
            _ = try? submanagerCalls.send(.cancel, to: call)
            return
        }

        let nickname = call.caller?.nickname ?? ""

        // CALL: start incoming call
        print("cc:controler:incoming_call:01")

        resetCallAnswerState()
        let callKitUUID = UUID()
        incomingCallKitUUID = callKitUUID

        if !UIApplication.isActive {
            delegate?.callCoordinator(self, notifyAboutBackgroundCallFrom: nickname, userInfo: call.uniqueIdentifier)
            print("cc:controler:incoming_call:BG")
        }

        let backgroundTaskIdentifier = UIApplication.shared.beginBackgroundTask(expirationHandler: nil)
        DispatchQueue.main.asyncAfter(wallDeadline: DispatchWallTime.now() + 0.1) {
            AppDelegate.shared.displayIncomingCall(uuid: callKitUUID, handle: nickname, hasVideo: videoEnabled) { _ in
                UIApplication.shared.endBackgroundTask(backgroundTaskIdentifier)
            }
        }

        let controller = CallIncomingController(theme: theme, callerName: nickname)
        controller.delegate = self

        startActiveCallWithCall(call, controller: controller)

        print("cc:controler:incoming_call:99")
    }
}

extension CallCoordinator: CallIncomingControllerDelegate {
    func callIncomingControllerDecline(_ controller: CallIncomingController) {
        // CALL:
        print("cc:controler:callIncomingControllerDecline:01")
        declineCall(callWasRemoved: false)
    }

    func callIncomingControllerAnswerAudio(_ controller: CallIncomingController) {
        // CALL:
        print("cc:controler:callIncomingControllerAnswerAudio:01")
        answerCall(enableVideo: false)
    }

    func callIncomingControllerAnswerVideo(_ controller: CallIncomingController) {
        // CALL:
        print("cc:controler:callIncomingControllerAnswerVideo:01")
        answerCall(enableVideo: true)
    }
}

extension CallCoordinator: CallActiveControllerDelegate {
    func callActiveController(_ controller: CallActiveController, mute: Bool) {
        submanagerCalls.enableMicrophone = !mute
    }

    func callActiveController(_ controller: CallActiveController, speaker: Bool) {
        do {
            try submanagerCalls.routeAudio(toSpeaker: speaker)
        }
        catch {
            handleErrorWithType(.routeAudioToSpeaker)
            controller.speaker = !speaker
        }
    }

    func callActiveController(_ controller: CallActiveController, outgoingVideo: Bool) {
        guard let activeCall = activeCall else {
            assert(false, "This method should be called only if active call is non-nil")
            return
        }

        // KHANDAQ (#112): turning the camera ON mid-call needs camera access first — the video engine
        // otherwise fails and surfaces a generic "Внутренняя ошибка". Turning it OFF needs no permission.
        if outgoingVideo {
            MediaPermission.requestCameraAccess(from: controller) { [weak self] granted in
                guard let self = self else { return }
                guard granted else {
                    controller.outgoingVideo = false
                    return
                }
                do {
                    try submanagerCalls.enableVideoSending(true, for: activeCall.call)
                }
                catch {
                    handleErrorWithType(.enableVideoSending)
                    controller.outgoingVideo = false
                }
            }
            return
        }

        do {
            try submanagerCalls.enableVideoSending(false, for: activeCall.call)
        }
        catch {
            handleErrorWithType(.enableVideoSending)
            controller.outgoingVideo = true
        }
    }

    func callActiveControllerDecline(_ controller: CallActiveController) {
        // CALL:
        print("cc:controler:callActiveControllerDecline:02")
        declineCall(callWasRemoved: false)
    }

    func callActiveControllerSwitchCamera(_ controller: CallActiveController) {
        guard let activeCall = activeCall else {
            assert(false, "This method should be called only if active call is non-nil")
            return
        }

        do {
            let front = !activeCall.usingFrontCamera
            try submanagerCalls.switch(toCameraFront: front)

            self.activeCall?.usingFrontCamera = front
        }
        catch {
            handleErrorWithType(.callSwitchCamera)
        }
    }
}

extension CallCoordinator {
    func declineCall(callWasRemoved wasRemoved: Bool) {
        // CALL:
        print("cc:controler:declineCall:01")

        guard let activeCall = activeCall else {
            // assert(false, "This method should be called only if active call is non-nil")
            return
        }

        if !wasRemoved {
            _ = try? submanagerCalls.send(.cancel, to: activeCall.call)
        }

        audioPlayer.stopAll()

        if let controller = activeCall.navigation.topViewController as? CallBaseController {
            controller.prepareForRemoval()
        }

        let backgroundTaskIdentifier = UIApplication.shared.beginBackgroundTask(expirationHandler: nil)
        DispatchQueue.main.asyncAfter(wallDeadline: DispatchWallTime.now() + 0.1) {
            AppDelegate.shared.endIncomingCalls()
            UIApplication.shared.endBackgroundTask(backgroundTaskIdentifier)
        }
        // self.providerdelegate.endIncomingCall()

        resetCallAnswerState()

        let delayTime = DispatchTime.now() + Double(Int64(Constants.DeclineAfterInterval * Double(NSEC_PER_SEC))) / Double(NSEC_PER_SEC)
        DispatchQueue.main.asyncAfter(deadline: delayTime) { [weak self] in
            self?.presentingController.dismiss(animated: true, completion: nil)
            self?.activeCall = nil
        }
    }

    func consumePreferredAnswerVideo() -> Bool? {
        defer { preferredAnswerVideo = nil }
        return preferredAnswerVideo
    }

    func startActiveCallWithCall(_ call: OCTCall, controller: CallBaseController) {
        guard activeCall == nil else {
            assert(false, "This method should be called only if there is no active call")
            return
        }

        // CALL:
        print("cc:controler:startActiveCallWithCall:01")

        let navigation = UINavigationController(rootViewController: controller)
        navigation.modalPresentationStyle = .overCurrentContext
        navigation.isNavigationBarHidden = true
        navigation.modalTransitionStyle = .crossDissolve

        activeCall = ActiveCall(call: call, navigation: navigation)

        let predicate = NSPredicate(format: "uniqueIdentifier == %@", call.uniqueIdentifier)
        let results = submanagerObjects.calls(predicate: predicate)
        activeCall!.callToken = results.addNotificationBlock { [unowned self] change in
            switch change {
                case .initial:
                    break
                case .update(_, let deletions, _, let modifications):
                    if deletions.count > 0 {
                        self.declineCall(callWasRemoved: true)
                    }
                    else if modifications.count > 0 {
                        self.activeCallWasUpdated()
                    }
                case .error(let error):
                    fatalError("\(error)")
            }
        }

        presentingController.present(navigation, animated: true, completion: nil)
        activeCallWasUpdated()
    }

    func answerCall(enableVideo: Bool) {

        // CALL:
        print("cc:controler:answerCall:01")

        preferredAnswerVideo = enableVideo

        ensureMicrophonePermission { [weak self] granted in
            guard let self = self else { return }
            guard granted else {
                handleErrorWithType(.answerCall, error: nil)
                self.declineCall(callWasRemoved: false)
                return
            }

            if let callKitUUID = self.incomingCallKitUUID,
               AppDelegate.shared.callManager.callWithUUID(uuid: callKitUUID) != nil {
                AppDelegate.shared.callManager.answerViaCallKit(uuid: callKitUUID)
                return
            }

            self.configureAudioSessionForAnswer(enableVideo: enableVideo)
            self.performAnswerCall(enableVideo: enableVideo)
        }
    }

    func performAnswerCall(enableVideo: Bool) {
        guard !toxAnswerInProgress && !toxAnswerCompleted else {
            print("cc:controler:performAnswerCall:skip:already_answered")
            return
        }

        guard let activeCall = activeCall else {
            return
        }

        guard activeCall.call.status == .ringing else {
            print("cc:controler:performAnswerCall:skip:status=\(activeCall.call.status.rawValue)")
            return
        }

        toxAnswerInProgress = true

        do {
            try submanagerCalls.answer(activeCall.call, enableAudio: true, enableVideo: enableVideo)
            toxAnswerCompleted = true
        }
        catch let error as NSError {
            toxAnswerInProgress = false
            handleErrorWithType(.answerCall, error: error)
            declineCall(callWasRemoved: false)
        }
    }

    private func resetCallAnswerState() {
        toxAnswerInProgress = false
        toxAnswerCompleted = false
        preferredAnswerVideo = nil
        incomingCallKitUUID = nil
        submanagerCalls?.callKitAudioSessionIsActive = false
    }

    private func configureAudioSessionForAnswer(enableVideo: Bool) {
        let session = AVAudioSession.sharedInstance()
        let mode = enableVideo ? AVAudioSessionModeVideoChat : AVAudioSessionModeVoiceChat
        var options: AVAudioSessionCategoryOptions = [.allowBluetooth]
        if enableVideo {
            options.insert(.defaultToSpeaker)
        }
        do {
            try session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                with: options
            )
            try session.setMode(mode)
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.005)
            try session.overrideOutputAudioPort(enableVideo ? .speaker : .none)
            try session.setActive(true)
        } catch {
            print("cc:controler:configureAudioSessionForAnswer:error \(error)")
        }
    }

    private func ensureMicrophonePermission(_ completion: @escaping (Bool) -> Void) {
        let session = AVAudioSession.sharedInstance()
        let permission = session.recordPermission()

        if permission == .granted {
            completion(true)
            return
        }
        if permission == .denied {
            completion(false)
            return
        }

        session.requestRecordPermission { granted in
            DispatchQueue.main.async {
                completion(granted)
            }
        }
    }

    func activeCallWasUpdated() {

        // CALL:
        print("cc:controler:activeCallWasUpdated:01")

        guard let activeCall = activeCall else {
            assert(false, "This method should be called only if active call is non-nil")
            return
        }

        switch activeCall.call.status {
            case .ringing:
                if !audioPlayer.isPlayingSound(.Ringtone) {
                    audioPlayer.playSound(.Ringtone, loop: true)
                }

                // no update for ringing status
                return
            case .dialing:
                if !audioPlayer.isPlayingSound(.Calltone) {
                    audioPlayer.playSound(.Calltone, loop: true)
                }
            case .active:
                if audioPlayer.isPlaying() {
                    audioPlayer.stopAll()
                }
        }

        var activeController = activeCall.navigation.topViewController as? CallActiveController

        if (activeController == nil) {
            let nickname = activeCall.call.caller?.nickname ?? ""
            activeController = CallActiveController(theme: theme, callerName: nickname)
            activeController!.delegate = self

            activeCall.navigation.setViewControllers([activeController!], animated: false)
        }

        switch activeCall.call.status {
            case .ringing:
                break
            case .dialing:
                activeController!.state = .reaching
            case .active:
                activeController!.state = .active(duration: activeCall.call.callDuration)
        }

        activeController!.outgoingVideo = activeCall.call.videoIsEnabled
        // KHANDAQ: apply the speaker default (on for video, earpiece for audio) only ONCE, when the call
        // first goes active. Re-applying it on every update tick reset the user's manual speaker toggle
        // about a second after they pressed it ("кнопка сбрасывается автоматически").
        if activeCall.call.status == .active && !appliedInitialSpeakerRoute {
            appliedInitialSpeakerRoute = true
            activeController!.speaker = activeCall.call.videoIsEnabled
            try? submanagerCalls.routeAudio(toSpeaker: activeCall.call.videoIsEnabled)
        }
        if activeCall.call.videoIsEnabled {
            if activeController!.videoPreviewLayer == nil {
                submanagerCalls.getVideoCallPreview { [weak activeController] layer in
                    activeController?.videoPreviewLayer = layer
                }
            }
        }
        else {
            if activeController!.videoPreviewLayer != nil {
                activeController!.videoPreviewLayer = nil
            }
        }

        // KHANDAQ: decide remote-feed attachment by whether frames are ACTUALLY still arriving, using
        // the frame-count delta between ticks. The earlier `frameCount > 0` test was wrong: the count
        // is cumulative and never decreases, so once a single frame arrived the feed stayed attached
        // forever — the last frame froze on screen after the peer turned their camera off or the call
        // went audio-only. friendSendingVideo can attach a touch sooner but must NOT keep the feed
        // alive on its own (toxav's SENDING_V flag lags / can stay stale).
        let frameCount = Int(submanagerCalls.receivedVideoFrameCount())
        if frameCount > lastReceivedVideoFrameCount {
            noNewVideoFrameTicks = 0
        }
        else {
            noNewVideoFrameTicks += 1
        }
        lastReceivedVideoFrameCount = frameCount

        let videoLive = noNewVideoFrameTicks < Constants.NoVideoFrameTicksToDetach
        if videoLive {
            if activeController!.videoFeed == nil {
                activeController!.videoFeed = submanagerCalls.videoFeed()
            }
        }
        else {
            if activeController!.videoFeed != nil {
                activeController!.videoFeed = nil
            }
        }

        // KHANDAQ (remote-video diagnostic): surface the incoming-frame count + flags on the call
        // screen during a video call so a screenshot pinpoints the break (rx:0 = peer not transmitting).
        if activeCall.call.videoIsEnabled || activeCall.call.friendSendingVideo || frameCount > 0 {
            activeController!.debugVideoInfo = "rx:\(frameCount) sendV:\(activeCall.call.friendSendingVideo ? 1 : 0) feed:\(activeController!.videoFeed != nil ? 1 : 0)"
        }
        else {
            activeController!.debugVideoInfo = ""
        }
    }
}
