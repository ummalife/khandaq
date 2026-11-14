/*
    Copyright © 2021 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "src/persistence/ismileysettings.h"
#include "src/persistence/smileypack.h"
#include "util/interface.h"

#include <QtTest/QtTest>
#include <QSignalSpy>
#include <QString>
#include <QStandardPaths>

#include <QGuiApplication>
#include <QTextBlock>
#include <QTextDocument>

#include <memory>

class MockSettings : public QObject, public ISmileySettings
{
Q_OBJECT
public:
    QString getSmileyPack() const override;
    int getEmojiFontPointSize() const override;
    SIGNAL_IMPL(MockSettings, smileyPackChanged, const QString& name);
};

QString MockSettings::getSmileyPack() const
{
    return ":/smileys/emojione/emoticons.xml";
}

/** The size of the pack's pictures, which a sequence in the emoji font takes too. */
constexpr int PICTURE_SIZE = 24;

int MockSettings::getEmojiFontPointSize() const
{
    return PICTURE_SIZE;
}

namespace {
// KHANDAQ (QA 2026-11-14, F1): the joiner, the presentation selectors and the keycap mark cannot be
// seen in a literal, so the tests below spell them out.
const QChar ZERO_WIDTH_JOINER{0x200D};
const QChar TEXT_PRESENTATION{0xFE0E};
const QChar EMOJI_PRESENTATION{0xFE0F};
const QChar COMBINING_KEYCAP{0x20E3};

/** A sequence as smileyfied() sets it: in the emoji font, as big as the pictures of the pack. */
QString inPictureSize(const QString& sequence)
{
    return SmileyPack::getInEmojiFont(sequence, PICTURE_SIZE);
}

/** The format the rich text gives to the first character of the given text in it. */
QTextCharFormat formatOf(const QTextDocument& document, const QString& text)
{
    const int at = document.toPlainText().indexOf(text);
    for (QTextBlock block = document.begin(); block.isValid(); block = block.next()) {
        for (auto it = block.begin(); !it.atEnd(); ++it) {
            const QTextFragment fragment = it.fragment();
            if (at >= fragment.position() && at < fragment.position() + fragment.length()) {
                return fragment.charFormat();
            }
        }
    }
    return {};
}
} // namespace

class TestSmileyPack : public QObject
{
    Q_OBJECT
public:
    TestSmileyPack();

private slots:
    void testSmilifySingleCharEmoji();
    void testSmilifyMultiCharEmoji();
    void testSmilifyAsciiEmoticon();
    void testSmilifyWholeCharactersOnly();
    void testSmilifyKeepsFlagsWhole();
    void testSmilifyKeepsJoinedSequencesWhole();
    void testSmilifyEmojiPresentationSelector();
    void testSmilifyKeepsKeycapsWhole();
    void testSmilifySetsJoinedSequencesInTheEmojiFont();
    void testSmilifySetsToneSequencesInTheEmojiFont();
    void testSmilifyDrawsBlockedSequencesApart();
    void testSmilifyAsciiEmoticonBetweenWords();
    void testSmilifyMixedScripts();
    void testPickerLeavesOutWhatAndroidDoesNotOffer();
    void testPickerOffersTheRestOfThePack();
    void testSequencesAreAsBigAsThePictures();
    void testEmojiFontRangesLeaveBlockedSequencesApart();
    void testPlainTextAsRichTextKeepsTheText();
private:
    std::unique_ptr<QGuiApplication> app;
    std::unique_ptr<MockSettings> settings;
};

TestSmileyPack::TestSmileyPack()
{
    static char arg1[]{"QToxSmileyPackTestApp"};
    static char arg2[]{"-platform"};
    static char arg3[]{"offscreen"};
    static char* qtTestAppArgv[] = {arg1, arg2, arg3};
    static int qtTestAppArgc = 3;

    app = std::unique_ptr<QGuiApplication>(new QGuiApplication(qtTestAppArgc, qtTestAppArgv));
    settings = std::unique_ptr<MockSettings>(new MockSettings());
}

/**
 * @brief Test that single-character emojis (non-ascii) are correctly smilified
 */
void TestSmileyPack::testSmilifySingleCharEmoji()
{
    SmileyPack smileyPack{*settings};

    auto result = smileyPack.smileyfied("😊");
    QVERIFY(result == SmileyPack::getAsRichText("😊"));

    result = smileyPack.smileyfied("Some😊Letters");
    QVERIFY(result == "Some" + SmileyPack::getAsRichText("😊") + "Letters");
}

/**
 * @brief Test that multi-character emojis (non-ascii) are correctly smilified
 *  and not incorrectly matched against single-char counterparts
 */
void TestSmileyPack::testSmilifyMultiCharEmoji()
{
    SmileyPack smileyPack{*settings};

    auto result = smileyPack.smileyfied("🇬🇧");
    QVERIFY(result == SmileyPack::getAsRichText("🇬🇧"));

    result = smileyPack.smileyfied("Some🇬🇧Letters");
    QVERIFY(result == "Some" + SmileyPack::getAsRichText("🇬🇧") + "Letters");

    // This verifies that multi-char emojis are not accidentally
    // considered a multichar ascii smiley
    result = smileyPack.smileyfied("🇫🇷🇬🇧");
    QVERIFY(result == SmileyPack::getAsRichText("🇫🇷") + SmileyPack::getAsRichText("🇬🇧"));
}


/**
 * @brief Test that single character emojis (non-ascii) are correctly smilified
 *  and not when surrounded by non-punctuation and non-whitespace chars
 */
void TestSmileyPack::testSmilifyAsciiEmoticon()
{
    SmileyPack smileyPack{*settings};

    auto result = smileyPack.smileyfied(":-)");
    QVERIFY(result == SmileyPack::getAsRichText(":-)"));

    const auto testMsg = QStringLiteral("Some:-)Letters");
    result = smileyPack.smileyfied(testMsg);

    // Nothing has changed. Ascii smileys are only considered
    // when they are surrounded by white space
    QVERIFY(result == testMsg);

    result = smileyPack.smileyfied("  :-)  ");
    QVERIFY(result == "  " + SmileyPack::getAsRichText(":-)") + "  ");
}

/**
 * @brief Test that an emoji gets its picture only when it is the whole character the reader sees:
 *  a skin tone after it makes another character, which the pack does not have
 */
void TestSmileyPack::testSmilifyWholeCharactersOnly()
{
    SmileyPack smileyPack{*settings};

    QCOMPARE(smileyPack.smileyfied("👍"), SmileyPack::getAsRichText("👍"));

    // 👍🏽 is U+1F44D U+1F3FD. The pack has 👍 and not 👍🏽: the picture of 👍 followed by the medium
    // skin tone alone (a brown square) is what the QA run saw. It is one character, set in the emoji
    // font (testSmilifySetsToneSequencesInTheEmojiFont).
    const QString thumbsUpMedium = QStringLiteral("👍🏽");
    QCOMPARE(smileyPack.smileyfied(thumbsUpMedium), inPictureSize(thumbsUpMedium));

    // A character left to the system font does not keep the next one from its picture.
    QCOMPARE(smileyPack.smileyfied(thumbsUpMedium + "👍"),
             QString(inPictureSize(thumbsUpMedium) + SmileyPack::getAsRichText("👍")));
}

/**
 * @brief Test that a flag the pack does not have stays one flag: the pack has pictures for the
 *  regional indicator letters, and two letters side by side are a flag, not two letters
 */
void TestSmileyPack::testSmilifyKeepsFlagsWhole()
{
    SmileyPack smileyPack{*settings};

    // 🇹🇷 is U+1F1F9 U+1F1F7. The pack has both letters alone and not the flag. The system's emoji
    // font draws it, as big as the pictures (QA live 2026-11-14: it was as big as the text).
    const QString turkey = QStringLiteral("🇹🇷");
    QCOMPARE(smileyPack.smileyfied(turkey), inPictureSize(turkey));

    // Letters pair up from the start of a run: 🇹🇷🇷🇺 is Turkey, then Russia, which the pack has.
    QCOMPARE(smileyPack.smileyfied(turkey + "🇷🇺"),
             QString(inPictureSize(turkey) + SmileyPack::getAsRichText("🇷🇺")));

    // A letter without a pair is a character of its own and keeps its picture.
    QCOMPARE(smileyPack.smileyfied("🇷🇺🇹"),
             QString(SmileyPack::getAsRichText("🇷🇺") + SmileyPack::getAsRichText("🇹")));
}

/**
 * @brief Test that emoji joined into one character by U+200D are not taken apart into the
 *  pictures of their members
 */
void TestSmileyPack::testSmilifyKeepsJoinedSequencesWhole()
{
    SmileyPack smileyPack{*settings};

    // A family: the pack has the man, the woman and the girl, not the family. It is one character,
    // set in the emoji font (testSmilifySetsJoinedSequencesInTheEmojiFont).
    const QString family = QStringLiteral("👨") + ZERO_WIDTH_JOINER + QStringLiteral("👩")
                           + ZERO_WIDTH_JOINER + QStringLiteral("👧");
    QCOMPARE(smileyPack.smileyfied(family), inPictureSize(family));

    // The heart on fire is newer than the cluster rules of Qt 5.12 (the Windows build), which end
    // the character at the joiner; the fire after it is still a part of it.
    const QString heartOnFire =
        QStringLiteral("❤") + EMOJI_PRESENTATION + ZERO_WIDTH_JOINER + QStringLiteral("🔥");
    QCOMPARE(smileyPack.smileyfied(heartOnFire), inPictureSize(heartOnFire));

    // Qt 5.15 holds the heart on fire together by its own rules. What holds it on Qt 5.12 is that
    // an emoji right after a joiner stays text even where the cluster rules end the character at
    // the joiner, as every Qt does after a letter.
    const QString joinedToLetter = QStringLiteral("a") + ZERO_WIDTH_JOINER + QStringLiteral("🔥");
    QCOMPARE(smileyPack.smileyfied(joinedToLetter), joinedToLetter);

    // The emoji around a sequence keep their pictures.
    QCOMPARE(smileyPack.smileyfied("😊" + family + "😊"),
             QString(SmileyPack::getAsRichText("😊") + inPictureSize(family)
                     + SmileyPack::getAsRichText("😊")));
}

/**
 * @brief Test that a joined sequence the pack has no picture of is set in the colour emoji font of
 *  the system, so that Qt 5 does not take its members from two fonts
 */
void TestSmileyPack::testSmilifySetsJoinedSequencesInTheEmojiFont()
{
    SmileyPack smileyPack{*settings};

    // QA live: the pirate flag (U+1F3F4 U+200D U+2620 U+FE0F) was a flag and a skull on Qt 5.
    const QString pirateFlag = QStringLiteral("🏴") + ZERO_WIDTH_JOINER + QStringLiteral("☠")
                               + EMOJI_PRESENTATION;
    const QString inFont = inPictureSize(pirateFlag);
    QVERIFY(inFont.startsWith(QStringLiteral("<span style=\"font-family:'")));
    QVERIFY(inFont.contains(pirateFlag));
    QCOMPARE(smileyPack.smileyfied(pirateFlag), inFont);
    QCOMPARE(smileyPack.smileyfied("a " + pirateFlag + " b"), QString("a " + inFont + " b"));

    // A skin tone on a member is a part of the sequence: the woman runner, medium skin tone.
    const QString runner = QStringLiteral("🏃🏽") + ZERO_WIDTH_JOINER + QStringLiteral("♀")
                           + EMOJI_PRESENTATION;
    QCOMPARE(smileyPack.smileyfied(runner), inPictureSize(runner));

    // A man and a woman are not two fathers: 👨‍👩‍👦 stays one character.
    const QString parents = QStringLiteral("👨") + ZERO_WIDTH_JOINER + QStringLiteral("👩")
                            + ZERO_WIDTH_JOINER + QStringLiteral("👦");
    QCOMPARE(smileyPack.smileyfied(parents), inPictureSize(parents));
}

/**
 * @brief Test that an emoji with a skin tone, which the pack has no picture of, is set in the colour
 *  emoji font as one character
 */
void TestSmileyPack::testSmilifySetsToneSequencesInTheEmojiFont()
{
    SmileyPack smileyPack{*settings};

    // QA live 2026-11-14: ✌🏽 (U+270C U+1F3FD) was the text font's ✌ and a brown square on the Mac.
    // ✌ alone keeps its picture.
    const QString victoryMedium = QStringLiteral("✌🏽");
    QCOMPARE(smileyPack.smileyfied(victoryMedium), inPictureSize(victoryMedium));
    QCOMPARE(smileyPack.smileyfied(QStringLiteral("✌")), SmileyPack::getAsRichText(QStringLiteral("✌")));
    QCOMPARE(smileyPack.smileyfied("a " + victoryMedium + " " + QStringLiteral("✌")),
             QString("a " + inPictureSize(victoryMedium) + " "
                     + SmileyPack::getAsRichText(QStringLiteral("✌"))));

    // The tone with an emoji presentation selector before it is still the same character.
    const QString victoryAsEmojiMedium = QStringLiteral("✌") + EMOJI_PRESENTATION + QStringLiteral("🏽");
    QCOMPARE(smileyPack.smileyfied(victoryAsEmojiMedium), inPictureSize(victoryAsEmojiMedium));
}

/**
 * @brief Test that the joined sequences the emoji picker does not offer (Android's
 *  KhandaqEmojiFilter) are drawn apart, each member with its picture where the pack has one
 */
void TestSmileyPack::testSmilifyDrawsBlockedSequencesApart()
{
    SmileyPack smileyPack{*settings};

    // 🏳️‍🌈 is U+1F3F3 U+FE0F U+200D U+1F308. The pack has the rainbow, not the white flag: the
    // flag stays text, the rainbow gets its picture, and there is no joiner between them.
    const QString whiteFlag = QStringLiteral("🏳") + EMOJI_PRESENTATION;
    QCOMPARE(smileyPack.smileyfied(whiteFlag + ZERO_WIDTH_JOINER + QStringLiteral("🌈")),
             QString(whiteFlag + SmileyPack::getAsRichText("🌈")));
    // The same without U+FE0F.
    QCOMPARE(smileyPack.smileyfied(QStringLiteral("🏳") + ZERO_WIDTH_JOINER + QStringLiteral("🌈")),
             QString(QStringLiteral("🏳") + SmileyPack::getAsRichText("🌈")));

    // Two fathers and a son: three pictures.
    const QString twoFathers = QStringLiteral("👨") + ZERO_WIDTH_JOINER + QStringLiteral("👨")
                               + ZERO_WIDTH_JOINER + QStringLiteral("👦");
    QCOMPARE(smileyPack.smileyfied(twoFathers),
             QString(SmileyPack::getAsRichText("👨") + SmileyPack::getAsRichText("👨")
                     + SmileyPack::getAsRichText("👦")));

    // A skin tone does not hide a sequence: men, a heart, light and medium-light skin tones. Each
    // member with a skin tone is a character of its own in the emoji font, the heart gets its picture.
    const QString coupleWithTones = QStringLiteral("👨🏻") + ZERO_WIDTH_JOINER + QStringLiteral("❤")
                                    + EMOJI_PRESENTATION + ZERO_WIDTH_JOINER + QStringLiteral("👨🏼");
    QCOMPARE(smileyPack.smileyfied(coupleWithTones),
             QString(inPictureSize(QStringLiteral("👨🏻")) + SmileyPack::getAsRichText("❤")
                     + EMOJI_PRESENTATION + inPictureSize(QStringLiteral("👨🏼"))));

    // A blocked sequence does not touch the one next to it.
    const QString pirateFlag = QStringLiteral("🏴") + ZERO_WIDTH_JOINER + QStringLiteral("☠")
                               + EMOJI_PRESENTATION;
    QCOMPARE(smileyPack.smileyfied(pirateFlag + " " + twoFathers),
             QString(inPictureSize(pirateFlag) + " " + SmileyPack::getAsRichText("👨")
                     + SmileyPack::getAsRichText("👨") + SmileyPack::getAsRichText("👦")));
}

/**
 * @brief Test that U+FE0F, which asks for the colour picture of the emoji before it, leaves the
 *  emoji its picture, and that U+FE0E, which asks for the plain text glyph, gets that glyph
 */
void TestSmileyPack::testSmilifyEmojiPresentationSelector()
{
    SmileyPack smileyPack{*settings};

    // The red heart is ❤ (in the pack) and U+FE0F. The selector stays in the text after the
    // picture, as it always has: a copy of the message keeps the characters the sender typed.
    const QString redHeart = QStringLiteral("❤") + EMOJI_PRESENTATION;
    const QString redHeartPicture = SmileyPack::getAsRichText("❤") + EMOJI_PRESENTATION;
    QCOMPARE(smileyPack.smileyfied(redHeart), redHeartPicture);
    QCOMPARE(smileyPack.smileyfied(redHeart + redHeart), QString(redHeartPicture + redHeartPicture));

    const QString textHeart = QStringLiteral("❤") + TEXT_PRESENTATION;
    QCOMPARE(smileyPack.smileyfied(textHeart), textHeart);
}

/**
 * @brief Test that a keycap stays one character
 */
void TestSmileyPack::testSmilifyKeepsKeycapsWhole()
{
    SmileyPack smileyPack{*settings};

    // The keycap one is "1", U+FE0F and U+20E3. The pack has no keycaps: the system's emoji font
    // draws it, as big as the pictures (QA live 2026-11-14: the text font drew a 1 in a frame).
    const QString keycapOne = QStringLiteral("1") + EMOJI_PRESENTATION + COMBINING_KEYCAP;
    QCOMPARE(smileyPack.smileyfied(keycapOne), inPictureSize(keycapOne));
    QCOMPARE(smileyPack.smileyfied(keycapOne + " 😊"),
             QString(inPictureSize(keycapOne) + " " + SmileyPack::getAsRichText("😊")));

    // Without U+FE0F it is a keycap too; a digit alone stays text.
    const QString keycapHash = QStringLiteral("#") + COMBINING_KEYCAP;
    QCOMPARE(smileyPack.smileyfied(QStringLiteral("1 ") + keycapHash),
             QString(QStringLiteral("1 ") + inPictureSize(keycapHash)));
}

/**
 * @brief Test that text emoticons between words of other scripts behave as they always have
 */
void TestSmileyPack::testSmilifyAsciiEmoticonBetweenWords()
{
    SmileyPack smileyPack{*settings};

    QCOMPARE(smileyPack.smileyfied("Привет :) мир"),
             QString("Привет " + SmileyPack::getAsRichText(":)") + " мир"));
    QCOMPARE(smileyPack.smileyfied(":) :-("),
             QString(SmileyPack::getAsRichText(":)") + " " + SmileyPack::getAsRichText(":-(")));

    const QString glued = QStringLiteral("Привет:) мир");
    QCOMPARE(smileyPack.smileyfied(glued), glued);
}

/**
 * @brief Test that the words around the emoji, in any script, come back as they were sent
 */
void TestSmileyPack::testSmilifyMixedScripts()
{
    SmileyPack smileyPack{*settings};

    // The message of the cross-platform QA run: the pack has none of its emoji whole. The thumb with a
    // skin tone and the flag are set in the emoji font; the digits stay text.
    const QString qaMessage = QStringLiteral(
        "Тест 1 с мака: кириллица, emoji 👍🏽🇹🇷, арабский السلام عليكم, цифры 12345");
    QCOMPARE(smileyPack.smileyfied(qaMessage),
             QString(QStringLiteral("Тест 1 с мака: кириллица, emoji ")
                     + inPictureSize(QStringLiteral("👍🏽")) + inPictureSize(QStringLiteral("🇹🇷"))
                     + QStringLiteral(", арабский السلام عليكم, цифры 12345")));

    QCOMPARE(smileyPack.smileyfied("Привет👍мир 🇷🇺 السلام😊عليكم"),
             QString("Привет" + SmileyPack::getAsRichText("👍") + "мир "
                     + SmileyPack::getAsRichText("🇷🇺") + " السلام"
                     + SmileyPack::getAsRichText("😊") + "عليكم"));
}


/**
 * @brief Test that the picker does not offer what Android's picker does not (KhandaqEmojiFilter)
 */
void TestSmileyPack::testPickerLeavesOutWhatAndroidDoesNotOffer()
{
    const QStringList singles = {
        QStringLiteral("⛪"), QStringLiteral("♈"), QStringLiteral("♓"), QStringLiteral("⛎"),
        QStringLiteral("🔯"), QStringLiteral("✝"), QStringLiteral("☦"), QStringLiteral("✡"),
        QStringLiteral("🕎"), QStringLiteral("🕉"), QStringLiteral("☸"), QStringLiteral("☯"),
        QStringLiteral("🛐"), QStringLiteral("🕍"), QStringLiteral("🛕"), QStringLiteral("⛩"),
        QStringLiteral("👻"), QStringLiteral("👽"), QStringLiteral("👾"), QStringLiteral("👹"),
        QStringLiteral("👺"), QStringLiteral("😈"), QStringLiteral("👿"), QStringLiteral("👬"),
        QStringLiteral("👭"), QStringLiteral("⚧"),
    };
    for (const QString& single : singles) {
        QVERIFY2(!SmileyPack::isOfferedInPicker({single}), qPrintable(single));
        QVERIFY2(!SmileyPack::isOfferedInPicker({single + EMOJI_PRESENTATION}), qPrintable(single));
    }
    // Any skin tone, and every spelling of the emoticon: a text one among them.
    QVERIFY(!SmileyPack::isOfferedInPicker({QStringLiteral("👬🏽")}));
    QVERIFY(!SmileyPack::isOfferedInPicker({QStringLiteral(">:)"), QStringLiteral("😈")}));
    // The joined sequences the picker does not offer are left out as well.
    const QString rainbowFlag =
        QStringLiteral("🏳") + EMOJI_PRESENTATION + ZERO_WIDTH_JOINER + QStringLiteral("🌈");
    QVERIFY(!SmileyPack::isOfferedInPicker({rainbowFlag}));

    // The rest is offered: the mosque, the Kaaba, the star and crescent, a face, a skin tone, a
    // text emoticon, the neighbours of the zodiac. A blocked emoji inside a longer text is not one.
    const QStringList offered = {
        QStringLiteral("🕌"), QStringLiteral("🕋"), QStringLiteral("☪"), QStringLiteral("😊"),
        QStringLiteral("👍🏽"), QStringLiteral(":-)"), QStringLiteral("♻"), QStringLiteral("⛔"),
        QStringLiteral("⛪⛪"),
    };
    for (const QString& emoticon : offered) {
        QVERIFY2(SmileyPack::isOfferedInPicker({emoticon}), qPrintable(emoticon));
    }
    QVERIFY(SmileyPack::isOfferedInPicker({}));
}

/**
 * @brief Test that the picker leaves out of the emojione pack only what Android does not offer
 */
void TestSmileyPack::testPickerOffersTheRestOfThePack()
{
    SmileyPack smileyPack{*settings};
    const QList<QStringList> all = smileyPack.getEmoticons();
    const QString expected = QStringLiteral("⛪♈♉♊♋♌♍♎♏♐♑♒♓⛎🔯👻👽👾👹👺😈👿👬👭");
    int leftOut = 0;
    for (const QStringList& set : all) {
        if (SmileyPack::isOfferedInPicker(set)) {
            continue;
        }
        ++leftOut;
        QString bare = set.first();
        bare.remove(EMOJI_PRESENTATION);
        QVERIFY2(expected.contains(bare), qPrintable(set.join(QLatin1Char(' '))));
    }
    // The 24 of them, eight signs of the zodiac twice: the pack has them with and without U+FE0F.
    QCOMPARE(leftOut, 32);
    QCOMPARE(all.size(), 832);
}

/**
 * @brief KHANDAQ (QA live 2026-11-14): a sequence in the emoji font is as big as the pictures of
 * the pack in the same message (👍🏽 was half as big as 👍); without pictures, as big as the text.
 */
void TestSmileyPack::testSequencesAreAsBigAsThePictures()
{
    const QString thumbsUpMedium = QStringLiteral("👍🏽");
    QTextDocument message;
    message.setHtml(QStringLiteral("a ") + inPictureSize(thumbsUpMedium));
    const QTextCharFormat sized = formatOf(message, thumbsUpMedium);
    QCOMPARE(sized.font().family(), SmileyPack::emojiFontFamily());
    QCOMPARE(sized.intProperty(QTextFormat::FontPixelSize), PICTURE_SIZE);
    QVERIFY(!formatOf(message, QStringLiteral("a")).hasProperty(QTextFormat::FontPixelSize));

    QTextDocument plain;
    plain.setHtml(QStringLiteral("a ") + SmileyPack::getInEmojiFont(thumbsUpMedium, 0));
    const QTextCharFormat unsized = formatOf(plain, thumbsUpMedium);
    QCOMPARE(unsized.font().family(), SmileyPack::emojiFontFamily());
    QVERIFY(!unsized.hasProperty(QTextFormat::FontPixelSize));

    SmileyPack smileyPack{*settings};
    QVERIFY(smileyPack.smileyfied(thumbsUpMedium)
                .contains(QStringLiteral("font-size:%1px").arg(PICTURE_SIZE)));
}

/**
 * @brief The text being typed has its joined sequences set in the emoji font, all but those the
 * picker does not offer
 */
void TestSmileyPack::testEmojiFontRangesLeaveBlockedSequencesApart()
{
    const QString doctor = QStringLiteral("👨") + ZERO_WIDTH_JOINER + QStringLiteral("⚕")
                           + EMOJI_PRESENTATION;
    const QString twoFathers = QStringLiteral("👨") + ZERO_WIDTH_JOINER + QStringLiteral("👨");
    const QString thumbsUpMedium = QStringLiteral("👍🏽");
    const QString text = QStringLiteral("a ") + doctor + QStringLiteral(" b ") + twoFathers
                         + QStringLiteral(" ") + thumbsUpMedium;

    const QVector<QPair<int, int>> ranges = SmileyPack::emojiFontRanges(text);
    QCOMPARE(ranges.size(), 2);
    const auto textOf = [&text](const QPair<int, int>& range) {
        return text.mid(range.first, range.second - range.first);
    };
    QCOMPARE(textOf(ranges.at(0)), doctor);
    QCOMPARE(textOf(ranges.at(1)), thumbsUpMedium);
    QVERIFY(SmileyPack::emojiFontRanges(QStringLiteral("no sequence 😊 here 1")).isEmpty());

    // Flags and keycaps too, in the order of the text.
    const QString keycapOne = QStringLiteral("1") + EMOJI_PRESENTATION + COMBINING_KEYCAP;
    const QString more = QStringLiteral("🇹🇷 ") + keycapOne + QStringLiteral(" ") + doctor;
    const QVector<QPair<int, int>> moreRanges = SmileyPack::emojiFontRanges(more);
    QCOMPARE(moreRanges.size(), 3);
    QCOMPARE(more.mid(moreRanges.at(0).first, moreRanges.at(0).second - moreRanges.at(0).first),
             QStringLiteral("🇹🇷"));
    QCOMPARE(more.mid(moreRanges.at(1).first, moreRanges.at(1).second - moreRanges.at(1).first),
             keycapOne);
    QCOMPARE(more.mid(moreRanges.at(2).first, moreRanges.at(2).second - moreRanges.at(2).first),
             doctor);
}

/**
 * @brief A plain text shown as rich text reads as typed: its markup characters, line breaks and
 * runs of spaces kept, its sequences in the emoji font and those the picker does not offer apart
 */
void TestSmileyPack::testPlainTextAsRichTextKeepsTheText()
{
    const QString doctor = QStringLiteral("👨") + ZERO_WIDTH_JOINER + QStringLiteral("⚕")
                           + EMOJI_PRESENTATION;
    const QString typed = QStringLiteral("<b>a &amp; b</b>  two spaces\nnext line ") + doctor;
    QTextDocument shown;
    shown.setHtml(SmileyPack::plainTextAsRichText(typed));
    QCOMPARE(shown.toPlainText(), typed);
    QCOMPARE(formatOf(shown, doctor).font().family(), SmileyPack::emojiFontFamily());
    QVERIFY(!formatOf(shown, doctor).hasProperty(QTextFormat::FontPixelSize));

    const QString twoFathers = QStringLiteral("👨") + ZERO_WIDTH_JOINER + QStringLiteral("👨");
    QTextDocument apart;
    apart.setHtml(SmileyPack::plainTextAsRichText(twoFathers));
    QCOMPARE(apart.toPlainText(), QStringLiteral("👨👨"));
}

QTEST_GUILESS_MAIN(TestSmileyPack)
#include "smileypack_test.moc"
