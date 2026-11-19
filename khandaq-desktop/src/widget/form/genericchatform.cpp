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
#include "src/model/air/airmedia.h"
#include "src/model/air/airmodel.h"
#include "src/model/air/airvoiceencoder.h"
#include "src/model/friend.h"
#include "src/model/group.h"
#include "src/model/notes.h"
#include "src/persistence/paths.h"
#include "src/persistence/settings.h"
#include "src/persistence/smileypack.h"
#include "src/platform/mediaaccess.h"
#include "src/widget/air/aircomposer.h"
#include "src/widget/air/airmediaparts.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airvoicerecorder.h"
#include "src/widget/chatformheader.h"
#include "src/widget/contentdialog.h"
#include "src/widget/contentdialogmanager.h"
#include "src/widget/contentlayout.h"
#include "src/widget/emoticonswidget.h"
#include "src/widget/form/chatform.h"
#include "src/widget/form/loadhistorydialog.h"
#include "src/widget/imagepreviewwidget.h"
#include "src/widget/maskablepixmapwidget.h"
#include "src/widget/searchform.h"
#include "src/widget/style.h"
#include "src/widget/tool/chatquotepreview.h"
#include "src/widget/tool/chattextedit.h"
#include "src/widget/tool/flyoutoverlaywidget.h"
#include "src/widget/translator.h"
#include "src/widget/widget.h"

#include <QClipboard>
#include <QDateTime>
#include <QDir>
#include <QFile>
#include <QFileDialog>
#include <QFileInfo>
#include <QFutureWatcher>
#include <QHBoxLayout>
#include <QKeyEvent>
#include <QPointer>
#include <QRegularExpression>
#include <QStringBuilder>
#include <QtConcurrent/QtConcurrentRun>
#include <QtGlobal>

#include <memory>

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
 * The words of the field as Send hands them over: after the lines of a quote, or after the header
 * of a reply (KHANDAQ 0.2.58). A /me action takes no reply: the header would stand in the line of
 * the action on every reader, so the reply waits for the next message.
 */
QString withQuote(const ChatQuotePreview& preview, const QString& words, bool isAction)
{
    if (!preview.hasQuote()) {
        return words;
    }
    if (preview.isReply()) {
        return isAction ? words : Air::replyHead(preview.reply()) + words;
    }
    return formatQuoteForSend(preview.quoteText()) + words;
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

/** KHANDAQ (0.2.63): the emoji we reacted with to a message or a file; empty for none. */
QString ownReactionOn(const ChatLogItem& item)
{
    switch (item.getContentType()) {
    case ChatLogItem::ContentType::message:
        return item.getContentAsMessage().marks.emojiOf(Air::ownReactionActor());
    case ChatLogItem::ContentType::fileTransfer:
        return item.getContentAsFile().marks.emojiOf(Air::ownReactionActor());
    case ChatLogItem::ContentType::systemMessage:
        break;
    }
    return QString();
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

    takesReplies = chatWidgetKind(chat) != ChatWidget::Kind::Notes;
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

    // kq-composer: the paperclip, the field, the emoji and "Send" in one frame. The old buttons stay
    // hidden and do what the composer asks of them, so their connections hold.
    composer = new AirComposer(msgEdit, contentWidget);
    // KHANDAQ (0.2.60): the emoji came back (users could not send any from the computer); a build
    // without a set has nothing to offer.
    composer->setEmojiVisible(!smileyPack.getEmoticons().empty());
    connect(composer, &AirComposer::emojiRequested, this, &GenericChatForm::showEmoticons);
    // KHANDAQ (0.2.60): voice notes from the computer too; a build whose FFmpeg cannot write them
    // offers no microphone.
    composer->setVoiceVisible(AirVoiceEncoder::canEncode());
    connect(composer, &AirComposer::voiceRequested, this, &GenericChatForm::startVoiceNote);
    connect(composer, &AirComposer::voiceSendRequested, this, [this] { sendVoiceNote(false); });
    connect(composer, &AirComposer::voiceCancelRequested, this, &GenericChatForm::cancelVoiceNote);
    {
        // One recorder for the app: every form listens and takes what is about its own note.
        AirVoiceRecorder* const recorder = &AirVoiceRecorder::instance();
        connect(recorder, &AirVoiceRecorder::elapsedChanged, this, [this, recorder](qint64 ms) {
            if (recorder->isRecordingFor(this)) {
                composer->setRecordingTime(ms, static_cast<double>(ms)
                                                   / AirVoiceRecorder::longestMs);
            }
        });
        connect(recorder, &AirVoiceRecorder::limitReached, this, [this, recorder] {
            if (recorder->isRecordingFor(this)) {
                composer->setRecordingTime(AirVoiceRecorder::longestMs, 1.0);
                sendVoiceNote(true);
            }
        });
        connect(recorder, &AirVoiceRecorder::lost, this, [this, recorder] {
            if (recorder->isRecordingFor(this)) {
                AirToast::post(this, AirStrings::text(AirStrings::Text::VoiceNoMicrophone));
            }
        });
        connect(recorder, &AirVoiceRecorder::ended, this, [this, recorder] {
            if (!recorder->isRecordingFor(this)) {
                composer->setRecording(false);
            }
        });
    }
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

    // A pasted picture or a screenshot waits here, right over the field (takePastedImages).
    // KHANDAQ (0.2.60): in line with the field, as far from the edge as the composer keeps it; it
    // stood against the edge of the column. Hidden, its row takes no room.
    imagePreview = new ImagePreviewButton(this);
    imagePreview->setFixedSize(100, 100);
    imagePreview->setFlat(true);
    imagePreview->setStyleSheet("QPushButton { border: 0px }");
    imagePreview->hide();
    auto* const cancelPreview = new QPushButton(imagePreview);
    cancelPreview->setFixedSize(20, 20);
    cancelPreview->move(QPoint(80, 0));
    cancelPreview->setIcon(QIcon(style.getImagePath("rejectCall/rejectCall.svg", settings)));
    cancelPreview->setFlat(true);
    connect(cancelPreview, &QPushButton::pressed, this, &GenericChatForm::cancelImagePreview);
    auto* const previewRow = new QHBoxLayout();
    previewRow->setContentsMargins(AirTokens::Space::s16, AirTokens::Space::s8,
                                   AirTokens::Space::s16, 0);
    previewRow->addWidget(imagePreview);
    previewRow->addStretch(1);
    contentLayout->insertLayout(contentLayout->indexOf(composer), previewRow);

    // KHANDAQ (0.2.58): Reply first, on the message or the file the menu is opened on.
    replyAction = menu.addAction(QString());
    connect(replyAction, &QAction::triggered, this, &GenericChatForm::replyToMenuEntry);
    // KHANDAQ (0.2.63): then React, with the emoji the phones offer, on that entry too.
    reactMenu = menu.addMenu(QString());
    for (const QString& emoji : Air::quickReactions()) {
        QAction* const reaction = reactMenu->addAction(emoji);
        reaction->setCheckable(true);
        reaction->setData(emoji);
        connect(reaction, &QAction::triggered, this, [this, emoji] { reactTo(menuEntry, emoji); });
    }
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
    ChatLogIdx entry{0};
    const bool onEntry = chatWidget->entryAtGlobalPos(pos, entry);
    menuEntry = entry;
    replyAction->setVisible(onEntry && canReplyTo(entry));
    const bool reacts = onEntry && canReactTo(entry);
    reactMenu->menuAction()->setVisible(reacts);
    if (reacts) {
        // Ours is checked: choosing it again takes it back.
        const QString own = ownReactionOn(chatLog.at(entry));
        for (QAction* const reaction : reactMenu->actions()) {
            reaction->setChecked(reaction->data().toString() == own);
        }
    }
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

void GenericChatForm::sendPreparedFiles(const QStringList& paths)
{
    std::ignore = paths;
}

QString GenericChatForm::voiceRefusal() const
{
    return QString();
}

void GenericChatForm::startVoiceNote()
{
    AirVoiceRecorder& recorder = AirVoiceRecorder::instance();
    if (recorder.isRecordingFor(this)) {
        return;
    }
    const QString refusal = voiceRefusal();
    if (!refusal.isEmpty()) {
        AirToast::post(this, refusal);
        return;
    }
    const QPointer<GenericChatForm> self(this);
    MediaAccess::ask(MediaAccess::Device::Microphone, this, [self](bool granted) {
        GenericChatForm* const form = self.data();
        if (!form) {
            return;
        }
        if (!granted) {
            form->explainVoiceMicrophone();
            return;
        }
        AirVoiceRecorder& voice = AirVoiceRecorder::instance();
        // A note of another window (a chat of its own) gives way to this one, and a note that plays
        // stops: the microphone would hear it, as in a call.
        voice.cancel();
        AirVoicePlayer::instance().stop();
        if (!voice.start(form)) {
            AirToast::post(form, AirStrings::text(AirStrings::Text::VoiceNoMicrophone));
            return;
        }
        AirToast::dismiss(form);
        form->composer->setRecording(true);
    });
}

void GenericChatForm::sendVoiceNote(bool atLimit)
{
    AirVoiceRecorder& recorder = AirVoiceRecorder::instance();
    if (!recorder.isRecordingFor(this) || !canSendNow()) {
        // A group where nobody would get it: the note waits with Send, as a message does.
        return;
    }
    const QString refusal = voiceRefusal();
    if (!refusal.isEmpty()) {
        // The person went offline while the note was recorded: it waits for them or the cross.
        AirToast::post(this, refusal);
        return;
    }
    if (recorder.elapsedMs() < AirVoiceRecorder::shortestMs) {
        recorder.cancel();
        AirToast::post(this, AirStrings::text(AirStrings::Text::VoiceTooShort));
        return;
    }
    const QString folder = Air::voiceNoteFolder(settings.getPaths().getAppDataDirPath());
    if (!QDir().mkpath(folder)) {
        recorder.cancel();
        AirDialog::notice(this, AirStrings::text(AirStrings::Text::FileNotSentTitle),
                          AirStrings::text(AirStrings::Text::VoiceNotSaved));
        return;
    }
    const QString path = QDir(folder).filePath(AirVoiceEncoder::newNoteName());
    AirVoiceDecoder::Pcm pcm = recorder.finish();
    // Minutes of sound take a moment to write: off the main thread, the field free meanwhile.
    const auto samples = std::make_shared<std::vector<int16_t>>();
    samples->swap(pcm.samples);
    const int sampleRate = pcm.sampleRate;
    auto* const writing = new QFutureWatcher<bool>(this);
    connect(writing, &QFutureWatcher<bool>::finished, this, [this, writing, path, atLimit] {
        const bool written = writing->result();
        writing->deleteLater();
        if (!written) {
            AirDialog::notice(this, AirStrings::text(AirStrings::Text::FileNotSentTitle),
                              AirStrings::text(AirStrings::Text::VoiceNotSaved));
            return;
        }
        sendPreparedFiles(QStringList{path});
        if (atLimit) {
            AirToast::post(this, AirStrings::text(AirStrings::Text::VoiceLimitSent));
        }
    });
    writing->setFuture(QtConcurrent::run([samples, sampleRate, path]() {
        return AirVoiceEncoder::encode(*samples, sampleRate, path);
    }));
}

void GenericChatForm::cancelVoiceNote()
{
    AirVoiceRecorder& recorder = AirVoiceRecorder::instance();
    if (recorder.isRecordingFor(this)) {
        recorder.cancel();
    } else {
        composer->setRecording(false);
    }
}

void GenericChatForm::explainVoiceMicrophone()
{
    AirDialog::confirm(this, AirStrings::text(AirStrings::Text::CallMicTitle),
                       AirStrings::text(AirStrings::Text::VoiceMicMessage),
                       AirStrings::text(AirStrings::Text::CallOpenSettings), AirDialog::Verb::Ink,
                       [] { MediaAccess::openSettings(MediaAccess::Device::Microphone); });
}

void GenericChatForm::takePastedImages()
{
    connect(msgEdit, &ChatTextEdit::pasteImage, this, &GenericChatForm::previewImage);
    connect(sendButton, &QPushButton::pressed, this, &GenericChatForm::sendImageFromPreview);
    connect(msgEdit, &ChatTextEdit::enterPressed, this, &GenericChatForm::sendImageFromPreview);
    connect(msgEdit, &ChatTextEdit::escapePressed, this, &GenericChatForm::cancelImagePreview);
}

void GenericChatForm::previewImage(const QPixmap& pixmap)
{
    imagePreviewSource = pixmap;
    imagePreview->setIconFromPixmap(pixmap);
    imagePreview->show();
}

void GenericChatForm::cancelImagePreview()
{
    imagePreviewSource = QPixmap();
    imagePreview->hide();
}

void GenericChatForm::sendImageFromPreview()
{
    if (!imagePreview->isVisible()) {
        return;
    }

    QDir(settings.getPaths().getAppDataDirPath()).mkpath("images");

    // ~ISO 8601 for the name, without the colons Windows does not take in a file name.
    const QString filepath =
        QString("%1images%2Khandaq_Image_%3.png")
            .arg(settings.getPaths().getAppDataDirPath())
            .arg(QDir::separator())
            .arg(QDateTime::currentDateTime().toString("yyyy-MM-dd HH-mm-ss.zzz"));
    QFile file(filepath);
    if (!file.open(QFile::WriteOnly) || !imagePreviewSource.save(&file, "PNG")) {
        // The operation as the title, over the window (E21).
        AirDialog::notice(this, AirStrings::text(AirStrings::Text::FileNotSentTitle),
                          AirStrings::text(AirStrings::Text::ScreenshotNotSaved));
        return;
    }
    file.close();
    cancelImagePreview();
    // KHANDAQ (0.2.60): the same way as a chosen file, with its checks and their words (the picture
    // went past them before, straight to the core).
    sendPreparedFiles({QFileInfo(file).filePath()});
}

QString GenericChatForm::outgoingText() const
{
    QString msg = msgEdit->toPlainText();
    const bool isAction = msg.startsWith(ChatForm::ACTION_PREFIX, Qt::CaseInsensitive);
    if (isAction) {
        msg.remove(0, ChatForm::ACTION_PREFIX.length());
    }
    return withQuote(*quotePreview, msg, isAction);
}

GenericChatForm::ReplyAuthor GenericChatForm::replyAuthorOf(const ChatLogItem& item) const
{
    ReplyAuthor author;
    author.key = item.getSender();
    author.self = item.getSender() == core.getSelfPublicKey();
    author.name = author.self ? core.getUsername() : item.getDisplayName();
    return author;
}

bool GenericChatForm::canReplyTo(ChatLogIdx idx) const
{
    if (!takesReplies || composer->isReadOnly() || idx < chatLog.getFirstIdx()
        || idx >= chatLog.getNextIdx()) {
        return false;
    }
    const ChatLogItem& item = chatLog.at(idx);
    switch (item.getContentType()) {
    case ChatLogItem::ContentType::message:
        return !Air::replyQuote(AirStrings::messageWords(item.getContentAsMessage().message.content))
                    .isEmpty();
    case ChatLogItem::ContentType::fileTransfer:
        return !Air::replyQuote(item.getContentAsFile().file.fileName).isEmpty();
    case ChatLogItem::ContentType::systemMessage:
        // A line nobody wrote, a call among them, has nothing to answer.
        return false;
    }
    return false;
}

bool GenericChatForm::startReply(ChatLogIdx idx, const QString& words)
{
    if (!canReplyTo(idx)) {
        return false;
    }
    const ChatLogItem& item = chatLog.at(idx);
    QString shown;
    if (item.getContentType() == ChatLogItem::ContentType::fileTransfer) {
        // A file is quoted by its name, as on the phones.
        shown = item.getContentAsFile().file.fileName;
    } else {
        shown = AirStrings::messageWords(item.getContentAsMessage().message.content);
    }

    const ReplyAuthor author = replyAuthorOf(item);
    Air::ReplyMeta meta;
    meta.authorKey = author.key.isEmpty() ? QString() : author.key.toString();
    meta.timeMs = item.getTimestamp().toMSecsSinceEpoch();
    meta.authorName = author.name;
    meta.words = words.trimmed().isEmpty() ? shown : words;
    // The same entry gives the same id every time; the phones compare it with ids of their own only.
    meta.localId = Air::stableLocalId(item.getSender().toString() + QLatin1Char('|')
                                      + QString::number(meta.timeMs) + QLatin1Char('|') + shown);
    if (Air::replyQuote(meta.words).isEmpty()) {
        return false;
    }
    const QString name =
        item.getDisplayName().trimmed().isEmpty() ? author.name : item.getDisplayName();
    quotePreview->setReply(meta, name, author.self);
    return true;
}

void GenericChatForm::replyToMenuEntry()
{
    if (startReply(menuEntry)) {
        msgEdit->setFocus();
    }
}

bool GenericChatForm::canReactTo(ChatLogIdx idx) const
{
    std::ignore = idx;
    return false;
}

void GenericChatForm::reactTo(ChatLogIdx idx, const QString& emoji)
{
    std::ignore = idx;
    std::ignore = emoji;
}

bool GenericChatForm::reactionAnchorOf(ChatLogIdx idx, Air::MessageEffect::Anchor* anchor,
                                       QByteArray* id) const
{
    if (idx < chatLog.getFirstIdx() || idx >= chatLog.getNextIdx() || !chatLog.isShown(idx)) {
        return false;
    }
    const ChatLogItem& item = chatLog.at(idx);
    Air::MessageEffect::Anchor found = Air::MessageEffect::Anchor::Text;
    QByteArray foundId;
    if (item.getContentType() == ChatLogItem::ContentType::message) {
        foundId = item.getContentAsMessage().message.wireId;
    } else if (item.getContentType() == ChatLogItem::ContentType::fileTransfer) {
        found = Air::MessageEffect::Anchor::File;
        foundId = item.getContentAsFile().file.resumeFileId;
    } else {
        return false;
    }
    // A group's text still on its way has the id 0, and the receivers drop a reaction to it.
    if (foundId.isEmpty() || foundId.count('\0') == foundId.size()) {
        return false;
    }
    *anchor = found;
    *id = foundId;
    return true;
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

    // KHANDAQ (0.2.58): a reply needs words of its own; its header alone sends nothing. An action
    // goes without it and leaves the reply waiting (withQuote).
    const bool replying = quotePreview->hasQuote() && quotePreview->isReply();
    if (replying && msg.trimmed().isEmpty()) {
        return;
    }
    const bool keepsQuote = replying && isAction;
    msg = withQuote(*quotePreview, msg, isAction);

    if (msg.trimmed().isEmpty()) {
        return;
    }

    msgEdit->setLastMessage(msgEdit->toPlainText());
    msgEdit->clear();
    if (!keepsQuote) {
        quotePreview->clearQuote();
    }

    messageDispatcher.sendMessage(isAction, msg);
}


void GenericChatForm::onEmoteButtonClicked()
{
    QWidget* sender = qobject_cast<QWidget*>(QObject::sender());
    if (sender) {
        showEmoticons(sender->mapToGlobal(QPoint()));
    }
}

void GenericChatForm::showEmoticons(const QPoint& anchor)
{
    // don't show the smiley selection widget if there are no smileys available
    if (smileyPack.getEmoticons().empty())
        return;

    EmoticonsWidget widget(smileyPack, settings, style);
    connect(&widget, SIGNAL(insertEmoticon(QString)), this, SLOT(onEmoteInsertRequested(QString)));
    widget.installEventFilter(this);

    // Centred over the anchor, 10 px above it; QMenu keeps it on the screen.
    const QPoint pos =
        -QPoint(widget.sizeHint().width() / 2, widget.sizeHint().height()) - QPoint(0, 10);
    widget.exec(anchor + pos);
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
    // A note is recorded for the chat on the screen: another chat opened cancels it. A window
    // minimized (a hide of the system) does not.
    if (!event->spontaneous() && AirVoiceRecorder::instance().isRecordingFor(this)) {
        AirVoiceRecorder::instance().cancel();
    }
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

    // KHANDAQ (0.2.58): words chosen in one message answer it, with those words as its quote; a
    // choice across several entries, or where a reply cannot go, stays a quote of the old kind.
    ChatLogIdx idx{0};
    if (!chatWidget->selectedEntry(idx) || !startReply(idx, selectedText)) {
        quotePreview->setQuoteText(selectedText);
    }
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
    replyAction->setText(AirStrings::text(AirStrings::Text::MessageReply));
    reactMenu->setTitle(AirStrings::text(AirStrings::Text::MessageReact));
    quoteAction->setText(tr("Quote selected text"));
    copyLinkAction->setText(tr("Copy link address"));
    searchAction->setText(tr("Search in text"));
    goToCurrentDateAction->setText(tr("Go to current date"));
    loadHistoryAction->setText(tr("Load chat history..."));
    exportChatAction->setText(tr("Export to file"));
}
