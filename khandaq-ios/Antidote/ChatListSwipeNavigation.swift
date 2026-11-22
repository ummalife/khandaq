// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

/**
 KHANDAQ (owner request 17.08): which filter a horizontal swipe on the Air list should land on.

 Pulled out of the gesture handler so it can be tested without a running app: the parts worth being
 sure about are the order, the two ends, and Arabic. In an RTL layout the pills are mirrored on screen,
 so a swipe to the left has to walk the list the other way: get that backwards and the gesture fights
 what the user sees.
 */
enum ChatListSwipeNavigation {
    /// The pills left to right, everything first, in a left-to-right layout.
    static let order: [AirFilter] = AirFilter.swipeOrder

    /**
     - returns: the filter to switch to, or nil at either end: deliberately no wrap-around, so the
                first and the last filter feel like edges instead of teleporting across the strip.
     */
    static func nextFilter(from current: AirFilter,
                           swipingLeft: Bool,
                           isRightToLeft: Bool) -> AirFilter? {
        guard let index = order.firstIndex(of: current) else {
            return nil
        }

        // Swiping left moves FORWARD through the visual order, unless the layout is mirrored.
        let forward = swipingLeft != isRightToLeft
        let next = forward ? index + 1 : index - 1

        guard next >= 0, next < order.count else {
            return nil
        }
        return order[next]
    }

    /**
     KHANDAQ (tester 8250): whether a sideways swipe belongs to a row of the list instead of the filters.
     Swiping a row to delete it or add it to Favourites also switched to the next filter. A row's actions
     come out from its trailing edge, so a swipe toward the leading edge that starts on a row with actions
     opens them, and a swipe made while a row shows its actions closes them: neither switches the filter.
     Every other swipe still does (back across the rows, both ways on the pills and on empty space).
     */
    static func swipeBelongsToRow(swipingLeft: Bool,
                                  isRightToLeft: Bool,
                                  startedOnRowWithActions: Bool,
                                  rowActionsOpen: Bool) -> Bool {
        if rowActionsOpen {
            return true
        }
        guard startedOnRowWithActions else {
            return false
        }
        // Toward the leading edge: left in a left-to-right layout, right in a mirrored one.
        return swipingLeft != isRightToLeft
    }
}
