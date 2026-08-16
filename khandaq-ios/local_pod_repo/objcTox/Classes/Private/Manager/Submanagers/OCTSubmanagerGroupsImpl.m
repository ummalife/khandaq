// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerGroupsImpl.h"
#import "OCTTox.h"
#import "OCTTox+Private.h"
#import "OCTLogging.h"
#import "OCTRealmManager.h"
#import "OCTChat.h"
#import "OCTGroupPeer.h"
#import "OCTFriend.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageText.h"
#import "OCTMessageFile.h"
#import "OCTSubmanagerDataSource.h"
#import "OCTFileStorageProtocol.h"
#import "OCTFileTools.h"
#import "OCTNgcGroupFileTransfer.h"
#import "OCTNgcGroupHistSync.h"
#import "OCTNgcHskAnnounce.h"
#import "OCTNgcHskStore.h"
#import "OCTNgcSignedHistory.h"
#import "OCTNgcGroupLiveAudio.h"
#import "OCTNgcGroupLiveVideo.h"
#import "OCTSettingsStorageObject.h"
#import <toxcore/tox.h>

#if TARGET_OS_IPHONE
@import MobileCoreServices;
#endif

static NSString *const kOCTDefaultGroupPeerName = @"Khandaq";
static const uint64_t kOCTNgcMaxFileTransferBytes = 200ULL * 1024ULL * 1024ULL;
static const uint8_t kOCTLosslessPktGroupInviteRequest = 184;
static const uint8_t kOCTGroupInviteRequestVersion = 1;
static const NSTimeInterval kOCTGroupFriendFallbackAfterSec = 10.0;
static const NSTimeInterval kOCTGroupInviteRequestResendSec = 30.0;
static const NSTimeInterval kOCTGroupInviteReplyMinIntervalSec = 60.0;
static const NSTimeInterval kOCTGroupInviteRequestTTLSec = 600.0;
static const NSTimeInterval kOCTGroupMaintenanceInviteIntervalSec = 15.0;
static const NSTimeInterval kOCTGroupReconnectSuppressSec = 300.0;
// KHANDAQ (#25): opening a group chat forces an immediate reconnect of a stalled group instead of
// waiting up to 90s for the maintenance tick. Rate-limited so rapid open/close can't hammer.
static const NSTimeInterval kOCTGroupForegroundReconnectMinIntervalSec = 5.0;

NSString *const kOCTSubmanagerGroupsErrorDomain = @"OCTSubmanagerGroupsErrorDomain";

typedef NS_ENUM(NSInteger, OCTSubmanagerGroupsError) {
    OCTSubmanagerGroupsErrorObserverCannotSend = 1,
};

NSNotificationName const kOCTGroupConnectionStatusChangeNotification = @"kOCTGroupConnectionStatusChangeNotification";
NSString *const kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey = @"chatUniqueIdentifier";
NSNotificationName const kOCTGroupPeersUpdatedNotification = @"kOCTGroupPeersUpdatedNotification";
NSString *const kOCTGroupPeersUpdatedChatUniqueIdentifierKey = @"chatUniqueIdentifier";
NSNotificationName const kOCTGroupLiveVideoActivityNotification = @"kOCTGroupLiveVideoActivityNotification";
NSString *const kOCTGroupLiveVideoActivityGroupNumberKey = @"groupNumber";

@interface OCTSubmanagerGroupsImpl ()
@property (nonatomic, assign) BOOL networkObserverRegistered;
@property (nonatomic, assign) BOOL pendingGroupsSync;
@property (nonatomic, assign) BOOL groupsSyncInProgress;
@property (nonatomic, assign) BOOL groupBackgroundWorkPaused;
@property (nonatomic, strong) OCTNgcGroupFileTransfer *fileTransfer;
@property (nonatomic, strong) OCTNgcGroupLiveAudio *liveAudio;
@property (nonatomic, strong) OCTNgcGroupLiveVideo *liveVideo;
@property (nonatomic, strong) OCTNgcGroupHistSync *histSync;
@property (nonatomic, strong) OCTNgcHskAnnounce *hskAnnounce;
@property (nonatomic, strong) OCTNgcSignedHistory *signedHistory;
@property (nonatomic, strong) NSMutableSet<NSString *> *groupJoinRetryRunning;
@property (nonatomic, strong) NSMutableSet<NSString *> *groupJoinRetryCancelled;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *groupJoinAttemptByKey;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *pendingFriendAssistedJoins;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *groupAloneSinceMs;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *groupConnectStartedMs;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *lastInviteRequestMs;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *lastInviteReplyMs;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *groupLastInviteRequestMs;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *groupPeerReconnectSuppressUntil;
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *groupLastForegroundReconnectMs;
// KHANDAQ (#15): recent incoming group-message keys (group:peer:text -> last-seen timestamp) used to
// drop sender-retry re-deliveries. In-memory so it does not depend on cross-thread Realm reads.
@property (nonatomic, strong) NSMutableDictionary<NSString *, NSNumber *> *recentGroupMessageSeenAt;
@property (nonatomic) dispatch_source_t groupsMaintenanceTimer;
// KHANDAQ: pubkeys we've already announced as "joined" (group:pubkey), so a peer that merely
// reconnects / re-appears on our own reconnect doesn't spam another "X joined" notice every time.
@property (nonatomic, strong) NSMutableSet<NSString *> *groupAnnouncedJoinPubkeys;
// KHANDAQ: stable pubkey-based group peer name resolver (declared so the receive callbacks above can
// call it before its definition further down).
- (nullable NSString *)groupPeerNameByPubkeyForGroupNumber:(OCTToxGroupNumber)groupNumber
                                                    peerId:(uint32_t)peerId
                                                      chat:(OCTChat *)chat;
// KHANDAQ (audit, round 5): stable author key frozen on an incoming group TEXT row (same reason as
// above — the receive callback calls it before its definition further down).
- (void)freezeGroupTextSenderPubkeyOnMessage:(OCTMessageAbstract *)message
                            senderPublicKeyHex:(NSString *)senderPublicKeyHex
                                   groupNumber:(OCTToxGroupNumber)groupNumber
                                        peerId:(uint32_t)peerId
                                          chat:(OCTChat *)chat;
@end

@implementation OCTSubmanagerGroupsImpl
@synthesize dataSource = _dataSource;
@synthesize delegate = _delegate;

// KHANDAQ (leak): the 90s groupsMaintenanceTimer dispatch source was created + resumed but never
// cancelled — the class had no dealloc, so it leaked on teardown. The event handler captures weakSelf
// (no retain cycle), so a plain dealloc runs; cancel the always-resumed source here (safe to cancel a
// resumed source; never suspended, so no "release of suspended object" crash).
- (void)dealloc
{
    if (_groupsMaintenanceTimer) {
        dispatch_source_cancel(_groupsMaintenanceTimer);
        _groupsMaintenanceTimer = nil;
    }
}

#pragma mark - Tox queue helpers

- (OCTTox *)toxInstance
{
    return [self.dataSource managerGetTox];
}

- (void)performSyncOnToxQueue:(void (^)(OCTTox *tox))block
{
    [[self toxInstance] performSyncBlockOnToxQueue:^{
        block([self toxInstance]);
    }];
}

- (NSString *)normalizedGroupChatIdHexString:(NSString *)chatIdHex
{
    if (chatIdHex.length == 0) {
        return nil;
    }

    NSCharacterSet *hexSet = [NSCharacterSet characterSetWithCharactersInString:@"0123456789ABCDEFabcdef"];
    NSMutableString *hex = [NSMutableString stringWithCapacity:64];

    for (NSUInteger i = 0; i < chatIdHex.length; i++) {
        unichar c = [chatIdHex characterAtIndex:i];

        if ([hexSet characterIsMember:c]) {
            [hex appendFormat:@"%C", c];
        }
    }

    if (hex.length != 64) {
        return nil;
    }

    return hex.uppercaseString;
}

- (OCTToxGroupNumber)groupNumberInToxForChatIdHex:(NSString *)chatIdHex onTox:(OCTTox *)tox
{
    NSString *normalized = [self normalizedGroupChatIdHexString:chatIdHex];

    if (normalized.length != 64 || ! tox) {
        return kOCTToxGroupNumberFailure;
    }

    for (NSNumber *number in [tox groupNumbers]) {
        OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)number.unsignedIntValue;
        NSString *existingChatIdHex = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];

        if (existingChatIdHex.length > 0 &&
            [existingChatIdHex caseInsensitiveCompare:normalized] == NSOrderedSame) {
            return groupNumber;
        }
    }

    return kOCTToxGroupNumberFailure;
}

#pragma mark - OCTSubmanagerProtocol

- (void)configure
{
    if (self.networkObserverRegistered) {
        return;
    }

    self.groupJoinRetryRunning = [NSMutableSet set];
    self.groupJoinRetryCancelled = [NSMutableSet set];
    self.groupJoinAttemptByKey = [NSMutableDictionary dictionary];
    self.pendingFriendAssistedJoins = [NSMutableDictionary dictionary];
    self.groupAloneSinceMs = [NSMutableDictionary dictionary];
    self.groupConnectStartedMs = [NSMutableDictionary dictionary];
    self.lastInviteRequestMs = [NSMutableDictionary dictionary];
    self.lastInviteReplyMs = [NSMutableDictionary dictionary];
    self.groupLastInviteRequestMs = [NSMutableDictionary dictionary];
    self.groupPeerReconnectSuppressUntil = [NSMutableDictionary dictionary];
    self.groupLastForegroundReconnectMs = [NSMutableDictionary dictionary];
    self.groupAnnouncedJoinPubkeys = [NSMutableSet set];

    NSNotificationCenter *center = [self.dataSource managerGetNotificationCenter];

    [center addObserver:self
               selector:@selector(selfConnectionBecameOnline:)
                   name:kOCTSelfConnectionBecameOnlineNotification
                 object:nil];
    [center addObserver:self
               selector:@selector(networkRebootstrapCompleted:)
                   name:kOCTNetworkRebootstrapCompletedNotification
                 object:nil];
    [center addObserver:self
               selector:@selector(friendConnectionStatusChangeNotification:)
                   name:kOCTFriendConnectionStatusChangeNotification
                 object:nil];
    self.networkObserverRegistered = YES;

    if ([self.dataSource managerIsToxConnected]) {
        [self scheduleGroupsSyncIfNeeded];
    }

    [self setupFileTransferIfNeeded];
    [self setupHistSyncIfNeeded];
    [self startGroupsMaintenanceTimer];
}

#pragma mark - Public

- (OCTToxGroupNumber)createPublicGroupWithName:(NSString *)groupName
                                      peerName:(NSString *)peerName
                                         error:(NSError **)error
{
    OCTToxGroupNumber groupNumber = [self performGroupNewWithPrivacyState:OCTToxGroupPrivacyStatePublic
                                                                groupName:groupName
                                                                 peerName:peerName
                                                                    error:error];

    if (groupNumber == kOCTToxGroupNumberFailure) {
        return groupNumber;
    }

    [self finalizeNewGroupWithNumber:groupNumber
                           groupName:groupName
                        privacyState:OCTToxGroupPrivacyStatePublic
                      systemMessageKey:@"group_system_group_created"];
    return groupNumber;
}

- (void)createPublicGroupWithName:(NSString *)groupName
                         peerName:(NSString *)peerName
                       completion:(void (^)(OCTToxGroupNumber, NSError *))completion
{
    __weak typeof(self) weakSelf = self;

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        __strong typeof(weakSelf) self = weakSelf;

        if (! self) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (completion) {
                    completion(kOCTToxGroupNumberFailure, nil);
                }
            });
            return;
        }

        NSError *error = nil;
        OCTToxGroupNumber groupNumber = [self performGroupNewWithPrivacyState:OCTToxGroupPrivacyStatePublic
                                                                    groupName:groupName
                                                                     peerName:peerName
                                                                        error:&error];

        dispatch_async(dispatch_get_main_queue(), ^{
            if (groupNumber != kOCTToxGroupNumberFailure && ! error) {
                [self finalizeNewGroupWithNumber:groupNumber
                                       groupName:groupName
                                    privacyState:OCTToxGroupPrivacyStatePublic
                                  systemMessageKey:@"group_system_group_created"];
            }

            if (completion) {
                completion(groupNumber, error);
            }
        });
    });
}

- (OCTToxGroupNumber)createPrivateGroupWithName:(NSString *)groupName
                                       peerName:(NSString *)peerName
                                          error:(NSError **)error
{
    OCTToxGroupNumber groupNumber = [self performGroupNewWithPrivacyState:OCTToxGroupPrivacyStatePrivate
                                                                groupName:groupName
                                                                 peerName:peerName
                                                                    error:error];

    if (groupNumber == kOCTToxGroupNumberFailure) {
        return groupNumber;
    }

    [self finalizeNewGroupWithNumber:groupNumber
                           groupName:groupName
                        privacyState:OCTToxGroupPrivacyStatePrivate
                      systemMessageKey:@"group_system_group_created"];
    return groupNumber;
}

- (void)createPrivateGroupWithName:(NSString *)groupName
                          peerName:(NSString *)peerName
                        completion:(void (^)(OCTToxGroupNumber, NSError *))completion
{
    __weak typeof(self) weakSelf = self;

    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        __strong typeof(weakSelf) self = weakSelf;

        if (! self) {
            dispatch_async(dispatch_get_main_queue(), ^{
                if (completion) {
                    completion(kOCTToxGroupNumberFailure, nil);
                }
            });
            return;
        }

        NSError *error = nil;
        OCTToxGroupNumber groupNumber = [self performGroupNewWithPrivacyState:OCTToxGroupPrivacyStatePrivate
                                                                    groupName:groupName
                                                                     peerName:peerName
                                                                        error:&error];

        dispatch_async(dispatch_get_main_queue(), ^{
            if (groupNumber != kOCTToxGroupNumberFailure && ! error) {
                [self finalizeNewGroupWithNumber:groupNumber
                                       groupName:groupName
                                    privacyState:OCTToxGroupPrivacyStatePrivate
                                  systemMessageKey:@"group_system_group_created"];
            }

            if (completion) {
                completion(groupNumber, error);
            }
        });
    });
}

- (OCTToxGroupNumber)joinGroupWithChatIdHex:(NSString *)chatIdHex
                                   peerName:(NSString *)peerName
                                   password:(NSString *)password
                                      error:(NSError **)error
{
    NSString *normalizedChatIdHex = [self normalizedGroupChatIdHexString:chatIdHex];

    if (normalizedChatIdHex.length != 64) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupJoinBadChatId
                                     description:@"Cannot join group"
                                   failureReason:@"Chat ID must be exactly 64 hex characters"];
        }
        return kOCTToxGroupNumberFailure;
    }

    __block OCTToxGroupNumber groupNumber = kOCTToxGroupNumberFailure;
    __block NSError *localError = nil;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        groupNumber = [tox groupJoinWithChatIdHex:normalizedChatIdHex
                                         peerName:peerName
                                         password:password
                                            error:&localError];
    }];

    if (groupNumber == kOCTToxGroupNumberFailure) {
        if (error) {
            *error = localError;
        }

        return groupNumber;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    __block OCTToxGroupPrivacyState privacyState = OCTToxGroupPrivacyStatePublic;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        privacyState = [tox groupPrivacyStateForGroupNumber:groupNumber error:nil];
    }];

    OCTChat *chat = [realmManager getOrCreateGroupChatWithGroupNumber:groupNumber
                                                            chatIdHex:normalizedChatIdHex
                                                            groupName:nil
                                                         privacyState:privacyState];
    if (password.length > 0) {
        [realmManager updateGroupPassword:password forChat:chat];
    }

    [self bootstrapGroupConnectionForChat:chat groupNumber:groupNumber];
    [self sendGroupInviteRequestToFriendsForChatIdHex:normalizedChatIdHex];
    [self addGroupSystemMessageWithFormatKey:@"group_system_you_joined" argument:nil toChat:chat];
    return groupNumber;
}

- (BOOL)leaveGroupWithNumber:(OCTToxGroupNumber)groupNumber
                partMessage:(NSString *)partMessage
                      error:(NSError **)error
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat) {
        [self cancelGroupJoinRetryForChat:chat];
    }

    __block BOOL result = NO;
    __block NSError *localError = nil;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        result = [tox groupLeaveWithGroupNumber:groupNumber
                                    partMessage:partMessage
                                          error:&localError];
    }];

    if (result) {
        // KHANDAQ: persist immediately so a left/deleted group does not reappear after a crash or
        // relaunch (toxcore otherwise keeps it in the in-memory save until the next periodic write).
        [self.dataSource managerSaveTox];
    }

    if (error) {
        *error = localError;
    }

    return result;
}

- (BOOL)sendMessage:(NSString *)message
               type:(OCTToxMessageType)type
        groupNumber:(OCTToxGroupNumber)groupNumber
          messageId:(uint32_t *)messageId
              error:(NSError **)error
{
    __block BOOL sent = NO;
    __block uint32_t localMessageId = 0;
    __block NSError *localError = nil;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        sent = [tox groupSendMessage:message
                                type:type
                         groupNumber:groupNumber
                           messageId:&localMessageId
                               error:&localError];
    }];

    if (messageId) {
        *messageId = localMessageId;
    }

    if (error) {
        *error = localError;
    }

    return sent;
}

- (NSString *)chatIdHexForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    return [[self.dataSource managerGetTox] groupChatIdHexForGroupNumber:groupNumber error:error];
}

- (NSString *)chatIdHexForChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);

    if (chat.groupChatIdHex.length > 0) {
        return chat.groupChatIdHex;
    }

    if (chat.groupNumber < 0) {
        return nil;
    }

    return [self chatIdHexForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:error];
}

- (OCTChat *)chatForGroupNumber:(OCTToxGroupNumber)groupNumber
{
    return [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];
}

- (int32_t)peerCountForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    int32_t count = chat.groupPeerCount;

    if (count <= 0) {
        count = (int32_t)[[self peersForChat:chat] count];
    }

    if (count <= 0 && (chat.groupNumber >= 0 || chat.groupChatIdHex.length == 64)) {
        count = 1;
    }

    return count;
}

- (RLMResults *)peersForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    return [[self.dataSource managerGetRealmManager] groupPeersForChatUniqueIdentifier:chat.uniqueIdentifier];
}

// KHANDAQ (#9): saved members currently offline, straight from toxcore's NGC-synced state. Total real
// members = peerCountForChat + this — the same on every client (parity with Android), instead of
// each device guessing from its own peer DB.
- (int32_t)offlinePeerCountForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    if (chat.groupNumber < 0) {
        return 0;
    }

    __block uint32_t count = 0;
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        count = [tox groupOfflinePeerCountForGroupNumber:groupNumber error:nil];
    }];

    return (int32_t)count;
}

- (void)refreshPeersForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        [self prepareChatForGroupActivity:chat peerName:[self defaultGroupPeerName] error:nil];
    }

    if (chat.groupNumber < 0) {
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
    __block NSArray<NSDictionary *> *peers = nil;
    __block uint32_t peerCount = 0;
    __block NSMutableArray<NSDictionary *> *peerUpdates = [NSMutableArray array];

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        peers = [tox groupPeersForGroupNumber:groupNumber error:nil];
        peerCount = [tox groupPeerCountForGroupNumber:groupNumber error:nil];

        for (NSDictionary *entry in peers) {
            uint32_t peerId = [entry[@"peerId"] unsignedIntValue];
            NSString *name = entry[@"name"];
            OCTToxGroupRole role = entry[@"role"] != nil
                ? (OCTToxGroupRole)[entry[@"role"] integerValue]
                : [tox groupPeerRoleForGroupNumber:groupNumber peerId:peerId error:nil];
            NSString *peerPublicKeyHex = [tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil];
            OCTToxConnectionStatus connectionStatus = [tox groupPeerConnectionStatusForGroupNumber:groupNumber
                                                                                          peerId:peerId
                                                                                           error:nil];

            [peerUpdates addObject:@{
                @"peerId": @(peerId),
                @"name": name ?: @"",
                @"role": @(role),
                @"peerPublicKeyHex": peerPublicKeyHex ?: @"",
                @"connectionStatus": @(connectionStatus),
            }];
        }
    }];

    NSMutableSet<NSNumber *> *peerIds = [NSMutableSet setWithCapacity:peerUpdates.count];

    for (NSDictionary *entry in peerUpdates) {
        uint32_t peerId = [entry[@"peerId"] unsignedIntValue];
        [peerIds addObject:@(peerId)];
        [realmManager upsertGroupPeerForChat:chat
                                      peerId:peerId
                                    peerName:entry[@"name"]
                                    peerRole:(OCTToxGroupRole)[entry[@"role"] integerValue]
                          peerPublicKeyHex:entry[@"peerPublicKeyHex"]];

        if ([entry[@"connectionStatus"] integerValue] != OCTToxConnectionStatusNone) {
            [realmManager updateGroupPeerLastSeenDateInterval:[[NSDate date] timeIntervalSince1970]
                                                      forChat:chat
                                                       peerId:peerId];
        }
    }

    [realmManager removeGroupPeersForChat:chat notInPeerIds:peerIds];

    if (peerCount == 0 && peerIds.count > 0) {
        peerCount = (uint32_t)peerIds.count;
    }

    if (peerCount == 0) {
        peerCount = 1;
    }

    [realmManager updateGroupPeerCount:(int32_t)peerCount forChat:chat];
    [self refreshGroupMetadataForChat:chat];

    [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTGroupPeersUpdatedNotification
                                                                    object:nil
                                                                  userInfo:@{
        kOCTGroupPeersUpdatedChatUniqueIdentifierKey: chat.uniqueIdentifier,
        @"peerCount": @(peerCount),
    }];
}

- (void)prepareGroupLiveMediaMonitoringForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        return;
    }

    [self setupLiveVideoIfNeeded];
    [self setupLiveAudioIfNeeded];
}

- (void)syncGroupsWithTox
{
    if (self.groupBackgroundWorkPaused) {
        self.pendingGroupsSync = YES;
        return;
    }

    if (! [self.dataSource managerIsToxConnected]) {
        self.pendingGroupsSync = YES;
        OCTLogInfo(@"NGC group sync deferred until DHT is online");
        return;
    }

    if (self.groupsSyncInProgress) {
        self.pendingGroupsSync = YES;
        return;
    }

    self.groupsSyncInProgress = YES;
    self.pendingGroupsSync = NO;
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    __block NSArray<NSNumber *> *groupNumbers = nil;
    __block NSDictionary<NSNumber *, NSString *> *chatIdHexByGroupNumber = nil;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        groupNumbers = [tox groupNumbers];
        NSMutableDictionary<NSNumber *, NSString *> *chatIds = [NSMutableDictionary dictionaryWithCapacity:groupNumbers.count];

        for (NSNumber *number in groupNumbers) {
            OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)number.unsignedIntValue;
            NSString *chatIdHex = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];

            if (chatIdHex.length == 0) {
                continue;
            }

            chatIds[number] = chatIdHex;
            int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:groupNumber error:nil];

            if (connectionStatus < 0) {
                [tox groupReconnectWithGroupNumber:groupNumber error:nil];
            }
        }

        chatIdHexByGroupNumber = chatIds;
    }];

    for (NSNumber *number in groupNumbers) {
        NSString *chatIdHex = chatIdHexByGroupNumber[number];

        if (chatIdHex.length == 0) {
            continue;
        }

        OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)number.unsignedIntValue;
        OCTChat *chat = [realmManager getOrCreateGroupChatWithGroupNumber:groupNumber
                                                                chatIdHex:chatIdHex
                                                                groupName:nil
                                                             privacyState:OCTToxGroupPrivacyStatePublic];
        [self refreshPeersForChat:chat];
    }

    for (OCTChat *chat in [realmManager groupChatsSnapshot]) {
        [self prepareChatForGroupActivity:chat peerName:[self defaultGroupPeerName] error:nil];
    }

    [self maintainAllGroups];

    self.groupsSyncInProgress = NO;

    if (self.pendingGroupsSync) {
        self.pendingGroupsSync = NO;
        dispatch_async(dispatch_get_main_queue(), ^{
            [self syncGroupsWithTox];
        });
    }
}

- (nullable OCTChat *)acceptGroupInviteFromFriendNumber:(OCTToxFriendNumber)friendNumber
                                             inviteData:(NSData *)inviteData
                                              groupName:(NSString *)groupName
                                               password:(NSString *)password
                                                  error:(NSError **)error
{
    NSParameterAssert(inviteData);

    if (inviteData.length < TOX_GROUP_CHAT_ID_SIZE) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupInviteAcceptBadInvite
                                     description:@"Cannot accept group invite"
                                   failureReason:@"Invite data is too short"];
        }
        return nil;
    }

    NSString *chatIdHex = [self normalizedGroupChatIdHexString:[self chatIdHexFromInviteData:inviteData]];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    __block OCTToxGroupNumber existingGroupNumber = kOCTToxGroupNumberFailure;
    __block OCTToxGroupNumber groupNumber = kOCTToxGroupNumberFailure;
    __block NSError *localError = nil;
    __block NSString *resolvedChatIdHex = chatIdHex;
    __block OCTToxGroupPrivacyState privacyState = OCTToxGroupPrivacyStatePublic;
    __block BOOL reusedExistingGroup = NO;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        existingGroupNumber = [self groupNumberInToxForChatIdHex:chatIdHex onTox:tox];

        if (existingGroupNumber != kOCTToxGroupNumberFailure) {
            int32_t existingConnectionStatus = [tox groupConnectionStatusForGroupNumber:existingGroupNumber error:nil];
            uint32_t peerCount = [tox groupPeerCountForGroupNumber:existingGroupNumber error:nil];

            if (existingConnectionStatus > 0 && peerCount > 1) {
                groupNumber = existingGroupNumber;
                reusedExistingGroup = YES;
                return;
            }

            [tox groupLeaveWithGroupNumber:existingGroupNumber partMessage:nil error:nil];
        }

        groupNumber = [tox groupInviteAcceptWithFriendNumber:friendNumber
                                                  inviteData:inviteData
                                                    peerName:[self defaultGroupPeerName]
                                                    password:password
                                                       error:&localError];

        if (groupNumber == kOCTToxGroupNumberFailure) {
            return;
        }

        NSString *fromTox = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];

        if (fromTox.length > 0) {
            resolvedChatIdHex = [self normalizedGroupChatIdHexString:fromTox];
        }

        privacyState = [tox groupPrivacyStateForGroupNumber:groupNumber error:nil];

        if ([tox groupConnectionStatusForGroupNumber:groupNumber error:nil] <= 0) {
            [tox groupReconnectWithGroupNumber:groupNumber error:nil];
        }
    }];

    if (groupNumber == kOCTToxGroupNumberFailure) {
        if (error) {
            *error = localError;
        }
        return nil;
    }

    if (reusedExistingGroup) {
        OCTChat *existingChat = [realmManager chatWithGroupNumber:groupNumber];

        if (existingChat) {
            [self refreshPeersForChat:existingChat];
            return existingChat;
        }
    }

    OCTChat *chat = [realmManager getOrCreateGroupChatWithGroupNumber:groupNumber
                                                            chatIdHex:resolvedChatIdHex
                                                            groupName:groupName
                                                         privacyState:privacyState];

    if (password.length > 0) {
        [realmManager updateGroupPassword:password forChat:chat];
    }

    if (groupName.length > 0 && chat.groupName.length == 0) {
        [realmManager updateObject:chat withBlock:^(OCTChat *theChat) {
            theChat.groupName = groupName;
        }];
    }

    [self bootstrapGroupConnectionForChat:chat groupNumber:groupNumber];
    [self refreshPeersForChat:chat];
    [self addGroupSystemMessageWithFormatKey:@"group_system_you_joined" argument:nil toChat:chat];

    return chat;
}

- (BOOL)inviteFriendWithNumber:(OCTToxFriendNumber)friendNumber
                 toGroupNumber:(OCTToxGroupNumber)groupNumber
                         error:(NSError **)error
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat) {
        [self prepareChatForGroupActivity:chat peerName:[self defaultGroupPeerName] error:nil];
    }

    __block BOOL result = NO;
    __block NSError *localError = nil;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:groupNumber error:nil];

        if (connectionStatus < 0) {
            [tox groupReconnectWithGroupNumber:groupNumber error:nil];
        }

        result = [tox groupInviteFriendWithGroupNumber:groupNumber
                                          friendNumber:friendNumber
                                                 error:&localError];
    }];

    if (error) {
        *error = localError;
    }

    return result;
}

- (void)sendMessageToChat:(OCTChat *)chat
                     text:(NSString *)text
                     type:(OCTToxMessageType)type
             successBlock:(void (^)(OCTMessageAbstract *message))successBlock
             failureBlock:(void (^)(NSError *error))failureBlock
{
    NSParameterAssert(chat);
    NSParameterAssert(text);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *peerName = [self defaultGroupPeerName];
    uint32_t messageId = 0;
    NSError *error = nil;

    if (! [self prepareChatForGroupActivity:chat peerName:peerName error:&error]) {
        if (failureBlock) {
            failureBlock(error);
        }
        return;
    }

    if (! [self precheckCanSendInChat:chat error:&error]) {
        if (failureBlock) {
            failureBlock(error);
        }
        return;
    }

    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
    __block BOOL sent = NO;

    for (NSUInteger attempt = 0; attempt < 4; attempt++) {
        if (attempt > 0) {
            [self prepareChatForGroupActivity:chat peerName:peerName error:nil];
            groupNumber = (OCTToxGroupNumber)chat.groupNumber;
            __block NSError *reconnectError = nil;

            [self performSyncOnToxQueue:^(OCTTox *tox) {
                [tox groupReconnectWithGroupNumber:groupNumber error:&reconnectError];
            }];
        }

        __block uint32_t localMessageId = 0;
        __block NSError *localError = nil;

        [self performSyncOnToxQueue:^(OCTTox *tox) {
            sent = [tox groupSendMessage:text
                                    type:type
                             groupNumber:groupNumber
                               messageId:&localMessageId
                                   error:&localError];
        }];

        if (sent) {
            messageId = localMessageId;
            error = localError;
            break;
        }

        error = localError;

        if (error != nil &&
            error.code != OCTToxErrorGroupSendMessageDisconnected &&
            error.code != OCTToxErrorGroupSendMessageGroupNotFound &&
            error.code != OCTToxErrorGroupSendMessageFailSend) {
            break;
        }
    }

    if (! sent) {
        if ([self shouldQueueGroupMessageForError:error]) {
            OCTMessageAbstract *pendingMessage = [realmManager addGroupPendingMessageWithText:text
                                                                                         type:type
                                                                                         chat:chat];
            OCTLogInfo(@"NGC group message queued while disconnected chat=%@", chat.uniqueIdentifier);

            if (successBlock) {
                successBlock(pendingMessage);
            }

            return;
        }

        if (failureBlock) {
            failureBlock(error);
        }

        return;
    }

    OCTMessageAbstract *message = [realmManager addGroupMessageWithText:text
                                                                   type:type
                                                                   chat:chat
                                                                 peerId:0
                                                               peerName:nil
                                                              messageId:messageId];

    [self scheduleHistSyncBroadcastForGroupNumber:groupNumber];

    if (successBlock) {
        successBlock(message);
    }
}

- (void)sendFileAtPath:(NSString *)filePath
                toChat:(OCTChat *)chat
          moveToUploads:(BOOL)moveToUploads
          successBlock:(void (^)(OCTMessageAbstract *message))successBlock
          failureBlock:(void (^)(NSError *error))failureBlock
{
    NSParameterAssert(filePath);
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *peerName = [self defaultGroupPeerName];
    NSError *error = nil;

    if (! [self prepareChatForGroupActivity:chat peerName:peerName error:&error]) {
        if (failureBlock) {
            failureBlock(error);
        }
        return;
    }

    if (! [self precheckCanSendInChat:chat error:&error]) {
        if (failureBlock) {
            failureBlock(error);
        }
        return;
    }

    NSString *fileName = [filePath lastPathComponent];

    if (moveToUploads) {
        NSString *uploadsDirectory = [self groupUploadsDirectory];
        NSString *toPath = [OCTFileTools createNewFilePathInDirectory:uploadsDirectory fileName:fileName];

        if (! [[NSFileManager defaultManager] moveItemAtPath:filePath toPath:toPath error:&error]) {
            if (failureBlock) {
                failureBlock(error ?: [NSError errorWithDomain:NSCocoaErrorDomain code:NSFileWriteUnknownError userInfo:nil]);
            }
            return;
        }

        filePath = toPath;
    }

    NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:filePath error:&error];

    if (! attributes) {
        if (failureBlock) {
            failureBlock(error ?: [NSError errorWithDomain:NSCocoaErrorDomain code:NSFileReadUnknownError userInfo:nil]);
        }
        return;
    }

    uint64_t fileSize = [attributes[NSFileSize] unsignedLongLongValue];

    if (fileSize < 1 || fileSize > kOCTNgcMaxFileTransferBytes) {
        if (failureBlock) {
            failureBlock([NSError errorWithDomain:kOCTNgcGroupFileTransferErrorDomain
                                             code:OCTNgcGroupFileTransferErrorFileTooLarge
                                         userInfo:nil]);
        }
        return;
    }

    NSString *msgIdHex = [OCTNgcGroupFileTransfer generateMsgIdHex];
    NSString *fileUTI = [self fileUTIFromFileName:fileName];
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;

    OCTMessageAbstract *message = [realmManager addGroupMessageWithFileName:fileName
                                                                   fileSize:fileSize
                                                                   filePath:filePath
                                                                   fileType:OCTMessageFileTypeLoading
                                                                       chat:chat
                                                                     peerId:0
                                                                   fileUTI:fileUTI
                                                        groupMsgIdHashHex:msgIdHex
                                                     groupTransferProgress:0.0f];

    // KHANDAQ: don't push media into a group that is still CONNECTING (it would sit at 0% then
    // "Upload Failed"). Gate ONLY on the group being connected — the chunk send broadcasts via the
    // group mesh (tox_group_send_custom_packet), which does NOT require a peer with a direct online
    // status; requiring an "online peer" here wrongly blocked media when peers were reachable but
    // their per-peer status read None. If still connecting, queue as pending and let
    // flushPendingGroupFileMessage retry on reconnect.
    if ([[self.dataSource managerGetTox] groupConnectionStatusForGroupNumber:groupNumber error:nil] <= 0) {
        [realmManager updateObject:message withBlock:^(OCTMessageAbstract *abstract) {
            abstract.groupPendingSend = YES;
        }];
        OCTLogInfo(@"NGC group file queued — group not ready, will retry chat=%@", chat.uniqueIdentifier);
        if (successBlock) {
            successBlock(message);
        }
        return;
    }

    [self setupFileTransferIfNeeded];

    __weak typeof(self) weakSelf = self;
    __weak OCTMessageAbstract *weakMessage = message;

    [self.fileTransfer sendFileAtPath:filePath
                          groupNumber:groupNumber
                             msgIdHex:msgIdHex
                             progress:^(float progress) {
                                 __strong typeof(weakSelf) self = weakSelf;
                                 __strong OCTMessageAbstract *strongMessage = weakMessage;

                                 if (! self || ! strongMessage) {
                                     return;
                                 }

                                 OCTRealmManager *realm = [self.dataSource managerGetRealmManager];
                                 [realm updateObject:strongMessage.messageFile withBlock:^(OCTMessageFile *file) {
                                     file.groupTransferProgress = progress;
                                 }];
                                 [realm updateObject:strongMessage withBlock:^(OCTMessageAbstract *abstract) {
                                     abstract.dateInterval = abstract.dateInterval;
                                 }];
                             }
                           completion:^(BOOL success, NSError *sendError) {
                               __strong typeof(weakSelf) self = weakSelf;

                               if (! self) {
                                   return;
                               }

                               OCTRealmManager *realm = [self.dataSource managerGetRealmManager];

                               if (success) {
                                   [realm updateObject:message.messageFile withBlock:^(OCTMessageFile *file) {
                                       file.fileType = OCTMessageFileTypeReady;
                                       file.groupTransferProgress = 1.0f;
                                       file.isDelivered = YES;
                                   }];
                                   [realm updateObject:message withBlock:^(OCTMessageAbstract *abstract) {
                                       abstract.groupPendingSend = NO;
                                       abstract.dateInterval = abstract.dateInterval;
                                   }];

                                   [self scheduleHistSyncBroadcastForGroupNumber:groupNumber];

                                   if (successBlock) {
                                       successBlock(message);
                                   }
                                   return;
                               }

                               // KHANDAQ: re-queue (not fail) when the group isn't connected — the
                               // pending queue retries on reconnect. (Connected sends that still fail
                               // fall through to the genuine-failure path below.)
                               if (! [self isGroupConnectedForChat:chat]) {
                                   [realm updateObject:message withBlock:^(OCTMessageAbstract *abstract) {
                                       abstract.groupPendingSend = YES;
                                   }];
                                   [realm updateObject:message.messageFile withBlock:^(OCTMessageFile *file) {
                                       file.fileType = OCTMessageFileTypeLoading;
                                       file.groupTransferProgress = 0.0f;
                                   }];
                                   OCTLogInfo(@"NGC group file queued (not ready) chat=%@", chat.uniqueIdentifier);

                                   if (successBlock) {
                                       successBlock(message);
                                   }
                                   return;
                               }

                               if (sendError.code == OCTNgcGroupFileTransferErrorCancelled ||
                                   message.messageFile.fileType == OCTMessageFileTypeCanceled) {
                                   if (failureBlock) {
                                       failureBlock(sendError);
                                   }
                                   return;
                               }

                               [realm updateObject:message.messageFile withBlock:^(OCTMessageFile *file) {
                                   file.fileType = OCTMessageFileTypeCanceled;
                               }];

                               if (failureBlock) {
                                   failureBlock(sendError ?: [NSError errorWithDomain:kOCTNgcGroupFileTransferErrorDomain
                                                                                code:OCTNgcGroupFileTransferErrorSendFailed
                                                                            userInfo:nil]);
                               }
                           }];
}

- (void)removeAllMessagesInChat:(OCTChat *)chat removeChat:(BOOL)removeChat leaveGroup:(BOOL)leaveGroup
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (leaveGroup && chat.groupNumber >= 0) {
        [self leaveGroupWithNumber:(OCTToxGroupNumber)chat.groupNumber partMessage:nil error:nil];
    }

    [[self.dataSource managerGetRealmManager] removeAllMessagesInChat:chat removeChat:removeChat];
}

- (NSUInteger)removeGroupSystemMessagesInChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    return [[self.dataSource managerGetRealmManager] removeGroupSystemMessagesInChat:chat];
}

- (NSUInteger)groupSystemMessageCountForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    return [[self.dataSource managerGetRealmManager] groupSystemMessageCountForChat:chat];
}

- (BOOL)isGroupPeerOnlineWithId:(uint32_t)peerId inChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        return NO;
    }

    OCTToxConnectionStatus status = [[self.dataSource managerGetTox] groupPeerConnectionStatusForGroupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                                                                       peerId:peerId
                                                                                                        error:nil];
    return status != OCTToxConnectionStatusNone;
}

- (NSTimeInterval)groupPeerLastSeenDateIntervalForPeerId:(uint32_t)peerId inChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    return [[self.dataSource managerGetRealmManager] groupPeerLastSeenDateIntervalForChat:chat peerId:peerId];
}

- (int32_t)onlineGroupPeerCountForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        return 0;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
    int32_t onlineCount = 0;

    for (NSDictionary *entry in [tox groupPeersForGroupNumber:groupNumber error:nil]) {
        uint32_t peerId = [entry[@"peerId"] unsignedIntValue];
        OCTToxConnectionStatus status = [tox groupPeerConnectionStatusForGroupNumber:groupNumber
                                                                              peerId:peerId
                                                                               error:nil];

        if (status != OCTToxConnectionStatusNone) {
            onlineCount++;
        }
    }

    return onlineCount;
}

- (NSString *)groupSelfPeerNameForChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Group is not connected"
                                   failureReason:@"Invalid group number"];
        }
        return nil;
    }

    NSString *name = [[self.dataSource managerGetTox] groupSelfNameForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:error];

    if (name.length > 0) {
        return name;
    }

    return [self defaultGroupPeerName];
}

- (uint32_t)groupSelfPeerIdForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        return 0;
    }

    return [[self.dataSource managerGetTox] groupSelfPeerIdForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:nil];
}

- (BOOL)setGroupSelfPeerName:(NSString *)name forChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSParameterAssert(name);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    NSString *peerName = [self defaultGroupPeerName];

    if (! [self prepareChatForGroupActivity:chat peerName:peerName error:error]) {
        return NO;
    }

    NSString *trimmed = [name stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];

    if (trimmed.length == 0) {
        trimmed = peerName;
    }

    return [[self.dataSource managerGetTox] groupSelfSetName:trimmed
                                                 groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                       error:error];
}

- (BOOL)cancelGroupFileTransferForMessage:(OCTMessageAbstract *)message error:(NSError **)error
{
    NSParameterAssert(message);

    if (! message.messageFile || message.messageFile.fileType != OCTMessageFileTypeLoading ||
        message.messageFile.groupMsgIdHashHex.length == 0) {
        if (error) {
            *error = [NSError errorWithDomain:kOCTSubmanagerGroupsErrorDomain
                                         code:0
                                     userInfo:@{NSLocalizedDescriptionKey: @"No active group file transfer."}];
        }
        return NO;
    }

    NSString *msgIdHex = message.messageFile.groupMsgIdHashHex;

    if (msgIdHex.length == 0) {
        if (error) {
            *error = [NSError errorWithDomain:kOCTSubmanagerGroupsErrorDomain
                                         code:0
                                     userInfo:@{NSLocalizedDescriptionKey: @"Missing transfer id."}];
        }
        return NO;
    }

    [self setupFileTransferIfNeeded];
    [self.fileTransfer cancelSendForMsgIdHex:msgIdHex];

    [[self.dataSource managerGetRealmManager] updateObject:message.messageFile withBlock:^(OCTMessageFile *file) {
        file.fileType = OCTMessageFileTypeCanceled;
        file.groupTransferProgress = 0.0f;
    }];

    return YES;
}

- (void)setGroupNotificationsSilent:(BOOL)silent forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [[self.dataSource managerGetRealmManager] setGroupNotificationsSilent:silent forChat:chat];
}

- (void)setPeerNotificationsSilent:(BOOL)silent peerId:(uint32_t)peerId forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [[self.dataSource managerGetRealmManager] setGroupPeerNotificationsSilent:silent peerId:peerId chat:chat];
}

- (void)setPrivateLastReadDateInterval:(NSTimeInterval)interval peerId:(uint32_t)peerId forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [[self.dataSource managerGetRealmManager] setGroupPeerPrivateLastReadDateInterval:interval peerId:peerId chat:chat];
}

- (void)markAllPrivateThreadsReadForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [[self.dataSource managerGetRealmManager] markAllGroupPrivateThreadsReadForChat:chat
                                                                       dateInterval:[[NSDate date] timeIntervalSince1970]];
}

- (int32_t)unreadPrivateMessageCountForPeerId:(uint32_t)peerId inChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    return [[self.dataSource managerGetRealmManager] unreadPrivateMessageCountForPeerId:peerId chat:chat];
}

- (int32_t)totalUnreadPrivateMessageCountForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    RLMResults *peerResults = [[self.dataSource managerGetRealmManager] groupPeersForChatUniqueIdentifier:chat.uniqueIdentifier];
    int32_t total = 0;

    for (OCTGroupPeer *peer in peerResults) {
        total += [self unreadPrivateMessageCountForPeerId:(uint32_t)peer.peerId inChat:chat];
    }

    return total;
}

- (BOOL)isGroupAtPeerCapacityForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    int32_t peerCount = [self peerCountForChat:chat];

    if (peerCount <= 0) {
        return NO;
    }

    int32_t limit = [self groupPeerLimitForChat:chat error:nil];

    if (limit <= 0) {
        limit = kOCTDefaultGroupPeerLimit;
    }

    return peerCount >= limit;
}

- (BOOL)isGroupConnectedForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    if (chat.groupNumber < 0) {
        return NO;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
    int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:groupNumber error:nil];

    if (connectionStatus > 0) {
        return YES;
    }

    if ([self.dataSource managerIsToxConnected]) {
        uint32_t peerCount = [tox groupPeerCountForGroupNumber:groupNumber error:nil];

        if (peerCount >= 1) {
            return YES;
        }

        uint32_t selfPeerId = [tox groupSelfPeerIdForGroupNumber:groupNumber error:nil];

        if (selfPeerId > 0) {
            return YES;
        }
    }

    return NO;
}

- (NSInteger)groupJoinAttemptForChat:(OCTChat *)chat
{
    NSString *key = [self groupJoinRetryKeyForChat:chat];

    if (key.length == 0) {
        return 0;
    }

    return [self.groupJoinAttemptByKey[key] integerValue];
}

- (BOOL)isGroupJoinRetryRunningForChat:(OCTChat *)chat
{
    NSString *key = [self groupJoinRetryKeyForChat:chat];

    if (key.length == 0) {
        return NO;
    }

    return [self.groupJoinRetryRunning containsObject:key];
}

- (BOOL)canKickPeerWithId:(uint32_t)peerId inChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        return NO;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
    uint32_t selfPeerId = [tox groupSelfPeerIdForGroupNumber:groupNumber error:nil];
    OCTToxGroupRole selfRole = [tox groupSelfRoleForGroupNumber:groupNumber error:nil];
    OCTToxGroupRole peerRole = [tox groupPeerRoleForGroupNumber:groupNumber peerId:peerId error:nil];

    return [self.class canKickPeerWithRole:peerRole selfRole:selfRole isSelfPeer:(peerId == selfPeerId)];
}

- (BOOL)kickPeerWithId:(uint32_t)peerId inChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (! [self canKickPeerWithId:peerId inChat:chat]) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupKickPeerPermissions
                                     description:@"Cannot kick group peer"
                                   failureReason:@"Insufficient permissions"];
        }
        return NO;
    }

    return [[self.dataSource managerGetTox] groupKickPeerWithId:peerId
                                                  groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                        error:error];
}

- (OCTToxGroupRole)peerRoleWithId:(uint32_t)peerId inChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        OCTGroupPeer *peer = [[self.dataSource managerGetRealmManager] groupPeerForChat:chat peerId:peerId];
        return peer ? (OCTToxGroupRole)peer.peerRole : OCTToxGroupRoleUser;
    }

    return [[self.dataSource managerGetTox] groupPeerRoleForGroupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                                 peerId:peerId
                                                                  error:nil];
}

- (BOOL)canSetPeerRole:(OCTToxGroupRole)role peerId:(uint32_t)peerId inChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0 || role == OCTToxGroupRoleFounder) {
        return NO;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
    uint32_t selfPeerId = [tox groupSelfPeerIdForGroupNumber:groupNumber error:nil];
    OCTToxGroupRole selfRole = [tox groupSelfRoleForGroupNumber:groupNumber error:nil];
    OCTToxGroupRole peerRole = [self peerRoleWithId:peerId inChat:chat];

    return [self.class canSetPeerRole:role
                             peerRole:peerRole
                             selfRole:selfRole
                           isSelfPeer:(peerId == selfPeerId)];
}

- (BOOL)setPeerRole:(OCTToxGroupRole)role peerId:(uint32_t)peerId inChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (! [self canSetPeerRole:role peerId:peerId inChat:chat]) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupSetRolePermissions
                                     description:@"Cannot set group peer role"
                                   failureReason:@"Insufficient permissions"];
        }
        return NO;
    }

    BOOL result = [[self.dataSource managerGetTox] groupModSetRole:role
                                                            peerId:peerId
                                                       groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                             error:error];

    if (result) {
        [[self.dataSource managerGetRealmManager] setGroupPeerRole:role peerId:peerId chat:chat];
    }

    return result;
}

- (void)refreshGroupMetadataForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        return;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
    NSString *topic = [tox groupTopicForGroupNumber:groupNumber error:nil];

    if (topic.length > 0) {
        [realmManager updateGroupTopic:topic forChat:chat];
    }

    // KHANDAQ (#15): pull the authoritative group name from toxcore so the chat list shows the real
    // name (e.g. "Gggg") instead of a stale placeholder / the topic (e.g. "11AE2E"). This also
    // repairs already-corrupted chats where the topic previously overwrote the name.
    NSString *liveName = [tox groupNameForGroupNumber:groupNumber error:nil];
    [realmManager updateGroupName:liveName forChat:chat];

    NSString *password = [tox groupPasswordForGroupNumber:groupNumber error:nil];
    [realmManager updateGroupPassword:password forChat:chat];

    OCTToxGroupTopicLock topicLock = [tox groupTopicLockForGroupNumber:groupNumber error:nil];
    [realmManager updateGroupTopicLockEnabled:(topicLock == OCTToxGroupTopicLockEnabled) forChat:chat];

    uint16_t peerLimit = [tox groupPeerLimitForGroupNumber:groupNumber error:nil];
    [realmManager updateGroupPeerLimit:peerLimit > 0 ? (int32_t)peerLimit : 0 forChat:chat];

    OCTToxGroupPrivacyState privacyState = [tox groupPrivacyStateForGroupNumber:groupNumber error:nil];
    [realmManager updateGroupPrivacyState:privacyState forChat:chat];

    OCTToxGroupVoiceState voiceState = [tox groupVoiceStateForGroupNumber:groupNumber error:nil];
    [realmManager updateGroupVoiceState:voiceState forChat:chat];
}

- (OCTToxGroupRole)selfRoleInChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    if (chat.groupNumber < 0) {
        return OCTToxGroupRoleUser;
    }

    return [[self.dataSource managerGetTox] groupSelfRoleForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:nil];
}

- (NSString *)groupTopicForChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);

    if (chat.groupTopic.length > 0) {
        return chat.groupTopic;
    }

    if (chat.groupNumber < 0) {
        return chat.groupName;
    }

    return [[self.dataSource managerGetTox] groupTopicForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:error];
}

- (BOOL)setGroupTopic:(NSString *)topic forChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    NSString *sanitized = [self.class sanitizedGroupTopic:topic];

    if (sanitized.length == 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupTopicSetTooLong
                                     description:@"Cannot set group topic"
                                   failureReason:@"Topic is empty"];
        }
        return NO;
    }

    if (chat.groupNumber < 0) {
        [[self.dataSource managerGetRealmManager] updateGroupTopic:sanitized forChat:chat];
        return YES;
    }

    BOOL result = [[self.dataSource managerGetTox] groupSetTopic:sanitized
                                                    groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                          error:error];

    if (result) {
        [[self.dataSource managerGetRealmManager] updateGroupTopic:sanitized forChat:chat];
    }

    return result;
}

- (NSString *)groupPasswordForChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);

    if (chat.groupPassword.length > 0) {
        return chat.groupPassword;
    }

    if (chat.groupNumber < 0) {
        return nil;
    }

    return [[self.dataSource managerGetTox] groupPasswordForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:error];
}

- (BOOL)setGroupPassword:(NSString *)password forChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupFounderSetPasswordGroupNotFound
                                     description:@"Cannot set group password"
                                   failureReason:@"Group is not connected"];
        }
        return NO;
    }

    NSString *sanitized = password.length > 0 ? [password stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] : nil;
    BOOL result = [[self.dataSource managerGetTox] groupFounderSetPassword:sanitized
                                                               groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                                     error:error];

    if (result) {
        [[self.dataSource managerGetRealmManager] updateGroupPassword:sanitized forChat:chat];
    }

    return result;
}

- (OCTToxGroupTopicLock)groupTopicLockForChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);

    if (chat.groupNumber < 0) {
        return chat.groupTopicLockEnabled ? OCTToxGroupTopicLockEnabled : OCTToxGroupTopicLockDisabled;
    }

    return [[self.dataSource managerGetTox] groupTopicLockForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:error];
}

- (BOOL)setGroupTopicLock:(OCTToxGroupTopicLock)topicLock forChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupFounderSetTopicLockGroupNotFound
                                     description:@"Cannot set group topic lock"
                                   failureReason:@"Group is not connected"];
        }
        return NO;
    }

    BOOL result = [[self.dataSource managerGetTox] groupFounderSetTopicLock:topicLock
                                                               groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                                     error:error];

    if (result) {
        [[self.dataSource managerGetRealmManager] updateGroupTopicLockEnabled:(topicLock == OCTToxGroupTopicLockEnabled) forChat:chat];
    }

    return result;
}

- (int32_t)groupPeerLimitForChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);

    if (chat.groupPeerLimit > 0) {
        return chat.groupPeerLimit;
    }

    if (chat.groupNumber < 0) {
        return kOCTDefaultGroupPeerLimit;
    }

    uint16_t limit = [[self.dataSource managerGetTox] groupPeerLimitForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:error];

    if (limit < 1) {
        return kOCTDefaultGroupPeerLimit;
    }

    return (int32_t)limit;
}

- (BOOL)setGroupPeerLimit:(int32_t)peerLimit forChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (peerLimit < 1) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupFounderSetPeerLimitFailSet
                                     description:@"Cannot set group peer limit"
                                   failureReason:@"Peer limit must be at least 1"];
        }
        return NO;
    }

    if (chat.groupNumber < 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupFounderSetPeerLimitGroupNotFound
                                     description:@"Cannot set group peer limit"
                                   failureReason:@"Group is not connected"];
        }
        return NO;
    }

    BOOL result = [[self.dataSource managerGetTox] groupFounderSetPeerLimit:(uint16_t)peerLimit
                                                               groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                                     error:error];

    if (result) {
        [[self.dataSource managerGetRealmManager] updateGroupPeerLimit:peerLimit forChat:chat];
    }

    return result;
}

- (BOOL)canEditGroupTopicInChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    OCTToxGroupRole role = [self selfRoleInChat:chat];

    if (role == OCTToxGroupRoleObserver) {
        return NO;
    }

    if (role == OCTToxGroupRoleFounder || role == OCTToxGroupRoleModerator) {
        return YES;
    }

    return [self groupTopicLockForChat:chat error:nil] == OCTToxGroupTopicLockDisabled;
}

- (OCTToxGroupPrivacyState)groupPrivacyStateForChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);

    if (chat.groupNumber < 0) {
        return (OCTToxGroupPrivacyState)chat.groupPrivacyState;
    }

    return [[self.dataSource managerGetTox] groupPrivacyStateForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:error];
}

- (BOOL)setGroupPrivacyState:(OCTToxGroupPrivacyState)privacyState forChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if ([self selfRoleInChat:chat] != OCTToxGroupRoleFounder) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupFounderSetPrivacyStatePermissions
                                     description:@"Cannot set group privacy state"
                                   failureReason:@"Only the founder can change privacy state"];
        }
        return NO;
    }

    if (chat.groupNumber < 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupFounderSetPrivacyStateGroupNotFound
                                     description:@"Cannot set group privacy state"
                                   failureReason:@"Group is not connected"];
        }
        return NO;
    }

    BOOL result = [[self.dataSource managerGetTox] groupFounderSetPrivacyState:privacyState
                                                                  groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                                        error:error];

    if (result) {
        [[self.dataSource managerGetRealmManager] updateGroupPrivacyState:privacyState forChat:chat];
    }

    return result;
}

- (OCTToxGroupVoiceState)groupVoiceStateForChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);

    if (chat.groupNumber >= 0) {
        return [[self.dataSource managerGetTox] groupVoiceStateForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:error];
    }

    return (OCTToxGroupVoiceState)chat.groupVoiceState;
}

- (BOOL)setGroupVoiceState:(OCTToxGroupVoiceState)voiceState forChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if ([self selfRoleInChat:chat] != OCTToxGroupRoleFounder) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupFounderSetVoiceStatePermissions
                                     description:@"Cannot set group voice state"
                                   failureReason:@"Only the founder can change voice state"];
        }
        return NO;
    }

    if (chat.groupNumber < 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupFounderSetVoiceStateGroupNotFound
                                     description:@"Cannot set group voice state"
                                   failureReason:@"Group is not connected"];
        }
        return NO;
    }

    BOOL result = [[self.dataSource managerGetTox] groupFounderSetVoiceState:voiceState
                                                                 groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                                       error:error];

    if (result) {
        [[self.dataSource managerGetRealmManager] updateGroupVoiceState:voiceState forChat:chat];
    }

    return result;
}

- (BOOL)startGroupLiveAudioForChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        if (error) {
            *error = [NSError errorWithDomain:kOCTSubmanagerGroupsErrorDomain
                                         code:OCTSubmanagerGroupsErrorObserverCannotSend
                                     userInfo:@{NSLocalizedDescriptionKey: @"Group is not connected"}];
        }
        return NO;
    }

    [self setupLiveAudioIfNeeded];
    return [self.liveAudio startLiveCaptureForGroupNumber:(uint32_t)chat.groupNumber error:error];
}

- (void)stopGroupLiveAudio
{
    if (self.liveAudio) {
        [self.liveAudio stopLiveCapture];
    }
}

- (BOOL)isGroupLiveAudioActive
{
    return self.liveAudio != nil && self.liveAudio.isLiveCaptureActive;
}

- (BOOL)startGroupLiveVideoForChat:(OCTChat *)chat
                  remoteFrameBlock:(void (^ _Nullable)(UIImage * _Nullable frame))remoteFrameBlock
                   localFrameBlock:(void (^ _Nullable)(UIImage * _Nullable frame))localFrameBlock
                             error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (chat.groupNumber < 0) {
        if (error) {
            *error = [NSError errorWithDomain:kOCTSubmanagerGroupsErrorDomain
                                         code:OCTSubmanagerGroupsErrorObserverCannotSend
                                     userInfo:@{NSLocalizedDescriptionKey: @"Group is not connected"}];
        }
        return NO;
    }

    [self setupLiveVideoIfNeeded];
    [self setupLiveAudioIfNeeded];
    [self.liveAudio prepareIncomingPlayback];

    const BOOL started = [self.liveVideo startLiveCaptureForGroupNumber:(uint32_t)chat.groupNumber
                                                       remoteFrameBlock:remoteFrameBlock
                                                        localFrameBlock:localFrameBlock
                                                                  error:error];
    return started;
}

- (void)stopGroupLiveVideo
{
    if (self.liveVideo) {
        [self.liveVideo stopLiveCapture];
    }
}

- (BOOL)isGroupLiveVideoActive
{
    return self.liveVideo != nil && self.liveVideo.isLiveCaptureActive;
}

- (void)setGroupLiveVideoHighQuality:(BOOL)highQuality forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    (void)chat;

    if (self.liveVideo) {
        [self.liveVideo setHighQualityEnabled:highQuality];
    }
}

- (BOOL)isGroupLiveVideoHighQuality
{
    return self.liveVideo != nil && self.liveVideo.isHighQualityEnabled;
}

- (OCTGroupLiveVideoIconState)groupLiveVideoIconStateForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    if (chat.groupNumber < 0 || ! self.liveVideo) {
        return OCTGroupLiveVideoIconStateInactive;
    }

    const uint32_t groupNumber = (uint32_t)chat.groupNumber;

    if (self.liveVideo.isLiveCaptureActive && self.liveVideo.captureGroupNumber == groupNumber) {
        return OCTGroupLiveVideoIconStateActive;
    }

    if ([self.liveVideo hasRecentIncomingVideoForGroupNumber:groupNumber withinSeconds:2.0]) {
        return OCTGroupLiveVideoIconStateIncoming;
    }

    return OCTGroupLiveVideoIconStateInactive;
}

- (NSUInteger)recentIncomingGroupLiveVideoPeerCountForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    if (chat.groupNumber < 0 || ! self.liveVideo) {
        return 0;
    }

    return [self.liveVideo recentIncomingVideoPeerCountForGroupNumber:(uint32_t)chat.groupNumber withinSeconds:5.0];
}

- (NSString *)primaryRecentIncomingGroupLiveVideoPeerNameForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    if (chat.groupNumber < 0 || ! self.liveVideo) {
        return nil;
    }

    NSString *peerPublicKeyHex = [self.liveVideo primaryRecentIncomingVideoPeerPublicKeyHexForGroupNumber:(uint32_t)chat.groupNumber
                                                                                            withinSeconds:5.0];

    if (peerPublicKeyHex.length == 0) {
        return nil;
    }

    RLMResults *peers = [[self.dataSource managerGetRealmManager] groupPeersForChatUniqueIdentifier:chat.uniqueIdentifier];

    for (OCTGroupPeer *peer in peers) {
        if (peer.peerPublicKeyHex.length > 0 &&
            [peer.peerPublicKeyHex.lowercaseString isEqualToString:peerPublicKeyHex.lowercaseString]) {
            return peer.peerName.length > 0 ? peer.peerName : nil;
        }
    }

    return nil;
}

- (void)sendPrivateMessage:(NSString *)text
                  toPeerId:(uint32_t)peerId
                    inChat:(OCTChat *)chat
              successBlock:(void (^)(OCTMessageAbstract *message))successBlock
              failureBlock:(void (^)(NSError *error))failureBlock
{
    NSParameterAssert(text);
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if ([self isBlockedIncomingPeerId:peerId groupNumber:(OCTToxGroupNumber)chat.groupNumber]) {
        if (failureBlock) {
            failureBlock([NSError errorWithDomain:@"OCTSubmanagerGroups" code:0 userInfo:@{NSLocalizedDescriptionKey: @"Peer is not available"}]);
        }
        return;
    }

    if (chat.groupNumber < 0) {
        if (failureBlock) {
            failureBlock([NSError errorWithDomain:@"OCTSubmanagerGroups" code:0 userInfo:@{NSLocalizedDescriptionKey: @"Group is not connected"}]);
        }
        return;
    }

    NSError *error = nil;
    BOOL sent = [[self.dataSource managerGetTox] groupSendPrivateMessage:text
                                                                    type:OCTToxMessageTypeNormal
                                                             groupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                                  peerId:peerId
                                                                   error:&error];

    if (! sent) {
        if (failureBlock) {
            failureBlock(error ?: [NSError errorWithDomain:@"OCTSubmanagerGroups" code:0 userInfo:nil]);
        }
        return;
    }

    // KHANDAQ (#55): counterparty of an OUTGOING private message is the RECIPIENT — freeze its stable
    // pubkey so the local copy threads under the right person across peer-id churn.
    NSString *recipientPubkey = [[self.dataSource managerGetTox] groupPeerPublicKeyHexForGroupNumber:(OCTToxGroupNumber)chat.groupNumber peerId:peerId error:nil];

    OCTMessageAbstract *message = [[self.dataSource managerGetRealmManager] addGroupPrivateMessageWithText:text
                                                                                                      type:OCTToxMessageTypeNormal
                                                                                                      chat:chat
                                                                                                    peerId:peerId
                                                                                                  peerName:nil
                                                                                            counterpartyId:peerId
                                                                                        counterpartyPubkey:recipientPubkey
                                                                                                 messageId:0
                                                                                                isOutgoing:YES];

    if (successBlock) {
        successBlock(message);
    }
}

#pragma mark - OCTToxDelegate

- (void)selfConnectionBecameOnline:(NSNotification *)notification
{
    [self scheduleGroupsSyncIfNeeded];
}

- (void)networkRebootstrapCompleted:(NSNotification *)notification
{
    [self scheduleGroupsSyncIfNeeded];
}

- (void)friendConnectionStatusChangeNotification:(NSNotification *)notification
{
    OCTFriend *friend = notification.object;

    if (! friend || friend.connectionStatus == OCTToxConnectionStatusNone) {
        return;
    }

    [self resendPendingGroupInviteRequests];
}

// KHANDAQ (#15): shared content-based dedup for incoming group messages. Records (chat:peer:text)
// with a timestamp and returns YES if the same was already seen within a short window. Covers both
// the live groupMessage path and the history-sync insert path, which otherwise duplicate because the
// sender's retry loop re-sends the same text with different messageIds. In-memory (no cross-thread
// Realm reads). Tradeoff: a genuinely identical re-send by the same peer within the window is merged.
// KHANDAQ (#155): keyed by a STABLE senderKey (the sender pubkey where available) instead of the
// volatile peer_id — during connection flaps the peer_id churns or resolves to 0, which forked the
// dedup key and let the same message through (live copy + history-sync copies rendered 2-3x).
- (BOOL)isRecentDuplicateGroupMessageInChat:(OCTChat *)chat senderKey:(NSString *)senderKey text:(NSString *)text windowSeconds:(NSTimeInterval)windowSeconds
{
    if (chat.uniqueIdentifier.length == 0 || text.length == 0) {
        return NO;
    }

    NSString *dedupKey = [NSString stringWithFormat:@"%@:%@:%@", chat.uniqueIdentifier, senderKey ?: @"", text];
    NSTimeInterval nowTs = [[NSDate date] timeIntervalSince1970];

    @synchronized(self) {
        if (! self.recentGroupMessageSeenAt) {
            self.recentGroupMessageSeenAt = [NSMutableDictionary dictionary];
        }

        NSNumber *seenAt = self.recentGroupMessageSeenAt[dedupKey];
        BOOL duplicate = (seenAt != nil && (nowTs - seenAt.doubleValue) < windowSeconds);
        self.recentGroupMessageSeenAt[dedupKey] = @(nowTs);

        if (self.recentGroupMessageSeenAt.count > 256) {
            NSMutableArray<NSString *> *stale = [NSMutableArray array];
            [self.recentGroupMessageSeenAt enumerateKeysAndObjectsUsingBlock:^(NSString *key, NSNumber *ts, BOOL *stop) {
                if (nowTs - ts.doubleValue > 300.0) {
                    [stale addObject:key];
                }
            }];
            [self.recentGroupMessageSeenAt removeObjectsForKeys:stale];
        }

        return duplicate;
    }
}

- (void)tox:(OCTTox *)tox groupMessage:(NSString *)message
       type:(OCTToxMessageType)type
groupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
  messageId:(uint32_t)messageId
{
    uint32_t selfPeerId = [tox groupSelfPeerIdForGroupNumber:groupNumber error:nil];

    if (selfPeerId > 0 && peerId == selfPeerId) {
        return;
    }

    // KHANDAQ (#51): also drop our OWN message echoed back by matching the STABLE self pubkey. The
    // volatile selfPeerId lookup can momentarily return 0/wrong (just after (re)connect), which let an
    // echo of our own send appear as an incoming message attributed to ourselves ("Isa iOS · …") and
    // doubled the conversation. The pubkey comparison is reliable regardless of peer-id churn.
    NSString *selfPubkeyHex = [[tox groupSelfPublicKeyHexForGroupNumber:groupNumber error:nil] uppercaseString];
    NSString *senderPubkeyHex = [[tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil] uppercaseString];

    if (selfPubkeyHex.length > 0 && senderPubkeyHex.length > 0 && [selfPubkeyHex isEqualToString:senderPubkeyHex]) {
        return;
    }

    if ([self isBlockedIncomingPeerId:peerId groupNumber:groupNumber]) {
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager chatWithGroupNumber:groupNumber];

    if (! chat) {
        NSString *chatIdHex = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];
        chat = [realmManager getOrCreateGroupChatWithGroupNumber:groupNumber
                                                       chatIdHex:chatIdHex
                                                       groupName:nil
                                                    privacyState:OCTToxGroupPrivacyStatePublic];
    }

    // KHANDAQ: freeze the sender name resolved by STABLE pubkey (not the volatile peerId), so it stays
    // correct on the stored message even after this peer_id is later reused by someone else. Resolved
    // up-front because the persistent dedup below scopes by this same (stable) name.
    NSString *peerName = [self groupPeerNameByPubkeyForGroupNumber:groupNumber peerId:peerId chat:chat];

    if (peerName.length == 0) {
        peerName = [NSString stringWithFormat:@"Peer %u", peerId];
    }

    // KHANDAQ (#15): incoming group messages arrived duplicated (up to 4x) because the sender's
    // send-retry loop re-transmits the same text — each copy with a DIFFERENT messageId — AND the
    // same message also arrives via the history-sync path. messageId-based dedup misses these. Use a
    // shared in-memory content dedup (group+peer+text within a short window) covering BOTH paths.
    BOOL alreadyStored = [self isRecentDuplicateGroupMessageInChat:chat
                                                          senderKey:(senderPubkeyHex.length > 0 ? senderPubkeyHex : [NSString stringWithFormat:@"%u", peerId])
                                                               text:message
                                                      windowSeconds:15.0];

    // KHANDAQ (#88): the in-memory map above is EMPTY after an app restart and is keyed by the volatile
    // peer_id, so an Android delivery-retry storm (it re-sends the same text up to ~30s apart, each with
    // a different messageId) slipped past it and inserted ~9 copies. Also consult a PERSISTENT
    // content dedup scoped by the STABLE sender name (frozen groupPeerName) so already-stored copies are
    // caught regardless of restart / peer_id churn, WITHOUT eating an identical short message ("Да")
    // legitimately sent by a DIFFERENT peer. Window 45s covers the sender's full resync spread.
    if (! alreadyStored) {
        NSTimeInterval nowTs = [[NSDate date] timeIntervalSince1970];
        alreadyStored = [realmManager groupTextMessageExistsInChat:chat
                                                              text:message
                                                        senderName:peerName
                                                  nearDateInterval:nowTs
                                                     windowSeconds:45.0];
    }

    // KHANDAQ (#114): peer-agnostic dedup by (messageId + text). NGC relays/re-delivers the same logical
    // message with the SAME messageId but a DIFFERENT peerId, sometimes outside the content window above —
    // the stable catch is messageId + text. A legitimate repeat carries a new messageId, so this is safe.
    // (Sender retry storms use different messageIds and are still caught by the content dedup above.)
    if (! alreadyStored && messageId > 0) {
        alreadyStored = [realmManager groupTextMessageExistsInChat:chat
                                                         messageId:messageId
                                                              text:message];
    }

    if (! alreadyStored) {
        OCTMessageAbstract *stored = [realmManager addGroupMessageWithText:message
                                                                      type:type
                                                                      chat:chat
                                                                    peerId:peerId
                                                                  peerName:peerName
                                                                 messageId:messageId];
        // KHANDAQ (audit, round 5): freeze the author's STABLE key on the row, mirroring what #82 does
        // for file rows. Until now a text row recorded its author only as the frozen display name (a
        // nickname anyone can copy) plus the volatile peer id, so the 0x42/0x41 gates had nothing
        // unforgeable to authenticate against. This key comes from toxcore for the peer that actually
        // delivered the packet, so it cannot be claimed by another member.
        [self freezeGroupTextSenderPubkeyOnMessage:stored
                                senderPublicKeyHex:senderPubkeyHex
                                       groupNumber:groupNumber
                                            peerId:peerId
                                              chat:chat];
    }

    if (messageId > 0) {
        [self setupHistSyncIfNeeded];
        [self.histSync sendDeliveryReceiptForMessageId:messageId
                                           groupNumber:groupNumber
                                          senderPeerId:peerId];
    }
}

- (void)tox:(OCTTox *)tox groupConnectionStatusChanged:(int32_t)status
groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTLogInfo(@"NGC group connection group=%u status=%d", groupNumber, status);

    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat) {
        if (status > 0) {
            NSString *retryKey = [self groupJoinRetryKeyForChat:chat];

            if (retryKey.length > 0) {
                [self.groupJoinRetryRunning removeObject:retryKey];
                [self.groupJoinAttemptByKey removeObjectForKey:retryKey];
            }

            [self clearPendingFriendAssistedJoinForChatIdHex:chat.groupChatIdHex];
            [self flushPendingGroupMessagesForChat:chat];
        }
        else if (status <= 0) {
            [tox groupReconnectWithGroupNumber:groupNumber error:nil];
        }

        [self refreshPeersForChat:chat];

        if (status > 0) {
            [self setupHistSyncIfNeeded];
            [self.histSync handleGroupConnectedWithGroupNumber:groupNumber];
            [self setupHskAnnounceIfNeeded];
            [self.hskAnnounce announceToGroupNumber:groupNumber force:NO];
        }

        [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTGroupConnectionStatusChangeNotification
                                                                        object:nil
                                                                      userInfo:@{
            kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey: chat.uniqueIdentifier,
            @"connected": @(status > 0),
        }];
    }
}

- (void)tox:(OCTTox *)tox groupPeerJoinWithGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];
    uint32_t selfPeerId = [tox groupSelfPeerIdForGroupNumber:groupNumber error:nil];

    if (chat) {
        // KHANDAQ: capture whether this pubkey was ALREADY a known member (persisted from a prior
        // session, peerLastSeenDateInterval > 0) BEFORE we stamp lastSeen below — so a long-time member
        // who merely reconnects / flaps online does not re-announce "joined", even after an app restart
        // (the in-memory set alone resets on restart and let the spam back in).
        NSString *joinPubkey = [tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil];
        BOOL peerKnownBefore = NO;
        if (joinPubkey.length > 0) {
            for (OCTGroupPeer *p in [[self.dataSource managerGetRealmManager] groupPeersForChatUniqueIdentifier:chat.uniqueIdentifier]) {
                if (p.peerPublicKeyHex.length > 0
                        && [p.peerPublicKeyHex caseInsensitiveCompare:joinPubkey] == NSOrderedSame
                        && p.peerLastSeenDateInterval > 0) {
                    peerKnownBefore = YES;
                    break;
                }
            }
        }

        [[self.dataSource managerGetRealmManager] updateGroupPeerLastSeenDateInterval:[[NSDate date] timeIntervalSince1970]
                                                                              forChat:chat
                                                                               peerId:peerId];
        [self refreshPeersForChat:chat];

        uint32_t peerCount = [tox groupPeerCountForGroupNumber:groupNumber error:nil];

        if (peerCount > 1) {
            [self clearPendingFriendAssistedJoinForChatIdHex:chat.groupChatIdHex];
        }

        if (selfPeerId == 0 || peerId != selfPeerId) {
            // Announce a peer's FIRST-ever join only: not already known from a prior session (persistent),
            // and not already announced this session (in-memory). Fall back to the timed exit-suppression
            // when the pubkey can't be resolved.
            BOOL firstAnnounce;
            if (joinPubkey.length > 0) {
                NSString *pubkeyLower = joinPubkey.lowercaseString;
                NSString *announceKey = [NSString stringWithFormat:@"%@:%@", chat.uniqueIdentifier, pubkeyLower];
                // KHANDAQ (#87): announce a pubkey's join exactly ONCE per chat, ever. Suppress if it was
                // a known member last session (peerKnownBefore), already announced this session
                // (in-memory), OR ever announced before (persistent — survives peer-row pruning on flaps
                // and app restarts). Without the persistent check, every reconnect of a volatile peer_id
                // re-announced the same long-time members and spammed the chat.
                BOOL knownAlready = peerKnownBefore
                        || [self.groupAnnouncedJoinPubkeys containsObject:announceKey]
                        || [self hasPersistentlyAnnouncedJoinForChat:chat pubkeyLower:pubkeyLower];
                firstAnnounce = ! knownAlready;
                [self.groupAnnouncedJoinPubkeys addObject:announceKey];
                [self markPersistentlyAnnouncedJoinForChat:chat pubkeyLower:pubkeyLower];
            }
            else {
                firstAnnounce = ! [self shouldSuppressGroupPeerEventForChat:chat peerId:peerId];
            }
            if (firstAnnounce) {
                NSString *displayName = [self peerDisplayNameForGroupNumber:groupNumber peerId:peerId];
                [self addGroupSystemMessageWithFormatKey:@"group_system_peer_joined" argument:displayName toChat:chat];
            }
        }
    }

    [self setupHistSyncIfNeeded];
    [self.histSync handlePeerJoinedWithGroupNumber:groupNumber peerId:peerId];
    // KHANDAQ (audit #2 finding 1): the arriving peer can verify nothing we wrote until it
    // has our signing key, and our own connect-time announcement went out before it existed.
    [self setupHskAnnounceIfNeeded];
    [self.hskAnnounce announceForNewPeerWithGroupNumber:groupNumber peerId:peerId];

    OCTLogInfo(@"NGC group peer join group=%u peer=%u", groupNumber, peerId);
}

- (void)tox:(OCTTox *)tox groupPeerNameUpdate:(NSString *)name
groupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager chatWithGroupNumber:groupNumber];

    if (chat) {
        NSString *previousName = [[self.dataSource managerGetRealmManager] groupPeerForChat:chat peerId:peerId].peerName;
        [realmManager upsertGroupPeerForChat:chat peerId:peerId peerName:name];

        uint32_t selfPeerId = [tox groupSelfPeerIdForGroupNumber:groupNumber error:nil];

        if ((selfPeerId == 0 || peerId != selfPeerId) &&
            ! [self shouldSuppressGroupPeerEventForChat:chat peerId:peerId]) {
            NSString *displayName = name.length > 0 ? name : [self peerDisplayNameForGroupNumber:groupNumber peerId:peerId];

            if (previousName.length > 0 && ![previousName isEqualToString:displayName]) {
                [self addGroupSystemMessageWithFormatKey:@"group_system_peer_renamed" argument:displayName toChat:chat];
            }
        }
    }

    OCTLogInfo(@"NGC group peer name group=%u peer=%u name=%@", groupNumber, peerId, name);
}

- (void)tox:(OCTTox *)tox groupInviteFromFriendNumber:(OCTToxFriendNumber)friendNumber
 inviteData:(NSData *)inviteData
  groupName:(NSString *)groupName
{
    if ([self shouldIgnoreGroupInvite:inviteData tox:tox]) {
        OCTLogInfo(@"NGC group invite ignored friend=%d groupName=%@", friendNumber, groupName);
        return;
    }

    OCTLogInfo(@"NGC group invite friend=%d groupName=%@ bytes=%lu",
               friendNumber, groupName, (unsigned long)inviteData.length);

    __weak typeof(self) weakSelf = self;

    dispatch_async(dispatch_get_main_queue(), ^{
        __strong typeof(weakSelf) self = weakSelf;

        if (! self) {
            return;
        }

        id<OCTSubmanagerGroupsDelegate> delegate = self.delegate;

        if ([delegate respondsToSelector:@selector(submanagerGroups:didReceiveInviteFromFriendNumber:inviteData:groupName:)]) {
            [delegate submanagerGroups:self
    didReceiveInviteFromFriendNumber:friendNumber
                            inviteData:inviteData
                             groupName:groupName];
        }
    });
}

- (void)tox:(OCTTox *)tox friendGroupInviteRequestFromFriendNumber:(OCTToxFriendNumber)friendNumber
 chatIdData:(NSData *)chatIdData
{
    [self handleFriendGroupInviteRequestFromFriendNumber:friendNumber chatIdData:chatIdData];
}

- (void)tox:(OCTTox *)tox groupPeerExitWithGroupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
   exitType:(OCTToxGroupExitType)exitType
       name:(NSString *)name
partMessage:(NSString *)partMessage
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];
    uint32_t selfPeerId = [tox groupSelfPeerIdForGroupNumber:groupNumber error:nil];

    if (chat) {
        [[self.dataSource managerGetRealmManager] updateGroupPeerLastSeenDateInterval:[[NSDate date] timeIntervalSince1970]
                                                                              forChat:chat
                                                                               peerId:peerId];

        if (exitType == OCTToxGroupExitTypeTimeout || exitType == OCTToxGroupExitTypeDisconnected) {
            [self markGroupPeerReconnectSuppressForChat:chat peerId:peerId];
            [self refreshPeersForChat:chat];
            OCTLogInfo(@"NGC group peer exit (reconnect noise) group=%u peer=%u type=%ld name=%@",
                       groupNumber, peerId, (long)exitType, name);
            return;
        }

        [self refreshPeersForChat:chat];

        if (selfPeerId == 0 || peerId != selfPeerId) {
            NSString *displayName = name.length > 0 ? name : [NSString stringWithFormat:@"Peer %u", peerId];
            NSString *formatKey = exitType == OCTToxGroupExitTypeKick ? @"group_system_peer_kicked" : @"group_system_peer_left";
            [self addGroupSystemMessageWithFormatKey:formatKey argument:displayName toChat:chat];
        }
    }

    OCTLogInfo(@"NGC group peer exit group=%u peer=%u type=%ld name=%@",
               groupNumber, peerId, (long)exitType, name);
}

// KHANDAQ (audit, round 5): shared author test for the 0x42 retract / 0x41 edit gates.
//
// A row that RECORDS its author (frozen stable pubkey) is bound STRICTLY to that key — nothing else
// unlocks it. A row that records NO author keeps the pre-existing, weaker rule, because "no recorded
// author" does not mean "attacker": the author key only started being frozen on file rows in this
// batch, so EVERY media row a live user received through history sync before upgrading carries none.
// Refusing those outright made the sender's own, legitimate "delete for everyone" a silent no-op
// forever — a regression against shipped behaviour that hurts honest users, which no forgery vector
// justifies. Legacy rows therefore stay exactly as retractable as they are today; only new rows get
// the strict binding. An exact author match always wins over the legacy fallback.
- (nullable OCTMessageAbstract *)groupFileRowForRetractionInResults:(RLMResults<OCTMessageAbstract *> *)results
                                                        senderPubkey:(NSString *)senderPubkey
{
    OCTMessageAbstract *legacy = nil;

    for (OCTMessageAbstract *candidate in results) {
        // KHANDAQ (audit, round 6): OUR OWN outgoing media is never retractable by a remote peer, so it
        // is out of this gate entirely. The local row is created with peerId 0 and gets NO
        // groupSenderPubkey (only the incoming handlers freeze a sender), so on a fresh install it is
        // author-less — and the legacy fallback below would then hand it to whoever echoes our own
        // groupMsgIdHashHex back at us, which every group member can read off our file packets. We
        // retract our own messages locally (deleteGroupMessageForBoth), never through here. Same test
        // as -isOutgoing: peerId 0 alone is not "ours", a history-synced row carries 0 whenever its
        // author was unresolvable at insert time, and those rows keep the legacy behaviour below.
        // (The TEXT branch of this gate already excludes them at the predicate: groupSenderPeerId != 0.)
        if (candidate.groupSenderPeerId == 0 && ! candidate.groupHistorySync) {
            continue;
        }

        NSString *rowPubkey = candidate.messageFile.groupSenderPubkey;

        if (rowPubkey.length == 0) {
            if (! legacy) {
                legacy = candidate; // pre-batch row: no author on file, behave as before
            }
            continue;
        }

        if (senderPubkey.length > 0 && [rowPubkey caseInsensitiveCompare:senderPubkey] == NSOrderedSame) {
            return candidate;
        }
    }

    return legacy;
}

// KHANDAQ (audit, round 5): TEXT counterpart of the above. The old rule matched the requester's
// CURRENT display name against the name frozen on the row, and a display name is neither unique nor
// authenticated — any member who renamed itself to the victim's nickname could retract (or edit) the
// victim's messages. Bind to the author pubkey frozen on the row instead (groupPrivatePeerPubkey; see
// its header note), and keep the name rule ONLY for rows that carry no frozen key, i.e. every row
// written before this build. peerName may legitimately be empty (author no longer resolvable) — then
// only an authored row can match, which is strictly better than the old "refuse everything".
- (nullable OCTMessageAbstract *)groupTextRowForRetractionInResults:(RLMResults<OCTMessageAbstract *> *)results
                                                        senderPubkey:(NSString *)senderPubkey
                                                            peerName:(NSString *)peerName
{
    OCTMessageAbstract *legacy = nil;

    for (OCTMessageAbstract *candidate in results) {
        NSString *rowPubkey = candidate.groupPrivatePeerPubkey;

        if (rowPubkey.length > 0) {
            if (senderPubkey.length > 0 && [rowPubkey caseInsensitiveCompare:senderPubkey] == NSOrderedSame) {
                return candidate;
            }
            continue; // author on file and it is not the requester — a matching name proves nothing
        }

        if (! legacy && peerName.length > 0 && [candidate.messageText.groupPeerName isEqualToString:peerName]) {
            legacy = candidate; // pre-batch row: no author on file, behave as before
        }
    }

    return legacy;
}

// KHANDAQ (#179): delete-for-both broadcast (KQ family, mirrors Android HelperMessageDelete):
// [0..5]=0x667788113435 [6]=ver(1) [7]=0x42 [8..11]=message_id u32 BE [12..15]=ts u32 BE.
// Returns YES when the packet was a delete (consumed), regardless of whether a row matched.
- (BOOL)handleIncomingGroupDeletePacketWithGroupNumber:(OCTToxGroupNumber)groupNumber
                                                peerId:(uint32_t)peerId
                                                  data:(NSData *)data
{
    // TEXT delete = 16 bytes (message_id at [8..11]); FILE delete = 45 bytes (b[8]=0x02 anchor_type,
    // full 32-byte groupMsgIdHashHex at [9..40], ts at [41..44]).
    if (data.length != 16 && data.length != 45) {
        return NO;
    }
    const uint8_t *b = data.bytes;
    if (b[0] != 0x66 || b[1] != 0x77 || b[2] != 0x88 || b[3] != 0x11 || b[4] != 0x34 || b[5] != 0x35
        || b[6] != 0x01 || b[7] != 0x42) {
        return NO;
    }
    const BOOL isFile = (data.length == 45 && b[8] == 0x02);

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager chatWithGroupNumber:groupNumber];
    if (! chat) {
        return YES;
    }

    // the requester's STABLE identity, resolved once for both branches (empty when toxcore can no
    // longer map the peer id — then only the legacy, author-less rows below can still match).
    // KHANDAQ (audit, round 7): use the SAME two-source resolver the stamping side uses. Every group
    // callback hops to the main queue before we run, so by now toxcore may already have dropped the peer
    // id and the direct lookup answers nothing. Stamping then falls back to the roster while checking did
    // not — so in that window an honest author's own "delete for everyone" resolved to an empty key,
    // failed to match the row it had itself authored, and silently no-opped on that recipient while
    // succeeding on the others: a half-deleted message. The roster is consulted only when no live peer
    // holds the id (a recycled id is by definition known to toxcore), so it cannot widen the gate.
    NSString *senderPubkey = [self groupStableSenderPubkeyHexForGroupNumber:groupNumber peerId:peerId chat:chat];

    OCTMessageAbstract *found = nil;
    if (isFile) {
        // KHANDAQ (audit): the groupMsgIdHashHex identifies the TRANSFER, not its author, and it rides in
        // the clear in every group file packet — matching on the hash alone let ANY member retract
        // anybody's media. Bind the retraction to the stable sender key frozen on the row (#82 live path
        // / audit F-6 history-sync path both stamp it) when the row has one.
        NSMutableString *hashHex = [NSMutableString stringWithCapacity:64];
        for (int i = 9; i < 41; i++) {
            [hashHex appendFormat:@"%02x", b[i]];
        }
        NSPredicate *predicate = [NSPredicate predicateWithFormat:
                                  @"chatUniqueIdentifier == %@ AND messageFile.groupMsgIdHashHex ==[c] %@",
                                  chat.uniqueIdentifier, hashHex];
        // scan, don't take firstObject: a payload refused because its sender did not match is filed as
        // ITS own row under the same hash (audit F-5), so the first row may be a stranger's.
        found = [self groupFileRowForRetractionInResults:[realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate]
                                             senderPubkey:senderPubkey];
    }
    else {
        const uint32_t messageId = ((uint32_t)b[8] << 24) | ((uint32_t)b[9] << 16)
                                   | ((uint32_t)b[10] << 8) | (uint32_t)b[11];
        if (messageId == 0) {
            return YES;
        }
        // authenticate by the sender's STABLE identity (the frozen peer NAME this used to match on is a
        // nickname: any member could rename itself to the victim's name and retract the victim's texts).
        // The name is still resolved, but only as the fallback for rows written before the key existed.
        NSString *peerName = [self groupPeerNameByPubkeyForGroupNumber:groupNumber peerId:peerId chat:chat];
        if (peerName.length == 0 && senderPubkey.length == 0) {
            return YES;
        }
        NSPredicate *predicate = [NSPredicate predicateWithFormat:
                                  @"chatUniqueIdentifier == %@ AND messageText.messageId == %d AND groupSenderPeerId != 0",
                                  chat.uniqueIdentifier, (int32_t)messageId];
        found = [self groupTextRowForRetractionInResults:[realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate]
                                             senderPubkey:senderPubkey
                                                 peerName:peerName];
    }
    if (found) {
        OCTLogInfo(@"group delete: removing retracted %@ message", isFile ? @"file" : @"text");
        [realmManager removeMessages:@[found]];
    }
    (void)tox;
    return YES;
}

// KHANDAQ (#192): reactions JSON model [{"e":emoji,"p":[actors]}] shared with the 1:1 path
// (duplicated here because the 1:1 helpers are file-static in OCTSubmanagerChatsImpl.m).
static NSString *const kKQGroupOwnReactionMarker = @"-OWN-";

static NSString *kqGroupReactionsApplyActor(NSString *reactionsJSON, NSString *actor, NSString *emoji, BOOL add)
{
    NSArray *arr = nil;
    if (reactionsJSON.length > 0) {
        arr = [NSJSONSerialization JSONObjectWithData:[reactionsJSON dataUsingEncoding:NSUTF8StringEncoding]
                                              options:0 error:nil];
    }
    if (! [arr isKindOfClass:[NSArray class]]) {
        arr = @[];
    }
    NSMutableArray *out = [NSMutableArray new];
    NSMutableDictionary *target = nil;
    for (NSDictionary *entry in arr) {
        if (! [entry isKindOfClass:[NSDictionary class]] || ! [entry[@"p"] isKindOfClass:[NSArray class]]) {
            continue;
        }
        NSMutableArray *kept = [NSMutableArray new];
        for (NSString *a in entry[@"p"]) {
            if (! [actor isEqualToString:a]) {
                [kept addObject:a];
            }
        }
        if (kept.count > 0) {
            NSMutableDictionary *ne = [@{ @"e" : entry[@"e"] ?: @"", @"p" : kept } mutableCopy];
            [out addObject:ne];
            if (add && [emoji isEqualToString:entry[@"e"]]) {
                target = ne;
            }
        }
    }
    if (add) {
        if (target == nil) {
            if (out.count >= 12) {
                goto serialize;
            }
            target = [@{ @"e" : emoji, @"p" : [NSMutableArray new] } mutableCopy];
            [out addObject:target];
        }
        NSMutableArray *actors = target[@"p"];
        if (actors.count < 128) {
            [actors addObject:actor];
        }
    }
serialize:
    if (out.count == 0) {
        return nil;
    }
    NSData *d = [NSJSONSerialization dataWithJSONObject:out options:0 error:nil];
    return d ? [[NSString alloc] initWithData:d encoding:NSUTF8StringEncoding] : nil;
}

static NSString *kqGroupReactionsActorEmoji(NSString *reactionsJSON, NSString *actor)
{
    if (reactionsJSON.length == 0) {
        return nil;
    }
    NSArray *arr = [NSJSONSerialization JSONObjectWithData:[reactionsJSON dataUsingEncoding:NSUTF8StringEncoding]
                                                   options:0 error:nil];
    if (! [arr isKindOfClass:[NSArray class]]) {
        return nil;
    }
    for (NSDictionary *entry in arr) {
        if ([entry[@"p"] isKindOfClass:[NSArray class]] && [entry[@"p"] containsObject:actor]) {
            return entry[@"e"];
        }
    }
    return nil;
}

// Resolve a peer's frozen display name from a pubkey hex (reactor targets the author BY pubkey;
// iOS text rows carry only the frozen groupPeerName, so we map pubkey -> name via the peer list).
- (nullable NSString *)groupPeerNameForPubkeyHex:(NSString *)pubkeyHex chat:(OCTChat *)chat
{
    if (pubkeyHex.length == 0 || ! chat) {
        return nil;
    }
    RLMResults *peers = [[self.dataSource managerGetRealmManager] groupPeersForChatUniqueIdentifier:chat.uniqueIdentifier];
    for (OCTGroupPeer *peer in peers) {
        if (peer.peerPublicKeyHex.length > 0
            && [peer.peerPublicKeyHex caseInsensitiveCompare:pubkeyHex] == NSOrderedSame
            && peer.peerName.length > 0) {
            return peer.peerName;
        }
    }
    return nil;
}

// KHANDAQ (#192): incoming reaction broadcast (KQ NGC family):
// [0..5]=magic [6]=0x01 [7]=0x43 [8]=anchor_type(1=text message_id, 2=file groupMsgIdHashHex)
// [9..40]=anchor (type 1: msg_id in first 4 bytes BE, zero-padded; type 2: full 32-byte hash)
// [41..72]=author pubkey 32B (unused for type 2) [73..76]=ts u32 BE [77]=action [78]=emojiLen
// [79..]=emoji. Returns YES when the packet was a reaction (consumed).
- (BOOL)handleIncomingGroupReactionPacketWithGroupNumber:(OCTToxGroupNumber)groupNumber
                                                  peerId:(uint32_t)peerId
                                                    data:(NSData *)data
{
    if (data.length < 80) {
        return NO;
    }
    const uint8_t *b = data.bytes;
    if (b[0] != 0x66 || b[1] != 0x77 || b[2] != 0x88 || b[3] != 0x11 || b[4] != 0x34 || b[5] != 0x35
        || b[6] != 0x01 || b[7] != 0x43) {
        return NO;
    }
    uint8_t anchorType = b[8];
    BOOL add = (b[77] == 1);
    NSUInteger emLen = b[78];
    if (emLen < 1 || emLen > 16 || data.length < 79 + emLen) {
        return YES; // malformed, but it WAS a reaction packet — consume it
    }
    if (anchorType != 1 && anchorType != 2) {
        return YES; // unknown anchor kind
    }
    NSString *emoji = [[NSString alloc] initWithBytes:(b + 79) length:emLen encoding:NSUTF8StringEncoding];
    if (add && emoji.length == 0) {
        return YES;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager chatWithGroupNumber:groupNumber];
    if (! chat) {
        return YES;
    }

    OCTMessageAbstract *found = nil;
    if (anchorType == 2) {
        // file / media / voice: anchor is the 32-byte groupMsgIdHashHex (globally unique & symmetric,
        // so no author disambiguation needed — the hash alone identifies the transfer).
        NSMutableString *hashHex = [NSMutableString stringWithCapacity:64];
        for (int i = 9; i < 41; i++) {
            [hashHex appendFormat:@"%02x", b[i]];
        }
        NSPredicate *predicate = [NSPredicate predicateWithFormat:
                     @"chatUniqueIdentifier == %@ AND messageFile.groupMsgIdHashHex ==[c] %@",
                     chat.uniqueIdentifier, hashHex];
        found = [[realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate] firstObject];
    }
    else {
        const uint32_t messageId = ((uint32_t)b[9] << 24) | ((uint32_t)b[10] << 16)
                                   | ((uint32_t)b[11] << 8) | (uint32_t)b[12];
        NSMutableString *authorHex = [NSMutableString stringWithCapacity:64];
        for (int i = 41; i < 73; i++) {
            [authorHex appendFormat:@"%02x", b[i]];
        }

        NSString *selfHex = [tox groupSelfPublicKeyHexForGroupNumber:groupNumber error:nil];
        NSPredicate *predicate;
        if (selfHex.length > 0 && [selfHex caseInsensitiveCompare:authorHex] == NSOrderedSame) {
            // reaction targets an OWN (outgoing) message
            predicate = [NSPredicate predicateWithFormat:
                         @"chatUniqueIdentifier == %@ AND messageText.messageId == %d AND groupSenderPeerId == 0",
                         chat.uniqueIdentifier, (int32_t)messageId];
        }
        else {
            NSString *authorName = [self groupPeerNameForPubkeyHex:authorHex chat:chat];
            if (authorName.length == 0) {
                return YES;
            }
            predicate = [NSPredicate predicateWithFormat:
                         @"chatUniqueIdentifier == %@ AND messageText.messageId == %d AND messageText.groupPeerName == %@ AND groupSenderPeerId != 0",
                         chat.uniqueIdentifier, (int32_t)messageId, authorName];
        }
        found = [[realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate] firstObject];
    }
    if (! found) {
        return YES; // reactions never create rows for unknown originals
    }

    NSString *reactorHex = [[tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil] uppercaseString];
    if (reactorHex.length == 0) {
        return YES;
    }
    NSString *newJson = kqGroupReactionsApplyActor(found.reactionsJSON, reactorHex, emoji, add);
    [realmManager updateObject:found withBlock:^(OCTMessageAbstract *msg) {
        msg.reactionsJSON = newJson;
    }];
    return YES;
}

- (void)toggleReactionOnGroupMessage:(OCTMessageAbstract *)message
                               emoji:(NSString *)emoji
                              inChat:(OCTChat *)chat
{
    if (! message || (! message.messageText && ! message.messageFile) || emoji.length == 0
        || [emoji lengthOfBytesUsingEncoding:NSUTF8StringEncoding] > 16 || ! chat) {
        return;
    }
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *cur = kqGroupReactionsActorEmoji(message.reactionsJSON, kKQGroupOwnReactionMarker);
    BOOL add = ! [emoji isEqualToString:cur];
    NSString *newJson = kqGroupReactionsApplyActor(message.reactionsJSON, kKQGroupOwnReactionMarker, emoji, add);
    [realmManager updateObject:message withBlock:^(OCTMessageAbstract *msg) {
        msg.reactionsJSON = newJson;
    }];

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
    NSString *own = kqGroupReactionsActorEmoji(message.reactionsJSON, kKQGroupOwnReactionMarker);

    // FILE / media / voice: anchor on the globally-unique groupMsgIdHashHex (type 2). No author
    // resolution needed — the hash identifies the transfer for every peer.
    if (message.messageFile) {
        NSString *hashHex = message.messageFile.groupMsgIdHashHex;
        if (hashHex.length != 64) {
            return; // no shared file anchor yet (reaction still stored locally above)
        }
        NSData *pkt = [self kqGroupReactionPacketForMsgIdHash:hashHex
                                                        emoji:(own ?: @"*")
                                                          add:(own != nil)];
        if (pkt) {
            [tox groupSendCustomPacket:pkt groupNumber:groupNumber lossless:YES error:nil];
        }
        return;
    }

    // resolve the message AUTHOR's pubkey (for the wire anchor). Outgoing -> self; incoming ->
    // the peer whose frozen name matches, or the volatile peerId as a fallback.
    int32_t messageId = (int32_t)message.messageText.messageId;
    if (messageId == 0) {
        return; // pending send has no shared id yet
    }
    NSString *authorHex = nil;
    if (message.groupSenderPeerId == 0) {
        authorHex = [tox groupSelfPublicKeyHexForGroupNumber:groupNumber error:nil];
    }
    else {
        NSString *authorName = message.messageText.groupPeerName;
        if (authorName.length > 0) {
            RLMResults *peers = [realmManager groupPeersForChatUniqueIdentifier:chat.uniqueIdentifier];
            for (OCTGroupPeer *peer in peers) {
                if ([peer.peerName isEqualToString:authorName] && peer.peerPublicKeyHex.length > 0) {
                    authorHex = peer.peerPublicKeyHex;
                    break;
                }
            }
        }
        if (authorHex.length == 0) {
            authorHex = [tox groupPeerPublicKeyHexForGroupNumber:groupNumber
                                                          peerId:(uint32_t)message.groupSenderPeerId error:nil];
        }
    }
    if (authorHex.length != 64) {
        return; // cannot address the author -> local-only reaction (still stored above)
    }

    NSData *pkt = [self kqGroupReactionPacketForMessageId:messageId
                                               authorHex:authorHex
                                                   emoji:(own ?: @"*")
                                                     add:(own != nil)];
    if (pkt) {
        [tox groupSendCustomPacket:pkt groupNumber:groupNumber lossless:YES error:nil];
    }
}

- (NSData *)kqGroupReactionPacketForMessageId:(int32_t)messageId
                                    authorHex:(NSString *)authorHex
                                        emoji:(NSString *)emoji
                                          add:(BOOL)add
{
    NSData *em = [emoji dataUsingEncoding:NSUTF8StringEncoding];
    if (authorHex.length != 64 || em.length < 1 || em.length > 16) {
        return nil;
    }
    NSMutableData *pkt = [NSMutableData dataWithCapacity:79 + em.length];
    const uint8_t header[9] = { 0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x43, 0x01 }; // ..anchor_type 1
    [pkt appendBytes:header length:9];
    // anchor: message_id (4 bytes BE) zero-padded to 32
    uint32_t mid = (uint32_t)messageId;
    const uint8_t midbe[4] = {
        (uint8_t)((mid >> 24) & 0xFF), (uint8_t)((mid >> 16) & 0xFF),
        (uint8_t)((mid >> 8) & 0xFF), (uint8_t)(mid & 0xFF)
    };
    [pkt appendBytes:midbe length:4];
    const uint8_t zeros[28] = {0};
    [pkt appendBytes:zeros length:28];
    // author pubkey 32 bytes
    for (int i = 0; i < 64; i += 2) {
        unsigned int v = 0;
        [[NSScanner scannerWithString:[authorHex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&v];
        uint8_t byte = (uint8_t)v;
        [pkt appendBytes:&byte length:1];
    }
    uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970];
    const uint8_t tsbe[4] = {
        (uint8_t)((ts >> 24) & 0xFF), (uint8_t)((ts >> 16) & 0xFF),
        (uint8_t)((ts >> 8) & 0xFF), (uint8_t)(ts & 0xFF)
    };
    [pkt appendBytes:tsbe length:4];
    const uint8_t action = add ? 1 : 0;
    [pkt appendBytes:&action length:1];
    const uint8_t emLen = (uint8_t)em.length;
    [pkt appendBytes:&emLen length:1];
    [pkt appendData:em];
    return pkt;
}

// KHANDAQ (#192): file/media/voice reaction packet — anchor_type 2, the full 32-byte
// groupMsgIdHashHex sits in bytes[9..40]; the author-pubkey slot [41..72] is unused (the hash is
// globally unique) so it stays zero. Layout otherwise identical to the message-id variant.
- (NSData *)kqGroupReactionPacketForMsgIdHash:(NSString *)msgIdHashHex
                                        emoji:(NSString *)emoji
                                          add:(BOOL)add
{
    NSData *em = [emoji dataUsingEncoding:NSUTF8StringEncoding];
    if (msgIdHashHex.length != 64 || em.length < 1 || em.length > 16) {
        return nil;
    }
    NSMutableData *pkt = [NSMutableData dataWithCapacity:79 + em.length];
    const uint8_t header[9] = { 0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x43, 0x02 }; // anchor_type 2
    [pkt appendBytes:header length:9];
    // anchor: 32-byte hash decoded from hex
    for (int i = 0; i < 64; i += 2) {
        unsigned int v = 0;
        [[NSScanner scannerWithString:[msgIdHashHex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&v];
        uint8_t byte = (uint8_t)v;
        [pkt appendBytes:&byte length:1];
    }
    // author pubkey slot [41..72] — unused for file anchors, left zeroed
    const uint8_t zeros[32] = {0};
    [pkt appendBytes:zeros length:32];
    uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970];
    const uint8_t tsbe[4] = {
        (uint8_t)((ts >> 24) & 0xFF), (uint8_t)((ts >> 16) & 0xFF),
        (uint8_t)((ts >> 8) & 0xFF), (uint8_t)(ts & 0xFF)
    };
    [pkt appendBytes:tsbe length:4];
    const uint8_t action = add ? 1 : 0;
    [pkt appendBytes:&action length:1];
    const uint8_t emLen = (uint8_t)em.length;
    [pkt appendBytes:&emLen length:1];
    [pkt appendData:em];
    return pkt;
}

// KHANDAQ (#193/#179): SEND side of group delete-for-both (the RECEIVE side already exists at
// handleIncomingGroupDeletePacketWithGroupNumber). Broadcasts the 16-byte 0x42 packet, byte-compatible
// with Android's HelperMessageDelete.buildGroupDeletePacket. TEXT-only + own message (a file variant
// needs a 32-byte-anchor packet on both platforms). Local removal always happens.
- (void)deleteGroupMessageForBoth:(OCTMessageAbstract *)message inChat:(OCTChat *)chat
{
    do {
        // KHANDAQ (audit F-6): peerId 0 alone is not "ours" — a history-synced row carries 0 whenever
        // its author was unresolvable at insert time, and retracting it would broadcast a 0x42 for
        // someone else's message under OUR key. Local delete still works via the caller's else branch.
        //
        // KHANDAQ (audit, round 5): kept as-is after re-checking the "lost my DB, rejoined, my own
        // messages came back through history sync" case — it cannot produce a row here. A relaying peer
        // only re-serves rows it received LIVE (OCTNgcGroupHistSync buildSyncMessagePacket... returns nil
        // for groupHistorySync rows) and stamps them with the original author's key, and our own receive
        // side drops any sync packet carrying our own pubkey (handleIncomingSyncMessage/File). So a row
        // we authored never gets re-inserted as a synced row while our identity is unchanged; if the
        // identity DID change, the message is not ours to retract for others anyway. Nothing an honest
        // user can still edit/retract is lost — the UI does not even offer these actions on such a row,
        // since -isOutgoing is NO for it.
        if (! message || ! chat || message.groupSenderPeerId != 0 || message.groupHistorySync) { // own outgoing only
            break;
        }
        OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
        uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970];
        const uint8_t tsbe[4] = {
            (uint8_t)((ts >> 24) & 0xFF), (uint8_t)((ts >> 16) & 0xFF),
            (uint8_t)((ts >> 8) & 0xFF), (uint8_t)(ts & 0xFF)
        };
        NSMutableData *pkt = nil;

        if (message.messageFile) {
            // FILE/media/voice: 0x42 anchor_type 2, full 32-byte groupMsgIdHashHex in bytes[9..40].
            NSString *hashHex = message.messageFile.groupMsgIdHashHex;
            if (hashHex.length != 64) {
                break; // no shared file anchor yet
            }
            pkt = [NSMutableData dataWithCapacity:45];
            const uint8_t header[9] = { 0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x42, 0x02 };
            [pkt appendBytes:header length:9];
            for (int i = 0; i < 64; i += 2) {
                unsigned int v = 0;
                [[NSScanner scannerWithString:[hashHex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&v];
                uint8_t byte = (uint8_t)v;
                [pkt appendBytes:&byte length:1];
            }
            [pkt appendBytes:tsbe length:4];
        }
        else if (message.messageText) {
            // TEXT: 16-byte packet, message_id at [8..11] (implicit anchor_type 1).
            int32_t messageId = (int32_t)message.messageText.messageId;
            if (messageId == 0) {
                break; // pending send has no shared id yet
            }
            pkt = [NSMutableData dataWithCapacity:16];
            const uint8_t header[8] = { 0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x42 };
            [pkt appendBytes:header length:8];
            uint32_t mid = (uint32_t)messageId;
            const uint8_t midbe[4] = {
                (uint8_t)((mid >> 24) & 0xFF), (uint8_t)((mid >> 16) & 0xFF),
                (uint8_t)((mid >> 8) & 0xFF), (uint8_t)(mid & 0xFF)
            };
            [pkt appendBytes:midbe length:4];
            [pkt appendBytes:tsbe length:4];
        }

        if (pkt) {
            [[self.dataSource managerGetTox] groupSendCustomPacket:pkt groupNumber:groupNumber lossless:YES error:nil];
        }
    }
    while (0);

    // local deletion always happens — even if the message can't be retracted for everyone
    [[self.dataSource managerGetRealmManager] removeMessages:@[message]];
}

// KHANDAQ (#208): NGC group message EDIT (custom pkt 0x41, byte-parity with Android
// HelperMessageEdit.buildGroupEditPacket). 16-byte header: [0..5]=magic [6]=ver(1) [7]=0x41
// [8..11]=original message_id u32 BE [12..15]=edit-ts u32 BE [16..]=new text UTF-8. TEXT-only +
// own message (addressed by group+message_id+frozen author name, like the 0x42 text delete).
- (void)editGroupMessage:(OCTMessageAbstract *)message inChat:(OCTChat *)chat newText:(NSString *)newText
{
    // KHANDAQ (audit F-6): same "peerId 0 is not proof it is ours" narrowing as the 0x42 retract above,
    // and kept for the same reason (see the round-5 note there: our own messages never come back as
    // history-synced rows, so this bail costs an honest user nothing).
    if (! message || ! chat || message.groupSenderPeerId != 0 || message.groupHistorySync || message.messageText == nil) {
        return; // own outgoing text only
    }
    int32_t messageId = (int32_t)message.messageText.messageId;
    if (messageId == 0) {
        return; // pending send has no shared id yet
    }
    NSString *trimmed = [newText stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if (trimmed.length == 0) {
        return;
    }
    uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970];

    // apply locally first (Realm notification refreshes the row)
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    [realmManager updateObject:message withBlock:^(OCTMessageAbstract *m) {
        m.messageText.text = trimmed;
        m.edited = YES;
        m.editedTimestamp = (NSTimeInterval)ts;
    }];

    NSMutableData *pkt = [NSMutableData dataWithCapacity:16 + trimmed.length];
    const uint8_t header[8] = { 0x66, 0x77, 0x88, 0x11, 0x34, 0x35, 0x01, 0x41 };
    [pkt appendBytes:header length:8];
    uint32_t mid = (uint32_t)messageId;
    const uint8_t midbe[4] = { (uint8_t)((mid >> 24) & 0xFF), (uint8_t)((mid >> 16) & 0xFF),
                               (uint8_t)((mid >> 8) & 0xFF), (uint8_t)(mid & 0xFF) };
    [pkt appendBytes:midbe length:4];
    const uint8_t tsbe[4] = { (uint8_t)((ts >> 24) & 0xFF), (uint8_t)((ts >> 16) & 0xFF),
                              (uint8_t)((ts >> 8) & 0xFF), (uint8_t)(ts & 0xFF) };
    [pkt appendBytes:tsbe length:4];
    NSData *textData = [trimmed dataUsingEncoding:NSUTF8StringEncoding];
    if (textData) {
        [pkt appendData:textData];
    }
    [[self.dataSource managerGetTox] groupSendCustomPacket:pkt groupNumber:(OCTToxGroupNumber)chat.groupNumber lossless:YES error:nil];
}

// Receive/apply an incoming 0x41 group edit. Returns YES when the packet was an edit (consumed).
- (BOOL)handleIncomingGroupEditPacketWithGroupNumber:(OCTToxGroupNumber)groupNumber
                                              peerId:(uint32_t)peerId
                                                data:(NSData *)data
{
    if (data.length < 16) {
        return NO;
    }
    const uint8_t *b = data.bytes;
    if (b[0] != 0x66 || b[1] != 0x77 || b[2] != 0x88 || b[3] != 0x11 || b[4] != 0x34 || b[5] != 0x35
        || b[6] != 0x01 || b[7] != 0x41) {
        return NO;
    }
    const uint32_t messageId = ((uint32_t)b[8] << 24) | ((uint32_t)b[9] << 16)
                             | ((uint32_t)b[10] << 8) | (uint32_t)b[11];
    const uint32_t editTs = ((uint32_t)b[12] << 24) | ((uint32_t)b[13] << 16)
                          | ((uint32_t)b[14] << 8) | (uint32_t)b[15];
    if (messageId == 0) {
        return YES;
    }
    NSString *newText = @"";
    if (data.length > 16) {
        newText = [[NSString alloc] initWithBytes:(b + 16) length:(data.length - 16) encoding:NSUTF8StringEncoding];
        if (newText == nil) {
            return YES;
        }
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager chatWithGroupNumber:groupNumber];
    if (! chat) {
        return YES;
    }
    // authenticate by the author's STABLE key, exactly like the 0x42 text retract — the frozen peer NAME
    // this used to match on is a spoofable nickname, and rewriting someone else's message is at least as
    // damaging as deleting it. Rows with no frozen key (pre-batch) keep the old name rule.
    // KHANDAQ (audit, round 7): two-source resolver, same as the 0x42 gate — a peer id toxcore has
    // already forgotten by the time this main-queue callback runs otherwise resolved to an empty key and
    // the author's own edit silently no-opped on that recipient. See the resolver's own header note.
    NSString *senderPubkey = [self groupStableSenderPubkeyHexForGroupNumber:groupNumber peerId:peerId chat:chat];
    NSString *peerName = [self groupPeerNameByPubkeyForGroupNumber:groupNumber peerId:peerId chat:chat];
    if (peerName.length == 0 && senderPubkey.length == 0) {
        return YES;
    }
    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND messageText.messageId == %d AND groupSenderPeerId != 0",
                              chat.uniqueIdentifier, (int32_t)messageId];
    OCTMessageAbstract *found = [self groupTextRowForRetractionInResults:[realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate]
                                                            senderPubkey:senderPubkey
                                                                peerName:peerName];
    if (! found || found.messageText == nil) {
        return YES;
    }
    if (found.edited && found.editedTimestamp >= (NSTimeInterval)editTs) {
        return YES; // last-write-wins
    }
    [realmManager updateObject:found withBlock:^(OCTMessageAbstract *m) {
        m.messageText.text = newText;
        m.edited = YES;
        m.editedTimestamp = (NSTimeInterval)editTs;
    }];
    return YES;
}

- (void)tox:(OCTTox *)tox groupCustomPacketWithGroupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
       data:(NSData *)data
{
    uint32_t selfPeerId = [tox groupSelfPeerIdForGroupNumber:groupNumber error:nil];

    if (selfPeerId > 0 && peerId == selfPeerId) {
        return;
    }

    if ([self isBlockedIncomingPeerId:peerId groupNumber:groupNumber]) {
        return;
    }

    // KHANDAQ (audit #2 finding 1, step 3): history-signing-key announcement, protocol version
    // 0x02. Checked before everything else because it is an exact-length packet whose test is three
    // fields deep (magic, version, pktid), so it cannot be confused with any of the 0x01 traffic —
    // and consuming it here keeps a malformed one out of their length arithmetic.
    [self setupHskAnnounceIfNeeded];
    if ([self.hskAnnounce handleIncomingPacketWithGroupNumber:groupNumber peerId:peerId data:data]) {
        return;
    }

    if ([self handleIncomingGroupDeletePacketWithGroupNumber:groupNumber peerId:peerId data:data]) {
        return;
    }

    if ([self handleIncomingGroupEditPacketWithGroupNumber:groupNumber peerId:peerId data:data]) {
        return;
    }

    if ([self handleIncomingGroupReactionPacketWithGroupNumber:groupNumber peerId:peerId data:data]) {
        return;
    }

    [self setupLiveVideoIfNeeded];
    NSString *peerPublicKeyHex = [tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil];
    if ([self.liveVideo handleIncomingPacketWithGroupNumber:groupNumber
                                                    peerId:peerId
                                      peerPublicKeyHex:peerPublicKeyHex
                                                      data:data]) {
        return;
    }

    [self setupLiveAudioIfNeeded];
    if ([self.liveAudio handleIncomingPacketWithGroupNumber:groupNumber peerId:peerId data:data]) {
        return;
    }

    [self setupFileTransferIfNeeded];
    [self.fileTransfer handleIncomingPacketWithGroupNumber:groupNumber
                                                    peerId:peerId
                                          peerPublicKeyHex:peerPublicKeyHex
                                                      data:data];
}

- (void)tox:(OCTTox *)tox groupCustomPrivatePacketWithGroupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
       data:(NSData *)data
{
    uint32_t selfPeerId = [tox groupSelfPeerIdForGroupNumber:groupNumber error:nil];

    if (selfPeerId > 0 && peerId == selfPeerId) {
        return;
    }

    if ([self isBlockedIncomingPeerId:peerId groupNumber:groupNumber]) {
        return;
    }

    // KHANDAQ: Android sends NGC file-transfer (BEGIN/CHUNK) — and may send live media — as PRIVATE
    // (unicast) custom packets, so they arrive HERE, not in the broadcast callback. Previously this
    // path only ran histSync, so Android->iOS group photos/videos were silently dropped (no progress,
    // no media). Route private packets through the same media handlers as the broadcast callback
    // first; each handler ignores packets that aren't its own (magic/type), so histSync still runs for
    // genuine history-sync packets.
    // KHANDAQ (#192/#208): reactions and edits may also arrive on the private path (robustness,
    // mirrors the delete/media routing note above)
    if ([self handleIncomingGroupEditPacketWithGroupNumber:groupNumber peerId:peerId data:data]) {
        return;
    }
    if ([self handleIncomingGroupReactionPacketWithGroupNumber:groupNumber peerId:peerId data:data]) {
        return;
    }
    NSString *peerPublicKeyHex = [tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil];
    [self setupLiveVideoIfNeeded];
    if ([self.liveVideo handleIncomingPacketWithGroupNumber:groupNumber
                                                     peerId:peerId
                                           peerPublicKeyHex:peerPublicKeyHex
                                                       data:data]) {
        return;
    }
    [self setupLiveAudioIfNeeded];
    if ([self.liveAudio handleIncomingPacketWithGroupNumber:groupNumber peerId:peerId data:data]) {
        return;
    }
    [self setupFileTransferIfNeeded];
    [self.fileTransfer handleIncomingPacketWithGroupNumber:groupNumber
                                                    peerId:peerId
                                          peerPublicKeyHex:peerPublicKeyHex
                                                      data:data];

    [self setupHistSyncIfNeeded];
    [self.histSync handleIncomingPrivatePacketWithGroupNumber:groupNumber peerId:peerId data:data];
}

- (void)tox:(OCTTox *)tox groupModerationWithGroupNumber:(OCTToxGroupNumber)groupNumber
 sourcePeerId:(uint32_t)sourcePeerId
 targetPeerId:(uint32_t)targetPeerId
          event:(OCTToxGroupModEvent)event
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat) {
        OCTToxGroupRole role = [tox groupPeerRoleForGroupNumber:groupNumber peerId:targetPeerId error:nil];

        [[self.dataSource managerGetRealmManager] setGroupPeerRole:role peerId:targetPeerId chat:chat];
        [self refreshPeersForChat:chat];
    }

    OCTLogInfo(@"NGC group moderation group=%u source=%u target=%u event=%ld",
               groupNumber, sourcePeerId, targetPeerId, (long)event);
}

- (void)tox:(OCTTox *)tox groupTopicUpdate:(NSString *)topic
groupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat && topic.length > 0) {
        [[self.dataSource managerGetRealmManager] updateGroupTopic:topic forChat:chat];
    }
}

- (void)tox:(OCTTox *)tox groupPasswordUpdate:(NSString *)password
groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat) {
        [[self.dataSource managerGetRealmManager] updateGroupPassword:password forChat:chat];
    }
}

- (void)tox:(OCTTox *)tox groupTopicLockUpdate:(OCTToxGroupTopicLock)topicLock
groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat) {
        [[self.dataSource managerGetRealmManager] updateGroupTopicLockEnabled:(topicLock == OCTToxGroupTopicLockEnabled) forChat:chat];
    }
}

- (void)tox:(OCTTox *)tox groupPeerLimitUpdate:(uint16_t)peerLimit
groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat) {
        [[self.dataSource managerGetRealmManager] updateGroupPeerLimit:peerLimit > 0 ? (int32_t)peerLimit : 0 forChat:chat];
    }
}

- (void)tox:(OCTTox *)tox groupPrivacyStateUpdate:(OCTToxGroupPrivacyState)privacyState
groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat) {
        [[self.dataSource managerGetRealmManager] updateGroupPrivacyState:privacyState forChat:chat];
        NSString *privacyLabel = [self localizedPrivacyNameForState:privacyState];
        [self addGroupSystemMessageWithFormatKey:@"group_system_privacy_changed" argument:privacyLabel toChat:chat];
    }
}

- (void)tox:(OCTTox *)tox groupVoiceStateUpdate:(OCTToxGroupVoiceState)voiceState
groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (chat) {
        [[self.dataSource managerGetRealmManager] updateGroupVoiceState:voiceState forChat:chat];
    }
}

- (void)tox:(OCTTox *)tox groupJoinFail:(OCTToxGroupJoinFail)failType
groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

    if (! chat) {
        return;
    }

    OCTLogWarn(@"NGC group join fail group=%u type=%ld chatId=%@", groupNumber, (long)failType, chat.groupChatIdHex);

    [self refreshPeersForChat:chat];

    if (failType == OCTToxGroupJoinFailPeerLimit || failType == OCTToxGroupJoinFailInvalidPassword) {
        [self notifyGroupJoinDidFail:failType forChat:chat];
        return;
    }

    [self scheduleGroupJoinFailRetryForChat:chat groupNumber:groupNumber failType:failType];
}

- (void)tox:(OCTTox *)tox groupPrivateMessage:(NSString *)message
       type:(OCTToxMessageType)type
groupNumber:(OCTToxGroupNumber)groupNumber
     peerId:(uint32_t)peerId
{
    if ([self isBlockedIncomingPeerId:peerId groupNumber:groupNumber]) {
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager chatWithGroupNumber:groupNumber];

    if (! chat) {
        NSString *chatIdHex = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];
        chat = [realmManager getOrCreateGroupChatWithGroupNumber:groupNumber
                                                       chatIdHex:chatIdHex
                                                       groupName:nil
                                                    privacyState:OCTToxGroupPrivacyStatePublic];
    }

    // KHANDAQ: resolve the sender name by STABLE pubkey, not the volatile peerId.
    NSString *peerName = [self groupPeerNameByPubkeyForGroupNumber:groupNumber peerId:peerId chat:chat];
    if (peerName.length == 0) {
        peerName = [NSString stringWithFormat:@"Peer %u", peerId];
    }

    // KHANDAQ (#55): freeze the COUNTERPARTY (sender) stable pubkey so the private thread stays bound
    // to this person even after the peer id is reused — resolved now while the peer id is valid.
    NSString *senderPubkey = [tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil];

    [realmManager addGroupPrivateMessageWithText:message
                                            type:type
                                            chat:chat
                                          peerId:peerId
                                        peerName:peerName
                                  counterpartyId:peerId
                              counterpartyPubkey:senderPubkey
                                       messageId:0
                                      isOutgoing:NO];
}

#pragma mark - Private

- (void)recordGroupSyncConfirmationForChat:(OCTChat *)chat
                               ackerPeerId:(uint32_t)ackerPeerId
                                 messageId:(uint32_t)messageId
                               msgIdHashHex:(NSString *)msgIdHashHex
                                groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTTox *tox = [self.dataSource managerGetTox];
    NSString *ackerPubkeyHex = [tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:ackerPeerId error:nil];
    NSString *selfPubkeyHex = [tox groupSelfPublicKeyHexForGroupNumber:groupNumber error:nil];

    if (ackerPubkeyHex.length == 0) {
        return;
    }

    if (selfPubkeyHex.length > 0 &&
        [ackerPubkeyHex.uppercaseString isEqualToString:selfPubkeyHex.uppercaseString]) {
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    if (msgIdHashHex.length > 0) {
        [realmManager recordGroupSyncConfirmationForOutgoingFileWithHashHex:msgIdHashHex
                                                             ackerPubkeyHex:ackerPubkeyHex
                                                                     inChat:chat];
    }
    else if (messageId > 0) {
        [realmManager recordGroupSyncConfirmationForOutgoingTextMessageId:messageId
                                                           ackerPubkeyHex:ackerPubkeyHex
                                                                   inChat:chat];
    }
}

+ (BOOL)canKickPeerWithRole:(OCTToxGroupRole)peerRole
                   selfRole:(OCTToxGroupRole)selfRole
                 isSelfPeer:(BOOL)isSelfPeer
{
    if (isSelfPeer) {
        return NO;
    }

    if (peerRole == OCTToxGroupRoleFounder) {
        return NO;
    }

    if (selfRole != OCTToxGroupRoleFounder && selfRole != OCTToxGroupRoleModerator) {
        return NO;
    }

    if (selfRole == OCTToxGroupRoleModerator && peerRole == OCTToxGroupRoleModerator) {
        return NO;
    }

    return YES;
}

+ (BOOL)isBlockedGroupPeerWithRole:(OCTToxGroupRole)role
{
    return role == OCTToxGroupRoleObserver;
}

+ (NSString *)sanitizedGroupTopic:(NSString *)topic
{
    if (topic.length == 0) {
        return @"";
    }

    NSString *cleaned = [[topic stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]]
                         stringByReplacingOccurrencesOfString:@"\r" withString:@""];
    cleaned = [cleaned stringByReplacingOccurrencesOfString:@"\n" withString:@""];

    NSData *data = [cleaned dataUsingEncoding:NSUTF8StringEncoding];

    if (! data || data.length <= TOX_GROUP_MAX_TOPIC_LENGTH) {
        return cleaned;
    }

    return [[NSString alloc] initWithBytes:data.bytes length:TOX_GROUP_MAX_TOPIC_LENGTH encoding:NSUTF8StringEncoding] ?: cleaned;
}

+ (BOOL)canSetPeerRole:(OCTToxGroupRole)newRole
               peerRole:(OCTToxGroupRole)peerRole
               selfRole:(OCTToxGroupRole)selfRole
             isSelfPeer:(BOOL)isSelfPeer
{
    if (isSelfPeer || peerRole == OCTToxGroupRoleFounder || newRole == OCTToxGroupRoleFounder) {
        return NO;
    }

    return selfRole == OCTToxGroupRoleFounder;
}

- (BOOL)isBlockedIncomingPeerId:(uint32_t)peerId groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTToxGroupRole role = [[self.dataSource managerGetTox] groupPeerRoleForGroupNumber:groupNumber
                                                                                 peerId:peerId
                                                                                  error:nil];

    return [self.class isBlockedGroupPeerWithRole:role];
}

- (void)scheduleGroupsSyncIfNeeded
{
    if (! [self.dataSource managerIsToxConnected]) {
        self.pendingGroupsSync = YES;
        return;
    }

    if (self.pendingGroupsSync) {
        self.pendingGroupsSync = NO;
        [self syncGroupsWithTox];
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    if ([realmManager groupChats].count == 0 && [self.dataSource managerGetTox].groupCount == 0) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        __strong typeof(weakSelf) self = weakSelf;

        if (! self || ! [self.dataSource managerIsToxConnected]) {
            return;
        }

        if (self.groupBackgroundWorkPaused) {
            self.pendingGroupsSync = YES;
            return;
        }

        [self syncGroupsWithTox];
    });
}

- (void)setGroupBackgroundWorkPaused:(BOOL)paused
{
    _groupBackgroundWorkPaused = paused;

    if (! paused && self.pendingGroupsSync && [self.dataSource managerIsToxConnected]) {
        self.pendingGroupsSync = NO;
        [self syncGroupsWithTox];
    }
}

- (OCTChat *)persistGroupChatWithNumber:(OCTToxGroupNumber)groupNumber
                              groupName:(NSString *)groupName
                           privacyState:(OCTToxGroupPrivacyState)privacyState
{
    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *chatIdHex = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];

    return [realmManager getOrCreateGroupChatWithGroupNumber:groupNumber
                                                   chatIdHex:chatIdHex
                                                   groupName:groupName
                                                privacyState:privacyState];
}

- (OCTToxGroupNumber)performGroupNewWithPrivacyState:(OCTToxGroupPrivacyState)privacyState
                                           groupName:(NSString *)groupName
                                            peerName:(NSString *)peerName
                                               error:(NSError **)error
{
    OCTTox *tox = [self.dataSource managerGetTox];
    __block OCTToxGroupNumber groupNumber = kOCTToxGroupNumberFailure;
    __block NSError *toxError = nil;

    [tox performSyncBlockOnToxQueue:^{
        groupNumber = [tox groupNewWithPrivacyState:privacyState
                                          groupName:groupName
                                           peerName:peerName
                                              error:&toxError];
    }];

    if (groupNumber != kOCTToxGroupNumberFailure) {
        // KHANDAQ: persist immediately so a newly created group survives a crash/relaunch (toxcore
        // otherwise keeps it only in the in-memory save until the next periodic write).
        [self.dataSource managerSaveTox];
    }

    if (error) {
        *error = toxError;
    }

    return groupNumber;
}

- (void)finalizeNewGroupWithNumber:(OCTToxGroupNumber)groupNumber
                         groupName:(NSString *)groupName
                      privacyState:(OCTToxGroupPrivacyState)privacyState
                    systemMessageKey:(NSString *)systemMessageKey
{
    OCTChat *chat = [self persistGroupChatWithNumber:groupNumber groupName:groupName privacyState:privacyState];
    [self finishNewGroupSetupForChat:chat groupNumber:groupNumber];

    if (systemMessageKey.length > 0) {
        [self addGroupSystemMessageWithFormatKey:systemMessageKey argument:nil toChat:chat];
    }
}

- (NSString *)defaultGroupPeerName
{
    // KHANDAQ (#15): publish the real Tox self-name as our NGC peer name (used when accepting an
    // invite / preparing group activity). Previously this returned the hard-coded "Khandaq", so
    // everyone who joined via an invite appeared — and had their messages attributed — as "Khandaq".
    NSString *selfName = [[self.dataSource managerGetTox] userName];
    if (selfName.length > 0) {
        return selfName;
    }
    return kOCTDefaultGroupPeerName;
}

- (NSString *)chatIdHexFromInviteData:(NSData *)inviteData
{
    if (inviteData.length < TOX_GROUP_CHAT_ID_SIZE) {
        return nil;
    }

    return [[self normalizedGroupChatIdHexString:[OCTTox binToHexString:(uint8_t *)inviteData.bytes length:TOX_GROUP_CHAT_ID_SIZE]] copy];
}

- (BOOL)shouldIgnoreGroupInvite:(NSData *)inviteData tox:(OCTTox *)tox
{
    NSString *chatIdHex = [self chatIdHexFromInviteData:inviteData];

    if (chatIdHex.length == 0) {
        return NO;
    }

    OCTToxGroupNumber existingGroupNumber = [self groupNumberInToxForChatIdHex:chatIdHex];

    if (existingGroupNumber == kOCTToxGroupNumberFailure) {
        return NO;
    }

    int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:existingGroupNumber error:nil];
    uint32_t peerCount = [tox groupPeerCountForGroupNumber:existingGroupNumber error:nil];

    return connectionStatus > 0 && peerCount > 1;
}

- (OCTToxGroupNumber)groupNumberInToxForChatIdHex:(NSString *)chatIdHex
{
    __block OCTToxGroupNumber result = kOCTToxGroupNumberFailure;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        result = [self groupNumberInToxForChatIdHex:chatIdHex onTox:tox];
    }];

    return result;
}

- (void)finishNewGroupSetupForChat:(OCTChat *)chat groupNumber:(OCTToxGroupNumber)groupNumber
{
    if (! chat || groupNumber == kOCTToxGroupNumberFailure) {
        return;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    NSString *chatIdHex = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];

    if (chatIdHex.length > 0 && chat.groupChatIdHex.length == 0) {
        OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
        [realmManager updateObject:chat withBlock:^(OCTChat *theChat) {
            theChat.groupChatIdHex = chatIdHex;
        }];
    }

    [self refreshPeersForChat:chat];

    [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTGroupConnectionStatusChangeNotification
                                                                    object:nil
                                                                  userInfo:@{
        kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey: chat.uniqueIdentifier,
        @"connected": @YES,
    }];
}

- (void)bootstrapGroupConnectionForChat:(OCTChat *)chat groupNumber:(OCTToxGroupNumber)groupNumber
{
    if (! chat || groupNumber == kOCTToxGroupNumberFailure) {
        return;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:groupNumber error:nil];

    // 0 = connecting (announce lookup running); reconnect would restart it from zero.
    if (connectionStatus < 0) {
        [tox groupReconnectWithGroupNumber:groupNumber error:nil];
        connectionStatus = [tox groupConnectionStatusForGroupNumber:groupNumber error:nil];
    }

    NSString *chatIdHex = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];

    if (chatIdHex.length > 0 && chat.groupChatIdHex.length == 0) {
        OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
        [realmManager updateObject:chat withBlock:^(OCTChat *theChat) {
            theChat.groupChatIdHex = chatIdHex;
        }];
    }

    [self refreshPeersForChat:chat];
    [self maintainAllGroups];

    if (chatIdHex.length == 64) {
        [self sendGroupInviteRequestToFriendsForChatIdHex:chatIdHex];
    }

    [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTGroupConnectionStatusChangeNotification
                                                                    object:nil
                                                                  userInfo:@{
        kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey: chat.uniqueIdentifier,
        @"connected": @((connectionStatus > 0) || [tox groupPeerCountForGroupNumber:groupNumber error:nil] >= 1),
    }];
}

- (BOOL)prepareChatForGroupActivity:(OCTChat *)chat
                           peerName:(NSString *)peerName
                              error:(NSError **)error
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *resolvedPeerName = peerName.length > 0 ? peerName : [self defaultGroupPeerName];
    NSString *normalizedChatId = [self normalizedGroupChatIdHexString:chat.groupChatIdHex];
    __block BOOL success = NO;
    __block NSError *localError = nil;
    __block OCTToxGroupNumber joinedGroupNumber = kOCTToxGroupNumberFailure;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        if (chat.groupNumber >= 0) {
            OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
            NSString *chatIdHex = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];

            if (chatIdHex.length > 0) {
                int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:groupNumber error:nil];

                if (connectionStatus < 0) {
                    [tox groupReconnectWithGroupNumber:groupNumber error:nil];
                }

                success = YES;
                return;
            }
        }

        NSString *chatIdHex = normalizedChatId;

        if (chatIdHex.length == 0 && chat.groupNumber >= 0) {
            chatIdHex = [self normalizedGroupChatIdHexString:[tox groupChatIdHexForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:nil]];
        }

        if (chatIdHex.length != 64) {
            return;
        }

        OCTToxGroupNumber existingGroupNumber = [self groupNumberInToxForChatIdHex:chatIdHex onTox:tox];

        if (existingGroupNumber != kOCTToxGroupNumberFailure) {
            joinedGroupNumber = existingGroupNumber;
            success = YES;
            return;
        }

        joinedGroupNumber = [tox groupJoinWithChatIdHex:chatIdHex
                                                 peerName:resolvedPeerName
                                                 password:chat.groupPassword
                                                    error:&localError];
        success = joinedGroupNumber != kOCTToxGroupNumberFailure;
    }];

    if (! success) {
        if (error) {
            *error = localError ?: [OCTTox createErrorWithCode:OCTToxErrorGroupSendMessageGroupNotFound
                                                   description:@"Cannot prepare group chat"
                                                 failureReason:@"Group is missing from Tox and has no chat ID"];
        }
        return NO;
    }

    if (joinedGroupNumber != kOCTToxGroupNumberFailure) {
        NSString *persistChatId = normalizedChatId.length == 64 ? normalizedChatId : chat.groupChatIdHex;

        [realmManager getOrCreateGroupChatWithGroupNumber:joinedGroupNumber
                                                chatIdHex:persistChatId
                                                groupName:chat.groupName
                                             privacyState:(OCTToxGroupPrivacyState)chat.groupPrivacyState];

        [realmManager updateObject:chat withBlock:^(OCTChat *theChat) {
            theChat.groupNumber = (int32_t)joinedGroupNumber;
            if (persistChatId.length > 0) {
                theChat.groupChatIdHex = [[self normalizedGroupChatIdHexString:persistChatId] copy];
            }
        }];

        [self bootstrapGroupConnectionForChat:chat groupNumber:joinedGroupNumber];

        if (persistChatId.length == 64) {
            [self sendGroupInviteRequestToFriendsForChatIdHex:persistChatId];
        }
    }
    else if (chat.groupNumber >= 0) {
        __block NSString *chatIdHex = nil;

        [self performSyncOnToxQueue:^(OCTTox *tox) {
            chatIdHex = [tox groupChatIdHexForGroupNumber:(OCTToxGroupNumber)chat.groupNumber error:nil];
        }];

        if (chatIdHex.length > 0 && chat.groupChatIdHex.length == 0) {
            [realmManager updateObject:chat withBlock:^(OCTChat *theChat) {
                theChat.groupChatIdHex = [self normalizedGroupChatIdHexString:chatIdHex];
            }];
        }
    }

    return YES;
}

- (void)setupFileTransferIfNeeded
{
    if (self.fileTransfer) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    self.fileTransfer = [[OCTNgcGroupFileTransfer alloc]
        initWithSendPacketBlock:^BOOL(uint32_t groupNumber, NSData *packet, NSError **error) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return NO;
            }

            return [[self.dataSource managerGetTox] groupSendCustomPacket:packet
                                                            groupNumber:groupNumber
                                                               lossless:YES
                                                                  error:error];
        }
        incomingFilesDirectory:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return @"";
            }

            OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];

            if (! chat) {
                return @"";
            }

            return [self groupFilesDirectoryForChat:chat createIfNeeded:YES];
        }
        incomingBeginBlock:^(uint32_t groupNumber,
                             uint32_t peerId,
                             NSString *senderPublicKeyHex,
                             NSString *fileName,
                             NSString *filePath,
                             uint64_t fileSize,
                             NSString *msgIdHex) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return;
            }

            [self handleIncomingGroupFileBeginWithGroupNumber:groupNumber
                                                       peerId:peerId
                                          senderPublicKeyHex:senderPublicKeyHex
                                                     fileName:fileName
                                                     filePath:filePath
                                                     fileSize:fileSize
                                                     msgIdHex:msgIdHex];
        }
        incomingCompleteBlock:^(uint32_t groupNumber,
                                uint32_t peerId,
                                NSString *senderPublicKeyHex,
                                NSString *fileName,
                                NSString *filePath,
                                uint64_t fileSize,
                                NSString *msgIdHex) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return;
            }

            [self handleIncomingGroupFileCompleteWithGroupNumber:groupNumber
                                                          peerId:peerId
                                             senderPublicKeyHex:senderPublicKeyHex
                                                        fileName:fileName
                                                        filePath:filePath
                                                        fileSize:fileSize
                                                        msgIdHex:msgIdHex];
        }
        transferProgressBlock:^(uint32_t groupNumber, NSString *msgIdHex, float progress) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return;
            }

            [self handleIncomingGroupFileProgressWithGroupNumber:groupNumber
                                                        msgIdHex:msgIdHex
                                                        progress:progress];
        }];
}

- (void)setupLiveAudioIfNeeded
{
    if (self.liveAudio) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    self.liveAudio = [[OCTNgcGroupLiveAudio alloc]
        initWithSendPacketBlock:^BOOL(uint32_t groupNumber, NSData *packet, BOOL lossless, NSError **error) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return NO;
            }

            return [[self.dataSource managerGetTox] groupSendCustomPacket:packet
                                                            groupNumber:groupNumber
                                                               lossless:lossless
                                                                  error:error];
        }];
}

- (void)setupLiveVideoIfNeeded
{
    if (self.liveVideo) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    self.liveVideo = [[OCTNgcGroupLiveVideo alloc]
        initWithSendPacketBlock:^BOOL(uint32_t groupNumber, NSData *packet, BOOL lossless, NSError **error) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return NO;
            }

            return [[self.dataSource managerGetTox] groupSendCustomPacket:packet
                                                            groupNumber:groupNumber
                                                               lossless:lossless
                                                                  error:error];
        }];

    [self.liveVideo setIncomingVideoActivityBlock:^(uint32_t groupNumber) {
        __strong typeof(weakSelf) self = weakSelf;

        if (! self) {
            return;
        }

        [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTGroupLiveVideoActivityNotification
                                                                        object:self
                                                                      userInfo:@{kOCTGroupLiveVideoActivityGroupNumberKey: @(groupNumber)}];
    }];
}

- (void)setupHskAnnounceIfNeeded
{
    if (self.hskAnnounce) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    self.hskAnnounce = [[OCTNgcHskAnnounce alloc]
        initWithSendBroadcastBlock:^BOOL(uint32_t groupNumber, NSData *packet) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return NO;
            }
            return [[self.dataSource managerGetTox] groupSendCustomPacket:packet
                                                              groupNumber:groupNumber
                                                                 lossless:YES
                                                                    error:nil];
        }
        selfGroupPubBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return nil;
            }
            return [[self.dataSource managerGetTox] groupSelfPublicKeyHexForGroupNumber:groupNumber
                                                                                  error:nil];
        }
        peerGroupPubBlock:^NSString *(uint32_t groupNumber, uint32_t peerId) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return nil;
            }
            return [[self.dataSource managerGetTox] groupPeerPublicKeyHexForGroupNumber:groupNumber
                                                                                 peerId:peerId
                                                                                  error:nil];
        }
        groupIdBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return nil;
            }
            return [[self.dataSource managerGetTox] groupChatIdHexForGroupNumber:groupNumber error:nil];
        }
        selfToxPubBlock:^NSString *{
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return nil;
            }
            // The profile identity, used ONLY to decide whose stored key this is (a changed Tox
            // identity must re-mint). What a signature binds to is the per-group key above.
            return [OCTNgcHskStore toxPubFromToxId:[[self.dataSource managerGetTox] userAddress]];
        }
        peerConnectedBlock:^BOOL(uint32_t groupNumber, uint32_t peerId) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return NO;
            }
            OCTToxConnectionStatus status =
                [[self.dataSource managerGetTox] groupPeerConnectionStatusForGroupNumber:groupNumber
                                                                                  peerId:peerId
                                                                                   error:nil];
            return status != OCTToxConnectionStatusNone;
        }
        getValueBlock:^NSString *(NSString *key) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return nil;
            }
            return [[self.dataSource managerGetRealmManager] ngcValueForKey:key];
        }
        setValueBlock:^BOOL(NSString *key, NSString *value) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return NO;
            }
            return [[self.dataSource managerGetRealmManager] setNgcValue:value forKey:key];
        }];
}

- (void)setupSignedHistoryIfNeeded
{
    if (self.signedHistory) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    self.signedHistory = [[OCTNgcSignedHistory alloc]
        initWithSendPrivateBlock:^BOOL(uint32_t groupNumber, uint32_t peerId, NSData *packet) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return NO;
            }
            // Unicast, like the unsigned history it travels beside: a broadcast would hand every
            // member a copy of a row only the requesting peer asked for.
            return [[self.dataSource managerGetTox] groupSendCustomPrivatePacket:packet
                                                                     groupNumber:groupNumber
                                                                          peerId:peerId
                                                                        lossless:YES
                                                                           error:nil];
        }
        groupIdBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return nil;
            }
            return [[self.dataSource managerGetTox] groupChatIdHexForGroupNumber:groupNumber error:nil];
        }
        selfGroupPubBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return nil;
            }
            return [[self.dataSource managerGetTox] groupSelfPublicKeyHexForGroupNumber:groupNumber
                                                                                  error:nil];
        }
        selfToxPubBlock:^NSString *{
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return nil;
            }
            // Same role as in the announcer: it decides only WHOSE stored key this is, so a changed
            // profile re-mints instead of signing with a stranger's key. What the signature binds to
            // is the per-group key above.
            return [OCTNgcHskStore toxPubFromToxId:[[self.dataSource managerGetTox] userAddress]];
        }
        getValueBlock:^NSString *(NSString *key) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return nil;
            }
            return [[self.dataSource managerGetRealmManager] ngcValueForKey:key];
        }
        setValueBlock:^BOOL(NSString *key, NSString *value) {
            __strong typeof(weakSelf) self = weakSelf;
            if (! self) {
                return NO;
            }
            return [[self.dataSource managerGetRealmManager] setNgcValue:value forKey:key];
        }];
}

- (void)setupHistSyncIfNeeded
{
    if (self.histSync) {
        return;
    }

    __weak typeof(self) weakSelf = self;

    self.histSync = [[OCTNgcGroupHistSync alloc]
        initWithSendPrivatePacketBlock:^BOOL(uint32_t groupNumber, uint32_t peerId, NSData *packet, NSError **error) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return NO;
            }

            return [[self.dataSource managerGetTox] groupSendCustomPrivatePacket:packet
                                                                   groupNumber:groupNumber
                                                                        peerId:peerId
                                                                      lossless:YES
                                                                         error:error];
        }
        peerPublicKeyBlock:^NSString *(uint32_t groupNumber, uint32_t peerId) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return nil;
            }

            return [[self.dataSource managerGetTox] groupPeerPublicKeyHexForGroupNumber:groupNumber
                                                                                 peerId:peerId
                                                                                  error:nil];
        }
        peerNameForPeerIdBlock:^NSString *(uint32_t groupNumber, uint32_t peerId) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return nil;
            }

            for (NSDictionary *peer in [[self.dataSource managerGetTox] groupPeersForGroupNumber:groupNumber error:nil]) {
                if ([peer[@"peerId"] unsignedIntValue] == peerId) {
                    return peer[@"name"];
                }
            }

            return nil;
        }
        defaultPeerNameBlock:^NSString * {
            __strong typeof(weakSelf) self = weakSelf;

            return self ? [self defaultGroupPeerName] : @"peer";
        }
        selfPublicKeyBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return nil;
            }

            return [[self.dataSource managerGetTox] groupSelfPublicKeyHexForGroupNumber:groupNumber error:nil];
        }
        peerIdForPublicKeyBlock:^uint32_t(uint32_t groupNumber, NSString *publicKeyHex) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return 0;
            }

            return [[self.dataSource managerGetTox] groupPeerIdForPublicKeyHex:publicKeyHex
                                                                 groupNumber:groupNumber
                                                                       error:nil];
        }
        isPublicGroupBlock:^BOOL(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return NO;
            }

            // Android syncs hist for public and private NGC groups alike.
            return [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber] != nil;
        }
        selfPeerIdBlock:^uint32_t(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return 0;
            }

            return [[self.dataSource managerGetTox] groupSelfPeerIdForGroupNumber:groupNumber error:nil];
        }
        peerIdsBlock:^NSArray<NSNumber *> *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return @[];
            }

            NSMutableArray<NSNumber *> *peerIds = [NSMutableArray new];

            for (NSDictionary *peer in [[self.dataSource managerGetTox] groupPeersForGroupNumber:groupNumber error:nil]) {
                [peerIds addObject:peer[@"peerId"]];
            }

            return peerIds;
        }
        chatForGroupBlock:^OCTChat *(uint32_t groupNumber, BOOL createIfNeeded) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return nil;
            }

            return [self chatForGroupNumber:groupNumber createIfNeeded:createIfNeeded];
        }
        messagesToSyncBlock:^NSArray<OCTMessageAbstract *> *(OCTChat *chat) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return @[];
            }

            return [[self.dataSource managerGetRealmManager] groupMessagesForHistorySyncInChat:chat];
        }
        messageExistsBlock:^BOOL(OCTChat *chat, uint32_t messageId, uint32_t peerId, NSString *senderPubkeyHex, NSString *peerName, NSString *text, NSTimeInterval dateInterval) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return NO;
            }

            OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

            // KHANDAQ (#15): share the live path's content dedup so a message that arrived live (with
            // a different messageId due to the sender's retries) is recognised here and not inserted
            // again by history-sync. Keyed by the stable sender pubkey (#155) so it actually matches
            // the live path's entry even when the volatile peer_id churned or resolved to 0.
            if ([self isRecentDuplicateGroupMessageInChat:chat
                                                senderKey:(senderPubkeyHex.length > 0 ? senderPubkeyHex : [NSString stringWithFormat:@"%u", peerId])
                                                     text:text
                                            windowSeconds:15.0]) {
                return YES;
            }

            // KHANDAQ (#155): persistent, sender-scoped content check (mirrors the live path's #88
            // layer, which history-sync lacked). The live-arrived copy is stored with the ARRIVAL time
            // while the synced copy carries the ORIGINAL send timestamp, so the narrow 3s window below
            // misses it whenever delivery lagged or the link flapped — a reconnect storm then rendered
            // the same message 2-3x. The frozen sender name + wide window catches those without eating
            // an identical short text from a DIFFERENT peer.
            if (peerName.length > 0 &&
                [realmManager groupTextMessageExistsInChat:chat
                                                      text:text
                                                senderName:peerName
                                          nearDateInterval:dateInterval
                                             windowSeconds:120.0]) {
                return YES;
            }

            // KHANDAQ (#42): persistent check — survives an app restart. A re-synced copy carries the
            // ORIGINAL timestamp, so chat + identical text + (near) the same dateInterval recognises it
            // even though its volatile messageId no longer matches the stored one. This is what kills
            // the post-restart duplicate flood (the in-memory map above is empty after a relaunch).
            if ([realmManager groupTextMessageExistsInChat:chat text:text nearDateInterval:dateInterval windowSeconds:3.0]) {
                return YES;
            }

            // KHANDAQ (#114): peer-agnostic dedup. A relayed / re-synced copy carries the SAME messageId
            // but a DIFFERENT (volatile) peerId, so the peerId-scoped variant let it slip through and
            // history-sync inserted a duplicate — surfacing the same message many times under different
            // sender attributions. Match the live path: collapse by (messageId + text) regardless of the
            // relaying peer.
            return [realmManager groupTextMessageExistsInChat:chat
                                                    messageId:messageId
                                                         text:text];
        }
        fileExistsBlock:^BOOL(OCTChat *chat, NSString *msgIdHashHex) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return NO;
            }

            return [[self.dataSource managerGetRealmManager] groupMessageWithGroupMsgIdHashHex:msgIdHashHex chat:chat] != nil;
        }
        insertSyncedMessageBlock:^OCTMessageAbstract *(OCTChat *chat,
                                                         NSString *text,
                                                         OCTToxMessageType type,
                                                         uint32_t peerId,
                                                         NSString *peerName,
                                                         uint32_t messageId,
                                                         NSTimeInterval dateInterval) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return nil;
            }

            OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

            // KHANDAQ (audit F-6): the peer name inside a history-sync packet is UNAUTHENTICATED — the
            // relaying member types whatever it wants there. Writing it into the shared peer table
            // renamed that peer EVERYWHERE (member list, live messages, "X сменил имя" notices), so one
            // member could impersonate another across the whole UI. The name now stays on this row only
            // (messageText.groupPeerName below); real peer names keep coming from toxcore's own roster.
            return [realmManager addGroupSyncedMessageWithText:text
                                                          type:type
                                                          chat:chat
                                                        peerId:peerId
                                                      peerName:peerName
                                                     messageId:messageId
                                                  dateInterval:dateInterval];
        }
        insertSyncedFileBlock:^OCTMessageAbstract *(OCTChat *chat,
                                                    NSString *fileName,
                                                    NSString *filePath,
                                                    uint64_t fileSize,
                                                    NSString *fileUTI,
                                                    uint32_t peerId,
                                                    NSString *peerName,
                                                    NSString *senderPubkeyHex,
                                                    NSString *msgIdHashHex,
                                                    NSTimeInterval dateInterval) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return nil;
            }

            OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

            // KHANDAQ (#15): cross-path file dedup — the same file also arrives via the live BEGIN
            // path (with a different msgIdHashHex from the sender's retries), so the msgIdHashHex
            // check can miss it. Drop the history-sync copy if we already have it (peer + name + size).
            if ([self isRecentDuplicateGroupMessageInChat:chat
                                                 senderKey:[NSString stringWithFormat:@"%u", peerId]
                                                      text:[NSString stringWithFormat:@"file:%@:%llu", fileName ?: @"", fileSize]
                                             windowSeconds:120.0]) {
                return nil;
            }

            // KHANDAQ (#170): the check above compares exact name + volatile peer id inside 120s —
            // a re-served copy after a rejoin has a NEW peer id and a storage-prefixed name, so it
            // slipped through. Collapse by content (size + base name) against what we already show.
            if ([realmManager groupReadyFileDuplicateExistsInChat:chat fileName:fileName fileSize:fileSize]) {
                OCTLogInfo(@"NGC sync-file dup-skip name=%@ size=%llu", fileName, fileSize);
                [[NSFileManager defaultManager] removeItemAtPath:filePath error:nil];
                return nil;
            }

            // KHANDAQ (audit F-6): same as the synced-text path — never let the packet's peer name into
            // the shared peer table. Prefer the name toxcore itself vouches for (resolved by the stable
            // pubkey), and fall back to the packet's claim only when the author is not in our roster;
            // either way it is frozen on this row alone.
            NSString *frozenPeerName = peerName;

            if (peerId > 0) {
                NSString *rosterName = [self groupPeerNameByPubkeyForGroupNumber:(OCTToxGroupNumber)chat.groupNumber
                                                                          peerId:peerId
                                                                            chat:chat];

                if (rosterName.length > 0) {
                    frozenPeerName = rosterName;
                }
            }

            return [realmManager addGroupSyncedMessageWithFileName:fileName
                                                          fileSize:fileSize
                                                          filePath:filePath
                                                          fileType:OCTMessageFileTypeReady
                                                              chat:chat
                                                            peerId:peerId
                                                          peerName:frozenPeerName
                                                   senderPubkeyHex:senderPubkeyHex
                                                           fileUTI:fileUTI
                                                groupMsgIdHashHex:msgIdHashHex
                                                      dateInterval:dateInterval];
        }
        incomingFilesDirectoryBlock:^NSString *(uint32_t groupNumber) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return @"";
            }

            OCTChat *chat = [self chatForGroupNumber:groupNumber createIfNeeded:YES];

            return chat ? [self groupFilesDirectoryForChat:chat createIfNeeded:YES] : @"";
        }
        fileUTIBlock:^NSString *(NSString *fileName) {
            __strong typeof(weakSelf) self = weakSelf;

            return self ? [self fileUTIFromFileName:fileName] : @"";
        }
        isBlockedPeerBlock:^BOOL(uint32_t groupNumber, uint32_t peerId) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return NO;
            }

            return [self isBlockedIncomingPeerId:peerId groupNumber:groupNumber];
        }
        deliveryReceiptBlock:^(OCTChat *chat, uint32_t messageId, uint32_t peerId) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return;
            }

            [self recordGroupSyncConfirmationForChat:chat
                                         ackerPeerId:peerId
                                           messageId:messageId
                                         msgIdHashHex:nil
                                          groupNumber:(OCTToxGroupNumber)chat.groupNumber];
        }
        fileSyncConfirmationBlock:^(OCTChat *chat, NSString *msgIdHashHex, uint32_t syncerPeerId) {
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return;
            }

            [self recordGroupSyncConfirmationForChat:chat
                                         ackerPeerId:syncerPeerId
                                           messageId:0
                                         msgIdHashHex:msgIdHashHex
                                          groupNumber:(OCTToxGroupNumber)chat.groupNumber];
        }];

    // KHANDAQ (external audit #2, finding 1, step 4): hand the sync layer the signer. Both objects are
    // owned here so the signing layer has exactly one on/off point; with the property left nil nothing
    // is signed and 0x02 records stay dropped, i.e. the pre-signing behaviour.
    [self setupSignedHistoryIfNeeded];
    self.histSync.signedHistory = self.signedHistory;

    self.histSync.historySyncPacketsForGroupBlock = ^NSArray<NSData *> *(uint32_t groupNumber) {
        __strong typeof(weakSelf) self = weakSelf;

        if (! self || ! self.histSync) {
            return @[];
        }

        OCTNgcGroupHistSync *histSync = self.histSync;
        OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

        // Resolve the signing key BEFORE entering Realm's queue below. The store reads and writes
        // through that same serial queue, and the per-message block runs inside it — looking the key
        // up there is a dispatch_sync onto the queue this thread already owns, which crashes the
        // process and takes the shipped unsigned history path down with it.
        [self.signedHistory prepareKeyForGroupNumber:groupNumber];
        // Filled from inside the per-message block below, which Realm runs serially on its own queue,
        // so no locking is needed and every twin belongs to the row built immediately before it.
        NSMutableArray<NSData *> *signedPackets = [NSMutableArray new];

        NSArray<NSData *> *packets = [realmManager groupHistorySyncPacketsForGroupNumber:groupNumber
                                                                       packetFromMessage:^NSData *(OCTMessageAbstract *message) {
            NSData *signedTwin = nil;
            NSData *packet = [histSync buildSyncPacketForMessage:message
                                                     groupNumber:groupNumber
                                                      signedTwin:&signedTwin];

            // Only when the unsigned copy actually goes out: a twin for a row the caller discarded
            // would be a signature over a message the peer never receives, which reads on the far side
            // as a signed record for a row that does not exist.
            if (packet && signedTwin) {
                [signedPackets addObject:signedTwin];
            }

            return packet;
        }];

        if (signedPackets.count == 0) {
            return packets;
        }

        // Signed copies go LAST. The unsigned packet is what inserts the row on every client in the
        // field, and 0x02 is dropped outright by the ones that have not upgraded yet, so a verdict must
        // never be able to arrive before the row it is about.
        return [packets arrayByAddingObjectsFromArray:signedPackets];
    };
}

- (OCTChat *)chatForGroupNumber:(uint32_t)groupNumber createIfNeeded:(BOOL)createIfNeeded
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager chatWithGroupNumber:groupNumber];

    if (chat || ! createIfNeeded) {
        return chat;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    NSString *chatIdHex = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];

    return [realmManager getOrCreateGroupChatWithGroupNumber:groupNumber
                                                   chatIdHex:chatIdHex
                                                   groupName:nil
                                                privacyState:OCTToxGroupPrivacyStatePublic];
}

- (void)handleIncomingGroupFileBeginWithGroupNumber:(uint32_t)groupNumber
                                             peerId:(uint32_t)peerId
                                 senderPublicKeyHex:(NSString *)senderPublicKeyHex
                                           fileName:(NSString *)fileName
                                           filePath:(NSString *)filePath
                                           fileSize:(uint64_t)fileSize
                                           msgIdHex:(NSString *)msgIdHex
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [self chatForGroupNumber:groupNumber createIfNeeded:YES];

    if (! chat) {
        return;
    }

    // KHANDAQ (audit F-5): only the peer a row already names may re-open (resume) it. The in-memory
    // BEGIN dedup stops a second BEGIN only while the assembly lives — after the NACK eviction (see
    // OCTNgcGroupFileTransfer) the row is still 'Loading' with no assembly behind it, and a foreign peer
    // could then re-point it at its own file name/path/sender. The ownership test belongs in the lookup,
    // not after it: the msg-id hash is public AND not unique (a refused COMPLETE files its payload as
    // its own row under the same hash), so firstObject can return a stranger's row. Asking for OUR
    // sender's row means a genuine resume still lands here, while a foreign hash collision simply falls
    // through and gets a row of its own below — no legitimate transfer is dropped. Rows with no frozen
    // key (legacy/unresolved sender) keep matching any sender, as before.
    OCTMessageAbstract *existing = [realmManager groupMessageWithGroupMsgIdHashHex:msgIdHex
                                                                      senderPubkey:senderPublicKeyHex
                                                                              chat:chat];

    if (existing) {
        if (existing.messageFile.fileType == OCTMessageFileTypeReady) {
            // A finished row is never re-opened. This is also what absorbs a re-served copy of our OWN
            // file (#170): our row is Ready as soon as the upload succeeded, so the own-outgoing guard
            // below is never reached for it and no duplicate is created.
            return;
        }

        // KHANDAQ (audit, round 7): a foreign BEGIN may NEVER adopt our own outgoing row. Our msgIdHex
        // rides in the clear in our own BEGIN and in every CHUNK we broadcast, and our row is created
        // with peerId 0 and NO frozen author key (only the incoming handlers freeze one) — so it is
        // exactly the row the author-less branch of the lookup above hands out. It stays adoptable for a
        // long time: Loading for the whole upload, Canceled forever if a connected send failed. Adopting
        // it rewrote our file name/path, stamped the ATTACKER's peer id and froze the ATTACKER's key onto
        // it — which then satisfied the 0x42 retract gate (peerId != 0 and the row's key == the
        // requester's) and deleted our own photo on our own device. Same "ours" test as the retract gate:
        // peerId 0 alone is not ours, a history-synced row can carry 0 when its author was unresolvable.
        // Dropping the match here (instead of returning) keeps the foreign BEGIN honest — it falls
        // through and gets a row of its own, attributed to its real sender.
        if (existing.groupSenderPeerId == 0 && ! existing.groupHistorySync) {
            OCTLogWarn(@"NGC group file BEGIN targeting our own outgoing row refused group=%u peer=%u",
                       groupNumber, peerId);
            existing = nil;
        }
    }

    if (existing) {
        NSString *fileUTI = [self fileUTIFromFileName:fileName];

        [realmManager updateObject:existing.messageFile withBlock:^(OCTMessageFile *file) {
            file.fileName = fileName;
            file.fileSize = fileSize;
            file.fileUTI = fileUTI;
            [file internalSetFilePath:filePath];
            file.fileType = OCTMessageFileTypeLoading;
            file.groupTransferProgress = 0.0f;
        }];
        [realmManager updateObject:existing withBlock:^(OCTMessageAbstract *abstract) {
            abstract.groupSenderPeerId = peerId;
            abstract.dateInterval = abstract.dateInterval;
        }];
        [self freezeGroupFileSenderOnMessage:existing
                                 groupNumber:groupNumber
                                      peerId:peerId
                          senderPublicKeyHex:senderPublicKeyHex
                                        chat:chat];
        return;
    }

    // KHANDAQ (#15): dedup re-delivered file BEGINs whose msgIdHex differs (the sender's retries hash
    // a changing messageId), matched by peer + file name + size within a short window — same approach
    // as incoming text. Placed after the msgIdHex match so a legitimate resume (same hash) still
    // updates the existing transfer above.
    if ([self isRecentDuplicateGroupMessageInChat:chat
                                         senderKey:[NSString stringWithFormat:@"%u", peerId]
                                              text:[NSString stringWithFormat:@"file:%@:%llu", fileName ?: @"", fileSize]
                                     windowSeconds:120.0]) {
        return;
    }

    NSString *fileUTI = [self fileUTIFromFileName:fileName];

    OCTMessageAbstract *beginMessage = [realmManager addGroupMessageWithFileName:fileName
                                     fileSize:fileSize
                                     filePath:filePath
                                     fileType:OCTMessageFileTypeLoading
                                         chat:chat
                                       peerId:peerId
                                     fileUTI:fileUTI
                          groupMsgIdHashHex:msgIdHex
                       groupTransferProgress:0.0f];
    [self freezeGroupFileSenderOnMessage:beginMessage
                             groupNumber:groupNumber
                                  peerId:peerId
                      senderPublicKeyHex:senderPublicKeyHex
                                    chat:chat];
}

- (void)handleIncomingGroupFileProgressWithGroupNumber:(uint32_t)groupNumber
                                              msgIdHex:(NSString *)msgIdHex
                                              progress:(float)progress
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [self chatForGroupNumber:groupNumber createIfNeeded:NO];
    OCTMessageAbstract *message = chat ? [realmManager groupMessageWithGroupMsgIdHashHex:msgIdHex chat:chat] : nil;

    if (! message || message.messageFile.fileType == OCTMessageFileTypeReady) {
        return;
    }

    [realmManager updateObject:message.messageFile withBlock:^(OCTMessageFile *file) {
        file.groupTransferProgress = progress;
    }];
    [realmManager updateObject:message withBlock:^(OCTMessageAbstract *abstract) {
        abstract.dateInterval = abstract.dateInterval;
    }];
}

- (void)handleIncomingGroupFileCompleteWithGroupNumber:(uint32_t)groupNumber
                                                peerId:(uint32_t)peerId
                                    senderPublicKeyHex:(NSString *)senderPublicKeyHex
                                              fileName:(NSString *)fileName
                                              filePath:(NSString *)filePath
                                              fileSize:(uint64_t)fileSize
                                              msgIdHex:(NSString *)msgIdHex
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [self chatForGroupNumber:groupNumber createIfNeeded:YES];

    if (! chat) {
        return;
    }

    // KHANDAQ (#15): find the loading bubble AND mark it ready atomically on the realm's own queue
    // (the old split lookup-then-update used a stale default realm, so byHash/byContent missed the
    // BEGIN message and a duplicate "ready" copy was created — the stuck grey image + a loaded one).
    // KHANDAQ (audit F-5): the msg-id hash is public inside the group, so the row lookup alone lets any
    // member finish somebody else's transfer with its own payload. Pass the key of the peer this file
    // really came from — the row keeps the sender frozen at BEGIN and refuses a different one.
    BOOL handled = [realmManager markGroupIncomingFileReadyInChat:chat
                                                        msgIdHash:msgIdHex
                                                           peerId:peerId
                                                     senderPubkey:senderPublicKeyHex
                                                         fileName:fileName
                                                         filePath:filePath
                                                         fileSize:fileSize];

    if (handled) {
        return;
    }

    // KHANDAQ (#170): a re-broadcast/re-served copy arrives with a DIFFERENT msg-id hash and a
    // storage-prefixed file name, so the hash dedup above cannot catch it. Collapse by content
    // (same size + same base name in this chat) instead of stacking another identical photo.
    if ([realmManager groupReadyFileDuplicateExistsInChat:chat fileName:fileName fileSize:fileSize]) {
        OCTLogInfo(@"NGC live-file dup-skip group=%u name=%@ size=%llu", groupNumber, fileName, fileSize);
        [[NSFileManager defaultManager] removeItemAtPath:filePath error:nil];
        return;
    }

    NSString *fileUTI = [self fileUTIFromFileName:fileName];

    OCTMessageAbstract *completeMessage = [realmManager addGroupMessageWithFileName:fileName
                                     fileSize:fileSize
                                     filePath:filePath
                                     fileType:OCTMessageFileTypeReady
                                         chat:chat
                                       peerId:peerId
                                     fileUTI:fileUTI
                          groupMsgIdHashHex:msgIdHex
                       groupTransferProgress:1.0f];
    [self freezeGroupFileSenderOnMessage:completeMessage
                             groupNumber:groupNumber
                                  peerId:peerId
                      senderPublicKeyHex:senderPublicKeyHex
                                    chat:chat];
}

- (void)handleIncomingGroupFileWithGroupNumber:(uint32_t)groupNumber
                                        peerId:(uint32_t)peerId
                            senderPublicKeyHex:(NSString *)senderPublicKeyHex
                                      fileName:(NSString *)fileName
                                      filePath:(NSString *)filePath
                                      fileSize:(uint64_t)fileSize
                                      msgIdHex:(NSString *)msgIdHex
{
    [self handleIncomingGroupFileCompleteWithGroupNumber:groupNumber
                                                  peerId:peerId
                                     senderPublicKeyHex:senderPublicKeyHex
                                                fileName:fileName
                                                filePath:filePath
                                                fileSize:fileSize
                                                msgIdHex:msgIdHex];
}

- (NSString *)groupFilesDirectoryForChat:(OCTChat *)chat createIfNeeded:(BOOL)createIfNeeded
{
    id<OCTFileStorageProtocol> fileStorage = [self.dataSource managerGetFileStorage];
    NSString *chatIdHex = chat.groupChatIdHex;

    if (chatIdHex.length == 0) {
        chatIdHex = [self chatIdHexForChat:chat error:nil];
    }

    if (chatIdHex.length == 0) {
        return @"";
    }

    NSString *path = [[fileStorage pathForDownloadedFilesDirectory]
        stringByAppendingPathComponent:[NSString stringWithFormat:@"group/%@", [chatIdHex lowercaseString]]];

    if (createIfNeeded) {
        NSFileManager *fileManager = [NSFileManager defaultManager];
        BOOL isDirectory = NO;

        if (! [fileManager fileExistsAtPath:path isDirectory:&isDirectory] || ! isDirectory) {
            [fileManager createDirectoryAtPath:path withIntermediateDirectories:YES attributes:nil error:nil];
        }
    }

    return path;
}

- (NSString *)groupUploadsDirectory
{
    id<OCTFileStorageProtocol> fileStorage = [self.dataSource managerGetFileStorage];
    NSString *path = [[fileStorage pathForUploadedFilesDirectory] stringByAppendingPathComponent:@"group"];
    NSFileManager *fileManager = [NSFileManager defaultManager];
    BOOL isDirectory = NO;

    if (! [fileManager fileExistsAtPath:path isDirectory:&isDirectory] || ! isDirectory) {
        [fileManager createDirectoryAtPath:path withIntermediateDirectories:YES attributes:nil error:nil];
    }

    return path;
}

- (NSString *)fileUTIFromFileName:(NSString *)fileName
{
    NSString *extension = [fileName pathExtension];

    if (extension.length == 0) {
        return nil;
    }

#if TARGET_OS_IPHONE
    return (__bridge_transfer NSString *)UTTypeCreatePreferredIdentifierForTag(
        kUTTagClassFilenameExtension,
        (__bridge CFStringRef)extension,
        NULL);
#else
    return extension;
#endif
}

- (NSString *)groupJoinRetryKeyForChat:(OCTChat *)chat
{
    if (chat.groupChatIdHex.length > 0) {
        return chat.groupChatIdHex.lowercaseString;
    }

    return chat.uniqueIdentifier;
}

- (void)cancelGroupJoinRetryForChat:(OCTChat *)chat
{
    NSString *key = [self groupJoinRetryKeyForChat:chat];

    if (key.length > 0) {
        [self.groupJoinRetryCancelled addObject:key];
        [self.groupJoinRetryRunning removeObject:key];
    }
}

- (void)notifyGroupJoinDidFail:(OCTToxGroupJoinFail)failType forChat:(OCTChat *)chat
{
    id<OCTSubmanagerGroupsDelegate> delegate = self.delegate;

    if ([delegate respondsToSelector:@selector(submanagerGroups:groupJoinDidFail:forChat:)]) {
        [delegate submanagerGroups:self groupJoinDidFail:failType forChat:chat];
    }
}

- (OCTChat *)chatForJoinRetryKey:(NSString *)key chatIdHex:(NSString *)chatIdHex
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    if (chatIdHex.length > 0) {
        OCTChat *chat = [realmManager chatWithGroupChatIdHex:chatIdHex];

        if (chat) {
            return chat;
        }
    }

    if (key.length == 0) {
        return nil;
    }

    return [[realmManager groupChats] objectsWhere:@"groupChatIdHex ==[c] %@ OR uniqueIdentifier == %@", key, key].firstObject;
}

- (void)scheduleGroupJoinFailRetryForChat:(OCTChat *)chat
                             groupNumber:(OCTToxGroupNumber)groupNumber
                                failType:(OCTToxGroupJoinFail)failType
{
    NSString *key = [self groupJoinRetryKeyForChat:chat];

    if (key.length == 0) {
        [self notifyGroupJoinDidFail:failType forChat:chat];
        return;
    }

    if ([self.groupJoinRetryCancelled containsObject:key]) {
        return;
    }

    if ([self.groupJoinRetryRunning containsObject:key]) {
        return;
    }

    [self.groupJoinRetryRunning addObject:key];

    NSString *chatIdHex = chat.groupChatIdHex;
    __weak typeof(self) weakSelf = self;

    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
        for (int attempt = 1; attempt <= 4; attempt++) {
            [NSThread sleepForTimeInterval:2.5 * attempt];

            __strong typeof(weakSelf) self = weakSelf;

            if (! self || [self.groupJoinRetryCancelled containsObject:key]) {
                break;
            }

            dispatch_async(dispatch_get_main_queue(), ^{
                self.groupJoinAttemptByKey[key] = @(attempt);
                OCTChat *bannerChat = [self chatForJoinRetryKey:key chatIdHex:chatIdHex];

                if (bannerChat) {
                    [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTGroupConnectionStatusChangeNotification
                                                                                    object:nil
                                                                                  userInfo:@{
                        kOCTGroupConnectionStatusChangeChatUniqueIdentifierKey: bannerChat.uniqueIdentifier,
                        @"connected": @NO,
                        @"joinAttempt": @(attempt),
                    }];
                }
            });

            OCTChat *currentChat = [self chatForJoinRetryKey:key chatIdHex:chatIdHex];

            if (! currentChat) {
                break;
            }

            OCTTox *tox = [self.dataSource managerGetTox];
            OCTToxGroupNumber activeGroupNumber = (OCTToxGroupNumber)currentChat.groupNumber;

            if (activeGroupNumber == kOCTToxGroupNumberFailure && chatIdHex.length == 64) {
                activeGroupNumber = [self groupNumberInToxForChatIdHex:chatIdHex];
            }

            if (activeGroupNumber != kOCTToxGroupNumberFailure) {
                int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:activeGroupNumber error:nil];

                if (connectionStatus > 0) {
                    dispatch_async(dispatch_get_main_queue(), ^{
                        [self refreshPeersForChat:currentChat];
                        [self.groupJoinRetryRunning removeObject:key];
                    });
                    OCTLogInfo(@"NGC group join retry connected attempt=%d chatId=%@", attempt, chatIdHex);
                    return;
                }

                [tox groupReconnectWithGroupNumber:activeGroupNumber error:nil];
            }
            else if (chatIdHex.length == 64) {
                OCTChat *retryChat = [self chatForJoinRetryKey:key chatIdHex:chatIdHex];
                NSString *password = retryChat.groupPassword;

                dispatch_async(dispatch_get_main_queue(), ^{
                    [self joinGroupWithChatIdHex:chatIdHex
                                        peerName:[self defaultGroupPeerName]
                                        password:password.length > 0 ? password : nil
                                           error:nil];
                });
            }

            OCTLogInfo(@"NGC group join retry attempt=%d chatId=%@", attempt, chatIdHex);
        }

        dispatch_async(dispatch_get_main_queue(), ^{
            __strong typeof(weakSelf) self = weakSelf;

            if (! self) {
                return;
            }

            [self.groupJoinRetryRunning removeObject:key];

            if ([self.groupJoinRetryCancelled containsObject:key]) {
                return;
            }

            OCTChat *currentChat = [self chatForJoinRetryKey:key chatIdHex:chatIdHex];

            if (! currentChat || currentChat.groupNumber < 0) {
                [self notifyGroupJoinDidFail:failType forChat:currentChat ?: chat];
                return;
            }

            OCTTox *tox = [self.dataSource managerGetTox];
            int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:(OCTToxGroupNumber)currentChat.groupNumber error:nil];

            if (connectionStatus <= 0) {
                [self notifyGroupJoinDidFail:failType forChat:currentChat];
            }
        });
    });
}

- (void)scheduleHistSyncBroadcastForGroupNumber:(OCTToxGroupNumber)groupNumber
{
    if (groupNumber == kOCTToxGroupNumberFailure) {
        return;
    }

    [self setupHistSyncIfNeeded];
    [self.histSync scheduleBroadcastHistoryToAllPeersWithGroupNumber:groupNumber];
}

#pragma mark - Friend-assisted join + pending send queue

- (BOOL)shouldQueueGroupMessageForError:(NSError *)error
{
    if (! error) {
        return YES;
    }

    return error.code == OCTToxErrorGroupSendMessageDisconnected ||
           error.code == OCTToxErrorGroupSendMessageGroupNotFound ||
           error.code == OCTToxErrorGroupSendMessageFailSend;
}

- (void)flushPendingGroupMessagesForChat:(OCTChat *)chat
{
    if (! chat || chat.groupNumber < 0 || ! [self isGroupConnectedForChat:chat]) {
        return;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    RLMResults *pendingMessages = [realmManager pendingGroupMessagesForChat:chat];

    if (pendingMessages.count == 0) {
        return;
    }

    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;

    for (OCTMessageAbstract *pendingMessage in pendingMessages) {
        if (pendingMessage.messageText) {
            [self flushPendingGroupTextMessage:pendingMessage chat:chat groupNumber:groupNumber tox:tox realmManager:realmManager];
        }
        else if (pendingMessage.messageFile) {
            [self flushPendingGroupFileMessage:pendingMessage chat:chat groupNumber:groupNumber];
        }
    }
}

- (void)flushAllPendingGroupMessagesIfNeeded
{
    if (! [self.dataSource managerIsToxConnected]) {
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    RLMResults *groupChats = [OCTChat objectsInRealm:realmManager.realm where:@"isGroup == YES"];

    for (OCTChat *chat in groupChats) {
        [self flushPendingGroupMessagesForChat:chat];
    }
}

- (void)flushPendingGroupTextMessage:(OCTMessageAbstract *)pendingMessage
                                chat:(OCTChat *)chat
                         groupNumber:(OCTToxGroupNumber)groupNumber
                                 tox:(OCTTox *)tox
                        realmManager:(OCTRealmManager *)realmManager
{
    OCTMessageText *messageText = pendingMessage.messageText;
    NSString *text = messageText.text;

    if (text.length == 0) {
        return;
    }

    uint32_t messageId = 0;
    NSError *error = nil;
    BOOL sent = [tox groupSendMessage:text
                                 type:messageText.type
                          groupNumber:groupNumber
                            messageId:&messageId
                                error:&error];

    if (! sent) {
        if ([self shouldQueueGroupMessageForError:error]) {
            return;
        }

        OCTLogInfo(@"NGC pending group message send failed chat=%@ error=%@", chat.uniqueIdentifier, error);
        return;
    }

    [realmManager markGroupPendingMessageSent:pendingMessage messageId:messageId];
    [self scheduleHistSyncBroadcastForGroupNumber:groupNumber];
}

- (void)flushPendingGroupFileMessage:(OCTMessageAbstract *)pendingMessage
                                chat:(OCTChat *)chat
                         groupNumber:(OCTToxGroupNumber)groupNumber
{
    OCTMessageFile *messageFile = pendingMessage.messageFile;
    NSString *filePath = [messageFile filePath];
    NSString *msgIdHex = messageFile.groupMsgIdHashHex;

    if (filePath.length == 0 || msgIdHex.length == 0) {
        return;
    }

    [self setupFileTransferIfNeeded];

    __weak typeof(self) weakSelf = self;

    [self.fileTransfer sendFileAtPath:filePath
                          groupNumber:groupNumber
                             msgIdHex:msgIdHex
                             progress:^(float progress) {
                                 __strong typeof(weakSelf) self = weakSelf;

                                 if (! self) {
                                     return;
                                 }

                                 OCTRealmManager *realm = [self.dataSource managerGetRealmManager];
                                 [realm updateObject:messageFile withBlock:^(OCTMessageFile *file) {
                                     file.groupTransferProgress = progress;
                                 }];
                             }
                           completion:^(BOOL success, NSError *sendError) {
                               __strong typeof(weakSelf) self = weakSelf;

                               if (! self) {
                                   return;
                               }

                               OCTRealmManager *realm = [self.dataSource managerGetRealmManager];

                               if (success) {
                                   [realm updateObject:messageFile withBlock:^(OCTMessageFile *file) {
                                       file.fileType = OCTMessageFileTypeReady;
                                       file.groupTransferProgress = 1.0f;
                                       file.isDelivered = YES;
                                   }];
                                   [realm updateObject:pendingMessage withBlock:^(OCTMessageAbstract *abstract) {
                                       abstract.groupPendingSend = NO;
                                   }];
                                   [self scheduleHistSyncBroadcastForGroupNumber:groupNumber];
                                   return;
                               }

                               if (! [self isGroupConnectedForChat:chat]) {
                                   return;
                               }

                               [realm updateObject:messageFile withBlock:^(OCTMessageFile *file) {
                                   file.fileType = OCTMessageFileTypeCanceled;
                               }];
                               [realm updateObject:pendingMessage withBlock:^(OCTMessageAbstract *abstract) {
                                   abstract.groupPendingSend = NO;
                               }];
                               OCTLogInfo(@"NGC pending group file send failed chat=%@ error=%@", chat.uniqueIdentifier, sendError);
                           }];
}

- (void)maintainAllGroups
{
    if (! [self.dataSource managerIsToxConnected]) {
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSArray<OCTChat *> *groupChatsSnapshot = [realmManager groupChatsSnapshot];

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        for (OCTChat *chat in groupChatsSnapshot) {
            if (chat.groupNumber < 0) {
                continue;
            }

            OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
            int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:groupNumber error:nil];
            uint32_t peerCount = [tox groupPeerCountForGroupNumber:groupNumber error:nil];
            OCTToxGroupPrivacyState privacyState = [tox groupPrivacyStateForGroupNumber:groupNumber error:nil];

            if (peerCount <= 1) {
                [self maintainAloneGroupForChat:chat
                                      groupNumber:groupNumber
                                connectionStatus:connectionStatus
                                        peerCount:peerCount];
            }

            if (connectionStatus > 0) {
                [self flushPendingGroupMessagesForChat:chat];
            }
        }
    }];

    [self resendPendingGroupInviteRequests];
}

- (void)maintainAloneGroupForChat:(OCTChat *)chat
                        groupNumber:(OCTToxGroupNumber)groupNumber
                  connectionStatus:(int32_t)connectionStatus
                          peerCount:(uint32_t)peerCount
{
    NSString *chatIdHex = chat.groupChatIdHex.lowercaseString;

    if (chatIdHex.length != 64) {
        return;
    }

    if (peerCount > 1) {
        [self.groupAloneSinceMs removeObjectForKey:chatIdHex];

        if (connectionStatus > 0) {
            [self clearPendingFriendAssistedJoinForChatIdHex:chatIdHex];
        }

        return;
    }

    NSTimeInterval nowMs = [[NSDate date] timeIntervalSince1970] * 1000.0;

    if (! self.groupAloneSinceMs[chatIdHex]) {
        self.groupAloneSinceMs[chatIdHex] = @(nowMs);
    }

    if (connectionStatus <= 0 && ! self.groupConnectStartedMs[chatIdHex]) {
        self.groupConnectStartedMs[chatIdHex] = @(nowMs);
    }

    NSTimeInterval connectStartedMs = [self.groupConnectStartedMs[chatIdHex] doubleValue];

    if (connectStartedMs <= 0) {
        connectStartedMs = nowMs;
        self.groupConnectStartedMs[chatIdHex] = @(nowMs);
    }

    if ((nowMs - connectStartedMs) >= (kOCTGroupFriendFallbackAfterSec * 1000.0)) {
        if ([self shouldRunGroupMaintenanceForKey:chatIdHex
                                       lastRunMap:self.groupLastInviteRequestMs
                                         interval:kOCTGroupMaintenanceInviteIntervalSec]) {
            [self sendGroupInviteRequestToKnownMemberFriendsForChatIdHex:chatIdHex];
            [self sendGroupInviteRequestToFriendsForChatIdHex:chatIdHex pubkeyFilter:nil];
        }
    }

    if (connectionStatus < 0) {
        [[self.dataSource managerGetTox] groupReconnectWithGroupNumber:groupNumber error:nil];
    }
}

// KHANDAQ (#25): the user opened the chat — force an immediate reconnect of a stalled group instead
// of waiting up to 90s for the maintenance tick. Rate-limited (5s) and guarded the same way the tick
// is: status 0 = connecting (announce in progress) and reconnect would restart it from zero, so we
// only revive a genuinely disconnected group (status < 0).
- (void)foregroundReconnectForChat:(OCTChat *)chat
{
    if (! chat || ! chat.isGroup || chat.groupNumber < 0) {
        return;
    }
    if (! [self.dataSource managerIsToxConnected]) {
        return;
    }

    NSString *chatIdHex = chat.groupChatIdHex.lowercaseString;
    if (chatIdHex.length == 64 &&
        ! [self shouldRunGroupMaintenanceForKey:chatIdHex
                                     lastRunMap:self.groupLastForegroundReconnectMs
                                       interval:kOCTGroupForegroundReconnectMinIntervalSec]) {
        return;
    }

    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
    [self performSyncOnToxQueue:^(OCTTox *tox) {
        if ([tox groupConnectionStatusForGroupNumber:groupNumber error:nil] < 0) {
            [tox groupReconnectWithGroupNumber:groupNumber error:nil];
        }
    }];
    [self refreshPeersForChat:chat];
}

- (BOOL)shouldRunGroupMaintenanceForKey:(NSString *)key
                             lastRunMap:(NSMutableDictionary<NSString *, NSNumber *> *)lastRunMap
                               interval:(NSTimeInterval)intervalSec
{
    if (key.length == 0) {
        return NO;
    }

    NSTimeInterval nowMs = [[NSDate date] timeIntervalSince1970] * 1000.0;
    NSTimeInterval lastMs = [lastRunMap[key] doubleValue];

    if (lastMs > 0 && (nowMs - lastMs) < (intervalSec * 1000.0)) {
        return NO;
    }

    lastRunMap[key] = @(nowMs);
    return YES;
}

- (void)sendGroupInviteRequestToKnownMemberFriendsForChatIdHex:(NSString *)chatIdHex
{
    if (chatIdHex.length != 64) {
        return;
    }

    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupChatIdHex:chatIdHex];

    if (! chat) {
        chat = [[self.dataSource managerGetRealmManager] chatWithGroupChatIdHex:[self normalizedGroupChatIdHexString:chatIdHex]];
    }

    NSSet<NSString *> *knownPubkeys = [self collectKnownGroupMemberPublicKeyHexesForChat:chat];

    if (knownPubkeys.count == 0) {
        return;
    }

    [self sendGroupInviteRequestToFriendsForChatIdHex:chatIdHex pubkeyFilter:knownPubkeys];
}

- (NSSet<NSString *> *)collectKnownGroupMemberPublicKeyHexesForChat:(OCTChat *)chat
{
    NSMutableSet<NSString *> *pubkeys = [[self.dataSource managerGetRealmManager] knownGroupMemberPublicKeyHexesForChat:chat].mutableCopy;

    if (chat.groupNumber < 0) {
        return pubkeys;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;

    for (NSDictionary *entry in [tox groupPeersForGroupNumber:groupNumber error:nil]) {
        uint32_t peerId = [entry[@"peerId"] unsignedIntValue];
        NSString *peerPublicKeyHex = [tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil];

        if (peerPublicKeyHex.length > 0) {
            [pubkeys addObject:peerPublicKeyHex.lowercaseString];
        }
    }

    return pubkeys;
}

- (void)sendGroupInviteRequestToFriendsForChatIdHex:(NSString *)chatIdHex
{
    [self sendGroupInviteRequestToFriendsForChatIdHex:chatIdHex pubkeyFilter:nil];
}

- (void)sendGroupInviteRequestToFriendsForChatIdHex:(NSString *)chatIdHex pubkeyFilter:(NSSet<NSString *> *)pubkeyFilter
{
    if (! [self.dataSource managerIsToxConnected] || chatIdHex.length != 64) {
        return;
    }

    NSString *chatIdLower = chatIdHex.lowercaseString;
    uint8_t *chatIdBytes = [OCTTox hexStringToBin:chatIdLower];

    if (! chatIdBytes) {
        return;
    }

    @synchronized (self.pendingFriendAssistedJoins) {
        self.pendingFriendAssistedJoins[chatIdLower] = @([[NSDate date] timeIntervalSince1970] * 1000.0);
    }

    NSMutableData *packet = [NSMutableData dataWithCapacity:(2 + TOX_GROUP_CHAT_ID_SIZE)];
    uint8_t header[] = { kOCTLosslessPktGroupInviteRequest, kOCTGroupInviteRequestVersion };
    [packet appendBytes:header length:sizeof(header)];
    [packet appendBytes:chatIdBytes length:TOX_GROUP_CHAT_ID_SIZE];
    free(chatIdBytes);

    OCTTox *tox = [self.dataSource managerGetTox];
    NSArray<NSNumber *> *friendNumbers = [tox friendsArray];
    NSTimeInterval nowMs = [[NSDate date] timeIntervalSince1970] * 1000.0;
    NSUInteger sentCount = 0;

    for (NSNumber *number in friendNumbers) {
        OCTToxFriendNumber friendNumber = (OCTToxFriendNumber)number.intValue;

        if ([tox friendConnectionStatusWithFriendNumber:friendNumber error:nil] == OCTToxConnectionStatusNone) {
            continue;
        }

        if (pubkeyFilter.count > 0) {
            NSString *friendPubkey = [tox publicKeyFromFriendNumber:friendNumber error:nil];

            if (friendPubkey.length == 0 || ! [pubkeyFilter containsObject:friendPubkey.lowercaseString]) {
                continue;
            }
        }

        NSString *rateKey = [NSString stringWithFormat:@"%d:%@", friendNumber, chatIdLower];
        NSTimeInterval lastRequestMs = [self.lastInviteRequestMs[rateKey] doubleValue];

        if (lastRequestMs > 0 && (nowMs - lastRequestMs) < (kOCTGroupInviteRequestResendSec * 1000.0)) {
            continue;
        }

        self.lastInviteRequestMs[rateKey] = @(nowMs);

        if ([tox sendLosslessPacketWithFriendNumber:friendNumber bytes:packet error:nil]) {
            sentCount++;
        }
    }

    OCTLogInfo(@"NGC friend-assisted join request chatId=%@ sent=%lu", chatIdLower, (unsigned long)sentCount);
}

- (void)handleFriendGroupInviteRequestFromFriendNumber:(OCTToxFriendNumber)friendNumber
                                            chatIdData:(NSData *)chatIdData
{
    if (chatIdData.length != TOX_GROUP_CHAT_ID_SIZE) {
        return;
    }

    NSString *chatIdHex = [self normalizedGroupChatIdHexString:[[OCTTox binToHexString:(uint8_t *)chatIdData.bytes length:chatIdData.length] copy]];
    __block OCTToxGroupNumber groupNumber = kOCTToxGroupNumberFailure;
    __block BOOL invited = NO;

    if (chatIdHex.length != 64) {
        return;
    }

    NSTimeInterval nowMs = [[NSDate date] timeIntervalSince1970] * 1000.0;

    [self performSyncOnToxQueue:^(OCTTox *tox) {
        groupNumber = [self groupNumberInToxForChatIdHex:chatIdHex onTox:tox];

        if (groupNumber == kOCTToxGroupNumberFailure) {
            return;
        }

        NSString *rateKey = [NSString stringWithFormat:@"%d:%u", friendNumber, groupNumber];
        NSTimeInterval lastReplyMs = [self.lastInviteReplyMs[rateKey] doubleValue];

        if (lastReplyMs > 0 && (nowMs - lastReplyMs) < (kOCTGroupInviteReplyMinIntervalSec * 1000.0)) {
            return;
        }

        self.lastInviteReplyMs[rateKey] = @(nowMs);
        invited = [tox groupInviteFriendWithGroupNumber:groupNumber friendNumber:friendNumber error:nil];
    }];

    if (groupNumber == kOCTToxGroupNumberFailure) {
        return;
    }

    OCTLogInfo(@"NGC friend-assisted join reply friend=%d chatId=%@ invited=%d",
               friendNumber, chatIdHex, invited);
}

- (void)resendPendingGroupInviteRequests
{
    NSDictionary<NSString *, NSNumber *> *pendingSnapshot = nil;

    @synchronized (self.pendingFriendAssistedJoins) {
        if (self.pendingFriendAssistedJoins.count == 0) {
            return;
        }

        pendingSnapshot = [self.pendingFriendAssistedJoins copy];
    }

    NSTimeInterval nowMs = [[NSDate date] timeIntervalSince1970] * 1000.0;
    NSMutableArray<NSString *> *expiredKeys = [NSMutableArray array];

    for (NSString *chatIdLower in pendingSnapshot) {
        NSTimeInterval startedMs = [pendingSnapshot[chatIdLower] doubleValue];

        if ((nowMs - startedMs) > (kOCTGroupInviteRequestTTLSec * 1000.0)) {
            [expiredKeys addObject:chatIdLower];
            continue;
        }

        OCTToxGroupNumber groupNumber = [self groupNumberInToxForChatIdHex:chatIdLower];

        if (groupNumber != kOCTToxGroupNumberFailure) {
            OCTTox *tox = [self.dataSource managerGetTox];
            uint32_t peerCount = [tox groupPeerCountForGroupNumber:groupNumber error:nil];
            int32_t connectionStatus = [tox groupConnectionStatusForGroupNumber:groupNumber error:nil];

            if (peerCount > 1 && connectionStatus > 0) {
                [expiredKeys addObject:chatIdLower];
                continue;
            }
        }

        [self sendGroupInviteRequestToFriendsForChatIdHex:chatIdLower];
    }

    if (expiredKeys.count == 0) {
        return;
    }

    @synchronized (self.pendingFriendAssistedJoins) {
        for (NSString *key in expiredKeys) {
            [self.pendingFriendAssistedJoins removeObjectForKey:key];
        }
    }
}

- (void)clearPendingFriendAssistedJoinForChatIdHex:(NSString *)chatIdHex
{
    if (chatIdHex.length == 0) {
        return;
    }

    NSString *key = chatIdHex.lowercaseString;

    @synchronized (self.pendingFriendAssistedJoins) {
        [self.pendingFriendAssistedJoins removeObjectForKey:key];
    }

    [self.groupAloneSinceMs removeObjectForKey:key];
    [self.groupConnectStartedMs removeObjectForKey:key];
}

- (void)startGroupsMaintenanceTimer
{
    if (self.groupsMaintenanceTimer) {
        return;
    }

    dispatch_queue_t queue = dispatch_get_global_queue(QOS_CLASS_UTILITY, 0);
    self.groupsMaintenanceTimer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, queue);
    dispatch_source_set_timer(self.groupsMaintenanceTimer,
                            dispatch_time(DISPATCH_TIME_NOW, (int64_t)(90 * NSEC_PER_SEC)),
                            (int64_t)(90 * NSEC_PER_SEC),
                            (int64_t)(5 * NSEC_PER_SEC));

    __weak typeof(self) weakSelf = self;

    dispatch_source_set_event_handler(self.groupsMaintenanceTimer, ^{
        __strong typeof(weakSelf) self = weakSelf;

        if (! self || ! [self.dataSource managerIsToxConnected]) {
            return;
        }

        if (self.groupBackgroundWorkPaused) {
            return;
        }

        dispatch_async(dispatch_get_main_queue(), ^{
            __strong typeof(weakSelf) selfOnMain = weakSelf;

            if (! selfOnMain || selfOnMain.groupBackgroundWorkPaused) {
                return;
            }

            [selfOnMain syncGroupsWithTox];
        });
    });
    dispatch_resume(self.groupsMaintenanceTimer);
}

- (NSString *)groupSystemMessageWithFormatKey:(NSString *)key argument:(NSString *)argument
{
    NSString *format = [[NSBundle mainBundle] localizedStringForKey:key value:@"%@" table:nil];

    if (argument.length == 0) {
        return format;
    }

    return [NSString stringWithFormat:format, argument];
}

- (void)addGroupSystemMessageWithFormatKey:(NSString *)key argument:(NSString *)argument toChat:(OCTChat *)chat
{
    if (! chat || key.length == 0) {
        return;
    }

    if (! [[self.dataSource managerGetRealmManager].settingsStorage groupShowSystemMessages]) {
        return;
    }

    NSString *text = [self groupSystemMessageWithFormatKey:key argument:argument];

    if (text.length == 0) {
        return;
    }

    [[self.dataSource managerGetRealmManager] addGroupSystemMessageWithText:text inChat:chat];
}

// KHANDAQ: resolve a group peer's display name by STABLE pubkey, not the volatile peerId (which NGC
// reuses on leave/rejoin). The pubkey is captured from the still-valid peerId at the callback instant,
// then matched against the roster by pubkey, so a later peer_id reuse can't attach the wrong name.
// Returns nil if no name resolves (caller supplies a "Peer N" fallback).
- (nullable NSString *)groupPeerNameByPubkeyForGroupNumber:(OCTToxGroupNumber)groupNumber
                                                    peerId:(uint32_t)peerId
                                                      chat:(OCTChat *)chat
{
    OCTTox *tox = [self.dataSource managerGetTox];
    NSString *pubkeyHex = [tox groupPeerPublicKeyHexForGroupNumber:groupNumber peerId:peerId error:nil];

    if (chat && pubkeyHex.length > 0) {
        RLMResults *peers = [[self.dataSource managerGetRealmManager] groupPeersForChatUniqueIdentifier:chat.uniqueIdentifier];
        for (OCTGroupPeer *peer in peers) {
            if (peer.peerPublicKeyHex.length > 0 &&
                [peer.peerPublicKeyHex caseInsensitiveCompare:pubkeyHex] == NSOrderedSame) {
                if (peer.peerName.length > 0) {
                    return peer.peerName;
                }
                break;
            }
        }
    }

    // toxcore direct lookup (peerId valid at this instant) as a last resort before the fallback
    NSArray<NSDictionary *> *tpeers = [tox groupPeersForGroupNumber:groupNumber error:nil];
    for (NSDictionary *entry in tpeers) {
        if ([entry[@"peerId"] unsignedIntValue] == peerId) {
            NSString *name = entry[@"name"];
            if (name.length > 0) {
                return name;
            }
            break;
        }
    }
    return nil;
}

// KHANDAQ (audit, round 6): resolve the STABLE key of the peer a packet came from, for freezing it as
// the row's author. Every toxcore group callback hops to the main queue before we run, so the peer id
// can already be gone from toxcore's list by then and the direct lookup answers nothing — the row is
// stored author-less, and an author-less row falls into the LEGACY branch of the 0x42/0x41 gates,
// where any member can retract it. The roster is the second source: its rows are keyed by peer id and
// dropped only by -removeGroupPeersForChat:notInPeerIds: inside refreshPeersForChat (main queue too,
// so it cannot interleave with us). It is consulted ONLY after toxcore came up empty, i.e. when no
// live peer holds that id — a recycled id is by definition known to toxcore and answered above — so
// the roster row can only be the departed original sender. Returns nil when neither source knows the
// id, which needs toxcore to have delivered a packet from a peer it and our roster both already
// forgot; the caller logs that case so it can be counted in the field instead of guessed at.
- (nullable NSString *)groupStableSenderPubkeyHexForGroupNumber:(OCTToxGroupNumber)groupNumber
                                                         peerId:(uint32_t)peerId
                                                           chat:(OCTChat *)chat
{
    NSString *pubkey = [[self.dataSource managerGetTox] groupPeerPublicKeyHexForGroupNumber:groupNumber
                                                                                     peerId:peerId
                                                                                      error:nil];

    if (pubkey.length > 0) {
        return pubkey;
    }

    if (! chat) {
        return nil;
    }

    OCTGroupPeer *peer = [[self.dataSource managerGetRealmManager] groupPeerForChat:chat peerId:peerId];

    return peer.peerPublicKeyHex.length > 0 ? peer.peerPublicKeyHex : nil;
}

// KHANDAQ (#82/#83): freeze the FILE sender's display name + stable pubkey onto the message at
// receipt, mirroring the text path (resolved by pubkey, not the volatile peerId). Only stamps when
// empty, so a later BEGIN-resume / COMPLETE for the same transfer never overwrites the original
// sender. Without this the file-cell label & reply-quote author were resolved from the volatile
// groupSenderPeerId at render time and drifted to the wrong peer ("Неизвестно"/self) after a restart.
- (void)freezeGroupFileSenderOnMessage:(OCTMessageAbstract *)message
                           groupNumber:(OCTToxGroupNumber)groupNumber
                                peerId:(uint32_t)peerId
                    senderPublicKeyHex:(NSString *)senderPublicKeyHex
                                  chat:(OCTChat *)chat
{
    if (! message.messageFile) {
        return;
    }

    NSString *peerName = [self groupPeerNameByPubkeyForGroupNumber:groupNumber peerId:peerId chat:chat];
    if (peerName.length == 0) {
        peerName = [NSString stringWithFormat:@"Peer %u", peerId];
    }
    // KHANDAQ (audit F-5): prefer the key captured when the packet was delivered. Re-resolving it from
    // the volatile peerId here can stamp the wrong key if the peer number was recycled in between, and
    // that key is now what guards the row against a foreign sender finishing the transfer.
    NSString *pubkey = [senderPublicKeyHex lowercaseString];
    if (pubkey.length == 0) {
        // KHANDAQ (audit, round 6): toxcore first, then the roster — see the resolver's note. A row we
        // leave author-less here is one ANY member can retract via the legacy branch of the 0x42 gate,
        // so it is worth the second lookup. BEGIN and COMPLETE both call this method and it only stamps
        // an empty field, so a transfer whose BEGIN could not resolve still gets stamped at COMPLETE.
        pubkey = [[self groupStableSenderPubkeyHexForGroupNumber:groupNumber peerId:peerId chat:chat] lowercaseString];
    }

    if (pubkey.length == 0) {
        OCTLogWarn(@"NGC group file row stored with no author key group=%u peer=%u", groupNumber, peerId);
    }

    [[self.dataSource managerGetRealmManager] updateObject:message.messageFile withBlock:^(OCTMessageFile *file) {
        if (file.groupPeerName.length == 0) {
            file.groupPeerName = peerName;
        }
        if (pubkey.length > 0 && file.groupSenderPubkey.length == 0) {
            file.groupSenderPubkey = pubkey;
        }
    }];
}

// KHANDAQ (audit, round 5): freeze the TEXT sender's stable pubkey onto an incoming group row, the
// counterpart of freezeGroupFileSenderOnMessage above. OCTMessageText has no key column of its own and
// this batch adds no schema, so the row-level groupPrivatePeerPubkey carries it — for an INCOMING row
// that field already means "the other peer's stable key" (see its header note), which is the author.
// Only stamps when empty, so a later touch never re-attributes an existing message.
//
// KHANDAQ (audit, round 6): takes the group/peer/chat too, so that when the key captured at delivery
// is empty it can go through the same two-source resolver the file path uses. A text row has no
// second event to be stamped by later (unlike a file's BEGIN/COMPLETE pair): whatever it is stamped
// with here is final, and staying author-less is what drops it into the legacy branch of the gates.
- (void)freezeGroupTextSenderPubkeyOnMessage:(OCTMessageAbstract *)message
                            senderPublicKeyHex:(NSString *)senderPublicKeyHex
                                   groupNumber:(OCTToxGroupNumber)groupNumber
                                        peerId:(uint32_t)peerId
                                          chat:(OCTChat *)chat
{
    if (! message || message.groupPrivatePeerPubkey.length > 0) {
        return;
    }

    NSString *pubkey = [senderPublicKeyHex lowercaseString];

    if (pubkey.length == 0) {
        pubkey = [[self groupStableSenderPubkeyHexForGroupNumber:groupNumber peerId:peerId chat:chat] lowercaseString];
    }

    if (pubkey.length == 0) {
        OCTLogWarn(@"NGC group text row stored with no author key group=%u peer=%u", groupNumber, peerId);
        return;
    }

    [[self.dataSource managerGetRealmManager] updateObject:message withBlock:^(OCTMessageAbstract *theMessage) {
        if (theMessage.groupPrivatePeerPubkey.length == 0) {
            theMessage.groupPrivatePeerPubkey = pubkey;
        }
    }];
}

- (NSString *)peerDisplayNameForGroupNumber:(OCTToxGroupNumber)groupNumber peerId:(uint32_t)peerId
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] chatWithGroupNumber:groupNumber];
    NSString *name = [self groupPeerNameByPubkeyForGroupNumber:groupNumber peerId:peerId chat:chat];
    if (name.length > 0) {
        return name;
    }
    return [NSString stringWithFormat:@"Peer %u", peerId];
}

#pragma mark - Reconnect noise & send precheck

- (NSString *)groupPeerEventKeyForChat:(OCTChat *)chat peerId:(uint32_t)peerId
{
    return [NSString stringWithFormat:@"%@:%u", chat.uniqueIdentifier, peerId];
}

- (void)markGroupPeerReconnectSuppressForChat:(OCTChat *)chat peerId:(uint32_t)peerId
{
    NSTimeInterval until = [[NSDate date] timeIntervalSince1970] + kOCTGroupReconnectSuppressSec;
    self.groupPeerReconnectSuppressUntil[[self groupPeerEventKeyForChat:chat peerId:peerId]] = @(until);
}

// KHANDAQ (#87): a PERSISTENT record of every pubkey we've already processed a join for in a chat.
// The peer-row table (used by the peerKnownBefore check) is pruned by refreshPeersForChat whenever a
// volatile NGC peer_id drops from toxcore's list on a flap, so a long-time member who merely
// reconnects looked "new" again and re-announced "joined" — endlessly. This store is never pruned and
// survives app restarts, so a join is announced exactly once per pubkey per chat.
- (NSString *)persistentAnnouncedJoinsKeyForChat:(OCTChat *)chat
{
    return [@"KhandaqAnnouncedJoins:" stringByAppendingString:chat.uniqueIdentifier ?: @""];
}

- (BOOL)hasPersistentlyAnnouncedJoinForChat:(OCTChat *)chat pubkeyLower:(NSString *)pubkeyLower
{
    if (chat.uniqueIdentifier.length == 0 || pubkeyLower.length == 0) {
        return NO;
    }
    NSArray *arr = [[NSUserDefaults standardUserDefaults] arrayForKey:[self persistentAnnouncedJoinsKeyForChat:chat]];
    return arr != nil && [arr containsObject:pubkeyLower];
}

- (void)markPersistentlyAnnouncedJoinForChat:(OCTChat *)chat pubkeyLower:(NSString *)pubkeyLower
{
    if (chat.uniqueIdentifier.length == 0 || pubkeyLower.length == 0) {
        return;
    }
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    NSString *key = [self persistentAnnouncedJoinsKeyForChat:chat];
    NSArray *existing = [defaults arrayForKey:key];
    if ([existing containsObject:pubkeyLower]) {
        return;
    }
    NSMutableArray *updated = existing ? [existing mutableCopy] : [NSMutableArray array];
    [updated addObject:pubkeyLower];
    if (updated.count > 512) {
        [updated removeObjectsInRange:NSMakeRange(0, updated.count - 512)];
    }
    [defaults setObject:updated forKey:key];
}

- (BOOL)shouldSuppressGroupPeerEventForChat:(OCTChat *)chat peerId:(uint32_t)peerId
{
    NSString *key = [self groupPeerEventKeyForChat:chat peerId:peerId];
    NSNumber *until = self.groupPeerReconnectSuppressUntil[key];

    if (! until) {
        return NO;
    }

    if ([[NSDate date] timeIntervalSince1970] > until.doubleValue) {
        [self.groupPeerReconnectSuppressUntil removeObjectForKey:key];
        return NO;
    }

    return YES;
}

- (BOOL)precheckCanSendInChat:(OCTChat *)chat error:(NSError **)error
{
    if ([self selfRoleInChat:chat] == OCTToxGroupRoleObserver) {
        if (error) {
            *error = [NSError errorWithDomain:kOCTSubmanagerGroupsErrorDomain
                                         code:OCTSubmanagerGroupsErrorObserverCannotSend
                                     userInfo:nil];
        }
        return NO;
    }

    return YES;
}

- (NSString *)localizedPrivacyNameForState:(OCTToxGroupPrivacyState)privacyState
{
    NSString *key = privacyState == OCTToxGroupPrivacyStatePrivate
        ? @"group_system_privacy_private"
        : @"group_system_privacy_public";
    return [[NSBundle mainBundle] localizedStringForKey:key value:key table:nil];
}

@end
