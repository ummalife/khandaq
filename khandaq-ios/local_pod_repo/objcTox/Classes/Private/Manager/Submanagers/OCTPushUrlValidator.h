// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * KHANDAQ (audit 2026-08-20) — strict validation of a push wake URL received from a CONTACT.
 *
 * A friend's push token arrives as a raw string over the Tox wire (`OCTTox.m`, packet id 181) and
 * was written straight into Realm and later handed to `+[NSURL URLWithString:]` with no check of any
 * kind. That is two problems in one: a string that is not a URL at all makes `+URLWithString:`
 * return nil, and `-[NSMutableURLRequest initWithURL:nil]` raises — inside a bare dispatch_async
 * block, so the app dies. And a string that IS a URL but points somewhere else is an SSRF / IP-leak
 * primitive.
 *
 * Android has had this validation since audit A33 (`PushUrlValidator.java`); iOS had a Swift
 * equivalent (`KhandaqPush.isAllowedPushURL`) that is not reachable from this pod and was never
 * called from anywhere. This is the pod-side mirror of the Android rules, kept deliberately close to
 * them: the two clients must agree about which tokens are usable, or a token one of them publishes
 * is a token the other silently cannot wake.
 */
@interface OCTPushUrlValidator : NSObject

/**
 * @return YES when `pushUrl` is a wake URL this client is willing to store and later call.
 *
 * Rejects, in this order: nil/short input, a non-https scheme, whitespace / '@' / '\\' (the classic
 * host-confusion smuggling characters — a legitimate wake URL contains none of them), anything
 * `NSURLComponents` cannot parse, a host outside the known relays, a path other than the exact
 * endpoint, and a missing or empty `id` parameter.
 */
+ (BOOL)isAllowedPushURL:(nullable NSString *)pushUrl;

/**
 * KHANDAQ (re-audit 2026-08-22, K-01) — our own wake URL to hand to ONE contact.
 *
 * The shared push HMAC is baked into every published binary, so anyone who unpacks one holds a
 * credential that wakes any device whose token they also have. The fix is a secret per relationship:
 * this device mints 32 random bytes for each contact, registers their SHA-256 with the relay, and
 * publishes them inside the wake URL that contact already receives over Tox.
 *
 * The minting and registration happen in the app target (`KhandaqPushCapability.swift`) — the pod
 * cannot reach app-target Swift, and registration needs the FCM challenge push, which only the app
 * receives. The result is left in NSUserDefaults, and this reads it. When there is none the URL is
 * returned unchanged, which is the pre-capability behaviour and is always safe: the relay never
 * requires a capability it was not given.
 */
+ (NSString *)ownWakeURLForToken:(NSString *)fcmToken friendPublicKey:(nullable NSString *)friendPublicKey;

@end

NS_ASSUME_NONNULL_END
