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

#include "src/model/chathistory.h"
#include "src/core/groupid.h"
#include "src/core/icoregroupmessagesender.h"
#include "src/core/toxpk.h"
#include "src/friendlist.h"
#include "src/grouplist.h"
#include "src/model/group.h"
#include "src/model/groupmessagedispatcher.h"
#include "src/model/message.h"
#include "src/persistence/db/rawdatabase.h"
#include "src/persistence/history.h"
#include "src/persistence/settings.h"
#include "src/widget/tool/imessageboxmanager.h"

#include "mock/mockcoreidhandler.h"
#include "mock/mockgroupquery.h"

#include <QApplication>
#include <QDateTime>
#include <QHash>
#include <QSet>
#include <QTemporaryDir>
#include <QTemporaryFile>
#include <QtTest/QtTest>

#include <tox/tox.h>

#include <memory>
#include <utility>
#include <vector>

namespace {
const GroupId family{QByteArray(GroupId::size, 'g')};
const ToxPk maryam{QByteArray(ToxPk::size, 'm')};
const ToxPk yusuf{QByteArray(ToxPk::size, 'y')};
/** Our key in the group, as Core::getGroupSelfPublicKey gives it. */
const ToxPk groupSelf{QByteArray(ToxPk::size, 's')};
const QByteArray fileId{"0123456789abcdef0123456789abcdef"};

using Anchor = Air::MessageEffect::Anchor;
using Change = SessionChatLog::EffectResult::Change;

/** A deletion by its author (packet 0x42), as Air::readGroupPacket reads it. */
Air::MessageEffect deletionOf(Anchor anchor, const QByteArray& anchorId)
{
    Air::MessageEffect effect;
    effect.kind = Air::MessageEffect::Kind::Delete;
    effect.anchor = anchor;
    effect.anchorId = anchorId;
    return effect;
}

/** What AirLocal keeps between the starts of the app, in memory for every start of a test. */
class MemoryEntryStore : public Air::EntryMarksStore
{
public:
    Air::EntryMarks entryMarks(const QString& key) const override
    {
        return marks.value(key);
    }
    void setEntryMarks(const QString& key, const Air::EntryMarks& kept) override
    {
        if (kept.isEmpty()) {
            marks.remove(key);
        } else {
            marks.insert(key, kept);
        }
    }
    QByteArray wireId(const QString& key) const override
    {
        return wires.value(key);
    }
    void setWireId(const QString& key, const QByteArray& id) override
    {
        if (id.isEmpty()) {
            wires.remove(key);
        } else {
            wires.insert(key, id);
        }
    }
    bool isRelayed(const QString& key) const override
    {
        return relayed.contains(key);
    }
    void markRelayed(const QString& key) override
    {
        relayed.insert(key);
    }

private:
    QHash<QString, Air::EntryMarks> marks;
    QHash<QString, QByteArray> wires;
    QSet<QString> relayed;
};

class SilentMessageBoxes : public IMessageBoxManager
{
public:
    ~SilentMessageBoxes() override = default;
    void showInfo(const QString& title, const QString& msg) override
    {
        std::ignore = title;
        std::ignore = msg;
    }
    void showWarning(const QString& title, const QString& msg) override
    {
        std::ignore = title;
        std::ignore = msg;
    }
    void showError(const QString& title, const QString& msg) override
    {
        std::ignore = title;
        std::ignore = msg;
    }
    bool askQuestion(const QString& title, const QString& msg, bool defaultAns = false,
                     bool warning = true, bool yesno = true) override
    {
        std::ignore = title;
        std::ignore = msg;
        std::ignore = warning;
        std::ignore = yesno;
        return defaultAns;
    }
    bool askQuestion(const QString& title, const QString& msg, const QString& button1,
                     const QString& button2, bool defaultAns = false, bool warning = true) override
    {
        std::ignore = title;
        std::ignore = msg;
        std::ignore = button1;
        std::ignore = button2;
        std::ignore = warning;
        return defaultAns;
    }
    void confirmExecutableOpen(const QFileInfo& file) override
    {
        std::ignore = file;
    }
};

/** A group that takes every text at once; the receipts go nowhere. */
class TakingGroupSender : public ICoreGroupMessageSender
{
public:
    SendResult sendGroupAction(int groupId, const QString& message, uint32_t& wireId) override
    {
        std::ignore = groupId;
        std::ignore = message;
        wireId = ++lastWireId;
        return SendResult::Sent;
    }
    SendResult sendGroupMessage(int groupId, const QString& message, uint32_t& wireId) override
    {
        std::ignore = groupId;
        std::ignore = message;
        wireId = ++lastWireId;
        return SendResult::Sent;
    }
    void sendGroupDeliveryReceipt(int groupId, const ToxPk& author, uint32_t messageId) override
    {
        std::ignore = groupId;
        std::ignore = author;
        std::ignore = messageId;
    }

private:
    uint32_t lastWireId = 0;
};
} // namespace

/**
 * KHANDAQ (group notify parity, 2026-11-20): what the conversation of a group tells the window of
 * the texts a member relays from its history, on a real database. One that joins the conversation
 * is told once (ChatHistory::syncedMessageAdded), and the window counts it as unread; one the
 * history keeps already, live or relayed in an earlier run, joins nothing and is not told.
 * KHANDAQ (beta feedback 2026-11-22): nor does one its author deleted.
 */
class TestChatHistory : public QObject
{
    Q_OBJECT
private slots:
    void initTestCase();
    void init();
    void cleanup();
    void cleanupTestCase();
    void aRelayedTextIsToldOnceItJoinsTheConversation();
    void aRelayedTextTheHistoryKeepsIsNotToldAgain();
    void aCopyOfADeletedTextIsNotShownAgain();
    void aDeletionOfMessagesNotLoadedErasesThemInTheHistory();

private:
    /** The conversation of the group as a start of the app makes it, on the same database. */
    void open();

    SilentMessageBoxes messageBoxes;
    std::unique_ptr<Settings> settings;
    std::unique_ptr<QTemporaryFile> file;
    std::shared_ptr<History> history;
    FriendList friendList;
    GroupList groupList;
    MockGroupQuery groupQuery;
    MockCoreIdHandler idHandler;
    TakingGroupSender groupSender;
    std::unique_ptr<MessageProcessor::SharedParams> processorParams;
    std::unique_ptr<Group> group;
    std::unique_ptr<GroupMessageDispatcher> dispatcher;
    std::unique_ptr<ChatHistory> chatHistory;
    MemoryEntryStore entryStore;
    /** The relayed texts the conversation told of, in order. */
    std::vector<std::pair<ToxPk, Message>> told;
};

void TestChatHistory::initTestCase()
{
    // As Nexus does at start: the rows the database put in reach the history through a queue.
    qRegisterMetaType<RowId>("RowId");
    settings.reset(new Settings(messageBoxes));
    settings->setEnableLogging(true);
    processorParams.reset(
        new MessageProcessor::SharedParams(tox_max_message_length(), 10 * 1024 * 1024));
}

void TestChatHistory::init()
{
    file.reset(new QTemporaryFile());
    QVERIFY(file->open());
    file->close();
    auto db = std::make_shared<RawDatabase>(file->fileName(), QString(), QByteArray());
    history = std::make_shared<History>(db, *settings, messageBoxes);
    QVERIFY(history->isValid());
    group.reset(new Group(0, family, QStringLiteral("Family"), false, QStringLiteral("me"),
                          groupQuery, idHandler, friendList));
    open();
}

void TestChatHistory::cleanup()
{
    // As Widget::removeGroup: what the history still has queued runs before the conversation goes.
    if (history) {
        history->sync();
    }
    chatHistory.reset();
    dispatcher.reset();
    group.reset();
    history.reset();
    file.reset();
}

void TestChatHistory::cleanupTestCase()
{
    settings.reset();
}

void TestChatHistory::open()
{
    // As Widget::removeGroup: what the history still has queued runs before the conversation goes,
    // and the conversation, which holds the dispatcher by reference, goes before the dispatcher.
    history->sync();
    chatHistory.reset();
    dispatcher.reset(new GroupMessageDispatcher(*group, MessageProcessor(*processorParams),
                                                idHandler, groupSender, *settings));
    chatHistory.reset(new ChatHistory(*group, history.get(), idHandler, *settings, *dispatcher,
                                      friendList, groupList, &entryStore));
    told.clear();
    connect(chatHistory.get(), &ChatHistory::syncedMessageAdded, this,
            [this](const ToxPk& author, const Message& message) {
                // Told once it is in the conversation, which the row of the group reads.
                const ChatLogItem& newest = chatHistory->at(chatHistory->getNextIdx() - 1);
                QVERIFY(newest.getContentType() == ChatLogItem::ContentType::message);
                QCOMPARE(newest.getContentAsMessage().message.content, message.content);
                told.emplace_back(author, message);
            });
}

void TestChatHistory::aRelayedTextIsToldOnceItJoinsTheConversation()
{
    const QDateTime written = QDateTime::currentDateTime().addSecs(-10 * 60);
    const size_t before = chatHistory->getNextIdx().get();
    dispatcher->onSyncedMessageReceived(maryam, QStringLiteral("Salam"), 0x0A0B0C0D, written);
    QCOMPARE(told.size(), size_t{1});
    QCOMPARE(told[0].first, maryam);
    QCOMPARE(told[0].second.content, QStringLiteral("Salam"));
    QCOMPARE(told[0].second.timestamp, written);
    QCOMPARE(chatHistory->getNextIdx().get(), before + 1);

    // Ours never comes back as theirs: the dispatcher drops it, and nothing is told.
    dispatcher->onSyncedMessageReceived(idHandler.getSelfPublicKey(), QStringLiteral("Ours"),
                                        0x0A0B0C0E, written);
    QCOMPARE(told.size(), size_t{1});
    QCOMPARE(chatHistory->getNextIdx().get(), before + 1);
}

void TestChatHistory::aRelayedTextTheHistoryKeepsIsNotToldAgain()
{
    // A text that came live, then a member relays it: the history keeps it already.
    dispatcher->onMessageReceived(maryam, false, QStringLiteral("Live words"), 7);
    history->sync();
    const size_t afterLive = chatHistory->getNextIdx().get();
    dispatcher->onSyncedMessageReceived(maryam, QStringLiteral("Live words"), 7,
                                        QDateTime::currentDateTime().addSecs(-1));
    QVERIFY(told.empty());
    QCOMPARE(chatHistory->getNextIdx().get(), afterLive);

    // A relayed text told in one run is not told again in the next.
    const QDateTime written = QDateTime::currentDateTime().addSecs(-20 * 60);
    dispatcher->onSyncedMessageReceived(maryam, QStringLiteral("Relayed"), 8, written);
    QCOMPARE(told.size(), size_t{1});
    open();
    dispatcher->onSyncedMessageReceived(maryam, QStringLiteral("Relayed"), 8, written);
    QVERIFY(told.empty());

    // Other words of theirs are news.
    dispatcher->onSyncedMessageReceived(maryam, QStringLiteral("Relayed too"), 9, written.addSecs(1));
    QCOMPARE(told.size(), size_t{1});
    QCOMPARE(told[0].second.content, QStringLiteral("Relayed too"));
}

/**
 * KHANDAQ (beta feedback 2026-11-22): a member who missed a deletion relays the message again from
 * the history of the group, and after a restart the history has no words of it to know it by. The
 * deletion is kept: neither that copy nor a live one joins the conversation, and nothing is told.
 * The author's other texts, and one of another member with the same id, still come.
 */
void TestChatHistory::aCopyOfADeletedTextIsNotShownAgain()
{
    dispatcher->onMessageReceived(maryam, false, QStringLiteral("Deleted words"), 21);
    history->sync();
    const size_t count = chatHistory->getNextIdx().get();
    const ChatLogIdx deleted{count - 1};
    const SessionChatLog::EffectResult result =
        chatHistory->applyEffect(maryam, deletionOf(Anchor::Text, Air::groupWireId(21)), groupSelf);
    QVERIFY(result.change == Change::Deleted);
    QVERIFY(!chatHistory->isShown(deleted));
    QVERIFY(chatHistory->isDeleted(maryam, Anchor::Text, Air::groupWireId(21)));

    open();
    QVERIFY(!chatHistory->isShown(deleted));
    const QDateTime written = QDateTime::currentDateTime().addSecs(-60);
    dispatcher->onSyncedMessageReceived(maryam, QStringLiteral("Deleted words"), 21, written);
    QVERIFY(told.empty());
    QCOMPARE(chatHistory->getNextIdx().get(), count);
    dispatcher->onMessageReceived(maryam, false, QStringLiteral("Deleted words"), 21);
    QCOMPARE(chatHistory->getNextIdx().get(), count);

    dispatcher->onSyncedMessageReceived(maryam, QStringLiteral("Kept words"), 22,
                                        written.addSecs(1));
    QCOMPARE(told.size(), size_t{1});
    dispatcher->onSyncedMessageReceived(yusuf, QStringLiteral("Deleted words"), 21,
                                        written.addSecs(2));
    QCOMPARE(told.size(), size_t{2});
    QCOMPARE(chatHistory->getNextIdx().get(), count + 2);

    // A deletion of ours, or one that names no message, is not kept.
    const Air::MessageEffect ours = deletionOf(Anchor::Text, Air::groupWireId(23));
    std::ignore = chatHistory->applyEffect(groupSelf, ours, groupSelf);
    QVERIFY(!chatHistory->isDeleted(groupSelf, Anchor::Text, Air::groupWireId(23)));
    const Air::MessageEffect onItsWay = deletionOf(Anchor::Text, Air::groupWireId(0));
    std::ignore = chatHistory->applyEffect(maryam, onItsWay, groupSelf);
    QVERIFY(!chatHistory->isDeleted(maryam, Anchor::Text, Air::groupWireId(0)));
}

/**
 * KHANDAQ (beta feedback 2026-11-22): a start of the app loads the last 100 entries of the group.
 * A deletion of an older text or file of the author erases it in the history all the same, and it
 * stays erased after a restart; one by another member erases nothing of theirs.
 */
void TestChatHistory::aDeletionOfMessagesNotLoadedErasesThemInTheHistory()
{
    dispatcher->onMessageReceived(maryam, false, QStringLiteral("Old words"), 31);
    ToxFile photo(1, 0, QStringLiteral("photo.jpg"), QStringLiteral("/files/photo.jpg"), 1234,
                  ToxFile::RECEIVING);
    photo.resumeFileId = fileId;
    photo.status = ToxFile::INITIALIZING;
    chatHistory->onFileUpdated(maryam, photo);
    photo.status = ToxFile::FINISHED;
    chatHistory->onFileUpdated(maryam, photo);
    for (uint32_t id = 100; id < 220; ++id) {
        dispatcher->onMessageReceived(yusuf, false, QStringLiteral("Later %1").arg(id), id);
    }
    history->sync();
    QCoreApplication::processEvents();
    const ChatLogIdx oldText{0};
    const ChatLogIdx oldFile{1};
    const Air::MessageEffect textDeleted = deletionOf(Anchor::Text, Air::groupWireId(31));
    const Air::MessageEffect fileDeleted = deletionOf(Anchor::File, fileId);

    // Another member's deletion of them: not loaded, and not theirs.
    open();
    QVERIFY(chatHistory->applyEffect(yusuf, textDeleted, groupSelf).change == Change::None);
    QVERIFY(chatHistory->applyEffect(yusuf, fileDeleted, groupSelf).change == Change::None);
    QCOMPARE(chatHistory->at(oldText).getContentAsMessage().message.content,
             QStringLiteral("Old words"));
    QVERIFY(chatHistory->isShown(oldText));
    QCOMPARE(chatHistory->at(oldFile).getContentAsFile().file.fileName,
             QStringLiteral("photo.jpg"));
    QVERIFY(chatHistory->isShown(oldFile));

    // Hers, not loaded either.
    open();
    QVERIFY(chatHistory->applyEffect(maryam, textDeleted, groupSelf).change == Change::None);
    QVERIFY(chatHistory->applyEffect(maryam, fileDeleted, groupSelf).change == Change::None);
    QVERIFY(chatHistory->at(oldText).getContentType() == ChatLogItem::ContentType::message);
    QVERIFY(!chatHistory->isShown(oldText));
    QVERIFY(!chatHistory->isShown(oldFile));
    QVERIFY(chatHistory->isDeleted(maryam, Anchor::File, fileId));

    open();
    std::ignore = chatHistory->at(oldText);
    QVERIFY(!chatHistory->isShown(oldText));
    QVERIFY(!chatHistory->isShown(oldFile));
    QVERIFY(chatHistory->isShown(ChatLogIdx{2}));
}

int main(int argc, char* argv[])
{
    // The settings need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    // Settings reads and creates its folder under the home folder: a scratch one, not the user's.
    QTemporaryDir home;
    if (!home.isValid()) {
        return 1;
    }
    qputenv("HOME", home.path().toLocal8Bit());
    QApplication app(argc, argv);
    TestChatHistory test;
    return QTest::qExec(&test, argc, argv);
}

#include "chathistory_test.moc"
