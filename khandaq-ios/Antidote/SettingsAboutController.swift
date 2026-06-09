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
    fileprivate let toxcoreVersionModel = StaticTableInfoCellModel()
    fileprivate let acknowledgementsModel = StaticTableDefaultCellModel()

    init(theme: Theme) {
        super.init(theme: theme, style: .grouped, model: [
            [
                khandaqVersionModel,
                khandaqBuildModel,
                websiteModel,
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
        websiteModel.value = "https://khandaq.org"
        websiteModel.didSelectHandler = openWebsite
        websiteModel.rightImageType = .arrow

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
}
