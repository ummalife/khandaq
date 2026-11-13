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

#include "src/model/air/airyou.h"

#include <QtTest/QtTest>

namespace {
const QString address =
    QStringLiteral("DC43B11E32BDAFE763689CBFDC35F5A49DE19F8F68441D9FFFB8AEAE3A827951297A33435908");
} // namespace

class TestAirYou : public QObject
{
    Q_OBJECT
private slots:
    void fitKeepsWhatFits();
    void fitCutsBetweenCharacters();
    void fitKeepsGraphemesWhole();
    void editThatFitsStaysAsTyped();
    void typingIntoAFullFieldChangesNothing();
    void pasteGoesInAsFarAsItFits();
    void editKeepsCharactersWhole();
    void legacyStatusesAreKnown();
    void ownStatusesStay();
    void idPartsSplitAWholeId();
    void newAntispamDiffers();
    void qrCodeOfAnId();
    void qrImageHasItsQuietZone();
};

void TestAirYou::fitKeepsWhatFits()
{
    QCOMPARE(Air::fitUtf8(QStringLiteral("Amina"), 5), QStringLiteral("Amina"));
    QCOMPARE(Air::fitUtf8(QString(), Air::nameLimitBytes), QString());
    QCOMPARE(Air::fitUtf8(QStringLiteral("Amina"), 0), QString());
    const QString longest(Air::nameLimitBytes, QLatin1Char('a'));
    QCOMPARE(Air::fitUtf8(longest, Air::nameLimitBytes), longest);
    QCOMPARE(Air::fitUtf8(longest + QLatin1Char('a'), Air::nameLimitBytes), longest);
}

void TestAirYou::fitCutsBetweenCharacters()
{
    // Two bytes a Cyrillic letter: five bytes hold two letters, not two and a half.
    const QString name = QString::fromUtf8("\xd0\x90\xd0\xbc\xd0\xb8\xd0\xbd\xd0\xb0");
    QCOMPARE(Air::fitUtf8(name, 5), name.left(2));
    QCOMPARE(Air::fitUtf8(name, 6), name.left(3));
    QVERIFY(Air::fitUtf8(name, 5).toUtf8().size() <= 5);
}

void TestAirYou::fitKeepsGraphemesWhole()
{
    // A thumbs-up with a skin tone is one character a person sees, eight bytes in UTF-8: it stays
    // whole or goes.
    const QString thumb = QString::fromUtf8("\xf0\x9f\x91\x8d\xf0\x9f\x8f\xbd");
    QCOMPARE(Air::fitUtf8(QStringLiteral("ok") + thumb, 9), QStringLiteral("ok"));
    QCOMPARE(Air::fitUtf8(QStringLiteral("ok") + thumb, 10), QStringLiteral("ok") + thumb);
    // An e with a combining acute keeps its accent.
    const QString accented = QStringLiteral("e") + QChar(0x0301);
    QCOMPARE(Air::fitUtf8(QStringLiteral("ab") + accented, 3), QStringLiteral("ab"));
    QCOMPARE(Air::fitUtf8(QStringLiteral("ab") + accented, 5), QStringLiteral("ab") + accented);
}

void TestAirYou::editThatFitsStaysAsTyped()
{
    const Air::FittedEdit fitted = Air::fitEdit(QStringLiteral("Amin"), QStringLiteral("Amina"), 5);
    QCOMPARE(fitted.text, QStringLiteral("Amina"));
    QCOMPARE(fitted.caret, 5);
}

void TestAirYou::typingIntoAFullFieldChangesNothing()
{
    const QString full(Air::nameLimitBytes, QLatin1Char('a'));
    const Air::FittedEdit atEnd = Air::fitEdit(full, full + QLatin1Char('b'), Air::nameLimitBytes);
    QCOMPARE(atEnd.text, full);
    QCOMPARE(atEnd.caret, full.size());
    // A letter typed in the middle goes nowhere either: the end of the text stays.
    const QString middle = full.left(10) + QLatin1Char('b') + full.mid(10);
    const Air::FittedEdit inside = Air::fitEdit(full, middle, Air::nameLimitBytes);
    QCOMPARE(inside.text, full);
    QCOMPARE(inside.caret, 10);
}

void TestAirYou::pasteGoesInAsFarAsItFits()
{
    // Three Cyrillic letters of two bytes each pasted between a and b, with room for two.
    const QString three = QString::fromUtf8("\xd0\x94\xd0\x94\xd0\x94");
    const Air::FittedEdit pasted =
        Air::fitEdit(QStringLiteral("ab"), QStringLiteral("a") + three + QStringLiteral("b"), 6);
    QCOMPARE(pasted.text, QStringLiteral("a") + three.left(2) + QStringLiteral("b"));
    QCOMPARE(pasted.caret, 3);
    // A selection replaced by a longer text: as much of it as fits, then the rest of the line.
    const Air::FittedEdit replaced =
        Air::fitEdit(QStringLiteral("hello"), QStringLiteral("heXYZWo"), 5);
    QCOMPARE(replaced.text, QStringLiteral("heXYo"));
    QCOMPARE(replaced.caret, 4);
}

void TestAirYou::editKeepsCharactersWhole()
{
    // A smiley replaced by two others shares its first half with them: the halves stay together.
    const QString grinning = QString::fromUtf8("\xf0\x9f\x98\x80");
    const QString beaming = QString::fromUtf8("\xf0\x9f\x98\x81");
    const Air::FittedEdit fitted =
        Air::fitEdit(QStringLiteral("a") + grinning, QStringLiteral("a") + beaming + beaming, 5);
    QCOMPARE(fitted.text, QStringLiteral("a") + beaming);
    QCOMPARE(fitted.caret, 3);
    // A thumbs-up with its skin tone goes in whole or not at all.
    const QString thumb = QString::fromUtf8("\xf0\x9f\x91\x8d\xf0\x9f\x8f\xbd");
    QCOMPARE(Air::fitEdit(QStringLiteral("ok"), QStringLiteral("ok") + thumb, 9).text,
             QStringLiteral("ok"));
}

void TestAirYou::legacyStatusesAreKnown()
{
    QVERIFY(Air::isLegacyDefaultStatus(QStringLiteral("Toxing on Khandaq")));
    QVERIFY(Air::isLegacyDefaultStatus(QStringLiteral("Toxing on qTox")));
    QVERIFY(Air::isLegacyDefaultStatus(QStringLiteral("Khandaq is Tox")));
    // The shipped translations, written here in UTF-8 the way the .ts files have them.
    QVERIFY(Air::isLegacyDefaultStatus(
        QString::fromUtf8("\xd0\x9e\xd0\xb1\xd1\x89\xd0\xb0\xd0\xb9\xd1\x82\xd0\xb5\xd1\x81\xd1\x8c"
                          " \xd0\xb2 Khandaq")));
    QVERIFY(Air::isLegacyDefaultStatus(QString::fromUtf8(
        "\xd8\xa7\xd8\xb3\xd8\xaa\xd8\xae\xd8\xaf\xd9\x85 "
        "\xd8\xa7\xd9\x84\xd8\xaa\xd9\x88\xd9\x83\xd8\xb3 "
        "\xd8\xb9\xd9\x84\xd9\x89 \xd9\x83\xd9\x8a\xd9\x88\xd8\xaa\xd9\x88\xd9\x83\xd8\xb3")));
    QVERIFY(Air::isLegacyDefaultStatus(
        QString::fromUtf8("\xe4\xbd\xbf\xe7\x94\xa8 Khandaq "
                          "\xe4\xb8\x8e\xe4\xbb\x96\xe4\xba\xba\xe4\xba\xa4\xe6\xb5\x81")));
    QVERIFY(Air::isLegacyDefaultStatus(QStringLiteral("Toxin' aboard Khandaq")));
}

void TestAirYou::ownStatusesStay()
{
    QVERIFY(!Air::isLegacyDefaultStatus(QString()));
    QVERIFY(!Air::isLegacyDefaultStatus(QStringLiteral("Back after lunch")));
    // Only the default as it was written: a word more makes it the person's own.
    QVERIFY(!Air::isLegacyDefaultStatus(QStringLiteral("Toxing on Khandaq again")));
    QVERIFY(!Air::isLegacyDefaultStatus(QStringLiteral("toxing on khandaq")));
}

void TestAirYou::idPartsSplitAWholeId()
{
    const Air::IdParts parts = Air::idParts(address);
    QCOMPARE(parts.key, address.left(64));
    QCOMPARE(parts.antispam, QStringLiteral("297A3343"));
    QCOMPARE(parts.check, QStringLiteral("5908"));
    QVERIFY(!parts.isEmpty());
    QVERIFY(Air::idParts(address.left(64)).isEmpty());
    QVERIFY(Air::idParts(address + QLatin1Char('0')).isEmpty());
}

void TestAirYou::newAntispamDiffers()
{
    QVector<quint32> draws{7, 7, 9};
    int next = 0;
    const auto sequence = [&draws, &next] { return draws.at(qMin(next++, draws.size() - 1)); };
    QCOMPARE(Air::newAntispam(7, sequence), 9u);
    QCOMPARE(Air::newAntispam(3, [] { return 3u; }), 2u);
    QCOMPARE(Air::newAntispam(0xffffffffu, [] { return 0x12345678u; }), 0x12345678u);
}

void TestAirYou::qrCodeOfAnId()
{
    // "tox:" and 76 characters fit version 5 at level M: 37 modules a side.
    const Air::QrModules modules = Air::qrModules(QStringLiteral("tox:") + address);
    QCOMPARE(modules.side, 37);
    QCOMPARE(modules.dark.size(), 37 * 37);
    // The finder pattern in the top left corner: a dark ring, a light ring, a dark centre.
    QVERIFY(modules.isDark(0, 0));
    QVERIFY(modules.isDark(6, 6));
    QVERIFY(!modules.isDark(1, 1));
    QVERIFY(!modules.isDark(5, 3));
    QVERIFY(modules.isDark(3, 3));
    QVERIFY(!modules.isDark(7, 7));
    // And in the other two corners with one.
    QVERIFY(modules.isDark(36, 0));
    QVERIFY(modules.isDark(0, 36));
    QVERIFY(!modules.isDark(-1, 0));
    QVERIFY(!modules.isDark(37, 0));
    QCOMPARE(Air::qrModules(QString()).side, 0);
}

void TestAirYou::qrImageHasItsQuietZone()
{
    const Air::QrModules modules = Air::qrModules(QStringLiteral("tox:") + address);
    const QImage image = Air::qrImage(modules, 3);
    const int side = (37 + 2 * Air::qrQuietModules) * 3;
    QCOMPARE(image.size(), QSize(side, side));
    const int edge = Air::qrQuietModules * 3;
    QCOMPARE(image.pixel(0, 0), qRgb(255, 255, 255));
    QCOMPARE(image.pixel(edge - 1, edge - 1), qRgb(255, 255, 255));
    QCOMPARE(image.pixel(edge, edge), qRgb(0, 0, 0));
    QCOMPARE(image.pixel(edge + 2, edge + 2), qRgb(0, 0, 0));
    // The light ring of the finder pattern, one module in.
    QCOMPARE(image.pixel(edge + 3, edge + 3), qRgb(255, 255, 255));
    QCOMPARE(image.pixel(side - 1, side - 1), qRgb(255, 255, 255));
    QVERIFY(Air::qrImage(Air::QrModules(), 3).isNull());
}

QTEST_GUILESS_MAIN(TestAirYou)
#include "airyou_test.moc"
