// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <Foundation/Foundation.h>

#import "OCTToxConstants.h"

@class OCTFriendRequest;
@class OCTFriend;

@protocol OCTSubmanagerFriends <NSObject>

/**
 * Send friend request to given address. Automatically adds friend with this address to friend list.
 *
 * @param address Address of a friend. If required.
 * @param message Message to send with friend request. Is required.
 * @param error If an error occurs, this pointer is set to an actual error object containing the error information.
 * See OCTToxErrorFriendAdd for all error codes.
 *
 * @return YES on success, NO on failure.
 */
- (BOOL)sendFriendRequestToAddress:(NSString *)address message:(NSString *)message error:(NSError **)error;

/**
 * Add a friend by their 64-hex public key alone, without sending a classic friend request.
 *
 * KHANDAQ (#15): NGC group peers only expose their long-term public key (64 hex), never a full
 * Tox ID (76 hex, which carries nospam + checksum). A classic friend request therefore cannot be
 * sent to a group peer. This wraps tox_friend_add_norequest: it adds the peer locally so a
 * connection can form once both sides have each other; no "wants to add you" prompt is delivered.
 *
 * @param publicKey 64-hex long-term public key of the peer.
 * @param error If an error occurs, this pointer is set to an actual error object.
 *
 * @return YES on success, NO on failure (e.g. already a friend or malformed key).
 */
- (BOOL)addFriendByPublicKey:(NSString *)publicKey error:(NSError **)error;

/**
 * Approve given friend request. After approving new friend will be added and friendRequest will be removed.
 *
 * @param friendRequest Friend request to approve.
 * @param error If an error occurs, this pointer is set to an actual error object containing the error information.
 * See OCTToxErrorFriendAdd for all error codes.
 *
 * @return YES on success, NO on failure.
 */
- (BOOL)approveFriendRequest:(OCTFriendRequest *)friendRequest error:(NSError **)error;

/**
 * Remove friend request from list. This cannot be undone.
 *
 * @param friendRequest Friend request to remove.
 */
- (void)removeFriendRequest:(OCTFriendRequest *)friendRequest;

/**
 * Remove friend from list. This cannot be undone.
 *
 * @param friend Friend to remove.
 * @param error If an error occurs, this pointer is set to an actual error object containing the error information.
 * See OCTToxErrorFriendDelete for all error codes.
 *
 * @return YES on success, NO on failure.
 */
- (BOOL)removeFriend:(OCTFriend *)friend error:(NSError **)error;

/**
 * Re-read connection status from toxcore for all friends (e.g. after returning to foreground).
 */
- (void)refreshConnectionStatuses;

/**
 * Returns an existing friend or creates one from tox state when missing (e.g. incoming message race).
 */
- (nullable OCTFriend *)ensureFriendForFriendNumber:(OCTToxFriendNumber)friendNumber;

@end
