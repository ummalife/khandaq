// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTObject.h"

@class OCTFriend;
@class OCTChat;
@class OCTMessageText;
@class OCTMessageFile;
@class OCTMessageCall;

/**
 * An abstract message that represents one chunk of chat history.
 *
 * Please note that all properties of this object are readonly.
 * You can change some of them only with appropriate method in OCTSubmanagerObjects.
 */
@interface OCTMessageAbstract : OCTObject

/**
 * The date interval when message was send/received.
 */
@property NSTimeInterval dateInterval;

/**
 * KHANDAQ (#H5): the timestamp the chat timeline SORTS by. Unlike `dateInterval` (local arrival/insert
 * time), this is the message's true chronological position: for 1:1 it's MIN(tssent, dateInterval) so a
 * late/offline-flushed message lands in its real slot (matching its displayed time) and the order is
 * identical across devices; for group live it equals dateInterval; for group history-sync it equals the
 * clamped original stamp. 0 = legacy/unset → callers fall back to dateInterval.
 */
@property NSTimeInterval sortTimestamp;

/**
 * Unixtimestamp when messageV3 was sent or 0.
 */
@property NSTimeInterval tssent;

/**
 * Unixtimestamp when messageV3 was received or 0.
 */
@property NSTimeInterval tsrcvd;

/**
 * Unique identifier of friend that have send message.
 * If the message if outgoing senderUniqueIdentifier is nil.
 */
@property (nullable) NSString *senderUniqueIdentifier;

/**
 * NGC sender peer id. Zero for outgoing group messages; non-zero for incoming group messages.
 */
@property int32_t groupSenderPeerId;

/**
 * YES for messages inserted via NGC history sync (suppresses notification noise).
 */
@property BOOL groupHistorySync;

/**
 * YES for local system lines (join/leave/create) shown centered in the group timeline.
 */
@property BOOL groupSystemMessage;

/**
 * YES for outgoing group text queued while disconnected; flushed when the group connects.
 */
@property BOOL groupPendingSend;

/**
 * YES for NGC private messages between peers (hidden from main group timeline).
 */
@property BOOL groupPrivateMessage;

/**
 * Counterparty peer id for private group messages.
 */
@property int32_t groupPrivatePeerId;

/**
 * KHANDAQ (#55): stable LOWERCASE pubkey hex of the counterparty (the OTHER peer in a private
 * thread). The thread/identity is keyed by this so it survives volatile NGC peer-id reuse. nil for
 * legacy rows or when unresolved (group offline) — callers then fall back to groupPrivatePeerId.
 *
 * KHANDAQ (audit, round 5): for an INCOMING group row (groupSenderPeerId != 0) this is the frozen
 * pubkey of the message's AUTHOR — which for an incoming private message is exactly the counterparty
 * above, so the meaning is unchanged there, and it now carries the same identity for a public group
 * TEXT row (there is no other stable author key on OCTMessageText; groupPeerName is a nickname the
 * author can change and anyone can copy). The 0x42 retract / 0x41 edit gates authenticate against it.
 * Outgoing rows keep the old meaning (the recipient) and are never matched by those gates.
 */
@property (nullable) NSString *groupPrivatePeerPubkey;

/**
 * KHANDAQ (#60): the message's STABLE original timestamp, used ONLY for cross-path duplicate
 * detection — kept independent of `dateInterval`, which is clamped to "not in the future" for
 * display/unread so a clock-skewed sender's future-dated synced message can't keep a read chat
 * marked unread. 0 = unset (legacy rows / live messages fall back to dateInterval for dedup).
 */
@property NSTimeInterval groupSyncDedupTimestamp;

/**
 * The chat message message belongs to.
 */
@property (nonnull) NSString *chatUniqueIdentifier;

/**
 * KHANDAQ: YES for a text message that is the caption of the immediately-preceding file message.
 * Local-only display hint (Telegram-style merged media+caption); not transmitted over Tox.
 */
@property BOOL isCaption;

/**
 * KHANDAQ (#192): message reactions as a JSON array [{"e":"<emoji>","p":["-OWN-","<PUBKEY>",...]},...].
 * One reaction per actor; own reactions stored as the "-OWN-" marker. nil = no reactions.
 */
@property (nullable) NSString *reactionsJSON;

/**
 * KHANDAQ (#192): own reaction state not yet delivered to the peer (reconnect flush rebuilds the
 * packet from the CURRENT state, so repeated toggles collapse into one delivery).
 */
@property BOOL reactionsPending;

/**
 * KHANDAQ (#208): message-edit state. `edited` flags an edited message (renders an «изменено»
 * marker); `editedTimestamp` is the last-write-wins clock (u32 seconds, byte-parity with Android's
 * 186/0x41 edit packet); `editPending` marks an own edit not yet delivered (reconnect flush).
 */
@property BOOL edited;
@property NSTimeInterval editedTimestamp;
@property BOOL editPending;

/**
 * Message has one of the following properties.
 */
@property (nullable) OCTMessageText *messageText;
@property (nullable) OCTMessageFile *messageFile;
@property (nullable) OCTMessageCall *messageCall;

/**
 * The date when message was send/received.
 */
- (nonnull NSDate *)date;

/**
 * Indicates if message is outgoing or incoming.
 * In case if it is incoming you can check `sender` property for message sender.
 */
- (BOOL)isOutgoing;

@end

RLM_ARRAY_TYPE(OCTMessageAbstract)
