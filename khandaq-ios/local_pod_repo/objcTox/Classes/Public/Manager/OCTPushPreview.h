// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

@class OCTTox;
@class OCTPushPreviewGroup;

NS_ASSUME_NONNULL_BEGIN

/// Kinds of a preview (`k`) and of a reacted message (`rk`), docs/PUSH_PREVIEWS.md.
FOUNDATION_EXPORT NSString *const kOCTPushPreviewKindText;
FOUNDATION_EXPORT NSString *const kOCTPushPreviewKindVoice;
FOUNDATION_EXPORT NSString *const kOCTPushPreviewKindVideo;
FOUNDATION_EXPORT NSString *const kOCTPushPreviewKindPhoto;
FOUNDATION_EXPORT NSString *const kOCTPushPreviewKindFile;
FOUNDATION_EXPORT NSString *const kOCTPushPreviewKindReaction;

/// Keychain service of this device's own preview items: the master key (account `master`), the allowed
/// senders (account `allow`), and the settings the notification service extension reads (accounts `show`
/// and `lang`).
FOUNDATION_EXPORT NSString *const kOCTPushPreviewKeychainService;

/// The `/wake` body fields a sealed preview travels in.
FOUNDATION_EXPORT NSString *const kOCTPushPreviewWakeFieldPreview;
FOUNDATION_EXPORT NSString *const kOCTPushPreviewWakeFieldPid;

/**
 * KHANDAQ (0.2.64): end-to-end encrypted push previews (docs/PUSH_PREVIEWS.md).
 *
 * A wake push may carry what the event was (the text, or that it is a voice message, a video, a photo,
 * a file or a reaction), sealed on the sender's device with a key only the recipient's device can
 * derive. The relay, Google and Apple carry an opaque string.
 *
 * The recipient half: a random master key `M` per installation, kept in the Keychain where the
 * notification service extension can read it, and the per-contact key `K = HMAC-SHA256(M, label || P)`
 * handed to each contact in lossless packet 190/01. The sender half: the `K` a contact handed us, used
 * to seal a preview for that contact's device.
 */
@interface OCTPushPreview : NSObject

#pragma mark - Our own keys

/// This installation's master key, created on first use. Nil when the Keychain cannot keep it (before
/// the first unlock after a restart, for one): a key the extension could not read must not be published.
+ (nullable NSData *)masterKey;

/// `K` for the contact with this Tox public key (64 hex), derived from `masterKey`.
+ (nullable NSData *)perContactKeyWithMasterKey:(NSData *)masterKey publicKeyHex:(NSString *)publicKeyHex;

/// Packet 190/01 (`BE 01 01 K`) for that contact, or nil without a master key or with a malformed key. The
/// contact goes on the list of allowed senders first; nil when it cannot.
+ (nullable NSData *)keyPacketForFriendPublicKey:(NSString *)publicKeyHex;

/// The contacts this device handed its key to and still has: the only senders whose previews the notification
/// service extension shows. `allowSenderPublicKey:` adds one (NO when the list cannot be written now);
/// `forgetFriendPublicKey:` takes one off.
+ (BOOL)allowSenderPublicKey:(NSString *)publicKeyHex;
+ (BOOL)isAllowedSenderPublicKey:(NSString *)publicKeyHex;

/// A profile was deleted: the master key goes, and with it every key its contacts were given, together with the
/// allowed senders, the keys contacts sent, their group presence and their group wake sets. A new master key is
/// made the next time one is handed out.
+ (void)resetAll;

#pragma mark - Group presence

/// `SHA-256("khandaq group presence v1" || chat_id || peer_pk)[0:16]` as 32 lowercase hex.
+ (nullable NSString *)groupPresenceHashForChatIdHex:(NSString *)chatIdHex peerPublicKeyHex:(NSString *)peerPublicKeyHex;

/// The presence hashes of every group this Tox instance is in and knows its own peer key for.
+ (NSArray<NSString *> *)ownGroupPresenceHashesWithTox:(OCTTox *)tox;

/// Packet 190/02 listing `hashes` (the first 64 well-formed ones).
+ (NSData *)groupPresencePacketWithHashes:(NSArray<NSString *> *)hashes;

#pragma mark - Group message wakes

/// KHANDAQ (0.2.69, group message wakes): packet 190/03 listing `hashes` (the first 64 well-formed ones).
+ (NSData *)groupWakePacketWithHashes:(NSArray<NSString *> *)hashes;

/**
 * KHANDAQ (0.2.69, group message wakes): the 190/03 set for a friend whose stored 190/02 set is
 * `friendPresenceHashes` (docs/PUSH_PREVIEWS.md, "Group message wakes"): the presence hash of each of `groups`, in
 * their order, but a muted group and a group in which a member muted here is that friend (the presence hash of the
 * member's key there is in `friendPresenceHashes`). The first 64.
 */
+ (NSArray<NSString *> *)groupWakeHashesForGroups:(NSArray<OCTPushPreviewGroup *> *)groups
                             friendPresenceHashes:(nullable NSArray<NSString *> *)friendPresenceHashes;

/**
 * KHANDAQ (0.2.69, group message wakes): whom a message of ours to the group with this chat id wakes, as contact
 * public key (the key of `contactWakeSets`) → the member key (lower case) the contact is woken for. A member counts
 * when it is one of `memberKeys`, is not connected (`connectedKeys`), is not us (`ownKey`) and has not left
 * (`leftKeys`); it wakes the one contact whose 190/03 set in `contactWakeSets` holds the presence hash of the group
 * and that key. A hash two contacts hold wakes neither: one of them replayed the other's. A contact is woken for one
 * member at most, the first in `memberKeys`. Whether the contact is away and can be woken is for the caller.
 */
+ (NSDictionary<NSString *, NSString *> *)groupWakeMembersForChatIdHex:(NSString *)chatIdHex
                                                           memberKeys:(NSArray<NSString *> *)memberKeys
                                                        connectedKeys:(NSArray<NSString *> *)connectedKeys
                                                               ownKey:(nullable NSString *)ownKey
                                                             leftKeys:(NSArray<NSString *> *)leftKeys
                                                      contactWakeSets:(NSDictionary<NSString *, NSArray<NSString *> *> *)contactWakeSets;

/// KHANDAQ (0.2.69, group message wakes): the event key of a group text, `gt:` + the message id (8 uppercase hex,
/// big-endian) + `:` + the sender's public key in the group (64 uppercase hex); nil without such a key.
+ (nullable NSString *)groupTextEventKeyWithMessageId:(uint32_t)messageId senderPeerKeyHex:(nullable NSString *)senderPeerKeyHex;

/// KHANDAQ (0.2.69, group message wakes): the event key of a group file, `gf:` + its 32-byte hash (64 uppercase
/// hex); nil without one.
+ (nullable NSString *)groupFileEventKeyWithMsgIdHex:(nullable NSString *)msgIdHex;

/**
 * KHANDAQ (0.2.69, group message wakes): the preview fields of a wake for a message of ours to a group: `fields` (the
 * kind, our name, our clock) with the body `b` and the group's name `g` when there are any, its chat id `gid` and the
 * event key `h`. A text that waits has no event key, since toxcore gives a text its id only when it goes: it carries
 * `gp`, our key in the group (`senderPeerKeyHex`), instead, and the recipient knows it by that key and what it says.
 * Nil for a chat id that is not 64 hex, and without an event key for anything but a text with a 64-hex sender key.
 */
+ (nullable NSDictionary<NSString *, id> *)groupWakeFieldsWithFields:(NSDictionary<NSString *, id> *)fields
                                                                body:(nullable NSString *)body
                                                           groupName:(nullable NSString *)groupName
                                                           chatIdHex:(nullable NSString *)chatIdHex
                                                            eventKey:(nullable NSString *)eventKey
                                                    senderPeerKeyHex:(nullable NSString *)senderPeerKeyHex;

#pragma mark - Incoming packet 190

/// The `K` a contact sent in packet 190/01 (the whole packet, id byte included), or nil.
+ (nullable NSData *)peerKeyFromPacket:(NSData *)packet;

/// The hashes a contact sent in packet 190/02 (the whole packet, id byte included), or nil.
+ (nullable NSArray<NSString *> *)groupPresenceHashesFromPacket:(NSData *)packet;

/// The hashes a contact sent in packet 190/03 (the whole packet, id byte included), or nil.
+ (nullable NSArray<NSString *> *)groupWakeHashesFromPacket:(NSData *)packet;

#pragma mark - What contacts sent us

+ (void)storePeerKey:(NSData *)key forFriendPublicKey:(NSString *)publicKeyHex;
+ (nullable NSData *)peerKeyForFriendPublicKey:(NSString *)publicKeyHex;
+ (void)storeGroupPresence:(NSArray<NSString *> *)hashes forFriendPublicKey:(NSString *)publicKeyHex;
+ (BOOL)friendPublicKey:(NSString *)publicKeyHex hasGroupPresenceHash:(NSString *)hash;
/// The 190/02 set the contact sent last, empty without one.
+ (NSArray<NSString *> *)groupPresenceHashesForFriendPublicKey:(NSString *)publicKeyHex;
/// KHANDAQ (0.2.69, group message wakes): the 190/03 set the contact sent last replaces the one before.
+ (void)storeGroupWake:(NSArray<NSString *> *)hashes forFriendPublicKey:(NSString *)publicKeyHex;
/// The 190/03 set the contact sent last, empty without one: a client before 0.2.69 is never woken for a group
/// message.
+ (NSArray<NSString *> *)groupWakeHashesForFriendPublicKey:(NSString *)publicKeyHex;
/// Drops the key, the group presence and the group wake set of a contact that was removed (or blocked), and takes
/// it off the allowed senders.
+ (void)forgetFriendPublicKey:(NSString *)publicKeyHex;

#pragma mark - Message text

/// The app's reading of a message text for a notification, with reply and mention markup taken out
/// (GroupMentionHelper.notificationPreviewText). Without one the text goes as it is.
@property (class, nonatomic, copy, nullable) NSString * (^previewTextSanitizer)(NSString *text);

/// `text` as the sanitizer reads it, trimmed; empty when nothing is left.
+ (NSString *)previewTextFromMessageText:(nullable NSString *)text;

#pragma mark - Building a preview

/// `voice`, `video`, `photo` or `file`, from a file name.
+ (NSString *)kindForFileName:(nullable NSString *)fileName;

/// The first `limit` user-perceived characters of `text`, never splitting one.
+ (NSString *)prefixOfString:(nullable NSString *)text characters:(NSUInteger)limit;

/// The compact JSON plaintext, fields in the order of the spec, at most 900 bytes (the text is
/// shortened, then the names, then the text dropped, until it fits). Nil without `k`.
+ (nullable NSData *)plaintextWithFields:(NSDictionary<NSString *, id> *)fields;

/// `base64url(0x01 || nonce || ciphertext || tag)`; a random nonce when `nonce` is nil.
+ (nullable NSString *)sealPlaintext:(NSData *)plaintext key:(NSData *)key nonce:(nullable NSData *)nonce;

/// The relay's event id: `hex(HMAC-SHA256(K, "khandaq push event v1" || eventKey))[0:32]`, or 32
/// random hex characters when there is no event key.
+ (NSString *)pidForEventKey:(nullable NSString *)eventKey key:(NSData *)key;

/// `preview` and `pid` for a wake to the contact with this public key, or nil when the contact has not
/// sent us a key (an older client: its wake stays the generic one).
+ (nullable NSDictionary<NSString *, NSString *> *)wakeFieldsForFriendPublicKey:(NSString *)publicKeyHex
                                                                          fields:(NSDictionary<NSString *, id> *)fields;

@end

/**
 * KHANDAQ (0.2.69, group message wakes): one group of this device as the 190/03 set for a friend sees it
 * (+[OCTPushPreview groupWakeHashesForGroups:friendPresenceHashes:]).
 */
@interface OCTPushPreviewGroup : NSObject

@property (nonatomic, copy, readonly) NSString *chatIdHex;
/// This device's own peer key in the group.
@property (nonatomic, copy, readonly) NSString *ownPeerKeyHex;
/// The group is muted on this device.
@property (nonatomic, readonly) BOOL muted;
/// The keys of the members muted on this device in the group.
@property (nonatomic, copy, readonly) NSArray<NSString *> *mutedMemberKeyHexes;

- (instancetype)initWithChatIdHex:(NSString *)chatIdHex
                    ownPeerKeyHex:(NSString *)ownPeerKeyHex
                            muted:(BOOL)muted
              mutedMemberKeyHexes:(NSArray<NSString *> *)mutedMemberKeyHexes NS_DESIGNATED_INITIALIZER;
- (instancetype)init NS_UNAVAILABLE;

@end

NS_ASSUME_NONNULL_END
