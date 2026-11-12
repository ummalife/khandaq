// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

/// "Group settings", opened from the group dossier (E14), as the screens of Settings are drawn: your
/// name in this group and the topic, then who can join (with the password and the member limit) and
/// who can write (with who changes the topic). The founder changes the last two; everyone else sees
/// what they are and who changes them. The dossier keeps the members, the key, notifications, the
/// history and "Leave the group". The calls to objcTox are the ones the old screen made.
final class GroupInfoController: AirScrollController {
    let chat: OCTChat

    private weak var submanagerGroups: OCTSubmanagerGroups!

    private var selfName = ""
    private var topic = ""
    private var canEditTopic = false
    private var isFounder = false
    private var privacy: AirGroupPrivacy = .inviteOnly
    private var voice: OCTToxGroupVoiceState = .all
    private var topicLocked = false
    private var hasPassword = false
    private var peerLimit: Int32 = kOCTDefaultGroupPeerLimit

    private var privacyRows: [AirChoiceRow] = []
    private var voiceRows: [AirChoiceRow] = []
    private let joinFailure = AirKit.note("")
    private let writeFailure = AirKit.note("")

    init(chat: OCTChat, submanagerGroups: OCTSubmanagerGroups) {
        self.chat = chat
        self.submanagerGroups = submanagerGroups
        super.init(nibName: nil, bundle: nil)
        hidesBottomBarWhenPushed = true
        title = String(localized: "air_group_settings")
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.group.settings"
        for failure in [joinFailure, writeFailure] {
            failure.textColor = KQColor.danger
            failure.isHidden = true
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        guard !chat.isInvalidated else {
            navigationController?.popToRootViewController(animated: false)
            return
        }
        reload()
        build()
    }

    // MARK: State

    private var isLive: Bool {
        return chat.groupNumber >= 0
    }

    /// What toxcore holds now, with what the profile stored as the fallback while it does not answer.
    private func reload() {
        submanagerGroups.refreshGroupMetadata(for: chat)
        let role = submanagerGroups.selfRole(in: chat)
        isFounder = role == .founder
        canEditTopic = isLive && submanagerGroups.canEditGroupTopic(in: chat)
        topic = (try? submanagerGroups.groupTopic(for: chat)) ?? chat.groupTopic ?? ""
        selfName = isLive ? ((try? submanagerGroups.groupSelfPeerName(for: chat)) ?? "") : ""
        hasPassword = !(((try? submanagerGroups.groupPassword(for: chat)) ?? chat.groupPassword) ?? "").isEmpty
        var error: NSError?
        privacy = AirGroupPrivacy(state: submanagerGroups.groupPrivacyState(for: chat, error: &error))
        voice = submanagerGroups.groupVoiceState(for: chat, error: &error)
        topicLocked = submanagerGroups.groupTopicLock(for: chat, error: &error) == .enabled
        peerLimit = submanagerGroups.groupPeerLimit(for: chat, error: &error)
        if peerLimit < 1 {
            peerLimit = kOCTDefaultGroupPeerLimit
        }
    }

    // MARK: Building

    private func build() {
        column.arrangedSubviews.forEach { $0.removeFromSuperview() }
        joinFailure.isHidden = true
        writeFailure.isHidden = true

        // Your name here, then the topic.
        let nameRow = AirSettingRow(title: String(localized: "air_group_settings_self_name"),
                                    value: selfName.isEmpty ? nil : selfName,
                                    opens: isLive)
        nameRow.accessibilityIdentifier = "air.group.settings.name"
        nameRow.isEnabled = isLive
        if !isLive {
            nameRow.accessibilityTraits = UIAccessibilityTraitStaticText
        }
        nameRow.addTarget(self, action: #selector(nameTapped), for: .touchUpInside)
        addSection(nil, rows: [nameRow], note: String(localized: "air_group_settings_self_name_note"))

        let topicRow = AirSettingRow(title: String(localized: "air_group_settings_topic"),
                                     value: topic.isEmpty ? String(localized: "air_group_settings_topic_none") : topic,
                                     opens: canEditTopic)
        topicRow.accessibilityIdentifier = "air.group.settings.topic"
        topicRow.isEnabled = canEditTopic
        if !canEditTopic {
            topicRow.accessibilityTraits = UIAccessibilityTraitStaticText
        }
        topicRow.addTarget(self, action: #selector(topicTapped), for: .touchUpInside)
        let topicNote: String
        if canEditTopic {
            topicNote = "air_group_settings_topic_note"
        }
        else {
            // An observer never changes it; with the topic open everyone else may.
            topicNote = topicLocked ? "air_group_settings_topic_locked" : "air_group_settings_topic_not_observers"
        }
        addSection(nil, rows: [topicRow], note: String(localized: topicNote))

        // Who can join.
        if isFounder {
            privacyRows = [AirGroupPrivacy.inviteOnly, AirGroupPrivacy.openById].map { option in
                let row = AirChoiceRow(title: option.title, detail: option.consequence)
                row.accessibilityIdentifier = option == .inviteOnly ? "air.group.settings.inviteOnly" : "air.group.settings.open"
                row.addTarget(self, action: #selector(privacyTapped(_:)), for: .touchUpInside)
                return row
            }
            let join = addSection(String(localized: "air_group_settings_join"), rows: privacyRows)
            join.addArrangedSubview(joinFailure)
            updateChoices()

            let passwordRow = AirSettingRow(title: String(localized: "group_password_header"),
                                            value: String(localized: hasPassword ? "air_group_settings_password_set" : "air_group_settings_password_none"))
            passwordRow.accessibilityIdentifier = "air.group.settings.password"
            passwordRow.addTarget(self, action: #selector(passwordTapped), for: .touchUpInside)
            let limitRow = AirSettingRow(title: String(localized: "air_group_settings_limit"),
                                         value: AirGroupSettingsText.number(Int(peerLimit)))
            limitRow.accessibilityIdentifier = "air.group.settings.limit"
            limitRow.addTarget(self, action: #selector(limitTapped), for: .touchUpInside)
            var note = String(localized: "air_group_settings_password_note")
            if submanagerGroups.isGroupAtPeerCapacity(for: chat) {
                note += " " + String(localized: "air_group_settings_limit_full")
            }
            addSection(nil, rows: [passwordRow, limitRow], note: note)
        }
        else {
            let row = AirSettingRow(title: privacy.title, detail: privacy.consequence, opens: false)
            row.isUserInteractionEnabled = false
            row.accessibilityTraits = UIAccessibilityTraitStaticText
            row.accessibilityIdentifier = "air.group.settings.join"
            addSection(String(localized: "air_group_settings_join"), rows: [row])
        }

        // Who can write.
        let states: [OCTToxGroupVoiceState] = [.all, .moderator, .founder]
        if isFounder {
            voiceRows = states.map { state in
                let row = AirChoiceRow(title: AirGroupSettingsText.voiceTitle(state), detail: AirGroupSettingsText.voiceDetail(state))
                row.accessibilityIdentifier = "air.group.settings.voice.\(state.rawValue)"
                row.addTarget(self, action: #selector(voiceTapped(_:)), for: .touchUpInside)
                return row
            }
            let write = addSection(String(localized: "air_group_settings_write"), rows: voiceRows)
            write.addArrangedSubview(writeFailure)
            updateChoices()

            let lock = AirSettingRow.toggle(title: String(localized: "air_group_settings_topic_lock"),
                                            isOn: topicLocked,
                                            identifier: "air.group.settings.topicLock") { [weak self] on in
                self?.topicLockChanged(on)
            }
            addSection(nil, rows: [lock])
        }
        else {
            let row = AirSettingRow(title: AirGroupSettingsText.voiceTitle(voice), detail: AirGroupSettingsText.voiceDetail(voice), opens: false)
            row.isUserInteractionEnabled = false
            row.accessibilityTraits = UIAccessibilityTraitStaticText
            row.accessibilityIdentifier = "air.group.settings.voice"
            addSection(String(localized: "air_group_settings_write"), rows: [row],
                       note: String(localized: "air_group_settings_founder_only"))
        }
    }

    private func updateChoices() {
        for (row, option) in zip(privacyRows, [AirGroupPrivacy.inviteOnly, AirGroupPrivacy.openById]) {
            row.isSelected = option == privacy
        }
        for (row, state) in zip(voiceRows, [OCTToxGroupVoiceState.all, .moderator, .founder]) {
            row.isSelected = state == voice
        }
    }

    // MARK: Changes on this screen

    @objc private func privacyTapped(_ row: AirChoiceRow) {
        guard let index = privacyRows.firstIndex(where: { $0 === row }) else {
            return
        }
        let choice: AirGroupPrivacy = index == 0 ? .inviteOnly : .openById
        guard choice != privacy else {
            return
        }
        do {
            try submanagerGroups.setGroupPrivacyState(choice == .inviteOnly ? .private : .public, for: chat)
            privacy = choice
            updateChoices()
            joinFailure.isHidden = true
            saved()
        }
        catch let error as NSError {
            show(errorAlert(for: .setGroupPrivacy, error: error), in: joinFailure)
        }
    }

    @objc private func voiceTapped(_ row: AirChoiceRow) {
        guard let index = voiceRows.firstIndex(where: { $0 === row }) else {
            return
        }
        let state = [OCTToxGroupVoiceState.all, .moderator, .founder][index]
        guard state != voice else {
            return
        }
        do {
            try submanagerGroups.setGroupVoiceState(state, for: chat)
            voice = state
            updateChoices()
            writeFailure.isHidden = true
            saved()
        }
        catch let error as NSError {
            show(errorAlert(for: .setGroupVoice, error: error), in: writeFailure)
        }
    }

    private func topicLockChanged(_ on: Bool) {
        do {
            try submanagerGroups.setGroupTopicLock(on ? .enabled : .disabled, for: chat)
            topicLocked = on
            writeFailure.isHidden = true
            saved()
        }
        catch let error as NSError {
            // The switch goes back to what the group keeps.
            reload()
            build()
            show(errorAlert(for: .setGroupTopicLock, error: error), in: writeFailure)
        }
    }

    /// A failure in red under the section it happened in (E21), its code under "Details" in the words.
    private func show(_ words: ErrorAlert?, in label: UILabel) {
        let text = [words?.title, words?.message].compactMap { $0 }.joined(separator: " ")
        label.text = text.isEmpty ? String(localized: "error_failed_group_settings") : text
        label.isHidden = false
        UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, label.text)
    }

    private func saved() {
        AirToast.show(String(localized: "air_group_settings_saved"), in: navigationController?.view ?? view)
    }

    // MARK: The values with a screen of their own

    @objc private func nameTapped() {
        open(.selfName, value: selfName)
    }

    @objc private func topicTapped() {
        guard canEditTopic else {
            return
        }
        open(.topic, value: topic)
    }

    @objc private func passwordTapped() {
        let current = ((try? submanagerGroups.groupPassword(for: chat)) ?? chat.groupPassword) ?? ""
        open(.password, value: current)
    }

    @objc private func limitTapped() {
        open(.peerLimit, value: String(peerLimit))
    }

    private func open(_ kind: AirGroupFieldController.Kind, value: String) {
        let controller = AirGroupFieldController(kind: kind, value: value, chat: chat, submanagerGroups: submanagerGroups)
        controller.onSaved = { [weak self] words in
            guard let self = self else {
                return
            }
            self.navigationController?.popToViewController(self, animated: true)
            AirToast.show(words, in: self.navigationController?.view ?? self.view)
        }
        navigationController?.pushViewController(controller, animated: true)
    }
}

/// The words the group settings use for what toxcore keeps as numbers.
enum AirGroupSettingsText {
    static func voiceTitle(_ state: OCTToxGroupVoiceState) -> String {
        switch state {
            case .all:
                return String(localized: "group_voice_all")
            case .moderator:
                return String(localized: "group_voice_moderator")
            case .founder:
                return String(localized: "group_voice_founder")
        }
    }

    static func voiceDetail(_ state: OCTToxGroupVoiceState) -> String? {
        switch state {
            case .all:
                return String(localized: "air_group_voice_all_note")
            case .moderator, .founder:
                return nil
        }
    }

    /// A count as the person's region writes it ("65 535", "65,535").
    static func number(_ value: Int) -> String {
        let formatter = NumberFormatter()
        formatter.locale = AppLanguage.locale
        formatter.numberStyle = .decimal
        return formatter.string(from: NSNumber(value: value)) ?? String(value)
    }

    /// The member limit as typed: digits in any script the keyboard gives, nil for anything else or a
    /// number outside 1...65535 (NGC keeps the limit in 16 bits).
    static func peerLimit(from text: String) -> Int32? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, trimmed.count <= 6 else {
            return nil
        }
        var value = 0
        for character in trimmed {
            // A decimal digit of any script (the Arabic keyboard gives Arabic-Indic ones).
            guard character.unicodeScalars.count == 1, let scalar = character.unicodeScalars.first,
                  CharacterSet.decimalDigits.contains(scalar), let digit = character.wholeNumberValue else {
                return nil
            }
            value = value * 10 + digit
        }
        return (1...65535).contains(value) ? Int32(value) : nil
    }
}

/// One value of the group settings on a screen of its own: the field, what the value does under it
/// and "Save". A refusal or a failure takes the place of that line, a failure with "Details".
final class AirGroupFieldController: AirScrollController {
    enum Kind {
        case selfName
        case topic
        case password
        case peerLimit

        var title: String {
            switch self {
                case .selfName:
                    return String(localized: "air_group_settings_self_name")
                case .topic:
                    return String(localized: "air_group_settings_topic")
                case .password:
                    return String(localized: "group_password_header")
                case .peerLimit:
                    return String(localized: "air_group_settings_limit")
            }
        }

        var hint: String {
            switch self {
                case .selfName:
                    return String(localized: "air_group_settings_self_name_note")
                case .topic:
                    return String(localized: "air_group_settings_topic_note")
                case .password:
                    return String(localized: "air_group_settings_password_note")
                case .peerLimit:
                    return String(localized: "air_group_limit_range")
            }
        }

        /// What toxcore takes at most, in bytes of UTF-8.
        var maxBytes: Int? {
            switch self {
                case .selfName:
                    return 128
                case .topic:
                    return 512
                case .password:
                    return 32
                case .peerLimit:
                    return nil
            }
        }

        var errorType: ErrorHandlerType {
            switch self {
                case .selfName:
                    return .setGroupSelfPeerName
                case .topic:
                    return .setGroupTopic
                case .password:
                    return .setGroupPassword
                case .peerLimit:
                    return .setGroupPeerLimit
            }
        }

        var identifier: String {
            switch self {
                case .selfName:
                    return "name"
                case .topic:
                    return "topic"
                case .password:
                    return "password"
                case .peerLimit:
                    return "limit"
            }
        }
    }

    /// The confirmation to show once the value is kept.
    var onSaved: ((String) -> Void)?

    private let kind: Kind
    private let initial: String
    private let chat: OCTChat
    private weak var submanagerGroups: OCTSubmanagerGroups?
    private let field: AirLineField
    private let note: AirFieldNote
    private let saveButton = AirButton(style: .ink, title: String(localized: "message_edit_save"))

    init(kind: Kind, value: String, chat: OCTChat, submanagerGroups: OCTSubmanagerGroups) {
        self.kind = kind
        self.initial = value
        self.chat = chat
        self.submanagerGroups = submanagerGroups
        self.field = AirLineField(placeholder: kind.title, secret: kind == .password)
        self.note = AirFieldNote(hint: kind.hint)
        super.init(nibName: nil, bundle: nil)
        title = kind.title
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.group.field." + kind.identifier
        field.text = initial
        field.maxBytes = kind.maxBytes
        field.field.accessibilityIdentifier = "air.group.field.input"
        field.field.returnKeyType = .done
        switch kind {
            case .selfName:
                field.field.textContentType = .nickname
                field.field.autocapitalizationType = .words
            case .topic:
                field.field.autocapitalizationType = .sentences
            case .password:
                field.field.textContentType = .newPassword
            case .peerLimit:
                field.field.keyboardType = .numberPad
        }
        field.onChange = { [weak self] in
            self?.note.clear()
            self?.field.hasError = false
        }
        field.onReturn = { [weak self] in
            self?.save()
        }
        addSection(nil, views: [field, note])

        saveButton.accessibilityIdentifier = "air.group.field.save"
        saveButton.addTarget(self, action: #selector(saveTapped), for: .touchUpInside)
        var buttons: [UIView] = [saveButton]
        if kind == .password && !initial.isEmpty {
            let remove = AirButton(style: .normal, title: String(localized: "air_group_password_remove"))
            remove.accessibilityIdentifier = "air.group.field.remove"
            remove.addTarget(self, action: #selector(removeTapped), for: .touchUpInside)
            buttons.append(remove)
        }
        addSection(nil, views: buttons)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        _ = field.field.becomeFirstResponder()
    }

    @objc private func saveTapped() {
        save()
    }

    @objc private func removeTapped() {
        field.text = ""
        apply("", confirmation: String(localized: "air_group_password_removed"))
    }

    private func save() {
        let text = field.text
        switch kind {
            case .selfName:
                let name = AirGroupName.clean(text)
                guard !name.isEmpty else {
                    refuse(String(localized: "air_group_name_empty"))
                    return
                }
                apply(name, confirmation: String(localized: "air_group_settings_saved"))
            case .topic:
                apply(AirGroupName.clean(text), confirmation: String(localized: "air_group_settings_saved"))
            case .password:
                apply(text, confirmation: String(localized: text.isEmpty ? "air_group_password_removed" : "air_group_settings_saved"))
            case .peerLimit:
                guard AirGroupSettingsText.peerLimit(from: text) != nil else {
                    refuse(String(localized: "air_group_limit_range"))
                    return
                }
                apply(text, confirmation: String(localized: "air_group_settings_saved"))
        }
    }

    private func refuse(_ words: String) {
        field.hasError = true
        field.shake()
        note.showRefusal(words)
    }

    private func apply(_ value: String, confirmation: String) {
        guard let submanagerGroups = submanagerGroups, !chat.isInvalidated else {
            return
        }
        do {
            switch kind {
                case .selfName:
                    try submanagerGroups.setGroupSelfPeerName(value, for: chat)
                    submanagerGroups.refreshPeers(for: chat)
                case .topic:
                    try submanagerGroups.setGroupTopic(value, for: chat)
                case .password:
                    try submanagerGroups.setGroupPassword(value.isEmpty ? nil : value, for: chat)
                case .peerLimit:
                    try submanagerGroups.setGroupPeerLimit(AirGroupSettingsText.peerLimit(from: value) ?? kOCTDefaultGroupPeerLimit, for: chat)
            }
        }
        catch let error as NSError {
            let words = errorAlert(for: kind.errorType, error: error)
            note.onDetails = words?.details.map { code in
                { UIAlertController.showDetails(code) }
            }
            field.hasError = true
            note.showError([words?.title, words?.message].compactMap { $0 }.joined(separator: " "), details: words?.details != nil)
            return
        }
        _ = field.field.resignFirstResponder()
        onSaved?(confirmation)
    }
}
