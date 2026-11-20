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

#include "src/net/updatemanifest.h"
#include "src/net/updateplatform.h"

#include <QByteArray>
#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkRequest>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QUrl>
#include <QVector>

class IUpdateSettings;
class QNetworkReply;

/** How every request of the update is made, the check and the download alike. */
namespace UpdateNetwork {

/**
 * A GET that says nothing about who asks: "Khandaq" as the user agent with no version, a fixed
 * Accept-Language in place of the system's, no cookies kept or sent, no cache, and no redirect
 * followed (a redirect is an answer the check refuses).
 */
QNetworkRequest request(const QUrl& url);

/**
 * The route through the proxy of the profile, as Tox takes it. A proxy without an address or a port
 * cannot be used, and then nothing is sent at all (false): the request never falls back to a direct
 * connection that would bypass it.
 */
bool route(const QNetworkProxy& configured, QNetworkProxy& out);

} // namespace UpdateNetwork

/**
 * KHANDAQ (0.2.66): asks khandaq.org whether a newer desktop release is out, and believes the answer
 * only once the release key's signature over it verifies.
 *
 * A release build (one made from an exact tag) of a platform that has downloads checks 60 to 180 s
 * after it starts, then every hour asks itself whether a day has passed since the last check that
 * went through, while the switch in About is on; Check now asks at once. A check is two small GETs,
 * the manifest and its signature (64 KiB and 4 KiB at most, 30 s each), through the proxy of the
 * profile. What it finds is only offered: nothing is downloaded or installed until the person asks.
 */
class UpdateCheck : public QObject
{
    Q_OBJECT

public:
    /** Everything a check depends on; forThisCopy is the one the app uses, tests make their own. */
    struct Setup
    {
        QUrl manifest;
        UpdatePlatform::Layout layout;
        UpdateManifest::Running running;
        QVector<QByteArray> keys;
        int timeoutMs = 30 * 1000;
        int manifestLimit = 64 * 1024;
        int signatureLimit = 4 * 1024;
        /** The first check comes this long after the start, at random within the range, so that
         * computers that start together do not all ask at once. */
        int firstCheckEarliestMs = 60 * 1000;
        int firstCheckLatestMs = 180 * 1000;
        /** How often the check asks itself whether a day has passed since the last one. */
        int recheckMs = 60 * 60 * 1000;

        static Setup forThisCopy();
    };

    explicit UpdateCheck(IUpdateSettings& settings, QObject* parent = nullptr);
    UpdateCheck(IUpdateSettings& settings, Setup setup, QObject* parent = nullptr);
    ~UpdateCheck() override;

    /** This build and this copy update at all: a release build (or a QA build) of a platform a
     * release is published for. */
    bool supported() const;
    const UpdatePlatform::Layout& layout() const;
    /** The version this build counts itself as. */
    UpdateManifest::Version runningVersion() const;
    /** The address of the manifest; downloads are named beside it. */
    QUrl manifestUrl() const;
    /** Starts the schedule. */
    void start();
    /** Checks now, unless a check is already running; Check now in About works with the switch
     * off too, as the person asks for it. */
    void checkForUpdate();
    bool checking() const;
    /** A verified manifest offers a newer version for this copy. */
    bool hasOffer() const;
    const UpdateManifest::Entry& offer() const;
    /** The last verified manifest. */
    const UpdateManifest::Release& release() const;

signals:
    void checkStarted();
    void updateAvailable(const QString& version);
    void upToDate();
    void updateCheckFailed();

private:
    enum class Stage
    {
        Idle,
        Manifest,
        Signature,
    };

    void tick();
    void fetch(const QUrl& url, int limit);
    void finished(QNetworkReply* reply, int limit);
    void conclude(const QByteArray& manifest, const QByteArray& signature);
    void fail(const QString& why);

    IUpdateSettings& settings;
    const Setup setup;
    QNetworkAccessManager manager;
    QPointer<QNetworkReply> current;
    QTimer hourly;
    Stage stage = Stage::Idle;
    QByteArray manifestBody;
    UpdateManifest::Release latest;
    UpdateManifest::Entry offered;
    bool offering = false;
};
