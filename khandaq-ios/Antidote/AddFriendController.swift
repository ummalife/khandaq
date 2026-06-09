// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let TextViewTopOffset = 5.0
    static let TextViewXOffset = 5.0
    static let QrCodeBottomSpacerDeltaHeight = 70.0

    static let SendAlertTextViewBottomOffset = -10.0
    static let SendAlertTextViewXOffset = 5.0
    static let SendAlertTextViewHeight = 70.0
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
    fileprivate let ownToxAddress: String

    fileprivate var textView: UITextView!

    fileprivate var orTopSpacer: UIView!
    fileprivate var qrCodeBottomSpacer: UIView!

    fileprivate var orLabel: UILabel!
    fileprivate var qrCodeButton: UIButton!

    fileprivate var cachedMessage: String?

    init(theme: Theme, submanagerFriends: OCTSubmanagerFriends, ownToxAddress: String) {
        self.theme = theme
        self.submanagerFriends = submanagerFriends
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
            self.textView.text = sanitizeAddressInput($0)
            self.updateSendButton()
        })
    }

    @objc func sendButtonPressed() {
        textView.resignFirstResponder()

        let messageView = UITextView()
        messageView.text = cachedMessage
        let placeholderstring = NSAttributedString.init(string: String(localized: "add_contact_default_message_text"))
        messageView.attributedPlaceholder = placeholderstring
        messageView.font = UIFont.systemFont(ofSize: 17.0)
        messageView.layer.cornerRadius = 5.0
        messageView.layer.masksToBounds = true

        let alert = SDCAlertController(
                title: String(localized: "add_contact_default_message_title"),
                message: nil,
                preferredStyle: .alert)!

        alert.contentView.addSubview(messageView)
        messageView.snp.makeConstraints {
            $0.top.equalTo(alert.contentView)
            $0.bottom.equalTo(alert.contentView).offset(Constants.SendAlertTextViewBottomOffset);
            $0.leading.equalTo(alert.contentView).offset(Constants.SendAlertTextViewXOffset);
            $0.trailing.equalTo(alert.contentView).offset(-Constants.SendAlertTextViewXOffset);
            $0.height.equalTo(Constants.SendAlertTextViewHeight);
        }

        alert.addAction(SDCAlertAction(title: String(localized: "alert_cancel"), style: .default, handler: nil))
        alert.addAction(SDCAlertAction(title: String(localized: "add_contact_send"), style: .recommended) { [unowned self] action in
            self.cachedMessage = messageView.text

            let message = messageView.text.isEmpty ? KhandaqBranding.defaultStatusMessage : messageView.text

            do {
                guard let address = normalizeAddressString(self.textView.text) else {
                    return
                }

                if self.isOwnToxAddress(address) {
                    UIAlertController.showWithTitle(
                        String(localized: "error_title"),
                        message: String(localized: "add_contact_own_id_error"),
                        retryBlock: nil
                    )
                    return
                }

                try self.submanagerFriends.sendFriendRequest(toAddress: address, message: message)
            }
            catch let error as NSError {
                handleErrorWithType(.toxAddFriend, error: error)
                return
            }

            self.delegate?.addFriendControllerDidFinish(self)
        })

        alert.present(completion: nil)
    }
}

extension AddFriendController: UITextViewDelegate {
    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
        if text == "\n" {
            updateSendButton()
            textView.resignFirstResponder()
            return false
        }

        let merged = (textView.text! as NSString).replacingCharacters(in: range, with: text)
        let maxHex = Int(kOCTToxAddressLength)

        // Paste / autofill: strip tox:, spaces, then keep up to 76 hex chars.
        if text.count > 1 {
            textView.text = String(sanitizeAddressInput(merged).prefix(maxHex))
            updateSendButton()
            return false
        }

        if text.count == 1 {
            let ch = text.uppercased()
            if ch.rangeOfCharacter(from: CharacterSet(charactersIn: "0123456789ABCDEF")) == nil {
                return false
            }
        }

        if sanitizeAddressInput(merged).count > maxHex {
            return false
        }

        updateSendButton()
        return true
    }

    func textViewDidChange(_ textView: UITextView) {
        updateSendButton()
    }
}

private extension AddFriendController {
    func addNavigationButtons() {
        navigationItem.rightBarButtonItem = UIBarButtonItem(
                title: String(localized: "add_contact_send"),
                style: .done,
                target: self,
                action: #selector(AddFriendController.sendButtonPressed))
    }

    func createViews() {
        textView = UITextView()
        let placeholderstring = NSAttributedString.init(string: String(localized: "add_contact_tox_id_placeholder"))
        textView.attributedPlaceholder = placeholderstring
        textView.delegate = self
        textView.isScrollEnabled = false
        textView.font = UIFont.systemFont(ofSize: 17)
        textView.textColor = theme.colorForType(.NormalText)
        textView.backgroundColor = .clear
        textView.returnKeyType = .done
        textView.autocapitalizationType = .allCharacters
        textView.autocorrectionType = .no
        textView.smartInsertDeleteType = .no
        textView.spellCheckingType = .no
        textView.keyboardType = .asciiCapable
        textView.layer.cornerRadius = 5.0
        textView.layer.borderWidth = 0.5
        textView.layer.borderColor = theme.colorForType(.SeparatorsAndBorders).cgColor
        textView.layer.masksToBounds = true
        view.addSubview(textView)

        orTopSpacer = createSpacer()
        qrCodeBottomSpacer = createSpacer()

        orLabel = UILabel()
        orLabel.text = String(localized: "add_contact_or_label")
        orLabel.textColor = theme.colorForType(.NormalText)
        orLabel.backgroundColor = .clear
        view.addSubview(orLabel)

        qrCodeButton = UIButton(type: .system)
        qrCodeButton.setTitle(String(localized: "add_contact_use_qr"), for: UIControlState())
        qrCodeButton.titleLabel!.font = UIFont.khandaqFontWithSize(16.0, weight: .bold)
        qrCodeButton.addTarget(self, action: #selector(AddFriendController.qrCodeButtonPressed), for: .touchUpInside)
        view.addSubview(qrCodeButton)
    }

    func createSpacer() -> UIView {
        let spacer = UIView()
        spacer.backgroundColor = .clear
        view.addSubview(spacer)

        return spacer
    }

    func installConstraints() {
        textView.snp.makeConstraints {
            $0.top.equalTo(view).offset(Constants.TextViewTopOffset)
            $0.leading.equalTo(view).offset(Constants.TextViewXOffset)
            $0.trailing.equalTo(view).offset(-Constants.TextViewXOffset)
            $0.bottom.equalTo(view.snp.centerY)
        }

        orTopSpacer.snp.makeConstraints {
            $0.top.equalTo(textView.snp.bottom)
        }

        orLabel.snp.makeConstraints {
            $0.top.equalTo(orTopSpacer.snp.bottom)
            $0.centerX.equalTo(view)
        }

        qrCodeButton.snp.makeConstraints {
            $0.top.equalTo(orLabel.snp.bottom)
            $0.centerX.equalTo(view)
        }

        qrCodeBottomSpacer.snp.makeConstraints {
            $0.top.equalTo(qrCodeButton.snp.bottom)
            $0.bottom.equalTo(view)
            $0.height.equalTo(orTopSpacer)
        }
    }

    func updateSendButton() {
        navigationItem.rightBarButtonItem!.isEnabled = isAddressString(textView.text)
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
}
