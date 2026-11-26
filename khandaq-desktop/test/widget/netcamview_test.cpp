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

#include "src/video/netcamview.h"

#include "src/model/air/aircallstage.h"
#include "src/persistence/paths.h"
#include "src/persistence/settings.h"
#include "src/video/videoframe.h"
#include "src/video/videosource.h"
#include "src/video/videosurface.h"
#include "src/widget/air/aircallparts.h"
#include "src/widget/air/airchatparts.h"
#include "src/widget/air/airstyle.h"
#include "src/widget/air/airtokens.h"
#include "src/widget/style.h"
#include "src/widget/tool/imessageboxmanager.h"
#include "src/widget/tool/movablewidget.h"
#include "src/widget/translator.h"

extern "C" {
#include <libavutil/frame.h>
#include <libavutil/imgutils.h>
}

#include <QAccessible>
#include <QApplication>
#include <QDir>
#include <QFile>
#include <QPainter>
#include <QScreen>
#include <QSignalSpy>
#include <QStandardPaths>
#include <QTemporaryDir>
#include <QTranslator>
#include <QtTest/QtTest>

#include <cstring>
#include <memory>

using AirTokens::Color;

namespace {

const QSize landscape(1280, 720);
const QSize portrait(720, 1280);
/** The word for the shape of a frame, in the names of rows and files. */
QLatin1String shapeWord(const QSize& frame)
{
    return frame == landscape ? QLatin1String("landscape") : QLatin1String("portrait");
}
const QColor callTone(0x1f, 0x5c, 0x66);
const QColor selfTone(0x5a, 0x34, 0x70);
/** The edges of a test card: a mirror shows the yellow one on the right. */
const QColor leftEdge(0xff, 0xc8, 0x3c);
const QColor rightEdge(0x3c, 0x8c, 0xff);

/** A camera or a call of the test: it sends the test cards it is given, nothing else. */
class CardSource : public VideoSource
{
public:
    void subscribe() override
    {
        ++subscribers;
    }

    void unsubscribe() override
    {
        --subscribers;
    }

    /** Sends card as one frame in RGB24, which the surfaces convert as they do a call's frames. */
    void push(const QImage& card)
    {
        const QImage rgb = card.convertToFormat(QImage::Format_RGB888);
        AVFrame* frame = av_frame_alloc();
        QVERIFY(frame != nullptr);
        frame->width = rgb.width();
        frame->height = rgb.height();
        frame->format = AV_PIX_FMT_RGB24;
        if (av_image_alloc(frame->data, frame->linesize, rgb.width(), rgb.height(),
                           AV_PIX_FMT_RGB24, VideoFrame::dataAlignment)
            < 0) {
            av_frame_free(&frame);
            QFAIL("No room for a frame");
        }
        for (int y = 0; y < rgb.height(); ++y) {
            std::memcpy(frame->data[0] + y * frame->linesize[0], rgb.constScanLine(y),
                        static_cast<size_t>(rgb.width()) * 3);
        }
        emit frameAvailable(std::make_shared<VideoFrame>(id, frame, true));
    }

    int subscribers = 0;
};

/**
 * A test card: a grid of squares and a circle, which a stretched frame would make oblong, the
 * edges in two colours, which show a mirror, and its name and size.
 */
QImage testCard(const QSize& size, const QColor& tone, const QString& name)
{
    QImage card(size, QImage::Format_RGB888);
    card.fill(tone);
    QPainter painter(&card);
    painter.setRenderHint(QPainter::Antialiasing);
    const int shortSide = qMin(size.width(), size.height());
    const int cell = shortSide / 6;
    painter.setPen(QPen(QColor(255, 255, 255, 70), 2));
    for (int x = cell; x < size.width(); x += cell) {
        painter.drawLine(x, 0, x, size.height());
    }
    for (int y = cell; y < size.height(); y += cell) {
        painter.drawLine(0, y, size.width(), y);
    }
    const qreal diameter = shortSide * 0.75;
    painter.setPen(QPen(Qt::white, shortSide / 90.0));
    painter.drawEllipse(QRectF((size.width() - diameter) / 2, (size.height() - diameter) / 2,
                               diameter, diameter));
    const int edge = cell / 2;
    painter.fillRect(0, 0, edge, size.height(), leftEdge);
    painter.fillRect(size.width() - edge, 0, edge, size.height(), rightEdge);
    QFont font = AirStyle::instance().font(AirTokens::TextRole::Display);
    font.setPixelSize(cell / 2);
    painter.setFont(font);
    painter.setPen(Qt::white);
    painter.drawText(card.rect(), Qt::AlignCenter,
                     QStringLiteral("%1\n%2 x %3").arg(name).arg(size.width()).arg(size.height()));
    painter.drawText(QRect(edge + cell / 4, cell / 4, cell * 2, cell), Qt::AlignLeft | Qt::AlignTop,
                     QStringLiteral("L"));
    return card;
}

bool near(const QColor& pixel, const QColor& wanted)
{
    const int tolerance = 28;
    return qAbs(pixel.red() - wanted.red()) <= tolerance
           && qAbs(pixel.green() - wanted.green()) <= tolerance
           && qAbs(pixel.blue() - wanted.blue()) <= tolerance;
}

QString describe(const QColor& color)
{
    return color.name(QColor::HexRgb);
}

/** A stage with a camera and a call of its own: no camera, microphone or network is touched. */
struct TestStage
{
    explicit TestStage(const QSize& cameraSize = QSize())
        : view(ToxPk(), camera, cameraSize, QPixmap(), QPixmap())
    {
    }

    template <typename T>
    T* part(const char* name)
    {
        return view.findChild<T*>(QLatin1String(name));
    }

    VideoSurface* friendSurface()
    {
        return part<VideoSurface>("FriendVideoSurface");
    }

    VideoSurface* selfSurface()
    {
        return part<VideoSurface>("CamVideoSurface");
    }

    MovableWidget* box()
    {
        return part<MovableWidget>("SelfFrame");
    }

    AirCallHeader* header()
    {
        return part<AirCallHeader>("CallStageHeader");
    }

    AirCallControl* control(const char* name)
    {
        return part<AirCallControl>(name);
    }

    /** The controls shown, from the left. */
    QVector<AirCallControl*> row()
    {
        QVector<AirCallControl*> shown;
        for (AirCallControl* const each : view.findChildren<AirCallControl*>()) {
            if (each->isVisibleTo(&view)) {
                shown.append(each);
            }
        }
        std::sort(shown.begin(), shown.end(), [](const AirCallControl* a, const AirCallControl* b) {
            return a->x() < b->x();
        });
        return shown;
    }

    CardSource camera;
    CardSource call;
    NetCamView view;
};

/** Shows stage at size with frames from both sources: ours in landscape, theirs in callSize. */
void startStage(TestStage& stage, const QSize& size, const QSize& callSize, bool frames = true)
{
    stage.view.resize(size);
    stage.view.show(&stage.call, QStringLiteral("Amina Rahman"));
    QVERIFY(QTest::qWaitForWindowExposed(&stage.view));
    QTRY_COMPARE(stage.view.size(), size);
    QCOMPARE(stage.camera.subscribers, 1);
    QCOMPARE(stage.call.subscribers, 1);
    if (frames) {
        stage.camera.push(testCard(landscape, selfTone, QStringLiteral("SELF")));
        stage.call.push(testCard(callSize, callTone, QStringLiteral("REMOTE")));
    }
    QCoreApplication::processEvents();
}

/**
 * Sends a mouse event to target as the window would, at point of the stage, with the time stamp
 * time; the box moves under the mouse while it is dragged, so the points are the stage's.
 */
void mouseAt(TestStage& stage, QWidget* target, QEvent::Type type, const QPoint& point,
             Qt::MouseButtons buttons, ulong time)
{
    const QPoint global = stage.view.mapToGlobal(point);
    const Qt::MouseButton button = type == QEvent::MouseMove ? Qt::NoButton : Qt::LeftButton;
    QMouseEvent event(type, target->mapFromGlobal(global), global, button, buttons, Qt::NoModifier);
    event.setTimestamp(time);
    QApplication::sendEvent(target, &event);
}

/** A press and a release on the middle of the box, on the video in it. */
void clickBox(TestStage& stage)
{
    MovableWidget* const box = stage.box();
    QWidget* const video = box->childAt(box->width() / 2, box->height() / 2);
    QVERIFY(video != nullptr);
    const QPoint middle = box->geometry().center();
    mouseAt(stage, video, QEvent::MouseButtonPress, middle, Qt::LeftButton, 1000);
    mouseAt(stage, video, QEvent::MouseButtonRelease, middle, Qt::NoButton, 1080);
    QCoreApplication::processEvents();
}

/**
 * Takes the stage to full screen with its own control. The offscreen platform keeps a window in
 * full screen as big as its one screen, 800 by 600: for another size the window is let go and
 * given it, and the stage keeps its full screen layout.
 */
void fullScreen(TestStage& stage, const QSize& size = QSize())
{
    stage.control("callStageFullScreen")->click();
    QTRY_VERIFY(stage.view.isFullScreen());
    // The platform tells the window its new size later: wait for it before anything else.
    const QSize screen = QGuiApplication::primaryScreen()->geometry().size();
    QTRY_COMPARE(stage.view.size(), screen);
    if (size.isValid() && size != screen) {
        stage.view.setWindowState(Qt::WindowNoState);
        QTest::qWait(20);
        stage.view.resize(size);
        QTest::qWait(20);
        QTRY_COMPARE(stage.view.size(), size);
    }
}

/** Shows no dialog: a Settings needs someone to report to, and the test has nobody to ask. */
class QuietMessages : public IMessageBoxManager
{
public:
    void showInfo(const QString&, const QString&) override {}
    void showWarning(const QString&, const QString&) override {}
    void showError(const QString&, const QString&) override {}
    bool askQuestion(const QString&, const QString&, bool defaultAns, bool, bool) override
    {
        return defaultAns;
    }
    bool askQuestion(const QString&, const QString&, const QString&, const QString&,
                     bool defaultAns, bool) override
    {
        return defaultAns;
    }
    void confirmExecutableOpen(const QFileInfo&) override {}
};

} // namespace

class TestNetCamView : public QObject
{
    Q_OBJECT
public:
    explicit TestNetCamView(const QString& home_)
        : home{QDir::cleanPath(home_)}
    {
    }

private slots:
    void initTestCase();
    void remoteVideoFillsTheStage();
    void portraitVideoFitsWithBars();
    void scaleFollowsResizesAndFrames();
    void boxTakesTheCameraShape();
    void ownVideoIsMirrored();
    void clickSwapsTheVideos();
    void dragSnapsToTheNearestCorner();
    void throwCarriesTheBox();
    void windowRowHasTheStageControls();
    void rowFollowsTheReadingDirection();
    void fullScreenHasItsHeadAndTheWholeRow();
    void endLeavesFullScreenAndEnds();
    void controlsSayOnAndOff();
    void selfViewHidesTheBox();
    void messagesTellWhatCame();
    void nothingOverlaps_data();
    void nothingOverlaps();
    void noDebugOverlays();
    void settingsPreviewKeepsItsLook();
    void stageIsDarkInBothThemes();
    void screenshots();
    void cleanupTestCase();

private:
    void useTheme(bool dark);

    const QString home;
    QuietMessages messages;
    std::unique_ptr<Settings> settings;
    Style style;
};

void TestNetCamView::initTestCase()
{
    // A Settings reads and writes the settings file of the Khandaq of this computer: it is made
    // only when that file lies in the home of the test.
    const QString folder = QDir::cleanPath(Paths().getSettingsDirPath());
    if (!home.isEmpty() && folder.startsWith(home + QLatin1Char('/'))) {
        settings.reset(new Settings(messages));
    } else {
        qWarning() << "The settings would not stay in the test's home:" << folder;
    }
}

void TestNetCamView::cleanupTestCase()
{
    settings.reset();
}

void TestNetCamView::useTheme(bool dark)
{
    settings->setThemeMode(dark ? Settings::ThemeMode::Dark : Settings::ThemeMode::Light);
    AirStyle::instance().follow(*settings, style, nullptr);
    QCOMPARE(AirStyle::instance().isDark(), dark);
}

void TestNetCamView::remoteVideoFillsTheStage()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    VideoSurface* const surface = stage.friendSurface();
    QCOMPARE(surface->parentWidget(), &stage.view);
    QCOMPARE(surface->geometry(), QRect(0, 0, 1024, 640));
    QCOMPARE(surface->getScaling(), VideoSurface::Scaling::Stage);
    QCOMPARE(surface->getLook(), VideoSurface::Look::Stage);
    QVERIFY(!surface->isMirrored());
    QCOMPARE(surface->getFrameSize(), landscape);
    QCOMPARE(surface->getBoundingRect(), QRect(0, 0, 1024, 640));

    // No bars: the edges of the stage show the call, cut at the sides, not stretched.
    const QImage picture = stage.view.grab().toImage();
    const QPoint edges[] = {QPoint(2, 320), QPoint(1021, 320), QPoint(512, 2), QPoint(512, 637)};
    for (const QPoint& point : edges) {
        QVERIFY2(!near(picture.pixelColor(point), AirStyle::darkColor(Color::FixedBlack)),
                 qPrintable(describe(picture.pixelColor(point))));
    }
    // 10 px in is 74 px into the frame, past its yellow edge, between the lines of its grid.
    QVERIFY2(near(picture.pixelColor(10, 300), callTone),
             qPrintable(describe(picture.pixelColor(10, 300))));
}

void TestNetCamView::portraitVideoFitsWithBars()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), portrait);
    // 640 * 720 / 1280 = 360 wide, in the middle.
    QCOMPARE(stage.friendSurface()->getBoundingRect(), QRect(332, 0, 360, 640));
    const QImage picture = stage.view.grab().toImage();
    QCOMPARE(picture.pixelColor(10, 320), AirStyle::darkColor(Color::FixedBlack));
    // The left edge of the frame is yellow; (432, 30) is (200, 60) in it, on its ground.
    QVERIFY(near(picture.pixelColor(334, 320), leftEdge));
    QVERIFY2(near(picture.pixelColor(432, 30), callTone),
             qPrintable(describe(picture.pixelColor(432, 30))));
}

void TestNetCamView::scaleFollowsResizesAndFrames()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), portrait);
    QCOMPARE(stage.friendSurface()->getBoundingRect(), QRect(332, 0, 360, 640));
    // A portrait window: the portrait call fills it.
    stage.view.resize(480, 800);
    QTRY_COMPARE(stage.view.size(), QSize(480, 800));
    QCOMPARE(stage.friendSurface()->getBoundingRect(), QRect(0, 0, 480, 800));
    // The call turns to landscape: it fits, 480 * 720 / 1280 = 270 high.
    stage.call.push(testCard(landscape, callTone, QStringLiteral("REMOTE")));
    QCOMPARE(stage.friendSurface()->getBoundingRect(), QRect(0, 265, 480, 270));
}

void TestNetCamView::boxTakesTheCameraShape()
{
    {
        // Before the camera says, the box is 16:9: 22 % of 640 is 141, 141 * 16 / 9 is 251.
        TestStage stage;
        startStage(stage, QSize(1024, 640), landscape, false);
        QCOMPARE(stage.box()->geometry(), QRect(757, 369, 251, 141));
        VideoSurface* const self = stage.selfSurface();
        QCOMPARE(self->parentWidget(), stage.box());
        QCOMPARE(self->geometry(), QRect(0, 0, 251, 141));
        QCOMPARE(self->getLook(), VideoSurface::Look::Box);
        QCOMPARE(self->getScaling(), VideoSurface::Scaling::Fill);
        QVERIFY(self->isMirrored());
        QVERIFY(!self->hasFrame());

        // A portrait camera turns the box: 16 from the right, 16 over the row at 526.
        stage.camera.push(testCard(portrait, selfTone, QStringLiteral("SELF")));
        QCOMPARE(stage.box()->geometry(), QRect(867, 259, 141, 251));
        QCOMPARE(self->geometry(), QRect(0, 0, 141, 251));
        QVERIFY(self->hasFrame());
    }
    {
        // The size the camera is set to gives the shape until the first frame.
        TestStage stage(QSize(640, 480));
        startStage(stage, QSize(1024, 640), landscape, false);
        QCOMPARE(stage.box()->size(), QSize(188, 141));
    }
}

void TestNetCamView::ownVideoIsMirrored()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    const QRect box = stage.box()->geometry();
    QCOMPARE(box, QRect(757, 369, 251, 141));
    const QImage picture = stage.view.grab().toImage();
    const int middle = box.y() + box.height() / 2;
    const QColor right = picture.pixelColor(box.x() + box.width() - 5, middle);
    const QColor left = picture.pixelColor(box.x() + 5, middle);
    QVERIFY2(near(right, leftEdge), qPrintable(describe(right)));
    QVERIFY2(near(left, rightEdge), qPrintable(describe(left)));
    // The corner of the box is rounded off: the call shows there.
    const QColor corner = picture.pixelColor(box.x(), box.y());
    QVERIFY2(near(corner, callTone), qPrintable(describe(corner)));
    // The hairline: fixed-white at 20 % over our video, a little lighter than the video under it.
    const QColor border = picture.pixelColor(box.x() + box.width() / 2, box.y());
    const QColor inside = picture.pixelColor(box.x() + box.width() / 2, box.y() + 3);
    QVERIFY2(border.lightness() > inside.lightness(),
             qPrintable(describe(border) + QLatin1Char(' ') + describe(inside)));
}

void TestNetCamView::clickSwapsTheVideos()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), portrait);
    const QRect box = stage.box()->geometry();
    clickBox(stage);
    VideoSurface* const self = stage.selfSurface();
    VideoSurface* const other = stage.friendSurface();
    QCOMPARE(self->parentWidget(), &stage.view);
    QCOMPARE(self->geometry(), QRect(0, 0, 1024, 640));
    QCOMPARE(self->getLook(), VideoSurface::Look::Stage);
    QCOMPARE(self->getScaling(), VideoSurface::Scaling::Stage);
    QVERIFY(self->isMirrored());
    QVERIFY(self->isVisibleTo(&stage.view));
    QCOMPARE(other->parentWidget(), stage.box());
    QCOMPARE(other->geometry(), QRect(QPoint(), box.size()));
    QCOMPARE(other->getLook(), VideoSurface::Look::Box);
    // Swapped, the person's portrait video fills our landscape box.
    QCOMPARE(other->getScaling(), VideoSurface::Scaling::Fill);
    QVERIFY(other->isVisibleTo(&stage.view));
    // The box keeps its place and its shape.
    QCOMPARE(stage.box()->geometry(), box);

    clickBox(stage);
    QCOMPARE(other->parentWidget(), &stage.view);
    QCOMPARE(other->geometry(), QRect(0, 0, 1024, 640));
    QCOMPARE(self->parentWidget(), stage.box());
    QCOMPARE(stage.friendSurface()->getBoundingRect(), QRect(332, 0, 360, 640));
}

void TestNetCamView::dragSnapsToTheNearestCorner()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    MovableWidget* const box = stage.box();
    QWidget* const video = stage.selfSurface();
    QSignalSpy drops(box, &MovableWidget::dropped);
    QSignalSpy clicks(box, &MovableWidget::clicked);
    const QPoint start = box->geometry().center();
    mouseAt(stage, video, QEvent::MouseButtonPress, start, Qt::LeftButton, 1000);
    // To the top left in steps, then held still before the release: no throw.
    for (int step = 1; step <= 10; ++step) {
        const QPoint to = start + (QPoint(300, 150) - start) * step / 10;
        mouseAt(stage, video, QEvent::MouseMove, to, Qt::LeftButton, 1000 + 20 * step);
    }
    QCOMPARE(box->geometry().center(), QPoint(300, 150));
    mouseAt(stage, video, QEvent::MouseButtonRelease, QPoint(300, 150), Qt::NoButton, 1400);
    QCOMPARE(drops.count(), 1);
    QCOMPARE(drops.first().first().toPointF(), QPointF());
    QCOMPARE(clicks.count(), 0);
    QTRY_COMPARE(box->pos(), QPoint(16, 16));
    // The corner holds through a resize: 16 from the top and the left.
    stage.view.resize(1440, 900);
    QTRY_COMPARE(stage.view.size(), QSize(1440, 900));
    QCOMPARE(box->pos(), QPoint(16, 16));
}

void TestNetCamView::throwCarriesTheBox()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    MovableWidget* const box = stage.box();
    QWidget* const video = stage.selfSurface();
    QCOMPARE(box->pos(), QPoint(757, 369));
    const QPoint start = box->geometry().center();
    mouseAt(stage, video, QEvent::MouseButtonPress, start, Qt::LeftButton, 1000);
    // 180 px to the left in 60 ms: 3000 px/s, let go while moving.
    for (int step = 1; step <= 6; ++step) {
        mouseAt(stage, video, QEvent::MouseMove, start - QPoint(30 * step, 0), Qt::LeftButton,
                1000 + 10 * step);
    }
    QCOMPARE(box->pos(), QPoint(577, 369));
    QSignalSpy drops(box, &MovableWidget::dropped);
    mouseAt(stage, video, QEvent::MouseButtonRelease, start - QPoint(180, 0), Qt::NoButton, 1060);
    QCOMPARE(drops.count(), 1);
    QCOMPARE(drops.first().first().toPointF(), QPointF(-3000, 0));
    // At rest it stands right of the middle; the throw takes it to the left corner.
    QTRY_COMPARE(box->pos(), QPoint(16, 369));
}

void TestNetCamView::windowRowHasTheStageControls()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    const QVector<AirCallControl*> row = stage.row();
    QCOMPARE(row.size(), 3);
    QCOMPARE(row[0]->kind(), AirCallControl::Kind::SelfView);
    QCOMPARE(row[1]->kind(), AirCallControl::Kind::Messages);
    QCOMPARE(row[2]->kind(), AirCallControl::Kind::FullScreen);
    // Columns of 96 in the middle, 24 over the bottom; each control has room for its word's shadow.
    QCOMPARE(row[0]->geometry(), QRect(368, 526, 96, 95));
    QCOMPARE(row[1]->geometry(), QRect(464, 526, 96, 95));
    QCOMPARE(row[2]->geometry(), QRect(560, 526, 96, 95));
    QCOMPARE(AirCallControl::controlHeight(), 90);
    QVERIFY(!stage.header()->isVisibleTo(&stage.view));
}

void TestNetCamView::rowFollowsTheReadingDirection()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    stage.view.setLayoutDirection(Qt::RightToLeft);
    QCoreApplication::processEvents();
    const QVector<AirCallControl*> row = stage.row();
    QCOMPARE(row.size(), 3);
    QCOMPARE(row[0]->kind(), AirCallControl::Kind::FullScreen);
    QCOMPARE(row[2]->kind(), AirCallControl::Kind::SelfView);
    // The trailing corner is the bottom left one.
    QCOMPARE(stage.box()->pos(), QPoint(16, 369));
    stage.view.setLayoutDirection(Qt::LeftToRight);
    QCOMPARE(stage.box()->pos(), QPoint(757, 369));

    // The link mark of the head stands where the words start: left, then right.
    fullScreen(stage);
    const AirLinkMark* const mark = stage.header()->findChild<AirLinkMark*>();
    QVERIFY(mark != nullptr);
    QVERIFY(mark->x() < stage.header()->width() / 2);
    stage.view.setLayoutDirection(Qt::RightToLeft);
    QVERIFY(mark->x() > stage.header()->width() / 2);
}

void TestNetCamView::fullScreenHasItsHeadAndTheWholeRow()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    stage.view.setSeconds(754);
    stage.view.setLink(Air::LinkState::Direct);
    fullScreen(stage);
    QCOMPARE(stage.view.size(), QSize(800, 600));
    QVERIFY(stage.control("callStageFullScreen")->isEngaged());
    QVERIFY(stage.control("callStageFullScreen")->isChecked());

    AirCallHeader* const header = stage.header();
    QVERIFY(header->isVisibleTo(&stage.view));
    // 24 from the top of the screen; the head is 28 + 6 + 21 + 12 + 26 = 93 high, with the shadow
    // room round it.
    QCOMPARE(AirCallHeader::contentHeight(), 93);
    QCOMPARE(header->geometry(), QRect(0, 19, 800, 103));
    QCOMPARE(header->accessibleName(), QStringLiteral("Amina Rahman, 12:34, Direct link"));

    const QVector<AirCallControl*> row = stage.row();
    QCOMPARE(row.size(), 5);
    using Kind = AirCallControl::Kind;
    const Kind kinds[] = {Kind::Microphone, Kind::Sound, Kind::SelfView, Kind::FullScreen,
                          Kind::End};
    for (int i = 0; i < row.size(); ++i) {
        QCOMPARE(row[i]->kind(), kinds[i]);
        QCOMPARE(row[i]->geometry(), QRect(160 + 96 * i, 486, 96, 95));
    }
    // Between the head (117 + 16) and the row (486 - 16): 22 % of 600 is 132.
    QCOMPARE(stage.box()->geometry(), QRect(549, 338, 235, 132));

    QTest::keyClick(stage.control("callStageFullScreen"), Qt::Key_Escape);
    QTRY_VERIFY(!stage.view.isFullScreen());
    QVERIFY(!header->isVisibleTo(&stage.view));
    QCOMPARE(stage.row().size(), 3);
    QVERIFY(!stage.control("callStageFullScreen")->isEngaged());
}

void TestNetCamView::endLeavesFullScreenAndEnds()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    QSignalSpy ends(&stage.view, &NetCamView::videoCallEnd);
    fullScreen(stage);
    stage.control("callStageEnd")->click();
    QCOMPARE(ends.count(), 1);
    QTRY_VERIFY(!stage.view.isFullScreen());
    QVERIFY(!stage.header()->isVisibleTo(&stage.view));
}

void TestNetCamView::controlsSayOnAndOff()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    AirCallControl* const microphone = stage.control("callStageMicrophone");
    QSignalSpy toggles(&stage.view, &NetCamView::micMuteToggle);
    QAccessibleInterface* const access = QAccessible::queryAccessibleInterface(microphone);
    QVERIFY(access != nullptr);
    QCOMPARE(access->role(), QAccessible::CheckBox);
    QCOMPARE(access->text(QAccessible::Name), QStringLiteral("Microphone"));
    QVERIFY(access->state().checked);
    QVERIFY(!microphone->isEngaged());

    stage.view.updateMuteMicButton(true);
    QVERIFY(microphone->isEngaged());
    QVERIFY(!access->state().checked);
    // A click asks the call; the switch turns when the call says so.
    microphone->click();
    QCOMPARE(toggles.count(), 1);
    QVERIFY(microphone->isEngaged());
    stage.view.updateMuteMicButton(false);
    QVERIFY(access->state().checked);

    AirCallControl* const sound = stage.control("callStageSound");
    QSignalSpy soundToggles(&stage.view, &NetCamView::volMuteToggle);
    sound->click();
    QCOMPARE(soundToggles.count(), 1);
    stage.view.updateMuteVolButton(true);
    QVERIFY(sound->isEngaged());
    QVERIFY(!sound->isChecked());

    AirCallControl* const end = stage.control("callStageEnd");
    QVERIFY(!end->isCheckable());
    QCOMPARE(QAccessible::queryAccessibleInterface(end)->role(), QAccessible::Button);
    QCOMPARE(QAccessible::queryAccessibleInterface(end)->text(QAccessible::Name),
             QStringLiteral("End"));

    // Full screen is on while engaged; the others are on while not.
    AirCallControl* const full = stage.control("callStageFullScreen");
    QVERIFY(!full->isChecked());
    QCOMPARE(QAccessible::queryAccessibleInterface(full)->text(QAccessible::Name),
             QStringLiteral("Full screen"));
}

void TestNetCamView::selfViewHidesTheBox()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    AirCallControl* const selfView = stage.control("callStageSelfView");
    QVERIFY(selfView->isChecked());
    selfView->click();
    QVERIFY(!stage.box()->isVisibleTo(&stage.view));
    QVERIFY(selfView->isEngaged());
    QVERIFY(!selfView->isChecked());
    selfView->click();
    QVERIFY(stage.box()->isVisibleTo(&stage.view));

    // Hiding our video while it is the big one brings theirs back to the stage.
    clickBox(stage);
    QCOMPARE(stage.selfSurface()->parentWidget(), &stage.view);
    selfView->click();
    QCOMPARE(stage.friendSurface()->parentWidget(), &stage.view);
    QCOMPARE(stage.friendSurface()->geometry(), QRect(0, 0, 1024, 640));
    QVERIFY(stage.friendSurface()->isVisibleTo(&stage.view));
    QVERIFY(!stage.box()->isVisibleTo(&stage.view));
}

void TestNetCamView::messagesTellWhatCame()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    AirCallControl* const messages = stage.control("callStageMessages");
    QSignalSpy clicks(&stage.view, &NetCamView::showMessageClicked);
    QVERIFY(!messages->isEngaged());
    stage.view.setShowMessages(true, true);
    QVERIFY(messages->isEngaged());
    QVERIFY(messages->hasNotice());
    stage.view.setShowMessages(true);
    QVERIFY(!messages->hasNotice());
    stage.view.setShowMessages(false, true);
    QVERIFY(!messages->isEngaged());
    QVERIFY(!messages->hasNotice());
    messages->click();
    QCOMPARE(clicks.count(), 1);
}

void TestNetCamView::nothingOverlaps_data()
{
    QTest::addColumn<QSize>("size");
    QTest::addColumn<QSize>("camera");
    QTest::addColumn<bool>("full");
    const QSize windows[] = {QSize(640, 256), QSize(640, 480), QSize(1024, 640), QSize(1440, 900)};
    for (const QSize& size : windows) {
        for (const QSize& camera : {landscape, portrait}) {
            const QString name = QStringLiteral("%1x%2 %3").arg(size.width()).arg(size.height());
            QTest::newRow(qPrintable(name.arg(shapeWord(camera))))
                << size << camera << false;
        }
    }
    for (const QSize& size : {QSize(800, 600), QSize(1920, 1080)}) {
        for (const QSize& camera : {landscape, portrait}) {
            const QString name =
                QStringLiteral("full %1x%2 %3").arg(size.width()).arg(size.height());
            QTest::newRow(qPrintable(name.arg(shapeWord(camera))))
                << size << camera << true;
        }
    }
}

void TestNetCamView::nothingOverlaps()
{
    QFETCH(QSize, size);
    QFETCH(QSize, camera);
    QFETCH(bool, full);
    TestStage stage;
    startStage(stage, full ? QSize(1024, 640) : size, landscape);
    stage.camera.push(testCard(camera, selfTone, QStringLiteral("SELF")));
    if (full) {
        fullScreen(stage, size);
    }
    QCOMPARE(stage.view.size(), size);
    const QRect stageRect(QPoint(), size);
    const QRect box = stage.box()->geometry();
    QVERIFY(stage.box()->isVisibleTo(&stage.view));
    const QString where = QStringLiteral("%1,%2 %3x%4")
                              .arg(box.x())
                              .arg(box.y())
                              .arg(box.width())
                              .arg(box.height());
    QVERIFY2(stageRect.adjusted(16, 16, -16, -16).contains(box), qPrintable(where));
    // The box keeps the camera's shape, give or take a pixel of rounding.
    QVERIFY(qAbs(box.width() * camera.height() - box.height() * camera.width())
            <= qMax(camera.width(), camera.height()));
    const QVector<AirCallControl*> row = stage.row();
    QCOMPARE(row.size(), full ? 5 : 3);
    for (int i = 0; i < row.size(); ++i) {
        QVERIFY(stageRect.contains(row[i]->geometry()));
        QVERIFY(!row[i]->geometry().intersects(box));
        if (i > 0) {
            QVERIFY(!row[i]->geometry().intersects(row[i - 1]->geometry()));
        }
    }
    if (full) {
        QVERIFY(!stage.header()->geometry().intersects(box));
        QVERIFY(!stage.header()->geometry().intersects(row.first()->geometry()));
    }
}

void TestNetCamView::noDebugOverlays()
{
    TestStage stage;
    startStage(stage, QSize(1024, 640), landscape);
    // The stage holds its two videos, the box, the head and the six controls, and nothing else.
    const QList<QWidget*> parts =
        stage.view.findChildren<QWidget*>(QString(), Qt::FindDirectChildrenOnly);
    QCOMPARE(parts.size(), 9);
    int surfaces = 0;
    int controls = 0;
    for (QWidget* const part : parts) {
        if (qobject_cast<VideoSurface*>(part)) {
            ++surfaces;
        } else if (qobject_cast<AirCallControl*>(part)) {
            ++controls;
        } else {
            QVERIFY(qobject_cast<MovableWidget*>(part) || qobject_cast<AirCallHeader*>(part));
        }
    }
    QCOMPARE(surfaces, 1);
    QCOMPARE(controls, 6);
    QCOMPARE(stage.box()->findChildren<QWidget*>().size(), 1);
}

void TestNetCamView::settingsPreviewKeepsItsLook()
{
    // The preview of the settings: whole, not mirrored, black round it, as it was.
    CardSource camera;
    VideoSurface surface(QPixmap(), &camera);
    QCOMPARE(camera.subscribers, 1);
    QCOMPARE(surface.getLook(), VideoSurface::Look::Plain);
    QCOMPARE(surface.getScaling(), VideoSurface::Scaling::Fit);
    QVERIFY(!surface.isExpanding());
    QVERIFY(!surface.isMirrored());
    surface.resize(400, 300);
    camera.push(testCard(landscape, selfTone, QStringLiteral("SELF")));
    QCOMPARE(surface.getBoundingRect(), QRect(0, 37, 400, 225));
    const QImage picture = surface.grab().toImage();
    QCOMPARE(picture.pixelColor(200, 10), QColor(Qt::black));
    QVERIFY(near(picture.pixelColor(5, 150), leftEdge));

    VideoSurface filling(QPixmap(), static_cast<QWidget*>(nullptr), true);
    QVERIFY(filling.isExpanding());
    QCOMPARE(filling.getScaling(), VideoSurface::Scaling::Fill);
}

void TestNetCamView::stageIsDarkInBothThemes()
{
    if (!settings) {
        QSKIP("No settings of the test's own to change the theme with");
    }
    QImage pictures[2];
    QImage waiting[2];
    for (int dark = 0; dark < 2; ++dark) {
        useTheme(dark == 1);
        {
            TestStage stage;
            startStage(stage, QSize(1024, 640), portrait);
            stage.view.setSeconds(42);
            stage.view.setLink(Air::LinkState::Relay);
            fullScreen(stage, QSize(1440, 900));
            pictures[dark] = stage.view.grab().toImage();
        }
        TestStage stage;
        startStage(stage, QSize(1024, 640), landscape, false);
        waiting[dark] = stage.view.grab().toImage();
        // Until the first frame the stage has the lock ground and our box the lock-bottom one.
        QCOMPARE(waiting[dark].pixelColor(5, 5), AirStyle::darkColor(Color::LockTop));
        QCOMPARE(waiting[dark].pixelColor(757 + 125, 369 + 70),
                 AirStyle::darkColor(Color::LockBottom));
    }
    QCOMPARE(pictures[0], pictures[1]);
    QCOMPARE(waiting[0], waiting[1]);
}

void TestNetCamView::screenshots()
{
    const QString folder = qEnvironmentVariable("KHANDAQ_STAGE_SHOTS");
    if (folder.isEmpty()) {
        QSKIP("KHANDAQ_STAGE_SHOTS names no folder for the pictures");
    }
    if (!settings) {
        QSKIP("No settings of the test's own to change the theme with");
    }
    QVERIFY(QDir().mkpath(folder));
    const auto save = [&folder](NetCamView& view, const QString& name) {
        const QString path = QDir(folder).filePath(name + QStringLiteral(".png"));
        QVERIFY2(view.grab().save(path), qPrintable(path));
    };
    for (const bool dark : {false, true}) {
        useTheme(dark);
        const QString theme = dark ? QStringLiteral("dark") : QStringLiteral("light");
        for (const QSize& size : {QSize(640, 480), QSize(1024, 640), QSize(1440, 900)}) {
            for (const QSize& call : {landscape, portrait}) {
                for (const bool swapped : {false, true}) {
                    TestStage stage;
                    startStage(stage, size, call);
                    if (swapped) {
                        clickBox(stage);
                    }
                    save(stage.view, QStringLiteral("%1-%2x%3-%4-%5")
                                         .arg(theme)
                                         .arg(size.width())
                                         .arg(size.height())
                                         .arg(shapeWord(call))
                                         .arg(QLatin1String(swapped ? "swapped" : "normal")));
                }
            }
        }
        for (const QSize& call : {landscape, portrait}) {
            for (const bool swapped : {false, true}) {
                TestStage stage;
                startStage(stage, QSize(1024, 640), call);
                stage.view.setSeconds(754);
                stage.view.setLink(call == landscape ? Air::LinkState::Direct
                                                     : Air::LinkState::Relay);
                stage.view.updateMuteMicButton(swapped);
                fullScreen(stage, QSize(1920, 1080));
                if (swapped) {
                    clickBox(stage);
                }
                save(stage.view, QStringLiteral("%1-full-1920x1080-%2-%3")
                                     .arg(theme)
                                     .arg(shapeWord(call))
                                     .arg(QLatin1String(swapped ? "swapped-muted" : "normal")));
            }
        }
        // Our camera in portrait, as the 9:16 self card of the phones.
        for (const bool full : {false, true}) {
            TestStage stage;
            startStage(stage, QSize(1024, 640), landscape);
            stage.camera.push(testCard(portrait, selfTone, QStringLiteral("SELF")));
            if (full) {
                stage.view.setSeconds(754);
                stage.view.setLink(Air::LinkState::Direct);
                fullScreen(stage, QSize(1920, 1080));
            }
            save(stage.view, QStringLiteral("%1-%2-landscape-self-portrait")
                                 .arg(theme, full ? QStringLiteral("full-1920x1080")
                                                  : QStringLiteral("1024x640")));
        }
        {
            TestStage stage;
            startStage(stage, QSize(1024, 640), landscape, false);
            stage.view.setShowMessages(true, true);
            save(stage.view, theme + QStringLiteral("-1024x640-waiting"));
        }
        {
            TestStage stage;
            startStage(stage, QSize(1024, 640), landscape);
            stage.view.setLayoutDirection(Qt::RightToLeft);
            stage.view.setSeconds(5);
            fullScreen(stage, QSize(1024, 640));
            save(stage.view, theme + QStringLiteral("-full-1024x640-right-to-left"));
        }
    }

    // The longer words of other languages, from the compiled translations in KHANDAQ_STAGE_QM.
    const QString translations = qEnvironmentVariable("KHANDAQ_STAGE_QM");
    if (translations.isEmpty()) {
        return;
    }
    for (const QString& language : {QStringLiteral("ru"), QStringLiteral("ar")}) {
        QTranslator words;
        QVERIFY2(words.load(language, translations), qPrintable(language));
        QCoreApplication::installTranslator(&words);
        // The handlers retranslate, and the direction follows the language.
        Translator::translate(language);
        for (const QSize& size : {QSize(640, 480), QSize(1024, 640)}) {
            TestStage stage;
            startStage(stage, size, landscape);
            stage.view.setSeconds(754);
            stage.view.setLink(Air::LinkState::Direct);
            stage.view.updateMuteMicButton(true);
            stage.view.setShowMessages(true, true);
            const QString named =
                QStringLiteral("%1x%2-%3").arg(size.width()).arg(size.height()).arg(language);
            save(stage.view, QStringLiteral("dark-") + named);
            fullScreen(stage, size);
            save(stage.view, QStringLiteral("dark-full-") + named);
        }
        QCoreApplication::removeTranslator(&words);
        Translator::translate(QStringLiteral("en"));
    }
}

int main(int argc, char* argv[])
{
    // The widgets need a platform, not a screen: CI runs the tests without a display.
    if (qEnvironmentVariableIsEmpty("QT_QPA_PLATFORM")) {
        qputenv("QT_QPA_PLATFORM", "offscreen");
    }
    // The themes come from a Settings, which reads and writes the settings of the home folder: the
    // test gets a home of its own, which goes with it.
    QTemporaryDir home;
    if (home.isValid()) {
        qputenv("HOME", QFile::encodeName(home.path()));
        QStandardPaths::setTestModeEnabled(true);
    }
    QApplication app(argc, argv);
    AirStyle::installFonts();
    TestNetCamView test(home.isValid() ? home.path() : QString());
    return QTest::qExec(&test, argc, argv);
}

#include "netcamview_test.moc"
