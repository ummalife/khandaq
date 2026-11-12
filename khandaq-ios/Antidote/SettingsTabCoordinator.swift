// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SafariServices
import MobileCoreServices

private struct Options {
    static let ToShowKey = "ToShowKey"

    enum Controller {
        case advancedSettings
        case appearance
    }
}

protocol SettingsTabCoordinatorDelegate: class {
    func settingsTabCoordinatorRecreateCoordinatorsStack(_ coordinator: SettingsTabCoordinator, options: CoordinatorOptions)
}

class SettingsTabCoordinator: ActiveSessionNavigationCoordinator {
    weak var delegate: SettingsTabCoordinatorDelegate?
    weak var sessionCoordinator: ActiveSessionCoordinator?
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate weak var toxManager: OCTManager!

    fileprivate var documentInteractionController: UIDocumentInteractionController?

    /// `navigationController`: on the phone the stack is shared with the Air screen at its root.
    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects, toxManager: OCTManager, navigationController: UINavigationController? = nil) {
        self.submanagerObjects = submanagerObjects
        self.toxManager = toxManager
        if let navigationController = navigationController {
            super.init(theme: theme, navigationController: navigationController)
        }
        else {
            super.init(theme: theme)
        }
    }

    override func startWithOptions(_ options: CoordinatorOptions?) {
        installRootViewController(makeMainController())
        pushStoredScreen(from: options)
    }

    /// The phone has no tabs: Settings open above the Air screen in the shared stack, and a rebuilt
    /// stack (a language change) comes back with the screen that asked for it.
    func pushSettings(options: CoordinatorOptions?, animated: Bool) {
        navigationController.popToRootViewController(animated: false)
        navigationController.pushViewController(makeMainController(), animated: animated)
        pushStoredScreen(from: options)
    }

    /// What `pushSettings` takes to come back to Settings > Appearance, where the theme and the
    /// language change and the interface is rebuilt.
    static var appearanceOptions: CoordinatorOptions {
        return [Options.ToShowKey: Options.Controller.appearance]
    }
}

private extension SettingsTabCoordinator {
    func makeMainController() -> SettingsMainController {
        let controller = SettingsMainController(theme: theme, submanagerObjects: submanagerObjects)
        controller.delegate = self
        return controller
    }

    func makeAdvancedController() -> SettingsAdvancedController {
        let controller = SettingsAdvancedController(theme: theme)
        controller.delegate = self
        controller.sessionCoordinator = sessionCoordinator
        return controller
    }

    func pushStoredScreen(from options: CoordinatorOptions?) {
        guard let toShow = options?[Options.ToShowKey] as? Options.Controller else {
            return
        }
        // The screen under it has not loaded its view yet (the stack is built again after a change of
        // theme or language), so its own "chevron only" is not set when the back button is made.
        navigationController.topViewController?.navigationItem.backButtonDisplayMode = .minimal
        switch toShow {
            case .advancedSettings:
                navigationController.pushViewController(makeAdvancedController(), animated: false)
            case .appearance:
                navigationController.pushViewController(AirAppearanceController(submanagerObjects: submanagerObjects), animated: false)
        }
    }
}

extension SettingsTabCoordinator: SettingsMainControllerDelegate {
    func settingsMainControllerShowAppearance(_ controller: SettingsMainController) {
        navigationController.pushViewController(AirAppearanceController(submanagerObjects: submanagerObjects), animated: true)
    }

    func settingsMainControllerShowNotifications(_ controller: SettingsMainController) {
        navigationController.pushViewController(AirNotificationsController(), animated: true)
    }

    // KHANDAQ (redesign stage 3): Settings > Privacy > Blocked.
    func settingsMainControllerShowPrivacy(_ controller: SettingsMainController) {
        let privacy = AirPrivacyController()
        privacy.onShowBlocked = { [weak self] in
            self?.navigationController.pushViewController(AirBlockedKeysController(), animated: true)
        }
        navigationController.pushViewController(privacy, animated: true)
    }

    func settingsMainControllerShowTransfer(_ controller: SettingsMainController) {
        let transfer = AirTransferController()
        transfer.onExport = { [weak self] screen in
            self?.exportProfile(from: screen)
        }
        transfer.onImport = { [weak self] screen in
            self?.importProfile(from: screen)
        }
        navigationController.pushViewController(transfer, animated: true)
    }

    func settingsMainControllerShowAdvancedSettings(_ controller: SettingsMainController) {
        navigationController.pushViewController(makeAdvancedController(), animated: true)
    }

    func settingsMainControllerShowFaqScreen(_ controller: SettingsMainController) {
        let controller = FAQController(theme: theme)

        navigationController.pushViewController(controller, animated: true)
    }

    func settingsMainControllerShowAboutScreen(_ controller: SettingsMainController) {
        let controller = SettingsAboutController(theme: theme)
        controller.delegate = self

        navigationController.pushViewController(controller, animated: true)
    }

    #if DEBUG
    func settingsMainControllerShowDebug(_ controller: SettingsMainController) {
        let debug = AirDebugController(theme: theme)
        debug.sessionCoordinator = sessionCoordinator
        navigationController.pushViewController(debug, animated: true)
    }
    #endif
}

private extension SettingsTabCoordinator {
    func exportProfile(from controller: UIViewController) {
        // The file of a profile without a password opens only with the key of this iPhone: elsewhere
        // it is of no use. Said first, with the way out (stage 4).
        guard !ProfileDeviceKey.activeProfileHasKey else {
            let sheet = AirConfirm.sheet(title: String(localized: "air_export_needs_password_title"),
                                         message: String(localized: "air_export_needs_password_message"),
                                         verb: String(localized: "air_password_set_action"),
                                         style: .default,
                                         source: controller.view) { [weak self] in
                self?.presentSetPassword()
            }
            navigationController.present(sheet, animated: true, completion: nil)
            return
        }
        do {
            let path = try toxManager.exportToxSaveFile()
            let name = UserDefaultsManager().lastActiveProfile ?? "profile"

            documentInteractionController = UIDocumentInteractionController(url: URL(fileURLWithPath: path))
            documentInteractionController!.delegate = self
            documentInteractionController!.name = "\(name).tox"
            documentInteractionController!.presentOptionsMenu(
                from: controller.view.frame,
                in: controller.view,
                animated: true
            )
        }
        catch let error as NSError {
            handleErrorWithType(.exportProfile, error: error)
        }
    }

    /// Asks first: importing logs out of this profile, which then waits for its password. From below,
    /// with the verb on the button (E21).
    func importProfile(from controller: UIViewController) {
        let message = ProfileDeviceKey.activeProfileHasKey ? "air_settings_import_message_device" : "air_settings_import_message"
        let sheet = AirConfirm.sheet(title: String(localized: "import_profile"),
                                     message: String(localized: message),
                                     verb: String(localized: "air_settings_import_action"),
                                     style: .default,
                                     source: controller.view) { [weak self] in
            self?.presentToxProfileImportPicker()
        }
        navigationController.present(sheet, animated: true, completion: nil)
    }
}

extension SettingsTabCoordinator: UIDocumentInteractionControllerDelegate {
    func documentInteractionControllerViewControllerForPreview(_ controller: UIDocumentInteractionController) -> UIViewController {
        return navigationController
    }

    func documentInteractionControllerViewForPreview(_ controller: UIDocumentInteractionController) -> UIView? {
        return navigationController.view
    }

    func documentInteractionControllerRectForPreview(_ controller: UIDocumentInteractionController) -> CGRect {
        return navigationController.view.frame
    }
}

extension SettingsTabCoordinator: UIDocumentPickerDelegate {
    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard let url = urls.first else {
            return
        }

        guard url.isToxURL() else {
            let alert = UIAlertController(
                title: nil,
                message: String(localized: "settings_import_profile_invalid_file"),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: String(localized: "error_ok_button"), style: .default, handler: nil))
            navigationController.present(alert, animated: true, completion: nil)
            return
        }

        sessionCoordinator?.logout(importToxProfileFromURL: url)
    }
}

extension SettingsTabCoordinator: SettingsAboutControllerDelegate {
    func settingsAboutControllerShowAcknowledgements(_ controller: SettingsAboutController) {
        let controller = TextViewController(
                resourceName: "antidote-acknowledgements",
                backgroundColor: theme.colorForType(.NormalBackground),
                titleColor: theme.colorForType(.NormalText),
                textColor: theme.colorForType(.NormalText))
        controller.title = String(localized: "settings_acknowledgements")

        navigationController.pushViewController(controller, animated: true)
    }
}

extension SettingsTabCoordinator: SettingsAdvancedControllerDelegate {
    func settingsAdvancedControllerToxOptionsChanged(_ controller: SettingsAdvancedController) {
        delegate?.settingsTabCoordinatorRecreateCoordinatorsStack(self, options: [
            Options.ToShowKey: Options.Controller.advancedSettings
        ])
    }
}

extension SettingsTabCoordinator: ChangePasswordControllerDelegate {
    func changePasswordControllerDidFinishPresenting(_ controller: ChangePasswordController) {
        navigationController.dismiss(animated: true, completion: nil)
    }
}

private extension SettingsTabCoordinator {
    func presentSetPassword() {
        let controller = ChangePasswordController(theme: theme, toxManager: toxManager)
        controller.delegate = self
        navigationController.present(UINavigationController(rootViewController: controller), animated: true, completion: nil)
    }

    func presentToxProfileImportPicker() {
        let picker = UIDocumentPickerViewController(documentTypes: [String(kUTTypeData)], in: .import)
        picker.delegate = self
        navigationController.present(picker, animated: true, completion: nil)
    }
}
