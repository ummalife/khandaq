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

#include "src/model/air/airlocalstore.h"
#include "src/model/air/airmessagemarks.h"
#include "src/model/ichatlog.h"
#include "src/model/imessagedispatcher.h"
#include "src/model/sessionchatlog.h"

#include <QtTest/QtTest>

namespace {
const QString TEST_USERNAME = "qTox Tester #1";

Message createMessage(const QString& content)
{
    Message message;
    message.content = content;
    message.isAction = false;
    message.timestamp = QDateTime::currentDateTime();
    return message;
}

/** A message as a group gives it: with the 4-byte id toxcore gave it, in hexadecimal. */
Message wiredMessage(const QString& content, const char* wireHex)
{
    Message message = createMessage(content);
    message.wireId = QByteArray::fromHex(wireHex);
    return message;
}

ToxPk keyOf(uint8_t fill)
{
    uint8_t bytes[ToxPk::size];
    std::fill(std::begin(bytes), std::end(bytes), fill);
    return ToxPk(bytes);
}

Air::MessageEffect effectOn(Air::MessageEffect::Kind kind, const char* anchorHex)
{
    Air::MessageEffect effect;
    effect.kind = kind;
    effect.anchor = Air::MessageEffect::Anchor::Text;
    effect.anchorId = QByteArray::fromHex(anchorHex);
    return effect;
}

class MockCoreIdHandler : public ICoreIdHandler
{
public:
    ToxId getSelfId() const override
    {
        std::terminate();
        return ToxId();
    }

    ToxPk getSelfPublicKey() const override
    {
        static uint8_t id[ToxPk::size] = {5};
        return ToxPk(id);
    }

    QString getUsername() const override
    {
        return TEST_USERNAME;
    }
};
} // namespace

class TestSessionChatLog : public QObject
{
    Q_OBJECT

public:
    TestSessionChatLog(){}

private slots:
    void init();

    void testSanity();
    void onlyItsAuthorEditsAMessageAndTheLatestEditWins();
    void aDeletedMessageIsNeitherShownNorFound();
    void everyoneReactsOnceToAMessageOfEitherSide();
    void ourOwnEchoChangesNothing();
    void aGroupReactionNamesWhoseMessageItIs();
    void aFileIsFoundByItsToxFileId();
    void aMessageWithoutAnIdIsNeverAnchored();

private:
    MockCoreIdHandler idHandler;
    std::unique_ptr<SessionChatLog> chatLog;
    std::unique_ptr<FriendList> friendList;
    std::unique_ptr<GroupList> groupList;
};

/**
 * @brief Test initialiation, resets the chatlog
 */
void TestSessionChatLog::init()
{
    friendList = std::unique_ptr<FriendList>(new FriendList());
    groupList = std::unique_ptr<GroupList>(new GroupList());
    chatLog = std::unique_ptr<SessionChatLog>(new SessionChatLog(idHandler, *friendList,
                                                                 *groupList));
}

/**
 * @brief Quick sanity test that the chatlog is working as expected. Tests basic insertion, retrieval, and searching of messages
 */
void TestSessionChatLog::testSanity()
{
    /* ChatLogIdx(0) */ chatLog->onMessageSent(DispatchedMessageId(0), createMessage("test"));
    /* ChatLogIdx(1) */ chatLog->onMessageSent(DispatchedMessageId(1), createMessage("test test"));
    /* ChatLogIdx(2) */ chatLog->onMessageReceived(ToxPk(), createMessage("test2"));
    /* ChatLogIdx(3) */ chatLog->onFileUpdated(ToxPk(), ToxFile());
    /* ChatLogIdx(4) */ chatLog->onMessageSent(DispatchedMessageId(2), createMessage("test3"));
    /* ChatLogIdx(5) */ chatLog->onMessageSent(DispatchedMessageId(3), createMessage("test4"));
    /* ChatLogIdx(6) */ chatLog->onMessageSent(DispatchedMessageId(4), createMessage("test"));
    /* ChatLogIdx(7) */ chatLog->onMessageReceived(ToxPk(), createMessage("test5"));

    QVERIFY(chatLog->getNextIdx() == ChatLogIdx(8));
    QVERIFY(chatLog->at(ChatLogIdx(3)).getContentType() == ChatLogItem::ContentType::fileTransfer);
    QVERIFY(chatLog->at(ChatLogIdx(7)).getContentType() == ChatLogItem::ContentType::message);

    auto searchPos = SearchPos{ChatLogIdx(1), 0};
    auto searchResult = chatLog->searchForward(searchPos, "test", ParameterSearch());

    QVERIFY(searchResult.found);
    QVERIFY(searchResult.len == 4);
    QVERIFY(searchResult.pos.logIdx == ChatLogIdx(1));
    QVERIFY(searchResult.start == 0);

    searchPos = searchResult.pos;
    searchResult = chatLog->searchForward(searchPos, "test", ParameterSearch());

    QVERIFY(searchResult.found);
    QVERIFY(searchResult.len == 4);
    QVERIFY(searchResult.pos.logIdx == ChatLogIdx(1));
    QVERIFY(searchResult.start == 5);

    searchPos = searchResult.pos;
    searchResult = chatLog->searchForward(searchPos, "test", ParameterSearch());

    QVERIFY(searchResult.found);
    QVERIFY(searchResult.len == 4);
    QVERIFY(searchResult.pos.logIdx == ChatLogIdx(2));
    QVERIFY(searchResult.start == 0);

    searchPos = searchResult.pos;
    searchResult = chatLog->searchBackward(searchPos, "test", ParameterSearch());

    QVERIFY(searchResult.found);
    QVERIFY(searchResult.len == 4);
    QVERIFY(searchResult.pos.logIdx == ChatLogIdx(1));
    QVERIFY(searchResult.start == 5);
}

/**
 * @brief An edit (186, 0x41) changes a message only when its author sends it, and an older or a
 * repeated one changes nothing (stage 3, item 10, as HelperMessageEdit applies them on Android)
 */
void TestSessionChatLog::onlyItsAuthorEditsAMessageAndTheLatestEditWins()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk self = idHandler.getSelfPublicKey();
    const ToxPk maryam = keyOf(0x11);
    const ToxPk ibrahim = keyOf(0x22);
    /* ChatLogIdx(0) */ chatLog->onMessageReceived(maryam,
                                                   wiredMessage("Встреча в пять", "0a0b0c0d"));
    /* ChatLogIdx(1) */ chatLog->onMessageReceived(ibrahim, wiredMessage("Хорошо", "0a0b0c0e"));

    Air::MessageEffect edit = effectOn(Air::MessageEffect::Kind::Edit, "0a0b0c0d");
    edit.time = 100;
    edit.text = QStringLiteral("Встреча в шесть");
    QCOMPARE(chatLog->applyEffect(ibrahim, edit, self).change, Change::None);
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsMessage().message.content,
             QStringLiteral("Встреча в пять"));

    const SessionChatLog::EffectResult edited = chatLog->applyEffect(maryam, edit, self);
    QCOMPARE(edited.change, Change::Edited);
    QCOMPARE(edited.idx, ChatLogIdx(0));
    QCOMPARE(edited.author, maryam);
    QCOMPARE(edited.before.content, QStringLiteral("Встреча в пять"));
    const ChatLogMessage& message = chatLog->at(ChatLogIdx(0)).getContentAsMessage();
    QCOMPARE(message.message.content, QStringLiteral("Встреча в шесть"));
    QVERIFY(message.marks.edited);
    QCOMPARE(message.marks.editedAt, quint32{100});
    QVERIFY(edited.marks.edited);

    // An older edit, and the same one again, change nothing; a newer one does.
    edit.time = 99;
    edit.text = QStringLiteral("Встреча в четыре");
    QCOMPARE(chatLog->applyEffect(maryam, edit, self).change, Change::None);
    edit.time = 100;
    edit.text = QStringLiteral("Встреча в шесть");
    QCOMPARE(chatLog->applyEffect(maryam, edit, self).change, Change::None);
    edit.time = 101;
    edit.text = QStringLiteral("Встреча в семь");
    QCOMPARE(chatLog->applyEffect(maryam, edit, self).change, Change::Edited);
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsMessage().message.content,
             QStringLiteral("Встреча в семь"));

    // A file has no words to edit.
    Air::MessageEffect fileEdit = edit;
    fileEdit.anchor = Air::MessageEffect::Anchor::File;
    QCOMPARE(chatLog->applyEffect(maryam, fileEdit, self).change, Change::None);
}

/**
 * @brief A deletion (187, 0x42) from the author takes the message out of the log: it keeps its
 * index, but it is neither shown nor found, and a second deletion finds nothing
 */
void TestSessionChatLog::aDeletedMessageIsNeitherShownNorFound()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk self = idHandler.getSelfPublicKey();
    const ToxPk maryam = keyOf(0x11);
    /* ChatLogIdx(0) */ chatLog->onMessageReceived(maryam,
                                                   wiredMessage("Удалю это позже", "00000007"));
    /* ChatLogIdx(1) */ chatLog->onMessageReceived(maryam,
                                                   wiredMessage("Это останется", "00000008"));

    const Air::MessageEffect deletion = effectOn(Air::MessageEffect::Kind::Delete, "00000007");
    QCOMPARE(chatLog->applyEffect(keyOf(0x22), deletion, self).change, Change::None);
    QVERIFY(chatLog->isShown(ChatLogIdx(0)));

    const SessionChatLog::EffectResult deleted = chatLog->applyEffect(maryam, deletion, self);
    QCOMPARE(deleted.change, Change::Deleted);
    QCOMPARE(deleted.idx, ChatLogIdx(0));
    QVERIFY(!chatLog->isShown(ChatLogIdx(0)));
    QVERIFY(chatLog->isCancelled(ChatLogIdx(0)));
    QVERIFY(chatLog->isShown(ChatLogIdx(1)));
    QCOMPARE(chatLog->getNextIdx(), ChatLogIdx(2));
    QVERIFY(!chatLog->searchForward(SearchPos{ChatLogIdx(0), 0}, "Удалю", ParameterSearch()).found);
    QVERIFY(chatLog->searchForward(SearchPos{ChatLogIdx(0), 0}, "останется", ParameterSearch()).found);
    QCOMPARE(chatLog->applyEffect(maryam, deletion, self).change, Change::None);
}

/**
 * @brief A reaction (188, 0x43) counts once per person, on our messages and on theirs: a new one
 * takes the place of the old, a removal takes it back
 */
void TestSessionChatLog::everyoneReactsOnceToAMessageOfEitherSide()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk self = idHandler.getSelfPublicKey();
    const ToxPk maryam = keyOf(0x11);
    const ToxPk ibrahim = keyOf(0x22);
    /* ChatLogIdx(0) */ chatLog->onMessageSent(DispatchedMessageId(0),
                                               wiredMessage("Салам", "00000001"));

    Air::MessageEffect reaction = effectOn(Air::MessageEffect::Kind::Reaction, "00000001");
    reaction.emoji = QStringLiteral("👍");
    reaction.add = true;
    const SessionChatLog::EffectResult first = chatLog->applyEffect(maryam, reaction, self);
    QCOMPARE(first.change, Change::Reacted);
    QCOMPARE(first.idx, ChatLogIdx(0));
    QCOMPARE(first.author, self);
    QCOMPARE(first.marks.reactions.size(), 1);
    QCOMPARE(first.marks.reactions.first().actors,
             QStringList{Air::LocalStore::hashedKey(maryam.toString())});
    QCOMPARE(chatLog->applyEffect(maryam, reaction, self).change, Change::None);
    QCOMPARE(chatLog->applyEffect(ibrahim, reaction, self).marks.reactionsText(),
             QStringLiteral("👍 2"));

    reaction.emoji = QStringLiteral("❤️");
    QCOMPARE(chatLog->applyEffect(maryam, reaction, self).marks.reactionsText(),
             QStringLiteral("👍  ❤️"));
    reaction.emoji = QStringLiteral("*");
    reaction.add = false;
    const SessionChatLog::EffectResult taken = chatLog->applyEffect(maryam, reaction, self);
    QCOMPARE(taken.change, Change::Reacted);
    QCOMPARE(taken.marks.reactionsText(), QStringLiteral("👍"));
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsMessage().marks.reactionsText(),
             QStringLiteral("👍"));
}

/** @brief What we did ourselves, coming back from the other side, changes nothing */
void TestSessionChatLog::ourOwnEchoChangesNothing()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk self = idHandler.getSelfPublicKey();
    const ToxPk groupSelf = keyOf(0x55);
    /* ChatLogIdx(0) */ chatLog->onMessageSent(DispatchedMessageId(0),
                                               wiredMessage("Наш текст", "00000002"));

    Air::MessageEffect edit = effectOn(Air::MessageEffect::Kind::Edit, "00000002");
    edit.text = QStringLiteral("Чужая правка");
    QCOMPARE(chatLog->applyEffect(self, edit, self).change, Change::None);
    QCOMPARE(chatLog->applyEffect(groupSelf, edit, groupSelf).change, Change::None);
    Air::MessageEffect reaction = effectOn(Air::MessageEffect::Kind::Reaction, "00000002");
    reaction.emoji = QStringLiteral("👍");
    reaction.add = true;
    QCOMPARE(chatLog->applyEffect(groupSelf, reaction, groupSelf).change, Change::None);
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsMessage().message.content,
             QStringLiteral("Наш текст"));
}

/**
 * @brief In a group a reaction names the key of who wrote the message, ours being our key in
 * the group: the same id from two people points at two messages
 */
void TestSessionChatLog::aGroupReactionNamesWhoseMessageItIs()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk groupSelf = keyOf(0x55);
    const ToxPk maryam = keyOf(0x11);
    const ToxPk ibrahim = keyOf(0x22);
    /* ChatLogIdx(0) */ chatLog->onMessageSent(DispatchedMessageId(0),
                                               wiredMessage("Наш текст", "00000009"));
    /* ChatLogIdx(1) */ chatLog->onMessageReceived(maryam, wiredMessage("Её текст", "00000009"));

    Air::MessageEffect reaction = effectOn(Air::MessageEffect::Kind::Reaction, "00000009");
    reaction.emoji = QStringLiteral("👍");
    reaction.add = true;
    reaction.author = groupSelf.getByteArray();
    const SessionChatLog::EffectResult ours = chatLog->applyEffect(ibrahim, reaction, groupSelf);
    QCOMPARE(ours.change, Change::Reacted);
    QCOMPARE(ours.idx, ChatLogIdx(0));

    reaction.author = maryam.getByteArray();
    const SessionChatLog::EffectResult hers = chatLog->applyEffect(ibrahim, reaction, groupSelf);
    QCOMPARE(hers.change, Change::Reacted);
    QCOMPARE(hers.idx, ChatLogIdx(1));

    reaction.author = keyOf(0x33).getByteArray();
    QCOMPARE(chatLog->applyEffect(ibrahim, reaction, groupSelf).change, Change::None);
}

/** @brief A file is anchored on its Tox file id; only who sent it deletes it */
void TestSessionChatLog::aFileIsFoundByItsToxFileId()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk self = idHandler.getSelfPublicKey();
    const ToxPk maryam = keyOf(0x11);
    ToxFile file(0, 0, QStringLiteral("Смета.pdf"), QString(), 1000, ToxFile::RECEIVING);
    file.resumeFileId = QByteArray(32, '\x07');
    /* ChatLogIdx(0) */ chatLog->onFileUpdated(maryam, file);

    Air::MessageEffect reaction;
    reaction.kind = Air::MessageEffect::Kind::Reaction;
    reaction.anchor = Air::MessageEffect::Anchor::File;
    reaction.anchorId = file.resumeFileId;
    reaction.emoji = QStringLiteral("👍");
    reaction.add = true;
    const SessionChatLog::EffectResult reacted = chatLog->applyEffect(keyOf(0x22), reaction, self);
    QCOMPARE(reacted.change, Change::Reacted);
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsFile().marks.reactionsText(), QStringLiteral("👍"));

    Air::MessageEffect deletion = reaction;
    deletion.kind = Air::MessageEffect::Kind::Delete;
    QCOMPARE(chatLog->applyEffect(keyOf(0x22), deletion, self).change, Change::None);
    deletion.anchorId = QByteArray(32, '\x08');
    QCOMPARE(chatLog->applyEffect(maryam, deletion, self).change, Change::None);
    deletion.anchorId = file.resumeFileId;
    const SessionChatLog::EffectResult deleted = chatLog->applyEffect(maryam, deletion, self);
    QCOMPARE(deleted.change, Change::Deleted);
    QCOMPARE(deleted.file.fileName, QStringLiteral("Смета.pdf"));
    QVERIFY(!chatLog->isShown(ChatLogIdx(0)));
}

/**
 * @brief Between two people Android anchors a text on its msgV3 hash, which this client has no
 * place for: a message without an id is never the one a packet points at
 */
void TestSessionChatLog::aMessageWithoutAnIdIsNeverAnchored()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk self = idHandler.getSelfPublicKey();
    const ToxPk maryam = keyOf(0x11);
    /* ChatLogIdx(0) */ chatLog->onMessageReceived(maryam, createMessage("Без номера"));

    Air::MessageEffect deletion =
        effectOn(Air::MessageEffect::Kind::Delete,
                 "00112233445566778899AABBCCDDEEFF0123456789ABCDEFFEDCBA9876543210");
    QCOMPARE(chatLog->applyEffect(maryam, deletion, self).change, Change::None);
    deletion.anchorId.clear();
    QCOMPARE(chatLog->applyEffect(maryam, deletion, self).change, Change::None);
    QVERIFY(chatLog->isShown(ChatLogIdx(0)));
}

QTEST_GUILESS_MAIN(TestSessionChatLog)
#include "sessionchatlog_test.moc"
