/*
    Copyright © 2026 by The Khandaq Project

    This file is part of Khandaq, a Qt-based graphical interface for Tox.

    Khandaq is libre software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    Khandaq is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with Khandaq.  If not, see <http://www.gnu.org/licenses/>.
*/

#pragma once

#include "src/net/updatemanifest.h"
#include "src/net/updateplatform.h"

#include <QCryptographicHash>
#include <QNetworkAccessManager>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QUrl>

#include <memory>

class IUpdateSettings;
class QFile;
class QNetworkReply;
class QProcess;

/**
 * KHANDAQ (0.2.65): downloads the release the update check offers and puts it in place, and only
 * ever because the person pressed Update.
 *
 * The download goes beside the other caches of the app, through the proxy of the profile, and is
 * kept only if its size and SHA-256 are the ones the signed manifest gives. Then, as this copy
 * allows: the Windows installer runs again over the installed copy (Windows asks for permission);
 * a portable Windows folder or a macOS bundle is unpacked beside the copy and swapped in by a helper
 * once Khandaq has quit (UpdatePlatform); anything else, Linux always, keeps the verified file, shows
 * it in the file manager and says how to install it.
 */
class UpdateInstaller : public QObject
{
    Q_OBJECT
public:
    enum class Stage
    {
        Idle,
        Downloading,
        Preparing,
        /** The installer or the helper has started; Khandaq quits. */
        Restarting,
        /** The verified file is there for the person to install; the band shows it in the file
         * manager. */
        Manual,
        Failed,
    };

    enum class Failure
    {
        None,
        /** The download did not finish. */
        Download,
        /** The file was not the one the signed manifest describes; it is deleted. */
        Integrity,
        /** The file could not be unpacked. */
        Unpack,
        /** Windows asked for permission to install and it was not given. */
        Declined,
        /** The installer or the helper could not be started. */
        Start,
    };

    struct Setup
    {
        /** The manifest the entry came from: downloads are named beside it. */
        QUrl manifest;
        UpdatePlatform::Layout layout;
        /** Downloads go into a folder of their version here. */
        QString downloads;
        UpdateManifest::Version running;
        /** The settings and profiles live in the folder of the program. */
        bool portable = false;
        /** How long a download may go without a byte before it is given up. */
        int stallMs = 60 * 1000;
        /** A portable Windows folder: the words before and after the folders in the note the helper
         * opens when the person's files may not all be back in place (UpdatePlatform::windowsNote),
         * in the language of the interface. */
        QString noteIntro;
        QString noteAction;
    };

    UpdateInstaller(IUpdateSettings& settings, Setup setup, QObject* parent = nullptr);
    ~UpdateInstaller() override;

    /** Where downloads go: the cache of the app, or the folder of a portable Windows copy, which
     * keeps what it writes inside itself when it can. */
    static QString downloadsFolder(bool portable);

    /** Downloads entry, checks it and puts it in place as this copy allows. */
    void install(const UpdateManifest::Entry& entry);
    /** Stops a download that is running. */
    void cancel();
    Stage stage() const;
    Failure failure() const;
    /** How much of the download has come, 0 to 100. */
    int percent() const;
    /** The verified download, once there is one. */
    QString file() const;
    /** The command that installs the verified .deb; empty for anything else. */
    QString command() const;
    /** Removes what earlier updates left: downloads of versions up to the running one, unfinished
     * downloads, and old and unpacked copies beside this one that the helper is done with. Does
     * nothing while an update of this run is downloading, unpacking or handing over. */
    void cleanUp();

signals:
    void changed();
    /** The installer or the helper is waiting for Khandaq to quit. */
    void quitRequested();

private:
    void setStage(Stage next);
    void failWith(Failure kind, const QString& why);
    void receive();
    void downloaded();
    void apply();
    void manual();
    void runInstaller();
    bool canSwap() const;
    void unpack();
    void unpacked(int exitCode, bool crashed);
    void startHelper();
    void dropStaging();

    IUpdateSettings& settings;
    const Setup setup;
    QNetworkAccessManager manager;
    QPointer<QNetworkReply> reply;
    std::unique_ptr<QFile> partial;
    QCryptographicHash hash{QCryptographicHash::Sha256};
    QTimer stall;
    QPointer<QProcess> unpacker;
    UpdateManifest::Entry target;
    QString path;
    UpdatePlatform::Swap swap;
    Stage current = Stage::Idle;
    Failure failed = Failure::None;
    qint64 received = 0;
    /** More came than the manifest says the file is. */
    bool overrun = false;
    int shownPercent = 0;
    /** Windows: the installer, open so that nothing can change it between its check and its start. */
    void* heldInstaller = nullptr;
};
