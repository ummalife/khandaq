// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import AVFoundation
import XCTest
@testable import Antidote

/**
 KHANDAQ (0.2.56, owner request): a voice note plays from a round play button, as in Telegram and as
 AirPlayButton on the desktop, in place of the word "Listen".

 The numbers of the desktop: a 36 point disc, a triangle 11 by 13 moved 1.5 past the centre, two bars 3.5
 by 12 and 4 apart, all scaled with the disc; on iOS the disc stands at the leading edge of a 44 point
 target, and only its place follows a right-to-left layout, not the glyph. The colours are those of the ink
 button. Then the voice note: the words stay the button's name, Listen or Pause, with the length as its
 value, and the wave keeps its distance from the disc.
 */
final class AirPlayButtonTests: XCTestCase {
    private let target = CGRect(x: 0, y: 0, width: 44, height: 44)
    private let disc36 = CGRect(x: 0, y: 0, width: 36, height: 36)

    func testTheDiscIs36PointsAtTheLeadingEdgeOfThe44PointTarget() {
        XCTAssertEqual(AirPlayGlyph.disc(in: target, isRightToLeft: false), CGRect(x: 0, y: 4, width: 36, height: 36))
        XCTAssertEqual(AirPlayGlyph.disc(in: target, isRightToLeft: true), CGRect(x: 8, y: 4, width: 36, height: 36))
        // A smaller button holds a smaller disc.
        XCTAssertEqual(AirPlayGlyph.disc(in: CGRect(x: 0, y: 0, width: 24, height: 30), isRightToLeft: false),
                       CGRect(x: 0, y: 3, width: 24, height: 24))
    }

    func testTheTriangleIsTheOneOfTheDesktop() {
        // 11 by 13, pointing to the right, its flat side 1.5 past where a centred box would put it.
        XCTAssertEqual(AirPlayGlyph.playTriangle(in: disc36),
                       [CGPoint(x: 14, y: 11.5), CGPoint(x: 25, y: 18), CGPoint(x: 14, y: 24.5)])
        XCTAssertEqual(AirPlayGlyph.triangleStroke(in: disc36), 2)
    }

    func testTwoBarsWhilePlaying() {
        let bars = AirPlayGlyph.pauseBars(in: disc36)
        XCTAssertEqual(bars, [CGRect(x: 12.5, y: 12, width: 3.5, height: 12), CGRect(x: 20, y: 12, width: 3.5, height: 12)])
        XCTAssertEqual(bars[1].minX - bars[0].maxX, 4, "the bars are 4 apart")
        XCTAssertEqual((bars[0].minX + bars[1].maxX) / 2, disc36.midX, "the bars stand about the centre")
    }

    func testTheGlyphScalesWithTheDisc() {
        let disc = CGRect(x: 10, y: 20, width: 72, height: 72)
        XCTAssertEqual(AirPlayGlyph.playTriangle(in: disc),
                       [CGPoint(x: 38, y: 43), CGPoint(x: 60, y: 56), CGPoint(x: 38, y: 69)])
        XCTAssertEqual(AirPlayGlyph.triangleStroke(in: disc), 4)
        XCTAssertEqual(AirPlayGlyph.pauseBars(in: disc),
                       [CGRect(x: 35, y: 44, width: 7, height: 24), CGRect(x: 50, y: 44, width: 7, height: 24)])
    }

    func testTheGlyphIsNotMirroredRightToLeft() {
        let leftToRight = AirPlayGlyph.disc(in: target, isRightToLeft: false)
        let rightToLeft = AirPlayGlyph.disc(in: target, isRightToLeft: true)
        let triangle = AirPlayGlyph.playTriangle(in: rightToLeft)
        XCTAssertGreaterThan(triangle[1].x, triangle[0].x, "the triangle points to the right in both layouts")
        let shift = rightToLeft.minX - leftToRight.minX
        XCTAssertEqual(triangle, AirPlayGlyph.playTriangle(in: leftToRight).map { CGPoint(x: $0.x + shift, y: $0.y) },
                       "the same triangle, moved with the disc")
    }

    func testANewDirectionDrawsTheDiscAgain() {
        // The bounds keep their size when the layout turns right to left, so only the direction can move
        // the disc to the other edge.
        let button = AirPlayButton(frame: target)
        button.layer.displayIfNeeded()
        XCTAssertFalse(button.layer.needsDisplay())
        button.semanticContentAttribute = .forceRightToLeft
        XCTAssertTrue(button.layer.needsDisplay())
    }

    func testTheColoursAreThoseOfTheInkButton() {
        XCTAssertTrue(AirPlayGlyph.discColor(enabled: true, pressed: false) === KQColor.ink)
        XCTAssertTrue(AirPlayGlyph.discColor(enabled: true, pressed: true) === KQColor.inkPressed)
        XCTAssertTrue(AirPlayGlyph.discColor(enabled: false, pressed: false) === KQColor.sunken)
        XCTAssertTrue(AirPlayGlyph.discColor(enabled: false, pressed: true) === KQColor.sunken)
        XCTAssertTrue(AirPlayGlyph.markColor(enabled: true) === KQColor.onInk)
        XCTAssertTrue(AirPlayGlyph.markColor(enabled: false) === KQColor.ink3)
    }

    func testTheTargetIs44Points() {
        let size = AirPlayButton().systemLayoutSizeFitting(UILayoutFittingCompressedSize)
        XCTAssertEqual(size, CGSize(width: KQSize.targetIos, height: KQSize.targetIos))
    }

    func testTheVoiceNoteNamesItsButtonByItsWords() {
        let note = ChatVoiceMessageView()
        guard let button = playButton(of: note) else {
            XCTFail("the voice note has no play button")
            return
        }
        XCTAssertEqual(button.accessibilityIdentifier, "air.voice.play")
        XCTAssertEqual(button.accessibilityLabel, String(localized: "air_voice_listen"))

        note.update(isPlaying: false, progress: 0, currentTime: 0, duration: 14, enabled: true)
        XCTAssertFalse(button.isPlaying)
        XCTAssertTrue(button.isEnabled)
        XCTAssertEqual(button.accessibilityLabel, String(localized: "air_voice_listen"))
        XCTAssertEqual(button.accessibilityValue, "0:14")

        note.update(isPlaying: true, progress: 0.5, currentTime: 7, duration: 14, enabled: true)
        XCTAssertTrue(button.isPlaying)
        XCTAssertEqual(button.accessibilityLabel, String(localized: "air_voice_pause"))
        XCTAssertEqual(button.accessibilityValue, "0:07")

        // A note that is not on this device does not play, and VoiceOver says the button is dimmed.
        note.update(isPlaying: false, progress: 0, currentTime: 0, duration: 0, enabled: false)
        XCTAssertFalse(button.isPlaying)
        XCTAssertFalse(button.isEnabled)
        XCTAssertNotEqual(button.accessibilityTraits & UIAccessibilityTraitNotEnabled, 0)
    }

    func testTheWaveKeepsItsDistanceFromTheDisc() {
        let note = ChatVoiceMessageView(frame: CGRect(x: 0, y: 0, width: 320, height: KQSize.targetIos))
        note.update(isPlaying: false, progress: 0, currentTime: 0, duration: 14, enabled: true)
        note.layoutIfNeeded()
        guard let button = playButton(of: note),
              let wave = note.subviews.first(where: { !($0 is AirPlayButton) && !($0 is UILabel) }) else {
            XCTFail("the voice note has no play button or no wave")
            return
        }
        XCTAssertEqual(button.frame, CGRect(x: 0, y: 0, width: KQSize.targetIos, height: KQSize.targetIos))
        let disc = AirPlayGlyph.disc(in: button.bounds, isRightToLeft: false)
        // The layout engine works in doubles: 45.99999999999999 is 46.
        XCTAssertEqual(wave.frame.minX - (button.frame.minX + disc.maxX), KQSpace.x10, accuracy: 0.001)
    }

    private func playButton(of note: ChatVoiceMessageView) -> AirPlayButton? {
        return note.subviews.compactMap { $0 as? AirPlayButton }.first
    }
}

/**
 KHANDAQ (ideas 1): the speed of voice notes, 1x, 1.5x and 2x in turn from a tap on the speed of the note
 that plays; the app keeps one speed for every note, and the note that plays takes a new one at once.
 */
final class VoicePlaybackSpeedTests: XCTestCase {
    /// Where UserDefaultsManager keeps the speed.
    private let speedKey = "user-info/voice-playback-speed"
    private var savedSpeed: Any?
    private var savedLanguage: Any?
    private var savedAppleLanguages: Any?
    private var files: [URL] = []

    override func setUp() {
        super.setUp()
        savedSpeed = UserDefaults.standard.object(forKey: speedKey)
        savedLanguage = UserDefaults.standard.object(forKey: AppLanguage.storageKey)
        savedAppleLanguages = UserDefaults.standard.object(forKey: "AppleLanguages")
        AppLanguage.set("en")
        UserDefaults.standard.removeObject(forKey: speedKey)
    }

    override func tearDown() {
        ChatVoiceMessagePlayer.shared.stop()
        files.forEach { try? FileManager.default.removeItem(at: $0) }
        files = []
        UserDefaults.standard.set(savedSpeed, forKey: speedKey)
        UserDefaults.standard.set(savedLanguage, forKey: AppLanguage.storageKey)
        UserDefaults.standard.set(savedAppleLanguages, forKey: "AppleLanguages")
        AppLanguage.applyAtLaunch()
        super.tearDown()
    }

    func testTheSpeedGoesOneOneAndAHalfTwoAndBack() {
        XCTAssertEqual(VoicePlaybackSpeed.next(after: 1), 1.5)
        XCTAssertEqual(VoicePlaybackSpeed.next(after: 1.5), 2)
        XCTAssertEqual(VoicePlaybackSpeed.next(after: 2), 1)
        // A value that is not a step starts over.
        XCTAssertEqual(VoicePlaybackSpeed.next(after: 0.75), 1)
        XCTAssertEqual(VoicePlaybackSpeed.valid(3), 1)
        XCTAssertEqual(VoicePlaybackSpeed.valid(1.5), 1.5)
    }

    func testTheSpeedIsWrittenAsTheLanguageWritesNumbers() {
        XCTAssertEqual(VoicePlaybackSpeed.text(for: 1, locale: Locale(identifier: "en_US")), "1x")
        XCTAssertEqual(VoicePlaybackSpeed.text(for: 1.5, locale: Locale(identifier: "en_US")), "1.5x")
        XCTAssertEqual(VoicePlaybackSpeed.text(for: 2, locale: Locale(identifier: "en_US")), "2x")
        XCTAssertEqual(VoicePlaybackSpeed.text(for: 1.5, locale: Locale(identifier: "ru_RU")), "1,5x")
        // Western digits in Arabic, as everywhere else in the app (AppLanguage.locale).
        let arabic = VoicePlaybackSpeed.text(for: 1.5, locale: AppLanguage.locale(forLanguage: "ar", system: Locale(identifier: "en_US")))
        XCTAssertTrue(arabic.hasPrefix("1") && arabic.hasSuffix("5x"), arabic)
    }

    func testTheWordsAreInEveryLanguage() {
        assertTranslated(["air_voice_speed", "air_voice_speed_format"])
    }

    /// One speed for the app: a tap with no note playing changes it too, and it is kept.
    func testTheSpeedIsTheAppsAndKept() {
        let player = ChatVoiceMessagePlayer.shared
        XCTAssertEqual(player.speed, 1, "a first speed other than 1x")
        player.cycleSpeed()
        XCTAssertEqual(player.speed, 1.5)
        XCTAssertEqual(UserDefaultsManager().voicePlaybackSpeed, 1.5, "the speed is not kept")
        player.cycleSpeed()
        player.cycleSpeed()
        XCTAssertEqual(player.speed, 1)
        // A value written by anything else reads as 1x.
        UserDefaultsManager().voicePlaybackSpeed = 7
        XCTAssertEqual(player.speed, 1)
    }

    /// The note that plays goes faster the moment the speed changes, the next note starts at the kept speed,
    /// and only a note in progress shows it.
    func testTheNoteThatPlaysTakesTheNewSpeedAtOnce() throws {
        let player = ChatVoiceMessagePlayer.shared
        let first = try silentNote(seconds: 6)
        player.togglePlayback(messageId: "first", filePath: first.path)
        XCTAssertEqual(player.activeRate, 1)
        XCTAssertEqual(player.speedShown(for: "first"), 1)
        XCTAssertNil(player.speedShown(for: "second"), "a note that does not play shows a speed")

        player.cycleSpeed()
        player.cycleSpeed()
        XCTAssertEqual(player.activeRate, 2, "the note that plays kept its speed")
        XCTAssertEqual(player.speedShown(for: "first"), 2)
        let start = try XCTUnwrap(player.state(for: "first")).currentTime
        run(for: 0.8)
        let played = try XCTUnwrap(player.state(for: "first")).currentTime - start
        XCTAssertGreaterThan(played, 1.1, "0.8 s at 2x played only \(played) s of the note")

        // Paused part of the way, the note keeps showing its speed.
        player.togglePlayback(messageId: "first", filePath: first.path)
        XCTAssertEqual(player.speedShown(for: "first"), 2)

        let second = try silentNote(seconds: 2)
        player.togglePlayback(messageId: "second", filePath: second.path)
        XCTAssertEqual(player.activeRate, 2, "the next note did not start at the kept speed")
        XCTAssertNil(player.speedShown(for: "first"))
        player.stop()
        XCTAssertNil(player.speedShown(for: "second"))
    }

    func testTheSpeedStandsAfterTheLengthOnlyWhenShown() throws {
        let note = ChatVoiceMessageView(frame: CGRect(x: 0, y: 0, width: 320, height: KQSize.targetIos))
        note.update(isPlaying: true, progress: 0.5, currentTime: 7, duration: 14, enabled: true)
        let speed = try XCTUnwrap(control(of: note, "air.voice.speed"))
        note.showSpeed(nil)
        note.layoutIfNeeded()
        XCTAssertTrue(speed.isHidden)
        XCTAssertNil(note.shownSpeedText)
        let waveWithout = try XCTUnwrap(wave(of: note)).frame.width

        note.showSpeed(1.5)
        note.layoutIfNeeded()
        XCTAssertFalse(speed.isHidden)
        // "1.5x", with the decimal mark of the app's locale.
        let text = VoicePlaybackSpeed.text(for: 1.5)
        XCTAssertTrue(text.hasPrefix("1") && text.hasSuffix("5x"), text)
        XCTAssertEqual(note.shownSpeedText, text)
        XCTAssertEqual(speed.accessibilityLabel, String(localized: "air_voice_speed"))
        XCTAssertEqual(speed.accessibilityValue, text)
        let length = try XCTUnwrap(labels(of: note).first { $0.text == "0:07" })
        XCTAssertGreaterThan(speed.convert(speed.bounds, to: note).minX, length.convert(length.bounds, to: note).maxX,
                             "the speed does not stand after the length")
        XCTAssertLessThan(try XCTUnwrap(wave(of: note)).frame.width, waveWithout, "the wave did not make room")
        // Drawn 28 high, it takes a touch over 44.
        XCTAssertTrue(speed.point(inside: CGPoint(x: speed.bounds.midX, y: speed.bounds.midY - KQSize.targetIos / 2 + 1), with: nil))

        var taps = 0
        note.onSpeedTapped = {
            taps += 1
        }
        speed.sendActions(for: .touchUpInside)
        XCTAssertEqual(taps, 1)
    }

    /// The file entry of a note shows the speed while that note plays, not on the other notes.
    func testAVoiceEntryShowsTheSpeedOfItsNoteOnly() throws {
        let note = try silentNote(seconds: 4)
        ChatVoiceMessagePlayer.shared.togglePlayback(messageId: "playing", filePath: note.path)
        let theme = try Theme(yamlString: String(contentsOfFile: try XCTUnwrap(Bundle.main.path(forResource: "default-theme", ofType: "yaml")),
                                                 encoding: .utf8))

        let playing = voiceEntry(id: "playing")
        let playingCell = ChatIncomingFileCell()
        playingCell.setupWithTheme(theme, model: playing)
        XCTAssertEqual(playingCell.voiceMessageView.shownSpeedText, VoicePlaybackSpeed.text(for: 1))

        let other = voiceEntry(id: "other")
        let otherCell = ChatIncomingFileCell()
        otherCell.setupWithTheme(theme, model: other)
        XCTAssertNil(otherCell.voiceMessageView.shownSpeedText)

        // A tap on the speed reaches the player, and the entry shows the new one when it is drawn again.
        playingCell.voiceMessageView.onSpeedTapped?()
        playingCell.refreshVoicePlaybackState(theme: theme)
        XCTAssertEqual(playingCell.voiceMessageView.shownSpeedText, VoicePlaybackSpeed.text(for: 1.5))
        XCTAssertEqual(ChatVoiceMessagePlayer.shared.activeRate, 1.5)
    }

    /// KHANDAQ (tester 8263): the files that play in their entry: the formats the player reads, never a voice note.
    func testWhichFilesPlayInTheirEntry() {
        XCTAssertTrue(VoiceMessageHelper.isInlineAudioFile(fileName: "Song.mp3", filePath: nil))
        XCTAssertTrue(VoiceMessageHelper.isInlineAudioFile(fileName: "Talk.M4A", filePath: nil))
        XCTAssertTrue(VoiceMessageHelper.isInlineAudioFile(fileName: "take", filePath: "/files/take.wav"))
        XCTAssertFalse(VoiceMessageHelper.isInlineAudioFile(fileName: VoiceMessageHelper.makeOutgoingFileName(), filePath: nil),
                       "a voice note plays as a file")
        XCTAssertFalse(VoiceMessageHelper.isInlineAudioFile(fileName: "note.ogg", filePath: nil))
        XCTAssertFalse(VoiceMessageHelper.isInlineAudioFile(fileName: "Report.pdf", filePath: nil))
        XCTAssertFalse(VoiceMessageHelper.isInlineAudioFile(fileName: "Clip.mp4", filePath: nil))
        XCTAssertFalse(VoiceMessageHelper.isInlineAudioFile(fileName: nil, filePath: nil))
    }

    /// KHANDAQ (tester 8263): an audio file plays at 1x whatever the speed of the notes, and shows no speed;
    /// the next voice note plays at the notes' speed again.
    func testAnAudioFilePlaysAtItsOwnSpeed() throws {
        let player = ChatVoiceMessagePlayer.shared
        player.cycleSpeed()
        XCTAssertEqual(player.speed, 1.5)

        let song = try silentNote(seconds: 4)
        XCTAssertTrue(player.togglePlayback(messageId: "song", filePath: song.path, atOwnSpeed: true))
        XCTAssertEqual(player.activeRate, 1, "an audio file plays at the speed of the voice notes")
        XCTAssertNil(player.speedShown(for: "song"))
        player.cycleSpeed()
        XCTAssertEqual(player.activeRate, 1, "a new speed of the notes reached the audio file")
        XCTAssertEqual(player.speed, 2)

        let note = try silentNote(seconds: 2)
        XCTAssertTrue(player.togglePlayback(messageId: "note", filePath: note.path))
        XCTAssertEqual(player.activeRate, 2)
        XCTAssertEqual(player.speedShown(for: "note"), 2)
    }

    /// KHANDAQ (tester 8263): a file the player does not read, or one that is gone, is not played; the entry
    /// opens it in QuickLook instead.
    func testAFileThePlayerCannotReadIsNotPlayed() throws {
        let player = ChatVoiceMessagePlayer.shared
        let fake = FileManager.default.temporaryDirectory.appendingPathComponent("not-audio-\(UUID().uuidString).mp3")
        files.append(fake)
        try Data("no sound in here".utf8).write(to: fake)

        XCTAssertFalse(player.togglePlayback(messageId: "fake", filePath: fake.path, atOwnSpeed: true))
        XCTAssertNil(player.activeMessageId)
        XCTAssertFalse(player.togglePlayback(messageId: "gone", filePath: fake.path + ".gone", atOwnSpeed: true))
        XCTAssertNil(player.activeMessageId)
    }

    /// KHANDAQ (tester 8263): the entry of an audio file: the player over its name row, no speed while it plays,
    /// and a tap on the name row opens the file (QuickLook: share, save, another app).
    func testAnAudioFileEntryHasThePlayerOverItsNameRow() throws {
        let song = try silentNote(seconds: 4)
        let theme = try Theme(yamlString: String(contentsOfFile: try XCTUnwrap(Bundle.main.path(forResource: "default-theme", ofType: "yaml")),
                                                 encoding: .utf8))
        let model = ChatIncomingFileCellModel()
        model.state = .done
        model.fileName = "Song.mp3"
        model.fileSize = "64 KB"
        model.isAudioFile = true
        model.voiceMessageId = "song"
        model.voiceDuration = 4
        var opened = 0
        model.openHandle = {
            opened += 1
        }
        model.voicePlayToggleHandle = {
            ChatVoiceMessagePlayer.shared.togglePlayback(messageId: "song", filePath: song.path, atOwnSpeed: true)
        }
        let cell = ChatIncomingFileCell()
        cell.setupWithTheme(theme, model: model)

        XCTAssertFalse(cell.voiceMessageView.isHidden, "an audio file has no player")
        XCTAssertFalse(cell.fileRow.isHidden, "an audio file lost its name row")
        XCTAssertTrue(labels(of: cell.fileRow).contains { $0.text == "Song.mp3" }, "the row does not name the file")
        XCTAssertTrue(labels(of: cell.voiceMessageView).contains { $0.text == "0:04" }, "the player does not give the length")

        cell.voicePlayTogglePressed()
        cell.refreshVoicePlaybackState(theme: theme)
        XCTAssertEqual(ChatVoiceMessagePlayer.shared.activeMessageId, "song")
        XCTAssertNil(cell.voiceMessageView.shownSpeedText, "an audio file shows the speed of the voice notes")

        cell.fileRowTapped()
        XCTAssertEqual(opened, 1, "a tap on the name row did not open the file")
    }
}

private extension VoicePlaybackSpeedTests {
    /// A silent note of `seconds` the player can open: plain PCM, nothing to hear.
    func silentNote(seconds: Double) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("silent-note-\(UUID().uuidString).caf")
        files.append(url)
        let format = try XCTUnwrap(AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 8_000, channels: 1, interleaved: true))
        let frames = AVAudioFrameCount(seconds * 8_000)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        let samples = try XCTUnwrap(buffer.int16ChannelData)
        samples[0].initialize(repeating: 0, count: Int(frames))
        do {
            let file = try AVAudioFile(forWriting: url, settings: format.settings, commonFormat: .pcmFormatInt16, interleaved: true)
            try file.write(from: buffer)
        }
        return url
    }

    func voiceEntry(id: String) -> ChatIncomingFileCellModel {
        let model = ChatIncomingFileCellModel()
        model.state = .done
        model.fileName = "voice.m4a"
        model.isVoiceMessage = true
        model.voiceMessageId = id
        model.voiceDuration = 4
        return model
    }

    /// Lets the main loop run for `seconds`.
    func run(for seconds: TimeInterval) {
        let ran = expectation(description: "the main loop ran")
        DispatchQueue.main.asyncAfter(deadline: .now() + seconds) {
            ran.fulfill()
        }
        wait(for: [ran], timeout: seconds + 5)
    }

    func control(of view: UIView, _ identifier: String) -> UIControl? {
        for subview in view.subviews {
            if let control = subview as? UIControl, control.accessibilityIdentifier == identifier {
                return control
            }
            if let found = control(of: subview, identifier) {
                return found
            }
        }
        return nil
    }

    func labels(of view: UIView) -> [UILabel] {
        return view.subviews.flatMap { subview -> [UILabel] in
            ((subview as? UILabel).map { [$0] } ?? []) + labels(of: subview)
        }
    }

    /// The wave: the view beside the play button that is neither a control nor a stack.
    func wave(of note: ChatVoiceMessageView) -> UIView? {
        return note.subviews.first { !($0 is UIControl) && !($0 is UIStackView) && !($0 is UILabel) }
    }
}
