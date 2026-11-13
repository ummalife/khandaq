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

#include "src/model/air/airstart.h"

#include <QtTest/QtTest>

using Air::NewPasswordProblem;

/**
 * The rules of the start window (E19, E20): the rule of a new password as khandaq-ios
 * PasswordPolicy has it, and the file name a new profile gets from the name typed on the first
 * step, as khandaq-ios ProfileFolderName makes it, with what Windows refuses besides.
 */
class TestAirStart : public QObject
{
    Q_OBJECT

private slots:
    void lengthCountsWhatAPersonSees();
    void newPasswordTakesEightCharactersALetterAndADigit();
    void newPasswordIsTypedTheSameTwice();
    void fileNameKeepsTheNameWithoutWhatFileSystemsRefuse();
    void fileNameIsUniqueWithoutCase();
    void fileNameAvoidsTheNamesWindowsKeeps();
    void fileNameFallsBackWhenNothingIsLeft();
    void fileNameKeepsToItsLimits();
    void failureTellsItsKindAndCode();
};

void TestAirStart::lengthCountsWhatAPersonSees()
{
    QCOMPARE(Air::visibleLength(QString()), 0);
    QCOMPARE(Air::visibleLength(QStringLiteral("Амина")), 5);
    // An accent typed as its own code point, a skin tone and a family of three are one each.
    QCOMPARE(Air::visibleLength(QStringLiteral("é")), 1);
    QCOMPARE(Air::visibleLength(QString::fromUtf8("\xF0\x9F\x91\x8D\xF0\x9F\x8F\xBD")), 1);
    QCOMPARE(Air::visibleLength(QString::fromUtf8("\xF0\x9F\x91\xA8\xE2\x80\x8D\xF0\x9F\x91\xA9"
                                                  "\xE2\x80\x8D\xF0\x9F\x91\xA7")),
             1);
}

void TestAirStart::newPasswordTakesEightCharactersALetterAndADigit()
{
    QCOMPARE(Air::newPasswordProblem(QString(), QString()), NewPasswordProblem::Empty);
    QCOMPARE(Air::newPasswordProblem(QStringLiteral("abc1"), QString()), NewPasswordProblem::Short);
    QCOMPARE(Air::newPasswordProblem(QStringLiteral("abcdefgh"), QString()), NewPasswordProblem::Weak);
    QCOMPARE(Air::newPasswordProblem(QStringLiteral("12345678"), QString()), NewPasswordProblem::Weak);
    QCOMPARE(Air::newPasswordProblem(QStringLiteral("пароль12"), QString()),
             NewPasswordProblem::RepeatFirst);
    // Letters and digits of any script count: Arabic letters with Arabic-Indic digits.
    QCOMPARE(Air::newPasswordProblem(QStringLiteral("كلمةسر٤٥"), QString()),
             NewPasswordProblem::RepeatFirst);
    // Seven accented letters typed as two code points each are seven characters, not fourteen.
    const QString accented = QStringLiteral("é").repeated(6) + QStringLiteral("1");
    QCOMPARE(Air::newPasswordProblem(accented, QString()), NewPasswordProblem::Short);
    QCOMPARE(Air::newPasswordProblem(accented + QStringLiteral("é"), QString()),
             NewPasswordProblem::RepeatFirst);
}

void TestAirStart::newPasswordIsTypedTheSameTwice()
{
    const QString password = QStringLiteral("Kitab2026");
    QCOMPARE(Air::newPasswordProblem(password, QString()), NewPasswordProblem::RepeatFirst);
    QCOMPARE(Air::newPasswordProblem(password, QStringLiteral("Kitab2025")),
             NewPasswordProblem::Mismatch);
    QCOMPARE(Air::newPasswordProblem(password, QStringLiteral("kitab2026")),
             NewPasswordProblem::Mismatch);
    QCOMPARE(Air::newPasswordProblem(password, password), NewPasswordProblem::None);
}

void TestAirStart::fileNameKeepsTheNameWithoutWhatFileSystemsRefuse()
{
    const QString fallback = QStringLiteral("Profile");
    QCOMPARE(Air::profileFileName(QStringLiteral("Амина Рахимова"), {}, fallback),
             QStringLiteral("Амина Рахимова"));
    QCOMPARE(Air::profileFileName(QStringLiteral("a/b\\c:d*e?f\"g<h>i|j"), {}, fallback),
             QStringLiteral("a b c d e f g h i j"));
    QCOMPARE(Air::profileFileName(QStringLiteral("Amina\nRahimova\t"), {}, fallback),
             QStringLiteral("Amina Rahimova"));
    // A leading dot would hide the files; Windows drops trailing dots and spaces.
    QCOMPARE(Air::profileFileName(QStringLiteral("  ..Amina.  "), {}, fallback),
             QStringLiteral("Amina"));
    // A direction override would turn the name round on screen; it goes, the joiners stay.
    QCOMPARE(Air::profileFileName(QStringLiteral("Amina\u202Egpj.exe"), {}, fallback),
             QStringLiteral("Amina gpj.exe"));
    QCOMPARE(Air::profileFileName(QStringLiteral("نامه‌ای"), {}, fallback),
             QStringLiteral("نامه‌ای"));
}

void TestAirStart::fileNameIsUniqueWithoutCase()
{
    const QString fallback = QStringLiteral("Profile");
    QCOMPARE(Air::profileFileName(QStringLiteral("Амина"), {QStringLiteral("Марьям")}, fallback),
             QStringLiteral("Амина"));
    QCOMPARE(Air::profileFileName(QStringLiteral("Амина"), {QStringLiteral("амина")}, fallback),
             QStringLiteral("Амина 2"));
    QCOMPARE(Air::profileFileName(QStringLiteral("Амина"),
                                  {QStringLiteral("Амина"), QStringLiteral("АМИНА 2")}, fallback),
             QStringLiteral("Амина 3"));
}

void TestAirStart::fileNameAvoidsTheNamesWindowsKeeps()
{
    const QString fallback = QStringLiteral("Profile");
    QCOMPARE(Air::profileFileName(QStringLiteral("Con"), {}, fallback), QStringLiteral("Con 2"));
    QCOMPARE(Air::profileFileName(QStringLiteral("nul"), {}, fallback), QStringLiteral("nul 2"));
    QCOMPARE(Air::profileFileName(QStringLiteral("COM1"), {}, fallback), QStringLiteral("COM1 2"));
    QCOMPARE(Air::profileFileName(QStringLiteral("lpt9"), {}, fallback), QStringLiteral("lpt9 2"));
    QCOMPARE(Air::profileFileName(QStringLiteral("COM0"), {}, fallback), QStringLiteral("COM0"));
    QCOMPARE(Air::profileFileName(QStringLiteral("Console"), {}, fallback),
             QStringLiteral("Console"));
}

void TestAirStart::fileNameFallsBackWhenNothingIsLeft()
{
    QCOMPARE(Air::profileFileName(QStringLiteral("///"), {}, QStringLiteral("Amina")),
             QStringLiteral("Amina"));
    QCOMPARE(Air::profileFileName(QStringLiteral(" . "), {QStringLiteral("amina")},
                                  QStringLiteral("Amina")),
             QStringLiteral("Amina 2"));
    QCOMPARE(Air::profileFileName(QString(), {}, QString()), QStringLiteral("Profile"));
}

void TestAirStart::fileNameKeepsToItsLimits()
{
    const QString fallback = QStringLiteral("Profile");
    const QString longName = QStringLiteral("я").repeated(100);
    const QString cut = Air::profileFileName(longName, {}, fallback);
    QCOMPARE(cut, QStringLiteral("я").repeated(Air::profileFileNameMaxLength));

    // Four bytes each: the bytes run out before the characters do.
    const QString thumbs = QString::fromUtf8("\xF0\x9F\x91\x8D").repeated(100);
    const QString thumbsCut = Air::profileFileName(thumbs, {}, fallback);
    QCOMPARE(thumbsCut.toUtf8().size(), Air::profileFileNameMaxBytes);
    QCOMPARE(Air::visibleLength(thumbsCut), Air::profileFileNameMaxBytes / 4);

    // A number for a taken name fits in the same limits, and the cut never splits a character.
    const QString numbered = Air::profileFileName(longName, {cut}, fallback);
    QCOMPARE(numbered,
             QStringLiteral("я").repeated(Air::profileFileNameMaxLength - 2) + QStringLiteral(" 2"));
    const QString accents = QStringLiteral("é").repeated(100);
    const QString accentsCut = Air::profileFileName(accents, {}, fallback);
    QCOMPARE(accentsCut, QStringLiteral("é").repeated(Air::profileFileNameMaxLength));
    QVERIFY(accentsCut.toUtf8().size() <= Air::profileFileNameMaxBytes);
    // A space the cut leaves at the end goes, as Windows would drop it.
    const QString spaced =
        QStringLiteral("a").repeated(Air::profileFileNameMaxLength - 1) + QStringLiteral(" b");
    QCOMPARE(Air::profileFileName(spaced, {}, fallback),
             QStringLiteral("a").repeated(Air::profileFileNameMaxLength - 1));
}

void TestAirStart::failureTellsItsKindAndCode()
{
    const Air::OpenFailure none;
    QVERIFY(!none.failed());
    QCOMPARE(none.kind, Air::OpenFailure::Kind::None);

    const Air::OpenFailure wrong(Air::OpenFailure::Kind::WrongPassword,
                                 QStringLiteral("LoadToxDataError 5"));
    QVERIFY(wrong.failed());
    QVERIFY(wrong
            == Air::OpenFailure(Air::OpenFailure::Kind::WrongPassword,
                                QStringLiteral("LoadToxDataError 5")));
    QVERIFY(!(wrong
              == Air::OpenFailure(Air::OpenFailure::Kind::Damaged,
                                  QStringLiteral("LoadToxDataError 5"))));
}

QTEST_GUILESS_MAIN(TestAirStart)
#include "airstart_test.moc"
