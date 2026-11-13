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

#include "src/model/air/airmodel.h"

#include <QDate>
#include <QDateTime>
#include <QPair>
#include <QSet>
#include <QString>
#include <QStringList>
#include <QVector>

/**
 * The conversation with one person (design/redesign/02-plan.md, screens 5, 6, 7, 10 and 16) as
 * plain values.
 *
 * A port of khandaq-ios/Antidote/AirConversationModel.swift and of the Android
 * AirConversationModel.java, with the same rules and test vectors: which state a message is in,
 * where a run of one author starts, the day marks, the link, the hint and the counter under the
 * field, the key in groups, the day strip and the best hours. Nothing here touches Tox, the
 * database or a widget; AirStrings says all of it in words.
 *
 * Days and hours are counted in the time zone of the dates given: the app passes local times.
 */
namespace Air {

/** The state of one of our own messages, as the transcript and the tray draw it. */
enum class EntryStatus
{
    /** Incoming messages and notes to yourself: no mark at all. */
    None,
    /** Waits on this device until the person is online (an amber clock, in the tray). */
    Transit,
    /** The person is online and the message is leaving right now (a faint clock). */
    Sending,
    /** Left this device, no receipt yet (one check). */
    Sent,
    /** The person's device confirmed it (two checks). */
    Delivered,
    /** The network gave up on it; "Retry" sends the same text again (red). */
    Failed,
};

/**
 * An outgoing text: handedOver turns true once toxcore has taken the message, isDelivered with
 * the receipt. Undelivered while the person is away means it waits for them: the queue sends it
 * again when they come online.
 */
EntryStatus outgoingTextStatus(bool isSaved, bool isDelivered, bool handedOver, bool personOnline);

/**
 * A text of a chat log that also knows when the delivery gave up on a message (sent, never
 * confirmed, retries spent): on the desktop that is a broken message.
 */
EntryStatus directTextStatus(bool isSaved, bool isDelivered, bool gaveUp, bool handedOver,
                             bool personOnline);

/** The icon of the design-system sprite for the mark, empty for no mark. */
QString statusIconName(EntryStatus status);

/** The mark of a list row for our last text, by the same rule as the conversation. */
Delivery rowDelivery(EntryStatus status);

/** What the transcript needs to know about a neighbouring entry to decide where a run starts. */
struct RunKey
{
    RunKey() = default;
    RunKey(const QString& who, const QDateTime& when, bool text = true)
        : author{who}
        , date{when}
        , isText{text}
    {
    }

    /** The author: an empty string for ourselves, the friend's key otherwise. */
    QString author;
    QDateTime date;
    /** Text entries share runs; a call or a file stands on its own row and breaks the run after it. */
    bool isText = true;
};

/** Two messages of one author further apart than this start a new run, so the name shows again. */
constexpr qint64 runGapSeconds = 300;

/**
 * An entry opens a new run (and shows its author) unless the entry before it in reading order is
 * a text by the same author on the same day, written at most five minutes earlier. previous is
 * null for the first entry.
 */
bool startsRun(const RunKey& entry, const RunKey* previous);

/** The first word of a name: the author label and the hints say "Amina", not "Amina Safina". */
QString shortName(const QString& name);

/** The day mark above the first entry of a day. */
enum class DayMarkKind
{
    Today,
    Yesterday,
    /** The date itself, with the year only when it is not this year. */
    Date,
};

DayMarkKind dayMarkKind(const QDate& date, const QDate& today);

/** How the link to the person looks right now: the mark beside the presence line, the dossier path. */
enum class LinkState
{
    Direct,
    Relay,
    None,
};

LinkState linkState(const Reach& reach);

/** The line under the message field: what happens to a message sent now. */
struct ComposeHint
{
    enum class Kind
    {
        /** Notes to yourself: they stay on this device. */
        Saved,
        /** The person is online: it goes right away. */
        Live,
        /** The person is offline: it waits in transit and goes on its own. */
        Wait,
        /** Our own node looks for the network: it waits in transit, whoever the person is. */
        SelfConnecting,
        /** This computer has no network: it waits in transit. */
        SelfOffline,
        /** Nobody to send to (the contact was removed): no line. */
        Empty,
    };

    enum class Dot
    {
        Live,
        Transit,
        None,
    };

    Kind kind = Kind::Empty;
    Dot dot = Dot::None;

    /**
     * contactExists is false once the contact was removed. While our own node is not linked
     * (self), the line speaks for it, whatever the other side's state (DESIGN.md, "Network state
     * on the phone").
     */
    static ComposeHint make(bool isSaved, bool contactExists, const Reach& reach,
                            NetworkState self = NetworkState::Direct);

    bool operator==(const ComposeHint& other) const;
};

/**
 * The length counter under the field. Tox carries at most 1372 bytes in one message and toxcore
 * keeps 38 of them for the message id and time of msgV3, so one message holds 1334 bytes of text;
 * the counter shows the room left before that, once only a tenth of it remains. The desktop splits
 * a longer text into several messages at its own core limit, which it passes as limitBytes.
 */
constexpr int composeLimitBytes = 1334;

struct ComposeRoom
{
    /** False while there is plenty of room. */
    bool shown = false;
    /** The bytes left, negative once over the limit. */
    int left = 0;

    bool operator==(const ComposeRoom& other) const;
};

ComposeRoom composeRoom(int byteCount, int limitBytes = composeLimitBytes);

/** A key in groups of four, so two people can read it to each other: "7F3A 91C0 5D2E ...". */
QStringList keyGroups(const QString& key, int size = 4);
/** The groups in lines of perLine, joined by spaces and line breaks. */
QString keyLines(const QString& key, int perLine = 4);

/**
 * The 24 cells of the day strip. Each cell is a clock hour; it shows the most recent time that
 * hour came round, so the hours after now belong to yesterday and the strip always covers the
 * last day.
 */
struct DayStrip
{
    /** Index 0 is the hour from midnight to one o'clock. */
    QVector<bool> online;
    /** The current clock hour, outlined on the strip. */
    int nowHour = 0;

    bool hasMarks() const;
    /** Runs of online hours as (from, to) on the clock: 7 to 9 is the hours 7 and 8. */
    QVector<QPair<int, int>> ranges() const;

    /** hourStarts holds the start of every hour the person was seen online, in seconds since 1970. */
    static DayStrip make(const QSet<qint64>& hourStarts, const QDateTime& now);

    bool operator==(const DayStrip& other) const;
};

/**
 * When the person is usually online, from the last week of hourly marks: the longest run of clock
 * hours seen on the most days. Needs marks on at least two different days, or it would only repeat
 * the strip; a person online almost all day has no best time. Returns false when there is none.
 */
bool bestHours(const QSet<qint64>& hourStarts, const QDateTime& now, int* from, int* to);

/** What the words beside the day strip say, in the order they are tried. */
enum class DayHint
{
    /** "usually 7:00 to 9:00". */
    Best,
    /** "past day: 7:00 to 9:00, 21:00 to 22:00". */
    Ranges,
    /** "no marks yet". */
    Empty,
};

DayHint dayHint(bool hasBest, const DayStrip& strip);

/** The start of the clock hour holding time, in seconds since 1970: how the day strip files it. */
qint64 hourStart(const QDateTime& time);

/** The line under the open tray's heading. */
enum class TrayDetail
{
    /** The person is online and the messages are leaving. */
    Live,
    /** Waiting, and the person's phone gets a wake-up signal without the text. */
    Push,
    /** Waiting until the person's app is online. */
    Plain,
};

TrayDetail trayDetail(bool personLive, bool hasPush);

/**
 * The messages of before that are no longer in now, in the order they waited: they leave the
 * tray one by one in that order. Cancelled ones are among them; the caller keeps only those that
 * went out.
 */
QVector<qint64> leftTray(const QVector<qint64>& before, const QSet<qint64>& now);

} // namespace Air
