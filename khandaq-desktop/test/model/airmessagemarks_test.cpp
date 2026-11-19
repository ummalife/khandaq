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
    void seen189MatchesTheSharedVectors();
    void seen189OfAnotherShapeIsDropped();
    void groupIdsAreFourBytesBigEndian();
    void onePersonHasOneReaction();
    void reactionsAreCapped();
    void theReactionsReadAsEmojiWithTheirCounts();
    void theLatestEditWins();
    void entriesAreKeptUnderTheirConversation();
    void theQuickReactionsAreTheSevenOfThePhones();
    void ourReaction188IsWhatTheDemoContactSends();
    void ourReaction0x43PadsTheIdAndNamesTheAuthor();
    void aReactionTheOthersWouldDropIsNotBuilt();
    void aFileReactionFromIosNamesNoAuthor();
    void ourReactionIsKeptUnderOwn();
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

void TestAirMessageMarks::seen189MatchesTheSharedVectors()
{
    // KHANDAQ (0.2.57): the test vectors of the read receipts protocol, which Android and iOS
    // build and read byte for byte too.
    QByteArray countingId;
    for (int i = 0; i < 32; ++i) {
        countingId.append(static_cast<char>(i));
    }
    const QByteArray textVector = QByteArray::fromHex(
        "BD4B510101000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F6553F100");
    const QByteArray fileVector = QByteArray::fromHex(
        "BD4B510102FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF00000000");
    QCOMPARE(textVector.size(), 41);
    QCOMPARE(fileVector.size(), 41);

    SeenReceipt text;
    text.anchor = MessageEffect::Anchor::Text;
    text.anchorId = countingId;
    text.time = 0x6553F100;
    QCOMPARE(seenPacket(text), textVector);

    SeenReceipt file;
    file.anchor = MessageEffect::Anchor::File;
    file.anchorId = QByteArray(32, '\xFF');
    file.time = 0;
    QCOMPARE(seenPacket(file), fileVector);

    SeenReceipt read;
    QVERIFY(readSeenPacket(textVector, &read));
    QCOMPARE(read.anchor, MessageEffect::Anchor::Text);
    QCOMPARE(read.anchorId, countingId);
    QCOMPARE(read.time, quint32{0x6553F100});
    QVERIFY(readSeenPacket(fileVector, &read));
    QCOMPARE(read.anchor, MessageEffect::Anchor::File);
    QCOMPARE(read.anchorId, QByteArray(32, '\xFF'));
    QCOMPARE(read.time, quint32{0});

    // An id of another size makes no packet.
    text.anchorId = countingId.left(31);
    QVERIFY(seenPacket(text).isEmpty());

    // The receipt goes its own way, not as an edit, deletion or reaction.
    const auto data = reinterpret_cast<const uint8_t*>(textVector.constData());
    QVERIFY(isSeenPacket(data, static_cast<size_t>(textVector.size())));
    QVERIFY(!routedAsFriendMark(textVector));
    QVERIFY(!isSeenPacket(data, 0));
}

void TestAirMessageMarks::seen189OfAnotherShapeIsDropped()
{
    const QByteArray good = QByteArray::fromHex(
        "BD4B510101000102030405060708090A0B0C0D0E0F101112131415161718191A1B1C1D1E1F6553F100");
    SeenReceipt read;
    read.anchorId = QByteArrayLiteral("untouched");
    QVERIFY(readSeenPacket(good, &read));

    const auto rejected = [](const QByteArray& packet) {
        SeenReceipt receipt;
        receipt.anchorId = QByteArrayLiteral("untouched");
        const bool ok = readSeenPacket(packet, &receipt);
        return !ok && receipt.anchorId == QByteArrayLiteral("untouched");
    };
    // Length: one byte short, one byte more, nothing.
    QVERIFY(rejected(good.left(40)));
    QVERIFY(rejected(good + bytes({0})));
    QVERIFY(rejected(QByteArray()));
    // The file vector as v1 of the SPEC misprinted it, with an id of 28 bytes: 37 bytes in all. It
    // still takes the way of a receipt, never toxext's, and is dropped there.
    const QByteArray misprinted =
        QByteArray::fromHex("BD4B510102") + QByteArray(28, '\xFF') + QByteArray(4, '\0');
    QCOMPARE(misprinted.size(), 37);
    QVERIFY(rejected(misprinted));
    QVERIFY(isSeenPacket(reinterpret_cast<const uint8_t*>(misprinted.constData()),
                         static_cast<size_t>(misprinted.size())));
    // Id and magic.
    for (const int at : {0, 1, 2}) {
        QByteArray other = good;
        other[at] = static_cast<char>(other.at(at) ^ 0x01);
        QVERIFY(rejected(other));
    }
    // Version.
    for (const int version : {0, 2, 255}) {
        QByteArray other = good;
        other[3] = static_cast<char>(version);
        QVERIFY(rejected(other));
    }
    // Anchor type.
    for (const int type : {0, 3, 255}) {
        QByteArray other = good;
        other[4] = static_cast<char>(type);
        QVERIFY(rejected(other));
    }
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

void TestAirMessageMarks::theQuickReactionsAreTheSevenOfThePhones()
{
    const QStringList quick = quickReactions();
    QCOMPARE(quick.size(), 7);
    // The heart keeps its variation selector.
    QCOMPARE(utf8(quick.at(0)), QByteArray::fromHex("E29DA4EFB88F"));
    QCOMPARE(utf8(quick.at(1)), QByteArray::fromHex("F09F918D"));
    QCOMPARE(utf8(quick.at(2)), QByteArray::fromHex("F09F918E"));
    QCOMPARE(utf8(quick.at(3)), QByteArray::fromHex("F09F9882"));
    QCOMPARE(utf8(quick.at(4)), QByteArray::fromHex("F09F98AE"));
    QCOMPARE(utf8(quick.at(5)), QByteArray::fromHex("F09F98A2"));
    QCOMPARE(utf8(quick.at(6)), QByteArray::fromHex("F09F94A5"));
    // Each one goes, and comes back as it went.
    using Anchor = MessageEffect::Anchor;
    for (const QString& emoji : quick) {
        const QByteArray friendPacket =
            friendReactionPacket(Anchor::Text, anchorHash, stamp, emoji, true);
        const MessageEffect sent = readPacket(friendPacket, false);
        QCOMPARE(sent.kind, MessageEffect::Kind::Reaction);
        QCOMPARE(sent.emoji, emoji);
        const QByteArray groupPacket =
            groupReactionPacket(Anchor::Text, wireMessageId, authorKey, stamp, emoji, true);
        const MessageEffect inGroup = readPacket(groupPacket, true);
        QCOMPARE(inGroup.kind, MessageEffect::Kind::Reaction);
        QCOMPARE(inGroup.emoji, emoji);
    }
}

void TestAirMessageMarks::ourReaction188IsWhatTheDemoContactSends()
{
    // infra/demo-contact/khandaq_demo.c send_reaction: 188 'K' 'Q' 1, the kind of anchor, its id,
    // the time, the action, the length of the emoji and the emoji.
    const QString heart = quickReactions().at(0);
    const QByteArray emoji = utf8(heart);
    const QByteArray add =
        friendReactionPacket(MessageEffect::Anchor::Text, anchorHash, stamp, heart, true);
    QCOMPARE(add, friendHead(188) + bytes({1}) + anchorHash + stampBytes
                      + bytes({1, emoji.size()}) + emoji);
    QCOMPARE(add.size(), 43 + emoji.size());
    const MessageEffect reaction = readPacket(add, false);
    QCOMPARE(reaction.kind, MessageEffect::Kind::Reaction);
    QCOMPARE(reaction.anchor, MessageEffect::Anchor::Text);
    QCOMPARE(reaction.anchorId, anchorHash);
    QCOMPARE(reaction.time, stamp);
    QCOMPARE(reaction.emoji, heart);
    QVERIFY(reaction.add);

    // Taken back from a file: action 0 and "*", whichever emoji it was.
    const QByteArray removal =
        friendReactionPacket(MessageEffect::Anchor::File, anchorHash, stamp, heart, false);
    QCOMPARE(removal, friendHead(188) + bytes({2}) + anchorHash + stampBytes + bytes({0, 1, '*'}));
    QCOMPARE(friendReactionPacket(MessageEffect::Anchor::File, anchorHash, stamp, QString(), false),
             removal);
    const MessageEffect taken = readPacket(removal, false);
    QCOMPARE(taken.kind, MessageEffect::Kind::Reaction);
    QCOMPARE(taken.anchor, MessageEffect::Anchor::File);
    QVERIFY(!taken.add);
}

void TestAirMessageMarks::ourReaction0x43PadsTheIdAndNamesTheAuthor()
{
    const QString thumb = quickReactions().at(1);
    const QByteArray emoji = utf8(thumb);
    QByteArray padded = wireMessageId;
    padded.append(QByteArray(28, '\0'));
    const QByteArray add = groupReactionPacket(MessageEffect::Anchor::Text, wireMessageId,
                                               authorKey, stamp, thumb, true);
    QCOMPARE(add, groupHead(0x43) + bytes({1}) + padded + authorKey + stampBytes
                      + bytes({1, emoji.size()}) + emoji);
    QCOMPARE(add.size(), 79 + emoji.size());
    const MessageEffect reaction = readPacket(add, true);
    QCOMPARE(reaction.kind, MessageEffect::Kind::Reaction);
    QCOMPARE(reaction.anchorId, wireMessageId);
    QCOMPARE(reaction.author, authorKey);
    QCOMPARE(reaction.time, stamp);
    QCOMPARE(reaction.emoji, thumb);
    QVERIFY(reaction.add);

    const QByteArray file = groupReactionPacket(MessageEffect::Anchor::File, anchorHash, authorKey,
                                                stamp, thumb, false);
    QCOMPARE(file, groupHead(0x43) + bytes({2}) + anchorHash + authorKey + stampBytes
                       + bytes({0, 1, '*'}));
    const MessageEffect taken = readPacket(file, true);
    QCOMPARE(taken.kind, MessageEffect::Kind::Reaction);
    QCOMPARE(taken.anchor, MessageEffect::Anchor::File);
    QCOMPARE(taken.anchorId, anchorHash);
    QCOMPARE(taken.author, authorKey);
    QVERIFY(!taken.add);
}

void TestAirMessageMarks::aReactionTheOthersWouldDropIsNotBuilt()
{
    using Anchor = MessageEffect::Anchor;
    const QString heart = quickReactions().at(0);
    const auto toFriend = [](const QByteArray& id, const QString& emoji) {
        return friendReactionPacket(Anchor::Text, id, stamp, emoji, true);
    };
    // Between two people: a 32-byte id, and 1 to 16 bytes of a readable emoji.
    QVERIFY(toFriend(anchorHash.left(31), heart).isEmpty());
    QVERIFY(toFriend(wireMessageId, heart).isEmpty());
    QVERIFY(toFriend(anchorHash, QString()).isEmpty());
    QVERIFY(toFriend(anchorHash, QString(17, QLatin1Char('a'))).isEmpty());
    QVERIFY(toFriend(anchorHash, QStringLiteral("a\nb")).isEmpty());
    QVERIFY(!toFriend(anchorHash, QString(16, QLatin1Char('a'))).isEmpty());
    // In a group: a text by its 4-byte id but 0, a file by its 32-byte one, and an author.
    const auto toGroup = [](Anchor anchor, const QByteArray& id, const QByteArray& author,
                            const QString& emoji) {
        return groupReactionPacket(anchor, id, author, stamp, emoji, true);
    };
    QVERIFY(toGroup(Anchor::Text, anchorHash, authorKey, heart).isEmpty());
    QVERIFY(toGroup(Anchor::Text, QByteArray(4, '\0'), authorKey, heart).isEmpty());
    QVERIFY(toGroup(Anchor::File, wireMessageId, authorKey, heart).isEmpty());
    QVERIFY(toGroup(Anchor::Text, wireMessageId, authorKey.left(31), heart).isEmpty());
    QVERIFY(toGroup(Anchor::Text, wireMessageId, QByteArray(32, '\0'), heart).isEmpty());
    QVERIFY(toGroup(Anchor::Text, wireMessageId, authorKey, QString()).isEmpty());
    QVERIFY(!toGroup(Anchor::Text, wireMessageId, authorKey, heart).isEmpty());
}

void TestAirMessageMarks::aFileReactionFromIosNamesNoAuthor()
{
    // iOS puts 32 zero bytes where the author of a file goes (OCTSubmanagerGroupsImpl): the
    // reaction is taken, by the file's id alone.
    const QByteArray emoji = utf8(quickReactions().at(1));
    const QByteArray zeros(32, '\0');
    const MessageEffect file = readPacket(groupHead(0x43) + bytes({2}) + anchorHash + zeros
                                              + stampBytes + bytes({1, emoji.size()}) + emoji,
                                          true);
    QCOMPARE(file.kind, MessageEffect::Kind::Reaction);
    QCOMPARE(file.anchor, MessageEffect::Anchor::File);
    QCOMPARE(file.anchorId, anchorHash);
    QVERIFY(file.author.isEmpty());
    QVERIFY(file.add);

    // A text is told apart by whose it is: its zeros stay, and match no message.
    QByteArray padded = wireMessageId;
    padded.append(QByteArray(28, '\0'));
    const MessageEffect text = readPacket(groupHead(0x43) + bytes({1}) + padded + zeros + stampBytes
                                              + bytes({1, emoji.size()}) + emoji,
                                          true);
    QCOMPARE(text.kind, MessageEffect::Kind::Reaction);
    QCOMPARE(text.author, zeros);
}

void TestAirMessageMarks::ourReactionIsKeptUnderOwn()
{
    const QString own = ownReactionActor();
    QCOMPARE(own, QStringLiteral("-OWN-"));
    // No hashed key of a person (LocalStore::hashedKey) gives it.
    QVERIFY(!QRegularExpression(QStringLiteral("^[0-9a-f]*$")).match(own).hasMatch());
    const QString heart = quickReactions().at(0);
    const QString fire = quickReactions().at(6);
    EntryMarks marks;
    QVERIFY(marks.emojiOf(own).isEmpty());
    QVERIFY(marks.react(QStringLiteral("abc"), heart, true));
    QVERIFY(marks.emojiOf(own).isEmpty());
    QCOMPARE(marks.emojiOf(QStringLiteral("abc")), heart);
    QVERIFY(marks.react(own, fire, true));
    QCOMPARE(marks.emojiOf(own), fire);
    // Another emoji takes the place of ours; the person's stays.
    QVERIFY(marks.react(own, heart, true));
    QCOMPARE(marks.emojiOf(own), heart);
    QCOMPARE(marks.emojiOf(QStringLiteral("abc")), heart);
    QVERIFY(marks.react(own, heart, false));
    QVERIFY(marks.emojiOf(own).isEmpty());
}

QTEST_GUILESS_MAIN(TestAirMessageMarks)
#include "airmessagemarks_test.moc"
