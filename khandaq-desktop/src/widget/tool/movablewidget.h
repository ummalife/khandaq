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

#pragma once

#include <QEasingCurve>
#include <QPointer>
#include <QVector>
#include <QWidget>

class QPropertyAnimation;

/**
 * A widget the mouse drags within a boundary. KHANDAQ (calls stage, 26.11): it is the box of the
 * small video of a call now. A press and a release without a drag is a click (the stage swaps its
 * two videos); a drag ends with the speed the mouse had, so the stage can throw the box to the
 * corner it went for and slide it there. The box takes its size from the stage (SPEC R3), so the
 * resizing by its edges and the half transparency of a double click are gone: the one fought the
 * size, the other the click.
 */
class MovableWidget : public QWidget
{
    Q_OBJECT
public:
    explicit MovableWidget(QWidget* parent);
    /** Where the widget may be dragged; it moves inside at once if it stands outside. */
    void resetBoundary(QRect newBoundary);
    /**
     * Moves the widget's top left to topLeft over duration ms along curve, or at once for 0 or
     * less. A press or a new move stops a slide under way.
     */
    void slideTo(const QPoint& topLeft, int duration, const QEasingCurve& curve = QEasingCurve());

signals:
    /** A press and a release with no drag between them. */
    void clicked();
    /** A drag ended where the widget stands, moving at velocity (px per second) when let go. */
    void dropped(const QPointF& velocity);

protected:
    void mousePressEvent(QMouseEvent* event) override;
    void mouseMoveEvent(QMouseEvent* event) override;
    void mouseReleaseEvent(QMouseEvent* event) override;

private:
    struct Sample
    {
        ulong time;
        QPointF position;
    };

    void checkBoundary(QPoint& point) const;
    void stopSlide();
    void sample(ulong time, const QPointF& position);
    QPointF velocityAt(ulong time) const;

    bool pressed = false;
    bool dragging = false;
    QPoint pressPoint;
    QPoint lastPoint;
    QRect boundaryRect;
    QVector<Sample> samples;
    QPointer<QPropertyAnimation> slide;
};
