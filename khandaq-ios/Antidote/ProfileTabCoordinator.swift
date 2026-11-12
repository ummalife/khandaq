// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import AudioToolbox

protocol ProfileTabCoordinatorDelegate: class {
    func profileTabCoordinatorDelegateLogout(_ coordinator: ProfileTabCoordinator)
    func profileTabCoordinatorDelegateDeleteProfile(_ coordinator: ProfileTabCoordinator)
    func profileTabCoordinatorDelegateDidChangeUserStatus(_ coordinator: ProfileTabCoordinator)
    func profileTabCoordinatorDelegateDidChangeAvatar(_ coordinator: ProfileTabCoordinator)
    func profileTabCoordinatorDelegateDidChangeUserName(_ coordinator: ProfileTabCoordinator)
}

class ProfileTabCoordinator: ActiveSessionNavigationCoordinator, UIAdaptivePresentationControllerDelegate {
    weak var delegate: ProfileTabCoordinatorDelegate?

    fileprivate weak var toxManager: OCTManager!

    /// `navigationController`: on the phone the stack is shared with the Air screen at its root.
    init(theme: Theme, toxManager: OCTManager, navigationController: UINavigationController? = nil) {
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
    }

    /// The phone has no tabs: "You" opens above the Air screen in the shared stack.
    func pushProfile(animated: Bool) {
        navigationController.popToRootViewController(animated: false)
        navigationController.pushViewController(makeMainController(), animated: animated)
    }

    private func makeMainController() -> ProfileMainController {
        let controller = ProfileMainController(theme: theme, submanagerUser: toxManager.user)
        controller.delegate = self
        return controller
    }
}

extension OCTToxErrorSetInfoCode: Error {

}

extension ProfileTabCoordinator: ProfileMainControllerDelegate {
    func profileMainControllerLogout(_ controller: ProfileMainController) {
        delegate?.profileTabCoordinatorDelegateLogout(self)
    }

    func profileMainControllerDeleteProfile(_ controller: ProfileMainController) {
        delegate?.profileTabCoordinatorDelegateDeleteProfile(self)
    }

    func profileMainControllerDidChangeUserName(_ controller: ProfileMainController) {
        delegate?.profileTabCoordinatorDelegateDidChangeUserName(self)
    }

    func profileMainControllerShowProfileDetails(_ controller: ProfileMainController) {
        let controller = ProfileDetailsController(theme: theme, toxManager: toxManager)
        controller.delegate = self
        navigationController.pushViewController(controller, animated: true)
    }

    func profileMainControllerDidChangeAvatar(_ controller: ProfileMainController) {
        delegate?.profileTabCoordinatorDelegateDidChangeAvatar(self)
    }
}

extension ProfileTabCoordinator: ChangeUserStatusControllerDelegate {
    func changeUserStatusController(_ controller: ChangeUserStatusController, selectedStatus: OCTToxUserStatus) {
        toxManager.user.userStatus = selectedStatus
        navigationController.popViewController(animated: true)

        delegate?.profileTabCoordinatorDelegateDidChangeUserStatus(self)
    }
}

extension ProfileTabCoordinator: QRViewerControllerDelegate {
    func qrViewerControllerDidFinishPresenting() {
        navigationController.dismiss(animated: true, completion: nil)
    }
}

extension ProfileTabCoordinator: ChangePasswordControllerDelegate {
    func changePasswordControllerDidFinishPresenting(_ controller: ChangePasswordController) {
        navigationController.dismiss(animated: true, completion: nil)
    }
}

extension ProfileTabCoordinator: ProfileDetailsControllerDelegate {
    func profileDetailsControllerSetPin(_ controller: ProfileDetailsController) {
        let controller = EnterPinController(theme: theme, state: .setPin)
        controller.topText = String(localized: "pin_set")
        // What a forgotten PIN means, said before it is set (E20).
        controller.descriptionText = String(localized: "air_pin_set_note")
        controller.delegate = self
        controller.closeHandler = makePinSheetCloseHandler()

        let toPresent = PortraitNavigationController(rootViewController: controller)
        toPresent.isNavigationBarHidden = true
        toPresent.presentationController?.delegate = self
        navigationController.present(toPresent, animated: true, completion: nil)
    }

    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        NotificationCenter.default.post(name: .khandaqProfileSettingsDidChange, object: nil)
    }

    func profileDetailsControllerChangeLockTimeout(_ controller: ProfileDetailsController) {
        let controller = ChangePinTimeoutController(theme: theme, submanagerObjects: toxManager.objects)
        controller.delegate = self
        navigationController.pushViewController(controller, animated: true)
    }

    func profileDetailsControllerChangePassword(_ controller: ProfileDetailsController) {
        let controller = ChangePasswordController(theme: theme, toxManager: toxManager)
        controller.delegate = self

        let toPresent = UINavigationController(rootViewController: controller)
        navigationController.present(toPresent, animated: true, completion: nil)
    }

}

extension ProfileTabCoordinator: EnterPinControllerDelegate {
    func enterPinController(_ controller: EnterPinController, successWithPin pin: String) {
        switch controller.state {
            case .validatePin:
                let settings = toxManager.objects.getProfileSettings()
                settings.unlockPinCode = pin
                toxManager.objects.saveProfileSettings(settings)

                navigationController.dismiss(animated: true) {
                    NotificationCenter.default.post(name: .khandaqProfileSettingsDidChange, object: nil)
                }
            case .setPin:
                guard let presentedNavigation = controller.navigationController else {
                    fatalError("wrong state")
                }

                let validate = EnterPinController(theme: theme, state: .validatePin(validPin: pin))
                validate.topText = String(localized: "pin_confirm")
                validate.delegate = self
                validate.closeHandler = makePinSheetCloseHandler()

                presentedNavigation.viewControllers = [validate]
        }
    }

    func enterPinControllerFailure(_ controller: EnterPinController) {
        guard let presentedNavigation = controller.navigationController else {
            fatalError("wrong state")
        }

        AudioServicesPlayAlertSound(SystemSoundID(kSystemSoundID_Vibrate))

        let setPin = EnterPinController(theme: theme, state: .setPin)
        setPin.topText = String(localized: "pin_do_not_match")
        setPin.descriptionText = String(localized: "air_pin_set_note")
        setPin.delegate = self
        setPin.closeHandler = makePinSheetCloseHandler()

        presentedNavigation.viewControllers = [setPin]
    }

    // KHANDAQ (Figma): X button on the set-PIN sheet; mirrors the interactive swipe-dismiss path
    // (presentationControllerDidDismiss) which also posts the settings-changed notification.
    private func makePinSheetCloseHandler() -> () -> Void {
        return { [weak self] in
            self?.navigationController.dismiss(animated: true) {
                NotificationCenter.default.post(name: .khandaqProfileSettingsDidChange, object: nil)
            }
        }
    }
}

extension ProfileTabCoordinator: ChangePinTimeoutControllerDelegate {
    func changePinTimeoutControllerDone(_ controller: ChangePinTimeoutController) {
        navigationController.popViewController(animated: true)
    }
}
