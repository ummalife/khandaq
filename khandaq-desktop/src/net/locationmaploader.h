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

#include "src/model/air/airlocation.h"

#include <QCache>
#include <QElapsedTimer>
#include <QHash>
#include <QImage>
#include <QNetworkRequest>
#include <QObject>
#include <QQueue>
#include <QString>
#include <QUrl>
#include <QVector>

class QNetworkAccessManager;
class QNetworkProxy;
class QNetworkReply;

/**
 * KHANDAQ (28.11.2026): the maps of the places in the transcript (AirLocationCard), drawn from the
 * tiles of OpenStreetMap as the phones draw theirs (Air::LocationMap).
 *
 * The tiles go through the proxy of the profile, as the requests of the update check do
 * (UpdateNetwork::route): a proxy that cannot be used sends nothing at all, never a direct request
 * past it. As the tile usage policy of the OpenStreetMap Foundation asks, the agent names the app,
 * two tiles at most are on their way at a time, and no request says "no-cache". A map is kept only
 * in the memory of this launch, the 24 drawn last as on Android, never on the disk; a place whose
 * tiles did not all come is asked for again a minute later at the earliest.
 */
class LocationMapLoader : public QObject
{
    Q_OBJECT
public:
    /** The maps of the app, made on first use on the thread of the interface. */
    static LocationMapLoader& instance();

    /** manager makes the requests, a test's own for a test; without one the loader has its own. */
    explicit LocationMapLoader(QNetworkAccessManager* manager = nullptr, QObject* parent = nullptr);
    ~LocationMapLoader() override;

    /** The map of place once it is drawn, else a null image. */
    QImage map(const Air::Location& place) const;
    /** The tiles of place are on their way. */
    bool loading(const Air::Location& place) const;
    /**
     * Fetches the tiles of place through configured, the proxy of the profile, and draws its map,
     * unless the map is drawn or on its way, or its tiles failed less than a minute ago.
     */
    void load(const Air::Location& place, const QNetworkProxy& configured);
    /** Keeps image as the map of place, as a drawing does once its tiles are in. */
    void keep(const Air::Location& place, const QImage& image);

    /** The window of layout from its tiles: tiles[i] is the picture of layout.tiles[i]. */
    static QImage draw(const Air::LocationMap::Layout& layout, const QVector<QImage>& tiles);
    /** The request of a tile: the app's agent, no cookies, nobody's language, no redirect. */
    static QNetworkRequest tileRequest(const QUrl& url);
    /** "KhandaqMessenger/0.2.77", the version this build was made from; without it when unknown. */
    static QString userAgent();

signals:
    /** The map of the place with this key (Air::locationKey) is drawn. */
    void ready(const QString& key);

private:
    struct Job
    {
        Air::Location place;
        Air::LocationMap::Layout layout;
        QVector<QImage> tiles;
        int waiting = 0;
    };
    struct Fetch
    {
        QString key;
        int index = 0;
        QUrl url;
    };

    void next();
    void tileDone(QNetworkReply* reply, const Fetch& fetch);
    void finish(const QString& key);

    QNetworkAccessManager* const manager;
    QCache<QString, QImage> maps;
    QHash<QString, Job> jobs;
    QQueue<Fetch> queue;
    int onTheirWay = 0;
    QHash<QString, qint64> failedAt;
    QElapsedTimer clock;
};
