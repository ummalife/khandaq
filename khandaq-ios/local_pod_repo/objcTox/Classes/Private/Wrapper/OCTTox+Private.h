// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTTox.h"
#import <toxcore/tox.h>

/**
 * Tox functions
 */
extern void (*_tox_self_get_public_key)(const Tox *tox, uint8_t *public_key);

/**
 * Callbacks
 */
tox_log_cb logCallback;
tox_self_connection_status_cb connectionStatusCallback;
tox_friend_name_cb friendNameCallback;
tox_friend_status_message_cb friendStatusMessageCallback;
tox_friend_status_cb friendStatusCallback;
tox_friend_connection_status_cb friendConnectionStatusCallback;
tox_friend_typing_cb friendTypingCallback;
tox_friend_read_receipt_cb friendReadReceiptCallback;
tox_friend_request_cb friendRequestCallback;
tox_friend_message_cb friendMessageCallback;
tox_friend_lossless_packet_cb friendLosslessPacketCallback;
tox_file_recv_control_cb fileReceiveControlCallback;
tox_file_chunk_request_cb fileChunkRequestCallback;
tox_file_recv_cb fileReceiveCallback;
tox_file_recv_chunk_cb fileReceiveChunkCallback;
tox_group_message_cb groupMessageCallback;
tox_group_connection_status_cb groupConnectionStatusCallback;
tox_group_peer_join_cb groupPeerJoinCallback;
tox_group_peer_name_cb groupPeerNameCallback;
tox_group_invite_cb groupInviteCallback;
tox_group_peer_exit_cb groupPeerExitCallback;
tox_group_custom_packet_cb groupCustomPacketCallback;
tox_group_custom_private_packet_cb groupCustomPrivatePacketCallback;
tox_group_moderation_cb groupModerationCallback;
tox_group_topic_cb groupTopicCallback;
tox_group_password_cb groupPasswordCallback;
tox_group_topic_lock_cb groupTopicLockCallback;
tox_group_peer_limit_cb groupPeerLimitCallback;
tox_group_privacy_state_cb groupPrivacyStateCallback;
tox_group_voice_state_cb groupVoiceStateCallback;
tox_group_join_fail_cb groupJoinFailCallback;
tox_group_private_message_cb groupPrivateMessageCallback;

@interface OCTTox (Private)

@property (assign, nonatomic) Tox *tox;

- (OCTToxUserStatus)userStatusFromCUserStatus:(TOX_USER_STATUS)cStatus;
- (OCTToxConnectionStatus)userConnectionStatusFromCUserStatus:(TOX_CONNECTION)cStatus;
- (OCTToxMessageType)messageTypeFromCMessageType:(TOX_MESSAGE_TYPE)cType;
- (OCTToxFileControl)fileControlFromCFileControl:(TOX_FILE_CONTROL)cControl;
- (OCTToxGroupPrivacyState)groupPrivacyStateFromCPrivacyState:(Tox_Group_Privacy_State)cState;
- (OCTToxGroupVoiceState)groupVoiceStateFromCVoiceState:(Tox_Group_Voice_State)cState;
- (OCTToxGroupJoinFail)groupJoinFailFromCFail:(Tox_Group_Join_Fail)cFail;
- (OCTToxGroupExitType)groupExitTypeFromCExitType:(Tox_Group_Exit_Type)cType;
- (OCTToxGroupRole)groupRoleFromCRole:(Tox_Group_Role)cRole;
- (Tox_Group_Role)groupRoleToCRole:(OCTToxGroupRole)role;
- (OCTToxGroupModEvent)groupModEventFromCEvent:(Tox_Group_Mod_Event)cEvent;
- (Tox_Group_Privacy_State)cPrivacyStateFromGroupPrivacyState:(OCTToxGroupPrivacyState)privacyState;
- (Tox_Group_Voice_State)cVoiceStateFromGroupVoiceState:(OCTToxGroupVoiceState)voiceState;
- (Tox_Message_Type)cMessageTypeFromMessageType:(OCTToxMessageType)type;
- (BOOL)fillError:(NSError **)error withCErrorInit:(TOX_ERR_NEW)cError;
- (BOOL)fillError:(NSError **)error withCErrorBootstrap:(TOX_ERR_BOOTSTRAP)cError;
- (BOOL)fillError:(NSError **)error withCErrorFriendAdd:(TOX_ERR_FRIEND_ADD)cError;
- (BOOL)fillError:(NSError **)error withCErrorFriendDelete:(TOX_ERR_FRIEND_DELETE)cError;
- (BOOL)fillError:(NSError **)error withCErrorFriendByPublicKey:(TOX_ERR_FRIEND_BY_PUBLIC_KEY)cError;
- (BOOL)fillError:(NSError **)error withCErrorFriendGetPublicKey:(TOX_ERR_FRIEND_GET_PUBLIC_KEY)cError;
- (BOOL)fillError:(NSError **)error withCErrorSetInfo:(TOX_ERR_SET_INFO)cError;
- (BOOL)fillError:(NSError **)error withCErrorFriendGetLastOnline:(TOX_ERR_FRIEND_GET_LAST_ONLINE)cError;
- (BOOL)fillError:(NSError **)error withCErrorFriendQuery:(TOX_ERR_FRIEND_QUERY)cError;
- (BOOL)fillError:(NSError **)error withCErrorSetTyping:(TOX_ERR_SET_TYPING)cError;
- (BOOL)fillError:(NSError **)error withCErrorFriendSendMessage:(TOX_ERR_FRIEND_SEND_MESSAGE)cError;
- (BOOL)fillError:(NSError **)error withCErrorFileControl:(TOX_ERR_FILE_CONTROL)cError;
- (BOOL)fillError:(NSError **)error withCErrorFileSeek:(TOX_ERR_FILE_SEEK)cError;
- (BOOL)fillError:(NSError **)error withCErrorFileGet:(TOX_ERR_FILE_GET)cError;
- (BOOL)fillError:(NSError **)error withCErrorFileSend:(TOX_ERR_FILE_SEND)cError;
- (BOOL)fillError:(NSError **)error withCErrorFileSendChunk:(TOX_ERR_FILE_SEND_CHUNK)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupNew:(Tox_Err_Group_New)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupJoin:(Tox_Err_Group_Join)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupInviteAccept:(Tox_Err_Group_Invite_Accept)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupInviteFriend:(Tox_Err_Group_Invite_Friend)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupLeave:(Tox_Err_Group_Leave)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupSendMessage:(Tox_Err_Group_Send_Message)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupSendCustomPacket:(Tox_Err_Group_Send_Custom_Packet)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupSendCustomPrivatePacket:(Tox_Err_Group_Send_Custom_Private_Packet)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupModKickPeer:(Tox_Err_Group_Mod_Kick_Peer)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupFounderSetPeerLimit:(Tox_Err_Group_Founder_Set_Peer_Limit)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupFounderSetVoiceState:(Tox_Err_Group_Founder_Set_Voice_State)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupFounderSetPrivacyState:(Tox_Err_Group_Founder_Set_Privacy_State)cError;
- (BOOL)fillError:(NSError **)error withCErrorGroupStateQueries:(Tox_Err_Group_State_Queries)cError;
+ (NSError *)createErrorWithCode:(NSUInteger)code
                     description:(NSString *)description
                   failureReason:(NSString *)failureReason;
- (struct Tox_Options) cToxOptionsFromOptions:(OCTToxOptions *)options;
+ (NSString *)binToHexString:(uint8_t *)bin length:(NSUInteger)length;
+ (uint8_t *)hexStringToBin:(NSString *)string;

- (BOOL)sendLosslessPacketWithFriendNumber:(OCTToxFriendNumber)friendNumber
                                      bytes:(NSData *)bytes
                                      error:(NSError **)error;

@end
