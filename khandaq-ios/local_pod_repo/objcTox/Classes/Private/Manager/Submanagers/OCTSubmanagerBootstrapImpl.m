// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerBootstrapImpl.h"
#import "OCTNode.h"
#import "OCTTox.h"
#import "OCTLogging.h"
#import "OCTRealmManager.h"
#import "OCTSettingsStorageObject.h"

static const NSTimeInterval kDidConnectDelay = 2.0; // in seconds
static const NSTimeInterval kIterationTime = 5.0; // in seconds
static const NSTimeInterval kUrgentIterationTime = 1.0; // in seconds
static const NSUInteger kNodesPerIteration = 20;
static const NSUInteger kUrgentNodesPerIteration = 30;

@interface OCTSubmanagerBootstrapImpl ()

@property (strong, nonatomic) NSMutableSet *addedNodes;

@property (assign, nonatomic) BOOL isBootstrapping;
@property (assign, nonatomic) BOOL bootstrappingCancelled;

@property (strong, nonatomic) NSObject *bootstrappingLock;

@property (assign, nonatomic) NSTimeInterval didConnectDelay;
@property (assign, nonatomic) NSTimeInterval iterationTime;

@property (assign, nonatomic) BOOL urgentNetworkRebootstrap;
@property (assign, nonatomic) NSUInteger nodesPerIteration;
@property (assign, nonatomic) NSTimeInterval lastUrgentRebootstrapTime;

@end

@implementation OCTSubmanagerBootstrapImpl
@synthesize dataSource = _dataSource;

- (void)dealloc
{
    @synchronized(self.bootstrappingLock) {
        self.bootstrappingCancelled = YES;
    }
}

#pragma mark -  Lifecycle

- (instancetype)init
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _addedNodes = [NSMutableSet new];
    _bootstrappingLock = [NSObject new];

    _didConnectDelay = kDidConnectDelay;
    _iterationTime = kIterationTime;
    _nodesPerIteration = kNodesPerIteration;

    return self;
}

#pragma mark -  Public

- (void)addNodeWithIpv4Host:(nullable NSString *)ipv4Host
                   ipv6Host:(nullable NSString *)ipv6Host
                    udpPort:(OCTToxPort)udpPort
                   tcpPorts:(NSArray<NSNumber *> *)tcpPorts
                  publicKey:(NSString *)publicKey
{
    OCTNode *node = [[OCTNode alloc] initWithIpv4Host:ipv4Host
                                             ipv6Host:ipv6Host
                                              udpPort:udpPort
                                             tcpPorts:tcpPorts
                                            publicKey:publicKey];

    @synchronized(self.addedNodes) {
        [self.addedNodes addObject:node];
    }
}

- (void)addPredefinedNodes
{
    NSString *file = [[self objcToxBundle] pathForResource:@"nodes" ofType:@"json"];
    NSData *data = [NSData dataWithContentsOfFile:file];

    NSDictionary *dictionary = [NSJSONSerialization JSONObjectWithData:data options:0 error:nil];
    NSAssert(dictionary, @"Nodes json file is corrupted.");

    for (NSDictionary *node in dictionary[@"nodes"]) {
        NSUInteger lastPing = [node[@"last_ping"] unsignedIntegerValue];

        if (lastPing == 0) {
            // Skip nodes that weren't seen online.
            continue;
        }

        NSString *ipv4 = node[@"ipv4"];
        NSString *ipv6 = node[@"ipv6"];
        OCTToxPort udpPort = [node[@"port"] unsignedShortValue];
        NSArray<NSNumber *> *tcpPorts = node[@"tcp_ports"];
        NSString *publicKey = node[@"public_key"];

        // Check if addresses are valid.
        if (ipv4.length <= 2) {
            ipv4 = nil;
        }
        if (ipv6.length <= 2) {
            ipv6 = nil;
        }

        NSAssert(ipv4, @"Nodes json file is corrupted");
        NSAssert(udpPort > 0, @"Nodes json file is corrupted");
        NSAssert(publicKey, @"Nodes json file is corrupted");

        [self addNodeWithIpv4Host:ipv4 ipv6Host:ipv6 udpPort:udpPort tcpPorts:tcpPorts publicKey:publicKey];
    }
}

- (void)reloadPredefinedNodes
{
    @synchronized(self.addedNodes) {
        [self.addedNodes removeAllObjects];
    }

    [self addPredefinedNodes];
}

- (void)performKhandaqBootstrapBurst
{
    OCTTox *tox = [self.dataSource managerGetTox];

    NSArray<NSDictionary<NSString *, NSString *> *> *khandaqNodes = @[
        @{ @"host": @"bootstrap1.khandaq.org", @"key": @"74AE9E62A2AE51983CF9C6B526CD89ABD8AA91864B35FC0CF7AC60454CBDDD6D" },
        @{ @"host": @"bootstrap2.khandaq.org", @"key": @"5C6F3903FB1EC4AC386843D8FB584CC34567E045EC26939A6034C3A2746A9B6B" },
        @{ @"host": @"bootstrap3.khandaq.org", @"key": @"A181DD1F8C9A9D41BE1875A5C2687A89C3CB4F0F76ED9C390E7270B01BF24665" },
    ];

    [tox performBlockOnToxQueue:^{
        for (NSDictionary<NSString *, NSString *> *node in khandaqNodes) {
            NSString *host = node[@"host"];
            NSString *key = node[@"key"];
            NSError *error = nil;

            [tox bootstrapFromHost:host port:33445 publicKey:key error:&error];
            [tox addTCPRelayWithHost:host port:33445 publicKey:key error:&error];
            [tox addTCPRelayWithHost:host port:3389 publicKey:key error:&error];
        }
    }];
}

- (void)rebootstrapOnNetworkChange
{
    NSTimeInterval now = [[NSDate date] timeIntervalSince1970];

    if ((now - self.lastUrgentRebootstrapTime) < 2.0) {
        OCTLogInfo(@"rebootstrapOnNetworkChange: debounced");
        return;
    }

    self.lastUrgentRebootstrapTime = now;
    OCTLogInfo(@"rebootstrapOnNetworkChange: urgent reconnect");

    @synchronized(self.bootstrappingLock) {
        self.bootstrappingCancelled = YES;
        self.isBootstrapping = NO;
    }

    [self reloadPredefinedNodes];

    self.urgentNetworkRebootstrap = YES;
    self.iterationTime = kUrgentIterationTime;
    self.didConnectDelay = 0;
    self.nodesPerIteration = kUrgentNodesPerIteration;

    @synchronized(self.bootstrappingLock) {
        self.bootstrappingCancelled = NO;
        self.isBootstrapping = YES;
    }

    [[self.dataSource managerGetTox] resetOfflineRebootstrapTimer];
    [self performKhandaqBootstrapBurst];
    [self tryToBootstrap];

    __weak OCTSubmanagerBootstrapImpl *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, (int64_t)(3 * NSEC_PER_SEC)), dispatch_get_main_queue(), ^{
        __strong OCTSubmanagerBootstrapImpl *strongSelf = weakSelf;

        if (! strongSelf) {
            return;
        }

        [[strongSelf.dataSource managerGetNotificationCenter] postNotificationName:kOCTNetworkRebootstrapCompletedNotification
                                                                            object:nil];
    });
}

- (void)bootstrap
{
    @synchronized(self.bootstrappingLock) {
        if (self.isBootstrapping) {
            OCTLogWarn(@"bootstrap method called while already bootstrapping");
            return;
        }
        self.isBootstrapping = YES;
    }

    OCTLogVerbose(@"bootstrapping with %lu nodes", (unsigned long)self.addedNodes.count);

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    if (realmManager.settingsStorage.bootstrapDidConnect) {
        OCTLogVerbose(@"did connect before, waiting %g seconds", self.didConnectDelay);
        [self tryToBootstrapAfter:self.didConnectDelay];
    }
    else {
        [self tryToBootstrap];
    }
}

#pragma mark -  Private

- (BOOL)shouldContinueBootstrapping
{
    @synchronized(self.bootstrappingLock) {
        return self.isBootstrapping && !self.bootstrappingCancelled;
    }
}

- (void)tryToBootstrapAfter:(NSTimeInterval)after
{
    __weak OCTSubmanagerBootstrapImpl *weakSelf = self;
    dispatch_after(dispatch_time(DISPATCH_TIME_NOW, after * NSEC_PER_SEC), dispatch_get_main_queue(), ^{
        __strong OCTSubmanagerBootstrapImpl *strongSelf = weakSelf;

        if (! strongSelf) {
            OCTLogInfo(@"OCTSubmanagerBootstrap is dead, seems that OCTManager was killed, quiting.");
            return;
        }

        if (! [strongSelf shouldContinueBootstrapping]) {
            [strongSelf finishBootstrapping];
            return;
        }

        [strongSelf tryToBootstrap];
    });
}

- (void)tryToBootstrap
{
    if (! [self shouldContinueBootstrapping]) {
        [self finishBootstrapping];
        return;
    }

    NSArray *selectedNodes = [self selectedNodesForIteration];
    const BOOL toxConnected = [self.dataSource managerIsToxConnected];

    const BOOL urgent = self.urgentNetworkRebootstrap;

    if (toxConnected && ! urgent) {
        OCTLogInfo(@"trying to bootstrap... tox is connected, finishing TCP relays");
    }

    if (! selectedNodes.count) {
        OCTLogInfo(@"trying to bootstrap... no nodes left, exiting");

        if (toxConnected || urgent) {
            OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
            [realmManager updateObject:realmManager.settingsStorage withBlock:^(OCTSettingsStorageObject *object) {
                object.bootstrapDidConnect = YES;
            }];
        }

        if (urgent) {
            self.urgentNetworkRebootstrap = NO;
            self.iterationTime = kIterationTime;
            self.nodesPerIteration = kNodesPerIteration;
            [[self.dataSource managerGetNotificationCenter] postNotificationName:kOCTNetworkRebootstrapCompletedNotification
                                                                        object:nil];
        }

        [self finishBootstrapping];
        return;
    }

    OCTLogInfo(@"trying to bootstrap... picked %lu nodes (connected=%d urgent=%d)", (unsigned long)selectedNodes.count, toxConnected, urgent);

    __weak OCTSubmanagerBootstrapImpl *weakSelf = self;
    [[self.dataSource managerGetTox] performBlockOnToxQueue:^{
        __strong OCTSubmanagerBootstrapImpl *strongSelf = weakSelf;

        if (! strongSelf || ! [strongSelf shouldContinueBootstrapping]) {
            return;
        }

        for (OCTNode *node in selectedNodes) {
            if (! [strongSelf shouldContinueBootstrapping]) {
                break;
            }

            if (! toxConnected || strongSelf.urgentNetworkRebootstrap) {
                [strongSelf safeBootstrapFromHost:node.ipv4Host port:node.udpPort publicKey:node.publicKey];
                [strongSelf safeBootstrapFromHost:node.ipv6Host port:node.udpPort publicKey:node.publicKey];
            }

            for (NSNumber *tcpPort in node.tcpPorts) {
                [strongSelf safeAddTcpRelayWithHost:node.ipv4Host port:tcpPort.intValue publicKey:node.publicKey];
                [strongSelf safeAddTcpRelayWithHost:node.ipv6Host port:tcpPort.intValue publicKey:node.publicKey];
            }
        }

        dispatch_async(dispatch_get_main_queue(), ^{
            __strong OCTSubmanagerBootstrapImpl *mainSelf = weakSelf;

            if (! mainSelf || ! [mainSelf shouldContinueBootstrapping]) {
                return;
            }

            if (toxConnected && ! mainSelf.urgentNetworkRebootstrap) {
                OCTRealmManager *realmManager = [mainSelf.dataSource managerGetRealmManager];
                [realmManager updateObject:realmManager.settingsStorage withBlock:^(OCTSettingsStorageObject *object) {
                    object.bootstrapDidConnect = YES;
                }];
                [mainSelf finishBootstrapping];
                return;
            }

            [mainSelf tryToBootstrapAfter:mainSelf.iterationTime];
        });
    }];
}

- (void)safeBootstrapFromHost:(NSString *)host port:(OCTToxPort)port publicKey:(NSString *)publicKey
{
    if (! [self shouldContinueBootstrapping] || ! host) {
        return;
    }

    NSError *error;

    if (! [[self.dataSource managerGetTox] bootstrapFromHost:host port:port publicKey:publicKey error:&error]) {
        OCTLogWarn(@"trying to bootstrap... bootstrap failed with address %@, error %@", host, error);
    }
}

- (void)safeAddTcpRelayWithHost:(NSString *)host port:(OCTToxPort)port publicKey:(NSString *)publicKey
{
    if (! [self shouldContinueBootstrapping] || ! host) {
        return;
    }

    NSError *error;

    if (! [[self.dataSource managerGetTox] addTCPRelayWithHost:host port:port publicKey:publicKey error:&error]) {
        OCTLogWarn(@"trying to bootstrap... tcp relay failed with address %@, error %@", host, error);
    }
}

- (void)finishBootstrapping
{
    @synchronized(self.bootstrappingLock) {
        self.isBootstrapping = NO;
    }
}

- (NSArray *)selectedNodesForIteration
{
    NSMutableArray *allNodes;
    NSMutableArray *selectedNodes = [NSMutableArray new];

    @synchronized(self.addedNodes) {
        allNodes = [[self.addedNodes allObjects] mutableCopy];
    }

    NSUInteger pickLimit = self.nodesPerIteration > 0 ? self.nodesPerIteration : kNodesPerIteration;

    while (allNodes.count && (selectedNodes.count < pickLimit)) {
        NSUInteger index = arc4random_uniform((u_int32_t)allNodes.count);

        [selectedNodes addObject:allNodes[index]];
        [allNodes removeObjectAtIndex:index];
    }

    @synchronized(self.addedNodes) {
        [self.addedNodes minusSet:[NSSet setWithArray:selectedNodes]];
    }

    return [selectedNodes copy];
}

- (NSBundle *)objcToxBundle
{
    NSBundle *mainBundle = [NSBundle bundleForClass:[self class]];
    NSBundle *objcToxBundle = [NSBundle bundleWithPath:[mainBundle pathForResource:@"objcTox" ofType:@"bundle"]];

    // objcToxBundle is used when installed with CocoaPods. If we run tests/demo app mainBundle would be used.
    return objcToxBundle ?: mainBundle;
}

@end
