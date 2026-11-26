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

#include "mock/mockbootstraplistgenerator.h"
#include "mock/mockcoresettings.h"
#include "src/core/core.h"

#include <QMutex>
#include <QSignalSpy>
#include <QtTest/QtTest>

#include <tox/tox.h>

#include <cstdint>
#include <limits>

namespace {
uint32_t numberOf(const Core& core, const QByteArray& chatId)
{
    Tox_Err_Group_State_Queries error;
    const uint32_t number =
        tox_group_by_chat_id(core.getTox(), reinterpret_cast<const uint8_t*>(chatId.constData()),
                             &error);
    return error == TOX_ERR_GROUP_STATE_QUERIES_OK ? number : std::numeric_limits<uint32_t>::max();
}

QByteArray chatIdOf(const Core& core, int group)
{
    return core.getGroupPersistentId(static_cast<uint32_t>(group)).getByteArray();
}

QString nameIn(const Core& core, const QByteArray& chatId)
{
    return core.getGroupSelfName(static_cast<int>(numberOf(core, chatId)));
}

const QString maryam = QStringLiteral("Maryam");
const QString sister = QStringLiteral("Sister Maryam");
} // namespace

// KHANDAQ (beta feedback 2026-11-22): our name in a group, on cores that are never started unless
// a test says so. toxcore gave the profile name to friends only, and every join took it: a group
// with a name of our own keeps it through a rename of the profile and a join again, and every
// other group takes the profile name.
class TestCoreGroupSelfName : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void aRenameOfTheProfilePassesToTheGroupsWithoutANameOfTheirOwn();
    void theProfileNameMakesAGroupFollowTheProfileAgain();
    void theNextStartKeepsTheNamesAndMendsAnOldOne();
    void aGroupJoinedAgainTakesItsOwnName();
    void leavingAGroupForgetsItsName();

private:
    ToxCorePtr makeCore(const QByteArray& savedata = QByteArray());
    QByteArray foundGroup();

    MockSettings settings;
    MockBootstrapListGenerator nodes;
};

ToxCorePtr TestCoreGroupSelfName::makeCore(const QByteArray& savedata)
{
    Core::ToxCoreErrors error = Core::ToxCoreErrors::FAILED_TO_START;
    return Core::makeToxCore(savedata, settings, nodes, &error);
}

QByteArray TestCoreGroupSelfName::foundGroup()
{
    const ToxCorePtr founder = makeCore();
    if (!founder) {
        return QByteArray();
    }
    const int group = founder->createGroup(QStringLiteral("Join"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    return group < 0 ? QByteArray() : chatIdOf(*founder, group);
}

void TestCoreGroupSelfName::initTestCase()
{
    settings.setProxyAddr(QString());
    settings.setProxyPort(0);
    settings.setProxyType(MockSettings::ProxyType::ptNone);
}

void TestCoreGroupSelfName::aRenameOfTheProfilePassesToTheGroupsWithoutANameOfTheirOwn()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    core->setUsername(maryam);
    const int study = core->createGroup(QStringLiteral("Study"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    const int family = core->createGroup(QStringLiteral("Family"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(study >= 0);
    QVERIFY(family >= 0);
    QCOMPARE(core->getGroupSelfName(study), maryam);
    QCOMPARE(core->getGroupSelfName(family), maryam);
    QSignalSpy kept(core.get(), &Core::groupSelfNamesChanged);
    QSignalSpy saves(core.get(), &Core::saveRequest);

    QVERIFY(core->setGroupSelfName(study, sister));
    QCOMPARE(core->getGroupSelfName(study), sister);
    QCOMPARE(kept.count(), 1);
    QCOMPARE(kept.at(0).at(0).toString(), core->getGroupSelfNames());
    QVERIFY(!core->getGroupSelfNames().isEmpty());
    QCOMPARE(saves.count(), 1);
    // The same name again changes nothing.
    QVERIFY(core->setGroupSelfName(study, sister));
    QCOMPARE(kept.count(), 1);
    QCOMPARE(saves.count(), 1);

    // Before: the rename reached no group. Now it reaches the one without a name of its own.
    core->setUsername(QStringLiteral("Maryam A."));
    QCOMPARE(core->getGroupSelfName(study), sister);
    QCOMPARE(core->getGroupSelfName(family), QStringLiteral("Maryam A."));
    QCOMPARE(kept.count(), 1);

    // No group, or no name, is refused.
    QVERIFY(!core->setGroupSelfName(99, sister));
    QVERIFY(!core->setGroupSelfName(family, QString()));
    QCOMPARE(core->getGroupSelfName(family), QStringLiteral("Maryam A."));
}

void TestCoreGroupSelfName::theProfileNameMakesAGroupFollowTheProfileAgain()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    core->setUsername(maryam);
    const int study = core->createGroup(QStringLiteral("Study"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(study >= 0);
    QVERIFY(core->setGroupSelfName(study, sister));
    QSignalSpy kept(core.get(), &Core::groupSelfNamesChanged);

    QVERIFY(core->setGroupSelfName(study, maryam));
    QCOMPARE(core->getGroupSelfName(study), maryam);
    QCOMPARE(kept.count(), 1);
    QCOMPARE(kept.at(0).at(0).toString(), QString());
    core->setUsername(QStringLiteral("Maryam A."));
    QCOMPARE(core->getGroupSelfName(study), QStringLiteral("Maryam A."));
}

void TestCoreGroupSelfName::theNextStartKeepsTheNamesAndMendsAnOldOne()
{
    // The groups load on the thread of the node: what they show is kept under a lock until the
    // node stops.
    QMutex shownLock;
    int shown = 0;
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    core->setUsername(maryam);
    const int study = core->createGroup(QStringLiteral("Study"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    const int family = core->createGroup(QStringLiteral("Family"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(study >= 0);
    QVERIFY(family >= 0);
    const QByteArray studyId = chatIdOf(*core, study);
    const QByteArray familyId = chatIdOf(*core, family);
    QVERIFY(core->setGroupSelfName(study, sister));
    // What an older build left: the profile renamed, and the group kept the name from before.
    const QByteArray old = QByteArrayLiteral("Maryam before");
    Tox_Err_Group_Self_Name_Set setError;
    QVERIFY(tox_group_self_set_name(core->getTox(), static_cast<uint32_t>(family),
                                    reinterpret_cast<const uint8_t*>(old.constData()),
                                    static_cast<size_t>(old.size()), &setError));
    const QString stored = core->getGroupSelfNames();
    const QByteArray save = core->getToxSaveData();
    core.reset();

    ToxCorePtr next = makeCore(save);
    QVERIFY(next);
    // toxcore keeps the name of each group in the save.
    QCOMPARE(nameIn(*next, studyId), sister);
    QCOMPARE(nameIn(*next, familyId), QStringLiteral("Maryam before"));
    next->restoreGroupSelfNames(stored);
    QCOMPARE(next->getGroupSelfNames(), stored);
    // GroupId is not registered for a queued connection in this test.
    connect(
        next.get(), &Core::emptyGroupCreated, this,
        [&](uint32_t, const GroupId&, const QString&) {
            const QMutexLocker locker{&shownLock};
            ++shown;
        },
        Qt::DirectConnection);
    const auto shownCount = [&] {
        const QMutexLocker locker{&shownLock};
        return shown;
    };

    next->start();

    QTRY_COMPARE_WITH_TIMEOUT(shownCount(), 2, 5000);
    // Each group shows with the name we should have there: its own, else the profile name.
    QCOMPARE(nameIn(*next, studyId), sister);
    QCOMPARE(nameIn(*next, familyId), maryam);
    next.reset();
}

void TestCoreGroupSelfName::aGroupJoinedAgainTakesItsOwnName()
{
    const QByteArray chatId = foundGroup();
    QCOMPARE(chatId.size(), TOX_GROUP_CHAT_ID_SIZE);
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    core->setUsername(maryam);
    const uint32_t number = core->joinGroupByChatId(chatId);
    QVERIFY(number != std::numeric_limits<uint32_t>::max());
    QCOMPARE(core->getGroupSelfName(static_cast<int>(number)), maryam);
    // A group that has not synced takes a name as well.
    QVERIFY(core->setGroupSelfName(static_cast<int>(number), sister));
    const QString joins = core->getUnsyncedGroupJoins();
    const QString names = core->getGroupSelfNames();
    const QByteArray save = core->getToxSaveData();
    core.reset();

    // toxcore saved neither the group nor its name: the next start joins it again with its own.
    ToxCorePtr next = makeCore(save);
    QVERIFY(next);
    QCOMPARE(numberOf(*next, chatId), std::numeric_limits<uint32_t>::max());
    next->restoreGroupSelfNames(names);
    next->rejoinStoredGroups(joins);
    QCOMPARE(nameIn(*next, chatId), sister);

    // Without its name kept, it would take the profile name.
    ToxCorePtr plain = makeCore(save);
    QVERIFY(plain);
    plain->rejoinStoredGroups(joins);
    QCOMPARE(nameIn(*plain, chatId), maryam);
}

void TestCoreGroupSelfName::leavingAGroupForgetsItsName()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    core->setUsername(maryam);
    const int study = core->createGroup(QStringLiteral("Study"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(study >= 0);
    QVERIFY(core->setGroupSelfName(study, sister));
    QSignalSpy kept(core.get(), &Core::groupSelfNamesChanged);

    core->removeGroup(study);

    QCOMPARE(kept.count(), 1);
    QCOMPARE(kept.at(0).at(0).toString(), QString());
    QCOMPARE(core->getGroupSelfNames(), QString());
}

QTEST_GUILESS_MAIN(TestCoreGroupSelfName)
#include "coregroupselfname_test.moc"
