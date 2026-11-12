// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
    
class OCTSubmanagerCallsMock: NSObject, OCTSubmanagerCalls {
    weak var delegate: OCTSubmanagerCallDelegate? = nil
    var enableMicrophone: Bool = false
    var callKitAudioSessionIsActive: Bool = false
    
    func setup() throws {
        // nop
    }
    
    /// How long the demo call has been on when its screen opens.
    static let demoDuration: TimeInterval = 42

    /// The demo call is already on (UI tests only): its screen shows the timer and the controls, and
    /// nothing rings out of the simulator, which a call still being placed would.
    func call(to chat: OCTChat, enableAudio: Bool, enableVideo: Bool) throws -> OCTCall {
        let call = OCTCall()
        call.chat = chat
        call.status = .active
        call.callDuration = OCTSubmanagerCallsMock.demoDuration
        call.videoIsEnabled = enableVideo
        return call
    }
    
    func enableVideoSending(_ enable: Bool, for call: OCTCall) throws {
        // nop
    }
    
    func answer(_ call: OCTCall, enableAudio: Bool, enableVideo: Bool) throws {
        // nop
    }
    
    func send(_ control: OCTToxAVCallControl, to call: OCTCall) throws {
        // nop
    }
    
    func videoFeed() -> UIView? {
        return nil
    }

    func receivedVideoFrameCount() -> UInt {
        return 0
    }

    func getVideoCallPreview(_ completionBlock: @escaping (CALayer?) -> Void) {
        // nop
    }
    
    func setAudioBitrate(_ bitrate: Int32, for call: OCTCall) throws {
        // nop
    }
    
    func routeAudio(toSpeaker speaker: Bool) throws {
        // nop
    }
    
    func `switch`(toCameraFront front: Bool) throws {
        // nop
    }
}
