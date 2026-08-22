// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTPushUrlValidator.h"
#import <Security/Security.h>

/**
 * The relays this client will call. Same two the send path already gated on by prefix
 * (OCTSubmanagerChatsImpl.m) and the first two Android accepts (PushUrlValidator.java).
 */
static NSString *const kKhandaqRelayHost = @"push.khandaq.org";
static NSString *const kLegacyRelayHost = @"tox.zoff.xyz";
static NSString *const kWakePath = @"/toxfcm/fcm.php";

/// Where the app target leaves a registered capability. Must match KhandaqPush.swift.
static NSString *const kCapabilityDefaultsPrefix = @"khandaq_pushcap_";
/// The FCM token those capabilities were registered against.
static NSString *const kCapabilityTokenKey = @"khandaq_pushcap_token";
/// KHANDAQ (re-review 2026-08-22, KQ-08): capabilities are bearer secrets and live in the Keychain,
/// device-only. Same service string as KhandaqPush.swift; changing one without the other silently
/// stops this pod from finding them, and the symptom is push URLs published without a capability.
static NSString *const kCapabilityKeychainService = @"org.khandaq.pushcap";

/// Read one capability. Keychain first, then the pre-KQ-08 preference — the app migrates on its own
/// read, and this must keep working on a device where that migration has not run yet.
static NSString *khandaqCapabilityValue(NSString *account)
{
    if (account.length == 0) {
        return nil;
    }
    NSDictionary *query = @{
        (__bridge id)kSecClass : (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService : kCapabilityKeychainService,
        (__bridge id)kSecAttrAccount : account,
        (__bridge id)kSecReturnData : @YES,
        (__bridge id)kSecMatchLimit : (__bridge id)kSecMatchLimitOne,
    };
    // kSecAttrAccessible is NOT in the query on purpose: there it acts as a search predicate and
    // would miss items stored under a different class.
    CFTypeRef result = NULL;
    OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &result);
    if (status == errSecSuccess && result != NULL) {
        NSData *data = (__bridge_transfer NSData *)result;
        NSString *value = [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding];
        if (value.length > 0) {
            return value;
        }
    }
    else if (result != NULL) {
        CFRelease(result);
    }

    NSString *legacy = [[NSUserDefaults standardUserDefaults] stringForKey:account];
    return legacy.length > 0 ? legacy : nil;
}

@implementation OCTPushUrlValidator

+ (NSString *)ownWakeURLForToken:(NSString *)fcmToken friendPublicKey:(nullable NSString *)friendPublicKey
{
    NSString *base = [NSString stringWithFormat:@"https://%@%@?id=%@&type=1",
                                                kKhandaqRelayHost, kWakePath, fcmToken ?: @""];
    if (fcmToken.length == 0 || friendPublicKey.length == 0) {
        return base;
    }

    NSString *registeredFor = khandaqCapabilityValue(kCapabilityTokenKey);
    if (registeredFor == nil || ![registeredFor isEqualToString:fcmToken]) {
        // The FCM token has rotated. Every capability was registered against the old one and can
        // never be used again, so publishing one would be worse than publishing none: the relay
        // would be handed a capability it does not recognise. The app re-registers against the new
        // token and the next publish carries the new capability.
        return base;
    }

    NSString *key = [kCapabilityDefaultsPrefix stringByAppendingString:friendPublicKey.uppercaseString];
    NSString *cap = khandaqCapabilityValue(key);
    if (cap.length == 0) {
        return base;
    }
    return [NSString stringWithFormat:@"%@&cap=%@", base, cap];
}

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
