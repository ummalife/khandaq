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

#pragma once

#include "src/core/toxfile.h"
#include "src/model/air/airconversation.h"
#include "src/model/systemmessage.h"

#include <QByteArray>
#include <QString>
#include <QVector>

#include <functional>

class QIODevice;

/**
 * Files, voice notes and calls as entries of the transcript (E8, E9), apart from any widget: what a
 * file entry says and offers in each state of its transfer, the length of a voice note, and the
 * call rows of the history read as one entry per call. The rules are those of design/DESIGN.md,
 * "Files, voice and calls on the phone", as khandaq-ios/Antidote/AirFileEntry.swift and
 * AirCalls.swift have them; the transfers stay Tox file transfers and the history keeps its rows.
 */
namespace Air {

// ---- files ----

/**
 * The most a file may weigh, as the clients say it: the round 200 MB of the phones, counted in
 * thousands like every size. The cap of the transport (KHANDAQ_MAX_FILE_TRANSFER_BYTES, 200 MiB) is
 * a little higher, so no file the words allow is refused.
 */
constexpr qint64 fileLimitShownBytes = 200LL * 1000 * 1000;

/**
 * The voice note of the phones: a Tox file whose name ends in ".file.m4a", in any case. Only the
 * name says so; nothing about the file is trusted from it.
 */
bool isVoiceNote(const QString& fileName);

/**
 * The folder voice notes go to, in the data folder of the app (appDataDir): they are listened to,
 * not kept as files, so they come there without the question where to save them.
 */
QString voiceNoteFolder(const QString& appDataDir);

/**
 * Where a received file goes when nobody is asked where: in folder under its own name, or, while a
 * file of that name is there (taken), with " (1)", " (2)" ... after the part before its first dot,
 * as the files accepted by themselves are named (Widget::acceptFileTransfer). The name is the
 * sender's, cleaned on Windows only (CoreFile::getCleanFileName): only its last part is taken, so
 * the file goes into folder whatever path it names.
 * @return The path; empty for an empty name.
 */
QString freeFilePath(const QString& folder, const QString& fileName,
                     const std::function<bool(const QString&)>& taken);

/**
 * KHANDAQ (0.2.57): a file of the person is written under a name of its own until all of it is
 * here: the path it goes to with this after it (partFilePath). Whole, it takes that path
 * (wholeFilePath); one that will not come leaves nothing behind, and one that may still go on
 * keeps what came under this name. Until all of it is here, nothing in its folder has its name.
 */
extern const char partFileSuffix[];
/** Where a file going to path is written until it is whole; empty for an empty path. */
QString partFilePath(const QString& path);
/** Whether a file is written at path until it is whole (partFilePath). */
bool isPartPath(const QString& path);
/** The path the file written at path goes to once whole: path itself unless it is a part. */
QString wholeFilePath(const QString& path);
/**
 * The part for a file that starts again beside the one at path (a part, or a file of an older
 * build written under its own name): the part of the first free name (freeFilePath) of the file
 * it goes to, where neither that file nor its part is taken. Empty for an empty name.
 */
QString freePartPath(const QString& path, const std::function<bool(const QString&)>& taken);

/** What a file entry offers, in the order it shows them. */
enum class FileAction
{
    Accept,
    Decline,
    Pause,
    Resume,
    Cancel,
    SendAgain,
    Open,
    ShowInFolder,
};

/** The line of words under the name. */
enum class FileNote
{
    None,
    /** "1.2 MB left", while it moves. */
    Left,
    /** We paused it. */
    Paused,
    /** The other side paused it: only they can go on. */
    PausedThere,
    /** Ours, offered and not yet accepted: "Waits for Yusuf to accept it". */
    WaitsAccept,
    /** Ours, into a group that has nobody to send it to now. */
    WaitsMembers,
    /** Theirs stopped before the end; Tox cannot ask for it again, only they can send it again. */
    DownloadStopped,
    /** Ours stopped before the end. */
    NotSent,
    /**
     * KHANDAQ (0.2.57): the friend went offline in the middle of it; it goes on by itself, from
     * where it stopped, once they are back.
     */
    WaitsConnection,
};

/** What the entry knows about its transfer. */
struct FileFacts
{
    ToxFile::FileStatus status = ToxFile::INITIALIZING;
    bool incoming = false;
    bool pausedHere = false;
    bool pausedThere = false;
    qint64 size = 0;
    /** The share moved so far, 0 to 1. */
    double progress = 0;
    /** A group file travels whole: nothing to accept, pause or cancel. */
    bool group = false;
    /** A voice note plays from its own row instead of opening. */
    bool voiceNote = false;
    /** The finished file is at its path on this computer. */
    bool here = false;
    /** Ours stopped, the file is still at its path and the person is online: it can go again. */
    bool canSendAgain = false;
    /** KHANDAQ (0.2.57): the friend went offline in the middle of it (ToxFile::connectionLost). */
    bool waitsConnection = false;
    /**
     * KHANDAQ (0.2.57): a group file of someone else that came and waits for Accept to be written
     * (Air::Community::HeldFiles); only Khandaq Community holds its files.
     */
    bool held = false;
};

struct FileEntryState
{
    /** The share moved, 0 to 1, for the bar; negative while nothing moves. */
    double progress = -1;
    FileNote note = FileNote::None;
    /** For FileNote::Left: what is still to come, in bytes. */
    qint64 bytesLeft = 0;
    QVector<FileAction> actions;

    /** The note says that the transfer failed (in the danger colour) rather than how it goes. */
    bool isProblem() const;
};

FileEntryState fileEntryState(const FileFacts& facts);

/**
 * The mark of a file of ours: two checks once the person has all of it, a clock while it waits or
 * moves (amber while they are offline), red when it stopped.
 */
EntryStatus fileStatus(ToxFile::FileStatus status, bool personOnline);
/** The mark of a file; one that waits for its friend to come back waits as an offer does. */
EntryStatus fileStatus(const ToxFile& file, bool personOnline);

/**
 * KHANDAQ (0.2.57): whether a file of the person is an offer the window answers (an entry, the
 * downloads rule, an alert): theirs and not yet accepted. One that waits for its sender is none:
 * CoreFile only shows again, in its entry, an offer whose sender went offline.
 */
bool isOffer(const ToxFile& file);

/**
 * KHANDAQ (0.2.57): what a file of theirs this side has is, for an offer of a file with its id
 * (fileResume): the id, the size and the state of its entry, whether it was accepted, and how much
 * of it the file it goes to holds.
 */
struct FileHere
{
    QByteArray id;
    qint64 size = 0;
    ToxFile::FileStatus status = ToxFile::INITIALIZING;
    /** It was accepted, and what came of it went to its path. */
    bool accepted = false;
    /** The bytes at its path; 0 when there is nothing there. */
    qint64 bytes = 0;
    /** The person cancelled or declined it (ToxFile::refusedHere). */
    bool refused = false;
    /**
     * What is at its path is what came of it (isOwnPart), and no later file goes there: only then
     * does it go on in that file.
     */
    bool ownPart = false;
};

/** What an offer does with the entry of the same file (fileResume). */
struct FileResume
{
    enum class Step
    {
        /** Another file: a new entry, as for any offer. */
        NewEntry,
        /**
         * All of it is here already, and its sender did not learn it before the connection went:
         * the last byte comes once more, so both sides end, and nothing here changes.
         */
        EndQuietly,
        /** It goes on in its entry by itself from offset, since it was accepted. */
        GoOn,
        /** It waits in its entry for Accept, or for the downloads rule, from the start. */
        WaitInEntry,
        /**
         * The person cancelled or declined it: it waits in its entry for their tap, from the
         * start, and never comes by itself. Declined again, its sender learns it.
         */
        AskAgain,
    };
    Step step = Step::NewEntry;
    /** Where it goes on from, in bytes: what toxcore seeks to before the accept. */
    qint64 offset = 0;
    /**
     * For GoOn: what is at its path may not be what came of it, so it goes on from the start under
     * a free name beside it (freePartPath), and that file stays as it is.
     */
    bool newPath = false;
};

/**
 * KHANDAQ (0.2.57): the standard resume of toxcore. The sender offers the file again with the same
 * id, kind, size and name; this side seeks to what it has before it accepts. Another id or another
 * size is another file. What is here goes on from its length; a file that is whole goes on from
 * its last byte, which comes once more. What is at its path goes on only when it is what came of
 * it (FileHere::ownPart); otherwise, or when it is longer than its size, it starts again under a
 * new name. One the person cancelled or declined waits for them.
 */
FileResume fileResume(const QByteArray& offerId, qint64 offerSize, const FileHere& here);

/**
 * KHANDAQ (0.2.57): the stamp of the file at path now (ToxFile::Stamp): its birth time, its length
 * and its last change; -1 for what the system does not tell, all of it when there is no file.
 */
ToxFile::Stamp fileStampAt(const QString& path);

/**
 * KHANDAQ (0.2.57): whether the file at a path is the part a file of the person left there, from
 * the stamp recorded of it (recorded) and that of the path now (now, fileStampAt): the same file,
 * made when that one was, and, where its cut is known, as it was then. With nothing to tell it by,
 * it is not.
 */
bool isOwnPart(const ToxFile::Stamp& recorded, const ToxFile::Stamp& now);

/**
 * KHANDAQ (0.2.57): whether a file of ours is still the one that went (sent, its stamp when it was
 * offered): the same length as the offer (size), the same last change, and made when it was where
 * that is known. Only then does it go again under its Tox file id; without the last change that
 * went, it does not.
 */
bool isSameSource(const ToxFile::Stamp& sent, qint64 size, const ToxFile::Stamp& now);

/**
 * KHANDAQ (0.2.57): whether the downloads rule (autoAcceptFolder) may take an offer the window
 * answers with step: a new one, or one that waits in its entry as a new offer does. Never one the
 * person cancelled or declined: it comes only by their tap.
 */
bool downloadsRuleApplies(FileResume::Step step);

// ---- voice notes ----

/**
 * The length of an MP4 or M4A file from its movie header (moov, mvhd), in milliseconds; -1 when
 * there is none or it cannot be read. Only box headers are read, so a large file costs no time.
 */
qint64 mp4DurationMs(QIODevice& device);

/** "0:42", "12:05", "1:02:09": the length of a voice note or a call, in Latin digits. */
QString clockText(qint64 seconds);

// ---- calls ----

/** How a call went, as its entry says it. */
enum class CallOutcome
{
    /** "Call, 4:12". */
    Answered,
    /** Answered, then the link broke: "Call, 4:12, cut off". */
    CutOff,
    /** Theirs that nobody answered. */
    Missed,
    /** Ours that nobody answered. */
    NoAnswer,
    /** The start of one of our calls, as builds before this one wrote it. */
    Outgoing,
    /** The start of one of theirs, as builds before this one wrote it. */
    Incoming,
};

struct CallEntry
{
    CallOutcome outcome = CallOutcome::Answered;
    /** How long it lasted, for Answered and CutOff. */
    qint64 seconds = 0;
};

/** Whether a row of the history is a call; entry, when given, takes what it says. */
bool callEntryOf(const SystemMessage& message, CallEntry* entry);

/**
 * The row of a call that was answered. legacyText is what builds before this one show after the
 * name ("Call duration: 04m 12s"), so a history read by an older build still says it.
 */
SystemMessage::Args answeredCallArgs(const QString& name, qint64 seconds, const QString& legacyText);

/**
 * The row of a call nobody answered; it keeps the type of the way the call went (outgoingCall or
 * incomingCall), which older builds show as "Calling Yusuf" and "Yusuf calling".
 */
SystemMessage::Args unansweredCallArgs(const QString& name);

} // namespace Air
