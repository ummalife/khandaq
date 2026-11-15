// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CoreGraphics

/// KHANDAQ (0.2.56, owner decision 3): the numbers of the swipe that answers a message, as in Telegram,
/// kept apart from UIKit so they can be tested. They are those of ReplySwipeCallback on Android: the row
/// follows the finger toward the start edge only (left, or right in a right-to-left layout) and never
/// further than `maxOffset`; a release past `triggerOffset` answers the message; the reply badge grows
/// with the offset until the reply point. ChatReplySwipe (ChatMovableDateCell.swift) moves the row.
enum ChatReplySwipeGeometry {
    /// The furthest the row goes, in points (72 dp on Android).
    static let maxOffset: CGFloat = 72.0
    /// The reply point: past it the finger feels one tick and a release answers (56 dp on Android).
    static let triggerOffset: CGFloat = 56.0
    /// The badge at its full size (a 16 dp radius on Android) and the arrow in it (20 dp).
    static let badgeDiameter: CGFloat = 32.0
    static let iconSize: CGFloat = 20.0

    /// Whether a drag that has just begun is this swipe: since the finger came down it has moved more
    /// sideways than up or down, and toward the start edge. A drag the other way is left to the table and
    /// the navigation.
    static func beginsSwipe(translation: CGPoint, isRightToLeft: Bool) -> Bool {
        guard abs(translation.x) > abs(translation.y) else {
            return false
        }
        return isRightToLeft ? translation.x > 0 : translation.x < 0
    }

    /// Where the row is for a sideways movement of the finger: toward the start edge only (negative is
    /// left) and at most `maxOffset` away.
    static func offset(forTranslation translation: CGFloat,
                       isRightToLeft: Bool,
                       maxOffset: CGFloat = ChatReplySwipeGeometry.maxOffset) -> CGFloat {
        if isRightToLeft {
            return max(0, min(translation, maxOffset))
        }
        return min(0, max(translation, -maxOffset))
    }

    /// Whether a release at this offset answers the message.
    static func isPastTrigger(_ offset: CGFloat, triggerOffset: CGFloat = ChatReplySwipeGeometry.triggerOffset) -> Bool {
        return abs(offset) >= triggerOffset
    }

    /// How grown the badge is: from 0 at rest to 1 at the reply point and beyond it.
    static func badgeProgress(forOffset offset: CGFloat,
                              triggerOffset: CGFloat = ChatReplySwipeGeometry.triggerOffset) -> CGFloat {
        guard triggerOffset > 0 else {
            return 1
        }
        return min(1, abs(offset) / triggerOffset)
    }

    /// The middle of the badge across the row: halfway into the strip the row leaves at its end edge
    /// (the right one, or the left one in a right-to-left layout).
    static func badgeCenterX(rowWidth: CGFloat, offset: CGFloat, isRightToLeft: Bool) -> CGFloat {
        return (isRightToLeft ? 0 : rowWidth) + offset / 2
    }
}
