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
    
    override func setupWithTheme(_ theme: Theme, model: BaseCellModel) {
        super.setupWithTheme(theme, model: model)

        guard let textModel = model as? ChatBaseTextCellModel else {
            assert(false, "Wrong model \(model) passed to cell \(self)")
            return
        }

        canBeCopied = true
        canBeReacted = true
        bubbleView.onLocationTap = nil
        bubbleView.onReplyQuoteTap = nil
        bubbleView.setLocationMapImage(nil)
        bubbleView.bindReplyQuote(textModel.replyMeta, theme: theme)
        bubbleView.onReplyQuoteTap = textModel.onReplyQuoteTap
        // KHANDAQ (#192): reaction chips + tap-to-toggle (opens the quick bar via the delegate)
        bubbleView.bindReactions(textModel.reactionsDisplay)
        bubbleView.onReactionsTap = { [weak self] in
            guard let cell = self else { return }
            cell.delegate?.chatMovableDateCellReactPressed(cell)
        }

        if textModel.hasLocation,
           let latitude = textModel.locationLatitude,
           let longitude = textModel.locationLongitude {
            bubbleView.text = String(format: "%.5f, %.5f", latitude, longitude)
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

    fileprivate func loadLocationMapSnapshot(latitude: Double, longitude: Double) {
        let cacheKey = NSString(string: String(format: "%.5f,%.5f", latitude, longitude))

        if let cachedImage = ChatBaseTextCell.mapSnapshotCache.object(forKey: cacheKey) {
            bubbleView.setLocationMapImage(cachedImage)
            return
        }

        let options = MKMapSnapshotter.Options()
        options.region = MKCoordinateRegionMakeWithDistance(
            CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
            800,
            800)
        options.size = CGSize(width: 260, height: 120)
        options.scale = UIScreen.main.scale

        MKMapSnapshotter(options: options).start { [weak self] snapshot, _ in
            guard let self = self, let image = snapshot?.image else {
                return
            }

            ChatBaseTextCell.mapSnapshotCache.setObject(image, forKey: cacheKey)

            DispatchQueue.main.async {
                self.bubbleView.setLocationMapImage(image)
            }
        }
    }

    fileprivate func openLocationInMaps(latitude: Double, longitude: Double) {
        let coordinate = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        let placemark = MKPlacemark(coordinate: coordinate)
        let mapItem = MKMapItem(placemark: placemark)
        mapItem.name = "Location"
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
