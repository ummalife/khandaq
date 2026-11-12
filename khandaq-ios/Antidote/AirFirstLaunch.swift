// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// First launch (redesign stage 4, E19): one path of three steps, each a page at the full height of the
// phone. The name; the password, each choice with what it means; then the ID. The pages live in the
// login navigation stack, so they slide in the direction of travel (a cross-fade with reduced motion),
// the progress of the three steps stands in the bar above them, and the main button waits above the
// keyboard with the reason it waits.

// MARK: - The page

extension UIView {
    /// One sideways shake: a wrong password or PIN. Nothing moves with reduced motion.
    func airShakeOnce() {
        guard !UIAccessibilityIsReduceMotionEnabled() else {
            return
        }
        let shake = CAKeyframeAnimation(keyPath: "transform.translation.x")
        shake.values = [0, -KQSpace.x6, KQSpace.x6, -KQSpace.x4, KQSpace.x2, 0]
        shake.keyTimes = [0, 0.2, 0.45, 0.65, 0.85, 1]
        shake.duration = KQMotion.duration(KQMotion.reveal)
        shake.timingFunction = CAMediaTimingFunction(name: kCAMediaTimingFunctionEaseOut)
        layer.add(shake, forKey: "shake")
    }
}

/// A page of first launch, sign-in and import: its title and words at the top of a scrolling column,
/// the main action on a bar above the keyboard, and the step of first launch in the navigation bar.
class AirStepController: AirScrollController {
    let titleLabel = AirKit.label(.display, color: KQColor.ink, lines: 0)
    let bodyLabel = AirKit.label(.body, color: KQColor.ink2, lines: 0)
    let mainButton: AirButton
    let whyLabel = AirKit.note("")
    let bar = UIView()
    /// The title and the words under it, the first thing in the column.
    private(set) var header: UIStackView!
    /// The step of first launch, 1 to 3; nil shows no progress.
    let step: Int?
    private let progress: AirStepProgressView?
    /// The field being typed into, kept in sight with the note under it when the keyboard raises the bar.
    private weak var focusedField: UIView?
    private var revealPending = false
    private var lastVisibleHeight: CGFloat = 0
    private var contentSizeObservation: NSKeyValueObservation?

    init(title: String, body: String?, mainTitle: String, step: Int? = nil) {
        mainButton = AirButton(style: .ink, title: mainTitle)
        self.step = step
        progress = step.map { AirStepProgressView(step: $0) }
        super.init(nibName: nil, bundle: nil)
        titleLabel.text = title
        bodyLabel.text = body
        bodyLabel.isHidden = (body ?? "").isEmpty
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        adjustsForKeyboard = false
        super.viewDidLoad()
        navigationItem.titleView = progress
        titleLabel.accessibilityTraits = UIAccessibilityTraitHeader
        titleLabel.accessibilityIdentifier = "air.step.title"
        header = AirKit.column([titleLabel, bodyLabel], spacing: KQSpace.x8)
        column.addArrangedSubview(header)
        installBar()
        setWaiting(nil)
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(fieldBeganEditing(_:)),
                                               name: .UITextFieldTextDidBeginEditing,
                                               object: nil)
        // Once the keyboard has settled, what the bar covers is final, also in a sheet.
        NotificationCenter.default.addObserver(self,
                                               selector: #selector(keyboardSettled),
                                               name: .UIKeyboardDidShow,
                                               object: nil)
        // An error under the field can take more lines than the room kept for it at large text sizes.
        contentSizeObservation = scroll.observe(\.contentSize, options: [.old, .new]) { [weak self] _, change in
            guard change.oldValue?.height != change.newValue?.height else {
                return
            }
            DispatchQueue.main.async {
                self?.revealFocusedField()
            }
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        progress?.fillCurrent(animated: animated)
        LaunchRecovery.markLaunchCompleted()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let height = scroll.bounds.height
        if height != lastVisibleHeight {
            lastVisibleHeight = height
            revealPending = true
        }
        guard revealPending, let field = focusedField, field.isFirstResponder else {
            return
        }
        revealPending = false
        // After this pass, when the column has its final frames.
        DispatchQueue.main.async { [weak self] in
            self?.revealFocusedField()
        }
    }

    @objc private func fieldBeganEditing(_ note: Notification) {
        guard let field = note.object as? UIView, field.isDescendant(of: scroll) else {
            return
        }
        focusedField = field
        revealPending = true
        view.setNeedsLayout()
    }

    @objc private func keyboardSettled() {
        revealFocusedField()
    }

    /// The bar follows the keyboard and the column scrolls above it: the field being typed into and
    /// the note under it come into sight with a little room around them, also at the largest text sizes.
    private func revealFocusedField() {
        guard let field = focusedField, field.isFirstResponder, field.window != nil else {
            return
        }
        var rect = field.convert(field.bounds, to: scroll)
        if let note = noteUnder(field) {
            rect = rect.union(note.convert(note.bounds, to: scroll))
        }
        let target = rect.insetBy(dx: 0, dy: -KQSpace.x16)
        // The offset is worked out here, not left to scrollRectToVisible: a target taller than the room
        // above the bar has to keep its top, the field, in sight.
        let inset = scroll.adjustedContentInset
        let visibleHeight = scroll.bounds.height - inset.top - inset.bottom
        var offset = scroll.contentOffset.y
        if target.maxY > offset + inset.top + visibleHeight {
            offset = target.maxY - inset.top - visibleHeight
        }
        // A target taller than what the bar leaves keeps its top, the field, in sight.
        if target.minY < offset + inset.top {
            offset = target.minY - inset.top
        }
        let lowest = -inset.top
        let highest = max(lowest, scroll.contentSize.height + inset.bottom - scroll.bounds.height)
        offset = min(max(offset, lowest), highest)
        guard abs(offset - scroll.contentOffset.y) > 0.5 else {
            return
        }
        scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: offset), animated: !UIAccessibilityIsReduceMotionEnabled())
    }

    /// The note under the field in its column: right under it, or under the fields that follow it there
    /// (a new password and its repeat share the rule under them).
    private func noteUnder(_ field: UIView) -> AirFieldNote? {
        var current = field
        while let parent = current.superview, parent !== scroll {
            if let stack = parent as? UIStackView, let index = stack.arrangedSubviews.firstIndex(of: current) {
                for next in stack.arrangedSubviews.dropFirst(index + 1) where !next.isHidden {
                    if let note = next as? AirFieldNote {
                        return note
                    }
                    if !(next is AirLineField) {
                        return nil
                    }
                }
                return nil
            }
            current = parent
        }
        return nil
    }

    /// The main action waits while `reason` is set, and the line under it says why.
    func setWaiting(_ reason: String?) {
        mainButton.isEnabled = reason == nil
        whyLabel.text = reason
        whyLabel.isHidden = reason == nil
    }

    /// The main action; pages override it.
    @objc func mainTapped() {
    }

    private func installBar() {
        bar.backgroundColor = KQColor.bg
        let rule = UIView()
        rule.backgroundColor = KQColor.line
        bar.addSubview(rule)
        mainButton.accessibilityIdentifier = "air.step.main"
        mainButton.addTarget(self, action: #selector(mainTapped), for: .touchUpInside)
        whyLabel.accessibilityIdentifier = "air.step.why"
        let barColumn = AirKit.column([mainButton, whyLabel], spacing: KQSpace.x6)
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
        mainButton.snp.makeConstraints {
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
    }
}

// MARK: - Progress

/// The three steps of first launch as three short bars (.kq-bar): the steps behind are filled, the
/// current one fills from its leading end when its page has come in.
final class AirStepProgressView: UIView {
    static let stepCount = 3

    private let step: Int
    private var tracks: [AirProgressTrack] = []

    init(step: Int) {
        self.step = step
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityLabel = String(localized: "air_first_step_format", step, AirStepProgressView.stepCount)
        accessibilityIdentifier = "air.step.progress"
        let row = UIStackView()
        row.axis = .horizontal
        row.spacing = KQSpace.x6
        row.distribution = .fillEqually
        for index in 1...AirStepProgressView.stepCount {
            let track = AirProgressTrack()
            track.isFilled = index < step
            tracks.append(track)
            row.addArrangedSubview(track)
            track.snp.makeConstraints {
                $0.width.equalTo(KQSpace.x32)
                $0.height.equalTo(KQSize.bar)
            }
        }
        addSubview(row)
        row.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override var intrinsicContentSize: CGSize {
        let count = CGFloat(AirStepProgressView.stepCount)
        return CGSize(width: count * KQSpace.x32 + (count - 1) * KQSpace.x6, height: KQSize.bar)
    }

    func fillCurrent(animated: Bool) {
        guard tracks.indices.contains(step - 1) else {
            return
        }
        tracks[step - 1].setFilled(true, animated: animated)
    }
}

/// One bar of the progress: a sunken track and an ink fill that slides in from the leading end
/// (transform only; the track clips it).
final class AirProgressTrack: UIView {
    private let fill = UIView()
    var isFilled = false {
        didSet {
            setNeedsLayout()
        }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.sunken
        layer.cornerRadius = KQSize.bar / 2
        clipsToBounds = true
        fill.backgroundColor = KQColor.ink
        fill.layer.cornerRadius = KQSize.bar / 2
        addSubview(fill)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        fill.transform = .identity
        fill.frame = bounds
        fill.transform = restingTransform
    }

    func setFilled(_ filled: Bool, animated: Bool) {
        guard filled != isFilled else {
            return
        }
        isFilled = filled
        layoutIfNeeded()
        guard animated, !UIAccessibilityIsReduceMotionEnabled() else {
            fill.transform = restingTransform
            return
        }
        fill.transform = filled ? emptyTransform : .identity
        UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), delay: 0, options: [.curveEaseOut], animations: {
            self.fill.transform = self.restingTransform
        }, completion: nil)
    }

    private var restingTransform: CGAffineTransform {
        return isFilled ? .identity : emptyTransform
    }

    /// The fill pushed out past the leading end.
    private var emptyTransform: CGAffineTransform {
        let leftToRight = effectiveUserInterfaceLayoutDirection == .leftToRight
        return CGAffineTransform(translationX: leftToRight ? -bounds.width : bounds.width, y: 0)
    }
}

// MARK: - Fields

/// A one-line field (.kq-input): the surface with a strong hairline, ink while it has focus, red while
/// an error stands under it. A secret field has the eye that shows what was typed.
final class AirLineField: UIView, UITextFieldDelegate {
    let field = UITextField()
    var onChange: (() -> Void)?
    var onReturn: (() -> Void)?
    /// Tox carries names in bytes of UTF-8: a name field stops there.
    var maxBytes: Int?

    var hasError = false {
        didSet {
            applyColors()
        }
    }

    var text: String {
        get {
            return field.text ?? ""
        }
        set {
            field.text = newValue
        }
    }

    private var eye: AirIconButton?

    init(placeholder: String, secret: Bool) {
        super.init(frame: .zero)
        layer.cornerRadius = KQRadius.field
        layer.borderWidth = KQStroke.hairline

        field.font = KQFont.font(.body)
        field.adjustsFontForContentSizeCategory = true
        // "Natural" follows the script of the text, and a secure field stays left to right: the field
        // keeps to the page's leading edge instead, as the labels around it do.
        field.textAlignment = AppLanguage.isRightToLeft ? .right : .left
        field.textColor = KQColor.ink
        field.tintColor = KQColor.ink
        field.attributedPlaceholder = NSAttributedString(string: placeholder,
                                                         attributes: [NSAttributedStringKey.foregroundColor: KQColor.ink3])
        field.accessibilityLabel = placeholder
        field.delegate = self
        field.addTarget(self, action: #selector(changed), for: .editingChanged)
        addSubview(field)

        if secret {
            field.isSecureTextEntry = true
            field.autocorrectionType = .no
            field.autocapitalizationType = .none
            field.spellCheckingType = .no
            field.smartQuotesType = .no
            field.smartDashesType = .no
            let eye = AirIconButton(icon: "eye", label: String(localized: "air_field_show_password"))
            eye.addTarget(self, action: #selector(toggleSecret), for: .touchUpInside)
            addSubview(eye)
            eye.snp.makeConstraints {
                $0.centerY.equalToSuperview()
                $0.trailing.equalToSuperview()
            }
            self.eye = eye
        }
        field.snp.makeConstraints {
            $0.top.bottom.equalToSuperview()
            $0.leading.equalToSuperview().offset(KQSpace.x12)
            if let eye = eye {
                $0.trailing.equalTo(eye.snp.leading)
            }
            else {
                $0.trailing.equalToSuperview().offset(-KQSpace.x12)
            }
            $0.height.greaterThanOrEqualTo(KQSize.targetIos)
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

    /// Shakes once sideways (a wrong password); nothing moves with reduced motion.
    func shake() {
        airShakeOnce()
    }

    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange, replacementString string: String) -> Bool {
        guard let maxBytes = maxBytes else {
            return true
        }
        let current = textField.text ?? ""
        guard let swiftRange = Range(range, in: current) else {
            return false
        }
        let next = current.replacingCharacters(in: swiftRange, with: string)
        return next.utf8.count <= maxBytes
    }

    func textFieldDidBeginEditing(_ textField: UITextField) {
        applyColors()
    }

    func textFieldDidEndEditing(_ textField: UITextField) {
        applyColors()
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
        onReturn?()
        return false
    }

    @objc private func changed() {
        if hasError {
            hasError = false
        }
        onChange?()
    }

    @objc private func toggleSecret() {
        let typed = field.text
        field.isSecureTextEntry.toggle()
        // Switching the secrecy while typing drops the text on the next key; it is put back.
        if field.isFirstResponder {
            field.text = nil
            field.text = typed
        }
        let shown = !field.isSecureTextEntry
        eye?.restingTint = shown ? KQColor.ink : KQColor.ink2
        eye?.accessibilityLabel = String(localized: shown ? "air_field_hide_password" : "air_field_show_password")
    }

    private func applyColors() {
        let focused = field.isEditing
        backgroundColor = KQColor.surface
        let line = hasError ? KQColor.danger : (focused ? KQColor.ink : KQColor.lineStrong)
        layer.borderColor = airCGColor(line)
    }
}

/// The line under a field: a quiet hint, a refusal of a rule in the ordinary colour, or an error in red
/// with "Details" when it has a code (E21). Room for two lines is kept, so a message coming does not
/// push what is below; it fades in from 4 points lower.
final class AirFieldNote: UIView {
    var onDetails: (() -> Void)?

    private let label = AirKit.label(.caption, color: KQColor.ink3, lines: 0)
    private let detailsButton = AirQuietButton(title: String(localized: "error_details"))
    private var hint: String?

    init(hint: String?) {
        self.hint = hint
        super.init(frame: .zero)
        label.accessibilityIdentifier = "air.field.note"
        detailsButton.accessibilityIdentifier = "air.field.details"
        detailsButton.isHidden = true
        detailsButton.addTarget(self, action: #selector(detailsTapped), for: .touchUpInside)
        let stack = AirKit.column([label, AirKit.leading(detailsButton)], spacing: KQSpace.x2)
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.bottom.lessThanOrEqualToSuperview()
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(2 * ceil(KQFont.lineHeight(.caption)) + KQSpace.x2)
        }
        showHint(hint)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func showHint(_ text: String?) {
        hint = text
        set(text, color: KQColor.ink3, details: false, animated: false)
    }

    /// A rule the input does not meet yet: what is wrong and what to do, in the ordinary colour.
    func showRefusal(_ text: String) {
        set(text, color: KQColor.ink2, details: false, animated: true)
    }

    /// Something failed: in red, with "Details" when there is a code for support.
    func showError(_ text: String, details: Bool) {
        set(text, color: KQColor.danger, details: details, animated: true)
        UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, text)
    }

    func clear() {
        set(hint, color: KQColor.ink3, details: false, animated: false)
    }

    private func set(_ text: String?, color: UIColor, details: Bool, animated: Bool) {
        let changed = label.text != text || label.textColor != color
        label.text = text
        label.textColor = color
        label.isHidden = (text ?? "").isEmpty
        detailsButton.isHidden = !details
        guard animated, changed else {
            return
        }
        alpha = 0
        let reduce = UIAccessibilityIsReduceMotionEnabled()
        transform = reduce ? .identity : CGAffineTransform(translationX: 0, y: KQSpace.x4)
        UIView.animate(withDuration: KQMotion.duration(KQMotion.state), delay: 0, options: [.curveEaseOut], animations: {
            self.alpha = 1
            self.transform = .identity
        }, completion: nil)
    }

    @objc private func detailsTapped() {
        onDetails?()
    }
}

// MARK: - Step 1: the name

/// "What should we call you": one field, who sees the name, and the quieter way in for a profile that
/// already exists on another device.
final class AirFirstNameController: AirStepController {
    var onNext: ((String) -> Void)?
    var onMoveProfile: (() -> Void)?

    let nameField = AirLineField(placeholder: String(localized: "air_first_name_placeholder"), secret: false)
    private let moveButton = AirQuietButton(title: String(localized: "air_first_move_profile"))

    init(name: String? = nil) {
        super.init(title: String(localized: "air_first_name_title"),
                   body: String(localized: "air_first_name_body"),
                   mainTitle: String(localized: "air_first_next"),
                   step: 1)
        nameField.text = name ?? ""
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.first.name.screen"
        nameField.field.accessibilityIdentifier = "air.first.name"
        nameField.field.textContentType = .nickname
        nameField.field.autocapitalizationType = .words
        nameField.field.autocorrectionType = .no
        nameField.field.returnKeyType = .next
        nameField.maxBytes = Int(kOCTToxMaxNameLength)
        nameField.onChange = { [weak self] in
            self?.update()
        }
        nameField.onReturn = { [weak self] in
            self?.mainTapped()
        }
        moveButton.accessibilityIdentifier = "air.first.move"
        moveButton.addTarget(self, action: #selector(moveTapped), for: .touchUpInside)
        column.addArrangedSubview(nameField)
        column.addArrangedSubview(AirKit.leading(moveButton))
        update()
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if nameField.text.isEmpty {
            nameField.field.becomeFirstResponder()
        }
    }

    var cleanName: String {
        return nameField.text.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func update() {
        setWaiting(cleanName.isEmpty ? String(localized: "air_first_name_first") : nil)
    }

    override func mainTapped() {
        let name = cleanName
        guard !name.isEmpty else {
            update()
            return
        }
        onNext?(name)
    }

    @objc private func moveTapped() {
        view.endEditing(true)
        onMoveProfile?()
    }
}

// MARK: - Step 2: the password

/// A new password, here and where the password of an open profile is set or changed.
enum AirNewPassword {
    /// Why the password cannot be taken yet, in the words under the field; nil when it can.
    static func reason(_ first: String, repeated second: String) -> String? {
        if first.isEmpty {
            return String(localized: "air_first_password_empty")
        }
        if let failure = PasswordPolicy.validate(first) {
            switch failure {
                case .empty:
                    return String(localized: "air_first_password_empty")
                case .tooShort:
                    return String(localized: "air_first_password_short")
                case .missingLetter, .missingDigit:
                    return String(localized: "air_first_password_weak")
            }
        }
        if second.isEmpty {
            return String(localized: "air_first_password_repeat_first")
        }
        if first != second {
            return String(localized: "air_first_password_mismatch")
        }
        return nil
    }

    /// A password manager offers a strong password that already meets the rule.
    static func prepare(_ field: AirLineField) {
        field.field.textContentType = .newPassword
        field.field.passwordRules = UITextInputPasswordRules(descriptor: "minlength: \(PasswordPolicy.minimumLength); required: lower, upper; required: digit;")
    }
}

/// "Profile password": that a password cannot be recovered, then the two choices, each with what it
/// means for the profile on this iPhone, said before choosing. Also opens a profile file without a
/// password (an import, or an old profile) before it is moved in or opened, without the step.
final class AirPasswordChoiceController: AirStepController {
    enum Choice: Equatable {
        case password(String)
        case deviceKey
    }

    var onChoose: ((Choice) -> Void)?

    private var withPassword = true
    private let setRow = AirChoiceRow(title: String(localized: "air_first_password_set"),
                                      detail: String(localized: "air_first_password_set_detail"))
    private let skipRow = AirChoiceRow(title: String(localized: "air_first_password_skip"),
                                       detail: String(localized: "air_first_password_skip_detail"))
    private let passwordField = AirLineField(placeholder: String(localized: "air_first_password_placeholder"), secret: true)
    private let repeatField = AirLineField(placeholder: String(localized: "air_first_repeat_placeholder"), secret: true)
    private let note = AirFieldNote(hint: String(localized: "air_first_password_rule"))
    private var fields: UIStackView!

    init(step: Int?, mainTitle: String) {
        super.init(title: String(localized: "air_first_password_title"),
                   body: String(localized: "air_first_password_body"),
                   mainTitle: mainTitle,
                   step: step)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.first.password.screen"

        setRow.accessibilityIdentifier = "air.first.password.set"
        skipRow.accessibilityIdentifier = "air.first.password.skip"
        for row in [setRow, skipRow] {
            row.addTarget(self, action: #selector(choiceTapped(_:)), for: .touchUpInside)
        }
        let rows = AirKit.column([setRow, skipRow], spacing: 0)
        let plate = UIView()
        plate.backgroundColor = KQColor.surface
        plate.layer.cornerRadius = KQRadius.card
        plate.clipsToBounds = true
        plate.addSubview(rows)
        rows.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        column.addArrangedSubview(plate)

        passwordField.field.accessibilityIdentifier = "air.first.password"
        repeatField.field.accessibilityIdentifier = "air.first.password.repeat"
        for field in [passwordField, repeatField] {
            AirNewPassword.prepare(field)
            field.onChange = { [weak self] in
                self?.update()
            }
        }
        passwordField.field.returnKeyType = .next
        repeatField.field.returnKeyType = .done
        passwordField.onReturn = { [weak self] in
            self?.repeatField.field.becomeFirstResponder()
        }
        repeatField.onReturn = { [weak self] in
            self?.mainTapped()
        }
        fields = AirKit.column([passwordField, repeatField, note], spacing: KQSpace.x8)
        fields.setCustomSpacing(KQSpace.x6, after: repeatField)
        column.addArrangedSubview(fields)
        updateChoice()
    }

    /// Creating or opening the profile: the button says so and nothing takes taps.
    func setBusy(_ words: String?) {
        mainButton.setBusy(words)
        for row in [setRow, skipRow] {
            row.isEnabled = words == nil
        }
        fields.isUserInteractionEnabled = words == nil
        navigationItem.hidesBackButton = words != nil
    }

    /// The profile was not made: what happened, under the choice.
    func showFailure(_ text: String, details: String?) {
        setBusy(nil)
        note.onDetails = details.map { code in
            { UIAlertController.showDetails(code) }
        }
        if !withPassword {
            // The key needs the keychain; with a password nothing does.
            withPassword = true
            updateChoice()
        }
        note.showError(text, details: details != nil)
    }

    private var reason: String? {
        guard withPassword else {
            return nil
        }
        return AirNewPassword.reason(passwordField.text, repeated: repeatField.text)
    }

    private func update() {
        setWaiting(reason)
        note.clear()
    }

    private func updateChoice() {
        setRow.isSelected = withPassword
        skipRow.isSelected = !withPassword
        let show = withPassword
        if show {
            fields.isHidden = false
        }
        UIView.animate(withDuration: KQMotion.duration(KQMotion.state), animations: {
            self.fields.alpha = show ? 1 : 0
        }, completion: { _ in
            self.fields.isHidden = !self.withPassword
        })
        update()
    }

    @objc private func choiceTapped(_ row: AirChoiceRow) {
        withPassword = row === setRow
        if !withPassword {
            view.endEditing(true)
        }
        updateChoice()
        if withPassword, passwordField.text.isEmpty {
            passwordField.field.becomeFirstResponder()
        }
    }

    override func mainTapped() {
        guard reason == nil else {
            update()
            return
        }
        view.endEditing(true)
        if !withPassword {
            // Nothing half-typed stays behind for the system to offer to save.
            passwordField.text = ""
            repeatField.text = ""
        }
        onChoose?(withPassword ? .password(passwordField.text) : .deviceKey)
    }
}

// MARK: - Step 3: the ID

/// "Your ID is ready": the QR code and the ID in its three parts, whose groups come in one after
/// another when the page has come in (at once with reduced motion or VoiceOver), Share, and the way
/// into the app: adding the first person in the command line.
final class AirIdReadyController: AirStepController {
    var onAddFirstPerson: (() -> Void)?

    private let address: String
    private let qr = AirQRView()
    private let idView = AirIdAssemblyView()
    private let shareButton = AirButton(style: .normal, title: String(localized: "air_first_share"))
    private var assembled = false

    init(address: String) {
        self.address = address
        super.init(title: String(localized: "air_first_id_title"),
                   body: String(localized: "air_first_id_body"),
                   mainTitle: String(localized: "air_first_add_person"),
                   step: 3)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.first.id.screen"
        navigationItem.hidesBackButton = true
        qr.text = address
        let qrRow = UIView()
        qrRow.addSubview(qr)
        qr.snp.makeConstraints {
            $0.top.bottom.centerX.equalToSuperview()
            $0.width.equalToSuperview().multipliedBy(0.5)
        }
        idView.configure(address: address)
        idView.prepareToAssemble()
        shareButton.accessibilityIdentifier = "air.first.share"
        shareButton.addTarget(self, action: #selector(shareTapped), for: .touchUpInside)
        column.addArrangedSubview(qrRow)
        column.addArrangedSubview(idView)
        column.addArrangedSubview(shareButton)
        column.setCustomSpacing(KQSpace.x12, after: idView)
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        guard !assembled else {
            return
        }
        assembled = true
        idView.assemble(animated: !UIAccessibilityIsReduceMotionEnabled() && !UIAccessibilityIsVoiceOverRunning())
    }

    override func mainTapped() {
        onAddFirstPerson?()
    }

    @objc private func shareTapped() {
        let share = UIActivityViewController(activityItems: [address], applicationActivities: nil)
        share.popoverPresentationController?.sourceView = shareButton
        share.popoverPresentationController?.sourceRect = shareButton.bounds
        present(share, animated: true, completion: nil)
    }
}

/// The ID in its three named parts, key, antispam and check, in groups of four, left to right in every
/// language. Each group is a label of its own, so the key can come together group by group.
final class AirIdAssemblyView: UIView {
    static let groupsPerLine = 4

    private var groups: [UILabel] = []
    private var parts: [(title: UILabel, lines: UIStackView, element: UIView)] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.surface
        layer.cornerRadius = KQRadius.card
        accessibilityIdentifier = "air.first.id"
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(address: String) {
        subviews.forEach { $0.removeFromSuperview() }
        groups = []
        guard let split = AirYouId.parts(of: address) else {
            return
        }
        let named = [
            (String(localized: "air_you_part_key"), split.key),
            (String(localized: "air_you_part_antispam"), split.antispam),
            (String(localized: "air_you_part_check"), split.check),
        ]
        var rows: [UIView] = []
        for (name, value) in named {
            rows.append(part(name, AirKeyFormat.groups(value)))
        }
        let stack = AirKit.column(rows, spacing: KQSpace.x12)
        stack.semanticContentAttribute = .forceLeftToRight
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview().inset(KQSpace.x16)
        }
    }

    /// Every group out of sight, a little lower, before the page comes in.
    func prepareToAssemble() {
        for group in groups {
            group.alpha = 0
            group.transform = CGAffineTransform(translationX: 0, y: KQSpace.x4)
        }
    }

    /// The groups come in one after another (stagger), or all at once.
    func assemble(animated: Bool) {
        guard animated else {
            for group in groups {
                group.alpha = 1
                group.transform = .identity
            }
            return
        }
        for (index, group) in groups.enumerated() {
            UIView.animate(withDuration: KQMotion.duration(KQMotion.state),
                           delay: Double(index) * KQMotion.stagger,
                           options: [.curveEaseOut],
                           animations: {
                group.alpha = 1
                group.transform = .identity
            }, completion: nil)
        }
    }

    private func part(_ name: String, _ values: [String]) -> UIView {
        let title = AirKit.label(.caption, color: KQColor.ink3)
        title.text = name
        var lines: [UIView] = []
        var index = 0
        while index < values.count {
            let line = UIStackView()
            line.axis = .horizontal
            line.spacing = KQSpace.x8
            line.semanticContentAttribute = .forceLeftToRight
            for value in values[index..<min(index + AirIdAssemblyView.groupsPerLine, values.count)] {
                let group = AirKit.label(.mono, color: KQColor.ink)
                group.text = value
                group.isAccessibilityElement = false
                groups.append(group)
                line.addArrangedSubview(group)
            }
            lines.append(AirKit.leading(line))
            index += AirIdAssemblyView.groupsPerLine
        }
        let column = AirKit.column([title] + lines, spacing: KQSpace.x4)
        column.isAccessibilityElement = true
        column.accessibilityLabel = name
        column.accessibilityValue = values.joined(separator: " ")
        title.isAccessibilityElement = false
        return column
    }
}

// MARK: - Transitions

/// With reduced motion the pages of first launch and sign-in cross-fade instead of sliding.
final class AirFadeTransition: NSObject, UIViewControllerAnimatedTransitioning {
    func transitionDuration(using transitionContext: UIViewControllerContextTransitioning?) -> TimeInterval {
        return KQMotion.duration(KQMotion.state)
    }

    func animateTransition(using transitionContext: UIViewControllerContextTransitioning) {
        guard let to = transitionContext.view(forKey: .to),
              let toController = transitionContext.viewController(forKey: .to) else {
            transitionContext.completeTransition(false)
            return
        }
        to.frame = transitionContext.finalFrame(for: toController)
        to.alpha = 0
        transitionContext.containerView.addSubview(to)
        UIView.animate(withDuration: transitionDuration(using: transitionContext), animations: {
            to.alpha = 1
        }, completion: { _ in
            transitionContext.completeTransition(!transitionContext.transitionWasCancelled)
        })
    }
}
