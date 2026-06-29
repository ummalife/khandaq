// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>
#import <UIKit/UIKit.h>

#import "OCTToxConstants.h"

@class OCTChat;
@class OCTMessageAbstract;
@class RLMResults;

FOUNDATION_EXPORT NSNotificationName const kOCTGroupConnectionStatusChangeNotification;
FOUNDATION_EXPORT NSString *const kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey;
FOUNDATION_EXPORT NSNotificationName const kOCTGroupPeersUpdatedNotification;
FOUNDATION_EXPORT NSString *const kOCTGroupPeersUpdatedChatUniqueIdentifierKey;
FOUNDATION_EXPORT NSNotificationName const kOCTGroupLiveVideoActivityNotification;
FOUNDATION_EXPORT NSString *const kOCTGroupLiveVideoActivityGroupNumberKey;

typedef NS_ENUM(NSInteger, OCTGroupLiveVideoIconState) {
    OCTGroupLiveVideoIconStateInactive = 0,
    OCTGroupLiveVideoIconStateIncoming = 1,
    OCTGroupLiveVideoIconStateActive = 2,
};

@protocol OCTSubmanagerGroups;
@protocol OCTSubmanagerGroupsDelegate <NSObject>
- (void)submanagerGroups:(nonnull id<OCTSubmanagerGroups>)submanager
didReceiveInviteFromFriendNumber:(OCTToxFriendNumber)friendNumber
              inviteData:(nonnull NSData *)inviteData
               groupName:(nullable NSString *)groupName;

- (void)submanagerGroups:(nonnull id<OCTSubmanagerGroups>)submanager
          groupJoinDidFail:(OCTToxGroupJoinFail)failType
                   forChat:(nonnull OCTChat *)chat;
@end

@protocol OCTSubmanagerGroups <NSObject>

@property (weak, nonatomic, nullable) id<OCTSubmanagerGroupsDelegate> delegate;

- (OCTToxGroupNumber)createPublicGroupWithName:(NSString *)groupName
                                      peerName:(NSString *)peerName
                                         error:(NSError **)error;

- (void)createPublicGroupWithName:(NSString *)groupName
                         peerName:(NSString *)peerName
                       completion:(void (^)(OCTToxGroupNumber groupNumber, NSError *_Nullable error))completion;

- (OCTToxGroupNumber)createPrivateGroupWithName:(NSString *)groupName
                                       peerName:(NSString *)peerName
                                          error:(NSError **)error;

- (void)createPrivateGroupWithName:(NSString *)groupName
                          peerName:(NSString *)peerName
                        completion:(void (^)(OCTToxGroupNumber groupNumber, NSError *_Nullable error))completion;

- (OCTToxGroupNumber)joinGroupWithChatIdHex:(NSString *)chatIdHex
                                   peerName:(NSString *)peerName
                                   password:(nullable NSString *)password
                                      error:(NSError **)error;

- (BOOL)leaveGroupWithNumber:(OCTToxGroupNumber)groupNumber
                partMessage:(nullable NSString *)partMessage
                      error:(NSError **)error;

- (BOOL)sendMessage:(NSString *)message
               type:(OCTToxMessageType)type
        groupNumber:(OCTToxGroupNumber)groupNumber
          messageId:(nullable uint32_t *)messageId
              error:(NSError **)error;

- (nullable NSString *)chatIdHexForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error;

- (nullable NSString *)chatIdHexForChat:(OCTChat *)chat error:(NSError **)error;

- (nullable OCTChat *)chatForGroupNumber:(OCTToxGroupNumber)groupNumber;

- (int32_t)peerCountForChat:(OCTChat *)chat;

// KHANDAQ (#9): saved members currently offline. Total real members = peerCountForChat + this.
- (int32_t)offlinePeerCountForChat:(OCTChat *)chat;

- (RLMResults *)peersForChat:(OCTChat *)chat;

- (void)refreshPeersForChat:(OCTChat *)chat;

// KHANDAQ (#25): force an immediate reconnect of a stalled group when its chat is opened.
- (void)foregroundReconnectForChat:(OCTChat *)chat;

- (void)prepareGroupLiveMediaMonitoringForChat:(OCTChat *)chat;

- (void)syncGroupsWithTox;

- (void)setGroupBackgroundWorkPaused:(BOOL)paused;

- (nullable OCTChat *)acceptGroupInviteFromFriendNumber:(OCTToxFriendNumber)friendNumber
                                             inviteData:(NSData *)inviteData
                                              groupName:(nullable NSString *)groupName
                                               password:(nullable NSString *)password
                                                  error:(NSError **)error;

- (BOOL)inviteFriendWithNumber:(OCTToxFriendNumber)friendNumber
                 toGroupNumber:(OCTToxGroupNumber)groupNumber
                         error:(NSError **)error;

- (void)sendMessageToChat:(OCTChat *)chat
                     text:(NSString *)text
                     type:(OCTToxMessageType)type
             successBlock:(void (^)(OCTMessageAbstract *message))successBlock
             failureBlock:(void (^)(NSError *error))failureBlock;

- (void)sendFileAtPath:(NSString *)filePath
                toChat:(OCTChat *)chat
          moveToUploads:(BOOL)moveToUploads
          successBlock:(void (^)(OCTMessageAbstract *message))successBlock
          failureBlock:(void (^)(NSError *error))failureBlock;

- (void)removeAllMessagesInChat:(OCTChat *)chat removeChat:(BOOL)removeChat leaveGroup:(BOOL)leaveGroup;

- (NSUInteger)removeGroupSystemMessagesInChat:(OCTChat *)chat;

- (NSUInteger)groupSystemMessageCountForChat:(OCTChat *)chat;

- (BOOL)isGroupPeerOnlineWithId:(uint32_t)peerId inChat:(OCTChat *)chat;

- (NSTimeInterval)groupPeerLastSeenDateIntervalForPeerId:(uint32_t)peerId inChat:(OCTChat *)chat;

- (int32_t)onlineGroupPeerCountForChat:(OCTChat *)chat;

- (nullable NSString *)groupSelfPeerNameForChat:(OCTChat *)chat error:(NSError **)error;

- (uint32_t)groupSelfPeerIdForChat:(OCTChat *)chat;

- (BOOL)setGroupSelfPeerName:(NSString *)name forChat:(OCTChat *)chat error:(NSError **)error;

- (BOOL)cancelGroupFileTransferForMessage:(OCTMessageAbstract *)message error:(NSError **)error;

- (void)setGroupNotificationsSilent:(BOOL)silent forChat:(OCTChat *)chat;

- (void)setPeerNotificationsSilent:(BOOL)silent peerId:(uint32_t)peerId forChat:(OCTChat *)chat;

- (BOOL)canKickPeerWithId:(uint32_t)peerId inChat:(OCTChat *)chat;

- (BOOL)kickPeerWithId:(uint32_t)peerId inChat:(OCTChat *)chat error:(NSError **)error;

- (OCTToxGroupRole)peerRoleWithId:(uint32_t)peerId inChat:(OCTChat *)chat;

- (BOOL)canSetPeerRole:(OCTToxGroupRole)role peerId:(uint32_t)peerId inChat:(OCTChat *)chat;

- (BOOL)setPeerRole:(OCTToxGroupRole)role peerId:(uint32_t)peerId inChat:(OCTChat *)chat error:(NSError **)error;

- (void)refreshGroupMetadataForChat:(OCTChat *)chat;

- (OCTToxGroupRole)selfRoleInChat:(OCTChat *)chat;

- (nullable NSString *)groupTopicForChat:(OCTChat *)chat error:(NSError **)error;

- (BOOL)setGroupTopic:(NSString *)topic forChat:(OCTChat *)chat error:(NSError **)error;

- (nullable NSString *)groupPasswordForChat:(OCTChat *)chat error:(NSError **)error;

- (BOOL)setGroupPassword:(nullable NSString *)password forChat:(OCTChat *)chat error:(NSError **)error;

- (OCTToxGroupTopicLock)groupTopicLockForChat:(OCTChat *)chat error:(NSError **)error;

- (BOOL)setGroupTopicLock:(OCTToxGroupTopicLock)topicLock forChat:(OCTChat *)chat error:(NSError **)error;

- (int32_t)groupPeerLimitForChat:(OCTChat *)chat error:(NSError **)error;

- (BOOL)setGroupPeerLimit:(int32_t)peerLimit forChat:(OCTChat *)chat error:(NSError **)error;

- (BOOL)canEditGroupTopicInChat:(OCTChat *)chat;

- (void)setPrivateLastReadDateInterval:(NSTimeInterval)interval peerId:(uint32_t)peerId forChat:(OCTChat *)chat;

// KHANDAQ (#54): clear the in-group private-message unread count for the whole chat (all peer rows).
- (void)markAllPrivateThreadsReadForChat:(OCTChat *)chat;

- (int32_t)unreadPrivateMessageCountForPeerId:(uint32_t)peerId inChat:(OCTChat *)chat;

- (int32_t)totalUnreadPrivateMessageCountForChat:(OCTChat *)chat;

- (BOOL)isGroupAtPeerCapacityForChat:(OCTChat *)chat;

- (BOOL)isGroupConnectedForChat:(OCTChat *)chat;

- (NSInteger)groupJoinAttemptForChat:(OCTChat *)chat;

- (BOOL)isGroupJoinRetryRunningForChat:(OCTChat *)chat;

- (OCTToxGroupPrivacyState)groupPrivacyStateForChat:(OCTChat *)chat error:(NSError **)error;

- (BOOL)setGroupPrivacyState:(OCTToxGroupPrivacyState)privacyState forChat:(OCTChat *)chat error:(NSError **)error;

- (OCTToxGroupVoiceState)groupVoiceStateForChat:(OCTChat *)chat error:(NSError **)error;

- (BOOL)setGroupVoiceState:(OCTToxGroupVoiceState)voiceState forChat:(OCTChat *)chat error:(NSError **)error;

- (BOOL)startGroupLiveAudioForChat:(OCTChat *)chat error:(NSError **)error;

- (void)stopGroupLiveAudio;

- (BOOL)isGroupLiveAudioActive;

- (BOOL)startGroupLiveVideoForChat:(OCTChat *)chat
                  remoteFrameBlock:(void (^ _Nullable)(UIImage * _Nullable frame))remoteFrameBlock
                   localFrameBlock:(void (^ _Nullable)(UIImage * _Nullable frame))localFrameBlock
                             error:(NSError **)error;

- (void)stopGroupLiveVideo;

- (BOOL)isGroupLiveVideoActive;

- (void)setGroupLiveVideoHighQuality:(BOOL)highQuality forChat:(OCTChat *)chat;

- (BOOL)isGroupLiveVideoHighQuality;

- (OCTGroupLiveVideoIconState)groupLiveVideoIconStateForChat:(OCTChat *)chat;

- (NSUInteger)recentIncomingGroupLiveVideoPeerCountForChat:(OCTChat *)chat;

- (nullable NSString *)primaryRecentIncomingGroupLiveVideoPeerNameForChat:(OCTChat *)chat;

- (void)sendPrivateMessage:(NSString *)text
                  toPeerId:(uint32_t)peerId
                    inChat:(OCTChat *)chat
              successBlock:(void (^)(OCTMessageAbstract *message))successBlock
              failureBlock:(void (^)(NSError *error))failureBlock;

/**
 * Flush queued group text/file messages for all connected groups.
 */
- (void)flushAllPendingGroupMessagesIfNeeded;

@end
