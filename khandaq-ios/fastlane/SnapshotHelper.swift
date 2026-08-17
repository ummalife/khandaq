// The project has always referenced fastlane/SnapshotHelper.swift, but the file was never in the
// repository — so the ScreenshotsUITests target could not be built at all ("Build input file cannot
// be found"). This is a small stand-in that keeps the API the existing tests use:
//
//   setupSnapshot(app)          — remember the app and pass the language through launch arguments
//   snapshot("01_Something")    — capture the screen into SNAPSHOT_DIR (or the temp dir)
//   deviceLanguage              — the language the run was started with
//
// It is deliberately not the full fastlane helper: no simulator status-bar juggling, no
// localization sniffing from the host. If `fastlane snapshot` is ever wired up again, replace this
// file with the generated one.

import Foundation
import XCTest

var deviceLanguage = "en-US"

private var snapshotApp: XCUIApplication?
private var snapshotCounter = 0

func setupSnapshot(_ app: XCUIApplication, waitForAnimations: Bool = true) {
    snapshotApp = app

    // fastlane passes the language on the command line; honour it, otherwise take the device's.
    if let index = ProcessInfo.processInfo.arguments.firstIndex(of: "-AppleLanguages"),
       index + 1 < ProcessInfo.processInfo.arguments.count {
        let raw = ProcessInfo.processInfo.arguments[index + 1]
        deviceLanguage = raw.trimmingCharacters(in: CharacterSet(charactersIn: "()\" "))
    }
    else if let preferred = Locale.preferredLanguages.first {
        deviceLanguage = preferred
    }

    app.launchArguments += ["-FASTLANE_SNAPSHOT", "YES", "-ui_testing"]
}

func snapshot(_ name: String, waitForLoadingIndicator: Bool = false) {
    snapshotCounter += 1

    let screenshot = XCUIScreen.main.screenshot()
    let directory = ProcessInfo.processInfo.environment["SNAPSHOT_DIR"] ?? NSTemporaryDirectory()
    let file = URL(fileURLWithPath: directory)
            .appendingPathComponent("\(String(format: "%02d", snapshotCounter))_\(name)_\(deviceLanguage).png")

    do {
        try screenshot.pngRepresentation.write(to: file)
        print("snapshot: \(file.path)")
    }
    catch {
        print("snapshot failed for \(name): \(error)")
    }
}
