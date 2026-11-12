// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol ChangePinTimeoutControllerDelegate: class {
    func changePinTimeoutControllerDone(_ controller: ChangePinTimeoutController)
}

/// "Lock" from "Password and PIN": how long after Khandaq was left it asks for the PIN again, one
/// choice of five; choosing one keeps it and goes back.
final class ChangePinTimeoutController: AirScrollController {
    weak var delegate: ChangePinTimeoutControllerDelegate?

    private weak var submanagerObjects: OCTSubmanagerObjects!
    private var rows: [AirChoiceRow] = []

    init(theme: Theme, submanagerObjects: OCTSubmanagerObjects) {
        self.submanagerObjects = submanagerObjects
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
        title = String(localized: "air_security_lock")
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.security.lockScreen"
        let current = submanagerObjects.getProfileSettings().lockTimeout
        rows = AirLockTimeoutText.all.map { timeout in
            let row = AirChoiceRow(title: AirLockTimeoutText.title(timeout))
            row.accessibilityIdentifier = "air.security.lock." + timeout.rawValue
            row.isSelected = timeout == current
            row.addTarget(self, action: #selector(rowTapped(_:)), for: .touchUpInside)
            return row
        }
        addSection(nil, rows: rows, note: String(localized: "air_security_lock_note"))
    }

    @objc private func rowTapped(_ row: AirChoiceRow) {
        guard let index = rows.firstIndex(where: { $0 === row }) else {
            return
        }
        for other in rows {
            other.isSelected = other === row
        }
        let settings = submanagerObjects.getProfileSettings()
        settings.lockTimeout = AirLockTimeoutText.all[index]
        submanagerObjects.saveProfileSettings(settings)
        delegate?.changePinTimeoutControllerDone(self)
    }
}
