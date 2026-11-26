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

#include "src/model/air/aircallstage.h"

#include <QtTest/QtTest>

using Air::CallStage::Scale;
using Air::CallStage::Views;

Q_DECLARE_METATYPE(Scale)

namespace {
const QSize landscapeFrame(1280, 720);
const QSize portraitFrame(720, 1280);
/** The free part of a 1024 by 640 window stage over a row of three controls, 90 px high. */
const QRect windowArea(16, 16, 992, 494);
} // namespace

class TestAirCallStage : public QObject
{
    Q_OBJECT
private slots:
    void squareCountsAsLandscape();
    void stageScaleFollowsOrientation_data();
    void stageScaleFollowsOrientation();
    void fitShowsTheWholeFrame();
    void fillCoversTheStage();
    void sameShapeFillsExactly();
    void videoRectKeepsTheBoxOffset();
    void unknownFrameTakesTheBox();
    void selfSizeFollowsTheShortSide_data();
    void selfSizeFollowsTheShortSide();
    void selfSizeTakesTheCameraShape();
    void fitIntoShrinksKeepingTheShape();
    void selfAreaKeepsTheInsets();
    void selfAreaStaysUnderTheHeaderAndOverTheRow();
    void cornersStandInTheArea();
    void defaultCornerIsBottomTrailing();
    void snapGoesToTheNearestCorner();
    void snapFollowsTheThrow();
    void viewsSwap();
    void smallVideoAlwaysFills();
    void rowCentresItsColumns();
    void rowShrinksItsColumnsOnANarrowStage();
    void labelShrinksThenStops();
};

void TestAirCallStage::squareCountsAsLandscape()
{
    QVERIFY(Air::CallStage::isLandscape(landscapeFrame));
    QVERIFY(!Air::CallStage::isLandscape(portraitFrame));
    QVERIFY(Air::CallStage::isLandscape(QSize(500, 500)));
}

void TestAirCallStage::stageScaleFollowsOrientation_data()
{
    QTest::addColumn<QSize>("frame");
    QTest::addColumn<QSize>("stage");
    QTest::addColumn<Scale>("scale");
    QTest::newRow("landscape on landscape") << landscapeFrame << QSize(640, 480) << Scale::Fill;
    QTest::newRow("portrait on landscape") << portraitFrame << QSize(640, 480) << Scale::Fit;
    QTest::newRow("portrait on portrait") << portraitFrame << QSize(480, 640) << Scale::Fill;
    QTest::newRow("landscape on portrait") << landscapeFrame << QSize(480, 640) << Scale::Fit;
    QTest::newRow("square on portrait") << QSize(500, 500) << QSize(480, 640) << Scale::Fit;
    QTest::newRow("square on landscape") << QSize(500, 500) << QSize(640, 480) << Scale::Fill;
    QTest::newRow("no frame yet") << QSize() << QSize(640, 480) << Scale::Fill;
}

void TestAirCallStage::stageScaleFollowsOrientation()
{
    QFETCH(QSize, frame);
    QFETCH(QSize, stage);
    QFETCH(Scale, scale);
    QCOMPARE(Air::CallStage::stageScale(frame, stage), scale);
}

void TestAirCallStage::fitShowsTheWholeFrame()
{
    // 640 * 720 / 1280 = 360: bars of 60 above and below.
    QCOMPARE(Air::CallStage::videoRect(landscapeFrame, QRect(0, 0, 640, 480), Scale::Fit),
             QRect(0, 60, 640, 360));
    // 480 * 720 / 1280 = 270: bars of 185 at the sides.
    QCOMPARE(Air::CallStage::videoRect(portraitFrame, QRect(0, 0, 640, 480), Scale::Fit),
             QRect(185, 0, 270, 480));
}

void TestAirCallStage::fillCoversTheStage()
{
    // 480 * 1280 / 720 = 853.3: the frame is cut by 106 and 107 px at the sides, never stretched.
    const QRect drawn =
        Air::CallStage::videoRect(landscapeFrame, QRect(0, 0, 640, 480), Scale::Fill);
    QCOMPARE(drawn, QRect(-106, 0, 853, 480));
    QVERIFY(drawn.contains(QRect(0, 0, 640, 480)));
    // A portrait frame on a portrait stage: 480 * 1280 / 720 = 853.3 high.
    QCOMPARE(Air::CallStage::videoRect(portraitFrame, QRect(0, 0, 480, 640), Scale::Fill),
             QRect(0, -106, 480, 853));
}

void TestAirCallStage::sameShapeFillsExactly()
{
    QCOMPARE(Air::CallStage::videoRect(landscapeFrame, QRect(0, 0, 1920, 1080), Scale::Fill),
             QRect(0, 0, 1920, 1080));
    QCOMPARE(Air::CallStage::videoRect(landscapeFrame, QRect(0, 0, 1920, 1080), Scale::Fit),
             QRect(0, 0, 1920, 1080));
}

void TestAirCallStage::videoRectKeepsTheBoxOffset()
{
    QCOMPARE(Air::CallStage::videoRect(landscapeFrame, QRect(10, 20, 640, 480), Scale::Fit),
             QRect(10, 80, 640, 360));
}

void TestAirCallStage::unknownFrameTakesTheBox()
{
    QCOMPARE(Air::CallStage::videoRect(QSize(), QRect(5, 5, 300, 200), Scale::Fit),
             QRect(5, 5, 300, 200));
}

void TestAirCallStage::selfSizeFollowsTheShortSide_data()
{
    QTest::addColumn<QSize>("stage");
    QTest::addColumn<QSize>("size");
    // The short side is 22 % of the stage's short side, from 96 to 200; the long one keeps 16:9.
    QTest::newRow("640x480") << QSize(640, 480) << QSize(188, 106);
    QTest::newRow("1024x640") << QSize(1024, 640) << QSize(251, 141);
    QTest::newRow("1440x900") << QSize(1440, 900) << QSize(352, 198);
    QTest::newRow("1920x1080 stops at 200") << QSize(1920, 1080) << QSize(356, 200);
    QTest::newRow("300x256 stops at 96") << QSize(300, 256) << QSize(171, 96);
}

void TestAirCallStage::selfSizeFollowsTheShortSide()
{
    QFETCH(QSize, stage);
    QFETCH(QSize, size);
    QCOMPARE(Air::CallStage::selfSize(stage, landscapeFrame), size);
}

void TestAirCallStage::selfSizeTakesTheCameraShape()
{
    const QSize stage(1024, 640);
    QCOMPARE(Air::CallStage::selfSize(stage, portraitFrame), QSize(141, 251));
    QCOMPARE(Air::CallStage::selfSize(stage, QSize(480, 480)), QSize(141, 141));
    QCOMPARE(Air::CallStage::selfSize(stage, QSize(640, 480)), QSize(188, 141));
    // Before the camera says, the desktop box is 16:9 landscape.
    QCOMPARE(Air::CallStage::selfSize(stage, QSize()), QSize(251, 141));
}

void TestAirCallStage::fitIntoShrinksKeepingTheShape()
{
    QCOMPARE(Air::CallStage::fitInto(QSize(251, 141), QSize(300, 300)), QSize(251, 141));
    QCOMPARE(Air::CallStage::fitInto(QSize(251, 141), QSize(200, 100)), QSize(178, 100));
    QCOMPARE(Air::CallStage::fitInto(QSize(251, 141), QSize(100, 200)), QSize(100, 56));
    QCOMPARE(Air::CallStage::fitInto(QSize(251, 141), QSize(0, 100)), QSize(0, 0));
}

void TestAirCallStage::selfAreaKeepsTheInsets()
{
    QCOMPARE(Air::CallStage::selfArea(QRect(0, 0, 1024, 640), -1, -1), QRect(16, 16, 992, 608));
    QCOMPARE(Air::CallStage::selfArea(QRect(0, 0, 20, 20), -1, -1), QRect(16, 16, 0, 0));
}

void TestAirCallStage::selfAreaStaysUnderTheHeaderAndOverTheRow()
{
    // The window: the row of three stands at 526 (640 - 24 - 90).
    QCOMPARE(Air::CallStage::selfArea(QRect(0, 0, 1024, 640), -1, 526), windowArea);
    // Full screen: the head ends at 117 (24 + 93), the row of five stands at 966.
    QCOMPARE(Air::CallStage::selfArea(QRect(0, 0, 1920, 1080), 117, 966),
             QRect(16, 133, 1888, 817));
}

void TestAirCallStage::cornersStandInTheArea()
{
    const QSize size(251, 141);
    QCOMPARE(Air::CallStage::cornerRect(windowArea, size, Qt::BottomRightCorner),
             QRect(757, 369, 251, 141));
    QCOMPARE(Air::CallStage::cornerRect(windowArea, size, Qt::BottomLeftCorner),
             QRect(16, 369, 251, 141));
    QCOMPARE(Air::CallStage::cornerRect(windowArea, size, Qt::TopLeftCorner),
             QRect(16, 16, 251, 141));
    QCOMPARE(Air::CallStage::cornerRect(windowArea, size, Qt::TopRightCorner),
             QRect(757, 16, 251, 141));
    // 16 from the right edge of the stage, 16 over the row.
    const QRect bottomRight = Air::CallStage::cornerRect(windowArea, size, Qt::BottomRightCorner);
    QCOMPARE(1024 - (bottomRight.x() + bottomRight.width()), 16);
    QCOMPARE(526 - (bottomRight.y() + bottomRight.height()), 16);
}

void TestAirCallStage::defaultCornerIsBottomTrailing()
{
    QCOMPARE(Air::CallStage::defaultCorner(Qt::LeftToRight), Qt::BottomRightCorner);
    QCOMPARE(Air::CallStage::defaultCorner(Qt::RightToLeft), Qt::BottomLeftCorner);
}

void TestAirCallStage::snapGoesToTheNearestCorner()
{
    const QSize size(251, 141);
    // The middle of the area is (512, 263); the box is measured by its centre.
    QCOMPARE(Air::CallStage::snapCorner(windowArea, size, QPointF(100, 300), QPointF()),
             Qt::BottomLeftCorner);
    QCOMPARE(Air::CallStage::snapCorner(windowArea, size, QPointF(600, 30), QPointF()),
             Qt::TopRightCorner);
    QCOMPARE(Air::CallStage::snapCorner(windowArea, size, QPointF(300, 100), QPointF()),
             Qt::TopLeftCorner);
    QCOMPARE(Air::CallStage::snapCorner(windowArea, size, QPointF(700, 360), QPointF()),
             Qt::BottomRightCorner);
}

void TestAirCallStage::snapFollowsTheThrow()
{
    const QSize size(251, 141);
    // At rest the centre (425.5, 170.5) is top left; thrown at 1000 px/s each way it goes 150 px
    // further, to (575.5, 320.5), bottom right.
    QCOMPARE(Air::CallStage::snapCorner(windowArea, size, QPointF(300, 100), QPointF(1000, 1000)),
             Qt::BottomRightCorner);
    // A slow throw does not carry it over the middle: 80 * 0.15 = 12 px.
    QCOMPARE(Air::CallStage::snapCorner(windowArea, size, QPointF(300, 100), QPointF(80, 80)),
             Qt::TopLeftCorner);
    // Thrown back to the left: 3000 * 0.15 = 450 px takes the centre from 825.5 to 375.5.
    QCOMPARE(Air::CallStage::snapCorner(windowArea, size, QPointF(700, 360), QPointF(-3000, 0)),
             Qt::BottomLeftCorner);
}

void TestAirCallStage::viewsSwap()
{
    Views views;
    QVERIFY(!views.isSwapped());
    QCOMPARE(views.big(), Views::Video::Remote);
    QCOMPARE(views.small(), Views::Video::Self);
    views.swap();
    QVERIFY(views.isSwapped());
    QCOMPARE(views.big(), Views::Video::Self);
    QCOMPARE(views.small(), Views::Video::Remote);
    views.swap();
    QCOMPARE(views.big(), Views::Video::Remote);
}

void TestAirCallStage::smallVideoAlwaysFills()
{
    const QSize stage(1024, 640);
    Views views;
    QCOMPARE(views.scaleOf(Views::Video::Remote, portraitFrame, stage), Scale::Fit);
    QCOMPARE(views.scaleOf(Views::Video::Self, landscapeFrame, stage), Scale::Fill);
    views.swap();
    // Swapped, the person's portrait video fills the small box; ours takes the stage.
    QCOMPARE(views.scaleOf(Views::Video::Remote, portraitFrame, stage), Scale::Fill);
    QCOMPARE(views.scaleOf(Views::Video::Self, landscapeFrame, stage), Scale::Fill);
    QCOMPARE(views.scaleOf(Views::Video::Self, portraitFrame, stage), Scale::Fit);
}

void TestAirCallStage::rowCentresItsColumns()
{
    QCOMPARE(Air::CallStage::columnWidth(), 96);
    QCOMPARE(Air::CallStage::rowRect(QRect(0, 0, 1024, 640), 3, 90), QRect(368, 526, 288, 90));
    QCOMPARE(Air::CallStage::rowRect(QRect(0, 0, 1920, 1080), 5, 90), QRect(720, 966, 480, 90));
    QCOMPARE(Air::CallStage::rowRect(QRect(0, 0, 1024, 640), 0, 90), QRect(512, 526, 0, 90));
}

void TestAirCallStage::rowShrinksItsColumnsOnANarrowStage()
{
    // 268 px between the insets make three columns of 89.
    QCOMPARE(Air::CallStage::rowRect(QRect(0, 0, 300, 256), 3, 90), QRect(16, 142, 267, 90));
}

void TestAirCallStage::labelShrinksThenStops()
{
    QCOMPARE(Air::CallStage::labelPixelSize(13.5, 60, 80), 13.5);
    QCOMPARE(Air::CallStage::labelPixelSize(13.5, 100, 90), 12.15);
    // Never under 11 px: the rest is elided.
    QCOMPARE(Air::CallStage::labelPixelSize(13.5, 200, 90), 11.0);
    QCOMPARE(Air::CallStage::labelPixelSize(13.5, 0, 90), 13.5);
    // A base under 11 px is not grown.
    QCOMPARE(Air::CallStage::labelPixelSize(10, 200, 90), 10.0);
}

QTEST_GUILESS_MAIN(TestAirCallStage)
#include "aircallstage_test.moc"
