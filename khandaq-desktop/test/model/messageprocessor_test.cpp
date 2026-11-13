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

#include "src/model/message.h"

#include <tox/tox.h>

#include <QObject>
#include <QStringBuilder>
#include <QtTest/QtTest>

namespace {
bool messageHasSelfMention(const Message& message)
{
    return std::any_of(message.metadata.begin(), message.metadata.end(), [](MessageMetadata meta) {
        return meta.type == MessageMetadataType::selfMention;
    });
}
} // namespace

class TestMessageProcessor : public QObject
{
    Q_OBJECT

public:
    TestMessageProcessor(){}

private slots:
    void testSelfMention();
    void testReplyIsNoMention();
    void testOutgoingMessage();
    void testIncomingMessage();
};


/**
 * @brief Tests detection of username
 */
void TestMessageProcessor::testSelfMention()
{
    MessageProcessor::SharedParams sharedParams(tox_max_message_length(), 10 * 1024 * 1024);;
    const QLatin1String testUserName{"MyUserName"};
    const QLatin1String testToxPk{
        "0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"};
    sharedParams.onUserNameSet(testUserName);
    sharedParams.setPublicKey(testToxPk);

    auto messageProcessor = MessageProcessor(sharedParams);
    messageProcessor.enableMentions();

    for (const auto& str : {testUserName, testToxPk}) {

        // Using my name or public key should match
        auto processedMessage = messageProcessor.processIncomingCoreMessage(false, str % " hi");
        QVERIFY(messageHasSelfMention(processedMessage));

        // Action messages should match too
        processedMessage = messageProcessor.processIncomingCoreMessage(true, str % " hi");
        QVERIFY(messageHasSelfMention(processedMessage));

        // Too much text shouldn't match
        processedMessage = messageProcessor.processIncomingCoreMessage(false, str % "2");
        QVERIFY(!messageHasSelfMention(processedMessage));

        // Unless it's a colon
        processedMessage = messageProcessor.processIncomingCoreMessage(false, str % ": test");
        QVERIFY(messageHasSelfMention(processedMessage));

        // remove last character
        QString chopped = str;
        chopped.chop(1);
        // Too little text shouldn't match
        processedMessage = messageProcessor.processIncomingCoreMessage(false, chopped);
        QVERIFY(!messageHasSelfMention(processedMessage));

        // make lower case
        QString lower = QString(str).toLower();

        // The regex should be case insensitive
        processedMessage = messageProcessor.processIncomingCoreMessage(false, lower % " hi");
        QVERIFY(messageHasSelfMention(processedMessage));
    }

    // New user name changes should be detected
    sharedParams.onUserNameSet("NewUserName");
    auto processedMessage = messageProcessor.processIncomingCoreMessage(false, "NewUserName: hi");
    QVERIFY(messageHasSelfMention(processedMessage));

    // Special characters should be removed
    sharedParams.onUserNameSet("New\nUserName");
    processedMessage = messageProcessor.processIncomingCoreMessage(false, "NewUserName: hi");
    QVERIFY(messageHasSelfMention(processedMessage));

    // Names outside ASCII are words too: the word boundary has to know Cyrillic and Arabic letters
    for (const QString& name : {QStringLiteral("Амина Рахимова"), QStringLiteral("أمينة")}) {
        sharedParams.onUserNameSet(name);
        processedMessage =
            messageProcessor.processIncomingCoreMessage(false, name % QStringLiteral(", привет"));
        QVERIFY2(messageHasSelfMention(processedMessage), qPrintable(name));
        // A longer word that starts with the name is not a mention
        processedMessage =
            messageProcessor.processIncomingCoreMessage(false, name % QStringLiteral("ой"));
        QVERIFY2(!messageHasSelfMention(processedMessage), qPrintable(name));
    }

    // Regression tests for: https://github.com/qTox/qTox/issues/2119
    {
        // Empty usernames should not match
        sharedParams.onUserNameSet("");
        processedMessage = messageProcessor.processIncomingCoreMessage(false, "");
        QVERIFY(!messageHasSelfMention(processedMessage));

        // Empty usernames matched on everything, ensure this is not the case
        processedMessage = messageProcessor.processIncomingCoreMessage(false, "a");
        QVERIFY(!messageHasSelfMention(processedMessage));
    }
}

/**
 * @brief A reply to my message carries my name in its header and my words in its quote; only the
 * words its author wrote can mention me.
 */
void TestMessageProcessor::testReplyIsNoMention()
{
    MessageProcessor::SharedParams sharedParams(tox_max_message_length(), 10 * 1024 * 1024);
    const QString name = QStringLiteral("Амина Рахимова");
    sharedParams.onUserNameSet(name);
    sharedParams.setPublicKey(
        QStringLiteral("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"));
    auto messageProcessor = MessageProcessor(sharedParams);
    messageProcessor.enableMentions();

    const QString header = QStringLiteral("[KQ|1731400000000|5908ABCD|1731400000000|") % name
                           % QStringLiteral("]\n") % name % QStringLiteral(": Я возьму проектор\n");
    // The phones' reply: header, quote, footer, then the words.
    const QString reply = header % QStringLiteral("[KQ/end]\nОтлично, а я принесу удлинитель");
    QVERIFY(!messageHasSelfMention(messageProcessor.processIncomingCoreMessage(false, reply)));
    // Without the footer the first line is the quote.
    const QString shortReply =
        QStringLiteral("[KQ|1|5908ABCD|2|") % name % QStringLiteral("]\nЯ возьму проектор\nОтлично");
    QVERIFY(!messageHasSelfMention(messageProcessor.processIncomingCoreMessage(false, shortReply)));
    // The older quote.
    const QString legacy =
        QStringLiteral("----\n") % name % QStringLiteral(": Я возьму проектор\n----\nОтлично");
    QVERIFY(!messageHasSelfMention(messageProcessor.processIncomingCoreMessage(false, legacy)));
    // Markup this build cannot read says nothing to me.
    const QString poll = QStringLiteral("[KQ|poll|7|") % name % QStringLiteral("?|Да|Нет]");
    QVERIFY(!messageHasSelfMention(messageProcessor.processIncomingCoreMessage(false, poll)));

    // A reply whose own words name me mentions me, at the place of those words.
    const QString named = header % QStringLiteral("[KQ/end]\n") % name % QStringLiteral(", а ты?");
    const Message processed = messageProcessor.processIncomingCoreMessage(false, named);
    QVERIFY(messageHasSelfMention(processed));
    QCOMPARE(processed.metadata.front().start, static_cast<size_t>(named.lastIndexOf(name)));
    QCOMPARE(processed.metadata.front().end,
             static_cast<size_t>(named.lastIndexOf(name) + name.size()));

    // A mention block with the "@" it puts before the name.
    const QString mention = QStringLiteral("[KQ|m|ABCDEF12:") % name % QStringLiteral("][KQ/m]@")
                            % name % QStringLiteral(", ключ от зала у тебя?");
    QVERIFY(messageHasSelfMention(messageProcessor.processIncomingCoreMessage(false, mention)));
}

/**
 * @brief Tests behavior of the processor for outgoing messages
 */
void TestMessageProcessor::testOutgoingMessage()
{
    auto sharedParams = MessageProcessor::SharedParams(tox_max_message_length(), 10 * 1024 * 1024);
    auto messageProcessor = MessageProcessor(sharedParams);

    QString testStr;

    for (size_t i = 0; i < tox_max_message_length() + 50; ++i) {
        testStr += "a";
    }

    auto messages = messageProcessor.processOutgoingMessage(false, testStr, ExtensionSet());

    // The message processor should split our messages
    QVERIFY(messages.size() == 2);

    auto extensionSet = ExtensionSet();
    extensionSet[ExtensionType::messages] = true;
    messages = messageProcessor.processOutgoingMessage(false, testStr, extensionSet);

    // If we have multipart messages we shouldn't split our messages
    QVERIFY(messages.size() == 1);
}

/**
 * @brief Tests behavior of the processor for incoming messages
 */
void TestMessageProcessor::testIncomingMessage()
{
    // Nothing too special happening on the incoming side if we aren't looking for self mentions
    auto sharedParams = MessageProcessor::SharedParams(tox_max_message_length(), 10 * 1024 * 1024);
    auto messageProcessor = MessageProcessor(sharedParams);
    auto message = messageProcessor.processIncomingCoreMessage(false, "test");

    QVERIFY(message.isAction == false);
    QVERIFY(message.content == "test");
    QVERIFY(message.timestamp.isValid());
}

QTEST_GUILESS_MAIN(TestMessageProcessor)
#include "messageprocessor_test.moc"
