// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

struct LocationMessage {
    private static let prefix = "khandaq-location:"
    private static let legacyPattern = #"my\s+Location:\s*([-\d.]+)\s*,\s*([-\d.]+)"#

    static func payload(latitude: Double, longitude: Double) -> String {
        String(format: "\(prefix)%.5f,%.5f", latitude, longitude)
    }

    static func parse(_ text: String) -> (latitude: Double, longitude: Double)? {
        if text.hasPrefix(prefix) {
            let coordinates = String(text.dropFirst(prefix.count))
            return coordinatesFromPair(coordinates)
        }

        if let regex = try? NSRegularExpression(pattern: legacyPattern, options: [.caseInsensitive]),
           let match = regex.firstMatch(in: text, options: [], range: NSRange(text.startIndex..., in: text)),
           match.numberOfRanges == 3,
           let latRange = Range(match.range(at: 1), in: text),
           let lonRange = Range(match.range(at: 2), in: text) {
            return coordinatesFromPair("\(text[latRange]),\(text[lonRange])")
        }

        return nil
    }

    private static func coordinatesFromPair(_ pair: String) -> (latitude: Double, longitude: Double)? {
        let parts = pair.split(separator: ",", maxSplits: 1).map(String.init)
        guard parts.count == 2,
              let latitude = Double(parts[0].trimmingCharacters(in: .whitespaces)),
              let longitude = Double(parts[1].trimmingCharacters(in: .whitespaces)) else {
            return nil
        }
        return (latitude, longitude)
    }
}

class ChatBaseTextCellModel: ChatMovableDateCellModel {
    var message: String = ""
    var locationLatitude: Double?
    var locationLongitude: Double?

    var hasLocation: Bool {
        locationLatitude != nil && locationLongitude != nil
    }
}
