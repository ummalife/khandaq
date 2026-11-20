
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
#include "src/widget/air/airstrings.h"

#include "mock/mockcoreidhandler.h"
#include "mock/mockgroupquery.h"

#include <QCoreApplication>
#include <QLocale>
#include <QObject>
#include <QTranslator>
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
    void testGroupMessageOfMemberWhoLeft();
    void testMultipleGroupMessages();
    void testMultipleFriendSourceMessages();
    void testMultipleGroupSourceMessages();
    void testMixedSourceMessages();
    void testFileTransfer();
    void testFileTransferAfterMessage();
    void testVoiceNoteTransfer();
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
    void testVideoAndPhotoTransfer();
    void testFriendReaction();
    void testGroupReaction();
    void testReactionUncounted();
    void testSimpleReaction();
    void testReactionWords();
    void testReactionInRussian();

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

/**
 * KHANDAQ (group notify parity, 2026-11-20): the author left before the notification of their
 * message: the words alone, as the band in the window says them, never ": test2".
 */
void TestNotificationGenerator::testGroupMessageOfMemberWhoLeft()
{
    Group g(0, GroupId(0), "groupName", false, "selfName", *groupQuery, *coreIdHandler, *friendList);
    g.regeneratePeerList();
    const ToxPk sender = groupQuery->getGroupPeerPk(0, 1);
    g.updateUsername(sender, "sender1");
    QCOMPARE(notificationGenerator->groupMessageNotification(&g, sender, "test").message,
             QStringLiteral("sender1: test"));

    // Only we are in the group now.
    groupQuery->setAsEmptyGroup();
    g.regeneratePeerList();
    QVERIFY(g.resolveToxPk(sender).isEmpty());
    auto notificationData = notificationGenerator->groupMessageNotification(&g, sender, "test2");
    QCOMPARE(notificationData.title, QStringLiteral("2 message(s) from groupName"));
    QCOMPARE(notificationData.message, QStringLiteral("test2"));
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

void TestNotificationGenerator::testVoiceNoteTransfer()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    // A voice note says what it is, as the phones say it, not the name of its file.
    auto notificationData = notificationGenerator->fileTransferNotification(
        &f, QStringLiteral("voice_F5CD3544.file.m4a"), 43000);
    QCOMPARE(notificationData.title, QStringLiteral("friend"));
    QCOMPARE(notificationData.message, QStringLiteral("Voice message (43 KB)"));

    notificationData = notificationGenerator->fileTransferNotification(
        &f, QStringLiteral("voice_F5CD3545.file.m4a"), 43000);
    QCOMPARE(notificationData.message, QStringLiteral("Voice message"));
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

/**
 * KHANDAQ (0.2.64): a video and a photo say what they are, as a voice note does, by the extension
 * of the push previews of the phones; any other file keeps its name.
 */
void TestNotificationGenerator::testVideoAndPhotoTransfer()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    auto notificationData = notificationGenerator->fileTransferNotification(
        &f, QStringLiteral("clip.MP4"), 5 * 1024 * 1024);
    QCOMPARE(notificationData.title, QStringLiteral("friend"));
    QCOMPARE(notificationData.message, QStringLiteral("Video (5.2 MB)"));

    notificationData = notificationGenerator->fileTransferNotification(
        &f, QStringLiteral("IMG_0042.heic"), 1100000);
    QCOMPARE(notificationData.message, QStringLiteral("Photo"));

    notificationGenerator->onNotificationActivated();
    notificationData = notificationGenerator->fileTransferNotification(
        &f, QStringLiteral("IMG_0042.jpeg"), 1100000);
    QCOMPARE(notificationData.message, QStringLiteral("Photo (1.1 MB)"));

    QCOMPARE(AirStrings::fileKindWords(QStringLiteral("trip.webm")), QStringLiteral("Video"));
    QCOMPARE(AirStrings::fileKindWords(QStringLiteral("voice_F5CD3544.file.m4a")),
             QStringLiteral("Voice message"));
    QVERIFY(AirStrings::fileKindWords(QStringLiteral("plan.pdf")).isEmpty());
}

/** KHANDAQ (0.2.64): the contact, then what they reacted with and to what of ours. */
void TestNotificationGenerator::testFriendReaction()
{
    Friend f(0, ToxPk());
    f.setName("friend");

    const QString words = AirStrings::reactionWords(
        QStringLiteral("❤️"), Air::MessageEffect::Anchor::Text, QStringLiteral("See you at five"));
    QCOMPARE(words, QStringLiteral("Reacted ❤️ to “See you at five”"));
    auto notificationData = notificationGenerator->friendReactionNotification(&f, words);
    QCOMPARE(notificationData.title, QStringLiteral("friend"));
    QCOMPARE(notificationData.message, words);
}

/** KHANDAQ (0.2.64): the group, then who reacted and the words, as a message of a group. */
void TestNotificationGenerator::testGroupReaction()
{
    Group g(0, GroupId(0), "groupName", false, "selfName", *groupQuery, *coreIdHandler,
            *friendList);
    auto sender = groupQuery->getGroupPeerPk(0, 0);
    g.updateUsername(sender, "sender1");

    const QString words = AirStrings::reactionWords(
        QStringLiteral("👍"), Air::MessageEffect::Anchor::File, QStringLiteral("IMG_0042.HEIC"));
    QCOMPARE(words, QStringLiteral("Reacted 👍 to your photo"));
    auto notificationData = notificationGenerator->groupReactionNotification(&g, sender, words);
    QCOMPARE(notificationData.title, QStringLiteral("groupName"));
    QCOMPARE(notificationData.message, QStringLiteral("sender1: Reacted 👍 to your photo"));

    // A member the group has no name for: the words alone.
    notificationData =
        notificationGenerator->groupReactionNotification(&g, ToxPk(QByteArray(32, '\x7f')), words);
    QCOMPARE(notificationData.title, QStringLiteral("groupName"));
    QCOMPARE(notificationData.message, words);
}

/** KHANDAQ (0.2.64): a reaction is no message: it neither counts nor takes the count. */
void TestNotificationGenerator::testReactionUncounted()
{
    Friend f(0, ToxPk());
    f.setName("friend");
    Group g(0, GroupId(0), "groupName", false, "selfName", *groupQuery, *coreIdHandler,
            *friendList);
    auto sender = groupQuery->getGroupPeerPk(0, 0);
    g.updateUsername(sender, "sender1");
    const QString words = QStringLiteral("Reacted 👍 to your file");

    notificationGenerator->friendMessageNotification(&f, "test");
    auto notificationData = notificationGenerator->friendReactionNotification(&f, words);
    QCOMPARE(notificationData.title, QStringLiteral("friend"));
    notificationData = notificationGenerator->groupReactionNotification(&g, sender, words);
    QCOMPARE(notificationData.title, QStringLiteral("groupName"));

    notificationData = notificationGenerator->friendMessageNotification(&f, "test2");
    QCOMPARE(notificationData.title, QStringLiteral("2 message(s) from friend"));
    QCOMPARE(notificationData.message, QStringLiteral("test2"));
}

/** KHANDAQ (0.2.64): with message text off only that a reaction came, from nobody. */
void TestNotificationGenerator::testSimpleReaction()
{
    Friend f(0, ToxPk());
    f.setName("friend");
    Group g(0, GroupId(0), "groupName", false, "selfName", *groupQuery, *coreIdHandler,
            *friendList);
    auto sender = groupQuery->getGroupPeerPk(0, 0);
    g.updateUsername(sender, "sender1");
    const QString words = QStringLiteral("Reacted 👍 to “secret”");

    notificationSettings->setNotifyHide(true);

    auto notificationData = notificationGenerator->friendReactionNotification(&f, words);
    QCOMPARE(notificationData.title, QStringLiteral("New reaction"));
    QVERIFY(notificationData.message.isEmpty());
    QVERIFY(notificationData.pixmap.isNull());
    notificationData = notificationGenerator->groupReactionNotification(&g, sender, words);
    QCOMPARE(notificationData.title, QStringLiteral("New reaction"));
    QVERIFY(notificationData.message.isEmpty());
}

/**
 * KHANDAQ (0.2.64): the words of a reaction by docs/PUSH_PREVIEWS.md: the first 80 characters of
 * our text as a person counts them, nothing added, or what our file is.
 */
void TestNotificationGenerator::testReactionWords()
{
    using Anchor = Air::MessageEffect::Anchor;
    const QString heart = QStringLiteral("❤️");
    const QString open = QStringLiteral("Reacted ❤️ to “");
    const QString close = QStringLiteral("”");
    const QString a79(79, QLatin1Char('a'));

    // Eighty characters and no more: a flag, or a letter with its mark, is one and stays whole.
    const QString flag = QStringLiteral("🇸🇦");
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::Text, a79 + flag + QStringLiteral("bc")),
             open + a79 + flag + close);
    const QString accented = QStringLiteral("é");
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::Text, a79 + accented + QStringLiteral("z")),
             open + a79 + accented + close);
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::Text, a79 + QStringLiteral("b")),
             open + a79 + QStringLiteral("b") + close);
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::Text, QStringLiteral("  See you  ")),
             open + QStringLiteral("See you") + close);

    // A reply says its own words, not the quote; a form this build cannot read, none at all.
    QCOMPARE(AirStrings::reactionWords(
                 heart, Anchor::Text,
                 QStringLiteral("[KQ|42|ABCDEF12|1700000000000|Amina]\nquote\n[KQ/end]\nСалам")),
             open + QStringLiteral("Салам") + close);
    for (const QString& wordless :
         {QString(), QStringLiteral("   "), QStringLiteral("[KQ|poll|1|2|3]"),
          QStringLiteral("[KQ|7|AB|1|Yusuf]\nquoted only")}) {
        QCOMPARE(AirStrings::reactionWords(heart, Anchor::Text, wordless),
                 QStringLiteral("Reacted ❤️ to your message"));
    }

    QCOMPARE(AirStrings::reactionWords(heart, Anchor::File, QStringLiteral("voice_3F9A.file.m4a")),
             QStringLiteral("Reacted ❤️ to your voice message"));
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::File, QStringLiteral("clip.MOV")),
             QStringLiteral("Reacted ❤️ to your video"));
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::File, QStringLiteral("IMG_1.webp")),
             QStringLiteral("Reacted ❤️ to your photo"));
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::File, QStringLiteral("plan.pdf")),
             QStringLiteral("Reacted ❤️ to your file"));

    // What the emoji or the text holds is never read as an argument.
    QCOMPARE(AirStrings::reactionWords(QStringLiteral("%2"), Anchor::Text, QStringLiteral("hi %1")),
             QStringLiteral("Reacted %2 to “hi %1”"));
}

/** KHANDAQ (0.2.64): the same words in Russian, as the spec gives them. */
void TestNotificationGenerator::testReactionInRussian()
{
    using Anchor = Air::MessageEffect::Anchor;
    Q_INIT_RESOURCE(translations);
    QTranslator translator;
    QVERIFY(translator.load(QStringLiteral("ru"), QStringLiteral(":translations/")));
    QCoreApplication::installTranslator(&translator);
    const QString heart = QStringLiteral("❤️");

    QCOMPARE(AirStrings::reactionWords(heart, Anchor::Text, QStringLiteral("Салам")),
             QStringLiteral("Реакция ❤️ на «Салам»"));
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::File, QStringLiteral("voice_3F9A.file.m4a")),
             QStringLiteral("Реакция ❤️ на ваше голосовое сообщение"));
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::File, QStringLiteral("clip.mp4")),
             QStringLiteral("Реакция ❤️ на ваше видео"));
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::File, QStringLiteral("IMG_1.png")),
             QStringLiteral("Реакция ❤️ на ваше фото"));
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::File, QStringLiteral("plan.pdf")),
             QStringLiteral("Реакция ❤️ на ваш файл"));
    QCOMPARE(AirStrings::reactionWords(heart, Anchor::Text, QString()),
             QStringLiteral("Реакция ❤️ на ваше сообщение"));
    QCOMPARE(AirStrings::fileKindWords(QStringLiteral("clip.mp4")), QStringLiteral("Видео"));
    QCOMPARE(AirStrings::fileKindWords(QStringLiteral("IMG_1.png")), QStringLiteral("Фото"));

    Friend f(0, ToxPk());
    f.setName("friend");
    notificationSettings->setNotifyHide(true);
    QCOMPARE(notificationGenerator->friendReactionNotification(&f, heart).title,
             QStringLiteral("Новая реакция"));

    QCoreApplication::removeTranslator(&translator);
}

QTEST_GUILESS_MAIN(TestNotificationGenerator)
#include "notificationgenerator_test.moc"
