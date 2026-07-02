// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

private struct Constants {
    static let DotsSize: CGFloat = 16
    static let DotsSpacing: CGFloat = 10
    static let ButtonSize: CGFloat = 75
    static let VerticalOffsetSmall: CGFloat = 12
    static let VerticalOffsetBig: CGFloat = 17
    static let HorizontalOffset: CGFloat = 17
}

protocol PinInputViewDelegate: class {
    func pinInputView(_ view: PinInputView, numericButtonPressed i: Int)
    func pinInputViewDeleteButtonPressed(_ view: PinInputView)
}

// KHANDAQ (Figma): flat PIN pad — plain background, light-grey circular keys, teal outline/filled
// dots. Replaces the old full-screen teal gradient styling.
class PinInputView: UIView {
    weak var delegate: PinInputViewDelegate?

    /// Entered numbers. Must be in 0...pinLength range.
    var enteredNumbersCount: Int = 0 {
        didSet {
            enteredNumbersCount = max(enteredNumbersCount, 0)
            enteredNumbersCount = min(enteredNumbersCount, pinLength)

            updateDotsImages()
        }
    }

    var topText: String {
        get {
            return topLabel.text!
        }
        set {
            topLabel.text = newValue
        }
    }

    var descriptionText: String? {
        get {
            return descriptionLabel.text
        }
        set {
            descriptionLabel.text = newValue
        }
    }

    fileprivate let pinLength: Int

    fileprivate let textColor: UIColor
    fileprivate let secondaryTextColor: UIColor
    fileprivate let accentColor: UIColor
    fileprivate let keyFillColor: UIColor

    fileprivate var topLabel: UILabel!
    fileprivate var descriptionLabel: UILabel!
    fileprivate var dotsContainer: UIView!
    fileprivate var dotsImageViews = [UIImageView]()
    fileprivate var numericButtons = [UIButton]()
    fileprivate var deleteButton: UIButton!

    init(pinLength: Int, theme: Theme) {
        self.pinLength = pinLength
        self.textColor = theme.colorForType(.NormalText)
        self.secondaryTextColor = theme.colorForType(.ChatInformationText)
        self.accentColor = theme.colorForType(.LinkText)
        self.keyFillColor = theme.colorForType(.ChatInputBackground)

        super.init(frame: CGRect.zero)

        createLabels()
        createDotsImageViews()
        createNumericButtons()
        createDeleteButton()

        installConstraints()

        updateDotsImages()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /**
        Refreshes dots state. Kept for compatibility with the previous (gradient) implementation,
        colors are now static and applied at creation.
    */
    func applyColors() {
        updateDotsImages()
    }
}

extension PinInputView {
    @objc func numericButtonPressed(_ button: UIButton) {
        guard let i = numericButtons.index(of: button) else {
            return
        }

        delegate?.pinInputView(self, numericButtonPressed: i)
    }

    @objc func deleteButtonPressed(_ button: UIButton) {
        delegate?.pinInputViewDeleteButtonPressed(self)
    }
}

private extension PinInputView {
    func createLabels() {
        topLabel = UILabel()
        topLabel.font = UIFont.khandaqFontWithSize(18.0, weight: .medium)
        topLabel.textColor = textColor
        addSubview(topLabel)

        descriptionLabel = UILabel()
        descriptionLabel.font = UIFont.khandaqFontWithSize(16.0, weight: .light)
        descriptionLabel.textColor = secondaryTextColor
        addSubview(descriptionLabel)
    }

    func createDotsImageViews() {
        for _ in 0..<pinLength {
            dotsContainer = UIView()
            dotsContainer.backgroundColor = .clear
            addSubview(dotsContainer)

            let imageView = UIImageView()
            dotsContainer.addSubview(imageView)

            dotsImageViews.append(imageView)
        }
    }

    func createNumericButtons() {
        let normalImage = circleImage(color: keyFillColor, size: Constants.ButtonSize, filled: true)
        let highlightedImage = circleImage(color: accentColor, size: Constants.ButtonSize, filled: true)

        for i in 0...9 {
            let button = UIButton()
            button.setTitle("\(i)", for: UIControlState())
            button.titleLabel?.font = UIFont.khandaqFontWithSize(26.0, weight: .light)
            button.setBackgroundImage(normalImage, for: UIControlState())
            button.setBackgroundImage(highlightedImage, for: .highlighted)
            button.setTitleColor(textColor, for: UIControlState())
            button.setTitleColor(.white, for: .highlighted)
            button.addTarget(self, action: #selector(PinInputView.numericButtonPressed(_:)), for: .touchUpInside)
            addSubview(button)

            numericButtons.append(button)
        }
    }

    func createDeleteButton() {
        deleteButton = UIButton(type: .system)
        deleteButton.setTitle(String(localized: "pin_delete"), for: UIControlState())
        deleteButton.titleLabel?.font = UIFont.khandaqFontWithSize(17.0, weight: .light)
        deleteButton.setTitleColor(secondaryTextColor, for: UIControlState())
        deleteButton.addTarget(self, action: #selector(PinInputView.deleteButtonPressed(_:)), for: .touchUpInside)
        addSubview(deleteButton)
    }

    func installConstraints() {
        topLabel.snp.makeConstraints {
            $0.top.equalTo(self)
            $0.centerX.equalTo(self)
        }

        descriptionLabel.snp.makeConstraints {
            $0.top.equalTo(topLabel.snp.bottom).offset(Constants.VerticalOffsetSmall)
            $0.centerX.equalTo(self)
        }

        installConstraintsForDotsViews()
        installConstraintsForZeroButton()
        installConstraintsForNumericButtons()

        deleteButton.snp.makeConstraints {
            $0.centerX.equalTo(numericButtons[9])
            $0.centerY.equalTo(numericButtons[0])
        }
    }

    func installConstraintsForDotsViews() {
        dotsContainer.snp.makeConstraints {
            $0.top.equalTo(descriptionLabel.snp.bottom).offset(Constants.VerticalOffsetBig)
            $0.centerX.equalTo(self)
        }

        for i in 0..<dotsImageViews.count {
            let imageView = dotsImageViews[i]

            imageView.snp.makeConstraints {
                $0.top.equalTo(dotsContainer)
                $0.bottom.equalTo(dotsContainer)
                $0.size.equalTo(Constants.DotsSize)

                if i == 0 {
                    $0.left.equalTo(dotsContainer)
                }
                else {
                    $0.left.equalTo(dotsImageViews[i - 1].snp.right).offset(Constants.DotsSpacing)
                }

                if i == (dotsImageViews.count - 1) {
                    $0.right.equalTo(dotsContainer)
                }
            }
        }
    }

    func installConstraintsForZeroButton() {
        numericButtons[0].snp.makeConstraints {
            $0.top.equalTo(numericButtons[8].snp.bottom).offset(Constants.VerticalOffsetSmall)
            $0.bottom.equalTo(self)

            $0.centerX.equalTo(numericButtons[8])
            $0.size.equalTo(Constants.ButtonSize)
        }
    }

    func installConstraintsForNumericButtons() {
        for i in 1...9 {
            let button = numericButtons[i]

            button.snp.makeConstraints {
                $0.size.equalTo(Constants.ButtonSize)

                switch i % 3 {
                case 1:
                    $0.left.equalTo(self)
                case 2:
                    $0.left.equalTo(numericButtons[i - 1].snp.right).offset(Constants.HorizontalOffset)
                default:
                    $0.left.equalTo(numericButtons[i - 1].snp.right).offset(Constants.HorizontalOffset)
                    $0.right.equalTo(self)
                }

                if i <= 3 {
                    $0.top.equalTo(dotsContainer.snp.bottom).offset(Constants.VerticalOffsetBig)
                }
                else if i <= 6 {
                    $0.top.equalTo(numericButtons[i - 3].snp.bottom).offset(Constants.VerticalOffsetSmall)
                }
                else {
                    $0.top.equalTo(numericButtons[i - 3].snp.bottom).offset(Constants.VerticalOffsetSmall)
                }
            }
        }
    }

    func updateDotsImages() {
        guard !dotsImageViews.isEmpty else {
            return
        }

        let empty = circleImage(color: accentColor, size: Constants.DotsSize, filled: false)
        let filled = circleImage(color: accentColor, size: Constants.DotsSize, filled: true)

        for i in 0..<dotsImageViews.count {
            let imageView = dotsImageViews[i]
            imageView.image = (i < enteredNumbersCount) ? filled : empty
        }
    }

    func circleImage(color: UIColor, size: CGFloat, filled: Bool) -> UIImage {
        let renderer = UIGraphicsImageRenderer(size: CGSize(width: size, height: size))

        return renderer.image { _ in
            if filled {
                let path = UIBezierPath(ovalIn: CGRect(x: 0, y: 0, width: size, height: size))
                color.setFill()
                path.fill()
            }
            else {
                let lineWidth: CGFloat = 1.5
                let path = UIBezierPath(ovalIn: CGRect(x: lineWidth / 2,
                                                       y: lineWidth / 2,
                                                       width: size - lineWidth,
                                                       height: size - lineWidth))
                path.lineWidth = lineWidth
                color.setStroke()
                path.stroke()
            }
        }
    }
}
