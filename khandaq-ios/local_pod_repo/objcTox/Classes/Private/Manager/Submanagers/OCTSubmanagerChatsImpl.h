// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerChats.h"
#import "OCTSubmanagerProtocol.h"

NS_ASSUME_NONNULL_BEGIN

@interface OCTSubmanagerChatsImpl : NSObject <OCTSubmanagerChats, OCTSubmanagerProtocol>

/**
 * KHANDAQ (0.2.64), docs/PUSH_PREVIEWS.md: the fields every push preview starts from: the kind, our own
 * name and our clock.
 */
- (NSMutableDictionary<NSString *, id> *)pushPreviewFieldsWithKind:(NSString *)kind;

/**
 * One wake, without retries, to a friend who is offline, carrying a sealed preview. Nothing when the
 * friend is online, has no usable wake URL, or has not sent a preview key (an older client, which would
 * show a reaction wake as a message that is not there).
 */
- (void)sendPreviewWakeToFriend:(OCTFriend *)friend fields:(NSDictionary<NSString *, id> *)fields;

/**
 * The wake for a file queued while the friend is offline, with a preview of what the file is.
 */
- (void)triggerWakePushForChat:(OCTChat *)chat fileMessage:(nullable OCTMessageAbstract *)message;

/**
 * Our group presence (packet 190/02) to every connected friend, when we may have joined a group.
 */
- (void)broadcastGroupPresenceToConnectedFriends;

/**
 * Posts kOCTMessageReactionNotification for a reaction someone added to one of our own messages.
 */
- (void)postReactionOnOwnMessage:(OCTMessageAbstract *)message
                           emoji:(NSString *)emoji
                     reactorName:(nullable NSString *)reactorName
                        eventKey:(NSString *)eventKey;

@end

NS_ASSUME_NONNULL_END
