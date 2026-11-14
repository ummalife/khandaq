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

QString shown(const GroupFileQueue::Packet& packet)
{
    const QString chunk = packet.chunk < 0 ? QStringLiteral("begin")
                                           : QString::number(packet.chunk);
    return QStringLiteral("%1:%2:%3")
        .arg(packet.group)
        .arg(QString::fromLatin1(packet.fileId.left(1)))
        .arg(chunk);
}

// Sends every packet in turn, as Core::process does, and says what went.
QStringList drain(GroupFileQueue& queue, int most = 1000)
{
    QStringList went;
    GroupFileQueue::Packet packet;
    while (most-- > 0 && queue.next(&packet)) {
        went.append(shown(packet));
        queue.sent(packet);
    }
    return went;
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
    void onlyTheFileItsGroupSendsNowMayBeginAtOnce();
    void aFileThatFailsLetsTheNextOneGo();
    void aPacketThatIsNotTheNextOneChangesNothing();
    void theBytesSentGrowWithTheChunks();
    void thePaceStartsWithABurstAndThenKeepsItsRate();
    void aLongPauseGivesNoMoreThanTheBurst();
    void aCostlyPacketIsPaidForByTheWaitAfterIt();
    void aPacketCostsItsBytesForEachMember();
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
    QVERIFY(!queue.next(&packet));

    queue.add(4, fileA, 3 * payload);
    QVERIFY(!queue.isEmpty());
    QVERIFY(queue.contains(fileA));
    QVERIFY(queue.next(&packet));
    QCOMPARE(shown(packet), QStringLiteral("4:a:begin"));
    QVERIFY(!queue.sent(packet));
    QVERIFY(queue.next(&packet));
    QCOMPARE(shown(packet), QStringLiteral("4:a:0"));
    QVERIFY(!queue.sent(packet));
    QVERIFY(queue.next(&packet));
    QCOMPARE(shown(packet), QStringLiteral("4:a:1"));
    QVERIFY(!queue.sent(packet));
    QVERIFY(queue.next(&packet));
    QCOMPARE(shown(packet), QStringLiteral("4:a:2"));
    // The last chunk ends the file.
    QVERIFY(queue.sent(packet));
    QVERIFY(queue.isEmpty());
    QVERIFY(!queue.contains(fileA));
    QVERIFY(!queue.next(&packet));
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
    GroupFileQueue queue;
    queue.add(1, fileA, 3 * payload);
    queue.add(2, fileB, payload);
    queue.add(1, fileC, payload);
    QCOMPARE(drain(queue), (QStringList{"1:a:begin", "2:b:begin", "1:a:0", "2:b:0", "1:a:1",
                                        "1:a:2", "1:c:begin", "1:c:0"}));
}

void TestAirGroupFileQueue::onlyTheFileItsGroupSendsNowMayBeginAtOnce()
{
    GroupFileQueue queue;
    queue.add(1, fileA, payload);
    queue.add(1, fileB, payload);
    queue.add(2, fileC, payload);
    QVERIFY(queue.isFirst(fileA));
    QVERIFY(!queue.isFirst(fileB));
    // Another group's file goes in its own turn.
    QVERIFY(queue.isFirst(fileC));
    QVERIFY(!queue.isFirst(QByteArray(32, 'z')));

    // The core sends the BEGIN of a group's first file at once, out of turn: it is the next packet
    // of its group all the same, and the group's turn comes again after the others.
    GroupFileQueue::Packet begin;
    begin.fileId = fileC;
    begin.group = 2;
    begin.chunk = -1;
    QVERIFY(!queue.sent(begin));
    GroupFileQueue::Packet packet;
    QVERIFY(queue.next(&packet));
    QCOMPARE(shown(packet), QStringLiteral("1:a:begin"));

    QCOMPARE(drain(queue, 3), (QStringList{"1:a:begin", "2:c:0", "1:a:0"}));
    QVERIFY(!queue.contains(fileA));
    QVERIFY(queue.isFirst(fileB));
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
    QVERIFY(queue.isFirst(fileB));
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
    QVERIFY(!queue.sent(ahead));
    GroupFileQueue::Packet waiting;
    waiting.fileId = fileB;
    waiting.group = 1;
    waiting.chunk = -1;
    QVERIFY(!queue.sent(waiting));
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

void TestAirGroupFileQueue::aPacketCostsItsBytesForEachMember()
{
    QCOMPARE(UploadPace::cost(chunkPacket, 1), chunkPacket);
    QCOMPARE(UploadPace::cost(chunkPacket, 5), 5 * chunkPacket);
    // Alone in the group the packet still takes its turn, so a member who comes meanwhile does
    // not get the rest of the file at once.
    QCOMPARE(UploadPace::cost(chunkPacket, 0), chunkPacket);
    QCOMPARE(UploadPace::cost(chunkPacket, -1), chunkPacket);
}

QTEST_GUILESS_MAIN(TestAirGroupFileQueue)
#include "airgroupfilequeue_test.moc"
