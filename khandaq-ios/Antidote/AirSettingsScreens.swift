// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// Settings screens of the redesign (E18): Settings lists its sections by task and each opens a screen
// of its own: Appearance, Notifications, Privacy, Profile transfer and Network (SettingsAdvancedController),
// with debugging behind the build flag. Settings > Privacy > Blocked lists the keys this device blocked,
// each with "Unblock" (design/DESIGN.md, "Screen decisions", block contract).

/// Settings > Appearance: the theme as a segment, as in the system by default; the language; whether
/// group chats say who joined and who left. A new theme or language rebuilds the interface under a
/// cross-fade of the whole window and comes back to this screen (ActiveSessionCoordinator).
final class AirAppearanceController: AirScrollController {
    private let userDefaults = UserDefaultsManager()
    private weak var submanagerObjects: OCTSubmanagerObjects?
    private let languageRow = AirSettingRow(title: String(localized: "settings_language"))
    private(set) lazy var themeControl: AirSegmentedControl = AirSegmentedControl(
        label: String(localized: "settings_theme"),
        titles: ThemePreference.allCases.map { $0.localizedName },
        identifiers: ThemePreference.allCases.map { "settings.theme.\($0.rawValue)" },
        selectedIndex: ThemePreference.allCases.firstIndex(of: ThemeAppearance.preference) ?? 0)

    init(submanagerObjects: OCTSubmanagerObjects?) {
        self.submanagerObjects = submanagerObjects
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = String(localized: "air_settings_appearance")
        view.accessibilityIdentifier = "settings.appearance.screen"

        themeControl.accessibilityIdentifier = "settings.theme"
        themeControl.addTarget(self, action: #selector(themeChanged), for: .valueChanged)
        addSection(String(localized: "settings_theme"), views: [themeControl])

        languageRow.accessibilityIdentifier = "settings.language"
        languageRow.setValue(AppLanguage.nativeName(for: AppLanguage.current))
        languageRow.addTarget(self, action: #selector(languageTapped), for: .touchUpInside)
        let groupEvents = AirSettingRow.toggle(title: String(localized: "settings_group_system_messages"),
                                               detail: String(localized: "air_settings_group_events_detail"),
                                               isOn: userDefaults.groupShowSystemMessages,
                                               identifier: "settings.group-events") { [weak self] on in
            self?.userDefaults.groupShowSystemMessages = on
            self?.submanagerObjects?.setGroupShowSystemMessages(on)
        }
        addSection(nil, rows: [languageRow, groupEvents])
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        // Following the system, the stored choice stays the same while the phone changes; a choice
        // made elsewhere (the QA command) shows here too.
        themeControl.select(ThemePreference.allCases.firstIndex(of: ThemeAppearance.preference) ?? 0)
    }

    @objc private func themeChanged() {
        let choice = ThemePreference.allCases[themeControl.selectedIndex]
        // A different palette rebuilds the interface at once; the same palette only restyles the window.
        ThemeAppearance.preference = choice
    }

    // KHANDAQ (#248): each language is written in its own language, and the whole interface is rebuilt
    // right after, so the change shows without restarting the app.
    @objc private func languageTapped() {
        // The current language is named in the message rather than ticked: a check glyph in the title
        // is read aloud as part of the name and is one of the characters the product text avoids.
        let sheet = UIAlertController(title: String(localized: "settings_language_title"),
                                      message: String(localized: "settings_choice_current",
                                                      AppLanguage.nativeName(for: AppLanguage.current)),
                                      preferredStyle: .actionSheet)
        for code in AppLanguage.supportedCodes {
            sheet.addAction(UIAlertAction(title: AppLanguage.nativeName(for: code), style: .default) { [weak self] _ in
                guard code != AppLanguage.current else {
                    return
                }
                AppLanguage.set(code)
                self?.reloadAfterLanguageChange()
            })
        }
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        // An iPad needs an anchor for an action sheet.
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = languageRow
            popover.sourceRect = languageRow.bounds
        }
        present(sheet, animated: true, completion: nil)
    }

    private func reloadAfterLanguageChange() {
        // Titles, the bars and the layout direction are decided when views are made, so the whole
        // session is rebuilt (AppCoordinator listens for this) rather than this screen alone.
        languageRow.setValue(AppLanguage.nativeName(for: AppLanguage.current))
        let window = view.window
        NotificationCenter.default.post(name: Notification.Name("KhandaqAppLanguageChanged"), object: nil)
        guard let window = window else {
            return
        }
        UIView.transition(with: window, duration: KQMotion.duration(KQMotion.state), options: .transitionCrossDissolve, animations: {
            window.rootViewController?.view.setNeedsLayout()
        }, completion: nil)
    }
}

/// Settings > Notifications: whether a notification shows the text, with what that means.
final class AirNotificationsController: AirScrollController {
    private let userDefaults = UserDefaultsManager()

    override func viewDidLoad() {
        super.viewDidLoad()
        title = String(localized: "air_settings_notifications")
        view.accessibilityIdentifier = "settings.notifications.screen"
        let preview = AirSettingRow.toggle(title: String(localized: "air_settings_preview"),
                                           isOn: userDefaults.showNotificationPreview,
                                           identifier: "settings.preview") { [weak self] on in
            self?.userDefaults.showNotificationPreview = on
        }
        addSection(nil, rows: [preview], note: String(localized: "air_settings_preview_note"))
    }
}

/// Settings > Profile transfer: export and import of the profile file, and what the file carries.
final class AirTransferController: AirScrollController {
    var onExport: ((AirTransferController) -> Void)?
    var onImport: ((AirTransferController) -> Void)?

    override func viewDidLoad() {
        super.viewDidLoad()
        title = String(localized: "air_settings_transfer")
        view.accessibilityIdentifier = "settings.transfer.screen"
        let export = AirSettingRow(title: String(localized: "export_profile"))
        export.accessibilityIdentifier = "settings.export"
        export.onTap { [weak self] in
            if let self = self {
                self.onExport?(self)
            }
        }
        let importRow = AirSettingRow(title: String(localized: "import_profile"))
        importRow.accessibilityIdentifier = "settings.import"
        importRow.onTap { [weak self] in
            if let self = self {
                self.onImport?(self)
            }
        }
        addSection(nil, rows: [export, importRow], note: String(localized: "air_settings_transfer_note"))
    }
}

#if DEBUG
/// Settings > Debugging, in debug builds only: the switches and tools a developer uses, kept out of
/// the sections people use.
final class AirDebugController: AirScrollController {
    weak var sessionCoordinator: ActiveSessionCoordinator?
    private let theme: Theme
    private let userDefaults = UserDefaultsManager()

    init(theme: Theme) {
        self.theme = theme
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        title = String(localized: "air_settings_debug")
        view.accessibilityIdentifier = "settings.debug.screen"
        let debugMode = AirSettingRow.toggle(title: "Debug Mode", isOn: userDefaults.DebugMode,
                                             identifier: "settings.debug-mode") { [weak self] on in
            self?.userDefaults.DebugMode = on
        }
        let dates = AirSettingRow.toggle(title: "Always show date on Messages", isOn: userDefaults.DateonmessageMode,
                                         identifier: "settings.date-on-messages") { [weak self] on in
            self?.userDefaults.DateonmessageMode = on
        }
        let automation = AirSettingRow(title: "QA Automation", detail: "Cross-platform test commands")
        automation.accessibilityIdentifier = "settings.qa-automation"
        automation.onTap { [weak self] in
            guard let self = self, let sessionCoordinator = self.sessionCoordinator else {
                return
            }
            let controller = QaDebugController(theme: self.theme, sessionCoordinator: sessionCoordinator)
            self.navigationController?.pushViewController(controller, animated: true)
        }
        addSection(nil, rows: [debugMode, dates, automation])
    }
}
#endif

extension AirSettingRow {
    /// A row with a switch on its trailing side. VoiceOver reads the switch under the row's title and
    /// the quiet line as its hint.
    static func toggle(title: String, detail: String? = nil, isOn: Bool, identifier: String,
                       changed: @escaping (Bool) -> Void) -> AirSettingRow {
        let row = AirSettingRow(title: title, detail: detail, opens: false)
        let toggle = UISwitch()
        toggle.isOn = isOn
        toggle.onTintColor = KQColor.switchOn
        toggle.accessibilityIdentifier = identifier
        toggle.accessibilityLabel = title
        toggle.accessibilityHint = detail
        toggle.onValueChange { [unowned toggle] in
            changed(toggle.isOn)
        }
        row.setAccessory(toggle)
        return row
    }
}

/// Settings > Privacy.
final class AirPrivacyController: AirScrollController {
    var onShowBlocked: (() -> Void)?

    private let blockedRow = AirSettingRow(title: String(localized: "air_blocked_title"))
    private var observer: NSObjectProtocol?

    override func viewDidLoad() {
        super.viewDidLoad()
        title = String(localized: "air_privacy_title")
        blockedRow.accessibilityIdentifier = "air.settings.privacy.blocked"
        blockedRow.addTarget(self, action: #selector(blockedTapped), for: .touchUpInside)
        addSection(String(localized: "air_blocked_title"), rows: [blockedRow], note: String(localized: "air_blocked_section_note"))
        observer = NotificationCenter.default.addObserver(forName: .airLocalStoreDidChange, object: AirLocalStore.shared, queue: .main) { [weak self] _ in
            self?.refresh()
        }
        refresh()
    }

    deinit {
        if let observer = observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func refresh() {
        let count = AirLocalStore.shared.blockedKeys.count
        blockedRow.setValue(count == 0 ? String(localized: "air_blocked_none") : String(localized: "air_blocked_count_format", count))
    }

    @objc private func blockedTapped() {
        onShowBlocked?()
    }
}

/// Settings > Privacy > Blocked: every blocked key in groups of four, each with "Unblock". Unblocking
/// only lets requests from the key show again; the contact itself comes back with a new request.
final class AirBlockedKeysController: AirScrollController {
    private var observer: NSObjectProtocol?

    override func viewDidLoad() {
        super.viewDidLoad()
        title = String(localized: "air_blocked_title")
        view.accessibilityIdentifier = "air.settings.blocked"
        observer = NotificationCenter.default.addObserver(forName: .airLocalStoreDidChange, object: AirLocalStore.shared, queue: .main) { [weak self] _ in
            self?.rebuild()
        }
        rebuild()
    }

    deinit {
        if let observer = observer {
            NotificationCenter.default.removeObserver(observer)
        }
    }

    private func rebuild() {
        column.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let keys = AirLocalStore.shared.blockedKeys
        guard !keys.isEmpty else {
            let empty = AirKit.body(String(localized: "air_blocked_empty"))
            empty.accessibilityIdentifier = "air.settings.blocked.empty"
            addSection(nil, views: [empty])
            return
        }
        addSection(nil, views: [AirKit.body(String(localized: "air_blocked_note"))])
        let cards = keys.enumerated().map { index, key in card(for: key, index: index) }
        addSection(nil, views: cards)
    }

    private func card(for key: String, index: Int) -> UIView {
        let keyLabel = AirKit.label(.mono, color: KQColor.ink, lines: 0)
        keyLabel.text = AirKeyFormat.lines(key)
        keyLabel.accessibilityLabel = String(localized: "air_dossier_key_title")
        keyLabel.accessibilityValue = AirKeyFormat.groups(key).joined(separator: " ")
        let unblock = AirButton(style: .normal, title: String(localized: "air_blocked_unblock"))
        unblock.accessibilityIdentifier = "air.settings.blocked.unblock.\(index)"
        unblock.onTap { [weak self] in
            self?.unblock(key)
        }
        let stack = AirKit.column([keyLabel, AirKit.leading(unblock)], spacing: KQSpace.x12)
        let plate = UIView()
        plate.backgroundColor = KQColor.surface
        plate.layer.cornerRadius = KQRadius.card
        plate.addSubview(stack)
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview().inset(KQSpace.x16)
        }
        return plate
    }

    private func unblock(_ key: String) {
        AirLocalStore.shared.unblock(publicKey: key)
        AirToast.show(String(localized: "air_blocked_unblocked"), in: view)
    }
}

extension UIControl {
    /// A tap handler without a selector; the closure lives as long as the control.
    func onTap(_ handler: @escaping () -> Void) {
        let target = AirClosureTarget(handler)
        objc_setAssociatedObject(self, &AirClosureTarget.key, target, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        addTarget(target, action: #selector(AirClosureTarget.fire), for: .touchUpInside)
    }

    /// A handler for a new value (a switch), kept apart from the tap handler.
    func onValueChange(_ handler: @escaping () -> Void) {
        let target = AirClosureTarget(handler)
        objc_setAssociatedObject(self, &AirClosureTarget.valueKey, target, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        addTarget(target, action: #selector(AirClosureTarget.fire), for: .valueChanged)
    }
}

private final class AirClosureTarget: NSObject {
    static var key = 0
    static var valueKey = 0
    private let handler: () -> Void

    init(_ handler: @escaping () -> Void) {
        self.handler = handler
    }

    @objc func fire() {
        handler()
    }
}
