// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTPushPreview.h"
#import "OCTTox.h"

#import <CommonCrypto/CommonDigest.h>
#import <CommonCrypto/CommonHMAC.h>
#import <Security/Security.h>
#import <libsodium/crypto_aead_chacha20poly1305.h>

NSString *const kOCTPushPreviewKindText = @"text";
NSString *const kOCTPushPreviewKindVoice = @"voice";
NSString *const kOCTPushPreviewKindVideo = @"video";
NSString *const kOCTPushPreviewKindPhoto = @"photo";
NSString *const kOCTPushPreviewKindFile = @"file";
NSString *const kOCTPushPreviewKindReaction = @"reaction";
NSString *const kOCTPushPreviewKeychainService = @"org.khandaq.push.preview";
NSString *const kOCTPushPreviewWakeFieldPreview = @"preview";
NSString *const kOCTPushPreviewWakeFieldPid = @"pid";

static NSString *const kOCTPushPreviewMasterAccount = @"master";
/// The contacts this device handed its K to and still has, their public keys, 32 bytes each: the only senders
/// whose previews the notification service extension shows (PushPreviewStore.allowsSender). A contact removed or
/// blocked keeps the K it was given and the wake URL, and without this list its wakes would still put its name and
/// words on the lock screen.
static NSString *const kOCTPushPreviewAllowedAccount = @"allow";
/// Where the keys contacts sent us are kept, one item per contact (account = their public key).
static NSString *const kOCTPushPreviewPeerService = @"org.khandaq.push.preview.peer";
static NSString *const kOCTGroupPresenceDefaultsPrefix = @"khandaq.groupPresence.";
/// M once read or made in this process; resetAll drops it with the stored one.
static NSData *sOCTPushPreviewMasterKey = nil;

enum {
    kOCTPushPreviewPacketId = 190,
    kOCTPushPreviewSubtypeKey = 0x01,
    kOCTPushPreviewSubtypePresence = 0x02,
    kOCTPushPreviewPacketVersion = 0x01,
    kOCTPushPreviewSealVersion = 0x01,
    kOCTPushPreviewKeyLength = 32,
    kOCTPushPreviewPublicKeyLength = 32,
    kOCTPushPreviewChatIdLength = 32,
    kOCTPushPreviewPresenceHashLength = 16,
    kOCTPushPreviewPresenceMaxHashes = 64,
    kOCTPushPreviewNonceLength = 12,
    kOCTPushPreviewTagLength = 16,
    kOCTPushPreviewPidLength = 16,
    kOCTPushPreviewMaxPlaintextBytes = 900,
    kOCTPushPreviewMaxSealedCharacters = 1400,
};

static const char kOCTPushPreviewKeyLabel[] = "khandaq push preview key v1";
static const char kOCTPushPreviewAssociatedData[] = "khandaq push preview v1";
static const char kOCTPushPreviewEventLabel[] = "khandaq push event v1";
static const char kOCTGroupPresenceLabel[] = "khandaq group presence v1";

#pragma mark - Hex

static int OCTPushPreviewHexValue(unichar c)
{
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    return -1;
}

/// The bytes of a hex string of exactly `length` bytes, nil for any other shape.
static NSData *OCTPushPreviewDataFromHex(NSString *hex, NSUInteger length)
{
    if (! [hex isKindOfClass:[NSString class]] || hex.length != length * 2) {
        return nil;
    }
    NSMutableData *data = [NSMutableData dataWithLength:length];
    uint8_t *bytes = data.mutableBytes;
    for (NSUInteger i = 0; i < length; i++) {
        int high = OCTPushPreviewHexValue([hex characterAtIndex:2 * i]);
        int low = OCTPushPreviewHexValue([hex characterAtIndex:2 * i + 1]);
        if (high < 0 || low < 0) {
            return nil;
        }
        bytes[i] = (uint8_t)((high << 4) | low);
    }
    return data;
}

static NSString *OCTPushPreviewHex(const uint8_t *bytes, NSUInteger length)
{
    static const char digits[] = "0123456789abcdef";
    NSMutableString *hex = [NSMutableString stringWithCapacity:length * 2];
    for (NSUInteger i = 0; i < length; i++) {
        [hex appendFormat:@"%c%c", digits[bytes[i] >> 4], digits[bytes[i] & 0x0F]];
    }
    return hex;
}

/// A contact's public key as the account name of their items: 64 uppercase hex, nil for anything else.
static NSString *OCTPushPreviewAccount(NSString *publicKeyHex)
{
    if (OCTPushPreviewDataFromHex(publicKeyHex, kOCTPushPreviewPublicKeyLength) == nil) {
        return nil;
    }
    return publicKeyHex.uppercaseString;
}

#pragma mark - Keychain

/// kSecAttrAccessible stays out of the query: in a search it is a predicate, not an attribute (the trap
/// described in OCTPushUrlValidator.m).
static NSData *OCTPushPreviewKeychainRead(NSString *service, NSString *account, OSStatus *outStatus)
{
    NSDictionary *query = @{
        (__bridge id)kSecClass : (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService : service,
        (__bridge id)kSecAttrAccount : account,
        (__bridge id)kSecReturnData : @YES,
        (__bridge id)kSecMatchLimit : (__bridge id)kSecMatchLimitOne,
    };
    CFTypeRef result = NULL;
    OSStatus status = SecItemCopyMatching((__bridge CFDictionaryRef)query, &result);
    if (outStatus != NULL) {
        *outStatus = status;
    }
    if (status != errSecSuccess || result == NULL) {
        if (result != NULL) {
            CFRelease(result);
        }
        return nil;
    }
    id value = (__bridge_transfer id)result;
    return [value isKindOfClass:[NSData class]] ? value : nil;
}

/// Device-only and readable after the first unlock: a push arrives in the background, the extension
/// reads these items then, and a backup must not carry them to another device.
static BOOL OCTPushPreviewKeychainWrite(NSString *service, NSString *account, NSData *value)
{
    NSDictionary *base = @{
        (__bridge id)kSecClass : (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService : service,
        (__bridge id)kSecAttrAccount : account,
    };
    SecItemDelete((__bridge CFDictionaryRef)base);
    NSMutableDictionary *add = [base mutableCopy];
    add[(__bridge id)kSecValueData] = value;
    add[(__bridge id)kSecAttrAccessible] = (__bridge id)kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly;
    return SecItemAdd((__bridge CFDictionaryRef)add, NULL) == errSecSuccess;
}

static void OCTPushPreviewKeychainDelete(NSString *service, NSString *account)
{
    NSDictionary *query = @{
        (__bridge id)kSecClass : (__bridge id)kSecClassGenericPassword,
        (__bridge id)kSecAttrService : service,
        (__bridge id)kSecAttrAccount : account,
    };
    SecItemDelete((__bridge CFDictionaryRef)query);
}

#pragma mark - Allowed senders

/// The stored list, or an empty one when there is none yet; nil when it cannot be read now (the device locked
/// since a restart, say), and then nothing may be written over it. A list whose length is not a whole number of
/// keys is no list: it starts again, and every contact goes back on it the next time it connects.
static NSData *OCTPushPreviewAllowedRead(void)
{
    OSStatus status = errSecSuccess;
    NSData *held = OCTPushPreviewKeychainRead(kOCTPushPreviewKeychainService, kOCTPushPreviewAllowedAccount, &status);
    if (held == nil) {
        return status == errSecItemNotFound ? [NSData data] : nil;
    }
    return (held.length % kOCTPushPreviewPublicKeyLength == 0) ? held : [NSData data];
}

static BOOL OCTPushPreviewAllowedContains(NSData *held, NSData *publicKey)
{
    if (publicKey.length != kOCTPushPreviewPublicKeyLength) {
        return NO;
    }
    const uint8_t *bytes = held.bytes;
    for (NSUInteger offset = 0; offset + kOCTPushPreviewPublicKeyLength <= held.length; offset += kOCTPushPreviewPublicKeyLength) {
        if (memcmp(bytes + offset, publicKey.bytes, kOCTPushPreviewPublicKeyLength) == 0) {
            return YES;
        }
    }
    return NO;
}

#pragma mark - JSON

/// One JSON string literal, quotes included, escaped by Foundation.
static NSString *OCTPushPreviewJSONString(NSString *value)
{
    NSJSONWritingOptions options = 0;
    if (@available(iOS 13.0, macOS 10.15, *)) {
        options |= NSJSONWritingWithoutEscapingSlashes;
    }
    NSData *data = [NSJSONSerialization dataWithJSONObject:@[value] options:options error:nil];
    NSString *wrapped = data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : nil;
    if (wrapped.length < 4 || ! [wrapped hasPrefix:@"["] || ! [wrapped hasSuffix:@"]"]) {
        return nil;
    }
    return [wrapped substringWithRange:NSMakeRange(1, wrapped.length - 2)];
}

/// Fields in the order of the spec, so that one input always gives the same bytes (the test vectors
/// compare them).
static NSData *OCTPushPreviewSerialize(NSDictionary<NSString *, id> *fields)
{
    NSArray<NSString *> *order = @[@"v", @"k", @"b", @"rk", @"e", @"n", @"g", @"gid", @"ts", @"h"];
    NSMutableString *json = [NSMutableString stringWithString:@"{"];
    BOOL first = YES;
    for (NSString *name in order) {
        id value = fields[name];
        NSString *encoded = nil;
        if ([value isKindOfClass:[NSString class]]) {
            encoded = OCTPushPreviewJSONString(value);
            if (encoded == nil) {
                return nil;
            }
        }
        else if ([value isKindOfClass:[NSNumber class]]) {
            encoded = [NSString stringWithFormat:@"%lld", [value longLongValue]];
        }
        else {
            continue;
        }
        [json appendFormat:@"%@\"%@\":%@", first ? @"" : @",", name, encoded];
        first = NO;
    }
    [json appendString:@"}"];
    return [json dataUsingEncoding:NSUTF8StringEncoding];
}

@implementation OCTPushPreview

#pragma mark - Our own keys

+ (NSData *)masterKey
{
    @synchronized(self) {
        if (sOCTPushPreviewMasterKey != nil) {
            return sOCTPushPreviewMasterKey;
        }

        OSStatus status = errSecSuccess;
        NSData *stored = OCTPushPreviewKeychainRead(kOCTPushPreviewKeychainService, kOCTPushPreviewMasterAccount, &status);
        if (stored.length == kOCTPushPreviewKeyLength) {
            sOCTPushPreviewMasterKey = stored;
            return sOCTPushPreviewMasterKey;
        }
        if (stored == nil && status != errSecItemNotFound) {
            // Not readable now (locked since a restart, say). A new key would orphan every key already
            // handed to contacts, so nothing is published until the stored one can be read.
            return nil;
        }

        uint8_t fresh[kOCTPushPreviewKeyLength];
        if (SecRandomCopyBytes(kSecRandomDefault, sizeof(fresh), fresh) != errSecSuccess) {
            return nil;
        }
        NSData *key = [NSData dataWithBytes:fresh length:sizeof(fresh)];
        memset(fresh, 0, sizeof(fresh));

        if (! OCTPushPreviewKeychainWrite(kOCTPushPreviewKeychainService, kOCTPushPreviewMasterAccount, key)) {
            return nil;
        }
        sOCTPushPreviewMasterKey = key;
        return sOCTPushPreviewMasterKey;
    }
}

+ (BOOL)allowSenderPublicKey:(NSString *)publicKeyHex
{
    NSData *publicKey = OCTPushPreviewDataFromHex(publicKeyHex, kOCTPushPreviewPublicKeyLength);
    if (publicKey == nil) {
        return NO;
    }
    @synchronized(self) {
        NSData *held = OCTPushPreviewAllowedRead();
        if (held == nil) {
            return NO;
        }
        if (OCTPushPreviewAllowedContains(held, publicKey)) {
            return YES;
        }
        NSMutableData *updated = [held mutableCopy];
        [updated appendData:publicKey];
        return OCTPushPreviewKeychainWrite(kOCTPushPreviewKeychainService, kOCTPushPreviewAllowedAccount, updated);
    }
}

+ (BOOL)isAllowedSenderPublicKey:(NSString *)publicKeyHex
{
    NSData *publicKey = OCTPushPreviewDataFromHex(publicKeyHex, kOCTPushPreviewPublicKeyLength);
    NSData *held = publicKey ? OCTPushPreviewAllowedRead() : nil;
    return held != nil && OCTPushPreviewAllowedContains(held, publicKey);
}

+ (void)resetAll
{
    @synchronized(self) {
        sOCTPushPreviewMasterKey = nil;
        OCTPushPreviewKeychainDelete(kOCTPushPreviewKeychainService, kOCTPushPreviewMasterAccount);
        OCTPushPreviewKeychainDelete(kOCTPushPreviewKeychainService, kOCTPushPreviewAllowedAccount);
        // Every key a contact sent, in one go: the query names the service and no account.
        NSDictionary *peers = @{
            (__bridge id)kSecClass : (__bridge id)kSecClassGenericPassword,
            (__bridge id)kSecAttrService : kOCTPushPreviewPeerService,
        };
        SecItemDelete((__bridge CFDictionaryRef)peers);
        NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
        for (NSString *key in defaults.dictionaryRepresentation.allKeys) {
            if ([key hasPrefix:kOCTGroupPresenceDefaultsPrefix]) {
                [defaults removeObjectForKey:key];
            }
        }
    }
}

+ (NSData *)perContactKeyWithMasterKey:(NSData *)masterKey publicKeyHex:(NSString *)publicKeyHex
{
    NSData *publicKey = OCTPushPreviewDataFromHex(publicKeyHex, kOCTPushPreviewPublicKeyLength);
    if (masterKey.length != kOCTPushPreviewKeyLength || publicKey == nil) {
        return nil;
    }

    NSMutableData *message = [NSMutableData dataWithBytes:kOCTPushPreviewKeyLabel length:strlen(kOCTPushPreviewKeyLabel)];
    [message appendData:publicKey];

    uint8_t digest[CC_SHA256_DIGEST_LENGTH];
    CCHmac(kCCHmacAlgSHA256, masterKey.bytes, masterKey.length, message.bytes, message.length, digest);
    return [NSData dataWithBytes:digest length:sizeof(digest)];
}

+ (NSData *)keyPacketForFriendPublicKey:(NSString *)publicKeyHex
{
    NSData *masterKey = [self masterKey];
    NSData *key = masterKey ? [self perContactKeyWithMasterKey:masterKey publicKeyHex:publicKeyHex] : nil;
    // The contact goes on the list of senders whose previews are shown before the key goes to them, and the key
    // goes only to someone on it.
    if (key == nil || ! [self allowSenderPublicKey:publicKeyHex]) {
        return nil;
    }

    NSMutableData *packet = [NSMutableData dataWithCapacity:3 + kOCTPushPreviewKeyLength];
    const uint8_t header[3] = { kOCTPushPreviewPacketId, kOCTPushPreviewSubtypeKey, kOCTPushPreviewPacketVersion };
    [packet appendBytes:header length:sizeof(header)];
    [packet appendData:key];
    return packet;
}

#pragma mark - Group presence

+ (NSString *)groupPresenceHashForChatIdHex:(NSString *)chatIdHex peerPublicKeyHex:(NSString *)peerPublicKeyHex
{
    NSData *chatId = OCTPushPreviewDataFromHex(chatIdHex, kOCTPushPreviewChatIdLength);
    NSData *peerKey = OCTPushPreviewDataFromHex(peerPublicKeyHex, kOCTPushPreviewPublicKeyLength);
    if (chatId == nil || peerKey == nil) {
        return nil;
    }

    NSMutableData *input = [NSMutableData dataWithBytes:kOCTGroupPresenceLabel length:strlen(kOCTGroupPresenceLabel)];
    [input appendData:chatId];
    [input appendData:peerKey];

    uint8_t digest[CC_SHA256_DIGEST_LENGTH];
    CC_SHA256(input.bytes, (CC_LONG)input.length, digest);
    return OCTPushPreviewHex(digest, kOCTPushPreviewPresenceHashLength);
}

+ (NSArray<NSString *> *)ownGroupPresenceHashesWithTox:(OCTTox *)tox
{
    NSMutableArray<NSString *> *hashes = [NSMutableArray new];
    for (NSNumber *number in [tox groupNumbers]) {
        OCTToxGroupNumber groupNumber = (OCTToxGroupNumber)number.unsignedIntValue;
        NSString *chatId = [tox groupChatIdHexForGroupNumber:groupNumber error:nil];
        NSString *selfKey = [tox groupSelfPublicKeyHexForGroupNumber:groupNumber error:nil];
        NSString *hash = (chatId && selfKey) ? [self groupPresenceHashForChatIdHex:chatId peerPublicKeyHex:selfKey] : nil;
        if (hash != nil && ! [hashes containsObject:hash]) {
            [hashes addObject:hash];
        }
        if (hashes.count == kOCTPushPreviewPresenceMaxHashes) {
            break;
        }
    }
    return hashes;
}

+ (NSData *)groupPresencePacketWithHashes:(NSArray<NSString *> *)hashes
{
    NSMutableData *body = [NSMutableData new];
    uint8_t count = 0;
    for (NSString *hash in hashes) {
        if (count == kOCTPushPreviewPresenceMaxHashes) {
            break;
        }
        NSData *bytes = OCTPushPreviewDataFromHex(hash, kOCTPushPreviewPresenceHashLength);
        if (bytes == nil) {
            continue;
        }
        [body appendData:bytes];
        count++;
    }

    NSMutableData *packet = [NSMutableData dataWithCapacity:4 + body.length];
    const uint8_t header[4] = { kOCTPushPreviewPacketId, kOCTPushPreviewSubtypePresence, kOCTPushPreviewPacketVersion, count };
    [packet appendBytes:header length:sizeof(header)];
    [packet appendData:body];
    return packet;
}

#pragma mark - Incoming packet 190

+ (NSData *)peerKeyFromPacket:(NSData *)packet
{
    if (packet.length != 3 + kOCTPushPreviewKeyLength) {
        return nil;
    }
    const uint8_t *bytes = packet.bytes;
    if (bytes[0] != kOCTPushPreviewPacketId || bytes[1] != kOCTPushPreviewSubtypeKey || bytes[2] != kOCTPushPreviewPacketVersion) {
        return nil;
    }
    return [packet subdataWithRange:NSMakeRange(3, kOCTPushPreviewKeyLength)];
}

+ (NSArray<NSString *> *)groupPresenceHashesFromPacket:(NSData *)packet
{
    if (packet.length < 4) {
        return nil;
    }
    const uint8_t *bytes = packet.bytes;
    if (bytes[0] != kOCTPushPreviewPacketId || bytes[1] != kOCTPushPreviewSubtypePresence || bytes[2] != kOCTPushPreviewPacketVersion) {
        return nil;
    }
    NSUInteger count = bytes[3];
    if (count > kOCTPushPreviewPresenceMaxHashes || packet.length != 4 + count * kOCTPushPreviewPresenceHashLength) {
        return nil;
    }
    NSMutableArray<NSString *> *hashes = [NSMutableArray arrayWithCapacity:count];
    for (NSUInteger i = 0; i < count; i++) {
        [hashes addObject:OCTPushPreviewHex(bytes + 4 + i * kOCTPushPreviewPresenceHashLength, kOCTPushPreviewPresenceHashLength)];
    }
    return hashes;
}

#pragma mark - What contacts sent us

+ (void)storePeerKey:(NSData *)key forFriendPublicKey:(NSString *)publicKeyHex
{
    NSString *account = OCTPushPreviewAccount(publicKeyHex);
    if (account == nil || key.length != kOCTPushPreviewKeyLength) {
        return;
    }
    // The same key comes again on every reconnect: nothing to write then.
    if ([OCTPushPreviewKeychainRead(kOCTPushPreviewPeerService, account, NULL) isEqualToData:key]) {
        return;
    }
    OCTPushPreviewKeychainWrite(kOCTPushPreviewPeerService, account, key);
}

+ (NSData *)peerKeyForFriendPublicKey:(NSString *)publicKeyHex
{
    NSString *account = OCTPushPreviewAccount(publicKeyHex);
    if (account == nil) {
        return nil;
    }
    NSData *key = OCTPushPreviewKeychainRead(kOCTPushPreviewPeerService, account, NULL);
    return key.length == kOCTPushPreviewKeyLength ? key : nil;
}

+ (void)storeGroupPresence:(NSArray<NSString *> *)hashes forFriendPublicKey:(NSString *)publicKeyHex
{
    NSString *account = OCTPushPreviewAccount(publicKeyHex);
    if (account == nil) {
        return;
    }
    NSString *defaultsKey = [kOCTGroupPresenceDefaultsPrefix stringByAppendingString:account];
    NSUserDefaults *defaults = [NSUserDefaults standardUserDefaults];
    if (hashes.count == 0) {
        [defaults removeObjectForKey:defaultsKey];
        return;
    }
    if ([[defaults arrayForKey:defaultsKey] isEqualToArray:hashes]) {
        return;
    }
    [defaults setObject:hashes forKey:defaultsKey];
}

+ (BOOL)friendPublicKey:(NSString *)publicKeyHex hasGroupPresenceHash:(NSString *)hash
{
    NSString *account = OCTPushPreviewAccount(publicKeyHex);
    if (account == nil || hash.length != kOCTPushPreviewPresenceHashLength * 2) {
        return NO;
    }
    NSString *defaultsKey = [kOCTGroupPresenceDefaultsPrefix stringByAppendingString:account];
    return [[[NSUserDefaults standardUserDefaults] arrayForKey:defaultsKey] containsObject:hash.lowercaseString];
}

+ (void)forgetFriendPublicKey:(NSString *)publicKeyHex
{
    NSString *account = OCTPushPreviewAccount(publicKeyHex);
    if (account == nil) {
        return;
    }
    OCTPushPreviewKeychainDelete(kOCTPushPreviewPeerService, account);
    [[NSUserDefaults standardUserDefaults] removeObjectForKey:[kOCTGroupPresenceDefaultsPrefix stringByAppendingString:account]];

    // Removed or blocked: whatever they still send is not shown.
    NSData *publicKey = OCTPushPreviewDataFromHex(publicKeyHex, kOCTPushPreviewPublicKeyLength);
    @synchronized(self) {
        NSData *held = OCTPushPreviewAllowedRead();
        if (held == nil || ! OCTPushPreviewAllowedContains(held, publicKey)) {
            return;
        }
        NSMutableData *kept = [NSMutableData dataWithCapacity:held.length];
        const uint8_t *bytes = held.bytes;
        for (NSUInteger offset = 0; offset + kOCTPushPreviewPublicKeyLength <= held.length; offset += kOCTPushPreviewPublicKeyLength) {
            if (memcmp(bytes + offset, publicKey.bytes, kOCTPushPreviewPublicKeyLength) != 0) {
                [kept appendBytes:bytes + offset length:kOCTPushPreviewPublicKeyLength];
            }
        }
        if (kept.length == 0) {
            OCTPushPreviewKeychainDelete(kOCTPushPreviewKeychainService, kOCTPushPreviewAllowedAccount);
        }
        else {
            OCTPushPreviewKeychainWrite(kOCTPushPreviewKeychainService, kOCTPushPreviewAllowedAccount, kept);
        }
    }
}

#pragma mark - Message text

static NSString * (^sOCTPushPreviewTextSanitizer)(NSString *) = nil;

+ (NSString * (^)(NSString *))previewTextSanitizer
{
    @synchronized(self) {
        return sOCTPushPreviewTextSanitizer;
    }
}

+ (void)setPreviewTextSanitizer:(NSString * (^)(NSString *))previewTextSanitizer
{
    @synchronized(self) {
        sOCTPushPreviewTextSanitizer = [previewTextSanitizer copy];
    }
}

+ (NSString *)previewTextFromMessageText:(NSString *)text
{
    if (! [text isKindOfClass:[NSString class]] || text.length == 0) {
        return @"";
    }
    NSString * (^sanitizer)(NSString *) = [self previewTextSanitizer];
    NSString *read = sanitizer ? sanitizer(text) : text;
    if (! [read isKindOfClass:[NSString class]]) {
        return @"";
    }
    return [read stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
}

#pragma mark - Building a preview

+ (NSString *)kindForFileName:(NSString *)fileName
{
    NSString *lower = fileName.lowercaseString ?: @"";
    if ([lower hasSuffix:@".file.m4a"]) {
        return kOCTPushPreviewKindVoice;
    }
    NSString *extension = lower.pathExtension;
    if ([@[@"mp4", @"mov", @"m4v", @"webm", @"mkv", @"3gp", @"avi"] containsObject:extension]) {
        return kOCTPushPreviewKindVideo;
    }
    if ([@[@"jpg", @"jpeg", @"png", @"gif", @"heic", @"heif", @"webp"] containsObject:extension]) {
        return kOCTPushPreviewKindPhoto;
    }
    return kOCTPushPreviewKindFile;
}

+ (NSString *)prefixOfString:(NSString *)text characters:(NSUInteger)limit
{
    if (! [text isKindOfClass:[NSString class]] || text.length == 0 || limit == 0) {
        return @"";
    }
    __block NSUInteger count = 0;
    __block NSUInteger end = 0;
    [text enumerateSubstringsInRange:NSMakeRange(0, text.length)
                             options:NSStringEnumerationByComposedCharacterSequences | NSStringEnumerationSubstringNotRequired
                          usingBlock:^(NSString *substring, NSRange range, NSRange enclosingRange, BOOL *stop) {
        if (count == limit) {
            *stop = YES;
            return;
        }
        count++;
        end = NSMaxRange(range);
    }];
    return [text substringToIndex:end];
}

+ (NSData *)plaintextWithFields:(NSDictionary<NSString *, id> *)fields
{
    NSString *kind = fields[@"k"];
    if (! [kind isKindOfClass:[NSString class]] || kind.length == 0) {
        return nil;
    }

    NSMutableDictionary<NSString *, id> *current = [fields mutableCopy];
    current[@"v"] = @1;

    NSUInteger bodyLimit = [kind isEqualToString:kOCTPushPreviewKindReaction] ? 80 : 140;
    void (^limitText)(NSString *, NSUInteger) = ^(NSString *name, NSUInteger limit) {
        NSString *value = current[name];
        if ([value isKindOfClass:[NSString class]]) {
            NSString *shortened = [self prefixOfString:value characters:limit];
            if (shortened.length > 0) {
                current[name] = shortened;
            }
            else {
                [current removeObjectForKey:name];
            }
        }
    };
    limitText(@"b", bodyLimit);
    limitText(@"n", 48);
    limitText(@"g", 48);

    NSData *data = OCTPushPreviewSerialize(current);

    // Escapes and four-byte characters can push the longest preview past the limit: the text gives
    // way first, then the names, then the text goes, then the names go.
    if (data.length > kOCTPushPreviewMaxPlaintextBytes) {
        limitText(@"b", 60);
        data = OCTPushPreviewSerialize(current);
    }
    if (data.length > kOCTPushPreviewMaxPlaintextBytes) {
        limitText(@"n", 24);
        limitText(@"g", 24);
        data = OCTPushPreviewSerialize(current);
    }
    if (data.length > kOCTPushPreviewMaxPlaintextBytes) {
        [current removeObjectForKey:@"b"];
        data = OCTPushPreviewSerialize(current);
    }
    if (data.length > kOCTPushPreviewMaxPlaintextBytes) {
        [current removeObjectForKey:@"n"];
        [current removeObjectForKey:@"g"];
        data = OCTPushPreviewSerialize(current);
    }
    return data.length <= kOCTPushPreviewMaxPlaintextBytes ? data : nil;
}

+ (NSString *)sealPlaintext:(NSData *)plaintext key:(NSData *)key nonce:(NSData *)nonce
{
    if (key.length != kOCTPushPreviewKeyLength || plaintext.length == 0
        || plaintext.length > kOCTPushPreviewMaxPlaintextBytes
        || (nonce != nil && nonce.length != kOCTPushPreviewNonceLength)) {
        return nil;
    }

    uint8_t nonceBytes[kOCTPushPreviewNonceLength];
    if (nonce != nil) {
        memcpy(nonceBytes, nonce.bytes, sizeof(nonceBytes));
    }
    else if (SecRandomCopyBytes(kSecRandomDefault, sizeof(nonceBytes), nonceBytes) != errSecSuccess) {
        return nil;
    }

    NSMutableData *sealed = [NSMutableData dataWithLength:1 + kOCTPushPreviewNonceLength + plaintext.length + kOCTPushPreviewTagLength];
    uint8_t *out = sealed.mutableBytes;
    out[0] = kOCTPushPreviewSealVersion;
    memcpy(out + 1, nonceBytes, sizeof(nonceBytes));

    unsigned long long cipherLength = 0;
    int result = crypto_aead_chacha20poly1305_ietf_encrypt(out + 1 + kOCTPushPreviewNonceLength, &cipherLength,
                                                           plaintext.bytes, plaintext.length,
                                                           (const unsigned char *)kOCTPushPreviewAssociatedData,
                                                           strlen(kOCTPushPreviewAssociatedData),
                                                           NULL, nonceBytes, key.bytes);
    if (result != 0 || cipherLength != plaintext.length + kOCTPushPreviewTagLength) {
        return nil;
    }

    NSString *encoded = [sealed base64EncodedStringWithOptions:0];
    encoded = [encoded stringByReplacingOccurrencesOfString:@"+" withString:@"-"];
    encoded = [encoded stringByReplacingOccurrencesOfString:@"/" withString:@"_"];
    encoded = [encoded stringByReplacingOccurrencesOfString:@"=" withString:@""];
    return encoded.length <= kOCTPushPreviewMaxSealedCharacters ? encoded : nil;
}

+ (NSString *)pidForEventKey:(NSString *)eventKey key:(NSData *)key
{
    uint8_t pid[kOCTPushPreviewPidLength];

    if (eventKey.length > 0 && key.length == kOCTPushPreviewKeyLength) {
        NSMutableData *message = [NSMutableData dataWithBytes:kOCTPushPreviewEventLabel length:strlen(kOCTPushPreviewEventLabel)];
        [message appendData:[eventKey dataUsingEncoding:NSUTF8StringEncoding]];
        uint8_t digest[CC_SHA256_DIGEST_LENGTH];
        CCHmac(kCCHmacAlgSHA256, key.bytes, key.length, message.bytes, message.length, digest);
        memcpy(pid, digest, sizeof(pid));
    }
    else if (SecRandomCopyBytes(kSecRandomDefault, sizeof(pid), pid) != errSecSuccess) {
        arc4random_buf(pid, sizeof(pid));
    }
    return OCTPushPreviewHex(pid, sizeof(pid));
}

+ (NSDictionary<NSString *, NSString *> *)wakeFieldsForFriendPublicKey:(NSString *)publicKeyHex
                                                                 fields:(NSDictionary<NSString *, id> *)fields
{
    NSData *key = [self peerKeyForFriendPublicKey:publicKeyHex];
    if (key == nil) {
        return nil;
    }
    NSData *plaintext = [self plaintextWithFields:fields];
    NSString *preview = plaintext ? [self sealPlaintext:plaintext key:key nonce:nil] : nil;
    if (preview == nil) {
        return nil;
    }
    NSString *eventKey = [fields[@"h"] isKindOfClass:[NSString class]] ? fields[@"h"] : nil;
    return @{
        kOCTPushPreviewWakeFieldPreview : preview,
        kOCTPushPreviewWakeFieldPid : [self pidForEventKey:eventKey key:key],
    };
}

@end
