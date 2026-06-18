// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let offset: CGFloat = 20
    static let fieldHeight: CGFloat = 44
    static let spacing: CGFloat = 16
}

/// Full-screen name entry instead of UIAlertController — UIAlert text fields freeze
/// when main-thread group sync runs under the Russian (and other) IME keyboards.
final class GroupCreateNameController: UIViewController {
    private let theme: Theme
    private let isPrivate: Bool
    private let onCreate: (String, String?) -> Void

    var onCancel: (() -> Void)?

    private var nameField: UITextField!
    private var passwordField: UITextField?

    init(theme: Theme, isPrivate: Bool, onCreate: @escaping (String, String?) -> Void) {
        self.theme = theme
        self.isPrivate = isPrivate
        self.onCreate = onCreate
        super.init(nibName: nil, bundle: nil)
        title = isPrivate
            ? String(localized: "group_create_private")
            : String(localized: "group_create_public")
        edgesForExtendedLayout = UIRectEdge()
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))
        configureNavigationItems()
        buildFields()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        nameField.becomeFirstResponder()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)

        if isBeingDismissed || navigationController?.isBeingDismissed == true {
            onCancel?()
        }
    }
}

private extension GroupCreateNameController {
    func configureNavigationItems() {
        navigationItem.leftBarButtonItem = UIBarButtonItem(
            title: String(localized: "alert_cancel"),
            style: .plain,
            target: self,
            action: #selector(cancelTapped))

        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: String(localized: "alert_create"),
            style: .done,
            target: self,
            action: #selector(createTapped))
    }

    func buildFields() {
        let hint = UILabel()
        hint.font = UIFont.preferredFont(forTextStyle: .footnote)
        hint.textColor = theme.colorForType(.NormalText)
        hint.numberOfLines = 0
        hint.text = isPrivate ? String(localized: "group_create_private_hint") : nil
        hint.isHidden = !isPrivate

        nameField = makeField(
            placeholder: String(localized: "group_name_placeholder"),
            secure: false)
        nameField.returnKeyType = isPrivate ? .next : .done
        nameField.delegate = self

        view.addSubview(hint)
        view.addSubview(nameField)

        hint.translatesAutoresizingMaskIntoConstraints = false
        nameField.translatesAutoresizingMaskIntoConstraints = false

        var lastAnchor = nameField.bottomAnchor

        NSLayoutConstraint.activate([
            hint.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: Constants.offset),
            hint.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: Constants.offset),
            hint.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -Constants.offset),

            nameField.topAnchor.constraint(equalTo: isPrivate ? hint.bottomAnchor : view.safeAreaLayoutGuide.topAnchor,
                                           constant: isPrivate ? Constants.spacing : Constants.offset),
            nameField.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: Constants.offset),
            nameField.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -Constants.offset),
            nameField.heightAnchor.constraint(equalToConstant: Constants.fieldHeight),
        ])

        if isPrivate {
            let password = makeField(
                placeholder: String(localized: "group_password_placeholder"),
                secure: true)
            password.returnKeyType = .done
            password.delegate = self
            passwordField = password
            view.addSubview(password)
            password.translatesAutoresizingMaskIntoConstraints = false

            NSLayoutConstraint.activate([
                password.topAnchor.constraint(equalTo: nameField.bottomAnchor, constant: Constants.spacing),
                password.leadingAnchor.constraint(equalTo: nameField.leadingAnchor),
                password.trailingAnchor.constraint(equalTo: nameField.trailingAnchor),
                password.heightAnchor.constraint(equalToConstant: Constants.fieldHeight),
            ])
            lastAnchor = password.bottomAnchor
        }

        _ = lastAnchor
    }

    func makeField(placeholder: String, secure: Bool) -> UITextField {
        let field = UITextField()
        field.placeholder = placeholder
        field.borderStyle = .roundedRect
        field.autocorrectionType = .no
        field.autocapitalizationType = .sentences
        field.spellCheckingType = .no
        field.isSecureTextEntry = secure
        if secure {
            field.textContentType = .password
        }
        return field
    }

    @objc func cancelTapped() {
        dismiss(animated: true, completion: nil)
    }

    @objc func createTapped() {
        submit()
    }

    func submit() {
        let name = nameField.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        guard !name.isEmpty else {
            return
        }

        let passwordRaw = passwordField?.text?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let password = isPrivate && !passwordRaw.isEmpty ? passwordRaw : nil

        view.endEditing(true)
        onCancel = nil
        dismiss(animated: true) { [onCreate] in
            onCreate(name, password)
        }
    }
}

extension GroupCreateNameController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        if textField === nameField, isPrivate {
            passwordField?.becomeFirstResponder()
            return true
        }

        submit()
        return true
    }
}
