// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerFilesImpl.h"
#import "OCTSubmanagerChats.h"
#import "OCTSubmanagerFilesProgressSubscriber.h"
#import "OCTTox.h"
#import "OCTToxConstants.h"
#import "OCTFileBaseOperation+Private.h"
#import "OCTFileDownloadOperation.h"
#import "OCTFileUploadOperation.h"
#import "OCTRealmManager.h"
#import "OCTLogging.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageFile.h"
#import "OCTFriend.h"
#import "OCTChat.h"
#import "OCTFileStorageProtocol.h"
#import "OCTFilePathInput.h"
#import "OCTFilePathOutput.h"
#import "OCTFileDataInput.h"
#import "OCTFileDataOutput.h"
#import "OCTFileTools.h"
#import "OCTSettingsStorageObject.h"
#import "NSError+OCTFile.h"

#if TARGET_OS_IPHONE
@import MobileCoreServices;
#endif

static NSString *const kDownloadsTempDirectory = @"me.dvor.objcTox.downloads";

static NSString *const kProgressSubscribersKey = @"kProgressSubscribersKey";
static NSString *const kMessageIdentifierKey = @"kMessageIdentifierKey";

static const NSTimeInterval kReofferGraceInterval = 120.0;

@interface OCTSubmanagerFilesImpl ()

@property (strong, nonatomic, readonly) NSOperationQueue *queue;

@property (strong, nonatomic, readonly) NSObject *filesCleanupLock;
@property (assign, nonatomic) BOOL filesCleanupInProgress;

/// KHANDAQ (0.2.57): public key of a friend -> how many times they went online or offline, so a grace that began
/// while they were online ends nothing once they have gone away and come back since.
@property (strong, nonatomic, readonly) NSMutableDictionary<NSString *, NSNumber *> *friendLinkGenerations;
/// KHANDAQ (0.2.57): public key of a friend -> whether they were online at the last change of their connection.
@property (strong, nonatomic, readonly) NSMutableDictionary<NSString *, NSNumber *> *friendLinkConnected;
/// KHANDAQ (0.2.57): public key of a friend -> when an offer of theirs last came or a transfer of theirs to us last
/// ended (system uptime). The grace counts from then (endReofferGraceForFriendWithPublicKey:generation:).
@property (strong, nonatomic, readonly) NSMutableDictionary<NSString *, NSNumber *> *friendIncomingActivity;

@end

@implementation OCTSubmanagerFilesImpl
@synthesize dataSource = _dataSource;
@synthesize reofferAutoAcceptPolicy = _reofferAutoAcceptPolicy;

#pragma mark -  Lifecycle

- (instancetype)init
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _queue = [NSOperationQueue new];
    _filesCleanupLock = [NSObject new];
    _friendLinkGenerations = [NSMutableDictionary new];
    _friendLinkConnected = [NSMutableDictionary new];
    _friendIncomingActivity = [NSMutableDictionary new];
    _reofferGraceInterval = kReofferGraceInterval;

    return self;
}

- (void)dealloc
{
    [self.dataSource.managerGetNotificationCenter removeObserver:self];
}

- (void)configure
{
    [self.dataSource.managerGetNotificationCenter addObserver:self
                                                     selector:@selector(friendConnectionStatusChangeNotification:)
                                                         name:kOCTFriendConnectionStatusChangeNotification
                                                       object:nil];
    [self.dataSource.managerGetNotificationCenter addObserver:self
                                                     selector:@selector(userAvatarWasUpdatedNotification)
                                                         name:kOCTUserAvatarWasUpdatedNotification
                                                       object:nil];
    [self.dataSource.managerGetNotificationCenter addObserver:self
                                                     selector:@selector(scheduleFilesCleanup)
                                                         name:kOCTScheduleFileTransferCleanupNotification
                                                       object:nil];

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSSet<NSString *> *waiting = [self keepTransfersOfLastRunWaitingForTheConnection];

    OCTLogInfo(@"cancelling pending files...");

    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"fileType == %d OR fileType == %d OR fileType == %d",
                              OCTMessageFileTypeWaitingConfirmation, OCTMessageFileTypeLoading, OCTMessageFileTypePaused];

    [realmManager updateObjectsWithClass:[OCTMessageFile class] predicate:predicate updateBlock:^(OCTMessageFile *file) {
        // KHANDAQ (0.2.57): a 1:1 transfer of the last run waits for the connection now (see above).
        if ([waiting containsObject:file.uniqueIdentifier]) {
            return;
        }

        // KHANDAQ (#81): a fully-downloaded NGC group file can be persisted as Loading (a lingering
        // BEGIN bubble whose COMPLETE finalized a sibling row, or a progress row not yet flipped to
        // Ready). The blanket cancel below then turned an already-downloaded & played group video into
        // "Загрузка не удалась" after every restart. If the bytes are actually complete on disk,
        // finalize it to Ready instead of cancelling. Only applies to group files (groupMsgIdHashHex);
        // genuinely interrupted 1:1 transfers (and incomplete group temp files) still cancel.
        if (file.groupMsgIdHashHex.length > 0 && file.fileSize > 0) {
            NSString *onDiskPath = [file filePath];
            if (onDiskPath.length > 0) {
                NSDictionary *attrs = [[NSFileManager defaultManager] attributesOfItemAtPath:onDiskPath error:nil];
                if (attrs && [attrs fileSize] >= file.fileSize) {
                    file.fileType = OCTMessageFileTypeReady;
                    file.groupTransferProgress = 1.0f;
                    OCTLogInfo(@"salvaging completed group file %@", file);
                    return;
                }
            }
        }

        // KHANDAQ (#165): a file QUEUED for an offline friend (faux-offline: WaitingConfirmation
        // with the failure file number, stored as -1 in the signed int property) has no live tox
        // transfer to lose — keep it queued across restarts so the delivery watchdog can resend it
        // once the friend comes online. The blanket cancel below is only for transfers that were
        // actually in-flight when the app died.
        if (file.fileType == OCTMessageFileTypeWaitingConfirmation &&
            file.internalFileNumber == (int)kOCTToxFileNumberFailure &&
            file.groupMsgIdHashHex.length == 0) {
            OCTLogInfo(@"keeping offline-queued file %@", file);
            return;
        }

        file.fileType = OCTMessageFileTypeCanceled;
        OCTLogInfo(@"cancelling file %@", file);
    }];

    OCTLogInfo(@"cancelling pending files... done");

    NSFileManager *fileManager = [NSFileManager defaultManager];

    // KHANDAQ (0.2.57): 1:1 downloads keep their bytes next to the result now (OCTFilePathOutput
    // initWithResultFilePath:ownerIdentifier:), so a restart leaves them; this directory holds what older versions left.
    NSString *downloads = [self downloadsTempDirectory];
    OCTLogInfo(@"clearing downloads temp directory %@\ncontents %@",
               downloads,
               [fileManager contentsOfDirectoryAtPath:downloads error:nil]);
    [fileManager removeItemAtPath:downloads error:nil];

    [self scheduleFilesCleanup];
}

#pragma mark -  Public

- (void)sendData:(nonnull NSData *)data
    withFileName:(nonnull NSString *)fileName
          toChat:(nonnull OCTChat *)chat
    failureBlock:(nullable void (^)(NSError *__nonnull error))failureBlock
{
    NSParameterAssert(data);
    NSParameterAssert(fileName);
    NSParameterAssert(chat);

    NSString *filePath = [OCTFileTools createNewFilePathInDirectory:[self uploadsDirectory] fileName:fileName];

    if (! [data writeToFile:filePath atomically:NO]) {
        OCTLogWarn(@"cannot save data to uploads directory.");
        if (failureBlock) {
            failureBlock([NSError sendFileErrorCannotSaveFileToUploads]);
        }
        return;
    }

    [self sendFileAtPath:filePath moveToUploads:NO toChat:chat failureBlock:failureBlock];
}

- (void)sendFileAtPath:(nonnull NSString *)filePath
         moveToUploads:(BOOL)moveToUploads
                toChat:(nonnull OCTChat *)chat
          failureBlock:(nullable void (^)(NSError *__nonnull error))failureBlock
{
    NSParameterAssert(filePath);
    NSParameterAssert(chat);

    NSString *fileName = [filePath lastPathComponent];
    NSError *error;

    if (moveToUploads) {
        NSString *toPath = [OCTFileTools createNewFilePathInDirectory:[self uploadsDirectory] fileName:fileName];

        if (! [[NSFileManager defaultManager] moveItemAtPath:filePath toPath:toPath error:&error]) {
            OCTLogWarn(@"cannot move file to uploads %@", error);
            if (failureBlock) {
                failureBlock([NSError sendFileErrorCannotSaveFileToUploads]);
            }
            return;
        }

        filePath = toPath;
    }

    NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:filePath error:nil];

    if (! attributes) {
        OCTLogWarn(@"cannot read file %@", filePath);
        if (failureBlock) {
            failureBlock([NSError sendFileErrorCannotReadFile]);
        }
        return;
    }

    OCTToxFileSize fileSize = [attributes[NSFileSize] longLongValue];
    OCTFriend *friend = [chat.friends firstObject];

    OCTToxFileNumber fileNumber = [[self.dataSource managerGetTox] fileSendWithFriendNumber:friend.friendNumber
                                                                                       kind:OCTToxFileKindData
                                                                                   fileSize:fileSize
                                                                                     fileId:nil
                                                                                   fileName:fileName
                                                                                      error:&error];

    if (fileNumber == kOCTToxFileNumberFailure) {
        if ((error.code == OCTToxErrorFileSendFriendNotConnected) &&
            [self.dataSource managerUseFauxOfflineMessaging]) {
            OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

            [realmManager addMessageWithFileNumber:kOCTToxFileNumberFailure
                                          fileType:OCTMessageFileTypeWaitingConfirmation
                                          fileSize:fileSize
                                          fileName:fileName
                                          filePath:filePath
                                           fileUTI:[self fileUTIFromFileName:fileName]
                                              chat:chat
                                            sender:nil];

            [[self.dataSource managerGetChats] triggerWakePushForChat:chat];
            return;
        }

        OCTLogWarn(@"cannot send file %@", error);
        if (failureBlock) {
            failureBlock([NSError sendFileErrorFromToxFileSendError:error.code]);
        }
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTMessageAbstract *message = [realmManager addMessageWithFileNumber:fileNumber
                                                                fileType:OCTMessageFileTypeWaitingConfirmation
                                                                fileSize:fileSize
                                                                fileName:fileName
                                                                filePath:filePath
                                                                 fileUTI:[self fileUTIFromFileName:fileName]
                                                                    chat:chat
                                                                  sender:nil];
    [self stampFileIdHexOnMessage:message fileNumber:fileNumber friendNumber:friend.friendNumber];

    NSDictionary *userInfo = [self fileOperationUserInfoWithMessage:message];
    OCTFilePathInput *input = [[OCTFilePathInput alloc] initWithFilePath:filePath];

    OCTFileUploadOperation *operation = [[OCTFileUploadOperation alloc] initWithTox:[self.dataSource managerGetTox]
                                                                          fileInput:input
                                                                       friendNumber:friend.friendNumber
                                                                         fileNumber:fileNumber
                                                                           fileSize:fileSize
                                                                           userInfo:userInfo
                                                                      progressBlock:[self fileProgressBlockWithMessage:message]
                                                                     etaUpdateBlock:[self fileEtaUpdateBlockWithMessage:message]
                                                                       successBlock:[self fileSuccessBlockWithMessage:message]
                                                                       failureBlock:[self   fileFailureBlockWithMessage:message
                                                                                                       userFailureBlock:failureBlock]];

    [self.queue addOperation:operation];
}

- (OCTMessageAbstract *)incomingMessageWithFileNumber:(OCTToxFileNumber)fileNumber
                                       friendNumber:(OCTToxFriendNumber)friendNumber
{
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [[self.dataSource managerGetRealmManager] friendWithPublicKey:publicKey];
    OCTChat *chat = [[self.dataSource managerGetRealmManager] getOrCreateChatWithFriend:friend];

    if (! chat || ! friend) {
        return nil;
    }

    // KHANDAQ (0.2.57): only a transfer under way. toxcore gives the numbers out again, and a file that came
    // long ago keeps the number it came with: a cancel of a new transfer marked that old file failed.
    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"chatUniqueIdentifier == %@ AND senderUniqueIdentifier == %@ AND messageFile.internalFileNumber == %d AND messageFile.fileType IN %@",
                              chat.uniqueIdentifier, friend.uniqueIdentifier, (int)fileNumber,
                              @[@(OCTMessageFileTypeWaitingConfirmation), @(OCTMessageFileTypeLoading), @(OCTMessageFileTypePaused)]];

    RLMResults *results = [[self.dataSource managerGetRealmManager] objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    return [[results sortedResultsUsingKeyPath:@"dateInterval" ascending:NO] firstObject];
}

- (BOOL)prepareIncomingFileForAccept:(OCTMessageAbstract *)message
                        failureBlock:(nullable void (^)(NSError *__nonnull error))failureBlock
{
    OCTMessageFileType type = message.messageFile.fileType;
    OCTFriend *friend = [self friendForMessage:message];
    OCTFileBaseOperation *operation = [self operationWithFileNumber:message.messageFile.internalFileNumber
                                                       friendNumber:friend.friendNumber];

    if (type == OCTMessageFileTypeReady) {
        OCTLogWarn(@"specified wrong message: file already downloaded. %@", message);
        if (failureBlock) {
            failureBlock([NSError acceptFileErrorWrongMessage:message]);
        }
        return NO;
    }

    if (type == OCTMessageFileTypeLoading || type == OCTMessageFileTypePaused) {
        if (operation && ! operation.isFinished) {
            return NO;
        }

        [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
            file.fileType = OCTMessageFileTypeWaitingConfirmation;
            file.pausedBy = OCTMessageFilePausedByNone;
        }];

        if (operation) {
            [operation cancel];
        }
        return YES;
    }

    if (type == OCTMessageFileTypeCanceled) {
        // KHANDAQ (0.2.57): a canceled file has no transfer left: its number is toxcore's to give to another
        // file of the friend, whose transfer the cancel and the accept here used to hit. It waits for the friend
        // to offer it again (acceptFileTransfer).
        [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
            file.internalFileNumber = (int)kOCTToxFileNumberFailure;
            file.fileType = OCTMessageFileTypeWaitingConfirmation;
            file.pausedBy = OCTMessageFilePausedByNone;
        }];
        return YES;
    }

    if (type != OCTMessageFileTypeWaitingConfirmation) {
        OCTLogWarn(@"specified wrong message: wrong file type %ld. %@", (long)type, message);
        if (failureBlock) {
            failureBlock([NSError acceptFileErrorWrongMessage:message]);
        }
        return NO;
    }

    if (operation && ! operation.isFinished) {
        [operation cancel];
    }

    return YES;
}

- (void)acceptFileTransfer:(OCTMessageAbstract *)message
              failureBlock:(nullable void (^)(NSError *__nonnull error))failureBlock
{
    if (! message.senderUniqueIdentifier) {
        OCTLogWarn(@"specified wrong message: no sender. %@", message);
        if (failureBlock) {
            failureBlock([NSError acceptFileErrorWrongMessage:message]);
        }
        return;
    }

    if (! message.messageFile) {
        OCTLogWarn(@"specified wrong message: no messageFile. %@", message);
        if (failureBlock) {
            failureBlock([NSError acceptFileErrorWrongMessage:message]);
        }
        return;
    }

    if (! [self prepareIncomingFileForAccept:message failureBlock:failureBlock]) {
        return;
    }

    OCTFriend *friend = [[self.dataSource managerGetRealmManager] objectWithUniqueIdentifier:message.senderUniqueIdentifier
                                                                                       class:[OCTFriend class]];

    // KHANDAQ (0.2.57): nothing live to accept (the connection went, or the app restarted, since the offer):
    // the person wants the file, and it goes on by itself once the friend offers it again.
    if (message.messageFile.internalFileNumber == (int)kOCTToxFileNumberFailure) {
        OCTToxFileSize kept = [self partialLengthOfMessage:message];
        OCTLogInfo(@"accepted with no live transfer, waiting for the friend to offer it again: %@", message);
        [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
            file.fileType = OCTMessageFileTypeWaitingConfirmation;
            file.pausedBy = OCTMessageFilePausedByConnection;
            file.transferredBytes = kept;
        }];
        [self beginReofferGraceForFriend:friend];
        return;
    }

    // KHANDAQ (0.2.57): the bytes go next to the result and outlive an interruption; a file accepted before
    // goes on into the same result from what it kept.
    NSString *resultFilePath = [message.messageFile filePath];
    if (resultFilePath.length == 0) {
        resultFilePath = [OCTFileTools createNewFilePathInDirectory:[self downloadsDirectory]
                                                           fileName:message.messageFile.fileName];
    }
    OCTToxFileSize resumePosition = [self resumePositionForMessage:message];

    // Referenced by the message before the placeholder is made, so a cleanup in between cannot take them.
    [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
        [file internalSetFilePath:resultFilePath];
    }];

    OCTFilePathOutput *output = [[OCTFilePathOutput alloc] initWithResultFilePath:resultFilePath
                                                                  ownerIdentifier:message.uniqueIdentifier];

    NSDictionary *userInfo = [self fileOperationUserInfoWithMessage:message];

    OCTFileDownloadOperation *operation = [[OCTFileDownloadOperation alloc]
                                           initWithTox:self.dataSource.managerGetTox
                                              fileOutput:output
                                            friendNumber:friend.friendNumber
                                              fileNumber:message.messageFile.internalFileNumber
                                                fileSize:message.messageFile.fileSize
                                                userInfo:userInfo
                                           progressBlock:[self fileProgressBlockWithMessage:message]
                                          etaUpdateBlock:[self fileEtaUpdateBlockWithMessage:message]
                                            successBlock:[self fileSuccessBlockWithMessage:message]
                                            failureBlock:[self   fileFailureBlockWithMessage:message
                                                                            userFailureBlock:failureBlock]];
    operation.resumePosition = resumePosition;
    if (resumePosition > 0) {
        OCTLogInfo(@"resuming the download from byte %lld of %lld: %@", resumePosition, message.messageFile.fileSize, message);
    }

    [self.queue addOperation:operation];

    [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
        file.fileType = OCTMessageFileTypeLoading;
        file.pausedBy = OCTMessageFilePausedByNone;
        file.transferredBytes = 0;
    }];
}

- (BOOL)cancelFileTransfer:(OCTMessageAbstract *)message error:(NSError **)error
{
    if (! message.messageFile) {
        OCTLogWarn(@"specified wrong message: no messageFile. %@", message);
        if (error) {
            *error = [NSError fileTransferErrorWrongMessage:message];
        }
        return NO;
    }

    OCTFriend *friend = [self friendForMessage:message];
    OCTMessageFileType type = message.messageFile.fileType;
    BOOL underWay = (type == OCTMessageFileTypeWaitingConfirmation ||
                     type == OCTMessageFileTypeLoading ||
                     type == OCTMessageFileTypePaused);
    BOOL live = underWay && message.messageFile.internalFileNumber != (int)kOCTToxFileNumberFailure;
    BOOL incoming = (message.senderUniqueIdentifier != nil);

    // KHANDAQ (0.2.57): a file waiting for the connection has no transfer to cancel, and its number is not
    // its own any more.
    if (live) {
        [self.dataSource.managerGetTox fileSendControlForFileNumber:message.messageFile.internalFileNumber
                                                       friendNumber:friend.friendNumber
                                                            control:OCTToxFileControlCancel
                                                              error:nil];

        OCTFileBaseOperation *operation = [self operationWithFileNumber:message.messageFile.internalFileNumber
                                                           friendNumber:friend.friendNumber];
        [operation cancel];
    }

    if (incoming) {
        [self removePartialFileOfMessage:message];
    }
    if (incoming && live) {
        [self noteIncomingTransferActivityOfFriend:friend];
    }

    [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
        file.fileType = OCTMessageFileTypeCanceled;
        // KHANDAQ (0.2.57): theirs, cancelled while under way: every later offer of it is refused
        // (takeReofferOfMessage), across a restart too, never taken by itself, until the person asks for it again
        // ("Download again", acceptFileTransfer). With nothing live the cancel cannot tell them; with a transfer
        // live it may not reach them (the connection goes first), and they offer it again, or send it again.
        file.pausedBy = (incoming && underWay) ? OCTMessageFilePausedByConnection : OCTMessageFilePausedByNone;
        file.transferredBytes = 0;
    }];

    return YES;
}

- (void)retrySendingFile:(nonnull OCTMessageAbstract *)message
            failureBlock:(nullable void (^)(NSError *__nonnull error))failureBlock
{
    NSParameterAssert(message);

    BOOL isPendingOffline = (message.messageFile.fileType == OCTMessageFileTypeWaitingConfirmation &&
                             message.messageFile.internalFileNumber == kOCTToxFileNumberFailure);
    BOOL isCanceled = (message.messageFile.fileType == OCTMessageFileTypeCanceled);

    if (! message.messageFile || (! isPendingOffline && ! isCanceled)) {
        OCTLogWarn(@"specified wrong message: no messageFile. %@", message);
        if (failureBlock) {
            failureBlock([NSError fileTransferErrorWrongMessage:message]);
        }
        return;
    }

    NSString *fileName = message.messageFile.fileName;
    NSString *filePath = [message.messageFile filePath];

    NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:filePath error:nil];

    if (! attributes) {
        OCTLogWarn(@"cannot read file %@", filePath);
        // KHANDAQ (0.2.57): a queued file whose bytes are gone can never leave: it fails instead of being tried
        // again on every tick of the delivery watchdog.
        if (isPendingOffline) {
            [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
                file.fileType = OCTMessageFileTypeCanceled;
                file.pausedBy = OCTMessageFilePausedByNone;
                file.transferredBytes = 0;
            }];
        }
        if (failureBlock) {
            failureBlock([NSError sendFileErrorCannotReadFile]);
        }
        return;
    }

    OCTToxFileSize fileSize = [attributes[NSFileSize] longLongValue];
    OCTFriend *friend = [self friendForMessage:message];
    NSError *error;

    // KHANDAQ (0.2.57): one Tox file id for the whole life of the file: every offer after the first passes the
    // id the first one made (reconnect, restart, the delivery watchdog, "Send again"), so the friend's side
    // knows the file and goes on from the bytes it has instead of taking it again as a new one.
    NSData *fileId = [self fileIdFromHex:message.messageFile.fileIdHex];

    OCTToxFileNumber fileNumber = [[self.dataSource managerGetTox] fileSendWithFriendNumber:friend.friendNumber
                                                                                       kind:OCTToxFileKindData
                                                                                   fileSize:fileSize
                                                                                     fileId:fileId
                                                                                   fileName:fileName
                                                                                      error:&error];

    if (fileNumber == kOCTToxFileNumberFailure) {
        if ((error.code == OCTToxErrorFileSendFriendNotConnected) &&
            [self.dataSource managerUseFauxOfflineMessaging]) {
            // KHANDAQ (0.2.57): "Send again" while the friend is away queues the file, as a new one is queued.
            if (isCanceled) {
                [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
                    file.internalFileNumber = (int)kOCTToxFileNumberFailure;
                    file.fileType = OCTMessageFileTypeWaitingConfirmation;
                    file.pausedBy = OCTMessageFilePausedByNone;
                    file.transferredBytes = 0;
                }];
            }
            OCTChat *messageChat = [[self.dataSource managerGetRealmManager] getOrCreateChatWithFriend:friend];
            [[self.dataSource managerGetChats] triggerWakePushForChat:messageChat];
            return;
        }

        OCTLogWarn(@"cannot send file %@", error);
        if (failureBlock) {
            failureBlock([NSError sendFileErrorFromToxFileSendError:error.code]);
        }
        return;
    }

    [self updateMessageFile:message withBlock:^(OCTMessageFile *messageFile) {
        messageFile.internalFileNumber = fileNumber;
        messageFile.fileType = OCTMessageFileTypeWaitingConfirmation;
        messageFile.fileSize = fileSize;
        // A file that waited for the connection keeps saying so, and its bar, until the friend takes it again
        // (the Resume in fileReceiveControl).
        if (! (messageFile.pausedBy & OCTMessageFilePausedByConnection)) {
            messageFile.pausedBy = OCTMessageFilePausedByNone;
            messageFile.transferredBytes = 0;
        }
    }];
    // KHANDAQ (0.2.57): a file sent while they were offline leaves here without an id until this first offer
    // makes one, and the friend knows the file by it alone: their read receipt, reaction or delete for it, and
    // their side of a transfer that goes on after an interruption.
    [self stampFileIdHexOnMessage:message fileNumber:fileNumber friendNumber:friend.friendNumber];

    NSDictionary *userInfo = [self fileOperationUserInfoWithMessage:message];
    OCTFilePathInput *input = [[OCTFilePathInput alloc] initWithFilePath:filePath];

    OCTFileUploadOperation *operation = [[OCTFileUploadOperation alloc] initWithTox:[self.dataSource managerGetTox]
                                                                          fileInput:input
                                                                       friendNumber:friend.friendNumber
                                                                         fileNumber:fileNumber
                                                                           fileSize:fileSize
                                                                           userInfo:userInfo
                                                                      progressBlock:[self fileProgressBlockWithMessage:message]
                                                                     etaUpdateBlock:[self fileEtaUpdateBlockWithMessage:message]
                                                                       successBlock:[self fileSuccessBlockWithMessage:message]
                                                                       failureBlock:[self   fileFailureBlockWithMessage:message
                                                                                                       userFailureBlock:failureBlock]];

    [self.queue addOperation:operation];
}

- (BOOL)pauseFileTransfer:(BOOL)pause message:(nonnull OCTMessageAbstract *)message error:(NSError **)error
{
    if (! message.messageFile) {
        OCTLogWarn(@"specified wrong message: no messageFile. %@", message);
        if (error) {
            *error = [NSError fileTransferErrorWrongMessage:message];
        }
        return NO;
    }

    OCTToxFileControl control;
    OCTMessageFileType type;
    OCTMessageFilePausedBy pausedBy = message.messageFile.pausedBy;

    if (pause) {
        BOOL pausedByFriend = message.messageFile.pausedBy & OCTMessageFilePausedByFriend;

        if ((message.messageFile.fileType != OCTMessageFileTypeLoading) && ! pausedByFriend) {
            OCTLogWarn(@"message in wrong state %ld", (long)message.messageFile.fileType);
            return YES;
        }

        control = OCTToxFileControlPause;
        type = OCTMessageFileTypePaused;
        pausedBy |= OCTMessageFilePausedByUser;
    }
    else {
        BOOL pausedByUser = message.messageFile.pausedBy & OCTMessageFilePausedByUser;
        if ((message.messageFile.fileType != OCTMessageFileTypePaused) && ! pausedByUser) {
            OCTLogWarn(@"message in wrong state %ld", (long)message.messageFile.fileType);
            return YES;
        }

        control = OCTToxFileControlResume;
        pausedBy &= ~OCTMessageFilePausedByUser;

        type = (pausedBy == OCTMessageFilePausedByNone) ? OCTMessageFileTypeLoading : OCTMessageFileTypePaused;
    }

    OCTFriend *friend = [self friendForMessage:message];

    OCTFileBaseOperation *operation = [self operationWithFileNumber:message.messageFile.internalFileNumber
                                                       friendNumber:friend.friendNumber];

    if (! operation || operation.isFinished) {
        if (message.messageFile.fileType == OCTMessageFileTypeLoading ||
            message.messageFile.fileType == OCTMessageFileTypePaused) {
            [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
                file.fileType = OCTMessageFileTypeWaitingConfirmation;
                file.pausedBy = OCTMessageFilePausedByNone;
            }];
        }
        return YES;
    }

    NSError *controlError = nil;
    if (! [self.dataSource.managerGetTox fileSendControlForFileNumber:message.messageFile.internalFileNumber
                                                         friendNumber:friend.friendNumber
                                                              control:control
                                                                error:&controlError]) {
        OCTLogWarn(@"cannot send file control %@, resetting transfer state", controlError);
        [operation cancel];
        [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
            file.fileType = OCTMessageFileTypeWaitingConfirmation;
            file.pausedBy = OCTMessageFilePausedByNone;
        }];
        return YES;
    }

    [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
        file.fileType = type;
        file.pausedBy = pausedBy;
    }];

    return YES;
}

- (BOOL)addProgressSubscriber:(nonnull id<OCTSubmanagerFilesProgressSubscriber>)subscriber
              forFileTransfer:(nonnull OCTMessageAbstract *)message
                        error:(NSError **)error
{
    if (! message.messageFile) {
        if (error) {
            *error = [NSError fileTransferErrorWrongMessage:message];
        }
        return NO;
    }

    OCTFriend *friend = [self friendForMessage:message];

    OCTFileBaseOperation *operation = [self operationWithFileNumber:message.messageFile.internalFileNumber
                                                       friendNumber:friend.friendNumber];

    if (! operation) {
        return YES;
    }

    NSString *identifier = operation.userInfo[kMessageIdentifierKey];
    if (! [identifier isEqualToString:message.uniqueIdentifier]) {
        return YES;
    }

    [subscriber submanagerFilesOnProgressUpdate:operation.progress message:message];
    [subscriber submanagerFilesOnEtaUpdate:operation.eta bytesPerSecond:operation.bytesPerSecond message:message];

    NSHashTable *progressSubscribers = operation.userInfo[kProgressSubscribersKey];
    [progressSubscribers addObject:subscriber];

    return YES;
}

- (BOOL)removeProgressSubscriber:(nonnull id<OCTSubmanagerFilesProgressSubscriber>)subscriber
                 forFileTransfer:(nonnull OCTMessageAbstract *)message
                           error:(NSError **)error
{
    if (! message.messageFile) {
        if (error) {
            *error = [NSError fileTransferErrorWrongMessage:message];
        }
        return NO;
    }

    OCTFriend *friend = [self friendForMessage:message];

    OCTFileBaseOperation *operation = [self operationWithFileNumber:message.messageFile.internalFileNumber
                                                       friendNumber:friend.friendNumber];

    if (! operation) {
        return YES;
    }

    NSString *identifier = operation.userInfo[kMessageIdentifierKey];
    if (! [identifier isEqualToString:message.uniqueIdentifier]) {
        return YES;
    }

    NSHashTable *progressSubscribers = operation.userInfo[kProgressSubscribersKey];
    [progressSubscribers removeObject:subscriber];

    return YES;
}

#pragma mark -  OCTToxDelegate

- (void)     tox:(OCTTox *)tox fileReceiveControl:(OCTToxFileControl)control
    friendNumber:(OCTToxFriendNumber)friendNumber
      fileNumber:(OCTToxFileNumber)fileNumber
{
    OCTFileBaseOperation *operation = [self operationWithFileNumber:fileNumber friendNumber:friendNumber];

    NSString *identifier = operation.userInfo[kMessageIdentifierKey];
    OCTMessageAbstract *message;

    if (identifier) {
        message = [self.dataSource.managerGetRealmManager objectWithUniqueIdentifier:identifier
                                                                               class:[OCTMessageAbstract class]];
    }

    if (! message) {
        message = [self incomingMessageWithFileNumber:fileNumber friendNumber:friendNumber];
    }

    switch (control) {
        case OCTToxFileControlResume: {
            [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
                // KHANDAQ (0.2.57): the friend took the file again: it no longer waits for the connection.
                file.pausedBy &= ~(OCTMessageFilePausedByFriend | OCTMessageFilePausedByConnection);
                file.fileType = (file.pausedBy == OCTMessageFilePausedByNone) ? OCTMessageFileTypeLoading : OCTMessageFileTypePaused;
                file.transferredBytes = 0;
            }];
            break;
        }
        case OCTToxFileControlPause: {
            [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
                file.pausedBy |= OCTMessageFilePausedByFriend;
                file.fileType = OCTMessageFileTypePaused;
            }];
            break;
        }
        case OCTToxFileControlCancel: {
            if (operation) {
                [operation cancel];
            }

            if (message.senderUniqueIdentifier) {
                [self removePartialFileOfMessage:message];
                [self noteIncomingTransferActivityOfFriend:[self friendForMessage:message]];
            }

            [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
                file.fileType = OCTMessageFileTypeCanceled;
                file.pausedBy = OCTMessageFilePausedByNone;
                file.transferredBytes = 0;
            }];
            break;
        }
    }
}

- (void)     tox:(OCTTox *)tox fileChunkRequestForFileNumber:(OCTToxFileNumber)fileNumber
    friendNumber:(OCTToxFriendNumber)friendNumber
        position:(OCTToxFileSize)position
          length:(size_t)length
{
    OCTFileBaseOperation *operation = [self operationWithFileNumber:fileNumber friendNumber:friendNumber];

    if ([operation isKindOfClass:[OCTFileUploadOperation class]]) {
        [(OCTFileUploadOperation *)operation chunkRequestWithPosition:position length:length];
    }
    else {
        OCTLogWarn(@"operation not found with fileNumber %d friendNumber %d", fileNumber, friendNumber);
        [self.dataSource.managerGetTox fileSendControlForFileNumber:fileNumber friendNumber:friendNumber control:OCTToxFileControlCancel error:nil];
    }
}

- (void)     tox:(OCTTox *)tox fileReceiveForFileNumber:(OCTToxFileNumber)fileNumber
    friendNumber:(OCTToxFriendNumber)friendNumber
            kind:(OCTToxFileKind)kind
        fileSize:(OCTToxFileSize)fileSize
        fileName:(NSString *)fileName
{
    switch (kind) {
        case OCTToxFileKindData:
            [self dataFileReceiveForFileNumber:fileNumber
                                  friendNumber:friendNumber
                                          kind:kind
                                      fileSize:fileSize
                                      fileName:fileName];
            break;
        case OCTToxFileKindAvatar:
            [self avatarFileReceiveForFileNumber:fileNumber
                                    friendNumber:friendNumber
                                            kind:kind
                                        fileSize:fileSize
                                        fileName:fileName];
            break;
    }
}

- (void)     tox:(OCTTox *)tox fileReceiveChunk:(NSData *)chunk
      fileNumber:(OCTToxFileNumber)fileNumber
    friendNumber:(OCTToxFriendNumber)friendNumber
        position:(OCTToxFileSize)position
{
    OCTFileBaseOperation *operation = [self operationWithFileNumber:fileNumber friendNumber:friendNumber];

    if ([operation isKindOfClass:[OCTFileDownloadOperation class]]) {
        [(OCTFileDownloadOperation *)operation receiveChunk:chunk position:position];
    }
    else {
        OCTLogWarn(@"operation not found with fileNumber %d friendNumber %d", fileNumber, friendNumber);
        [self.dataSource.managerGetTox fileSendControlForFileNumber:fileNumber friendNumber:friendNumber control:OCTToxFileControlCancel error:nil];
    }
}

#pragma mark -  NSNotification

- (void)friendConnectionStatusChangeNotification:(NSNotification *)notification
{
    OCTFriend *friend = notification.object;

    if (! friend) {
        OCTLogWarn(@"no friend received in notification %@, exiting", notification);
        return;
    }

    // KHANDAQ (0.2.57): only going online or offline is a new connection. A change between UDP and TCP, or the
    // same status again, keeps the grace that runs: a link that flipped between them more often than the grace
    // began it anew each time, and their file that waited never ended.
    BOOL linkChanged = YES;
    if (friend.publicKey.length > 0) {
        NSNumber *wasConnected = self.friendLinkConnected[friend.publicKey];
        linkChanged = (! wasConnected || wasConnected.boolValue != friend.isConnected);
        self.friendLinkConnected[friend.publicKey] = @(friend.isConnected);

        if (linkChanged) {
            NSUInteger generation = [self.friendLinkGenerations[friend.publicKey] unsignedIntegerValue];
            self.friendLinkGenerations[friend.publicKey] = @(generation + 1);
        }
    }

    [self sendAvatarToFriend:friend];

    if (friend.isConnected) {
        [self resendPendingOutgoingFilesToFriend:friend];
        if (linkChanged) {
            [self beginReofferGraceForFriend:friend];
        }
    }
    else {
        [self requeueInFlightTransfersForDisconnectedFriend:friend];
    }
}

// KHANDAQ (#177): toxcore forgets every in-flight transfer when the friend connection drops, but
// nothing here reacted to the drop — an outgoing voice/file caught mid-flap kept its (now dead)
// fileNumber and spun in «Loading» forever while later messages flowed over the new connection.
// On disconnect: cancel the orphaned operation, then move OUTGOING transfers back into the proven
// faux-offline queue (internalFileNumber=-1 + WaitingConfirmation → auto-resent by the
// friend-connected hook / delivery watchdog).
// KHANDAQ (0.2.57): INCOMING ones wait too instead of being canceled, and keep the bytes that came. A transfer
// that was under way (ours offered, theirs accepted) is marked OCTMessageFilePausedByConnection with what had
// moved, for its bar: ours goes again with the same file id, and theirs, offered again with its id, goes on
// from the bytes kept here (dataFileReceiveForFileNumber).
- (void)requeueInFlightTransfersForDisconnectedFriend:(OCTFriend *)friend
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND messageFile.fileType IN %@ AND messageFile.internalFileNumber != %d",
                              chat.uniqueIdentifier,
                              @[@(OCTMessageFileTypeWaitingConfirmation), @(OCTMessageFileTypeLoading), @(OCTMessageFileTypePaused)],
                              (int)kOCTToxFileNumberFailure];

    RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];

    NSMutableArray<OCTMessageAbstract *> *messages = [NSMutableArray new];
    for (OCTMessageAbstract *message in results) {
        [messages addObject:message];
    }

    for (OCTMessageAbstract *message in messages) {
        BOOL outgoing = (message.senderUniqueIdentifier == nil);
        OCTMessageFile *messageFile = message.messageFile;
        BOOL underWay = (messageFile.fileType != OCTMessageFileTypeWaitingConfirmation) ||
                        (messageFile.pausedBy & OCTMessageFilePausedByConnection);

        OCTFileBaseOperation *operation = [self operationWithFileNumber:messageFile.internalFileNumber
                                                           friendNumber:friend.friendNumber];
        OCTToxFileSize moved = outgoing ? operation.bytesDone : [self partialLengthOfMessage:message];
        if (moved <= 0) {
            moved = messageFile.transferredBytes;
        }

        OCTLogInfo(@"requeue-on-disconnect: %@ transfer fileNumber %d friend %@, under way %d, %lld bytes moved",
                   outgoing ? @"outgoing" : @"incoming", messageFile.internalFileNumber, friend, underWay, moved);

        // A download keeps the bytes it wrote (OCTFilePathOutput initWithResultFilePath:ownerIdentifier:).
        [operation cancel];

        [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
            file.internalFileNumber = (int)kOCTToxFileNumberFailure;
            file.fileType = OCTMessageFileTypeWaitingConfirmation;
            file.pausedBy = underWay ? OCTMessageFilePausedByConnection : OCTMessageFilePausedByNone;
            file.transferredBytes = underWay ? moved : 0;
        }];
    }
}

- (void)resendPendingOutgoingFilesToFriend:(OCTFriend *)friend
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    // KHANDAQ (#165): internalFileNumber is a SIGNED int property — kOCTToxFileNumberFailure
    // (UINT32_MAX) is stored as -1. The old "%u" comparison put 4294967295 into the predicate,
    // which never matched, so files/voice queued for an offline friend were NEVER auto-resent
    // when the friend came online (neither by the connection-status hook nor by the watchdog).
    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"chatUniqueIdentifier == %@ AND senderUniqueIdentifier == nil AND messageFile.fileType == %d AND messageFile.internalFileNumber == %d",
                              chat.uniqueIdentifier, OCTMessageFileTypeWaitingConfirmation, (int)kOCTToxFileNumberFailure];

    RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];

    // KHANDAQ: preserve intra-batch order — RLMResults is unsorted by default
    results = [results sortedResultsUsingKeyPath:@"dateInterval" ascending:YES];

    for (OCTMessageAbstract *message in results) {
        OCTLogInfo(@"Resending pending file to friend %@", friend);
        [self retrySendingFile:message failureBlock:^(NSError *error) {
            OCTLogWarn(@"Cannot resend pending file to friend %@, error %@", friend, error);
        }];
    }
}

// KHANDAQ: periodic safety net (driven by the app's MessageDeliveryWatchdog) so files/voice queued
// while the recipient was offline actually deliver once they reconnect — the one-shot
// friendConnectionStatusChangeNotification: above can miss files added around the status flip.
// Reuses the proven per-friend resend; retrySendingFile only acts on WaitingConfirmation files with
// a failed file number, so this is idempotent and safe to call every tick.
- (void)resendPendingOutgoingFilesToAllOnlineFriends
{
    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"connectionStatus != %d", OCTToxConnectionStatusNone];
    RLMResults *onlineFriends = [self.dataSource.managerGetRealmManager objectsWithClass:[OCTFriend class] predicate:predicate];

    for (OCTFriend *friend in onlineFriends) {
        [self resendPendingOutgoingFilesToFriend:friend];
    }
}

- (void)userAvatarWasUpdatedNotification
{
    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"connectionStatus != %d", OCTToxConnectionStatusNone];
    RLMResults *onlineFriends = [self.dataSource.managerGetRealmManager objectsWithClass:[OCTFriend class] predicate:predicate];

    for (OCTFriend *friend in onlineFriends) {
        [self sendAvatarToFriend:friend];
    }
}

#pragma mark -  Private

- (void)scheduleFilesCleanup
{
    @synchronized(self.filesCleanupLock) {
        if (self.filesCleanupInProgress) {
            return;
        }
        self.filesCleanupInProgress = YES;
    }

    OCTLogInfo(@"cleanup: starting files cleanup");

    NSString *uploads = [self uploadsDirectory];
    NSString *downloads = [self downloadsDirectory];

    __weak OCTSubmanagerFilesImpl *weakSelf = self;
    dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_LOW, 0), ^{
        NSFileManager *fileManager = [NSFileManager defaultManager];

        NSMutableSet *allFiles = [NSMutableSet new];

        NSError *error;
        NSArray<NSString *> *uploadsContents = [fileManager contentsOfDirectoryAtPath:uploads error:&error];
        if (uploadsContents) {
            for (NSString *file in uploadsContents) {
                [allFiles addObject:[uploads stringByAppendingPathComponent:file]];
            }
        }
        else {
            OCTLogWarn(@"cleanup: cannot read contents of uploads directory %@, error %@", uploads, error);
        }

        NSArray<NSString *> *downloadsContents = [fileManager contentsOfDirectoryAtPath:downloads error:&error];
        if (downloadsContents) {
            for (NSString *file in downloadsContents) {
                [allFiles addObject:[downloads stringByAppendingPathComponent:file]];
            }
        }
        else {
            OCTLogWarn(@"cleanup: cannot read contents of download directory %@, error %@", downloads, error);
        }

        OCTLogInfo(@"cleanup: total number of files %lu", (unsigned long)allFiles.count);
        OCTToxFileSize freedSpace = 0;

        for (NSString *path in allFiles) {
            __strong OCTSubmanagerFilesImpl *strongSelf = weakSelf;
            if (! strongSelf) {
                OCTLogWarn(@"cleanup: submanager was killed, quiting");
            }

            // KHANDAQ (0.2.57): only files are swept. The files of groups live in the folder "group" here
            // (groupFilesDirectoryForChat:, groupUploadsDirectory), a name no message holds, so every start of the
            // app and every message deleted removed that folder whole: every photo, video and file of every group,
            // sent and received, while their cards still said they were there. A folder stays, with all it holds.
            BOOL isDirectory = NO;
            if ([fileManager fileExistsAtPath:path isDirectory:&isDirectory] && isDirectory) {
                continue;
            }

            __block BOOL exists = NO;

            dispatch_sync(dispatch_get_main_queue(), ^{
                // KHANDAQ (#125): a file is "bounded" if ANY message still references it. The stored
                // internalFilePath can be in three historical formats — container-relative
                // ("Documents/.../name"), tilde ("~/Documents/.../name"), or a legacy absolute path.
                // The old exact tilde-match stopped matching once paths were stored container-relative
                // (the #44 re-rooting fix), so cleanup wrongly treated every still-referenced upload as
                // unbounded and DELETED it — outgoing photos/videos/voice notes vanished on the next
                // launch and only an icon was left on re-entry. Match on the unique on-disk file name,
                // which all three formats share and which is immune to container-UUID drift; biasing
                // toward "keep" here is correct (an orphan wastes space, a false delete loses data).
                NSString *name = [path lastPathComponent];
                OCTRealmManager *realmManager = strongSelf.dataSource.managerGetRealmManager;
                BOOL (^isBound)(NSString *) = ^BOOL (NSString *fileName) {
                    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                                              @"internalFilePath ENDSWITH %@ OR internalFilePath == %@",
                                              [@"/" stringByAppendingString:fileName], fileName];
                    return [realmManager objectsWithClass:[OCTMessageFile class] predicate:predicate].count > 0;
                };

                exists = isBound(name);

                // KHANDAQ (0.2.57): ".<message id>.part" holds the bytes of a download of that message that waits
                // for the connection (OCTFilePathOutput initWithResultFilePath:ownerIdentifier:); it goes when its
                // message goes.
                NSString *owner = [OCTFilePathOutput ownerIdentifierOfPartialFileName:name];
                if (! exists && owner.length > 0) {
                    exists = [realmManager objectWithUniqueIdentifier:owner class:[OCTMessageAbstract class]] != nil;
                }
            });

            if (exists) {
                continue;
            }

            OCTLogInfo(@"cleanup: found unbounded file, removing it. Path %@", path);

            NSDictionary *attributes = [[NSFileManager defaultManager] attributesOfItemAtPath:path error:&error];
            if (! attributes) {
                OCTLogWarn(@"cleanup: cannot read file at path %@, error %@", path, error);
            }

            if ([fileManager removeItemAtPath:path error:&error]) {
                freedSpace += [attributes[NSFileSize] longLongValue];
            }
            else {
                OCTLogWarn(@"cleanup: cannot remove file at path %@, error %@", path, error);
            }
        }

        OCTLogInfo(@"cleanup: done. Freed %lld bytes.", freedSpace);

        __strong OCTSubmanagerFilesImpl *strongSelf = weakSelf;
        @synchronized(strongSelf.filesCleanupLock) {
            strongSelf.filesCleanupInProgress = NO;
        }
    });
}

- (OCTFileBaseOperation *)operationWithFileNumber:(OCTToxFileNumber)fileNumber friendNumber:(OCTToxFriendNumber)friendNumber
{
    NSString *operationId = [OCTFileBaseOperation operationIdFromFileNumber:fileNumber friendNumber:friendNumber];

    for (OCTFileBaseOperation *operation in [self.queue operations]) {
        if ([operation.operationId isEqualToString:operationId]) {
            return operation;
        }
    }

    return nil;
}

- (void)createDirectoryIfNeeded:(NSString *)path
{
    NSFileManager *fileManager = [NSFileManager defaultManager];

    BOOL isDirectory;
    BOOL exists = [fileManager fileExistsAtPath:path isDirectory:&isDirectory];

    if (exists && ! isDirectory) {
        [fileManager removeItemAtPath:path error:nil];
        exists = NO;
    }

    if (! exists) {
        [fileManager createDirectoryAtPath:path withIntermediateDirectories:YES attributes:nil error:nil];
    }
}

- (NSString *)downloadsDirectory
{
    id<OCTFileStorageProtocol> fileStorage = self.dataSource.managerGetFileStorage;

    NSString *path = fileStorage.pathForDownloadedFilesDirectory;
    [self createDirectoryIfNeeded:path];

    return path;
}

- (NSString *)uploadsDirectory
{
    id<OCTFileStorageProtocol> fileStorage = self.dataSource.managerGetFileStorage;

    NSString *path = fileStorage.pathForUploadedFilesDirectory;
    [self createDirectoryIfNeeded:path];

    return path;
}

- (NSString *)downloadsTempDirectory
{
    id<OCTFileStorageProtocol> fileStorage = self.dataSource.managerGetFileStorage;

    NSString *path = [fileStorage.pathForTemporaryFilesDirectory stringByAppendingPathComponent:kDownloadsTempDirectory];
    [self createDirectoryIfNeeded:path];

    return path;
}

- (NSString *)fileUTIFromFileName:(NSString *)fileName
{
    NSString *extension = [fileName pathExtension];

    if (! extension) {
        return nil;
    }

    return (__bridge_transfer NSString *)UTTypeCreatePreferredIdentifierForTag(
        kUTTagClassFilenameExtension,
        (__bridge CFStringRef)extension,
        NULL);
}

- (void)updateMessageFile:(OCTMessageAbstract *)message withBlock:(void (^)(OCTMessageFile *))block
{
    if (! message) {
        DDLogWarn(@"no message to update");
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    [realmManager updateObject:message.messageFile withBlock:block];

    // Workaround to force Realm to update OCTMessageAbstract when OCTMessageFile was updated.
    [realmManager updateObject:message withBlock:^(OCTMessageAbstract *message) {
        message.dateInterval = message.dateInterval;
    }];
}

- (OCTFileBaseOperationProgressBlock)fileProgressBlockWithMessage:(OCTMessageAbstract *)message
{
    return ^(OCTFileBaseOperation *__nonnull operation) {
               NSHashTable *progressSubscribers = operation.userInfo[kProgressSubscribersKey];

               for (id<OCTSubmanagerFilesProgressSubscriber> subscriber in progressSubscribers) {
                   [subscriber submanagerFilesOnProgressUpdate:operation.progress message:message];
               }
    };
}

- (OCTFileBaseOperationProgressBlock)fileEtaUpdateBlockWithMessage:(OCTMessageAbstract *)message
{
    return ^(OCTFileBaseOperation *__nonnull operation) {
               NSHashTable *progressSubscribers = operation.userInfo[kProgressSubscribersKey];

               for (id<OCTSubmanagerFilesProgressSubscriber> subscriber in progressSubscribers) {
                   [subscriber submanagerFilesOnEtaUpdate:operation.eta
                                           bytesPerSecond:operation.bytesPerSecond
                                                  message:message];
               }
    };
}

- (OCTFileBaseOperationSuccessBlock)fileSuccessBlockWithMessage:(OCTMessageAbstract *)message
{
    __weak OCTSubmanagerFilesImpl *weakSelf = self;

    return ^(OCTFileBaseOperation *__nonnull operation) {
               __strong OCTSubmanagerFilesImpl *strongSelf = weakSelf;
               if (! message.isInvalidated && message.senderUniqueIdentifier) {
                   [strongSelf noteIncomingTransferActivityOfFriend:[strongSelf friendForMessage:message]];
               }
               [strongSelf updateMessageFile:message withBlock:^(OCTMessageFile *file) {

            file.fileType = OCTMessageFileTypeReady;
            file.pausedBy = OCTMessageFilePausedByNone;
            file.transferredBytes = 0;
        }];
    };
}

- (OCTFileBaseOperationFailureBlock)fileFailureBlockWithMessage:(OCTMessageAbstract *)message
                                               userFailureBlock:(void (^)(NSError *))userFailureBlock
{
    __weak OCTSubmanagerFilesImpl *weakSelf = self;

    return ^(OCTFileBaseOperation *__nonnull operation, NSError *__nonnull error) {
               __strong OCTSubmanagerFilesImpl *strongSelf = weakSelf;

               // KHANDAQ (0.2.57): an operation cancelled meanwhile, or one the message has left for a newer
               // transfer, says nothing about the message any more (the disconnect requeue cancels its
               // operation while a failure of it may be on its way here).
               if (operation.isCancelled || message.isInvalidated || ! message.messageFile ||
                   message.messageFile.internalFileNumber != (int)operation.fileNumber) {
                   return;
               }

               // KHANDAQ (0.2.57): it failed because the friend went away, or before the accept with the bytes kept
               // left as they were (OCTFileDownloadOperation failedBeforeAccept): it waits for the connection and
               // goes on from where it stopped, as one the disconnect requeue reaches first does. With the friend
               // online, the grace ends it if they never offer it again.
               OCTFriend *friend = [strongSelf friendForMessage:message];
               if (message.senderUniqueIdentifier) {
                   [strongSelf noteIncomingTransferActivityOfFriend:friend];
               }
               BOOL failedBeforeAccept = [operation isKindOfClass:[OCTFileDownloadOperation class]] &&
                                         ((OCTFileDownloadOperation *)operation).failedBeforeAccept;
               if (friend && (failedBeforeAccept || ! [strongSelf isFriendConnected:friend])) {
                   BOOL incoming = (message.senderUniqueIdentifier != nil);
                   OCTToxFileSize moved = incoming ? [strongSelf partialLengthOfMessage:message] : operation.bytesDone;
                   OCTLogInfo(@"transfer failed with the friend away or before the accept, waiting for the connection: %@", message);
                   [strongSelf updateMessageFile:message withBlock:^(OCTMessageFile *file) {
                       file.internalFileNumber = (int)kOCTToxFileNumberFailure;
                       file.fileType = OCTMessageFileTypeWaitingConfirmation;
                       file.pausedBy = OCTMessageFilePausedByConnection;
                       file.transferredBytes = moved;
                   }];
                   [strongSelf beginReofferGraceForFriend:friend];
                   return;
               }

               if (message.senderUniqueIdentifier) {
                   [strongSelf removePartialFileOfMessage:message];
               }

               [strongSelf updateMessageFile:message withBlock:^(OCTMessageFile *file) {
            file.fileType = OCTMessageFileTypeCanceled;
            file.pausedBy = OCTMessageFilePausedByNone;
            file.transferredBytes = 0;

            if (userFailureBlock) {
                userFailureBlock(error);
            }
        }];
    };
}

- (NSDictionary *)fileOperationUserInfoWithMessage:(OCTMessageAbstract *)message
{
    return @{
               kProgressSubscribersKey : [NSHashTable weakObjectsHashTable],
               kMessageIdentifierKey : message.uniqueIdentifier,
    };
}

- (void)sendAvatarToFriend:(OCTFriend *)friend
{
    NSParameterAssert(friend);

    NSData *avatar = self.dataSource.managerGetRealmManager.settingsStorage.userAvatarData;

    if (! avatar) {
        [[self.dataSource managerGetTox] fileSendWithFriendNumber:friend.friendNumber
                                                             kind:OCTToxFileKindAvatar
                                                         fileSize:0
                                                           fileId:nil
                                                         fileName:nil
                                                            error:nil];
        return;
    }

    OCTToxFileSize fileSize = avatar.length;
    NSData *hash = [self.dataSource.managerGetTox hashData:avatar];

    NSError *error;
    OCTToxFileNumber fileNumber = [[self.dataSource managerGetTox] fileSendWithFriendNumber:friend.friendNumber
                                                                                       kind:OCTToxFileKindAvatar
                                                                                   fileSize:fileSize
                                                                                     fileId:hash
                                                                                   fileName:nil
                                                                                      error:&error];

    if (fileNumber == kOCTToxFileNumberFailure) {
        OCTLogWarn(@"cannot send file %@", error);
        return;
    }

    OCTFileDataInput *input = [[OCTFileDataInput alloc] initWithData:avatar];

    OCTFileUploadOperation *operation = [[OCTFileUploadOperation alloc] initWithTox:[self.dataSource managerGetTox]
                                                                          fileInput:input
                                                                       friendNumber:friend.friendNumber
                                                                         fileNumber:fileNumber
                                                                           fileSize:fileSize
                                                                           userInfo:nil
                                                                      progressBlock:nil
                                                                     etaUpdateBlock:nil
                                                                       successBlock:nil
                                                                       failureBlock:nil];

    [self.queue addOperation:operation];
}

- (void)dataFileReceiveForFileNumber:(OCTToxFileNumber)fileNumber
                        friendNumber:(OCTToxFriendNumber)friendNumber
                                kind:(OCTToxFileKind)kind
                            fileSize:(OCTToxFileSize)fileSize
                            fileName:(NSString *)fileName
{
    if (fileSize == 0) {
        OCTLogWarn(@"Received file with size 0, ignoring it.");
        [self.dataSource.managerGetTox fileSendControlForFileNumber:fileNumber friendNumber:friendNumber control:OCTToxFileControlCancel error:nil];
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];
    [self noteIncomingTransferActivityOfFriend:friend];

    // KHANDAQ (0.2.57): the same file again, offered with the id it came with before (the sender goes on after
    // the connection went): it belongs to the message it already has here, never to a new one.
    NSString *fileIdHex = [self fileIdHexForFileNumber:fileNumber friendNumber:friendNumber];
    OCTMessageAbstract *known = [self incomingMessageInChat:chat from:friend fileIdHex:fileIdHex fileSize:fileSize];
    if (known) {
        [self takeReofferOfMessage:known fileNumber:fileNumber friendNumber:friendNumber];
        return;
    }

    // KHANDAQ (0.2.57): a client without resume offers an interrupted file again with a new id: it comes as a new
    // message, as before, and the card that waited for it here ends interrupted instead of waiting on.
    [self endWaitingFilesInChat:chat from:friend matchingName:fileName fileSize:fileSize];

    OCTMessageAbstract *incoming = [realmManager addMessageWithFileNumber:fileNumber
                                  fileType:OCTMessageFileTypeWaitingConfirmation
                                  fileSize:fileSize
                                  fileName:fileName
                                  filePath:nil
                                   fileUTI:[self fileUTIFromFileName:fileName]
                                      chat:chat
                                    sender:friend];
    [self stampFileIdHexOnMessage:incoming fileNumber:fileNumber friendNumber:friendNumber];
    // KHANDAQ: do NOT auto-accept here. The "Загрузка вложений" setting (Never / Wi-Fi / Always) is an
    // app-layer policy enforced by AutomationCoordinator, which observes new incoming file messages and
    // accepts them only when the setting allows. Unconditionally accepting at the submanager level
    // bypassed that, so every incoming 1:1 file downloaded even with "Never" selected. Leaving the
    // message as WaitingConfirmation lets the user (or the policy) start the download.
}

- (void)avatarFileReceiveForFileNumber:(OCTToxFileNumber)fileNumber
                          friendNumber:(OCTToxFriendNumber)friendNumber
                                  kind:(OCTToxFileKind)kind
                              fileSize:(OCTToxFileSize)fileSize
                              fileName:(NSString *)fileName
{
    void (^cancelBlock)() = ^() {
        [self.dataSource.managerGetTox fileSendControlForFileNumber:fileNumber friendNumber:friendNumber control:OCTToxFileControlCancel error:nil];
    };

    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [[self.dataSource managerGetRealmManager] friendWithPublicKey:publicKey];

    if (fileSize == 0) {
        if (friend.avatarData) {
            [[self.dataSource managerGetRealmManager] updateObject:friend withBlock:^(OCTFriend *theFriend) {
                theFriend.avatarData = nil;
            }];
        }

        cancelBlock();
        return;
    }

    if (fileSize > kOCTManagerMaxAvatarSize) {
        OCTLogWarn(@"received avatar is too big, ignoring it, size %lld", fileSize);
        cancelBlock();
        return;
    }

    NSData *hash = [self.dataSource.managerGetTox hashData:friend.avatarData];
    NSData *remoteHash = [self.dataSource.managerGetTox fileGetFileIdForFileNumber:fileNumber
                                                                      friendNumber:friendNumber
                                                                             error:nil];

    if (remoteHash && [hash isEqual:remoteHash]) {
        OCTLogInfo(@"received same avatar, ignoring it");
        cancelBlock();
        return;
    }

    OCTFileDataOutput *output = [OCTFileDataOutput new];
    __weak OCTSubmanagerFilesImpl *weakSelf = self;

    OCTFileDownloadOperation *operation = [[OCTFileDownloadOperation alloc] initWithTox:self.dataSource.managerGetTox
                                                                             fileOutput:output
                                                                           friendNumber:friendNumber
                                                                             fileNumber:fileNumber
                                                                               fileSize:fileSize
                                                                               userInfo:nil
                                                                          progressBlock:nil
                                                                         etaUpdateBlock:nil
                                                                           successBlock:^(OCTFileBaseOperation *__nonnull operation) {
        __strong OCTSubmanagerFilesImpl *strongSelf = weakSelf;

        NSString *publicKey = [[strongSelf.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
        OCTFriend *friend = [[strongSelf.dataSource managerGetRealmManager] friendWithPublicKey:publicKey];

        [[strongSelf.dataSource managerGetRealmManager] updateObject:friend withBlock:^(OCTFriend *theFriend) {
            theFriend.avatarData = output.resultData;
        }];
    } failureBlock:nil];

    [self.queue addOperation:operation];
}

#pragma mark -  Transfers that go on after an interruption (KHANDAQ 0.2.57)

/**
 * An offer of a file a message here already has: theirs, the same Tox file id (any case of the hex) and the same
 * size. The newest one when there are several.
 */
- (OCTMessageAbstract *)incomingMessageInChat:(OCTChat *)chat
                                         from:(OCTFriend *)friend
                                    fileIdHex:(NSString *)fileIdHex
                                     fileSize:(OCTToxFileSize)fileSize
{
    if (! chat || ! friend || fileIdHex.length == 0) {
        return nil;
    }

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND senderUniqueIdentifier == %@ AND messageFile.fileIdHex ==[c] %@ AND messageFile.fileSize == %lld",
                              chat.uniqueIdentifier, friend.uniqueIdentifier, fileIdHex, fileSize];
    RLMResults *results = [[self.dataSource managerGetRealmManager] objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    return [[results sortedResultsUsingKeyPath:@"dateInterval" ascending:NO] firstObject];
}

/**
 * The friend offers the file of `message` again on `fileNumber`.
 * - Complete here (the connection went before they learnt it was done): its last byte once more, so both sides
 *   finish, and the card, its notification and the file on disk stay as they are.
 * - Cancelled here by the person, while it came or waited: refused, this time and every time it comes again, also
 *   after a restart, until the person asks for it again ("Download again"). What they said no to never downloads
 *   by itself.
 * - Otherwise the new transfer is the message's: it goes on from the bytes kept here, by itself when the person
 *   had accepted it or the auto-download rule takes it now (reofferAutoAcceptPolicy), else on their tap.
 */
- (void)takeReofferOfMessage:(OCTMessageAbstract *)message
                  fileNumber:(OCTToxFileNumber)fileNumber
                friendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTMessageFile *file = message.messageFile;

    if (file.fileType == OCTMessageFileTypeReady) {
        OCTLogInfo(@"re-offer of a file complete here, taking its last byte again: %@", message);
        [self finishCompleteFileAgainWithFileNumber:fileNumber friendNumber:friendNumber fileSize:file.fileSize];
        return;
    }

    // KHANDAQ (0.2.57): the mark stays. It went with the first refusal, so the next offer, the sender's second
    // "Send again", was taken by the auto-download rule and the whole file came again by itself.
    if (file.fileType == OCTMessageFileTypeCanceled && (file.pausedBy & OCTMessageFilePausedByConnection)) {
        OCTLogInfo(@"re-offer of a file the person cancelled here, refusing it: %@", message);
        [self.dataSource.managerGetTox fileSendControlForFileNumber:fileNumber
                                                       friendNumber:friendNumber
                                                            control:OCTToxFileControlCancel
                                                              error:nil];
        return;
    }

    BOOL accepted = (file.pausedBy & OCTMessageFilePausedByConnection) ||
                    file.fileType == OCTMessageFileTypeLoading ||
                    file.fileType == OCTMessageFileTypePaused;
    BOOL wasCanceled = (file.fileType == OCTMessageFileTypeCanceled);

    // A transfer it still had is gone: toxcore offers it anew only on a new connection.
    if (file.internalFileNumber != (int)kOCTToxFileNumberFailure && ! wasCanceled) {
        OCTFileBaseOperation *stale = [self operationWithFileNumber:file.internalFileNumber friendNumber:friendNumber];
        [stale cancel];
    }
    if (wasCanceled) {
        [self removePartialFileOfMessage:message];
    }

    OCTLogInfo(@"re-offer of a known file on number %u, accepted before %d: %@", fileNumber, accepted, message);
    [self updateMessageFile:message withBlock:^(OCTMessageFile *theFile) {
        theFile.internalFileNumber = fileNumber;
        theFile.fileType = OCTMessageFileTypeWaitingConfirmation;
        theFile.pausedBy = accepted ? OCTMessageFilePausedByConnection : OCTMessageFilePausedByNone;
        if (! accepted) {
            theFile.transferredBytes = 0;
        }
    }];

    BOOL (^policy)(OCTMessageAbstract *) = self.reofferAutoAcceptPolicy;
    if (accepted || (policy && policy(message))) {
        [self acceptFileTransfer:message failureBlock:nil];
    }
}

/**
 * The last byte of a file that is complete here comes once more and is dropped: the sender's transfer ends as
 * done, nothing changes here.
 */
- (void)finishCompleteFileAgainWithFileNumber:(OCTToxFileNumber)fileNumber
                                 friendNumber:(OCTToxFriendNumber)friendNumber
                                     fileSize:(OCTToxFileSize)fileSize
{
    OCTFileDownloadOperation *operation = [[OCTFileDownloadOperation alloc] initWithTox:self.dataSource.managerGetTox
                                                                             fileOutput:[OCTFileDataOutput new]
                                                                           friendNumber:friendNumber
                                                                             fileNumber:fileNumber
                                                                               fileSize:fileSize
                                                                               userInfo:nil
                                                                          progressBlock:nil
                                                                         etaUpdateBlock:nil
                                                                           successBlock:nil
                                                                           failureBlock:nil];
    operation.resumePosition = fileSize - 1;
    operation.cancelsWhenSeekFails = YES;
    [self.queue addOperation:operation];
}

/**
 * A new offer from the friend with another id and the name and size of a file of theirs that waits here for the
 * connection: a client without resume sent it again. The waiting card ends interrupted, and its bytes go.
 */
- (void)endWaitingFilesInChat:(OCTChat *)chat
                         from:(OCTFriend *)friend
                 matchingName:(NSString *)fileName
                     fileSize:(OCTToxFileSize)fileSize
{
    if (! chat || ! friend) {
        return;
    }

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND senderUniqueIdentifier == %@ AND messageFile.fileType == %d AND messageFile.internalFileNumber == %d AND messageFile.fileSize == %lld AND messageFile.fileName == %@",
                              chat.uniqueIdentifier, friend.uniqueIdentifier, OCTMessageFileTypeWaitingConfirmation,
                              (int)kOCTToxFileNumberFailure, fileSize, fileName];
    [self endWaitingFilesMatching:predicate reason:@"offered again with another id"];
}

/**
 * Begins the time the friend has, while online, to offer again what of theirs waits here for the connection.
 * Nothing happens while they are offline: the grace begins when they connect.
 */
- (void)beginReofferGraceForFriend:(OCTFriend *)friend
{
    NSString *publicKey = friend.publicKey;
    if (publicKey.length == 0 || ! [self isFriendConnected:friend]) {
        return;
    }

    NSUInteger generation = [self.friendLinkGenerations[publicKey] unsignedIntegerValue];
    [self endReofferGraceForFriendWithPublicKey:publicKey generation:generation after:self.reofferGraceInterval];
}

- (void)endReofferGraceForFriendWithPublicKey:(NSString *)publicKey
                                   generation:(NSUInteger)generation
                                        after:(NSTimeInterval)delay
{
    __weak OCTSubmanagerFilesImpl *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(delay * NSEC_PER_SEC)),
                   dispatch_get_main_queue(), ^{
        [weakSelf endReofferGraceForFriendWithPublicKey:publicKey generation:generation];
    });
}

- (void)endReofferGraceForFriendWithPublicKey:(NSString *)publicKey generation:(NSUInteger)generation
{
    // They went away and came back since: the grace of that connection decides.
    if ([self.friendLinkGenerations[publicKey] unsignedIntegerValue] != generation) {
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];
    if (! friend || ! [self isFriendConnected:friend]) {
        return;
    }

    // KHANDAQ (0.2.57): a sender may offer what waits one file at a time (Android: one per friend, the next once
    // the last has ended): the rest may still come while a file of theirs is under way here, or one ended a moment
    // ago. The grace waits on, and counts from the last time a transfer of theirs was live. It used to end the files
    // that waited behind the first one, with their bytes, while that one came.
    NSTimeInterval quiet = 0;
    if (! [self hasIncomingTransferUnderWayFromFriend:friend]) {
        NSNumber *activity = self.friendIncomingActivity[publicKey];
        quiet = activity ? [NSProcessInfo processInfo].systemUptime - activity.doubleValue : self.reofferGraceInterval;
    }
    if (quiet < self.reofferGraceInterval) {
        [self endReofferGraceForFriendWithPublicKey:publicKey generation:generation after:self.reofferGraceInterval - quiet];
        return;
    }

    // Only their 1:1 files name them as the sender (group files have none), and no chat is made for the look.
    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"senderUniqueIdentifier == %@ AND messageFile.fileType == %d AND messageFile.internalFileNumber == %d",
                              friend.uniqueIdentifier, OCTMessageFileTypeWaitingConfirmation, (int)kOCTToxFileNumberFailure];
    [self endWaitingFilesMatching:predicate reason:@"not offered again while the friend was online"];
}

/// KHANDAQ (0.2.57): an offer of theirs waits for the accept here, or a file of theirs comes or is paused.
- (BOOL)hasIncomingTransferUnderWayFromFriend:(OCTFriend *)friend
{
    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"senderUniqueIdentifier == %@ AND messageFile.internalFileNumber != %d AND messageFile.fileType IN %@",
                              friend.uniqueIdentifier, (int)kOCTToxFileNumberFailure,
                              @[@(OCTMessageFileTypeWaitingConfirmation), @(OCTMessageFileTypeLoading), @(OCTMessageFileTypePaused)]];
    RLMResults *results = [[self.dataSource managerGetRealmManager] objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    return results.count > 0;
}

/// KHANDAQ (0.2.57): an offer of theirs came, or a transfer of theirs to us ended, now (friendIncomingActivity).
- (void)noteIncomingTransferActivityOfFriend:(OCTFriend *)friend
{
    if (friend.publicKey.length > 0) {
        self.friendIncomingActivity[friend.publicKey] = @([NSProcessInfo processInfo].systemUptime);
    }
}

- (void)endWaitingFilesMatching:(NSPredicate *)predicate reason:(NSString *)reason
{
    RLMResults *results = [[self.dataSource managerGetRealmManager] objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    NSMutableArray<OCTMessageAbstract *> *messages = [NSMutableArray new];
    for (OCTMessageAbstract *message in results) {
        [messages addObject:message];
    }

    for (OCTMessageAbstract *message in messages) {
        OCTLogInfo(@"ending a file that waited for the connection, %@: %@", reason, message);
        [self removePartialFileOfMessage:message];
        [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
            file.fileType = OCTMessageFileTypeCanceled;
            file.pausedBy = OCTMessageFilePausedByNone;
            file.transferredBytes = 0;
        }];
    }
}

/**
 * A 1:1 transfer of the last run waits for the connection, as one the connection dropped does: ours is offered
 * again with its own file id once the friend is online, and theirs goes on from the bytes kept here when they
 * offer it again. Group files and the Saved chat keep the rules of configure. Returns the ids of the files kept.
 */
- (NSSet<NSString *> *)keepTransfersOfLastRunWaitingForTheConnection
{
    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"messageFile.fileType IN %@",
                              @[@(OCTMessageFileTypeWaitingConfirmation), @(OCTMessageFileTypeLoading), @(OCTMessageFileTypePaused)]];
    RLMResults *results = [[self.dataSource managerGetRealmManager] objectsWithClass:[OCTMessageAbstract class] predicate:predicate];

    NSMutableArray<OCTMessageAbstract *> *messages = [NSMutableArray new];
    for (OCTMessageAbstract *message in results) {
        if ([self isOneToOneFileMessage:message]) {
            [messages addObject:message];
        }
    }

    NSMutableSet<NSString *> *kept = [NSMutableSet new];
    for (OCTMessageAbstract *message in messages) {
        OCTMessageFile *messageFile = message.messageFile;
        BOOL incoming = (message.senderUniqueIdentifier != nil);
        BOOL underWay = (messageFile.fileType != OCTMessageFileTypeWaitingConfirmation) ||
                        (messageFile.pausedBy & OCTMessageFilePausedByConnection);
        OCTToxFileSize moved = incoming ? [self partialLengthOfMessage:message] : messageFile.transferredBytes;

        OCTLogInfo(@"keeping %@ file of the last run waiting for the connection, under way %d, %lld bytes moved: %@",
                   incoming ? @"incoming" : @"outgoing", underWay, moved, message);
        [kept addObject:messageFile.uniqueIdentifier];
        [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
            file.internalFileNumber = (int)kOCTToxFileNumberFailure;
            file.fileType = OCTMessageFileTypeWaitingConfirmation;
            file.pausedBy = underWay ? OCTMessageFilePausedByConnection : OCTMessageFilePausedByNone;
            file.transferredBytes = underWay ? moved : 0;
        }];
    }

    return kept;
}

- (BOOL)isOneToOneFileMessage:(OCTMessageAbstract *)message
{
    if (! message.messageFile || message.messageFile.groupMsgIdHashHex.length > 0) {
        return NO;
    }

    OCTChat *chat = [[self.dataSource managerGetRealmManager] objectWithUniqueIdentifier:message.chatUniqueIdentifier
                                                                                    class:[OCTChat class]];
    return chat && ! chat.isGroup && ! chat.isSavedMessages && chat.friends.count > 0;
}

- (BOOL)isFriendConnected:(OCTFriend *)friend
{
    OCTToxConnectionStatus status = [self.dataSource.managerGetTox friendConnectionStatusWithFriendNumber:friend.friendNumber
                                                                                                    error:nil];
    return status != OCTToxConnectionStatusNone;
}

/// Where a download of `message` goes on from: the bytes kept of it. All of them (the last write landed, the move
/// to the result did not) takes the last byte again, so the transfer ends as one; more than the file takes it anew.
- (OCTToxFileSize)resumePositionForMessage:(OCTMessageAbstract *)message
{
    OCTToxFileSize kept = [self partialLengthOfMessage:message];
    OCTToxFileSize size = message.messageFile.fileSize;

    if (kept <= 0 || size <= 0 || kept > size) {
        return 0;
    }
    return MIN(kept, size - 1);
}

- (NSString *)partialFilePathOfMessage:(OCTMessageAbstract *)message
{
    NSString *resultFilePath = [message.messageFile filePath];
    if (resultFilePath.length == 0 || message.uniqueIdentifier.length == 0) {
        return nil;
    }
    return [OCTFilePathOutput partialFilePathForResultFilePath:resultFilePath ownerIdentifier:message.uniqueIdentifier];
}

- (OCTToxFileSize)partialLengthOfMessage:(OCTMessageAbstract *)message
{
    NSString *path = [self partialFilePathOfMessage:message];
    if (! path) {
        return 0;
    }
    return [[[NSFileManager defaultManager] attributesOfItemAtPath:path error:nil] fileSize];
}

- (void)removePartialFileOfMessage:(OCTMessageAbstract *)message
{
    NSString *path = [self partialFilePathOfMessage:message];
    if (path) {
        [[NSFileManager defaultManager] removeItemAtPath:path error:nil];
    }
}

- (NSString *)fileIdHexForFileNumber:(OCTToxFileNumber)fileNumber friendNumber:(OCTToxFriendNumber)friendNumber
{
    NSData *fileId = [[self.dataSource managerGetTox] fileGetFileIdForFileNumber:fileNumber
                                                                     friendNumber:friendNumber
                                                                            error:nil];
    if (fileId.length != kOCTToxFileIdLength) {
        return nil;
    }

    const uint8_t *bytes = fileId.bytes;
    NSMutableString *hex = [NSMutableString stringWithCapacity:fileId.length * 2];
    for (NSUInteger i = 0; i < fileId.length; i++) {
        [hex appendFormat:@"%02X", bytes[i]];
    }
    return hex;
}

/// The 32 bytes of a stored file id; nil for no id or one of another shape (the offer then makes a new one).
- (NSData *)fileIdFromHex:(NSString *)hex
{
    if (hex.length != kOCTToxFileIdLength * 2) {
        return nil;
    }

    NSMutableData *data = [NSMutableData dataWithCapacity:kOCTToxFileIdLength];
    for (NSUInteger i = 0; i < hex.length; i += 2) {
        unsigned int value = 0;
        NSScanner *scanner = [NSScanner scannerWithString:[hex substringWithRange:NSMakeRange(i, 2)]];
        if (! [scanner scanHexInt:&value] || ! scanner.isAtEnd) {
            return nil;
        }
        uint8_t byte = (uint8_t)value;
        [data appendBytes:&byte length:1];
    }
    return data;
}

- (OCTFriend *)friendForMessage:(OCTMessageAbstract *)message
{
    OCTChat *chat = [[self.dataSource managerGetRealmManager] objectWithUniqueIdentifier:message.chatUniqueIdentifier
                                                                                   class:[OCTChat class]];
    return [chat.friends firstObject];
}

// KHANDAQ (#192): capture the symmetric tox file_id (identical for both peers) and stamp it as
// uppercase hex on the message so reactions can anchor to files/media/voice. Must run while the
// file_number is still live (right after send/receive), since the id is released with the transfer.
- (void)stampFileIdHexOnMessage:(OCTMessageAbstract *)message
                     fileNumber:(OCTToxFileNumber)fileNumber
                   friendNumber:(OCTToxFriendNumber)friendNumber
{
    if (! message.messageFile || fileNumber == (OCTToxFileNumber)kOCTToxFileNumberFailure) {
        return;
    }
    NSString *hex = [self fileIdHexForFileNumber:fileNumber friendNumber:friendNumber];
    if (! hex) {
        return;
    }
    [self updateMessageFile:message withBlock:^(OCTMessageFile *file) {
        file.fileIdHex = hex;
    }];
}

@end
