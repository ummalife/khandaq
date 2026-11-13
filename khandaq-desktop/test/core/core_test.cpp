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

#include <QtTest/QtTest>
#include <QtGlobal>
#include <limits>
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

QTEST_GUILESS_MAIN(TestCore)
#include "core_test.moc"
