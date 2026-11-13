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

#include "mediaaccess.h"

#include <QCoreApplication>
#include <QDesktopServices>
#include <QObject>
#include <QPointer>
#include <QUrl>

#include <tuple>

#ifdef KHANDAQ_SCREENSHOT_HOOK
#include <QHash>
#endif

namespace MediaAccess {

namespace {
#ifdef KHANDAQ_SCREENSHOT_HOOK
QHash<int, State>& shotStates()
{
    static QHash<int, State> states;
    return states;
}
#endif
} // namespace

State state(Device device)
{
#ifdef KHANDAQ_SCREENSHOT_HOOK
    // The walk never reaches the system: its question would come up on the screen of the
    // computer the walk runs on.
    return shotStates().value(static_cast<int>(device), State::Granted);
#else
    return detail::systemState(device);
#endif
}

void ask(Device device, QObject* context, std::function<void(bool granted)> done)
{
    const State known = state(device);
    if (known != State::Undecided) {
        done(known == State::Granted);
        return;
    }
#ifdef KHANDAQ_SCREENSHOT_HOOK
    // Undecided in the walk: the person says yes.
    std::ignore = context;
    done(true);
#else
    QPointer<QObject> guard(context);
    detail::systemAsk(device, [guard, done](bool granted) {
        // The system answers on a thread of its own; the answer is taken on the main one, where
        // the guard is read too.
        QMetaObject::invokeMethod(
            QCoreApplication::instance(),
            [guard, done, granted] {
                if (guard) {
                    done(granted);
                }
            },
            Qt::QueuedConnection);
    });
#endif
}

bool openSettings(Device device)
{
#ifdef Q_OS_MACOS
    const QString pane = device == Device::Microphone ? QStringLiteral("Privacy_Microphone")
                                                      : QStringLiteral("Privacy_Camera");
    return QDesktopServices::openUrl(
        QUrl(QStringLiteral("x-apple.systempreferences:com.apple.preference.security?") + pane));
#else
    std::ignore = device;
    return false;
#endif
}

#ifdef KHANDAQ_SCREENSHOT_HOOK
void setStateForShots(Device device, State value)
{
    shotStates().insert(static_cast<int>(device), value);
}
#endif

#ifndef Q_OS_MACOS
namespace detail {
State systemState(Device device)
{
    std::ignore = device;
    return State::Granted;
}

void systemAsk(Device device, std::function<void(bool granted)> done)
{
    std::ignore = device;
    done(true);
}
} // namespace detail
#endif

} // namespace MediaAccess
