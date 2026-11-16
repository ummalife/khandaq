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


#pragma once

#include <tox/tox.h>

#include "toxfile.h"
#include "src/core/core.h"
#include "src/core/toxpk.h"
#include "src/model/status.h"

#include "util/compatiblerecursivemutex.h"

#include <QHash>
#include <QMutex>
#include <QObject>
#include <QSet>
#include <QString>

#include <cstddef>
#include <cstdint>
#include <memory>

struct Tox;
class CoreFile;

using CoreFilePtr = std::unique_ptr<CoreFile>;

class CoreFile : public QObject
{
    Q_OBJECT

public:
    void handleAvatarOffer(uint32_t friendId, uint32_t fileId, bool accept, uint64_t filesize);
    static CoreFilePtr makeCoreFile(Core* core, Tox* tox, CompatibleRecursiveMutex& coreLoopLock);

    /**
     * fileId: KHANDAQ (0.2.57), the Tox file id the file had before (Send again), so its receiver
     * can go on with what it has of it; empty for a new file, which toxcore gives an id of its own.
     */
    void sendFile(uint32_t friendId, QString filename, QString filePath, long long filesize,
                  const QByteArray& fileId = QByteArray());
    void sendAvatarFile(uint32_t friendId, const QByteArray& data);
    void pauseResumeFile(uint32_t friendId, uint32_t fileId);
    void cancelFileSend(uint32_t friendId, uint32_t fileId);

    void cancelFileRecv(uint32_t friendId, uint32_t fileId);
    void rejectFileRecvRequest(uint32_t friendId, uint32_t fileId);
    void acceptFileRecvRequest(uint32_t friendId, uint32_t fileId, QString path);
    /**
     * KHANDAQ (0.2.57): accepts an offer of a file partly here (Air::fileResume): toxcore seeks to
     * offset before the accept, and what comes is appended to path from there.
     */
    void resumeFileRecv(uint32_t friendId, uint32_t fileId, QString path, uint64_t offset);
    /**
     * KHANDAQ (0.2.57): accepts an offer of a file that is all here already from offset, its last
     * byte, so both sides end; nothing is written and nothing is signalled for it.
     */
    void finishFileRecvQuietly(uint32_t friendId, uint32_t fileId, uint64_t offset);

    unsigned corefileIterationInterval();
    /**
     * KHANDAQ (0.2.57): the friend was removed, and toxcore forgot their transfers without a word
     * (m_delfriend). Nothing of theirs is kept, waiting or not: a friend added later can get their
     * number, and none of it is offered to that friend or taken for one of theirs.
     */
    void forgetFriend(uint32_t friendId);
    /**
     * KHANDAQ (0.2.57): called by the core loop after tox_iterate, with its lock held. Offers again
     * our files that waited for a friend who is back, and ends as stopped those of a friend who is
     * back and offered nothing again for a while: an older client that cannot go on.
     */
    void process(qint64 nowMs);

    /**
     * KHANDAQ (0.2.57): how long after a friend is back our files that waited are offered to them
     * again. 0.2.56 and older qTox, on the status every connection brings, mark each file of that
     * friend as going and forget it, on their GUI thread a moment after the status came: an offer
     * that reached them first was taken so, showed no Accept and never went on.
     */
    static constexpr qint64 offerAgainAfterMs = 2000;

signals:
    void fileSendStarted(ToxFile file);
    void fileReceiveRequested(ToxFile file);
    void fileTransferAccepted(ToxFile file);
    void fileTransferCancelled(ToxFile file);
    void fileTransferFinished(ToxFile file);
    void fileTransferPaused(ToxFile file);
    void fileTransferInfo(ToxFile file);
    void fileTransferRemotePausedUnpaused(ToxFile file, bool paused);
    void fileTransferBrokenUnbroken(ToxFile file, bool broken);
    void fileSendFailed(uint32_t friendId, const QString& fname);

private:
    CoreFile(Tox* core_, CompatibleRecursiveMutex& coreLoopLock_);

    ToxFile* findFile(uint32_t friendId, uint32_t fileId);
    void addFile(uint32_t friendId, uint32_t fileId, const ToxFile& file);
    void removeFile(uint32_t friendId, uint32_t fileId);
    /** The transfers of a friend who went offline wait for them, under numbers of their own. */
    void keepForFriend(uint32_t friendId);
    /** backMs: when the friend came back (monotonic ms), from which theirs wait for an offer. */
    void offerAgain(uint32_t friendId, qint64 backMs);
    /** An offer that goes on with a file that waits for its sender; false when none waits. */
    bool takeWaitingFile(const ToxFile& offer);
    /** Seeks to offset, opens the file there and accepts the offer, unless it is paused here. */
    bool startReceiving(ToxFile& file, uint64_t offset);
    /** Ends an incoming file that waited for its sender as stopped, keeping what came of it. */
    void endWaitingFile(uint64_t key);
    static constexpr uint64_t getFriendKey(uint32_t friendId, uint32_t fileId)
    {
        return (static_cast<std::uint64_t>(friendId) << 32) + fileId;
    }

    static void connectCallbacks(Tox& tox);
    static void onFileReceiveCallback(Tox* tox, uint32_t friendId, uint32_t fileId, uint32_t kind,
                                      uint64_t filesize, const uint8_t* fname, size_t fnameLen,
                                      void* vCore);
    static void onFileControlCallback(Tox* tox, uint32_t friendId, uint32_t fileId,
                                      Tox_File_Control control, void* vCore);
    static void onFileDataCallback(Tox* tox, uint32_t friendId, uint32_t fileId, uint64_t pos,
                                   size_t length, void* vCore);
    static void onFileRecvChunkCallback(Tox* tox, uint32_t friendId, uint32_t fileId, uint64_t position,
                                        const uint8_t* data, size_t length, void* vCore);

    static QString getCleanFileName(QString filename);

private slots:
    /** Called on the core thread, in tox_iterate (Qt::DirectConnection). */
    void onFriendLinkChanged(uint32_t friendId, int toxConnection);

private:
    QHash<uint64_t, ToxFile> fileMap;
    /**
     * Friends back online, whose files that waited process() offers again offerAgainAfterMs later:
     * when they came back (monotonic ms), or -1 until process() has seen them.
     */
    QHash<uint32_t, qint64> friendsBack;
    /**
     * Incoming files that wait for their sender, by key: when the sender came back (monotonic ms),
     * or -1 while they are offline.
     */
    QHash<uint64_t, qint64> waitingSince;
    /** Incoming files that are all here already: their last byte comes once more, unseen. */
    QSet<uint64_t> quietFiles;
    /** Numbers for files that wait, which toxcore never gives (it gives below 1 << 24). */
    uint32_t nextWaitingNumber = 0;
    Tox* tox;
    CompatibleRecursiveMutex* coreLoopLock = nullptr;
};
