// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

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
    fileprivate let profileTheme: Theme
    fileprivate let avatarManager: AvatarManager

    fileprivate let avatarModel = StaticTableAvatarCellModel()
    fileprivate let userNameModel = StaticTableDefaultCellModel()
    fileprivate let statusMessageModel = StaticTableDefaultCellModel()
    // fileprivate let userStatusModel = StaticTableDefaultCellModel()
    fileprivate let toxIdModel = StaticTableDefaultCellModel()
    fileprivate let copyMyIdModel = StaticTableButtonCellModel()
    fileprivate let showQrModel = StaticTableButtonCellModel()
    fileprivate let capabilitiesModel = StaticTableDefaultCellModel()
    fileprivate let networkConnectionsModel = StaticTableDefaultCellModel()
    fileprivate let profileDetailsModel = StaticTableDefaultCellModel()
    fileprivate let logoutModel = StaticTableButtonCellModel()

    init(theme: Theme, submanagerUser: OCTSubmanagerUser) {
        self.submanagerUser = submanagerUser
        self.profileTheme = theme

        avatarManager = AvatarManager(theme: theme)

        super.init(theme: theme, style: StaticTableController.insetGroupedStyle, model: [
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
            // KHANDAQ (Figma): each MyID action is its own rounded pill, separate from the ID card.
            [
                copyMyIdModel,
            ],
            [
                showQrModel,
            ],
            [
                capabilitiesModel,
            ],
            [
                networkConnectionsModel,
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

        // KHANDAQ (#8): the picker runs with allowsEditing, so prefer the user's crop/zoom result.
        guard let image = (info[UIImagePickerControllerEditedImage] ?? info[UIImagePickerControllerOriginalImage]) as? UIImage else {
            return
        }

        applyAvatarImage(image)
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        dismiss(animated: true, completion: nil)
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
        avatarModel.showCameraBadge = true

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
        // Tapping the MyID row still copies it (with a toast); long-press works via canCopyValue.
        toxIdModel.userInteractionEnabled = true
        toxIdModel.canCopyValue = true
        toxIdModel.didSelectHandler = { [weak self] _ in
            guard let self = self else { return }
            UIPasteboard.general.string = self.submanagerUser.userAddress
            self.showCopiedHUD(String(localized: "group_member_action_copy_done"))
        }

        // KHANDAQ (Figma): the MyID section shows two pill buttons with icons — «Копировать MyID» and
        // «Показать QR-код» (the copy button, dropped in #117, is back to match the design).
        copyMyIdModel.title = String(localized: "contacts_copy_myid")
        copyMyIdModel.iconName = "doc.on.doc"
        copyMyIdModel.didSelectHandler = { [weak self] _ in
            guard let self = self else { return }
            UIPasteboard.general.string = self.submanagerUser.userAddress
            self.showCopiedHUD(String(localized: "group_member_action_copy_done"))
        }

        showQrModel.title = String(localized: "show_qr_code")
        showQrModel.iconName = "qrcode"
        showQrModel.didSelectHandler = { [weak self] _ in
            self?.showToxIdQR()
        }
        // for debugging print own ToxID ----------------
        // print("TOXID: \(submanagerUser.userAddress)")
        // for debugging print own ToxID ----------------

        capabilitiesModel.title = "Tox Capabilities"
        capabilitiesModel.value = capabilitiesToString(submanagerUser.capabilities as NSNumber)
        capabilitiesModel.userInteractionEnabled = false

        networkConnectionsModel.title = String(localized: "network_connections_title")
        networkConnectionsModel.value = String(localized: "network_connections_summary")
        networkConnectionsModel.rightImageType = .arrow
        networkConnectionsModel.didSelectHandler = showNetworkConnections

        profileDetailsModel.value = String(localized: "profile_details")
        profileDetailsModel.didSelectHandler = showProfileDetails
        profileDetailsModel.rightImageType = .arrow

        logoutModel.title = String(localized: "logout_button")
        logoutModel.destructive = true
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
                    // KHANDAQ (#8): let the user crop/zoom the shot before it becomes the avatar.
                    controller.allowsEditing = true

                    if UIImagePickerController.isCameraDeviceAvailable(.front) {
                        controller.cameraDevice = .front
                    }

                    self.present(controller, animated: true, completion: nil)
                }
            })
        }

        alert.addAction(UIAlertAction(title: String(localized: "photo_from_photo_library"), style: .default) { [unowned self] _ -> Void in
            // KHANDAQ (#8): PHPicker has no crop step, so the avatar was auto-center-cropped with no
            // way to zoom/reposition. UIImagePickerController with allowsEditing gives the native
            // move-and-scale square crop (and needs no photo permission since iOS 11).
            if UIImagePickerController.isSourceTypeAvailable(.photoLibrary) {
                let controller = UIImagePickerController()
                controller.sourceType = .photoLibrary
                controller.delegate = self
                controller.allowsEditing = true
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

    func showNetworkConnections(_: StaticTableBaseCell) {
        let controller = NetworkDiagnosticsDetailController(theme: profileTheme)
        navigationController?.pushViewController(controller, animated: true)
    }
}
