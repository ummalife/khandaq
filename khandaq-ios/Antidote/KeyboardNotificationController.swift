// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class KeyboardNotificationController: UIViewController {
    /// Skip keyboard-matched animation for the next layout pass (e.g. first-responder on appear).
    var suppressNextKeyboardAnimation = false

    init() {
        super.init(nibName: nil, bundle: nil)

        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(KeyboardNotificationController.keyboardWillShowNotification(_:)), name: NSNotification.Name.UIKeyboardWillShow, object: nil)
        center.addObserver(self, selector: #selector(KeyboardNotificationController.keyboardWillHideNotification(_:)), name: NSNotification.Name.UIKeyboardWillHide, object: nil)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        let center = NotificationCenter.default
        center.removeObserver(self)
    }

    func keyboardWillShowAnimated(keyboardFrame frame: CGRect) {
        // nop
    }

    func keyboardWillHideAnimated(keyboardFrame frame: CGRect) {
        // nop
    }

    @objc func keyboardWillShowNotification(_ notification: Notification) {
        handleNotification(notification, willShow: true)
    }

    @objc func keyboardWillHideNotification(_ notification: Notification) {
        handleNotification(notification, willShow: false)
    }
}

private extension KeyboardNotificationController {
    func handleNotification(_ notification: Notification, willShow: Bool) {
        let userInfo = notification.userInfo!

        let frame = (userInfo[UIKeyboardFrameEndUserInfoKey] as! NSValue).cgRectValue
        let duration = (userInfo[UIKeyboardAnimationDurationUserInfoKey] as! NSNumber).doubleValue
        let curve = UIViewAnimationCurve(rawValue: (userInfo[UIKeyboardAnimationCurveUserInfoKey] as! NSNumber).intValue)!

        let options: UIViewAnimationOptions

        switch curve {
            case .easeInOut:
                options = UIViewAnimationOptions()
            case .easeIn:
                options = .curveEaseIn
            case .easeOut:
                options = .curveEaseOut
            case .linear:
                options = .curveLinear
           default:
            options = .curveLinear
            
        }

        if willShow {
            keyboardWillShowAnimated(keyboardFrame: frame)
        } else {
            keyboardWillHideAnimated(keyboardFrame: frame)
        }

        if suppressNextKeyboardAnimation {
            suppressNextKeyboardAnimation = false
            view.layoutIfNeeded()
            return
        }

        UIView.animate(withDuration: duration, delay: 0.0, options: options, animations: { [weak self] in
            self?.view.layoutIfNeeded()
        }, completion: nil)
    }
}
