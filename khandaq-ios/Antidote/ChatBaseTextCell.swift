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
        bubbleView.onLocationTap = nil
        bubbleView.setLocationMapImage(nil)

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
            bubbleView.text = textModel.message
        }

        bubbleView.textColor = theme.colorForType(.NormalText)
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
