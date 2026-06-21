// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Realm/Realm.h>

#import "OCTRealmManager.h"
#import "OCTFriend.h"
#import "OCTFriendRequest.h"
#import "OCTChat.h"
#import "OCTCall.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageText.h"
#import "OCTMessageFile.h"
#import "OCTMessageCall.h"
#import "OCTGroupPeer.h"
#import "OCTSettingsStorageObject.h"
#import "OCTToxConstants.h"
#import "OCTLogging.h"

static const uint64_t kCurrentSchemeVersion = 33;
static NSString *kSettingsStorageObjectPrimaryKey = @"kSettingsStorageObjectPrimaryKey";

@interface OCTRealmManager ()

@property (strong, nonatomic) dispatch_queue_t queue;
@property (strong, nonatomic) RLMRealm *realm;

@end

@implementation OCTRealmManager
@synthesize settingsStorage = _settingsStorage;

#pragma mark -  Class methods

+ (BOOL)migrateToEncryptedDatabase:(NSString *)databasePath
                     encryptionKey:(NSData *)encryptionKey
                             error:(NSError **)error
{
    NSString *tempPath = [databasePath stringByAppendingPathExtension:@"tmp"];

    @autoreleasepool {
        RLMRealm *old = [OCTRealmManager createRealmWithFileURL:[NSURL fileURLWithPath:databasePath]
                                                  encryptionKey:nil
                                                          error:error];

        if (! old) {
            return NO;
        }

        if (! [old writeCopyToURL:[NSURL fileURLWithPath:tempPath] encryptionKey:encryptionKey error:error]) {
            return NO;
        }
    }

    if (! [[NSFileManager defaultManager] removeItemAtPath:databasePath error:error]) {
        return NO;
    }

    if (! [[NSFileManager defaultManager] moveItemAtPath:tempPath toPath:databasePath error:error]) {
        return NO;
    }

    return YES;
}

#pragma mark -  Lifecycle

- (instancetype)initWithDatabaseFileURL:(NSURL *)fileURL encryptionKey:(NSData *)encryptionKey
{
    NSParameterAssert(fileURL);

    self = [super init];

    if (! self) {
        return nil;
    }

    OCTLogInfo(@"init with fileURL %@", fileURL);

    _queue = dispatch_queue_create("OCTRealmManager queue", NULL);

    __weak OCTRealmManager *weakSelf = self;
    dispatch_sync(_queue, ^{
        __strong OCTRealmManager *strongSelf = weakSelf;

        NSError *realmError = nil;
        self->_realm = [OCTRealmManager createRealmWithFileURL:fileURL encryptionKey:encryptionKey error:&realmError];

        if (! self->_realm) {
            // KHANDAQ (#6): the Realm DB is only the on-device CACHE of contacts/messages — the
            // canonical friend list lives in the atomically-saved .tox file and is re-synced into a
            // fresh Realm by -[OCTSubmanagerFriends configure] on every launch. A crash mid-write can
            // leave the Realm file corrupt or unmigratable, so realmWithConfiguration returns nil; a
            // nil realm then makes ALL contacts vanish from the UI ("контакты исчезли после краша").
            // Recover by moving the bad file aside and creating a fresh Realm — friends reappear from
            // .tox on this same launch. We RENAME rather than delete, so the old DB (e.g. message
            // history) can still be recovered manually.
            OCTLogError(@"Realm open failed (%@); moving corrupt DB aside and recreating", realmError);

            NSString *path = fileURL.path;
            NSString *corruptPath = [path stringByAppendingPathExtension:@"corrupt"];
            [[NSFileManager defaultManager] removeItemAtPath:corruptPath error:nil];
            [[NSFileManager defaultManager] moveItemAtPath:path toPath:corruptPath error:nil];

            self->_realm = [OCTRealmManager createRealmWithFileURL:fileURL encryptionKey:encryptionKey error:nil];
        }

        [strongSelf createSettingsStorage];
    });

    // KHANDAQ (crash fix): a few legacy group-file helpers query via RLMObject CLASS methods
    // (`+objectsWithPredicate:`) which hit the DEFAULT Realm — not our encrypted `self.realm`. They run
    // on background (Tox file-transfer) threads, where `self.realm` (confined to the init thread) isn't
    // reachable, so they were always no-ops against an empty default realm. After bumping the schema
    // (isSavedMessages/isCaption), the stale on-disk `default.realm` from an older build needed a
    // migration the default config didn't provide → `+[RLMRealm defaultRealm]` THREW → the app crashed
    // on ANY group file transfer (send photo/voice, retry) on a real device (fresh sims have no stale
    // default.realm, hence no repro). Make the default config self-healing (it holds no real data) so
    // `defaultRealm` always opens cleanly; the helpers keep their prior harmless no-op behaviour.
    RLMRealmConfiguration *defaultConfig = [RLMRealmConfiguration defaultConfiguration];
    defaultConfig.schemaVersion = kCurrentSchemeVersion;
    defaultConfig.deleteRealmIfMigrationNeeded = YES;
    [RLMRealmConfiguration setDefaultConfiguration:defaultConfig];

    [self convertAllCallsToMessages];

    return self;
}

#pragma mark -  Public

- (NSURL *)realmFileURL
{
    return self.realm.configuration.fileURL;
}

#pragma mark -  Basic methods

- (id)objectWithUniqueIdentifier:(NSString *)uniqueIdentifier class:(Class)class
{
    NSParameterAssert(uniqueIdentifier);
    NSParameterAssert(class);

    __block OCTObject *object = nil;

    dispatch_sync(self.queue, ^{
        object = [class objectInRealm:self.realm forPrimaryKey:uniqueIdentifier];
    });

    return object;
}

- (RLMResults *)objectsWithClass:(Class)class predicate:(NSPredicate *)predicate
{
    NSParameterAssert(class);

    __block RLMResults *results;

    dispatch_sync(self.queue, ^{
        results = [class objectsInRealm:self.realm withPredicate:predicate];
    });

    return results;
}

- (void)updateObject:(OCTObject *)object withBlock:(void (^)(id theObject))updateBlock
{
    NSParameterAssert(object);
    NSParameterAssert(updateBlock);

    // OCTLogInfo(@"updateObject %@", object);

    dispatch_sync(self.queue, ^{
        // KHANDAQ: skip if the object was deleted/invalidated — e.g. its group/chat was left or
        // removed while a file transfer was still updating its progress. Modifying an invalidated
        // Realm object throws and crashed the app ("выход/удалить группу во время отправки фото").
        if (object.isInvalidated) {
            return;
        }

        [self.realm beginWriteTransaction];

        updateBlock(object);

        [self.realm commitWriteTransaction];
    });
}

- (void)updateObjectsWithClass:(Class)class
                     predicate:(NSPredicate *)predicate
                   updateBlock:(void (^)(id theObject))updateBlock
{
    NSParameterAssert(class);
    NSParameterAssert(updateBlock);

    // OCTLogInfo(@"updating objects of class %@ with predicate %@", NSStringFromClass(class), predicate);

    dispatch_sync(self.queue, ^{
        RLMResults *results = [class objectsInRealm:self.realm withPredicate:predicate];

        [self.realm beginWriteTransaction];
        for (id object in results) {
            updateBlock(object);
        }
        [self.realm commitWriteTransaction];
    });
}

- (void)addObject:(OCTObject *)object
{
    NSParameterAssert(object);

    // OCTLogInfo(@"add object %@", object);

    dispatch_sync(self.queue, ^{
        [self.realm beginWriteTransaction];

        [self.realm addObject:object];

        [self.realm commitWriteTransaction];
    });
}

- (void)deleteObject:(OCTObject *)object
{
    NSParameterAssert(object);

    // OCTLogInfo(@"delete object %@", object);

    dispatch_sync(self.queue, ^{
        [self.realm beginWriteTransaction];

        [self.realm deleteObject:object];

        [self.realm commitWriteTransaction];
    });
}

#pragma mark -  Other methods

+ (RLMRealm *)createRealmWithFileURL:(NSURL *)fileURL encryptionKey:(NSData *)encryptionKey error:(NSError **)error
{
    RLMRealmConfiguration *configuration = [RLMRealmConfiguration defaultConfiguration];
    configuration.fileURL = fileURL;
    configuration.schemaVersion = kCurrentSchemeVersion;
    configuration.migrationBlock = [self realmMigrationBlock];
    configuration.encryptionKey = encryptionKey;

    RLMRealm *realm = [RLMRealm realmWithConfiguration:configuration error:error];

    if (! realm && error) {
        OCTLogInfo(@"Cannot create Realm, error %@", *error);
    }

    return realm;
}

- (void)createSettingsStorage
{
    _settingsStorage = [OCTSettingsStorageObject objectInRealm:self.realm
                                                 forPrimaryKey:kSettingsStorageObjectPrimaryKey];

    if (! _settingsStorage) {
        OCTLogInfo(@"no _settingsStorage, creating it");
        _settingsStorage = [OCTSettingsStorageObject new];
        _settingsStorage.uniqueIdentifier = kSettingsStorageObjectPrimaryKey;

        [self.realm beginWriteTransaction];
        [self.realm addObject:_settingsStorage];
        [self.realm commitWriteTransaction];
    }
}

- (OCTFriend *)friendWithPublicKey:(NSString *)publicKey
{
    NSAssert(publicKey, @"Public key should be non-empty.");
    __block OCTFriend *friend;

    dispatch_sync(self.queue, ^{
        friend = [[OCTFriend objectsInRealm:self.realm where:@"publicKey == %@", publicKey] firstObject];
    });

    return friend;
}

- (OCTChat *)getOrCreateChatWithFriend:(OCTFriend *)friend
{
    __block OCTChat *chat = nil;

    dispatch_sync(self.queue, ^{
        // TODO add this (friends.@count == 1) condition. Currentry Realm doesn't support collection queries
        // See https://github.com/realm/realm-cocoa/issues/1490
        chat = [[OCTChat objectsInRealm:self.realm where:@"ANY friends == %@", friend] firstObject];

        if (chat) {
            return;
        }

        OCTLogInfo(@"creating chat with friend %@", friend);

        chat = [OCTChat new];
        chat.lastActivityDateInterval = [[NSDate date] timeIntervalSince1970];

        [self.realm beginWriteTransaction];

        [self.realm addObject:chat];
        [chat.friends addObject:friend];

        [self.realm commitWriteTransaction];
    });

    return chat;
}

- (OCTChat *)getOrCreateSavedMessagesChat
{
    __block OCTChat *chat = nil;

    dispatch_sync(self.queue, ^{
        // The saved-messages chat is flagged explicitly so it can't be confused with a 1:1 chat
        // whose friend was deleted (that one is also friend-less).
        RLMResults *flagged = [OCTChat objectsInRealm:self.realm where:@"isSavedMessages == YES"];
        if (flagged.count > 0) {
            chat = [flagged firstObject];
            return;
        }

        chat = [OCTChat new];
        chat.isSavedMessages = YES;
        chat.lastActivityDateInterval = [[NSDate date] timeIntervalSince1970];

        [self.realm beginWriteTransaction];
        [self.realm addObject:chat];
        [self.realm commitWriteTransaction];
    });

    return chat;
}

- (OCTMessageAbstract *)addSavedTextMessage:(NSString *)text toChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    OCTMessageText *messageText = [OCTMessageText new];
    messageText.text = text ?: @"";
    messageText.isDelivered = YES;
    messageText.type = OCTToxMessageTypeNormal;

    OCTMessageAbstract *message = [self addMessageAbstractWithChat:chat sender:nil messageText:messageText messageFile:nil messageCall:nil tssent:0 tsrcvd:0];
    [self markSavedChatRead:chat upTo:message];
    return message;
}

- (void)markMessageAsCaption:(OCTMessageAbstract *)message
{
    if (! message) {
        return;
    }
    [self updateObject:message withBlock:^(OCTMessageAbstract *theMessage) {
        theMessage.isCaption = YES;
    }];
}

// Saved Messages are always your own — never let them count as "unread" in the chats badge.
- (void)markSavedChatRead:(OCTChat *)chat upTo:(OCTMessageAbstract *)message
{
    if (! message) {
        return;
    }
    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.lastReadDateInterval = message.dateInterval;
    }];
}

- (OCTMessageAbstract *)addSavedFileMessageWithPath:(NSString *)filePath
                                           fileName:(NSString *)fileName
                                           fileSize:(OCTToxFileSize)fileSize
                                            fileUTI:(nullable NSString *)fileUTI
                                               toChat:(OCTChat *)chat
{
    NSParameterAssert(filePath);
    NSParameterAssert(chat);

    OCTMessageFile *messageFile = [OCTMessageFile new];
    messageFile.internalFileNumber = kOCTToxFileNumberFailure;
    messageFile.fileType = OCTMessageFileTypeReady;
    messageFile.fileSize = fileSize;
    messageFile.fileName = fileName;
    [messageFile internalSetFilePath:filePath];
    messageFile.fileUTI = fileUTI;

    OCTMessageAbstract *message = [self addMessageAbstractWithChat:chat sender:nil messageText:nil messageFile:messageFile messageCall:nil tssent:0 tsrcvd:0];
    [self markSavedChatRead:chat upTo:message];
    return message;
}

- (OCTChat *)chatWithGroupNumber:(uint32_t)groupNumber
{
    __block OCTChat *chat = nil;

    dispatch_sync(self.queue, ^{
        chat = [[OCTChat objectsInRealm:self.realm where:@"isGroup == YES AND groupNumber == %d", groupNumber] firstObject];
    });

    return chat;
}

- (OCTChat *)chatWithGroupChatIdHex:(NSString *)chatIdHex
{
    NSAssert(chatIdHex.length > 0, @"Group chat id hex should be non-empty.");
    __block OCTChat *chat = nil;
    NSString *normalized = chatIdHex.uppercaseString;

    dispatch_sync(self.queue, ^{
        chat = [[OCTChat objectsInRealm:self.realm where:@"isGroup == YES AND groupChatIdHex ==[c] %@", normalized] firstObject];
    });

    return chat;
}

- (RLMResults *)groupChats
{
    __block RLMResults *chats = nil;

    dispatch_sync(self.queue, ^{
        chats = [OCTChat objectsInRealm:self.realm where:@"isGroup == YES"];
    });

    return chats;
}

- (NSArray<OCTChat *> *)groupChatsSnapshot
{
    __block NSMutableArray<OCTChat *> *snapshot = [NSMutableArray array];

    dispatch_sync(self.queue, ^{
        RLMResults *results = [OCTChat objectsInRealm:self.realm where:@"isGroup == YES"];
        NSUInteger count = results.count;

        for (NSUInteger idx = 0; idx < count; idx++) {
            [snapshot addObject:results[idx]];
        }
    });

    return snapshot;
}

- (OCTChat *)getOrCreateGroupChatWithGroupNumber:(uint32_t)groupNumber
                                      chatIdHex:(NSString *)chatIdHex
                                      groupName:(NSString *)groupName
                                   privacyState:(OCTToxGroupPrivacyState)privacyState
{
    NSParameterAssert(chatIdHex);

    __block OCTChat *chat = nil;

    dispatch_sync(self.queue, ^{
        chat = [[OCTChat objectsInRealm:self.realm where:@"isGroup == YES AND groupNumber == %d", groupNumber] firstObject];

        if (! chat && chatIdHex.length > 0) {
            NSString *normalized = chatIdHex.uppercaseString;
            chat = [[OCTChat objectsInRealm:self.realm where:@"isGroup == YES AND groupChatIdHex ==[c] %@", normalized] firstObject];
        }

        if (chat) {
            [self.realm beginWriteTransaction];
            chat.groupNumber = (int32_t)groupNumber;
            if (chatIdHex.length > 0) {
                chat.groupChatIdHex = chatIdHex.uppercaseString;
            }
            if (groupName.length > 0) {
                chat.groupName = groupName;
            }
            chat.groupPrivacyState = (int32_t)privacyState;
            [self.realm commitWriteTransaction];
            return;
        }

        OCTLogInfo(@"creating group chat number=%u chatId=%@ name=%@", groupNumber, chatIdHex, groupName);

        chat = [OCTChat new];
        chat.isGroup = YES;
        chat.groupNumber = (int32_t)groupNumber;
        chat.groupChatIdHex = chatIdHex.length > 0 ? chatIdHex.uppercaseString : nil;
        chat.groupName = groupName.length > 0 ? groupName : nil;
        chat.groupPrivacyState = (int32_t)privacyState;
        chat.lastActivityDateInterval = [[NSDate date] timeIntervalSince1970];

        [self.realm beginWriteTransaction];
        [self.realm addObject:chat];
        [self.realm commitWriteTransaction];
    });

    return chat;
}

- (RLMResults *)groupPeersForChatUniqueIdentifier:(NSString *)chatUniqueIdentifier
{
    NSAssert(chatUniqueIdentifier.length > 0, @"Chat unique identifier should be non-empty.");
    __block RLMResults *peers = nil;

    dispatch_sync(self.queue, ^{
        peers = [[OCTGroupPeer objectsInRealm:self.realm where:@"chatUniqueIdentifier == %@", chatUniqueIdentifier]
                 sortedResultsUsingKeyPath:@"peerName" ascending:YES];
    });

    return peers;
}

- (void)upsertGroupPeerForChat:(OCTChat *)chat peerId:(uint32_t)peerId peerName:(NSString *)peerName peerRole:(OCTToxGroupRole)peerRole peerPublicKeyHex:(NSString *)peerPublicKeyHex
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    NSString *uniqueIdentifier = [NSString stringWithFormat:@"%@:%u", chat.uniqueIdentifier, peerId];
    NSString *normalizedPubkey = peerPublicKeyHex.length > 0 ? peerPublicKeyHex.lowercaseString : nil;

    dispatch_sync(self.queue, ^{
        OCTGroupPeer *peer = [OCTGroupPeer objectInRealm:self.realm forPrimaryKey:uniqueIdentifier];

        [self.realm beginWriteTransaction];

        if (! peer) {
            peer = [OCTGroupPeer new];
            peer.uniqueIdentifier = uniqueIdentifier;
            peer.chatUniqueIdentifier = chat.uniqueIdentifier;
            peer.peerId = (int32_t)peerId;
            peer.peerRole = (int32_t)peerRole;

            if (peerName.length > 0) {
                peer.peerName = peerName;
            }

            if (normalizedPubkey.length > 0) {
                peer.peerPublicKeyHex = normalizedPubkey;
            }

            [self.realm addObject:peer];
            [self.realm commitWriteTransaction];
            return;
        }

        if (peerName.length > 0 && ! [peer.peerName isEqualToString:peerName]) {
            peer.peerName = peerName;
        }

        if (peer.peerRole != (int32_t)peerRole) {
            peer.peerRole = (int32_t)peerRole;
        }

        if (normalizedPubkey.length > 0 && ! [peer.peerPublicKeyHex isEqualToString:normalizedPubkey]) {
            peer.peerPublicKeyHex = normalizedPubkey;
        }

        [self.realm commitWriteTransaction];
    });
}

- (void)upsertGroupPeerForChat:(OCTChat *)chat peerId:(uint32_t)peerId peerName:(NSString *)peerName peerRole:(OCTToxGroupRole)peerRole
{
    [self upsertGroupPeerForChat:chat peerId:peerId peerName:peerName peerRole:peerRole peerPublicKeyHex:nil];
}

- (NSSet<NSString *> *)knownGroupMemberPublicKeyHexesForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    __block NSMutableSet<NSString *> *pubkeys = [NSMutableSet set];

    dispatch_sync(self.queue, ^{
        RLMResults *peers = [OCTGroupPeer objectsInRealm:self.realm
                                                   where:@"chatUniqueIdentifier == %@ AND peerPublicKeyHex != nil",
                                                         chat.uniqueIdentifier];

        for (OCTGroupPeer *peer in peers) {
            if (peer.peerPublicKeyHex.length > 0) {
                [pubkeys addObject:peer.peerPublicKeyHex.lowercaseString];
            }
        }
    });

    return pubkeys;
}

- (void)updateGroupPeerLastSeenDateInterval:(NSTimeInterval)dateInterval forChat:(OCTChat *)chat peerId:(uint32_t)peerId
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    if (dateInterval <= 0) {
        return;
    }

    NSString *uniqueIdentifier = [NSString stringWithFormat:@"%@:%u", chat.uniqueIdentifier, peerId];

    dispatch_sync(self.queue, ^{
        OCTGroupPeer *peer = [OCTGroupPeer objectInRealm:self.realm forPrimaryKey:uniqueIdentifier];

        if (! peer) {
            return;
        }

        if (peer.peerLastSeenDateInterval >= dateInterval) {
            return;
        }

        [self.realm beginWriteTransaction];
        peer.peerLastSeenDateInterval = dateInterval;
        [self.realm commitWriteTransaction];
    });
}

- (NSTimeInterval)groupPeerLastSeenDateIntervalForChat:(OCTChat *)chat peerId:(uint32_t)peerId
{
    NSParameterAssert(chat);

    __block NSTimeInterval lastSeen = 0;

    dispatch_sync(self.queue, ^{
        NSString *uniqueIdentifier = [NSString stringWithFormat:@"%@:%u", chat.uniqueIdentifier, peerId];
        OCTGroupPeer *peer = [OCTGroupPeer objectInRealm:self.realm forPrimaryKey:uniqueIdentifier];
        lastSeen = peer.peerLastSeenDateInterval;
    });

    return lastSeen;
}

- (NSUInteger)groupSystemMessageCountForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    __block NSUInteger count = 0;

    dispatch_sync(self.queue, ^{
        count = [OCTMessageAbstract objectsInRealm:self.realm
                                             where:@"chatUniqueIdentifier == %@ AND groupSystemMessage == YES",
                                                   chat.uniqueIdentifier].count;
    });

    return count;
}

- (NSUInteger)removeGroupSystemMessagesInChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    __block NSUInteger count = 0;

    dispatch_sync(self.queue, ^{
        RLMResults *messages = [OCTMessageAbstract objectsInRealm:self.realm
                                                            where:@"chatUniqueIdentifier == %@ AND groupSystemMessage == YES",
                                                                  chat.uniqueIdentifier];
        count = messages.count;

        if (count == 0) {
            return;
        }

        [self.realm beginWriteTransaction];
        [self removeMessagesWithSubmessages:messages];

        RLMResults *remaining = [OCTMessageAbstract objectsInRealm:self.realm where:@"chatUniqueIdentifier == %@", chat.uniqueIdentifier];
        remaining = [remaining sortedResultsUsingKeyPath:@"dateInterval" ascending:YES];
        chat.lastMessage = remaining.lastObject;

        [self.realm commitWriteTransaction];
    });

    return count;
}

- (void)upsertGroupPeerForChat:(OCTChat *)chat peerId:(uint32_t)peerId peerName:(NSString *)peerName
{
    OCTGroupPeer *existingPeer = [self groupPeerForChat:chat peerId:peerId];
    OCTToxGroupRole role = existingPeer ? (OCTToxGroupRole)existingPeer.peerRole : OCTToxGroupRoleUser;

    [self upsertGroupPeerForChat:chat peerId:peerId peerName:peerName peerRole:role];
}

- (void)setGroupPeerRole:(OCTToxGroupRole)peerRole peerId:(uint32_t)peerId chat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self upsertGroupPeerForChat:chat peerId:peerId peerName:nil peerRole:peerRole];
}

- (void)setGroupNotificationsSilent:(BOOL)silent forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.groupNotificationsSilent = silent;
    }];
}

- (void)setGroupPeerNotificationsSilent:(BOOL)silent peerId:(uint32_t)peerId chat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self upsertGroupPeerForChat:chat peerId:peerId peerName:nil];

    NSString *uniqueIdentifier = [NSString stringWithFormat:@"%@:%u", chat.uniqueIdentifier, peerId];

    dispatch_sync(self.queue, ^{
        OCTGroupPeer *peer = [OCTGroupPeer objectInRealm:self.realm forPrimaryKey:uniqueIdentifier];

        if (! peer) {
            return;
        }

        [self.realm beginWriteTransaction];
        peer.peerNotificationsSilent = silent;
        [self.realm commitWriteTransaction];
    });
}

- (void)setGroupPeerPrivateLastReadDateInterval:(NSTimeInterval)interval peerId:(uint32_t)peerId chat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self upsertGroupPeerForChat:chat peerId:peerId peerName:nil];

    NSString *uniqueIdentifier = [NSString stringWithFormat:@"%@:%u", chat.uniqueIdentifier, peerId];

    dispatch_sync(self.queue, ^{
        OCTGroupPeer *peer = [OCTGroupPeer objectInRealm:self.realm forPrimaryKey:uniqueIdentifier];

        if (! peer) {
            return;
        }

        [self.realm beginWriteTransaction];
        peer.privateLastReadDateInterval = interval;
        [self.realm commitWriteTransaction];
    });
}

- (int32_t)unreadPrivateMessageCountForPeerId:(uint32_t)peerId chat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    OCTGroupPeer *peer = [self groupPeerForChat:chat peerId:peerId];
    NSTimeInterval lastRead = peer ? peer.privateLastReadDateInterval : 0;

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND groupPrivateMessage == YES AND groupPrivatePeerId == %u AND groupSenderPeerId == %u AND dateInterval > %f",
                              chat.uniqueIdentifier, peerId, peerId, lastRead];

    __block int32_t count = 0;

    dispatch_sync(self.queue, ^{
        count = (int32_t)[OCTMessageAbstract objectsInRealm:self.realm withPredicate:predicate].count;
    });

    return count;
}

- (nullable OCTGroupPeer *)groupPeerForChat:(OCTChat *)chat peerId:(uint32_t)peerId
{
    NSParameterAssert(chat);

    NSString *uniqueIdentifier = [NSString stringWithFormat:@"%@:%u", chat.uniqueIdentifier, peerId];

    __block OCTGroupPeer *peer = nil;

    dispatch_sync(self.queue, ^{
        peer = [OCTGroupPeer objectInRealm:self.realm forPrimaryKey:uniqueIdentifier];
    });

    return peer;
}

- (void)removeGroupPeersForChat:(OCTChat *)chat notInPeerIds:(NSSet<NSNumber *> *)peerIds
{
    NSParameterAssert(chat);

    dispatch_sync(self.queue, ^{
        RLMResults *peers = [OCTGroupPeer objectsInRealm:self.realm where:@"chatUniqueIdentifier == %@", chat.uniqueIdentifier];

        [self.realm beginWriteTransaction];

        for (OCTGroupPeer *peer in peers) {
            NSNumber *peerIdNumber = @(peer.peerId);

            if (! [peerIds containsObject:peerIdNumber]) {
                [self.realm deleteObject:peer];
            }
        }

        [self.realm commitWriteTransaction];
    });
}

- (void)updateGroupPeerCount:(int32_t)peerCount forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.groupPeerCount = peerCount;
    }];
}

- (OCTCall *)createCallWithChat:(OCTChat *)chat status:(OCTCallStatus)status
{
    __block OCTCall *call = nil;

    dispatch_sync(self.queue, ^{

        call = [[OCTCall objectsInRealm:self.realm where:@"chat == %@", chat] firstObject];

        if (call) {
            return;
        }

        OCTLogInfo(@"creating call with chat %@", chat);

        call = [OCTCall new];
        call.status = status;
        call.chat = chat;

        [self.realm beginWriteTransaction];
        [self.realm addObject:call];
        [self.realm commitWriteTransaction];
    });

    return call;
}

- (OCTCall *)getCurrentCallForChat:(OCTChat *)chat
{
    __block OCTCall *call = nil;

    dispatch_sync(self.queue, ^{

        call = [[OCTCall objectsInRealm:self.realm where:@"chat == %@", chat] firstObject];
    });

    return call;
}

- (void)removeMessages:(NSArray<OCTMessageAbstract *> *)messages
{
    NSParameterAssert(messages);

    OCTLogInfo(@"removing messages %lu", (unsigned long)messages.count);

    dispatch_sync(self.queue, ^{
        [self.realm beginWriteTransaction];

        NSMutableSet *changedChats = [NSMutableSet new];
        for (OCTMessageAbstract *message in messages) {
            [changedChats addObject:message.chatUniqueIdentifier];
        }

        [self removeMessagesWithSubmessages:messages];

        for (NSString *chatUniqueIdentifier in changedChats) {
            RLMResults *messages = [OCTMessageAbstract objectsInRealm:self.realm where:@"chatUniqueIdentifier == %@", chatUniqueIdentifier];
            messages = [messages sortedResultsUsingKeyPath:@"dateInterval" ascending:YES];

            OCTChat *chat = [OCTChat objectInRealm:self.realm forPrimaryKey:chatUniqueIdentifier];
            chat.lastMessage = messages.lastObject;
        }

        [self.realm commitWriteTransaction];
    });
}

- (void)removeAllMessagesInChat:(OCTChat *)chat removeChat:(BOOL)removeChat
{
    NSParameterAssert(chat);

    OCTLogInfo(@"removing chat with all messages %@", chat);

    dispatch_sync(self.queue, ^{
        RLMResults *messages = [OCTMessageAbstract objectsInRealm:self.realm where:@"chatUniqueIdentifier == %@", chat.uniqueIdentifier];

        [self.realm beginWriteTransaction];

        [self removeMessagesWithSubmessages:messages];
        if (removeChat) {
            [self.realm deleteObject:chat];
        }
        else {
            NSTimeInterval now = [[NSDate date] timeIntervalSince1970];
            chat.lastMessage = nil;
            chat.lastActivityDateInterval = now;
            chat.lastReadDateInterval = now;

            RLMResults *peers = [OCTGroupPeer objectsInRealm:self.realm where:@"chatUniqueIdentifier == %@", chat.uniqueIdentifier];

            for (OCTGroupPeer *peer in peers) {
                peer.privateLastReadDateInterval = now;
            }
        }

        [self.realm commitWriteTransaction];
    });
}

- (void)convertAllCallsToMessages
{
    RLMResults *calls = [OCTCall allObjectsInRealm:self.realm];

    OCTLogInfo(@"removing %lu calls", (unsigned long)calls.count);

    for (OCTCall *call in calls) {
        [self addMessageCall:call];
    }

    [self.realm beginWriteTransaction];
    [self.realm deleteObjects:calls];
    [self.realm commitWriteTransaction];
}

- (OCTMessageAbstract *)addMessageWithText:(NSString *)text
                                      type:(OCTToxMessageType)type
                                      chat:(OCTChat *)chat
                                    sender:(OCTFriend *)sender
                                 messageId:(OCTToxMessageId)messageId
                              msgv3HashHex:(NSString *)msgv3HashHex
                                  sentPush:(BOOL)sentPush
                                    tssent:(UInt32)tssent
                                    tsrcvd:(UInt32)tsrcvd
{
    NSParameterAssert(text);

    OCTLogInfo(@"adding messageText to chat %@", chat);

    OCTMessageText *messageText = [OCTMessageText new];
    messageText.text = text;
    messageText.isDelivered = NO;
    messageText.type = type;
    messageText.messageId = messageId;
    messageText.msgv3HashHex = msgv3HashHex;
    messageText.sentPush = sentPush;

    return [self addMessageAbstractWithChat:chat sender:sender messageText:messageText messageFile:nil messageCall:nil tssent:tssent tsrcvd:tsrcvd];
}

- (OCTMessageAbstract *)addGroupMessageWithText:(NSString *)text
                                           type:(OCTToxMessageType)type
                                           chat:(OCTChat *)chat
                                         peerId:(uint32_t)peerId
                                       peerName:(NSString *)peerName
                                      messageId:(OCTToxMessageId)messageId
{
    NSParameterAssert(text);
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTLogInfo(@"adding group messageText to chat %@ peer=%u", chat, peerId);

    OCTMessageText *messageText = [OCTMessageText new];
    messageText.text = text;
    messageText.isDelivered = (peerId == 0);
    messageText.type = type;
    messageText.messageId = messageId;
    messageText.groupPeerName = peerName.length > 0 ? peerName : nil;

    OCTMessageAbstract *messageAbstract = [OCTMessageAbstract new];
    messageAbstract.dateInterval = [[NSDate date] timeIntervalSince1970];
    messageAbstract.senderUniqueIdentifier = nil;
    messageAbstract.groupSenderPeerId = peerId;
    messageAbstract.chatUniqueIdentifier = chat.uniqueIdentifier;
    messageAbstract.messageText = messageText;

    [self addObject:messageAbstract];

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.lastMessage = messageAbstract;
        theChat.lastActivityDateInterval = messageAbstract.dateInterval;
    }];

    return messageAbstract;
}

- (OCTMessageAbstract *)addGroupPendingMessageWithText:(NSString *)text
                                                  type:(OCTToxMessageType)type
                                                  chat:(OCTChat *)chat
{
    NSParameterAssert(text);
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTMessageText *messageText = [OCTMessageText new];
    messageText.text = text;
    messageText.isDelivered = NO;
    messageText.type = type;
    messageText.messageId = 0;
    messageText.groupPeerName = nil;

    OCTMessageAbstract *messageAbstract = [OCTMessageAbstract new];
    messageAbstract.dateInterval = [[NSDate date] timeIntervalSince1970];
    messageAbstract.senderUniqueIdentifier = nil;
    messageAbstract.groupSenderPeerId = 0;
    messageAbstract.groupPendingSend = YES;
    messageAbstract.chatUniqueIdentifier = chat.uniqueIdentifier;
    messageAbstract.messageText = messageText;

    [self addObject:messageAbstract];

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.lastMessage = messageAbstract;
        theChat.lastActivityDateInterval = messageAbstract.dateInterval;
    }];

    return messageAbstract;
}

- (RLMResults *)pendingGroupMessagesForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    __block RLMResults *results = nil;

    dispatch_sync(self.queue, ^{
        results = [[OCTMessageAbstract objectsInRealm:self.realm
                                                where:@"chatUniqueIdentifier == %@ AND groupPendingSend == YES",
                                                      chat.uniqueIdentifier]
                   sortedResultsUsingKeyPath:@"dateInterval" ascending:YES];
    });

    return results;
}

- (void)markGroupPendingMessageSent:(OCTMessageAbstract *)message messageId:(uint32_t)messageId
{
    NSParameterAssert(message);

    [self updateObject:message withBlock:^(OCTMessageAbstract *theMessage) {
        theMessage.groupPendingSend = NO;

        if (theMessage.messageText) {
            theMessage.messageText.isDelivered = YES;
            theMessage.messageText.messageId = messageId;
        }
    }];
}

- (OCTMessageAbstract *)addGroupSystemMessageWithText:(NSString *)text inChat:(OCTChat *)chat
{
    NSParameterAssert(text);
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTMessageText *messageText = [OCTMessageText new];
    messageText.text = text;
    messageText.isDelivered = YES;
    messageText.type = OCTToxMessageTypeNormal;
    messageText.messageId = 0;

    OCTMessageAbstract *messageAbstract = [OCTMessageAbstract new];
    messageAbstract.dateInterval = [[NSDate date] timeIntervalSince1970];
    messageAbstract.senderUniqueIdentifier = nil;
    messageAbstract.groupSenderPeerId = 0;
    messageAbstract.groupSystemMessage = YES;
    messageAbstract.chatUniqueIdentifier = chat.uniqueIdentifier;
    messageAbstract.messageText = messageText;

    [self addObject:messageAbstract];

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.lastMessage = messageAbstract;
        theChat.lastActivityDateInterval = messageAbstract.dateInterval;
    }];

    return messageAbstract;
}

- (void)updateGroupTopic:(NSString *)topic forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.groupTopic = topic.length > 0 ? topic : nil;
        // KHANDAQ (#15): the group NAME (set at creation, authoritative via tox_group_get_name)
        // and the TOPIC are distinct fields. Only seed the display name from the topic when there
        // is no real name yet — never overwrite an existing name, otherwise the chat list shows the
        // topic (often a hex/auto placeholder like "11AE2E") instead of the group's actual name.
        if (topic.length > 0 && theChat.groupName.length == 0) {
            theChat.groupName = topic;
        }
    }];
}

- (void)updateGroupName:(NSString *)groupName forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    // KHANDAQ (#15): authoritative group name from tox_group_get_name. Only apply a non-empty
    // value and only when it actually changed, so we don't clobber a good name with an empty
    // result before the group state has synced.
    if (groupName.length == 0) {
        return;
    }

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        if (![theChat.groupName isEqualToString:groupName]) {
            theChat.groupName = groupName;
        }
    }];
}

- (void)updateGroupPassword:(NSString *)password forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.groupPassword = password.length > 0 ? password : nil;
    }];
}

- (void)updateGroupTopicLockEnabled:(BOOL)enabled forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.groupTopicLockEnabled = enabled;
    }];
}

- (void)updateGroupPeerLimit:(int32_t)peerLimit forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.groupPeerLimit = peerLimit;
    }];
}

- (void)updateGroupPrivacyState:(OCTToxGroupPrivacyState)privacyState forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.groupPrivacyState = (int32_t)privacyState;
    }];
}

- (void)updateGroupVoiceState:(OCTToxGroupVoiceState)voiceState forChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.groupVoiceState = (int32_t)voiceState;
    }];
}

static BOOL OCTRealmGroupSyncPubkeyEquals(NSString *stored, NSString *candidate)
{
    if (stored.length == 0 || candidate.length == 0) {
        return NO;
    }

    return [stored.uppercaseString isEqualToString:candidate.uppercaseString];
}

static void OCTRealmApplyGroupSyncConfirmation(int32_t *count,
                                               NSString **key1,
                                               NSString **key2,
                                               NSString **key3,
                                               NSString *ackerPubkeyHex)
{
    NSString *acker = ackerPubkeyHex.uppercaseString;

    if (OCTRealmGroupSyncPubkeyEquals(*key1, acker) ||
        OCTRealmGroupSyncPubkeyEquals(*key2, acker) ||
        OCTRealmGroupSyncPubkeyEquals(*key3, acker)) {
        return;
    }

    if (*count == 0) {
        *key1 = acker;
        *count = 1;
    }
    else if (*count == 1) {
        *key2 = acker;
        *count = 2;
    }
    else if (*count == 2) {
        *key3 = acker;
        *count = 3;
    }
    else {
        (*count)++;
    }
}

- (void)recordGroupSyncConfirmationForOutgoingTextMessageId:(uint32_t)messageId
                                           ackerPubkeyHex:(NSString *)ackerPubkeyHex
                                                   inChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    if (ackerPubkeyHex.length == 0) {
        return;
    }

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND groupSenderPeerId == 0 AND messageText != nil AND messageText.messageId == %u",
                              chat.uniqueIdentifier, messageId];
    OCTMessageAbstract *message = [OCTMessageAbstract objectsWithPredicate:predicate].firstObject;

    if (! message || ! message.messageText) {
        return;
    }

    [self updateObject:message withBlock:^(OCTMessageAbstract *theMessage) {
        OCTMessageText *messageText = theMessage.messageText;
        int32_t count = messageText.groupSyncConfirmations;
        NSString *key1 = messageText.groupSyncPeerKey1;
        NSString *key2 = messageText.groupSyncPeerKey2;
        NSString *key3 = messageText.groupSyncPeerKey3;

        OCTRealmApplyGroupSyncConfirmation(&count, &key1, &key2, &key3, ackerPubkeyHex);

        messageText.groupSyncConfirmations = count;
        messageText.groupSyncPeerKey1 = key1;
        messageText.groupSyncPeerKey2 = key2;
        messageText.groupSyncPeerKey3 = key3;
    }];
}

- (void)recordGroupSyncConfirmationForOutgoingFileWithHashHex:(NSString *)msgIdHashHex
                                               ackerPubkeyHex:(NSString *)ackerPubkeyHex
                                                       inChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    if (msgIdHashHex.length == 0 || ackerPubkeyHex.length == 0) {
        return;
    }

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND groupSenderPeerId == 0 AND messageFile != nil AND messageFile.groupMsgIdHashHex ==[c] %@",
                              chat.uniqueIdentifier, msgIdHashHex];
    OCTMessageAbstract *message = [OCTMessageAbstract objectsWithPredicate:predicate].firstObject;

    if (! message || ! message.messageFile) {
        return;
    }

    [self updateObject:message withBlock:^(OCTMessageAbstract *theMessage) {
        OCTMessageFile *messageFile = theMessage.messageFile;
        int32_t count = messageFile.groupSyncConfirmations;
        NSString *key1 = messageFile.groupSyncPeerKey1;
        NSString *key2 = messageFile.groupSyncPeerKey2;
        NSString *key3 = messageFile.groupSyncPeerKey3;

        OCTRealmApplyGroupSyncConfirmation(&count, &key1, &key2, &key3, ackerPubkeyHex);

        messageFile.groupSyncConfirmations = count;
        messageFile.groupSyncPeerKey1 = key1;
        messageFile.groupSyncPeerKey2 = key2;
        messageFile.groupSyncPeerKey3 = key3;

        if (count > 0) {
            messageFile.isDelivered = YES;
        }
    }];
}

- (OCTMessageAbstract *)addGroupPrivateMessageWithText:(NSString *)text
                                                  type:(OCTToxMessageType)type
                                                  chat:(OCTChat *)chat
                                                peerId:(uint32_t)peerId
                                              peerName:(NSString *)peerName
                                        counterpartyId:(uint32_t)counterpartyId
                                             messageId:(OCTToxMessageId)messageId
                                              isOutgoing:(BOOL)isOutgoing
{
    NSParameterAssert(text);
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTMessageText *messageText = [OCTMessageText new];
    messageText.text = text;
    messageText.isDelivered = isOutgoing;
    messageText.type = type;
    messageText.messageId = messageId;
    messageText.groupPeerName = peerName.length > 0 ? peerName : nil;

    OCTMessageAbstract *messageAbstract = [OCTMessageAbstract new];
    messageAbstract.dateInterval = [[NSDate date] timeIntervalSince1970];
    messageAbstract.senderUniqueIdentifier = nil;
    messageAbstract.groupSenderPeerId = isOutgoing ? 0 : peerId;
    messageAbstract.groupPrivateMessage = YES;
    messageAbstract.groupPrivatePeerId = counterpartyId;
    messageAbstract.chatUniqueIdentifier = chat.uniqueIdentifier;
    messageAbstract.messageText = messageText;

    [self addObject:messageAbstract];

    return messageAbstract;
}

- (OCTMessageAbstract *)addGroupMessageWithFileName:(NSString *)fileName
                                           fileSize:(uint64_t)fileSize
                                           filePath:(NSString *)filePath
                                           fileType:(OCTMessageFileType)fileType
                                               chat:(OCTChat *)chat
                                             peerId:(uint32_t)peerId
                                            fileUTI:(NSString *)fileUTI
                                 groupMsgIdHashHex:(NSString *)groupMsgIdHashHex
                              groupTransferProgress:(float)groupTransferProgress
{
    NSParameterAssert(fileName);
    NSParameterAssert(filePath);
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTLogInfo(@"adding group messageFile to chat %@ peer=%u file=%@", chat, peerId, fileName);

    OCTMessageFile *messageFile = [OCTMessageFile new];
    messageFile.internalFileNumber = kOCTToxFileNumberFailure;
    messageFile.fileType = fileType;
    messageFile.fileSize = fileSize;
    messageFile.fileName = fileName;
    [messageFile internalSetFilePath:filePath];
    messageFile.fileUTI = fileUTI;
    messageFile.groupMsgIdHashHex = groupMsgIdHashHex.length > 0 ? groupMsgIdHashHex : nil;
    messageFile.groupTransferProgress = groupTransferProgress;

    OCTMessageAbstract *messageAbstract = [OCTMessageAbstract new];
    messageAbstract.dateInterval = [[NSDate date] timeIntervalSince1970];
    messageAbstract.senderUniqueIdentifier = nil;
    messageAbstract.groupSenderPeerId = peerId;
    messageAbstract.chatUniqueIdentifier = chat.uniqueIdentifier;
    messageAbstract.messageFile = messageFile;

    [self addObject:messageAbstract];

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.lastMessage = messageAbstract;
        theChat.lastActivityDateInterval = messageAbstract.dateInterval;
    }];

    return messageAbstract;
}

- (NSArray<OCTMessageAbstract *> *)groupMessagesForHistorySyncInChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    __block NSMutableArray<OCTMessageAbstract *> *messages = [NSMutableArray new];

    dispatch_sync(self.queue, ^{
        static const NSTimeInterval kHistoryWindowSeconds = 130 * 60;
        static const uint64_t kMaxSyncFileBytes = 36701;
        NSTimeInterval since = [[NSDate date] timeIntervalSince1970] - kHistoryWindowSeconds;
        NSPredicate *predicate = [NSPredicate predicateWithFormat:
                                  @"chatUniqueIdentifier == %@ AND dateInterval > %f AND "
                                  @"(messageText != nil OR (messageFile != nil AND messageFile.fileType == %d AND messageFile.fileSize <= %llu))",
                                  chat.uniqueIdentifier, since, (int)OCTMessageFileTypeReady, kMaxSyncFileBytes];
        RLMResults *results = [OCTMessageAbstract objectsInRealm:self.realm withPredicate:predicate];
        RLMResults *sorted = [results sortedResultsUsingKeyPath:@"dateInterval" ascending:YES];

        for (OCTMessageAbstract *message in sorted) {
            if (message.messageFile && message.messageFile.groupMsgIdHashHex.length == 0) {
                continue;
            }

            [messages addObject:message];
        }
    });

    return messages;
}

- (NSArray<NSData *> *)groupHistorySyncPacketsForGroupNumber:(uint32_t)groupNumber
                                         packetFromMessage:(NSData *(^)(OCTMessageAbstract *message))packetBlock
{
    NSParameterAssert(packetBlock);

    __block NSMutableArray<NSData *> *packets = [NSMutableArray new];

    dispatch_sync(self.queue, ^{
        OCTChat *chat = [[OCTChat objectsInRealm:self.realm where:@"isGroup == YES AND groupNumber == %d", groupNumber] firstObject];

        if (! chat) {
            return;
        }

        static const NSTimeInterval kHistoryWindowSeconds = 130 * 60;
        static const uint64_t kMaxSyncFileBytes = 36701;
        NSTimeInterval since = [[NSDate date] timeIntervalSince1970] - kHistoryWindowSeconds;
        NSPredicate *predicate = [NSPredicate predicateWithFormat:
                                  @"chatUniqueIdentifier == %@ AND dateInterval > %f AND "
                                  @"(messageText != nil OR (messageFile != nil AND messageFile.fileType == %d AND messageFile.fileSize <= %llu))",
                                  chat.uniqueIdentifier, since, (int)OCTMessageFileTypeReady, kMaxSyncFileBytes];
        RLMResults *results = [OCTMessageAbstract objectsInRealm:self.realm withPredicate:predicate];
        RLMResults *sorted = [results sortedResultsUsingKeyPath:@"dateInterval" ascending:YES];

        for (OCTMessageAbstract *message in sorted) {
            if (message.messageFile && message.messageFile.groupMsgIdHashHex.length == 0) {
                continue;
            }

            NSData *packet = packetBlock(message);

            if (packet) {
                [packets addObject:packet];
            }
        }
    });

    return packets;
}

- (BOOL)groupTextMessageExistsInChat:(OCTChat *)chat
                           messageId:(uint32_t)messageId
                              peerId:(uint32_t)peerId
                                text:(NSString *)text
{
    NSParameterAssert(chat);
    NSParameterAssert(text);

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND messageText != nil AND messageText.messageId == %u AND messageText.text == %@",
                              chat.uniqueIdentifier, messageId, text];
    RLMResults *results = [self objectsWithClass:[OCTMessageAbstract class] predicate:predicate];

    return results.count > 0;
}

- (BOOL)groupTextMessageExistsInChat:(OCTChat *)chat
                                text:(NSString *)text
                    nearDateInterval:(NSTimeInterval)dateInterval
                       windowSeconds:(NSTimeInterval)windowSeconds
{
    NSParameterAssert(chat);
    NSParameterAssert(text);

    if (chat.uniqueIdentifier.length == 0 || text.length == 0 || dateInterval <= 0) {
        return NO;
    }

    NSTimeInterval lower = dateInterval - windowSeconds;
    NSTimeInterval upper = dateInterval + windowSeconds;
    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND messageText != nil AND messageText.text == %@ AND dateInterval >= %f AND dateInterval <= %f",
                              chat.uniqueIdentifier, text, lower, upper];
    RLMResults *results = [self objectsWithClass:[OCTMessageAbstract class] predicate:predicate];

    return results.count > 0;
}

- (OCTMessageAbstract *)addGroupSyncedMessageWithText:(NSString *)text
                                                 type:(OCTToxMessageType)type
                                                 chat:(OCTChat *)chat
                                               peerId:(uint32_t)peerId
                                             peerName:(NSString *)peerName
                                            messageId:(OCTToxMessageId)messageId
                                         dateInterval:(NSTimeInterval)dateInterval
{
    NSParameterAssert(text);
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTLogInfo(@"adding synced group messageText to chat %@ peer=%u", chat, peerId);

    OCTMessageText *messageText = [OCTMessageText new];
    messageText.text = text;
    messageText.isDelivered = YES;
    messageText.type = type;
    messageText.messageId = messageId;
    messageText.groupPeerName = peerName.length > 0 ? peerName : nil;

    OCTMessageAbstract *messageAbstract = [OCTMessageAbstract new];
    messageAbstract.dateInterval = dateInterval > 0 ? dateInterval : [[NSDate date] timeIntervalSince1970];
    messageAbstract.senderUniqueIdentifier = nil;
    messageAbstract.groupSenderPeerId = peerId;
    messageAbstract.chatUniqueIdentifier = chat.uniqueIdentifier;
    messageAbstract.groupHistorySync = YES;
    messageAbstract.messageText = messageText;

    [self addObject:messageAbstract];

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        if (! theChat.lastMessage || theChat.lastMessage.dateInterval <= messageAbstract.dateInterval) {
            theChat.lastMessage = messageAbstract;
            theChat.lastActivityDateInterval = messageAbstract.dateInterval;
        }
    }];

    return messageAbstract;
}

- (OCTMessageAbstract *)addGroupSyncedMessageWithFileName:(NSString *)fileName
                                                 fileSize:(uint64_t)fileSize
                                                 filePath:(NSString *)filePath
                                                 fileType:(OCTMessageFileType)fileType
                                                     chat:(OCTChat *)chat
                                                   peerId:(uint32_t)peerId
                                                 peerName:(NSString *)peerName
                                                  fileUTI:(NSString *)fileUTI
                                       groupMsgIdHashHex:(NSString *)groupMsgIdHashHex
                                             dateInterval:(NSTimeInterval)dateInterval
{
    NSParameterAssert(fileName);
    NSParameterAssert(filePath);
    NSParameterAssert(chat);
    NSAssert(chat.isGroup, @"Chat must be a group chat.");

    OCTLogInfo(@"adding synced group messageFile to chat %@ peer=%u file=%@", chat, peerId, fileName);

    OCTMessageFile *messageFile = [OCTMessageFile new];
    messageFile.internalFileNumber = kOCTToxFileNumberFailure;
    messageFile.fileType = fileType;
    messageFile.fileSize = fileSize;
    messageFile.fileName = fileName;
    [messageFile internalSetFilePath:filePath];
    messageFile.fileUTI = fileUTI;
    messageFile.groupMsgIdHashHex = groupMsgIdHashHex.length > 0 ? groupMsgIdHashHex : nil;
    messageFile.groupTransferProgress = 1.0f;

    OCTMessageAbstract *messageAbstract = [OCTMessageAbstract new];
    messageAbstract.dateInterval = dateInterval > 0 ? dateInterval : [[NSDate date] timeIntervalSince1970];
    messageAbstract.senderUniqueIdentifier = nil;
    messageAbstract.groupSenderPeerId = peerId;
    messageAbstract.chatUniqueIdentifier = chat.uniqueIdentifier;
    messageAbstract.groupHistorySync = YES;
    messageAbstract.messageFile = messageFile;

    [self addObject:messageAbstract];

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        if (! theChat.lastMessage || theChat.lastMessage.dateInterval <= messageAbstract.dateInterval) {
            theChat.lastMessage = messageAbstract;
            theChat.lastActivityDateInterval = messageAbstract.dateInterval;
        }
    }];

    return messageAbstract;
}

- (nullable OCTMessageAbstract *)groupMessageWithGroupMsgIdHashHex:(NSString *)groupMsgIdHashHex
                                                              chat:(OCTChat *)chat
{
    if (groupMsgIdHashHex.length == 0 || chat.uniqueIdentifier.length == 0) {
        return nil;
    }

    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"chatUniqueIdentifier == %@ AND messageFile.groupMsgIdHashHex ==[c] %@",
                              chat.uniqueIdentifier, groupMsgIdHashHex];

    return [OCTMessageAbstract objectsWithPredicate:predicate].firstObject;
}

- (nullable OCTMessageAbstract *)groupIncompleteFileMessageForChat:(OCTChat *)chat
                                                            peerId:(uint32_t)peerId
                                                          fileName:(NSString *)fileName
{
    // KHANDAQ (#15): most recent NOT-ready incoming file message from this peer with this name. The
    // sender's retries change the file's msgIdHex between BEGIN and COMPLETE, so a hash lookup misses
    // the loading bubble and a duplicate "ready" copy gets created. Match by content instead to
    // update the existing loading bubble.
    if (chat.uniqueIdentifier.length == 0 || fileName.length == 0) {
        return nil;
    }

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
        @"chatUniqueIdentifier == %@ AND groupSenderPeerId == %u AND messageFile != nil AND messageFile.fileName ==[c] %@ AND messageFile.fileType != %d",
        chat.uniqueIdentifier, peerId, fileName, (int)OCTMessageFileTypeReady];

    return [[OCTMessageAbstract objectsWithPredicate:predicate] sortedResultsUsingKeyPath:@"dateInterval" ascending:NO].firstObject;
}

- (BOOL)markGroupIncomingFileReadyInChat:(OCTChat *)chat
                               msgIdHash:(NSString *)msgIdHash
                                  peerId:(uint32_t)peerId
                                fileName:(NSString *)fileName
                                filePath:(NSString *)filePath
                                fileSize:(uint64_t)fileSize
{
    // KHANDAQ (#15): find the loading bubble AND update it to ready ATOMICALLY on the manager's own
    // realm/queue. The previous approach looked up via the calling thread's default realm (which can
    // be a stale/separate instance) and then updated via this realm — so the lookup missed the
    // BEGIN's message (byHash=0/byContent=0) and a duplicate "ready" copy was created. Matching by
    // groupMsgIdHashHex first, then by sender+filename, both within this chat.
    if (chat.uniqueIdentifier.length == 0) {
        return NO;
    }

    __block BOOL handled = NO;

    dispatch_sync(self.queue, ^{
        OCTMessageAbstract *existing = nil;

        if (msgIdHash.length > 0) {
            existing = [[OCTMessageAbstract objectsInRealm:self.realm
                                                     where:@"chatUniqueIdentifier == %@ AND messageFile.groupMsgIdHashHex ==[c] %@",
                         chat.uniqueIdentifier, msgIdHash] firstObject];
        }

        if (! existing && fileName.length > 0) {
            existing = [[[OCTMessageAbstract objectsInRealm:self.realm
                                                      where:@"chatUniqueIdentifier == %@ AND groupSenderPeerId == %u AND messageFile != nil AND messageFile.fileName ==[c] %@ AND messageFile.fileType != %d",
                          chat.uniqueIdentifier, peerId, fileName, (int)OCTMessageFileTypeReady]
                         sortedResultsUsingKeyPath:@"dateInterval" ascending:NO] firstObject];
        }

        if (! existing || ! existing.messageFile || existing.isInvalidated) {
            return;
        }

        handled = YES;

        if (existing.messageFile.fileType == OCTMessageFileTypeReady) {
            return;
        }

        [self.realm beginWriteTransaction];
        existing.messageFile.fileType = OCTMessageFileTypeReady;
        existing.messageFile.fileSize = fileSize;
        existing.messageFile.fileName = fileName;
        [existing.messageFile internalSetFilePath:filePath];
        existing.messageFile.groupTransferProgress = 1.0f;
        // KHANDAQ: do NOT re-stamp the sender here. groupSenderPeerId was set at file BEGIN, when the
        // peer_id was valid for the real sender. NGC reuses peer_ids on leave/rejoin, so the peer_id
        // at COMPLETION time can belong to a different peer (or self) — overwriting it here made an
        // incoming voice/file show as another person's, or your own. Keep the begin-time attribution.
        (void)peerId;
        [self.realm commitWriteTransaction];
    });

    return handled;
}

- (OCTMessageAbstract *)addMessageWithFileNumber:(OCTToxFileNumber)fileNumber
                                        fileType:(OCTMessageFileType)fileType
                                        fileSize:(OCTToxFileSize)fileSize
                                        fileName:(NSString *)fileName
                                        filePath:(NSString *)filePath
                                         fileUTI:(NSString *)fileUTI
                                            chat:(OCTChat *)chat
                                          sender:(OCTFriend *)sender
{
    OCTLogInfo(@"adding messageFile to chat %@, fileSize %lld", chat, fileSize);

    OCTMessageFile *messageFile = [OCTMessageFile new];
    messageFile.internalFileNumber = fileNumber;
    messageFile.fileType = fileType;
    messageFile.fileSize = fileSize;
    messageFile.fileName = fileName;
    [messageFile internalSetFilePath:filePath];
    messageFile.fileUTI = fileUTI;

    return [self addMessageAbstractWithChat:chat sender:sender messageText:nil messageFile:messageFile messageCall:nil tssent:0 tsrcvd:0];
}

- (OCTMessageAbstract *)addMessageCall:(OCTCall *)call
{
    OCTLogInfo(@"adding messageCall to call %@", call);

    OCTMessageCallEvent event;
    switch (call.status) {
        case OCTCallStatusDialing:
        case OCTCallStatusRinging:
            event = OCTMessageCallEventUnanswered;
            break;
        case OCTCallStatusActive:
            event = OCTMessageCallEventAnswered;
            break;
    }

    OCTMessageCall *messageCall = [OCTMessageCall new];
    messageCall.callDuration = call.callDuration;
    messageCall.callEvent = event;

    return [self addMessageAbstractWithChat:call.chat sender:call.caller messageText:nil messageFile:nil messageCall:messageCall tssent:0 tsrcvd:0];
}

#pragma mark -  Private

+ (RLMMigrationBlock)realmMigrationBlock
{
    return ^(RLMMigration *migration, uint64_t oldSchemaVersion) {
               if (oldSchemaVersion < 1) {
                   // objcTox version 0.1.0
               }

               if (oldSchemaVersion < 2) {
                   // objcTox version 0.2.1
               }

               if (oldSchemaVersion < 3) {
                   // objcTox version 0.4.0
               }

               if (oldSchemaVersion < 4) {
                   // objcTox version 0.5.0
                   [self doMigrationVersion4:migration];
               }

               if (oldSchemaVersion < 5) {
                   // OCTMessageAbstract: chat property replaced with chatUniqueIdentifier
                   [self doMigrationVersion5:migration];
               }

               if (oldSchemaVersion < 6) {
                   // OCTSettingsStorageObject: adding genericSettingsData property.
               }

               if (oldSchemaVersion < 7) {
                   [self doMigrationVersion7:migration];
               }

               if (oldSchemaVersion < 8) {
                   [self doMigrationVersion8:migration];
               }

               if (oldSchemaVersion < 9) {}

               if (oldSchemaVersion < 10) {}

               if (oldSchemaVersion < 11) {
                   [self doMigrationVersion11:migration];
               }

               if (oldSchemaVersion < 12) {
                   [self doMigrationVersion12:migration];
               }

               if (oldSchemaVersion < 13) {
                   [self doMigrationVersion13:migration];
               }

               if (oldSchemaVersion < 14) {
                   [self doMigrationVersion14:migration];
               }

               if (oldSchemaVersion < 16) {
                   [self doMigrationVersion16:migration];
               }

               if (oldSchemaVersion < 17) {
                   [self doMigrationVersion17:migration];
               }

               if (oldSchemaVersion < 18) {
                   [self doMigrationVersion18:migration];
               }

               if (oldSchemaVersion < 19) {
                   [self doMigrationVersion19:migration];
               }

               if (oldSchemaVersion < 20) {
                   [self doMigrationVersion20:migration];
               }

               if (oldSchemaVersion < 21) {
                   [self doMigrationVersion21:migration];
               }

               if (oldSchemaVersion < 22) {
                   [self doMigrationVersion22:migration];
               }

               if (oldSchemaVersion < 23) {
                   [self doMigrationVersion23:migration];
               }

               if (oldSchemaVersion < 24) {
                   [self doMigrationVersion24:migration];
               }

               if (oldSchemaVersion < 25) {
                   [self doMigrationVersion25:migration];
               }

               if (oldSchemaVersion < 26) {
                   [self doMigrationVersion26:migration];
               }

               if (oldSchemaVersion < 27) {
                   [self doMigrationVersion27:migration];
               }

               if (oldSchemaVersion < 28) {
                   [self doMigrationVersion28:migration];
               }

               if (oldSchemaVersion < 29) {
                   [self doMigrationVersion29:migration];
               }

               if (oldSchemaVersion < 30) {
                   [self doMigrationVersion30:migration];
               }

               if (oldSchemaVersion < 31) {
                   [self doMigrationVersion31:migration];
               }
    };
}

+ (void)doMigrationVersion4:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTChat.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"enteredText"] = [oldObject[@"enteredText"] length] > 0 ? oldObject[@"enteredText"] : nil;
    }];

    [migration enumerateObjects:OCTFriend.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"name"] = [oldObject[@"name"] length] > 0 ? oldObject[@"name"] : nil;
        newObject[@"statusMessage"] = [oldObject[@"statusMessage"] length] > 0 ? oldObject[@"statusMessage"] : nil;
    }];

    [migration enumerateObjects:OCTFriendRequest.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"message"] = [oldObject[@"message"] length] > 0 ? oldObject[@"message"] : nil;
    }];

    [migration enumerateObjects:OCTMessageFile.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"fileName"] = [oldObject[@"fileName"] length] > 0 ? oldObject[@"fileName"] : nil;
        newObject[@"fileUTI"] = [oldObject[@"fileUTI"] length] > 0 ? oldObject[@"fileUTI"] : nil;
    }];

    [migration enumerateObjects:OCTMessageText.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"text"] = [oldObject[@"text"] length] > 0 ? oldObject[@"text"] : nil;
    }];
}

+ (void)doMigrationVersion5:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTMessageAbstract.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"chatUniqueIdentifier"] = oldObject[@"chat"][@"uniqueIdentifier"];
        newObject[@"senderUniqueIdentifier"] = oldObject[@"sender"][@"uniqueIdentifier"];
    }];
}

+ (void)doMigrationVersion7:(RLMMigration *)migration
{
    // Before this version OCTMessageText.isDelivered was broken.
    // See https://github.com/Antidote-for-Tox/objcTox/issues/158
    //
    // After update it was fixed + resending of undelivered messages feature was introduced.
    // This fired resending all messages that were in history for all friends.
    //
    // To fix an issue and stop people suffering we mark all outgoing text messages as delivered.

    [migration enumerateObjects:OCTMessageAbstract.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        if (newObject[@"senderUniqueIdentifier"] != nil) {
            return;
        }

        RLMObject *messageText = newObject[@"messageText"];

        if (! messageText) {
            return;
        }

        messageText[@"isDelivered"] = @YES;
    }];
}

+ (void)doMigrationVersion8:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTFriend.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"pushToken"] = nil;
    }];
}

+ (void)doMigrationVersion11:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTFriend.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"msgv3Capability"] = @NO;
    }];
}

+ (void)doMigrationVersion12:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTMessageText.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"msgv3HashHex"] = nil;
    }];
}

+ (void)doMigrationVersion13:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTMessageText.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"sentPush"] = @YES;
    }];
}

+ (void)doMigrationVersion14:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTMessageAbstract.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"tssent"] = @0;
        newObject[@"tsrcvd"] = @0;
    }];
}

+ (void)doMigrationVersion16:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTFriend.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"capabilities2"] = nil;
    }];
}

+ (void)doMigrationVersion18:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTChat.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupPeerCount"] = @0;
    }];
}

+ (void)doMigrationVersion19:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTMessageFile.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupMsgIdHashHex"] = nil;
        newObject[@"groupTransferProgress"] = @0.0f;
    }];
}

+ (void)doMigrationVersion26:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTChat.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupVoiceState"] = @0;
    }];
}

+ (void)doMigrationVersion28:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTMessageAbstract.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupSystemMessage"] = @NO;
    }];
}

+ (void)doMigrationVersion29:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTMessageAbstract.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupPendingSend"] = @NO;
    }];
}

+ (void)doMigrationVersion30:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTGroupPeer.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"peerPublicKeyHex"] = nil;
    }];

    [migration enumerateObjects:OCTSettingsStorageObject.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupShowSystemMessages"] = @NO;
    }];
}

+ (void)doMigrationVersion31:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTGroupPeer.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"peerLastSeenDateInterval"] = @0.0;
    }];
}

+ (void)doMigrationVersion27:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTMessageFile.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        BOOL delivered = NO;

        if ([oldObject[@"fileType"] intValue] == OCTMessageFileTypeReady &&
            [oldObject[@"groupSyncConfirmations"] intValue] > 0) {
            delivered = YES;
        }

        newObject[@"isDelivered"] = @(delivered);
    }];
}

+ (void)doMigrationVersion25:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTGroupPeer.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"privateLastReadDateInterval"] = @0.0;
    }];
}

+ (void)doMigrationVersion24:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTChat.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupTopicLockEnabled"] = @NO;
        newObject[@"groupPeerLimit"] = @0;
    }];

    [migration enumerateObjects:OCTMessageText.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupSyncPeerKey1"] = nil;
        newObject[@"groupSyncPeerKey2"] = nil;
        newObject[@"groupSyncPeerKey3"] = nil;
    }];

    [migration enumerateObjects:OCTMessageFile.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupSyncConfirmations"] = @0;
        newObject[@"groupSyncPeerKey1"] = nil;
        newObject[@"groupSyncPeerKey2"] = nil;
        newObject[@"groupSyncPeerKey3"] = nil;
    }];
}

+ (void)doMigrationVersion23:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTChat.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupTopic"] = nil;
        newObject[@"groupPassword"] = nil;
    }];

    [migration enumerateObjects:OCTMessageAbstract.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupPrivateMessage"] = @NO;
        newObject[@"groupPrivatePeerId"] = @0;
    }];

    [migration enumerateObjects:OCTMessageText.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupSyncConfirmations"] = @0;
    }];
}

+ (void)doMigrationVersion22:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTGroupPeer.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"peerRole"] = @(OCTToxGroupRoleUser);
    }];
}

+ (void)doMigrationVersion21:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTChat.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupNotificationsSilent"] = @NO;
    }];

    [migration enumerateObjects:OCTGroupPeer.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"peerNotificationsSilent"] = @NO;
    }];
}

+ (void)doMigrationVersion20:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTMessageAbstract.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupHistorySync"] = @NO;
    }];
}

+ (void)doMigrationVersion17:(RLMMigration *)migration
{
    [migration enumerateObjects:OCTChat.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"isGroup"] = @NO;
        newObject[@"groupNumber"] = @(-1);
        newObject[@"groupPrivacyState"] = @0;
    }];

    [migration enumerateObjects:OCTMessageAbstract.className block:^(RLMObject *oldObject, RLMObject *newObject) {
        newObject[@"groupSenderPeerId"] = @0;
    }];
}

/**
 * Only one of messageText, messageFile or messageCall can be non-nil.
 */
- (OCTMessageAbstract *)addMessageAbstractWithChat:(OCTChat *)chat
                                            sender:(OCTFriend *)sender
                                       messageText:(OCTMessageText *)messageText
                                       messageFile:(OCTMessageFile *)messageFile
                                       messageCall:(OCTMessageCall *)messageCall
                                            tssent:(UInt32)tssent
                                            tsrcvd:(UInt32)tsrcvd
{
    NSParameterAssert(chat);

    NSAssert( (messageText && ! messageFile && ! messageCall) ||
              (! messageText && messageFile && ! messageCall) ||
              (! messageText && ! messageFile && messageCall),
              @"Wrong options passed. Only one of messageText, messageFile or messageCall should be non-nil.");

    OCTMessageAbstract *messageAbstract = [OCTMessageAbstract new];
    messageAbstract.dateInterval = [[NSDate date] timeIntervalSince1970];
    messageAbstract.senderUniqueIdentifier = sender.uniqueIdentifier;
    messageAbstract.chatUniqueIdentifier = chat.uniqueIdentifier;
    messageAbstract.tssent = tssent;
    messageAbstract.tsrcvd = tsrcvd;
    messageAbstract.messageText = messageText;
    messageAbstract.messageFile = messageFile;
    messageAbstract.messageCall = messageCall;

    [self addObject:messageAbstract];

    [self updateObject:chat withBlock:^(OCTChat *theChat) {
        theChat.lastMessage = messageAbstract;
        theChat.lastActivityDateInterval = messageAbstract.dateInterval;
    }];

    return messageAbstract;
}

// Delete an NSArray, RLMArray, or RLMResults of messages from this Realm.
- (void)removeMessagesWithSubmessages:(id)messages
{
    for (OCTMessageAbstract *message in messages) {
        if (message.messageText) {
            [self.realm deleteObject:message.messageText];
        }
        if (message.messageFile) {
            [self.realm deleteObject:message.messageFile];
        }
        if (message.messageCall) {
            [self.realm deleteObject:message.messageCall];
        }
    }

    [self.realm deleteObjects:messages];
}

@end
