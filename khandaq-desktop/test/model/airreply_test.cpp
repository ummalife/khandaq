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

#include "src/model/air/airmodel.h"

#include <QRegularExpression>
#include <QtTest/QtTest>

using namespace Air;

namespace {
/** A key of 64 hexadecimal digits that ends in lower case, as a key may come. */
const QString authorKey =
    QStringLiteral("0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789abcdef");
const QChar ellipsis{0x2026};

ReplyMeta meta(qint64 localId, const QString& name, const QString& words)
{
    ReplyMeta result;
    result.localId = localId;
    result.authorKey = authorKey;
    result.timeMs = 1763380800123;
    result.authorName = name;
    result.words = words;
    return result;
}

/** An emoji family: three people joined by two zero-width joiners, 18 bytes of UTF-8. */
QString family()
{
    return QString::fromUtf8(
        "\xF0\x9F\x91\xA8\xE2\x80\x8D\xF0\x9F\x91\xA9\xE2\x80\x8D\xF0\x9F\x91\xA7");
}
} // namespace

/**
 * KHANDAQ (0.2.58): the reply the desktop writes. The expected texts are what khandaq-ios and
 * Android MessageReplyHelper.encode write for the same reply, worked out by hand from their code;
 * where the two differ, the comments say which one the desktop follows and why.
 */
class TestAirReply : public QObject
{
    Q_OBJECT
private slots:
    void writesTheHeaderAsThePhones();
    void readsBackWhatItWrites();
    void namesGoOnOneLineWithoutTheMarkers();
    void quotesGoOnOneLineWithoutTheMarkers();
    void cutsTheQuoteAt200Characters();
    void keepsTheQuoteWithin600Bytes();
    void keyTailIsTheLastEightInUpperCase();
    void localIdIsDjb2OfTheUtf8();
    void headEndsWhereTheWordsBegin();
};

void TestAirReply::writesTheHeaderAsThePhones()
{
    // iOS and Android alike: "[KQ|<id>|<TAIL>|<time>|<name>]\n<quote>\n[KQ/end]\n<words>".
    QCOMPARE(replyHead(meta(193485963, QStringLiteral("Yusuf"),
                            QStringLiteral("Assalamu alaikum, see you at 5")))
                 + QStringLiteral("Wa alaikum salam"),
             QStringLiteral("[KQ|193485963|89ABCDEF|1763380800123|Yusuf]\n"
                            "Assalamu alaikum, see you at 5\n[KQ/end]\nWa alaikum salam"));
    QCOMPARE(replyHead(meta(42, QStringLiteral("Амина"),
                            QStringLiteral("Ассаляму алейкум! Материалы к четвергу в закрепе"))),
             QStringLiteral("[KQ|42|89ABCDEF|1763380800123|Амина]\n"
                            "Ассаляму алейкум! Материалы к четвергу в закрепе\n[KQ/end]\n"));
    // The ends of a name and of the words are trimmed by both.
    QCOMPARE(replyHead(meta(0, QStringLiteral("  Fatima Zahra  "), QStringLiteral("  at five \n"))),
             QStringLiteral("[KQ|0|89ABCDEF|1763380800123|Fatima Zahra]\nat five\n[KQ/end]\n"));
    // A file is quoted by its name.
    QCOMPARE(replyHead(meta(7, QStringLiteral("Li Wei"), QStringLiteral("notes 2026-11.pdf"))),
             QStringLiteral("[KQ|7|89ABCDEF|1763380800123|Li Wei]\nnotes 2026-11.pdf\n[KQ/end]\n"));
}

void TestAirReply::readsBackWhatItWrites()
{
    const QVector<ReplyMeta> replies{
        meta(193485963, QStringLiteral("Yusuf"), QStringLiteral("Assalamu alaikum")),
        meta(1, QStringLiteral("Амина Рахимова"), QStringLiteral("Материалы\nк четвергу")),
        meta(2, QStringLiteral("A]B|C"), QStringLiteral("see [KQ/end] here")),
        meta(3, QStringLiteral("x[KQ|y"), QString(250, QLatin1Char('w'))),
        meta(4, QStringLiteral("أمينة"), QStringLiteral("السلام عليكم")),
    };
    for (const ReplyMeta& reply : replies) {
        const QString words = QStringLiteral("the answer\nin two lines");
        const MessageText read = readMessageText(replyHead(reply) + words);
        QVERIFY2(read.reply, qPrintable(reply.authorName));
        QVERIFY(read.marked);
        QVERIFY(!read.unreadable);
        QCOMPARE(read.replyName, replyHeaderName(reply.authorName));
        QCOMPARE(read.replyPreview, replyQuote(reply.words));
        QCOMPARE(read.body, words);
        // The list and the notifications read the words only.
        QCOMPARE(plainMessageBody(replyHead(reply) + words), words);
    }
}

void TestAirReply::namesGoOnOneLineWithoutTheMarkers()
{
    // iOS sanitizeField: "]" and "|" become spaces, the ends are trimmed.
    QCOMPARE(replyHeaderName(QStringLiteral(" Юсуф ] | Ахмед ")), QStringLiteral("Юсуф     Ахмед"));
    QCOMPARE(replyHeaderName(QStringLiteral("A]B|C")), QStringLiteral("A B C"));
    // Line breaks become spaces, as Android does (iOS drops them and joins the lines).
    QCOMPARE(replyHeaderName(QStringLiteral("Amina\nSafina\r")), QStringLiteral("Amina Safina"));
    // Other control characters go, a tab and the NUL older builds left among them (iOS #161).
    QCOMPARE(replyHeaderName(QStringLiteral("Tab\tName") + QChar(0)), QStringLiteral("TabName"));
    // The markers of a reply go, as Android does (audit #18), and none is left by joining halves.
    QCOMPARE(replyHeaderName(QStringLiteral("Bad[KQ/end]Name")), QStringLiteral("BadName"));
    QCOMPARE(replyHeaderName(QStringLiteral("x[KQ|y")), QStringLiteral("xy"));
    const QString joined = replyHeaderName(QStringLiteral("[KQ[KQ|/end]Name"));
    QVERIFY(!joined.contains(QStringLiteral("[KQ")));
    QCOMPARE(joined, QStringLiteral("Name"));
    // Whatever the name, the header ends where it should.
    const QString head = replyHead(meta(5, QStringLiteral("]|\n[KQ/end]"), QStringLiteral("q")));
    QCOMPARE(head, QStringLiteral("[KQ|5|89ABCDEF|1763380800123|]\nq\n[KQ/end]\n"));
}

void TestAirReply::quotesGoOnOneLineWithoutTheMarkers()
{
    // Android truncatePreview: line breaks become spaces.
    QCOMPARE(replyQuote(QStringLiteral("line one\nline two\r\nthree")),
             QStringLiteral("line one line two  three"));
    // The markers go (Android, audit #18): the quote never ends early on a reader.
    QCOMPARE(replyQuote(QStringLiteral("see [KQ/end] and [KQ|1|x|2|y] here")),
             QStringLiteral("see  and 1|x|2|y] here"));
    QVERIFY(!replyQuote(QStringLiteral("[KQ[KQ|/end]")).contains(QStringLiteral("[KQ")));
    QCOMPARE(replyQuote(QStringLiteral("  hello") + QChar(0) + QStringLiteral("  ")),
             QStringLiteral("hello"));
    QCOMPARE(replyQuote(QStringLiteral(" \n ")), QString());
}

void TestAirReply::cutsTheQuoteAt200Characters()
{
    const QString exact(200, QLatin1Char('x'));
    QCOMPARE(replyQuote(exact), exact);
    // 199 characters and the ellipsis, as iOS previewForDisplay(maxLen: 200) and Android cut them.
    QCOMPARE(replyQuote(QString(201, QLatin1Char('x'))), QString(199, QLatin1Char('x')) + ellipsis);
    QCOMPARE(replyQuote(QString(198, QLatin1Char('a')) + QStringLiteral(" ")
                        + QString(10, QLatin1Char('b'))),
             QString(198, QLatin1Char('a')) + ellipsis);
    QCOMPARE(replyQuote(QString(500, QLatin1Char('x'))).size(), 200);

    // Characters as people count them, as iOS counts a String: a letter with its accent is one,
    // and the accent never comes off.
    const QString accented = QStringLiteral("e") + QChar(0x0301);
    QString words;
    for (int i = 0; i < 250; ++i) {
        words += accented;
    }
    QCOMPARE(replyQuote(words), accented.repeated(199) + ellipsis);
}

void TestAirReply::keepsTheQuoteWithin600Bytes()
{
    // Fewer than 200 characters, but 18 bytes each: the quote keeps as many as fit with the
    // ellipsis in 600 bytes, so that the head of a reply always leaves room for words.
    const QString words = family().repeated(150);
    const QString quote = replyQuote(words);
    QCOMPARE(quote, family().repeated(33) + ellipsis);
    QVERIFY(quote.toUtf8().size() <= 600);
    // Words within both limits are kept whole.
    const QString fits = family().repeated(33);
    QCOMPARE(replyQuote(fits), fits);
    // 200 Chinese characters take 600 bytes and are kept whole, as the phones keep them.
    const QString chinese = QString(200, QChar(0x4E2D));
    QCOMPARE(replyQuote(chinese), chinese);
}

void TestAirReply::keyTailIsTheLastEightInUpperCase()
{
    QCOMPARE(keyTail(authorKey), QStringLiteral("89ABCDEF"));
    QVERIFY(QRegularExpression(QStringLiteral("^[0-9A-F]{8}$")).match(keyTail(authorKey)).hasMatch());
    QCOMPARE(keyTail(QStringLiteral("deadbeef")), QStringLiteral("DEADBEEF"));
    // pubkeyTail: a shorter key whole, nothing for none.
    QCOMPARE(keyTail(QStringLiteral("abc")), QStringLiteral("ABC"));
    QCOMPARE(keyTail(QString()), QString());
}

void TestAirReply::localIdIsDjb2OfTheUtf8()
{
    // iOS stableLocalId: djb2 over the UTF-8 bytes, wrapping, kept non-negative.
    QCOMPARE(stableLocalId(QString()), qint64{5381});
    QCOMPARE(stableLocalId(QStringLiteral("abc")), qint64{193485963});
    QCOMPARE(stableLocalId(QStringLiteral("я")), qint64{5866949});
    QCOMPARE(stableLocalId(QStringLiteral("Ассаляму алейкум")), qint64{2366436191696766295});
    QCOMPARE(stableLocalId(authorKey.toUpper() + QStringLiteral("|1763380800123|Assalamu alaikum")),
             qint64{4095325698435002458});
    QVERIFY(stableLocalId(QString(1000, QLatin1Char('z'))) >= 0);
}

void TestAirReply::headEndsWhereTheWordsBegin()
{
    const ReplyMeta reply = meta(193485963, QStringLiteral("Yusuf"), QStringLiteral("what time?"));
    const QString head = replyHead(reply);
    const QString raw = head + QStringLiteral("at five");
    QCOMPARE(replyHeadLength(raw), head.size());
    QCOMPARE(raw.mid(replyHeadLength(raw)), QStringLiteral("at five"));
    QCOMPARE(replyHeadLength(head), head.size());

    // The head is read back as it was written: written again, it is the same.
    ReplyMeta read;
    QVERIFY(readReplyHead(raw, &read));
    QCOMPARE(read.localId, reply.localId);
    QCOMPARE(read.authorKey, QStringLiteral("89ABCDEF"));
    QCOMPARE(read.timeMs, reply.timeMs);
    QCOMPARE(read.authorName, QStringLiteral("Yusuf"));
    QCOMPARE(read.words, QStringLiteral("what time?"));
    QCOMPARE(replyHead(read), head);

    // Other texts have no head of this form.
    for (const QString& other :
         {QStringLiteral("plain text"), QStringLiteral("[KQ|7|AB|1|Yusuf]\nquoted line\nmy words"),
          QStringLiteral("----\nold quote\n----\nthe reply"), QStringLiteral("> quote\nanswer"),
          QStringLiteral("[KQ|m|ABCDEF12:amina][KQ/m]@amina"), QString()}) {
        QCOMPARE(replyHeadLength(other), 0);
        QVERIFY(!readReplyHead(other, &read));
    }
}

QTEST_GUILESS_MAIN(TestAirReply)
#include "airreply_test.moc"
