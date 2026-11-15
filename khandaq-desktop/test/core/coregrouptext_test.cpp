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

// 0.2.56: toxcore takes a group text while it has confirmed nobody else in the group, and says it
// went. Core hands it over only while the group is connected and has someone else in it
// (Air::groupTakesText); otherwise the text waits (GroupMessageDispatcher). The cores here are
// never started: their groups never find a member.
class TestCoreGroupText : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void aGroupJustMadeIsConnectedButTakesNoText();
    void aGroupStillConnectingTakesNoText();
    void aTextTooLongFailsWhetherTheGroupTakesTextsOrNot();
    void aGroupThatIsNotThereTakesNoText();

private:
    ToxCorePtr makeCore(const QByteArray& savedata = QByteArray());

    MockSettings settings;
    MockBootstrapListGenerator nodes;
};

using SendResult = ICoreGroupMessageSender::SendResult;

ToxCorePtr TestCoreGroupText::makeCore(const QByteArray& savedata)
{
    Core::ToxCoreErrors error = Core::ToxCoreErrors::FAILED_TO_START;
    return Core::makeToxCore(savedata, settings, nodes, &error);
}

void TestCoreGroupText::initTestCase()
{
    settings.setProxyAddr(QString());
    settings.setProxyPort(0);
    settings.setProxyType(MockSettings::ProxyType::ptNone);
}

void TestCoreGroupText::aGroupJustMadeIsConnectedButTakesNoText()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Alone"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    // What toxcore says of it: connected, with its founder alone.
    QCOMPARE(core->getGroupConnection(group), 1);
    QCOMPARE(core->getGroupNumberPeers(group), uint32_t{1});
    QSignalSpy failed(core.get(), &Core::groupSentFailed);

    uint32_t wireId = 1;
    QCOMPARE(core->sendGroupMessage(group, QStringLiteral("Hello"), wireId), SendResult::NotReady);
    QCOMPARE(wireId, uint32_t{0});
    wireId = 1;
    QCOMPARE(core->sendGroupAction(group, QStringLiteral("waves"), wireId), SendResult::NotReady);
    QCOMPARE(wireId, uint32_t{0});
    // Nothing failed: the text waits.
    QCOMPARE(failed.count(), 0);
}

void TestCoreGroupText::aGroupStillConnectingTakesNoText()
{
    // The founder's group back from the save connects again; until then it takes no text.
    ToxCorePtr founder = makeCore();
    QVERIFY(founder);
    const int founded =
        founder->createGroup(QStringLiteral("Back"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(founded >= 0);
    const QByteArray chatId =
        founder->getGroupPersistentId(static_cast<uint32_t>(founded)).getByteArray();
    const QByteArray save = founder->getToxSaveData();
    founder.reset();

    ToxCorePtr core = makeCore(save);
    QVERIFY(core);
    Tox_Err_Group_State_Queries error;
    const uint32_t loaded = tox_group_by_chat_id(
        core->getTox(), reinterpret_cast<const uint8_t*>(chatId.constData()), &error);
    QCOMPARE(error, TOX_ERR_GROUP_STATE_QUERIES_OK);
    const int group = static_cast<int>(loaded);
    QCOMPARE(core->getGroupConnection(group), 0);

    uint32_t wireId = 1;
    QCOMPARE(core->sendGroupMessage(group, QStringLiteral("Hello"), wireId), SendResult::NotReady);
    QCOMPARE(wireId, uint32_t{0});

    // A group joined by its ID that never synced: the same.
    ToxCorePtr joiner = makeCore();
    QVERIFY(joiner);
    const uint32_t joined = joiner->joinGroupByChatId(chatId);
    QVERIFY(joined != std::numeric_limits<uint32_t>::max());
    QCOMPARE(joiner->getGroupConnection(static_cast<int>(joined)), 0);
    QCOMPARE(joiner->sendGroupMessage(static_cast<int>(joined), QStringLiteral("Hi"), wireId),
             SendResult::NotReady);
}

void TestCoreGroupText::aTextTooLongFailsWhetherTheGroupTakesTextsOrNot()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Long"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    const QString tooLong(static_cast<int>(Core::getMaxGroupMessageSize()) + 1, QLatin1Char('a'));

    uint32_t wireId = 1;
    // It would not go later either, so it does not wait.
    QCOMPARE(core->sendGroupMessage(group, tooLong, wireId), SendResult::Failed);
    QCOMPARE(wireId, uint32_t{0});
}

void TestCoreGroupText::aGroupThatIsNotThereTakesNoText()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    uint32_t wireId = 1;
    QCOMPARE(core->sendGroupMessage(41, QStringLiteral("Hello"), wireId), SendResult::NotReady);
    QCOMPARE(wireId, uint32_t{0});
}

QTEST_GUILESS_MAIN(TestCoreGroupText)
#include "coregrouptext_test.moc"
