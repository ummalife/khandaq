// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// KHANDAQ (0.2.65, tester feedback "leave a chat with a swipe"): the numbers of the flick that leaves a
/// conversation from anywhere on its messages, as in Telegram, kept apart from UIKit so they can be tested.
/// The flick goes toward the end edge (right, or left in a right-to-left layout); the other way a message is
/// answered (ChatReplySwipeGeometry). A release that went far across goes back unless the finger was on its
/// way back, and so does a quick one; a slow short drag does not, so a finger that only wandered sideways
/// leaves the conversation where it is.
enum ChatFlickBackGeometry {
    /// How far a quick flick has to travel, in points.
    static let minimumFlick: CGFloat = 60.0
    /// How fast a quick flick has to be moving when the finger is lifted, in points per second. A finger
    /// slows down before it lets go: the quick drag of a UI test (XCUITest's "fast") peaked at 860 and ended
    /// at 405, its slow one ended at 164.
    static let minimumVelocity: CGFloat = 350.0
    /// Without the speed, a drag across this share of the width goes back as well.
    static let farShare: CGFloat = 0.5
    /// How much more sideways than up or down the movement has to be: scrolling the messages is not leaving.
    static let sidewaysRatio: CGFloat = 1.5

    /// Whether a release with this movement and speed leaves the conversation.
    static func goesBack(translation: CGPoint, velocity: CGPoint, width: CGFloat, isRightToLeft: Bool) -> Bool {
        let toward: CGFloat = isRightToLeft ? -1 : 1
        let along = translation.x * toward
        let speed = velocity.x * toward
        guard along > 0, along > abs(translation.y) * sidewaysRatio else {
            return false
        }
        if width > 0, along >= width * farShare, speed > -minimumVelocity {
            return true
        }
        return along >= minimumFlick && speed >= minimumVelocity
    }
}

/// Leaves a conversation on the flick of ChatFlickBackGeometry, from anywhere on its messages. The system's
/// swipe from the edge keeps working and goes first. Where the system goes back on a swipe from anywhere in
/// the content itself (iOS 26 and later, in their design), its transition is under way by the time the finger
/// is lifted and this one does nothing; it acts only when no transition is, so it also covers a system swipe
/// that something in the conversation kept from starting. The screen does not follow the finger: it goes
/// back with the usual animation once the finger is lifted. Views that take sideways drags themselves keep
/// them: the wave of a voice note (replySwipeExcludedViews), sliders, switches, anything that scrolls
/// sideways and text with a selection, whose handles are dragged. Kept alive by its conversation, which
/// owns it.
final class ChatFlickBack: NSObject, UIGestureRecognizerDelegate {
    private weak var controller: UIViewController?
    private let pan = UIPanGestureRecognizer()

    /// - parameter view: the view of the messages the flick starts on (the table of the conversation).
    init(controller: UIViewController, view: UIView) {
        self.controller = controller
        super.init()

        pan.addTarget(self, action: #selector(panned(_:)))
        pan.delegate = self
        pan.cancelsTouchesInView = false
        pan.delaysTouchesEnded = false
        pan.maximumNumberOfTouches = 1
        view.addGestureRecognizer(pan)
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        var current = touch.view
        while let view = current, view !== gestureRecognizer.view {
            if view is UISlider || view is UISwitch || view is ChatVoiceMessageView {
                return false
            }
            if let text = view as? UITextView, text.selectedRange.length > 0 {
                return false
            }
            if let scroll = view as? UIScrollView, scroll.isScrollEnabled, scroll.contentSize.width > scroll.bounds.width + 1 {
                return false
            }
            if let cell = view as? ChatMovableDateCell, let touched = touch.view,
               cell.replySwipeExcludedViews.contains(where: { touched.isDescendant(of: $0) }) {
                return false
            }
            current = view.superview
        }
        return true
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let controller = controller,
              let navigationController = controller.navigationController,
              navigationController.topViewController === controller,
              navigationController.viewControllers.count > 1,
              navigationController.transitionCoordinator == nil,
              (gestureRecognizer.view as? UITableView)?.isEditing != true else {
            return false
        }
        return true
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRequireFailureOf otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // A swipe from the edge is the system's, which follows the finger.
        return otherGestureRecognizer === controller?.navigationController?.interactivePopGestureRecognizer
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                           shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        // Scrolling, the swipe to reply and the drag that shows the time go on as before; this one only
        // decides once the finger is lifted.
        return true
    }

    @objc private func panned(_ recognizer: UIPanGestureRecognizer) {
        guard recognizer.state == .ended,
              let controller = controller,
              let navigationController = controller.navigationController,
              navigationController.topViewController === controller,
              navigationController.transitionCoordinator == nil else {
            return
        }
        // Measured in the screen's own view: the table of a group is drawn upside down.
        let space: UIView = controller.view
        let isRightToLeft = space.effectiveUserInterfaceLayoutDirection == .rightToLeft
        guard ChatFlickBackGeometry.goesBack(translation: recognizer.translation(in: space),
                                             velocity: recognizer.velocity(in: space),
                                             width: space.bounds.width,
                                             isRightToLeft: isRightToLeft) else {
            return
        }
        navigationController.popViewController(animated: true)
    }
}
