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

#include "genericchatform.h"

class NotesChat;
class NotesDispatcher;

/**
 * Saved Messages (screen 16): the conversation with yourself. The same transcript and field as a
 * chat, without authors, marks, calls or attachments; the line under the field says that a note
 * stays only on this device. A note is deleted from the menu of the transcript, after one
 * confirmation.
 */
class NotesForm : public GenericChatForm
{
    Q_OBJECT
public:
    NotesForm(const Core& core, NotesChat& chat, IChatLog& chatLog, NotesDispatcher& dispatcher,
              DocumentCache& documentCache, SmileyPack& smileyPack, Settings& settings, Style& style,
              IMessageBoxManager& messageBoxManager, FriendList& friendList, GroupList& groupList);
    ~NotesForm() override;

    /** Asks once whether to delete the note the log shows at idx, then deletes it. */
    void askToDelete(ChatLogIdx idx);

protected:
    void prepareChatMenu(QPoint globalPos) override;

private slots:
    void onAttachClicked() override;
    void onScreenshotClicked() override;

private:
    void retranslateUi();

    NotesChat& chat;
    NotesDispatcher& dispatcher;
    QAction* deleteAction = nullptr;
    /** The note under the menu while it is open. */
    ChatLogIdx menuNote{0};
};
