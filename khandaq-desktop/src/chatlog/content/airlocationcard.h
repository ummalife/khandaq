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

#include "../chatlinecontent.h"

#include "src/model/air/airlocation.h"

#include <QImage>
#include <QString>

class ICoreSettings;
class LocationMapLoader;
class Style;

/**
 * KHANDAQ (28.11.2026): a place a phone shared, in the transcript, as the phones show one: its map,
 * 260 by 120 at most, with the dot of the place in the middle and the attribution OpenStreetMap
 * asks for, then "Location" and the coordinates in the caption. The map is fetched once the entry
 * comes into view (LocationMapLoader); until it is drawn, and without a network, the box shows the
 * glyph of a place at the same size, so the entry keeps its height when the map comes. A click
 * opens the place on openstreetmap.org; a copy and a screen reader take the coordinates, never the
 * words the place came as.
 */
class AirLocationCard : public ChatLineContent
{
    Q_OBJECT
public:
    /**
     * settings gives the proxy the tiles go through, without settings no map is fetched; style the
     * colour of a selection, as a text of the transcript has it.
     */
    AirLocationCard(const Air::Location& place, const ICoreSettings* settings, Style& style,
                    LocationMapLoader& maps);

    const Air::Location& location() const;
    /** The map is drawn: the box no longer shows the glyph. */
    bool showsMap() const;
    /** The box of the map in the card, at its start edge. */
    QRectF mapRect() const;
    /** Where the dot of the place is in the card. */
    QPointF markerPoint() const;

    void setWidth(float width) override;
    QRectF boundingRect() const override;
    void paint(QPainter* painter, const QStyleOptionGraphicsItem* option, QWidget* widget) override;

    // A selection takes the card whole: the coordinates are one piece.
    void selectionMouseMove(QPointF scenePos) override;
    void selectionStarted(QPointF scenePos) override;
    void selectionCleared() override;
    void selectionDoubleClick(QPointF scenePos) override;
    void selectionTripleClick(QPointF scenePos) override;
    bool isOverSelection(QPointF scenePos) const override;
    QString getSelectedText() const override;

    QString getText() const override;
    QString getSpokenText() const override;
    qreal getAscent() const override;
    void visibilityChanged(bool visible) override;
    void reloadTheme() override;

protected:
    void mousePressEvent(QGraphicsSceneMouseEvent* event) override;
    void mouseDoubleClickEvent(QGraphicsSceneMouseEvent* event) override;
    void mouseReleaseEvent(QGraphicsSceneMouseEvent* event) override;

private:
    void mapReady(const QString& readyKey);
    void select(bool on);
    qreal cardWidth() const;

    const Air::Location place;
    const QString key;
    /** The place in the window of its map (Air::LocationMap::Layout::marker). */
    const QPointF placeInMap;
    const ICoreSettings* const settings;
    Style& style;
    LocationMapLoader& maps;
    QImage map;
    qreal width = 0;
    bool selected = false;
    bool pressed = false;
};
