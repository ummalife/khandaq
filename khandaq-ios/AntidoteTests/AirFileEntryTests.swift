// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import ImageIO
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

    /// KHANDAQ (0.2.57): a file the lost connection cut keeps its bar and waits; it never says failed.
    func testAFileCutByTheConnectionWaitsWithWhatHasMovedNeverFailed() {
        for incoming in [true, false] {
            let text = make(.waitingConnection, incoming: incoming, bytes: 60_000_000, progress: 0.4)
            XCTAssertEqual(text.progress, 0.4)
            XCTAssertEqual(text.note, "Waiting for the connection")
            XCTAssertFalse(text.noteIsProblem)
            XCTAssertEqual(text.actions, [.cancel], "Cancel still works")
        }
        XCTAssertEqual(make(.waitingConnection, incoming: true, progress: 1.4).progress, 1)
        XCTAssertEqual(AirFileEntryText.status(state: .waitingConnection, delivered: false, personOnline: true), .transit)
        XCTAssertEqual(AirFileEntryText.status(state: .waitingConnection, delivered: false, personOnline: false), .transit)

        use("ru")
        XCTAssertEqual(make(.waitingConnection, incoming: true, progress: 0.4).note, "Ждёт связи")
    }

    /// KHANDAQ (0.2.57): our file the connection cut, once offered again while the person is online, waits for
    /// their accept: it said "Waiting for the connection" with the person online until they took it.
    func testOurFileOfferedAgainWhileThePersonIsOnlineWaitsForTheirAccept() {
        let offeredAgain = ChatGenericFileCellModel.waitingState(cutByConnection: true, outgoing: true, offered: true)
        XCTAssertEqual(offeredAgain, .waitingConfirmation)
        XCTAssertEqual(make(offeredAgain, incoming: false, person: "Yusuf", online: true).note, "Waits for Yusuf to accept it")
        XCTAssertEqual(AirFileEntryText.status(state: offeredAgain, delivered: false, personOnline: true), .sending)

        // Not offered yet, or theirs (accepted here): it waits for the connection with its bar. Never cut: it
        // waits for the accept as before.
        let others: [(cut: Bool, outgoing: Bool, offered: Bool, state: ChatGenericFileCellModel.State)] = [
            (true, true, false, .waitingConnection),
            (true, false, true, .waitingConnection),
            (true, false, false, .waitingConnection),
            (false, true, true, .waitingConfirmation),
            (false, false, true, .waitingConfirmation),
        ]
        for other in others {
            let state = ChatGenericFileCellModel.waitingState(cutByConnection: other.cut,
                                                              outgoing: other.outgoing,
                                                              offered: other.offered)
            XCTAssertEqual(state, other.state, "\(other)")
        }
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

    /// A file without a preview opens with a tap on its row once it is here (0.2.61). The row had only the
    /// buttons of the transfer, so a received file that was here opened nothing at all.
    func testATapOnTheRowOfAFileThatIsHereOpensIt() {
        var opened = 0
        let model = fileEntry(ChatIncomingFileCellModel(), state: .done, name: "VID_20251204_233311_545.mp4",
                              bytes: 28_800_000, uti: "public.mpeg-4")
        model.openHandle = {
            opened += 1
        }
        let cell = ChatIncomingFileCell()
        cell.setupWithTheme(theme, model: model)
        layoutAsTheLogDoes(cell)

        XCTAssertFalse(cell.fileRow.isHidden, "a file without a preview shows no row")
        XCTAssertTrue(cell.fileRow.isUserInteractionEnabled, "the row takes no touch")
        let taps = (cell.fileRow.gestureRecognizers ?? []).compactMap { $0 as? UITapGestureRecognizer }
        XCTAssertEqual(taps.count, 1, "the row has no tap of its own")
        XCTAssertEqual(taps.first?.isEnabled, true)

        cell.fileRowTapped()
        XCTAssertEqual(opened, 1, "a tap on the row of a file that is here opened nothing")
    }

    func testATapOnTheRowOfAFileNotHereYetOpensNothing() {
        var opened = 0
        let states: [ChatGenericFileCellModel.State] = [.waitingConfirmation, .waitingConnection, .loading, .paused, .cancelled]
        for state in states {
            let model = fileEntry(ChatIncomingFileCellModel(), state: state)
            model.openHandle = {
                opened += 1
            }
            let cell = ChatIncomingFileCell()
            cell.setupWithTheme(theme, model: model)
            cell.fileRowTapped()
        }
        XCTAssertEqual(opened, 0, "a file that is not here yet opened")
    }
}

/**
 The preview of a photo of any size (0.2.61): its size as it is seen, turned by its EXIF orientation, and a
 picture read down to the side of the preview, so a large photo shows as a photo instead of a file row.
 */
class ChatFileMediaLoaderTests: XCTestCase {
    private var directory: URL!

    override func setUpWithError() throws {
        try super.setUpWithError()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
        try super.tearDownWithError()
    }

    /// A JPEG of `width` × `height` stored pixels with the EXIF `orientation`.
    private func jpeg(width: Int, height: Int, orientation: Int) throws -> String {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let size = CGSize(width: width, height: height)
        let picture = UIGraphicsImageRenderer(size: size, format: format).image { context in
            UIColor.darkGray.setFill()
            context.fill(CGRect(origin: .zero, size: size))
        }
        let url = directory.appendingPathComponent("photo-\(width)x\(height)-\(orientation).jpg")
        guard let cgImage = picture.cgImage,
              let destination = CGImageDestinationCreateWithURL(url as CFURL, "public.jpeg" as CFString, 1, nil) else {
            throw XCTSkip("ImageIO cannot write a JPEG here")
        }
        CGImageDestinationAddImage(destination, cgImage, [kCGImagePropertyOrientation: orientation] as CFDictionary)
        XCTAssertTrue(CGImageDestinationFinalize(destination), "the JPEG was not written")
        return url.path
    }

    func testAPhotoTurnedByItsOrientationIsAsTallAsItIsShown() throws {
        XCTAssertEqual(ChatFileMediaLoader.imagePixelSize(at: try jpeg(width: 400, height: 300, orientation: 1)),
                       CGSize(width: 400, height: 300))
        XCTAssertEqual(ChatFileMediaLoader.imagePixelSize(at: try jpeg(width: 400, height: 300, orientation: 6)),
                       CGSize(width: 300, height: 400), "a portrait taken on its side is measured on its side")
    }

    func testALargePhotoIsReadDownToThePreviewSideAndTurned() throws {
        let path = try jpeg(width: 2000, height: 1500, orientation: 6)
        guard let preview = ChatFileMediaLoader.downsampledImage(at: path, maxPixelSide: 540) else {
            return XCTFail("a large photo has no preview")
        }
        XCTAssertEqual(preview.size, CGSize(width: 405, height: 540), "the preview is not read down or not turned")
    }

    func testAFileThatIsNoPhotoHasNoSizeAndNoPreview() throws {
        let url = directory.appendingPathComponent("notes.txt")
        try Data("not a picture".utf8).write(to: url)
        XCTAssertNil(ChatFileMediaLoader.imagePixelSize(at: url.path))
        XCTAssertNil(ChatFileMediaLoader.downsampledImage(at: url.path, maxPixelSide: 540))
    }
}

/**
 The banner of a video being prepared (large media): it stays up until its job ends, with the progress,
 and the end of an older job leaves the banner of a newer one alone. It used to take itself down the
 moment it came up, so a long preparation looked like nothing was happening.
 */
class VideoSendBannerTests: XCTestCase {
    func testTheBannerStaysUpUntilItsOwnJobEnds() {
        let host = UIViewController()
        host.loadViewIfNeeded()
        let overlay = VideoSendProgressOverlay.shared
        defer {
            overlay.hide()
            drainMainQueue()
        }

        let first = overlay.show(on: host, message: "Preparing video...")
        // Twice: a hide() queued from inside show() would run after a single drain.
        drainMainQueue()
        drainMainQueue()
        XCTAssertEqual(progressViews(in: host.view).count, 1, "the banner took itself down")

        let second = overlay.show(on: host, message: "Preparing video...")
        drainMainQueue()
        drainMainQueue()
        XCTAssertNotEqual(first, second)
        XCTAssertEqual(progressViews(in: host.view).count, 1, "one banner in place of the other")

        overlay.update(progress: 0.5, banner: second)
        overlay.update(progress: 0.9, banner: first)
        drainMainQueue()
        XCTAssertEqual(progressViews(in: host.view).first?.progress ?? 0, 0.5, accuracy: 0.001)

        overlay.hide(banner: first)
        drainMainQueue()
        XCTAssertEqual(progressViews(in: host.view).count, 1, "the end of the older job took the newer banner down")

        overlay.hide(banner: second)
        drainMainQueue()
        XCTAssertTrue(progressViews(in: host.view).isEmpty)
    }

    /// Cancel on the banner ends the preparation without an alert: the person asked for it.
    func testACancelledPreparationSaysNothing() {
        XCTAssertTrue(VideoSendError.isCancellation(VideoSendError.cancelled))
        XCTAssertNil(VideoSendError.cancelled.errorDescription)
        XCTAssertFalse(VideoSendError.isCancellation(VideoSendError.preparationFailed))
        XCTAssertFalse(VideoSendError.isCancellation(NSError(domain: NSCocoaErrorDomain, code: NSUserCancelledError)))
    }

    private func drainMainQueue() {
        let drained = expectation(description: "the main queue ran what was queued before")
        DispatchQueue.main.async {
            drained.fulfill()
        }
        wait(for: [drained], timeout: 2)
    }

    private func progressViews(in view: UIView) -> [UIProgressView] {
        return view.subviews.compactMap { $0 as? UIProgressView } + view.subviews.flatMap { progressViews(in: $0) }
    }
}

/**
 A video belongs to the chat that prepares it (review 0.2.56). The preprocessor and the banner serve every
 chat, and a 1:1 chat screen that went away stopped whatever video was being prepared and took whatever
 banner was up down, another chat's too: that video ended as cancelled, and its chat took every later
 video for one still in progress. A text held its screen for the ten seconds until its wake-up signal,
 so the screen went away that late, while another chat could be preparing its video.
 */
class VideoSendOwnershipTests: XCTestCase {
    private var directories: [URL] = []
    /// What the input manager holds weakly: its submanagers and its screen.
    private var kept: [AnyObject] = []

    override func tearDown() {
        VideoSendProgressOverlay.shared.hide()
        drainMainQueue()
        directories.forEach { try? FileManager.default.removeItem(at: $0) }
        directories = []
        kept = []
        super.tearDown()
    }

    func testAChatScreenThatGoesAwayLeavesTheBannerOfAnotherChatUp() throws {
        var screen: ChatInputViewManager? = try chatScreen()
        weak var goneScreen: ChatInputViewManager?
        goneScreen = screen
        let host = UIViewController()
        host.loadViewIfNeeded()
        VideoSendProgressOverlay.shared.show(on: host, message: "Preparing video...")
        drainMainQueue()
        drainMainQueue()
        XCTAssertEqual(progressViews(in: host.view).count, 1)

        screen = nil
        XCTAssertNil(goneScreen)
        drainMainQueue()
        drainMainQueue()
        XCTAssertEqual(progressViews(in: host.view).count, 1, "a chat screen that went away took the banner of another down")
    }

    func testAChatScreenThatGoesAwayLetsAnotherChatPrepareItsVideo() throws {
        var screen: ChatInputViewManager? = try chatScreen()
        weak var goneScreen: ChatInputViewManager?
        goneScreen = screen
        let source = try clip()
        let ended = expectation(description: "the preparation ended")
        var outcome: Result<URL, Error>?
        VideoSendPreprocessor.shared.prepareVideo(at: source, progress: { _ in }) { result in
            outcome = result
            ended.fulfill()
        }
        screen = nil
        XCTAssertNil(goneScreen)
        wait(for: [ended], timeout: 20)

        guard case .success(let prepared)? = outcome else {
            return XCTFail("a chat screen that went away stopped the video of another: \(String(describing: outcome))")
        }
        try? FileManager.default.removeItem(at: prepared)
    }

    func testAPreparationIsCancelledByItsOwnNumberOnly() throws {
        let preprocessor = VideoSendPreprocessor.shared
        let firstSource = try clip()
        let secondSource = try clip()

        let first = expectation(description: "the first preparation ended")
        var firstOutcome: Result<URL, Error>?
        let number = preprocessor.prepareVideo(at: firstSource, progress: { _ in }) { result in
            firstOutcome = result
            first.fulfill()
        }
        preprocessor.cancelPreparation(number + 1)
        wait(for: [first], timeout: 20)
        guard case .success(let prepared)? = firstOutcome else {
            return XCTFail("the cancel of another preparation stopped this one: \(String(describing: firstOutcome))")
        }
        try? FileManager.default.removeItem(at: prepared)

        let second = expectation(description: "the second preparation ended")
        var secondOutcome: Result<URL, Error>?
        let own = preprocessor.prepareVideo(at: secondSource, progress: { _ in }) { result in
            secondOutcome = result
            second.fulfill()
        }
        preprocessor.cancelPreparation(own)
        wait(for: [second], timeout: 20)
        XCTAssertNotEqual(own, number)
        guard case .failure(let error)? = secondOutcome else {
            return XCTFail("a cancelled preparation handed a file over: \(String(describing: secondOutcome))")
        }
        XCTAssertTrue(VideoSendError.isCancellation(error), "\(error)")
    }

    func testATextThatWentDoesNotHoldItsChatScreen() throws {
        var screen: ChatInputViewManager? = try chatScreen()
        weak var goneScreen: ChatInputViewManager?
        goneScreen = screen
        let sent = expectation(description: "the text went")
        screen?.resend("Assalamu alaikum") { taken in
            XCTAssertTrue(taken)
            sent.fulfill()
        }
        wait(for: [sent], timeout: 2)

        screen = nil
        XCTAssertNil(goneScreen, "the wake-up signal ten seconds after the text holds the chat screen until then")
    }

    // MARK: - Helpers

    /// The input manager of a 1:1 chat screen, over the mocks of the UI tests and a profile of its own.
    private func chatScreen() throws -> ChatInputViewManager {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("video-send-owner-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true, attributes: nil)
        directories.append(directory)
        let realmManagerClass = try appClass("OCTRealmManager", as: OCTRealmManager.Type.self)
        let realmManager = try XCTUnwrap(realmManagerClass.init(databaseFileURL: directory.appendingPathComponent("profile.realm"),
                                                                encryptionKey: nil))
        let chat = try appClass("OCTChat", as: OCTChat.Type.self).init()
        realmManager.add(chat)
        let realm: RLMRealm = realmManager.realm

        let path = try XCTUnwrap(Bundle.main.path(forResource: "default-theme", ofType: "yaml"))
        let theme = try Theme(yamlString: String(contentsOfFile: path, encoding: .utf8))
        let input = ChatInputView(theme: theme)
        let chats = OCTSubmanagerChatsMock(realm: realm)
        let files = OCTSubmanagerFilesMock()
        let objects = OCTSubmanagerObjectsMock(realm: realm)
        let presenter = UIViewController()
        presenter.loadViewIfNeeded()
        kept += [realmManager, input, chats, files, objects, presenter]
        return ChatInputViewManager(inputView: input,
                                    theme: theme,
                                    chat: chat,
                                    submanagerChats: chats,
                                    submanagerFiles: files,
                                    submanagerObjects: objects,
                                    presentingViewController: presenter)
    }

    /// A file the preprocessor has nothing to transcode in: it hands it over as it is.
    private func clip() throws -> URL {
        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent(UUID().uuidString).appendingPathExtension("mov")
        try Data(repeating: 0x5A, count: 64 * 1024).write(to: url)
        return url
    }

    private func appClass<T>(_ name: String, as type: T.Type) throws -> T {
        let cls: AnyClass = try XCTUnwrap(NSClassFromString(name), "no class \(name)")
        return unsafeBitCast(cls, to: type)
    }

    private func drainMainQueue() {
        let drained = expectation(description: "the main queue ran what was queued before")
        DispatchQueue.main.async {
            drained.fulfill()
        }
        wait(for: [drained], timeout: 2)
    }

    private func progressViews(in view: UIView) -> [UIProgressView] {
        return view.subviews.compactMap { $0 as? UIProgressView } + view.subviews.flatMap { progressViews(in: $0) }
    }
}
