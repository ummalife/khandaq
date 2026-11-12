// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit

/// "Share" into Khandaq from another app. What is shared is copied into the app group's inbox with a
/// manifest (ShareInboxStorage); the app takes it when it comes up and asks which chat it goes to. A share
/// extension may not open its app (only Today and iMessage extensions may), so when the system refuses,
/// the sheet says to open Khandaq; the app also takes the inbox whenever it becomes active.
class ShareViewController: UIViewController {
    private let statusLabel = UILabel()
    private let doneButton = UIButton(type: .system)
    private var didFinish = false
    private var didShowOutcome = false
    private var outcomeSucceeded = false

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = .systemBackground

        statusLabel.translatesAutoresizingMaskIntoConstraints = false
        statusLabel.textAlignment = .center
        statusLabel.numberOfLines = 0
        statusLabel.font = UIFont.preferredFont(forTextStyle: .body)
        statusLabel.adjustsFontForContentSizeCategory = true
        statusLabel.textColor = .label
        statusLabel.text = ShareText.preparing
        statusLabel.accessibilityIdentifier = "share.status"
        view.addSubview(statusLabel)

        doneButton.translatesAutoresizingMaskIntoConstraints = false
        doneButton.setTitle(ShareText.done, for: .normal)
        doneButton.titleLabel?.font = UIFont.preferredFont(forTextStyle: .headline)
        doneButton.titleLabel?.adjustsFontForContentSizeCategory = true
        doneButton.isHidden = true
        doneButton.accessibilityIdentifier = "share.done"
        doneButton.addTarget(self, action: #selector(donePressed), for: .touchUpInside)
        view.addSubview(doneButton)

        NSLayoutConstraint.activate([
            statusLabel.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            statusLabel.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            statusLabel.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            doneButton.topAnchor.constraint(equalTo: statusLabel.bottomAnchor, constant: 24),
            doneButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            doneButton.heightAnchor.constraint(greaterThanOrEqualToConstant: 44),
            doneButton.widthAnchor.constraint(greaterThanOrEqualToConstant: 88),
        ])

        guard let extensionItems = extensionContext?.inputItems as? [NSExtensionItem] else {
            showOutcome(ShareText.failed, succeeded: false)
            return
        }
        ShareInboxCollector.collect(extensionItems) { [weak self] items in
            self?.finalizeShare(with: items)
        }
    }

    private func finalizeShare(with items: [ShareInboxItem]) {
        guard !items.isEmpty else {
            showOutcome(ShareText.failed, succeeded: false)
            return
        }

        let payload = ShareInboxPayload(version: 1, createdAt: Date().timeIntervalSince1970, items: items)
        guard ShareInboxStorage.savePendingShare(payload) else {
            showOutcome(ShareText.failed, succeeded: false)
            return
        }

        guard let url = URL(string: "khandaq://share"), let context = extensionContext else {
            showOutcome(ShareText.openApp, succeeded: true)
            return
        }
        context.open(url) { [weak self] opened in
            DispatchQueue.main.async {
                if opened {
                    self?.finish(succeeded: true)
                }
                else {
                    self?.showOutcome(ShareText.openApp, succeeded: true)
                }
            }
        }
        // Should the system never answer, the sheet does not stay on "Preparing".
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) { [weak self] in
            self?.showOutcome(ShareText.openApp, succeeded: true)
        }
    }

    /// The last words of the sheet and its Done, which closes it.
    private func showOutcome(_ text: String, succeeded: Bool) {
        guard !didFinish, !didShowOutcome else {
            return
        }
        didShowOutcome = true
        outcomeSucceeded = succeeded
        statusLabel.text = text
        doneButton.isHidden = false
        UIAccessibilityPostNotification(UIAccessibilityScreenChangedNotification, statusLabel)
    }

    @objc private func donePressed() {
        finish(succeeded: outcomeSucceeded)
    }

    private func finish(succeeded: Bool) {
        guard !didFinish else {
            return
        }
        didFinish = true
        if succeeded {
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            return
        }
        let error = NSError(domain: "ShareExtension", code: 1, userInfo: [
            NSLocalizedDescriptionKey: ShareText.failed,
        ])
        extensionContext?.cancelRequest(withError: error)
    }
}

/// The words of the sheet, from the app's own dictionary (one dictionary for both), in the language the
/// app speaks: the one it keeps in the app group, or the phone's when it is one of the app's four, English
/// otherwise, as AppLanguage picks it.
private enum ShareText {
    static var preparing: String {
        return string("air_share_ext_preparing", "Preparing to send")
    }

    static var openApp: String {
        return string("air_share_ext_open_app", "Ready. Open Khandaq and choose who gets it.")
    }

    static var failed: String {
        return string("air_share_ext_failed", "This could not be passed to Khandaq.")
    }

    static var done: String {
        return string("air_nc_done", "Done")
    }

    private static let supported = ["en", "ru", "ar", "zh"]

    private static let table: Bundle? = {
        // The extension lies in the app's PlugIns folder.
        let appURL = Bundle.main.bundleURL.deletingLastPathComponent().deletingLastPathComponent()
        guard let app = Bundle(url: appURL),
              let path = app.path(forResource: language, ofType: "lproj") else {
            return nil
        }
        return Bundle(path: path)
    }()

    private static var language: String {
        if let kept = ShareInboxStorage.appLanguage, supported.contains(kept) {
            return kept
        }
        for tag in Locale.preferredLanguages {
            let base = tag.split(separator: "-").first.map(String.init) ?? tag
            if supported.contains(base) {
                return base
            }
        }
        return "en"
    }

    private static func string(_ key: String, _ english: String) -> String {
        return table?.localizedString(forKey: key, value: english, table: nil) ?? english
    }
}
