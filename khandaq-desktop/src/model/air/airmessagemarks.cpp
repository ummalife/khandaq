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

#include "airmessagemarks.h"

#include "src/model/air/airlocalstore.h"

#include <QCryptographicHash>

#include <algorithm>
#include <cstring>

namespace Air {

namespace {
// Between two people: the packet id, 'K', 'Q' and the version, then the anchor and the time.
constexpr uint8_t friendEditId = 186;
constexpr uint8_t friendDeleteId = 187;
constexpr uint8_t friendReactionId = 188;
constexpr uint8_t kqVersion = 1;
constexpr int kqHeader = 4;
constexpr int anchorLength = 32;
constexpr int timeLength = 4;
// 186 and 187: the header, the 32-byte anchor and the time; a file delete adds its anchor type.
constexpr int friendBase = kqHeader + anchorLength + timeLength;
// 188: the header, the anchor type, the anchor, the time, the action and the emoji length.
constexpr int friendReactionBase = kqHeader + 1 + anchorLength + timeLength + 2;

// In a group: the NGC magic, the version and the packet id.
const uint8_t ngcMagic[] = {0x66, 0x77, 0x88, 0x11, 0x34, 0x35};
constexpr uint8_t ngcVersion = 1;
constexpr int ngcHeader = 8;
constexpr uint8_t groupEditId = 0x41;
constexpr uint8_t groupDeleteId = 0x42;
constexpr uint8_t groupReactionId = 0x43;
constexpr int messageIdLength = 4;
// 0x41 and the text form of 0x42: the header, the message id and the time.
constexpr int groupBase = ngcHeader + messageIdLength + timeLength;
// The file form of 0x42: the header, the anchor type, the 32-byte anchor and the time.
constexpr int groupFileDelete = ngcHeader + 1 + anchorLength + timeLength;
// 0x43: the header, the anchor type, the anchor, the author, the time, the action, the length.
constexpr int groupReactionBase = ngcHeader + 1 + anchorLength + anchorLength + timeLength + 2;

constexpr uint8_t anchorText = 1;
constexpr uint8_t anchorFile = 2;
constexpr int maxEditBytes = 1300;
constexpr int maxEmojiBytes = 16;

quint32 bigEndian(const uint8_t* bytes)
{
    return (static_cast<quint32>(bytes[0]) << 24) | (static_cast<quint32>(bytes[1]) << 16)
           | (static_cast<quint32>(bytes[2]) << 8) | static_cast<quint32>(bytes[3]);
}

const uint8_t* bytesOf(const QByteArray& packet)
{
    return reinterpret_cast<const uint8_t*>(packet.constData());
}

QString utf8(const uint8_t* bytes, int length)
{
    return QString::fromUtf8(reinterpret_cast<const char*>(bytes), length);
}

/** toxcore's id 0 is what Android stores for a message still on its way: it names nothing. */
bool isNamedMessage(const QByteArray& wireId)
{
    return wireId.count('\0') != wireId.size();
}

/**
 * An emoji a person may add: readable, on one line, without what would turn the line around it.
 * The joiner and the variation selectors of emoji sequences are kept.
 */
bool isReadableEmoji(const QString& emoji)
{
    if (emoji.trimmed().isEmpty()) {
        return false;
    }
    for (const QChar character : emoji) {
        const ushort code = character.unicode();
        const bool bidiControl = (code >= 0x202A && code <= 0x202E)
                                 || (code >= 0x2066 && code <= 0x2069) || code == 0x200E
                                 || code == 0x200F;
        const QChar::Category category = character.category();
        if (character == QChar::ReplacementCharacter || bidiControl || category == QChar::Other_Control
            || category == QChar::Separator_Line || category == QChar::Separator_Paragraph) {
            return false;
        }
    }
    return true;
}

/** The reaction part shared by 188 and 0x43: action and emoji, at the offsets of each. */
bool readReaction(const uint8_t* bytes, int length, int actionAt, MessageEffect* effect)
{
    const uint8_t action = bytes[actionAt];
    const int emojiLength = bytes[actionAt + 1];
    if (action > 1 || emojiLength < 1 || emojiLength > maxEmojiBytes
        || length != actionAt + 2 + emojiLength) {
        return false;
    }
    effect->kind = MessageEffect::Kind::Reaction;
    effect->add = action == 1;
    effect->emoji = utf8(bytes + actionAt + 2, emojiLength);
    return !effect->add || isReadableEmoji(effect->emoji);
}

QString digest(const QString& text)
{
    const QByteArray hash = QCryptographicHash::hash(text.toUtf8(), QCryptographicHash::Sha256);
    return QString::fromLatin1(hash.left(16).toHex());
}
} // namespace

constexpr int EntryMarks::maxEmoji;
constexpr int EntryMarks::maxActors;

bool readFriendPacket(const QByteArray& packet, MessageEffect* effect)
{
    const int length = packet.size();
    if (length < friendBase) {
        return false;
    }
    const uint8_t* const bytes = bytesOf(packet);
    if (bytes[1] != 'K' || bytes[2] != 'Q' || bytes[3] != kqVersion) {
        return false;
    }

    MessageEffect read;
    switch (bytes[0]) {
    case friendEditId: {
        const int textLength = length - friendBase;
        if (textLength < 1 || textLength > maxEditBytes) {
            return false;
        }
        read.kind = MessageEffect::Kind::Edit;
        read.anchor = MessageEffect::Anchor::Text;
        read.anchorId = packet.mid(kqHeader, anchorLength);
        read.time = bigEndian(bytes + kqHeader + anchorLength);
        read.text = utf8(bytes + friendBase, textLength);
        break;
    }
    case friendDeleteId:
        read.kind = MessageEffect::Kind::Delete;
        if (length == friendBase) {
            read.anchor = MessageEffect::Anchor::Text;
            read.anchorId = packet.mid(kqHeader, anchorLength);
            read.time = bigEndian(bytes + kqHeader + anchorLength);
        } else if (length == friendBase + 1 && bytes[kqHeader] == anchorFile) {
            read.anchor = MessageEffect::Anchor::File;
            read.anchorId = packet.mid(kqHeader + 1, anchorLength);
            read.time = bigEndian(bytes + kqHeader + 1 + anchorLength);
        } else {
            return false;
        }
        break;
    case friendReactionId: {
        if (length <= friendReactionBase) {
            return false;
        }
        const uint8_t type = bytes[kqHeader];
        if (type != anchorText && type != anchorFile) {
            return false;
        }
        read.anchor = type == anchorText ? MessageEffect::Anchor::Text : MessageEffect::Anchor::File;
        read.anchorId = packet.mid(kqHeader + 1, anchorLength);
        read.time = bigEndian(bytes + kqHeader + 1 + anchorLength);
        if (!readReaction(bytes, length, friendReactionBase - 2, &read)) {
            return false;
        }
        break;
    }
    default:
        return false;
    }

    if (read.kind == MessageEffect::Kind::Edit && read.text.trimmed().isEmpty()) {
        return false;
    }
    *effect = read;
    return true;
}

bool readGroupPacket(const QByteArray& packet, MessageEffect* effect)
{
    const int length = packet.size();
    if (length < groupBase) {
        return false;
    }
    const uint8_t* const bytes = bytesOf(packet);
    if (std::memcmp(bytes, ngcMagic, sizeof(ngcMagic)) != 0 || bytes[6] != ngcVersion) {
        return false;
    }

    MessageEffect read;
    switch (bytes[7]) {
    case groupEditId: {
        const int textLength = length - groupBase;
        if (textLength < 1 || textLength > maxEditBytes) {
            return false;
        }
        read.kind = MessageEffect::Kind::Edit;
        read.anchor = MessageEffect::Anchor::Text;
        read.anchorId = packet.mid(ngcHeader, messageIdLength);
        read.time = bigEndian(bytes + ngcHeader + messageIdLength);
        read.text = utf8(bytes + groupBase, textLength);
        break;
    }
    case groupDeleteId:
        read.kind = MessageEffect::Kind::Delete;
        if (length == groupBase) {
            read.anchor = MessageEffect::Anchor::Text;
            read.anchorId = packet.mid(ngcHeader, messageIdLength);
            read.time = bigEndian(bytes + ngcHeader + messageIdLength);
        } else if (length == groupFileDelete && bytes[ngcHeader] == anchorFile) {
            read.anchor = MessageEffect::Anchor::File;
            read.anchorId = packet.mid(ngcHeader + 1, anchorLength);
            read.time = bigEndian(bytes + ngcHeader + 1 + anchorLength);
        } else {
            return false;
        }
        break;
    case groupReactionId: {
        if (length <= groupReactionBase) {
            return false;
        }
        const uint8_t type = bytes[ngcHeader];
        if (type != anchorText && type != anchorFile) {
            return false;
        }
        // A text is anchored on its 4-byte id, padded with zeros to the 32 bytes of the field.
        read.anchor = type == anchorText ? MessageEffect::Anchor::Text : MessageEffect::Anchor::File;
        read.anchorId = packet.mid(ngcHeader + 1, type == anchorText ? messageIdLength : anchorLength);
        read.author = packet.mid(ngcHeader + 1 + anchorLength, anchorLength);
        read.time = bigEndian(bytes + ngcHeader + 1 + 2 * anchorLength);
        if (!readReaction(bytes, length, groupReactionBase - 2, &read)) {
            return false;
        }
        break;
    }
    default:
        return false;
    }

    if (read.anchor == MessageEffect::Anchor::Text && !isNamedMessage(read.anchorId)) {
        return false;
    }
    if (read.kind == MessageEffect::Kind::Edit && read.text.trimmed().isEmpty()) {
        return false;
    }
    *effect = read;
    return true;
}

bool isFriendMarkPacket(const uint8_t* data, size_t length)
{
    return length > 0 && data[0] >= friendEditId && data[0] <= friendReactionId;
}

bool isGroupMarkPacket(const uint8_t* data, size_t length)
{
    return length >= static_cast<size_t>(ngcHeader)
           && std::memcmp(data, ngcMagic, sizeof(ngcMagic)) == 0 && data[6] == ngcVersion
           && data[7] >= groupEditId && data[7] <= groupReactionId;
}

QByteArray groupWireId(quint32 messageId)
{
    QByteArray bytes(messageIdLength, '\0');
    bytes[0] = static_cast<char>((messageId >> 24) & 0xFF);
    bytes[1] = static_cast<char>((messageId >> 16) & 0xFF);
    bytes[2] = static_cast<char>((messageId >> 8) & 0xFF);
    bytes[3] = static_cast<char>(messageId & 0xFF);
    return bytes;
}

bool Reaction::operator==(const Reaction& other) const
{
    return emoji == other.emoji && actors == other.actors;
}

bool EntryMarks::isEmpty() const
{
    return !edited && reactions.isEmpty();
}

bool EntryMarks::operator==(const EntryMarks& other) const
{
    return edited == other.edited && editedAt == other.editedAt && reactions == other.reactions;
}

bool EntryMarks::operator!=(const EntryMarks& other) const
{
    return !(*this == other);
}

bool EntryMarks::react(const QString& actor, const QString& emoji, bool add)
{
    // The actor's previous reaction goes first, wherever it was.
    QVector<Reaction> next;
    next.reserve(reactions.size() + 1);
    for (const Reaction& reaction : reactions) {
        Reaction kept{reaction.emoji, {}};
        for (const QString& who : reaction.actors) {
            if (who != actor) {
                kept.actors.append(who);
            }
        }
        if (!kept.actors.isEmpty()) {
            next.append(kept);
        }
    }

    if (add) {
        auto target = std::find_if(next.begin(), next.end(), [&emoji](const Reaction& reaction) {
            return reaction.emoji == emoji;
        });
        if (target == next.end()) {
            if (next.size() < maxEmoji) {
                next.append(Reaction{emoji, {actor}});
            }
        } else if (target->actors.size() < maxActors) {
            target->actors.append(actor);
        }
    }

    if (next == reactions) {
        return false;
    }
    reactions = next;
    return true;
}

bool EntryMarks::acceptsEdit(quint32 time, const QString& newText, const QString& shownText) const
{
    if (!edited) {
        return true;
    }
    if (time < editedAt) {
        return false;
    }
    return !(time == editedAt && newText == shownText);
}

QString EntryMarks::reactionsText() const
{
    QStringList parts;
    for (const Reaction& reaction : reactions) {
        parts.append(reaction.actors.size() > 1
                         ? reaction.emoji + QLatin1Char(' ') + QString::number(reaction.actors.size())
                         : reaction.emoji);
    }
    return parts.join(QStringLiteral("  "));
}

QString entryKeyPrefix(const QString& chatKey)
{
    return LocalStore::hashedKey(chatKey) + QLatin1Char('/');
}

QString textEntryKey(const QString& chatKey, const QString& authorKey, qint64 timeMs)
{
    return entryKeyPrefix(chatKey)
           + digest(QStringLiteral("T|%1|%2|%3")
                        .arg(chatKey.toUpper(), authorKey.toUpper(), QString::number(timeMs)));
}

QString fileEntryKey(const QString& chatKey, const QByteArray& fileId)
{
    return entryKeyPrefix(chatKey)
           + digest(QStringLiteral("F|%1|%2").arg(chatKey.toUpper(),
                                                  QString::fromLatin1(fileId.toHex().toUpper())));
}

EntryMarksStore::~EntryMarksStore() = default;

} // namespace Air
