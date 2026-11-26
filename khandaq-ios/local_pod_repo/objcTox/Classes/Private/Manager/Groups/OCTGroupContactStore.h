// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTGroupContactExchange.h"
#import "OCTToxConstants.h"

@class OCTRealmManager;
@class OCTTox;

NS_ASSUME_NONNULL_BEGIN

/// How long an offer we sent waits for its answer: 30 days.
FOUNDATION_EXPORT const NSTimeInterval kOCTGroupContactOfferLifetime;

/// Sends the packet of `kind` with our own address and `nonceHex` to that member, lossless over the group's private
/// channel. NO when our address does not hold or toxcore refused the packet.
FOUNDATION_EXPORT BOOL OCTGroupContactSendPacket(OCTTox *tox,
                                                 OCTToxGroupNumber groupNumber,
                                                 uint32_t peerId,
                                                 OCTGroupContactPacketKind kind,
                                                 NSString *nonceHex);

/// The app's string for `key` with `argument` in place of its one %@, or `fallback` so filled when the app has none.
FOUNDATION_EXPORT NSString *OCTGroupContactLocalizedString(NSString *key, NSString *fallback, NSString *argument);

/// An offer we sent to a member: the member, their name in the group, the nonce and when it went.
@interface OCTGroupContactPendingOffer : NSObject

/// 64 hex characters, lower case.
@property (nonatomic, copy, readonly) NSString *groupChatIdHex;
/// The member's key in the group: 64 hex characters, lower case.
@property (nonatomic, copy, readonly) NSString *memberPublicKeyHex;
@property (nonatomic, copy, readonly) NSString *memberName;
/// 16 hex characters, lower case.
@property (nonatomic, copy, readonly) NSString *nonceHex;
@property (nonatomic, assign, readonly) NSTimeInterval sentAt;

- (instancetype)initWithGroupChatIdHex:(NSString *)groupChatIdHex
                    memberPublicKeyHex:(NSString *)memberPublicKeyHex
                            memberName:(NSString *)memberName
                              nonceHex:(NSString *)nonceHex
                                sentAt:(NSTimeInterval)sentAt NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

/// An offer a member sent us, kept while its contact request waits in the list.
@interface OCTGroupContactIncomingOffer : NSObject

/// The member's own Tox address: 76 hex characters, upper case.
@property (nonatomic, copy, readonly) NSString *contactAddressHex;
/// The key of that address, 64 hex characters, upper case: the key of the request.
@property (nonatomic, copy, readonly) NSString *contactPublicKeyHex;
@property (nonatomic, copy, readonly) NSString *nonceHex;
@property (nonatomic, copy, readonly) NSString *groupChatIdHex;
@property (nonatomic, copy, readonly) NSString *groupName;
@property (nonatomic, copy, readonly) NSString *memberPublicKeyHex;
@property (nonatomic, copy, readonly) NSString *memberName;
@property (nonatomic, assign, readonly) NSTimeInterval receivedAt;

- (instancetype)initWithContactAddressHex:(NSString *)contactAddressHex
                                 nonceHex:(NSString *)nonceHex
                           groupChatIdHex:(NSString *)groupChatIdHex
                                groupName:(NSString *)groupName
                       memberPublicKeyHex:(NSString *)memberPublicKeyHex
                               memberName:(NSString *)memberName
                               receivedAt:(NSTimeInterval)receivedAt NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

/// What the request list says about it.
- (OCTGroupContactOffer *)offer;

@end

/// The contact behind a member, once the two of them exchanged their IDs.
@interface OCTGroupContactLink : NSObject

/// 64 hex characters, upper case.
@property (nonatomic, copy, readonly) NSString *contactPublicKeyHex;
/// The whole ID, 76 hex characters, upper case; nil when only the key came (their contact request came before
/// their accept, and the accept never did).
@property (nonatomic, copy, readonly, nullable) NSString *contactAddressHex;

- (instancetype)initWithContactPublicKeyHex:(NSString *)contactPublicKeyHex
                          contactAddressHex:(nullable NSString *)contactAddressHex NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

/**
 * KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): what the member-to-contact exchange keeps on disk,
 * in the profile's key/value table (OCTNgcKeyValue): the offers we sent (one per member of a group, a new one
 * replacing the old, for 30 days), the offers we received (one per contact key, while the request waits), the
 * contact behind each member once known, and the name a contact had in the group, which names them until their own
 * name comes and when it is the default one.
 */
@interface OCTGroupContactStore : NSObject

- (instancetype)initWithRealmManager:(OCTRealmManager *)realmManager NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

#pragma mark - Offers we sent

// An offer is answered twice, by the accept over the group and by the contact request with its nonce, and the two can
// come together. Reading an offer and dropping it is one step under one lock (every store of the process shares it):
// the answer that takes the offer adds the contact, the other finds it gone and adds nothing.

- (void)savePendingOffer:(OCTGroupContactPendingOffer *)offer;
/// Takes the offer to that member of that group when its nonce is `nonceHex`: their answer, there, to that very offer.
/// Nil, and the offer left waiting, for another nonce; nil when there is none, or it is 30 days old (it is dropped).
- (nullable OCTGroupContactPendingOffer *)takePendingOfferToMember:(NSString *)memberPublicKeyHex
                                                           inGroup:(NSString *)groupChatIdHex
                                                          nonceHex:(NSString *)nonceHex
                                                                at:(NSTimeInterval)now;
/// Takes the offer with that nonce in any group: nil when there is none still alive, or another answer took it.
- (nullable OCTGroupContactPendingOffer *)takePendingOfferWithNonceHex:(NSString *)nonceHex at:(NSTimeInterval)now;
- (void)removePendingOfferToMember:(NSString *)memberPublicKeyHex inGroup:(NSString *)groupChatIdHex;

#pragma mark - Offers we received

- (void)saveIncomingOffer:(OCTGroupContactIncomingOffer *)offer;
- (nullable OCTGroupContactIncomingOffer *)incomingOfferFromContact:(NSString *)contactPublicKeyHex;
- (void)removeIncomingOfferFromContact:(NSString *)contactPublicKeyHex;

#pragma mark - Members known as contacts

- (void)saveLink:(OCTGroupContactLink *)link forMember:(NSString *)memberPublicKeyHex inGroup:(NSString *)groupChatIdHex;
- (nullable OCTGroupContactLink *)linkForMember:(NSString *)memberPublicKeyHex inGroup:(NSString *)groupChatIdHex;

/// The name a contact had in the group they were added from.
- (void)saveMemberName:(NSString *)memberName forContact:(NSString *)contactPublicKeyHex;
- (nullable NSString *)memberNameForContact:(NSString *)contactPublicKeyHex;

@end

NS_ASSUME_NONNULL_END
