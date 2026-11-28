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
        /**
         * "You" for our own messages; the author's name otherwise, its first word in a chat with a
         * person and all of it in a group (Air::authorName).
         */
        QString who;
        /** The author's tone, Air::youTone for our own messages; -1 signs the entry in ink-2. */
        int tone = -1;
        /** Our own messages to a person and our texts in a group carry a state mark. */
        bool hasMark = false;
        Air::EntryStatus status = Air::EntryStatus::None;
        /** Who wrote it, as a screen reader says it: "You" or the whole name; empty in notes. */
        QString spokenWho;
        /**
         * KHANDAQ (26.11.2026): a private message of a group, what its first line says of who gets
         * it ("Privately to Maryam: other members don't see this"); empty for any other entry.
         */
        QString privateNote;
        /** KHANDAQ (26.11.2026): its mention block names the key the group knows us by. */
        bool mentionsYou = false;
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
    /**
     * KHANDAQ (beta ideas 2026-11-20): "Unread messages", above the first entry that came while the
     * conversation was not in front (ChatWidget::markUnread).
     */
    static ChatMessage::Ptr createUnreadMark(const QString& text, DocumentCache& documentCache,
                                             Settings& settings, Style& style);
    /** The text font of the transcript: the design's body text, unless the user chose a font. */
    static QFont bodyFont(Settings& settings);
    static ChatMessage::Ptr createTypingNotification(DocumentCache& documentCache, Settings& settings, Style& style);
    static ChatMessage::Ptr createBusyNotification(DocumentCache& documentCache, Settings& settings, Style& style);

    /**
     * Changes the state mark of one of our messages. KHANDAQ (owner, 28.11.2026): returns whether
     * the mark came or went (AirMark::isShown), so the time at the end of the entry moves.
     */
    bool setStatus(Air::EntryStatus status);
    /**
     * Shows what changed on a message since it was drawn (stage 3, item 10): the new words of an
     * edit, its type (KHANDAQ, beta feedback 2026-11-22: a saved message edited into an action or
     * out of one), and the marks. Returns whether the entry may have changed its height.
     */
    bool updateMessage(const QString& rawMessage, MessageType type, const Air::EntryMarks& marks,
                       SmileyPack& smileyPack);
    /** Shows the marks of an entry anew; returns whether it may have changed its height. */
    bool updateMarks(const Air::EntryMarks& marks);
    /**
     * KHANDAQ (0.2.63): the author over the entry as look has them now: a member a group relayed an
     * entry of before it knew them takes their name once it does (ChatHistory::nameUnnamedAuthors).
     * @return Whether it changed, and the line may with it
     */
    bool updateAuthor(const AirLook& look);
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
    /**
     * The text of a message entry, content 1: the words rawMessage shows (AirStrings::messageWords),
     * sender signs an action. KHANDAQ (26.11.2026): each @handle its mention block names stands out
     * as a link to the member (Air::mentionLink). KHANDAQ (28.11.2026): a place a phone shared is
     * its card, its map and its coordinates (AirLocationCard).
     */
    static ChatLineContent* createBody(const QString& rawMessage, const QString& sender,
                                       MessageType type, DocumentCache& documentCache,
                                       SmileyPack& smileyPack, Settings& settings, Style& style);


    bool action = false;
    QString spokenWho;
    bool mentionsYou = false;
    /** What a message entry was drawn from, to draw its words again after an edit. */
    QString rawText;
    QString senderName;
    MessageType messageType = NORMAL;
    /** A message, whose author may change it: its time says "edited" then. A file is not. */
    bool editable = false;
    DocumentCache& documentCache;
    Settings& settings;
    Style& style;
};
