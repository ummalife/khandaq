// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import UniformTypeIdentifiers
import XCTest
@testable import Antidote

/// E12: requests and group invitations are cards above the sections, and "Decline" is undone from a
/// toast instead of being asked about first.
class AirRequestTests: XCTestCase {
    private let key = String(repeating: "A", count: 64)

    func testInvitationsStandWithRequestsNewestFirst() {
        let contact = AirRequest(id: "r1", publicKey: key, message: "Hi", timeText: "", date: 100)
        XCTAssertEqual(contact.kind, .contact, "a card is a contact request unless it says otherwise")
        let invite = AirRequest(id: "i1", publicKey: key, message: "", timeText: "", date: 200,
                                kind: .group(name: "Book club", inviter: "Amina", inviterOnline: false))
        let older = AirRequest(id: "r0", publicKey: key, message: "", timeText: "", date: 50)

        let snapshot = AirListBuilder.build(entries: [], requests: [contact, invite, older], filter: .all, query: "")
        XCTAssertEqual(snapshot.counts.requests, 3)
        XCTAssertEqual(snapshot.sections.first?.kind, .requests)
        XCTAssertEqual(snapshot.sections.first?.items.map { $0.id }, ["request:i1", "request:r1", "request:r0"])
        XCTAssertNil(snapshot.emptyState, "an invitation alone is something to answer, not an empty list")

        let filtered = AirListBuilder.build(entries: [], requests: [invite], filter: .requests, query: "")
        XCTAssertEqual(filtered.sections.first?.items.count, 1)
        let searching = AirListBuilder.build(entries: [], requests: [invite], filter: .all, query: "Book")
        XCTAssertTrue(searching.sections.isEmpty, "cards stay out of a search")
    }

    func testUndoTakesBackADeclineAndOnlyAMissedUndoCommitsIt() {
        let host = UIView(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        var undone = 0
        var committed = 0

        // "Undo" pressed: the decline never happens.
        let first = AirToast.show("Request declined", actionTitle: "Undo", seconds: 5, in: host,
                                  action: { undone += 1 }, expired: { committed += 1 })
        actionButton(in: first)?.sendActions(for: .touchUpInside)
        XCTAssertEqual(undone, 1)
        XCTAssertEqual(committed, 0)

        // A second decline while the first toast is up: the first is committed as the new one comes.
        AirToast.show("Request declined", actionTitle: "Undo", seconds: 5, in: host,
                      action: { undone += 1 }, expired: { committed += 1 })
        let third = AirToast.show("Invitation declined", actionTitle: "Undo", seconds: 5, in: host,
                                  action: { undone += 1 }, expired: { committed += 1 })
        XCTAssertEqual(committed, 1)

        // Its time runs out: committed once, and only once.
        third.dismiss(animated: false)
        third.dismiss(animated: false)
        XCTAssertEqual(committed, 2)
        XCTAssertEqual(undone, 1)
        XCTAssertNil(actionButton(in: third)?.window)
    }

    /// Stage 5: at the accessibility text sizes the two answers of a card stand one above the other at
    /// the card's width (side by side, a one-word answer broke inside the word), and the tag beside the
    /// time wraps whole instead of losing its tail. At the usual sizes they stay side by side.
    func testCardAnswersStackAtTheAccessibilityTextSizes() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let invite = AirRequest(id: "i1", publicKey: key, message: "", timeText: "8:55", date: 200,
                                kind: .group(name: "Book club", inviter: "Amina", inviterOnline: true))

        let usual = layOutCard(invite, category: .large)
        XCTAssertEqual(usual.join.minY, usual.decline.minY, accuracy: 0.5, "the answers left their line at the usual size")
        XCTAssertLessThan(usual.join.maxX, usual.decline.minX)

        let large = layOutCard(invite, category: .accessibilityExtraLarge)
        XCTAssertGreaterThanOrEqual(large.decline.minY, large.join.maxY, "the answers still share a line at AX3")
        XCTAssertEqual(large.join.width, large.decline.width, accuracy: 0.5, "the stacked answers differ in width")
        XCTAssertFalse(large.tagCut, "the tag lost its tail beside the time")
    }

    @available(iOS 17.0, *)
    private func layOutCard(_ request: AirRequest, category: UIContentSizeCategory) -> (join: CGRect, decline: CGRect, tagCut: Bool) {
        let width: CGFloat = 375
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 900))
        window.traitOverrides.preferredContentSizeCategory = category
        let cell = AirRequestCell(style: .default, reuseIdentifier: AirRequestCell.reuseIdentifier)
        window.addSubview(cell)
        window.isHidden = false
        cell.configure(with: request)
        let fitting = cell.contentView.systemLayoutSizeFitting(CGSize(width: width, height: 0),
                                                               withHorizontalFittingPriority: .required,
                                                               verticalFittingPriority: .fittingSizeLevel)
        cell.frame = CGRect(x: 0, y: 0, width: width, height: ceil(fitting.height))
        cell.layoutIfNeeded()

        let join = view(in: cell, identifier: "air.invite.join")
        let decline = view(in: cell, identifier: "air.invite.decline")
        XCTAssertNotNil(join)
        XCTAssertNotNil(decline)
        let tag = headerLabel(in: cell)
        XCTAssertNotNil(tag)
        var tagCut = true
        if let tag = tag {
            let needed = tag.sizeThatFits(CGSize(width: tag.bounds.width, height: .greatestFiniteMagnitude)).height
            tagCut = needed > tag.bounds.height + 0.5
        }
        window.isHidden = true
        return (join.map { $0.convert($0.bounds, to: cell) } ?? .zero,
                decline.map { $0.convert($0.bounds, to: cell) } ?? .zero,
                tagCut)
    }

    private func view(in root: UIView, identifier: String) -> UIView? {
        if root.accessibilityIdentifier == identifier {
            return root
        }
        for subview in root.subviews {
            if let found = view(in: subview, identifier: identifier) {
                return found
            }
        }
        return nil
    }

    private func headerLabel(in root: UIView) -> UILabel? {
        if let label = root as? UILabel, label.accessibilityTraits & UIAccessibilityTraitHeader != 0 {
            return label
        }
        for subview in root.subviews {
            if let found = headerLabel(in: subview) {
                return found
            }
        }
        return nil
    }

    private func actionButton(in view: UIView) -> UIButton? {
        if let button = view as? UIButton, button.accessibilityIdentifier == "air.toast.action" {
            return button
        }
        for subview in view.subviews {
            if let found = actionButton(in: subview) {
                return found
            }
        }
        return nil
    }
}

/**
 Stage 5, the full pass at the largest text sizes (AX3 and AX5) on the narrow and the usual phone: on the
 screens where it went wrong no word breaks inside itself and no words stand out of their plate. Your
 name and the line under it beside the tile, the letters of the tile ("..."), the pills, the tag of a
 card beside its time, a toast with Undo, the hours of the band of a conversation, the words of the link
 picture in the dossier, the head of a run in the transcript, and the name and reach of a row of Air.
 */
class AirLargeTextLayoutTests: XCTestCase {
    private let sizes: [UIContentSizeCategory] = [.accessibilityExtraLarge, .accessibilityExtraExtraExtraLarge]
    private let widths: [CGFloat] = [375, 402]
    private var savedLanguage: Any?
    private var savedAppleLanguages: Any?

    override func setUp() {
        super.setUp()
        savedLanguage = UserDefaults.standard.object(forKey: AppLanguage.storageKey)
        savedAppleLanguages = UserDefaults.standard.object(forKey: "AppleLanguages")
        // Russian has the longest words of the four languages.
        AppLanguage.set("ru")
    }

    override func tearDown() {
        UserDefaults.standard.set(savedLanguage, forKey: AppLanguage.storageKey)
        UserDefaults.standard.set(savedAppleLanguages, forKey: "AppleLanguages")
        AppLanguage.applyAtLaunch()
        super.tearDown()
    }

    func testAirHeaderKeepsWholeWordsAndThePillsHoldTheirWords() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        for category in sizes {
            for width in widths {
                let context = "\(category.rawValue) at \(width)"
                let header = AirHeaderView()
                let window = host(header, width: width, category: category)
                header.selfView.configure(name: "Самир Валиев", photo: nil)
                header.selfView.setNetwork(.relay, animated: false)
                header.setCounts(AirCounts(requests: 4, transit: 4), animated: false)
                window.layoutIfNeeded()

                assertWholeWords(in: header, context)
                for pill in header.pills.values {
                    for label in labels(in: pill) where label.isVisible && !(label.text ?? "").isEmpty {
                        let frame = label.convert(label.bounds, to: pill)
                        XCTAssertTrue(pill.bounds.insetBy(dx: -0.5, dy: -0.5).contains(frame),
                                      "\"\(label.text ?? "")\" stands out of its pill (\(context))")
                    }
                }
                // The buttons stay on the tile's row, clear of the name under it.
                let tile = header.selfView.tileView.convert(header.selfView.tileView.bounds, to: header)
                let settings = header.settingsButton.convert(header.settingsButton.bounds, to: header)
                XCTAssertEqual(settings.midY, tile.midY, accuracy: 0.5, "the buttons left the tile's row (\(context))")
                window.isHidden = true
            }
        }
    }

    func testACardAndAToastWithUndoKeepWholeWords() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let invite = AirRequest(id: "i1", publicKey: String(repeating: "A", count: 64), message: "", timeText: "10:04", date: 200,
                                kind: .group(name: "Книжный клуб", inviter: "Амина Сафина", inviterOnline: true))
        for category in sizes {
            for width in widths {
                let context = "\(category.rawValue) at \(width)"
                // As the list lays a card out: the cell in a window of these traits, as tall as it asks.
                let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 1600))
                window.traitOverrides.preferredContentSizeCategory = category
                let cell = AirRequestCell(style: .default, reuseIdentifier: AirRequestCell.reuseIdentifier)
                window.addSubview(cell)
                window.isHidden = false
                cell.configure(with: invite)
                let fitting = cell.contentView.systemLayoutSizeFitting(CGSize(width: width, height: 0),
                                                                       withHorizontalFittingPriority: .required,
                                                                       verticalFittingPriority: .fittingSizeLevel)
                cell.frame = CGRect(x: 0, y: 0, width: width, height: ceil(fitting.height))
                cell.layoutIfNeeded()
                assertWholeWords(in: cell.contentView, context)
                window.isHidden = true

                let screen = UIView()
                let toastWindow = host(screen, width: width, category: category, height: 600)
                let toast = AirToast.show("Запрос отклонён", actionTitle: "Отменить", seconds: 60, in: screen)
                toastWindow.layoutIfNeeded()
                assertWholeWords(in: toast, context)
                toast.dismiss(animated: false)
                toastWindow.isHidden = true
            }
        }
    }

    func testTheBandAndTheLinkPictureKeepTheirWordsWhole() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        var hours = Set<Int>()
        let now = Date()
        for back in [2, 3, 12, 13] {
            let start = Calendar.current.dateInterval(of: .hour, for: now.addingTimeInterval(-Double(back) * 3600))?.start ?? now
            hours.insert(Int(start.timeIntervalSince1970))
        }
        let strip = AirDayStrip.make(hours: hours, now: now)
        for category in sizes {
            for width in widths {
                let context = "\(category.rawValue) at \(width)"
                let band = AirPresenceBandView()
                let window = host(band, width: width, category: category)
                band.configure(link: .relay, presence: "в сети с 9:24, через ретранслятор", strip: strip, nowOnline: true,
                               stripHint: "за сутки: с 8 до 10, с 21 до 23", stripSpoken: "")
                window.layoutIfNeeded()
                assertWholeWords(in: band, context)
                assertNothingCut(in: band, context)
                // At these sizes the band keeps the presence line; the hours are in the dossier.
                XCTAssertTrue(labels(in: band).contains { $0.text == "за сутки: с 8 до 10, с 21 до 23" && !$0.isVisible },
                              "the hours stayed on the band (\(context))")
                XCTAssertTrue(band.accessibilityLabel?.contains("Сутки") ?? false, "VoiceOver lost the day (\(context))")
                window.isHidden = true

                let path = AirPathView()
                let pathWindow = host(path, width: width - 2 * KQSpace.x16, category: category)
                path.configure(state: .relay, initials: "АС", name: "Амина Сафина")
                pathWindow.layoutIfNeeded()
                for label in labels(in: path) where label.numberOfLines == 0 {
                    assertWholeWords(of: label, context)
                    let needed = label.sizeThatFits(CGSize(width: label.bounds.width, height: .greatestFiniteMagnitude)).height
                    XCTAssertLessThanOrEqual(needed, label.bounds.height + 0.5, "\"\(label.text ?? "")\" is cut (\(context))")
                }
                pathWindow.isHidden = true
            }
        }
    }

    func testARowKeepsTheWholeNameAndReachAtTheLargestSizes() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let person = AirEntry(id: "p1", kind: .direct, title: "Тимур Ахмедов", reach: .relay,
                              preview: "Хорошо, я буду у тебя в половине восьмого", previewStyle: .message,
                              lastDelivery: .delivered, timeText: "10:57", activity: 1, transitCount: 4, unreadCount: 2,
                              isFavorite: false, avatarData: nil, tone: 0)
        let group = AirEntry(id: "g1", kind: .group, title: "Книжный клуб", reach: .group(online: 4),
                             preview: "Конечно, чем больше, тем лучше", previewStyle: .message,
                             lastDelivery: nil, timeText: "9:38", activity: 1, transitCount: 0, unreadCount: 0,
                             isFavorite: false, avatarData: nil, tone: 1)
        for entry in [person, group] {
            for category in sizes {
                for width in widths {
                    let context = "\(entry.title), \(category.rawValue) at \(width)"
                    // As the list lays a row out: the cell in a window of these traits, as tall as it asks.
                    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: 1600))
                    window.traitOverrides.preferredContentSizeCategory = category
                    let cell = AirRowCell(style: .default, reuseIdentifier: AirRowCell.reuseIdentifier)
                    window.addSubview(cell)
                    window.isHidden = false
                    cell.configure(with: entry, photo: nil)
                    let fitting = cell.contentView.systemLayoutSizeFitting(CGSize(width: width, height: 0),
                                                                           withHorizontalFittingPriority: .required,
                                                                           verticalFittingPriority: .fittingSizeLevel)
                    cell.frame = CGRect(x: 0, y: 0, width: width, height: ceil(fitting.height))
                    cell.layoutIfNeeded()

                    let reach = AirRowCell.reachText(entry.reach)
                    let whole = labels(in: cell.contentView).filter { $0.isVisible && [entry.title, reach].contains($0.text ?? "") }
                    XCTAssertEqual(whole.count, 2, "the name or the reach is not on the row (\(context))")
                    for label in whole {
                        assertWholeWords(of: label, context)
                        // One line cut with "..." asks for no more height, only for more width.
                        if label.numberOfLines == 1 {
                            XCTAssertLessThanOrEqual(label.intrinsicContentSize.width, label.bounds.width + 0.5,
                                                     "\"\(label.text ?? "")\" is cut (\(context))")
                        }
                        let needed = label.sizeThatFits(CGSize(width: label.bounds.width, height: .greatestFiniteMagnitude)).height
                        XCTAssertLessThanOrEqual(needed, label.bounds.height + 0.5, "\"\(label.text ?? "")\" is cut (\(context))")
                        let frame = label.convert(label.bounds, to: cell.contentView)
                        XCTAssertTrue(cell.contentView.bounds.insetBy(dx: -0.5, dy: -0.5).contains(frame),
                                      "\"\(label.text ?? "")\" stands out of the row (\(context))")
                    }
                    window.isHidden = true
                }
            }
        }
    }

    func testTheHintUnderTheFieldShowsAllItsWordsAtTheLargestSizes() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let long = "Подождёт в пути: Максимилиан не в сети. Уйдёт само"
        for category in sizes {
            for width in widths {
                let context = "\(category.rawValue) at \(width)"
                // As the composer holds it: 16 points in from both edges, as tall as its words ask.
                let container = UIView()
                let hint = AirComposeHintView()
                hint.translatesAutoresizingMaskIntoConstraints = false
                container.addSubview(hint)
                NSLayoutConstraint.activate([
                    hint.topAnchor.constraint(equalTo: container.topAnchor),
                    hint.bottomAnchor.constraint(equalTo: container.bottomAnchor),
                    hint.leadingAnchor.constraint(equalTo: container.leadingAnchor, constant: KQSpace.x16),
                    hint.trailingAnchor.constraint(equalTo: container.trailingAnchor, constant: -KQSpace.x16),
                ])
                let window = host(container, width: width, category: category)
                // The conversation opens with a short hint and the person's state changes it later.
                hint.setHint(AirComposeHint(text: "Уйдёт сразу: Юсуф в сети", dot: .live), animated: false)
                window.layoutIfNeeded()
                assertNothingCut(in: hint, context)

                hint.setHint(AirComposeHint(text: long, dot: .transit), animated: false)
                window.layoutIfNeeded()
                assertWholeWords(in: hint, context)
                assertNothingCut(in: hint, context)

                // The length counter at the end leaves the words less room.
                hint.setCounter(remaining: 120)
                window.layoutIfNeeded()
                assertWholeWords(in: hint, context)
                assertNothingCut(in: hint, context)
                window.isHidden = true
            }
        }
    }

    func testTheComposerGivesItsHintTheRoomOfAllItsWordsUnderTheSelectionToolbar() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let path = try XCTUnwrap(Bundle.main.path(forResource: "default-theme", ofType: "yaml"))
        let theme = try Theme(yamlString: String(contentsOfFile: path, encoding: .utf8))
        for category in sizes {
            for width in widths {
                let context = "\(category.rawValue) at \(width)"
                let input = ChatInputView(theme: theme)
                input.useAirStyle()
                let window = host(input, width: width, category: category)
                // As a conversation holds the composer: the toolbar of the selection mode lies over it,
                // edge to edge, hidden. It hugs its 44 points at the default priority; it pressed a long
                // hint to the room of one line at AX5 and of two at AX3.
                let toolbar = UIToolbar()
                toolbar.isHidden = true
                toolbar.translatesAutoresizingMaskIntoConstraints = false
                window.addSubview(toolbar)
                NSLayoutConstraint.activate([
                    toolbar.topAnchor.constraint(equalTo: input.topAnchor),
                    toolbar.bottomAnchor.constraint(equalTo: input.bottomAnchor),
                    toolbar.leadingAnchor.constraint(equalTo: input.leadingAnchor),
                    toolbar.trailingAnchor.constraint(equalTo: input.trailingAnchor),
                ])
                let hint = try XCTUnwrap(input.hintView)
                hint.setHint(AirComposeHint(text: "Подождёт в пути: Максимилиан не в сети. Уйдёт само", dot: .transit), animated: false)
                window.layoutIfNeeded()
                assertWholeWords(in: hint, context)
                assertNothingCut(in: hint, context)
                window.isHidden = true
            }
        }
    }

    func testTheOpenTrayScrollsItsExplanationWithTheMessagesAtTheLargestSizes() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let detail = "Уйдут сами, как только Юсуф появится в сети. Телефон получит сигнал без текста и заберёт их сам."
        let items = (1...4).map { AirQueueItem(id: "q\($0)", text: "Буду у тебя в 19:\($0)0", time: "11:1\($0)", status: .transit) }
        for category in [UIContentSizeCategory.large] + sizes {
            let tray = AirTrayView()
            let window = host(tray, width: 402, category: category)
            tray.configure(items: items, collapsed: "4 в пути", expanded: "В пути: 4 сообщения", detail: detail)
            tray.setExpanded(true, animated: false)
            window.layoutIfNeeded()
            let explanation = try XCTUnwrap(labels(in: tray).first { $0.text == detail })
            let scroll = try XCTUnwrap(scrollViews(in: tray).first)
            XCTAssertEqual(explanation.isDescendant(of: scroll), category.isAccessibilityCategory,
                           "the explanation \(category.isAccessibilityCategory ? "stays out of" : "went into") the scroll (\(category.rawValue))")
            XCTAssertTrue(explanation.isVisible, "the open tray lost its explanation (\(category.rawValue))")
            XCTAssertFalse(scroll.isHidden, "the open tray hides its messages (\(category.rawValue))")
            // The messages come back after the explanation when the tray is configured again.
            tray.configure(items: Array(items.prefix(2)), collapsed: "2 в пути", expanded: "В пути: 2 сообщения", detail: detail)
            window.layoutIfNeeded()
            XCTAssertTrue(explanation.isVisible, "a new configuration took the explanation away (\(category.rawValue))")
            XCTAssertEqual(labels(in: tray).filter { ($0.text ?? "").hasPrefix("Буду у тебя") }.count, 2,
                           "the messages are not all in the tray (\(category.rawValue))")
            window.isHidden = true
        }
    }

    func testTheBandGivesTheLogItsShareAtTheLargestSizesAndDoesNotComeAndGo() {
        let screen: CGFloat = 874
        XCTAssertFalse(AirLogRoom.bandFolds(accessible: false, keyboardUp: true, log: 10, band: 172, bandShown: true, screen: screen),
                       "the band folded below the accessibility text sizes")
        XCTAssertTrue(AirLogRoom.bandFolds(accessible: true, keyboardUp: true, log: 600, band: 120, bandShown: true, screen: screen),
                      "the band stayed with the keyboard up")
        // The conversation at AX5 with a refused message: 45 points of log beside a band of 172.
        XCTAssertTrue(AirLogRoom.bandFolds(accessible: true, keyboardUp: false, log: 45, band: 172, bandShown: true, screen: screen),
                      "the band kept the log at 45 points")
        // Folded, it comes back only where the log keeps its share with it.
        XCTAssertTrue(AirLogRoom.bandFolds(accessible: true, keyboardUp: false, log: 259, band: 172, bandShown: false, screen: screen),
                      "the band came back over a log it leaves less than its share")
        XCTAssertFalse(AirLogRoom.bandFolds(accessible: true, keyboardUp: false, log: 511, band: 120, bandShown: false, screen: screen),
                       "the band stayed away from a log with room for it")
        // Whatever it decides, the next layout agrees with it.
        for log in stride(from: CGFloat(0), through: 800, by: 7) {
            for band: CGFloat in [60, 120, 172] {
                if !AirLogRoom.bandFolds(accessible: true, keyboardUp: false, log: log, band: band, bandShown: false, screen: screen) {
                    XCTAssertFalse(AirLogRoom.bandFolds(accessible: true, keyboardUp: false, log: log - band, band: band, bandShown: true, screen: screen),
                                   "the band came back and folded again (log \(log), band \(band))")
                }
                if AirLogRoom.bandFolds(accessible: true, keyboardUp: false, log: log, band: band, bandShown: true, screen: screen) {
                    XCTAssertTrue(AirLogRoom.bandFolds(accessible: true, keyboardUp: false, log: log + band, band: band, bandShown: false, screen: screen),
                                  "the band folded and came back (log \(log), band \(band))")
                }
            }
        }
    }

    func testTheRefusedMessagesScrollInTheRoomTheyAreGivenAndKeepTheirWords() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let items = (1...3).map { AirQueueItem(id: "f\($0)", text: "Во сколько обратный поезд? \($0)", time: "12:1\($0)", status: .failed) }
        for category in [UIContentSizeCategory.large] + sizes {
            // Where nothing presses them, they take their own height.
            let free = AirFailedListView()
            let freeWindow = host(free, width: 402, category: category)
            free.configure(items: items)
            freeWindow.layoutIfNeeded()
            let freeScroll = try XCTUnwrap(scrollViews(in: free).first)
            XCTAssertEqual(freeScroll.bounds.height, freeScroll.contentSize.height, accuracy: 1,
                           "the refused messages do not take their own height (\(category.rawValue))")
            assertNothingCut(in: free, category.rawValue)
            freeWindow.isHidden = true

            // Given less, they scroll in it, and no word of theirs is cut.
            let pressed = AirFailedListView()
            let window = host(pressed, width: 402, category: category, height: 160)
            pressed.configure(items: items)
            window.layoutIfNeeded()
            let scroll = try XCTUnwrap(scrollViews(in: pressed).first)
            XCTAssertEqual(pressed.bounds.height, 160, accuracy: 0.5, "the refused messages took more than their room (\(category.rawValue))")
            XCTAssertGreaterThan(scroll.contentSize.height, scroll.bounds.height,
                                 "the refused messages do not scroll in less room (\(category.rawValue))")
            assertNothingCut(in: pressed, category.rawValue)
            window.isHidden = true
        }
    }

    func testTheTrayFoldsAwayWithTheKeyboardAndComesBackWithItsMessages() {
        let items = (1...2).map { AirQueueItem(id: "q\($0)", text: "Буду у тебя в 19:\($0)0", time: "11:1\($0)", status: .transit) }
        let tray = AirTrayView()
        tray.configure(items: items, collapsed: "2 в пути", expanded: "В пути: 2 сообщения", detail: "")
        XCTAssertFalse(tray.isHidden)
        tray.foldsAway = true
        XCTAssertTrue(tray.isHidden, "the tray stayed with the keyboard up")
        // A message sent meanwhile goes into the tray without bringing it back.
        let more = items + [AirQueueItem(id: "q3", text: "Ещё одно", time: "11:20", status: .transit)]
        tray.configure(items: more, collapsed: "3 в пути", expanded: "В пути: 3 сообщения", detail: "")
        XCTAssertTrue(tray.isHidden, "a new message brought the tray back with the keyboard up")
        tray.foldsAway = false
        XCTAssertFalse(tray.isHidden, "the tray did not come back with its messages")
        tray.configure(items: [], collapsed: "", expanded: "", detail: "")
        XCTAssertTrue(tray.isHidden, "an empty tray is on the screen")
        tray.foldsAway = true
        tray.foldsAway = false
        XCTAssertTrue(tray.isHidden, "an empty tray came back after the keyboard")
    }

    func testTheHeadOfARunPutsTheAuthorAfterTheTimeAtTheLargestSizes() {
        let label = UILabel()
        AirEntryMetrics.show(author: "Максимилиан", color: KQColor.ink, time: "10:04", stacked: true, in: label)
        XCTAssertEqual(label.attributedText?.string, "10:04 Максимилиан")
        XCTAssertEqual(label.numberOfLines, 0, "the head must wrap at its words")

        AirEntryMetrics.show(author: "Максимилиан", color: KQColor.ink, time: "10:04", stacked: false, in: label)
        XCTAssertEqual(label.text, "Максимилиан")
        XCTAssertEqual(label.numberOfLines, 2)
        XCTAssertEqual(label.font, KQFont.font(.label, weight: 600), "the head left its font on the author")
    }

    /// The controls that keep their size while the text grows show their glyph and name large on a long
    /// press (the Large Content Viewer): every icon button of Air, the paperclip, the microphone and the
    /// arrow of the field, and the title of a conversation with the whole name.
    func testTheControlsThatKeepTheirSizeShowLargeOnALongPress() throws {
        let button = AirIconButton(icon: "paperclip", label: "Прикрепить файл")
        assertShowsLargeContent(button, title: "Прикрепить файл", "an icon button")
        let paperclip = button.largeContentImage
        button.setIcon("mic")
        XCTAssertNotNil(button.largeContentImage)
        XCTAssertFalse(button.largeContentImage === paperclip, "the viewer kept the glyph the button had before")
        button.accessibilityLabel = "Голосовое сообщение"
        XCTAssertEqual(button.largeContentTitle, "Голосовое сообщение", "the viewer kept the name the button had before")

        let input = try airComposer()
        for identifier in ["air.chat.attach", "air.chat.voice", "air.chat.send"] {
            let control = try XCTUnwrap(input.descendant(withIdentifier: identifier), identifier)
            XCTAssertFalse((control.accessibilityLabel ?? "").isEmpty, identifier)
            assertShowsLargeContent(control, title: control.accessibilityLabel, identifier)
        }

        // The microphone records while it is held: the hold runs together with the viewer, never instead of it.
        let voice = try XCTUnwrap(input.descendant(withIdentifier: "air.chat.voice"))
        let hold = try XCTUnwrap(voice.gestureRecognizers?.compactMap { $0 as? UILongPressGestureRecognizer }
                                    .first { abs($0.minimumPressDuration - 0.25) < 0.001 }, "the hold that records")
        let viewer = try XCTUnwrap(voice.interactions.compactMap { $0 as? UILargeContentViewerInteraction }.first)
        let delegate = try XCTUnwrap(hold.delegate, "nothing lets the hold run with the viewer")
        XCTAssertEqual(delegate.gestureRecognizer?(hold, shouldRecognizeSimultaneouslyWith: viewer.gestureRecognizerForExclusionRelationship),
                       true, "the viewer would take the hold away")
        for other in voice.gestureRecognizers ?? [] where other !== hold {
            XCTAssertEqual(delegate.gestureRecognizer?(hold, shouldRecognizeSimultaneouslyWith: other), true,
                           "the hold does not run with \(type(of: other))")
        }

        let title = ChatPrivateTitleView()
        let name = "Максимилиан Александрович Константинопольский"
        title.configure(name: name, style: .person(initials: "МК", tone: 1), photo: nil)
        assertShowsLargeContent(title, title: name, "the title of a conversation", glyph: false)
    }

    /// At the accessibility sizes the glyphs of the field grow to 28 points in their 44 point targets; at the
    /// other sizes they keep the size of the design (20 for the paperclip, the sprite's own for the
    /// microphone and the arrow), so the store frames, taken at the default size, stay as they are.
    func testTheGlyphsOfTheFieldGrowWithTheTextAtTheAccessibilitySizesOnly() throws {
        guard #available(iOS 17.0, *) else {
            throw XCTSkip("trait overrides of a window need iOS 17")
        }
        let mic = try XCTUnwrap(AirIcon.image("mic")).size
        let arrow = try XCTUnwrap(AirIcon.image("arrow-up")).size
        let design: [String: CGSize] = [
            "air.chat.attach": CGSize(width: KQSize.icon, height: KQSize.icon),
            "air.chat.voice": mic,
            "air.chat.send": arrow,
        ]
        let grown = CGSize(width: AirLargeContent.grownGlyph, height: AirLargeContent.grownGlyph)
        XCTAssertEqual(grown.width, 28)
        let categories: [UIContentSizeCategory] = [.large, .extraExtraExtraLarge, .accessibilityMedium,
                                                   .accessibilityExtraLarge, .accessibilityExtraExtraExtraLarge]
        for category in categories {
            let input = try airComposer()
            let window = host(input, width: 402, category: category)
            for (identifier, size) in design {
                let context = "\(identifier) at \(category.rawValue)"
                let control = try XCTUnwrap(input.descendant(withIdentifier: identifier), context)
                XCTAssertEqual(control.bounds.size, CGSize(width: KQSize.targetIos, height: KQSize.targetIos), context)
                let glyph: CGSize
                if let button = control as? UIButton {
                    glyph = try XCTUnwrap(button.image(for: .normal), context).size
                }
                else {
                    glyph = try XCTUnwrap(control.subviews.compactMap { $0 as? UIImageView }.first, context).bounds.size
                }
                XCTAssertEqual(glyph, category.isAccessibilityCategory ? grown : size, context)
            }
            window.isHidden = true
        }
    }

    /// A navigation bar shows the viewer for the views of its items itself, from their properties: the title
    /// and the buttons of a conversation's header keep the properties there and leave the interaction to the
    /// bar (with one of its own the title showed nothing at AX5), and a button out of a bar has its own
    /// again. The round buttons of the older screens (back, add) have the properties too.
    func testTheViewsOfABarLeaveTheViewerToTheBar() throws {
        let root = UIViewController()
        let navigation = UINavigationController(rootViewController: root)
        let title = ChatPrivateTitleView()
        let name = "Максимилиан Александрович Константинопольский"
        title.configure(name: name, style: .person(initials: "МК", tone: 1), photo: nil)
        let search = AirIconButton(icon: "search", label: "Поиск")
        root.navigationItem.leftBarButtonItems = [UIBarButtonItem(customView: title)]
        root.navigationItem.rightBarButtonItems = [UIBarButtonItem(customView: search)]
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 402, height: 874))
        window.rootViewController = navigation
        window.isHidden = false
        window.layoutIfNeeded()

        for (view, expected, context) in [(title as UIView, name, "the title"), (search as UIView, "Поиск", "a header button")] {
            XCTAssertNotNil(view.window, "\(context) is not in the bar")
            XCTAssertTrue(view.showsLargeContentViewer, "\(context) does not show in the viewer of the bar")
            XCTAssertEqual(view.largeContentTitle, expected, "\(context) has another name in the viewer")
            XCTAssertFalse(view.interactions.contains { $0 is UILargeContentViewerInteraction },
                           "\(context) keeps an interaction of its own in the bar")
        }
        XCTAssertNotNil(search.largeContentImage, "the header button has no glyph in the viewer")

        root.navigationItem.rightBarButtonItems = nil
        root.view.addSubview(search)
        window.layoutIfNeeded()
        XCTAssertTrue(search.interactions.contains { $0 is UILargeContentViewerInteraction },
                      "a button out of a bar has no interaction of the viewer")
        window.isHidden = true

        let path = try XCTUnwrap(Bundle.main.path(forResource: "default-theme", ofType: "yaml"))
        let theme = try Theme(yamlString: String(contentsOfFile: path, encoding: .utf8))
        let back = ThemeChrome.makeCircleNavButton(theme: theme, image: UIImage(systemName: "chevron.backward"), target: nil,
                                                   action: #selector(UIViewController.khandaqCircleBackTapped))
        let backView = try XCTUnwrap(back.customView)
        XCTAssertTrue(backView.showsLargeContentViewer, "the round back button does not show in the viewer of the bar")
        XCTAssertNotNil(backView.largeContentImage, "the round back button has no glyph in the viewer")
        XCTAssertTrue(backView.scalesLargeContentImage)
    }

    // MARK: - Helpers

    private func airComposer() throws -> ChatInputView {
        let path = try XCTUnwrap(Bundle.main.path(forResource: "default-theme", ofType: "yaml"))
        let theme = try Theme(yamlString: String(contentsOfFile: path, encoding: .utf8))
        let input = ChatInputView(theme: theme)
        input.useAirStyle()
        return input
    }

    private func assertShowsLargeContent(_ view: UIView, title: String?, _ context: String, glyph: Bool = true,
                                         file: StaticString = #file, line: UInt = #line) {
        XCTAssertTrue(view.showsLargeContentViewer, "\(context) does not show in the Large Content Viewer", file: file, line: line)
        XCTAssertEqual(view.largeContentTitle, title, "\(context) has another name in the viewer", file: file, line: line)
        XCTAssertTrue(view.interactions.contains { $0 is UILargeContentViewerInteraction },
                      "\(context) has no interaction of the viewer", file: file, line: line)
        if glyph {
            XCTAssertNotNil(view.largeContentImage, "\(context) has no glyph in the viewer", file: file, line: line)
            XCTAssertTrue(view.scalesLargeContentImage, "\(context) shows its glyph small in the viewer", file: file, line: line)
        }
    }

    @available(iOS 17.0, *)
    private func host(_ view: UIView, width: CGFloat, category: UIContentSizeCategory, height: CGFloat = 1200) -> UIWindow {
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: width, height: height))
        window.traitOverrides.preferredContentSizeCategory = category
        view.translatesAutoresizingMaskIntoConstraints = false
        window.addSubview(view)
        NSLayoutConstraint.activate([
            view.topAnchor.constraint(equalTo: window.topAnchor),
            view.leadingAnchor.constraint(equalTo: window.leadingAnchor),
            view.trailingAnchor.constraint(equalTo: window.trailingAnchor),
        ])
        if height < 1200 {
            view.bottomAnchor.constraint(equalTo: window.bottomAnchor).isActive = true
        }
        window.isHidden = false
        window.layoutIfNeeded()
        return window
    }

    private func scrollViews(in root: UIView) -> [UIScrollView] {
        var found: [UIScrollView] = []
        if let scroll = root as? UIScrollView {
            found.append(scroll)
        }
        for subview in root.subviews {
            found += scrollViews(in: subview)
        }
        return found
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

    private func assertWholeWords(in root: UIView, _ context: String, file: StaticString = #file, line: UInt = #line) {
        for label in labels(in: root) where label.isVisible {
            assertWholeWords(of: label, context, file: file, line: line)
        }
    }

    /// Every word of the label fits its width at the label's own font: a word wider than the line has
    /// to break inside itself.
    private func assertWholeWords(of label: UILabel, _ context: String, file: StaticString = #file, line: UInt = #line) {
        let text = label.attributedText ?? NSAttributedString(string: label.text ?? "")
        let string = text.string as NSString
        guard string.length > 0, label.bounds.width > 0 else {
            return
        }
        string.enumerateSubstrings(in: NSRange(location: 0, length: string.length), options: .byWords) { _, range, _, _ in
            let width = ceil(text.attributedSubstring(from: range).size().width)
            XCTAssertLessThanOrEqual(width, ceil(label.bounds.width) + 1,
                                     "\"\(string.substring(with: range))\" breaks inside itself in \"\(text.string)\" (\(context))",
                                     file: file, line: line)
        }
    }

    /// No visible label of the view needs more height than it has: nothing is cut with "...".
    private func assertNothingCut(in root: UIView, _ context: String, file: StaticString = #file, line: UInt = #line) {
        for label in labels(in: root) where label.isVisible && !(label.text ?? "").isEmpty {
            let needed = label.sizeThatFits(CGSize(width: label.bounds.width, height: .greatestFiniteMagnitude)).height
            XCTAssertLessThanOrEqual(needed, label.bounds.height + 0.5, "\"\(label.text ?? "")\" is cut (\(context))",
                                     file: file, line: line)
        }
    }
}

private extension UIView {
    /// The first view under this one, itself included, with the accessibility identifier.
    func descendant(withIdentifier identifier: String) -> UIView? {
        if accessibilityIdentifier == identifier {
            return self
        }
        for subview in subviews {
            if let found = subview.descendant(withIdentifier: identifier) {
                return found
            }
        }
        return nil
    }

    /// Shown on the screen: neither it nor any view above it is hidden.
    var isVisible: Bool {
        var view: UIView? = self
        while let current = view {
            if current.isHidden || current.alpha < 0.01 {
                return false
            }
            view = current.superview
        }
        return window != nil
    }
}

/// Stage 5: the first hold of the microphone asks iOS, and the answer comes after the finger has left the
/// button. Allowed then, the recording started on the answer went on with nobody holding it.
class VoiceHoldGateTests: XCTestCase {
    func testAHoldWithTheMicrophoneAllowedRecordsAtOnce() {
        var gate = VoiceHoldGate()
        let hold = gate.begin()
        XCTAssertTrue(gate.isHeld)
        XCTAssertTrue(gate.mayRecord(for: hold), "the hold did not record with the microphone allowed")
    }

    func testTheAnswerAfterTheHoldStartsNoRecording() {
        var gate = VoiceHoldGate()
        let hold = gate.begin()
        gate.end()
        XCTAssertFalse(gate.isHeld)
        XCTAssertFalse(gate.mayRecord(for: hold), "allowed after the hold had ended, a recording started")
    }

    func testTheAnswerForAnEarlierHoldStartsNoRecording() {
        var gate = VoiceHoldGate()
        let first = gate.begin()
        gate.end()
        let second = gate.begin()
        XCTAssertFalse(gate.mayRecord(for: first), "the answer for an earlier hold started a recording")
        XCTAssertTrue(gate.mayRecord(for: second), "the hold that is on did not record")
    }
}

/// Stage 5: "Share" into Khandaq. The extension hands what is shared to the app through the app group's
/// inbox, which exists only while both targets declare the group: without it every share failed.
class ShareInboxTests: XCTestCase {
    override func tearDown() {
        ShareInboxStorage.clearPendingShare()
        super.tearDown()
    }

    func testTheAppGroupContainerIsThere() {
        XCTAssertNotNil(ShareInboxStorage.containerURL(),
                        "no container of group.org.khandaq.messenger: the app does not declare the app group")
    }

    func testAPendingShareIsKeptTakenAndCleared() {
        let payload = ShareInboxPayload(version: 1, createdAt: 1, items: [
            ShareInboxItem(type: .text, text: "Salam", fileName: nil, relativePath: nil),
        ])
        XCTAssertTrue(ShareInboxStorage.savePendingShare(payload), "the share was not kept")
        XCTAssertEqual(ShareInboxStorage.loadPendingShare()?.items.first?.text, "Salam")
        ShareInboxStorage.clearPendingShare()
        XCTAssertNil(ShareInboxStorage.loadPendingShare(), "the share stayed after it was taken")
    }

    /// Texts, a page link, a file that answers under a text type (a .txt from Files) and a picture as
    /// plain data: all of them, in the order they were given, the texts that follow one another as one.
    /// The file's link travels as a property list on iOS: read as plain data it gave no file at all.
    func testEverythingSharedIsReadInTheOrderGiven() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        defer {
            try? FileManager.default.removeItem(at: directory)
        }
        let notes = directory.appendingPathComponent("notes.txt")
        try Data("Hike on Saturday at nine".utf8).write(to: notes)
        let picture = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 4)).pngData { context in
            UIColor.white.setFill()
            context.fill(CGRect(x: 0, y: 0, width: 4, height: 4))
        }

        let item = NSExtensionItem()
        item.attributedContentText = NSAttributedString(string: " A note above it ")
        item.attachments = [
            NSItemProvider(item: "Second text" as NSString, typeIdentifier: UTType.plainText.identifier),
            NSItemProvider(item: URL(string: "https://example.org/page")! as NSURL, typeIdentifier: UTType.url.identifier),
            try XCTUnwrap(NSItemProvider(contentsOf: notes)),
            NSItemProvider(item: picture as NSData, typeIdentifier: UTType.png.identifier),
        ]

        let collected = expectation(description: "collected")
        var items = [ShareInboxItem]()
        ShareInboxCollector.collect([item]) { result in
            items = result
            collected.fulfill()
        }
        wait(for: [collected], timeout: 10)

        XCTAssertEqual(items.map { $0.type }, [.text, .file, .file])
        XCTAssertEqual(items.compactMap { $0.text }, ["A note above it\nSecond text\nhttps://example.org/page"])
        XCTAssertEqual(items.compactMap { $0.fileName }, ["notes.txt", "photo.png"])
        for file in items where file.type == .file {
            let path = try XCTUnwrap(ShareInboxStorage.absolutePath(for: file))
            XCTAssertTrue(FileManager.default.fileExists(atPath: path), "\(file.fileName ?? "") is not in the inbox")
        }
        guard items.count == 3 else {
            return
        }
        let notesPath = try XCTUnwrap(ShareInboxStorage.absolutePath(for: items[1]))
        XCTAssertEqual(try String(contentsOfFile: notesPath, encoding: .utf8), "Hike on Saturday at nine")
    }

    /// Another app shared again before the question about the first share was answered: answering it
    /// takes only the first share's files, and the newer share waits for its own question.
    func testANewerShareWaitsWhenTheEarlierOneIsTaken() throws {
        let first = try XCTUnwrap(ShareInboxStorage.storeFileData(Data("one".utf8), suggestedName: "one.txt"))
        let earlier = ShareInboxPayload(version: 1, createdAt: 1, items: [first])
        XCTAssertTrue(ShareInboxStorage.savePendingShare(earlier))
        let second = try XCTUnwrap(ShareInboxStorage.storeFileData(Data("two".utf8), suggestedName: "two.txt"))
        let later = ShareInboxPayload(version: 1, createdAt: 2, items: [second])
        XCTAssertTrue(ShareInboxStorage.savePendingShare(later))
        let firstPath = try XCTUnwrap(ShareInboxStorage.absolutePath(for: first))
        let secondPath = try XCTUnwrap(ShareInboxStorage.absolutePath(for: second))

        ShareInboxStorage.clearPendingShare(earlier)
        XCTAssertEqual(ShareInboxStorage.loadPendingShare()?.createdAt, 2, "the newer share was lost")
        XCTAssertFalse(FileManager.default.fileExists(atPath: firstPath), "the earlier share's file stayed")
        XCTAssertTrue(FileManager.default.fileExists(atPath: secondPath), "the newer share's file went")

        ShareInboxStorage.clearPendingShare(later)
        XCTAssertNil(ShareInboxStorage.loadPendingShare())
        XCTAssertFalse(FileManager.default.fileExists(atPath: secondPath))
    }

    func testTextsThatFollowOneAnotherAreOneMessage() {
        let title = ShareInboxItem(type: .text, text: "Stage 5", fileName: nil, relativePath: nil)
        let link = ShareInboxItem(type: .text, text: "https://example.org/page", fileName: nil, relativePath: nil)
        let file = ShareInboxItem(type: .file, text: nil, fileName: "notes.txt", relativePath: "inbox/notes.txt")
        let joined = ShareInboxCollector.joinedTexts([title, link, file, title])
        XCTAssertEqual(joined.map { $0.type }, [.text, .file, .text])
        XCTAssertEqual(joined.compactMap { $0.text }, ["Stage 5\nhttps://example.org/page", "Stage 5"])
    }

    /// A shared text has no counter under a field: one that does not fit a message goes in parts that
    /// do, ended between words, instead of being cut short by objcTox.
    func testALongTextGoesInPartsThatFit() {
        XCTAssertEqual(AirComposeLimit.parts(of: "Salam"), ["Salam"])

        let words = Array(repeating: "привет", count: 600).joined(separator: " ")
        let parts = AirComposeLimit.parts(of: words)
        XCTAssertGreaterThan(parts.count, 1)
        for part in parts {
            XCTAssertLessThanOrEqual(part.utf8.count, AirComposeLimit.bytes)
            XCTAssertTrue(part.split(separator: " ").allSatisfy { $0 == "привет" }, "a word was cut")
        }
        XCTAssertEqual(parts.joined(separator: " "), words)

        // A line break in the second half ends the part there.
        let lines = String(repeating: "a", count: 1000) + "\n" + String(repeating: "b c ", count: 200)
        XCTAssertEqual(AirComposeLimit.parts(of: lines).first, String(repeating: "a", count: 1000))

        // Without a space, at the last whole character that fits: two bytes each here.
        let unbroken = String(repeating: "ж", count: 1000)
        let halves = AirComposeLimit.parts(of: unbroken)
        XCTAssertEqual(halves.map { $0.count }, [667, 333])
        XCTAssertEqual(halves.joined(), unbroken)
    }

    func testTheSameTextTwiceIsSentOnce() {
        let text = ShareInboxItem(type: .text, text: "Salam", fileName: nil, relativePath: nil)
        XCTAssertEqual(ShareInboxCollector.deduplicated([text, text]).count, 1)
    }

    /// QA-Live 2026-11-13: a shared text toxcore refuses waits in its chat with "Retry", as one from the
    /// composer does, instead of an alert that the closing "Send to" sheet can swallow. Without a person
    /// to send to again nothing is kept, and the caller shows the error.
    func testARefusedSharedTextWaitsInItsChatWithRetry() {
        let sends = AirFailedSends()
        XCTAssertTrue(sends.keepRefusedShare("https://khandaq.org/", toChat: "chat-a", hasPerson: true))
        XCTAssertEqual(sends.items(forChat: "chat-a").map { $0.text }, ["https://khandaq.org/"])

        XCTAssertFalse(sends.keepRefusedShare("https://khandaq.org/", toChat: "chat-b", hasPerson: false),
                       "a chat whose person was removed kept a text nobody can be sent it again")
        XCTAssertTrue(sends.items(forChat: "chat-b").isEmpty)
        XCTAssertFalse(sends.keepRefusedShare("", toChat: "chat-a", hasPerson: true))
        XCTAssertEqual(sends.items(forChat: "chat-a").count, 1)
    }

    func testALongTextIsShortenedAtAWordAboveTheChats() {
        let words = Array(repeating: "word", count: 100).joined(separator: " ")
        let payload = ShareInboxPayload(version: 1, createdAt: 1, items: [
            ShareInboxItem(type: .text, text: words, fileName: nil, relativePath: nil),
            ShareInboxItem(type: .file, text: nil, fileName: "notes.txt", relativePath: "inbox/notes.txt"),
        ])
        let lines = ShareInboxStorage.summary(of: payload).components(separatedBy: "\n")
        XCTAssertEqual(lines.count, 2)
        XCTAssertLessThanOrEqual(lines[0].count, 300)
        XCTAssertTrue(lines[0].hasSuffix("word"), "the text was cut inside a word")
        XCTAssertEqual(lines[1], "notes.txt")
    }
}
