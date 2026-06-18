// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTObject.h"
#import "OCTFriend.h"

@class OCTMessageAbstract;

/**
 * Please note that all properties of this object are readonly.
 * You can change some of them only with appropriate method in OCTSubmanagerObjects.
 */
@interface OCTChat : OCTObject

/**
 * YES for NGC group chats; 1:1 chats use `friends` instead.
 */
@property BOOL isGroup;

/**
 * Tox group number (valid when `isGroup` is YES).
 */
@property int32_t groupNumber;

/**
 * 64-char hex group chat id (valid when `isGroup` is YES).
 */
@property (nullable) NSString *groupChatIdHex;

/**
 * Display name of the group.
 */
@property (nullable) NSString *groupName;

/**
 * OCTToxGroupPrivacyState raw value.
 */
@property int32_t groupPrivacyState;

/**
 * OCTToxGroupVoiceState raw value.
 */
@property int32_t groupVoiceState;

/**
 * Cached NGC peer count (includes self when known).
 */
@property int32_t groupPeerCount;

/**
 * When YES, suppress local notifications for this group chat.
 */
@property BOOL groupNotificationsSilent;

/**
 * NGC group topic (display title).
 */
@property (nullable) NSString *groupTopic;

/**
 * Cached group password when set (founder-visible via Tox).
 */
@property (nullable) NSString *groupPassword;

/**
 * When YES, only founder and moderators may set the group topic.
 */
@property BOOL groupTopicLockEnabled;

/**
 * Maximum peers allowed in the group (0 = use default).
 */
@property int32_t groupPeerLimit;

@property (nonnull) RLMArray<OCTFriend> *friends;

/**
 * The latest message that was send or received.
 */
@property (nullable) OCTMessageAbstract *lastMessage;

/**
 * This property can be used for storing entered text that wasn't send yet.
 *
 * To change please use OCTSubmanagerObjects method.
 *
 * May be empty.
 */
@property (nullable) NSString *enteredText;

/**
 * This property stores last date interval when chat was read.
 * `hasUnreadMessages` method use lastReadDateInterval to determine if there are unread messages.
 *
 * To change please use OCTSubmanagerObjects method.
 */
@property NSTimeInterval lastReadDateInterval;

/**
 * Date interval of lastMessage or chat creationDate if there is no last message.
 *
 * This property is workaround to support sorting. Should be replaced with keypath
 * lastMessage.dateInterval sorting in future.
 * See https://github.com/realm/realm-cocoa/issues/1277
 */
@property NSTimeInterval lastActivityDateInterval;

/**
 * The date when chat was read last time.
 */
- (nullable NSDate *)lastReadDate;

/**
 * Returns date of lastMessage or chat creationDate if there is no last message.
 */
- (nullable NSDate *)lastActivityDate;

/**
 * If there are unread messages in chat YES is returned. All messages that have date later than lastReadDateInterval
 * are considered as unread.
 *
 * Please note that you have to set lastReadDateInterval to make this method work.
 *
 * @return YES if there are unread messages, NO otherwise.
 */
- (BOOL)hasUnreadMessages;

@end

RLM_ARRAY_TYPE(OCTChat)
