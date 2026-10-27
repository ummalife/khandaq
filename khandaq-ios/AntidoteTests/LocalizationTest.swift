// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Counted nouns and dates in the language picked inside the app.

 The group header showed "3 members" with the noun in the wrong form in Russian and Arabic: each string
 had a single form, while both languages change the noun with the number. The strings go through
 String(localized:), the path the screens use, after switching the language the way the in-app picker
 does — without a restart, so the process language stays whatever the test host started with.
 */
class LocalizationTest: XCTestCase {
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

    private func use(_ language: String) {
        AppLanguage.set(language)
        XCTAssertEqual(AppLanguage.current, language)
    }

    func testRussianMemberCountAgreesWithTheNumber() {
        use("ru")
        XCTAssertEqual(String(localized: "group_member_count_format", 1), "1 участник")
        XCTAssertEqual(String(localized: "group_member_count_format", 3), "3 участника")
        XCTAssertEqual(String(localized: "group_member_count_format", 5), "5 участников")
        XCTAssertEqual(String(localized: "group_member_count_format", 11), "11 участников")
        XCTAssertEqual(String(localized: "group_member_count_format", 21), "21 участник")
        XCTAssertEqual(String(localized: "group_member_count_format", 22), "22 участника")
    }

    func testArabicMemberCountAgreesWithTheNumber() {
        use("ar")
        XCTAssertEqual(String(localized: "group_member_count_format", 1), "1 عضو")
        XCTAssertEqual(String(localized: "group_member_count_format", 2), "2 عضوان")
        XCTAssertEqual(String(localized: "group_member_count_format", 3), "3 أعضاء")
        XCTAssertEqual(String(localized: "group_member_count_format", 11), "11 عضوًا")
        XCTAssertEqual(String(localized: "group_member_count_format", 100), "100 عضو")
    }

    func testEnglishSingularIsNotPlural() {
        use("en")
        XCTAssertEqual(String(localized: "group_member_count_format", 1), "1 member")
        XCTAssertEqual(String(localized: "group_member_count_format", 2), "2 members")
        XCTAssertEqual(String(localized: "group_delete_system_msgs_count_format", 1), "1 message")
    }

    func testTwoNumbersKeepTheirOrder() {
        use("ru")
        XCTAssertEqual(String(localized: "group_member_online_count_format", 3, 1), "3 участника · 1 онлайн")
        XCTAssertEqual(String(localized: "group_member_online_count_format", 5, 2), "5 участников · 2 онлайн")
        use("ar")
        XCTAssertEqual(String(localized: "group_member_online_count_format", 5, 2), "5 أعضاء · 2 متصل")
    }

    func testPluralInsideASentence() {
        use("ru")
        XCTAssertEqual(String(localized: "group_danger_footer_with_system_format", 2),
                       "Очистка истории удаляет сообщения локально. Можно отдельно удалить 2 системных сообщения.")
        use("ar")
        XCTAssertEqual(String(localized: "status_last_seen_minutes_ago", 5), "آخر اتصال قبل 5 دقائق")
    }

    func testChineseStillReadsThePlainStrings() {
        // Chinese has no stringsdict: the .strings entry must keep working.
        use("zh")
        XCTAssertEqual(String(localized: "group_member_count_format", 3), "3名成员")
    }

    func testDatesFollowTheAppLanguageNotThePhone() {
        let phone = Locale(identifier: "en_US")
        XCTAssertEqual(AppLanguage.locale(forLanguage: "ru", system: phone).identifier, "ru_US")

        let formatter = DateFormatter()
        formatter.locale = AppLanguage.locale(forLanguage: "ru", system: phone)
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        formatter.doesRelativeDateFormatting = true
        XCTAssertEqual(formatter.string(from: Date()).lowercased(), "сегодня")
    }

    func testPhoneLocaleIsKeptWhenItSpeaksTheAppLanguage() {
        let phone = Locale(identifier: "en_GB")
        XCTAssertEqual(AppLanguage.locale(forLanguage: "en", system: phone), phone)
    }

    func testArabicDatesUseWesternDigits() {
        let formatter = DateFormatter()
        formatter.locale = AppLanguage.locale(forLanguage: "ar", system: Locale(identifier: "en_US"))
        formatter.dateFormat = "yyyy"
        XCTAssertEqual(formatter.string(from: Date(timeIntervalSince1970: 1_790_000_000)), "2026")
    }
}
