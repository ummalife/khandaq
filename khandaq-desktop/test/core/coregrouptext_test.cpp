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
#include "src/model/air/airgroupcontact.h"

#include <QSignalSpy>
#include <QtTest/QtTest>

#include <tox/tox.h>

#include <cstdint>
#include <limits>
#include <vector>

// 0.2.56: toxcore takes a group text while it has confirmed nobody else in the group, and says it
// went. Core hands it over only while the group is not disconnected and has someone else in it
// (Air::groupTakesText); otherwise the text waits (GroupMessageDispatcher). The cores here are
// never started: their groups never find a member.
class TestCoreGroupText : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void aGroupJustMadeIsConnectedButTakesNoText();
    void aGroupConnectingWithNobodyTakesNoText();
    void aTextTooLongFailsWhetherTheGroupTakesTextsOrNot();
    void aGroupThatIsNotThereTakesNoText();
    void aTextRelayedFromTheHistoryIsToldOnce();
    void aContactPacketOfNoMemberIsDropped();
    void aContactPacketGoesOnlyToAMemberOnline();

private:
    ToxCorePtr makeCore(const QByteArray& savedata = QByteArray());

    MockSettings settings;
    MockBootstrapListGenerator nodes;
};

using SendResult = ICoreGroupMessageSender::SendResult;

namespace {
/**
 * KHANDAQ (0.2.63): a text of the history as a member relays it (0x02): the magic, the version,
 * 02, the id 0x0A0B0C0D, the author, the time, a name of 25 bytes and the words.
 */
QByteArray syncText(const QByteArray& author, quint32 time, const QString& words,
                    char version = '\x01')
{
    QByteArray packet = QByteArray::fromHex("667788113435");
    packet.append(version);
    packet.append('\x02');
    packet.append(QByteArray::fromHex("0A0B0C0D"));
    packet.append(author);
    for (int shift = 24; shift >= 0; shift -= 8) {
        packet.append(static_cast<char>((time >> shift) & 0xFF));
    }
    packet.append(QByteArray(25, '\0'));
    packet.append(words.toUtf8());
    return packet;
}

/** A Tox ID whose checksum holds: the key, the nospam, then the XOR of the byte pairs. */
QByteArray addressOf(const QByteArray& key)
{
    QByteArray address = key + QByteArray::fromHex("01020304");
    char check[2] = {0, 0};
    for (int i = 0; i < address.size(); ++i) {
        check[i % 2] = static_cast<char>(check[i % 2] ^ address.at(i));
    }
    address.append(check, 2);
    return address;
}

void handContact(Core& core, int group, uint32_t peer, const QByteArray& packet)
{
    core.handleGroupContactPacket(static_cast<uint32_t>(group), peer,
                                  reinterpret_cast<const uint8_t*>(packet.constData()),
                                  static_cast<size_t>(packet.size()));
}

void handText(Core& core, int group, const QByteArray& packet)
{
    core.handleSyncedGroupText(static_cast<uint32_t>(group), 1,
                               reinterpret_cast<const uint8_t*>(packet.constData()),
                               static_cast<size_t>(packet.size()));
}
} // namespace

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

void TestCoreGroupText::aGroupConnectingWithNobodyTakesNoText()
{
    // The founder's group back from the save connects again; until a member reaches it, it takes
    // no text.
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
    QCOMPARE(core->getGroupNumberPeers(group), uint32_t{1});

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

/**
 * KHANDAQ (0.2.63): a text a member relays from the history of a group is told once, with the
 * time its author wrote it: not again, not out of the window of time (and then it keeps out no
 * real one), not of version 2, not naming us, and not for a group that is not there.
 */
void TestCoreGroupText::aTextRelayedFromTheHistoryIsToldOnce()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("History"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    struct Told
    {
        int group;
        ToxPk author;
        QString text;
        uint32_t id;
        QDateTime sentAt;
    };
    std::vector<Told> told;
    connect(core.get(), &Core::groupSyncedMessageReceived,
            [&told](int groupnumber, const ToxPk& author, const QString& message,
                    uint32_t messageId, const QDateTime& sentAt) {
                told.push_back({groupnumber, author, message, messageId, sentAt});
            });

    const QByteArray maryam(ToxPk::size, 'm');
    const quint32 now = static_cast<quint32>(QDateTime::currentSecsSinceEpoch());
    handText(*core, group, syncText(maryam, now, QStringLiteral("Salam")));
    QCOMPARE(told.size(), size_t{1});
    QCOMPARE(told[0].group, group);
    QCOMPARE(told[0].author, ToxPk(maryam));
    QCOMPARE(told[0].text, QStringLiteral("Salam"));
    QCOMPARE(told[0].id, uint32_t{0x0A0B0C0D});
    // The milliseconds are the last three digits of the id (Air::syncedTextTimeMs).
    QCOMPARE(told[0].sentAt.toMSecsSinceEpoch(), qint64{now} * 1000 + 0x0A0B0C0D % 1000);

    handText(*core, group, syncText(maryam, now, QStringLiteral("Salam")));
    QCOMPARE(told.size(), size_t{1});

    handText(*core, group, syncText(maryam, now - 200 * 60 - 60, QStringLiteral("Later")));
    QCOMPARE(told.size(), size_t{1});
    handText(*core, group, syncText(maryam, now, QStringLiteral("Later")));
    QCOMPARE(told.size(), size_t{2});

    const QByteArray groupSelf = core->getGroupSelfPublicKey(group).getByteArray();
    const QByteArray profileSelf = core->getSelfPublicKey().getByteArray();
    handText(*core, group, syncText(maryam, now, QStringLiteral("Signed"), '\x02'));
    handText(*core, group, syncText(groupSelf, now, QStringLiteral("Ours")));
    handText(*core, group, syncText(profileSelf, now, QStringLiteral("Ours too")));
    handText(*core, group + 1, syncText(maryam, now, QStringLiteral("Nowhere")));
    QCOMPARE(told.size(), size_t{2});
}

/**
 * KHANDAQ (26.11.2026): an offer or an accept of a member to contacts is told only when toxcore
 * names a member who sent it. Ours, one of a member who is not there, of a group that is not
 * there, or one not of 54 bytes is dropped. (A member who sends one needs a second node: the
 * cores here are never started.)
 */
void TestCoreGroupText::aContactPacketOfNoMemberIsDropped()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Contacts"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    QSignalSpy told(core.get(), &Core::groupContactPacketReceived);

    const QByteArray nonce = QByteArray::fromHex("0102030405060708");
    const QByteArray offer = Air::groupContactPacket(Air::GroupContactPacket::Kind::Offer,
                                                     addressOf(QByteArray(ToxPk::size, 'm')), nonce);
    const QByteArray accept = Air::groupContactPacket(
        Air::GroupContactPacket::Kind::Accept, addressOf(QByteArray(ToxPk::size, 'm')), nonce);
    QCOMPARE(offer.size(), 54);

    // Our own number in the group: a packet of ours is never news.
    const uint32_t self = core->getGroupSelfPeerId(group);
    handContact(*core, group, self, offer);
    handContact(*core, group, self, accept);
    // Nobody has these numbers, and no group that one.
    handContact(*core, group, 4242, offer);
    handContact(*core, group, 4242, accept);
    handContact(*core, group + 1, self, offer);
    // Not 54 bytes.
    handContact(*core, group, self, offer.left(53));
    handContact(*core, group, self, offer + '\0');
    QCOMPARE(told.count(), 0);
}

void TestCoreGroupText::aContactPacketGoesOnlyToAMemberOnline()
{
    ToxCorePtr core = makeCore();
    QVERIFY(core);
    const int group = core->createGroup(QStringLiteral("Contacts"), TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(group >= 0);
    const QByteArray nonce = QByteArray::fromHex("0102030405060708");
    using Result = Core::ContactPacketResult;
    // A key nobody has in the group: the member is not there, and nothing goes.
    QCOMPARE(core->sendGroupContactPacket(group, ToxPk(QByteArray(ToxPk::size, 'm')), false, nonce),
             Result::Offline);
    QCOMPARE(core->sendGroupContactPacket(group, ToxPk(QByteArray(ToxPk::size, 'm')), true, nonce),
             Result::Offline);
    // No key, or a nonce that is not 8 bytes, sends nothing either.
    QCOMPARE(core->sendGroupContactPacket(group, ToxPk(), false, nonce), Result::Failed);
    QCOMPARE(core->sendGroupContactPacket(group, core->getGroupSelfPublicKey(group), false,
                                          nonce.left(4)),
             Result::Failed);
}

QTEST_GUILESS_MAIN(TestCoreGroupText)
#include "coregrouptext_test.moc"
