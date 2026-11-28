/*
    Copyright © 2026 by The Khandaq Project

    This file is part of Khandaq, a Qt-based graphical interface for Tox.

    Khandaq is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Khandaq is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Khandaq.  If not, see <http://www.gnu.org/licenses/>.
*/

// KHANDAQ (owner, 28.11.2026): the compact transcript. No time column before the text and no
// column for the mark after it: "edited", the time and the mark of ours stand at the far end of the
// text column, on the last line of the text when it has room for them, on a line of their own
// under it otherwise (ChatLine::layoutAir).

#include "src/chatlog/chatmessage.h"
#include "src/chatlog/chatlinecontentproxy.h"
#include "src/chatlog/content/airlabel.h"
#include "src/chatlog/content/airlocationcard.h"
#include "src/chatlog/content/text.h"
#include "src/chatlog/documentcache.h"
#include "src/core/core.h"
#include "src/core/toxfile.h"
#include "src/model/air/airconversation.h"
#include "src/model/air/airlocation.h"
#include "src/model/air/airmessagemarks.h"
#include "src/model/air/airmodel.h"
#include "src/persistence/paths.h"
#include "src/persistence/settings.h"
#include "src/persistence/smileypack.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/style.h"
#include "src/widget/tool/imessageboxmanager.h"

#include "mock/mockbootstraplistgenerator.h"
#include "mock/mockcoresettings.h"

#include <QApplication>
#include <QDir>
#include <QFile>
#include <QGraphicsScene>
#include <QImage>
#include <QPainter>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QtTest/QtTest>

#include <cmath>
#include <memory>
#include <tuple>
#include <vector>

using AirTokens::Color;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
/** Shows no dialog: a Settings needs someone to report to, and the test has nobody to ask. */
class QuietMessages : public IMessageBoxManager
{
public:
    void showInfo(const QString&, const QString&) override {}
    void showWarning(const QString&, const QString&) override {}
    void showError(const QString&, const QString&) override {}
    bool askQuestion(const QString&, const QString&, bool defaultAns, bool, bool) override
    {
        return defaultAns;
    }
    bool askQuestion(const QString&, const QString&, const QString&, const QString&,
                     bool defaultAns, bool) override
    {
        return defaultAns;
    }
    void confirmExecutableOpen(const QFileInfo&) override {}
};

const QDateTime fiveOClock(QDate(2026, 11, 28), QTime(17, 5));

ChatMessage::AirLook member(const QString& name, int tone)
{
    ChatMessage::AirLook look;
    look.who = name;
    look.tone = tone;
    look.spokenWho = name;
    return look;
}

ChatMessage::AirLook ours(Air::EntryStatus status)
{
    ChatMessage::AirLook look;
    look.who = AirStrings::text(AirStrings::Text::You);
    look.tone = Air::youTone;
    look.spokenWho = look.who;
    look.hasMark = true;
    look.status = status;
    return look;
}

/** The parts of a laid-out entry the meta is checked against, in the scene. */
struct Placed
{
    QRectF body;
    QRectF time;
    QRectF mark;
    /** The last line of the text and its baseline, in the scene; null for a body not of words. */
    QRectF lastLine;
    qreal lastBaseline = 0;
    qreal timeBaseline = 0;
    QRectF line;
};

Placed placed(const ChatLine& entry)
{
    Placed parts;
    const ChatLineContent* const body = entry.getContent(1);
    parts.body = body->sceneBoundingRect();
    const auto* const time = qobject_cast<const AirLabel*>(entry.getContent(2));
    parts.time = time->sceneBoundingRect();
    parts.timeBaseline = parts.time.top() + time->baseline();
    const auto* const mark = qobject_cast<const AirMark*>(entry.getContent(3));
    if (mark && mark->isVisible()) {
        parts.mark = mark->sceneBoundingRect();
    }
    if (const auto* const words = qobject_cast<const Text*>(body)) {
        QRectF last;
        qreal baseline = 0;
        if (words->lastTextLine(&last, &baseline)) {
            parts.lastLine = last.translated(parts.body.topLeft());
            parts.lastBaseline = parts.body.top() + baseline;
        }
    }
    parts.line = entry.sceneBoundingRect();
    return parts;
}

/** The first content of line of the type Part: a quote, the notes. */
template <typename Part>
const Part* partOf(const ChatLine& line)
{
    for (int column = 0; line.getContent(column); ++column) {
        if (const auto* const part = qobject_cast<const Part*>(line.getContent(column))) {
            return part;
        }
    }
    return nullptr;
}

/** The far end of the text column of a line width wide, in a left-to-right interface. */
qreal columnEnd(qreal width)
{
    return Space::s16 + qBound<qreal>(0, width - 2 * Space::s16, Size::textMeasure);
}
} // namespace

class TestAirEntryLayout : public QObject
{
    Q_OBJECT
public:
    explicit TestAirEntryLayout(const QString& home_)
        : home{QDir::cleanPath(home_)}
    {
    }

private slots:
    void initTestCase();
    void init();
    void cleanup();
    void cleanupTestCase();

    void theTextStartsAtTheMarginWithoutATimeColumn();
    void theMetaStandsOnTheLastLineWhenItFits();
    void theMetaTakesALineOfItsOwnWhenTheLastLineIsFull();
    void aRightToLeftParagraphHasItsMetaUnderItInALeftToRightInterface();
    void theMirrorInARightToLeftInterface();
    void anEditedEntrySaysSoBeforeItsTime();
    void ourMarkStandsAfterTheTimeWhileItShows();
    void aGroupEntryHasItsNameOverItsTextAtTheMargin();
    void theQuoteAndTheReactionsStayAboveAndBelow();
    void aBodyThatIsNotWordsKeepsClearOfTheMeta();
    void aFileEntryKeepsClearOfTheMeta();
    void screenshots();

private:
    ChatMessage::Ptr message(const ChatMessage::AirLook& look, const QString& words,
                             const Air::EntryMarks& marks = Air::EntryMarks{},
                             const QDateTime& when = fiveOClock);
    /** Laid out width wide at the top of the scene, its words kept as when in view. */
    void layOut(ChatLine& entry, qreal width, bool authorShown = false);
    /**
     * The start of text whose last line leaves less room than the meta needs, cut at the end of a
     * word when wholeWords asks for it; empty when no start of it does.
     */
    QString fullLastLine(const ChatMessage::AirLook& look, const QString& text, qreal width,
                         bool wholeWords = false);

    const QString home;
    QuietMessages messages;
    std::unique_ptr<Settings> settings;
    Style style;
    std::unique_ptr<SmileyPack> smileys;
    std::unique_ptr<DocumentCache> documents;
};

void TestAirEntryLayout::initTestCase()
{
    // A Settings reads and writes the settings file of the Khandaq of this computer: it is made
    // only when that file lies in the home of the test.
    const QString folder = QDir::cleanPath(Paths().getSettingsDirPath());
    if (home.isEmpty() || !folder.startsWith(home + QLatin1Char('/'))) {
        QSKIP("The settings would not stay in the test's home");
    }
    settings.reset(new Settings(messages));
    // The test draws emoji with the font, as the pack is not loaded here.
    settings->setUseEmoticons(false);
    smileys.reset(new SmileyPack(*settings));
    std::ignore = smileys->getEmoticons();
    documents.reset(new DocumentCache(*smileys, *settings));
}

void TestAirEntryLayout::init()
{
    QGuiApplication::setLayoutDirection(Qt::LeftToRight);
}

void TestAirEntryLayout::cleanup()
{
    QGuiApplication::setLayoutDirection(Qt::LeftToRight);
}

void TestAirEntryLayout::cleanupTestCase()
{
    documents.reset();
    smileys.reset();
    settings.reset();
}

ChatMessage::Ptr TestAirEntryLayout::message(const ChatMessage::AirLook& look,
                                             const QString& words, const Air::EntryMarks& marks,
                                             const QDateTime& when)
{
    return ChatMessage::createChatMessage(look, look.who, words, ChatMessage::NORMAL, when, marks,
                                          *documents, *smileys, *settings, style);
}

void TestAirEntryLayout::layOut(ChatLine& entry, qreal width, bool authorShown)
{
    entry.setAuthorShown(authorShown);
    for (int column = 0; column < entry.getColumnCount(); ++column) {
        ChatLineContent* const part = entry.getContent(column);
        // A place would fetch its map once in view.
        if (!qobject_cast<AirLocationCard*>(part)) {
            part->visibilityChanged(true);
        }
    }
    entry.layout(width, QPointF(0, 0));
}

QString TestAirEntryLayout::fullLastLine(const ChatMessage::AirLook& look, const QString& text,
                                         qreal width, bool wholeWords)
{
    // One letter more at a time: the last line grows to the end of the column before it wraps.
    for (int length = 1; length <= text.size(); ++length) {
        const QString words = text.left(length);
        const bool wordEnds = length == text.size() || text.at(length) == QLatin1Char(' ');
        if (words.endsWith(QLatin1Char(' ')) || (wholeWords && !wordEnds)) {
            continue;
        }
        const ChatMessage::Ptr entry = message(look, words);
        layOut(*entry, width);
        const Placed parts = placed(*entry);
        const qreal meta = parts.mark.isNull() ? parts.time.width()
                                               : parts.mark.right() - parts.time.left();
        if (!parts.lastLine.isNull() && columnEnd(width) - parts.lastLine.right() < Space::s8 + meta) {
            return words;
        }
    }
    return QString();
}

/**
 * @brief The author and the text start at the 16 px margin, and the text runs to the margin at the
 * far end, at most 760 px wide; the day mark and a line nobody wrote stand at the margin too.
 */
void TestAirEntryLayout::theTextStartsAtTheMarginWithoutATimeColumn()
{
    const ChatMessage::Ptr entry = message(member(QStringLiteral("Amina"), 2),
                                           QStringLiteral("See you at five"));
    layOut(*entry, 600, true);
    const ChatLineContent* const author = entry->getContent(0);
    const ChatLineContent* const body = entry->getContent(1);
    QVERIFY(author->isVisible());
    QCOMPARE(author->pos().x(), qreal(Space::s16));
    QCOMPARE(body->pos().x(), qreal(Space::s16));
    QCOMPARE(body->boundingRect().width(), 600.0 - 2 * Space::s16);
    // The time is no longer before the text.
    QVERIFY(entry->getContent(2)->pos().x() > body->pos().x() + Size::timeColumn);

    layOut(*entry, 1200, true);
    QCOMPARE(body->pos().x(), qreal(Space::s16));
    QCOMPARE(body->boundingRect().width(), qreal(Size::textMeasure));

    const ChatMessage::Ptr day =
        ChatMessage::createDayMark(QStringLiteral("Today"), *documents, *settings, style);
    day->layout(600, QPointF(0, 0));
    QCOMPARE(day->getContent(0)->pos().x(), qreal(Space::s16));

    // A line nobody wrote: its words at the margin, its time at the far end of their line.
    const ChatMessage::Ptr info =
        ChatMessage::createChatInfoMessage(QStringLiteral("The title is now Weekend camp"),
                                           ChatMessage::INFO, fiveOClock, *documents, *settings,
                                           style);
    layOut(*info, 600);
    const Placed infoParts = placed(*info);
    QCOMPARE(infoParts.body.left(), qreal(Space::s16));
    QCOMPARE(infoParts.time.right(), columnEnd(600));
    QVERIFY(std::abs(infoParts.timeBaseline - infoParts.lastBaseline) < 0.01);
}

/**
 * @brief A short line, and two lines whose last one is short: the time stands on the last line at
 * the far end of the column, on its baseline, and takes no height of its own.
 */
void TestAirEntryLayout::theMetaStandsOnTheLastLineWhenItFits()
{
    const qreal width = 600;
    for (const QString& words :
         {QStringLiteral("See you at five"), QStringLiteral("The bus leaves the square\nat six")}) {
        const ChatMessage::Ptr entry = message(member(QStringLiteral("Amina"), 2), words);
        layOut(*entry, width);
        const Placed parts = placed(*entry);
        QVERIFY(!parts.lastLine.isNull());
        QCOMPARE(parts.time.right(), columnEnd(width));
        QVERIFY(parts.time.left() >= parts.lastLine.right() + Space::s8);
        QVERIFY2(std::abs(parts.timeBaseline - parts.lastBaseline) < 0.01,
                 qPrintable(QStringLiteral("the time's baseline %1 is not the text's %2")
                                .arg(parts.timeBaseline)
                                .arg(parts.lastBaseline)));
        QVERIFY(parts.time.bottom() <= parts.line.bottom());
        // As tall as its text with the 4 px above and below.
        QCOMPARE(parts.line.height(), Space::s4 + parts.body.height() + Space::s4);
    }
    // The second has two lines, the time is on the lower one.
    const ChatMessage::Ptr twoLines = message(member(QStringLiteral("Amina"), 2),
                                              QStringLiteral("The bus leaves the square\nat six"));
    layOut(*twoLines, width);
    const Placed parts = placed(*twoLines);
    QVERIFY(parts.lastLine.top() > parts.body.top());
    QVERIFY(parts.time.top() > parts.body.top() + parts.lastLine.height() / 2);
}

/**
 * @brief A last line that leaves less than 8 px and the meta at its end: the meta takes a line of
 * its own right under the text, at the far end, as tall as itself.
 */
void TestAirEntryLayout::theMetaTakesALineOfItsOwnWhenTheLastLineIsFull()
{
    const qreal width = 420;
    const ChatMessage::AirLook amina = member(QStringLiteral("Amina"), 2);
    const QString words = fullLastLine(
        amina,
        QStringLiteral("We are taking the tent, the stove and two lamps, and the rest stays at home "
                       "until the weekend"),
        width);
    QVERIFY2(!words.isEmpty(), "no text fills its last line");

    const ChatMessage::Ptr entry = message(amina, words);
    layOut(*entry, width);
    const Placed parts = placed(*entry);
    QCOMPARE(parts.time.right(), columnEnd(width));
    QCOMPARE(parts.time.top(), parts.body.bottom());
    QCOMPARE(parts.line.height(), Space::s4 + parts.body.height() + parts.time.height() + Space::s4);

    // One letter less leaves the room, and the time goes back up.
    const ChatMessage::Ptr shorter = message(amina, words.left(words.size() - 1));
    layOut(*shorter, width);
    const Placed before = placed(*shorter);
    if (before.body.height() == parts.body.height()) {
        QVERIFY(before.time.bottom() <= before.body.bottom() + 1);
    }

    // Ours: the meta is the time and the mark, and takes the mark's height.
    const QString oursFull =
        fullLastLine(ours(Air::EntryStatus::Delivered), QStringLiteral("We are taking the tent, "
                                                                       "the stove and two lamps, "
                                                                       "and the rest stays home"),
                     width);
    QVERIFY(!oursFull.isEmpty());
    const ChatMessage::Ptr our = message(ours(Air::EntryStatus::Delivered), oursFull);
    layOut(*our, width);
    const Placed ourParts = placed(*our);
    QVERIFY(!ourParts.mark.isNull());
    QCOMPARE(ourParts.mark.right(), columnEnd(width));
    QCOMPARE(ourParts.mark.top(), ourParts.body.bottom());
    QCOMPARE(ourParts.line.height(), Space::s4 + ourParts.body.height() + Size::mark + Space::s4);
}

/**
 * @brief An Arabic paragraph in a left-to-right interface stands at the right, by its own
 * direction: its last line has no room at its end, and the time goes under it.
 */
void TestAirEntryLayout::aRightToLeftParagraphHasItsMetaUnderItInALeftToRightInterface()
{
    const qreal width = 600;
    const ChatMessage::Ptr entry =
        message(member(QStringLiteral("Amina"), 2), QStringLiteral("نلتقي بعد صلاة العصر"));
    layOut(*entry, width);
    const Placed parts = placed(*entry);
    QVERIFY(!parts.lastLine.isNull());
    QVERIFY2(parts.lastLine.right() > columnEnd(width) - 1, "the paragraph is not at the right");
    QVERIFY(parts.lastLine.left() > parts.body.left() + parts.body.width() / 2);
    QCOMPARE(parts.time.right(), columnEnd(width));
    QCOMPARE(parts.time.top(), parts.body.bottom());

    // A paragraph in each direction: the last one decides.
    const ChatMessage::Ptr mixed = message(member(QStringLiteral("Amina"), 2),
                                           QStringLiteral("نلتقي بعد صلاة العصر\nAt the gate"));
    layOut(*mixed, width);
    const Placed mixedParts = placed(*mixed);
    QVERIFY(mixedParts.time.bottom() <= mixedParts.body.bottom() + 1);
    QVERIFY(std::abs(mixedParts.timeBaseline - mixedParts.lastBaseline) < 0.01);
}

/**
 * @brief In an Arabic interface the meta stands at the left end: on the last line of an Arabic
 * paragraph, which stands at the right, and under an English one, which stands at the left.
 */
void TestAirEntryLayout::theMirrorInARightToLeftInterface()
{
    QGuiApplication::setLayoutDirection(Qt::RightToLeft);
    const qreal width = 600;
    const ChatMessage::Ptr arabic =
        message(member(QStringLiteral("Amina"), 2), QStringLiteral("نلتقي بعد صلاة العصر"));
    layOut(*arabic, width, true);
    const Placed parts = placed(*arabic);
    // The author and the text at the margin of the right edge.
    QCOMPARE(arabic->getContent(0)->sceneBoundingRect().right(), width - Space::s16);
    QCOMPARE(parts.body.right(), width - Space::s16);
    QCOMPARE(parts.time.left(), qreal(Space::s16));
    QVERIFY(parts.time.right() + Space::s8 <= parts.lastLine.left());
    QVERIFY(std::abs(parts.timeBaseline - parts.lastBaseline) < 0.01);

    const ChatMessage::Ptr english =
        message(member(QStringLiteral("Amina"), 2), QStringLiteral("See you at five"));
    layOut(*english, width);
    const Placed englishParts = placed(*english);
    QVERIFY(englishParts.lastLine.left() < englishParts.body.left() + 1);
    QCOMPARE(englishParts.time.left(), qreal(Space::s16));
    QCOMPARE(englishParts.time.top(), englishParts.body.bottom());

    // Emoji have no direction of their own: they run as the interface does, from the right, and
    // the time stands on their line.
    const ChatMessage::Ptr emoji =
        message(member(QStringLiteral("Amina"), 2), QStringLiteral("\U0001F44D \U0001F389"));
    layOut(*emoji, width);
    const Placed emojiParts = placed(*emoji);
    QVERIFY(emojiParts.lastLine.right() > emojiParts.body.right() - 1);
    QVERIFY(std::abs(emojiParts.timeBaseline - emojiParts.lastBaseline) < 0.01);
    QCOMPARE(emojiParts.time.left(), qreal(Space::s16));

    // Figures are read left to right in every language: the coordinates of a place keep their
    // order, start at the left, and have the time under them, as English does.
    const ChatMessage::Ptr figures =
        message(member(QStringLiteral("Amina"), 2), QStringLiteral("39.92077, 32.85411"));
    layOut(*figures, width);
    const Placed figureParts = placed(*figures);
    QVERIFY(figureParts.lastLine.left() < figureParts.body.left() + 1);
    QCOMPARE(figureParts.time.left(), qreal(Space::s16));
    QCOMPARE(figureParts.time.top(), figureParts.body.bottom());

    // Ours: the mark at the end, the time after it in the direction of the interface.
    const ChatMessage::Ptr our =
        message(ours(Air::EntryStatus::Delivered), QStringLiteral("نلتقي بعد صلاة العصر"));
    layOut(*our, width);
    const Placed ourParts = placed(*our);
    QCOMPARE(ourParts.mark.left(), qreal(Space::s16));
    QCOMPARE(ourParts.time.left(), ourParts.mark.right() + Space::s4);
}

/**
 * @brief "edited" stands before the time, 4 px apart, and no longer on a line under the text; a
 * copy takes the time without it, a screen reader still says it once.
 */
void TestAirEntryLayout::anEditedEntrySaysSoBeforeItsTime()
{
    const qreal width = 600;
    Air::EntryMarks edited;
    edited.edited = true;
    const ChatMessage::Ptr entry =
        message(ours(Air::EntryStatus::Delivered), QStringLiteral("Five is fine"), edited);
    layOut(*entry, width);
    auto* const time = qobject_cast<AirLabel*>(entry->getContent(2));
    const QString word = AirStrings::text(AirStrings::Text::MessageEdited);
    QCOMPARE(time->getLead(), word);
    QCOMPARE(time->getText(), QStringLiteral("17:05"));
    const QFontMetricsF caption(AirStyle::instance().font(AirTokens::TextRole::Caption));
    QCOMPARE(time->naturalWidth(), caption.horizontalAdvance(word) + Space::s4
                                       + caption.horizontalAdvance(QStringLiteral("17:05")));

    const Placed parts = placed(*entry);
    QCOMPARE(parts.mark.right(), columnEnd(width));
    QCOMPARE(parts.time.right(), parts.mark.left() - Space::s4);
    QVERIFY(std::abs(parts.timeBaseline - parts.lastBaseline) < 0.01);
    // No line of its own under the text.
    QCOMPARE(partOf<AirEntryNotes>(*entry)->boundingRect().height(), 0.0);
    QCOMPARE(parts.line.height(), Space::s4 + parts.body.height() + Space::s4);
    QCOMPARE(entry->spokenText().count(word), 1);

    // The word comes and goes with the edit, and the line is laid out again for it.
    QVERIFY(entry->updateMarks(Air::EntryMarks{}));
    QCOMPARE(time->getLead(), QString());
    QVERIFY(!entry->updateMarks(Air::EntryMarks{}));
    QVERIFY(entry->updateMarks(edited));
    QCOMPARE(time->getLead(), word);
}

/**
 * @brief Our mark stands at the far end, the time 4 px before it; a text of ours in a group that
 * went has no glyph, and its time stands at the end by itself.
 */
void TestAirEntryLayout::ourMarkStandsAfterTheTimeWhileItShows()
{
    const qreal width = 600;
    const ChatMessage::Ptr entry =
        message(ours(Air::EntryStatus::Transit), QStringLiteral("On my way"));
    layOut(*entry, width);
    Placed parts = placed(*entry);
    QVERIFY(!parts.mark.isNull());
    QCOMPARE(parts.mark.right(), columnEnd(width));
    QCOMPARE(parts.time.right(), parts.mark.left() - Space::s4);
    // The mark stands in the middle of the time.
    QCOMPARE(parts.mark.center().y(), parts.time.center().y());

    QVERIFY(entry->setStatus(Air::EntryStatus::None));
    layOut(*entry, width);
    parts = placed(*entry);
    QVERIFY(parts.mark.isNull());
    QCOMPARE(parts.time.right(), columnEnd(width));
    QVERIFY(!entry->setStatus(Air::EntryStatus::None));
    QVERIFY(entry->setStatus(Air::EntryStatus::Failed));
    QVERIFY(!entry->setStatus(Air::EntryStatus::Transit));
}

/**
 * @brief In a group the name of another member stands over the run in their tone, at the margin
 * as the text (the desktop draws no tiles of authors), 4 px over the text.
 */
void TestAirEntryLayout::aGroupEntryHasItsNameOverItsTextAtTheMargin()
{
    const qreal width = 600;
    const ChatMessage::Ptr entry = message(member(QStringLiteral("Bilal Rahman"), 4),
                                           QStringLiteral("Who brings the water?"));
    layOut(*entry, width, true);
    const ChatLineContent* const author = entry->getContent(0);
    const Placed parts = placed(*entry);
    QCOMPARE(author->pos().x(), qreal(Space::s16));
    QCOMPARE(parts.body.left(), qreal(Space::s16));
    QCOMPARE(parts.body.top(), author->sceneBoundingRect().bottom() + Space::s4);
    QCOMPARE(parts.time.right(), columnEnd(width));
    QVERIFY(parts.time.top() >= parts.body.top());
    // Spoken as before: who, when, the words.
    QCOMPARE(entry->spokenText(),
             AirStrings::spokenEntry(QStringLiteral("Bilal Rahman"), QStringLiteral("17:05"),
                                     QStringLiteral("Who brings the water?"),
                                     Air::EntryStatus::None, false));
}

/**
 * @brief The quote of a reply stays over the text and the reactions under it: the meta belongs to
 * the last line of the text, above the chips.
 */
void TestAirEntryLayout::theQuoteAndTheReactionsStayAboveAndBelow()
{
    const qreal width = 600;
    Air::ReplyMeta meta;
    meta.localId = 42;
    meta.authorKey = QStringLiteral("56A1ADE4");
    meta.timeMs = 1764331500000;
    meta.authorName = QStringLiteral("Amina");
    meta.words = QStringLiteral("See you at five");
    Air::EntryMarks reacted;
    reacted.react(QStringLiteral("person-1"), QStringLiteral("\U0001F44D"), true);
    const ChatMessage::Ptr entry =
        message(ours(Air::EntryStatus::Seen), Air::replyHead(meta) + QStringLiteral("Five is fine"),
                reacted);
    layOut(*entry, width);
    const QRectF quote = partOf<AirQuote>(*entry)->sceneBoundingRect();
    const QRectF notes = partOf<AirEntryNotes>(*entry)->sceneBoundingRect();
    const Placed parts = placed(*entry);
    QVERIFY(quote.height() > 0);
    QCOMPARE(quote.left(), qreal(Space::s16));
    QCOMPARE(parts.body.top(), quote.bottom() + Space::s4);
    QVERIFY(std::abs(parts.timeBaseline - parts.lastBaseline) < 0.01);
    QCOMPARE(notes.top(), parts.body.bottom());
    QVERIFY(parts.mark.bottom() <= notes.top() + Space::s4);

    // With the meta on a line of its own, the chips come under that line.
    const QString full = fullLastLine(
        ours(Air::EntryStatus::Seen),
        QStringLiteral("We are taking the tent, the stove and two lamps, and the rest stays home "
                       "until the weekend comes"),
        width);
    QVERIFY(!full.isEmpty());
    const ChatMessage::Ptr longer = message(ours(Air::EntryStatus::Seen), full, reacted);
    layOut(*longer, width);
    const Placed longParts = placed(*longer);
    QCOMPARE(partOf<AirEntryNotes>(*longer)->sceneBoundingRect().top(), longParts.mark.bottom());
}

/**
 * @brief A call and a place are not words: the body keeps 8 px clear of the meta, which stands
 * at the far end at the foot of the body.
 */
void TestAirEntryLayout::aBodyThatIsNotWordsKeepsClearOfTheMeta()
{
    for (const qreal width : {600.0, 200.0}) {
        const ChatMessage::Ptr call = ChatMessage::createCallEntry(
            QStringLiteral("Call, 4:12"), true, fiveOClock, *documents, *settings, style);
        layOut(*call, width);
        const Placed parts = placed(*call);
        QVERIFY(parts.lastLine.isNull());
        QCOMPARE(parts.time.right(), columnEnd(width));
        QCOMPARE(parts.time.bottom(), parts.body.bottom());
        QCOMPARE(parts.body.right(), parts.time.left() - Space::s8);
        QCOMPARE(parts.line.height(), Space::s4 + parts.body.height() + Space::s4);
    }

    const ChatMessage::Ptr place = message(member(QStringLiteral("Amina"), 2),
                                           QStringLiteral("khandaq-location:55.75580,37.61730"));
    QVERIFY(qobject_cast<AirLocationCard*>(place->getContent(1)));
    layOut(*place, 600);
    const Placed parts = placed(*place);
    QCOMPARE(parts.time.right(), columnEnd(600));
    QCOMPARE(parts.time.bottom(), parts.body.bottom());
    QVERIFY(parts.body.right() <= parts.time.left() - Space::s8);
}

/**
 * @brief A file card keeps clear of the meta too; in a window too narrow for its least width the
 * meta takes a line of its own under it.
 */
void TestAirEntryLayout::aFileEntryKeepsClearOfTheMeta()
{
    // A file entry needs the files of a core: one that is never started or told of a node, so it
    // stays off every network.
    MockSettings coreSettings;
    MockBootstrapListGenerator noNodes;
    const ToxCorePtr core = Core::makeToxCore({}, coreSettings, noNodes);
    if (!core) {
        QSKIP("No core to take the files of");
    }
    ToxFile file(1, 0, QStringLiteral("route.pdf"), QString(), 2048, ToxFile::RECEIVING);
    file.status = ToxFile::FINISHED;
    // The card takes 60% of its room and at least 320 px: at 300 px it cannot keep clear.
    for (const qreal width : {600.0, 300.0}) {
        const ChatMessage::Ptr entry = ChatMessage::createFileTransferMessage(
            member(QStringLiteral("Amina"), 2), *core->getCoreFile(), file, fiveOClock,
            Air::EntryMarks{}, *documents, *smileys, *settings, style, messages);
        const auto* const card = qobject_cast<ChatLineContentProxy*>(entry->getContent(1));
        QVERIFY(card);
        layOut(*entry, width);
        const Placed parts = placed(*entry);
        QCOMPARE(parts.time.right(), columnEnd(width));
        if (width > 400) {
            QVERIFY(parts.body.right() <= parts.time.left() - Space::s8);
            // At the foot of the card, not of the room its proxy keeps under it.
            QCOMPARE(parts.time.bottom(), parts.body.top() + card->getWidget()->height());
            QCOMPARE(parts.line.height(), Space::s4 + parts.body.height() + Space::s4);
        } else {
            QVERIFY(parts.body.right() > parts.time.left() - Space::s8);
            QCOMPARE(parts.time.top(), parts.body.bottom());
        }
    }
}

void TestAirEntryLayout::screenshots()
{
    const QString folder = qEnvironmentVariable("KHANDAQ_TRANSCRIPT_SHOTS");
    if (folder.isEmpty()) {
        QSKIP("KHANDAQ_TRANSCRIPT_SHOTS names no folder for the pictures");
    }
    QVERIFY(QDir().mkpath(folder));
    MockSettings coreSettings;
    MockBootstrapListGenerator noNodes;
    const ToxCorePtr core = Core::makeToxCore({}, coreSettings, noNodes);

    const ChatMessage::AirLook amina = member(QStringLiteral("Amina"), 2);
    const ChatMessage::AirLook bilal = member(QStringLiteral("Bilal Rahman"), 4);
    Air::ReplyMeta meta;
    meta.localId = 42;
    meta.authorKey = QStringLiteral("56A1ADE4");
    meta.timeMs = 1764331500000;
    meta.authorName = amina.who;
    meta.words = QStringLiteral("The bus leaves the square at half past six, the second one at "
                                "seven, so we are in time");
    Air::EntryMarks edited;
    edited.edited = true;
    Air::EntryMarks reacted;
    reacted.react(QStringLiteral("person-1"), QStringLiteral("\U0001F44D"), true);
    reacted.react(QStringLiteral("person-2"), QStringLiteral("\U0001F44D"), true);
    reacted.react(QStringLiteral("person-3"), QStringLiteral("❤️"), true);
    const qreal width = 560;
    // A text whose last line is full, cut where a word ends when one of its starts fills it.
    const QString sentence = QStringLiteral("Please bring the tent, the stove, two lamps and the big pot "
                                        "for the evening meal by the lake");
    QString full = fullLastLine(amina, sentence, width, true);
    if (full.isEmpty()) {
        full = fullLastLine(amina, sentence, width);
    }
    QVERIFY(!full.isEmpty());

    for (const bool rightToLeft : {false, true}) {
        QGuiApplication::setLayoutDirection(rightToLeft ? Qt::RightToLeft : Qt::LeftToRight);
        for (const bool dark : {false, true}) {
            settings->setThemeMode(dark ? Settings::ThemeMode::Dark : Settings::ThemeMode::Light);
            AirStyle::instance().follow(*settings, style, nullptr);
            const QColor ground = AirStyle::instance().color(Color::Bg);

            struct Shown
            {
                ChatMessage::Ptr entry;
                bool authorShown;
            };
            std::vector<Shown> entries;
            const auto add = [&](const ChatMessage::Ptr& entry, bool authorShown) {
                entries.push_back({entry, authorShown});
            };
            QDateTime when = fiveOClock;
            const auto next = [&when]() {
                when = when.addSecs(60);
                return when;
            };
            add(ChatMessage::createDayMark(QStringLiteral("Today"), *documents, *settings, style),
                false);
            add(message(amina, QStringLiteral("See you at five"), Air::EntryMarks{}, next()), true);
            add(message(amina, meta.words, Air::EntryMarks{}, next()), false);
            add(message(amina, full, Air::EntryMarks{}, next()), false);
            add(message(amina, QStringLiteral("نلتقي بعد صلاة العصر عند البوابة"),
                        Air::EntryMarks{}, next()),
                false);
            add(message(ours(Air::EntryStatus::Delivered), QStringLiteral("On my way"),
                        Air::EntryMarks{}, next()),
                true);
            add(message(ours(Air::EntryStatus::Seen), QStringLiteral("Five is fine, see you there"),
                        edited, next()),
                false);
            add(message(ours(Air::EntryStatus::Seen),
                        Air::replyHead(meta) + QStringLiteral("Then the second one"),
                        Air::EntryMarks{}, next()),
                false);
            add(message(bilal, QStringLiteral("Who brings the water?"), reacted, next()), true);
            if (core) {
                ToxFile file(1, 0, QStringLiteral("route-to-the-camp.pdf"), QString(), 284000,
                             ToxFile::RECEIVING);
                file.status = ToxFile::FINISHED;
                add(ChatMessage::createFileTransferMessage(bilal, *core->getCoreFile(), file,
                                                           next(), Air::EntryMarks{}, *documents,
                                                           *smileys, *settings, style, messages),
                    false);
            }
            add(ChatMessage::createCallEntry(QStringLiteral("Call, 4:12"), true, next(),
                                             *documents, *settings, style),
                false);
            add(ChatMessage::createChatInfoMessage(QStringLiteral("The title is now Weekend camp"),
                                                   ChatMessage::INFO, next(), *documents,
                                                   *settings, style),
                false);
            add(message(ours(Air::EntryStatus::Transit), QStringLiteral("Leaving now"),
                        Air::EntryMarks{}, next()),
                true);

            QGraphicsScene scene;
            qreal y = Space::s8;
            for (const Shown& shown : entries) {
                shown.entry->addToScene(&scene);
                layOut(*shown.entry, width, shown.authorShown);
                shown.entry->layout(width, QPointF(0, y));
                y += shown.entry->sceneBoundingRect().height();
            }
            // The meta of each entry stays in the column.
            for (const Shown& shown : entries) {
                if (shown.entry->getLayout() != ChatLine::Layout::AirEntry) {
                    continue;
                }
                const Placed parts = placed(*shown.entry);
                QVERIFY(parts.time.left() >= Space::s16 - 0.01);
                QVERIFY(parts.time.right() <= width - Space::s16 + 0.01);
            }
            const QRectF area(0, 0, width, y + Space::s8);
            const qreal scale = 2;
            QImage image(qCeil(area.width() * scale), qCeil(area.height() * scale),
                         QImage::Format_ARGB32_Premultiplied);
            image.setDevicePixelRatio(scale);
            image.fill(ground);
            QPainter painter(&image);
            painter.setRenderHint(QPainter::Antialiasing);
            painter.setRenderHint(QPainter::TextAntialiasing);
            scene.render(&painter, area, area);
            painter.end();
            const QString name =
                QStringLiteral("transcript-%1-%2")
                    .arg(rightToLeft ? QStringLiteral("rtl") : QStringLiteral("ltr"),
                         dark ? QStringLiteral("dark") : QStringLiteral("light"));
            const QString path = QDir(folder).filePath(name + QStringLiteral(".png"));
            QVERIFY2(image.save(path), qPrintable(path));
            for (const Shown& shown : entries) {
                shown.entry->removeFromScene();
            }
        }
    }
    settings->setThemeMode(Settings::ThemeMode::Light);
    AirStyle::instance().follow(*settings, style, nullptr);
}

int main(int argc, char* argv[])
{
    // The entries need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    // The entries need a Settings, which reads and writes the settings of the home folder: the
    // test gets a home of its own, which goes with it.
    QTemporaryDir home;
    if (home.isValid()) {
        qputenv("HOME", QFile::encodeName(home.path()));
        QStandardPaths::setTestModeEnabled(true);
    }
    QApplication app(argc, argv);
    // The Plex faces, as the app has them: the line heights are theirs.
    AirStyle::installFonts();
    TestAirEntryLayout test(home.isValid() ? home.path() : QString());
    return QTest::qExec(&test, argc, argv);
}

#include "airentrylayout_test.moc"
