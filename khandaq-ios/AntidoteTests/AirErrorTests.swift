// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Errors and confirmations (redesign stage 3, E21): every error says what failed and what to do, never
 a bare "Error" or "Internal error"; an error without a code, or with a code this build does not know,
 still has words and never crashes; the technical code waits under "Details" and stays out of the
 words; a confirmation comes from below with the verb on its button.
 */
class AirErrorTests: XCTestCase {
    private let bare = [String(localized: "error_title"),
                        String(localized: "error_internal_message"),
                        String(localized: "error_general_unknown_message")]

    func testEveryErrorSaysWhatFailedEvenWithoutACode() {
        for type in ErrorHandlerType.allCases {
            guard let alert = errorAlert(for: type, error: nil) else {
                XCTFail("\(type) says nothing")
                continue
            }
            XCTAssertFalse(alert.title.isEmpty, "\(type) has no title")
            XCTAssertFalse(bare.contains(alert.title), "\(type) is titled \(alert.title)")
            XCTAssertFalse(bare.contains(alert.message ?? ""), "\(type) says \(alert.message ?? "")")
            XCTAssertNil(alert.details, "\(type) has details without an error")
        }
    }

    func testACodeThisBuildDoesNotKnowNeitherCrashesNorShows() {
        let unknown = NSError(domain: "KhandaqTestDomain", code: 9_999, userInfo: nil)
        for type in ErrorHandlerType.allCases {
            guard let alert = errorAlert(for: type, error: unknown) else {
                XCTFail("\(type) says nothing")
                continue
            }
            XCTAssertFalse(bare.contains(alert.title), "\(type) is titled \(alert.title)")
            XCTAssertEqual(alert.details, "KhandaqTestDomain 9999", "\(type) loses the code")
            for words in [alert.title, alert.message ?? ""] {
                XCTAssertFalse(words.contains("9999"), "\(type) shows the code in its words")
                XCTAssertFalse(words.contains("KhandaqTestDomain"), "\(type) shows the domain in its words")
            }
        }
    }

    func testAKnownCodeKeepsItsSentenceUnderTheTitleOfTheOperation() {
        let tooLong = NSError(domain: "me.dvor.objcTox.OCTToxErrorDomain", code: OCTToxErrorFriendAdd.tooLong.rawValue, userInfo: nil)
        let alert = errorAlert(for: .toxAddFriend, error: tooLong)
        XCTAssertEqual(alert?.title, String(localized: "error_failed_add_contact"))
        XCTAssertEqual(alert?.message, String(localized: "error_contact_request_too_long"))
        XCTAssertEqual(alert?.details, "me.dvor.objcTox.OCTToxErrorDomain \(OCTToxErrorFriendAdd.tooLong.rawValue)")
    }

    func testAnEmptyErrorAsksToTryAgainAndToSupportOnlyWithACode() {
        XCTAssertEqual(errorAlert(for: .routeAudioToSpeaker, error: nil)?.message, String(localized: "error_try_again_message"))
        let code = NSError(domain: "KhandaqTestDomain", code: 1, userInfo: nil)
        XCTAssertEqual(errorAlert(for: .routeAudioToSpeaker, error: code)?.message, String(localized: "error_try_again_support_message"))
        XCTAssertEqual(errorAlert(for: .routeAudioToSpeaker, error: nil)?.title, String(localized: "error_failed_speaker"))
    }

    func testAGroupFileThePersonCancelledSaysNothing() {
        let cancelled = NSError(domain: "OCTNgcGroupFileTransferErrorDomain", code: 4, userInfo: nil)
        XCTAssertNil(errorAlert(for: .sendFileToFriend, error: cancelled))
        let tooLarge = NSError(domain: "OCTNgcGroupFileTransferErrorDomain", code: 1, userInfo: nil)
        XCTAssertEqual(errorAlert(for: .sendFileToFriend, error: tooLarge)?.title, String(localized: "error_failed_send_file"))
        XCTAssertEqual(errorAlert(for: .sendFileToFriend, error: tooLarge)?.message, String(localized: "group_file_too_large"))
    }

    func testASentenceAloneIsTheTitle() {
        let words = UIAlertController.errorWords(message: String(localized: "group_file_send_failed"))
        XCTAssertEqual(words.title, String(localized: "group_file_send_failed"))
        XCTAssertNil(words.message)

        let empty = UIAlertController.errorWords(message: String(localized: "error_internal_message"))
        XCTAssertEqual(empty.title, String(localized: "error_failed_generic"))
        XCTAssertEqual(empty.message, String(localized: "error_try_again_message"))
    }

    func testAConfirmationComesFromBelowWithTheVerbOnItsButton() {
        let sheet = AirConfirm.sheet(title: "Delete the chat?",
                                     message: "The conversation will be deleted from this device; the contact stays.",
                                     verb: "Delete",
                                     source: nil) {}
        XCTAssertEqual(sheet.preferredStyle, .actionSheet)
        XCTAssertEqual(sheet.actions.map { $0.title ?? "" }, ["Delete", String(localized: "alert_cancel")])
        XCTAssertEqual(sheet.actions.first?.style, .destructive)
        XCTAssertEqual(sheet.actions.last?.style, .cancel)

        let quiet = AirConfirm.sheet(title: nil, message: nil, verb: "Log out", style: .default, source: nil) {}
        XCTAssertEqual(quiet.actions.first?.style, .default)
    }
}
