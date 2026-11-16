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
    void aReportIsAMailToSupportWithTheMessage();
    void everyPartOfTheReportIsEncoded();
    void aLongMessageIsCutAt500CharactersNeverInHalf();
    void filesOfOthersInTheCommunityWaitForATap();
    void heldFilesAreTakenByTheNumberOfTheirEntry();
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
             QStringLiteral("Group: \r\nAuthor: \r\nTime: \r\nMessage:\r\n"));
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
    report.text.clear();
    for (int i = 0; i < 501; ++i) {
        report.text += smile;
    }
    const QString cut = textOf(reportMailUrl(report));
    QCOMPARE(cut.size(), 1000);
    QVERIFY(cut.at(cut.size() - 1).isLowSurrogate());
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
    QVERIFY(!Community::savesFileAtOnce(Community::chatId(), false));
    // Our own files, and the files of every other group, are written as they come.
    QVERIFY(Community::savesFileAtOnce(Community::chatId(), true));
    QVERIFY(Community::savesFileAtOnce(otherGroup, false));
    QVERIFY(Community::savesFileAtOnce(otherGroup, true));
    QVERIFY(Community::savesFileAtOnce(QByteArray(), false));
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
    held.hold(heldFile(2, Community::chatId(), Community::HeldFiles::limitBytes));
    QVERIFY(held.known(Community::chatId(), id));
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
