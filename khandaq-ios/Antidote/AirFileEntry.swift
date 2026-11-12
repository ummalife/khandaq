// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SnapKit
import UIKit

// Files, photos and voice notes as entries of the transcript (E8). An attachment weighs no more than a
// line of text, a photo excepted: a file is its name and size, and while it moves a bar with how much
// is left and the action it waits for; a voice note is "Listen", its wave and its length in tabular
// figures. The cells are ChatIncomingFileCell and ChatOutgoingFileCell.

/// What can be done with a file entry now. An action shows only when the conversation gave the entry
/// its handle: a group has no pause, and a file another member sends to a group arrives by itself.
enum AirFileAction: Equatable {
    case accept
    case decline
    case pause
    case resume
    case cancel
    case retry
}

/// The bar, the words under the name and the actions of a file entry in one state of its transfer.
struct AirFileEntryText: Equatable {
    /// How much of the file has moved, for the bar; nil when nothing is moving.
    var progress: Float?
    /// What the file waits for, or how much is left.
    var note: String?
    /// The note says the transfer failed (red) rather than how it goes (grey).
    var noteIsProblem = false
    var actions: [AirFileAction] = []

    /// `progress` is the share moved so far, 0 to 1. `personName` is the other person of a conversation
    /// with one person, nil in a group; `personOnline` says whether they are online now.
    static func make(state: ChatGenericFileCellModel.State,
                     incoming: Bool,
                     bytesTotal: Int64,
                     progress: Float,
                     available: Set<AirFileAction>,
                     personName: String?,
                     personOnline: Bool) -> AirFileEntryText {
        var text = AirFileEntryText()
        let wanted: [AirFileAction]
        switch state {
            case .waitingConfirmation:
                if incoming {
                    // The size is on the line above; "Accept" is the whole message.
                    wanted = [.accept, .decline]
                }
                else {
                    if let name = personName, !name.isEmpty {
                        let format = personOnline ? "air_file_waits_accept_format" : "air_file_waits_online_format"
                        text.note = String(localized: format, name)
                    }
                    wanted = [.cancel]
                }
            case .loading:
                text.progress = clamped(progress)
                text.note = leftText(bytesTotal: bytesTotal, progress: progress)
                wanted = [.pause, .cancel]
            case .paused:
                text.note = String(localized: "air_file_paused")
                wanted = [.resume, .cancel]
            case .cancelled:
                text.note = String(localized: incoming ? "air_file_failed_incoming" : "air_file_failed_outgoing")
                text.noteIsProblem = true
                wanted = [.retry]
            case .done:
                wanted = []
        }
        text.actions = wanted.filter { available.contains($0) }
        return text
    }

    /// "1.2 MB left"; nil while the size is unknown.
    static func leftText(bytesTotal: Int64, progress: Float) -> String? {
        guard bytesTotal > 0 else {
            return nil
        }
        let left = Int64((Double(bytesTotal) * Double(1 - clamped(progress))).rounded(.up))
        return String(localized: "air_file_left_format", AirByteSize.text(left))
    }

    static func title(for action: AirFileAction, incoming: Bool) -> String {
        switch action {
            case .accept:
                return String(localized: "air_file_accept")
            case .decline:
                return String(localized: "air_file_decline")
            case .pause:
                return String(localized: "air_file_pause")
            case .resume:
                return String(localized: "air_file_resume")
            case .cancel:
                return String(localized: "air_file_cancel")
            case .retry:
                return String(localized: incoming ? "air_file_retry_incoming" : "air_file_retry_outgoing")
        }
    }

    /// The mark of our own file: two checks once the person has it all, one when it left, a clock while
    /// it waits for them or moves, red when it failed.
    static func status(state: ChatGenericFileCellModel.State, delivered: Bool, personOnline: Bool) -> AirEntryStatus {
        switch state {
            case .done:
                return delivered ? .delivered : .sent
            case .waitingConfirmation:
                return personOnline ? .sending : .transit
            case .loading, .paused:
                return .sending
            case .cancelled:
                return .failed
        }
    }

    private static func clamped(_ value: Float) -> Float {
        return max(0, min(1, value.isFinite ? value : 0))
    }
}

/// A size in the app's language ("12.6 MB", "154 KB"), counted in thousands as the Files app counts.
/// ByteCountFormatter follows the system language, which the language chosen in the app does not change.
enum AirByteSize {
    private struct Unit {
        let bytes: Double
        let fractionDigits: Int
        let format: String
    }

    private static let units = [
        Unit(bytes: 1, fractionDigits: 0, format: "air_size_bytes_format"),
        Unit(bytes: 1_000, fractionDigits: 0, format: "air_size_kb_format"),
        Unit(bytes: 1_000_000, fractionDigits: 1, format: "air_size_mb_format"),
        Unit(bytes: 1_000_000_000, fractionDigits: 2, format: "air_size_gb_format"),
    ]

    static func text(_ bytes: Int64, locale: Locale = AppLanguage.locale) -> String {
        let value = Double(max(0, bytes))
        var index = 0
        while index + 1 < units.count && value >= units[index + 1].bytes {
            index += 1
        }
        var amount = rounded(value / units[index].bytes, digits: units[index].fractionDigits)
        // "1 MB", not "1,000 KB", when the amount rounds up to the next unit.
        if amount >= 1000 && index + 1 < units.count {
            index += 1
            amount = rounded(value / units[index].bytes, digits: units[index].fractionDigits)
        }
        let unit = units[index]
        return String(localized: unit.format, number(amount, digits: unit.fractionDigits, locale: locale))
    }

    private static func rounded(_ value: Double, digits: Int) -> Double {
        let scale = pow(10, Double(digits))
        return (value * scale).rounded(.toNearestOrAwayFromZero) / scale
    }

    private static func number(_ value: Double, digits: Int, locale: Locale) -> String {
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = digits
        formatter.roundingMode = .halfUp
        return formatter.string(from: NSNumber(value: value)) ?? String(Int(value))
    }
}

/// How large a file the transport takes, said before the file is chosen.
enum AirFileLimits {
    /// A group file travels in NGC custom packets, capped at 200 MiB (kOCTNgcMaxFileTransferBytes in
    /// objcTox's OCTNgcGroupFileTransfer.m); a larger one is refused before it leaves.
    static let groupMaxMegabytes: Int64 = 200

    /// "A group takes files up to 200 MB", next to the choice of a file.
    static var groupHint: String {
        return String(localized: "air_group_file_limit_format", AirByteSize.text(groupMaxMegabytes * 1_000_000))
    }
}

/// The bar of a transfer in design/system's terms (.kq-bar): a round track on the sunken ground and the
/// part done in ink. A new value slides there smoothly; with Reduce Motion it jumps.
final class AirProgressBar: UIView {
    private let fill = UIView()
    private(set) var progress: Float = 0

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        clipsToBounds = true
        layer.cornerRadius = KQSize.bar / 2
        fill.layer.cornerRadius = KQSize.bar / 2
        addSubview(fill)
        snp.makeConstraints {
            $0.height.equalTo(KQSize.bar)
        }
        applyColors()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func setProgress(_ value: Float, animated: Bool) {
        let clamped = max(0, min(1, value.isFinite ? value : 0))
        let changed = clamped != progress
        progress = clamped
        guard changed else {
            return
        }
        if animated && window != nil && !UIAccessibilityIsReduceMotionEnabled() {
            UIView.animate(withDuration: KQMotion.layout,
                           delay: 0,
                           options: [.curveEaseOut, .beginFromCurrentState, .allowUserInteraction],
                           animations: { self.layoutFill() },
                           completion: nil)
        }
        else {
            setNeedsLayout()
        }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutFill()
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyColors()
    }

    private func layoutFill() {
        let width = bounds.width * CGFloat(progress)
        // The fill grows from the start of the line: the left in English, the right in Arabic.
        let leftToRight = effectiveUserInterfaceLayoutDirection == .leftToRight
        fill.frame = CGRect(x: leftToRight ? 0 : bounds.width - width, y: 0, width: width, height: bounds.height)
    }

    private func applyColors() {
        backgroundColor = KQColor.sunken
        fill.backgroundColor = KQColor.ink
    }
}

/// A file without a preview, or the transfer of a photo under its preview: the file glyph, the name
/// and the size on one line; under them the bar, the note and the actions while the file is not here.
final class AirFileRowView: UIView {
    /// One of the actions was tapped.
    var onAction: ((AirFileAction) -> Void)?

    private let head = UIView()
    private let iconView = UIImageView()
    private let nameLabel = UILabel()
    private let sizeLabel = UILabel()
    private let bar = AirProgressBar()
    private let noteLabel = UILabel()
    private let actionsStack = UIStackView()
    private let column = UIStackView()
    private var actions: [AirFileAction] = []
    private var actionButtons: [AirQuietButton] = []
    private var sizeFontCategory = UIApplication.shared.preferredContentSizeCategory

    override init(frame: CGRect) {
        super.init(frame: frame)

        iconView.image = AirIcon.image("file")
        iconView.contentMode = .scaleAspectFit
        iconView.isAccessibilityElement = false

        nameLabel.font = KQFont.font(.label, weight: 600)
        nameLabel.adjustsFontForContentSizeCategory = true
        nameLabel.textColor = KQColor.ink
        nameLabel.lineBreakMode = .byTruncatingMiddle
        nameLabel.numberOfLines = 1
        nameLabel.isAccessibilityElement = false
        nameLabel.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        sizeLabel.font = KQFont.tabular(KQFont.font(.caption))
        sizeLabel.textColor = KQColor.ink3
        sizeLabel.isAccessibilityElement = false
        sizeLabel.setContentHuggingPriority(.required, for: .horizontal)
        sizeLabel.setContentCompressionResistancePriority(.required, for: .horizontal)

        head.addSubview(iconView)
        head.addSubview(nameLabel)
        head.addSubview(sizeLabel)
        iconView.snp.makeConstraints {
            $0.leading.equalToSuperview()
            $0.centerY.equalTo(nameLabel)
            $0.size.equalTo(KQSize.icon)
        }
        nameLabel.snp.makeConstraints {
            $0.top.bottom.equalToSuperview()
            $0.leading.equalTo(iconView.snp.trailing).offset(KQSpace.x10)
            $0.height.greaterThanOrEqualTo(KQSize.icon)
        }
        sizeLabel.snp.makeConstraints {
            $0.leading.greaterThanOrEqualTo(nameLabel.snp.trailing).offset(KQSpace.x10)
            $0.trailing.equalToSuperview()
            $0.firstBaseline.equalTo(nameLabel)
        }

        noteLabel.font = KQFont.font(.caption)
        noteLabel.adjustsFontForContentSizeCategory = true
        noteLabel.numberOfLines = 0
        noteLabel.isAccessibilityElement = false

        actionsStack.axis = .horizontal
        actionsStack.alignment = .center
        actionsStack.spacing = KQSpace.x16

        let actionsRow = UIView()
        actionsRow.addSubview(actionsStack)
        actionsStack.snp.makeConstraints {
            $0.top.bottom.leading.equalToSuperview()
            $0.trailing.lessThanOrEqualToSuperview()
        }

        column.axis = .vertical
        column.alignment = .fill
        column.spacing = KQSpace.x6
        column.addArrangedSubview(head)
        column.addArrangedSubview(bar)
        column.addArrangedSubview(noteLabel)
        column.addArrangedSubview(actionsRow)
        addSubview(column)
        column.snp.makeConstraints {
            $0.top.leading.trailing.equalToSuperview()
            // Gives way while a stack hides the row, instead of fighting its zero height.
            $0.bottom.equalToSuperview().priority(999)
        }

        applyColors()
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// The line of the name, for the time column to sit on.
    static var firstLineHeight: CGFloat {
        return max(KQSize.icon, ceil(KQFont.lineHeight(.label)))
    }

    /// `showsHead` is false under a preview, which already shows what the file is.
    func configure(name: String, size: String, text: AirFileEntryText, incoming: Bool, showsHead: Bool) {
        let category = UIApplication.shared.preferredContentSizeCategory
        if category != sizeFontCategory {
            sizeFontCategory = category
            sizeLabel.font = KQFont.tabular(KQFont.font(.caption))
        }
        nameLabel.text = name
        sizeLabel.text = size
        head.isHidden = !showsHead

        bar.isHidden = text.progress == nil
        bar.setProgress(text.progress ?? 0, animated: false)

        noteLabel.text = text.note
        noteLabel.isHidden = (text.note ?? "").isEmpty
        noteLabel.textColor = text.noteIsProblem ? KQColor.danger : KQColor.ink3

        if text.actions != actions || actionButtons.isEmpty != text.actions.isEmpty {
            setActions(text.actions, incoming: incoming)
        }
        actionsStack.superview?.isHidden = text.actions.isEmpty
    }

    /// A new share has moved: the bar slides and the note says what is left.
    func updateProgress(_ progress: Float, note: String?) {
        bar.isHidden = false
        bar.setProgress(progress, animated: true)
        if let note = note {
            noteLabel.text = note
            noteLabel.isHidden = false
        }
    }

    /// The actions for VoiceOver, in order, as custom actions of the whole entry.
    var spokenActions: [(AirFileAction, String)] {
        return zip(actions, actionButtons).map { ($0.0, $0.1.accessibilityLabel ?? "") }
    }

    /// What the row says to VoiceOver: the name, the size, how much is left or what it waits for.
    var spokenText: String {
        var parts: [String] = []
        if !head.isHidden {
            parts.append(contentsOf: [nameLabel.text, sizeLabel.text].compactMap { $0 }.filter { !$0.isEmpty })
        }
        if !noteLabel.isHidden, let note = noteLabel.text, !note.isEmpty {
            parts.append(note)
        }
        return parts.joined(separator: ", ")
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        applyColors()
    }

    private func setActions(_ wanted: [AirFileAction], incoming: Bool) {
        actionButtons.forEach { $0.removeFromSuperview() }
        actions = wanted
        actionButtons = wanted.map { action in
            // The action the entry waits for (Accept, Retry) in ink, the way out in grey.
            let primary = action == .accept || action == .retry || action == .resume
            let button = AirQuietButton(title: AirFileEntryText.title(for: action, incoming: incoming),
                                        color: primary ? KQColor.ink : KQColor.ink2)
            button.accessibilityIdentifier = "air.file.\(AirFileRowView.identifier(for: action))"
            button.addTarget(self, action: #selector(actionTapped(_:)), for: .touchUpInside)
            return button
        }
        actionButtons.forEach { actionsStack.addArrangedSubview($0) }
    }

    @objc private func actionTapped(_ sender: AirQuietButton) {
        guard let index = actionButtons.firstIndex(where: { $0 === sender }), index < actions.count else {
            return
        }
        onAction?(actions[index])
    }

    private func applyColors() {
        iconView.tintColor = KQColor.ink2
        nameLabel.textColor = KQColor.ink
        sizeLabel.textColor = KQColor.ink3
    }

    static func identifier(for action: AirFileAction) -> String {
        switch action {
            case .accept:
                return "accept"
            case .decline:
                return "decline"
            case .pause:
                return "pause"
            case .resume:
                return "resume"
            case .cancel:
                return "cancel"
            case .retry:
                return "retry"
        }
    }
}
