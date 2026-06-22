// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

protocol SettingsMainControllerDelegate: class {
    func settingsMainControllerShowAboutScreen(_ controller: SettingsMainController)
    func settingsMainControllerShowFaqScreen(_ controller: SettingsMainController)
    func settingsMainControllerShowAdvancedSettings(_ controller: SettingsMainController)
    func settingsMainControllerChangeAutodownloadImages(_ controller: SettingsMainController)
    func settingsMainControllerExportProfile(_ controller: SettingsMainController)
    func settingsMainControllerImportProfile(_ controller: SettingsMainController)
}

class SettingsMainController: StaticTableController {
    weak var delegate: SettingsMainControllerDelegate?

    fileprivate let theme: Theme
    fileprivate let userDefaults = UserDefaultsManager()

    fileprivate let aboutModel = StaticTableDefaultCellModel()
    fileprivate let faqModel = StaticTableDefaultCellModel()
    fileprivate let exportProfileModel = StaticTableDefaultCellModel()
    fileprivate let importProfileModel = StaticTableDefaultCellModel()
    fileprivate let autodownloadImagesModel = StaticTableInfoCellModel()
    fileprivate let darkThemeModel = StaticTableSwitchCellModel()
    fileprivate let notificationsModel = StaticTableSwitchCellModel()
    fileprivate let groupSystemMessagesModel = StaticTableSwitchCellModel()
    fileprivate let longerbgModel = StaticTableSwitchCellModel()
    fileprivate let debugmodeModel = StaticTableSwitchCellModel()
    fileprivate let dateonmessagemodeModel = StaticTableSwitchCellModel()
    fileprivate let advancedSettingsModel = StaticTableDefaultCellModel()

    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!

    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects) {
        self.theme = theme
        self.submanagerObjects = submanagerObjects

        super.init(theme: theme, style: .grouped, model: SettingsMainController.buildSections(
            exportProfileModel: exportProfileModel,
            importProfileModel: importProfileModel,
            autodownloadImagesModel: autodownloadImagesModel,
            longerbgModel: longerbgModel,
            darkThemeModel: darkThemeModel,
            notificationsModel: notificationsModel,
            groupSystemMessagesModel: groupSystemMessagesModel,
            dateonmessagemodeModel: dateonmessagemodeModel,
            debugmodeModel: debugmodeModel,
            advancedSettingsModel: advancedSettingsModel,
            faqModel: faqModel,
            aboutModel: aboutModel
        ), footers: SettingsMainController.buildFooters())

        title = String(localized: "settings_title")
        updateModels()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        submanagerObjects.setGroupShowSystemMessages(userDefaults.groupShowSystemMessages)
        updateModels()
        reloadTableView()
    }
}

private extension SettingsMainController{
    func updateModels() {
        aboutModel.value = String(localized: "settings_about")
        aboutModel.didSelectHandler = showAboutScreen
        aboutModel.rightImageType = .arrow

        faqModel.value = String(localized: "settings_faq")
        faqModel.didSelectHandler = showFaqScreen
        faqModel.rightImageType = .arrow

        exportProfileModel.value = String(localized: "export_profile")
        exportProfileModel.didSelectHandler = exportProfile
        exportProfileModel.rightImageType = .arrow

        importProfileModel.value = String(localized: "import_profile")
        importProfileModel.didSelectHandler = importProfile
        importProfileModel.rightImageType = .arrow

        autodownloadImagesModel.title = String(localized: "settings_autodownload_images")
        autodownloadImagesModel.showArrow = true
        autodownloadImagesModel.didSelectHandler = changeAutodownloadImages
        switch userDefaults.autodownloadImages {
            case .Never:
                autodownloadImagesModel.value = String(localized: "settings_never")
            case .UsingWiFi:
                autodownloadImagesModel.value = String(localized: "settings_wifi")
            case .Always:
                autodownloadImagesModel.value = String(localized: "settings_always")
        }

        // KHANDAQ design (Figma): the theme switch lives in Settings ("Тёмная тема").
        darkThemeModel.title = String(localized: "settings_dark_theme")
        darkThemeModel.on = ThemeAppearance.isDarkMode
        darkThemeModel.valueChangedHandler = darkThemeValueChanged

        notificationsModel.title = String(localized: "settings_notifications_message_preview")
        notificationsModel.on = userDefaults.showNotificationPreview
        notificationsModel.valueChangedHandler = notificationsValueChanged

        groupSystemMessagesModel.title = String(localized: "settings_group_system_messages")
        groupSystemMessagesModel.on = userDefaults.groupShowSystemMessages
        groupSystemMessagesModel.valueChangedHandler = groupSystemMessagesValueChanged

        #if DEBUG
        longerbgModel.title = "Longer Background Mode"
        longerbgModel.on = userDefaults.LongerbgMode
        longerbgModel.valueChangedHandler = longerbgValueChanged

        debugmodeModel.title = "Debug Mode"
        debugmodeModel.on = userDefaults.DebugMode
        debugmodeModel.valueChangedHandler = debugmodeValueChanged

        dateonmessagemodeModel.title = "Always show date on Messages"
        dateonmessagemodeModel.on = userDefaults.DateonmessageMode
        dateonmessagemodeModel.valueChangedHandler = dateonmessagemodeValueChanged
        #endif

        advancedSettingsModel.value = String(localized: "settings_advanced_settings")
        advancedSettingsModel.didSelectHandler = showAdvancedSettings
        advancedSettingsModel.rightImageType = .arrow
    }

    func showAboutScreen(_: StaticTableBaseCell) {
        delegate?.settingsMainControllerShowAboutScreen(self)
    }

    func showFaqScreen(_: StaticTableBaseCell) {
        delegate?.settingsMainControllerShowFaqScreen(self)
    }

    func darkThemeValueChanged(_ on: Bool) {
        ThemeAppearance.isDarkMode = on
    }

    func notificationsValueChanged(_ on: Bool) {
        userDefaults.showNotificationPreview = on
    }

    func groupSystemMessagesValueChanged(_ on: Bool) {
        userDefaults.groupShowSystemMessages = on
        submanagerObjects.setGroupShowSystemMessages(on)
    }

    func longerbgValueChanged(_ on: Bool) {
        userDefaults.LongerbgMode = on
    }

    func debugmodeValueChanged(_ on: Bool) {
        userDefaults.DebugMode = on
    }

    func dateonmessagemodeValueChanged(_ on: Bool) {
        userDefaults.DateonmessageMode = on
    }

    func changeAutodownloadImages(_: StaticTableBaseCell) {
        delegate?.settingsMainControllerChangeAutodownloadImages(self)
    }

    func showAdvancedSettings(_: StaticTableBaseCell) {
        delegate?.settingsMainControllerShowAdvancedSettings(self)
    }

    func exportProfile(_: StaticTableBaseCell) {
        delegate?.settingsMainControllerExportProfile(self)
    }

    func importProfile(_: StaticTableBaseCell) {
        delegate?.settingsMainControllerImportProfile(self)
    }
}

private extension SettingsMainController {
    static func buildSections(exportProfileModel: StaticTableDefaultCellModel,
                              importProfileModel: StaticTableDefaultCellModel,
                              autodownloadImagesModel: StaticTableInfoCellModel,
                              longerbgModel: StaticTableSwitchCellModel,
                              darkThemeModel: StaticTableSwitchCellModel,
                              notificationsModel: StaticTableSwitchCellModel,
                              groupSystemMessagesModel: StaticTableSwitchCellModel,
                              dateonmessagemodeModel: StaticTableSwitchCellModel,
                              debugmodeModel: StaticTableSwitchCellModel,
                              advancedSettingsModel: StaticTableDefaultCellModel,
                              faqModel: StaticTableDefaultCellModel,
                              aboutModel: StaticTableDefaultCellModel) -> [[StaticTableBaseCellModel]] {
        let profileBackup: [StaticTableBaseCellModel] = [
            exportProfileModel,
            importProfileModel,
        ]
        #if DEBUG
        let toggles: [StaticTableBaseCellModel] = [
            darkThemeModel,
            notificationsModel,
            groupSystemMessagesModel,
            dateonmessagemodeModel,
            debugmodeModel,
        ]
        return [
            profileBackup,
            [autodownloadImagesModel],
            [longerbgModel],
            toggles,
            [advancedSettingsModel],
            [faqModel, aboutModel],
        ]
        #else
        let toggles: [StaticTableBaseCellModel] = [
            darkThemeModel,
            notificationsModel,
            groupSystemMessagesModel,
        ]
        return [
            profileBackup,
            [autodownloadImagesModel],
            toggles,
            [advancedSettingsModel],
            [faqModel, aboutModel],
        ]
        #endif
    }

    static func buildFooters() -> [String?] {
        #if DEBUG
        return [
            String(localized: "settings_profile_backup_description"),
            String(localized: "settings_autodownload_images_description"),
            "This will keep the Application running for longer in the background to finish sending messages, but this will also reveal more meta data about you. It will link your IP address and your PUSH token. It's a tradeoff between convenience and metadata privacy.\n\nYou can use MyCitadel VPN to prevent that.\n\nSee https://mycitadel.vip\n\nand\n\nhttps://t.me/MyCitadelBot",
            nil,
            nil,
            nil,
        ]
        #else
        return [
            String(localized: "settings_profile_backup_description"),
            String(localized: "settings_autodownload_images_description"),
            nil,
            nil,
            nil,
        ]
        #endif
    }
}
