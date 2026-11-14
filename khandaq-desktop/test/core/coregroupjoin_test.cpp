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
#include "src/model/air/airgroupleave.h"

#include <QMutex>
#include <QSignalSpy>
#include <QStringList>
#include <QtTest/QtTest>

#include <tox/tox.h>

#include <cstdint>
#include <limits>

namespace {
bool hasGroup(const Core& core, const QByteArray& chatId)
{
    Tox_Err_Group_State_Queries error;
    const uint32_t number = tox_group_by_chat_id(
        core.getTox(), reinterpret_cast<const uint8_t*>(chatId.constData()), &error);
    return error == TOX_ERR_GROUP_STATE_QUERIES_OK
           && number != std::numeric_limits<uint32_t>::max();
}

QByteArray chatIdOf(const Core& core, int group)
{
    return core.getGroupPersistentId(static_cast<uint32_t>(group)).getByteArray();
}
} // namespace

// 0.2.56: a group joined by its ID, on cores that are never started unless a test says so: such a
// group never finds a member, and so never syncs. toxcore saves a group only once it synced, so
// the profile keeps the chat ids of those joins, and the next start joins them again.
class TestCoreGroupJoin : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void aGroupJoinedByItsIdIsKeptUntilItSyncs();
    void theNextStartJoinsItAgain();
    void aKeptJoinThatToxcoreLoadedIsForgotten();
    void aGroupLeftBeforeItSyncsIsForgotten();
    void theGroupsLoadWithTheOnesJoinedAgain();

private:
    ToxCorePtr makeCore(const QByteArray& savedata = QByteArray());
    // The chat id of a group that another node founded.
    QByteArray foundGroup();

    MockSettings settings;
    MockBootstrapListGenerator nodes;
};

ToxCorePtr TestCoreGroupJoin::makeCore(const QByteArray& savedata)
{
    Core::ToxCoreErrors error = Core::ToxCoreErrors::FAILED_TO_START;
    return Core::makeToxCore(savedata, settings, nodes, &error);
}

QByteArray TestCoreGroupJoin::foundGroup()
{
    const ToxCorePtr founder = makeCore();
    if (!founder) {
        return QByteArray();
    }
    const int group = founder->createGroup(QStringLiteral("Join"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    return group < 0 ? QByteArray() : chatIdOf(*founder, group);
}

void TestCoreGroupJoin::initTestCase()
{
    settings.setProxyAddr(QString());
    settings.setProxyPort(0);
    settings.setProxyType(MockSettings::ProxyType::ptNone);
}

void TestCoreGroupJoin::aGroupJoinedByItsIdIsKeptUntilItSyncs()
{
    const QByteArray chatId = foundGroup();
    QCOMPARE(chatId.size(), TOX_GROUP_CHAT_ID_SIZE);
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    QSignalSpy kept(core.get(), &Core::unsyncedGroupJoinsChanged);

    QVERIFY(core->joinGroupByChatId(chatId) != std::numeric_limits<uint32_t>::max());

    const QString stored = Air::PendingGroupLeaves::store({chatId});
    QCOMPARE(kept.count(), 1);
    QCOMPARE(kept.at(0).at(0).toString(), stored);
    QCOMPARE(core->getUnsyncedGroupJoins(), stored);
    // Joining it again keeps it once.
    QVERIFY(core->joinGroupByChatId(chatId) != std::numeric_limits<uint32_t>::max());
    QCOMPARE(kept.count(), 1);
    QCOMPARE(core->getUnsyncedGroupJoins(), stored);

    // toxcore does not save it: without the profile, the next start has no such group.
    const ToxCorePtr next = makeCore(core->getToxSaveData());
    QVERIFY(next);
    QVERIFY(!hasGroup(*next, chatId));
}

void TestCoreGroupJoin::theNextStartJoinsItAgain()
{
    const QByteArray chatId = foundGroup();
    QCOMPARE(chatId.size(), TOX_GROUP_CHAT_ID_SIZE);
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    QVERIFY(core->joinGroupByChatId(chatId) != std::numeric_limits<uint32_t>::max());
    const QString stored = core->getUnsyncedGroupJoins();
    const QByteArray save = core->getToxSaveData();
    core.reset();

    ToxCorePtr next = makeCore(save);
    QVERIFY(next);
    QSignalSpy kept(next.get(), &Core::unsyncedGroupJoinsChanged);
    next->rejoinStoredGroups(stored);

    // Joined again, and kept until it syncs: nothing changed for the profile.
    QVERIFY(hasGroup(*next, chatId));
    QCOMPARE(next->getUnsyncedGroupJoins(), stored);
    QCOMPARE(kept.count(), 0);
    // Nothing kept, nothing to join.
    const ToxCorePtr fresh = makeCore();
    QVERIFY(fresh);
    QSignalSpy freshKept(fresh.get(), &Core::unsyncedGroupJoinsChanged);
    fresh->rejoinStoredGroups(QString());
    QCOMPARE(freshKept.count(), 0);
    QCOMPARE(fresh->getUnsyncedGroupJoins(), QString());
}

void TestCoreGroupJoin::aKeptJoinThatToxcoreLoadedIsForgotten()
{
    // A group in the save synced before the app closed: toxcore loads it, and it is not joined
    // again. Here the node founded it, which syncs it at once.
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Synced"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    const QByteArray chatId = chatIdOf(*core, group);
    const QByteArray save = core->getToxSaveData();
    core.reset();

    ToxCorePtr next = makeCore(save);
    QVERIFY(next);
    QVERIFY(hasGroup(*next, chatId));
    QSignalSpy kept(next.get(), &Core::unsyncedGroupJoinsChanged);
    next->rejoinStoredGroups(Air::PendingGroupLeaves::store({chatId}));

    QCOMPARE(kept.count(), 1);
    QCOMPARE(kept.at(0).at(0).toString(), QString());
    QCOMPARE(next->getUnsyncedGroupJoins(), QString());
}

void TestCoreGroupJoin::aGroupLeftBeforeItSyncsIsForgotten()
{
    const QByteArray chatId = foundGroup();
    QCOMPARE(chatId.size(), TOX_GROUP_CHAT_ID_SIZE);
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const uint32_t number = core->joinGroupByChatId(chatId);
    QVERIFY(number != std::numeric_limits<uint32_t>::max());
    QSignalSpy kept(core.get(), &Core::unsyncedGroupJoinsChanged);

    core->removeGroup(static_cast<int>(number));

    // The next start does not join it again.
    QCOMPARE(kept.count(), 1);
    QCOMPARE(kept.at(0).at(0).toString(), QString());
    QCOMPARE(core->getUnsyncedGroupJoins(), QString());
}

void TestCoreGroupJoin::theGroupsLoadWithTheOnesJoinedAgain()
{
    // toxcore lists only the groups it saves (tox_group_get_grouplist): the loop of the node shows
    // the one joined again as well, at its start. Its thread tells them, so the names it gives are
    // kept under a lock until it stops.
    QMutex shownLock;
    QList<QByteArray> shown;
    QStringList titles;
    const QByteArray joinedId = foundGroup();
    QCOMPARE(joinedId.size(), TOX_GROUP_CHAT_ID_SIZE);
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int founded = core->createGroup(QStringLiteral("Own"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(founded >= 0);
    const QByteArray foundedId = chatIdOf(*core, founded);
    QVERIFY(core->joinGroupByChatId(joinedId) != std::numeric_limits<uint32_t>::max());
    const QString stored = core->getUnsyncedGroupJoins();
    const QByteArray save = core->getToxSaveData();
    core.reset();

    ToxCorePtr next = makeCore(save);
    QVERIFY(next);
    next->rejoinStoredGroups(stored);
    // GroupId is not registered for a queued connection in this test.
    connect(next.get(), &Core::emptyGroupCreated, this,
            [&](uint32_t, const GroupId& groupId, const QString& title) {
                const QMutexLocker locker{&shownLock};
                shown.append(groupId.getByteArray());
                titles.append(title);
            },
            Qt::DirectConnection);
    const auto shownCount = [&] {
        const QMutexLocker locker{&shownLock};
        return shown.size();
    };

    next->start();

    QTRY_COMPARE_WITH_TIMEOUT(shownCount(), 2, 5000);
    next.reset();
    QVERIFY(shown.contains(foundedId));
    QVERIFY(shown.contains(joinedId));
    // A group that did not sync has no name yet: it shows with the start of its key.
    QVERIFY(!titles.contains(QString()));
}

QTEST_GUILESS_MAIN(TestCoreGroupJoin)
#include "coregroupjoin_test.moc"
