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

#include "src/model/air/airgroupselfname.h"

#include <QtTest/QtTest>

using Air::GroupSelfNames;

namespace {
const QByteArray study(32, '\x0a');
const QByteArray family(32, '\x0b');
const QString profile = QStringLiteral("Maryam");
} // namespace

// KHANDAQ (beta feedback 2026-11-22): a group with a name of our own keeps it; every other group
// takes the profile name, at a rename of the profile and at a join.
class TestAirGroupSelfName : public QObject
{
    Q_OBJECT
private slots:
    void aGroupWithoutANameOfItsOwnFollowsTheProfile();
    void aNameOfItsOwnIsKeptThroughARenameOfTheProfile();
    void theProfileNameMakesTheGroupFollowTheProfileAgain();
    void leavingTheGroupForgetsItsName();
    void whatTheProfileKeepsComesBack();
    void aStoreThatIsNotOneGivesNoName();
    void toxcoreIsGivenANameItTakesAndHasNot();
};

void TestAirGroupSelfName::aGroupWithoutANameOfItsOwnFollowsTheProfile()
{
    GroupSelfNames names;
    QVERIFY(names.isEmpty());
    QVERIFY(!names.hasOwn(study));
    QCOMPARE(names.nameFor(study, profile), profile);
    // The profile renamed: the group takes the new name.
    QCOMPARE(names.nameFor(study, QStringLiteral("Maryam A.")), QStringLiteral("Maryam A."));
}

void TestAirGroupSelfName::aNameOfItsOwnIsKeptThroughARenameOfTheProfile()
{
    GroupSelfNames names;
    QVERIFY(names.choose(study, QStringLiteral("Sister Maryam"), profile));
    QVERIFY(names.hasOwn(study));
    QCOMPARE(names.nameFor(study, profile), QStringLiteral("Sister Maryam"));
    QCOMPARE(names.nameFor(study, QStringLiteral("Maryam A.")), QStringLiteral("Sister Maryam"));
    // Another group follows the profile still.
    QVERIFY(!names.hasOwn(family));
    QCOMPARE(names.nameFor(family, QStringLiteral("Maryam A.")), QStringLiteral("Maryam A."));

    // The same name again changes nothing; another one replaces it.
    QVERIFY(!names.choose(study, QStringLiteral("Sister Maryam"), profile));
    QVERIFY(names.choose(study, QStringLiteral("Umm Yusuf"), profile));
    QCOMPARE(names.nameFor(study, profile), QStringLiteral("Umm Yusuf"));

    // No name, or no chat id, is never kept.
    QVERIFY(!names.choose(family, QString(), profile));
    QVERIFY(!names.choose(QByteArray(31, 'x'), QStringLiteral("Umm Yusuf"), profile));
    QVERIFY(!names.hasOwn(family));
}

void TestAirGroupSelfName::theProfileNameMakesTheGroupFollowTheProfileAgain()
{
    GroupSelfNames names;
    QVERIFY(names.choose(study, QStringLiteral("Sister Maryam"), profile));
    QVERIFY(names.choose(study, profile, profile));
    QVERIFY(!names.hasOwn(study));
    QCOMPARE(names.nameFor(study, QStringLiteral("Maryam A.")), QStringLiteral("Maryam A."));
    // A group that followed the profile already changes nothing.
    QVERIFY(!names.choose(family, profile, profile));
    QVERIFY(names.isEmpty());
}

void TestAirGroupSelfName::leavingTheGroupForgetsItsName()
{
    GroupSelfNames names;
    QVERIFY(names.choose(study, QStringLiteral("Sister Maryam"), profile));
    QVERIFY(names.choose(family, QStringLiteral("Mama"), profile));
    QVERIFY(names.forget(study));
    QVERIFY(!names.forget(study));
    // A later join takes the profile name; the other group keeps its own.
    QCOMPARE(names.nameFor(study, profile), profile);
    QCOMPARE(names.nameFor(family, profile), QStringLiteral("Mama"));
}

void TestAirGroupSelfName::whatTheProfileKeepsComesBack()
{
    GroupSelfNames names;
    QCOMPARE(names.store(), QString());
    QVERIFY(GroupSelfNames::parseStore(QString()).isEmpty());

    // Any letters, the quotes and the line breaks of JSON included.
    const QString arabic = QStringLiteral("مريم \"الأخت\"\n");
    QVERIFY(names.choose(study, arabic, profile));
    QVERIFY(names.choose(family, QStringLiteral("妈妈"), profile));
    const QString stored = names.store();
    QVERIFY(stored.contains(QString::fromLatin1(study.toHex())));

    const GroupSelfNames back = GroupSelfNames::parseStore(stored);
    QVERIFY(back.hasOwn(study));
    QVERIFY(back.hasOwn(family));
    QCOMPARE(back.nameFor(study, profile), arabic);
    QCOMPARE(back.nameFor(family, profile), QStringLiteral("妈妈"));
    QCOMPARE(back.store(), stored);
}

void TestAirGroupSelfName::aStoreThatIsNotOneGivesNoName()
{
    QVERIFY(GroupSelfNames::parseStore(QStringLiteral("not json")).isEmpty());
    QVERIFY(GroupSelfNames::parseStore(QStringLiteral("[\"a\"]")).isEmpty());
    // An entry without a whole chat id, with a key that is not all hex, or without a name is
    // skipped; the others stay.
    const QString hex = QString::fromLatin1(study.toHex());
    const QString stored =
        QStringLiteral("{\"%1\":\"Sister Maryam\",\"0a0b\":\"Short\",\"%2\":\"Odd\","
                       "\"%3\":\"\",\"%4\":7}")
            .arg(hex, hex.left(62) + QStringLiteral("zz"), QString::fromLatin1(family.toHex()),
                 QString(64, QLatin1Char('c')));
    const GroupSelfNames parsed = GroupSelfNames::parseStore(stored);
    QVERIFY(parsed.hasOwn(study));
    QCOMPARE(parsed.nameFor(study, profile), QStringLiteral("Sister Maryam"));
    QVERIFY(!parsed.hasOwn(family));
    QVERIFY(!parsed.hasOwn(QByteArray(32, '\xcc')));
    QCOMPARE(parsed.store(), QStringLiteral("{\"%1\":\"Sister Maryam\"}").arg(hex));
}

void TestAirGroupSelfName::toxcoreIsGivenANameItTakesAndHasNot()
{
    QVERIFY(Air::needsGroupSelfName(QStringLiteral("Maryam"), QStringLiteral("Maryam A.")));
    QVERIFY(Air::needsGroupSelfName(QString(), profile));
    QVERIFY(!Air::needsGroupSelfName(profile, profile));
    // toxcore refuses an empty name, and one over 128 bytes of UTF-8.
    QVERIFY(!Air::needsGroupSelfName(profile, QString()));
    QVERIFY(Air::needsGroupSelfName(profile, QString(64, QChar(0x0645))));
    QVERIFY(!Air::needsGroupSelfName(profile, QString(65, QChar(0x0645))));
}

QTEST_GUILESS_MAIN(TestAirGroupSelfName)
#include "airgroupselfname_test.moc"
