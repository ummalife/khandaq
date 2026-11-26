/*
    Copyright © 2015-2019 by The qTox Project Contributors

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

#include "movablewidget.h"
#include <QApplication>
#include <QMouseEvent>
#include <QPropertyAnimation>

namespace {
/** The speed of a release is measured over the last stretch of the drag. */
constexpr ulong velocityWindowMs = 100;
/** A drag held still this long before the release lets go without speed. */
constexpr ulong stillMs = 60;
} // namespace

MovableWidget::MovableWidget(QWidget* parent)
    : QWidget(parent)
{
    setSizePolicy(QSizePolicy(QSizePolicy::Ignored, QSizePolicy::Ignored));
    setCursor(Qt::PointingHandCursor);
    boundaryRect = QRect(0, 0, 0, 0);
}

void MovableWidget::resetBoundary(QRect newBoundary)
{
    boundaryRect = newBoundary;
    QPoint moveTo = pos();
    checkBoundary(moveTo);
    move(moveTo);
}

void MovableWidget::slideTo(const QPoint& topLeft, int duration, const QEasingCurve& curve)
{
    stopSlide();
    if (duration <= 0 || topLeft == pos()) {
        move(topLeft);
        return;
    }
    auto* const animation = new QPropertyAnimation(this, "pos", this);
    animation->setStartValue(pos());
    animation->setEndValue(topLeft);
    animation->setDuration(duration);
    animation->setEasingCurve(curve);
    slide = animation;
    animation->start(QAbstractAnimation::DeleteWhenStopped);
}

void MovableWidget::stopSlide()
{
    if (slide) {
        slide->stop();
    }
}

void MovableWidget::mousePressEvent(QMouseEvent* event)
{
    if (!(event->buttons() & Qt::LeftButton)) {
        return;
    }
    stopSlide();
    pressed = true;
    dragging = false;
    pressPoint = event->globalPos();
    lastPoint = event->globalPos();
    samples.clear();
    sample(event->timestamp(), pos());
}

void MovableWidget::mouseMoveEvent(QMouseEvent* event)
{
    if (!pressed) {
        return;
    }
    if (!dragging
        && (event->globalPos() - pressPoint).manhattanLength() >= QApplication::startDragDistance()) {
        dragging = true;
    }
    if (!dragging) {
        return;
    }
    QPoint moveTo = pos() - (lastPoint - event->globalPos());
    checkBoundary(moveTo);
    move(moveTo);
    lastPoint = event->globalPos();
    sample(event->timestamp(), pos());
}

void MovableWidget::mouseReleaseEvent(QMouseEvent* event)
{
    if (!pressed || event->button() != Qt::LeftButton) {
        return;
    }
    pressed = false;
    if (!dragging) {
        emit clicked();
        return;
    }
    dragging = false;
    emit dropped(velocityAt(event->timestamp()));
}

void MovableWidget::sample(ulong time, const QPointF& position)
{
    samples.append(Sample{time, position});
    // Only the last stretch counts.
    while (samples.size() > 2 && time - samples.first().time > velocityWindowMs) {
        samples.removeFirst();
    }
}

QPointF MovableWidget::velocityAt(ulong time) const
{
    if (samples.size() < 2) {
        return QPointF();
    }
    const Sample& last = samples.last();
    if (time > last.time && time - last.time > stillMs) {
        return QPointF();
    }
    const Sample& first = samples.first();
    const ulong span = last.time - first.time;
    if (last.time <= first.time || span == 0) {
        return QPointF();
    }
    return (last.position - first.position) * (1000.0 / span);
}

void MovableWidget::checkBoundary(QPoint& point) const
{
    if (point.x() < boundaryRect.left())
        point.setX(boundaryRect.left());

    if (point.y() < boundaryRect.top())
        point.setY(boundaryRect.top());

    if (point.x() + width() > boundaryRect.right() + 1)
        point.setX(boundaryRect.right() - width() + 1);

    if (point.y() + height() > boundaryRect.bottom() + 1)
        point.setY(boundaryRect.bottom() - height() + 1);
}
