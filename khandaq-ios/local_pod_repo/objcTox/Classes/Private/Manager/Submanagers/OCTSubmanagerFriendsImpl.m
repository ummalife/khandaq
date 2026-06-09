// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerFriendsImpl.h"
#import "OCTLogging.h"
#import "OCTTox.h"
#import "OCTFriend.h"
#import "OCTFriendRequest.h"
#import "OCTRealmManager.h"
#import "Firebase.h"

@implementation OCTSubmanagerFriendsImpl
@synthesize dataSource = _dataSource;

static NSString *OCTShortPublicKeyLabel(NSString *publicKey)
{
    if (publicKey.length < 6) {
        return publicKey;
    }

    return [[publicKey substringFromIndex:publicKey.length - 6] uppercaseString];
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

static void OCTApplyFriendName(OCTFriend *friend, NSString *name, NSString *publicKey)
{
    friend.name = name ?: @"";

    if (!OCTIsGenericDefaultFriendName(name)) {
        friend.nickname = name;
    } else {
        friend.nickname = OCTShortPublicKeyLabel(publicKey);
    }
}

static void OCTRefreshFriendNameFromTox(OCTTox *tox, OCTFriend *friend, OCTToxFriendNumber friendNumber)
{
    NSError *error = nil;
    NSString *liveName = [tox friendNameWithFriendNumber:friendNumber error:&error];

    if (error || liveName.length == 0) {
        return;
    }

    OCTApplyFriendName(friend, liveName, friend.publicKey);
}

#pragma mark -  Public

- (BOOL)sendFriendRequestToAddress:(NSString *)address message:(NSString *)message error:(NSError **)error
{
    NSParameterAssert(address);
    NSParameterAssert(message);

    OCTTox *tox = [self.dataSource managerGetTox];

    OCTToxFriendNumber friendNumber = [tox addFriendWithAddress:address message:message error:error];

    if (friendNumber == kOCTToxFriendNumberFailure) {
        if (error && ((*error).code == OCTToxErrorFriendAddAlreadySent || (*error).code == OCTToxErrorFriendAddSetNewNospam)) {
            return [self resendFriendRequestToAddress:address message:message error:error];
        }

        return NO;
    }

    [self.dataSource managerSaveTox];

    return [self createFriendWithFriendNumber:friendNumber error:error];
}

- (BOOL)resendFriendRequestToAddress:(NSString *)address message:(NSString *)message error:(NSError **)error
{
    if (address.length < kOCTToxPublicKeyLength) {
        return NO;
    }

    NSString *publicKey = [[address substringToIndex:kOCTToxPublicKeyLength] uppercaseString];
    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTFriend *existingFriend = [realmManager friendWithPublicKey:publicKey];

    if (existingFriend && existingFriend.connectionStatus != OCTToxConnectionStatusNone) {
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
    OCTToxFriendNumber friendNumber = [tox addFriendWithAddress:address message:message error:&retryError];

    if (error) {
        *error = retryError;
    }

    if (friendNumber == kOCTToxFriendNumberFailure) {
        return NO;
    }

    [self.dataSource managerSaveTox];

    return [self createFriendWithFriendNumber:friendNumber error:error];
}

- (BOOL)approveFriendRequest:(OCTFriendRequest *)friendRequest error:(NSError **)error
{
    NSParameterAssert(friendRequest);

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

    [[self.dataSource managerGetRealmManager] deleteObject:friendRequest];
}

- (BOOL)removeFriend:(OCTFriend *)friend error:(NSError **)error
{
    NSParameterAssert(friend);

    OCTTox *tox = [self.dataSource managerGetTox];

    if (! [tox deleteFriendWithFriendNumber:friend.friendNumber error:error]) {
        return NO;
    }

    [self.dataSource managerSaveTox];

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

        // Reset some fields for friends.
        [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
            theFriend.friendNumber = number;
            theFriend.status = OCTToxUserStatusNone;
            theFriend.isConnected = NO;
            theFriend.connectionStatus = OCTToxConnectionStatusNone;
            theFriend.isTyping = NO;
            NSDate *dateOffline = [tox friendGetLastOnlineWithFriendNumber:number error:nil];
            theFriend.lastSeenOnlineInterval = [dateOffline timeIntervalSince1970];
            OCTRefreshFriendNameFromTox(tox, theFriend, number);
        }];
    }

    // Remove all OCTFriend's which aren't bounded to tox. User cannot interact with them anyway.
    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"friendNumber == %d", kOCTToxFriendNumberFailure];
    RLMResults *results = [realmManager objectsWithClass:[OCTFriend class] predicate:predicate];

    for (OCTFriend *friend in results) {
        [realmManager deleteObject:friend];
    }
}

#pragma mark -  OCTToxDelegate

- (void)tox:(OCTTox *)tox friendRequestWithMessage:(NSString *)message publicKey:(NSString *)publicKey
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"publicKey == %@", publicKey];
    RLMResults *results = [realmManager objectsWithClass:[OCTFriendRequest class] predicate:predicate];
    if (results.count > 0) {
        // friendRequest already exists
        return;
    }

    results = [realmManager objectsWithClass:[OCTFriend class] predicate:predicate];
    if (results.count > 0) {
        // friend with such publicKey already exists
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

    [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {
        theFriend.name = name;

        if (!OCTIsGenericDefaultFriendName(name)) {
            theFriend.nickname = name;
        } else {
            theFriend.nickname = OCTShortPublicKeyLabel(publicKey);
        }
    }];
}

- (void)tox:(OCTTox *)tox friendPushTokenUpdate:(NSString *)pushToken friendNumber:(OCTToxFriendNumber)friendNumber
{
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

    [realmManager updateObject:friend withBlock:^(OCTFriend *theFriend) {

        if ((status != OCTToxConnectionStatusNone)
            && (theFriend.connectionStatus == OCTToxConnectionStatusNone))
        {
            // Friend is coming online now
            OCTRefreshFriendNameFromTox(tox, theFriend, friendNumber);

            OCTToxCapabilities f_caps = [tox friendGetCapabilitiesWithFriendNumber:friendNumber];
            OCTLogVerbose(@"f_caps=%lu", f_caps);
            NSString* cap_string = [NSString stringWithFormat:@"%lu", f_caps];
            theFriend.capabilities2 = cap_string;

            NSString *token = nil;
            if ([FIRApp defaultApp] != nil) {
                token = [FIRMessaging messaging].FCMToken;
            }
            if (token.length > 0)
            {
                // HINT: prepend a dummy "A" char as placeholder for Tox Packet ID.
                //       it will be replaced in sendLosslessPacketWithFriendNumber by pktid
                NSString *data = [NSString stringWithFormat:@"Ahttps://push.khandaq.org/toxfcm/fcm.php?id=%@&type=1", token];
                // NSLog(@"token push url=%@", data);
                NSError *error;

                // HINT: pktid 181 is for sending push urls to friends
                BOOL result = [tox sendLosslessPacketWithFriendNumber:friendNumber
                                                 pktid:181
                                                  data:data
                                                 error:&error];
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

- (BOOL)createFriendWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)userError
{
    OCTTox *tox = [self.dataSource managerGetTox];
    NSError *error;

    OCTFriend *friend = [OCTFriend new];

    friend.friendNumber = friendNumber;

    friend.publicKey = [tox publicKeyFromFriendNumber:friendNumber error:&error];
    if ([self checkForError:error andAssignTo:userError]) {
        return NO;
    }

    friend.name = [tox friendNameWithFriendNumber:friendNumber error:&error];
    if ([self checkForError:error andAssignTo:userError]) {
        return NO;
    }

    friend.statusMessage = [tox friendStatusMessageWithFriendNumber:friendNumber error:&error];
    if ([self checkForError:error andAssignTo:userError]) {
        return NO;
    }

    friend.status = [tox friendStatusWithFriendNumber:friendNumber error:&error];
    if ([self checkForError:error andAssignTo:userError]) {
        return NO;
    }

    friend.connectionStatus = [tox friendConnectionStatusWithFriendNumber:friendNumber error:&error];
    if ([self checkForError:error andAssignTo:userError]) {
        return NO;
    }

    NSDate *lastSeenOnline = [tox friendGetLastOnlineWithFriendNumber:friendNumber error:&error];
    friend.lastSeenOnlineInterval = [lastSeenOnline timeIntervalSince1970];
    if ([self checkForError:error andAssignTo:userError]) {
        return NO;
    }

    friend.isTyping = [tox isFriendTypingWithFriendNumber:friendNumber error:&error];
    if ([self checkForError:error andAssignTo:userError]) {
        return NO;
    }

    friend.isConnected = (friend.connectionStatus != OCTToxConnectionStatusNone);
    OCTApplyFriendName(friend, friend.name, friend.publicKey);

    [[self.dataSource managerGetRealmManager] addObject:friend];

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
