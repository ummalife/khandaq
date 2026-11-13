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

// The macOS answers live in systemappearance_osx.mm.

#include "systemappearance.h"

#include <QGuiApplication>
#include <QPalette>
#include <QSettings>

#ifdef Q_OS_WIN
#include <windows.h>
// Windows Vista and later; MinGW declares it only when _WIN32_WINNT is 0x0600 or higher, and the
// project leaves that to the toolchain's default.
#ifndef SPI_GETCLIENTAREAANIMATION
#define SPI_GETCLIENTAREAANIMATION 0x1042
#endif
#endif

namespace {

bool reduceMotionRequested()
{
    return qEnvironmentVariableIntValue("KHANDAQ_REDUCE_MOTION") == 1;
}

/**
 * The desktop palette before the app installs its own, which would otherwise answer the question
 * itself. Read once, on the first call, which happens before the theme is applied.
 */
bool startupPaletteIsDark()
{
    static const bool dark = [] {
        const QPalette palette = QGuiApplication::palette();
        return palette.color(QPalette::Window).lightness()
               < palette.color(QPalette::WindowText).lightness();
    }();
    return dark;
}

} // namespace

bool SystemAppearance::prefersDark()
{
#ifdef Q_OS_WIN
    const QSettings personalize(
        QStringLiteral(
            "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"),
        QSettings::NativeFormat);
    const QVariant appsUseLightTheme = personalize.value(QStringLiteral("AppsUseLightTheme"));
    if (appsUseLightTheme.isValid()) {
        return appsUseLightTheme.toInt() == 0;
    }
#endif
    return startupPaletteIsDark();
}

bool SystemAppearance::prefersReducedMotion()
{
    if (reduceMotionRequested()) {
        return true;
    }
#ifdef Q_OS_WIN
    BOOL animationsEnabled = TRUE;
    if (SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION, 0, &animationsEnabled, 0)) {
        return animationsEnabled == FALSE;
    }
#endif
    return false;
}

void SystemAppearance::setApplicationAppearance(Appearance appearance)
{
    // Only macOS has an app-wide appearance switch.
    Q_UNUSED(appearance)
}
