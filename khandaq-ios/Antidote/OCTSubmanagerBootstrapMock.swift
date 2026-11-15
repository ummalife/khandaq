// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class OCTSubmanagerBootstrapMock: NSObject, OCTSubmanagerBootstrap {
    /// How many urgent re-bootstraps were asked for (tests).
    private(set) var rebootstrapCount = 0

    func addNode(withIpv4Host ipv4Host: String?, ipv6Host: String?, udpPort: OCTToxPort, tcpPorts: [NSNumber], publicKey: String) {
        // nop
    }
    
    func addPredefinedNodes() {
        // nop
    }
    
    func bootstrap() {
        // nop
    }

    func rebootstrapOnNetworkChange() {
        rebootstrapCount += 1
    }

    func performKhandaqBootstrapBurst() {
        // nop
    }
}
