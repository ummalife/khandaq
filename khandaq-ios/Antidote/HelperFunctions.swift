// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import Foundation

enum KhandaqBranding {
    static let defaultStatusMessage = "Khandaq is Tox"
}

private func isHexString(_ string: String, length: Int) -> Bool {
    let nsstring = string as NSString

    if nsstring.length != length {
        return false
    }

    let validChars = CharacterSet(charactersIn: "1234567890abcdefABCDEF")
    let components = nsstring.components(separatedBy: validChars)
    let leftChars = components.joined(separator: "")

    return leftChars.isEmpty
}

private func toxDataChecksum(_ data: [UInt8]) -> UInt16 {
    var checksum: [UInt8] = [0, 0]

    for (index, byte) in data.enumerated() {
        checksum[index % 2] ^= byte
    }

    return UInt16(checksum[0]) | (UInt16(checksum[1]) << 8)
}

func sanitizeAddressInput(_ string: String) -> String {
    var result = string.uppercased().trimmingCharacters(in: .whitespacesAndNewlines)

    if result.hasPrefix("TOX:") {
        result = String(result[result.index(result.startIndex, offsetBy: 4) ..< result.endIndex])
    }

    return result.filter { $0.isHexDigit }.uppercased()
}

func normalizeAddressString(_ string: String) -> String? {
    let hex = sanitizeAddressInput(string)

    if hex.count == Int(kOCTToxAddressLength) && isHexString(hex, length: Int(kOCTToxAddressLength)) {
        return hex
    }

    if hex.count == Int(kOCTToxPublicKeyLength) && isHexString(hex, length: Int(kOCTToxPublicKeyLength)) {
        var address = [UInt8](repeating: 0, count: Int(kOCTToxAddressLength / 2))

        var index = hex.startIndex
        for byteIndex in 0 ..< address.count - 2 {
            let nextIndex = hex.index(index, offsetBy: 2)
            address[byteIndex] = UInt8(hex[index ..< nextIndex], radix: 16) ?? 0
            index = nextIndex
        }

        let checksum = toxDataChecksum(Array(address[0 ..< address.count - 2]))
        address[address.count - 2] = UInt8(checksum & 0xFF)
        address[address.count - 1] = UInt8((checksum >> 8) & 0xFF)

        return address.map { String(format: "%02X", $0) }.joined()
    }

    return nil
}

func isAddressString(_ string: String) -> Bool {
    return normalizeAddressString(string) != nil
}
