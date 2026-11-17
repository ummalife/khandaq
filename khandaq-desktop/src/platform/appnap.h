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
 * macOS puts an app whose windows are hidden, minimised or covered into App Nap: its timers are
 * slowed down and coalesced, and the Tox loop, which has to answer the network every few hundred
 * milliseconds, falls behind until the connections time out and contacts see us offline.
 */
namespace AppNap {

/**
 * Keeps this process out of App Nap for as long as it runs. The computer still sleeps when it is
 * idle, and the display too. A second call does nothing. macOS only (appnap_osx.mm).
 */
void disable();

} // namespace AppNap
