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
    fileprivate let languageModel = StaticTableInfoCellModel()   // KHANDAQ (#248)
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

        super.init(theme: theme, style: StaticTableController.insetGroupedStyle, model: SettingsMainController.buildSections(
            exportProfileModel: exportProfileModel,
            importProfileModel: importProfileModel,
            autodownloadImagesModel: autodownloadImagesModel,
            longerbgModel: longerbgModel,
            darkThemeModel: darkThemeModel,
            languageModel: languageModel,
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
        // KHANDAQ (#248): language sits right under the theme switch — the first place anyone looks.
        languageModel.title = String(localized: "settings_language")
        languageModel.value = AppLanguage.nativeName(for: AppLanguage.current)
        languageModel.showArrow = true
        languageModel.didSelectHandler = changeAppLanguage

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
        longerbgModel.title = String(localized: "settings_longer_bg_mode")
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

    // KHANDAQ (#248): pick the app language. Each option is written in its own language, and the
    // whole UI is rebuilt right after so the change is visible without restarting the app.
    func changeAppLanguage(_: StaticTableBaseCell) {
        let sheet = UIAlertController(title: String(localized: "settings_language_title"),
                                      message: nil,
                                      preferredStyle: .actionSheet)

        for code in AppLanguage.supportedCodes {
            let name = AppLanguage.nativeName(for: code)
            let mark = (code == AppLanguage.current) ? " ✓" : ""
            sheet.addAction(UIAlertAction(title: name + mark, style: .default) { [weak self] _ in
                guard code != AppLanguage.current else { return }
                AppLanguage.set(code)
                self?.reloadAfterLanguageChange()
            })
        }
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        // iPad needs an anchor for action sheets.
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = view
            popover.sourceRect = CGRect(x: view.bounds.midX, y: view.bounds.midY, width: 0, height: 0)
            popover.permittedArrowDirections = []
        }
        present(sheet, animated: true, completion: nil)
    }

    func reloadAfterLanguageChange() {
        // Rebuild from the window down: titles, tab bar and layout direction are all decided when
        // views are created, so refreshing this screen alone would leave the rest in the old language.
        guard let window = view.window ?? UIApplication.shared.keyWindow else {
            updateModels()
            reloadTableView()
            return
        }
        NotificationCenter.default.post(name: Notification.Name("KhandaqAppLanguageChanged"), object: nil)
        UIView.transition(with: window, duration: 0.25, options: .transitionCrossDissolve, animations: {
            window.rootViewController?.view.setNeedsLayout()
        }, completion: nil)
        updateModels()
        reloadTableView()
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
                              languageModel: StaticTableInfoCellModel,
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
            languageModel,
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
            languageModel,
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
            String(localized: "settings_longer_bg_mode_description"),
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
