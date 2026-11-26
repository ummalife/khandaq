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

#include "src/model/air/airgroup.h"

#include <QHash>
#include <QString>
#include <QVector>

namespace Air {

/**
 * @mentions in a group, as Telegram has them (DESIGN-group-contacts-private-mentions.md, part 3).
 * The wire format is the one the three clients read since 2026-08: a block before the words, after
 * the header of a reply when there is one,
 *
 *     [KQ|m|<MEMBER KEY>:<handle>;<MEMBER KEY>:<handle>][KQ/m]\n<words with @handle in them>
 *
 * where a key is the member's key in the group (GroupMember::publicKey). A handle is made the same
 * way on every client from the member's name in the group (mentionHandle, mentionHandles).
 */
struct Mention
{
    /** The member's key in the group, 64 hexadecimal characters in upper case. */
    QString key;
    /** The handle, without its "@", as the words carry it. */
    QString handle;

    bool operator==(const Mention& other) const;
};

/** A member the list over the field offers, with the handle that choosing them puts in. */
struct MentionChoice
{
    GroupMember member;
    QString handle;
};

/** Where "@handle" stands in words, the "@" included, and whose it is. */
struct MentionSpan
{
    int start = 0;
    int length = 0;
    QString key;

    bool operator==(const MentionSpan& other) const;
};

/**
 * The handle a name gives, as GroupMentionHelper.mentionHandleFromName makes it on Android: the
 * name up to " (", its letters and decimal digits of any script, with the combining marks after
 * them (Indic vowel signs, Arabic harakat); a run of spaces, "-" or "." becomes one "_" (none at the
 * start), everything else is dropped, and "_" at the end is trimmed. Empty when nothing is left. A
 * Cyrillic or Arabic name of two words gives the two words joined by "_", in their own script.
 */
QString mentionHandle(const QString& name);

/**
 * The handle of every member of members, you included, by key in upper case: mentionHandle of the
 * name, or "u" and the first 8 hex digits of the key when the name gives none. A handle that two
 * members would share (in any case) gets "_" and the first 4 hex digits of the key on each of them,
 * or the first 8 when those are alike too, so that no one's handle depends on the order of the
 * members. A member without a key has none.
 */
QHash<QString, QString> mentionHandles(const QVector<GroupMember>& members);

/**
 * The members the list over the field offers for query, the words typed after the "@": all of them
 * but you and those without a key, when their handle starts with the query or their name holds
 * it, case and marks ignored, in any script. Online ones first, then by name.
 */
QVector<MentionChoice> mentionChoices(const QVector<GroupMember>& members, const QString& query);

/**
 * Whether the caret stands in a mention being typed: an "@" at the start of the text or after a
 * space or a line break, then only letters, marks, digits and "_" up to the caret. Sets start to
 * the place of the "@" and query to what follows it up to the caret.
 */
bool mentionQueryAt(const QString& text, int caret, int* start, QString* query);
/** Where the word of the "@" at start ends: after its letters, marks, digits and "_". */
int mentionTokenEnd(const QString& text, int start);

/**
 * The mentions of words as they go: every "@token" (@([\p{L}\p{M}\p{N}_]+)) that names a member
 * other than you, with the token as written. A token names a member, case ignored, by the handle
 * mentionHandles gives them; by the handle of their name alone (or "u" and 8 hex digits) when no
 * other member but you has it; or by that with "_" and 4 or 8 hex digits of their key, as another
 * client may have made it. Each pair of key and token goes once, in the order of the words; any
 * other token stays plain text.
 */
QVector<Mention> collectMentions(const QString& words, const QVector<GroupMember>& members);
/**
 * The block that carries mentions, with the line break after it; empty for none. The fields are
 * cleaned as GroupMentionHelper.sanitizeField cleans them: no "|", ";", ":", line breaks as spaces.
 */
QString mentionBlock(const QVector<Mention>& mentions);

/**
 * The mentions in the block of a raw message, after the header of a reply when there is one; none
 * when it has no block. Keys come in upper case; a pair without a key or a handle is left out.
 */
QVector<Mention> readMentions(const QString& raw);
/** Whether the block of a raw message names key, in any case of its hex digits. */
bool mentionsKey(const QString& raw, const QString& key);
/**
 * Where the handles of mentions stand in words: each "@token" that is the handle of one of them,
 * case ignored, in the order of the words.
 */
QVector<MentionSpan> mentionSpans(const QString& words, const QVector<Mention>& mentions);

/** The link a transcript makes of a mention of the member with this key: "kqmention:" and the key. */
QString mentionLink(const QString& key);
/** The member's key, upper case, of a link mentionLink made; empty for any other link. */
QString mentionLinkKey(const QString& link);

} // namespace Air
