// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UIKit
import MapKit

class ChatBaseTextCell: ChatMovableDateCell {
    private static let mapSnapshotCache = NSCache<NSString, UIImage>()
    struct Constants {
        static let BubbleVerticalOffset = 1.0
        static let BubbleHorizontalOffset = 10.0
    }

    var bubbleNormalBackground: UIColor?
    var bubbleView: BubbleView!
    /// KHANDAQ (location row): the place this cell shows the map of, nil on any other row.
    private var shownLocationKey: NSString?

    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let textModel = model as? ChatBaseTextCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        canBeCopied = true
        canBeReacted = true
        // KHANDAQ (#208): only own outgoing text messages expose "Изменить"
        canBeEdited = textModel.canEdit
        bubbleView.onLocationTap = nil
        bubbleView.onReplyQuoteTap = nil
        bubbleView.setLocationMapImage(nil)
        shownLocationKey = nil
        bubbleView.bindReplyQuote(textModel.replyMeta, theme: theme)
        bubbleView.onReplyQuoteTap = textModel.onReplyQuoteTap
        // KHANDAQ (#192): reaction chips + tap-to-toggle (opens the quick bar via the delegate)
        bubbleView.bindReactions(textModel.reactionChips)
        bubbleView.onReactionsTap = { [weak self] in
            guard let cell = self else { return }
            cell.delegate?.chatMovableDateCellReactPressed(cell)
        }

        if textModel.hasLocation,
           let latitude = textModel.locationLatitude,
           let longitude = textModel.locationLongitude {
            bubbleView.showLocationCoordinates(LocationMessage.coordinatesText(latitude: latitude, longitude: longitude))
            bubbleView.onLocationTap = { [weak self] in
                self?.openLocationInMaps(latitude: latitude, longitude: longitude)
            }
            loadLocationMapSnapshot(latitude: latitude, longitude: longitude)
        }
        else {
            if let highlight = textModel.searchHighlight, !highlight.isEmpty {
                bubbleView.attributedText = MessageSearchHighlighter.highlightedText(
                        textModel.message,
                        query: highlight,
                        textColor: theme.colorForType(.NormalText))
            }
            else if !textModel.mentionHandles.isEmpty {
                bubbleView.attributedText = GroupMentionHelper.attributedMessage(
                    textModel.message,
                    mentionHandles: textModel.mentionHandles,
                    textColor: theme.colorForType(.NormalText),
                    mentionColor: theme.colorForType(.LinkText),
                    font: bubbleView.font ?? UIFont.preferredFont(forTextStyle: .body))
            }
            else {
                bubbleView.text = textModel.message
            }
        }

        bubbleView.textColor = theme.colorForType(.NormalText)

        // KHANDAQ (#iOS edited-marker): «изменено» is now rendered as its OWN metadata sub-row inside
        // the bubble (BubbleView.bindEdited) — no longer appended inline into the message body, so it
        // reads as a system note instead of text the sender typed onto the end of the message.
        bubbleView.bindEdited(textModel.edited && !textModel.hasLocation)

        // KHANDAQ (audit#2 finding 1): relayed history whose author nobody signed for. Shown on the
        // location bubble too — a forwarded "here is where I am" is exactly the claim worth doubting.
        bubbleView.bindUnverifiedSender(textModel.senderUnverified)
    }

    /// KHANDAQ (Figma): tint the group sender header line ("Name · Role") with the per-peer color.
    /// Must run AFTER any whole-view font/textColor assignment (those wipe per-range attributes),
    /// so subclasses call it at the END of their setupWithTheme.
    func applySenderHeaderStyling(_ theme: Theme, model: BaseCellModel) {
        guard let textModel = model as? ChatBaseTextCellModel,
              textModel.senderHeaderLength > 0,
              let senderColor = textModel.senderColor,
              !textModel.hasLocation else {
            return
        }

        let baseFont = bubbleView.font ?? UIFont.preferredFont(forTextStyle: .body)
        let current: NSMutableAttributedString
        if let attributed = bubbleView.attributedText, attributed.length > 0 {
            current = NSMutableAttributedString(attributedString: attributed)
        }
        else {
            current = NSMutableAttributedString(string: textModel.message, attributes: [
                .foregroundColor: theme.colorForType(.NormalText),
                .font: baseFont,
            ])
        }

        let headerLength = min(textModel.senderHeaderLength, current.length)
        guard headerLength > 0 else {
            return
        }

        let headerFont = UIFont.systemFont(ofSize: max(baseFont.pointSize - 2.0, 12.0), weight: .semibold)
        current.addAttributes([
            .foregroundColor: senderColor,
            .font: headerFont,
        ], range: NSRange(location: 0, length: headerLength))
        bubbleView.attributedText = current
    }

    /// The key a place's map snapshot is cached and matched under.
    static func mapSnapshotKey(latitude: Double, longitude: Double) -> NSString {
        return NSString(string: String(format: "%.5f,%.5f", latitude, longitude))
    }

    /// KHANDAQ (location row): the snapshot comes late, and by then the table may have given this cell
    /// another row, so it goes on only while the cell still shows the place it was made for. Without
    /// the check one row's map was drawn on the next row of the log (TestFlight 1.4.33 (142993)).
    func applyLocationMapSnapshot(_ image: UIImage, forKey key: NSString) {
        guard shownLocationKey == key else {
            return
        }
        bubbleView.setLocationMapImage(image)
    }

    fileprivate func loadLocationMapSnapshot(latitude: Double, longitude: Double) {
        let cacheKey = ChatBaseTextCell.mapSnapshotKey(latitude: latitude, longitude: longitude)
        shownLocationKey = cacheKey

        if let cachedImage = ChatBaseTextCell.cachedMapSnapshot(latitude: latitude, longitude: longitude) {
            bubbleView.setLocationMapImage(cachedImage)
            return
        }

        // The row is measured now, before the snapshot exists: the tile takes the map's place from the
        // start and the snapshot only fills it in.
        bubbleView.showLocationMapPlaceholder()

        ChatBaseTextCell.makeMapSnapshot(latitude: latitude, longitude: longitude) { [weak self] image in
            self?.applyLocationMapSnapshot(image, forKey: cacheKey)
        }
    }

    /// The size of a place's map snapshot, in points.
    static let mapSnapshotSize = CGSize(width: 260, height: 120)

    /// KHANDAQ (inbox #28, 28.11): the map of a place made before, by a row of a conversation or by the preview of a
    /// chat, which show one place by one snapshot.
    static func cachedMapSnapshot(latitude: Double, longitude: Double) -> UIImage? {
        return mapSnapshotCache.object(forKey: mapSnapshotKey(latitude: latitude, longitude: longitude))
    }

    /// Makes the map of a place and keeps it for the next row that shows the place. `completion` gets the map on the
    /// main queue, and never runs when no map could be made (no network).
    static func makeMapSnapshot(latitude: Double, longitude: Double, completion: @escaping (UIImage) -> Void) {
        let cacheKey = mapSnapshotKey(latitude: latitude, longitude: longitude)
        let options = MKMapSnapshotter.Options()
        options.region = MKCoordinateRegionMakeWithDistance(
            CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
            800,
            800)
        options.size = mapSnapshotSize
        options.scale = UIScreen.main.scale

        MKMapSnapshotter(options: options).start { snapshot, _ in
            guard let snapshot = snapshot else {
                return
            }
            let place = snapshot.point(for: CLLocationCoordinate2D(latitude: latitude, longitude: longitude))
            let image = drawPin(on: snapshot.image, at: place)

            mapSnapshotCache.setObject(image, forKey: cacheKey)

            DispatchQueue.main.async {
                completion(image)
            }
        }
    }

    /// KHANDAQ (owner, 28.11): the pin of the place on its map, drawn into the snapshot, so the conversation and the
    /// preview of a chat show where on the map it is (the map alone left that to guess): a red head with a white
    /// dot and a white edge, its point on the place, a soft shadow under the point. Android draws its marker into
    /// its map the same way (LocationMapSnapshot.drawMarker).
    static func drawPin(on map: UIImage, at place: CGPoint) -> UIImage {
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = map.scale
        return UIGraphicsImageRenderer(size: map.size, format: format).image { context in
            map.draw(at: .zero)
            let cg = context.cgContext

            let radius: CGFloat = 10
            let head = CGPoint(x: place.x, y: place.y - 2.2 * radius)
            // The two sides run from the point to where they touch the head: there the radius is square to them.
            let spread = acos(radius / (place.y - head.y))
            let pin = UIBezierPath()
            pin.move(to: place)
            pin.addArc(withCenter: head, radius: radius,
                       startAngle: .pi / 2 + spread, endAngle: 2.5 * .pi - spread, clockwise: true)
            pin.close()

            cg.setFillColor(UIColor.black.withAlphaComponent(0.22).cgColor)
            cg.fillEllipse(in: CGRect(x: place.x - 5, y: place.y - 2, width: 10, height: 4))

            cg.saveGState()
            cg.setShadow(offset: CGSize(width: 0, height: 1), blur: 3, color: UIColor.black.withAlphaComponent(0.3).cgColor)
            KQColor.dangerFill.setFill()
            pin.fill()
            cg.restoreGState()

            KQColor.fixedWhite.setStroke()
            pin.lineWidth = 1.5
            pin.stroke()

            KQColor.fixedWhite.setFill()
            UIBezierPath(arcCenter: head, radius: 3.5, startAngle: 0, endAngle: 2 * .pi, clockwise: true).fill()
        }
    }

    fileprivate func openLocationInMaps(latitude: Double, longitude: Double) {
        let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        let placemark = MKPlacemark(coordinate: coordinate)
        let mapItem = MKMapItem(placemark: placemark)
        mapItem.name = LocationMessage.title
        mapItem.openInMaps()
    }

    override func createViews() {
        super.createViews()

        bubbleView = BubbleView()
        contentView.addSubview(bubbleView)
    }

    override func setEditing(_ editing: Bool, animated: Bool) {
        super.setEditing(editing, animated: animated)

        bubbleView.isUserInteractionEnabled = !editing
    }

    override func setHighlighted(_ highlighted: Bool, animated: Bool) {
        super.setHighlighted(highlighted, animated: animated)
        bubbleView.backgroundColor = bubbleNormalBackground
    }

    override func setSelected(_ selected: Bool, animated: Bool) {
        super.setSelected(selected, animated: animated)
        
        if isEditing {
            bubbleView.backgroundColor = bubbleNormalBackground
            return
        }

        if selected {
            bubbleView.backgroundColor = bubbleNormalBackground?.darkerColor()
        }
        else {
            bubbleView.backgroundColor = bubbleNormalBackground
        }
    }
}

// Accessibility
extension ChatBaseTextCell {
    override var accessibilityValue: String? {
        get {
            var value = bubbleView.text!
            if let sValue = super.accessibilityValue {
                value += ", " + sValue
            }

            return value
        }
        set {}
    }
}

// ChatEditable
extension ChatBaseTextCell {
    override func shouldShowMenu() -> Bool {
        return true
    }

    override func menuTargetRect() -> CGRect {
        return bubbleView.frame
    }

    override func willShowMenu() {
        super.willShowMenu()

        bubbleView.selectable = false
    }

    override func willHideMenu() {
        super.willHideMenu()

        bubbleView.selectable = true
    }
}
