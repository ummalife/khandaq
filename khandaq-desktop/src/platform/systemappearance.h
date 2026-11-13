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

/**
 * What the operating system asks of the look of every app: a light or a dark appearance, and
 * whether motion should be kept to a minimum.
 *
 * macOS and Windows are asked live, so a change in System Settings is seen on the next call.
 * Elsewhere the desktop's palette at start-up decides, and "reduce motion" can only be asked for
 * with the KHANDAQ_REDUCE_MOTION=1 environment variable (which works on every system).
 */
namespace SystemAppearance {

enum class Appearance
{
    /** Follow the system. */
    System,
    Light,
    Dark,
};

bool prefersDark();
bool prefersReducedMotion();

/**
 * macOS: draws window frames and native controls in the given appearance; System hands the choice
 * back to the system. Other systems have no app-wide switch, so this does nothing there.
 */
void setApplicationAppearance(Appearance appearance);

} // namespace SystemAppearance
