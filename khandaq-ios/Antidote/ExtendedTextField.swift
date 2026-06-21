// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    // KHANDAQ design (Figma): taller filled field + a touch more breathing room under the label.
    static let TextFieldHeight = 52.0
    static let VerticalOffset = 8.0
}

protocol ExtendedTextFieldDelegate: class {
    func loginExtendedTextFieldReturnKeyPressed(_ field: ExtendedTextField)
}

class ExtendedTextField: UIView {
    enum FieldType {
        case login
        case normal
    }

    weak var delegate: ExtendedTextFieldDelegate?

    var maxTextUTF8Length: Int = Int.max

    var title: String? {
        get {
            return titleLabel.text
        }
        set {
            titleLabel.text = newValue
        }
    }

    var placeholder: String? {
        get {
            return textField.placeholder
        }
        set {
            textField.placeholder = newValue
        }
    }

    var text: String? {
        get {
            return textField.text
        }
        set {
            textField.text = newValue
        }
    }

    var hint: String? {
        get {
            return hintLabel.text
        }
        set {
            hintLabel.text = newValue
        }
    }

    var secureTextEntry: Bool {
        get {
            return textField.isSecureTextEntry
        }
        set {
            textField.isSecureTextEntry = newValue
        }
    }

    var returnKeyType: UIReturnKeyType {
        get {
            return textField.returnKeyType
        }
        set {
            textField.returnKeyType = newValue
        }
    }

    fileprivate var titleLabel: UILabel!
    fileprivate var textField: UITextField!
    fileprivate var hintLabel: UILabel!

    init(theme: Theme, type: FieldType) {
        super.init(frame: CGRect.zero)

        createViews(theme: theme, type: type)
        installConstraints()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
    override func becomeFirstResponder() -> Bool {
        return textField.becomeFirstResponder()
    }
}

extension ExtendedTextField: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        delegate?.loginExtendedTextFieldReturnKeyPressed(self)
        return false
    }

    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
        let currentText = textField.text ?? ""
        let resultText = (currentText as NSString).replacingCharacters(in: range, with: string)

        if resultText.lengthOfBytes(using: String.Encoding.utf8) <= maxTextUTF8Length {
            return true
        }

        textField.text = resultText.substringToByteLength(maxTextUTF8Length, encoding: String.Encoding.utf8)
        return false
    }
}

// Accessibility
extension ExtendedTextField {
    override var isAccessibilityElement: Bool {
        get {
            return true
        }
        set {}
    }

    override var accessibilityLabel: String? {
        get {
            return placeholder ?? title
        }
        set {}
    }

    override var accessibilityHint: String? {
        get {
            var result: String?

            if placeholder != nil {
                // If there is a placeholder also read title as part of the hint.
                result = title
            }

            switch (result, hint) {
                case (.none, _):
                    return hint
                case (.some, .none):
                    return result
                case (.some(let r), .some(let s)):
                    return "\(r), \(s)"
            }
        }
        set {}
    }

    override var accessibilityValue: String? {
        get {
            return text
        }
        set {}
    }

    override var accessibilityTraits: UIAccessibilityTraits {
        get {
            return textField.accessibilityTraits
        }
        set {}
    }
}

private extension ExtendedTextField {
    func createViews(theme: Theme, type: FieldType) {
        textField = UITextField()
        textField.delegate = self
        textField.borderStyle = .roundedRect
        textField.enablesReturnKeyAutomatically = true
        addSubview(textField)

        let textColor: UIColor

        switch type {
            case .login:
                textColor = theme.colorForType(.NormalText)

                textField.autocapitalizationType = .none
                textField.autocorrectionType = .no
                textField.spellCheckingType = .no
                if #available(iOS 11.0, *) {
                    textField.smartInsertDeleteType = .no
                }

                // KHANDAQ design (Figma): filled grey rounded field (no green hairline border), 12pt
                // radius, with horizontal padding since the borderless style has none of its own.
                textField.borderStyle = .none
                textField.backgroundColor = theme.colorForType(.ChatInputBackground)
                textField.layer.borderWidth = 0.0
                textField.layer.masksToBounds = true
                textField.layer.cornerRadius = 12.0
                let leftPad = UIView(frame: CGRect(x: 0, y: 0, width: 14, height: 1))
                textField.leftView = leftPad
                textField.leftViewMode = .always
                let rightPad = UIView(frame: CGRect(x: 0, y: 0, width: 14, height: 1))
                textField.rightView = rightPad
                textField.rightViewMode = .always
            case .normal:
                textColor = theme.colorForType(.NormalText)
                textField.autocapitalizationType = .sentences
        }

        titleLabel = UILabel()
        titleLabel.textColor = textColor
        // KHANDAQ design: compact field label above the input.
        titleLabel.font = UIFont.systemFont(ofSize: 15.0, weight: .medium)
        titleLabel.backgroundColor = .clear
        addSubview(titleLabel)

        hintLabel = UILabel()
        hintLabel.textColor = theme.colorForType(.FriendCellStatus)
        hintLabel.font = UIFont.systemFont(ofSize: 13.0)
        hintLabel.numberOfLines = 0
        hintLabel.backgroundColor = .clear
        addSubview(hintLabel)
    }

    func installConstraints() {
        titleLabel.snp.makeConstraints {
            $0.top.leading.trailing.equalTo(self)
        }

        textField.snp.makeConstraints {
            $0.top.equalTo(titleLabel.snp.bottom).offset(Constants.VerticalOffset)
            $0.leading.trailing.equalTo(self)
            $0.height.equalTo(Constants.TextFieldHeight)
        }

        hintLabel.snp.makeConstraints {
            $0.top.equalTo(textField.snp.bottom).offset(Constants.VerticalOffset)
            $0.leading.trailing.equalTo(self)
            $0.bottom.equalTo(self)
        }
    }
}
