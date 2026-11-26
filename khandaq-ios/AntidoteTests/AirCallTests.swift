// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Calls (redesign stage 3, E9) as plain values: the call in the transcript, its length in tabular
 figures, what the call button says about someone offline, the link line of the call screen, and the
 words of the controls VoiceOver reads.
 */
class AirCallTests: XCTestCase {
    private var savedLanguage: Any?
    private var savedAppleLanguages: Any?

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

    func testTheLengthOfACall() {
        XCTAssertEqual(AirCallText.duration(0), "0:00")
        XCTAssertEqual(AirCallText.duration(-3), "0:00")
        XCTAssertEqual(AirCallText.duration(.nan), "0:00")
        XCTAssertEqual(AirCallText.duration(42.9), "0:42")
        XCTAssertEqual(AirCallText.duration(725), "12:05")
        XCTAssertEqual(AirCallText.duration(3729), "1:02:09")
    }

    func testACallInTheTranscript() {
        XCTAssertEqual(AirCallText.entry(answered: true, duration: 252, incoming: true), "Call, 4:12")
        XCTAssertEqual(AirCallText.entry(answered: true, duration: 1473, incoming: false), "Call, 24:33")
        // Theirs that nobody answered is missed; ours simply had no answer.
        XCTAssertEqual(AirCallText.entry(answered: false, duration: 0, incoming: true), "Missed call")
        XCTAssertEqual(AirCallText.entry(answered: false, duration: 0, incoming: false), "No answer")

        use("ru")
        XCTAssertEqual(AirCallText.entry(answered: true, duration: 252, incoming: true), "Звонок, 4:12")
        XCTAssertEqual(AirCallText.entry(answered: false, duration: 0, incoming: true), "Пропущенный звонок")
    }

    func testTheListSaysOfACallWhatTheTranscriptSays() {
        // A message of the store cannot be made here (its links are not Realm types in the test bundle),
        // so the call goes in as it lies in the message.
        let call = OCTMessageCall()
        call.callEvent = .answered
        call.callDuration = 1473
        // The forks wrote "Call finished - 24:33" under the name, a hyphen standing for a dash.
        XCTAssertEqual(ChatListContent.callPreview(call, outgoing: false), ChatListContent.Preview(text: "Call, 24:33", kind: .message))
        XCTAssertEqual(ChatListContent.callPreview(call, outgoing: true).text, "Call, 24:33")
        call.callEvent = .unanswered
        XCTAssertEqual(ChatListContent.callPreview(call, outgoing: false).text, "Missed call")
        XCTAssertEqual(ChatListContent.callPreview(call, outgoing: true).text, "No answer")

        use("ru")
        call.callEvent = .answered
        XCTAssertEqual(ChatListContent.callPreview(call, outgoing: false).text, "Звонок, 24:33")
    }

    func testTheCallButtonSaysWhyACallWillNotGetThrough() {
        XCTAssertEqual(AirCallText.offlineTitle(name: "Yusuf"), "Yusuf is offline: the call will not get through")
        // With a push token their phone is woken first, with a signal that carries no text.
        XCTAssertTrue(AirCallText.offlineDetail(hasPush: true).contains("no text"))
        XCTAssertFalse(AirCallText.offlineDetail(hasPush: false).contains("signal"))

        use("ru")
        XCTAssertEqual(AirCallText.offlineTitle(name: "Юсуф"), "Юсуф не в сети: звонок не дойдёт")
        XCTAssertEqual(AirCallText.waiting(name: "Юсуф"), "Ждём, когда Юсуф появится в сети")
    }

    func testTheLinkLineOfTheCallScreen() {
        XCTAssertEqual(AirCallText.line(.direct), "Direct link")
        XCTAssertEqual(AirCallText.line(.relay), "Via a relay")
        XCTAssertEqual(AirCallText.line(.none), "No link right now")
        XCTAssertEqual(AirLinkState(friend: nil), .none)
    }

    func testTheControlsSayWhatTheyAreAndWhetherTheyAreOn() {
        let mute = CallButton(theme: nil, type: .mute, buttonSize: .small)
        XCTAssertEqual(mute.accessibilityLabel, "Microphone")
        XCTAssertEqual(mute.accessibilityValue, "on")
        // The microphone switch is selected while the microphone is muted.
        mute.isSelected = true
        XCTAssertEqual(mute.accessibilityValue, "off")

        let camera = CallButton(theme: nil, type: .video, buttonSize: .small)
        XCTAssertEqual(camera.accessibilityValue, "off")
        camera.isSelected = true
        XCTAssertEqual(camera.accessibilityValue, "on")

        let end = CallButton(theme: nil, type: .decline, buttonSize: .small)
        XCTAssertEqual(end.accessibilityLabel, "End")
        XCTAssertNil(end.accessibilityValue)
        XCTAssertEqual(end.intrinsicContentSize, CGSize(width: 64, height: 64))
    }

    func testAControlKeepsItsWordUnderTheCircle() {
        let control = AirCallControl(button: CallButton(theme: nil, type: .decline, buttonSize: .big), title: "Decline")
        XCTAssertEqual(control.button.accessibilityLabel, "Decline")
        control.title = "Cancel"
        XCTAssertEqual(control.button.accessibilityLabel, "Cancel")
    }

    private func use(_ language: String) {
        AppLanguage.set(language)
        XCTAssertEqual(AppLanguage.current, language)
    }
}

/**
 KHANDAQ (calls stage, 26.11): the geometry of a video call by the rules all three clients follow
 (calls stage SPEC, R1 to R6), in exact numbers: the scale mode of a frame, the rect it takes, the
 size and the corners of your own picture, where a release snaps it and which video is the big one.
 */
class AirCallStageTests: XCTestCase {
    private let portraitFrame = CGSize(width: 720, height: 1280)
    private let landscapeFrame = CGSize(width: 1280, height: 720)

    func testAFrameFillsAStageOfItsOrientationAndFitsTheOther() {
        let phone = CGSize(width: 393, height: 852)
        let turned = CGSize(width: 852, height: 393)
        XCTAssertEqual(AirCallStage.scaleMode(frame: portraitFrame, stage: phone), .fill)
        XCTAssertEqual(AirCallStage.scaleMode(frame: landscapeFrame, stage: phone), .fit)
        XCTAssertEqual(AirCallStage.scaleMode(frame: landscapeFrame, stage: turned), .fill)
        XCTAssertEqual(AirCallStage.scaleMode(frame: portraitFrame, stage: turned), .fit)

        // A square counts as landscape, the frame and the stage alike.
        let square = CGSize(width: 640, height: 640)
        XCTAssertEqual(AirCallStage.scaleMode(frame: square, stage: phone), .fit)
        XCTAssertEqual(AirCallStage.scaleMode(frame: square, stage: turned), .fill)
        XCTAssertEqual(AirCallStage.scaleMode(frame: landscapeFrame, stage: CGSize(width: 600, height: 600)), .fill)
        XCTAssertEqual(AirCallStage.scaleMode(frame: portraitFrame, stage: CGSize(width: 600, height: 600)), .fit)
    }

    func testAFrameKeepsItsShapeWhetherItFillsOrFits() {
        let stage = CGRect(x: 0, y: 0, width: 393, height: 852)

        // Landscape on a portrait phone: the full width, black bars above and below.
        let fit = AirCallStage.contentRect(frame: landscapeFrame, in: stage, mode: .fit)
        XCTAssertEqual(fit.origin.x, 0, accuracy: 0.001)
        XCTAssertEqual(fit.origin.y, 315.46875, accuracy: 0.001)
        XCTAssertEqual(fit.width, 393, accuracy: 0.001)
        XCTAssertEqual(fit.height, 221.0625, accuracy: 0.001)

        // Portrait on a portrait phone: the full height, the sides cropped evenly.
        let fill = AirCallStage.contentRect(frame: portraitFrame, in: stage, mode: .fill)
        XCTAssertEqual(fill.origin.x, -43.125, accuracy: 0.001)
        XCTAssertEqual(fill.origin.y, 0, accuracy: 0.001)
        XCTAssertEqual(fill.width, 479.25, accuracy: 0.001)
        XCTAssertEqual(fill.height, 852, accuracy: 0.001)

        // The other person's landscape frame covering the upright box when swapped.
        let box = CGRect(x: 0, y: 0, width: 102, height: 181)
        let cover = AirCallStage.contentRect(frame: landscapeFrame, in: box, mode: .fill)
        XCTAssertEqual(cover.height, 181, accuracy: 0.001)
        XCTAssertEqual(cover.width, 321.7778, accuracy: 0.001)
        XCTAssertEqual(cover.midX, 51, accuracy: 0.001)
        XCTAssertEqual(cover.width / cover.height, 1280.0 / 720.0, accuracy: 0.0001)

        // No frame yet: the slot itself.
        XCTAssertEqual(AirCallStage.contentRect(frame: .zero, in: stage, mode: .fit), stage)
    }

    func testTheCameraFrameStandsAsItsPreviewTurnsIt() {
        XCTAssertEqual(AirCallStage.shownFrame(landscapeFrame, rotationAngle: 90), portraitFrame)
        XCTAssertEqual(AirCallStage.shownFrame(landscapeFrame, rotationAngle: 270), portraitFrame)
        XCTAssertEqual(AirCallStage.shownFrame(landscapeFrame, rotationAngle: 0), landscapeFrame)
        XCTAssertEqual(AirCallStage.shownFrame(landscapeFrame, rotationAngle: 180), landscapeFrame)
    }

    func testYourPictureTakesItsSizeFromTheStageAndItsShapeFromTheCamera() {
        // The examples of R3, a 9:16 frame on a phone held upright.
        XCTAssertEqual(AirCallStage.ownSize(frame: portraitFrame, stage: CGSize(width: 320, height: 568)), CGSize(width: 88, height: 156))
        XCTAssertEqual(AirCallStage.ownSize(frame: portraitFrame, stage: CGSize(width: 375, height: 667)), CGSize(width: 98, height: 174))
        XCTAssertEqual(AirCallStage.ownSize(frame: portraitFrame, stage: CGSize(width: 393, height: 852)), CGSize(width: 102, height: 181))
        XCTAssertEqual(AirCallStage.ownSize(frame: portraitFrame, stage: CGSize(width: 430, height: 932)), CGSize(width: 112, height: 199))
        XCTAssertEqual(AirCallStage.ownSize(frame: portraitFrame, stage: CGSize(width: 820, height: 1180)), CGSize(width: 168, height: 299))

        // A landscape frame gives a landscape picture (R11); the short side still comes from the stage.
        XCTAssertEqual(AirCallStage.ownSize(frame: landscapeFrame, stage: CGSize(width: 852, height: 393)), CGSize(width: 181, height: 102))
        XCTAssertEqual(AirCallStage.ownSize(frame: landscapeFrame, stage: CGSize(width: 1180, height: 820)), CGSize(width: 299, height: 168))

        // Before the camera says its shape: 3:4, upright.
        XCTAssertEqual(AirCallStage.ownSize(frame: nil, stage: CGSize(width: 393, height: 852)), CGSize(width: 102, height: 136))
        XCTAssertEqual(AirCallStage.ownSize(frame: .zero, stage: CGSize(width: 320, height: 568)), CGSize(width: 88, height: 117))
    }

    func testYourPictureStandsInACornerClearOfTheHeadAndTheRow() {
        let stage = CGRect(x: 0, y: 0, width: 393, height: 852)
        let area = AirCallStage.ownArea(stage: stage, safeInsets: UIEdgeInsets(top: 59, left: 0, bottom: 34, right: 0))
        XCTAssertEqual(area, CGRect(x: 16, y: 75, width: 361, height: 727))

        let size = CGSize(width: 102, height: 181)
        // Nothing in the way: 16 in from the safe edges.
        XCTAssertEqual(AirCallStage.ownRect(size: size, corner: .bottomRight, area: area, obstacles: []),
                       CGRect(x: 275, y: 621, width: 102, height: 181))
        XCTAssertEqual(AirCallStage.ownRect(size: size, corner: .topLeft, area: area, obstacles: []),
                       CGRect(x: 16, y: 75, width: 102, height: 181))

        let row = CGRect(x: 16, y: 700, width: 361, height: 86)
        let head = CGRect(x: 100, y: 99, width: 193, height: 110)
        let obstacles = [row, head]
        // 16 above the row, 16 below the head.
        XCTAssertEqual(AirCallStage.ownRect(size: size, corner: .bottomRight, area: area, obstacles: obstacles),
                       CGRect(x: 275, y: 503, width: 102, height: 181))
        XCTAssertEqual(AirCallStage.ownRect(size: size, corner: .bottomLeft, area: area, obstacles: obstacles),
                       CGRect(x: 16, y: 503, width: 102, height: 181))
        XCTAssertEqual(AirCallStage.ownRect(size: size, corner: .topRight, area: area, obstacles: obstacles),
                       CGRect(x: 275, y: 225, width: 102, height: 181))
        XCTAssertEqual(AirCallStage.ownRect(size: size, corner: .topLeft, area: area, obstacles: obstacles),
                       CGRect(x: 16, y: 225, width: 102, height: 181))

        // A short name leaves the top corner free beside it.
        let shortHead = CGRect(x: 160, y: 99, width: 73, height: 110)
        XCTAssertEqual(AirCallStage.ownRect(size: size, corner: .topRight, area: area, obstacles: [row, shortHead]),
                       CGRect(x: 275, y: 75, width: 102, height: 181))

        // Bottom trailing by default: bottom right, bottom left in a right-to-left language.
        XCTAssertEqual(AirCallStage.Corner.bottomTrailing(rightToLeft: false), .bottomRight)
        XCTAssertEqual(AirCallStage.Corner.bottomTrailing(rightToLeft: true), .bottomLeft)
    }

    func testYourPictureStaysOnTheStageWhereThereIsNoRoomToClearEverything() {
        // A phone on its side with a wide head: above the row the picture would cross the head, above
        // the head it would leave the stage, so it stops at the top of the area.
        let area = CGRect(x: 16, y: 16, width: 536, height: 288)
        let size = CGSize(width: 156, height: 88)
        let row = CGRect(x: 92, y: 202, width: 384, height: 86)
        let head = CGRect(x: 16, y: 40, width: 536, height: 130)
        let rect = AirCallStage.ownRect(size: size, corner: .bottomRight, area: area, obstacles: [row, head])
        XCTAssertEqual(rect, CGRect(x: 396, y: 16, width: 156, height: 88))
        XCTAssertTrue(area.contains(rect))
    }

    func testAReleaseSnapsToTheCornerItsSpeedCarriesTheBoxTo() {
        XCTAssertEqual(AirCallStage.projected(CGPoint(x: 10, y: 20), velocity: CGPoint(x: 100, y: -200)), CGPoint(x: 25, y: -10))

        let area = CGRect(x: 16, y: 75, width: 361, height: 727)
        let size = CGSize(width: 102, height: 181)
        // Let go still: the nearest corner.
        XCTAssertEqual(AirCallStage.snapCorner(center: CGPoint(x: 200, y: 300), velocity: .zero, size: size, area: area, obstacles: []),
                       .topRight)
        XCTAssertEqual(AirCallStage.snapCorner(center: CGPoint(x: 180, y: 600), velocity: .zero, size: size, area: area, obstacles: []),
                       .bottomLeft)
        // Flicked down and to the left: 0.15 s of the speed carries it to (-100, 750).
        XCTAssertEqual(AirCallStage.snapCorner(center: CGPoint(x: 200, y: 300), velocity: CGPoint(x: -2000, y: 3000), size: size,
                                               area: area, obstacles: []),
                       .bottomLeft)
        // Flicked up from the bottom right corner.
        XCTAssertEqual(AirCallStage.snapCorner(center: CGPoint(x: 326, y: 711.5), velocity: CGPoint(x: 0, y: -4000), size: size,
                                               area: area, obstacles: []),
                       .topRight)
    }

    func testATapSwapsTheBigAndTheSmallVideoOnlyWhenThereAreTwo() {
        let normal = AirCallStage.placement(remote: true, own: true, swapped: false)
        XCTAssertEqual(normal.stage, .remote)
        XCTAssertEqual(normal.box, .own)

        let swapped = AirCallStage.placement(remote: true, own: true, swapped: true)
        XCTAssertEqual(swapped.stage, .own)
        XCTAssertEqual(swapped.box, .remote)

        let remoteOnly = AirCallStage.placement(remote: true, own: false, swapped: true)
        XCTAssertEqual(remoteOnly.stage, .remote)
        XCTAssertNil(remoteOnly.box)

        // Your camera before the other person's video: in the box, over the dark ground.
        let ownOnly = AirCallStage.placement(remote: false, own: true, swapped: false)
        XCTAssertNil(ownOnly.stage)
        XCTAssertEqual(ownOnly.box, .own)

        let none = AirCallStage.placement(remote: false, own: false, swapped: false)
        XCTAssertNil(none.stage)
        XCTAssertNil(none.box)
    }

    func testTheFeedOfTheOtherPersonTakesTheShapeOfItsFrames() {
        // objcTox's view of the remote video, fed as toxav feeds it.
        let feed: OCTVideoView = OCTVideoView()
        XCTAssertEqual(feed.intrinsicContentSize.width, UIViewNoIntrinsicMetric)

        let slot = AirCallVideoSlot(frame: CGRect(x: 0, y: 0, width: 393, height: 852))
        slot.show(feed)
        slot.layoutIfNeeded()
        // No frame yet: the whole stage, black.
        XCTAssertEqual(feed.frame, slot.bounds)

        feed.enqueue(makePixelBuffer(width: 1280, height: 720))
        XCTAssertEqual(feed.intrinsicContentSize, landscapeFrame)
        slot.layoutIfNeeded()
        XCTAssertEqual(slot.scaleMode, .fit)
        XCTAssertEqual(feed.frame.origin.y, 315.46875, accuracy: 0.001)
        XCTAssertEqual(feed.frame.width, 393, accuracy: 0.001)
        XCTAssertEqual(feed.frame.height, 221.0625, accuracy: 0.001)

        // The other person turns their phone upright.
        feed.enqueue(makePixelBuffer(width: 720, height: 1280))
        XCTAssertEqual(feed.intrinsicContentSize, portraitFrame)
        slot.layoutIfNeeded()
        XCTAssertEqual(slot.scaleMode, .fill)
        XCTAssertEqual(feed.frame.origin.x, -43.125, accuracy: 0.001)
        XCTAssertEqual(feed.frame.height, 852, accuracy: 0.001)

        // In the box it always covers.
        slot.isBox = true
        slot.frame = CGRect(x: 0, y: 0, width: 181, height: 102)
        slot.layoutIfNeeded()
        XCTAssertEqual(slot.scaleMode, .fill)
        XCTAssertEqual(feed.frame.width, 181, accuracy: 0.001)
        XCTAssertEqual(feed.frame.height, 321.7778, accuracy: 0.001)
        XCTAssertEqual(slot.layer.cornerRadius, KQRadius.card)
        XCTAssertEqual(slot.layer.borderWidth, KQStroke.hairline)
    }

    func testTheCameraOfACallIsCrossedOutWhileOff() {
        let camera = CallButton(theme: nil, type: .video, buttonSize: .small)
        let off = png(camera.image(for: .normal))
        let on = png(camera.image(for: .selected))
        XCTAssertNotNil(off)
        XCTAssertNotNil(on)
        XCTAssertNotEqual(off, on)
        XCTAssertEqual(camera.image(for: .normal)?.size, camera.image(for: .selected)?.size)
        // Pressed or disabled, a switch that is on keeps the glyph of on.
        XCTAssertEqual(png(camera.image(for: [.selected, .highlighted])), on)
        XCTAssertEqual(png(camera.image(for: [.selected, .disabled])), on)
        let mute = CallButton(theme: nil, type: .mute, buttonSize: .small)
        XCTAssertEqual(png(mute.image(for: [.selected, .highlighted])), png(mute.image(for: .selected)))
    }

    private func png(_ image: UIImage?) -> Data? {
        return image.flatMap { UIImagePNGRepresentation($0) }
    }

    private func makePixelBuffer(width: Int, height: Int) -> CVPixelBuffer {
        var buffer: CVPixelBuffer?
        let attributes = [kCVPixelBufferIOSurfacePropertiesKey as String: [String: Any]()] as CFDictionary
        let status = CVPixelBufferCreate(kCFAllocatorDefault, width, height, kCVPixelFormatType_32BGRA, attributes, &buffer)
        XCTAssertEqual(status, kCVReturnSuccess)
        return buffer!
    }
}
