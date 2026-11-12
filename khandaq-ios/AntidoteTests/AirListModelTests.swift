// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 The Air screen's rules as plain values: which section a row stands in and in what order, what each
 filter and the command line keep, every count and empty state, the batch update between two lists,
 and the small helpers the rows use (reach, network, tone, initials, the request key, fonts, strings).
 */
class AirListModelTests: XCTestCase {
    private var savedLanguage: Any?
    private var savedAppleLanguages: Any?

    override func setUp() {
        super.setUp()
        savedLanguage = UserDefaults.standard.object(forKey: AppLanguage.storageKey)
        savedAppleLanguages = UserDefaults.standard.object(forKey: "AppleLanguages")
    }

    override func tearDown() {
        // Put back the test host's own language, stored and applied.
        UserDefaults.standard.set(savedLanguage, forKey: AppLanguage.storageKey)
        UserDefaults.standard.set(savedAppleLanguages, forKey: "AppleLanguages")
        AppLanguage.applyAtLaunch()
        super.tearDown()
    }

    // MARK: - Sections and order

    func testSectionsStandInTheirOrderAndRowsSortByActivity() {
        let entries = [
            entry("away-old", reach: .away(secondsAgo: 7200), activity: 10),
            entry("live-new", reach: .direct, activity: 300),
            entry("relay", reach: .relay, activity: 200),
            entry("group-live", kind: .group, reach: .group(online: 2), activity: 100),
            entry("group-empty", kind: .group, reach: .group(online: 0), activity: 50),
            entry("linking", reach: .connecting, activity: 20),
            entry("pending", reach: .pending),
            saved(),
        ]
        let requests = [request("older", date: 1), request("newer", date: 2)]

        let snapshot = AirListBuilder.build(entries: entries, requests: requests, filter: .all, query: "")

        XCTAssertEqual(kinds(snapshot), [.requests, .live, .away, .saved])
        XCTAssertEqual(ids(snapshot, .requests), ["newer", "older"])
        XCTAssertEqual(ids(snapshot, .live), ["live-new", "relay", "group-live"])
        XCTAssertEqual(ids(snapshot, .away), ["group-empty", "linking", "away-old", "pending"])
        XCTAssertEqual(ids(snapshot, .saved), ["saved"])
        XCTAssertNil(snapshot.emptyState)
        XCTAssertEqual(snapshot.itemCount, 10)
    }

    func testContactsWithoutAConversationComeLastByName() {
        let entries = [
            entry("k2", title: "zed", kind: .contact),
            entry("k1", title: "Anna", kind: .contact),
            entry("chat", title: "Bob", activity: 5),
            entry("k3", title: "anna", kind: .contact),
        ]

        let snapshot = AirListBuilder.build(entries: entries, requests: [], filter: .all, query: "")

        XCTAssertEqual(ids(snapshot, .away), ["chat", "k1", "k3", "k2"])
    }

    func testSnapshotLookupsOutsideTheListReturnNothing() {
        let snapshot = AirListBuilder.build(entries: [entry("a")], requests: [], filter: .all, query: "")

        XCTAssertEqual(snapshot.entry(at: IndexPath(row: 0, section: 0))?.id, "a")
        XCTAssertNil(snapshot.item(at: IndexPath(row: 1, section: 0)))
        XCTAssertNil(snapshot.item(at: IndexPath(row: 0, section: 1)))
        XCTAssertNil(snapshot.item(at: IndexPath(row: -1, section: 0)))
    }

    // MARK: - Filters

    func testRequestsFilterKeepsCardsAndUnansweredContacts() {
        let entries = [entry("pending", reach: .pending), entry("friend", reach: .direct), saved()]

        let snapshot = AirListBuilder.build(entries: entries, requests: [request("r", date: 1)], filter: .requests, query: "")

        XCTAssertEqual(kinds(snapshot), [.requests, .away])
        XCTAssertEqual(ids(snapshot, .away), ["pending"])
        XCTAssertNil(snapshot.emptyState)
    }

    func testTransitFilterKeepsRowsWithWaitingMessages() {
        let entries = [entry("waiting", transit: 2), entry("clear"), saved()]

        let snapshot = AirListBuilder.build(entries: entries, requests: [request("r", date: 1)], filter: .transit, query: "")

        XCTAssertEqual(kinds(snapshot), [.away])
        XCTAssertEqual(ids(snapshot, .away), ["waiting"])
        XCTAssertNil(snapshot.emptyState)
    }

    func testUndeliveredMessagesWaitInTransitOnlyWhileThePersonIsAway() {
        XCTAssertEqual(AirReach.away(secondsAgo: 60).messagesInTransit(undelivered: 3), 3)
        XCTAssertEqual(AirReach.connecting.messagesInTransit(undelivered: 2), 2)
        XCTAssertEqual(AirReach.pending.messagesInTransit(undelivered: 1), 1)
        XCTAssertEqual(AirReach.direct.messagesInTransit(undelivered: 3), 0)
        XCTAssertEqual(AirReach.relay.messagesInTransit(undelivered: 3), 0)
    }

    func testRowMarkOfALastTextFollowsTheConversation() {
        XCTAssertEqual(AirDelivery.ofOutgoingText(isDelivered: false, messageId: 7, personOnline: false), .transit)
        XCTAssertEqual(AirDelivery.ofOutgoingText(isDelivered: false, messageId: -1, personOnline: true), .transit)
        XCTAssertEqual(AirDelivery.ofOutgoingText(isDelivered: false, messageId: 7, personOnline: true), .sent)
        XCTAssertEqual(AirDelivery.ofOutgoingText(isDelivered: true, messageId: 7, personOnline: false), .delivered)
        XCTAssertEqual(AirDelivery.ofOutgoingText(isDelivered: true, messageId: 7, personOnline: true), .delivered)
    }

    func testFavoritesFilterKeepsStarredRowsAndSavedMessages() {
        let entries = [entry("star", reach: .direct, favorite: true), entry("plain", reach: .direct), saved()]

        let snapshot = AirListBuilder.build(entries: entries, requests: [], filter: .favorites, query: "")

        XCTAssertEqual(kinds(snapshot), [.live, .saved])
        XCTAssertEqual(ids(snapshot, .live), ["star"])
        XCTAssertNil(snapshot.emptyState)
    }

    // MARK: - Searching

    func testQueryMatchesTitlesIgnoringCaseAndMarksAndHidesRequests() {
        let entries = [entry("a", title: "Zo\u{00EB} Adams", reach: .direct), entry("b", title: "Bob"), saved()]
        let hit = AirMessageHit(id: "m1", chatId: "b", title: "Bob", snippet: "zoe said hi", timeText: "")

        let snapshot = AirListBuilder.build(entries: entries,
                                            requests: [request("r", date: 1)],
                                            messageHits: [hit],
                                            filter: .all,
                                            query: "  zoe ")

        XCTAssertEqual(kinds(snapshot), [.live, .messages])
        XCTAssertEqual(ids(snapshot, .live), ["a"])
        XCTAssertEqual(ids(snapshot, .messages), ["m1"])
        XCTAssertNil(snapshot.emptyState)
    }

    func testMessageHitsAreIgnoredWithoutAQuery() {
        let hit = AirMessageHit(id: "m1", chatId: "b", title: "Bob", snippet: "", timeText: "")

        let snapshot = AirListBuilder.build(entries: [entry("b", title: "Bob")], requests: [], messageHits: [hit], filter: .all, query: " ")

        XCTAssertEqual(kinds(snapshot), [.away])
    }

    // MARK: - Counts and empty states

    func testCountsIgnoreTheFilterAndTheQuery() {
        let entries = [entry("a", transit: 2), entry("b", transit: 1), saved()]
        let requests = [request("r1", date: 1), request("r2", date: 2)]

        for filter in AirFilter.swipeOrder {
            for query in ["", "nothing like it"] {
                let snapshot = AirListBuilder.build(entries: entries, requests: requests, filter: filter, query: query)
                XCTAssertEqual(snapshot.counts, AirCounts(requests: 2, transit: 3), "\(filter) \(query)")
            }
        }
    }

    func testOnlySavedMessagesMeansNothingYet() {
        let snapshot = AirListBuilder.build(entries: [saved()], requests: [], filter: .all, query: "")

        XCTAssertEqual(snapshot.emptyState, .nothingYet)
        XCTAssertEqual(kinds(snapshot), [.saved])
    }

    func testARequestAloneIsNotNothing() {
        let snapshot = AirListBuilder.build(entries: [saved()], requests: [request("r", date: 1)], filter: .all, query: "")

        XCTAssertNil(snapshot.emptyState)
    }

    func testEmptyStateForEachFilterAndForTheQuery() {
        let entries = [entry("a"), saved()]

        XCTAssertEqual(AirListBuilder.build(entries: entries, requests: [], filter: .requests, query: "").emptyState, .noRequests)
        XCTAssertEqual(AirListBuilder.build(entries: entries, requests: [], filter: .transit, query: "").emptyState, .noTransit)
        XCTAssertEqual(AirListBuilder.build(entries: entries, requests: [], filter: .favorites, query: "").emptyState, .noFavorites)
        XCTAssertEqual(AirListBuilder.build(entries: entries, requests: [], filter: .all, query: "qqq").emptyState, .noMatch)
        XCTAssertNil(AirListBuilder.build(entries: entries, requests: [], filter: .all, query: "").emptyState)
    }

    // MARK: - Batch updates

    func testAPersonComingOnlineIsTheOneRowThatMoves() {
        let before = [entry("a", reach: .direct, activity: 30), entry("b", activity: 20), entry("c", activity: 10)]
        let after = [entry("a", reach: .direct, activity: 30), entry("b", activity: 20), entry("c", reach: .direct, activity: 10)]
        let old = AirListBuilder.build(entries: before, requests: [], filter: .all, query: "")
        let new = AirListBuilder.build(entries: after, requests: [], filter: .all, query: "")

        let changes = AirListDiff.changes(from: old, to: new)

        XCTAssertEqual(changes?.movedRows, [AirRowMove(from: IndexPath(row: 1, section: 1), to: IndexPath(row: 1, section: 0))])
        XCTAssertEqual(changes?.deletedRows, [])
        XCTAssertEqual(changes?.insertedRows, [])
        XCTAssertEqual(changes?.deletedSections, IndexSet())
        XCTAssertEqual(changes?.insertedSections, IndexSet())
    }

    func testUnchangedListsNeedNoUpdate() {
        let entries = [entry("a", reach: .direct, activity: 3), entry("b", activity: 2), saved()]
        let snapshot = AirListBuilder.build(entries: entries, requests: [request("r", date: 1)], filter: .all, query: "")

        XCTAssertEqual(AirListDiff.changes(from: snapshot, to: snapshot)?.isEmpty, true)
    }

    func testARowCannotMoveIntoASectionThatAppears() {
        let before = [entry("a", activity: 2), entry("b", activity: 1)]
        let after = [entry("a", reach: .direct, activity: 2), entry("b", activity: 1)]
        let old = AirListBuilder.build(entries: before, requests: [], filter: .all, query: "")
        let new = AirListBuilder.build(entries: after, requests: [], filter: .all, query: "")

        let changes = AirListDiff.changes(from: old, to: new)

        XCTAssertEqual(changes?.insertedSections, IndexSet(integer: 0))
        XCTAssertEqual(changes?.deletedRows, [IndexPath(row: 0, section: 0)])
        XCTAssertEqual(changes?.insertedRows, [])
        XCTAssertEqual(changes?.movedRows, [])
    }

    func testTooManyChangesFallBackToAReload() {
        let before = (0..<50).map { entry("p\($0)", activity: TimeInterval($0)) }
        let after = (50..<100).map { entry("p\($0)", activity: TimeInterval($0)) }
        let old = AirListBuilder.build(entries: before, requests: [], filter: .all, query: "")
        let new = AirListBuilder.build(entries: after, requests: [], filter: .all, query: "")

        XCTAssertNil(AirListDiff.changes(from: old, to: new))
    }

    func testLongestIncreasingRun() {
        XCTAssertEqual(AirListDiff.longestIncreasingRun([]), [])
        XCTAssertEqual(AirListDiff.longestIncreasingRun([0, 1, 2]), [0, 1, 2])
        XCTAssertEqual(AirListDiff.longestIncreasingRun([2, 1, 0]).count, 1)
        XCTAssertEqual(AirListDiff.longestIncreasingRun([0, 3, 1, 2, 4]), [0, 2, 3, 4])
    }

    /// Random lists and random changes between them. Every update that comes back is replayed the way
    /// UITableView applies one (rows it does not mention keep their order and fill the free places),
    /// and the replay has to give exactly the new list.
    func testRandomUpdatesReplayIntoTheNewList() {
        var generator = SeededGenerator(seed: 20261111)
        var animated = 0
        let rounds = 300

        for _ in 0..<rounds {
            let before = (0..<Int.random(in: 0..<14, using: &generator)).map { index in
                entry("p\(index)", reach: randomReach(&generator), activity: randomActivity(&generator))
            }
            var after: [AirEntry] = []
            for old in before {
                switch Int.random(in: 0..<6, using: &generator) {
                    case 0:
                        continue
                    case 1:
                        after.append(entry(old.id, reach: randomReach(&generator), activity: old.activity))
                    case 2:
                        after.append(entry(old.id, reach: old.reach, activity: randomActivity(&generator)))
                    default:
                        after.append(old)
                }
            }
            for index in 0..<Int.random(in: 0..<3, using: &generator) {
                after.append(entry("n\(index)", reach: randomReach(&generator), activity: randomActivity(&generator)))
            }
            let oldRequests = (0..<Int.random(in: 0..<3, using: &generator)).map { request("r\($0)", date: TimeInterval($0)) }
            let newRequests = Bool.random(using: &generator) ? oldRequests : Array(oldRequests.dropFirst())
            let extra = Bool.random(using: &generator) ? [saved()] : []

            let old = AirListBuilder.build(entries: before + extra, requests: oldRequests, filter: .all, query: "")
            let new = AirListBuilder.build(entries: after + extra, requests: newRequests, filter: .all, query: "")

            guard let changes = AirListDiff.changes(from: old, to: new) else {
                continue
            }
            animated += 1
            XCTAssertEqual(replay(changes, from: old, to: new), new.sections.map { $0.items.map { $0.id } })
        }

        // Lists this small stay far below the animation limit, so almost every round must animate.
        XCTAssertGreaterThan(animated, rounds * 9 / 10)
    }

    // MARK: - Command line

    func testCommandLineTellsASearchFromAnID() {
        let address = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855D34D34D37CB5"
        let publicKey = String(address.prefix(64))
        let expected = normalizeAddressString(address)
        XCTAssertNotNil(expected)

        XCTAssertEqual(AirCommand.parse(""), .empty)
        XCTAssertEqual(AirCommand.parse("  \n "), .empty)
        XCTAssertEqual(AirCommand.parse("  Alice  "), .query("Alice"))
        // Hex letters in ordinary words are still a search.
        XCTAssertEqual(AirCommand.parse("cafe bead"), .query("cafe bead"))
        // A bare key is not a whole ID: the row "New contact with this ID" says what is missing.
        XCTAssertEqual(AirCommand.parse(publicKey), .partialAddress(publicKey))
        // A group's key says it is one only with its prefix; bare, the line offers both rows.
        XCTAssertEqual(AirCommand.parse("khandaq://group/" + publicKey.lowercased()), .groupKey(publicKey))
        XCTAssertEqual(AirCommand.parse(" KHANDAQ:GROUP:" + publicKey + " "), .groupKey(publicKey))
        XCTAssertNotEqual(AirCommand.parse("KHANDAQ:GROUP:" + String(publicKey.prefix(60))), .groupKey(String(publicKey.prefix(60))))
        XCTAssertEqual(AirCommand.parse(String(address.prefix(11))), .query(String(address.prefix(11))))
        // Twelve hex letters without a digit are still a name.
        XCTAssertEqual(AirCommand.parse("Abba Deedcafe"), .query("Abba Deedcafe"))
        XCTAssertEqual(AirCommand.parse(address + "0"), .query(address + "0"))
        // A typo in a whole ID is not repaired here: New contact shows that the check does not match.
        let typo = String(address.prefix(75)) + (address.hasSuffix("5") ? "6" : "5")
        XCTAssertEqual(AirCommand.parse(typo), .contactAddress(typo))

        let pasted = String(address.prefix(30)).lowercased() + " \n" + String(address.dropFirst(30))
        XCTAssertEqual(AirCommand.parse(pasted), expected.map { AirCommand.contactAddress($0) })
        XCTAssertEqual(AirCommand.parse("tox://" + address), expected.map { AirCommand.contactAddress($0) })

        if case .contactAddress(let parsed) = AirCommand.parse(address) {
            XCTAssertEqual(parsed.prefix(72), address.prefix(72))
            XCTAssertEqual(parsed.count, 76)
        }
        else {
            XCTFail("a whole ID must open New contact")
        }
    }

    // MARK: - Reach and network

    func testFriendReach() {
        let now: TimeInterval = 1_000_000

        XCTAssertEqual(AirReach.friend(connection: .UDP, lastSeenOnlineInterval: 0, now: now), .direct)
        XCTAssertEqual(AirReach.friend(connection: .TCP, lastSeenOnlineInterval: 0, now: now), .relay)
        XCTAssertEqual(AirReach.friend(connection: .none, lastSeenOnlineInterval: 0, now: now), .pending)
        XCTAssertEqual(AirReach.friend(connection: .none, lastSeenOnlineInterval: now - 90, now: now), .away(secondsAgo: 90))
        // A clock that runs behind never gives a negative age.
        XCTAssertEqual(AirReach.friend(connection: .none, lastSeenOnlineInterval: now + 30, now: now), .away(secondsAgo: 0))
    }

    func testFriendSeenTodayIsStillLinkingRightAfterWeComeOnline() {
        let now: TimeInterval = 1_000_000

        XCTAssertEqual(AirReach.friend(connection: .none, lastSeenOnlineInterval: now - 90, now: now, selfLinkSettling: true), .connecting)
        XCTAssertEqual(AirReach.friend(connection: .none, lastSeenOnlineInterval: now - 86400, now: now, selfLinkSettling: true),
                       .away(secondsAgo: 86400))
        XCTAssertEqual(AirReach.friend(connection: .none, lastSeenOnlineInterval: 0, now: now, selfLinkSettling: true), .pending)
        XCTAssertEqual(AirReach.friend(connection: .UDP, lastSeenOnlineInterval: now - 90, now: now, selfLinkSettling: true), .direct)
    }

    func testOnlyAWorkingLinkCountsAsLive() {
        XCTAssertTrue(AirReach.direct.isLive)
        XCTAssertTrue(AirReach.relay.isLive)
        XCTAssertTrue(AirReach.group(online: 1).isLive)
        XCTAssertFalse(AirReach.group(online: 0).isLive)
        XCTAssertFalse(AirReach.away(secondsAgo: 1).isLive)
        XCTAssertFalse(AirReach.pending.isLive)
        XCTAssertFalse(AirReach.connecting.isLive)
        XCTAssertFalse(AirReach.local.isLive)
    }

    func testNetworkStateFromTheToxLinkAndTheDevice() {
        XCTAssertEqual(AirNetworkState(connection: .UDP, networkReachable: false), .direct)
        XCTAssertEqual(AirNetworkState(connection: .TCP, networkReachable: true), .relay)
        XCTAssertEqual(AirNetworkState(connection: .none, networkReachable: true), .connecting)
        XCTAssertEqual(AirNetworkState(connection: .none, networkReachable: false), .offline)
    }

    func testReachWordsInRussian() {
        use("ru")

        XCTAssertEqual(AirRowCell.reachText(.direct), "в сети")
        XCTAssertEqual(AirRowCell.reachText(.relay), "в сети, узел")
        XCTAssertEqual(AirRowCell.reachText(.pending), "ждёт ответа")
        XCTAssertEqual(AirRowCell.reachText(.connecting), "подключаемся")
        XCTAssertEqual(AirRowCell.reachText(.group(online: 3)), "3 в сети")
        XCTAssertEqual(AirRowCell.reachText(.group(online: 0)), "никого")
        XCTAssertEqual(AirRowCell.reachText(.local), "")
        XCTAssertEqual(AirRowCell.reachText(.away(secondsAgo: 59)), "только что")
        XCTAssertEqual(AirRowCell.reachText(.away(secondsAgo: 60)), "1 мин назад")
        XCTAssertEqual(AirRowCell.reachText(.away(secondsAgo: 3599)), "59 мин назад")
        XCTAssertEqual(AirRowCell.reachText(.away(secondsAgo: 3600)), "1 ч назад")
        XCTAssertEqual(AirRowCell.reachText(.away(secondsAgo: 86399)), "23 ч назад")
        XCTAssertEqual(AirRowCell.reachText(.away(secondsAgo: 86400)), "1 дн назад")
        XCTAssertEqual(AirRowCell.reachText(.away(secondsAgo: 86400 * 7)), "давно")
    }

    // MARK: - Tiles and cards

    func testToneIsTheSameOnEveryLaunchAndEveryClient() {
        // FNV-1a over the uppercased UTF-8 bytes, then mod 6: Android and desktop use the same vectors.
        XCTAssertEqual(AirTone.index(for: ""), 1)
        XCTAssertEqual(AirTone.index(for: "A"), 0)
        XCTAssertEqual(AirTone.index(for: "abc"), 3)
        XCTAssertEqual(AirTone.index(for: "ABC"), 3)
        XCTAssertEqual(AirTone.index(for: "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855"), 0)
        XCTAssertEqual(AirTone.index(for: "group-chat-1"), 5)
    }

    func testTonesSpreadOverAllSix() {
        var seen = Set<Int>()
        for index in 0..<120 {
            seen.insert(AirTone.index(for: "key-\(index)"))
        }

        XCTAssertEqual(AirTone.colors.count, 6)
        XCTAssertEqual(seen, Set(0..<6))
        XCTAssertTrue(AirTone.color(at: -1) === AirTone.colors[5])
        XCTAssertTrue(AirTone.color(at: 6) === AirTone.colors[0])
    }

    func testInitialsTakeTheFirstLettersOfTwoWords() {
        XCTAssertEqual(AirInitials.from("anna maria lopez"), "A\u{200C}M")
        XCTAssertEqual(AirInitials.from("  Bob "), "B")
        XCTAssertEqual(AirInitials.from(""), "")
    }

    func testRequestKeyShowsTheFirstTwelveDigitsInFours() {
        XCTAssertEqual(AirRequestCell.groupedKey("56a1ade4b65b86bcd51cc73e"), "56A1 ADE4 B65B")
        XCTAssertEqual(AirRequestCell.groupedKey("56A1AD"), "56A1 AD")
        XCTAssertEqual(AirRequestCell.groupedKey(""), "")
    }

    /// A long name gives way to the reach words, which stay inside the row: the audit walk of 11.11
    /// found the Russian relay words running off the right edge of a 402 pt phone.
    func testLongNameGivesWayToTheReachWords() {
        use("ru")
        let long = entry("long",
                         title: "app_store_screenshot_friend_female_1",
                         reach: .relay,
                         activity: 1,
                         transit: 2,
                         unread: 3)

        for width: CGFloat in [320, 402] {
            let cell = AirRowCell(style: .default, reuseIdentifier: AirRowCell.reuseIdentifier)
            cell.configure(with: long, photo: nil)
            cell.frame = CGRect(x: 0, y: 0, width: width, height: KQSize.rowPhone)
            cell.layoutIfNeeded()

            let limit = width - KQSpace.x16 + 0.5
            for view in descendants(of: cell.contentView) where !view.isHidden && view.bounds.width > 0 {
                let frame = view.convert(view.bounds, to: cell)
                XCTAssertLessThanOrEqual(frame.maxX, limit,
                                         "\(type(of: view)) ends at \(frame.maxX) in a \(width) pt row")
            }
            let reach = descendants(of: cell.contentView).compactMap { $0 as? AirReachView }.first
            XCTAssertNotNil(reach)
            XCTAssertGreaterThan(reach?.bounds.width ?? 0, 0, "the reach words were squeezed out at \(width) pt")
        }
    }

    // MARK: - Fonts and strings

    func testEveryBundledFaceLoads() {
        for file in KQFont.bundledFiles {
            XCTAssertNotNil(Bundle.main.url(forResource: file, withExtension: nil), file)
        }
        for family in ["text", "mono", "arabic"] {
            for weight in [400, 500, 600] {
                guard let name = KQFont.postScriptName(family: family, weight: weight) else {
                    continue
                }
                XCTAssertNotNil(UIFont(name: name, size: 12), name)
            }
        }
        XCTAssertNil(KQFont.postScriptName(family: "mono", weight: 600))
    }

    func testTextRolesUsePlexAndArabicUsesPlexArabic() {
        use("en")
        for role in KQFontRole.allCases {
            XCTAssertTrue(KQFont.font(role).fontName.hasPrefix("IBMPlex"), "\(role)")
        }
        XCTAssertEqual(KQFont.font(.body, weight: 600).fontName, "IBMPlexSans-SmBld")
        XCTAssertEqual(KQFont.font(.mono).fontName, "IBMPlexMono-Medm")

        use("ar")
        XCTAssertEqual(KQFont.font(.body).fontName, "IBMPlexSansArabic")
        XCTAssertEqual(KQFont.font(.mono).fontName, "IBMPlexMono-Medm")
    }

    func testEveryAirStringExistsInEveryLanguageWithTheSameArguments() {
        let english = airStrings("en")
        XCTAssertGreaterThan(english.count, 40)

        for language in AppLanguage.supportedCodes where language != "en" {
            let other = airStrings(language)
            XCTAssertEqual(Set(other.keys), Set(english.keys), language)
            for (key, format) in english {
                let translated = other[key] ?? ""
                XCTAssertFalse(translated.isEmpty, "\(language) \(key)")
                XCTAssertEqual(formatArguments(translated), formatArguments(format), "\(language) \(key)")
            }
        }
    }
}

// MARK: - Fixtures

private extension AirListModelTests {
    func entry(_ id: String,
               title: String? = nil,
               kind: AirEntry.Kind = .direct,
               reach: AirReach = .away(secondsAgo: 3600),
               activity: TimeInterval = 0,
               transit: Int = 0,
               unread: Int = 0,
               favorite: Bool = false) -> AirEntry {
        return AirEntry(id: id,
                        kind: kind,
                        title: title ?? id,
                        reach: reach,
                        preview: "",
                        previewStyle: .message,
                        lastDelivery: nil,
                        timeText: "",
                        activity: activity,
                        transitCount: transit,
                        unreadCount: unread,
                        isFavorite: favorite,
                        avatarData: nil,
                        tone: AirTone.index(for: id))
    }

    func saved() -> AirEntry {
        return entry("saved", title: "Saved", kind: .saved, reach: .local, activity: 1)
    }

    func descendants(of view: UIView) -> [UIView] {
        return view.subviews + view.subviews.flatMap { descendants(of: $0) }
    }

    func request(_ id: String, date: TimeInterval) -> AirRequest {
        return AirRequest(id: id, publicKey: String(repeating: "A", count: 64), message: "", timeText: "", date: date)
    }

    func kinds(_ snapshot: AirSnapshot) -> [AirSectionKind] {
        return snapshot.sections.map { $0.kind }
    }

    func ids(_ snapshot: AirSnapshot, _ kind: AirSectionKind) -> [String] {
        guard let section = snapshot.sections.first(where: { $0.kind == kind }) else {
            return []
        }
        return section.items.map { item in
            switch item {
                case .request(let request):
                    return request.id
                case .entry(let entry):
                    return entry.id
                case .message(let hit):
                    return hit.id
            }
        }
    }

    func randomReach(_ generator: inout SeededGenerator) -> AirReach {
        switch Int.random(in: 0..<5, using: &generator) {
            case 0:
                return .direct
            case 1:
                return .relay
            case 2:
                return .pending
            case 3:
                return .connecting
            default:
                return .away(secondsAgo: TimeInterval(Int.random(in: 0..<100_000, using: &generator)))
        }
    }

    func randomActivity(_ generator: inout SeededGenerator) -> TimeInterval {
        // Few distinct values, so equal activity (and the tie-break by name) comes up often.
        return TimeInterval(Int.random(in: 0..<6, using: &generator))
    }

    /// The new list as UITableView builds it from the old one and the update: removed sections and
    /// rows go, rows moved or inserted take their new places, and every row the update does not
    /// mention keeps its order and fills the places left, section by section.
    func replay(_ changes: AirListChanges, from old: AirSnapshot, to new: AirSnapshot) -> [[String]]? {
        var survivors: [AirSectionKind: [String]] = [:]
        for (index, section) in old.sections.enumerated() where !changes.deletedSections.contains(index) {
            let gone = Set(changes.deletedRows.filter { $0.section == index }.map { $0.row }
                + changes.movedRows.filter { $0.from.section == index }.map { $0.from.row })
            survivors[section.kind] = section.items.enumerated().filter { !gone.contains($0.offset) }.map { $0.element.id }
        }

        var result: [[String]] = []
        for (index, section) in new.sections.enumerated() {
            if changes.insertedSections.contains(index) {
                result.append(section.items.map { $0.id })
                continue
            }
            var slots = [String?](repeating: nil, count: section.items.count)
            for path in changes.insertedRows where path.section == index {
                slots[path.row] = section.items[path.row].id
            }
            for move in changes.movedRows where move.to.section == index {
                slots[move.to.row] = old.sections[move.from.section].items[move.from.row].id
            }
            guard var rest = survivors[section.kind] else {
                return nil
            }
            for slot in slots.indices where slots[slot] == nil {
                guard !rest.isEmpty else {
                    return nil
                }
                slots[slot] = rest.removeFirst()
            }
            guard rest.isEmpty else {
                return nil
            }
            result.append(slots.compactMap { $0 })
        }
        return result
    }

    func use(_ language: String) {
        AppLanguage.set(language)
        XCTAssertEqual(AppLanguage.current, language)
    }

    func airStrings(_ language: String) -> [String: String] {
        guard let path = Bundle.main.path(forResource: "Localizable", ofType: "strings", inDirectory: nil, forLocalization: language),
              let table = NSDictionary(contentsOfFile: path) as? [String: String] else {
            XCTFail("no Localizable.strings for \(language)")
            return [:]
        }
        return table.filter { $0.key.hasPrefix("air_") }
    }

    func formatArguments(_ format: String) -> [String] {
        guard let pattern = try? NSRegularExpression(pattern: "%(?:[0-9]+\\$)?[@dDuUxXoOfeEgGcCsSpaAF]") else {
            XCTFail("format pattern")
            return []
        }
        let text = format as NSString
        return pattern.matches(in: format, range: NSRange(location: 0, length: text.length))
            .map { text.substring(with: $0.range) }
            .sorted()
    }
}

/// SplitMix64: the same random lists on every run.
private struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state &+= 0x9E3779B97F4A7C15
        var value = state
        value = (value ^ (value >> 30)) &* 0xBF58476D1CE4E5B9
        value = (value ^ (value >> 27)) &* 0x94D049BB133111EB
        return value ^ (value >> 31)
    }
}
