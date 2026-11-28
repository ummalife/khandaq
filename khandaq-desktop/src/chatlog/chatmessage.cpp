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

#include "chatmessage.h"
#include "chatlinecontentproxy.h"
#include "textformatter.h"
#include "content/filetransferwidget.h"
#include "content/image.h"
#include "content/notificationicon.h"
#include "content/spinner.h"
#include "content/airlabel.h"
#include "content/airlocationcard.h"
#include "content/text.h"
#include "src/model/air/airlocation.h"
#include "src/model/air/airmention.h"
#include "src/model/air/airmodel.h"
#include "src/net/locationmaploader.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/style.h"

#include <QDebug>
#include <QLocale>

#include "src/persistence/settings.h"
#include "src/persistence/smileypack.h"
#include "src/persistence/history.h"

#define NAME_COL_WIDTH 90.0


ChatMessage::ChatMessage(DocumentCache& documentCache_, Settings& settings_,
    Style& style_)
    : documentCache{documentCache_}
    , settings{settings_}
    , style{style_}
{
}

ChatMessage::~ChatMessage() = default;

namespace {
/** The time of an entry: hours and minutes, no seconds, in the Latin digits of the type. */
QString entryTime(const QDateTime& date)
{
    return date.isValid() ? QLocale::c().toString(date.toLocalTime().time(), QStringLiteral("H:mm"))
                          : QString();
}

/** The author label and the time of an entry: contents 0 and 2. */
AirLabel* authorLabel(const ChatMessage::AirLook& look)
{
    return new AirLabel(look.who, AirTokens::TextRole::Label,
                        look.tone < 0 ? AirLabel::Ink::Ink2 : AirLabel::Ink::Tone, QFont::DemiBold,
                        true, look.tone);
}

AirLabel* timeLabel(const QDateTime& date)
{
    return new AirLabel(entryTime(date), AirTokens::TextRole::Caption, AirLabel::Ink::Ink3, -1, true);
}

/**
 * KHANDAQ (owner, 28.11.2026): an entry its author changed says so before its time, as Telegram's
 * "edited 14:17", no longer on a line of its own under the text.
 */
QString editedLead(const Air::EntryMarks& marks)
{
    return marks.edited ? AirStrings::text(AirStrings::Text::MessageEdited) : QString();
}

/**
 * KHANDAQ (26.11.2026): where a mention starts and ends in the words while they go through the
 * formatting (escaping, smileys, links, markdown), which leaves characters of the private use area
 * as they are; createBody turns them into the link at the end.
 */
const QChar mentionOpen(0xE000);
const QChar mentionClose(0xE001);

/** The words with each mention of spans between mentionOpen and mentionClose. */
QString markMentions(const QString& words, const QVector<Air::MentionSpan>& spans)
{
    QString marked = words;
    // From the end, so that the places of the earlier spans hold.
    for (auto it = spans.crbegin(); it != spans.crend(); ++it) {
        marked.insert(it->start + it->length, mentionClose);
        marked.insert(it->start, mentionOpen);
    }
    return marked;
}

/** Where the reactions under an entry take their pictures: where the messages take theirs. */
const SmileyPack* reactionPictures(const SmileyPack& smileyPack, const Settings& settings)
{
    return settings.getUseEmoticons() ? &smileyPack : nullptr;
}
} // namespace

QFont ChatMessage::bodyFont(Settings& settings)
{
    const QFont chosen = settings.getChatMessageFont();
    if (chosen == Style::getFont(Style::Font::Big)) {
        return AirStyle::instance().font(AirTokens::TextRole::Body);
    }
    return chosen;
}

ChatLineContent* ChatMessage::createBody(const QString& rawMessage, const QString& sender,
                                         MessageType type, DocumentCache& documentCache,
                                         SmileyPack& smileyPack, Settings& settings, Style& style)
{
    // KHANDAQ (28.11.2026): a place shows its map and coordinates, as on the phones, never the
    // words it travels as ("khandaq-location:LAT,LON"). An action keeps the words of its line.
    Air::Location place;
    if (type != ACTION && Air::messageLocation(rawMessage, place)) {
        return new AirLocationCard(place, &settings, style, LocationMapLoader::instance());
    }

    const QString words = AirStrings::transcriptWords(rawMessage);
    // A text that holds the marks itself shows its mentions plain.
    const QVector<Air::MentionSpan> spans =
        words.contains(mentionOpen) || words.contains(mentionClose)
            ? QVector<Air::MentionSpan>()
            : Air::mentionSpans(words, Air::readMentions(rawMessage));
    QString text = markMentions(words, spans).toHtmlEscaped();

    // Avoid UI freeze on pathological markdown from untrusted contacts (#11).
    const int kMaxMarkdownChars = 16000;
    if (text.length() > kMaxMarkdownChars) {
        text = text.left(kMaxMarkdownChars);
    }

    auto textType = Text::NORMAL;
    // smileys
    if (settings.getUseEmoticons())
        text = smileyPack.smileyfied(text);

    // quotes (green text)
    text = detectQuotes(text, type);
    text = TextFormatter::highlightURI(text);

    // text styling
    Settings::StyleType styleType = settings.getStylePreference();
    if (styleType != Settings::StyleType::NONE) {
        text = TextFormatter::applyMarkdown(text, styleType == Settings::StyleType::WITH_CHARS);
    }

    // Each mention a link to the member, in the order of the words; ink and bold (the
    // stylesheet's "mention"), so it holds on both themes and on the ground of a mention of you.
    for (const Air::MentionSpan& span : spans) {
        const int open = text.indexOf(mentionOpen);
        if (open < 0) {
            break;
        }
        text.replace(open, 1,
                     QStringLiteral("<a class=\"mention\" href=\"%1\">")
                         .arg(Air::mentionLink(span.key).toHtmlEscaped()));
    }
    text.replace(mentionClose, QStringLiteral("</a>"));
    // An open mark the formatting cut off has no link to open.
    text.remove(mentionOpen);

    switch (type) {
    case NORMAL:
        text = wrapDiv(text, "msg");
        break;
    case ACTION:
        textType = Text::ACTION;
        text = wrapDiv(QString("%1 %2").arg(sender.toHtmlEscaped(), text), "action");
        break;
    case ALERT:
        text = wrapDiv(text, "alert");
        break;
    }

    // Note: Eliding cannot be enabled for RichText items. (QTBUG-17207)
    // A copy takes the words the entry shows, never the markup of a reply.
    auto* const body =
        new Text(documentCache, settings, style, text, bodyFont(settings), false,
                 type == ACTION ? QString("%1 %2").arg(sender, words) : words, textType);
    body->setDocumentMargin(0);
    // KHANDAQ (owner, 28.11.2026): each paragraph by its own direction, as on the phones: an
    // Arabic one stands at the right in an English interface, and its time goes under it, as the
    // last line has no room left at its end (ChatLine::layoutAir).
    body->setOwnDirection(true);
    return body;
}

ChatMessage::Ptr ChatMessage::createChatMessage(const AirLook& look, const QString& sender,
                                                const QString& rawMessage, MessageType type,
                                                const QDateTime& date, const Air::EntryMarks& marks,
                                                DocumentCache& documentCache, SmileyPack& smileyPack,
                                                Settings& settings, Style& style)
{
    ChatMessage::Ptr msg = ChatMessage::Ptr(new ChatMessage(documentCache, settings, style));
    msg->spokenWho = look.spokenWho;
    msg->mentionsYou = type == ALERT;
    msg->rawText = rawMessage;
    msg->senderName = sender;
    msg->messageType = type;
    msg->editable = true;
    if (type == ACTION) {
        msg->setAsAction();
    }

    const Air::MessageText read = Air::readMessageText(rawMessage);
    msg->setLayout(Layout::AirEntry);
    msg->addColumn(authorLabel(look), ColumnFormat());
    msg->addColumn(createBody(rawMessage, sender, type, documentCache, smileyPack, settings, style),
                   ColumnFormat());
    AirLabel* const time = timeLabel(date);
    time->setLead(editedLead(marks));
    msg->addColumn(time, ColumnFormat());
    if (look.hasMark) {
        msg->addColumn(new AirMark(look.status), ColumnFormat());
    }
    // Every message keeps a place for a quote and notes: an edit or a reaction may come later.
    const int quote = msg->getColumnCount();
    msg->addColumn(new AirQuote(read.replyName, read.reply ? read.replyPreview : QString()),
                   ColumnFormat());
    msg->addColumn(new AirEntryNotes(marks, reactionPictures(smileyPack, settings)), ColumnFormat());
    msg->setEntryParts(quote, quote + 1);
    // KHANDAQ (26.11.2026): a private message of a group says so on its first line, on its ground.
    if (!look.privateNote.isEmpty()) {
        msg->addColumn(new AirPrivateNote(look.privateNote), ColumnFormat());
        msg->setPrivatePart(msg->getColumnCount() - 1);
    }
    return msg;
}

/**
 * @brief A line of the log that nobody wrote: a call that ended, a new name. Its words stand where
 * the text of the entries stands, in ink-2, without an author.
 */
ChatMessage::Ptr ChatMessage::createChatInfoMessage(const QString& rawMessage,
                                                    SystemMessageType type, const QDateTime& date,
                                                    DocumentCache& documentCache, Settings& settings,
                                                    Style& style)
{
    std::ignore = type;
    ChatMessage::Ptr msg = ChatMessage::Ptr(new ChatMessage(documentCache, settings, style));
    const QString text = rawMessage.toHtmlEscaped();

    msg->setLayout(Layout::AirEntry);
    msg->addColumn(new AirLabel(QString(), AirTokens::TextRole::Label, AirLabel::Ink::Ink2, -1, false),
                   ColumnFormat());
    auto* const words =
        new Text(documentCache, settings, style, text,
                 AirStyle::instance().font(AirTokens::TextRole::Caption), false, rawMessage);
    words->setDocumentMargin(0);
    words->setStartAligned(true);
    msg->addColumn(words, ColumnFormat());
    msg->addColumn(timeLabel(date), ColumnFormat());
    msg->setAuthorShown(false);
    return msg;
}

ChatMessage::Ptr ChatMessage::createFileTransferMessage(
    const AirLook& look, CoreFile& coreFile, ToxFile file, const QDateTime& date,
    const Air::EntryMarks& marks, DocumentCache& documentCache, SmileyPack& smileyPack,
    Settings& settings, Style& style, IMessageBoxManager& messageBoxManager)
{
    ChatMessage::Ptr msg = ChatMessage::Ptr(new ChatMessage(documentCache, settings, style));
    msg->spokenWho = look.spokenWho;

    msg->setLayout(Layout::AirEntry);
    msg->addColumn(authorLabel(look), ColumnFormat());
    msg->addColumn(new ChatLineContentProxy(new FileTransferWidget(
                   nullptr, coreFile, file, settings, style, messageBoxManager), 320, 0.6f),
                   ColumnFormat(1.0, ColumnFormat::VariableSize));
    msg->addColumn(timeLabel(date), ColumnFormat());
    // A file of ours to a person takes the mark a message takes, at the far end of the text.
    if (look.hasMark) {
        msg->addColumn(new AirMark(look.status), ColumnFormat());
    }
    // A file is not edited, but people react to it.
    Air::EntryMarks reactions = marks;
    reactions.edited = false;
    const int notes = msg->getColumnCount();
    msg->addColumn(new AirEntryNotes(reactions, reactionPictures(smileyPack, settings)),
                   ColumnFormat());
    msg->setEntryParts(-1, notes);

    return msg;
}

ChatMessage::Ptr ChatMessage::createCallEntry(const QString& words, bool answered,
                                              const QDateTime& date, DocumentCache& documentCache,
                                              Settings& settings, Style& style)
{
    ChatMessage::Ptr msg = ChatMessage::Ptr(new ChatMessage(documentCache, settings, style));
    msg->setLayout(Layout::AirEntry);
    msg->addColumn(new AirLabel(QString(), AirTokens::TextRole::Label, AirLabel::Ink::Ink2, -1, false),
                   ColumnFormat());
    msg->addColumn(new AirCallLine(words, answered), ColumnFormat());
    msg->addColumn(timeLabel(date), ColumnFormat());
    msg->setAuthorShown(false);
    return msg;
}

ChatMessage::Ptr ChatMessage::createDayMark(const QString& text, DocumentCache& documentCache,
                                            Settings& settings, Style& style)
{
    ChatMessage::Ptr msg = ChatMessage::Ptr(new ChatMessage(documentCache, settings, style));
    msg->setLayout(Layout::AirDay);
    // Three contents, as every line of the log has: a copied selection skips a line whose
    // content 1 says nothing.
    msg->addColumn(new AirLabel(text, AirTokens::TextRole::Caption, AirLabel::Ink::Ink3,
                                QFont::Medium, false),
                   ColumnFormat());
    msg->addColumn(new AirLabel(QString(), AirTokens::TextRole::Caption, AirLabel::Ink::Ink3, -1, false),
                   ColumnFormat());
    msg->addColumn(new AirLabel(QString(), AirTokens::TextRole::Caption, AirLabel::Ink::Ink3, -1, false),
                   ColumnFormat());
    return msg;
}

ChatMessage::Ptr ChatMessage::createUnreadMark(const QString& text, DocumentCache& documentCache,
                                               Settings& settings, Style& style)
{
    ChatMessage::Ptr msg = ChatMessage::Ptr(new ChatMessage(documentCache, settings, style));
    msg->setLayout(Layout::AirUnread);
    // The caption of iOS in ink at weight 600; three contents, as a day mark has.
    msg->addColumn(new AirLabel(text, AirTokens::TextRole::Caption, AirLabel::Ink::Ink,
                                QFont::DemiBold, false),
                   ColumnFormat());
    for (int i = 0; i < 2; ++i) {
        msg->addColumn(new AirLabel(QString(), AirTokens::TextRole::Caption, AirLabel::Ink::Ink3,
                                    -1, false),
                       ColumnFormat());
    }
    return msg;
}

ChatMessage::Ptr ChatMessage::createTypingNotification(DocumentCache& documentCache,
        Settings& settings, Style& style)
{
    ChatMessage::Ptr msg = ChatMessage::Ptr(new ChatMessage(documentCache, settings, style));

    QFont baseFont = settings.getChatMessageFont();

    // Note: "[user]..." is just a placeholder. The actual text is set in
    // ChatForm::setFriendTyping()
    //
    // FIXME: Due to circumstances, placeholder is being used in a case where
    // user received typing notifications constantly since contact came online.
    // This causes "[user]..." to be displayed in place of user nick, as long
    // as user will keep typing. Issue #1280
    msg->addColumn(new NotificationIcon(settings, style, QSize(18, 18)),
                   ColumnFormat(NAME_COL_WIDTH, ColumnFormat::FixedSize, ColumnFormat::Right));
    msg->addColumn(new Text(documentCache, settings, style, "[user]...", baseFont, false, ""),
                   ColumnFormat(1.0, ColumnFormat::VariableSize, ColumnFormat::Left));

    return msg;
}

/**
 * @brief Create message placeholder while chatform restructures text
 *
 * It can take a while for chatform to resize large amounts of text, thus
 * a message placeholder is needed to inform users about it.
 *
 * @return created message
 */
ChatMessage::Ptr ChatMessage::createBusyNotification(DocumentCache& documentCache,
        Settings& settings, Style& style)
{
    ChatMessage::Ptr msg = ChatMessage::Ptr(new ChatMessage(documentCache, settings, style));
    QFont baseFont = settings.getChatMessageFont();
    baseFont.setPixelSize(baseFont.pixelSize() + 2);
    baseFont.setBold(true);

    msg->addColumn(new Text(documentCache, settings, style, QObject::tr("Reformatting text...", "Waiting for text to be reformatted"), baseFont, false, ""),
                   ColumnFormat(1.0, ColumnFormat::VariableSize, ColumnFormat::Center));

    return msg;
}

bool ChatMessage::setStatus(Air::EntryStatus status)
{
    auto* mark = qobject_cast<AirMark*>(getContent(3));
    if (!mark) {
        return false;
    }
    const bool wasShown = mark->isShown();
    mark->setStatus(status);
    return mark->isShown() != wasShown;
}

bool ChatMessage::updateMessage(const QString& rawMessage, MessageType type,
                                const Air::EntryMarks& marks, SmileyPack& smileyPack)
{
    bool resized = updateMarks(marks);
    if ((rawMessage == rawText && type == messageType) || getLayout() != Layout::AirEntry) {
        return resized;
    }
    // Its author changed the words: the text is drawn again, and so is the quote it opens with.
    rawText = rawMessage;
    // As createChatMessage gives them.
    messageType = type;
    action = type == ACTION;
    mentionsYou = type == ALERT;
    replaceContent(1, createBody(rawMessage, senderName, messageType, documentCache, smileyPack,
                                 settings, style));
    if (auto* const quote = qobject_cast<AirQuote*>(quotePart())) {
        const Air::MessageText read = Air::readMessageText(rawMessage);
        quote->setQuote(read.replyName, read.reply ? read.replyPreview : QString());
    }
    return true;
}

bool ChatMessage::updateMarks(const Air::EntryMarks& marks)
{
    auto* const notes = qobject_cast<AirEntryNotes*>(notesPart());
    const bool notesResized = notes && notes->setNotes(marks);
    // KHANDAQ (owner, 28.11.2026): "edited" before the time widens it, and the time may then
    // leave the last line of the text for a line of its own. A file is not edited.
    auto* const time = editable ? qobject_cast<AirLabel*>(getContent(2)) : nullptr;
    const bool timeResized = time && time->setLead(editedLead(marks));
    return notesResized || timeResized;
}

bool ChatMessage::updateAuthor(const AirLook& look)
{
    spokenWho = look.spokenWho;
    auto* const author = qobject_cast<AirLabel*>(getContent(0));
    if (!author || author->getText() == look.who) {
        return false;
    }
    author->setText(look.who);
    return true;
}

QString ChatMessage::spokenText() const
{
    const auto spoken = [this](int column) {
        const ChatLineContent* const part = getContent(column);
        return part ? part->getSpokenText() : QString();
    };
    const auto spokenPart = [](const ChatLineContent* part) {
        return part ? part->getSpokenText() : QString();
    };
    switch (getLayout()) {
    case Layout::AirDay:
    case Layout::AirUnread:
        return spoken(0);
    case Layout::AirEntry: {
        const auto* const mark = qobject_cast<const AirMark*>(getContent(3));
        // KHANDAQ (26.11.2026): who gets a private message is said before its quote and words.
        const QString privateNote = spokenPart(privatePart());
        const QString quote = spokenPart(quotePart());
        return AirStrings::spokenEntry(
            spokenWho, spoken(2), spoken(1), mark ? mark->getStatus() : Air::EntryStatus::None,
            mentionsYou,
            privateNote.isEmpty() || quote.isEmpty()
                ? privateNote + quote
                : privateNote + QStringLiteral(", ") + quote,
            spokenPart(notesPart()));
    }
    case Layout::Columns:
        break;
    }
    return ChatLine::spokenText();
}

QString ChatMessage::toString() const
{
    ChatLineContent* c = getContent(1);
    if (c)
        return c->getText();

    return QString();
}

bool ChatMessage::isAction() const
{
    return action;
}

void ChatMessage::setAsAction()
{
    action = true;
}

void ChatMessage::hideSender()
{
    ChatLineContent* c = getContent(0);
    if (c)
        c->hide();
}

void ChatMessage::hideDate()
{
    ChatLineContent* c = getContent(2);
    if (c)
        c->hide();
}

QString ChatMessage::detectQuotes(const QString& str, MessageType type)
{
    // detect text quotes
    QStringList messageLines = str.split("\n");
    QString quotedText;
    for (int i = 0; i < messageLines.size(); ++i) {
        // don't quote first line in action message. This makes co-existence of
        // quotes and action messages possible, since only first line can cause
        // problems in case where there is quote in it used.
        if (QRegExp("^(&gt;|＞).*").exactMatch(messageLines[i])) {
            if (i > 0 || type != ACTION)
                quotedText += "<span class=quote>" + messageLines[i] + " </span>";
            else
                quotedText += messageLines[i];
        } else {
            quotedText += messageLines[i];
        }

        if (i < messageLines.size() - 1) {
            quotedText += '\n';
        }
    }

    return quotedText;
}

QString ChatMessage::wrapDiv(const QString& str, const QString& div)
{
    return QString("<p class=%1>%2</p>").arg(div, /*QChar(0x200E) + */ QString(str));
}
