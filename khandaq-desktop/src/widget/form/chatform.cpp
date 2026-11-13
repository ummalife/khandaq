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

#include "chatform.h"
#include "src/core/khandaqlimits.h"
#include "src/chatlog/chatlinecontentproxy.h"
#include "src/chatlog/chatwidget.h"
#include "src/chatlog/chatmessage.h"
#include "src/chatlog/content/filetransferwidget.h"
#include "src/chatlog/content/text.h"
#include "src/core/core.h"
#include "src/core/coreav.h"
#include "src/core/corefile.h"
#include "src/model/air/airconversation.h"
#include "src/model/chathistory.h"
#include "src/model/friend.h"
#include "src/model/status.h"
#include "src/nexus.h"
#include "src/persistence/history.h"
#include "src/persistence/offlinemsgengine.h"
#include "src/persistence/profile.h"
#include "src/persistence/settings.h"
#include "src/platform/mediaaccess.h"
#include "src/video/netcamview.h"
#include "src/widget/air/aircomposer.h"
#include "src/widget/air/airdossier.h"
#include "src/widget/air/airlocal.h"
#include "src/widget/air/airmediaparts.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airsource.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtray.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/chatformheader.h"
#include "src/widget/contentdialogmanager.h"
#include "src/widget/form/loadhistorydialog.h"
#include "src/widget/imagepreviewwidget.h"
#include "src/widget/maskablepixmapwidget.h"
#include "src/widget/searchform.h"
#include "src/widget/style.h"
#include "src/widget/tool/chattextedit.h"
#include "src/widget/tool/croppinglabel.h"
#include "src/widget/tool/imessageboxmanager.h"
#include "src/widget/tool/screenshotgrabber.h"
#include "src/widget/translator.h"
#include "src/widget/widget.h"

#include <QApplication>
#include <QClipboard>
#include <QFileDialog>
#include <QFileInfo>
#include <QMimeData>
#include <QPushButton>
#include <QScrollBar>
#include <QSplitter>
#include <QStringBuilder>

#include <cassert>

/**
 * @brief ChatForm::incomingNotification Notify that we are called by someone.
 * @param friendId Friend that is calling us.
 *
 * @brief ChatForm::outgoingNotification Notify that we are calling someone.
 *
 * @brief stopNotification Tell others to stop notification of a call.
 */
namespace {
constexpr int CHAT_WIDGET_MIN_HEIGHT = 50;
constexpr int SCREENSHOT_GRABBER_OPENING_DELAY = 500;
constexpr int TYPING_NOTIFICATION_DURATION = 3000;
/** The presence words count minutes; reading them twice a minute keeps them right. */
constexpr int HEAD_TICK_MS = 30 * 1000;
/** The tray takes at most this share of the column, so the transcript stays in view. */
constexpr qreal TRAY_SHARE = 0.28;
/** The dossier lists the files of this many latest items of the conversation. */
constexpr size_t DOSSIER_FILE_WINDOW = 300;

QString clockText(const QDateTime& time)
{
    // Hours and minutes in the Latin digits of the Plex type, whatever the language.
    return QLocale::c().toString(time.toLocalTime().time(), QStringLiteral("H:mm"));
}
} // namespace

const QString ChatForm::ACTION_PREFIX = QStringLiteral("/me ");

namespace {
using AirText = AirStrings::Text;

/** A call that could not start or be answered: what happened in words, the toxav code for support. */
void showCallFailure(QWidget* anchor, bool answering, const CoreAV::CallError& error,
                     const QString& person)
{
    QString message;
    QString details;
    switch (error.kind) {
    case CoreAV::CallError::Kind::AlreadyInCall:
        message = AirStrings::text(AirText::CallAlreadyOn);
        break;
    case CoreAV::CallError::Kind::NotCalling:
        message = AirStrings::text(AirText::CallNotCalling).arg(person);
        break;
    case CoreAV::CallError::Kind::None:
    case CoreAV::CallError::Kind::Offline:
    case CoreAV::CallError::Kind::Toxav:
        message = AirStrings::text(AirText::TryAgain);
        if (error.code != 0) {
            details =
                QStringLiteral("%1 %2")
                    .arg(answering ? QStringLiteral("toxav_answer") : QStringLiteral("toxav_call"))
                    .arg(error.code);
        }
        break;
    }
    AirDialog::notice(anchor,
                      AirStrings::text(answering ? AirText::CallAnswerFailedTitle
                                                 : AirText::CallStartFailedTitle),
                      message, details);
}
} // namespace

namespace {
QString secondsToDHMS(quint32 duration)
{
    QString res;
    QString cD = ChatForm::tr("Call duration: ");
    quint32 seconds = duration % 60;
    duration /= 60;
    quint32 minutes = duration % 60;
    duration /= 60;
    quint32 hours = duration % 24;
    quint32 days = duration / 24;

    // I assume no one will ever have call longer than a month
    if (days) {
        return cD + res.asprintf("%dd%02dh %02dm %02ds", days, hours, minutes, seconds);
    }

    if (hours) {
        return cD + res.asprintf("%02dh %02dm %02ds", hours, minutes, seconds);
    }

    if (minutes) {
        return cD + res.asprintf("%02dm %02ds", minutes, seconds);
    }

    return cD + res.asprintf("%02ds", seconds);
}
} // namespace

ChatForm::ChatForm(Profile& profile_, Friend* chatFriend, IChatLog& chatLog_,
    IMessageDispatcher& messageDispatcher_, DocumentCache& documentCache_,
    SmileyPack& smileyPack_, CameraSource& cameraSource_, Settings& settings_,
    Style& style_, IMessageBoxManager& messageBoxManager,
    ContentDialogManager& contentDialogManager_, FriendList& friendList_,
    GroupList& groupList_)
    : GenericChatForm(profile_.getCore(), chatFriend, chatLog_, messageDispatcher_,
        documentCache_, smileyPack_, settings_, style_, messageBoxManager, friendList_,
        groupList_)
    , core{profile_.getCore()}
    , f(chatFriend)
    , isTyping{false}
    , lastCallIsVideo{false}
    , cameraSource{cameraSource_}
    , settings{settings_}
    , style{style_}
    , contentDialogManager{contentDialogManager_}
    , profile{profile_}
{
    setName(f->getDisplayedName());

    // The head: the person's tile, the name that unfolds the dossier, presence and the day band.
    // The status message moved into the dossier, under the name.
    headWidget->setTile(f->getDisplayedName(), Air::toneIndex(f->getPublicKey().toString()),
                        AirTile::Kind::Person);
    headWidget->setDossierEnabled(true);
    headWidget->setNameEditable(false);
    headWidget->setDayBandVisible(true);

    typingTimer.setSingleShot(true);

    callDurationTimer = nullptr;

    chatWidget->setMinimumHeight(CHAT_WIDGET_MIN_HEIGHT);
    chatWidget->setPersonOnline(Status::isOnline(f->getStatus()));
    chatWidget->setPersonName(Air::shortName(f->getDisplayedName()));

    // A call is a band under the name (E9), so the transcript stays in view.
    callBand = new AirCallBand(this);
    headWidget->addBand(callBand);
    connect(callBand, &AirCallBand::answerRequested, this, &ChatForm::answerIncoming);
    connect(callBand, &AirCallBand::declineRequested, this, &ChatForm::onRejectCallTriggered);
    connect(callBand, &AirCallBand::endRequested, this, [this] {
        CoreAV* const av = core.getAv();
        if (av->isCallStarted(f)) {
            av->cancelCall(f->getId());
        }
    });
    connect(callBand, &AirCallBand::microphoneToggled, this, &ChatForm::onMicMuteToggle);
    connect(callBand, &AirCallBand::soundToggled, this, &ChatForm::onVolMuteToggle);
    connect(callBand, &AirCallBand::waitCancelled, this, &ChatForm::cancelWaiting);

    imagePreview = new ImagePreviewButton(this);
    imagePreview->setFixedSize(100, 100);
    imagePreview->setFlat(true);
    imagePreview->setStyleSheet("QPushButton { border: 0px }");
    imagePreview->hide();

    auto cancelIcon = QIcon(style.getImagePath("rejectCall/rejectCall.svg", settings));
    QPushButton* cancelButton = new QPushButton(imagePreview);
    cancelButton->setFixedSize(20, 20);
    cancelButton->move(QPoint(80, 0));
    cancelButton->setIcon(cancelIcon);
    cancelButton->setFlat(true);

    connect(cancelButton, &QPushButton::pressed, this, &ChatForm::cancelImagePreview);

    contentLayout->insertWidget(3, imagePreview);

    // Between the transcript and the field: the messages the network gave up on, each in its own
    // row, then the tray of those waiting for the person.
    queueLog = qobject_cast<ChatHistory*>(&chatLog_);
    failedList = new AirFailedList(this);
    tray = new AirTray(this);
    tray->limitTo(this, TRAY_SHARE);
    contentLayout->insertWidget(3, tray);
    contentLayout->insertWidget(3, failedList);
    if (queueLog) {
        connect(queueLog, &ChatHistory::queueChanged, this, &ChatForm::refreshQueue);
    }
    connect(tray, &AirTray::editRequested, this, &ChatForm::editQueued);
    connect(tray, &AirTray::cancelRequested, this, &ChatForm::cancelQueued);
    connect(failedList, &AirFailedList::retryRequested, this, &ChatForm::retryQueued);
    connect(failedList, &AirFailedList::editRequested, this, &ChatForm::editQueued);
    connect(failedList, &AirFailedList::cancelRequested, this, &ChatForm::cancelQueued);
    msgEdit->setEditLastHandler([this] { return editLastQueued(); });

    // The dossier unfolds over this conversation from its head.
    dossier = new AirDossier(this);
    connect(headWidget, &ChatFormHeader::dossierToggled, this, &ChatForm::toggleDossier);
    connect(dossier, &AirDossier::openChanged, headWidget, &ChatFormHeader::setDossierOpen);
    connect(dossier, &AirDossier::renameRequested, this, [this] {
        dossier->close();
        headWidget->beginRename();
    });
    connect(dossier, &AirDossier::muteChanged, this, [this](bool muted) {
        if (airLocal) {
            airLocal->setMuted(f->getPublicKey(), muted);
        }
    });
    connect(dossier, &AirDossier::verifiedChanged, this, [this](bool verified) {
        if (airLocal) {
            airLocal->setVerified(f->getPublicKey(), verified);
        }
    });
    connect(dossier, &AirDossier::removeRequested, this, [this] {
        dossier->close();
        emit removeFriendRequested(f->getPublicKey());
    });
    connect(dossier, &AirDossier::blockRequested, this, [this] {
        dossier->close();
        // The key is listed before anything else, so a request that lands meanwhile is dropped.
        if (airLocal) {
            airLocal->block(f->getPublicKey());
        }
        emit blockFriendRequested(f->getPublicKey());
    });
    // A received file opens as from the file list and the transfer: an executable asks first.
    IMessageBoxManager* const messageBoxes = &messageBoxManager;
    connect(dossier, &AirDossier::fileOpenRequested, this, [messageBoxes](const QString& path) {
        messageBoxes->confirmExecutableOpen(QFileInfo(path));
    });
    // The photo goes to the head and, when open, the dossier: both exist from here on.
    refreshPhoto();

    headLater.setSingleShot(true);
    headLater.setInterval(0);
    connect(&headLater, &QTimer::timeout, this, &ChatForm::refreshHead);
    headTicker.setInterval(HEAD_TICK_MS);
    connect(&headTicker, &QTimer::timeout, this, &ChatForm::refreshHead);
    headTicker.start();
    connect(msgEdit, &ChatTextEdit::textChanged, this, &ChatForm::refreshCounter);

    const CoreFile* coreFile = core.getCoreFile();
    connect(&profile, &Profile::friendAvatarChanged, this, &ChatForm::onAvatarChanged);
    connect(coreFile, &CoreFile::fileReceiveRequested, this, &ChatForm::updateFriendActivityForFile);
    connect(coreFile, &CoreFile::fileSendStarted, this, &ChatForm::updateFriendActivityForFile);
    connect(&core, &Core::friendTypingChanged, this, &ChatForm::onFriendTypingChanged);
    connect(chatFriend, &Friend::statusChanged, this, &ChatForm::onFriendStatusChanged);

    const CoreAV* av = core.getAv();
    connect(av, &CoreAV::avInvite, this, &ChatForm::onAvInvite);
    connect(av, &CoreAV::avStart, this, &ChatForm::onAvStart);
    connect(av, &CoreAV::avEnd, this, &ChatForm::onAvEnd);

    connect(headWidget, &ChatFormHeader::callTriggered, this, &ChatForm::onCallTriggered);
    connect(headWidget, &ChatFormHeader::videoCallTriggered, this, &ChatForm::onVideoCallTriggered);
    connect(headWidget, &ChatFormHeader::micMuteToggle, this, &ChatForm::onMicMuteToggle);
    connect(headWidget, &ChatFormHeader::volMuteToggle, this, &ChatForm::onVolMuteToggle);
    connect(sendButton, &QPushButton::pressed, this, &ChatForm::callUpdateFriendActivity);
    connect(sendButton, &QPushButton::pressed, this, &ChatForm::sendImageFromPreview);
    connect(msgEdit, &ChatTextEdit::enterPressed, this, &ChatForm::callUpdateFriendActivity);
    connect(msgEdit, &ChatTextEdit::textChanged, this, &ChatForm::onTextEditChanged);
    connect(msgEdit, &ChatTextEdit::pasteImage, this, &ChatForm::previewImage);
    connect(msgEdit, &ChatTextEdit::enterPressed, this, &ChatForm::sendImageFromPreview);
    connect(msgEdit, &ChatTextEdit::escapePressed, this, &ChatForm::cancelImagePreview);

    connect(&typingTimer, &QTimer::timeout, this, [&] {
        core.sendTyping(f->getId(), false);
        isTyping = false;
    });

    // reflect name changes in the header
    connect(headWidget, &ChatFormHeader::nameChanged, this,
            [=](const QString& newName) { f->setAlias(newName); });

    connect(bodySplitter, &QSplitter::splitterMoved, this, &ChatForm::onSplitterMoved);

    connect(f, &Friend::extensionSupportChanged, this, &ChatForm::onExtensionSupportChanged);
    connect(f, &Friend::displayedNameChanged, this, [this] {
        headWidget->setTile(f->getDisplayedName(), Air::toneIndex(f->getPublicKey().toString()),
                            AirTile::Kind::Person);
        // The file entries and the call band name the person too.
        chatWidget->setPersonName(Air::shortName(f->getDisplayedName()));
        if (callBand->phase() == AirCallBand::Phase::Waiting) {
            callBand->setPhase(AirCallBand::Phase::Waiting, Air::shortName(f->getDisplayedName()));
        }
        // The tray names the person too.
        refreshQueue();
        headLater.start();
    });
    connect(&profile, &Profile::friendAvatarRemoved, this, [this](const ToxPk& friendPk) {
        if (friendPk == f->getPublicKey()) {
            QTimer::singleShot(0, this, &ChatForm::refreshPhoto);
        }
    });

    updateCallButtons();
    refreshQueue();
    refreshHead();

    setAcceptDrops(true);
    retranslateUi();
    Translator::registerHandler(std::bind(&ChatForm::retranslateUi, this), this);
}

ChatForm::~ChatForm()
{
    Translator::unregister(this);
}

void ChatForm::setStatusMessage(const QString& newMessage)
{
    // The status message shows in the dossier, under the name.
    std::ignore = newMessage;
    if (dossier->isOpen()) {
        dossier->setInfo(dossierInfo());
    }
}

void ChatForm::setAir(AirSource* source, AirLocal* local)
{
    airSource = source;
    airLocal = local;
    if (airSource) {
        connect(airSource, &AirSource::changed, &headLater, QOverload<>::of(&QTimer::start));
    }
    if (airLocal) {
        connect(airLocal, &AirLocal::changed, this, [this](const ToxPk& friendPk) {
            if (friendPk == f->getPublicKey()) {
                headLater.start();
            }
        });
    }
    refreshHead();
}

void ChatForm::refreshHead()
{
    const ToxPk& friendPk = f->getPublicKey();
    const bool online = Status::isOnline(f->getStatus());
    const Air::Reach reach = airSource ? airSource->reachOf(*f)
                             : online  ? Air::Reach::direct()
                                       : Air::Reach::awayLongAgo();
    const QDateTime since = airLocal ? airLocal->onlineSince(friendPk) : QDateTime();
    headWidget->setPresence(Air::linkState(reach),
                            AirStrings::presenceText(reach,
                                                     since.isValid() ? clockText(since) : QString(),
                                                     friendTyping));

    const QDateTime now = QDateTime::currentDateTime();
    const QSet<qint64> hours = airLocal ? airLocal->hours(friendPk) : QSet<qint64>();
    const Air::DayStrip strip = Air::DayStrip::make(hours, now);
    int bestFrom = 0;
    int bestTo = 0;
    const bool hasBest = Air::bestHours(hours, now, &bestFrom, &bestTo);
    headWidget->setDayBand(strip, online, AirStrings::dayHintText(strip, hasBest, bestFrom, bestTo));

    // The line under the field: what happens to a message sent now.
    const Air::ComposeHint hint =
        Air::ComposeHint::make(false, true, reach,
                               airSource ? airSource->networkState() : Air::NetworkState::Direct);
    composer->setHint(AirStrings::composeHintText(hint, Air::shortName(f->getDisplayedName())),
                      hint.dot);

    if (dossier->isOpen()) {
        dossier->setInfo(dossierInfo());
    }
}

void ChatForm::refreshQueue()
{
    QVector<AirQueueItem> waiting;
    QVector<AirQueueItem> failed;
    if (queueLog) {
        for (const ChatHistory::QueuedMessage& message : queueLog->queuedMessages()) {
            AirQueueItem item;
            item.id = message.outboxId;
            item.time = clockText(message.timestamp);
            item.text = message.isAction ? ChatForm::ACTION_PREFIX + message.text : message.text;
            (message.failed ? failed : waiting).append(item);
        }
    }
    // "In transit" only while the person is away: online, the same messages are leaving or sent,
    // and the transcript shows them.
    const bool online = Status::isOnline(f->getStatus());
    failedList->setItems(failed);
    tray->setItems(online ? QVector<AirQueueItem>{} : waiting,
                   Air::shortName(f->getDisplayedName()), online);
    // The rows of the unsent messages are made afresh: they join the order where they stand.
    orderTabs();
}

void ChatForm::orderTabs()
{
    // The field and the buttons beside it were made before the rows and the tray, so the chain
    // would reach them first. The rows are taken in the order they were made: rows made afresh
    // stand at the end of the chain, so in its order they would come before the tray's chevron.
    QList<QWidget*> order{chatWidget};
    order << AirFocus::made(failedList) << AirFocus::made(tray) << AirFocus::stops(composer);
    AirFocus::chain(order);
}

void ChatForm::refreshCounter()
{
    // The room left once a tenth of a message remains; past the limit the text goes in parts.
    const int bytes = msgEdit->toPlainText().toUtf8().size();
    const Air::ComposeRoom room = Air::composeRoom(bytes, static_cast<int>(core.getMaxMessageSize()));
    composer->setCounter(room.shown ? AirStrings::composeCounterText(room.left) : QString());
}

void ChatForm::refreshPhoto()
{
    QPixmap loaded;
    const QByteArray avatarData = profile.loadAvatarData(f->getPublicKey());
    if (!avatarData.isEmpty()) {
        loaded.loadFromData(avatarData);
    }
    photo = loaded;
    headWidget->setAvatar(photo);
    if (dossier->isOpen()) {
        dossier->setInfo(dossierInfo());
    }
}

void ChatForm::toggleDossier()
{
    if (dossier->isOpen()) {
        dossier->close();
        return;
    }
    dossier->setInfo(dossierInfo());
    dossier->open();
}

QLocale ChatForm::interfaceLocale() const
{
    const QString code = settings.getTranslation();
    return code.isEmpty() ? QLocale::system() : QLocale(code);
}

AirDossierInfo ChatForm::dossierInfo() const
{
    const ToxPk& friendPk = f->getPublicKey();
    const QDateTime now = QDateTime::currentDateTime();
    const bool online = Status::isOnline(f->getStatus());

    AirDossierInfo info;
    info.locale = interfaceLocale();
    info.name = f->getDisplayedName();
    info.statusMessage = f->getStatusMessage();
    info.tone = Air::toneIndex(friendPk.toString());
    info.photo = photo;
    info.publicKey = friendPk.toString();
    const Air::Reach reach = airSource ? airSource->reachOf(*f)
                             : online  ? Air::Reach::direct()
                                       : Air::Reach::awayLongAgo();
    info.link = Air::linkState(reach);
    const QSet<qint64> hours = airLocal ? airLocal->hours(friendPk) : QSet<qint64>();
    info.strip = Air::DayStrip::make(hours, now);
    info.liveNow = online;
    info.hasBest = Air::bestHours(hours, now, &info.bestFrom, &info.bestTo);
    info.muted = airLocal && airLocal->isMuted(friendPk);
    const QDateTime verified = airLocal ? airLocal->verifiedAt(friendPk) : QDateTime();
    if (verified.isValid()) {
        info.verifiedDate = AirStrings::latinDigits(info.locale.toString(verified.toLocalTime().date(),
                                                                         QLocale::LongFormat),
                                                    info.locale);
    }

    // The files of the latest part of the conversation, newest first.
    const size_t first = chatLog.getFirstIdx().get();
    const size_t next = chatLog.getNextIdx().get();
    const size_t start = std::max(first, next > DOSSIER_FILE_WINDOW ? next - DOSSIER_FILE_WINDOW : 0);
    for (size_t index = next; index-- > start;) {
        const ChatLogItem& item = chatLog.at(ChatLogIdx(index));
        if (item.getContentType() != ChatLogItem::ContentType::fileTransfer) {
            continue;
        }
        const ToxFile& file = item.getContentAsFile().file;
        AirDossierFile entry;
        entry.name = file.fileName;
        // Only a finished file is on this computer to open.
        entry.path = file.status == ToxFile::FINISHED ? file.filePath : QString();
        entry.size = static_cast<qint64>(file.progress.getFileSize());
        info.files.append(entry);
    }
    return info;
}

void ChatForm::editQueued(qint64 id)
{
    if (!queueLog) {
        return;
    }
    for (const ChatHistory::QueuedMessage& message : queueLog->queuedMessages()) {
        if (message.outboxId != id) {
            continue;
        }
        const QString text = message.isAction ? ChatForm::ACTION_PREFIX + message.text : message.text;
        // The message comes back into the field; a draft already there stays in front of it.
        queueLog->cancelQueuedMessage(id);
        const QString draft = msgEdit->toPlainText();
        msgEdit->setPlainText(draft.isEmpty() ? text : draft + QLatin1Char('\n') + text);
        msgEdit->moveCursor(QTextCursor::End);
        msgEdit->setFocus();
        return;
    }
}

void ChatForm::cancelQueued(qint64 id)
{
    if (queueLog) {
        queueLog->cancelQueuedMessage(id);
    }
}

void ChatForm::retryQueued(qint64 id)
{
    if (!queueLog) {
        return;
    }
    for (const ChatHistory::QueuedMessage& message : queueLog->queuedMessages()) {
        if (message.outboxId == id && message.failed) {
            // The same text goes again as a new message; the failed one leaves the conversation.
            const bool isAction = message.isAction;
            const QString text = message.text;
            queueLog->cancelQueuedMessage(id);
            messageDispatcher.sendMessage(isAction, text);
            emit updateFriendActivity(*f);
            return;
        }
    }
}

bool ChatForm::editLastQueued()
{
    // Up in an empty field takes back the newest message still waiting in the tray.
    if (!queueLog || Status::isOnline(f->getStatus())) {
        return false;
    }
    const QVector<ChatHistory::QueuedMessage> queue = queueLog->queuedMessages();
    for (int i = queue.size() - 1; i >= 0; --i) {
        if (!queue[i].failed) {
            editQueued(queue[i].outboxId);
            return true;
        }
    }
    return false;
}

void ChatForm::callUpdateFriendActivity()
{
    emit updateFriendActivity(*f);
}

void ChatForm::updateFriendActivityForFile(const ToxFile& file)
{
    if (file.friendId != f->getId()) {
        return;
    }
    emit updateFriendActivity(*f);
}

void ChatForm::onExtensionSupportChanged(ExtensionSet extensions)
{
    headWidget->updateExtensionSupport(extensions);
}

void ChatForm::onTextEditChanged()
{
    if (!settings.getTypingNotification()) {
        if (isTyping) {
            isTyping = false;
            core.sendTyping(f->getId(), false);
        }

        return;
    }
    bool isTypingNow = !msgEdit->toPlainText().trimmed().isEmpty();
    if (isTyping != isTypingNow) {
        core.sendTyping(f->getId(), isTypingNow);
        if (isTypingNow) {
            typingTimer.start(TYPING_NOTIFICATION_DURATION);
        }

        isTyping = isTypingNow;
    }
}

void ChatForm::onAttachClicked()
{
    const QStringList paths = QFileDialog::getOpenFileNames(window(), tr("Send a file"),
                                                            QDir::homePath(), QString(), nullptr);
    sendLocalFiles(paths);
}

void ChatForm::onPasteFiles(const QStringList& paths)
{
    sendLocalFiles(paths);
}

void ChatForm::sendLocalFiles(const QStringList& paths)
{
    if (paths.isEmpty()) {
        return;
    }

    // Every refusal is said, in one dialog for all the files chosen.
    const QLocale locale = interfaceLocale();
    const QString person = Air::shortName(f->getDisplayedName());
    QStringList refusals;
    for (const QString& path : paths) {
        QFile file(path);
        const QString fileName = QFileInfo(path).fileName();
        if (!file.exists() || !file.open(QIODevice::ReadOnly) || file.isSequential()) {
            refusals.append(AirStrings::text(AirText::FileUnreadable).arg(fileName));
            continue;
        }
        file.close();

        const qint64 filesize = file.size();
        if (filesize <= 0) {
            refusals.append(AirStrings::text(AirText::FileEmpty).arg(fileName));
            continue;
        }
        if (static_cast<quint64>(filesize) > KHANDAQ_MAX_FILE_TRANSFER_BYTES) {
            refusals.append(
                AirStrings::text(AirText::FileTooLarge)
                    .arg(fileName, AirStrings::byteSizeText(Air::fileLimitShownBytes, locale)));
            continue;
        }
        if (!Status::isOnline(f->getStatus())) {
            // A Tox file goes only while both are online, and this client keeps no queue of files:
            // the transfer would break at once.
            refusals.append(AirStrings::text(AirText::FileOffline).arg(person));
            break;
        }
        core.getCoreFile()->sendFile(f->getId(), fileName, path, filesize);
    }
    if (!refusals.isEmpty()) {
        refusals.removeDuplicates();
        AirDialog::notice(this, AirStrings::text(AirText::FileNotSentTitle),
                          refusals.join(QLatin1Char('\n')));
    }
}

void ChatForm::onAvInvite(uint32_t friendId, bool video)
{
    if (friendId != f->getId()) {
        return;
    }

    // Their call ends a wait for them, and a voice note that plays.
    waitingToCall = false;
    AirVoicePlayer::instance().stop();
    callIncoming = true;
    lastCallIsVideo = video;
    callBand->setPhase(AirCallBand::Phase::Incoming, Air::shortName(f->getDisplayedName()), video);
    updateCallButtons();

    auto testedFlag = video ? Settings::AutoAcceptCall::Video : Settings::AutoAcceptCall::Audio;
    // AutoAcceptCall is set for this friend
    if (settings.getAutoAcceptCall(f->getPublicKey()).testFlag(testedFlag)) {
        qDebug() << "automatic call answer";
        answerIncoming(video);
    } else {
        emit incomingNotification(friendId);
    }
}

void ChatForm::onAvStart(uint32_t friendId, bool video)
{
    if (friendId != f->getId()) {
        return;
    }

    if (video) {
        showNetcam();
    } else {
        hideNetcam();
    }

    emit stopNotification();
    startCounter();
    callBand->setPhase(AirCallBand::Phase::Active, Air::shortName(f->getDisplayedName()));
    updateCallButtons();
}

void ChatForm::onAvEnd(uint32_t friendId, bool error)
{
    if (friendId != f->getId()) {
        return;
    }

    // Fixes an OS X bug with ending a call while in full screen
    if (netcam && netcam->isFullScreen()) {
        netcam->showNormal();
    }

    emit stopNotification();
    emit endCallNotification();
    // One entry says how the call went: its length once answered, else missed or no answer.
    const bool answered = callDurationTimer != nullptr;
    const AirCallBand::Phase phase = callBand->phase();
    if (answered || phase == AirCallBand::Phase::Incoming || phase == AirCallBand::Phase::Outgoing) {
        recordCall(answered, error);
    }
    stopCounter();
    callBand->setPhase(AirCallBand::Phase::None);
    updateCallButtons();
    hideNetcam();
}

void ChatForm::onRejectCallTriggered()
{
    // The main window ends the call; its end writes the missed call.
    emit rejectCall(f->getId());
}

void ChatForm::onCallTriggered()
{
    CoreAV* av = core.getAv();
    if (av->isCallStarted(f)) {
        av->cancelCall(f->getId());
        return;
    }
    requestCall(false);
}

void ChatForm::onVideoCallTriggered()
{
    CoreAV* av = core.getAv();
    if (av->isCallStarted(f)) {
        // End in the video view; a call that is on cannot take video in this version.
        if (av->isCallVideoEnabled(f)) {
            av->cancelCall(f->getId());
        }
        return;
    }
    requestCall(true);
}

void ChatForm::requestCall(bool video)
{
    if (waitingToCall) {
        return;
    }
    checkCallAccess(video, [this](bool withVideo) {
        if (Status::isOnline(f->getStatus())) {
            placeCall(withVideo);
        } else {
            askToWait(withVideo);
        }
    });
}

void ChatForm::checkCallAccess(bool video, std::function<void(bool video)> proceed)
{
    using MediaAccess::Device;
    const QPointer<ChatForm> self(this);
    MediaAccess::ask(Device::Microphone, this, [self, video, proceed](bool granted) {
        // One read of the guard: the form is either gone or here for the whole answer.
        ChatForm* const form = self.data();
        if (!form) {
            return;
        }
        if (!granted) {
            form->explainMicrophone();
            return;
        }
        if (!video) {
            proceed(false);
            return;
        }
        MediaAccess::ask(Device::Camera, form, [self, proceed](bool cameraGranted) {
            ChatForm* const still = self.data();
            if (!still) {
                return;
            }
            if (cameraGranted) {
                proceed(true);
            } else {
                still->explainCamera(proceed);
            }
        });
    });
}

void ChatForm::explainMicrophone()
{
    AirDialog::confirm(this, AirStrings::text(AirText::CallMicTitle),
                       AirStrings::text(AirText::CallMicMessage),
                       AirStrings::text(AirText::CallOpenSettings), AirDialog::Verb::Ink,
                       [] { MediaAccess::openSettings(MediaAccess::Device::Microphone); });
}

void ChatForm::explainCamera(std::function<void(bool video)> proceed)
{
    // The call can go with sound alone; the settings are the other way out.
    AirDialog* const dialog = AirDialog::confirm(this, AirStrings::text(AirText::CallCameraTitle),
                                                 AirStrings::text(AirText::CallCameraMessage),
                                                 AirStrings::text(AirText::CallAudioOnly),
                                                 AirDialog::Verb::Ink, [proceed] { proceed(false); });
    if (dialog) {
        dialog->addChoice(AirStrings::text(AirText::CallOpenSettings),
                          [] { MediaAccess::openSettings(MediaAccess::Device::Camera); });
    }
}

void ChatForm::placeCall(bool video)
{
    CoreAV* const av = core.getAv();
    if (av->isCallStarted(f)) {
        return;
    }
    AirVoicePlayer::instance().stop();
    CoreAV::CallError error;
    if (!av->startCall(f->getId(), video, &error)) {
        if (error.kind == CoreAV::CallError::Kind::Offline) {
            // toxav knew before the list did: the same offer as for someone shown offline.
            askToWait(video);
        } else {
            showCallFailure(this, false, error, Air::shortName(f->getDisplayedName()));
        }
        updateCallButtons();
        return;
    }
    callIncoming = false;
    callBand->setPhase(AirCallBand::Phase::Outgoing, Air::shortName(f->getDisplayedName()));
    updateCallButtons();
    emit outgoingNotification();
    emit updateFriendActivity(*f);
}

void ChatForm::askToWait(bool video)
{
    const QPointer<ChatForm> self(this);
    const QString person = Air::shortName(f->getDisplayedName());
    AirDialog::confirm(this, AirStrings::text(AirText::CallOfflineTitle).arg(person),
                       AirStrings::text(AirText::CallOfflineWait),
                       AirStrings::text(AirText::CallWaitAction), AirDialog::Verb::Ink, [self, video] {
                           ChatForm* const form = self.data();
                           if (!form) {
                               return;
                           }
                           // They may have come online while the question was open.
                           if (Status::isOnline(form->f->getStatus())) {
                               form->placeCall(video);
                               return;
                           }
                           form->waitingToCall = true;
                           form->waitingVideo = video;
                           form->callBand->setPhase(AirCallBand::Phase::Waiting,
                                                    Air::shortName(form->f->getDisplayedName()));
                           form->updateCallButtons();
                       });
}

void ChatForm::cancelWaiting()
{
    waitingToCall = false;
    if (callBand->phase() == AirCallBand::Phase::Waiting) {
        callBand->setPhase(AirCallBand::Phase::None);
    }
    updateCallButtons();
}

void ChatForm::answerIncoming(bool video)
{
    const QPointer<ChatForm> self(this);
    // Only a call of theirs that still rings is answered.
    MediaAccess::ask(MediaAccess::Device::Microphone, this, [self, video](bool granted) {
        ChatForm* const form = self.data();
        if (!form || !form->stillRinging()) {
            return;
        }
        if (!granted) {
            // Without the microphone the call would go on in silence: it is
            // declined, and the dialog says why.
            form->onRejectCallTriggered();
            form->explainMicrophone();
            return;
        }
        if (!video) {
            form->finishAnswer(false);
            return;
        }
        MediaAccess::ask(MediaAccess::Device::Camera, form, [self](bool cameraGranted) {
            ChatForm* const still = self.data();
            if (!still || !still->stillRinging()) {
                return;
            }
            if (cameraGranted) {
                still->finishAnswer(true);
                return;
            }
            still->explainCamera([self](bool) {
                ChatForm* const answering = self.data();
                if (answering && answering->stillRinging()) {
                    answering->finishAnswer(false);
                }
            });
        });
    });
}

bool ChatForm::stillRinging() const
{
    CoreAV* const av = core.getAv();
    return callBand->phase() == AirCallBand::Phase::Incoming && av->isCallStarted(f)
           && !av->isCallActive(f);
}

void ChatForm::finishAnswer(bool video)
{
    const uint32_t friendId = f->getId();
    emit stopNotification();
    emit acceptCall(friendId);

    CoreAV* const av = core.getAv();
    CoreAV::CallError error;
    if (!av->answerCall(friendId, video, &error)) {
        // Nothing was answered: the call stays a missed one, and the dialog says why.
        recordCall(false, false);
        callBand->setPhase(AirCallBand::Phase::None);
        stopCounter();
        hideNetcam();
        updateCallButtons();
        showCallFailure(this, true, error, Air::shortName(f->getDisplayedName()));
        return;
    }

    onAvStart(friendId, av->isCallVideoEnabled(f));
}

void ChatForm::recordCall(bool answered, bool error)
{
    const QString name = f->getDisplayedName();
    if (answered) {
        const qint64 seconds = timeElapsed.elapsed() / 1000;
        addSystemInfoMessage(QDateTime::currentDateTime(),
                             error ? SystemMessageType::unexpectedCallEnd : SystemMessageType::callEnd,
                             Air::answeredCallArgs(name, seconds,
                                                   secondsToDHMS(static_cast<quint32>(seconds))));
    } else {
        addSystemInfoMessage(QDateTime::currentDateTime(),
                             callIncoming ? SystemMessageType::incomingCall
                                          : SystemMessageType::outgoingCall,
                             Air::unansweredCallArgs(name));
    }
}

void ChatForm::updateCallButtons()
{
    CoreAV* av = core.getAv();
    const bool audio = av->isCallActive(f);
    const bool video = av->isCallVideoEnabled(f);
    const bool online = Status::isOnline(f->getStatus());
    headWidget->updateCallButtons(online, audio, video);
    // While the band is up it holds the buttons of the call.
    headWidget->setCallsBusy(callBand->phase() != AirCallBand::Phase::None);
    updateMuteMicButton();
    updateMuteVolButton();
}

void ChatForm::onMicMuteToggle()
{
    CoreAV* av = core.getAv();
    av->toggleMuteCallInput(f);
    updateMuteMicButton();
}

void ChatForm::onVolMuteToggle()
{
    CoreAV* av = core.getAv();
    av->toggleMuteCallOutput(f);
    updateMuteVolButton();
}

void ChatForm::onFriendStatusChanged(const ToxPk& friendPk, Status::Status status)
{
    // Disable call buttons if friend is offline
    assert(friendPk == f->getPublicKey());
    std::ignore = friendPk;

    const bool online = Status::isOnline(f->getStatus());
    if (!online) {
        // Hide the "is typing" message when a friend goes offline
        setFriendTyping(false);
    }

    updateCallButtons();
    // Our waiting messages leave the tray for the transcript when the person comes online, and
    // go back into it when they leave.
    chatWidget->setPersonOnline(online);
    refreshQueue();
    refreshHead();

    // Wait and call: the call starts by itself once they are online.
    if (online && waitingToCall) {
        waitingToCall = false;
        callBand->setPhase(AirCallBand::Phase::None);
        placeCall(waitingVideo);
    }

    if (settings.getStatusChangeNotificationEnabled()) {
        QString fStatus = Status::getTitle(status);
        addSystemInfoMessage(QDateTime::currentDateTime(), SystemMessageType::peerStateChange,
                             {f->getDisplayedName(), fStatus});
    }
}

void ChatForm::onFriendTypingChanged(quint32 friendId, bool isTyping_)
{
    if (friendId == f->getId()) {
        setFriendTyping(isTyping_);
    }
}

void ChatForm::onFriendNameChanged(const QString& name)
{
    if (sender() == f) {
        setName(name);
    }
}

void ChatForm::onStatusMessage(const QString& message)
{
    if (sender() == f) {
        setStatusMessage(message);
    }
}

void ChatForm::onAvatarChanged(const ToxPk& friendPk, const QPixmap& pic)
{
    if (friendPk != f->getPublicKey()) {
        return;
    }

    // The tile shows a photo the person set, never a generated picture; read it once saved.
    std::ignore = pic;
    QTimer::singleShot(0, this, &ChatForm::refreshPhoto);
}

std::unique_ptr<NetCamView> ChatForm::createNetcam()
{
    qDebug() << "creating netcam";
    uint32_t friendId = f->getId();
    std::unique_ptr<NetCamView> view = std::unique_ptr<NetCamView>(
        new NetCamView(f->getPublicKey(), cameraSource, settings, style, profile, this));
    CoreAV* av = core.getAv();
    VideoSource* source = av->getVideoSourceFromCall(friendId);
    view->show(source, f->getDisplayedName());
    connect(view.get(), &NetCamView::videoCallEnd, this, &ChatForm::onVideoCallTriggered);
    connect(view.get(), &NetCamView::volMuteToggle, this, &ChatForm::onVolMuteToggle);
    connect(view.get(), &NetCamView::micMuteToggle, this, &ChatForm::onMicMuteToggle);
    return view;
}

void ChatForm::dragEnterEvent(QDragEnterEvent* ev)
{
    if (ev->mimeData()->hasUrls()) {
        ev->acceptProposedAction();
    }
}

void ChatForm::dropEvent(QDropEvent* ev)
{
    if (!ev->mimeData()->hasUrls()) {
        return;
    }

    QStringList paths;
    for (const QUrl& url : ev->mimeData()->urls()) {
        QString urlString = url.toString();
        if (url.isValid() && !url.isLocalFile()
            && urlString.length() < static_cast<int>(tox_max_message_length())) {
            messageDispatcher.sendMessage(false, urlString);

            continue;
        }

        // The path of the URL first, then its local file, as either may name the file.
        QString path = QFileInfo(url.path()).absoluteFilePath();
        if (!QFileInfo::exists(path)) {
            path = QFileInfo(url.toLocalFile()).absoluteFilePath();
        }
        paths.append(path);
    }
    // A dropped file goes the way of a chosen one, with the same refusals in words.
    sendLocalFiles(paths);
}

void ChatForm::clearChatArea()
{
    GenericChatForm::clearChatArea(/* confirm = */ false, /* inform = */ true);
}

void ChatForm::onScreenshotClicked()
{
    doScreenshot();
    // Give the window manager a moment to open the fullscreen grabber window
    QTimer::singleShot(SCREENSHOT_GRABBER_OPENING_DELAY, this, SLOT(hideFileMenu()));
}

void ChatForm::doScreenshot()
{
    // note: grabber is self-managed and will destroy itself when done
    ScreenshotGrabber* grabber = new ScreenshotGrabber;
    connect(grabber, &ScreenshotGrabber::screenshotTaken, this, &ChatForm::previewImage);
    grabber->showGrabber();
}

void ChatForm::previewImage(const QPixmap& pixmap)
{
    imagePreviewSource = pixmap;
    imagePreview->setIconFromPixmap(pixmap);
    imagePreview->show();
}

void ChatForm::cancelImagePreview()
{
    imagePreviewSource = QPixmap();
    imagePreview->hide();
}

void ChatForm::sendImageFromPreview()
{
    if (!imagePreview->isVisible()) {
        return;
    }

    QDir(settings.getPaths().getAppDataDirPath()).mkpath("images");

    // use ~ISO 8601 for screenshot timestamp, considering FS limitations
    // https://en.wikipedia.org/wiki/ISO_8601
    // Windows has to be supported, thus filename can't have `:` in it :/
    // Format should be: `qTox_Screenshot_yyyy-MM-dd HH-mm-ss.zzz.png`
    QString filepath = QString("%1images%2Khandaq_Image_%3.png")
                           .arg(settings.getPaths().getAppDataDirPath())
                           .arg(QDir::separator())
                           .arg(QDateTime::currentDateTime().toString("yyyy-MM-dd HH-mm-ss.zzz"));
    QFile file(filepath);

    if (file.open(QFile::ReadWrite)) {
        imagePreviewSource.save(&file, "PNG");
        qint64 filesize = file.size();
        imagePreview->hide();
        imagePreviewSource = QPixmap();
        file.close();
        QFileInfo fi(file);
        CoreFile* coreFile = core.getCoreFile();
        coreFile->sendFile(f->getId(), fi.fileName(), fi.filePath(), filesize);
    } else {
        // The operation as the title, over the window (E21).
        AirDialog::notice(this, AirStrings::text(AirText::FileNotSentTitle),
                          AirStrings::text(AirText::ScreenshotNotSaved));
    }
}

void ChatForm::updateMuteMicButton()
{
    const CoreAV* av = core.getAv();
    bool active = av->isCallActive(f);
    bool inputMuted = av->isCallInputMuted(f);
    headWidget->updateMuteMicButton(active, inputMuted);
    callBand->setMuted(inputMuted, av->isCallOutputMuted(f));
    if (netcam) {
        netcam->updateMuteMicButton(inputMuted);
    }
}

void ChatForm::updateMuteVolButton()
{
    const CoreAV* av = core.getAv();
    bool active = av->isCallActive(f);
    bool outputMuted = av->isCallOutputMuted(f);
    headWidget->updateMuteVolButton(active, outputMuted);
    callBand->setMuted(av->isCallInputMuted(f), outputMuted);
    if (netcam) {
        netcam->updateMuteVolButton(outputMuted);
    }
}

void ChatForm::startCounter()
{
    if (callDurationTimer) {
        return;
    }
    callDurationTimer = new QTimer();
    connect(callDurationTimer, &QTimer::timeout, this, &ChatForm::onUpdateTime);
    callDurationTimer->start(1000);
    timeElapsed.start();
    callBand->setSeconds(0);
}

void ChatForm::stopCounter()
{
    if (!callDurationTimer) {
        return;
    }
    callDurationTimer->stop();
    delete callDurationTimer;
    callDurationTimer = nullptr;
}

void ChatForm::onUpdateTime()
{
    callBand->setSeconds(timeElapsed.elapsed() / 1000);
}

void ChatForm::setFriendTyping(bool isTyping_)
{
    // "typing" replaces the presence words in the head instead of a line in the transcript.
    if (friendTyping == isTyping_) {
        return;
    }
    friendTyping = isTyping_;
    refreshHead();
}

void ChatForm::show(ContentLayout* contentLayout_)
{
    GenericChatForm::show(contentLayout_);
}

void ChatForm::reloadTheme()
{
    GenericChatForm::reloadTheme();
}

void ChatForm::showEvent(QShowEvent* event)
{
    GenericChatForm::showEvent(event);
}

void ChatForm::hideEvent(QHideEvent* event)
{
    GenericChatForm::hideEvent(event);
}

void ChatForm::retranslateUi()
{
    refreshHead();
    refreshQueue();
    refreshCounter();

    updateMuteMicButton();
    updateMuteVolButton();

    if (netcam) {
        netcam->setShowMessages(chatWidget->isVisible());
    }
}

void ChatForm::showNetcam()
{
    if (!netcam) {
        netcam = createNetcam();
    }

    connect(netcam.get(), &NetCamView::showMessageClicked, this,
            &ChatForm::onShowMessagesClicked);

    bodySplitter->insertWidget(0, netcam.get());
    bodySplitter->setCollapsible(0, false);

    QSize minSize = netcam->getSurfaceMinSize();
    ContentDialog* current = contentDialogManager.current();
    if (current) {
        current->onVideoShow(minSize);
    }
}

void ChatForm::hideNetcam()
{
    if (!netcam) {
        return;
    }

    ContentDialog* current = contentDialogManager.current();
    if (current) {
        current->onVideoHide();
    }

    netcam->close();
    netcam->hide();
    netcam.reset();
}

void ChatForm::onSplitterMoved(int pos, int index)
{
    std::ignore = pos;
    std::ignore = index;
    if (netcam) {
        netcam->setShowMessages(bodySplitter->sizes()[1] == 0);
    }
}

void ChatForm::onShowMessagesClicked()
{
    if (netcam) {
        if (bodySplitter->sizes()[1] == 0) {
            bodySplitter->setSizes({1, 1});
        }
        else {
            bodySplitter->setSizes({1, 0});
        }

        onSplitterMoved(0, 0);
    }
}
