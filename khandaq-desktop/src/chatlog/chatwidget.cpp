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

#include "chatwidget.h"
#include "chatlinecontent.h"
#include "chatlinecontentproxy.h"
#include "chatmessage.h"
#include "content/airlabel.h"
#include "content/filetransferwidget.h"
#include "content/text.h"
#include "src/widget/air/airaccessible.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/translator.h"
#include "src/widget/style.h"
#include "src/persistence/settings.h"
#include "src/chatlog/chatlinestorage.h"
#include <iostream>

#include <QAction>
#include <QApplication>
#include <QClipboard>
#include <QDebug>
#include <QLocale>
#include <QMouseEvent>
#include <QPainter>
#include <QScrollBar>
#include <QShortcut>
#include <QTimer>

#include <algorithm>
#include <cassert>
#include <set>


namespace
{

// Maximum number of rendered messages at any given time
int constexpr maxWindowSize = 300;
// Amount of messages to purge when removing messages
int constexpr windowChunkSize = 100;

template <class T>
T clamp(T x, T min, T max)
{
    if (x > max)
        return max;
    if (x < min)
        return min;
    return x;
}

/** "Today", "Yesterday" or the date: the day mark above the first entry of a day. */
ChatMessage::Ptr createDateMessage(QDateTime timestamp, DocumentCache& documentCache,
    Settings& settings, Style& style)
{
    const QString code = settings.getTranslation();
    const QLocale locale = code.isEmpty() ? QLocale::system() : QLocale(code);
    const QString dateText = AirStrings::dayMarkText(timestamp.date(), QDate::currentDate(), locale);
    return ChatMessage::createDayMark(dateText, documentCache, settings, style);
}

ChatMessage::Ptr createMessage(const ChatMessage::AirLook& look, const QString& displayName,
                               const ChatLogMessage& chatLogMessage, DocumentCache& documentCache,
                               SmileyPack& smileyPack, Settings& settings, Style& style)
{
    auto messageType = chatLogMessage.message.isAction ? ChatMessage::MessageType::ACTION
                                                       : ChatMessage::MessageType::NORMAL;

    const bool bSelfMentioned =
        std::any_of(chatLogMessage.message.metadata.begin(), chatLogMessage.message.metadata.end(),
                    [](const MessageMetadata& metadata) {
                        return metadata.type == MessageMetadataType::selfMention;
                    });

    if (bSelfMentioned) {
        messageType = ChatMessage::MessageType::ALERT;
    }

    const auto timestamp = chatLogMessage.message.timestamp;
    return ChatMessage::createChatMessage(look, displayName, chatLogMessage.message.content,
                                          messageType, timestamp, chatLogMessage.marks,
                                          documentCache, smileyPack, settings, style);
}

/** Returns whether a line drawn before may have changed its height: an edit, a reaction. */
bool renderMessageRaw(const ChatMessage::AirLook& look, const QString& displayName,
                      const ChatLogMessage& chatLogMessage, ChatLine::Ptr& chatLine,
                      DocumentCache& documentCache, SmileyPack& smileyPack, Settings& settings,
                      Style& style)
{
    // HACK: This is kind of gross, but there's not an easy way to fit this into
    // the existing architecture. This shouldn't ever fail since we should only
    // correlate ChatMessages created here, however a logic bug could turn into
    // a crash due to this dangerous cast. The alternative would be to make
    // ChatLine a QObject which I didn't think was worth it.
    auto chatMessage = static_cast<ChatMessage*>(chatLine.get());

    if (chatMessage) {
        chatMessage->setStatus(look.status);
        return chatMessage->updateMessage(chatLogMessage.message.content, chatLogMessage.marks,
                                          smileyPack);
    }
    chatLine =
        createMessage(look, displayName, chatLogMessage, documentCache, smileyPack, settings, style);
    return false;
}

/**
 * @return Chat message message type (info/warning) for the given system message
 * @param[in] systemMessage
 */
ChatMessage::SystemMessageType getChatMessageType(const SystemMessage& systemMessage)
{
    switch (systemMessage.messageType)
    {
    case SystemMessageType::fileSendFailed:
    case SystemMessageType::messageSendFailed:
    case SystemMessageType::unexpectedCallEnd:
        return ChatMessage::ERROR;
    case SystemMessageType::userJoinedGroup:
    case SystemMessageType::userLeftGroup:
    case SystemMessageType::peerNameChanged:
    case SystemMessageType::peerStateChange:
    case SystemMessageType::titleChanged:
    case SystemMessageType::cleared:
    case SystemMessageType::outgoingCall:
    case SystemMessageType::incomingCall:
    case SystemMessageType::callEnd:
    case SystemMessageType::selfJoinedGroup:
    case SystemMessageType::selfLeftGroup:
        return ChatMessage::INFO;
    }

    return ChatMessage::INFO;
}

ChatLogIdx firstItemAfterDate(QDate date, const IChatLog& chatLog)
{
    auto idxs = chatLog.getDateIdxs(date, 1);
    if (idxs.size()) {
        return idxs[0].idx;
    } else {
        return chatLog.getNextIdx();
    }
}

/**
 * @brief Applies a function for each line in between first and last in storage.
 *
 * @note Undefined behavior if first appears after last
 * @note If first or last is not seen in storage it is assumed that they point
 * to elements past the edge of our rendered storage. We will iterate from the
 * beginning or to the end in these cases
 */
template <typename Fn>
void forEachLineIn(ChatLine::Ptr first, ChatLine::Ptr last, ChatLineStorage& storage, Fn f)
{
    auto startIt = storage.find(first);

    if (startIt == storage.end()) {
        startIt = storage.begin();
    }

    auto endIt = storage.find(last);
    if (endIt != storage.end()) {
        endIt++;
    }

    for (auto it = startIt; it != endIt; ++it) {
        f(*it);
    }
}

/**
 * @brief Helper function to add an offset ot a ChatLogIdx without going
 * outside the bounds of the associated chatlog
 */
ChatLogIdx clampedAdd(ChatLogIdx idx, int val, IChatLog& chatLog)
{
    if (val < 0) {
        auto distToEnd = idx - chatLog.getFirstIdx();
        if (static_cast<size_t>(std::abs(val)) > distToEnd) {
            return chatLog.getFirstIdx();
        }

        return idx - std::abs(val);
    } else {
        auto distToEnd = chatLog.getNextIdx() - idx;
        if (static_cast<size_t>(val) > distToEnd) {
            return chatLog.getNextIdx();
        }

        return idx + val;
    }
}

} // namespace


ChatWidget::ChatWidget(IChatLog& chatLog_, const Core& core_, DocumentCache& documentCache_,
                       SmileyPack& smileyPack_, Settings& settings_, Style& style_,
                       IMessageBoxManager& messageBoxManager_, QWidget* parent, Kind kind)
    : QGraphicsView(parent)
    , selectionRectColor{style_.getColor(Style::ColorPalette::SelectText)}
    , chatLog(chatLog_)
    , core(core_)
    , chatLineStorage(new ChatLineStorage())
    , documentCache(documentCache_)
    , smileyPack{smileyPack_}
    , settings(settings_)
    , style{style_}
    , messageBoxManager{messageBoxManager_}
{
    personChat = kind == Kind::Person;
    notesChat = kind == Kind::Notes;
    // Every entry an element of its own for a screen reader, with what the phones say for it.
    AirAccessible::install();

    // Create the scene
    busyScene = new QGraphicsScene(this);
    scene = new QGraphicsScene(this);
    scene->setItemIndexMethod(QGraphicsScene::BspTreeIndex);
    setScene(scene);

    busyNotification = ChatMessage::createBusyNotification(documentCache, settings, style);
    busyNotification->addToScene(busyScene);
    busyNotification->visibilityChanged(true);

    // Cfg.
    setInteractive(true);
    setAcceptDrops(false);
    setAlignment(Qt::AlignTop | Qt::AlignLeft);
    setHorizontalScrollBarPolicy(Qt::ScrollBarAlwaysOff);
    setDragMode(QGraphicsView::NoDrag);
    setViewportUpdateMode(MinimalViewportUpdate);
    setContextMenuPolicy(Qt::CustomContextMenu);
    setBackgroundBrush(QBrush(style.getColor(Style::ColorPalette::GroundBase), Qt::SolidPattern));

    // The selection rect for multi-line selection
    selGraphItem = scene->addRect(0, 0, 0, 0, selectionRectColor.darker(120), selectionRectColor);
    selGraphItem->setZValue(-1.0); // behind all other items

    // copy action (ie. Ctrl+C)
    copyAction = new QAction(this);
    copyAction->setIcon(QIcon::fromTheme("edit-copy"));
    copyAction->setShortcut(QKeySequence::Copy);
    copyAction->setEnabled(false);
    connect(copyAction, &QAction::triggered, this, [this]() { copySelectedText(); });
    addAction(copyAction);

    // Ctrl+Insert shortcut
    QShortcut* copyCtrlInsShortcut = new QShortcut(QKeySequence(Qt::CTRL + Qt::Key_Insert), this);
    connect(copyCtrlInsShortcut, &QShortcut::activated, this, [this]() { copySelectedText(); });

    // select all action (ie. Ctrl+A)
    selectAllAction = new QAction(this);
    selectAllAction->setIcon(QIcon::fromTheme("edit-select-all"));
    selectAllAction->setShortcut(QKeySequence::SelectAll);
    connect(selectAllAction, &QAction::triggered, this, [this]() { selectAll(); });
    addAction(selectAllAction);

    // This timer is used to scroll the view while the user is
    // moving the mouse past the top/bottom edge of the widget while selecting.
    selectionTimer = new QTimer(this);
    selectionTimer->setInterval(1000 / 30);
    selectionTimer->setSingleShot(false);
    selectionTimer->start();
    connect(selectionTimer, &QTimer::timeout, this, &ChatWidget::onSelectionTimerTimeout);

    // Background worker
    // Updates the layout of all chat-lines after a resize
    workerTimer = new QTimer(this);
    workerTimer->setSingleShot(false);
    workerTimer->setInterval(5);
    connect(workerTimer, &QTimer::timeout, this, &ChatWidget::onWorkerTimeout);

    // A file entry that grows or shrinks moves the lines after it, once per turn of the loop.
    resizedLater = new QTimer(this);
    resizedLater->setSingleShot(true);
    resizedLater->setInterval(0);
    connect(resizedLater, &QTimer::timeout, this, &ChatWidget::layoutResizedLines);

    // This timer is used to detect multiple clicks
    multiClickTimer = new QTimer(this);
    multiClickTimer->setSingleShot(true);
    multiClickTimer->setInterval(QApplication::doubleClickInterval());
    connect(multiClickTimer, &QTimer::timeout, this, &ChatWidget::onMultiClickTimeout);

    // selection
    connect(this, &ChatWidget::selectionChanged, this, [this]() {
        copyAction->setEnabled(hasTextToBeCopied());
        copySelectedText(true);
    });

    connect(&style, &Style::themeReload, this, &ChatWidget::reloadTheme);
    connect(&AirStyle::instance(), &AirStyle::changed, this, [this]() {
        setBackgroundBrush(QBrush(style.getColor(Style::ColorPalette::GroundBase), Qt::SolidPattern));
        viewport()->update();
    });

    reloadTheme();
    retranslateUi();
    Translator::registerHandler(std::bind(&ChatWidget::retranslateUi, this), this);

    connect(this, &ChatWidget::renderFinished, this, &ChatWidget::onRenderFinished);
    connect(&chatLog_, &IChatLog::itemUpdated, this, &ChatWidget::onMessageUpdated);
    connect(verticalScrollBar(), &QScrollBar::valueChanged, this, &ChatWidget::onScrollValueChanged);

    auto firstChatLogIdx = clampedAdd(chatLog_.getNextIdx(), -100, chatLog_);
    renderMessages(firstChatLogIdx, chatLog_.getNextIdx());
}

ChatWidget::~ChatWidget()
{
    Translator::unregister(this);

    // Remove chatlines from scene
    for (ChatLine::Ptr l : *chatLineStorage)
        l->removeFromScene();

    if (busyNotification)
        busyNotification->removeFromScene();

    if (typingNotification)
        typingNotification->removeFromScene();
}

void ChatWidget::clearSelection()
{
    if (selectionMode == SelectionMode::None)
        return;

    forEachLineIn(selFirstRow, selLastRow, *chatLineStorage, [&] (ChatLine::Ptr& line) {
        line->selectionCleared();
    });

    selFirstRow.reset();
    selLastRow.reset();
    selClickedCol = -1;
    selClickedRow.reset();

    selectionMode = SelectionMode::None;
    emit selectionChanged();

    updateMultiSelectionRect();
}

QRect ChatWidget::getVisibleRect() const
{
    return mapToScene(viewport()->rect()).boundingRect().toRect();
}

void ChatWidget::updateSceneRect()
{
    setSceneRect(calculateSceneRect());
}

void ChatWidget::layout(int start, int end, qreal width)
{
    if (chatLineStorage->empty())
        return;

    qreal h = 0.0;

    // Line at start-1 is considered to have the correct position. All following lines are
    // positioned in respect to this line. A collapsed line takes no room, not even the gap after it.
    if (start - 1 >= 0 && start - 1 < static_cast<int>(chatLineStorage->size())) {
        const ChatLine* previous = (*chatLineStorage)[start - 1].get();
        h = previous->sceneBoundingRect().bottom() + (previous->isCollapsed() ? 0.0 : lineSpacing);
    }

    start = clamp<int>(start, 0, chatLineStorage->size());
    end = clamp<int>(end + 1, 0, chatLineStorage->size());

    for (int i = start; i < end; ++i) {
        ChatLine* l = (*chatLineStorage)[i].get();

        l->layout(width, QPointF(0.0, h));
        if (!l->isCollapsed()) {
            h += l->sceneBoundingRect().height() + lineSpacing;
        }
    }
}

void ChatWidget::mousePressEvent(QMouseEvent* ev)
{
    QGraphicsView::mousePressEvent(ev);

    if (ev->button() == Qt::LeftButton) {
        clickPos = ev->pos();
        clearSelection();
    }

    if (lastClickButton == ev->button()) {
        // Counts only single clicks and first click of doule click
        clickCount++;
    }
    else {
        clickCount = 1; // restarting counter
        lastClickButton = ev->button();
    }
    lastClickPos = ev->pos();

    // Triggers on odd click counts
    handleMultiClickEvent();
}

void ChatWidget::mouseReleaseEvent(QMouseEvent* ev)
{
    QGraphicsView::mouseReleaseEvent(ev);

    selectionScrollDir = AutoScrollDirection::NoDirection;

    multiClickTimer->start();
}

void ChatWidget::mouseMoveEvent(QMouseEvent* ev)
{
    QGraphicsView::mouseMoveEvent(ev);

    QPointF scenePos = mapToScene(ev->pos());

    if (ev->buttons() & Qt::LeftButton) {
        // autoscroll
        if (ev->pos().y() < 0)
            selectionScrollDir = AutoScrollDirection::Up;
        else if (ev->pos().y() > height())
            selectionScrollDir = AutoScrollDirection::Down;
        else
            selectionScrollDir = AutoScrollDirection::NoDirection;

        // select
        if (selectionMode == SelectionMode::None
            && (clickPos - ev->pos()).manhattanLength() > QApplication::startDragDistance()) {
            QPointF sceneClickPos = mapToScene(clickPos.toPoint());
            ChatLine::Ptr line = findLineByPosY(scenePos.y());

            ChatLineContent* content = getContentFromPos(sceneClickPos);
            if (content) {
                selClickedRow = line;
                selClickedCol = content->getColumn();
                selFirstRow = line;
                selLastRow = line;

                content->selectionStarted(sceneClickPos);

                selectionMode = SelectionMode::Precise;

                // ungrab mouse grabber
                if (scene->mouseGrabberItem())
                    scene->mouseGrabberItem()->ungrabMouse();
            } else if (line.get()) {
                selClickedRow = line;
                selFirstRow = selClickedRow;
                selLastRow = selClickedRow;

                selectionMode = SelectionMode::Multi;
            }
        }

        if (selectionMode != SelectionMode::None) {
            ChatLineContent* content = getContentFromPos(scenePos);
            ChatLine::Ptr line = findLineByPosY(scenePos.y());

            if (content) {
                int col = content->getColumn();

                if (line == selClickedRow && col == selClickedCol) {
                    selectionMode = SelectionMode::Precise;

                    content->selectionMouseMove(scenePos);
                    selGraphItem->hide();
                } else if (col != selClickedCol) {
                    selectionMode = SelectionMode::Multi;

                    line->selectionCleared();
                }
            } else if (line.get()) {
                if (line != selClickedRow) {
                    selectionMode = SelectionMode::Multi;
                    line->selectionCleared();
                }
            } else {
                return;
            }

            auto selClickedIt = chatLineStorage->find(selClickedRow);
            auto lineIt = chatLineStorage->find(line);
            if (lineIt > selClickedIt)
                selLastRow = line;

            if (lineIt <= selClickedIt)
                selFirstRow = line;

            updateMultiSelectionRect();
        }

        emit selectionChanged();
    }
}

// Much faster than QGraphicsScene::itemAt()!
ChatLineContent* ChatWidget::getContentFromPos(QPointF scenePos) const
{
    if (chatLineStorage->empty())
        return nullptr;

    auto itr =
        std::lower_bound(chatLineStorage->begin(), chatLineStorage->end(), scenePos.y(), ChatLine::lessThanBSRectBottom);

    // find content
    if (itr != chatLineStorage->end() && (*itr)->sceneBoundingRect().contains(scenePos))
        return (*itr)->getContent(scenePos);

    return nullptr;
}

bool ChatWidget::isOverSelection(QPointF scenePos) const
{
    if (selectionMode == SelectionMode::Precise) {
        ChatLineContent* content = getContentFromPos(scenePos);

        if (content)
            return content->isOverSelection(scenePos);
    } else if (selectionMode == SelectionMode::Multi) {
        if (selGraphItem->rect().contains(scenePos))
            return true;
    }

    return false;
}

qreal ChatWidget::useableWidth() const
{
    return width() - verticalScrollBar()->sizeHint().width() - margins.right() - margins.left();
}

void ChatWidget::insertChatlines(std::map<ChatLogIdx, ChatLine::Ptr> chatLines)
{
    if (chatLines.empty())
        return;

    bool allLinesAtEnd = !chatLineStorage->hasIndexedMessage() || chatLines.begin()->first > chatLineStorage->lastIdx();
    auto startLineSize = chatLineStorage->size();

    QGraphicsScene::ItemIndexMethod oldIndexMeth = scene->itemIndexMethod();
    scene->setItemIndexMethod(QGraphicsScene::NoIndex);

    for (auto const& chatLine : chatLines) {
        auto idx = chatLine.first;
        auto const& l = chatLine.second;

        chatLineStorage->insertChatMessage(idx, chatLog.at(idx).getTimestamp(), l);

#if (QT_VERSION >= QT_VERSION_CHECK(5, 15, 0))
        auto date = chatLog.at(idx).getTimestamp().date().startOfDay();
#else
        auto date = QDateTime(chatLog.at(idx).getTimestamp().date());
#endif

        if (!chatLineStorage->contains(date)) {
            // If there is no dateline for the given date we need to insert it
            // above the line we'd like to insert.
            auto dateLine = createDateMessage(date, documentCache, settings, style);
            chatLineStorage->insertDateLine(date, dateLine);
            dateLine->addToScene(scene);
            dateLine->visibilityChanged(false);
        }

        l->addToScene(scene);

        // Optimization copied from previous implementation of upwards
        // rendering. This will be changed when we call updateVisibility
        // later
        l->visibilityChanged(false);
    }

    scene->setItemIndexMethod(oldIndexMeth);

    // New lines may also open a day whose mark was folded, or close a run of one author.
    const size_t firstMoved = applyEntryFlags();
    if (firstMoved < startLineSize) {
        startLineSize = firstMoved;
    }

    // If all insertions are at the bottom we can get away with only rendering
    // the updated lines, otherwise we need to go through the resize workflow to
    // re-layout everything asynchronously.
    //
    // NOTE: This can make flow from the callers a little frustrating as you
    // have to rely on the onRenderFinished callback to continue doing any work,
    // even if all rendering is done synchronously
    if (allLinesAtEnd) {
        bool stickToBtm = stickToBottom();

        // partial refresh
        layout(startLineSize, chatLineStorage->size(), useableWidth());
        updateSceneRect();

        if (stickToBtm)
            scrollToBottom();

        checkVisibility();
        updateTypingNotification();
        updateMultiSelectionRect();

        emit renderFinished();
    } else {
        startResizeWorker();
    }
}

bool ChatWidget::stickToBottom() const
{
    return verticalScrollBar()->value() == verticalScrollBar()->maximum();
}

void ChatWidget::scrollToBottom()
{
    updateSceneRect();
    verticalScrollBar()->setValue(verticalScrollBar()->maximum());
}

void ChatWidget::startResizeWorker()
{
    if (chatLineStorage->empty())
        return;

    // (re)start the worker
    if (!workerTimer->isActive()) {
        // these values must not be reevaluated while the worker is running
        workerStb = stickToBottom();

        if (!visibleLines.empty())
            workerAnchorLine = visibleLines.first();
    }

    // switch to busy scene displaying the busy notification if there is a lot
    // of text to be resized
    int txt = 0;
    for (ChatLine::Ptr line : *chatLineStorage) {
        if (txt > 500000)
            break;
        for (ChatLineContent* content : line->content)
            txt += content->getText().size();
    }
    if (txt > 500000)
        setScene(busyScene);

    workerLastIndex = 0;
    workerTimer->start();

    verticalScrollBar()->hide();
}

void ChatWidget::mouseDoubleClickEvent(QMouseEvent* ev)
{
    QPointF scenePos = mapToScene(ev->pos());
    ChatLineContent* content = getContentFromPos(scenePos);
    ChatLine::Ptr line = findLineByPosY(scenePos.y());

    if (content) {
        content->selectionDoubleClick(scenePos);
        selClickedCol = content->getColumn();
        selClickedRow = line;
        selFirstRow = line;
        selLastRow = line;
        selectionMode = SelectionMode::Precise;

        emit selectionChanged();
    }

    if (lastClickButton == ev->button()) {
        // Counts the second click of double click
        clickCount++;
    }
    else {
        clickCount = 1; // restarting counter
        lastClickButton = ev->button();
    }
    lastClickPos = ev->pos();

    // Triggers on even click counts
    handleMultiClickEvent();
}

QString ChatWidget::getSelectedText() const
{
    if (selectionMode == SelectionMode::Precise) {
        return selClickedRow->content[selClickedCol]->getSelectedText();
    } else if (selectionMode == SelectionMode::Multi) {
        // build a nicely formatted message
        QString out;

        forEachLineIn(selFirstRow, selLastRow, *chatLineStorage, [&](ChatLine::Ptr& line) {
            if (line->isCollapsed() || line->content[1]->getText().isEmpty())
                return;

            //: Copied text of several messages: in place of the time of one that has not gone yet
            QString timestamp = line->content[2]->getText().isEmpty() ? tr("in transit")
                                                                      : line->content[2]->getText();
            QString author = line->content[0]->getText();
            QString msg = line->content[1]->getText();

            out +=
                QString(out.isEmpty() ? QStringLiteral("[%2] %1: %3") : QStringLiteral("\n[%2] %1: %3")).arg(author, timestamp, msg);
        });

        return out;
    }

    return QString();
}

std::vector<ChatLine::Ptr> ChatWidget::spokenLines() const
{
    std::vector<ChatLine::Ptr> spoken;
    spoken.reserve(chatLineStorage->size());
    for (size_t i = 0; i < chatLineStorage->size(); ++i) {
        const ChatLine::Ptr& line = (*chatLineStorage)[i];
        if (line && !line->isCollapsed()) {
            spoken.push_back(line);
        }
    }
    return spoken;
}

QRect ChatWidget::viewportRectOf(const ChatLine& line) const
{
    return mapFromScene(line.sceneBoundingRect()).boundingRect();
}

bool ChatWidget::isEmpty() const
{
    return chatLineStorage->empty();
}

bool ChatWidget::hasTextToBeCopied() const
{
    return selectionMode != SelectionMode::None;
}

/**
 * @brief Finds the chat line object at a position on screen
 * @param pos Position on screen in global coordinates
 * @sa getContentFromPos()
 */
ChatLineContent* ChatWidget::getContentFromGlobalPos(QPoint pos) const
{
    return getContentFromPos(mapToScene(mapFromGlobal(pos)));
}

bool ChatWidget::entryAtGlobalPos(QPoint pos, ChatLogIdx& idx) const
{
    if (!chatLineStorage->hasIndexedMessage()) {
        return false;
    }
    const QPointF scenePos = mapToScene(mapFromGlobal(pos));
    const ChatLine::Ptr line = findLineByPosY(scenePos.y());
    if (!line || line->isCollapsed()) {
        return false;
    }
    const QRectF bounds = line->sceneBoundingRect();
    if (scenePos.y() < bounds.top() || scenePos.y() > bounds.bottom()) {
        return false;
    }
    for (auto i = chatLineStorage->firstIdx(); i <= chatLineStorage->lastIdx(); ++i) {
        if (chatLineStorage->contains(i) && (*chatLineStorage)[i] == line) {
            idx = i;
            return true;
        }
    }
    return false;
}

void ChatWidget::clear()
{
    clearSelection();

    QVector<ChatLine::Ptr> savedLines;

    for (auto it = chatLineStorage->begin(); it != chatLineStorage->end();) {
        if (!isActiveFileTransfer(*it)) {
            (*it)->removeFromScene();
            it = chatLineStorage->erase(it);
        } else {
            it++;
        }
    }

    visibleLines.clear();

    checkVisibility();
    updateSceneRect();
}

void ChatWidget::copySelectedText(bool toSelectionBuffer) const
{
    QString text = getSelectedText();
    QClipboard* clipboard = QApplication::clipboard();

    if (clipboard && !text.isNull())
        clipboard->setText(text, toSelectionBuffer ? QClipboard::Selection : QClipboard::Clipboard);
}

void ChatWidget::setTypingNotificationVisible(bool visible)
{
    typingShown = visible;
    if (typingNotification.get()) {
        typingNotification->setVisible(visible);
        updateTypingNotification();
        updateSceneRect();
    }
}

void ChatWidget::setTypingNotificationName(const QString& displayName)
{
    if (!typingNotification.get()) {
        setTypingNotification();
    }

    Text* text = static_cast<Text*>(typingNotification->getContent(1));
    QString typingDiv = "<div class=typing>%1</div>";
    text->setText(typingDiv.arg(tr("%1 is typing").arg(displayName)));

    updateTypingNotification();
}

void ChatWidget::scrollToLine(ChatLine::Ptr line)
{
    if (!line.get())
        return;

    updateSceneRect();
    verticalScrollBar()->setValue(line->sceneBoundingRect().top());
}

void ChatWidget::selectAll()
{
    if (chatLineStorage->empty())
        return;

    clearSelection();

    selectionMode = SelectionMode::Multi;
    selFirstRow = chatLineStorage->front();;
    selLastRow = chatLineStorage->back();

    emit selectionChanged();
    updateMultiSelectionRect();
}

void ChatWidget::fontChanged(const QFont& font)
{
    for (ChatLine::Ptr l : *chatLineStorage) {
        l->fontChanged(font);
    }
}

void ChatWidget::reloadTheme()
{
    setStyleSheet(style.getStylesheet("chatArea/chatArea.css", settings));
    setBackgroundBrush(QBrush(style.getColor(Style::ColorPalette::GroundBase), Qt::SolidPattern));
    selectionRectColor = style.getColor(Style::ColorPalette::SelectText);
    selGraphItem->setBrush(QBrush(selectionRectColor));
    selGraphItem->setPen(QPen(selectionRectColor.darker(120)));
    setTypingNotification();

    for (ChatLine::Ptr l : *chatLineStorage) {
        l->reloadTheme();
    }
}

void ChatWidget::startSearch(const QString& phrase, const ParameterSearch& parameter)
{
    disableSearchText();

    bool bForwardSearch = false;
    switch (parameter.period) {
    case PeriodSearch::WithTheFirst: {
        bForwardSearch = true;
        searchPos.logIdx = chatLog.getFirstIdx();
        searchPos.numMatches = 0;
        break;
    }
    case PeriodSearch::WithTheEnd:
    case PeriodSearch::None: {
        bForwardSearch = false;
        searchPos.logIdx = chatLog.getNextIdx();
        searchPos.numMatches = 0;
        break;
    }
    case PeriodSearch::AfterDate: {
        bForwardSearch = true;
        searchPos.logIdx = firstItemAfterDate(parameter.date, chatLog);
        searchPos.numMatches = 0;
        break;
    }
    case PeriodSearch::BeforeDate: {
        bForwardSearch = false;
        searchPos.logIdx = firstItemAfterDate(parameter.date, chatLog);
        searchPos.numMatches = 0;
        break;
    }
    }

    if (bForwardSearch) {
        onSearchDown(phrase, parameter);
    } else {
        onSearchUp(phrase, parameter);
    }
}

void ChatWidget::onSearchUp(const QString& phrase, const ParameterSearch& parameter)
{
    auto result = chatLog.searchBackward(searchPos, phrase, parameter);
    handleSearchResult(result, SearchDirection::Up);
}

void ChatWidget::onSearchDown(const QString& phrase, const ParameterSearch& parameter)
{
    auto result = chatLog.searchForward(searchPos, phrase, parameter);
    handleSearchResult(result, SearchDirection::Down);
}

void ChatWidget::handleSearchResult(SearchResult result, SearchDirection direction)
{
    if (!result.found) {
        emit messageNotFoundShow(direction);
        return;
    }

    disableSearchText();

    searchPos = result.pos;

    auto selectText = [this, result] {
        // With fast changes our callback could become invalid, ensure that the
        // index we want to view is still actually visible
        if (!chatLineStorage->contains(searchPos.logIdx))
            return;

        auto msg = (*chatLineStorage)[searchPos.logIdx];
        scrollToLine(msg);

        auto text = qobject_cast<Text*>(msg->getContent(1));
        text->selectText(result.exp, std::make_pair(result.start, result.len));
    };

    // If the requested element is visible the render completion callback will
    // not be called, we need to figure out which path we're going to take
    // before we take it.
    if (chatLineStorage->contains(searchPos.logIdx)) {
        jumpToIdx(searchPos.logIdx);
        selectText();
    } else {
        renderCompletionFns.push_back(selectText);
        jumpToIdx(searchPos.logIdx);
    }

}

void ChatWidget::forceRelayout()
{
    startResizeWorker();
}

void ChatWidget::checkVisibility()
{
    if (chatLineStorage->empty())
        return;

    // find first visible line
    auto lowerBound = std::lower_bound(chatLineStorage->begin(), chatLineStorage->end(), getVisibleRect().top(),
                                       ChatLine::lessThanBSRectBottom);

    // find last visible line
    auto upperBound = std::lower_bound(lowerBound, chatLineStorage->end(), getVisibleRect().bottom(),
                                       ChatLine::lessThanBSRectTop);

    const ChatLine::Ptr lastLineBeforeVisible = lowerBound == chatLineStorage->begin()
        ? ChatLine::Ptr()
        : *std::prev(lowerBound);

    // set visibilty
    QList<ChatLine::Ptr> newVisibleLines;
    for (auto itr = lowerBound; itr != upperBound; ++itr) {
        newVisibleLines.append(*itr);

        if (!visibleLines.contains(*itr))
            (*itr)->visibilityChanged(true);

        visibleLines.removeOne(*itr);
    }

    // these lines are no longer visible
    for (ChatLine::Ptr line : visibleLines)
        line->visibilityChanged(false);

    visibleLines = newVisibleLines;

    if (!visibleLines.isEmpty()) {
        emit firstVisibleLineChanged(lastLineBeforeVisible, visibleLines.at(0));
    }
}

void ChatWidget::scrollContentsBy(int dx, int dy)
{
    QGraphicsView::scrollContentsBy(dx, dy);
    // The view moves what it shows by copying it; the focus ring stays where it is, so the view
    // is painted afresh while the ring shows.
    if (AirPaint::keyboardFocus(this)) {
        viewport()->update();
    }
    checkVisibility();
}

void ChatWidget::resizeEvent(QResizeEvent* ev)
{
    bool stb = stickToBottom();

    if (ev->size().width() != ev->oldSize().width()) {
        startResizeWorker();
        stb = false; // let the resize worker handle it
    }

    QGraphicsView::resizeEvent(ev);

    if (stb)
        scrollToBottom();

    updateBusyNotification();
}

void ChatWidget::updateMultiSelectionRect()
{
    if (selectionMode == SelectionMode::Multi && selFirstRow && selLastRow) {
        QRectF selBBox;
        selBBox = selBBox.united(selFirstRow->sceneBoundingRect());
        selBBox = selBBox.united(selLastRow->sceneBoundingRect());

        if (selGraphItem->rect() != selBBox)
            scene->invalidate(selGraphItem->rect());

        selGraphItem->setRect(selBBox);
        selGraphItem->show();
    } else {
        selGraphItem->hide();
    }
}

void ChatWidget::updateTypingNotification()
{
    ChatLine* notification = typingNotification.get();
    if (!notification)
        return;

    qreal posY = 0.0;

    if (!chatLineStorage->empty())
        posY = chatLineStorage->back()->sceneBoundingRect().bottom() + lineSpacing;

    notification->layout(useableWidth(), QPointF(0.0, posY));
}

void ChatWidget::updateBusyNotification()
{
    // repoisition the busy notification (centered)
    busyNotification->layout(useableWidth(), getVisibleRect().topLeft()
                                                    + QPointF(0, getVisibleRect().height() / 2.0));
}

ChatLine::Ptr ChatWidget::findLineByPosY(qreal yPos) const
{
    auto itr = std::lower_bound(chatLineStorage->begin(), chatLineStorage->end(), yPos, ChatLine::lessThanBSRectBottom);

    if (itr != chatLineStorage->end())
        return *itr;

    return ChatLine::Ptr();
}

void ChatWidget::removeLines(ChatLogIdx begin, ChatLogIdx end)
{
    if (!chatLineStorage->hasIndexedMessage()) {
        // No indexed lines to remove
        return;
    }

    begin = clamp<ChatLogIdx>(begin, chatLineStorage->firstIdx(), chatLineStorage->lastIdx());
    end = clamp<ChatLogIdx>(end, chatLineStorage->firstIdx(), chatLineStorage->lastIdx()) + 1;

    // NOTE: Optimization potential if this find proves to be too expensive.
    // Batching all our erases into one call would be more efficient
    for (auto it = chatLineStorage->find(begin); it != chatLineStorage->find(end);) {
        (*it)->removeFromScene();
        it = chatLineStorage->erase(it);
    }

    // We need to re-layout anything that is after any line we removed. We could
    // probably be smarter and try to only re-render anything under what we
    // removed, but with the sliding window there doesn't seem to be much need
    if (chatLineStorage->hasIndexedMessage() && begin <= chatLineStorage->lastIdx()) {
        layout(0, chatLineStorage->size(), useableWidth());
    }
}

QRectF ChatWidget::calculateSceneRect() const
{
    qreal bottom = (chatLineStorage->empty() ? 0.0 : chatLineStorage->back()->sceneBoundingRect().bottom());

    if (typingNotification.get() != nullptr && typingShown)
        bottom += typingNotification->sceneBoundingRect().height() + lineSpacing;

    return QRectF(-margins.left(), -margins.top(), useableWidth(),
                  bottom + margins.bottom() + margins.top());
}

void ChatWidget::onSelectionTimerTimeout()
{
    const int scrollSpeed = 10;

    switch (selectionScrollDir) {
    case AutoScrollDirection::Up:
        verticalScrollBar()->setValue(verticalScrollBar()->value() - scrollSpeed);
        break;
    case AutoScrollDirection::Down:
        verticalScrollBar()->setValue(verticalScrollBar()->value() + scrollSpeed);
        break;
    default:
        break;
    }
}

void ChatWidget::onWorkerTimeout()
{
    // Fairly arbitrary but
    // large values will make the UI unresponsive
    const int stepSize = 50;

    layout(workerLastIndex, workerLastIndex + stepSize, useableWidth());
    workerLastIndex += stepSize;

    // done?
    if (workerLastIndex >= chatLineStorage->size()) {
        workerTimer->stop();

        // switch back to the scene containing the chat messages
        setScene(scene);

        // make sure everything gets updated
        updateSceneRect();
        checkVisibility();
        updateTypingNotification();
        updateMultiSelectionRect();

        // scroll
        if (workerStb)
            scrollToBottom();
        else
            scrollToLine(workerAnchorLine);

        // don't keep a Ptr to the anchor line
        workerAnchorLine = ChatLine::Ptr();

        // hidden during busy screen
        verticalScrollBar()->show();

        emit renderFinished();
    }
}

void ChatWidget::onMultiClickTimeout()
{
    clickCount = 0;
}

void ChatWidget::onMessageUpdated(ChatLogIdx idx)
{
    if (shouldRenderMessage(idx)) {
        renderMessage(idx);
    }
}

void ChatWidget::renderMessage(ChatLogIdx idx)
{
    renderMessages(idx, idx + 1);
}

void ChatWidget::renderMessages(ChatLogIdx begin, ChatLogIdx end)
{
    auto linesToRender = std::map<ChatLogIdx, ChatLine::Ptr>();

    bool updated = false;
    for (auto i = begin; i < end; ++i) {
        bool alreadyRendered = chatLineStorage->contains(i);
        bool prevIdxRendered = i != begin || chatLineStorage->contains(i - 1);

        auto chatMessage = alreadyRendered ? (*chatLineStorage)[i] : ChatLine::Ptr();
        renderItem(chatLog.at(i), needsToHideName(i, prevIdxRendered), colorizeNames, chatMessage);

        if (!alreadyRendered) {
            linesToRender.insert({i, chatMessage});
        } else {
            updated = true;
        }
    }

    insertChatlines(linesToRender);
    // A line that changed in place may have left the log (cancelled, failed, waiting in the tray).
    if (updated && linesToRender.empty()) {
        refreshEntries();
    }
}

void ChatWidget::setRenderedWindowStart(ChatLogIdx begin)
{
    // End of the window is pre-determined as a hardcoded window size relative
    // to the start
    auto end = clampedAdd(begin, maxWindowSize, chatLog);

    // Use invalid + equal ChatLogIdx to force a full re-render if we do not
    // have an indexed message to compare to
    ChatLogIdx currentStart = ChatLogIdx(-1);
    ChatLogIdx currentEnd = ChatLogIdx(-1);

    if (chatLineStorage->hasIndexedMessage()) {
        currentStart = chatLineStorage->firstIdx();
        currentEnd = chatLineStorage->lastIdx() + 1;
    }

    // If the window is already where we have no work to do
    if (currentStart == begin) {
        emit renderFinished();
        return;
    }

    // NOTE: This is more than an optimization, this is important for
    // selection consistency. If we re-create lines that are already rendered
    // the selXXXRow members will now be pointing to the wrong ChatLine::Ptr!
    // Please be sure to test selection logic when scrolling around loading
    // boundaries if changing this logic.
    if (begin < currentEnd && begin > currentStart) {
        // Remove leading lines
        removeLines(currentStart, begin);
        renderMessages(currentEnd, end);
    }
    else if (end <= currentEnd && end > currentStart) {
        // Remove trailing lines
        removeLines(end, currentEnd);
        renderMessages(begin, currentStart);
    }
    else {
        removeLines(currentStart, currentEnd);
        renderMessages(begin, end);
    }
}

void ChatWidget::setRenderedWindowEnd(ChatLogIdx end)
{
    // Off by 1 since the maxWindowSize is not inclusive
    auto start = clampedAdd(end, -maxWindowSize + 1, chatLog);

    setRenderedWindowStart(start);
}

void ChatWidget::onRenderFinished()
{
    // We have to back these up before we run them, because people might queue
    // on _more_ items on top of the ones we want. If they do this while we're
    // iterating we can hit some memory corruption issues
    auto renderCompletionFnsLocal = renderCompletionFns;
    renderCompletionFns.clear();

    while (renderCompletionFnsLocal.size()) {
        renderCompletionFnsLocal.back()();
        renderCompletionFnsLocal.pop_back();
    }

    // NOTE: this is a regression from previous behavior. We used to be able to
    // load an infinite amount of chat and copy paste it out. Now we limit the
    // user to 300 elements and any time the elements change our selection gets
    // invalidated. This could be improved in the future but for now I  do not
    // believe this is a serious usage impediment. Chats can be exported if a
    // user really needs more than 300 messages to be copied
    if (chatLineStorage->find(selFirstRow) == chatLineStorage->end() ||
        chatLineStorage->find(selLastRow) == chatLineStorage->end() ||
        chatLineStorage->find(selClickedRow) == chatLineStorage->end())
    {
        // FIXME: Segfault when selecting while scrolling down
        clearSelection();
    }
}

void ChatWidget::onScrollValueChanged(int value)
{
    if (!chatLineStorage->hasIndexedMessage()) {
        // This could be a little better. On a cleared screen we should probably
        // be able to scroll, but this makes the rest of this function easier
        return;
    }

    // When we hit the end of our scroll bar we change the content that's in the
    // viewport. In this process our scroll value may end up changing again! We
    // avoid this by changing our scroll position to match where it was before
    // started our viewport change, but we need to filter out any intermediate
    // scroll events triggered before we get to that point
    if (!scrollMonitoringEnabled) {
        return;
    }

    if (value == verticalScrollBar()->minimum())
    {
        auto idx = clampedAdd(chatLineStorage->firstIdx(), -static_cast<int>(windowChunkSize), chatLog);

        if (idx != chatLineStorage->firstIdx()) {
            auto currentTop = (*chatLineStorage)[chatLineStorage->firstIdx()];

            renderCompletionFns.push_back([this, currentTop] {
                scrollToLine(currentTop);
                scrollMonitoringEnabled = true;
            });

            scrollMonitoringEnabled = false;
            setRenderedWindowStart(idx);
        }

    }
    else if (value == verticalScrollBar()->maximum())
    {
        auto idx = clampedAdd(chatLineStorage->lastIdx(), static_cast<int>(windowChunkSize), chatLog);

        if (idx != chatLineStorage->lastIdx() + 1) {
            // FIXME: This should be the top line
            auto currentBottomIdx = chatLineStorage->lastIdx();
            auto currentTopPx = mapToScene(0, 0).y();
            auto currentBottomPx = (*chatLineStorage)[currentBottomIdx]->sceneBoundingRect().bottom();
            auto bottomOffset = currentBottomPx - currentTopPx;

            renderCompletionFns.push_back([this, currentBottomIdx, bottomOffset] {
                auto it = chatLineStorage->find(currentBottomIdx);
                if (it != chatLineStorage->end()) {
                    updateSceneRect();
                    verticalScrollBar()->setValue((*it)->sceneBoundingRect().bottom() - bottomOffset);
                    scrollMonitoringEnabled = true;
                }
            });

            scrollMonitoringEnabled = false;
            setRenderedWindowEnd(idx);
        }
    }
}

void ChatWidget::handleMultiClickEvent()
{
    // Ignore single or double clicks
    if (clickCount < 2)
        return;

    switch (clickCount) {
    case 3:
        QPointF scenePos = mapToScene(lastClickPos);
        ChatLineContent* content = getContentFromPos(scenePos);
        ChatLine::Ptr line = findLineByPosY(scenePos.y());

        if (content) {
            content->selectionTripleClick(scenePos);
            selClickedCol = content->getColumn();
            selClickedRow = line;
            selFirstRow = line;
            selLastRow = line;
            selectionMode = SelectionMode::Precise;

            emit selectionChanged();
        }
        break;
    }
}

void ChatWidget::showEvent(QShowEvent* event)
{
    std::ignore = event;
    // Empty.
    // The default implementation calls centerOn - for some reason - causing
    // the scrollbar to move.
}

void ChatWidget::hideEvent(QHideEvent* event)
{
    std::ignore = event;
    // Purge accumulated lines from the chatlog. We do not purge messages while
    // the chatlog is open because it causes flickers. When a user leaves the
    // chat we take the opportunity to remove old messages. If a user only has
    // one friend this could end up accumulating chat logs until they restart
    // qTox, but that isn't a regression from previously released behavior.

    auto numLinesToRemove = chatLineStorage->size() > maxWindowSize
        ? chatLineStorage->size() - maxWindowSize
        : 0;

    if (numLinesToRemove > 0) {
        removeLines(chatLineStorage->firstIdx(), chatLineStorage->firstIdx() + numLinesToRemove);
        startResizeWorker();
    }
}

void ChatWidget::drawForeground(QPainter* painter, const QRectF& rect)
{
    QGraphicsView::drawForeground(painter, rect);
    if (!AirPaint::keyboardFocus(this)) {
        return;
    }
    // In the coordinates of the viewport: the ring stands at its edge, whatever is scrolled.
    painter->save();
    painter->resetTransform();
    AirPaint::focusRing(*painter, QRectF(viewport()->rect()), AirTokens::Radius::control);
    painter->restore();
}

void ChatWidget::focusInEvent(QFocusEvent* ev)
{
    QGraphicsView::focusInEvent(ev);
    viewport()->update();

    if (selectionMode != SelectionMode::None) {
        selGraphItem->setBrush(QBrush(selectionRectColor));

        auto endIt = chatLineStorage->find(selLastRow);
        // Increase by one since this selLastRow is inclusive, not exclusive
        // like our loop expects
        if (endIt != chatLineStorage->end()) {
            endIt++;
        }

        for (auto it = chatLineStorage->begin(); it != chatLineStorage->end() && it != endIt; ++it)
            (*it)->selectionFocusChanged(true);
    }
}

void ChatWidget::focusOutEvent(QFocusEvent* ev)
{
    QGraphicsView::focusOutEvent(ev);
    viewport()->update();

    if (selectionMode != SelectionMode::None) {
        selGraphItem->setBrush(QBrush(selectionRectColor.lighter(120)));

        auto endIt = chatLineStorage->find(selLastRow);
        // Increase by one since this selLastRow is inclusive, not exclusive
        // like our loop expects
        if (endIt != chatLineStorage->end()) {
            endIt++;
        }

        for (auto it = chatLineStorage->begin(); it != chatLineStorage->end() && it != endIt; ++it)
            (*it)->selectionFocusChanged(false);
    }
}

void ChatWidget::wheelEvent(QWheelEvent *event)
{
    QGraphicsView::wheelEvent(event);
    checkVisibility();
}

void ChatWidget::retranslateUi()
{
    copyAction->setText(tr("Copy"));
    selectAllAction->setText(tr("Select all"));
    // What a screen reader says when Tab brings the focus to the messages.
    setAccessibleName(AirStrings::text(AirStrings::Text::TranscriptName));

    // A language written the other way turns the transcript around: the texts take the new start
    // edge and the lines are laid out again.
    const Qt::LayoutDirection direction = QGuiApplication::layoutDirection();
    const bool turned = direction != lineDirection;
    if (turned) {
        lineDirection = direction;
        for (ChatLine::Ptr l : *chatLineStorage) {
            l->reloadTheme();
        }
    }

    // The words the transcript writes itself come in the new language at once: the day marks
    // ("Today", "Yesterday", a date) and "You" over your own messages.
    const QString code = settings.getTranslation();
    const QLocale locale = code.isEmpty() ? QLocale::system() : QLocale(code);
    const QDate today = QDate::currentDate();
    bool renamed = false;
    for (const auto& day : chatLineStorage->dateLines()) {
        if (auto* const label = qobject_cast<AirLabel*>(day.first->getContent(0))) {
            label->setText(AirStrings::dayMarkText(day.second.date(), today, locale));
            renamed = true;
        }
    }
    if (chatLineStorage->hasIndexedMessage()) {
        const QString you = AirStrings::text(AirStrings::Text::You);
        const ToxPk self = core.getSelfPublicKey();
        for (auto i = chatLineStorage->firstIdx(); i <= chatLineStorage->lastIdx(); ++i) {
            if (!chatLineStorage->contains(i)) {
                continue;
            }
            const ChatLogItem& item = chatLog.at(i);
            if (item.getContentType() == ChatLogItem::ContentType::systemMessage) {
                // The words of a call entry.
                Air::CallEntry call;
                auto* const line = qobject_cast<AirCallLine*>((*chatLineStorage)[i]->getContent(1));
                if (line && Air::callEntryOf(item.getContentAsSystemMessage(), &call)) {
                    line->setText(AirStrings::callEntryText(call));
                    renamed = true;
                }
                continue;
            }
            if (item.getSender() != self) {
                continue;
            }
            if (auto* const label = qobject_cast<AirLabel*>((*chatLineStorage)[i]->getContent(0))) {
                label->setText(you);
                renamed = true;
            }
        }
    }
    if (turned || renamed) {
        forceRelayout();
    }
}

bool ChatWidget::isActiveFileTransfer(ChatLine::Ptr l)
{
    int count = l->getColumnCount();
    for (int i = 0; i < count; ++i) {
        ChatLineContent* content = l->getContent(i);
        ChatLineContentProxy* proxy = qobject_cast<ChatLineContentProxy*>(content);
        if (!proxy)
            continue;

        QWidget* widget = proxy->getWidget();
        FileTransferWidget* transferWidget = qobject_cast<FileTransferWidget*>(widget);
        if (transferWidget && transferWidget->isActive())
            return true;
    }

    return false;
}

void ChatWidget::setTypingNotification()
{
    typingNotification = ChatMessage::createTypingNotification(documentCache, settings, style);
    typingNotification->visibilityChanged(true);
    typingNotification->setVisible(false);
    typingNotification->addToScene(scene);
    updateTypingNotification();
}


void ChatWidget::renderItem(const ChatLogItem& item, bool hideName, bool colorizeNames_, ChatLine::Ptr& chatMessage)
{
    // Every author has a tone of their own in the redesign, so the colorized names option has
    // nothing left to switch.
    std::ignore = colorizeNames_;
    const ChatMessage::AirLook look = lookOf(item);

    switch (item.getContentType()) {
    case ChatLogItem::ContentType::message: {
        const auto& chatLogMessage = item.getContentAsMessage();

        if (renderMessageRaw(look, item.getDisplayName(), chatLogMessage, chatMessage,
                             documentCache, smileyPack, settings, style)) {
            resizedLines.push_back(chatMessage);
            resizedLater->start();
        }

        break;
    }
    case ChatLogItem::ContentType::fileTransfer: {
        const auto& file = item.getContentAsFile();
        renderFile(look, file.file, item.getTimestamp(), file.marks, chatMessage);
        break;
    }
    case ChatLogItem::ContentType::systemMessage: {
        const auto& systemMessage = item.getContentAsSystemMessage();

        // A call is an entry of its own: one row per call, with its length or how it went.
        Air::CallEntry call;
        if (Air::callEntryOf(systemMessage, &call)) {
            const bool answered = call.outcome == Air::CallOutcome::Answered
                                  || call.outcome == Air::CallOutcome::CutOff;
            chatMessage = ChatMessage::createCallEntry(AirStrings::callEntryText(call), answered,
                                                       systemMessage.timestamp, documentCache,
                                                       settings, style);
            hideName = true;
            break;
        }
        auto chatMessageType = getChatMessageType(systemMessage);
        chatMessage = ChatMessage::createChatInfoMessage(systemMessage.toString(),
            chatMessageType, systemMessage.timestamp, documentCache, settings,
            style);
        // A line nobody wrote has no author to show.
        hideName = true;
        break;
    }
    }

    // Notes are all yours: no author over any of them.
    chatMessage->setAuthorShown(!hideName && !notesChat);
}

ChatMessage::AirLook ChatWidget::lookOf(const ChatLogItem& item) const
{
    ChatMessage::AirLook look;
    const bool isSelf = item.getSender() == core.getSelfPublicKey();
    if (isSelf) {
        look.who = AirStrings::text(AirStrings::Text::You);
        look.tone = -1;
        // Notes are all yours: a screen reader says no author over them either.
        look.spokenWho = notesChat ? QString() : look.who;
    } else {
        look.who = Air::shortName(item.getDisplayName());
        look.tone = Air::toneIndex(item.getSender().toString());
        look.spokenWho = item.getDisplayName();
    }
    const ChatLogItem::ContentType type = item.getContentType();
    look.hasMark = personChat && isSelf
                   && (type == ChatLogItem::ContentType::message
                       || type == ChatLogItem::ContentType::fileTransfer);
    look.status = look.hasMark ? statusOf(item) : Air::EntryStatus::None;
    return look;
}

Air::EntryStatus ChatWidget::statusOf(const ChatLogItem& item) const
{
    if (item.getContentType() == ChatLogItem::ContentType::fileTransfer) {
        // Two checks once the person has all of it, a clock while it waits or moves.
        return Air::fileStatus(item.getContentAsFile().file.status, personOnline);
    }
    if (item.getContentType() != ChatLogItem::ContentType::message) {
        return Air::EntryStatus::None;
    }
    const MessageState state = item.getContentAsMessage().state;
    // toxcore takes a message as soon as the person is online, so a pending one has left this
    // computer then: there is no "sending" state on the desktop.
    return Air::directTextStatus(false, state == MessageState::complete,
                                 state == MessageState::broken, true, personOnline);
}

bool ChatWidget::isCollapsedAt(ChatLogIdx idx) const
{
    if (!chatLog.isShown(idx)) {
        return true;
    }
    if (!personChat || personOnline) {
        return false;
    }
    // While the person is offline, our messages waiting for them are in the tray instead.
    const ChatLogItem& item = chatLog.at(idx);
    return item.getContentType() == ChatLogItem::ContentType::message
           && item.getSender() == core.getSelfPublicKey()
           && item.getContentAsMessage().state == MessageState::pending;
}

size_t ChatWidget::applyEntryFlags()
{
    const size_t count = chatLineStorage->size();
    if (!chatLineStorage->hasIndexedMessage()) {
        return count;
    }

    std::map<const ChatLine*, ChatLogIdx> idxOf;
    for (auto i = chatLineStorage->firstIdx(); i <= chatLineStorage->lastIdx(); ++i) {
        if (chatLineStorage->contains(i)) {
            idxOf.insert({(*chatLineStorage)[i].get(), i});
        }
    }

    size_t firstMoved = count;
    const auto moved = [&firstMoved](size_t position) {
        firstMoved = std::min(firstMoved, position);
    };

    // The day mark folds away with the last entry of its day that the log shows.
    size_t dayPosition = count;
    bool dayHasEntries = false;
    const auto closeDay = [&]() {
        if (dayPosition < count && (*chatLineStorage)[dayPosition]->setCollapsed(!dayHasEntries)) {
            moved(dayPosition);
        }
    };

    bool hasPrevious = false;
    Air::RunKey previous;
    for (size_t position = 0; position < count; ++position) {
        ChatLine* line = (*chatLineStorage)[position].get();
        if (line->getLayout() == ChatLine::Layout::AirDay) {
            closeDay();
            dayPosition = position;
            dayHasEntries = false;
            hasPrevious = false;
            continue;
        }
        const auto found = idxOf.find(line);
        if (found == idxOf.end()) {
            continue;
        }

        const ChatLogIdx idx = found->second;
        const bool collapsed = isCollapsedAt(idx);
        if (line->setCollapsed(collapsed)) {
            moved(position);
        }
        if (collapsed) {
            continue;
        }
        dayHasEntries = true;

        const ChatLogItem& item = chatLog.at(idx);
        const bool isSelf = item.getSender() == core.getSelfPublicKey();
        const ChatLogItem::ContentType type = item.getContentType();
        const Air::RunKey key(isSelf ? QString() : item.getSender().toString(), item.getTimestamp(),
                              type == ChatLogItem::ContentType::message);
        if (type != ChatLogItem::ContentType::systemMessage) {
            const bool shown = !notesChat && Air::startsRun(key, hasPrevious ? &previous : nullptr);
            if (line->setAuthorShown(shown)) {
                moved(position);
            }
        }
        if (personChat && isSelf
            && (type == ChatLogItem::ContentType::message
                || type == ChatLogItem::ContentType::fileTransfer)) {
            static_cast<ChatMessage*>(line)->setStatus(statusOf(item));
        }
        previous = key;
        hasPrevious = true;
    }
    closeDay();
    return firstMoved;
}

void ChatWidget::refreshEntries()
{
    const size_t firstMoved = applyEntryFlags();
    if (firstMoved >= chatLineStorage->size()) {
        return;
    }
    const bool stickToBtm = stickToBottom();
    layout(static_cast<int>(firstMoved), static_cast<int>(chatLineStorage->size()), useableWidth());
    updateSceneRect();
    if (stickToBtm) {
        scrollToBottom();
    }
    checkVisibility();
    updateTypingNotification();
    updateMultiSelectionRect();
}

void ChatWidget::setPersonOnline(bool online)
{
    if (personOnline == online) {
        return;
    }
    personOnline = online;
    refreshEntries();
    refreshFileEntries();
}

void ChatWidget::setPersonName(const QString& name)
{
    if (personName == name) {
        return;
    }
    personName = name;
    refreshFileEntries();
}

namespace {
/** The file entry of a line, or null for a line of another kind. */
FileTransferWidget* fileEntryOf(const ChatLine& line)
{
    auto* const proxy = qobject_cast<ChatLineContentProxy*>(line.getContent(1));
    return proxy ? qobject_cast<FileTransferWidget*>(proxy->getWidget()) : nullptr;
}
} // namespace

void ChatWidget::refreshFileEntries()
{
    for (const ChatLine::Ptr& line : *chatLineStorage) {
        if (FileTransferWidget* const entry = fileEntryOf(*line)) {
            entry->setConversation(!personChat, personName, personOnline);
        }
    }
}

void ChatWidget::layoutResizedLines()
{
    size_t first = chatLineStorage->size();
    for (const std::weak_ptr<ChatLine>& weak : resizedLines) {
        const ChatLine::Ptr line = weak.lock();
        if (!line) {
            continue;
        }
        const auto found = chatLineStorage->find(line);
        if (found != chatLineStorage->end()) {
            first = std::min(first, static_cast<size_t>(found - chatLineStorage->begin()));
        }
    }
    resizedLines.clear();
    if (first >= chatLineStorage->size()) {
        return;
    }
    const bool stickToBtm = stickToBottom();
    layout(static_cast<int>(first), static_cast<int>(chatLineStorage->size()), useableWidth());
    updateSceneRect();
    if (stickToBtm) {
        scrollToBottom();
    }
    checkVisibility();
    updateTypingNotification();
    updateMultiSelectionRect();
}

void ChatWidget::renderFile(const ChatMessage::AirLook& look, ToxFile file, QDateTime timestamp,
                            const Air::EntryMarks& marks, ChatLine::Ptr& chatMessage)
{
    if (!chatMessage) {
        CoreFile* coreFile = core.getCoreFile();
        assert(coreFile);
        chatMessage = ChatMessage::createFileTransferMessage(look, *coreFile, file, timestamp,
                                                             marks, documentCache, smileyPack,
                                                             settings, style, messageBoxManager);
        FileTransferWidget* const entry = fileEntryOf(*chatMessage);
        assert(entry);
        entry->setConversation(!personChat, personName, personOnline);
        const std::weak_ptr<ChatLine> weak = chatMessage;
        connect(entry, &FileTransferWidget::heightChanged, this, [this, weak] {
            resizedLines.push_back(weak);
            resizedLater->start();
        });
    } else {
        FileTransferWidget* const entry = fileEntryOf(*chatMessage);
        assert(entry);
        entry->onFileTransferUpdate(file);
        auto* const line = static_cast<ChatMessage*>(chatMessage.get());
        line->setStatus(look.status);
        if (line->updateMarks(marks)) {
            resizedLines.push_back(chatMessage);
            resizedLater->start();
        }
    }
}

/**
 * @brief Determine if the name at the given idx needs to be hidden
 * @param idx ChatLogIdx of the message
 * @param prevIdxRendered Hint if the previous index is going to be rendered at
 * all. If the previous line is not rendered we always show the name
 * @return True if the name should be hidden, false otherwise
 */
bool ChatWidget::needsToHideName(ChatLogIdx idx, bool prevIdxRendered) const
{
    // If the previous message is not rendered we should show the name
    // regardless of other constraints

    if (!prevIdxRendered) {
        return false;
    }

    const auto& prevItem = chatLog.at(idx - 1);
    const auto& currentItem = chatLog.at(idx);

    // The run rule of the redesign: the same author on the same day, at most five minutes apart.
    const auto keyOf = [this](const ChatLogItem& item) -> Air::RunKey {
        const bool isSelf = item.getSender() == core.getSelfPublicKey();
        return Air::RunKey(isSelf ? QString() : item.getSender().toString(), item.getTimestamp(),
                           item.getContentType() == ChatLogItem::ContentType::message);
    };
    const Air::RunKey previous = keyOf(prevItem);
    return !Air::startsRun(keyOf(currentItem), &previous);
}

bool ChatWidget::shouldRenderMessage(ChatLogIdx idx) const
{
    return chatLineStorage->contains(idx) ||
        (
            chatLineStorage->contains(idx - 1) && idx + 1 == chatLog.getNextIdx()
        ) || chatLineStorage->empty();
}

void ChatWidget::disableSearchText()
{
    if (!chatLineStorage->contains(searchPos.logIdx)) {
        return;
    }

    auto line = (*chatLineStorage)[searchPos.logIdx];
    auto text = qobject_cast<Text*>(line->getContent(1));
    text->deselectText();
}

void ChatWidget::removeSearchPhrase()
{
    disableSearchText();
}

void ChatWidget::jumpToDate(QDate date) {
    auto idx = firstItemAfterDate(date, chatLog);
    jumpToIdx(idx);
}

void ChatWidget::jumpToIdx(ChatLogIdx idx)
{
    if (idx == chatLog.getNextIdx()) {
        idx = chatLog.getNextIdx() - 1;
    }

    if (chatLineStorage->contains(idx)) {
        scrollToLine((*chatLineStorage)[idx]);
        return;
    }

    // If the requested idx is not currently rendered we need to request a
    // render and jump to the requested line after the render completes
    renderCompletionFns.push_back([this, idx] {
        if (chatLineStorage->contains(idx)) {
            scrollToLine((*chatLineStorage)[idx]);
        }
    });

    // If the chatlog is empty it's likely the user has just cleared. In this
    // case it makes more sense to present the jump as if we're coming from the
    // bottom
    if (chatLineStorage->hasIndexedMessage() && idx > chatLineStorage->lastIdx()) {
        setRenderedWindowEnd(clampedAdd(idx, windowChunkSize, chatLog));
    }
    else {
        setRenderedWindowStart(clampedAdd(idx, -windowChunkSize, chatLog));
    }
}
