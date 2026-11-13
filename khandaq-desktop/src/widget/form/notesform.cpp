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

#include "notesform.h"

#include "src/chatlog/chatwidget.h"
#include "src/model/air/airconversation.h"
#include "src/model/notes.h"
#include "src/widget/air/aircomposer.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/chatformheader.h"
#include "src/widget/translator.h"

#include <QAction>

NotesForm::NotesForm(const Core& core_, NotesChat& chat_, IChatLog& chatLog_,
                     NotesDispatcher& dispatcher_, DocumentCache& documentCache_,
                     SmileyPack& smileyPack_, Settings& settings_, Style& style_,
                     IMessageBoxManager& messageBoxManager_, FriendList& friendList_,
                     GroupList& groupList_)
    : GenericChatForm(core_, &chat_, chatLog_, dispatcher_, documentCache_, smileyPack_, settings_,
                      style_, messageBoxManager_, friendList_, groupList_)
    , chat{chat_}
    , dispatcher{dispatcher_}
{
    menu.addSeparator();
    deleteAction = menu.addAction(QString(), this, [this] { askToDelete(menuNote); });

    // Nobody to call and no dossier: a bookmark tile, the title and search.
    headWidget->setMode(ChatFormHeader::Mode::None);
    headWidget->setTile(QString(), 0, AirTile::Kind::Saved);
    headWidget->setNameEditable(false);
    composer->setAttachVisible(false);

    retranslateUi();
    Translator::registerHandler(std::bind(&NotesForm::retranslateUi, this), this);
}

NotesForm::~NotesForm()
{
    Translator::unregister(this);
}

void NotesForm::onAttachClicked()
{
    // Notes keep text only; the paperclip is hidden.
}

void NotesForm::onScreenshotClicked() {}

void NotesForm::prepareChatMenu(QPoint globalPos)
{
    ChatLogIdx idx{0};
    const bool onNote = chatWidget->entryAtGlobalPos(globalPos, idx) && dispatcher.hasNote(idx);
    deleteAction->setVisible(onNote);
    if (onNote) {
        menuNote = idx;
    }
}

void NotesForm::askToDelete(ChatLogIdx idx)
{
    if (!dispatcher.hasNote(idx)) {
        return;
    }
    AirDialog::confirm(this, AirStrings::text(AirStrings::Text::NoteDeleteTitle),
                       AirStrings::text(AirStrings::Text::NoteDeleteMessage),
                       AirStrings::text(AirStrings::Text::Delete), AirDialog::Verb::Danger,
                       [this, idx] {
                           chatWidget->clearSelection();
                           dispatcher.removeNote(idx);
                       });
}

void NotesForm::retranslateUi()
{
    chat.setName(AirStrings::text(AirStrings::Text::SavedTitle));
    setName(chat.getDisplayedName());
    const Air::ComposeHint hint = Air::ComposeHint::make(true, true, Air::Reach::local());
    composer->setHint(AirStrings::composeHintText(hint, QString()), hint.dot);
    deleteAction->setText(AirStrings::text(AirStrings::Text::NoteDelete));
}
