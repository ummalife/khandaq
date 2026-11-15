/*
    Copyright © 2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/

#include "mock/mockbootstraplistgenerator.h"
#include "src/core/core.h"
#include "src/core/toxoptions.h"
#include "src/core/icoresettings.h"
#include "src/net/bootstrapnodeupdater.h"
#include "src/model/ibootstraplistgenerator.h"
#include "src/persistence/settings.h"
#include "mock/mockcoresettings.h"
#include "src/core/khandaqlimits.h"
#include "src/model/groupinvite.h"

#include <QTemporaryDir>
#include <QtTest/QtTest>
#include <QtGlobal>
#include <limits>
#include <QMutexLocker>
#include <QSignalSpy>

#include <iostream>
#include <memory>

Q_DECLARE_METATYPE(QList<DhtServer>)
Q_DECLARE_METATYPE(ToxPk)
Q_DECLARE_METATYPE(uint32_t)
Q_DECLARE_METATYPE(Status::Status)

class TestCore : public QObject
{
Q_OBJECT
public:
    TestCore()
    {
        qRegisterMetaType<ToxPk>("ToxPk");
        qRegisterMetaType<uint32_t>("uint32_t");
        qRegisterMetaType<Status::Status>("Status::Status");
    }

private slots:
    void startup_with_invalid_socks5_proxy();
    void startup_with_invalid_http_proxy();
    void bootstrap();
    void make_friends();
    void change_name();
    void change_status_message();
    void change_status();
    void change_nospam();
    void msgv3_receipt_is_not_a_message();
    void group_file_waits_for_a_member_in_the_core_loop();
    void an_invite_from_a_friend_online_replaces_a_group_joined_by_its_id();

private:
    /* Test Variables */
    Core::ToxCoreErrors* err = nullptr;
    MockSettings settings;
    ToxCorePtr alice;
    ToxCorePtr bob;
    MockBootstrapListGenerator alicesNodes{};
    MockBootstrapListGenerator bobsNodes{};
};

namespace {
const int bootstrap_timeout = 15000;
const int connected_message_wait = 5000;

void bootstrapToxes(Core& alice, MockBootstrapListGenerator& alicesNodes,
    Core& bob, MockBootstrapListGenerator& bobsNodes)
{
    alicesNodes.setBootstrapNodes(MockBootstrapListGenerator::makeListFromSelf(bob));
    bobsNodes.setBootstrapNodes(MockBootstrapListGenerator::makeListFromSelf(alice));

    QSignalSpy spyAlice(&alice, &Core::connected);
    QSignalSpy spyBob(&bob, &Core::connected);

    alice.start();
    bob.start();

    QTRY_VERIFY_WITH_TIMEOUT(spyAlice.count() == 1 &&
        spyBob.count() == 1, bootstrap_timeout);
}
} // namespace

void TestCore::startup_with_invalid_socks5_proxy()
{
    settings.setProxyAddr("Test");
    settings.setProxyPort(9985);
    settings.setProxyType(MockSettings::ProxyType::ptSOCKS5);

    alice = Core::makeToxCore({}, settings, alicesNodes, err);

    QVERIFY(!alice);
}

void TestCore::startup_with_invalid_http_proxy()
{
    settings.setProxyAddr("Test");
    settings.setProxyPort(9985);
    settings.setProxyType(MockSettings::ProxyType::ptHTTP);

    alice = Core::makeToxCore({}, settings, alicesNodes, err);

    QVERIFY(!alice);
}

void TestCore::bootstrap()
{
    // No proxy
    settings.setProxyAddr("");
    settings.setProxyPort(0);
    settings.setProxyType(MockSettings::ProxyType::ptNone);

    alice = Core::makeToxCore({}, settings, alicesNodes, err);
    bob = Core::makeToxCore({}, settings, bobsNodes, err);
    QVERIFY(!!alice);
    QVERIFY(!!bob);

    bootstrapToxes(*alice, alicesNodes, *bob, bobsNodes);
}

void TestCore::make_friends()
{
    // Make a friend request from alice to bob. The core accepts nothing by itself: the request and
    // its message reach the interface, which shows them until they are answered.
    const QLatin1String friendMsg{"Test Invite Message"};

    QSignalSpy spyBobFriendMsg(bob.get(), &Core::friendRequestReceived);
    QSignalSpy spyBobFriendAdded(bob.get(), &Core::friendAdded);
    QSignalSpy spyAliceFriendMsg(alice.get(), &Core::requestSent);
    QSignalSpy spyAliceFriendOnline(alice.get(), &Core::friendStatusChanged);
    QSignalSpy spyBobFriendOnline(bob.get(), &Core::friendStatusChanged);
    alice->requestFriendship(bob->getSelfId(), friendMsg);

    // Wait for the request to arrive. A request goes through the onion, which may still be
    // building its paths right after bootstrap, so it can take longer than a message.
    QTRY_VERIFY_WITH_TIMEOUT(spyBobFriendMsg.count() == 1 && spyAliceFriendMsg.count() == 1,
                             bootstrap_timeout);

    // Check for expected signal content
    QVERIFY(qvariant_cast<ToxPk>(spyBobFriendMsg[0][0]) == alice->getSelfPublicKey());
    QVERIFY(spyBobFriendMsg[0][1].toString() == friendMsg);
    QVERIFY(spyBobFriendAdded.isEmpty());

    QVERIFY(qvariant_cast<ToxPk>(spyAliceFriendMsg[0][0]) == bob->getSelfPublicKey());
    QVERIFY(spyAliceFriendMsg[0][1].toString() == friendMsg);

    // Let Bob accept the friend request from Alice
    bob->acceptFriendRequest(alice->getSelfPublicKey());
    QCOMPARE(spyBobFriendAdded.count(), 1);
    QVERIFY(qvariant_cast<ToxPk>(spyBobFriendAdded[0][1]) == alice->getSelfPublicKey());

    // Wait until both see each other. They find each other through the onion as the request did,
    // which takes as long on a busy machine.
    QTRY_VERIFY_WITH_TIMEOUT(spyAliceFriendOnline.count() >= 1 && spyBobFriendOnline.count() >= 1,
                             bootstrap_timeout);

    // Check for expected signal content
    QVERIFY(spyAliceFriendOnline[0][0].toInt() == static_cast<int>(Status::Status::Online));
    QVERIFY(spyBobFriendOnline[0][0].toInt() == static_cast<int>(Status::Status::Online));
}

void TestCore::change_name()
{
    // Change the name of Alice to "Alice"
    const QLatin1String aliceName{"Alice"};

    QSignalSpy aliceSaveRequest(alice.get(), &Core::saveRequest);
    QSignalSpy aliceUsernameChanged(alice.get(), &Core::usernameSet);
    QSignalSpy bobUsernameChangeReceived(bob.get(), &Core::friendUsernameChanged);

    alice->setUsername(aliceName);

    QTRY_VERIFY_WITH_TIMEOUT(aliceSaveRequest.count() == 1, bootstrap_timeout);
    QTRY_VERIFY_WITH_TIMEOUT(aliceUsernameChanged.count() == 1 &&
                             aliceUsernameChanged[0][0].toString() == aliceName,
                             bootstrap_timeout);

    QTRY_VERIFY_WITH_TIMEOUT(bobUsernameChangeReceived.count() == 1 &&
                             bobUsernameChangeReceived[0][1].toString() == aliceName,
                             bootstrap_timeout);

    // Setting the username again to the same value shoud NOT trigger any signals
    alice->setUsername(aliceName);

    // Need to sleep here, because we're testing that these don't increase based on
    // Alice re-setting her username, so QTRY_VERIFY_WITH_TIMEOUT would return immediately.
    QTest::qSleep(connected_message_wait);
    QVERIFY(aliceSaveRequest.count() == 1);
    QVERIFY(aliceUsernameChanged.count() == 1);
    QVERIFY(bobUsernameChangeReceived.count() == 1);
}

void TestCore::change_status_message()
{
    // Change the status message of Alice
    const QLatin1String aliceStatusMsg{"Testing a lot"};

    QSignalSpy aliceSaveRequest(alice.get(), &Core::saveRequest);
    QSignalSpy aliceStatusMsgChanged(alice.get(), &Core::statusMessageSet);
    QSignalSpy bobStatusMsgChangeReceived(bob.get(), &Core::friendStatusMessageChanged);

    alice->setStatusMessage(aliceStatusMsg);

    QTRY_VERIFY_WITH_TIMEOUT(aliceSaveRequest.count() == 1, connected_message_wait);
    QTRY_VERIFY_WITH_TIMEOUT(aliceStatusMsgChanged.count() == 1 &&
                             aliceStatusMsgChanged[0][0].toString() == aliceStatusMsg,
                             connected_message_wait);

    QTRY_VERIFY_WITH_TIMEOUT(bobStatusMsgChangeReceived.count() == 1 &&
                             bobStatusMsgChangeReceived[0][1].toString() == aliceStatusMsg,
                             connected_message_wait);

    // Setting the status message again to the same value shoud NOT trigger any signals
    alice->setStatusMessage(aliceStatusMsg);

    // Need to sleep here, because we're testing that these don't increase based on
    // Alice re-setting her status message, so QTRY_VERIFY_WITH_TIMEOUT would return immediately.
    QTest::qSleep(connected_message_wait);
    QVERIFY(aliceSaveRequest.count() == 1);
    QVERIFY(aliceStatusMsgChanged.count() == 1);
    QVERIFY(bobStatusMsgChangeReceived.count() == 1);
}

void TestCore::change_status()
{
    QSignalSpy aliceSaveRequest(alice.get(), &Core::saveRequest);
    QSignalSpy aliceStatusChanged(alice.get(), &Core::statusSet);
    QSignalSpy bobStatusChangeReceived(bob.get(), &Core::friendStatusChanged);

    alice->setStatus(Status::Status::Away);

    QTRY_VERIFY_WITH_TIMEOUT(aliceSaveRequest.count() == 1, connected_message_wait);
    QTRY_VERIFY_WITH_TIMEOUT(aliceStatusChanged.count() == 1 &&
                             qvariant_cast<Status::Status>(aliceStatusChanged[0][0]) == Status::Status::Away,
                             connected_message_wait);

    QTRY_VERIFY_WITH_TIMEOUT(bobStatusChangeReceived.count() == 1 &&
                             qvariant_cast<Status::Status>(bobStatusChangeReceived[0][1]) == Status::Status::Away,
                             connected_message_wait);

    // Setting the status message again to the same value again triggers all signals
    alice->setStatus(Status::Status::Away);

    // TODO(sudden6): Emitting these again odd and should probably be changed, lets codify it for now though
    QTRY_VERIFY_WITH_TIMEOUT(aliceSaveRequest.count() == 2, connected_message_wait);
    QTRY_VERIFY_WITH_TIMEOUT(aliceStatusChanged.count() == 2 &&
                             qvariant_cast<Status::Status>(aliceStatusChanged[1][0]) == Status::Status::Away,
                             connected_message_wait);

    // Need to sleep here, because we're testing that we don't get a new signal for the re-set but
    // unchanged status, so QTRY_VERIFY_WITH_TIMEOUT would return immediately.
    QTest::qSleep(connected_message_wait);
    QVERIFY(bobStatusChangeReceived.count() == 1);
}

void TestCore::change_nospam()
{
    // A new antispam (Change antispam in You): a new ID, the same key, and saved at once.
    const ToxId before = alice->getSelfId();
    // The spy can keep the ID the signal carries once the type is known, as Nexus makes it.
    qRegisterMetaType<ToxId>("ToxId");
    QSignalSpy aliceSaveRequest(alice.get(), &Core::saveRequest);
    QSignalSpy aliceIdSet(alice.get(), &Core::idSet);

    alice->setNospam(0x12345678);

    QCOMPARE(aliceSaveRequest.count(), 1);
    QCOMPARE(aliceIdSet.count(), 1);
    QCOMPARE(alice->getSelfId().getNoSpamString(), QStringLiteral("12345678"));
    QVERIFY(alice->getSelfId().getPublicKey() == before.getPublicKey());
    // ToxId compares the keys alone: the whole ID tells the two apart.
    QVERIFY(alice->getSelfId().toString() != before.toString());
}

void TestCore::msgv3_receipt_is_not_a_message()
{
    // KHANDAQ (msgV3 receipt shown as "_"): the iPhone answers a message with a msgV3 receipt, a
    // message of type TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK whose text is "_". Alice sends Bob exactly
    // that, then a normal message. Both travel in order on one connection, so once the normal one
    // has arrived the receipt has been handled too, and it must not have become a message.
#ifdef TOX_MSGV3_MSGID_LENGTH
    const QVector<uint32_t> aliceFriends = alice->getFriendList();
    QCOMPARE(aliceFriends.size(), 1);
    const uint32_t bobOnAlice = aliceFriends.first();
    QVERIFY(alice->getFriendPublicKey(bobOnAlice) == bob->getSelfPublicKey());

    QSignalSpy bobMessages(bob.get(), &Core::friendMessageReceived);

    // Straight through toxcore, as a phone sends it: the desktop itself never sends one. Under the
    // core's own lock, since its thread iterates the same Tox.
    Tox_Err_Friend_Send_Message receiptError = TOX_ERR_FRIEND_SEND_MESSAGE_OK;
    {
        QMutexLocker locker{&alice->getCoreLoopLock()};
        const uint8_t receiptText[] = {'_'};
        tox_friend_send_message(alice->getTox(), bobOnAlice, TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK,
                                receiptText, sizeof(receiptText), &receiptError);
    }
    QCOMPARE(receiptError, TOX_ERR_FRIEND_SEND_MESSAGE_OK);

    const QString after{QStringLiteral("after the receipt")};
    ReceiptNum receipt;
    QVERIFY(alice->sendMessage(bobOnAlice, after, receipt));

    QTRY_VERIFY_WITH_TIMEOUT(!bobMessages.isEmpty(), connected_message_wait);
    QCOMPARE(bobMessages.count(), 1);
    QCOMPARE(bobMessages[0][1].toString(), after);
    QCOMPARE(bobMessages[0][2].toBool(), false);
#else
    // An upstream toxcore has neither the type nor its packet, so there is nothing to receive; it
    // also aborts on an attempt to send one.
    QSKIP("this toxcore has no msgV3 receipts");
#endif
}

void TestCore::group_file_waits_for_a_member_in_the_core_loop()
{
    // 0.2.56: a file of more than one packet goes into a group from the loop of the node; the call
    // that sends it only queues it. Alice is alone in the group: toxcore would take the packets
    // for nobody and say they went, so the loop sends none of them and the file waits.
    const int group = alice->createGroup(QStringLiteral("Files"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
    QVERIFY(group >= 0);
    const qint64 size = 7 * static_cast<qint64>(NGC_CHUNK_PAYLOAD_MAX) + 1234;
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("video.mp4"));
    {
        QFile file(path);
        QVERIFY(file.open(QIODevice::WriteOnly));
        QCOMPARE(file.write(QByteArray(static_cast<int>(size), 'x')), size);
    }

    QSignalSpy started(alice.get(), &Core::groupFileSendStarted);
    QSignalSpy waiting(alice.get(), &Core::groupFileSendWaiting);
    QSignalSpy progress(alice.get(), &Core::groupFileSendProgress);
    QSignalSpy sent(alice.get(), &Core::groupFileSent);
    QSignalSpy failed(alice.get(), &Core::groupFileSendFailed);
    QVERIFY(alice->sendGroupFileFromPath(group, QStringLiteral("video.mp4"), path));
    QCOMPARE(started.count(), 1);
    const QByteArray fileId = started.at(0).at(4).toByteArray();

    QTRY_VERIFY_WITH_TIMEOUT(waiting.count() >= 1, connected_message_wait);
    QCOMPARE(waiting.at(0).at(0).toByteArray(), fileId);
    QCOMPARE(waiting.at(0).at(1).toBool(), true);
    // Turns of the loop go by, and nothing of the file with them.
    QTest::qWait(1000);
    QCOMPARE(waiting.count(), 1);
    QCOMPARE(progress.count(), 0);
    QCOMPARE(sent.count(), 0);
    QCOMPARE(failed.count(), 0);
}

void TestCore::an_invite_from_a_friend_online_replaces_a_group_joined_by_its_id()
{
    // 0.2.56: a group joined by its ID that never synced goes for an invitation into it from a
    // friend who is online, and toxcore takes the invitation once it let go of the group. When
    // toxcore refuses it then all the same (here one cut short), the group is joined by its ID
    // again: its row takes the new instance. Nobody hosts the group: it never syncs.
    QByteArray chatId;
    {
        MockBootstrapListGenerator nobodysNodes;
        const ToxCorePtr founder = Core::makeToxCore({}, settings, nobodysNodes, err);
        QVERIFY(founder);
        const int founded =
            founder->createGroup(QStringLiteral("Elsewhere"), TOX_GROUP_PRIVACY_STATE_PUBLIC);
        QVERIFY(founded >= 0);
        chatId = founder->getGroupPersistentId(static_cast<uint32_t>(founded)).getByteArray();
    }
    const QVector<uint32_t> aliceFriends = alice->getFriendList();
    QCOMPARE(aliceFriends.size(), 1);
    const uint32_t bobOnAlice = aliceFriends.first();
    const auto numberOnAlice = [this, &chatId] {
        QMutexLocker locker{&alice->getCoreLoopLock()};
        Tox_Err_Group_State_Queries error;
        const uint32_t number = tox_group_by_chat_id(
            alice->getTox(), reinterpret_cast<const uint8_t*>(chatId.constData()), &error);
        return error == TOX_ERR_GROUP_STATE_QUERIES_OK ? number
                                                       : std::numeric_limits<uint32_t>::max();
    };
    const auto bobOnline = [this, bobOnAlice] {
        QMutexLocker locker{&alice->getCoreLoopLock()};
        return tox_friend_get_connection_status(alice->getTox(), bobOnAlice, nullptr)
               != TOX_CONNECTION_NONE;
    };
    QTRY_VERIFY_WITH_TIMEOUT(bobOnline(), bootstrap_timeout);

    // The loop of the node tells of the deferred accept: what it says is kept under a lock, which
    // the test never holds while it takes the core's. The context goes with the test, and the
    // connections with it.
    QObject context;
    QMutex seenLock;
    QVector<uint32_t> joined;
    QList<QByteArray> failed;
    connect(alice.get(), &Core::groupJoined, &context,
            [&](int number, const GroupId& groupId) {
                const QMutexLocker locker{&seenLock};
                if (groupId.getByteArray() == chatId) {
                    joined.append(static_cast<uint32_t>(number));
                }
            },
            Qt::DirectConnection);
    connect(alice.get(), &Core::groupInviteAcceptFailed, &context,
            [&](const QByteArray& failedId) {
                const QMutexLocker locker{&seenLock};
                failed.append(failedId);
            },
            Qt::DirectConnection);
    const auto joins = [&] {
        const QMutexLocker locker{&seenLock};
        return joined;
    };
    const auto failures = [&] {
        const QMutexLocker locker{&seenLock};
        return failed;
    };

    const uint32_t stuck = alice->joinGroupByChatId(chatId);
    QVERIFY(stuck != std::numeric_limits<uint32_t>::max());
    const QString kept = alice->getUnsyncedGroupJoins();
    QCOMPARE(joins(), QVector<uint32_t>{stuck});

    const GroupInvite cutShort(bobOnAlice, chatId + QByteArray(8, '\0'), QStringLiteral("Else"));
    QCOMPARE(alice->joinGroupchat(cutShort), stuck);
    QTRY_COMPARE_WITH_TIMEOUT(joins().size(), 2, connected_message_wait);
    QCOMPARE(failures(), QList<QByteArray>{chatId});
    const uint32_t rejoined = joins().at(1);
    QCOMPARE(numberOnAlice(), rejoined);
    QCOMPARE(alice->getUnsyncedGroupJoins(), kept);

    // A whole invitation: the group joined again goes for it, and toxcore takes it.
    const GroupInvite invite(bobOnAlice, chatId + QByteArray(32, '\0'), QStringLiteral("Else"));
    QCOMPARE(alice->joinGroupchat(invite), rejoined);
    QTRY_COMPARE_WITH_TIMEOUT(joins().size(), 3, connected_message_wait);
    QCOMPARE(failures().size(), 1);
    QCOMPARE(numberOnAlice(), joins().at(2));

    alice->removeGroup(static_cast<int>(numberOnAlice()));
}

QTEST_GUILESS_MAIN(TestCore)
#include "core_test.moc"
