/*
    Copyright © 2026 by The Khandaq Project Contributors

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

#include "src/persistence/paths.h"
#include "src/platform/interfacescale.h"

#include <QDir>
#include <QSettings>
#include <QTemporaryDir>
#include <QtTest/QtTest>

class TestInterfaceScale : public QObject
{
    Q_OBJECT
private slots:
    void offeredSizes();
    void storedSize();
    void settingsFileFollowsPortable();
    void decideWithoutEnvironment();
    void decideFromTheUsersFactor();
    void decideAfterRestart();
    void decideFromAFactorQtIgnores();
    void sizesTheScreenHasRoomFor();

private:
    static void writeIni(const QString& path, const QString& group, const QString& key,
                         const QVariant& value);
};

void TestInterfaceScale::writeIni(const QString& path, const QString& group, const QString& key,
                                  const QVariant& value)
{
    QSettings s(path, QSettings::IniFormat);
    s.setIniCodec("UTF-8");
    s.beginGroup(group);
    s.setValue(key, value);
    s.endGroup();
    s.sync();
}

void TestInterfaceScale::offeredSizes()
{
    const QVector<int> expected{100, 110, 125, 150, 175, 200};
    QCOMPARE(InterfaceScale::percents(), expected);
    for (const int percent : expected) {
        QCOMPARE(InterfaceScale::normalized(percent), percent);
    }
    // Anything else, written by hand into khandaq.ini or by a later version, is the usual size.
    QCOMPARE(InterfaceScale::normalized(130), 100);
    QCOMPARE(InterfaceScale::normalized(0), 100);
    QCOMPARE(InterfaceScale::normalized(-125), 100);
    QCOMPARE(InterfaceScale::normalized(1000), 100);
    QCOMPARE(InterfaceScale::inForce(), 100);
}

void TestInterfaceScale::storedSize()
{
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString ini = dir.filePath(QStringLiteral("khandaq.ini"));

    QCOMPARE(InterfaceScale::storedPercent(ini), 100); // no file
    QCOMPARE(InterfaceScale::storedPercent(QString()), 100);

    writeIni(ini, QStringLiteral("GUI"), QStringLiteral("themeMode"), 0);
    QCOMPARE(InterfaceScale::storedPercent(ini), 100); // no size in it

    writeIni(ini, QStringLiteral("GUI"), QStringLiteral("interfaceScale"), 150);
    QCOMPARE(InterfaceScale::storedPercent(ini), 150);

    writeIni(ini, QStringLiteral("GUI"), QStringLiteral("interfaceScale"), 130);
    QCOMPARE(InterfaceScale::storedPercent(ini), 100);

    writeIni(ini, QStringLiteral("GUI"), QStringLiteral("interfaceScale"), QStringLiteral("big"));
    QCOMPARE(InterfaceScale::storedPercent(ini), 100);

    // The key of another group is not the size.
    QFile::remove(ini);
    writeIni(ini, QStringLiteral("General"), QStringLiteral("interfaceScale"), 175);
    QCOMPARE(InterfaceScale::storedPercent(ini), 100);
}

void TestInterfaceScale::settingsFileFollowsPortable()
{
    QTemporaryDir program;
    QVERIFY(program.isValid());
    const QString system = Paths::systemSettingsDirPath() + QStringLiteral("khandaq.ini");
    QVERIFY(Paths::systemSettingsDirPath().endsWith(QDir::separator()));

    // No khandaq.ini next to the program: the one of the system, as Settings reads it.
    QCOMPARE(InterfaceScale::globalSettingsFile(program.path()), system);
    QVERIFY(!Paths::portableIn(program.path()));

    const QString beside = program.path() + QDir::separator() + QStringLiteral("khandaq.ini");
    writeIni(beside, QStringLiteral("Advanced"), QStringLiteral("makeToxPortable"), false);
    QVERIFY(!Paths::portableIn(program.path()));
    QCOMPARE(InterfaceScale::globalSettingsFile(program.path()), system);

    writeIni(beside, QStringLiteral("Advanced"), QStringLiteral("makeToxPortable"), true);
    QVERIFY(Paths::portableIn(program.path()));
    QCOMPARE(InterfaceScale::globalSettingsFile(program.path()), beside);

    writeIni(beside, QStringLiteral("GUI"), QStringLiteral("interfaceScale"), 175);
    const QString found = InterfaceScale::globalSettingsFile(program.path());
    QCOMPARE(InterfaceScale::storedPercent(found), 175);
}

void TestInterfaceScale::decideWithoutEnvironment()
{
    const InterfaceScale::Environment none;

    const InterfaceScale::Decision usual = InterfaceScale::decide(100, none);
    QVERIFY(!usual.scaleFactorSet);
    QVERIFY(!usual.baseSet);

    const InterfaceScale::Decision larger = InterfaceScale::decide(125, none);
    QVERIFY(larger.scaleFactorSet);
    QCOMPARE(larger.scaleFactor, QByteArrayLiteral("1.25"));
    QVERIFY(larger.baseSet);
    QCOMPARE(larger.base, QByteArrayLiteral("none"));

    QCOMPARE(InterfaceScale::decide(110, none).scaleFactor, QByteArrayLiteral("1.1"));
    QCOMPARE(InterfaceScale::decide(150, none).scaleFactor, QByteArrayLiteral("1.5"));
    QCOMPARE(InterfaceScale::decide(175, none).scaleFactor, QByteArrayLiteral("1.75"));
    QCOMPARE(InterfaceScale::decide(200, none).scaleFactor, QByteArrayLiteral("2"));

    // A size not offered is the usual one.
    const InterfaceScale::Decision unknown = InterfaceScale::decide(130, none);
    QVERIFY(!unknown.scaleFactorSet);
    QVERIFY(!unknown.baseSet);
}

void TestInterfaceScale::decideFromTheUsersFactor()
{
    // QT_SCALE_FACTOR set for every Qt program: the size scales from it, and it stays at 100.
    InterfaceScale::Environment found;
    found.scaleFactorSet = true;
    found.scaleFactor = QByteArrayLiteral("2");

    const InterfaceScale::Decision larger = InterfaceScale::decide(150, found);
    QCOMPARE(larger.scaleFactor, QByteArrayLiteral("3"));
    QVERIFY(larger.baseSet);
    QCOMPARE(larger.base, QByteArrayLiteral("2"));

    const InterfaceScale::Decision usual = InterfaceScale::decide(100, found);
    QVERIFY(usual.scaleFactorSet);
    QCOMPARE(usual.scaleFactor, QByteArrayLiteral("2"));
    QVERIFY(!usual.baseSet);
}

void TestInterfaceScale::decideAfterRestart()
{
    // Started again by a Khandaq at 150% that had no QT_SCALE_FACTOR of its own: its factor is
    // not the base.
    InterfaceScale::Environment fromKhandaq;
    fromKhandaq.scaleFactorSet = true;
    fromKhandaq.scaleFactor = QByteArrayLiteral("1.5");
    fromKhandaq.baseSet = true;
    fromKhandaq.base = QByteArrayLiteral("none");

    const InterfaceScale::Decision smaller = InterfaceScale::decide(125, fromKhandaq);
    QCOMPARE(smaller.scaleFactor, QByteArrayLiteral("1.25"));
    QCOMPARE(smaller.base, QByteArrayLiteral("none"));

    const InterfaceScale::Decision usual = InterfaceScale::decide(100, fromKhandaq);
    QVERIFY(!usual.scaleFactorSet);
    QVERIFY(!usual.baseSet);

    // The Khandaq before had the person's QT_SCALE_FACTOR=2 under its 150%.
    InterfaceScale::Environment overUsers;
    overUsers.scaleFactorSet = true;
    overUsers.scaleFactor = QByteArrayLiteral("3");
    overUsers.baseSet = true;
    overUsers.base = QByteArrayLiteral("2");

    QCOMPARE(InterfaceScale::decide(110, overUsers).scaleFactor, QByteArrayLiteral("2.2"));
    const InterfaceScale::Decision back = InterfaceScale::decide(100, overUsers);
    QVERIFY(back.scaleFactorSet);
    QCOMPARE(back.scaleFactor, QByteArrayLiteral("2"));
    QVERIFY(!back.baseSet);
}

void TestInterfaceScale::decideFromAFactorQtIgnores()
{
    // Qt ignores a QT_SCALE_FACTOR that is not a positive number: the size scales from 1, and the
    // value goes back as it was at 100.
    InterfaceScale::Environment found;
    found.scaleFactorSet = true;
    found.scaleFactor = QByteArrayLiteral("large");

    const InterfaceScale::Decision larger = InterfaceScale::decide(125, found);
    QCOMPARE(larger.scaleFactor, QByteArrayLiteral("1.25"));
    QCOMPARE(larger.base, QByteArrayLiteral("large"));

    const InterfaceScale::Decision usual = InterfaceScale::decide(100, found);
    QCOMPARE(usual.scaleFactor, QByteArrayLiteral("large"));

    found.scaleFactor = QByteArrayLiteral("-1");
    QCOMPARE(InterfaceScale::decide(200, found).scaleFactor, QByteArrayLiteral("2"));
}

void TestInterfaceScale::sizesTheScreenHasRoomFor()
{
    // A laptop at 1366 x 768 with the task bar below, and a window no smaller than 720 x 480.
    const QSize laptop(1366, 728);
    const QSize least(720, 480);
    QVERIFY(InterfaceScale::fits(laptop, least, 100, 100));
    QVERIFY(InterfaceScale::fits(laptop, least, 100, 125));
    QVERIFY(InterfaceScale::fits(laptop, least, 100, 150)); // 720 of 728 high
    QVERIFY(!InterfaceScale::fits(laptop, least, 100, 175));
    QVERIFY(!InterfaceScale::fits(laptop, least, 100, 200));

    // The same screen seen from 150%: fewer pixels, the same answers.
    const QSize fromLarger(910, 485);
    QVERIFY(InterfaceScale::fits(fromLarger, least, 150, 100));
    QVERIFY(InterfaceScale::fits(fromLarger, least, 150, 150));
    QVERIFY(!InterfaceScale::fits(fromLarger, least, 150, 175));

    // Too narrow is as bad as too low.
    QVERIFY(!InterfaceScale::fits(QSize(1000, 2000), least, 100, 150));

    // A size not larger than the one in force, or a screen not known, leaves the choice open.
    QVERIFY(InterfaceScale::fits(QSize(100, 100), least, 200, 110));
    QVERIFY(InterfaceScale::fits(QSize(), least, 100, 200));
    QVERIFY(InterfaceScale::fits(QSize(0, 900), least, 100, 200));
}

QTEST_GUILESS_MAIN(TestInterfaceScale)
#include "interfacescale_test.moc"
