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

#include "src/core/msgv3.h"

#include <QtTest/QtTest>

#include <tox/tox.h>

namespace {
QByteArray countingId()
{
    QByteArray id(MsgV3::idLength, '\0');
    for (int i = 0; i < id.size(); ++i) {
        id[i] = static_cast<char>(i);
    }
    return id;
}

const uint8_t* bytesOf(const QByteArray& bytes)
{
    return reinterpret_cast<const uint8_t*>(bytes.constData());
}
} // namespace

class TestMsgV3 : public QObject
{
    Q_OBJECT
private slots:
    void sizesAreThoseOfTheFork();
    void trailerIsBuiltByteForByte();
    void trailerIsReadBack();
    void plainTextHasNoTrailer();
    void shortOrUnguardedMessageIsPlain();
    void oldDesktopShowsOnlyTheText();
    void idOfWrongSizeAddsNoTrailer();
    void newIdsAreRandom();
    void stampFollowsTheClock();
    void stampWaitsForTheNextSecond();
    void stampOfAClockSetBack();
    void partsAreASecondApart();
};

void TestMsgV3::sizesAreThoseOfTheFork()
{
#ifdef TOX_MSGV3_MSGID_LENGTH
    QCOMPARE(MsgV3::guardLength, TOX_MSGV3_GUARD);
    QCOMPARE(MsgV3::idLength, TOX_MSGV3_MSGID_LENGTH);
    QCOMPARE(MsgV3::timeLength, TOX_MSGV3_TIMESTAMP_LENGTH);
    // The text that fits one msgV3 message is the composer's limit (Air::composeLimitBytes).
    QCOMPARE(static_cast<int>(TOX_MSGV3_MAX_MESSAGE_LENGTH), 1334);
#else
    QSKIP("this toxcore has no msgV3");
#endif
}

void TestMsgV3::trailerIsBuiltByteForByte()
{
    const QByteArray text = QByteArrayLiteral("Salam");
    const QByteArray bytes = MsgV3::withTrailer(text, countingId(), 0x6553F100);

    QCOMPARE(bytes.size(), text.size() + 38);
    QCOMPARE(bytes.left(text.size()), text);
    // The guard right after the text, then the id, then the time, big-endian.
    QCOMPARE(bytes.mid(text.size(), 2), QByteArray(2, '\0'));
    QCOMPARE(bytes.mid(text.size() + 2, 32), countingId());
    QCOMPARE(bytes.right(4), QByteArray::fromHex("6553F100"));
}

void TestMsgV3::trailerIsReadBack()
{
    // As jni-c-toxcore.c tox_messagev3_friend_send_message lays it out: text, the calloc'ed guard,
    // the hash, then xnet_pack_u32 of the time.
    QByteArray wire = QByteArrayLiteral("Как дела? 🙂");
    wire.append(QByteArray(2, '\0'));
    wire.append(QByteArray(32, '\xAB'));
    wire.append(QByteArray::fromHex("01020304"));

    QByteArray id;
    quint32 time = 7;
    const QByteArray text = MsgV3::textOf(bytesOf(wire), static_cast<size_t>(wire.size()), &id, &time);
    QCOMPARE(QString::fromUtf8(text), QStringLiteral("Как дела? 🙂"));
    QCOMPARE(id, QByteArray(32, '\xAB'));
    QCOMPARE(time, quint32{0x01020304});

    const QByteArray built = MsgV3::withTrailer(text, id, time);
    QCOMPARE(built, wire);
}

void TestMsgV3::plainTextHasNoTrailer()
{
    const QByteArray wire =
        QByteArrayLiteral("a plain message from a desktop of 0.2.56, long enough");
    QByteArray id = QByteArrayLiteral("stale");
    quint32 time = 9;
    const QByteArray text = MsgV3::textOf(bytesOf(wire), static_cast<size_t>(wire.size()), &id, &time);
    QCOMPARE(text, wire);
    QVERIFY(id.isEmpty());
    QCOMPARE(time, quint32{0});
    // Null outputs are allowed.
    QCOMPARE(MsgV3::textOf(bytesOf(wire), static_cast<size_t>(wire.size()), nullptr, nullptr), wire);
    QVERIFY(MsgV3::textOf(nullptr, 0, &id, &time).isEmpty());
}

void TestMsgV3::shortOrUnguardedMessageIsPlain()
{
    // Exactly the trailer's length: the phones only read a trailer after at least one byte.
    QByteArray exact(38, '\0');
    QByteArray id;
    QCOMPARE(MsgV3::textOf(bytesOf(exact), static_cast<size_t>(exact.size()), &id, nullptr), exact);
    QVERIFY(id.isEmpty());

    // A byte where the guard goes is not zero.
    QByteArray unguarded = MsgV3::withTrailer(QByteArrayLiteral("x"), countingId(), 1);
    unguarded[2] = 'g';
    QCOMPARE(MsgV3::textOf(bytesOf(unguarded), static_cast<size_t>(unguarded.size()), &id, nullptr),
             unguarded);
    QVERIFY(id.isEmpty());
}

void TestMsgV3::oldDesktopShowsOnlyTheText()
{
    // A desktop of 0.2.56 reads a message with QString::fromUtf8(QByteArray) (ToxString), which
    // stops at the first NUL: the guard. So it shows our text and nothing of the trailer.
    const QString words = QStringLiteral("Ассаламу алейкум, 你好");
    const QByteArray wire = MsgV3::withTrailer(words.toUtf8(), countingId(), 0x6553F100);
    QCOMPARE(QString::fromUtf8(wire), words);
}

void TestMsgV3::idOfWrongSizeAddsNoTrailer()
{
    const QByteArray text = QByteArrayLiteral("text");
    QCOMPARE(MsgV3::withTrailer(text, QByteArray(), 5), text);
    QCOMPARE(MsgV3::withTrailer(text, QByteArray(31, 'a'), 5), text);
}

void TestMsgV3::newIdsAreRandom()
{
    const QByteArray first = MsgV3::newId();
    const QByteArray second = MsgV3::newId();
    QCOMPARE(first.size(), 32);
    QCOMPARE(second.size(), 32);
    QVERIFY(first != second);
    QVERIFY(first != QByteArray(32, '\0'));
}

void TestMsgV3::stampFollowsTheClock()
{
    // Nothing sent yet, or the last text was in an earlier second: the clock.
    QCOMPARE(MsgV3::sentMsAfter(0, 1700000000123), qint64{1700000000123});
    QCOMPARE(MsgV3::sentMsAfter(1700000000999, 1700000001000), qint64{1700000001000});
}

void TestMsgV3::stampWaitsForTheNextSecond()
{
    // The clock is still in the second of the last stamp: the start of the next one.
    QCOMPARE(MsgV3::sentMsAfter(1700000000100, 1700000000900), qint64{1700000001000});
    // The last stamp runs ahead of the clock (the parts of a long text): one second after it.
    QCOMPARE(MsgV3::sentMsAfter(1700000005000, 1700000001500), qint64{1700000006000});
}

void TestMsgV3::stampOfAClockSetBack()
{
    // An hour or more ahead is a clock set back: the stamps start from the clock again.
    QCOMPARE(MsgV3::sentMsAfter(1700003600000, 1700000000000), qint64{1700000000000});
    QCOMPARE(MsgV3::sentMsAfter(1700003599000, 1700000000000), qint64{1700003600000});
}

void TestMsgV3::partsAreASecondApart()
{
    // A text of three parts, then a text right after it: four stamps in four seconds, in order.
    const qint64 now = 1700000000400;
    const qint64 first = MsgV3::sentMsAfter(0, now);
    QVector<qint64> seconds;
    for (int part = 0; part < 3; ++part) {
        seconds << (first + part * MsgV3::partGapMs) / 1000;
    }
    const qint64 last = first + 2 * MsgV3::partGapMs;
    seconds << MsgV3::sentMsAfter(last, now + 10) / 1000;
    for (int i = 1; i < seconds.size(); ++i) {
        QCOMPARE(seconds[i], seconds[i - 1] + 1);
    }
}

QTEST_GUILESS_MAIN(TestMsgV3)
#include "msgv3_test.moc"
