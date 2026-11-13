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

#include "src/model/air/airlocalstore.h"
#include "src/model/air/airmessagemarks.h"

#include <QtTest/QtTest>

#include <initializer_list>

using namespace Air;

namespace {
// The vectors of khandaq-android-trifa MessagePacketFormatTest.java, which builds the packets.
const QByteArray anchorHash =
    QByteArray::fromHex("00112233445566778899AABBCCDDEEFF0123456789ABCDEFFEDCBA9876543210");
const QByteArray authorKey =
    QByteArray::fromHex("A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0");
const QByteArray wireMessageId = QByteArray::fromHex("0A0B0C0D");
/** 1700000000 seconds, 0x6553F100. */
constexpr quint32 stamp = 1700000000;
const QByteArray stampBytes = QByteArray::fromHex("6553F100");
const QByteArray ngcHeader = QByteArray::fromHex("667788113435");
const QByteArray kq = QByteArray("KQ\x01", 3);

QByteArray bytes(std::initializer_list<int> values)
{
    QByteArray out;
    for (const int value : values) {
        out.append(static_cast<char>(value));
    }
    return out;
}

QByteArray friendHead(int id)
{
    return bytes({id}) + kq;
}

QByteArray groupHead(int id)
{
    return ngcHeader + bytes({1, id});
}

QByteArray utf8(const QString& text)
{
    return text.toUtf8();
}

MessageEffect readPacket(const QByteArray& packet, bool group)
{
    MessageEffect effect;
    const bool ok = group ? readGroupPacket(packet, &effect) : readFriendPacket(packet, &effect);
    if (!ok) {
        // A sign that nothing was read: no packet makes an edit without words.
        effect.kind = MessageEffect::Kind::Edit;
        effect.text.clear();
        effect.anchorId.clear();
    }
    return effect;
}

bool accepted(const QByteArray& packet, bool group)
{
    MessageEffect effect;
    return group ? readGroupPacket(packet, &effect) : readFriendPacket(packet, &effect);
}

bool routedAsFriendMark(const QByteArray& packet)
{
    return isFriendMarkPacket(reinterpret_cast<const uint8_t*>(packet.constData()),
                              static_cast<size_t>(packet.size()));
}

bool routedAsGroupMark(const QByteArray& packet)
{
    return isGroupMarkPacket(reinterpret_cast<const uint8_t*>(packet.constData()),
                             static_cast<size_t>(packet.size()));
}
} // namespace

/**
 * The shared minimum (redesign stage 3, item 10): the edits, deletions and reactions Android sends,
 * the lossless packets 186, 187 and 188 between two people and the NGC custom packets 0x41, 0x42
 * and 0x43 in a group, read from the exact bytes HelperMessageEdit, HelperMessageDelete and
 * HelperMessageReaction build (MessagePacketFormatTest). A packet of another version, length or
 * kind is dropped, as khandaq-ios AirPacketTests checks. Then the marks they leave on an entry.
 */
class TestAirMessageMarks : public QObject
{
    Q_OBJECT
private slots:
    void friendEdit186IsIdMagicVersionHashTimeAndTheNewText();
    void friendEditTakesOneTo1300BytesOfWords();
    void friendDelete187IsFortyBytesForATextAndFortyOneForAFile();
    void friendReaction188CarriesAnchorTimeActionAndTheEmoji();
    void aFriendPacketOfAnotherShapeIsDropped();
    void groupEdit0x41IsTheNgcHeaderMessageIdTimeAndTheNewText();
    void groupDelete0x42IsSixteenBytesForATextAndFortyFiveForAFile();
    void groupReaction0x43PadsTheMessageIdAndNamesTheAuthor();
    void aGroupPacketOfAnotherShapeIsDropped();
    void theRoutingTakesOnlyTheseSixPackets();
    void groupIdsAreFourBytesBigEndian();
    void onePersonHasOneReaction();
    void reactionsAreCapped();
    void theReactionsReadAsEmojiWithTheirCounts();
    void theLatestEditWins();
    void entriesAreKeptUnderTheirConversation();
};

void TestAirMessageMarks::friendEdit186IsIdMagicVersionHashTimeAndTheNewText()
{
    const QString text = QStringLiteral("Hi é");
    const MessageEffect edit =
        readPacket(friendHead(186) + anchorHash + stampBytes + utf8(text), false);
    QCOMPARE(edit.kind, MessageEffect::Kind::Edit);
    QCOMPARE(edit.anchor, MessageEffect::Anchor::Text);
    QCOMPARE(edit.anchorId, anchorHash);
    QCOMPARE(edit.time, stamp);
    QCOMPARE(edit.text, text);
    QVERIFY(edit.author.isEmpty());
}

void TestAirMessageMarks::friendEditTakesOneTo1300BytesOfWords()
{
    const QByteArray longest(1300, 'a');
    const QByteArray packet = friendHead(186) + anchorHash + stampBytes + longest;
    QCOMPARE(packet.size(), 40 + 1300);
    QCOMPARE(readPacket(packet, false).text.size(), 1300);
    QVERIFY(!accepted(packet + "a", false));
    // No words, or only spaces, is no edit.
    QVERIFY(!accepted(friendHead(186) + anchorHash + stampBytes, false));
    QVERIFY(!accepted(friendHead(186) + anchorHash + stampBytes + "  ", false));
    // Cyrillic letters take two bytes each.
    QCOMPARE(readPacket(friendHead(186) + anchorHash + stampBytes + utf8(QStringLiteral("Исправил")), false)
                 .text,
             QStringLiteral("Исправил"));
}

void TestAirMessageMarks::friendDelete187IsFortyBytesForATextAndFortyOneForAFile()
{
    const QByteArray text = friendHead(187) + anchorHash + stampBytes;
    QCOMPARE(text.size(), 40);
    const MessageEffect textDelete = readPacket(text, false);
    QCOMPARE(textDelete.kind, MessageEffect::Kind::Delete);
    QCOMPARE(textDelete.anchor, MessageEffect::Anchor::Text);
    QCOMPARE(textDelete.anchorId, anchorHash);
    QCOMPARE(textDelete.time, stamp);

    // A file is anchored on its Tox file id, after the anchor type 2.
    const QByteArray file = friendHead(187) + bytes({2}) + anchorHash + stampBytes;
    QCOMPARE(file.size(), 41);
    const MessageEffect fileDelete = readPacket(file, false);
    QCOMPARE(fileDelete.kind, MessageEffect::Kind::Delete);
    QCOMPARE(fileDelete.anchor, MessageEffect::Anchor::File);
    QCOMPARE(fileDelete.anchorId, anchorHash);
    QCOMPARE(fileDelete.time, stamp);

    // The time is four bytes, big-endian.
    QCOMPARE(readPacket(friendHead(187) + anchorHash + bytes({1, 2, 3, 4}), false).time,
             quint32{0x01020304});
}

void TestAirMessageMarks::friendReaction188CarriesAnchorTimeActionAndTheEmoji()
{
    const QString heart = QStringLiteral("❤️");
    const QByteArray emoji = utf8(heart);
    const QByteArray add =
        friendHead(188) + bytes({1}) + anchorHash + stampBytes + bytes({1, emoji.size()}) + emoji;
    QCOMPARE(add.size(), 43 + emoji.size());
    const MessageEffect reaction = readPacket(add, false);
    QCOMPARE(reaction.kind, MessageEffect::Kind::Reaction);
    QCOMPARE(reaction.anchor, MessageEffect::Anchor::Text);
    QCOMPARE(reaction.anchorId, anchorHash);
    QCOMPARE(reaction.time, stamp);
    QCOMPARE(reaction.emoji, heart);
    QVERIFY(reaction.add);

    // Taking one's own reaction back: action 0, and the emoji only informs.
    const QByteArray removal =
        friendHead(188) + bytes({2}) + anchorHash + stampBytes + bytes({0, 1, '*'});
    QCOMPARE(removal.size(), 44);
    const MessageEffect taken = readPacket(removal, false);
    QCOMPARE(taken.kind, MessageEffect::Kind::Reaction);
    QCOMPARE(taken.anchor, MessageEffect::Anchor::File);
    QVERIFY(!taken.add);

    // One to sixteen bytes of emoji.
    const QByteArray sixteen(16, 'a');
    QVERIFY(accepted(friendHead(188) + bytes({1}) + anchorHash + stampBytes + bytes({1, 16}) + sixteen,
                     false));
    QVERIFY(!accepted(friendHead(188) + bytes({1}) + anchorHash + stampBytes + bytes({1, 17})
                          + sixteen + "a",
                      false));
}

void TestAirMessageMarks::aFriendPacketOfAnotherShapeIsDropped()
{
    // Another version.
    QVERIFY(!accepted(bytes({186, 'K', 'Q', 2}) + anchorHash + bytes({0, 0, 0, 1, 'x'}), false));
    // A delete of 39 bytes, or of 41 that names no file.
    QVERIFY(!accepted(friendHead(187) + anchorHash + bytes({0, 0, 1}), false));
    QVERIFY(!accepted(friendHead(187) + bytes({1}) + anchorHash + stampBytes, false));
    QVERIFY(!accepted(friendHead(187) + anchorHash + stampBytes + bytes({0, 0}), false));
    // An anchor of an unknown kind, an emoji of no bytes, a length that does not match.
    QVERIFY(!accepted(friendHead(188) + bytes({3}) + anchorHash + stampBytes + bytes({1, 1, 'A'}),
                      false));
    QVERIFY(!accepted(friendHead(188) + bytes({1}) + anchorHash + stampBytes + bytes({1, 0, 'A'}),
                      false));
    QVERIFY(!accepted(friendHead(188) + bytes({1}) + anchorHash + stampBytes + bytes({1, 4, 'A'}),
                      false));
    // An action that is neither add nor remove.
    QVERIFY(!accepted(friendHead(188) + bytes({1}) + anchorHash + stampBytes + bytes({2, 1, 'A'}),
                      false));
    // An added emoji that would turn the line around it, or break it.
    QVERIFY(!accepted(friendHead(188) + bytes({1}) + anchorHash + stampBytes + bytes({1, 3})
                          + utf8(QString(QChar(0x202E))),
                      false));
    QVERIFY(!accepted(friendHead(188) + bytes({1}) + anchorHash + stampBytes + bytes({1, 1, '\n'}),
                      false));
    // Other ids, a foreign mark, and too short to hold anything.
    QVERIFY(!accepted(friendHead(185) + anchorHash + stampBytes + "x", false));
    QVERIFY(!accepted(bytes({186, 'K', 'R', 1}) + anchorHash + stampBytes + "x", false));
    QVERIFY(!accepted(friendHead(186), false));
    QVERIFY(!accepted(QByteArray(), false));
}

void TestAirMessageMarks::groupEdit0x41IsTheNgcHeaderMessageIdTimeAndTheNewText()
{
    const MessageEffect edit = readPacket(groupHead(0x41) + wireMessageId + stampBytes + "ok", true);
    QCOMPARE(edit.kind, MessageEffect::Kind::Edit);
    QCOMPARE(edit.anchor, MessageEffect::Anchor::Text);
    QCOMPARE(edit.anchorId, wireMessageId);
    QCOMPARE(edit.time, stamp);
    QCOMPARE(edit.text, QStringLiteral("ok"));

    const QByteArray longest = groupHead(0x41) + wireMessageId + stampBytes + QByteArray(1300, 'a');
    QCOMPARE(longest.size(), 16 + 1300);
    QVERIFY(accepted(longest, true));
    QVERIFY(!accepted(longest + "a", true));
    QVERIFY(!accepted(groupHead(0x41) + wireMessageId + stampBytes, true));
}

void TestAirMessageMarks::groupDelete0x42IsSixteenBytesForATextAndFortyFiveForAFile()
{
    const QByteArray text = groupHead(0x42) + wireMessageId + stampBytes;
    QCOMPARE(text.size(), 16);
    const MessageEffect textDelete = readPacket(text, true);
    QCOMPARE(textDelete.kind, MessageEffect::Kind::Delete);
    QCOMPARE(textDelete.anchor, MessageEffect::Anchor::Text);
    QCOMPARE(textDelete.anchorId, wireMessageId);
    QCOMPARE(textDelete.time, stamp);

    const QByteArray file = groupHead(0x42) + bytes({2}) + anchorHash + stampBytes;
    QCOMPARE(file.size(), 45);
    const MessageEffect fileDelete = readPacket(file, true);
    QCOMPARE(fileDelete.kind, MessageEffect::Kind::Delete);
    QCOMPARE(fileDelete.anchor, MessageEffect::Anchor::File);
    QCOMPARE(fileDelete.anchorId, anchorHash);
    QCOMPARE(fileDelete.time, stamp);
}

void TestAirMessageMarks::groupReaction0x43PadsTheMessageIdAndNamesTheAuthor()
{
    const QByteArray emoji = utf8(QStringLiteral("👍"));
    QByteArray padded = wireMessageId;
    padded.append(QByteArray(28, '\0'));
    const QByteArray add = groupHead(0x43) + bytes({1}) + padded + authorKey + stampBytes
                           + bytes({1, emoji.size()}) + emoji;
    QCOMPARE(add.size(), 79 + emoji.size());
    const MessageEffect reaction = readPacket(add, true);
    QCOMPARE(reaction.kind, MessageEffect::Kind::Reaction);
    QCOMPARE(reaction.anchor, MessageEffect::Anchor::Text);
    // The message id, without the zeros that pad it to the field.
    QCOMPARE(reaction.anchorId, wireMessageId);
    QCOMPARE(reaction.author, authorKey);
    QCOMPARE(reaction.time, stamp);
    QCOMPARE(reaction.emoji, QStringLiteral("👍"));
    QVERIFY(reaction.add);

    // A file is anchored on its whole 32-byte id; the action stands at byte 77.
    const QByteArray file =
        groupHead(0x43) + bytes({2}) + anchorHash + authorKey + stampBytes + bytes({0, 1, '*'});
    QCOMPARE(file.size(), 80);
    QCOMPARE(file.at(77), '\0');
    const MessageEffect taken = readPacket(file, true);
    QCOMPARE(taken.anchor, MessageEffect::Anchor::File);
    QCOMPARE(taken.anchorId, anchorHash);
    QCOMPARE(taken.author, authorKey);
    QVERIFY(!taken.add);
}

void TestAirMessageMarks::aGroupPacketOfAnotherShapeIsDropped()
{
    const QByteArray edit =
        groupHead(0x41) + wireMessageId + stampBytes + utf8(QStringLiteral("Исправил"));
    QVERIFY(accepted(edit, true));
    // Fifteen bytes, a foreign magic, another version.
    QVERIFY(!accepted(edit.left(15), true));
    QByteArray foreign = edit;
    foreign[0] = 0x67;
    QVERIFY(!accepted(foreign, true));
    QByteArray version = edit;
    version[6] = 2;
    QVERIFY(!accepted(version, true));
    // A delete of 20 bytes, and one whose file anchor is not marked as a file.
    QVERIFY(!accepted(groupHead(0x42) + wireMessageId + stampBytes + bytes({0, 0, 0, 0}), true));
    QVERIFY(!accepted(groupHead(0x42) + bytes({1}) + anchorHash + stampBytes, true));
    // A reaction cut short, of another id, or on an anchor of an unknown kind.
    const QByteArray emoji = utf8(QStringLiteral("👍"));
    const QByteArray reaction = groupHead(0x43) + bytes({1}) + anchorHash + authorKey + stampBytes
                                + bytes({1, emoji.size()}) + emoji;
    QVERIFY(accepted(reaction, true));
    QVERIFY(!accepted(reaction.left(79), true));
    QByteArray otherId = reaction;
    otherId[7] = 0x44;
    QVERIFY(!accepted(otherId, true));
    QByteArray unknownAnchor = reaction;
    unknownAnchor[8] = 3;
    QVERIFY(!accepted(unknownAnchor, true));
    // Id 0 is what Android keeps for a message still on its way: it names nothing.
    QVERIFY(!accepted(groupHead(0x41) + bytes({0, 0, 0, 0}) + stampBytes + "ok", true));
    QVERIFY(!accepted(groupHead(0x42) + bytes({0, 0, 0, 0}) + stampBytes, true));
}

void TestAirMessageMarks::theRoutingTakesOnlyTheseSixPackets()
{
    for (const int id : {186, 187, 188}) {
        QVERIFY(routedAsFriendMark(friendHead(id)));
    }
    // toxext (172), the group invitation (184) and the packets after them go their own way.
    for (const int id : {172, 184, 185, 189}) {
        QVERIFY(!routedAsFriendMark(friendHead(id)));
    }
    QVERIFY(!routedAsFriendMark(QByteArray()));

    for (const int id : {0x41, 0x42, 0x43}) {
        QVERIFY(routedAsGroupMark(groupHead(id)));
    }
    // The NGC file packets share the magic with other ids; a header cut short is nothing.
    for (const int id : {0x11, 0x40, 0x44}) {
        QVERIFY(!routedAsGroupMark(groupHead(id)));
    }
    QVERIFY(!routedAsGroupMark(groupHead(0x41).left(7)));
    QByteArray otherVersion = groupHead(0x41);
    otherVersion[6] = 2;
    QVERIFY(!routedAsGroupMark(otherVersion));
}

void TestAirMessageMarks::groupIdsAreFourBytesBigEndian()
{
    QCOMPARE(groupWireId(0x0A0B0C0D), wireMessageId);
    QCOMPARE(groupWireId(0xDEADBEEF), QByteArray::fromHex("DEADBEEF"));
    QCOMPARE(groupWireId(1), QByteArray::fromHex("00000001"));
}

void TestAirMessageMarks::onePersonHasOneReaction()
{
    EntryMarks marks;
    QVERIFY(marks.react(QStringLiteral("amina"), QStringLiteral("👍"), true));
    QVERIFY(!marks.react(QStringLiteral("amina"), QStringLiteral("👍"), true));
    QVERIFY(marks.react(QStringLiteral("yusuf"), QStringLiteral("👍"), true));
    QCOMPARE(marks.reactions.size(), 1);
    QCOMPARE(marks.reactions.first().actors,
             (QStringList{QStringLiteral("amina"), QStringLiteral("yusuf")}));

    // A new one takes the place of the old.
    QVERIFY(marks.react(QStringLiteral("amina"), QStringLiteral("❤️"), true));
    QCOMPARE(marks.reactions.size(), 2);
    QCOMPARE(marks.reactions.at(0).actors, QStringList{QStringLiteral("yusuf")});
    QCOMPARE(marks.reactions.at(1).emoji, QStringLiteral("❤️"));

    // A removal takes it back, whatever emoji it names; nothing to take back changes nothing.
    QVERIFY(marks.react(QStringLiteral("amina"), QStringLiteral("*"), false));
    QVERIFY(!marks.react(QStringLiteral("amina"), QStringLiteral("*"), false));
    QVERIFY(marks.react(QStringLiteral("yusuf"), QStringLiteral("*"), false));
    QVERIFY(marks.isEmpty());
}

void TestAirMessageMarks::reactionsAreCapped()
{
    EntryMarks marks;
    for (int i = 0; i < EntryMarks::maxEmoji + 3; ++i) {
        marks.react(QStringLiteral("actor%1").arg(i), QString(QChar(0x41 + i)), true);
    }
    QCOMPARE(marks.reactions.size(), EntryMarks::maxEmoji);

    EntryMarks crowd;
    for (int i = 0; i < EntryMarks::maxActors + 5; ++i) {
        crowd.react(QStringLiteral("actor%1").arg(i), QStringLiteral("👍"), true);
    }
    QCOMPARE(crowd.reactions.first().actors.size(), EntryMarks::maxActors);
}

void TestAirMessageMarks::theReactionsReadAsEmojiWithTheirCounts()
{
    EntryMarks marks;
    QCOMPARE(marks.reactionsText(), QString());
    marks.react(QStringLiteral("amina"), QStringLiteral("❤️"), true);
    marks.react(QStringLiteral("yusuf"), QStringLiteral("❤️"), true);
    marks.react(QStringLiteral("omar"), QStringLiteral("👍"), true);
    QCOMPARE(marks.reactionsText(), QStringLiteral("❤️ 2  👍"));
}

void TestAirMessageMarks::theLatestEditWins()
{
    EntryMarks marks;
    // A message never edited takes any edit.
    QVERIFY(marks.acceptsEdit(0, QStringLiteral("new"), QStringLiteral("old")));
    marks.edited = true;
    marks.editedAt = 100;
    // An older edit, or the same one again, changes nothing; a newer one does.
    QVERIFY(!marks.acceptsEdit(99, QStringLiteral("older"), QStringLiteral("shown")));
    QVERIFY(!marks.acceptsEdit(100, QStringLiteral("shown"), QStringLiteral("shown")));
    QVERIFY(marks.acceptsEdit(100, QStringLiteral("other"), QStringLiteral("shown")));
    QVERIFY(marks.acceptsEdit(101, QStringLiteral("shown"), QStringLiteral("shown")));
}

void TestAirMessageMarks::entriesAreKeptUnderTheirConversation()
{
    const QString chat =
        QStringLiteral("56A1ADE4B1C2D3E4F5061728394A5B6C7D8E9FA0B1C2D3E4F5061728394A5B6C");
    const QString other =
        QStringLiteral("0000ADE4B1C2D3E4F5061728394A5B6C7D8E9FA0B1C2D3E4F5061728394A5B6C");
    const QString sender =
        QStringLiteral("A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0");
    const QString text = textEntryKey(chat, sender, 1700000000123);
    const QString file = fileEntryKey(chat, anchorHash);

    // Under a hash of the conversation, never its key or the author's.
    QVERIFY(text.startsWith(entryKeyPrefix(chat)));
    QVERIFY(file.startsWith(entryKeyPrefix(chat)));
    QVERIFY(!textEntryKey(other, sender, 1700000000123).startsWith(entryKeyPrefix(chat)));
    QVERIFY(!text.contains(sender, Qt::CaseInsensitive) && !text.contains(chat, Qt::CaseInsensitive));
    QCOMPARE(text.size(), 65);
    QCOMPARE(file.size(), 65);

    // The same entry gives the same key, whatever the case of the keys; another gives another.
    QCOMPARE(textEntryKey(chat.toLower(), sender.toLower(), 1700000000123), text);
    QVERIFY(textEntryKey(chat, sender, 1700000000124) != text);
    QVERIFY(fileEntryKey(chat, authorKey) != file);
    QVERIFY(text != file);
}

QTEST_GUILESS_MAIN(TestAirMessageMarks)
#include "airmessagemarks_test.moc"
