// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTPushUrlValidator.h"

/**
 * The relays this client will call. Same two the send path already gated on by prefix
 * (OCTSubmanagerChatsImpl.m) and the first two Android accepts (PushUrlValidator.java).
 */
static NSString *const kKhandaqRelayHost = @"push.khandaq.org";
static NSString *const kLegacyRelayHost = @"tox.zoff.xyz";
static NSString *const kWakePath = @"/toxfcm/fcm.php";

@implementation OCTPushUrlValidator

+ (BOOL)isAllowedPushURL:(nullable NSString *)pushUrl
{
    if (pushUrl == nil || pushUrl.length < 12) {
        return NO;
    }
    if (! [pushUrl hasPrefix:@"https://"]) {
        return NO;
    }

    // Reject before parsing, not after: these are exactly the characters that let two different
    // parsers disagree about the host, and a genuine wake URL never contains them. Whitespace is
    // also the input that makes +URLWithString: return nil on the older CFURL parser, which is the
    // crash this validator exists to stop.
    NSCharacterSet *forbidden = [NSCharacterSet characterSetWithCharactersInString:@"@\\"];
    if ([pushUrl rangeOfCharacterFromSet:forbidden].location != NSNotFound) {
        return NO;
    }
    if ([pushUrl rangeOfCharacterFromSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]].location != NSNotFound) {
        return NO;
    }

    NSURLComponents *components = [NSURLComponents componentsWithString:pushUrl];
    if (components == nil) {
        return NO;
    }
    if (! [components.scheme isEqualToString:@"https"]) {
        return NO;
    }

    NSString *host = components.host;
    if (host == nil) {
        return NO;
    }
    if ([host caseInsensitiveCompare:kKhandaqRelayHost] != NSOrderedSame
        && [host caseInsensitiveCompare:kLegacyRelayHost] != NSOrderedSame) {
        return NO;
    }

    if (! [components.path isEqualToString:kWakePath]) {
        return NO;
    }

    // The recipient token itself. Empty is useless and is what a probe looks like.
    for (NSURLQueryItem *item in components.queryItems) {
        if ([item.name isEqualToString:@"id"] && item.value.length > 0) {
            // Final gate: the platform must actually be able to build a URL from it, because that
            // is what the send path will do. Anything that fails here would raise there.
            return [NSURL URLWithString:pushUrl] != nil;
        }
    }
    return NO;
}

@end
