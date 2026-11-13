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


#include "nexus.h"
#include "persistence/settings.h"
#include "src/core/core.h"
#include "src/core/coreav.h"
#include "src/model/groupinvite.h"
#include "src/model/status.h"
#include "src/persistence/credentialstore.h"
#include "src/persistence/profile.h"
#include "src/widget/widget.h"
#include "src/widget/style.h"
#include "video/camerasource.h"
#include "widget/loginscreen.h"
#include "src/widget/tool/messageboxmanager.h"
#include "audio/audio.h"
#include "src/ipc.h"

#include <QApplication>
#include <QCommandLineParser>
#include <QDebug>
#include <QThread>

#include <vpx/vpx_image.h>

#include <cassert>

#ifdef Q_OS_MAC
#include <QActionGroup>
#include <QMenuBar>
#include <QWindow>
#endif

/**
 * @class Nexus
 *
 * This class is in charge of connecting various systems together
 * and forwarding signals appropriately to the right objects,
 * it is in charge of starting the GUI and the Core.
 */

Q_DECLARE_OPAQUE_POINTER(ToxAV*)

Nexus::Nexus(Settings& settings_, IMessageBoxManager& messageBoxManager_,
    CameraSource& cameraSource_, IPC& ipc_, QObject* parent)
    : QObject(parent)
    , profile{nullptr}
    , settings{settings_}
    , widget{nullptr}
    , cameraSource{cameraSource_}
    , style{new Style()}
    , messageBoxManager{messageBoxManager_}
    , ipc{ipc_}
{
    QObject::connect(this, &Nexus::saveGlobal, &settings, &Settings::saveGlobal);
}

Nexus::~Nexus()
{
    delete widget;
    widget = nullptr;
    delete profile;
    profile = nullptr;
    emit saveGlobal();
#ifdef Q_OS_MAC
    delete globalMenuBar;
#endif
}

/**
 * @brief Sets up invariants and calls showLogin
 *
 * Hides the login screen and shows the GUI for the given profile.
 * Will delete the current GUI, if it exists.
 */
void Nexus::start()
{
    qDebug() << "Starting up";

    // Setup the environment
    qRegisterMetaType<Status::Status>("Status::Status");
    qRegisterMetaType<vpx_image>("vpx_image");
    qRegisterMetaType<uint8_t>("uint8_t");
    qRegisterMetaType<uint16_t>("uint16_t");
    qRegisterMetaType<uint32_t>("uint32_t");
    qRegisterMetaType<const int16_t*>("const int16_t*");
    qRegisterMetaType<int32_t>("int32_t");
    qRegisterMetaType<int64_t>("int64_t");
    qRegisterMetaType<size_t>("size_t");
    qRegisterMetaType<QPixmap>("QPixmap");
    qRegisterMetaType<Profile*>("Profile*");
    qRegisterMetaType<ToxAV*>("ToxAV*");
    qRegisterMetaType<ToxFile>("ToxFile");
    qRegisterMetaType<ToxFile::FileDirection>("ToxFile::FileDirection");
    qRegisterMetaType<std::shared_ptr<VideoFrame>>("std::shared_ptr<VideoFrame>");
    qRegisterMetaType<ToxPk>("ToxPk");
    qRegisterMetaType<ToxId>("ToxId");
    qRegisterMetaType<ToxPk>("GroupId");
    qRegisterMetaType<ToxPk>("ChatId");
    qRegisterMetaType<GroupInvite>("GroupInvite");
    qRegisterMetaType<ReceiptNum>("ReceiptNum");
    qRegisterMetaType<RowId>("RowId");
    qRegisterMetaType<uint64_t>("uint64_t");
    qRegisterMetaType<ExtensionSet>("ExtensionSet");

    qApp->setQuitOnLastWindowClosed(false);

#ifdef Q_OS_MAC
    // TODO: still needed?
    globalMenuBar = new QMenuBar();
    dockMenu = new QMenu(globalMenuBar);

    viewMenu = globalMenuBar->addMenu(QString());

    windowMenu = globalMenuBar->addMenu(QString());
    globalMenuBar->addAction(windowMenu->menuAction());

    fullscreenAction = viewMenu->addAction(QString());
    fullscreenAction->setShortcut(QKeySequence::FullScreen);
    connect(fullscreenAction, &QAction::triggered, this, &Nexus::toggleFullscreen);

    minimizeAction = windowMenu->addAction(QString());
    minimizeAction->setShortcut(Qt::CTRL + Qt::Key_M);
    connect(minimizeAction, &QAction::triggered, [this]() {
        minimizeAction->setEnabled(false);
        QApplication::focusWindow()->showMinimized();
    });

    windowMenu->addSeparator();
    frontAction = windowMenu->addAction(QString());
    connect(frontAction, &QAction::triggered, this, &Nexus::bringAllToFront);

    QAction* quitAction = new QAction(globalMenuBar);
    quitAction->setMenuRole(QAction::QuitRole);
    connect(quitAction, &QAction::triggered, qApp, &QApplication::quit);

    retranslateUi();
#endif
    showMainGUI();
}

void Nexus::setLoginFailure(const QString& profileName, const Air::OpenFailure& failure)
{
    loginFailureProfile = profileName;
    loginFailure = failure;
}

void Nexus::setPendingImport(const QString& path)
{
    pendingImport = path;
}

/**
 * @brief Hides the main GUI, delete the profile, and shows the login screen
 */
int Nexus::showLogin(const QString& profileName)
{
    delete widget;
    widget = nullptr;

    delete profile;
    profile = nullptr;

    LoginScreen loginScreen{settings, *style, profileName};
    connectLoginScreen(loginScreen);

    // A profile just deleted from You: the files that stayed are named over the start window.
    loginScreen.tellLeftovers(deletionLeftovers);
    deletionLeftovers.clear();
    // The profile that did not open by itself at start says why under its field.
    loginScreen.showFailure(loginFailureProfile, loginFailure);
    loginFailureProfile.clear();
    loginFailure = Air::OpenFailure();
    if (!pendingImport.isEmpty()) {
        loginScreen.moveIn(pendingImport);
        pendingImport.clear();
    }

    QCoreApplication::sendPostedEvents(nullptr, QEvent::DeferredDelete);

    // TODO(kriby): Move core out of profile
    // This is awkward because the core is in the profile
    // The connection order ensures profile will be ready for bootstrap for now
    connect(this, &Nexus::currentProfileChanged, this, &Nexus::bootstrapWithProfile);
    // KHANDAQ (redesign E19, E20): the main window is made as soon as the profile is open and waits
    // behind the start window, which then grows into its frame and gives way to it.
    QObject::connect(&loginScreen, &LoginScreen::readyToOpen, this,
                     [this, &loginScreen](bool addFirstPerson) {
                         if (!widget) {
                             loginScreen.done(QDialog::Accepted);
                             return;
                         }
                         const QRect frame = widget->frameForStart(loginScreen.geometry());
                         loginScreen.growInto(frame, [this, &loginScreen, frame, addFirstPerson] {
                             if (widget) {
                                 widget->showFromStart(frame, addFirstPerson);
                             }
                             loginScreen.done(QDialog::Accepted);
                         });
                     });
    holdWindow = true;
    int returnval = loginScreen.exec();
    holdWindow = false;
    if (returnval == QDialog::Rejected) {
        // Kriby: This will terminate the main application loop, necessary until we refactor
        // away the split startup/return to login behavior.
        qApp->quit();
    }
    disconnect(this, &Nexus::currentProfileChanged, this, &Nexus::bootstrapWithProfile);
    return returnval;
}

/**
 * @brief Deletes the profile in use from this computer, then shows the login screen.
 */
void Nexus::removeProfile()
{
    if (!profile) {
        return;
    }
    const QString name = profile->getName();
    deletionLeftovers = profile->remove();
    // KHANDAQ (redesign E17): a password kept for logging in automatically goes with the profile.
    if (CredentialStore::isSupported()) {
        CredentialStore::remove(name);
    }
    showLogin();
}

/**
 * @brief A login at the login screen ends what Log out started (ProfileInfo::logout): the next
 * starts log in by themselves again when logging in automatically is on.
 */
void Nexus::clearLoggedOut()
{
    if (settings.getLoggedOut()) {
        settings.setLoggedOut(false);
        settings.saveGlobal();
    }
}

void Nexus::bootstrapWithProfile(Profile* p)
{
    // kriby: This is a hack until a proper controller is written

    profile = p;

    if (profile) {
        audioControl = std::unique_ptr<IAudioControl>(Audio::makeAudio(settings));
        assert(audioControl != nullptr);
        profile->getCore().getAv()->setAudio(*audioControl);
        start();
    }
}

void Nexus::connectLoginScreen(const LoginScreen& loginScreen)
{
    // TODO(kriby): Move connect sequences to a controller class object instead

    // Nexus -> LoginScreen
    QObject::connect(this, &Nexus::profileLoaded, &loginScreen, &LoginScreen::onProfileLoaded);
    QObject::connect(this, &Nexus::profileCreated, &loginScreen, &LoginScreen::onProfileCreated);
    QObject::connect(this, &Nexus::profileLoadFailed, &loginScreen, &LoginScreen::onProfileLoadFailed);
    // LoginScreen -> Nexus
    QObject::connect(&loginScreen, &LoginScreen::createNewProfile, this, &Nexus::onCreateNewProfile);
    QObject::connect(&loginScreen, &LoginScreen::loadProfile, this, &Nexus::onLoadProfile);
    // LoginScreen -> Settings
    QObject::connect(&loginScreen, &LoginScreen::autoLoginChanged, &settings, &Settings::setAutoLogin);
    QObject::connect(&loginScreen, &LoginScreen::autoLoginChanged, &settings, &Settings::saveGlobal);
    // Settings -> LoginScreen
    QObject::connect(&settings, &Settings::autoLoginChanged, &loginScreen,
                     &LoginScreen::onAutoLoginChanged);
}

void Nexus::showMainGUI()
{
    // TODO(kriby): Rewrite as view-model connect sequence only, add to a controller class object
    assert(profile);

    // Create GUI
    widget = new Widget(*profile, *audioControl, cameraSource, settings, *style,
        ipc, *this);
    if (holdWindow) {
        widget->holdForStart();
    }

    // Start GUI
    widget->init();

    // Zetok protection
    // There are small instants on startup during which no
    // profile is loaded but the GUI could still receive events,
    // e.g. between two modal windows. Disable the GUI to prevent that.
    widget->setEnabled(false);

    // Connections
    connect(profile, &Profile::selfAvatarChanged, widget, &Widget::onSelfAvatarLoaded);

    connect(profile, &Profile::coreChanged, widget, &Widget::onCoreChanged);

    profile->startCore();

    widget->setEnabled(true);

    // Other instances hand their tox: links and files over from now on (Widget::handleToxUri).
    // KHANDAQ: after Log out the main window is made again, and takes them again.
    if (ipc.isAttached()) {
        registerIpcHandlers();
    }
}

/**
 * @brief Get current user profile.
 * @return nullptr if not started, profile otherwise.
 */
Profile* Nexus::getProfile()
{
    return profile;
}

/**
 * @brief Creates a new profile and replaces the current one.
 * @param name New username
 * @param pass New password
 */
void Nexus::onCreateNewProfile(const QString& fileName, const QString& selfName,
                               const QString& pass, bool autoLogin)
{
    Air::OpenFailure failure;
    Profile* p = Profile::createProfile(fileName, pass, settings, parser, cameraSource,
                                        messageBoxManager, &failure, selfName);
    // KHANDAQ (deep review 2026-08-23, RR3-09): storing the password and skipping the login screen
    // happen only when the person creating the profile asked for them.
    //
    // Both used to happen unconditionally, at the moment the profile was made, with nothing on
    // screen saying so. The password to a profile is the key to the whole local history; putting a
    // copy of it in Keychain / Credential Manager and turning off the prompt is a real reduction in
    // what an attacker with the unlocked machine has to get past, and it is not a decision to make
    // on somebody's behalf. The create-profile page now carries the checkbox that asks.
    if (p && autoLogin && !pass.isEmpty() && CredentialStore::isSupported()) {
        if (CredentialStore::save(fileName, pass)) {
            settings.setAutoLogin(true);
            settings.saveGlobal();
        }
    }
    if (p) {
        clearLoggedOut();
    }
    setProfile(p, failure, true);
    parser = nullptr; // only apply cmdline proxy settings once
}

/**
 * Loads an existing profile and replaces the current one.
 */
void Nexus::onLoadProfile(const QString& name, const QString& pass, bool withoutProxy)
{
    Air::OpenFailure failure;
    Profile* p = Profile::loadProfile(name, pass, settings, parser, cameraSource, messageBoxManager,
                                      &failure, withoutProxy);
    if (p) {
        if (settings.getAutoLogin()) {
            if (!pass.isEmpty() && CredentialStore::isSupported()) {
                CredentialStore::save(name, pass);
            }
        } else if (CredentialStore::isSupported()) {
            CredentialStore::remove(name);
        }
        clearLoggedOut();
    }
    setProfile(p, failure, false);
    parser = nullptr; // only apply cmdline proxy settings once
}

/**
 * Changes the loaded profile and notifies listeners.
 * @param p
 */
void Nexus::setProfile(Profile* p, const Air::OpenFailure& failure, bool created)
{
    if (!p) {
        // Why it failed is said under the field of the start window.
        emit profileLoadFailed(failure);
        return;
    }
    // The ID is read before the core runs on its own thread.
    const QString id = created ? p->getCore().getSelfId().toString() : QString();
    // The main window is made first, held behind the start window, so the start window can grow
    // into its frame once it is told the profile is open.
    emit currentProfileChanged(p);
    if (created) {
        emit profileCreated(id);
    } else {
        emit profileLoaded();
    }
}

void Nexus::setParser(QCommandLineParser* parser_)
{
    parser = parser_;
}

void Nexus::registerIpcHandlers()
{
    widget->registerIpcHandlers();
}

bool Nexus::handleToxSave(const QString& path)
{
    assert(widget);
    return widget->handleToxSave(path);
}

bool Nexus::handleToxUri(const QString& uri)
{
    assert(widget);
    return widget->handleToxUri(uri);
}

#ifdef Q_OS_MAC
void Nexus::retranslateUi()
{
    viewMenu->menuAction()->setText(tr("View", "OS X Menu bar"));
    windowMenu->menuAction()->setText(tr("Window", "OS X Menu bar"));
    minimizeAction->setText(tr("Minimize", "OS X Menu bar"));
    frontAction->setText((tr("Bring All to Front", "OS X Menu bar")));
}

void Nexus::onWindowStateChanged(Qt::WindowStates state)
{
    minimizeAction->setEnabled(QApplication::activeWindow() != nullptr);

    if (QApplication::activeWindow() != nullptr && sender() == QApplication::activeWindow()) {
        if (state & Qt::WindowFullScreen)
            minimizeAction->setEnabled(false);

        if (state & Qt::WindowFullScreen)
            fullscreenAction->setText(tr("Exit Fullscreen"));
        else
            fullscreenAction->setText(tr("Enter Fullscreen"));

        updateWindows();
    }

    updateWindowsStates();
}

void Nexus::updateWindows()
{
    updateWindowsArg(nullptr);
}

void Nexus::updateWindowsArg(QWindow* closedWindow)
{
    QWindowList windowList = QApplication::topLevelWindows();
    delete windowActions;
    windowActions = new QActionGroup(this);

    windowMenu->addSeparator();

    QAction* dockLast;
    if (!dockMenu->actions().isEmpty())
        dockLast = dockMenu->actions().first();
    else
        dockLast = nullptr;

    QWindow* activeWindow;

    if (QApplication::activeWindow())
        activeWindow = QApplication::activeWindow()->windowHandle();
    else
        activeWindow = nullptr;

    for (int i = 0; i < windowList.size(); ++i) {
        if (closedWindow == windowList[i])
            continue;

        QAction* action = windowActions->addAction(windowList[i]->title());
        action->setCheckable(true);
        action->setChecked(windowList[i] == activeWindow);
        connect(action, &QAction::triggered, [=] { onOpenWindow(windowList[i]); });
        windowMenu->addAction(action);
        dockMenu->insertAction(dockLast, action);
    }

    if (dockLast && !dockLast->isSeparator())
        dockMenu->insertSeparator(dockLast);
}

void Nexus::updateWindowsClosed()
{
    updateWindowsArg(static_cast<QWidget*>(sender())->windowHandle());
}

void Nexus::updateWindowsStates()
{
    bool exists = false;
    QWindowList windowList = QApplication::topLevelWindows();

    for (QWindow* window : windowList) {
        if (!(window->windowState() & Qt::WindowMinimized)) {
            exists = true;
            break;
        }
    }

    frontAction->setEnabled(exists);
}

void Nexus::onOpenWindow(QObject* object)
{
    QWindow* window = static_cast<QWindow*>(object);

    if (window->windowState() & QWindow::Minimized)
        window->showNormal();

    window->raise();
    window->requestActivate();
}

void Nexus::toggleFullscreen()
{
    QWidget* window = QApplication::activeWindow();

    if (window->isFullScreen())
        window->showNormal();
    else
        window->showFullScreen();
}

void Nexus::bringAllToFront()
{
    QWindowList windowList = QApplication::topLevelWindows();
    QWindow* focused = QApplication::focusWindow();

    for (QWindow* window : windowList)
        window->raise();

    focused->raise();
}
#endif
