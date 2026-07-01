// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import SnapKit

private struct PrivateConstants {
    static let FieldsOffset = 20.0
    static let VerticalOffset = 30.0
}

class LoginGenericCreateController: LoginBaseController {
    fileprivate var containerView: IncompressibleView!
    fileprivate var containerViewTopConstraint: Constraint!

    var logoImageView: UIImageView!
    var titleLabel: UILabel!
    var firstTextField: ExtendedTextField!
    var secondTextField: ExtendedTextField!
    var bottomButton: RoundedButton!

    override func loadView() {
        super.loadView()

        createGestureRecognizers()
        createContainerView()
        createLogoImageView()
        createTitleLabel()
        createExtendedTextFields()
        createGoButton()

        configureViews()
        installConstraints()
    }

    override func keyboardWillShowAnimated(keyboardFrame frame: CGRect) {
        if containerView.frame.isEmpty {
            return
        }
        let keyboardFrameInView = view.convert(frame, from: nil)
        let underFormHeight = containerView.frame.size.height - secondTextField.frame.maxY

        let offset = min(0.0, underFormHeight - keyboardFrameInView.height)

        containerViewTopConstraint.update(offset: offset)
    }

    override func keyboardWillHideAnimated(keyboardFrame frame: CGRect) {
        containerViewTopConstraint.update(offset: 0.0)
    }

    func configureViews() {
        fatalError("override in subclass")
    }
}

extension LoginGenericCreateController {
    @objc func tapOnView() {
        view.endEditing(true)
    }

    @objc func bottomButtonPressed() {
        fatalError("override in subclass")
    }
}

extension LoginGenericCreateController: ExtendedTextFieldDelegate {
    func loginExtendedTextFieldReturnKeyPressed(_ field: ExtendedTextField) {
        if field == firstTextField {
            _ = secondTextField.becomeFirstResponder()
        }
        else if field == secondTextField {
            view.endEditing(true)
            bottomButtonPressed()
        }
    }
}

private extension LoginGenericCreateController {
    func createGestureRecognizers() {
        let tapGR = UITapGestureRecognizer(target: self, action: #selector(LoginCreateAccountController.tapOnView))
        // Let touches reach the text fields (the dismiss-keyboard tap must not swallow/cancel
        // the touch that focuses a field — synthetic taps lose that race and can never focus).
        tapGR.cancelsTouchesInView = false
        view.addGestureRecognizer(tapGR)
    }

    func createContainerView() {
        containerView = IncompressibleView()
        containerView.backgroundColor = .clear
        view.addSubview(containerView)
    }

    func createLogoImageView() {
        // KHANDAQ design (Figma): brand logo centered above the screen title.
        logoImageView = UIImageView(image: UIImage(named: "login-logo"))
        logoImageView.contentMode = .scaleAspectFit
        containerView.addSubview(logoImageView)
    }

    func createTitleLabel() {
        titleLabel = UILabel()
        // KHANDAQ design (Figma): screen title is primary-colour, bold and left-aligned (was a centered
        // light green heading).
        titleLabel.textColor = theme.colorForType(.NormalText)
        titleLabel.font = UIFont.systemFont(ofSize: 28.0, weight: .bold)
        titleLabel.textAlignment = .left
        titleLabel.backgroundColor = .clear
        containerView.addSubview(titleLabel)
    }

    func createExtendedTextFields() {
        firstTextField = ExtendedTextField(theme: theme, type: .login)
        firstTextField.delegate = self
        firstTextField.returnKeyType = .next
        containerView.addSubview(firstTextField)

        secondTextField = ExtendedTextField(theme: theme, type: .login)
        secondTextField.delegate = self
        secondTextField.returnKeyType = .go
        containerView.addSubview(secondTextField)
    }

    func createGoButton() {
        bottomButton = RoundedButton(theme: theme, type: .login)
        bottomButton.addTarget(self, action: #selector(LoginCreateAccountController.bottomButtonPressed), for: .touchUpInside)
        containerView.addSubview(bottomButton)
    }

    func installConstraints() {
        containerView.customIntrinsicContentSize.width = CGFloat(Constants.MaxFormWidth)
        containerView.snp.makeConstraints {
            containerViewTopConstraint = $0.top.equalTo(view).constraint
            $0.centerX.equalTo(view)
            $0.width.lessThanOrEqualTo(Constants.MaxFormWidth)
            $0.width.lessThanOrEqualTo(view).offset(-2 * Constants.HorizontalOffset)
            $0.height.equalTo(view)
        }

        logoImageView.snp.makeConstraints {
            $0.top.equalTo(containerView).offset(PrivateConstants.VerticalOffset)
            $0.centerX.equalTo(containerView)
            $0.height.equalTo(56.0)
        }

        titleLabel.snp.makeConstraints {
            $0.top.equalTo(logoImageView.snp.bottom).offset(PrivateConstants.VerticalOffset)
            $0.leading.trailing.equalTo(containerView)
        }

        firstTextField.snp.makeConstraints {
            $0.top.equalTo(titleLabel.snp.bottom).offset(PrivateConstants.FieldsOffset)
            $0.leading.equalTo(containerView)
            $0.trailing.equalTo(containerView)
        }

        secondTextField.snp.makeConstraints {
            $0.leading.trailing.equalTo(firstTextField)

            if firstTextField.isHidden {
                $0.top.equalTo(titleLabel.snp.bottom).offset(PrivateConstants.FieldsOffset)
            }
            else {
                $0.top.equalTo(firstTextField.snp.bottom).offset(PrivateConstants.FieldsOffset)
            }
        }

        bottomButton.snp.makeConstraints {
            $0.top.equalTo(secondTextField.snp.bottom).offset(PrivateConstants.VerticalOffset)
            $0.leading.trailing.equalTo(firstTextField)
        }
    }
}
