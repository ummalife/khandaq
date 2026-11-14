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
 * Where a received file goes when nobody is asked where: in folder under its own name, or, while a
 * file of that name is there (taken), with " (1)", " (2)" ... after the part before its first dot,
 * as the files accepted by themselves are named (Widget::acceptFileTransfer). The name came through
 * CoreFile::getCleanFileName, so it has no path in it.
 * @return The path; empty for an empty name.
 */
QString freeFilePath(const QString& folder, const QString& fileName,
                     const std::function<bool(const QString&)>& taken);

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
    /** Theirs stopped before the end; Tox cannot ask for it again, only they can send it again. */
    DownloadStopped,
    /** Ours stopped before the end. */
    NotSent,
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
