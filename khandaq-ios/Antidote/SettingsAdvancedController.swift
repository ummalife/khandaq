// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol SettingsAdvancedControllerDelegate: class {
    func settingsAdvancedControllerToxOptionsChanged(_ controller: SettingsAdvancedController)
}

/// Settings > Network (redesign stage 3, E18): UDP, the connection log, when attachments download by
/// themselves, the call audio engine and, in debug builds, the longer background mode with its cost
/// in one phrase. A change to UDP restarts the network after a confirmation, as before.
class SettingsAdvancedController: AirScrollController {
    weak var delegate: SettingsAdvancedControllerDelegate?
    weak var sessionCoordinator: ActiveSessionCoordinator?

    fileprivate let theme: Theme
    fileprivate let userDefaults = UserDefaultsManager()

    fileprivate let udpSwitch = UISwitch()
    fileprivate var attachmentRows: [(choice: UserDefaultsManager.AutodownloadImages, row: AirChoiceRow)] = []
    // KHANDAQ (#112/#166): escape hatch for the new VoiceProcessingIO call-audio engine.
    fileprivate static let callAudioVPIODisabledKey = "KhandaqCallAudioVPIODisabled"

    init(theme: Theme) {
        self.theme = theme
        super.init(nibName: nil, bundle: nil)
        title = String(localized: "air_settings_network")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "settings.network.screen"

        let udpTitle = String(localized: "settings_udp_enabled")
        let udp = AirSettingRow(title: udpTitle, opens: false)
        udpSwitch.isOn = userDefaults.UDPEnabled
        udpSwitch.onTintColor = KQColor.switchOn
        udpSwitch.accessibilityIdentifier = "settings.udp"
        udpSwitch.accessibilityLabel = udpTitle
        udpSwitch.addTarget(self, action: #selector(udpChanged), for: .valueChanged)
        udp.setAccessory(udpSwitch)
        let connections = AirSettingRow(title: String(localized: "network_connections_title"))
        connections.accessibilityIdentifier = "settings.connections"
        connections.addTarget(self, action: #selector(showNetworkDiagnostics), for: .touchUpInside)
        addSection(nil, rows: [udp, connections])

        let choices: [(UserDefaultsManager.AutodownloadImages, String, String)] = [
            (.Never, "settings_never", "never"),
            (.UsingWiFi, "settings_using_wifi", "wifi"),
            (.Always, "settings_always", "always"),
        ]
        attachmentRows = choices.map { choice, key, name in
            let row = AirChoiceRow(title: String(localized: key))
            row.accessibilityIdentifier = "settings.attachments.\(name)"
            row.addTarget(self, action: #selector(attachmentChoiceTapped(_:)), for: .touchUpInside)
            return (choice: choice, row: row)
        }
        addSection(String(localized: "settings_autodownload_images"), rows: attachmentRows.map { $0.row },
                   note: String(localized: "air_settings_attachments_note"))
        markAttachmentChoice()

        let callAudio = AirSettingRow.toggle(title: String(localized: "settings_call_audio_vpio"),
                                             isOn: !UserDefaults.standard.bool(forKey: SettingsAdvancedController.callAudioVPIODisabledKey),
                                             identifier: "settings.call-audio") { on in
            // Takes effect on the next call: the engine reads the flag when call audio starts.
            UserDefaults.standard.set(!on, forKey: SettingsAdvancedController.callAudioVPIODisabledKey)
        }
        addSection(String(localized: "air_settings_calls"), rows: [callAudio])

        #if DEBUG
        let longer = AirSettingRow.toggle(title: String(localized: "settings_longer_bg_mode"), isOn: userDefaults.LongerbgMode,
                                          identifier: "settings.longer-background") { [weak self] on in
            self?.userDefaults.LongerbgMode = on
        }
        addSection(nil, rows: [longer], note: String(localized: "settings_longer_bg_mode_description"))
        #endif

        let restore = AirSettingRow(title: String(localized: "settings_restore_default"), opens: false, danger: true)
        restore.accessibilityIdentifier = "settings.restore"
        restore.addTarget(self, action: #selector(restoreDefaultsSettings), for: .touchUpInside)
        addSection(nil, rows: [restore])
    }
}

private extension SettingsAdvancedController {
    @objc func showNetworkDiagnostics() {
        let controller = NetworkDiagnosticsDetailController(theme: theme)
        navigationController?.pushViewController(controller, animated: true)
    }

    @objc func attachmentChoiceTapped(_ sender: AirChoiceRow) {
        guard let picked = attachmentRows.first(where: { $0.row === sender }) else {
            return
        }
        userDefaults.autodownloadImages = picked.choice
        markAttachmentChoice()
    }

    func markAttachmentChoice() {
        let current = userDefaults.autodownloadImages
        for item in attachmentRows {
            item.row.isSelected = item.choice == current
        }
    }

    @objc func udpChanged() {
        let on = udpSwitch.isOn
        // The switch shows the stored value until the restart is confirmed.
        udpSwitch.setOn(userDefaults.UDPEnabled, animated: true)

        let title = String(localized: "settings_udp_restart_title")
        let message = on
            ? String(localized: "settings_udp_restart_enable_message")
            : String(localized: "settings_udp_restart_disable_message")

        // E21: a confirmation comes from below, with the verb on its button.
        let sheet = AirConfirm.sheet(title: title,
                                     message: message,
                                     verb: String(localized: "settings_udp_restart_confirm"),
                                     style: .default,
                                     source: udpSwitch) { [weak self] in
            guard let self = self, let delegate = self.delegate else {
                return
            }
            self.userDefaults.UDPEnabled = on
            self.udpSwitch.setOn(on, animated: true)
            ToxOptionsRestartScheduler.requestRestart(from: self, delegate: delegate)
        }
        present(sheet, animated: true, completion: nil)
    }

    @objc func restoreDefaultsSettings() {
        guard userDefaults.UDPEnabled != false else {
            userDefaults.resetUDPEnabled()
            return
        }

        let sheet = AirConfirm.sheet(title: String(localized: "settings_udp_restart_title"),
                                     message: String(localized: "settings_udp_restart_disable_message"),
                                     verb: String(localized: "settings_udp_restart_confirm"),
                                     style: .default,
                                     source: udpSwitch) { [weak self] in
            guard let self = self, let delegate = self.delegate else {
                return
            }
            self.userDefaults.resetUDPEnabled()
            self.udpSwitch.setOn(false, animated: true)
            ToxOptionsRestartScheduler.requestRestart(from: self, delegate: delegate)
        }
        present(sheet, animated: true, completion: nil)
    }
}
