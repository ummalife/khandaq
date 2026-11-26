// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerFriendsImpl.h"
#import "OCTLogging.h"
#import "OCTTox.h"
#import "OCTTox+Private.h"
#import "OCTFriend.h"
#import "OCTFriendRequest.h"
#import "OCTRealmManager.h"
#import "OCTPushUrlValidator.h"
#import "OCTPushPreview.h"
#import "OCTSubmanagerChatsImpl.h"
#import "OCTChat.h"
#import "OCTGroupContactExchange.h"
#import "OCTGroupContactStore.h"
#if __has_include(<Firebase/Firebase.h>)
#import <Firebase/Firebase.h>
#define OCTHasFirebase 1
#else
#define OCTHasFirebase 0
#endif

@interface OCTSubmanagerFriendsImpl ()

@property (assign, nonatomic) BOOL networkObserverRegistered;

@end

@implementation OCTSubmanagerFriendsImpl
@synthesize dataSource = _dataSource;

- (void)dealloc
{
    [[self.dataSource managerGetNotificationCenter] removeObserver:self];
}

static NSString *OCTShortPublicKeyLabel(NSString *publicKey)
{
    if (publicKey.length < 6) {
        return publicKey;
    }

    return [[publicKey substringFromIndex:publicKey.length - 6] uppercaseString];
}

static void OCTSendPushURLToFriend(OCTTox *tox, OCTToxFriendNumber friendNumber, id<OCTSubmanagerChats> chats)
{
#if OCTHasFirebase
    if ([FIRApp defaultApp] == nil) {
        return;
    }

    NSString *token = [FIRMessaging messaging].FCMToken;

    if (token.length == 0) {
        return;
    }

    // KHANDAQ (re-audit 2026-08-22, K-01): the capability this device issued to THIS contact.
    // Absent until the app target has registered one, and absent is the pre-capability behaviour.
    NSString *friendPublicKey = [tox publicKeyFromFriendNumber:friendNumber error:nil];
    NSString *data = [@"A" stringByAppendingString:
                      [OCTPushUrlValidator ownWakeURLForToken:token friendPublicKey:friendPublicKey]];
    NSError *error = nil;
    BOOL result = [tox sendLosslessPacketWithFriendNumber:friendNumber
                                                   pktid:181
                                                    data:data
                                                   error:&error];

    if (! result) {
        NSLog(@"push URL send failed for friendNumber %d: %@", friendNumber, error);
    }

    // KHANDAQ (0.2.64): wherever the wake URL goes, the preview key and the group presence go with it
    // (packets 190/01 and 190/02, docs/PUSH_PREVIEWS.md), so the friend can seal what its wakes say for
    // this device and knows which member we are in a group we share.
    NSData *keyPacket = [OCTPushPreview keyPacketForFriendPublicKey:friendPublicKey];
    if (keyPacket != nil) {
        [tox sendLosslessPacketWithFriendNumber:friendNumber bytes:keyPacket error:nil];
    }
    NSData *presencePacket = [OCTPushPreview groupPresencePacketWithHashes:[OCTPushPreview ownGroupPresenceHashesWithTox:tox]];
    [tox sendLosslessPacketWithFriendNumber:friendNumber bytes:presencePacket error:nil];

    // KHANDAQ (0.2.69, group message wakes): and the groups of ours in which the friend's group messages may wake this
    // device (190/03). A moment later on the main queue: the set is read from the Realm, and one caller sends this
    // from inside a write to it.
    if ([chats isKindOfClass:[OCTSubmanagerChatsImpl class]] && friendPublicKey.length > 0) {
        OCTSubmanagerChatsImpl *chatsImpl = (OCTSubmanagerChatsImpl *)chats;
        dispatch_async(dispatch_get_main_queue(), ^{
            [chatsImpl sendGroupWakeToFriendWithPublicKey:friendPublicKey];
        });
    }
#endif
}

static BOOL OCTIsGenericDefaultFriendName(NSString *name)
{
    if (name.length == 0) {
        return YES;
    }

    NSString *trimmed = [name stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if (trimmed.length == 0) {
        return YES;
    }

    if ([trimmed caseInsensitiveCompare:@"Khandaq"] == NSOrderedSame) {
        return YES;
    }

    if (trimmed.length >= 5 && [[trimmed substringToIndex:5] caseInsensitiveCompare:@"TRIfA"] == NSOrderedSame) {
        return YES;
    }

    return NO;
}

static void OCTApplyFriendName(OCTFriend *friend, NSString *name, NSString *publicKey, NSString *groupMemberName)
{
    // KHANDAQ (#97): preserve a user-chosen nickname. Only keep the nickname tracking the friend's
    // name when the user hasn't set a custom alias (nickname empty, still equal to the previous name,
    // or still the auto-derived public-key label). This helper runs on friend load/connect/refresh, so
    // without the guard a restart or reconnect clobbers the alias back to the name.
    // KHANDAQ (26.11.2026): a contact added from a group (groupMemberName, their name there) is named with it until
    // their own name comes, and keeps it while their own name is the default one.
    BOOL nicknameTracksName = (friend.nickname.length == 0)
        || [friend.nickname isEqualToString:friend.name]
        || [friend.nickname isEqualToString:OCTShortPublicKeyLabel(publicKey)]
        || (groupMemberName.length > 0 && [friend.nickname isEqualToString:groupMemberName]);

    friend.name = name ?: @"";

    if (nicknameTracksName) {
        if (!OCTIsGenericDefaultFriendName(name)) {
            friend.nickname = name;
        } else if (groupMemberName.length > 0) {
            friend.nickname = groupMemberName;
        } else {
            friend.nickname = OCTShortPublicKeyLabel(publicKey);
        }
    }
}

static NSError *OCTFriendAddAlreadySentError(void)
{
    return [NSError errorWithDomain:kOCTToxErrorDomain
                               code:OCTToxErrorFriendAddAlreadySent
                           userInfo:@{
                               NSLocalizedDescriptionKey: @"Cannot add friend",
                               NSLocalizedFailureReasonErrorKey: @"This contact is already in your list or a request was already sent.",
                           }];
}

static BOOL OCTIsValidFriendAddAddress(NSString *address, NSError **error)
{
    NSString *normalized = [[address stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] uppercaseString];

    if (normalized.length != kOCTToxAddressLength) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorFriendAddBadChecksum
                                     description:@"Cannot add friend"
                                   failureReason:@"Address must be exactly 76 hex characters"];
        }
        return NO;
    }

    NSCharacterSet *nonHex = [[NSCharacterSet characterSetWithCharactersInString:@"0123456789ABCDEF"] invertedSet];
    if ([normalized rangeOfCharacterFromSet:nonHex].location != NSNotFound) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorFriendAddBadChecksum
                                     description:@"Cannot add friend"
                                   failureReason:@"Address contains non-hex characters"];
        }
        return NO;
    }

    return YES;
}

static void OCTRefreshFriendNameFromTox(OCTTox *tox, OCTFriend *friend, OCTToxFriendNumber friendNumber, NSString *groupMemberName)
{
    NSError *error = nil;
    NSString *liveName = [tox friendNameWithFriendNumber:friendNumber error:&error];

    if (error || liveName.length == 0) {
        return;
    }

    OCTApplyFriendName(friend, liveName, friend.publicKey, groupMemberName);
}

#pragma mark -  Public

- (BOOL)sendFriendRequestToAddress:(NSString *)address message:(NSString *)message error:(NSError **)error
{
    NSParameterAssert(address);

    if (!OCTIsValidFriendAddAddress(address, error)) {
        return NO;
    }

    NSString *normalizedAddress = [[address stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] uppercaseString];
    NSString *publicKey = [normalizedAddress substringToIndex:kOCTToxPublicKeyLength];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTFriend *existingFriend = [realmManager friendWithPublicKey:publicKey];

    if (existingFriend && existingFriend.connectionStatus != OCTToxConnectionStatusNone) {
        if (error) {
            *error = OCTFriendAddAlreadySentError();
        }
        return NO;
    }

    NSString *requestMessage = message.length > 0 ? message : @"Khandaq";
    OCTTox *tox = [self.dataSource managerGetTox];

    OCTToxFriendNumber friendNumber = [tox addFriendWithAddress:normalizedAddress message:requestMessage error:error];

    if (friendNumber == kOCTToxFriendNumberFailure) {
        if (error && ((*error).code == OCTToxErrorFriendAddAlreadySent || (*error).code == OCTToxErrorFriendAddSetNewNospam)) {
            return [self resendFriendRequestToAddress:normalizedAddress message:message error:error];
        }

        return NO;
    }

    [self.dataSource managerSaveTox];

    return [self createFriendWithFriendNumber:friendNumber error:error];
}

- (BOOL)resendFriendRequestToAddress:(NSString *)address message:(NSString *)message error:(NSError **)error
{
    if (!OCTIsValidFriendAddAddress(address, error)) {
        return NO;
    }

    NSString *normalizedAddress = [[address stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] uppercaseString];
    NSString *publicKey = [normalizedAddress substringToIndex:kOCTToxPublicKeyLength];
    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTFriend *existingFriend = [realmManager friendWithPublicKey:publicKey];

    if (existingFriend && existingFriend.connectionStatus != OCTToxConnectionStatusNone) {
        if (error) {
            *error = OCTFriendAddAlreadySentError();
        }
        return NO;
    }

    if (existingFriend) {
        [tox deleteFriendWithFriendNumber:existingFriend.friendNumber error:nil];
        [realmManager deleteObject:existingFriend];
    }
    else {
        for (NSNumber *friendNumberValue in [tox friendsArray]) {
            OCTToxFriendNumber friendNumber = [friendNumberValue intValue];
            NSString *friendPublicKey = [tox publicKeyFromFriendNumber:friendNumber error:nil];

            if ([friendPublicKey.uppercaseString isEqualToString:publicKey]) {
                [tox deleteFriendWithFriendNumber:friendNumber error:nil];
                break;
            }
        }
    }

    [self.dataSource managerSaveTox];

    NSError *retryError = nil;
    NSString *requestMessage = message.length > 0 ? message : @"Khandaq";
    OCTToxFriendNumber friendNumber = [tox addFriendWithAddress:normalizedAddress message:requestMessage error:&retryError];

    if (error) {
        *error = retryError;
    }

    if (friendNumber == kOCTToxFriendNumberFailure) {
        return NO;
    }

    [self.dataSource managerSaveTox];

    return [self createFriendWithFriendNumber:friendNumber error:error];
}

- (BOOL)addFriendByPublicKey:(NSString *)publicKey error:(NSError **)error
{
    // KHANDAQ (#15): add an NGC group peer by their 64-hex public key (tox_friend_add_norequest).
    // Group peers have no full Tox ID, so the classic sendFriendRequestToAddress: path rejects them.
    NSString *normalizedKey = [[publicKey stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] uppercaseString];

    // Guard the length up front: addFriendWithNoRequestWithPublicKey: asserts on a wrong-length key,
    // which would crash instead of returning an error.
    if (normalizedKey.length != kOCTToxPublicKeyLength) {
        if (error) {
            *error = OCTFriendAddAlreadySentError();
        }
        return NO;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTFriend *existingFriend = [realmManager friendWithPublicKey:normalizedKey];

    if (existingFriend) {
        if (error) {
            *error = OCTFriendAddAlreadySentError();
        }
        return NO;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTToxFriendNumber friendNumber = [tox addFriendWithNoRequestWithPublicKey:normalizedKey error:error];

    if (friendNumber == kOCTToxFriendNumberFailure) {
        return NO;
    }

    [self.dataSource managerSaveTox];

    return [self createFriendWithFriendNumber:friendNumber error:error];
}

- (BOOL)approveFriendRequest:(OCTFriendRequest *)friendRequest error:(NSError **)error
{
    NSParameterAssert(friendRequest);

    // KHANDAQ (26.11.2026): a member who asked in a group is added with their whole ID, and told so.
    OCTGroupContactStore *store = [self groupContactStore];
    OCTGroupContactIncomingOffer *groupOffer = [store incomingOfferFromContact:friendRequest.publicKey];
    if (groupOffer != nil) {
        return [self approveGroupContactRequest:friendRequest offer:groupOffer store:store error:error];
    }

    OCTTox *tox = [self.dataSource managerGetTox];

    OCTToxFriendNumber friendNumber = [tox addFriendWithNoRequestWithPublicKey:friendRequest.publicKey error:error];

    if (friendNumber == kOCTToxFriendNumberFailure) {
        return NO;
    }

    [self.dataSource managerSaveTox];

    [[self.dataSource managerGetRealmManager] deleteObject:friendRequest];

    return [self createFriendWithFriendNumber:friendNumber error:error];
}

- (void)removeFriendRequest:(OCTFriendRequest *)friendRequest
{
    NSParameterAssert(friendRequest);

    // KHANDAQ (26.11.2026): declining an offer from a group sends nothing, as declining a request by ID does.
    if (friendRequest.publicKey.length > 0) {
        [[self groupContactStore] removeIncomingOfferFromContact:friendRequest.publicKey];
    }
    [[self.dataSource managerGetRealmManager] deleteObject:friendRequest];
}

- (nullable OCTGroupContactOffer *)groupContactOfferForFriendRequest:(OCTFriendRequest *)friendRequest
{
    if (friendRequest == nil || friendRequest.isInvalidated || friendRequest.publicKey.length == 0) {
        return nil;
    }
    return [[[self groupContactStore] incomingOfferFromContact:friendRequest.publicKey] offer];
}

- (BOOL)finishGroupContactOffer:(OCTGroupContactPendingOffer *)offer
            contactPublicKeyHex:(NSString *)contactPublicKeyHex
              contactAddressHex:(nullable NSString *)contactAddressHex
{
    NSString *contactKey = contactPublicKeyHex.uppercaseString;
    if (contactKey.length != kOCTToxPublicKeyLength) {
        return NO;
    }
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTGroupContactStore *store = [self groupContactStore];

    BOOL added = NO;
    if ([realmManager friendWithPublicKey:contactKey] == nil) {
        // Kept first: the new contact is named with it (OCTApplyFriendName).
        [store saveMemberName:offer.memberName forContact:contactKey];
        OCTTox *tox = [self.dataSource managerGetTox];
        OCTToxFriendNumber friendNumber = [tox addFriendWithNoRequestWithPublicKey:contactKey error:nil];
        if (friendNumber == kOCTToxFriendNumberFailure) {
            return NO;
        }
        [self.dataSource managerSaveTox];
        if (! [self createFriendWithFriendNumber:friendNumber error:nil]) {
            return NO;
        }
        added = YES;
    }

    // A request of theirs waiting here (both asked) is answered by this.
    RLMResults *requests = [realmManager objectsWithClass:[OCTFriendRequest class]
                                                predicate:[NSPredicate predicateWithFormat:@"publicKey ==[c] %@", contactKey]];
    NSMutableArray<OCTFriendRequest *> *answered = [NSMutableArray array];
    for (OCTFriendRequest *request in requests) {
        [answered addObject:request];
    }
    for (OCTFriendRequest *request in answered) {
        [realmManager deleteObject:request];
    }
    [store removeIncomingOfferFromContact:contactKey];
    [store saveLink:[[OCTGroupContactLink alloc] initWithContactPublicKeyHex:contactKey contactAddressHex:contactAddressHex]
          forMember:offer.memberPublicKeyHex
            inGroup:offer.groupChatIdHex];

    if (added) {
        [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTGroupContactAddedNotification
                                                                      object:nil
                                                                    userInfo:@{
                                                                        kOCTGroupContactAddedNameKey: offer.memberName ?: @"",
                                                                        kOCTGroupContactAddedPublicKeyKey: contactKey,
                                                                    }];
    }
    return YES;
}

- (BOOL)removeFriend:(OCTFriend *)friend error:(NSError **)error
{
    NSParameterAssert(friend);

    OCTTox *tox = [self.dataSource managerGetTox];

    if (! [tox deleteFriendWithFriendNumber:friend.friendNumber error:error]) {
        return NO;
    }

    [self.dataSource managerSaveTox];

    // KHANDAQ (0.2.64): the preview key and group presence that contact sent go with them.
    [OCTPushPreview forgetFriendPublicKey:friend.publicKey];

    [[self.dataSource managerGetRealmManager] deleteObject:friend];

    return YES;
}

#pragma mark -  Private category

- (void)configure
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTTox *tox = [self.dataSource managerGetTox];

    [realmManager updateObjectsWithClass:[OCTFriend class] predicate:nil updateBlock:^(OCTFriend *friend) {
        // Tox may change friendNumber after relaunch, resetting them.
        friend.friendNumber = kOCTToxFriendNumberFailure;
    }];

    for (NSNumber *friendNumber in [tox friendsArray]) {
        OCTToxFriendNumber number = [friendNumber intValue];
        NSError *error;

        NSString *publicKey = [tox publicKeyFromFriendNumber:number error:&error];

        if (! publicKey) {
            @throw [NSException exceptionWithName:@"Cannot find publicKey for existing friendNumber, Tox save data is broken"
                                           reason:error.debugDescription
                                         userInfo:nil];
        }

        NSPredicate *predicate = [NSPredicate predicateWithFormat:@"publicKey == %@", publicKey];
        RLMResults *results = [realmManager objectsWithClass:[OCTFriend class] predicate:predicate];

        if (results.count == 0) {
            // It seems that friend is in Tox but isn't in Realm. Let's add it.
            [self createFriendWithFriendNumber:number error:nil];
            continue;
        }

        OCTFriend *friend = [results firstObject];
        // Read before the write block: the store reads Realm on its queue.
        NSString *groupMemberName = [self groupMemberNameForContact:publicKey];

        __block BOOL friendIsConnected = NO;

        [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
            theFriend.friendNumber = number;
            theFriend.isTyping = NO;
            theFriend.status = [tox friendStatusWithFriendNumber:number error:nil];

            OCTToxConnectionStatus connectionStatus = [tox friendConnectionStatusWithFriendNumber:number error:nil];
            theFriend.connectionStatus = connectionStatus;
            theFriend.isConnected = (connectionStatus != OCTToxConnectionStatusNone);
            friendIsConnected = theFriend.isConnected;

            if (theFriend.isConnected) {
                OCTRefreshFriendNameFromTox(tox, theFriend, number, groupMemberName);

                OCTToxCapabilities f_caps = [tox friendGetCapabilitiesWithFriendNumber:number];
                theFriend.capabilities2 = [NSString stringWithFormat:@"%lu", f_caps];
            }
            else {
                NSDate *dateOffline = [tox friendGetLastOnlineWithFriendNumber:number error:nil];
                theFriend.lastSeenOnlineInterval = [dateOffline timeIntervalSince1970];
                OCTRefreshFriendNameFromTox(tox, theFriend, number, groupMemberName);
            }
        }];

        if (friendIsConnected) {
            OCTSendPushURLToFriend(tox, number, [self.dataSource managerGetChats]);
            [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTFriendConnectionStatusChangeNotification
                                                                        object:friend];
        }
    }

    // Remove all OCTFriend's which aren't bounded to tox. User cannot interact with them anyway.
    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"friendNumber == %d", kOCTToxFriendNumberFailure];
    RLMResults *results = [realmManager objectsWithClass:[OCTFriend class] predicate:predicate];

    for (OCTFriend *friend in results) {
        [realmManager deleteObject:friend];
    }

    if (! self.networkObserverRegistered) {
        [[self.dataSource managerGetNotificationCenter] addObserver:self
                                                             selector:@selector(networkRebootstrapCompleted:)
                                                                 name:kOCTNetworkRebootstrapCompletedNotification
                                                               object:nil];
        self.networkObserverRegistered = YES;
    }
}

- (void)networkRebootstrapCompleted:(NSNotification *)notification
{
    [self resyncAllFriendConnectionStatuses];
}

- (void)refreshConnectionStatuses
{
    [self resyncAllFriendConnectionStatuses];
}

- (OCTFriend *)ensureFriendForFriendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *publicKey = [tox publicKeyFromFriendNumber:friendNumber error:nil];

    if (publicKey.length == 0) {
        return nil;
    }

    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];

    if (friend) {
        return friend;
    }

    if (! [self createFriendWithFriendNumber:friendNumber error:nil]) {
        return nil;
    }

    return [realmManager friendWithPublicKey:publicKey];
}

- (void)resyncAllFriendConnectionStatuses
{
    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    RLMResults *friends = [realmManager objectsWithClass:[OCTFriend class] predicate:nil];

    for (OCTFriend *friend in friends) {
        if (friend.friendNumber == kOCTToxFriendNumberFailure) {
            continue;
        }

        OCTToxConnectionStatus status = [tox friendConnectionStatusWithFriendNumber:friend.friendNumber error:nil];
        const BOOL connected = (status != OCTToxConnectionStatusNone);

        if (friend.connectionStatus == status && friend.isConnected == connected) {
            continue;
        }

        NSString *groupMemberName = [self groupMemberNameForContact:friend.publicKey];
        [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
            theFriend.connectionStatus = status;
            theFriend.isConnected = connected;

            if (connected) {
                OCTRefreshFriendNameFromTox(tox, theFriend, theFriend.friendNumber, groupMemberName);
            }
        }];

        if (connected) {
            OCTSendPushURLToFriend(tox, friend.friendNumber, [self.dataSource managerGetChats]);
        }

        [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTFriendConnectionStatusChangeNotification
                                                                    object:friend];
    }
}

#pragma mark -  OCTToxDelegate

- (void)tox:(OCTTox *)tox friendRequestWithMessage:(NSString *)message publicKey:(NSString *)publicKey
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    // KHANDAQ (26.11.2026): a member we offered to add accepted: their request carries the nonce of our offer and is
    // accepted without asking. A marker with a nonce we do not know makes an ordinary request, read without it. The
    // offer is taken, not read: their accept over the group answers it too, and only the answer that takes it adds.
    NSString *nonceHex = [OCTGroupContactExchange nonceHexInRequestMessage:message];
    if (nonceHex != nil) {
        OCTGroupContactPendingOffer *offer = [[self groupContactStore] takePendingOfferWithNonceHex:nonceHex
                                                                                                 at:[[NSDate date] timeIntervalSince1970]];
        if (offer != nil && [self finishGroupContactOffer:offer contactPublicKeyHex:publicKey contactAddressHex:nil]) {
            return;
        }
    }
    message = [OCTGroupContactExchange requestMessageWithoutMarker:message];

    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"publicKey == %@", publicKey];
    RLMResults *results = [realmManager objectsWithClass:[OCTFriendRequest class] predicate:predicate];
    if (results.count > 0) {
        return;
    }

    results = [realmManager objectsWithClass:[OCTFriend class] predicate:predicate];
    if (results.count > 0) {
        return;
    }

    OCTFriendRequest *request = [OCTFriendRequest new];
    request.publicKey = publicKey;
    request.message = message;
    request.dateInterval = [[NSDate date] timeIntervalSince1970];

    [realmManager addObject:request];
}

- (void)tox:(OCTTox *)tox friendNameUpdate:(NSString *)name friendNumber:(OCTToxFriendNumber)friendNumber
{
    [self.dataSource managerSaveTox];

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];

    if (! friend) {
        return;
    }

    NSString *groupMemberName = [self groupMemberNameForContact:publicKey];
    [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
        // KHANDAQ (#97): apply via the shared helper, which preserves a user-chosen nickname.
        OCTApplyFriendName(theFriend, name, publicKey, groupMemberName);
    }];
}

- (void)tox:(OCTTox *)tox friendPushTokenUpdate:(NSString *)pushToken friendNumber:(OCTToxFriendNumber)friendNumber
{
    // KHANDAQ (audit 2026-08-20): validate at the STORAGE boundary. This string came off the wire
    // from a contact, unchecked, and used to go straight into Realm — from where the send path fed
    // it to +[NSURL URLWithString:] and, on nil, raised inside a dispatch_async block and killed the
    // app. Refusing it here means a malformed or foreign token never gets persisted at all, so the
    // dangerous value cannot outlive the packet that carried it. Android has refused the same shapes
    // since audit A33 (PushUrlValidator.java).
    if (! [OCTPushUrlValidator isAllowedPushURL:pushToken]) {
        OCTLogWarn(@"friendPushTokenUpdate: rejecting a push token that is not a usable wake URL");
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];

    if (! friend) {
        return;
    }

    [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
        theFriend.pushToken = pushToken;
    }];
}

- (void)tox:(OCTTox *)tox friendPushPreviewKey:(NSData *)key friendNumber:(OCTToxFriendNumber)friendNumber
{
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    if ([[self.dataSource managerGetRealmManager] friendWithPublicKey:publicKey] == nil) {
        return;
    }
    [OCTPushPreview storePeerKey:key forFriendPublicKey:publicKey];
}

- (void)tox:(OCTTox *)tox friendGroupPresence:(NSArray<NSString *> *)hashes friendNumber:(OCTToxFriendNumber)friendNumber
{
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    if ([[self.dataSource managerGetRealmManager] friendWithPublicKey:publicKey] == nil) {
        return;
    }
    [OCTPushPreview storeGroupPresence:hashes forFriendPublicKey:publicKey];

    // KHANDAQ (0.2.69, group message wakes): our 190/03 set for the friend leaves out a group where a member muted here
    // is the friend, and only this set tells which member that is.
    id<OCTSubmanagerChats> chats = [self.dataSource managerGetChats];
    if ([chats isKindOfClass:[OCTSubmanagerChatsImpl class]]) {
        [(OCTSubmanagerChatsImpl *)chats sendGroupWakeToFriendWithPublicKey:publicKey];
    }
}

- (void)tox:(OCTTox *)tox friendGroupWake:(NSArray<NSString *> *)hashes friendNumber:(OCTToxFriendNumber)friendNumber
{
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    if ([[self.dataSource managerGetRealmManager] friendWithPublicKey:publicKey] == nil) {
        return;
    }
    [OCTPushPreview storeGroupWake:hashes forFriendPublicKey:publicKey];
}

- (void)tox:(OCTTox *)tox friendStatusMessageUpdate:(NSString *)statusMessage friendNumber:(OCTToxFriendNumber)friendNumber
{
    [self.dataSource managerSaveTox];

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];

    if (! friend) {
        return;
    }

    [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
        theFriend.statusMessage = statusMessage;
    }];
}

- (void)tox:(OCTTox *)tox friendStatusUpdate:(OCTToxUserStatus)status friendNumber:(OCTToxFriendNumber)friendNumber
{
    [self.dataSource managerSaveTox];

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];

    if (! friend) {
        return;
    }

    [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
        theFriend.status = status;
    }];
}

- (void)tox:(OCTTox *)tox friendIsTypingUpdate:(BOOL)isTyping friendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];

    if (! friend) {
        return;
    }

    [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
        theFriend.isTyping = isTyping;
    }];
}

- (void)tox:(OCTTox *)tox friendSetMsgv3Capability:(BOOL)msgv3Capability friendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];

    if (! friend || friend.msgv3Capability == msgv3Capability) {
        return;
    }

    [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
        theFriend.msgv3Capability = msgv3Capability;
    }];
}

- (void)tox:(OCTTox *)tox friendConnectionStatusChanged:(OCTToxConnectionStatus)status friendNumber:(OCTToxFriendNumber)friendNumber
{
    [self.dataSource managerSaveTox];

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];

    if (! friend) {
        return;
    }

    NSString *groupMemberName = [self groupMemberNameForContact:publicKey];
    [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {

        if ((status != OCTToxConnectionStatusNone)
            && (theFriend.connectionStatus == OCTToxConnectionStatusNone))
        {
            // Friend is coming online now
            OCTRefreshFriendNameFromTox(tox, theFriend, friendNumber, groupMemberName);

            OCTToxCapabilities f_caps = [tox friendGetCapabilitiesWithFriendNumber:friendNumber];
            OCTLogVerbose(@"f_caps=%lu", f_caps);
            NSString* cap_string = [NSString stringWithFormat:@"%lu", f_caps];
            theFriend.capabilities2 = cap_string;

            NSString *token = nil;
#if OCTHasFirebase
            if ([FIRApp defaultApp] != nil) {
                token = [FIRMessaging messaging].FCMToken;
            }
#endif
            if (token.length > 0)
            {
                OCTSendPushURLToFriend(tox, friendNumber, [self.dataSource managerGetChats]);
            }
        }
        theFriend.isConnected = (status != OCTToxConnectionStatusNone);
        theFriend.connectionStatus = status;

        if (! theFriend.isConnected) {
            // Friend is offline now
            NSDate *dateOffline = [tox friendGetLastOnlineWithFriendNumber:friendNumber error:nil];
            NSTimeInterval timeSince = [dateOffline timeIntervalSince1970];
            theFriend.lastSeenOnlineInterval = timeSince;
        }
    }];

    [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTFriendConnectionStatusChangeNotification object:friend];
}

#pragma mark -  Private

- (OCTGroupContactStore *)groupContactStore
{
    return [[OCTGroupContactStore alloc] initWithRealmManager:[self.dataSource managerGetRealmManager]];
}

/// The name a contact added from a group had there, nil for any other contact.
- (nullable NSString *)groupMemberNameForContact:(NSString *)publicKey
{
    return publicKey.length > 0 ? [[self groupContactStore] memberNameForContact:publicKey] : nil;
}

/// Accepting an offer from a group: our own contact request to the member's whole ID, with the nonce of the offer in
/// its first line (toxcore resends it until they are online, wherever they are), and the accept over the group while
/// they are online there. The new contact is named with their name in the group until their own comes.
- (BOOL)approveGroupContactRequest:(OCTFriendRequest *)friendRequest
                             offer:(OCTGroupContactIncomingOffer *)offer
                             store:(OCTGroupContactStore *)store
                             error:(NSError **)error
{
    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSString *text = OCTGroupContactLocalizedString(@"air_group_contact_accept_message_format",
                                                    @"I accepted your request from the group «%@»",
                                                    offer.groupName);
    NSString *message = [OCTGroupContactExchange requestMessageWithNonceHex:offer.nonceHex text:text];
    if ([message lengthOfBytesUsingEncoding:NSUTF8StringEncoding] > kOCTToxMaxFriendRequestLength) {
        message = [OCTGroupContactExchange requestMessageWithNonceHex:offer.nonceHex text:@""];
    }

    [store saveMemberName:offer.memberName forContact:offer.contactPublicKeyHex];
    OCTToxFriendNumber friendNumber = [tox addFriendWithAddress:offer.contactAddressHex message:message error:error];
    if (friendNumber == kOCTToxFriendNumberFailure) {
        return NO;
    }
    [self.dataSource managerSaveTox];

    [realmManager deleteObject:friendRequest];
    [store removeIncomingOfferFromContact:offer.contactPublicKeyHex];
    if (! [self createFriendWithFriendNumber:friendNumber error:error]) {
        return NO;
    }
    [store saveLink:[[OCTGroupContactLink alloc] initWithContactPublicKeyHex:offer.contactPublicKeyHex
                                                           contactAddressHex:offer.contactAddressHex]
          forMember:offer.memberPublicKeyHex
            inGroup:offer.groupChatIdHex];
    // Both asked: the offer we sent them is answered by this as well.
    [store removePendingOfferToMember:offer.memberPublicKeyHex inGroup:offer.groupChatIdHex];

    OCTChat *chat = [realmManager chatWithGroupChatIdHex:offer.groupChatIdHex];
    if (chat != nil && chat.groupNumber >= 0) {
        OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)chat.groupNumber;
        NSError *peerError = nil;
        uint32_t peerId = [tox groupPeerIdForPublicKeyHex:offer.memberPublicKeyHex groupNumber:groupNumber error:&peerError];
        if (peerError == nil
            && [tox groupPeerConnectionStatusForGroupNumber:groupNumber peerId:peerId error:nil] != OCTToxConnectionStatusNone) {
            OCTGroupContactSendPacket(tox, groupNumber, peerId, OCTGroupContactPacketKindAccept, offer.nonceHex);
        }
    }
    return YES;
}

- (BOOL)createFriendWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)userError
{
    OCTTox *tox = [self.dataSource managerGetTox];
    OCTFriend *friend = [OCTFriend new];
    friend.friendNumber = friendNumber;

    __block NSError *error = nil;
    __block BOOL toxReadsSucceeded = YES;

    [tox performSyncBlockOnToxQueue:^{
        friend.publicKey = [tox publicKeyFromFriendNumber:friendNumber error:&error];
        if ([self checkForError:error andAssignTo:userError]) {
            toxReadsSucceeded = NO;
            return;
        }

        friend.name = [tox friendNameWithFriendNumber:friendNumber error:&error];
        if ([self checkForError:error andAssignTo:userError]) {
            toxReadsSucceeded = NO;
            return;
        }

        friend.statusMessage = [tox friendStatusMessageWithFriendNumber:friendNumber error:&error];
        if ([self checkForError:error andAssignTo:userError]) {
            toxReadsSucceeded = NO;
            return;
        }

        friend.status = [tox friendStatusWithFriendNumber:friendNumber error:&error];
        if ([self checkForError:error andAssignTo:userError]) {
            toxReadsSucceeded = NO;
            return;
        }

        friend.connectionStatus = [tox friendConnectionStatusWithFriendNumber:friendNumber error:&error];
        if ([self checkForError:error andAssignTo:userError]) {
            toxReadsSucceeded = NO;
            return;
        }

        NSDate *lastSeenOnline = [tox friendGetLastOnlineWithFriendNumber:friendNumber error:&error];
        friend.lastSeenOnlineInterval = [lastSeenOnline timeIntervalSince1970];
        if ([self checkForError:error andAssignTo:userError]) {
            toxReadsSucceeded = NO;
            return;
        }

        friend.isTyping = [tox isFriendTypingWithFriendNumber:friendNumber error:&error];
        if ([self checkForError:error andAssignTo:userError]) {
            toxReadsSucceeded = NO;
            return;
        }
    }];

    if (! toxReadsSucceeded) {
        return NO;
    }

    friend.isConnected = (friend.connectionStatus != OCTToxConnectionStatusNone);
    OCTApplyFriendName(friend, friend.name, friend.publicKey, [self groupMemberNameForContact:friend.publicKey]);

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTFriend *existingFriend = [realmManager friendWithPublicKey:friend.publicKey];

    if (existingFriend) {
        [realmManager updateObject:existingFriend withBlock:^(OCTFriend *theFriend) {
            theFriend.friendNumber = friend.friendNumber;
            theFriend.name = friend.name;
            theFriend.nickname = friend.nickname;
            theFriend.statusMessage = friend.statusMessage;
            theFriend.status = friend.status;
            theFriend.connectionStatus = friend.connectionStatus;
            theFriend.isConnected = friend.isConnected;
            theFriend.lastSeenOnlineInterval = friend.lastSeenOnlineInterval;
            theFriend.isTyping = friend.isTyping;
        }];
        friend = existingFriend;
    } else {
        [realmManager addObject:friend];
    }

    if (friend.isConnected) {
        OCTSendPushURLToFriend(tox, friendNumber, [self.dataSource managerGetChats]);
        [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTFriendConnectionStatusChangeNotification
                                                                    object:friend];
    }

    return YES;
}

- (BOOL)checkForError:(NSError *)toCheck andAssignTo:(NSError **)toAssign
{
    if (! toCheck) {
        return NO;
    }

    if (toAssign) {
        *toAssign = toCheck;
    }

    return YES;
}

@end
