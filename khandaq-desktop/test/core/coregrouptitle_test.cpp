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

#include <QSignalSpy>
#include <QtTest/QtTest>

#include <tox/tox.h>

#include <cstdint>
#include <limits>

namespace {
QByteArray chatIdOf(const Core& core, int group)
{
    return core.getGroupPersistentId(static_cast<uint32_t>(group)).getByteArray();
}

const QString amina = QStringLiteral("Amina");
const QString name = QStringLiteral("Book club");
const QString topic = QStringLiteral("Reading circle");
} // namespace

// KHANDAQ (inbox #13, 27.11): the title of a group, on cores that are never started. As on iOS and
// Android, a group goes by its topic, which a rename sets, while it has one, else by its name in
// the group state. A rename toxcore refuses changes no title, and is said. What the join of a
// member brings needs two nodes: test_core (a_group_goes_by_its_topic_else_by_its_name).
class TestCoreGroupTitle : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void aRenameGivesTheGroupItsTopicAndAnEmptyOneItsName();
    void aRenameToxcoreRefusesChangesNoTitle();

private:
    ToxCorePtr makeCore();
    // The chat id of a group that another node founded.
    QByteArray foundGroup();

    MockSettings settings;
    MockBootstrapListGenerator nodes;
};

ToxCorePtr TestCoreGroupTitle::makeCore()
{
    Core::ToxCoreErrors error = Core::ToxCoreErrors::FAILED_TO_START;
    return Core::makeToxCore(QByteArray(), settings, nodes, &error);
}

QByteArray TestCoreGroupTitle::foundGroup()
{
    const ToxCorePtr founder = makeCore();
    if (!founder) {
        return QByteArray();
    }
    const int group = founder->createGroup(name, TOX_GROUP_PRIVACY_STATE_PUBLIC);
    return group < 0 ? QByteArray() : chatIdOf(*founder, group);
}

void TestCoreGroupTitle::initTestCase()
{
    settings.setProxyAddr(QString());
    settings.setProxyPort(0);
    settings.setProxyType(MockSettings::ProxyType::ptNone);
}

void TestCoreGroupTitle::aRenameGivesTheGroupItsTopicAndAnEmptyOneItsName()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    core->setUsername(amina);
    const int group = core->createGroup(name, TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    // A new group has no topic yet: it goes by its name.
    QCOMPARE(core->getGroupTopic(group), QString());
    QCOMPARE(core->getGroupTitle(group), name);
    // As toxcore makes a group, its topic is locked: the founder, as here, and the moderators
    // alone may rename it (Air::canRenameGroup).
    QVERIFY(core->getGroupSelfRole(group) == TOX_GROUP_ROLE_FOUNDER);
    QVERIFY(core->getGroupTopicLock(group) == TOX_GROUP_TOPIC_LOCK_ENABLED);
    QSignalSpy titles(core.get(), &Core::groupTitleChanged);
    QSignalSpy refusals(core.get(), &Core::groupTitleChangeFailed);
    QSignalSpy saves(core.get(), &Core::saveRequest);

    // toxcore takes the rename: the topic is the title now, once, under our name in the group.
    core->changeGroupTitle(group, topic);
    QCOMPARE(core->getGroupTopic(group), topic);
    QCOMPARE(core->getGroupName(group), name);
    QCOMPARE(core->getGroupTitle(group), topic);
    QCOMPARE(titles.count(), 1);
    QCOMPARE(titles.at(0).at(0).toInt(), group);
    QCOMPARE(titles.at(0).at(1).toString(), amina);
    QCOMPARE(titles.at(0).at(2).toString(), topic);
    QCOMPARE(refusals.count(), 0);
    QCOMPARE(saves.count(), 1);

    // An empty topic gives the group its name again.
    core->changeGroupTitle(group, QString());
    QCOMPARE(core->getGroupTopic(group), QString());
    QCOMPARE(core->getGroupTitle(group), name);
    QCOMPARE(titles.count(), 2);
    QCOMPARE(titles.at(1).at(1).toString(), amina);
    QCOMPARE(titles.at(1).at(2).toString(), name);
    QCOMPARE(refusals.count(), 0);
    QCOMPARE(saves.count(), 2);
}

void TestCoreGroupTitle::aRenameToxcoreRefusesChangesNoTitle()
{
    // A group of another node joined by its ID never syncs here: we are a member in it, and its
    // topic is locked, so toxcore sets no topic of ours.
    const QByteArray chatId = foundGroup();
    QCOMPARE(chatId.size(), TOX_GROUP_CHAT_ID_SIZE);
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const uint32_t number = core->joinGroupByChatId(chatId);
    QVERIFY(number != std::numeric_limits<uint32_t>::max());
    const int group = static_cast<int>(number);
    const QString before = core->getGroupTitle(group);
    QSignalSpy titles(core.get(), &Core::groupTitleChanged);
    QSignalSpy refusals(core.get(), &Core::groupTitleChangeFailed);
    QSignalSpy saves(core.get(), &Core::saveRequest);

    // Before, the title changed here all the same; now the group keeps it, and the window says so
    // (Widget::onGroupTitleChangeFailed).
    core->changeGroupTitle(group, topic);
    QCOMPARE(refusals.count(), 1);
    QCOMPARE(refusals.at(0).at(0).toInt(), group);
    QCOMPARE(titles.count(), 0);
    QCOMPARE(saves.count(), 0);
    QCOMPARE(core->getGroupTopic(group), QString());
    QCOMPARE(core->getGroupTitle(group), before);

    // A group toxcore does not have: the same. Its topic lock is unknown, which leaves a rename
    // to toxcore, as on Android.
    const int unknown = 99;
    QVERIFY(core->getGroupTopicLock(unknown) == TOX_GROUP_TOPIC_LOCK_DISABLED);
    core->changeGroupTitle(unknown, topic);
    QCOMPARE(refusals.count(), 2);
    QCOMPARE(refusals.at(1).at(0).toInt(), unknown);
    QCOMPARE(titles.count(), 0);
    QCOMPARE(core->getGroupTitle(unknown), QString());
}

QTEST_GUILESS_MAIN(TestCoreGroupTitle)
#include "coregrouptitle_test.moc"
