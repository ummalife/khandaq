/*
    Copyright © 2015-2019 by The qTox Project Contributors

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

#include "audio/iaudiocontrol.h"
#include "src/model/air/airstart.h"

#include <QObject>
#include <QStringList>

#include <memory>

class Widget;
class Profile;
class Settings;
class LoginScreen;
class Core;
class QCommandLineParser;
class CameraSource;
class Style;
class IMessageBoxManager;
class IPC;

#ifdef Q_OS_MAC
class QMenuBar;
class QMenu;
class QAction;
class QWindow;
class QActionGroup;
#endif

class Nexus : public QObject
{
    Q_OBJECT
public:
    Nexus(Settings& settings, IMessageBoxManager& messageBoxManager,
        CameraSource& cameraSource, IPC& ipc, QObject* parent = nullptr);
    ~Nexus();
    void start();
    void showMainGUI();
    void setParser(QCommandLineParser* parser_);
    /**
     * The profile set to open at start did not: the start window says why under its password field
     * (E21). It is the window there is, and a box alone on the screen belongs to nothing.
     */
    void setLoginFailure(const QString& profileName, const Air::OpenFailure& failure);
    /** The next start window moves in the profile file at path (Settings, a file opened with the app). */
    void setPendingImport(const QString& path);
    Profile* getProfile();
    void registerIpcHandlers();
    bool handleToxSave(const QString& path);
    bool handleToxUri(const QString& uri);

#ifdef Q_OS_MAC
public:
    QMenuBar* globalMenuBar;
    QMenu* viewMenu;
    QMenu* windowMenu;
    QAction* minimizeAction;
    QAction* fullscreenAction;
    QAction* frontAction;
    QMenu* dockMenu;

public slots:
    void retranslateUi();
    void onWindowStateChanged(Qt::WindowStates state);
    void updateWindows();
    void updateWindowsClosed();
    void updateWindowsStates();
    void onOpenWindow(QObject* object);
    void toggleFullscreen();
    void bringAllToFront();

private:
    void updateWindowsArg(QWindow* closedWindow);

    QActionGroup* windowActions = nullptr;
#endif
signals:
    void currentProfileChanged(Profile* Profile);
    void profileLoaded();
    /** A profile made at first launch is open; id is its ID. */
    void profileCreated(const QString& id);
    void profileLoadFailed(const Air::OpenFailure& failure);
    void saveGlobal();

public slots:
    void onCreateNewProfile(const QString& fileName, const QString& selfName, const QString& pass,
                            bool autoLogin);
    void onLoadProfile(const QString& name, const QString& pass, bool withoutProxy);
    int showLogin(const QString& profileName = QString());
    void removeProfile();
    void bootstrapWithProfile(Profile* p);

private:
    void connectLoginScreen(const LoginScreen& loginScreen);
    /** created: the profile was made just now, at first launch. */
    void setProfile(Profile* p, const Air::OpenFailure& failure, bool created);
    void clearLoggedOut();

private:
    Profile* profile;
    Settings& settings;
    Widget* widget;
    std::unique_ptr<IAudioControl> audioControl;
    QCommandLineParser* parser = nullptr;
    CameraSource& cameraSource;
    std::unique_ptr<Style> style;
    IMessageBoxManager& messageBoxManager;
    IPC& ipc;
    /** Files of a profile deleted from You that stayed on disk, for the login screen to name. */
    QStringList deletionLeftovers;
    QString loginFailureProfile;
    Air::OpenFailure loginFailure;
    QString pendingImport;
    /** The start window is up: the main window made meanwhile waits behind it (Widget::holdForStart). */
    bool holdWindow = false;
};
