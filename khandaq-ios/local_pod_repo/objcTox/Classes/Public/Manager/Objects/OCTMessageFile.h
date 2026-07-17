// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTObject.h"
#import "OCTToxConstants.h"
#import "OCTManagerConstants.h"

/**
 * Message that contains file, that has been send/received. Represents pending, canceled and loaded files.
 *
 * Please note that all properties of this object are readonly.
 * You can change some of them only with appropriate method in OCTSubmanagerObjects.
 */
@interface OCTMessageFile : OCTObject

/**
 * The current state of file.
 */
@property OCTMessageFileType fileType;

/**
 * In case if fileType is equal to OCTMessageFileTypePaused this property will contain information
 * by whom file transfer was paused.
 */
@property OCTMessageFilePausedBy pausedBy;

/**
 * Size of file in bytes.
 */
@property OCTToxFileSize fileSize;

/**
 * Name of the file as specified by sender. Note that actual fileName in path
 * may differ from this fileName.
 */
@property (nullable) NSString *fileName;

/**
 * Uniform Type Identifier of file.
 */
@property (nullable) NSString *fileUTI;

/**
 * NGC group file message id (32-byte hash as hex). Used for dedup and progress tracking.
 */
@property (nullable) NSString *groupMsgIdHashHex;

/**
 * KHANDAQ (#192): symmetric tox file_id (32-byte, as uppercase hex) for a 1:1 transfer. Both peers
 * read the identical value via tox_file_get_file_id, so it anchors reactions on files/media/voice.
 */
@property (nullable) NSString *fileIdHex;

/**
 * Transfer progress for NGC group files (0.0 – 1.0). Meaningful while fileType is Loading.
 */
@property float groupTransferProgress;

/**
 * KHANDAQ (#82): frozen sender display name for incoming NGC group FILE messages, resolved by
 * STABLE pubkey at receipt (mirrors OCTMessageText.groupPeerName). Without it the sender label was
 * resolved from the volatile groupSenderPeerId at render time and changed after peers reshuffled on
 * reconnect/restart. nil for outgoing/legacy rows.
 */
@property (nullable) NSString *groupPeerName;

/**
 * KHANDAQ (#82): stable LOWERCASE pubkey hex of the file sender, frozen at receipt. Allows
 * re-resolving the name later if the roster is known, and keys identity across volatile peer-id reuse.
 */
@property (nullable) NSString *groupSenderPubkey;

/**
 * Indicate if message is delivered. Actual only for outgoing group files (after send completes).
 */
@property BOOL isDelivered;

/**
 * Count of hist-sync confirmations for outgoing group files.
 */
@property int32_t groupSyncConfirmations;

@property (nullable) NSString *groupSyncPeerKey1;
@property (nullable) NSString *groupSyncPeerKey2;
@property (nullable) NSString *groupSyncPeerKey3;

/**
 * Path of file on disk. If you need fileName to show to user please use
 * `fileName` property. filePath has it's own random fileName.
 *
 * In case of incoming file filePath will have value only if fileType is OCTMessageFileTypeReady
 */
- (nullable NSString *)filePath;

// Properties and methods below are for internal use.
// Do not use them or rely on them. They may change in any moment.

@property int internalFileNumber;
@property (nullable) NSString *internalFilePath;
- (void)internalSetFilePath:(nullable NSString *)path;

@end

RLM_ARRAY_TYPE(OCTMessageFile)
