// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

protocol SettingsAboutControllerDelegate: class {
    func settingsAboutControllerShowAcknowledgements(_ controller: SettingsAboutController)
}

/// Settings > About (E18), drawn as the other sections of Settings (redesign stage 5): the version and
/// the build, the site and the owner, then the toxcore version, then the licences. Rows that only say
/// a value are read as text, not as buttons.
class SettingsAboutController: AirScrollController {
    weak var delegate: SettingsAboutControllerDelegate?

    init(theme: Theme) {
        super.init(nibName: nil, bundle: nil)
        title = String(localized: "settings_about")
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "settings.about.screen"
        let info = Bundle.main.infoDictionary

        let version = valueRow("settings_khandaq_version", value: info?["CFBundleShortVersionString"] as? String)
        let build = valueRow("settings_khandaq_build", value: info?["CFBundleVersion"] as? String)
        // KHANDAQ (18.08): bare hosts, the same on Android; a tap opens the whole address.
        let website = AirSettingRow(title: String(localized: "settings_website"), value: "khandaq.org")
        website.onTap {
            AirAboutLinks.open("https://khandaq.org")
        }
        // The owner and developer, under the project's own site. The identifier is the QA regression's
        // hook: the Arabic catalogue transliterates the name, so a match on the text cannot pass there.
        let credit = AirSettingRow(title: String(localized: "settings_credit_owner"), value: "1sa.me")
        credit.accessibilityIdentifier = "about.ownerCredit"
        credit.onTap {
            AirAboutLinks.open("https://1sa.me/")
        }
        addSection(nil, rows: [version, build, website, credit])

        addSection(nil, rows: [valueRow("settings_toxcore_version", value: OCTTox.version())])

        let licences = AirSettingRow(title: String(localized: "settings_acknowledgements"))
        licences.accessibilityIdentifier = "settings.about.licences"
        licences.onTap { [weak self] in
            if let self = self {
                self.delegate?.settingsAboutControllerShowAcknowledgements(self)
            }
        }
        addSection(nil, rows: [licences])
    }
}

private extension SettingsAboutController {
    /// A row that only says a value: no chevron, and VoiceOver reads it as text.
    func valueRow(_ titleKey: String, value: String?) -> AirSettingRow {
        let row = AirSettingRow(title: String(localized: titleKey), value: value, opens: false)
        row.isUserInteractionEnabled = false
        row.accessibilityTraits = UIAccessibilityTraitStaticText
        return row
    }
}

private enum AirAboutLinks {
    static func open(_ address: String) {
        guard let url = URL(string: address) else {
            return
        }
        UIApplication.shared.open(url)
    }
}
