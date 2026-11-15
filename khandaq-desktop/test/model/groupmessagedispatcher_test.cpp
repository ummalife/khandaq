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

#include "src/core/core.h"
#include "src/core/icoregroupmessagesender.h"
#include "src/model/group.h"
#include "src/model/groupmessagedispatcher.h"
#include "src/model/message.h"
#include "src/persistence/settings.h"
#include "src/persistence/igroupsettings.h"
#include "src/friendlist.h"
#include "util/interface.h"

#include "mock/mockcoreidhandler.h"
#include "mock/mockgroupquery.h"

#include <QElapsedTimer>
#include <QObject>
#include <QtTest/QtTest>

#include <set>
#include <deque>
#include <vector>


class MockGroupMessageSender : public ICoreGroupMessageSender
{
public:
    SendResult sendGroupAction(int groupId, const QString& action, uint32_t& wireId) override;

    SendResult sendGroupMessage(int groupId, const QString& message, uint32_t& wireId) override;

    void sendGroupDeliveryReceipt(int groupId, const ToxPk& author, uint32_t messageId) override;

    bool sendsFileInto(int groupId) const override
    {
        std::ignore = groupId;
        return fileGoes;
    }

    struct Receipt
    {
        int groupId;
        ToxPk author;
        uint32_t messageId;
    };

    size_t numSentActions = 0;
    size_t numSentMessages = 0;
    /** The texts handed to toxcore, in the order they went. */
    std::vector<QString> sentTexts;
    std::vector<Receipt> receipts;
    /** The id toxcore gives the next message, as tox_group_send_message hands it back. */
    uint32_t nextWireId = 0x0A0B0C0D;
    /** Whether the group takes a text now, as Core asks toxcore (Air::groupTakesText). */
    bool ready = true;
    /** Texts that cannot go as they are, as one too long: Core refuses them ready or not. */
    QStringList refused;
    /** Whether a file of ours goes into the group (Core::sendsFileInto). */
    bool fileGoes = false;

private:
    SendResult take(const QString& text, uint32_t& wireId);
};

ICoreGroupMessageSender::SendResult MockGroupMessageSender::take(const QString& text,
                                                                 uint32_t& wireId)
{
    wireId = 0;
    if (refused.contains(text)) {
        return SendResult::Failed;
    }
    if (!ready) {
        return SendResult::NotReady;
    }
    wireId = nextWireId++;
    return SendResult::Sent;
}

void MockGroupMessageSender::sendGroupDeliveryReceipt(int groupId, const ToxPk& author,
                                                      uint32_t messageId)
{
    receipts.push_back({groupId, author, messageId});
}

ICoreGroupMessageSender::SendResult
MockGroupMessageSender::sendGroupAction(int groupId, const QString& action, uint32_t& wireId)
{
    std::ignore = groupId;
    const SendResult result = take(action, wireId);
    if (result == SendResult::Sent) {
        numSentActions++;
    }
    return result;
}

ICoreGroupMessageSender::SendResult
MockGroupMessageSender::sendGroupMessage(int groupId, const QString& message, uint32_t& wireId)
{
    std::ignore = groupId;
    const SendResult result = take(message, wireId);
    if (result == SendResult::Sent) {
        numSentMessages++;
        sentTexts.push_back(message);
    }
    return result;
}

class MockGroupSettings : public QObject, public IGroupSettings
{
    Q_OBJECT

public:
    QStringList getBlackList() const override;
    void setBlackList(const QStringList& blist) override;
    SIGNAL_IMPL(MockGroupSettings, blackListChanged, QStringList const& blist)

    bool getShowGroupJoinLeaveMessages() const override { return true; };
    void setShowGroupJoinLeaveMessages(bool newValue) override { std::ignore = newValue; };
    SIGNAL_IMPL(MockGroupSettings, showGroupJoinLeaveMessagesChanged, bool show)

private:
    QStringList blacklist;
};

QStringList MockGroupSettings::getBlackList() const
{
    return blacklist;
}

void MockGroupSettings::setBlackList(const QStringList& blist)
{
    blacklist = blist;
}

class TestGroupMessageDispatcher : public QObject
{
    Q_OBJECT

public:
    TestGroupMessageDispatcher();

private slots:
    void init();
    void testSignals();
    void testMessageSending();
    void aTextWaitsWhileNobodyElseIsInTheGroup();
    void testSelfReceive();
    void testBlacklist();
    void messagesCarryTheIdsToxcoreGaveThem();
    void aShownMessageIsConfirmedToItsAuthor();
    void aLongTextGoesInPartsWithinTheGroupLimit();
    void whatWaitedGoesInOrderAGraceAfterAMemberJoins();
    void whatWaitedGoesOnceTheGroupConnects();
    void aNewTextGoesBehindTheOnesThatWait();
    void aTextThatCannotGoDoesNotHoldTheOthers();
    void theWaitingPartsOfALongTextGoInOrder();
    void aTextRightAfterTheFirstMemberCameWaitsForTheGrace();
    void whatWaitsGoesNoMoreOnceTheGroupGoes();
    void whatWaitsFailsWhenTheGroupIsLeft();
    void aMemberWhoJoinsInTheGraceHasItInFull();
    void aStreamOfJoinsDoesNotHoldWhatWaitsForGood();
    void aTextThatWentWithAFileWaitsForAMemberToConfirmIt();
    void aTextThatWaitedAndWentWithAFileWaitsToo();
    void aTextThatWentWithAFileFailsWhenTheLastMemberTimesOut();
    void aTextNobodyConfirmsIsSentAfterAWhile();
    void aTextThatWaitsForAConfirmationIsSentWhenTheGroupGoes();

    void onMessageSent(DispatchedMessageId id, Message message)
    {
        auto it = outgoingMessages.find(id);
        QVERIFY(it == outgoingMessages.end());
        outgoingMessages.emplace(id);
        sentMessages.push_back(std::move(message));
    }

    void onMessageComplete(DispatchedMessageId id)
    {
        auto it = outgoingMessages.find(id);
        QVERIFY(it != outgoingMessages.end());
        outgoingMessages.erase(it);
    }

    void onMessageReceived(const ToxPk& sender, Message message)
    {
        std::ignore = sender;
        receivedMessages.push_back(std::move(message));
    }

private:
    // All unique_ptrs to make construction/init() easier to manage
    std::unique_ptr<MockGroupSettings> groupSettings;
    std::unique_ptr<MockGroupQuery> groupQuery;
    std::unique_ptr<MockCoreIdHandler> coreIdHandler;
    std::unique_ptr<Group> g;
    std::unique_ptr<MockGroupMessageSender> messageSender;
    std::unique_ptr<MessageProcessor::SharedParams> sharedProcessorParams;
    std::unique_ptr<MessageProcessor> messageProcessor;
    std::unique_ptr<GroupMessageDispatcher> groupMessageDispatcher;
    std::set<DispatchedMessageId> outgoingMessages;
    std::deque<Message> sentMessages;
    std::deque<Message> receivedMessages;
    std::unique_ptr<FriendList> friendList;
    /** The messages that failed, and the ids the ones that waited went with, as told. */
    std::vector<DispatchedMessageId> brokenMessages;
    std::vector<std::pair<DispatchedMessageId, QByteArray>> assignedWireIds;
};

namespace {
// The grace of the tests: short, so that they do not wait two seconds each.
constexpr int testGraceMs = 50;
// How long a text that went with a file waits for a member to confirm it, in the tests.
constexpr int testConfirmMs = 1000;
constexpr int waitMs = 5000;

std::vector<QString> texts(std::initializer_list<const char*> list)
{
    std::vector<QString> result;
    for (const char* text : list) {
        result.push_back(QString::fromUtf8(text));
    }
    return result;
}

/** What the members did meanwhile: the peer list read again, with one other member in it. */
void memberJoins(MockGroupQuery& query, Group& group, MockGroupMessageSender& sender)
{
    sender.ready = true;
    query.setAsFunctionalGroup();
    group.regeneratePeerList();
}
} // namespace

TestGroupMessageDispatcher::TestGroupMessageDispatcher() {}

/**
 * @brief Test initialization. Resets all members to initial state
 */
void TestGroupMessageDispatcher::init()
{
    friendList = std::unique_ptr<FriendList>(new FriendList());
    groupSettings = std::unique_ptr<MockGroupSettings>(new MockGroupSettings());
    groupQuery = std::unique_ptr<MockGroupQuery>(new MockGroupQuery());
    coreIdHandler = std::unique_ptr<MockCoreIdHandler>(new MockCoreIdHandler());
    g = std::unique_ptr<Group>(
        new Group(0, GroupId(), "TestGroup", false, "me", *groupQuery, *coreIdHandler,
            *friendList));
    messageSender = std::unique_ptr<MockGroupMessageSender>(new MockGroupMessageSender());
    sharedProcessorParams =
        std::unique_ptr<MessageProcessor::SharedParams>(new MessageProcessor::SharedParams(tox_max_message_length(), 10 * 1024 * 1024));
    messageProcessor = std::unique_ptr<MessageProcessor>(new MessageProcessor(*sharedProcessorParams));
    groupMessageDispatcher = std::unique_ptr<GroupMessageDispatcher>(
        new GroupMessageDispatcher(*g, *messageProcessor, *coreIdHandler, *messageSender,
                                   *groupSettings, testGraceMs, testConfirmMs));

    connect(groupMessageDispatcher.get(), &GroupMessageDispatcher::messageSent, this,
            &TestGroupMessageDispatcher::onMessageSent);
    connect(groupMessageDispatcher.get(), &GroupMessageDispatcher::messageComplete, this,
            &TestGroupMessageDispatcher::onMessageComplete);
    connect(groupMessageDispatcher.get(), &GroupMessageDispatcher::messageReceived, this,
            &TestGroupMessageDispatcher::onMessageReceived);
    connect(groupMessageDispatcher.get(), &GroupMessageDispatcher::messageBroken, this,
            [this](DispatchedMessageId id, BrokenMessageReason reason) {
                std::ignore = reason;
                brokenMessages.push_back(id);
            });
    connect(groupMessageDispatcher.get(), &GroupMessageDispatcher::messageWireIdAssigned, this,
            [this](DispatchedMessageId id, const QByteArray& wireId) {
                assignedWireIds.emplace_back(id, wireId);
            });

    outgoingMessages = std::set<DispatchedMessageId>();
    sentMessages = std::deque<Message>();
    receivedMessages = std::deque<Message>();
    brokenMessages.clear();
    assignedWireIds.clear();
}

/**
 * @brief Tests that the signals emitted by the dispatcher are all emitted at the correct times
 */
void TestGroupMessageDispatcher::testSignals()
{
    groupMessageDispatcher->sendMessage(false, "test");

    // For groups we pair our sent and completed signals since we have no receiver reports
    QVERIFY(outgoingMessages.size() == 0);
    QVERIFY(!sentMessages.empty());
    QVERIFY(sentMessages.front().isAction == false);
    QVERIFY(sentMessages.front().content == "test");

    // If signals are emitted correctly we should have one message in our received message buffer
    QVERIFY(receivedMessages.empty());
    groupMessageDispatcher->onMessageReceived(ToxPk(), false, "test2");

    QVERIFY(!receivedMessages.empty());
    QVERIFY(receivedMessages.front().isAction == false);
    QVERIFY(receivedMessages.front().content == "test2");
}

/**
 * @brief Tests that sent messages actually go through to core
 */
void TestGroupMessageDispatcher::testMessageSending()
{
    groupMessageDispatcher->sendMessage(false, "Test");

    QVERIFY(messageSender->numSentMessages == 1);
    QVERIFY(messageSender->numSentActions == 0);

    groupMessageDispatcher->sendMessage(true, "Test");

    QVERIFY(messageSender->numSentMessages == 1);
    QVERIFY(messageSender->numSentActions == 1);
}

/**
 * @brief 0.2.56: alone in the group, toxcore takes a text for nobody and says it went. Core does
 * not hand it over then, and the text waits: shown, neither complete nor failed, and it does not
 * go by itself while nothing changes in the group.
 */
void TestGroupMessageDispatcher::aTextWaitsWhileNobodyElseIsInTheGroup()
{
    QCOMPARE(GroupMessageDispatcher::joinGraceMs, 2000);
    groupQuery->setAsEmptyGroup();
    g->regeneratePeerList();
    messageSender->ready = false;

    groupMessageDispatcher->sendMessage(false, "Test");
    groupMessageDispatcher->sendMessage(true, "Test");

    QVERIFY(messageSender->numSentMessages == 0);
    QVERIFY(messageSender->numSentActions == 0);
    QCOMPARE(sentMessages.size(), size_t{2});
    QCOMPARE(outgoingMessages.size(), size_t{2});
    QVERIFY(brokenMessages.empty());

    // The core becomes ready, but nothing says so: the texts wait for a change of the peer list
    // or the next text.
    messageSender->ready = true;
    QTest::qWait(testGraceMs * 4);
    QVERIFY(messageSender->numSentMessages == 0);
    QCOMPARE(outgoingMessages.size(), size_t{2});
}

/**
 * @brief Tests that we do not emit any signals if we receive a message from ourself. Toxcore will send us back messages we sent
 */
void TestGroupMessageDispatcher::testSelfReceive()
{
    uint8_t selfId[ToxPk::size] = {0};
    groupMessageDispatcher->onMessageReceived(ToxPk(selfId), false, "Test");
    QVERIFY(receivedMessages.size() == 0);

    uint8_t id[ToxPk::size] = {1};
    groupMessageDispatcher->onMessageReceived(ToxPk(id), false, "Test");
    QVERIFY(receivedMessages.size() == 1);
}

/**
 * @brief Tests that messages from blacklisted peers do not get propogated from the dispatcher
 */
void TestGroupMessageDispatcher::testBlacklist()
{
    uint8_t id[ToxPk::size] = {1};
    auto otherPk = ToxPk(id);
    groupMessageDispatcher->onMessageReceived(otherPk, false, "Test");
    QVERIFY(receivedMessages.size() == 1);

    groupSettings->setBlackList({otherPk.toString()});
    groupMessageDispatcher->onMessageReceived(otherPk, false, "Test");
    QVERIFY(receivedMessages.size() == 1);
}

/**
 * @brief The edits, deletions and reactions of a group point at the id toxcore gave a message
 * (stage 3, item 10): a sent message keeps the id tox_group_send_message handed back, a received
 * one the id of the callback, both as the packets carry them, four bytes big-endian.
 */
void TestGroupMessageDispatcher::messagesCarryTheIdsToxcoreGaveThem()
{
    groupMessageDispatcher->sendMessage(false, "Ours");
    QCOMPARE(sentMessages.size(), size_t{1});
    QCOMPARE(sentMessages.front().wireId, QByteArray::fromHex("0a0b0c0d"));

    uint8_t id[ToxPk::size] = {1};
    groupMessageDispatcher->onMessageReceived(ToxPk(id), false, "Theirs", 0xDEADBEEF);
    QCOMPARE(receivedMessages.size(), size_t{1});
    QCOMPARE(receivedMessages.front().wireId, QByteArray::fromHex("deadbeef"));

    // Without an id, nothing points at the message.
    groupMessageDispatcher->onMessageReceived(ToxPk(id), false, "No id");
    QVERIFY(receivedMessages.back().wireId.isEmpty());

    // Alone in the group nothing is sent, so nothing has an id either, until it goes.
    messageSender->ready = false;
    const auto alone = groupMessageDispatcher->sendMessage(false, "Alone");
    QVERIFY(sentMessages.back().wireId.isEmpty());
    memberJoins(*groupQuery, *g, *messageSender);
    QTRY_COMPARE_WITH_TIMEOUT(assignedWireIds.size(), size_t{1}, waitMs);
    QCOMPARE(assignedWireIds[0].first, alone.first);
    QCOMPARE(assignedWireIds[0].second, QByteArray::fromHex("0a0b0c0e"));
}

/**
 * @brief A received message that is shown goes back to its author as a delivery receipt with the
 * id toxcore gave it, as the Android and iOS clients confirm it. Nothing is confirmed for a message
 * of our own, of a blocked peer, or one without an id: there is nothing the author could match.
 */
void TestGroupMessageDispatcher::aShownMessageIsConfirmedToItsAuthor()
{
    uint8_t id[ToxPk::size] = {1};
    const ToxPk author(id);
    groupMessageDispatcher->onMessageReceived(author, false, "Theirs", 0xDEADBEEF);
    groupMessageDispatcher->onMessageReceived(author, true, "An action", 0x01020304);
    QCOMPARE(messageSender->receipts.size(), size_t{2});
    QCOMPARE(messageSender->receipts[0].groupId, static_cast<int>(g->getId()));
    QCOMPARE(messageSender->receipts[0].author, author);
    QCOMPARE(messageSender->receipts[0].messageId, uint32_t{0xDEADBEEF});
    QCOMPARE(messageSender->receipts[1].messageId, uint32_t{0x01020304});

    groupMessageDispatcher->onMessageReceived(author, false, "No id");
    QCOMPARE(messageSender->receipts.size(), size_t{2});

    uint8_t selfId[ToxPk::size] = {0};
    groupMessageDispatcher->onMessageReceived(ToxPk(selfId), false, "Ours", 0x0BADF00D);
    QCOMPARE(messageSender->receipts.size(), size_t{2});

    groupSettings->setBlackList({author.toString()});
    groupMessageDispatcher->onMessageReceived(author, false, "Blocked", 0x0BADF00D);
    QCOMPARE(messageSender->receipts.size(), size_t{2});
    QCOMPARE(receivedMessages.size(), size_t{3});
}

/**
 * @brief KHANDAQ (0.2.56): the field of a group no longer stops a text longer than one group
 * message; the dispatcher sends it in parts. Each part fits toxcore's group limit in UTF-8 bytes,
 * none cuts a character in two, and together they are the text, in order. A text without spaces
 * or line breaks is cut at the limit itself, so two- and four-byte characters meet the cut.
 */
void TestGroupMessageDispatcher::aLongTextGoesInPartsWithinTheGroupLimit()
{
    // The parameters Widget gives every group (groupMessageProcessorParams).
    MessageProcessor::SharedParams groupParams(Core::getMaxGroupMessageSize(), 10 * 1024 * 1024);
    MessageProcessor groupProcessor(groupParams);
    GroupMessageDispatcher dispatcher(*g, groupProcessor, *coreIdHandler, *messageSender,
                                      *groupSettings);
    const int limit = static_cast<int>(Core::getMaxGroupMessageSize());
    QCOMPARE(limit, TOX_GROUP_MAX_MESSAGE_LENGTH);

    QString words;
    for (int i = 0; i < 400; ++i) {
        words += QStringLiteral("Слово %1 word \U0001F600\n").arg(i);
    }
    // One ASCII byte in front shifts every later character off the even byte boundaries.
    QString solid = QStringLiteral("a");
    for (int i = 0; i < 1500; ++i) {
        solid += (i % 3 == 0) ? QStringLiteral("\U0001F468\u200D\u2695\uFE0F")
                              : QStringLiteral("жё");
    }

    for (const QString& text : {words, solid}) {
        messageSender->sentTexts.clear();
        const auto ids = dispatcher.sendMessage(false, text);

        const std::vector<QString>& parts = messageSender->sentTexts;
        QVERIFY(parts.size() > 1);
        QCOMPARE(static_cast<size_t>(ids.second.get() - ids.first.get() + 1), parts.size());
        QString joined;
        for (const QString& part : parts) {
            const QByteArray bytes = part.toUtf8();
            QVERIFY(!bytes.isEmpty());
            QVERIFY(bytes.size() <= limit);
            // A cut inside a character would not come back from UTF-8 as the same text.
            QCOMPARE(QString::fromUtf8(bytes), part);
            QVERIFY(!part.contains(QChar::ReplacementCharacter));
            QVERIFY(!part.at(part.size() - 1).isHighSurrogate());
            QVERIFY(!part.at(0).isLowSurrogate());
            joined += part;
        }
        QCOMPARE(joined, text);
    }
}

/**
 * @brief 0.2.56: the texts that waited go once a member is there, in the order they were written,
 * a grace after the member joined: toxcore drops a text from a peer it has no info for, and ours
 * reaches the member a moment after they are confirmed here. Each gets the id toxcore gave it then.
 */
void TestGroupMessageDispatcher::whatWaitedGoesInOrderAGraceAfterAMemberJoins()
{
    groupQuery->setAsEmptyGroup();
    g->regeneratePeerList();
    messageSender->ready = false;
    const auto first = groupMessageDispatcher->sendMessage(false, "First");
    const auto second = groupMessageDispatcher->sendMessage(false, "Second");

    memberJoins(*groupQuery, *g, *messageSender);
    // Not at once: the member has the grace first.
    QVERIFY(messageSender->sentTexts.empty());
    QCOMPARE(outgoingMessages.size(), size_t{2});

    QTRY_COMPARE_WITH_TIMEOUT(messageSender->sentTexts.size(), size_t{2}, waitMs);
    QCOMPARE(messageSender->sentTexts, texts({"First", "Second"}));
    QVERIFY(outgoingMessages.empty());
    QCOMPARE(assignedWireIds.size(), size_t{2});
    QCOMPARE(assignedWireIds[0].first, first.first);
    QCOMPARE(assignedWireIds[0].second, QByteArray::fromHex("0a0b0c0d"));
    QCOMPARE(assignedWireIds[1].first, second.first);
    QCOMPARE(assignedWireIds[1].second, QByteArray::fromHex("0a0b0c0e"));
    // Shown without an id, as nothing pointed at them before they went.
    QVERIFY(sentMessages[0].wireId.isEmpty());
    QVERIFY(sentMessages[1].wireId.isEmpty());
}

/**
 * @brief The group that connects reads its peer list again with nobody new in it (the members it
 * had confirmed while it was connecting): what waited goes then too.
 */
void TestGroupMessageDispatcher::whatWaitedGoesOnceTheGroupConnects()
{
    messageSender->ready = false;
    groupMessageDispatcher->sendMessage(false, "Hello");
    QCOMPARE(outgoingMessages.size(), size_t{1});

    messageSender->ready = true;
    g->regeneratePeerList();
    QTRY_COMPARE_WITH_TIMEOUT(messageSender->sentTexts, texts({"Hello"}), waitMs);
    QVERIFY(outgoingMessages.empty());
}

/**
 * @brief A text written while others wait goes behind them: it tries them first and goes after
 * them, or waits behind them; while a member who just joined has the grace, it waits for it too.
 */
void TestGroupMessageDispatcher::aNewTextGoesBehindTheOnesThatWait()
{
    messageSender->ready = false;
    groupMessageDispatcher->sendMessage(false, "A");
    messageSender->ready = true;
    groupMessageDispatcher->sendMessage(false, "B");
    QCOMPARE(messageSender->sentTexts, texts({"A", "B"}));
    QVERIFY(outgoingMessages.empty());

    messageSender->ready = false;
    groupMessageDispatcher->sendMessage(false, "C");
    groupMessageDispatcher->sendMessage(false, "D");
    QCOMPARE(outgoingMessages.size(), size_t{2});

    memberJoins(*groupQuery, *g, *messageSender);
    groupMessageDispatcher->sendMessage(false, "E");
    QCOMPARE(messageSender->sentTexts, texts({"A", "B"}));
    QTRY_COMPARE_WITH_TIMEOUT(messageSender->sentTexts, texts({"A", "B", "C", "D", "E"}), waitMs);
    QVERIFY(outgoingMessages.empty());
}

/**
 * @brief A text that cannot go as it is (too long) fails, waiting or not; the ones behind it go.
 */
void TestGroupMessageDispatcher::aTextThatCannotGoDoesNotHoldTheOthers()
{
    messageSender->refused = QStringList{QStringLiteral("Too long"), QStringLiteral("Refused")};
    messageSender->ready = false;
    groupMessageDispatcher->sendMessage(false, "Before");
    const auto tooLong = groupMessageDispatcher->sendMessage(false, "Too long");
    groupMessageDispatcher->sendMessage(false, "After");
    QVERIFY(brokenMessages.empty());

    memberJoins(*groupQuery, *g, *messageSender);
    QTRY_COMPARE_WITH_TIMEOUT(messageSender->sentTexts, texts({"Before", "After"}), waitMs);
    QCOMPARE(brokenMessages, std::vector<DispatchedMessageId>{tooLong.first});
    QCOMPARE(outgoingMessages, std::set<DispatchedMessageId>{tooLong.first});

    // Ready, it fails at once and does not wait.
    const auto refused = groupMessageDispatcher->sendMessage(false, "Refused");
    groupMessageDispatcher->sendMessage(false, "Fine");
    QCOMPARE(brokenMessages.size(), size_t{2});
    QCOMPARE(brokenMessages[1], refused.first);
    QCOMPARE(messageSender->sentTexts, texts({"Before", "After", "Fine"}));
}

/**
 * @brief The parts of a long text that waits go in the order of the text.
 */
void TestGroupMessageDispatcher::theWaitingPartsOfALongTextGoInOrder()
{
    MessageProcessor::SharedParams groupParams(Core::getMaxGroupMessageSize(), 10 * 1024 * 1024);
    MessageProcessor groupProcessor(groupParams);
    GroupMessageDispatcher dispatcher(*g, groupProcessor, *coreIdHandler, *messageSender,
                                      *groupSettings, testGraceMs);
    QString text;
    for (int i = 0; i < 400; ++i) {
        text += QStringLiteral("Part of the text %1, ").arg(i);
    }
    messageSender->ready = false;
    const auto ids = dispatcher.sendMessage(false, text);
    QVERIFY(ids.second.get() > ids.first.get());
    QVERIFY(messageSender->sentTexts.empty());

    memberJoins(*groupQuery, *g, *messageSender);
    const size_t parts = static_cast<size_t>(ids.second.get() - ids.first.get() + 1);
    QTRY_COMPARE_WITH_TIMEOUT(messageSender->sentTexts.size(), parts, waitMs);
    QString joined;
    for (const QString& part : messageSender->sentTexts) {
        joined += part;
    }
    QCOMPARE(joined, text);
}

/**
 * @brief The first member who comes into a group we were alone in has the grace from the texts
 * written then too, though none waited; once the group has someone else, a text goes at once.
 */
void TestGroupMessageDispatcher::aTextRightAfterTheFirstMemberCameWaitsForTheGrace()
{
    groupQuery->setAsEmptyGroup();
    g->regeneratePeerList();
    memberJoins(*groupQuery, *g, *messageSender);

    groupMessageDispatcher->sendMessage(false, "Hi");
    QVERIFY(messageSender->sentTexts.empty());
    QCOMPARE(outgoingMessages.size(), size_t{1});
    QTRY_COMPARE_WITH_TIMEOUT(messageSender->sentTexts, texts({"Hi"}), waitMs);
    QVERIFY(outgoingMessages.empty());

    // The peer list read again with the member still in it: no grace.
    g->regeneratePeerList();
    groupMessageDispatcher->sendMessage(false, "Again");
    QCOMPARE(messageSender->sentTexts, texts({"Hi", "Again"}));
}

/**
 * @brief 0.2.56: the group goes from the window while its texts wait, in the grace of a member who
 * joined (Widget::discardGroup, as the app closes): nothing of them goes after, neither at the end
 * of the grace nor with a later text, and none fails: they stay pending for the next start.
 */
void TestGroupMessageDispatcher::whatWaitsGoesNoMoreOnceTheGroupGoes()
{
    groupQuery->setAsEmptyGroup();
    g->regeneratePeerList();
    messageSender->ready = false;
    groupMessageDispatcher->sendMessage(false, "First");
    groupMessageDispatcher->sendMessage(false, "Second");
    memberJoins(*groupQuery, *g, *messageSender);

    groupMessageDispatcher->dropWaiting(false);
    QTest::qWait(testGraceMs * 4);
    QVERIFY(messageSender->sentTexts.empty());
    QVERIFY(brokenMessages.empty());
    QCOMPARE(outgoingMessages.size(), size_t{2});

    groupMessageDispatcher->sendMessage(false, "Third");
    QCOMPARE(messageSender->sentTexts, texts({"Third"}));
}

/**
 * @brief A group left or deleted while its texts wait takes them along: each fails, and the history
 * keeps none of them pending for a later start or join of the group to send.
 */
void TestGroupMessageDispatcher::whatWaitsFailsWhenTheGroupIsLeft()
{
    messageSender->ready = false;
    const auto first = groupMessageDispatcher->sendMessage(false, "First");
    const auto second = groupMessageDispatcher->sendMessage(true, "Second");
    QCOMPARE(outgoingMessages.size(), size_t{2});

    groupMessageDispatcher->dropWaiting(true);
    QCOMPARE(brokenMessages, (std::vector<DispatchedMessageId>{first.first, second.first}));
    memberJoins(*groupQuery, *g, *messageSender);
    QTest::qWait(testGraceMs * 4);
    QVERIFY(messageSender->sentTexts.empty());
    QCOMPARE(messageSender->numSentActions, size_t{0});
}

/**
 * @brief 0.2.56: a member who joins in the grace of another has it in full too: what waits reaches
 * them a grace after they joined, not as the grace of the first one ends.
 */
void TestGroupMessageDispatcher::aMemberWhoJoinsInTheGraceHasItInFull()
{
    // Long enough for the second member to join in it on a busy machine.
    constexpr int graceMs = 300;
    GroupMessageDispatcher dispatcher(*g, *messageProcessor, *coreIdHandler, *messageSender,
                                      *groupSettings, graceMs);
    QElapsedTimer sinceSecond;
    sinceSecond.start();
    qint64 wentAfterSecondMs = -1;
    connect(&dispatcher, &GroupMessageDispatcher::messageComplete, this,
            [&sinceSecond, &wentAfterSecondMs] { wentAfterSecondMs = sinceSecond.elapsed(); });
    groupQuery->setAsEmptyGroup();
    g->regeneratePeerList();
    messageSender->ready = false;
    dispatcher.sendMessage(false, "Hello");

    memberJoins(*groupQuery, *g, *messageSender);
    QTest::qWait(graceMs / 3);
    QVERIFY(messageSender->sentTexts.empty());
    groupQuery->setOthers(2);
    g->regeneratePeerList();
    sinceSecond.start();

    QTRY_COMPARE_WITH_TIMEOUT(messageSender->sentTexts, texts({"Hello"}), waitMs);
    // A coarse QTimer may fire up to 5 % early.
    QVERIFY2(wentAfterSecondMs >= graceMs * 9 / 10, qPrintable(QString::number(wentAfterSecondMs)));
}

/**
 * @brief Members who keep joining put what waits off a few graces at most (joinGraceLimit).
 */
void TestGroupMessageDispatcher::aStreamOfJoinsDoesNotHoldWhatWaitsForGood()
{
    groupQuery->setAsEmptyGroup();
    g->regeneratePeerList();
    messageSender->ready = false;
    groupMessageDispatcher->sendMessage(false, "Hello");
    memberJoins(*groupQuery, *g, *messageSender);

    QElapsedTimer since;
    since.start();
    for (uint32_t others = 2; messageSender->sentTexts.empty() && since.elapsed() < waitMs;
         ++others) {
        QTest::qWait(testGraceMs / 2);
        groupQuery->setOthers(others);
        g->regeneratePeerList();
    }
    QCOMPARE(messageSender->sentTexts, texts({"Hello"}));
    QVERIFY(outgoingMessages.empty());
}

// Cannot be guiless due to a settings instance in GroupMessageDispatcher
/**
 * @brief QA of 0.2.56 (H1): a text that toxcore takes while a file of ours goes into the group
 * waits for a member to confirm it. The file can stall the members' connections for good, and
 * toxcore takes texts for them all the same: such a text was shown as sent and never arrived. Its
 * own receipt makes it sent, not that of another text.
 */
void TestGroupMessageDispatcher::aTextThatWentWithAFileWaitsForAMemberToConfirmIt()
{
    messageSender->fileGoes = true;
    const uint32_t firstWireId = messageSender->nextWireId;
    const auto first = groupMessageDispatcher->sendMessage(false, "with the file");
    groupMessageDispatcher->sendMessage(false, "and another");
    QCOMPARE(messageSender->numSentMessages, size_t{2});
    QCOMPARE(outgoingMessages.size(), size_t{2});

    groupMessageDispatcher->onDeliveryReceipt(firstWireId + 1);
    QCOMPARE(outgoingMessages, std::set<DispatchedMessageId>{first.first});
    // A second receipt, or one for a text that waits for none, changes nothing.
    groupMessageDispatcher->onDeliveryReceipt(firstWireId + 1);
    groupMessageDispatcher->onDeliveryReceipt(firstWireId + 7);
    QCOMPARE(outgoingMessages, std::set<DispatchedMessageId>{first.first});
    groupMessageDispatcher->onDeliveryReceipt(firstWireId);
    QVERIFY(outgoingMessages.empty());
    QVERIFY(brokenMessages.empty());

    // Without a file, a text is sent once toxcore took it, as before.
    messageSender->fileGoes = false;
    groupMessageDispatcher->sendMessage(false, "after the file");
    QVERIFY(outgoingMessages.empty());
}

/**
 * @brief A text that waited for the group and went while a file goes waits for a confirmation too.
 */
void TestGroupMessageDispatcher::aTextThatWaitedAndWentWithAFileWaitsToo()
{
    messageSender->ready = false;
    messageSender->fileGoes = true;
    groupMessageDispatcher->sendMessage(false, "waits");
    QCOMPARE(messageSender->numSentMessages, size_t{0});
    const uint32_t wireId = messageSender->nextWireId;
    memberJoins(*groupQuery, *g, *messageSender);
    QTRY_COMPARE_WITH_TIMEOUT(messageSender->numSentMessages, size_t{1}, waitMs);
    QCOMPARE(outgoingMessages.size(), size_t{1});

    groupMessageDispatcher->onDeliveryReceipt(wireId);
    QVERIFY(outgoingMessages.empty());
}

/**
 * @brief toxcore times out the last member before any confirmed the texts that went with a file:
 * a member that had one would have confirmed it, as toxcore times a stalled member out a minute
 * later. They reached nobody, and fail. A text confirmed before stays sent.
 */
void TestGroupMessageDispatcher::aTextThatWentWithAFileFailsWhenTheLastMemberTimesOut()
{
    messageSender->fileGoes = true;
    const uint32_t firstWireId = messageSender->nextWireId;
    groupMessageDispatcher->sendMessage(false, "arrived");
    const auto lost = groupMessageDispatcher->sendMessage(false, "lost with the file");
    groupMessageDispatcher->onDeliveryReceipt(firstWireId);
    QCOMPARE(outgoingMessages.size(), size_t{1});

    groupMessageDispatcher->onMembersTimedOut();
    QCOMPARE(brokenMessages, std::vector<DispatchedMessageId>{lost.first});
    // Nothing waits any more: neither a late receipt nor the end of the wait sends it.
    groupMessageDispatcher->onDeliveryReceipt(firstWireId + 1);
    QTest::qWait(testConfirmMs + 200);
    QCOMPARE(outgoingMessages, std::set<DispatchedMessageId>{lost.first});
}

/**
 * @brief A text that went with a file and that nobody confirms is sent once toxcore would have
 * timed out a stalled member, as one that has a member which does not send receipts.
 */
void TestGroupMessageDispatcher::aTextNobodyConfirmsIsSentAfterAWhile()
{
    messageSender->fileGoes = true;
    QElapsedTimer waited;
    waited.start();
    groupMessageDispatcher->sendMessage(false, "to members that send no receipts");
    QTest::qWait(testConfirmMs / 4);
    groupMessageDispatcher->sendMessage(false, "a while later");
    QCOMPARE(outgoingMessages.size(), size_t{2});

    QTRY_COMPARE_WITH_TIMEOUT(outgoingMessages.size(), size_t{1}, waitMs);
    QVERIFY(waited.elapsed() >= testConfirmMs - 50);
    QTRY_VERIFY_WITH_TIMEOUT(outgoingMessages.empty(), waitMs);
    QVERIFY(waited.elapsed() >= testConfirmMs + testConfirmMs / 4 - 50);
    QVERIFY(brokenMessages.empty());
    // A time-out after that breaks nothing.
    groupMessageDispatcher->onMembersTimedOut();
    QVERIFY(brokenMessages.empty());
}

/**
 * @brief The group leaves this window: a text that waits for a confirmation went to toxcore and is
 * sent, so that no later start sends it again.
 */
void TestGroupMessageDispatcher::aTextThatWaitsForAConfirmationIsSentWhenTheGroupGoes()
{
    messageSender->fileGoes = true;
    groupMessageDispatcher->sendMessage(false, "went");
    QCOMPARE(outgoingMessages.size(), size_t{1});
    groupMessageDispatcher->dropWaiting(true);
    QVERIFY(outgoingMessages.empty());
    QVERIFY(brokenMessages.empty());
}

QTEST_GUILESS_MAIN(TestGroupMessageDispatcher)
#include "groupmessagedispatcher_test.moc"
