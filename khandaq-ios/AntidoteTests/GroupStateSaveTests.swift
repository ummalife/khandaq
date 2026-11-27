// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 A group's name and settings after a restart of the app (inbox #25, 27.11). A group renamed a second time had its first
 new name again after the app was ended. toxcore keeps a group's topic (its name here) and the founder's settings in the
 profile's save, and nothing saved the profile after a change of them: the next start loaded the save of before, and
 refreshGroupMetadataForChat wrote what that save held over the chat. A change toxcore takes now saves the profile, and
 the new name and settings come back from that save.
 */
final class GroupStateSaveTests: XCTestCase {
    private var bed: GroupTestBed?

    override func tearDown() {
        bed?.remove()
        bed = nil
        super.tearDown()
    }

    func testEveryRenameSavesTheProfileWithTheNewName() throws {
        let bed = try makeBed()
        var topicsAtSave = [String]()
        bed.profile.onSave = {
            topicsAtSave.append((try? bed.tox.groupTopic(forGroupNumber: bed.group)) ?? "?")
        }

        try bed.groups.setGroupTopic("Book club", for: bed.chat)
        try bed.groups.setGroupTopic("Book club 2", for: bed.chat)

        XCTAssertEqual(topicsAtSave, ["Book club", "Book club 2"], "a rename did not save the profile")
        XCTAssertEqual(bed.chat.groupTopic, "Book club 2")
    }

    /// The start after it: a Tox made from the last save has the second name.
    func testTheSecondNameComesBackFromTheSave() throws {
        let bed = try makeBed()
        var saved: Data?
        bed.profile.onSave = {
            saved = bed.tox.save()
        }

        try bed.groups.setGroupTopic("Book club", for: bed.chat)
        try bed.groups.setGroupTopic("Book club 2", for: bed.chat)

        let restarted = try GroupTestBed.makeTox(savedData: XCTUnwrap(saved, "nothing was saved"))
        XCTAssertEqual(try restarted.groupTopic(forGroupNumber: bed.group), "Book club 2")
    }

    /// The founder's settings are in the same save, each saved as it changes.
    func testTheFoundersSettingsComeBackFromTheSave() throws {
        let bed = try makeBed()
        var saves = 0
        var saved: Data?
        bed.profile.onSave = {
            saves += 1
            saved = bed.tox.save()
        }

        try bed.groups.setGroupPassword("secret", for: bed.chat)
        XCTAssertEqual(saves, 1, "a password did not save the profile")
        try bed.groups.setGroupTopicLock(.disabled, for: bed.chat)
        XCTAssertEqual(saves, 2, "a topic lock did not save the profile")
        try bed.groups.setGroupPeerLimit(50, for: bed.chat)
        XCTAssertEqual(saves, 3, "a peer limit did not save the profile")
        try bed.groups.setGroupVoiceState(.moderator, for: bed.chat)
        XCTAssertEqual(saves, 4, "a voice state did not save the profile")
        try bed.groups.setGroupPrivacyState(.public, for: bed.chat)
        XCTAssertEqual(saves, 5, "a privacy state did not save the profile")

        let restarted = try GroupTestBed.makeTox(savedData: XCTUnwrap(saved, "nothing was saved"))
        XCTAssertEqual(try restarted.groupPassword(forGroupNumber: bed.group), "secret")
        XCTAssertEqual(restarted.groupTopicLock(forGroupNumber: bed.group, error: nil), .disabled)
        XCTAssertEqual(restarted.groupPeerLimit(forGroupNumber: bed.group, error: nil), 50)
        XCTAssertEqual(restarted.groupVoiceState(forGroupNumber: bed.group, error: nil), .moderator)
        XCTAssertEqual(restarted.groupPrivacyState(forGroupNumber: bed.group, error: nil), .public)
    }

    /// A change toxcore refuses changes nothing, and there is nothing to save.
    func testARefusedChangeSavesNothing() throws {
        let bed = try makeBed()
        var saves = 0
        bed.profile.onSave = {
            saves += 1
        }

        // toxcore takes a password of at most 32 bytes (TOX_GROUP_MAX_PASSWORD_SIZE).
        XCTAssertThrowsError(try bed.groups.setGroupPassword(String(repeating: "p", count: 40), for: bed.chat))

        XCTAssertEqual(saves, 0)
    }

    private func makeBed() throws -> GroupTestBed {
        let bed = try GroupTestBed(peerName: "Founder", groupName: "Book club 0")
        self.bed = bed
        return bed
    }
}
