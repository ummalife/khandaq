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
#include <QVector>
#include <QtGlobal>

#include <cstddef>
#include <cstdint>

namespace Air {

// 0.2.57: a group file whose chunks did not all come is repaired by packet 0x14 (FILE_REQUEST):
// its receiver asks the sender for the chunks it misses, and the sender sends them again to that
// member alone. toxcore keeps the packets to a member in order, and a part of a chunk it loses
// holds back all that comes after it until a copy of that part arrives; a member whose oldest
// packet is not acknowledged for 58 s is dropped, with all it had yet to get (gcc_resend_packets
// in group_connection.c). What a member misses then, only a request brings again.
//
// A request is magic(6) version(1) 0x14 msg_id(32) count(4), then what it asks for in one of the
// two forms the clients send today (count and indices big-endian):
//   - iOS (OCTNgcGroupFileTransfer sendNackForAssembly) and this client: a count n of 1 to 256,
//     then n chunk indices of 4 bytes each, and the packet ends there: 44 + 4 n bytes;
//   - Android (NgcGroupFileTransfer buildFileRequestPacket): the count is the file's number of
//     chunks, then a bitmap of (count + 7) / 8 bytes, bit i (lowest bit first) set for a chunk it
//     misses. An Android that holds nothing of the file asks for all of it with the 44 bytes alone.
// The two never have the same length: a bitmap of n chunks is (n + 7) / 8 bytes, never 4 n.
namespace FileRequest {
/** The bytes before what a request asks for. */
constexpr int headerBytes = 44;
/** The most chunks the list form names (iOS kOCTNgcNackMaxIndicesPerPacket). */
constexpr int maxListed = 256;

/** What a request asks for. */
struct Wanted
{
    enum class Kind
    {
        /** Not a request this client reads: it is dropped. */
        Invalid,
        /** The whole file, its BEGIN first: Android's request of 44 bytes. */
        Whole,
        /** The chunks listed. */
        Chunks,
    };
    Kind kind = Kind::Invalid;
    /** For Chunks: the chunks it names of the file, in order, each once. */
    QVector<int> chunks;
};

/**
 * What the request of length bytes at data asks of a file of totalChunks chunks. Indices past
 * the file are left out, and a list that names none of its chunks asks for nothing (Invalid). A
 * bitmap whose count is not the file's belongs to another file, and is dropped too.
 */
Wanted parse(const uint8_t* data, size_t length, int totalChunks);

/**
 * The chunks a request in the list form names for a file that comes: the first maxListed of
 * those not received, in order. Empty when none is missing.
 */
QVector<int> missing(const QVector<bool>& received);
} // namespace FileRequest

/**
 * 0.2.57: the repair of one file that comes, timed as on Android (NgcFileNackBudget, HelperGroup
 * arm_ngc_incoming_file_timeout) and bounded as on iOS (kOCTNgcNackMaxRounds). A file asks for the
 * chunks it misses once none came for a while, at most maxRequests times and requestGapMs apart,
 * and is given up once it has been silent for silenceLimitMs in a row while its sender was there:
 * its entry then says it did not come. A sender that paces its chunks far apart (in a big group
 * the desktop waits up to 40 s between two) is not asked before twice its last gap, so a file that
 * goes on at its pace spends no requests. A sender that is away is not asked, and its absence is
 * not the file's silence; once it has been away for awayLimitMs since the file was last heard, the
 * file is given up all the same. Times are of a steady clock.
 */
class FileArrival
{
public:
    /** The most requests one file sends: iOS kOCTNgcNackMaxRounds, Android MAX_REQUESTS. */
    static constexpr int maxRequests = 24;
    /** The least time between two requests of one file (Android MIN_GAP_MS). */
    static constexpr qint64 requestGapMs = 4000;
    /** How long a file goes without a new chunk before it asks, at the least. */
    static constexpr qint64 stallMs = 3000;
    /** ...and at the most, whatever its sender's pace. */
    static constexpr qint64 longestStallMs = 30000;
    /** A file silent this long in a row while its sender is there is given up (both apps). */
    static constexpr qint64 silenceLimitMs = 60000;
    /** A file whose sender is away is given up once nothing came of it for this long. */
    static constexpr qint64 awayLimitMs = 2 * 60 * 1000;

    enum class Step
    {
        /** Nothing to do yet. */
        Wait,
        /** Ask the sender for the chunks it misses: the request is counted. */
        Ask,
        /** It did not come: it is given up from now on. */
        GiveUp,
    };

    /** Its BEGIN came at nowMs: it starts with all its requests. */
    explicit FileArrival(qint64 nowMs = 0);

    /**
     * Something of it came from its sender at nowMs: a chunk it did not have (newChunk), or one
     * it had, or its BEGIN again. Any of them ends its silence; only a new chunk is progress.
     */
    void heard(qint64 nowMs, bool newChunk);
    /** What to do at nowMs, its sender in the group or not. */
    Step look(qint64 nowMs, bool senderHere);

    int requests() const;
    bool gaveUp() const;
    /** How long the file goes without a new chunk before it asks (stallMs to longestStallMs). */
    qint64 askAfterMs() const;

private:
    /** When something of it last came. */
    qint64 heardMs = 0;
    /** When it last got a chunk it did not have, or its BEGIN. */
    qint64 progressMs = 0;
    /** Between its last two new chunks; 0 before the second one. */
    qint64 gapMs = 0;
    /** Whether a new chunk came since the BEGIN. */
    bool anyChunk = false;
    /** When a look last found its sender away; -1 when none did. */
    qint64 awayMs = -1;
    /** When it last asked; -1 when it did not. */
    qint64 askedMs = -1;
    int asked = 0;
    bool givenUp = false;
};

/**
 * 0.2.57: what goes again to a member that asked for chunks of one of our files, to it alone and
 * at the pace of the others (Core::sendGroupFileResend): the file's BEGIN, then the chunks in
 * order. A member has one at a time for a file (ResendBudget::answer); a default one is none.
 */
struct Resend
{
    /**
     * The member asked for all of the file: it holds nothing of it (FileRequest::Wanted::Whole).
     * A file goes whole to one member at a time.
     */
    bool whole = false;
    /** Its BEGIN is still to go: it goes first. */
    bool begin = false;
    /** The chunks to send, in order, and the next of them. */
    QVector<int> chunks;
    int next = 0;

    /** Whether something of it is still to go. */
    bool goes() const;
};

/**
 * 0.2.57: how often this client answers the requests of a member for one of its files. A request
 * costs a resend of the chunks it names, so one member could make us send a file over and over;
 * each member gets at most maxAnswers requests answered for a file, cooldownMs apart unless they
 * join what goes to it (answer; Android's SELECTIVE_RESEND_COOLDOWN_MS; iOS waits 2 s), and at
 * most chunkShare times the file's chunks in all. A member and a file are one entry; past maxKept
 * entries the oldest is forgotten: a member gets a new share only by keeping us busy with that
 * many other files and members first.
 */
class ResendBudget
{
public:
    /** The most requests of one member for one file that are answered: what a receiver sends. */
    static constexpr int maxAnswers = 24;
    static constexpr qint64 cooldownMs = 2500;
    static constexpr int chunkShare = 3;
    static constexpr int maxKept = 512;
    /**
     * The whole file goes again at most once in so long, to whichever member asks: each time it
     * costs the whole file (Android's RESEND_SERVICE_THROTTLE_MS for its requests of 44 bytes).
     */
    static constexpr qint64 wholeGapMs = 4000;

    /**
     * How many of the chunks chunks that member asks for of fileId, a file of totalChunks, are
     * sent again at nowMs (a steady clock): the first ones, as many as its share has left; 0 when
     * the request is not answered. An answered request counts against both.
     */
    int take(const QByteArray& fileId, const QByteArray& member, int chunks, int totalChunks,
             qint64 nowMs);
    /**
     * What wanted, a request of member for fileId, changes in resend, what goes to that member of
     * the file now: a file of totalChunks chunks whose first went went already, the ones it may
     * ask for again, as the others come in their turn. nullptr once it is answered (take), else
     * why it is not, and resend stays as it was.
     *  - The whole file (Android asks for it when it holds nothing of it) goes from its BEGIN, all
     *    of it that went, to one member at a time and wholeGapMs apart: wholeElsewhere, it goes
     *    whole to another member now. Asked for while it goes to that member whole, nothing more
     *    goes. It takes the place of chunks that go to that member: it holds nothing now.
     *  - Chunks join what goes to the member, those on their way left out, and wait for no
     *    cooldown: they add to it at the same pace, and the request is not lost (Android drops
     *    one while the file goes to the member). With nothing on its way they go from the BEGIN.
     */
    const char* answer(const QByteArray& fileId, const QByteArray& member,
                       const FileRequest::Wanted& wanted, int went, int totalChunks,
                       bool wholeElsewhere, Resend& resend, qint64 nowMs);
    /** The file is forgotten, and the requests for it with it. */
    void forget(const QByteArray& fileId);
    int kept() const;

private:
    /** As take; cooled: within cooldownMs of the last answer it gets nothing. why: why not. */
    int grant(const QByteArray& fileId, const QByteArray& member, int chunks, int totalChunks,
              qint64 nowMs, bool cooled, const char** why);

    struct Entry
    {
        QByteArray fileId;
        int answers = 0;
        qint64 lastMs = 0;
        qint64 chunks = 0;
        /** The order in which the entries were made: the lowest is the oldest. */
        qint64 order = 0;
    };
    QHash<QByteArray, Entry> entries;
    qint64 made = 0;
    /** When each file last began to go whole again, while that is within wholeGapMs. */
    QHash<QByteArray, qint64> wholeBeganMs;
};

} // namespace Air
