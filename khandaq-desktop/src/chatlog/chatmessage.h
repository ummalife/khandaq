/*
    Copyright © 2014-2019 by The qTox Project Contributors

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

#include "chatline.h"
#include "src/core/toxfile.h"
#include "src/model/air/airconversation.h"
#include "src/model/air/airmessagemarks.h"
#include "src/persistence/history.h"

#include <QDateTime>

class CoreFile;
class QGraphicsScene;
class DocumentCache;
class Text;
class SmileyPack;
class Settings;
class Style;
class IMessageBoxManager;

class ChatMessage : public ChatLine
{
public:
    using Ptr = std::shared_ptr<ChatMessage>;

    enum SystemMessageType
    {
        INFO,
        ERROR,
        TYPING,
    };

    enum MessageType
    {
        NORMAL,
        ACTION,
        ALERT,
    };

    ChatMessage(DocumentCache& documentCache, Settings& settings, Style& style);
    ~ChatMessage();
    ChatMessage(const ChatMessage&) = default;
    ChatMessage(ChatMessage&&) = default;

    /** Who wrote an entry of the transcript, and the state of our own messages. */
    struct AirLook
    {
        /** "You" for our own messages, the first word of the author's name otherwise. */
        QString who;
        /** The author's tone; -1 signs the entry in ink-2, as our own messages are signed. */
        int tone = -1;
        /** Our own messages to a person carry a state mark; others have none. */
        bool hasMark = false;
        Air::EntryStatus status = Air::EntryStatus::None;
        /** Who wrote it, as a screen reader says it: "You" or the whole name; empty in notes. */
        QString spokenWho;
    };

    /**
     * A message: the words it shows (Air::readMessageText, or the sentence for a form this version
     * cannot read), the quote of a reply above them, and its marks (an edit by its author, the
     * reactions on it) under them.
     */
    static ChatMessage::Ptr createChatMessage(const AirLook& look, const QString& sender,
                                              const QString& rawMessage, MessageType type,
                                              const QDateTime& date, const Air::EntryMarks& marks,
                                              DocumentCache& documentCache, SmileyPack& smileyPack,
                                              Settings& settings, Style& style);
    static ChatMessage::Ptr createChatInfoMessage(const QString& rawMessage, SystemMessageType type,
                                                  const QDateTime& date, DocumentCache& documentCache, Settings& settings,
                                                  Style& style);
    static ChatMessage::Ptr createFileTransferMessage(
        const AirLook& look, CoreFile& coreFile, ToxFile file, const QDateTime& date,
        const Air::EntryMarks& marks, DocumentCache& documentCache, SmileyPack& smileyPack,
        Settings& settings, Style& style, IMessageBoxManager& messageBoxManager);
    /** A call: the phone glyph and its words ("Call, 4:12", "Missed call"), without an author. */
    static ChatMessage::Ptr createCallEntry(const QString& words, bool answered,
                                            const QDateTime& date, DocumentCache& documentCache,
                                            Settings& settings, Style& style);
    /** "Today", "Yesterday" or the date, above the first entry of a day. */
    static ChatMessage::Ptr createDayMark(const QString& text, DocumentCache& documentCache,
                                          Settings& settings, Style& style);
    /** The text font of the transcript: the design's body text, unless the user chose a font. */
    static QFont bodyFont(Settings& settings);
    static ChatMessage::Ptr createTypingNotification(DocumentCache& documentCache, Settings& settings, Style& style);
    static ChatMessage::Ptr createBusyNotification(DocumentCache& documentCache, Settings& settings, Style& style);

    /** Changes the state mark of one of our messages. */
    void setStatus(Air::EntryStatus status);
    /**
     * Shows what changed on a message since it was drawn (stage 3, item 10): the new words of an
     * edit and the marks. Returns whether the entry may have changed its height.
     */
    bool updateMessage(const QString& rawMessage, const Air::EntryMarks& marks, SmileyPack& smileyPack);
    /** Shows the marks of an entry anew; returns whether it may have changed its height. */
    bool updateMarks(const Air::EntryMarks& marks);
    /**
     * An entry as iOS says it: who wrote it and when, its words, that it mentions you, the state
     * of a message of ours; a day mark says its day.
     */
    QString spokenText() const override;
    QString toString() const;
    bool isAction() const;
    void setAsAction();
    void hideSender();
    void hideDate();

protected:
    static QString detectQuotes(const QString& str, MessageType type);
    static QString wrapDiv(const QString& str, const QString& div);

private:
    /** The text of a message entry, content 1: words is what it shows, sender signs an action. */
    static Text* createBody(const QString& words, const QString& sender, MessageType type,
                            DocumentCache& documentCache, SmileyPack& smileyPack,
                            Settings& settings, Style& style);

    bool action = false;
    QString spokenWho;
    bool mentionsYou = false;
    /** What a message entry was drawn from, to draw its words again after an edit. */
    QString rawText;
    QString senderName;
    MessageType messageType = NORMAL;
    DocumentCache& documentCache;
    Settings& settings;
    Style& style;
};
