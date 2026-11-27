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

#include "src/model/air/airlocation.h"
#include "src/model/air/airmessagemarks.h"
#include "src/model/air/airmodel.h"
#include "src/widget/air/airstrings.h"

#include <QLocale>
#include <QRect>
#include <QRegion>
#include <QTranslator>
#include <QtTest/QtTest>

using namespace Air;

namespace {
/** As both phones send a place (LocationMessage.payload, HelperLocationMessage.encode). */
const QString redSquare = QStringLiteral("khandaq-location:55.75580,37.61730");

Location at(double latitude, double longitude)
{
    Location place;
    place.latitude = latitude;
    place.longitude = longitude;
    return place;
}

bool near(double a, double b)
{
    return std::fabs(a - b) < 1e-3;
}

QString tileText(const LocationMap::Tile& tile)
{
    return QStringLiteral("%1/%2 at %3,%4")
        .arg(tile.x)
        .arg(tile.y)
        .arg(tile.offset.x())
        .arg(tile.offset.y());
}

QStringList tileTexts(const LocationMap::Layout& layout)
{
    QStringList texts;
    for (const LocationMap::Tile& tile : layout.tiles) {
        texts.append(tileText(tile));
    }
    return texts;
}
} // namespace

/**
 * KHANDAQ (28.11.2026): a place a phone shares came to the desktop as the words
 * "khandaq-location:55.75580,37.61730". It reads now as the phones read it, its map is cut from
 * the tiles as Android cuts it, and wherever its words are read it says "Location" and its
 * coordinates.
 */
class TestAirLocation : public QObject
{
    Q_OBJECT
private slots:
    void readsThePayloadOfThePhones();
    void readsTheOldWordsOfThePhones();
    void wordsThatAreNoPlaceStayWords_data();
    void wordsThatAreNoPlaceStayWords();
    void readsAndWritesTheSameInEveryLanguage();
    void aMessageIsAPlaceByItsOwnWords();
    void thePageOfAPlaceIsOpenStreetMap();
    void theMapIsCutAroundThePlace_data();
    void theMapIsCutAroundThePlace();
    void theWindowIsCoveredAndThePlaceInItsMiddleEverywhere();
    void theTilesGoRoundTheAntimeridian();
    void aPlaceNearAPoleTakesNoTilePastTheMap();
    void aPlaceReadsByItsNameWhereItsWordsShow();
    void aReplyQuotesAPlaceAsItCame();
    void aPlaceIsTranslated();
};

void TestAirLocation::readsThePayloadOfThePhones()
{
    Location place;
    QVERIFY(readLocation(redSquare, place));
    QCOMPARE(place.latitude, 55.7558);
    QCOMPARE(place.longitude, 37.6173);

    // Below the equator and west of Greenwich, with the spaces and the line a message may end with.
    QVERIFY(readLocation(QStringLiteral("  khandaq-location:-33.85680,-70.64830\n"), place));
    QCOMPARE(place.latitude, -33.8568);
    QCOMPARE(place.longitude, -70.6483);

    // Whole degrees and the edges of the map are places too.
    QVERIFY(readLocation(QStringLiteral("khandaq-location:0,-180"), place));
    QCOMPARE(place.latitude, 0.0);
    QCOMPARE(place.longitude, -180.0);
    QVERIFY(readLocation(QStringLiteral("khandaq-location:-90.00000,180.00000"), place));
    QCOMPARE(place.latitude, -90.0);
    QCOMPARE(place.longitude, 180.0);
}

void TestAirLocation::readsTheOldWordsOfThePhones()
{
    Location place;
    QVERIFY(readLocation(QStringLiteral("my Location: 55.7558, 37.6173"), place));
    QCOMPARE(place.latitude, 55.7558);
    QCOMPARE(place.longitude, 37.6173);
    // In any case and among other words, as both phones find it.
    QVERIFY(readLocation(QStringLiteral("Here is MY  location:21.4225 ,39.8262 (Mecca)"), place));
    QCOMPARE(place.latitude, 21.4225);
    QCOMPARE(place.longitude, 39.8262);
}

void TestAirLocation::wordsThatAreNoPlaceStayWords_data()
{
    QTest::addColumn<QString>("words");
    QTest::newRow("nothing") << QString();
    QTest::newRow("words") << QStringLiteral("See you at five");
    QTest::newRow("no numbers") << QStringLiteral("khandaq-location:");
    QTest::newRow("one number") << QStringLiteral("khandaq-location:55.75580");
    QTest::newRow("no comma") << QStringLiteral("khandaq-location:55.75580;37.61730");
    QTest::newRow("decimal commas") << QStringLiteral("khandaq-location:55,75580,37,61730");
    // Android finds no place where a space is; the stricter phone decides.
    QTest::newRow("a space") << QStringLiteral("khandaq-location:55.75580, 37.61730");
    QTest::newRow("capitals") << QStringLiteral("KHANDAQ-LOCATION:55.75580,37.61730");
    // iOS reads a place only from the start of the words to their end: words around it stay.
    QTest::newRow("words before") << QStringLiteral("look khandaq-location:55.75580,37.61730");
    QTest::newRow("words after") << QStringLiteral("khandaq-location:55.75580,37.61730 here");
    QTest::newRow("latitude past 90") << QStringLiteral("khandaq-location:90.00001,0.00000");
    QTest::newRow("longitude past 180") << QStringLiteral("khandaq-location:0.00000,-180.00001");
    QTest::newRow("exponent") << QStringLiteral("khandaq-location:1e1,2");
    QTest::newRow("not a number") << QStringLiteral("khandaq-location:nan,inf");
    QTest::newRow("two minuses") << QStringLiteral("khandaq-location:--1,2");
    QTest::newRow("a plus") << QStringLiteral("khandaq-location:+1,2");
    QTest::newRow("two points") << QStringLiteral("khandaq-location:1.2.3,4");
    QTest::newRow("other digits") << QStringLiteral("khandaq-location:٥٥,٣٧");
    QTest::newRow("old, two points") << QStringLiteral("my Location: 1.2.3, 4");
    QTest::newRow("old, past 90") << QStringLiteral("my Location: 95, 4");
}

void TestAirLocation::wordsThatAreNoPlaceStayWords()
{
    QFETCH(QString, words);
    Location place = at(1, 2);
    QVERIFY(!readLocation(words, place));
    // What it held stays as it was.
    QCOMPARE(place.latitude, 1.0);
    QCOMPARE(place.longitude, 2.0);
    QCOMPARE(AirStrings::shownWords(words), words);
}

void TestAirLocation::readsAndWritesTheSameInEveryLanguage()
{
    const QLocale before;
    for (const QLocale& locale :
         {QLocale(QLocale::German, QLocale::Germany), QLocale(QLocale::Arabic, QLocale::Egypt),
          QLocale(QLocale::Russian, QLocale::Russia), QLocale(QLocale::Persian, QLocale::Iran)}) {
        QLocale::setDefault(locale);
        Location place;
        QVERIFY2(readLocation(redSquare, place), qPrintable(locale.name()));
        QCOMPARE(place.latitude, 55.7558);
        QCOMPARE(place.longitude, 37.6173);
        QCOMPARE(locationCoordinates(place), QStringLiteral("55.75580, 37.61730"));
        QCOMPARE(locationKey(place), QStringLiteral("55.75580,37.61730"));
        QCOMPARE(locationPage(place).toString(),
                 QStringLiteral("https://www.openstreetmap.org/?mlat=55.75580&mlon=37.61730"
                                "#map=16/55.75580/37.61730"));
    }
    QLocale::setDefault(before);

    // Five decimals, rounded as the phones round them.
    QCOMPARE(locationCoordinates(at(55.755806, -0.1278)), QStringLiteral("55.75581, -0.12780"));
    QCOMPARE(locationKey(at(-33.8568, 151.2153)), QStringLiteral("-33.85680,151.21530"));
}

void TestAirLocation::aMessageIsAPlaceByItsOwnWords()
{
    Location place;
    QVERIFY(messageLocation(redSquare, place));
    QCOMPARE(place.latitude, 55.7558);

    // A reply that is a place: its own words are the place, the quote is what it answers.
    QVERIFY(messageLocation(
        QStringLiteral("[KQ|42|56A1ADE4|1762800000000|Amina]\nwhere are you?\n[KQ/end]\n")
            + redSquare,
        place));
    // In a group, after the block of mentions.
    QVERIFY(messageLocation(QStringLiteral("[KQ|m|56A1ADE4:amina][KQ/m]") + redSquare, place));

    // A reply that quotes a place is words.
    QVERIFY(!messageLocation(QStringLiteral("[KQ|42|56A1ADE4|1762800000000|Amina]\n") + redSquare
                                 + QStringLiteral("\n[KQ/end]\nI am coming"),
                             place));
    // A form this version cannot read is no place.
    QVERIFY(!messageLocation(QStringLiteral("[KQ|poll|1|2|3]"), place));
    QVERIFY(!messageLocation(QStringLiteral("See you at five"), place));
}

void TestAirLocation::thePageOfAPlaceIsOpenStreetMap()
{
    const QUrl page = locationPage(at(-33.8568, 151.2153));
    QCOMPARE(page.toString(), QStringLiteral("https://www.openstreetmap.org/"
                                             "?mlat=-33.85680&mlon=151.21530"
                                             "#map=16/-33.85680/151.21530"));
    QCOMPARE(page.scheme(), QStringLiteral("https"));
    QCOMPARE(page.host(), QStringLiteral("www.openstreetmap.org"));

    LocationMap::Tile tile;
    tile.x = 19808;
    tile.y = 10243;
    QCOMPARE(LocationMap::tileUrl(tile).toString(),
             QStringLiteral("https://tile.openstreetmap.org/15/19808/10243.png"));
}

void TestAirLocation::theMapIsCutAroundThePlace_data()
{
    QTest::addColumn<double>("latitude");
    QTest::addColumn<double>("longitude");
    QTest::addColumn<QStringList>("tiles");
    QTest::addColumn<QPointF>("marker");

    // Worked out on their own from the formulas of LocationMapSnapshot.java: the tile of the
    // place, the canvas of the nine around it, the window from the whole pixel of the place.
    QTest::newRow("Red Square, two tiles")
        << 55.7558 << 37.6173
        << QStringList{QStringLiteral("19807/10243 at -128,-32"),
                       QStringLiteral("19808/10243 at 128,-32")}
        << QPointF(130.6214, 60.4178);
    QTest::newRow("Sydney, the window right of the middle tile")
        << -33.8568 << 151.2153
        << QStringList{QStringLiteral("30147/19662 at -113,-69"),
                       QStringLiteral("30148/19662 at 143,-69")}
        << QPointF(130.8758, 60.9213);
    QTest::newRow("the corner of four tiles")
        << 0.0 << 0.0
        << QStringList{QStringLiteral("16383/16383 at -126,-196"),
                       QStringLiteral("16384/16383 at 130,-196"),
                       QStringLiteral("16383/16384 at -126,60"),
                       QStringLiteral("16384/16384 at 130,60")}
        << QPointF(130, 60);
    QTest::newRow("Mecca, the window below the middle tile")
        << 21.4225 << 39.8262
        << QStringList{QStringLiteral("20008/14386 at -143,-193"),
                       QStringLiteral("20009/14386 at 113,-193"),
                       QStringLiteral("20008/14387 at -143,63"),
                       QStringLiteral("20009/14387 at 113,63")}
        << QPointF(130.7220, 60.2442);
}

void TestAirLocation::theMapIsCutAroundThePlace()
{
    QFETCH(double, latitude);
    QFETCH(double, longitude);
    QFETCH(QStringList, tiles);
    QFETCH(QPointF, marker);

    const LocationMap::Layout layout = LocationMap::layoutOf(at(latitude, longitude));
    QCOMPARE(tileTexts(layout), tiles);
    QVERIFY2(near(layout.marker.x(), marker.x()) && near(layout.marker.y(), marker.y()),
             qPrintable(QStringLiteral("the dot is at %1,%2")
                            .arg(layout.marker.x())
                            .arg(layout.marker.y())));
}

void TestAirLocation::theWindowIsCoveredAndThePlaceInItsMiddleEverywhere()
{
    const QRect window(0, 0, LocationMap::width, LocationMap::height);
    for (double latitude = -84.9; latitude <= 84.9; latitude += 7.3) {
        for (double longitude = -180; longitude <= 180; longitude += 13.7) {
            const LocationMap::Layout layout = LocationMap::layoutOf(at(latitude, longitude));
            const QString where = QStringLiteral("%1,%2").arg(latitude).arg(longitude);
            // The dot within a pixel of the middle, as Android cuts the window.
            QVERIFY2(layout.marker.x() >= LocationMap::width / 2
                         && layout.marker.x() < LocationMap::width / 2 + 1
                         && layout.marker.y() >= LocationMap::height / 2
                         && layout.marker.y() < LocationMap::height / 2 + 1,
                     qPrintable(where));
            QVERIFY2(layout.tiles.size() >= 2 && layout.tiles.size() <= 6, qPrintable(where));
            QRegion covered;
            for (const LocationMap::Tile& tile : layout.tiles) {
                const QRect square(tile.offset,
                                   QSize(LocationMap::tileSize, LocationMap::tileSize));
                // Each tile fetched shows in the window.
                QVERIFY2(square.intersects(window), qPrintable(where));
                covered += square;
            }
            QVERIFY2((QRegion(window) - covered).isEmpty(), qPrintable(where));
        }
    }
}

void TestAirLocation::theTilesGoRoundTheAntimeridian()
{
    // Just west of the antimeridian: the tile east of the last one is the first one.
    const LocationMap::Layout east = LocationMap::layoutOf(at(10.0, 179.999));
    QCOMPARE(tileTexts(east), (QStringList{QStringLiteral("32767/15468 at -102,-227"),
                                           QStringLiteral("0/15468 at 154,-227"),
                                           QStringLiteral("32767/15469 at -102,29"),
                                           QStringLiteral("0/15469 at 154,29")}));
    QCOMPARE(LocationMap::tileUrl(east.tiles.at(1)).toString(),
             QStringLiteral("https://tile.openstreetmap.org/15/0/15468.png"));

    // And the other way: east of -180 the tile west of the first one is the last one.
    const LocationMap::Layout west = LocationMap::layoutOf(at(10.0, -180.0));
    bool wrapped = false;
    for (const LocationMap::Tile& tile : west.tiles) {
        QVERIFY(tile.x >= 0 && tile.x < (1 << LocationMap::zoom));
        wrapped = wrapped || tile.x == (1 << LocationMap::zoom) - 1;
    }
    QVERIFY(wrapped);
}

void TestAirLocation::aPlaceNearAPoleTakesNoTilePastTheMap()
{
    const int last = (1 << LocationMap::zoom) - 1;
    for (const double latitude : {89.0, 90.0, -89.0, -90.0}) {
        const LocationMap::Layout layout = LocationMap::layoutOf(at(latitude, 0.0));
        QVERIFY(!layout.tiles.isEmpty());
        for (const LocationMap::Tile& tile : layout.tiles) {
            QCOMPARE(tile.y, latitude > 0 ? 0 : last);
        }
        QVERIFY(layout.marker.x() >= 0 && layout.marker.x() <= LocationMap::width);
        QVERIFY(layout.marker.y() >= 0 && layout.marker.y() <= LocationMap::height);
    }
}

void TestAirLocation::aPlaceReadsByItsNameWhereItsWordsShow()
{
    const QString mentioned = QStringLiteral("[KQ|m|56A1ADE4:amina][KQ/m]") + redSquare;
    const QString replying =
        QStringLiteral("[KQ|42|56A1ADE4|1762800000000|Amina]\nwhere?\n[KQ/end]\n") + redSquare;
    // The list of chats, the notifications and the bands name it, as the phones do.
    const QString title = QStringLiteral("Location");
    QCOMPARE(AirStrings::messageWords(redSquare), title);
    QCOMPARE(AirStrings::messageWords(mentioned), title);
    QCOMPARE(AirStrings::messageWords(replying), title);
    QCOMPARE(AirStrings::messageWords(QStringLiteral("my Location: 55.7558, 37.6173")), title);

    // Where the words of the transcript are read (the export, the queue of messages that wait, a
    // member's lines in a group) it has its coordinates, as its card.
    const QString words = QStringLiteral("Location: 55.75580, 37.61730");
    QCOMPARE(AirStrings::transcriptWords(redSquare), words);
    QCOMPARE(AirStrings::transcriptWords(mentioned), words);
    QCOMPARE(AirStrings::transcriptWords(replying), words);
    QCOMPARE(AirStrings::locationWords(at(55.7558, 37.6173)), words);
    // A quote and a search hit.
    QCOMPARE(AirStrings::shownWords(redSquare), words);
    QCOMPARE(AirStrings::shownWords(plainMessageBody(redSquare)), words);
    QCOMPARE(AirStrings::shownWords(QStringLiteral("my Location: 55.7558, 37.6173")), words);
    // The entry shows the coordinates under the name; the search of a conversation finds them.
    QCOMPARE(entryWords(redSquare), QStringLiteral("55.75580, 37.61730"));
    // A reaction to a place says so, as on Android; its wire words are never quoted.
    QCOMPARE(AirStrings::reactionWords(QStringLiteral("👍"), MessageEffect::Anchor::Text,
                                       redSquare),
             QStringLiteral("Reacted 👍 to your location"));
    QCOMPARE(AirStrings::reactionWords(QStringLiteral("👍"), MessageEffect::Anchor::Text,
                                       replying),
             QStringLiteral("Reacted 👍 to your location"));

    // Words that hold a place among others stay words, everywhere.
    const QString talk = QStringLiteral("look khandaq-location:55.75580,37.61730");
    QCOMPARE(AirStrings::messageWords(talk), talk);
    QCOMPARE(AirStrings::transcriptWords(talk), talk);
    QCOMPARE(entryWords(talk), talk);
    QCOMPARE(AirStrings::reactionWords(QStringLiteral("👍"), MessageEffect::Anchor::Text, talk),
             QStringLiteral("Reacted 👍 to “look khandaq-location:55.75580,37.61730”"));
    QCOMPARE(AirStrings::shownWords(QStringLiteral("See you")), QStringLiteral("See you"));
    // A form this version cannot read says so wherever it is read.
    const QString poll = QStringLiteral("[KQ|poll|1|2|3]");
    QCOMPARE(AirStrings::transcriptWords(poll), AirStrings::messageWords(poll));
}

void TestAirLocation::aReplyQuotesAPlaceAsItCame()
{
    // The quote goes out as the phones send theirs, and every client reads it.
    QCOMPARE(AirStrings::quotedWords(redSquare), redSquare);
    QCOMPARE(replyQuote(AirStrings::quotedWords(redSquare)), redSquare);
    // Anything else is quoted as before.
    QCOMPARE(AirStrings::quotedWords(QStringLiteral("[KQ|m|ABCDEF12:amina][KQ/m]Салам")),
             QStringLiteral("Салам"));
    QCOMPARE(AirStrings::quotedWords(QStringLiteral("[KQ|poll|1|2|3]")),
             AirStrings::messageWords(QStringLiteral("[KQ|poll|1|2|3]")));

    ReplyMeta meta;
    meta.localId = 42;
    meta.authorKey = QStringLiteral("56A1ADE4");
    meta.timeMs = 1762800000000;
    meta.authorName = QStringLiteral("Amina");
    meta.words = AirStrings::quotedWords(redSquare);
    const MessageText reply = readMessageText(replyHead(meta) + QStringLiteral("I am coming"));
    QVERIFY(reply.reply);
    QCOMPARE(reply.body, QStringLiteral("I am coming"));
    QCOMPARE(reply.replyPreview, redSquare);
    // The quote of it reads by the name of the place.
    QCOMPARE(AirStrings::shownWords(reply.replyPreview),
             QStringLiteral("Location: 55.75580, 37.61730"));
}

void TestAirLocation::aPlaceIsTranslated()
{
    Q_INIT_RESOURCE(translations);
    // The names the phones give a place (chat_location_title), in their languages.
    struct Expected
    {
        QString language;
        QString title;
        QString words;
        QString reacted;
    };
    for (const Expected& expected :
         {Expected{QStringLiteral("ru"), QStringLiteral("Геопозиция"),
                   QStringLiteral("Геопозиция: 55.75580, 37.61730"),
                   QStringLiteral("Реакция 👍 на вашу геопозицию")},
          Expected{QStringLiteral("ar"), QStringLiteral("الموقع"),
                   QStringLiteral("الموقع: 55.75580, 37.61730"),
                   QStringLiteral("تفاعل بـ 👍 على موقعك")},
          Expected{QStringLiteral("zh_CN"), QStringLiteral("位置"),
                   QStringLiteral("位置：55.75580, 37.61730"),
                   QStringLiteral("对你的位置回应了 👍")}}) {
        QTranslator translator;
        QVERIFY2(translator.load(expected.language, QStringLiteral(":translations/")),
                 qPrintable(expected.language));
        QCoreApplication::installTranslator(&translator);
        QCOMPARE(AirStrings::messageWords(redSquare), expected.title);
        QCOMPARE(AirStrings::transcriptWords(redSquare), expected.words);
        QCOMPARE(AirStrings::shownWords(redSquare), expected.words);
        QCOMPARE(AirStrings::reactionWords(QStringLiteral("👍"), MessageEffect::Anchor::Text,
                                           redSquare),
                 expected.reacted);
        QCoreApplication::removeTranslator(&translator);
    }
}

QTEST_GUILESS_MAIN(TestAirLocation)
#include "airlocation_test.moc"
