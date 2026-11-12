// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// The parts every screen of stage 3 is built from, drawn as design/system/index.html draws them: text
// in the roles of the type scale, section headings, rows with a value on the trailing side, a scrolling
// column on the page colour, and the toast. Colours, sizes and durations come from DesignTokens.swift.

enum AirKit {
    static func label(_ role: KQFontRole, weight: Int? = nil, color: UIColor = KQColor.ink, lines: Int = 1) -> UILabel {
        let label = UILabel()
        label.font = weight.map { KQFont.font(role, weight: $0) } ?? KQFont.font(role)
        label.adjustsFontForContentSizeCategory = true
        label.textColor = color
        label.numberOfLines = lines
        return label
    }

    /// Running text under a heading: label size, regular weight, second ink.
    static func body(_ text: String) -> UILabel {
        let label = AirKit.label(.label, weight: 400, color: KQColor.ink2, lines: 0)
        label.text = text
        return label
    }

    /// The quiet line under a control (.kq-why): caption, third ink.
    static func note(_ text: String) -> UILabel {
        let label = AirKit.label(.caption, color: KQColor.ink3, lines: 0)
        label.text = text
        return label
    }

    static func heading(_ text: String) -> UILabel {
        let label = AirKit.label(.label, color: KQColor.ink3, lines: 0)
        label.text = text
        label.accessibilityTraits = UIAccessibilityTraitHeader
        return label
    }

    /// Keeps a control at its own width on the leading side of a column.
    static func leading(_ view: UIView) -> UIView {
        let wrapper = UIView()
        wrapper.addSubview(view)
        view.snp.makeConstraints {
            $0.top.bottom.leading.equalToSuperview()
            $0.trailing.lessThanOrEqualToSuperview()
        }
        return wrapper
    }

    /// Buttons side by side, one above the other at the accessibility text sizes.
    static func buttonRow(_ buttons: [UIView], traits: UITraitCollection) -> UIStackView {
        let row = UIStackView(arrangedSubviews: buttons)
        row.axis = traits.preferredContentSizeCategory.isAccessibilityCategory ? .vertical : .horizontal
        row.distribution = .fillEqually
        row.spacing = KQSpace.x8
        return row
    }

    static func column(_ views: [UIView], spacing: CGFloat) -> UIStackView {
        let stack = UIStackView(arrangedSubviews: views)
        stack.axis = .vertical
        stack.spacing = spacing
        return stack
    }
}

// MARK: - A scrolling screen

/// A screen of sections in one column on the page colour. The column scrolls, keeps 16 points from
/// the edges and moves out of the keyboard's way.
class AirScrollController: UIViewController {
    let scroll = UIScrollView()
    let column = UIStackView()
    /// Off for a screen that keeps its own bar above the keyboard (the keyboard layout guide moves it).
    var adjustsForKeyboard = true

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = KQColor.bg
        // The screen pushed from here shows only the chevron: a long title would make UIKit fall back
        // to the system "Back", which does not follow the in-app language.
        navigationItem.backButtonDisplayMode = .minimal
        scroll.alwaysBounceVertical = true
        scroll.keyboardDismissMode = .interactive
        view.addSubview(scroll)
        scroll.snp.makeConstraints {
            $0.edges.equalToSuperview()
        }
        column.axis = .vertical
        column.spacing = KQSpace.x24
        scroll.addSubview(column)
        column.snp.makeConstraints {
            $0.top.equalToSuperview().offset(KQSpace.x16)
            $0.bottom.equalToSuperview().offset(-KQSpace.x32)
            $0.leading.equalTo(view.safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.equalTo(view.safeAreaLayoutGuide).offset(-KQSpace.x16)
        }
        // The content as wide as the screen: pinned to the safe area only, it had no width at all, and a
        // scroll to a view (VoiceOver moving to a field below the fold, scrollRectToVisible) found nothing
        // to show. The column still keeps to the safe area; nothing scrolls sideways.
        scroll.contentLayoutGuide.widthAnchor.constraint(equalTo: scroll.frameLayoutGuide.widthAnchor).isActive = true
        let center = NotificationCenter.default
        center.addObserver(self, selector: #selector(keyboardChanged(_:)), name: .UIKeyboardWillChangeFrame, object: nil)
        center.addObserver(self, selector: #selector(keyboardChanged(_:)), name: .UIKeyboardWillHide, object: nil)
    }

    deinit {
        NotificationCenter.default.removeObserver(self)
    }

    /// A section: its heading, then its views; `rows` sit flush on the surface colour, edge to edge.
    @discardableResult
    func addSection(_ title: String?, views: [UIView] = [], rows: [UIView] = [], note: String? = nil) -> UIStackView {
        var parts: [UIView] = []
        if let title = title {
            parts.append(AirKit.heading(title))
        }
        parts.append(contentsOf: views)
        if !rows.isEmpty {
            let list = AirKit.column(rows, spacing: 0)
            list.backgroundColor = KQColor.surface
            let plate = UIView()
            plate.backgroundColor = KQColor.surface
            plate.layer.cornerRadius = KQRadius.card
            plate.clipsToBounds = true
            plate.addSubview(list)
            list.snp.makeConstraints {
                $0.edges.equalToSuperview()
            }
            parts.append(plate)
        }
        if let note = note {
            parts.append(AirKit.note(note))
        }
        let section = AirKit.column(parts, spacing: KQSpace.x8)
        column.addArrangedSubview(section)
        return section
    }

    @objc private func keyboardChanged(_ note: Notification) {
        guard let frame = (note.userInfo?[UIKeyboardFrameEndUserInfoKey] as? NSValue)?.cgRectValue else {
            return
        }
        guard adjustsForKeyboard else {
            return
        }
        let inView = view.convert(frame, from: nil)
        let overlap = note.name == .UIKeyboardWillHide ? 0 : max(0, view.bounds.maxY - inView.minY - view.safeAreaInsets.bottom)
        scroll.contentInset.bottom = overlap
        scroll.scrollIndicatorInsets.bottom = overlap
        guard overlap > 0 else {
            return
        }
        // After this pass, with the new inset in place.
        DispatchQueue.main.async { [weak self] in
            self?.revealFocusedField()
        }
    }

    /// The field being typed in comes out from under the keyboard (a name edited where it stands on
    /// You, a field of the group settings): UIKit scrolls only a table to it, so on a short phone, or
    /// at the large text sizes, the field stayed under the keyboard.
    private func revealFocusedField() {
        guard let field = AirScrollController.firstResponder(in: column), field.window != nil else {
            return
        }
        // Worked out by hand, as on the first launch, not left to scrollRectToVisible: a field taller than
        // what the keyboard leaves has to keep its top in sight.
        let rect = field.convert(field.bounds, to: scroll).insetBy(dx: 0, dy: -KQSpace.x12)
        let inset = scroll.adjustedContentInset
        let visible = scroll.bounds.height - inset.top - inset.bottom
        var offset = scroll.contentOffset.y
        if rect.maxY > offset + inset.top + visible {
            offset = rect.maxY - inset.top - visible
        }
        // A field taller than what the keyboard leaves keeps its top in sight.
        if rect.minY < offset + inset.top {
            offset = rect.minY - inset.top
        }
        let lowest = -inset.top
        let highest = max(lowest, scroll.contentSize.height + inset.bottom - scroll.bounds.height)
        offset = min(max(offset, lowest), highest)
        guard abs(offset - scroll.contentOffset.y) > 0.5 else {
            return
        }
        scroll.setContentOffset(CGPoint(x: scroll.contentOffset.x, y: offset), animated: !UIAccessibilityIsReduceMotionEnabled())
    }

    private static func firstResponder(in view: UIView) -> UIView? {
        if view.isFirstResponder {
            return view
        }
        for subview in view.subviews {
            if let found = firstResponder(in: subview) {
                return found
            }
        }
        return nil
    }
}

// MARK: - Row

/// A row of a settings list: the title, a quiet line under it if needed, the value on the trailing
/// side and a chevron when it opens something. 52 points at the default text size, more when the text
/// grows; the whole row is the target.
final class AirSettingRow: UIControl {
    let titleLabel = AirKit.label(.body, color: KQColor.ink, lines: 0)
    let detailLabel = AirKit.label(.caption, color: KQColor.ink3, lines: 0)
    let valueLabel = AirKit.label(.label, weight: 400, color: KQColor.ink2, lines: 2)
    private let chevron = UIImageView(image: AirIcon.image("chevron-down"))
    private var accessory: UIView?
    private let stack = UIStackView()

    init(title: String, detail: String? = nil, value: String? = nil, opens: Bool = true, danger: Bool = false) {
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        titleLabel.text = title
        titleLabel.textColor = danger ? KQColor.danger : KQColor.ink
        detailLabel.text = detail
        detailLabel.isHidden = (detail ?? "").isEmpty
        valueLabel.text = value
        valueLabel.isHidden = (value ?? "").isEmpty
        valueLabel.setContentCompressionResistancePriority(.defaultHigh, for: .horizontal)
        // The value keeps to its words on the trailing side, next to the chevron; the title takes the
        // rest of the row.
        valueLabel.setContentHuggingPriority(.defaultHigh, for: .horizontal)
        for label in [titleLabel, detailLabel] {
            label.setContentHuggingPriority(UILayoutPriority(rawValue: UILayoutPriority.defaultLow.rawValue - 1), for: .horizontal)
        }
        chevron.tintColor = KQColor.ink3
        chevron.contentMode = .scaleAspectFit
        chevron.isHidden = !opens

        let texts = AirKit.column([titleLabel, detailLabel], spacing: KQSpace.x2)
        texts.isUserInteractionEnabled = false
        stack.addArrangedSubview(texts)
        stack.addArrangedSubview(valueLabel)
        stack.addArrangedSubview(chevron)
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = KQSpace.x8
        stack.isUserInteractionEnabled = false
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.top.bottom.equalToSuperview().inset(KQSpace.x12)
            $0.leading.trailing.equalToSuperview().inset(KQSpace.x16)
        }
        chevron.snp.makeConstraints {
            $0.size.equalTo(KQSize.iconSmall)
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.targetIos + KQSpace.x8)
        }
        arrangeValue()
        applyAccessibilityText()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        if previousTraitCollection?.preferredContentSizeCategory != traitCollection.preferredContentSizeCategory {
            arrangeValue()
        }
        if previousTraitCollection?.layoutDirection != traitCollection.layoutDirection {
            applyDirection()
        }
    }

    override var semanticContentAttribute: UISemanticContentAttribute {
        didSet {
            applyDirection()
        }
    }

    /// The chevron of the icon set points down; a row that opens a screen points it to the trailing
    /// side, and a value beside the title keeps to that side too, whichever way the row reads now.
    private func applyDirection() {
        let rightToLeft = effectiveUserInterfaceLayoutDirection == .rightToLeft
        chevron.transform = CGAffineTransform(rotationAngle: (rightToLeft ? 1 : -1) * .pi / 2)
        let under = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        valueLabel.textAlignment = under ? .natural : (rightToLeft ? .left : .right)
    }

    /// At the accessibility text sizes the value goes under the title, as in the system Settings: side
    /// by side, a long title would break in the middle of a word.
    private func arrangeValue() {
        guard let texts = stack.arrangedSubviews.first as? UIStackView else {
            return
        }
        let under = traitCollection.preferredContentSizeCategory.isAccessibilityCategory
        valueLabel.removeFromSuperview()
        if under {
            texts.insertArrangedSubview(valueLabel, at: 1)
        }
        else {
            stack.insertArrangedSubview(valueLabel, at: 1)
        }
        valueLabel.numberOfLines = under ? 0 : 2
        applyDirection()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// A trailing control instead of the value and chevron (a switch); the row then does not act as a
    /// button itself.
    func setAccessory(_ view: UIView) {
        accessory?.removeFromSuperview()
        accessory = view
        valueLabel.isHidden = true
        chevron.isHidden = true
        stack.addArrangedSubview(view)
        stack.isUserInteractionEnabled = true
        isAccessibilityElement = false
        // A switch that says the row's title (and its note as the hint) is read once, not after the
        // same words read out of the labels.
        titleLabel.isAccessibilityElement = view.accessibilityLabel != titleLabel.text
        detailLabel.isAccessibilityElement = view.accessibilityHint == nil || view.accessibilityHint != detailLabel.text
    }

    func setValue(_ value: String?) {
        valueLabel.text = value
        valueLabel.isHidden = (value ?? "").isEmpty || accessory != nil
        applyAccessibilityText()
    }

    override var isHighlighted: Bool {
        didSet {
            backgroundColor = isHighlighted ? KQColor.selected : .clear
        }
    }

    override func didMoveToWindow() {
        super.didMoveToWindow()
        applyDirection()
    }

    private func applyAccessibilityText() {
        accessibilityLabel = titleLabel.text
        accessibilityValue = [valueLabel.text, detailLabel.text].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: ", ")
    }
}

// MARK: - Segment

/// A choice of a few short options side by side (.kq-seg): a sunken track, the chosen option raised
/// with a hairline. The track keeps to its words on the leading side; when they do not fit the width
/// (a long translation, the accessibility text sizes) the options stand one under another, each the
/// full width. Choosing an option sends .valueChanged.
final class AirSegmentedControl: UIControl {
    private let track = UIView()
    private let stack = UIStackView()
    private var segments: [AirSegment] = []
    private var hugTrailing: Constraint?
    private var fillTrailing: Constraint?

    private(set) var selectedIndex: Int

    /// `label` names the choice for VoiceOver; `identifiers` name the options, one per title.
    init(label: String, titles: [String], identifiers: [String], selectedIndex: Int) {
        self.selectedIndex = selectedIndex
        super.init(frame: .zero)
        // The options are the elements; VoiceOver hears them as one group under `label`.
        isAccessibilityElement = false
        accessibilityLabel = label
        accessibilityContainerType = .semanticGroup

        track.backgroundColor = KQColor.sunken
        track.layer.cornerRadius = KQRadius.field
        addSubview(track)
        stack.axis = .horizontal
        stack.alignment = .fill
        stack.distribution = .fill
        track.addSubview(stack)
        for (index, title) in titles.enumerated() {
            let segment = AirSegment(title: title)
            segment.accessibilityIdentifier = index < identifiers.count ? identifiers[index] : nil
            segment.addTarget(self, action: #selector(segmentTapped(_:)), for: .touchUpInside)
            segments.append(segment)
            stack.addArrangedSubview(segment)
        }
        track.snp.makeConstraints {
            $0.top.bottom.leading.equalToSuperview()
            hugTrailing = $0.trailing.lessThanOrEqualToSuperview().constraint
            fillTrailing = $0.trailing.equalToSuperview().constraint
        }
        fillTrailing?.deactivate()
        stack.snp.makeConstraints {
            $0.edges.equalToSuperview().inset(KQSpace.x2)
        }
        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// Whether the options stand one under another, for the tests.
    var isStacked: Bool {
        return stack.axis == .vertical
    }

    /// Shows `index` as chosen without sending .valueChanged: the value changed somewhere else.
    func select(_ index: Int) {
        guard segments.indices.contains(index) else {
            return
        }
        selectedIndex = index
        apply()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard bounds.width > 0 else {
            return
        }
        // Side by side only while every option keeps its words on one line.
        let needed = segments.reduce(2 * KQSpace.x2) { $0 + $1.intrinsicContentSize.width }
        let axis: UILayoutConstraintAxis = needed <= bounds.width ? .horizontal : .vertical
        guard stack.axis != axis else {
            return
        }
        stack.axis = axis
        if axis == .horizontal {
            fillTrailing?.deactivate()
            hugTrailing?.activate()
        }
        else {
            hugTrailing?.deactivate()
            fillTrailing?.activate()
        }
        setNeedsLayout()
    }

    @objc private func segmentTapped(_ sender: AirSegment) {
        guard let index = segments.firstIndex(where: { $0 === sender }), index != selectedIndex else {
            return
        }
        selectedIndex = index
        UIView.transition(with: track, duration: KQMotion.duration(KQMotion.state), options: [.transitionCrossDissolve],
                          animations: { self.apply() }, completion: nil)
        sendActions(for: .valueChanged)
    }

    private func apply() {
        for (index, segment) in segments.enumerated() {
            segment.isSelected = index == selectedIndex
        }
    }
}

/// One option of AirSegmentedControl: its words, raised with a hairline while chosen.
private final class AirSegment: UIControl {
    private let titleLabel = AirKit.label(.label, color: KQColor.ink2, lines: 0)

    init(title: String) {
        super.init(frame: .zero)
        isAccessibilityElement = true
        accessibilityLabel = title
        layer.cornerRadius = KQRadius.field - KQSpace.x2
        titleLabel.text = title
        titleLabel.textAlignment = .center
        titleLabel.isUserInteractionEnabled = false
        addSubview(titleLabel)
        titleLabel.snp.makeConstraints {
            $0.leading.trailing.equalToSuperview().inset(KQSpace.x10)
            $0.top.greaterThanOrEqualToSuperview().offset(KQSpace.x6)
            $0.bottom.lessThanOrEqualToSuperview().offset(-KQSpace.x6)
            $0.centerY.equalToSuperview()
        }
        snp.makeConstraints {
            $0.height.greaterThanOrEqualTo(KQSize.targetIos - 2 * KQSpace.x2)
        }
        apply()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// The width the words take on one line, with the padding on both sides. Measured from the text
    /// itself: a wrapping label reports less once it has been laid out narrow.
    override var intrinsicContentSize: CGSize {
        let font: UIFont = titleLabel.font ?? KQFont.font(.label)
        let words = ((titleLabel.text ?? "") as NSString).size(withAttributes: [NSAttributedStringKey.font: font])
        return CGSize(width: ceil(words.width) + 2 * KQSpace.x10, height: UIViewNoIntrinsicMetric)
    }

    override var isSelected: Bool {
        didSet {
            apply()
        }
    }

    override var isHighlighted: Bool {
        didSet {
            apply()
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        invalidateIntrinsicContentSize()
        apply()
    }

    private func apply() {
        if isSelected {
            backgroundColor = KQColor.raised
            layer.borderWidth = KQStroke.hairline
            layer.borderColor = airCGColor(KQColor.lineStrong)
        }
        else {
            backgroundColor = isHighlighted ? KQColor.selected : .clear
            layer.borderWidth = 0
        }
        titleLabel.textColor = isSelected ? KQColor.ink : KQColor.ink2
        accessibilityTraits = isSelected ? UIAccessibilityTraitButton | UIAccessibilityTraitSelected : UIAccessibilityTraitButton
    }
}

// MARK: - Confirmation

/// A confirmation on the phone (redesign stage 3, E21): a sheet from below that says what will happen
/// in one sentence, with the verb on its button and Cancel apart.
enum AirConfirm {
    /// `source` anchors the sheet where it shows as a popover.
    static func sheet(title: String?,
                      message: String?,
                      verb: String,
                      style: UIAlertActionStyle = .destructive,
                      source: UIView?,
                      perform: @escaping () -> Void) -> UIAlertController {
        let sheet = UIAlertController(title: title, message: message, preferredStyle: .actionSheet)
        sheet.addAction(UIAlertAction(title: verb, style: style) { _ in
            perform()
        })
        sheet.addAction(UIAlertAction(title: String(localized: "alert_cancel"), style: .cancel, handler: nil))
        if let source = source, let popover = sheet.popoverPresentationController {
            popover.sourceView = source
            popover.sourceRect = source.bounds
        }
        return sheet
    }
}

// MARK: - Toast

/// A toast (.kq-toast): ink plate, one line of what happened and at most one action, above the bottom
/// edge or above a given view (the composer). It leaves by itself; VoiceOver hears it when it comes.
final class AirToast: UIView {
    private static weak var current: AirToast?

    private let textLabel = AirKit.label(.label, color: KQColor.onInk, lines: 0)
    private let row = UIStackView()
    private var action: (() -> Void)?
    private var expired: (() -> Void)?
    private var timer: Timer?

    /// Shows `text` for `seconds`; `actionTitle` with `action` adds the one underlined button.
    /// `expired` runs once when the toast goes without its action being taken: its time ran out or
    /// another toast took its place. An undo commits there.
    @discardableResult
    static func show(_ text: String, actionTitle: String? = nil, seconds: TimeInterval = 3, in host: UIView, above anchor: UIView? = nil, action: (() -> Void)? = nil, expired: (() -> Void)? = nil) -> AirToast {
        current?.dismiss(animated: false)
        let toast = AirToast(text: text, actionTitle: actionTitle, action: action)
        toast.expired = expired
        host.addSubview(toast)
        toast.snp.makeConstraints {
            $0.centerX.equalToSuperview()
            $0.leading.greaterThanOrEqualTo(host.safeAreaLayoutGuide).offset(KQSpace.x16)
            $0.trailing.lessThanOrEqualTo(host.safeAreaLayoutGuide).offset(-KQSpace.x16)
            $0.width.lessThanOrEqualTo(KQSize.dialog)
            if let anchor = anchor, anchor.isDescendant(of: host) {
                $0.bottom.equalTo(anchor.snp.top).offset(-KQSpace.x12)
            }
            else {
                $0.bottom.equalTo(host.safeAreaLayoutGuide).offset(-KQSpace.x20)
            }
        }
        current = toast
        toast.present(seconds: seconds)
        UIAccessibilityPostNotification(UIAccessibilityAnnouncementNotification, [text, actionTitle].compactMap { $0 }.joined(separator: ". "))
        return toast
    }

    private init(text: String, actionTitle: String?, action: (() -> Void)?) {
        self.action = action
        super.init(frame: .zero)
        accessibilityIdentifier = "air.toast"
        backgroundColor = KQColor.ink
        layer.cornerRadius = KQRadius.card
        layer.shadowColor = KQColor.shadow.cgColor
        layer.shadowOpacity = 1
        layer.shadowOffset = CGSize(width: KQElevation.toast.x, height: KQElevation.toast.y)
        layer.shadowRadius = KQElevation.toast.blur / 2

        textLabel.text = text
        row.addArrangedSubview(textLabel)
        arrangeForTextSize(UIApplication.shared.preferredContentSizeCategory)
        if let actionTitle = actionTitle {
            let button = UIButton(type: .system)
            button.setAttributedTitle(NSAttributedString(string: actionTitle, attributes: [
                .font: KQFont.font(.label, weight: 600),
                .foregroundColor: KQColor.onInk,
                .underlineStyle: NSUnderlineStyle.styleSingle.rawValue,
            ]), for: .normal)
            button.titleLabel?.adjustsFontForContentSizeCategory = true
            button.accessibilityIdentifier = "air.toast.action"
            button.addTarget(self, action: #selector(actionTapped), for: .touchUpInside)
            button.setContentCompressionResistancePriority(.required, for: .horizontal)
            button.snp.makeConstraints {
                $0.height.greaterThanOrEqualTo(KQSize.targetIos)
            }
            row.addArrangedSubview(button)
        }
        addSubview(row)
        row.snp.makeConstraints {
            $0.top.bottom.equalToSuperview().inset(KQSpace.x6)
            $0.leading.equalToSuperview().offset(KQSpace.x16)
            $0.trailing.equalToSuperview().offset(-KQSpace.x12)
            $0.height.greaterThanOrEqualTo(KQSize.targetIos - 2 * KQSpace.x6)
        }
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        layer.shadowColor = KQColor.shadow.cgColor
        arrangeForTextSize(traitCollection.preferredContentSizeCategory)
    }

    /// The action beside the words, or under them at the accessibility text sizes: beside an action
    /// that keeps its whole word, "Request declined" had a column a word wide and broke inside its words.
    private func arrangeForTextSize(_ category: UIContentSizeCategory) {
        let stacked = category.isAccessibilityCategory
        row.axis = stacked ? .vertical : .horizontal
        row.alignment = stacked ? .leading : .center
        row.spacing = stacked ? KQSpace.x2 : KQSpace.x16
    }

    private func present(seconds: TimeInterval) {
        alpha = 0
        let reduce = UIAccessibilityIsReduceMotionEnabled()
        transform = reduce ? .identity : CGAffineTransform(translationX: 0, y: KQSpace.x8)
        UIView.animate(withDuration: KQMotion.duration(KQMotion.state), delay: 0, options: [.curveEaseOut], animations: {
            self.alpha = 1
            self.transform = .identity
        }, completion: nil)
        // VoiceOver users get longer: the action has to be found first.
        let shown = UIAccessibilityIsVoiceOverRunning() ? max(seconds, 10) : seconds
        timer = Timer.scheduledTimer(withTimeInterval: shown, repeats: false) { [weak self] _ in
            self?.dismiss(animated: true)
        }
    }

    func dismiss(animated: Bool) {
        timer?.invalidate()
        timer = nil
        let expired = self.expired
        self.expired = nil
        expired?()
        guard animated else {
            removeFromSuperview()
            return
        }
        UIView.animate(withDuration: KQMotion.duration(KQMotion.state), animations: {
            self.alpha = 0
        }, completion: { _ in
            self.removeFromSuperview()
        })
    }

    @objc private func actionTapped() {
        let action = self.action
        self.action = nil
        expired = nil
        dismiss(animated: true)
        action?()
    }
}
