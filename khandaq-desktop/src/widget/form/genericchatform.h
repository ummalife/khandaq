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

#include "src/chatlog/chatmessage.h"
#include "src/core/toxpk.h"
#include "src/model/ichatlog.h"
#include "src/widget/searchtypes.h"

#include <QMenu>
#include <QPixmap>
#include <QWidget>

/**
 * Spacing in px inserted when the author of the last message changes
 * @note Why the hell is this a thing? surely the different font is enough?
 *        - Even a different font is not enough – TODO #1307 ~~zetok
 */

class AirComposer;
class ChatFormHeader;
class ChatWidget;
class ChatQuotePreview;
class ChatTextEdit;
class Chat;
class ContentLayout;
class CroppingLabel;
class FlyoutOverlayWidget;
class GenericNetCamView;
class ImagePreviewButton;
class MaskablePixmapWidget;
class SearchForm;
class Widget;

class QLabel;
class QPushButton;
class QSplitter;
class QToolButton;
class QVBoxLayout;

class IMessageDispatcher;
struct Message;
class DocumentCache;
class SmileyPack;
class Settings;
class Style;
class IMessageBoxManager;
class FriendList;

namespace Ui {
class MainWindow;
}

#ifdef SPELL_CHECKING
namespace Sonnet {
class SpellCheckDecorator;
}
#endif

class GenericChatForm : public QWidget
{
    Q_OBJECT
public:
    GenericChatForm(const Core& core_, const Chat* chat, IChatLog& chatLog_,
                    IMessageDispatcher& messageDispatcher_, DocumentCache& documentCache,
                    SmileyPack& smileyPack, Settings& settings, Style& style,
                    IMessageBoxManager& messageBoxmanager, FriendList& friendList,
                    GroupList& groupList, QWidget* parent_ = nullptr);
    ~GenericChatForm() override;

    void setName(const QString& newName);
    virtual void show(ContentLayout* contentLayout_);

    void addSystemInfoMessage(const QDateTime& datetime, SystemMessageType messageType,
                              SystemMessage::Args messageArgs);
    QString resolveToxPk(const ToxPk& pk);
    QDateTime getLatestTime() const;
    /**
     * KHANDAQ (beta ideas 2026-11-20): the conversation came to the front with count entries that
     * came while it was not there, the first at since: the transcript opens on the first of them
     * (ChatWidget::markUnread).
     */
    void markUnread(int count, const QDateTime& since);

signals:
    void messageInserted();

public slots:
    void focusInput();
    void onChatMessageFontChanged(const QFont& font);
    void setColorizedNames(bool enable);
    virtual void reloadTheme();

protected slots:
    void onChatContextMenuRequested(QPoint pos);
    virtual void onScreenshotClicked() = 0;
    void onSendTriggered();
    virtual void onAttachClicked() = 0;
    virtual void onPasteFiles(const QStringList& paths);
    /**
     * KHANDAQ (0.2.60): a picture pasted, or a screenshot, waits over the field until Enter or Send
     * sends it as a file of the chat, or Esc or its cross puts it away; in a group as with a person.
     */
    void previewImage(const QPixmap& pixmap);
    void cancelImagePreview();
    void sendImageFromPreview();
    void onEmoteButtonClicked();
    /** KHANDAQ (0.2.60): the emoji of the set, opened above anchor (a global point). */
    void showEmoticons(const QPoint& anchor);
    void onEmoteInsertRequested(QString str);
    void onCopyLogClicked();
    void clearChatArea();
    void clearChatArea(bool confirm, bool inform);
    void onSelectAllClicked();
    void showFileMenu();
    void hideFileMenu();
    void quoteSelectedText();
    /** KHANDAQ (0.2.58): Reply in the menu of the transcript, on the entry it was opened on. */
    void replyToMenuEntry();
    void copyLink();
    void onLoadHistory();
    void onExportChat();
    void searchFormShow();
    void updateShowDateInfo(const ChatLine::Ptr& prevLine, const ChatLine::Ptr& topLine);
    void goToCurrentDate();

private:
    void retranslateUi();
    void addSystemDateMessage(const QDate& date);
    QDateTime getTime(const ChatLine::Ptr& chatLine) const;

protected:
    /**
     * Called before the menu of the transcript shows at globalPos, so a form can add what fits
     * the entry there.
     */
    virtual void prepareChatMenu(QPoint globalPos);
    /**
     * What Send would hand over now: the quote or the reply's header and the field, without the
     * action prefix.
     */
    QString outgoingText() const;
    /**
     * KHANDAQ (26.11.2026): the block of mentions (Air::mentionBlock) that goes with words, the
     * text of the field; a form of a group makes it, none by default.
     */
    virtual QString mentionBlockFor(const QString& words) const;
    /**
     * KHANDAQ (26.11.2026): whether Reply on the entry at idx answers it away from this
     * conversation, because a reply here would quote it to people it was not meant for (a private
     * message of a group). Then the entry is never quoted here. None by default.
     */
    virtual bool repliesElsewhere(ChatLogIdx idx) const;
    /** Answers the entry at idx where repliesElsewhere says; nothing by default. */
    virtual void replyElsewhere(ChatLogIdx idx);
    /**
     * KHANDAQ (0.2.58): who wrote an entry, as a reply to it names them on the wire. The name is
     * the one the others know them by (ours too, never "You") and the key the one this
     * conversation knows them by.
     */
    struct ReplyAuthor
    {
        QString name;
        ToxPk key;
        /** The entry is ours: the bar over the field says "You". */
        bool self = false;
    };
    /** The base reads the log: our own key and name, else the entry's sender and the name it shows. */
    virtual ReplyAuthor replyAuthorOf(const ChatLogItem& item) const;
    /**
     * Whether the entry at idx may be answered now: a message or a file with words to quote, in a
     * conversation that takes replies, while you may write.
     */
    bool canReplyTo(ChatLogIdx idx) const;
    /**
     * Puts a reply to the entry at idx above the field, quoting words, or the words the entry
     * shows when words is empty. False, and nothing changes, when it may not be answered.
     */
    bool startReply(ChatLogIdx idx, const QString& words = QString());
    /**
     * KHANDAQ (0.2.63): whether React shows on the entry at idx: a form that sends reactions
     * says so. Nowhere by default.
     */
    virtual bool canReactTo(ChatLogIdx idx) const;
    /**
     * KHANDAQ (0.2.63): our reaction with emoji to the entry at idx, as React chose it: the same
     * emoji again takes it back. Nothing by default.
     */
    virtual void reactTo(ChatLogIdx idx, const QString& emoji);
    /**
     * KHANDAQ (0.2.63): what a reaction to the entry at idx points at: a text by the id it went
     * or came with (Message::wireId), a file by its Tox file id. False for anything else, an
     * entry not shown, or an id that is empty or zeros.
     */
    bool reactionAnchorOf(ChatLogIdx idx, Air::MessageEffect::Anchor* anchor, QByteArray* id) const;
    /** Whether Send may hand the text over now; a form that limits it says why under the field. */
    virtual bool canSendNow() const;
    ChatMessage::Ptr createMessage(const ToxPk& author, const QString& message,
                                   const QDateTime& datetime, bool isAction, bool isSent, bool colorizeName = false);
    void adjustFileMenuPosition();
    void hideEvent(QHideEvent* event) override;
    void showEvent(QShowEvent* event) override;
    bool event(QEvent* event) final;
    void resizeEvent(QResizeEvent* event) final;
    bool eventFilter(QObject* object, QEvent* event) final;
    bool searchInText(const QString& phrase, const ParameterSearch& parameter, SearchDirection direction);
    std::pair<int, int> indexForSearchInLine(const QString& txt, const QString& phrase, const ParameterSearch& parameter, SearchDirection direction);

protected:
    const Core& core;
    bool audioInputFlag;
    bool audioOutputFlag;
    int curRow;

    QAction* clearAction;
    QAction* replyAction;
    /** KHANDAQ (0.2.63): the emoji of Air::quickReactions, ours checked. */
    QMenu* reactMenu;
    QAction* quoteAction;
    QAction* copyLinkAction;
    QAction* searchAction;
    QAction* goToCurrentDateAction;
    QAction* loadHistoryAction;
    QAction* exportChatAction;

    QMenu menu;

    /**
     * The forms that send files take pasted pictures: the field offers them (ChatTextEdit sees the
     * connection) and the preview sends them through sendPreparedFiles.
     */
    void takePastedImages();
    /** Sends files of this computer into the chat, saying every refusal; nothing by default. */
    virtual void sendPreparedFiles(const QStringList& paths);
    /**
     * KHANDAQ (beta ideas 2026-11-20): files chosen with the paperclip, pasted or dropped go only
     * once Send is pressed in a dialog that lists them (Enter; Cancel and Esc keep them back); then
     * sendPreparedFiles takes them, with its checks. A picture pasted waits over the field instead.
     */
    void offerFiles(const QStringList& paths);
    /**
     * KHANDAQ (beta ideas 2026-11-20): web addresses dropped on the conversation go into the field
     * at its cursor, to be sent with Enter like anything typed there.
     */
    void putAddressesInField(const QStringList& addresses);
    /**
     * KHANDAQ (0.2.60): why a voice note cannot go from here now (the person is offline), said at
     * the microphone and again at Send; empty when it can.
     */
    virtual QString voiceRefusal() const;

    QVBoxLayout* contentLayout;
    /** The field with the paperclip and "Send", and the line about the next message. */
    AirComposer* composer;
    QPixmap imagePreviewSource;
    ImagePreviewButton* imagePreview;
    /** A group's file limit, said by the paperclip and the attach menu before a file is chosen. */
    void setAttachLimit(const QString& words);
    QString attachLimit;
    // The buttons of the old footer stay as the actions the composer triggers.
    QPushButton* emoteButton;
    QPushButton* fileButton;
    QPushButton* screenshotButton;
    QPushButton* sendButton;

    QSplitter* bodySplitter;

    ChatFormHeader* headWidget;

    SearchForm *searchForm;
    QLabel *dateInfo;
    ChatWidget* chatWidget;
    ChatQuotePreview* quotePreview;
    ChatTextEdit* msgEdit;
#ifdef SPELL_CHECKING
    Sonnet::SpellCheckDecorator* decorator{nullptr};
#endif
    FlyoutOverlayWidget* fileFlyout;
    Widget* parent;

    IChatLog& chatLog;
    IMessageDispatcher& messageDispatcher;
    SmileyPack& smileyPack;
    Settings& settings;
    Style& style;
    FriendList& friendList;
    GroupList& groupList;

private:
    /**
     * KHANDAQ (0.2.60): the voice note of this form (AirVoiceRecorder): the microphone asked for
     * on macOS, the note written off the main thread into the voice folder, then sent as a file.
     */
    void startVoiceNote();
    void sendVoiceNote(bool atLimit);
    void cancelVoiceNote();
    void explainVoiceMicrophone();

    /** Notes take no replies: they have no author to answer. */
    bool takesReplies = true;
    /** The entry the menu of the transcript was opened on, as Reply answers it. */
    ChatLogIdx menuEntry{0};
};
