//
//  Use this file to import your target's public headers that you would like to expose to Swift.
//

#import <objcTox/OCTCall.h>
#import <objcTox/OCTChat.h>
#import <objcTox/OCTDefaultFileStorage.h>
#import <objcTox/OCTFriend.h>
#import <objcTox/OCTFriendRequest.h>
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
@end

// Redesign stage 3, item 10: the receive paths of the KQ packets (1:1 186, 187, 188 and group 0x41,
// 0x42, 0x43), declared here so the tests can hand them the bytes Android sends. objcTox is unchanged.
void friendLosslessPacketCallback(void *cTox, uint32_t friendNumber, const uint8_t *data, size_t length, void *userData);

@interface OCTSubmanagerGroupsImpl : NSObject
- (BOOL)handleIncomingGroupEditPacketWithGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId data:(NSData *)data;
- (BOOL)handleIncomingGroupDeletePacketWithGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId data:(NSData *)data;
- (BOOL)handleIncomingGroupReactionPacketWithGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId data:(NSData *)data;
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
