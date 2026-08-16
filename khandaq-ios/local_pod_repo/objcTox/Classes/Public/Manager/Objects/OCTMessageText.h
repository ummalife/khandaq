// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTObject.h"
#import "OCTToxConstants.h"

@class OCTToxConstants;

/**
 * Simple text message.
 *
 * Please note that all properties of this object are readonly.
 * You can change some of them only with appropriate method in OCTSubmanagerObjects.
 */
@interface OCTMessageText : OCTObject

/**
 * The text of the message.
 */
@property (nullable) NSString *text;

/**
 * Indicate if message is delivered. Actual only for outgoing messages.
 */
@property BOOL isDelivered;

/**
 * Type of the message.
 */
@property OCTToxMessageType type;

@property OCTToxMessageId messageId;

/**
 * msgV3 Hash as uppercase Hexstring.
 *
 * May be empty if message is not v3.
 */
@property (nullable) NSString *msgv3HashHex;

/**
 * Indicate if message has triggered a push notification.
 */
@property BOOL sentPush;

/**
 * Display name of NGC peer (incoming group messages only).
 */
@property (nullable) NSString *groupPeerName;

/**
 * Public key of the author, frozen at insert time, uppercase hex (incoming group messages only).
 *
 * The same thing OCTMessageFile.groupSenderPubkey already stores, and for the same reason: the only
 * other handle on the author is groupSenderPeerId, which NGC re-issues on leave/rejoin, so a later
 * lookup can legitimately answer with a different person. On a history-synced row this is an
 * UNSIGNED claim made by the peer that relayed it — it is what the signature check is checked
 * AGAINST, never evidence on its own.
 */
@property (nullable) NSString *groupSenderPubkey;

/**
 * Count of hist-sync delivery confirmations for outgoing group messages.
 */
@property int32_t groupSyncConfirmations;

/**
 * Pubkeys of peers who confirmed delivery via hist-sync (dedup, up to 3 stored).
 */
@property (nullable) NSString *groupSyncPeerKey1;
@property (nullable) NSString *groupSyncPeerKey2;
@property (nullable) NSString *groupSyncPeerKey3;

@end

RLM_ARRAY_TYPE(OCTMessageText)
