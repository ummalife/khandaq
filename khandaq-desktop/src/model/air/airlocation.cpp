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

#include "src/model/air/airlocation.h"

#include "src/model/air/airmodel.h"

#include <QRegularExpression>
#include <QtMath>

#include <cmath>

namespace Air {

namespace {
const QString payloadPrefix = QStringLiteral("khandaq-location:");

/** Degrees as both phones read them: digits with a point and a minus at most, and finite. */
bool readDegrees(const QString& text, double& out)
{
    static const QRegularExpression number(
        QStringLiteral("\\A-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)\\z"));
    if (!number.match(text).hasMatch()) {
        return false;
    }
    bool ok = false;
    // QString::toDouble reads the C locale's numbers, never the user's.
    const double value = text.toDouble(&ok);
    if (!ok || !std::isfinite(value)) {
        return false;
    }
    out = value;
    return true;
}

bool readPair(const QString& latitude, const QString& longitude, Location& out)
{
    Location place;
    if (!readDegrees(latitude, place.latitude) || !readDegrees(longitude, place.longitude)
        || std::fabs(place.latitude) > 90.0 || std::fabs(place.longitude) > 180.0) {
        return false;
    }
    out = place;
    return true;
}

QString degrees(double value)
{
    // Five decimals and a point in every language, as the phones format them (Locale.US).
    return QString::number(value, 'f', 5);
}
} // namespace

bool readLocation(const QString& words, Location& out)
{
    const QString trimmed = words.trimmed();
    if (trimmed.startsWith(payloadPrefix)) {
        const QString pair = trimmed.mid(payloadPrefix.size());
        const int comma = pair.indexOf(QLatin1Char(','));
        return comma > 0 && readPair(pair.left(comma), pair.mid(comma + 1), out);
    }
    static const QRegularExpression legacy(
        QStringLiteral("my\\s+Location:\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)"),
        QRegularExpression::CaseInsensitiveOption);
    const QRegularExpressionMatch match = legacy.match(words);
    return match.hasMatch() && readPair(match.captured(1), match.captured(2), out);
}

bool messageLocation(const QString& raw, Location& out)
{
    const MessageText text = readMessageText(raw);
    return !text.unreadable && readLocation(text.body, out);
}

QString locationCoordinates(const Location& place)
{
    return degrees(place.latitude) + QStringLiteral(", ") + degrees(place.longitude);
}

QString locationKey(const Location& place)
{
    return degrees(place.latitude) + QLatin1Char(',') + degrees(place.longitude);
}

QUrl locationPage(const Location& place)
{
    const QString latitude = degrees(place.latitude);
    const QString longitude = degrees(place.longitude);
    QUrl page(QStringLiteral("https://www.openstreetmap.org/"));
    page.setQuery(QStringLiteral("mlat=%1&mlon=%2").arg(latitude, longitude));
    page.setFragment(QStringLiteral("map=16/%1/%2").arg(latitude, longitude));
    return page;
}

namespace LocationMap {

namespace {
/** Web Mercator ends here: a place nearer a pole is drawn at the edge of the map. */
constexpr double mercatorLimit = 85.0511287798066;

/** The place in tiles of the zoom, from the left edge of the map and from its top. */
double tilesFromLeft(double longitude)
{
    return (longitude + 180.0) / 360.0 * (1 << zoom);
}

double tilesFromTop(double latitude)
{
    const double radians = qDegreesToRadians(qBound(-mercatorLimit, latitude, mercatorLimit));
    return (1.0 - std::log(std::tan(radians) + 1.0 / std::cos(radians)) / M_PI) / 2.0
           * (1 << zoom);
}
} // namespace

Layout layoutOf(const Location& place)
{
    const int tilesAcross = 1 << zoom;
    const double x = tilesFromLeft(place.longitude);
    const double y = tilesFromTop(place.latitude);
    // The first of the nine tiles, and the place in the canvas they make.
    const int firstX = static_cast<int>(std::floor(x)) - grid / 2;
    const int firstY = static_cast<int>(std::floor(y)) - grid / 2;
    const double pixelX = (x - firstX) * tileSize;
    const double pixelY = (y - firstY) * tileSize;
    // The window as Android cuts it, from the whole pixel of the place.
    const int canvas = grid * tileSize;
    const int left = qBound(0, static_cast<int>(pixelX) - width / 2, canvas - width);
    const int top = qBound(0, static_cast<int>(pixelY) - height / 2, canvas - height);

    Layout layout;
    layout.marker = QPointF(pixelX - left, pixelY - top);
    for (int row = top / tileSize; row <= (top + height - 1) / tileSize; ++row) {
        const int tileY = firstY + row;
        if (tileY < 0 || tileY >= tilesAcross) {
            continue;
        }
        for (int column = left / tileSize; column <= (left + width - 1) / tileSize; ++column) {
            Tile tile;
            tile.x = ((firstX + column) % tilesAcross + tilesAcross) % tilesAcross;
            tile.y = tileY;
            tile.offset = QPoint(column * tileSize - left, row * tileSize - top);
            layout.tiles.append(tile);
        }
    }
    return layout;
}

QUrl tileUrl(const Tile& tile)
{
    return QUrl(QStringLiteral("https://tile.openstreetmap.org/%1/%2/%3.png")
                    .arg(zoom)
                    .arg(tile.x)
                    .arg(tile.y));
}

} // namespace LocationMap

} // namespace Air
