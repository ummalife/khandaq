// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit
import CoreImage
import MobileCoreServices

private struct Constants {
    static let TextViewTopOffset = 5.0
    static let TextViewXOffset = 5.0
}

protocol AddFriendControllerDelegate: class {
    func addFriendControllerScanQRCode(
            _ controller: AddFriendController,
            validateCodeHandler: @escaping (String) -> Bool,
            didScanHander: @escaping (String) -> Void)

    func addFriendControllerDidFinish(_ controller: AddFriendController)
}

class AddFriendController: UIViewController {
    weak var delegate: AddFriendControllerDelegate?

    fileprivate let theme: Theme
    fileprivate weak var submanagerFriends: OCTSubmanagerFriends!
    fileprivate weak var submanagerObjects: OCTSubmanagerObjects!
    fileprivate let ownToxAddress: String

    fileprivate var myIdLabel: UILabel!
    fileprivate var idTextField: UITextField!
    fileprivate var orLabel: UILabel!

    fileprivate var qrCodeButton: UIButton!
    fileprivate var qrGalleryButton: UIButton!

    fileprivate var cachedMessage: String?

    init(theme: Theme, submanagerFriends: OCTSubmanagerFriends, submanagerObjects: OCTSubmanagerObjects, ownToxAddress: String) {
        self.theme = theme
        self.submanagerFriends = submanagerFriends
        self.submanagerObjects = submanagerObjects
        self.ownToxAddress = ownToxAddress.uppercased()

        super.init(nibName: nil, bundle: nil)

        addNavigationButtons()

        edgesForExtendedLayout = UIRectEdge()
        title = String(localized: "add_contact_title")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        createViews()
        installConstraints()

        updateSendButton()
    }
}

extension AddFriendController {
    @objc func qrCodeButtonPressed() {
        delegate?.addFriendControllerScanQRCode(self, validateCodeHandler: {
            return isAddressString($0)

        }, didScanHander: { [unowned self] in
            let value = self.normalizedIdFieldText(from: $0)
            self.applyIdFieldText(value, cursorOffset: (value as NSString).length)
        })
    }

    @objc func qrGalleryButtonPressed() {
        guard UIImagePickerController.isSourceTypeAvailable(.photoLibrary) else {
            return
        }
        let picker = UIImagePickerController()
        picker.sourceType = .photoLibrary
        picker.delegate = self
        present(picker, animated: true, completion: nil)
    }

    @objc func sendButtonPressed() {
        idTextField.resignFirstResponder()
        sanitizeIdFieldIfNeeded()

        guard let address = validatedAddressFromIdField() else {
            showInvalidIdFormatError()
            return
        }

        if isOwnToxAddress(address) {
            UIAlertController.showWithTitle(
                String(localized: "error_title"),
                message: String(localized: "add_contact_own_id_error"),
                retryBlock: nil
            )
            return
        }

        if let conflictMessage = existingContactConflictMessage(for: address) {
            UIAlertController.showWithTitle(
                String(localized: "error_title"),
                message: conflictMessage,
                retryBlock: nil
            )
            return
        }

        let hex = sanitizeAddressInput(idTextField.text ?? "")
        if hex.count == Int(kOCTToxPublicKeyLength) {
            let alert = UIAlertController(
                title: String(localized: "error_title"),
                message: String(localized: "add_contact_public_key_only_hint"),
                preferredStyle: .alert
            )
            alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
            alert.addAction(UIAlertAction(title: String(localized: "add_contact_send"), style: .default) { [weak self] _ in
                self?.presentMessageAlert(for: address)
            })
            present(alert, animated: true, completion: nil)
            return
        }

        presentMessageAlert(for: address)
    }

    func presentMessageAlert(for address: String) {
        let alert = UIAlertController(
            title: String(localized: "add_contact_default_message_title"),
            message: nil,
            preferredStyle: .alert
        )

        alert.addTextField { [weak self] textField in
            textField.text = self?.cachedMessage
            textField.placeholder = String(localized: "add_contact_default_message_text")
            textField.autocorrectionType = .no
            textField.spellCheckingType = .no
        }

        alert.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        alert.addAction(UIAlertAction(title: String(localized: "add_contact_send"), style: .default) { [weak self] _ in
            guard let self = self else {
                return
            }

            let messageText = alert.textFields?.first?.text ?? ""
            self.cachedMessage = messageText
            let message = messageText.isEmpty ? KhandaqBranding.defaultStatusMessage : messageText
            self.sendFriendRequest(to: address, message: message)
        })

        present(alert, animated: true, completion: nil)
    }

    func sendFriendRequest(to address: String, message: String) {
        guard let normalizedAddress = normalizedToxAddress(from: address) else {
            showInvalidIdFormatError()
            return
        }

        if isOwnToxAddress(normalizedAddress) {
            UIAlertController.showWithTitle(
                String(localized: "error_title"),
                message: String(localized: "add_contact_own_id_error"),
                retryBlock: nil
            )
            return
        }

        if let conflictMessage = existingContactConflictMessage(for: normalizedAddress) {
            UIAlertController.showWithTitle(
                String(localized: "error_title"),
                message: conflictMessage,
                retryBlock: nil
            )
            return
        }

        do {
            try submanagerFriends.sendFriendRequest(toAddress: normalizedAddress, message: message)
        }
        catch let error as NSError {
            handleErrorWithType(.toxAddFriend, error: error)
            return
        }

        let toast = UIAlertController(
            title: nil,
            message: String(localized: "group_member_action_add_friend_sent"),
            preferredStyle: .alert
        )
        toast.addAction(UIAlertAction(title: String(localized: "alert_ok"), style: .default) { [weak self] _ in
            guard let self = self else {
                return
            }
            self.delegate?.addFriendControllerDidFinish(self)
        })

        if presentedViewController != nil {
            dismiss(animated: true) { [weak self] in
                self?.present(toast, animated: true, completion: nil)
            }
        }
        else {
            present(toast, animated: true, completion: nil)
        }
    }
}

extension AddFriendController: UITextFieldDelegate {
    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
        if textField !== idTextField {
            return true
        }

        if string == "\n" {
            updateSendButton()
            textField.resignFirstResponder()
            return false
        }

        let current = textField.text ?? ""
        let nsCurrent = current as NSString
        let maxHex = Int(kOCTToxAddressLength)

        guard range.location >= 0,
              range.length >= 0,
              range.location <= nsCurrent.length,
              range.location + range.length <= nsCurrent.length else {
            return false
        }

        let merged = nsCurrent.replacingCharacters(in: range, with: string) as String
        let newText: String
        let cursorOffset: Int

        if string.count > 1 {
            // Paste / autofill
            newText = normalizedIdFieldText(from: merged)
            cursorOffset = (newText as NSString).length
        } else if string.count == 1 {
            let ch = string.uppercased()
            if ch.rangeOfCharacter(from: CharacterSet(charactersIn: "0123456789ABCDEF")) == nil {
                return false
            }
            newText = String(sanitizeAddressInput(merged).prefix(maxHex))
            cursorOffset = min(range.location + 1, (newText as NSString).length)
        } else {
            // Backspace / delete — apply as-is; validation only on Send.
            newText = merged
            cursorOffset = range.location
        }

        applyIdFieldText(newText, cursorOffset: cursorOffset)
        return false
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        textField.resignFirstResponder()
        return true
    }
}

private extension AddFriendController {
    func addNavigationButtons() {
        let send = UIBarButtonItem(
                title: String(localized: "add_contact_send"),
                style: .done,
                target: self,
                action: #selector(AddFriendController.sendButtonPressed))
        // KHANDAQ design (Figma): the action button sits in a grey pill.
        let capsule = ThemeChrome.navCapsuleBackgroundImage(theme: theme)
        send.setBackgroundImage(capsule, for: .normal, barMetrics: .default)
        send.setBackgroundImage(capsule, for: .disabled, barMetrics: .default)
        navigationItem.rightBarButtonItem = send
    }

    func createViews() {
        // KHANDAQ design (Figma): a "MyID пользователя" label above the field.
        myIdLabel = UILabel()
        myIdLabel.text = String(localized: "add_contact_myid_label")
        myIdLabel.font = UIFont.systemFont(ofSize: 13.0)
        myIdLabel.textColor = theme.colorForType(.FriendCellStatus)
        view.addSubview(myIdLabel)

        // UITextField avoids UITextView+Placeholder crashes on iOS 17/26 when deleting pasted Tox IDs.
        idTextField = UITextField()
        idTextField.placeholder = String(localized: "add_contact_tox_id_placeholder")
        idTextField.delegate = self
        if #available(iOS 13.0, *) {
            idTextField.font = UIFont.monospacedSystemFont(ofSize: 14, weight: .regular)
        } else {
            idTextField.font = UIFont(name: "Menlo-Regular", size: 14) ?? UIFont.systemFont(ofSize: 14)
        }
        idTextField.textColor = theme.colorForType(.NormalText)
        // KHANDAQ design (Figma): filled grey rounded field.
        idTextField.backgroundColor = theme.colorForType(.ChatInputBackground)
        idTextField.returnKeyType = .done
        idTextField.adjustsFontSizeToFitWidth = false
        idTextField.autocapitalizationType = .allCharacters
        idTextField.autocorrectionType = .no
        idTextField.spellCheckingType = .no
        idTextField.keyboardType = .asciiCapable
        idTextField.clearButtonMode = .whileEditing
        idTextField.layer.cornerRadius = 12.0
        idTextField.layer.borderWidth = 0.0
        idTextField.layer.masksToBounds = true
        idTextField.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 8, height: 1))
        idTextField.leftViewMode = .always
        idTextField.addTarget(self, action: #selector(AddFriendController.idTextFieldEditingChanged), for: .editingChanged)
        view.addSubview(idTextField)

        // KHANDAQ design (Figma): an "или" divider, then a filled grey "Добавить по QR-коду" row with
        // a QR glyph. The gallery-scan row is kept (extra feature) in the same style.
        orLabel = UILabel()
        orLabel.text = String(localized: "add_contact_or_label")
        orLabel.font = UIFont.systemFont(ofSize: 13.0)
        orLabel.textColor = theme.colorForType(.FriendCellStatus)
        orLabel.textAlignment = .center
        view.addSubview(orLabel)

        qrCodeButton = makeQrRow(title: String(localized: "add_contact_use_qr"),
                                 systemImage: "qrcode",
                                 action: #selector(AddFriendController.qrCodeButtonPressed))
        qrGalleryButton = makeQrRow(title: String(localized: "add_contact_use_qr_gallery"),
                                    systemImage: "photo.on.rectangle",
                                    action: #selector(AddFriendController.qrGalleryButtonPressed))
    }

    func makeQrRow(title: String, systemImage: String, action: Selector) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle("  " + title, for: UIControlState())
        button.titleLabel?.font = UIFont.systemFont(ofSize: 16.0, weight: .medium)
        button.setTitleColor(theme.colorForType(.NormalText), for: UIControlState())
        button.tintColor = theme.colorForType(.NormalText)
        button.contentHorizontalAlignment = .left
        if #available(iOS 13.0, *) {
            let config = UIImage.SymbolConfiguration(pointSize: 18.0, weight: .regular)
            button.setImage(UIImage(systemName: systemImage, withConfiguration: config), for: .normal)
        }
        // KHANDAQ design (Figma): filled grey rounded row (not a bordered button).
        button.backgroundColor = theme.colorForType(.ChatInputBackground)
        button.layer.cornerRadius = 12.0
        button.layer.masksToBounds = true
        button.contentEdgeInsets = UIEdgeInsets(top: 14, left: 16, bottom: 14, right: 16)
        button.addTarget(self, action: action, for: .touchUpInside)
        view.addSubview(button)
        return button
    }

    func installConstraints() {
        myIdLabel.snp.makeConstraints {
            $0.top.equalTo(view).offset(16)
            $0.leading.equalTo(view).offset(Constants.TextViewXOffset + 11)
            $0.trailing.equalTo(view).offset(-(Constants.TextViewXOffset + 11))
        }

        idTextField.snp.makeConstraints {
            $0.top.equalTo(myIdLabel.snp.bottom).offset(8)
            $0.leading.equalTo(myIdLabel)
            $0.trailing.equalTo(myIdLabel)
            $0.height.equalTo(52)
        }

        orLabel.snp.makeConstraints {
            $0.top.equalTo(idTextField.snp.bottom).offset(12)
            $0.leading.trailing.equalTo(idTextField)
        }

        qrCodeButton.snp.makeConstraints {
            $0.top.equalTo(orLabel.snp.bottom).offset(12)
            $0.leading.equalTo(idTextField)
            $0.trailing.equalTo(idTextField)
        }

        qrGalleryButton.snp.makeConstraints {
            $0.top.equalTo(qrCodeButton.snp.bottom).offset(12)
            $0.leading.equalTo(idTextField)
            $0.trailing.equalTo(idTextField)
        }
    }

    func applyIdFieldText(_ text: String, cursorOffset: Int) {
        idTextField.text = text
        setIdFieldCursor(offset: cursorOffset)
        updateSendButton(with: text)
    }

    func setIdFieldCursor(offset: Int) {
        let nsLength = ((idTextField.text ?? "") as NSString).length
        let clamped = min(max(0, offset), nsLength)

        guard let position = idTextField.position(from: idTextField.beginningOfDocument, offset: clamped) else {
            return
        }
        idTextField.selectedTextRange = idTextField.textRange(from: position, to: position)
    }

    func updateSendButton(with text: String? = nil) {
        let value = text ?? idTextField.text ?? ""
        navigationItem.rightBarButtonItem?.isEnabled = isAddressString(value)
    }

    func normalizedIdFieldText(from raw: String) -> String {
        let hex = sanitizeAddressInput(raw)

        if let normalized = normalizeAddressString(hex) {
            return normalized
        }

        if hex.count > Int(kOCTToxAddressLength) {
            let prefix = String(hex.prefix(Int(kOCTToxAddressLength)))
            if let normalized = normalizeAddressString(prefix) {
                return normalized
            }
        }

        return String(hex.prefix(Int(kOCTToxAddressLength)))
    }

    func isOwnToxAddress(_ address: String) -> Bool {
        let normalized = sanitizeAddressInput(address).uppercased()
        let own = sanitizeAddressInput(ownToxAddress).uppercased()
        if normalized == own {
            return true
        }
        let ownPublicKey = String(own.prefix(64))
        let candidatePublicKey = String(normalized.prefix(64))
        return !ownPublicKey.isEmpty && ownPublicKey == candidatePublicKey
    }

    @objc func idTextFieldEditingChanged() {
        sanitizeIdFieldIfNeeded()
    }

    func sanitizeIdFieldIfNeeded() {
        let raw = idTextField.text ?? ""
        let normalized = normalizedIdFieldText(from: raw)
        if normalized != raw {
            applyIdFieldText(normalized, cursorOffset: (normalized as NSString).length)
        } else {
            updateSendButton(with: normalized)
        }
    }

    func validatedAddressFromIdField() -> String? {
        return normalizedToxAddress(from: idTextField.text ?? "")
    }

    func existingContactConflictMessage(for address: String) -> String? {
        guard let publicKey = toxPublicKeyPrefix(from: address) else {
            return nil
        }

        let friends = submanagerObjects.friends(
            predicate: NSPredicate(format: "publicKey ==[c] %@", publicKey)
        )
        if friends.count > 0 {
            return String(localized: "add_contact_already_friend")
        }

        let pending = submanagerObjects.friendRequests(
            predicate: NSPredicate(format: "publicKey ==[c] %@", publicKey)
        )
        if pending.count > 0 {
            return String(localized: "add_contact_request_already_pending")
        }

        return nil
    }

    func showInvalidIdFormatError() {
        UIAlertController.showWithTitle(
            String(localized: "error_title"),
            message: String(localized: "add_contact_invalid_id_format"),
            retryBlock: nil
        )
    }
}

// KHANDAQ (#13): scan a friend's QR code from a saved photo (parity with Android). Reuses the same
// validation/normalisation as the camera scanner.
extension AddFriendController: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [String: Any]) {
        picker.dismiss(animated: true) { [weak self] in
            guard let self = self else {
                return
            }

            guard let image = info[UIImagePickerControllerOriginalImage] as? UIImage,
                  let decoded = self.decodeQRCode(from: image) else {
                self.showWrongQRError()
                return
            }

            let value = self.normalizedIdFieldText(from: decoded)
            guard isAddressString(value) else {
                self.showWrongQRError()
                return
            }

            self.applyIdFieldText(value, cursorOffset: (value as NSString).length)
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true, completion: nil)
    }

    func decodeQRCode(from image: UIImage) -> String? {
        guard let ciImage = CIImage(image: image) else {
            return nil
        }

        let detector = CIDetector(ofType: CIDetectorTypeQRCode,
                                  context: nil,
                                  options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        let features = detector?.features(in: ciImage) ?? []

        for feature in features {
            if let qr = feature as? CIQRCodeFeature, let message = qr.messageString, !message.isEmpty {
                return message
            }
        }
        return nil
    }

    func showWrongQRError() {
        UIAlertController.showWithTitle(
            String(localized: "error_title"),
            message: String(localized: "add_contact_wrong_qr"),
            retryBlock: nil
        )
    }
}
