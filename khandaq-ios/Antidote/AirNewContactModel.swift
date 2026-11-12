// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

// What "New contact" knows about an ID before anything is sent (design/redesign/02-plan.md, E11): the
// three parts of the ruler, the verdict in words and whether "Send request" may go. A Tox ID is the
// 64-character public key, 8 characters of antispam (nospam) and a 4-character check over both.

enum AirToxId {
    static let length = Int(kOCTToxAddressLength)
    static let keyLength = Int(kOCTToxPublicKeyLength)
    static let antispamLength = 8
    static let checkLength = 4
    /// The command line reads a hex run this long as the start of an ID rather than a name.
    static let commandMinimum = 12

    private static let prefixes = ["TOX://", "KHANDAQ://", "TOX:", "KHANDAQ:", "TOX-ID:", "TOXID:"]

    /// The hex of an ID in any usual shape: spaces, line breaks, invisible characters, a "tox:" prefix,
    /// lower case, or a whole sentence around it. Upper case, at most 76 characters.
    static func clean(_ raw: String) -> String {
        let scalars = raw.uppercased().unicodeScalars.filter { scalar in
            !CharacterSet.whitespacesAndNewlines.contains(scalar)
                && !CharacterSet(charactersIn: "\u{200B}\u{200C}\u{200D}\u{2060}\u{FEFF}\u{00AD}").contains(scalar)
        }
        var text = String(String.UnicodeScalarView(scalars))
        for prefix in prefixes where text.hasPrefix(prefix) {
            text = String(text.dropFirst(prefix.count))
            break
        }
        // A whole ID somewhere in a sentence ("my ID: ..."): take that run, not the hex letters of the words.
        if let run = longestHexRun(text), run.count >= length {
            return String(run.prefix(length))
        }
        return String(text.filter { $0.isHexDigit }.prefix(length))
    }

    /// Groups of four, the way the ID is read aloud and compared.
    static func grouped(_ hex: String) -> String {
        var groups: [String] = []
        var current = ""
        for character in hex {
            current.append(character)
            if current.count == 4 {
                groups.append(current)
                current = ""
            }
        }
        if !current.isEmpty {
            groups.append(current)
        }
        return groups.joined(separator: " ")
    }

    /// toxcore's check: the two bytes are the XOR of the even and of the odd bytes of key and antispam.
    static func checkMatches(_ hex: String) -> Bool {
        guard hex.count == length, let bytes = bytes(hex) else {
            return false
        }
        var check: [UInt8] = [0, 0]
        for index in 0..<(bytes.count - 2) {
            check[index % 2] ^= bytes[index]
        }
        return check[0] == bytes[bytes.count - 2] && check[1] == bytes[bytes.count - 1]
    }

    /// What the command line holds when it reads as an ID: a run of 12 or more hex characters and
    /// nothing else (spaces, line breaks and a "tox:" prefix aside). Nil for names and sentences.
    static func commandCandidate(_ raw: String) -> String? {
        var text = raw.uppercased().components(separatedBy: .whitespacesAndNewlines).joined()
        for prefix in prefixes where text.hasPrefix(prefix) {
            text = String(text.dropFirst(prefix.count))
            break
        }
        guard text.count >= commandMinimum, text.count <= length, text.allSatisfy({ $0.isHexDigit }) else {
            return nil
        }
        return text
    }

    private static func longestHexRun(_ text: String) -> String? {
        var best = ""
        var current = ""
        for character in text {
            if character.isHexDigit {
                current.append(character)
            }
            else {
                if current.count > best.count {
                    best = current
                }
                current = ""
            }
        }
        if current.count > best.count {
            best = current
        }
        return best.isEmpty ? nil : best
    }

    private static func bytes(_ hex: String) -> [UInt8]? {
        var result: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            guard let next = hex.index(index, offsetBy: 2, limitedBy: hex.endIndex), let byte = UInt8(hex[index..<next], radix: 16) else {
                return nil
            }
            result.append(byte)
            index = next
        }
        return result
    }
}

/// The verdict under the ID field, in the order it is decided.
enum AirIdVerdict: Equatable {
    case empty
    /// Characters still missing.
    case partial(left: Int)
    case badCheck
    case own
    /// Already in contacts (also one whose request is still on its way): "Open chat" instead of sending.
    case contact(publicKey: String)
    /// This person already sent you a request: it waits in Requests.
    case theyAsked
    /// The key is on this device's block list: sending the request lifts the block.
    case blocked(address: String)
    case ok(address: String)

    /// The address "Send request" sends to, when it may go.
    var address: String? {
        switch self {
            case .ok(let address), .blocked(let address):
                return address
            default:
                return nil
        }
    }

    /// The ruler's check part turns green or red only once the ID is whole.
    var checkState: AirRulerState {
        switch self {
            case .empty, .partial:
                return .filling
            case .badCheck:
                return .bad
            default:
                return .good
        }
    }

    static func of(hex: String,
                   ownAddress: String,
                   isContact: (String) -> Bool,
                   hasRequest: (String) -> Bool,
                   isBlocked: (String) -> Bool) -> AirIdVerdict {
        guard !hex.isEmpty else {
            return .empty
        }
        guard hex.count >= AirToxId.length else {
            return .partial(left: AirToxId.length - hex.count)
        }
        guard AirToxId.checkMatches(hex) else {
            return .badCheck
        }
        let key = String(hex.prefix(AirToxId.keyLength))
        if key == AirToxId.clean(ownAddress).prefix(AirToxId.keyLength) {
            return .own
        }
        if isContact(key) {
            return .contact(publicKey: key)
        }
        if hasRequest(key) {
            return .theyAsked
        }
        if isBlocked(key) {
            return .blocked(address: hex)
        }
        return .ok(address: hex)
    }
}

enum AirRulerState: Equatable {
    case filling
    case good
    case bad
}

/// One part of the ruler: how much of it is typed.
struct AirRulerPart: Equatable {
    let filled: Int
    let size: Int

    static func parts(count: Int) -> [AirRulerPart] {
        let bounds = [(0, AirToxId.keyLength),
                      (AirToxId.keyLength, AirToxId.keyLength + AirToxId.antispamLength),
                      (AirToxId.keyLength + AirToxId.antispamLength, AirToxId.length)]
        return bounds.map { from, to in
            AirRulerPart(filled: max(0, min(count, to) - from), size: to - from)
        }
    }
}
