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

#pragma once

#include <QByteArray>
#include <QString>
#include <QStringList>
#include <QVector>

#include <cstddef>
#include <cstdint>

namespace Air {

/**
 * An edit, a deletion or a reaction (redesign stage 3, item 10), as Android sends them: the
 * lossless packets 186, 187 and 188 between two people and the NGC custom packets 0x41, 0x42 and
 * 0x43 in a group, byte for byte as khandaq-android-trifa HelperMessageEdit, HelperMessageDelete
 * and HelperMessageReaction build them (MessagePacketFormatTest). Nothing here sends one.
 */
struct MessageEffect
{
    enum class Kind
    {
        Edit,
        Delete,
        Reaction,
    };
    /** What the packet points at. */
    enum class Anchor
    {
        Text,
        File,
    };

    Kind kind = Kind::Edit;
    Anchor anchor = Anchor::Text;
    /**
     * The message it points at. Between two people: the 32-byte msgV3 hash of a text, or the Tox
     * file id of a file. In a group: the 4-byte id toxcore gave a text, big-endian, or the 32-byte
     * id of a file.
     */
    QByteArray anchorId;
    /** A reaction in a group names the 32-byte key of who wrote the message; empty otherwise. */
    QByteArray author;
    /** When it was done, by the sender's clock, in seconds since 1970. */
    quint32 time = 0;
    /** The new words of an edit. */
    QString text;
    /** The emoji of a reaction; a removal only informs (Android sends "*"). */
    QString emoji;
    /** A reaction added, or taken back. */
    bool add = false;
};

/**
 * Reads a friend's lossless packet 186, 187 or 188 of version 1. False, and nothing read, for any
 * other id, version, length or kind of anchor, and for an edit without words or a reaction whose
 * emoji is no emoji.
 */
bool readFriendPacket(const QByteArray& packet, MessageEffect* effect);
/** Reads a group's custom packet 0x41, 0x42 or 0x43 of version 1, by the same rules. */
bool readGroupPacket(const QByteArray& packet, MessageEffect* effect);
/** Whether a friend's lossless packet carries the id of an edit, a deletion or a reaction. */
bool isFriendMarkPacket(const uint8_t* data, size_t length);
/** Whether a group's custom packet carries the NGC header of an edit, a deletion or a reaction. */
bool isGroupMarkPacket(const uint8_t* data, size_t length);
/** The id toxcore gave a group message as the packets of the group carry it: 4 bytes, big-endian. */
QByteArray groupWireId(quint32 messageId);

/** One emoji on a message and who chose it. */
struct Reaction
{
    QString emoji;
    /** Who chose it, by LocalStore::hashedKey of their key, in the order they came. */
    QStringList actors;

    bool operator==(const Reaction& other) const;
};

/**
 * What happened to a message after it came: an edit by its author and the reactions on it. One
 * reaction per person, as on Android: a new one takes the place of the old, a removal takes it back.
 */
struct EntryMarks
{
    /** As HelperMessageReaction caps a row, so that nobody can balloon it. */
    static constexpr int maxEmoji = 12;
    static constexpr int maxActors = 128;

    bool edited = false;
    /** When the author last changed it, by their clock, in seconds since 1970. */
    quint32 editedAt = 0;
    QVector<Reaction> reactions;

    bool isEmpty() const;
    bool operator==(const EntryMarks& other) const;
    bool operator!=(const EntryMarks& other) const;
    /** Applies one reaction of actor; returns whether anything changed. */
    bool react(const QString& actor, const QString& emoji, bool add);
    /**
     * Applies an edit of its author made at time; false for one older than the edit shown, or the
     * same edit again.
     */
    bool acceptsEdit(quint32 time, const QString& newText, const QString& shownText) const;
    /** "❤️ 2  👍": each emoji, with its count once more than one person chose it; empty for none. */
    QString reactionsText() const;
};

/**
 * Where an entry's marks are kept between launches: a hash under a hash of its conversation, so a
 * removed contact or a left group takes its marks along (entryKeyPrefix). A text is known by who
 * wrote it and when, to the millisecond, as the history keeps it; a file by its Tox file id.
 */
QString textEntryKey(const QString& chatKey, const QString& authorKey, qint64 timeMs);
QString fileEntryKey(const QString& chatKey, const QByteArray& fileId);
QString entryKeyPrefix(const QString& chatKey);

/**
 * Keeps, between launches, what the history of a conversation has no place for: the marks of its
 * entries and the ids a group gave its messages, both under the keys above.
 */
class EntryMarksStore
{
public:
    virtual ~EntryMarksStore();
    /** The marks kept under key; empty marks when none are. */
    virtual EntryMarks entryMarks(const QString& key) const = 0;
    /** Keeps marks under key; empty marks forget it. */
    virtual void setEntryMarks(const QString& key, const EntryMarks& marks) = 0;
    /** The id a group gave the message kept under key (groupWireId); empty when none is kept. */
    virtual QByteArray wireId(const QString& key) const = 0;
    /** Keeps the id a group gave a message; an empty id forgets it. */
    virtual void setWireId(const QString& key, const QByteArray& wireId) = 0;
};

} // namespace Air
