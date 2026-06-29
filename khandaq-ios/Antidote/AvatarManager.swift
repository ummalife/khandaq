// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

class AvatarManager {
    enum AvatarType: String {
        case Normal
        case Call
    }

    fileprivate let theme: Theme
    fileprivate let cache: NSCache<AnyObject, AnyObject>

    init(theme: Theme) {
        self.theme = theme
        self.cache = NSCache()
    }

    // KHANDAQ design (Figma): vibrant solid-disc avatar palette (white initials sit on top).
    private static let avatarColors: [UIColor] = [
        UIColor(red: 0x02 / 255.0, green: 0x9B / 255.0, blue: 0x7D / 255.0, alpha: 1.0), // brand teal
        UIColor(red: 0x2E / 255.0, green: 0x9B / 255.0, blue: 0xE6 / 255.0, alpha: 1.0), // blue
        UIColor(red: 0xC7 / 255.0, green: 0x24 / 255.0, blue: 0xB1 / 255.0, alpha: 1.0), // magenta
        UIColor(red: 0xE0 / 255.0, green: 0xA4 / 255.0, blue: 0x0C / 255.0, alpha: 1.0), // amber
        UIColor(red: 0x6A / 255.0, green: 0x5A / 255.0, blue: 0xE0 / 255.0, alpha: 1.0), // indigo
        UIColor(red: 0xE0 / 255.0, green: 0x52 / 255.0, blue: 0x4A / 255.0, alpha: 1.0), // red
        UIColor(red: 0x16 / 255.0, green: 0xA0 / 255.0, blue: 0x85 / 255.0, alpha: 1.0), // green
        UIColor(red: 0xEC / 255.0, green: 0x40 / 255.0, blue: 0x7B / 255.0, alpha: 1.0), // pink
        UIColor(red: 0xF2 / 255.0, green: 0x7A / 255.0, blue: 0x1A / 255.0, alpha: 1.0), // orange
        UIColor(red: 0x3D / 255.0, green: 0x6F / 255.0, blue: 0xD6 / 255.0, alpha: 1.0), // royal
    ]

    // Stable across launches (Swift's String.hashValue is per-run randomized — would reshuffle colours).
    static func avatarColor(forString string: String) -> UIColor {
        var hash = 5381
        for byte in string.utf8 {
            hash = (hash &* 33) &+ Int(byte)
        }
        let index = ((hash % avatarColors.count) + avatarColors.count) % avatarColors.count
        return avatarColors[index]
    }

    /**
        Returns round avatar created from string with a given diameter. Searches for an avatar in cache first,
        if not found creates it.

        - Parameters:
          - string: String to create avatar from. In case of empty string avatar will be set to "?".
          - diameter: Diameter of circle with avatar.

        - Returns: Avatar from given string with given size.
     */
    func avatarFromString(_ string: String, diameter: CGFloat, type: AvatarType = .Normal) -> UIImage {
        var string = string

        if string.isEmpty {
            string = "?"
        }

        let key = keyFromString(string, diameter: diameter, type: type)

        if let avatar = cache.object(forKey: key as AnyObject) as? UIImage {
            return avatar
        }

        let avatar = createAvatarFromString(string, diameter: diameter, type: type)
        cache.setObject(avatar, forKey: key as AnyObject)

        return avatar
    }
}

private extension AvatarManager {
    func keyFromString(_ string: String, diameter: CGFloat, type: AvatarType) -> String {
        return "\(string)-\(diameter)-\(type.rawValue)"
    }

    func createAvatarFromString(_ string: String, diameter: CGFloat, type: AvatarType) -> UIImage {
        let avatarString = avatarStringFromString(string)

        let label = UILabel()
        label.layer.masksToBounds = true
        label.textAlignment = .center
        label.text = avatarString

        switch type {
            case .Normal:
                // KHANDAQ design (Figma): solid colour disc keyed deterministically off the name, with
                // white initials and no ring.
                label.layer.borderWidth = 0.0
                label.backgroundColor = AvatarManager.avatarColor(forString: string)
                label.textColor = .white
            case .Call:
                label.layer.borderWidth = 1.0
                label.backgroundColor = .clear
                label.layer.borderColor = theme.colorForType(.CallButtonIconColor).cgColor
                label.textColor = theme.colorForType(.CallButtonIconColor)
        }

        var size: CGSize
        var fontSize = diameter

        repeat {
            fontSize -= 1

            let font = UIFont.khandaqFontWithSize(fontSize, weight: .light)
            size = avatarString.stringSizeWithFont(font)
        }
        while (max(size.width, size.height) > diameter)

        let frame = CGRect(x: 0, y: 0, width: diameter, height: diameter)

        label.font = UIFont.systemFont(ofSize: fontSize * 0.62, weight: .medium)
        label.layer.cornerRadius = frame.size.width / 2
        label.frame = frame

        return imageWithLabel(label)
    }

    func avatarStringFromString(_ string: String) -> String {
        guard !string.isEmpty else {
            return ""
        }

        // Avatar can have alphanumeric symbols and ? sign.
        let badSymbols = (CharacterSet.alphanumerics.inverted as NSCharacterSet).mutableCopy() as! NSMutableCharacterSet
        badSymbols.removeCharacters(in: "?")

        let words = string.components(separatedBy: CharacterSet.whitespaces).map {
            $0.components(separatedBy: badSymbols as CharacterSet).joined(separator: "")
        }.filter {
            !$0.isEmpty
        }

        var result = words.map {
            $0.isEmpty ? "" : $0[$0.startIndex ..< $0.index($0.startIndex, offsetBy: 1)]
        }.joined(separator: "")

        let numberOfLetters = min(2, result.count)

        result = result.uppercased()
        return String(result[result.startIndex ..< result.index(result.startIndex, offsetBy: numberOfLetters)])
    }

    func imageWithLabel(_ label: UILabel) -> UIImage {
        UIGraphicsBeginImageContextWithOptions(label.bounds.size, false, 0.0)
        label.layer.render(in: UIGraphicsGetCurrentContext()!)

        let image = UIGraphicsGetImageFromCurrentImageContext()

        UIGraphicsEndImageContext()

        return image!
    }
}
