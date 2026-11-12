// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol SettingsMainControllerDelegate: class {
    func settingsMainControllerShowAppearance(_ controller: SettingsMainController)
    func settingsMainControllerShowNotifications(_ controller: SettingsMainController)
    func settingsMainControllerShowPrivacy(_ controller: SettingsMainController)
    func settingsMainControllerShowTransfer(_ controller: SettingsMainController)
    func settingsMainControllerShowAdvancedSettings(_ controller: SettingsMainController)
    func settingsMainControllerShowFaqScreen(_ controller: SettingsMainController)
    func settingsMainControllerShowAboutScreen(_ controller: SettingsMainController)
    #if DEBUG
    func settingsMainControllerShowDebug(_ controller: SettingsMainController)
    #endif
}

/// Settings (redesign stage 3, E18): the sections by task, each opening a screen of its own, as
/// design/redesign/02-plan.md lays them out for the phone. What a person looks for comes first; the
/// profile file, rarely needed, sits in its own section instead of at the top; debugging is there only
/// in debug builds.
class SettingsMainController: AirScrollController {
    weak var delegate: SettingsMainControllerDelegate?

    fileprivate let userDefaults = UserDefaultsManager()
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects?

    private let appearanceRow = AirSettingRow(title: String(localized: "air_settings_appearance"))

    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects?) {
        self.submanagerObjects = submanagerObjects
        super.init(nibName: nil, bundle: nil)
        title = String(localized: "settings_title")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "settings.root"
        appearanceRow.accessibilityIdentifier = "settings.appearance"
        opens(appearanceRow) { $0.settingsMainControllerShowAppearance($1) }
        let tasks = [
            appearanceRow,
            row("air_settings_notifications", "settings.notifications") { $0.settingsMainControllerShowNotifications($1) },
            row("air_privacy_title", "settings.privacy") { $0.settingsMainControllerShowPrivacy($1) },
            row("air_settings_transfer", "settings.transfer") { $0.settingsMainControllerShowTransfer($1) },
            row("air_settings_network", "settings.network") { $0.settingsMainControllerShowAdvancedSettings($1) },
        ]
        addSection(nil, rows: tasks)
        addSection(nil, rows: [
            row("settings_faq", "settings.faq") { $0.settingsMainControllerShowFaqScreen($1) },
            row("settings_about", "settings.about") { $0.settingsMainControllerShowAboutScreen($1) },
        ])
        #if DEBUG
        addSection(nil, rows: [row("air_settings_debug", "settings.debug") { $0.settingsMainControllerShowDebug($1) }])
        #endif
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // The switch lives in the defaults; the database reads its own copy of it.
        submanagerObjects?.setGroupShowSystemMessages(userDefaults.groupShowSystemMessages)
        appearanceRow.setValue(ThemeAppearance.preference.localizedName)
    }
}

private extension SettingsMainController {
    typealias Show = (SettingsMainControllerDelegate, SettingsMainController) -> Void

    /// A row that opens a section through the delegate.
    func row(_ titleKey: String, _ identifier: String, show: @escaping Show) -> AirSettingRow {
        let row = AirSettingRow(title: String(localized: titleKey))
        row.accessibilityIdentifier = identifier
        opens(row, show: show)
        return row
    }

    func opens(_ row: AirSettingRow, show: @escaping Show) {
        row.onTap { [weak self] in
            guard let self = self, let delegate = self.delegate else {
                return
            }
            show(delegate, self)
        }
    }
}
