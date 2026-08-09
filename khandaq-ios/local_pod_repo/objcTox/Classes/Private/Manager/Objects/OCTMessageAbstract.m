// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTMessageAbstract.h"
#import "OCTMessageText.h"
#import "OCTMessageFile.h"
#import "OCTMessageCall.h"

@interface OCTMessageAbstract ()

@end

@implementation OCTMessageAbstract

#pragma mark -  Public

- (NSDate *)date
{
    if (self.dateInterval <= 0) {
        return nil;
    }

    return [NSDate dateWithTimeIntervalSince1970:self.dateInterval];
}

- (BOOL)isOutgoing
{
    if (self.groupSystemMessage) {
        return NO;
    }

    // KHANDAQ (audit F-6): a history-synced row is by definition someone else's message (the sync
    // handler drops packets carrying our own pubkey). Its groupSenderPeerId is 0 whenever the author
    // is not in our roster at insert time — offline, already left, or an outright forged pubkey — and
    // the peer-id test below then rendered that message as OUR OWN outgoing bubble, which also made it
    // look editable / deletable-for-everyone by us.
    if (self.groupHistorySync) {
        return NO;
    }

    if (self.groupSenderPeerId > 0) {
        return NO;
    }

    return (self.senderUniqueIdentifier == nil);
}

- (NSString *)description
{
    NSString *string = nil;

    if (self.messageText) {
        string = [self.messageText description];
    }
    else if (self.messageFile) {
        string = [self.messageFile description];
    }
    else if (self.messageCall) {
        string = [self.messageCall description];
    }

    return [NSString stringWithFormat:@"OCTMessageAbstract with date %@, %@", self.date, string];
}

@end
