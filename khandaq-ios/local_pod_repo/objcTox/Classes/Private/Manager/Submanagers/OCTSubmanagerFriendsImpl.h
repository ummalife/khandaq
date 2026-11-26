// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerFriends.h"
#import "OCTSubmanagerProtocol.h"

@class OCTGroupContactPendingOffer;

NS_ASSUME_NONNULL_BEGIN

@interface OCTSubmanagerFriendsImpl : NSObject <OCTSubmanagerFriends, OCTSubmanagerProtocol>

/**
 * KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): the member we offered to add answered, with their
 * accept over the group or with their contact request carrying the nonce of the offer. They are added without a request
 * of ours (tox_friend_add_norequest: they asked for it with their own), named with their name in the group until their
 * own name comes. `offer` is one the caller took from the store (OCTGroupContactStore), so the other answer to it finds
 * it gone and adds nothing. `contactAddressHex` is their whole ID when the accept brought it. A contact already there
 * is left as it is. NO when toxcore refused the key.
 */
- (BOOL)finishGroupContactOffer:(OCTGroupContactPendingOffer *)offer
            contactPublicKeyHex:(NSString *)contactPublicKeyHex
              contactAddressHex:(nullable NSString *)contactAddressHex;

@end

NS_ASSUME_NONNULL_END
