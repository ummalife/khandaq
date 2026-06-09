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

@end

RLM_ARRAY_TYPE(OCTMessageText)
