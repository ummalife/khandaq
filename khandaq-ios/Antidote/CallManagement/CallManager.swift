// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

/**
 * Copyright (c) 2017 Razeware LLC
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

import Foundation
import CallKit

class CallManager {

    var callsChangedHandler: (() -> Void)?

    private(set) var calls = [Call]()

    private let callController = CXCallController()

    func callWithUUID(uuid: UUID) -> Call? {
        guard let index = calls.index(where: { $0.uuid == uuid }) else {
            return nil
        }
        return calls[index]
    }

    func add(call: Call) {
        calls.append(call)
        call.stateChanged = { [weak self] in
            guard let strongSelf = self else { return }
            strongSelf.callsChangedHandler?()
        }
        callsChangedHandler?()
    }

    func startCall(handle: String, videoEnabled: Bool) {
        let handle = CXHandle(type: .phoneNumber, value: handle)
        // generate a new UUID, use it to instantiate startCallAction
        let startCallAction = CXStartCallAction(call: UUID(), handle: handle)

        startCallAction.isVideo = videoEnabled
        let transaction = CXTransaction(action: startCallAction)

        requestTransaction(transaction)
    }

    func end(call: Call) {
        let endCallAction = CXEndCallAction(call: call.uuid)
        // wrap action in a transaction
        let transaction = CXTransaction(action: endCallAction)
        // send transaction to system
        requestTransaction(transaction)
    }

    func setHeld(call: Call, onHold: Bool) {
        let setHeldCallAction = CXSetHeldCallAction(call: call.uuid, onHold: onHold)
        let transaction = CXTransaction()
        transaction.addAction(setHeldCallAction)

        requestTransaction(transaction)
    }

    private func requestTransaction(_ transaction: CXTransaction) {
        callController.request(transaction) { error in
            if let error = error {
                print("Error requesting transaction: \(error)")
            } else {
                print("Requested transaction successfully")
            }
        }
    }

    func remove(call: Call) {
        guard let index = calls.index(where: { $0 === call }) else { return }
        calls.remove(at: index)
        callsChangedHandler?()
    }

    func removeAllCalls() {
        calls.removeAll()
        callsChangedHandler?()
    }
}
