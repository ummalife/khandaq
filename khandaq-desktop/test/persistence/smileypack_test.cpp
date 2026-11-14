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

#include <memory>

class MockSettings : public QObject, public ISmileySettings
{
Q_OBJECT
public:
    QString getSmileyPack() const override;
    SIGNAL_IMPL(MockSettings, smileyPackChanged, const QString& name);
};

QString MockSettings::getSmileyPack() const
{
    return ":/smileys/emojione/emoticons.xml";
}

namespace {
// KHANDAQ (QA 2026-11-14, F1): the joiner, the presentation selectors and the keycap mark cannot be
// seen in a literal, so the tests below spell them out.
const QChar ZERO_WIDTH_JOINER{0x200D};
const QChar TEXT_PRESENTATION{0xFE0E};
const QChar EMOJI_PRESENTATION{0xFE0F};
const QChar COMBINING_KEYCAP{0x20E3};
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
    void testSmilifyAsciiEmoticonBetweenWords();
    void testSmilifyMixedScripts();
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
    // skin tone alone (a brown square) is what the QA run saw.
    const QString thumbsUpMedium = QStringLiteral("👍🏽");
    QCOMPARE(smileyPack.smileyfied(thumbsUpMedium), thumbsUpMedium);

    // A character left to the system font does not keep the next one from its picture.
    QCOMPARE(smileyPack.smileyfied(thumbsUpMedium + "👍"),
             QString(thumbsUpMedium + SmileyPack::getAsRichText("👍")));
}

/**
 * @brief Test that a flag the pack does not have stays one flag: the pack has pictures for the
 *  regional indicator letters, and two letters side by side are a flag, not two letters
 */
void TestSmileyPack::testSmilifyKeepsFlagsWhole()
{
    SmileyPack smileyPack{*settings};

    // 🇹🇷 is U+1F1F9 U+1F1F7. The pack has both letters alone and not the flag.
    const QString turkey = QStringLiteral("🇹🇷");
    QCOMPARE(smileyPack.smileyfied(turkey), turkey);

    // Letters pair up from the start of a run: 🇹🇷🇷🇺 is Turkey, then Russia, which the pack has.
    QCOMPARE(smileyPack.smileyfied(turkey + "🇷🇺"),
             QString(turkey + SmileyPack::getAsRichText("🇷🇺")));

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

    // A family: the pack has the man, the woman and the girl, not the family.
    const QString family = QStringLiteral("👨") + ZERO_WIDTH_JOINER + QStringLiteral("👩")
                           + ZERO_WIDTH_JOINER + QStringLiteral("👧");
    QCOMPARE(smileyPack.smileyfied(family), family);

    // The heart on fire is newer than the cluster rules of Qt 5.12 (the Windows build), which end
    // the character at the joiner; the fire after it is still a part of it.
    const QString heartOnFire =
        QStringLiteral("❤") + EMOJI_PRESENTATION + ZERO_WIDTH_JOINER + QStringLiteral("🔥");
    QCOMPARE(smileyPack.smileyfied(heartOnFire), heartOnFire);

    // Qt 5.15 holds the heart on fire together by its own rules. What holds it on Qt 5.12 is that
    // an emoji right after a joiner stays text even where the cluster rules end the character at
    // the joiner, as every Qt does after a letter.
    const QString joinedToLetter = QStringLiteral("a") + ZERO_WIDTH_JOINER + QStringLiteral("🔥");
    QCOMPARE(smileyPack.smileyfied(joinedToLetter), joinedToLetter);

    // The emoji around a sequence keep their pictures.
    QCOMPARE(smileyPack.smileyfied("😊" + family + "😊"),
             QString(SmileyPack::getAsRichText("😊") + family + SmileyPack::getAsRichText("😊")));
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

    // The keycap one is "1", U+FE0F and U+20E3.
    const QString keycapOne = QStringLiteral("1") + EMOJI_PRESENTATION + COMBINING_KEYCAP;
    QCOMPARE(smileyPack.smileyfied(keycapOne), keycapOne);
    QCOMPARE(smileyPack.smileyfied(keycapOne + " 😊"),
             QString(keycapOne + " " + SmileyPack::getAsRichText("😊")));
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

    // The message of the cross-platform QA run: the pack has none of its emoji whole.
    const QString qaMessage = QStringLiteral(
        "Тест 1 с мака: кириллица, emoji 👍🏽🇹🇷, арабский السلام عليكم, цифры 12345");
    QCOMPARE(smileyPack.smileyfied(qaMessage), qaMessage);

    QCOMPARE(smileyPack.smileyfied("Привет👍мир 🇷🇺 السلام😊عليكم"),
             QString("Привет" + SmileyPack::getAsRichText("👍") + "мир "
                     + SmileyPack::getAsRichText("🇷🇺") + " السلام"
                     + SmileyPack::getAsRichText("😊") + "عليكم"));
}


QTEST_GUILESS_MAIN(TestSmileyPack)
#include "smileypack_test.moc"
