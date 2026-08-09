// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import SnapKit

private struct Constants {
    static let PinLength = 4
}

protocol EnterPinControllerDelegate: class {
    func enterPinController(_ controller: EnterPinController, successWithPin pin: String)

    // This method may be called only for ValidatePin state.
    func enterPinControllerFailure(_ controller: EnterPinController)
}

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
            customLoadView()
            pinInputView.topText = newValue
        }
    }

    var descriptionText: String? {
        get {
            return pinInputView.descriptionText
        }
        set {
            customLoadView()
            pinInputView.descriptionText = newValue
        }
    }

    // KHANDAQ (Figma): the unlock screen shows the Khandaq wordmark above the title.
    var showLogo: Bool = false {
        didSet {
            if isViewLoaded {
                logoImageView.isHidden = !showLogo
            }
        }
    }

    // KHANDAQ (audit #11): the coordinator switches the keypad off while a brute-force lockout is
    // running and back on when it expires, so the wait cannot be skipped by tapping through.
    var inputEnabled: Bool = true

    // KHANDAQ (Figma): the set-PIN sheet (from profile) has a круглая close (X) button top-left.
    var closeHandler: (() -> Void)? {
        didSet {
            if isViewLoaded {
                closeButton.isHidden = (closeHandler == nil)
            }
        }
    }

    fileprivate let theme: Theme

    fileprivate var pinInputView: PinInputView!
    fileprivate var logoImageView: UIImageView!
    fileprivate var closeButton: UIButton!

    fileprivate var enteredString: String = ""

    init(theme: Theme, state: State) {
        self.theme = theme
        self.state = state

        super.init(nibName: nil, bundle: nil)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        createViews()
        installConstraints()

        pinInputView.applyColors()
    }

    override var shouldAutorotate : Bool {
        return false
    }

    override var supportedInterfaceOrientations : UIInterfaceOrientationMask {
        return UIInterfaceOrientationMask.portrait
    }

    func resetEnteredPin() {
        enteredString = ""
        pinInputView.enteredNumbersCount = enteredString.count
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

private extension EnterPinController {
    func createViews() {
        pinInputView = PinInputView(pinLength: Constants.PinLength, theme: theme)
        pinInputView.delegate = self
        view.addSubview(pinInputView)

        logoImageView = UIImageView(image: UIImage(named: "login-logo"))
        logoImageView.contentMode = .scaleAspectFit
        logoImageView.isHidden = !showLogo
        view.addSubview(logoImageView)

        closeButton = UIButton()
        if #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 15.0, weight: .medium)
            closeButton.setImage(UIImage(systemName: "xmark", withConfiguration: config), for: UIControlState())
        }
        else {
            closeButton.setTitle("✕", for: UIControlState())
            closeButton.setTitleColor(theme.colorForType(.NormalText), for: UIControlState())
        }
        closeButton.tintColor = theme.colorForType(.NormalText)
        closeButton.backgroundColor = theme.colorForType(.ChatInputBackground)
        closeButton.layer.cornerRadius = 20.0
        closeButton.layer.masksToBounds = true
        closeButton.isHidden = (closeHandler == nil)
        closeButton.addTarget(self, action: #selector(EnterPinController.closeButtonPressed), for: .touchUpInside)
        view.addSubview(closeButton)
    }

    @objc func closeButtonPressed() {
        closeHandler?()
    }

    func installConstraints() {
        pinInputView.snp.makeConstraints {
            $0.center.equalTo(view)
        }

        logoImageView.snp.makeConstraints {
            $0.centerX.equalTo(view)
            $0.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(48.0)
            $0.width.equalTo(190.0)
            $0.height.equalTo(76.0)
        }

        closeButton.snp.makeConstraints {
            $0.top.equalTo(view.safeAreaLayoutGuide.snp.top).offset(16.0)
            $0.left.equalTo(view).offset(16.0)
            $0.size.equalTo(40.0)
        }
    }

    func customLoadView() {
        if #available(iOS 9.0, *) {
            loadViewIfNeeded()
        } else {
            if !isViewLoaded {
                // creating view
                view.setNeedsDisplay()
            }
        }
    }
}
