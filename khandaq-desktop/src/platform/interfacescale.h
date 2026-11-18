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

} // namespace InterfaceScale
