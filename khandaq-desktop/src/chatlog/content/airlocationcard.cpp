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

#include "airlocationcard.h"

#include "src/core/icoresettings.h"
#include "src/net/locationmaploader.h"
#include "src/widget/air/airstrings.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/style.h"

#include <QBrush>
#include <QCursor>
#include <QDesktopServices>
#include <QFontMetricsF>
#include <QGraphicsSceneMouseEvent>
#include <QGuiApplication>
#include <QPainter>
#include <QPainterPath>
#include <QStyle>

#include <tuple>

using AirTokens::Color;
using AirTokens::TextRole;
namespace Radius = AirTokens::Radius;
namespace Space = AirTokens::Space;

namespace {
AirStyle& air()
{
    return AirStyle::instance();
}

/**
 * The attribution the tile usage policy of the OpenStreetMap Foundation asks for, on the map. A
 * name and a sign, the same in every language.
 */
const QString attribution = QStringLiteral("© OpenStreetMap");

/** The glyph in the box while there is no map: 32 px, on the 24 unit grid of the Air icons. */
constexpr int glyphSize = Space::s32;

/** The start edge of the interface as an absolute alignment: the view does not mirror it again. */
Qt::Alignment startEdge()
{
    return QStyle::visualAlignment(QGuiApplication::layoutDirection(),
                                   Qt::AlignLeft | Qt::AlignVCenter);
}

qreal titleLine()
{
    return air().lineHeight(TextRole::Body);
}

qreal coordinatesLine()
{
    return air().lineHeight(TextRole::Caption);
}

/** A map pin in outline, as the Air icons are drawn: 24 units, a stroke of 1.7. */
QPainterPath placeGlyph()
{
    QPainterPath pin;
    pin.moveTo(19, 10);
    pin.cubicTo(19, 15.2, 12, 21.2, 12, 21.2);
    pin.cubicTo(12, 21.2, 5, 15.2, 5, 10);
    pin.arcTo(QRectF(5, 3, 14, 14), 180, -180);
    pin.closeSubpath();
    pin.addEllipse(QPointF(12, 10), 2.5, 2.5);
    return pin;
}

void paintGlyph(QPainter& painter, const QRectF& box)
{
    painter.save();
    painter.translate(box.center().x() - glyphSize / 2.0, box.center().y() - glyphSize / 2.0);
    painter.scale(glyphSize / 24.0, glyphSize / 24.0);
    painter.setBrush(Qt::NoBrush);
    painter.setPen(QPen(air().color(Color::Ink3), AirTokens::Stroke::icon, Qt::SolidLine,
                        Qt::RoundCap, Qt::RoundJoin));
    painter.drawPath(placeGlyph());
    painter.restore();
}

/**
 * The dot of the place as Android draws it on its map (LocationMapSnapshot.drawMarker): its shadow
 * 4 px lower, red, a white ring. The map is the same in both themes, and so is the dot.
 */
void paintMarker(QPainter& painter, const QPointF& at)
{
    painter.save();
    painter.setPen(Qt::NoPen);
    painter.setBrush(QColor(0, 0, 0, 90));
    painter.drawEllipse(QPointF(at.x(), at.y() + 4), 8, 8);
    painter.setBrush(QColor(220, 53, 69));
    painter.setPen(QPen(QColor(Qt::white), 2));
    painter.drawEllipse(at, 7, 7);
    painter.restore();
}

/** The attribution in the end corner at the foot of the map, on a light plate, as the site has it. */
void paintAttribution(QPainter& painter, const QRectF& box, const QPainterPath& rounded)
{
    QFont font = air().font(TextRole::Caption);
    // Smaller than any text of the interface: a mark on the map, not words of the message.
    font.setPixelSize(10);
    const QFontMetricsF metrics(font);
    const QSizeF size(metrics.horizontalAdvance(attribution) + 2 * Space::s6, metrics.height());
    const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
    const QRectF plate(rightToLeft ? box.left() : box.right() - size.width(),
                       box.bottom() - size.height(), size.width(), size.height());
    QPainterPath shape;
    shape.addRect(plate);
    painter.save();
    painter.setPen(Qt::NoPen);
    // The corner of the plate follows the corner of the map.
    painter.fillPath(shape.intersected(rounded), QColor(255, 255, 255, 190));
    painter.setFont(font);
    painter.setPen(QColor(0, 0, 0, 190));
    painter.drawText(plate, Qt::AlignCenter | Qt::TextSingleLine, attribution);
    painter.restore();
}
} // namespace

AirLocationCard::AirLocationCard(const Air::Location& place_, const ICoreSettings* settings_,
                                 Style& style_, LocationMapLoader& maps_)
    : place{place_}
    , key{Air::locationKey(place_)}
    , placeInMap{Air::LocationMap::layoutOf(place_).marker}
    , settings{settings_}
    , style{style_}
    , maps{maps_}
    , map{maps_.map(place_)}
{
    setCursor(Qt::PointingHandCursor);
    connect(&maps, &LocationMapLoader::ready, this, &AirLocationCard::mapReady);
}

const Air::Location& AirLocationCard::location() const
{
    return place;
}

bool AirLocationCard::showsMap() const
{
    return !map.isNull();
}

qreal AirLocationCard::cardWidth() const
{
    return qBound<qreal>(0, width, Air::LocationMap::width);
}

QRectF AirLocationCard::mapRect() const
{
    return QRectF(0, 0, cardWidth(), Air::LocationMap::height);
}

QPointF AirLocationCard::markerPoint() const
{
    // A card narrower than the map shows the middle of it.
    return placeInMap - QPointF((Air::LocationMap::width - cardWidth()) / 2.0, 0);
}

void AirLocationCard::setWidth(float width_)
{
    prepareGeometryChange();
    width = width_;
}

QRectF AirLocationCard::boundingRect() const
{
    // The same height with the map and without it: the entry does not move when the map comes.
    return QRectF(0, 0, cardWidth(),
                  Air::LocationMap::height + Space::s8 + titleLine() + coordinatesLine());
}

void AirLocationCard::paint(QPainter* painter, const QStyleOptionGraphicsItem* option,
                            QWidget* widget)
{
    std::ignore = option;
    std::ignore = widget;
    const QRectF box = mapRect();
    QPainterPath rounded;
    rounded.addRoundedRect(box, Radius::card, Radius::card);

    painter->save();
    painter->setRenderHint(QPainter::Antialiasing);
    painter->setRenderHint(QPainter::SmoothPixmapTransform);
    painter->fillPath(rounded, air().color(Color::Sunken));
    if (map.isNull()) {
        paintGlyph(*painter, box);
    } else {
        QBrush picture(map);
        picture.setTransform(QTransform::fromTranslate(
            box.left() - (Air::LocationMap::width - box.width()) / 2.0, box.top()));
        painter->fillPath(rounded, picture);
        paintMarker(*painter, markerPoint());
        paintAttribution(*painter, box, rounded);
    }
    painter->restore();

    const QFont titleFont = air().font(TextRole::Body);
    const QFont coordinatesFont = air().font(TextRole::Caption);
    const QRectF title(0, box.bottom() + Space::s8, box.width(), titleLine());
    const QRectF coordinates(0, title.bottom(), box.width(), coordinatesLine());
    const QString titleText = QFontMetricsF(titleFont).elidedText(
        AirStrings::text(AirStrings::Text::LocationTitle), Qt::ElideRight, title.width());
    const QString coordinatesText = QFontMetricsF(coordinatesFont).elidedText(
        Air::locationCoordinates(place), Qt::ElideRight, coordinates.width());
    const int flags = static_cast<int>(startEdge()) | Qt::TextSingleLine;

    // Selected, the words stand on the colour a selected text of the transcript has (Text).
    const auto line = [&](const QRectF& rect, const QFont& font, const QString& words,
                          const QColor& ink) {
        painter->setFont(font);
        if (selected) {
            const qreal advance = qMin(rect.width(), QFontMetricsF(font).horizontalAdvance(words));
            const bool rightToLeft = QGuiApplication::layoutDirection() == Qt::RightToLeft;
            painter->fillRect(QRectF(rightToLeft ? rect.right() - advance : rect.left(), rect.top(),
                                     advance, rect.height()),
                              style.getColor(Style::ColorPalette::SelectText));
            painter->setPen(QColor(Qt::white));
        } else {
            painter->setPen(ink);
        }
        painter->drawText(rect, flags, words);
    };
    line(title, titleFont, titleText, air().color(Color::Ink));
    line(coordinates, coordinatesFont, coordinatesText, air().color(Color::Ink2));
}

void AirLocationCard::select(bool on)
{
    if (selected == on) {
        return;
    }
    selected = on;
    update();
}

void AirLocationCard::selectionMouseMove(QPointF scenePos)
{
    std::ignore = scenePos;
    select(true);
}

void AirLocationCard::selectionStarted(QPointF scenePos)
{
    std::ignore = scenePos;
    // A drag that started here selects; it opens nothing when it ends.
    pressed = false;
}

void AirLocationCard::selectionCleared()
{
    select(false);
}

void AirLocationCard::selectionDoubleClick(QPointF scenePos)
{
    std::ignore = scenePos;
    pressed = false;
    select(true);
}

void AirLocationCard::selectionTripleClick(QPointF scenePos)
{
    std::ignore = scenePos;
    pressed = false;
    select(true);
}

bool AirLocationCard::isOverSelection(QPointF scenePos) const
{
    return selected && boundingRect().contains(mapFromScene(scenePos));
}

QString AirLocationCard::getSelectedText() const
{
    return selected ? getText() : QString();
}

QString AirLocationCard::getText() const
{
    return Air::locationCoordinates(place);
}

QString AirLocationCard::getSpokenText() const
{
    return AirStrings::locationWords(place);
}

qreal AirLocationCard::getAscent() const
{
    return QFontMetricsF(air().font(TextRole::Body)).ascent();
}

void AirLocationCard::visibilityChanged(bool visible)
{
    if (visible && map.isNull() && settings) {
        maps.load(place, settings->getProxy());
    }
}

void AirLocationCard::reloadTheme()
{
    update();
}

void AirLocationCard::mousePressEvent(QGraphicsSceneMouseEvent* event)
{
    if (event->button() != Qt::LeftButton) {
        event->ignore();
        return;
    }
    // The grabber, as a text with a link is: the release on the card opens the place.
    event->accept();
    pressed = true;
}

void AirLocationCard::mouseDoubleClickEvent(QGraphicsSceneMouseEvent* event)
{
    // The second click of a double click opens the place no second time.
    event->accept();
    pressed = false;
}

void AirLocationCard::mouseReleaseEvent(QGraphicsSceneMouseEvent* event)
{
    const bool click =
        pressed && event->button() == Qt::LeftButton && boundingRect().contains(event->pos());
    pressed = false;
    if (click) {
        QDesktopServices::openUrl(Air::locationPage(place));
    }
}

void AirLocationCard::mapReady(const QString& readyKey)
{
    if (readyKey != key) {
        return;
    }
    map = maps.map(place);
    update();
}
