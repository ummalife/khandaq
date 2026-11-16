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
#include "src/core/corefile.h"
#include "src/model/air/airmedia.h"
#include "src/model/groupinvite.h"

#include <QTemporaryDir>
#include <QtTest/QtTest>
#include <QtGlobal>
#include <limits>
#include <QMutexLocker>
#include <QSignalSpy>

#include <chrono>
#include <iostream>
#include <memory>
#include <random>

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
    void msgv3_text_comes_with_its_words_and_id();
    void read_receipt_reaches_the_friend_as_it_went();
    void group_file_waits_for_a_member_in_the_core_loop();
    void group_file_goes_to_a_member_in_the_core_loop();
    void a_group_being_left_takes_no_text();
    void an_invite_from_a_friend_online_replaces_a_group_joined_by_its_id();
    void a_file_goes_on_from_where_it_stopped_once_the_friend_is_back();
    void an_offer_of_a_file_partly_here_goes_on_from_what_is_here();
    void an_offer_of_a_file_all_here_ends_quietly();
    void a_removed_friend_leaves_no_file_to_whoever_gets_their_number();

private:
    /** Alice makes a group, and Bob joins it by her invite: both count the two of them then. */
    void makeGroupWithBob(const QString& name, int& aliceGroup, uint32_t& bobGroup);

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
/**
 * How long a node that hears nothing more from a friend takes to call them offline
 * (FRIEND_CONNECTION_TIMEOUT of toxcore, 32 s), with room for the link to come back after it.
 */
const int friend_timeout = 50000;
/** What a file sends in a packet at most (MAX_FILE_DATA_SIZE of toxcore). */
const qint64 fileChunkMax = 1371;

/** The clock of the core loop (monotonicMs in core.cpp). */
qint64 steadyMs()
{
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

/** KHANDAQ (0.2.57): what the core thread of a node says of its files and friends, for the test. */
class NodeLog : public QObject
{
public:
    NodeLog(Core& core)
    {
        CoreFile& files = *core.getCoreFile();
        connect(&files, &CoreFile::fileReceiveRequested, this, keep(&offered),
                Qt::DirectConnection);
        connect(&files, &CoreFile::fileSendStarted, this,
                [this](ToxFile file) {
                    const QMutexLocker locker{&lock};
                    started.append(file);
                    lastStartMs = steadyMs();
                },
                Qt::DirectConnection);
        connect(&files, &CoreFile::fileTransferAccepted, this, keep(&accepted),
                Qt::DirectConnection);
        connect(&files, &CoreFile::fileTransferFinished, this, keep(&finished),
                Qt::DirectConnection);
        connect(&files, &CoreFile::fileTransferCancelled, this, keep(&cancelled),
                Qt::DirectConnection);
        connect(&files, &CoreFile::fileTransferBrokenUnbroken, this,
                [this](ToxFile file, bool) {
                    const QMutexLocker locker{&lock};
                    waiting.append(file);
                },
                Qt::DirectConnection);
        connect(&files, &CoreFile::fileTransferInfo, this,
                [this](ToxFile file) {
                    const QMutexLocker locker{&lock};
                    bytes = file.progress.getBytesSent();
                    if (file.continued && firstContinued == 0) {
                        firstContinued = bytes;
                    }
                },
                Qt::DirectConnection);
        connect(&core, &Core::friendLinkChanged, this,
                [this](uint32_t friendId, int link) {
                    const QMutexLocker locker{&lock};
                    if (link != TOX_CONNECTION_NONE
                        && links.value(friendId, TOX_CONNECTION_NONE) == TOX_CONNECTION_NONE) {
                        backMs[friendId] = steadyMs();
                    }
                    links[friendId] = link;
                },
                Qt::DirectConnection);
    }

    QList<ToxFile> of(const QList<ToxFile>& list)
    {
        const QMutexLocker locker{&lock};
        return list;
    }
    qint64 lastBytes()
    {
        const QMutexLocker locker{&lock};
        return static_cast<qint64>(bytes);
    }
    /** The bytes of the first transfer info of a file that went on: where it went on from. */
    qint64 firstContinuedBytes()
    {
        const QMutexLocker locker{&lock};
        return static_cast<qint64>(firstContinued);
    }
    bool online(uint32_t friendId)
    {
        const QMutexLocker locker{&lock};
        return links.value(friendId, TOX_CONNECTION_NONE) != TOX_CONNECTION_NONE;
    }
    /** From the moment the link to friendId last came back to the last file this node sent. */
    qint64 msFromBackToLastStart(uint32_t friendId)
    {
        const QMutexLocker locker{&lock};
        return lastStartMs - backMs.value(friendId, lastStartMs);
    }

    QList<ToxFile> offered;
    QList<ToxFile> started;
    QList<ToxFile> accepted;
    QList<ToxFile> finished;
    QList<ToxFile> cancelled;
    QList<ToxFile> waiting;

private:
    std::function<void(ToxFile)> keep(QList<ToxFile>* list)
    {
        return [this, list](ToxFile file) {
            const QMutexLocker locker{&lock};
            list->append(file);
        };
    }

    QMutex lock;
    uint64_t bytes = 0;
    uint64_t firstContinued = 0;
    QHash<uint32_t, int> links;
    QHash<uint32_t, qint64> backMs;
    qint64 lastStartMs = 0;
};

/** A file of size bytes that no shift of itself matches, so a part from a wrong place shows. */
QByteArray fileBytes(int size, unsigned seed)
{
    std::mt19937 random(seed);
    QByteArray bytes(size, Qt::Uninitialized);
    for (int i = 0; i < size; ++i) {
        bytes[i] = static_cast<char>(random() & 0xff);
    }
    return bytes;
}

bool writeFile(const QString& path, const QByteArray& bytes)
{
    QFile file(path);
    return file.open(QIODevice::WriteOnly) && file.write(bytes) == bytes.size();
}

QByteArray readFile(const QString& path)
{
    QFile file(path);
    return file.open(QIODevice::ReadOnly) ? file.readAll() : QByteArray();
}

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
    QVERIFY(alice->sendMessage(bobOnAlice, after, QByteArray(), 0, receipt));

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

void TestCore::msgv3_text_comes_with_its_words_and_id()
{
    // KHANDAQ (0.2.57): a text goes as msgV3, its id and time after the words; the friend reads the
    // words without the trailer, and the id. Bob confirms it with a msgV3 receipt of his own
    // ("_" and the id, Core::sendHighLevelAck), which Alice must not show as a message: Bob's next
    // text travels after it on the same connection. Toxcore's own receipt of that msgV3 receipt
    // stops in Bob's core: no message of his has its number.
#ifdef TOX_MSGV3_MSGID_LENGTH
    const uint32_t bobOnAlice = alice->getFriendList().first();
    const uint32_t aliceOnBob = bob->getFriendList().first();
    QSignalSpy bobMessages(bob.get(), &Core::friendMessageReceived);
    QSignalSpy aliceMessages(alice.get(), &Core::friendMessageReceived);
    QSignalSpy bobReceipts(bob.get(), &Core::receiptRecieved);

    QByteArray id(32, '\0');
    for (int i = 0; i < id.size(); ++i) {
        id[i] = static_cast<char>(0xA0 + i);
    }
    const QString words{QStringLiteral("Ассаламу алейкум 🙂")};
    ReceiptNum receipt;
    QVERIFY(alice->sendMessage(bobOnAlice, words, id, 1700000000, receipt));
    QTRY_VERIFY_WITH_TIMEOUT(!bobMessages.isEmpty(), connected_message_wait);
    QCOMPARE(bobMessages.count(), 1);
    QCOMPARE(bobMessages[0][1].toString(), words);
    QCOMPARE(bobMessages[0][2].toBool(), false);
    QCOMPARE(bobMessages[0][3].toByteArray(), id);

    // A text without an id arrives without one.
    const QString reply{QStringLiteral("plain reply")};
    QVERIFY(bob->sendMessage(aliceOnBob, reply, QByteArray(), 0, receipt));
    QTRY_VERIFY_WITH_TIMEOUT(!aliceMessages.isEmpty(), connected_message_wait);
    QCOMPARE(aliceMessages.count(), 1);
    QCOMPARE(aliceMessages[0][1].toString(), reply);
    QVERIFY(aliceMessages[0][3].toByteArray().isEmpty());

    // The receipt of the msgV3 receipt comes before that of the reply, which went after it.
    QTRY_VERIFY_WITH_TIMEOUT(!bobReceipts.isEmpty(), connected_message_wait);
    QCOMPARE(bobReceipts.count(), 1);
    QCOMPARE(bobReceipts[0][1].value<ReceiptNum>(), receipt);
#else
    QSKIP("this toxcore has no msgV3");
#endif
}

void TestCore::read_receipt_reaches_the_friend_as_it_went()
{
    // KHANDAQ (0.2.57): the lossless packet 189 reaches the friend byte for byte, before toxext
    // could take it; one of another shape too, for the receiver to drop.
    const uint32_t bobOnAlice = alice->getFriendList().first();
    const uint32_t aliceOnBob = bob->getFriendList().first();
    QSignalSpy bobSeen(bob.get(), &Core::friendSeenReceived);

    QByteArray packet = QByteArray::fromHex("BD4B510101");
    packet.append(QByteArray(32, '\x42'));
    packet.append(QByteArray::fromHex("6553F100"));
    QCOMPARE(packet.size(), 41);
    QVERIFY(alice->sendSeenReceipt(bobOnAlice, packet));
    QVERIFY(alice->sendSeenReceipt(bobOnAlice, packet.left(40)));

    QTRY_COMPARE_WITH_TIMEOUT(bobSeen.count(), 2, connected_message_wait);
    QCOMPARE(bobSeen[0][0].value<uint32_t>(), aliceOnBob);
    QCOMPARE(bobSeen[0][1].toByteArray(), packet);
    QCOMPARE(bobSeen[1][1].toByteArray(), packet.left(40));

    // Nobody to send to: refused, not thrown.
    QVERIFY(!alice->sendSeenReceipt(bobOnAlice + 100, packet));
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

void TestCore::makeGroupWithBob(const QString& name, int& aliceGroup, uint32_t& bobGroup)
{
    const QVector<uint32_t> aliceFriends = alice->getFriendList();
    QCOMPARE(aliceFriends.size(), 1);
    aliceGroup = alice->createGroup(name, TOX_GROUP_PRIVACY_STATE_PRIVATE);
    QVERIFY(aliceGroup >= 0);

    // The loop of Bob's node tells of the invitation. The context goes before what the
    // connection writes to, and the connection with it.
    QMutex inviteLock;
    QList<GroupInvite> invites;
    QObject context;
    connect(bob.get(), &Core::groupInviteReceived, &context,
            [&](const GroupInvite& invite) {
                const QMutexLocker locker{&inviteLock};
                invites.append(invite);
            },
            Qt::DirectConnection);
    const auto received = [&] {
        const QMutexLocker locker{&inviteLock};
        return invites;
    };
    QVERIFY(alice->groupInviteFriend(aliceFriends.first(), aliceGroup));
    QTRY_COMPARE_WITH_TIMEOUT(received().size(), 1, bootstrap_timeout);
    bobGroup = bob->joinGroupchat(received().first());
    QVERIFY(bobGroup != std::numeric_limits<uint32_t>::max());
    QTRY_VERIFY_WITH_TIMEOUT(alice->getGroupNumberPeers(aliceGroup) == 2
                                 && bob->getGroupNumberPeers(static_cast<int>(bobGroup)) == 2,
                             bootstrap_timeout);
}

void TestCore::group_file_goes_to_a_member_in_the_core_loop()
{
    // 0.2.56: with Bob in the group, the loop of the node sends the BEGIN and then every chunk of a
    // file of more than one packet (Core::sendGroupFileChunks), at their pace: the call that sends
    // it only queues it. What Bob gets is not checked here: the ids of the files a node saw are
    // kept for the whole process (ngcSeenFileMsgIds in core.cpp), so Bob, in the process of Alice,
    // takes her file for one he has.
    int group = -1;
    uint32_t bobsGroup = std::numeric_limits<uint32_t>::max();
    makeGroupWithBob(QStringLiteral("Files for Bob"), group, bobsGroup);
    if (QTest::currentTestFailed()) {
        return;
    }

    const qint64 size = 7 * static_cast<qint64>(NGC_CHUNK_PAYLOAD_MAX) + 1234;
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("photos.zip"));
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
    QVERIFY(alice->sendGroupFileFromPath(group, QStringLiteral("photos.zip"), path));
    QCOMPARE(started.count(), 1);
    const QByteArray fileId = started.at(0).at(4).toByteArray();
    QCOMPARE(sent.count(), 0);

    // Its last chunk went to toxcore from the loop, the bar moved on the way, and nothing waited.
    QTRY_COMPARE_WITH_TIMEOUT(sent.count(), 1, connected_message_wait);
    QCOMPARE(sent.at(0).at(0).toInt(), group);
    QCOMPARE(sent.at(0).at(3).toLongLong(), size);
    QCOMPARE(sent.at(0).at(4).toByteArray(), fileId);
    QVERIFY(progress.count() >= 1);
    QCOMPARE(progress.at(0).at(0).toByteArray(), fileId);
    QVERIFY(progress.last().at(1).toLongLong() < size);
    QCOMPARE(waiting.count(), 0);
    QCOMPARE(failed.count(), 0);
}

void TestCore::a_group_being_left_takes_no_text()
{
    // 0.2.56: Bob leaves a group of the two of them. toxcore keeps the group a while on both
    // sides: Bob's until his notice had its time (Air::PendingGroupLeaves::delayMs), and Bob in
    // Alice's until his exit arrives. Neither side takes a text meanwhile, though toxcore still
    // counts the two: it would reach nobody.
    int group = -1;
    uint32_t bobsGroup = std::numeric_limits<uint32_t>::max();
    makeGroupWithBob(QStringLiteral("Leaving"), group, bobsGroup);
    if (QTest::currentTestFailed()) {
        return;
    }
    const auto toxPeers = [](Core& core, uint32_t number) {
        QMutexLocker locker{&core.getCoreLoopLock()};
        return tox_group_peer_count(core.getTox(), number, nullptr);
    };

    bob->removeGroup(static_cast<int>(bobsGroup));
    QCOMPARE(toxPeers(*bob, bobsGroup), uint32_t{2});
    uint32_t wireId = 1;
    QCOMPARE(bob->sendGroupMessage(static_cast<int>(bobsGroup), QStringLiteral("Bye"), wireId),
             Core::SendResult::NotReady);
    QCOMPARE(wireId, uint32_t{0});

    // Bob is gone for Alice once his notice arrives, while her toxcore still has him.
    QTRY_COMPARE_WITH_TIMEOUT(alice->getGroupNumberPeers(group), uint32_t{1},
                              connected_message_wait);
    QCOMPARE(toxPeers(*alice, static_cast<uint32_t>(group)), uint32_t{2});
    QCOMPARE(alice->sendGroupMessage(group, QStringLiteral("Anyone?"), wireId),
             Core::SendResult::NotReady);
    QCOMPARE(wireId, uint32_t{0});
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
    // the test never holds while it takes the core's. The context goes with the test, before what
    // the connections write to, and the connections with it.
    QMutex seenLock;
    QVector<uint32_t> joined;
    QList<QByteArray> failed;
    QObject context;
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

/**
 * KHANDAQ (0.2.57): the link between Alice and Bob goes in the middle of a file of Alice, as when
 * the network goes, and comes back. Alice's node stops for longer than toxcore waits for a friend,
 * so both call the other offline and forget the transfer. Both keep it waiting; once they are
 * linked again Alice offers it with the same Tox file id by herself, a moment later (an older
 * receiver would forget an offer that came with the link), and Bob's node goes on with it from what
 * came, asking his window nothing. The file ends whole, byte for byte.
 */
void TestCore::a_file_goes_on_from_where_it_stopped_once_the_friend_is_back()
{
    const QVector<uint32_t> aliceFriends = alice->getFriendList();
    QCOMPARE(aliceFriends.size(), 1);
    const uint32_t bobOnAlice = aliceFriends.first();
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QByteArray content = fileBytes(6 * 1024 * 1024, 57);
    const QString source = dir.filePath(QStringLiteral("film.mkv"));
    const QString target = dir.filePath(QStringLiteral("film here.mkv"));
    QVERIFY(writeFile(source, content));

    NodeLog aliceLog(*alice);
    NodeLog bobLog(*bob);
    alice->getCoreFile()->sendFile(bobOnAlice, QStringLiteral("film.mkv"), source, content.size());
    QTRY_COMPARE_WITH_TIMEOUT(bobLog.of(bobLog.offered).size(), 1, connected_message_wait);
    const ToxFile offer = bobLog.of(bobLog.offered).first();
    QCOMPARE(offer.resumeFileId.size(), TOX_FILE_ID_LENGTH);
    bob->getCoreFile()->acceptFileRecvRequest(offer.friendId, offer.fileNum, target);
    QTRY_VERIFY_WITH_TIMEOUT(bobLog.lastBytes() >= 256 * 1024, connected_message_wait);

    qint64 here = 0;
    {
        // Alice's node stops: nothing of hers reaches Bob, as without a network. His node calls
        // her offline and keeps the file waiting at what came.
        const QMutexLocker stopped{&alice->getCoreLoopLock()};
        QTRY_COMPARE_WITH_TIMEOUT(bobLog.of(bobLog.waiting).size(), 1, friend_timeout);
        const ToxFile waiting = bobLog.of(bobLog.waiting).first();
        QVERIFY(waiting.connectionLost);
        QCOMPARE(waiting.status, ToxFile::TRANSMITTING);
        QCOMPARE(waiting.resumeFileId, offer.resumeFileId);
        QCOMPARE(waiting.filePath, target);
        QVERIFY(waiting.fileNum >= 0x80000000u);
        here = static_cast<qint64>(waiting.progress.getBytesSent());
        QVERIFY(here > 0 && here < content.size());
        QCOMPARE(QFileInfo(target).size(), here);
    }

    // Her node goes on: it learns Bob went, keeps the file waiting too, and once they are linked
    // again offers it with its id; it goes on by itself where Bob's part ends.
    QTRY_VERIFY_WITH_TIMEOUT(bobLog.of(bobLog.finished).size() == 1
                                 && aliceLog.of(aliceLog.finished).size() == 1,
                             friend_timeout);
    QCOMPARE(aliceLog.of(aliceLog.waiting).size(), 1);
    QVERIFY(aliceLog.of(aliceLog.waiting).first().connectionLost);
    QCOMPARE(aliceLog.of(aliceLog.cancelled).size(), 0);
    QCOMPARE(aliceLog.of(aliceLog.started).size(), 2);
    QVERIFY(aliceLog.of(aliceLog.started).last().continued);
    QCOMPARE(aliceLog.of(aliceLog.started).last().resumeFileId, offer.resumeFileId);
    // Not with the link: the status that comes with it is through on the other side first.
    QVERIFY(aliceLog.msFromBackToLastStart(bobOnAlice) >= CoreFile::offerAgainAfterMs - 5);
    QCOMPARE(bobLog.of(bobLog.offered).size(), 1);
    QVERIFY(bobLog.of(bobLog.accepted).last().continued);
    QVERIFY(bobLog.of(bobLog.finished).first().continued);
    QVERIFY(aliceLog.firstContinuedBytes() > here);
    QVERIFY(aliceLog.firstContinuedBytes() <= here + fileChunkMax);
    QCOMPARE(QFileInfo(target).size(), static_cast<qint64>(content.size()));
    QVERIFY(readFile(target) == content);
}

/**
 * KHANDAQ (0.2.57): an offer of a file that is partly here already, after a restart of either side
 * or Send again: the same Tox file id as the entry of it. Bob, as his window does for that entry
 * (Air::fileResume), seeks to what he has before he accepts; Alice reads on from there, and the
 * file ends whole, byte for byte.
 */
void TestCore::an_offer_of_a_file_partly_here_goes_on_from_what_is_here()
{
    const uint32_t bobOnAlice = alice->getFriendList().first();
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QByteArray content = fileBytes(2 * 1024 * 1024 + 333, 1116);
    const QString source = dir.filePath(QStringLiteral("album.zip"));
    const QString target = dir.filePath(QStringLiteral("album here.zip"));
    QVERIFY(writeFile(source, content));
    // What came before: not a whole number of packets.
    const qint64 here = 700001;
    QVERIFY(writeFile(target, content.left(static_cast<int>(here))));
    const QByteArray fileId(TOX_FILE_ID_LENGTH, '\x5a');

    NodeLog aliceLog(*alice);
    NodeLog bobLog(*bob);
    alice->getCoreFile()->sendFile(bobOnAlice, QStringLiteral("album.zip"), source, content.size(),
                                   fileId);
    QTRY_COMPARE_WITH_TIMEOUT(bobLog.of(bobLog.offered).size(), 1, connected_message_wait);
    const ToxFile offer = bobLog.of(bobLog.offered).first();
    QCOMPARE(offer.resumeFileId, fileId);
    QVERIFY(aliceLog.of(aliceLog.started).first().continued);

    Air::FileHere entry;
    entry.id = fileId;
    entry.size = content.size();
    entry.status = ToxFile::BROKEN;
    entry.accepted = true;
    entry.bytes = QFileInfo(target).size();
    const Air::FileResume resume = Air::fileResume(
        offer.resumeFileId, static_cast<qint64>(offer.progress.getFileSize()), entry);
    QCOMPARE(resume.step, Air::FileResume::Step::GoOn);
    QCOMPARE(resume.offset, here);
    bob->getCoreFile()->resumeFileRecv(offer.friendId, offer.fileNum, target,
                                       static_cast<uint64_t>(resume.offset));
    QTRY_VERIFY_WITH_TIMEOUT(bobLog.of(bobLog.finished).size() == 1
                                 && aliceLog.of(aliceLog.finished).size() == 1,
                             bootstrap_timeout);

    QVERIFY(aliceLog.firstContinuedBytes() > here);
    QVERIFY(aliceLog.firstContinuedBytes() <= here + fileChunkMax);
    QVERIFY(bobLog.firstContinuedBytes() > here);
    QVERIFY(bobLog.of(bobLog.finished).first().continued);
    QVERIFY(readFile(target) == content);
}

/**
 * KHANDAQ (0.2.57): an offer of a file that is all here already: the connection went before its
 * sender learnt it was done. Bob seeks to its last byte, so both sides end as usual, and nothing on
 * his side changes or says anything: no entry, no notification, no second copy.
 */
void TestCore::an_offer_of_a_file_all_here_ends_quietly()
{
    const uint32_t bobOnAlice = alice->getFriendList().first();
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QByteArray content = fileBytes(300 * 1024 + 7, 2026);
    const QString source = dir.filePath(QStringLiteral("plan.pdf"));
    const QString target = dir.filePath(QStringLiteral("plan here.pdf"));
    QVERIFY(writeFile(source, content));
    QVERIFY(writeFile(target, content));
    const QByteArray fileId(TOX_FILE_ID_LENGTH, '\x6b');

    NodeLog aliceLog(*alice);
    NodeLog bobLog(*bob);
    alice->getCoreFile()->sendFile(bobOnAlice, QStringLiteral("plan.pdf"), source, content.size(),
                                   fileId);
    QTRY_COMPARE_WITH_TIMEOUT(bobLog.of(bobLog.offered).size(), 1, connected_message_wait);
    const ToxFile offer = bobLog.of(bobLog.offered).first();

    Air::FileHere entry;
    entry.id = fileId;
    entry.size = content.size();
    entry.status = ToxFile::FINISHED;
    entry.accepted = true;
    entry.bytes = content.size();
    const Air::FileResume resume = Air::fileResume(
        offer.resumeFileId, static_cast<qint64>(offer.progress.getFileSize()), entry);
    QCOMPARE(resume.step, Air::FileResume::Step::EndQuietly);
    QCOMPARE(resume.offset, static_cast<qint64>(content.size() - 1));
    bob->getCoreFile()->finishFileRecvQuietly(offer.friendId, offer.fileNum,
                                              static_cast<uint64_t>(resume.offset));
    QTRY_COMPARE_WITH_TIMEOUT(aliceLog.of(aliceLog.finished).size(), 1, bootstrap_timeout);

    // Its end reaches Bob's node too; his side says nothing of it.
    QTest::qWait(500);
    QCOMPARE(bobLog.of(bobLog.finished).size(), 0);
    QCOMPARE(bobLog.of(bobLog.cancelled).size(), 0);
    QCOMPARE(bobLog.of(bobLog.accepted).size(), 0);
    QCOMPARE(bobLog.lastBytes(), qint64{0});
    QVERIFY(readFile(target) == content);
}

/**
 * KHANDAQ (0.2.57): a removed friend takes their files with them. Alice offers Bob a talk and Bob
 * offers her a photo; she removes him, so his node learns at once that she went and keeps both
 * waiting for her, and then he removes her too. Added again, each gets the number the other had.
 * Nothing of before is offered then, and a talk of Alice's with the id of the first is a new offer
 * to Bob, not one his node goes on with.
 */
void TestCore::a_removed_friend_leaves_no_file_to_whoever_gets_their_number()
{
    const uint32_t bobOnAlice = alice->getFriendList().first();
    const uint32_t aliceOnBob = bob->getFriendList().first();
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QByteArray talk = fileBytes(512 * 1024, 1414);
    const QByteArray photo = fileBytes(64 * 1024, 1415);
    const QString talkPath = dir.filePath(QStringLiteral("talk.mkv"));
    const QString photoPath = dir.filePath(QStringLiteral("garden.jpg"));
    QVERIFY(writeFile(talkPath, talk));
    QVERIFY(writeFile(photoPath, photo));
    const auto linked = [](Core& core, uint32_t friendId) {
        QMutexLocker locker{&core.getCoreLoopLock()};
        return tox_friend_get_connection_status(core.getTox(), friendId, nullptr)
               != TOX_CONNECTION_NONE;
    };

    NodeLog aliceLog(*alice);
    NodeLog bobLog(*bob);
    alice->getCoreFile()->sendFile(bobOnAlice, QStringLiteral("talk.mkv"), talkPath, talk.size());
    bob->getCoreFile()->sendFile(aliceOnBob, QStringLiteral("garden.jpg"), photoPath, photo.size());
    QTRY_VERIFY_WITH_TIMEOUT(bobLog.of(bobLog.offered).size() == 1
                                 && aliceLog.of(aliceLog.offered).size() == 1,
                             connected_message_wait);
    const QByteArray talkId = bobLog.of(bobLog.offered).first().resumeFileId;

    alice->removeFriend(bobOnAlice);
    QTRY_COMPARE_WITH_TIMEOUT(bobLog.of(bobLog.waiting).size(), 2, friend_timeout);
    for (const ToxFile& waiting : bobLog.of(bobLog.waiting)) {
        QVERIFY(waiting.connectionLost);
    }
    bob->removeFriend(aliceOnBob);

    bob->acceptFriendRequest(alice->getSelfPublicKey());
    alice->acceptFriendRequest(bob->getSelfPublicKey());
    QCOMPARE(alice->getFriendList(), QVector<uint32_t>{bobOnAlice});
    QCOMPARE(bob->getFriendList(), QVector<uint32_t>{aliceOnBob});
    QTRY_VERIFY_WITH_TIMEOUT(linked(*alice, bobOnAlice) && linked(*bob, aliceOnBob),
                             bootstrap_timeout);

    // Past the moment files that waited are offered again: Bob's photo goes to nobody.
    QTest::qWait(CoreFile::offerAgainAfterMs + 3000);
    QCOMPARE(bobLog.of(bobLog.started).size(), 1);
    QCOMPARE(aliceLog.of(aliceLog.offered).size(), 1);

    alice->getCoreFile()->sendFile(bobOnAlice, QStringLiteral("talk.mkv"), talkPath, talk.size(),
                                   talkId);
    QTRY_COMPARE_WITH_TIMEOUT(bobLog.of(bobLog.offered).size(), 2, connected_message_wait);
    const ToxFile again = bobLog.of(bobLog.offered).last();
    QCOMPARE(again.resumeFileId, talkId);
    QVERIFY(!again.continued);
    QCOMPARE(bobLog.of(bobLog.accepted).size(), 0);
    bob->getCoreFile()->rejectFileRecvRequest(again.friendId, again.fileNum);
    QTRY_COMPARE_WITH_TIMEOUT(aliceLog.of(aliceLog.cancelled).size(), 1, connected_message_wait);
}

QTEST_GUILESS_MAIN(TestCore)
#include "core_test.moc"
