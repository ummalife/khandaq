// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 E13 to E15, the group screens as plain values: the order of the members, who gets a message now and
 what the header and the hint say about it, who may write, what the founder and a moderator may change,
 the invitation list, the name of a new group, the length counter, and how the transcript names
 authors, marks a mention of you and the state of our own message.
 */
class AirGroupModelTests: XCTestCase {
    private var savedLanguage: Any?
    private var savedAppleLanguages: Any?
    private let now: TimeInterval = 1_800_000_000

    override func setUp() {
        super.setUp()
        savedLanguage = UserDefaults.standard.object(forKey: AppLanguage.storageKey)
        savedAppleLanguages = UserDefaults.standard.object(forKey: "AppleLanguages")
        use("en")
    }

    override func tearDown() {
        UserDefaults.standard.set(savedLanguage, forKey: AppLanguage.storageKey)
        UserDefaults.standard.set(savedAppleLanguages, forKey: "AppleLanguages")
        AppLanguage.applyAtLaunch()
        super.tearDown()
    }

    // MARK: - Members

    func testMembersGoByRoleThenOnlineThenLastSeenThenName() {
        let members = [
            member(1, "Yusuf", .user, online: false, lastSeen: now - 600),
            member(2, "Ibrahim", .observer, online: true),
            member(3, "Bilal", .user, online: true),
            member(4, "Amina", .moderator, online: false, lastSeen: now - 60),
            member(5, "Samir", .founder, online: true, isSelf: true),
            member(6, "Zaynab", .user, online: false, lastSeen: now - 3600),
            member(7, "Hamza", .user, online: false, lastSeen: 0),
            member(8, "Aisha", .user, online: true),
        ]
        XCTAssertEqual(AirGroupRoster.sorted(members).map { $0.name },
                       ["Samir", "Amina", "Aisha", "Bilal", "Yusuf", "Zaynab", "Hamza", "Ibrahim"])

        XCTAssertEqual(AirGroupRoster.reachableNow(members).map { $0.name }, ["Aisha", "Bilal", "Ibrahim"],
                       "everyone online but you, in the order of the dossier")
        XCTAssertEqual(AirGroupRoster.moderators(members).map { $0.name }, ["Amina"], "you are never offered to yourself")

        let founderElsewhere = members + [member(9, "Imam Khalid", .founder, online: false, lastSeen: now - 100)]
        XCTAssertEqual(AirGroupRoster.moderators(founderElsewhere).map { $0.name }, ["Imam Khalid", "Amina"],
                       "the founder is among the ones in charge")
    }

    func testSearchByNameIgnoresCaseAndDiacritics() {
        let members = [member(1, "Zaynab Alieva", .user), member(2, "Ibrahim Gasanov", .user), member(3, "Zoë", .user)]
        XCTAssertEqual(AirGroupRoster.filtered(members, query: "  ").count, 3, "an empty query keeps everyone")
        XCTAssertEqual(AirGroupRoster.filtered(members, query: "alieva").map { $0.peerId }, [1])
        XCTAssertEqual(AirGroupRoster.filtered(members, query: "zoe").map { $0.peerId }, [3])
        XCTAssertTrue(AirGroupRoster.filtered(members, query: "Omar").isEmpty)
        XCTAssertEqual(AirGroupRoster.searchThreshold, 20)
    }

    func testMemberRowSaysTheRoleAndThePresence() {
        XCTAssertEqual(AirGroupRoster.statusLine(for: member(1, "Amina", .moderator, online: true), now: now), "moderator, online")
        XCTAssertEqual(AirGroupRoster.statusLine(for: member(2, "Hamza", .user, online: false, lastSeen: 0), now: now), "member, offline")
        XCTAssertEqual(AirGroupRoster.statusLine(for: member(3, "Yusuf", .observer, online: false, lastSeen: now - 25 * 60), now: now),
                       "observer, offline for 25 minutes")

        use("ru")
        XCTAssertEqual(AirGroupRoster.statusLine(for: member(1, "Амина", .founder, online: true), now: now), "основатель, в сети")
        XCTAssertEqual(AirGroupRoster.statusLine(for: member(2, "Хамза", .user, online: false, lastSeen: 0), now: now), "участник, не в сети")
    }

    func testToneFollowsTheGroupKeyWhenKnown() {
        XCTAssertEqual(member(1, "Amina", .user, key: "ABC").toneKey, "ABC")
        XCTAssertEqual(member(1, "Amina", .user).toneKey, "Amina")
    }

    // MARK: - Who gets it now

    /// In a real group toxcore gives you peer id 0, and the strip "Get it now" named you instead of the
    /// others (found on the App Review path, stage 5); the demo world gives you 900.
    func testYouAreTheFirstPeerOfARealGroup() {
        XCTAssertTrue(AirGroupMembers.isSelfPeer(0, selfPeerId: 0, groupNumber: 0))
        XCTAssertFalse(AirGroupMembers.isSelfPeer(1, selfPeerId: 0, groupNumber: 0))
        XCTAssertTrue(AirGroupMembers.isSelfPeer(900, selfPeerId: 900, groupNumber: 3))
        XCTAssertFalse(AirGroupMembers.isSelfPeer(0, selfPeerId: 0, groupNumber: -1), "a chat without a group number has no you")

        let members = [member(0, "Samir", .user, online: true, isSelf: AirGroupMembers.isSelfPeer(0, selfPeerId: 0, groupNumber: 0)),
                       member(1, "Khandaq Demo", .founder, online: true,
                              isSelf: AirGroupMembers.isSelfPeer(1, selfPeerId: 0, groupNumber: 0))]
        XCTAssertEqual(AirGroupRoster.reachableNow(members).map { $0.name }, ["Khandaq Demo"])
    }

    func testHeaderAndHintCountTheMembersAndTheOnesOnline() {
        // The plan's group: 9 members with you, 4 others online. A message is for the 8 others.
        let reach = AirGroupReach(peerCount: 9, knownMembers: 9, online: 4, connected: true)
        XCTAssertEqual(reach.total, 9)
        XCTAssertEqual(reach.recipients, 8)
        XCTAssertEqual(reach.headerText, "9 members, 4 online now")
        XCTAssertEqual(reach.hint, AirComposeHint(text: "4 of 8 get it now, the rest when they come online", dot: .live))
        XCTAssertEqual(AirGroupReach(peerCount: 9, knownMembers: 9, online: 1, connected: true).hint.text,
                       "1 of 8 gets it now, the rest when they come online")

        use("ru")
        XCTAssertEqual(reach.headerText, "9 участников, 4 в сети сейчас")
        XCTAssertEqual(reach.hint.text, "Сразу получат 4 из 8, остальные, когда появятся в сети")
        let large = AirGroupReach(peerCount: 31, knownMembers: 31, online: 21, connected: true)
        XCTAssertEqual(large.headerText, "31 участник, 21 в сети сейчас")
        XCTAssertEqual(large.hint.text, "Сразу получит 21 из 30, остальные, когда появятся в сети")
        XCTAssertEqual(AirGroupReach(peerCount: 3, knownMembers: 3, online: 1, connected: true).headerText, "3 участника, 1 в сети сейчас")
    }

    func testEveryoneOnlineNobodyOnlineAloneAndNotConnected() {
        XCTAssertEqual(AirGroupReach(peerCount: 5, knownMembers: 5, online: 4, connected: true).hint,
                       AirComposeHint(text: "All 4 get it now: everyone is online", dot: .live))
        XCTAssertEqual(AirGroupReach(peerCount: 2, knownMembers: 2, online: 1, connected: true).hint,
                       AirComposeHint(text: "The other member is online and gets it now", dot: .live))

        let nobody = AirGroupReach(peerCount: 9, knownMembers: 9, online: 0, connected: true)
        XCTAssertEqual(nobody.headerText, "9 members, nobody online now")
        XCTAssertEqual(nobody.hint.dot, .transit)
        XCTAssertEqual(nobody.hint.text, "Nobody is online: members get it when they come online")

        let alone = AirGroupReach(peerCount: 1, knownMembers: 1, online: 0, connected: true)
        XCTAssertEqual(alone.headerText, "1 member, nobody online now")
        XCTAssertEqual(alone.hint, AirComposeHint(text: "Only you are in the group so far: invite people from the group dossier", dot: .none))

        let offline = AirGroupReach(peerCount: 9, knownMembers: 9, online: 4, connected: false)
        XCTAssertEqual(offline.online, 0, "nobody gets anything while the group is not connected")
        XCTAssertEqual(offline.headerText, "9 members, not connected to the group")
        XCTAssertEqual(offline.hint.dot, .transit)

        use("ru")
        XCTAssertEqual(AirGroupReach(peerCount: 22, knownMembers: 22, online: 21, connected: true).hint.text,
                       "Сразу получат все 21: все в сети", "21 is a plural form of one in Russian, not one other member")
        XCTAssertEqual(AirGroupReach(peerCount: 2, knownMembers: 2, online: 1, connected: true).hint.text,
                       "Второй участник в сети и получит сразу")
    }

    func testCountsNeverContradictEachOther() {
        // The roster arrived before peerCount did: the known members count.
        XCTAssertEqual(AirGroupReach(peerCount: 0, knownMembers: 4, online: 2, connected: true).total, 4)
        // Never fewer than you.
        XCTAssertEqual(AirGroupReach(peerCount: 0, knownMembers: 0, online: 0, connected: true).total, 1)
        // More online than there are others: capped, so "5 of 3" cannot happen.
        let capped = AirGroupReach(peerCount: 4, knownMembers: 4, online: 9, connected: true)
        XCTAssertEqual(capped.online, 3)
        XCTAssertEqual(capped.hint.text, "All 3 get it now: everyone is online")
        XCTAssertEqual(AirGroupReach(peerCount: 4, knownMembers: 4, online: -2, connected: true).online, 0)
    }

    // MARK: - Who may write

    func testObserversAndTheVoiceStateDecideWhoWrites() {
        for voice in [OCTToxGroupVoiceState.all, .moderator, .founder] {
            XCTAssertEqual(AirGroupComposeRight.make(selfRole: .observer, voiceState: voice), .observer)
        }
        XCTAssertEqual(AirGroupComposeRight.make(selfRole: .user, voiceState: .all), .write)
        XCTAssertEqual(AirGroupComposeRight.make(selfRole: .user, voiceState: .moderator), .moderatorsOnly)
        XCTAssertEqual(AirGroupComposeRight.make(selfRole: .moderator, voiceState: .moderator), .write)
        XCTAssertEqual(AirGroupComposeRight.make(selfRole: .founder, voiceState: .moderator), .write)
        XCTAssertEqual(AirGroupComposeRight.make(selfRole: .moderator, voiceState: .founder), .founderOnly)
        XCTAssertEqual(AirGroupComposeRight.make(selfRole: .founder, voiceState: .founder), .write)

        XCTAssertNil(AirGroupComposeRight.write.readOnlyText)
        XCTAssertNil(AirGroupComposeRight.write.askTitle)
        XCTAssertEqual(AirGroupComposeRight.observer.readOnlyText, "Read only. You are an observer: a moderator can give you the right to write.")
        XCTAssertEqual(AirGroupComposeRight.moderatorsOnly.askTitle, "Write to a moderator")
        XCTAssertEqual(AirGroupComposeRight.founderOnly.askTitle, "Write to the founder")

        use("ru")
        XCTAssertEqual(AirGroupComposeRight.observer.readOnlyText, "Только чтение. Вы наблюдатель: право писать может дать модератор.")
        XCTAssertEqual(AirGroupComposeRight.moderatorsOnly.askTitle, "Написать модератору")
    }

    /// toxcore refuses an observer's private messages (gc_send_private_message returns -5 while your role
    /// is observer), whatever the voice state: an observer has no one to ask inside the group and cannot
    /// answer a moderator's private message. A member where only the moderators or the founder write
    /// still writes privately, which is how they ask.
    func testAnObserverCanNeitherAskNorAnswerPrivately() {
        let members = [
            member(1, "Imam Khalid", .founder, online: false, lastSeen: now - 600),
            member(2, "Hamza", .moderator, online: true),
            member(3, "Maryam", .moderator, online: false, lastSeen: now - 7200),
            member(4, "Bilal", .user, online: true),
            member(5, "Samir", .observer, online: true, isSelf: true),
        ]
        for voice in [OCTToxGroupVoiceState.all, .moderator, .founder] {
            let right = AirGroupComposeRight.make(selfRole: .observer, voiceState: voice)
            XCTAssertNil(right.askTitle, "an observer has no Write to a moderator")
            XCTAssertFalse(right.canWritePrivately, "an observer cannot answer privately")
            XCTAssertEqual(right.privateRefusalText,
                           "An observer cannot write here, not even privately: ask a moderator outside the group.")
            XCTAssertTrue(AirGroupRoster.askable(members, right: right).isEmpty, "nobody to ask inside the group")
        }

        let moderatorsOnly = AirGroupComposeRight.make(selfRole: .user, voiceState: .moderator)
        XCTAssertEqual(moderatorsOnly.askTitle, "Write to a moderator")
        XCTAssertTrue(moderatorsOnly.canWritePrivately)
        XCTAssertNil(moderatorsOnly.privateRefusalText)
        XCTAssertEqual(AirGroupRoster.askable(members, right: moderatorsOnly).map { $0.name }, ["Imam Khalid", "Hamza", "Maryam"])

        let founderOnly = AirGroupComposeRight.make(selfRole: .user, voiceState: .founder)
        XCTAssertEqual(founderOnly.askTitle, "Write to the founder")
        XCTAssertTrue(founderOnly.canWritePrivately)
        XCTAssertNil(founderOnly.privateRefusalText)
        XCTAssertEqual(AirGroupRoster.askable(members, right: founderOnly).map { $0.name }, ["Imam Khalid"],
                       "Write to the founder writes to the founder alone")

        XCTAssertTrue(AirGroupComposeRight.write.canWritePrivately)
        XCTAssertNil(AirGroupComposeRight.write.privateRefusalText)
        XCTAssertTrue(AirGroupRoster.askable(members, right: .write).isEmpty, "nothing to ask while you write")

        // The words the desktop says, so that the three clients say one thing.
        let words = [
            "ru": "Наблюдатель не может писать здесь, даже лично: попросите модератора вне группы.",
            "ar": "لا يستطيع المراقب الكتابة هنا ولو على انفراد: اطلب ذلك من مشرف خارج المجموعة.",
            "zh": "旁观者不能在这里发言，私信也不行：请在群组之外联系管理员。",
        ]
        for (language, text) in words {
            use(language)
            XCTAssertEqual(AirGroupComposeRight.observer.privateRefusalText, text, language)
        }
    }

    /// At AX5 the bar of a group you only read took 522 of 874 points with the line of an observer and
    /// left the log 83. Held to its share of the screen, as ChatGroupController holds it, the bar is as
    /// tall as its text up to that share and scrolls past it; no line of it is cut.
    func testTheReadOnlyBarKeepsToItsShareOfTheScreen() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let rights: [AirGroupComposeRight] = [.observer, .moderatorsOnly]
        for language in ["ru", "ar"] {
            use(language)
            for right in rights {
                for category in [UIContentSizeCategory.large, .accessibilityExtraExtraExtraLarge] {
                    let context = "\(right), \(language), \(category.rawValue)"
                    let laid = layOutReadOnlyBar(right, category: category)
                    let cap = laid.screen * AirGroupReadOnlyBar.screenShare
                    XCTAssertEqual(laid.bar, min(laid.content, cap), accuracy: 0.5, "the bar is not its text up to its share: \(context)")
                    XCTAssertFalse(laid.cut, "a line of the bar lost its tail: \(context)")
                    if category == .large {
                        XCTAssertLessThan(laid.content, cap, "the bar scrolls at the usual size: \(context)")
                    }
                }
            }
            let observer = layOutReadOnlyBar(.observer, category: .accessibilityExtraExtraExtraLarge)
            XCTAssertGreaterThan(observer.content, observer.screen * AirGroupReadOnlyBar.screenShare,
                                 "the text of an observer fits at AX5 without scrolling (\(language)): the cap went untested")
        }
    }

    // MARK: - Roles

    func testFounderAndModeratorsChangeRolesWithinTheirRights() {
        let roles: [OCTToxGroupRole] = [.founder, .moderator, .user, .observer]
        for target in roles {
            for role in roles {
                XCTAssertFalse(AirGroupRoles.canSet(role, on: target, by: .user), "a member manages nothing")
                XCTAssertFalse(AirGroupRoles.canSet(role, on: target, by: .observer), "an observer manages nothing")
                XCTAssertFalse(AirGroupRoles.canSet(.founder, on: target, by: .founder), "nobody is made founder")
                XCTAssertFalse(AirGroupRoles.canSet(role, on: .founder, by: .founder), "the founder's role stays")
            }
            XCTAssertFalse(AirGroupRoles.canKick(target, by: .user))
            XCTAssertFalse(AirGroupRoles.canKick(target, by: .observer))
        }

        XCTAssertEqual(AirGroupMemberAction.available(on: .user, by: .founder), [.makeModerator, .makeObserver, .kick])
        XCTAssertEqual(AirGroupMemberAction.available(on: .moderator, by: .founder), [.makeUser, .makeObserver, .kick])
        XCTAssertEqual(AirGroupMemberAction.available(on: .observer, by: .founder), [.makeModerator, .makeUser, .kick])
        XCTAssertEqual(AirGroupMemberAction.available(on: .founder, by: .founder), [])

        XCTAssertEqual(AirGroupMemberAction.available(on: .user, by: .moderator), [.makeObserver, .kick])
        XCTAssertEqual(AirGroupMemberAction.available(on: .observer, by: .moderator), [.makeUser, .kick])
        XCTAssertEqual(AirGroupMemberAction.available(on: .moderator, by: .moderator), [], "moderators do not manage each other")
        XCTAssertEqual(AirGroupMemberAction.available(on: .founder, by: .moderator), [])
        XCTAssertEqual(AirGroupMemberAction.available(on: .user, by: .user), [])
    }

    func testEveryChangeIsAskedInOneSentenceAndToldOnceDone() {
        let ask = AirGroupMemberAction.makeModerator.confirmation(name: "Amina")
        XCTAssertEqual(ask.title, "Make Amina a moderator?")
        XCTAssertEqual(ask.message, "A moderator makes members observers and back and can remove them.")
        XCTAssertEqual(AirGroupMemberAction.makeModerator.doneText(name: "Amina"), "Amina is a moderator now")
        XCTAssertEqual(AirGroupMemberAction.kick.confirmation(name: "Omar").title, "Remove Omar from the group?")
        XCTAssertEqual(AirGroupMemberAction.kick.doneText(name: "Omar"), "Omar is no longer in the group")
        XCTAssertEqual(AirGroupMemberAction.makeObserver.role, .observer)
        XCTAssertNil(AirGroupMemberAction.kick.role)

        use("ru")
        // The name stays as it is written, so it opens the question instead of taking a case ending.
        XCTAssertEqual(AirGroupMemberAction.makeObserver.confirmation(name: "Амина").title, "Амина: сделать наблюдателем?")
        XCTAssertEqual(AirGroupMemberAction.makeUser.doneText(name: "Омар"), "Омар теперь участник")
        XCTAssertEqual(AirGroupRoles.explanation(.observer), "Вы наблюдатель: читаете группу, но писать не можете. Право писать может дать модератор.")
    }

    // MARK: - Invitations

    func testInvitationListPutsWhoCanBeInvitedFirst() {
        let candidates = [
            candidate(1, "Yusuf", online: false),
            candidate(2, "Bilal", online: true, member: true),
            candidate(3, "Amina", online: true),
            candidate(4, "Timur", online: true),
            candidate(5, "Aisha", online: false),
            candidate(-1, "Zaid", online: true),
        ]
        XCTAssertEqual(AirInviteList.arranged(candidates, query: "").map { $0.name },
                       ["Amina", "Timur", "Bilal", "Zaid", "Aisha", "Yusuf"])
        XCTAssertEqual(AirInviteList.arranged(candidates, query: "AMI").map { $0.name }, ["Amina"])

        XCTAssertTrue(candidate(3, "Amina", online: true).canInvite)
        XCTAssertFalse(candidate(2, "Bilal", online: true, member: true).canInvite, "already in the group")
        XCTAssertFalse(candidate(1, "Yusuf", online: false).canInvite, "an invitation needs the contact online")
        XCTAssertFalse(candidate(-1, "Zaid", online: true).canInvite, "no friend number, nothing to send it over")

        XCTAssertNil(AirInviteList.note(for: candidate(3, "Amina", online: true)))
        XCTAssertEqual(AirInviteList.note(for: candidate(2, "Bilal", online: true, member: true)), "already in the group")
        XCTAssertEqual(AirInviteList.note(for: candidate(1, "Yusuf", online: false)), "can be invited once online")
    }

    func testInvitationListChoosesContactsByKey() {
        // A friend number is a slot of one session (the demo world gives every contact 0): choosing one
        // contact must not choose the others in the same slot.
        let list = AirInviteListView(frame: CGRect(x: 0, y: 0, width: 390, height: 600))
        list.configure(candidates: [candidate(0, "Amina", online: true), candidate(0, "Bilal", online: true),
                                    candidate(0, "Yusuf", online: false)])
        let rows = subviews(of: list).compactMap { $0 as? AirInviteRow }
        XCTAssertEqual(rows.map { $0.candidate.name }, ["Amina", "Bilal", "Yusuf"])
        rows[0].sendActions(for: .touchUpInside)
        XCTAssertEqual(list.chosen.map { $0.name }, ["Amina"])
        rows[1].sendActions(for: .touchUpInside)
        rows[0].sendActions(for: .touchUpInside)
        XCTAssertEqual(list.chosen.map { $0.name }, ["Bilal"])

        // Bilal goes offline: he is no longer chosen, as he cannot be invited now.
        list.configure(candidates: [candidate(0, "Amina", online: true), candidate(0, "Bilal", online: false)])
        XCTAssertTrue(list.chosen.isEmpty)
    }

    func testSentInvitationsAreCounted() {
        XCTAssertEqual(String(localized: "air_invite_sent", 1), "Invitation sent")
        XCTAssertEqual(String(localized: "air_invite_sent", 3), "3 invitations sent")
        use("ru")
        XCTAssertEqual(String(localized: "air_invite_sent", 1), "Отправлено 1 приглашение")
        XCTAssertEqual(String(localized: "air_invite_sent", 3), "Отправлено 3 приглашения")
        XCTAssertEqual(String(localized: "air_invite_sent", 5), "Отправлено 5 приглашений")
    }

    // MARK: - New group

    func testPrivacyIsSaidWithItsConsequence() {
        XCTAssertEqual(AirGroupPrivacy(state: .private), .inviteOnly)
        XCTAssertEqual(AirGroupPrivacy(state: .public), .openById)
        XCTAssertEqual(AirGroupPrivacy.inviteOnly.title, "By invitation only")
        XCTAssertEqual(AirGroupPrivacy.openById.consequence, "Anyone who has the group key can join, no invitation needed.")
    }

    func testNameIsOneLineAndFitsInFortyEightBytes() {
        XCTAssertEqual(AirGroupName.clean("  Book\nclub \r\n2026 "), "Book club 2026")
        XCTAssertNil(AirGroupName.overflow(String(repeating: "a", count: 48)))
        XCTAssertEqual(AirGroupName.overflow(String(repeating: "a", count: 50)), 2)
        // Cyrillic takes two bytes a letter: 25 letters are 50 bytes.
        XCTAssertEqual(AirGroupName.overflow(String(repeating: "ж", count: 25)), 2)
        XCTAssertNil(AirGroupName.overflow(String(repeating: "ж", count: 24)))
        XCTAssertNil(AirGroupName.overflow("  " + String(repeating: "a", count: 48) + "  "), "trimmed before it is counted")
    }

    // MARK: - Field and transcript

    func testCounterShowsTheLastTenthOfAGroupMessage() {
        XCTAssertNil(AirGroupComposeLimit.remaining(forBytes: 0))
        XCTAssertNil(AirGroupComposeLimit.remaining(forBytes: 1234))
        XCTAssertEqual(AirGroupComposeLimit.remaining(forBytes: 1235), 137)
        XCTAssertEqual(AirGroupComposeLimit.remaining(forBytes: 1372), 0)
        XCTAssertEqual(AirGroupComposeLimit.remaining(forBytes: 1400), -28, "over the limit: toxcore would refuse it")
    }

    func testAuthorsAreNamedWithTheRoleOfTheOnesInCharge() {
        XCTAssertEqual(AirGroupTranscript.authorLabel(name: "Amina", role: .moderator), "Amina, moderator")
        XCTAssertEqual(AirGroupTranscript.authorLabel(name: "Imam Khalid", role: .founder), "Imam Khalid, founder")
        XCTAssertEqual(AirGroupTranscript.authorLabel(name: "Bilal", role: .user), "Bilal")
        XCTAssertEqual(AirGroupTranscript.authorLabel(name: "Ibrahim", role: .observer), "Ibrahim")
        XCTAssertEqual(AirGroupTranscript.authorLabel(name: "Omar", role: nil), "Omar")
        use("ar")
        XCTAssertEqual(AirGroupTranscript.authorLabel(name: "أمينة", role: .moderator), "أمينة، مشرف")
    }

    func testMentionOfYouGoesByKeyAndByNameOnlyWithoutOne() {
        let selfKey = String(repeating: "5E", count: 32)
        let other = String(repeating: "A1", count: 32)
        XCTAssertTrue(AirGroupTranscript.mentionsMe([mention(selfKey.lowercased(), "Samir")], selfKey: selfKey, selfName: "Samir Valiev"))
        XCTAssertFalse(AirGroupTranscript.mentionsMe([mention(other, "Samir")], selfKey: selfKey, selfName: "Samir Valiev"),
                       "another Samir in the group is not you")
        XCTAssertTrue(AirGroupTranscript.mentionsMe([mention(other, "Bilal"), mention("", "samir")], selfKey: selfKey, selfName: "Samir Valiev"),
                      "an older client sends the handle alone")
        XCTAssertTrue(AirGroupTranscript.mentionsMe([mention("", "Samir Valiev")], selfKey: nil, selfName: "Samir Valiev"))
        XCTAssertTrue(AirGroupTranscript.mentionsMe([mention(other, "Самир")], selfKey: nil, selfName: "Самир Валиев"),
                      "without your key the handle decides")
        XCTAssertFalse(AirGroupTranscript.mentionsMe([], selfKey: selfKey, selfName: "Samir"))
        XCTAssertFalse(AirGroupTranscript.mentionsMe([mention("", "Samir")], selfKey: nil, selfName: nil))
    }

    func testOwnMessageWaitsLeavesAndIsConfirmed() {
        XCTAssertEqual(AirGroupTranscript.status(pending: true, delivered: true, confirmations: 2), .transit)
        XCTAssertEqual(AirGroupTranscript.status(pending: false, delivered: false, confirmations: 0), .sent)
        XCTAssertEqual(AirGroupTranscript.status(pending: false, delivered: false, confirmations: 1), .delivered)
        XCTAssertEqual(AirGroupTranscript.status(pending: false, delivered: true, confirmations: 0), .delivered)
    }

    // MARK: - Joining by key, the group settings

    func testJoinKeyFieldSaysWhatItHolds() {
        let key = "5D0A7C3E9B1F4A6C8E2D0B5F7A9C1E3D5B7F9A1C3E5D7B9F1A3C5E7D9B1F3A5C"
        let stranger: (String) -> Bool = { _ in false }

        XCTAssertEqual(AirGroupKeyVerdict.of("", isMember: stranger), .empty)
        XCTAssertEqual(AirGroupKeyVerdict.of(" \n ", isMember: stranger), .empty)
        XCTAssertEqual(AirGroupKeyVerdict.of(String(key.prefix(40)), isMember: stranger), .partial(40))
        // In lines as the dossier shows it, in lower case, from a link, or typed full width: the same key.
        XCTAssertEqual(AirGroupKeyVerdict.of(AirKeyFormat.lines(key).lowercased(), isMember: stranger), .ok(key))
        XCTAssertEqual(AirGroupKeyVerdict.of("khandaq://group/" + key, isMember: stranger), .ok(key))
        XCTAssertEqual(AirGroupKeyVerdict.of("KHANDAQ:GROUP:" + key, isMember: stranger), .ok(key))
        let fullWidth = key.applyingTransform(.fullwidthToHalfwidth, reverse: true) ?? key
        XCTAssertNotEqual(fullWidth, key)
        XCTAssertEqual(AirGroupKeyVerdict.of(fullWidth, isMember: stranger), .ok(key))
        // A person's ID is named as one; anything longer or not hex is not a key.
        XCTAssertEqual(AirGroupKeyVerdict.of(key + "0123456789AB", isMember: stranger), .personId)
        XCTAssertEqual(AirGroupKeyVerdict.of(key + "0", isMember: stranger), .notKey)
        XCTAssertEqual(AirGroupKeyVerdict.of("Hiking club", isMember: stranger), .notKey)
        XCTAssertEqual(AirGroupKeyVerdict.of(String(key.prefix(63)) + "\u{0663}", isMember: stranger), .notKey)
        // A group already on this device opens instead of a second join.
        XCTAssertEqual(AirGroupKeyVerdict.of(key, isMember: { $0 == key }), .member(key))

        XCTAssertEqual(AirGroupKeyVerdict.of(key, isMember: stranger).key, key)
        XCTAssertNil(AirGroupKeyVerdict.of(key, isMember: { _ in true }).key)
        XCTAssertNil(AirGroupKeyVerdict.partial(40).key)
        XCTAssertNil(AirGroupKeyVerdict.ok(key).note)
        XCTAssertNil(AirGroupKeyVerdict.empty.note)
        XCTAssertEqual(AirGroupKeyVerdict.partial(40).note, "40 of 64 characters")
        for verdict in [AirGroupKeyVerdict.personId, .notKey, .member(key)] {
            XCTAssertFalse(verdict.note?.isEmpty ?? true, "\(verdict) says nothing")
        }
    }

    func testJoinRefusalsAreWords() {
        let badKey = NSError(domain: "OCTToxErrorDomain", code: OCTToxErrorGroupJoin.badChatId.rawValue)
        let longPassword = NSError(domain: "OCTToxErrorDomain", code: OCTToxErrorGroupJoin.password.rawValue)
        XCTAssertEqual(AirGroupJoinError.text(for: badKey), String(localized: "air_join_key_not_key"))
        XCTAssertEqual(AirGroupJoinError.text(for: longPassword), String(localized: "group_password_too_long"))
        XCTAssertEqual(AirGroupJoinError.text(for: nil), String(localized: "group_join_failed"))
        for text in [AirGroupJoinError.text(for: badKey), AirGroupJoinError.text(for: nil)] {
            XCTAssertFalse(text.contains("_"), "a key instead of words: \(text)")
        }
    }

    func testMemberLimitTakesDigitsOfAnyScriptInRange() {
        XCTAssertEqual(AirGroupSettingsText.peerLimit(from: "100"), 100)
        XCTAssertEqual(AirGroupSettingsText.peerLimit(from: " 65535 "), 65535)
        XCTAssertEqual(AirGroupSettingsText.peerLimit(from: "1"), 1)
        // The Arabic keyboard gives Arabic-Indic digits.
        XCTAssertEqual(AirGroupSettingsText.peerLimit(from: "\u{0661}\u{0662}\u{0663}"), 123)
        XCTAssertNil(AirGroupSettingsText.peerLimit(from: ""))
        XCTAssertNil(AirGroupSettingsText.peerLimit(from: "0"))
        XCTAssertNil(AirGroupSettingsText.peerLimit(from: "65536"))
        XCTAssertNil(AirGroupSettingsText.peerLimit(from: "-5"))
        XCTAssertNil(AirGroupSettingsText.peerLimit(from: "12a"))
        XCTAssertNil(AirGroupSettingsText.peerLimit(from: "1.5"))
        XCTAssertNil(AirGroupSettingsText.peerLimit(from: "99999999999999999999"))
        // A count as the region writes it, with its grouping (the separator is the region's).
        for language in ["en", "ru", "ar"] {
            use(language)
            let written = AirGroupSettingsText.number(65535)
            XCTAssertEqual(written.filter { $0.isNumber }, "65535", language)
            XCTAssertNotEqual(written, "65535", "no grouping in \(language)")
        }
    }

    func testWhoCanWriteNamesEachState() {
        let titles = [OCTToxGroupVoiceState.all, .moderator, .founder].map { AirGroupSettingsText.voiceTitle($0) }
        XCTAssertEqual(Set(titles).count, 3)
        XCTAssertNotNil(AirGroupSettingsText.voiceDetail(.all))
        XCTAssertNil(AirGroupSettingsText.voiceDetail(.moderator))
    }

    // MARK: - Events of a real group (stage 5)

    /// objcTox posts a group's events to a center of its own; the screens hear them on the default one.
    /// Without the bridge the demo group, joined on a real profile, said "1 member, nobody online now"
    /// under the founder's welcome.
    func testTheGroupEventsOfTheManagersOwnCenterReachTheScreens() {
        let manager = OwnCenterManager()
        XCTAssertTrue(ToxNotificationBridge.install(on: manager), "the bridge did not find the manager's center")
        for name in ToxNotificationBridge.names {
            let chatId = "bridge-\(name.rawValue)"
            let arrived = expectation(forNotification: name, object: nil) { note in
                (note.userInfo?["chat"] as? String) == chatId
            }
            manager.notificationCenter.post(name: name, object: nil, userInfo: ["chat": chatId])
            wait(for: [arrived], timeout: 1)
        }
    }

    /// The demo world's manager posts to the default center itself: nothing to bridge, and no echo.
    func testAManagerWithoutACenterOfItsOwnIsLeftAlone() {
        XCTAssertFalse(ToxNotificationBridge.install(on: NSObject()))
        XCTAssertFalse(ToxNotificationBridge.install(on: DefaultCenterManager()))
    }
}

/// Stands for objcTox's manager: a notification center of its own under the same key.
private final class OwnCenterManager: NSObject {
    @objc let notificationCenter = NotificationCenter()
}

/// A manager whose center is the default one: bridging it would post every event twice.
private final class DefaultCenterManager: NSObject {
    @objc let notificationCenter = NotificationCenter.default
}

// MARK: - Fixtures

private extension AirGroupModelTests {
    func use(_ language: String) {
        AppLanguage.set(language)
        XCTAssertEqual(AppLanguage.current, language)
    }

    func member(_ peerId: UInt32, _ name: String, _ role: OCTToxGroupRole, online: Bool = false, lastSeen: TimeInterval = 0,
                isSelf: Bool = false, key: String = "") -> AirGroupMember {
        return AirGroupMember(peerId: peerId, name: name, publicKey: key, role: role, isOnline: online, lastSeen: lastSeen, isSelf: isSelf)
    }

    func candidate(_ number: Int32, _ name: String, online: Bool, member: Bool = false) -> AirInviteCandidate {
        return AirInviteCandidate(friendNumber: number, publicKey: name.uppercased(), name: name, isOnline: online, isMember: member)
    }

    func subviews(of view: UIView) -> [UIView] {
        return view.subviews + view.subviews.flatMap { subviews(of: $0) }
    }

    /// The read-only bar of `right` at the foot of a 402 by 874 window at `category`, held to its share
    /// of the screen as ChatGroupController holds it: its height, the height of what it holds, the
    /// window's height, and whether either line of text is shorter than its text.
    @available(iOS 17.0, *)
    func layOutReadOnlyBar(_ right: AirGroupComposeRight, category: UIContentSizeCategory)
            -> (bar: CGFloat, content: CGFloat, screen: CGFloat, cut: Bool) {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        window.traitOverrides.preferredContentSizeCategory = category
        let bar = AirGroupReadOnlyBar()
        bar.translatesAutoresizingMaskIntoConstraints = false
        window.addSubview(bar)
        NSLayoutConstraint.activate([
            bar.leadingAnchor.constraint(equalTo: window.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: window.trailingAnchor),
            bar.bottomAnchor.constraint(equalTo: window.bottomAnchor),
            bar.heightAnchor.constraint(lessThanOrEqualTo: window.heightAnchor, multiplier: AirGroupReadOnlyBar.screenShare),
        ])
        window.isHidden = false
        bar.configure(text: right.readOnlyText ?? "", askTitle: right.askTitle, canAsk: true, note: right.privateRefusalText)
        window.layoutIfNeeded()

        let content = subviews(of: bar).compactMap { $0 as? UIScrollView }.first?.contentSize.height ?? 0
        let texts = subviews(of: bar).compactMap { $0 as? UILabel }.filter {
            ["air.group.readOnly.text", "air.group.readOnly.note"].contains($0.accessibilityIdentifier ?? "") && !$0.isHidden
        }
        XCTAssertEqual(texts.count, right == .observer ? 2 : 1, "the lines of the bar")
        let cut = texts.contains { label in
            label.sizeThatFits(CGSize(width: label.bounds.width, height: .greatestFiniteMagnitude)).height > label.bounds.height + 0.5
        }
        window.isHidden = true
        return (bar.bounds.height, content, window.bounds.height, cut)
    }

    func mention(_ key: String, _ handle: String) -> GroupMentionHelper.MentionEntry {
        return GroupMentionHelper.MentionEntry(pubkey: key, handle: handle)
    }
}
