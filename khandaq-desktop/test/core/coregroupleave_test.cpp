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
#include "src/model/groupinvite.h"

#include <QSignalSpy>
#include <QStringList>
#include <QtTest/QtTest>

#include <tox/tox.h>

#include <cstdint>
#include <limits>

namespace {
bool hasGroup(const Core& core, int group)
{
    uint8_t chatId[TOX_GROUP_CHAT_ID_SIZE];
    Tox_Err_Group_State_Queries error;
    return tox_group_get_chat_id(core.getTox(), static_cast<uint32_t>(group), chatId, &error);
}

QByteArray chatIdOf(const Core& core, int group)
{
    return core.getGroupPersistentId(static_cast<uint32_t>(group)).getByteArray();
}

int groupCount(const Core& core)
{
    return static_cast<int>(tox_group_get_number_groups(core.getTox()));
}
} // namespace

// QA live F11: how the core leaves a group, on cores that are never started: a group founded here
// is all there is, and nothing goes out. Such a group has no other member, so its leave never
// waits for the notice; the waiting itself is Air::PendingGroupLeaves' (airgroupleave_test).
class TestCoreGroupLeave : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void aGroupWithoutAnotherMemberIsLeftAtOnce();
    void aGroupThatTakesNoNoticeIsLeftAtOnce();
    void closingTheProfileLetsToxcoreDeleteTheGroups();
    void theNextStartFinishesTheLeave();
    void aKeptGroupThatIsGoneIsForgotten();
    void joiningAgainWaitsForToxcoreToLetGo();
    void joiningAKeptGroupAtTheStartKeepsIt();
    void acceptingAnInviteWaitsForToxcoreToLetGo();

private:
    ToxCorePtr makeCore(const QByteArray& savedata = QByteArray());

    MockSettings settings;
    MockBootstrapListGenerator nodes;
};

ToxCorePtr TestCoreGroupLeave::makeCore(const QByteArray& savedata)
{
    Core::ToxCoreErrors error = Core::ToxCoreErrors::FAILED_TO_START;
    return Core::makeToxCore(savedata, settings, nodes, &error);
}

void TestCoreGroupLeave::initTestCase()
{
    settings.setProxyAddr(QString());
    settings.setProxyPort(0);
    settings.setProxyType(MockSettings::ProxyType::ptNone);
}

void TestCoreGroupLeave::aGroupWithoutAnotherMemberIsLeftAtOnce()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Leave"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    Tox_Err_Group_Peer_Query peerError;
    QCOMPARE(tox_group_peer_count(core->getTox(), static_cast<uint32_t>(group), &peerError), 1u);
    QSignalSpy kept(core.get(), &Core::pendingGroupLeavesChanged);
    QSignalSpy saved(core.get(), &Core::saveRequest);

    core->removeGroup(group);

    // toxcore takes the notice as sent, but nobody gets it: nothing waits, and nothing is kept.
    QCOMPARE(kept.count(), 0);
    QCOMPARE(core->getPendingGroupLeaves(), QString());
    // toxcore deletes the group at the next tox_iterate, and the save follows it.
    QVERIFY(hasGroup(*core, group));
    QCOMPARE(saved.count(), 0);
    core->leavePendingGroupsNow();
    QVERIFY(!hasGroup(*core, group));
    QCOMPARE(saved.count(), 1);
    QCOMPARE(kept.count(), 0);
}

void TestCoreGroupLeave::aGroupThatTakesNoNoticeIsLeftAtOnce()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Leave"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    Tox_Err_Group_Disconnect disconnectError;
    QVERIFY(tox_group_disconnect(core->getTox(), static_cast<uint32_t>(group), &disconnectError));
    QSignalSpy kept(core.get(), &Core::pendingGroupLeavesChanged);
    QSignalSpy saved(core.get(), &Core::saveRequest);

    core->removeGroup(group);

    // toxcore sends nothing to a group it is disconnected from: nothing waits, and nothing is kept.
    QCOMPARE(kept.count(), 0);
    QCOMPARE(core->getPendingGroupLeaves(), QString());
    QVERIFY(hasGroup(*core, group));
    QCOMPARE(saved.count(), 0);
    core->leavePendingGroupsNow();
    QVERIFY(!hasGroup(*core, group));
    QCOMPARE(saved.count(), 1);
}

void TestCoreGroupLeave::closingTheProfileLetsToxcoreDeleteTheGroups()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Leave"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    core->removeGroup(group);
    {
        // Until the next tox_iterate, toxcore saves the group it was told to leave.
        const ToxCorePtr saved = makeCore(core->getToxSaveData());
        QVERIFY(saved);
        QCOMPARE(groupCount(*saved), 1);
    }

    core->leavePendingGroupsNow();

    const ToxCorePtr next = makeCore(core->getToxSaveData());
    QVERIFY(next);
    QCOMPARE(groupCount(*next), 0);
}

void TestCoreGroupLeave::theNextStartFinishesTheLeave()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Leave"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    // The app stops while the leave waits: the last save holds the group, the profile its chat id.
    const QString stored = Air::PendingGroupLeaves::store({chatIdOf(*core, group)});
    const QByteArray save = core->getToxSaveData();
    core.reset();

    ToxCorePtr next = makeCore(save);
    QVERIFY(next);
    QCOMPARE(groupCount(*next), 1);
    QStringList events;
    connect(next.get(), &Core::saveRequest, this,
            [&events] { events.append(QStringLiteral("save")); });
    connect(next.get(), &Core::pendingGroupLeavesChanged, this,
            [&events](const QString& kept) { events.append(QStringLiteral("kept:") + kept); });

    next->leaveStoredGroups(stored);

    // Left without a notice; the profile keeps the chat id until the save without the group.
    QVERIFY(events.isEmpty());
    QCOMPARE(next->getPendingGroupLeaves(), stored);
    next->leavePendingGroupsNow();
    QCOMPARE(groupCount(*next), 0);
    QCOMPARE(events, (QStringList{QStringLiteral("save"), QStringLiteral("kept:")}));
    QCOMPARE(next->getPendingGroupLeaves(), QString());
}

void TestCoreGroupLeave::aKeptGroupThatIsGoneIsForgotten()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    QSignalSpy kept(core.get(), &Core::pendingGroupLeavesChanged);

    core->leaveStoredGroups(Air::PendingGroupLeaves::store({QByteArray(32, '\x0c')}));

    QCOMPARE(kept.count(), 1);
    QCOMPARE(kept.at(0).at(0).toString(), QString());
    QCOMPARE(core->getPendingGroupLeaves(), QString());
    // Nothing kept, nothing to say.
    core->leaveStoredGroups(QString());
    QCOMPARE(kept.count(), 1);
}

void TestCoreGroupLeave::joiningAgainWaitsForToxcoreToLetGo()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Rejoin"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    const QByteArray chatId = chatIdOf(*core, group);
    core->removeGroup(group);
    QVector<int> joined;
    connect(core.get(), &Core::groupJoined, this, [&joined, &chatId](int number, GroupId groupId) {
        QCOMPARE(groupId.getByteArray(), chatId);
        joined.append(number);
    });

    // toxcore still has the old instance and refuses the chat id: the join waits for it to go.
    QCOMPARE(core->joinGroupByChatId(chatId), static_cast<uint32_t>(group));
    QVERIFY(joined.isEmpty());
    QCOMPARE(core->joinGroupByChatId(chatId), static_cast<uint32_t>(group));
    QVERIFY(joined.isEmpty());

    core->leavePendingGroupsNow();

    QCOMPARE(joined.size(), 2);
    QCOMPARE(joined.at(1), joined.at(0));
    QVERIFY(hasGroup(*core, joined.first()));
    QCOMPARE(chatIdOf(*core, joined.first()), chatId);
}

void TestCoreGroupLeave::joiningAKeptGroupAtTheStartKeepsIt()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Rejoin"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    const QByteArray chatId = chatIdOf(*core, group);
    const QByteArray save = core->getToxSaveData();
    core.reset();
    ToxCorePtr next = makeCore(save);
    QVERIFY(next);
    next->leaveStoredGroups(Air::PendingGroupLeaves::store({chatId}));
    QVector<int> joined;
    connect(next.get(), &Core::groupJoined, this, [&joined](int number) { joined.append(number); });
    QSignalSpy kept(next.get(), &Core::pendingGroupLeavesChanged);

    const uint32_t number = next->joinGroupByChatId(chatId);

    // Joined again, so it is not left at the next start.
    QVERIFY(number != std::numeric_limits<uint32_t>::max());
    QCOMPARE(kept.count(), 1);
    QCOMPARE(kept.at(0).at(0).toString(), QString());
    QCOMPARE(next->getPendingGroupLeaves(), QString());
    QVERIFY(joined.isEmpty());
    next->leavePendingGroupsNow();
    QCOMPARE(joined.size(), 1);
    QCOMPARE(chatIdOf(*next, joined.first()), chatId);
    QCOMPARE(kept.count(), 1);
}

void TestCoreGroupLeave::acceptingAnInviteWaitsForToxcoreToLetGo()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Invite"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    const QByteArray chatId = chatIdOf(*core, group);
    core->removeGroup(group);

    // An invite starts with the chat id. It is accepted once toxcore let go of the old instance;
    // here no friend sent it, and toxcore refuses it then.
    const GroupInvite invite(0, chatId + QByteArray(32, '\0'), QStringLiteral("Invite"));
    QCOMPARE(core->joinGroupchat(invite), static_cast<uint32_t>(group));
    QVERIFY(hasGroup(*core, group));

    core->leavePendingGroupsNow();

    QVERIFY(!hasGroup(*core, group));
}

QTEST_GUILESS_MAIN(TestCoreGroupLeave)
#include "coregroupleave_test.moc"
