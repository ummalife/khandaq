// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

//
//  ProviderDelegate.swift
//  Hotline
//
//  Created by Steve Baker on 10/27/17.
//  Copyright © 2017 Razeware LLC. All rights reserved.
//

import AVFoundation
import CallKit
import os

class ProviderDelegate: NSObject {

    fileprivate let callManager: CallManager
    fileprivate let provider: CXProvider

    init(callManager: CallManager) {
        os_log("ProviderDelegate:init")
        self.callManager = callManager
        provider = CXProvider(configuration: type(of: self).providerConfiguration)

        super.init()

        provider.setDelegate(self, queue: nil)
    }

    // static var belongs to the type
    // subclasses can't override static
    static var providerConfiguration: CXProviderConfiguration {
        // initialize
        let providerConfiguration = CXProviderConfiguration(localizedName: "Khandaq")

        // set call capabilities
        providerConfiguration.supportsVideo = true
        providerConfiguration.maximumCallsPerCallGroup = 2 // Signal Messenger seems to think 2 is needed
        providerConfiguration.supportedHandleTypes = [.generic]

        return providerConfiguration
    }
    
    func endIncomingCalls() {
        
        os_log("ProviderDelegate:endIncomingCalls")
        
        for call in callManager.calls {
            os_log("ProviderDelegate:endcall")
            provider.reportCall(with: call.uuid, endedAt: Date(), reason: .remoteEnded)
            call.end()
        }

        callManager.removeAllCalls()
    }

    func reportIncomingCall(uuid: UUID, handle: String, hasVideo: Bool = false, completion: ((NSError?) -> Void)?) {

        os_log("ProviderDelegate:reportIncomingCall")

        // prepare update to send to system
        let update = CXCallUpdate()
        // add call metadata
        update.remoteHandle = CXHandle(type: .generic, value: handle)
        update.hasVideo = hasVideo

        // use provider to notify system
        provider.reportNewIncomingCall(with: uuid, update: update) { error in

            // now we are inside reportNewIncomingCall's final argument, a completion block
            if error == nil {
                // no error, so add call
                let call = Call(uuid: uuid, handle: handle, hasVideo: hasVideo)
                self.callManager.add(call: call)
            }

            // execute "completion", the final argument that was passed to outer method reportIncomingCall
            // execute if it isn't nil
            completion?(error as NSError?)
        }
    }

}

extension ProviderDelegate: CXProviderDelegate {

    func providerDidReset(_ provider: CXProvider) {
        os_log("ProviderDelegate:providerDidReset")

        // stopAudio()

        for call in callManager.calls {
            call.end()
        }

        callManager.removeAllCalls()
    }

    func provider(_ provider: CXProvider, didActivate audioSession: AVAudioSession) {
        os_log("cc:ProviderDelegate:didActivate")
        print("cc:ProviderDelegate:didActivate %@", audioSession)

        guard let runcoord = AppDelegate.shared.coordinator?.activeCoordinator as? RunningCoordinator,
              let sessionCoord = runcoord.activeSessionCoordinator else {
            os_log("ProviderDelegate:didActivate:skip:not_running")
            return
        }

        // CallKit already activated the session — tell objcTox not to reconfigure it.
        sessionCoord.toxManager.calls.callKitAudioSessionIsActive = true

        let enableVideo = callManager.calls.first?.hasVideo ?? false
        sessionCoord.callCoordinator.answerCall(enableVideo: enableVideo)
    }

    func provider(_ provider: CXProvider, perform action: CXAnswerCallAction) {

        os_log("cc:ProviderDelegate:call-answer %@", action)

        guard let call = callManager.callWithUUID(uuid: action.callUUID) else {
            action.fail()
            return
        }

        configureAudioSession(forVideo: call.hasVideo)
        os_log("cc:ProviderDelegate:call-answer:answer()")
        call.answer()
        // when processing an action, app should fulfill it or fail
        os_log("cc:ProviderDelegate:call-answer:fulfill()")
        action.fulfill()
    }

    func configureAudioSession(forVideo: Bool)
    {
        os_log("cc:ProviderDelegate:configureAudioSession:start")

        let session = AVAudioSession.sharedInstance()
        let mode = forVideo ? AVAudioSessionModeVideoChat : AVAudioSessionModeVoiceChat
        do {
            try session.setCategory(
                AVAudioSessionCategoryPlayAndRecord,
                with: [.allowBluetooth, .defaultToSpeaker]
            )
            try session.setMode(mode)
            try session.setPreferredSampleRate(48000)
            try session.setPreferredIOBufferDuration(0.005)
            // Do not call setActive here — CallKit activates the session in didActivate.
        } catch (let error) {
            os_log("cc:ProviderDelegate:configureAudioSession:EE_01")
            print("cc:ProviderDelegate:configureAudioSession:Error while configuring audio session: \(error)")
        }

        os_log("ProviderDelegate:configureAudioSession:end")
    }

    func provider(_ provider: CXProvider, perform action: CXEndCallAction) {

        os_log("ProviderDelegate:call-end %@", action)

        guard let call = callManager.callWithUUID(uuid: action.callUUID) else {
            action.fail()
            return
        }

        // also decline Tox Call -------------
        // -- HaXX0r --
        // -- HaXX0r --
        // -- HaXX0r --
        guard let runcoord = AppDelegate.shared.coordinator?.activeCoordinator as? RunningCoordinator else {
            os_log("ProviderDelegate:call-end:skip:not_running")
            action.fulfill()
            call.end()
            callManager.remove(call: call)
            return
        }
        runcoord.activeSessionCoordinator?.callCoordinator.declineCall(callWasRemoved: false)
        // -- HaXX0r --
        // -- HaXX0r --
        // -- HaXX0r --
        // also decline Tox Call -------------

        // stopAudio()
        // call.end changes the call's status, allows other classes to react to new state
        call.end()
        action.fulfill()
        callManager.remove(call: call)
    }

    func provider(_ provider: CXProvider, perform action: CXSetHeldCallAction) {

        os_log("ProviderDelegate:call-held %@", action)

        guard let call = callManager.callWithUUID(uuid: action.callUUID) else {
            action.fail()
            return
        }

        call.state = action.isOnHold ? .held : .active

        if call.state == .held {
            // stopAudio()
        } else {
            // startAudio()
        }

        action.fulfill()
    }

    func provider(_ provider: CXProvider, perform action: CXStartCallAction) {
        let call = Call(uuid: action.callUUID, outgoing: true, handle: action.handle.value, hasVideo: action.isVideo)
        configureAudioSession(forVideo: action.isVideo)
        
        os_log("cc:ProviderDelegate:call-start %s", action.handle.value)

        // set connectedStateChanged as a closure to monitor call lifecycle
        call.connectedStateChanged = { [weak self, weak call] in
            guard let strongSelf = self, let call = call else { return }

            if call.connectedState == .pending {
                strongSelf.provider.reportOutgoingCall(with: call.uuid, startedConnectingAt: nil)
            } else if call.connectedState == .complete {
                strongSelf.provider.reportOutgoingCall(with: call.uuid, connectedAt: nil)
            }
        }

        call.start { [weak self, weak call] success in
            guard let strongSelf = self, let call = call else { return }

            if success {
                action.fulfill()
                strongSelf.callManager.add(call: call)
            } else {
                action.fail()
            }
        }
    }

}
