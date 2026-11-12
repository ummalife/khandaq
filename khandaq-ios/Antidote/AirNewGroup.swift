// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

/// "New group" (E15), a sheet the height of the screen: the name first, then who can join, said with
/// its consequence, then the contacts to invite (online first), and "Create group" at the bottom with
/// the reason when it waits. The group is named and closed as the founder chose before it exists.
final class AirNewGroupController: AirScrollController, UIAdaptivePresentationControllerDelegate {
    /// The name, whether the group is by invitation only, and the contacts to invite.
    var onCreate: ((String, Bool, [AirInviteCandidate]) -> Void)?
    var onCancel: (() -> Void)?

    private let candidates: [AirInviteCandidate]
    private let nameField = AirFramedTextView(role: .body, placeholder: String(localized: "air_new_group_name_placeholder"), minHeight: KQSize.targetIos)
    private let nameNote = AirKit.note(String(localized: "air_new_group_name_note"))
    private var privacy: AirGroupPrivacy
    private var choiceRows: [AirChoiceRow] = []
    private let listView = AirInviteListView()
    private let bar = UIView()
    private let createButton = AirButton(style: .ink, title: String(localized: "air_new_group_create"))
    private let whyLabel = AirKit.note("")

    /// `inviteOnly` is the answer chosen when the sheet opens.
    init(candidates: [AirInviteCandidate], inviteOnly: Bool = true) {
        self.candidates = candidates
        self.privacy = inviteOnly ? .inviteOnly : .openById
        super.init(nibName: nil, bundle: nil)
        title = String(localized: "air_new_group_title")
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        adjustsForKeyboard = false
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.newGroup.screen"

        let cancel = UIBarButtonItem(title: String(localized: "alert_cancel"), style: .plain, target: self, action: #selector(cancelTapped))
        cancel.accessibilityIdentifier = "air.newGroup.cancel"
        cancel.tintColor = KQColor.ink
        navigationItem.leftBarButtonItem = cancel

        // The name.
        nameField.textView.accessibilityIdentifier = "air.newGroup.name"
        nameField.textView.accessibilityLabel = String(localized: "air_new_group_name")
        nameField.textView.returnKeyType = .done
        nameField.textView.autocapitalizationType = .sentences
        // A name is written as the founder means it: no correction of a word the dictionary lacks.
        nameField.textView.autocorrectionType = .no
        nameField.onReturn = { [weak self] in
            _ = self?.nameField.textView.resignFirstResponder()
        }
        nameField.onChange = { [weak self] in
            self?.updateButton()
        }
        addSection(String(localized: "air_new_group_name"), views: [nameField, nameNote])

        // Who can join.
        choiceRows = [AirGroupPrivacy.inviteOnly, AirGroupPrivacy.openById].map { option in
            let row = AirChoiceRow(title: option.title, detail: option.consequence)
            row.accessibilityIdentifier = option == .inviteOnly ? "air.newGroup.privacy.inviteOnly" : "air.newGroup.privacy.open"
            row.addTarget(self, action: #selector(choiceTapped(_:)), for: .touchUpInside)
            return row
        }
        addSection(String(localized: "air_new_group_privacy"), rows: choiceRows)
        updateChoice()

        // Whom to invite.
        listView.configure(candidates: candidates)
        listView.onChange = { [weak self] in
            self?.updateButton()
        }
        addSection(String(localized: "air_group_invite"), views: [listView], note: String(localized: "air_new_group_invite_note"))

        // The main action, on a bar above the keyboard.
        bar.backgroundColor = KQColor.surface
        let rule = UIView()
        rule.backgroundColor = KQColor.line
        bar.addSubview(rule)
        createButton.accessibilityIdentifier = "air.newGroup.create"
        createButton.addTarget(self, action: #selector(createTapped), for: .touchUpInside)
        whyLabel.accessibilityIdentifier = "air.newGroup.why"
        let barColumn = AirKit.column([createButton, whyLabel], spacing: KQSpace.x6)
        bar.addSubview(barColumn)
        view.addSubview(bar)
        rule.snp.makeConstraints {
            $0.leading.trailing.top.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
        barColumn.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x8)
            $0.bottom.equalToSuperview().offset(-KQSpace.x8)
            $0.leading.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.equalTo(view.safeAreaLayoutGuide).offset(-KQSpace.x16)
        }
        createButton.snp.makeConstraints {
            $0.height.equalTo(KQSize.targetIos).priority(.medium)
        }
        bar.snp.makeConstraints {
            $0.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(view.keyboardLayoutGuide.snp.top)
        }
        scroll.snp.remakeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(bar.snp.top)
        }
        updateButton()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if nameField.text.isEmpty {
            _ = nameField.textView.becomeFirstResponder()
        }
    }

    // MARK: State

    private var cleanName: String {
        return AirGroupName.clean(nameField.text)
    }

    private func updateChoice() {
        for (row, option) in zip(choiceRows, [AirGroupPrivacy.inviteOnly, AirGroupPrivacy.openById]) {
            row.isSelected = option == privacy
        }
    }

    /// "Create group" waits for a name that fits; the line under it says why.
    private func updateButton() {
        let name = cleanName
        let why: String?
        if name.isEmpty {
            why = String(localized: "air_new_group_name_first")
        }
        else if AirGroupName.overflow(name) != nil {
            why = String(localized: "air_new_group_name_too_long")
        }
        else {
            why = nil
        }
        createButton.isEnabled = why == nil
        let count = listView.chosen.count
        createButton.setTitle(count > 0 ? String(localized: "air_new_group_create_invite_format", count) : String(localized: "air_new_group_create"))
        whyLabel.text = why
        whyLabel.isHidden = why == nil
    }

    // MARK: Actions

    @objc private func choiceTapped(_ row: AirChoiceRow) {
        guard let index = choiceRows.firstIndex(where: { $0 === row }) else {
            return
        }
        privacy = index == 0 ? .inviteOnly : .openById
        updateChoice()
    }

    @objc private func createTapped() {
        let name = cleanName
        guard !name.isEmpty, AirGroupName.overflow(name) == nil else {
            updateButton()
            return
        }
        _ = nameField.textView.resignFirstResponder()
        onCreate?(name, privacy == .inviteOnly, listView.chosen.filter { $0.canInvite })
    }

    @objc private func cancelTapped() {
        onCancel?()
    }

    /// Swiped away: the same as "Cancel", the sheet is already gone.
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        onCancel?()
    }
}

/// One answer of a choice: the title, the consequence under it when there is one, and a round mark,
/// filled while chosen.
final class AirChoiceRow: UIControl {
    private let titleLabel = AirKit.label(.body, color: KQColor.ink, lines: 0)
    private let detailLabel = AirKit.label(.caption, color: KQColor.ink3, lines: 0)
    private let mark = UIView()
    private let dot = UIView()

    init(title: String, detail: String? = nil) {
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityLabel = title
        accessibilityValue = detail
        titleLabel.text = title
        detailLabel.text = detail
        detailLabel.isHidden = (detail ?? "").isEmpty

        mark.isUserInteractionEnabled = false
        mark.layer.cornerRadius = KQSize.mark / 2
        mark.layer.borderWidth = KQStroke.strong
        dot.isUserInteractionEnabled = false
        dot.layer.cornerRadius = KQSize.dot / 2
        dot.backgroundColor = KQColor.ink
        mark.addSubview(dot)

        let texts = AirKit.column([titleLabel, detailLabel], spacing: KQSpace.x2)
        texts.isUserInteractionEnabled = false
        addSubview(mark)
        addSubview(texts)
        mark.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.top.equalTo(texts).offset(max(0, (ceil(KQFont.lineHeight(.body)) - KQSize.mark) / 2))
            $0.size.equalTo(KQSize.mark)
        }
        dot.snp.makeConstraints {
            $0.center.equalToSuperview()
            $0.size.equalTo(KQSize.dot)
        }
        // A title alone is shorter than the row: it stands in the middle at its own height instead of
        // being stretched (which pushed its words below the mark), and the mark keeps to its first line
        // either way. The row is as tall as the words and their margins, or the row height if that is more.
        for label in [titleLabel, detailLabel] {
            label.setContentHuggingPriority(.defaultHigh, for: .vertical)
        }
        texts.snp.makeConstraints {
            $0.leading.equalTo(mark.snp.trailing).offset(KQSpace.x12)
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.top.greaterThanOrEqualToSuperview().offset(KQSpace.x12)
            $0.top.equalToSuperview().offset(KQSpace.x12).priority(.low)
            $0.centerY.equalToSuperview()
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.rowDesk)
        }
        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// The words under the title, changed once the answer means something else.
    func setDetail(_ detail: String?) {
        accessibilityValue = detail
        detailLabel.text = detail
        detailLabel.isHidden = (detail ?? "").isEmpty
    }

    override var isSelected: Bool {
        didSet {
            apply()
        }
    }

    override var isHighlighted: Bool {
        didSet {
            backgroundColor = isHighlighted ? KQColor.selected : .clear
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        apply()
    }

    private func apply() {
        mark.layer.borderColor = airCGColor(isSelected ? KQColor.ink : KQColor.lineStrong)
        dot.isHidden = !isSelected
        accessibilityTraits = isSelected ? UIAccessibilityTraitButton | UIAccessibilityTraitSelected : UIAccessibilityTraitButton
    }
}

/// "Join a group" (E15) by its key, a sheet the height of the screen like "New group": the key typed,
/// pasted or scanned from the dossier's QR code, the password if the founder set one, how joining works,
/// and "Join" on a bar above the keyboard with the reason when it waits. The group is found once one of
/// its members is online; a contact of yours among them is asked for the invitation (objcTox). A key
/// of a group already on this device opens that group instead.
final class AirJoinGroupController: AirScrollController, UIAdaptivePresentationControllerDelegate {
    /// The key and the password, nil without one. Returns the words of a refusal, nil once joined.
    var onJoin: ((String, String?) -> String?)?
    var onCancel: (() -> Void)?
    /// Scans a QR code and hands back what it says.
    var onScan: ((@escaping (String) -> Void) -> Void)?
    /// The group of a key, when it is already on this device.
    var existingGroup: ((String) -> OCTChat?)?
    var onOpenExisting: ((OCTChat) -> Void)?

    private let keyField = AirFramedTextView(role: .monoEntry, placeholder: String(localized: "air_join_key_placeholder"), minHeight: KQSize.targetIos)
    private let keyNote = AirFieldNote(hint: String(localized: "air_join_key_hint"))
    private let openButton = AirQuietButton(title: String(localized: "air_join_open_group"))
    private let scanButton = AirButton(style: .normal, title: String(localized: "air_nc_scan"), icon: "scan")
    private let passwordField = AirLineField(placeholder: String(localized: "air_join_password_placeholder"), secret: true)
    private let bar = UIView()
    private let joinButton = AirButton(style: .ink, title: String(localized: "air_invite_join"))
    private let failureNote = AirFieldNote(hint: nil)
    private var verdict: AirGroupKeyVerdict = .empty

    init(key: String? = nil) {
        super.init(nibName: nil, bundle: nil)
        title = String(localized: "air_join_title")
        if let key = key {
            keyField.text = AirKeyFormat.lines(key)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        adjustsForKeyboard = false
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.joinGroup.screen"

        let cancel = UIBarButtonItem(title: String(localized: "alert_cancel"), style: .plain, target: self, action: #selector(cancelTapped))
        cancel.accessibilityIdentifier = "air.joinGroup.cancel"
        cancel.tintColor = KQColor.ink
        navigationItem.leftBarButtonItem = cancel

        // The key.
        let tv = keyField.textView
        tv.autocapitalizationType = .allCharacters
        tv.autocorrectionType = .no
        tv.spellCheckingType = .no
        tv.keyboardType = .asciiCapable
        tv.returnKeyType = .done
        tv.accessibilityIdentifier = "air.joinGroup.key"
        tv.accessibilityLabel = String(localized: "air_group_key")
        keyField.onReturn = { [weak self] in
            _ = self?.keyField.textView.resignFirstResponder()
        }
        keyField.onChange = { [weak self] in
            self?.refresh()
        }
        openButton.accessibilityIdentifier = "air.joinGroup.open"
        openButton.addTarget(self, action: #selector(openTapped), for: .touchUpInside)
        scanButton.accessibilityIdentifier = "air.joinGroup.scan"
        scanButton.addTarget(self, action: #selector(scanTapped), for: .touchUpInside)
        keyNote.accessibilityIdentifier = "air.joinGroup.keyNote"
        addSection(String(localized: "air_group_key"), views: [keyField, keyNote, AirKit.leading(openButton), AirKit.leading(scanButton)])

        // The password.
        passwordField.field.accessibilityIdentifier = "air.joinGroup.password"
        passwordField.maxBytes = 32
        passwordField.field.returnKeyType = .done
        passwordField.onReturn = { [weak self] in
            _ = self?.passwordField.field.resignFirstResponder()
        }
        passwordField.onChange = { [weak self] in
            self?.failureNote.clear()
        }
        addSection(String(localized: "group_password_header"), views: [passwordField],
                   note: String(localized: "air_join_password_note"))

        // How it works.
        let how = AirKit.body(String(localized: "air_join_how"))
        how.accessibilityIdentifier = "air.joinGroup.how"
        addSection(nil, views: [how])

        // The main action, on a bar above the keyboard.
        bar.backgroundColor = KQColor.surface
        let rule = UIView()
        rule.backgroundColor = KQColor.line
        bar.addSubview(rule)
        joinButton.accessibilityIdentifier = "air.joinGroup.join"
        joinButton.addTarget(self, action: #selector(joinTapped), for: .touchUpInside)
        failureNote.accessibilityIdentifier = "air.joinGroup.why"
        let barColumn = AirKit.column([joinButton, failureNote], spacing: KQSpace.x6)
        bar.addSubview(barColumn)
        view.addSubview(bar)
        rule.snp.makeConstraints {
            $0.leading.trailing.top.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
        barColumn.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x8)
            $0.bottom.equalToSuperview().offset(-KQSpace.x8)
            $0.leading.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.equalTo(view.safeAreaLayoutGuide).offset(-KQSpace.x16)
        }
        joinButton.snp.makeConstraints {
            $0.height.equalTo(KQSize.targetIos).priority(.medium)
        }
        // The line under "Join" keeps its room for two lines and grows only with its text; the form above
        // takes the rest of the sheet (without this the bar could take all of it).
        failureNote.snp.makeConstraints {
            $0.height.equalTo(0).priority(.low)
        }
        bar.snp.makeConstraints {
            $0.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(view.keyboardLayoutGuide.snp.top)
        }
        scroll.snp.remakeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(bar.snp.top)
        }
        refresh()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if keyField.text.isEmpty {
            _ = keyField.textView.becomeFirstResponder()
        }
    }

    // MARK: State

    private func refresh() {
        verdict = AirGroupKeyVerdict.of(keyField.text) { [weak self] key in
            self?.existingGroup?(key) != nil
        }
        if let note = verdict.note {
            keyNote.showRefusal(note)
        }
        else {
            keyNote.clear()
        }
        if case .member = verdict {
            openButton.isHidden = false
        }
        else {
            openButton.isHidden = true
        }
        failureNote.clear()
        joinButton.isEnabled = verdict.key != nil
        if verdict.key == nil {
            // "Join" waits for a key; the line under it says so, once something is there to say it about.
            failureNote.showHint(verdict == .empty ? String(localized: "air_join_why_key") : nil)
        }
    }

    // MARK: Actions

    @objc private func scanTapped() {
        view.endEditing(true)
        onScan? { [weak self] text in
            guard let self = self else {
                return
            }
            let key = GroupJoinHelper.normalizedGroupChatIdHex(from: text) ?? AirGroupKey.clean(text).text
            self.keyField.text = AirKeyFormat.lines(key)
            self.refresh()
        }
    }

    @objc private func openTapped() {
        guard case .member(let key) = verdict, let chat = existingGroup?(key) else {
            return
        }
        onOpenExisting?(chat)
    }

    @objc private func joinTapped() {
        refresh()
        guard let key = verdict.key else {
            return
        }
        view.endEditing(true)
        let password = passwordField.text.isEmpty ? nil : passwordField.text
        if let refusal = onJoin?(key, password) {
            // Said where the action is (E21).
            failureNote.showError(refusal, details: false)
        }
    }

    @objc private func cancelTapped() {
        onCancel?()
    }

    /// Swiped away: the same as "Cancel", the sheet is already gone.
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) {
        onCancel?()
    }
}
