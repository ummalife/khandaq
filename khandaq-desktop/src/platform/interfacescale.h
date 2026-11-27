/*
    Copyright © 2026 by The Khandaq Project Contributors

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

#include <QByteArray>
#include <QRect>
#include <QSize>
#include <QString>
#include <QVector>

/**
 * KHANDAQ (0.2.60): the size of the interface, chosen in Settings > Appearance ("everything is
 * small", users on Windows). Qt takes a global scale factor only before the application object
 * exists, from QT_SCALE_FACTOR, so the choice is read from khandaq.ini before it is made and
 * comes into force when Khandaq starts. Qt 5.12 (Windows) has no other way to scale the whole
 * interface, fractional sizes included.
 */
namespace InterfaceScale {

/** The sizes Settings offers, in percent of the size Qt gives the screen; 100 changes nothing. */
const QVector<int>& percents();

/** percent when Settings offers it, else 100. */
int normalized(int percent);

/**
 * Whether a window that cannot be smaller than least fits in room at percent, both measured in
 * the pixels of the size in force: a larger size leaves fewer of them. A size not larger than the
 * one in force always fits, as does anything when room is unknown.
 */
bool fits(const QSize& room, const QSize& least, int inForce, int percent);

/** The khandaq.ini Settings reads: next to the program when that one makes Khandaq portable. */
QString globalSettingsFile(const QString& applicationDir);

/** The size stored in the khandaq.ini at path ([GUI] interfaceScale); 100 without one. */
int storedPercent(const QString& path);

/** The environment variables the decision reads, as the process found them. */
struct Environment
{
    bool scaleFactorSet = false;
    QByteArray scaleFactor;
    bool baseSet = false;
    QByteArray base;
};

/**
 * What the two variables become. QT_SCALE_FACTOR is the base scaled by the size; the base is what
 * the process got from outside Khandaq, kept in KHANDAQ_SCALE_BASE so that a Khandaq started
 * again from this one (Restart in Settings) scales from it and not from its own factor.
 */
struct Decision
{
    /** Unset QT_SCALE_FACTOR when false. */
    bool scaleFactorSet = false;
    QByteArray scaleFactor;
    /** Unset KHANDAQ_SCALE_BASE when false. */
    bool baseSet = false;
    QByteArray base;
};

/** The variables for percent, from those found. */
Decision decide(int percent, const Environment& found);

/**
 * Before the QApplication is made: reads the stored size, sets the variables for it and says what
 * it did in the log. argv0 helps to find the program only where the system cannot tell.
 */
void applyBeforeApplication(const char* argv0);

/** The size this run of Khandaq has, as applyBeforeApplication left it (100 before it ran). */
int inForce();

/**
 * KHANDAQ (inbox #27, 27.11): Qt 5.12 on Windows scales a screen by its DPI rounded to a whole
 * factor (QWindowsScreen::pixelDensity: 120 dpi, 125 %, is 1; 144 dpi, 150 %, is 2), so Khandaq
 * was drawn as at 100 % on a screen set to 125 %, and as at 200 % on one set to 150 %.
 * QT_SCREEN_SCALE_FACTORS multiplies that whole factor per screen: by what the screen's DPI asks
 * for over what Qt gives it, each screen comes to its own scale. The size chosen in Settings
 * (QT_SCALE_FACTOR) still comes on top of it.
 */
struct ScreenDpi
{
    /** The name Qt 5.12 gives the screen on Windows: its device's, such as \\.\DISPLAY1. */
    QString name;
    /** Its effective DPI: 96 at 100 %, 120 at 125 %. */
    int dpi;
};

/**
 * The factor a screen of dpi needs over the whole one Qt 5.12 gives it: 1.25 at 120 dpi, 0.75 at
 * 144, 1 at 96 and at 192. Never below 1 at a DPI below 96, which Qt does not scale down either.
 */
double screenCorrection(int dpi);

/**
 * QT_SCREEN_SCALE_FACTORS for screens: "name=factor" of each screen whose factor is not 1, joined
 * with ";"; empty when no screen needs one.
 */
QByteArray screenFactors(const QVector<ScreenDpi>& screens);

/**
 * KHANDAQ (inbox #27, 27.11): window, the frame of a window as it was saved, moved and shrunk by as little as it
 * takes to lie within available with room for a title bar of titleBar above it. A frame is saved in the pixels of
 * the scale its screen had: since screenFactors, Windows at 125 % is drawn at 125 %, and a window left at nearly
 * the whole of such a screen would come back larger than it, its foot under the taskbar.
 */
QRect windowWithin(const QRect& window, const QRect& available, int titleBar);

} // namespace InterfaceScale
