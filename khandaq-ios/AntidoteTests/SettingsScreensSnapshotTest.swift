// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import XCTest
@testable import Antidote

/**
 Settings (redesign stage 3, E18): the list of sections, Appearance with the theme segment (also dark on
 the narrowest phone, where the segment still fits on one line), Notifications, Profile transfer and
 Network. The stored values the screens show are pinned for the run and put back afterwards.
 */
class SettingsScreensSnapshotTest: SnapshotBaseTest {
    private var window: UIWindow?
    private var stored: (theme: ThemePreference, groupEvents: Bool, preview: Bool, attachments: UserDefaultsManager.AutodownloadImages,
                         udp: Bool, longerBackground: Bool, callAudioOff: Bool)?
    private static let callAudioOffKey = "KhandaqCallAudioVPIODisabled"
    private var languageBefore: String?

    override func setUp() {
        super.setUp()
        let defaults = UserDefaultsManager()
        stored = (defaults.themePreference, defaults.groupShowSystemMessages, defaults.showNotificationPreview,
                  defaults.autodownloadImages, defaults.UDPEnabled, defaults.LongerbgMode,
                  UserDefaults.standard.bool(forKey: SettingsScreensSnapshotTest.callAudioOffKey))
        // Written past ThemeAppearance on purpose: its setter would rebuild the app around the tests.
        defaults.themePreference = .system
        defaults.groupShowSystemMessages = false
        defaults.showNotificationPreview = true
        defaults.autodownloadImages = .UsingWiFi
        defaults.UDPEnabled = true
        defaults.LongerbgMode = false
        UserDefaults.standard.set(false, forKey: SettingsScreensSnapshotTest.callAudioOffKey)
        // The Language row names the language in effect; the strings of these snapshots are English.
        languageBefore = UserDefaults.standard.string(forKey: AppLanguage.storageKey)
        UserDefaults.standard.set("en", forKey: AppLanguage.storageKey)
    }

    override func tearDown() {
        window?.isHidden = true
        window = nil
        if let stored = stored {
            let defaults = UserDefaultsManager()
            defaults.themePreference = stored.theme
            defaults.groupShowSystemMessages = stored.groupEvents
            defaults.showNotificationPreview = stored.preview
            defaults.autodownloadImages = stored.attachments
            defaults.UDPEnabled = stored.udp
            defaults.LongerbgMode = stored.longerBackground
            UserDefaults.standard.set(stored.callAudioOff, forKey: SettingsScreensSnapshotTest.callAudioOffKey)
        }
        UserDefaults.standard.set(languageBefore, forKey: AppLanguage.storageKey)
        super.tearDown()
    }

    func testSettings() {
        let controller = SettingsMainController(theme: theme, submanagerObjects: nil)
        host(controller, size: CGSize(width: 390, height: 640), style: .light)
        verifyView(controller.view)
    }

    func testAppearance() {
        let controller = AirAppearanceController(submanagerObjects: nil)
        host(controller, size: CGSize(width: 390, height: 420), style: .light)
        verifyView(controller.view)
    }

    func testAppearanceInTheDarkOnTheNarrowestPhone() {
        let controller = AirAppearanceController(submanagerObjects: nil)
        let width: CGFloat = 320
        host(controller, size: CGSize(width: width, height: 420), style: .dark)

        let segment = controller.themeControl
        XCTAssertFalse(segment.isStacked, "the three themes no longer fit on one line at 320 points")
        let frame = segment.convert(segment.bounds, to: controller.view)
        XCTAssertGreaterThanOrEqual(frame.minX, KQSpace.x16 - 0.5)
        XCTAssertLessThanOrEqual(frame.maxX, width - KQSpace.x16 + 0.5)
        FBSnapshotVerifyView(controller.view, identifier: "dark-320")
    }

    func testNotifications() {
        let controller = AirNotificationsController()
        host(controller, size: CGSize(width: 390, height: 260), style: .light)
        FBSnapshotVerifyView(controller.view)
    }

    func testTransfer() {
        let controller = AirTransferController()
        host(controller, size: CGSize(width: 390, height: 280), style: .light)
        FBSnapshotVerifyView(controller.view)
    }

    func testNetwork() {
        let controller = SettingsAdvancedController(theme: theme)
        host(controller, size: CGSize(width: 390, height: 1000), style: .light)
        verifyView(controller.view)
    }

    /// In a window of its own, so the theme reaches every view as it does in the app.
    private func host(_ controller: UIViewController, size: CGSize, style: UIUserInterfaceStyle) {
        let window = UIWindow(frame: CGRect(origin: .zero, size: size))
        window.overrideUserInterfaceStyle = style
        window.rootViewController = controller
        window.isHidden = false
        controller.view.frame = window.bounds
        controller.view.setNeedsLayout()
        controller.view.layoutIfNeeded()
        // A label that wraps settles on the second pass, as it does in the app on the next frame.
        controller.view.layoutIfNeeded()
        self.window = window
    }
}
