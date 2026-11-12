// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

protocol ChangePasswordControllerDelegate: class {
    func changePasswordControllerDidFinishPresenting(_ controller: ChangePasswordController)
}

/// The password of the profile open now (redesign stage 4, E20). A profile with a password changes it:
/// the old one, then the new one twice. A profile made without one opens with a key kept in this
/// iPhone's keychain; here it gets a password, and from then on its file opens with that password on
/// another device too. Refusals stand under the fields, never in an alert.
class ChangePasswordController: AirStepController {
    weak var delegate: ChangePasswordControllerDelegate?

    private weak var toxManager: OCTManager!
    private let profilePath: String?
    /// The key the profile opens with while it has no password.
    private let deviceKey: String?

    private let oldField = AirLineField(placeholder: String(localized: "air_password_old_placeholder"), secret: true)
    private let newField = AirLineField(placeholder: String(localized: "air_password_new_placeholder"), secret: true)
    private let repeatField = AirLineField(placeholder: String(localized: "air_first_repeat_placeholder"), secret: true)
    private let note = AirFieldNote(hint: String(localized: "air_first_password_rule"))
    private var fields: UIStackView!

    private var setsPassword: Bool {
        return deviceKey != nil
    }

    init(theme: Theme, toxManager: OCTManager) {
        self.toxManager = toxManager
        let path = ProfileDeviceKey.activeProfilePath
        profilePath = path
        if let path = path, ProfileDeviceKey.hasKey(profilePath: path) {
            deviceKey = ProfileDeviceKey.key(profilePath: path)
        }
        else {
            deviceKey = nil
        }
        let sets = deviceKey != nil
        super.init(title: String(localized: sets ? "air_password_set_title" : "change_password"),
                   body: sets ? String(localized: "air_password_set_body") : nil,
                   mainTitle: String(localized: sets ? "air_password_set_action" : "air_password_change_action"))
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.password.screen"
        navigationItem.leftBarButtonItem = UIBarButtonItem(title: String(localized: "alert_cancel"),
                                                           style: .plain,
                                                           target: self,
                                                           action: #selector(cancelTapped))

        oldField.field.accessibilityIdentifier = "air.password.old"
        oldField.field.textContentType = .password
        newField.field.accessibilityIdentifier = "air.password.new"
        repeatField.field.accessibilityIdentifier = "air.password.repeat"
        AirNewPassword.prepare(newField)
        AirNewPassword.prepare(repeatField)

        var parts: [UIView] = [newField, repeatField, note]
        if !setsPassword {
            parts.insert(oldField, at: 0)
        }
        for field in [oldField, newField, repeatField] {
            field.field.returnKeyType = .next
            field.onChange = { [weak self] in
                self?.update()
            }
        }
        repeatField.field.returnKeyType = .done
        oldField.onReturn = { [weak self] in
            self?.newField.field.becomeFirstResponder()
        }
        newField.onReturn = { [weak self] in
            self?.repeatField.field.becomeFirstResponder()
        }
        repeatField.onReturn = { [weak self] in
            self?.mainTapped()
        }
        fields = AirKit.column(parts, spacing: KQSpace.x8)
        fields.setCustomSpacing(KQSpace.x6, after: repeatField)
        column.addArrangedSubview(fields)
        update()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        let first = setsPassword ? newField : oldField
        if first.text.isEmpty {
            first.field.becomeFirstResponder()
        }
    }

    private var reason: String? {
        if !setsPassword, oldField.text.isEmpty {
            return String(localized: "air_password_old_first")
        }
        return AirNewPassword.reason(newField.text, repeated: repeatField.text)
    }

    private func update() {
        setWaiting(reason)
        note.clear()
        oldField.hasError = false
    }

    private func setBusy(_ busy: Bool) {
        mainButton.setBusy(busy ? String(localized: "air_password_saving") : nil)
        fields.isUserInteractionEnabled = !busy
        navigationItem.leftBarButtonItem?.isEnabled = !busy
        isModalInPresentation = busy
    }

    @objc private func cancelTapped() {
        delegate?.changePasswordControllerDidFinishPresenting(self)
    }

    override func mainTapped() {
        guard reason == nil, let manager = toxManager else {
            update()
            return
        }
        view.endEditing(true)
        let oldPassword = deviceKey ?? oldField.text
        let newPassword = newField.text
        let sets = setsPassword
        let path = profilePath
        setBusy(true)

        // KHANDAQ (audit #15): re-encrypting a large save takes seconds; the sheet holds still meanwhile
        // (no Cancel, no swipe), and a controller gone by then is simply not told.
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let changed = manager.changeEncryptPassword(newPassword, oldPassword: oldPassword)
            DispatchQueue.main.async {
                guard changed else {
                    self?.showWrongOld()
                    return
                }
                let keychain = KeychainManager()
                if sets || keychain.toxPasswordForActiveAccount != nil {
                    keychain.toxPasswordForActiveAccount = newPassword
                }
                if sets, let path = path {
                    // Opened with the password from now on: the key has nothing left to open.
                    ProfileDeviceKey.remove(profilePath: path)
                    // The security page and You say what the password changes.
                    NotificationCenter.default.post(name: .khandaqProfileSettingsDidChange, object: nil)
                }
                guard let self = self else {
                    return
                }
                let host = self.presentingViewController?.view
                self.delegate?.changePasswordControllerDidFinishPresenting(self)
                if let host = host {
                    AirToast.show(String(localized: sets ? "air_password_set_done" : "air_password_changed"), in: host)
                }
            }
        }
    }

    private func showWrongOld() {
        setBusy(false)
        if setsPassword {
            // The key of this iPhone did not open the profile: nothing the person typed was wrong.
            note.onDetails = nil
            note.showError(String(localized: "air_password_set_failed"), details: false)
            return
        }
        note.onDetails = nil
        note.showError(String(localized: "air_password_wrong_old"), details: false)
        oldField.hasError = true
        oldField.shake()
        oldField.field.becomeFirstResponder()
        oldField.field.selectAll(nil)
    }
}
