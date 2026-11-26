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

#pragma once

#include "src/video/videosource.h"
#include <QWidget>
#include <atomic>
#include <memory>

class QImage;
class QPainter;

class VideoSurface : public QWidget
{
    Q_OBJECT

public:
    /**
     * KHANDAQ (calls stage, 26.11): how a frame takes the surface; it is never stretched. Fit shows
     * it whole with bars round it, Fill covers the surface and cuts the edges off, Stage is the big
     * video of a call: Fill while the frame and the surface are both portrait or both landscape,
     * Fit otherwise (Air::CallStage::stageScale).
     */
    enum class Scaling
    {
        Fit,
        Fill,
        Stage,
    };

    /**
     * KHANDAQ (calls stage, 26.11): what the surface looks like. Plain is the preview of the
     * settings: black, and the avatar on white until a frame comes. Stage is the big video of a
     * call, dark in both themes: black bars, and the avatar at the size of an audio call on the
     * lock ground until a frame comes. Box is the small video of a call: rounded, with a hairline
     * border, on the lock-bottom ground until a frame comes.
     */
    enum class Look
    {
        Plain,
        Stage,
        Box,
    };

    /** expanding_ makes the surface Fill instead of Fit. */
    VideoSurface(const QPixmap& avatar_, QWidget* parent = nullptr, bool expanding_ = false);
    VideoSurface(const QPixmap& avatar_, VideoSource* source_, QWidget* parent = nullptr);
    ~VideoSurface();

    bool isExpanding() const;
    void setSource(VideoSource* src);
    QRect getBoundingRect() const;
    float getRatio() const;
    void setAvatar(const QPixmap& pixmap);
    QPixmap getAvatar() const;

    void setScaling(Scaling scaling_);
    Scaling getScaling() const;
    void setLook(Look look_);
    Look getLook() const;
    /** Shows the frames mirrored, as a mirror shows you; what is sent stays as it is. */
    void setMirrored(bool mirrored_);
    bool isMirrored() const;
    /** The size of the frames, or an empty size before the first one. */
    QSize getFrameSize() const;
    /** Whether a frame is on the surface, or the avatar while none is. */
    bool hasFrame();

signals:
    void ratioChanged();
    void boundaryChanged();

protected:
    void subscribe();
    void unsubscribe();

    void paintEvent(QPaintEvent* event) final;
    void resizeEvent(QResizeEvent* event) final;
    void showEvent(QShowEvent* event) final;

private slots:
    void onNewFrameAvailable(const std::shared_ptr<VideoFrame>& newFrame);
    void onSourceStopped();

private:
    void recalulateBounds();
    void lock();
    void unlock();
    /** How a frame of size frame takes the surface now. */
    bool fills(const QSize& frame) const;
    void paintFrame(QPainter& painter, const QImage& frame, const QRect& drawn);
    void paintWithoutFrame(QPainter& painter);

    QRect boundingRect;
    VideoSource* source;
    std::shared_ptr<VideoFrame> lastFrame;
    std::atomic_bool frameLock;
    uint8_t hasSubscribed;
    QPixmap avatar;
    float ratio;
    Scaling scaling;
    Look look = Look::Plain;
    bool mirrored = false;
    QSize frameSize;
};
