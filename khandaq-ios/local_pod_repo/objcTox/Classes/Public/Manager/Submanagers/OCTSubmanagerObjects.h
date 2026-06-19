// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTManagerConstants.h"
#import "OCTToxConstants.h"

@class OCTObject;
@class OCTFriend;
@class OCTChat;
@class OCTMessageAbstract;
@class RLMResults;

@protocol OCTSubmanagerObjects <NSObject>

/**
 * This property can be used to save any generic data you like.
 *
 * The default value is nil.
 */
@property (strong, nonatomic) NSData *genericSettingsData;

/**
 * Returns fetch request for specified type.
 *
 * @param type Type of fetch request.
 * @param predicate Predicate that represents search query.
 *
 * @return RLMResults with objects of specified type.
 */
- (RLMResults *)objectsForType:(OCTFetchRequestType)type predicate:(NSPredicate *)predicate;

/**
 * Returns object for specified type with uniqueIdentifier.
 *
 * @param uniqueIdentifier Unique identifier of object.
 * @param type Type of object.
 *
 * @return Object of specified type or nil, if object does not exist.
 */
- (OCTObject *)objectWithUniqueIdentifier:(NSString *)uniqueIdentifier forType:(OCTFetchRequestType)type;

#pragma mark -  Friends

/**
 * Sets nickname property for friend.
 *
 * @param friend Friend to change.
 * @param nickname New nickname. If nickname is empty or nil, it will be set to friends name.
 * If friend don't have name, it will be set to friends publicKey.
 */
- (void)changeFriend:(OCTFriend *)friend nickname:(NSString *)nickname;

#pragma mark -  Chats

/**
 * Sets enteredText property for chat.
 *
 * @param chat Chat to change.
 * @param enteredText New text.
 */
- (void)changeChat:(OCTChat *)chat enteredText:(NSString *)enteredText;

/**
 * Sets lastReadDateInterval property for chat.
 *
 * @param chat Chat to change.
 * @param lastReadDateInterval New interval.
 */
- (void)changeChat:(OCTChat *)chat lastReadDateInterval:(NSTimeInterval)lastReadDateInterval;

- (void)setGroupShowSystemMessages:(BOOL)enabled;

#pragma mark -  Saved Messages (KHANDAQ)

/**
 * Find or create the built-in local-only "Saved Messages" chat (friend-less, non-group).
 */
- (OCTChat *)getOrCreateSavedMessagesChat;

/**
 * Store a text message locally in a chat (no Tox transfer). Used for Saved Messages.
 */
- (OCTMessageAbstract *)addSavedTextMessage:(NSString *)text toChat:(OCTChat *)chat;

/**
 * Store a (ready) file message locally in a chat (no Tox transfer). Used for Saved Messages.
 */
- (OCTMessageAbstract *)addSavedFileMessageWithPath:(NSString *)filePath
                                           fileName:(NSString *)fileName
                                           fileSize:(OCTToxFileSize)fileSize
                                            fileUTI:(nullable NSString *)fileUTI
                                               toChat:(OCTChat *)chat;

/**
 * Flag a text message as the caption of the immediately-preceding file message (merged display).
 */
- (void)markMessageAsCaption:(OCTMessageAbstract *)message;

@end
