// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    // KHANDAQ design (Figma): 16pt grid, taller filled field.
    static let Offset = 16.0
    static let FieldHeight = 48.0
    // KHANDAQ (#93): roomy multi-line editor (e.g. Status).
    static let MultilineFieldHeight = 160.0
}

class TextEditController: UIViewController {
    fileprivate let theme: Theme

    fileprivate let defaultValue: String?
    fileprivate let multiline: Bool
    fileprivate let changeTextHandler: (String) -> Void
    fileprivate let userFinishedEditing: () -> Void

    fileprivate var captionLabel: UILabel!
    fileprivate var textField: UITextField!
    fileprivate var textView: UITextView!

    /**
        Creates controller for editing single text field.

        - Parameters:
          - theme: Theme controller will use.
          - multiline: When true, uses a multi-line UITextView (Return inserts a newline) instead of a
            single-line field; finish editing via the navbar "Готово". Used for the Status editor (#93).
          - changeTextHandler: Handler called when user have changed the text.
          - userFinishedEditing: Handler called when user have finished editing.
     */
    init(theme: Theme, title: String, defaultValue: String?, multiline: Bool = false, changeTextHandler: @escaping (String) -> Void, userFinishedEditing: @escaping () -> Void) {
        self.theme = theme
        self.defaultValue = defaultValue
        self.multiline = multiline
        self.changeTextHandler = changeTextHandler
        self.userFinishedEditing = userFinishedEditing

        super.init(nibName: nil, bundle: nil)

        self.title = title
        edgesForExtendedLayout = UIRectEdge()
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func loadView() {
        loadViewWithBackgroundColor(theme.colorForType(.NormalBackground))

        createTextField()
        installConstraints()
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        // KHANDAQ design (Figma): grey circle back + explicit "Готово".
        installKhandaqCircleBackButton(theme: theme)
        navigationItem.rightBarButtonItem = UIBarButtonItem(
            title: String(localized: "change_password_done"),
            style: .done, target: self, action: #selector(TextEditController.donePressed))
    }

    @objc func donePressed() {
        changeTextHandler(currentText)
        userFinishedEditing()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        activeEditor.becomeFirstResponder()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)

        changeTextHandler(currentText)
    }

    fileprivate var activeEditor: UIView {
        return multiline ? textView : textField
    }

    fileprivate var currentText: String {
        return multiline ? (textView.text ?? "") : (textField.text ?? "")
    }
}

extension TextEditController: UITextFieldDelegate {
    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        changeTextHandler(textField.text ?? "")
        userFinishedEditing()

        return false
    }
}

private extension TextEditController {
    func createTextField() {
        // KHANDAQ design (Figma): grey caption above the field (the screen title).
        captionLabel = UILabel()
        captionLabel.text = title
        captionLabel.font = UIFont.systemFont(ofSize: 13.0)
        captionLabel.textColor = theme.colorForType(.ChatListCellMessage)
        view.addSubview(captionLabel)

        if multiline {
            // KHANDAQ (#93): multi-line editor. Return inserts a newline; "Готово" finishes.
            textView = UITextView()
            textView.text = defaultValue
            textView.font = UIFont.systemFont(ofSize: 17.0)
            textView.backgroundColor = theme.colorForType(.ChatInputBackground)
            textView.textColor = theme.colorForType(.NormalText)
            textView.tintColor = theme.colorForType(.LinkText)
            textView.layer.cornerRadius = 12.0
            textView.layer.masksToBounds = true
            textView.textContainerInset = UIEdgeInsets(top: 12.0, left: 8.0, bottom: 12.0, right: 8.0)
            textView.returnKeyType = .default
            view.addSubview(textView)
            return
        }

        textField = UITextField()
        textField.text = defaultValue
        textField.delegate = self
        textField.returnKeyType = .done
        // KHANDAQ design (Figma Forms): filled neutral-grey rounded field, no border.
        textField.borderStyle = .none
        textField.backgroundColor = theme.colorForType(.ChatInputBackground)
        textField.textColor = theme.colorForType(.NormalText)
        textField.layer.cornerRadius = 12.0
        textField.layer.masksToBounds = true
        textField.placeholder = title
        textField.leftView = UIView(frame: CGRect(x: 0, y: 0, width: 12, height: 1))
        textField.leftViewMode = .always
        textField.clearButtonMode = .whileEditing
        view.addSubview(textField)
    }

    func installConstraints() {
        captionLabel.snp.makeConstraints {
            $0.top.equalTo(view).offset(Constants.Offset)
            $0.leading.equalTo(view).offset(Constants.Offset)
            $0.trailing.equalTo(view).offset(-Constants.Offset)
        }

        activeEditor.snp.makeConstraints {
            $0.top.equalTo(captionLabel.snp.bottom).offset(8.0)
            $0.leading.equalTo(view).offset(Constants.Offset)
            $0.trailing.equalTo(view).offset(-Constants.Offset)
            $0.height.equalTo(multiline ? Constants.MultilineFieldHeight : Constants.FieldHeight)
        }
    }
}
