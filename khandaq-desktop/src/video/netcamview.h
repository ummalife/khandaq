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

#include "src/core/toxpk.h"
#include "src/model/air/aircallstage.h"
#include "src/model/air/airconversation.h"
#include <QVector>
#include <QWidget>

class AirCallControl;
class AirCallHeader;
struct vpx_image;
class VideoSource;
class MovableWidget;
class VideoSurface;
class QKeyEvent;
class QCloseEvent;
class QShowEvent;
class CameraSource;
class Settings;
class Style;
class Profile;

/**
 * The video call stage. KHANDAQ (calls stage, 26.11): it follows the rules the three clients share
 * for the call screens (SPEC R1 to R12): dark in both themes, the big video full bleed and never
 * stretched, your own video in a rounded box of your camera's shape in a corner of the free part of
 * the stage, which a drag throws to another corner and a click swaps with the big one, and the
 * controls as 64 px circles with their words at the bottom. In the window of the conversation the
 * head above it says who and how long, and its call band holds the microphone, the sound and End,
 * so the row has Self view, Messages and Full screen. In full screen the stage has its own head
 * (the name, the time, the link) and the whole row.
 */
class NetCamView : public QWidget
{
    Q_OBJECT

public:
    NetCamView(ToxPk friendPk_, CameraSource& cameraSource, Settings& settings,
        Style& style, Profile& profile, QWidget* parent = nullptr);
    /**
     * KHANDAQ (calls stage, 26.11): the stage without a profile. The avatars and the size the
     * camera is set to (empty when the settings leave it to the camera) come as they are; the
     * constructor above reads them from the profile and the settings and keeps the avatars up to
     * date. Tests make the stage with this one and a camera of their own.
     */
    NetCamView(ToxPk friendPk_, VideoSource& cameraSource, const QSize& cameraSize,
               const QPixmap& selfAvatar, const QPixmap& friendAvatar, QWidget* parent = nullptr);
    ~NetCamView();

    virtual void show(VideoSource* source, const QString& title);
    virtual void hide();

    void setSource(VideoSource* s);
    void setTitle(const QString& title);
    QSize getSurfaceMinSize();
    /** KHANDAQ (calls stage, 26.11): how long the call has been on, for the head in full screen. */
    void setSeconds(qint64 seconds);
    /** KHANDAQ (calls stage, 26.11): the link to the person, for the chip under the time. */
    void setLink(Air::LinkState link);

protected:
    void showEvent(QShowEvent* event) override;
    void resizeEvent(QResizeEvent* event) override;
    void paintEvent(QPaintEvent* event) override;
    void changeEvent(QEvent* event) override;
    VideoSurface* videoSurface;

signals:
    void showMessageClicked();
    void videoCallEnd();
    void volMuteToggle();
    void micMuteToggle();

public slots:
    void setShowMessages(bool show, bool notify = false);
    void updateMuteVolButton(bool isMuted);
    void updateMuteMicButton(bool isMuted);

private:
    void toggleFullScreen();
    void enterFullScreen();
    void exitFullScreen();
    void endVideoCall();
    void toggleVideoPreview();
    void keyPressEvent(QKeyEvent *event) override;
    void closeEvent(QCloseEvent *event) override;
    /** The videos where the swap puts them: the big one on the stage, the small one in the box. */
    void placeVideos();
    void swapVideos();
    /** Lays the stage out: the big video, the head, the row and the box in its corner. */
    void layoutStage();
    /** The free part of the stage the box stands in, for the layout as it is now. */
    QRect selfAreaNow() const;
    /** The shape of the box: our camera's frames, else the size the camera is set to. */
    QSize selfFrameSize() const;
    void onSelfDropped(const QPointF& velocity);
    QVector<AirCallControl*> rowNow() const;

    VideoSurface* selfVideoSurface;
    MovableWidget* selfFrame;
    AirCallHeader* header = nullptr;
    AirCallControl* microphoneButton = nullptr;
    AirCallControl* soundButton = nullptr;
    AirCallControl* selfViewButton = nullptr;
    AirCallControl* messagesButton = nullptr;
    AirCallControl* fullScreenButton = nullptr;
    AirCallControl* endButton = nullptr;
    ToxPk friendPk;
    QVector<QMetaObject::Connection> connections;
    VideoSource& cameraSource;
    QSize cameraSize;
    Air::CallStage::Views views;
    Qt::Corner selfCorner = Qt::BottomRightCorner;
    bool fullScreen = false;
    bool selfShown = true;
};
