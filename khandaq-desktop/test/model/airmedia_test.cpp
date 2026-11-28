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

#include "src/model/air/airmedia.h"
#include "src/widget/air/airstrings.h"

#include <QBuffer>
#include <QFile>
#include <QFileInfo>
#include <QTemporaryDir>
#include <QtTest/QtTest>

#include <limits>

using Air::FileAction;
using Air::FileNote;

namespace {

QByteArray big32(quint32 value)
{
    QByteArray bytes(4, '\0');
    for (int i = 0; i < 4; ++i) {
        bytes[i] = static_cast<char>((value >> (24 - 8 * i)) & 0xff);
    }
    return bytes;
}

QByteArray big64(quint64 value)
{
    return big32(static_cast<quint32>(value >> 32)) + big32(static_cast<quint32>(value));
}

QByteArray box(const char* type, const QByteArray& body)
{
    return big32(static_cast<quint32>(8 + body.size())) + QByteArray(type, 4) + body;
}

/** An mvhd box of version 0: times, then the scale and the duration in 32 bits. */
QByteArray movieHeader(quint32 scale, quint32 duration)
{
    QByteArray body = big32(0) + big32(0) + big32(0) + big32(scale) + big32(duration);
    body += QByteArray(80, '\0');
    return box("mvhd", body);
}

/** An mvhd box of version 1, its times and duration in 64 bits. */
QByteArray wideMovieHeader(quint32 scale, quint64 duration)
{
    QByteArray body = big32(0x01000000) + big64(0) + big64(0) + big32(scale) + big64(duration);
    body += QByteArray(80, '\0');
    return box("mvhd", body);
}

qint64 durationOf(const QByteArray& file)
{
    QByteArray data = file;
    QBuffer buffer(&data);
    buffer.open(QIODevice::ReadOnly);
    return Air::mp4DurationMs(buffer);
}

Air::FileFacts facts(ToxFile::FileStatus status, bool incoming)
{
    Air::FileFacts result;
    result.status = status;
    result.incoming = incoming;
    result.size = 1000;
    return result;
}

SystemMessage callRow(SystemMessageType type, const SystemMessage::Args& args)
{
    SystemMessage message;
    message.messageType = type;
    message.args = args;
    return message;
}

} // namespace

class TestAirMedia : public QObject
{
    Q_OBJECT
private slots:
    void aVoiceNoteIsKnownByItsWholeSuffixInAnyCase();
    void aFileIsKnownByItsKindFromItsName();
    void aFileTakenInWithoutAskingGetsAFreeName();
    void aNameFromTheSenderStaysInItsFolder();
    void aFileThatComesIsWrittenToItsPartUntilWhole();
    void aFileThatStartsAgainGetsAFreePartBeside();
    void moreFilesJoinTheListOnce();
    void theirFileWaitsForAcceptOrDecline();
    void ourFileWaitsForTheOtherSide();
    void aMovingFileSaysWhatIsLeft();
    void onlyTheSideThatPausedGoesOn();
    void aStoppedFileSaysWhichWayItWent();
    void aFileItsSenderCancelledSaysSo();
    void aFileThatIsHereOpens();
    void aGroupFileHasNothingToAnswer();
    void aHeldGroupFileWaitsForAcceptAlone();
    void ourGroupFileWaitsForSomeoneToSendItTo();
    void theMarkOfOurFileFollowsTheTransfer();
    void aFileWhoseFriendWentOfflineWaitsAndNeverFails();
    void anOfferWhoseSenderWentOfflineIsNoNewOffer();
    void anOfferOfAFilePartlyHereGoesOnFromWhatIsHere();
    void anOfferOfAFileAllHereEndsQuietly();
    void anOfferOfAnotherFileIsANewEntry();
    void anOfferOfAFileNeverAcceptedWaitsInItsEntry();
    void anOfferOfAFileThePersonRefusedWaitsForTheirTap();
    void anOfferGoesOnOnlyInThePartItLeft();
    void aPartIsToldFromAnotherFileByItsStamp();
    void aFileOfOursGoesAgainUnderItsIdOnlyWhileItIsTheSame();
    void theLengthOfANoteComesFromItsMovieHeader();
    void aBrokenHeaderHasNoLength();
    void lengthsReadAsClocks();
    void callRowsReadAsOneEntryEach();
    void newCallRowsReadBackAsWritten();
};

void TestAirMedia::aVoiceNoteIsKnownByItsWholeSuffixInAnyCase()
{
    QVERIFY(Air::isVoiceNote(QStringLiteral("voice_3F9A2C71.file.m4a")));
    QVERIFY(Air::isVoiceNote(QStringLiteral("VOICE_3F9A2C71.FILE.M4A")));
    QVERIFY(!Air::isVoiceNote(QStringLiteral(".file.m4a")));
    QVERIFY(!Air::isVoiceNote(QStringLiteral("voice.m4a")));
    QVERIFY(!Air::isVoiceNote(QStringLiteral("voice.file.m4a.exe")));
    QVERIFY(!Air::isVoiceNote(QString()));
}

/**
 * KHANDAQ (0.2.64): a notification says a voice note, a video or a photo by its kind, as the push
 * previews of the phones do (docs/PUSH_PREVIEWS.md), by the extension in any case; any other name
 * is a file.
 */
void TestAirMedia::aFileIsKnownByItsKindFromItsName()
{
    using Air::FileKind;
    QCOMPARE(Air::fileKindOf(QStringLiteral("voice_3F9A2C71.file.m4a")), FileKind::Voice);
    for (const char* video : {"clip.mp4", "clip.mov", "clip.m4v", "clip.webm", "clip.mkv",
                              "clip.3gp", "clip.avi", "CLIP.MP4", "my.trip.Mov"}) {
        QCOMPARE(Air::fileKindOf(QString::fromLatin1(video)), FileKind::Video);
    }
    for (const char* photo : {"IMG_1.jpg", "IMG_1.jpeg", "IMG_1.png", "IMG_1.gif", "IMG_1.heic",
                              "IMG_1.heif", "IMG_1.webp", "IMG_1.JPG", "scan.HEIC"}) {
        QCOMPARE(Air::fileKindOf(QString::fromLatin1(photo)), FileKind::Photo);
    }
    // A song in m4a is no voice note, and a name that is only its extension is no photo.
    for (const char* file : {"song.m4a", "plan.pdf", "archive.mp4.zip", "photo.jpg.exe", "mp4",
                             ".jpg", "video.", "", ".file.m4a"}) {
        QCOMPARE(Air::fileKindOf(QString::fromLatin1(file)), FileKind::File);
    }
    QCOMPARE(Air::fileKindOf(QStringLiteral("Видео.mp4")), FileKind::Video);
    QCOMPARE(Air::fileKindOf(QStringLiteral("Фото.jpeg")), FileKind::Photo);
}

/**
 * QA live 2026-11-14: Accept and Listen take a voice note into the app's folder without asking
 * where; a note of the same name already there keeps its file.
 */
void TestAirMedia::aFileTakenInWithoutAskingGetsAFreeName()
{
    QStringList there;
    const auto taken = [&there](const QString& path) { return there.contains(path); };
    const QString folder = QStringLiteral("/data/voice");

    QCOMPARE(Air::freeFilePath(folder, QStringLiteral("voice_3F9A.file.m4a"), taken),
             QStringLiteral("/data/voice/voice_3F9A.file.m4a"));
    there << QStringLiteral("/data/voice/voice_3F9A.file.m4a");
    // The number goes before the first dot, so the name still ends in ".file.m4a".
    const QString second = Air::freeFilePath(folder, QStringLiteral("voice_3F9A.file.m4a"), taken);
    QCOMPARE(second, QStringLiteral("/data/voice/voice_3F9A (1).file.m4a"));
    QVERIFY(Air::isVoiceNote(second));
    there << second;
    QCOMPARE(Air::freeFilePath(folder, QStringLiteral("voice_3F9A.file.m4a"), taken),
             QStringLiteral("/data/voice/voice_3F9A (2).file.m4a"));
    // A name without a dot takes the number at its end; no name has no path.
    there << QStringLiteral("/data/voice/note");
    QCOMPARE(Air::freeFilePath(folder, QStringLiteral("note"), taken),
             QStringLiteral("/data/voice/note (1)"));
    QCOMPARE(Air::freeFilePath(folder, QString(), taken), QString());
    // Without a way to ask, the name is taken as it is.
    QCOMPARE(Air::freeFilePath(folder, QStringLiteral("note"), nullptr), QStringLiteral("/data/voice/note"));
}

/**
 * KHANDAQ (0.2.57): the name of a file is the sender's, cleaned on Windows only: a name that
 * names a path still puts the file into the folder, under its last part.
 */
void TestAirMedia::aNameFromTheSenderStaysInItsFolder()
{
    const auto taken = [](const QString&) { return false; };
    const QString folder = QStringLiteral("/data/voice");
    QCOMPARE(Air::freeFilePath(folder, QStringLiteral("../../.ssh/keys.file.m4a"), taken),
             QStringLiteral("/data/voice/keys.file.m4a"));
    QCOMPARE(Air::freeFilePath(folder, QStringLiteral("/etc/hosts"), taken),
             QStringLiteral("/data/voice/hosts"));
    QCOMPARE(Air::freeFilePath(folder, QStringLiteral("dir/"), taken), QString());
}

/**
 * KHANDAQ (0.2.57): a file of the person is written beside the name it goes to, under its part,
 * and takes that name once whole; nothing in the folder has the name of a file not there.
 */
void TestAirMedia::aFileThatComesIsWrittenToItsPartUntilWhole()
{
    const QString path = QStringLiteral("/home/me/Downloads/report.pdf");
    const QString part = Air::partFilePath(path);
    QCOMPARE(part, path + QLatin1String(Air::partFileSuffix));
    QVERIFY(part != path);
    QVERIFY(Air::isPartPath(part));
    QVERIFY(!Air::isPartPath(path));
    QCOMPARE(Air::wholeFilePath(part), path);
    // A file of an older build, written under its own name, is whole where it is.
    QCOMPARE(Air::wholeFilePath(path), path);
    // A file whose own name ends as a part does comes back to that name.
    const QString named = QStringLiteral("/tmp/x") + QLatin1String(Air::partFileSuffix);
    QCOMPARE(Air::wholeFilePath(Air::partFilePath(named)), named);
    QCOMPARE(Air::partFilePath(QString()), QString());
    QVERIFY(!Air::isPartPath(QString()));
    QVERIFY(!Air::isPartPath(QLatin1String(Air::partFileSuffix)));
}

/**
 * KHANDAQ (0.2.57): a file that starts again beside the one it left gets the first name where
 * neither a file nor its part is, and is written to that part.
 */
void TestAirMedia::aFileThatStartsAgainGetsAFreePartBeside()
{
    QStringList there;
    const auto taken = [&there](const QString& path) { return there.contains(path); };
    const QString folder = QStringLiteral("/home/me/Downloads");
    const QString path = folder + QStringLiteral("/lecture.mkv");
    there << Air::partFilePath(path);
    QCOMPARE(Air::freePartPath(Air::partFilePath(path), taken),
             Air::partFilePath(folder + QStringLiteral("/lecture (1).mkv")));
    // A file there under the next name counts as much as a part of it.
    there << folder + QStringLiteral("/lecture (1).mkv");
    QCOMPARE(Air::freePartPath(Air::partFilePath(path), taken),
             Air::partFilePath(folder + QStringLiteral("/lecture (2).mkv")));
    // From a file of an older build, written under its own name, the same.
    QCOMPARE(Air::freePartPath(path, taken),
             Air::partFilePath(folder + QStringLiteral("/lecture (2).mkv")));
    QCOMPARE(Air::freePartPath(QString(), taken), QString());
}

/**
 * KHANDAQ (beta feedback 2026-11-22): Add more in the dialog before sending: the files listed stay
 * in their order and those chosen follow them, each file once, however its path is written.
 */
void TestAirMedia::moreFilesJoinTheListOnce()
{
    const QString photo = QStringLiteral("/home/maryam/Pictures/photo.jpg");
    const QString notes = QStringLiteral("/home/maryam/Documents/notes.pdf");
    const QString plan = QStringLiteral("/home/maryam/Documents/plan.txt");
    const QStringList listed{photo, notes};

    QCOMPARE(Air::withMoreFiles(listed, {}), listed);
    QCOMPARE(Air::withMoreFiles(listed, {plan}), (QStringList{photo, notes, plan}));
    // Chosen again, also as another way of writing it, or twice in one choice: listed once.
    const QString notesAgain = QStringLiteral("/home/maryam/Documents/./../Documents/notes.pdf");
    QCOMPARE(Air::withMoreFiles(listed, {notesAgain, plan, plan, QString(), photo}),
             (QStringList{photo, notes, plan}));
    QCOMPARE(Air::withMoreFiles({}, {plan, notes}), (QStringList{plan, notes}));
}

void TestAirMedia::theirFileWaitsForAcceptOrDecline()
{
    const Air::FileEntryState state = Air::fileEntryState(facts(ToxFile::INITIALIZING, true));
    QCOMPARE(state.note, FileNote::None);
    QCOMPARE(state.actions, (QVector<FileAction>{FileAction::Accept, FileAction::Decline}));
    QVERIFY(state.progress < 0);
    QVERIFY(!state.isProblem());
}

void TestAirMedia::ourFileWaitsForTheOtherSide()
{
    const Air::FileEntryState state = Air::fileEntryState(facts(ToxFile::INITIALIZING, false));
    QCOMPARE(state.note, FileNote::WaitsAccept);
    QCOMPARE(state.actions, QVector<FileAction>{FileAction::Cancel});
}

void TestAirMedia::aMovingFileSaysWhatIsLeft()
{
    Air::FileFacts moving = facts(ToxFile::TRANSMITTING, true);
    moving.progress = 0.25;
    Air::FileEntryState state = Air::fileEntryState(moving);
    QCOMPARE(state.note, FileNote::Left);
    QCOMPARE(state.bytesLeft, qint64{750});
    QCOMPARE(state.progress, 0.25);
    QCOMPARE(state.actions, (QVector<FileAction>{FileAction::Pause, FileAction::Cancel}));

    // A share that is not a number does not reach the bar.
    moving.progress = std::numeric_limits<double>::quiet_NaN();
    state = Air::fileEntryState(moving);
    QCOMPARE(state.progress, 0.0);
    QCOMPARE(state.bytesLeft, qint64{1000});
    moving.progress = 2.0;
    QCOMPARE(Air::fileEntryState(moving).progress, 1.0);
}

void TestAirMedia::onlyTheSideThatPausedGoesOn()
{
    Air::FileFacts paused = facts(ToxFile::PAUSED, true);
    paused.pausedHere = true;
    Air::FileEntryState state = Air::fileEntryState(paused);
    QCOMPARE(state.note, FileNote::Paused);
    QCOMPARE(state.actions, (QVector<FileAction>{FileAction::Resume, FileAction::Cancel}));

    paused.pausedHere = false;
    paused.pausedThere = true;
    state = Air::fileEntryState(paused);
    QCOMPARE(state.note, FileNote::PausedThere);
    QCOMPARE(state.actions, QVector<FileAction>{FileAction::Cancel});
}

void TestAirMedia::aStoppedFileSaysWhichWayItWent()
{
    for (const ToxFile::FileStatus status : {ToxFile::BROKEN, ToxFile::CANCELED}) {
        Air::FileEntryState theirs = Air::fileEntryState(facts(status, true));
        QCOMPARE(theirs.note, FileNote::DownloadStopped);
        // Tox cannot ask for it again: only they can send it once more.
        QVERIFY(theirs.actions.isEmpty());
        QVERIFY(theirs.isProblem());

        Air::FileFacts ours = facts(status, false);
        QCOMPARE(Air::fileEntryState(ours).note, FileNote::NotSent);
        QVERIFY(Air::fileEntryState(ours).actions.isEmpty());
        ours.canSendAgain = true;
        QCOMPARE(Air::fileEntryState(ours).actions, QVector<FileAction>{FileAction::SendAgain});
        QVERIFY(Air::fileEntryState(ours).isProblem());
    }
}

void TestAirMedia::aFileItsSenderCancelledSaysSo()
{
    // KHANDAQ (inbox #36): testers read "The download stopped" and could not tell why.
    Air::FileFacts theirs = facts(ToxFile::CANCELED, true);
    theirs.cancelledThere = true;
    const Air::FileEntryState state = Air::fileEntryState(theirs);
    QCOMPARE(state.note, FileNote::CancelledThere);
    // Nothing went wrong here, and nothing here brings it back: only they can send it again.
    QVERIFY(!state.isProblem());
    QVERIFY(state.actions.isEmpty());
    QVERIFY(state.progress < 0);
    QCOMPARE(AirStrings::fileNoteText(state, QString(), QLocale::c()),
             QStringLiteral("The sender cancelled it"));

    // A transfer that broke was cancelled by nobody, and a file of ours says how it went for us.
    theirs.status = ToxFile::BROKEN;
    QCOMPARE(Air::fileEntryState(theirs).note, FileNote::DownloadStopped);
    Air::FileFacts ours = facts(ToxFile::CANCELED, false);
    ours.cancelledThere = true;
    QCOMPARE(Air::fileEntryState(ours).note, FileNote::NotSent);
}

void TestAirMedia::aFileThatIsHereOpens()
{
    Air::FileFacts done = facts(ToxFile::FINISHED, true);
    QVERIFY(Air::fileEntryState(done).actions.isEmpty());
    done.here = true;
    QCOMPARE(Air::fileEntryState(done).actions,
             (QVector<FileAction>{FileAction::Open, FileAction::ShowInFolder}));
    QCOMPARE(Air::fileEntryState(done).note, FileNote::None);
    // A voice note plays from its own row instead.
    done.voiceNote = true;
    QVERIFY(Air::fileEntryState(done).actions.isEmpty());
}

void TestAirMedia::aGroupFileHasNothingToAnswer()
{
    for (const ToxFile::FileStatus status :
         {ToxFile::INITIALIZING, ToxFile::TRANSMITTING, ToxFile::PAUSED}) {
        for (const bool incoming : {true, false}) {
            Air::FileFacts groupFile = facts(status, incoming);
            groupFile.group = true;
            groupFile.pausedHere = true;
            QVERIFY(Air::fileEntryState(groupFile).actions.isEmpty());
        }
    }
    Air::FileFacts ours = facts(ToxFile::INITIALIZING, false);
    ours.group = true;
    QCOMPARE(Air::fileEntryState(ours).note, FileNote::None);
}

void TestAirMedia::aHeldGroupFileWaitsForAcceptAlone()
{
    // KHANDAQ (0.2.57): a file of Khandaq Community came and is held: Accept writes it, and
    // there is no sender to decline it to. Nothing tells of a transfer.
    Air::FileFacts held = facts(ToxFile::INITIALIZING, true);
    held.group = true;
    held.held = true;
    const Air::FileEntryState state = Air::fileEntryState(held);
    QCOMPARE(state.actions, (QVector<FileAction>{FileAction::Accept}));
    QCOMPARE(state.note, FileNote::None);
    QVERIFY(state.progress < 0);
    QVERIFY(!state.isProblem());
    // Once written it is a file like any other here; one that went to make room stopped.
    held.status = ToxFile::FINISHED;
    held.here = true;
    QCOMPARE(Air::fileEntryState(held).actions,
             (QVector<FileAction>{FileAction::Open, FileAction::ShowInFolder}));
    held.status = ToxFile::BROKEN;
    held.here = false;
    QCOMPARE(Air::fileEntryState(held).note, FileNote::DownloadStopped);
    QVERIFY(Air::fileEntryState(held).actions.isEmpty());
}

void TestAirMedia::ourGroupFileWaitsForSomeoneToSendItTo()
{
    // Nobody pauses a group file: ours stops while nobody else in the group is online.
    Air::FileFacts ours = facts(ToxFile::PAUSED, false);
    ours.group = true;
    QCOMPARE(Air::fileEntryState(ours).note, FileNote::WaitsMembers);
    QVERIFY(Air::fileEntryState(ours).actions.isEmpty());
    QVERIFY(!Air::fileEntryState(ours).isProblem());
    // A paused file of a person is still the one its side paused.
    ours.group = false;
    QCOMPARE(Air::fileEntryState(ours).note, FileNote::PausedThere);
}

void TestAirMedia::theMarkOfOurFileFollowsTheTransfer()
{
    QCOMPARE(Air::fileStatus(ToxFile::FINISHED, false), Air::EntryStatus::Delivered);
    QCOMPARE(Air::fileStatus(ToxFile::INITIALIZING, true), Air::EntryStatus::Sending);
    QCOMPARE(Air::fileStatus(ToxFile::INITIALIZING, false), Air::EntryStatus::Transit);
    QCOMPARE(Air::fileStatus(ToxFile::TRANSMITTING, true), Air::EntryStatus::Sending);
    QCOMPARE(Air::fileStatus(ToxFile::PAUSED, true), Air::EntryStatus::Sending);
    QCOMPARE(Air::fileStatus(ToxFile::BROKEN, true), Air::EntryStatus::Failed);
    QCOMPARE(Air::fileStatus(ToxFile::CANCELED, true), Air::EntryStatus::Failed);
}

/**
 * @brief KHANDAQ (0.2.57): either way, a transfer whose friend went offline keeps the bar where it
 * was and says it waits for the connection: never failed, and Cancel still works.
 */
void TestAirMedia::aFileWhoseFriendWentOfflineWaitsAndNeverFails()
{
    for (const bool incoming : {true, false}) {
        Air::FileFacts waiting = facts(ToxFile::TRANSMITTING, incoming);
        waiting.progress = 0.4;
        waiting.waitsConnection = true;
        const Air::FileEntryState state = Air::fileEntryState(waiting);
        QCOMPARE(state.note, FileNote::WaitsConnection);
        QCOMPARE(state.progress, 0.4);
        QCOMPARE(state.actions, QVector<FileAction>{FileAction::Cancel});
        QVERIFY(!state.isProblem());
    }
    // One that was not accepted yet has no bar, and cannot be accepted until they are back.
    Air::FileFacts offer = facts(ToxFile::INITIALIZING, true);
    offer.waitsConnection = true;
    QCOMPARE(Air::fileEntryState(offer).note, FileNote::WaitsConnection);
    QVERIFY(Air::fileEntryState(offer).progress < 0);
    QCOMPARE(Air::fileEntryState(offer).actions, QVector<FileAction>{FileAction::Cancel});
    // A group file is out of it: it has its own way.
    Air::FileFacts groupFile = facts(ToxFile::PAUSED, false);
    groupFile.group = true;
    groupFile.waitsConnection = true;
    QCOMPARE(Air::fileEntryState(groupFile).note, FileNote::WaitsMembers);

    ToxFile ours(1, 0, QStringLiteral("video.mp4"), QString(), 1000, ToxFile::SENDING);
    ours.status = ToxFile::TRANSMITTING;
    ours.connectionLost = true;
    QCOMPARE(Air::fileStatus(ours, false), Air::EntryStatus::Transit);
    ours.connectionLost = false;
    QCOMPARE(Air::fileStatus(ours, true), Air::EntryStatus::Sending);
}

/**
 * @brief KHANDAQ (0.2.57): an offer the window answers is theirs and not yet accepted; the same
 * offer once its sender went offline only waits in its entry: no alert, no download.
 */
void TestAirMedia::anOfferWhoseSenderWentOfflineIsNoNewOffer()
{
    ToxFile offer(65536, 0, QStringLiteral("video.mp4"), QString(), 1000, ToxFile::RECEIVING);
    QVERIFY(Air::isOffer(offer));
    offer.connectionLost = true;
    QVERIFY(!Air::isOffer(offer));
    // Offered again once they are back, it is one: it waits in its entry or comes by itself.
    offer.connectionLost = false;
    offer.continued = true;
    QVERIFY(Air::isOffer(offer));

    ToxFile accepted = offer;
    accepted.status = ToxFile::TRANSMITTING;
    QVERIFY(!Air::isOffer(accepted));
    ToxFile ours(0, 0, QStringLiteral("video.mp4"), QString(), 1000, ToxFile::SENDING);
    QVERIFY(!Air::isOffer(ours));
}

namespace {
Air::FileHere fileHere(ToxFile::FileStatus status, bool accepted, qint64 bytes)
{
    Air::FileHere here;
    here.id = QByteArray(32, '\x07');
    here.size = 1000;
    here.status = status;
    here.accepted = accepted;
    here.bytes = bytes;
    here.ownPart = accepted;
    return here;
}
} // namespace

/** @brief KHANDAQ (0.2.57): the same id and size, n bytes here: seek to n, in the same entry. */
void TestAirMedia::anOfferOfAFilePartlyHereGoesOnFromWhatIsHere()
{
    const QByteArray id(32, '\x07');
    Air::FileResume resume = Air::fileResume(id, 1000, fileHere(ToxFile::BROKEN, true, 400));
    QCOMPARE(resume.step, Air::FileResume::Step::GoOn);
    QCOMPARE(resume.offset, qint64{400});
    // Nothing here, or the data is gone: from the start, in the same entry.
    resume = Air::fileResume(id, 1000, fileHere(ToxFile::BROKEN, true, 0));
    QCOMPARE(resume.step, Air::FileResume::Step::GoOn);
    QCOMPARE(resume.offset, qint64{0});
    // More than its size is not this file: it starts again, beside it.
    resume = Air::fileResume(id, 1000, fileHere(ToxFile::BROKEN, true, 1200));
    QCOMPARE(resume.step, Air::FileResume::Step::GoOn);
    QCOMPARE(resume.offset, qint64{0});
    QVERIFY(resume.newPath);
    // All of it came but not its end: the last byte once more ends it as usual.
    resume = Air::fileResume(id, 1000, fileHere(ToxFile::BROKEN, true, 1000));
    QCOMPARE(resume.step, Air::FileResume::Step::GoOn);
    QCOMPARE(resume.offset, qint64{999});
}

/** @brief KHANDAQ (0.2.57): a file all here: seek to size - 1, no new card, nothing shown. */
void TestAirMedia::anOfferOfAFileAllHereEndsQuietly()
{
    const QByteArray id(32, '\x07');
    Air::FileResume resume = Air::fileResume(id, 1000, fileHere(ToxFile::FINISHED, true, 1000));
    QCOMPARE(resume.step, Air::FileResume::Step::EndQuietly);
    QCOMPARE(resume.offset, qint64{999});
    // Whatever is at its path now: what was done stays done.
    resume = Air::fileResume(id, 1000, fileHere(ToxFile::FINISHED, true, 0));
    QCOMPARE(resume.step, Air::FileResume::Step::EndQuietly);
    Air::FileHere empty = fileHere(ToxFile::FINISHED, true, 0);
    empty.size = 0;
    resume = Air::fileResume(id, 0, empty);
    QCOMPARE(resume.step, Air::FileResume::Step::EndQuietly);
    QCOMPARE(resume.offset, qint64{0});
}

/** @brief KHANDAQ (0.2.57): another id, or another size, is another file: a new entry. */
void TestAirMedia::anOfferOfAnotherFileIsANewEntry()
{
    const Air::FileHere here = fileHere(ToxFile::BROKEN, true, 400);
    QCOMPARE(Air::fileResume(QByteArray(32, '\x08'), 1000, here).step,
             Air::FileResume::Step::NewEntry);
    QCOMPARE(Air::fileResume(here.id, 1001, here).step, Air::FileResume::Step::NewEntry);
    QCOMPARE(Air::fileResume(QByteArray(), 1000, here).step, Air::FileResume::Step::NewEntry);
    Air::FileHere noId = here;
    noId.id.clear();
    QCOMPARE(Air::fileResume(QByteArray(), 1000, noId).step, Air::FileResume::Step::NewEntry);
    QCOMPARE(Air::fileResume(QByteArray(32, '\x07'), 1000, noId).step,
             Air::FileResume::Step::NewEntry);
}

/**
 * @brief KHANDAQ (0.2.57): one never accepted, or cancelled by its sender, waits in its entry for
 * Accept or for the downloads rule, as a new offer does.
 */
void TestAirMedia::anOfferOfAFileNeverAcceptedWaitsInItsEntry()
{
    const QByteArray id(32, '\x07');
    for (const ToxFile::FileStatus status : {ToxFile::BROKEN, ToxFile::CANCELED}) {
        const Air::FileResume resume = Air::fileResume(id, 1000, fileHere(status, false, 0));
        QCOMPARE(resume.step, Air::FileResume::Step::WaitInEntry);
        QCOMPARE(resume.offset, qint64{0});
    }
}

/**
 * @brief KHANDAQ (0.2.57): one the person cancelled or declined waits in its entry for their tap,
 * from the start, whatever came of it before; never by itself, nor by the downloads rule.
 */
void TestAirMedia::anOfferOfAFileThePersonRefusedWaitsForTheirTap()
{
    const QByteArray id(32, '\x07');
    for (const ToxFile::FileStatus status : {ToxFile::CANCELED, ToxFile::BROKEN}) {
        for (const bool accepted : {false, true}) {
            Air::FileHere here = fileHere(status, accepted, accepted ? 400 : 0);
            here.refused = true;
            const Air::FileResume resume = Air::fileResume(id, 1000, here);
            QCOMPARE(resume.step, Air::FileResume::Step::AskAgain);
            QCOMPARE(resume.offset, qint64{0});
        }
    }
    // Another file is a new offer all the same, and one all here still ends quietly.
    Air::FileHere refused = fileHere(ToxFile::CANCELED, false, 0);
    refused.refused = true;
    QCOMPARE(Air::fileResume(QByteArray(32, '\x08'), 1000, refused).step,
             Air::FileResume::Step::NewEntry);
    QCOMPARE(Air::fileResume(id, 1001, refused).step, Air::FileResume::Step::NewEntry);
    Air::FileHere whole = fileHere(ToxFile::FINISHED, true, 1000);
    whole.refused = true;
    QCOMPARE(Air::fileResume(id, 1000, whole).step, Air::FileResume::Step::EndQuietly);

    // The downloads rule takes a new offer, or one that waits as a new offer does, and no other.
    QVERIFY(!Air::downloadsRuleApplies(Air::FileResume::Step::AskAgain));
    QVERIFY(Air::downloadsRuleApplies(Air::FileResume::Step::NewEntry));
    QVERIFY(Air::downloadsRuleApplies(Air::FileResume::Step::WaitInEntry));
    QVERIFY(!Air::downloadsRuleApplies(Air::FileResume::Step::GoOn));
    QVERIFY(!Air::downloadsRuleApplies(Air::FileResume::Step::EndQuietly));
}

/**
 * @brief KHANDAQ (0.2.57): an accepted file goes on in its path only when what is there is the part
 * it left; otherwise from the start under a new name, and that file is not touched.
 */
void TestAirMedia::anOfferGoesOnOnlyInThePartItLeft()
{
    const QByteArray id(32, '\x07');
    Air::FileHere own = fileHere(ToxFile::BROKEN, true, 400);
    Air::FileResume resume = Air::fileResume(id, 1000, own);
    QCOMPARE(resume.step, Air::FileResume::Step::GoOn);
    QCOMPARE(resume.offset, qint64{400});
    QVERIFY(!resume.newPath);

    for (const qint64 bytes : {qint64{0}, qint64{400}, qint64{1000}}) {
        Air::FileHere other = fileHere(ToxFile::BROKEN, true, bytes);
        other.ownPart = false;
        resume = Air::fileResume(id, 1000, other);
        QCOMPARE(resume.step, Air::FileResume::Step::GoOn);
        QCOMPARE(resume.offset, qint64{0});
        QVERIFY(resume.newPath);
    }
}

/**
 * @brief KHANDAQ (0.2.57): the part a file left is the file made for it, as it was when the
 * transfer was cut where that is known; with nothing to tell it by, a file at that path is not its
 * part.
 */
void TestAirMedia::aPartIsToldFromAnotherFileByItsStamp()
{
    ToxFile::Stamp recorded;
    recorded.bornMs = 1000;
    recorded.bytes = 400;
    recorded.modifiedMs = 2000;
    ToxFile::Stamp now = recorded;
    QVERIFY(Air::isOwnPart(recorded, now));
    // Another file made there, or the same one written since the cut.
    now.bornMs = 1001;
    QVERIFY(!Air::isOwnPart(recorded, now));
    now = recorded;
    now.bytes = 401;
    QVERIFY(!Air::isOwnPart(recorded, now));
    now = recorded;
    now.modifiedMs = 2001;
    QVERIFY(!Air::isOwnPart(recorded, now));
    // No file there.
    QVERIFY(!Air::isOwnPart(recorded, ToxFile::Stamp()));

    // Not cut (the app stopped in the middle of it): when it was made tells it.
    ToxFile::Stamp made;
    made.bornMs = 1000;
    QVERIFY(Air::isOwnPart(made, now));
    now.bornMs = -1;
    QVERIFY(!Air::isOwnPart(made, now));
    // Where the system tells no birth time, only a cut tells it.
    ToxFile::Stamp unborn = recorded;
    unborn.bornMs = -1;
    now = unborn;
    QVERIFY(Air::isOwnPart(unborn, now));
    ToxFile::Stamp nothing;
    QVERIFY(!Air::isOwnPart(nothing, now));

    QTemporaryDir dir;
    QVERIFY(dir.isValid());
    const QString path = dir.filePath(QStringLiteral("part.bin"));
    QFile file(path);
    QVERIFY(file.open(QIODevice::WriteOnly));
    QCOMPARE(file.write(QByteArray(400, '\x01')), qint64{400});
    file.close();
    const ToxFile::Stamp stamp = Air::fileStampAt(path);
    QCOMPARE(stamp.bytes, qint64{400});
    QCOMPARE(stamp.modifiedMs, QFileInfo(path).lastModified().toMSecsSinceEpoch());
    QVERIFY(stamp.bornMs == -1 || stamp.bornMs > 0);
    QVERIFY(Air::isOwnPart(stamp, Air::fileStampAt(path)));
    QCOMPARE(Air::fileStampAt(dir.filePath(QStringLiteral("none.bin"))), ToxFile::Stamp());
    QCOMPARE(Air::fileStampAt(dir.path()), ToxFile::Stamp());
}

/**
 * @brief KHANDAQ (0.2.57): a file of ours goes again under its Tox file id only while it is the
 * file that went: the length of the offer and the same last change, and made when it was where
 * that is known.
 */
void TestAirMedia::aFileOfOursGoesAgainUnderItsIdOnlyWhileItIsTheSame()
{
    ToxFile::Stamp sent;
    sent.bornMs = 1000;
    sent.bytes = 400;
    sent.modifiedMs = 2000;
    QVERIFY(Air::isSameSource(sent, 400, sent));
    QVERIFY(!Air::isSameSource(sent, 500, sent));
    ToxFile::Stamp now = sent;
    now.bytes = 401;
    QVERIFY(!Air::isSameSource(sent, 400, now));
    now = sent;
    now.modifiedMs = 2001;
    QVERIFY(!Air::isSameSource(sent, 400, now));
    now = sent;
    now.bornMs = 1001;
    QVERIFY(!Air::isSameSource(sent, 400, now));
    QVERIFY(!Air::isSameSource(sent, 400, ToxFile::Stamp()));

    // Made when nobody could tell: its length and last change tell it.
    ToxFile::Stamp unborn = sent;
    unborn.bornMs = -1;
    QVERIFY(Air::isSameSource(unborn, 400, sent));
    // Its last change when it went is not known (a row of before): a new file.
    ToxFile::Stamp unknown = sent;
    unknown.modifiedMs = -1;
    QVERIFY(!Air::isSameSource(unknown, 400, sent));
}

void TestAirMedia::theLengthOfANoteComesFromItsMovieHeader()
{
    const QByteArray type = box("ftyp", QByteArray("M4A ") + big32(0) + QByteArray("M4A mp42"));
    const QByteArray data = box("mdat", QByteArray(64, '\x5a'));
    // Other boxes come before the header inside the movie box; the media data may come first.
    QCOMPARE(durationOf(
                 type + data
                 + box("moov", box("udta", QByteArray(12, '\0')) + movieHeader(44100, 44100 * 42))),
             qint64{42000});
    QCOMPARE(durationOf(type + box("moov", wideMovieHeader(1000, 754321)) + data), qint64{754321});
    // A box whose size follows in 64 bits.
    const QByteArray header = movieHeader(600, 600 * 3);
    const QByteArray wideBox =
        big32(1) + QByteArray("moov", 4) + big64(static_cast<quint64>(16 + header.size())) + header;
    QCOMPARE(durationOf(type + wideBox), qint64{3000});
}

void TestAirMedia::aBrokenHeaderHasNoLength()
{
    const QByteArray type = box("ftyp", QByteArray("M4A ") + big32(0));
    QCOMPARE(durationOf(QByteArray()), qint64{-1});
    QCOMPARE(durationOf(QByteArray("not a movie at all")), qint64{-1});
    QCOMPARE(durationOf(type), qint64{-1});
    // A movie without its header, a header without a time scale, a header cut short.
    QCOMPARE(durationOf(type + box("moov", box("trak", QByteArray(16, '\0')))), qint64{-1});
    QCOMPARE(durationOf(type + box("moov", movieHeader(0, 100))), qint64{-1});
    QCOMPARE(durationOf(type + box("moov", box("mvhd", big32(0) + big32(0)))), qint64{-1});
    // A box that claims more than the file holds is not read past the end.
    QByteArray lying = type + big32(1u << 30) + QByteArray("moov", 4) + movieHeader(1000, 5000);
    QCOMPARE(durationOf(lying), qint64{-1});
    // The unknown duration of the format.
    QCOMPARE(durationOf(type + box("moov", movieHeader(1000, 0xffffffffu))), qint64{-1});
    QCOMPARE(durationOf(type + box("moov", wideMovieHeader(1000, std::numeric_limits<quint64>::max()))),
             qint64{-1});

    // A device that is not open is not read at all.
    QByteArray data = type + box("moov", movieHeader(1000, 5000));
    QBuffer closed(&data);
    QCOMPARE(Air::mp4DurationMs(closed), qint64{-1});
}

void TestAirMedia::lengthsReadAsClocks()
{
    QCOMPARE(Air::clockText(0), QStringLiteral("0:00"));
    QCOMPARE(Air::clockText(42), QStringLiteral("0:42"));
    QCOMPARE(Air::clockText(725), QStringLiteral("12:05"));
    QCOMPARE(Air::clockText(3729), QStringLiteral("1:02:09"));
    QCOMPARE(Air::clockText(-5), QStringLiteral("0:00"));
}

void TestAirMedia::callRowsReadAsOneEntryEach()
{
    Air::CallEntry entry;
    // Rows of older builds: the length in the words after the name, in Latin units.
    QVERIFY(Air::callEntryOf(callRow(SystemMessageType::callEnd,
                                     {QStringLiteral("Yusuf"),
                                      QStringLiteral("Call duration: 04m 12s"), QString(), QString()}),
                             &entry));
    QCOMPARE(entry.outcome, Air::CallOutcome::Answered);
    QCOMPARE(entry.seconds, qint64{252});
    QVERIFY(Air::callEntryOf(callRow(SystemMessageType::unexpectedCallEnd,
                                     {QStringLiteral("Yusuf"),
                                      QStringLiteral("Длительность звонка: 1d02h 04m 12s"),
                                      QString(), QString()}),
                             &entry));
    QCOMPARE(entry.outcome, Air::CallOutcome::CutOff);
    QCOMPARE(entry.seconds, qint64{93852});
    QVERIFY(Air::callEntryOf(callRow(SystemMessageType::outgoingCall,
                                     {QStringLiteral("Yusuf"), QString(), QString(), QString()}),
                             &entry));
    QCOMPARE(entry.outcome, Air::CallOutcome::Outgoing);
    QVERIFY(Air::callEntryOf(callRow(SystemMessageType::incomingCall,
                                     {QStringLiteral("Yusuf"), QString(), QString(), QString()}),
                             &entry));
    QCOMPARE(entry.outcome, Air::CallOutcome::Incoming);

    // Anything else is not a call.
    QVERIFY(!Air::callEntryOf(callRow(SystemMessageType::peerNameChanged,
                                      {QStringLiteral("Yusuf"), QStringLiteral("Yu"), QString(),
                                       QString()}),
                              &entry));
    QVERIFY(!Air::callEntryOf(callRow(SystemMessageType::messageSendFailed, {}), nullptr));
}

void TestAirMedia::newCallRowsReadBackAsWritten()
{
    Air::CallEntry entry;
    const SystemMessage::Args answered =
        Air::answeredCallArgs(QStringLiteral("Yusuf"), 47, QStringLiteral("Call duration: 47s"));
    // An older build still shows the length after the name.
    QCOMPARE(answered[1], QStringLiteral("Call duration: 47s"));
    QVERIFY(Air::callEntryOf(callRow(SystemMessageType::callEnd, answered), &entry));
    QCOMPARE(entry.outcome, Air::CallOutcome::Answered);
    QCOMPARE(entry.seconds, qint64{47});
    // The seconds win over words that say otherwise.
    QVERIFY(Air::callEntryOf(callRow(SystemMessageType::callEnd,
                                     Air::answeredCallArgs(QStringLiteral("Yusuf"), 61,
                                                           QStringLiteral("Call duration: 05s"))),
                             &entry));
    QCOMPARE(entry.seconds, qint64{61});
    QCOMPARE(Air::answeredCallArgs(QStringLiteral("Yusuf"), -3, QString())[2], QStringLiteral("0"));

    const SystemMessage::Args unanswered = Air::unansweredCallArgs(QStringLiteral("Yusuf"));
    QCOMPARE(unanswered[0], QStringLiteral("Yusuf"));
    QVERIFY(Air::callEntryOf(callRow(SystemMessageType::outgoingCall, unanswered), &entry));
    QCOMPARE(entry.outcome, Air::CallOutcome::NoAnswer);
    QVERIFY(Air::callEntryOf(callRow(SystemMessageType::incomingCall, unanswered), &entry));
    QCOMPARE(entry.outcome, Air::CallOutcome::Missed);
}

QTEST_GUILESS_MAIN(TestAirMedia)
#include "airmedia_test.moc"
