// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import CoreImage
import SnapKit
import UIKit

// "New contact" (design/redesign/02-plan.md, E11): the ID field with its ruler (key, antispam, check),
// the verdict in words before anything is sent, Scan QR, the message that goes with the request, and
// your own ID with Copy at the bottom. After sending, the screen turns into "Request on its way" with
// the path picture; the person is in the list as "waiting for reply" from then on.

protocol AirNewContactControllerDelegate: class {
    /// Opens the camera; `found` gets the text of the first QR code that holds an ID.
    func newContactScanQR(_ controller: AirNewContactController, found: @escaping (String) -> Void)
    func newContactOpenChat(_ controller: AirNewContactController, publicKey: String)
    func newContactOpenRequests(_ controller: AirNewContactController)
    func newContactDidFinish(_ controller: AirNewContactController)
}

// MARK: - Ruler

/// One bar of the ruler: the sunken track and the filled part from the left edge. The ruler follows
/// the ID it measures, which reads left to right in every language.
final class AirRulerBar: UIView {
    private let fill = UIView()
    private var fraction: CGFloat = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = KQColor.sunken
        layer.cornerRadius = KQSize.bar / 2
        clipsToBounds = true
        fill.layer.cornerRadius = KQSize.bar / 2
        addSubview(fill)
        snp.makeConstraints {
            $0.height.equalTo(KQSize.bar)
        }
        setState(.filling)
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setState(_ state: AirRulerState) {
        switch state {
            case .filling:
                fill.backgroundColor = KQColor.ink
            case .good:
                fill.backgroundColor = KQColor.live
            case .bad:
                fill.backgroundColor = KQColor.danger
        }
    }

    func setFraction(_ value: CGFloat, animated: Bool) {
        fraction = min(1, max(0, value))
        guard animated, window != nil else {
            setNeedsLayout()
            return
        }
        UIView.animate(withDuration: KQMotion.duration(KQMotion.layout), delay: 0, options: [.curveEaseOut, .beginFromCurrentState], animations: {
            self.layoutFill()
        }, completion: nil)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutFill()
    }

    private func layoutFill() {
        fill.frame = CGRect(x: 0, y: 0, width: bounds.width * fraction, height: bounds.height)
    }
}

/// The three parts of an ID: the key (64), antispam (8) and the check (4), each with its count. The
/// parts keep the proportions of the design system (64 : 16 : 12), so the short ones stay readable;
/// at the accessibility text sizes they stand one under the other. Key, antispam and check stand left
/// to right in Arabic too: under the ID, whose characters run that way.
final class AirIdRulerView: UIView {
    private struct Part {
        let name: String
        let bar = AirRulerBar()
        let nameLabel = AirKit.label(.caption, color: KQColor.ink3)
        let countLabel = AirKit.label(.caption, weight: 500, color: KQColor.ink3)
        let view = UIView()
    }

    private var parts: [Part] = []
    private let stack = UIStackView()
    private var widthConstraints: [Constraint] = []
    /// The proportions of the parts give way to a name that needs more room (its label holds its width
    /// at the default high compression resistance, 750).
    static let shareLayoutPriority: Float = 700

    override init(frame: CGRect) {
        super.init(frame: frame)
        let names = [String(localized: "air_nc_ruler_key"), String(localized: "air_nc_ruler_antispam"), String(localized: "air_nc_ruler_check")]
        stack.spacing = KQSpace.x6
        stack.alignment = .top
        stack.semanticContentAttribute = .forceLeftToRight
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        for name in names {
            let part = Part(name: name)
            part.nameLabel.text = name
            part.nameLabel.adjustsFontSizeToFitWidth = true
            part.nameLabel.minimumScaleFactor = 0.75
            part.countLabel.font = KQFont.tabular(KQFont.font(.caption, weight: 500))
            // The name over its count: "antispam" and "check" have a quarter of the key's width.
            let labels = AirKit.column([part.nameLabel, part.countLabel], spacing: 0)
            let column = AirKit.column([part.bar, labels], spacing: KQSpace.x6)
            for view in [part.view, column, labels, part.bar, part.nameLabel, part.countLabel] {
                view.semanticContentAttribute = .forceLeftToRight
            }
            part.nameLabel.textAlignment = .left
            part.countLabel.textAlignment = .left
            part.view.addSubview(column)
            column.snp.makeConstraints {
                $0.edges.equalToSuperview()
            }
            part.view.isAccessibilityElement = true
            part.view.accessibilityIdentifier = "air.newContact.ruler.\(parts.count)"
            stack.addArrangedSubview(part.view)
            parts.append(part)
        }
        arrange()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            arrange()
        }
    }

    private func arrange() {
        widthConstraints.forEach { $0.deactivate() }
        widthConstraints = []
        let vertical = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        stack.axis = vertical ? .vertical : .horizontal
        stack.spacing = vertical ? KQSpace.x8 : KQSpace.x6
        guard !vertical, parts.count == 3 else {
            return
        }
        // 64fr 16fr 12fr: the key part is four times the antispam part, the check three quarters of it.
        // Below the names' hold on their width, as an fr column of the web never gets narrower than its
        // content: on a 375 point phone the Russian name of the check needs more than its share.
        parts[0].view.snp.makeConstraints {
            widthConstraints.append($0.width.equalTo(parts[1].view).multipliedBy(4).priority(AirIdRulerView.shareLayoutPriority).constraint)
        }
        parts[2].view.snp.makeConstraints {
            widthConstraints.append($0.width.equalTo(parts[1].view).multipliedBy(0.75).priority(AirIdRulerView.shareLayoutPriority).constraint)
        }
    }

    func update(count: Int, check: AirRulerState, animated: Bool) {
        for (index, size) in AirRulerPart.parts(count: count).enumerated() where index < parts.count {
            let part = parts[index]
            part.bar.setFraction(CGFloat(size.filled) / CGFloat(size.size), animated: animated)
            part.countLabel.text = "\(size.filled)/\(size.size)"
            let state: AirRulerState = index == 2 ? check : .filling
            part.bar.setState(state)
            part.nameLabel.textColor = state == .bad ? KQColor.danger : KQColor.ink3
            part.countLabel.textColor = state == .bad ? KQColor.danger : KQColor.ink3
            part.view.accessibilityLabel = String(localized: "air_nc_ruler_part_format", part.name, size.filled, size.size)
        }
    }

    /// The check part shakes once when the check fails; nothing moves with reduced motion.
    func shakeCheck() {
        guard parts.count == 3, !UIAccessibilityIsReduceMotionEnabled() else {
            return
        }
        let shake = CAKeyframeAnimation(keyPath: "transform.translation.x")
        shake.values = [0, -KQSpace.x4, KQSpace.x4, -KQSpace.x2, 0]
        shake.keyTimes = [0, 0.3, 0.6, 0.85, 1]
        shake.duration = 0.32
        shake.timingFunction = CAMediaTimingFunction(name: kCAMediaTimingFunctionEaseOut)
        parts[2].view.layer.add(shake, forKey: "shake")
    }
}

// MARK: - Fields

/// A framed multi-line field (.kq-id-input, .kq-input): surface, a strong hairline that turns to ink
/// while it has focus, and a placeholder of our own (no UITextView+Placeholder: it crashed on delete).
final class AirFramedTextView: UIView, UITextViewDelegate {
    let textView = UITextView()
    private let placeholderLabel: UILabel
    var shouldChange: ((NSRange, String) -> Bool)?
    var onChange: (() -> Void)?
    var onReturn: (() -> Void)?
    var onFocus: (() -> Void)?

    init(role: KQFontRole, placeholder: String, minHeight: CGFloat) {
        placeholderLabel = AirKit.label(role, weight: role == .monoEntry ? nil : 400, color: KQColor.ink3, lines: 0)
        super.init(frame: .zero)
        backgroundColor = KQColor.surface
        layer.cornerRadius = KQRadius.field
        layer.borderWidth = KQStroke.hairline
        textView.backgroundColor = .clear
        textView.font = role == .monoEntry ? KQFont.font(role) : KQFont.font(role, weight: 400)
        textView.adjustsFontForContentSizeCategory = true
        textView.textColor = KQColor.ink
        textView.tintColor = KQColor.ink
        textView.isScrollEnabled = false
        textView.textContainerInset = UIEdgeInsets(top: KQSpace.x10, left: KQSpace.x12, bottom: KQSpace.x10, right: KQSpace.x12)
        textView.textContainer.lineFragmentPadding = 0
        textView.delegate = self
        textView.smartDashesType = .no
        textView.smartQuotesType = .no
        textView.smartInsertDeleteType = .no
        addSubview(textView)
        textView.snp.makeConstraints {
            $0.edges.equalToSuperview()
            $0.height.greaterThanOrEqualTo(minHeight)
        }
        placeholderLabel.text = placeholder
        placeholderLabel.isUserInteractionEnabled = false
        placeholderLabel.isAccessibilityElement = false
        addSubview(placeholderLabel)
        placeholderLabel.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x10)
            $0.leading.equalToSuperview().offset(KQSpace.x12)
            $0.trailing.lessThanOrEqualToSuperview().offset(-KQSpace.x12)
            // A placeholder of two lines makes the frame taller instead of running out of it.
            $0.bottom.lessThanOrEqualToSuperview().offset(-KQSpace.x10)
        }
        applyBorder()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    var text: String {
        get {
            return textView.text ?? ""
        }
        set {
            textView.text = newValue
            refreshPlaceholder()
        }
    }

    func setPlaceholder(_ text: String) {
        placeholderLabel.text = text
    }

    func refreshPlaceholder() {
        placeholderLabel.isHidden = !(textView.text ?? "").isEmpty
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyBorder()
    }

    private func applyBorder() {
        layer.borderColor = airCGColor(textView.isFirstResponder ? KQColor.ink : KQColor.lineStrong)
    }

    func textView(_ textView: UITextView, shouldChangeTextIn range: NSRange, replacementText text: String) -> Bool {
        if text == "\n", let onReturn = onReturn {
            onReturn()
            return false
        }
        return shouldChange?(range, text) ?? true
    }

    func textViewDidChange(_ textView: UITextView) {
        refreshPlaceholder()
        onChange?()
    }

    func textViewDidBeginEditing(_ textView: UITextView) {
        UIView.animate(withDuration: KQMotion.duration(KQMotion.tap)) {
            self.applyBorder()
        }
        onFocus?()
    }

    func textViewDidEndEditing(_ textView: UITextView) {
        UIView.animate(withDuration: KQMotion.duration(KQMotion.tap)) {
            self.applyBorder()
        }
    }
}

// MARK: - Screen

final class AirNewContactController: AirScrollController {
    weak var delegate: AirNewContactControllerDelegate?

    private weak var submanagerFriends: OCTSubmanagerFriends?
    private weak var submanagerObjects: OCTSubmanagerObjects?
    private let ownAddress: String
    private let prefilled: String?
    private let blocks: AirLocalStore

    private let idField = AirFramedTextView(role: .monoEntry, placeholder: String(localized: "air_nc_id_placeholder"), minHeight: KQSize.idField)
    private let fieldAction = AirQuietButton(title: String(localized: "air_nc_paste"))
    private let clearAction = AirQuietButton(title: String(localized: "air_nc_clear"))
    private let ruler = AirIdRulerView()
    private let verdictLabel = AirKit.label(.caption, color: KQColor.ink2, lines: 0)
    private let verdictLink = AirQuietButton(title: String(localized: "air_nc_open_chat"), color: KQColor.ink)
    private let scanButton = AirButton(style: .normal, title: String(localized: "air_nc_scan"), icon: "scan")
    private let photoButton = AirButton(style: .normal, title: String(localized: "air_nc_scan_photo"))
    private let messageField = AirFramedTextView(role: .body, placeholder: "", minHeight: KQSize.field)
    private let messageError = AirKit.label(.caption, color: KQColor.danger, lines: 0)
    private let sendButton = AirButton(style: .ink, title: String(localized: "air_nc_send"))
    private let whyLabel = AirKit.note("")
    private let sendError = AirKit.label(.caption, color: KQColor.danger, lines: 0)
    /// "Send request" stays above the keyboard (E11, phone): the form scrolls behind it.
    private let sendBar = UIView()
    private let sendBarBackground = UIView()

    private var verdict: AirIdVerdict = .empty
    private var formHeight: CGFloat = 0
    private var sentKey: String?
    private var sentPath: AirPathView?
    private var sentTitle: UILabel?
    private var sentText: UILabel?
    private var friendToken: RLMNotificationToken?
    private var friendResults: Results<OCTFriend>?

    /// `prefilled`: an ID from the command line or a link, checked as if pasted.
    init(submanagerFriends: OCTSubmanagerFriends, submanagerObjects: OCTSubmanagerObjects, ownAddress: String, prefilled: String?, blocks: AirLocalStore = AirLocalStore.shared) {
        self.submanagerFriends = submanagerFriends
        self.submanagerObjects = submanagerObjects
        self.ownAddress = ownAddress
        self.prefilled = prefilled
        self.blocks = blocks
        super.init(nibName: nil, bundle: nil)
        title = String(localized: "air_new_contact")
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        friendToken?.invalidate()
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.accessibilityIdentifier = "air.newContact.screen"
        buildForm()
        if let prefilled = prefilled {
            setId(AirToxId.clean(prefilled), fromUser: true)
        }
        else {
            refresh(animated: false, fromUser: false)
        }
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        if sentKey == nil, prefilled == nil, idField.text.isEmpty {
            idField.textView.becomeFirstResponder()
        }
        sentPath?.setRunning(true)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sentPath?.setRunning(false)
    }

    // MARK: Form

    private func buildForm() {
        let tv = idField.textView
        tv.autocapitalizationType = .allCharacters
        tv.autocorrectionType = .no
        tv.spellCheckingType = .no
        tv.keyboardType = .asciiCapable
        tv.returnKeyType = .done
        tv.accessibilityIdentifier = "air.newContact.id"
        tv.accessibilityLabel = String(localized: "air_nc_id_label")
        idField.onReturn = { [weak self] in
            self?.idField.textView.resignFirstResponder()
        }
        idField.shouldChange = { [weak self] range, text in
            self?.edit(range: range, replacement: text)
            return false
        }

        fieldAction.accessibilityIdentifier = "air.newContact.paste"
        fieldAction.addTarget(self, action: #selector(pasteTapped), for: .touchUpInside)
        clearAction.accessibilityIdentifier = "air.newContact.clear"
        clearAction.addTarget(self, action: #selector(clearTapped), for: .touchUpInside)
        let fieldActions = UIStackView(arrangedSubviews: [UIView(), fieldAction, clearAction])
        fieldActions.axis = .horizontal
        fieldActions.spacing = KQSpace.x12

        verdictLabel.accessibilityIdentifier = "air.newContact.verdict"
        verdictLink.accessibilityIdentifier = "air.newContact.verdictLink"
        verdictLink.addTarget(self, action: #selector(verdictLinkTapped), for: .touchUpInside)

        scanButton.accessibilityIdentifier = "air.newContact.scan"
        scanButton.addTarget(self, action: #selector(scanTapped), for: .touchUpInside)
        photoButton.accessibilityIdentifier = "air.newContact.photo"
        photoButton.addTarget(self, action: #selector(photoTapped), for: .touchUpInside)
        photoButton.isHidden = !UIImagePickerController.isSourceTypeAvailable(.photoLibrary)
        let scanRow = AirKit.buttonRow([scanButton, photoButton], traits: traitCollection)

        let idLabel = AirKit.heading(String(localized: "air_nc_id_label"))
        idLabel.isAccessibilityElement = false
        addSection(nil, views: [idLabel, idField, fieldActions, ruler, verdictLabel, AirKit.leading(verdictLink), scanRow])

        messageField.setPlaceholder(String(localized: "add_contact_default_message_text"))
        messageField.textView.accessibilityIdentifier = "air.newContact.message"
        messageField.textView.accessibilityLabel = String(localized: "air_nc_message_label")
        messageField.onChange = { [weak self] in
            self?.refresh(animated: true, fromUser: false)
            self?.revealFocused(animated: false)
        }
        for field in [idField, messageField] {
            field.onFocus = { [weak self] in
                DispatchQueue.main.async {
                    self?.revealFocused(animated: true)
                }
            }
        }
        messageError.text = String(localized: "air_nc_message_too_long")
        messageError.isHidden = true
        addSection(String(localized: "air_nc_message_label"), views: [messageField, messageError], note: String(localized: "air_nc_message_note"))

        sendButton.accessibilityIdentifier = "air.newContact.send"
        sendButton.addTarget(self, action: #selector(sendTapped), for: .touchUpInside)
        whyLabel.accessibilityIdentifier = "air.newContact.why"
        sendError.isHidden = true
        sendError.accessibilityIdentifier = "air.newContact.error"
        installSendBar()

        let own = AirKit.label(.mono, color: KQColor.ink2, lines: 0)
        own.text = AirKeyFormat.lines(AirToxId.clean(ownAddress))
        own.accessibilityLabel = String(localized: "air_nc_own_title")
        own.accessibilityValue = AirKeyFormat.groups(AirToxId.clean(ownAddress)).joined(separator: " ")
        own.accessibilityIdentifier = "air.newContact.ownId"
        let copy = AirButton(style: .normal, title: String(localized: "air_nc_copy"), icon: "copy")
        copy.accessibilityIdentifier = "air.newContact.copyOwn"
        copy.addTarget(self, action: #selector(copyOwnTapped), for: .touchUpInside)
        addSection(String(localized: "air_nc_own_title"), views: [own, AirKit.leading(copy)], note: String(localized: "air_nc_own_note"))
    }

    private func installSendBar() {
        adjustsForKeyboard = false
        sendBarBackground.backgroundColor = KQColor.bg
        sendBar.backgroundColor = KQColor.bg
        let rule = UIView()
        rule.backgroundColor = KQColor.line
        view.addSubview(sendBarBackground)
        view.addSubview(sendBar)
        sendBar.addSubview(rule)
        let stack = AirKit.column([sendError, sendButton, whyLabel], spacing: KQSpace.x6)
        sendBar.addSubview(stack)
        sendBar.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            sendBar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            sendBar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            sendBar.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
        ])
        sendBarBackground.snp.makeConstraints {
            $0.top.equalTo(sendBar)
            $0.leading.trailing.bottom.equalToSuperview()
        }
        rule.snp.makeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.height.equalTo(KQStroke.hairline)
        }
        stack.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x12)
            $0.bottom.equalToSuperview().offset(-KQSpace.x12)
            $0.leading.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.equalTo(view.safeAreaLayoutGuide).offset(-KQSpace.x16)
        }
        // The button has no height of its own above 44 points: without this the bar, not the form,
        // takes the free height.
        sendButton.snp.makeConstraints {
            $0.height.equalTo(KQSize.targetIos).priority(.medium)
        }
        scroll.snp.remakeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            $0.bottom.equalTo(sendBar.snp.top)
        }
    }

    /// The field being typed in stays clear of the send bar: the form scrolls, the bar does not move.
    private func revealFocused(animated: Bool, layout: Bool = true) {
        let focused = [idField, messageField].first { $0.textView.isFirstResponder }
        guard let field = focused, sentKey == nil else {
            return
        }
        if layout {
            view.layoutIfNeeded()
        }
        // Worked out here, not left to scrollRectToVisible: a field taller than the room above the send
        // bar is not pulled to its top while the end of it is typed in.
        let rect = field.convert(field.bounds, to: scroll).insetBy(dx: 0, dy: -KQSpace.x12)
        let inset = scroll.adjustedContentInset
        let visible = scroll.bounds.height - inset.top - inset.bottom
        var offset = scroll.contentOffset.y
        if rect.maxY > offset + inset.top + visible {
            offset = rect.maxY - visible - inset.top
        }
        if rect.minY < offset + inset.top, rect.height <= visible {
            offset = rect.minY - inset.top
        }
        let lowest = max(-inset.top, scroll.contentSize.height + inset.bottom - scroll.bounds.height)
        offset = min(max(offset, -inset.top), lowest)
        guard abs(offset - scroll.contentOffset.y) > 0.5 else {
            return
        }
        scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: offset), animated: animated && !UIAccessibilityIsReduceMotionEnabled())
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        // The keyboard came up or grew (another layout, the suggestions bar): the form got shorter.
        let height = scroll.bounds.height
        if height < formHeight {
            revealFocused(animated: false, layout: false)
        }
        formHeight = height
    }

    /// The ID field takes only the hex of an ID and shows it in groups of four; the cursor stays after
    /// the same character it was after. Deleting the space between groups deletes the character before it.
    private func edit(range: NSRange, replacement: String) {
        let current = idField.text as NSString
        guard range.location != NSNotFound, NSMaxRange(range) <= current.length else {
            return
        }
        var range = range
        if replacement.isEmpty, range.length == 1, current.substring(with: range) == " ", range.location > 0 {
            range = NSRange(location: range.location - 1, length: 2)
        }
        let edited = current.replacingCharacters(in: range, with: replacement)
        let caret = range.location + (replacement as NSString).length
        let before = (edited as NSString).substring(to: min(caret, (edited as NSString).length))
        let hexBefore = min(AirToxId.clean(before).count, AirToxId.length)
        setId(AirToxId.clean(edited), fromUser: true, caretAfter: hexBefore)
    }

    private func setId(_ hex: String, fromUser: Bool, caretAfter: Int? = nil) {
        let grouped = AirToxId.grouped(hex)
        idField.text = grouped
        let hexCount = caretAfter.map { min($0, hex.count) } ?? hex.count
        let offset = hexCount + max(0, (hexCount - 1) / 4) + (hexCount > 0 && hexCount % 4 == 0 && hexCount < hex.count ? 1 : 0)
        if let position = idField.textView.position(from: idField.textView.beginningOfDocument, offset: min(offset, (grouped as NSString).length)) {
            idField.textView.selectedTextRange = idField.textView.textRange(from: position, to: position)
        }
        refresh(animated: true, fromUser: fromUser)
    }

    private var currentHex: String {
        return AirToxId.clean(idField.text)
    }

    private func decide(_ hex: String) -> AirIdVerdict {
        return AirIdVerdict.of(hex: hex, ownAddress: ownAddress, isContact: { [weak self] key in
            (self?.submanagerObjects?.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", key)).count ?? 0) > 0
        }, hasRequest: { [weak self] key in
            (self?.submanagerObjects?.friendRequests(predicate: NSPredicate(format: "publicKey ==[c] %@", key)).count ?? 0) > 0
        }, isBlocked: { [weak self] key in
            self?.blocks.isBlocked(publicKey: key) ?? false
        })
    }

    private var messageBytes: Int {
        return messageField.text.trimmingCharacters(in: .whitespacesAndNewlines).lengthOfBytes(using: .utf8)
    }

    private func refresh(animated: Bool, fromUser: Bool) {
        let hex = currentHex
        let previous = verdict
        verdict = decide(hex)
        fieldAction.isHidden = !hex.isEmpty
        clearAction.isHidden = hex.isEmpty
        ruler.update(count: hex.count, check: verdict.checkState, animated: animated)
        if fromUser, verdict == .badCheck, previous != .badCheck {
            ruler.shakeCheck()
        }

        var link: String?
        switch verdict {
            case .empty:
                verdictLabel.text = String(localized: "air_nc_verdict_empty")
                verdictLabel.textColor = KQColor.ink3
            case .partial(let left):
                verdictLabel.text = String(localized: "air_nc_verdict_left", left)
                verdictLabel.textColor = KQColor.ink2
            case .badCheck:
                verdictLabel.text = String(localized: "air_nc_verdict_bad")
                verdictLabel.textColor = KQColor.danger
            case .own:
                verdictLabel.text = String(localized: "air_nc_verdict_own")
                verdictLabel.textColor = KQColor.danger
            case .contact(let key):
                verdictLabel.text = String(localized: "air_nc_verdict_contact_format", contactName(key))
                verdictLabel.textColor = KQColor.ink2
                link = String(localized: "air_nc_open_chat")
            case .theyAsked:
                verdictLabel.text = String(localized: "air_nc_verdict_they_asked")
                verdictLabel.textColor = KQColor.ink2
                link = String(localized: "air_nc_open_requests")
            case .blocked:
                verdictLabel.text = String(localized: "air_nc_verdict_blocked")
                verdictLabel.textColor = KQColor.ink2
            case .ok:
                verdictLabel.text = String(localized: "air_nc_verdict_ok")
                verdictLabel.textColor = KQColor.live
        }
        verdictLink.isHidden = link == nil
        if let link = link {
            verdictLink.setTitle(link)
        }

        let tooLong = messageBytes > Int(kOCTToxMaxFriendRequestLength)
        messageError.isHidden = !tooLong
        sendButton.isEnabled = verdict.address != nil && !tooLong
        switch verdict {
            case .own:
                whyLabel.text = String(localized: "air_nc_why_own")
            case .badCheck:
                whyLabel.text = String(localized: "air_nc_why_fix")
            case .contact:
                whyLabel.text = String(localized: "air_nc_why_contact")
            case .theyAsked:
                whyLabel.text = String(localized: "air_nc_why_they_asked")
            case .ok, .blocked:
                whyLabel.text = tooLong ? String(localized: "air_nc_message_too_long") : nil
            case .empty, .partial:
                whyLabel.text = String(localized: "air_nc_why_whole")
        }
        whyLabel.isHidden = sendButton.isEnabled
        sendButton.accessibilityHint = sendButton.isEnabled ? nil : whyLabel.text

        if fromUser, kind(previous) != kind(verdict), let text = verdictLabel.text, kind(verdict) != 0 {
            UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, text)
        }
    }

    /// The verdicts VoiceOver announces when one turns into another: not every character typed.
    private func kind(_ verdict: AirIdVerdict) -> Int {
        switch verdict {
            case .empty, .partial:
                return 0
            case .badCheck:
                return 1
            case .own:
                return 2
            case .contact, .theyAsked:
                return 3
            case .blocked, .ok:
                return 4
        }
    }

    private func contactName(_ key: String) -> String {
        guard let friends = submanagerObjects?.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", key)), friends.count > 0 else {
            return String(localized: "air_nc_them")
        }
        let nick = friends.firstObject.nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        return nick.isEmpty ? String(localized: "air_nc_them") : nick
    }

    // MARK: Actions

    @objc private func pasteTapped() {
        guard let raw = UIPasteboard.general.string, !raw.isEmpty else {
            return
        }
        setId(AirToxId.clean(raw), fromUser: true)
    }

    @objc private func clearTapped() {
        setId("", fromUser: false)
        idField.textView.becomeFirstResponder()
    }

    @objc private func verdictLinkTapped() {
        switch verdict {
            case .contact(let key):
                delegate?.newContactOpenChat(self, publicKey: key)
            case .theyAsked:
                delegate?.newContactOpenRequests(self)
            default:
                break
        }
    }

    @objc private func scanTapped() {
        view.endEditing(true)
        delegate?.newContactScanQR(self) { [weak self] text in
            self?.setId(AirToxId.clean(text), fromUser: true)
        }
    }

    @objc private func photoTapped() {
        view.endEditing(true)
        let picker = UIImagePickerController()
        picker.sourceType = .photoLibrary
        picker.delegate = self
        present(picker, animated: true, completion: nil)
    }

    @objc private func copyOwnTapped() {
        UIPasteboard.general.string = AirToxId.clean(ownAddress)
        AirToast.show(String(localized: "air_nc_copied"), in: view)
    }

    @objc private func sendTapped() {
        view.endEditing(true)
        refresh(animated: false, fromUser: false)
        guard let address = verdict.address, let submanagerFriends = submanagerFriends else {
            return
        }
        let typed = messageField.text.trimmingCharacters(in: .whitespacesAndNewlines)
        let message = typed.isEmpty ? String(localized: "add_contact_default_message_text") : typed
        do {
            try submanagerFriends.sendFriendRequest(toAddress: address, message: message)
        }
        catch let error as NSError {
            // Said where the action is, not in an alert (E21).
            let text = OCTToxErrorFriendAdd(rawValue: error.code)?.strings().message ?? String(localized: "error_internal_message")
            sendError.text = text
            sendError.isHidden = false
            UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, text)
            return
        }
        let key = String(address.prefix(AirToxId.keyLength))
        if case .blocked = verdict {
            blocks.unblock(publicKey: key)
        }
        showSent(key: key)
    }

    // MARK: Request on its way

    private func showSent(key: String) {
        sentKey = key
        sendBar.removeFromSuperview()
        sendBarBackground.removeFromSuperview()
        scroll.snp.remakeConstraints {
            $0.edges.equalToSuperview()
        }
        column.arrangedSubviews.forEach { $0.removeFromSuperview() }
        let path = AirPathView()
        path.configure(state: .none, initials: "?", name: String(localized: "air_nc_them"))
        path.accessibilityIdentifier = "air.newContact.path"
        path.isAccessibilityElement = true
        path.accessibilityLabel = String(localized: "air_nc_path_label_format", String(localized: "air_nc_them"))
        sentPath = path
        let title = AirKit.label(.heading, color: KQColor.ink, lines: 0)
        title.text = String(localized: "air_nc_sent_title")
        title.accessibilityTraits = UIAccessibilityTraitHeader
        title.accessibilityIdentifier = "air.newContact.sentTitle"
        sentTitle = title
        let text = AirKit.body(String(localized: "air_nc_sent_text"))
        sentText = text
        let done = AirButton(style: .ink, title: String(localized: "air_nc_done"))
        done.accessibilityIdentifier = "air.newContact.done"
        done.addTarget(self, action: #selector(doneTapped), for: .touchUpInside)
        addSection(nil, views: [path, title, text, done])
        column.alpha = 0
        UIView.animate(withDuration: KQMotion.duration(KQMotion.reveal)) {
            self.column.alpha = 1
        }
        path.setRunning(view.window != nil)
        UIAccessibilityPostNotification(UIAccessibilityScreenChangedNotification, title)
        watchFriend(key: key)
    }

    /// While the screen is open, the dashed line turns solid when the person accepts and comes online.
    private func watchFriend(key: String) {
        guard let objects = submanagerObjects else {
            return
        }
        let results = objects.friends(predicate: NSPredicate(format: "publicKey ==[c] %@", key))
        friendResults = results
        friendToken = results.addNotificationBlock { [weak self] _ in
            self?.friendChanged()
        }
    }

    private func friendChanged() {
        guard let results = friendResults, results.count > 0, !results.firstObject.isInvalidated, results.firstObject.isConnected else {
            return
        }
        let friend = results.firstObject
        friendToken?.invalidate()
        friendToken = nil
        let name = friend.nickname.isEmpty ? String(localized: "air_nc_them") : friend.nickname
        sentPath?.setRunning(false)
        sentPath?.configure(state: friend.connectionStatus == .TCP ? .relay : .direct, initials: AirInitials.from(name), name: name)
        sentPath?.accessibilityLabel = String(localized: "air_nc_path_live_format", name)
        sentTitle?.text = String(localized: "air_nc_accepted_title")
        sentText?.text = String(localized: "air_nc_accepted_text_format", name)
        if let title = sentTitle {
            UIAccessibilityPostNotification(UIAccessibilityLayoutChangedNotification, title)
        }
    }

    @objc private func doneTapped() {
        delegate?.newContactDidFinish(self)
    }
}

extension AirNewContactController: UIImagePickerControllerDelegate, UINavigationControllerDelegate {
    func imagePickerController(_ picker: UIImagePickerController, didFinishPickingMediaWithInfo info: [String: Any]) {
        picker.dismiss(animated: true) { [weak self] in
            guard let self = self else {
                return
            }
            guard let image = info[UIImagePickerControllerOriginalImage] as? UIImage,
                  let text = AirNewContactController.qrText(in: image),
                  AirToxId.clean(text).count == AirToxId.length else {
                AirToast.show(String(localized: "air_nc_wrong_qr"), in: self.view)
                return
            }
            self.setId(AirToxId.clean(text), fromUser: true)
        }
    }

    func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
        picker.dismiss(animated: true, completion: nil)
    }

    static func qrText(in image: UIImage) -> String? {
        guard let ciImage = CIImage(image: image) else {
            return nil
        }
        let detector = CIDetector(ofType: CIDetectorTypeQRCode, context: nil, options: [CIDetectorAccuracy: CIDetectorAccuracyHigh])
        for feature in detector?.features(in: ciImage) ?? [] {
            if let code = feature as? CIQRCodeFeature, let text = code.messageString, !text.isEmpty {
                return text
            }
        }
        return nil
    }
}

// MARK: - Command line row

/// Over the Air list while the command line holds the start of an ID: "New contact with this ID" and
/// what is wrong with it so far, as a row of the list (the dashed tile of a pending contact).
final class AirCommandIdRow: UIControl {
    /// A person's ID, or a group's key (64 characters are both a whole key and the start of an ID).
    enum Kind {
        case contact
        case group
    }

    private let kind: Kind
    private let tile = AirTileView(side: KQSize.tilePhone)
    private let titleLabel = AirKit.label(.body, weight: 500, color: KQColor.ink, lines: 0)
    // As many lines as the words take: at the largest text sizes two lines cut the note mid-word.
    private let noteLabel = AirKit.label(.caption, color: KQColor.ink3, lines: 0)

    init(kind: Kind = .contact) {
        self.kind = kind
        super.init(frame: .zero)
        backgroundColor = KQColor.surface
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        switch kind {
            case .contact:
                accessibilityIdentifier = "air.command.newContact"
                tile.configure(style: .pending, photo: nil)
            case .group:
                accessibilityIdentifier = "air.command.joinGroup"
                tile.configure(style: .pendingGroup, photo: nil)
        }
        titleLabel.text = title
        let texts = AirKit.column([titleLabel, noteLabel], spacing: KQSpace.x2)
        texts.isUserInteractionEnabled = false
        addSubview(tile)
        addSubview(texts)
        tile.snp.makeConstraints {
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.centerY.equalToSuperview()
            $0.size.equalTo(KQSize.tilePhone)
        }
        texts.snp.makeConstraints {
            $0.leading.equalTo(tile.snp.trailing).offset(KQSpace.x12)
            $0.trailing.equalToSuperview().offset(-KQSpace.x16)
            $0.top.greaterThanOrEqualToSuperview().offset(KQSpace.x8)
            $0.bottom.lessThanOrEqualToSuperview().offset(-KQSpace.x8)
            $0.centerY.equalToSuperview()
        }
        // The table header keeps the frame it is given (ChatListController.setCommandIdRow): a
        // constraint on the row itself would let its words widen it past the screen.
    }

    private var title: String {
        switch kind {
            case .contact:
                return String(localized: "air_cmd_new_contact")
            case .group:
                return String(localized: "air_cmd_join_group")
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func configure(note: String) {
        noteLabel.text = note
        accessibilityLabel = title
        accessibilityValue = note
    }

    override var isHighlighted: Bool {
        didSet {
            backgroundColor = isHighlighted ? KQColor.selected : KQColor.surface
        }
    }

    /// The note for what the command line holds so far, in the words of the new contact screen.
    static func note(hex: String, ownAddress: String, isContact: (String) -> Bool) -> String {
        switch AirIdVerdict.of(hex: hex, ownAddress: ownAddress, isContact: isContact, hasRequest: { _ in false }, isBlocked: { _ in false }) {
            case .empty, .partial:
                return String(localized: "air_cmd_id_left", max(0, AirToxId.length - hex.count))
            case .badCheck:
                return String(localized: "air_cmd_id_bad")
            case .own:
                return String(localized: "air_cmd_id_own")
            case .contact:
                return String(localized: "air_cmd_id_contact")
            case .theyAsked, .blocked, .ok:
                return String(localized: "air_cmd_id_ok")
        }
    }
}
