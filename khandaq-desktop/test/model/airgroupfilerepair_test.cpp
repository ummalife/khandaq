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

#include "src/model/air/airgroupfilerepair.h"

#include <QtEndian>
#include <QtTest/QtTest>

using namespace Air;

namespace {
const QByteArray fileA(32, 'a');
const QByteArray fileB(32, 'b');
const QByteArray memberA(32, 'm');
const QByteArray memberB(32, 'n');

// The first 44 bytes of every request: magic, version, 0x14, the msg id, then the count.
QByteArray head(quint32 count)
{
    QByteArray packet(FileRequest::headerBytes, '\0');
    const char magic[] = {'\x66', '\x77', '\x88', '\x11', '\x34', '\x35', '\x01', '\x14'};
    packet.replace(0, static_cast<int>(sizeof(magic)), magic, static_cast<int>(sizeof(magic)));
    packet.replace(8, fileA.size(), fileA);
    qToBigEndian(count, packet.data() + 40);
    return packet;
}

// A request as iOS lays it out (sendNackForAssembly): the count, then the indices.
QByteArray listRequest(const QVector<quint32>& indices)
{
    QByteArray packet = head(static_cast<quint32>(indices.size()));
    for (const quint32 index : indices) {
        char bytes[4];
        qToBigEndian(index, bytes);
        packet.append(bytes, 4);
    }
    return packet;
}

// A request as Android lays it out (buildFileRequestPacket): the file's chunk count, then a bit
// for each chunk, the lowest first, set for those it misses.
QByteArray bitmapRequest(int totalChunks, const QVector<int>& missing)
{
    QByteArray packet = head(static_cast<quint32>(totalChunks));
    QByteArray bitmap((totalChunks + 7) / 8, '\0');
    for (const int chunk : missing) {
        bitmap[chunk >> 3] = static_cast<char>(bitmap.at(chunk >> 3) | (1 << (chunk & 7)));
    }
    return packet + bitmap;
}

FileRequest::Wanted parsed(const QByteArray& packet, int totalChunks)
{
    return FileRequest::parse(reinterpret_cast<const uint8_t*>(packet.constData()),
                              static_cast<size_t>(packet.size()), totalChunks);
}

// The chunks a request asks for, or a word for what else it is.
QString asked(const QByteArray& packet, int totalChunks)
{
    const FileRequest::Wanted wanted = parsed(packet, totalChunks);
    switch (wanted.kind) {
    case FileRequest::Wanted::Kind::Invalid:
        return QStringLiteral("invalid");
    case FileRequest::Wanted::Kind::Whole:
        return QStringLiteral("whole");
    case FileRequest::Wanted::Kind::Chunks:
        break;
    }
    QStringList chunks;
    for (const int chunk : wanted.chunks) {
        chunks.append(QString::number(chunk));
    }
    return chunks.join(QLatin1Char(','));
}

// A file that comes: its chunks at these times, then looks at those, its sender there.
QVector<FileArrival::Step> looks(FileArrival& arrival, const QVector<qint64>& atMs,
                                 bool senderHere = true)
{
    QVector<FileArrival::Step> steps;
    for (const qint64 nowMs : atMs) {
        steps.append(arrival.look(nowMs, senderHere));
    }
    return steps;
}

using Step = FileArrival::Step;
} // namespace

// 0.2.57: the repair of a group file whose chunks did not all come (packet 0x14).
class TestAirGroupFileRepair : public QObject
{
    Q_OBJECT
private slots:
    void aListIsReadAsIosWritesIt();
    void aBitmapIsReadAsAndroidWritesIt();
    void theTwoFormsAreNeverTakenForEachOther();
    void androidWithNothingOfTheFileAsksForAllOfIt();
    void whatIsNotARequestForTheFileIsDropped();
    void theListNamesTheFirstChunksMissing();
    void aFileThatFlowsAsksForNothing();
    void aStalledFileAsksAFewSecondsApart();
    void aFileAsksTwentyFourTimesAtMost();
    void aSenderThatPacesFarApartIsAskedLater();
    void aStallTellsNothingOfThePace();
    void aFileSilentForAMinuteIsGivenUp();
    void whatComesAgainEndsTheSilenceButNotTheRequests();
    void aSenderAwayIsNeitherAskedNorSilent();
    void aSenderAwayTooLongGivesTheFileUp();
    void aMemberIsAnsweredTwentyFourTimesAtMost();
    void aMemberWaitsBetweenTwoAnswers();
    void eachMemberAndFileHasItsOwnBudget();
    void aMemberGetsThreeTimesTheFileAtMost();
    void aFileForgottenForgetsItsRequests();
    void theOldestEntriesGoFirst();
};

void TestAirGroupFileRepair::aListIsReadAsIosWritesIt()
{
    // In order and once each, whatever the order and repeats of the list; past the file, nothing.
    QCOMPARE(asked(listRequest({5, 2, 2, 85, 86, 4000000000u}), 86), QStringLiteral("2,5,85"));
    QCOMPARE(asked(listRequest({0}), 1), QStringLiteral("0"));
    QVector<quint32> all;
    for (quint32 i = 0; i < 256; ++i) {
        all.append(i);
    }
    QCOMPARE(parsed(listRequest(all), 5676).chunks.size(), 256);
    QCOMPARE(parsed(listRequest(all), 5676).chunks.last(), 255);
}

void TestAirGroupFileRepair::aBitmapIsReadAsAndroidWritesIt()
{
    // QA 16.11: 86 chunks, 55 bytes.
    const QByteArray request = bitmapRequest(86, {0, 7, 8, 40, 85});
    QCOMPARE(request.size(), 55);
    QCOMPARE(asked(request, 86), QStringLiteral("0,7,8,40,85"));
    // All of them, as Android asks with the BEGIN.
    QVector<int> every;
    for (int i = 0; i < 86; ++i) {
        every.append(i);
    }
    QCOMPARE(parsed(bitmapRequest(86, every), 86).chunks.size(), 86);
    // Bytes after the bitmap change nothing.
    QCOMPARE(asked(bitmapRequest(10, {9}) + QByteArray(3, '\xff'), 10), QStringLiteral("9"));
}

void TestAirGroupFileRepair::theTwoFormsAreNeverTakenForEachOther()
{
    for (int chunks = 1; chunks <= 300; ++chunks) {
        // A bitmap that misses the last chunk, and a list of that chunk alone, of a file this big.
        QCOMPARE(asked(bitmapRequest(chunks, {chunks - 1}), chunks), QString::number(chunks - 1));
        QCOMPARE(asked(listRequest({static_cast<quint32>(chunks - 1)}), chunks),
                 QString::number(chunks - 1));
        if (chunks <= FileRequest::maxListed) {
            QVector<quint32> every;
            for (int i = 0; i < chunks; ++i) {
                every.append(static_cast<quint32>(i));
            }
            QCOMPARE(parsed(listRequest(every), chunks).chunks.size(), chunks);
        }
    }
}

void TestAirGroupFileRepair::androidWithNothingOfTheFileAsksForAllOfIt()
{
    QCOMPARE(asked(head(0), 86), QStringLiteral("whole"));
}

void TestAirGroupFileRepair::whatIsNotARequestForTheFileIsDropped()
{
    const QString invalid = QStringLiteral("invalid");
    QCOMPARE(asked(head(0).left(43), 86), invalid);
    QCOMPARE(asked(QByteArray(), 86), invalid);
    // A file of no chunks has nothing to send again.
    QCOMPARE(asked(listRequest({0}), 0), invalid);
    // More than a list holds, a list with a byte more, and the count of another file's bitmap.
    QVector<quint32> tooMany(257, 1);
    QCOMPARE(asked(listRequest(tooMany), 5676), invalid);
    QCOMPARE(asked(listRequest({1, 2}) + QByteArray(1, '\0'), 86), invalid);
    QCOMPARE(asked(bitmapRequest(87, {3}), 86), invalid);
    // A bitmap cut short.
    QCOMPARE(asked(bitmapRequest(86, {3}).left(54), 86), invalid);
    // Requests that ask for no chunk of the file.
    QCOMPARE(asked(listRequest({86, 90}), 86), invalid);
    QCOMPARE(asked(bitmapRequest(86, {}), 86), invalid);
    QCOMPARE(asked(listRequest({}) + QByteArray(4, '\0'), 86), invalid);
    QCOMPARE(FileRequest::parse(nullptr, 55, 86).kind, FileRequest::Wanted::Kind::Invalid);
}

void TestAirGroupFileRepair::theListNamesTheFirstChunksMissing()
{
    QVector<bool> received(600, false);
    received[0] = true;
    received[2] = true;
    const QVector<int> missing = FileRequest::missing(received);
    QCOMPARE(missing.size(), FileRequest::maxListed);
    QCOMPARE(missing.at(0), 1);
    QCOMPARE(missing.at(1), 3);
    QCOMPARE(missing.last(), 257);
    QVERIFY(FileRequest::missing(QVector<bool>(5, true)).isEmpty());
    QCOMPARE(FileRequest::missing({true, false, true}), QVector<int>{1});
}

void TestAirGroupFileRepair::aFileThatFlowsAsksForNothing()
{
    FileArrival arrival(0);
    for (qint64 nowMs = 150; nowMs <= 20000; nowMs += 150) {
        arrival.heard(nowMs, true);
        QCOMPARE(arrival.look(nowMs + 100, true), Step::Wait);
    }
    QCOMPARE(arrival.requests(), 0);
    QCOMPARE(arrival.askAfterMs(), FileArrival::stallMs);
}

void TestAirGroupFileRepair::aStalledFileAsksAFewSecondsApart()
{
    FileArrival arrival(0);
    arrival.heard(100, true);
    arrival.heard(250, true);
    // Three seconds without a chunk; then every four at most.
    QCOMPARE(looks(arrival, {1000, 3249, 3250, 4000, 7249, 7250, 9000, 11250}),
             (QVector<Step>{Step::Wait, Step::Wait, Step::Ask, Step::Wait, Step::Wait, Step::Ask,
                            Step::Wait, Step::Ask}));
    QCOMPARE(arrival.requests(), 3);
    // A chunk ends the stall; the next one starts three seconds after it.
    arrival.heard(12000, true);
    QCOMPARE(looks(arrival, {14000, 15100, 15500}),
             (QVector<Step>{Step::Wait, Step::Wait, Step::Ask}));
}

void TestAirGroupFileRepair::aFileAsksTwentyFourTimesAtMost()
{
    FileArrival arrival(0);
    int asks = 0;
    for (qint64 nowMs = 0; nowMs < 200000; nowMs += 500) {
        // A chunk now and then, so that it is never given up.
        if (nowMs % 20000 == 0) {
            arrival.heard(nowMs, true);
        }
        asks += arrival.look(nowMs, true) == Step::Ask ? 1 : 0;
    }
    QCOMPARE(asks, FileArrival::maxRequests);
    QCOMPARE(arrival.requests(), FileArrival::maxRequests);
    QVERIFY(!arrival.gaveUp());
}

void TestAirGroupFileRepair::aSenderThatPacesFarApartIsAskedLater()
{
    // A desktop in a big group: a chunk every seven seconds.
    FileArrival arrival(0);
    arrival.heard(100, true);
    for (qint64 nowMs = 7100; nowMs <= 35100; nowMs += 7000) {
        arrival.heard(nowMs, true);
        QCOMPARE(arrival.look(nowMs + 6999, true), Step::Wait);
    }
    QCOMPARE(arrival.requests(), 0);
    QCOMPARE(arrival.askAfterMs(), qint64{14000});
    QCOMPARE(looks(arrival, {35100 + 13999, 35100 + 14000}),
             (QVector<Step>{Step::Wait, Step::Ask}));
    // However far apart, it asks within half a minute.
    FileArrival slow(0);
    slow.heard(25000, true);
    slow.heard(50000, true);
    QCOMPARE(slow.askAfterMs(), FileArrival::longestStallMs);
}

void TestAirGroupFileRepair::aStallTellsNothingOfThePace()
{
    FileArrival arrival(0);
    arrival.heard(100, true);
    arrival.heard(250, true);
    QCOMPARE(arrival.look(3500, true), Step::Ask);
    // What the request brought ends a gap of ten seconds, which was the stall, not the pace.
    arrival.heard(10000, true);
    QCOMPARE(arrival.askAfterMs(), FileArrival::stallMs);
    arrival.heard(10150, true);
    QCOMPARE(arrival.askAfterMs(), FileArrival::stallMs);
}

void TestAirGroupFileRepair::aFileSilentForAMinuteIsGivenUp()
{
    FileArrival arrival(1000);
    arrival.heard(2000, true);
    Step last = Step::Wait;
    qint64 nowMs = 2000;
    while (last != Step::GiveUp && nowMs < 200000) {
        nowMs += 500;
        last = arrival.look(nowMs, true);
    }
    QCOMPARE(nowMs, 2000 + FileArrival::silenceLimitMs);
    QVERIFY(arrival.gaveUp());
    // Given up it stays, whatever comes; and it asked meanwhile, as far as it could.
    arrival.heard(nowMs + 1, true);
    QCOMPARE(arrival.look(nowMs + 2, true), Step::GiveUp);
    QCOMPARE(arrival.requests(), 15);
}

void TestAirGroupFileRepair::whatComesAgainEndsTheSilenceButNotTheRequests()
{
    FileArrival arrival(0);
    arrival.heard(500, true);
    // A copy of a chunk it has, or its BEGIN again, at 50 s: the sender is there.
    int asks = 0;
    for (qint64 nowMs = 1000; nowMs <= 100000; nowMs += 1000) {
        if (nowMs == 50000) {
            arrival.heard(nowMs, false);
        }
        const Step step = arrival.look(nowMs, true);
        QVERIFY2(step != Step::GiveUp, qPrintable(QString::number(nowMs)));
        asks += step == Step::Ask ? 1 : 0;
    }
    QCOMPARE(asks, FileArrival::maxRequests);
    QCOMPARE(arrival.look(50000 + FileArrival::silenceLimitMs, true), Step::GiveUp);
}

void TestAirGroupFileRepair::aSenderAwayIsNeitherAskedNorSilent()
{
    FileArrival arrival(0);
    arrival.heard(500, true);
    for (qint64 nowMs = 1000; nowMs <= 90000; nowMs += 1000) {
        QCOMPARE(arrival.look(nowMs, false), Step::Wait);
    }
    QCOMPARE(arrival.requests(), 0);
    // Back after a minute and a half: asked at once, and silent only from now on.
    QCOMPARE(arrival.look(91000, true), Step::Ask);
    QCOMPARE(arrival.look(90000 + FileArrival::silenceLimitMs - 1, true), Step::Ask);
    QCOMPARE(arrival.look(90000 + FileArrival::silenceLimitMs, true), Step::GiveUp);
}

void TestAirGroupFileRepair::aSenderAwayTooLongGivesTheFileUp()
{
    FileArrival arrival(0);
    arrival.heard(500, true);
    QCOMPARE(arrival.look(500 + FileArrival::awayLimitMs - 1, false), Step::Wait);
    QCOMPARE(arrival.look(500 + FileArrival::awayLimitMs, false), Step::GiveUp);
    QCOMPARE(arrival.look(500 + FileArrival::awayLimitMs + 1, true), Step::GiveUp);
}

void TestAirGroupFileRepair::aMemberIsAnsweredTwentyFourTimesAtMost()
{
    ResendBudget budget;
    int answered = 0;
    for (int i = 0; i < 40; ++i) {
        answered += budget.take(fileA, memberA, 1, 5676, i * 3000) > 0 ? 1 : 0;
    }
    QCOMPARE(answered, ResendBudget::maxAnswers);
}

void TestAirGroupFileRepair::aMemberWaitsBetweenTwoAnswers()
{
    ResendBudget budget;
    QCOMPARE(budget.take(fileA, memberA, 10, 86, 1000), 10);
    QCOMPARE(budget.take(fileA, memberA, 10, 86, 1000 + ResendBudget::cooldownMs - 1), 0);
    QCOMPARE(budget.take(fileA, memberA, 10, 86, 1000 + ResendBudget::cooldownMs), 10);
}

void TestAirGroupFileRepair::eachMemberAndFileHasItsOwnBudget()
{
    ResendBudget budget;
    QCOMPARE(budget.take(fileA, memberA, 10, 86, 1000), 10);
    QCOMPARE(budget.take(fileA, memberB, 10, 86, 1001), 10);
    QCOMPARE(budget.take(fileB, memberA, 10, 86, 1002), 10);
    QCOMPARE(budget.kept(), 3);
}

void TestAirGroupFileRepair::aMemberGetsThreeTimesTheFileAtMost()
{
    ResendBudget budget;
    // A file of 86 chunks: 258 chunks in all, the last request in part.
    QCOMPARE(budget.take(fileA, memberA, 86, 86, 0), 86);
    QCOMPARE(budget.take(fileA, memberA, 86, 86, 3000), 86);
    QCOMPARE(budget.take(fileA, memberA, 80, 86, 6000), 80);
    QCOMPARE(budget.take(fileA, memberA, 86, 86, 9000), 6);
    QCOMPARE(budget.take(fileA, memberA, 1, 86, 12000), 0);
    QCOMPARE(budget.take(fileA, memberA, 0, 86, 15000), 0);
}

void TestAirGroupFileRepair::aFileForgottenForgetsItsRequests()
{
    ResendBudget budget;
    QCOMPARE(budget.take(fileA, memberA, 86, 86, 0), 86);
    QCOMPARE(budget.take(fileA, memberB, 86, 86, 0), 86);
    QCOMPARE(budget.take(fileB, memberA, 86, 86, 0), 86);
    budget.forget(fileA);
    QCOMPARE(budget.kept(), 1);
    QCOMPARE(budget.take(fileA, memberA, 86, 86, 1), 86);
}

void TestAirGroupFileRepair::theOldestEntriesGoFirst()
{
    ResendBudget budget;
    for (int i = 0; i < ResendBudget::maxKept; ++i) {
        const QByteArray member = QByteArray::number(i).rightJustified(32, '0');
        QCOMPARE(budget.take(fileA, member, 1, 86, i), 1);
    }
    QCOMPARE(budget.kept(), ResendBudget::maxKept);
    const QByteArray first = QByteArray::number(0).rightJustified(32, '0');
    const QByteArray last = QByteArray::number(ResendBudget::maxKept - 1).rightJustified(32, '0');
    // A new one takes the place of the oldest; the newest keeps its cooldown.
    QCOMPARE(budget.take(fileB, memberA, 1, 86, 600), 1);
    QCOMPARE(budget.kept(), ResendBudget::maxKept);
    QCOMPARE(budget.take(fileA, last, 1, 86, 600), 0);
    QCOMPARE(budget.take(fileA, first, 1, 86, 600), 1);
}

QTEST_GUILESS_MAIN(TestAirGroupFileRepair)
#include "airgroupfilerepair_test.moc"
