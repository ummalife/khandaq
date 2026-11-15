// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

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
