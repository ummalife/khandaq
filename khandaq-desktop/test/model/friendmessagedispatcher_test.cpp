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

#include "src/core/icorefriendmessagesender.h"
#include "src/model/friend.h"
#include "src/model/friendmessagedispatcher.h"
#include "src/model/message.h"

#include <QObject>
#include <QtTest/QtTest>

#include <set>
#include <deque>

namespace {
constexpr uint64_t testMaxExtendedMessageSize = 10 * 1024 * 1024;

/**
 * @brief Short waits for the tests of the receipt retries: a receipt is due after receiptTimeoutMs,
 * and a message is sent at most twice more before it is reported as not sent.
 */
FriendMessageRetryPolicy fastRetryPolicy(int receiptTimeoutMs)
{
    FriendMessageRetryPolicy policy;
    policy.receiptTimeoutMs = receiptTimeoutMs;
    policy.maxRetries = 2;
    policy.retryBaseMs = 5;
    policy.retryMaxMs = 10;
    return policy;
}
}

class MockCoreExtPacket : public ICoreExtPacket
{
public:

    MockCoreExtPacket(uint64_t& numSentMessages_, uint64_t& currentReceiptId_)
        : numSentMessages(numSentMessages_)
        , currentReceiptId(currentReceiptId_)
    {}

    uint64_t addExtendedMessage(QString message_) override;

    bool send() override;

    uint64_t& numSentMessages;
    uint64_t& currentReceiptId;
    QDateTime senderTimestamp;
    QString message;
};

uint64_t MockCoreExtPacket::addExtendedMessage(QString message_)
{
    message = message_;
    return currentReceiptId++;
}

bool MockCoreExtPacket::send()
{
    numSentMessages++;
    return true;
}

class MockCoreExtPacketAllocator : public ICoreExtPacketAllocator
{
public:
    std::unique_ptr<ICoreExtPacket> getPacket(uint32_t friendId) override;

    uint64_t numSentMessages = 0;
    uint64_t currentReceiptId = 0;
};

std::unique_ptr<ICoreExtPacket> MockCoreExtPacketAllocator::getPacket(uint32_t friendId)
{
    std::ignore = friendId;
    return std::unique_ptr<MockCoreExtPacket>(new MockCoreExtPacket(numSentMessages, currentReceiptId));
}

class MockFriendMessageSender : public ICoreFriendMessageSender
{
public:
    bool sendAction(uint32_t friendId, const QString& action, ReceiptNum& receipt) override;

    bool sendMessage(uint32_t friendId, const QString& message, ReceiptNum& receipt) override;

    bool canSend = true;
    ReceiptNum receiptNum{0};
    size_t numSentActions = 0;
    size_t numSentMessages = 0;
};

bool MockFriendMessageSender::sendAction(uint32_t friendId, const QString& action, ReceiptNum& receipt)
{
    std::ignore = friendId;
    std::ignore = action;
    if (canSend) {
        numSentActions++;
        receipt = receiptNum;
        receiptNum.get() += 1;
    }
    return canSend;
}

bool MockFriendMessageSender::sendMessage(uint32_t friendId, const QString& message, ReceiptNum& receipt)
{
    std::ignore = friendId;
    std::ignore = message;
    if (canSend) {
        numSentMessages++;
        receipt = receiptNum;
        receiptNum.get() += 1;
    }
    return canSend;
}

class TestFriendMessageDispatcher : public QObject
{
    Q_OBJECT

public:
    TestFriendMessageDispatcher();

private slots:
    void init();
    void testSignals();
    void testMessageSending();
    void testOfflineMessages();
    void testFailedMessage();
    void testNegotiationFailure();
    void testNegotiationSuccess();
    void testOfflineExtensionMessages();
    void testSentMessageExtensionSetReduced();
    void testActionMessagesSplitWithExtensions();
    void testReceiptTimeoutGivesUp();
    void testReceiptAfterRetryCompletes();
    void testReconnectKeepsOneRetryChain();
    void testReceiptTimeoutWhileOffline();
    void testCancelledMessagesAreNotSentAgain();

    void onMessageSent(DispatchedMessageId id, Message message)
    {
        auto it = outgoingMessages.find(id);
        QVERIFY(it == outgoingMessages.end());
        outgoingMessages.emplace(id, std::move(message));
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

    void onMessageBroken(DispatchedMessageId id, BrokenMessageReason reason)
    {
        std::ignore = reason;
        brokenMessages.insert(id);
        ++numBrokenSignals;
    }

private:
    void recreateDispatcher(FriendMessageRetryPolicy retryPolicy);

    // All unique_ptrs to make construction/init() easier to manage
    std::unique_ptr<Friend> f;
    std::unique_ptr<MockFriendMessageSender> messageSender;
    std::unique_ptr<MockCoreExtPacketAllocator> coreExtPacketAllocator;
    std::unique_ptr<MessageProcessor::SharedParams> sharedProcessorParams;
    std::unique_ptr<MessageProcessor> messageProcessor;
    std::unique_ptr<FriendMessageDispatcher> friendMessageDispatcher;
    std::map<DispatchedMessageId, Message> outgoingMessages;
    std::set<DispatchedMessageId> brokenMessages;
    size_t numBrokenSignals = 0;
    std::deque<Message> receivedMessages;
};

TestFriendMessageDispatcher::TestFriendMessageDispatcher() {}

/**
 * @brief Test initialization. Resets all member variables for a fresh test state
 */
void TestFriendMessageDispatcher::init()
{
    f = std::unique_ptr<Friend>(new Friend(0, ToxPk()));
    f->setStatus(Status::Status::Online);
    f->onNegotiationComplete();
    messageSender = std::unique_ptr<MockFriendMessageSender>(new MockFriendMessageSender());
    coreExtPacketAllocator = std::unique_ptr<MockCoreExtPacketAllocator>(new MockCoreExtPacketAllocator());
    sharedProcessorParams =
        std::unique_ptr<MessageProcessor::SharedParams>(new MessageProcessor::SharedParams(tox_max_message_length(), testMaxExtendedMessageSize));

    messageProcessor = std::unique_ptr<MessageProcessor>(new MessageProcessor(*sharedProcessorParams));
    recreateDispatcher(FriendMessageRetryPolicy());

    outgoingMessages = std::map<DispatchedMessageId, Message>();
    receivedMessages = std::deque<Message>();
    brokenMessages = std::set<DispatchedMessageId>();
    numBrokenSignals = 0;
}

void TestFriendMessageDispatcher::recreateDispatcher(FriendMessageRetryPolicy retryPolicy)
{
    friendMessageDispatcher = std::unique_ptr<FriendMessageDispatcher>(
        new FriendMessageDispatcher(*f, *messageProcessor, *messageSender, *coreExtPacketAllocator,
                                    retryPolicy));

    connect(friendMessageDispatcher.get(), &FriendMessageDispatcher::messageSent, this,
            &TestFriendMessageDispatcher::onMessageSent);
    connect(friendMessageDispatcher.get(), &FriendMessageDispatcher::messageComplete, this,
            &TestFriendMessageDispatcher::onMessageComplete);
    connect(friendMessageDispatcher.get(), &FriendMessageDispatcher::messageReceived, this,
            &TestFriendMessageDispatcher::onMessageReceived);
    connect(friendMessageDispatcher.get(), &FriendMessageDispatcher::messageBroken, this,
            &TestFriendMessageDispatcher::onMessageBroken);
}

/**
 * @brief Tests that the signals emitted by the dispatcher are all emitted at the correct times
 */
void TestFriendMessageDispatcher::testSignals()
{
    auto startReceiptNum = messageSender->receiptNum;
    auto sentIds = friendMessageDispatcher->sendMessage(false, "test");
    auto endReceiptNum = messageSender->receiptNum;

    // We should have received some message ids in our callbacks
    QVERIFY(sentIds.first == sentIds.second);
    QVERIFY(outgoingMessages.find(sentIds.first) != outgoingMessages.end());
    QVERIFY(startReceiptNum.get() != endReceiptNum.get());
    QVERIFY(outgoingMessages.size() == 1);

    QVERIFY(outgoingMessages.begin()->second.isAction == false);
    QVERIFY(outgoingMessages.begin()->second.content == "test");

    for (auto i = startReceiptNum; i < endReceiptNum; ++i.get()) {
        friendMessageDispatcher->onReceiptReceived(i);
    }

    // If our completion ids were hooked up right this should be empty
    QVERIFY(outgoingMessages.empty());

    // If signals are emitted correctly we should have one message in our received message buffer
    QVERIFY(receivedMessages.empty());
    friendMessageDispatcher->onMessageReceived(false, "test2");

    QVERIFY(!receivedMessages.empty());
    QVERIFY(receivedMessages.front().isAction == false);
    QVERIFY(receivedMessages.front().content == "test2");
}

/**
 * @brief Tests that sent messages actually go through to core
 */
void TestFriendMessageDispatcher::testMessageSending()
{
    friendMessageDispatcher->sendMessage(false, "Test");

    QVERIFY(messageSender->numSentMessages == 1);
    QVERIFY(messageSender->numSentActions == 0);

    friendMessageDispatcher->sendMessage(true, "Test");

    QVERIFY(messageSender->numSentMessages == 1);
    QVERIFY(messageSender->numSentActions == 1);
}

/**
 * @brief Tests that messages dispatched while a friend is offline are sent later
 */
void TestFriendMessageDispatcher::testOfflineMessages()
{
    f->setStatus(Status::Status::Offline);
    auto firstReceipt = messageSender->receiptNum;

    friendMessageDispatcher->sendMessage(false, "test");
    friendMessageDispatcher->sendMessage(false, "test2");
    friendMessageDispatcher->sendMessage(true, "test3");

    QVERIFY(messageSender->numSentActions == 0);
    QVERIFY(messageSender->numSentMessages == 0);
    QVERIFY(outgoingMessages.size() == 3);

    f->setStatus(Status::Status::Online);
    f->onNegotiationComplete();

    QVERIFY(messageSender->numSentActions == 1);
    QVERIFY(messageSender->numSentMessages == 2);
    QVERIFY(outgoingMessages.size() == 3);

    auto lastReceipt = messageSender->receiptNum;
    for (auto i = firstReceipt; i < lastReceipt; ++i.get()) {
        friendMessageDispatcher->onReceiptReceived(i);
    }

    QVERIFY(messageSender->numSentActions == 1);
    QVERIFY(messageSender->numSentMessages == 2);
    QVERIFY(outgoingMessages.size() == 0);
}

/**
 * @brief Tests that messages that failed to send due to toxcore are resent later
 */
void TestFriendMessageDispatcher::testFailedMessage()
{
    messageSender->canSend = false;

    friendMessageDispatcher->sendMessage(false, "test");

    QVERIFY(messageSender->numSentMessages == 0);

    messageSender->canSend = true;
    f->setStatus(Status::Status::Offline);
    f->setStatus(Status::Status::Online);
    f->onNegotiationComplete();

    QVERIFY(messageSender->numSentMessages == 1);
}

void TestFriendMessageDispatcher::testNegotiationFailure()
{
    f->setStatus(Status::Status::Offline);
    f->setStatus(Status::Status::Online);

    QVERIFY(f->getStatus() == Status::Status::Negotiating);

    friendMessageDispatcher->sendMessage(false, "test");

    QVERIFY(messageSender->numSentMessages == 0);

    f->onNegotiationComplete();

    QVERIFY(messageSender->numSentMessages == 1);
}

/**
 * @brief Since v0.2.6 outgoing messages take the Tox core path, also when the friend negotiated
 * extended messages
 */
void TestFriendMessageDispatcher::testNegotiationSuccess()
{
    f->setStatus(Status::Status::Offline);
    f->setStatus(Status::Status::Online);

    f->setExtendedMessageSupport(true);
    f->onNegotiationComplete();

    friendMessageDispatcher->sendMessage(false, "test");
    QVERIFY(messageSender->numSentMessages == 1);

    friendMessageDispatcher->sendMessage(false, "test");
    QVERIFY(messageSender->numSentMessages == 2);
    QVERIFY(coreExtPacketAllocator->numSentMessages == 0);
}

/**
 * @brief A message queued with required extensions goes over the core path once the friend is
 * online, whether or not the friend supports the extensions; nothing is dropped
 */
void TestFriendMessageDispatcher::testOfflineExtensionMessages()
{
    f->setStatus(Status::Status::Offline);

    auto requiredExtensions = ExtensionSet();
    requiredExtensions[ExtensionType::messages] = true;

    const auto firstReceipt = messageSender->receiptNum;
    friendMessageDispatcher->sendExtendedMessage("Test", requiredExtensions);
    QVERIFY(messageSender->numSentMessages == 0);

    f->setStatus(Status::Status::Online);
    f->setExtendedMessageSupport(true);
    f->onNegotiationComplete();

    QVERIFY(messageSender->numSentMessages == 1);
    friendMessageDispatcher->onReceiptReceived(firstReceipt);
    QVERIFY(outgoingMessages.empty());

    f->setStatus(Status::Status::Offline);

    friendMessageDispatcher->sendExtendedMessage("Test", requiredExtensions);

    f->setStatus(Status::Status::Online);
    f->setExtendedMessageSupport(false);
    f->onNegotiationComplete();

    QVERIFY(messageSender->numSentMessages == 2);
    QVERIFY(coreExtPacketAllocator->numSentMessages == 0);
    QVERIFY(brokenMessages.empty());
}

/**
 * @brief A sent message still waiting for its receipt is sent again after a reconnect, whatever
 * extensions the friend supports then, and is not reported as broken
 */
void TestFriendMessageDispatcher::testSentMessageExtensionSetReduced()
{
    f->setStatus(Status::Status::Online);
    f->setExtendedMessageSupport(true);
    f->onNegotiationComplete();

    friendMessageDispatcher->sendMessage(false, "Test");
    QVERIFY(messageSender->numSentMessages == 1);

    f->setStatus(Status::Status::Offline);
    f->setStatus(Status::Status::Online);
    f->setExtendedMessageSupport(false);
    f->onNegotiationComplete();

    QVERIFY(messageSender->numSentMessages == 2);
    QVERIFY(outgoingMessages.size() == 1);
    QVERIFY(brokenMessages.empty());
}

void TestFriendMessageDispatcher::testActionMessagesSplitWithExtensions()
{
    f->setStatus(Status::Status::Online);
    f->setExtendedMessageSupport(true);
    f->onNegotiationComplete();

    auto reallyLongMessage = QString("a");

    for (uint64_t i = 0; i < testMaxExtendedMessageSize + 50; ++i) {
        reallyLongMessage += QString().number(i);
    }

    friendMessageDispatcher->sendMessage(true, reallyLongMessage);

    QVERIFY(coreExtPacketAllocator->numSentMessages == 0);
    QVERIFY(messageSender->numSentMessages == 0);
    QVERIFY(messageSender->numSentActions > 1);
}

/**
 * @brief A message without a receipt is sent again only as many times as the policy allows, then it
 * is reported as not sent, once, and leaves the queue: a reconnect does not send it again
 */
void TestFriendMessageDispatcher::testReceiptTimeoutGivesUp()
{
    recreateDispatcher(fastRetryPolicy(30));

    const auto sentIds = friendMessageDispatcher->sendMessage(false, "test");
    QCOMPARE(messageSender->numSentMessages, size_t(1));

    QTRY_COMPARE_WITH_TIMEOUT(messageSender->numSentMessages, size_t(3), 5000);
    QTRY_COMPARE_WITH_TIMEOUT(brokenMessages.count(sentIds.first), size_t(1), 5000);

    QTest::qWait(200);
    QCOMPARE(messageSender->numSentMessages, size_t(3));
    QCOMPARE(numBrokenSignals, size_t(1));

    f->setStatus(Status::Status::Offline);
    f->setStatus(Status::Status::Online);
    f->onNegotiationComplete();

    QCOMPARE(messageSender->numSentMessages, size_t(3));
    QCOMPARE(numBrokenSignals, size_t(1));
}

/**
 * @brief The receipt of a resent message completes it, and nothing is sent after that
 */
void TestFriendMessageDispatcher::testReceiptAfterRetryCompletes()
{
    recreateDispatcher(fastRetryPolicy(250));

    const auto firstReceipt = messageSender->receiptNum;
    friendMessageDispatcher->sendMessage(false, "test");

    QTRY_COMPARE_WITH_TIMEOUT(messageSender->numSentMessages, size_t(2), 5000);
    friendMessageDispatcher->onReceiptReceived(ReceiptNum{firstReceipt.get() + 1});
    QVERIFY(outgoingMessages.empty());

    QTest::qWait(600);
    QCOMPARE(messageSender->numSentMessages, size_t(2));
    QVERIFY(brokenMessages.empty());
}

/**
 * @brief The wait of a send that a reconnect replaced does not start a second chain of resends:
 * the message goes out once per retry and is reported as not sent once
 */
void TestFriendMessageDispatcher::testReconnectKeepsOneRetryChain()
{
    recreateDispatcher(fastRetryPolicy(30));

    const auto sentIds = friendMessageDispatcher->sendMessage(false, "test");
    f->setStatus(Status::Status::Offline);
    f->setStatus(Status::Status::Online);
    f->onNegotiationComplete();
    QCOMPARE(messageSender->numSentMessages, size_t(2));

    // The send, the resend after the reconnect and two retries
    QTRY_COMPARE_WITH_TIMEOUT(brokenMessages.count(sentIds.first), size_t(1), 5000);
    QTest::qWait(200);
    QCOMPARE(messageSender->numSentMessages, size_t(4));
    QCOMPARE(numBrokenSignals, size_t(1));
}

/**
 * @brief A receipt that is due while the friend is offline neither resends nor fails the message;
 * the reconnect sends it
 */
void TestFriendMessageDispatcher::testReceiptTimeoutWhileOffline()
{
    recreateDispatcher(fastRetryPolicy(30));

    friendMessageDispatcher->sendMessage(false, "test");
    f->setStatus(Status::Status::Offline);

    QTest::qWait(200);
    QCOMPARE(messageSender->numSentMessages, size_t(1));
    QVERIFY(brokenMessages.empty());

    f->setStatus(Status::Status::Online);
    f->onNegotiationComplete();
    QCOMPARE(messageSender->numSentMessages, size_t(2));
    QCOMPARE(outgoingMessages.size(), size_t(1));
}

/**
 * @brief Tests that a message taken back while the friend is offline is not sent when they come
 * online, whether it never left or was sent and still waited for its receipt, and that neither
 * completion nor failure is reported for it
 */
void TestFriendMessageDispatcher::testCancelledMessagesAreNotSentAgain()
{
    const auto sentBefore = friendMessageDispatcher->sendMessage(false, "sent before").first;
    QCOMPARE(messageSender->numSentMessages, size_t(1));

    f->setStatus(Status::Status::Offline);
    const auto waiting = friendMessageDispatcher->sendMessage(false, "waiting").first;
    const auto kept = friendMessageDispatcher->sendMessage(false, "kept").first;

    QVERIFY(friendMessageDispatcher->cancelMessage(sentBefore));
    QVERIFY(friendMessageDispatcher->cancelMessage(waiting));

    f->setStatus(Status::Status::Online);
    f->onNegotiationComplete();

    // Only "kept" goes out again.
    QCOMPARE(messageSender->numSentMessages, size_t(2));

    for (auto i = ReceiptNum(0); i < messageSender->receiptNum; ++i.get()) {
        friendMessageDispatcher->onReceiptReceived(i);
    }

    QVERIFY(outgoingMessages.find(kept) == outgoingMessages.end());
    QVERIFY(outgoingMessages.find(sentBefore) != outgoingMessages.end());
    QVERIFY(outgoingMessages.find(waiting) != outgoingMessages.end());
    QCOMPARE(numBrokenSignals, size_t(0));
}

QTEST_GUILESS_MAIN(TestFriendMessageDispatcher)
#include "friendmessagedispatcher_test.moc"
