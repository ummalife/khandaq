/*
    Copyright © 2019 by The qTox Project Contributors

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

#include "src/core/toxfilepause.h"
#include "src/core/toxfileprogress.h"

#include <QString>
#include <memory>
#include <QCryptographicHash>

class QFile;
class QTimer;

struct ToxFile
{
    // Note do not change values, these are directly inserted into the DB in their
    // current form, changing order would mess up database state!
    enum FileStatus
    {
        INITIALIZING = 0,
        PAUSED = 1,
        TRANSMITTING = 2,
        BROKEN = 3,
        CANCELED = 4,
        FINISHED = 5,
    };

    // Note do not change values, these are directly inserted into the DB in their
    // current form (can add fields though as db representation is an int)
    enum FileDirection : bool
    {
        SENDING = 0,
        RECEIVING = 1,
    };

    /**
     * KHANDAQ (0.2.57): what tells the file a file of the person goes to from another one at its
     * path (Air::isOwnPart), before anything of it goes on there. -1 where it is not known.
     */
    struct Stamp
    {
        /** When the file was made (its birth time), in ms since the epoch. */
        qint64 bornMs = -1;
        /** Its length and its last change, in ms since the epoch, when its transfer was cut. */
        qint64 bytes = -1;
        qint64 modifiedMs = -1;

        bool operator==(const Stamp& other) const
        {
            return bornMs == other.bornMs && bytes == other.bytes
                   && modifiedMs == other.modifiedMs;
        }
        bool operator!=(const Stamp& other) const
        {
            return !(*this == other);
        }
    };

    ToxFile();
    ToxFile(uint32_t fileNum_, uint32_t friendId_, QString fileName_, QString filePath_,
            uint64_t filesize, FileDirection direction);

    bool operator==(const ToxFile& other) const;
    bool operator!=(const ToxFile& other) const;

    void setFilePath(QString path);
    bool open(bool write);
    /**
     * Opens the file to receive into from offset on: what is there before it stays and anything
     * after it goes. At 0 it is open(true).
     */
    bool openAt(uint64_t offset);

    uint8_t fileKind;
    uint32_t fileNum;
    uint32_t friendId;
    QString fileName;
    QString filePath;
    std::shared_ptr<QFile> file;
    FileStatus status;
    FileDirection direction;
    QByteArray avatarData;
    QByteArray resumeFileId;
    std::shared_ptr<QCryptographicHash> hashGenerator = std::make_shared<QCryptographicHash>(QCryptographicHash::Sha256);
    ToxFilePause pauseStatus;
    ToxFileProgress progress;
    /**
     * KHANDAQ (0.2.57): the friend went offline in the middle of it. toxcore has forgotten it then,
     * but it stays where it was, waiting for them; it goes on by itself when they are back.
     */
    bool connectionLost = false;
    /**
     * KHANDAQ (0.2.57): an offer of a file that already has its entry in the chat (the same Tox
     * file id): it goes on in that entry instead of making a new one.
     */
    bool continued = false;
    /**
     * KHANDAQ (0.2.57): the person cancelled or declined this file of theirs here, or the history
     * keeps it as cancelled (it does not say who stopped it). An offer of it again waits in its
     * entry for the person, never coming by itself, until they accept it.
     */
    bool refusedHere = false;
    /**
     * KHANDAQ (inbox #36): its sender cancelled this file of theirs before it was whole here
     * (their CANCEL), so its entry says so instead of "The download stopped". For this run of the
     * app only: the history keeps no word of who stopped a file; an offer of it again is a new
     * ToxFile, without it.
     */
    bool cancelledThere = false;
    /**
     * KHANDAQ (0.2.57): a file of a group that a member relayed (history sync) instead of its
     * sender: toxcore vouches for nobody, so the key its entry names is what that member says. It
     * is never taken for ours, and a Report on it names no author.
     */
    bool relayed = false;
    /** KHANDAQ (0.2.57): of a file of the person, what tells the file it goes to (Stamp). */
    Stamp stamp;
};
