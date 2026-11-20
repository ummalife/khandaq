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

#include "src/net/updatecheck.h"

#include "src/net/sshsig.h"
#include "src/persistence/iupdatesettings.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QNetworkReply>
#include <QRandomGenerator>

#include <utility>

namespace {
constexpr qint64 daySeconds = 24 * 60 * 60;
} // namespace

namespace UpdateNetwork {

QNetworkRequest request(const QUrl& url)
{
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::UserAgentHeader, QStringLiteral("Khandaq"));
    // Qt would send the language of the system.
    request.setRawHeader("Accept-Language", "*");
    // The bytes as they are on the server: what is hashed and counted against the limits.
    request.setRawHeader("Accept-Encoding", "identity");
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                         QNetworkRequest::ManualRedirectPolicy);
    request.setAttribute(QNetworkRequest::CookieLoadControlAttribute, QNetworkRequest::Manual);
    request.setAttribute(QNetworkRequest::CookieSaveControlAttribute, QNetworkRequest::Manual);
    request.setAttribute(QNetworkRequest::CacheLoadControlAttribute,
                         QNetworkRequest::AlwaysNetwork);
    request.setAttribute(QNetworkRequest::CacheSaveControlAttribute, false);
    return request;
}

bool route(const QNetworkProxy& configured, QNetworkProxy& out)
{
    switch (configured.type()) {
    case QNetworkProxy::NoProxy:
        out = QNetworkProxy(QNetworkProxy::NoProxy);
        return true;
    case QNetworkProxy::Socks5Proxy:
    case QNetworkProxy::HttpProxy:
        if (configured.hostName().isEmpty() || configured.port() == 0) {
            return false;
        }
        out = configured;
        // The proxy looks the name up, so khandaq.org never reaches the resolver of the computer.
        out.setCapabilities(out.capabilities() | QNetworkProxy::HostNameLookupCapability);
        return true;
    default:
        return false;
    }
}

} // namespace UpdateNetwork

UpdateCheck::Setup UpdateCheck::Setup::forThisCopy()
{
    Setup made;
    made.manifest = UpdateEndpoint::manifestUrl();
    made.layout = UpdatePlatform::detect(QCoreApplication::applicationDirPath());
    // The tag as a UTF-16 literal: scripts/generate-desktop-update-manifest.py reads the version
    // a download carries from it.
    made.running = UpdateManifest::running(QStringLiteral(GIT_DESCRIBE_EXACT),
                                           QStringLiteral(GIT_DESCRIBE));
    made.keys = UpdateEndpoint::trustedKeys();
    return made;
}

UpdateCheck::UpdateCheck(IUpdateSettings& settings_, QObject* parent)
    : UpdateCheck(settings_, Setup::forThisCopy(), parent)
{}

UpdateCheck::UpdateCheck(IUpdateSettings& settings_, Setup setup_, QObject* parent)
    : QObject(parent)
    , settings{settings_}
    , setup{std::move(setup_)}
{
    qInfo() << "Khandaq is running version:" << GIT_DESCRIBE;
    hourly.setInterval(setup.recheckMs);
    connect(&hourly, &QTimer::timeout, this, &UpdateCheck::tick);
}

UpdateCheck::~UpdateCheck()
{
    // A reply still on its way must not come back to a check that is being destroyed. Read once
    // into a plain pointer: GCC's -Wnull-dereference, an error in strict builds, cannot tell that
    // a second read of a QPointer is not null.
    if (QNetworkReply* const reply = current.data()) {
        reply->disconnect(this);
        reply->abort();
    }
}

bool UpdateCheck::supported() const
{
    const bool build =
        setup.running.release || (UpdateEndpoint::qaBuild() && setup.running.known);
    return build && setup.layout.kind != UpdatePlatform::Kind::None && !setup.keys.isEmpty();
}

const UpdatePlatform::Layout& UpdateCheck::layout() const
{
    return setup.layout;
}

UpdateManifest::Version UpdateCheck::runningVersion() const
{
    return setup.running.version;
}

QUrl UpdateCheck::manifestUrl() const
{
    return setup.manifest;
}

void UpdateCheck::start()
{
    if (!supported()) {
        qInfo() << "Update check: not in this build or this copy (version" << GIT_DESCRIBE_EXACT
                << "platform" << UpdatePlatform::key(setup.layout.kind) << ")";
        return;
    }
    const int delayMs =
        QRandomGenerator::global()->bounded(setup.firstCheckEarliestMs, setup.firstCheckLatestMs + 1);
    QTimer::singleShot(delayMs, this, [this] {
        if (settings.getCheckUpdates()) {
            checkForUpdate();
        }
    });
    hourly.start();
}

void UpdateCheck::tick()
{
    if (!settings.getCheckUpdates()) {
        return;
    }
    // A clock set back puts the last check in the future: that is a day gone as well.
    const qint64 since = QDateTime::currentSecsSinceEpoch() - settings.getUpdateLastCheck();
    if (since < 0 || since >= daySeconds) {
        checkForUpdate();
    }
}

void UpdateCheck::checkForUpdate()
{
    if (!supported() || checking()) {
        return;
    }
    QNetworkProxy proxy;
    if (!UpdateNetwork::route(settings.getProxy(), proxy)) {
        qInfo() << "Update check: skipped, the proxy in the settings has no address or port";
        emit updateCheckFailed();
        return;
    }
    manager.setProxy(proxy);
    stage = Stage::Manifest;
    emit checkStarted();
    fetch(setup.manifest, setup.manifestLimit);
}

bool UpdateCheck::checking() const
{
    return stage != Stage::Idle;
}

bool UpdateCheck::hasOffer() const
{
    return offering;
}

const UpdateManifest::Entry& UpdateCheck::offer() const
{
    return offered;
}

const UpdateManifest::Release& UpdateCheck::release() const
{
    return latest;
}

void UpdateCheck::fetch(const QUrl& url, int limit)
{
    QNetworkReply* const reply = manager.get(UpdateNetwork::request(url));
    current = reply;
    // A server that never answers, or answers a byte at a time, ends here.
    QTimer::singleShot(setup.timeoutMs, reply, &QNetworkReply::abort);
    // Neither a body announced as larger than the limit nor one that grows past it is read.
    connect(reply, &QNetworkReply::metaDataChanged, reply, [reply, limit] {
        const QVariant length = reply->header(QNetworkRequest::ContentLengthHeader);
        if (length.isValid() && length.toLongLong() > limit) {
            reply->abort();
        }
    });
    connect(reply, &QNetworkReply::readyRead, reply, [reply, limit] {
        if (reply->bytesAvailable() > limit) {
            reply->abort();
        }
    });
    connect(reply, &QNetworkReply::finished, this, [this, reply, limit] { finished(reply, limit); });
}

void UpdateCheck::finished(QNetworkReply* reply, int limit)
{
    reply->deleteLater();
    current.clear();
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (reply->error() != QNetworkReply::NoError || status != 200) {
        fail(QStringLiteral("%1 answered %2 (%3)")
                 .arg(reply->url().toString(), QString::number(status), reply->errorString()));
        return;
    }
    const QByteArray body = reply->read(limit + 1);
    if (body.size() > limit) {
        fail(QStringLiteral("%1 is larger than %2 bytes")
                 .arg(reply->url().toString(), QString::number(limit)));
        return;
    }
    if (stage == Stage::Manifest) {
        manifestBody = body;
        stage = Stage::Signature;
        fetch(UpdateEndpoint::signatureUrl(setup.manifest), setup.signatureLimit);
        return;
    }
    stage = Stage::Idle;
    conclude(manifestBody, body);
}

void UpdateCheck::conclude(const QByteArray& manifest, const QByteArray& signature)
{
    // The signature first: not one byte of an unsigned manifest is read as JSON.
    bool genuine = false;
    for (const QByteArray& key : setup.keys) {
        if (SshSig::verify(manifest, signature, key,
                           QByteArray(UpdateManifest::signatureNameSpace))) {
            genuine = true;
            break;
        }
    }
    if (!genuine) {
        fail(QStringLiteral("the manifest is not signed by the release key"));
        return;
    }
    UpdateManifest::Release verified;
    QString error;
    if (!UpdateManifest::parse(manifest, verified, error)) {
        fail(QStringLiteral("the signed manifest was refused: ") + error);
        return;
    }

    UpdateManifest::Version highest;
    UpdateManifest::parseVersion(settings.getUpdateHighestSeen(), highest);
    const QString platform = UpdatePlatform::key(setup.layout.kind);
    const UpdateManifest::Decision decision =
        UpdateManifest::decide(verified, platform, setup.running.version, highest);
    if (UpdateManifest::compare(decision.highestSeen, UpdateManifest::Version{}) > 0) {
        settings.setUpdateHighestSeen(UpdateManifest::toString(decision.highestSeen));
    }
    settings.setUpdateLastCheck(QDateTime::currentSecsSinceEpoch());
    settings.saveGlobal();

    latest = verified;
    offering = decision.offer;
    offered = decision.entry;
    if (offering) {
        const QString version = UpdateManifest::toString(offered.version);
        qInfo() << "Update check: Khandaq" << version << "is available for" << platform;
        emit updateAvailable(version);
    } else {
        qInfo() << "Update check: up to date for" << platform;
        emit upToDate();
    }
}

void UpdateCheck::fail(const QString& why)
{
    stage = Stage::Idle;
    qWarning() << "Update check failed:" << why;
    emit updateCheckFailed();
}
