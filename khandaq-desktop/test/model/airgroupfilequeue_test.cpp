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

#include "src/core/khandaqlimits.h"
#include "src/model/air/airgroupfilequeue.h"

#include <QStringList>
#include <QtTest/QtTest>

using namespace Air;

namespace {
constexpr qint64 payload = static_cast<qint64>(NGC_CHUNK_PAYLOAD_MAX);
constexpr qint64 chunkPacket = static_cast<qint64>(NGC_MAX_PACKET_SIZE);
const QByteArray fileA(32, 'a');
const QByteArray fileB(32, 'b');
const QByteArray fileC(32, 'c');
const QByteArray fileD(32, 'd');

QString shown(const GroupFileQueue::Packet& packet)
{
    const QString chunk = packet.chunk < 0 ? QStringLiteral("begin")
                                           : QString::number(packet.chunk);
    return QStringLiteral("%1:%2:%3")
        .arg(packet.group)
        .arg(QString::fromLatin1(packet.fileId.left(1)))
        .arg(chunk);
}

// Sends every packet in turn at nowMs, as Core::process does, and says what went.
QStringList drain(GroupFileQueue& queue, int most = 1000, qint64 nowMs = 0)
{
    QStringList went;
    GroupFileQueue::Packet packet;
    while (most-- > 0 && queue.next(&packet, nowMs)) {
        went.append(shown(packet));
        queue.sent(packet, nowMs);
    }
    return went;
}

// Sends the packet whose turn it is at nowMs, and says which it was.
QString step(GroupFileQueue& queue, qint64 nowMs)
{
    const QStringList went = drain(queue, 1, nowMs);
    return went.isEmpty() ? QString() : went.first();
}

// What one chunk to this many members costs the pace.
qint64 chunkTo(int members)
{
    return UploadPace::cost(chunkPacket, members);
}
} // namespace

// 0.2.56: the chunks of a file the desktop sends into a group go from the thread of the node, a few
// at each turn of its loop, instead of all at once from the window's thread.
class TestAirGroupFileQueue : public QObject
{
    Q_OBJECT
private slots:
    void aFileIsCutIntoChunksOfThePacketPayload();
    void aFileGoesAsItsBeginThenItsChunksInOrder();
    void theFilesOfAGroupGoOneAfterAnother();
    void theGroupsTakeTurns();
    void theGroupsShareThePaceByWhatTheyCost();
    void aGroupThatComesStartsLevelWithTheOthers();
    void aPacketCostsItsBytesForEachMemberOfItsGroup();
    void aGroupWithNobodyToSendToWaits();
    void aFileStartsAgainWhenAMemberIsBack();
    void theFilesOfAGroupWaitAtMostTheAloneLimit();
    void aFileThatWaitedLongGoesFirst();
    void aFileBeginsOnlyWhileTheFilesGoingLeaveRoom();
    void aFileThatWaitedForRoomBeginsLevelWithTheOthers();
    void aFileThatCostsMoreThanTheWindowGoesAlone();
    void theChunksOfAFileComeWithinTheMinuteOfAReceiver();
    void theFilesThatBeganLastStartAgainWhenMembersCome();
    void theChunksStayWithinTheMinuteAsMembersCome();
    void aFileThatFailsLetsTheNextOneGo();
    void aPacketThatIsNotTheNextOneChangesNothing();
    void theBytesSentGrowWithTheChunks();
    void theChunksSentAreThoseSinceTheLastBegin();
    void thePaceStartsWithABurstAndThenKeepsItsRate();
    void aLongPauseGivesNoMoreThanTheBurst();
    void aCostlyPacketIsPaidForByTheWaitAfterIt();
    void thePaceSaysWhenTheNextPacketMayGo();
    void aPacketCostsItsBytesForEachMember();
    void aPacketGivesEachMemberItsBytes();
    void thePaceOfTheRelaysKeepsItsOwnRate();
    void aPaceOfNothingStillLetsAPacketGo();
};

void TestAirGroupFileQueue::aFileIsCutIntoChunksOfThePacketPayload()
{
    QCOMPARE(payload, qint64{36952});
    QCOMPARE(GroupFileQueue::chunkCount(0), 0);
    QCOMPARE(GroupFileQueue::chunkCount(1), 1);
    QCOMPARE(GroupFileQueue::chunkCount(payload), 1);
    QCOMPARE(GroupFileQueue::chunkCount(payload + 1), 2);
    // The largest file there is: 200 MB, in 5676 chunks, as Android and iOS count them.
    QCOMPARE(GroupFileQueue::chunkCount(static_cast<qint64>(KHANDAQ_MAX_FILE_TRANSFER_BYTES)),
             5676);

    const qint64 size = 2 * payload + 10;
    QCOMPARE(GroupFileQueue::chunkOffset(0), qint64{0});
    QCOMPARE(GroupFileQueue::chunkOffset(2), 2 * payload);
    QCOMPARE(GroupFileQueue::chunkBytes(size, 0), payload);
    QCOMPARE(GroupFileQueue::chunkBytes(size, 1), payload);
    QCOMPARE(GroupFileQueue::chunkBytes(size, 2), qint64{10});
    QCOMPARE(GroupFileQueue::chunkBytes(size, 3), qint64{0});
    QCOMPARE(GroupFileQueue::chunkBytes(size, -1), qint64{0});
}

void TestAirGroupFileQueue::aFileGoesAsItsBeginThenItsChunksInOrder()
{
    GroupFileQueue queue;
    QVERIFY(queue.isEmpty());
    GroupFileQueue::Packet packet;
    QVERIFY(!queue.next(&packet, 0));

    queue.add(4, fileA, 3 * payload);
    QVERIFY(!queue.isEmpty());
    QVERIFY(queue.contains(fileA));
    QVERIFY(queue.next(&packet, 0));
    QCOMPARE(shown(packet), QStringLiteral("4:a:begin"));
    QVERIFY(!queue.sent(packet, 0));
    QVERIFY(queue.next(&packet, 0));
    QCOMPARE(shown(packet), QStringLiteral("4:a:0"));
    QVERIFY(!queue.sent(packet, 0));
    QVERIFY(queue.next(&packet, 0));
    QCOMPARE(shown(packet), QStringLiteral("4:a:1"));
    QVERIFY(!queue.sent(packet, 0));
    QVERIFY(queue.next(&packet, 0));
    QCOMPARE(shown(packet), QStringLiteral("4:a:2"));
    // The last chunk ends the file.
    QVERIFY(queue.sent(packet, 0));
    QVERIFY(queue.isEmpty());
    QVERIFY(!queue.contains(fileA));
    QVERIFY(!queue.next(&packet, 0));
}

void TestAirGroupFileQueue::theFilesOfAGroupGoOneAfterAnother()
{
    GroupFileQueue queue;
    queue.add(1, fileA, 2 * payload);
    queue.add(1, fileB, payload);
    // The BEGIN of the second file waits for the last chunk of the first: a receiver forgets a
    // BEGIN whose chunks do not come.
    QCOMPARE(drain(queue), (QStringList{"1:a:begin", "1:a:0", "1:a:1", "1:b:begin", "1:b:0"}));
    QVERIFY(queue.isEmpty());
}

void TestAirGroupFileQueue::theGroupsTakeTurns()
{
    // Groups whose packets cost the same take turns.
    GroupFileQueue queue;
    queue.add(1, fileA, 3 * payload);
    queue.add(2, fileB, payload);
    queue.add(1, fileC, payload);
    QCOMPARE(drain(queue), (QStringList{"1:a:begin", "2:b:begin", "1:a:0", "2:b:0", "1:a:1",
                                        "1:a:2", "1:c:begin", "1:c:0"}));
}

void TestAirGroupFileQueue::theGroupsShareThePaceByWhatTheyCost()
{
    // A group of two beside a group of fifty: each chunk to the large one costs 49 times as much.
    GroupFileQueue queue;
    queue.add(1, fileA, 1000 * payload);
    queue.add(2, fileB, 1000 * payload);
    queue.setMembers(1, 1, 0);
    queue.setMembers(2, 49, 0);
    qint64 cost[3] = {0, 0, 0};
    int chunks[3] = {0, 0, 0};
    GroupFileQueue::Packet packet;
    for (int i = 0; i < 500 && queue.next(&packet, 0); ++i) {
        cost[packet.group] += queue.cost(packet);
        chunks[packet.group] += packet.chunk >= 0 ? 1 : 0;
        queue.sent(packet, 0);
    }
    // Each took half of the pace, one chunk of the large group apart at most: the small group
    // sent 49 chunks for each of the other's, where turns of a packet each gave both the same.
    QVERIFY(chunks[2] >= 2);
    QVERIFY2(qAbs(cost[1] - cost[2]) <= 49 * chunkPacket,
             qPrintable(QStringLiteral("%1 %2").arg(cost[1]).arg(cost[2])));
    QVERIFY2(chunks[1] >= 40 * chunks[2],
             qPrintable(QStringLiteral("%1 %2").arg(chunks[1]).arg(chunks[2])));
}

void TestAirGroupFileQueue::aGroupThatComesStartsLevelWithTheOthers()
{
    GroupFileQueue queue;
    queue.add(1, fileA, 100 * payload);
    drain(queue, 21);
    // A group that comes later does not take all the turns it missed: it goes level with the
    // one that has cost the least, and they take turns from there.
    queue.add(2, fileB, 100 * payload);
    QCOMPARE(drain(queue, 5), (QStringList{"1:a:20", "2:b:begin", "1:a:21", "2:b:0", "1:a:22"}));
}

void TestAirGroupFileQueue::aPacketCostsItsBytesForEachMemberOfItsGroup()
{
    GroupFileQueue queue;
    queue.add(1, fileA, payload + 10);
    GroupFileQueue::Packet packet;
    QVERIFY(queue.next(&packet, 0));
    // One member until the core says how many there are.
    QCOMPARE(queue.cost(packet), static_cast<qint64>(NGC_FILE_BEGIN_SIZE));
    queue.setMembers(1, 3, 0);
    QCOMPARE(queue.cost(packet), 3 * static_cast<qint64>(NGC_FILE_BEGIN_SIZE));
    queue.sent(packet, 0);
    QVERIFY(queue.next(&packet, 0));
    QCOMPARE(queue.cost(packet), 3 * chunkPacket);
    queue.sent(packet, 0);
    QVERIFY(queue.next(&packet, 0));
    QCOMPARE(queue.cost(packet), 3 * (static_cast<qint64>(NGC_FILE_CHUNK_HEADER_SIZE) + 10));
    // A group that has no file to send is not kept.
    queue.setMembers(2, 7, 0);
    QCOMPARE(queue.groups(), QList<int>{1});
}

void TestAirGroupFileQueue::aGroupWithNobodyToSendToWaits()
{
    GroupFileQueue queue;
    queue.add(1, fileA, 3 * payload);
    queue.add(2, fileB, 2 * payload);
    queue.setMembers(1, 0, 1000);
    QVERIFY(queue.waits(1));
    QVERIFY(!queue.waits(2));
    // toxcore would take its packets for nobody: the other group goes on alone.
    QCOMPARE(drain(queue), (QStringList{"2:b:begin", "2:b:0", "2:b:1"}));
    GroupFileQueue::Packet packet;
    QVERIFY(!queue.next(&packet, 0));
    QVERIFY(queue.contains(fileA));
    QCOMPARE(queue.bytesSent(fileA), qint64{0});
    // A group of one waits from its first file on.
    queue.add(3, fileC, payload);
    queue.setMembers(3, 0, 1000);
    QVERIFY(!queue.next(&packet, 0));
}

void TestAirGroupFileQueue::aFileStartsAgainWhenAMemberIsBack()
{
    GroupFileQueue queue;
    queue.add(1, fileA, 3 * payload);
    queue.add(2, fileB, 100 * payload);
    queue.setMembers(1, 1, 0);
    queue.setMembers(2, 1, 0);
    QCOMPARE(drain(queue, 4), (QStringList{"1:a:begin", "2:b:begin", "1:a:0", "2:b:0"}));
    QCOMPARE(queue.bytesSent(fileA), payload);

    queue.setMembers(1, 0, 1000);
    QCOMPARE(drain(queue, 10).size(), 10);
    // Whoever left took the chunks it had not acknowledged, and whoever comes had none: the
    // file goes again from its BEGIN, level with the group that went on meanwhile.
    queue.setMembers(1, 1, 5000);
    QVERIFY(!queue.waits(1));
    QCOMPARE(queue.bytesSent(fileA), qint64{0});
    QCOMPARE(drain(queue, 4), (QStringList{"1:a:begin", "2:b:11", "1:a:0", "2:b:12"}));
    // Members that stay say nothing new: the file goes on.
    queue.setMembers(1, 3, 6000);
    QCOMPARE(drain(queue, 1), QStringList{"1:a:1"});
}

void TestAirGroupFileQueue::theFilesOfAGroupWaitAtMostTheAloneLimit()
{
    GroupFileQueue queue;
    queue.add(1, fileA, 2 * payload);
    queue.add(2, fileB, payload);
    queue.add(1, fileC, payload);
    const qint64 alone = 1000;
    queue.setMembers(1, 0, alone);
    queue.setMembers(2, 1, alone);
    // Still alone a turn later: it has waited since it first was.
    queue.setMembers(1, 0, alone + 500);
    QVERIFY(queue.waitedTooLong(alone + GroupFileQueue::aloneLimitMs - 1).isEmpty());
    QCOMPARE(queue.waitedTooLong(alone + GroupFileQueue::aloneLimitMs),
             (QList<QByteArray>{fileA, fileC}));

    // A member that comes back in time sets the clock back too.
    queue.setMembers(1, 1, alone + 1000);
    queue.setMembers(1, 0, alone + 2000);
    QVERIFY(queue.waitedTooLong(alone + GroupFileQueue::aloneLimitMs + 1).isEmpty());
    QCOMPARE(queue.waitedTooLong(alone + 2000 + GroupFileQueue::aloneLimitMs).size(), 2);
}

void TestAirGroupFileQueue::aFileThatWaitedLongGoesFirst()
{
    // A chunk to a group of a hundred costs the pace a hundred times one to a group of two: by
    // what they cost, the files of the large groups would wait for a hundred chunks of the small.
    GroupFileQueue queue;
    queue.add(1, fileA, 1000 * payload);
    queue.add(2, fileB, 10 * payload);
    queue.add(3, fileC, 10 * payload);
    queue.setMembers(2, 100, 0);
    queue.setMembers(3, 100, 0);
    QCOMPARE(drain(queue, 5),
             (QStringList{"1:a:begin", "2:b:begin", "3:c:begin", "1:a:0", "2:b:0"}));
    QCOMPARE(step(queue, 1000), QStringLiteral("3:c:0"));
    // Until urgentMs after their last packet, they wait their turn by what they cost.
    QCOMPARE(step(queue, GroupFileQueue::urgentMs - 1), QStringLiteral("1:a:1"));
    // Then they go first, the one that waited the longest before the other, and the small group
    // goes on.
    const qint64 late = 1000 + GroupFileQueue::urgentMs;
    QCOMPARE(step(queue, late), QStringLiteral("2:b:1"));
    QCOMPARE(step(queue, late), QStringLiteral("3:c:1"));
    QCOMPARE(step(queue, late), QStringLiteral("1:a:2"));
}

void TestAirGroupFileQueue::aFileBeginsOnlyWhileTheFilesGoingLeaveRoom()
{
    // The chunks of two groups of a hundred fill most of the window: those of a group of fifty
    // would overfill it, those of a group of ten would not.
    const qint64 room = UploadPace::bytesPerSecond * GroupFileQueue::windowMs / 1000;
    QVERIFY(2 * chunkTo(100) + chunkTo(10) <= room);
    QVERIFY(2 * chunkTo(100) + chunkTo(50) > room);

    GroupFileQueue queue;
    queue.add(1, fileA, payload);
    queue.add(2, fileB, 2 * payload);
    queue.add(3, fileC, payload);
    queue.add(4, fileD, payload);
    queue.setMembers(1, 100, 0);
    queue.setMembers(2, 100, 0);
    queue.setMembers(3, 50, 0);
    queue.setMembers(4, 10, 0);
    // The group of fifty begins once the first file is done, the group of ten beside them.
    QCOMPARE(drain(queue), (QStringList{"1:a:begin", "2:b:begin", "4:d:begin", "1:a:0",
                                        "3:c:begin", "4:d:0", "3:c:0", "2:b:0", "2:b:1"}));
}

void TestAirGroupFileQueue::aFileThatWaitedForRoomBeginsLevelWithTheOthers()
{
    GroupFileQueue queue;
    queue.add(1, fileA, 10 * payload);
    queue.add(2, fileB, 30 * payload);
    queue.add(3, fileC, 30 * payload);
    queue.setMembers(1, 100, 0);
    queue.setMembers(2, 100, 0);
    queue.setMembers(3, 50, 0);
    QStringList first{"1:a:begin", "2:b:begin"};
    for (int chunk = 0; chunk < 9; ++chunk) {
        first << QStringLiteral("1:a:%1").arg(chunk) << QStringLiteral("2:b:%1").arg(chunk);
    }
    first << QStringLiteral("1:a:9");
    QCOMPARE(drain(queue, first.size()), first);
    // It does not take from the other the turns it waited: they share the pace by what they
    // cost from there, two chunks to fifty for one to a hundred.
    QCOMPARE(drain(queue, 8), (QStringList{"3:c:begin", "2:b:9", "3:c:0", "3:c:1", "2:b:10",
                                           "3:c:2", "3:c:3", "2:b:11"}));
}

void TestAirGroupFileQueue::aFileThatCostsMoreThanTheWindowGoesAlone()
{
    const qint64 room = UploadPace::bytesPerSecond * GroupFileQueue::windowMs / 1000;
    QVERIFY(chunkTo(300) > room);

    GroupFileQueue queue;
    queue.add(1, fileA, 2 * payload);
    queue.add(5, fileB, 2 * payload);
    queue.add(2, fileC, payload);
    queue.setMembers(5, 300, 0);
    // It begins once nothing else goes,
    QCOMPARE(drain(queue, 6), (QStringList{"1:a:begin", "2:c:begin", "1:a:0", "2:c:0", "1:a:1",
                                           "5:b:begin"}));
    // and nothing else begins beside it.
    queue.add(3, fileD, payload);
    QCOMPARE(drain(queue), (QStringList{"5:b:0", "5:b:1", "3:d:begin", "3:d:0"}));
}

void TestAirGroupFileQueue::theChunksOfAFileComeWithinTheMinuteOfAReceiver()
{
    // Ten groups of a hundred send a file each, and eight groups of two a longer one, at the pace
    // and in the turns of the loop of the node (Core::sendGroupFileChunks).
    GroupFileQueue queue;
    UploadPace pace;
    for (int group = 0; group < 10; ++group) {
        queue.add(group, QByteArray(32, static_cast<char>('A' + group)), 20 * payload);
        queue.setMembers(group, 99, 0);
    }
    QList<QByteArray> small;
    for (int group = 10; group < 18; ++group) {
        small.append(QByteArray(32, static_cast<char>('a' + group - 10)));
        queue.add(group, small.last(), 100 * payload);
    }

    QHash<QByteArray, qint64> last;
    QHash<QByteArray, qint64> done;
    qint64 longest = 0;
    for (qint64 nowMs = 0; !queue.isEmpty() && nowMs < 60 * 60 * 1000; nowMs += 10) {
        GroupFileQueue::Packet packet;
        while (queue.next(&packet, nowMs) && pace.ready(nowMs)) {
            pace.spend(queue.cost(packet));
            if (packet.chunk >= 0) {
                longest = qMax(longest, nowMs - last.value(packet.fileId));
            }
            last.insert(packet.fileId, nowMs);
            if (queue.sent(packet, nowMs)) {
                done.insert(packet.fileId, nowMs);
            }
        }
    }
    QVERIFY(queue.isEmpty());
    // Each chunk went within 40 seconds of the packet before it, well within the minute after
    // which a receiver drops the file. By what they cost alone, a chunk to a hundred would wait
    // for as much to each of the nine other groups that go, seven seconds of the pace each.
    QVERIFY2(longest <= 40 * 1000, qPrintable(QString::number(longest)));
    // The groups of two kept their share of what the files that went first left: two fifths of
    // the pace end their files in some two and a half minutes, where a packet each in turn, as
    // when every file goes first, takes more than twenty.
    for (const QByteArray& fileId : small) {
        QVERIFY(done.contains(fileId));
        QVERIFY2(done.value(fileId) <= 5 * 60 * 1000,
                 qPrintable(QString::number(done.value(fileId))));
    }
}

/**
 * 0.2.56: members that come once files began make their chunks dearer than the window counted
 * them. While the files still fit restartMs they go on; past it the files that began last start
 * again from their BEGIN, and wait for room as a new file does. One file always goes.
 */
void TestAirGroupFileQueue::theFilesThatBeganLastStartAgainWhenMembersCome()
{
    const qint64 room = UploadPace::bytesPerSecond * GroupFileQueue::windowMs / 1000;
    const qint64 limit = UploadPace::bytesPerSecond * GroupFileQueue::restartMs / 1000;
    QVERIFY(6 * chunkTo(30) <= room);
    QVERIFY(5 * chunkTo(30) + chunkTo(150) <= limit);
    QVERIFY(2 * chunkTo(150) <= limit);
    QVERIFY(3 * chunkTo(150) > limit);

    GroupFileQueue queue;
    QList<QByteArray> ids;
    for (int group = 1; group <= 6; ++group) {
        ids.append(QByteArray(32, static_cast<char>('a' + group - 1)));
        queue.add(group, ids.last(), 10 * payload);
        queue.setMembers(group, 30, 0);
    }
    QCOMPARE(drain(queue, 7), (QStringList{"1:a:begin", "2:b:begin", "3:c:begin", "4:d:begin",
                                           "5:e:begin", "6:f:begin", "1:a:0"}));
    QVERIFY(queue.fitGoingFiles().isEmpty());

    // One group of a hundred and fifty among them: they still fit.
    queue.setMembers(1, 150, 0);
    QVERIFY(queue.fitGoingFiles().isEmpty());

    // All six: the four that began last start again, the last first.
    for (int group = 2; group <= 6; ++group) {
        queue.setMembers(group, 150, 0);
    }
    QCOMPARE(queue.fitGoingFiles(), (QList<QByteArray>{ids[5], ids[4], ids[3], ids[2]}));
    QVERIFY(queue.fitGoingFiles().isEmpty());
    QCOMPARE(queue.bytesSent(ids[0]), payload);
    QCOMPARE(queue.bytesSent(ids[5]), qint64{0});
    // The two go on alone; the others begin again from their BEGIN once there is room, at this
    // size one at a time.
    QStringList going;
    for (int chunk = 0; chunk < 10; ++chunk) {
        going << QStringLiteral("2:b:%1").arg(chunk);
        if (chunk < 9) {
            going << QStringLiteral("1:a:%1").arg(chunk + 1);
        }
    }
    going << QStringLiteral("3:c:begin") << QStringLiteral("3:c:0") << QStringLiteral("3:c:1");
    QCOMPARE(drain(queue, going.size()), going);

    // A single file goes, whatever it costs.
    GroupFileQueue alone;
    alone.add(1, fileA, 2 * payload);
    alone.setMembers(1, 2, 0);
    QCOMPARE(drain(alone, 1), QStringList{"1:a:begin"});
    alone.setMembers(1, 1000, 0);
    QVERIFY(alone.fitGoingFiles().isEmpty());
    QCOMPARE(step(alone, 0), QStringLiteral("1:a:0"));
}

/**
 * Six groups of thirty begin a file each, and a minute later each has a hundred and fifty
 * members online. At the pace, a chunk to each of them takes more than a minute: without the
 * restart a receiver of each would drop the file.
 */
void TestAirGroupFileQueue::theChunksStayWithinTheMinuteAsMembersCome()
{
    GroupFileQueue queue;
    UploadPace pace;
    for (int group = 0; group < 6; ++group) {
        queue.add(group, QByteArray(32, static_cast<char>('A' + group)), 20 * payload);
    }
    QHash<QByteArray, qint64> last;
    QSet<QByteArray> restarted;
    qint64 longest = 0;
    for (qint64 nowMs = 0; !queue.isEmpty() && nowMs < 60 * 60 * 1000; nowMs += 10) {
        // What the loop of the node does at each turn (Core::sendGroupFileChunks).
        for (int group = 0; group < 6; ++group) {
            queue.setMembers(group, nowMs < 60 * 1000 ? 30 : 150, nowMs);
        }
        for (const QByteArray& fileId : queue.fitGoingFiles()) {
            restarted.insert(fileId);
        }
        GroupFileQueue::Packet packet;
        while (queue.next(&packet, nowMs) && pace.ready(nowMs)) {
            pace.spend(queue.cost(packet));
            if (packet.chunk >= 0) {
                longest = qMax(longest, nowMs - last.value(packet.fileId));
            }
            last.insert(packet.fileId, nowMs);
            queue.sent(packet, nowMs);
        }
    }
    QVERIFY(queue.isEmpty());
    QCOMPARE(restarted.size(), 4);
    // A chunk follows the packet of its file before it within urgentMs and restartMs.
    QVERIFY2(longest <= GroupFileQueue::urgentMs + GroupFileQueue::restartMs,
             qPrintable(QString::number(longest)));
}

void TestAirGroupFileQueue::aFileThatFailsLetsTheNextOneGo()
{
    GroupFileQueue queue;
    queue.add(1, fileA, 2 * payload);
    queue.add(1, fileB, payload);
    queue.add(2, fileC, payload);
    drain(queue, 2);

    queue.remove(fileA);
    QVERIFY(!queue.contains(fileA));
    // A file that is not there changes nothing.
    queue.remove(fileA);
    QCOMPARE(drain(queue), (QStringList{"1:b:begin", "2:c:0", "1:b:0"}));

    // A group whose only file fails gives up its turn.
    queue.add(3, fileA, payload);
    queue.add(4, fileB, payload);
    queue.remove(fileA);
    QCOMPARE(drain(queue), (QStringList{"4:b:begin", "4:b:0"}));
}

void TestAirGroupFileQueue::aPacketThatIsNotTheNextOneChangesNothing()
{
    GroupFileQueue queue;
    queue.add(1, fileA, 2 * payload);
    queue.add(1, fileB, payload);

    GroupFileQueue::Packet ahead;
    ahead.fileId = fileA;
    ahead.group = 1;
    ahead.chunk = 1;
    QVERIFY(!queue.sent(ahead, 0));
    GroupFileQueue::Packet waiting;
    waiting.fileId = fileB;
    waiting.group = 1;
    waiting.chunk = -1;
    QVERIFY(!queue.sent(waiting, 0));
    QCOMPARE(drain(queue), (QStringList{"1:a:begin", "1:a:0", "1:a:1", "1:b:begin", "1:b:0"}));
}

void TestAirGroupFileQueue::theBytesSentGrowWithTheChunks()
{
    GroupFileQueue queue;
    const qint64 size = 2 * payload + 10;
    queue.add(1, fileA, size);
    QCOMPARE(queue.bytesSent(fileA), qint64{0});
    drain(queue, 1);
    QCOMPARE(queue.bytesSent(fileA), qint64{0});
    drain(queue, 1);
    QCOMPARE(queue.bytesSent(fileA), payload);
    drain(queue, 1);
    QCOMPARE(queue.bytesSent(fileA), 2 * payload);
    QCOMPARE(queue.bytesSent(fileB), qint64{0});
}

void TestAirGroupFileQueue::theChunksSentAreThoseSinceTheLastBegin()
{
    GroupFileQueue queue;
    queue.add(1, fileA, 3 * payload);
    QCOMPARE(queue.chunksSent(fileA), 0);
    drain(queue, 2);
    QCOMPARE(queue.chunksSent(fileA), 1);
    drain(queue, 1);
    QCOMPARE(queue.chunksSent(fileA), 2);
    // A member back after the group had nobody: the file goes again from its BEGIN, and a
    // request for its chunks waits for them.
    queue.setMembers(1, 0, 10);
    queue.setMembers(1, 1, 20);
    QCOMPARE(queue.chunksSent(fileA), 0);
    QCOMPARE(queue.chunksSent(fileB), 0);
    // A file that went whole is not the queue's any more.
    drain(queue);
    QCOMPARE(queue.chunksSent(fileA), 0);
}

void TestAirGroupFileQueue::thePaceStartsWithABurstAndThenKeepsItsRate()
{
    UploadPace pace;
    const qint64 start = 5000000;
    // A burst of a quarter of a second goes at once: some 4 chunks to one member.
    int burst = 0;
    while (pace.ready(start)) {
        pace.spend(chunkPacket);
        ++burst;
    }
    QCOMPARE(burst, static_cast<int>((UploadPace::burstBytes + chunkPacket - 1) / chunkPacket));
    QVERIFY(!pace.ready(start));

    // Then a second carries the rate: about 14 chunks to one member.
    int second = 0;
    for (qint64 at = start + 10; at <= start + 1000; at += 10) {
        while (pace.ready(at)) {
            pace.spend(chunkPacket);
            ++second;
        }
    }
    const int rate = static_cast<int>(UploadPace::bytesPerSecond / chunkPacket);
    QVERIFY2(second >= rate - 1 && second <= rate + 1, qPrintable(QString::number(second)));
    // A clock that goes back gives nothing.
    QVERIFY(!pace.ready(start));
}

void TestAirGroupFileQueue::aLongPauseGivesNoMoreThanTheBurst()
{
    UploadPace pace;
    QVERIFY(pace.ready(1000));
    pace.spend(UploadPace::burstBytes);
    QVERIFY(!pace.ready(1000));

    // A day later, the burst is all there is.
    const qint64 later = 1000 + 24LL * 60 * 60 * 1000;
    QVERIFY(pace.ready(later));
    pace.spend(UploadPace::burstBytes - 1);
    QVERIFY(pace.ready(later));
    pace.spend(1);
    QVERIFY(!pace.ready(later));
}

void TestAirGroupFileQueue::aCostlyPacketIsPaidForByTheWaitAfterIt()
{
    UploadPace pace;
    QVERIFY(pace.ready(0));
    // A chunk to a group of 100 costs far more than the burst: it goes, and the rate pays for it.
    const qint64 cost = UploadPace::cost(chunkPacket, 100);
    QVERIFY(cost > UploadPace::burstBytes);
    pace.spend(cost);
    const qint64 waitMs = (cost - UploadPace::burstBytes) * 1000 / UploadPace::bytesPerSecond;
    QVERIFY(!pace.ready(waitMs - 10));
    QVERIFY(pace.ready(waitMs + 10));
}

/**
 * 0.2.56: the loop of the node (Core::process) sleeps until the pace lets the next packet go,
 * instead of turning every groupFileTurnMs to find it may not.
 */
void TestAirGroupFileQueue::thePaceSaysWhenTheNextPacketMayGo()
{
    UploadPace pace;
    QCOMPARE(pace.msUntilReady(1000), qint64{0});
    QVERIFY(pace.ready(1000));
    QCOMPARE(pace.msUntilReady(1000), qint64{0});

    // No credit left at all: the next millisecond brings some.
    pace.spend(UploadPace::burstBytes);
    QCOMPARE(pace.msUntilReady(1000), qint64{1});

    // A chunk to a group of 100 on top: some seven seconds of the rate.
    pace.spend(UploadPace::cost(chunkPacket, 100));
    const qint64 waitMs = pace.msUntilReady(1000);
    const qint64 paidMs = UploadPace::cost(chunkPacket, 100) * 1000 / UploadPace::bytesPerSecond;
    QVERIFY2(waitMs >= paidMs && waitMs <= paidMs + 1, qPrintable(QString::number(waitMs)));
    // ready() says yes then, and not a millisecond sooner.
    UploadPace sooner = pace;
    QVERIFY(!sooner.ready(1000 + waitMs - 1));
    UploadPace then = pace;
    QVERIFY(then.ready(1000 + waitMs));
    // Less of the wait is left later on, and none once it is over.
    QCOMPARE(pace.msUntilReady(1000 + waitMs / 2), waitMs - waitMs / 2);
    QCOMPARE(pace.msUntilReady(1000 + waitMs + 50), qint64{0});
}

void TestAirGroupFileQueue::aPacketCostsItsBytesForEachMember()
{
    QCOMPARE(UploadPace::cost(chunkPacket, 1), chunkPacket);
    QCOMPARE(UploadPace::cost(chunkPacket, 5), 5 * chunkPacket);
    // Alone in the group the packet still takes its turn, so a member who comes meanwhile does
    // not get the rest of the file at once.
    QCOMPARE(UploadPace::cost(chunkPacket, 0), chunkPacket);
    QCOMPARE(UploadPace::cost(chunkPacket, -1), chunkPacket);
}

/**
 * 0.2.57: the pace of the relays counts what one member gets: the bytes of a packet, whatever
 * members it goes to.
 */
void TestAirGroupFileQueue::aPacketGivesEachMemberItsBytes()
{
    GroupFileQueue queue;
    queue.add(1, fileA, payload + 10);
    queue.setMembers(1, 3, 0);
    GroupFileQueue::Packet packet;
    QVERIFY(queue.next(&packet, 0));
    QCOMPARE(queue.bytes(packet), static_cast<qint64>(NGC_FILE_BEGIN_SIZE));
    QCOMPARE(queue.cost(packet), 3 * queue.bytes(packet));
    queue.sent(packet, 0);
    QVERIFY(queue.next(&packet, 0));
    QCOMPARE(queue.bytes(packet), chunkPacket);
    QCOMPARE(queue.cost(packet), 3 * chunkPacket);
    queue.sent(packet, 0);
    QVERIFY(queue.next(&packet, 0));
    QCOMPARE(queue.bytes(packet), static_cast<qint64>(NGC_FILE_CHUNK_HEADER_SIZE) + 10);
    // A file the queue does not hold is its header alone.
    GroupFileQueue::Packet other;
    other.fileId = fileB;
    other.group = 1;
    other.chunk = 0;
    QCOMPARE(queue.bytes(other), static_cast<qint64>(NGC_FILE_CHUNK_HEADER_SIZE));
}

/**
 * 0.2.57: a member over a relay gets the packets at relayedBytesPerSecond: after a quarter of a
 * second's burst, one chunk at a time, about two a second.
 */
void TestAirGroupFileQueue::thePaceOfTheRelaysKeepsItsOwnRate()
{
    QVERIFY(UploadPace::relayedBytesPerSecond < UploadPace::bytesPerSecond);
    UploadPace pace(UploadPace::relayedBytesPerSecond);
    const qint64 start = 5000000;
    const qint64 burst = UploadPace::relayedBytesPerSecond * UploadPace::burstMs / 1000;
    int first = 0;
    while (pace.ready(start)) {
        pace.spend(chunkPacket);
        ++first;
    }
    QCOMPARE(first, static_cast<int>((burst + chunkPacket - 1) / chunkPacket));

    // The next one waits until the rate has paid for what went beyond the burst.
    const qint64 waitMs = pace.msUntilReady(start);
    const qint64 owed = first * chunkPacket - burst;
    QVERIFY2(waitMs >= owed * 1000 / UploadPace::relayedBytesPerSecond
                 && waitMs <= owed * 1000 / UploadPace::relayedBytesPerSecond + 1,
             qPrintable(QString::number(waitMs)));

    // Ten seconds carry ten times the rate, give or take a chunk.
    int went = 0;
    for (qint64 at = start + 10; at <= start + 10000; at += 10) {
        while (pace.ready(at)) {
            pace.spend(chunkPacket);
            ++went;
        }
    }
    const qint64 sent = went * chunkPacket;
    const qint64 rate = 10 * UploadPace::relayedBytesPerSecond;
    QVERIFY2(sent >= rate - chunkPacket && sent <= rate + chunkPacket,
             qPrintable(QString::number(sent)));
    // The default pace is the one of the whole upload.
    UploadPace upload;
    QVERIFY(upload.ready(0));
    upload.spend(UploadPace::burstBytes);
    QVERIFY(!upload.ready(0));
}

/** 0.2.57: a pace said to be of no bytes is one of a byte a second, not a division by zero. */
void TestAirGroupFileQueue::aPaceOfNothingStillLetsAPacketGo()
{
    UploadPace pace(0);
    QVERIFY(pace.ready(0));
    pace.spend(1);
    QVERIFY(!pace.ready(0));
    QCOMPARE(pace.msUntilReady(0), qint64{1000});
    QVERIFY(pace.ready(1000));
}

QTEST_GUILESS_MAIN(TestAirGroupFileQueue)
#include "airgroupfilequeue_test.moc"
