// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/**
 KHANDAQ (owner request 17.08): which tab a horizontal swipe on the chat list should land on.

 Pulled out of the gesture handler so it can be tested without a running app: the parts worth being
 sure about are the order, the two ends, and Arabic. In an RTL layout the tabs are mirrored on screen,
 so a swipe to the left has to walk the list the other way — get that backwards and the gesture fights
 what the user sees.
 */
enum ChatListSwipeNavigation {
    /// Visual order of the tabs, left to right, in a left-to-right layout.
    static let order: [ChatListFilterTab] = [.direct, .groups, .favorites]

    /**
     - returns: the tab to switch to, or nil at either end — deliberately no wrap-around, so the
                first and last tab feel like edges instead of teleporting across the bar.
     */
    static func nextTab(from current: ChatListFilterTab,
                        swipingLeft: Bool,
                        isRightToLeft: Bool) -> ChatListFilterTab? {
        guard let index = order.firstIndex(of: current) else {
            return nil
        }

        // Swiping left moves FORWARD through the visual order — unless the layout is mirrored.
        let forward = swipingLeft != isRightToLeft
        let next = forward ? index + 1 : index - 1

        guard next >= 0, next < order.count else {
            return nil
        }
        return order[next]
    }
}
