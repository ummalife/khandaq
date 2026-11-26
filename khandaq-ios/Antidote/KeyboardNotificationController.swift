// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

class KeyboardNotificationController: UIViewController {
    /// Skip keyboard-matched animation for the next layout pass (e.g. first-responder on appear).
    var suppressNextKeyboardAnimation = false

    /// KHANDAQ (beta 8251): whether the keyboard is up, as the last keyboard notification said.
    private(set) var isKeyboardShown = false

    /// What waits for the keyboard to be down (putKeyboardAway(then:)).
    private var keyboardDownActions: [() -> Void] = []

    init() {
        super.init(nibName: nil, bundle: nil)

        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(KeyboardNotificationController.keyboardWillShowNotification(_:)), name: NSNotification.Name.UIKeyboardWillShow, object: nil)
        center.addObserver(self, selector: #selector(KeyboardNotificationController.keyboardWillHideNotification(_:)), name: NSNotification.Name.UIKeyboardWillHide, object: nil)
        center.addObserver(self, selector: #selector(KeyboardNotificationController.keyboardDidHideNotification(_:)), name: NSNotification.Name.UIKeyboardDidHide, object: nil)
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

    /// KHANDAQ (beta 8251): puts the keyboard away and runs `action` once it is down; at once when it is not
    /// up. What `action` lays out over the screen, such as the long-press menu of a message, then has the
    /// screen the keyboard leaves instead of standing under the keyboard. A keyboard that comes up again
    /// first drops the action; one whose hiding is never reported runs it after a second all the same.
    func putKeyboardAway(then action: @escaping () -> Void) {
        guard isKeyboardShown else {
            action()
            return
        }
        keyboardDownActions.append(action)
        view.window?.endEditing(true)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.0) { [weak self] in
            self?.runKeyboardDownActions()
        }
    }

    @objc func keyboardWillShowNotification(_ notification: Notification) {
        handleNotification(notification, willShow: true)
    }

    @objc func keyboardWillHideNotification(_ notification: Notification) {
        handleNotification(notification, willShow: false)
    }

    @objc func keyboardDidHideNotification(_ notification: Notification) {
        runKeyboardDownActions()
    }
}

private extension KeyboardNotificationController {
    func runKeyboardDownActions() {
        let actions = keyboardDownActions
        keyboardDownActions.removeAll()
        actions.forEach { $0() }
    }

    func handleNotification(_ notification: Notification, willShow: Bool) {
        isKeyboardShown = willShow
        if willShow {
            keyboardDownActions.removeAll()
        }

        // KHANDAQ (audit): keyboard userInfo keys are not contractually guaranteed to be present/typed;
        // the old forced casts + rawValue! would crash on any surprise payload. Read defensively.
        let userInfo = notification.userInfo ?? [:]

        let frame = (userInfo[UIKeyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue ?? .zero
        let duration = (userInfo[UIKeyboardAnimationDurationUserInfoKey] as? NSNumber)?.doubleValue ?? 0.25
        let curveRaw = (userInfo[UIKeyboardAnimationCurveUserInfoKey] as? NSNumber)?.intValue ?? 0
        let curve = UIViewAnimationCurve(rawValue: curveRaw) ?? .easeInOut

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
