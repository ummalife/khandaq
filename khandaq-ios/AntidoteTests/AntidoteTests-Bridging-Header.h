//
//  Use this file to import your target's public headers that you would like to expose to Swift.
//

#import <objcTox/OCTCall.h>
#import <objcTox/OCTChat.h>
#import <objcTox/OCTDefaultFileStorage.h>
#import <objcTox/OCTFriend.h>
#import <objcTox/OCTFriendRequest.h>
#import <objcTox/OCTGroupContactExchange.h>
#import <objcTox/OCTManager.h>
#import <objcTox/OCTManagerFactory.h>
#import <objcTox/OCTManagerConfiguration.h>
#import <objcTox/OCTManagerConstants.h>
#import <objcTox/OCTMessageAbstract.h>
#import <objcTox/OCTMessageCall.h>
#import <objcTox/OCTMessageFile.h>
#import <objcTox/OCTMessageText.h>
#import <objcTox/OCTSubmanagerBootstrap.h>
#import <objcTox/OCTSubmanagerCalls.h>
#import <objcTox/OCTSubmanagerCallsDelegate.h>
#import <objcTox/OCTSubmanagerChats.h>
#import <objcTox/OCTSubmanagerFiles.h>
#import <objcTox/OCTSubmanagerFilesProgressSubscriber.h>
#import <objcTox/OCTSubmanagerFriends.h>
#import <objcTox/OCTSubmanagerGroups.h>
#import <objcTox/OCTSubmanagerObjects.h>
#import <objcTox/OCTSubmanagerUser.h>
#import <objcTox/OCTTox.h>
#import <objcTox/OCTToxEncryptSave.h>
#import <objcTox/OCTToxConstants.h>
#import <objcTox/OCTView.h>
#import <objcTox/OCTPushPreview.h>

// The order of a 1:1 chat (QA-Live 14.11): objcTox's realm manager, declared here so the tests can keep
// a chat in a Realm of their own, store a file message as sending a file does, and read the chat in the
// order the app shows it. objcTox is unchanged by these declarations.
@interface OCTRealmManager : NSObject
- (instancetype)initWithDatabaseFileURL:(NSURL *)fileURL encryptionKey:(NSData *)encryptionKey;
- (void)addObject:(OCTObject *)object;
- (OCTChat *)getOrCreateChatWithFriend:(OCTFriend *)friend;
- (RLMResults *)objectsWithClass:(Class)class predicate:(NSPredicate *)predicate;
- (OCTMessageAbstract *)addMessageWithFileNumber:(OCTToxFileNumber)fileNumber
                                        fileType:(OCTMessageFileType)fileType
                                        fileSize:(OCTToxFileSize)fileSize
                                        fileName:(NSString *)fileName
                                        filePath:(NSString *)filePath
                                         fileUTI:(NSString *)fileUTI
                                            chat:(OCTChat *)chat
                                          sender:(OCTFriend *)sender;
// QA live F11 (deferred leave): the profile's key-value rows, which keep the chat ids of the pending leaves.
- (NSString *)ngcValueForKey:(NSString *)key;
- (BOOL)setNgcValue:(NSString *)value forKey:(NSString *)key;
// Who can join a group (new groups public by default): the Realm the dossier watches, written as the privacy
// callback of toxcore writes it.
@property (strong, nonatomic, readonly) RLMRealm *realm;
// The group settings and the dossier while objcTox stores what toxcore holds of a group: its writes, made
// through the queue of the realm manager.
- (void)updateGroupPrivacyState:(OCTToxGroupPrivacyState)privacyState forChat:(OCTChat *)chat;
- (void)updateGroupVoiceState:(OCTToxGroupVoiceState)voiceState forChat:(OCTChat *)chat;
// 26.11.2026 (DESIGN-group-contacts-private-mentions.md): the two queries the group history sync serves from, which
// must never hand out a private message.
- (NSArray<OCTMessageAbstract *> *)groupMessagesForHistorySyncInChat:(OCTChat *)chat;
- (NSArray<NSData *> *)groupHistorySyncPacketsForGroupNumber:(uint32_t)groupNumber
                                         packetFromMessage:(NSData *(^)(OCTMessageAbstract *message))packetBlock;
@end

// 26.11.2026 (DESIGN-group-contacts-private-mentions.md): the builder of the group history sync's packets, which
// refuses a private message as the queries above do. objcTox is unchanged by these declarations.
@interface OCTNgcGroupHistSync : NSObject
- (instancetype)initWithSendPrivatePacketBlock:(BOOL (^)(uint32_t groupNumber, uint32_t peerId, NSData *packet, NSError **error))sendPrivatePacketBlock
                            peerPublicKeyBlock:(NSString *(^)(uint32_t groupNumber, uint32_t peerId))peerPublicKeyBlock
                        peerNameForPeerIdBlock:(NSString *(^)(uint32_t groupNumber, uint32_t peerId))peerNameForPeerIdBlock
                          defaultPeerNameBlock:(NSString *(^)(void))defaultPeerNameBlock
                            selfPublicKeyBlock:(NSString *(^)(uint32_t groupNumber))selfPublicKeyBlock
                       peerIdForPublicKeyBlock:(uint32_t (^)(uint32_t groupNumber, NSString *publicKeyHex))peerIdForPublicKeyBlock
                            isPublicGroupBlock:(BOOL (^)(uint32_t groupNumber))isPublicGroupBlock
                               selfPeerIdBlock:(uint32_t (^)(uint32_t groupNumber))selfPeerIdBlock
                                  peerIdsBlock:(NSArray<NSNumber *> *(^)(uint32_t groupNumber))peerIdsBlock
                             chatForGroupBlock:(OCTChat *(^)(uint32_t groupNumber, BOOL createIfNeeded))chatForGroupBlock
                           messagesToSyncBlock:(NSArray<OCTMessageAbstract *> *(^)(OCTChat *chat))messagesToSyncBlock
                            messageExistsBlock:(BOOL (^)(OCTChat *chat, uint32_t messageId, uint32_t peerId, NSString *senderPubkeyHex, NSString *peerName, NSString *text, NSTimeInterval dateInterval))messageExistsBlock
                               fileExistsBlock:(BOOL (^)(OCTChat *chat, NSString *msgIdHashHex))fileExistsBlock
                      insertSyncedMessageBlock:(OCTMessageAbstract *(^)(OCTChat *chat, NSString *text, OCTToxMessageType type, uint32_t peerId, NSString *peerName, NSString *senderPubkeyHex, uint32_t messageId, NSTimeInterval dateInterval))insertSyncedMessageBlock
                         insertSyncedFileBlock:(OCTMessageAbstract *(^)(OCTChat *chat, NSString *fileName, NSString *filePath, uint64_t fileSize, NSString *fileUTI, uint32_t peerId, NSString *peerName, NSString *senderPubkeyHex, NSString *msgIdHashHex, NSTimeInterval dateInterval))insertSyncedFileBlock
                   incomingFilesDirectoryBlock:(NSString *(^)(uint32_t groupNumber))incomingFilesDirectoryBlock
                                  fileUTIBlock:(NSString *(^)(NSString *fileName))fileUTIBlock
                            isBlockedPeerBlock:(BOOL (^)(uint32_t groupNumber, uint32_t peerId))isBlockedPeerBlock
                          deliveryReceiptBlock:(void (^)(OCTChat *chat, uint32_t messageId, uint32_t peerId))deliveryReceiptBlock
                     fileSyncConfirmationBlock:(void (^)(OCTChat *chat, NSString *msgIdHashHex, uint32_t syncerPeerId))fileSyncConfirmationBlock;
- (NSData *)buildSyncPacketForMessage:(OCTMessageAbstract *)message groupNumber:(uint32_t)groupNumber
    NS_SWIFT_NAME(syncPacket(for:groupNumber:));
@end

// 26.11.2026 (DESIGN-group-contacts-private-mentions.md): the offers of the member-to-contact exchange, which the
// accept over the group and the contact request with the offer's nonce each take, only one of them. objcTox is
// unchanged by these declarations.
@interface OCTGroupContactPendingOffer : NSObject
@property (nonatomic, copy, readonly) NSString *memberPublicKeyHex;
@property (nonatomic, copy, readonly) NSString *nonceHex;
- (instancetype)initWithGroupChatIdHex:(NSString *)groupChatIdHex
                    memberPublicKeyHex:(NSString *)memberPublicKeyHex
                            memberName:(NSString *)memberName
                              nonceHex:(NSString *)nonceHex
                                sentAt:(NSTimeInterval)sentAt;
@end

@interface OCTGroupContactStore : NSObject
- (instancetype)initWithRealmManager:(OCTRealmManager *)realmManager;
- (void)savePendingOffer:(OCTGroupContactPendingOffer *)offer;
- (OCTGroupContactPendingOffer *)takePendingOfferToMember:(NSString *)memberPublicKeyHex
                                                  inGroup:(NSString *)groupChatIdHex
                                                 nonceHex:(NSString *)nonceHex
                                                       at:(NSTimeInterval)now;
- (OCTGroupContactPendingOffer *)takePendingOfferWithNonceHex:(NSString *)nonceHex at:(NSTimeInterval)now;
@end

// Redesign stage 3, item 10: the receive paths of the KQ packets (1:1 186, 187, 188 and group 0x41,
// 0x42, 0x43), declared here so the tests can hand them the bytes Android sends. objcTox is unchanged.
void friendLosslessPacketCallback(void *cTox, uint32_t friendNumber, const uint8_t *data, size_t length, void *userData);

// QA live F11 (deferred leave): the leaves that wait for the leave notice's resends, declared here so the tests can
// keep their books and run the glue that leaves them on a Tox made without the network. The arrays of leaves that
// objcTox returns are untyped: objcTox is in the app and in this bundle ("implemented in both"), and an array of its
// leaves does not bridge to a Swift array of the class, so the tests read them by key.
@interface OCTGroupPendingLeave : NSObject
@property (nonatomic, assign, readonly) OCTToxGroupNumber groupNumber;
@property (nonatomic, copy, readonly) NSString *chatIdHex;
@property (nonatomic, copy, readonly) NSString *partMessage;
@property (nonatomic, assign, readonly) NSTimeInterval dueAt;
- (instancetype)initWithGroupNumber:(OCTToxGroupNumber)groupNumber chatIdHex:(NSString *)chatIdHex partMessage:(NSString *)partMessage dueAt:(NSTimeInterval)dueAt;
@end

@interface OCTGroupPendingLeaves : NSObject
+ (NSTimeInterval)delay;
+ (BOOL)leaveWaitsForNoticeSent:(BOOL)noticeSent confirmedPeerCount:(uint32_t)confirmedPeerCount;
+ (NSArray<NSString *> *)chatIdHexesFromSerialized:(NSString *)serialized;
@property (nonatomic, assign, readonly) NSUInteger count;
- (OCTGroupPendingLeave *)recordLeaveOfGroupNumber:(OCTToxGroupNumber)groupNumber chatIdHex:(NSString *)chatIdHex partMessage:(NSString *)partMessage at:(NSTimeInterval)now;
- (BOOL)removeEntry:(OCTGroupPendingLeave *)entry;
- (BOOL)containsGroupNumber:(OCTToxGroupNumber)groupNumber;
- (NSArray *)entriesForChatIdHex:(NSString *)chatIdHex;
- (NSArray *)dueEntriesAt:(NSTimeInterval)now;
- (NSArray *)allEntries;
- (NSTimeInterval)nextDueAt;
- (NSString *)serializedChatIds;
@end

@interface OCTSubmanagerGroupsImpl : NSObject
- (BOOL)handleIncomingGroupEditPacketWithGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId data:(NSData *)data;
- (BOOL)handleIncomingGroupDeletePacketWithGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId data:(NSData *)data;
- (BOOL)handleIncomingGroupReactionPacketWithGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId data:(NSData *)data;
// 0.2.63: the reaction packet on a group file names the file's author (nil or not 64 hex digits: zero).
- (NSData *)kqGroupReactionPacketForMsgIdHash:(NSString *)msgIdHashHex authorHex:(NSString *)authorHex emoji:(NSString *)emoji add:(BOOL)add;
// QA live F11: the leave notice that tells a member who leaves from an app that closes.
+ (NSData *)groupLeaveNoticePacket;
+ (BOOL)isGroupLeaveNoticePacket:(NSData *)data;
+ (BOOL)groupExitEndsMembership:(OCTToxGroupExitType)exitType saidItWasLeaving:(BOOL)saidItWasLeaving;
+ (NSString *)groupExitSystemMessageFormatKey:(OCTToxGroupExitType)exitType saidItWasLeaving:(BOOL)saidItWasLeaving;
- (BOOL)noteGroupLeaveNoticeForChatKey:(NSString *)chatKey peerId:(uint32_t)peerId publicKeyHex:(NSString *)publicKeyHex at:(NSTimeInterval)now;
- (BOOL)takeGroupLeaveNoticeForChatKey:(NSString *)chatKey peerId:(uint32_t)peerId at:(NSTimeInterval)now publicKeyHex:(NSString **)publicKeyHex;
- (void)forgetGroupLeaveNoticeForChatKey:(NSString *)chatKey peerId:(uint32_t)peerId;
// QA live F11: a member that said it is leaving is out of the members at once, until its exit or the window.
- (BOOL)isGroupPeerLeavingForChatKey:(NSString *)chatKey peerId:(uint32_t)peerId publicKeyHex:(NSString *)publicKeyHex at:(NSTimeInterval)now;
- (BOOL)hasGroupLeaveNoticesForChatKey:(NSString *)chatKey;
- (void)forgetGroupLeaveNoticesForChatKey:(NSString *)chatKey publicKeyHex:(NSString *)publicKeyHex;
// QA live F11 (deferred leave): the glue around OCTGroupPendingLeaves.
- (BOOL)leaveGroupWithNumber:(OCTToxGroupNumber)groupNumber partMessage:(NSString *)partMessage error:(NSError **)error;
@property (nonatomic, strong, readonly) OCTGroupPendingLeaves *pendingGroupLeaves;
- (BOOL)isGroupNumberBeingLeft:(OCTToxGroupNumber)groupNumber;
- (NSUInteger)leaveGroupsOfPendingLeaves:(NSArray<OCTGroupPendingLeave *> *)entries event:(NSString *)event onTox:(OCTTox *)tox;
- (NSArray *)leavePendingGroupsBeforeRejoinOfChatIdHex:(NSString *)chatIdHex onTox:(OCTTox *)tox;
- (OCTToxGroupNumber)groupNumberInToxForChatIdHex:(NSString *)chatIdHex onTox:(OCTTox *)tox;
- (void)recordPendingGroupLeaveOfGroupNumber:(OCTToxGroupNumber)groupNumber chatIdHex:(NSString *)chatIdHex partMessage:(NSString *)partMessage;
- (void)leaveGroupsOfStoredPendingLeaves;
- (void)leavePendingGroupsNow;
@end

// KHANDAQ (msgV3 receipt shown as "_"): the 1:1 message receive path and the msgV3 receipt sender,
// declared here so MsgV3ReceiptTests can check which message gets a receipt. cType is a
// TOX_MESSAGE_TYPE (0 normal, 1 action, 2 receipt).
void friendMessageCallback(void *cTox, uint32_t friendNumber, uint32_t cType, const uint8_t *cMessage, size_t length, void *userData);

@interface OCTSubmanagerChatsImpl : NSObject
@property (strong, nonatomic, readonly) NSOperationQueue *sendMessageQueue;
- (void)tox:(OCTTox *)tox sendFriendHighlevelACK:(NSString *)message friendNumber:(OCTToxFriendNumber)friendNumber msgv3HashHex:(NSString *)msgv3HashHex sendTimestamp:(uint32_t)sendTimestamp;
@end

#undef LOG_INFO
#undef LOG_DEBUG
#import "DDLog.h"
#import "DDASLLogger.h"
#import "DDTTYLogger.h"

#import <LNNotificationsUI/LNNotificationsUI.h>
#import <SDCAlertView/SDCAlertController.h>
#import <UITextView+Placeholder/UITextView+Placeholder.h>
#import <JGProgressHUD/JGProgressHUD.h>

#import "ExceptionHandling.h"

#import "FBSnapshotTestCase.h"
