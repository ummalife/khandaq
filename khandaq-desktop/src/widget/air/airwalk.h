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

#ifdef KHANDAQ_SCREENSHOT_HOOK

class QString;
class QWidget;

/**
 * Test builds only (cmake -DKHANDAQ_SCREENSHOT_HOOK=ON): what the walks of the review pages ask of
 * a window besides its picture. The file is not built at all without the hook.
 */
namespace AirWalk {

/**
 * Saves the window every ms milliseconds, count times, as <prefix>-NN.png while the script goes
 * on, to catch a motion. It stops early once the window is hidden.
 */
void frames(QWidget* window, int count, int ms, const QString& prefix);

/**
 * Writes into the log, one "Walk audit:" line each, what a review would otherwise check by eye:
 * every control Tab reaches, in that order, with the name a screen reader says, its tooltip and
 * how many pixels round it change when it takes the focus (none: the focus does not show); icon
 * buttons without a name or a tooltip; text a label or a button has no room for; widgets squeezed
 * below their least size, pushed out of their parent or laid over a neighbour of their layout.
 * The focus is back where it was afterwards.
 */
void audit(QWidget* window, const QString& label);

/**
 * Counts for ms milliseconds what is painted inside the window, then writes one "Walk idle:" line.
 * Run while nothing happens: a motion that never ends keeps painting.
 */
void idle(QWidget* window, int ms, const QString& label);

/**
 * Writes the least size of root and of what it holds, depth levels down, one "Walk least:" line
 * each, beside the size each has: where a window's least size comes from.
 */
void least(QWidget* root, int depth, const QString& label);

} // namespace AirWalk

#endif
