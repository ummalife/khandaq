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

#include "aircallstage.h"

#include "src/widget/air/airtokens.h"

#include <QtGlobal>

namespace Air {
namespace CallStage {

namespace {

/** The short side of the self view on the desktop: a share of the stage, within two bounds. */
constexpr double selfShare = 0.22;
constexpr int selfShortestSide = 96;
constexpr int selfLongestShortSide = 200;
/** 4 columns of 96 px make the 384 px row of a wide stage. */
constexpr int controlColumn = 96;
/** The words under a control shrink to 80 % of the label size, and never under 11 px. */
constexpr qreal labelShrink = 0.8;
constexpr qreal labelSmallestPixels = 11.0;

/** a * b / c rounded half up, for sizes, which are never negative. */
int scaled(qint64 a, qint64 b, qint64 c)
{
    return static_cast<int>((2 * a * b + c) / (2 * c));
}

} // namespace

bool isLandscape(const QSize& size)
{
    return size.width() >= size.height();
}

Scale stageScale(const QSize& frame, const QSize& stage)
{
    if (frame.isEmpty() || stage.isEmpty()) {
        return Scale::Fill;
    }
    return isLandscape(frame) == isLandscape(stage) ? Scale::Fill : Scale::Fit;
}

QRect videoRect(const QSize& frame, const QRect& box, Scale scale)
{
    if (frame.isEmpty() || box.isEmpty()) {
        return box;
    }
    const qint64 frameWidth = frame.width();
    const qint64 frameHeight = frame.height();
    const qint64 boxWidth = box.width();
    const qint64 boxHeight = box.height();
    // Fit meets the box with the frame's relatively wider side, Fill with the other one.
    const bool wider = frameWidth * boxHeight > frameHeight * boxWidth;
    const bool widthMeets = (scale == Scale::Fit) == wider;
    const int width =
        widthMeets ? box.width() : scaled(boxHeight, frameWidth, frameHeight);
    const int height =
        widthMeets ? scaled(boxWidth, frameHeight, frameWidth) : box.height();
    return QRect(box.x() + (box.width() - width) / 2, box.y() + (box.height() - height) / 2, width,
                 height);
}

int selfInset()
{
    return AirTokens::Space::s16;
}

QSize selfSize(const QSize& stage, const QSize& frame)
{
    const int shortest = qMin(stage.width(), stage.height());
    const int side = qBound(selfShortestSide, qRound(selfShare * shortest), selfLongestShortSide);
    const QSize shape = frame.isEmpty() ? QSize(16, 9) : frame;
    const qint64 longSide = qMax(shape.width(), shape.height());
    const qint64 shortSide = qMin(shape.width(), shape.height());
    const int other = scaled(side, longSide, shortSide);
    return isLandscape(shape) ? QSize(other, side) : QSize(side, other);
}

QSize fitInto(const QSize& size, const QSize& room)
{
    if (size.isEmpty() || (size.width() <= room.width() && size.height() <= room.height())) {
        return size;
    }
    if (room.isEmpty()) {
        return QSize(0, 0);
    }
    const qint64 width = size.width();
    const qint64 height = size.height();
    const qint64 roomWidth = room.width();
    const qint64 roomHeight = room.height();
    if (width * roomHeight > height * roomWidth) {
        return QSize(room.width(), scaled(roomWidth, height, width));
    }
    return QSize(scaled(roomHeight, width, height), room.height());
}

QRect selfArea(const QRect& stage, int headerBottom, int rowTop)
{
    const int inset = selfInset();
    int top = stage.top() + inset;
    int bottom = stage.top() + stage.height() - inset;
    if (headerBottom >= 0) {
        top = qMax(top, headerBottom + inset);
    }
    if (rowTop >= 0) {
        bottom = qMin(bottom, rowTop - inset);
    }
    return QRect(stage.left() + inset, top, qMax(0, stage.width() - 2 * inset),
                 qMax(0, bottom - top));
}

QRect cornerRect(const QRect& area, const QSize& size, Qt::Corner corner)
{
    const bool left = corner == Qt::TopLeftCorner || corner == Qt::BottomLeftCorner;
    const bool top = corner == Qt::TopLeftCorner || corner == Qt::TopRightCorner;
    const int x = left ? area.left() : area.left() + area.width() - size.width();
    const int y = top ? area.top() : area.top() + area.height() - size.height();
    return QRect(QPoint(x, y), size);
}

Qt::Corner defaultCorner(Qt::LayoutDirection direction)
{
    return direction == Qt::RightToLeft ? Qt::BottomLeftCorner : Qt::BottomRightCorner;
}

Qt::Corner snapCorner(const QRect& area, const QSize& size, const QPointF& released,
                      const QPointF& velocity)
{
    const QPointF centre =
        released + QPointF(size.width() / 2.0, size.height() / 2.0) + velocity * throwSeconds;
    // The four places lie on the corners of a rectangle, so the nearest one is on the same side of
    // its middle on both axes. The middle does not depend on the size of the box.
    const qreal middleX = area.left() + area.width() / 2.0;
    const qreal middleY = area.top() + area.height() / 2.0;
    const bool left = centre.x() < middleX;
    const bool top = centre.y() < middleY;
    if (top) {
        return left ? Qt::TopLeftCorner : Qt::TopRightCorner;
    }
    return left ? Qt::BottomLeftCorner : Qt::BottomRightCorner;
}

void Views::swap()
{
    swapped = !swapped;
}

bool Views::isSwapped() const
{
    return swapped;
}

Views::Video Views::big() const
{
    return swapped ? Video::Self : Video::Remote;
}

Views::Video Views::small() const
{
    return swapped ? Video::Remote : Video::Self;
}

Scale Views::scaleOf(Video video, const QSize& frame, const QSize& stage) const
{
    return video == big() ? stageScale(frame, stage) : Scale::Fill;
}

int columnWidth()
{
    return controlColumn;
}

QRect rowRect(const QRect& stage, int count, int height)
{
    const int inset = selfInset();
    const int room = qMax(0, stage.width() - 2 * inset);
    const int column = count > 0 ? qMin(controlColumn, room / count) : 0;
    const int width = column * count;
    const int x = stage.left() + (stage.width() - width) / 2;
    const int y = stage.top() + stage.height() - AirTokens::Space::s24 - height;
    return QRect(x, y, width, height);
}

qreal labelPixelSize(qreal basePixelSize, qreal textWidth, qreal width)
{
    if (textWidth <= width || textWidth <= 0) {
        return basePixelSize;
    }
    const qreal smallest =
        qMin(basePixelSize, qMax(basePixelSize * labelShrink, labelSmallestPixels));
    return qMax(smallest, basePixelSize * width / textWidth);
}

} // namespace CallStage
} // namespace Air
