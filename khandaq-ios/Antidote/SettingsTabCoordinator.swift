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

    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects, toxManager: OCTManager) {
        self.submanagerObjects = submanagerObjects
        self.toxManager = toxManager
        super.init(theme: theme)
    }

    override func startWithOptions(_ options: CoordinatorOptions?) {
        let controller = SettingsMainController(theme: theme, submanagerObjects: submanagerObjects)
        controller.delegate = self

        installRootViewController(controller)

        if let toShow = options?[Options.ToShowKey] as? Options.Controller {
            switch toShow {
                case .advancedSettings:
                    let advanced = SettingsAdvancedController(theme: theme)
                    advanced.delegate = self
                    advanced.sessionCoordinator = sessionCoordinator

                    navigationController.pushViewController(advanced, animated: false)
            }
        }
    }
}

extension SettingsTabCoordinator: SettingsMainControllerDelegate {
    func settingsMainControllerShowAboutScreen(_ controller: SettingsMainController) {
        let controller = SettingsAboutController(theme: theme)
        controller.delegate = self

        navigationController.pushViewController(controller, animated: true)
    }

    func settingsMainControllerShowFaqScreen(_ controller: SettingsMainController) {
        let controller = FAQController(theme: theme)

        navigationController.pushViewController(controller, animated: true)
    }

    func settingsMainControllerChangeAutodownloadImages(_ controller: SettingsMainController) {
        let controller = ChangeAutodownloadImagesController(theme: theme)
        controller.delegate = self

        navigationController.pushViewController(controller, animated: true)
    }

    func settingsMainControllerShowAdvancedSettings(_ controller: SettingsMainController) {
        let controller = SettingsAdvancedController(theme: theme)
        controller.delegate = self
        controller.sessionCoordinator = sessionCoordinator

        navigationController.pushViewController(controller, animated: true)
    }

    func settingsMainControllerExportProfile(_ controller: SettingsMainController) {
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

    func settingsMainControllerImportProfile(_ controller: SettingsMainController) {
        let alert = UIAlertController(
            title: String(localized: "import_profile"),
            message: String(localized: "settings_profile_backup_description"),
            preferredStyle: .alert
        )
        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "import_profile"), style: .default) { [weak self] _ in
            self?.presentToxProfileImportPicker(from: controller)
        })
        navigationController.present(alert, animated: true, completion: nil)
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

extension SettingsTabCoordinator: ChangeAutodownloadImagesControllerDelegate {
    func changeAutodownloadImagesControllerDidChange(_ controller: ChangeAutodownloadImagesController) {
        navigationController.popViewController(animated: true)
    }
}

extension SettingsTabCoordinator: SettingsAdvancedControllerDelegate {
    func settingsAdvancedControllerToxOptionsChanged(_ controller: SettingsAdvancedController) {
        delegate?.settingsTabCoordinatorRecreateCoordinatorsStack(self, options: [
            Options.ToShowKey: Options.Controller.advancedSettings
        ])
    }
}

private extension SettingsTabCoordinator {
    func presentToxProfileImportPicker(from controller: SettingsMainController) {
        let picker = UIDocumentPickerViewController(documentTypes: [String(kUTTypeData)], in: .import)
        picker.delegate = self
        navigationController.present(picker, animated: true, completion: nil)
    }
}
