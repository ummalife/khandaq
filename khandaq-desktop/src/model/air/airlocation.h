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

#include <QPoint>
#include <QPointF>
#include <QString>
#include <QUrl>
#include <QVector>

namespace Air {

/**
 * KHANDAQ (28.11.2026): a place a phone shares from a chat. It travels as the words of a message,
 *
 *     khandaq-location:55.75580,37.61730
 *
 * latitude and longitude in degrees with five decimals and a point, whatever the language
 * (LocationMessage.payload on iOS, HelperLocationMessage.encode on Android). Earlier builds of the
 * phones sent "my Location: 55.7558, 37.6173", which the phones still read.
 */
struct Location
{
    double latitude = 0;
    double longitude = 0;
};

/**
 * The place words are, as both phones read one (LocationMessage.parse on iOS,
 * HelperLocationMessage.parse on Android): the words, spaces around them aside, are
 * "khandaq-location:" and two numbers split by a comma; or they hold the old "my Location: LAT,
 * LON" anywhere, in any case. Where the two phones differ the stricter rule holds, so that words
 * around a place are never hidden behind its map: the prefix in small letters, no space and
 * nothing else in the payload. A number is digits with a point and a minus at most; a latitude
 * past 90 degrees either way or a longitude past 180 is no place.
 */
bool readLocation(const QString& words, Location& out);

/**
 * The place a message is: the words its entry shows, its reply's quote and its mention blocks left
 * out (readMessageText), read as readLocation reads them. A form this version cannot read is none.
 */
bool messageLocation(const QString& raw, Location& out);

/** "55.75580, 37.61730": a place under its map, as the phones show one, whatever the language. */
QString locationCoordinates(const Location& place);

/** "55.75580,37.61730": the key of a place's map, as the phones keep theirs. */
QString locationKey(const Location& place);

/**
 * The place on openstreetmap.org, its marker in the middle:
 * https://www.openstreetmap.org/?mlat=LAT&mlon=LON#map=16/LAT/LON
 */
QUrl locationPage(const Location& place);

/**
 * The map of a place as Android draws it (LocationMapSnapshot): the OpenStreetMap tiles of zoom 15,
 * three by three around the tile of the place, and a window of 260 by 120 cut from them with the
 * place in its middle. The place lies in the middle tile, so the window never reaches the edge of
 * the nine; only the tiles it touches are fetched, two to six of them. A tile past the top or the
 * bottom of the map is none, and the ground shows there; the tiles go round the antimeridian.
 */
namespace LocationMap {
constexpr int zoom = 15;
constexpr int tileSize = 256;
constexpr int grid = 3;
constexpr int width = 260;
constexpr int height = 120;

struct Tile
{
    /** The tile on the server. */
    int x = 0;
    int y = 0;
    /** Where the tile's top left corner lies in the window: left of it or above it may be. */
    QPoint offset;
};

struct Layout
{
    QVector<Tile> tiles;
    /** The place in the window: where its dot goes. */
    QPointF marker;
};

Layout layoutOf(const Location& place);

/** https://tile.openstreetmap.org/15/X/Y.png */
QUrl tileUrl(const Tile& tile);
} // namespace LocationMap

} // namespace Air
