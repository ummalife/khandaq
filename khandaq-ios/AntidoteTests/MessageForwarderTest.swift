// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 A re-forwarded voice note arrived as a plain file named "voice_welcome.file 2.m4a". The forward
 sent the on-disk name, which the file store makes unique with " 2", and that broke the ".file.m4a"
 marker both platforms read as "voice message".
 */
class MessageForwarderTest: XCTestCase {
    private func file(named name: String?) -> OCTMessageFile {
        let file = OCTMessageFile()
        file.fileName = name
        return file
    }

    func testForwardKeepsTheNameTheFileCameWith() {
        let name = MessageForwarder.forwardFileName(for: file(named: "voice_welcome.file.m4a"),
                                                    diskPath: "/downloads/voice_welcome.file 2.m4a")
        XCTAssertEqual(name, "voice_welcome.file.m4a")
        XCTAssertTrue(VoiceMessageHelper.isVoiceMessage(fileName: name, filePath: nil))
    }

    func testNamesThatLeaveTheTempDirectoryFallBackToTheDiskName() {
        // The name is the sender's: anything that is not a plain file name must not reach a path.
        for hostile in ["../../Documents/x.m4a", "a/b.m4a", "..", ".", "a\\b.m4a", ""] {
            XCTAssertEqual(MessageForwarder.forwardFileName(for: file(named: hostile), diskPath: "/downloads/safe 2.m4a"),
                           "safe 2.m4a", hostile)
        }
        XCTAssertEqual(MessageForwarder.forwardFileName(for: file(named: nil), diskPath: "/downloads/safe.m4a"),
                       "safe.m4a")
    }

    // MARK: A forwarded text

    private let header = MessageReplyHelper.forwardMark + "Forwarded from Amina"

    func testAForwardThatFitsGoesAsOneMessageAsBefore() {
        let parts = MessageForwarder.forwardTextParts(header: header, body: "Assalamu alaikum", maxBytes: AirComposeLimit.bytes)
        XCTAssertEqual(parts, [header + "\nAssalamu alaikum"])
    }

    /// A part of a long text is as long as a message already: with the header it no longer fit, and went
    /// cut short to a person or refused by a group.
    func testTheForwardOfAPartOfALongTextGoesInPartsThatEachFit() {
        for maxBytes in [AirComposeLimit.bytes, AirGroupComposeLimit.bytes] {
            // Ten bytes a word and a space between: as long as a message, nearly.
            let words = Array(repeating: "слово", count: (maxBytes + 1) / 11)
            let body = words.joined(separator: " ")
            XCTAssertGreaterThan(body.utf8.count, maxBytes - 20)
            XCTAssertLessThanOrEqual(body.utf8.count, maxBytes)

            let parts = MessageForwarder.forwardTextParts(header: header, body: body, maxBytes: maxBytes)
            XCTAssertEqual(parts.count, 2, "\(maxBytes)")
            XCTAssertTrue(parts.allSatisfy { $0.utf8.count <= maxBytes }, "a part does not fit one message")
            XCTAssertTrue(parts[0].hasPrefix(header + "\n"), "the header no longer leads the forward")
            let sentWords = parts.joined(separator: " ").dropFirst(header.count + 1).split(separator: " ").map(String.init)
            XCTAssertEqual(sentWords, words, "words were lost or reordered")
        }
    }
}
