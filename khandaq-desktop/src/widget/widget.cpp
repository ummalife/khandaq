/*
    Copyright © 2014-2022 by The qTox Project Contributors

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

#include "widget.h"

#include <algorithm>
#include <cassert>

#include <QApplication>
#include <QClipboard>
#include <QDebug>
#include <QDesktopServices>
#include <QDir>
#include <QFile>
#include <QHash>
#include <QInputDialog>
#include <QMessageBox>
#include <QMouseEvent>
#include <QPainter>
#include <QRegularExpression>
#include <QScreen>
#include <QSet>
#include <QShortcut>
#include <QStandardPaths>
#include <QString>
#include <QSvgRenderer>
#include <QTime>
#include <QTimer>
#include <QWindow>
#ifdef Q_OS_MAC
#include <QMenuBar>
#include <QWindow>
#endif
#ifdef KHANDAQ_SCREENSHOT_HOOK
#include "src/chatlog/chatwidget.h"
#include "src/chatlog/content/text.h"
#include "src/platform/mediaaccess.h"
#include "src/widget/air/airaccessible.h"
#include "src/widget/tool/chatquotepreview.h"

#include <QAbstractButton>
#include <QElapsedTimer>
#include <QApplication>
#include <QGraphicsProxyWidget>
#include <QGraphicsScene>
#include <QGraphicsView>
#include <QKeyEvent>
#include <QKeySequence>
#include <QScrollArea>
#include <QTextEdit>

#include "tool/chattextedit.h"
#endif

#include "audio/audio.h"
#include "circlewidget.h"
#include "contentdialog.h"
#include "contentlayout.h"
#include "friendlistwidget.h"
#include "friendwidget.h"
#include "groupwidget.h"
#include "maskablepixmapwidget.h"
#include "splitterrestorer.h"
#include "form/groupchatform.h"
#include "src/chatlog/content/filetransferwidget.h"
#include "src/chatlog/documentcache.h"
#include "src/core/core.h"
#include "src/core/coreav.h"
#include "src/core/corefile.h"
#include "src/friendlist.h"
#include "src/grouplist.h"
#include "src/model/chathistory.h"
#include "src/model/chatroom/friendchatroom.h"
#include "src/model/chatroom/groupchatroom.h"
#include "src/model/friend.h"
#include "src/model/group.h"
#include "src/model/air/airmessagemarks.h"
#include "src/model/air/airpreferences.h"
#include "src/model/groupinvite.h"
#include "src/model/notes.h"
#include "src/model/sessionchatlog.h"
#include "src/model/profile/profileinfo.h"
#include "src/model/status.h"
#include "src/net/updatecheck.h"
#include "src/nexus.h"
#include "src/persistence/offlinemsgengine.h"
#include "src/persistence/profile.h"
#include "src/persistence/settings.h"
#include "src/platform/timer.h"
#include "src/widget/air/airband.h"
#include "src/widget/air/airchatparts.h"
#include "src/widget/air/aircontroller.h"
#include "src/widget/air/airlistview.h"
#include "src/widget/air/airlocal.h"
#include "src/widget/air/airmediaparts.h"
#include "src/widget/air/airnewcontact.h"
#include "src/widget/air/airnewgroup.h"
#include "src/widget/air/airoverlay.h"
#include "src/widget/air/airsettingspage.h"
#include "src/widget/air/airsource.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/air/airwalk.h"
#include "src/widget/air/airyou.h"
#include "src/widget/contentdialogmanager.h"
#include "src/widget/form/addfriendform.h"
#include "src/widget/form/chatform.h"
#include "src/widget/form/filesform.h"
#include "src/widget/form/groupinviteform.h"
#include "src/widget/form/notesform.h"
#include "src/widget/form/settings/avform.h"
#include "src/widget/style.h"
#include "src/widget/translator.h"
#include "src/widget/tool/messageboxmanager.h"
#include "tool/removechatdialog.h"
#include "src/persistence/smileypack.h"
#include "src/persistence/toxsave.h"
#include "src/ipc.h"

namespace {

/**
 * @brief Dangerous way to find out if a path is writable.
 * @param filepath Path to file which should be deleted.
 * @return True, if file writeable, false otherwise.
 */
bool tryRemoveFile(const QString& filepath)
{
    QFile tmp(filepath);
    bool writable = tmp.open(QIODevice::WriteOnly);
    tmp.remove();
    return writable;
}

/**
 * The least width of the window with the list and a conversation side by side, as the old window
 * had it (mainwindow.ui): the list column and room for a conversation beside it.
 */
constexpr int leastSideBySideWidth = 775;

/**
 * The least height of the window with the list and a conversation side by side: the Air band over
 * the sections of Settings (eight rows under their heading), or over a conversation with its
 * header, a row not sent, the tray and the composer, none of them squeezed.
 */
constexpr int leastSideBySideHeight = 480;

/**
 * The least height of the list alone, as the old window had it (mainwindow.ui), and of a window
 * that holds Settings or You on its own, where the sections of Settings take 413 px.
 */
constexpr int leastSingleHeight = 420;

/** The least width of a conversation in a window of its own, as the old window had it. */
constexpr int leastSeparateWidth = 720;

const QString activateHandlerKey("activate");
const QString saveHandlerKey("save");
/** The event another instance posts with a tox: link it was started with (AppManager::run). */
const QString uriHandlerKey("uri");

/**
 * The window of the app in front, which news comes over as the band (E22): the main window or a
 * window of chats. Null while another app is in front or the window is minimized, where the system
 * notification says it, and while Notify about new messages is off, where nothing says it.
 */
QWidget* bandWindow(QWidget* mainWindow, bool notify)
{
    if (!notify) {
        return nullptr;
    }
    QWidget* const active = QApplication::activeWindow();
    if (!active || !active->isVisible() || active->isMinimized()) {
        return nullptr;
    }
    if (active == mainWindow || qobject_cast<ContentDialog*>(active) != nullptr) {
        return active;
    }
    return nullptr;
}

/**
 * The main window comes to the top for what a band opens there. The band is up in a window of the
 * app in front, so this is no forceShow, which hides and shows the window to get it out of the Dock.
 */
void bringForward(QWidget* mainWindow)
{
    if (mainWindow->isMinimized()) {
        mainWindow->showNormal();
    }
    mainWindow->raise();
    mainWindow->activateWindow();
}

} // namespace

bool Widget::toxActivateEventHandler(const QByteArray& data, void* userData)
{
    std::ignore = data;

    qDebug() << "Handling" << activateHandlerKey << "event from other instance";
    static_cast<Widget*>(userData)->forceShow();
    return true;
}

bool Widget::toxUriEventHandler(const QByteArray& data, void* userData)
{
    return static_cast<Widget*>(userData)->handleToxUri(QString::fromUtf8(data));
}

void Widget::acceptFileTransfer(const ToxFile& file, const QString& path)
{
    QString filepath;
    int number = 0;

    QString suffix = QFileInfo(file.fileName).completeSuffix();
    QString base = QFileInfo(file.fileName).baseName();

    do {
        filepath = QString("%1/%2%3.%4")
                       .arg(path, base,
                            number > 0 ? QString(" (%1)").arg(QString::number(number)) : QString(),
                            suffix);
        ++number;
    } while (QFileInfo(filepath).exists());

    // Do not automatically accept the file-transfer if the path is not writable.
    // The user can still accept it manually.
    if (tryRemoveFile(filepath)) {
        CoreFile* coreFile = core->getCoreFile();
        coreFile->acceptFileRecvRequest(file.friendId, file.fileNum, filepath);
    } else {
        qWarning() << "Cannot write to " << filepath;
    }
}

Widget* Widget::instance{nullptr};

Widget::Widget(Profile& profile_, IAudioControl& audio_, CameraSource& cameraSource_,
               Settings& settings_, Style& style_, IPC& ipc_, Nexus& nexus_, QWidget* parent)
    : QMainWindow(parent)
    , profile{profile_}
    , trayMenu{nullptr}
    , ui(new Ui::MainWindow)
    , activeChatroomWidget{nullptr}
    , eventFlag(false)
    , eventIcon(false)
    , audio(audio_)
    , settings(settings_)
    , notifier{settings}
    , smileyPack(new SmileyPack(settings))
    , documentCache(new DocumentCache(*smileyPack, settings))
    , cameraSource{cameraSource_}
    , style{style_}
    , messageBoxManager(new MessageBoxManager(this))
    , friendList(new FriendList())
    , groupList(new GroupList())
    , contentDialogManager(new ContentDialogManager(*friendList))
    , ipc{ipc_}
    , toxSave(new ToxSave{ipc, *this})
    , nexus{nexus_}
{
    installEventFilter(this);
    QString locale = settings.getTranslation();
    Translator::translate(locale);
}

void Widget::init()
{
    ui->setupUi(this);
    // Voice notes play through the output of calls.
    AirVoicePlayer::instance().setAudio(&audio);

#ifndef Q_OS_MAC
    // macOS Dock uses CFBundle icns; setWindowIcon() with tray SVGs causes a white frame.
    QIcon themeIcon = QIcon::fromTheme("khandaq");
    if (!themeIcon.isNull()) {
        setWindowIcon(themeIcon);
    }
#endif

    timer = new QTimer();
    timer->start(1000);

    icon_size = 15;

    actionShow = new QAction(this);
    connect(actionShow, &QAction::triggered, this, &Widget::forceShow);

    // Preparing icons and set their size
    statusOnline = new QAction(this);
    statusOnline->setIcon(
        prepareIcon(Status::getIconPath(Status::Status::Online), icon_size, icon_size));
    connect(statusOnline, &QAction::triggered, this, &Widget::setStatusOnline);

    statusAway = new QAction(this);
    statusAway->setIcon(prepareIcon(Status::getIconPath(Status::Status::Away), icon_size, icon_size));
    connect(statusAway, &QAction::triggered, this, &Widget::setStatusAway);

    statusBusy = new QAction(this);
    statusBusy->setIcon(prepareIcon(Status::getIconPath(Status::Status::Busy), icon_size, icon_size));
    connect(statusBusy, &QAction::triggered, this, &Widget::setStatusBusy);

    actionLogout = new QAction(this);
    actionLogout->setIcon(prepareIcon(":/img/others/logout-icon.svg", icon_size, icon_size));

    actionQuit = new QAction(this);
#ifndef Q_OS_OSX
    actionQuit->setMenuRole(QAction::QuitRole);
#endif

    actionQuit->setIcon(
        prepareIcon(style.getImagePath("rejectCall/rejectCall.svg", settings), icon_size, icon_size));
    connect(actionQuit, &QAction::triggered, qApp, &QApplication::quit);

    layout()->setContentsMargins(0, 0, 0, 0);

    profilePicture = new MaskablePixmapWidget(this, QSize(40, 40), ":/img/avatar_mask.svg");
    profilePicture->setPixmap(QPixmap(":/img/contact_dark.svg"));
    profilePicture->setClickable(true);
    profilePicture->setObjectName("selfAvatar");
    ui->myProfile->insertWidget(0, profilePicture);
    ui->myProfile->insertSpacing(1, 7);

    filterMenu = new QMenu(this);
    filterGroup = new QActionGroup(this);
    filterDisplayGroup = new QActionGroup(this);

    filterDisplayName = new QAction(this);
    filterDisplayName->setCheckable(true);
    filterDisplayName->setChecked(true);
    filterDisplayGroup->addAction(filterDisplayName);
    filterMenu->addAction(filterDisplayName);
    filterDisplayActivity = new QAction(this);
    filterDisplayActivity->setCheckable(true);
    filterDisplayGroup->addAction(filterDisplayActivity);
    filterMenu->addAction(filterDisplayActivity);
    settings.getFriendSortingMode() == FriendListWidget::SortingMode::Name
        ? filterDisplayName->setChecked(true)
        : filterDisplayActivity->setChecked(true);
    filterMenu->addSeparator();

    filterAllAction = new QAction(this);
    filterAllAction->setCheckable(true);
    filterAllAction->setChecked(true);
    filterGroup->addAction(filterAllAction);
    filterMenu->addAction(filterAllAction);
    filterOnlineAction = new QAction(this);
    filterOnlineAction->setCheckable(true);
    filterGroup->addAction(filterOnlineAction);
    filterMenu->addAction(filterOnlineAction);
    filterOfflineAction = new QAction(this);
    filterOfflineAction->setCheckable(true);
    filterGroup->addAction(filterOfflineAction);
    filterMenu->addAction(filterOfflineAction);
    filterFriendsAction = new QAction(this);
    filterFriendsAction->setCheckable(true);
    filterGroup->addAction(filterFriendsAction);
    filterMenu->addAction(filterFriendsAction);
    filterGroupsAction = new QAction(this);
    filterGroupsAction->setCheckable(true);
    filterGroup->addAction(filterGroupsAction);
    filterMenu->addAction(filterGroupsAction);

    ui->searchContactFilterBox->setMenu(filterMenu);

    core = &profile.getCore();
    auto coreExt = core->getExt();

    sharedMessageProcessorParams.reset(new MessageProcessor::SharedParams(core->getMaxMessageSize(), coreExt->getMaxExtendedMessageSize()));
    groupMessageProcessorParams.reset(
        new MessageProcessor::SharedParams(Core::getMaxGroupMessageSize(),
                                           coreExt->getMaxExtendedMessageSize()));

    chatListWidget = new FriendListWidget(*core, this, settings, style,
        *messageBoxManager, *friendList, *groupList, profile, settings.getGroupchatPosition());
    connect(chatListWidget, &FriendListWidget::searchCircle, this, &Widget::searchCircle);
    connect(chatListWidget, &FriendListWidget::connectCircleWidget, this,
            &Widget::connectCircleWidget);
    ui->friendList->setWidget(chatListWidget);
    ui->friendList->setLayoutDirection(Qt::RightToLeft);
    ui->friendList->setContextMenuPolicy(Qt::CustomContextMenu);

    ui->statusLabel->setEditable(true);

    QMenu* statusButtonMenu = new QMenu(ui->statusButton);
    statusButtonMenu->addAction(statusOnline);
    statusButtonMenu->addAction(statusAway);
    statusButtonMenu->addAction(statusBusy);
    ui->statusButton->setMenu(statusButtonMenu);

    // disable proportional scaling
    ui->mainSplitter->setStretchFactor(0, 0);
    ui->mainSplitter->setStretchFactor(1, 1);

    onStatusSet(Status::Status::Offline);

    // Disable some widgets until we're connected to the DHT
    ui->statusButton->setEnabled(false);

    style.setThemeColor(settings, settings.getThemeColor());

    CoreFile* coreFile = core->getCoreFile();
    filesForm = new FilesForm(*coreFile, settings, style, *messageBoxManager, *friendList);
    addFriendForm = new AddFriendForm(core->getSelfId(), settings, style,
        *messageBoxManager, *core);
    groupInviteForm = new GroupInviteForm(settings, *core);
    setupAir();

#if UPDATE_CHECK_ENABLED
    updateCheck = std::unique_ptr<UpdateCheck>(new UpdateCheck(settings));
    connect(updateCheck.get(), &UpdateCheck::updateAvailable, this, &Widget::onUpdateAvailable);
#endif
    profileInfo = new ProfileInfo(core, &profile, settings, nexus);
    youPage = new AirYouPage(*profileInfo);
    // Your photo as the "you" block has it: your initials on the ink tile while you have none.
    youPage->setPhoto(airSource->selfAvatar());
    connect(airSource, &AirSource::selfAvatarChanged, youPage,
            [this] { youPage->setPhoto(airSource->selfAvatar()); });

    // Made before the check runs, so that an update it finds reaches About.
    settingsScreen();
#if UPDATE_CHECK_ENABLED
    updateCheck->checkForUpdate();
#endif

    notificationGenerator.reset(new NotificationGenerator(settings, &profile));
#if DESKTOP_NOTIFICATIONS
    connect(&notifier, &DesktopNotify::notificationClosed, notificationGenerator.get(),
            &NotificationGenerator::onNotificationActivated);
#else
    connect(&notifier, &TrayNotify::notificationClosed, notificationGenerator.get(),
            &NotificationGenerator::onNotificationActivated);
    // A click on the notification brings the window forward, as a click on the icon does.
    connect(&notifier, &TrayNotify::activated, this, &Widget::forceShow);
#endif

    // connect logout tray menu action
    connect(actionLogout, &QAction::triggered, this, &Widget::askLogout);


    connect(coreFile, &CoreFile::fileReceiveRequested, this, &Widget::onFileReceiveRequested);
    connect(ui->addButton, &QPushButton::clicked, this, [this] { openNewContact(QString()); });
    connect(ui->groupButton, &QPushButton::clicked, this, &Widget::onGroupClicked);
    connect(ui->transferButton, &QPushButton::clicked, this, &Widget::onTransferClicked);
    connect(ui->settingsButton, &QPushButton::clicked, this, &Widget::onShowSettings);

    // KHANDAQ (Figma): Чаты / Группы / Избранные segmented tabs reuse the existing, tested chat-list
    // filter actions (filterGroup → searchChats), so no new filtering logic is introduced.
    // Избранные has no backing model yet → maps to the friends/contacts filter as a placeholder.
    connect(ui->chatTabChats, &QPushButton::clicked, this, [this]() { filterAllAction->trigger(); });
    connect(ui->chatTabGroups, &QPushButton::clicked, this, [this]() { filterGroupsAction->trigger(); });
    connect(ui->chatTabFavorites, &QPushButton::clicked, this, [this]() { filterFriendsAction->trigger(); });
    connect(profilePicture, &MaskablePixmapWidget::clicked, this, &Widget::showProfile);
    connect(ui->nameLabel, &CroppingLabel::clicked, this, &Widget::showProfile);
    connect(ui->statusLabel, &CroppingLabel::editFinished, this, &Widget::onStatusMessageChanged);
    connect(ui->mainSplitter, &QSplitter::splitterMoved, this, &Widget::onSplitterMoved);
    connect(addFriendForm, &AddFriendForm::friendRequested, this, &Widget::friendRequested);
    connect(groupInviteForm, &GroupInviteForm::groupCreate, this, [this]() { openNewGroup(); });
    connect(timer, &QTimer::timeout, this, &Widget::onUserAwayCheck);
    connect(timer, &QTimer::timeout, this, &Widget::onEventIconTick);
    connect(timer, &QTimer::timeout, this, &Widget::onTryCreateTrayIcon);
    connect(ui->searchContactText, &QLineEdit::textChanged, this, &Widget::searchChats);
    connect(filterGroup, &QActionGroup::triggered, this, &Widget::searchChats);
    connect(filterDisplayGroup, &QActionGroup::triggered, this, &Widget::changeDisplayMode);
    connect(ui->friendList, &QWidget::customContextMenuRequested, this, &Widget::friendListContextMenu);

    // NOTE: Order of these signals as well as the use of QueuedConnection is important!
    // Qt::AutoConnection, signals emitted from the same thread as Widget will
    // be serviced before other signals. This is a problem when we have tight
    // calls between file control and file info callbacks.
    //
    // File info callbacks are called from the core thread and will use
    // QueuedConnection by default, our control path can easily end up on the
    // same thread as widget. This can result in the following behavior if we
    // are not careful
    //
    // * File data is received
    // * User presses pause at the same time
    // * Pause waits for data receive callback to complete (and emit fileTransferInfo)
    // * Pause is executed and emits fileTransferPaused
    // * Pause signal is handled by Qt::DirectConnection
    // * fileTransferInfo signal is handled after by Qt::QueuedConnection
    //
    // This results in stale file state! In these conditions if we are not
    // careful toxcore will think we are paused but our UI will think we are
    // resumed, because the last signal they got was a transmitting file info
    // signal!
    connect(coreFile, &CoreFile::fileTransferInfo, this, &Widget::dispatchFile, Qt::QueuedConnection);
    connect(coreFile, &CoreFile::fileSendStarted, this, &Widget::dispatchFile, Qt::QueuedConnection);
    connect(coreFile, &CoreFile::fileReceiveRequested, this, &Widget::dispatchFile, Qt::QueuedConnection);
    connect(coreFile, &CoreFile::fileTransferAccepted, this, &Widget::dispatchFile, Qt::QueuedConnection);
    connect(coreFile, &CoreFile::fileTransferCancelled, this, &Widget::dispatchFile, Qt::QueuedConnection);
    connect(coreFile, &CoreFile::fileTransferFinished, this, &Widget::dispatchFile, Qt::QueuedConnection);
    connect(coreFile, &CoreFile::fileTransferPaused, this, &Widget::dispatchFile, Qt::QueuedConnection);
    connect(coreFile, &CoreFile::fileTransferRemotePausedUnpaused, this, &Widget::dispatchFileWithBool, Qt::QueuedConnection);
    connect(coreFile, &CoreFile::fileTransferBrokenUnbroken, this, &Widget::dispatchFileWithBool, Qt::QueuedConnection);
    connect(coreFile, &CoreFile::fileSendFailed, this, &Widget::dispatchFileSendFailed, Qt::QueuedConnection);
    // NOTE: We intentionally do not connect the fileUploadFinished and fileDownloadFinished signals
    // because they are duplicates of fileTransferFinished NOTE: We don't hook up the
    // fileNameChanged signal since it is only emitted before a fileReceiveRequest. We get the
    // initial request with the sanitized name so there is no work for us to do

    // keyboard shortcuts
    auto* const quitShortcut = new QShortcut(Qt::CTRL + Qt::Key_Q, this);
    connect(quitShortcut, &QShortcut::activated, qApp, &QApplication::quit);
    new QShortcut(Qt::CTRL + Qt::SHIFT + Qt::Key_Tab, this, SLOT(previousChat()));
    new QShortcut(Qt::CTRL + Qt::Key_Tab, this, SLOT(nextChat()));
    new QShortcut(Qt::CTRL + Qt::Key_PageUp, this, SLOT(previousChat()));
    new QShortcut(Qt::CTRL + Qt::Key_PageDown, this, SLOT(nextChat()));
    new QShortcut(Qt::Key_F11, this, SLOT(toggleFullscreen()));

#ifdef Q_OS_MAC
    QMenuBar* globalMenu = nexus.globalMenuBar;
    QAction* windowMenu = nexus.windowMenu->menuAction();
    QAction* viewMenu = nexus.viewMenu->menuAction();
    QAction* frontAction = nexus.frontAction;

    fileMenu = globalMenu->insertMenu(viewMenu, new QMenu(this));

    editProfileAction = fileMenu->menu()->addAction(QString());
    connect(editProfileAction, &QAction::triggered, this, &Widget::showProfile);

    changeStatusMenu = fileMenu->menu()->addMenu(QString());
    fileMenu->menu()->addAction(changeStatusMenu->menuAction());
    changeStatusMenu->addAction(statusOnline);
    changeStatusMenu->addSeparator();
    changeStatusMenu->addAction(statusAway);
    changeStatusMenu->addAction(statusBusy);

    fileMenu->menu()->addSeparator();
    logoutAction = fileMenu->menu()->addAction(QString());
    connect(logoutAction, &QAction::triggered, this, &Widget::askLogout);

    editMenu = globalMenu->insertMenu(viewMenu, new QMenu(this));
    editMenu->menu()->addSeparator();

    // qTox's Filter submenu stays out of View: it sorted and filtered the old list, which the
    // window no longer shows, so its "Friends" and "By Activity" changed nothing anyone sees.

    contactMenu = globalMenu->insertMenu(windowMenu, new QMenu(this));

    addContactAction = contactMenu->menu()->addAction(QString());
    connect(addContactAction, &QAction::triggered, this, [this] { openNewContact(QString()); });

    nextConversationAction = new QAction(this);
    nexus.windowMenu->insertAction(frontAction, nextConversationAction);
    nextConversationAction->setShortcut(QKeySequence::SelectNextPage);
    connect(nextConversationAction, &QAction::triggered, [this]() {
        if (contentDialogManager->current() == QApplication::activeWindow())
            contentDialogManager->current()->cycleChats(true);
        else if (QApplication::activeWindow() == this)
            cycleChats(true);
    });

    previousConversationAction = new QAction(this);
    nexus.windowMenu->insertAction(frontAction, previousConversationAction);
    previousConversationAction->setShortcut(QKeySequence::SelectPreviousPage);
    connect(previousConversationAction, &QAction::triggered, [this] {
        if (contentDialogManager->current() == QApplication::activeWindow())
            contentDialogManager->current()->cycleChats(false);
        else if (QApplication::activeWindow() == this)
            cycleChats(false);
    });

    windowMenu->menu()->insertSeparator(frontAction);

    QAction* preferencesAction = viewMenu->menu()->addAction(QString());
    preferencesAction->setMenuRole(QAction::PreferencesRole);
    connect(preferencesAction, &QAction::triggered, this, &Widget::onShowSettings);

    QAction* aboutAction = viewMenu->menu()->addAction(QString());
    aboutAction->setMenuRole(QAction::AboutRole);
    connect(aboutAction, &QAction::triggered, [this]() {
        onShowSettings();
        settingsScreen().showSection(AirSettingsPage::Section::About);
    });

    QMenu* dockChangeStatusMenu = new QMenu(tr("Status"), this);
    dockChangeStatusMenu->addAction(statusOnline);
    statusOnline->setIconVisibleInMenu(true);
    dockChangeStatusMenu->addSeparator();
    dockChangeStatusMenu->addAction(statusAway);
    dockChangeStatusMenu->addAction(statusBusy);
    nexus.dockMenu->addAction(dockChangeStatusMenu->menuAction());

    connect(this, &Widget::windowStateChanged, &nexus, &Nexus::onWindowStateChanged);
#endif

    contentLayout = nullptr;
    onSeparateWindowChanged(settings.getSeparateWindow(), false);

    ui->addButton->setCheckable(true);
    ui->groupButton->setCheckable(true);
    ui->transferButton->setCheckable(true);
    ui->settingsButton->setCheckable(true);

    if (contentLayout) {
        showNoChat();
    }

    // restore window state
    restoreGeometry(settings.getWindowGeometry());
    restoreState(settings.getWindowState());
    SplitterRestorer restorer(ui->mainSplitter);
    restorer.restore(settings.getSplitterState(), size());
    // The saved state and the restorer bring back the style's handle width; Air draws a hairline.
    ui->mainSplitter->setHandleWidth(static_cast<int>(AirTokens::Stroke::hairline));

    friendRequestsButton = nullptr;
    groupInvitesButton = nullptr;
    unreadGroupInvites = 0;

    connect(addFriendForm, &AddFriendForm::friendRequested, this, &Widget::friendRequestsUpdate);
    connect(addFriendForm, &AddFriendForm::friendRequestsSeen, this, &Widget::friendRequestsUpdate);
    connect(addFriendForm, &AddFriendForm::friendRequestAccepted, this, &Widget::friendRequestAccepted);
    connect(groupInviteForm, &GroupInviteForm::groupInvitesSeen, this, &Widget::groupInvitesClear);
    connect(groupInviteForm, &GroupInviteForm::groupInviteAccepted, this,
            &Widget::onGroupInviteAccepted);
    connect(groupInviteForm, &GroupInviteForm::groupJoinFailed, this, &Widget::onGroupJoinFailed);
    connect(groupInviteForm, &GroupInviteForm::groupJoinSucceeded, this, &Widget::onGroupJoinSucceeded);

    // settings
    connect(&settings, &Settings::showSystemTrayChanged, this, &Widget::onSetShowSystemTray);
    connect(&settings, &Settings::separateWindowChanged, this, &Widget::onSeparateWindowClicked);
    connect(&settings, &Settings::compactLayoutChanged, chatListWidget,
            &FriendListWidget::onCompactChanged);
    connect(&settings, &Settings::groupchatPositionChanged, chatListWidget,
            &FriendListWidget::onGroupchatPositionChanged);

    connect(&style, &Style::themeReload, this, &Widget::reloadTheme);

    reloadTheme();
    updateIcons();
    retranslateUi();
    Translator::registerHandler(std::bind(&Widget::retranslateUi, this), this);

    // Behind the start window the window waits for it to give way (showFromStart).
    if (!settings.getShowSystemTray() && !heldByStart) {
        show();
    }

#ifdef Q_OS_MAC
    nexus.updateWindows();
#endif
}

bool Widget::eventFilter(QObject* obj, QEvent* event)
{
    QWindowStateChangeEvent* ce = nullptr;
    Qt::WindowStates state = windowState();

    switch (event->type()) {
    case QEvent::Close:
        // It's needed if user enable `Close to tray`
        wasMaximized = state.testFlag(Qt::WindowMaximized);
        break;

    case QEvent::WindowStateChange:
        ce = static_cast<QWindowStateChangeEvent*>(event);
        if (state.testFlag(Qt::WindowMinimized) && obj) {
            wasMaximized = ce->oldState().testFlag(Qt::WindowMaximized);
        }

#ifdef Q_OS_MAC
        emit windowStateChanged(windowState());
#endif
        break;
    case QEvent::LayoutRequest:
        // A pill came or went in the Air band, or its words changed language.
        if (obj == airBand) {
            refreshLeastSize();
        }
        break;
    default:
        break;
    }

    return false;
}

void Widget::updateIcons()
{
    if (!icon) {
        return;
    }

    const QString assetSuffix = Status::getAssetSuffix(static_cast<Status::Status>(
                                    ui->statusButton->property("status").toInt()))
                                + (eventIcon ? QStringLiteral("_event") : QString());

    // Some builds of Qt appear to have a bug in icon loading:
    // QIcon::hasThemeIcon is sometimes unaware that the icon returned
    // from QIcon::fromTheme was a fallback icon, causing hasThemeIcon to
    // incorrectly return true.
    //
    // In qTox this leads to the tray and window icons using the static qTox logo
    // icon instead of an icon based on the current presence status.
    //
    // This workaround checks for an icon that definitely does not exist to
    // determine if hasThemeIcon can be trusted.
    //
    // On systems with the Qt bug, this workaround will always use our included
    // icons but user themes will be unable to override them.
    static bool checkedHasThemeIcon = false;
    static bool hasThemeIconBug = false;

    if (!checkedHasThemeIcon) {
        hasThemeIconBug = QIcon::hasThemeIcon("khandaq-asjkdfhawjkeghdfjgh");
        checkedHasThemeIcon = true;

        if (hasThemeIconBug) {
            qDebug()
                << "Detected buggy QIcon::hasThemeIcon. Icon overrides from theme will be ignored.";
        }
    }

    QIcon ico;
    if (!hasThemeIconBug && QIcon::hasThemeIcon("khandaq-" + assetSuffix)) {
        ico = QIcon::fromTheme("khandaq-" + assetSuffix);
    } else {
        QString color = settings.getLightTrayIcon() ? QStringLiteral("light") : QStringLiteral("dark");
        QString path = ":/img/taskbar/" + color + "/taskbar_" + assetSuffix + ".svg";
        QSvgRenderer renderer(path);

        ico = QIcon();
        const QList<int> trayIconSizes = {16, 20, 24, 32, 48, 64};
        for (const int size : trayIconSizes) {
            QImage image(size, size, QImage::Format_ARGB32_Premultiplied);
            image.fill(Qt::transparent);
            QPainter painter(&image);
            renderer.render(&painter);
            painter.end();
            ico.addPixmap(QPixmap::fromImage(image));
        }
    }

    if (icon) {
        icon->setIcon(ico);
    }
#ifndef Q_OS_MAC
    setWindowIcon(ico);
#endif
}

Widget::~Widget()
{
    // The output belongs to the session: a note that plays stops with it.
    AirVoicePlayer::instance().setAudio(nullptr);
    ipc.unregisterEventHandler(activateHandlerKey);
    // No window, no New contact: while logged out a link stays with the instance it was given to.
    ipc.unregisterEventHandler(uriHandlerKey);

    QWidgetList windowList = QApplication::topLevelWidgets();

    for (QWidget* window : windowList) {
        if (window != this) {
            window->close();
        }
    }

    Translator::unregister(this);
    if (icon) {
        icon->hide();
    }

    for (Group* g : groupList->getAllGroups()) {
        removeGroup(g, true);
    }

    for (Friend* f : friendList->getAllFriends()) {
        removeFriend(f, true);
    }

    for (auto form : chatForms) {
        delete form;
    }
    delete notesForm;
    notesForm = nullptr;

    delete youPage;
    // The page holds profileInfo, so it goes first.
    delete settingsPage.data();
    delete profileInfo;
    delete addFriendForm;
    delete groupInviteForm;
    delete filesForm;
    delete timer;
    delete contentLayout;
    // Out of the content column by now, like the forms above.
    delete noChatView.data();

    friendList->clear();
    groupList->clear();
    delete trayMenu;
    delete ui;
    instance = nullptr;
}

/**
 * @brief Switches to the About settings page.
 */
void Widget::showUpdateDownloadProgress()
{
    onShowSettings();
    settingsScreen().showSection(AirSettingsPage::Section::About);
}

void Widget::moveEvent(QMoveEvent* event)
{
    if (event->type() == QEvent::Move && !isFullScreen() && !isTogglingFullscreen_) {
        saveWindowGeometry();
        saveSplitterGeometry();
    }

    QWidget::moveEvent(event);
}

void Widget::closeEvent(QCloseEvent* event)
{
    if (settings.getShowSystemTray() && settings.getCloseToTray()) {
        QWidget::closeEvent(event);
    } else {
        if (autoAwayActive) {
            emit statusSet(Status::Status::Online);
            autoAwayActive = false;
        }
        saveWindowGeometry();
        saveSplitterGeometry();
        QWidget::closeEvent(event);
        qApp->quit();
    }
}

void Widget::changeEvent(QEvent* event)
{
    if (event->type() == QEvent::WindowStateChange) {
        if (isMinimized() && settings.getShowSystemTray() && settings.getMinimizeToTray()) {
            hide();
        }
    }
}

void Widget::resizeEvent(QResizeEvent* event)
{
    if (!isFullScreen() && !isTogglingFullscreen_) {
        saveWindowGeometry();
    }
    QMainWindow::resizeEvent(event);
}

QString Widget::getUsername()
{
    return core->getUsername();
}

void Widget::onSelfAvatarLoaded(const QPixmap& pic)
{
    profilePicture->setPixmap(pic);
}

void Widget::onCoreChanged(Core& core_)
{
    core = &core_;
    connect(core, &Core::connected, this, &Widget::onConnected);
    connect(core, &Core::disconnected, this, &Widget::onDisconnected);
    connect(core, &Core::statusSet, this, &Widget::onStatusSet);
    connect(core, &Core::usernameSet, this, &Widget::setUsername);
    connect(core, &Core::statusMessageSet, this, &Widget::setStatusMessage);
    connect(core, &Core::friendAdded, this, &Widget::addFriend);
    connect(core, &Core::failedToAddFriend, this, &Widget::addFriendFailed);
    connect(core, &Core::friendUsernameChanged, this, &Widget::onFriendUsernameChanged);
    connect(core, &Core::friendStatusChanged, this, &Widget::onCoreFriendStatusChanged);
    connect(core, &Core::friendStatusMessageChanged, this, &Widget::onFriendStatusMessageChanged);
    connect(core, &Core::friendRequestReceived, this, &Widget::onFriendRequestReceived);
    connect(core, &Core::friendMessageReceived, this, &Widget::onFriendMessageReceived);
    connect(core, &Core::receiptRecieved, this, &Widget::onReceiptReceived);
    connect(core, &Core::groupInviteReceived, this, &Widget::onGroupInviteReceived);
    connect(core, &Core::groupMessageReceived, this, &Widget::onGroupMessageReceived);
    connect(core, &Core::friendMessageEffect, this, &Widget::onFriendMessageEffect);
    connect(core, &Core::groupMessageEffect, this, &Widget::onGroupMessageEffect);
    connect(core, &Core::groupFileReceived, this, &Widget::onGroupFileReceived);
    connect(core, &Core::groupFileSent, this, &Widget::onGroupFileSent);
    connect(core, &Core::groupPeerlistChanged, this, &Widget::onGroupPeerlistChanged);
    connect(core, &Core::groupPeerNameChanged, this, &Widget::onGroupPeerNameChanged);
    connect(core, &Core::groupTitleChanged, this, &Widget::onGroupTitleChanged);
    connect(core, &Core::groupPeerAudioPlaying, this, &Widget::onGroupPeerAudioPlaying);
    connect(core, &Core::emptyGroupCreated, this, &Widget::onEmptyGroupCreated);
    connect(core, &Core::groupJoined, this, &Widget::onGroupJoined);
    connect(core, &Core::groupPrivateMessageReceived, this, &Widget::onGroupPrivateMessageReceived);
    connect(core, &Core::friendTypingChanged, this, &Widget::onFriendTypingChanged);
    connect(core, &Core::groupSentFailed, this, &Widget::onGroupSendFailed);
    connect(core, &Core::usernameSet, this, &Widget::refreshPeerListsLocal);

    auto coreExt = core->getExt();

    connect(coreExt, &CoreExt::extendedMessageReceived, this, &Widget::onFriendExtMessageReceived);
    connect(coreExt, &CoreExt::extendedReceiptReceived, this, &Widget::onExtReceiptReceived);
    connect(coreExt, &CoreExt::extendedMessageSupport, this, &Widget::onExtendedMessageSupport);

    connect(this, &Widget::statusSet, core, &Core::setStatus);
    connect(this, &Widget::friendRequested, core, &Core::requestFriendship);
    connect(this, &Widget::friendRequestAccepted, core, &Core::acceptFriendRequest);
    connect(this, &Widget::changeGroupTitle, core, &Core::changeGroupTitle);

    sharedMessageProcessorParams->setPublicKey(core->getSelfPublicKey().toString());
    groupMessageProcessorParams->setPublicKey(core->getSelfPublicKey().toString());
}

void Widget::onConnected()
{
    ui->statusButton->setEnabled(true);
    emit core->statusSet(core->getStatus());
}

void Widget::onDisconnected()
{
    ui->statusButton->setEnabled(false);
    emit core->statusSet(Status::Status::Offline);
}

void Widget::onStatusSet(Status::Status status)
{
    ui->statusButton->setProperty("status", static_cast<int>(status));
    ui->statusButton->setIcon(prepareIcon(getIconPath(status), icon_size, icon_size));
    updateIcons();
}

void Widget::onSeparateWindowClicked(bool separate)
{
    onSeparateWindowChanged(separate, true);
}

void Widget::onSeparateWindowChanged(bool separate, bool clicked)
{
    if (!separate) {
        QWindowList windowList = QGuiApplication::topLevelWindows();

        for (QWindow* window : windowList) {
            if (window->objectName() == "detachedWindow") {
                window->close();
            }
        }

        QWidget* contentWidget = new QWidget(this);
        contentWidget->setObjectName("contentWidget");

        contentLayout = new ContentLayout(settings, style, contentWidget);
        ui->mainSplitter->addWidget(contentWidget);

        // The list column has the width of the design; the conversation takes the rest.
        ui->statusPanel->setFixedWidth(AirTokens::Size::listColumn);
        refreshLeastSize();

        SplitterRestorer restorer(ui->mainSplitter);
        restorer.restore(settings.getSplitterState(), size());
        ui->mainSplitter->setHandleWidth(static_cast<int>(AirTokens::Stroke::hairline));

        onShowSettings();
    } else {
        int width = ui->friendList->size().width();
        QSize size;
        QPoint pos;

        if (contentLayout) {
            pos = mapToGlobal(ui->mainSplitter->widget(1)->pos());
            size = ui->mainSplitter->widget(1)->size();
        }

        if (contentLayout) {
            contentLayout->clear();
            contentLayout->parentWidget()->setParent(nullptr); // Remove from splitter.
            contentLayout->parentWidget()->hide();
            contentLayout->parentWidget()->deleteLater();
            contentLayout->deleteLater();
            contentLayout = nullptr;
        }

        // Only the list is left in this window: it takes all of it.
        ui->statusPanel->setMinimumWidth(AirTokens::Size::listColumn);
        ui->statusPanel->setMaximumWidth(QWIDGETSIZE_MAX);
        refreshLeastSize();

        if (clicked) {
            showNormal();
            resize(width, height());

            ContentLayout* contentLayout_ = createContentDialog((DialogType::SettingDialog));
            contentLayout_->parentWidget()->resize(size);
            contentLayout_->parentWidget()->move(pos);
            settingsScreen().showIn(contentLayout_);
            setActiveToolMenuButton(ActiveToolMenuButton::None);
        }

        setWindowTitle(QString());
        setActiveToolMenuButton(ActiveToolMenuButton::None);
    }
}

void Widget::setWindowTitle(const QString& title)
{
    if (title.isEmpty()) {
        QMainWindow::setWindowTitle(QApplication::applicationName());
    } else {
        QString tmp = title;
        /// <[^>]*> Regexp to remove HTML tags, in case someone used them in title
        QMainWindow::setWindowTitle(tmp.remove(QRegExp("<[^>]*>")) + QStringLiteral(" - ")
                                    + QApplication::applicationName());
    }
}

void Widget::forceShow()
{
    if (heldByStart) {
        // The start window is in front and this one comes after it: the start window comes up.
        if (QWidget* const modal = QApplication::activeModalWidget()) {
            modal->raise();
            modal->activateWindow();
        }
        return;
    }
    hide(); // Workaround to force minimized window to be restored
    show();
    activateWindow();
}

void Widget::holdForStart()
{
    heldByStart = true;
}

QRect Widget::frameForStart(const QRect& startWindow) const
{
    const QScreen* screen = QGuiApplication::screenAt(startWindow.center());
    if (!screen) {
        screen = QGuiApplication::primaryScreen();
    }
    const QRect available = screen ? screen->availableGeometry() : startWindow;
    if (isFullScreen() && screen) {
        return screen->geometry();
    }
    if (isMaximized()) {
        return available;
    }
    // As the window was left, restored in init().
    if (!settings.getWindowGeometry().isEmpty()) {
        return geometry();
    }
    // The first time: room for the list and a conversation at its measure, nearly as tall as the
    // screen, in its middle.
    const QSize least = minimumSize().expandedTo(minimumSizeHint());
    const int width =
        qMax(least.width(),
             qMin(AirTokens::Size::listColumn + AirTokens::Size::textMeasure + AirTokens::Space::s64,
                  available.width() - 2 * AirTokens::Space::s32));
    const int height = qMax(least.height(), available.height() - 2 * AirTokens::Space::s64);
    QRect frame(0, 0, width, height);
    frame.moveCenter(available.center());
    return frame;
}

void Widget::showFromStart(const QRect& frame, bool addFirstPerson)
{
    heldByStart = false;
    openedFromStart = true;
    if (!isMaximized() && !isFullScreen()) {
        setGeometry(frame);
    }
    show();
    raise();
    // The start window is still modal for this turn of the loop: the window is made active, and
    // New contact takes the keyboard, once it has gone. The timer goes with the window.
    QTimer::singleShot(0, this, [this, addFirstPerson] {
        raise();
        activateWindow();
        if (addFirstPerson) {
            openNewContact(QString());
        }
#ifdef KHANDAQ_SCREENSHOT_HOOK
        if (!heldShotScript.isEmpty()) {
            const QString script = heldShotScript;
            heldShotScript.clear();
            runShotScript(script);
        }
#endif
    });
}

bool Widget::askImportProfile(const QString& path)
{
    forceShow();
    return AirSettingsPage::moveProfileIn(this, *profileInfo, path, false);
}

void Widget::onAddClicked()
{
    if (settings.getSeparateWindow()) {
        if (!addFriendForm->isShown()) {
            addFriendForm->show(createContentDialog(DialogType::AddDialog));
        }

        setActiveToolMenuButton(ActiveToolMenuButton::None);
    } else {
        hideMainForms(nullptr);
        addFriendForm->show(contentLayout);
        setWindowTitle(fromDialogType(DialogType::AddDialog));
        setActiveToolMenuButton(ActiveToolMenuButton::AddButton);
    }
}

void Widget::onGroupClicked()
{
    if (settings.getSeparateWindow()) {
        if (!groupInviteForm->isShown()) {
            groupInviteForm->show(createContentDialog(DialogType::GroupDialog));
        }

        setActiveToolMenuButton(ActiveToolMenuButton::None);
    } else {
        hideMainForms(nullptr);
        groupInviteForm->show(contentLayout);
        setWindowTitle(fromDialogType(DialogType::GroupDialog));
        setActiveToolMenuButton(ActiveToolMenuButton::GroupButton);
    }
}

void Widget::onTransferClicked()
{
    if (settings.getSeparateWindow()) {
        if (!filesForm->isShown()) {
            filesForm->show(createContentDialog(DialogType::TransferDialog));
        }

        setActiveToolMenuButton(ActiveToolMenuButton::None);
    } else {
        hideMainForms(nullptr);
        filesForm->show(contentLayout);
        setWindowTitle(fromDialogType(DialogType::TransferDialog));
        setActiveToolMenuButton(ActiveToolMenuButton::TransferButton);
    }
}

void Widget::onIconClick(QSystemTrayIcon::ActivationReason reason)
{
    if (heldByStart) {
        forceShow();
        return;
    }
    if (reason == QSystemTrayIcon::Trigger) {
        if (isHidden() || isMinimized()) {
            if (wasMaximized) {
                showMaximized();
            } else {
                showNormal();
            }

            activateWindow();
        } else if (!isActiveWindow()) {
            activateWindow();
        } else {
            wasMaximized = isMaximized();
            hide();
        }
    } else if (reason == QSystemTrayIcon::Unknown) {
        if (isHidden()) {
            forceShow();
        }
    }
}

void Widget::onShowSettings()
{
    AirSettingsPage& page = settingsScreen();
    if (settings.getSeparateWindow()) {
        if (!page.isShown()) {
            page.showIn(createContentDialog(DialogType::SettingDialog));
        }

        setActiveToolMenuButton(ActiveToolMenuButton::None);
    } else {
        hideMainForms(nullptr);
        page.showIn(contentLayout);
        setWindowTitle(fromDialogType(DialogType::SettingDialog));
        setActiveToolMenuButton(ActiveToolMenuButton::SettingButton);
    }
}

AirSettingsPage& Widget::settingsScreen()
{
    if (!settingsPage) {
        // The call devices keep the form of the old Audio and video tab, under Calls.
        auto* const callsForm =
            new AVForm(audio, core->getAv(), cameraSource, &settings, &settings, style);
        settingsPage = new AirSettingsPage(settings, profile, *profileInfo, *airLocal, callsForm,
                                           updateCheck.get());
        connect(settingsPage, &AirSettingsPage::trayIconChanged, this, &Widget::updateIcons);
        // Connect again goes to the thread the node runs on.
        const QPointer<Core> node(core);
        settingsPage->setNode(airSource, [node] {
            if (node) {
                QMetaObject::invokeMethod(node.data(), &Core::reconnect, Qt::QueuedConnection);
            }
        });
    }
    return *settingsPage;
}

void Widget::showProfile() // onAvatarClicked, onUsernameClicked
{
    if (settings.getSeparateWindow()) {
        if (!youPage->isShown()) {
            youPage->showIn(createContentDialog(DialogType::ProfileDialog));
        }

        setActiveToolMenuButton(ActiveToolMenuButton::None);
    } else {
        hideMainForms(nullptr);
        youPage->showIn(contentLayout);
        setWindowTitle(fromDialogType(DialogType::ProfileDialog));
        setActiveToolMenuButton(ActiveToolMenuButton::None);
    }
}

void Widget::askLogout()
{
    // The question belongs over the window, which the tray may have hidden.
    forceShow();
    showProfile();
    QTimer::singleShot(0, youPage, [this] { youPage->askLogout(); });
}

void Widget::hideMainForms(GenericChatroomWidget* chatroomWidget)
{
    setActiveToolMenuButton(ActiveToolMenuButton::None);

    if (contentLayout != nullptr) {
        contentLayout->clear();
    }

    if (activeChatroomWidget != nullptr) {
        activeChatroomWidget->setAsInactiveChatroom();
    }

    activeChatroomWidget = chatroomWidget;
    if (airController) {
        airController->setOpenChat(airEntryIdOf(chatroomWidget));
    }
}

void Widget::setUsername(const QString& username)
{
    if (username.isEmpty()) {
        ui->nameLabel->setText(tr("Your name"));
        ui->nameLabel->setToolTip(tr("Your name"));
    } else {
        ui->nameLabel->setText(username);
        ui->nameLabel->setToolTip(
            Qt::convertFromPlainText(username, Qt::WhiteSpaceNormal)); // for overlength names
    }

    sharedMessageProcessorParams->onUserNameSet(username);
    groupMessageProcessorParams->onUserNameSet(username);
    if (airController) {
        airController->setSelfName(username);
    }
}

void Widget::onStatusMessageChanged(const QString& newStatusMessage)
{
    // Keep old status message until Core tells us to set it.
    core->setStatusMessage(newStatusMessage);
}

void Widget::setStatusMessage(const QString& statusMessage)
{
    ui->statusLabel->setText(statusMessage);
    // escape HTML from tooltips and preserve newlines
    // TODO: move newspace preservance to a generic function
    ui->statusLabel->setToolTip("<p style='white-space:pre'>" + statusMessage.toHtmlEscaped() + "</p>");
}

/**
 * @brief Plays a sound via the audioNotification AudioSink
 * @param sound Sound to play
 * @param loop if true, loop the sound until onStopNotification() is called
 */
void Widget::playNotificationSound(IAudioSink::Sound sound, bool loop)
{
    if (!settings.getAudioOutDevEnabled()) {
        // don't try to play sounds if audio is disabled
        return;
    }

    if (audioNotification == nullptr) {
        audioNotification = std::unique_ptr<IAudioSink>(audio.makeSink());
        if (audioNotification == nullptr) {
            qDebug() << "Failed to allocate AudioSink";
            return;
        }
    }

    audioNotification->connectTo_finishedPlaying(this, [this](){ cleanupNotificationSound(); });

    audioNotification->playMono16Sound(sound);

    if (loop) {
        audioNotification->startLoop();
    }
}

void Widget::cleanupNotificationSound()
{
    audioNotification.reset();
}

void Widget::incomingNotification(uint32_t friendNum)
{
    const auto& friendId = friendList->id2Key(friendNum);
    newFriendMessageAlert(friendId, {}, false);

    // loop until call answered or rejected
    playNotificationSound(IAudioSink::Sound::IncomingCall, true);
}

void Widget::outgoingNotification()
{
    // loop until call answered or rejected
    playNotificationSound(IAudioSink::Sound::OutgoingCall, true);
}

void Widget::onCallEnd()
{
    playNotificationSound(IAudioSink::Sound::CallEnd);
}

/**
 * @brief Widget::onStopNotification Stop the notification sound.
 */
void Widget::onStopNotification()
{
    audioNotification.reset();
}

/**
 * @brief Dispatches file to the appropriate chatlog and accepts the transfer if necessary
 */
void Widget::dispatchFile(ToxFile file)
{
    const auto& friendId = friendList->id2Key(file.friendId);
    Friend* f = friendList->findFriend(friendId);
    if (!f) {
        return;
    }

    auto pk = f->getPublicKey();

    if (file.status == ToxFile::INITIALIZING && file.direction == ToxFile::RECEIVING) {
        // Download attachments (Settings, Network): Never leaves every file waiting for Accept,
        // except where a folder was chosen for this contact; Small files takes them up to the limit.
        const QString autoAcceptDir =
            Air::autoAcceptFolder(settings.getAutoAcceptDir(f->getPublicKey()),
                                  settings.getAutoSaveEnabled(), settings.getGlobalAutoAcceptDir(),
                                  QStandardPaths::writableLocation(QStandardPaths::DownloadLocation),
                                  static_cast<qint64>(settings.getMaxAutoAcceptSize()),
                                  static_cast<qint64>(file.progress.getFileSize()));
        if (!autoAcceptDir.isEmpty()) {
            acceptFileTransfer(file, autoAcceptDir);
        }
    }

    const auto senderPk = (file.direction == ToxFile::SENDING) ? core->getSelfPublicKey() : pk;
    friendChatLogs[pk]->onFileUpdated(senderPk, file);

    filesForm->onFileUpdated(file);
}

void Widget::dispatchFileWithBool(ToxFile file, bool pausedOrBroken)
{
    std::ignore = pausedOrBroken;
    dispatchFile(file);
}

void Widget::dispatchFileSendFailed(uint32_t friendId, const QString& fileName)
{
    const auto& friendPk = friendList->id2Key(friendId);

    auto chatForm = chatForms.find(friendPk);
    if (chatForm == chatForms.end()) {
        return;
    }

    chatForm.value()->addSystemInfoMessage(QDateTime::currentDateTime(),
                                           SystemMessageType::fileSendFailed, {fileName});
}

void Widget::onRejectCall(uint32_t friendId)
{
    CoreAV* const av = core->getAv();
    av->cancelCall(friendId);
}

void Widget::addFriend(uint32_t friendId, const ToxPk& friendPk)
{
    assert(core != nullptr);
    settings.updateFriendAddress(friendPk.toString());
    // Whichever way they became a contact, a request from them is answered now.
    // KHANDAQ (QA 2026-11-14, F2): and the contact keeps the time it came. Until they first
    // connect toxcore has no last-online time for them, which the list read as our own request
    // waiting for a reply; that they asked is known only here.
    if (airLocal) {
        airLocal->acceptRequest(friendPk);
    }
    if (airSource) {
        airSource->setRequestHidden(friendPk.toString(), false);
    }

    Friend* newfriend = friendList->addFriend(friendId, friendPk, settings);
    auto rawChatroom = new FriendChatroom(newfriend, contentDialogManager.get(), *core, settings, *groupList);
    std::shared_ptr<FriendChatroom> chatroom(rawChatroom);
    const auto compact = settings.getCompactLayout();
    auto widget = new FriendWidget(chatroom, compact, settings, style, *messageBoxManager, profile);
    connectFriendWidget(*widget);
    auto history = profile.getHistory();

    auto messageProcessor = MessageProcessor(*sharedMessageProcessorParams);
    auto friendMessageDispatcher =
        std::make_shared<FriendMessageDispatcher>(*newfriend, std::move(messageProcessor), *core, *core->getExt());

    // Note: We do not have to connect the message dispatcher signals since
    // ChatHistory hooks them up in a very specific order
    auto chatHistory =
        std::make_shared<ChatHistory>(*newfriend, history, *core, settings,
                                      *friendMessageDispatcher, *friendList, *groupList, airLocal);
    auto friendForm = new ChatForm(profile, newfriend, *chatHistory,
        *friendMessageDispatcher, *documentCache, *smileyPack, cameraSource,
        settings, style, *messageBoxManager, *contentDialogManager, *friendList,
        *groupList);
    connect(friendForm, &ChatForm::updateFriendActivity, this, &Widget::updateFriendActivity);

    friendMessageDispatchers[friendPk] = friendMessageDispatcher;
    friendChatLogs[friendPk] = chatHistory;
    friendChatrooms[friendPk] = chatroom;
    friendWidgets[friendPk] = widget;
    chatForms[friendPk] = friendForm;
    airSource->watchFriend(*newfriend, chatHistory);
    airLocal->watchFriend(*newfriend);
    friendForm->setAir(airSource, airLocal);
    // Queued: the dossier that asks lives in the form that the removal deletes.
    connect(
        friendForm, &ChatForm::removeFriendRequested, this,
        [this](const ToxPk& friendPk_) { removeConfirmedFriend(friendPk_); }, Qt::QueuedConnection);
    connect(
        friendForm, &ChatForm::blockFriendRequested, this,
        [this](const ToxPk& friendPk_) { blockFriend(friendPk_); }, Qt::QueuedConnection);

    const auto activityTime = settings.getFriendActivity(friendPk);
    const auto chatTime = friendForm->getLatestTime();
    if (chatTime > activityTime && chatTime.isValid()) {
        settings.setFriendActivity(friendPk, chatTime);
    }

    chatListWidget->addFriendWidget(widget);


    auto notifyReceivedCallback = [this, friendPk](const ToxPk& author, const Message& message) {
        std::ignore = author;
        // The words the transcript shows: a reply without its markup, a form this version cannot
        // read in a sentence.
        newFriendMessageAlert(friendPk, AirStrings::messageWords(message.content));
    };

    auto notifyReceivedConnection =
        connect(friendMessageDispatcher.get(), &IMessageDispatcher::messageReceived,
                notifyReceivedCallback);

    friendAlertConnections.insert(friendPk, notifyReceivedConnection);
    connect(newfriend, &Friend::aliasChanged, this, &Widget::onFriendAliasChanged);
    connect(newfriend, &Friend::displayedNameChanged, this, &Widget::onFriendDisplayedNameChanged);
    connect(newfriend, &Friend::statusChanged, this, &Widget::onFriendStatusChanged);

    connect(friendForm, &ChatForm::incomingNotification, this, &Widget::incomingNotification);
    connect(friendForm, &ChatForm::outgoingNotification, this, &Widget::outgoingNotification);
    connect(friendForm, &ChatForm::stopNotification, this, &Widget::onStopNotification);
    connect(friendForm, &ChatForm::endCallNotification, this, &Widget::onCallEnd);
    connect(friendForm, &ChatForm::rejectCall, this, &Widget::onRejectCall);

    connect(widget, &FriendWidget::newWindowOpened, this, &Widget::openNewDialog);
    connect(widget, &FriendWidget::chatroomWidgetClicked, this, &Widget::onChatroomWidgetClicked);
    connect(widget, &FriendWidget::chatroomWidgetClicked, friendForm, &ChatForm::focusInput);
    connect(widget, &FriendWidget::friendHistoryRemoved, friendForm, &ChatForm::clearChatArea);
    connect(widget, &FriendWidget::copyFriendIdToClipboard, this, &Widget::copyFriendIdToClipboard);
    connect(widget, &FriendWidget::newGroupRequested, this,
            [this](const ToxPk& contactPk) { openNewGroup(contactPk.toString()); });
    connect(widget, &FriendWidget::contextMenuCalled, widget, &FriendWidget::onContextMenuCalled);
    connect(widget, SIGNAL(removeFriend(const ToxPk&)), this, SLOT(removeFriend(const ToxPk&)));

    connect(&profile, &Profile::friendAvatarSet, widget, &FriendWidget::onAvatarSet);
    connect(&profile, &Profile::friendAvatarRemoved, widget, &FriendWidget::onAvatarRemoved);

    // Try to get the avatar from the cache
    QPixmap avatar = profile.loadAvatar(friendPk);
    if (!avatar.isNull()) {
        friendForm->onAvatarChanged(friendPk, avatar);
        widget->onAvatarSet(friendPk, avatar);
    }

    const QString username = core->getFriendUsername(friendId);
    if (!username.isEmpty()) {
        onFriendUsernameChanged(friendId, username);
    }
}

void Widget::addFriendFailed(const ToxPk& userId, const QString& errorInfo)
{
    // A request from New contact is answered there, in its words (E21).
    if (newContact && newContact->awaitedKey() == userId.toString()) {
        newContact->requestFailed(userId.toString());
        return;
    }
    using AirStrings::Text;
    // Accept on a request card: the card comes back and the window says it did not work.
    if (airLocal && airLocal->hasRequest(userId)) {
        airSource->setRequestHidden(userId.toString(), false);
        AirDialog::notice(this, AirStrings::text(Text::ErrorAddContactTitle),
                          AirStrings::text(Text::RequestAcceptFailed));
        return;
    }
    // A request from elsewhere (a tox: link, a member of a group, a list of IDs) is answered over
    // the window, the operation as the title and Tox's reason under it.
    AirDialog::notice(this, AirStrings::text(Text::ErrorAddContactTitle),
                      errorInfo.isEmpty() ? AirStrings::text(Text::TryAgain) : errorInfo);
}

void Widget::onCoreFriendStatusChanged(int friendId, Status::Status status)
{
    const auto& friendPk = friendList->id2Key(friendId);
    Friend* f = friendList->findFriend(friendPk);
    if (!f) {
        return;
    }

    auto const oldStatus = f->getStatus();
    f->setStatus(status);
    auto const newStatus = f->getStatus();

    auto const startedNegotiating = (newStatus == Status::Status::Negotiating && oldStatus != newStatus);
    if (startedNegotiating) {
        constexpr auto negotiationTimeoutMs = 1000;
        auto negotiateTimer = std::unique_ptr<QTimer>(new QTimer);
        negotiateTimer->setSingleShot(true);
        negotiateTimer->setInterval(negotiationTimeoutMs);
        connect(negotiateTimer.get(), &QTimer::timeout, f, &Friend::onNegotiationComplete);
        negotiateTimer->start();
        negotiateTimers[friendPk] = std::move(negotiateTimer);
    }

    // Any widget behavior will be triggered based off of the status
    // transformations done by the Friend class
}

void Widget::onFriendStatusChanged(const ToxPk& friendPk, Status::Status status)
{
    FriendWidget* widget = friendWidgets[friendPk];

    if (Status::isOnline(status)) {
        chatListWidget->moveWidget(widget, Status::Status::Online);
    } else {
        chatListWidget->moveWidget(widget, Status::Status::Offline);
    }

    widget->updateStatusLight();
    if (widget->isActive()) {
        setWindowTitle(widget->getTitle());
    }

    contentDialogManager->updateFriendStatus(friendPk);
    // Only contacts online now can be invited: the list of New group follows them.
    if (newGroup) {
        newGroup->refreshCandidates();
    }
}

void Widget::onFriendStatusMessageChanged(int friendId, const QString& message)
{
    const auto& friendPk = friendList->id2Key(friendId);
    Friend* f = friendList->findFriend(friendPk);
    if (!f) {
        return;
    }

    QString str = message;
    str.replace('\n', ' ').remove('\r').remove(QChar('\0'));
    f->setStatusMessage(str);

    friendWidgets[friendPk]->setStatusMsg(str);
    chatForms[friendPk]->setStatusMessage(str);
}

void Widget::onFriendDisplayedNameChanged(const QString& displayed)
{
    Friend* f = qobject_cast<Friend*>(sender());
    const auto& friendPk = f->getPublicKey();
    for (Group* g : groupList->getAllGroups()) {
        if (g->getPeerList().contains(friendPk)) {
            g->updateUsername(friendPk, displayed);
        }
    }

    FriendWidget* friendWidget = friendWidgets[f->getPublicKey()];
    // setWindowTitle adds the name of the app; adding it here too gave "X - Khandaq - Khandaq".
    if (friendWidget->isActive()) {
        setWindowTitle(displayed);
    }

    chatListWidget->itemsChanged();
}

void Widget::onFriendUsernameChanged(int friendId, const QString& username)
{
    const auto& friendPk = friendList->id2Key(friendId);
    Friend* f = friendList->findFriend(friendPk);
    if (!f) {
        return;
    }

    QString str = username;
    str.replace('\n', ' ').remove('\r').remove(QChar('\0'));
    f->setName(str);
}

void Widget::onFriendAliasChanged(const ToxPk& friendId, const QString& alias)
{
    settings.setFriendAlias(friendId, alias);
    settings.savePersonal();
}

void Widget::onChatroomWidgetClicked(GenericChatroomWidget* widget)
{
    openDialog(widget, /* newWindow = */ false);
}

void Widget::openNewDialog(GenericChatroomWidget* widget)
{
    openDialog(widget, /* newWindow = */ true);
}

void Widget::openDialog(GenericChatroomWidget* widget, bool newWindow)
{
    widget->resetEventFlags();
    widget->updateStatusLight();

    GenericChatForm* form;
    const Friend* frnd = widget->getFriend();
    const Group* group = widget->getGroup();
    bool chatFormIsSet;
    if (frnd) {
        form = chatForms[frnd->getPublicKey()];
        contentDialogManager->focusChat(frnd->getPersistentId());
        chatFormIsSet = contentDialogManager->chatWidgetExists(frnd->getPersistentId());
    } else {
        form = groupChatForms[group->getPersistentId()].data();
        contentDialogManager->focusChat(group->getPersistentId());
        chatFormIsSet = contentDialogManager->chatWidgetExists(group->getPersistentId());
    }

    if ((chatFormIsSet || form->isVisible()) && !newWindow) {
        return;
    }

    if (settings.getSeparateWindow() || newWindow) {
        ContentDialog* dialog = nullptr;

        if (!settings.getDontGroupWindows() && !newWindow) {
            dialog = contentDialogManager->current();
        }

        if (dialog == nullptr) {
            dialog = createContentDialog();
        }

        dialog->show();

        if (frnd) {
            addFriendDialog(frnd, dialog);
        } else {
            addGroupDialog(group, dialog);
        }

        dialog->raise();
        dialog->activateWindow();
    } else {
        hideMainForms(widget);
        if (frnd) {
            chatForms[frnd->getPublicKey()]->show(contentLayout);
        } else {
            groupChatForms[group->getPersistentId()]->show(contentLayout);
        }
        widget->setAsActiveChatroom();
        setWindowTitle(widget->getTitle());
    }
}

void Widget::onFriendMessageReceived(uint32_t friendnumber, const QString& message, bool isAction)
{
    const auto& friendId = friendList->id2Key(friendnumber);
    Friend* f = friendList->findFriend(friendId);
    if (!f) {
        return;
    }

    const auto dispatcher = friendMessageDispatchers.value(f->getPublicKey());
    if (dispatcher) {
        dispatcher->onMessageReceived(isAction, message);
    }
}

void Widget::onReceiptReceived(int friendId, ReceiptNum receipt)
{
    const auto& friendKey = friendList->id2Key(friendId);
    Friend* f = friendList->findFriend(friendKey);
    if (!f) {
        return;
    }

    const auto dispatcher = friendMessageDispatchers.value(f->getPublicKey());
    if (dispatcher) {
        dispatcher->onReceiptReceived(receipt);
    }
}

void Widget::onExtendedMessageSupport(uint32_t friendNumber, bool supported)
{
    const auto& friendKey = friendList->id2Key(friendNumber);
    Friend* f = friendList->findFriend(friendKey);
    if (!f) {
        return;
    }

    f->setExtendedMessageSupport(supported);
}

void Widget::onFriendExtMessageReceived(uint32_t friendNumber, const QString& message)
{
    const auto& friendKey = friendList->id2Key(friendNumber);
    const auto dispatcher = friendMessageDispatchers.value(friendKey);
    if (dispatcher) {
        dispatcher->onExtMessageReceived(message);
    }
}

void Widget::onExtReceiptReceived(uint32_t friendNumber, uint64_t receiptId)
{
    const auto& friendKey = friendList->id2Key(friendNumber);
    const auto dispatcher = friendMessageDispatchers.value(friendKey);
    if (dispatcher) {
        dispatcher->onExtReceiptReceived(receiptId);
    }
}

void Widget::addFriendDialog(const Friend* frnd, ContentDialog* dialog)
{
    const ToxPk& friendPk = frnd->getPublicKey();
    ContentDialog* contentDialog = contentDialogManager->getFriendDialog(friendPk);
    bool isSeparate = settings.getSeparateWindow();
    FriendWidget* widget = friendWidgets[friendPk];
    bool isCurrent = activeChatroomWidget == widget;
    if (!contentDialog && !isSeparate && isCurrent) {
        showNoChat();
    }

    auto form = chatForms[friendPk];
    auto chatroom = friendChatrooms[friendPk];
    FriendWidget* friendWidget =
        contentDialogManager->addFriendToDialog(dialog, chatroom, form);

    friendWidget->setStatusMsg(widget->getStatusMsg());

#if (QT_VERSION >= QT_VERSION_CHECK(5, 7, 0))
    auto widgetRemoveFriend = QOverload<const ToxPk&>::of(&Widget::removeFriend);
#else
    auto widgetRemoveFriend = static_cast<void (Widget::*)(const ToxPk&)>(&Widget::removeFriend);
#endif
    connect(friendWidget, &FriendWidget::removeFriend, this, widgetRemoveFriend);
    connect(friendWidget, &FriendWidget::middleMouseClicked, dialog,
            [=]() { dialog->removeFriend(friendPk); });
    connect(friendWidget, &FriendWidget::copyFriendIdToClipboard, this,
            &Widget::copyFriendIdToClipboard);
    connect(friendWidget, &FriendWidget::newWindowOpened, this, &Widget::openNewDialog);

    // Signal transmission from the created `friendWidget` (which shown in
    // ContentDialog) to the `widget` (which shown in main widget)
    // FIXME: emit should be removed
    connect(friendWidget, &FriendWidget::contextMenuCalled, widget,
            [=](QContextMenuEvent* event) { emit widget->contextMenuCalled(event); });

    connect(friendWidget, &FriendWidget::chatroomWidgetClicked, [=](GenericChatroomWidget* w) {
        std::ignore = w;
        emit widget->chatroomWidgetClicked(widget);
    });
    connect(friendWidget, &FriendWidget::newWindowOpened, [=](GenericChatroomWidget* w) {
        std::ignore = w;
        emit widget->newWindowOpened(widget);
    });
    // FIXME: emit should be removed
    emit widget->chatroomWidgetClicked(widget);

    connect(&profile, &Profile::friendAvatarSet, friendWidget, &FriendWidget::onAvatarSet);
    connect(&profile, &Profile::friendAvatarRemoved, friendWidget, &FriendWidget::onAvatarRemoved);

    QPixmap avatar = profile.loadAvatar(frnd->getPublicKey());
    if (!avatar.isNull()) {
        friendWidget->onAvatarSet(frnd->getPublicKey(), avatar);
    }
}

void Widget::addGroupDialog(const Group* group, ContentDialog* dialog)
{
    const GroupId& groupId = group->getPersistentId();
    ContentDialog* groupDialog = contentDialogManager->getGroupDialog(groupId);
    bool separated = settings.getSeparateWindow();
    GroupWidget* widget = groupWidgets[groupId];
    bool isCurrentWindow = activeChatroomWidget == widget;
    if (!groupDialog && !separated && isCurrentWindow) {
        showNoChat();
    }

    auto chatForm = groupChatForms[groupId].data();
    auto chatroom = groupChatrooms[groupId];
    auto groupWidget =
        contentDialogManager->addGroupToDialog(dialog, chatroom, chatForm);

#if (QT_VERSION >= QT_VERSION_CHECK(5, 7, 0))
    auto removeGroup = QOverload<const GroupId&>::of(&Widget::removeGroup);
#else
    auto removeGroup = static_cast<void (Widget::*)(const GroupId&)>(&Widget::removeGroup);
#endif
    connect(groupWidget, &GroupWidget::removeGroup, this, removeGroup);
    connect(groupWidget, &GroupWidget::chatroomWidgetClicked, chatForm, &GroupChatForm::focusInput);
    connect(groupWidget, &GroupWidget::middleMouseClicked, dialog,
            [=]() { dialog->removeGroup(groupId); });
    connect(groupWidget, &GroupWidget::chatroomWidgetClicked, chatForm, &ChatForm::focusInput);
    connect(groupWidget, &GroupWidget::newWindowOpened, this, &Widget::openNewDialog);

    // Signal transmission from the created `groupWidget` (which shown in
    // ContentDialog) to the `widget` (which shown in main widget)
    // FIXME: emit should be removed
    connect(groupWidget, &GroupWidget::chatroomWidgetClicked, [=](GenericChatroomWidget* w) {
        std::ignore = w;
        emit widget->chatroomWidgetClicked(widget);
    });

    connect(groupWidget, &GroupWidget::newWindowOpened, [=](GenericChatroomWidget* w) {
        std::ignore = w;
        emit widget->newWindowOpened(widget);
    });

    // FIXME: emit should be removed
    emit widget->chatroomWidgetClicked(widget);
}

bool Widget::newFriendMessageAlert(const ToxPk& friendId, const QString& text, bool sound, QString filename, size_t filesize)
{
    bool hasActive;
    QWidget* currentWindow;
    ContentDialog* contentDialog = contentDialogManager->getFriendDialog(friendId);
    Friend* f = friendList->findFriend(friendId);

    if (contentDialog != nullptr) {
        currentWindow = contentDialog->window();
        hasActive = contentDialogManager->isChatActive(friendId);
    } else {
        if (settings.getSeparateWindow() && settings.getShowWindow()) {
            if (settings.getDontGroupWindows()) {
                contentDialog = createContentDialog();
            } else {
                contentDialog = contentDialogManager->current();
                if (!contentDialog) {
                    contentDialog = createContentDialog();
                }
            }

            addFriendDialog(f, contentDialog);
            currentWindow = contentDialog->window();
            hasActive = contentDialogManager->isChatActive(friendId);
        } else {
            currentWindow = window();
            FriendWidget* widget = friendWidgets[friendId];
            hasActive = widget == activeChatroomWidget;
        }
    }

    // "Mute" in the dossier: the message still counts as unread, without a sound or an alert.
    const bool muted = airLocal && airLocal->isMuted(friendId);
    if (newMessageAlert(currentWindow, hasActive, sound, !muted)) {
        FriendWidget* widget = friendWidgets[friendId];
        f->setEventFlag(true);
        airSource->noteUnread(AirSource::friendEntryId(friendId));
        widget->updateStatusLight();
        ui->friendList->trackWidget(settings, style, widget);
        if (!muted) {
            // With the app in front the band says it in the window; behind, the system does.
            if (QWidget* const front = bandWindow(this, settings.getNotify())) {
                showFriendBand(front, *f, text, filename);
            } else {
                auto notificationData =
                    filename.isEmpty()
                        ? notificationGenerator->friendMessageNotification(f, text)
                        : notificationGenerator->fileTransferNotification(f, filename, filesize);
                notifier.notifyMessage(notificationData);
            }
        }

        if (contentDialog == nullptr) {
            if (hasActive) {
                setWindowTitle(widget->getTitle());
            }
        } else {
            contentDialogManager->updateFriendStatus(friendId);
        }

        return true;
    }

    return false;
}

bool Widget::newGroupMessageAlert(const GroupId& groupId, const ToxPk& authorPk,
                                  const QString& message, bool notify)
{
    bool hasActive;
    QWidget* currentWindow;
    ContentDialog* contentDialog = contentDialogManager->getGroupDialog(groupId);
    Group* g = groupList->findGroup(groupId);
    GroupWidget* widget = groupWidgets[groupId];

    if (contentDialog != nullptr) {
        currentWindow = contentDialog->window();
        hasActive = contentDialogManager->isChatActive(groupId);
    } else {
        currentWindow = window();
        hasActive = widget == activeChatroomWidget;
    }

    // "Mute" in the group dossier, or on the author's row there: the message still counts as
    // unread, without a sound, an alert or a notification.
    const bool muted =
        airLocal && (airLocal->isMuted(ToxPk(groupId.getByteArray())) || airLocal->isMuted(authorPk));
    if (!newMessageAlert(currentWindow, hasActive, !muted, notify && !muted)) {
        return false;
    }

    g->setEventFlag(true);
    airSource->noteUnread(AirSource::groupEntryId(groupId));
    widget->updateStatusLight();
    // A message that only counts (no mention while Only when someone mentions you is on) makes no
    // notification either, as it makes no sound.
    if (notify && !muted) {
        if (QWidget* const front = bandWindow(this, settings.getNotify())) {
            showGroupBand(front, *g, authorPk, message);
        } else {
            auto notificationData =
                notificationGenerator->groupMessageNotification(g, authorPk, message);
            notifier.notifyMessage(notificationData);
        }
    }

    if (contentDialog == nullptr) {
        if (hasActive) {
            setWindowTitle(widget->getTitle());
        }
    } else {
        contentDialogManager->updateGroupStatus(groupId);
    }

    return true;
}

void Widget::showFriendBand(QWidget* front, const Friend& f, const QString& text, const QString& filename)
{
    using AirStrings::Text;
    const ToxPk key = f.getPublicKey();
    // The sender, then what they wrote or sent; with message text off only that something came.
    const bool shown = !settings.getNotifyHide();
    AirBanner::Content band;
    band.title = f.getDisplayedName();
    if (filename.isEmpty()) {
        band.body = shown ? text : AirStrings::text(Text::NotifyNewMessage);
    } else {
        band.body = shown ? AirStrings::text(Text::FileMessage).arg(filename)
                          : AirStrings::text(Text::NotifyIncomingFile);
    }
    band.tile = AirBanner::Tile::Person;
    band.tileName = f.getDisplayedName();
    band.tone = Air::toneIndex(key.toString());
    band.photo = airSource->avatar(key.toString());
    band.hint = AirStrings::text(Text::BandOpensChat);
    AirBanner::post(front, band, [this, key] { openAirEntry(AirSource::friendEntryId(key)); });
}

void Widget::showGroupBand(QWidget* front, const Group& g, const ToxPk& author, const QString& message)
{
    using AirStrings::Text;
    const GroupId groupId = g.getPersistentId();
    AirBanner::Content band;
    band.title = g.getDisplayedName();
    if (settings.getNotifyHide()) {
        band.body = AirStrings::text(Text::NotifyNewMessage);
    } else {
        // Who wrote, then the words, as the notification of a group says it.
        const QString name = g.resolveToxPk(author);
        band.body = name.isEmpty() ? message : name + QStringLiteral(": ") + message;
    }
    band.tile = AirBanner::Tile::Group;
    band.tileName = g.getDisplayedName();
    band.tone = Air::toneIndex(groupId.toString());
    band.hint = AirStrings::text(Text::BandOpensChat);
    AirBanner::post(front, band, [this, groupId] { openAirEntry(AirSource::groupEntryId(groupId)); });
}

void Widget::showRequestBand(QWidget* front, const QString& message)
{
    using AirStrings::Text;
    // "Incoming request" and its words under the dashed tile; the key says nothing to a person.
    AirBanner::Content band;
    band.title = AirStrings::text(Text::RequestTag);
    band.body = message;
    band.tile = AirBanner::Tile::Request;
    band.hint = AirStrings::text(Text::BandOpensRequest);
    AirBanner::post(front, band, [this] {
        bringForward(this);
        airController->revealRequests();
    });
}

void Widget::showInviteBand(QWidget* front, const Friend& inviter, const QString& groupName)
{
    using AirStrings::Text;
    // Who invites, then to what. The tap opens Requests rather than the card alone: the
    // invitation may have been answered while the band was up.
    const ToxPk key = inviter.getPublicKey();
    AirBanner::Content band;
    band.title = inviter.getDisplayedName();
    band.body = groupName.isEmpty() ? AirStrings::text(Text::NotifyInvite)
                                    : AirStrings::text(Text::NotifyInviteTo).arg(groupName);
    band.tile = AirBanner::Tile::Person;
    band.tileName = inviter.getDisplayedName();
    band.tone = Air::toneIndex(key.toString());
    band.photo = airSource->avatar(key.toString());
    band.hint = AirStrings::text(Text::BandOpensInvite);
    AirBanner::post(front, band, [this] {
        bringForward(this);
        airController->revealRequests();
    });
}

QString Widget::fromDialogType(DialogType type)
{
    switch (type) {
    case DialogType::AddDialog:
        // The form only imports a file of IDs now; one ID goes through New contact.
        return AirStrings::text(AirStrings::Text::NcImport);
    case DialogType::GroupDialog:
        return tr("Group invites", "title of the window");
    case DialogType::TransferDialog:
        return tr("File transfers", "title of the window");
    case DialogType::SettingDialog:
        return AirStrings::text(AirStrings::Text::Settings);
    case DialogType::ProfileDialog:
        return AirStrings::text(AirStrings::Text::You);
    case DialogType::NotesDialog:
        return AirStrings::text(AirStrings::Text::SavedTitle);
    }

    assert(false);
    return QString();
}

bool Widget::newMessageAlert(QWidget* currentWindow, bool isActive, bool sound, bool notify)
{
    bool inactiveWindow = isMinimized() || !currentWindow->isActiveWindow();

    if (!inactiveWindow && isActive) {
        return false;
    }

    if (notify) {
        if (settings.getShowWindow()) {
            currentWindow->show();
        }

        if (settings.getNotify()) {
            if (inactiveWindow) {
                // A system notification says it already; without one the window asks for attention.
                if (!systemNotificationShows()) {
                    QApplication::alert(currentWindow);
                }
                eventFlag = true;
            }
            bool isBusy = core->getStatus() == Status::Status::Busy;
            bool busySound = settings.getBusySound();
            bool notifySound = settings.getNotifySound();

            if (notifySound && sound && (!isBusy || busySound)) {
                playNotificationSound(IAudioSink::Sound::NewMessage);
            }
        }
    }

    return true;
}

bool Widget::systemNotificationShows() const
{
#if DESKTOP_NOTIFICATIONS
    return settings.getDesktopNotify();
#else
    return TrayNotify::wanted(settings.getNotify(), settings.getDesktopNotify(), notifier.canShow());
#endif
}

void Widget::onFriendRequestReceived(const ToxPk& friendPk, const QString& message)
{
    // A request from a blocked key is dropped as it lands, the way Decline drops one: no card, no
    // badge, no notification, and nothing goes back.
    if (!airLocal || airLocal->isBlocked(friendPk)) {
        return;
    }
    // Nothing is accepted without the person (E12): the request waits as a card, with its words,
    // until Accept or Decline. The same request again (toxcore asks anew after a restart) is not
    // news.
    if (!airLocal->addRequest(friendPk, message)) {
        return;
    }
    newMessageAlert(window(), isActiveWindow(), true, true);
    // A notification of its own kind, as the card is a row of its own (E22).
    if (QWidget* const front = bandWindow(this, settings.getNotify())) {
        showRequestBand(front, message);
        return;
    }
    auto notificationData = notificationGenerator->friendRequestNotification(friendPk, message);
    notifier.notifyMessage(notificationData);
}

void Widget::onFileReceiveRequested(const ToxFile& file)
{
    const ToxPk& friendPk = friendList->id2Key(file.friendId);
    newFriendMessageAlert(friendPk, {}, true, file.fileName, file.progress.getFileSize());
}

void Widget::updateFriendActivity(const Friend& frnd)
{
    const ToxPk& pk = frnd.getPublicKey();
    const auto oldTime = settings.getFriendActivity(pk);
    const auto newTime = QDateTime::currentDateTime();
    settings.setFriendActivity(pk, newTime);
    FriendWidget* widget = friendWidgets[frnd.getPublicKey()];
    chatListWidget->moveWidget(widget, frnd.getStatus());
    chatListWidget->updateActivityTime(oldTime); // update old category widget
    airController->refresh();
}

void Widget::removeFriend(Friend* f, bool fake)
{
    assert(f);
    if (!fake) {
        RemoveChatDialog ask(this, *f);
        ask.exec();

        if (!ask.accepted()) {
            return;
        }
        forgetFriend(*f, ask.removeHistory());
    }
    dropFriend(f, fake);
}

/**
 * @brief What removing a contact clears on this device besides the contact: the history when asked,
 * the queue and the marks.
 */
void Widget::forgetFriend(const Friend& f, bool removeHistory)
{
    History* const history = profile.getHistory();
    if (removeHistory && history) {
        history->removeChatHistory(f.getPersistentId());
    }

    // Nobody is left to send the queue to. Pending rows of a kept history join the queue again
    // if the person is added back, as they did before the queue was kept apart.
    if (Outbox* const outbox = history ? history->getOutbox() : nullptr) {
        outbox->removeChat(f.getPersistentId());
    }
    // Nothing this computer noted about them (online hours, mute, verification) stays either, nor
    // their place among the favorites.
    if (airLocal) {
        airLocal->forget(f.getPublicKey());
    }
    const QString entryId = AirSource::friendEntryId(f.getPublicKey());
    if (settings.isFavoriteChat(entryId)) {
        settings.setFavoriteChat(entryId, false);
        settings.savePersonal();
    }
}

/**
 * @brief Block from the dossier, already confirmed there: the key goes on the block list first,
 * then the contact and the conversation go the way Remove takes them, without a second question.
 */
void Widget::blockFriend(const ToxPk& friendPk)
{
    if (airLocal) {
        airLocal->block(friendPk);
    }
    removeConfirmedFriend(friendPk);
}

void Widget::removeConfirmedFriend(const ToxPk& friendPk)
{
    Friend* const f = friendList->findFriend(friendPk);
    if (!f) {
        return;
    }
    forgetFriend(*f, true);
    dropFriend(f, false);
}

void Widget::dropFriend(Friend* f, bool fake)
{
    const ToxPk friendPk = f->getPublicKey();
    auto widget = friendWidgets[friendPk];
    widget->setAsInactiveChatroom();
    if (widget == activeChatroomWidget) {
        activeChatroomWidget = nullptr;
        showNoChat();
    }

    friendAlertConnections.remove(friendPk);

    chatListWidget->removeFriendWidget(widget);

    ContentDialog* lastDialog = contentDialogManager->getFriendDialog(friendPk);
    if (lastDialog != nullptr) {
        lastDialog->removeFriend(friendPk);
    }

    friendList->removeFriend(friendPk, settings, fake);
    if (!fake) {
        core->removeFriend(f->getId());
        // aliases aren't supported for non-friend peers in groups, revert to basic username
        for (Group* g : groupList->getAllGroups()) {
            if (g->getPeerList().contains(friendPk)) {
                g->updateUsername(friendPk, f->getUserName());
            }
        }
    }

    friendWidgets.remove(friendPk);

    auto chatForm = chatForms[friendPk];
    chatForms.remove(friendPk);
    delete chatForm;

    delete f;
    if (contentLayout && contentLayout->mainHead->layout()->isEmpty()) {
        showNoChat();
    }
}

void Widget::removeFriend(const ToxPk& friendId)
{
    removeFriend(friendList->findFriend(friendId), false);
}

void Widget::onDialogShown(GenericChatroomWidget* widget)
{
    widget->resetEventFlags();
    widget->updateStatusLight();
    if (airController) {
        // The conversation's row loses its unread mark with the flag.
        airController->refresh();
    }

    ui->friendList->updateTracking(widget);
    resetIcon();
}

void Widget::onFriendDialogShown(const Friend* f)
{
    onDialogShown(friendWidgets[f->getPublicKey()]);
}

void Widget::onGroupDialogShown(Group* g)
{
    const GroupId& groupId = g->getPersistentId();
    onDialogShown(groupWidgets[groupId]);
}

void Widget::toggleFullscreen()
{
    isTogglingFullscreen_ = true;

    if (isFullScreen()) {
        showNormal();

        if (wasMaximizedBeforeFullscreen_) {
            showMaximized();
        } else if (geometryBeforeFullscreen_.isValid()) {
            setGeometry(geometryBeforeFullscreen_);
        }
    } else {
        wasMaximizedBeforeFullscreen_ = isMaximized();
        geometryBeforeFullscreen_ =
            wasMaximizedBeforeFullscreen_ ? normalGeometry() : geometry();
        showFullScreen();
    }

    isTogglingFullscreen_ = false;
}

void Widget::onUpdateAvailable()
{
    ui->settingsButton->setProperty("update-available", true);
    ui->settingsButton->style()->unpolish(ui->settingsButton);
    ui->settingsButton->style()->polish(ui->settingsButton);
}

ContentDialog* Widget::createContentDialog() const
{
    ContentDialog* contentDialog = new ContentDialog(*core, settings, style,
        *messageBoxManager, *friendList, *groupList, profile);

    registerContentDialog(*contentDialog);
    return contentDialog;
}

void Widget::registerContentDialog(ContentDialog& contentDialog) const
{
    contentDialogManager->addContentDialog(contentDialog);
    connect(&contentDialog, &ContentDialog::friendDialogShown, this, &Widget::onFriendDialogShown);
    connect(&contentDialog, &ContentDialog::groupDialogShown, this, &Widget::onGroupDialogShown);
    connect(core, &Core::usernameSet, &contentDialog, &ContentDialog::setUsername);
    connect(&settings, &Settings::groupchatPositionChanged, &contentDialog,
            &ContentDialog::reorderLayouts);
    connect(&contentDialog, &ContentDialog::addFriendDialog, this, &Widget::addFriendDialog);
    connect(&contentDialog, &ContentDialog::addGroupDialog, this, &Widget::addGroupDialog);
    connect(&contentDialog, &ContentDialog::connectFriendWidget, this, &Widget::connectFriendWidget);

#ifdef Q_OS_MAC
    Nexus& n = nexus;
    connect(&contentDialog, &ContentDialog::destroyed, &n, &Nexus::updateWindowsClosed);
    connect(&contentDialog, &ContentDialog::windowStateChanged, &n, &Nexus::onWindowStateChanged);
    connect(contentDialog.windowHandle(), &QWindow::windowTitleChanged, &n, &Nexus::updateWindows);
    n.updateWindows();
#endif
}

ContentLayout* Widget::createContentDialog(DialogType type) const
{
    class Dialog : public ActivateDialog
    {
    public:
        explicit Dialog(DialogType type_, Settings& settings_, Core* core_, Style& style_)
            : ActivateDialog(style_, nullptr, Qt::Window)
            , type(type_)
            , settings(settings_)
            , core{core_}
            , style{style_}
        {
            restoreGeometry(settings.getDialogSettingsGeometry());
            Translator::registerHandler(std::bind(&Dialog::retranslateUi, this), this);
            retranslateUi();
            setWindowIcon(QIcon(":/img/icons/khandaq.svg"));
            reloadTheme();

            connect(core, &Core::usernameSet, this, &Dialog::retranslateUi);
        }

        ~Dialog()
        {
            Translator::unregister(this);
        }

    public slots:

        void retranslateUi()
        {
            setWindowTitle(core->getUsername() + QStringLiteral(" - ") + Widget::fromDialogType(type));
        }

        void reloadTheme() final
        {
            setStyleSheet(style.getStylesheet("window/general.css", settings));
        }

    protected:
        void resizeEvent(QResizeEvent* event) override
        {
            settings.setDialogSettingsGeometry(saveGeometry());
            QDialog::resizeEvent(event);
        }

        void moveEvent(QMoveEvent* event) override
        {
            settings.setDialogSettingsGeometry(saveGeometry());
            QDialog::moveEvent(event);
        }

    private:
        DialogType type;
        Settings& settings;
        Core* core;
        Style& style;
    };

    Dialog* dialog = new Dialog(type, settings, core, style);
    dialog->setAttribute(Qt::WA_DeleteOnClose);
    ContentLayout* contentLayoutDialog = new ContentLayout(settings, style, dialog);

    dialog->setObjectName("detached");
    dialog->setLayout(contentLayoutDialog);
    dialog->layout()->setMargin(0);
    dialog->layout()->setSpacing(0);
    dialog->setMinimumSize(leastSeparateWidth, leastSingleHeight);
    dialog->setAttribute(Qt::WA_DeleteOnClose);
    dialog->show();

#ifdef Q_OS_MAC
    connect(dialog, &Dialog::destroyed, &nexus, &Nexus::updateWindowsClosed);
    connect(dialog, &ActivateDialog::windowStateChanged, &nexus,
            &Nexus::updateWindowsStates);
    connect(dialog->windowHandle(), &QWindow::windowTitleChanged, &nexus,
            &Nexus::updateWindows);
    nexus.updateWindows();
#endif

    return contentLayoutDialog;
}

void Widget::copyFriendIdToClipboard(const ToxPk& friendId)
{
    Friend* f = friendList->findFriend(friendId);
    if (f != nullptr) {
        QClipboard* clipboard = QApplication::clipboard();
        clipboard->setText(friendId.toString(), QClipboard::Clipboard);
    }
}

void Widget::onGroupInviteReceived(const GroupInvite& inviteInfo)
{
    const uint32_t friendId = inviteInfo.getFriendId();
    const ToxPk& friendPk = friendList->id2Key(friendId);
    const Friend* f = friendList->findFriend(friendPk);
    if (!f) {
        return;
    }
    updateFriendActivity(*f);

    if (settings.getAutoGroupInvite(f->getPublicKey())) {
        onGroupInviteAccepted(inviteInfo);
        return;
    }
    // The invitation waits as a card above the list until Join or Decline (E12); toxcore hands it
    // over only once, so it is kept with the profile until then.
    if (!airLocal
        || airLocal->addInvite(friendPk, inviteInfo.getGroupName(), inviteInfo.getInvite()).isEmpty()) {
        return;
    }
    newMessageAlert(window(), isActiveWindow(), true, true);
    if (QWidget* const front = bandWindow(this, settings.getNotify())) {
        showInviteBand(front, *f, inviteInfo.getGroupName());
        return;
    }
    auto notificationData =
        notificationGenerator->groupInvitationNotification(f, inviteInfo.getGroupName());
    notifier.notifyMessage(notificationData);
}

void Widget::onGroupInviteAccepted(const GroupInvite& inviteInfo)
{
    const uint32_t groupId = core->joinGroupchat(inviteInfo);
    if (groupId == std::numeric_limits<uint32_t>::max()) {
        qWarning() << "onGroupInviteAccepted: Unable to accept group invite";
        AirDialog::notice(this, AirStrings::text(AirStrings::Text::ErrorJoinGroupTitle),
                          AirStrings::text(AirStrings::Text::TryAgain));
    }
}

void Widget::onGroupJoinFailed(const QString& reason)
{
    // The operation is the title; toxcore's reason, when it gives one, is what to do.
    AirDialog::notice(this, AirStrings::text(AirStrings::Text::ErrorJoinGroupTitle),
                      reason.isEmpty() ? AirStrings::text(AirStrings::Text::TryAgain) : reason);
}

void Widget::onGroupJoinSucceeded()
{
    // groupJoined signal from Core opens the group chat
}

void Widget::onGroupJoinByIdPrompt()
{
    // Joining by key is the second page of New group: the key field checks what is typed.
    openJoinGroup();
}

void Widget::onGroupMessageReceived(int groupnumber, int peernumber, const QString& message,
                                    bool isAction, uint32_t messageId)
{
    const GroupId& groupId = groupList->id2Key(groupnumber);
    assert(groupList->findGroup(groupId));

    ToxPk author = core->getGroupPeerPk(groupnumber, peernumber);

    groupMessageDispatchers[groupId]->onMessageReceived(author, isAction, message, messageId);
}

void Widget::onFriendMessageEffect(uint32_t friendnumber, const QByteArray& packet)
{
    Air::MessageEffect effect;
    if (!Air::readFriendPacket(packet, &effect)) {
        // DESIGN.md "Unknown markup": another version, length or kind is dropped, never shown.
        qDebug() << "Dropped a lossless packet"
                 << static_cast<int>(static_cast<uint8_t>(packet.at(0))) << "of" << packet.size()
                 << "bytes that is no edit, deletion or reaction";
        return;
    }
    const ToxPk friendPk = friendList->id2Key(friendnumber);
    const std::shared_ptr<ChatHistory> chatLog = friendChatLogs.value(friendPk);
    if (!chatLog) {
        return;
    }
    using Change = SessionChatLog::EffectResult::Change;
    const SessionChatLog::EffectResult result =
        chatLog->applyEffect(friendPk, effect, core->getSelfPublicKey());
    if (result.change == Change::None) {
        // A text between two people is known by its msgV3 hash, which this client has none of.
        qDebug() << "An edit, deletion or reaction from a friend points at nothing shown here";
        return;
    }
    // A file deleted while it still comes or waits for Accept: the transfer stops as well.
    const ToxFile& file = result.file;
    const bool live = file.status == ToxFile::INITIALIZING || file.status == ToxFile::PAUSED
                      || file.status == ToxFile::TRANSMITTING;
    if (result.change == Change::Deleted && effect.anchor == Air::MessageEffect::Anchor::File
        && file.direction == ToxFile::RECEIVING && live) {
        core->getCoreFile()->cancelFileRecv(friendnumber, file.fileNum);
    }
}

void Widget::onGroupMessageEffect(int groupnumber, const ToxPk& sender, const QByteArray& packet)
{
    Air::MessageEffect effect;
    if (!Air::readGroupPacket(packet, &effect)) {
        qDebug() << "Dropped a group packet of" << packet.size()
                 << "bytes that is no edit, deletion or reaction";
        return;
    }
    const GroupId groupId = groupList->id2Key(static_cast<uint32_t>(groupnumber));
    const auto chatLog = groupChatLogs.find(groupId);
    // A member whose messages are hidden on this device changes nothing here either.
    if (chatLog == groupChatLogs.end() || settings.getBlackList().contains(sender.toString())) {
        return;
    }
    const auto result = std::static_pointer_cast<ChatHistory>(chatLog.value())
                            ->applyEffect(sender, effect, core->getGroupSelfPublicKey(groupnumber));
    if (result.change == SessionChatLog::EffectResult::Change::None) {
        qDebug() << "An edit, deletion or reaction in a group points at nothing shown here";
    }
}

void Widget::onGroupPrivateMessageReceived(int groupnumber, const ToxPk& peerPk,
                                           const QString& message, bool isAction)
{
    const GroupId groupId = groupList->id2Key(static_cast<uint32_t>(groupnumber));
    Group* const group = groupList->findGroup(groupId);
    // A member whose messages are hidden on this device is not heard privately either.
    if (!group || !airLocal || settings.getBlackList().contains(peerPk.toString())) {
        return;
    }
    const QString text = isAction ? ChatForm::ACTION_PREFIX + message : message;
    airLocal->addPrivateLine(groupId.toString(), peerPk.toString(), false, text);

    const QSharedPointer<GroupChatForm> form = groupChatForms.value(groupId);
    if (form && form->isVisible() && form->isPrivateOpen(peerPk.toString())) {
        // Read where it lands.
        return;
    }
    if (airLocal->isMuted(ToxPk(groupId.getByteArray())) || airLocal->isMuted(peerPk)) {
        return;
    }
    const QString name = group->resolveToxPk(peerPk);
    const QString key = peerPk.toString();
    if (!newMessageAlert(window(), false, true, true)) {
        return;
    }
    QWidget* const front = bandWindow(this, settings.getNotify());
    const QString words = AirStrings::messageWords(text);
    if (!front) {
        notifier.notifyMessage(notificationGenerator->groupMessageNotification(group, peerPk, words));
        return;
    }
    // The band names who wrote, under the group's tile; a click opens the private messages.
    using AirStrings::Text;
    AirBanner::Content band;
    band.title = AirStrings::text(Text::PrivateReceived).arg(name);
    band.body = settings.getNotifyHide() ? QString() : words;
    band.tile = AirBanner::Tile::Group;
    band.tileName = group->getDisplayedName();
    band.tone = Air::toneIndex(groupId.toString());
    band.hint = AirStrings::text(Text::BandOpensChat);
    AirBanner::post(front, band, [this, groupId, key] {
        openAirEntry(AirSource::groupEntryId(groupId));
        const QSharedPointer<GroupChatForm> shown = groupChatForms.value(groupId);
        if (shown) {
            shown->openPrivate(key);
        }
    });
}

void Widget::onGroupPeerlistChanged(uint32_t groupnumber)
{
    const GroupId& groupId = groupList->id2Key(groupnumber);
    Group* g = groupList->findGroup(groupId);
    assert(g);
    g->regeneratePeerList();
}

namespace {
QString sanitizeGroupFileName(const QString& fileName)
{
    QString name = QFileInfo(fileName).fileName();
    name.replace(QRegularExpression(QStringLiteral("[\\\\/:*?\"<>|]")), QStringLiteral("_"));
    if (name.isEmpty() || name == QLatin1String(".") || name == QLatin1String("..")) {
        name = QStringLiteral("file.bin");
    }
    return name;
}

QString uniqueFilePath(const QDir& dir, const QString& fileName)
{
    QString candidate = dir.filePath(fileName);
    if (!QFile::exists(candidate)) {
        return candidate;
    }
    const QFileInfo info(fileName);
    const QString base = info.completeBaseName();
    const QString suffix = info.suffix().isEmpty() ? QString() : QLatin1String(".") + info.suffix();
    for (int i = 1; i < 1000; ++i) {
        candidate = dir.filePath(QStringLiteral("%1 (%2)%3").arg(base).arg(i).arg(suffix));
        if (!QFile::exists(candidate)) {
            return candidate;
        }
    }
    return dir.filePath(fileName);
}
} // namespace

QDir Widget::groupDownloadDir(const Group* g) const
{
    QString base = settings.getGlobalAutoAcceptDir();
    if (base.isEmpty()) {
        base = QStandardPaths::writableLocation(QStandardPaths::DownloadLocation);
    }
    QString groupPart = g->getName().trimmed();
    groupPart.replace(QRegularExpression(QStringLiteral("[\\\\/:*?\"<>|]")), QStringLiteral("_"));
    if (groupPart.isEmpty()) {
        groupPart = g->getPersistentId().toString().left(16);
    }
    QDir dir(base + QDir::separator() + QStringLiteral("Khandaq Groups") + QDir::separator() + groupPart);
    if (!dir.exists()) {
        QDir().mkpath(dir.absolutePath());
    }
    return dir;
}

void Widget::dispatchGroupFileToChatLog(const GroupId& groupId, const ToxPk& sender,
                                        ToxFile file)
{
    auto chatLogIt = groupChatLogs.find(groupId);
    if (chatLogIt == groupChatLogs.end()) {
        return;
    }
    auto chatHistory = std::static_pointer_cast<ChatHistory>(chatLogIt.value());

    // INITIALIZING creates the history record, FINISHED marks it complete and
    // makes the widget render the final state (preview + open buttons).
    file.status = ToxFile::INITIALIZING;
    chatHistory->onFileUpdated(sender, file);
    file.status = ToxFile::FINISHED;
    chatHistory->onFileUpdated(sender, file);
}

void Widget::onGroupFileReceived(int groupnumber, const ToxPk& sender, const QString& fileName,
                                 const QByteArray& fileData, const QDateTime& timestamp,
                                 bool wasSynced, const QByteArray& fileId)
{
    std::ignore = timestamp;
    const GroupId& groupId = groupList->id2Key(groupnumber);
    Group* g = groupList->findGroup(groupId);
    if (g == nullptr) {
        return;
    }

    // KHANDAQ (audit 2026-08-20): bound what one group may write to the user's disk unattended.
    //
    // Every file any member of a group sends lands in the download folder immediately, with no
    // prompt and, until now, no limit — up to KHANDAQ_MAX_FILE_TRANSFER_BYTES (200 MB) per file, as
    // often as the sender likes, from anyone at all in a public group. Android and iOS both gate
    // this on the user's attachment-download setting; desktop has no such setting, so a per-group
    // per-run ceiling is the smallest thing that turns "unbounded" into "bounded".
    //
    // The cap is NOT an early return on the receive path in general: the bytes only exist in memory
    // here and desktop has no "download later" affordance, so refusing would lose the file outright
    // rather than defer it. Past the ceiling the file is dropped and said so once, which is the
    // honest outcome — the alternative is filling the disk silently.
    static constexpr quint64 kGroupAutoSaveSessionCap = 2ULL * 1024 * 1024 * 1024; // per group, per run
    static QHash<GroupId, quint64> groupAutoSavedBytes;
    static QSet<GroupId> groupAutoSaveWarned;
    quint64& savedForGroup = groupAutoSavedBytes[groupId];
    if (savedForGroup + static_cast<quint64>(fileData.size()) > kGroupAutoSaveSessionCap) {
        if (!groupAutoSaveWarned.contains(groupId)) {
            groupAutoSaveWarned.insert(groupId);
            qWarning() << "onGroupFileReceived: group auto-save cap reached for" << g->getName()
                       << "- further files from this group will not be written this session";
        }
        return;
    }

    const QDir dir = groupDownloadDir(g);
    const QString safeName = sanitizeGroupFileName(fileName);
    const QString filePath = uniqueFilePath(dir, safeName);

    QFile out(filePath);
    if (!out.open(QIODevice::WriteOnly)) {
        qWarning() << "onGroupFileReceived: cannot write" << filePath;
        return;
    }
    out.write(fileData);
    out.close();
    // Booked after the write actually happened, so a failed open above costs nothing.
    savedForGroup += static_cast<quint64>(fileData.size());

    static uint32_t groupFileNum = 700000;
    ToxFile file(++groupFileNum, static_cast<uint32_t>(groupnumber), safeName, filePath,
                 static_cast<uint64_t>(fileData.size()), ToxFile::RECEIVING);
    file.progress.addSample(file.progress.getFileSize(), QTime::currentTime());
    // The id of the file in its packets, which its sender's deletion and the reactions point at.
    file.resumeFileId = fileId;

    dispatchGroupFileToChatLog(groupId, sender, file);

    // KHANDAQ (external audit 2026-08-21, K-01) — the desktop clause: "renders unverified legacy
    // rows visibly and suppresses notifications for them".
    //
    // The notification half, which is the security-relevant one. A synced row's `sender` is 32 bytes
    // the RELAYING peer chose; nothing authenticated it, and desktop cannot verify it even in
    // principle, because it drops version 0x02 at the parser and has no signed-history consumer. So
    // any group member could make this desktop pop an OS notification attributed to any other
    // member, at will, simply by relaying a fabricated history row. The file itself is still saved
    // and still appears in the chat log — it just no longer interrupts the user under a name nobody
    // stands behind.
    //
    // Live rows are unaffected: toxcore authenticates their sender.
    if (!wasSynced) {
        newGroupMessageAlert(groupId, sender, fileName, settings.getGroupAlwaysNotify());
    } else {
        qDebug() << "onGroupFileReceived: synced row, storing without a notification"
                 << "(attribution to" << sender.toString().left(8) << "is unauthenticated)";
    }
}

void Widget::onGroupFileSent(int groupnumber, const QString& fileName, const QString& localPath,
                             qint64 fileSize, const QByteArray& fileId)
{
    const GroupId& groupId = groupList->id2Key(groupnumber);
    if (!groupList->findGroup(groupId)) {
        return;
    }

    static uint32_t groupFileNum = 800000;
    ToxFile file(++groupFileNum, static_cast<uint32_t>(groupnumber), sanitizeGroupFileName(fileName),
                 localPath, static_cast<uint64_t>(fileSize), ToxFile::SENDING);
    file.progress.addSample(file.progress.getFileSize(), QTime::currentTime());
    file.resumeFileId = fileId;

    dispatchGroupFileToChatLog(groupId, core->getSelfPublicKey(), file);
}

void Widget::onGroupPeerNameChanged(uint32_t groupnumber, const ToxPk& peerPk, const QString& newName)
{
    const GroupId& groupId = groupList->id2Key(groupnumber);
    Group* g = groupList->findGroup(groupId);
    assert(g);

    const QString setName = friendList->decideNickname(peerPk, newName);
    g->updateUsername(peerPk, newName);
}

void Widget::onGroupTitleChanged(uint32_t groupnumber, const QString& author, const QString& title)
{
    const GroupId& groupId = groupList->id2Key(groupnumber);
    Group* g = groupList->findGroup(groupId);
    assert(g);

    GroupWidget* widget = groupWidgets[groupId];
    if (widget->isActive()) {
        setWindowTitle(title);
    }

    g->setTitle(author, title);
    chatListWidget->itemsChanged();
}

void Widget::titleChangedByUser(const QString& title)
{
    const auto* group = qobject_cast<Group*>(sender());
    assert(group != nullptr);
    emit changeGroupTitle(group->getId(), title);
}

void Widget::onGroupPeerAudioPlaying(int groupnumber, ToxPk peerPk)
{
    const GroupId& groupId = groupList->id2Key(groupnumber);
    assert(groupList->findGroup(groupId));

    auto form = groupChatForms[groupId].data();
    form->peerAudioPlaying(peerPk);
}

void Widget::removeGroup(Group* g, bool fake)
{
    assert(g);
    if (!fake) {
        RemoveChatDialog ask(this, *g);
        ask.exec();

        if (!ask.accepted()) {
            return;
        }

        if (ask.removeHistory()) {
            profile.getHistory()->removeChatHistory(g->getPersistentId());
        }
    }
    discardGroup(g, fake);
}

void Widget::leaveGroup(const GroupId& groupId)
{
    Group* const g = groupList->findGroup(groupId);
    if (!g) {
        return;
    }
    // Asked in the dossier, whose note says that the history leaves this device with the group.
    if (History* const history = profile.getHistory()) {
        history->removeChatHistory(groupId);
    }
    discardGroup(g, false);
}

void Widget::discardGroup(Group* g, bool fake)
{
    assert(g);
    // A copy: the group, and the id it holds, are deleted on the way.
    const GroupId groupId = g->getPersistentId();
    const auto groupnumber = g->getId();
    auto groupWidgetIt = groupWidgets.find(groupId);
    if (groupWidgetIt == groupWidgets.end()) {
        qWarning() << "Tried to remove group" << groupnumber << "but GroupWidget doesn't exist";
        return;
    }
    auto widget = groupWidgetIt.value();
    widget->setAsInactiveChatroom();
    if (static_cast<GenericChatroomWidget*>(widget) == activeChatroomWidget) {
        activeChatroomWidget = nullptr;
        showNoChat();
    }

    groupList->removeGroup(groupId, fake);
    ContentDialog* contentDialog = contentDialogManager->getGroupDialog(groupId);
    if (contentDialog != nullptr) {
        contentDialog->removeGroup(groupId);
    }

    if (!fake) {
        core->removeGroup(groupnumber);
        // Its mute and the private messages with its members leave with it.
        if (airLocal) {
            airLocal->forgetGroup(groupId.toString());
        }
    }
    chatListWidget->removeGroupWidget(widget); // deletes widget

    groupWidgets.remove(groupId);
    auto groupChatFormIt = groupChatForms.find(groupId);
    if (groupChatFormIt == groupChatForms.end()) {
        qWarning() << "Tried to remove group" << groupnumber << "but GroupChatForm doesn't exist";
        return;
    }
    groupChatForms.erase(groupChatFormIt);
    groupAlertConnections.remove(groupId);

    delete g;
    if (contentLayout && contentLayout->mainHead->layout()->isEmpty()) {
        showNoChat();
    }
}

void Widget::removeGroup(const GroupId& groupId)
{
    removeGroup(groupList->findGroup(groupId));
}

Group* Widget::createGroup(uint32_t groupnumber, const GroupId& groupId)
{
    assert(core != nullptr);

    Group* g = groupList->findGroup(groupId);
    if (g) {
        // Same persistent group rejoined: the tox group number may have
        // changed (friend-assisted rejoin replaces the instance), rebind it.
        groupList->updateGroupNumber(groupnumber, groupId);
        g->setToxGroupNum(static_cast<int>(groupnumber));
        const QString realName = core->getGroupName(static_cast<int>(groupnumber));
        if (!realName.isEmpty()) {
            g->setTitle(QString(), realName);
        }
        return g;
    }

    // prefer the real NGC group name; "Group" with the start of its key is only a fallback until
    // the shared state arrives, the same one Core gives a group that has no name
    QString groupName = core->getGroupName(static_cast<int>(groupnumber));
    if (groupName.isEmpty()) {
        //: Name of a group until its own name arrives; %1 is the start of the group key
        groupName = tr("Group %1").arg(groupId.toString().left(8));
    }
    const bool enabled = core->getGroupAvEnabled(groupnumber);
    Group* newgroup =
        groupList->addGroup(*core, groupnumber, groupId, groupName, enabled, core->getUsername(),
            *friendList);
    assert(newgroup);

    if (enabled) {
        connect(newgroup, &Group::userLeft, [=](const ToxPk& user){
            CoreAV *av = core->getAv();
            assert(av);
            av->invalidateGroupCallPeerSource(*newgroup, user);
        });
    }
    auto rawChatroom = new GroupChatroom(newgroup, contentDialogManager.get(), *core,
        *friendList);
    std::shared_ptr<GroupChatroom> chatroom(rawChatroom);

    const auto compact = settings.getCompactLayout();
    auto widget = new GroupWidget(chatroom, compact, settings, style);
    auto messageProcessor = MessageProcessor(*groupMessageProcessorParams);
    auto messageDispatcher =
        std::make_shared<GroupMessageDispatcher>(*newgroup, std::move(messageProcessor), *core,
                                                 *core, settings);

    auto history = profile.getHistory();
    // Note: We do not have to connect the message dispatcher signals since
    // ChatHistory hooks them up in a very specific order
    auto chatHistory =
        std::make_shared<ChatHistory>(*newgroup, history, *core, settings, *messageDispatcher,
                                      *friendList, *groupList, airLocal);

    auto notifyReceivedCallback = [this, groupId](const ToxPk& author, const Message& message) {
        auto isTargeted = std::any_of(message.metadata.begin(), message.metadata.end(),
                                      [](MessageMetadata metadata) {
                                          return metadata.type == MessageMetadataType::selfMention;
                                      });
        const bool alerted =
            newGroupMessageAlert(groupId, author, AirStrings::messageWords(message.content),
                                 isTargeted || settings.getGroupAlwaysNotify());
        // The row carries an @ until the group is opened (GroupChatroom::resetEventFlags).
        if (alerted && isTargeted) {
            if (Group* group = groupList->findGroup(groupId)) {
                group->setMentionedFlag(true);
            }
        }
    };

    auto notifyReceivedConnection =
        connect(messageDispatcher.get(), &IMessageDispatcher::messageReceived, notifyReceivedCallback);
    groupAlertConnections.insert(groupId, notifyReceivedConnection);

    auto form = new GroupChatForm(*core, newgroup, *chatHistory, *messageDispatcher,
        settings, *documentCache, *smileyPack, style, *messageBoxManager, *friendList, *groupList);
    connect(&settings, &Settings::nameColorsChanged, form, &GenericChatForm::setColorizedNames);
    form->setColorizedNames(settings.getEnableGroupChatsColor());
    form->setAir(airLocal, airSource);
    // The form goes with the group, so leaving waits until the dossier's click is over.
    connect(form, &GroupChatForm::leaveRequested, this, &Widget::leaveGroup, Qt::QueuedConnection);
    groupMessageDispatchers[groupId] = messageDispatcher;
    groupChatLogs[groupId] = chatHistory;
    groupWidgets[groupId] = widget;
    groupChatrooms[groupId] = chatroom;
    groupChatForms[groupId] = QSharedPointer<GroupChatForm>(form);
    airSource->watchGroup(*newgroup, chatHistory);

    chatListWidget->addGroupWidget(widget);
    widget->updateStatusLight();
    chatListWidget->activateWindow();

    connect(widget, &GroupWidget::chatroomWidgetClicked, this, &Widget::onChatroomWidgetClicked);
    connect(widget, &GroupWidget::newWindowOpened, this, &Widget::openNewDialog);
#if (QT_VERSION >= QT_VERSION_CHECK(5, 7, 0))
    auto widgetRemoveGroup = QOverload<const GroupId&>::of(&Widget::removeGroup);
#else
    auto widgetRemoveGroup = static_cast<void (Widget::*)(const GroupId&)>(&Widget::removeGroup);
#endif
    connect(widget, &GroupWidget::removeGroup, this, widgetRemoveGroup);
    connect(widget, &GroupWidget::middleMouseClicked, this, [=]() { removeGroup(groupId); });
    connect(widget, &GroupWidget::chatroomWidgetClicked, form, &ChatForm::focusInput);
    connect(newgroup, &Group::titleChangedByUser, this, &Widget::titleChangedByUser);
    connect(core, &Core::usernameSet, newgroup, &Group::setSelfName);

    return newgroup;
}

void Widget::onEmptyGroupCreated(uint32_t groupnumber, const GroupId& groupId, const QString& title)
{
    Group* group = createGroup(groupnumber, groupId);
    if (!group) {
        return;
    }
    if (title.isEmpty()) {
        // Only rename group if groups are visible.
        if (groupsVisible()) {
            groupWidgets[groupId]->editName();
        }
    } else {
        group->setTitle(QString(), title);
    }
}

void Widget::onGroupJoined(int groupNum, const GroupId& groupId)
{
    createGroup(groupNum, groupId);
}

/**
 * @brief Used to reset the blinking icon.
 */
void Widget::resetIcon()
{
    eventIcon = false;
    eventFlag = false;
    updateIcons();
}

bool Widget::event(QEvent* e)
{
    switch (e->type()) {
    case QEvent::MouseButtonPress:
    case QEvent::MouseButtonDblClick:
        focusChatInput();
        break;
    case QEvent::Paint:
        ui->friendList->updateVisualTracking();
        break;
    case QEvent::WindowActivate:
        if (activeChatroomWidget) {
            activeChatroomWidget->resetEventFlags();
            activeChatroomWidget->updateStatusLight();
            setWindowTitle(activeChatroomWidget->getTitle());
            if (airController) {
                // The open conversation's row loses its unread mark with the flag.
                airController->refresh();
            }
        }

        if (eventFlag) {
            resetIcon();
        }
        // Back at the window, the person has seen what the notifications counted.
        if (notificationGenerator) {
            notificationGenerator->onNotificationActivated();
        }

        focusChatInput();

#ifdef Q_OS_MAC
        emit windowStateChanged(windowState());

    case QEvent::WindowStateChange:
        nexus.updateWindowsStates();
#endif
        break;
    default:
        break;
    }

    return QMainWindow::event(e);
}

void Widget::onUserAwayCheck()
{
#ifdef QTOX_PLATFORM_EXT
    uint32_t autoAwayTime = settings.getAutoAwayTime() * 60 * 1000;
    bool online = static_cast<Status::Status>(ui->statusButton->property("status").toInt())
                  == Status::Status::Online;
    bool away = autoAwayTime && Platform::getIdleTime() >= autoAwayTime;

    if (online && away) {
        qDebug() << "auto away activated at" << QTime::currentTime().toString();
        emit statusSet(Status::Status::Away);
        autoAwayActive = true;
    } else if (autoAwayActive && !away) {
        qDebug() << "auto away deactivated at" << QTime::currentTime().toString();
        emit statusSet(Status::Status::Online);
        autoAwayActive = false;
    }
#endif
}

void Widget::onEventIconTick()
{
    if (eventFlag) {
        eventIcon ^= true;
        updateIcons();
    }
}

void Widget::onTryCreateTrayIcon()
{
    static int32_t tries = 15;
    if (!icon && tries--) {
        if (QSystemTrayIcon::isSystemTrayAvailable()) {
            icon = std::unique_ptr<QSystemTrayIcon>(new QSystemTrayIcon);
#if !DESKTOP_NOTIFICATIONS
            // The icon carries the system notifications of this build.
            notifier.setTrayIcon(icon.get());
#endif
            updateIcons();
            trayMenu = new QMenu(this);

            // adding activate to the top, avoids accidentally clicking quit
            trayMenu->addAction(actionShow);
            trayMenu->addSeparator();
            trayMenu->addAction(statusOnline);
            trayMenu->addAction(statusAway);
            trayMenu->addAction(statusBusy);
            trayMenu->addSeparator();
            trayMenu->addAction(actionLogout);
            trayMenu->addAction(actionQuit);
            icon->setContextMenu(trayMenu);

            connect(icon.get(), &QSystemTrayIcon::activated, this, &Widget::onIconClick);

            if (settings.getShowSystemTray()) {
                icon->show();
                // Started in the tray, the window stays there; not once a person opened it from
                // the start window, and not while that window is up.
                if (!heldByStart && !openedFromStart) {
                    setHidden(settings.getAutostartInTray());
                }
            } else if (!heldByStart) {
                show();
            }

#ifdef Q_OS_MAC
            nexus.dockMenu->setAsDockMenu();
#endif
        } else if (!isVisible() && !heldByStart) {
            show();
        }
    } else {
        disconnect(timer, &QTimer::timeout, this, &Widget::onTryCreateTrayIcon);
        if (!icon) {
            qWarning() << "No system tray detected!";
            if (!heldByStart) {
                show();
            }
        }
    }
}

void Widget::setStatusOnline()
{
    if (!ui->statusButton->isEnabled()) {
        return;
    }

    core->setStatus(Status::Status::Online);
}

void Widget::setStatusAway()
{
    if (!ui->statusButton->isEnabled()) {
        return;
    }

    core->setStatus(Status::Status::Away);
}

void Widget::setStatusBusy()
{
    if (!ui->statusButton->isEnabled()) {
        return;
    }

    core->setStatus(Status::Status::Busy);
}

void Widget::onGroupSendFailed(uint32_t groupnumber)
{
    const auto& groupId = groupList->id2Key(groupnumber);
    assert(groupList->findGroup(groupId));

    const auto curTime = QDateTime::currentDateTime();
    auto form = groupChatForms[groupId].data();
    form->addSystemInfoMessage(curTime, SystemMessageType::messageSendFailed, {});
}

void Widget::onFriendTypingChanged(uint32_t friendnumber, bool isTyping)
{
    const auto& friendId = friendList->id2Key(friendnumber);
    Friend* f = friendList->findFriend(friendId);
    if (!f) {
        return;
    }

    chatForms[f->getPublicKey()]->setFriendTyping(isTyping);
    airSource->setTyping(f->getPublicKey(), isTyping);
}

void Widget::onSetShowSystemTray(bool newValue)
{
    if (icon) {
        icon->setVisible(newValue);
    }
}

void Widget::saveWindowGeometry()
{
    if (isFullScreen() || isTogglingFullscreen_) {
        return;
    }

    settings.setWindowGeometry(saveGeometry());
    settings.setWindowState(saveState());
}

void Widget::saveSplitterGeometry()
{
    if (!settings.getSeparateWindow()) {
        settings.setSplitterState(ui->mainSplitter->saveState());
    }
}

void Widget::onSplitterMoved(int pos, int index)
{
    std::ignore = pos;
    std::ignore = index;
    saveSplitterGeometry();
}

void Widget::cycleChats(bool forward)
{
    // The old list is hidden: Ctrl+Tab walks the rows as the Air list shows them.
    const QString next = airController->chatNextTo(forward);
    if (!next.isEmpty()) {
        openAirEntry(next);
    }
}

bool Widget::filterGroups(FilterCriteria index)
{
    switch (index) {
    case FilterCriteria::Offline:
    case FilterCriteria::Friends:
        return true;
    default:
        return false;
    }
}

bool Widget::filterOffline(FilterCriteria index)
{
    switch (index) {
    case FilterCriteria::Online:
    case FilterCriteria::Groups:
        return true;
    default:
        return false;
    }
}

bool Widget::filterOnline(FilterCriteria index)
{
    switch (index) {
    case FilterCriteria::Offline:
    case FilterCriteria::Groups:
        return true;
    default:
        return false;
    }
}

void Widget::reloadTheme()
{
    setStyleSheet("");
    QWidgetList wgts = findChildren<QWidget*>();
    for (auto x : wgts) {
        x->setStyleSheet("");
    }

    setStyleSheet(style.getStylesheet("window/general.css", settings));
    QString statusPanelStyle = style.getStylesheet("window/statusPanel.css", settings);
    ui->tooliconsZone->setStyleSheet(style.getStylesheet("tooliconsZone/tooliconsZone.css", settings));
    ui->statusPanel->setStyleSheet(statusPanelStyle);
    ui->statusHead->setStyleSheet(statusPanelStyle);
    ui->friendList->setStyleSheet(style.getStylesheet("friendList/friendList.css", settings));
    ui->statusButton->setStyleSheet(style.getStylesheet("statusButton/statusButton.css", settings));

    profilePicture->setStyleSheet(style.getStylesheet("window/profile.css", settings));

    // The loop above cleared the Air widgets' own sheets too.
    AirStyle::instance().refresh();
}

void Widget::nextChat()
{
    cycleChats(true);
}

void Widget::previousChat()
{
    cycleChats(false);
}

// Preparing needed to set correct size of icons for GTK tray backend
inline QIcon Widget::prepareIcon(QString path, int w, int h)
{
#ifdef Q_OS_LINUX

    QString desktop = QString::fromUtf8(getenv("XDG_CURRENT_DESKTOP"));
    if (desktop.isEmpty()) {
        desktop = QString::fromUtf8(getenv("DESKTOP_SESSION"));
    }

    desktop = desktop.toLower();
    if (desktop == "xfce" || desktop.contains("gnome") || desktop == "mate" || desktop == "x-cinnamon") {
        if (w > 0 && h > 0) {
            QSvgRenderer renderer(path);

            QPixmap pm(w, h);
            pm.fill(Qt::transparent);
            QPainter painter(&pm);
            renderer.render(&painter, pm.rect());

            return QIcon(pm);
        }
    }
#else
    std::ignore = w;
    std::ignore = h;
#endif
    return QIcon(path);
}

void Widget::searchChats()
{
    QString searchString = ui->searchContactText->text();
    FilterCriteria filter = getFilterCriteria();

    chatListWidget->searchChatrooms(searchString, filterOnline(filter), filterOffline(filter),
                                       filterGroups(filter));

    updateFilterText();
}

void Widget::changeDisplayMode()
{
    filterDisplayGroup->setEnabled(false);

    if (filterDisplayGroup->checkedAction() == filterDisplayActivity) {
        chatListWidget->setMode(FriendListWidget::SortingMode::Activity);
    } else if (filterDisplayGroup->checkedAction() == filterDisplayName) {
        chatListWidget->setMode(FriendListWidget::SortingMode::Name);
    }

    searchChats();
    filterDisplayGroup->setEnabled(true);

    updateFilterText();
}

void Widget::updateFilterText()
{
    QString action = filterDisplayGroup->checkedAction()->text();
    QString text = filterGroup->checkedAction()->text();
    text = action + QStringLiteral(" | ") + text;
    ui->searchContactFilterBox->setText(text);
}

Widget::FilterCriteria Widget::getFilterCriteria() const
{
    QAction* checked = filterGroup->checkedAction();

    if (checked == filterOnlineAction)
        return FilterCriteria::Online;
    else if (checked == filterOfflineAction)
        return FilterCriteria::Offline;
    else if (checked == filterFriendsAction)
        return FilterCriteria::Friends;
    else if (checked == filterGroupsAction)
        return FilterCriteria::Groups;

    return FilterCriteria::All;
}

void Widget::searchCircle(CircleWidget& circleWidget)
{
    if (chatListWidget->getMode() == FriendListWidget::SortingMode::Name) {
        FilterCriteria filter = getFilterCriteria();
        QString text = ui->searchContactText->text();
        circleWidget.search(text, true, filterOnline(filter), filterOffline(filter));
    }
}

bool Widget::groupsVisible() const
{
    FilterCriteria filter = getFilterCriteria();
    return !filterGroups(filter);
}

void Widget::friendListContextMenu(const QPoint& pos)
{
    QMenu menu(this);
    QAction* createGroupAction = menu.addAction(tr("Create new group..."));
    QAction* joinGroupAction = menu.addAction(tr("Join group by ID..."));
    QAction* addCircleAction = menu.addAction(tr("Add new circle..."));
    QAction* chosenAction = menu.exec(ui->friendList->mapToGlobal(pos));

    if (chosenAction == addCircleAction) {
        chatListWidget->addCircleWidget();
    } else if (chosenAction == createGroupAction) {
        openNewGroup();
    } else if (chosenAction == joinGroupAction) {
        openJoinGroup();
    }
}

void Widget::friendRequestsUpdate()
{
    delete friendRequestsButton;
    friendRequestsButton = nullptr;
}

void Widget::groupInvitesUpdate()
{
    if (unreadGroupInvites == 0) {
        delete groupInvitesButton;
        groupInvitesButton = nullptr;
    } else if (!groupInvitesButton) {
        groupInvitesButton = new QPushButton(this);
        groupInvitesButton->setObjectName("green");
        ui->statusLayout->insertWidget(2, groupInvitesButton);

        connect(groupInvitesButton, &QPushButton::released, this, &Widget::onGroupClicked);
    }

    if (groupInvitesButton) {
        groupInvitesButton->setText(tr("%n new group invite(s)", "", unreadGroupInvites));
    }
}

void Widget::groupInvitesClear()
{
    unreadGroupInvites = 0;
    groupInvitesUpdate();
}

void Widget::setActiveToolMenuButton(ActiveToolMenuButton newActiveButton)
{
    ui->addButton->setChecked(newActiveButton == ActiveToolMenuButton::AddButton);
    ui->addButton->setDisabled(newActiveButton == ActiveToolMenuButton::AddButton);
    ui->groupButton->setChecked(newActiveButton == ActiveToolMenuButton::GroupButton);
    ui->groupButton->setDisabled(newActiveButton == ActiveToolMenuButton::GroupButton);
    ui->transferButton->setChecked(newActiveButton == ActiveToolMenuButton::TransferButton);
    ui->transferButton->setDisabled(newActiveButton == ActiveToolMenuButton::TransferButton);
    ui->settingsButton->setChecked(newActiveButton == ActiveToolMenuButton::SettingButton);
    ui->settingsButton->setDisabled(newActiveButton == ActiveToolMenuButton::SettingButton);
}

void Widget::retranslateUi()
{
    ui->retranslateUi(this);
    setUsername(core->getUsername());
    setStatusMessage(core->getStatusMessage());

    filterDisplayName->setText(tr("By Name"));
    filterDisplayActivity->setText(tr("By Activity"));
    filterAllAction->setText(tr("All"));
    filterOnlineAction->setText(tr("Online"));
    filterOfflineAction->setText(tr("Offline"));
    filterFriendsAction->setText(tr("Friends"));
    filterGroupsAction->setText(tr("Groups"));
    ui->searchContactText->setPlaceholderText(tr("Search Contacts"));
    updateFilterText();

    statusOnline->setText(tr("Online", "Button to set your status to 'Online'"));
    statusAway->setText(tr("Away", "Button to set your status to 'Away'"));
    statusBusy->setText(tr("Busy", "Button to set your status to 'Busy'"));
    actionLogout->setText(AirStrings::text(AirStrings::Text::YouLogout));
    actionQuit->setText(tr("Exit", "Tray action menu to exit Tox"));
    actionShow->setText(tr("Show", "Tray action menu to show Khandaq window"));

    if (!settings.getSeparateWindow() && settingsPage && settingsPage->isVisible()) {
        setWindowTitle(fromDialogType(DialogType::SettingDialog));
    }
    if (!settings.getSeparateWindow() && youPage && youPage->isVisible()) {
        setWindowTitle(fromDialogType(DialogType::ProfileDialog));
    }

    friendRequestsUpdate();
    groupInvitesUpdate();


#ifdef Q_OS_MAC
    nexus.retranslateUi();

    filterMenu->menuAction()->setText(tr("Filter..."));

    fileMenu->setText(tr("File"));
    editMenu->setText(tr("Edit"));
    contactMenu->setText(tr("Contacts"));
    changeStatusMenu->menuAction()->setText(tr("Change status"));
    // Named as the page it opens is named.
    editProfileAction->setText(AirStrings::text(AirStrings::Text::You));
    logoutAction->setText(AirStrings::text(AirStrings::Text::YouLogout));
    addContactAction->setText(tr("Add contact..."));
    nextConversationAction->setText(tr("Next conversation"));
    previousConversationAction->setText(tr("Previous conversation"));
#endif
}

void Widget::focusChatInput()
{
    if (activeChatroomWidget) {
        if (const Friend* f = activeChatroomWidget->getFriend()) {
            chatForms[f->getPublicKey()]->focusInput();
        } else if (Group* g = activeChatroomWidget->getGroup()) {
            groupChatForms[g->getPersistentId()]->focusInput();
        }
    }
}

void Widget::refreshPeerListsLocal(const QString& username)
{
    for (Group* g : groupList->getAllGroups()) {
        g->updateUsername(core->getSelfPublicKey(), username);
    }
}

void Widget::connectCircleWidget(CircleWidget& circleWidget)
{
    connect(&circleWidget, &CircleWidget::newContentDialog, this, &Widget::registerContentDialog);
}

void Widget::connectFriendWidget(FriendWidget& friendWidget)
{
    connect(&friendWidget, &FriendWidget::updateFriendActivity, this, &Widget::updateFriendActivity);
}

void Widget::registerIpcHandlers()
{
    ipc.registerEventHandler(activateHandlerKey, &toxActivateEventHandler, this);
    ipc.registerEventHandler(saveHandlerKey, &ToxSave::toxSaveEventHandler, toxSave.get());
    ipc.registerEventHandler(uriHandlerKey, &toxUriEventHandler, this);
}

bool Widget::handleToxSave(const QString& path)
{
    return toxSave->handleToxSave(path);
}

bool Widget::handleToxUri(const QString& uri)
{
    if (!uri.startsWith(QStringLiteral("tox:"), Qt::CaseInsensitive)) {
        return false;
    }
    // qTox asked in a window of its own, with no parent and its own words for the ID and the
    // request; New contact checks the ID as it is pasted and says what is wrong next to it.
    forceShow();
    openNewContact(uri);
    return true;
}

void Widget::setupAir()
{
    AirStyle::instance().follow(settings, style, this);

    airBand = new AirBand(ui->centralwidget);
    ui->verticalLayout_4->insertWidget(0, airBand);
    // The window keeps the band's width as its least, as the band's pills come and go.
    airBand->installEventFilter(this);

    // The Air list takes the place of the old head, search field, tabs, list and tool bar. They
    // stay in the window, hidden, so everything that still talks to them keeps working.
    ui->statusHead->hide();
    ui->searchContactsContainer->hide();
    ui->chatTabsContainer->hide();
    ui->friendList->hide();
    ui->tooliconsZone->hide();
    airList = new AirListView(ui->statusPanel);
    ui->statusLayout->addWidget(airList, 1);

    airSource = new AirSource(*core, profile, settings, *friendList, *groupList, this);
    airController = new AirController(*airSource, *airBand, *airList, settings, this);
    airLocal = new AirLocal(settings, this);
    // Requests and invitations wait in the local store until they are answered (E12).
    airSource->setLocal(airLocal);

    // New contact (E11): a panel that drops over the list column, under the band.
    AirNewContact::Lookup lookup;
    lookup.ownAddress = [this] { return core->getSelfId().toString(); };
    lookup.isContact = [this](const QString& key) {
        return friendList->findFriend(ToxPk(key)) != nullptr;
    };
    lookup.hasRequest = [this](const QString& key) {
        const QVector<Air::Request> requests = airSource->requests();
        return std::any_of(requests.cbegin(), requests.cend(),
                           [&key](const Air::Request& request) { return request.publicKey == key; });
    };
    lookup.isBlocked = [this](const QString& key) { return airLocal->isBlocked(ToxPk(key)); };
    lookup.contactName = [this](const QString& key) {
        const Friend* const frnd = friendList->findFriend(ToxPk(key));
        return frnd ? frnd->getDisplayedName() : QString();
    };
    newContact = new AirNewContact(std::move(lookup), ui->statusPanel, ui->centralwidget);
    connect(newContact, &AirNewContact::sendRequested, this,
            [this](const QString& address, const QString& message) {
                emit friendRequested(ToxId(address), message);
            });
    connect(newContact, &AirNewContact::openChatRequested, this,
            [this](const QString& key) { openAirEntry(AirSource::friendEntryId(ToxPk(key))); });
    connect(newContact, &AirNewContact::openRequestsRequested, this,
            [this] { airController->setFilter(Air::Filter::Requests); });
    connect(newContact, &AirNewContact::importRequested, this, &Widget::openImportContacts);
    connect(newContact, &AirNewContact::openChanged, this, [this](bool open) {
        // Folded away, the panel hands the keyboard back to the command line it came from.
        if (!open) {
            airBand->focusCommandLine();
        }
    });
    connect(core, &Core::requestSent, this, &Widget::onRequestSent);
    connect(airSource, &AirSource::changed, this, &Widget::followNewContact);

    // New group (E15): the same kind of panel, which also joins a group by its key.
    AirNewGroup::Lookup groupLookup;
    groupLookup.candidates = [this] { return inviteCandidates(); };
    groupLookup.hasGroup = [this](const QString& key) {
        return groupList->findGroup(GroupId(QByteArray::fromHex(key.toLatin1()))) != nullptr;
    };
    newGroup = new AirNewGroup(std::move(groupLookup), ui->statusPanel, ui->centralwidget);
    connect(newGroup, &AirNewGroup::createRequested, this, &Widget::createAirGroup);
    connect(newGroup, &AirNewGroup::joinRequested, this, &Widget::joinAirGroup);
    connect(newGroup, &AirNewGroup::openGroupRequested, this, [this](const QString& key) {
        newGroup->close();
        openAirEntry(AirSource::groupEntryId(GroupId(QByteArray::fromHex(key.toLatin1()))));
    });
    connect(newGroup, &AirNewGroup::openChanged, this, [this](bool open) {
        if (!open) {
            airBand->focusCommandLine();
        }
    });

    // The start of an ID in the command line (E3): a row over the list offers New contact.
    commandIdRow = new AirCommandIdRow(ui->statusPanel);
    commandIdRow->hide();
    ui->statusLayout->insertWidget(ui->statusLayout->indexOf(airList), commandIdRow);
    connect(airBand, &AirBand::idStartChanged, this, &Widget::showCommandIdStart);
    connect(commandIdRow, &AirCommandIdRow::clicked, this, [this] {
        const QString idStart = commandIdRow->idStart();
        airBand->commandLine()->clear();
        openNewContact(idStart);
    });

    // Saved Messages: kept in the profile database whether the history is on or off.
    History* const history = profile.getHistory();
    SavedNotes* const savedNotes = history ? history->getSavedNotes() : nullptr;
    notesChat.reset(new NotesChat());
    notesChat->setName(AirStrings::text(AirStrings::Text::SavedTitle));
    notesDispatcher.reset(new NotesDispatcher(savedNotes));
    notesLog = makeNotesLog(savedNotes, *notesDispatcher, *core, *friendList, *groupList);
    notesForm =
        new NotesForm(*core, *notesChat, *notesLog, *notesDispatcher, *documentCache, *smileyPack,
                      settings, style, *messageBoxManager, *friendList, *groupList);
    airSource->watchNotes(*notesLog);

    connect(airController, &AirController::chatRequested, this, &Widget::openAirEntry);
    connect(airController, &AirController::newContactRequested, this, &Widget::openNewContact);
    connect(airController, &AirController::favoriteToggled, this,
            [this](const QString& entryId, bool favorite) {
                settings.setFavoriteChat(entryId, favorite);
                settings.savePersonal();
            });
    connect(airController, &AirController::entryMenuRequested, this, &Widget::showAirEntryMenu);
    connect(airController, &AirController::requestAccepted, this, &Widget::acceptAirRequest);
    connect(airController, &AirController::requestDeclined, this, &Widget::declineAirRequest);
    connect(airController, &AirController::backgroundMenuRequested, this,
            &Widget::showAirBackgroundMenu);
    connect(airBand, &AirBand::profileRequested, this, &Widget::showProfile);
    // The line under your name opens the state of the network, in Settings.
    connect(airBand, &AirBand::networkRequested, this, [this] {
        onShowSettings();
        settingsScreen().showSection(AirSettingsPage::Section::Network);
    });
    connect(airBand, &AirBand::statusMenuRequested, this, &Widget::showStatusMenu);
    connect(airBand, &AirBand::groupsRequested, this, [this] { openNewGroup(); });
    connect(airBand, &AirBand::transfersRequested, this, &Widget::onTransferClicked);
    connect(airBand, &AirBand::settingsRequested, this, &Widget::onShowSettings);
    connect(&AirStyle::instance(), &AirStyle::changed, this, &Widget::restyleAir);

    // "/" reaches the command line from anywhere but a text field: fields take the key for
    // themselves (ShortcutOverride), so a slash typed in a message stays a slash.
    auto* const commandShortcut = new QShortcut(QKeySequence(Qt::Key_Slash), this);
    connect(commandShortcut, &QShortcut::activated, airBand, &AirBand::focusCommandLine);

    airController->setSelfName(core->getUsername());
    restyleAir();

#ifdef KHANDAQ_SCREENSHOT_HOOK
    const QString shotScript = qEnvironmentVariable("KHANDAQ_SHOT_SCRIPT");
    if (!shotScript.isEmpty()) {
        // Behind the start window, the script waits for the window to show (showFromStart).
        if (heldByStart) {
            heldShotScript = shotScript;
        } else {
            runShotScript(shotScript);
        }
    }
#endif
}

void Widget::restyleAir()
{
    // The hairline between the list and the conversation; the legacy sheet paints the handle white.
    const QColor line = AirStyle::instance().color(AirTokens::Color::Line);
    ui->mainSplitter->setStyleSheet(
        QStringLiteral("QSplitter::handle { background: %1; }").arg(line.name(QColor::HexArgb)));
}

int Widget::airMinimumWidth() const
{
    return airBand ? airBand->minimumSizeHint().width() : 0;
}

void Widget::refreshLeastSize()
{
    // The list and the conversation side by side take what the old window took; the list alone,
    // with conversations in windows of their own, its tool bar. Either way the Air band fits whole,
    // with the pills it shows now: the window grows when one comes in, rather than cutting it.
    const int floor = contentLayout ? leastSideBySideWidth : ui->tooliconsZone->sizeHint().width();
    setMinimumSize(std::max(floor, airMinimumWidth()),
                   contentLayout ? leastSideBySideHeight : leastSingleHeight);
}

QString Widget::airEntryIdOf(const GenericChatroomWidget* widget) const
{
    if (!widget) {
        return QString();
    }
    if (const Friend* frnd = widget->getFriend()) {
        return AirSource::friendEntryId(frnd->getPublicKey());
    }
    if (const Group* group = widget->getGroup()) {
        return AirSource::groupEntryId(group->getPersistentId());
    }
    return QString();
}

GenericChatroomWidget* Widget::chatroomWidgetOf(const QString& entryId) const
{
    const QString key = entryId.mid(2);
    if (entryId.startsWith(QStringLiteral("f:"))) {
        return friendWidgets.value(ToxPk(key), nullptr);
    }
    if (entryId.startsWith(QStringLiteral("c:"))) {
        return groupWidgets.value(GroupId(QByteArray::fromHex(key.toLatin1())), nullptr);
    }
    return nullptr;
}

void Widget::openAirEntry(const QString& entryId)
{
    if (entryId == AirSource::savedEntryId()) {
        openNotes();
        return;
    }
    GenericChatroomWidget* widget = chatroomWidgetOf(entryId);
    if (widget) {
        // The same path as a click on the old list: it opens the chat and focuses its input.
        emit widget->chatroomWidgetClicked(widget);
    }
}

void Widget::openNotes()
{
    if (!notesForm) {
        return;
    }
    if (settings.getSeparateWindow()) {
        if (notesForm->isVisible()) {
            notesForm->window()->activateWindow();
        } else {
            notesForm->show(createContentDialog(DialogType::NotesDialog));
        }
        notesForm->focusInput();
        return;
    }
    hideMainForms(nullptr);
    notesForm->show(contentLayout);
    setWindowTitle(AirStrings::text(AirStrings::Text::SavedTitle));
    if (airController) {
        airController->setOpenChat(AirSource::savedEntryId());
    }
    notesForm->focusInput();
}

void Widget::openNewContact(const QString& address)
{
    if (newGroup) {
        newGroup->close();
    }
    if (newContact) {
        newContact->open(address);
    }
}

void Widget::openNewGroup(const QString& inviteKey)
{
    if (newContact) {
        newContact->close();
    }
    if (newGroup) {
        newGroup->openCreate(inviteKey);
    }
}

void Widget::openJoinGroup(const QString& text)
{
    if (newContact) {
        newContact->close();
    }
    if (newGroup) {
        newGroup->openJoin(text);
    }
}

QVector<Air::InviteCandidate> Widget::inviteCandidates() const
{
    QVector<Air::InviteCandidate> candidates;
    for (Friend* const frnd : friendList->getAllFriends()) {
        Air::InviteCandidate candidate;
        candidate.friendId = static_cast<int>(frnd->getId());
        candidate.publicKey = frnd->getPublicKey().toString();
        candidate.name = frnd->getDisplayedName();
        candidate.online = Status::isOnline(frnd->getStatus());
        candidates.append(candidate);
    }
    return candidates;
}

void Widget::createAirGroup(const QString& name, Air::GroupPrivacy privacy,
                            const QVector<Air::InviteCandidate>& invited)
{
    // By invitation only is a private group in NGC; Open is a public one, found by its key.
    const int groupNumber = core->createGroup(name, privacy == Air::GroupPrivacy::InviteOnly
                                                        ? TOX_GROUP_PRIVACY_STATE_PRIVATE
                                                        : TOX_GROUP_PRIVACY_STATE_PUBLIC);
    if (groupNumber < 0) {
        newGroup->createFailed();
        return;
    }
    // Core::emptyGroupCreated has put the group in the list already.
    const GroupId groupId = core->getGroupPersistentId(static_cast<uint32_t>(groupNumber));
    int sent = 0;
    for (const Air::InviteCandidate& candidate : invited) {
        sent += candidate.friendId >= 0
                && core->groupInviteFriend(static_cast<uint32_t>(candidate.friendId), groupNumber);
    }
    newGroup->close();
    openAirEntry(AirSource::groupEntryId(groupId));
    if (!invited.isEmpty()) {
        using AirStrings::Text;
        if (sent == invited.size()) {
            AirToast::post(airList, AirStrings::text(Text::InviteSent, sent));
        } else {
            AirDialog::notice(this,
                              AirStrings::text(sent == 0 ? Text::ErrorInviteTitle
                                                         : Text::ErrorInviteSomeTitle),
                              AirStrings::text(Text::InviteRetry));
        }
    }
}

void Widget::joinAirGroup(const QString& groupKey)
{
    const uint32_t groupNumber = core->joinGroupByChatIdHex(groupKey);
    if (groupNumber == std::numeric_limits<uint32_t>::max()) {
        newGroup->joinFailed();
        return;
    }
    newGroup->close();
    // Core::groupJoined has put the group in the list: it opens, waiting for the members.
    const Group* const group = groupList->findGroup(core->getGroupPersistentId(groupNumber));
    if (group) {
        openAirEntry(AirSource::groupEntryId(group->getPersistentId()));
    }
}

void Widget::openImportContacts()
{
    addFriendForm->setMode(AddFriendForm::ImportContacts);
    onAddClicked();
}

void Widget::showNoChat()
{
    if (!contentLayout) {
        return;
    }
    hideMainForms(nullptr);
    if (!noChatView) {
        noChatView = new AirNoChat();
    }
    contentLayout->setFlush(true);
    contentLayout->mainContent->layout()->addWidget(noChatView);
    noChatView->show();
    setWindowTitle(QString());
}

void Widget::showCommandIdStart(const QString& hex)
{
    if (!commandIdRow || !newContact) {
        return;
    }
    if (hex.isEmpty()) {
        commandIdRow->hide();
        return;
    }
    commandIdRow->setIdStart(hex, newContact->commandVerdict(hex));
    commandIdRow->show();
}

void Widget::onRequestSent(const ToxPk& friendPk, const QString& message)
{
    std::ignore = message;
    // Sending a request lifts the block of the key (DESIGN.md, "Block, on every client").
    if (airLocal && airLocal->isBlocked(friendPk)) {
        airLocal->unblock(friendPk.toString());
    }
    if (newContact) {
        newContact->requestSent(friendPk.toString());
    }
}

void Widget::acceptAirRequest(const QString& requestId)
{
    if (requestId.startsWith(Air::Request::invitationId(QString()))) {
        joinAirInvite(requestId.mid(Air::Request::invitationId(QString()).size()));
        return;
    }
    const ToxPk friendPk(requestId);
    if (!airLocal || !airLocal->hasRequest(friendPk)) {
        return;
    }
    // The card leaves at once; the person's row comes when Core has added them (addFriend), and
    // the card comes back if it could not (addFriendFailed).
    airSource->setRequestHidden(requestId, true);
    emit friendRequestAccepted(friendPk);
}

void Widget::declineAirRequest(const QString& requestId)
{
    const QString prefix = Air::Request::invitationId(QString());
    const bool invitation = requestId.startsWith(prefix);
    const QString inviteId = invitation ? requestId.mid(prefix.size()) : QString();
    if (!airLocal
        || (invitation ? airLocal->invite(inviteId).isNull()
                       : !airLocal->hasRequest(ToxPk(requestId)))) {
        return;
    }
    // Decline asks nothing: the card leaves, and only a toast gone without Undo removes the request
    // for good. Tox has no refusal, so nothing goes back to the person; closing the window in those
    // seconds keeps the request for the next start.
    airSource->setRequestHidden(requestId, true);
    using AirStrings::Text;
    QPointer<AirSource> source = airSource;
    QPointer<AirLocal> local = airLocal;
    AirToast::post(
        airList, AirStrings::text(invitation ? Text::InviteDeclined : Text::RequestDeclined),
        AirStrings::text(Text::Undo),
        [source, requestId] {
            if (source) {
                source->setRequestHidden(requestId, false);
            }
        },
        [source, local, requestId, invitation, inviteId] {
            if (local) {
                if (invitation) {
                    local->removeInvite(inviteId);
                } else {
                    local->removeRequest(ToxPk(requestId));
                }
            }
            if (source) {
                source->setRequestHidden(requestId, false);
            }
        });
}

void Widget::joinAirInvite(const QString& inviteId)
{
    const Air::LocalStore::WaitingInvite invite = airLocal->invite(inviteId);
    if (invite.isNull()) {
        return;
    }
    Friend* const inviter = friendList->findFriend(ToxPk(invite.inviterKey));
    if (!inviter) {
        // The inviter is gone, and Join went through them.
        airLocal->removeInvite(inviteId);
        return;
    }
    using AirStrings::Text;
    // toxcore joins through the inviter, which fails while they are away: the card stays where it
    // is, and it lights up with the words on it that say so, next to Join (E21).
    if (!airSource->reachOf(*inviter).isLive()) {
        airController->lightRequest(Air::Request::invitationId(inviteId));
        return;
    }
    const uint32_t groupNumber =
        core->joinGroupchat(GroupInvite(inviter->getId(), invite.data, invite.groupName));
    if (groupNumber == std::numeric_limits<uint32_t>::max()) {
        AirDialog::notice(this, AirStrings::text(Text::ErrorJoinGroupTitle),
                          AirStrings::text(Text::TryAgain));
        return;
    }
    airLocal->removeInvite(inviteId);
    // The group is in the list now (Core::groupJoined): it opens, as a joined conversation does.
    const Group* const group = groupList->findGroup(core->getGroupPersistentId(groupNumber));
    if (group) {
        openAirEntry(AirSource::groupEntryId(group->getPersistentId()));
    }
}

void Widget::followNewContact()
{
    const QString key = newContact ? newContact->awaitedKey() : QString();
    const Friend* const frnd = key.isEmpty() ? nullptr : friendList->findFriend(ToxPk(key));
    if (!frnd) {
        return;
    }
    const Air::Reach reach = airSource->reachOf(*frnd);
    if (!reach.isLive()) {
        return;
    }
    // Until their own name comes, the list calls them by the end of their key: not a name yet.
    QString name = frnd->getDisplayedName();
    if (name == key.right(6)) {
        name.clear();
    }
    newContact->personAccepted(name, Air::linkState(reach));
}

void Widget::showAirEntryMenu(const QString& entryId, const QPoint& globalPos)
{
    GenericChatroomWidget* widget = chatroomWidgetOf(entryId);
    if (!widget) {
        return;
    }
    using AirStrings::Text;
    const bool favorite = settings.isFavoriteChat(entryId);
    QAction favoriteAction(AirStrings::text(favorite ? Text::RemoveFavorite : Text::AddFavorite));
    connect(&favoriteAction, &QAction::triggered, this, [this, entryId, favorite] {
        settings.setFavoriteChat(entryId, !favorite);
        settings.savePersonal();
    });
    // The chat's own menu, as on the old list, with the favourite first.
    if (auto* friendWidget = qobject_cast<FriendWidget*>(widget)) {
        friendWidget->showContextMenu(globalPos, {&favoriteAction});
    } else if (auto* groupWidget = qobject_cast<GroupWidget*>(widget)) {
        groupWidget->showContextMenu(globalPos, {&favoriteAction});
    }
}

void Widget::showAirBackgroundMenu(const QPoint& globalPos)
{
    // Creating and joining a group are found in one place: New group, whose panel also joins.
    QMenu menu(this);
    QAction* const newContactAction = menu.addAction(AirStrings::text(AirStrings::Text::NewContact));
    QAction* const newGroupAction = menu.addAction(AirStrings::text(AirStrings::Text::NewGroupTitle));
    QAction* const chosenAction = menu.exec(globalPos);
    if (chosenAction == newContactAction) {
        openNewContact(QString());
    } else if (chosenAction == newGroupAction) {
        openNewGroup();
    }
}

void Widget::showStatusMenu(const QPoint& globalPos)
{
    QMenu menu(this);
    menu.addAction(statusOnline);
    menu.addAction(statusAway);
    menu.addAction(statusBusy);
    menu.exec(globalPos);
}

#ifdef KHANDAQ_SCREENSHOT_HOOK
/**
 * Test builds only (cmake -DKHANDAQ_SCREENSHOT_HOOK=ON): plays a script of window states and saves
 * the window to PNG files, for the redesign review pages. One command per line:
 * wait <ms> | size <w> <h> | theme system|light|dark | lang <code> | filter <name> |
 * query <text> | open <title> | send <title>|<text> | favorite|unfavorite <title> |
 * compact on|off | settings [section] | reveal <object name> | add | click <object name> |
 * press <name> | type <text> | hours <title>|<hours ago,...> | notedelete <text> |
 * newcontact [text] | toxlink <tox:...> | fill <object name>|<text> | key <key> | focuslist |
 * card accept|decline <words> | newgroup [contact] | joingroup [text] | private <member> |
 * groupask <member>|moderator|member|observer|remove | sendfile <title>|<path> |
 * access microphone|camera granted|undecided|denied | network offline|report <path> |
 * waitband <words>|<ms> | band hold|leave|open | minimize | restore | link <address> | clear |
 * quote <text> | spoken list|log | grab <file.png> | grabpopup <file.png> |
 * frames <count> <ms> <path prefix> | audit <label> | idle <ms> <label> |
 * least <name> <depth> <label> | quit
 * The title "saved" names the saved messages; the group commands act on the open group. access
 * sets what the system answers before a call, without asking it. A click lands once the line is
 * done, so a menu it opens does not hold the script; grabpopup saves that menu, and key goes to it.
 * waitband holds the script until news that comes over the network is up as the band. spoken writes
 * what a screen reader is offered in the list or the open transcript into the log, one line each.
 * frames, audit and idle are the ones of AirWalk: the window saved while a motion runs, the focus
 * order, names and room of its controls, and what it paints while nothing happens.
 */
void Widget::runShotScript(const QString& path)
{
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly | QIODevice::Text)) {
        qWarning() << "Screenshot script not readable:" << path;
        return;
    }
    auto lines = std::make_shared<QStringList>(QString::fromUtf8(file.readAll()).split('\n'));
    const auto entryByTitle = [this](const QString& title) {
        if (title == QStringLiteral("saved")) {
            return AirSource::savedEntryId();
        }
        for (Friend* frnd : friendList->getAllFriends()) {
            if (frnd->getDisplayedName() == title) {
                return AirSource::friendEntryId(frnd->getPublicKey());
            }
        }
        for (Group* group : groupList->getAllGroups()) {
            if (group->getDisplayedName() == title) {
                return AirSource::groupEntryId(group->getPersistentId());
            }
        }
        return QString();
    };
    const auto openGroupForm = [this]() -> QSharedPointer<GroupChatForm> {
        const Group* const group = activeChatroomWidget ? activeChatroomWidget->getGroup() : nullptr;
        return group ? groupChatForms.value(group->getPersistentId())
                     : QSharedPointer<GroupChatForm>();
    };
    auto* const runner = new QTimer(this);
    runner->setSingleShot(true);
    // How long waitband has held the script so far.
    auto waited = std::make_shared<QElapsedTimer>();
    connect(runner, &QTimer::timeout, this, [this, lines, runner, entryByTitle, openGroupForm, waited] {
        int delay = 150;
        while (!lines->isEmpty()) {
            const QString line = lines->takeFirst().trimmed();
            if (line.isEmpty() || line.startsWith('#')) {
                continue;
            }
            const QString command = line.section(' ', 0, 0);
            const QString rest = line.section(' ', 1);
            qDebug() << "Screenshot script:" << line;
            if (command == QStringLiteral("wait")) {
                delay = rest.toInt();
            } else if (command == QStringLiteral("size")) {
                showNormal();
                resize(rest.section(' ', 0, 0).toInt(), rest.section(' ', 1, 1).toInt());
            } else if (command == QStringLiteral("theme")) {
                settings.setThemeMode(rest == QStringLiteral("dark")    ? Settings::ThemeMode::Dark
                                      : rest == QStringLiteral("light") ? Settings::ThemeMode::Light
                                                                        : Settings::ThemeMode::System);
                delay = 600;
            } else if (command == QStringLiteral("lang")) {
                settings.setTranslation(rest);
                Translator::translate(rest);
                delay = 600;
            } else if (command == QStringLiteral("filter")) {
                airController->setFilter(Air::filterFromName(rest));
            } else if (command == QStringLiteral("query")) {
                airBand->commandLine()->setText(rest);
            } else if (command == QStringLiteral("open")) {
                openAirEntry(entryByTitle(rest));
            } else if (command == QStringLiteral("send")) {
                // send <title>|<text>: titles may hold spaces.
                const QString entryId = entryByTitle(rest.section('|', 0, 0).trimmed());
                const QString text = rest.section('|', 1);
                if (entryId == AirSource::savedEntryId()) {
                    notesDispatcher->sendMessage(false, text);
                } else if (entryId.startsWith(QStringLiteral("f:"))) {
                    const auto dispatcher = friendMessageDispatchers.value(ToxPk(entryId.mid(2)));
                    if (dispatcher) {
                        dispatcher->sendMessage(false, text);
                    }
                } else if (entryId.startsWith(QStringLiteral("c:"))) {
                    const auto dispatcher = groupMessageDispatchers.value(
                        GroupId(QByteArray::fromHex(entryId.mid(2).toLatin1())));
                    if (dispatcher) {
                        dispatcher->sendMessage(false, text);
                    }
                }
            } else if (command == QStringLiteral("notedelete")) {
                // notedelete <text>: asks to delete the newest saved message with this text.
                bool found = false;
                for (auto idx = notesLog->getNextIdx(); idx > notesLog->getFirstIdx() && !found;) {
                    idx = idx - 1;
                    const ChatLogItem& item = notesLog->at(idx);
                    if (notesLog->isShown(idx) && item.getContentType() == ChatLogItem::ContentType::message
                        && item.getContentAsMessage().message.content == rest) {
                        notesForm->askToDelete(idx);
                        found = true;
                    }
                }
                if (!found) {
                    qWarning() << "No saved message to delete:" << rest;
                }
            } else if (command == QStringLiteral("favorite")
                       || command == QStringLiteral("unfavorite")) {
                settings.setFavoriteChat(entryByTitle(rest), command == QStringLiteral("favorite"));
            } else if (command == QStringLiteral("network")) {
                // network offline: the node reads this computer's network as gone, as without one.
                // network report <path>: the network part of the log for support, into a file.
                if (rest == QStringLiteral("offline")) {
                    airSource->forgetNetworkForWalk();
                    // The next step comes at once, so a grab after a short wait catches the
                    // change halfway through its cross-fade.
                    delay = 0;
                } else if (rest.startsWith(QStringLiteral("report "))) {
                    QFile report(rest.mid(7));
                    if (!report.open(QIODevice::WriteOnly | QIODevice::Truncate)
                        || report.write(airSource->networkReport().toUtf8()) < 0) {
                        qWarning() << "Network report not saved:" << report.fileName();
                    }
                } else {
                    qWarning() << "No network state named" << rest;
                }
            } else if (command == QStringLiteral("compact")) {
                settings.setCompactLayout(rest == QStringLiteral("on"));
            } else if (command == QStringLiteral("settings")) {
                // settings [section]: the settings page, at a section named as its object names
                // say it (appearance, notifications, privacy, transfer, network, window, about).
                onShowSettings();
                AirSettingsPage::Section section = AirSettingsPage::Section::Appearance;
                if (!rest.isEmpty() && !AirSettingsPage::sectionNamed(rest, &section)) {
                    qWarning() << "No settings section named" << rest;
                }
                settingsScreen().showSection(section);
            } else if (command == QStringLiteral("reveal")) {
                // reveal <object name>: scrolls the content column to a widget, a settings group.
                QWidget* const target =
                    contentLayout ? contentLayout->mainContent->findChild<QWidget*>(rest) : nullptr;
                QScrollArea* scroller = nullptr;
                for (QWidget* parent = target ? target->parentWidget() : nullptr;
                     parent && !scroller; parent = parent->parentWidget()) {
                    scroller = qobject_cast<QScrollArea*>(parent);
                }
                if (scroller) {
                    scroller->ensureWidgetVisible(target);
                } else {
                    qWarning() << "Nothing to reveal:" << rest;
                }
            } else if (command == QStringLiteral("add")) {
                onAddClicked();
            } else if (command == QStringLiteral("newcontact")) {
                // newcontact [text]: New contact, with text in its ID field as if pasted.
                openNewContact(rest);
            } else if (command == QStringLiteral("toxlink")) {
                // toxlink <tox:...>: a tox: link as another instance hands it over.
                if (!handleToxUri(rest)) {
                    qWarning() << "Not a tox: link:" << rest;
                }
            } else if (command == QStringLiteral("toxsave")) {
                // toxsave <path>: a profile file opened with the app while a profile is open.
                askImportProfile(rest);
            } else if (command == QStringLiteral("newgroup")) {
                // newgroup [contact]: New group, with that contact chosen for the invitations.
                const QString entryId = rest.isEmpty() ? QString() : entryByTitle(rest);
                openNewGroup(entryId.startsWith(QStringLiteral("f:")) ? entryId.mid(2) : QString());
            } else if (command == QStringLiteral("joingroup")) {
                // joingroup [text]: New group on joining by key, with text in the key field.
                openJoinGroup(rest);
            } else if (command == QStringLiteral("private")) {
                // private <member>: the private messages with that member of the open group.
                const QSharedPointer<GroupChatForm> form = openGroupForm();
                const Group* const group =
                    activeChatroomWidget ? activeChatroomWidget->getGroup() : nullptr;
                QString key;
                if (group) {
                    const int number = static_cast<int>(group->getId());
                    for (const uint32_t peerId : core->getGroupPeerList(number)) {
                        if (core->getGroupPeerName(number, static_cast<int>(peerId)) == rest) {
                            key = core->getGroupPeerPk(number, static_cast<int>(peerId)).toString();
                        }
                    }
                }
                if (form && !key.isEmpty()) {
                    form->openPrivate(key);
                } else {
                    qWarning() << "No member to write to:" << rest;
                }
            } else if (command == QStringLiteral("groupask")) {
                // groupask <member>|<action>: the question before an action on that member.
                const QSharedPointer<GroupChatForm> form = openGroupForm();
                const QString verb = rest.section('|', 1).trimmed();
                const Air::MemberAction action =
                    verb == QStringLiteral("moderator")  ? Air::MemberAction::MakeModerator
                    : verb == QStringLiteral("member")   ? Air::MemberAction::MakeMember
                    : verb == QStringLiteral("observer") ? Air::MemberAction::MakeObserver
                                                         : Air::MemberAction::Remove;
                if (form) {
                    form->askMemberActionForShots(rest.section('|', 0, 0).trimmed(), action);
                }
            } else if (command == QStringLiteral("fill")) {
                // fill <object name>|<text>: types text into a field of New contact, as a person.
                AirTextField* field = nullptr;
                for (AirTextField* candidate :
                     findChildren<AirTextField*>(rest.section('|', 0, 0).trimmed())) {
                    if (candidate->isVisible()) {
                        field = candidate;
                        break;
                    }
                }
                if (field) {
                    field->edit()->setFocus();
                    field->edit()->selectAll();
                    field->edit()->insertPlainText(rest.section('|', 1));
                } else {
                    qWarning() << "No field to fill:" << rest;
                }
            } else if (command == QStringLiteral("key")) {
                // key <key>: one press of a key, such as Return, Esc, Backspace or /, for the
                // widget with the focus; it goes through the shortcuts first, as a real one does,
                // which needs the window to be the active one.
                activateWindow();
                const QKeySequence sequence = QKeySequence::fromString(rest);
                // A menu that is open takes the key, else the widget with the focus.
                const auto keyTarget = [this]() -> QWidget* {
                    if (QWidget* const popup = QApplication::activePopupWidget()) {
                        return popup;
                    }
                    QWidget* const focus = QApplication::focusWidget();
                    return focus ? focus : this;
                };
                QWidget* const target = keyTarget();
                if (sequence.isEmpty()) {
                    qWarning() << "Unknown key:" << rest;
                } else {
                    const int code = sequence[0];
                    const auto key = static_cast<Qt::Key>(code & ~Qt::KeyboardModifierMask);
                    const auto modifiers =
                        static_cast<Qt::KeyboardModifiers>(code & Qt::KeyboardModifierMask);
                    QString text;
                    if (key >= Qt::Key_Space && key <= Qt::Key_AsciiTilde) {
                        text = QString(QChar(key)).toLower();
                    } else if (key == Qt::Key_Return || key == Qt::Key_Enter) {
                        text = QStringLiteral("\r");
                    } else if (key == Qt::Key_Escape) {
                        text = QString(QChar(0x1b));
                    } else if (key == Qt::Key_Backspace) {
                        text = QStringLiteral("\b");
                    } else if (key == Qt::Key_Tab) {
                        text = QStringLiteral("\t");
                    }
                    QKeyEvent press(QEvent::KeyPress, key, modifiers, text);
                    QCoreApplication::sendEvent(target, &press);
                    QKeyEvent release(QEvent::KeyRelease, key, modifiers, text);
                    QCoreApplication::sendEvent(keyTarget(), &release);
                }
            } else if (command == QStringLiteral("click")) {
                // click <object name>: a button of the window, the chat title for the dossier, say.
                QAbstractButton* button = nullptr;
                for (QAbstractButton* candidate : findChildren<QAbstractButton*>(rest)) {
                    if (candidate->isVisible()) {
                        button = candidate;
                        break;
                    }
                }
                // The entries of a transcript live in its scene, so their buttons are no children
                // of the window: the one in the transcript that shows is taken.
                const QWidgetList everyWidget = QApplication::allWidgets();
                for (QWidget* const candidate : everyWidget) {
                    auto* const embedded = qobject_cast<QAbstractButton*>(candidate);
                    if (button || !embedded || embedded->objectName() != rest
                        || !embedded->isVisible()) {
                        continue;
                    }
                    QGraphicsProxyWidget* const proxy = embedded->window()->graphicsProxyWidget();
                    const QList<QGraphicsView*> views =
                        proxy && proxy->scene() ? proxy->scene()->views() : QList<QGraphicsView*>();
                    for (QGraphicsView* const view : views) {
                        if (view->isVisible()) {
                            button = embedded;
                        }
                    }
                }
                if (button) {
                    // After this line: a menu the click opens runs its own loop, which the next
                    // lines of the script then run in.
                    QTimer::singleShot(0, button, &QAbstractButton::click);
                } else {
                    qWarning() << "Nothing to click:" << rest;
                }
            } else if (command == QStringLiteral("press")) {
                // press <name>: the button on screen that is named so or says so, for the switches,
                // menu fields and choices of the settings, which have no object names.
                QAbstractButton* button = nullptr;
                for (QAbstractButton* const candidate : findChildren<QAbstractButton*>()) {
                    if (candidate->isVisible()
                        && (candidate->accessibleName() == rest || candidate->text() == rest)) {
                        button = candidate;
                        break;
                    }
                }
                if (button) {
                    QTimer::singleShot(0, button, &QAbstractButton::click);
                } else {
                    qWarning() << "Nothing to press:" << rest;
                }
            } else if (command == QStringLiteral("type")) {
                // type <text>: the message field of the open conversation; \n starts a new line.
                auto* const field =
                    contentLayout ? contentLayout->mainContent->findChild<ChatTextEdit*>() : nullptr;
                if (field) {
                    field->setFocus();
                    field->setPlainText(
                        QString(rest).replace(QStringLiteral("\\n"), QStringLiteral("\n")));
                    field->moveCursor(QTextCursor::End);
                } else {
                    qWarning() << "No message field to type into";
                }
            } else if (command == QStringLiteral("focuslist")) {
                // focuslist: the list takes the keyboard as Tab gives it, cursor and all.
                activateWindow();
                airList->setFocus(Qt::TabFocusReason);
            } else if (command == QStringLiteral("card")) {
                // card accept|decline <words>: answers the request or invitation card whose
                // message, group name or key starts with the words, as its button does.
                const QString verb = rest.section(' ', 0, 0);
                const QString words = rest.section(' ', 1).trimmed();
                QString requestId;
                for (const Air::Request& request : airSource->requests()) {
                    if (!words.isEmpty()
                        && (request.message.startsWith(words) || request.groupName.startsWith(words)
                            || request.publicKey.startsWith(words, Qt::CaseInsensitive))) {
                        requestId = request.id;
                        break;
                    }
                }
                if (requestId.isEmpty()) {
                    qWarning() << "No card:" << rest;
                } else if (verb == QStringLiteral("accept")) {
                    acceptAirRequest(requestId);
                } else {
                    declineAirRequest(requestId);
                }
            } else if (command == QStringLiteral("sendfile")) {
                // sendfile <title>|<path>: a file from this computer to a person, through the
                // checks of the paperclip, so a refusal is said as it is to a person.
                const QString entryId = entryByTitle(rest.section('|', 0, 0).trimmed());
                const QString path = rest.section('|', 1).trimmed();
                ChatForm* const form = entryId.startsWith(QStringLiteral("f:"))
                                           ? chatForms.value(ToxPk(entryId.mid(2)))
                                           : nullptr;
                if (!form || path.isEmpty()
                    || !QMetaObject::invokeMethod(form, "sendLocalFiles", Qt::DirectConnection,
                                                  Q_ARG(QStringList, QStringList{path}))) {
                    qWarning() << "Nothing to send:" << rest;
                }
            } else if (command == QStringLiteral("access")) {
                // access microphone|camera granted|undecided|denied
                const QString what = rest.section(' ', 1, 1);
                MediaAccess::setStateForShots(rest.section(' ', 0, 0) == QStringLiteral("camera")
                                                  ? MediaAccess::Device::Camera
                                                  : MediaAccess::Device::Microphone,
                                              what == QStringLiteral("denied") ? MediaAccess::State::Denied
                                              : what == QStringLiteral("undecided")
                                                  ? MediaAccess::State::Undecided
                                                  : MediaAccess::State::Granted);
            } else if (command == QStringLiteral("hours")) {
                // hours <title>|<hours ago,...>: online marks for the day strip of a person.
                const QString entryId = entryByTitle(rest.section('|', 0, 0).trimmed());
                if (entryId.startsWith(QStringLiteral("f:"))) {
                    const ToxPk publicKey(entryId.mid(2));
                    const QDateTime now = QDateTime::currentDateTime();
                    for (const QString& ago : rest.section('|', 1).split(',')) {
                        if (!ago.isEmpty()) {
                            airLocal->markOnlineForShots(publicKey, now.addSecs(-3600 * ago.toInt()));
                        }
                    }
                }
            } else if (command == QStringLiteral("waitband")) {
                // waitband <words>|<ms>: holds the script until the band that is up says the words
                // in its title or text, ms at most; news from the network comes when it comes.
                const QString words = rest.section('|', 0, 0).trimmed();
                const qint64 most = rest.section('|', 1).trimmed().toLongLong();
                const AirBanner* const band = AirBanner::current(this);
                const bool found = band && !words.isEmpty()
                                   && (band->content().title.contains(words)
                                       || band->content().body.contains(words));
                if (!waited->isValid()) {
                    waited->start();
                }
                if (!found && waited->elapsed() < most) {
                    lines->prepend(line);
                    delay = 100;
                } else {
                    if (!found) {
                        qWarning() << "No band came:" << words;
                    }
                    waited->invalidate();
                }
            } else if (command == QStringLiteral("band")) {
                // band hold|leave|open: the pointer comes over the band that is up, goes off it,
                // or clicks it, as the mouse gives it.
                AirBanner* const band = AirBanner::current(this);
                if (!band) {
                    qWarning() << "No band to" << rest;
                } else if (rest == QStringLiteral("hold") || rest == QStringLiteral("leave")) {
                    QEvent crossing(rest == QStringLiteral("hold") ? QEvent::Enter : QEvent::Leave);
                    QCoreApplication::sendEvent(band, &crossing);
                } else if (rest == QStringLiteral("open")) {
                    const QPoint at = band->rect().center();
                    QMouseEvent press(QEvent::MouseButtonPress, at, band->mapToGlobal(at),
                                      Qt::LeftButton, Qt::LeftButton, Qt::NoModifier);
                    QCoreApplication::sendEvent(band, &press);
                    QMouseEvent release(QEvent::MouseButtonRelease, at, band->mapToGlobal(at),
                                        Qt::LeftButton, Qt::NoButton, Qt::NoModifier);
                    QCoreApplication::sendEvent(band, &release);
                } else {
                    qWarning() << "No band action" << rest;
                }
            } else if (command == QStringLiteral("minimize")) {
                // minimize: the window goes to the Dock, so news is the system's to say.
                showMinimized();
            } else if (command == QStringLiteral("restore")) {
                showNormal();
                raise();
                activateWindow();
            } else if (command == QStringLiteral("link")) {
                // link <address>: a click on the message that is this link alone, in the transcript
                // on screen, given to its view as the mouse gives it.
                bool clicked = false;
                const QList<ChatWidget*> views =
                    contentLayout ? contentLayout->mainContent->findChildren<ChatWidget*>()
                                  : QList<ChatWidget*>();
                for (ChatWidget* const view : views) {
                    // ChatWidget keeps a scene of its own under the same name as the view's.
                    QGraphicsScene* const scene = static_cast<QGraphicsView*>(view)->scene();
                    if (clicked || !view->isVisible() || !scene) {
                        continue;
                    }
                    const QList<QGraphicsItem*> items = scene->items();
                    for (QGraphicsItem* const item : items) {
                        if (item->type() != ChatLineContent::ChatLineContentType) {
                            continue;
                        }
                        const Text* const text =
                            qobject_cast<const Text*>(static_cast<ChatLineContent*>(item));
                        if (!text || !text->isVisible() || text->getText() != rest) {
                            continue;
                        }
                        // The link starts the first line, at the start edge of the interface:
                        // right to left, a line stands at the right.
                        const QRectF box = text->sceneBoundingRect();
                        const qreal start = view->isRightToLeft()
                                                ? box.right() - AirTokens::Space::s8
                                                : box.left() + AirTokens::Space::s8;
                        const QPoint at = view->mapFromScene(QPointF(start, box.center().y()));
                        if (!view->viewport()->rect().contains(at)) {
                            continue;
                        }
                        const QPoint global = view->viewport()->mapToGlobal(at);
                        QMouseEvent press(QEvent::MouseButtonPress, at, global, Qt::LeftButton,
                                          Qt::LeftButton, Qt::NoModifier);
                        QCoreApplication::sendEvent(view->viewport(), &press);
                        QMouseEvent release(QEvent::MouseButtonRelease, at, global, Qt::LeftButton,
                                            Qt::NoButton, Qt::NoModifier);
                        QCoreApplication::sendEvent(view->viewport(), &release);
                        clicked = true;
                        break;
                    }
                }
                if (!clicked) {
                    qWarning() << "No link to click:" << rest;
                }
            } else if (command == QStringLiteral("clear")) {
                // clear: Clear displayed messages of the open group or of the saved messages, which
                // asks first, as its menu item does.
                QWidget* form = nullptr;
                if (const QSharedPointer<GroupChatForm> group = openGroupForm()) {
                    form = group.data();
                } else if (notesForm && notesForm->isVisible()) {
                    form = notesForm;
                }
                if (!form
                    || !QMetaObject::invokeMethod(form, "clearChatArea", Qt::DirectConnection,
                                                  Q_ARG(bool, true), Q_ARG(bool, true))) {
                    qWarning() << "Nothing to clear";
                }
            } else if (command == QStringLiteral("quote")) {
                // quote <text>: the open conversation quotes text, as Quote does with a selection.
                bool quoted = false;
                for (ChatQuotePreview* const preview : findChildren<ChatQuotePreview*>()) {
                    if (preview->parentWidget() && preview->parentWidget()->isVisible()) {
                        preview->setQuoteText(rest);
                        quoted = true;
                        break;
                    }
                }
                if (!quoted) {
                    qWarning() << "No conversation to quote in";
                }
            } else if (command == QStringLiteral("spoken")) {
                QWidget* target = nullptr;
                if (rest == QStringLiteral("list")) {
                    target = findChild<AirListView*>();
                } else {
                    for (ChatWidget* const log : findChildren<ChatWidget*>()) {
                        if (log->isVisible()) {
                            target = log;
                            break;
                        }
                    }
                }
                if (!target) {
                    qWarning() << "Nothing to speak in" << rest;
                } else {
                    for (const QString& said : AirAccessible::spokenTree(target)) {
                        qDebug().noquote() << "Spoken:" << said;
                    }
                }
            } else if (command == QStringLiteral("grab")) {
                if (!grab().save(rest)) {
                    qWarning() << "Screenshot not saved:" << rest;
                }
            } else if (command == QStringLiteral("frames")) {
                AirWalk::frames(this, rest.section(' ', 0, 0).toInt(),
                                rest.section(' ', 1, 1).toInt(), rest.section(' ', 2));
            } else if (command == QStringLiteral("audit")) {
                AirWalk::audit(this, rest);
            } else if (command == QStringLiteral("idle")) {
                AirWalk::idle(this, rest.section(' ', 0, 0).toInt(), rest.section(' ', 1));
            } else if (command == QStringLiteral("least")) {
                // least <object name or "window"> <depth> <label>
                const QString name = rest.section(' ', 0, 0);
                QWidget* const root = name == QStringLiteral("window") ? static_cast<QWidget*>(this)
                                                                       : findChild<QWidget*>(name);
                AirWalk::least(root, rest.section(' ', 1, 1).toInt(), rest.section(' ', 2));
            } else if (command == QStringLiteral("grabpopup")) {
                // grabpopup <file.png>: the menu that is open, on its own.
                QWidget* const popup = QApplication::activePopupWidget();
                if (!popup || !popup->grab().save(rest)) {
                    qWarning() << "No menu to save:" << rest;
                }
            } else if (command == QStringLiteral("quit")) {
                qApp->quit();
                return;
            } else {
                qWarning() << "Unknown screenshot command:" << line;
            }
            break;
        }
        if (!lines->isEmpty()) {
            runner->start(delay);
        }
    });
    runner->start(0);
}
#endif
