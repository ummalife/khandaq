// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTGroupContactStore.h"
#import "OCTRealmManager.h"
#import "OCTTox.h"

const NSTimeInterval kOCTGroupContactOfferLifetime = 30.0 * 24.0 * 3600.0;

BOOL OCTGroupContactSendPacket(OCTTox *tox, OCTToxGroupNumber groupNumber, uint32_t peerId, OCTGroupContactPacketKind kind, NSString *nonceHex)
{
    NSData *packet = [OCTGroupContactExchange packetWithKind:kind addressHex:tox.userAddress ?: @"" nonceHex:nonceHex];
    if (packet == nil) {
        return NO;
    }
    return [tox groupSendCustomPrivatePacket:packet groupNumber:groupNumber peerId:peerId lossless:YES error:nil];
}

NSString *OCTGroupContactLocalizedString(NSString *key, NSString *fallback, NSString *argument)
{
    // The one argument goes in by replacement, not as a format: the string is the app's, the argument a name
    // someone chose.
    NSString *text = [[NSBundle mainBundle] localizedStringForKey:key value:fallback table:nil];
    if ([text rangeOfString:@"%@"].location == NSNotFound) {
        text = fallback;
    }
    return [text stringByReplacingOccurrencesOfString:@"%@" withString:argument ?: @""];
}

// The rows of the key/value table, one namespace each.
static NSString *const kOCTGroupContactOutPrefix = @"kqgcout|";
static NSString *const kOCTGroupContactInPrefix = @"kqgcin|";
static NSString *const kOCTGroupContactLinkPrefix = @"kqgclink|";
static NSString *const kOCTGroupContactNamePrefix = @"kqgcname|";

static NSString *OCTGroupContactString(NSDictionary *fields, NSString *key)
{
    id value = fields[key];
    return [value isKindOfClass:[NSString class]] ? value : @"";
}

static NSTimeInterval OCTGroupContactTime(NSDictionary *fields, NSString *key)
{
    id value = fields[key];
    return [value isKindOfClass:[NSNumber class]] ? [value doubleValue] : 0;
}

@implementation OCTGroupContactPendingOffer

- (instancetype)initWithGroupChatIdHex:(NSString *)groupChatIdHex
                    memberPublicKeyHex:(NSString *)memberPublicKeyHex
                            memberName:(NSString *)memberName
                              nonceHex:(NSString *)nonceHex
                                sentAt:(NSTimeInterval)sentAt
{
    self = [super init];
    if (self) {
        _groupChatIdHex = [groupChatIdHex.lowercaseString copy];
        _memberPublicKeyHex = [memberPublicKeyHex.lowercaseString copy];
        _memberName = [memberName copy];
        _nonceHex = [nonceHex.lowercaseString copy];
        _sentAt = sentAt;
    }
    return self;
}

@end

@implementation OCTGroupContactIncomingOffer

- (instancetype)initWithContactAddressHex:(NSString *)contactAddressHex
                                 nonceHex:(NSString *)nonceHex
                           groupChatIdHex:(NSString *)groupChatIdHex
                                groupName:(NSString *)groupName
                       memberPublicKeyHex:(NSString *)memberPublicKeyHex
                               memberName:(NSString *)memberName
                               receivedAt:(NSTimeInterval)receivedAt
{
    self = [super init];
    if (self) {
        _contactAddressHex = [contactAddressHex.uppercaseString copy];
        _contactPublicKeyHex = [[_contactAddressHex substringToIndex:MIN((NSUInteger)64, _contactAddressHex.length)] copy];
        _nonceHex = [nonceHex.lowercaseString copy];
        _groupChatIdHex = [groupChatIdHex.lowercaseString copy];
        _groupName = [groupName copy];
        _memberPublicKeyHex = [memberPublicKeyHex.lowercaseString copy];
        _memberName = [memberName copy];
        _receivedAt = receivedAt;
    }
    return self;
}

- (OCTGroupContactOffer *)offer
{
    return [[OCTGroupContactOffer alloc] initWithMemberName:self.memberName
                                                  groupName:self.groupName
                                             groupChatIdHex:self.groupChatIdHex
                                         memberPublicKeyHex:self.memberPublicKeyHex];
}

@end

@implementation OCTGroupContactLink

- (instancetype)initWithContactPublicKeyHex:(NSString *)contactPublicKeyHex contactAddressHex:(nullable NSString *)contactAddressHex
{
    self = [super init];
    if (self) {
        _contactPublicKeyHex = [contactPublicKeyHex.uppercaseString copy];
        _contactAddressHex = contactAddressHex.length > 0 ? [contactAddressHex.uppercaseString copy] : nil;
    }
    return self;
}

@end

@interface OCTGroupContactStore ()

@property (nonatomic, strong, readonly) OCTRealmManager *realmManager;

@end

@implementation OCTGroupContactStore

- (instancetype)initWithRealmManager:(OCTRealmManager *)realmManager
{
    self = [super init];
    if (self) {
        _realmManager = realmManager;
    }
    return self;
}

#pragma mark - Rows

+ (NSString *)memberKeyWithPrefix:(NSString *)prefix member:(NSString *)memberPublicKeyHex group:(NSString *)groupChatIdHex
{
    return [NSString stringWithFormat:@"%@%@|%@", prefix, groupChatIdHex.lowercaseString, memberPublicKeyHex.lowercaseString];
}

+ (NSString *)contactKeyWithPrefix:(NSString *)prefix contact:(NSString *)contactPublicKeyHex
{
    return [prefix stringByAppendingString:contactPublicKeyHex.uppercaseString];
}

- (void)write:(NSDictionary *)fields forKey:(NSString *)key
{
    NSData *json = [NSJSONSerialization dataWithJSONObject:fields options:0 error:nil];
    NSString *value = json ? [[NSString alloc] initWithData:json encoding:NSUTF8StringEncoding] : nil;
    if (value.length > 0) {
        [self.realmManager setNgcValue:value forKey:key];
    }
}

+ (nullable NSDictionary *)fieldsFromValue:(nullable NSString *)value
{
    NSData *data = [value dataUsingEncoding:NSUTF8StringEncoding];
    if (data.length == 0) {
        return nil;
    }
    id fields = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    return [fields isKindOfClass:[NSDictionary class]] ? fields : nil;
}

- (nullable NSDictionary *)fieldsForKey:(NSString *)key
{
    return [OCTGroupContactStore fieldsFromValue:[self.realmManager ngcValueForKey:key]];
}

#pragma mark - Offers we sent

+ (nullable OCTGroupContactPendingOffer *)pendingOfferFromFields:(nullable NSDictionary *)fields
{
    if (fields == nil) {
        return nil;
    }
    OCTGroupContactPendingOffer *offer = [[OCTGroupContactPendingOffer alloc] initWithGroupChatIdHex:OCTGroupContactString(fields, @"g")
                                                                                  memberPublicKeyHex:OCTGroupContactString(fields, @"k")
                                                                                          memberName:OCTGroupContactString(fields, @"n")
                                                                                            nonceHex:OCTGroupContactString(fields, @"o")
                                                                                              sentAt:OCTGroupContactTime(fields, @"t")];
    if (offer.groupChatIdHex.length != 64 || offer.memberPublicKeyHex.length != 64 || offer.nonceHex.length != 16) {
        return nil;
    }
    return offer;
}

+ (BOOL)isAlive:(OCTGroupContactPendingOffer *)offer at:(NSTimeInterval)now
{
    return now - offer.sentAt < kOCTGroupContactOfferLifetime;
}

/// The lock of the offers we sent, whichever store reads them (a store is made where it is needed; the rows are one
/// table). objcTox hands both answers to an offer over on the main queue, one after the other; the lock keeps reading
/// an offer and dropping it one step for any caller all the same.
static NSObject *OCTGroupContactPendingOffersLock(void)
{
    static NSObject *lock;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        lock = [NSObject new];
    });
    return lock;
}

- (void)savePendingOffer:(OCTGroupContactPendingOffer *)offer
{
    NSString *key = [OCTGroupContactStore memberKeyWithPrefix:kOCTGroupContactOutPrefix
                                                       member:offer.memberPublicKeyHex
                                                        group:offer.groupChatIdHex];
    @synchronized(OCTGroupContactPendingOffersLock()) {
        [self write:@{
            @"g": offer.groupChatIdHex,
            @"k": offer.memberPublicKeyHex,
            @"n": offer.memberName ?: @"",
            @"o": offer.nonceHex,
            @"t": @(offer.sentAt),
        } forKey:key];
    }
}

- (nullable OCTGroupContactPendingOffer *)takePendingOfferToMember:(NSString *)memberPublicKeyHex
                                                           inGroup:(NSString *)groupChatIdHex
                                                          nonceHex:(NSString *)nonceHex
                                                                at:(NSTimeInterval)now
{
    NSString *member = memberPublicKeyHex.lowercaseString;
    NSString *group = groupChatIdHex.lowercaseString;
    NSString *key = [OCTGroupContactStore memberKeyWithPrefix:kOCTGroupContactOutPrefix member:member group:group];
    @synchronized(OCTGroupContactPendingOffersLock()) {
        OCTGroupContactPendingOffer *offer = [OCTGroupContactStore pendingOfferFromFields:[self fieldsForKey:key]];
        if (offer == nil) {
            return nil;
        }
        if (! [OCTGroupContactStore isAlive:offer at:now]) {
            [self.realmManager removeNgcValueForKey:key];
            return nil;
        }
        if (! [offer.memberPublicKeyHex isEqualToString:member]
            || ! [offer.groupChatIdHex isEqualToString:group]
            || ! [offer.nonceHex isEqualToString:nonceHex.lowercaseString]) {
            return nil;
        }
        [self.realmManager removeNgcValueForKey:key];
        return offer;
    }
}

- (nullable OCTGroupContactPendingOffer *)takePendingOfferWithNonceHex:(NSString *)nonceHex at:(NSTimeInterval)now
{
    NSString *wanted = nonceHex.lowercaseString;
    if (wanted.length != 16) {
        return nil;
    }
    @synchronized(OCTGroupContactPendingOffersLock()) {
        NSDictionary<NSString *, NSString *> *rows = [self.realmManager ngcValuesWithKeyPrefix:kOCTGroupContactOutPrefix];
        for (NSString *key in rows) {
            OCTGroupContactPendingOffer *offer = [OCTGroupContactStore pendingOfferFromFields:[OCTGroupContactStore fieldsFromValue:rows[key]]];
            if (offer == nil || ! [offer.nonceHex isEqualToString:wanted]) {
                continue;
            }
            [self.realmManager removeNgcValueForKey:key];
            return [OCTGroupContactStore isAlive:offer at:now] ? offer : nil;
        }
        return nil;
    }
}

- (void)removePendingOfferToMember:(NSString *)memberPublicKeyHex inGroup:(NSString *)groupChatIdHex
{
    @synchronized(OCTGroupContactPendingOffersLock()) {
        [self.realmManager removeNgcValueForKey:[OCTGroupContactStore memberKeyWithPrefix:kOCTGroupContactOutPrefix
                                                                                   member:memberPublicKeyHex
                                                                                    group:groupChatIdHex]];
    }
}

#pragma mark - Offers we received

- (void)saveIncomingOffer:(OCTGroupContactIncomingOffer *)offer
{
    [self write:@{
        @"a": offer.contactAddressHex,
        @"o": offer.nonceHex,
        @"g": offer.groupChatIdHex,
        @"gn": offer.groupName ?: @"",
        @"k": offer.memberPublicKeyHex,
        @"n": offer.memberName ?: @"",
        @"t": @(offer.receivedAt),
    } forKey:[OCTGroupContactStore contactKeyWithPrefix:kOCTGroupContactInPrefix contact:offer.contactPublicKeyHex]];
}

- (nullable OCTGroupContactIncomingOffer *)incomingOfferFromContact:(NSString *)contactPublicKeyHex
{
    if (contactPublicKeyHex.length == 0) {
        return nil;
    }
    NSDictionary *fields = [self fieldsForKey:[OCTGroupContactStore contactKeyWithPrefix:kOCTGroupContactInPrefix contact:contactPublicKeyHex]];
    if (fields == nil) {
        return nil;
    }
    OCTGroupContactIncomingOffer *offer = [[OCTGroupContactIncomingOffer alloc] initWithContactAddressHex:OCTGroupContactString(fields, @"a")
                                                                                                 nonceHex:OCTGroupContactString(fields, @"o")
                                                                                           groupChatIdHex:OCTGroupContactString(fields, @"g")
                                                                                                groupName:OCTGroupContactString(fields, @"gn")
                                                                                       memberPublicKeyHex:OCTGroupContactString(fields, @"k")
                                                                                               memberName:OCTGroupContactString(fields, @"n")
                                                                                               receivedAt:OCTGroupContactTime(fields, @"t")];
    if (! [OCTGroupContactExchange isValidAddressHex:offer.contactAddressHex]
        || ! [offer.contactPublicKeyHex isEqualToString:contactPublicKeyHex.uppercaseString]
        || offer.nonceHex.length != 16) {
        return nil;
    }
    return offer;
}

- (void)removeIncomingOfferFromContact:(NSString *)contactPublicKeyHex
{
    if (contactPublicKeyHex.length == 0) {
        return;
    }
    [self.realmManager removeNgcValueForKey:[OCTGroupContactStore contactKeyWithPrefix:kOCTGroupContactInPrefix contact:contactPublicKeyHex]];
}

#pragma mark - Members known as contacts

- (void)saveLink:(OCTGroupContactLink *)link forMember:(NSString *)memberPublicKeyHex inGroup:(NSString *)groupChatIdHex
{
    if (link.contactPublicKeyHex.length != 64 || memberPublicKeyHex.length != 64 || groupChatIdHex.length != 64) {
        return;
    }
    [self write:@{
        @"c": link.contactPublicKeyHex,
        @"a": link.contactAddressHex ?: @"",
    } forKey:[OCTGroupContactStore memberKeyWithPrefix:kOCTGroupContactLinkPrefix member:memberPublicKeyHex group:groupChatIdHex]];
}

- (nullable OCTGroupContactLink *)linkForMember:(NSString *)memberPublicKeyHex inGroup:(NSString *)groupChatIdHex
{
    if (memberPublicKeyHex.length != 64 || groupChatIdHex.length != 64) {
        return nil;
    }
    NSDictionary *fields = [self fieldsForKey:[OCTGroupContactStore memberKeyWithPrefix:kOCTGroupContactLinkPrefix
                                                                                 member:memberPublicKeyHex
                                                                                  group:groupChatIdHex]];
    NSString *contact = OCTGroupContactString(fields, @"c");
    if (contact.length != 64) {
        return nil;
    }
    NSString *address = OCTGroupContactString(fields, @"a");
    BOOL addressHolds = [OCTGroupContactExchange isValidAddressHex:address]
        && [[address substringToIndex:64] caseInsensitiveCompare:contact] == NSOrderedSame;
    return [[OCTGroupContactLink alloc] initWithContactPublicKeyHex:contact contactAddressHex:addressHolds ? address : nil];
}

- (void)saveMemberName:(NSString *)memberName forContact:(NSString *)contactPublicKeyHex
{
    if (memberName.length == 0 || contactPublicKeyHex.length != 64) {
        return;
    }
    [self.realmManager setNgcValue:memberName forKey:[OCTGroupContactStore contactKeyWithPrefix:kOCTGroupContactNamePrefix contact:contactPublicKeyHex]];
}

- (nullable NSString *)memberNameForContact:(NSString *)contactPublicKeyHex
{
    if (contactPublicKeyHex.length != 64) {
        return nil;
    }
    NSString *name = [self.realmManager ngcValueForKey:[OCTGroupContactStore contactKeyWithPrefix:kOCTGroupContactNamePrefix contact:contactPublicKeyHex]];
    return name.length > 0 ? name : nil;
}

@end
