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

#include <QBuffer>
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
    void theirFileWaitsForAcceptOrDecline();
    void ourFileWaitsForTheOtherSide();
    void aMovingFileSaysWhatIsLeft();
    void onlyTheSideThatPausedGoesOn();
    void aStoppedFileSaysWhichWayItWent();
    void aFileThatIsHereOpens();
    void aGroupFileHasNothingToAnswer();
    void theMarkOfOurFileFollowsTheTransfer();
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
