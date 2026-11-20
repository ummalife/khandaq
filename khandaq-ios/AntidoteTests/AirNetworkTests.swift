// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Our own node (redesign stage 3, E4) and the in-app band (E22): the offline watchdog asks for a
 reconnect every 15 seconds while the node is offline on a phone with a network, the Network screen
 says what each state means, the hint under the field speaks for our node, the line under the name is
 a target of its own, and the band's window belongs to the app's scene and shows only with a band. An
 invitation to a group is a notification of its own kind. The band of no network (0.2.57) keeps its
 times: nothing for a drop under 3 s, then No network, Connecting, Back online and gone, and Connecting
 gives up after 30 s.
 */
class AirNetworkTests: XCTestCase {
    private final class Node {
        var online = false
        var reachable = true
        var pending = false
        var requests = 0
    }

    private func watchdog(_ node: Node) -> OfflineReconnectWatchdog {
        return OfflineReconnectWatchdog(isOnline: { node.online },
                                        isReachable: { node.reachable },
                                        isReconnectPending: { node.pending },
                                        requestReconnect: { node.requests += 1 })
    }

    func testTheWatchdogAsksForAReconnectEveryFifteenSecondsWhileOfflineWithANetwork() {
        let node = Node()
        let dog = watchdog(node)
        let start = Date(timeIntervalSince1970: 1_000)

        XCTAssertFalse(dog.check(now: start), "the first look only starts the clock")
        XCTAssertFalse(dog.check(now: start.addingTimeInterval(14)))
        XCTAssertTrue(dog.check(now: start.addingTimeInterval(15)))
        XCTAssertEqual(node.requests, 1)

        // The clock starts again after each request.
        XCTAssertFalse(dog.check(now: start.addingTimeInterval(20)))
        XCTAssertTrue(dog.check(now: start.addingTimeInterval(30)))
        XCTAssertEqual(node.requests, 2)
    }

    func testTheWatchdogLeavesAPendingReconnectAndAnOnlineNodeAlone() {
        let node = Node()
        let dog = watchdog(node)
        let start = Date(timeIntervalSince1970: 1_000)
        dog.check(now: start)

        node.pending = true
        XCTAssertFalse(dog.check(now: start.addingTimeInterval(16)), "a pending reconnect would only be pushed back")
        node.pending = false
        XCTAssertTrue(dog.check(now: start.addingTimeInterval(17)))

        node.online = true
        XCTAssertFalse(dog.check(now: start.addingTimeInterval(60)))
        XCTAssertNil(dog.offlineSince, "an online node keeps no offline clock")
        XCTAssertEqual(node.requests, 1)
    }

    func testWithoutANetworkTheWatchdogWaitsForItsReturn() {
        let node = Node()
        node.reachable = false
        let dog = watchdog(node)
        let start = Date(timeIntervalSince1970: 1_000)
        dog.check(now: start)
        XCTAssertFalse(dog.check(now: start.addingTimeInterval(60)))
        XCTAssertEqual(node.requests, 0)

        // The network is back but the node is not: the clock starts then.
        node.reachable = true
        XCTAssertFalse(dog.check(now: start.addingTimeInterval(61)))
        XCTAssertTrue(dog.check(now: start.addingTimeInterval(76)))
    }

    func testTheNetworkScreenSaysWhatEachStateMeans() {
        let direct = AirNetworkReport(state: .direct)
        XCTAssertTrue(direct.isLive)
        XCTAssertFalse(direct.isAlarm)
        XCTAssertFalse(direct.offersReconnect)
        XCTAssertEqual(direct.link, String(localized: "air_network_link_direct"))

        let relay = AirNetworkReport(state: .relay)
        XCTAssertTrue(relay.isLive)
        XCTAssertEqual(relay.link, String(localized: "air_network_link_relay"))

        let connecting = AirNetworkReport(state: .connecting)
        XCTAssertFalse(connecting.isLive)
        XCTAssertFalse(connecting.isAlarm, "connecting is not an alarm")
        XCTAssertTrue(connecting.offersReconnect)
        XCTAssertEqual(connecting.internet, String(localized: "air_network_internet_yes"))

        let offline = AirNetworkReport(state: .offline)
        XCTAssertTrue(offline.isAlarm, "only no network at all is red")
        XCTAssertFalse(offline.offersReconnect, "without internet a reconnect cannot help")
        XCTAssertEqual(offline.internet, String(localized: "air_network_internet_no"))

        for report in [direct, relay, connecting, offline] {
            for words in [report.title, report.meaning] {
                XCTAssertFalse(words.isEmpty)
                XCTAssertNil(words.range(of: "[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+", options: .regularExpression), "an address in \(words)")
                XCTAssertFalse(words.lowercased().contains("probe"), "a probe code in \(words)")
            }
        }
    }

    func testTheHintUnderTheFieldSpeaksForOurNodeOnlyWhileItIsNotConnected() {
        XCTAssertNil(AirComposeHint.ownNode(.direct))
        XCTAssertNil(AirComposeHint.ownNode(.relay))
        XCTAssertEqual(AirComposeHint.ownNode(.connecting),
                       AirComposeHint(text: String(localized: "air_compose_hint_self_connecting"), dot: .transit))
        XCTAssertEqual(AirComposeHint.ownNode(.offline),
                       AirComposeHint(text: String(localized: "air_compose_hint_self_offline"), dot: .transit))
    }

    func testTheNodeStateIsAnnouncedOnlyWhenItChanges() {
        let before = AirSelfNetwork.state
        defer {
            AirSelfNetwork.update(before)
        }
        let other: AirNetworkState = before == .offline ? .relay : .offline
        var posts = 0
        let observer = NotificationCenter.default.addObserver(forName: AirSelfNetwork.didChange, object: nil, queue: nil) { _ in
            posts += 1
        }
        defer {
            NotificationCenter.default.removeObserver(observer)
        }
        AirSelfNetwork.update(other)
        AirSelfNetwork.update(other)
        XCTAssertEqual(posts, 1)
        XCTAssertEqual(AirSelfNetwork.state, other)
    }

    func testTheLineUnderTheNameIsATargetOfItsOwnAFingerHigh() {
        let block = AirSelfView()
        block.configure(name: "Samir Valiev", photo: nil)
        block.setNetwork(.relay, animated: false)
        // In the app's scene, as on the phone: a window of no scene has no place on the screen. The block
        // stands off the window's corner, so a frame left in the view's own coordinates would show.
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let host = scene.map { UIWindow(windowScene: $0) } ?? UIWindow()
        host.frame = CGRect(x: 0, y: 0, width: 300, height: 200)
        host.isHidden = false
        defer {
            host.isHidden = true
        }
        host.addSubview(block)
        block.frame = CGRect(x: 10, y: 60, width: 280, height: 67)
        block.setNeedsLayout()
        block.layoutIfNeeded()

        let elements = block.accessibilityElements as? [NSObject] ?? []
        let identifiers = elements.map { element -> String? in
            if let view = element as? UIView {
                return view.accessibilityIdentifier
            }
            return (element as? UIAccessibilityElement)?.accessibilityIdentifier
        }
        XCTAssertEqual(identifiers, ["air.you", "air.you.network"])
        let you = elements[0]
        let line = elements[1] as? UIControl
        XCTAssertEqual(you.accessibilityLabel, String(localized: "air_you") + ", Samir Valiev")
        XCTAssertEqual(line?.accessibilityLabel, String(localized: "air_net_relay"))

        guard let target = line else {
            return XCTFail("the line is not a control")
        }
        XCTAssertGreaterThanOrEqual(target.frame.height, KQSize.targetIos - 0.5, "the line is not a finger high")
        let lineMiddle = CGPoint(x: target.frame.midX, y: target.frame.midY)
        XCTAssertTrue(block.hitTest(lineMiddle, with: nil) === target, "a tap on the line does not reach it")
        let tileMiddle = CGPoint(x: KQSize.tilePhone / 2, y: block.bounds.midY)
        XCTAssertTrue(block.hitTest(tileMiddle, with: nil) === block, "a tap on the tile does not open You")

        // Voice Control and the UI tests tap "You" in the middle of the tile, away from the line.
        let onScreen = { (view: UIView) -> CGRect in
            host.convert(view.convert(view.bounds, to: nil), to: host.screen.coordinateSpace)
        }
        let tileFrame = onScreen(block.tileView)
        let lineFrame = onScreen(target)
        XCTAssertEqual(tileFrame.width, KQSize.tilePhone, accuracy: 0.5, "the tile has no place on the screen")
        XCTAssertFalse(lineFrame.isEmpty, "the line has no place on the screen")
        XCTAssertFalse(lineFrame.insetBy(dx: 0.5, dy: 0.5).intersects(tileFrame), "the line's target covers the tile")
        let point = you.accessibilityActivationPoint
        XCTAssertTrue(tileFrame.contains(point), "You is tapped at \(point), off the tile \(tileFrame)")
        XCTAssertFalse(lineFrame.contains(point), "You is tapped on the line \(lineFrame)")

        var opened = 0
        block.onNetworkTap = {
            opened += 1
        }
        target.sendActions(for: .touchUpInside)
        XCTAssertEqual(opened, 1)
    }

    func testOnlyAMessageThatArrivedJustNowComesAsABand() {
        let becameActive = Date(timeIntervalSince1970: 1_000_000)
        let later = becameActive.addingTimeInterval(60)
        let now = later.timeIntervalSince1970

        XCTAssertTrue(NotificationFreshness.arrivedJustNow(sentAt: now - 2, becameActive: becameActive, now: later))
        XCTAssertTrue(NotificationFreshness.arrivedJustNow(sentAt: 0, becameActive: becameActive, now: later),
                      "a peer without msgV3 sends no time")
        XCTAssertFalse(NotificationFreshness.arrivedJustNow(sentAt: now - 60, becameActive: becameActive, now: later),
                       "a message delivered late was notified while away")

        let justBack = becameActive.addingTimeInterval(1)
        XCTAssertFalse(NotificationFreshness.arrivedJustNow(sentAt: justBack.timeIntervalSince1970, becameActive: becameActive, now: justBack),
                       "the backlog of the first seconds back was notified while away")
        XCTAssertFalse(NotificationFreshness.isPastEntryGrace(becameActive: becameActive, now: justBack))
        XCTAssertTrue(NotificationFreshness.isPastEntryGrace(becameActive: becameActive, now: becameActive.addingTimeInterval(3)))
    }

    /// KHANDAQ (notification parity, 2026-11-20): Realm hands the rows written together over in one change, newest first.
    /// The newest one the filter passes notifies, not the newest of all alone: a 1:1 message under a muted Community
    /// one, a synced row, a notice or our own message. One at most, and never a row under one stored before.
    func testAChangeNotifiesTheNewestInsertedRowTheFilterPasses() {
        func pick(_ insertions: [Int], passing: Set<Int>) -> Int? {
            return NotificationCoordinator.indexToNotify(insertions: insertions, notifies: { passing.contains($0) })
        }

        XCTAssertEqual(pick([0], passing: [0]), 0, "one new message, as before")
        XCTAssertEqual(pick([0, 1], passing: [1]), 1, "the 1:1 message under a muted Community one")
        XCTAssertEqual(pick([0, 1, 2], passing: [1, 2]), 1, "one band for the change, the newest")
        XCTAssertEqual(pick([2, 0, 1], passing: [2]), 2, "in whatever order the change lists them")
        XCTAssertNil(pick([0, 1], passing: []), "nothing in it notifies")
        XCTAssertNil(pick([], passing: [0]))
        XCTAssertNil(pick([1, 2], passing: [1, 2]), "under the newest row, stored before: a synced row with its old date")
        XCTAssertNil(pick([0, 2], passing: [2]), "row 1 was stored before")

        var asked: [Int] = []
        let picked = NotificationCoordinator.indexToNotify(insertions: [0, 1, 2, 3], notifies: { row in
            asked.append(row)
            return row >= 1
        })
        XCTAssertEqual(picked, 1)
        XCTAssertEqual(asked, [0, 1], "the rows under the one that notifies are not even looked at")
    }

    func testAnInvitationIsANotificationOfItsOwnKind() {
        let invitation = NotificationAction.openGroupInvite(inviteIdentifier: "F00D")
        // A system notification keeps its action in userInfo; a tap on it later opens Requests.
        guard case .openGroupInvite(let identifier)? = NotificationAction(dictionary: invitation.archive()) else {
            return XCTFail("the system notification of an invitation does not come back as one")
        }
        XCTAssertEqual(identifier, "F00D")

        let request = NotificationAction.openRequest(requestUniqueIdentifier: "request")
        let chat = NotificationAction.openChat(chatUniqueIdentifier: "chat")
        XCTAssertEqual(invitation.bandHint, String(localized: "air_banner_hint_invite"))
        XCTAssertEqual(request.bandHint, String(localized: "air_banner_hint_request"))
        XCTAssertEqual(chat.bandHint, String(localized: "air_banner_hint"))
        XCTAssertEqual(Set([invitation.bandHint, request.bandHint, chat.bandHint]).count, 3, "two kinds tell the same")
    }

    func testTheBandsWindowBelongsToTheSceneAndShowsOnlyWithABand() {
        let window = NotificationWindow()
        XCTAssertNotNil(window.windowScene, "a window without a scene never shows")
        XCTAssertTrue(window.isHidden, "the window is up without a band")

        let hint = NotificationAction.openChat(chatUniqueIdentifier: "chat").bandHint
        window.showBanner(title: "Amina Safina", body: "See you at the station at 5:45",
                          tile: .person(initials: "AS", tone: 2), photo: nil, hint: hint) {}
        XCTAssertFalse(window.isHidden)
        let band = window.subviews.compactMap { $0 as? NotificationBannerView }.first
        XCTAssertEqual(band?.accessibilityIdentifier, "air.banner")
        XCTAssertEqual(band?.accessibilityLabel, "Amina Safina, See you at the station at 5:45")
        XCTAssertEqual(band?.accessibilityHint, String(localized: "air_banner_hint"))

        window.dismissBanner(animated: false)
        XCTAssertTrue(window.isHidden, "the window stays up after its band left")
    }

    private final class Session: NSObject, OCTSubmanagerUserDelegate, OCTSubmanagerGroupsDelegate {
        func submanagerUser(_ submanager: OCTSubmanagerUser, connectionStatusUpdate connectionStatus: OCTToxConnectionStatus) {}
        func submanagerGroups(_ submanager: OCTSubmanagerGroups, didReceiveInviteFromFriendNumber friendNumber: OCTToxFriendNumber,
                              invite inviteData: Data, groupName: String?) {}
        func submanagerGroups(_ submanager: OCTSubmanagerGroups, groupJoinDidFail failType: OCTToxGroupJoinFail, for chat: OCTChat) {}
    }

    /// A theme or language switch builds a new session for the same manager before the old one shuts
    /// down. The old one used to clear the delegates the new one had just taken, so after every switch
    /// an invitation reached nobody (no card, no band) and neither did the node's own status.
    func testTheOldSessionLeavesTheNewSessionsDelegatesAlone() {
        let user = OCTSubmanagerUserMock()
        let groups = OCTSubmanagerGroupsMock()
        let old = Session()
        let new = Session()
        user.delegate = new
        groups.delegate = new

        ActiveSessionCoordinator.releaseDelegates(user: user, groups: groups, heldBy: old)
        XCTAssertTrue(user.delegate === new, "the old session took the node's status from the new one")
        XCTAssertTrue(groups.delegate === new, "the old session took the invitations from the new one")

        ActiveSessionCoordinator.releaseDelegates(user: user, groups: groups, heldBy: new)
        XCTAssertNil(user.delegate, "a session that shuts down keeps its own delegate")
        XCTAssertNil(groups.delegate, "a session that shuts down keeps its own delegate")
    }

    /// A wake push used to only wait 25 seconds on connections that died while the phone slept, so the
    /// message it announced rarely arrived in time. It reconnects first now.
    func testAWakePushReconnectsAtOnce() {
        let bootstrap = OCTSubmanagerBootstrapMock()
        AppDelegate.reconnectForWake(bootstrap)
        XCTAssertEqual(bootstrap.rebootstrapCount, 1, "the wake did not ask for an urgent reconnect")
    }

    // MARK: - The band of no network (0.2.57)

    /// The time of a test: work runs when the test moves the clock past it, in the order it falls due.
    private final class Clock: AirNoticeScheduling {
        private var now: TimeInterval = 0
        private var pending: [(at: TimeInterval, id: Int, work: () -> Void)] = []
        private var lastId = 0

        func schedule(after delay: TimeInterval, _ work: @escaping () -> Void) -> AirNoticeTimer {
            lastId += 1
            let id = lastId
            pending.append((at: now + delay, id: id, work: work))
            return AirNoticeTimer { [weak self] in
                self?.pending.removeAll { $0.id == id }
            }
        }

        func advance(_ seconds: TimeInterval) {
            let end = now + seconds
            while let next = pending.filter({ $0.at <= end + 1e-9 }).min(by: { ($0.at, $0.id) < ($1.at, $1.id) }) {
                pending.removeAll { $0.id == next.id }
                now = next.at
                next.work()
            }
            now = end
        }
    }

    private final class Phases {
        var seen: [AirNetworkNoticePhase] = []
    }

    private func makeNotice(_ clock: Clock, _ phases: Phases) -> AirNetworkNotice {
        return AirNetworkNotice(scheduler: clock) { _, current in
            phases.seen.append(current)
        }
    }

    func testANetworkGoneForLessThanThreeSecondsShowsNothing() {
        let clock = Clock()
        let phases = Phases()
        let notice = makeNotice(clock, phases)
        notice.nodeChanged(online: true)

        // A handover from Wi-Fi to mobile data.
        notice.networkChanged(available: false)
        clock.advance(2.9)
        notice.networkChanged(available: true)
        clock.advance(60)
        XCTAssertEqual(notice.phase, .hidden)

        // Drops that add up to more than 3 s show nothing either: it takes 3 s in a row.
        for _ in 0..<5 {
            notice.networkChanged(available: false)
            clock.advance(2)
            notice.networkChanged(available: true)
            clock.advance(0.5)
        }
        XCTAssertEqual(phases.seen, [])
    }

    func testThreeSecondsWithoutANetworkShowTheBandForAsLongAsThereIsNone() {
        let clock = Clock()
        let phases = Phases()
        let notice = makeNotice(clock, phases)
        notice.nodeChanged(online: true)

        notice.networkChanged(available: false)
        clock.advance(2.99)
        XCTAssertEqual(notice.phase, .hidden)
        clock.advance(0.01)
        XCTAssertEqual(notice.phase, .offline)

        // Our node gives up in the meantime; the band keeps saying there is no network.
        notice.nodeChanged(online: false)
        clock.advance(600)
        XCTAssertEqual(notice.phase, .offline)
        XCTAssertEqual(phases.seen, [.offline])
    }

    func testTheNetworkBackSaysConnectingThenBackOnlineThenTheBandGoes() {
        let clock = Clock()
        let phases = Phases()
        let notice = makeNotice(clock, phases)
        notice.nodeChanged(online: true)
        notice.networkChanged(available: false)
        clock.advance(3)
        notice.nodeChanged(online: false)

        notice.networkChanged(available: true)
        XCTAssertEqual(notice.phase, .connecting)
        clock.advance(12)
        XCTAssertEqual(notice.phase, .connecting)

        notice.nodeChanged(online: true)
        XCTAssertEqual(notice.phase, .backOnline)
        clock.advance(1.49)
        XCTAssertEqual(notice.phase, .backOnline)
        clock.advance(0.01)
        XCTAssertEqual(notice.phase, .hidden)
        XCTAssertEqual(phases.seen, [.offline, .connecting, .backOnline, .hidden])
    }

    func testANodeThatStayedOnlineIsBackOnlineAtOnce() {
        let clock = Clock()
        let phases = Phases()
        let notice = makeNotice(clock, phases)
        notice.nodeChanged(online: true)
        notice.networkChanged(available: false)
        clock.advance(5)

        notice.networkChanged(available: true)
        XCTAssertEqual(notice.phase, .backOnline)
        clock.advance(1.5)
        XCTAssertEqual(phases.seen, [.offline, .backOnline, .hidden])
    }

    func testConnectingGivesUpThirtySecondsAfterTheNetworkCameBack() {
        let clock = Clock()
        let phases = Phases()
        let notice = makeNotice(clock, phases)
        notice.networkChanged(available: false)
        // The 30 s count from the network's return, not from the loss.
        clock.advance(40)

        notice.networkChanged(available: true)
        clock.advance(29.9)
        XCTAssertEqual(notice.phase, .connecting)
        clock.advance(0.1)
        XCTAssertEqual(notice.phase, .hidden)

        // The line under the name says the rest; our node online later brings no band.
        notice.nodeChanged(online: true)
        clock.advance(10)
        XCTAssertEqual(phases.seen, [.offline, .connecting, .hidden])
    }

    func testOurNodeConnectingWithANetworkIsNotThisNotice() {
        let clock = Clock()
        let phases = Phases()
        let notice = makeNotice(clock, phases)
        notice.networkChanged(available: true)
        notice.nodeChanged(online: false)
        clock.advance(60)
        notice.nodeChanged(online: true)
        notice.nodeChanged(online: false)
        clock.advance(60)
        XCTAssertEqual(notice.phase, .hidden)
        XCTAssertEqual(phases.seen, [])
    }

    func testTheNetworkGoneAgainWhileTheBandIsUpSaysNoNetworkAfterThreeSeconds() {
        let clock = Clock()
        let phases = Phases()
        let notice = makeNotice(clock, phases)
        notice.networkChanged(available: false)
        clock.advance(3)
        notice.networkChanged(available: true)
        XCTAssertEqual(notice.phase, .connecting)

        // A short drop while it connects changes nothing.
        notice.networkChanged(available: false)
        clock.advance(2.9)
        XCTAssertEqual(notice.phase, .connecting)
        clock.advance(0.1)
        XCTAssertEqual(notice.phase, .offline)

        // Gone again while it says Back online: the band leaves on time and comes back at 3 s.
        notice.nodeChanged(online: true)
        notice.networkChanged(available: true)
        XCTAssertEqual(notice.phase, .backOnline)
        notice.networkChanged(available: false)
        clock.advance(1.5)
        XCTAssertEqual(notice.phase, .hidden)
        clock.advance(1.5)
        XCTAssertEqual(notice.phase, .offline)
        XCTAssertEqual(phases.seen, [.offline, .connecting, .offline, .backOnline, .hidden, .offline])
    }

    func testAScreenReaderHearsTheTitleOnceEachTimeTheBandComes() {
        let clock = Clock()
        let notice = makeNotice(clock, Phases())
        XCTAssertFalse(notice.takeAnnouncement(), "nothing to say while the band is gone")

        notice.networkChanged(available: false)
        clock.advance(3)
        XCTAssertTrue(notice.takeAnnouncement())
        XCTAssertFalse(notice.takeAnnouncement(), "a second screen with the band heard it again")

        notice.networkChanged(available: true)
        XCTAssertEqual(notice.phase, .connecting)
        XCTAssertFalse(notice.takeAnnouncement(), "the band did not come again, its words changed")
        clock.advance(30)

        notice.networkChanged(available: false)
        clock.advance(3)
        XCTAssertTrue(notice.takeAnnouncement())
    }

    func testTheBandSaysEachPhaseAndGoesWhenHidden() {
        let band = AirNetworkBandView()
        XCTAssertTrue(band.isHidden)
        band.show(.offline, animated: false)
        XCTAssertFalse(band.isHidden)
        // A screen reader hears the line too, also where a large text size leaves it off the band.
        XCTAssertEqual(band.accessibilityLabel,
                       String(localized: "air_net_band_offline") + ". " + String(localized: "air_net_band_offline_line"))
        band.show(.connecting, animated: false)
        XCTAssertEqual(band.accessibilityLabel, String(localized: "air_net_band_connecting"))
        band.show(.backOnline, animated: false)
        XCTAssertEqual(band.accessibilityLabel, String(localized: "air_net_band_back"))
        band.show(.hidden, animated: false)
        XCTAssertTrue(band.isHidden)
    }
}
