package com.zoffcc.applications.trifa;

import org.junit.Test;

import static com.zoffcc.applications.trifa.AirCallStage.CORNER_BOTTOM_LEFT;
import static com.zoffcc.applications.trifa.AirCallStage.CORNER_BOTTOM_RIGHT;
import static com.zoffcc.applications.trifa.AirCallStage.CORNER_TOP_LEFT;
import static com.zoffcc.applications.trifa.AirCallStage.CORNER_TOP_RIGHT;
import static com.zoffcc.applications.trifa.AirCallStage.Scale.FILL;
import static com.zoffcc.applications.trifa.AirCallStage.Scale.FIT;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** KHANDAQ (calls stage, 26.11): the numbers of the call stage spec, rules R1 to R9. */
public class AirCallStageTest
{
    private static final float EPS = 0.01f;
    private static final float[][] NONE = new float[0][];

    // ---- R1: the big picture ----

    @Test
    public void aPictureTurnedTheStagesWayFills()
    {
        assertEquals(FILL, AirCallStage.scaleFor(720, 1280, 1080, 2400));
        assertEquals(FILL, AirCallStage.scaleFor(1280, 720, 2400, 1080));
    }

    @Test
    public void aPictureTurnedTheOtherWayFits()
    {
        assertEquals(FIT, AirCallStage.scaleFor(1280, 720, 1080, 2400));
        assertEquals(FIT, AirCallStage.scaleFor(720, 1280, 2400, 1080));
    }

    @Test
    public void aSquareCountsAsLandscape()
    {
        assertEquals(FIT, AirCallStage.scaleFor(640, 640, 1080, 2400));
        assertEquals(FILL, AirCallStage.scaleFor(640, 640, 2400, 1080));
        assertEquals(FIT, AirCallStage.scaleFor(720, 1280, 800, 800));
    }

    @Test
    public void unknownSizesFill()
    {
        assertEquals(FILL, AirCallStage.scaleFor(0, 0, 1080, 2400));
        assertEquals(FILL, AirCallStage.scaleFor(720, 1280, 0, 0));
    }

    @Test
    public void aFillCoversTheStageCentredWithItsAspect()
    {
        // 720x1280 on 1080x2400: the height decides (1.875), 1350 wide, 135 cut off on each side.
        final float[] r = AirCallStage.place(720, 1280, 1080, 2400, FILL);
        assertArrayEquals(new float[]{-135f, 0f, 1215f, 2400f}, r, EPS);
        assertEquals(720f / 1280f, (r[2] - r[0]) / (r[3] - r[1]), 0.0001f);
    }

    @Test
    public void aFitStandsWholeInsideTheStage()
    {
        // 1280x720 on 1080x2400: the width decides (0.84375), 607.5 high, bars of 896.25 above and below.
        final float[] r = AirCallStage.place(1280, 720, 1080, 2400, FIT);
        assertArrayEquals(new float[]{0f, 896.25f, 1080f, 1503.75f}, r, EPS);
    }

    @Test
    public void aStretchedTextureGetsItsAspectBack()
    {
        // A 720x1280 camera frame stretched to a 1080x2400 view: fill keeps the height and widens 1.25 times.
        assertArrayEquals(new float[]{1.25f, 1f}, AirCallStage.stretchUndo(720, 1280, 1080, 2400, FILL), EPS);
        // The same frame in a box of its own aspect needs nothing.
        assertArrayEquals(new float[]{1f, 1f}, AirCallStage.stretchUndo(720, 1280, 102, 181.33334f, FILL), EPS);
        // Fit on a landscape tablet: the height decides, the width shrinks to 0.317 of the view.
        final float[] fit = AirCallStage.stretchUndo(720, 1280, 2560, 1600, FIT);
        assertEquals(720f * (1600f / 1280f) / 2560f, fit[0], EPS);
        assertEquals(1f, fit[1], EPS);
    }

    // ---- R2, R3: your own picture ----

    @Test
    public void theShortSideFollowsTheSpecExamples()
    {
        assertEquals(88, AirCallStage.selfShortSideDp(320, 568));
        assertEquals(102, AirCallStage.selfShortSideDp(393, 852));
        assertEquals(112, AirCallStage.selfShortSideDp(430, 932));
        assertEquals(168, AirCallStage.selfShortSideDp(820, 1180));
        // A landscape stage counts its height.
        assertEquals(102, AirCallStage.selfShortSideDp(852, 393));
    }

    @Test
    public void aPortraitCameraGivesAPortraitBoxOfItsAspect()
    {
        assertArrayEquals(new int[]{88, 156}, AirCallStage.selfSize(320, 568, 1f, 720, 1280));
        assertArrayEquals(new int[]{102, 181}, AirCallStage.selfSize(393, 852, 1f, 720, 1280));
        assertArrayEquals(new int[]{112, 199}, AirCallStage.selfSize(430, 932, 1f, 720, 1280));
        assertArrayEquals(new int[]{168, 299}, AirCallStage.selfSize(820, 1180, 1f, 720, 1280));
    }

    @Test
    public void theBoxIsCountedInDpAndGivenInPixels()
    {
        // 1080x2340 at 2.75 is 393x851 dp: 102x181 dp, 281x498 px.
        assertArrayEquals(new int[]{281, 498}, AirCallStage.selfSize(1080, 2340, 2.75f, 720, 1280));
    }

    @Test
    public void aLandscapeFrameGivesALandscapeBox()
    {
        assertArrayEquals(new int[]{181, 102}, AirCallStage.selfSize(852, 393, 1f, 1280, 720));
    }

    @Test
    public void anUnknownCameraIsThreeByFourPortrait()
    {
        assertArrayEquals(new int[]{102, 136}, AirCallStage.selfSize(393, 852, 1f, 0, 0));
    }

    @Test
    public void aLongPressMakesItBiggerButNeverPastTheArea()
    {
        assertArrayEquals(new int[]{224, 398}, AirCallStage.enlarged(new int[]{102, 181}, 361, 600));
        // A short area brings it down with its aspect kept.
        assertArrayEquals(new int[]{168, 299}, AirCallStage.enlarged(new int[]{102, 181}, 361, 299));
    }

    @Test
    public void fitInsideNeverScalesUp()
    {
        assertArrayEquals(new int[]{100, 200}, AirCallStage.fitInside(100, 200, 500, 500));
        assertArrayEquals(new int[]{50, 100}, AirCallStage.fitInside(100, 200, 500, 100));
    }

    // ---- R5: where it stands ----

    @Test
    public void theAreaIsTheStageWithinItsSafeEdgesTheGapIn()
    {
        // A 393x852 stage, 24 status bar (then 48 with a cutout), 24 gesture bar.
        assertArrayEquals(new float[]{16f, 40f, 377f, 812f}, AirCallStage.ownArea(393, 852, 0, 24, 0, 24, 16), EPS);
        assertArrayEquals(new float[]{16f, 64f, 377f, 812f}, AirCallStage.ownArea(393, 852, 0, 48, 0, 24, 16), EPS);
    }

    @Test
    public void theCornersOfAnEmptyStage()
    {
        final float[] area = {16f, 40f, 377f, 812f};
        assertArrayEquals(new float[]{275f, 631f}, AirCallStage.origin(CORNER_BOTTOM_RIGHT, area, 102, 181, NONE, 16), EPS);
        assertArrayEquals(new float[]{16f, 631f}, AirCallStage.origin(CORNER_BOTTOM_LEFT, area, 102, 181, NONE, 16), EPS);
        assertArrayEquals(new float[]{16f, 40f}, AirCallStage.origin(CORNER_TOP_LEFT, area, 102, 181, NONE, 16), EPS);
        assertArrayEquals(new float[]{275f, 40f}, AirCallStage.origin(CORNER_TOP_RIGHT, area, 102, 181, NONE, 16), EPS);
    }

    @Test
    public void onAPortraitPhoneTheBoxKeepsTheGapUnderTheHeaderAndOverTheRow()
    {
        // The header's words 120 to 273 across, down to 140; the circles and their labels from 724 to 806.
        final float[] area = {16f, 40f, 377f, 812f};
        final float[][] obstacles = {{120f, 52f, 273f, 140f}, {16f, 724f, 377f, 806f}};
        assertArrayEquals(new float[]{275f, 527f},
                          AirCallStage.origin(CORNER_BOTTOM_RIGHT, area, 102, 181, obstacles, 16), EPS);
        assertArrayEquals(new float[]{16f, 527f},
                          AirCallStage.origin(CORNER_BOTTOM_LEFT, area, 102, 181, obstacles, 16), EPS);
        assertArrayEquals(new float[]{16f, 156f}, AirCallStage.origin(CORNER_TOP_LEFT, area, 102, 181, obstacles, 16), EPS);
        assertArrayEquals(new float[]{275f, 156f},
                          AirCallStage.origin(CORNER_TOP_RIGHT, area, 102, 181, obstacles, 16), EPS);
    }

    @Test
    public void onAWideStageTheBoxKeepsItsCornerBesideTheRowAndTheHeader()
    {
        // An 852x393 stage: the row is held to 384 in the middle and the header's words are narrow, so a
        // landscape box 181x102 stays in its corner (R11) instead of being squeezed between them.
        final float[] area = AirCallStage.ownArea(852, 393, 0, 24, 0, 24, 16);
        assertArrayEquals(new float[]{16f, 40f, 836f, 353f}, area, EPS);
        final float[][] obstacles = {{346f, 40f, 506f, 142f}, {234f, 255f, 618f, 345f}};
        assertArrayEquals(new float[]{655f, 251f},
                          AirCallStage.origin(CORNER_BOTTOM_RIGHT, area, 181, 102, obstacles, 16), EPS);
        assertArrayEquals(new float[]{16f, 251f}, AirCallStage.origin(CORNER_BOTTOM_LEFT, area, 181, 102, obstacles, 16), EPS);
        assertArrayEquals(new float[]{655f, 40f}, AirCallStage.origin(CORNER_TOP_RIGHT, area, 181, 102, obstacles, 16), EPS);
    }

    @Test
    public void withNoRoomLeftTheBoxStaysInsideTheArea()
    {
        // The header reaches down to 560: over the row (527) the box would cross it, and above the header there
        // is no room, so it keeps to the area.
        final float[] area = {16f, 40f, 377f, 812f};
        final float[][] obstacles = {{16f, 52f, 377f, 560f}, {16f, 724f, 377f, 806f}};
        assertArrayEquals(new float[]{275f, 40f},
                          AirCallStage.origin(CORNER_BOTTOM_RIGHT, area, 102, 181, obstacles, 16), EPS);
    }

    @Test
    public void aLongPressGrowsTheBoxOnlyAsFarAsItsCornerStaysClear()
    {
        // A regular phone: the long-pressed 224 x 398 fits between the header and the row as it is.
        assertArrayEquals(new int[]{224, 398},
                          AirCallStage.enlargedClear(CORNER_BOTTOM_RIGHT, new float[]{16f, 40f, 377f, 812f},
                                                     new int[]{102, 181}, new int[]{224, 398},
                                                     new float[][]{{120f, 52f, 273f, 140f}, {16f, 724f, 377f, 806f}},
                                                     16));
        // A small phone, 360 x 640: 207 x 367 would reach the name. Between the header (156 with the gap) and
        // the row (504) there are 348, so the box stops at 196 x 347, its aspect kept.
        assertArrayEquals(new int[]{196, 347},
                          AirCallStage.enlargedClear(CORNER_BOTTOM_RIGHT, new float[]{16f, 40f, 344f, 600f},
                                                     new int[]{94, 167}, new int[]{207, 367},
                                                     new float[][]{{100f, 52f, 260f, 140f}, {16f, 520f, 344f, 592f}},
                                                     16));
        // A phone on its side, 852 x 393: over the row and right of the header, 314 x 176 at most.
        assertArrayEquals(new int[]{314, 176},
                          AirCallStage.enlargedClear(CORNER_BOTTOM_RIGHT, new float[]{16f, 40f, 836f, 353f},
                                                     new int[]{181, 102}, new int[]{398, 224},
                                                     new float[][]{{346f, 40f, 506f, 142f}, {234f, 255f, 618f, 345f}},
                                                     16));
        // No room even for a bit more: the plain size.
        assertArrayEquals(new int[]{102, 181},
                          AirCallStage.enlargedClear(CORNER_BOTTOM_RIGHT, new float[]{16f, 40f, 377f, 812f},
                                                     new int[]{102, 181}, new int[]{224, 398},
                                                     new float[][]{{16f, 52f, 377f, 560f}, {16f, 724f, 377f, 806f}},
                                                     16));
    }

    @Test
    public void aBoxBiggerThanTheAreaIsCentredOnThatAxis()
    {
        final float[] area = {16f, 100f, 377f, 200f};
        // 181 high in 100: centred, 40.5 over the top of the area.
        assertArrayEquals(new float[]{275f, 59.5f},
                          AirCallStage.origin(CORNER_BOTTOM_RIGHT, area, 102, 181, NONE, 16), EPS);
    }

    @Test
    public void theDefaultCornerIsBottomTrailing()
    {
        assertEquals(CORNER_BOTTOM_RIGHT, AirCallStage.defaultCorner(false));
        assertEquals(CORNER_BOTTOM_LEFT, AirCallStage.defaultCorner(true));
    }

    // ---- R6: drag, snap, swap ----

    @Test
    public void aSlowReleaseSnapsToTheNearestCorner()
    {
        final float[] area = {16f, 156f, 377f, 708f};
        // Centre 230,300: the top-right place (its centre 326,246.5) is the nearest.
        assertEquals(CORNER_TOP_RIGHT, AirCallStage.snapCorner(179, 209.5f, 102, 181, 0, 0, area, NONE, 16));
        assertEquals(CORNER_BOTTOM_LEFT, AirCallStage.snapCorner(40, 500, 102, 181, 0, 0, area, NONE, 16));
    }

    @Test
    public void aFlickCarriesItToTheCornerItWasThrownAt()
    {
        final float[] area = {16f, 156f, 377f, 708f};
        // Centre 230,300 thrown left and down at 1200 px/s: 0.15 s later at 50,480, nearest the bottom-left place.
        assertEquals(CORNER_BOTTOM_LEFT, AirCallStage.snapCorner(179, 209.5f, 102, 181, -1200, 1200, area, NONE, 16));
        // Too slow to get far: it stays nearest its own corner.
        assertEquals(CORNER_TOP_RIGHT, AirCallStage.snapCorner(179, 209.5f, 102, 181, -100, 100, area, NONE, 16));
    }

    @Test
    public void theHeaderPushesTheTopPlacesDownAndSoDecidesTheSnap()
    {
        final float[] area = {16f, 40f, 377f, 812f};
        final float[][] obstacles = {{120f, 52f, 273f, 140f}, {16f, 724f, 377f, 806f}};
        // Centre 300,430 is a little under the middle of the area, yet the top-right place, pushed down under the
        // header (centre 326,246.5), lies nearer than the bottom-right one over the row (326,617.5).
        assertEquals(CORNER_TOP_RIGHT, AirCallStage.snapCorner(249, 339.5f, 102, 181, 0, 0, area, obstacles, 16));
        // On an empty stage the same release goes to the bottom-right corner.
        assertEquals(CORNER_BOTTOM_RIGHT, AirCallStage.snapCorner(249, 339.5f, 102, 181, 0, 0, area, NONE, 16));
    }

    @Test
    public void aDraggedBoxStaysOnTheStage()
    {
        assertEquals(16f, AirCallStage.clamp(-40f, 16f, 275f), EPS);
        assertEquals(275f, AirCallStage.clamp(400f, 16f, 275f), EPS);
        assertEquals(100f, AirCallStage.clamp(100f, 16f, 275f), EPS);
        assertEquals(16f, AirCallStage.clamp(100f, 16f, 10f), EPS);
    }

    @Test
    public void aTapSwapsAndAnotherSwapsBack()
    {
        final AirCallStage.Tiles tiles = new AirCallStage.Tiles(CORNER_BOTTOM_RIGHT);
        assertTrue(tiles.remoteIsBig());
        tiles.swap();
        assertFalse(tiles.remoteIsBig());
        assertTrue(tiles.swapped());
        tiles.swap();
        assertTrue(tiles.remoteIsBig());
        tiles.swap();
        tiles.unswap();
        assertTrue(tiles.remoteIsBig());
        assertEquals(CORNER_BOTTOM_RIGHT, tiles.corner());
    }

    @Test
    public void aLongPressTogglesTheBigSize()
    {
        final AirCallStage.Tiles tiles = new AirCallStage.Tiles(CORNER_TOP_LEFT);
        assertFalse(tiles.enlarged());
        tiles.toggleEnlarged();
        assertTrue(tiles.enlarged());
        tiles.toggleEnlarged();
        assertFalse(tiles.enlarged());
    }

    // ---- turning the phone ----

    @Test
    public void aPhoneHeldToPortraitTurnsThePictureAllTheWay()
    {
        assertEquals(0, AirCallStage.remainingRotation(0, 0));
        assertEquals(270, AirCallStage.remainingRotation(90, 0));
        assertEquals(180, AirCallStage.remainingRotation(180, 0));
        assertEquals(90, AirCallStage.remainingRotation(270, 0));
    }

    @Test
    public void aTabletThatTurnsItsDisplayNeedsNothingMore()
    {
        // Turned counter-clockwise: the accelerometer says 270, the display draws at ROTATION_90.
        assertEquals(0, AirCallStage.remainingRotation(270, 90));
        assertEquals(0, AirCallStage.remainingRotation(90, 270));
        assertEquals(0, AirCallStage.remainingRotation(180, 180));
    }

    @Test
    public void theCameraPreviewTurnsForTheDisplayAsItStands()
    {
        // Most phones held upright: the front sensor at 270 and the back one at 90 both need 90, the old fixed value.
        assertEquals(90, AirCallStage.cameraDisplayOrientation(true, 270, 0));
        assertEquals(90, AirCallStage.cameraDisplayOrientation(false, 90, 0));
        // A tablet turned to landscape needs no turn at all.
        assertEquals(0, AirCallStage.cameraDisplayOrientation(true, 270, 90));
        assertEquals(0, AirCallStage.cameraDisplayOrientation(false, 90, 90));
        assertEquals(180, AirCallStage.cameraDisplayOrientation(false, 90, 270));
        // A back camera mounted the other way round (270) was upside down with the fixed 90.
        assertEquals(270, AirCallStage.cameraDisplayOrientation(false, 270, 0));
    }

    @Test
    public void aQuarterTurnTradesTheFramesSides()
    {
        assertArrayEquals(new int[]{720, 1280}, AirCallStage.shownFrame(1280, 720, 90));
        assertArrayEquals(new int[]{720, 1280}, AirCallStage.shownFrame(1280, 720, 270));
        assertArrayEquals(new int[]{1280, 720}, AirCallStage.shownFrame(1280, 720, 0));
        assertArrayEquals(new int[]{1280, 720}, AirCallStage.shownFrame(1280, 720, 180));
    }

    @Test
    public void quartersAndSideways()
    {
        assertEquals(270, AirCallStage.quarter(-90));
        assertEquals(0, AirCallStage.quarter(360));
        assertEquals(90, AirCallStage.quarter(80));
        assertTrue(AirCallStage.sideways(90));
        assertTrue(AirCallStage.sideways(270));
        assertFalse(AirCallStage.sideways(180));
    }

    // ---- R7, R9 ----

    @Test
    public void aLabelShrinksToFitButNotPastTheFloor()
    {
        assertEquals(12.5f, AirCallStage.labelSize(12.5f, 10f, 70f, 80f), EPS);
        assertEquals(11.25f, AirCallStage.labelSize(12.5f, 10f, 80f, 72f), EPS);
        assertEquals(10f, AirCallStage.labelSize(12.5f, 10f, 160f, 72f), EPS);
    }

    @Test
    public void thePersonStandsInTheMiddleOnlyWithNoVideoEitherWay()
    {
        assertTrue(AirCallStage.personInMiddle(false, false));
        assertFalse(AirCallStage.personInMiddle(true, false));
        assertFalse(AirCallStage.personInMiddle(false, true));
        assertFalse(AirCallStage.personInMiddle(true, true));
    }

    @Test
    public void theTileOfAnAudioCall()
    {
        assertEquals(112, AirCallStage.avatarDp(393));
        assertEquals(128, AirCallStage.avatarDp(600));
        assertEquals(128, AirCallStage.avatarDp(800));
    }

    @Test
    public void inAShortWindowTheTileGivesWayToTheWords()
    {
        // 112 dp at density 2.625 is 294 px; the floor of 64 dp is 168 px.
        assertEquals(294, AirCallStage.avatarSide(294, 1000, 168));
        assertEquals(200, AirCallStage.avatarSide(294, 200, 168));
        assertEquals(168, AirCallStage.avatarSide(294, 168, 168));
        assertEquals(0, AirCallStage.avatarSide(294, 167, 168));
        assertEquals(0, AirCallStage.avatarSide(294, -40, 168));
    }
}
