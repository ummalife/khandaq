// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

//
//  ResultsExtension.swift
//  Antidote
//
//  Created by Dmytro Vorobiov on 26/04/2017.
//  Copyright © 2017 dvor. All rights reserved.
//

import Foundation

extension Results where T : OCTMessageAbstract {
    func undeliveredMessages() -> Results<T> {
        let undeliveredPredicate = NSPredicate(format: "messageText != nil AND messageText.isDelivered == NO AND senderUniqueIdentifier == nil")
        return self.objects(with: undeliveredPredicate)
    }
}

