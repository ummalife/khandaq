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
#include "src/core/toxpk.h"

#include <QByteArray>
#include <QDateTime>
#include <QHash>
#include <QSet>
#include <QString>
#include <QUrl>
#include <QVector>

#include <functional>

namespace Air {

class LocalStore;

/**
 * KHANDAQ (0.2.57): Report, on a message someone wrote in a group, any group (SPEC-community,
 * part B, item 5): a mail to support for the person to send from their own mail app. App Review
 * asks for it on iOS (guideline 1.2, content from strangers), and every platform has it.
 */
struct GroupReport
{
    QString groupName;
    /**
     * The author's key in the group, in hexadecimal; the mail names its first 8 characters. Empty
     * when nobody vouches for it, as for a file a member relayed: the mail says unknown.
     */
    QString authorKey;
    /** When the message was written. */
    QDateTime time;
    QString text;
};

/** Where reports go. */
constexpr char reportAddress[] = "support@khandaq.org";
/**
 * The text of the message goes up to this many characters, as a person counts them: a flag, an
 * emoji joined of several or a letter with its marks is one, and none is cut in half.
 */
constexpr int reportTextLimit = 500;

/**
 * mailto: reportAddress, with a subject that names the group and a body with the group, the
 * author, the time in UTC and the text, each part percent-encoded and its lines ended with CRLF.
 * The words of the mail are support's, in English: the person reads them before sending.
 */
QUrl reportMailUrl(const GroupReport& report);

/**
 * KHANDAQ (0.2.57): Khandaq Community, the official group of the app (SPEC-community, part B).
 * Every profile joins it once by itself, muted, and never again once the person has left it;
 * otherwise it is a group like any other. Its founder is our bot on khandaq.org, whose state keeps
 * the chat id, so the id is compiled in.
 */
namespace Community {

/** The chat id of the group, 64 hexadecimal characters in upper case. */
constexpr char chatIdHex[] = "CFF1995F235303B88C4EF1CE903DCEDC77CEAC82DD1323DAB2CACD2EF840646F";

/** The chat id as toxcore takes it: 32 bytes. */
QByteArray chatId();
/** Whether a chat id (32 bytes) is that of Khandaq Community. */
bool isCommunity(const QByteArray& groupChatId);

/**
 * The name the group always shows, wherever the name of a group is shown: its row, its chat, the
 * dossier, notifications, the search and the Report mail (the owner, 2026-11-16). Neither its topic
 * nor its name in the group state changes it: the founder's topic once began "Official Khandaq
 * community", and the desktop takes the topic of a saved group for its name until the group state
 * comes (Core::loadGroups). Not translated: it is the name of the group.
 */
constexpr char name[] = "Khandaq Community";

/** The name a group shows: name for Khandaq Community, title as it is for any other group. */
QString groupTitle(const QByteArray& groupChatId, const QString& title);

/**
 * Whether to join it now: our node is connected, the profile never joined it by itself before
 * (autoJoinDone) nor left it (left), and it is not in it already, by hand or from an earlier join.
 */
bool shouldAutoJoin(bool connected, bool autoJoinDone, bool left, bool alreadyInGroup);

/**
 * Joins it through join when shouldAutoJoin says so, with the marks store keeps. join says whether
 * the group now exists here, toxcore having taken the join. Only then is the group muted and the
 * join marked done in store; a join that failed leaves store as it was, so the next start or
 * connection tries again. True when it joined.
 */
bool autoJoin(LocalStore& store, bool connected, bool alreadyInGroup,
              const std::function<bool()>& join);

/**
 * A group was left: when it is Khandaq Community it is never joined by itself again, however it
 * was joined. True when that changed store.
 */
bool noteGroupLeft(LocalStore& store, const QByteArray& groupChatId);

/**
 * A group is in this profile, however it came: by hand, by an invitation, from the save or by
 * itself. When it is Khandaq Community it is never joined by itself again, so the auto-join never
 * runs over a join by hand, nor after a rejoin of it fails and its row goes without a leave. True
 * when that changed store.
 */
bool noteGroupHere(LocalStore& store, const QByteArray& groupChatId);

/**
 * Whether a file that came whole in a group goes to the download folder at once, as in every
 * group, or waits for a tap: in Khandaq Community, where strangers write, the files of others
 * wait, whatever the download setting says (item 6). A relayed file (history sync) is never
 * fromSelf: the key it names is what the member who relayed it says, and every member sees ours.
 */
bool savesFileAtOnce(const QByteArray& groupChatId, bool fromSelf, bool relayed);

/**
 * The files of Khandaq Community that came and wait for a tap. The group file packets push every
 * byte to every member, so the files are here already: they are held in memory, out of the
 * download folder, their entries shown without a picture until Accept writes them. Up to
 * limitBytes together, the oldest going first to make room; none is kept across a restart.
 */
class HeldFiles
{
public:
    static constexpr qint64 limitBytes = 256LL * 1024 * 1024;

    struct File
    {
        QByteArray groupChatId;
        ToxPk sender;
        /** Its entry in the transcript, which waits; the number is unique while the app runs. */
        ToxFile entry;
        QByteArray data;
    };

    /**
     * Whether a file with this id (ToxFile::resumeFileId, the id in its packets) came in this
     * group while the app runs, held, written or gone. An id is one message: a file that comes
     * again under it, by history sync or from another member, is dropped before it has an entry,
     * or it would take over the entry and the history row of the first, and show its own bytes
     * under that entry's Accept.
     */
    bool known(const QByteArray& groupChatId, const QByteArray& fileId) const;
    /**
     * Holds a file whose id is not known, and returns those that went to make room for it, oldest
     * first: their entries stop. A file bigger than limitBytes alone is not held and comes back
     * itself. Its name plays no part: each file is its entry's, by number.
     */
    QVector<File> hold(const File& file);
    /**
     * The file of the entry with this number, left where it is, for Accept to write; false when
     * none is held.
     */
    bool find(uint32_t number, File& file) const;
    /** Takes out the file of the entry with this number, once written; false when none is held. */
    bool take(uint32_t number, File& file);
    /** The group is gone: its files go with it, and a join again starts over (known). */
    void forgetGroup(const QByteArray& groupChatId);
    qint64 bytes() const;
    int count() const;

private:
    /** The oldest first. */
    QVector<File> files;
    qint64 total = 0;
    /** The ids of the files that came, by group. */
    QHash<QByteArray, QSet<QByteArray>> knownIds;
};

} // namespace Community
} // namespace Air
