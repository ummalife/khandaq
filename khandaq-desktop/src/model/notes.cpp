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

#include "notes.h"

#include "src/core/icoreidhandler.h"
#include "src/model/sessionchatlog.h"
#include "src/persistence/outbox.h"
#include "src/widget/form/chatform.h"

void NotesChat::setName(const QString& name_)
{
    if (name == name_) {
        return;
    }
    name = name_;
    emit displayedNameChanged(name);
}

QString NotesChat::getDisplayedName() const
{
    return name;
}

QString NotesChat::getDisplayedName(const ToxPk& contact) const
{
    std::ignore = contact;
    return name;
}

uint32_t NotesChat::getId() const
{
    return 0;
}

const ChatId& NotesChat::getPersistentId() const
{
    return id;
}

void NotesChat::setEventFlag(bool flag)
{
    std::ignore = flag;
}

bool NotesChat::getEventFlag() const
{
    return false;
}

NotesDispatcher::NotesDispatcher(SavedNotes* notes_)
    : notes{notes_}
{
}

QString NotesDispatcher::storedText(bool isAction, const QString& content)
{
    return isAction ? ChatForm::ACTION_PREFIX + content : content;
}

std::pair<DispatchedMessageId, DispatchedMessageId> NotesDispatcher::sendMessage(bool isAction,
                                                                                 const QString& content)
{
    Message message;
    message.isAction = isAction;
    message.content = content;
    message.timestamp = QDateTime::currentDateTime();
    const qint64 noteId = notes ? notes->add(message.timestamp, storedText(isAction, content)) : -1;

    const DispatchedMessageId id = nextId++;
    if (log) {
        // The log puts the note at its next index as it takes messageSent below.
        noteIds[log->getNextIdx()] = noteId;
    }
    // Nothing travels: the note is written as soon as it is stored.
    emit messageSent(id, message);
    emit messageComplete(id);
    return {id, id};
}

bool NotesDispatcher::hasNote(ChatLogIdx idx) const
{
    return noteIds.find(idx) != noteIds.end();
}

bool NotesDispatcher::removeNote(ChatLogIdx idx)
{
    const auto found = noteIds.find(idx);
    if (found == noteIds.end() || !log) {
        return false;
    }
    if (notes && found->second >= 0) {
        notes->remove(found->second);
    }
    noteIds.erase(found);
    log->hideItem(idx);
    return true;
}

std::pair<DispatchedMessageId, DispatchedMessageId>
NotesDispatcher::sendExtendedMessage(const QString& content, ExtensionSet extensions)
{
    std::ignore = extensions;
    return sendMessage(false, content);
}

std::unique_ptr<SessionChatLog> makeNotesLog(SavedNotes* notes, NotesDispatcher& dispatcher,
                                             const ICoreIdHandler& selfIds, FriendList& friendList,
                                             GroupList& groupList)
{
    const QVector<SavedNotes::Note> stored = notes ? notes->all() : QVector<SavedNotes::Note>{};
    std::unique_ptr<SessionChatLog> log(
        new SessionChatLog(ChatLogIdx(static_cast<size_t>(stored.size())), selfIds, friendList,
                           groupList));
    const ToxPk self = selfIds.getSelfPublicKey();
    const QString selfName = selfIds.getUsername();
    for (int i = 0; i < stored.size(); ++i) {
        QString text = stored[i].text;
        const bool isAction = text.startsWith(ChatForm::ACTION_PREFIX, Qt::CaseInsensitive);
        if (isAction) {
            text.remove(0, ChatForm::ACTION_PREFIX.size());
        }
        ChatLogMessage note;
        note.state = MessageState::complete;
        note.message.isAction = isAction;
        note.message.content = text;
        note.message.timestamp = stored[i].timestamp;
        const ChatLogIdx idx(static_cast<size_t>(i));
        log->insertCompleteMessageAtIdx(idx, self, selfName, note);
        dispatcher.noteIds[idx] = stored[i].id;
    }

    SessionChatLog* const raw = log.get();
    dispatcher.log = raw;
    QObject::connect(&dispatcher, &IMessageDispatcher::messageSent, raw,
                     &SessionChatLog::onMessageSent);
    QObject::connect(&dispatcher, &IMessageDispatcher::messageComplete, raw,
                     &SessionChatLog::onMessageComplete);
    return log;
}
