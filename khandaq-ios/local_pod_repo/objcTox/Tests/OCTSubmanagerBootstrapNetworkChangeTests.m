// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import <XCTest/XCTest.h>
#import <OCMock/OCMock.h>

#import "OCTSubmanagerBootstrapImpl.h"
#import "OCTSubmanagerDataSource.h"
#import "OCTTox.h"
#import "OCTRealmManager.h"

@interface OCTSubmanagerBootstrapImpl (NetworkChangeTests)

@property (assign, nonatomic) NSTimeInterval lastUrgentRebootstrapTime;

@end

/**
 * KHANDAQ (bootstrap loops): every network change started an urgent bootstrap loop and none of them
 * ever stopped. The running loop was not retired, and the urgent pass re-seeded its own nodes, so it
 * bootstrapped every node every second for as long as the app ran.
 *
 * Kept apart from OCTSubmanagerBootstrapImplTests, whose strict tox mock predates the tox queue and
 * which is therefore not built into iOSDemoTests.
 */
@interface OCTSubmanagerBootstrapNetworkChangeTests : XCTestCase

@end

@implementation OCTSubmanagerBootstrapNetworkChangeTests

/**
 * Two network changes on a connected client must settle within a few seconds and bootstrap nothing
 * after that.
 */
- (void)testNetworkRebootstrapsSettleOnceConnected
{
    id dataSource = OCMProtocolMock(@protocol(OCTSubmanagerDataSource));
    id tox = OCMClassMock([OCTTox class]);
    id realmManager = OCMClassMock([OCTRealmManager class]);
    __block NSUInteger bootstrapCalls = 0;

    OCMStub([dataSource managerGetTox]).andReturn(tox);
    OCMStub([dataSource managerGetRealmManager]).andReturn(realmManager);
    OCMStub([dataSource managerIsToxConnected]).andReturn(YES);
    OCMStub([dataSource managerGetNotificationCenter]).andReturn([NSNotificationCenter new]);
    OCMStub([tox performBlockOnToxQueue:[OCMArg any]]).andDo(^(NSInvocation *invocation) {
        void (^__unsafe_unretained block)(void) = nil;
        [invocation getArgument:&block atIndex:2];
        block();
    });
    [[[[tox stub] ignoringNonObjectArgs] andDo:^(NSInvocation *invocation) {
        bootstrapCalls++;
    }] bootstrapFromHost:[OCMArg any] port:0 publicKey:[OCMArg any] error:[OCMArg anyObjectRef]];

    OCTSubmanagerBootstrapImpl *submanager = [OCTSubmanagerBootstrapImpl new];
    submanager.dataSource = dataSource;

    [submanager rebootstrapOnNetworkChange];
    // Past the 2 s debounce, as a second network change a little later would be.
    submanager.lastUrgentRebootstrapTime = 0;
    [submanager rebootstrapOnNetworkChange];

    // The urgent pass uses up its nodes in about a second; give it three.
    __block NSUInteger settledCalls = 0;
    XCTestExpectation *passOver = [self expectationWithDescription:@"urgent pass over"];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        settledCalls = bootstrapCalls;
        [passOver fulfill];
    });
    [self waitForExpectationsWithTimeout:4.0 handler:nil];

    XCTestExpectation *quiet = [self expectationWithDescription:@"quiet period"];
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3.0 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        [quiet fulfill];
    });
    [self waitForExpectationsWithTimeout:4.0 handler:nil];

    XCTAssertGreaterThan(settledCalls, 0u, @"the network change bootstrapped nothing");
    XCTAssertEqual(bootstrapCalls, settledCalls, @"bootstrap went on after the client was connected");
}

@end
