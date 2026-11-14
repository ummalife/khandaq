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
// a file goes only when its chunks follow. The groups take turns, a packet each.
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

    /** A file to send into the group, after the files the group waits for. */
    void add(int group, const QByteArray& fileId, qint64 size);
    /** The packet whose turn it is; false when nothing waits. */
    bool next(Packet* packet) const;
    /**
     * The next packet of its group went, in its turn (next()) or before it, and the group's turn
     * comes again after the other groups. True when it was the last of its file, which is then
     * done. Any other packet changes nothing.
     */
    bool sent(const Packet& packet);
    /** The file is not sent: it failed, or its group is gone. */
    void remove(const QByteArray& fileId);
    /** Whether the file is the one its group sends now, so that its BEGIN may go at once. */
    bool isFirst(const QByteArray& fileId) const;
    /** The bytes of the file whose chunks went. */
    qint64 bytesSent(const QByteArray& fileId) const;
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
    };

    int indexOf(const QByteArray& fileId) const;
    int firstOf(int group) const;
    void dropTurnIfDone(int group);

    /** In the order they were added. */
    QList<File> files;
    /** The groups with a file to send, the one whose turn it is first. */
    QList<int> turns;
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

private:
    qint64 credit = 0;
    qint64 lastMs = -1;
};

} // namespace Air
