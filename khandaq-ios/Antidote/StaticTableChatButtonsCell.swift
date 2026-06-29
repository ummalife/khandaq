// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    // KHANDAQ design (Figma profile): action buttons are grey circles with a label underneath.
    static let CircleSize = 52.0
    static let IconInset = 15.0
    static let LabelTopOffset = 6.0
    static let VerticalOffset = 10.0
}

class StaticTableChatButtonsCell: StaticTableBaseCell {
    fileprivate var chatButton: UIButton!
    fileprivate var callButton: UIButton!
    fileprivate var videoButton: UIButton!

    fileprivate var chatColumn: UIStackView!
    fileprivate var callColumn: UIStackView!
    fileprivate var videoColumn: UIStackView!

    fileprivate var labels: [UILabel] = []
    fileprivate var rowStack: UIStackView!

    fileprivate var chatButtonHandler: (() -> Void)?
    fileprivate var callButtonHandler: (() -> Void)?
    fileprivate var videoButtonHandler: (() -> Void)?

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let buttonsModel = model as? StaticTableChatButtonsCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        selectionStyle = .none
        backgroundColor = .clear
        customContentView.backgroundColor = .clear

        chatButtonHandler = buttonsModel.chatButtonHandler
        callButtonHandler = buttonsModel.callButtonHandler
        videoButtonHandler = buttonsModel.videoButtonHandler

        for button in [chatButton, callButton, videoButton] {
            button?.backgroundColor = theme.colorForType(.TabSelection)
            button?.tintColor = theme.colorForType(.LinkText)
        }
        for label in labels {
            label.textColor = theme.colorForType(.NormalText)
        }

        applyEnabled(chatButton, column: chatColumn, enabled: buttonsModel.chatButtonEnabled)
        applyEnabled(callButton, column: callColumn, enabled: buttonsModel.callButtonEnabled)
        applyEnabled(videoButton, column: videoColumn, enabled: buttonsModel.videoButtonEnabled)
    }

    private func applyEnabled(_ button: UIButton, column: UIStackView, enabled: Bool) {
        button.isEnabled = enabled
        column.alpha = enabled ? 1.0 : 0.4
    }

    override func createViews() {
        super.createViews()

        chatButton = createCircleButton("friend-card-chat",
                                        accessibilityLabel: String(localized: "accessibility_chat_button_label"),
                                        accessibilityHint: String(localized: "accessibility_chat_button_hint"),
                                        action: #selector(StaticTableChatButtonsCell.chatButtonPressed))
        callButton = createCircleButton("start-call",
                                        accessibilityLabel: String(localized: "accessibility_call_button_label"),
                                        accessibilityHint: String(localized: "accessibility_call_button_hint"),
                                        action: #selector(StaticTableChatButtonsCell.callButtonPressed))
        videoButton = createCircleButton("video-call",
                                         accessibilityLabel: String(localized: "accessibility_video_button_label"),
                                         accessibilityHint: String(localized: "accessibility_video_button_hint"),
                                         action: #selector(StaticTableChatButtonsCell.videoButtonPressed))

        chatColumn = makeColumn(button: chatButton, title: String(localized: "accessibility_chat_button_label"))
        callColumn = makeColumn(button: callButton, title: String(localized: "accessibility_call_button_label"))
        videoColumn = makeColumn(button: videoButton, title: String(localized: "accessibility_video_button_label"))

        rowStack = UIStackView(arrangedSubviews: [chatColumn, callColumn, videoColumn])
        rowStack.axis = .horizontal
        rowStack.distribution = .fillEqually
        rowStack.alignment = .center
        customContentView.addSubview(rowStack)
    }

    override func installConstraints() {
        super.installConstraints()

        rowStack.snp.makeConstraints {
            $0.top.equalTo(customContentView).offset(Constants.VerticalOffset)
            $0.bottom.equalTo(customContentView).offset(-Constants.VerticalOffset)
            $0.leading.equalTo(customContentView).offset(24.0)
            $0.trailing.equalTo(customContentView).offset(-24.0)
        }
    }
}

extension StaticTableChatButtonsCell {
    @objc func chatButtonPressed() {
        chatButtonHandler?()
    }

    @objc func callButtonPressed() {
        callButtonHandler?()
    }

    @objc func videoButtonPressed() {
        videoButtonHandler?()
    }
}

private extension StaticTableChatButtonsCell {
    func createCircleButton(_ imageName: String,
                            accessibilityLabel: String,
                            accessibilityHint: String,
                            action: Selector) -> UIButton {
        let image = UIImage.templateNamed(imageName)

        let button = UIButton()
        button.setImage(image, for: UIControlState())
        button.imageView?.contentMode = .scaleAspectFit
        button.imageEdgeInsets = UIEdgeInsets(top: Constants.IconInset, left: Constants.IconInset,
                                              bottom: Constants.IconInset, right: Constants.IconInset)
        button.layer.cornerRadius = CGFloat(Constants.CircleSize) / 2.0
        button.layer.masksToBounds = true
        button.accessibilityLabel = accessibilityLabel
        button.accessibilityHint = accessibilityHint
        button.addTarget(self, action: action, for: .touchUpInside)

        button.snp.makeConstraints {
            $0.size.equalTo(Constants.CircleSize)
        }

        return button
    }

    func makeColumn(button: UIButton, title: String) -> UIStackView {
        let label = UILabel()
        label.text = title
        label.font = UIFont.systemFont(ofSize: 12.0)
        label.textAlignment = .center
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.8
        labels.append(label)

        let column = UIStackView(arrangedSubviews: [button, label])
        column.axis = .vertical
        column.alignment = .center
        column.spacing = Constants.LabelTopOffset
        return column
    }
}
