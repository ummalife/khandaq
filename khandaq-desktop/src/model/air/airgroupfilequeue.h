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
#include <QtGlobal>

#include <cstdint>

namespace Air {

// 0.2.56: a file of more than one packet goes into a group as its BEGIN and then its chunks
// (core.cpp, "NGC file transfer"). The desktop sent them all at once, from the window's thread and
// under the lock of the node: the window froze until the last chunk was handed to toxcore, and
// toxcore, given no tox_iterate to read the acknowledgements meanwhile, kept every packet for every
// member in a send array of 8192 entries (GCC_BUFFER_SIZE in group_common.h). A chunk takes 28 of
// them, so past about 290 chunks (some 11 MB) the rest were dropped, and the file never arrived.
// The thread of the node now sends them, a few at each turn of its loop (Core::process), in the
// order and at the pace below.

// The order of the files this device sends into groups. A file goes as its BEGIN, then its chunks
// in order. The files of one group go one after another, as they were chosen: a receiver drops a
// BEGIN after a minute without a chunk of it (NGC_ASSEMBLY_STALL_MS in core.cpp), so the BEGIN of
// a file goes only when its chunks follow. The groups share the pace by what they cost it: the
// next packet is the one of the group that has cost the least so far, its bytes counted once for
// every member they went to. Taking turns a packet each held a file to a group of two to the speed
// of one beside it to a group of fifty, whose every chunk costs the pace 49 times as much.
// Two rules keep the chunks of a file within a receiver's minute (urgentMs, windowMs): a file
// whose last packet went a while ago goes before the share, and a file begins only while the ones
// already going leave room for its chunks in the pace.
class GroupFileQueue
{
public:
    /** The BEGIN of a file, or one of its chunks. */
    struct Packet
    {
        QByteArray fileId;
        int group = -1;
        /** -1 for the BEGIN; from 0, the index of the chunk. */
        int chunk = -1;
    };

    /** How many chunks a file of this size takes. */
    static int chunkCount(qint64 size);
    /** Where the chunk with this index starts in the file. */
    static qint64 chunkOffset(int chunk);
    /** How many bytes of the file the chunk with this index carries. */
    static qint64 chunkBytes(qint64 size, int chunk);

    /**
     * A file to send into the group, after the files the group waits for. A group that had none
     * starts level with the one that has cost the least, not ahead of all the others.
     */
    void add(int group, const QByteArray& fileId, qint64 size);
    /**
     * How long a group may have nobody to send to before its files are said not sent. A member
     * that drops off stays a peer for toxcore until GC_CONFIRMED_PEER_TIMEOUT (58 s) passes
     * without a word from it, so a group looks empty about a minute after the last member went.
     * Two minutes more let a member whose link went for a while (a laptop that slept, a phone
     * between networks) come back for the file. Past them the conversation says the file was not
     * sent, rather than show it going to nobody while the group's next files wait behind it.
     */
    static constexpr qint64 aloneLimitMs = 2 * 60 * 1000;
    /**
     * A receiver drops a file whose next packet does not come within a minute of the one before
     * (NGC_ASSEMBLY_STALL_MS in core.cpp; iOS has the same minute), and the sender cannot tell.
     * A file whose last packet went urgentMs ago goes first, the one that waited the longest
     * before the others, and a file begins only while the chunks of the files that go, its own
     * with them, cost the pace at most windowMs together. A file then waits at most urgentMs, and
     * then for the files that went first: 40 seconds in all. The window is the shorter of the two,
     * so the files that go first take at most three fifths of the pace: the others share the rest
     * by what they cost, rather than a packet each in turn. With members enough that one chunk
     * costs more than windowMs (above some 210 of them) a file goes alone, a chunk as soon as the
     * pace lets it; past some 850 even that is more than a minute, which only a faster pace fixes.
     */
    static constexpr qint64 urgentMs = 25 * 1000;
    static constexpr qint64 windowMs = 15 * 1000;
    /**
     * Members that come once files began make their chunks dearer than the window counted them,
     * and six files that began at thirty members each go round in more than a minute at a hundred
     * and fifty. While the chunks of the files that go cost the pace at most restartMs together,
     * a file still waits at most urgentMs and the chunks of the others, 55 seconds, and they go
     * on: members come and go, and a file that starts again sends again all that it sent. Past
     * it, the files that began last start again from their BEGIN (fitGoingFiles), which waits
     * for room as a new file does. One file always goes.
     */
    static constexpr qint64 restartMs = 2 * windowMs;

    /**
     * How many members a packet into the group goes to now (one until it is said), at nowMs (a
     * steady clock). With none, the group's files wait: toxcore takes a packet for nobody and
     * says it went. When a member is there again, the file the group sends starts again from its
     * BEGIN: what went before was lost with the member that left, and a member that comes had
     * none of it. A receiver that stayed keeps what it has, as its sender's BEGIN never wipes it.
     */
    void setMembers(int group, int members, qint64 nowMs);
    /**
     * After the members were said (setMembers): the files that began last start again from their
     * BEGIN while the chunks of the files that go cost the pace more than restartMs. Returns them.
     */
    QList<QByteArray> fitGoingFiles();
    /** Whether the group has nobody to send to (setMembers). */
    bool waits(int group) const;
    /** The files of the groups that have had nobody to send to for aloneLimitMs. */
    QList<QByteArray> waitedTooLong(qint64 nowMs) const;
    /** The groups with a file to send. */
    QList<int> groups() const;
    /**
     * The packet whose turn it is at nowMs (a steady clock); false when nothing waits, or only
     * groups that wait.
     */
    bool next(Packet* packet, qint64 nowMs) const;
    /** What the packet costs the pace: its bytes once for each member of its group (UploadPace). */
    qint64 cost(const Packet& packet) const;
    /**
     * The next packet of its group went at nowMs, and its cost counts for the group; a BEGIN
     * first brings the group level with the files that go. True when it was the last of its file,
     * which is then done. Any other packet changes nothing.
     */
    bool sent(const Packet& packet, qint64 nowMs);
    /** The file is not sent: it failed, or its group is gone. */
    void remove(const QByteArray& fileId);
    /** The bytes of the file whose chunks went. */
    qint64 bytesSent(const QByteArray& fileId) const;
    /**
     * 0.2.57: how many chunks of the file went, in order, since its BEGIN last went: the ones a
     * member may ask for again (Air::FileRequest). 0 before its first chunk, and for a file the
     * queue does not hold.
     */
    int chunksSent(const QByteArray& fileId) const;
    bool contains(const QByteArray& fileId) const;
    bool isEmpty() const;

private:
    struct File
    {
        QByteArray fileId;
        int group = -1;
        qint64 size = 0;
        int chunks = 0;
        /** The packet to send: -1, the BEGIN, then the chunks. */
        int next = -1;
        /** When its last packet went. */
        qint64 lastMs = 0;
        /** The order in which its BEGIN went among those of all files: the last began last. */
        qint64 begun = 0;
    };

    int indexOf(const QByteArray& fileId) const;
    int firstOf(int group) const;
    void dropTurnIfDone(int group);
    qint64 leastCost(int except) const;
    qint64 leastGoingCost(int except) const;
    /** Whether the file the group sends has begun and the group does not wait. */
    bool goes(int group) const;
    /** What a whole chunk to the group costs the pace now. */
    qint64 chunkCost(int group) const;

    /** In the order they were added. */
    QList<File> files;
    /** The groups with a file to send; of two that cost the same, the first goes first. */
    QList<int> turns;
    /** What the packets of each of those groups have cost the pace. */
    QHash<int, qint64> costs;
    /** How many members each of those groups sends to (setMembers). */
    QHash<int, int> members;
    /** Since when each of those groups that waits has had nobody to send to. */
    QHash<int, qint64> aloneSince;
    /** How many BEGINs went (File::begun). */
    qint64 begins = 0;
};

// The pace of those packets. toxcore tells nothing of how full a member's send array is: a packet
// to the whole group is taken even when some member's array has no room for it. And it keeps each
// part of a packet until the member acknowledges a copy it sent again, sends again all the parts of
// the same second at once, and the member drops every part that comes after a lost one until that
// one arrives again (handle_gc_packet_fragment in group_chats.c). So a fast stream loses parts in
// those bursts, and fills the array before it is whole again. Two nodes on one Mac got a file of
// 14 MB whole at this rate; at 1 and 2 MiB/s the array filled, and the rest of the file was lost.
// The rate counts what this device uploads: once for every member a packet goes to.
class UploadPace
{
public:
    /** About 4 Mbit/s, shared by the members a packet goes to. */
    static constexpr qint64 bytesPerSecond = 512 * 1024;
    /** After a pause, at most this much of the rate goes at once. */
    static constexpr qint64 burstMs = 250;
    static constexpr qint64 burstBytes = bytesPerSecond * burstMs / 1000;

    /** What a packet of this size costs when it goes to this many members: at least one. */
    static qint64 cost(qint64 packetBytes, int members);

    /**
     * Whether a packet may go at nowMs (a steady clock). One may go whenever the credit is
     * positive, however much it costs: a costly one is paid for by the wait after it.
     */
    bool ready(qint64 nowMs);
    /** A packet went that cost this much (cost()). */
    void spend(qint64 bytes);
    /** How long after nowMs ready() first says a packet may go: 0 when it may at nowMs. */
    qint64 msUntilReady(qint64 nowMs) const;

private:
    qint64 credit = 0;
    qint64 lastMs = -1;
};

} // namespace Air
