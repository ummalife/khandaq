// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/// "New contact" decides before sending: the ruler, the verdict and whether the request may go.
class AirNewContactModelTests: XCTestCase {
    /// A whole ID with a correct check (the one AirListModelTests uses).
    private let address = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5"
    private let own = "7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71000000007F3A"

    func testTheFieldTakesAnIdInAnyUsualShape() {
        XCTAssertEqual(AirToxId.clean(address.lowercased()), address)
        XCTAssertEqual(AirToxId.clean("tox:" + address), address)
        XCTAssertEqual(AirToxId.clean("TOX://" + String(address.prefix(40)) + "\n  " + String(address.dropFirst(40))), address)
        XCTAssertEqual(AirToxId.clean("\u{200B}" + AirToxId.grouped(address) + "\u{FEFF}"), address)
        // A sentence around a whole ID: the ID, not the hex letters of the words.
        XCTAssertEqual(AirToxId.clean("My ID is " + address + ", add me"), address)
        // Anything longer is cut at 76 characters.
        XCTAssertEqual(AirToxId.clean(address + "ABCD").count, AirToxId.length)
        XCTAssertEqual(AirToxId.clean("12 34 ab"), "1234AB")
        XCTAssertEqual(AirToxId.grouped("1234567890"), "1234 5678 90")
    }

    func testTheCheckIsReallyChecked() {
        XCTAssertTrue(AirToxId.checkMatches(address))
        let lastWrong = String(address.dropLast()) + (address.hasSuffix("5") ? "6" : "5")
        XCTAssertFalse(AirToxId.checkMatches(lastWrong))
        // A typo in the key is caught too: the old field recomputed the check and hid it.
        var characters = Array(address)
        characters[10] = characters[10] == "A" ? "B" : "A"
        XCTAssertFalse(AirToxId.checkMatches(String(characters)))
        XCTAssertFalse(AirToxId.checkMatches(String(address.prefix(64))))
    }

    func testTheVerdictsComeInTheirOrder() {
        func verdict(_ hex: String, contacts: Set<String> = [], requests: Set<String> = [], blocked: Set<String> = []) -> AirIdVerdict {
            return AirIdVerdict.of(hex: hex, ownAddress: own, isContact: { contacts.contains($0) },
                                   hasRequest: { requests.contains($0) }, isBlocked: { blocked.contains($0) })
        }
        let key = String(address.prefix(64))
        XCTAssertEqual(verdict(""), .empty)
        XCTAssertEqual(verdict(String(address.prefix(64))), .partial(left: 12))
        XCTAssertEqual(verdict(String(address.dropLast()) + "0"), .badCheck)
        XCTAssertEqual(verdict(address), .ok(address: address))
        XCTAssertEqual(verdict(address).address, address)
        XCTAssertEqual(verdict(address, contacts: [key]), .contact(publicKey: key))
        XCTAssertNil(verdict(address, contacts: [key]).address)
        XCTAssertEqual(verdict(address, requests: [key]), .theyAsked)
        XCTAssertEqual(verdict(address, blocked: [key]), .blocked(address: address))
        XCTAssertEqual(verdict(address, blocked: [key]).address, address, "a blocked key may be asked again: that lifts the block")
        // Your own ID with another antispam is still yours.
        let ownOtherAntispam = String(own.prefix(64)) + "12345678"
        XCTAssertEqual(verdict(ownOtherAntispam + checkFor(ownOtherAntispam)), .own)
    }

    func testTheRulerCountsEachPart() {
        XCTAssertEqual(AirRulerPart.parts(count: 0).map { $0.filled }, [0, 0, 0])
        XCTAssertEqual(AirRulerPart.parts(count: 30).map { $0.filled }, [30, 0, 0])
        XCTAssertEqual(AirRulerPart.parts(count: 70).map { $0.filled }, [64, 6, 0])
        XCTAssertEqual(AirRulerPart.parts(count: 76).map { $0.filled }, [64, 8, 4])
        XCTAssertEqual(AirRulerPart.parts(count: 76).map { $0.size }, [64, 8, 4])
        XCTAssertEqual(AirIdVerdict.partial(left: 3).checkState, .filling)
        XCTAssertEqual(AirIdVerdict.badCheck.checkState, .bad)
        XCTAssertEqual(AirIdVerdict.own.checkState, .good)
    }

    /// Stage 5: on the narrow phones (375 and 320 points, a 16 point margin each side) every name of the
    /// ruler is whole at its size in every language: the proportions 64 : 16 : 12 give way to a name, as
    /// the fr columns of the web do (the Russian name of the check was cut in its 43 points).
    func testEveryNameOfTheRulerIsWholeOnTheNarrowPhones() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let savedLanguage = UserDefaults.standard.object(forKey: AppLanguage.storageKey)
        let savedAppleLanguages = UserDefaults.standard.object(forKey: "AppleLanguages")
        defer {
            UserDefaults.standard.set(savedLanguage, forKey: AppLanguage.storageKey)
            UserDefaults.standard.set(savedAppleLanguages, forKey: "AppleLanguages")
            AppLanguage.applyAtLaunch()
        }
        for language in ["en", "ru", "ar", "zh"] {
            AppLanguage.set(language)
            for width: CGFloat in [343, 288] {
                let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 200))
                window.traitOverrides.preferredContentSizeCategory = .large
                let ruler = AirIdRulerView()
                window.addSubview(ruler)
                window.isHidden = false
                ruler.update(count: AirToxId.length, check: .bad, animated: false)
                ruler.frame = CGRect(x: 0, y: 0, width: width, height: 60)
                ruler.layoutIfNeeded()
                let names = labels(in: ruler).filter { !($0.text ?? "").isEmpty }
                XCTAssertEqual(names.count, 6, "three names and three counts")
                for label in names {
                    XCTAssertLessThanOrEqual(label.intrinsicContentSize.width, label.bounds.width + 0.5,
                                             "\"\(label.text ?? "")\" is cut at \(width) points in \(language)")
                }
                window.isHidden = true
            }
        }
    }

    private func labels(in root: UIView) -> [UILabel] {
        var found: [UILabel] = []
        if let label = root as? UILabel {
            found.append(label)
        }
        for subview in root.subviews {
            found += labels(in: subview)
        }
        return found
    }

    func testTheCommandLineReadsTheStartOfAnId() {
        XCTAssertEqual(AirToxId.commandCandidate(String(address.prefix(12))), String(address.prefix(12)))
        XCTAssertNil(AirToxId.commandCandidate(String(address.prefix(11))))
        XCTAssertNil(AirToxId.commandCandidate("Amina Safina"))
        XCTAssertEqual(AirToxId.commandCandidate("tox:" + address.lowercased()), address)
        XCTAssertNil(AirToxId.commandCandidate(address + "0"))
    }

    /// A form of sections scrolls to a view below the fold, as VoiceOver moving to it and the UI tests
    /// ask (stage 5: at the largest text sizes the message field of New contact could not be reached).
    /// The column keeps to the screen's margins and nothing scrolls sideways.
    func testTheFormScrollsToAViewBelowTheFold() {
        let screen = AirScrollController()
        screen.loadViewIfNeeded()
        let low = UIView()
        for index in 0..<12 {
            let block = index == 11 ? low : UIView()
            block.heightAnchor.constraint(equalToConstant: 120).isActive = true
            screen.addSection("Section \(index)", views: [block])
        }
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 375, height: 667))
        window.rootViewController = screen
        window.isHidden = false
        screen.view.layoutIfNeeded()
        defer {
            window.isHidden = true
        }

        let scroll = screen.scroll
        XCTAssertEqual(scroll.contentSize.width, scroll.bounds.width, accuracy: 0.5, "the content has no width")
        scroll.scrollRectToVisible(low.convert(low.bounds, to: scroll), animated: false)
        XCTAssertGreaterThan(scroll.contentOffset.y, 0, "the form did not scroll to the view below the fold")
        XCTAssertEqual(scroll.contentOffset.x, 0, "the form scrolled sideways")
        let shown = low.convert(low.bounds, to: screen.view)
        XCTAssertTrue(screen.view.bounds.contains(shown), "the view is still out of sight: \(shown)")
        XCTAssertEqual(low.convert(low.bounds, to: screen.view).minX, 16, accuracy: 0.5, "the column left its margin")
    }

    /// The check toxcore puts at the end of an ID, for building one in a test.
    private func checkFor(_ keyAndAntispam: String) -> String {
        var bytes: [UInt8] = []
        var index = keyAndAntispam.startIndex
        while index < keyAndAntispam.endIndex {
            let next = keyAndAntispam.index(index, offsetBy: 2)
            bytes.append(UInt8(keyAndAntispam[index..<next], radix: 16)!)
            index = next
        }
        var check: [UInt8] = [0, 0]
        for (position, byte) in bytes.enumerated() {
            check[position % 2] ^= byte
        }
        return String(format: "%02X%02X", check[0], check[1])
    }
}
