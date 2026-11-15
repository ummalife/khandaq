// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTObject.h"
#import "OCTToxConstants.h"

@class OCTMessageAbstract;

/**
 * Class that represents friend (or just simply contact).
 *
 * Please note that all properties of this object are readonly.
 * You can change some of them only with appropriate method in OCTSubmanagerObjects.
 */
@interface OCTFriend : OCTObject

/**
 * Friend number that is unique for Tox.
 * In case if friend will be deleted, old id may be reused on new friend creation.
 */
@property OCTToxFriendNumber friendNumber;

/**
 * Nickname of friend.
 *
 * When friend is created it is set to the publicKey.
 * It is set to name when obtaining name for the first time.
 * After that name is unchanged (unless it is changed explicitly).
 *
 * To change please use OCTSubmanagerObjects method.
 */
@property (nonnull) NSString *nickname;

/**
 * Public key of a friend, is kOCTToxPublicKeyLength length.
 * Is constant, cannot be changed.
 */
@property (nonnull) NSString *publicKey;

/**
 * Name of a friend.
 *
 * May be empty.
 */
@property (nullable) NSString *name;

/**
 * Status message of a friend.
 *
 * May be empty.
 */
@property (nullable) NSString *statusMessage;

/**
 * Status message of a friend.
 */
@property OCTToxUserStatus status;

/**
 * Property specifies if friend is connected. For type of connection you can check
 * connectionStatus property.
 */
@property BOOL isConnected;

/**
 * Connection status message of a friend.
 */
@property OCTToxConnectionStatus connectionStatus;

/**
 * The date interval when friend was last seen online.
 * Contains actual information in case if friend has connectionStatus offline.
 */
@property NSTimeInterval lastSeenOnlineInterval;

/**
 * Whether friend is typing now in current chat.
 */
@property BOOL isTyping;

/**
 * Data representation of friend's avatar.
 */
@property (nullable) NSData *avatarData;

/**
 * The date when friend was last seen online.
 * Contains actual information in case if friend has connectionStatus offline.
 */
- (nullable NSDate *)lastSeenOnline;

/**
 * Push Token of a friend.
 *
 * May be empty.
 */
@property (nullable) NSString *pushToken;

/**
 * Indicate if a friend has msgV3 Capability.
 */
@property BOOL msgv3Capability;

/**
 * Friend's capabilities. A 64 bit unsigned integer.
 */
@property (nonnull) NSString *capabilities2;

/**
 * KHANDAQ (0.2.57): the seen watermark of the 1:1 chat with this friend: the sent time, by this device's
 * clock, of the newest of our messages and files the friend has reported seen (read receipt, "KQ" packet
 * 189). 0 while they have reported nothing. It only moves forward and goes with the friend when the
 * contact is removed.
 */
@property NSTimeInterval seenWatermark;

/**
 * KHANDAQ (0.2.57): when the newest message or file from this friend that this device has reported seen
 * arrived here (its dateInterval), so it is reported once. 0 while nothing has been reported.
 */
@property NSTimeInterval seenReportedDateInterval;

/**
 * KHANDAQ (0.2.57): the read receipt that waits for this friend, the whole 41-byte packet: they were
 * offline or the send failed. nil while none waits.
 */
@property (nullable) NSData *seenPendingPacket;

/**
 * KHANDAQ (0.2.57): whether this friend has seen our outgoing `message` of their 1:1 chat: it was sent no
 * later than the seen watermark. The caller shows it seen only once it has left this device (sent or
 * delivered), never while it waits or after it failed.
 */
- (BOOL)hasSeenOutgoingMessage:(nonnull OCTMessageAbstract *)message;

@end

RLM_ARRAY_TYPE(OCTFriend)
