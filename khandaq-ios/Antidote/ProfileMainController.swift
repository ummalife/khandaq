// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import PhotosUI
import UIKit

protocol ProfileMainControllerDelegate: class {
    func profileMainControllerLogout(_ controller: ProfileMainController)
    func profileMainControllerChangeUserName(_ controller: ProfileMainController)
    func profileMainControllerChangeUserStatus(_ controller: ProfileMainController)
    func profileMainControllerChangeStatusMessage(_ controller: ProfileMainController)
    func profileMainController(_ controller: ProfileMainController, showQRCodeWithText text: String)
    func profileMainControllerShowProfileDetails(_ controller: ProfileMainController)
    func profileMainControllerDidChangeAvatar(_ controller: ProfileMainController)
}

class ProfileMainController: StaticTableController {
    weak var delegate: ProfileMainControllerDelegate?

    fileprivate weak var submanagerUser: OCTSubmanagerUser!
    fileprivate let avatarManager: AvatarManager

    fileprivate let avatarModel = StaticTableAvatarCellModel()
    fileprivate let userNameModel = StaticTableDefaultCellModel()
    fileprivate let statusMessageModel = StaticTableDefaultCellModel()
    // fileprivate let userStatusModel = StaticTableDefaultCellModel()
    fileprivate let toxIdModel = StaticTableDefaultCellModel()
    fileprivate let capabilitiesModel = StaticTableDefaultCellModel()
    fileprivate let profileDetailsModel = StaticTableDefaultCellModel()
    fileprivate let logoutModel = StaticTableButtonCellModel()

    init(theme: Theme, submanagerUser: OCTSubmanagerUser) {
        self.submanagerUser = submanagerUser

        avatarManager = AvatarManager(theme: theme)

        super.init(theme: theme, style: .plain, model: [
            [
                avatarModel,
            ],
            [
                userNameModel,
                statusMessageModel,
            ],
            //[
            //    userStatusModel,
            //],
            [
                toxIdModel,
            ],
            [
                capabilitiesModel,
            ],
            [
                profileDetailsModel,
            ],
            [
                logoutModel,
            ],
        ])

        updateModels()

        title = String(localized: "profile_title")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        updateModels()
        reloadTableView()
    }
}

extension ProfileMainController: UIImagePickerControllerDelegate {
    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [String : Any]) {
        dismiss(animated: true, completion: nil)

        guard let image = info[UIImagePickerControllerOriginalImage] as? UIImage else {
            return
        }

        applyAvatarImage(image)
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        dismiss(animated: true, completion: nil)
    }
}

@available(iOS 14.0, *)
extension ProfileMainController: PHPickerViewControllerDelegate {
    func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
        dismiss(animated: true, completion: nil)

        guard let provider = results.first?.itemProvider, provider.canLoadObject(ofClass: UIImage.self) else {
            return
        }

        provider.loadObject(ofClass: UIImage.self) { [weak self] object, _ in
            guard let image = object as? UIImage else {
                return
            }

            DispatchQueue.main.async {
                self?.applyAvatarImage(image)
            }
        }
    }
}

extension ProfileMainController: UINavigationControllerDelegate {}

private extension ProfileMainController {
    struct PNGFromDataError: Error {}

    func updateModels() {
        if let avatarData = submanagerUser.userAvatar() {
            avatarModel.avatar = UIImage(data: avatarData)
        }
        else {
            avatarModel.avatar = avatarManager.avatarFromString(
                    submanagerUser.userName() ?? "?",
                    diameter: StaticTableAvatarCellModel.Constants.AvatarImageSize)
        }
        avatarModel.didTapOnAvatar = performAvatarAction

        userNameModel.title = String(localized: "name")
        userNameModel.value = submanagerUser.userName()
        userNameModel.rightImageType = .arrow
        userNameModel.didSelectHandler = changeUserName

        // Hardcoding any connected status to show only online/away/busy statuses here.
        let userStatus = UserStatus(connectionStatus: OCTToxConnectionStatus.TCP, userStatus: submanagerUser.userStatus)

        // userStatusModel.userStatus = userStatus
        // userStatusModel.value = userStatus.toString()
        // userStatusModel.rightImageType = .arrow
        // userStatusModel.didSelectHandler = changeUserStatus

        statusMessageModel.title = String(localized: "status_message")
        statusMessageModel.value = submanagerUser.userStatusMessage()
        statusMessageModel.rightImageType = .arrow
        statusMessageModel.didSelectHandler = changeStatusMessage

        toxIdModel.title = String(localized: "my_tox_id")
        toxIdModel.value = sanitizeAddressInput(submanagerUser.userAddress)
        toxIdModel.rightButton = String(localized: "show_qr")
        toxIdModel.rightButtonHandler = showToxIdQR
        toxIdModel.userInteractionEnabled = false
        toxIdModel.canCopyValue = true
        // for debugging print own ToxID ----------------
        // print("TOXID: \(submanagerUser.userAddress)")
        // for debugging print own ToxID ----------------

        capabilitiesModel.title = "Tox Capabilities"
        capabilitiesModel.value = capabilitiesToString(submanagerUser.capabilities as NSNumber)
        capabilitiesModel.userInteractionEnabled = false

        profileDetailsModel.value = String(localized: "profile_details")
        profileDetailsModel.didSelectHandler = showProfileDetails
        profileDetailsModel.rightImageType = .arrow

        logoutModel.title = String(localized: "logout_button")
        logoutModel.didSelectHandler = logout
    }

    func capabilitiesToString(_ cap: NSNumber) -> String {
        var ret: String = "BASIC"
        if ((UInt(cap) & 1) > 0) {
            ret = ret + " CAPABILITIES"
        }
        if ((UInt(cap) & 2) > 0) {
            ret = ret + " MSGV2"
        }
        if ((UInt(cap) & 4) > 0) {
            ret = ret + " H264"
        }
        if ((UInt(cap) & 8) > 0) {
            ret = ret + " MSGV3"
        }
        if ((UInt(cap) & 16) > 0) {
            ret = ret + " FTV2"
        }
        return ret;
    }

    func logout(_: StaticTableBaseCell) {
        delegate?.profileMainControllerLogout(self)
    }

    func performAvatarAction(_ cell: StaticTableAvatarCell) {
        let alert = UIAlertController(title: nil, message: nil, preferredStyle: .actionSheet)
        alert.popoverPresentationController?.sourceView = cell
        alert.popoverPresentationController?.sourceRect = CGRect(x: cell.frame.size.width / 2, y: cell.frame.size.height / 2, width: 1.0, height: 1.0)

        if UIImagePickerController.isSourceTypeAvailable(.camera) {
            alert.addAction(UIAlertAction(title: String(localized: "photo_from_camera"), style: .default) { [unowned self] _ -> Void in
                MediaPermission.requestCameraAccess(from: self) { granted in
                    guard granted else {
                        return
                    }

                    let controller = UIImagePickerController()
                    controller.sourceType = .camera
                    controller.delegate = self

                    if UIImagePickerController.isCameraDeviceAvailable(.front) {
                        controller.cameraDevice = .front
                    }

                    self.present(controller, animated: true, completion: nil)
                }
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "photo_from_photo_library"), style: .default) { [unowned self] _ -> Void in
            if #available(iOS 14.0, *) {
                var configuration = PHPickerConfiguration()
                configuration.filter = .images
                configuration.selectionLimit = 1

                let controller = PHPickerViewController(configuration: configuration)
                controller.delegate = self
                self.present(controller, animated: true, completion: nil)
            } else if UIImagePickerController.isSourceTypeAvailable(.photoLibrary) {
                let controller = UIImagePickerController()
                controller.sourceType = .photoLibrary
                controller.delegate = self
                self.present(controller, animated: true, completion: nil)
            }
        })

        if submanagerUser.userAvatar() != nil {
            alert.addAction(UIAlertAction(title: String(localized: "alert_delete"), style: .destructive) { [unowned self] _ -> Void in
                self.removeAvatar()
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))

        present(alert, animated: true, completion: nil)
    }

    func removeAvatar() {
        do {
            try submanagerUser.setUserAvatar(nil)
            updateModels()
            reloadTableView()

            delegate?.profileMainControllerDidChangeAvatar(self)
        }
        catch let error as NSError {
            handleErrorWithType(.changeAvatar, error: error)
        }
    }

    func applyAvatarImage(_ image: UIImage) {
        var croppedImage = image

        if croppedImage.size.width != croppedImage.size.height {
            let side = min(croppedImage.size.width, croppedImage.size.height)
            let x = (croppedImage.size.width - side) / 2
            let y = (croppedImage.size.height - side) / 2
            let rect = CGRect(x: x, y: y, width: side, height: side)

            croppedImage = croppedImage.cropWithRect(rect)
        }

        let data: Data

        do {
            data = try pngDataFromImage(croppedImage)
        }
        catch {
            handleErrorWithType(.convertImageToPNG, error: nil)
            return
        }

        do {
            try submanagerUser.setUserAvatar(data)
            updateModels()
            reloadTableView()

            delegate?.profileMainControllerDidChangeAvatar(self)
        }
        catch let error as NSError {
            handleErrorWithType(.changeAvatar, error: error)
        }
    }

    func pngDataFromImage(_ image: UIImage) throws -> Data {
        var imageSize = image.size

        // Maximum png size will be (4 * width * height)
        // * 1.5 to get as big avatar size as possible
        while OCTToxFileSize(4 * imageSize.width * imageSize.height) > OCTToxFileSize(1.5 * Double(kOCTManagerMaxAvatarSize)) {
            imageSize.width *= 0.9
            imageSize.height *= 0.9
        }

        imageSize.width = ceil(imageSize.width)
        imageSize.height = ceil(imageSize.height)

        var data: Data
        var tempImage = image

        repeat {
            UIGraphicsBeginImageContext(imageSize)
            tempImage.draw(in: CGRect(origin: CGPoint.zero, size: imageSize))
            tempImage = UIGraphicsGetImageFromCurrentImageContext()!
            UIGraphicsEndImageContext()

            guard let theData = UIImagePNGRepresentation(tempImage) else {
                throw PNGFromDataError()
            }
            data = theData

            imageSize.width *= 0.9
            imageSize.height *= 0.9
        } while (OCTToxFileSize(data.count) > kOCTManagerMaxAvatarSize)

        return data
    }

    func changeUserName(_: StaticTableBaseCell) {
        delegate?.profileMainControllerChangeUserName(self)
    }

    func changeUserStatus(_: StaticTableBaseCell) {
        delegate?.profileMainControllerChangeUserStatus(self)
    }

    func changeStatusMessage(_: StaticTableBaseCell) {
        delegate?.profileMainControllerChangeStatusMessage(self)
    }

    func showToxIdQR() {
        delegate?.profileMainController(self, showQRCodeWithText: submanagerUser.userAddress)
    }

    func showProfileDetails(_: StaticTableBaseCell) {
        delegate?.profileMainControllerShowProfileDetails(self)
    }
}
