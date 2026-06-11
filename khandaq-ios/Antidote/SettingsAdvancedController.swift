// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol SettingsAdvancedControllerDelegate: class {
    func settingsAdvancedControllerToxOptionsChanged(_ controller: SettingsAdvancedController)
}

class SettingsAdvancedController: StaticTableController {
    weak var delegate: SettingsAdvancedControllerDelegate?

    fileprivate let theme: Theme
    fileprivate let userDefaults = UserDefaultsManager()

    fileprivate let UDPModel = StaticTableSwitchCellModel()
    fileprivate let networkDiagnosticsModel = StaticTableDefaultCellModel()
    fileprivate let restoreDefaultsModel = StaticTableButtonCellModel()

    init(theme: Theme) {
        self.theme = theme

        super.init(theme: theme, style: .grouped, model: [
            [
                UDPModel,
            ],
            [
                networkDiagnosticsModel,
            ],
            [
                restoreDefaultsModel,
            ],
        ])

        title = String(localized: "settings_advanced_settings")
        updateModels()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

private extension SettingsAdvancedController {
    func updateModels() {
        UDPModel.title = String(localized: "settings_udp_enabled")
        UDPModel.on = userDefaults.UDPEnabled
        UDPModel.valueChangedHandler = UDPChanged

        networkDiagnosticsModel.title = "Network diagnostics"
        networkDiagnosticsModel.value = "DHT, relays, reconnect log"
        networkDiagnosticsModel.rightImageType = .arrow
        networkDiagnosticsModel.didSelectHandler = showNetworkDiagnostics

        restoreDefaultsModel.title = String(localized: "settings_restore_default")
        restoreDefaultsModel.didSelectHandler = restoreDefaultsSettings
    }

    func showNetworkDiagnostics(_: StaticTableBaseCell) {
        let quality = ConnectionQualityMonitor.shared
        let body = """
        --- event log ---
        \(NetworkDiagnosticsLog.snapshot())

        --- runtime ---
        connection_quality=\(quality.level.rawValue)
        estimated_rtt_ms=\(quality.estimatedRttMs)
        """
        let controller = TextViewController(
            plainText: body,
            backgroundColor: theme.colorForType(.NormalBackground),
            titleColor: theme.colorForType(.NormalText),
            textColor: theme.colorForType(.NormalText))
        controller.title = "Network diagnostics"
        navigationController?.pushViewController(controller, animated: true)
    }

    func UDPChanged(_ on: Bool) {
        let previous = userDefaults.UDPEnabled
        UDPModel.on = previous

        let title = String(localized: "settings_udp_restart_title")
        let message = on
            ? String(localized: "settings_udp_restart_enable_message")
            : String(localized: "settings_udp_restart_disable_message")

        let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "settings_udp_restart_confirm"), style: .default) { [weak self] _ in
            guard let self = self, let delegate = self.delegate else {
                return
            }
            self.userDefaults.UDPEnabled = on
            self.UDPModel.on = on
            ToxOptionsRestartScheduler.requestRestart(from: self, delegate: delegate)
        })
        present(alert, animated: true, completion: nil)
    }

    func restoreDefaultsSettings(_: StaticTableBaseCell) {
        guard userDefaults.UDPEnabled != false else {
            userDefaults.resetUDPEnabled()
            return
        }

        let alert = UIAlertController(
            title: String(localized: "settings_udp_restart_title"),
            message: String(localized: "settings_udp_restart_disable_message"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "settings_udp_restart_confirm"), style: .default) { [weak self] _ in
            guard let self = self, let delegate = self.delegate else {
                return
            }
            self.userDefaults.resetUDPEnabled()
            self.UDPModel.on = false
            ToxOptionsRestartScheduler.requestRestart(from: self, delegate: delegate)
        })
        present(alert, animated: true, completion: nil)
    }
}
