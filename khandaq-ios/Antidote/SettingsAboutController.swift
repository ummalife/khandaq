// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol SettingsAboutControllerDelegate: class {
    func settingsAboutControllerShowAcknowledgements(_ controller: SettingsAboutController)
}

class SettingsAboutController: StaticTableController {
    weak var delegate: SettingsAboutControllerDelegate?

    fileprivate let khandaqVersionModel = StaticTableInfoCellModel()
    fileprivate let khandaqBuildModel = StaticTableInfoCellModel()
    fileprivate let websiteModel = StaticTableDefaultCellModel()
    fileprivate let creditModel = StaticTableDefaultCellModel()
    fileprivate let toxcoreVersionModel = StaticTableInfoCellModel()
    fileprivate let acknowledgementsModel = StaticTableDefaultCellModel()

    init(theme: Theme) {
        super.init(theme: theme, style: StaticTableController.insetGroupedStyle, model: [
            [
                khandaqVersionModel,
                khandaqBuildModel,
                websiteModel,
                creditModel,
            ],
            [
                toxcoreVersionModel,
            ],
            [
                acknowledgementsModel,
            ],
        ])

        title = String(localized: "settings_about")
        updateModels()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}

private extension SettingsAboutController {
    func updateModels() {
        khandaqVersionModel.title = String(localized: "settings_khandaq_version")
        khandaqVersionModel.value =  Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String

        khandaqBuildModel.title = String(localized: "settings_khandaq_build")
        khandaqBuildModel.value = Bundle.main.infoDictionary?["CFBundleVersion"] as? String

        websiteModel.title = String(localized: "settings_website")
        // KHANDAQ (18.08): bare host, so this row and the credit row below read the same way
        // (Android already shows both as bare hosts). The tap still opens the full URL.
        websiteModel.value = "khandaq.org"
        websiteModel.didSelectHandler = openWebsite
        websiteModel.rightImageType = .arrow

        // KHANDAQ (18.08): credit the owner/developer directly under the project's own site — the
        // same row shape as "Website" above, so the tap opens https://1sa.me/ in the browser. The
        // caption is a whole sentence, hence multilineTitle: one fixed line clips it in ru.
        creditModel.title = String(localized: "settings_credit_owner")
        creditModel.multilineTitle = true
        creditModel.value = "1sa.me"
        creditModel.didSelectHandler = openDeveloperWebsite
        creditModel.rightImageType = .arrow

        toxcoreVersionModel.title = String(localized: "settings_toxcore_version")
        toxcoreVersionModel.value = OCTTox.version()

        acknowledgementsModel.value = String(localized: "settings_acknowledgements")
        acknowledgementsModel.didSelectHandler = showAcknowledgements
        acknowledgementsModel.rightImageType = .arrow
    }

    func showAcknowledgements(_: StaticTableBaseCell) {
        delegate?.settingsAboutControllerShowAcknowledgements(self)
    }

    func openWebsite(_: StaticTableBaseCell) {
        guard let url = URL(string: "https://khandaq.org") else { return }
        UIApplication.shared.open(url)
    }

    func openDeveloperWebsite(_: StaticTableBaseCell) {
        guard let url = URL(string: "https://1sa.me/") else { return }
        UIApplication.shared.open(url)
    }
}
