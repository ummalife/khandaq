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
}
