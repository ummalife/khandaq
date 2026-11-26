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

#include <QDateTime>
#include <QGraphicsView>
#include <QMargins>

#include <vector>

#include "chatline.h"
#include "chatmessage.h"
#include "src/model/ichatlog.h"

class QGraphicsScene;
class QGraphicsRectItem;
class QMouseEvent;
class QTimer;
class ChatLineContent;
struct ToxFile;
class SmileyPack;
class Settings;
class Style;
class ChatLineStorage;
class IMessageBoxManager;

static const size_t DEF_NUM_MSG_TO_LOAD = 100;
class ChatWidget : public QGraphicsView
{
    Q_OBJECT
public:
    /** What the log is a conversation with. */
    enum class Kind
    {
        /**
         * A group: authors in their tones. A group confirms nothing, so our texts carry a mark
         * only while they wait on this device and once they failed.
         */
        Group,
        /**
         * One person: our messages carry state marks, and while the person is offline the
         * messages waiting for them leave the log for the tray under it.
         */
        Person,
        /** Notes to yourself: no authors and no marks. */
        Notes,
    };

    ChatWidget(IChatLog& chatLog_, const Core& core_, DocumentCache& documentCache,
               SmileyPack& smileyPack, Settings& settings, Style& style,
               IMessageBoxManager& messageBoxManager, QWidget* parent = nullptr,
               Kind kind = Kind::Group);
    virtual ~ChatWidget();

    void insertChatlines(std::map<ChatLogIdx, ChatLine::Ptr> chatLines);
    void clearSelection();
    void clear();
    void copySelectedText(bool toSelectionBuffer = false) const;
    void setTypingNotificationVisible(bool visible);
    void setTypingNotificationName(const QString& displayName);
    void scrollToLine(ChatLine::Ptr line);
    void selectAll();
    void fontChanged(const QFont& font);

    QString getSelectedText() const;

    bool isEmpty() const;
    bool hasTextToBeCopied() const;
    ChatLineContent* getContentFromGlobalPos(QPoint pos) const;
    /** Finds the log entry shown at pos (global); false when none of the log stands there. */
    bool entryAtGlobalPos(QPoint pos, ChatLogIdx& idx) const;
    /**
     * KHANDAQ (0.2.58): the entry whose text holds the selection, when the selection lies in the
     * words of one entry; false for none or for a selection over several.
     */
    bool selectedEntry(ChatLogIdx& idx) const;
    /**
     * KHANDAQ (0.2.58): the key a group knows us by besides the key of our profile; our entries
     * carry either, and the name over them opens no menu.
     */
    void setGroupSelfKey(const ToxPk& key);
    const uint repNameAfter = 5 * 60;

    void setColorizedNames(bool enable) { colorizeNames = enable; };
    void jumpToDate(QDate date);
    void jumpToIdx(ChatLogIdx idx);
    /**
     * KHANDAQ (beta ideas 2026-11-20): the conversation comes to the front with count entries that
     * came while it was not there, the first of them at since (AirSource::takeUnread): "Unread
     * messages" stands over the first of them and the view starts at that line rather than at the
     * bottom, until something moves it. The line stays while the conversation is open; another one
     * opening takes it away (hideEvent), as does a count of none.
     */
    void markUnread(int count, const QDateTime& since);
    /**
     * KHANDAQ (beta ideas 2026-11-20): the entry "Unread messages" stands over: the count-th one
     * back from the newest of the entries the log shows that count as unread, a text or a file of
     * someone else or a call of theirs, and that are not older than since (by unreadSlackSecs; an
     * invalid since leaves none out); the oldest of them when the log holds fewer. A row a member
     * of a group relays from its history keeps the time it was sent, so it is left out, wherever it
     * stands. Our entries carry self or, in a group, groupSelf: one of ours older than since ends
     * the search, as does the start of the log or unreadLookBack entries read.
     * @return Whether there is one; false for a count of none
     */
    static bool firstUnreadIdx(const IChatLog& log, const ToxPk& self, const ToxPk& groupSelf,
                               int count, const QDateTime& since, ChatLogIdx* idx);
    static constexpr size_t unreadLookBack = 1000;
    /** An entry is written a moment before the alert that counts it notes the time. */
    static constexpr int unreadSlackSecs = 2;
    /**
     * KHANDAQ (beta feedback 2026-11-22): whether the entry at idx, of which the log just told, is
     * one we sent from here: a text, a file, a voice note or a note, by self, and new to the log
     * (at or past knownEnd, the end the transcript had heard of). The transcript then shows its
     * foot, wherever it was. One of ours that a group relays from its history carries our key in
     * the group, not self, a file that goes on from before (ToxFile::continued) was not sent now,
     * and a change to an entry already there is no new one: they leave it be.
     */
    static bool sentFromHere(const IChatLog& log, ChatLogIdx idx, ChatLogIdx knownEnd,
                             const ToxPk& self);

    /** Whether the person of a conversation with one person is online now. */
    void setPersonOnline(bool online);
    /** The short name of that person, as the file entries say it ("Waits for Yusuf to accept it"). */
    void setPersonName(const QString& name);
    /**
     * KHANDAQ (0.2.57): how far that person has seen our messages, by their read receipts
     * (AirLocal::seenUpTo): the time of the newest one seen, in milliseconds since 1970, or -1. Our
     * messages and files up to it show seen.
     */
    void setSeenUpTo(qint64 timeMs);

    /** The lines a screen reader goes through (AirAccessible): those the log shows, in order. */
    std::vector<ChatLine::Ptr> spokenLines() const;
    /** Where a line stands, in the coordinates of the viewport. */
    QRect viewportRectOf(const ChatLine& line) const;

signals:
    void selectionChanged();
    void firstVisibleLineChanged(const ChatLine::Ptr& prevLine, const ChatLine::Ptr& firstLine);

    void messageNotFoundShow(SearchDirection direction);
    void renderFinished();
    /** KHANDAQ (0.2.57): Accept on a group file that is held for a tap (FileTransferWidget). */
    void heldFileAccepted(const ToxFile& file);
    /**
     * KHANDAQ (0.2.58): a click, not a drag, on the name over someone else's message or file in a
     * group. globalPos is under the name, at its start edge: where the member's menu opens.
     */
    void authorClicked(ChatLogIdx idx, QPoint globalPos);
public slots:
    void forceRelayout();
    void reloadTheme();

    void startSearch(const QString& phrase, const ParameterSearch& parameter);
    void onSearchUp(const QString& phrase, const ParameterSearch& parameter);
    void onSearchDown(const QString& phrase, const ParameterSearch& parameter);
    void handleSearchResult(SearchResult result, SearchDirection direction);
    void removeSearchPhrase();

private slots:
    void onSelectionTimerTimeout();
    void onWorkerTimeout();
    void onMultiClickTimeout();

    void onMessageUpdated(ChatLogIdx idx);
    void renderMessage(ChatLogIdx idx);
    void renderMessages(ChatLogIdx begin, ChatLogIdx end);

    void setRenderedWindowStart(ChatLogIdx begin);
    void setRenderedWindowEnd(ChatLogIdx end);

    void onRenderFinished();
    void onScrollValueChanged(int value);
protected:
    QRectF calculateSceneRect() const;
    QRect getVisibleRect() const;
    ChatLineContent* getContentFromPos(QPointF scenePos) const;

    void layout(int start, int end, qreal width);
    /** Lays out again from the first of resizedLines on. */
    void layoutResizedLines();
    /** Gives every file entry the conversation it stands in. */
    void refreshFileEntries();
    bool isOverSelection(QPointF scenePos) const;
    bool stickToBottom() const;

    qreal useableWidth() const;

    void updateSceneRect();
    void checkVisibility();
    void scrollToBottom();
    void startResizeWorker();

    void mouseDoubleClickEvent(QMouseEvent* ev) final;
    void mousePressEvent(QMouseEvent* ev) final;
    void mouseReleaseEvent(QMouseEvent* ev) final;
    void mouseMoveEvent(QMouseEvent* ev) final;
    void scrollContentsBy(int dx, int dy) final;
    void resizeEvent(QResizeEvent* ev) final;
    void showEvent(QShowEvent* event) final;
    void hideEvent(QHideEvent* event) final;
    void focusInEvent(QFocusEvent* ev) final;
    void focusOutEvent(QFocusEvent* ev) final;
    void wheelEvent(QWheelEvent *event) final;
    /** The focus ring round the messages while Tab has brought the focus here. */
    void drawForeground(QPainter* painter, const QRectF& rect) final;

    void updateMultiSelectionRect();
    void updateTypingNotification();
    void updateBusyNotification();

    ChatLine::Ptr findLineByPosY(qreal yPos) const;

    void removeLines(ChatLogIdx begin, ChatLogIdx end);

private:
    void retranslateUi();
    bool isActiveFileTransfer(ChatLine::Ptr l);
    void handleMultiClickEvent();
    void moveSelectionRectUpIfSelected(int offset);
    void moveSelectionRectDownIfSelected(int offset);
    void movePreciseSelectionDown(int offset);
    void movePreciseSelectionUp(int offset);
    void moveMultiSelectionUp(int offset);
    void moveMultiSelectionDown(int offset);
    void setTypingNotification();

    void renderItem(const ChatLogItem &item, bool hideName, bool colorizeNames_, ChatLine::Ptr &chatMessage);
    void renderFile(const ChatMessage::AirLook& look, ToxFile file, QDateTime timestamp,
                    const Air::EntryMarks& marks, ChatLine::Ptr& chatMessage);
    bool needsToHideName(ChatLogIdx idx, bool prevIdxRendered) const;
    ChatMessage::AirLook lookOf(const ChatLogItem& item) const;
    Air::EntryStatus statusOf(const ChatLogItem& item) const;
    bool isCollapsedAt(ChatLogIdx idx) const;
    /**
     * Brings every rendered line in line with the log: which lines the log leaves out, where the
     * runs of one author start, the state marks. Returns the position of the first line whose
     * height changed, or the number of lines when none did.
     */
    size_t applyEntryFlags();
    /** applyEntryFlags(), then lays out again what moved. */
    void refreshEntries();
    bool shouldRenderMessage(ChatLogIdx idx) const;
    void disableSearchText();
    /** The index of the entry a rendered line shows; false for a line of no entry (a day mark). */
    bool idxOfLine(const ChatLine::Ptr& line, ChatLogIdx& idx) const;
    /** Whether the name over an entry opens the author's menu: someone else's, in a group, with a key. */
    bool opensAuthorMenu(const ChatLogItem& item) const;
    /** The entry whose author's name, one that opens a menu, stands at scenePos; name is where. */
    bool authorNameAt(QPointF scenePos, ChatLogIdx& idx, QRectF* name = nullptr) const;
    /** The names that open a menu, set again: after our key in the group changed. */
    void refreshAuthorNames();
    /**
     * KHANDAQ (beta ideas 2026-11-20): the line of the entry under "Unread messages", while it is
     * drawn.
     */
    ChatLine* unreadEntryLine() const;
    /** Puts "Unread messages" at the top of the view, as far as the log goes down. */
    void restOnUnread();
    /** Takes "Unread messages" away; the lines after it close up. */
    void dropUnreadMark();
    /** Lays out again from the entry at idx on, as its line "Unread messages" comes or goes. */
    void relayoutFromEntry(ChatLogIdx idx);

private:
    enum class SelectionMode
    {
        None,
        Precise,
        Multi,
    };

    enum class AutoScrollDirection
    {
        NoDirection,
        Up,
        Down,
    };

    QAction* copyAction = nullptr;
    QAction* selectAllAction = nullptr;
    QGraphicsScene* scene = nullptr;
    QGraphicsScene* busyScene = nullptr;
    QList<ChatLine::Ptr> visibleLines;
    ChatLine::Ptr typingNotification;
    ChatLine::Ptr busyNotification;
    /** The interface direction the lines are laid out for. */
    Qt::LayoutDirection lineDirection = Qt::LeftToRight;

    // selection

    // For the time being we store these selection indexes as ChatLine::Ptrs. In
    // order to do multi-selection we do an O(n) search in the chatline storage
    // to determine the index. This is inefficient but correct with the moving
    // window of storage. If this proves to cause performance issues we can move
    // this responsibility into ChatlineStorage and have it coordinate the
    // shifting of indexes
    ChatLine::Ptr selClickedRow; // These 4 are only valid while selectionMode != None
    int selClickedCol = -1;
    ChatLine::Ptr selFirstRow;
    ChatLine::Ptr selLastRow;
    QColor selectionRectColor;
    SelectionMode selectionMode = SelectionMode::None;
    QPointF clickPos;
    QGraphicsRectItem* selGraphItem = nullptr;
    QTimer* selectionTimer = nullptr;
    QTimer* workerTimer = nullptr;
    QTimer* multiClickTimer = nullptr;
    AutoScrollDirection selectionScrollDir = AutoScrollDirection::NoDirection;
    int clickCount = 0;
    QPoint lastClickPos;
    Qt::MouseButton lastClickButton;

    // worker vars
    size_t workerLastIndex = 0;
    bool workerStb = false;
    ChatLine::Ptr workerAnchorLine;

    // layout: kq-log, 12 px above and below the entries and 2 px between them. The entries keep
    // their own 16 px side padding.
    QMargins margins = QMargins(0, 12, 0, 12);
    qreal lineSpacing = 2.0;
    bool personChat = false;
    bool groupChat = false;
    bool notesChat = false;
    ToxPk groupSelfKey;
    /** The end of the log as the transcript heard of it: an entry told of past it is new. */
    ChatLogIdx seenEnd{0};
    /** A left press landed on a name that opens a menu: a release on it, without a drag, opens it. */
    bool authorPressed = false;
    ChatLogIdx pressedAuthor{0};
    bool personOnline = true;
    qint64 seenUpToMs = -1;
    QString personName;
    bool typingShown = false;
    /**
     * KHANDAQ (beta ideas 2026-11-20): "Unread messages" over the entry at unreadIdx (markUnread);
     * null when there is none. It is no line of chatLineStorage: layout() sets it over its entry
     * whenever that one is drawn.
     */
    ChatLine::Ptr unreadMark;
    ChatLogIdx unreadIdx{0};
    /** The view starts at unreadMark until something else moves it; a layout goes back there. */
    bool restsOnUnread = false;
    /** Lines whose file entry changed its height: laid out again on the next turn of the loop. */
    std::vector<std::weak_ptr<ChatLine>> resizedLines;
    QTimer* resizedLater = nullptr;

    IChatLog& chatLog;
    bool colorizeNames = false;
    SearchPos searchPos;
    const Core& core;
    bool scrollMonitoringEnabled = true;

    std::unique_ptr<ChatLineStorage> chatLineStorage;

    std::vector<std::function<void(void)>> renderCompletionFns;
    DocumentCache& documentCache;
    SmileyPack& smileyPack;
    Settings& settings;
    Style& style;
    IMessageBoxManager& messageBoxManager;
};
