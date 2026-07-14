// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTToxConstants.h"
#import "OCTManagerConstants.h"

@class OCTObject;
@class OCTFriend;
@class OCTChat;
@class OCTCall;
@class OCTMessageAbstract;
@class OCTGroupPeer;
@class OCTSettingsStorageObject;
@class RLMResults;
@class RLMRealm;

@interface OCTRealmManager : NSObject

/**
 * Storage with all objcTox settings.
 */
@property (strong, nonatomic, readonly) OCTSettingsStorageObject *settingsStorage;

/**
 * The backing Realm. Exposed read-only so submanagers (e.g. groups/chats) can run
 * RLMObject class queries (objectsInRealm:) against it. The private class extension in
 * OCTRealmManager.m redeclares it readwrite. Access on the Realm's own thread only.
 */
@property (strong, nonatomic, readonly) RLMRealm *realm;

/**
 * Migrate unencrypted database to encrypted one.
 *
 * @param databasePath Path to unencrypted database.
 * @param encryptionKey Key used to encrypt database.
 * @param error Error parameter will be filled in case of failure. It will contain RLMRealm or NSFileManager error.
 *
 * @return YES on success, NO on failure.
 */
+ (BOOL)migrateToEncryptedDatabase:(NSString *)databasePath
                     encryptionKey:(NSData *)encryptionKey
                             error:(NSError **)error;

/**
 * Create RealmManager.
 *
 * @param fileURL path to Realm file. File will be created if it doesn't exist.
 * @param encryptionKey A 64-byte key to use to encrypt the data, or nil if encryption is not enabled.
 */
- (instancetype)initWithDatabaseFileURL:(NSURL *)fileURL encryptionKey:(NSData *)encryptionKey;

- (NSURL *)realmFileURL;

#pragma mark -  Basic methods

- (id)objectWithUniqueIdentifier:(NSString *)uniqueIdentifier class:(Class)class;

- (RLMResults *)objectsWithClass:(Class)class predicate:(NSPredicate *)predicate;

- (void)addObject:(OCTObject *)object;
- (void)deleteObject:(OCTObject *)object;

/*
 * All realm objects should be updated ONLY using following two methods.
 *
 * Specified object will be passed in block.
 */
- (void)updateObject:(OCTObject *)object withBlock:(void (^)(id theObject))updateBlock;

- (void)updateObjectsWithClass:(Class)class
                     predicate:(NSPredicate *)predicate
                   updateBlock:(void (^)(id theObject))updateBlock;

#pragma mark -  Other methods

- (OCTFriend *)friendWithPublicKey:(NSString *)publicKey;
- (OCTChat *)getOrCreateChatWithFriend:(OCTFriend *)friend;
- (nullable OCTChat *)chatWithGroupNumber:(uint32_t)groupNumber;

// KHANDAQ: built-in local-only "Saved Messages" chat (friend-less, non-group). Messages are stored
// locally only — never sent over Tox.
- (OCTChat *)getOrCreateSavedMessagesChat;
- (OCTMessageAbstract *)addSavedTextMessage:(NSString *)text toChat:(OCTChat *)chat;
- (OCTMessageAbstract *)addSavedFileMessageWithPath:(NSString *)filePath
                                           fileName:(NSString *)fileName
                                           fileSize:(OCTToxFileSize)fileSize
                                            fileUTI:(nullable NSString *)fileUTI
                                               toChat:(OCTChat *)chat;
- (void)markMessageAsCaption:(OCTMessageAbstract *)message;
- (nullable OCTChat *)chatWithGroupChatIdHex:(NSString *)chatIdHex;
- (RLMResults *)groupChats;
- (NSArray<OCTChat *> *)groupChatsSnapshot;
- (OCTChat *)getOrCreateGroupChatWithGroupNumber:(uint32_t)groupNumber
                                      chatIdHex:(NSString *)chatIdHex
                                      groupName:(nullable NSString *)groupName
                                   privacyState:(OCTToxGroupPrivacyState)privacyState;
- (OCTCall *)createCallWithChat:(OCTChat *)chat status:(OCTCallStatus)status;

/**
 * Gets the current call for the chat if and only if it exists.
 * This will not create a call object.
 * @param chat The chat that is related to the call.
 * @return A call object if it exists, nil if no call is session for this call.
 */
- (OCTCall *)getCurrentCallForChat:(OCTChat *)chat;

- (void)removeMessages:(NSArray<OCTMessageAbstract *> *)messages;
- (void)removeAllMessagesInChat:(OCTChat *)chat removeChat:(BOOL)removeChat;

/**
 * Converts all the OCTCalls to OCTMessageCalls.
 * Only use this when first starting the app or during termination.
 */
- (void)convertAllCallsToMessages;

- (OCTMessageAbstract *)addMessageWithText:(NSString *)text
                                      type:(OCTToxMessageType)type
                                      chat:(OCTChat *)chat
                                    sender:(OCTFriend *)sender
                                 messageId:(OCTToxMessageId)messageId
                              msgv3HashHex:(NSString *)msgv3HashHex
                                  sentPush:(BOOL)sentPush
                                    tssent:(UInt32)tssent
                                    tsrcvd:(UInt32)tsrcvd;

- (OCTMessageAbstract *)addGroupMessageWithText:(NSString *)text
                                           type:(OCTToxMessageType)type
                                           chat:(OCTChat *)chat
                                         peerId:(uint32_t)peerId
                                       peerName:(nullable NSString *)peerName
                                      messageId:(OCTToxMessageId)messageId;

- (OCTMessageAbstract *)addGroupPendingMessageWithText:(NSString *)text
                                                  type:(OCTToxMessageType)type
                                                  chat:(OCTChat *)chat;

- (RLMResults *)pendingGroupMessagesForChat:(OCTChat *)chat;

- (void)markGroupPendingMessageSent:(OCTMessageAbstract *)message messageId:(uint32_t)messageId;

- (OCTMessageAbstract *)addGroupSystemMessageWithText:(NSString *)text inChat:(OCTChat *)chat;

- (OCTMessageAbstract *)addGroupMessageWithFileName:(NSString *)fileName
                                           fileSize:(uint64_t)fileSize
                                           filePath:(NSString *)filePath
                                           fileType:(OCTMessageFileType)fileType
                                               chat:(OCTChat *)chat
                                             peerId:(uint32_t)peerId
                                            fileUTI:(nullable NSString *)fileUTI
                                 groupMsgIdHashHex:(nullable NSString *)groupMsgIdHashHex
                              groupTransferProgress:(float)groupTransferProgress;

- (nullable OCTMessageAbstract *)groupMessageWithGroupMsgIdHashHex:(NSString *)groupMsgIdHashHex
                                                              chat:(OCTChat *)chat;

- (nullable OCTMessageAbstract *)groupIncompleteFileMessageForChat:(OCTChat *)chat
                                                            peerId:(uint32_t)peerId
                                                          fileName:(NSString *)fileName;

- (BOOL)markGroupIncomingFileReadyInChat:(OCTChat *)chat
                               msgIdHash:(NSString *)msgIdHash
                                  peerId:(uint32_t)peerId
                                fileName:(NSString *)fileName
                                filePath:(NSString *)filePath
                                fileSize:(uint64_t)fileSize;

/**
 * KHANDAQ (#170): content-level dedup for incoming NGC group files. YES when the chat already has a
 * READY file with the same size and same base file name (leading "NNN_" uniquifier prefixes ignored)
 * within the recent window — a re-broadcast / re-served copy of a file we already show.
 */
- (BOOL)groupReadyFileDuplicateExistsInChat:(OCTChat *)chat
                                   fileName:(NSString *)fileName
                                   fileSize:(uint64_t)fileSize;

- (NSArray<OCTMessageAbstract *> *)groupMessagesForHistorySyncInChat:(OCTChat *)chat;

- (NSArray<NSData *> *)groupHistorySyncPacketsForGroupNumber:(uint32_t)groupNumber
                                         packetFromMessage:(NSData *(^)(OCTMessageAbstract *message))packetBlock;

- (BOOL)groupTextMessageExistsInChat:(OCTChat *)chat
                           messageId:(uint32_t)messageId
                              peerId:(uint32_t)peerId
                                text:(NSString *)text;

// KHANDAQ (#114): peer-agnostic dedup by (chat + messageId + text). NGC relays/re-delivers the same
// logical message with the SAME messageId but a DIFFERENT (volatile) peerId, so the peerId-scoped
// variant above misses it on the live path. Window-free — a legitimate repeat carries a different
// messageId, so it is never collapsed.
- (BOOL)groupTextMessageExistsInChat:(OCTChat *)chat
                           messageId:(uint32_t)messageId
                                text:(NSString *)text;

// KHANDAQ (#42): persistent dedup that survives an app restart. History-sync re-delivers a message
// with its ORIGINAL timestamp; matching chat + identical text + (near) the same dateInterval catches
// a re-synced copy whose volatile messageId no longer matches the stored one. The tight window keeps
// it from merging two genuinely distinct identical texts (those would need the very same second).
- (BOOL)groupTextMessageExistsInChat:(OCTChat *)chat
                                text:(NSString *)text
                    nearDateInterval:(NSTimeInterval)dateInterval
                       windowSeconds:(NSTimeInterval)windowSeconds;

// KHANDAQ (#88): like the above but additionally scoped to the frozen sender name, so a single
// sender's delivery-retry storm collapses while identical short text from another peer survives.
- (BOOL)groupTextMessageExistsInChat:(OCTChat *)chat
                                text:(NSString *)text
                          senderName:(NSString *)senderName
                    nearDateInterval:(NSTimeInterval)dateInterval
                       windowSeconds:(NSTimeInterval)windowSeconds;

- (OCTMessageAbstract *)addGroupSyncedMessageWithText:(NSString *)text
                                                 type:(OCTToxMessageType)type
                                                 chat:(OCTChat *)chat
                                               peerId:(uint32_t)peerId
                                             peerName:(nullable NSString *)peerName
                                            messageId:(OCTToxMessageId)messageId
                                         dateInterval:(NSTimeInterval)dateInterval;

- (OCTMessageAbstract *)addGroupSyncedMessageWithFileName:(NSString *)fileName
                                                 fileSize:(uint64_t)fileSize
                                                 filePath:(NSString *)filePath
                                                 fileType:(OCTMessageFileType)fileType
                                                     chat:(OCTChat *)chat
                                                   peerId:(uint32_t)peerId
                                                 peerName:(nullable NSString *)peerName
                                                  fileUTI:(nullable NSString *)fileUTI
                                       groupMsgIdHashHex:(nullable NSString *)groupMsgIdHashHex
                                             dateInterval:(NSTimeInterval)dateInterval;

- (RLMResults *)groupPeersForChatUniqueIdentifier:(NSString *)chatUniqueIdentifier;
- (void)upsertGroupPeerForChat:(OCTChat *)chat peerId:(uint32_t)peerId peerName:(nullable NSString *)peerName;
- (void)upsertGroupPeerForChat:(OCTChat *)chat peerId:(uint32_t)peerId peerName:(nullable NSString *)peerName peerRole:(OCTToxGroupRole)peerRole;
- (void)upsertGroupPeerForChat:(OCTChat *)chat peerId:(uint32_t)peerId peerName:(nullable NSString *)peerName peerRole:(OCTToxGroupRole)peerRole peerPublicKeyHex:(nullable NSString *)peerPublicKeyHex;

- (void)updateGroupPeerLastSeenDateInterval:(NSTimeInterval)dateInterval forChat:(OCTChat *)chat peerId:(uint32_t)peerId;

- (NSTimeInterval)groupPeerLastSeenDateIntervalForChat:(OCTChat *)chat peerId:(uint32_t)peerId;

- (NSSet<NSString *> *)knownGroupMemberPublicKeyHexesForChat:(OCTChat *)chat;

- (NSUInteger)removeGroupSystemMessagesInChat:(OCTChat *)chat;

- (NSUInteger)groupSystemMessageCountForChat:(OCTChat *)chat;
- (void)setGroupPeerRole:(OCTToxGroupRole)peerRole peerId:(uint32_t)peerId chat:(OCTChat *)chat;
- (void)setGroupNotificationsSilent:(BOOL)silent forChat:(OCTChat *)chat;
- (void)setGroupPeerNotificationsSilent:(BOOL)silent peerId:(uint32_t)peerId chat:(OCTChat *)chat;
- (void)setGroupPeerPrivateLastReadDateInterval:(NSTimeInterval)interval peerId:(uint32_t)peerId chat:(OCTChat *)chat;
// KHANDAQ (#54): clear in-group private-message unread across the whole chat (all peer rows).
- (void)markAllGroupPrivateThreadsReadForChat:(OCTChat *)chat dateInterval:(NSTimeInterval)interval;
- (int32_t)unreadPrivateMessageCountForPeerId:(uint32_t)peerId chat:(OCTChat *)chat;
- (nullable OCTGroupPeer *)groupPeerForChat:(OCTChat *)chat peerId:(uint32_t)peerId;
- (void)removeGroupPeersForChat:(OCTChat *)chat notInPeerIds:(NSSet<NSNumber *> *)peerIds;
- (void)updateGroupPeerCount:(int32_t)peerCount forChat:(OCTChat *)chat;
- (void)updateGroupTopic:(nullable NSString *)topic forChat:(OCTChat *)chat;
- (void)updateGroupName:(nullable NSString *)groupName forChat:(OCTChat *)chat;
- (void)updateGroupPassword:(nullable NSString *)password forChat:(OCTChat *)chat;
- (void)updateGroupTopicLockEnabled:(BOOL)enabled forChat:(OCTChat *)chat;
- (void)updateGroupPeerLimit:(int32_t)peerLimit forChat:(OCTChat *)chat;
- (void)updateGroupPrivacyState:(OCTToxGroupPrivacyState)privacyState forChat:(OCTChat *)chat;
- (void)updateGroupVoiceState:(OCTToxGroupVoiceState)voiceState forChat:(OCTChat *)chat;

- (OCTMessageAbstract *)addGroupPrivateMessageWithText:(NSString *)text
                                                  type:(OCTToxMessageType)type
                                                  chat:(OCTChat *)chat
                                                peerId:(uint32_t)peerId
                                              peerName:(nullable NSString *)peerName
                                         counterpartyId:(uint32_t)counterpartyId
                                    counterpartyPubkey:(NSString *)counterpartyPubkey
                                             messageId:(OCTToxMessageId)messageId
                                              isOutgoing:(BOOL)isOutgoing;

- (void)recordGroupSyncConfirmationForOutgoingTextMessageId:(uint32_t)messageId
                                           ackerPubkeyHex:(NSString *)ackerPubkeyHex
                                                   inChat:(OCTChat *)chat;

- (void)recordGroupSyncConfirmationForOutgoingFileWithHashHex:(NSString *)msgIdHashHex
                                               ackerPubkeyHex:(NSString *)ackerPubkeyHex
                                                       inChat:(OCTChat *)chat;

- (OCTMessageAbstract *)addMessageWithFileNumber:(OCTToxFileNumber)fileNumber
                                        fileType:(OCTMessageFileType)fileType
                                        fileSize:(OCTToxFileSize)fileSize
                                        fileName:(NSString *)fileName
                                        filePath:(NSString *)filePath
                                         fileUTI:(NSString *)fileUTI
                                            chat:(OCTChat *)chat
                                          sender:(OCTFriend *)sender;

- (OCTMessageAbstract *)addMessageCall:(OCTCall *)call;

@end
