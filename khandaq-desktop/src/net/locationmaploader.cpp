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

#include "src/net/locationmaploader.h"

#include "src/net/updatecheck.h"
#include "src/net/updatemanifest.h"

#include <QNetworkAccessManager>
#include <QNetworkProxy>
#include <QNetworkReply>
#include <QPainter>
#include <QTimer>

namespace {
/** As Android keeps its maps (LocationMapSnapshot.CACHE). */
constexpr int keptMaps = 24;
/** The tile usage policy: two downloads at a time at most. */
constexpr int parallelTiles = 2;
/** A tile is some kilobytes; a megabyte is no tile. */
constexpr qint64 tileLimit = 1024 * 1024;
/** A server that never answers, or answers a byte at a time, ends here. */
constexpr int tileTimeoutMs = 20 * 1000;
/** A place whose tiles did not all come is asked for again after a minute at the earliest. */
constexpr qint64 retryAfterMs = 60 * 1000;
} // namespace

LocationMapLoader& LocationMapLoader::instance()
{
    // Kept for the whole launch, as AirStyle is.
    static LocationMapLoader* loader = new LocationMapLoader();
    return *loader;
}

LocationMapLoader::LocationMapLoader(QNetworkAccessManager* manager_, QObject* parent)
    : QObject(parent)
    , manager{manager_ ? manager_ : new QNetworkAccessManager(this)}
    , maps{keptMaps}
{
    clock.start();
}

LocationMapLoader::~LocationMapLoader() = default;

QImage LocationMapLoader::map(const Air::Location& place) const
{
    const QImage* const found = maps.object(Air::locationKey(place));
    return found ? *found : QImage();
}

bool LocationMapLoader::loading(const Air::Location& place) const
{
    return jobs.contains(Air::locationKey(place));
}

void LocationMapLoader::load(const Air::Location& place, const QNetworkProxy& configured)
{
    const QString key = Air::locationKey(place);
    if (maps.contains(key) || jobs.contains(key)) {
        return;
    }
    const auto failed = failedAt.constFind(key);
    if (failed != failedAt.constEnd() && clock.elapsed() - failed.value() < retryAfterMs) {
        return;
    }
    QNetworkProxy proxy;
    if (!UpdateNetwork::route(configured, proxy)) {
        qInfo() << "Location map: not fetched, the proxy in the settings has no address or port";
        failedAt.insert(key, clock.elapsed());
        return;
    }
    manager->setProxy(proxy);

    Job job;
    job.place = place;
    job.layout = Air::LocationMap::layoutOf(place);
    job.tiles.resize(job.layout.tiles.size());
    job.waiting = job.layout.tiles.size();
    if (job.waiting == 0) {
        failedAt.insert(key, clock.elapsed());
        return;
    }
    for (int i = 0; i < job.layout.tiles.size(); ++i) {
        Fetch fetch;
        fetch.key = key;
        fetch.index = i;
        fetch.url = Air::LocationMap::tileUrl(job.layout.tiles.at(i));
        queue.enqueue(fetch);
    }
    jobs.insert(key, job);
    next();
}

void LocationMapLoader::keep(const Air::Location& place, const QImage& image)
{
    const QString key = Air::locationKey(place);
    maps.insert(key, new QImage(image));
    failedAt.remove(key);
    emit ready(key);
}

void LocationMapLoader::next()
{
    while (onTheirWay < parallelTiles && !queue.isEmpty()) {
        const Fetch fetch = queue.dequeue();
        if (!jobs.contains(fetch.key)) {
            continue;
        }
        ++onTheirWay;
        QNetworkReply* const reply = manager->get(tileRequest(fetch.url));
        QTimer::singleShot(tileTimeoutMs, reply, &QNetworkReply::abort);
        // Neither a body announced as larger than the limit nor one that grows past it is read.
        connect(reply, &QNetworkReply::metaDataChanged, reply, [reply] {
            const QVariant length = reply->header(QNetworkRequest::ContentLengthHeader);
            if (length.isValid() && length.toLongLong() > tileLimit) {
                reply->abort();
            }
        });
        connect(reply, &QNetworkReply::readyRead, reply, [reply] {
            if (reply->bytesAvailable() > tileLimit) {
                reply->abort();
            }
        });
        connect(reply, &QNetworkReply::finished, this,
                [this, reply, fetch] { tileDone(reply, fetch); });
    }
}

void LocationMapLoader::tileDone(QNetworkReply* reply, const Fetch& fetch)
{
    reply->deleteLater();
    --onTheirWay;
    QImage tile;
    const int status = reply->attribute(QNetworkRequest::HttpStatusCodeAttribute).toInt();
    if (reply->error() == QNetworkReply::NoError && status == 200) {
        const QByteArray body = reply->read(tileLimit + 1);
        if (body.size() <= tileLimit) {
            tile.loadFromData(body, "PNG");
        }
    }
    const auto job = jobs.find(fetch.key);
    if (job != jobs.end()) {
        if (tile.size() == QSize(Air::LocationMap::tileSize, Air::LocationMap::tileSize)) {
            job->tiles[fetch.index] = tile;
        } else {
            qInfo() << "Location map: a tile did not come," << fetch.url.toString() << "answered"
                    << status << reply->errorString();
        }
        if (--job->waiting == 0) {
            finish(fetch.key);
        }
    }
    next();
}

void LocationMapLoader::finish(const QString& key)
{
    const Job job = jobs.take(key);
    for (const QImage& tile : job.tiles) {
        // A map with a hole would stay so for the launch: it is drawn again later instead.
        if (tile.isNull()) {
            failedAt.insert(key, clock.elapsed());
            return;
        }
    }
    keep(job.place, draw(job.layout, job.tiles));
}

QImage LocationMapLoader::draw(const Air::LocationMap::Layout& layout, const QVector<QImage>& tiles)
{
    QImage image(Air::LocationMap::width, Air::LocationMap::height,
                 QImage::Format_ARGB32_Premultiplied);
    // Past the top or the bottom of the map there is no tile: the card's ground shows there.
    image.fill(Qt::transparent);
    QPainter painter(&image);
    for (int i = 0; i < layout.tiles.size() && i < tiles.size(); ++i) {
        if (!tiles.at(i).isNull()) {
            painter.drawImage(layout.tiles.at(i).offset, tiles.at(i));
        }
    }
    painter.end();
    return image;
}

QNetworkRequest LocationMapLoader::tileRequest(const QUrl& url)
{
    QNetworkRequest request(url);
    request.setHeader(QNetworkRequest::UserAgentHeader, userAgent());
    // Qt would send the language of the system; a tile is the same in every language.
    request.setRawHeader("Accept-Language", "*");
    request.setAttribute(QNetworkRequest::RedirectPolicyAttribute,
                         QNetworkRequest::ManualRedirectPolicy);
    request.setAttribute(QNetworkRequest::CookieLoadControlAttribute, QNetworkRequest::Manual);
    request.setAttribute(QNetworkRequest::CookieSaveControlAttribute, QNetworkRequest::Manual);
    // The cache control stays as it is: AlwaysNetwork would send "no-cache", which the tile usage
    // policy forbids. The manager has no cache, so nothing is kept on the disk.
    request.setAttribute(QNetworkRequest::CacheSaveControlAttribute, false);
    return request;
}

QString LocationMapLoader::userAgent()
{
    // As Android names itself (KhandaqMessenger/1.0), with the version of this build.
    const UpdateManifest::Running running = UpdateManifest::running(
        QStringLiteral(GIT_DESCRIBE_EXACT), QStringLiteral(GIT_DESCRIBE));
    return running.known
               ? QStringLiteral("KhandaqMessenger/") + UpdateManifest::toString(running.version)
               : QStringLiteral("KhandaqMessenger");
}
