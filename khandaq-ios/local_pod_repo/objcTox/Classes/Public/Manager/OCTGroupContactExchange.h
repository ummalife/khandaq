// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/**
 * KHANDAQ (26.11.2026, DESIGN-group-contacts-private-mentions.md): a member of a group becomes a contact only by the
 * two of them exchanging their Tox addresses. toxcore makes a key pair for every group it creates or joins, so the
 * key a group shows for a member is theirs in that group only: it is not the key of their Khandaq ID and no nospam
 * and checksum complete it.
 *
 * The exchange travels in lossless group custom private packets, the same bytes on every client: the magic
 * 66 77 88 11 34 35, the version 01, the kind (61 offer, 62 accept), the sender's own address (key 32, nospam 4,
 * checksum 2) and the nonce of the offer (8, repeated by the accept), 54 bytes in all.
 */
typedef NS_ENUM(uint8_t, OCTGroupContactPacketKind) {
    OCTGroupContactPacketKindOffer = 0x61,
    OCTGroupContactPacketKindAccept = 0x62,
};

/// What came of "Add to contacts" on a member.
typedef NS_ENUM(NSInteger, OCTGroupContactOfferResult) {
    /// The offer went: the member is a contact once they accept it.
    OCTGroupContactOfferResultSent = 0,
    /// The member is not online in the group now, or the group is not connected: nothing went.
    OCTGroupContactOfferResultMemberOffline = 1,
    /// The member is one of your contacts already.
    OCTGroupContactOfferResultAlreadyContact = 2,
    /// toxcore refused the packet, or our own address could not be read.
    OCTGroupContactOfferResultFailed = 3,
};

/// 54: every packet of the exchange has exactly this length.
FOUNDATION_EXPORT const NSUInteger kOCTGroupContactPacketLength;

/// How the contact request of a member who accepted starts: `[KQ|c|`, the nonce of the offer in 16 lower-case hex
/// characters, `]` and a line break.
FOUNDATION_EXPORT NSString *const kOCTGroupContactRequestMarkerPrefix;

/**
 * Posted when a member we offered became a contact on this side, from their accept or from their contact request.
 * userInfo: kOCTGroupContactAddedNameKey, their name in the group, and kOCTGroupContactAddedPublicKeyKey, the key of
 * the contact (64 hex, upper case).
 */
FOUNDATION_EXPORT NSNotificationName const kOCTGroupContactAddedNotification;
FOUNDATION_EXPORT NSString *const kOCTGroupContactAddedNameKey;
FOUNDATION_EXPORT NSString *const kOCTGroupContactAddedPublicKeyKey;

/// A packet of the exchange that held: its length, its header and the checksum of its address.
@interface OCTGroupContactPacket : NSObject

@property (nonatomic, assign, readonly) OCTGroupContactPacketKind kind;
/// The sender's own Tox address: 76 hex characters, upper case.
@property (nonatomic, copy, readonly) NSString *addressHex;
/// The key of that address, its first 64 characters: the key of the contact.
@property (nonatomic, copy, readonly) NSString *publicKeyHex;
/// The nonce of the offer: 16 hex characters, lower case.
@property (nonatomic, copy, readonly) NSString *nonceHex;

- (instancetype)initWithKind:(OCTGroupContactPacketKind)kind
                  addressHex:(NSString *)addressHex
                    nonceHex:(NSString *)nonceHex NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

/// The group a contact request came through, when a member asked in a group rather than with an ID.
@interface OCTGroupContactOffer : NSObject

/// The member's name in the group when they asked.
@property (nonatomic, copy, readonly) NSString *memberName;
@property (nonatomic, copy, readonly) NSString *groupName;
/// The group's chat id: 64 hex characters, lower case.
@property (nonatomic, copy, readonly) NSString *groupChatIdHex;
/// The member's key in that group: 64 hex characters, lower case.
@property (nonatomic, copy, readonly) NSString *memberPublicKeyHex;

- (instancetype)initWithMemberName:(NSString *)memberName
                         groupName:(NSString *)groupName
                    groupChatIdHex:(NSString *)groupChatIdHex
                memberPublicKeyHex:(NSString *)memberPublicKeyHex NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

/// The bytes and the words of the exchange, without toxcore and without storage.
@interface OCTGroupContactExchange : NSObject

/// The packet of `kind` carrying `addressHex` (76 hex) and `nonceHex` (16 hex); nil when the address is not 76 hex
/// characters whose checksum holds, or the nonce is not 16 hex characters.
+ (nullable NSData *)packetWithKind:(OCTGroupContactPacketKind)kind
                         addressHex:(NSString *)addressHex
                           nonceHex:(NSString *)nonceHex;

/// YES for any packet with the header of the exchange (magic, version, 61 or 62), whatever its length: the
/// dispatcher hands it to the exchange alone, which drops the ones that do not hold.
+ (BOOL)isContactPacket:(NSData *)data;

/// The packet, or nil when its length is not 54, its header is not the exchange's, or the checksum of its address
/// does not hold.
+ (nullable OCTGroupContactPacket *)parsePacket:(NSData *)data;

/// YES for 76 hex characters, in either case, whose last two bytes are the checksum of the first 36.
+ (BOOL)isValidAddressHex:(NSString *)addressHex;

/// Eight random bytes in 16 lower-case hex characters.
+ (NSString *)randomNonceHex;

/// The message of the contact request that accepts an offer: the marker with the nonce of the offer, a line break,
/// then `text`.
+ (NSString *)requestMessageWithNonceHex:(NSString *)nonceHex text:(NSString *)text;

/// The nonce (16 hex, lower case) of a request message that starts with the marker, else nil.
+ (nullable NSString *)nonceHexInRequestMessage:(nullable NSString *)message;

/// A request message as a person reads it: without its first line when that line is a marker.
+ (nullable NSString *)requestMessageWithoutMarker:(nullable NSString *)message;

@end

NS_ASSUME_NONNULL_END
