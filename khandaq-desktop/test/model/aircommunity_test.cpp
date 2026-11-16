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

#include "src/model/air/aircommunity.h"
#include "src/model/air/airlocalstore.h"

#include <QDateTime>
#include <QtTest/QtTest>

using namespace Air;

namespace {
const QDateTime now = QDateTime::fromSecsSinceEpoch(1800000000);
const QString communityKey = QString::fromLatin1(Community::chatIdHex);
const QByteArray otherGroup(32, '\x0b');

/** The store as the next start reads it: what the profile's settings keep. */
LocalStore restarted(const LocalStore& store)
{
    LocalStore next;
    next.load(store.save(), now);
    return next;
}
} // namespace

/** KHANDAQ (0.2.57): Khandaq Community, joined once by itself, muted, and never after it was left. */
class TestAirCommunity : public QObject
{
    Q_OBJECT
private slots:
    void theChatIdIsTheGroupOfOurBot();
    void itJoinsOnlyWhenConnectedNeverJoinedNeverLeftAndNotInIt();
    void anAutoJoinMutesTheGroupAndIsDone();
    void aJoinToxcoreRefusedLeavesNothingAndIsTriedAgain();
    void nothingIsJoinedWhileTheAnswerIsNo();
    void aGroupJoinedByHandKeepsItsSound();
    void theJoinAndTheMuteLastAcrossARestart();
    void leftItIsNeverJoinedAgainAcrossARestart();
    void leftAfterJoiningByHandItIsNeverJoinedEither();
    void leavingAnotherGroupChangesNothing();
    void leavingForgetsTheMuteButNotTheMarks();
    void aStoreOfBeforeHasNeverJoinedIt();
};

void TestAirCommunity::theChatIdIsTheGroupOfOurBot()
{
    // The live group, whose founder runs on khandaq.org: SPEC-community, "Finding the group".
    QCOMPARE(QByteArray(Community::chatIdHex),
             QByteArray("CFF1995F235303B88C4EF1CE903DCEDC77CEAC82DD1323DAB2CACD2EF840646F"));
    QCOMPARE(Community::chatId().size(), 32);
    QCOMPARE(Community::chatId().toHex().toUpper(), QByteArray(Community::chatIdHex));
    QVERIFY(Community::isCommunity(Community::chatId()));
    QVERIFY(!Community::isCommunity(otherGroup));
    QVERIFY(!Community::isCommunity(QByteArray()));
    // Never the TRIfA public group of before, whose founder is not ours.
    QVERIFY(!Community::isCommunity(QByteArray::fromHex(
        "154b3973bd0e66304fd6179a8a54759073649e09e6e368f0334fc6ed666ab762")));
}

void TestAirCommunity::itJoinsOnlyWhenConnectedNeverJoinedNeverLeftAndNotInIt()
{
    for (int bits = 0; bits < 16; ++bits) {
        const bool connected = bits & 1;
        const bool autoJoinDone = bits & 2;
        const bool left = bits & 4;
        const bool alreadyInGroup = bits & 8;
        const bool expected = connected && !autoJoinDone && !left && !alreadyInGroup;
        QVERIFY2(Community::shouldAutoJoin(connected, autoJoinDone, left, alreadyInGroup)
                     == expected,
                 qPrintable(QString::number(bits)));
    }
    QVERIFY(Community::shouldAutoJoin(true, false, false, false));
}

void TestAirCommunity::anAutoJoinMutesTheGroupAndIsDone()
{
    LocalStore store;
    QVERIFY(!store.communityAutoJoinDone());
    QVERIFY(!store.isMuted(communityKey));
    int joins = 0;
    QVERIFY(Community::autoJoin(store, true, false, [&joins] {
        ++joins;
        return true;
    }));
    QCOMPARE(joins, 1);
    QVERIFY(store.communityAutoJoinDone());
    QVERIFY(!store.communityLeft());
    // The switch of the dossier keys a group's mute by its chat id, in whatever case.
    QVERIFY(store.isMuted(communityKey));
    QVERIFY(store.isMuted(communityKey.toLower()));
    QVERIFY(!store.isMuted(QString::fromLatin1(otherGroup.toHex())));

    // Once is all: the next connection does not join again.
    QVERIFY(!Community::autoJoin(store, true, false, [&joins] {
        ++joins;
        return true;
    }));
    QCOMPARE(joins, 1);
}

void TestAirCommunity::aJoinToxcoreRefusedLeavesNothingAndIsTriedAgain()
{
    LocalStore store;
    int joins = 0;
    QVERIFY(!Community::autoJoin(store, true, false, [&joins] {
        ++joins;
        return false;
    }));
    QCOMPARE(joins, 1);
    QVERIFY(!store.communityAutoJoinDone());
    QVERIFY(!store.isMuted(communityKey));

    // The next start, or the next connection, tries again.
    LocalStore next = restarted(store);
    QVERIFY(Community::autoJoin(next, true, false, [&joins] {
        ++joins;
        return true;
    }));
    QCOMPARE(joins, 2);
    QVERIFY(next.communityAutoJoinDone());
    QVERIFY(next.isMuted(communityKey));
}

void TestAirCommunity::nothingIsJoinedWhileTheAnswerIsNo()
{
    int joins = 0;
    const auto join = [&joins] {
        ++joins;
        return true;
    };
    LocalStore offline;
    QVERIFY(!Community::autoJoin(offline, false, false, join));
    QVERIFY(!offline.communityAutoJoinDone());

    LocalStore inIt;
    QVERIFY(!Community::autoJoin(inIt, true, true, join));
    QVERIFY(!inIt.communityAutoJoinDone());

    LocalStore left;
    QVERIFY(left.setCommunityLeft());
    QVERIFY(!Community::autoJoin(left, true, false, join));

    QCOMPARE(joins, 0);
    QVERIFY(!offline.isMuted(communityKey));
    QVERIFY(!inIt.isMuted(communityKey));
    QVERIFY(!left.isMuted(communityKey));
}

void TestAirCommunity::aGroupJoinedByHandKeepsItsSound()
{
    // Joined by hand before this version, or by its link: the app does not join it again, and its
    // sound is the person's own.
    LocalStore store;
    QVERIFY(!Community::autoJoin(store, true, true, [] { return true; }));
    QVERIFY(!store.isMuted(communityKey));
    QVERIFY(!store.communityAutoJoinDone());
    // Unmuted by the person after the join by itself, it stays so: nothing mutes it again.
    LocalStore joined;
    QVERIFY(Community::autoJoin(joined, true, false, [] { return true; }));
    QVERIFY(joined.setMuted(false, communityKey));
    QVERIFY(!Community::autoJoin(joined, true, true, [] { return true; }));
    QVERIFY(!restarted(joined).isMuted(communityKey));
}

void TestAirCommunity::theJoinAndTheMuteLastAcrossARestart()
{
    LocalStore store;
    QVERIFY(Community::autoJoin(store, true, false, [] { return true; }));
    LocalStore next = restarted(store);
    QVERIFY(next.communityAutoJoinDone());
    QVERIFY(!next.communityLeft());
    QVERIFY(next.isMuted(communityKey));
    // A group that synced after the start is in the list at the next one; one that had not is
    // joined again by the core (rejoinStoredGroups): neither is a second join by itself.
    int joins = 0;
    QVERIFY(!Community::autoJoin(next, true, false, [&joins] {
        ++joins;
        return true;
    }));
    QCOMPARE(joins, 0);
}

void TestAirCommunity::leftItIsNeverJoinedAgainAcrossARestart()
{
    LocalStore store;
    QVERIFY(Community::autoJoin(store, true, false, [] { return true; }));
    QVERIFY(Community::noteGroupLeft(store, Community::chatId()));
    QVERIFY(store.communityLeft());
    // Leaving twice is still left.
    QVERIFY(!Community::noteGroupLeft(store, Community::chatId()));

    LocalStore next = restarted(store);
    QVERIFY(next.communityLeft());
    int joins = 0;
    for (const bool alreadyInGroup : {false, true}) {
        QVERIFY(!Community::autoJoin(next, true, alreadyInGroup, [&joins] {
            ++joins;
            return true;
        }));
    }
    QCOMPARE(joins, 0);
    // Restart after restart.
    QVERIFY(restarted(restarted(next)).communityLeft());
}

void TestAirCommunity::leftAfterJoiningByHandItIsNeverJoinedEither()
{
    LocalStore store;
    QVERIFY(Community::noteGroupLeft(store, Community::chatId()));
    QVERIFY(!store.communityAutoJoinDone());
    LocalStore next = restarted(store);
    QVERIFY(next.communityLeft());
    QVERIFY(!Community::autoJoin(next, true, false, [] { return true; }));
    QVERIFY(!next.communityAutoJoinDone());
}

void TestAirCommunity::leavingAnotherGroupChangesNothing()
{
    LocalStore store;
    QVERIFY(Community::autoJoin(store, true, false, [] { return true; }));
    QVERIFY(!Community::noteGroupLeft(store, otherGroup));
    QVERIFY(!Community::noteGroupLeft(store, QByteArray()));
    QVERIFY(!store.communityLeft());
    QVERIFY(!restarted(store).communityLeft());
}

void TestAirCommunity::leavingForgetsTheMuteButNotTheMarks()
{
    // Widget::discardGroup: the group's mute and private messages go (forgetGroup), and the leave
    // is marked.
    LocalStore store;
    QVERIFY(Community::autoJoin(store, true, false, [] { return true; }));
    store.forgetGroup(communityKey);
    QVERIFY(Community::noteGroupLeft(store, Community::chatId()));
    QVERIFY(!store.isMuted(communityKey));
    LocalStore next = restarted(store);
    QVERIFY(next.communityAutoJoinDone());
    QVERIFY(next.communityLeft());
    // Neither do the history being erased nor a removed contact take the marks.
    QVERIFY(!next.forgetEntries());
    next.forget(communityKey);
    QVERIFY(next.communityLeft());
}

void TestAirCommunity::aStoreOfBeforeHasNeverJoinedIt()
{
    // A profile of 0.2.56 or older: its store has no marks, so the update joins once.
    LocalStore old;
    old.load(QByteArray(R"({"version":1,"contacts":{}})"), now);
    QVERIFY(!old.communityAutoJoinDone());
    QVERIFY(!old.communityLeft());
    QVERIFY(Community::shouldAutoJoin(true, old.communityAutoJoinDone(), old.communityLeft(), false));
    // A new profile, with nothing stored at all.
    LocalStore fresh;
    fresh.load(QByteArray(), now);
    QVERIFY(!fresh.communityAutoJoinDone());
    // A store without marks writes none: a profile that never met the group stays as it was.
    QVERIFY(!fresh.save().contains("community"));
    // A store that is not one reads as nothing joined, and a later load forgets earlier marks.
    QVERIFY(fresh.setCommunityLeft());
    fresh.load(QByteArray("not json"), now);
    QVERIFY(!fresh.communityLeft());
}

QTEST_GUILESS_MAIN(TestAirCommunity)
#include "aircommunity_test.moc"
