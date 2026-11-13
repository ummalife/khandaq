
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

#include "src/model/notificationgenerator.h"
#include "src/friendlist.h"

#include "mock/mockcoreidhandler.h"
#include "mock/mockgroupquery.h"

#include <QLocale>
#include <QObject>
#include <QtTest/QtTest>

namespace
{
    class MockNotificationSettings : public INotificationSettings
    {
        virtual bool getNotify() const override { return true; }

        virtual void setNotify(bool newValue) override { std::ignore = newValue; }

        virtual bool getShowWindow() const override { return true; }
        virtual void setShowWindow(bool newValue) override { std::ignore = newValue; }

        virtual bool getDesktopNotify() const override { return true; }
        virtual void setDesktopNotify(bool enabled) override { std::ignore = enabled; }

        virtual bool getNotifySound() const override { return true; }
        virtual void setNotifySound(bool newValue) override { std::ignore = newValue; }

        virtual bool getNotifyHide() const override { return notifyHide; }
        virtual void setNotifyHide(bool newValue) override { notifyHide = newValue; };

        virtual bool getBusySound() const override { return true; }
        virtual void setBusySound(bool newValue) override { std::ignore = newValue; }

        virtual bool getGroupAlwaysNotify() const override { return true; }
        virtual void setGroupAlwaysNotify(bool newValue) override { std::ignore = newValue; }
    private:
        bool notifyHide = false;
    };

} // namespace

class TestNotificationGenerator : public QObject
{
    Q_OBJECT

private slots:
    void initTestCase();
    void init();
    void testSingleFriendMessage();
    void testMultipleFriendMessages();
    void testNotificationClear();
    void testGroupMessage();
    void testMultipleGroupMessages();
    void testMultipleFriendSourceMessages();
    void testMultipleGroupSourceMessages();
    void testMixedSourceMessages();
    void testFileTransfer();
    void testFileTransferAfterMessage();
    void testGroupInvitation();
    void testGroupInviteUncounted();
    void testFriendRequest();
    void testFriendRequestUncounted();
    void testSimpleFriendMessage();
    void testSimpleFileTransfer();
    void testSimpleGroupMessage();
    void testSimpleFriendRequest();
    void testSimpleGroupInvite();
    void testSimpleMessageToggle();

private:
    std::unique_ptr<INotificationSettings> notificationSettings;
    std::unique_ptr<NotificationGenerator> notificationGenerator;
    std::unique_ptr<MockGroupQuery> groupQuery;
    std::unique_ptr<MockCoreIdHandler> coreIdHandler;
    std::unique_ptr<FriendList> friendList;
};

void TestNotificationGenerator::initTestCase()
{
    // File sizes are written in the interface language; before a language is loaded that is the
    // default locale, fixed here so the numbers do not depend on the machine.
    QLocale::setDefault(QLocale(QLocale::English, QLocale::UnitedStates));
}

void TestNotificationGenerator::init()
{
    friendList.reset(new FriendList());
    notificationSettings.reset(new MockNotificationSettings());
    notificationGenerator.reset(new NotificationGenerator(*notificationSettings, nullptr));
    groupQuery.reset(new MockGroupQuery());
    coreIdHandler.reset(new MockCoreIdHandler());
}

void TestNotificationGenerator::testSingleFriendMessage()
{
    Friend f(0, ToxPk());
    f.setName("friendName");
    auto notificationData = notificationGenerator->friendMessageNotification(&f, "test");
    QVERIFY(notificationData.title == "friendName");
    QVERIFY(notificationData.message == "test");
}

void TestNotificationGenerator::testMultipleFriendMessages()
{
    Friend f(0, ToxPk());
    f.setName("friendName");
    notificationGenerator->friendMessageNotification(&f, "test");
    auto notificationData = notificationGenerator->friendMessageNotification(&f, "test2");
    QVERIFY(notificationData.title == "2 message(s) from friendName");
    QVERIFY(notificationData.message == "test2");

    notificationData = notificationGenerator->friendMessageNotification(&f, "test3");
    QVERIFY(notificationData.title == "3 message(s) from friendName");
    QVERIFY(notificationData.message == "test3");
}

void TestNotificationGenerator::testNotificationClear()
{
    Friend f(0, ToxPk());
    f.setName("friendName");

    notificationGenerator->friendMessageNotification(&f, "test");

    // On notification clear we shouldn't see a notification count from the friend
    notificationGenerator->onNotificationActivated();

    auto notificationData = notificationGenerator->friendMessageNotification(&f, "test2");
    QVERIFY(notificationData.title == "friendName");
    QVERIFY(notificationData.message == "test2");
}

void TestNotificationGenerator::testGroupMessage()
{
    Group g(0, GroupId(0), "groupName", false, "selfName", *groupQuery, *coreIdHandler, *friendList);
    auto sender = groupQuery->getGroupPeerPk(0, 0);
    g.updateUsername(sender, "sender1");

    auto notificationData = notificationGenerator->groupMessageNotification(&g, sender, "test");
    QVERIFY(notificationData.title == "groupName");
    QVERIFY(notificationData.message == "sender1: test");
}

void TestNotificationGenerator::testMultipleGroupMessages()
{
    Group g(0, GroupId(0), "groupName", false, "selfName", *groupQuery, *coreIdHandler, *friendList);

    auto sender = groupQuery->getGroupPeerPk(0, 0);
    g.updateUsername(sender, "sender1");

    auto sender2 = groupQuery->getGroupPeerPk(0, 1);
    g.updateUsername(sender2, "sender2");

    notificationGenerator->groupMessageNotification(&g, sender, "test1");

    auto notificationData = notificationGenerator->groupMessageNotification(&g, sender2, "test2");
    QVERIFY(notificationData.title == "2 message(s) from groupName");
    QVERIFY(notificationData.message == "sender2: test2");
}

void TestNotificationGenerator::testMultipleFriendSourceMessages()
{
    Friend f(0, ToxPk());
    f.setName("friend1");

    Friend f2(1, ToxPk());
    f2.setName("friend2");

    notificationGenerator->friendMessageNotification(&f, "test1");
    auto notificationData = notificationGenerator->friendMessageNotification(&f2, "test2");

    QCOMPARE(notificationData.title, QStringLiteral("2 new message(s)"));
    QVERIFY(notificationData.message == "friend1, friend2");
}

void TestNotificationGenerator::testMultipleGroupSourceMessages()
{
    Group g(0, GroupId(QByteArray(32, 0)), "groupName", false, "selfName", *groupQuery, *coreIdHandler, *friendList);
    Group g2(1, GroupId(QByteArray(32, 1)), "groupName2", false, "selfName", *groupQuery, *coreIdHandler, *friendList);

    auto sender = groupQuery->getGroupPeerPk(0, 0);
    g.updateUsername(sender, "sender1");

    notificationGenerator->groupMessageNotification(&g, sender, "test1");
    auto notificationData = notificationGenerator->groupMessageNotification(&g2, sender, "test1");

    QCOMPARE(notificationData.title, QStringLiteral("2 new message(s)"));
    QVERIFY(notificationData.message == "groupName, groupName2");
}

void TestNotificationGenerator::testMixedSourceMessages()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    Group g(0, GroupId(QByteArray(32, 0)), "group", false, "selfName", *groupQuery, *coreIdHandler, *friendList);

    auto sender = groupQuery->getGroupPeerPk(0, 0);
    g.updateUsername(sender, "sender1");

    notificationGenerator->friendMessageNotification(&f, "test1");
    auto notificationData = notificationGenerator->groupMessageNotification(&g, sender, "test2");

    QCOMPARE(notificationData.title, QStringLiteral("2 new message(s)"));
    QVERIFY(notificationData.message == "friend, group");

    notificationData = notificationGenerator->fileTransferNotification(&f, "file", 0);
    QCOMPARE(notificationData.title, QStringLiteral("3 new message(s)"));
    QVERIFY(notificationData.message == "friend, group");
}

void TestNotificationGenerator::testFileTransfer()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    auto notificationData = notificationGenerator->fileTransferNotification(&f, "file", 5 * 1024 * 1024 /* 5MB */);

    QCOMPARE(notificationData.title, QStringLiteral("friend"));
    // 5 MiB is 5 242 880 bytes: 5.2 MB, counted in thousands as on the phones.
    QCOMPARE(notificationData.message, QStringLiteral("File: file (5.2 MB)"));
}

void TestNotificationGenerator::testFileTransferAfterMessage()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    notificationGenerator->friendMessageNotification(&f, "test1");
    auto notificationData = notificationGenerator->fileTransferNotification(&f, "file", 5 * 1024 * 1024 /* 5MB */);

    QVERIFY(notificationData.title == "2 message(s) from friend");
    QCOMPARE(notificationData.message, QStringLiteral("File: file"));
}

void TestNotificationGenerator::testGroupInvitation()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    auto notificationData = notificationGenerator->groupInvitationNotification(&f);

    QCOMPARE(notificationData.title, QStringLiteral("friend"));
    QCOMPARE(notificationData.message, QStringLiteral("Invites you to a group"));

    notificationData = notificationGenerator->groupInvitationNotification(&f, "Book club");
    QCOMPARE(notificationData.title, QStringLiteral("friend"));
    QCOMPARE(notificationData.message, QStringLiteral("Invites you to the group Book club"));
}

void TestNotificationGenerator::testGroupInviteUncounted()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    notificationGenerator->friendMessageNotification(&f, "test");
    notificationGenerator->groupInvitationNotification(&f);
    auto notificationData = notificationGenerator->friendMessageNotification(&f, "test2");

    QVERIFY(notificationData.title == "2 message(s) from friend");
    QVERIFY(notificationData.message == "test2");
}

void TestNotificationGenerator::testFriendRequest()
{
    ToxPk sender(QByteArray(32, 0));

    auto notificationData = notificationGenerator->friendRequestNotification(sender, "request");

    QCOMPARE(notificationData.title, QStringLiteral("Incoming request"));
    QCOMPARE(notificationData.message, QStringLiteral("request"));
    QVERIFY(!notificationData.title.contains(sender.toString()));
}

void TestNotificationGenerator::testFriendRequestUncounted()
{
    Friend f(0, ToxPk());
    f.setName("friend");
    ToxPk sender(QByteArray(32, 0));

    notificationGenerator->friendMessageNotification(&f, "test");
    notificationGenerator->friendRequestNotification(sender, "request");
    auto notificationData = notificationGenerator->friendMessageNotification(&f, "test2");

    QVERIFY(notificationData.title == "2 message(s) from friend");
    QVERIFY(notificationData.message == "test2");
}

void TestNotificationGenerator::testSimpleFriendMessage()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    notificationSettings->setNotifyHide(true);

    auto notificationData = notificationGenerator->friendMessageNotification(&f, "test");

    QVERIFY(notificationData.title == "New message");
    QVERIFY(notificationData.message == "");
}

void TestNotificationGenerator::testSimpleFileTransfer()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    notificationSettings->setNotifyHide(true);

    auto notificationData = notificationGenerator->fileTransferNotification(&f, "file", 0);

    QCOMPARE(notificationData.title, QStringLiteral("Incoming file"));
    QVERIFY(notificationData.message == "");
}

void TestNotificationGenerator::testSimpleGroupMessage()
{
    Group g(0, GroupId(0), "groupName", false, "selfName", *groupQuery, *coreIdHandler, *friendList);
    auto sender = groupQuery->getGroupPeerPk(0, 0);
    g.updateUsername(sender, "sender1");

    notificationSettings->setNotifyHide(true);

    auto notificationData = notificationGenerator->groupMessageNotification(&g, sender, "test");
    QVERIFY(notificationData.title == "New group message");
    QVERIFY(notificationData.message == "");
}

void TestNotificationGenerator::testSimpleFriendRequest()
{
    ToxPk sender(QByteArray(32, 0));

    notificationSettings->setNotifyHide(true);

    auto notificationData = notificationGenerator->friendRequestNotification(sender, "request");

    QCOMPARE(notificationData.title, QStringLiteral("Incoming request"));
    QVERIFY(notificationData.message == "");
}

void TestNotificationGenerator::testSimpleGroupInvite()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    notificationSettings->setNotifyHide(true);
    auto notificationData = notificationGenerator->groupInvitationNotification(&f, "Book club");

    // With message text off the group's name stays out too: only that an invitation came.
    QCOMPARE(notificationData.title, QStringLiteral("Group invitation"));
    QVERIFY(notificationData.message == "");
}

void TestNotificationGenerator::testSimpleMessageToggle()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    notificationSettings->setNotifyHide(true);

    notificationGenerator->friendMessageNotification(&f, "test");

    notificationSettings->setNotifyHide(false);

    auto notificationData = notificationGenerator->friendMessageNotification(&f, "test2");

    QVERIFY(notificationData.title == "2 message(s) from friend");
    QVERIFY(notificationData.message == "test2");
}

QTEST_GUILESS_MAIN(TestNotificationGenerator)
#include "notificationgenerator_test.moc"
