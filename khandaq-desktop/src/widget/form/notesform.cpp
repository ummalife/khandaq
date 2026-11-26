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
#include "src/widget/form/chatform.h"
#include "src/widget/tool/chatquotepreview.h"
#include "src/widget/tool/chattextedit.h"
#include "src/widget/translator.h"

#include <QAction>
#include <QTextCursor>

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
    editAction = menu.addAction(QString(), this, [this] { startEdit(menuNote); });
    deleteAction = menu.addAction(QString(), this, [this] { askToDelete(menuNote); });
    // The cross of the bar, or a quote taking its place, ends the edit; Esc too.
    connect(quotePreview, &ChatQuotePreview::changed, this, [this] {
        if (editing && !quotePreview->isEdit()) {
            endEdit();
        }
    });
    connect(msgEdit, &ChatTextEdit::escapePressed, this, [this] {
        if (editing) {
            endEdit();
        }
    });

    // Nobody to call and no dossier: a bookmark tile, the title and search.
    headWidget->setMode(ChatFormHeader::Mode::None);
    headWidget->setTile(QString(), 0, AirTile::Kind::Saved);
    headWidget->setNameEditable(false);
    composer->setAttachVisible(false);
    // KHANDAQ (0.2.60): notes are text; a voice note has nobody to go to.
    composer->setVoiceVisible(false);

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
    editAction->setVisible(onNote);
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
                           // The note being edited goes, and the edit with it.
                           if (editing && editedNote == idx) {
                               endEdit();
                           }
                           chatWidget->clearSelection();
                           dispatcher.removeNote(idx);
                       });
}

void NotesForm::startEdit(ChatLogIdx idx)
{
    const QString text = dispatcher.editableText(idx);
    if (text.isEmpty()) {
        return;
    }
    if (!editing) {
        fieldBeforeEdit = msgEdit->toPlainText();
        quoteBeforeEdit = quotePreview->hasQuote() ? quotePreview->quoteText() : QString();
    }
    editing = true;
    editedNote = idx;
    quotePreview->setEdit(chatLog.at(idx).getContentAsMessage().message.content);
    composer->setSaving(true);
    msgEdit->setPlainText(text);
    msgEdit->moveCursor(QTextCursor::End);
    msgEdit->setFocus(Qt::OtherFocusReason);
}

void NotesForm::onSendTriggered()
{
    if (!editing) {
        GenericChatForm::onSendTriggered();
        return;
    }
    if (!canSendNow()) {
        return;
    }
    // As Send reads the field: the action prefix makes an action; without words nothing changes.
    QString words = msgEdit->toPlainText();
    const bool isAction = words.startsWith(ChatForm::ACTION_PREFIX, Qt::CaseInsensitive);
    if (isAction) {
        words.remove(0, ChatForm::ACTION_PREFIX.length());
    }
    if (words.trimmed().isEmpty()) {
        return;
    }
    // The same words leave the note as it was, without "edited".
    dispatcher.editNote(editedNote, isAction, words);
    endEdit();
}

void NotesForm::endEdit()
{
    if (!editing) {
        return;
    }
    editing = false;
    composer->setSaving(false);
    if (quotePreview->isEdit()) {
        quotePreview->clearQuote();
    }
    // The quote that waited before the edit comes back, unless a new one has taken the bar.
    if (!quoteBeforeEdit.isEmpty() && !quotePreview->hasQuote()) {
        quotePreview->setQuoteText(quoteBeforeEdit);
    }
    quoteBeforeEdit.clear();
    msgEdit->setPlainText(fieldBeforeEdit);
    msgEdit->moveCursor(QTextCursor::End);
    fieldBeforeEdit.clear();
}

void NotesForm::retranslateUi()
{
    chat.setName(AirStrings::text(AirStrings::Text::SavedTitle));
    setName(chat.getDisplayedName());
    const Air::ComposeHint hint = Air::ComposeHint::make(true, true, Air::Reach::local());
    composer->setHint(AirStrings::composeHintText(hint, QString()), hint.dot);
    editAction->setText(AirStrings::text(AirStrings::Text::NoteEdit));
    deleteAction->setText(AirStrings::text(AirStrings::Text::NoteDelete));
}
