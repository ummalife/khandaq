// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import SnapKit

private struct Constants {
    static let TextViewMinWidth = 5.0
    static let TextViewMaxWidth = 260.0
    static let TextViewMinHeight = 10.0
    static let MapHeight = 120.0

    static let TextViewVerticalOffset = 1.0
    static let TextViewHorizontalOffset = 5.0
}

/// The text of a bubble. KHANDAQ (owner, 28.11.2026): TextKit 1, whose layout manager says where the last line of the
/// text and its baseline are (BubbleView.layoutTrailingMeta); a copy of a selection leaves out the room kept there.
private final class BubbleTextView: UITextView {
    override func copy(_ sender: Any?) {
        let selection = selectedRange
        guard let text = attributedText, selection.length > 0, NSMaxRange(selection) <= text.length else {
            super.copy(sender)
            return
        }
        let selected = NSMutableAttributedString(attributedString: text.attributedSubstring(from: selection))
        guard BubbleView.removeTrailingMetaRoom(from: selected) else {
            super.copy(sender)
            return
        }
        UIPasteboard.general.string = selected.string
    }
}

/// KHANDAQ (owner, 28.11.2026): which way the last paragraph of a text runs, as the text system lays it out, for where
/// it ends: the time of a transcript entry stands at the end of its text (BubbleView.showTrailingMeta).
enum AirTextDirection {
    private static let cache: NSCache<NSString, NSNumber> = {
        let cache = NSCache<NSString, NSNumber>()
        cache.countLimit = 512
        return cache
    }()

    /// Whether the last paragraph of `text` runs right to left (paragraphRunsRightToLeft); an empty one, after a
    /// text that ends a paragraph, runs as the interface does.
    static func lastParagraphRunsRightToLeft(_ text: NSAttributedString, interfaceRightToLeft: Bool) -> Bool {
        let string = text.string as NSString
        guard string.length > 0 else {
            return interfaceRightToLeft
        }
        let last = string.character(at: string.length - 1)
        // A text that ends a paragraph ends with an empty one.
        if last == 0x0A || last == 0x0D || last == 0x85 || last == 0x2029 {
            return interfaceRightToLeft
        }
        var start = 0
        var end = 0
        var contentsEnd = 0
        string.getParagraphStart(&start, end: &end, contentsEnd: &contentsEnd, for: NSRange(location: string.length - 1, length: 0))
        let paragraph = text.attributedSubstring(from: NSRange(location: start, length: contentsEnd - start))
        return paragraphRunsRightToLeft(paragraph, interfaceRightToLeft: interfaceRightToLeft)
    }

    /// Whether `paragraph` (one paragraph without its separator) runs right to left: by its letters
    /// (paragraphEndsLeft). One of figures without a letter runs left to right, as figures are read in every language,
    /// so a phone number or the coordinates of a place keep their order in an Arabic interface; one of neither
    /// (emoji, signs) runs as the interface does.
    static func paragraphRunsRightToLeft(_ paragraph: NSAttributedString, interfaceRightToLeft: Bool) -> Bool {
        if let endsLeft = paragraphEndsLeft(paragraph) {
            return endsLeft
        }
        if paragraph.string.unicodeScalars.contains(where: { $0.properties.numericType == .decimal }) {
            return false
        }
        return interfaceRightToLeft
    }

    /// Whether `paragraph` (one paragraph without its separator) runs right to left by its own letters, so it ends
    /// at its left. The text system lays it out on one line with a mark after it, without any paragraph style, so the
    /// first letter with a direction decides (the bidirectional algorithm), and the mark stands on the side the
    /// paragraph ends on. nil when the paragraph has no letter to take a direction from (figures, emoji), or is empty.
    static func paragraphEndsLeft(_ paragraph: NSAttributedString) -> Bool? {
        let text = paragraph.string
        guard !text.isEmpty, text.unicodeScalars.contains(where: { $0.properties.isAlphabetic }) else {
            return nil
        }
        let key = text as NSString
        if let known = cache.object(forKey: key) {
            return known.boolValue
        }

        let probe = NSMutableAttributedString(attributedString: paragraph)
        // The direction of the words, not one a style gave them (BubbleView aligns each paragraph by this).
        probe.removeAttribute(.paragraphStyle, range: NSRange(location: 0, length: probe.length))
        let mark = NSTextAttachment()
        mark.bounds = CGRect(x: 0, y: 0, width: 1, height: 1)
        probe.append(NSAttributedString(attachment: mark))
        let storage = NSTextStorage(attributedString: probe)
        let layout = NSLayoutManager()
        let container = NSTextContainer(size: CGSize(width: 1_000_000, height: CGFloat.greatestFiniteMagnitude))
        container.lineFragmentPadding = 0
        layout.addTextContainer(container)
        storage.addLayoutManager(layout)
        layout.ensureLayout(for: container)

        let glyph = layout.glyphIndexForCharacter(at: probe.length - 1)
        var line = NSRange(location: 0, length: 0)
        _ = layout.lineFragmentRect(forGlyphAt: glyph, effectiveRange: &line)
        guard glyph > line.location else {
            return nil
        }
        let words = layout.boundingRect(forGlyphRange: NSRange(location: line.location, length: glyph - line.location), in: container)
        let markRect = layout.boundingRect(forGlyphRange: NSRange(location: glyph, length: 1), in: container)
        guard words.width > 0.5 else {
            return nil
        }
        let endsLeft = markRect.midX < words.midX
        cache.setObject(NSNumber(value: endsLeft), forKey: key)
        return endsLeft
    }
}

class BubbleView: UIView {
    fileprivate var textView: UITextView!
    fileprivate var mapImageView: UIImageView?
    fileprivate var locationTapRecognizer: UITapGestureRecognizer?
    let replyQuoteView = ChatReplyQuoteView()
    // KHANDAQ (#192, 0.2.61): the reactions under the text, as chips (AirReactionChipsView).
    let reactionsView = AirReactionChipsView()
    // KHANDAQ (#iOS edited-marker): «изменено» rendered as its OWN bottom metadata sub-row (like the
    // reactions line) instead of being appended inline into the body — so it reads as a system note,
    // not as text the sender typed onto the end of their message.
    fileprivate let editedLabel = UILabel()
    // KHANDAQ (owner, 28.11.2026): the line "Relayed from history, sender not verified" under a group entry is gone:
    // the owner found the yellow notice would annoy people. Every row from a client that does not sign (the desktop)
    // carried it. A synced row still never notifies (NotificationCoordinator), and an author whose signing key went
    // stale is still not named (ChatGroupController+Air, KQ-03).
    //
    // KHANDAQ (owner, 28.11.2026): the end of a transcript entry, its time and our mark (AirEntryMeta), at the
    // trailing edge of the text's last line or on a line of its own under it (showTrailingMeta).
    private var trailingMeta: AirEntryMeta?
    /// The width the text keeps after its last character for the meta: the gap and the meta.
    private var trailingMetaRoom: CGFloat = 0
    /// The size of the meta, and how far its baseline is from its top.
    private var trailingMetaSize = CGSize.zero
    private var trailingMetaBaseline: CGFloat = 0
    /// Whether the room starts a line of its own: the text's last paragraph runs against the interface (Arabic in a
    /// Russian row), so it ends at the far edge and its last line has no room at the trailing one.
    private var trailingMetaOwnLine = false
    /// The direction of the interface the room was laid out for.
    private var trailingMetaRightToLeft = false
    /// The mark the characters of the room carry, so the text can be set again without a second room.
    fileprivate static let trailingMetaRoomKey = NSAttributedString.Key("KQTrailingMetaRoom")
    // KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): a private message in the feed of a group says
    // on its first line who sees it, behind a small lock, in the private colour.
    fileprivate let privateNoteRow = UIStackView()
    fileprivate let privateNoteLabel = UILabel()
    fileprivate var mentionTapRecognizer: UITapGestureRecognizer?
    /// A tap on a member the text names (styleMentions), with the member's key in the group.
    var onMentionTap: ((String) -> Void)?
    var onReactionsTap: (() -> Void)?
    // KHANDAQ (#100): keep the quote view's tap handler in sync no matter the assignment order.
    // The cell sets onReplyQuoteTap AFTER calling bindReplyQuote, so without this didSet the quote's
    // onTap was left nil (set to the then-nil handler inside bindReplyQuote) and tapping a reply quote
    // did nothing in both 1:1 and group chats.
    var onReplyQuoteTap: (() -> Void)? {
        didSet {
            replyQuoteView.onTap = onReplyQuoteTap
        }
    }
    var onLocationTap: (() -> Void)?

    /// KHANDAQ (redesign stage 2): a transcript entry rather than a bubble: no plate and no corner, the
    /// text flush with the text column, the notes under it in grey caption type.
    private(set) var isTranscript = false

    private var verticalInset: Double {
        return isTranscript ? 0 : Constants.TextViewVerticalOffset
    }

    private var horizontalInset: Double {
        return isTranscript ? 0 : Constants.TextViewHorizontalOffset
    }

    private var noteInset: Double {
        return isTranscript ? 0 : Constants.TextViewHorizontalOffset + 4.0
    }

    private var noteBottomInset: Double {
        return isTranscript ? 0 : 4.0
    }

    private var maxTextWidth: Double {
        return isTranscript ? Double(KQSize.textMeasure) : Constants.TextViewMaxWidth
    }

    /// The text without the room kept for the meta (showTrailingMeta).
    var text: String? {
        get {
            return attributedText?.string
        }
        set {
            locationCoordinates = nil
            textView.text = newValue
            applyTrailingMetaRoom()
        }
    }

    /// The text without the room kept for the meta (showTrailingMeta).
    var attributedText: NSAttributedString? {
        get {
            guard let text = textView.attributedText else {
                return nil
            }
            let plain = NSMutableAttributedString(attributedString: text)
            BubbleView.removeTrailingMetaRoom(from: plain)
            return plain
        }
        set {
            locationCoordinates = nil
            textView.attributedText = newValue
            applyTrailingMetaRoom()
        }
    }

    /// KHANDAQ (owner, 28.11): the coordinates under a place's map, nil for any other text. They are the map's
    /// caption, small grey figures, rather than a message of body size; kept here so the font and the colour each
    /// cell gives the whole text after the base setup style them again instead of wiping that look.
    private var locationCoordinates: String?

    func showLocationCoordinates(_ coordinates: String) {
        locationCoordinates = coordinates
        styleLocationCoordinates()
    }

    private func styleLocationCoordinates() {
        guard let coordinates = locationCoordinates else {
            return
        }
        let color = isTranscript ? KQColor.ink3 : (textView.textColor ?? KQColor.ink).withAlphaComponent(0.7)
        textView.attributedText = NSAttributedString(string: coordinates, attributes: [
            .font: KQFont.tabular(KQFont.font(.caption)),
            .foregroundColor: color,
        ])
        applyTrailingMetaRoom()
    }

    var textColor: UIColor {
        get {
            return textView.textColor!
        }
        set {
            textView.textColor = newValue
            styleLocationCoordinates()
            if isTranscript {
                editedLabel.textColor = KQColor.ink3
                return
            }
            // subdued so «изменено» reads as metadata (mirrors the timestamp/status tint intent).
            editedLabel.textColor = newValue.withAlphaComponent(0.5)
        }
    }

    var font: UIFont? {
        get {
            return textView.font
        }
        set {
            textView.font = newValue
            styleLocationCoordinates()
            // The font of the whole text reached the room too, which keeps the meta's type.
            applyTrailingMetaRoom()
        }
    }

    override var tintColor: UIColor! {
        didSet {
            textView.linkTextAttributes = [
                NSAttributedStringKey.foregroundColor.rawValue: tintColor,
                NSAttributedStringKey.underlineStyle.rawValue: NSUnderlineStyle.styleSingle.rawValue,
            ]
        }
    }

    var selectable: Bool {
        get {
            return textView.isSelectable
        }
        set {
            textView.isSelectable = newValue
        }
    }

    /// Switches this view to the transcript look; a cell calls it once, when it creates the view.
    func useTranscriptStyle() {
        guard !isTranscript else {
            return
        }
        isTranscript = true
        layer.cornerRadius = 0
        layer.masksToBounds = false
        backgroundColor = .clear
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.font = KQFont.font(.body)
        textView.adjustsFontForContentSizeCategory = true
        editedLabel.font = KQFont.font(.caption)
        editedLabel.adjustsFontForContentSizeCategory = true
        mapImageView?.layer.cornerRadius = KQRadius.card
        replyQuoteView.useTranscriptStyle()
        updateTextConstraints(hasMap: mapImageView?.isHidden == false)
    }

    func setLocationMapImage(_ image: UIImage?) {
        if let image = image {
            showMapTile(image, fills: true)
        }
        else {
            mapImageView?.isHidden = true
            mapImageView?.image = nil
            textView.dataDetectorTypes = .all
            removeLocationTapRecognizer()
            updateTextConstraints(hasMap: false)
        }
    }

    /// KHANDAQ (location row): holds the place of a map whose snapshot is still being made. The table
    /// measures a row when the cell is set up and the snapshot comes later, so without the tile the row
    /// was measured for the coordinates alone and the map, when it came, was drawn over the rows below.
    /// The tile has the size the snapshot will have and the glyph of "Location" in the attachment menu,
    /// so a snapshot that never comes (no network) still leaves a place to tap and open in Maps.
    func showLocationMapPlaceholder() {
        let glyph = UIImage(systemName: "location",
                            withConfiguration: UIImage.SymbolConfiguration(pointSize: 20.0, weight: .regular))
        showMapTile(glyph, fills: false)
    }

    private func showMapTile(_ image: UIImage?, fills: Bool) {
        ensureMapImageView()
        mapImageView?.contentMode = fills ? .scaleAspectFill : .center
        mapImageView?.image = image
        mapImageView?.isHidden = false
        textView.dataDetectorTypes = []
        installLocationTapRecognizer()
        updateTextConstraints(hasMap: true)
    }

    func bindReplyQuote(_ meta: MessageReplyHelper.ReplyMeta?, theme: Theme) {
        replyQuoteView.bind(meta: meta, theme: theme)
        replyQuoteView.onTap = onReplyQuoteTap
        updateTextConstraints(hasMap: mapImageView?.isHidden == false)
    }

    // KHANDAQ (#192): show/hide the reaction chips. None hides them.
    func bindReactions(_ chips: [AirReactionChip]) {
        reactionsView.bind(chips)
        reactionsView.isHidden = chips.isEmpty
        updateTextConstraints(hasMap: mapImageView?.isHidden == false)
    }

    /// The note over a private message of a group, or nil for any other message.
    func bindPrivateNote(_ note: String?) {
        privateNoteLabel.text = note
        privateNoteRow.isHidden = note == nil
        updateTextConstraints(hasMap: mapImageView?.isHidden == false)
    }

    /// The key a mention of a member carries in the text it is drawn in.
    static let mentionKeyAttribute = NSAttributedString.Key("KQMentionKey")

    /// Draws the members the text names in `color` and `font`; a tap on one runs onMentionTap. Called after the text,
    /// its font and its colour are set: setting those again draws the whole text in one font and colour.
    func styleMentions(_ mentions: [(range: NSRange, key: String)], color: UIColor, font: UIFont) {
        guard !mentions.isEmpty, let current = textView.attributedText, current.length > 0 else {
            return
        }
        let styled = NSMutableAttributedString(attributedString: current)
        for mention in mentions where NSMaxRange(mention.range) <= styled.length {
            styled.addAttributes([
                .foregroundColor: color,
                .font: font,
                BubbleView.mentionKeyAttribute: mention.key,
            ], range: mention.range)
        }
        textView.attributedText = styled
        if mentionTapRecognizer == nil {
            let recognizer = UITapGestureRecognizer(target: self, action: #selector(handleMentionTap(_:)))
            recognizer.delegate = self
            textView.addGestureRecognizer(recognizer)
            mentionTapRecognizer = recognizer
        }
    }

    /// The key of the mention under `recognizer`'s touch, nil when the touch is on anything else.
    fileprivate func mentionKey(at recognizer: UIGestureRecognizer) -> String? {
        guard let text = textView.attributedText, text.length > 0 else {
            return nil
        }
        let point = recognizer.location(in: textView)
        guard let position = textView.closestPosition(to: point) else {
            return nil
        }
        let offset = textView.offset(from: textView.beginningOfDocument, to: position)
        // The caret nearest to the touch may stand after the character touched.
        for index in [offset, offset - 1] where index >= 0 && index < text.length {
            var range = NSRange(location: 0, length: 0)
            guard let key = text.attribute(BubbleView.mentionKeyAttribute, at: index, effectiveRange: &range) as? String,
                  let start = textView.position(from: textView.beginningOfDocument, offset: range.location),
                  let end = textView.position(from: start, offset: range.length),
                  let textRange = textView.textRange(from: start, to: end) else {
                continue
            }
            let rects = textView.selectionRects(for: textRange).compactMap { ($0 as? UITextSelectionRect)?.rect }
            if rects.contains(where: { $0.insetBy(dx: -4, dy: -4).contains(point) }) {
                return key
            }
        }
        return nil
    }

    @objc fileprivate func handleMentionTap(_ recognizer: UITapGestureRecognizer) {
        if let key = mentionKey(at: recognizer) {
            onMentionTap?(key)
        }
    }

    // KHANDAQ (#iOS edited-marker): show/hide the «изменено» metadata row (its own bottom sub-row).
    func bindEdited(_ isEdited: Bool) {
        // A hidden label still takes the width of its text in the layout: without text it asks for
        // none, so a short message keeps a short bubble.
        editedLabel.text = isEdited ? String(localized: "message_edited_marker") : nil
        editedLabel.isHidden = !isEdited
        updateTextConstraints(hasMap: mapImageView?.isHidden == false)
    }

    /// KHANDAQ (owner, 28.11.2026): ends the text with `meta` (AirEntryMeta), as Telegram ends a message with its time:
    /// at the trailing edge of the text's last line when that line leaves the gap and the meta free, otherwise on a
    /// line of its own under the text, at the same edge. The text keeps the room for it after its last character, an
    /// empty attachment as wide as the gap and the meta and as tall as the meta: the text system wraps it with the
    /// text, so the row is measured with the meta wherever it goes, and layoutSubviews puts the meta on the room's
    /// line. A cell calls it last, after every style of the text; setting the text again keeps the room.
    func showTrailingMeta(_ meta: AirEntryMeta, gap: CGFloat) {
        if trailingMeta !== meta {
            trailingMeta?.removeFromSuperview()
            trailingMeta = meta
            addSubview(meta)
        }
        // The meta runs the way the row does: in a right-to-left row the mark stands at the far left.
        if semanticContentAttribute != .unspecified {
            meta.semanticContentAttribute = semanticContentAttribute
        }
        meta.translatesAutoresizingMaskIntoConstraints = true
        meta.isHidden = false
        let size = meta.systemLayoutSizeFitting(UILayoutFittingCompressedSize)
        meta.bounds = CGRect(origin: .zero, size: CGSize(width: ceil(size.width), height: ceil(size.height)))
        trailingMetaSize = meta.bounds.size
        trailingMetaBaseline = meta.firstBaselineOffset
        trailingMetaRoom = gap + meta.bounds.width
        applyTrailingMetaRoom()
        setNeedsLayout()
    }

    /// The direction of the interface the bubble is drawn in: forced on the view (the in-app language through the
    /// appearance proxy, a test), or the in-app language's, which a row not yet on screen has not taken.
    private var interfaceRunsRightToLeft: Bool {
        switch semanticContentAttribute {
            case .forceRightToLeft:
                return true
            case .forceLeftToRight:
                return false
            default:
                return window != nil ? effectiveUserInterfaceLayoutDirection == .rightToLeft : AppLanguage.isRightToLeft
        }
    }

    /// Puts the room for the meta after the last character of the text (showTrailingMeta), or takes it away when the
    /// bubble has no meta.
    private func applyTrailingMetaRoom() {
        guard let current = textView.attributedText else {
            return
        }
        let text = NSMutableAttributedString(attributedString: current)
        let hadRoom = BubbleView.removeTrailingMetaRoom(from: text)
        guard let meta = trailingMeta, trailingMetaRoom > 0, text.length > 0 else {
            if hadRoom {
                textView.attributedText = text
            }
            return
        }
        let rightToLeft = interfaceRunsRightToLeft
        trailingMetaRightToLeft = rightToLeft
        BubbleView.alignParagraphs(of: text, rightToLeftInterface: rightToLeft)

        // The room has the meta's type, so a line of its own is as tall as the meta, and the paragraph of the text.
        var attributes: [NSAttributedString.Key: Any] = [
            .font: meta.timeLabel.font ?? KQFont.font(.caption),
            BubbleView.trailingMetaRoomKey: true,
        ]
        if let paragraph = text.attribute(.paragraphStyle, at: text.length - 1, effectiveRange: nil) {
            attributes[.paragraphStyle] = paragraph
        }

        // The end of a paragraph that runs against the interface (Arabic in a Russian row, Russian in an Arabic one)
        // is at the far edge: its last line has no room at the trailing one.
        trailingMetaOwnLine = AirTextDirection.lastParagraphRunsRightToLeft(text, interfaceRightToLeft: rightToLeft) != rightToLeft
        // A line separator keeps the room's line in the paragraph, with its direction; a zero width space lets the
        // room wrap alone when the last word leaves too little after it.
        text.append(NSAttributedString(string: trailingMetaOwnLine ? "\u{2028}" : "\u{200B}", attributes: attributes))
        let attachment = NSTextAttachment()
        attachment.image = BubbleView.emptyImage
        let descent = trailingMetaSize.height - trailingMetaBaseline
        attachment.bounds = CGRect(x: 0, y: -descent, width: trailingMetaRoom, height: trailingMetaSize.height)
        let room = NSMutableAttributedString(attachment: attachment)
        room.addAttributes(attributes, range: NSRange(location: 0, length: room.length))
        text.append(room)
        textView.attributedText = text
    }

    /// KHANDAQ (owner, 28.11.2026): each paragraph of an entry stands at the side its own direction starts from, as on
    /// Android and the desktop: an Arabic one at the right in an English interface, an English one at the left in an
    /// Arabic interface (AirTextDirection.paragraphRunsRightToLeft). The natural alignment of a text view follows the
    /// language of the app instead: it left an Arabic paragraph at the left of an English row, with its time on the
    /// line under it at the right.
    private static func alignParagraphs(of text: NSMutableAttributedString, rightToLeftInterface: Bool) {
        let string = text.string as NSString
        let options: NSString.EnumerationOptions = [.byParagraphs, .substringNotRequired]
        string.enumerateSubstrings(in: NSRange(location: 0, length: string.length), options: options) { _, range, enclosing, _ in
            let paragraph = text.attributedSubstring(from: range)
            let rightToLeft = AirTextDirection.paragraphRunsRightToLeft(paragraph, interfaceRightToLeft: rightToLeftInterface)
            let current = text.attribute(.paragraphStyle, at: enclosing.location, effectiveRange: nil) as? NSParagraphStyle
            let style = (current?.mutableCopy() as? NSMutableParagraphStyle) ?? NSMutableParagraphStyle()
            style.baseWritingDirection = rightToLeft ? .rightToLeft : .leftToRight
            style.alignment = rightToLeft ? .right : .left
            text.addAttribute(.paragraphStyle, value: style, range: enclosing)
        }
    }

    /// Takes the room for the meta out of `text`; says whether it had one.
    @discardableResult
    fileprivate static func removeTrailingMetaRoom(from text: NSMutableAttributedString) -> Bool {
        var ranges = [NSRange]()
        text.enumerateAttribute(trailingMetaRoomKey, in: NSRange(location: 0, length: text.length), options: []) { value, range, _ in
            if value != nil {
                ranges.append(range)
            }
        }
        for range in ranges.reversed() {
            text.deleteCharacters(in: range)
        }
        return !ranges.isEmpty
    }

    private static let emptyImage: UIImage = {
        let format = UIGraphicsImageRendererFormat.default()
        format.opaque = false
        return UIGraphicsImageRenderer(size: CGSize(width: 1, height: 1), format: format).image { _ in }
    }()

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutTrailingMeta()
    }

    /// A row turned the other way after its text was set (the in-app language, a test that mirrors it) keeps the room
    /// for its meta where the new direction needs it.
    override var semanticContentAttribute: UISemanticContentAttribute {
        didSet {
            trailingMeta?.semanticContentAttribute = semanticContentAttribute
            guard trailingMeta != nil, interfaceRunsRightToLeft != trailingMetaRightToLeft else {
                return
            }
            applyTrailingMetaRoom()
            setNeedsLayout()
        }
    }

    /// Places the meta on the line of its room, with its baseline on the line's: at the trailing edge of the text
    /// column. Where the words of that line still stand on the trailing side of the room (a direction the guess
    /// missed), the meta takes the room's own place instead, so it never stands over a word.
    private func layoutTrailingMeta() {
        guard let meta = trailingMeta, !meta.isHidden, let text = textView.attributedText, text.length > 0,
              text.attribute(BubbleView.trailingMetaRoomKey, at: text.length - 1, effectiveRange: nil) != nil else {
            return
        }
        // The text view lays its text out at the width the bubble has just given it.
        textView.layoutIfNeeded()
        let layout = textView.layoutManager
        let container = textView.textContainer
        layout.ensureLayout(for: container)
        let glyph = layout.glyphIndexForCharacter(at: text.length - 1)
        var lineGlyphs = NSRange(location: 0, length: 0)
        let line = layout.lineFragmentRect(forGlyphAt: glyph, effectiveRange: &lineGlyphs)
        let room = layout.boundingRect(forGlyphRange: NSRange(location: glyph, length: 1), in: container)
        // TextKit 1 puts the location of an attachment at the bottom of its bounds, not on the baseline: the room is
        // as tall as the meta and stands on the line's baseline as the meta's words should, so the meta takes its
        // top. (The bounding rect of the room is as tall as its whole line.)
        let attachmentSize = layout.attachmentSize(forGlyphAt: glyph)
        let roomHeight = attachmentSize.height > 0 ? attachmentSize.height : trailingMetaSize.height
        let roomTop = line.minY + layout.location(forGlyphAt: glyph).y - roomHeight
        let inset = textView.textContainerInset
        let column = textView.frame
        let width = meta.bounds.width

        // The words on the room's line, without the room and the space before it.
        var words: CGRect?
        if glyph > lineGlyphs.location {
            let range = NSRange(location: lineGlyphs.location, length: glyph - lineGlyphs.location)
            let rect = layout.boundingRect(forGlyphRange: range, in: container)
            if rect.width > 0.5 {
                words = rect
            }
        }

        let x: CGFloat
        if let words = words, trailingMetaRightToLeft, room.minX >= words.midX {
            // A line that ends at the right in a right-to-left row: the meta ends the room there.
            x = column.minX + inset.left + room.maxX - width
        }
        else if let words = words, !trailingMetaRightToLeft, room.maxX <= words.midX {
            // A line that ends at the left in a left-to-right row: the meta starts the room there.
            x = column.minX + inset.left + room.minX
        }
        else {
            x = trailingMetaRightToLeft ? column.minX : column.maxX - width
        }
        meta.frame.origin = CGPoint(x: x, y: column.minY + inset.top + roomTop)
    }

    fileprivate func ensureMapImageView() {
        guard mapImageView == nil else {
            return
        }

        let imageView = UIImageView()
        imageView.contentMode = .scaleAspectFill
        imageView.clipsToBounds = true
        imageView.isUserInteractionEnabled = true
        // Seen only while the snapshot is being made (showLocationMapPlaceholder): the snapshot fills
        // the whole tile.
        imageView.backgroundColor = KQColor.sunken
        imageView.tintColor = KQColor.ink3
        if isTranscript {
            imageView.layer.cornerRadius = KQRadius.card
        }
        addSubview(imageView)
        mapImageView = imageView

        imageView.snp.makeConstraints {
            $0.top.equalTo(self).offset(verticalInset)
            $0.leading.equalTo(self).offset(horizontalInset)
            if isTranscript {
                $0.trailing.lessThanOrEqualTo(self)
                // KHANDAQ (tester 21.11, 8255): the map gives way where its column is narrower than it, as the
                // preview of a photo does (LoadingImageView): beside the tile of a group member's entry, and on a
                // narrow phone. A required width made Auto Layout break whichever constraint it liked.
                $0.width.equalTo(Constants.TextViewMaxWidth).priority(999)
            }
            else {
                $0.trailing.equalTo(self).offset(-horizontalInset)
            }
            $0.width.lessThanOrEqualTo(Constants.TextViewMaxWidth)
            $0.height.equalTo(Constants.MapHeight)
        }
    }

    fileprivate func updateTextConstraints(hasMap: Bool) {
        privateNoteRow.snp.remakeConstraints {
            $0.top.equalTo(self).offset(verticalInset)
            $0.leading.equalTo(self).offset(horizontalInset)
            $0.trailing.lessThanOrEqualTo(self).offset(-horizontalInset)
        }

        replyQuoteView.snp.remakeConstraints {
            if privateNoteRow.isHidden {
                $0.top.equalTo(self).offset(verticalInset)
            }
            else {
                $0.top.equalTo(privateNoteRow.snp.bottom).offset(KQSpace.x4)
            }
            $0.leading.equalTo(self).offset(horizontalInset)
            $0.trailing.equalTo(self).offset(-horizontalInset)
        }

        textView.snp.remakeConstraints {
            // Transcript entries keep a small gap under a quote or a map; bubbles keep theirs.
            let gapBelow = isTranscript ? Double(KQSpace.x4) : Constants.TextViewVerticalOffset
            if replyQuoteView.isHidden {
                if hasMap, let mapImageView = mapImageView {
                    $0.top.equalTo(mapImageView.snp.bottom).offset(gapBelow)
                }
                else if !privateNoteRow.isHidden {
                    $0.top.equalTo(privateNoteRow.snp.bottom).offset(KQSpace.x4)
                }
                else {
                    $0.top.equalTo(self).offset(verticalInset)
                }
            }
            else {
                $0.top.equalTo(replyQuoteView.snp.bottom).offset(gapBelow)
            }

            // KHANDAQ (#iOS edited-marker): the bubble bottom is now the lowest visible of
            // {textView, reactionsView, editedLabel} — only pin textView to the bottom when every
            // one of them is hidden.
            if reactionsView.isHidden && editedLabel.isHidden {
                $0.bottom.equalTo(self).offset(-verticalInset)
            }
            $0.leading.equalTo(self).offset(horizontalInset)
            $0.trailing.equalTo(self).offset(-horizontalInset)

            $0.width.greaterThanOrEqualTo(Constants.TextViewMinWidth)
            $0.width.lessThanOrEqualTo(maxTextWidth)
            $0.height.greaterThanOrEqualTo(Constants.TextViewMinHeight)
        }

        reactionsView.snp.remakeConstraints {
            // The chips stand a little apart from the text, as on Android and the desktop.
            $0.top.equalTo(textView.snp.bottom).offset(KQSpace.x4)
            $0.leading.equalTo(self).offset(noteInset)
            $0.trailing.lessThanOrEqualTo(self).offset(-horizontalInset)
            // pin to bottom only when it's the last visible row (nothing below it).
            if !reactionsView.isHidden && editedLabel.isHidden {
                $0.bottom.equalTo(self).offset(-noteBottomInset)
            }
        }

        // KHANDAQ (#iOS edited-marker): «изменено» sits on its OWN row below the text (and below the
        // reactions line when present), left-aligned so it never collides with the outgoing checkmark.
        editedLabel.snp.remakeConstraints {
            if reactionsView.isHidden {
                $0.top.equalTo(textView.snp.bottom).offset(1.0)
            }
            else {
                $0.top.equalTo(reactionsView.snp.bottom).offset(1.0)
            }
            $0.leading.equalTo(self).offset(noteInset)
            $0.trailing.lessThanOrEqualTo(self).offset(-horizontalInset)
            if !editedLabel.isHidden {
                $0.bottom.equalTo(self).offset(-noteBottomInset)
            }
        }
    }

    fileprivate func installLocationTapRecognizer() {
        guard locationTapRecognizer == nil else {
            return
        }

        let recognizer = UITapGestureRecognizer(target: self, action: #selector(handleLocationTap))
        addGestureRecognizer(recognizer)
        locationTapRecognizer = recognizer
    }

    fileprivate func removeLocationTapRecognizer() {
        if let recognizer = locationTapRecognizer {
            removeGestureRecognizer(recognizer)
            locationTapRecognizer = nil
        }
    }

    @objc fileprivate func handleLocationTap() {
        onLocationTap?()
    }

    override init(frame: CGRect) {
        super.init(frame: frame)

        // KHANDAQ design (Figma): message bubbles use a softer ~16pt corner radius.
        layer.cornerRadius = 16.0
        layer.masksToBounds = true

        if #available(iOS 16.0, *) {
            textView = BubbleTextView(usingTextLayoutManager: false)
        }
        else {
            textView = BubbleTextView()
        }
        textView.backgroundColor = .clear
        textView.isEditable = false
        textView.isScrollEnabled = false
        textView.dataDetectorTypes = .all
        textView.font = UIFont.systemFont(ofSize: 16.0)

        privateNoteLabel.font = KQFont.font(.caption)
        privateNoteLabel.adjustsFontForContentSizeCategory = true
        privateNoteLabel.textColor = KQColor.private
        privateNoteLabel.numberOfLines = 0
        let lock = UIImageView(image: AirIcon.image("lock"))
        lock.tintColor = KQColor.private
        lock.contentMode = .scaleAspectFit
        lock.isAccessibilityElement = false
        lock.snp.makeConstraints {
            $0.size.equalTo(KQSize.iconSmall)
        }
        privateNoteRow.axis = .horizontal
        privateNoteRow.alignment = .center
        privateNoteRow.spacing = KQSpace.x4
        privateNoteRow.addArrangedSubview(lock)
        privateNoteRow.addArrangedSubview(privateNoteLabel)
        privateNoteRow.isHidden = true
        privateNoteRow.accessibilityIdentifier = "air.entry.privateNote"
        addSubview(privateNoteRow)
        addSubview(replyQuoteView)
        addSubview(textView)

        reactionsView.isHidden = true
        reactionsView.onTap = { [weak self] in
            self?.onReactionsTap?()
        }
        addSubview(reactionsView)

        editedLabel.font = UIFont.italicSystemFont(ofSize: 11.0)
        editedLabel.isHidden = true
        addSubview(editedLabel)

        textView.snp.makeConstraints {
            $0.top.equalTo(self).offset(Constants.TextViewVerticalOffset)
            $0.bottom.equalTo(self).offset(-Constants.TextViewVerticalOffset)
            $0.leading.equalTo(self).offset(Constants.TextViewHorizontalOffset)
            $0.trailing.equalTo(self).offset(-Constants.TextViewHorizontalOffset)

            $0.width.greaterThanOrEqualTo(Constants.TextViewMinWidth)
            $0.width.lessThanOrEqualTo(Constants.TextViewMaxWidth)
            $0.height.greaterThanOrEqualTo(Constants.TextViewMinHeight)
        }
    }

    required convenience init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }
}


extension BubbleView: UIGestureRecognizerDelegate {
    /// The tap on a mention starts only on a mention: every other touch of the text stays the text view's.
    override func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard gestureRecognizer === mentionTapRecognizer else {
            return super.gestureRecognizerShouldBegin(gestureRecognizer)
        }
        return onMentionTap != nil && mentionKey(at: gestureRecognizer) != nil
    }

    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldRecognizeSimultaneouslyWith otherGestureRecognizer: UIGestureRecognizer) -> Bool {
        return gestureRecognizer === mentionTapRecognizer
    }
}

// MARK: - KHANDAQ (#192) Telegram-style reaction picker

/// Horizontal floating reaction pill (Telegram-style): a rounded capsule of quick-emoji buttons +
/// a trailing "expand" button for arbitrary emoji. Replaces the old vertical action-sheet picker.
/// Uses only pre-iOS-13 UIKit (target-action, solid background) for deployment-target compatibility.
final class ChatReactionBar: UIView {
    static let quickReactions = ["❤️", "👍", "👎", "😂", "😮", "😢", "🔥"]

    var onPick: ((String) -> Void)?
    var onExpand: (() -> Void)?

    static let barHeight: CGFloat = 52
    static func barWidth() -> CGFloat {
        // leading(12) + N*44 + N*4 spacing + expand(34) + trailing(10)
        let n = CGFloat(quickReactions.count)
        return 12 + n * 44 + n * 4 + 34 + 10
    }

    init(currentEmoji: String?, dark: Bool) {
        super.init(frame: .zero)

        backgroundColor = dark ? UIColor(white: 0.16, alpha: 0.98) : UIColor(white: 0.97, alpha: 0.98)
        layer.cornerRadius = ChatReactionBar.barHeight / 2
        layer.masksToBounds = true

        let stack = UIStackView()
        stack.axis = .horizontal
        stack.alignment = .center
        stack.spacing = 4
        addSubview(stack)
        stack.snp.makeConstraints {
            $0.centerY.equalToSuperview()
            $0.leading.equalToSuperview().offset(12)
            $0.trailing.equalToSuperview().offset(-10)
        }

        for emoji in ChatReactionBar.quickReactions {
            let b = UIButton(type: .custom)
            b.setTitle(emoji, for: .normal)
            b.titleLabel?.font = UIFont.systemFont(ofSize: 30)
            b.snp.makeConstraints { $0.width.height.equalTo(44) }
            if emoji == currentEmoji {
                b.backgroundColor = (dark ? UIColor.white : UIColor.black).withAlphaComponent(0.14)
                b.layer.cornerRadius = 22
            }
            b.addTarget(self, action: #selector(emojiTapped(_:)), for: .touchUpInside)
            stack.addArrangedSubview(b)
        }

        // trailing "expand" — opens the full emoji picker for any emoji ("⌄" like Telegram)
        let expand = UIButton(type: .custom)
        expand.setTitle("⌄", for: .normal)
        expand.titleLabel?.font = UIFont.systemFont(ofSize: 22, weight: .semibold)
        expand.setTitleColor(dark ? UIColor.white.withAlphaComponent(0.8) : UIColor.black.withAlphaComponent(0.5), for: .normal)
        expand.backgroundColor = (dark ? UIColor.white : UIColor.black).withAlphaComponent(0.10)
        expand.layer.cornerRadius = 17
        expand.snp.makeConstraints { $0.width.height.equalTo(34) }
        expand.addTarget(self, action: #selector(expandTapped), for: .touchUpInside)
        stack.addArrangedSubview(expand)
    }

    @objc private func emojiTapped(_ sender: UIButton) {
        if let emoji = sender.title(for: .normal) {
            onPick?(emoji)
        }
    }

    @objc private func expandTapped() {
        onExpand?()
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }
}

/// KHANDAQ (#G5 reaction-row): one row of the Telegram-style vertical action menu shown beneath the
/// message bubble, alongside the floating reaction bar. Plain UIKit (target-action) for iOS-12 compat.
struct ChatContextMenuAction {
    let title: String
    let systemImageName: String
    let destructive: Bool
    let handler: () -> Void
    init(title: String, systemImageName: String, destructive: Bool = false, handler: @escaping () -> Void) {
        self.title = title; self.systemImageName = systemImageName; self.destructive = destructive; self.handler = handler
    }
}

/// Presents a `ChatReactionBar` as a floating overlay anchored above (or below) a message bubble,
/// with a tap-catcher backdrop. Also handles "expand" → arbitrary-emoji entry via a hidden
/// emoji-keyboard text field. One instance per chat controller.
final class ChatReactionPopup: NSObject, UITextFieldDelegate {
    private var backdrop: UIView?
    private var bar: ChatReactionBar?
    private var menuCard: UIView?
    private var actionHandlers: [() -> Void] = []
    private var emojiField: UITextField?
    private var pickHandler: ((String) -> Void)?
    private var dismissHandler: (() -> Void)?

    var isVisible: Bool { return backdrop != nil }

    /// Show the bar above `rect` (message-bubble frame in `host` coordinates).
    func present(in host: UIView, aboveRect rect: CGRect, currentEmoji: String?, dark: Bool,
                 onPick: @escaping (String) -> Void) {
        dismiss(animated: false)
        pickHandler = onPick

        let backdrop = UIView(frame: host.bounds)
        backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        backdrop.backgroundColor = UIColor.black.withAlphaComponent(0.28) // Telegram-style dim
        backdrop.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(backdropTapped)))
        host.addSubview(backdrop)
        self.backdrop = backdrop

        let bar = ChatReactionBar(currentEmoji: currentEmoji, dark: dark)
        bar.onPick = { [weak self] emoji in self?.finish(with: emoji) }
        bar.onExpand = { [weak self] in self?.showEmojiKeyboard() }
        bar.layer.shadowColor = UIColor.black.cgColor
        bar.layer.shadowOpacity = 0.22
        bar.layer.shadowRadius = 12
        bar.layer.shadowOffset = CGSize(width: 0, height: 4)
        bar.layer.masksToBounds = false
        backdrop.addSubview(bar)
        self.bar = bar

        let barW = min(ChatReactionBar.barWidth(), host.bounds.width - 24)
        let barH = ChatReactionBar.barHeight

        var x = rect.midX - barW / 2
        x = max(12, min(x, host.bounds.width - barW - 12))
        let topSafe = host.safeAreaInsets.top + 8
        var y = rect.minY - barH - 8
        if y < topSafe { y = rect.maxY + 8 }
        bar.frame = CGRect(x: x, y: y, width: barW, height: barH)

        bar.alpha = 0
        bar.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
        UIView.animate(withDuration: 0.28, delay: 0, usingSpringWithDamping: 0.7,
                       initialSpringVelocity: 0.5, options: [], animations: {
            bar.alpha = 1
            bar.transform = .identity
        }, completion: nil)
    }

    /// KHANDAQ (#G5 reaction-row): full Telegram-style context popup — the reaction bar ABOVE the
    /// bubble and a vertical action menu BELOW it, over one dimmed tap-to-dismiss backdrop. Reuses the
    /// tested bar/backdrop/expand path; the message stays visible under the light dim (no snapshot).
    /// KHANDAQ (26.11.2026): `showsReactions` false leaves the bar out, for an entry no reaction can reach:
    /// a private message of a group has no id the other side could match (Android `groupRowAddressable`).
    func presentMenu(in host: UIView, aroundRect rect: CGRect, currentEmoji: String?, dark: Bool,
                     actions: [ChatContextMenuAction], bubbleSnapshot: UIView? = nil,
                     showsReactions: Bool = true,
                     onPick: @escaping (String) -> Void,
                     onDismiss: (() -> Void)? = nil) {
        dismiss(animated: false)
        pickHandler = onPick
        dismissHandler = onDismiss

        let backdrop = UIView(frame: host.bounds)
        backdrop.autoresizingMask = [.flexibleWidth, .flexibleHeight]
        backdrop.backgroundColor = UIColor.black.withAlphaComponent(0.28)
        backdrop.addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(backdropTapped)))
        host.addSubview(backdrop)
        self.backdrop = backdrop

        // --- lifted bubble snapshot (Telegram-style): the message sits ABOVE the dim, un-dimmed, between
        // the reaction bar and the action card. Additive — if no snapshot is supplied the real (lightly
        // dimmed) bubble shows through as before. Added first so the bar/card render on top. ---
        if let snapshot = bubbleSnapshot {
            snapshot.frame = rect
            backdrop.addSubview(snapshot)
        }

        // --- reaction bar (above) ---
        let bar: ChatReactionBar? = showsReactions ? ChatReactionBar(currentEmoji: currentEmoji, dark: dark) : nil
        if let bar = bar {
            bar.onPick = { [weak self] emoji in self?.finish(with: emoji) }
            bar.onExpand = { [weak self] in self?.showEmojiKeyboard() }
            bar.layer.shadowColor = UIColor.black.cgColor
            bar.layer.shadowOpacity = 0.22
            bar.layer.shadowRadius = 12
            bar.layer.shadowOffset = CGSize(width: 0, height: 4)
            bar.layer.masksToBounds = false
            backdrop.addSubview(bar)
        }
        self.bar = bar

        // --- action menu card (below) ---
        let card = buildMenuCard(actions: actions, dark: dark)
        backdrop.addSubview(card)
        self.menuCard = card

        // --- layout ---
        let hostW = host.bounds.width
        let topSafe = host.safeAreaInsets.top + 8
        let bottomSafe = host.bounds.height - host.safeAreaInsets.bottom - 8

        // Without a bar the card keeps to the bubble: barY and barH then stand for the bubble's own top.
        let barW = min(ChatReactionBar.barWidth(), hostW - 24)
        let barH: CGFloat = bar == nil ? 0 : ChatReactionBar.barHeight
        var barX = rect.midX - barW / 2
        barX = max(12, min(barX, hostW - barW - 12))
        var barY = bar == nil ? rect.minY : rect.minY - barH - 8
        if barY < topSafe { barY = topSafe }
        bar?.frame = CGRect(x: barX, y: barY, width: barW, height: barH)

        let cardW: CGFloat = 250
        let cardH = card.frame.height
        var cardX = rect.minX
        // align the card to the bubble side, clamped on screen
        cardX = max(12, min(cardX, hostW - cardW - 12))
        // KHANDAQ (context-menu-overlap): for the newest/last bubble there is no room BELOW it for the
        // tall action card; the old `bottomSafe - cardH` clamp slid the card UP over the message and
        // covered it. Instead flip the card ABOVE the reaction bar so the last bubble stays fully visible.
        let cardY: CGFloat
        if bottomSafe - rect.maxY >= cardH + 8 {
            cardY = max(rect.maxY + 8, barY + barH + 8)   // room below: keep below the bubble (unchanged)
        }
        else {
            cardY = max(topSafe, barY - 8 - cardH)        // no room below: place above the reaction bar
        }
        card.frame = CGRect(x: cardX, y: cardY, width: cardW, height: cardH)

        var appearing: [(UIView, CGAffineTransform)] = [(card, CGAffineTransform(translationX: 0, y: -12))]
        if let bar = bar {
            appearing.insert((bar, CGAffineTransform(scaleX: 0.6, y: 0.6)), at: 0)
        }
        for (v, t) in appearing {
            v.alpha = 0
            v.transform = t
        }
        UIView.animate(withDuration: 0.28, delay: 0, usingSpringWithDamping: 0.78,
                       initialSpringVelocity: 0.5, options: [], animations: {
            for (v, _) in appearing {
                v.alpha = 1
                v.transform = .identity
            }
        }, completion: nil)
    }

    /// SF Symbol name -> shared (Figma/Material) asset used on both platforms.
    private static let sharedGlyphNames: [String: String] = [
        "arrowshape.turn.up.left": "msg-action-reply",
        "doc.on.doc": "msg-action-copy",
        "pencil": "msg-action-edit",
        "pin": "msg-action-pin",
        "arrowshape.turn.up.right": "msg-action-forward",
        "checkmark.circle": "msg-action-select",
        "trash": "msg-action-delete",
    ]

    static func sharedGlyph(for systemImageName: String) -> UIImage? {
        guard let assetName = sharedGlyphNames[systemImageName] else {
            return nil
        }
        return UIImage(named: assetName)?.withRenderingMode(.alwaysTemplate)
    }

    private func buildMenuCard(actions: [ChatContextMenuAction], dark: Bool) -> UIView {
        let cardW: CGFloat = 250
        let rowH: CGFloat = 48
        let container = UIView()
        container.backgroundColor = dark ? UIColor(white: 0.16, alpha: 0.98) : UIColor(white: 0.98, alpha: 0.98)
        container.layer.cornerRadius = 14
        container.layer.masksToBounds = true

        let stack = UIStackView()
        stack.axis = .vertical
        stack.spacing = 0
        container.addSubview(stack)
        stack.translatesAutoresizingMaskIntoConstraints = false
        NSLayoutConstraint.activate([
            stack.topAnchor.constraint(equalTo: container.topAnchor),
            stack.leadingAnchor.constraint(equalTo: container.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: container.trailingAnchor),
            stack.bottomAnchor.constraint(equalTo: container.bottomAnchor),
        ])

        actionHandlers = actions.map { $0.handler }
        let sepColor = (dark ? UIColor.white : UIColor.black).withAlphaComponent(0.08)
        for (i, action) in actions.enumerated() {
            let row = UIButton(type: .system)
            row.tag = i
            row.contentHorizontalAlignment = .fill
            row.addTarget(self, action: #selector(menuRowTapped(_:)), for: .touchUpInside)
            // KHANDAQ (accessibility): as in the menu of the paperclip, the row is a button without a
            // title (its words are a label inside), so it had no accessibility label of its own. The row
            // is one element named by its action; the label and the icon inside are not read on their own.
            row.accessibilityLabel = action.title

            let tint = action.destructive ? UIColor(red: 1.0, green: 0.23, blue: 0.19, alpha: 1.0)
                                          : (dark ? UIColor.white : UIColor.black)
            let label = UILabel()
            label.text = action.title
            label.font = UIFont.systemFont(ofSize: 16)
            label.textColor = tint
            label.isAccessibilityElement = false

            let icon = UIImageView()
            icon.isAccessibilityElement = false
            icon.contentMode = .scaleAspectFit
            // KHANDAQ (18.08): one icon set across platforms — the context menu draws the same Material
            // glyphs Android uses, falling back to the SF Symbol if an asset is ever missing.
            icon.image = ChatReactionPopup.sharedGlyph(for: action.systemImageName)
                    ?? UIImage(systemName: action.systemImageName)?.withRenderingMode(.alwaysTemplate)
            icon.tintColor = tint

            row.addSubview(label); row.addSubview(icon)
            label.translatesAutoresizingMaskIntoConstraints = false
            icon.translatesAutoresizingMaskIntoConstraints = false
            NSLayoutConstraint.activate([
                label.leadingAnchor.constraint(equalTo: row.leadingAnchor, constant: 16),
                label.centerYAnchor.constraint(equalTo: row.centerYAnchor),
                icon.trailingAnchor.constraint(equalTo: row.trailingAnchor, constant: -16),
                icon.centerYAnchor.constraint(equalTo: row.centerYAnchor),
                icon.widthAnchor.constraint(equalToConstant: 22),
                icon.heightAnchor.constraint(equalToConstant: 22),
            ])
            row.translatesAutoresizingMaskIntoConstraints = false
            row.heightAnchor.constraint(equalToConstant: rowH).isActive = true
            stack.addArrangedSubview(row)

            if i < actions.count - 1 {
                let sep = UIView(); sep.backgroundColor = sepColor
                container.addSubview(sep)
                sep.frame = CGRect(x: 0, y: rowH * CGFloat(i + 1) - 0.5, width: cardW, height: 0.5)
            }
        }
        container.frame = CGRect(x: 0, y: 0, width: cardW, height: rowH * CGFloat(actions.count))
        return container
    }

    @objc private func menuRowTapped(_ sender: UIButton) {
        let idx = sender.tag
        let handlers = actionHandlers
        dismiss(animated: true)
        if idx >= 0, idx < handlers.count { handlers[idx]() }
    }

    @objc private func backdropTapped() { dismiss(animated: true) }

    private func finish(with emoji: String) {
        let handler = pickHandler
        dismiss(animated: true)
        handler?(emoji)
    }

    private func showEmojiKeyboard() {
        guard let backdrop = backdrop else { return }
        // a visually-hidden but on-screen field so the keyboard reliably comes up; the emoji
        // keyboard is requested explicitly via a custom EmojiTextField (keyboard type Default,
        // but we prime it) — the user taps any emoji and it's applied.
        let field = EmojiOnlyTextField(frame: CGRect(x: 20, y: backdrop.bounds.midY, width: 2, height: 20))
        field.delegate = self
        field.autocorrectionType = .no
        field.autocapitalizationType = .none
        field.tintColor = .clear
        field.textColor = .clear
        field.backgroundColor = .clear
        backdrop.addSubview(field)
        emojiField = field
        DispatchQueue.main.async {
            field.becomeFirstResponder()
        }
    }

    // capture the first emoji the user types, apply it
    func textField(_ textField: UITextField, shouldChangeCharactersIn range: NSRange,
                   replacementString string: String) -> Bool {
        let trimmed = string.trimmingCharacters(in: .whitespacesAndNewlines)
        if let emoji = trimmed.first.map(String.init), !emoji.isEmpty {
            textField.resignFirstResponder()
            finish(with: emoji)
        }
        return false
    }

    func dismiss(animated: Bool) {
        emojiField?.resignFirstResponder()
        emojiField?.removeFromSuperview()
        emojiField = nil
        pickHandler = nil
        actionHandlers = []
        let onDismiss = dismissHandler
        dismissHandler = nil
        onDismiss?()
        let bd = backdrop
        let b = bar
        let card = menuCard
        backdrop = nil
        bar = nil
        menuCard = nil
        // A menu without the bar (a private entry of a group) fades its card out the same way.
        guard animated, b != nil || card != nil else { bd?.removeFromSuperview(); return }
        UIView.animate(withDuration: 0.18, animations: {
            b?.alpha = 0
            b?.transform = CGAffineTransform(scaleX: 0.6, y: 0.6)
            card?.alpha = 0
            card?.transform = CGAffineTransform(translationX: 0, y: -12)
        }, completion: { _ in bd?.removeFromSuperview() })
    }
}

/// A text field that opens directly on the emoji keyboard (Telegram-style "expand" for any emoji).
final class EmojiOnlyTextField: UITextField {
    override var textInputMode: UITextInputMode? {
        for mode in UITextInputMode.activeInputModes where mode.primaryLanguage == "emoji" {
            return mode
        }
        return super.textInputMode
    }
}

/// KHANDAQ (0.2.61): the reactions under an entry as chips, as Android draws them
/// (AirTranscript.styleReactionChip) and the desktop paints them (AirEntryNotes): each emoji with its
/// count once more than one person chose it, in the caption and ink, on the raised plate with a hairline
/// and round ends; ours on the selected plate with the strong line. One line from the start edge: a chip
/// that does not fit is left out, as on the desktop. They had been a line of label text in ink-2.
final class AirReactionChipsView: UIView {
    /// A chip as Android sizes it: at least 28 high, 8 of padding at either end and 3 above and below
    /// the text, 4 between chips.
    static let chipHeight = KQSize.controlSmall
    static let padding = KQSpace.x8
    static let verticalPadding: CGFloat = 3
    static let gap = KQSpace.x4

    /// A tap anywhere on the chips.
    var onTap: (() -> Void)?

    private(set) var chips: [AirReactionChip] = []
    private var plates: [UIView] = []
    private var labels: [UILabel] = []
    private var fontCategory = UIApplication.shared.preferredContentSizeCategory

    override init(frame: CGRect) {
        super.init(frame: frame)
        isAccessibilityElement = true
        accessibilityTraits = UIAccessibilityTraitButton
        addGestureRecognizer(UITapGestureRecognizer(target: self, action: #selector(tapped)))
    }

    required init?(coder aDecoder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    /// The chips in words, as VoiceOver says them.
    var spokenText: String {
        return ChatReactionsFormat.display(of: chips) ?? ""
    }

    /// The plates of the chips that show, for the tests: those that fit in the width.
    var shownPlates: [UIView] {
        return plates.filter { !$0.isHidden }
    }

    func bind(_ newChips: [AirReactionChip]) {
        if newChips != chips || plates.count != newChips.count {
            plates.forEach { $0.removeFromSuperview() }
            plates = []
            labels = []
            for chip in newChips {
                let plate = UIView()
                plate.isUserInteractionEnabled = false
                plate.layer.borderWidth = KQStroke.hairline
                let label = UILabel()
                label.font = AirReactionChipsView.font
                label.text = chip.text
                label.isAccessibilityElement = false
                plate.addSubview(label)
                addSubview(plate)
                plates.append(plate)
                labels.append(label)
            }
            chips = newChips
        }
        applyColors()
        accessibilityLabel = spokenText
        invalidateIntrinsicContentSize()
        setNeedsLayout()
    }

    override var intrinsicContentSize: CGSize {
        let sizes = labels.map { plateSize(for: $0) }
        guard !sizes.isEmpty else {
            return .zero
        }
        let width = sizes.reduce(0) { $0 + $1.width } + AirReactionChipsView.gap * CGFloat(sizes.count - 1)
        return CGSize(width: width, height: sizes.map { $0.height }.max() ?? AirReactionChipsView.chipHeight)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let rightToLeft = effectiveUserInterfaceLayoutDirection == .rightToLeft
        var taken: CGFloat = 0
        var full = false
        for (plate, label) in zip(plates, labels) {
            let size = plateSize(for: label)
            full = full || taken + size.width > bounds.width + 0.5
            plate.isHidden = full
            guard !full else {
                continue
            }
            let x = rightToLeft ? bounds.width - taken - size.width : taken
            plate.frame = CGRect(x: x, y: 0, width: size.width, height: size.height)
            plate.layer.cornerRadius = size.height / 2
            label.frame = plate.bounds.insetBy(dx: AirReactionChipsView.padding, dy: 0)
            taken += size.width + AirReactionChipsView.gap
        }
    }

    override func traitCollectionDidChange(_ previousTraitCollection: UITraitCollection?) {
        super.traitCollectionDidChange(previousTraitCollection)
        // The tabular font is made again for a new text size: it does not follow the size by itself.
        let category = UIApplication.shared.preferredContentSizeCategory
        if category != fontCategory {
            fontCategory = category
            labels.forEach { $0.font = AirReactionChipsView.font }
            invalidateIntrinsicContentSize()
            setNeedsLayout()
        }
        applyColors()
    }

    private static var font: UIFont {
        return KQFont.tabular(KQFont.font(.caption))
    }

    private func plateSize(for label: UILabel) -> CGSize {
        let text = label.intrinsicContentSize
        return CGSize(width: ceil(text.width) + 2 * AirReactionChipsView.padding,
                      height: max(AirReactionChipsView.chipHeight, ceil(text.height) + 2 * AirReactionChipsView.verticalPadding))
    }

    private func applyColors() {
        for (plate, chip) in zip(plates, chips) {
            plate.backgroundColor = chip.own ? KQColor.selected : KQColor.raised
            plate.layer.borderColor = airCGColor(chip.own ? KQColor.lineStrong : KQColor.line)
        }
        labels.forEach { $0.textColor = KQColor.ink }
    }

    @objc private func tapped() {
        onTap?()
    }
}
