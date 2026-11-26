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

#include "netcamview.h"
#include "camerasource.h"
#include "src/persistence/profile.h"
#include "src/persistence/settings.h"
#include "src/video/videosurface.h"
#include "src/widget/air/aircallparts.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/tool/movablewidget.h"
#include "src/widget/style.h"

#include <QCloseEvent>
#include <QPainter>

#include <tuple>

namespace
{
/** The stage in the window is at least as tall as the old video surface was. */
constexpr int minimumStageHeight = 256;
/** In full screen the head stands this far from the top of the screen, as the row from its bottom. */
constexpr int headerTop = AirTokens::Space::s24;

/** The same corner on the other side, for the other reading direction. */
Qt::Corner mirroredCorner(Qt::Corner corner)
{
    switch (corner) {
    case Qt::TopLeftCorner:
        return Qt::TopRightCorner;
    case Qt::TopRightCorner:
        return Qt::TopLeftCorner;
    case Qt::BottomLeftCorner:
        return Qt::BottomRightCorner;
    case Qt::BottomRightCorner:
        break;
    }
    return Qt::BottomLeftCorner;
}
} // namespace

NetCamView::NetCamView(ToxPk friendPk_, CameraSource& cameraSource_,
    Settings& settings, Style& style, Profile& profile, QWidget* parent)
    : NetCamView(friendPk_, cameraSource_, settings.getCamVideoRes().size(), profile.loadAvatar(),
                 profile.loadAvatar(friendPk_), parent)
{
    // KHANDAQ (calls stage, 26.11): the stage paints with the tokens, dark in both themes, so the
    // style sheets of the theme are no longer read here.
    std::ignore = style;

    connections += connect(&profile, &Profile::selfAvatarChanged,
                           [this](const QPixmap& pixmap) { selfVideoSurface->setAvatar(pixmap); });

    connections += connect(&profile, &Profile::friendAvatarChanged,
                           [this](ToxPk friendPkArg, const QPixmap& pixmap) {
                               if (friendPk == friendPkArg)
                                   videoSurface->setAvatar(pixmap);
                           });
}

NetCamView::NetCamView(ToxPk friendPk_, VideoSource& cameraSource_, const QSize& cameraSize_,
                       const QPixmap& selfAvatar, const QPixmap& friendAvatar, QWidget* parent)
    : QWidget(parent)
    , selfFrame{nullptr}
    , friendPk{friendPk_}
    , cameraSource{cameraSource_}
    , cameraSize{cameraSize_}
{
    //: Title of the window of a video call
    setWindowTitle(tr("Video call"));
    setMinimumHeight(minimumStageHeight);
    selfCorner = Air::CallStage::defaultCorner(layoutDirection());

    videoSurface = new VideoSurface(friendAvatar, this);
    videoSurface->setObjectName(QStringLiteral("FriendVideoSurface"));

    selfVideoSurface = new VideoSurface(selfAvatar, this, true);
    selfVideoSurface->setObjectName(QStringLiteral("CamVideoSurface"));
    // KHANDAQ (calls stage, 26.11): your camera shows as a mirror shows you, wherever it stands;
    // what is sent is not mirrored.
    selfVideoSurface->setMirrored(true);

    selfFrame = new MovableWidget(this);
    selfFrame->setObjectName(QStringLiteral("SelfFrame"));

    header = new AirCallHeader(this);
    header->setObjectName(QStringLiteral("CallStageHeader"));
    header->hide();

    const auto makeControl = [this](AirCallControl::Kind kind, const char* name) {
        auto* const control = new AirCallControl(kind, this);
        control->setObjectName(QLatin1String(name));
        return control;
    };
    microphoneButton = makeControl(AirCallControl::Kind::Microphone, "callStageMicrophone");
    soundButton = makeControl(AirCallControl::Kind::Sound, "callStageSound");
    selfViewButton = makeControl(AirCallControl::Kind::SelfView, "callStageSelfView");
    messagesButton = makeControl(AirCallControl::Kind::Messages, "callStageMessages");
    fullScreenButton = makeControl(AirCallControl::Kind::FullScreen, "callStageFullScreen");
    endButton = makeControl(AirCallControl::Kind::End, "callStageEnd");

    connect(microphoneButton, &AirCallControl::clicked, this, &NetCamView::micMuteToggle);
    connect(soundButton, &AirCallControl::clicked, this, &NetCamView::volMuteToggle);
    connect(selfViewButton, &AirCallControl::clicked, this, &NetCamView::toggleVideoPreview);
    connect(messagesButton, &AirCallControl::clicked, this, &NetCamView::showMessageClicked);
    connect(fullScreenButton, &AirCallControl::clicked, this, &NetCamView::toggleFullScreen);
    connect(endButton, &AirCallControl::clicked, this, &NetCamView::endVideoCall);

    connect(selfFrame, &MovableWidget::clicked, this, &NetCamView::swapVideos);
    connect(selfFrame, &MovableWidget::dropped, this, &NetCamView::onSelfDropped);
    // The box takes the shape of our camera's frames as they come.
    connections +=
        connect(selfVideoSurface, &VideoSurface::ratioChanged, this, &NetCamView::layoutStage);

    setShowMessages(false);
    placeVideos();
    layoutStage();
}

NetCamView::~NetCamView()
{
    for (QMetaObject::Connection conn : connections)
        disconnect(conn);
}

void NetCamView::show(VideoSource* source, const QString& title)
{
    setSource(source);
    selfVideoSurface->setSource(&cameraSource);

    setTitle(title);
    QWidget::show();
}

void NetCamView::hide()
{
    setSource(nullptr);
    selfVideoSurface->setSource(nullptr);

    // KHANDAQ (calls stage, 26.11): the box is no longer deleted here: after a swap it holds the
    // person's video. It goes with the view.
    QWidget::hide();
}

void NetCamView::setSource(VideoSource* s)
{
    videoSurface->setSource(s);
}

void NetCamView::setTitle(const QString& title)
{
    setWindowTitle(title);
    header->setName(title);
}

void NetCamView::setSeconds(qint64 seconds)
{
    header->setSeconds(seconds);
}

void NetCamView::setLink(Air::LinkState link)
{
    header->setLink(link);
}

void NetCamView::showEvent(QShowEvent* event)
{
    std::ignore = event;
    layoutStage();
}

void NetCamView::resizeEvent(QResizeEvent* event)
{
    QWidget::resizeEvent(event);
    layoutStage();
}

void NetCamView::paintEvent(QPaintEvent* event)
{
    std::ignore = event;
    // The flat lock ground of the call screens, under the big video.
    QPainter painter(this);
    painter.fillRect(rect(), AirStyle::darkColor(AirTokens::Color::LockTop));
}

void NetCamView::changeEvent(QEvent* event)
{
    QWidget::changeEvent(event);
    if (event->type() == QEvent::LayoutDirectionChange) {
        // The box keeps its side of the reading: the trailing corner stays the trailing one.
        selfCorner = mirroredCorner(selfCorner);
        layoutStage();
    }
}

QSize NetCamView::getSurfaceMinSize()
{
    // KHANDAQ (calls stage, 26.11): the controls stand on the video now, so the stage needs its
    // own height and no more; it stands above the conversation, so it adds no width.
    return QSize(0, minimumHeight());
}

void NetCamView::setShowMessages(bool show, bool notify)
{
    // show: the messages are hidden, and the switch offers to show them; notify puts the dot of
    // something new on it, as the old icon did.
    messagesButton->setEngaged(show);
    messagesButton->setNotice(show && notify);
}

void NetCamView::placeVideos()
{
    VideoSurface* const big = views.isSwapped() ? selfVideoSurface : videoSurface;
    VideoSurface* const small = views.isSwapped() ? videoSurface : selfVideoSurface;
    if (big->parentWidget() != this) {
        big->setParent(this);
    }
    if (small->parentWidget() != selfFrame) {
        small->setParent(selfFrame);
    }
    // The big video takes the stage by its rule (SPEC R1); the small one fills its box (R2, R6).
    big->setLook(VideoSurface::Look::Stage);
    big->setScaling(VideoSurface::Scaling::Stage);
    small->setLook(VideoSurface::Look::Box);
    small->setScaling(VideoSurface::Scaling::Fill);
    big->lower();
    big->show();
    small->show();
}

void NetCamView::swapVideos()
{
    views.swap();
    placeVideos();
    layoutStage();
}

QVector<AirCallControl*> NetCamView::rowNow() const
{
    if (fullScreen) {
        return {microphoneButton, soundButton, selfViewButton, fullScreenButton, endButton};
    }
    // In the window the call band of the conversation's head has the microphone, the sound and End.
    return {selfViewButton, messagesButton, fullScreenButton};
}

QSize NetCamView::selfFrameSize() const
{
    const QSize frame = selfVideoSurface->getFrameSize();
    return frame.isEmpty() ? cameraSize : frame;
}

QRect NetCamView::selfAreaNow() const
{
    const QRect stage = rect();
    const int headerBottom = fullScreen ? headerTop + AirCallHeader::contentHeight() : -1;
    const QRect row = Air::CallStage::rowRect(stage, rowNow().size(), AirCallControl::controlHeight());
    return Air::CallStage::selfArea(stage, headerBottom, row.top());
}

void NetCamView::layoutStage()
{
    // Nothing to lay out before the constructor has made the last control.
    if (!endButton) {
        return;
    }
    const QRect stage = rect();
    VideoSurface* const big = views.isSwapped() ? selfVideoSurface : videoSurface;
    big->setGeometry(stage);

    const QRect area = selfAreaNow();
    const QSize size = Air::CallStage::fitInto(
        Air::CallStage::selfSize(stage.size(), selfFrameSize()), area.size());
    selfFrame->setVisible(selfShown && !size.isEmpty());
    selfFrame->resize(size);
    VideoSurface* const small = views.isSwapped() ? videoSurface : selfVideoSurface;
    small->setGeometry(QRect(QPoint(), size));
    selfFrame->resetBoundary(area);
    selfFrame->slideTo(Air::CallStage::cornerRect(area, size, selfCorner).topLeft(), 0);
    selfFrame->raise();

    header->setVisible(fullScreen);
    if (fullScreen) {
        const int reach = AirShadowedText::reach();
        header->setGeometry(0, headerTop - reach, stage.width(),
                            AirCallHeader::contentHeight() + 2 * reach);
        header->raise();
    }

    const QVector<AirCallControl*> row = rowNow();
    for (AirCallControl* const control : {microphoneButton, soundButton, selfViewButton,
                                          messagesButton, fullScreenButton, endButton}) {
        control->setVisible(row.contains(control));
    }
    const int height = AirCallControl::controlHeight();
    const QRect rowArea = Air::CallStage::rowRect(stage, row.size(), height);
    const int column = row.isEmpty() ? 0 : rowArea.width() / row.size();
    const bool rightToLeft = layoutDirection() == Qt::RightToLeft;
    for (int i = 0; i < row.size(); ++i) {
        // The row reads from the side the language starts, End last.
        const int slot = rightToLeft ? row.size() - 1 - i : i;
        // Each control has the room of its word's shadow under the row.
        row[i]->setGeometry(rowArea.x() + slot * column, rowArea.y(), column,
                            height + AirShadowedText::reach());
        row[i]->raise();
    }
}

void NetCamView::onSelfDropped(const QPointF& velocity)
{
    // SPEC R6: the box goes to the corner nearest to where the throw would take it, sliding with
    // the layout motion, or at once when the system asks for less motion.
    const QRect area = selfAreaNow();
    selfCorner = Air::CallStage::snapCorner(area, selfFrame->size(), selfFrame->pos(), velocity);
    const QPoint target = Air::CallStage::cornerRect(area, selfFrame->size(), selfCorner).topLeft();
    const AirStyle& style = AirStyle::instance();
    selfFrame->slideTo(target, style.reducedMotion() ? 0 : style.duration(AirTokens::Motion::layout),
                       style.easeOut());
}

void NetCamView::toggleFullScreen()
{
    if (fullScreen) {
        exitFullScreen();
    } else {
        enterFullScreen();
    }
}

void NetCamView::enterFullScreen()
{
    fullScreen = true;
    fullScreenButton->setEngaged(true);
    setWindowFlags(Qt::Window | Qt::CustomizeWindowHint | Qt::FramelessWindowHint);
    showFullScreen();
    layoutStage();
    activateWindow();
    // Esc reaches the stage through the control in focus.
    fullScreenButton->setFocus(Qt::OtherFocusReason);
}

void NetCamView::exitFullScreen()
{
    if (!fullScreen) {
        return;
    }
    fullScreen = false;
    fullScreenButton->setEngaged(false);
    setWindowFlags(Qt::Widget);
    showNormal();
    layoutStage();
}

void NetCamView::endVideoCall()
{
    // KHANDAQ (calls stage, 26.11): End leaves the full screen and ends the call. It used to toggle
    // the full screen, which from the window would have taken it.
    exitFullScreen();
    emit videoCallEnd();
}

void NetCamView::toggleVideoPreview()
{
    selfShown = !selfShown;
    // Hiding your video while it is the big one brings theirs back to the stage first.
    if (!selfShown && views.isSwapped()) {
        views.swap();
        placeVideos();
    }
    selfViewButton->setEngaged(!selfShown);
    layoutStage();
}

void NetCamView::updateMuteVolButton(bool isMuted)
{
    soundButton->setEngaged(isMuted);
}

void NetCamView::updateMuteMicButton(bool isMuted)
{
    microphoneButton->setEngaged(isMuted);
}

void NetCamView::keyPressEvent(QKeyEvent *event)
{
    int key = event->key();
    if (key == Qt::Key_Escape && fullScreen) {
        exitFullScreen();
    }
}

void NetCamView::closeEvent(QCloseEvent *event)
{
    exitFullScreen();
    event->ignore();
}
