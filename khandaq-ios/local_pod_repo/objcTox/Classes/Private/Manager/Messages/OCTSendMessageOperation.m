// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSendMessageOperation.h"
#import "OCTTox.h"

@interface OCTSendMessageOperation ()

@property (weak, nonatomic, readonly) OCTTox *tox;

@property (assign, nonatomic, readonly) OCTToxFriendNumber friendNumber;
@property (assign, nonatomic, readonly) OCTToxMessageType messageType;
@property (copy, nonatomic, readonly) NSString *message;
@property (copy, nonatomic, readonly) NSString *msgv3HashHex;
@property (assign, nonatomic, readonly) UInt32 msgv3tssec;
@property (copy, nonatomic, readonly) OCTSendMessageOperationSuccessBlock successBlock;
@property (copy, nonatomic, readonly) OCTSendMessageOperationFailureBlock failureBlock;

@end

@implementation OCTSendMessageOperation

- (instancetype)initWithTox:(OCTTox *)tox
               friendNumber:(OCTToxFriendNumber)friendNumber
                messageType:(OCTToxMessageType)messageType
                    message:(NSString *)message
               msgv3HashHex:(NSString *)msgv3HashHex
                 msgv3tssec:(UInt32)msgv3tssec
               successBlock:(nullable OCTSendMessageOperationSuccessBlock)successBlock
               failureBlock:(nullable OCTSendMessageOperationFailureBlock)failureBlock
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _tox = tox;
    _friendNumber = friendNumber;
    _messageType = messageType;
    _message = [message copy];
    _msgv3HashHex = [msgv3HashHex copy];
    _msgv3tssec = msgv3tssec;
    _successBlock = [successBlock copy];
    _failureBlock = [failureBlock copy];

    return self;
}

- (void)main
{
    if (self.cancelled) {
        return;
    }

    NSError *error;

    OCTToxMessageId messageId = [self.tox sendMessageWithFriendNumber:self.friendNumber
                                                                 type:self.messageType
                                                              message:self.message
                                                         msgv3HashHex:self.msgv3HashHex
                                                           msgv3tssec:self.msgv3tssec
                                                                error:&error];

    dispatch_async(dispatch_get_main_queue(), ^{
        if (self.cancelled) {
            return;
        }

        if (error && self.failureBlock) {
            self.failureBlock(error);
        }
        else if (! error && self.successBlock) {
            self.successBlock(messageId);
        }
    });
}

@end
