// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTObject.h"

@class OCTFriend;
@class OCTChat;
@class OCTMessageText;
@class OCTMessageFile;
@class OCTMessageCall;

/**
 * An abstract message that represents one chunk of chat history.
 *
 * Please note that all properties of this object are readonly.
 * You can change some of them only with appropriate method in OCTSubmanagerObjects.
 */
@interface OCTMessageAbstract : OCTObject

/**
 * The date interval when message was send/received.
 */
@property NSTimeInterval dateInterval;

/**
 * Unixtimestamp when messageV3 was sent or 0.
 */
@property NSTimeInterval tssent;

/**
 * Unixtimestamp when messageV3 was received or 0.
 */
@property NSTimeInterval tsrcvd;

/**
 * Unique identifier of friend that have send message.
 * If the message if outgoing senderUniqueIdentifier is nil.
 */
@property (nullable) NSString *senderUniqueIdentifier;

/**
 * NGC sender peer id. Zero for outgoing group messages; non-zero for incoming group messages.
 */
@property int32_t groupSenderPeerId;

/**
 * YES for messages inserted via NGC history sync (suppresses notification noise).
 */
@property BOOL groupHistorySync;

/**
 * YES for local system lines (join/leave/create) shown centered in the group timeline.
 */
@property BOOL groupSystemMessage;

/**
 * YES for outgoing group text queued while disconnected; flushed when the group connects.
 */
@property BOOL groupPendingSend;

/**
 * YES for NGC private messages between peers (hidden from main group timeline).
 */
@property BOOL groupPrivateMessage;

/**
 * Counterparty peer id for private group messages.
 */
@property int32_t groupPrivatePeerId;

/**
 * KHANDAQ (#55): stable LOWERCASE pubkey hex of the counterparty (the OTHER peer in a private
 * thread). The thread/identity is keyed by this so it survives volatile NGC peer-id reuse. nil for
 * legacy rows or when unresolved (group offline) — callers then fall back to groupPrivatePeerId.
 */
@property (nullable) NSString *groupPrivatePeerPubkey;

/**
 * The chat message message belongs to.
 */
@property (nonnull) NSString *chatUniqueIdentifier;

/**
 * KHANDAQ: YES for a text message that is the caption of the immediately-preceding file message.
 * Local-only display hint (Telegram-style merged media+caption); not transmitted over Tox.
 */
@property BOOL isCaption;

/**
 * Message has one of the following properties.
 */
@property (nullable) OCTMessageText *messageText;
@property (nullable) OCTMessageFile *messageFile;
@property (nullable) OCTMessageCall *messageCall;

/**
 * The date when message was send/received.
 */
- (nonnull NSDate *)date;

/**
 * Indicates if message is outgoing or incoming.
 * In case if it is incoming you can check `sender` property for message sender.
 */
- (BOOL)isOutgoing;

@end

RLM_ARRAY_TYPE(OCTMessageAbstract)
