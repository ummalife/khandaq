// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTObject.h"

/**
 * NGC group peer persisted for a group chat.
 */
@interface OCTGroupPeer : OCTObject

@property (nonnull) NSString *chatUniqueIdentifier;
@property int32_t peerId;
@property (nullable) NSString *peerName;

/**
 * When YES, suppress local notifications for messages from this peer in the group.
 */
@property BOOL peerNotificationsSilent;

/**
 * Cached Tox group role (`OCTToxGroupRole` raw value).
 */
@property int32_t peerRole;

/**
 * Cached Tox group peer public key (lowercase hex), used for friend-assisted join targeting.
 */
@property (nullable) NSString *peerPublicKeyHex;

/**
 * Last time the local user read the private thread with this peer.
 */
@property NSTimeInterval privateLastReadDateInterval;

/**
 * Last time this peer was observed online in the group (Unix epoch seconds).
 */
@property NSTimeInterval peerLastSeenDateInterval;

@end

RLM_ARRAY_TYPE(OCTGroupPeer)
