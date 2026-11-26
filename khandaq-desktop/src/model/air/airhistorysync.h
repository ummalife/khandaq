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
#include <QHash>
#include <QList>
#include <QSet>
#include <QString>

#include <cstddef>
#include <cstdint>

namespace Air {

/**
 * KHANDAQ (0.2.63): a text a member relays from the history of a group (history sync), the private
 * NGC custom packet 0x02 of version 1, as Android (HelperGroup) and iOS (OCTNgcGroupHistSync) send
 * it: the magic, 01 02, the id toxcore gave the message (4 bytes, big-endian), the 32-byte key of
 * its author in the group, its time by the author's clock (seconds since 1970, big-endian), a
 * 25-byte name nobody trusts, then the words in UTF-8, 1 to 37000 bytes. The member who relays it
 * is not its author, and nothing proves the author: the key is what that member says (K-01).
 */
struct SyncedText
{
    quint32 messageId = 0;
    QByteArray author;
    quint32 time = 0;
    QString text;
};

/** Whether a private packet of a group is a text of its history, version 1, of any length. */
bool isSyncTextPacket(const uint8_t* data, size_t length);
/**
 * Reads a packet 0x02 of version 1. False, and nothing read, for another magic, version, id or
 * length, the message id 0, an author of zeros, or no words. The signed text of version 2 (02 02)
 * is not one. Whose key the author is, and the time, are for the caller (syncTimeAccepted).
 */
bool readSyncText(const QByteArray& packet, SyncedText* text);
/**
 * Whether a relayed record of this time may be taken at nowSecs: from 200 minutes before to 5
 * minutes after, as the phones take it, and the files of the history (0x03) here. The members
 * relay the rows of the last 130 minutes only.
 */
bool syncTimeAccepted(quint32 time, qint64 nowSecs);
/** How far back a relayed text is looked for among those kept: the window above, both sides. */
constexpr qint64 syncedTextKnownMs = (200 * 60 + 5 * 60) * 1000LL;
/**
 * The time a relayed text has in the conversation, in milliseconds since 1970: its own to the
 * second, and the last three digits of its id as the milliseconds, so that the parts of a long
 * text sent in one second keep entries of their own (textEntryKey).
 */
qint64 syncedTextTimeMs(const SyncedText& text);

/**
 * KHANDAQ (group notify parity, 2026-11-20): whether a text of another member that a group just
 * showed makes a sound and a notification too, besides counting as unread on the row of the
 * group, as each one does that does not show in front of the person
 * (Widget::newGroupMessageAlert). One relayed from the history of the group never does: it was
 * written while this computer slept or was offline, the testers took such notifications for new
 * messages, and its author is only what the member who relayed it says (K-01). A live one does
 * when it mentions us, or when the group tells of every message (Settings::getGroupAlwaysNotify).
 * A relayed file counts nothing at all (Widget::onGroupFileReceived).
 */
bool groupEntryNotifies(bool relayed, bool mentionsUs, bool alwaysNotify);

/** The request for the history of a group, packet 0x01 of version 1: 8 bytes, nothing after. */
QByteArray historyRequestPacket();

/**
 * The texts of groups seen in this session, live or relayed, by group, author, id and words: a
 * relayed copy of one that came is dropped, and so is a live one after its relayed copy. The
 * words are part of the key, as on the phones, so that a record a member forges with someone
 * else's id takes nothing away from the real one. The newest `limit` are kept.
 */
class SyncedTextLedger
{
public:
    static constexpr int limit = 8192;

    /** Whether that text was seen already; it is seen from now on either way. */
    bool seen(const QByteArray& groupChatId, const QByteArray& author, quint32 messageId,
              const QString& text);

private:
    QSet<QByteArray> keys;
    QList<QByteArray> order;
};

/**
 * How much the relayed texts of one group may bring in one run of the app, as the phones bound it
 * (NgcHistorySyncBudget, OCTNgcGroupHistSync): maxTexts texts and maxBytes bytes of words. A member
 * can make up any number of records within the window of time; past the budget the rest are
 * dropped until the next start. The live texts of the group count for nothing.
 */
class SyncedTextBudget
{
public:
    static constexpr int maxTexts = 5000;
    static constexpr qint64 maxBytes = 8 * 1024 * 1024;

    /** Whether a text of textBytes bytes of UTF-8 still fits; one that fits is counted. */
    bool take(const QByteArray& groupChatId, int textBytes);
    /** How many texts of the group did not fit in this run. */
    int refused(const QByteArray& groupChatId) const;

private:
    struct Spent
    {
        int texts = 0;
        qint64 bytes = 0;
        int refused = 0;
    };
    QHash<QByteArray, Spent> spent;
};

/**
 * When the history of a public group is asked of a member: a moment after they join, or after we
 * do (between minDelayMs and maxDelayMs, so that the members do not all ask at once), and once in
 * gapMs at most for each group and member, the pause the phones keep between two serves.
 */
class HistoryRequestPace
{
public:
    static constexpr int minDelayMs = 1000;
    static constexpr int maxDelayMs = 7000;
    static constexpr qint64 gapMs = 20000;

    /** Whether the member may be asked at nowMs, a steady clock. */
    bool mayAsk(const QByteArray& groupChatId, const QByteArray& peerKey, qint64 nowMs) const;
    /** The member was asked at nowMs. */
    void asked(const QByteArray& groupChatId, const QByteArray& peerKey, qint64 nowMs);

private:
    QHash<QByteArray, qint64> lastAsked;
};

} // namespace Air
