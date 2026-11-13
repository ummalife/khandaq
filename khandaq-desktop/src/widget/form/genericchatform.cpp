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

#include "genericchatform.h"

#include "src/chatlog/chatlinecontentproxy.h"
#include "src/chatlog/chatwidget.h"
#include "src/chatlog/content/filetransferwidget.h"
#include "src/chatlog/content/timestamp.h"
#include "src/core/core.h"
#include "src/friendlist.h"
#include "src/grouplist.h"
#include "src/model/friend.h"
#include "src/model/group.h"
#include "src/model/notes.h"
#include "src/persistence/settings.h"
#include "src/persistence/smileypack.h"
#include "src/widget/air/aircomposer.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/chatformheader.h"
#include "src/widget/contentdialog.h"
#include "src/widget/contentdialogmanager.h"
#include "src/widget/contentlayout.h"
#include "src/widget/emoticonswidget.h"
#include "src/widget/form/chatform.h"
#include "src/widget/form/loadhistorydialog.h"
#include "src/widget/maskablepixmapwidget.h"
#include "src/widget/searchform.h"
#include "src/widget/style.h"
#include "src/widget/tool/chatquotepreview.h"
#include "src/widget/tool/chattextedit.h"
#include "src/widget/tool/flyoutoverlaywidget.h"
#include "src/widget/translator.h"
#include "src/widget/widget.h"

#include <QClipboard>
#include <QFileDialog>
#include <QKeyEvent>
#include <QPointer>
#include <QRegularExpression>
#include <QStringBuilder>
#include <QtGlobal>

#ifdef SPELL_CHECKING
#include <KF5/SonnetUi/sonnet/spellcheckdecorator.h>
#endif

/**
 * @class GenericChatForm
 * @brief Parent class for all chatforms. It's provide the minimum required UI
 * elements and methods to work with chat messages.
 */

namespace {
const QSize FILE_FLYOUT_SIZE{24, 24};

QString formatQuoteForSend(const QString& quote)
{
    QString formatted = quote;
    formatted.insert(0, QStringLiteral("> "));
    formatted.replace(QRegExp(QStringLiteral("\r\n|[\r\n\u2028\u2029]")), QStringLiteral("\n> "));
    if (!formatted.endsWith(QLatin1Char('\n'))) {
        formatted.append(QLatin1Char('\n'));
    }
    return formatted;
}

/**
 * The field lies inside the composer's frame: no frame or fill of its own, 8 px above the text
 * and 4 px at its sides (kq-compose textarea; one pixel less below, so one line is as high as
 * "Send").
 */
const QString MESSAGE_EDIT_STYLE = QStringLiteral(
    "QTextEdit { background: transparent; border: none; padding: 8px 4px 7px 4px; }");

ChatWidget::Kind chatWidgetKind(const Chat* chat)
{
    if (qobject_cast<const Friend*>(chat)) {
        return ChatWidget::Kind::Person;
    }
    if (qobject_cast<const NotesChat*>(chat)) {
        return ChatWidget::Kind::Notes;
    }
    return ChatWidget::Kind::Group;
}
} // namespace

/**
 * @brief Searches for name (possibly alias) of someone with specified public key among all of your
 * friends or groups you are participated
 * @param pk Searched public key
 * @return Name or alias of someone with such public key, or public key string representation if no
 * one was found
 */
QString GenericChatForm::resolveToxPk(const ToxPk& pk)
{
    Friend* f = friendList.findFriend(pk);
    if (f) {
        return f->getDisplayedName();
    }

    for (Group* it : groupList.getAllGroups()) {
        QString res = it->resolveToxPk(pk);
        if (!res.isEmpty()) {
            return res;
        }
    }

    return pk.toString();
}

namespace
{
const QString STYLE_PATH = QStringLiteral("chatForm/buttons.css");
}

namespace
{

template <class T, class Fun>
QPushButton* createButton(const QString& name, T* self, Fun onClickSlot,
    Settings& settings, Style& style)
{
    QPushButton* btn = new QPushButton();
    // Fix for incorrect layouts on OS X as per
    // https://bugreports.qt-project.org/browse/QTBUG-14591
    btn->setAttribute(Qt::WA_LayoutUsesWidgetRect);
    btn->setObjectName(name);
    btn->setProperty("state", "green");
    btn->setStyleSheet(style.getStylesheet(STYLE_PATH, settings));
    QObject::connect(btn, &QPushButton::clicked, self, onClickSlot);
    return btn;
}

} // namespace

GenericChatForm::GenericChatForm(const Core& core_, const Chat* chat, IChatLog& chatLog_,
                                 IMessageDispatcher& messageDispatcher_, DocumentCache& documentCache,
                                 SmileyPack& smileyPack_, Settings& settings_, Style& style_,
                                 IMessageBoxManager& messageBoxManager, FriendList& friendList_,
                                 GroupList& groupList_, QWidget* parent_)
    : QWidget(parent_, Qt::Window)
    , core{core_}
    , audioInputFlag(false)
    , audioOutputFlag(false)
    , chatLog(chatLog_)
    , messageDispatcher(messageDispatcher_)
    , smileyPack{smileyPack_}
    , settings{settings_}
    , style{style_}
    , friendList{friendList_}
    , groupList{groupList_}
{
    curRow = 0;
    headWidget = new ChatFormHeader(settings, style);
    searchForm = new SearchForm(settings, style);
    dateInfo = new QLabel(this);
    chatWidget = new ChatWidget(chatLog_, core, documentCache, smileyPack, settings, style,
                                messageBoxManager, this, chatWidgetKind(chat));
    searchForm->hide();
    dateInfo->setAlignment(Qt::AlignHCenter);
    dateInfo->setVisible(false);

    // settings
    connect(&settings, &Settings::emojiFontPointSizeChanged, chatWidget, &ChatWidget::forceRelayout);
    connect(&settings, &Settings::chatMessageFontChanged, this, &GenericChatForm::onChatMessageFontChanged);

    quotePreview = new ChatQuotePreview(this);
    msgEdit = new ChatTextEdit();
#ifdef SPELL_CHECKING
    if (settings.getSpellCheckingEnabled()) {
        decorator = new Sonnet::SpellCheckDecorator(msgEdit);
    }
#endif

    sendButton = createButton("sendButton", this, &GenericChatForm::onSendTriggered, settings, style);
    emoteButton = createButton("emoteButton", this, &GenericChatForm::onEmoteButtonClicked, settings, style);

    fileButton = createButton("fileButton", this, &GenericChatForm::onAttachClicked, settings, style);
    screenshotButton = createButton("screenshotButton", this, &GenericChatForm::onScreenshotClicked, settings, style);

    // TODO: Make updateCallButtons (see ChatForm) abstract
    //       and call here to set tooltips.

    fileFlyout = new FlyoutOverlayWidget;
    QHBoxLayout* fileLayout = new QHBoxLayout(fileFlyout);
    fileLayout->addWidget(screenshotButton);
    fileLayout->setContentsMargins(0, 0, 0, 0);
    fileLayout->setSpacing(0);
    fileLayout->setMargin(0);

    msgEdit->setFrameStyle(QFrame::NoFrame);

    // The conversation stands on the surface of the column it is shown in.
    setAutoFillBackground(true);

    bodySplitter = new QSplitter(Qt::Vertical, this);
    QWidget* contentWidget = new QWidget(this);
    bodySplitter->addWidget(contentWidget);

    QVBoxLayout* mainLayout = new QVBoxLayout();
    mainLayout->addWidget(bodySplitter);
    mainLayout->setMargin(0);

    setLayout(mainLayout);

    // kq-composer: the paperclip, the field and "Send" in one frame. The emoji button is gone; the
    // old buttons stay hidden and do what the composer asks of them, so their connections hold.
    composer = new AirComposer(msgEdit, contentWidget);
    composer->addAbove(quotePreview);
    for (QPushButton* button : {emoteButton, fileButton, sendButton}) {
        button->setParent(contentWidget);
        button->hide();
    }
    connect(composer, &AirComposer::sendRequested, sendButton, &QPushButton::click);
    connect(composer, &AirComposer::attachRequested, this, [this](const QPoint& globalPos) {
        QMenu attachMenu(this);
        if (!attachLimit.isEmpty()) {
            attachMenu.addAction(attachLimit)->setEnabled(false);
            attachMenu.addSeparator();
        }
        //: The paper clip's menu: opens the system dialog, where one file or several are chosen
        QAction* const fileAction = attachMenu.addAction(tr("Send files"));
        QAction* const screenshotAction = attachMenu.addAction(tr("Send a screenshot"));
        fileAction->setEnabled(fileButton->isEnabled());
        screenshotAction->setEnabled(screenshotButton->isEnabled());
        QAction* const chosen = attachMenu.exec(globalPos);
        if (chosen == fileAction) {
            fileButton->click();
        } else if (chosen == screenshotAction) {
            screenshotButton->click();
        }
    });
    connect(headWidget, &ChatFormHeader::searchRequested, this, &GenericChatForm::searchFormShow);

    // The log, the tray and the composer keep their own margins; the column has none.
    contentLayout = new QVBoxLayout(contentWidget);
    contentLayout->setContentsMargins(0, 0, 0, 0);
    contentLayout->setSpacing(0);
    contentLayout->addWidget(searchForm);
    contentLayout->addWidget(dateInfo);
    contentLayout->addWidget(chatWidget);
    contentLayout->addWidget(composer);

    quoteAction = menu.addAction(QIcon(), QString(), this, SLOT(quoteSelectedText()),
                                 QKeySequence(Qt::ALT + Qt::Key_Q));
    addAction(quoteAction);
    menu.addSeparator();

    goToCurrentDateAction = menu.addAction(QIcon(), QString(), this, SLOT(goToCurrentDate()),
                                  QKeySequence(Qt::CTRL + Qt::Key_G));
    addAction(goToCurrentDateAction);

    menu.addSeparator();

    searchAction = menu.addAction(QIcon(), QString(), this, SLOT(searchFormShow()),
                                  QKeySequence(Qt::CTRL + Qt::Key_F));
    addAction(searchAction);

    menu.addSeparator();

    menu.addActions(chatWidget->actions());
    menu.addSeparator();

    clearAction = menu.addAction(QIcon::fromTheme("edit-clear"), QString(),
                                 this, SLOT(clearChatArea()),
                                 QKeySequence(Qt::CTRL + Qt::SHIFT + Qt::Key_L));
    addAction(clearAction);

    copyLinkAction = menu.addAction(QIcon(), QString(), this, SLOT(copyLink()));
    menu.addSeparator();

    loadHistoryAction = menu.addAction(QIcon(), QString(), this, SLOT(onLoadHistory()));
    exportChatAction =
        menu.addAction(QIcon::fromTheme("document-save"), QString(), this, SLOT(onExportChat()));

    connect(chatWidget, &ChatWidget::customContextMenuRequested, this,
            &GenericChatForm::onChatContextMenuRequested);
    connect(chatWidget, &ChatWidget::firstVisibleLineChanged, this, &GenericChatForm::updateShowDateInfo);

    connect(searchForm, &SearchForm::searchInBegin, chatWidget, &ChatWidget::startSearch);
    connect(searchForm, &SearchForm::searchUp, chatWidget, &ChatWidget::onSearchUp);
    connect(searchForm, &SearchForm::searchDown, chatWidget, &ChatWidget::onSearchDown);
    connect(searchForm, &SearchForm::visibleChanged, chatWidget, &ChatWidget::removeSearchPhrase);
    connect(chatWidget, &ChatWidget::messageNotFoundShow, searchForm, &SearchForm::showMessageNotFound);

    connect(msgEdit, &ChatTextEdit::enterPressed, this, &GenericChatForm::onSendTriggered);
    connect(msgEdit, &ChatTextEdit::pasteFiles, this, &GenericChatForm::onPasteFiles);
    connect(msgEdit, &ChatTextEdit::escapePressed, this, [this]() {
        if (quotePreview->hasQuote()) {
            quotePreview->clearQuote();
        }
    });

    connect(&style, &Style::themeReload, this, &GenericChatForm::reloadTheme);

    reloadTheme();

    fileFlyout->setFixedSize(FILE_FLYOUT_SIZE);
    fileFlyout->setParent(this);
    fileButton->installEventFilter(this);
    fileFlyout->installEventFilter(this);

    retranslateUi();
    Translator::registerHandler(std::bind(&GenericChatForm::retranslateUi, this), this);

    // update header on name/title change
    connect(chat, &Chat::displayedNameChanged, this, &GenericChatForm::setName);

}

GenericChatForm::~GenericChatForm()
{
    Translator::unregister(this);
    delete searchForm;
}

void GenericChatForm::adjustFileMenuPosition()
{
    QPoint pos = fileButton->mapTo(bodySplitter, QPoint());
    QSize size = fileFlyout->size();
    fileFlyout->move(pos.x() - size.width(), pos.y());
}

void GenericChatForm::showFileMenu()
{
    if (!fileFlyout->isShown() && !fileFlyout->isBeingShown()) {
        adjustFileMenuPosition();
    }

    fileFlyout->animateShow();
}

void GenericChatForm::hideFileMenu()
{
    if (fileFlyout->isShown() || fileFlyout->isBeingShown())
        fileFlyout->animateHide();
}

QDateTime GenericChatForm::getLatestTime() const
{
    if (chatLog.getFirstIdx() == chatLog.getNextIdx())
        return QDateTime();

    const auto shouldUseTimestamp = [this] (ChatLogIdx idx) {
        if (chatLog.at(idx).getContentType() != ChatLogItem::ContentType::systemMessage) {
            return true;
        }

        const auto& message = chatLog.at(idx).getContentAsSystemMessage();
        switch (message.messageType) {
            case SystemMessageType::incomingCall:
            case SystemMessageType::outgoingCall:
            case SystemMessageType::callEnd:
            case SystemMessageType::unexpectedCallEnd:
                return true;
            case SystemMessageType::cleared:
            case SystemMessageType::titleChanged:
            case SystemMessageType::peerStateChange:
            case SystemMessageType::peerNameChanged:
            case SystemMessageType::userLeftGroup:
            case SystemMessageType::userJoinedGroup:
            case SystemMessageType::fileSendFailed:
            case SystemMessageType::messageSendFailed:
            case SystemMessageType::selfJoinedGroup:
            case SystemMessageType::selfLeftGroup:
                return false;
        }

        qWarning("Unexpected system message type %d", static_cast<int>(message.messageType));
        return false;
    };

    ChatLogIdx idx = chatLog.getNextIdx();
    while (idx > chatLog.getFirstIdx()) {
        idx = idx - 1;
        if (shouldUseTimestamp(idx)) {
            return chatLog.at(idx).getTimestamp();
        }
    }

    return QDateTime();
}

void GenericChatForm::reloadTheme()
{
    // No sheet for the whole form: it painted every child, the tray and the composer included,
    // with the old theme's ground. The form fills itself with the column's surface instead, and
    // the quote above the composer paints itself from the tokens.
    msgEdit->setStyleSheet(MESSAGE_EDIT_STYLE);
    msgEdit->setFont(ChatMessage::bodyFont(settings));

    emoteButton->setStyleSheet(style.getStylesheet(STYLE_PATH, settings));
    fileButton->setStyleSheet(style.getStylesheet(STYLE_PATH, settings));
    screenshotButton->setStyleSheet(style.getStylesheet(STYLE_PATH, settings));
    sendButton->setStyleSheet(style.getStylesheet(STYLE_PATH, settings));
}

void GenericChatForm::setName(const QString& newName)
{
    headWidget->setName(newName);
}

void GenericChatForm::show(ContentLayout* contentLayout_)
{
    contentLayout_->mainHead->layout()->addWidget(headWidget);
    headWidget->show();
    contentLayout_->setFlush(true);

#if QT_VERSION < QT_VERSION_CHECK(5, 12, 4) && QT_VERSION > QT_VERSION_CHECK(5, 11, 0)
    // HACK: switching order happens to avoid a Qt bug causing segfault, present between these versions.
    // this could cause flickering if our form is shown before added to the layout
    // https://github.com/qTox/qTox/issues/5570
    QWidget::show();
    contentLayout->mainContent->layout()->addWidget(this);
#else
    contentLayout_->mainContent->layout()->addWidget(this);
    QWidget::show();
#endif
}

void GenericChatForm::setAttachLimit(const QString& words)
{
    attachLimit = words;
    composer->setAttachNote(words);
}

void GenericChatForm::showEvent(QShowEvent* event)
{
    std::ignore = event;
    msgEdit->setFocus();
}

bool GenericChatForm::event(QEvent* e)
{
    // If the user accidentally starts typing outside of the msgEdit, focus it automatically
    if (e->type() == QEvent::KeyPress) {
        QKeyEvent* ke = static_cast<QKeyEvent*>(e);
        if ((ke->modifiers() == Qt::NoModifier || ke->modifiers() == Qt::ShiftModifier)
                && !ke->text().isEmpty()) {
            if (searchForm->isHidden()) {
                msgEdit->sendKeyEvent(ke);
                msgEdit->setFocus();
            } else {
                searchForm->insertEditor(ke->text());
                searchForm->setFocusEditor();
            }
        }
    }
    return QWidget::event(e);
}

void GenericChatForm::onChatContextMenuRequested(QPoint pos)
{
    QWidget* sender = static_cast<QWidget*>(QObject::sender());
    pos = sender->mapToGlobal(pos);

    // If we right-clicked on a link, give the option to copy it
    bool clickedOnLink = false;
    Text* clickedText = qobject_cast<Text*>(chatWidget->getContentFromGlobalPos(pos));
    if (clickedText) {
        QPointF scenePos = chatWidget->mapToScene(chatWidget->mapFromGlobal(pos));
        QString linkTarget = clickedText->getLinkAt(scenePos);
        if (!linkTarget.isEmpty()) {
            clickedOnLink = true;
            copyLinkAction->setData(linkTarget);
        }
    }
    copyLinkAction->setVisible(clickedOnLink);
    prepareChatMenu(pos);

    menu.exec(pos);
}

void GenericChatForm::prepareChatMenu(QPoint globalPos)
{
    std::ignore = globalPos;
}

void GenericChatForm::onPasteFiles(const QStringList& paths)
{
    std::ignore = paths;
}

QString GenericChatForm::outgoingText() const
{
    QString msg = msgEdit->toPlainText();
    if (msg.startsWith(ChatForm::ACTION_PREFIX, Qt::CaseInsensitive)) {
        msg.remove(0, ChatForm::ACTION_PREFIX.length());
    }
    if (quotePreview->hasQuote()) {
        msg = formatQuoteForSend(quotePreview->quoteText()) + msg;
    }
    return msg;
}

bool GenericChatForm::canSendNow() const
{
    return true;
}

void GenericChatForm::onSendTriggered()
{
    if (!canSendNow()) {
        return;
    }

    auto msg = msgEdit->toPlainText();

    bool isAction = msg.startsWith(ChatForm::ACTION_PREFIX, Qt::CaseInsensitive);
    if (isAction) {
        msg.remove(0, ChatForm::ACTION_PREFIX.length());
    }

    if (quotePreview->hasQuote()) {
        msg = formatQuoteForSend(quotePreview->quoteText()) + msg;
    }

    if (msg.trimmed().isEmpty()) {
        return;
    }

    msgEdit->setLastMessage(msgEdit->toPlainText());
    msgEdit->clear();
    quotePreview->clearQuote();

    messageDispatcher.sendMessage(isAction, msg);
}


void GenericChatForm::onEmoteButtonClicked()
{
    // don't show the smiley selection widget if there are no smileys available
    if (smileyPack.getEmoticons().empty())
        return;

    EmoticonsWidget widget(smileyPack, settings, style);
    connect(&widget, SIGNAL(insertEmoticon(QString)), this, SLOT(onEmoteInsertRequested(QString)));
    widget.installEventFilter(this);

    QWidget* sender = qobject_cast<QWidget*>(QObject::sender());
    if (sender) {
        QPoint pos =
            -QPoint(widget.sizeHint().width() / 2, widget.sizeHint().height()) - QPoint(0, 10);
        widget.exec(sender->mapToGlobal(pos));
    }
}

void GenericChatForm::onEmoteInsertRequested(QString str)
{
    // insert the emoticon
    QWidget* sender = qobject_cast<QWidget*>(QObject::sender());
    if (sender)
        msgEdit->insertPlainText(str);

    msgEdit->setFocus(); // refocus so that we can continue typing
}

void GenericChatForm::onCopyLogClicked()
{
    chatWidget->copySelectedText();
}

void GenericChatForm::focusInput()
{
    msgEdit->setFocus();
}

void GenericChatForm::onChatMessageFontChanged(const QFont& font)
{
    // chat log
    chatWidget->fontChanged(font);
    chatWidget->forceRelayout();
    // message editor: the same type as the log
    std::ignore = font;
    msgEdit->setFont(ChatMessage::bodyFont(settings));
}

void GenericChatForm::setColorizedNames(bool enable)
{
    chatWidget->setColorizedNames(enable);
}

void GenericChatForm::addSystemInfoMessage(const QDateTime& datetime, SystemMessageType messageType,
                                           SystemMessage::Args messageArgs)
{
    SystemMessage systemMessage;
    systemMessage.messageType = messageType;
    systemMessage.timestamp = datetime;
    systemMessage.args = std::move(messageArgs);
    chatLog.addSystemMessage(systemMessage);
}

QDateTime GenericChatForm::getTime(const ChatLine::Ptr &chatLine) const
{
    if (chatLine) {
        Timestamp* const timestamp = qobject_cast<Timestamp*>(chatLine->getContent(2));

        if (timestamp) {
            return timestamp->getTime();
        } else {
            return QDateTime();
        }
    }

    return QDateTime();
}


void GenericChatForm::clearChatArea()
{
    clearChatArea(/* confirm = */ true, /* inform = */ true);
}

void GenericChatForm::clearChatArea(bool confirm, bool inform)
{
    if (confirm) {
        // Asked over the window, the consequence in one sentence and the verb on the button (E21).
        using AirStrings::Text;
        QPointer<GenericChatForm> form(this);
        AirDialog::confirm(this, AirStrings::text(Text::ClearScreenTitle),
                           AirStrings::text(Text::ClearScreenMessage),
                           AirStrings::text(Text::ClearScreenAction), AirDialog::Verb::Ink,
                           [form, inform] {
                               if (form) {
                                   form->clearChatArea(false, inform);
                               }
                           });
        return;
    }

    chatWidget->clear();

    if (inform)
        addSystemInfoMessage(QDateTime::currentDateTime(), SystemMessageType::cleared, {});
}

void GenericChatForm::onSelectAllClicked()
{
    chatWidget->selectAll();
}

void GenericChatForm::hideEvent(QHideEvent* event)
{
    hideFileMenu();
    QWidget::hideEvent(event);
}

void GenericChatForm::resizeEvent(QResizeEvent* event)
{
    adjustFileMenuPosition();
    QWidget::resizeEvent(event);
}

bool GenericChatForm::eventFilter(QObject* object, QEvent* event)
{
    EmoticonsWidget* ev = qobject_cast<EmoticonsWidget*>(object);
    if (ev && event->type() == QEvent::KeyPress) {
        QKeyEvent* key = static_cast<QKeyEvent*>(event);
        msgEdit->sendKeyEvent(key);
        msgEdit->setFocus();
        return false;
    }

    if (object != fileButton && object != fileFlyout)
        return false;

    auto wObject = qobject_cast<QWidget*>(object);
    if (!wObject || !wObject->isEnabled())
        return false;

    switch (event->type()) {
    case QEvent::Enter:
        showFileMenu();
        break;

    case QEvent::Leave: {
        QPoint flyPos = fileFlyout->mapToGlobal(QPoint());
        QSize flySize = fileFlyout->size();

        QPoint filePos = fileButton->mapToGlobal(QPoint());
        QSize fileSize = fileButton->size();

        QRect region = QRect(flyPos, flySize).united(QRect(filePos, fileSize));

        if (!region.contains(QCursor::pos()))
            hideFileMenu();

        break;
    }

    case QEvent::MouseButtonPress:
        hideFileMenu();
        break;

    default:
        break;
    }

    return false;
}

void GenericChatForm::quoteSelectedText()
{
    const QString selectedText = chatWidget->getSelectedText();

    if (selectedText.isEmpty()) {
        return;
    }

    quotePreview->setQuoteText(selectedText);
    msgEdit->setFocus();
}

/**
 * @brief Callback of GenericChatForm::copyLinkAction
 */
void GenericChatForm::copyLink()
{
    QString linkText = copyLinkAction->data().toString();
    QApplication::clipboard()->setText(linkText);
}

void GenericChatForm::searchFormShow()
{
    if (searchForm->isHidden()) {
        searchForm->show();
        searchForm->setFocusEditor();
    }
}

void GenericChatForm::onLoadHistory()
{
    LoadHistoryDialog dlg(&chatLog);
    if (dlg.exec()) {
        chatWidget->jumpToDate(dlg.getFromDate().date());
    }
}

void GenericChatForm::onExportChat()
{
    QString path = QFileDialog::getSaveFileName(Q_NULLPTR, tr("Save chat log"));
    if (path.isEmpty()) {
        return;
    }

    QFile file(path);
    if (!file.open(QIODevice::WriteOnly | QIODevice::Text)) {
        return;
    }

    QString buffer;
    for (auto i = chatLog.getFirstIdx(); i < chatLog.getNextIdx(); ++i) {
        // A message cancelled here or deleted by its author is gone for the saved log too.
        if (chatLog.isCancelled(i)) {
            continue;
        }
        const auto& item = chatLog.at(i);
        if (item.getContentType() != ChatLogItem::ContentType::message) {
            continue;
        }

        QString timestamp = item.getTimestamp().time().toString("hh:mm:ss");
        QString datestamp = item.getTimestamp().date().toString("yyyy-MM-dd");
        QString author = item.getDisplayName();

        // The words the transcript shows, without the markup of a reply.
        buffer =
            buffer
            % QString{datestamp % '\t' % timestamp % '\t' % author % '\t'
                      % AirStrings::messageWords(item.getContentAsMessage().message.content) % '\n'};
    }
    file.write(buffer.toUtf8());
    file.close();
}

void GenericChatForm::goToCurrentDate()
{
    chatWidget->jumpToIdx(chatLog.getNextIdx());
}

void GenericChatForm::updateShowDateInfo(const ChatLine::Ptr& prevLine, const ChatLine::Ptr& topLine)
{
    // If the dateInfo is visible we need to pretend the top line is the one
    // covered by the date to prevent oscillations
    const auto effectiveTopLine = (dateInfo->isVisible() && prevLine)
        ? prevLine : topLine;

    const auto date = getTime(effectiveTopLine);

    if (date.isValid() && date.date() != QDate::currentDate()) {
        const auto dateText = QStringLiteral("<b>%1<\b>").arg(date.toString(settings.getDateFormat()));
        dateInfo->setText(dateText);
        dateInfo->setVisible(true);
    } else {
        dateInfo->setVisible(false);
    }
}

void GenericChatForm::retranslateUi()
{
    msgEdit->setPlaceholderText(AirStrings::text(AirStrings::Text::ComposePlaceholder));
    // A screen reader names the field as the placeholder does, which goes once text is typed.
    msgEdit->setAccessibleName(AirStrings::text(AirStrings::Text::ComposePlaceholder));
    sendButton->setToolTip(tr("Send message"));
    emoteButton->setToolTip(tr("Smileys"));
    //: The paper clip's menu: opens the system dialog, where one file or several are chosen
    fileButton->setToolTip(tr("Send files"));
    screenshotButton->setToolTip(tr("Send a screenshot"));
    clearAction->setText(tr("Clear displayed messages"));
    quoteAction->setText(tr("Quote selected text"));
    copyLinkAction->setText(tr("Copy link address"));
    searchAction->setText(tr("Search in text"));
    goToCurrentDateAction->setText(tr("Go to current date"));
    loadHistoryAction->setText(tr("Load chat history..."));
    exportChatAction->setText(tr("Export to file"));
}
