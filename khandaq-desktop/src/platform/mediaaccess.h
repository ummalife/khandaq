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

#include <functional>

class QObject;

/**
 * Whether the system lets the app use the microphone and the camera, asked before a call and never
 * assumed (design/DESIGN.md, "Files, voice and calls on the phone"). macOS keeps the answer per app
 * and asks the person once; Linux and Windows have no such question for desktop apps, so there the
 * access counts as granted and a refusal shows only as silence, as before.
 */
namespace MediaAccess {

enum class Device
{
    Microphone,
    Camera,
};

enum class State
{
    Granted,
    /** The system has not asked the person yet: ask() does. */
    Undecided,
    /** The person said no, or a policy of the computer does. */
    Denied,
};

State state(Device device);

/**
 * Where the system has not asked yet, shows its question; then runs done(granted) on the main
 * thread, unless context is gone by then. Where the answer is known, done runs at once.
 */
void ask(Device device, QObject* context, std::function<void(bool granted)> done);

/** Opens the privacy settings of the system at the device; false where there are none to open. */
bool openSettings(Device device);

#ifdef KHANDAQ_SCREENSHOT_HOOK
/** Test builds only: what the system answers in the review walk, without asking it. */
void setStateForShots(Device device, State state);
#endif

namespace detail {
/** The system's own answer: mediaaccess_osx.mm on macOS, mediaaccess.cpp elsewhere. */
State systemState(Device device);
/** Asks the system; done may run on any thread. */
void systemAsk(Device device, std::function<void(bool granted)> done);
} // namespace detail

} // namespace MediaAccess
