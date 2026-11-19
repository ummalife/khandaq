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

#include "src/model/air/airhistorysync.h"

#include <QtTest/QtTest>

using namespace Air;

namespace {
const QByteArray magic = QByteArray::fromHex("667788113435");
const QByteArray author =
    QByteArray::fromHex("A1A2A3A4A5A6A7A8A9AAABACADAEAFB0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0");
const QByteArray chatId(32, 'g');
/** 1700000000 seconds, 0x6553F100. */
constexpr quint32 stamp = 1700000000;

/**
 * A text of the history as Android lays it out (HelperGroup, the serve of a text): magic, 01 02,
 * the message id, the author, the time, a name of 25 bytes, then the words.
 */
QByteArray syncText(const QByteArray& words, const QByteArray& id = QByteArray::fromHex("0A0B0C0D"),
                    const QByteArray& key = author, char version = '\x01')
{
    QByteArray name("Maryam");
    name.append(QByteArray(25 - name.size(), '\0'));
    return magic + QByteArray(1, version) + QByteArray(1, '\x02') + id + key
           + QByteArray::fromHex("6553F100") + name + words;
}

bool routed(const QByteArray& packet)
{
    return isSyncTextPacket(reinterpret_cast<const uint8_t*>(packet.constData()),
                            static_cast<size_t>(packet.size()));
}

bool read(const QByteArray& packet, SyncedText* text = nullptr)
{
    SyncedText ignored;
    return readSyncText(packet, text ? text : &ignored);
}
} // namespace

/**
 * KHANDAQ (0.2.63): the texts the members relay from the history of a group (0x02 of version 1),
 * the request for that history (0x01), and what keeps a text from showing twice or the members
 * from being asked too often.
 */
class TestAirHistorySync : public QObject
{
    Q_OBJECT
private slots:
    void aTextOfTheHistoryIsReadAsThePhonesLayItOut();
    void itsLengthIsTheHeaderAndOneTo37000BytesOfWords();
    void onlyVersionOneIsATextOfTheHistory();
    void aRecordWithoutIdAuthorOrWordsIsDropped();
    void theTimeIsTakenFrom200MinutesBeforeTo5After();
    void partsOfOneSecondKeepTimesOfTheirOwn();
    void theRequestIsTheEightBytesOfTheHeader();
    void aTextIsSeenOnceByGroupAuthorIdAndWords();
    void theLedgerKeepsTheNewestTexts();
    void aGroupRelaysAtMost5000TextsInOneRun();
    void aGroupRelaysAtMost8MiBOfWordsInOneRun();
    void aMemberIsAskedOnceInTwentySeconds();
};

void TestAirHistorySync::aTextOfTheHistoryIsReadAsThePhonesLayItOut()
{
    const QString words = QStringLiteral("Salam é \U0001F44D");
    const QByteArray packet = syncText(words.toUtf8());
    QCOMPARE(packet.size(), 73 + words.toUtf8().size());
    QVERIFY(routed(packet));
    SyncedText text;
    QVERIFY(read(packet, &text));
    QCOMPARE(text.messageId, quint32{0x0A0B0C0D});
    QCOMPARE(text.author, author);
    QCOMPARE(text.time, stamp);
    // The name in the packet is not read: nobody vouches for it.
    QCOMPARE(text.text, words);
}

void TestAirHistorySync::itsLengthIsTheHeaderAndOneTo37000BytesOfWords()
{
    // Android takes it from 74 bytes on, and the senders put 37000 bytes of words at most.
    QVERIFY(read(syncText("a")));
    QVERIFY(!read(syncText(QByteArray())));
    QVERIFY(!read(syncText("a").left(72)));
    QVERIFY(read(syncText(QByteArray(37000, 'a'))));
    QVERIFY(!read(syncText(QByteArray(37001, 'a'))));
    // Still routed here, to be dropped: none of it is a file.
    QVERIFY(routed(syncText(QByteArray(37001, 'a'))));
}

void TestAirHistorySync::onlyVersionOneIsATextOfTheHistory()
{
    // The signed text (02 02) and the key announcement (02 50) of version 2 are not read.
    const QByteArray signedText =
        syncText("Salam", QByteArray::fromHex("0A0B0C0D"), author, '\x02');
    QVERIFY(!routed(signedText));
    QVERIFY(!read(signedText));
    QByteArray announcement = signedText;
    announcement[7] = 0x50;
    QVERIFY(!routed(announcement));
    // Nor a file of the history (01 03), the request (01 01) or another magic.
    QByteArray file = syncText("Salam");
    file[7] = 0x03;
    QVERIFY(!routed(file));
    QVERIFY(!read(file));
    QVERIFY(!routed(historyRequestPacket()));
    QByteArray foreign = syncText("Salam");
    foreign[0] = 0x67;
    QVERIFY(!routed(foreign));
    QVERIFY(!read(foreign));
    QVERIFY(!routed(magic + QByteArray::fromHex("01")));
}

void TestAirHistorySync::aRecordWithoutIdAuthorOrWordsIsDropped()
{
    // Id 0 is a message still on its way: it names nothing.
    QVERIFY(!read(syncText("Salam", QByteArray(4, '\0'))));
    // An author of zeros is nobody.
    QVERIFY(!read(syncText("Salam", QByteArray::fromHex("0A0B0C0D"), QByteArray(32, '\0'))));
    QVERIFY(read(syncText("Salam", QByteArray::fromHex("00000001"))));
}

void TestAirHistorySync::theTimeIsTakenFrom200MinutesBeforeTo5After()
{
    const qint64 now = 1700000000;
    QVERIFY(syncTimeAccepted(stamp, now));
    QVERIFY(syncTimeAccepted(static_cast<quint32>(now - 200 * 60), now));
    QVERIFY(!syncTimeAccepted(static_cast<quint32>(now - 200 * 60 - 1), now));
    QVERIFY(syncTimeAccepted(static_cast<quint32>(now + 5 * 60), now));
    QVERIFY(!syncTimeAccepted(static_cast<quint32>(now + 5 * 60 + 1), now));
    QVERIFY(!syncTimeAccepted(0, now));
    QVERIFY(!syncTimeAccepted(0xFFFFFFFFu, now));
    // The history is searched as far back, both sides of the window.
    QCOMPARE(syncedTextKnownMs, qint64{12300} * 1000);
}

void TestAirHistorySync::partsOfOneSecondKeepTimesOfTheirOwn()
{
    SyncedText first;
    first.time = stamp;
    first.messageId = 0x0A0B0C0D;
    SyncedText second = first;
    second.messageId = 0x0A0B0C0E;
    QCOMPARE(syncedTextTimeMs(first), qint64{1700000000} * 1000 + 0x0A0B0C0D % 1000);
    QVERIFY(syncedTextTimeMs(first) != syncedTextTimeMs(second));
    // Within the second it was written.
    QVERIFY(syncedTextTimeMs(second) / 1000 == qint64{stamp});
}

void TestAirHistorySync::theRequestIsTheEightBytesOfTheHeader()
{
    QCOMPARE(historyRequestPacket(), QByteArray::fromHex("6677881134350101"));
}

void TestAirHistorySync::aTextIsSeenOnceByGroupAuthorIdAndWords()
{
    SyncedTextLedger ledger;
    QVERIFY(!ledger.seen(chatId, author, 7, QStringLiteral("Salam")));
    QVERIFY(ledger.seen(chatId, author, 7, QStringLiteral("Salam")));
    // A forged record with the same id and other words takes nothing from the real one.
    QVERIFY(!ledger.seen(chatId, author, 7, QStringLiteral("Forged")));
    // Another group, author or id is another text.
    QVERIFY(!ledger.seen(QByteArray(32, 'h'), author, 7, QStringLiteral("Salam")));
    QVERIFY(!ledger.seen(chatId, QByteArray(32, 'b'), 7, QStringLiteral("Salam")));
    QVERIFY(!ledger.seen(chatId, author, 8, QStringLiteral("Salam")));
}

void TestAirHistorySync::theLedgerKeepsTheNewestTexts()
{
    SyncedTextLedger ledger;
    for (int i = 0; i < SyncedTextLedger::limit + 1; ++i) {
        QVERIFY(!ledger.seen(chatId, author, static_cast<quint32>(i + 1), QStringLiteral("text")));
    }
    // The oldest went to make room; the newest stays.
    QVERIFY(!ledger.seen(chatId, author, 1, QStringLiteral("text")));
    QVERIFY(ledger.seen(chatId, author, static_cast<quint32>(SyncedTextLedger::limit + 1),
                        QStringLiteral("text")));
}

void TestAirHistorySync::aGroupRelaysAtMost5000TextsInOneRun()
{
    QCOMPARE(SyncedTextBudget::maxTexts, 5000);
    SyncedTextBudget budget;
    for (int i = 0; i < SyncedTextBudget::maxTexts; ++i) {
        QVERIFY(budget.take(chatId, 1));
    }
    QCOMPARE(budget.refused(chatId), 0);
    QVERIFY(!budget.take(chatId, 1));
    QVERIFY(!budget.take(chatId, 0));
    QCOMPARE(budget.refused(chatId), 2);
    // Another group has a budget of its own.
    const QByteArray other(32, 'h');
    QVERIFY(budget.take(other, 1));
    QCOMPARE(budget.refused(other), 0);
}

void TestAirHistorySync::aGroupRelaysAtMost8MiBOfWordsInOneRun()
{
    QCOMPARE(SyncedTextBudget::maxBytes, qint64{8 * 1024 * 1024});
    SyncedTextBudget budget;
    // 226 of the longest texts are 8362000 bytes; the 227th would be past 8388608.
    for (int i = 0; i < 226; ++i) {
        QVERIFY(budget.take(chatId, 37000));
    }
    QVERIFY(!budget.take(chatId, 37000));
    QVERIFY(budget.take(chatId, 8388608 - 8362000));
    QVERIFY(!budget.take(chatId, 1));
    QVERIFY(!budget.take(chatId, -1));
    QCOMPARE(budget.refused(chatId), 3);
}

void TestAirHistorySync::aMemberIsAskedOnceInTwentySeconds()
{
    QCOMPARE(HistoryRequestPace::gapMs, qint64{20000});
    QCOMPARE(HistoryRequestPace::minDelayMs, 1000);
    QCOMPARE(HistoryRequestPace::maxDelayMs, 7000);
    HistoryRequestPace pace;
    const QByteArray member(32, 'm');
    const QByteArray other(32, 'o');
    QVERIFY(pace.mayAsk(chatId, member, 1000));
    pace.asked(chatId, member, 1000);
    QVERIFY(!pace.mayAsk(chatId, member, 1000));
    QVERIFY(!pace.mayAsk(chatId, member, 20999));
    QVERIFY(pace.mayAsk(chatId, member, 21000));
    // Each member and each group by itself.
    QVERIFY(pace.mayAsk(chatId, other, 1000));
    QVERIFY(pace.mayAsk(QByteArray(32, 'h'), member, 1000));
    // A request that did not go is no request: only asked() counts.
    QVERIFY(pace.mayAsk(chatId, other, 2000));
}

QTEST_GUILESS_MAIN(TestAirHistorySync)
#include "airhistorysync_test.moc"
