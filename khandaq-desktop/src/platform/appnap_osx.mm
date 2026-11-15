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

#include "appnap.h"

#import <Foundation/Foundation.h>

namespace {
/** The activity that keeps the process out of App Nap: retained, never ended, so it lasts. */
id<NSObject> activity = nil;
} // namespace

void AppNap::disable()
{
    if (activity != nil) {
        return;
    }
    @autoreleasepool {
        // KHANDAQ (0.2.57): with the window hidden or covered, App Nap slowed the Tox loop down
        // until the friends' connections dropped and they saw us go offline. Work the person
        // asked for is never put in App Nap; the variant that allows idle system sleep leaves the
        // sleep of the computer alone, and the termination flags are left as the app had them.
        const NSActivityOptions options = NSActivityUserInitiatedAllowingIdleSystemSleep
                                          & ~(NSActivitySuddenTerminationDisabled
                                              | NSActivityAutomaticTerminationDisabled);
        NSString* const reason = @"Khandaq keeps its Tox connections while its window is hidden";
        activity = [[[NSProcessInfo processInfo] beginActivityWithOptions:options
                                                                   reason:reason] retain];
    }
}
