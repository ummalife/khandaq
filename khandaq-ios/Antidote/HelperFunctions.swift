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

private let toxAddressInputPrefixes = [
    "TOX:",
    "KHANDAQ:",
    "TOX-ID:",
    "TOXID:",
]

private func stripInvisibleFormattingCharacters(_ string: String) -> String {
    String(string.unicodeScalars.filter { scalar in
        switch scalar.value {
        case 0x200B...0x200D, 0xFEFF, 0x2060, 0x00AD, 0x2028, 0x2029:
            return false
        default:
            return !CharacterSet.controlCharacters.contains(scalar)
        }
    })
}

func sanitizeAddressInput(_ string: String) -> String {
    var result = stripInvisibleFormattingCharacters(string.uppercased())
    result = result.trimmingCharacters(in: .whitespacesAndNewlines)
    result = result.components(separatedBy: .whitespacesAndNewlines).joined()

    for prefix in toxAddressInputPrefixes {
        if result.hasPrefix(prefix) {
            result = String(result[result.index(result.startIndex, offsetBy: prefix.count) ..< result.endIndex])
            break
        }
    }

    return result.filter { $0.isHexDigit }.uppercased()
}

private func hexBytes(from hex: String, byteCount: Int) -> [UInt8]? {
    guard hex.count >= byteCount * 2 else {
        return nil
    }

    var bytes = [UInt8]()
    var index = hex.startIndex

    for _ in 0 ..< byteCount {
        guard let nextIndex = hex.index(index, offsetBy: 2, limitedBy: hex.endIndex) else {
            return nil
        }
        guard let byte = UInt8(hex[index ..< nextIndex], radix: 16) else {
            return nil
        }
        bytes.append(byte)
        index = nextIndex
    }

    return bytes
}

private func canonicalizeAddressHex(_ hex: String) -> String? {
    let addressByteCount = Int(kOCTToxAddressLength / 2)

    guard let bytes = hexBytes(from: hex, byteCount: addressByteCount) else {
        return nil
    }

    var address = bytes
    let checksum = toxDataChecksum(Array(address[0 ..< addressByteCount - 2]))
    address[addressByteCount - 2] = UInt8(checksum & 0xFF)
    address[addressByteCount - 1] = UInt8((checksum >> 8) & 0xFF)

    return address.map { String(format: "%02X", $0) }.joined()
}

func normalizeAddressString(_ string: String) -> String? {
    let hex = sanitizeAddressInput(string)

    if hex.count == Int(kOCTToxAddressLength) && isHexString(hex, length: Int(kOCTToxAddressLength)) {
        // Recompute checksum from pubkey + nospam so minor paste corruption in the last 4 hex chars still works.
        return canonicalizeAddressHex(hex)
    }

    if hex.count == Int(kOCTToxPublicKeyLength) && isHexString(hex, length: Int(kOCTToxPublicKeyLength)) {
        let addressByteCount = Int(kOCTToxAddressLength / 2)
        let publicKeyByteCount = Int(kOCTToxPublicKeyLength / 2)

        guard let publicKeyBytes = hexBytes(from: hex, byteCount: publicKeyByteCount) else {
            return nil
        }

        var address = [UInt8](repeating: 0, count: addressByteCount)
        for (index, byte) in publicKeyBytes.enumerated() {
            address[index] = byte
        }

        return canonicalizeAddressHex(address.map { String(format: "%02X", $0) }.joined())
    }

    return nil
}

func isAddressString(_ string: String) -> Bool {
    let hex = sanitizeAddressInput(string)
    let addressLength = Int(kOCTToxAddressLength)
    let publicKeyLength = Int(kOCTToxPublicKeyLength)

    guard hex.count == addressLength || hex.count == publicKeyLength else {
        return false
    }

    return normalizeAddressString(string) != nil
}
