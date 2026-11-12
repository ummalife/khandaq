// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import LocalAuthentication

protocol ProfileDetailsControllerDelegate: class {
    func profileDetailsControllerSetPin(_ controller: ProfileDetailsController)
    func profileDetailsControllerChangeLockTimeout(_ controller: ProfileDetailsController)
    func profileDetailsControllerChangePassword(_ controller: ProfileDetailsController)
}

/// "Password and PIN" from "You" (E17, E20), drawn as the screens of Settings are: the PIN, when it
/// locks and the face or the finger instead of it; the password of the profile; and the copy of the
/// profile file. What cannot be recovered is said in the section it belongs to. The PIN sheet, the
/// password page and the lock choice are the ones the old page opened (ProfileTabCoordinator).
final class ProfileDetailsController: AirScrollController, UIDocumentInteractionControllerDelegate {
    weak var delegate: ProfileDetailsControllerDelegate?

    private weak var toxManager: OCTManager!

    private let pinSwitch = UISwitch()
    private let biometricSwitch = UISwitch()
    private var lockRow: AirSettingRow!
    private var biometricRow: AirSettingRow?
    private var passwordRow: AirSettingRow!
    private var passwordNote: UILabel!
    private let exportFailure = AirFieldNote(hint: nil)
    private var documentInteractionController: UIDocumentInteractionController?

    init(theme: Theme, toxManager: OCTManager) {
        self.toxManager = toxManager
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
        title = String(localized: "air_you_security")
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.security.screen"

        // The PIN, when it locks, and the face or the finger instead of it.
        let pinRow = makeToggleRow(title: String(localized: "air_security_pin"), detail: nil, toggle: pinSwitch,
                                   identifier: "air.security.pin", action: #selector(pinChanged))
        lockRow = AirSettingRow(title: String(localized: "air_security_lock"))
        lockRow.accessibilityIdentifier = "air.security.lock"
        lockRow.addTarget(self, action: #selector(lockTapped), for: .touchUpInside)
        var pinRows: [UIView] = [pinRow, lockRow]
        if let title = AirBiometrics.toggleTitle() {
            let row = makeToggleRow(title: title, detail: String(localized: "air_security_biometrics_note"), toggle: biometricSwitch,
                                    identifier: "air.security.biometrics", action: #selector(biometricsChanged))
            biometricRow = row
            pinRows.append(row)
        }
        addSection(String(localized: "air_security_pin_section"), rows: pinRows, note: String(localized: "air_security_pin_note"))

        // The password of the profile, or a password for a profile that opens with this iPhone's key.
        passwordRow = AirSettingRow(title: "")
        passwordRow.accessibilityIdentifier = "air.security.password"
        passwordRow.addTarget(self, action: #selector(passwordTapped), for: .touchUpInside)
        let passwordSection = addSection(String(localized: "air_security_password_section"), rows: [passwordRow])
        passwordNote = AirKit.note("")
        passwordSection.addArrangedSubview(passwordNote)

        // The copy of the profile file; a failure stands under it (E21).
        let exportRow = AirSettingRow(title: String(localized: "export_profile"))
        exportRow.accessibilityIdentifier = "air.security.export"
        exportRow.addTarget(self, action: #selector(exportTapped), for: .touchUpInside)
        exportFailure.accessibilityIdentifier = "air.security.export.why"
        let exportSection = addSection(String(localized: "air_settings_transfer"), rows: [exportRow],
                                       note: String(localized: "air_settings_transfer_note"))
        exportSection.addArrangedSubview(exportFailure)

        NotificationCenter.default.addObserver(self,
                                               selector: #selector(profileSettingsDidChange),
                                               name: .khandaqProfileSettingsDidChange,
                                               object: nil)
        refresh()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // Back from the password page or the lock choice.
        refresh()
    }

    // MARK: State

    private func refresh() {
        // A page of a session that has ended (Log out, another profile) can still hear the settings
        // notification of the next session: its profile is gone, so there is nothing to show.
        guard toxManager != nil, isViewLoaded else {
            return
        }
        let settings = toxManager.objects.getProfileSettings()
        let isPinEnabled = settings.unlockPinCode != nil
        pinSwitch.setOn(isPinEnabled, animated: pinSwitch.window != nil)
        // When it locks and the face or the finger mean something only with a PIN.
        lockRow.isHidden = !isPinEnabled
        lockRow.setValue(AirLockTimeoutText.title(settings.lockTimeout))
        biometricRow?.isHidden = !isPinEnabled
        biometricSwitch.setOn(isPinEnabled && settings.useTouchID, animated: biometricSwitch.window != nil)

        let hasDeviceKey = ProfileDeviceKey.activeProfileHasKey
        let title = String(localized: hasDeviceKey ? "air_password_set_title" : "air_password_change_action")
        passwordRow.titleLabel.text = title
        passwordRow.setValue(nil)
        passwordNote.text = String(localized: hasDeviceKey ? "air_security_device_key_note" : "air_security_password_note")
    }

    @objc private func profileSettingsDidChange() {
        refresh()
    }

    private func notifyProfileSettingsDidChange() {
        refresh()
        NotificationCenter.default.post(name: .khandaqProfileSettingsDidChange, object: nil)
    }

    private func makeToggleRow(title: String, detail: String?, toggle: UISwitch, identifier: String, action: Selector) -> AirSettingRow {
        let row = AirSettingRow(title: title, detail: detail, opens: false)
        toggle.onTintColor = KQColor.switchOn
        toggle.accessibilityIdentifier = identifier
        toggle.accessibilityLabel = title
        toggle.accessibilityHint = detail
        toggle.addTarget(self, action: action, for: .valueChanged)
        row.setAccessory(toggle)
        return row
    }

    // MARK: The PIN

    @objc private func pinChanged() {
        if pinSwitch.isOn {
            // The switch shows on while the PIN is set on its sheet; the settings notification puts it
            // back when the sheet is closed without one.
            delegate?.profileDetailsControllerSetPin(self)
            return
        }
        let settings = toxManager.objects.getProfileSettings()
        settings.unlockPinCode = nil
        settings.useTouchID = false
        toxManager.objects.saveProfileSettings(settings)
        notifyProfileSettingsDidChange()
    }

    @objc private func lockTapped() {
        delegate?.profileDetailsControllerChangeLockTimeout(self)
    }

    @objc private func biometricsChanged() {
        let settings = toxManager.objects.getProfileSettings()
        guard settings.unlockPinCode != nil else {
            notifyProfileSettingsDidChange()
            return
        }
        guard biometricSwitch.isOn else {
            settings.useTouchID = false
            toxManager.objects.saveProfileSettings(settings)
            notifyProfileSettingsDidChange()
            return
        }
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            presentNotice(String(localized: "pin_biometrics_unavailable"))
            notifyProfileSettingsDidChange()
            return
        }
        context.evaluatePolicy(.deviceOwnerAuthenticationWithBiometrics,
                               localizedReason: String(localized: "air_security_biometrics_note")) { [weak self] success, evaluateError in
            DispatchQueue.main.async {
                guard let self = self, self.toxManager != nil else {
                    return
                }
                let settings = self.toxManager.objects.getProfileSettings()
                settings.useTouchID = success
                self.toxManager.objects.saveProfileSettings(settings)
                let cancelled = [LAError.userCancel, .systemCancel, .appCancel].map { $0.rawValue }
                if !success, let code = (evaluateError as NSError?)?.code, !cancelled.contains(code) {
                    self.presentNotice(String(localized: "pin_biometrics_failed"))
                }
                self.notifyProfileSettingsDidChange()
            }
        }
    }

    private func presentNotice(_ message: String) {
        let alert = UIAlertController(title: nil, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "error_ok_button"), style: .default, handler: nil))
        present(alert, animated: true, completion: nil)
    }

    // MARK: The password and the copy of the profile

    @objc private func passwordTapped() {
        delegate?.profileDetailsControllerChangePassword(self)
    }

    @objc private func exportTapped() {
        exportFailure.clear()
        // The file of a profile without a password opens only with the key of this iPhone: elsewhere
        // it is of no use. Said first, with the way out (stage 4).
        guard !ProfileDeviceKey.activeProfileHasKey else {
            let sheet = AirConfirm.sheet(title: String(localized: "air_export_needs_password_title"),
                                         message: String(localized: "air_export_needs_password_message"),
                                         verb: String(localized: "air_password_set_action"),
                                         style: .default,
                                         source: view) { [weak self] in
                guard let self = self else {
                    return
                }
                self.delegate?.profileDetailsControllerChangePassword(self)
            }
            present(sheet, animated: true, completion: nil)
            return
        }
        do {
            let path = try toxManager.exportToxSaveFile()
            let name = UserDefaultsManager().lastActiveProfile ?? "profile"
            let document = UIDocumentInteractionController(url: URL(fileURLWithPath: path))
            document.delegate = self
            document.name = "\(name).tox"
            documentInteractionController = document
            document.presentOptionsMenu(from: view.frame, in: view, animated: true)
        }
        catch let error as NSError {
            // Said where the operation is, its code under "Details" (E21).
            let words = errorAlert(for: .exportProfile, error: error)
            exportFailure.onDetails = words?.details.map { code in
                { UIAlertController.showDetails(code) }
            }
            let text = [words?.title, words?.message].compactMap { $0 }.joined(separator: " ")
            exportFailure.showError(text.isEmpty ? String(localized: "export_profile") : text, details: words?.details != nil)
        }
    }

    func documentInteractionControllerViewControllerForPreview(_ controller: UIDocumentInteractionController) -> UIViewController {
        return self
    }

    func documentInteractionControllerViewForPreview(_ controller: UIDocumentInteractionController) -> UIView? {
        return view
    }

    func documentInteractionControllerRectForPreview(_ controller: UIDocumentInteractionController) -> CGRect {
        return view.frame
    }
}

/// The words for when the app locks after it was left.
enum AirLockTimeoutText {
    static let all: [ProfileSettings.LockTimeout] = [.Immediately, .Seconds30, .Minute1, .Minute2, .Minute5]

    static func title(_ timeout: ProfileSettings.LockTimeout) -> String {
        switch timeout {
            case .Immediately:
                return String(localized: "air_security_lock_now")
            case .Seconds30:
                return String(localized: "air_security_lock_30s")
            case .Minute1:
                return String(localized: "air_security_lock_1m")
            case .Minute2:
                return String(localized: "air_security_lock_2m")
            case .Minute5:
                return String(localized: "air_security_lock_5m")
        }
    }
}

/// Face ID or Touch ID, as this iPhone has it.
enum AirBiometrics {
    /// The title of the switch, nil when the iPhone has neither.
    static func toggleTitle() -> String? {
        let context = LAContext()
        var error: NSError?
        guard context.canEvaluatePolicy(.deviceOwnerAuthenticationWithBiometrics, error: &error) else {
            return nil
        }
        switch context.biometryType {
            case .faceID:
                return String(localized: "air_security_face_id")
            default:
                return String(localized: "air_security_touch_id")
        }
    }
}
