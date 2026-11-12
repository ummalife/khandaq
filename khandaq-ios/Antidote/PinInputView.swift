// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

protocol PinInputViewDelegate: class {
    func pinInputView(_ view: PinInputView, numericButtonPressed i: Int)
    func pinInputViewDeleteButtonPressed(_ view: PinInputView)
}

/// The PIN pad (redesign stage 4, E20): the title and the line under it, the cells of the PIN
/// (.kq-pin) that take a dot for each digit, and the keypad of 64 pt circles in the thumb's reach.
/// On the lock ground, dark in both themes, it is white on the translucent controls of the call
/// screens; in a sheet it is ink on the sunken colour. The keypad keeps its order in every language.
class PinInputView: UIView {
    enum Ground {
        case lock
        case sheet
    }

    weak var delegate: PinInputViewDelegate?

    /// Entered numbers. Must be in 0...pinLength range.
    var enteredNumbersCount: Int = 0 {
        didSet {
            enteredNumbersCount = min(max(enteredNumbersCount, 0), pinLength)
            updateCells()
        }
    }

    var topText: String {
        get {
            return titleLabel.text ?? ""
        }
        set {
            titleLabel.text = newValue
            cellsView.accessibilityLabel = newValue
        }
    }

    var descriptionText: String? {
        get {
            return descriptionLabel.text
        }
        set {
            descriptionLabel.text = newValue
            descriptionLabel.isHidden = (newValue ?? "").isEmpty
        }
    }

    /// Off while a lockout runs: the keys stand dimmed and take no taps.
    var keysEnabled = true {
        didSet {
            for key in keys {
                key.isEnabled = keysEnabled
            }
            deleteButton.isEnabled = keysEnabled
        }
    }

    private let pinLength: Int
    private let ground: Ground
    private let titleLabel: UILabel
    private let descriptionLabel: UILabel
    private let cellsView = UIStackView()
    private var cells = [AirPinCell]()
    private var keys = [AirPinKey]()
    private let deleteButton = UIButton(type: .system)

    init(pinLength: Int, ground: Ground) {
        self.pinLength = pinLength
        self.ground = ground
        let ink = ground == .lock ? KQColor.fixedWhite : KQColor.ink
        titleLabel = AirKit.label(.heading, color: ink, lines: 0)
        descriptionLabel = AirKit.label(.label, color: ground == .lock ? KQColor.fixedWhite : KQColor.ink2, lines: 0)
        super.init(frame: .zero)

        titleLabel.textAlignment = .center
        titleLabel.accessibilityTraits = UIAccessibilityTraitHeader
        titleLabel.accessibilityIdentifier = "air.pin.title"
        descriptionLabel.textAlignment = .center
        descriptionLabel.isHidden = true
        descriptionLabel.accessibilityIdentifier = "air.pin.note"

        for _ in 0..<pinLength {
            cells.append(AirPinCell(ground: ground))
        }
        cellsView.axis = .horizontal
        cellsView.spacing = KQSpace.x8
        cellsView.semanticContentAttribute = .forceLeftToRight
        cells.forEach { cellsView.addArrangedSubview($0) }
        cellsView.isAccessibilityElement = true
        cellsView.accessibilityIdentifier = "air.pin.cells"

        let words = AirKit.column([titleLabel, descriptionLabel], spacing: KQSpace.x6)
        words.alignment = .fill
        let column = AirKit.column([words, cellsView, makeKeypad()], spacing: KQSpace.x24)
        column.alignment = .center
        column.setCustomSpacing(KQSpace.x32, after: cellsView)
        addSubview(column)
        column.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        words.snp.makeConstraints {
            $0.width.equalToSuperview()
        }
        updateCells()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// Kept for the callers of the old pad: the colours follow the traits by themselves.
    func applyColors() {
        updateCells()
    }

    /// A wrong PIN: the cells shake once.
    func shake() {
        cellsView.airShakeOnce()
    }

    @objc private func keyTapped(_ key: AirPinKey) {
        delegate?.pinInputView(self, numericButtonPressed: key.digit)
    }

    @objc private func deleteTapped() {
        delegate?.pinInputViewDeleteButtonPressed(self)
    }

    private func updateCells() {
        for (index, cell) in cells.enumerated() {
            cell.isFilled = index < enteredNumbersCount
            cell.isCurrent = index == enteredNumbersCount
        }
        cellsView.accessibilityValue = String(localized: "air_pin_entered_format", enteredNumbersCount, pinLength)
    }

    /// 1 to 9 in three rows, then 0 with Delete after it.
    private func makeKeypad() -> UIView {
        var rows = [UIStackView]()
        for row in 0..<3 {
            let digits = (1...3).map { row * 3 + $0 }
            rows.append(keyRow(digits.map { makeKey($0) }))
        }

        deleteButton.setTitle(String(localized: "pin_delete"), for: .normal)
        deleteButton.titleLabel?.font = KQFont.font(.label)
        deleteButton.titleLabel?.adjustsFontForContentSizeCategory = true
        deleteButton.titleLabel?.adjustsFontSizeToFitWidth = true
        deleteButton.titleLabel?.minimumScaleFactor = 0.6
        deleteButton.tintColor = ground == .lock ? KQColor.fixedWhite : KQColor.ink
        deleteButton.accessibilityIdentifier = "air.pin.delete"
        deleteButton.addTarget(self, action: #selector(deleteTapped), for: .touchUpInside)
        let blank = UIView()
        rows.append(keyRow([blank, makeKey(0), deleteButton]))
        for view in [blank, deleteButton] {
            view.snp.makeConstraints {
                $0.size.equalTo(KQSpace.x64)
            }
        }

        let keypad = AirKit.column(rows, spacing: KQSpace.x12)
        keypad.semanticContentAttribute = .forceLeftToRight
        return keypad
    }

    private func makeKey(_ digit: Int) -> AirPinKey {
        let key = AirPinKey(digit: digit, ground: ground)
        key.addTarget(self, action: #selector(keyTapped(_:)), for: .touchUpInside)
        keys.append(key)
        return key
    }

    private func keyRow(_ views: [UIView]) -> UIStackView {
        let row = UIStackView(arrangedSubviews: views)
        row.axis = .horizontal
        row.spacing = KQSpace.x24
        row.semanticContentAttribute = .forceLeftToRight
        return row
    }
}

/// A cell of the PIN (.kq-pin): a dot for an entered digit, the strong edge on the one that comes next.
final class AirPinCell: UIView {
    var isFilled = false {
        didSet {
            dot.isHidden = !isFilled
        }
    }

    var isCurrent = false {
        didSet {
            applyColors()
        }
    }

    private let ground: PinInputView.Ground
    private let dot = UIView()

    init(ground: PinInputView.Ground) {
        self.ground = ground
        super.init(frame: .zero)
        layer.cornerRadius = KQRadius.field
        layer.borderWidth = KQStroke.hairline
        dot.layer.cornerRadius = KQSize.dot / 2
        dot.isHidden = true
        addSubview(dot)
        dot.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.size.equalTo(KQSize.dot)
        }
        snp.makeConstraints {
            $0.width.equalTo(KQSize.targetIos)
            $0.height.equalTo(KQSize.pinCell)
        }
        applyColors()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyColors()
    }

    private func applyColors() {
        switch ground {
            case .lock:
                backgroundColor = KQColor.mediaControl
                dot.backgroundColor = KQColor.fixedWhite
                layer.borderColor = airCGColor(isCurrent ? KQColor.fixedWhite : UIColor.clear)
            case .sheet:
                backgroundColor = KQColor.surface
                dot.backgroundColor = KQColor.ink
                layer.borderColor = airCGColor(isCurrent ? KQColor.ink : KQColor.lineStrong)
        }
    }
}

/// A key of the PIN keypad: a 64 pt circle with its digit. Pressed, it turns over like a call control.
final class AirPinKey: UIControl {
    let digit: Int
    private let ground: PinInputView.Ground
    private let label = UILabel()

    init(digit: Int, ground: PinInputView.Ground) {
        self.digit = digit
        self.ground = ground
        super.init(frame: .zero)
        layer.cornerRadius = KQSpace.x64 / 2
        label.font = KQFont.tabular(KQFont.font(.display))
        label.adjustsFontForContentSizeCategory = true
        // At the largest text sizes the digit shrinks to stay inside its circle.
        label.adjustsFontSizeToFitWidth = true
        label.minimumScaleFactor = 0.5
        label.text = AirPinKey.numeral(digit)
        label.textAlignment = .center
        label.isUserInteractionEnabled = false
        addSubview(label)
        label.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.width.lessThanOrEqualToSuperview().offset(-KQSpace.x16)
        }
        snp.makeConstraints {
            $0.size.equalTo(KQSpace.x64)
        }
        isAccessibilityElement = true
        accessibilityLabel = String(digit)
        accessibilityTraits = UIAccessibilityTraitButton | UIAccessibilityTraitKeyboardKey
        accessibilityIdentifier = String(digit)
        applyColors()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var isHighlighted: Bool {
        didSet {
            applyColors()
        }
    }

    override var isEnabled: Bool {
        didSet {
            alpha = isEnabled ? 1 : 0.4
            if isEnabled {
                accessibilityTraits &= ~UIAccessibilityTraitNotEnabled
            }
            else {
                accessibilityTraits |= UIAccessibilityTraitNotEnabled
            }
        }
    }

    /// The digit as the app's language writes it (Arabic-Indic digits in Arabic).
    static func numeral(_ digit: Int) -> String {
        let formatter = NumberFormatter()
        formatter.locale = Locale(identifier: AppLanguage.current)
        return formatter.string(from: NSNumber(value: digit)) ?? String(digit)
    }

    private func applyColors() {
        switch ground {
            case .lock:
                backgroundColor = isHighlighted ? KQColor.fixedWhite : KQColor.mediaControl
                label.textColor = isHighlighted ? KQColor.lockTop : KQColor.fixedWhite
            case .sheet:
                backgroundColor = isHighlighted ? KQColor.selected : KQColor.sunken
                label.textColor = KQColor.ink
        }
    }
}
