// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// Sign-in and moving a profile in (redesign stage 4, E20). Sign-in is the profile's name, the password
// field and one main button; several profiles are a list. Why it did not open stands under the field:
// a wrong password, a damaged file or no access to it, each with what to do. A profile file is read
// before it is moved in: its password, then its name and ID, then the confirmation.

/// A profile on the sign-in screen.
struct AirSignInProfile: Equatable {
    let name: String
    /// False for a profile that opens without typing: its key is in this iPhone's keychain, or its file
    /// is not encrypted yet (it gets a password or a key first).
    let needsPassword: Bool
    /// Made without a password, and its key is not in this iPhone's keychain: it came from another
    /// iPhone, or the keychain was reset. Nothing typed here opens it (stage 5).
    var keyMissing: Bool

    init(name: String, needsPassword: Bool, keyMissing: Bool = false) {
        self.name = name
        self.needsPassword = needsPassword
        self.keyMissing = keyMissing
    }

    /// What the row of the profile says under its name.
    var detail: String? {
        if keyMissing {
            return String(localized: "air_signin_key_missing_short")
        }
        return needsPassword ? nil : String(localized: "air_signin_no_password_short")
    }
}

// MARK: - Sign-in

final class AirSignInController: AirStepController, UIContextMenuInteractionDelegate {
    var onSignIn: ((AirSignInProfile, String?) -> Void)?
    var onCreateProfile: (() -> Void)?
    var onMoveProfile: (() -> Void)?
    var onDeleteProfile: ((AirSignInProfile) -> Void)?

    private var profiles: [AirSignInProfile]
    private var selected: Int
    /// The profile's name as the title, in a field that cannot be edited: the username half of the form,
    /// so a password manager pairs the saved password with this profile (#4).
    private let nameField = UITextField()
    private let passwordField = AirLineField(placeholder: String(localized: "air_first_password_placeholder"), secret: true)
    private let note = AirFieldNote(hint: nil)
    private let forgotButton = AirQuietButton(title: String(localized: "air_signin_forgot"))
    private let createButton = AirQuietButton(title: String(localized: "air_signin_create"))
    private let moveButton = AirQuietButton(title: String(localized: "air_first_move_profile"))
    /// A profile whose key is gone: how to bring it here, and its removal (stage 5).
    private let keyMissingNote = AirKit.note(String(localized: "air_signin_key_missing_note"))
    private let deleteButton = AirQuietButton(title: String(localized: "air_signin_delete"), color: KQColor.danger)
    private var rows: [AirChoiceRow] = []
    private var passwordBox: UIStackView!
    /// The rows of Move a profile and Delete: hidden with them, so no empty line is left.
    private var moveRow: UIView!
    private var deleteRow: UIView!

    init(profiles: [AirSignInProfile], selected: Int) {
        self.profiles = profiles
        self.selected = profiles.indices.contains(selected) ? selected : 0
        super.init(title: "", body: nil, mainTitle: String(localized: "air_signin_go"))
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    var selectedProfile: AirSignInProfile? {
        return profiles.indices.contains(selected) ? profiles[selected] : nil
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.signin.screen"

        titleLabel.isHidden = true
        nameField.font = KQFont.font(.display)
        nameField.adjustsFontForContentSizeCategory = true
        nameField.textColor = KQColor.ink
        // A text field aligns "natural" text by its script, so a Latin or Cyrillic name stood at the
        // left of an Arabic page; the title keeps to the page's leading edge, as the labels do.
        nameField.textAlignment = AppLanguage.isRightToLeft ? .right : .left
        nameField.textContentType = .username
        nameField.isUserInteractionEnabled = false
        nameField.accessibilityTraits = UIAccessibilityTraitHeader
        nameField.accessibilityIdentifier = "air.signin.name"
        header.insertArrangedSubview(nameField, at: 0)

        passwordField.field.accessibilityIdentifier = "air.signin.password"
        passwordField.field.textContentType = .password
        passwordField.field.returnKeyType = .go
        passwordField.onChange = { [weak self] in
            self?.note.clear()
            self?.update()
        }
        passwordField.onReturn = { [weak self] in
            self?.mainTapped()
        }
        forgotButton.accessibilityIdentifier = "air.signin.forgot"
        forgotButton.addTarget(self, action: #selector(forgotTapped), for: .touchUpInside)
        passwordBox = AirKit.column([passwordField, note, AirKit.leading(forgotButton)], spacing: KQSpace.x6)
        column.addArrangedSubview(passwordBox)
        keyMissingNote.accessibilityIdentifier = "air.signin.keyMissing.note"
        column.addArrangedSubview(keyMissingNote)

        if profiles.count > 1 {
            rows = profiles.enumerated().map { index, profile in
                let row = AirChoiceRow(title: profile.name, detail: profile.detail)
                row.accessibilityIdentifier = "air.signin.profile.\(index)"
                row.addTarget(self, action: #selector(profileTapped(_:)), for: .touchUpInside)
                row.addInteraction(UIContextMenuInteraction(delegate: self))
                return row
            }
            addSection(String(localized: "air_signin_profiles"), rows: rows, note: String(localized: "air_signin_profiles_note"))
        }

        createButton.accessibilityIdentifier = "air.signin.create"
        createButton.addTarget(self, action: #selector(createTapped), for: .touchUpInside)
        moveButton.accessibilityIdentifier = "air.signin.move"
        moveButton.addTarget(self, action: #selector(moveTapped), for: .touchUpInside)
        deleteButton.accessibilityIdentifier = "air.signin.delete"
        deleteButton.addTarget(self, action: #selector(deleteTapped), for: .touchUpInside)
        moveRow = AirKit.leading(moveButton)
        deleteRow = AirKit.leading(deleteButton)
        column.addArrangedSubview(AirKit.column([AirKit.leading(createButton), moveRow, deleteRow], spacing: KQSpace.x4))
        applySelection()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if selectedProfile?.needsPassword == true, passwordField.text.isEmpty {
            passwordField.field.becomeFirstResponder()
        }
    }

    /// Opening the profile: the button says so and the form takes no input.
    func setBusy(_ busy: Bool) {
        mainButton.setBusy(busy ? String(localized: "air_signin_opening") : nil)
        passwordField.isUserInteractionEnabled = !busy
        for row in rows {
            row.isEnabled = !busy
        }
        for button in [forgotButton, createButton, moveButton, deleteButton] {
            button.isEnabled = !busy
        }
    }

    /// The keychain has no key for the profile chosen: the page says so and offers the ways on, instead
    /// of an error that would come back with every try.
    func showKeyMissing() {
        setBusy(false)
        guard profiles.indices.contains(selected), !profiles[selected].keyMissing else {
            return
        }
        profiles[selected].keyMissing = true
        if rows.indices.contains(selected) {
            rows[selected].setDetail(profiles[selected].detail)
        }
        applySelection()
        UIAccessibilityPostNotification(UIAccessibilityLayoutChangedNotification, bodyLabel)
    }

    /// Why the profile did not open, under the field; a wrong password also shakes it once.
    func showFailure(_ failure: ProfileOpenFailure) {
        setBusy(false)
        note.onDetails = failure.details.map { code in
            { UIAlertController.showDetails(code) }
        }
        note.showError(failure.text, details: failure.details != nil)
        passwordField.hasError = selectedProfile?.needsPassword == true
        if failure == .wrongPassword {
            passwordField.shake()
            passwordField.field.becomeFirstResponder()
            passwordField.field.selectAll(nil)
        }
    }

    private func applySelection() {
        guard let profile = selectedProfile else {
            return
        }
        nameField.text = profile.name
        nameField.accessibilityLabel = profile.name
        for (index, row) in rows.enumerated() {
            row.isSelected = index == selected
        }
        passwordField.text = ""
        passwordField.hasError = false
        note.clear()
        passwordBox.isHidden = !profile.needsPassword || profile.keyMissing
        if profile.keyMissing {
            // Said as it is, with the ways on: moving the profile in is the main one, a new profile and
            // removing this one stand under it. Log in would only fail again.
            bodyLabel.text = String(localized: "air_signin_key_missing")
        }
        else {
            bodyLabel.text = profile.needsPassword ? nil : String(localized: "air_signin_no_password")
        }
        bodyLabel.isHidden = (bodyLabel.text ?? "").isEmpty
        keyMissingNote.isHidden = !profile.keyMissing
        moveRow.isHidden = profile.keyMissing
        deleteRow.isHidden = !profile.keyMissing
        mainButton.setTitle(String(localized: profile.keyMissing ? "air_signin_key_missing_move" : "air_signin_go"))
        update()
    }

    private func update() {
        let waiting = selectedProfile?.needsPassword == true && passwordField.text.isEmpty
        setWaiting(waiting ? String(localized: "air_signin_enter_password") : nil)
    }

    override func mainTapped() {
        guard let profile = selectedProfile else {
            return
        }
        if profile.keyMissing {
            moveTapped()
            return
        }
        if profile.needsPassword, passwordField.text.isEmpty {
            update()
            return
        }
        onSignIn?(profile, profile.needsPassword ? passwordField.text : nil)
    }

    @objc private func profileTapped(_ row: AirChoiceRow) {
        guard let index = rows.firstIndex(where: { $0 === row }), index != selected else {
            return
        }
        selected = index
        applySelection()
        if selectedProfile?.needsPassword == true {
            passwordField.field.becomeFirstResponder()
        }
        else {
            view.endEditing(true)
        }
    }

    @objc private func createTapped() {
        leaveWithoutSigningIn()
        onCreateProfile?()
    }

    @objc private func moveTapped() {
        leaveWithoutSigningIn()
        onMoveProfile?()
    }

    /// The page is left for another way in: a typed password goes with it, so the system does not
    /// offer to save a password that did not open the profile.
    private func leaveWithoutSigningIn() {
        view.endEditing(true)
        if !passwordField.text.isEmpty {
            passwordField.text = ""
            passwordField.hasError = false
            note.clear()
            update()
        }
    }

    /// A password cannot be recovered: what can be done instead, said before anything is done.
    @objc private func forgotTapped() {
        guard let profile = selectedProfile else {
            return
        }
        view.endEditing(true)
        let sheet = UIAlertController(title: String(localized: "air_signin_forgot_title"),
                                      message: String(localized: "air_signin_forgot_message"),
                                      preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: String(localized: "air_signin_create"), style: .default) { [weak self] _ in
            self?.leaveWithoutSigningIn()
            self?.onCreateProfile?()
        })
        sheet.addAction(UIAlertAction(title: String(localized: "air_signin_delete"), style: .destructive) { [weak self] _ in
            self?.confirmDelete(profile)
        })
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        if let popover = sheet.popoverPresentationController {
            popover.sourceView = forgotButton
            popover.sourceRect = forgotButton.bounds
        }
        present(sheet, animated: true, completion: nil)
    }

    /// Removing a profile whose key is gone: confirmed with what goes with it, as from Forgot.
    @objc private func deleteTapped() {
        guard let profile = selectedProfile else {
            return
        }
        confirmDelete(profile, from: deleteButton)
    }

    private func confirmDelete(_ profile: AirSignInProfile, from source: UIView? = nil) {
        let sheet = AirConfirm.sheet(title: String(localized: "air_you_delete_title"),
                                     message: String(localized: "air_you_delete_message_format", profile.name),
                                     verb: String(localized: "air_you_delete_action"),
                                     source: source ?? passwordBox) { [weak self] in
            self?.leaveWithoutSigningIn()
            self?.onDeleteProfile?(profile)
        }
        present(sheet, animated: true, completion: nil)
    }

    // MARK: UIContextMenuInteractionDelegate

    func contextMenuInteraction(_ interaction: UIContextMenuInteraction, configurationForMenuAtLocation location: CGPoint) -> UIContextMenuConfiguration? {
        guard let row = interaction.view as? AirChoiceRow,
              let index = rows.firstIndex(where: { $0 === row }),
              !mainButton.isBusy else {
            return nil
        }
        let profile = profiles[index]
        return UIContextMenuConfiguration(identifier: nil, previewProvider: nil) { [weak self] _ in
            let delete = UIAction(title: String(localized: "air_signin_delete_short"), attributes: .destructive) { _ in
                self?.confirmDelete(profile)
            }
            return UIMenu(title: profile.name, children: [delete])
        }
    }
}

// MARK: - Moving a profile in

/// An encrypted profile file: its name, and the password it was saved with.
final class AirImportPasswordController: AirStepController {
    var onRead: ((String) -> Void)?

    private let passwordField = AirLineField(placeholder: String(localized: "air_first_password_placeholder"), secret: true)
    private let note = AirFieldNote(hint: nil)

    init(fileName: String) {
        super.init(title: String(localized: "air_import_title"),
                   body: String(localized: "air_import_password_body_format", fileName),
                   mainTitle: String(localized: "air_first_next"))
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.import.password.screen"
        passwordField.field.accessibilityIdentifier = "air.import.password"
        passwordField.field.textContentType = .password
        passwordField.field.returnKeyType = .next
        passwordField.onChange = { [weak self] in
            self?.note.clear()
            self?.update()
        }
        passwordField.onReturn = { [weak self] in
            self?.mainTapped()
        }
        column.addArrangedSubview(AirKit.column([passwordField, note], spacing: KQSpace.x6))
        update()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if passwordField.text.isEmpty {
            passwordField.field.becomeFirstResponder()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // Back to sign-in or another file: a password that did not open the file is not offered to be saved.
        if isMovingFromParentViewController {
            passwordField.text = ""
        }
    }

    func setBusy(_ busy: Bool) {
        mainButton.setBusy(busy ? String(localized: "air_import_reading") : nil)
        passwordField.isUserInteractionEnabled = !busy
        navigationItem.hidesBackButton = busy
    }

    func showFailure(_ failure: ProfileOpenFailure) {
        setBusy(false)
        note.onDetails = failure.details.map { code in
            { UIAlertController.showDetails(code) }
        }
        note.showError(failure == .wrongPassword ? String(localized: "air_import_wrong_password") : failure.text,
                       details: failure.details != nil)
        passwordField.hasError = true
        if failure == .wrongPassword {
            passwordField.shake()
            passwordField.field.becomeFirstResponder()
            passwordField.field.selectAll(nil)
        }
    }

    private func update() {
        setWaiting(passwordField.text.isEmpty ? String(localized: "air_signin_enter_password") : nil)
    }

    override func mainTapped() {
        guard !passwordField.text.isEmpty else {
            update()
            return
        }
        view.endEditing(true)
        onRead?(passwordField.text)
    }
}

/// What the file holds, before it is moved in: the name and the ID.
final class AirImportPreviewController: AirStepController {
    var onConfirm: (() -> Void)?
    var onOtherFile: (() -> Void)?

    private let preview: ProfileFilePreview
    private let idView = AirIdPartsView()
    private let note = AirFieldNote(hint: nil)
    private let otherButton = AirQuietButton(title: String(localized: "air_import_other_file"))

    /// `mainTitle`: "Move the profile here", or "Next" when the password comes after.
    init(preview: ProfileFilePreview, mainTitle: String) {
        self.preview = preview
        super.init(title: String(localized: "air_import_preview_title"),
                   body: String(localized: "air_import_preview_body"),
                   mainTitle: mainTitle)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.import.preview.screen"
        let nameTitle = AirKit.label(.caption, color: KQColor.ink3)
        nameTitle.text = String(localized: "air_import_name")
        nameTitle.isAccessibilityElement = false
        let nameValue = AirKit.label(.heading, color: KQColor.ink, lines: 0)
        nameValue.text = preview.name.isEmpty ? String(localized: "air_import_no_name") : preview.name
        nameValue.accessibilityLabel = String(localized: "air_import_name")
        nameValue.accessibilityValue = nameValue.text
        nameValue.accessibilityIdentifier = "air.import.preview.name"
        let nameBlock = AirKit.column([nameTitle, nameValue], spacing: KQSpace.x4)
        idView.configure(address: preview.address)
        idView.accessibilityIdentifier = "air.import.preview.id"
        otherButton.accessibilityIdentifier = "air.import.other"
        otherButton.addTarget(self, action: #selector(otherTapped), for: .touchUpInside)
        column.addArrangedSubview(nameBlock)
        column.addArrangedSubview(idView)
        column.addArrangedSubview(AirKit.column([note, AirKit.leading(otherButton)], spacing: KQSpace.x4))
    }

    func setBusy(_ busy: Bool) {
        mainButton.setBusy(busy ? String(localized: "air_import_moving") : nil)
        otherButton.isEnabled = !busy
        navigationItem.hidesBackButton = busy
    }

    func showFailure(_ failure: ProfileOpenFailure) {
        setBusy(false)
        note.onDetails = failure.details.map { code in
            { UIAlertController.showDetails(code) }
        }
        note.showError(failure.text, details: failure.details != nil)
    }

    override func mainTapped() {
        onConfirm?()
    }

    @objc private func otherTapped() {
        onOtherFile?()
    }
}

/// A file that cannot be moved in: what is wrong with it, and another file.
final class AirImportProblemController: AirStepController {
    var onOtherFile: (() -> Void)?

    private let details: String?
    private let note = AirFieldNote(hint: nil)
    private let problem: String

    init(problem: String, details: String?) {
        self.problem = problem
        self.details = details
        super.init(title: String(localized: "air_import_title"), body: nil, mainTitle: String(localized: "air_import_other_file"))
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.import.problem.screen"
        column.addArrangedSubview(note)
        note.onDetails = details.map { code in
            { UIAlertController.showDetails(code) }
        }
        note.showError(problem, details: details != nil)
    }

    override func mainTapped() {
        onOtherFile?()
    }
}
