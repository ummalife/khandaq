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

#include "src/chatlog/content/airlabel.h"
#include "src/model/air/airmessagemarks.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"

#include <QApplication>
#include <QImage>
#include <QPainter>
#include <QtTest/QtTest>

#include <cmath>
#include <utility>
#include <vector>

using AirTokens::Color;
namespace Size = AirTokens::Size;
namespace Space = AirTokens::Space;

namespace {
/** The marks of an entry: each emoji with as many people as given, each person one reaction. */
Air::EntryMarks reacted(const std::vector<std::pair<QString, int>>& reactions, bool edited = false)
{
    Air::EntryMarks marks;
    marks.edited = edited;
    int person = 0;
    for (const auto& reaction : reactions) {
        for (int i = 0; i < reaction.second; ++i) {
            marks.react(QStringLiteral("person-%1").arg(person++), reaction.first, true);
        }
    }
    return marks;
}

/** The notes painted at width on the ground of the log. */
QImage painted(AirEntryNotes& notes, int width)
{
    notes.setWidth(width);
    const int height = static_cast<int>(std::ceil(notes.boundingRect().height()));
    QImage image(width, qMax(1, height), QImage::Format_ARGB32_Premultiplied);
    image.fill(AirStyle::instance().color(Color::Bg));
    QPainter painter(&image);
    notes.paint(&painter, nullptr, nullptr);
    painter.end();
    return image;
}

const QString writingHand = QStringLiteral("✍️");
const QString thumbsUp = QStringLiteral("\U0001F44D");
} // namespace

/**
 * The reactions under an entry on the desktop (0.2.61): chips as the phones draw them, each on the
 * raised plate with a hairline, the count after the emoji, and an emoji the smiley pack has no
 * picture of drawn in the emoji font. They were a line of label text in ink-2, where the ✍️ of an
 * iPhone came out as a small grey ✍ that nobody took for a reaction.
 */
class TestAirEntryNotes : public QObject
{
    Q_OBJECT
private slots:
    void chipsTakeALineOfTheirOwn();
    void eachReactionIsAChipFromTheStartEdge();
    void aChipIsTheRaisedPlateWithItsEmojiDrawn();
    void chipsStopWhereTheColumnEnds();
};

void TestAirEntryNotes::chipsTakeALineOfTheirOwn()
{
    AirEntryNotes none(Air::EntryMarks{}, nullptr);
    none.setWidth(400);
    QCOMPARE(none.boundingRect().height(), 0.0);

    AirEntryNotes chips(reacted({{writingHand, 1}}), nullptr);
    chips.setWidth(400);
    QCOMPARE(chips.boundingRect().height(), qreal(Space::s4 + Size::controlSmall));

    AirEntryNotes both(reacted({{writingHand, 1}}, true), nullptr);
    both.setWidth(400);
    QVERIFY2(both.boundingRect().height() > chips.boundingRect().height(),
             "\"edited\" takes no line under the chips");
}

void TestAirEntryNotes::eachReactionIsAChipFromTheStartEdge()
{
    AirEntryNotes notes(reacted({{writingHand, 1}, {thumbsUp, 2}}), nullptr);
    notes.setWidth(400);
    const QVector<QRectF> plates = notes.plates();
    QCOMPARE(plates.size(), 2);
    QCOMPARE(plates.at(0).left(), 0.0);
    QCOMPARE(plates.at(0).top(), qreal(Space::s4));
    QCOMPARE(plates.at(0).height(), qreal(Size::controlSmall));
    QCOMPARE(plates.at(1).left(), plates.at(0).right() + Space::s4);
    // A chip holds its emoji between the paddings, and the count after a gap.
    QVERIFY2(plates.at(0).width() >= Space::s8 + Size::iconSmall + Space::s8, "the chip is narrower than its emoji");
    QVERIFY2(plates.at(1).width() > Space::s8 + Size::iconSmall + Space::s4 + Space::s8, "the count has no room");
}

void TestAirEntryNotes::aChipIsTheRaisedPlateWithItsEmojiDrawn()
{
    AirEntryNotes notes(reacted({{writingHand, 1}, {thumbsUp, 2}}), nullptr);
    const QImage image = painted(notes, 400);
    const QVector<QRectF> plates = notes.plates();
    QCOMPARE(plates.size(), 2);
    const AirStyle& air = AirStyle::instance();
    const QColor raised = air.color(Color::Raised);
    const QColor ground = air.color(Color::Bg);
    const int middle = qRound(plates.at(0).center().y());

    // The plate inside its hairline, before the emoji, is the raised colour.
    QCOMPARE(image.pixelColor(qRound(plates.at(0).left()) + 5, middle).rgb(), raised.rgb());
    // Between two chips the ground shows.
    const int between = qRound((plates.at(0).right() + plates.at(1).left()) / 2);
    QCOMPARE(image.pixelColor(between, middle).rgb(), ground.rgb());
    // Above the chips, the room under the text is the ground.
    QCOMPARE(image.pixelColor(qRound(plates.at(0).center().x()), 1).rgb(), ground.rgb());

    // The ✍️ the pack has no picture of is drawn in its place: the emoji font's colour glyph, or a
    // glyph in ink where the system has no colour font. Either way the plate is not left empty.
    const QRect emoji(qRound(plates.at(0).left()) + Space::s8, qRound(plates.at(0).top()) + 2,
                      Size::iconSmall, qRound(plates.at(0).height()) - 4);
    int drawn = 0;
    for (int y = emoji.top(); y <= emoji.bottom(); ++y) {
        for (int x = emoji.left(); x <= emoji.right(); ++x) {
            if (image.pixelColor(x, y).rgb() != raised.rgb()) {
                ++drawn;
            }
        }
    }
    QVERIFY2(drawn > 10, qPrintable(QStringLiteral("the emoji left its chip empty (%1 pixels)").arg(drawn)));
}

void TestAirEntryNotes::chipsStopWhereTheColumnEnds()
{
    std::vector<std::pair<QString, int>> many;
    const QStringList emoji = {QStringLiteral("\U0001F600"), QStringLiteral("\U0001F601"),
                               QStringLiteral("\U0001F602"), QStringLiteral("\U0001F603"),
                               QStringLiteral("\U0001F604"), QStringLiteral("\U0001F605"),
                               QStringLiteral("\U0001F606"), QStringLiteral("\U0001F607")};
    for (const QString& one : emoji) {
        many.emplace_back(one, 1);
    }
    AirEntryNotes notes(reacted(many), nullptr);
    notes.setWidth(120);
    const QVector<QRectF> plates = notes.plates();
    QVERIFY2(!plates.isEmpty() && plates.size() < emoji.size(), "the chips do not stop at the column's end");
    QVERIFY(plates.last().right() <= 120);
}

int main(int argc, char* argv[])
{
    // The chips need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    QApplication app(argc, argv);
    // The Plex faces, as the app has them: the count and the line heights are theirs.
    AirStyle::installFonts();
    TestAirEntryNotes test;
    return QTest::qExec(&test, argc, argv);
}

#include "airentrynotes_test.moc"
