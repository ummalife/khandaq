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

#include "systemappearance.h"

#include <QtGlobal>

#import <AppKit/AppKit.h>

bool SystemAppearance::prefersDark()
{
    @autoreleasepool {
        // While the app follows the system, NSApp carries no appearance of its own, so its
        // effective appearance is the system's. Before NSApp exists the current one is the same.
        NSAppearance* appearance =
            NSApp != nil ? [NSApp effectiveAppearance] : [NSAppearance currentAppearance];
        if (appearance == nil) {
            return false;
        }
        NSAppearanceName best = [appearance
            bestMatchFromAppearancesWithNames:@[NSAppearanceNameAqua, NSAppearanceNameDarkAqua]];
        return [best isEqualToString:NSAppearanceNameDarkAqua];
    }
}

bool SystemAppearance::prefersReducedMotion()
{
    if (qEnvironmentVariableIntValue("KHANDAQ_REDUCE_MOTION") == 1) {
        return true;
    }
#ifdef KHANDAQ_SCREENSHOT_HOOK
    // The walk records the full motion even on a computer that asks for less of it.
    if (qEnvironmentVariableIntValue("KHANDAQ_SHOT_FULL_MOTION") == 1) {
        return false;
    }
#endif
    @autoreleasepool {
        return [[NSWorkspace sharedWorkspace] accessibilityDisplayShouldReduceMotion];
    }
}

void SystemAppearance::setApplicationAppearance(Appearance appearance)
{
    @autoreleasepool {
        if (NSApp == nil) {
            return;
        }
        switch (appearance) {
        case Appearance::Light:
            [NSApp setAppearance:[NSAppearance appearanceNamed:NSAppearanceNameAqua]];
            break;
        case Appearance::Dark:
            [NSApp setAppearance:[NSAppearance appearanceNamed:NSAppearanceNameDarkAqua]];
            break;
        case Appearance::System:
            [NSApp setAppearance:nil];
            break;
        }
    }
}
