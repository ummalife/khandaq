// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
    
class OCTSubmanagerUserMock: NSObject, OCTSubmanagerUser {
    /// DEMO_CONNECTING: our node starts without the network and finds it once "Connect again" asks for
    /// a reconnect, so that a walk can show the line, the Network screen and the hint while it connects.
    private static let startsConnecting = ProcessInfo.processInfo.arguments.contains("DEMO_CONNECTING")

    weak var delegate: OCTSubmanagerUserDelegate? = nil
    var connectionStatus: OCTToxConnectionStatus = OCTSubmanagerUserMock.startsConnecting ? .none : .TCP
    var userAddress: String = "123"
    var publicKey: String = "123"
    /// A new antispam gives a new ID, as in toxcore: the key stays, the antispam and the check change.
    var nospam: OCTToxNoSpam = 123 {
        didSet {
            userAddress = OCTSubmanagerUserMock.address(userAddress, withAntispam: nospam)
        }
    }
    var userStatus: OCTToxUserStatus = .none
    var capabilities: OCTToxCapabilities = 0

    /// Kept as set, so "You" shows what was saved (UI tests only).
    private var name: String?
    private var statusMessage: String?
    private var reconnectObserver: NSObjectProtocol?

    override init() {
        super.init()

        let delayTime = DispatchTime.now() + Double(Int64(2.0 * Double(NSEC_PER_SEC))) / Double(NSEC_PER_SEC)
        DispatchQueue.main.asyncAfter(deadline: delayTime) { [weak self] in
            self?.delegate?.submanagerUser(self!, connectionStatusUpdate: self!.connectionStatus)
        }

        if OCTSubmanagerUserMock.startsConnecting {
            // Only the reconnect asked for by hand: the offline watchdog keeps asking on its own.
            reconnectObserver = NotificationCenter.default.addObserver(forName: .khandaqManualReconnectRequested, object: nil, queue: .main) { [weak self] note in
                guard (note.object as? ReconnectBackoffCoordinator.Reason) == .manual, self?.connectionStatus == OCTToxConnectionStatus.none else {
                    return
                }
                // Long enough for a walk to see the toast over the screen still connecting.
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
                    guard let self = self else {
                        return
                    }
                    self.connectionStatus = .TCP
                    self.delegate?.submanagerUser(self, connectionStatusUpdate: .TCP)
                }
            }
        }
    }

    deinit {
        if let observer = reconnectObserver {
            NotificationCenter.default.removeObserver(observer)
        }
    }
    
    func setUserName(_ name: String?) throws {
        self.name = name
    }
    
    func userName() -> String? {
        return name
    }
    
    func setUserStatusMessage(_ statusMessage: String?) throws {
        self.statusMessage = statusMessage
    }
    
    func userStatusMessage() -> String? {
        return statusMessage
    }
    
    func setUserAvatar(_ avatar: Data?) throws {
        // nop
    }
    
    func userAvatar() -> Data? {
        return nil
    }

    /// `address` with `antispam` in its place and the check worked out again, as toxcore does it: the
    /// 36 bytes of the key and the antispam folded into two by XOR. Anything but a whole ID is kept.
    static func address(_ address: String, withAntispam antispam: OCTToxNoSpam) -> String {
        guard address.count == 76 else {
            return address
        }
        let head = String(address.prefix(64)) + String(format: "%08X", antispam)
        var check: [UInt8] = [0, 0]
        var index = head.startIndex
        var offset = 0
        while index < head.endIndex {
            let next = head.index(index, offsetBy: 2)
            guard let byte = UInt8(head[index..<next], radix: 16) else {
                return address
            }
            check[offset % 2] ^= byte
            offset += 1
            index = next
        }
        return head + String(format: "%02X%02X", check[0], check[1])
    }
}
