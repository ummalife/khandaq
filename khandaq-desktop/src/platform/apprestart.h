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

#include <QStringList>

/**
 * KHANDAQ (0.2.60): Khandaq closes and starts again, for a setting that is read only at the start
 * (the interface size). The new run starts after this one has let go of everything a second run
 * would find taken: the profile and its lock, the history, the Tox port and the single-instance
 * memory (IPC), which is why main() starts it, after the AppManager is gone.
 */
namespace AppRestart {

/**
 * The arguments of this run that the next one is given too, before those of request: the network
 * options of the command line (a proxy above all), which hold for the session they were given for.
 */
void keep(const QStringList& arguments);

/**
 * Ends the event loop as Quit does; main() then starts the program again with the arguments kept
 * and these.
 */
void request(const QStringList& arguments);

/** Whether request ran in this process. */
bool requested();

/**
 * In main(), after the AppManager and the application object are gone: starts the program again
 * when a restart was asked for. False when none was asked for or the start failed.
 */
bool startIfRequested();

} // namespace AppRestart
