// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

private struct Constants {
    static let PinLength = 4
}

protocol EnterPinControllerDelegate: class {
    func enterPinController(_ controller: EnterPinController, successWithPin pin: String)

    // This method may be called only for ValidatePin state.
    func enterPinControllerFailure(_ controller: EnterPinController)
}

/// A PIN screen (redesign stage 4, E20): the lock that opens the app, on the lock ground of the call
/// screens, or the sheet that sets the PIN and confirms it. What the PIN does, how it is checked and
/// Face ID or Touch ID before it stay as they were; only the screens changed.
class EnterPinController: UIViewController {
    enum State {
        case validatePin(validPin: String)
        case setPin
    }

    weak var delegate: EnterPinControllerDelegate?

    let state: State

    var topText: String {
        get {
            return pinInputView.topText
        }
        set {
            loadViewIfNeeded()
            pinInputView.topText = newValue
        }
    }

    var descriptionText: String? {
        get {
            return pinInputView.descriptionText
        }
        set {
            loadViewIfNeeded()
            pinInputView.descriptionText = newValue
        }
    }

    /// The wordmark above the title, on the lock.
    var showLogo: Bool = false {
        didSet {
            if isViewLoaded {
                logoView.isHidden = !showLogo
            }
        }
    }

    // KHANDAQ (audit #11): the coordinator switches the keypad off while a brute-force lockout is
    // running and back on when it expires, so the wait cannot be skipped by tapping through.
    var inputEnabled: Bool = true {
        didSet {
            if isViewLoaded {
                pinInputView.keysEnabled = inputEnabled
            }
        }
    }

    /// The close button of the set-PIN sheet; the lock has none.
    var closeHandler: (() -> Void)? {
        didSet {
            if isViewLoaded {
                closeButton.isHidden = (closeHandler == nil)
            }
        }
    }

    private let isLock: Bool
    private var pinInputView: PinInputView!
    private let logoView = UIImageView()
    private let closeButton = AirIconButton(icon: "x", label: String(localized: "alert_cancel"))
    private let scroll = UIScrollView()

    private var enteredString: String = ""

    /// `lock`: the screen that opens the app, dark in both themes. Without it, a sheet in the app.
    init(theme: Theme, state: State, lock: Bool = false) {
        self.state = state
        self.isLock = lock

        super.init(nibName: nil, bundle: nil)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        if isLock {
            view = AirCallBackdropView()
        }
        else {
            view = UIView()
            view.backgroundColor = KQColor.bg
        }
        view.accessibilityIdentifier = isLock ? "air.pin.lock" : "air.pin.sheet"

        pinInputView = PinInputView(pinLength: Constants.PinLength, ground: isLock ? .lock : .sheet)
        pinInputView.delegate = self
        pinInputView.keysEnabled = inputEnabled

        // The wordmark in its dark form (white letters): the lock ground is dark in both themes. An image
        // view picks the variant by its own traits, so the traits are what is set.
        logoView.overrideUserInterfaceStyle = .dark
        logoView.image = UIImage(named: "login-logo")
        logoView.contentMode = .scaleAspectFit
        logoView.isHidden = !showLogo
        logoView.isAccessibilityElement = false

        let column = AirKit.column([logoView, pinInputView], spacing: KQSpace.x32)
        column.alignment = .center
        let content = UIView()
        content.addSubview(column)
        scroll.addSubview(content)
        scroll.alwaysBounceVertical = false
        view.addSubview(scroll)

        closeButton.restingTint = KQColor.ink
        closeButton.accessibilityIdentifier = "air.pin.close"
        closeButton.isHidden = (closeHandler == nil)
        closeButton.addTarget(self, action: #selector(closeTapped), for: .touchUpInside)
        view.addSubview(closeButton)

        scroll.snp.makeConstraints {
            $0.edges.equalTo(view.safeAreaLayoutGuide)
        }
        content.snp.makeConstraints {
            $0.edges.equalTo(scroll.contentLayoutGuide)
            $0.width.equalTo(scroll.frameLayoutGuide)
            $0.height.greaterThanOrEqualTo(scroll.frameLayoutGuide)
        }
        // In the middle while it fits; scrolls at the largest text sizes.
        column.snp.makeConstraints {
            $0.centerX.equalToSuperview()
            $0.centerY.equalToSuperview().priority(.medium)
            $0.top.greaterThanOrEqualToSuperview().offset(KQSpace.x48)
            $0.bottom.lessThanOrEqualToSuperview().offset(-KQSpace.x24)
            $0.leading.greaterThanOrEqualToSuperview().offset(KQSpace.x16)
            $0.trailing.lessThanOrEqualToSuperview().offset(-KQSpace.x16)
        }
        logoView.snp.makeConstraints {
            $0.height.equalTo(KQSpace.x40)
            if let size = logoView.image?.size, size.height > 0 {
                $0.width.equalTo(logoView.snp.height).multipliedBy(size.width / size.height)
            }
        }
        closeButton.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x8)
            $0.leading.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x8)
        }
    }

    override var preferredStatusBarStyle: UIStatusBarStyle {
        return isLock ? .lightContent : .default
    }

    override var shouldAutorotate: Bool {
        return false
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        return UIInterfaceOrientationMask.portrait
    }

    func resetEnteredPin() {
        enteredString = ""
        pinInputView.enteredNumbersCount = enteredString.count
    }

    @objc private func closeTapped() {
        closeHandler?()
    }
}

extension EnterPinController: PinInputViewDelegate {
    func pinInputView(_ view: PinInputView, numericButtonPressed i: Int) {
        guard inputEnabled else {
            return
        }

        guard enteredString.count < Constants.PinLength else {
            return
        }

        enteredString += "\(i)"
        pinInputView.enteredNumbersCount = enteredString.count

        if enteredString.count == Constants.PinLength {
            switch state {
                case .validatePin(let validPin):
                    if enteredString == validPin {
                        delegate?.enterPinController(self, successWithPin: enteredString)
                    }
                    else {
                        pinInputView.shake()
                        delegate?.enterPinControllerFailure(self)
                    }
                case .setPin:
                    delegate?.enterPinController(self, successWithPin: enteredString)
            }
        }
    }

    func pinInputViewDeleteButtonPressed(_ view: PinInputView) {
        guard enteredString.count > 0 else {
            return
        }

        enteredString = String(enteredString.dropLast())
        view.enteredNumbersCount = enteredString.count
    }
}
