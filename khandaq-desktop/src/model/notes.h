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

#include "src/core/toxpk.h"
#include "src/model/chat.h"
#include "src/model/ichatlog.h"
#include "src/model/imessagedispatcher.h"

#include <map>
#include <memory>

class FriendList;
class GroupList;
class ICoreIdHandler;
class SavedNotes;
class SessionChatLog;

/**
 * Saved Messages (screen 16): notes to yourself. They are written into the profile database
 * (SavedNotes) and never leave this device: there is nobody on the other side.
 */

/** The conversation of the notes: a chat with no one, named by the interface. */
class NotesChat : public Chat
{
    Q_OBJECT
public:
    NotesChat() = default;

    void setName(const QString& name) override;
    QString getDisplayedName() const override;
    QString getDisplayedName(const ToxPk& contact) const override;
    uint32_t getId() const override;
    const ChatId& getPersistentId() const override;
    void setEventFlag(bool flag) override;
    bool getEventFlag() const override;

private:
    QString name;
    /** Notes belong to no Tox chat: the id stays empty. */
    ToxPk id;
};

/**
 * "Sends" a note: stores it and puts it into the log as written at once. A note has no length
 * limit and no delivery, so it is never split or queued.
 */
class NotesDispatcher : public IMessageDispatcher
{
    Q_OBJECT
public:
    /** Without a database (notes is null) the notes live until the app quits. */
    explicit NotesDispatcher(SavedNotes* notes);

    std::pair<DispatchedMessageId, DispatchedMessageId> sendMessage(bool isAction,
                                                                    const QString& content) override;
    std::pair<DispatchedMessageId, DispatchedMessageId>
    sendExtendedMessage(const QString& content, ExtensionSet extensions) override;

    /** The text a note keeps for an action: the same prefix as the history uses. */
    static QString storedText(bool isAction, const QString& content);

    /** Whether the log of the notes shows a note at idx that can be deleted. */
    bool hasNote(ChatLogIdx idx) const;
    /**
     * Deletes the note the log shows at idx: the database forgets it at once, the log hides it.
     * Returns false when there is no such note, also when it is deleted already.
     */
    bool removeNote(ChatLogIdx idx);

private:
    friend std::unique_ptr<SessionChatLog> makeNotesLog(SavedNotes* notes, NotesDispatcher& dispatcher,
                                                        const ICoreIdHandler& selfIds,
                                                        FriendList& friendList, GroupList& groupList);

    SavedNotes* notes;
    DispatchedMessageId nextId{0};
    /** The log the notes go to, set by makeNotesLog. */
    SessionChatLog* log = nullptr;
    /** The stored id of every note in the log by its index; -1 for a note without a database. */
    std::map<ChatLogIdx, qint64> noteIds;
};

/**
 * The log of the notes, filled with every stored note and following dispatcher from then on.
 * The notes are ours, so they are written by selfIds' key.
 */
std::unique_ptr<SessionChatLog> makeNotesLog(SavedNotes* notes, NotesDispatcher& dispatcher,
                                             const ICoreIdHandler& selfIds, FriendList& friendList,
                                             GroupList& groupList);
