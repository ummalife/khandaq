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

#include "videosurface.h"
#include "src/core/core.h"
#include "src/model/friend.h"
#include "src/friendlist.h"
#include "src/model/air/aircallstage.h"
#include "src/persistence/settings.h"
#include "src/video/videoframe.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airwidgets.h"
#include "src/widget/friendwidget.h"
#include "src/widget/style.h"

#include <QDebug>
#include <QLabel>
#include <QPainter>
#include <QPainterPath>
#include <QSvgRenderer>

namespace {
float getSizeRatio(const QSize size)
{
    return size.width() / static_cast<float>(size.height());
}

QPixmap loadPlaceholderAvatar(const QSize& bounds,
                              const QString& path = QStringLiteral(":/img/contact_dark.svg"))
{
    QSvgRenderer render(path);
    QSizeF naturalSize = render.defaultSize();
    if (naturalSize.isEmpty() || naturalSize.width() <= 0 || naturalSize.height() <= 0) {
        naturalSize = QSizeF(1, 1);
    }

    QSize targetSize = naturalSize.toSize();
    targetSize.scale(bounds, Qt::KeepAspectRatio);

    QPixmap pixmap(targetSize);
    pixmap.fill(Qt::transparent);
    QPainter painter(&pixmap);
    render.render(&painter, pixmap.rect());
    return pixmap;
}

QRect centeredPixmapRect(const QPixmap& pixmap, const QRect& container)
{
    const QPoint topLeft(container.x() + (container.width() - pixmap.width()) / 2,
                         container.y() + (container.height() - pixmap.height()) / 2);
    return QRect(topLeft, pixmap.size());
}

/**
 * KHANDAQ (calls stage, 26.11): the avatar on the stage until the person's video comes, at the
 * size of an audio call on the desktop.
 */
constexpr int stageAvatarSide = 128;
/** The share of the white of fixed-white in the hairline round the small video of a call. */
constexpr qreal boxBorderAlpha = 0.2;
} // namespace

/**
 * @var std::atomic_bool VideoSurface::frameLock
 * @brief Fast lock for lastFrame.
 */
VideoSurface::VideoSurface(const QPixmap& avatar_, QWidget* parent, bool expanding_)
    : QWidget{parent}
    , source{nullptr}
    , frameLock{false}
    , hasSubscribed{0}
    , avatar{avatar_}
    , ratio{1.0f}
    , scaling{expanding_ ? Scaling::Fill : Scaling::Fit}
{
    recalulateBounds();
}

VideoSurface::VideoSurface(const QPixmap& avatar_, VideoSource* source_, QWidget* parent)
    : VideoSurface(avatar_, parent)
{
    setSource(source_);
}

VideoSurface::~VideoSurface()
{
    unsubscribe();
}

bool VideoSurface::isExpanding() const
{
    return scaling == Scaling::Fill;
}

/**
 * @brief Update source.
 * @note nullptr is a valid option.
 * @param src source to set.
 *
 * Unsubscribe from old source and subscribe to new.
 */
void VideoSurface::setSource(VideoSource* src)
{
    if (source == src)
        return;

    unsubscribe();
    source = src;
    subscribe();
}

QRect VideoSurface::getBoundingRect() const
{
    QRect bRect = boundingRect;
    bRect.setBottomRight(QPoint(boundingRect.bottom() + 1, boundingRect.right() + 1));
    return boundingRect;
}

float VideoSurface::getRatio() const
{
    return ratio;
}

void VideoSurface::setAvatar(const QPixmap& pixmap)
{
    avatar = pixmap;
    update();
}

QPixmap VideoSurface::getAvatar() const
{
    return avatar;
}

void VideoSurface::setScaling(Scaling scaling_)
{
    if (scaling != scaling_) {
        scaling = scaling_;
        recalulateBounds();
        emit boundaryChanged();
    }
}

VideoSurface::Scaling VideoSurface::getScaling() const
{
    return scaling;
}

void VideoSurface::setLook(Look look_)
{
    if (look != look_) {
        look = look_;
        update();
    }
}

VideoSurface::Look VideoSurface::getLook() const
{
    return look;
}

void VideoSurface::setMirrored(bool mirrored_)
{
    if (mirrored != mirrored_) {
        mirrored = mirrored_;
        update();
    }
}

bool VideoSurface::isMirrored() const
{
    return mirrored;
}

QSize VideoSurface::getFrameSize() const
{
    return frameSize;
}

bool VideoSurface::hasFrame()
{
    lock();
    const bool has = lastFrame != nullptr;
    unlock();
    return has;
}

void VideoSurface::subscribe()
{
    if (source && hasSubscribed++ == 0) {
        source->subscribe();
        connect(source, &VideoSource::frameAvailable, this, &VideoSurface::onNewFrameAvailable);
        connect(source, &VideoSource::sourceStopped, this, &VideoSurface::onSourceStopped);
    }
}

void VideoSurface::unsubscribe()
{
    if (!source || hasSubscribed == 0)
        return;

    if (--hasSubscribed != 0)
        return;

    lock();
    lastFrame.reset();
    unlock();

    ratio = 1.0f;
    frameSize = QSize();
    recalulateBounds();
    emit ratioChanged();
    emit boundaryChanged();

    disconnect(source, &VideoSource::frameAvailable, this, &VideoSurface::onNewFrameAvailable);
    disconnect(source, &VideoSource::sourceStopped, this, &VideoSurface::onSourceStopped);
    source->unsubscribe();
}

void VideoSurface::onNewFrameAvailable(const std::shared_ptr<VideoFrame>& newFrame)
{
    QSize newSize;

    lock();
    lastFrame = newFrame;
    newSize = lastFrame->getSourceDimensions().size();
    unlock();

    // KHANDAQ (calls stage, 26.11): the size is followed while the surface is hidden too, so the
    // box of a call has its shape when it shows again; a new size of the same shape counts too.
    if (newSize != frameSize && !newSize.isEmpty()) {
        frameSize = newSize;
        ratio = getSizeRatio(newSize);
        recalulateBounds();
        emit ratioChanged();
        emit boundaryChanged();
    }

    update();
}

void VideoSurface::onSourceStopped()
{
    // If the source's stream is on hold, just revert back to the avatar view
    lastFrame.reset();
    update();
}

void VideoSurface::paintEvent(QPaintEvent* event)
{
    std::ignore = event;
    lock();

    QPainter painter(this);
    painter.setRenderHint(QPainter::Antialiasing);
    painter.setRenderHint(QPainter::SmoothPixmapTransform);
    bool painted = false;
    if (lastFrame) {
        // KHANDAQ (calls stage, 26.11): one resample with the aspect kept. The frame used to be
        // scaled to the whole widget first and then into its rect, unevenly both times and the
        // second time without smoothing. Now it is converted at the size it is drawn at, but never
        // above its own (swscale shrinks it), and a frame drawn bigger than it is grows in the
        // painter, smoothly.
        const QSize source = lastFrame->getSourceDimensions().size();
        const QRect area = contentsRect();
        const QRect drawn = Air::CallStage::videoRect(
            source, area, fills(source) ? Air::CallStage::Scale::Fill : Air::CallStage::Scale::Fit);
        const qreal dpr = devicePixelRatioF();
        QSize pixels(qRound(drawn.width() * dpr), qRound(drawn.height() * dpr));
        if (pixels.width() > source.width() || pixels.height() > source.height()) {
            pixels = source;
        }
        if (!pixels.isEmpty()) {
            const QImage frame = lastFrame->toQImage(pixels);
            if (frame.isNull()) {
                lastFrame.reset();
            } else {
                paintFrame(painter, frame, drawn);
                painted = true;
            }
        }
    }
    if (!painted) {
        paintWithoutFrame(painter);
    }

    unlock();
}

bool VideoSurface::fills(const QSize& frame) const
{
    switch (scaling) {
    case Scaling::Fit:
        return false;
    case Scaling::Fill:
        return true;
    case Scaling::Stage:
        break;
    }
    return Air::CallStage::stageScale(frame, contentsRect().size()) == Air::CallStage::Scale::Fill;
}

void VideoSurface::paintFrame(QPainter& painter, const QImage& frame, const QRect& drawn)
{
    using AirTokens::Color;
    if (look == Look::Plain) {
        painter.fillRect(painter.viewport(), Qt::black);
    } else if (look == Look::Stage) {
        painter.fillRect(rect(), AirStyle::darkColor(Color::FixedBlack));
    }

    painter.save();
    if (mirrored) {
        // Mirrored round the middle of the surface, where the frame is centred.
        painter.translate(width(), 0);
        painter.scale(-1, 1);
    }
    if (look == Look::Box) {
        // The video itself takes the rounded corners: a frame laid on as the brush of the rounded
        // rect keeps its edge smooth, where a clip path would cut it jagged.
        QBrush brush(frame);
        QTransform place;
        place.translate(drawn.x(), drawn.y());
        place.scale(drawn.width() / static_cast<qreal>(frame.width()),
                    drawn.height() / static_cast<qreal>(frame.height()));
        brush.setTransform(place);
        const qreal radius = AirTokens::Radius::card;
        painter.setPen(Qt::NoPen);
        painter.setBrush(AirStyle::darkColor(Color::LockBottom));
        painter.drawRoundedRect(QRectF(rect()), radius, radius);
        // A brush repeats its image, so it fills only where the frame is.
        painter.setBrush(brush);
        painter.drawRoundedRect(QRectF(drawn.intersected(rect())), radius, radius);
    } else {
        painter.drawImage(QRectF(drawn), frame);
    }
    painter.restore();

    if (look == Look::Box) {
        QColor border = AirStyle::darkColor(Color::FixedWhite);
        border.setAlphaF(boxBorderAlpha);
        AirPaint::box(painter, rect(), AirTokens::Radius::card, QColor(), border);
    }
}

void VideoSurface::paintWithoutFrame(QPainter& painter)
{
    using AirTokens::Color;
    switch (look) {
    case Look::Plain: {
        painter.fillRect(painter.viewport(), Qt::black);
        painter.fillRect(boundingRect, Qt::white);
        QPixmap drawnAvatar = avatar;

        if (drawnAvatar.isNull()) {
            drawnAvatar = loadPlaceholderAvatar(boundingRect.size());
        } else {
            drawnAvatar = drawnAvatar.scaled(boundingRect.size(), Qt::KeepAspectRatio,
                                             Qt::SmoothTransformation);
        }

        painter.drawPixmap(centeredPixmapRect(drawnAvatar, boundingRect), drawnAvatar);
        return;
    }
    case Look::Stage: {
        // KHANDAQ (calls stage, 26.11): the call screen stays dark in both themes; the person's
        // avatar stands in the middle at the size of an audio call until their video comes.
        painter.fillRect(rect(), AirStyle::darkColor(Color::LockTop));
        const int side =
            qMin(stageAvatarSide, qMin(width(), height()) - 2 * Air::CallStage::selfInset());
        if (side <= 0) {
            return;
        }
        const QRectF tile((width() - side) / 2.0, (height() - side) / 2.0, side, side);
        const qreal radius = AirTokens::Radius::card;
        const qreal dpr = devicePixelRatioF();
        const int pixels = qRound(side * dpr);
        QPixmap shown;
        if (avatar.isNull()) {
            painter.setPen(Qt::NoPen);
            painter.setBrush(AirStyle::darkColor(Color::MediaControl));
            painter.drawRoundedRect(tile, radius, radius);
            shown = loadPlaceholderAvatar(QSize(pixels, pixels) / 2,
                                          QStringLiteral(":/img/contact.svg"));
            shown.setDevicePixelRatio(dpr);
            const QSizeF logical = QSizeF(shown.size()) / dpr;
            painter.drawPixmap(QPointF(tile.center().x() - logical.width() / 2.0,
                                       tile.center().y() - logical.height() / 2.0),
                               shown);
            return;
        }
        shown = avatar.scaled(pixels, pixels, Qt::KeepAspectRatioByExpanding,
                              Qt::SmoothTransformation);
        shown.setDevicePixelRatio(dpr);
        QPainterPath clip;
        clip.addRoundedRect(tile, radius, radius);
        painter.save();
        painter.setClipPath(clip);
        const QSizeF logical = QSizeF(shown.size()) / dpr;
        painter.drawPixmap(QPointF(tile.center().x() - logical.width() / 2.0,
                                   tile.center().y() - logical.height() / 2.0),
                           shown);
        painter.restore();
        return;
    }
    case Look::Box: {
        // KHANDAQ (calls stage, 26.11): the ground of our camera's tile until its first frame.
        const qreal radius = AirTokens::Radius::card;
        painter.setPen(Qt::NoPen);
        painter.setBrush(AirStyle::darkColor(Color::LockBottom));
        painter.drawRoundedRect(QRectF(rect()), radius, radius);
        QColor border = AirStyle::darkColor(Color::FixedWhite);
        border.setAlphaF(boxBorderAlpha);
        AirPaint::box(painter, rect(), radius, QColor(), border);
        return;
    }
    }
}

void VideoSurface::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    recalulateBounds();
    emit boundaryChanged();
}

void VideoSurface::showEvent(QShowEvent* e)
{
    std::ignore = e;
    // emit ratioChanged();
}

void VideoSurface::recalulateBounds()
{
    // KHANDAQ (calls stage, 26.11): the rect the frame takes, inside the surface; one that fills
    // keeps its aspect and is cut at the edges instead of stretched. Before the first frame the
    // shape is a square, as it was.
    const QRect area = contentsRect();
    const QSize shape = frameSize.isEmpty() ? QSize(1, 1) : frameSize;
    const Air::CallStage::Scale scale =
        fills(shape) ? Air::CallStage::Scale::Fill : Air::CallStage::Scale::Fit;
    boundingRect = Air::CallStage::videoRect(shape, area, scale).intersected(area);

    update();
}

void VideoSurface::lock()
{
    // Fast lock
    bool expected = false;
    while (!frameLock.compare_exchange_weak(expected, true))
        expected = false;
}

void VideoSurface::unlock()
{
    frameLock = false;
}
