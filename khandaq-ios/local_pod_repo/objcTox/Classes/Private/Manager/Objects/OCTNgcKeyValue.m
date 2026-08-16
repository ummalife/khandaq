// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTNgcKeyValue.h"

@implementation OCTNgcKeyValue

+ (NSDictionary *)defaultPropertyValues
{
    // Realm rejects a nil NSString on write. A row that exists with an empty value is also how
    // "cleared" is represented, so the two states stay distinguishable from "row absent".
    return @{
               @"value" : @"",
    };
}

@end
