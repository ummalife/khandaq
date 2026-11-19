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
    /// The profiles of the dossier tests, removed after each test.
    private var directories: [URL] = []

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
        directories.forEach { try? FileManager.default.removeItem(at: $0) }
        directories = []
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
        // KHANDAQ (0.2.60): those offline come right after those online: their invitation waits for them.
        XCTAssertEqual(AirInviteList.arranged(candidates, query: "").map { $0.name },
                       ["Amina", "Timur", "Aisha", "Yusuf", "Bilal", "Zaid"])
        XCTAssertEqual(AirInviteList.arranged(candidates, query: "AMI").map { $0.name }, ["Amina"])

        XCTAssertTrue(candidate(3, "Amina", online: true).canInvite)
        XCTAssertFalse(candidate(2, "Bilal", online: true, member: true).canInvite, "already in the group")
        XCTAssertTrue(candidate(1, "Yusuf", online: false).canInvite, "a contact offline is invited too")
        XCTAssertFalse(candidate(-1, "Zaid", online: true).canInvite, "no friend number, nothing to send it over")

        XCTAssertNil(AirInviteList.note(for: candidate(3, "Amina", online: true)))
        XCTAssertEqual(AirInviteList.note(for: candidate(2, "Bilal", online: true, member: true)), "already in the group")
        XCTAssertEqual(AirInviteList.note(for: candidate(1, "Yusuf", online: false)), "gets the invitation once online")
        XCTAssertNil(AirInviteList.note(for: candidate(-1, "Zaid", online: false)), "nothing waits for a contact who cannot be invited")
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

        // Yusuf is offline: his row takes a choice and says that the invitation waits for him (0.2.60).
        XCTAssertTrue(rows[2].isEnabled)
        XCTAssertEqual(rows[2].accessibilityValue, "gets the invitation once online")
        rows[2].sendActions(for: .touchUpInside)
        XCTAssertEqual(list.chosen.map { $0.name }, ["Bilal", "Yusuf"])

        // Bilal goes offline: he stays chosen, his invitation will wait. Yusuf joined in the meantime.
        list.configure(candidates: [candidate(0, "Amina", online: true), candidate(0, "Bilal", online: false),
                                    candidate(0, "Yusuf", online: true, member: true)])
        XCTAssertEqual(list.chosen.map { $0.name }, ["Bilal"])
    }

    func testSentInvitationsAreCounted() {
        XCTAssertEqual(String(localized: "air_invite_sent", 1), "Invitation sent")
        XCTAssertEqual(String(localized: "air_invite_sent", 3), "3 invitations sent")
        XCTAssertEqual(String(localized: "air_invite_later", 1), "Invitation will go once the contact is online")
        XCTAssertEqual(String(localized: "air_invite_later", 2), "2 invitations will go once the contacts are online")
        var tally = AirInviteTally()
        XCTAssertNil(tally.toastText, "nothing went and nothing waits: no toast")
        tally.sent = 1
        tally.later = 2
        XCTAssertEqual(tally.toastText, "Invitation sent\n2 invitations will go once the contacts are online")
        use("ru")
        XCTAssertEqual(String(localized: "air_invite_sent", 1), "Отправлено 1 приглашение")
        XCTAssertEqual(String(localized: "air_invite_sent", 3), "Отправлено 3 приглашения")
        XCTAssertEqual(String(localized: "air_invite_sent", 5), "Отправлено 5 приглашений")
        XCTAssertEqual(String(localized: "air_invite_later", 1), "1 приглашение уйдёт при появлении в сети")
        XCTAssertEqual(String(localized: "air_invite_later", 21), "21 приглашение уйдёт при появлении в сети")
        XCTAssertEqual(String(localized: "air_invite_later", 3), "3 приглашения уйдут при появлении в сети")
        XCTAssertEqual(String(localized: "air_invite_later", 5), "5 приглашений уйдут при появлении в сети")
        XCTAssertEqual(String(localized: "air_invite_when_online"), "получит приглашение, когда будет в сети")
    }

    /// KHANDAQ (0.2.60): an invitation a contact offline now cannot take waits on this device; one sent or
    /// refused does not, and only toxcore's "not sent now" counts as offline.
    func testAnInvitationThatCannotGoNowWaitsOnThisDevice() {
        let store = AirLocalStore()
        let date = Date(timeIntervalSince1970: now)
        let friends: [(number: OCTToxFriendNumber, key: String)] = [(1, key(1)), (2, key(2).lowercased()), (3, key(3)), (4, key(4)), (-1, key(5))]
        let answers: [OCTToxFriendNumber: OCTToxErrorGroupInviteFriend] = [2: .inviteFail, 3: .disconnected, 4: .friendNotFound]
        var asked: [OCTToxFriendNumber] = []
        let tally = AirOutgoingInvites.invite(friends: friends, groupNumber: 7, groupKey: groupKey(1), store: store, now: date) { friend, group in
            XCTAssertEqual(group, 7)
            asked.append(friend)
            if let code = answers[friend] {
                throw toxError(code)
            }
        }
        XCTAssertEqual(asked, [1, 2, 3, 4], "a contact without a friend number is not asked for")
        XCTAssertEqual(tally.sent, 1)
        XCTAssertEqual(tally.later, 2)
        XCTAssertEqual(tally.refused, 2)
        XCTAssertEqual(tally.firstRefusal?.code, OCTToxErrorGroupInviteFriend.friendNotFound.rawValue)
        XCTAssertEqual(store.outgoingInvites, [AirOutgoingInvite(friendKey: key(2), groupKey: groupKey(1), date: date),
                                               AirOutgoingInvite(friendKey: key(3), groupKey: groupKey(1), date: date)])
        XCTAssertEqual(tally.toastText, "Invitation sent\n2 invitations will go once the contacts are online")

        // Asked again while it waits: it goes now, and from the store too.
        let again = AirOutgoingInvites.invite(friends: [(2, key(2))], groupNumber: 7, groupKey: groupKey(1), store: store, now: date) { _, _ in }
        XCTAssertEqual(again.sent, 1)
        XCTAssertEqual(store.outgoingInvites.map { $0.friendKey }, [key(3)])

        // A group whose key is not known cannot keep one: it is a refusal, with the error said.
        let unknownGroup = AirOutgoingInvites.invite(friends: [(4, key(4))], groupNumber: 7, groupKey: "", store: store, now: date) { _, _ in
            throw toxError(.inviteFail)
        }
        XCTAssertEqual(unknownGroup.refused, 1)
        XCTAssertEqual(unknownGroup.firstRefusal?.code, OCTToxErrorGroupInviteFriend.inviteFail.rawValue)

        XCTAssertEqual(AirInviteOutcome(error: nil), .sent)
        XCTAssertEqual(AirInviteOutcome(error: toxError(.failSend)), .later)
        XCTAssertEqual(AirInviteOutcome(error: toxError(.groupNotFound)), .refused)
        XCTAssertEqual(AirInviteOutcome(error: toxError(.unknown)), .refused)
        XCTAssertEqual(AirInviteOutcome(error: NSError(domain: "other", code: OCTToxErrorGroupInviteFriend.inviteFail.rawValue)), .refused)
    }

    /// KHANDAQ (0.2.60): the invitations that wait go once the contact is online and the group is with toxcore;
    /// those of a contact or a group gone, those refused and those older than thirty days go from the store.
    func testWaitingInvitationsGoOnceTheContactIsOnline() {
        let store = AirLocalStore()
        let date = Date(timeIntervalSince1970: now)
        let waiting = [(key(1), groupKey(1)), (key(2), groupKey(1)), (key(3), groupKey(1)), (key(1), groupKey(2)),
                       (key(4), groupKey(3)), (key(5), groupKey(1)), (key(6), groupKey(1))]
        for (friend, group) in waiting {
            XCTAssertTrue(store.keepOutgoingInvite(friendKey: friend, groupKey: group, at: date, now: date))
        }
        let contacts: [String: AirOutgoingInvites.Contact] = [key(1): .online(1), key(2): .offline, key(3): .gone,
                                                               key(4): .online(4), key(5): .online(5), key(6): .online(6)]
        let groups: [String: AirOutgoingInvites.Group] = [groupKey(1): .ready(7), groupKey(2): .gone, groupKey(3): .waiting]
        var asked: [OCTToxFriendNumber] = []
        let sent = AirOutgoingInvites.sendWaiting(store: store, now: date.addingTimeInterval(3600),
                                                  contact: { contacts[$0] ?? .gone },
                                                  group: { groups[$0] ?? .gone }) { friend, group in
            XCTAssertEqual(group, 7)
            asked.append(friend)
            if friend == 5 {
                throw toxError(.failSend)
            }
            if friend == 6 {
                throw toxError(.friendNotFound)
            }
        }
        XCTAssertEqual(sent, 1)
        XCTAssertEqual(asked, [1, 5, 6], "only a contact online now, in a group with toxcore, is asked for")
        XCTAssertEqual(store.outgoingInvites.map { $0.friendKey }, [key(2), key(4), key(5)])

        // Thirty days after they were asked for, those still waiting go without a word to toxcore.
        asked = []
        let late = AirOutgoingInvites.sendWaiting(store: store, now: date.addingTimeInterval(AirLocalStore.outgoingInviteKeepSeconds + 1),
                                                  contact: { _ in .online(9) },
                                                  group: { _ in .ready(7) }) { friend, _ in
            asked.append(friend)
        }
        XCTAssertEqual(late, 0)
        XCTAssertTrue(asked.isEmpty)
        XCTAssertTrue(store.outgoingInvites.isEmpty)
    }

    // MARK: - New group

    func testPrivacyIsSaidWithItsConsequence() {
        XCTAssertEqual(AirGroupPrivacy(state: .private), .inviteOnly)
        XCTAssertEqual(AirGroupPrivacy(state: .public), .openById)
        XCTAssertEqual(AirGroupPrivacy.inviteOnly.title, "By invitation only")
        XCTAssertEqual(AirGroupPrivacy.openById.consequence, "Anyone who has the group key can join, no invitation needed.")
    }

    /// A group by invitation only cannot be joined by its key, and its answer says so.
    func testInvitationOnlySaysTheKeyLetsNobodyIn() {
        XCTAssertEqual(AirGroupPrivacy.inviteOnly.consequence,
                       "Only people invited by a member can join. The group key does not let anyone in.")
        use("ru")
        XCTAssertEqual(AirGroupPrivacy.inviteOnly.consequence,
                       "Войти можно только по приглашению кого-то из участников. По ключу группы войти нельзя.")
    }

    /// A new group is open to anyone with its key unless its founder closes it on the sheet.
    func testANewGroupOpensByItsKeyUnlessItsFounderClosesIt() throws {
        var created: [Bool] = []
        let sheet = AirNewGroupController(candidates: [])
        sheet.onCreate = { _, inviteOnly, _ in
            created.append(inviteOnly)
        }
        sheet.loadViewIfNeeded()

        let rows = subviews(of: sheet.view).compactMap { $0 as? AirChoiceRow }
        XCTAssertEqual(rows.map { $0.accessibilityIdentifier ?? "" }, ["air.newGroup.privacy.open", "air.newGroup.privacy.inviteOnly"],
                       "the open answer comes first")
        let open = try XCTUnwrap(rows.first)
        let closed = try XCTUnwrap(rows.last)
        XCTAssertTrue(open.isSelected)
        XCTAssertFalse(closed.isSelected)

        let name = try XCTUnwrap(subviews(of: sheet.view).first { $0.accessibilityIdentifier == "air.newGroup.name" } as? UITextView)
        name.text = "Book club"
        name.delegate?.textViewDidChange?(name)
        let create = try XCTUnwrap(subviews(of: sheet.view).first { $0.accessibilityIdentifier == "air.newGroup.create" } as? UIControl)
        create.sendActions(for: .touchUpInside)

        closed.sendActions(for: .touchUpInside)
        XCTAssertTrue(closed.isSelected)
        XCTAssertFalse(open.isSelected)
        create.sendActions(for: .touchUpInside)
        XCTAssertEqual(created, [false, true])
    }

    /// The dossier of a group by invitation only neither shows nor hands out its key: it says how members
    /// join, with Invite, and its founder can open the group to anyone with the key from there.
    func testTheDossierOfAGroupByInvitationOnlyKeepsItsKey() throws {
        let world = try dossierWorld(privacy: .private, role: .founder)
        world.dossier.loadViewIfNeeded()
        XCTAssertNotNil(view(in: world.dossier, "air.group.dossier.inviteOnly"))
        XCTAssertNotNil(view(in: world.dossier, "air.group.dossier.invite"))
        for gone in ["air.group.dossier.key", "air.group.dossier.copy", "air.group.dossier.qr"] {
            XCTAssertNil(view(in: world.dossier, gone), "\(gone) in a group by invitation only")
        }
        XCTAssertFalse(showsKey(world.dossier), "the key is on the screen")

        let open = try XCTUnwrap(view(in: world.dossier, "air.group.dossier.openByKey") as? UISwitch)
        XCTAssertFalse(open.isOn)
        open.isOn = true
        open.sendActions(for: .valueChanged)

        XCTAssertEqual(world.chat.groupPrivacyState, Int32(OCTToxGroupPrivacyState.public.rawValue))
        for shown in ["air.group.dossier.key", "air.group.dossier.copy", "air.group.dossier.qr", "air.group.dossier.invite"] {
            XCTAssertNotNil(view(in: world.dossier, shown), "\(shown) once the group is open")
        }
        XCTAssertNil(view(in: world.dossier, "air.group.dossier.inviteOnly"))
        XCTAssertNil(view(in: world.dossier, "air.group.dossier.openByKey"))
        XCTAssertTrue(showsKey(world.dossier))
    }

    func testOnlyTheFounderCanOpenAGroupByInvitationOnly() throws {
        for role in [OCTToxGroupRole.moderator, .user, .observer] {
            let world = try dossierWorld(privacy: .private, role: role)
            world.dossier.loadViewIfNeeded()
            XCTAssertNotNil(view(in: world.dossier, "air.group.dossier.inviteOnly"))
            XCTAssertNil(view(in: world.dossier, "air.group.dossier.openByKey"), "a switch for role \(role.rawValue)")
        }
    }

    /// Who can join may change while the dossier is open: in the group settings, on another device of the
    /// founder, or when the group's state first arrives from its members. objcTox stores it, and the
    /// dossier follows the store.
    func testTheDossierFollowsWhoCanJoinAsTheProfileStoresIt() throws {
        let world = try dossierWorld(privacy: .public, role: .user)
        world.dossier.loadViewIfNeeded()
        XCTAssertNotNil(view(in: world.dossier, "air.group.dossier.key"))
        XCTAssertNil(view(in: world.dossier, "air.group.dossier.inviteOnly"))

        // As objcTox stores it from the privacy callback of toxcore.
        let realm: RLMRealm = world.realmManager.realm
        realm.beginWriteTransaction()
        world.chat.groupPrivacyState = Int32(OCTToxGroupPrivacyState.private.rawValue)
        try realm.commitWriteTransaction()

        let closed = expectation(description: "the dossier took the key away")
        pollUntil({ self.view(in: world.dossier, "air.group.dossier.inviteOnly") != nil }) {
            closed.fulfill()
        }
        wait(for: [closed], timeout: 5)
        XCTAssertNil(view(in: world.dossier, "air.group.dossier.key"))
        XCTAssertFalse(showsKey(world.dossier))
    }

    /// The settings of a group open by its key, Khandaq Community among them. Opening them asks objcTox to
    /// store what toxcore holds of the group, one write after another through the queue of the realm
    /// manager, and Realm hands the screen the stored "who can join" inside the next of those writes. The
    /// screen answered there, wrote once more from inside the queue and stopped the app (16.11); it answers
    /// once the write is done, and only when who can join is not what it shows.
    func testTheSettingsOfAGroupOpenByItsKeyOpenAndFollowItWithoutWritingInsideAWrite() throws {
        for role in [OCTToxGroupRole.user, .founder] {
            let groups = StoringGroups()
            let world = try dossierWorld(privacy: .public, role: role, groups: groups)
            groups.realmManager = world.realmManager
            let settings = GroupInfoController(chat: world.chat, submanagerGroups: groups)
            settings.loadViewIfNeeded()
            settings.viewWillAppear(false)
            runMainQueue()
            XCTAssertEqual(groups.callsInsideAWrite, [], "role \(role.rawValue)")
            XCTAssertEqual(groups.refreshes, 1, "an unchanged state rebuilt the screen, role \(role.rawValue)")
            if role == .founder {
                XCTAssertEqual((view(in: settings, "air.group.settings.open") as? UIControl)?.isSelected, true)
            }

            // The founder closes the group elsewhere: the screen follows, once.
            groups.storeAsThePrivacyCallbackDoes(.private, for: world.chat)
            let followed = expectation(description: "the settings followed who can join, role \(role.rawValue)")
            pollUntil({ groups.refreshes == 2 }) {
                followed.fulfill()
            }
            wait(for: [followed], timeout: 5)
            runMainQueue()
            XCTAssertEqual(groups.refreshes, 2, "role \(role.rawValue)")
            XCTAssertEqual(groups.callsInsideAWrite, [], "role \(role.rawValue)")
            if role == .founder {
                XCTAssertEqual((view(in: settings, "air.group.settings.inviteOnly") as? UIControl)?.isSelected, true)
            }
        }
    }

    /// The same change as toxcore's privacy callback brings it while the dossier is open: objcTox stores who
    /// can join, then the system line that says so, and Realm hands the dossier the change inside that second
    /// write. Rebuilt there, the dossier counted the group's system lines through the queue of the realm
    /// manager from inside it, which stops the app; it rebuilds once the write is done.
    func testTheDossierFollowsWhoCanJoinWithoutReadingInsideAWrite() throws {
        let groups = StoringGroups()
        let world = try dossierWorld(privacy: .public, role: .user, groups: groups)
        groups.realmManager = world.realmManager
        world.dossier.loadViewIfNeeded()
        XCTAssertNotNil(view(in: world.dossier, "air.group.dossier.key"))

        groups.storeAsThePrivacyCallbackDoes(.private, for: world.chat)
        XCTAssertEqual(groups.callsInsideAWrite, [])
        let closed = expectation(description: "the dossier took the key away")
        pollUntil({ self.view(in: world.dossier, "air.group.dossier.inviteOnly") != nil }) {
            closed.fulfill()
        }
        wait(for: [closed], timeout: 5)
        XCTAssertNil(view(in: world.dossier, "air.group.dossier.key"))
        XCTAssertEqual(groups.callsInsideAWrite, [])
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

    /// KHANDAQ (0.2.58): the name over an entry opens the member who wrote it. Amina wrote with number 5,
    /// left, and toxcore gave 5 to Bilal, who came next: her entry is hers by the key it kept, never Bilal's
    /// by the number, and with Amina gone it is nobody's.
    func testTheAuthorOfAnEntryIsTheMemberWithTheKeyItKept() {
        let aminaKey = String(repeating: "A1", count: 32)
        let bilalKey = String(repeating: "B2", count: 32)
        let bilal = member(5, "Bilal", .user, key: bilalKey)
        let amina = member(9, "Amina", .moderator, key: aminaKey)

        XCTAssertEqual(AirGroupTranscript.author(key: aminaKey, peerId: 5, name: "Amina", among: [bilal, amina]), amina,
                       "she came back with another number")
        XCTAssertEqual(AirGroupTranscript.author(key: aminaKey.lowercased(), peerId: 5, name: nil, among: [bilal, amina]), amina,
                       "a file keeps its author's key in lower case")
        XCTAssertNil(AirGroupTranscript.author(key: aminaKey, peerId: 5, name: "Amina", among: [bilal]),
                     "Bilal has her number now, not her entry")
        XCTAssertNil(AirGroupTranscript.author(key: aminaKey, peerId: 5, name: "Amina", among: [member(5, "Amina", .user)]),
                     "a member whose key is not known yet is nobody's author")
        XCTAssertNil(AirGroupTranscript.author(key: aminaKey, peerId: 9, name: "Amina", among: []))
    }

    /// KHANDAQ (0.2.63): the role over an entry is its author's. QA-Live of 0.2.63: the founder came back with
    /// the old peer number of a member, and that member's entry was labeled "founder".
    func testTheRoleOverAnEntryIsItsAuthorsNotThatOfItsOldNumber() {
        let memberKey = String(repeating: "C3", count: 32)
        let founderKey = String(repeating: "D4", count: 32)
        let founder = member(2, "Droid", .founder, key: founderKey)
        let windows = member(7, "QA A", .user, key: memberKey)

        XCTAssertNil(AirGroupTranscript.authorRole(key: memberKey, peerId: 2, name: "QA A", among: [founder]),
                     "the founder has the member's old number now, not the member's entry")
        XCTAssertEqual(AirGroupTranscript.authorLabel(name: "QA A",
                                                      role: AirGroupTranscript.authorRole(key: memberKey, peerId: 2, name: "QA A",
                                                                                          among: [founder])),
                       "QA A")
        XCTAssertEqual(AirGroupTranscript.authorRole(key: memberKey, peerId: 2, name: "QA A", among: [founder, windows]), .user,
                       "the member came back with another number")
        XCTAssertEqual(AirGroupTranscript.authorRole(key: founderKey.lowercased(), peerId: 7, name: nil, among: [founder, windows]),
                       .founder, "the founder's own entry, with a number someone else has now")
        XCTAssertNil(AirGroupTranscript.authorRole(key: "", peerId: 2, name: "QA A", among: [founder]),
                     "an entry without a key whose number went to someone else")
        XCTAssertEqual(AirGroupTranscript.authorRole(key: "", peerId: 2, name: "Droid", among: [founder]), .founder,
                       "an older entry without a key, while its number still has its author")
        XCTAssertNil(AirGroupTranscript.authorRole(key: "", peerId: 0, name: "Droid", among: [member(0, "Droid", .founder)]))
    }

    /// An older entry kept no key: its author is the member with its number, while that member has the name
    /// the entry kept; an entry without a number names nobody.
    func testAnEntryWithoutAKeyGoesByItsNumberWhileTheNameItKeptAgrees() {
        let bilal = member(5, "Bilal", .user, key: String(repeating: "B2", count: 32))
        let far = member(UInt32.max - 1, "Omar", .user)

        XCTAssertEqual(AirGroupTranscript.author(key: "", peerId: 5, name: nil, among: [bilal]), bilal, "the entry kept no name")
        XCTAssertEqual(AirGroupTranscript.author(key: "  ", peerId: 5, name: " Bilal ", among: [bilal]), bilal)
        XCTAssertNil(AirGroupTranscript.author(key: "", peerId: 5, name: "Amina", among: [bilal]), "number 5 went to someone else")
        XCTAssertNil(AirGroupTranscript.author(key: "", peerId: 6, name: nil, among: [bilal]), "the author is not in the group now")
        XCTAssertNil(AirGroupTranscript.author(key: "", peerId: 0, name: "Bilal", among: [member(0, "Bilal", .user)]),
                     "toxcore numbers only the entries of others")
        XCTAssertEqual(AirGroupTranscript.author(key: "", peerId: Int32(bitPattern: UInt32.max - 1), name: "Omar", among: [bilal, far]), far,
                       "a number past Int32.max is stored by its bits")
    }

    /// The key and the name an entry kept of its author: a text from the group's history keeps them on its
    /// text, a file on its file, a live text the key on the entry; our own entries keep the key of nobody.
    func testAnEntryKeepsTheKeyAndTheNameOfItsAuthorWhereItCame() throws {
        let key = String(repeating: "C3", count: 32)

        let live = try groupEntry(peerId: 5)
        live.groupPrivatePeerPubkey = key.lowercased()
        let liveText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        liveText.groupPeerName = "Amina"
        live.messageText = liveText
        XCTAssertEqual(AirGroupTranscript.authorKey(of: live), key.lowercased())
        XCTAssertEqual(AirGroupTranscript.authorName(of: live), "Amina")

        let synced = try groupEntry(peerId: 5)
        let syncedText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        syncedText.groupSenderPubkey = key
        synced.messageText = syncedText
        XCTAssertEqual(AirGroupTranscript.authorKey(of: synced), key)
        XCTAssertNil(AirGroupTranscript.authorName(of: synced), "it kept no name")

        let file = try groupEntry(peerId: 5)
        let messageFile = try appClass("OCTMessageFile", as: OCTMessageFile.Type.self).init()
        messageFile.groupSenderPubkey = key.lowercased()
        messageFile.groupPeerName = "Bilal"
        file.messageFile = messageFile
        XCTAssertEqual(AirGroupTranscript.authorKey(of: file), key.lowercased())
        XCTAssertEqual(AirGroupTranscript.authorName(of: file), "Bilal")

        let ours = try groupEntry(peerId: 0)
        ours.groupPrivateMessage = true
        ours.groupPrivatePeerPubkey = key
        XCTAssertEqual(AirGroupTranscript.authorKey(of: ours), "", "the key of the member it went to")
    }

    /// KHANDAQ (0.2.63): a member is one tone wherever their entry came from, the tone of their tile. QA-Live of
    /// 0.2.63: the live texts of QA Mac went by the name and the text the history brought of them by the key, two
    /// tones for one member. The runs go by the key too, and an entry of the history keeps a run of its own.
    func testAMemberIsOneToneWhereverTheirEntryCameFrom() throws {
        let key = String(repeating: "5E", count: 32)
        let tile = member(3, "QA Mac", .user, key: key)

        let live = try groupEntry(peerId: 3)
        live.groupPrivatePeerPubkey = key.lowercased()
        let liveText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        liveText.groupPeerName = "QA Mac"
        live.messageText = liveText

        let relayed = try groupEntry(peerId: 0)
        relayed.groupHistorySync = true
        let relayedText = try appClass("OCTMessageText", as: OCTMessageText.Type.self).init()
        relayedText.groupSenderPubkey = key
        relayedText.groupPeerName = "QA Mac"
        relayed.messageText = relayedText

        let file = try groupEntry(peerId: 3)
        let liveFile = try appClass("OCTMessageFile", as: OCTMessageFile.Type.self).init()
        liveFile.groupSenderPubkey = key.lowercased()
        file.messageFile = liveFile

        let tone = AirTone.index(for: tile.toneKey)
        for (entry, what) in [(live, "a live text"), (relayed, "a text of the history"), (file, "a live file")] {
            XCTAssertEqual(AirGroupTranscript.toneKey(of: entry, name: "QA Mac"), key, what)
            XCTAssertEqual(AirTone.index(for: AirGroupTranscript.toneKey(of: entry, name: "QA Mac")), tone,
                           "\(what) is not in the tone of the member's tile")
        }

        let keyless = try groupEntry(peerId: 3)
        XCTAssertEqual(AirGroupTranscript.toneKey(of: keyless, name: "Omar"), "Omar", "an entry that kept no key")

        // The runs: by the key, so two members of one name are two runs; the history apart, and its unattributed
        // entries apart again.
        XCTAssertEqual(AirGroupTranscript.runAuthor(of: live, attributed: true, name: "QA Mac"), key)
        XCTAssertEqual(AirGroupTranscript.runAuthor(of: file, attributed: true, name: "QA Mac"), key)
        let namesake = try groupEntry(peerId: 4)
        namesake.groupPrivatePeerPubkey = String(repeating: "A7", count: 32).lowercased()
        XCTAssertNotEqual(AirGroupTranscript.runAuthor(of: namesake, attributed: true, name: "QA Mac"),
                          AirGroupTranscript.runAuthor(of: live, attributed: true, name: "QA Mac"),
                          "two members of one name made one run")
        let fromHistory = AirGroupTranscript.runAuthor(of: relayed, attributed: true, name: "QA Mac")
        let unattributed = AirGroupTranscript.runAuthor(of: relayed, attributed: false, name: "QA Mac")
        XCTAssertNotEqual(fromHistory, key, "an entry of the history joined the live run")
        XCTAssertNotEqual(unattributed, fromHistory, "an unattributed entry joined an attributed run")
        XCTAssertNotEqual(unattributed, key)
        XCTAssertEqual(AirGroupTranscript.runAuthor(of: keyless, attributed: true, name: "Omar"), "Omar")
    }

    /// KHANDAQ (0.2.63): the rows are drawn again when what their labels take from the members changes, and only
    /// then. QA-Live of 0.2.63: a chat opened before the group connected kept the founder's entries without
    /// "founder" once the members came.
    func testTheLabelsAreDrawnAgainWhenWhoHasWhichRoleChanges() {
        let founderKey = String(repeating: "D4", count: 32)
        let founder = member(1, "QA Droid B", .founder, online: true, lastSeen: 10, key: founderKey)
        let mac = member(2, "QA Mac", .user, key: String(repeating: "5E", count: 32))
        let facts = AirGroupTranscript.authorFacts(of: [founder, mac])

        XCTAssertNotEqual(AirGroupTranscript.authorFacts(of: []), facts, "the members the group got once it connected")
        XCTAssertEqual(AirGroupTranscript.authorFacts(of: [mac, founder]), facts, "the same members in another order")
        XCTAssertEqual(AirGroupTranscript.authorFacts(of: [member(1, "QA Droid B", .founder, online: false, lastSeen: 99,
                                                                  key: founderKey.lowercased()), mac]),
                       facts, "who is online and when they were seen is not over the entries")
        XCTAssertNotEqual(AirGroupTranscript.authorFacts(of: [member(1, "QA Droid B", .moderator, key: founderKey), mac]),
                          facts, "a role changed")
        XCTAssertNotEqual(AirGroupTranscript.authorFacts(of: [member(1, "Droid", .founder, key: founderKey), mac]),
                          facts, "a name changed")
        XCTAssertNotEqual(AirGroupTranscript.authorFacts(of: [member(3, "QA Droid B", .founder, key: founderKey), mac]),
                          facts, "a peer number went to another member")
    }

    /// KHANDAQ (0.2.63): the rows drawn again keep the messages you picked for Delete or Copy picked. reloadRows
    /// alone takes the selection off every row it reloads (rows 1 and 3 picked, none after it).
    func testTheRowsDrawnAgainKeepWhatYouPicked() {
        let rows = PlainRows(count: 30)
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        let table = UITableView(frame: window.bounds, style: .plain)
        table.register(UITableViewCell.self, forCellReuseIdentifier: PlainRows.identifier)
        table.dataSource = rows
        table.allowsMultipleSelectionDuringEditing = true
        window.addSubview(table)
        window.isHidden = false
        table.reloadData()
        table.layoutIfNeeded()
        table.setEditing(true, animated: false)
        let picked = [IndexPath(row: 1, section: 0), IndexPath(row: 3, section: 0)]
        picked.forEach { table.selectRow(at: $0, animated: false, scrollPosition: .none) }
        let offscreen = IndexPath(row: 29, section: 0)
        table.selectRow(at: offscreen, animated: false, scrollPosition: .none)
        table.scrollToRow(at: IndexPath(row: 0, section: 0), at: .top, animated: false)
        table.layoutIfNeeded()
        XCTAssertFalse(table.indexPathsForVisibleRows?.contains(offscreen) ?? true, "row 29 is off the screen")

        table.reloadVisibleRowsKeepingSelection()
        table.layoutIfNeeded()

        XCTAssertEqual(Set(table.indexPathsForSelectedRows ?? []), Set(picked + [offscreen]))
        for indexPath in picked {
            XCTAssertTrue(table.cellForRow(at: indexPath)?.isSelected ?? false, "row \(indexPath.row) is drawn picked")
        }
        window.isHidden = true
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

    /// A contact's key or a group's chat id: 64 hexadecimal characters in upper case.
    func key(_ index: Int) -> String {
        return String(repeating: "A", count: 60) + String(format: "%04X", index)
    }

    func groupKey(_ index: Int) -> String {
        return String(repeating: "C", count: 60) + String(format: "%04X", index)
    }

    func toxError(_ code: OCTToxErrorGroupInviteFriend) -> NSError {
        return NSError(domain: kOCTToxErrorDomain, code: code.rawValue, userInfo: nil)
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

    /// A group in a profile of the test's own, joined with `role`, and its dossier (not loaded yet). The
    /// profile and its rows are made from the app's copies of the objcTox and Realm classes, which the
    /// screens use: this bundle has copies of its own, which do not mix with them (OwnCaptionOrderTests).
    func dossierWorld(privacy: OCTToxGroupPrivacyState,
                      role: OCTToxGroupRole,
                      groups: OCTSubmanagerGroupsMock = OCTSubmanagerGroupsMock()) throws -> DossierWorld {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("air-group-dossier-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        directories.append(directory)
        let realmManagerClass = try appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("profile.realm"),
                                                                encryptionKey: nil))
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        chat.isGroup = true
        chat.groupNumber = DossierWorld.groupNumber
        chat.groupName = "Book club"
        chat.groupPeerCount = 1
        chat.groupChatIdHex = DossierWorld.key
        chat.groupPrivacyState = Int32(privacy.rawValue)
        realmManager.add(chat)
        let realm: RLMRealm = realmManager.realm

        groups.realm = realm
        groups.selfRoles[DossierWorld.groupNumber] = role
        let dossier = AirGroupDossierController(theme: ThemeAppearance.resolvedTheme(),
                                                chat: chat,
                                                submanagerGroups: groups,
                                                submanagerObjects: OCTSubmanagerObjectsMock(realm: realm),
                                                submanagerChats: OCTSubmanagerChatsMock(realm: realm),
                                                submanagerFiles: OCTSubmanagerFilesMock(),
                                                submanagerFriends: OCTSubmanagerFriendsMock())
        return DossierWorld(realmManager: realmManager, chat: chat, dossier: dossier)
    }

    func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }

    /// An entry of a group outside any Realm, made from the app's class (GroupReportTests).
    func groupEntry(peerId: Int32) throws -> OCTMessageAbstract {
        let message = try appClass("OCTMessageAbstract", as: OCTMessageAbstract.Type.self).init()
        message.groupSenderPeerId = peerId
        return message
    }

    func view(in controller: UIViewController, _ identifier: String) -> UIView? {
        return subviews(of: controller.view).first { $0.accessibilityIdentifier == identifier }
    }

    /// Whether any text on the screen, read or spoken, holds the group key.
    func showsKey(_ controller: UIViewController) -> Bool {
        let start = AirKeyFormat.groups(DossierWorld.key).prefix(2).joined(separator: " ")
        return subviews(of: controller.view).contains { view in
            let texts = [(view as? UILabel)?.text, view.accessibilityLabel, view.accessibilityValue]
            return texts.contains { $0?.contains(start) == true || $0?.uppercased().contains(DossierWorld.key.uppercased()) == true }
        }
    }

    /// Runs what the main queue holds now, as the next turn of the main loop would.
    func runMainQueue() {
        let ran = expectation(description: "the main queue ran what it held")
        DispatchQueue.main.async {
            ran.fulfill()
        }
        wait(for: [ran], timeout: 2)
    }

    func pollUntil(_ condition: @escaping () -> Bool, then done: @escaping () -> Void) {
        if condition() {
            done()
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
            self?.pollUntil(condition, then: done)
        }
    }
}

/// objcTox's groups as far as the group settings and the dossier go. What toxcore holds of a group is stored
/// as objcTox stores it (refreshGroupMetadataForChat:), one write after another through the queue of the
/// realm manager; a call that objcTox makes through that queue, made while one of those writes is still
/// running, is written down instead, as it would stop the app there.
private final class StoringGroups: OCTSubmanagerGroupsMock {
    var realmManager: OCTRealmManager?
    /// Who can join, as toxcore holds it.
    var privacyState = OCTToxGroupPrivacyState.public
    private(set) var refreshes = 0
    private(set) var callsInsideAWrite: [String] = []
    private var writing = false

    /// As toxcore's privacy callback stores a change: the state, then (here) another write of the chat
    /// where objcTox adds the system line that tells it.
    func storeAsThePrivacyCallbackDoes(_ state: OCTToxGroupPrivacyState, for chat: OCTChat) {
        privacyState = state
        write { $0.update(state, for: chat) }
        write { $0.update(OCTToxGroupVoiceState.all, for: chat) }
    }

    override func refreshGroupMetadata(for chat: OCTChat!) {
        guard !writing else {
            callsInsideAWrite.append("refreshGroupMetadata")
            return
        }
        refreshes += 1
        write { $0.update(privacyState, for: chat) }
        write { $0.update(OCTToxGroupVoiceState.all, for: chat) }
    }

    override func groupSystemMessageCount(for chat: OCTChat!) -> UInt {
        if writing {
            callsInsideAWrite.append("groupSystemMessageCount")
        }
        return 0
    }

    override func groupPrivacyState(for chat: OCTChat!, error: NSErrorPointer) -> OCTToxGroupPrivacyState {
        return privacyState
    }

    private func write(_ body: (OCTRealmManager) -> Void) {
        guard let realmManager = realmManager else {
            return
        }
        writing = true
        body(realmManager)
        writing = false
    }
}

/// The profile of a dossier test, its group and the dossier.
private struct DossierWorld {
    static let groupNumber: Int32 = 41
    static let key = "7f3a91c05d2e8b4416a9c3e07d5f2b8164e0a3c9d7b25f1e8a4c6d0b3f9e2a71"

    let realmManager: OCTRealmManager
    let chat: OCTChat
    let dossier: AirGroupDossierController
}

/// Rows of plain cells, for what a table does with its rows whatever they hold.
private final class PlainRows: NSObject, UITableViewDataSource {
    static let identifier = "plain"
    let count: Int

    init(count: Int) {
        self.count = count
    }

    func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return count
    }

    func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = tableView.dequeueReusableCell(withIdentifier: PlainRows.identifier, for: indexPath)
        cell.textLabel?.text = "\(indexPath.row)"
        return cell
    }
}
