// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Files and voice notes in the transcript (redesign stage 3, E8) as plain values: what a file entry says
 and offers in every state of its transfer, the mark of our own file, sizes in the app's language and
 the group limit said before a file is chosen.
 */
class AirFileEntryTests: XCTestCase {
    private var savedLanguage: Any?
    private var savedAppleLanguages: Any?
    private let everything: Set<AirFileAction> = [.accept, .decline, .pause, .resume, .cancel, .retry]
    private let english = Locale(identifier: "en_US")

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

    // MARK: - What an entry says and offers

    func testAFileForUsWaitsForAccept() {
        let text = make(.waitingConfirmation, incoming: true)
        XCTAssertEqual(text.actions, [.accept, .decline])
        XCTAssertNil(text.progress)
        // The size is on the line of the name; "Accept" says the rest.
        XCTAssertNil(text.note)
        XCTAssertFalse(text.noteIsProblem)
    }

    func testOurFileSaysWhatItWaitsFor() {
        let online = make(.waitingConfirmation, incoming: false, person: "Yusuf", online: true)
        XCTAssertEqual(online.note, "Waits for Yusuf to accept it")
        XCTAssertEqual(online.actions, [.cancel])

        let away = make(.waitingConfirmation, incoming: false, person: "Yusuf", online: false)
        XCTAssertEqual(away.note, "Goes when Yusuf is online")
        XCTAssertEqual(away.actions, [.cancel])

        // A group has no one person to wait for.
        XCTAssertNil(make(.waitingConfirmation, incoming: false, person: nil).note)

        use("ru")
        XCTAssertEqual(make(.waitingConfirmation, incoming: false, person: "Юсуф", online: false).note,
                       "Уйдёт, когда Юсуф будет в сети")
    }

    func testAMovingFileShowsTheBarAndWhatIsLeft() {
        let text = make(.loading, incoming: true, bytes: 12_600_000, progress: 0.5)
        XCTAssertEqual(text.progress, 0.5)
        // The decimal mark follows the phone's region, as every number the app shows.
        XCTAssertEqual(text.note, AirByteSize.text(6_300_000) + " left")
        XCTAssertEqual(AirByteSize.text(6_300_000, locale: english), "6.3 MB")
        XCTAssertEqual(text.actions, [.pause, .cancel])

        use("ru")
        XCTAssertEqual(make(.loading, incoming: true, bytes: 12_600_000, progress: 0.5).note, "Осталось 6,3 МБ")
    }

    func testPausedFailedAndDone() {
        let paused = make(.paused, incoming: true)
        XCTAssertEqual(paused.note, "Paused")
        XCTAssertEqual(paused.actions, [.resume, .cancel])
        XCTAssertNil(paused.progress)

        let stopped = make(.cancelled, incoming: true)
        XCTAssertEqual(stopped.note, "The download stopped")
        XCTAssertTrue(stopped.noteIsProblem)
        XCTAssertEqual(stopped.actions, [.retry])

        let notSent = make(.cancelled, incoming: false)
        XCTAssertEqual(notSent.note, "Not sent")
        XCTAssertTrue(notSent.noteIsProblem)

        let done = make(.done, incoming: true)
        XCTAssertEqual(done, AirFileEntryText())
    }

    func testAnActionShowsOnlyWithItsHandle() {
        // A group gives no pause: the bar and "Cancel" stay.
        let group = make(.loading, incoming: false, bytes: 1_000_000, progress: 0.2, available: [.cancel])
        XCTAssertEqual(group.actions, [.cancel])
        XCTAssertEqual(group.progress, 0.2)

        // A file another member sends to a group arrives by itself: nothing to accept.
        XCTAssertEqual(make(.waitingConfirmation, incoming: true, available: []).actions, [])
    }

    func testTheBarStaysWithinItsTrack() {
        XCTAssertEqual(make(.loading, incoming: true, bytes: 1000, progress: 1.7).progress, 1)
        XCTAssertEqual(make(.loading, incoming: true, bytes: 1000, progress: -0.3).progress, 0)
        XCTAssertEqual(make(.loading, incoming: true, bytes: 1000, progress: .nan).progress, 0)
        XCTAssertEqual(AirFileEntryText.leftText(bytesTotal: 1000, progress: 1.7), "0 B left")
        // An unknown size says nothing rather than "0 B left".
        XCTAssertNil(AirFileEntryText.leftText(bytesTotal: 0, progress: 0.5))
    }

    func testRetrySaysWhichWayTheFileGoes() {
        XCTAssertEqual(AirFileEntryText.title(for: .retry, incoming: true), "Download again")
        XCTAssertEqual(AirFileEntryText.title(for: .retry, incoming: false), "Send again")
        XCTAssertEqual(AirFileEntryText.title(for: .accept, incoming: true), "Accept")

        use("ru")
        XCTAssertEqual(AirFileEntryText.title(for: .accept, incoming: true), "Принять")
        XCTAssertEqual(AirFileEntryText.title(for: .retry, incoming: false), "Отправить снова")
    }

    func testTheMarkOfOurFile() {
        XCTAssertEqual(AirFileEntryText.status(state: .done, delivered: true, personOnline: false), .delivered)
        XCTAssertEqual(AirFileEntryText.status(state: .done, delivered: false, personOnline: true), .sent)
        XCTAssertEqual(AirFileEntryText.status(state: .waitingConfirmation, delivered: false, personOnline: true), .sending)
        XCTAssertEqual(AirFileEntryText.status(state: .waitingConfirmation, delivered: false, personOnline: false), .transit)
        XCTAssertEqual(AirFileEntryText.status(state: .loading, delivered: false, personOnline: true), .sending)
        XCTAssertEqual(AirFileEntryText.status(state: .paused, delivered: false, personOnline: true), .sending)
        XCTAssertEqual(AirFileEntryText.status(state: .cancelled, delivered: false, personOnline: true), .failed)
    }

    // MARK: - Sizes

    func testSizesCountInThousandsAsTheFilesAppDoes() {
        XCTAssertEqual(AirByteSize.text(0, locale: english), "0 B")
        XCTAssertEqual(AirByteSize.text(-5, locale: english), "0 B")
        XCTAssertEqual(AirByteSize.text(999, locale: english), "999 B")
        XCTAssertEqual(AirByteSize.text(1000, locale: english), "1 KB")
        XCTAssertEqual(AirByteSize.text(154_300, locale: english), "154 KB")
        XCTAssertEqual(AirByteSize.text(12_600_000, locale: english), "12.6 MB")
        XCTAssertEqual(AirByteSize.text(3_000_000, locale: english), "3 MB")
        XCTAssertEqual(AirByteSize.text(1_250_000_000, locale: english), "1.25 GB")
        XCTAssertEqual(AirByteSize.text(2_000_000_000_000, locale: english), "2,000 GB")
    }

    func testASizeThatRoundsUpTakesTheNextUnit() {
        XCTAssertEqual(AirByteSize.text(999_600, locale: english), "1 MB")
        XCTAssertEqual(AirByteSize.text(999_960_000, locale: english), "1 GB")
        XCTAssertEqual(AirByteSize.text(999_400, locale: english), "999 KB")
    }

    func testSizesSpeakTheAppLanguage() {
        use("ru")
        XCTAssertEqual(AirByteSize.text(12_600_000, locale: Locale(identifier: "ru_RU")), "12,6 МБ")
        XCTAssertEqual(AirByteSize.text(512, locale: Locale(identifier: "ru_RU")), "512 Б")

        use("ar")
        // Western digits, as everywhere else in the app.
        let arabic = AirByteSize.text(12_600_000, locale: Locale(identifier: "ar@numbers=latn"))
        XCTAssertTrue(arabic.contains("12"), arabic)
        XCTAssertFalse(arabic.contains("MB"), arabic)

        use("zh")
        XCTAssertEqual(AirByteSize.text(12_600_000, locale: Locale(identifier: "zh_CN")), "12.6 MB")
    }

    func testTheGroupLimitIsSaidBeforeAFileIsChosen() {
        XCTAssertEqual(AirFileLimits.groupMaxMegabytes, 200)
        XCTAssertEqual(AirFileLimits.groupHint, "A group takes files up to 200 MB")

        use("ru")
        XCTAssertEqual(AirFileLimits.groupHint, "В группу можно отправить файл до 200 МБ")
    }
}

// MARK: - Fixtures

private extension AirFileEntryTests {
    func use(_ language: String) {
        AppLanguage.set(language)
        XCTAssertEqual(AppLanguage.current, language)
    }

    func make(_ state: ChatGenericFileCellModel.State,
              incoming: Bool,
              bytes: Int64 = 0,
              progress: Float = 0,
              available: Set<AirFileAction>? = nil,
              person: String? = nil,
              online: Bool = true) -> AirFileEntryText {
        return AirFileEntryText.make(state: state,
                                     incoming: incoming,
                                     bytesTotal: bytes,
                                     progress: progress,
                                     available: available ?? everything,
                                     personName: person,
                                     personOnline: online)
    }
}

/**
 A file entry for VoiceOver (stage 5): a double tap opens what is here, and a tap meant for the entry lands
 on its preview. VoiceOver asked the entry, not the cell, so it tapped the middle of the row instead, which
 at the largest text sizes fell beside the photo and opened nothing.
 */
class ChatFileEntryActivationTests: CellSnapshotTest {
    func testADoubleTapOpensThePhotoAndLandsOnItsPreview() throws {
        // Points on the screen exist only for a window of a scene, as the conversation's is.
        guard let scene = UIApplication.shared.connectedScenes.first(where: { $0 is UIWindowScene }) as? UIWindowScene else {
            throw XCTSkip("the test host has no window scene")
        }
        var opened = 0
        let model = fileEntry(ChatOutgoingFileCellModel(), state: .done, name: "north-exit.jpg", bytes: 6_000, uti: "public.jpeg")
        model.caption = "Meet here, at the north exit"
        model.openHandle = {
            opened += 1
        }
        let cell = ChatOutgoingFileCell()
        cell.setupWithTheme(theme, model: model)
        cell.setButtonImage(image)
        let window = UIWindow(windowScene: scene)
        window.frame = CGRect(x: 0, y: 0, width: 320, height: 800)
        window.addSubview(cell)
        window.isHidden = false
        defer {
            window.isHidden = true
        }
        layoutAsTheLogDoes(cell)

        guard let entry = cell.accessibilityElements?.first as? UIAccessibilityElement else {
            return XCTFail("the file has no spoken entry")
        }
        let preview: UIView = cell.loadingView
        let previewOnScreen = window.convert(preview.convert(preview.bounds, to: nil), to: window.screen.coordinateSpace)
        XCTAssertTrue(previewOnScreen.contains(entry.accessibilityActivationPoint),
                      "a tap on the entry lands beside the photo: \(entry.accessibilityActivationPoint) outside \(previewOnScreen)")
        XCTAssertTrue(entry.accessibilityActivate(), "a double tap on the entry does nothing")
        XCTAssertEqual(opened, 1, "the photo did not open")
    }
}
