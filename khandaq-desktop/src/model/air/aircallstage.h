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

#include <QPointF>
#include <QRect>
#include <QSize>
#include <Qt>

/**
 * KHANDAQ (calls stage, 26.11): the stage of a video call, apart from any widget. Where the two
 * videos stand and how each one takes its place, by the rules the three clients share for the call
 * screens (DESIGN.md, "Files, voice and calls on the phone"): the big video is never stretched, your
 * own video stands in a box with the aspect of your camera, in a corner of the free part of the
 * stage, and a click swaps the two. NetCamView lays its widgets out with these numbers, and the
 * tests check them without a call.
 */
namespace Air {
namespace CallStage {

/** How a video takes a box: covering it (centred, cut at the edges) or inside it (bars round it). */
enum class Scale
{
    Fill,
    Fit,
};

/** Landscape when the width is at least the height: a square counts as landscape. */
bool isLandscape(const QSize& size);

/**
 * How the big video takes the stage: Fill when the frame and the stage are both portrait or both
 * landscape, Fit otherwise, so a phone held upright shows whole in a wide window. A frame of
 * unknown size fills.
 */
Scale stageScale(const QSize& frame, const QSize& stage);

/**
 * Where a frame of size frame is drawn in box: centred with its aspect kept, covering box under
 * Fill (so it may stand out of box, which the painter clips) and inside box under Fit. A frame or
 * a box of unknown size gives box itself.
 */
QRect videoRect(const QSize& frame, const QRect& box, Scale scale);

/** The inset of the self view from the edges of the stage, the header and the control row. */
int selfInset();

/**
 * The box of your own video on the desktop for a stage of size stage. Its short side is
 * clamp(round(0.22 * min(width, height)), 96, 200) px, its long side follows the camera frame
 * (short side * long / short, rounded), and a landscape frame gives a landscape box. A frame of
 * unknown size counts as 16:9 landscape, the usual webcam.
 */
QSize selfSize(const QSize& stage, const QSize& frame);

/**
 * size made small enough to stand inside room, its aspect kept: a stage too short for the box of
 * selfSize must not put it over the header or the control row. A size that fits stays as it is.
 */
QSize fitInto(const QSize& size, const QSize& room);

/**
 * The part of the stage the self view may stand in: the stage less the inset on every side, below
 * the header while it shows (headerBottom is the y just under it) and above the control row while
 * it shows (rowTop is the y of its top edge). Pass a negative headerBottom or rowTop for a header
 * or a row that does not show.
 */
QRect selfArea(const QRect& stage, int headerBottom, int rowTop);

/** The rect of a box of size size in corner of area. */
QRect cornerRect(const QRect& area, const QSize& size, Qt::Corner corner);

/** Where the self view stands at first: bottom trailing, which is bottom left right to left. */
Qt::Corner defaultCorner(Qt::LayoutDirection direction);

/** How far ahead a released box is thrown: its velocity counts for this long, in seconds. */
constexpr double throwSeconds = 0.15;

/**
 * The corner a box of size size goes to when it is let go with its top left at released, moving
 * at velocity (px per second): the corner of area nearest to where its centre would be after
 * throwSeconds. A slow release goes to the nearest corner, a flick to the corner it was thrown to.
 */
Qt::Corner snapCorner(const QRect& area, const QSize& size, const QPointF& released,
                      const QPointF& velocity);

/** Which video stands big. A click on the small one swaps them; a second click swaps them back. */
class Views
{
public:
    enum class Video
    {
        Remote,
        Self,
    };

    void swap();
    bool isSwapped() const;
    /** The video that takes the stage: the remote one, or your own once swapped. */
    Video big() const;
    /** The video in the box. */
    Video small() const;
    /**
     * How video takes its place: the big one by stageScale on the stage, the small one fills the
     * box. frame is the size of that video's frame.
     */
    Scale scaleOf(Video video, const QSize& frame, const QSize& stage) const;

private:
    bool swapped = false;
};

/** The width of one column of the control row at most. */
int columnWidth();

/**
 * The control row: count equal columns of at most columnWidth, centred, no wider than the stage
 * less the inset on each side; height high, its bottom 24 px above the bottom edge of the stage.
 */
QRect rowRect(const QRect& stage, int count, int height);

/**
 * The pixel size the one line under a control takes: the label size while the words fit width, or
 * smaller to fit, but no smaller than 80 % of the label size and no smaller than 11 px; words that
 * do not fit then are cut short. textWidth is the width of the words at basePixelSize.
 */
qreal labelPixelSize(qreal basePixelSize, qreal textWidth, qreal width);

} // namespace CallStage
} // namespace Air
