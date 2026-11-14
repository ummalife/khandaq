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

#include "src/model/air/airgroupleave.h"

#include <QtTest/QtTest>

using namespace Air;

namespace {
bool isNotice(const QByteArray& packet)
{
    return isGroupLeaveNotice(reinterpret_cast<const uint8_t*>(packet.constData()),
                              static_cast<size_t>(packet.size()));
}

const QByteArray keyA(32, 'a');
const QByteArray keyB(32, 'b');
const QByteArray chatA(32, '\x0a');
const QByteArray chatB(32, '\x0b');
constexpr qint64 at = 1000000;
constexpr qint64 relayTimeout = 54 * 1000;
constexpr qint64 delay = PendingGroupLeaves::delayMs;
} // namespace

// QA live F11: a member that leaves a group says so first, since toxcore's QUIT is the same when its
// app closes. Android and iOS read the same eight bytes.
class TestAirGroupLeave : public QObject
{
    Q_OBJECT
private slots:
    void theNoticeIsTheNgcHeaderWithId0x44();
    void laterBytesAfterTheHeaderAreNotRead();
    void otherNgcPacketsAreNotANotice();
    void aNoticeHidesTheMemberAtOnce();
    void theExitThatFollowsKeepsItHiddenUntilItJoins();
    void aNoticeThatNoExitFollowsLapses();
    void anotherPeersExitChangesNothing();
    void leavingTheGroupForgetsItsNotices();
    void ourLeaveWaitsForTheResendsOfItsNotice();
    void aGroupWeLeaveIsKnownByItsNumberAndItsChatId();
    void leavingAgainReplacesTheLeave();
    void joiningAgainCancelsTheLeave();
    void closingTheProfileTakesEveryLeave();
    void theProfileKeepsTheChatIds();
    void aStoredLineThatHoldsNoChatIdIsSkipped();
};

void TestAirGroupLeave::theNoticeIsTheNgcHeaderWithId0x44()
{
    QCOMPARE(groupLeaveNoticePacket(), QByteArray::fromHex("6677881134350144"));
    QVERIFY(isNotice(groupLeaveNoticePacket()));
}

void TestAirGroupLeave::laterBytesAfterTheHeaderAreNotRead()
{
    QVERIFY(isNotice(groupLeaveNoticePacket() + QByteArray::fromHex("0001")));
}

void TestAirGroupLeave::otherNgcPacketsAreNotANotice()
{
    QVERIFY(!isGroupLeaveNotice(nullptr, 8));
    QVERIFY(!isNotice(groupLeaveNoticePacket().left(7)));
    for (const char id : {'\x01', '\x11', '\x21', '\x31', '\x41', '\x42', '\x43', '\x45', '\x50'}) {
        QByteArray other = groupLeaveNoticePacket();
        other[7] = id;
        QVERIFY2(!isNotice(other), qPrintable(QString::number(static_cast<uint8_t>(id), 16)));
    }
    QByteArray version2 = groupLeaveNoticePacket();
    version2[6] = '\x02';
    QVERIFY(!isNotice(version2));
}

void TestAirGroupLeave::aNoticeHidesTheMemberAtOnce()
{
    GroupLeaveNotices notices;
    QVERIFY(!notices.hidesAny(3));
    notices.note(3, keyA, 7, at);
    QVERIFY(notices.hidesAny(3));
    QVERIFY(notices.hides(3, keyA, at));
    // Over a relay the exit comes as a timeout a minute later: the member is not listed meanwhile.
    QVERIFY(notices.hides(3, keyA, at + relayTimeout));
    QVERIFY(!notices.hides(3, keyB, at));
    QVERIFY(!notices.hides(4, keyA, at));
    QVERIFY(!notices.hidesAny(4));
}

void TestAirGroupLeave::theExitThatFollowsKeepsItHiddenUntilItJoins()
{
    GroupLeaveNotices notices;
    notices.note(3, keyA, 7, at);
    QVERIFY(notices.exited(3, 7, at + relayTimeout));
    // toxcore lists the member that exited again for a while, unconfirmed and nameless.
    QVERIFY(notices.hides(3, keyA, at + GroupLeaveNotices::windowMs + relayTimeout));
    // That listing timing out is a second exit of the same peer id, not a second leave.
    QVERIFY(!notices.exited(3, 7, at + 2 * GroupLeaveNotices::windowMs));
    notices.joined(3, keyA);
    QVERIFY(!notices.hides(3, keyA, at + 2 * GroupLeaveNotices::windowMs));
    QVERIFY(!notices.hidesAny(3));
}

void TestAirGroupLeave::aNoticeThatNoExitFollowsLapses()
{
    GroupLeaveNotices notices;
    notices.note(3, keyA, 7, at);
    const qint64 late = at + GroupLeaveNotices::windowMs + 1;
    QVERIFY(!notices.hides(3, keyA, late));
    // An exit after that is like any other, and it ends the notice.
    QVERIFY(!notices.exited(3, 7, late));
    QVERIFY(!notices.hidesAny(3));
    // A clock that went back hides nobody either.
    notices.note(3, keyB, 8, at);
    QVERIFY(!notices.hides(3, keyB, at - 1));
}

void TestAirGroupLeave::anotherPeersExitChangesNothing()
{
    GroupLeaveNotices notices;
    notices.note(3, keyA, 7, at);
    QVERIFY(!notices.exited(3, 8, at + 1000));
    QVERIFY(!notices.exited(4, 7, at + 1000));
    QVERIFY(notices.hides(3, keyA, at + 1000));
    // A member that joins again before its exit is a member again.
    notices.joined(3, keyA);
    QVERIFY(!notices.hides(3, keyA, at + 1000));
    QVERIFY(!notices.exited(3, 7, at + 2000));
}

void TestAirGroupLeave::leavingTheGroupForgetsItsNotices()
{
    GroupLeaveNotices notices;
    notices.note(3, keyA, 7, at);
    notices.note(3, QByteArray(), 9, at);
    notices.forgetGroup(3);
    QVERIFY(!notices.hidesAny(3));
    QVERIFY(!notices.hides(3, keyA, at));
}

// QA live F11: our own leave waits while toxcore resends its notice, at about +3, +5 and +9 s.
void TestAirGroupLeave::ourLeaveWaitsForTheResendsOfItsNotice()
{
    QVERIFY(delay > 9 * 1000);
    PendingGroupLeaves leaves;
    leaves.record(3, chatA, QByteArray(), at);
    QVERIFY(leaves.takeDue(at).isEmpty());
    QVERIFY(leaves.takeDue(at + delay - 1).isEmpty());
    QVERIFY(leaves.containsGroup(3));
    const QVector<PendingGroupLeaves::Leave> due = leaves.takeDue(at + delay);
    QCOMPARE(due.size(), 1);
    QCOMPARE(due.first().group, 3);
    QCOMPARE(due.first().chatId, chatA);
    QVERIFY(due.first().partMessage.isEmpty());
    // Taken out, so that it is left once.
    QVERIFY(!leaves.containsGroup(3));
    QVERIFY(leaves.takeDue(at + 2 * delay).isEmpty());
}

void TestAirGroupLeave::aGroupWeLeaveIsKnownByItsNumberAndItsChatId()
{
    PendingGroupLeaves leaves;
    QVERIFY(!leaves.containsGroup(3));
    QCOMPARE(leaves.groupOf(chatA), -1);
    leaves.record(3, chatA, QByteArray(), at);
    leaves.record(5, chatB, QByteArray("bye"), at + 1000);
    QVERIFY(leaves.containsGroup(3));
    QVERIFY(leaves.containsGroup(5));
    QVERIFY(!leaves.containsGroup(4));
    QCOMPARE(leaves.groupOf(chatA), 3);
    QCOMPARE(leaves.groupOf(chatB), 5);
    QCOMPARE(leaves.groupOf(QByteArray(32, '\x0c')), -1);
    QCOMPARE(leaves.chatIds(), (QList<QByteArray>{chatA, chatB}));
    // Each is due in its own time.
    QVector<PendingGroupLeaves::Leave> due = leaves.takeDue(at + delay);
    QCOMPARE(due.size(), 1);
    QCOMPARE(due.first().group, 3);
    QVERIFY(leaves.containsGroup(5));
    due = leaves.takeDue(at + 1000 + delay);
    QCOMPARE(due.size(), 1);
    QCOMPARE(due.first().group, 5);
    QCOMPARE(due.first().partMessage, QByteArray("bye"));
    QVERIFY(leaves.chatIds().isEmpty());
}

void TestAirGroupLeave::leavingAgainReplacesTheLeave()
{
    PendingGroupLeaves leaves;
    leaves.record(3, chatA, QByteArray(), at);
    leaves.record(3, chatA, QByteArray(), at + 5000);
    QCOMPARE(leaves.chatIds().size(), 1);
    QVERIFY(leaves.takeDue(at + delay).isEmpty());
    // The same chat id under another number is the same group.
    leaves.record(6, chatA, QByteArray(), at + 5000);
    QVERIFY(!leaves.containsGroup(3));
    QCOMPARE(leaves.groupOf(chatA), 6);
    // So is the same number with another chat id: the number holds one group at a time.
    leaves.record(6, chatB, QByteArray(), at + 5000);
    QCOMPARE(leaves.groupOf(chatA), -1);
    QCOMPARE(leaves.chatIds(), QList<QByteArray>{chatB});
    QCOMPARE(leaves.takeDue(at + 5000 + delay).size(), 1);
}

void TestAirGroupLeave::joiningAgainCancelsTheLeave()
{
    PendingGroupLeaves leaves;
    leaves.record(3, chatA, QByteArray(), at);
    leaves.record(5, chatB, QByteArray(), at);
    QVERIFY(!leaves.cancel(QByteArray(32, '\x0c')));
    PendingGroupLeaves::Leave cancelled;
    QVERIFY(leaves.cancel(chatA, &cancelled));
    QCOMPARE(cancelled.group, 3);
    QCOMPARE(cancelled.chatId, chatA);
    // Its time never comes: the new instance may get the same number and has the same chat id.
    QVERIFY(!leaves.containsGroup(3));
    QCOMPARE(leaves.groupOf(chatA), -1);
    const QVector<PendingGroupLeaves::Leave> due = leaves.takeDue(at + delay);
    QCOMPARE(due.size(), 1);
    QCOMPARE(due.first().group, 5);
    QVERIFY(!leaves.cancel(chatA));
}

void TestAirGroupLeave::closingTheProfileTakesEveryLeave()
{
    PendingGroupLeaves leaves;
    QVERIFY(leaves.takeAll().isEmpty());
    leaves.record(3, chatA, QByteArray(), at);
    leaves.record(5, chatB, QByteArray(), at + 1000);
    const QVector<PendingGroupLeaves::Leave> all = leaves.takeAll();
    QCOMPARE(all.size(), 2);
    QCOMPARE(all.at(0).group, 3);
    QCOMPARE(all.at(1).group, 5);
    QVERIFY(!leaves.containsGroup(3));
    QVERIFY(leaves.chatIds().isEmpty());
    QVERIFY(leaves.takeDue(at + 2 * delay).isEmpty());
}

void TestAirGroupLeave::theProfileKeepsTheChatIds()
{
    QCOMPARE(PendingGroupLeaves::store({}), QString());
    QVERIFY(PendingGroupLeaves::parseStore(QString()).isEmpty());
    const QString stored = PendingGroupLeaves::store({chatA, chatB});
    QCOMPARE(stored, QString(QStringLiteral("0A")).repeated(32) + QLatin1Char('\n')
                         + QString(QStringLiteral("0B")).repeated(32));
    QCOMPARE(PendingGroupLeaves::parseStore(stored), (QList<QByteArray>{chatA, chatB}));
}

void TestAirGroupLeave::aStoredLineThatHoldsNoChatIdIsSkipped()
{
    const QString a = QString(QStringLiteral("0a")).repeated(32);
    const QString b = QString(QStringLiteral("0B")).repeated(32);
    const QStringList lines{QString(),
                            a,
                            QStringLiteral("  ") + b + QStringLiteral(" "),
                            b.left(62),
                            b.left(63),
                            QString(QStringLiteral("0G")).repeated(32),
                            b + QStringLiteral("0B"),
                            a};
    QCOMPARE(PendingGroupLeaves::parseStore(lines.join(QLatin1Char('\n'))),
             (QList<QByteArray>{chatA, chatB}));
}

QTEST_GUILESS_MAIN(TestAirGroupLeave)
#include "airgroupleave_test.moc"
