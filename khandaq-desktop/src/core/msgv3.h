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
#include <QtGlobal>

#include <cstddef>
#include <cstdint>

/**
 * KHANDAQ (0.2.57): the msgV3 trailer of a text between two people, byte for byte as the phones
 * write and read it (khandaq-android-trifa jni-c-toxcore.c, friend_message_cb and
 * tox_messagev3_friend_send_message; objcTox OCTTox.m, friendMessageCallback and
 * sendMessageWithFriendNumber:): the text, two zero bytes (the guard), the 32-byte id of the
 * message, then the time it was written, in seconds since 1970, 4 bytes big-endian. A message is
 * msgV3 when it is longer than the trailer and the two bytes where the guard goes are zero.
 *
 * The id names the message for good: every resend carries it, the receiver acknowledges it with a
 * TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK "_" that carries it too, and the edits, deletions, reactions and
 * read receipts (packet 189) of the phones point at it. The sizes are those of the fork's tox.h
 * (TOX_MSGV3_GUARD, TOX_MSGV3_MSGID_LENGTH, TOX_MSGV3_TIMESTAMP_LENGTH), named here so that this
 * builds against an upstream tox.h as well; core.cpp checks them against the fork's at compile time.
 */
namespace MsgV3 {

constexpr int guardLength = 2;
constexpr int idLength = 32;
constexpr int timeLength = 4;
constexpr int trailerLength = guardLength + idLength + timeLength;

/**
 * The parts of one long text are this far apart in their stamps, and so in the order a receiver
 * sorts them in (MessageSplitter.PART_GAP_MS on Android; iOS sorts by whole seconds).
 */
constexpr qint64 partGapMs = 1000;
/** How far ahead of the clock the texts to one person may run (DirectTextStamps.MAX_LEAD_SEC). */
constexpr qint64 maxLeadSeconds = 3600;

/**
 * The text of a message as toxcore hands it over, without its trailer. id gets the message id, or
 * is cleared when the message has no trailer; time gets the stamp, or 0. Either may be null.
 */
QByteArray textOf(const uint8_t* data, size_t length, QByteArray* id, quint32* time);

/** The bytes of text with the trailer of id and time; text alone when id is not 32 bytes. */
QByteArray withTrailer(const QByteArray& text, const QByteArray& id, quint32 time);

/** A new random message id, from the system's random source. */
QByteArray newId();

/**
 * The sent time, in milliseconds since 1970, of a text to a person written at nowMs after a text to
 * them sent at lastSentMs (0 for none): the clock once it is in a later second than the last stamp,
 * else the start of the second after it, so that texts sent within one second keep their order on
 * every receiver. A last stamp maxLeadSeconds or more ahead of the clock (a clock set back) is let
 * go. The rule of DirectTextStamps.sentTimestampAfter on Android and +textStampAfterStamp:now: on
 * iOS, without Android's push gap.
 */
qint64 sentMsAfter(qint64 lastSentMs, qint64 nowMs);

} // namespace MsgV3
