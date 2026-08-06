// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerBootstrapImpl.h"
#import "OCTNode.h"
#import "OCTTox.h"
#import "OCTLogging.h"
#import "OCTRealmManager.h"
#import "OCTSettingsStorageObject.h"

static const NSTimeInterval kDidConnectDelay = 1.0; // in seconds (KHANDAQ #iOS slow-connect: was 2.0)
static const NSTimeInterval kIterationTime = 3.0; // in seconds (KHANDAQ #iOS slow-connect: was 5.0)
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

    if (data.length == 0) {
        OCTLogWarn(@"Bootstrap: nodes.json missing or empty at %@", file ?: @"(nil)");
        return;
    }

    NSError *jsonError = nil;
    id parsed = [NSJSONSerialization JSONObjectWithData:data options:0 error:&jsonError];
    if (! [parsed isKindOfClass:[NSDictionary class]]) {
        OCTLogWarn(@"Bootstrap: nodes.json parse failed: %@", jsonError.localizedDescription ?: @"invalid root");
        return;
    }
    NSDictionary *dictionary = (NSDictionary *)parsed;
    NSArray *nodes = dictionary[@"nodes"];
    if (! [nodes isKindOfClass:[NSArray class]]) {
        OCTLogWarn(@"Bootstrap: nodes.json missing \"nodes\" array");
        return;
    }

    for (NSDictionary *node in nodes) {
        if (! [node isKindOfClass:[NSDictionary class]]) {
            continue;
        }
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

        if (ipv4.length <= 2 && ipv6.length <= 2) {
            continue;
        }
        if (udpPort == 0 || publicKey.length == 0) {
            continue;
        }

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

    // KHANDAQ: fast-connect burst to PROVEN PUBLIC Tox DHT nodes (parity with the Android client, which
    // also dropped the self-hosted bootstrap*.khandaq.org nodes). We no longer depend on our own
    // bootstrap nodes for joining the network. NB: this is the DHT bootstrap — push.khandaq.org (the FCM
    // wake relay) is a separate service and is unaffected.
    // KHANDAQ (#iOS slow-connect): fire IP-LITERAL nodes FIRST in their own tox-queue block. These take
    // no getaddrinfo, so tox_iterate (which shares this serial queue) is never stalled on DNS — the old
    // hostname-only burst did 12 blocking DNS lookups on the iterate queue and delayed first connect to
    // ~18s. IP literals are the real ones from nodes.json (keep in sync when nodes.json is regenerated).
    NSArray<NSDictionary *> *ipNodes = @[
        @{ @"host": @"139.162.110.188", @"key": @"F76A11284547163889DDC89A7738CF271797BF5E5E220643E97AD3C7E7903D55", @"tcp": @[@3389, @33445, @443] },
        @{ @"host": @"144.217.167.73",  @"key": @"7E5668E0EE09E19F320AD47902419331FFEE147BB3606769CFBE921A2A2FD34C", @"tcp": @[@3389, @33445] },
        @{ @"host": @"172.105.109.31",  @"key": @"D46E97CF995DC1820B92B7D899E152A217D36ABE22730FEA4B6BF1BFC06C617C", @"tcp": @[@33445] },
        @{ @"host": @"43.198.227.166",  @"key": @"AD13AB0D434BCE6C83FE2649237183964AE3341D0AFB3BE1694B18505E4E135E", @"tcp": @[@33445, @3389] },
        @{ @"host": @"188.245.84.166",  @"key": @"96B66D300BA2B59B98FC42DB1325E7092388F0379593E680ABDBEA03B9C9CE03", @"tcp": @[@443, @3389, @33445] },
    ];

    [tox performBlockOnToxQueue:^{
        for (NSDictionary *node in ipNodes) {
            NSString *host = node[@"host"];
            NSString *key = node[@"key"];
            NSError *error = nil;
            [tox bootstrapFromHost:host port:33445 publicKey:key error:&error];
            for (NSNumber *p in node[@"tcp"]) {
                [tox addTCPRelayWithHost:host port:p.intValue publicKey:key error:&error];
            }
        }
    }];

    // Hostname fallback — deferred to its OWN block so its DNS never blocks the IP burst above or an
    // interleaved tox_iterate tick.
    NSArray<NSDictionary<NSString *, NSString *> *> *hostNodes = @[
        @{ @"host": @"tox.abilinski.com", @"key": @"10C00EB250C3233E343E2AEBA07115A5C28920E9C8D29492F6D00B29049EDC7E" },
        @{ @"host": @"tox1.mf-net.eu",    @"key": @"B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506" },
        @{ @"host": @"tox2.mf-net.eu",    @"key": @"70EA214FDE161E7432530605213F18F7427DC773E276B3E317A07531F548545F" },
        @{ @"host": @"tox.initramfs.io",  @"key": @"3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25" },
    ];
    [tox performBlockOnToxQueue:^{
        for (NSDictionary<NSString *, NSString *> *node in hostNodes) {
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

    // KHANDAQ (#iOS slow-connect): the didConnectDelay exists to avoid hammering when ALREADY online.
    // On a cold launch we are offline (bootstrapDidConnect is a persisted flag, not live state), so the
    // old code needlessly waited 2s before touching the DNS-free IP nodes. Only delay when actually
    // connected right now; otherwise bootstrap immediately.
    if (realmManager.settingsStorage.bootstrapDidConnect && [self.dataSource managerIsToxConnected]) {
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
    // KHANDAQ (#H4): the minusSet pop below drains addedNodes, so after ~2 iterations it ran dry and
    // bootstrap gave up ~7s into launch (a big first-connect gap after an update). Re-seed the predefined
    // nodes when the working set empties so bootstrap keeps cycling the full reliable list until actually
    // connected. (The iterate loop only calls this while trying to connect, so it can't spin once online.)
    BOOL empty;
    @synchronized(self.addedNodes) {
        empty = (self.addedNodes.count == 0);
    }
    if (empty) {
        [self reloadPredefinedNodes];
    }

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
