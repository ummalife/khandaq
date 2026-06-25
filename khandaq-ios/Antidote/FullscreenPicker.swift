// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let AnimationDuration = 0.3
    static let ToolbarHeight: CGFloat = 44.0
}

protocol FullscreenPickerDelegate: class {
    func fullscreenPicker(_ picker: FullscreenPicker, willDismissWithSelectedIndex index: Int)
}

class FullscreenPicker: UIView {
    weak var delegate: FullscreenPickerDelegate?
    // KHANDAQ: optional "delete the currently-selected row" action (used to remove a login profile).
    var onDelete: ((Int) -> Void)?

    fileprivate var theme: Theme

    fileprivate var blackoutButton: UIButton!
    fileprivate var toolbar: UIToolbar!
    fileprivate var picker: UIPickerView!

    fileprivate var pickerBottomConstraint: Constraint!

    fileprivate let stringsArray: [String]

    init(theme: Theme, strings: [String], selectedIndex: Int) {
        self.theme = theme
        self.stringsArray = strings

        super.init(frame: CGRect.zero)

        configureSelf()
        createSubviews()
        installConstraints()

        picker.selectRow(selectedIndex, inComponent: 0, animated: false)
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func showAnimatedInView(_ view: UIView) {
        view.addSubview(self)

        snp.makeConstraints {
            $0.edges.equalTo(view)
        }

        show()
    }
}

// MARK: Actions
extension FullscreenPicker {
    @objc func doneButtonPressed() {
        delegate?.fullscreenPicker(self, willDismissWithSelectedIndex: picker.selectedRow(inComponent: 0))
        hide()
    }

    @objc func deleteButtonPressed() {
        onDelete?(picker.selectedRow(inComponent: 0))
    }
}

extension FullscreenPicker: UIPickerViewDataSource {
    func numberOfComponents(in pickerView: UIPickerView) -> Int {
        return 1
    }

    func pickerView(_ pickerView: UIPickerView, numberOfRowsInComponent component: Int) -> Int {
        return stringsArray.count
    }
}

extension FullscreenPicker: UIPickerViewDelegate {
    func pickerView(_ pickerView: UIPickerView, titleForRow row: Int, forComponent component: Int) -> String? {
        return stringsArray[row]
    }

    // KHANDAQ: render each row with an explicit themed text color. The default titleForRow uses the
    // system label color, which the window's dark interface style turns WHITE — invisible on the
    // picker's background in the dark theme (the profile names couldn't be read). viewForRow takes
    // full control so the names are visible in both themes.
    func pickerView(_ pickerView: UIPickerView, viewForRow row: Int, forComponent component: Int, reusing view: UIView?) -> UIView {
        let label = (view as? UILabel) ?? UILabel()
        label.text = stringsArray[row]
        label.textColor = theme.colorForType(.NormalText)
        label.textAlignment = .center
        label.font = UIFont.systemFont(ofSize: 21.0)
        label.backgroundColor = .clear
        return label
    }

    func pickerView(_ pickerView: UIPickerView, didSelectRow row: Int, inComponent component: Int) {
        picker.reloadAllComponents()
    }
}

private extension FullscreenPicker {
    func configureSelf() {
        backgroundColor = .clear
    }

    func createSubviews() {
        blackoutButton = UIButton()
        blackoutButton.backgroundColor = theme.colorForType(.TranslucentBackground)
        blackoutButton.addTarget(self, action: #selector(FullscreenPicker.doneButtonPressed), for:.touchUpInside)
        blackoutButton.accessibilityElementsHidden = true
        blackoutButton.isAccessibilityElement = false
        addSubview(blackoutButton)

        toolbar = UIToolbar()
        toolbar.tintColor = theme.colorForType(.LoginButtonText)
        toolbar.barTintColor = theme.loginNavigationBarColor
        // KHANDAQ: a destructive "delete profile" button on the left of the picker toolbar.
        let deleteItem = UIBarButtonItem(barButtonSystemItem: .trash, target: self, action: #selector(FullscreenPicker.deleteButtonPressed))
        deleteItem.tintColor = UIColor(red: 1.0, green: 0.27, blue: 0.23, alpha: 1.0)
        toolbar.items = [
            deleteItem,
            UIBarButtonItem(barButtonSystemItem: .flexibleSpace, target: self, action: nil),
            UIBarButtonItem(barButtonSystemItem: .done, target: self, action: #selector(FullscreenPicker.doneButtonPressed))
        ]
        addSubview(toolbar)

        picker = UIPickerView()
        // KHANDAQ: theme the picker background (was hard-coded white) so it matches the active theme;
        // the row text color is set explicitly in viewForRow so names stay readable on both.
        picker.backgroundColor = theme.colorForType(.NormalBackground)
        picker.delegate = self
        picker.dataSource = self
        addSubview(picker)
    }

    func installConstraints() {
        blackoutButton.snp.makeConstraints {
            $0.edges.equalTo(self)
        }

        toolbar.snp.makeConstraints {
            $0.bottom.equalTo(self.picker.snp.top)
            $0.height.equalTo(Constants.ToolbarHeight)
            $0.width.equalTo(self)
        }

        picker.snp.makeConstraints {
            $0.width.equalTo(self)
            pickerBottomConstraint = $0.bottom.equalTo(self).constraint
        }
    }

    func show() {
        blackoutButton.alpha = 0.0
        pickerBottomConstraint.update(offset: picker.frame.size.height + Constants.ToolbarHeight)

        layoutIfNeeded()

        UIView.animate(withDuration: Constants.AnimationDuration, animations: {
            self.blackoutButton.alpha = 1.0
            self.pickerBottomConstraint.update(offset: 0.0)

            self.layoutIfNeeded()
            UIAccessibilityPostNotification(UIAccessibilityScreenChangedNotification, self.picker);
        }) 
    }

    func hide() {
        UIView.animate(withDuration: Constants.AnimationDuration, animations: {
            self.blackoutButton.alpha = 0.0
            self.pickerBottomConstraint.update(offset: self.picker.frame.size.height + Constants.ToolbarHeight)

            self.layoutIfNeeded()
        }, completion: { finished in
            self.removeFromSuperview()
        })
    }
}
