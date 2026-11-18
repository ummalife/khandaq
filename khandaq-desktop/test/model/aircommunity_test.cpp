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
#include "src/model/air/airmessagemarks.h"

#include <QDateTime>
#include <QTemporaryDir>
#include <QTextBoundaryFinder>
#include <QUrlQuery>
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
    void theCommunityIsAlwaysNamedKhandaqCommunity();
    void itJoinsOnlyWhenConnectedNeverJoinedNeverLeftAndNotInIt();
    void anAutoJoinKeepsTheSoundAndIsDone();
    void theMuteOfAnEarlierJoinIsLiftedOnce();
    void theSoundOfAGroupLeftIsLeftAsItIs();
    void aGroupHereByAnyWayIsNeverJoinedByItself();
    void aJoinToxcoreRefusedLeavesNothingAndIsTriedAgain();
    void nothingIsJoinedWhileTheAnswerIsNo();
    void aGroupJoinedByHandKeepsItsSound();
    void theJoinLastsAcrossARestart();
    void leftItIsNeverJoinedAgainAcrossARestart();
    void leftAfterJoiningByHandItIsNeverJoinedEither();
    void leavingAnotherGroupChangesNothing();
    void leavingForgetsTheMuteButNotTheMarks();
    void aStoreOfBeforeHasNeverJoinedIt();
    void aReportIsAMailToSupportWithTheMessage();
    void aReportNamesNoAuthorNobodyVouchesFor();
    void aRelayedFileIsKnownSoInALaterLaunch();
    void theNewestRelayedFilesOfEachGroupAreKept();
    void everyPartOfTheReportIsEncoded();
    void aLongMessageIsCutAt500CharactersNeverInHalf();
    void aReportMailStaysWithinWhatWindowsTakes();
    void filesOfOthersInTheCommunityWaitForATap();
    void heldFilesAreTakenByTheNumberOfTheirEntry();
    void aFileStaysHeldUntilItIsWritten();
    void theOldestHeldFilesMakeRoomForNewOnes();
    void aGroupThatGoesTakesItsHeldFiles();
    void aFileIdComesOnceInARun();
    void namesPlayNoPartInWhatAcceptWrites();
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

void TestAirCommunity::theCommunityIsAlwaysNamedKhandaqCommunity()
{
    // The topic of the founder that iOS and Android showed for its name, the name of a saved
    // group before its state comes, the name in its state, nothing at all: always the same name.
    const QString topic = QStringLiteral(
        "Official Khandaq community \u00B7 Be kind, no spam or ads \u00B7 "
        "\u041E\u0444\u0438\u0446\u0438\u0430\u043B\u044C\u043D\u043E\u0435");
    for (const QString& title : {topic, QStringLiteral("Group CFF1995F"),
                                 QStringLiteral("Khandaq Community"), QString()}) {
        QCOMPARE(Community::groupTitle(Community::chatId(), title),
                 QStringLiteral("Khandaq Community"));
    }
    QCOMPARE(QString::fromLatin1(Community::name), QStringLiteral("Khandaq Community"));
    // Any other group shows what it has, as before, even nothing.
    QCOMPARE(Community::groupTitle(otherGroup, topic), topic);
    QCOMPARE(Community::groupTitle(otherGroup, QStringLiteral("Family")),
             QStringLiteral("Family"));
    QCOMPARE(Community::groupTitle(otherGroup, QString()), QString());
    QCOMPARE(Community::groupTitle(QByteArray(), QStringLiteral("Family")),
             QStringLiteral("Family"));
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

void TestAirCommunity::anAutoJoinKeepsTheSoundAndIsDone()
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
    // 0.2.60: the group is heard as any other (the owner, 2026-11-18).
    QVERIFY(!store.isMuted(communityKey));
    QVERIFY(!store.isMuted(communityKey.toLower()));

    // Once is all: the next connection does not join again.
    QVERIFY(!Community::autoJoin(store, true, false, [&joins] {
        ++joins;
        return true;
    }));
    QCOMPARE(joins, 1);
}

void TestAirCommunity::aGroupHereByAnyWayIsNeverJoinedByItself()
{
    // Joined by hand: once its rejoin fails and its row goes without a leave, the app does not join
    // it by itself, muted, over what the person did.
    LocalStore store;
    QVERIFY(Community::noteGroupHere(store, Community::chatId()));
    QVERIFY(store.communityAutoJoinDone());
    QVERIFY(!Community::noteGroupHere(store, Community::chatId()));
    int joins = 0;
    QVERIFY(!Community::autoJoin(store, true, false, [&joins] {
        ++joins;
        return true;
    }));
    QCOMPARE(joins, 0);
    QVERIFY(!store.isMuted(communityKey));
    QVERIFY(!store.communityLeft());
    // It stays so across a restart.
    LocalStore next;
    next.load(store.save(), now);
    QVERIFY(next.communityAutoJoinDone());

    // Any other group changes nothing.
    LocalStore other;
    QVERIFY(!Community::noteGroupHere(other, otherGroup));
    QVERIFY(!other.communityAutoJoinDone());

    // The join by itself: the group comes into the list during the join, and it is marked done all
    // the same.
    LocalStore fresh;
    QVERIFY(Community::autoJoin(fresh, true, false, [&fresh] {
        Community::noteGroupHere(fresh, Community::chatId());
        return true;
    }));
    QVERIFY(fresh.communityAutoJoinDone());
    QVERIFY(!fresh.isMuted(communityKey));
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
    QVERIFY(!next.isMuted(communityKey));
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
    // Muted by the person after the join by itself and the lift of 0.2.60, it stays so: nothing
    // unmutes it again.
    LocalStore joined;
    QVERIFY(Community::autoJoin(joined, true, false, [] { return true; }));
    QVERIFY(!Community::restoreSound(joined));
    QVERIFY(joined.setMuted(true, communityKey));
    QVERIFY(!Community::autoJoin(joined, true, true, [] { return true; }));
    LocalStore later = restarted(joined);
    QVERIFY(!Community::restoreSound(later));
    QVERIFY(later.isMuted(communityKey));
}

void TestAirCommunity::theMuteOfAnEarlierJoinIsLiftedOnce()
{
    // A profile the join of 0.2.57-0.2.59 muted, as its store keeps it.
    LocalStore store;
    QVERIFY(Community::autoJoin(store, true, false, [] { return true; }));
    QVERIFY(store.setMuted(true, communityKey));
    LocalStore updated = restarted(store);
    QVERIFY(!updated.communitySoundRestored());

    QVERIFY(Community::restoreSound(updated));
    QVERIFY(!updated.isMuted(communityKey));
    QVERIFY(updated.communitySoundRestored());

    // Once: muted again by the person, across a restart, it stays muted.
    QVERIFY(updated.setMuted(true, communityKey));
    LocalStore next = restarted(updated);
    QVERIFY(next.communitySoundRestored());
    QVERIFY(!Community::restoreSound(next));
    QVERIFY(next.isMuted(communityKey));
    // Another group's mute is never touched.
    LocalStore other;
    QVERIFY(other.setMuted(true, QString::fromLatin1(otherGroup.toHex())));
    QVERIFY(!Community::restoreSound(other));
    QVERIFY(other.isMuted(QString::fromLatin1(otherGroup.toHex())));
    QVERIFY(other.communitySoundRestored());
}

void TestAirCommunity::theSoundOfAGroupLeftIsLeftAsItIs()
{
    LocalStore store;
    QVERIFY(Community::autoJoin(store, true, false, [] { return true; }));
    QVERIFY(store.setMuted(true, communityKey));
    QVERIFY(Community::noteGroupLeft(store, Community::chatId()));
    QVERIFY(!Community::restoreSound(store));
    QVERIFY(store.isMuted(communityKey));
    QVERIFY(store.communitySoundRestored());
}

void TestAirCommunity::theJoinLastsAcrossARestart()
{
    LocalStore store;
    QVERIFY(Community::autoJoin(store, true, false, [] { return true; }));
    LocalStore next = restarted(store);
    QVERIFY(next.communityAutoJoinDone());
    QVERIFY(!next.communityLeft());
    QVERIFY(!next.isMuted(communityKey));
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

namespace {
GroupReport sampleReport()
{
    GroupReport report;
    report.groupName = QStringLiteral("Khandaq Community");
    report.authorKey = QStringLiteral("0a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f9");
    // 15:04:05 in Istanbul is 12:04:05 UTC.
    report.time = QDateTime(QDate(2026, 11, 16), QTime(15, 4, 5), Qt::OffsetFromUTC, 3 * 3600);
    report.text = QStringLiteral("Buy followers now");
    return report;
}

QString decodedPart(const QUrl& url, const QString& key)
{
    return QUrlQuery(url).queryItemValue(key, QUrl::FullyDecoded);
}
} // namespace

void TestAirCommunity::aReportIsAMailToSupportWithTheMessage()
{
    const QUrl mail = reportMailUrl(sampleReport());
    QVERIFY(mail.isValid());
    QCOMPARE(mail.scheme(), QStringLiteral("mailto"));
    QCOMPARE(mail.path(), QStringLiteral("support@khandaq.org"));
    QCOMPARE(decodedPart(mail, QStringLiteral("subject")),
             QStringLiteral("Report: Khandaq Community"));
    QCOMPARE(decodedPart(mail, QStringLiteral("body")),
             QStringLiteral("Group: Khandaq Community\r\n"
                            "Author: 0A1B2C3D\r\n"
                            "Time: 2026-11-16T12:04:05Z\r\n"
                            "Message:\r\n"
                            "Buy followers now"));
    // Exactly as a mail app gets it.
    QCOMPARE(mail.toEncoded(),
             QByteArray("mailto:support@khandaq.org?subject=Report%3A%20Khandaq%20Community"
                        "&body=Group%3A%20Khandaq%20Community%0D%0AAuthor%3A%200A1B2C3D%0D%0A"
                        "Time%3A%202026-11-16T12%3A04%3A05Z%0D%0AMessage%3A%0D%0A"
                        "Buy%20followers%20now"));
}

void TestAirCommunity::aReportNamesNoAuthorNobodyVouchesFor()
{
    // A file a member relayed names whom that member says, and an entry may have no key at all:
    // the mail goes all the same, by nobody's name.
    GroupReport report = sampleReport();
    report.authorKey.clear();
    QCOMPARE(decodedPart(reportMailUrl(report), QStringLiteral("body")),
             QStringLiteral("Group: Khandaq Community\r\n"
                            "Author: unknown\r\n"
                            "Time: 2026-11-16T12:04:05Z\r\n"
                            "Message:\r\n"
                            "Buy followers now"));
}

namespace {
/** A file id of the history: 32 bytes, its number at the end. */
QByteArray relayedId(int number)
{
    QByteArray id(32, '\x7e');
    for (int i = 0; i < 4; ++i) {
        id[31 - i] = static_cast<char>((number >> (8 * i)) & 0xff);
    }
    return id;
}
} // namespace

void TestAirCommunity::aRelayedFileIsKnownSoInALaterLaunch()
{
    // GroupChatForm asks by the key ChatHistory gives the entry: the group's key and the file's id
    // in its packets, which the history keeps (file_restart_id).
    const QString relayed = fileEntryKey(communityKey, relayedId(1));
    const QString live = fileEntryKey(communityKey, relayedId(2));
    LocalStore store;
    QVERIFY(!store.isRelayed(relayed));
    QVERIFY(store.markRelayed(relayed));
    QVERIFY(!store.markRelayed(relayed));
    QVERIFY(!store.markRelayed(QStringLiteral("not an entry key")));
    QVERIFY(store.isRelayed(relayed));
    QVERIFY(!store.isRelayed(live));

    // The next launch reads the same file, into a store of its own.
    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    QFile file(dir.filePath(QStringLiteral("store.json")));
    QVERIFY(file.open(QIODevice::WriteOnly));
    QCOMPARE(file.write(store.save()), static_cast<qint64>(store.save().size()));
    file.close();
    QVERIFY(file.open(QIODevice::ReadOnly));
    LocalStore next;
    next.load(file.readAll(), now);
    QVERIFY(next.isRelayed(relayed));
    QVERIFY(!next.isRelayed(live));
    // The mark is the group's: the same id in another group is another file.
    QVERIFY(!next.isRelayed(fileEntryKey(QString::fromLatin1(otherGroup.toHex()), relayedId(1))));

    // A store of before has none.
    LocalStore old;
    old.load(QByteArray(R"({"version":1,"contacts":{}})"), now);
    QVERIFY(!old.isRelayed(relayed));
}

void TestAirCommunity::theNewestRelayedFilesOfEachGroupAreKept()
{
    const QString other = QString::fromLatin1(otherGroup.toHex());
    LocalStore store;
    QVERIFY(store.markRelayed(fileEntryKey(other, relayedId(0))));
    for (int i = 0; i <= LocalStore::relayedLimit; ++i) {
        QVERIFY(store.markRelayed(fileEntryKey(communityKey, relayedId(i))));
    }
    // One past the bound: the oldest of the group went, the rest and the other group's stay.
    QVERIFY(!store.isRelayed(fileEntryKey(communityKey, relayedId(0))));
    QVERIFY(store.isRelayed(fileEntryKey(communityKey, relayedId(1))));
    QVERIFY(store.isRelayed(fileEntryKey(communityKey, relayedId(LocalStore::relayedLimit))));
    QVERIFY(store.isRelayed(fileEntryKey(other, relayedId(0))));

    // So after a launch, in the same order: the next one past the bound takes the oldest again.
    LocalStore next;
    next.load(store.save(), now);
    QVERIFY(!next.isRelayed(fileEntryKey(communityKey, relayedId(0))));
    QVERIFY(next.isRelayed(fileEntryKey(communityKey, relayedId(1))));
    QVERIFY(next.markRelayed(fileEntryKey(communityKey, relayedId(LocalStore::relayedLimit + 1))));
    QVERIFY(!next.isRelayed(fileEntryKey(communityKey, relayedId(1))));
    QVERIFY(next.isRelayed(fileEntryKey(communityKey, relayedId(2))));
    QVERIFY(next.isRelayed(fileEntryKey(other, relayedId(0))));

    // A group left takes its marks along; an erased history takes all.
    QVERIFY(next.forgetGroup(communityKey));
    QVERIFY(!next.isRelayed(fileEntryKey(communityKey, relayedId(2))));
    QVERIFY(next.isRelayed(fileEntryKey(other, relayedId(0))));
    QVERIFY(next.forgetEntries());
    QVERIFY(!next.isRelayed(fileEntryKey(other, relayedId(0))));
    QVERIFY(!next.forgetEntries());
}

void TestAirCommunity::everyPartOfTheReportIsEncoded()
{
    GroupReport report = sampleReport();
    // What would end the subject, start another field or the fragment, or read as a space.
    report.groupName = QStringLiteral("A&B=C #1\n+ Группа");
    report.text = QStringLiteral("50% off & more=free?\nline two\r\nline three #tag +1 مرحبا 你好 \U0001F600");
    const QUrl mail = reportMailUrl(report);
    const QByteArray encoded = mail.toEncoded();
    QCOMPARE(encoded.count('&'), 1);
    QCOMPARE(encoded.count('?'), 1);
    QVERIFY(!encoded.contains('#'));
    QVERIFY(!encoded.contains('+'));
    QVERIFY(!encoded.contains(' '));
    QVERIFY(!encoded.contains('\n'));
    QVERIFY(!encoded.contains('\r'));
    QVERIFY(encoded.contains("%26"));
    QVERIFY(encoded.contains("%2B"));
    QVERIFY(encoded.contains("%23"));
    QVERIFY(encoded.contains("%25"));
    // The group's name stays one line, in the subject and in the body.
    QCOMPARE(decodedPart(mail, QStringLiteral("subject")),
             QStringLiteral("Report: A&B=C #1 + Группа"));
    const QString body = decodedPart(mail, QStringLiteral("body"));
    QVERIFY(body.startsWith(QStringLiteral("Group: A&B=C #1 + Группа\r\n")));
    QVERIFY(body.endsWith(QStringLiteral("Message:\r\n50% off & more=free?\r\nline two\r\n"
                                         "line three #tag +1 مرحبا 你好 \U0001F600")));
    // An empty report is still a mail to support.
    const QUrl empty = reportMailUrl(GroupReport{});
    QCOMPARE(empty.path(), QStringLiteral("support@khandaq.org"));
    QCOMPARE(decodedPart(empty, QStringLiteral("body")),
             QStringLiteral("Group: \r\nAuthor: unknown\r\nTime: \r\nMessage:\r\n"));
}

void TestAirCommunity::aLongMessageIsCutAt500CharactersNeverInHalf()
{
    const QString bodyStart = QStringLiteral("Message:\r\n");
    const auto textOf = [&bodyStart](const QUrl& mail) {
        const QString body = decodedPart(mail, QStringLiteral("body"));
        return body.mid(body.indexOf(bodyStart) + bodyStart.size());
    };
    GroupReport report = sampleReport();
    report.text = QString(600, QLatin1Char('x'));
    QCOMPARE(textOf(reportMailUrl(report)), QString(500, QLatin1Char('x')));
    report.text = QString(500, QLatin1Char('y'));
    QCOMPARE(textOf(reportMailUrl(report)), report.text);

    // A character outside the first plane is two units of UTF-16 and counts once.
    const QString smile = QStringLiteral("\U0001F600");
    report.text = QString(499, QLatin1Char('z')) + smile + smile;
    QCOMPARE(textOf(reportMailUrl(report)), QString(499, QLatin1Char('z')) + smile);
    // 500 of them would be some 6000 characters encoded, past reportUrlLimit: fewer go, and none
    // is cut in half.
    report.text.clear();
    for (int i = 0; i < 501; ++i) {
        report.text += smile;
    }
    const QString cut = textOf(reportMailUrl(report));
    QVERIFY(!cut.isEmpty());
    QVERIFY(cut.size() < 1000);
    QCOMPARE(cut.size() % 2, 0);
    QVERIFY(cut.at(cut.size() - 1).isLowSurrogate());
    QCOMPARE(cut, report.text.left(cut.size()));

    // As a person counts, and iOS: a flag is two regional indicators and one character...
    const QString turkey = QStringLiteral("\U0001F1F9\U0001F1F7");
    report.text = QString(499, QLatin1Char('a')) + turkey + turkey;
    QCOMPARE(textOf(reportMailUrl(report)), QString(499, QLatin1Char('a')) + turkey);
    // ...a family joined of three emoji is one...
    const QString family = QStringLiteral("\U0001F468\u200D\U0001F469\u200D\U0001F467");
    report.text = QString(499, QLatin1Char('b')) + family + QStringLiteral("b");
    QCOMPARE(textOf(reportMailUrl(report)), QString(499, QLatin1Char('b')) + family);
    // ...and so is a letter with its accent, which stays on the last letter kept.
    report.text = QString(500, QLatin1Char('e')) + QStringLiteral("\u0301x");
    QCOMPARE(textOf(reportMailUrl(report)),
             QString(499, QLatin1Char('e')) + QStringLiteral("e\u0301"));
}

void TestAirCommunity::aReportMailStaysWithinWhatWindowsTakes()
{
    GroupReport report = sampleReport();
    const QString head = QStringLiteral("Group: Khandaq Community\r\n"
                                        "Author: 0A1B2C3D\r\n"
                                        "Time: 2026-11-16T12:04:05Z\r\n"
                                        "Message:\r\n");
    // A short text in Cyrillic, six characters a letter once encoded, goes whole.
    report.text = QStringLiteral("\u041A\u0443\u043F\u0438\u0442\u0435 \u043F\u043E\u0434\u043F"
                                 "\u0438\u0441\u0447\u0438\u043A\u043E\u0432");
    QCOMPARE(decodedPart(reportMailUrl(report), QStringLiteral("body")), head + report.text);

    // A long one would be thousands: the text gives way from its end, by whole characters. A
    // letter keeps its stress mark, and a flag its two halves.
    const QString words =
        QStringLiteral("\u041F\u0440\u0438\u0432\u0435\u0301\u0442 \U0001F1F9\U0001F1F7 ");
    report.text.clear();
    for (int i = 0; i < 80; ++i) {
        report.text += words;
    }
    const QUrl mail = reportMailUrl(report);
    const int length = mail.toEncoded().size();
    QVERIFY(length <= reportUrlLimit);
    // And no shorter than it must be: the next character, a flag at most, 24 encoded, did not fit.
    QVERIFY(length > reportUrlLimit - 24);
    QCOMPARE(decodedPart(mail, QStringLiteral("subject")),
             QStringLiteral("Report: Khandaq Community"));
    const QString body = decodedPart(mail, QStringLiteral("body"));
    QVERIFY(body.startsWith(head));
    const QString cut = body.mid(head.size());
    QVERIFY(!cut.isEmpty());
    QVERIFY(report.text.startsWith(cut));
    QTextBoundaryFinder characters(QTextBoundaryFinder::Grapheme, report.text);
    characters.setPosition(cut.size());
    QVERIFY(characters.isAtBoundary());

    // The group, the author and the time are never cut: a name that fills the mail alone leaves
    // the text out, and the mail goes as it is.
    report.groupName = QString(400, QChar(0x0416));
    const QUrl crowded = reportMailUrl(report);
    QVERIFY(crowded.toEncoded().size() > reportUrlLimit);
    QCOMPARE(decodedPart(crowded, QStringLiteral("body")),
             QStringLiteral("Group: ") + report.groupName
                 + QStringLiteral("\r\nAuthor: 0A1B2C3D\r\nTime: 2026-11-16T12:04:05Z\r\n"
                                  "Message:\r\n"));
}

namespace {
Community::HeldFiles::File heldFile(uint32_t number, const QByteArray& group, qint64 size,
                                    const QString& name = QStringLiteral("photo.jpg"),
                                    char fill = 'f')
{
    ToxFile entry(number, 0, name, QString(), static_cast<uint64_t>(size), ToxFile::RECEIVING);
    // The id in its packets: one per message.
    entry.resumeFileId = QByteArray(28, '\0') + QByteArray::number(number).rightJustified(4, '0');
    return {group, ToxPk(QByteArray(32, '\x0c')), entry, QByteArray(static_cast<int>(size), fill)};
}

QVector<uint32_t> numbersOf(const QVector<Community::HeldFiles::File>& files)
{
    QVector<uint32_t> numbers;
    for (const Community::HeldFiles::File& file : files) {
        numbers.append(file.entry.fileNum);
    }
    return numbers;
}

constexpr qint64 megabyte = 1024 * 1024;
} // namespace

void TestAirCommunity::filesOfOthersInTheCommunityWaitForATap()
{
    // Whatever the download setting says: it is not asked.
    QVERIFY(!Community::savesFileAtOnce(Community::chatId(), false, false));
    QVERIFY(!Community::savesFileAtOnce(Community::chatId(), false, true));
    // Our own files, and the files of every other group, are written as they come.
    QVERIFY(Community::savesFileAtOnce(Community::chatId(), true, false));
    QVERIFY(Community::savesFileAtOnce(otherGroup, false, false));
    QVERIFY(Community::savesFileAtOnce(otherGroup, true, false));
    QVERIFY(Community::savesFileAtOnce(otherGroup, false, true));
    QVERIFY(Community::savesFileAtOnce(QByteArray(), false, false));
    // A relayed file that names our key is no file of ours: anyone can name it.
    QVERIFY(!Community::savesFileAtOnce(Community::chatId(), true, true));
}

void TestAirCommunity::heldFilesAreTakenByTheNumberOfTheirEntry()
{
    Community::HeldFiles held;
    QVERIFY(held.hold(heldFile(700001, Community::chatId(), 3)).isEmpty());
    QVERIFY(held.hold(heldFile(700002, Community::chatId(), 5)).isEmpty());
    QCOMPARE(held.count(), 2);
    QCOMPARE(held.bytes(), qint64{8});

    Community::HeldFiles::File file;
    QVERIFY(held.take(700002, file));
    QCOMPARE(file.entry.fileNum, uint32_t{700002});
    QCOMPARE(file.data, QByteArray(5, 'f'));
    QCOMPARE(file.groupChatId, Community::chatId());
    QCOMPARE(file.sender, ToxPk(QByteArray(32, '\x0c')));
    QCOMPARE(held.bytes(), qint64{3});
    // Taken once: a second Accept finds nothing, nor does a number never held.
    QVERIFY(!held.take(700002, file));
    QVERIFY(!held.take(123, file));
    QCOMPARE(held.count(), 1);
}

void TestAirCommunity::aFileStaysHeldUntilItIsWritten()
{
    // Accept writes a file found by its number, and takes it out only once it is written: one the
    // disk did not take waits for Accept again, bytes and all.
    Community::HeldFiles held;
    held.hold(heldFile(700001, Community::chatId(), 3, QStringLiteral("a.jpg"), 'a'));
    held.hold(heldFile(700002, Community::chatId(), 5, QStringLiteral("b.jpg"), 'b'));
    Community::HeldFiles::File file;
    QVERIFY(held.find(700002, file));
    QCOMPARE(file.data, QByteArray(5, 'b'));
    QCOMPARE(file.entry.fileName, QStringLiteral("b.jpg"));
    QVERIFY(held.find(700002, file));
    QCOMPARE(held.count(), 2);
    QCOMPARE(held.bytes(), qint64{8});
    QVERIFY(!held.find(123, file));
    // Written: it goes, and the other stays.
    QVERIFY(held.take(700002, file));
    QVERIFY(!held.find(700002, file));
    QVERIFY(held.find(700001, file));
    QCOMPARE(file.data, QByteArray(3, 'a'));
}

void TestAirCommunity::theOldestHeldFilesMakeRoomForNewOnes()
{
    Community::HeldFiles held;
    const qint64 limit = Community::HeldFiles::limitBytes;
    // The largest file a group carries fits alone.
    QVERIFY(limit >= 200 * megabyte);
    QVERIFY(held.hold(heldFile(1, Community::chatId(), 100 * megabyte)).isEmpty());
    QVERIFY(held.hold(heldFile(2, Community::chatId(), 100 * megabyte)).isEmpty());
    QVERIFY(held.hold(heldFile(3, Community::chatId(), 50 * megabyte)).isEmpty());
    QCOMPARE(held.bytes(), 250 * megabyte);
    // 100 more do not fit: the oldest goes, and only as many as needed.
    QCOMPARE(numbersOf(held.hold(heldFile(4, Community::chatId(), 100 * megabyte))),
             (QVector<uint32_t>{1}));
    QCOMPARE(held.bytes(), 250 * megabyte);
    QCOMPARE(numbersOf(held.hold(heldFile(5, Community::chatId(), 200 * megabyte))),
             (QVector<uint32_t>{2, 3, 4}));
    QCOMPARE(held.count(), 1);
    QVERIFY(held.bytes() <= limit);
    // Bigger than all the room there is: not held, and the others stay.
    QCOMPARE(numbersOf(held.hold(heldFile(6, Community::chatId(), limit + 1))),
             (QVector<uint32_t>{6}));
    QCOMPARE(held.count(), 1);
    Community::HeldFiles::File file;
    QVERIFY(held.take(5, file));
    QVERIFY(!held.take(1, file));
    QCOMPARE(held.bytes(), qint64{0});
}

void TestAirCommunity::aGroupThatGoesTakesItsHeldFiles()
{
    Community::HeldFiles held;
    held.hold(heldFile(1, Community::chatId(), 4));
    held.hold(heldFile(2, otherGroup, 6));
    held.hold(heldFile(3, Community::chatId(), 8));
    held.forgetGroup(Community::chatId());
    QCOMPARE(held.count(), 1);
    QCOMPARE(held.bytes(), qint64{6});
    Community::HeldFiles::File file;
    QVERIFY(!held.take(1, file));
    QVERIFY(held.take(2, file));
}

void TestAirCommunity::aFileIdComesOnceInARun()
{
    Community::HeldFiles held;
    const Community::HeldFiles::File first = heldFile(1, Community::chatId(), 4);
    const QByteArray id = first.entry.resumeFileId;
    QVERIFY(!held.known(Community::chatId(), id));
    held.hold(first);
    QVERIFY(held.known(Community::chatId(), id));
    // Held, written or gone to make room: the id stays known, so a file again under it, by history
    // sync or from someone else, is dropped before it takes over the first entry.
    Community::HeldFiles::File file;
    QVERIFY(held.take(1, file));
    QVERIFY(held.known(Community::chatId(), id));
    // Gone to make room, though no other place remembers it any more (the core keeps the last 512
    // ids): a file again under its id gets no entry, so none takes the place of the one that went.
    const Community::HeldFiles::File third = heldFile(3, Community::chatId(), 4);
    held.hold(third);
    const Community::HeldFiles::File large =
        heldFile(2, Community::chatId(), Community::HeldFiles::limitBytes);
    QCOMPARE(numbersOf(held.hold(large)), (QVector<uint32_t>{3}));
    QVERIFY(held.known(Community::chatId(), id));
    QVERIFY(held.known(Community::chatId(), third.entry.resumeFileId));
    QVERIFY(!held.take(3, file));
    // Only in its group, and never for a file without an id.
    QVERIFY(!held.known(otherGroup, id));
    QVERIFY(!held.known(Community::chatId(), QByteArray()));
    // Left, and joined again: its files come again from the start.
    held.forgetGroup(Community::chatId());
    QVERIFY(!held.known(Community::chatId(), id));
    QCOMPARE(held.count(), 0);
}

void TestAirCommunity::namesPlayNoPartInWhatAcceptWrites()
{
    // Two files under one name, a name with a dot first, and one with nothing but dots: each
    // entry gets its own bytes, and each counts toward the limit.
    Community::HeldFiles held;
    held.hold(heldFile(1, Community::chatId(), 10 * megabyte, QStringLiteral("a.jpg"), '1'));
    held.hold(heldFile(2, Community::chatId(), 10 * megabyte, QStringLiteral("a.jpg"), '2'));
    held.hold(heldFile(3, Community::chatId(), 10 * megabyte, QStringLiteral(".hidden"), '3'));
    held.hold(heldFile(4, Community::chatId(), 10 * megabyte, QStringLiteral(".."), '4'));
    QCOMPARE(held.count(), 4);
    QCOMPARE(held.bytes(), 40 * megabyte);
    for (const uint32_t number : {uint32_t{2}, uint32_t{1}, uint32_t{4}, uint32_t{3}}) {
        Community::HeldFiles::File file;
        QVERIFY(held.take(number, file));
        QCOMPARE(file.entry.fileNum, number);
        QCOMPARE(file.data, QByteArray(10 * megabyte, static_cast<char>('0' + number)));
    }
    // Dots in front take no way around the limit.
    for (uint32_t number = 10; number < 40; ++number) {
        held.hold(heldFile(number, Community::chatId(), 10 * megabyte, QStringLiteral(".x")));
    }
    QVERIFY(held.bytes() <= Community::HeldFiles::limitBytes);
    QCOMPARE(held.count(), static_cast<int>(Community::HeldFiles::limitBytes / (10 * megabyte)));
}

QTEST_GUILESS_MAIN(TestAirCommunity)
#include "aircommunity_test.moc"
