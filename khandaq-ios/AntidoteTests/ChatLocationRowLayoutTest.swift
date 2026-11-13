// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation
import XCTest
@testable import Antidote

/**
 Seen on a phone with TestFlight 1.4.33 (142993): a location from the demo bot came in as a row one
 line high with a sliver of map in it, and a whole map was drawn on the next entry, over the text of
 the one after. The table measures a row when its cell is set up, and the map snapshot comes later:
 the row was measured for the coordinates alone, and the snapshot went on whatever row the cell showed
 by the time it came. These tests pin both halves down without the map service.
 */
class ChatLocationRowLayoutTest: CellSnapshotTest {
    func testLocationRowIsMeasuredWithItsMapBeforeTheSnapshotComes() {
        let location = makeCell(locationEntry(latitude: 37.3349, longitude: -122.009))
        let text = makeCell(textEntry("37.33490, -122.00900"))

        guard let tile = mapTile(in: location) else {
            return XCTFail("a location row shows the place of its map before the snapshot comes")
        }
        XCTAssertGreaterThan(tile.bounds.height, 0)
        XCTAssertGreaterThanOrEqual(location.frame.height, text.frame.height + tile.bounds.height,
                                    "the row is measured with its map, not for the coordinates alone")
        let tileInRow = tile.convert(tile.bounds, to: location)
        XCTAssertTrue(location.bounds.contains(tileInRow),
                      "the map stays inside its own row: \(tileInRow) in \(location.bounds)")
    }

    func testSnapshotForAnEarlierRowIsNotPutOnTheNextOne() {
        let cell = AirEntryTextCell()
        cell.setupWithTheme(theme, model: locationEntry(latitude: 21.4225, longitude: 39.8262))
        let earlierKey = ChatBaseTextCell.mapSnapshotKey(latitude: 21.4225, longitude: 39.8262)

        // The table gives the cell the next row before the snapshot of the first one comes.
        let next = "That's everything for now."
        cell.setupWithTheme(theme, model: textEntry(next))
        cell.applyLocationMapSnapshot(snapshot(), forKey: earlierKey)
        layoutAsTheLogDoes(cell)

        XCTAssertNil(mapTile(in: cell), "a snapshot that comes late does not land on a text row")
        XCTAssertEqual(cell.frame.height, makeCell(textEntry(next)).frame.height, accuracy: 0.5)
    }

    func testSnapshotForThisRowFillsItsPlaceWithoutChangingTheRow() {
        let cell = makeCell(locationEntry(latitude: 41.0082, longitude: 28.9784))
        let heightBefore = cell.frame.height
        let image = snapshot()

        cell.applyLocationMapSnapshot(image, forKey: ChatBaseTextCell.mapSnapshotKey(latitude: 41.0082, longitude: 28.9784))
        layoutAsTheLogDoes(cell)

        XCTAssertTrue(mapTile(in: cell)?.image === image, "the snapshot goes on the row it was made for")
        XCTAssertEqual(cell.frame.height, heightBefore, accuracy: 0.5,
                       "the snapshot fills the place kept for it, so the row the table measured still fits")
    }
}

private extension ChatLocationRowLayoutTest {
    func textEntry(_ text: String) -> AirEntryTextCellModel {
        let model = AirEntryTextCellModel()
        model.message = text
        model.timeText = "9:41"
        return model
    }

    /// A location entry as ChatPrivateController.populateTextModel fills it.
    func locationEntry(latitude: Double, longitude: Double) -> AirEntryTextCellModel {
        let model = textEntry(String(format: "%.5f, %.5f", latitude, longitude))
        model.locationLatitude = latitude
        model.locationLongitude = longitude
        return model
    }

    func makeCell(_ model: AirEntryTextCellModel) -> AirEntryTextCell {
        let cell = AirEntryTextCell()
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)
        return cell
    }

    /// The map tile is the only image view the bubble holds directly.
    func mapTile(in cell: ChatBaseTextCell) -> UIImageView? {
        for case let imageView as UIImageView in cell.bubbleView.subviews where !imageView.isHidden {
            return imageView
        }
        return nil
    }

    func snapshot() -> UIImage {
        let size = CGSize(width: 260, height: 120)
        return UIGraphicsImageRenderer(size: size).image { context in
            UIColor.gray.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
    }
}
