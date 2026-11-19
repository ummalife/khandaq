// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTToxConstants.h"

@class OCTChat;
@class OCTFriend;
@class OCTMessageAbstract;

/**
 * KHANDAQ (0.2.64): someone added a reaction to one of OUR messages, in a 1:1 chat or in a group. Posted
 * on the manager's notification center, on the main thread, with the keys below.
 */
FOUNDATION_EXPORT NSNotificationName const kOCTMessageReactionNotification;
/// The chat of the reacted message.
FOUNDATION_EXPORT NSString *const kOCTMessageReactionChatUniqueIdentifierKey;
/// The reacted message (ours).
FOUNDATION_EXPORT NSString *const kOCTMessageReactionMessageUniqueIdentifierKey;
FOUNDATION_EXPORT NSString *const kOCTMessageReactionEmojiKey;
/// The group member who reacted, as the group knows them; absent in a 1:1 chat.
FOUNDATION_EXPORT NSString *const kOCTMessageReactionReactorNameKey;
/// The event key of docs/PUSH_PREVIEWS.md (`r:<anchor>:<emoji>`), to match a push already shown for it.
FOUNDATION_EXPORT NSString *const kOCTMessageReactionEventKeyKey;

@protocol OCTSubmanagerChats <NSObject>

/**
 * Searches for a chat with specific friend. If chat is not found creates one and returns it.
 *
 * @param friend Friend to get/create chat with.
 *
 * @return Chat with specific friend.
 */
- (OCTChat *)getOrCreateChatWithFriend:(OCTFriend *)friend;

/**
 * Removes given messages.
 *
 * @param messages Array with messages to remove.
 *
 * @warning Destructive operation! There is no way to restore messages after removal.
 */
- (void)removeMessages:(NSArray<OCTMessageAbstract *> *)messages;

/**
 * KHANDAQ (#179): delete an OWN 1:1 text message locally AND retract it on the peer
 * (KQ lossless packet id 187, mirrors Android). Best effort: an offline peer or an old
 * client silently keeps its copy. Falls back to plain local removal for anything that
 * cannot be retracted (incoming messages, files, missing msgV3 hash).
 */
- (void)deleteMessageForBoth:(OCTMessageAbstract *)message;

/**
 * KHANDAQ (#192): toggle the OWN reaction on a 1:1 message (KQ lossless packet id 188,
 * mirrors Android). Same emoji removes, a different one replaces. Applied to Realm
 * immediately; delivery is queued (reactionsPending) when the peer is offline.
 */
- (void)toggleReactionOnMessage:(OCTMessageAbstract *)message emoji:(NSString *)emoji;

/**
 * KHANDAQ (#208): edit an OWN 1:1 text message in place and push the new text to the peer
 * (KQ lossless packet id 186, byte-parity with Android). Applied to Realm immediately; delivery
 * is queued (editPending) when the peer is offline. No-op for incoming messages, files, replies
 * without a msgV3 hash, or empty text. Saved Messages is edited locally only.
 */
- (void)editMessage:(OCTMessageAbstract *)message newText:(NSString *)newText;

/**
 * Removes all messages in chat and chat itself.
 *
 * @param chat Chat to remove in.
 * @param removeChat Whether remove chat or not
 *
 * @warning Destructive operation! There is no way to restore chat or messages after removal.
 */
- (void)removeAllMessagesInChat:(OCTChat *)chat removeChat:(BOOL)removeChat;

/**
 * Send text message to specific chat
 *
 * @param chat Chat send message to.
 * @param text Text to send.
 * @param type Type of message to send.
 * @param successBlock Block called when message was successfully send.
 * @param message Message that was send.
 * @param failureBlock Block called when submanager failed to send message.
 * @param error Error that occurred. See OCTToxErrorFriendSendMessage for all error codes.
 */
- (void)sendMessageToChat:(OCTChat *)chat
                     text:(NSString *)text
                     type:(OCTToxMessageType)type
             successBlock:(void (^)(OCTMessageAbstract *message))userSuccessBlock
             failureBlock:(void (^)(NSError *error))userFailureBlock;

/**
 * Send the parts of one long text to specific chat, in order, each as sendMessageToChat sends a text.
 *
 * The receiver sorts a message by its msgV3 timestamp, which has whole seconds, so a text to a chat is
 * stamped at least a second after the text sent to it before: the parts, sent within one second, and a
 * text sent right after them keep their order there. Only the first part wakes a friend who is offline:
 * its wake-up loop covers the text, and the push relay counts each wake.
 *
 * @param chat Chat send the parts to.
 * @param parts Texts that each fit one message.
 * @param type Type of message to send.
 * @param successBlock Block called for each part that was successfully sent, in order.
 * @param message Message that was sent.
 * @param index Index of the part in `parts`.
 * @param failureBlock Block called for each part submanager failed to send.
 * @param error Error that occurred. See OCTToxErrorFriendSendMessage for all error codes.
 */
- (void)sendMessagePartsToChat:(OCTChat *)chat
                         parts:(NSArray<NSString *> *)parts
                          type:(OCTToxMessageType)type
                  successBlock:(void (^)(OCTMessageAbstract *message, NSInteger index))userSuccessBlock
                  failureBlock:(void (^)(NSError *error, NSInteger index))userFailureBlock;

/**
 * Trigger PUSH Message to yourself
 */
- (void)sendOwnPush;

/**
 * Send own FCM push URL to all currently connected friends (lossless pkt 181).
 */
- (void)broadcastOwnPushURLToConnectedFriends;

/**
 * Trigger PUSH Message to Friend if the Text Message was sent in a period where the friend
 * was online to us, but the friend was in fact already offline.
 * so no message was sent AND not PUSH was triggered.
 */
- (void)sendMessagePushToChat:(OCTChat *)chat;

/**
 * Wake a friend's device with a push notification when they are offline.
 */
- (void)triggerWakePushForChat:(OCTChat *)chat;

/**
 * Set our typing status for a chat. You are responsible for turning it on or off.
 *
 * @param isTyping Status showing whether user is typing or not.
 * @param chat Chat to set typing status.
 * @param error If an error occurs, this pointer is set to an actual error object containing the error information.
 * See OCTToxErrorSetTyping for all error codes.
 *
 * @return YES on success, NO on failure.
 */
- (BOOL)setIsTyping:(BOOL)isTyping inChat:(OCTChat *)chat error:(NSError **)error;

/**
 * KHANDAQ (0.2.57): tells the friend of a 1:1 chat that the newest message or file they sent has been seen
 * (read receipt, "KQ" lossless packet 189). Call it only while the conversation is on screen with the app
 * in the foreground and unlocked, never from a list preview, a notification or the background.
 *
 * Does nothing for a group, the Saved chat, or when the newest of their messages and files that has an id
 * has been reported already. At most one receipt a second leaves for a friend, the later one replacing the
 * one that waits; while the friend is offline, or the send fails, it waits in the profile and leaves when
 * they connect.
 */
- (void)reportSeenInChat:(OCTChat *)chat;

/**
 * Retry stale undelivered direct messages (watchdog tick, ~5s cadence).
 */
- (void)tickDeliveryWatchdog;

@end
