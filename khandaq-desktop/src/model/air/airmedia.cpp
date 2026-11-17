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

#include "airmedia.h"

#include <QDateTime>
#include <QDir>
#include <QFileInfo>
#include <QIODevice>
#include <QRegularExpression>

#include <cmath>
#include <limits>

namespace Air {

namespace {
/** The fourth argument of a call row nobody answered; rows of older builds leave it empty. */
const char unansweredMark[] = "air:unanswered";

constexpr quint32 fourcc(char a, char b, char c, char d)
{
    return (static_cast<quint32>(static_cast<uchar>(a)) << 24)
           | (static_cast<quint32>(static_cast<uchar>(b)) << 16)
           | (static_cast<quint32>(static_cast<uchar>(c)) << 8)
           | static_cast<quint32>(static_cast<uchar>(d));
}

quint32 big32(const uchar* bytes)
{
    return (static_cast<quint32>(bytes[0]) << 24) | (static_cast<quint32>(bytes[1]) << 16)
           | (static_cast<quint32>(bytes[2]) << 8) | static_cast<quint32>(bytes[3]);
}

quint64 big64(const uchar* bytes)
{
    return (static_cast<quint64>(big32(bytes)) << 32) | big32(bytes + 4);
}

struct Box
{
    qint64 start = 0;
    qint64 size = 0;
    quint32 type = 0;
    int header = 8;
};

/** The header of the box at offset, which must end by end. */
bool readBox(QIODevice& device, qint64 offset, qint64 end, Box* box)
{
    if (end - offset < 8 || !device.seek(offset)) {
        return false;
    }
    uchar head[16];
    if (device.read(reinterpret_cast<char*>(head), 8) != 8) {
        return false;
    }
    quint64 size = big32(head);
    int header = 8;
    if (size == 1) {
        // The size follows in 64 bits.
        if (end - offset < 16 || device.read(reinterpret_cast<char*>(head + 8), 8) != 8) {
            return false;
        }
        size = big64(head + 8);
        header = 16;
    } else if (size == 0) {
        // The box runs to the end of the file.
        size = static_cast<quint64>(end - offset);
    }
    if (size < static_cast<quint64>(header) || size > static_cast<quint64>(end - offset)) {
        return false;
    }
    box->start = offset;
    box->size = static_cast<qint64>(size);
    box->type = big32(head + 4);
    box->header = header;
    return true;
}

/** The movie length of an mvhd box in milliseconds, -1 when it is unknown. */
qint64 movieLength(QIODevice& device, const Box& box)
{
    const qint64 body = box.size - box.header;
    if (body < 4 || !device.seek(box.start + box.header)) {
        return -1;
    }
    uchar bytes[32];
    if (device.read(reinterpret_cast<char*>(bytes), 4) != 4) {
        return -1;
    }
    const bool wide = bytes[0] == 1;
    // Creation and modification times, then the time scale and the duration.
    const qint64 rest = wide ? 28 : 16;
    if (body < 4 + rest || device.read(reinterpret_cast<char*>(bytes), rest) != rest) {
        return -1;
    }
    const quint32 scale = big32(bytes + (wide ? 16 : 8));
    quint64 length = 0;
    if (wide) {
        length = big64(bytes + 20);
        if (length == std::numeric_limits<quint64>::max()) {
            return -1;
        }
    } else {
        length = big32(bytes + 12);
        if (length == std::numeric_limits<quint32>::max()) {
            return -1;
        }
    }
    if (scale == 0 || length > static_cast<quint64>(std::numeric_limits<qint64>::max()) / 1000) {
        return -1;
    }
    return static_cast<qint64>(length * 1000 / scale);
}

/** The length of an answered call: the seconds this build writes, or the text of older ones. */
qint64 callSeconds(const SystemMessage::Args& args)
{
    bool number = false;
    const qint64 seconds = args[2].toLongLong(&number);
    if (number && seconds >= 0) {
        return seconds;
    }
    // "Call duration: 1d02h 04m 12s": the units stay Latin letters in every language.
    static const QRegularExpression part(QStringLiteral("(\\d+)([dhms])"));
    qint64 total = 0;
    QRegularExpressionMatchIterator it = part.globalMatch(args[1]);
    while (it.hasNext()) {
        const QRegularExpressionMatch match = it.next();
        const qint64 amount = match.captured(1).toLongLong();
        const QChar unit = match.captured(2).at(0);
        total += amount
                 * (unit == QLatin1Char('d')   ? 86400
                    : unit == QLatin1Char('h') ? 3600
                    : unit == QLatin1Char('m') ? 60
                                               : 1);
    }
    return total;
}
} // namespace

bool isVoiceNote(const QString& fileName)
{
    static const QString suffix = QStringLiteral(".file.m4a");
    return fileName.size() > suffix.size() && fileName.endsWith(suffix, Qt::CaseInsensitive);
}

QString voiceNoteFolder(const QString& appDataDir)
{
    return QDir(appDataDir).filePath(QStringLiteral("voice"));
}

QString freeFilePath(const QString& folder, const QString& fileName,
                     const std::function<bool(const QString&)>& taken)
{
    // KHANDAQ (0.2.57): only the last part of the name counts: the file goes into folder.
    const QString name = QFileInfo(fileName).fileName();
    if (name.isEmpty()) {
        return QString();
    }
    const QDir dir(folder);
    const int dot = name.indexOf(QLatin1Char('.'));
    // A name that starts with its dot, or has none, takes the number at its end.
    const QString base = dot > 0 ? name.left(dot) : name;
    const QString rest = dot > 0 ? name.mid(dot) : QString();
    QString path = dir.filePath(name);
    for (int number = 1; taken && taken(path); ++number) {
        path = dir.filePath(QStringLiteral("%1 (%2)%3").arg(base).arg(number).arg(rest));
    }
    return path;
}

const char partFileSuffix[] = ".khandaq-part";

QString partFilePath(const QString& path)
{
    return path.isEmpty() ? QString() : path + QLatin1String(partFileSuffix);
}

bool isPartPath(const QString& path)
{
    const QLatin1String suffix(partFileSuffix);
    return path.size() > suffix.size() && path.endsWith(suffix);
}

QString wholeFilePath(const QString& path)
{
    const QLatin1String suffix(partFileSuffix);
    return isPartPath(path) ? path.left(path.size() - suffix.size()) : path;
}

QString freePartPath(const QString& path, const std::function<bool(const QString&)>& taken)
{
    const QFileInfo whole(wholeFilePath(path));
    const QString free = freeFilePath(whole.absolutePath(), whole.fileName(),
                                      [&taken](const QString& candidate) {
                                          return taken
                                                 && (taken(candidate)
                                                     || taken(partFilePath(candidate)));
                                      });
    return partFilePath(free);
}

bool FileEntryState::isProblem() const
{
    return note == FileNote::DownloadStopped || note == FileNote::NotSent;
}

FileEntryState fileEntryState(const FileFacts& facts)
{
    FileEntryState state;
    // A group file has nothing to answer: it arrives whole or not at all.
    const bool controls = !facts.group;
    // KHANDAQ (0.2.57): a transfer whose friend went offline waits, never fails: the bar stays at
    // what came, and only Cancel is offered until they are back.
    if (facts.waitsConnection && controls) {
        if (facts.status == ToxFile::TRANSMITTING || facts.status == ToxFile::PAUSED) {
            state.progress =
                std::isfinite(facts.progress) ? qBound(0.0, facts.progress, 1.0) : 0.0;
        }
        state.note = FileNote::WaitsConnection;
        state.actions = {FileAction::Cancel};
        return state;
    }
    switch (facts.status) {
    case ToxFile::INITIALIZING:
        if (facts.incoming) {
            // The size is on the line above; Accept is the whole question.
            if (controls) {
                state.actions = {FileAction::Accept, FileAction::Decline};
            } else if (facts.held) {
                // Here already, in memory: there is nothing to decline from the sender.
                state.actions = {FileAction::Accept};
            }
        } else {
            state.note = facts.group ? FileNote::None : FileNote::WaitsAccept;
            if (controls) {
                state.actions = {FileAction::Cancel};
            }
        }
        break;
    case ToxFile::TRANSMITTING: {
        const double share = std::isfinite(facts.progress) ? qBound(0.0, facts.progress, 1.0) : 0.0;
        state.progress = share;
        if (facts.size > 0) {
            state.note = FileNote::Left;
            state.bytesLeft =
                static_cast<qint64>(std::ceil(static_cast<double>(facts.size) * (1.0 - share)));
        }
        if (controls) {
            state.actions = {FileAction::Pause, FileAction::Cancel};
        }
        break;
    }
    case ToxFile::PAUSED:
        // Nobody pauses a group file: ours waits for a member to send it to.
        if (facts.group && !facts.incoming) {
            state.note = FileNote::WaitsMembers;
            break;
        }
        // Only the side that paused can go on.
        if (facts.pausedHere) {
            state.note = FileNote::Paused;
            if (controls) {
                state.actions = {FileAction::Resume, FileAction::Cancel};
            }
        } else {
            state.note = FileNote::PausedThere;
            if (controls) {
                state.actions = {FileAction::Cancel};
            }
        }
        break;
    case ToxFile::BROKEN:
    case ToxFile::CANCELED:
        if (facts.incoming) {
            state.note = FileNote::DownloadStopped;
        } else {
            state.note = FileNote::NotSent;
            if (facts.canSendAgain) {
                state.actions = {FileAction::SendAgain};
            }
        }
        break;
    case ToxFile::FINISHED:
        if (facts.here && !facts.voiceNote) {
            state.actions = {FileAction::Open, FileAction::ShowInFolder};
        }
        break;
    }
    return state;
}

EntryStatus fileStatus(ToxFile::FileStatus status, bool personOnline)
{
    switch (status) {
    case ToxFile::FINISHED:
        return EntryStatus::Delivered;
    case ToxFile::INITIALIZING:
        return personOnline ? EntryStatus::Sending : EntryStatus::Transit;
    case ToxFile::TRANSMITTING:
    case ToxFile::PAUSED:
        return EntryStatus::Sending;
    case ToxFile::BROKEN:
    case ToxFile::CANCELED:
        return EntryStatus::Failed;
    }
    return EntryStatus::None;
}

EntryStatus fileStatus(const ToxFile& file, bool personOnline)
{
    return fileStatus(file.connectionLost ? ToxFile::INITIALIZING : file.status, personOnline);
}

bool isOffer(const ToxFile& file)
{
    return file.status == ToxFile::INITIALIZING && file.direction == ToxFile::RECEIVING
           && !file.connectionLost;
}

FileResume fileResume(const QByteArray& offerId, qint64 offerSize, const FileHere& here)
{
    FileResume resume;
    if (offerId.isEmpty() || offerId != here.id || offerSize != here.size || offerSize < 0) {
        return resume;
    }
    if (here.status == ToxFile::FINISHED) {
        resume.step = FileResume::Step::EndQuietly;
        resume.offset = qMax<qint64>(0, offerSize - 1);
        return resume;
    }
    if (here.refused) {
        resume.step = FileResume::Step::AskAgain;
        return resume;
    }
    if (!here.accepted) {
        resume.step = FileResume::Step::WaitInEntry;
        return resume;
    }
    resume.step = FileResume::Step::GoOn;
    if (!here.ownPart || here.bytes > offerSize) {
        // Never into a file that may not be what came of it.
        resume.newPath = true;
        return resume;
    }
    if (here.bytes > 0 && here.bytes < offerSize) {
        resume.offset = here.bytes;
    } else if (here.bytes == offerSize && offerSize > 0) {
        // All of it came, but its end did not: the last byte once more ends it as usual.
        resume.offset = offerSize - 1;
    }
    return resume;
}

ToxFile::Stamp fileStampAt(const QString& path)
{
    ToxFile::Stamp stamp;
    const QFileInfo info(path);
    if (path.isEmpty() || !info.isFile()) {
        return stamp;
    }
    stamp.bytes = info.size();
    const QDateTime modified = info.lastModified();
    stamp.modifiedMs = modified.isValid() ? modified.toMSecsSinceEpoch() : -1;
#if QT_VERSION >= QT_VERSION_CHECK(5, 10, 0)
    const QDateTime born = info.birthTime();
    stamp.bornMs = born.isValid() ? born.toMSecsSinceEpoch() : -1;
#endif
    return stamp;
}

bool isOwnPart(const ToxFile::Stamp& recorded, const ToxFile::Stamp& now)
{
    if (now.bytes < 0 || recorded.bornMs != now.bornMs) {
        return false;
    }
    const bool cutKnown = recorded.bytes >= 0 && recorded.modifiedMs >= 0;
    if (cutKnown && (recorded.bytes != now.bytes || recorded.modifiedMs != now.modifiedMs)) {
        return false;
    }
    return recorded.bornMs >= 0 || cutKnown;
}

bool isSameSource(const ToxFile::Stamp& sent, qint64 size, const ToxFile::Stamp& now)
{
    return sent.modifiedMs >= 0 && now.bytes == size && now.modifiedMs == sent.modifiedMs
           && (sent.bornMs < 0 || now.bornMs == sent.bornMs);
}

bool downloadsRuleApplies(FileResume::Step step)
{
    return step == FileResume::Step::NewEntry || step == FileResume::Step::WaitInEntry;
}

qint64 mp4DurationMs(QIODevice& device)
{
    if (!device.isOpen() || device.isSequential()) {
        return -1;
    }
    const qint64 end = device.size();
    Box box;
    for (qint64 offset = 0; readBox(device, offset, end, &box); offset += box.size) {
        if (box.type != fourcc('m', 'o', 'o', 'v')) {
            continue;
        }
        const qint64 movieEnd = box.start + box.size;
        Box inner;
        for (qint64 at = box.start + box.header; readBox(device, at, movieEnd, &inner);
             at += inner.size) {
            if (inner.type == fourcc('m', 'v', 'h', 'd')) {
                return movieLength(device, inner);
            }
        }
        return -1;
    }
    return -1;
}

QString clockText(qint64 seconds)
{
    const qint64 total = qMax<qint64>(0, seconds);
    const qint64 hours = total / 3600;
    const qint64 minutes = (total % 3600) / 60;
    const qint64 rest = total % 60;
    const QLatin1Char zero('0');
    if (hours > 0) {
        return QStringLiteral("%1:%2:%3").arg(hours).arg(minutes, 2, 10, zero).arg(rest, 2, 10, zero);
    }
    return QStringLiteral("%1:%2").arg(minutes).arg(rest, 2, 10, zero);
}

bool callEntryOf(const SystemMessage& message, CallEntry* entry)
{
    CallEntry result;
    const bool unanswered = message.args[3] == QLatin1String(unansweredMark);
    switch (message.messageType) {
    case SystemMessageType::callEnd:
        result.outcome = CallOutcome::Answered;
        result.seconds = callSeconds(message.args);
        break;
    case SystemMessageType::unexpectedCallEnd:
        result.outcome = CallOutcome::CutOff;
        result.seconds = callSeconds(message.args);
        break;
    case SystemMessageType::outgoingCall:
        result.outcome = unanswered ? CallOutcome::NoAnswer : CallOutcome::Outgoing;
        break;
    case SystemMessageType::incomingCall:
        result.outcome = unanswered ? CallOutcome::Missed : CallOutcome::Incoming;
        break;
    default:
        return false;
    }
    if (entry) {
        *entry = result;
    }
    return true;
}

SystemMessage::Args answeredCallArgs(const QString& name, qint64 seconds, const QString& legacyText)
{
    return {name, legacyText, QString::number(qMax<qint64>(0, seconds)), QString()};
}

SystemMessage::Args unansweredCallArgs(const QString& name)
{
    return {name, QString(), QString(), QLatin1String(unansweredMark)};
}

} // namespace Air
