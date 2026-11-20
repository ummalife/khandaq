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

#include "src/model/air/airlocalstore.h"
#include "src/model/air/airseenreceipts.h"

#include <QDateTime>
#include <QtTest/QtTest>


using namespace Air;

namespace {
/** The store AirLocal keeps, without its file. */
class LocalReadMarks : public ReadMarkStore
{
public:
    SeenAnchor readAnchor(const ToxPk& friendPk) const override
    {
        return local.readAnchor(friendPk.toString());
    }
    bool isReadPending(const ToxPk& friendPk) const override
    {
        return local.isReadPending(friendPk.toString());
    }
    bool noteRead(const ToxPk& friendPk, const SeenAnchor& anchor) override
    {
        return local.noteRead(friendPk.toString(), anchor);
    }
    void noteReadSent(const ToxPk& friendPk, const SeenAnchor& anchor) override
    {
        local.noteReadSent(friendPk.toString(), anchor);
    }

    LocalStore local;
};

struct SentPacket
{
    ToxPk to;
    QByteArray packet;
};

/** A person's key, and the network between us and them. */
class Wire
{
public:
    bool online = true;
    bool accepts = true;
    QVector<SentPacket> sent;

    SeenReceipts::Send sender()
    {
        return [this](const ToxPk& to, const QByteArray& packet) {
            if (!accepts) {
                return false;
            }
            sent.append(SentPacket{to, packet});
            return true;
        };
    }
    SeenReceipts::IsOnline presence()
    {
        return [this](const ToxPk&) { return online; };
    }
};

ToxPk person(char fill)
{
    return ToxPk(QByteArray(32, fill));
}

SeenAnchor textAnchor(char fill, qint64 timeMs)
{
    SeenAnchor anchor;
    anchor.anchor = MessageEffect::Anchor::Text;
    anchor.id = QByteArray(32, fill);
    anchor.timeMs = timeMs;
    return anchor;
}

ReadingFacts allFacts()
{
    ReadingFacts facts;
    facts.appActive = true;
    facts.windowShown = true;
    facts.windowMinimized = false;
    facts.windowActive = true;
    facts.windowExposed = true;
    facts.chatShown = true;
    return facts;
}
} // namespace

class TestAirSeenReceipts : public QObject
{
    Q_OBJECT
private slots:
    void readingNeedsEveryFact();
    void receiptGoesAtOnceByteForByte();
    void fileAnchorIsTypeTwo();
    void nothingNewSendsNothing();
    void emptyAnchorOwesNothing();
    void anchorOfAnotherSizeOwesNothing();
    void offlinePersonGetsItOnline();
    void refusedReceiptStaysPending();
    void oneReceiptPerSecondTheLastWins();
    void gapIsPerPerson();
    void pendingReceiptSurvivesRestart();
    void removedContactForgetsReceipts();
    void turnedOffSendsAndOwesNothing();
    void receiptOwedBeforeWaitsWhileTurnedOff();
    void receiptWaitingForTheGapStopsWhenTurnedOff();
};

void TestAirSeenReceipts::readingNeedsEveryFact()
{
    QVERIFY(isReadingNow(allFacts()));
    QVERIFY(!isReadingNow(ReadingFacts()));

    ReadingFacts facts = allFacts();
    facts.appActive = false;
    QVERIFY(!isReadingNow(facts));
    facts = allFacts();
    facts.windowShown = false;
    QVERIFY(!isReadingNow(facts));
    facts = allFacts();
    facts.windowMinimized = true;
    QVERIFY(!isReadingNow(facts));
    facts = allFacts();
    facts.windowActive = false;
    QVERIFY(!isReadingNow(facts));
    facts = allFacts();
    facts.windowExposed = false;
    QVERIFY(!isReadingNow(facts));
    facts = allFacts();
    facts.chatShown = false;
    QVERIFY(!isReadingNow(facts));
}

void TestAirSeenReceipts::receiptGoesAtOnceByteForByte()
{
    LocalReadMarks store;
    Wire wire;
    SeenReceipts receipts(store, wire.sender(), wire.presence());

    const quint32 before = static_cast<quint32>(QDateTime::currentSecsSinceEpoch());
    receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
    const quint32 after = static_cast<quint32>(QDateTime::currentSecsSinceEpoch());

    QCOMPARE(wire.sent.size(), 1);
    QCOMPARE(wire.sent[0].to, person('a'));
    const QByteArray packet = wire.sent[0].packet;
    QCOMPARE(packet.size(), 41);
    QCOMPARE(packet.left(5), QByteArray::fromHex("BD4B510101"));
    QCOMPARE(packet.mid(5, 32), QByteArray(32, '\x11'));
    // The time we saw it, by our clock, big-endian.
    const auto byteAt = [&packet](int i) {
        return static_cast<quint32>(static_cast<uint8_t>(packet[i]));
    };
    const quint32 time = (byteAt(37) << 24) | (byteAt(38) << 16) | (byteAt(39) << 8) | byteAt(40);
    QVERIFY(time >= before && time <= after);

    SeenReceipt read;
    QVERIFY(readSeenPacket(packet, &read));
    QVERIFY(!store.isReadPending(person('a')));
    QCOMPARE(store.readAnchor(person('a')).id, QByteArray(32, '\x11'));
}

void TestAirSeenReceipts::fileAnchorIsTypeTwo()
{
    LocalReadMarks store;
    Wire wire;
    SeenReceipts receipts(store, wire.sender(), wire.presence());

    SeenAnchor file = textAnchor('\x22', 1700000000000);
    file.anchor = MessageEffect::Anchor::File;
    receipts.seen(person('a'), file);

    QCOMPARE(wire.sent.size(), 1);
    QCOMPARE(static_cast<int>(wire.sent[0].packet.at(4)), 2);
    QCOMPARE(wire.sent[0].packet.mid(5, 32), QByteArray(32, '\x22'));
}

void TestAirSeenReceipts::nothingNewSendsNothing()
{
    LocalReadMarks store;
    Wire wire;
    SeenReceipts receipts(store, wire.sender(), wire.presence(), 0);

    receipts.seen(person('a'), textAnchor('\x11', 1700000005000));
    // The same message again, as every time the window comes back to the front.
    receipts.seen(person('a'), textAnchor('\x11', 1700000005000));
    // An older message of theirs is already covered by the receipt that went.
    receipts.seen(person('a'), textAnchor('\x10', 1700000001000));
    QCOMPARE(wire.sent.size(), 1);

    receipts.seen(person('a'), textAnchor('\x12', 1700000009000));
    QCOMPARE(wire.sent.size(), 2);
    QCOMPARE(wire.sent[1].packet.mid(5, 32), QByteArray(32, '\x12'));
}

void TestAirSeenReceipts::emptyAnchorOwesNothing()
{
    LocalReadMarks store;
    Wire wire;
    SeenReceipts receipts(store, wire.sender(), wire.presence());

    // A chat with no message of theirs a receipt can name (none yet, or all from 0.2.56).
    receipts.seen(person('a'), SeenAnchor());
    QVERIFY(wire.sent.isEmpty());
    QVERIFY(!store.isReadPending(person('a')));
    QVERIFY(store.readAnchor(person('a')).isEmpty());
}

void TestAirSeenReceipts::anchorOfAnotherSizeOwesNothing()
{
    LocalReadMarks store;
    Wire wire;
    SeenReceipts receipts(store, wire.sender(), wire.presence());

    SeenAnchor odd = textAnchor('\x11', 1700000000000);
    odd.id = QByteArray(16, '\x11');
    receipts.seen(person('a'), odd);
    QVERIFY(wire.sent.isEmpty());
    QVERIFY(!store.isReadPending(person('a')));
}

void TestAirSeenReceipts::offlinePersonGetsItOnline()
{
    LocalReadMarks store;
    Wire wire;
    wire.online = false;
    SeenReceipts receipts(store, wire.sender(), wire.presence());

    receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
    receipts.seen(person('a'), textAnchor('\x12', 1700000001000));
    QVERIFY(wire.sent.isEmpty());
    QVERIFY(store.isReadPending(person('a')));

    // Online again, but not them: still nothing.
    receipts.friendOnline(person('b'));
    QVERIFY(wire.sent.isEmpty());

    wire.online = true;
    receipts.friendOnline(person('a'));
    // Only the newest one owed goes.
    QCOMPARE(wire.sent.size(), 1);
    QCOMPARE(wire.sent[0].packet.mid(5, 32), QByteArray(32, '\x12'));
    QVERIFY(!store.isReadPending(person('a')));

    receipts.friendOnline(person('a'));
    QCOMPARE(wire.sent.size(), 1);
}

void TestAirSeenReceipts::refusedReceiptStaysPending()
{
    LocalReadMarks store;
    Wire wire;
    wire.accepts = false;
    SeenReceipts receipts(store, wire.sender(), wire.presence());

    receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
    QVERIFY(wire.sent.isEmpty());
    QVERIFY(store.isReadPending(person('a')));

    wire.accepts = true;
    receipts.friendOnline(person('a'));
    QCOMPARE(wire.sent.size(), 1);
    QVERIFY(!store.isReadPending(person('a')));
}

void TestAirSeenReceipts::oneReceiptPerSecondTheLastWins()
{
    LocalReadMarks store;
    Wire wire;
    const int gapMs = 200;
    SeenReceipts receipts(store, wire.sender(), wire.presence(), gapMs);

    QElapsedTimer clock;
    clock.start();
    receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
    receipts.seen(person('a'), textAnchor('\x12', 1700000001000));
    receipts.seen(person('a'), textAnchor('\x13', 1700000002000));
    // The first at once; the others wait for the gap, the newest in place of the one before.
    QCOMPARE(wire.sent.size(), 1);
    QVERIFY(store.isReadPending(person('a')));

    QTRY_COMPARE_WITH_TIMEOUT(wire.sent.size(), 2, 5000);
    QVERIFY(clock.elapsed() >= gapMs - 20);
    QCOMPARE(wire.sent[1].packet.mid(5, 32), QByteArray(32, '\x13'));
    QVERIFY(!store.isReadPending(person('a')));

    // Nothing more is owed, so nothing more goes.
    QTest::qWait(2 * gapMs);
    QCOMPARE(wire.sent.size(), 2);
}

void TestAirSeenReceipts::gapIsPerPerson()
{
    LocalReadMarks store;
    Wire wire;
    SeenReceipts receipts(store, wire.sender(), wire.presence(), 60000);

    receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
    receipts.seen(person('b'), textAnchor('\x21', 1700000000000));
    QCOMPARE(wire.sent.size(), 2);
    QCOMPARE(wire.sent[0].to, person('a'));
    QCOMPARE(wire.sent[1].to, person('b'));
}

void TestAirSeenReceipts::pendingReceiptSurvivesRestart()
{
    const QDateTime now = QDateTime::currentDateTime();
    QByteArray saved;
    {
        LocalReadMarks store;
        Wire wire;
        wire.online = false;
        SeenReceipts receipts(store, wire.sender(), wire.presence());
        receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
        QVERIFY(store.isReadPending(person('a')));
        saved = store.local.save();
    }

    LocalReadMarks store;
    store.local.load(saved, now);
    QVERIFY(store.isReadPending(person('a')));
    QCOMPARE(store.readAnchor(person('a')).id, QByteArray(32, '\x11'));
    QCOMPARE(store.readAnchor(person('a')).timeMs, qint64{1700000000000});

    Wire wire;
    SeenReceipts receipts(store, wire.sender(), wire.presence());
    receipts.friendOnline(person('a'));
    QCOMPARE(wire.sent.size(), 1);
    QCOMPARE(wire.sent[0].packet.mid(5, 32), QByteArray(32, '\x11'));

    // Sent: after the next launch nothing is owed.
    LocalReadMarks again;
    again.local.load(store.local.save(), now);
    QVERIFY(!again.isReadPending(person('a')));
    QCOMPARE(again.readAnchor(person('a')).id, QByteArray(32, '\x11'));
}

void TestAirSeenReceipts::removedContactForgetsReceipts()
{
    LocalReadMarks store;
    Wire wire;
    wire.online = false;
    SeenReceipts receipts(store, wire.sender(), wire.presence());
    receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
    QVERIFY(store.local.raiseSeenUpTo(person('a').toString(), 1700000000500));

    QVERIFY(store.local.forget(person('a').toString()));
    QVERIFY(!store.isReadPending(person('a')));
    QVERIFY(store.readAnchor(person('a')).isEmpty());
    QCOMPARE(store.local.seenUpTo(person('a').toString()), qint64{-1});

    wire.online = true;
    receipts.friendOnline(person('a'));
    QVERIFY(wire.sent.isEmpty());
}

void TestAirSeenReceipts::turnedOffSendsAndOwesNothing()
{
    LocalReadMarks store;
    Wire wire;
    SeenReceipts receipts(store, wire.sender(), wire.presence(), 0);
    QVERIFY(receipts.isEnabled());

    receipts.setEnabled(false);
    QVERIFY(!receipts.isEnabled());
    receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
    receipts.friendOnline(person('a'));
    QVERIFY(wire.sent.isEmpty());
    // What is read while they are off is not even noted: nothing of it is told later.
    QVERIFY(!store.isReadPending(person('a')));
    QVERIFY(store.readAnchor(person('a')).isEmpty());

    receipts.setEnabled(true);
    receipts.friendOnline(person('a'));
    QVERIFY(wire.sent.isEmpty());

    // On again, a message read from now on is told as before.
    receipts.seen(person('a'), textAnchor('\x12', 1700000001000));
    QCOMPARE(wire.sent.size(), 1);
    QCOMPARE(wire.sent[0].packet.mid(5, 32), QByteArray(32, '\x12'));
}

void TestAirSeenReceipts::receiptOwedBeforeWaitsWhileTurnedOff()
{
    LocalReadMarks store;
    Wire wire;
    wire.online = false;
    SeenReceipts receipts(store, wire.sender(), wire.presence(), 0);
    receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
    QVERIFY(store.isReadPending(person('a')));

    receipts.setEnabled(false);
    wire.online = true;
    receipts.friendOnline(person('a'));
    QVERIFY(wire.sent.isEmpty());
    QVERIFY(store.isReadPending(person('a')));

    receipts.setEnabled(true);
    receipts.friendOnline(person('a'));
    QCOMPARE(wire.sent.size(), 1);
    QCOMPARE(wire.sent[0].packet.mid(5, 32), QByteArray(32, '\x11'));
    QVERIFY(!store.isReadPending(person('a')));
}

void TestAirSeenReceipts::receiptWaitingForTheGapStopsWhenTurnedOff()
{
    LocalReadMarks store;
    Wire wire;
    const int gapMs = 100;
    SeenReceipts receipts(store, wire.sender(), wire.presence(), gapMs);

    receipts.seen(person('a'), textAnchor('\x11', 1700000000000));
    receipts.seen(person('a'), textAnchor('\x12', 1700000001000));
    QCOMPARE(wire.sent.size(), 1);

    receipts.setEnabled(false);
    QTest::qWait(3 * gapMs);
    QCOMPARE(wire.sent.size(), 1);
    QVERIFY(store.isReadPending(person('a')));
}

QTEST_GUILESS_MAIN(TestAirSeenReceipts)
#include "airseenreceipts_test.moc"
