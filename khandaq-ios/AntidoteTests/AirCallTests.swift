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
