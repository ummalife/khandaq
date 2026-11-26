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
    void onlyANewReactionOfAnotherToOursIsNews();
    void aReactionToOursInAGroupIsNewsUnderEitherOfOurKeys();
    void aFileIsFoundByItsToxFileId();
    void aFileThatWaitsForItsFriendKeepsItsEntry();
    void aFileThatGoesOnMovesInTheEntryItHad();
    void aMessageWithoutAnIdIsNeverAnchored();
    void aMessageThatWaitedIsFoundByTheIdItWentWith();
    void ourReactionComesGoesAndChangesUnderOwn();
    void onlyAShownMessageOrFileTakesOurReaction();
    void aFileReactionFromIosIsShown();
    void aRelayedAuthorTakesTheNameTheGroupGivesThem();
    void ourOwnTextTakesAnEditMarkedEdited();

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

/**
 * @brief KHANDAQ (0.2.64): the window alerts on a reaction (EffectResult::reactedToOwn) only when
 * someone else adds one to a message or a file of ours: never one on theirs, one taken back, or
 * the same one again
 */
void TestSessionChatLog::onlyANewReactionOfAnotherToOursIsNews()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk self = idHandler.getSelfPublicKey();
    const ToxPk maryam = keyOf(0x11);
    /* ChatLogIdx(0) */ chatLog->onMessageSent(DispatchedMessageId(0),
                                               wiredMessage("Salam", "00000001"));
    /* ChatLogIdx(1) */ chatLog->onMessageReceived(maryam, wiredMessage("Wa alaykum", "00000002"));
    ToxFile ours(1, 0, QStringLiteral("voice_3F9A.file.m4a"), QString(), 1000, ToxFile::SENDING);
    ours.resumeFileId = QByteArray(32, '\x0b');
    /* ChatLogIdx(2) */ chatLog->onFileUpdated(self, ours);
    ToxFile hers(2, 0, QStringLiteral("photo.jpg"), QString(), 1000, ToxFile::RECEIVING);
    hers.resumeFileId = QByteArray(32, '\x07');
    /* ChatLogIdx(3) */ chatLog->onFileUpdated(maryam, hers);

    Air::MessageEffect reaction = effectOn(Air::MessageEffect::Kind::Reaction, "00000001");
    reaction.emoji = QStringLiteral("👍");
    reaction.add = true;
    const SessionChatLog::EffectResult first = chatLog->applyEffect(maryam, reaction, self);
    QCOMPARE(first.change, Change::Reacted);
    QVERIFY(first.reactedToOwn);
    QCOMPARE(first.before.content, QStringLiteral("Salam"));
    // The same packet again, as a client sends it once more, changes nothing and says nothing.
    const SessionChatLog::EffectResult again = chatLog->applyEffect(maryam, reaction, self);
    QCOMPARE(again.change, Change::None);
    QVERIFY(!again.reactedToOwn);
    // Another emoji in place of hers is news; taking it back is not.
    reaction.emoji = QStringLiteral("❤️");
    QVERIFY(chatLog->applyEffect(maryam, reaction, self).reactedToOwn);
    reaction.add = false;
    const SessionChatLog::EffectResult taken = chatLog->applyEffect(maryam, reaction, self);
    QCOMPARE(taken.change, Change::Reacted);
    QVERIFY(!taken.reactedToOwn);

    // On her own message: shown, but no news for us.
    reaction = effectOn(Air::MessageEffect::Kind::Reaction, "00000002");
    reaction.emoji = QStringLiteral("👍");
    reaction.add = true;
    const SessionChatLog::EffectResult onHers = chatLog->applyEffect(maryam, reaction, self);
    QCOMPARE(onHers.change, Change::Reacted);
    QCOMPARE(onHers.idx, ChatLogIdx(1));
    QVERIFY(!onHers.reactedToOwn);

    // A file of ours is ours as a text is, and the result names it for the words.
    reaction.anchor = Air::MessageEffect::Anchor::File;
    reaction.anchorId = ours.resumeFileId;
    const SessionChatLog::EffectResult onOurFile = chatLog->applyEffect(maryam, reaction, self);
    QCOMPARE(onOurFile.change, Change::Reacted);
    QCOMPARE(onOurFile.idx, ChatLogIdx(2));
    QVERIFY(onOurFile.reactedToOwn);
    QCOMPARE(onOurFile.file.fileName, QStringLiteral("voice_3F9A.file.m4a"));
    reaction.anchorId = hers.resumeFileId;
    const SessionChatLog::EffectResult onHerFile = chatLog->applyEffect(maryam, reaction, self);
    QCOMPARE(onHerFile.change, Change::Reacted);
    QVERIFY(!onHerFile.reactedToOwn);

    // Our own reaction coming back is never taken, so never news.
    reaction.anchorId = ours.resumeFileId;
    reaction.emoji = QStringLiteral("🔥");
    const SessionChatLog::EffectResult echo = chatLog->applyEffect(self, reaction, self);
    QCOMPARE(echo.change, Change::None);
    QVERIFY(!echo.reactedToOwn);
}

/**
 * @brief KHANDAQ (0.2.64): in a group our message stands under the profile's key or our key in
 * the group, and a reaction to either is news; the same emoji sent again after others reacted
 * moves its place in the row but is no news, nor one that a full row of emoji leaves out
 */
void TestSessionChatLog::aReactionToOursInAGroupIsNewsUnderEitherOfOurKeys()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk groupSelf = keyOf(0x55);
    const ToxPk maryam = keyOf(0x11);
    const ToxPk ibrahim = keyOf(0x22);
    /* ChatLogIdx(0) */ chatLog->onMessageSent(DispatchedMessageId(0),
                                               wiredMessage("Our text", "00000009"));
    /* ChatLogIdx(1) */ chatLog->onMessageReceived(maryam, wiredMessage("Her text", "00000009"));
    /* ChatLogIdx(2) */ chatLog->onMessageReceived(groupSelf, wiredMessage("Ours too", "0000000c"));

    Air::MessageEffect reaction = effectOn(Air::MessageEffect::Kind::Reaction, "00000009");
    reaction.emoji = QStringLiteral("👍");
    reaction.add = true;
    reaction.author = groupSelf.getByteArray();
    QVERIFY(chatLog->applyEffect(maryam, reaction, groupSelf).reactedToOwn);
    QVERIFY(chatLog->applyEffect(ibrahim, reaction, groupSelf).reactedToOwn);
    // Hers again after his: the row changes ("👍" by him, then her), the news does not.
    const SessionChatLog::EffectResult resent = chatLog->applyEffect(maryam, reaction, groupSelf);
    QCOMPARE(resent.change, Change::Reacted);
    QVERIFY(!resent.reactedToOwn);

    reaction.author = maryam.getByteArray();
    const SessionChatLog::EffectResult onHers = chatLog->applyEffect(ibrahim, reaction, groupSelf);
    QCOMPARE(onHers.idx, ChatLogIdx(1));
    QVERIFY(!onHers.reactedToOwn);

    Air::MessageEffect toGroupKey = effectOn(Air::MessageEffect::Kind::Reaction, "0000000c");
    toGroupKey.emoji = QStringLiteral("👍");
    toGroupKey.add = true;
    toGroupKey.author = groupSelf.getByteArray();
    const SessionChatLog::EffectResult underGroupKey =
        chatLog->applyEffect(maryam, toGroupKey, groupSelf);
    QCOMPARE(underGroupKey.idx, ChatLogIdx(2));
    QVERIFY(underGroupKey.reactedToOwn);

    // Eleven more people fill the row of our message to twelve emoji with "👍"; hers goes to a
    // thirteenth, which the row leaves out, so only her old one went.
    const QStringList eleven{QStringLiteral("😂"), QStringLiteral("😮"), QStringLiteral("😢"),
                             QStringLiteral("🙏"), QStringLiteral("🔥"), QStringLiteral("👏"),
                             QStringLiteral("🎉"), QStringLiteral("💯"), QStringLiteral("🤝"),
                             QStringLiteral("🌙"), QStringLiteral("🌹")};
    reaction.author = groupSelf.getByteArray();
    for (int i = 0; i < eleven.size(); ++i) {
        reaction.emoji = eleven.at(i);
        QVERIFY(chatLog->applyEffect(keyOf(static_cast<uint8_t>(0x60 + i)), reaction, groupSelf)
                    .reactedToOwn);
    }
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsMessage().marks.reactions.size(), 12);
    reaction.emoji = QStringLiteral("🙂");
    const SessionChatLog::EffectResult leftOut = chatLog->applyEffect(maryam, reaction, groupSelf);
    QCOMPARE(leftOut.change, Change::Reacted);
    QVERIFY(!leftOut.reactedToOwn);
    QVERIFY(leftOut.marks.emojiOf(Air::LocalStore::hashedKey(maryam.toString())).isEmpty());
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
 * @brief KHANDAQ (0.2.57): the friend went offline in the middle of a file: it waits under a number
 * of its own (CoreFile::keepForFriend), in the entry it had, and its id says which entry that is.
 */
void TestSessionChatLog::aFileThatWaitsForItsFriendKeepsItsEntry()
{
    const ToxPk maryam = keyOf(0x11);
    ToxFile file(3, 0, QStringLiteral("Видео.mp4"), QStringLiteral("/files/video.mp4"), 5000,
                 ToxFile::RECEIVING);
    file.resumeFileId = QByteArray(32, '\x09');
    /* ChatLogIdx(0) */ chatLog->onFileUpdated(maryam, file);
    file.status = ToxFile::TRANSMITTING;
    chatLog->onFileUpdated(maryam, file);

    ToxFile waiting = file;
    waiting.fileNum = 0x80000000u;
    waiting.connectionLost = true;
    chatLog->onFileUpdated(maryam, waiting);
    QCOMPARE(chatLog->getNextIdx(), ChatLogIdx(1));
    QVERIFY(chatLog->at(ChatLogIdx(0)).getContentAsFile().file.connectionLost);
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsFile().file.fileNum, 0x80000000u);
    QVERIFY(chatLog->isCurrentFile(file));

    // Offered again under a new number, it is still that entry; Cancel there ends it.
    ToxFile again = file;
    again.fileNum = 7;
    again.status = ToxFile::INITIALIZING;
    again.continued = true;
    chatLog->onFileUpdated(maryam, again);
    QCOMPARE(chatLog->getNextIdx(), ChatLogIdx(1));
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsFile().file.fileNum, 7u);
    again.status = ToxFile::CANCELED;
    chatLog->onFileUpdated(maryam, again);
    QVERIFY(!chatLog->isCurrentFile(again));
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsFile().file.status, ToxFile::CANCELED);

    // The same file the other way is another transfer.
    ToxFile ours = file;
    ours.direction = ToxFile::SENDING;
    QVERIFY(!chatLog->isCurrentFile(ours));
}

/**
 * @brief KHANDAQ (0.2.57): an offer of a file whose entry ended goes on in that entry when it says
 * so (continued); any other offer, of the same id too, makes a new entry as before.
 */
void TestSessionChatLog::aFileThatGoesOnMovesInTheEntryItHad()
{
    const ToxPk maryam = keyOf(0x11);
    ToxFile file(3, 0, QStringLiteral("Видео.mp4"), QStringLiteral("/files/video.mp4"), 5000,
                 ToxFile::RECEIVING);
    file.resumeFileId = QByteArray(32, '\x09');
    /* ChatLogIdx(0) */ chatLog->onFileUpdated(maryam, file);
    file.status = ToxFile::BROKEN;
    chatLog->onFileUpdated(maryam, file);
    /* ChatLogIdx(1) */ chatLog->onMessageReceived(maryam, createMessage("Потом"));

    ToxFile found;
    QVERIFY(chatLog->findFileEntry(file.resumeFileId, ToxFile::RECEIVING, &found));
    QCOMPARE(found.status, ToxFile::BROKEN);
    QCOMPARE(found.filePath, file.filePath);
    QVERIFY(!chatLog->findFileEntry(file.resumeFileId, ToxFile::SENDING, nullptr));
    QVERIFY(!chatLog->findFileEntry(QByteArray(32, '\x0a'), ToxFile::RECEIVING, nullptr));
    QVERIFY(!chatLog->findFileEntry(QByteArray(), ToxFile::RECEIVING, nullptr));

    qRegisterMetaType<ChatLogIdx>();
    QSignalSpy updated(chatLog.get(), &SessionChatLog::itemUpdated);
    ToxFile again(8, 0, file.fileName, file.filePath, 5000, ToxFile::RECEIVING);
    again.resumeFileId = file.resumeFileId;
    again.continued = true;
    chatLog->onFileUpdated(maryam, again);
    QCOMPARE(chatLog->getNextIdx(), ChatLogIdx(2));
    QCOMPARE(updated.count(), 1);
    QCOMPARE(qvariant_cast<ChatLogIdx>(updated.at(0).at(0)), ChatLogIdx(0));
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsFile().file.fileNum, 8u);
    again.status = ToxFile::FINISHED;
    chatLog->onFileUpdated(maryam, again);
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsFile().file.status, ToxFile::FINISHED);

    // Not one that goes on: a new entry, whatever its id.
    ToxFile fresh = again;
    fresh.fileNum = 9;
    fresh.status = ToxFile::INITIALIZING;
    fresh.continued = false;
    /* ChatLogIdx(2) */ chatLog->onFileUpdated(maryam, fresh);
    QCOMPARE(chatLog->getNextIdx(), ChatLogIdx(3));
    // The newest entry of that id is the one an offer goes on with.
    QVERIFY(chatLog->findFileEntry(file.resumeFileId, ToxFile::RECEIVING, &found));
    QCOMPARE(found.fileNum, 9u);
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

/**
 * @brief 0.2.56: a text of ours that waited in a group is shown without an id, and gets the one
 * toxcore gave it once it went: the members' reactions point at that one.
 */
void TestSessionChatLog::aMessageThatWaitedIsFoundByTheIdItWentWith()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk groupSelf = keyOf(0x55);
    const ToxPk maryam = keyOf(0x11);
    const Message waited = createMessage("Ждал");
    /* ChatLogIdx(0) */ chatLog->onMessageSent(DispatchedMessageId(7), waited);

    Air::MessageEffect reaction = effectOn(Air::MessageEffect::Kind::Reaction, "0000000a");
    reaction.emoji = QStringLiteral("👍");
    reaction.add = true;
    reaction.author = groupSelf.getByteArray();
    QCOMPARE(chatLog->applyEffect(maryam, reaction, groupSelf).change, Change::None);

    QVERIFY(!chatLog->onMessageWireIdAssigned(DispatchedMessageId(8),
                                              QByteArray::fromHex("0000000a")));
    const Message* const went =
        chatLog->onMessageWireIdAssigned(DispatchedMessageId(7), QByteArray::fromHex("0000000a"));
    QVERIFY(went);
    QCOMPARE(went->wireId, QByteArray::fromHex("0000000a"));
    // The history keeps it under the time it was written (ChatHistory::keepWireId).
    QCOMPARE(went->timestamp, waited.timestamp);

    const SessionChatLog::EffectResult reacted = chatLog->applyEffect(maryam, reaction, groupSelf);
    QCOMPARE(reacted.change, Change::Reacted);
    QCOMPARE(reacted.idx, ChatLogIdx(0));
}

/**
 * @brief KHANDAQ (0.2.63): our reaction from the menu is kept under "-OWN-", beside the others':
 * one emoji, another one takes its place, and the same one again takes it back.
 */
void TestSessionChatLog::ourReactionComesGoesAndChangesUnderOwn()
{
    const ToxPk maryam = keyOf(0x11);
    /* ChatLogIdx(0) */ chatLog->onMessageReceived(maryam, wiredMessage("Her text", "00000009"));
    Air::MessageEffect theirs = effectOn(Air::MessageEffect::Kind::Reaction, "00000009");
    const QString heart = Air::quickReactions().at(0);
    const QString fire = Air::quickReactions().at(6);
    theirs.emoji = heart;
    theirs.add = true;
    QCOMPARE(chatLog->applyEffect(keyOf(0x22), theirs, idHandler.getSelfPublicKey()).change,
             SessionChatLog::EffectResult::Change::Reacted);
    const auto marks = [this] {
        return chatLog->at(ChatLogIdx(0)).getContentAsMessage().marks;
    };

    qRegisterMetaType<ChatLogIdx>();
    QSignalSpy updated(chatLog.get(), &SessionChatLog::itemUpdated);
    QString own = QStringLiteral("unset");
    QVERIFY(chatLog->toggleOwnReaction(ChatLogIdx(0), heart, &own));
    QCOMPARE(own, heart);
    QCOMPARE(marks().emojiOf(Air::ownReactionActor()), heart);
    QCOMPARE(marks().reactionsText(), heart + QStringLiteral(" 2"));

    QVERIFY(chatLog->toggleOwnReaction(ChatLogIdx(0), fire, &own));
    QCOMPARE(own, fire);
    QCOMPARE(marks().emojiOf(Air::ownReactionActor()), fire);

    QVERIFY(chatLog->toggleOwnReaction(ChatLogIdx(0), fire, &own));
    QVERIFY(own.isEmpty());
    QVERIFY(marks().emojiOf(Air::ownReactionActor()).isEmpty());
    // The other person's stays.
    QCOMPARE(marks().reactionsText(), heart);
    QCOMPARE(updated.count(), 3);
    QCOMPARE(qvariant_cast<ChatLogIdx>(updated.at(0).at(0)), ChatLogIdx(0));
}

/**
 * @brief KHANDAQ (0.2.63): nothing to react to in a deleted entry, a system message, an entry
 * that is not there, or with no emoji
 */
void TestSessionChatLog::onlyAShownMessageOrFileTakesOurReaction()
{
    const ToxPk maryam = keyOf(0x11);
    const QString heart = Air::quickReactions().at(0);
    /* ChatLogIdx(0) */ chatLog->onMessageReceived(maryam, wiredMessage("Deleted", "00000009"));
    SystemMessage joined;
    joined.messageType = SystemMessageType::userJoinedGroup;
    joined.timestamp = QDateTime::currentDateTime();
    /* ChatLogIdx(1) */ chatLog->addSystemMessage(joined);
    ToxFile file(0, 0, QStringLiteral("photo.jpg"), QString(), 1000, ToxFile::RECEIVING);
    file.resumeFileId = QByteArray(32, '\x07');
    /* ChatLogIdx(2) */ chatLog->onFileUpdated(maryam, file);

    const Air::MessageEffect deletion = effectOn(Air::MessageEffect::Kind::Delete, "00000009");
    QCOMPARE(chatLog->applyEffect(maryam, deletion, idHandler.getSelfPublicKey()).change,
             SessionChatLog::EffectResult::Change::Deleted);
    QString own = QStringLiteral("unset");
    QVERIFY(!chatLog->toggleOwnReaction(ChatLogIdx(0), heart, &own));
    QVERIFY(!chatLog->toggleOwnReaction(ChatLogIdx(1), heart, &own));
    QVERIFY(!chatLog->toggleOwnReaction(ChatLogIdx(3), heart, &own));
    QVERIFY(!chatLog->toggleOwnReaction(ChatLogIdx(2), QString(), &own));
    QCOMPARE(own, QStringLiteral("unset"));

    // A file takes it as a message does.
    QVERIFY(chatLog->toggleOwnReaction(ChatLogIdx(2), heart, nullptr));
    QCOMPARE(chatLog->at(ChatLogIdx(2)).getContentAsFile().marks.emojiOf(Air::ownReactionActor()),
             heart);
}

/**
 * @brief KHANDAQ (0.2.63): iOS names nobody as the author of a file it reacts to in a group (32
 * zero bytes): the reaction shows on the file with that id, whoever sent it
 */
void TestSessionChatLog::aFileReactionFromIosIsShown()
{
    using Change = SessionChatLog::EffectResult::Change;
    const ToxPk groupSelf = keyOf(0x55);
    const ToxPk maryam = keyOf(0x11);
    ToxFile file(0, 0, QStringLiteral("photo.jpg"), QString(), 1000, ToxFile::RECEIVING);
    file.resumeFileId = QByteArray(32, '\x07');
    /* ChatLogIdx(0) */ chatLog->onFileUpdated(maryam, file);

    const QByteArray emoji = Air::quickReactions().at(1).toUtf8();
    // 0x43 on a file: its id, 32 zero bytes for the author, the time, the action and the emoji.
    QByteArray packet = QByteArray::fromHex("667788113435014302") + file.resumeFileId;
    packet += QByteArray(32, '\0') + QByteArray::fromHex("6553F100") + QByteArray(1, '\x01');
    packet += static_cast<char>(emoji.size());
    packet += emoji;
    Air::MessageEffect reaction;
    QVERIFY(Air::readGroupPacket(packet, &reaction));
    const SessionChatLog::EffectResult reacted =
        chatLog->applyEffect(keyOf(0x22), reaction, groupSelf);
    QCOMPARE(reacted.change, Change::Reacted);
    QCOMPARE(reacted.idx, ChatLogIdx(0));
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsFile().marks.reactionsText(),
             Air::quickReactions().at(1));
}

/**
 * @brief KHANDAQ (0.2.63): a member relays texts of an author the group does not know yet; they
 * are kept without a name and take the one the group gives the author later: those, and no other.
 */
void TestSessionChatLog::aRelayedAuthorTakesTheNameTheGroupGivesThem()
{
    const ToxPk author = keyOf(0x2f);
    const ToxPk other = keyOf(0x3a);
    /* ChatLogIdx(0) */ chatLog->onRelayedMessageReceived(author, QString(), createMessage("salam"));
    /* ChatLogIdx(1) */ chatLog->onRelayedMessageReceived(author, author.toString(),
                                                          createMessage("again"));
    /* ChatLogIdx(2) */ chatLog->onRelayedMessageReceived(author, QStringLiteral("Maryam"),
                                                          createMessage("named already"));
    /* ChatLogIdx(3) */ chatLog->onRelayedMessageReceived(other, QString(), createMessage("other"));
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getDisplayName(), QString());
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getSender(), author);

    QSignalSpy updated(chatLog.get(), &SessionChatLog::itemUpdated);
    // No name, or the key, names nobody.
    QCOMPARE(chatLog->nameAuthor(author, QString()), 0);
    QCOMPARE(chatLog->nameAuthor(author, author.toString()), 0);
    QCOMPARE(updated.count(), 0);

    QCOMPARE(chatLog->nameAuthor(author, QStringLiteral("Maryam B")), 2);
    QCOMPARE(updated.count(), 2);
    QCOMPARE(updated.at(0).at(0).value<ChatLogIdx>(), ChatLogIdx(0));
    QCOMPARE(updated.at(1).at(0).value<ChatLogIdx>(), ChatLogIdx(1));
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getDisplayName(), QStringLiteral("Maryam B"));
    QCOMPARE(chatLog->at(ChatLogIdx(1)).getDisplayName(), QStringLiteral("Maryam B"));
    QCOMPARE(chatLog->at(ChatLogIdx(2)).getDisplayName(), QStringLiteral("Maryam"));
    QCOMPARE(chatLog->at(ChatLogIdx(3)).getDisplayName(), QString());
    // Named once, they keep it.
    QCOMPARE(chatLog->nameAuthor(author, QStringLiteral("Maryam C")), 0);
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getDisplayName(), QStringLiteral("Maryam B"));
}

/**
 * @brief KHANDAQ (beta feedback 2026-11-22): a text of ours takes new words, or becomes an action,
 * and shows "edited", as Saved Messages edits a note; nothing else does, and the same words change
 * nothing
 */
void TestSessionChatLog::ourOwnTextTakesAnEditMarkedEdited()
{
    const ToxPk maryam = keyOf(0x11);
    /* ChatLogIdx(0) */ chatLog->onMessageSent(DispatchedMessageId(0), createMessage("at five"));
    /* ChatLogIdx(1) */ chatLog->onMessageReceived(maryam, createMessage("see you"));
    SystemMessage joined;
    joined.messageType = SystemMessageType::userJoinedGroup;
    joined.timestamp = QDateTime::currentDateTime();
    /* ChatLogIdx(2) */ chatLog->addSystemMessage(joined);
    /* ChatLogIdx(3) */ chatLog->onMessageSent(DispatchedMessageId(1), createMessage("gone"));
    chatLog->hideItem(ChatLogIdx(3));
    QSignalSpy updated(chatLog.get(), &IChatLog::itemUpdated);

    QVERIFY(chatLog->editOwnMessage(ChatLogIdx(0), false, QStringLiteral("at six"), 1763380800));
    QCOMPARE(updated.count(), 1);
    const ChatLogMessage& edited = chatLog->at(ChatLogIdx(0)).getContentAsMessage();
    QCOMPARE(edited.message.content, QStringLiteral("at six"));
    QVERIFY(edited.marks.edited);
    QCOMPARE(edited.marks.editedAt, quint32(1763380800));
    const SearchPos start{ChatLogIdx(0), 0};
    QVERIFY(chatLog->searchForward(start, QStringLiteral("six"), ParameterSearch()).found);
    QVERIFY(chatLog->editOwnMessage(ChatLogIdx(0), true, QStringLiteral("at six"), 1763380900));
    QVERIFY(chatLog->at(ChatLogIdx(0)).getContentAsMessage().message.isAction);
    QCOMPARE(updated.count(), 2);

    // The same words, someone else's text, a call or a join, a hidden one, none at all: nothing.
    QVERIFY(!chatLog->editOwnMessage(ChatLogIdx(0), true, QStringLiteral("at six"), 1763381000));
    QVERIFY(!chatLog->editOwnMessage(ChatLogIdx(1), false, QStringLiteral("see you there"), 1));
    QVERIFY(!chatLog->editOwnMessage(ChatLogIdx(2), false, QStringLiteral("joined"), 1));
    QVERIFY(!chatLog->editOwnMessage(ChatLogIdx(3), false, QStringLiteral("back"), 1));
    QVERIFY(!chatLog->editOwnMessage(ChatLogIdx(9), false, QStringLiteral("none"), 1));
    QCOMPARE(updated.count(), 2);
    QCOMPARE(chatLog->at(ChatLogIdx(1)).getContentAsMessage().message.content,
             QStringLiteral("see you"));
    QVERIFY(!chatLog->at(ChatLogIdx(1)).getContentAsMessage().marks.edited);
    QCOMPARE(chatLog->at(ChatLogIdx(0)).getContentAsMessage().marks.editedAt, quint32(1763380900));
}

QTEST_GUILESS_MAIN(TestSessionChatLog)
#include "sessionchatlog_test.moc"
