/*
    Copyright © 2015-2019 by The qTox Project Contributors

    This file is part of qTox, a Qt-based graphical interface for Tox.

    qTox is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    qTox is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with qTox.  If not, see <http://www.gnu.org/licenses/>.
*/


#include "corefile.h"
#include "core.h"
#include "toxfile.h"
#include "toxstring.h"
#include "src/persistence/settings.h"
#include "src/model/air/airmedia.h"
#include "src/model/status.h"
#include "src/model/toxclientstandards.h"
#include "util/compatiblerecursivemutex.h"
#include "util/toxcoreerrorparser.h"

#include <QBuffer>
#include <QDebug>
#include <QDir>
#include <QFile>
#include <QFileInfo>
#include <QImageReader>
#include <QRegularExpression>
#include <QThread>

#include <tox/tox.h>

#include <cassert>
#include <memory>

namespace {
/**
 * KHANDAQ (audit 2026-08-20): true when an image's DECLARED dimensions are plausible enough to
 * decode. Reads the header only — no pixel buffer is allocated — so a decode bomb is rejected
 * before it can cost anything. Same limits as pixmapFromFile() in imagepreviewwidget.cpp.
 *
 * An unreadable or dimensionless header returns true: the decoder will fail on it anyway, and
 * refusing everything Qt cannot pre-parse would reject formats that are perfectly safe.
 */
bool khandaqImageDimensionsSane(const QByteArray& data)
{
    static const qint64 kMaxPixels = 64LL * 1000 * 1000; // ~64 megapixels
    static const int kMaxSide = 16384;

    QBuffer probeBuffer;
    probeBuffer.setData(data);
    if (!probeBuffer.open(QIODevice::ReadOnly)) {
        return true;
    }
    QImageReader probe(&probeBuffer);
    const QSize dims = probe.size();
    if (!dims.isValid()) {
        return true;
    }
    return static_cast<qint64>(dims.width()) * static_cast<qint64>(dims.height()) <= kMaxPixels
           && dims.width() <= kMaxSide && dims.height() <= kMaxSide;
}

/**
 * KHANDAQ (0.2.57): how long an incoming file waits for its sender to offer it again once they are
 * back. A client that goes on offers it a moment after it sees them (offerAgainAfterMs); one that
 * cannot (0.2.56 and older) never does, and its entry then ends as stopped instead of waiting for
 * ever.
 */
constexpr qint64 waitForOfferMs = 60 * 1000;
/** Files that wait for their friend are numbered from here: toxcore numbers stay below 1 << 24. */
constexpr uint32_t waitingNumberBase = 0x80000000u;

/** The file id of a file, as toxcore takes it: 32 bytes, or none. */
const uint8_t* toxFileId(const QByteArray& fileId)
{
    if (fileId.size() != TOX_FILE_ID_LENGTH) {
        return nullptr;
    }
    return reinterpret_cast<const uint8_t*>(fileId.constData());
}
} // namespace

constexpr qint64 CoreFile::offerAgainAfterMs;

/**
 * @class CoreFile
 * @brief Manages the file transfer service of toxcore
 */

CoreFilePtr CoreFile::makeCoreFile(Core *core, Tox *tox, CompatibleRecursiveMutex &coreLoopLock)
{
    assert(core != nullptr);
    assert(tox != nullptr);
    connectCallbacks(*tox);
    CoreFilePtr result = CoreFilePtr{new CoreFile{tox, coreLoopLock}};
    // KHANDAQ (0.2.57): at once, in tox_iterate: toxcore has just forgotten the transfers with the
    // friend (break_files), and an offer of one of them again may come in the same iteration.
    connect(core, &Core::friendLinkChanged, result.get(), &CoreFile::onFriendLinkChanged,
            Qt::DirectConnection);

    return result;
}

CoreFile::CoreFile(Tox *core_, CompatibleRecursiveMutex &coreLoopLock_)
    : tox{core_}
    , coreLoopLock{&coreLoopLock_}
{
}

/**
 * @brief Get corefile iteration interval.
 *
 * tox_iterate calls to get good file transfer performances
 * @return The maximum amount of time in ms that Core should wait between two tox_iterate() calls.
 */
unsigned CoreFile::corefileIterationInterval()
{
    /*
       Sleep at most 1000ms if we have no FT, 10 for user FTs
       There is no real difference between 10ms sleep and 50ms sleep when it
       comes to CPU usage – just keep the CPU usage low when there are no file
       transfers, and speed things up when there is an ongoing file transfer.
    */
    constexpr unsigned fileInterval = 10, idleInterval = 1000;

    for (ToxFile& file : fileMap) {
        if (file.status == ToxFile::TRANSMITTING && !file.connectionLost) {
            return fileInterval;
        }
    }
    return idleInterval;
}

void CoreFile::connectCallbacks(Tox &tox)
{
    // be careful not to to reconnect already used callbacks here
    tox_callback_file_chunk_request(&tox, CoreFile::onFileDataCallback);
    tox_callback_file_recv(&tox, CoreFile::onFileReceiveCallback);
    tox_callback_file_recv_chunk(&tox, CoreFile::onFileRecvChunkCallback);
    tox_callback_file_recv_control(&tox, CoreFile::onFileControlCallback);
}

void CoreFile::sendAvatarFile(uint32_t friendId, const QByteArray& data)
{
    QMutexLocker locker{coreLoopLock};

    uint64_t filesize = 0;
    uint8_t *file_id = nullptr;
    uint8_t *file_name = nullptr;
    size_t nameLength = 0;
    uint8_t avatarHash[TOX_HASH_LENGTH];
    if (!data.isEmpty()) {
        static_assert(TOX_HASH_LENGTH <= TOX_FILE_ID_LENGTH, "TOX_HASH_LENGTH > TOX_FILE_ID_LENGTH!");
        tox_hash(avatarHash, reinterpret_cast<const uint8_t*>(data.data()), data.size());
        filesize = data.size();
        file_id = avatarHash;
        file_name = avatarHash;
        nameLength = TOX_HASH_LENGTH;
    }
    Tox_Err_File_Send error;
    const uint32_t fileNum = tox_file_send(tox, friendId, TOX_FILE_KIND_AVATAR, filesize,
                                    file_id, file_name, nameLength, &error);
    if (!PARSE_ERR(error)) {
        return;
    }

    ToxFile file{fileNum, friendId, "", "", filesize, ToxFile::SENDING};
    file.fileKind = TOX_FILE_KIND_AVATAR;
    file.avatarData = data;
    file.resumeFileId.resize(TOX_FILE_ID_LENGTH);
    Tox_Err_File_Get fileGetErr;
    tox_file_get_file_id(tox, friendId, fileNum, reinterpret_cast<uint8_t*>(file.resumeFileId.data()),
                         &fileGetErr);
    if (!PARSE_ERR(fileGetErr)) {
        return;
    }
    addFile(friendId, fileNum, file);
}

void CoreFile::sendFile(uint32_t friendId, QString filename, QString filePath,
                        long long filesize, const QByteArray& fileId)
{
    QMutexLocker locker{coreLoopLock};

    ToxString fileName(filename);
    ToxFile file{0, friendId, fileName.getQString(), filePath, static_cast<uint64_t>(filesize),
                 ToxFile::SENDING};
    // KHANDAQ (0.2.57): a file that cannot be read is offered to nobody; what it is when it goes
    // tells whether it can go again under its id (Air::isSameSource).
    if (!file.open(false)) {
        qWarning() << QString("sendFile: Can't open file, error: %1").arg(file.file->errorString());
        emit fileSendFailed(friendId, fileName.getQString());
        return;
    }
    file.stamp = Air::fileStampAt(filePath);
    Tox_Err_File_Send sendErr;
    const uint8_t* const sameId = toxFileId(fileId);
    uint32_t fileNum = tox_file_send(tox, friendId, TOX_FILE_KIND_DATA, filesize,
                                     sameId, fileName.data(), fileName.size(), &sendErr);

    if (!PARSE_ERR(sendErr)) {
        emit fileSendFailed(friendId, fileName.getQString());
        return;
    }
    qDebug() << QString("sendFile: Created file sender %1 with friend %2").arg(fileNum).arg(friendId);

    file.fileNum = fileNum;
    file.continued = sameId != nullptr;
    file.resumeFileId.resize(TOX_FILE_ID_LENGTH);
    Tox_Err_File_Get fileGetErr;
    tox_file_get_file_id(tox, friendId, fileNum, reinterpret_cast<uint8_t*>(file.resumeFileId.data()),
                         &fileGetErr);
    if (!PARSE_ERR(fileGetErr)) {
        return;
    }

    addFile(friendId, fileNum, file);

    emit fileSendStarted(file);
}

void CoreFile::pauseResumeFile(uint32_t friendId, uint32_t fileId)
{
    QMutexLocker locker{coreLoopLock};

    ToxFile* file = findFile(friendId, fileId);
    if (!file) {
        qWarning("pauseResumeFileSend: No such file in queue");
        return;
    }
    if (file->connectionLost) {
        qWarning() << "pauseResumeFileSend: The file waits for its friend";
        return;
    }

    if (file->status != ToxFile::TRANSMITTING && file->status != ToxFile::PAUSED) {
        qWarning() << "pauseResumeFileSend: File is stopped";
        return;
    }

    file->pauseStatus.localPauseToggle();

    if (file->pauseStatus.paused()) {
        file->status = ToxFile::PAUSED;
        file->progress.resetSpeed();
        emit fileTransferPaused(*file);
    } else {
        file->status = ToxFile::TRANSMITTING;
        emit fileTransferAccepted(*file);
    }

    Tox_Err_File_Control err;
    if (file->pauseStatus.localPaused()) {
        tox_file_control(tox, file->friendId, file->fileNum, TOX_FILE_CONTROL_PAUSE,
                         &err);
    } else {
        tox_file_control(tox, file->friendId, file->fileNum, TOX_FILE_CONTROL_RESUME,
                         &err);
    }
    PARSE_ERR(err);
}

void CoreFile::cancelFileSend(uint32_t friendId, uint32_t fileId)
{
    QMutexLocker locker{coreLoopLock};

    ToxFile* file = findFile(friendId, fileId);
    if (!file) {
        qWarning("cancelFileSend: No such file in queue");
        return;
    }

    // A file that waits for its friend is not in toxcore any more.
    if (!file->connectionLost) {
        Tox_Err_File_Control err;
        tox_file_control(tox, file->friendId, file->fileNum, TOX_FILE_CONTROL_CANCEL, &err);
        if (!PARSE_ERR(err)) {
            return;
        }
    }
    file->status = ToxFile::CANCELED;
    emit fileTransferCancelled(*file);
    removeFile(friendId, fileId);
}

void CoreFile::cancelFileRecv(uint32_t friendId, uint32_t fileId)
{
    QMutexLocker locker{coreLoopLock};

    ToxFile* file = findFile(friendId, fileId);
    if (!file) {
        qWarning("cancelFileRecv: No such file in queue");
        return;
    }
    if (!file->connectionLost) {
        Tox_Err_File_Control err;
        tox_file_control(tox, file->friendId, file->fileNum, TOX_FILE_CONTROL_CANCEL, &err);
        if (!PARSE_ERR(err)) {
            return;
        }
    }
    file->status = ToxFile::CANCELED;
    // KHANDAQ (0.2.57): one that waits for its sender is cancelled here only; an offer of it
    // again waits for the person, and declined it is cancelled on the sender's side too.
    file->refusedHere = true;
    emit fileTransferCancelled(*file);
    removeFile(friendId, fileId);
}

void CoreFile::rejectFileRecvRequest(uint32_t friendId, uint32_t fileId)
{
    QMutexLocker locker{coreLoopLock};

    ToxFile* file = findFile(friendId, fileId);
    if (!file) {
        qWarning("rejectFileRecvRequest: No such file in queue");
        return;
    }
    if (!file->connectionLost) {
        Tox_Err_File_Control err;
        tox_file_control(tox, file->friendId, file->fileNum, TOX_FILE_CONTROL_CANCEL, &err);
        if (!PARSE_ERR(err)) {
            return;
        }
    }
    file->status = ToxFile::CANCELED;
    file->refusedHere = true;
    emit fileTransferCancelled(*file);
    removeFile(friendId, fileId);
}

void CoreFile::acceptFileRecvRequest(uint32_t friendId, uint32_t fileId, QString path)
{
    QMutexLocker locker{coreLoopLock};

    ToxFile* file = findFile(friendId, fileId);
    if (!file) {
        qWarning("acceptFileRecvRequest: No such file in queue");
        return;
    }
    if (file->connectionLost) {
        qWarning() << "acceptFileRecvRequest: The file waits for its sender";
        return;
    }
    file->setFilePath(path);
    if (!file->open(true)) {
        qWarning() << "acceptFileRecvRequest: Unable to open file" << path
                   << file->file->errorString();
        return;
    }
    // KHANDAQ (0.2.57): the file it goes to is made now; it is told from another by when.
    file->stamp = ToxFile::Stamp();
    file->stamp.bornMs = Air::fileStampAt(path).bornMs;
    Tox_Err_File_Control err;
    tox_file_control(tox, file->friendId, file->fileNum, TOX_FILE_CONTROL_RESUME, &err);
    if (!PARSE_ERR(err)) {
        qWarning() << "acceptFileRecvRequest: tox_file_control RESUME failed for"
                   << file->friendId << ':' << file->fileNum;
        file->file->close();
        return;
    }
    file->status = ToxFile::TRANSMITTING;
    // KHANDAQ (0.2.57): the person takes it now.
    file->refusedHere = false;
    emit fileTransferAccepted(*file);
}

void CoreFile::resumeFileRecv(uint32_t friendId, uint32_t fileId, QString path, uint64_t offset)
{
    QMutexLocker locker{coreLoopLock};

    ToxFile* file = findFile(friendId, fileId);
    if (!file || file->connectionLost || file->status != ToxFile::INITIALIZING) {
        qWarning("resumeFileRecv: No such offer");
        return;
    }
    file->setFilePath(path);
    file->continued = true;
    switch (startReceiving(*file, offset)) {
    case Receiving::Started:
        emit fileTransferAccepted(*file);
        return;
    case Receiving::Offered:
        // It waits for Accept in its entry then, from the start.
        return;
    case Receiving::Failed:
        stopReceiving(friendId, fileId);
        return;
    }
}

void CoreFile::finishFileRecvQuietly(uint32_t friendId, uint32_t fileId, uint64_t offset)
{
    QMutexLocker locker{coreLoopLock};

    ToxFile* file = findFile(friendId, fileId);
    if (!file || file->connectionLost || file->status != ToxFile::INITIALIZING) {
        qWarning("finishFileRecvQuietly: No such offer");
        return;
    }
    if (offset > 0) {
        Tox_Err_File_Seek seekErr;
        tox_file_seek(tox, friendId, fileId, offset, &seekErr);
        if (seekErr != TOX_ERR_FILE_SEEK_OK) {
            qWarning() << "finishFileRecvQuietly: seek failed with" << seekErr;
            offset = 0;
        }
    }
    file->progress = ToxFileProgress(file->progress.getFileSize());
    file->progress.addSample(offset);
    Tox_Err_File_Control err;
    tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_RESUME, &err);
    if (!PARSE_ERR(err)) {
        tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_CANCEL, &err);
        removeFile(friendId, fileId);
        return;
    }
    qDebug() << "finishFileRecvQuietly: a file that is here already ends from byte" << offset;
    file->status = ToxFile::TRANSMITTING;
    quietFiles.insert(getFriendKey(friendId, fileId));
}

void CoreFile::markFileRecvRefused(uint32_t friendId, const QByteArray& resumeFileId)
{
    QMutexLocker locker{coreLoopLock};

    for (auto it = fileMap.begin(); it != fileMap.end(); ++it) {
        ToxFile& file = it.value();
        if ((it.key() >> 32) == friendId && file.direction == ToxFile::RECEIVING
            && file.status == ToxFile::INITIALIZING && file.resumeFileId == resumeFileId) {
            file.refusedHere = true;
        }
    }
}

ToxFile* CoreFile::findFile(uint32_t friendId, uint32_t fileId)
{
    QMutexLocker locker{coreLoopLock};

    uint64_t key = getFriendKey(friendId, fileId);
    if (fileMap.contains(key)) {
        return &fileMap[key];
    }

    qWarning() << "findFile: File transfer with ID" << friendId << ':' << fileId << "doesn't exist";
    return nullptr;
}

void CoreFile::addFile(uint32_t friendId, uint32_t fileId, const ToxFile& file)
{
    uint64_t key = getFriendKey(friendId, fileId);

    if (fileMap.contains(key)) {
        qWarning() << "addFile: Overwriting existing file transfer with same ID" << friendId << ':'
                   << fileId;
    }

    fileMap.insert(key, file);
}

void CoreFile::removeFile(uint32_t friendId, uint32_t fileId)
{
    uint64_t key = getFriendKey(friendId, fileId);
    if (!fileMap.contains(key)) {
        qWarning() << "removeFile: No such file in queue";
        return;
    }
    fileMap[key].file->close();
    fileMap.remove(key);
    waitingSince.remove(key);
    quietFiles.remove(key);
}

QString CoreFile::getCleanFileName(QString filename)
{
    // Windows disallows: < > : " / \ | ? *
    QRegularExpression regex{QStringLiteral(R"([<>:"/\\|?*])")};
    filename.replace(regex, "_");

    return filename;
}

void CoreFile::onFileReceiveCallback(Tox* tox, uint32_t friendId, uint32_t fileId, uint32_t kind,
                                     uint64_t filesize, const uint8_t* fname, size_t fnameLen,
                                     void* vCore)
{
    Core* core = static_cast<Core*>(vCore);
    CoreFile* coreFile = core->getCoreFile();
    auto filename = ToxString(fname, fnameLen);
    const ToxPk friendPk = core->getFriendPublicKey(friendId);

    if (kind == TOX_FILE_KIND_AVATAR) {
        if (!filesize) {
            qDebug() << QString("Received empty avatar request %1:%2").arg(friendId).arg(fileId);
            // Avatars of size 0 means explicitely no avatar
            Tox_Err_File_Control err;
            tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_CANCEL, &err);
            PARSE_ERR(err);
            emit core->friendAvatarRemoved(core->getFriendPublicKey(friendId));
            return;
        }
        if (!ToxClientStandards::IsValidAvatarSize(filesize)) {
            qWarning() <<
                QString("Received avatar request from %1 with size %2.").arg(friendId).arg(filesize) +
                QString(" The max size allowed for avatars is %3. Cancelling transfer.").arg(ToxClientStandards::MaxAvatarSize);
            Tox_Err_File_Control err;
            tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_CANCEL, &err);
            PARSE_ERR(err);
            return;
        }
        static_assert(TOX_HASH_LENGTH <= TOX_FILE_ID_LENGTH,
                        "TOX_HASH_LENGTH > TOX_FILE_ID_LENGTH!");
        uint8_t avatarHash[TOX_FILE_ID_LENGTH];
        Tox_Err_File_Get fileGetErr;
        tox_file_get_file_id(tox, friendId, fileId, avatarHash, &fileGetErr);
        if (!PARSE_ERR(fileGetErr)) {
            return;
        }
        QByteArray avatarBytes{static_cast<const char*>(static_cast<const void*>(avatarHash)),
                                TOX_HASH_LENGTH};
        emit core->fileAvatarOfferReceived(friendId, fileId, avatarBytes, filesize);
        return;
    }
    #ifdef Q_OS_WIN
    const auto cleanFileName = CoreFile::getCleanFileName(filename.getQString());
    if (cleanFileName != filename.getQString()) {
        qDebug() << QStringLiteral("Sanitized incoming filename for Windows:")
                 << filename.getQString() << "->" << cleanFileName;
        filename = ToxString(cleanFileName);
    }
    #endif
    qDebug() << QString("Received file request %1:%2 kind %3").arg(friendId).arg(fileId).arg(kind);

    ToxFile file{fileId, friendId, filename.getQString(), "", filesize, ToxFile::RECEIVING};
    file.fileKind = kind;
    file.resumeFileId.resize(TOX_FILE_ID_LENGTH);
    Tox_Err_File_Get fileGetErr;
    tox_file_get_file_id(tox, friendId, fileId, reinterpret_cast<uint8_t*>(file.resumeFileId.data()),
                         &fileGetErr);
    if (!PARSE_ERR(fileGetErr)) {
        return;
    }
    // KHANDAQ (0.2.57): the sender offers again a file that waits here for it.
    if (kind == TOX_FILE_KIND_DATA && coreFile->takeWaitingFile(file)) {
        return;
    }
    coreFile->addFile(friendId, fileId, file);
    if (kind != TOX_FILE_KIND_AVATAR) {
        emit coreFile->fileReceiveRequested(file);
    }
}

// TODO(sudden6): This whole method is a mess but needed to get stuff working for now
void CoreFile::handleAvatarOffer(uint32_t friendId, uint32_t fileId, bool accept, uint64_t filesize)
{
    if (!accept) {
        // If it's an avatar but we already have it cached, cancel
        qDebug() << QString("Received avatar request %1:%2. Rejected since it is in cache.")
                        .arg(friendId)
                        .arg(fileId);
        Tox_Err_File_Control err;
        tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_CANCEL, &err);
        PARSE_ERR(err);
        return;
    }

    Tox_Err_File_Control err;
    tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_RESUME, &err);
    if (!PARSE_ERR(err)) {
        return;
    }
    // It's an avatar and we don't have it, autoaccept the transfer
    qDebug() << QString("Received avatar request %1:%2. Accepted.")
                    .arg(friendId)
                    .arg(fileId);

    ToxFile file{fileId, friendId, "<avatar>", "", filesize, ToxFile::RECEIVING};
    file.fileKind = TOX_FILE_KIND_AVATAR;
    file.resumeFileId.resize(TOX_FILE_ID_LENGTH);
    Tox_Err_File_Get getErr;
    tox_file_get_file_id(tox, friendId, fileId, reinterpret_cast<uint8_t*>(file.resumeFileId.data()),
                         &getErr);
    if (!PARSE_ERR(getErr)) {
        return;
    }
    addFile(friendId, fileId, file);
}

void CoreFile::onFileControlCallback(Tox* tox, uint32_t friendId, uint32_t fileId,
                                     Tox_File_Control control, void* vCore)
{
    std::ignore = tox;
    Core* core = static_cast<Core*>(vCore);
    CoreFile* coreFile = core->getCoreFile();
    ToxFile* file = coreFile->findFile(friendId, fileId);
    if (!file) {
        qWarning("onFileControlCallback: No such file in queue");
        return;
    }

    if (control == TOX_FILE_CONTROL_CANCEL) {
        if (file->fileKind != TOX_FILE_KIND_AVATAR)
            qDebug() << "File transfer" << friendId << ":" << fileId << "cancelled by friend";
        file->status = ToxFile::CANCELED;
        // The last byte of a file that is here already: nothing shows it.
        if (!coreFile->quietFiles.contains(getFriendKey(friendId, fileId))) {
            emit coreFile->fileTransferCancelled(*file);
        }
        coreFile->removeFile(friendId, fileId);
    } else if (control == TOX_FILE_CONTROL_PAUSE) {
        qDebug() << "onFileControlCallback: Received pause for file " << friendId << ":" << fileId;
        file->pauseStatus.remotePause();
        file->status = ToxFile::PAUSED;
        emit coreFile->fileTransferRemotePausedUnpaused(*file, true);
    } else if (control == TOX_FILE_CONTROL_RESUME) {
        if (file->direction == ToxFile::SENDING && file->fileKind == TOX_FILE_KIND_AVATAR)
            qDebug() << "Avatar transfer" << fileId << "to friend" << friendId << "accepted";
        else
            qDebug() << "onFileControlCallback: Received resume for file " << friendId << ":" << fileId;
        const bool accepted = file->status == ToxFile::INITIALIZING;
        file->pauseStatus.remoteResume();
        file->status = file->pauseStatus.paused() ? ToxFile::PAUSED : ToxFile::TRANSMITTING;
        // KHANDAQ (0.2.57): ours, paused here, was offered again: toxcore sends it once accepted,
        // so it is paused again there.
        if (accepted && file->direction == ToxFile::SENDING && file->pauseStatus.localPaused()) {
            Tox_Err_File_Control err;
            tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_PAUSE, &err);
            PARSE_ERR(err);
        }
        emit coreFile->fileTransferRemotePausedUnpaused(*file, false);
    } else {
        qWarning() << "Unhandled file control " << control << " for file " << friendId << ':' << fileId;
    }
}

void CoreFile::onFileDataCallback(Tox* tox, uint32_t friendId, uint32_t fileId, uint64_t pos,
                                  size_t length, void* vCore)
{

    Core* core = static_cast<Core*>(vCore);
    CoreFile* coreFile = core->getCoreFile();
    ToxFile* file = coreFile->findFile(friendId, fileId);
    if (!file) {
        qWarning("onFileDataCallback: No such file in queue");
        return;
    }

    // If we reached EOF, ack and cleanup the transfer
    if (!length) {
        file->status = ToxFile::FINISHED;
        if (file->fileKind != TOX_FILE_KIND_AVATAR) {
            emit coreFile->fileTransferFinished(*file);
        }
        coreFile->removeFile(friendId, fileId);
        return;
    }

    std::unique_ptr<uint8_t[]> data(new uint8_t[length]);
    int64_t nread;

    if (file->fileKind == TOX_FILE_KIND_AVATAR) {
        QByteArray chunk = file->avatarData.mid(pos, length);
        nread = chunk.size();
        memcpy(data.get(), chunk.data(), nread);
    } else {
        file->file->seek(pos);
        nread = file->file->read(reinterpret_cast<char*>(data.get()), length);
        if (nread <= 0) {
            qWarning("onFileDataCallback: Failed to read from file");
            file->status = ToxFile::CANCELED;
            emit coreFile->fileTransferCancelled(*file);
            // KHANDAQ (0.2.57): cancelled, so the receiver learns it; toxcore refuses a chunk of no
            // bytes before the end, which left both sides waiting.
            Tox_Err_File_Control err;
            tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_CANCEL, &err);
            PARSE_ERR(err);
            coreFile->removeFile(friendId, fileId);
            return;
        }
        // KHANDAQ (0.2.57): the chunks start where the receiver asked, after a seek: the progress
        // goes by the position, and a hash knows only a file read whole and in order.
        if (pos != file->progress.getBytesSent()) {
            qDebug() << "onFileDataCallback: file" << friendId << ':' << fileId
                     << "goes on from byte" << pos;
            file->progress = ToxFileProgress(file->progress.getFileSize());
            if (pos == 0) {
                file->hashGenerator =
                    std::make_shared<QCryptographicHash>(QCryptographicHash::Sha256);
            } else {
                file->hashGenerator.reset();
            }
        }
        file->progress.addSample(pos + static_cast<uint64_t>(nread));
        if (file->hashGenerator) {
            file->hashGenerator->addData(reinterpret_cast<const char*>(data.get()),
                                         static_cast<int>(nread));
        }
    }

    Tox_Err_File_Send_Chunk err;
    tox_file_send_chunk(tox, friendId, fileId, pos, data.get(), nread, &err);
    if (!PARSE_ERR(err)) {
        return;
    }
    if (file->fileKind != TOX_FILE_KIND_AVATAR) {
        emit coreFile->fileTransferInfo(*file);
    }
}

void CoreFile::onFileRecvChunkCallback(Tox* tox, uint32_t friendId, uint32_t fileId, uint64_t position,
                                       const uint8_t* data, size_t length, void* vCore)
{
    Core* core = static_cast<Core*>(vCore);
    CoreFile* coreFile = core->getCoreFile();
    ToxFile* file = coreFile->findFile(friendId, fileId);
    if (!file) {
        qWarning("onFileRecvChunkCallback: No such file in queue");
        Tox_Err_File_Control err;
        tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_CANCEL, &err);
        PARSE_ERR(err);
        return;
    }

    // KHANDAQ (0.2.57): the last byte of a file that is here already ends both sides, unseen.
    if (coreFile->quietFiles.contains(getFriendKey(friendId, fileId))) {
        if (!length) {
            qDebug() << "onFileRecvChunkCallback: a file that is here already ended" << friendId
                     << ':' << fileId;
            coreFile->removeFile(friendId, fileId);
        }
        return;
    }

    if (file->progress.getBytesSent() != position) {
        qWarning("onFileRecvChunkCallback: Received a chunk out-of-order, aborting transfer");
        if (file->fileKind != TOX_FILE_KIND_AVATAR) {
            file->status = ToxFile::CANCELED;
            emit coreFile->fileTransferCancelled(*file);
        }
        Tox_Err_File_Control err;
        tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_CANCEL, &err);
        PARSE_ERR(err);
        coreFile->removeFile(friendId, fileId);
        return;
    }

    if (!length) {
        file->status = ToxFile::FINISHED;
        if (file->fileKind == TOX_FILE_KIND_AVATAR) {
            // KHANDAQ (audit 2026-08-20): probe the header before decoding. The only check an
            // incoming avatar passes is a BYTE-size one (ToxClientStandards::MaxAvatarSize, 64 KB),
            // and byte size says nothing about pixel count: a uniform-colour PNG declaring
            // 20000x20000 compresses to a few tens of kilobytes and expands to ~1.6 GB when decoded.
            // Qt5 has no per-reader allocation limit, so loadFromData() on that is an OOM or a long
            // hang, triggerable by any accepted contact simply by setting an avatar.
            //
            // This is the same guard pixmapFromFile() in src/widget/imagepreviewwidget.cpp already
            // applies to preview images, with the same numbers — kept as a local helper rather than
            // shared because core must not depend on the widget layer. If those limits change,
            // change them in both places.
            if (!khandaqImageDimensionsSane(file->avatarData)) {
                qWarning() << "Rejecting avatar from" << friendId << "- implausible pixel dimensions";
                coreFile->removeFile(friendId, fileId);
                return;
            }

            QPixmap pic;
            pic.loadFromData(file->avatarData);
            if (!pic.isNull()) {
                qDebug() << "Got" << file->avatarData.size() << "bytes of avatar data from" << friendId;
                emit core->friendAvatarChanged(core->getFriendPublicKey(friendId), file->avatarData);
            }
        } else {
            emit coreFile->fileTransferFinished(*file);
        }
        coreFile->removeFile(friendId, fileId);
        return;
    }

    if (file->fileKind == TOX_FILE_KIND_AVATAR) {
        file->avatarData.append(reinterpret_cast<const char*>(data), length);
        file->progress.addSample(file->progress.getBytesSent() + length);
        file->hashGenerator->addData(reinterpret_cast<const char*>(data), length);
    } else if (file->fileKind == TOX_FILE_KIND_FTV2) {
        if (length <= TOX_FILE_ID_LENGTH) {
            qWarning() << "onFileRecvChunkCallback: FTV2 chunk shorter than file id"
                       << friendId << ':' << fileId << "length" << length;
            file->status = ToxFile::CANCELED;
            emit coreFile->fileTransferCancelled(*file);
            Tox_Err_File_Control err;
            tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_CANCEL, &err);
            PARSE_ERR(err);
            coreFile->removeFile(friendId, fileId);
            return;
        }
        const size_t payloadLen = length - TOX_FILE_ID_LENGTH;
        const char* payload = reinterpret_cast<const char*>(data) + TOX_FILE_ID_LENGTH;
        file->file->write(payload, static_cast<qint64>(payloadLen));
        file->progress.addSample(file->progress.getBytesSent() + payloadLen);
        if (file->hashGenerator) {
            file->hashGenerator->addData(payload, static_cast<qint64>(payloadLen));
        }
    } else {
        file->file->write(reinterpret_cast<const char*>(data), length);
        file->progress.addSample(file->progress.getBytesSent() + length);
        // A transfer that went on from the middle of the file knows no hash of it.
        if (file->hashGenerator) {
            file->hashGenerator->addData(reinterpret_cast<const char*>(data), length);
        }
    }

    if (file->fileKind != TOX_FILE_KIND_AVATAR) {
        emit coreFile->fileTransferInfo(*file);
    }
}

void CoreFile::onFriendLinkChanged(uint32_t friendId, int toxConnection)
{
    QMutexLocker locker{coreLoopLock};
    if (toxConnection == TOX_CONNECTION_NONE) {
        friendsBack.remove(friendId);
        keepForFriend(friendId);
    } else if (!friendsBack.contains(friendId)) {
        // Ours are offered again a moment later (process, offerAgainAfterMs); a change between TCP
        // and UDP keeps that moment.
        friendsBack.insert(friendId, -1);
    }
}

void CoreFile::forgetFriend(uint32_t friendId)
{
    QMutexLocker locker{coreLoopLock};
    // Their files close with them; nothing says so, as their chat goes too.
    for (const uint64_t key : fileMap.keys()) {
        if (key >> 32 == friendId) {
            removeFile(friendId, static_cast<uint32_t>(key & 0xffffffffu));
        }
    }
    friendsBack.remove(friendId);
}

void CoreFile::process(qint64 nowMs)
{
    QMutexLocker locker{coreLoopLock};
    QVector<QPair<uint32_t, qint64>> due;
    for (auto it = friendsBack.begin(); it != friendsBack.end();) {
        if (it.value() < 0) {
            it.value() = nowMs;
        }
        if (nowMs - it.value() >= offerAgainAfterMs) {
            due.append({it.key(), it.value()});
            it = friendsBack.erase(it);
        } else {
            ++it;
        }
    }
    for (const auto& back : due) {
        offerAgain(back.first, back.second);
    }
    QVector<uint64_t> ended;
    for (auto it = waitingSince.cbegin(); it != waitingSince.cend(); ++it) {
        if (it.value() >= 0 && nowMs - it.value() >= waitForOfferMs) {
            ended.append(it.key());
        }
    }
    for (const uint64_t key : ended) {
        qDebug() << "process: the sender of file" << (key >> 32) << ':' << (key & 0xffffffffu)
                 << "is back and did not offer it again";
        endWaitingFile(key);
    }
}

/**
 * KHANDAQ (0.2.57): the friend went offline. toxcore has forgotten every transfer with them before
 * it said so (break_files), so their numbers go to new transfers. A file between two people waits
 * for them under a number of its own: ours is offered again with the same file id when they are
 * back, theirs goes on when they offer it so. qTox ended them all as broken.
 */
void CoreFile::keepForFriend(uint32_t friendId)
{
    for (const uint64_t key : fileMap.keys()) {
        if (key >> 32 != friendId) {
            continue;
        }
        ToxFile file = fileMap.value(key);
        if (file.connectionLost) {
            // It waits already, and waits for them again.
            if (waitingSince.contains(key)) {
                waitingSince.insert(key, -1);
            }
            continue;
        }
        const bool quiet = quietFiles.contains(key);
        removeFile(friendId, file.fileNum);
        if (file.fileKind == TOX_FILE_KIND_AVATAR || quiet) {
            continue;
        }
        if (file.fileKind != TOX_FILE_KIND_DATA) {
            // toxcore keeps a file of its second version (FTV2), but we cannot go on with one.
            file.status = ToxFile::BROKEN;
            emit fileTransferBrokenUnbroken(file, true);
            continue;
        }
        file.connectionLost = true;
        file.progress.resetSpeed();
        if (file.direction == ToxFile::RECEIVING && !file.filePath.isEmpty()) {
            // What its part is once closed: an offer of it later goes on only in that file.
            const ToxFile::Stamp cut = Air::fileStampAt(file.filePath);
            file.stamp.bytes = cut.bytes;
            file.stamp.modifiedMs = cut.modifiedMs;
        }
        file.fileNum = waitingNumberBase | (nextWaitingNumber++ & ~waitingNumberBase);
        const uint64_t waitingKey = getFriendKey(friendId, file.fileNum);
        fileMap.insert(waitingKey, file);
        if (file.direction == ToxFile::RECEIVING) {
            waitingSince.insert(waitingKey, -1);
        }
        qDebug() << "keepForFriend: file" << friendId << ':' << key << "waits for the friend at"
                 << file.progress.getBytesSent() << "of" << file.progress.getFileSize();
        emit fileTransferBrokenUnbroken(file, true);
    }
}

/** KHANDAQ (0.2.57): the friend is back. Ours that waited are offered again with their own id. */
void CoreFile::offerAgain(uint32_t friendId, qint64 backMs)
{
    for (const uint64_t key : fileMap.keys()) {
        if (key >> 32 != friendId || !fileMap.value(key).connectionLost) {
            continue;
        }
        if (fileMap.value(key).direction == ToxFile::RECEIVING) {
            // Theirs: the sender offers it again, if it can; it is not waited for for ever.
            if (waitingSince.value(key, -1) < 0) {
                waitingSince.insert(key, backMs);
            }
            continue;
        }
        ToxFile file = fileMap.value(key);
        // Only the file that went goes again under its id, and only one that can be read: the
        // receiver would go on with another, or wait for bytes that never come. Its card says it
        // was not sent; Send again offers it anew.
        if (!Air::isSameSource(file.stamp, static_cast<qint64>(file.progress.getFileSize()),
                               Air::fileStampAt(file.filePath))
            || !file.open(false)) {
            qWarning() << "offerAgain: file" << friendId << ':' << (key & 0xffffffffu)
                       << "changed or cannot be read, not offered again";
            removeFile(friendId, file.fileNum);
            file.connectionLost = false;
            file.status = ToxFile::BROKEN;
            emit fileTransferBrokenUnbroken(file, true);
            continue;
        }
        ToxString fileName(file.fileName);
        Tox_Err_File_Send sendErr;
        const uint32_t fileNum =
            tox_file_send(tox, friendId, TOX_FILE_KIND_DATA, file.progress.getFileSize(),
                          toxFileId(file.resumeFileId), fileName.data(), fileName.size(), &sendErr);
        if (!PARSE_ERR(sendErr)) {
            // It waits for the next time they are back.
            file.file->close();
            continue;
        }
        fileMap.remove(key);
        file.fileNum = fileNum;
        file.connectionLost = false;
        file.continued = true;
        file.status = ToxFile::INITIALIZING;
        file.pauseStatus.remoteResume();
        // It goes on from where the receiver asks (onFileDataCallback); its progress and hash stay
        // until then, and both go on if that is where it stopped.
        addFile(friendId, fileNum, file);
        qDebug() << "offerAgain: file" << friendId << ':' << fileNum
                 << "offered again with its own id";
        emit fileSendStarted(file);
    }
}

/**
 * KHANDAQ (0.2.57): the offer of a file of the person that waits here for them, the same file id
 * and size. It goes on in its entry: from what came, by itself, when it was accepted; otherwise it
 * waits for Accept again.
 */
bool CoreFile::takeWaitingFile(const ToxFile& offer)
{
    uint64_t waitingKey = 0;
    bool found = false;
    QVector<uint64_t> replaced;
    for (auto it = fileMap.cbegin(); it != fileMap.cend(); ++it) {
        const ToxFile& waiting = it.value();
        if ((it.key() >> 32) != offer.friendId || !waiting.connectionLost
            || waiting.direction != ToxFile::RECEIVING) {
            continue;
        }
        if (waiting.resumeFileId == offer.resumeFileId) {
            waitingKey = it.key();
            found = waiting.progress.getFileSize() == offer.progress.getFileSize();
        } else if (waiting.fileName == offer.fileName
                   && waiting.progress.getFileSize() == offer.progress.getFileSize()) {
            // Sent again under a new id, as an older client does: this one ends now.
            replaced.append(it.key());
        }
    }
    for (const uint64_t key : replaced) {
        qDebug() << "takeWaitingFile: the sender offers file" << (key & 0xffffffffu)
                 << "again under a new id";
        endWaitingFile(key);
    }
    if (!found) {
        return false;
    }

    ToxFile file = fileMap.value(waitingKey);
    fileMap.remove(waitingKey);
    waitingSince.remove(waitingKey);
    file.fileNum = offer.fileNum;
    file.connectionLost = false;
    file.continued = true;
    file.pauseStatus.remoteResume();
    const bool accepted = file.status == ToxFile::TRANSMITTING || file.status == ToxFile::PAUSED;
    addFile(offer.friendId, offer.fileNum, file);
    ToxFile* const taken = findFile(offer.friendId, offer.fileNum);
    if (accepted) {
        Air::FileHere here;
        here.id = file.resumeFileId;
        here.size = static_cast<qint64>(file.progress.getFileSize());
        here.status = file.status;
        here.accepted = true;
        // KHANDAQ (0.2.57): it goes on only in the part it left, as it left it, which no other
        // transfer has; otherwise from the start under a free name beside it.
        const uint64_t takenKey = getFriendKey(offer.friendId, offer.fileNum);
        const auto inUse = [this, takenKey](const QString& path) {
            return QFileInfo::exists(path) || otherFileAt(path, takenKey);
        };
        const ToxFile::Stamp partial = Air::fileStampAt(file.filePath);
        here.bytes = qMax<qint64>(0, partial.bytes);
        here.ownPart = Air::isOwnPart(file.stamp, partial) && !otherFileAt(file.filePath, takenKey);
        const Air::FileResume resume =
            Air::fileResume(offer.resumeFileId, static_cast<qint64>(offer.progress.getFileSize()),
                            here);
        if (resume.newPath) {
            const QFileInfo old(file.filePath);
            taken->setFilePath(Air::freeFilePath(old.absolutePath(), old.fileName(), inUse));
        }
        qDebug() << "takeWaitingFile: file" << offer.friendId << ':' << offer.fileNum
                 << "goes on from byte" << resume.offset;
        switch (startReceiving(*taken, static_cast<uint64_t>(resume.offset))) {
        case Receiving::Started:
            if (taken->status == ToxFile::PAUSED) {
                emit fileTransferPaused(*taken);
            } else {
                emit fileTransferAccepted(*taken);
            }
            return true;
        case Receiving::Failed:
            stopReceiving(offer.friendId, offer.fileNum);
            return true;
        case Receiving::Offered:
            break;
        }
    }
    // It waits for Accept in its entry, as an offer does.
    taken->status = ToxFile::INITIALIZING;
    taken->progress = ToxFileProgress(taken->progress.getFileSize());
    emit fileReceiveRequested(*taken);
    return true;
}

CoreFile::Receiving CoreFile::startReceiving(ToxFile& file, uint64_t offset)
{
    // KHANDAQ (0.2.57): what is here is touched only once toxcore takes it from the offset, and
    // nothing is written elsewhere: what comes goes where toxcore says (onFileRecvChunkCallback).
    if (offset > 0) {
        Tox_Err_File_Seek seekErr;
        tox_file_seek(tox, file.friendId, file.fileNum, offset, &seekErr);
        if (seekErr != TOX_ERR_FILE_SEEK_OK) {
            qWarning() << "startReceiving: seek failed with" << seekErr << ", nothing touched";
            return Receiving::Offered;
        }
    }
    // Past a seek, toxcore takes it from the offset only: anything that fails ends it.
    const Receiving failed = offset > 0 ? Receiving::Failed : Receiving::Offered;
    file.file->close();
    if (!file.openAt(offset)) {
        qWarning() << "startReceiving: Unable to open file" << file.filePath
                   << file.file->errorString();
        return failed;
    }
    // From the start the file is made anew, past it it is its own part: either way what tells it
    // is when it was made, and no cut of it holds any more.
    file.stamp = ToxFile::Stamp();
    file.stamp.bornMs = Air::fileStampAt(file.filePath).bornMs;
    // The hash goes on only with all that came before; from the start it is a new one.
    if (offset == 0) {
        file.hashGenerator = std::make_shared<QCryptographicHash>(QCryptographicHash::Sha256);
    } else if (offset != file.progress.getBytesSent()) {
        file.hashGenerator.reset();
    }
    file.progress = ToxFileProgress(file.progress.getFileSize());
    file.progress.addSample(offset);
    if (file.pauseStatus.localPaused()) {
        // Paused here: Resume accepts it (pauseResumeFile).
        file.status = ToxFile::PAUSED;
        return Receiving::Started;
    }
    Tox_Err_File_Control err;
    tox_file_control(tox, file.friendId, file.fileNum, TOX_FILE_CONTROL_RESUME, &err);
    if (!PARSE_ERR(err)) {
        file.file->close();
        return failed;
    }
    file.status = ToxFile::TRANSMITTING;
    return Receiving::Started;
}

void CoreFile::stopReceiving(uint32_t friendId, uint32_t fileId)
{
    const ToxFile* const found = findFile(friendId, fileId);
    if (!found) {
        return;
    }
    ToxFile file = *found;
    Tox_Err_File_Control err;
    tox_file_control(tox, friendId, fileId, TOX_FILE_CONTROL_CANCEL, &err);
    PARSE_ERR(err);
    removeFile(friendId, fileId);
    file.status = ToxFile::BROKEN;
    emit fileTransferBrokenUnbroken(file, true);
}

bool CoreFile::otherFileAt(const QString& path, uint64_t key) const
{
    for (auto it = fileMap.cbegin(); it != fileMap.cend(); ++it) {
        if (it.key() != key && !path.isEmpty() && it.value().filePath == path) {
            return true;
        }
    }
    return false;
}

void CoreFile::endWaitingFile(uint64_t key)
{
    const auto found = fileMap.constFind(key);
    if (found == fileMap.cend()) {
        waitingSince.remove(key);
        return;
    }
    ToxFile file = found.value();
    removeFile(static_cast<uint32_t>(key >> 32), file.fileNum);
    // What came of it stays at its path: its entry says stopped, and an offer later goes on.
    file.connectionLost = false;
    file.status = ToxFile::BROKEN;
    emit fileTransferBrokenUnbroken(file, true);
}
