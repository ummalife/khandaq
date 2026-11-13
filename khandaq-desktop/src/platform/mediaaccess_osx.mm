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

#import <AVFoundation/AVFoundation.h>

namespace MediaAccess {
namespace detail {

namespace {
AVMediaType mediaType(Device device)
{
    return device == Device::Microphone ? AVMediaTypeAudio : AVMediaTypeVideo;
}
} // namespace

State systemState(Device device)
{
    @autoreleasepool {
        switch ([AVCaptureDevice authorizationStatusForMediaType:mediaType(device)]) {
        case AVAuthorizationStatusAuthorized:
            return State::Granted;
        case AVAuthorizationStatusNotDetermined:
            return State::Undecided;
        case AVAuthorizationStatusDenied:
        case AVAuthorizationStatusRestricted:
            break;
        }
        return State::Denied;
    }
}

void systemAsk(Device device, std::function<void(bool granted)> done)
{
    @autoreleasepool {
        // The usage descriptions in Info.plist are the words of the system's question.
        [AVCaptureDevice requestAccessForMediaType:mediaType(device)
                                 completionHandler:^(BOOL granted) {
                                   done(granted == YES);
                                 }];
    }
}

} // namespace detail
} // namespace MediaAccess
