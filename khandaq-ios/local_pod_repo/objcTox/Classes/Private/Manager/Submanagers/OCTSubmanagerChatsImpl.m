// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTSubmanagerChatsImpl.h"
#import "OCTSubmanagerFriends.h"
#import "OCTSubmanagerFiles.h"
#import "OCTTox.h"
#import "OCTRealmManager.h"
#import "OCTMessageAbstract.h"
#import "OCTMessageText.h"
#import "OCTMessageFile.h"
#import "OCTChat.h"
#import "OCTFriend.h"
#import "OCTLogging.h"
#import "OCTSendMessageOperation.h"
#import "OCTTox+Private.h"
#import "OCTToxOptions+Private.h"
#import <CommonCrypto/CommonHMAC.h>
#import <Security/Security.h>
#if __has_include(<Firebase/Firebase.h>)
#import <Firebase/Firebase.h>
#define OCTHasFirebase 1
#else
#define OCTHasFirebase 0
#endif

static void triggerPush(NSString *used_pushToken,
                        NSString *msgv3HashHex,
                        OCTSubmanagerChatsImpl *strongSelf,
                        OCTChat *chat);
static NSString *khandaqAppendRelayAuth(NSString *baseURL);
int bin_to_hex(const char *bin_id, size_t bin_id_size, char *output);

#pragma mark - KHANDAQ (audit A47) push.khandaq.org certificate pinning

// Pin the push wake endpoint to the STABLE Let's Encrypt trust anchors (ISRG Root X1 + X2) using
// Apple's own trust evaluator restricted to those anchors -- no hand-rolled SPKI/ASN.1 that could be
// miscomputed. Defensive by design: any INTERNAL problem (embedded roots fail to load, no server
// trust, host mismatch, or past the hard expiry) FALLS BACK to default validation, so an
// implementation bug can never brick legitimate push; only a genuine cert substitution that fails to
// chain to the ISRG roots is rejected. The roots (valid to 2035) survive routine LE leaf/intermediate
// rotation. Refresh the embedded roots + expiry in a client release BEFORE the expiry date.
static NSString *const kKhandaqPushPinnedHost = @"push.khandaq.org";
// ISRG Root X1 (RSA 4096), SHA-256 fp 96:BC:EC:...:08:C6
static NSString *const kKhandaqISRGRootX1B64 = @"MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAwTzELMAkGA1UEBhMCVVMxKTAnBgNVBAoTIEludGVybmV0IFNlY3VyaXR5IFJlc2VhcmNoIEdyb3VwMRUwEwYDVQQDEwxJU1JHIFJvb3QgWDEwHhcNMTUwNjA0MTEwNDM4WhcNMzUwNjA0MTEwNDM4WjBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMTCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBAK3oJHP0FDfzm54rVygch77ct984kIxuPOZXoHj3dcKi/vVqbvYATyjb3miGbESTtrFj/RQSa78f0uoxmyF+0TM8ukj13Xnfs7j/EvEhmkvBioZxaUpmZmyPfjxwv60pIgbz5MDmgK7iS4+3mX6UA5/TR5d8mUgjU+g4rk8Kb4Mu0UlXjIB0ttov0DiNewNwIRt18jA8+o+u3dpjq+sWT8KOEUt+zwvo/7V3LvSye0rgTBIlDHCNAymg4VMk7BPZ7hm/ELNKjD+Jo2FR3qyHB5T0Y3HsLuJvW5iB4YlcNHlsdu87kGJ55tukmi8mxdAQ4Q7e2RCOFvu396j3x+UCB5iPNgiV5+I3lg02dZ77DnKxHZu8A/lJBdiB3QW0KtZB6awBdpUKD9jf1b0SHzUvKBds0pjBqAlkd25HN7rOrFleaJ1/ctaJxQZBKT5ZPt0m9STJEadao0xAH0ahmbWnOlFuhjuefXKnEgV4We0+UXgVCwOPjdAvBbI+e0ocS3MFEvzG6uBQE3xDk3SzynTnjh8BCNAw1FtxNrQHusEwMFxIt4I7mKZ9YIqioymCzLq9gwQbooMDQaHWBfEbwrbwqHyGO0aoSCqI3Haadr8faqU9GY/rOPNk3sgrDQoo//fb4hVC1CLQJ13hef4Y53CIrU7m2Ys6xt0nUW7/vGT1M0NPAgMBAAGjQjBAMA4GA1UdDwEB/wQEAwIBBjAPBgNVHRMBAf8EBTADAQH/MB0GA1UdDgQWBBR5tFnme7bl5AFzgAiIyBpY9umbbjANBgkqhkiG9w0BAQsFAAOCAgEAVR9YqbyyqFDQDLHYGmkgJykIrGF1XIpu+ILlaS/V9lZLubhzEFnTIZd+50xx+7LSYK05qAvqFyFWhfFQDlnrzuBZ6brJFe+GnY+EgPbk6ZGQ3BebYhtF8GaV0nxvwuo77x/Py9auJ/GpsMiu/X1+mvoiBOv/2X/qkSsisRcOj/KKNFtY2PwByVS5uCbMiogziUwthDyC3+6WVwW6LLv3xLfHTjuCvjHIInNzktHCgKQ5ORAzI4JMPJ+GslWYHb4phowim57iaztXOoJwTdwJx4nLCgdNbOhdjsnvzqvHu7UrTkXWStAmzOVyyghqpZXjFaH3pO3JLF+l+/+sKAIuvtd7u+Nxe5AW0wdeRlN8NwdCjNPElpzVmbUq4JUagEiuTDkHzsxHpFKVK7q4+63SM1N95R1NbdWhscdCb+ZAJzVcoyi3B43njTOQ5yOf+1CceWxG1bQVs5ZufpsMljq4Ui0/1lvh+wjChP4kqKOJ2qxq4RgqsahDYVvTH9w7jXbyLeiNdd8XM2w9U/t7y0Ff/9yi0GE44Za4rF2LN9d11TPAmRGunUHBcnWEvgJBQl9nJEiU0Zsnvgc/ubhPgXRR4Xq37Z0j4r7g1SgEEzwxA57demyPxgcYxn/eR44/KJ4EBs+lVDR3veyJm+kXQ99b21/+jh5Xos1AnX5iItreGCc=";
// ISRG Root X2 (ECDSA P-384), SHA-256 fp 69:72:9B:...:14:70
static NSString *const kKhandaqISRGRootX2B64 = @"MIICGzCCAaGgAwIBAgIQQdKd0XLq7qeAwSxs6S+HUjAKBggqhkjOPQQDAzBPMQswCQYDVQQGEwJVUzEpMCcGA1UEChMgSW50ZXJuZXQgU2VjdXJpdHkgUmVzZWFyY2ggR3JvdXAxFTATBgNVBAMTDElTUkcgUm9vdCBYMjAeFw0yMDA5MDQwMDAwMDBaFw00MDA5MTcxNjAwMDBaME8xCzAJBgNVBAYTAlVTMSkwJwYDVQQKEyBJbnRlcm5ldCBTZWN1cml0eSBSZXNlYXJjaCBHcm91cDEVMBMGA1UEAxMMSVNSRyBSb290IFgyMHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEzZvVn4CDCuwJSvMWSj5cz3es3mcFDR0HttwW+1qLFNvicWDEukWVEYmO6gbf9yoWHKS5xcUy4APgHoIYOIvXRdgKam7mAHf7AlF9ItgKbppbd9/w+kHsOdx1ymgHDB/qo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUfEKWrt5LSDv6kviejM9ti6lyN5UwCgYIKoZIzj0EAwMDaAAwZQIwe3lORlCEwkSHRhtFcP9Ymd70/aTSVaYgLXTWNLxBo1BfASdWtL4ndQavEi51mI38AjEAi/V3bNTIZargCyzuFJ0nN6T5U6VR5CmD1/iQMVtCnwr1/q4AaOeMSQ+2b1tbFfLn";

@interface KhandaqPushPinningDelegate : NSObject <NSURLSessionDelegate>
@end

@implementation KhandaqPushPinningDelegate

+ (NSArray *)anchors
{
    static NSArray *anchors = nil;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSMutableArray *certs = [NSMutableArray array];
        for (NSString *b64 in @[kKhandaqISRGRootX1B64, kKhandaqISRGRootX2B64]) {
            NSData *der = [[NSData alloc] initWithBase64EncodedString:b64 options:0];
            if (der == nil) { continue; }
            SecCertificateRef cert = SecCertificateCreateWithData(NULL, (__bridge CFDataRef)der);
            if (cert != NULL) { [certs addObject:(__bridge_transfer id)cert]; }
        }
        // Require BOTH roots to parse; otherwise keep anchors nil so we fail OPEN (never brick).
        anchors = (certs.count == 2) ? [certs copy] : nil;
    });
    return anchors;
}

+ (BOOL)pinningActive
{
    static NSDate *expiry = nil;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSDateComponents *c = [[NSDateComponents alloc] init];
        c.year = 2027; c.month = 8; c.day = 1;
        NSCalendar *cal = [NSCalendar calendarWithIdentifier:NSCalendarIdentifierGregorian];
        cal.timeZone = [NSTimeZone timeZoneWithAbbreviation:@"UTC"];
        expiry = [cal dateFromComponents:c];
    });
    return (expiry != nil) && ([[NSDate date] compare:expiry] == NSOrderedAscending);
}

- (void)URLSession:(NSURLSession *)session
didReceiveChallenge:(NSURLAuthenticationChallenge *)challenge
 completionHandler:(void (^)(NSURLSessionAuthChallengeDisposition, NSURLCredential *))completionHandler
{
    SecTrustRef trust = challenge.protectionSpace.serverTrust;
    NSArray *anchors = [KhandaqPushPinningDelegate anchors];
    // Fail OPEN (default handling) unless this really is a server-trust challenge for our pinned
    // host, pinning is still in its validity window, and our anchor set loaded.
    if (trust == NULL || anchors == nil ||
        ![challenge.protectionSpace.authenticationMethod isEqualToString:NSURLAuthenticationMethodServerTrust] ||
        ![challenge.protectionSpace.host isEqualToString:kKhandaqPushPinnedHost] ||
        ![KhandaqPushPinningDelegate pinningActive]) {
        completionHandler(NSURLSessionAuthChallengePerformDefaultHandling, nil);
        return;
    }
    // Restrict evaluation to ONLY the ISRG roots and let Apple validate the chain.
    SecTrustSetAnchorCertificates(trust, (__bridge CFArrayRef)anchors);
    SecTrustSetAnchorCertificatesOnly(trust, true);
    CFErrorRef error = NULL;
    bool valid = SecTrustEvaluateWithError(trust, &error);
    if (error != NULL) { CFRelease(error); }
    if (valid) {
        completionHandler(NSURLSessionAuthChallengeUseCredential,
                          [NSURLCredential credentialForTrust:trust]);
    } else {
        completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, nil);
    }
}

@end

// Shared pinned session for push.khandaq.org wake pings (delegate retained by the session).
static NSURLSession *khandaqPinnedPushSession(void)
{
    static NSURLSession *session = nil;
    static dispatch_once_t once;
    dispatch_once(&once, ^{
        NSURLSessionConfiguration *cfg = [NSURLSessionConfiguration ephemeralSessionConfiguration];
        session = [NSURLSession sessionWithConfiguration:cfg
                                                delegate:[[KhandaqPushPinningDelegate alloc] init]
                                           delegateQueue:nil];
    });
    return session;
}

@interface OCTSubmanagerChatsImpl ()

@property (strong, nonatomic, readonly) NSOperationQueue *sendMessageQueue;

@end

@implementation OCTSubmanagerChatsImpl
@synthesize dataSource = _dataSource;

- (instancetype)init
{
    self = [super init];

    if (! self) {
        return nil;
    }

    _sendMessageQueue = [NSOperationQueue new];
    _sendMessageQueue.maxConcurrentOperationCount = 1;

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
}

#pragma mark -  Public

- (OCTChat *)getOrCreateChatWithFriend:(OCTFriend *)friend
{
    return [[self.dataSource managerGetRealmManager] getOrCreateChatWithFriend:friend];
}

- (void)removeMessages:(NSArray<OCTMessageAbstract *> *)messages
{
    [[self.dataSource managerGetRealmManager] removeMessages:messages];
    [self.dataSource.managerGetNotificationCenter postNotificationName:kOCTScheduleFileTransferCleanupNotification object:nil];
}

- (void)removeAllMessagesInChat:(OCTChat *)chat removeChat:(BOOL)removeChat
{
    [[self.dataSource managerGetRealmManager] removeAllMessagesInChat:chat removeChat:removeChat];
    [self.dataSource.managerGetNotificationCenter postNotificationName:kOCTScheduleFileTransferCleanupNotification object:nil];
}

- (void)sendOwnPush
{
    NSLog(@"PUSH:sendOwnPush");
#if OCTHasFirebase
    if ([FIRApp defaultApp] == nil) {
        NSLog(@"PUSH:sendOwnPush:firebase not configured");
        return;
    }
    NSString *token = [FIRMessaging messaging].FCMToken;
    if (token.length > 0) {
        NSString *my_pushToken = [NSString stringWithFormat:@"https://push.khandaq.org/toxfcm/fcm.php?id=%@&type=1", token];
        triggerPush(my_pushToken, nil, nil, nil);
    } else {
        NSLog(@"PUSH:sendOwnPush:no token");
    }
#else
    NSLog(@"PUSH:sendOwnPush:firebase not configured");
#endif
}

- (void)broadcastOwnPushURLToConnectedFriends
{
    NSLog(@"PUSH:broadcastOwnPushURLToConnectedFriends");
#if OCTHasFirebase
    if ([FIRApp defaultApp] == nil) {
        NSLog(@"PUSH:broadcastOwnPushURL:firebase not configured");
        return;
    }
    NSString *token = [FIRMessaging messaging].FCMToken;
    if (token.length == 0) {
        NSLog(@"PUSH:broadcastOwnPushURL:no token");
        return;
    }

    OCTTox *tox = [self.dataSource managerGetTox];
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    RLMResults *friends = [realmManager objectsWithClass:[OCTFriend class] predicate:nil];
    NSString *data = [NSString stringWithFormat:@"Ahttps://push.khandaq.org/toxfcm/fcm.php?id=%@&type=1", token];

    for (OCTFriend *friend in friends) {
        if (!friend.isConnected) {
            continue;
        }
        NSError *error = nil;
        [tox sendLosslessPacketWithFriendNumber:friend.friendNumber
                                          pktid:181
                                           data:data
                                          error:&error];
    }
#else
    NSLog(@"PUSH:broadcastOwnPushURL:firebase not configured");
#endif
}

- (void)triggerWakePushForChat:(OCTChat *)chat
{
    NSParameterAssert(chat);

    OCTFriend *friend = [chat.friends firstObject];
    if (friend.pushToken.length == 0) {
        return;
    }

    triggerPush(friend.pushToken, nil, self, chat);
}

- (void)sendMessagePushToChat:(OCTChat *)chat
{
    NSParameterAssert(chat);
    NSLog(@"PUSH:sendMessagePushToChat");
    __weak OCTSubmanagerChatsImpl *weakSelf = self;
    dispatch_async(dispatch_get_main_queue(), ^{
        __strong OCTSubmanagerChatsImpl *strongSelf = weakSelf;
        OCTRealmManager *realmManager = [strongSelf.dataSource managerGetRealmManager];

        OCTFriend *friend = [chat.friends firstObject];
        __block NSString *friend_pushToken = friend.pushToken;

        if (friend_pushToken == nil)
        {
            NSLog(@"sendMessagePushToChat:Friend has No Pushtoken");
        }
        else
        {
            // HINT: only select outgoing messages (senderUniqueIdentifier == NULL)
            NSPredicate *predicate = [NSPredicate predicateWithFormat:@"chatUniqueIdentifier == %@ AND messageText.isDelivered == 0 AND messageText.sentPush == 0 AND senderUniqueIdentifier == nil", chat.uniqueIdentifier];

            RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
            OCTMessageAbstract *message_found = [results firstObject];
            if (message_found) {

                [realmManager updateObject:message_found withBlock:^(OCTMessageAbstract *theMessage) {
                    theMessage.messageText.sentPush = YES;
                }];
                triggerPush(friend_pushToken, message_found.messageText.msgv3HashHex, strongSelf, chat);
            }
        }
    });
}

// KHANDAQ (security NEW-2 / NEW-5): wire the iOS push to the replay-resistant relay auth.
// Mirror byte-for-byte the server (infra/push/relay/app.py _auth_ok) and Android
// (KhandaqPush.withWakeParams): the sender signs the recipient token (id) + sender (from) +
// a unix timestamp (ts), so the auth value differs per request and expires (server enforces a
// freshness window) — it cannot be replayed. Computed here, per request, because the actual
// push URL is built in this pod (the Swift KhandaqPush helper lives in the app target and is
// unreachable from objcTox). Secret comes from Info.plist KhandaqPushRelayAuthSecret; empty =
// dormant (append nothing), matching production where relay auth is still OFF.
//   ts   = unix seconds (integer string)
//   msg  = id + "\n" + from + "\n" + ts   (raw, URL-decoded values, UTF-8)
//   auth = lowercase hex HMAC-SHA256(secret, msg)
static NSString *khandaqAppendRelayAuth(NSString *baseURL)
{
    if (baseURL == nil) {
        return baseURL;
    }
    NSString *secret = [[NSBundle mainBundle] objectForInfoDictionaryKey:@"KhandaqPushRelayAuthSecret"];
    if (secret == nil || secret.length == 0) {
        return baseURL; // dormant until a secret is provisioned
    }
    // Only sign our own relay, and never double-sign.
    if (![baseURL containsString:@"push.khandaq.org"] || [baseURL containsString:@"auth="]) {
        return baseURL;
    }

    NSURLComponents *components = [NSURLComponents componentsWithString:baseURL];
    NSString *idValue = @"";
    NSString *fromValue = @"";
    for (NSURLQueryItem *item in components.queryItems) {
        if ([item.name isEqualToString:@"id"]) {
            idValue = item.value ?: @"";
        } else if ([item.name isEqualToString:@"from"]) {
            fromValue = item.value ?: @"";
        }
    }

    long long tsInt = (long long)[[NSDate date] timeIntervalSince1970];
    NSString *ts = [NSString stringWithFormat:@"%lld", tsInt];

    NSString *message = [NSString stringWithFormat:@"%@\n%@\n%@", idValue, fromValue, ts];
    NSData *keyData = [secret dataUsingEncoding:NSUTF8StringEncoding];
    NSData *msgData = [message dataUsingEncoding:NSUTF8StringEncoding];
    unsigned char digest[CC_SHA256_DIGEST_LENGTH];
    CCHmac(kCCHmacAlgSHA256, keyData.bytes, keyData.length, msgData.bytes, msgData.length, digest);

    NSMutableString *authHex = [NSMutableString stringWithCapacity:CC_SHA256_DIGEST_LENGTH * 2];
    for (int i = 0; i < CC_SHA256_DIGEST_LENGTH; i++) {
        [authHex appendFormat:@"%02x", digest[i]];
    }

    return [NSString stringWithFormat:@"%@&ts=%@&auth=%@", baseURL, ts, authHex];
}

static void triggerPush(NSString *used_pushToken,
                        NSString *msgv3HashHex,
                        OCTSubmanagerChatsImpl *strongSelf,
                        OCTChat *chat)
{
    // HINT: call push token (URL) here
    //       best in a background thread
    //
    NSLog(@"PUSH:triggerPush");
    if ((used_pushToken != nil) && (used_pushToken.length > 5)) {

        // Strict push URL whitelist (security audit #10)
        if (
            ([used_pushToken hasPrefix:@"https://push.khandaq.org/toxfcm/fcm.php?id="] &&
             [used_pushToken containsString:@"id="])
            ||
            ([used_pushToken hasPrefix:@"https://tox.zoff.xyz/toxfcm/fcm.php?id="] &&
             [used_pushToken containsString:@"id="])
        ) {

            NSString *strong_pushToken = used_pushToken;
            if ((strongSelf != nil) && ([strong_pushToken containsString:@"fcm.php?id="]) && (![strong_pushToken containsString:@"from="])) {
                NSString *selfPublicKey = [[strongSelf.dataSource managerGetTox] publicKey];
                if ((selfPublicKey != nil) && (selfPublicKey.length > 0)) {
                    strong_pushToken = [NSString stringWithFormat:@"%@%@from=%@",
                        strong_pushToken,
                        ([strong_pushToken containsString:@"?"] ? @"&" : @"?"),
                        selfPublicKey];
                }
            }

            dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT, 0), ^{
                // NSString *strong_pushToken = weak_pushToken;
                int PUSH_URL_TRIGGER_AGAIN_MAX_COUNT = 8;
                int PUSH_URL_TRIGGER_AGAIN_SECONDS = 21;

                for (int i=0; i<(PUSH_URL_TRIGGER_AGAIN_MAX_COUNT + 1); i++)
                {
                    if (chat == nil) {
                        __block UIApplicationState as = UIApplicationStateBackground;
                        dispatch_sync(dispatch_get_main_queue(), ^{
                            as =[[UIApplication sharedApplication] applicationState];
                        });

                        if (as == UIApplicationStateActive) {
                            NSLog(@"PUSH:fg->break:1");
                            break;
                        }
                    }
                    // Sign per-request with a fresh timestamp (resends happen up to ~3 min apart).
                    NSString *signed_pushToken = khandaqAppendRelayAuth(strong_pushToken);
                    NSMutableURLRequest *urlRequest = [[NSMutableURLRequest alloc] initWithURL:[NSURL URLWithString:signed_pushToken]];
                    NSString *userUpdate = [NSString stringWithFormat:@"&text=1", nil];
                    [urlRequest setHTTPMethod:@"POST"];

                    NSData *data1 = [userUpdate dataUsingEncoding:NSUTF8StringEncoding];

                    [urlRequest setHTTPBody:data1];
                    [urlRequest setCachePolicy:NSURLRequestReloadIgnoringCacheData];
                    [urlRequest setTimeoutInterval:10]; // HINT: 10 seconds
                    NSString *userAgent = @"Mozilla/5.0 (Windows NT 6.1; rv:60.0) Gecko/20100101 Firefox/60.0";
                    [urlRequest setValue:userAgent forHTTPHeaderField:@"User-Agent"];
                    [urlRequest setValue:@"no-cache, no-store, must-revalidate" forHTTPHeaderField:@"Cache-Control"];
                    [urlRequest setValue:@"no-cache" forHTTPHeaderField:@"Pragma"];
                    [urlRequest setValue:@"0" forHTTPHeaderField:@"Expires"];

                    // NSLog(@"PUSH:for msgv3HashHex=%@", msgv3HashHex);
                    // NSLog(@"PUSH:for friend.pushToken=%@", strong_pushToken);

                    NSURLSession *session = khandaqPinnedPushSession();
                    NSURLSessionDataTask *dataTask = [session dataTaskWithRequest:urlRequest completionHandler:^(NSData *data, NSURLResponse *response, NSError *error) {
                        NSHTTPURLResponse *httpResponse = (NSHTTPURLResponse *)response;
                        if ((httpResponse.statusCode < 300) && (httpResponse.statusCode > 199)) {
                            NSLog(@"calling PUSH URL:CALL:SUCCESS");
                        }
                        else {
                            NSLog(@"calling PUSH URL:-ERROR:01-");
                        }
                    }];
                    NSLog(@"calling PUSH URL:CALL:start");
                    [dataTask resume];
                    if (i < PUSH_URL_TRIGGER_AGAIN_MAX_COUNT)
                    {
                        NSLog(@"calling PUSH URL:WAIT:start");
                        [NSThread sleepForTimeInterval:PUSH_URL_TRIGGER_AGAIN_SECONDS];
                        NSLog(@"calling PUSH URL:WAIT:done");
                    }

                    if (chat == nil) {
                        __block UIApplicationState as = UIApplicationStateBackground;
                        dispatch_sync(dispatch_get_main_queue(), ^{
                            as =[[UIApplication sharedApplication] applicationState];
                        });

                        if (as == UIApplicationStateActive) {
                            NSLog(@"PUSH:fg->break:2");
                            break;
                        }
                    }

                    if (msgv3HashHex != nil)
                    {
                        OCTRealmManager *realmManager = [strongSelf.dataSource managerGetRealmManager];
                        __block BOOL msgIsDelivered = NO;

                        NSLog(@"calling PUSH URL:DB check:start");
                        dispatch_sync(dispatch_get_main_queue(), ^{
                            // HINT: only select outgoing messages (senderUniqueIdentifier == NULL)
                            NSPredicate *predicate = [NSPredicate predicateWithFormat:@"chatUniqueIdentifier == %@ AND messageText.msgv3HashHex == %@ AND senderUniqueIdentifier == nil",
                                                      chat.uniqueIdentifier, msgv3HashHex];
                            RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
                            OCTMessageAbstract *message_found = [results firstObject];
                            if (message_found) {
                                if (message_found.messageText) {
                                    msgIsDelivered = message_found.messageText.isDelivered;
                                }
                            }
                            NSLog(@"calling PUSH URL:DB check:end_real");
                        });
                        NSLog(@"calling PUSH URL:DB check:end");

                        if (msgIsDelivered == YES) {
                                // OCTLogInfo(@"PUSH:for msgv3HashHex isDelivered=YES");
                                NSLog(@"PUSH:for msgv3HashHex isDelivered=YES");
                                break;
                        }
                    }
                }
            });
        }
        else {
            NSLog(@"unsafe PUSH URL not allowed:-ERROR:02-");
        }
    }
}

- (void)sendMessageToChat:(OCTChat *)chat
                     text:(NSString *)text
                     type:(OCTToxMessageType)type
             successBlock:(void (^)(OCTMessageAbstract *message))userSuccessBlock
             failureBlock:(void (^)(NSError *error))userFailureBlock
{
    NSParameterAssert(chat);
    NSParameterAssert(text);

    OCTFriend *friend = [chat.friends firstObject];

    uint8_t *message_v3_hash_bin = calloc(1, TOX_MSGV3_MSGID_LENGTH);
    uint8_t *message_v3_hash_hexstr = calloc(1, (TOX_MSGV3_MSGID_LENGTH * 2) + 1);

    NSString *msgv3HashHex = nil;
    UInt32 msgv3tssec = 0;

    if ((message_v3_hash_bin) && (message_v3_hash_hexstr))
    {
        tox_messagev3_get_new_message_id(message_v3_hash_bin);
        bin_to_hex((const char *)message_v3_hash_bin, (size_t)TOX_MSGV3_MSGID_LENGTH, message_v3_hash_hexstr);

        msgv3HashHex = [[NSString alloc] initWithBytes:message_v3_hash_hexstr length:(TOX_MSGV3_MSGID_LENGTH * 2) encoding:NSUTF8StringEncoding];

        // HINT: set sent timestamp to now() as unixtimestamp value
        msgv3tssec = [[NSNumber numberWithDouble: [[NSDate date] timeIntervalSince1970]] integerValue];

        free(message_v3_hash_bin);
        free(message_v3_hash_hexstr);
     }

    __weak OCTSubmanagerChatsImpl *weakSelf = self;
    OCTSendMessageOperationSuccessBlock successBlock = ^(OCTToxMessageId messageId) {
        __strong OCTSubmanagerChatsImpl *strongSelf = weakSelf;

        BOOL sent_push = NO;

        if (messageId == -1) {
            if ((friend.pushToken != nil) && (friend.pushToken.length > 5)) {

                // KHANDAQ (security audit NEW-6): keep this list in lock-step with the strict send
                // whitelist in triggerPush(). triggerPush only ever sends to push.khandaq.org /
                // tox.zoff.xyz, so marking a message as "push sent" for gotify/ntfy friends was a lie —
                // the push was never delivered. Only flag sent_push for hosts we actually push to.
                if (
                    ([friend.pushToken hasPrefix:@"https://push.khandaq.org/toxfcm/fcm.php?id="])
                    ||
                    ([friend.pushToken hasPrefix:@"https://tox.zoff.xyz/toxfcm/fcm.php?id="])
                ) {
                    sent_push = YES;
                }
            }
        }

        OCTRealmManager *realmManager = [strongSelf.dataSource managerGetRealmManager];
        OCTMessageAbstract *message = [realmManager addMessageWithText:text type:type chat:chat sender:nil messageId:messageId msgv3HashHex:msgv3HashHex sentPush:sent_push tssent:msgv3tssec tsrcvd:0];

        if (userSuccessBlock) {
            userSuccessBlock(message);
        }
    };

    OCTSendMessageOperationFailureBlock failureBlock = ^(NSError *error) {
        __strong OCTSubmanagerChatsImpl *strongSelf = weakSelf;

        if ((error.code == OCTToxErrorFriendSendMessageFriendNotConnected) &&
            [strongSelf.dataSource managerUseFauxOfflineMessaging]) {
            NSString *friend_pushToken = friend.pushToken;
            triggerPush(friend_pushToken, msgv3HashHex, strongSelf, chat);
            successBlock(-1);
            return;
        }

        if (userFailureBlock) {
            userFailureBlock(error);
        }
    };

    OCTSendMessageOperation *operation = [[OCTSendMessageOperation alloc] initWithTox:[self.dataSource managerGetTox]
                                                                         friendNumber:friend.friendNumber
                                                                          messageType:type
                                                                              message:text
                                                                         msgv3HashHex:msgv3HashHex
                                                                           msgv3tssec:msgv3tssec
                                                                         successBlock:successBlock
                                                                         failureBlock:failureBlock];
    [self.sendMessageQueue addOperation:operation];
}

- (BOOL)setIsTyping:(BOOL)isTyping inChat:(OCTChat *)chat error:(NSError **)error
{
    NSParameterAssert(chat);

    OCTFriend *friend = [chat.friends firstObject];
    OCTTox *tox = [self.dataSource managerGetTox];

    return [tox setUserIsTyping:isTyping forFriendNumber:friend.friendNumber error:error];
}

#pragma mark -  NSNotification

- (void)friendConnectionStatusChangeNotification:(NSNotification *)notification
{
    OCTFriend *friend = notification.object;

    if (! friend) {
        OCTLogWarn(@"no friend received in notification %@, exiting", notification);
        return;
    }

    if (friend.isConnected) {
        [self resendUndeliveredMessagesToFriend:friend];
        // KHANDAQ (#179): deliver delete-for-both retractions that were queued while this friend
        // was offline (mirrors Android's flushPendingDeletesForFriend on reconnect).
        [self flushPendingDeletesForFriend:friend];
        // KHANDAQ (#192): deliver reaction changes made while this friend was offline
        [self flushPendingReactionsForFriend:friend];
        // KHANDAQ (#208): deliver edits made while this friend was offline
        [self flushPendingEditsForFriend:friend];
    }
}

- (void)tickDeliveryWatchdog
{
    if (! [self.dataSource managerIsToxConnected]) {
        return;
    }

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    RLMResults *friends = [OCTFriend allObjectsInRealm:realmManager.realm];

    for (OCTFriend *friend in friends) {
        if (friend.isConnected) {
            [self resendUndeliveredMessagesToFriend:friend];
            // KHANDAQ (#179): retry queued deletions too — covers half-dead links where the
            // initial send "succeeded" into a dying connection.
            [self flushPendingDeletesForFriend:friend];
            // KHANDAQ (#192): retry pending reaction deliveries too
            [self flushPendingReactionsForFriend:friend];
            // KHANDAQ (#208): retry pending edit deliveries too
            [self flushPendingEditsForFriend:friend];
        }
    }
}

#pragma mark -  Private

- (void)resendUndeliveredMessagesToFriend:(OCTFriend *)friend
{
    // KHANDAQ: flush queued files/voice BEFORE queued texts. The receiver pins a file bubble's
    // position (dateInterval) at file_recv REQUEST arrival — not at accept/complete — and toxcore
    // delivers lossless packets in-order, so issuing queued file_send requests first preserves the
    // original send order (tester report: offline voice arrived AFTER later texts). tox_file_send
    // returns immediately; texts are delayed by microseconds, not by the transfer itself.
    [[self.dataSource managerGetFiles] resendPendingOutgoingFilesToAllOnlineFriends];

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"chatUniqueIdentifier == %@"
                              @" AND senderUniqueIdentifier == nil"
                              @" AND messageText.isDelivered == NO",
                              chat.uniqueIdentifier];

    RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];

    // KHANDAQ: preserve intra-batch order — RLMResults is unsorted by default
    results = [results sortedResultsUsingKeyPath:@"dateInterval" ascending:YES];

    for (OCTMessageAbstract *message in results) {
        OCTLogInfo(@"Resending message to friend %@", friend);

        __weak OCTSubmanagerChatsImpl *weakSelf = self;
        OCTSendMessageOperationSuccessBlock successBlock = ^(OCTToxMessageId messageId) {
            __strong OCTSubmanagerChatsImpl *strongSelf = weakSelf;

            OCTRealmManager *realmManager = [strongSelf.dataSource managerGetRealmManager];

            [realmManager updateObject:message withBlock:^(OCTMessageAbstract *theMessage) {
                theMessage.messageText.messageId = messageId;
            }];
        };

        OCTSendMessageOperationFailureBlock failureBlock = ^(NSError *error) {
            OCTLogWarn(@"Cannot resend message to friend %@, error %@", friend, error);
        };

        OCTSendMessageOperation *operation = [[OCTSendMessageOperation alloc] initWithTox:[self.dataSource managerGetTox]
                                                                             friendNumber:friend.friendNumber
                                                                              messageType:message.messageText.type
                                                                                  message:message.messageText.text
                                                                             msgv3HashHex:message.messageText.msgv3HashHex
                                                                               msgv3tssec:message.tssent
                                                                             successBlock:successBlock
                                                                             failureBlock:failureBlock];
        [self.sendMessageQueue addOperation:operation];
    }
}

#pragma mark -  OCTToxDelegate

/*
 * send mesgV3 high level ACK message.
 */
- (void)tox:(OCTTox *)tox sendFriendHighlevelACK:(NSString *)message
                                    friendNumber:(OCTToxFriendNumber)friendNumber
                                    msgv3HashHex:(NSString *)msgv3HashHex
                                   sendTimestamp:(uint32_t)sendTimestamp
{
    OCTSendMessageOperation *operation = [[OCTSendMessageOperation alloc] initWithTox:[self.dataSource managerGetTox]
                                                                         friendNumber:friendNumber
                                                                          messageType:OCTToxMessageTypeHighlevelack
                                                                              message:message
                                                                         msgv3HashHex:msgv3HashHex
                                                                           msgv3tssec:sendTimestamp
                                                                         successBlock:nil
                                                                         failureBlock:nil];
    [self.sendMessageQueue addOperation:operation];
}

/*
 * Process incoming text message from friend.
 */
- (void)tox:(OCTTox *)tox friendMessage:(NSString *)message
                                   type:(OCTToxMessageType)type
                           friendNumber:(OCTToxFriendNumber)friendNumber
                           msgv3HashHex:(NSString *)msgv3HashHex
                           sendTimestamp:(uint32_t)sendTimestamp
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    id<OCTSubmanagerFriends> friendsSubmanager = [self.dataSource managerGetFriends];
    OCTFriend *friend = [friendsSubmanager ensureFriendForFriendNumber:friendNumber];

    if (! friend) {
        OCTLogWarn(@"friendMessage ignoring message from unresolved friendNumber %d", friendNumber);
        return;
    }

    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    if (msgv3HashHex != nil)
    {
        // KHANDAQ (dup fix): dedup by hash across BOTH directions — a copy matching one of OUR OWN
        // outgoing hashes (echo/relay) must be ignored too (Android-parity, HelperGeneric).
        NSPredicate *predicate = [NSPredicate predicateWithFormat:@"chatUniqueIdentifier == %@ AND messageText.msgv3HashHex == %@",
                                  chat.uniqueIdentifier, msgv3HashHex];
        RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
        OCTMessageAbstract *message_found = [results firstObject];

        if (message_found) {
            OCTLogInfo(@"friendMessage ignoring double message i %@", chat.uniqueIdentifier);
            OCTLogInfo(@"friendMessage ignoring double message f %@", friend);
            return;
        }
    }

    [realmManager addMessageWithText:message type:type chat:chat sender:friend messageId:0 msgv3HashHex:msgv3HashHex sentPush:NO tssent:sendTimestamp tsrcvd:0];
}

// KHANDAQ (#179): the friend retracted one of their own messages (KQ delete packet, id 187).
- (void)tox:(OCTTox *)tox friendMessageDeleteWithMsgv3Hash:(NSString *)msgv3HashHex
                                              friendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];
    if (! friend || msgv3HashHex.length != 64) {
        return;
    }
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    // anti-spoofing: only a message this friend SENT us can be retracted by them
    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND messageText.msgv3HashHex == %@ AND senderUniqueIdentifier == %@",
                              chat.uniqueIdentifier, msgv3HashHex, friend.uniqueIdentifier];
    RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    OCTMessageAbstract *found = [results firstObject];
    if (! found) {
        return;
    }

    OCTLogInfo(@"friendMessageDelete: removing retracted message %@", msgv3HashHex);
    [self removeMessages:@[found]];
}

// KHANDAQ (#193): the friend retracted one of their own 1:1 FILE/media/voice messages (packet 187,
// anchor_type 2). Anchored by the symmetric tox file_id; anti-spoofed to a file this friend SENT us.
- (void)tox:(OCTTox *)tox friendMessageDeleteWithFileIdHex:(NSString *)fileIdHex
                                             friendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];
    if (! friend || fileIdHex.length != 64) {
        return;
    }
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND messageFile.fileIdHex ==[c] %@ AND senderUniqueIdentifier == %@",
                              chat.uniqueIdentifier, fileIdHex, friend.uniqueIdentifier];
    OCTMessageAbstract *found = [[realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate] firstObject];
    if (! found) {
        return;
    }

    OCTLogInfo(@"friendMessageDelete: removing retracted file message %@", fileIdHex);
    [self removeMessages:@[found]];
}

// KHANDAQ (#179): retract an OWN 1:1 text message on the peer too, then delete it locally.
// The retraction is NOT fire-and-forget anymore: if the peer's tox link is down (the common
// real-phone case — testers saw "deleted locally, peer keeps it"), the delete is stashed per
// friend and re-sent when the friend reconnects — mirroring Android's HelperMessageDelete queue.
static NSString *const kKQPendingDeletesKey = @"KQPendingFriendDeletes";

- (NSData *)kqDeletePacketWithHashHex:(NSString *)hashHex
{
    if (hashHex.length != 64) {
        return nil;
    }
    NSMutableData *pkt = [NSMutableData dataWithCapacity:40];
    const uint8_t header[4] = { 187, 'K', 'Q', 1 };
    [pkt appendBytes:header length:4];
    for (int i = 0; i < 64; i += 2) {
        unsigned int b = 0;
        [[NSScanner scannerWithString:[hashHex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&b];
        uint8_t byte = (uint8_t)b;
        [pkt appendBytes:&byte length:1];
    }
    uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970];
    const uint8_t tsbe[4] = {
        (uint8_t)((ts >> 24) & 0xFF), (uint8_t)((ts >> 16) & 0xFF),
        (uint8_t)((ts >> 8) & 0xFF), (uint8_t)(ts & 0xFF)
    };
    [pkt appendBytes:tsbe length:4];
    return pkt;
}

// KHANDAQ (#193): file/media/voice delete — 41 bytes: [0]=187 [1..2]='K','Q' [3]=ver(1)
// [4]=anchor_type(2=tox file_id) [5..36]=file_id 32B [37..40]=ts u32 BE. Text delete stays 40
// bytes (implicit anchor_type 1, hash at [4..35]) so it's byte-compatible with older clients.
- (NSData *)kqDeletePacketWithFileIdHex:(NSString *)fileIdHex
{
    if (fileIdHex.length != 64) {
        return nil;
    }
    NSMutableData *pkt = [NSMutableData dataWithCapacity:41];
    const uint8_t header[5] = { 187, 'K', 'Q', 1, 2 };
    [pkt appendBytes:header length:5];
    for (int i = 0; i < 64; i += 2) {
        unsigned int b = 0;
        [[NSScanner scannerWithString:[fileIdHex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&b];
        uint8_t byte = (uint8_t)b;
        [pkt appendBytes:&byte length:1];
    }
    uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970];
    const uint8_t tsbe[4] = {
        (uint8_t)((ts >> 24) & 0xFF), (uint8_t)((ts >> 16) & 0xFF),
        (uint8_t)((ts >> 8) & 0xFF), (uint8_t)(ts & 0xFF)
    };
    [pkt appendBytes:tsbe length:4];
    return pkt;
}

// KHANDAQ (#193): `hashHex` is a plain 64-char msgV3 hash (text) OR "F:"+fileIdHex (file/media).
- (void)stashPendingDeleteForFriendPK:(NSString *)publicKey hashHex:(NSString *)hashHex
{
    const BOOL isFileEntry = [hashHex hasPrefix:@"F:"];
    const NSUInteger anchorLen = isFileEntry ? (hashHex.length - 2) : hashHex.length;
    if (publicKey.length == 0 || anchorLen != 64) {
        return;
    }
    NSUserDefaults *ud = [NSUserDefaults standardUserDefaults];
    NSMutableDictionary *all = [[ud dictionaryForKey:kKQPendingDeletesKey] mutableCopy] ?: [NSMutableDictionary new];
    NSMutableArray *list = [all[publicKey] mutableCopy] ?: [NSMutableArray new];
    if (! [list containsObject:hashHex]) {
        if (list.count > 100) {
            [list removeAllObjects]; // cap like Android
        }
        [list addObject:hashHex];
    }
    all[publicKey] = list;
    [ud setObject:all forKey:kKQPendingDeletesKey];
    OCTLogInfo(@"deleteMessageForBoth: stashed pending delete for %@ (%lu queued)",
               publicKey, (unsigned long)list.count);
}

- (void)flushPendingDeletesForFriend:(OCTFriend *)friend
{
    if (friend.publicKey.length == 0) {
        return;
    }
    NSUserDefaults *ud = [NSUserDefaults standardUserDefaults];
    NSMutableDictionary *all = [[ud dictionaryForKey:kKQPendingDeletesKey] mutableCopy];
    NSArray *list = all[friend.publicKey];
    if (list.count == 0) {
        return;
    }
    OCTTox *tox = [self.dataSource managerGetTox];
    OCTToxFriendNumber friendNumber = [tox friendNumberWithPublicKey:friend.publicKey error:nil];
    if (friendNumber == kOCTToxFriendNumberFailure) {
        return;
    }
    NSMutableArray *remaining = [NSMutableArray new];
    for (NSString *hashHex in list) {
        NSData *pkt = [hashHex hasPrefix:@"F:"]
            ? [self kqDeletePacketWithFileIdHex:[hashHex substringFromIndex:2]]
            : [self kqDeletePacketWithHashHex:hashHex]; // fresh ts, like Android's flush
        if (pkt == nil) {
            continue;
        }
        if (! [tox sendLosslessPacketWithFriendNumber:friendNumber bytes:pkt error:nil]) {
            [remaining addObject:hashHex];
        }
    }
    OCTLogInfo(@"deleteMessageForBoth: flushed %lu pending deletes to %@ (%lu remain)",
               (unsigned long)(list.count - remaining.count), friend.publicKey,
               (unsigned long)remaining.count);
    if (remaining.count == 0) {
        [all removeObjectForKey:friend.publicKey];
    }
    else {
        all[friend.publicKey] = remaining;
    }
    [ud setObject:all forKey:kKQPendingDeletesKey];
}

// KHANDAQ (#192): Telegram-style message reactions ("KQ" lossless packet id 188, wire-compatible
// with Android's HelperMessageReaction). ONE reaction per actor per message; an add replaces the
// actor's previous emoji, a remove clears it (the remove packet's emoji is the "*" placeholder).
// Storage: OCTMessageAbstract.reactionsJSON = [{"e":"<emoji>","p":["-OWN-","<PUBKEY>",...]},...];
// own reactions use the "-OWN-" marker. Offline delivery: reactionsPending + reconnect flush
// rebuilt from the CURRENT own state, so repeated toggles collapse (like message edits).
static NSString *const kKQOwnReactionMarker = @"-OWN-";

static NSString *kqReactionsActorEmoji(NSString *reactionsJSON, NSString *actor)
{
    if (reactionsJSON.length == 0) {
        return nil;
    }
    NSArray *arr = [NSJSONSerialization JSONObjectWithData:[reactionsJSON dataUsingEncoding:NSUTF8StringEncoding]
                                                   options:0 error:nil];
    if (! [arr isKindOfClass:[NSArray class]]) {
        return nil;
    }
    for (NSDictionary *entry in arr) {
        if ([entry[@"p"] isKindOfClass:[NSArray class]] && [entry[@"p"] containsObject:actor]) {
            return entry[@"e"];
        }
    }
    return nil;
}

static NSString *kqReactionsApplyActor(NSString *reactionsJSON, NSString *actor, NSString *emoji, BOOL add)
{
    NSArray *arr = nil;
    if (reactionsJSON.length > 0) {
        arr = [NSJSONSerialization JSONObjectWithData:[reactionsJSON dataUsingEncoding:NSUTF8StringEncoding]
                                              options:0 error:nil];
    }
    if (! [arr isKindOfClass:[NSArray class]]) {
        arr = @[];
    }
    NSMutableArray *out = [NSMutableArray new];
    NSMutableDictionary *target = nil;
    for (NSDictionary *entry in arr) {
        if (! [entry isKindOfClass:[NSDictionary class]] || ! [entry[@"p"] isKindOfClass:[NSArray class]]) {
            continue;
        }
        NSMutableArray *kept = [NSMutableArray new];
        for (NSString *a in entry[@"p"]) {
            if (! [actor isEqualToString:a]) {
                [kept addObject:a];
            }
        }
        if (kept.count > 0) {
            NSMutableDictionary *ne = [@{ @"e" : entry[@"e"] ?: @"", @"p" : kept } mutableCopy];
            [out addObject:ne];
            if (add && [emoji isEqualToString:entry[@"e"]]) {
                target = ne;
            }
        }
    }
    if (add) {
        if (target == nil) {
            if (out.count >= 12) { // cap distinct emoji like Android
                goto serialize;
            }
            target = [@{ @"e" : emoji, @"p" : [NSMutableArray new] } mutableCopy];
            [out addObject:target];
        }
        NSMutableArray *actors = target[@"p"];
        if (actors.count < 128) {
            [actors addObject:actor];
        }
    }
serialize:
    if (out.count == 0) {
        return nil;
    }
    NSData *data = [NSJSONSerialization dataWithJSONObject:out options:0 error:nil];
    return data ? [[NSString alloc] initWithData:data encoding:NSUTF8StringEncoding] : nil;
}

- (NSData *)kqReactionPacketWithHashHex:(NSString *)hashHex emoji:(NSString *)emoji add:(BOOL)add
{
    NSData *em = [emoji dataUsingEncoding:NSUTF8StringEncoding];
    if (hashHex.length != 64 || em.length < 1 || em.length > 16) {
        return nil;
    }
    NSMutableData *pkt = [NSMutableData dataWithCapacity:43 + em.length];
    const uint8_t header[5] = { 188, 'K', 'Q', 1, 1 }; // ver 1, anchor_type 1 = msgv3 hash
    [pkt appendBytes:header length:5];
    for (int i = 0; i < 64; i += 2) {
        unsigned int b = 0;
        [[NSScanner scannerWithString:[hashHex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&b];
        uint8_t byte = (uint8_t)b;
        [pkt appendBytes:&byte length:1];
    }
    uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970];
    const uint8_t tail[4] = {
        (uint8_t)((ts >> 24) & 0xFF), (uint8_t)((ts >> 16) & 0xFF),
        (uint8_t)((ts >> 8) & 0xFF), (uint8_t)(ts & 0xFF)
    };
    [pkt appendBytes:tail length:4];
    const uint8_t action = add ? 1 : 0;
    [pkt appendBytes:&action length:1];
    const uint8_t emLen = (uint8_t)em.length;
    [pkt appendBytes:&emLen length:1];
    [pkt appendData:em];
    return pkt;
}

// KHANDAQ (#192): file/media/voice reaction packet — identical to the hash variant except
// anchor_type is 2 and bytes[5..36] carry the 32-byte symmetric tox file_id.
- (NSData *)kqReactionPacketWithFileIdHex:(NSString *)fileIdHex emoji:(NSString *)emoji add:(BOOL)add
{
    NSData *em = [emoji dataUsingEncoding:NSUTF8StringEncoding];
    if (fileIdHex.length != 64 || em.length < 1 || em.length > 16) {
        return nil;
    }
    NSMutableData *pkt = [NSMutableData dataWithCapacity:43 + em.length];
    const uint8_t header[5] = { 188, 'K', 'Q', 1, 2 }; // ver 1, anchor_type 2 = tox file_id
    [pkt appendBytes:header length:5];
    for (int i = 0; i < 64; i += 2) {
        unsigned int b = 0;
        [[NSScanner scannerWithString:[fileIdHex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&b];
        uint8_t byte = (uint8_t)b;
        [pkt appendBytes:&byte length:1];
    }
    uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970];
    const uint8_t tail[4] = {
        (uint8_t)((ts >> 24) & 0xFF), (uint8_t)((ts >> 16) & 0xFF),
        (uint8_t)((ts >> 8) & 0xFF), (uint8_t)(ts & 0xFF)
    };
    [pkt appendBytes:tail length:4];
    const uint8_t action = add ? 1 : 0;
    [pkt appendBytes:&action length:1];
    const uint8_t emLen = (uint8_t)em.length;
    [pkt appendBytes:&emLen length:1];
    [pkt appendData:em];
    return pkt;
}

- (void)toggleReactionOnMessage:(OCTMessageAbstract *)message emoji:(NSString *)emoji
{
    if (! message || emoji.length == 0
        || [emoji lengthOfBytesUsingEncoding:NSUTF8StringEncoding] > 16) {
        return;
    }
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *cur = kqReactionsActorEmoji(message.reactionsJSON, kKQOwnReactionMarker);
    BOOL add = ! [emoji isEqualToString:cur];
    NSString *newJson = kqReactionsApplyActor(message.reactionsJSON, kKQOwnReactionMarker, emoji, add);

    OCTChat *chat = [realmManager objectWithUniqueIdentifier:message.chatUniqueIdentifier
                                                       class:[OCTChat class]];
    OCTFriend *friend = [chat.friends firstObject];
    // Anchor: text messages use the msgV3 hash; files/media/voice use the symmetric tox file_id.
    NSString *anchorHex = message.messageFile ? message.messageFile.fileIdHex : message.messageText.msgv3HashHex;
    BOOL deliverable = (friend != nil) && (anchorHex.length == 64);

    [realmManager updateObject:message withBlock:^(OCTMessageAbstract *msg) {
        msg.reactionsJSON = newJson;
        msg.reactionsPending = deliverable;
    }];

    if (deliverable) {
        [self deliverReactionForMessage:message friend:friend];
    }
}

- (void)deliverReactionForMessage:(OCTMessageAbstract *)message friend:(OCTFriend *)friend
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *own = kqReactionsActorEmoji(message.reactionsJSON, kKQOwnReactionMarker);
    NSData *pkt = message.messageFile
        ? [self kqReactionPacketWithFileIdHex:message.messageFile.fileIdHex
                                        emoji:(own ?: @"*")
                                          add:(own != nil)]
        : [self kqReactionPacketWithHashHex:message.messageText.msgv3HashHex
                                      emoji:(own ?: @"*")
                                        add:(own != nil)];
    if (pkt == nil) {
        [realmManager updateObject:message withBlock:^(OCTMessageAbstract *msg) {
            msg.reactionsPending = NO;
        }];
        return;
    }
    OCTToxFriendNumber friendNumber = [[self.dataSource managerGetTox]
                                       friendNumberWithPublicKey:friend.publicKey error:nil];
    if (friendNumber == kOCTToxFriendNumberFailure) {
        return; // stays pending for the reconnect flush
    }
    if ([[self.dataSource managerGetTox] sendLosslessPacketWithFriendNumber:friendNumber
                                                                      bytes:pkt
                                                                      error:nil]) {
        [realmManager updateObject:message withBlock:^(OCTMessageAbstract *msg) {
            msg.reactionsPending = NO;
        }];
    }
}

- (void)flushPendingReactionsForFriend:(OCTFriend *)friend
{
    if (friend.publicKey.length == 0) {
        return;
    }
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];
    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND reactionsPending == YES",
                              chat.uniqueIdentifier];
    RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    // snapshot: delivery mutates reactionsPending which live-updates the RLMResults
    NSMutableArray *pending = [NSMutableArray new];
    for (OCTMessageAbstract *msg in results) {
        [pending addObject:msg];
    }
    for (OCTMessageAbstract *msg in pending) {
        NSString *anchorHex = msg.messageFile ? msg.messageFile.fileIdHex : msg.messageText.msgv3HashHex;
        if (anchorHex.length == 64) {
            [self deliverReactionForMessage:msg friend:friend];
        }
    }
}

// KHANDAQ (#192): the friend added/removed a reaction on a message in our shared 1:1 chat.
- (void)tox:(OCTTox *)tox friendMessageReactionWithMsgv3Hash:(NSString *)msgv3HashHex
                                                       emoji:(NSString *)emoji
                                                         add:(BOOL)add
                                                friendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];
    if (! friend || msgv3HashHex.length != 64) {
        return;
    }
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    // the friend may react to messages of EITHER direction in our shared chat — no sender filter
    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND messageText.msgv3HashHex == %@",
                              chat.uniqueIdentifier, msgv3HashHex];
    RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    OCTMessageAbstract *found = [results firstObject];
    if (! found) {
        return; // reactions never create state for unknown originals
    }

    NSString *actor = [publicKey uppercaseString];
    NSString *newJson = kqReactionsApplyActor(found.reactionsJSON, actor, emoji, add);
    [realmManager updateObject:found withBlock:^(OCTMessageAbstract *msg) {
        msg.reactionsJSON = newJson;
    }];
}

// KHANDAQ (#192): the friend added/removed a reaction on a FILE/media/voice message in our 1:1 chat.
// Anchored by the symmetric tox file_id (uppercase hex), matched case-insensitively against fileIdHex.
- (void)tox:(OCTTox *)tox friendFileReactionWithFileIdHex:(NSString *)fileIdHex
                                                    emoji:(NSString *)emoji
                                                      add:(BOOL)add
                                             friendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];
    if (! friend || fileIdHex.length != 64) {
        return;
    }
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND messageFile.fileIdHex ==[c] %@",
                              chat.uniqueIdentifier, fileIdHex];
    OCTMessageAbstract *found = [[realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate] firstObject];
    if (! found) {
        return; // reactions never create state for unknown originals
    }

    NSString *actor = [publicKey uppercaseString];
    NSString *newJson = kqReactionsApplyActor(found.reactionsJSON, actor, emoji, add);
    [realmManager updateObject:found withBlock:^(OCTMessageAbstract *msg) {
        msg.reactionsJSON = newJson;
    }];
}

- (void)deleteMessageForBoth:(OCTMessageAbstract *)message
{
    do {
        // own outgoing only; text anchors on the msgV3 hash, files/media/voice on the tox file_id.
        if (! message || message.senderUniqueIdentifier != nil) {
            break;
        }
        const BOOL isFile = (message.messageFile != nil);
        NSString *anchorHex = isFile ? message.messageFile.fileIdHex : message.messageText.msgv3HashHex;
        if (anchorHex.length != 64) {
            break;
        }

        OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
        OCTChat *chat = [realmManager objectWithUniqueIdentifier:message.chatUniqueIdentifier
                                                           class:[OCTChat class]];
        OCTFriend *friend = [chat.friends firstObject];
        if (! friend) {
            break;
        }

        NSData *pkt = isFile ? [self kqDeletePacketWithFileIdHex:anchorHex]
                             : [self kqDeletePacketWithHashHex:anchorHex];

        BOOL sent = NO;
        OCTToxFriendNumber friendNumber = [[self.dataSource managerGetTox]
                                           friendNumberWithPublicKey:friend.publicKey error:nil];
        if (pkt != nil && friendNumber != kOCTToxFriendNumberFailure) {
            sent = [[self.dataSource managerGetTox] sendLosslessPacketWithFriendNumber:friendNumber
                                                                                 bytes:pkt
                                                                                 error:nil];
        }
        if (! sent) {
            // peer offline / link down — queue the retraction, re-sent on reconnect. File entries
            // are tagged with an "F:" prefix so the flush rebuilds the right packet.
            [self stashPendingDeleteForFriendPK:friend.publicKey
                                        hashHex:(isFile ? [@"F:" stringByAppendingString:anchorHex] : anchorHex)];
        }
    }
    while (0);

    // local deletion always happens — even when the retraction could not be delivered yet
    [self removeMessages:@[message]];
}

#pragma mark - KHANDAQ (#208) message edit ("KQ" lossless packet id 186, byte-parity with Android)

// 40-byte header {186,'K','Q',1} + msgv3 hash 32B + edit-ts u32 BE + new text UTF-8 (variable).
- (NSData *)kqEditPacketWithHashHex:(NSString *)hashHex newText:(NSString *)newText ts:(uint32_t)ts
{
    if (hashHex.length != 64) {
        return nil;
    }
    NSMutableData *pkt = [NSMutableData dataWithCapacity:64];
    const uint8_t header[4] = { 186, 'K', 'Q', 1 };
    [pkt appendBytes:header length:4];
    for (int i = 0; i < 64; i += 2) {
        unsigned int b = 0;
        [[NSScanner scannerWithString:[hashHex substringWithRange:NSMakeRange(i, 2)]] scanHexInt:&b];
        uint8_t byte = (uint8_t)b;
        [pkt appendBytes:&byte length:1];
    }
    const uint8_t tsbe[4] = {
        (uint8_t)((ts >> 24) & 0xFF), (uint8_t)((ts >> 16) & 0xFF),
        (uint8_t)((ts >> 8) & 0xFF), (uint8_t)(ts & 0xFF)
    };
    [pkt appendBytes:tsbe length:4];
    NSData *textData = [newText dataUsingEncoding:NSUTF8StringEncoding];
    if (textData != nil) {
        [pkt appendData:textData];
    }
    return pkt;
}

- (void)editMessage:(OCTMessageAbstract *)message newText:(NSString *)newText
{
    // own outgoing TEXT only; a reply/mention-encoded message is left alone (Android excludes them too).
    if (! message || message.senderUniqueIdentifier != nil || message.messageText == nil) {
        return;
    }
    NSString *anchorHex = message.messageText.msgv3HashHex;
    if (anchorHex.length != 64) {
        return;
    }
    NSString *trimmed = [newText stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]];
    if (trimmed.length == 0) {
        return;
    }
    uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970];

    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager objectWithUniqueIdentifier:message.chatUniqueIdentifier class:[OCTChat class]];
    OCTFriend *friend = [chat.friends firstObject];
    const BOOL isSaved = (friend == nil); // Saved Messages self-chat has no friend → local edit only

    // update the local row first so the UI reflects the edit immediately (Realm notification → reload)
    [realmManager updateObject:message withBlock:^(OCTMessageAbstract *m) {
        m.messageText.text = trimmed;
        m.edited = YES;
        m.editedTimestamp = (NSTimeInterval)ts;
        m.editPending = ! isSaved; // pending until delivered; nothing to deliver in Saved Messages
    }];

    if (isSaved) {
        return;
    }

    NSData *pkt = [self kqEditPacketWithHashHex:anchorHex newText:trimmed ts:ts];
    OCTToxFriendNumber friendNumber = [[self.dataSource managerGetTox] friendNumberWithPublicKey:friend.publicKey error:nil];
    BOOL sent = NO;
    if (pkt != nil && friendNumber != kOCTToxFriendNumberFailure) {
        sent = [[self.dataSource managerGetTox] sendLosslessPacketWithFriendNumber:friendNumber bytes:pkt error:nil];
    }
    if (sent) {
        [realmManager updateObject:message withBlock:^(OCTMessageAbstract *m) {
            m.editPending = NO;
        }];
    }
    // else: editPending stays YES → flushed from the CURRENT text on reconnect
}

- (void)flushPendingEditsForFriend:(OCTFriend *)friend
{
    if (friend.publicKey.length == 0) {
        return;
    }
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];
    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND editPending == YES AND senderUniqueIdentifier == nil",
                              chat.uniqueIdentifier];
    RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    NSMutableArray *pending = [NSMutableArray new];
    for (OCTMessageAbstract *msg in results) {
        [pending addObject:msg];
    }
    OCTTox *tox = [self.dataSource managerGetTox];
    OCTToxFriendNumber friendNumber = [tox friendNumberWithPublicKey:friend.publicKey error:nil];
    if (friendNumber == kOCTToxFriendNumberFailure) {
        return;
    }
    for (OCTMessageAbstract *msg in pending) {
        NSString *anchorHex = msg.messageText.msgv3HashHex;
        if (anchorHex.length != 64 || msg.messageText.text == nil) {
            continue;
        }
        uint32_t ts = (uint32_t)[[NSDate date] timeIntervalSince1970]; // fresh ts, like the delete/reaction flush
        NSData *pkt = [self kqEditPacketWithHashHex:anchorHex newText:msg.messageText.text ts:ts];
        if (pkt != nil && [tox sendLosslessPacketWithFriendNumber:friendNumber bytes:pkt error:nil]) {
            [realmManager updateObject:msg withBlock:^(OCTMessageAbstract *m) {
                m.editPending = NO;
            }];
        }
    }
}

// the friend edited one of their OWN text messages → in our shared chat that's an INCOMING message.
- (void)tox:(OCTTox *)tox friendMessageEditWithMsgv3Hash:(NSString *)msgv3HashHex
                                                 newText:(NSString *)newText
                                                  editTs:(uint32_t)editTs
                                            friendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];
    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];
    if (! friend || msgv3HashHex.length != 64) {
        return;
    }
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];
    NSPredicate *predicate = [NSPredicate predicateWithFormat:
                              @"chatUniqueIdentifier == %@ AND messageText.msgv3HashHex == %@",
                              chat.uniqueIdentifier, msgv3HashHex];
    OCTMessageAbstract *found = [[realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate] firstObject];
    if (! found || found.messageText == nil) {
        return; // like reactions/deletes, an edit never creates state for an unknown original
    }
    // last-write-wins: ignore an edit not newer than what we already applied
    if (found.edited && found.editedTimestamp >= (NSTimeInterval)editTs) {
        return;
    }
    [realmManager updateObject:found withBlock:^(OCTMessageAbstract *m) {
        m.messageText.text = newText;
        m.edited = YES;
        m.editedTimestamp = (NSTimeInterval)editTs;
    }];
}

- (void)tox:(OCTTox *)tox friendHighLevelACK:(NSString *)message
                                friendNumber:(OCTToxFriendNumber)friendNumber
                                msgv3HashHex:(NSString *)msgv3HashHex
                               sendTimestamp:(uint32_t)sendTimestamp
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];
    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    // HINT: only select outgoing messages
    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"chatUniqueIdentifier == %@ AND messageText.msgv3HashHex == %@ AND senderUniqueIdentifier == nil",
                              chat.uniqueIdentifier, msgv3HashHex];

    RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    results = [results sortedResultsUsingKeyPath:@"dateInterval" ascending:YES];

    if (results.count == 0) {
        return;
    }

    OCTLogInfo(@"friendHighLevelACK recevied from friend %@", friend);

    // KHANDAQ (dup fix): mark ALL rows carrying this hash as delivered — with a same-hash pair
    // (e.g. after a resend) marking only the first left a permanent ✓/✓✓ split and made the
    // delivery watchdog resend the "undelivered" twin every few seconds.
    for (OCTMessageAbstract *message_found in results) {
        [realmManager updateObject:message_found withBlock:^(OCTMessageAbstract *theMessage) {
            theMessage.messageText.isDelivered = YES;
        }];
    }
}


- (void)tox:(OCTTox *)tox messageDelivered:(OCTToxMessageId)messageId friendNumber:(OCTToxFriendNumber)friendNumber
{
    OCTRealmManager *realmManager = [self.dataSource managerGetRealmManager];

    NSString *publicKey = [[self.dataSource managerGetTox] publicKeyFromFriendNumber:friendNumber error:nil];
    OCTFriend *friend = [realmManager friendWithPublicKey:publicKey];

    if (friend.msgv3Capability == YES)
    {
        // HINT: if friend has msgV3 capability, we ignore the low level ACK and keep waiting for the high level ACK
        OCTLogInfo(@"messageDelivered ignoring low level ACK %@", friend);
        return;
    }

    OCTChat *chat = [realmManager getOrCreateChatWithFriend:friend];

    NSPredicate *predicate = [NSPredicate predicateWithFormat:@"chatUniqueIdentifier == %@ AND messageText.messageId == %d",
                              chat.uniqueIdentifier, messageId];

    // messageId is reset on every launch, so we want to update delivered status on latest message.
    RLMResults *results = [realmManager objectsWithClass:[OCTMessageAbstract class] predicate:predicate];
    results = [results sortedResultsUsingKeyPath:@"dateInterval" ascending:NO];

    OCTMessageAbstract *message = [results firstObject];

    if (! message) {
        return;
    }

    [realmManager updateObject:message withBlock:^(OCTMessageAbstract *theMessage) {
        theMessage.messageText.isDelivered = YES;
    }];
}

@end
