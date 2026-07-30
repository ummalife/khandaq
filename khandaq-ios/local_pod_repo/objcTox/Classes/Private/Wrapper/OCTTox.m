// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#import "OCTTox+Private.h"
#import "OCTToxOptions+Private.h"
#import "OCTLogging.h"

void (*_tox_self_get_public_key)(const Tox *tox, uint8_t *public_key);

uint8_t *hex_to_bin(const char *hex_string_buffer, size_t hex_string_len);

@interface OCTTox ()

@property (assign, nonatomic) Tox *tox;

@property (strong, nonatomic) dispatch_source_t timer;
@property (strong, nonatomic) dispatch_queue_t toxQueue;
@property (assign, nonatomic) uint64_t previousIterate;

@end

@implementation OCTTox

static long long last_check_time = 0;
static void * const kOCTToxQueueSpecificKey = (void *)&kOCTToxQueueSpecificKey;
static dispatch_queue_t sOCTFileTransferQueue;
static dispatch_once_t sOCTFileTransferQueueOnceToken;
static long long OFFLINE_REBOOTSTRAP_GRACE_MS = (5 * 1000);
static long long OFFLINE_REBOOTSTRAP_INTERVAL_MS = (15 * 1000);

static void perform_khandaq_offline_rebootstrap(Tox *tox)
{
    // KHANDAQ: offline re-bootstrap against PROVEN PUBLIC Tox DHT nodes (parity with Android; the
    // self-hosted bootstrap*.khandaq.org nodes were dropped — we don't depend on our own bootstrap
    // infra to rejoin the network). push.khandaq.org (FCM wake relay) is a separate, untouched service.
    static const struct {
        const char *host;
        const char *key_hex;
    } nodes[] = {
        {"tox.abilinski.com", "10C00EB250C3233E343E2AEBA07115A5C28920E9C8D29492F6D00B29049EDC7E"},
        {"tox1.mf-net.eu",    "B3E5FA80DC8EBD1149AD2AB35ED8B85BD546DEDE261CA593234C619249419506"},
        {"tox2.mf-net.eu",    "70EA214FDE161E7432530605213F18F7427DC773E276B3E317A07531F548545F"},
        {"tox.initramfs.io",  "3F0A45A268367C1BEA652F258C85F4A66DA76BCAA667A49E770BCC4917AB6A25"},
    };
    static const uint16_t tcp_ports[] = {33445, 3389};

    for (size_t i = 0; i < (sizeof(nodes) / sizeof(nodes[0])); i++) {
        uint8_t *key_bin = hex_to_bin(nodes[i].key_hex, (TOX_PUBLIC_KEY_SIZE * 2));
        if (key_bin == NULL) {
            continue;
        }

        tox_bootstrap(tox, nodes[i].host, 33445, key_bin, NULL);
        for (size_t p = 0; p < (sizeof(tcp_ports) / sizeof(tcp_ports[0])); p++) {
            tox_add_tcp_relay(tox, nodes[i].host, tcp_ports[p], key_bin, NULL);
        }
        free(key_bin);
    }
}

#pragma mark -  Class methods

+ (NSString *)version
{
    return [NSString stringWithFormat:@"%lu.%lu.%lu",
            (unsigned long)[self versionMajor], (unsigned long)[self versionMinor], (unsigned long)[self versionPatch]];
}

+ (NSUInteger)versionMajor
{
    return tox_version_major();
}

+ (NSUInteger)versionMinor
{
    return tox_version_minor();
}

+ (NSUInteger)versionPatch
{
    return tox_version_patch();
}

#pragma mark -  Lifecycle

- (instancetype)initWithOptions:(OCTToxOptions *)options savedData:(NSData *)data error:(NSError **)error
{
    NSParameterAssert(options);

    self = [super init];

    OCTLogVerbose(@"OCTTox: loading with options %@", options);

    if (data) {
        OCTLogVerbose(@"loading from data of length %lu", (unsigned long)data.length);
        tox_options_set_savedata_type(options.options, TOX_SAVEDATA_TYPE_TOX_SAVE);
        tox_options_set_savedata_data(options.options, data.bytes, data.length);
    }
    else {
        tox_options_set_savedata_type(options.options, TOX_SAVEDATA_TYPE_NONE);
    }

    tox_options_set_log_callback(options.options, logCallback);

    TOX_ERR_NEW cError;

    _tox = tox_new(options.options, &cError);

    [self fillError:error withCErrorInit:cError];

    if (! _tox) {
        return nil;
    }

    [self setupCFunctions];
    [self setupCallbacks];

    return self;
}

- (void)dealloc
{
    [self stop];

    if (self.tox) {
        tox_kill(self.tox);
    }

    OCTLogVerbose(@"dealloc called, tox killed");
}

- (NSData *)save
{
    OCTLogVerbose(@"saving...");

    __block NSData *data = nil;

    [self performSyncBlockOnToxQueue:^{
        size_t size = tox_get_savedata_size(self.tox);
        uint8_t *cData = malloc(size);

        tox_get_savedata(self.tox, cData);

        data = [NSData dataWithBytes:cData length:size];
        free(cData);
    }];

    OCTLogInfo(@"saved to data with length %lu", (unsigned long)data.length);

    return data;
}

- (void)start
{
    OCTLogVerbose(@"start method called");

    @synchronized(self) {
        if (self.timer) {
            OCTLogWarn(@"already started");
            return;
        }

        self.toxQueue = dispatch_queue_create("me.dvor.objcTox.OCTToxQueue", DISPATCH_QUEUE_SERIAL);
        dispatch_queue_set_specific(self.toxQueue, kOCTToxQueueSpecificKey, kOCTToxQueueSpecificKey, NULL);
        self.timer = dispatch_source_create(DISPATCH_SOURCE_TYPE_TIMER, 0, 0, self.toxQueue);

        [self updateTimerIntervalIfNeeded];

        last_check_time = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
        last_check_time = last_check_time + OFFLINE_REBOOTSTRAP_GRACE_MS;

        __weak OCTTox *weakSelf = self;
        dispatch_source_set_event_handler(self.timer, ^{
            OCTTox *strongSelf = weakSelf;
            if (! strongSelf) {
                return;
            }

            tox_iterate(strongSelf.tox, (__bridge void *)self);

            long long current_time = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);

            if (current_time > (last_check_time + OFFLINE_REBOOTSTRAP_INTERVAL_MS)) {
                last_check_time = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
                int cstatus = tox_self_get_connection_status(strongSelf.tox);

                OCTLogInfo(@"Tox checking online status: %d", cstatus);

                if (cstatus == 0) {
                    OCTLogInfo(@"Tox offline for a long time, bootstrapping again ...");
                    perform_khandaq_offline_rebootstrap(strongSelf.tox);
                    OCTLogInfo(@"Tox offline for a long time, bootstrapping DONE");
                }
            }

            [strongSelf updateTimerIntervalIfNeeded];
        });

        dispatch_resume(self.timer);
    }

    OCTLogInfo(@"started");
}

- (void)stop
{
    OCTLogVerbose(@"stop method called");

    @synchronized(self) {
        if (! self.timer) {
            OCTLogWarn(@"tox isn't running, nothing to stop");
            return;
        }

        dispatch_source_cancel(self.timer);
        self.timer = nil;
        self.toxQueue = nil;
    }

    OCTLogInfo(@"stopped");
}

- (void)performBlockOnToxQueue:(void (^)(void))block
{
    if (! block) {
        return;
    }

    dispatch_queue_t queue = self.toxQueue;

    if (! queue) {
        block();
        return;
    }

    dispatch_async(queue, block);
}

- (void)performSyncBlockOnToxQueue:(void (^)(void))block
{
    if (! block) {
        return;
    }

    dispatch_queue_t queue = self.toxQueue;

    if (! queue) {
        block();
        return;
    }

    if (dispatch_get_specific(kOCTToxQueueSpecificKey)) {
        block();
        return;
    }

    dispatch_sync(queue, block);
}

- (void)resetOfflineRebootstrapTimer
{
    last_check_time = (long long)([[NSDate date] timeIntervalSince1970] * 1000.0);
}

#pragma mark -  Properties

- (OCTToxConnectionStatus)connectionStatus
{
    return [self userConnectionStatusFromCUserStatus:tox_self_get_connection_status(self.tox)];
}

- (OCTToxCapabilities)capabilities
{
    OCTLogVerbose(@"get capabilities");

    return tox_self_get_capabilities();
}

- (NSString *)userAddress
{
    OCTLogVerbose(@"get userAddress");

    const NSUInteger length = TOX_ADDRESS_SIZE;
    uint8_t *cAddress = malloc(length);

    tox_self_get_address(self.tox, cAddress);

    if (! cAddress) {
        return nil;
    }

    NSString *address = [OCTTox binToHexString:cAddress length:length];

    free(cAddress);

    return address;
}

- (NSString *)publicKey
{
    OCTLogVerbose(@"get publicKey");

    uint8_t *cPublicKey = malloc(TOX_PUBLIC_KEY_SIZE);

    _tox_self_get_public_key(self.tox, cPublicKey);

    NSString *publicKey = [OCTTox binToHexString:cPublicKey length:TOX_PUBLIC_KEY_SIZE];
    free(cPublicKey);

    return publicKey;
}

- (NSString *)secretKey
{
    OCTLogVerbose(@"get secretKey");

    uint8_t *cSecretKey = malloc(TOX_SECRET_KEY_SIZE);

    tox_self_get_secret_key(self.tox, cSecretKey);

    NSString *secretKey = [OCTTox binToHexString:cSecretKey length:TOX_SECRET_KEY_SIZE];
    free(cSecretKey);

    return secretKey;
}

- (void)setNospam:(OCTToxNoSpam)nospam
{
    OCTLogVerbose(@"set nospam");
    tox_self_set_nospam(self.tox, nospam);
}

- (OCTToxNoSpam)nospam
{
    OCTLogVerbose(@"get nospam");
    return tox_self_get_nospam(self.tox);
}

- (void)setUserStatus:(OCTToxUserStatus)status
{
    TOX_USER_STATUS cStatus = TOX_USER_STATUS_NONE;

    switch (status) {
        case OCTToxUserStatusNone:
            cStatus = TOX_USER_STATUS_NONE;
            break;
        case OCTToxUserStatusAway:
            cStatus = TOX_USER_STATUS_AWAY;
            break;
        case OCTToxUserStatusBusy:
            cStatus = TOX_USER_STATUS_BUSY;
            break;
    }

    tox_self_set_status(self.tox, cStatus);

    OCTLogInfo(@"set user status to %lu", (unsigned long)status);
}

- (OCTToxUserStatus)userStatus
{
    return [self userStatusFromCUserStatus:tox_self_get_status(self.tox)];
}

#pragma mark -  Methods

/*
 * Converts an ASCII character in hexadecimal (lower or upper case) into the corresponding decimal value.
 *
 * Returns decimal value on success.
 * Returns -1 on failure.
 */
int char_to_int(char c)
{
    if (c >= '0' && c <= '9')
    {
        return c - '0';
    }

    if (c >= 'A' && c <= 'F')
    {
        return 10 + c - 'A';
    }

    if (c >= 'a' && c <= 'f')
    {
        return 10 + c - 'a';
    }

    return -1;
}

/*
 * Converts a hexidecimal string of length hex_string_len to binary format and puts the result in output.
 * output_size must be exactly half of hex_string_len.
 *
 * Returns (uint8_t *) on success. the caller must free the buffer after use.
 * Returns NULL on failure.
 */
uint8_t *hex_to_bin(const char *hex_string_buffer, size_t hex_string_len)
{
    if ((!hex_string_buffer) || (hex_string_len < 2))
    {
        return NULL;
    }

    if ((hex_string_len % 2) != 0)
    {
        return NULL;
    }

    size_t len_bin = (hex_string_len / 2);
    uint8_t *val = calloc(1, len_bin);

    for (size_t i = 0; i < len_bin; i++)
    {
        val[i] = (16 * char_to_int(hex_string_buffer[2 * i])) + (char_to_int(hex_string_buffer[2 * i + 1]));
    }

    return val;
}

/*
 * Converts byte buffer into a hexidecimal string.
 *
 * Returns 0 on success. the caller must must provide a buffer with enough space to hold the hex string.
 * Returns -1 on failure.
 */
int bin_to_hex(const char *bin_id, size_t bin_id_size, char *output)
{
    if ((!output) || (!bin_id) || (bin_id_size < 1))
    {
        return -1;
    }

    size_t i;

    for (i = 0; i < bin_id_size; i++)
    {
        snprintf(&output[i * 2], ((bin_id_size * 2) + 1) - (i * 2), "%02X", bin_id[i] & 0xff);
    }

    return 0;
}

size_t xnet_pack_u16(uint8_t *bytes, uint16_t v)
{
    bytes[0] = (v >> 8) & 0xff;
    bytes[1] = v & 0xff;
    return sizeof(v);
}

size_t xnet_pack_u32(uint8_t *bytes, uint32_t v)
{
    uint8_t *p = bytes;
    p += xnet_pack_u16(p, (v >> 16) & 0xffff);
    p += xnet_pack_u16(p, v & 0xffff);
    return p - bytes;
}

size_t xnet_unpack_u16(const uint8_t *bytes, uint16_t *v)
{
    uint8_t hi = bytes[0];
    uint8_t lo = bytes[1];
    *v = ((uint16_t)hi << 8) | lo;
    return sizeof(*v);
}

size_t xnet_unpack_u32(const uint8_t *bytes, uint32_t *v)
{
    const uint8_t *p = bytes;
    uint16_t hi;
    uint16_t lo;
    p += xnet_unpack_u16(p, &hi);
    p += xnet_unpack_u16(p, &lo);
    *v = ((uint32_t)hi << 16) | lo;
    return p - bytes;
}

- (BOOL)bootstrapFromHost:(NSString *)host port:(OCTToxPort)port publicKey:(NSString *)publicKey error:(NSError **)error
{
    NSParameterAssert(host);
    NSParameterAssert(publicKey);

    OCTLogInfo(@"bootstrap with host %@ port %d publicKey %@", host, port, publicKey);

    const char *cAddress = host.UTF8String;
    uint8_t *cPublicKey = [OCTTox hexStringToBin:publicKey];

    TOX_ERR_BOOTSTRAP cError;

    bool result = tox_bootstrap(self.tox, cAddress, port, cPublicKey, &cError);

    [self fillError:error withCErrorBootstrap:cError];

    free(cPublicKey);

    return (BOOL)result;
}

- (BOOL)addTCPRelayWithHost:(NSString *)host port:(OCTToxPort)port publicKey:(NSString *)publicKey error:(NSError **)error
{
    NSParameterAssert(host);
    NSParameterAssert(publicKey);

    OCTLogInfo(@"add TCP relay with host %@ port %d publicKey %@", host, port, publicKey);

    const char *cAddress = host.UTF8String;
    uint8_t *cPublicKey = [OCTTox hexStringToBin:publicKey];

    TOX_ERR_BOOTSTRAP cError;

    bool result = tox_add_tcp_relay(self.tox, cAddress, port, cPublicKey, &cError);

    [self fillError:error withCErrorBootstrap:cError];

    free(cPublicKey);

    return (BOOL)result;
}

- (OCTToxFriendNumber)addFriendWithAddress:(NSString *)address message:(NSString *)message error:(NSError **)error
{
    NSParameterAssert(address);
    NSParameterAssert(message);

    NSString *normalizedAddress = [[address stringByTrimmingCharactersInSet:[NSCharacterSet whitespaceAndNewlineCharacterSet]] uppercaseString];

    if (normalizedAddress.length != kOCTToxAddressLength) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorFriendAddBadChecksum
                                     description:@"Cannot add friend"
                                   failureReason:@"Address must be exactly 76 hex characters"];
        }
        return kOCTToxFriendNumberFailure;
    }

    NSCharacterSet *nonHex = [[NSCharacterSet characterSetWithCharactersInString:@"0123456789ABCDEF"] invertedSet];
    if ([normalizedAddress rangeOfCharacterFromSet:nonHex].location != NSNotFound) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorFriendAddBadChecksum
                                     description:@"Cannot add friend"
                                   failureReason:@"Address contains non-hex characters"];
        }
        return kOCTToxFriendNumberFailure;
    }

    OCTLogVerbose(@"add friend with address.length %lu, message.length %lu", (unsigned long)normalizedAddress.length, (unsigned long)message.length);

    __block OCTToxFriendNumber result = kOCTToxFriendNumberFailure;
    __block NSError *localError = nil;

    [self performSyncBlockOnToxQueue:^{
        uint8_t *cAddress = [OCTTox hexStringToBin:normalizedAddress];
        if (cAddress == NULL) {
            localError = [OCTTox createErrorWithCode:OCTToxErrorFriendAddBadChecksum
                                         description:@"Cannot add friend"
                                       failureReason:@"Address is not valid hex"];
            return;
        }
        const char *cMessage = [message cStringUsingEncoding:NSUTF8StringEncoding];
        size_t length = [message lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

        TOX_ERR_FRIEND_ADD cError;

        result = tox_friend_add(self.tox, cAddress, (const uint8_t *)cMessage, length, &cError);

        free(cAddress);

        [self fillError:&localError withCErrorFriendAdd:cError];
    }];

    if (error) {
        *error = localError;
    }

    return result;
}

- (OCTToxFriendNumber)addFriendWithNoRequestWithPublicKey:(NSString *)publicKey error:(NSError **)error
{
    NSParameterAssert(publicKey);
    NSAssert(publicKey.length == kOCTToxPublicKeyLength, @"Public key must be kOCTToxPublicKeyLength length");

    OCTLogVerbose(@"add friend with no request and publicKey.length %lu", (unsigned long)publicKey.length);

    __block OCTToxFriendNumber result = kOCTToxFriendNumberFailure;
    __block NSError *localError = nil;

    [self performSyncBlockOnToxQueue:^{
        uint8_t *cPublicKey = [OCTTox hexStringToBin:publicKey];

        TOX_ERR_FRIEND_ADD cError;

        result = tox_friend_add_norequest(self.tox, cPublicKey, &cError);

        free(cPublicKey);

        [self fillError:&localError withCErrorFriendAdd:cError];
    }];

    if (error) {
        *error = localError;
    }

    return result;
}

- (BOOL)deleteFriendWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    __block TOX_ERR_FRIEND_DELETE cError = TOX_ERR_FRIEND_DELETE_OK;
    __block bool result = false;
    __block NSError *localError = nil;

    [self performSyncBlockOnToxQueue:^{
        result = tox_friend_delete(self.tox, friendNumber, &cError);
        [self fillError:&localError withCErrorFriendDelete:cError];
    }];

    if (error) {
        *error = localError;
    }

    OCTLogVerbose(@"deleting friend with friendNumber %d, result %d", friendNumber, (result == false));

    return (BOOL)result;
}

- (OCTToxFriendNumber)friendNumberWithPublicKey:(NSString *)publicKey error:(NSError **)error
{
    NSParameterAssert(publicKey);
    NSAssert(publicKey.length == kOCTToxPublicKeyLength, @"Public key must be kOCTToxPublicKeyLength length");

    OCTLogVerbose(@"get friend number with publicKey.length %lu", (unsigned long)publicKey.length);

    uint8_t *cPublicKey = [OCTTox hexStringToBin:publicKey];

    TOX_ERR_FRIEND_BY_PUBLIC_KEY cError;

    OCTToxFriendNumber result = tox_friend_by_public_key(self.tox, cPublicKey, &cError);

    free(cPublicKey);

    [self fillError:error withCErrorFriendByPublicKey:cError];

    return result;
}

- (NSString *)publicKeyFromFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    OCTLogVerbose(@"get public key from friend number %d", friendNumber);

    uint8_t *cPublicKey = malloc(TOX_PUBLIC_KEY_SIZE);

    TOX_ERR_FRIEND_GET_PUBLIC_KEY cError;

    bool result = tox_friend_get_public_key(self.tox, friendNumber, cPublicKey, &cError);

    NSString *publicKey = nil;

    if (result) {
        publicKey = [OCTTox binToHexString:cPublicKey length:TOX_PUBLIC_KEY_SIZE];
    }

    if (cPublicKey) {
        free(cPublicKey);
    }

    [self fillError:error withCErrorFriendGetPublicKey:cError];

    return publicKey;
}

- (BOOL)friendExistsWithFriendNumber:(OCTToxFriendNumber)friendNumber
{
    bool result = tox_friend_exists(self.tox, friendNumber);

    OCTLogVerbose(@"friend exists with friendNumber %d, result %d", friendNumber, result);

    return (BOOL)result;
}

- (NSDate *)friendGetLastOnlineWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    TOX_ERR_FRIEND_GET_LAST_ONLINE cError;

    uint64_t timestamp = tox_friend_get_last_online(self.tox, friendNumber, &cError);

    [self fillError:error withCErrorFriendGetLastOnline:cError];

    if (timestamp == UINT64_MAX) {
        return nil;
    }

    return [NSDate dateWithTimeIntervalSince1970:timestamp];
}


- (OCTToxCapabilities)friendGetCapabilitiesWithFriendNumber:(OCTToxFriendNumber)friendNumber
{
    return tox_friend_get_capabilities(self.tox, friendNumber);
}

- (OCTToxUserStatus)friendStatusWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    TOX_ERR_FRIEND_QUERY cError;

    TOX_USER_STATUS cStatus = tox_friend_get_status(self.tox, friendNumber, &cError);

    [self fillError:error withCErrorFriendQuery:cError];

    return [self userStatusFromCUserStatus:cStatus];
}

- (OCTToxConnectionStatus)friendConnectionStatusWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    TOX_ERR_FRIEND_QUERY cError;

    TOX_CONNECTION cStatus = tox_friend_get_connection_status(self.tox, friendNumber, &cError);

    [self fillError:error withCErrorFriendQuery:cError];

    return [self userConnectionStatusFromCUserStatus:cStatus];
}

- (BOOL)sendLosslessPacketWithFriendNumber:(OCTToxFriendNumber)friendNumber
                                                pktid:(uint8_t)pktid
                                                 data:(NSString *)data
                                                error:(NSError **)error
{
    // TODO: this now only works with UTF8 strings as data, make it work fully with byte arrays later

    NSParameterAssert(data);

    char *cData = [data cStringUsingEncoding:NSUTF8StringEncoding];
    size_t length = [data lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

    cData[0] = pktid;

    TOX_ERR_FRIEND_CUSTOM_PACKET cError;

    bool result = tox_friend_send_lossless_packet(self.tox, friendNumber, (const uint8_t *)cData, length, &cError);

    // TODO: fill cError with errorcode
    // [self fillError:error xxxxxxxxxx:cError];

    return (BOOL)result;
}

- (BOOL)sendLosslessPacketWithFriendNumber:(OCTToxFriendNumber)friendNumber
                                      bytes:(NSData *)bytes
                                      error:(NSError **)error
{
    NSParameterAssert(bytes);

    if (bytes.length == 0 || bytes.length >= 300) {
        return NO;
    }

    TOX_ERR_FRIEND_CUSTOM_PACKET cError;

    bool result = tox_friend_send_lossless_packet(self.tox,
                                                  friendNumber,
                                                  bytes.bytes,
                                                  bytes.length,
                                                  &cError);

    return (BOOL)result;
}

- (OCTToxMessageId)sendMessageWithFriendNumber:(OCTToxFriendNumber)friendNumber
                                          type:(OCTToxMessageType)type
                                       message:(NSString *)message
                                  msgv3HashHex:(NSString *)msgv3HashHex
                                    msgv3tssec:(UInt32)msgv3tssec
                                         error:(NSError **)error
{
    NSParameterAssert(message);

    char *cMessage = [message cStringUsingEncoding:NSUTF8StringEncoding];
    size_t length = [message lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

    TOX_MESSAGE_TYPE cType;
    switch (type) {
        case OCTToxMessageTypeNormal:
            cType = TOX_MESSAGE_TYPE_NORMAL;
            break;
        case OCTToxMessageTypeAction:
            cType = TOX_MESSAGE_TYPE_ACTION;
            break;
        case OCTToxMessageTypeHighlevelack:
            cType = TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK;
            break;
    }

    TOX_ERR_FRIEND_SEND_MESSAGE cError;

    char *cMessage2 = cMessage;
    size_t length2 = length;
    char *cMessage2_alloc = NULL;
    uint8_t *hash_buffer_c = NULL;

    if (msgv3HashHex != nil)
    {
        char *msgv3HashHex_cstr = [msgv3HashHex cStringUsingEncoding:NSUTF8StringEncoding];
        size_t msgv3HashHex_length = [msgv3HashHex lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

        if (msgv3HashHex_length >= (TOX_MSGV3_MSGID_LENGTH * 2))
        {
            size_t length_orig_corrected = length;
            if (length > TOX_MSGV3_MAX_MESSAGE_LENGTH)
            {
                length_orig_corrected = TOX_MSGV3_MAX_MESSAGE_LENGTH;
            }

            cMessage2_alloc = (char *)calloc(1, (size_t)(length_orig_corrected +
                    TOX_MSGV3_GUARD + TOX_MSGV3_MSGID_LENGTH + TOX_MSGV3_TIMESTAMP_LENGTH));
            hash_buffer_c = hex_to_bin(msgv3HashHex_cstr, (TOX_MSGV3_MSGID_LENGTH * 2));

            if ((cMessage2_alloc) && (hash_buffer_c))
            {
                uint32_t timestamp_unix = (uint32_t)msgv3tssec;
                uint32_t timestamp_unix_buf = 0;
                // NSLog(@"mmm:timestamp_unix %d", timestamp_unix);
                xnet_pack_u32((uint8_t *)&timestamp_unix_buf, timestamp_unix);
                // NSLog(@"mmm:timestamp_unix_buf %d", timestamp_unix_buf);

                uint8_t* position = cMessage2_alloc;
                memcpy(position, cMessage, (size_t)(length_orig_corrected));
                position = position + length_orig_corrected;
                position = position + TOX_MSGV3_GUARD;
                memcpy(position, hash_buffer_c, (size_t)(TOX_MSGV3_MSGID_LENGTH));
                position = position + TOX_MSGV3_MSGID_LENGTH;
                memcpy(position, &timestamp_unix_buf, (size_t)(TOX_MSGV3_TIMESTAMP_LENGTH));

                length2 = length_orig_corrected + TOX_MSGV3_GUARD + TOX_MSGV3_MSGID_LENGTH + TOX_MSGV3_TIMESTAMP_LENGTH;
                cMessage2 = cMessage2_alloc;
            }
        }
    }

    OCTToxMessageId result = tox_friend_send_message(self.tox, friendNumber, cType, (const uint8_t *)cMessage2, length2, &cError);

    if (cMessage2_alloc)
    {
        free(cMessage2_alloc);
    }

    if (hash_buffer_c)
    {
        free(hash_buffer_c);
    }

    [self fillError:error withCErrorFriendSendMessage:cError];

    return result;
}

- (BOOL)setNickname:(NSString *)name error:(NSError **)error
{
    NSParameterAssert(name);

    const char *cName = [name cStringUsingEncoding:NSUTF8StringEncoding];
    size_t length = [name lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

    TOX_ERR_SET_INFO cError;

    bool result = tox_self_set_name(self.tox, (const uint8_t *)cName, length, &cError);

    [self fillError:error withCErrorSetInfo:cError];

    OCTLogInfo(@"set userName to %@, result %d", name, result);

    return (BOOL)result;
}

- (NSString *)userName
{
    size_t length = tox_self_get_name_size(self.tox);

    if (! length) {
        return nil;
    }

    uint8_t *cName = malloc(length);
    tox_self_get_name(self.tox, cName);

    NSString *name = [[NSString alloc] initWithBytes:cName length:length encoding:NSUTF8StringEncoding];

    free(cName);

    return name;
}

- (NSString *)friendNameWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    TOX_ERR_FRIEND_QUERY cError;
    size_t size = tox_friend_get_name_size(self.tox, friendNumber, &cError);

    [self fillError:error withCErrorFriendQuery:cError];

    if (cError != TOX_ERR_FRIEND_QUERY_OK) {
        return nil;
    }

    uint8_t *cName = malloc(size);
    bool result = tox_friend_get_name(self.tox, friendNumber, cName, &cError);

    NSString *name = nil;

    if (result) {
        name = [[NSString alloc] initWithBytes:cName length:size encoding:NSUTF8StringEncoding];
    }

    if (cName) {
        free(cName);
    }

    [self fillError:error withCErrorFriendQuery:cError];

    return name;
}

- (BOOL)setUserStatusMessage:(NSString *)statusMessage error:(NSError **)error
{
    NSParameterAssert(statusMessage);

    const char *cStatusMessage = [statusMessage cStringUsingEncoding:NSUTF8StringEncoding];
    size_t length = [statusMessage lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

    TOX_ERR_SET_INFO cError;

    bool result = tox_self_set_status_message(self.tox, (const uint8_t *)cStatusMessage, length, &cError);

    [self fillError:error withCErrorSetInfo:cError];

    OCTLogInfo(@"set user status message to %@, result %d", statusMessage, result);

    return (BOOL)result;
}

- (NSString *)userStatusMessage
{
    size_t length = tox_self_get_status_message_size(self.tox);

    if (! length) {
        return nil;
    }

    uint8_t *cBuffer = malloc(length);

    tox_self_get_status_message(self.tox, cBuffer);

    NSString *message = [[NSString alloc] initWithBytes:cBuffer length:length encoding:NSUTF8StringEncoding];
    free(cBuffer);

    return message;
}

- (NSString *)friendStatusMessageWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    TOX_ERR_FRIEND_QUERY cError;

    size_t size = tox_friend_get_status_message_size(self.tox, friendNumber, &cError);

    [self fillError:error withCErrorFriendQuery:cError];

    if (cError != TOX_ERR_FRIEND_QUERY_OK) {
        return nil;
    }

    uint8_t *cBuffer = malloc(size);

    bool result = tox_friend_get_status_message(self.tox, friendNumber, cBuffer, &cError);

    NSString *message = nil;

    if (result) {
        message = [[NSString alloc] initWithBytes:cBuffer length:size encoding:NSUTF8StringEncoding];
    }

    if (cBuffer) {
        free(cBuffer);
    }

    [self fillError:error withCErrorFriendQuery:cError];

    return message;
}

- (BOOL)setUserIsTyping:(BOOL)isTyping forFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    TOX_ERR_SET_TYPING cError;

    bool result = tox_self_set_typing(self.tox, friendNumber, (bool)isTyping, &cError);

    [self fillError:error withCErrorSetTyping:cError];

    OCTLogInfo(@"set user isTyping to %d for friend number %d, result %d", isTyping, friendNumber, result);

    return (BOOL)result;
}

- (BOOL)isFriendTypingWithFriendNumber:(OCTToxFriendNumber)friendNumber error:(NSError **)error
{
    TOX_ERR_FRIEND_QUERY cError;

    bool isTyping = tox_friend_get_typing(self.tox, friendNumber, &cError);

    [self fillError:error withCErrorFriendQuery:cError];

    return (BOOL)isTyping;
}

- (NSUInteger)friendsCount
{
    return tox_self_get_friend_list_size(self.tox);
}

- (NSArray *)friendsArray
{
    size_t count = tox_self_get_friend_list_size(self.tox);

    if (! count) {
        return @[];
    }

    size_t listSize = count * sizeof(uint32_t);
    uint32_t *cList = malloc(listSize);

    tox_self_get_friend_list(self.tox, cList);

    NSMutableArray *list = [NSMutableArray new];

    for (NSUInteger index = 0; index < count; index++) {
        int32_t friendId = cList[index];
        [list addObject:@(friendId)];
    }

    free(cList);

    OCTLogVerbose(@"friend array %@", list);

    return [list copy];
}

- (NSData *)hashData:(NSData *)data
{
    uint8_t *cHash = malloc(TOX_HASH_LENGTH);
    const uint8_t *cData = [data bytes];

    bool result = tox_hash(cHash, cData, (uint32_t)data.length);
    NSData *hash;

    if (result) {
        hash = [NSData dataWithBytes:cHash length:TOX_HASH_LENGTH];
    }

    if (cHash) {
        free(cHash);
    }

    OCTLogInfo(@"hash data result %@", hash);

    return hash;
}

- (BOOL)fileSendControlForFileNumber:(OCTToxFileNumber)fileNumber
                        friendNumber:(OCTToxFriendNumber)friendNumber
                             control:(OCTToxFileControl)control
                               error:(NSError **)error
{
    TOX_FILE_CONTROL cControl;

    switch (control) {
        case OCTToxFileControlResume:
            cControl = TOX_FILE_CONTROL_RESUME;
            break;
        case OCTToxFileControlPause:
            cControl = TOX_FILE_CONTROL_PAUSE;
            break;
        case OCTToxFileControlCancel:
            cControl = TOX_FILE_CONTROL_CANCEL;
            break;
    }

    TOX_ERR_FILE_CONTROL cError;

    bool result = tox_file_control(self.tox, friendNumber, fileNumber, cControl, &cError);

    [self fillError:error withCErrorFileControl:cError];

    return (BOOL)result;
}

- (BOOL)fileSeekForFileNumber:(OCTToxFileNumber)fileNumber
                 friendNumber:(OCTToxFriendNumber)friendNumber
                     position:(OCTToxFileSize)position
                        error:(NSError **)error
{
    TOX_ERR_FILE_SEEK cError;

    bool result = tox_file_seek(self.tox, friendNumber, fileNumber, position, &cError);

    [self fillError:error withCErrorFileSeek:cError];

    return (BOOL)result;
}

- (NSData *)fileGetFileIdForFileNumber:(OCTToxFileNumber)fileNumber
                          friendNumber:(OCTToxFriendNumber)friendNumber
                                 error:(NSError **)error
{
    uint8_t *cFileId = malloc(kOCTToxFileIdLength);
    TOX_ERR_FILE_GET cError;

    bool result = tox_file_get_file_id(self.tox, friendNumber, fileNumber, cFileId, &cError);
    NSData *fileId;

    [self fillError:error withCErrorFileGet:cError];

    if (result) {
        fileId = [NSData dataWithBytes:cFileId length:kOCTToxFileIdLength];
    }

    if (cFileId) {
        free(cFileId);
    }

    return fileId;
}

- (OCTToxFileNumber)fileSendWithFriendNumber:(OCTToxFriendNumber)friendNumber
                                        kind:(OCTToxFileKind)kind
                                    fileSize:(OCTToxFileSize)fileSize
                                      fileId:(NSData *)fileId
                                    fileName:(NSString *)fileName
                                       error:(NSError **)error
{
    TOX_ERR_FILE_SEND cError;
    TOX_FILE_KIND cKind;
    const uint8_t *cFileId = NULL;
    const uint8_t *cFileName = NULL;

    switch (kind) {
        case OCTToxFileKindData:
            cKind = TOX_FILE_KIND_DATA;
            break;
        case OCTToxFileKindAvatar:
            cKind = TOX_FILE_KIND_AVATAR;
            break;
    }

    if (fileId.length) {
        cFileId = [fileId bytes];
    }

    if (fileName.length) {
        cFileName = (const uint8_t *)[fileName cStringUsingEncoding:NSUTF8StringEncoding];
    }

    OCTToxFileNumber result = tox_file_send(self.tox, friendNumber, cKind, fileSize, cFileId, cFileName, fileName.length, &cError);

    [self fillError:error withCErrorFileSend:cError];

    return result;
}

- (BOOL)fileSendChunkForFileNumber:(OCTToxFileNumber)fileNumber
                      friendNumber:(OCTToxFriendNumber)friendNumber
                          position:(OCTToxFileSize)position
                              data:(NSData *)data
                             error:(NSError **)error
{
    __block BOOL result = NO;
    __block TOX_ERR_FILE_SEND_CHUNK cError;

    [self performSyncBlockOnToxQueue:^{
        const uint8_t *cData = [data bytes];
        result = tox_file_send_chunk(self.tox, friendNumber, fileNumber, position, cData, (uint32_t)data.length, &cError);
    }];

    [self fillError:error withCErrorFileSendChunk:cError];

    return result;
}

#pragma mark - Groups

- (OCTToxGroupNumber)groupNewWithPrivacyState:(OCTToxGroupPrivacyState)privacyState
                                    groupName:(NSString *)groupName
                                     peerName:(NSString *)peerName
                                        error:(NSError **)error
{
    NSParameterAssert(groupName);
    NSParameterAssert(peerName);

    Tox_Group_Privacy_State cPrivacyState = [self cPrivacyStateFromGroupPrivacyState:privacyState];
    const char *cGroupName = [groupName cStringUsingEncoding:NSUTF8StringEncoding];
    size_t groupNameLength = [groupName lengthOfBytesUsingEncoding:NSUTF8StringEncoding];
    const char *cPeerName = [peerName cStringUsingEncoding:NSUTF8StringEncoding];
    size_t peerNameLength = [peerName lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

    Tox_Err_Group_New cError;

    OCTToxGroupNumber result = tox_group_new(self.tox, cPrivacyState, (const uint8_t *)cGroupName, groupNameLength,
                                            (const uint8_t *)cPeerName, peerNameLength, &cError);

    if (result == kOCTToxGroupNumberFailure) {
        [self fillError:error withCErrorGroupNew:cError];
    }

    OCTLogInfo(@"groupNew privacy=%ld name=%@ peer=%@ result=%u", (long)privacyState, groupName, peerName, result);

    return result;
}

- (OCTToxGroupNumber)groupJoinWithChatIdHex:(NSString *)chatIdHex
                                   peerName:(NSString *)peerName
                                   password:(NSString *)password
                                      error:(NSError **)error
{
    NSParameterAssert(chatIdHex);
    NSParameterAssert(peerName);

    if (chatIdHex.length != kOCTToxGroupChatIdHexLength) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupJoinBadChatId
                                     description:@"Cannot join group"
                                   failureReason:@"Chat ID must be exactly 64 hex characters"];
        }
        return kOCTToxGroupNumberFailure;
    }

    uint8_t *cChatId = [OCTTox hexStringToBin:chatIdHex];
    const char *cPeerName = [peerName cStringUsingEncoding:NSUTF8StringEncoding];
    size_t peerNameLength = [peerName lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

    const uint8_t *cPassword = NULL;
    size_t passwordLength = 0;

    if (password.length > 0) {
        cPassword = (const uint8_t *)[password cStringUsingEncoding:NSUTF8StringEncoding];
        passwordLength = [password lengthOfBytesUsingEncoding:NSUTF8StringEncoding];
    }

    Tox_Err_Group_Join cError;

    OCTToxGroupNumber result = tox_group_join(self.tox, cChatId, (const uint8_t *)cPeerName, peerNameLength,
                                              cPassword, passwordLength, &cError);

    free(cChatId);

    if (result == kOCTToxGroupNumberFailure) {
        [self fillError:error withCErrorGroupJoin:cError];
    }

    OCTLogInfo(@"groupJoin chatId=%@ peer=%@ result=%u", chatIdHex, peerName, result);

    return result;
}

- (OCTToxGroupNumber)groupInviteAcceptWithFriendNumber:(OCTToxFriendNumber)friendNumber
                                            inviteData:(NSData *)inviteData
                                              peerName:(NSString *)peerName
                                              password:(nullable NSString *)password
                                                 error:(NSError **)error
{
    NSParameterAssert(inviteData);
    NSParameterAssert(peerName);

    if (inviteData.length == 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupInviteAcceptBadInvite
                                     description:@"Cannot accept group invite"
                                   failureReason:@"Invite data is empty"];
        }
        return kOCTToxGroupNumberFailure;
    }

    const char *cPeerName = [peerName cStringUsingEncoding:NSUTF8StringEncoding];
    size_t peerNameLength = [peerName lengthOfBytesUsingEncoding:NSUTF8StringEncoding];

    const uint8_t *cPassword = NULL;
    size_t passwordLength = 0;

    if (password.length > 0) {
        cPassword = (const uint8_t *)[password cStringUsingEncoding:NSUTF8StringEncoding];
        passwordLength = [password lengthOfBytesUsingEncoding:NSUTF8StringEncoding];
    }

    Tox_Err_Group_Invite_Accept cError;

    OCTToxGroupNumber result = tox_group_invite_accept(self.tox, friendNumber, inviteData.bytes, inviteData.length,
                                                       (const uint8_t *)cPeerName, peerNameLength,
                                                       cPassword, passwordLength, &cError);

    if (result == kOCTToxGroupNumberFailure) {
        [self fillError:error withCErrorGroupInviteAccept:cError];
    }

    OCTLogInfo(@"groupInviteAccept friend=%d bytes=%lu peer=%@ result=%u",
               friendNumber, (unsigned long)inviteData.length, peerName, result);

    return result;
}

- (BOOL)groupInviteFriendWithGroupNumber:(OCTToxGroupNumber)groupNumber
                           friendNumber:(OCTToxFriendNumber)friendNumber
                                  error:(NSError **)error
{
    Tox_Err_Group_Invite_Friend cError;

    bool result = tox_group_invite_friend(self.tox, groupNumber, friendNumber, &cError);

    [self fillError:error withCErrorGroupInviteFriend:cError];

    OCTLogInfo(@"groupInviteFriend group=%u friend=%d result=%d", groupNumber, friendNumber, result);

    return (BOOL)result;
}

- (BOOL)groupLeaveWithGroupNumber:(OCTToxGroupNumber)groupNumber
                     partMessage:(NSString *)partMessage
                           error:(NSError **)error
{
    const uint8_t *cPartMessage = NULL;
    size_t partMessageLength = 0;

    if (partMessage.length > 0) {
        cPartMessage = (const uint8_t *)[partMessage cStringUsingEncoding:NSUTF8StringEncoding];
        partMessageLength = [partMessage lengthOfBytesUsingEncoding:NSUTF8StringEncoding];
    }

    Tox_Err_Group_Leave cError;

    bool result = tox_group_leave(self.tox, groupNumber, cPartMessage, partMessageLength, &cError);

    [self fillError:error withCErrorGroupLeave:cError];

    OCTLogInfo(@"groupLeave group=%u result=%d", groupNumber, result);

    return (BOOL)result;
}

- (BOOL)groupSendMessage:(NSString *)message
                    type:(OCTToxMessageType)type
             groupNumber:(OCTToxGroupNumber)groupNumber
               messageId:(uint32_t *)messageId
                   error:(NSError **)error
{
    NSParameterAssert(message);

    const char *cMessage = [message cStringUsingEncoding:NSUTF8StringEncoding];
    size_t length = [message lengthOfBytesUsingEncoding:NSUTF8StringEncoding];
    Tox_Message_Type cType = [self cMessageTypeFromMessageType:type];

    Tox_Err_Group_Send_Message cError;

    bool result = tox_group_send_message(self.tox, groupNumber, cType, (const uint8_t *)cMessage, length, messageId, &cError);

    [self fillError:error withCErrorGroupSendMessage:cError];

    return (BOOL)result;
}

- (BOOL)groupSendCustomPacket:(NSData *)data
                  groupNumber:(OCTToxGroupNumber)groupNumber
                     lossless:(BOOL)lossless
                        error:(NSError **)error
{
    NSParameterAssert(data);

    if (data.length == 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupSendCustomPacketEmpty
                                     description:@"Cannot send group custom packet"
                                   failureReason:@"Packet is empty"];
        }
        return NO;
    }

    Tox_Err_Group_Send_Custom_Packet cError;

    bool result = tox_group_send_custom_packet(self.tox,
                                               groupNumber,
                                               lossless,
                                               data.bytes,
                                               data.length,
                                               &cError);

    [self fillError:error withCErrorGroupSendCustomPacket:cError];

    return (BOOL)result;
}

- (BOOL)groupSendCustomPrivatePacket:(NSData *)data
                         groupNumber:(OCTToxGroupNumber)groupNumber
                              peerId:(uint32_t)peerId
                            lossless:(BOOL)lossless
                               error:(NSError **)error
{
    NSParameterAssert(data);

    if (data.length == 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupSendCustomPacketEmpty
                                     description:@"Cannot send group custom private packet"
                                   failureReason:@"Packet is empty"];
        }
        return NO;
    }

    Tox_Err_Group_Send_Custom_Private_Packet cError;

    bool result = tox_group_send_custom_private_packet(self.tox,
                                                       groupNumber,
                                                       peerId,
                                                       lossless,
                                                       data.bytes,
                                                       data.length,
                                                       &cError);

    [self fillError:error withCErrorGroupSendCustomPrivatePacket:cError];

    return (BOOL)result;
}

- (NSString *)groupChatIdHexForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    uint8_t chatId[kOCTToxGroupChatIdLength];
    Tox_Err_Group_State_Queries cError;

    bool result = tox_group_get_chat_id(self.tox, groupNumber, chatId, &cError);

    [self fillError:error withCErrorGroupStateQueries:cError];

    if (! result) {
        return nil;
    }

    return [OCTTox binToHexString:chatId length:kOCTToxGroupChatIdLength];
}

- (int32_t)groupConnectionStatusForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_Is_Connected cError;

    int32_t status = tox_group_is_connected(self.tox, groupNumber, &cError);

    if (cError == TOX_ERR_GROUP_IS_CONNECTED_GROUP_NOT_FOUND) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group connection status"
                                   failureReason:@"Group not found"];
        }
        return -1;
    }

    return status;
}

- (uint32_t)groupSelfPeerIdForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_Self_Query cError;
    uint32_t peerId = tox_group_self_get_peer_id(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_SELF_QUERY_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query self peer id"
                                   failureReason:@"Group not found"];
        }
        return 0;
    }

    return peerId;
}

- (BOOL)groupSelfSetName:(NSString *)name
             groupNumber:(OCTToxGroupNumber)groupNumber
                   error:(NSError **)error
{
    NSParameterAssert(name);

    const char *cName = [name cStringUsingEncoding:NSUTF8StringEncoding];
    size_t length = [name lengthOfBytesUsingEncoding:NSUTF8StringEncoding];
    Tox_Err_Group_Self_Name_Set cError;

    bool result = tox_group_self_set_name(self.tox, groupNumber, (const uint8_t *)cName, length, &cError);

    if (! result || cError != TOX_ERR_GROUP_SELF_NAME_SET_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot set group self name"
                                   failureReason:@"Group not found or name invalid"];
        }
        return NO;
    }

    return YES;
}

- (NSString *)groupSelfNameForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_Self_Query cError;
    size_t length = tox_group_self_get_name_size(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_SELF_QUERY_OK || length == 0) {
        if (cError != TOX_ERR_GROUP_SELF_QUERY_OK && error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group self name"
                                   failureReason:@"Group not found"];
        }
        return nil;
    }

    uint8_t *nameBytes = calloc(length, sizeof(uint8_t));

    if (! nameBytes) {
        return nil;
    }

    bool ok = tox_group_self_get_name(self.tox, groupNumber, nameBytes, &cError);
    NSString *name = nil;

    if (ok && cError == TOX_ERR_GROUP_SELF_QUERY_OK) {
        name = [[NSString alloc] initWithBytes:nameBytes length:length encoding:NSUTF8StringEncoding];
    }

    free(nameBytes);

    if (! ok && error) {
        *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                 description:@"Cannot query group self name"
                               failureReason:@"Group not found"];
    }

    return name;
}

- (NSString *)groupPeerPublicKeyHexForGroupNumber:(OCTToxGroupNumber)groupNumber
                                           peerId:(uint32_t)peerId
                                            error:(NSError **)error
{
    uint8_t publicKey[TOX_GROUP_PEER_PUBLIC_KEY_SIZE];
    Tox_Err_Group_Peer_Query cError;

    bool result = tox_group_peer_get_public_key(self.tox, groupNumber, peerId, publicKey, &cError);

    if (! result || cError != TOX_ERR_GROUP_PEER_QUERY_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group peer public key"
                                   failureReason:@"Peer not found"];
        }
        return nil;
    }

    return [OCTTox binToHexString:publicKey length:TOX_GROUP_PEER_PUBLIC_KEY_SIZE];
}

- (OCTToxConnectionStatus)groupPeerConnectionStatusForGroupNumber:(OCTToxGroupNumber)groupNumber
                                                             peerId:(uint32_t)peerId
                                                              error:(NSError **)error
{
    Tox_Err_Group_Peer_Query cError;
    Tox_Connection cStatus = tox_group_peer_get_connection_status(self.tox, groupNumber, peerId, &cError);

    if (cError != TOX_ERR_GROUP_PEER_QUERY_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group peer connection status"
                                   failureReason:@"Peer not found"];
        }
        return OCTToxConnectionStatusNone;
    }

    return [self userConnectionStatusFromCUserStatus:(TOX_CONNECTION)cStatus];
}

- (NSString *)groupSelfPublicKeyHexForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    uint8_t publicKey[TOX_GROUP_PEER_PUBLIC_KEY_SIZE];
    Tox_Err_Group_Self_Query cError;

    bool result = tox_group_self_get_public_key(self.tox, groupNumber, publicKey, &cError);

    if (! result || cError != TOX_ERR_GROUP_SELF_QUERY_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query self group public key"
                                   failureReason:@"Group not found"];
        }
        return nil;
    }

    return [OCTTox binToHexString:publicKey length:TOX_GROUP_PEER_PUBLIC_KEY_SIZE];
}

- (uint32_t)groupPeerIdForPublicKeyHex:(NSString *)publicKeyHex
                           groupNumber:(OCTToxGroupNumber)groupNumber
                                 error:(NSError **)error
{
    if (publicKeyHex.length == 0) {
        return 0;
    }

    uint8_t *publicKey = [OCTTox hexStringToBin:publicKeyHex];

    if (! publicKey) {
        return 0;
    }

    Tox_Err_Group_Peer_Query cError;
    uint32_t peerId = tox_group_peer_by_public_key(self.tox, groupNumber, publicKey, &cError);
    free(publicKey);

    if (cError != TOX_ERR_GROUP_PEER_QUERY_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot resolve group peer id"
                                   failureReason:@"Peer not found"];
        }
        return 0;
    }

    return peerId;
}

- (OCTToxGroupPrivacyState)groupPrivacyStateForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_State_Queries cError;
    Tox_Group_Privacy_State privacyState = tox_group_get_privacy_state(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_STATE_QUERIES_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group privacy state"
                                   failureReason:@"Group not found"];
        }
        return OCTToxGroupPrivacyStatePublic;
    }

    return [self groupPrivacyStateFromCPrivacyState:privacyState];
}

- (BOOL)groupFounderSetPrivacyState:(OCTToxGroupPrivacyState)privacyState
                        groupNumber:(OCTToxGroupNumber)groupNumber
                              error:(NSError **)error
{
    Tox_Err_Group_Founder_Set_Privacy_State cError;
    bool result = tox_group_founder_set_privacy_state(self.tox,
                                                      groupNumber,
                                                      [self cPrivacyStateFromGroupPrivacyState:privacyState],
                                                      &cError);

    [self fillError:error withCErrorGroupFounderSetPrivacyState:cError];

    return (BOOL)result;
}

- (OCTToxGroupVoiceState)groupVoiceStateForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_State_Queries cError;
    Tox_Group_Voice_State voiceState = tox_group_get_voice_state(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_STATE_QUERIES_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group voice state"
                                   failureReason:@"Group not found"];
        }
        return OCTToxGroupVoiceStateAll;
    }

    return [self groupVoiceStateFromCVoiceState:voiceState];
}

- (BOOL)groupFounderSetVoiceState:(OCTToxGroupVoiceState)voiceState
                     groupNumber:(OCTToxGroupNumber)groupNumber
                           error:(NSError **)error
{
    Tox_Err_Group_Founder_Set_Voice_State cError;
    bool result = tox_group_founder_set_voice_state(self.tox,
                                                    groupNumber,
                                                    [self cVoiceStateFromGroupVoiceState:voiceState],
                                                    &cError);

    [self fillError:error withCErrorGroupFounderSetVoiceState:cError];

    return (BOOL)result;
}

- (OCTToxGroupRole)groupSelfRoleForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_Self_Query cError;
    Tox_Group_Role role = tox_group_self_get_role(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_SELF_QUERY_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query self group role"
                                   failureReason:@"Group not found"];
        }
        return OCTToxGroupRoleUser;
    }

    return [self groupRoleFromCRole:role];
}

- (OCTToxGroupRole)groupPeerRoleForGroupNumber:(OCTToxGroupNumber)groupNumber
                                        peerId:(uint32_t)peerId
                                         error:(NSError **)error
{
    Tox_Err_Group_Peer_Query cError;
    Tox_Group_Role role = tox_group_peer_get_role(self.tox, groupNumber, peerId, &cError);

    if (cError != TOX_ERR_GROUP_PEER_QUERY_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group peer role"
                                   failureReason:@"Peer not found"];
        }
        return OCTToxGroupRoleObserver;
    }

    return [self groupRoleFromCRole:role];
}

- (BOOL)groupKickPeerWithId:(uint32_t)peerId
               groupNumber:(OCTToxGroupNumber)groupNumber
                     error:(NSError **)error
{
    Tox_Err_Group_Mod_Kick_Peer cError;
    bool result = tox_group_mod_kick_peer(self.tox, groupNumber, peerId, &cError);

    [self fillError:error withCErrorGroupModKickPeer:cError];

    return (BOOL)result;
}

- (BOOL)groupModSetRole:(OCTToxGroupRole)role
                 peerId:(uint32_t)peerId
            groupNumber:(OCTToxGroupNumber)groupNumber
                  error:(NSError **)error
{
    Tox_Err_Group_Mod_Set_Role cError;
    bool result = tox_group_mod_set_role(self.tox, groupNumber, peerId, [self groupRoleToCRole:role], &cError);

    [self fillError:error withCErrorGroupModSetRole:cError];

    return (BOOL)result;
}

- (uint32_t)groupPeerCountForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_Peer_Query cError;
    uint32_t count = tox_group_peer_count(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_PEER_QUERY_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group peer count"
                                   failureReason:@"Group not found"];
        }
        return 0;
    }

    return count;
}

- (uint32_t)groupOfflinePeerCountForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_Peer_Query cError;
    uint32_t count = tox_group_offline_peer_count(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_PEER_QUERY_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group offline peer count"
                                   failureReason:@"Group not found"];
        }
        return 0;
    }

    return count;
}

- (NSArray<NSDictionary *> *)groupPeersForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_Peer_Query cError;
    uint32_t count = tox_group_peer_count(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_PEER_QUERY_OK || count == 0) {
        if (cError != TOX_ERR_GROUP_PEER_QUERY_OK && error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group peer list"
                                   failureReason:@"Group not found"];
        }
        return @[];
    }

    uint32_t *peerlist = calloc(count, sizeof(uint32_t));

    if (! peerlist) {
        return @[];
    }

    tox_group_get_peerlist(self.tox, groupNumber, peerlist, &cError);

    if (cError != TOX_ERR_GROUP_PEER_QUERY_OK) {
        free(peerlist);

        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group peer list"
                                   failureReason:@"Group not found"];
        }

        return @[];
    }

    NSMutableArray *result = [NSMutableArray arrayWithCapacity:count];

    for (uint32_t i = 0; i < count; i++) {
        uint32_t peerId = peerlist[i];
        Tox_Err_Group_Peer_Query nameError;
        size_t nameSize = tox_group_peer_get_name_size(self.tox, groupNumber, peerId, &nameError);
        NSString *name = @"";

        if (nameError == TOX_ERR_GROUP_PEER_QUERY_OK && nameSize > 0) {
            uint8_t *nameBytes = calloc(nameSize, sizeof(uint8_t));

            if (nameBytes) {
                if (tox_group_peer_get_name(self.tox, groupNumber, peerId, nameBytes, &nameError)) {
                    name = [[NSString alloc] initWithBytes:nameBytes length:nameSize encoding:NSUTF8StringEncoding] ?: @"";
                }

                free(nameBytes);
            }
        }

        if (name.length == 0) {
            name = [NSString stringWithFormat:@"Peer %u", peerId];
        }

        Tox_Err_Group_Peer_Query roleError;
        Tox_Group_Role cRole = tox_group_peer_get_role(self.tox, groupNumber, peerId, &roleError);
        OCTToxGroupRole role = roleError == TOX_ERR_GROUP_PEER_QUERY_OK
            ? [self groupRoleFromCRole:cRole]
            : OCTToxGroupRoleUser;

        [result addObject:@{
            @"peerId" : @(peerId),
            @"name" : name,
            @"role" : @(role),
        }];
    }

    free(peerlist);
    return result;
}

- (nullable NSString *)groupTopicForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_State_Queries cError;
    size_t size = tox_group_get_topic_size(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_STATE_QUERIES_OK || size == 0) {
        if (cError != TOX_ERR_GROUP_STATE_QUERIES_OK && error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group topic"
                                   failureReason:@"Group not found"];
        }
        return nil;
    }

    uint8_t *bytes = calloc(size, sizeof(uint8_t));

    if (! bytes) {
        return nil;
    }

    if (! tox_group_get_topic(self.tox, groupNumber, bytes, &cError)) {
        free(bytes);

        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group topic"
                                   failureReason:@"Group not found"];
        }

        return nil;
    }

    NSString *topic = [[NSString alloc] initWithBytes:bytes length:size encoding:NSUTF8StringEncoding];
    free(bytes);
    return topic;
}

// KHANDAQ (#15): authoritative, immutable NGC group name (tox_group_get_name). Distinct from the
// mutable topic. Used to keep chat.groupName correct in the list instead of falling back to the
// topic / a hex placeholder.
- (nullable NSString *)groupNameForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_State_Queries cError;
    size_t size = tox_group_get_name_size(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_STATE_QUERIES_OK || size == 0) {
        if (cError != TOX_ERR_GROUP_STATE_QUERIES_OK && error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group name"
                                   failureReason:@"Group not found"];
        }
        return nil;
    }

    uint8_t *bytes = calloc(size, sizeof(uint8_t));

    if (! bytes) {
        return nil;
    }

    if (! tox_group_get_name(self.tox, groupNumber, bytes, &cError)) {
        free(bytes);

        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group name"
                                   failureReason:@"Group not found"];
        }

        return nil;
    }

    NSString *name = [[NSString alloc] initWithBytes:bytes length:size encoding:NSUTF8StringEncoding];
    free(bytes);
    return name;
}

- (BOOL)groupSetTopic:(NSString *)topic groupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    NSData *data = [topic dataUsingEncoding:NSUTF8StringEncoding] ?: [NSData data];
    Tox_Err_Group_Topic_Set cError;
    bool result = tox_group_set_topic(self.tox, groupNumber, data.bytes, data.length, &cError);

    [self fillError:error withCErrorGroupTopicSet:cError];

    return (BOOL)result;
}

- (nullable NSString *)groupPasswordForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_State_Queries cError;
    size_t size = tox_group_get_password_size(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_STATE_QUERIES_OK || size == 0) {
        return nil;
    }

    uint8_t *bytes = calloc(size, sizeof(uint8_t));

    if (! bytes) {
        return nil;
    }

    if (! tox_group_get_password(self.tox, groupNumber, bytes, &cError)) {
        free(bytes);
        return nil;
    }

    NSString *password = [[NSString alloc] initWithBytes:bytes length:size encoding:NSUTF8StringEncoding];
    free(bytes);
    return password;
}

- (BOOL)groupFounderSetPassword:(NSString *)password
                   groupNumber:(OCTToxGroupNumber)groupNumber
                         error:(NSError **)error
{
    NSData *data = password.length > 0 ? [password dataUsingEncoding:NSUTF8StringEncoding] : [NSData data];
    Tox_Err_Group_Founder_Set_Password cError;
    bool result = tox_group_founder_set_password(self.tox,
                                                 groupNumber,
                                                 data.length > 0 ? data.bytes : NULL,
                                                 data.length,
                                                 &cError);

    [self fillError:error withCErrorGroupFounderSetPassword:cError];

    return (BOOL)result;
}

- (OCTToxGroupTopicLock)groupTopicLockForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_State_Queries cError;
    Tox_Group_Topic_Lock lock = tox_group_get_topic_lock(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_STATE_QUERIES_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group topic lock"
                                   failureReason:@"Group not found"];
        }
        return OCTToxGroupTopicLockDisabled;
    }

    return [self groupTopicLockFromCLock:lock];
}

- (BOOL)groupFounderSetTopicLock:(OCTToxGroupTopicLock)topicLock
                     groupNumber:(OCTToxGroupNumber)groupNumber
                           error:(NSError **)error
{
    Tox_Err_Group_Founder_Set_Topic_Lock cError;
    bool result = tox_group_founder_set_topic_lock(self.tox,
                                                   groupNumber,
                                                   [self groupTopicLockToCLock:topicLock],
                                                   &cError);

    [self fillError:error withCErrorGroupFounderSetTopicLock:cError];

    return (BOOL)result;
}

- (uint16_t)groupPeerLimitForGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_State_Queries cError;
    uint16_t limit = tox_group_get_peer_limit(self.tox, groupNumber, &cError);

    if (cError != TOX_ERR_GROUP_STATE_QUERIES_OK) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupStateQueriesGroupNotFound
                                     description:@"Cannot query group peer limit"
                                   failureReason:@"Group not found"];
        }
        return 0;
    }

    return limit;
}

- (BOOL)groupFounderSetPeerLimit:(uint16_t)peerLimit
                     groupNumber:(OCTToxGroupNumber)groupNumber
                           error:(NSError **)error
{
    Tox_Err_Group_Founder_Set_Peer_Limit cError;
    bool result = tox_group_founder_set_peer_limit(self.tox, groupNumber, peerLimit, &cError);

    [self fillError:error withCErrorGroupFounderSetPeerLimit:cError];

    return (BOOL)result;
}

- (BOOL)groupSendPrivateMessage:(NSString *)message
                           type:(OCTToxMessageType)type
                    groupNumber:(OCTToxGroupNumber)groupNumber
                         peerId:(uint32_t)peerId
                          error:(NSError **)error
{
    NSData *data = [message dataUsingEncoding:NSUTF8StringEncoding];

    if (! data || data.length == 0) {
        if (error) {
            *error = [OCTTox createErrorWithCode:OCTToxErrorGroupSendPrivateMessageEmpty
                                     description:@"Cannot send group private message"
                                   failureReason:@"Message is empty"];
        }
        return NO;
    }

    Tox_Err_Group_Send_Private_Message cError;
    bool result = tox_group_send_private_message(self.tox,
                                                 groupNumber,
                                                 peerId,
                                                 [self cMessageTypeFromMessageType:type],
                                                 data.bytes,
                                                 data.length,
                                                 &cError);

    [self fillError:error withCErrorGroupSendPrivateMessage:cError];

    return (BOOL)result;
}

- (uint32_t)groupCount
{
    return tox_group_get_number_groups(self.tox);
}

- (NSArray<NSNumber *> *)groupNumbers
{
    uint32_t count = tox_group_get_number_groups(self.tox);

    if (count == 0) {
        return @[];
    }

    uint32_t *groupList = calloc(count, sizeof(uint32_t));
    tox_group_get_grouplist(self.tox, groupList);

    NSMutableArray<NSNumber *> *result = [NSMutableArray arrayWithCapacity:count];

    for (uint32_t i = 0; i < count; i++) {
        [result addObject:@(groupList[i])];
    }

    free(groupList);
    return result;
}

- (BOOL)groupReconnectWithGroupNumber:(OCTToxGroupNumber)groupNumber error:(NSError **)error
{
    Tox_Err_Group_Reconnect cError;
    bool result = tox_group_reconnect(self.tox, groupNumber, &cError);

    if (! result && error) {
        *error = [OCTTox createErrorWithCode:OCTToxErrorGroupSendMessageFailSend
                                 description:@"Cannot reconnect group"
                               failureReason:@"Group reconnect failed"];
    }

    OCTLogInfo(@"groupReconnect group=%u result=%d", groupNumber, result);

    return (BOOL)result;
}

#pragma mark -  Private methods

- (void)updateTimerIntervalIfNeeded
{
    uint64_t nextIterate = tox_iteration_interval(self.tox) * USEC_PER_SEC;

    if (self.previousIterate == nextIterate) {
        return;
    }

    self.previousIterate = nextIterate;
    dispatch_source_set_timer(self.timer, dispatch_walltime(NULL, nextIterate), nextIterate, nextIterate / 5);
}

- (void)setupCFunctions
{
    _tox_self_get_public_key = tox_self_get_public_key;
}

- (void)setupCallbacks
{
    tox_callback_self_connection_status(_tox, connectionStatusCallback);
    tox_callback_friend_name(_tox, friendNameCallback);
    tox_callback_friend_status_message(_tox, friendStatusMessageCallback);
    tox_callback_friend_status(_tox, friendStatusCallback);
    tox_callback_friend_connection_status(_tox, friendConnectionStatusCallback);
    tox_callback_friend_typing(_tox, friendTypingCallback);
    tox_callback_friend_read_receipt(_tox, friendReadReceiptCallback);
    tox_callback_friend_request(_tox, friendRequestCallback);
    tox_callback_friend_message(_tox, friendMessageCallback);
    tox_callback_friend_lossless_packet(_tox, friendLosslessPacketCallback);
    tox_callback_file_recv_control(_tox, fileReceiveControlCallback);
    tox_callback_file_chunk_request(_tox, fileChunkRequestCallback);
    tox_callback_file_recv(_tox, fileReceiveCallback);
    tox_callback_file_recv_chunk(_tox, fileReceiveChunkCallback);
    tox_callback_group_message(_tox, groupMessageCallback);
    tox_callback_group_connection_status(_tox, groupConnectionStatusCallback);
    tox_callback_group_peer_join(_tox, groupPeerJoinCallback);
    tox_callback_group_peer_name(_tox, groupPeerNameCallback);
    tox_callback_group_invite(_tox, groupInviteCallback);
    tox_callback_group_peer_exit(_tox, groupPeerExitCallback);
    tox_callback_group_custom_packet(_tox, groupCustomPacketCallback);
    tox_callback_group_custom_private_packet(_tox, groupCustomPrivatePacketCallback);
    tox_callback_group_moderation(_tox, groupModerationCallback);
    tox_callback_group_topic(_tox, groupTopicCallback);
    tox_callback_group_password(_tox, groupPasswordCallback);
    tox_callback_group_topic_lock(_tox, groupTopicLockCallback);
    tox_callback_group_peer_limit(_tox, groupPeerLimitCallback);
    tox_callback_group_privacy_state(_tox, groupPrivacyStateCallback);
    tox_callback_group_voice_state(_tox, groupVoiceStateCallback);
    tox_callback_group_join_fail(_tox, groupJoinFailCallback);
    tox_callback_group_private_message(_tox, groupPrivateMessageCallback);
}

- (OCTToxUserStatus)userStatusFromCUserStatus:(TOX_USER_STATUS)cStatus
{
    switch (cStatus) {
        case TOX_USER_STATUS_NONE:
            return OCTToxUserStatusNone;
        case TOX_USER_STATUS_AWAY:
            return OCTToxUserStatusAway;
        case TOX_USER_STATUS_BUSY:
            return OCTToxUserStatusBusy;
    }
}

- (OCTToxConnectionStatus)userConnectionStatusFromCUserStatus:(TOX_CONNECTION)cStatus
{
    switch (cStatus) {
        case TOX_CONNECTION_NONE:
            return OCTToxConnectionStatusNone;
        case TOX_CONNECTION_TCP:
            return OCTToxConnectionStatusTCP;
        case TOX_CONNECTION_UDP:
            return OCTToxConnectionStatusUDP;
    }
}

- (OCTToxMessageType)messageTypeFromCMessageType:(TOX_MESSAGE_TYPE)cType
{
    switch (cType) {
        case TOX_MESSAGE_TYPE_NORMAL:
            return OCTToxMessageTypeNormal;
        case TOX_MESSAGE_TYPE_ACTION:
            return OCTToxMessageTypeAction;
        default:
            return OCTToxMessageTypeNormal;
    }
}

- (Tox_Message_Type)cMessageTypeFromMessageType:(OCTToxMessageType)type
{
    switch (type) {
        case OCTToxMessageTypeNormal:
            return TOX_MESSAGE_TYPE_NORMAL;
        case OCTToxMessageTypeAction:
            return TOX_MESSAGE_TYPE_ACTION;
        case OCTToxMessageTypeHighlevelack:
            return TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK;
    }
}

- (OCTToxGroupPrivacyState)groupPrivacyStateFromCPrivacyState:(Tox_Group_Privacy_State)cState
{
    switch (cState) {
        case TOX_GROUP_PRIVACY_STATE_PUBLIC:
            return OCTToxGroupPrivacyStatePublic;
        case TOX_GROUP_PRIVACY_STATE_PRIVATE:
            return OCTToxGroupPrivacyStatePrivate;
    }
}

- (Tox_Group_Privacy_State)cPrivacyStateFromGroupPrivacyState:(OCTToxGroupPrivacyState)privacyState
{
    switch (privacyState) {
        case OCTToxGroupPrivacyStatePublic:
            return TOX_GROUP_PRIVACY_STATE_PUBLIC;
        case OCTToxGroupPrivacyStatePrivate:
            return TOX_GROUP_PRIVACY_STATE_PRIVATE;
    }
}

- (OCTToxGroupVoiceState)groupVoiceStateFromCVoiceState:(Tox_Group_Voice_State)cState
{
    switch (cState) {
        case TOX_GROUP_VOICE_STATE_ALL:
            return OCTToxGroupVoiceStateAll;
        case TOX_GROUP_VOICE_STATE_MODERATOR:
            return OCTToxGroupVoiceStateModerator;
        case TOX_GROUP_VOICE_STATE_FOUNDER:
            return OCTToxGroupVoiceStateFounder;
    }
}

- (Tox_Group_Voice_State)cVoiceStateFromGroupVoiceState:(OCTToxGroupVoiceState)voiceState
{
    switch (voiceState) {
        case OCTToxGroupVoiceStateAll:
            return TOX_GROUP_VOICE_STATE_ALL;
        case OCTToxGroupVoiceStateModerator:
            return TOX_GROUP_VOICE_STATE_MODERATOR;
        case OCTToxGroupVoiceStateFounder:
            return TOX_GROUP_VOICE_STATE_FOUNDER;
    }
}

- (OCTToxGroupJoinFail)groupJoinFailFromCFail:(Tox_Group_Join_Fail)cFail
{
    switch (cFail) {
        case TOX_GROUP_JOIN_FAIL_PEER_LIMIT:
            return OCTToxGroupJoinFailPeerLimit;
        case TOX_GROUP_JOIN_FAIL_INVALID_PASSWORD:
            return OCTToxGroupJoinFailInvalidPassword;
        case TOX_GROUP_JOIN_FAIL_UNKNOWN:
            return OCTToxGroupJoinFailUnknown;
    }
}

- (OCTToxGroupExitType)groupExitTypeFromCExitType:(Tox_Group_Exit_Type)cType
{
    switch (cType) {
        case TOX_GROUP_EXIT_TYPE_QUIT:
            return OCTToxGroupExitTypeQuit;
        case TOX_GROUP_EXIT_TYPE_TIMEOUT:
            return OCTToxGroupExitTypeTimeout;
        case TOX_GROUP_EXIT_TYPE_DISCONNECTED:
            return OCTToxGroupExitTypeDisconnected;
        case TOX_GROUP_EXIT_TYPE_SELF_DISCONNECTED:
            return OCTToxGroupExitTypeSelfDisconnected;
        case TOX_GROUP_EXIT_TYPE_KICK:
            return OCTToxGroupExitTypeKick;
        case TOX_GROUP_EXIT_TYPE_SYNC_ERROR:
            return OCTToxGroupExitTypeSyncError;
    }
}

- (OCTToxGroupRole)groupRoleFromCRole:(Tox_Group_Role)cRole
{
    switch (cRole) {
        case TOX_GROUP_ROLE_FOUNDER:
            return OCTToxGroupRoleFounder;
        case TOX_GROUP_ROLE_MODERATOR:
            return OCTToxGroupRoleModerator;
        case TOX_GROUP_ROLE_USER:
            return OCTToxGroupRoleUser;
        case TOX_GROUP_ROLE_OBSERVER:
            return OCTToxGroupRoleObserver;
    }
}

- (Tox_Group_Role)groupRoleToCRole:(OCTToxGroupRole)role
{
    switch (role) {
        case OCTToxGroupRoleFounder:
            return TOX_GROUP_ROLE_FOUNDER;
        case OCTToxGroupRoleModerator:
            return TOX_GROUP_ROLE_MODERATOR;
        case OCTToxGroupRoleUser:
            return TOX_GROUP_ROLE_USER;
        case OCTToxGroupRoleObserver:
            return TOX_GROUP_ROLE_OBSERVER;
    }
}

- (OCTToxGroupModEvent)groupModEventFromCEvent:(Tox_Group_Mod_Event)cEvent
{
    switch (cEvent) {
        case TOX_GROUP_MOD_EVENT_KICK:
            return OCTToxGroupModEventKick;
        case TOX_GROUP_MOD_EVENT_OBSERVER:
            return OCTToxGroupModEventObserver;
        case TOX_GROUP_MOD_EVENT_USER:
            return OCTToxGroupModEventUser;
        case TOX_GROUP_MOD_EVENT_MODERATOR:
            return OCTToxGroupModEventModerator;
    }
}

- (OCTToxGroupTopicLock)groupTopicLockFromCLock:(Tox_Group_Topic_Lock)cLock
{
    switch (cLock) {
        case TOX_GROUP_TOPIC_LOCK_ENABLED:
            return OCTToxGroupTopicLockEnabled;
        case TOX_GROUP_TOPIC_LOCK_DISABLED:
            return OCTToxGroupTopicLockDisabled;
    }
}

- (Tox_Group_Topic_Lock)groupTopicLockToCLock:(OCTToxGroupTopicLock)topicLock
{
    switch (topicLock) {
        case OCTToxGroupTopicLockEnabled:
            return TOX_GROUP_TOPIC_LOCK_ENABLED;
        case OCTToxGroupTopicLockDisabled:
            return TOX_GROUP_TOPIC_LOCK_DISABLED;
    }
}

- (OCTToxFileControl)fileControlFromCFileControl:(TOX_FILE_CONTROL)cControl
{
    switch (cControl) {
        case TOX_FILE_CONTROL_RESUME:
            return OCTToxFileControlResume;
        case TOX_FILE_CONTROL_PAUSE:
            return OCTToxFileControlPause;
        case TOX_FILE_CONTROL_CANCEL:
            return OCTToxFileControlCancel;
    }
}

- (BOOL)fillError:(NSError **)error withCErrorInit:(TOX_ERR_NEW)cError
{
    if (! error || (cError == TOX_ERR_NEW_OK)) {
        return NO;
    }

    OCTToxErrorInitCode code = OCTToxErrorInitCodeUnknown;
    NSString *description = @"Cannot initialize Tox";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_NEW_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_NEW_NULL:
            code = OCTToxErrorInitCodeUnknown;
            failureReason = @"Unknown error occured";
            break;
        case TOX_ERR_NEW_MALLOC:
            code = OCTToxErrorInitCodeMemoryError;
            failureReason = @"Not enough memory";
            break;
        case TOX_ERR_NEW_PORT_ALLOC:
            code = OCTToxErrorInitCodePortAlloc;
            failureReason = @"Cannot bint to a port";
            break;
        case TOX_ERR_NEW_PROXY_BAD_TYPE:
            code = OCTToxErrorInitCodeProxyBadType;
            failureReason = @"Proxy type is invalid";
            break;
        case TOX_ERR_NEW_PROXY_BAD_HOST:
            code = OCTToxErrorInitCodeProxyBadHost;
            failureReason = @"Proxy host is invalid";
            break;
        case TOX_ERR_NEW_PROXY_BAD_PORT:
            code = OCTToxErrorInitCodeProxyBadPort;
            failureReason = @"Proxy port is invalid";
            break;
        case TOX_ERR_NEW_PROXY_NOT_FOUND:
            code = OCTToxErrorInitCodeProxyNotFound;
            failureReason = @"Proxy host could not be resolved";
            break;
        case TOX_ERR_NEW_LOAD_ENCRYPTED:
            code = OCTToxErrorInitCodeEncrypted;
            failureReason = @"Tox save is encrypted";
            break;
        case TOX_ERR_NEW_LOAD_BAD_FORMAT:
            code = OCTToxErrorInitCodeLoadBadFormat;
            failureReason = @"Tox save is corrupted";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorBootstrap:(TOX_ERR_BOOTSTRAP)cError
{
    if (! error || (cError == TOX_ERR_BOOTSTRAP_OK)) {
        return NO;
    }

    OCTToxErrorBootstrapCode code = OCTToxErrorBootstrapCodeUnknown;
    NSString *description = @"Cannot bootstrap with specified node";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_BOOTSTRAP_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_BOOTSTRAP_NULL:
            code = OCTToxErrorBootstrapCodeUnknown;
            failureReason = @"Unknown error occured";
            break;
        case TOX_ERR_BOOTSTRAP_BAD_HOST:
            code = OCTToxErrorBootstrapCodeBadHost;
            failureReason = @"The host could not be resolved to an IP address, or the IP address passed was invalid";
            break;
        case TOX_ERR_BOOTSTRAP_BAD_PORT:
            code = OCTToxErrorBootstrapCodeBadPort;
            failureReason = @"The port passed was invalid";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFriendAdd:(TOX_ERR_FRIEND_ADD)cError
{
    if (! error || (cError == TOX_ERR_FRIEND_ADD_OK)) {
        return NO;
    }

    OCTToxErrorFriendAdd code = OCTToxErrorFriendAddUnknown;
    NSString *description = @"Cannot add friend";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FRIEND_ADD_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FRIEND_ADD_NULL:
            code = OCTToxErrorFriendAddUnknown;
            failureReason = @"Unknown error occured";
            break;
        case TOX_ERR_FRIEND_ADD_TOO_LONG:
            code = OCTToxErrorFriendAddTooLong;
            failureReason = @"The message is too long";
            break;
        case TOX_ERR_FRIEND_ADD_NO_MESSAGE:
            code = OCTToxErrorFriendAddNoMessage;
            failureReason = @"No message specified";
            break;
        case TOX_ERR_FRIEND_ADD_OWN_KEY:
            code = OCTToxErrorFriendAddOwnKey;
            failureReason = @"Cannot add own address";
            break;
        case TOX_ERR_FRIEND_ADD_ALREADY_SENT:
            code = OCTToxErrorFriendAddAlreadySent;
            failureReason = @"The request was already sent";
            break;
        case TOX_ERR_FRIEND_ADD_BAD_CHECKSUM:
            code = OCTToxErrorFriendAddBadChecksum;
            failureReason = @"Bad checksum";
            break;
        case TOX_ERR_FRIEND_ADD_SET_NEW_NOSPAM:
            code = OCTToxErrorFriendAddSetNewNospam;
            failureReason = @"The no spam value is outdated";
            break;
        case TOX_ERR_FRIEND_ADD_MALLOC:
            code = OCTToxErrorFriendAddMalloc;
            failureReason = nil;
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFriendDelete:(TOX_ERR_FRIEND_DELETE)cError
{
    if (! error || (cError == TOX_ERR_FRIEND_DELETE_OK)) {
        return NO;
    }

    OCTToxErrorFriendDelete code = OCTToxErrorFriendDeleteNotFound;
    NSString *description = @"Cannot delete friend";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FRIEND_DELETE_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FRIEND_DELETE_FRIEND_NOT_FOUND:
            code = OCTToxErrorFriendDeleteNotFound;
            failureReason = @"Friend not found";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFriendByPublicKey:(TOX_ERR_FRIEND_BY_PUBLIC_KEY)cError
{
    if (! error || (cError == TOX_ERR_FRIEND_BY_PUBLIC_KEY_OK)) {
        return NO;
    }

    OCTToxErrorFriendByPublicKey code = OCTToxErrorFriendByPublicKeyUnknown;
    NSString *description = @"Cannot get friend by public key";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FRIEND_BY_PUBLIC_KEY_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FRIEND_BY_PUBLIC_KEY_NULL:
            code = OCTToxErrorFriendByPublicKeyUnknown;
            failureReason = @"Unknown error occured";
            break;
        case TOX_ERR_FRIEND_BY_PUBLIC_KEY_NOT_FOUND:
            code = OCTToxErrorFriendByPublicKeyNotFound;
            failureReason = @"No friend with the given Public Key exists on the friend list";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFriendGetPublicKey:(TOX_ERR_FRIEND_GET_PUBLIC_KEY)cError
{
    if (! error || (cError == TOX_ERR_FRIEND_GET_PUBLIC_KEY_OK)) {
        return NO;
    }

    OCTToxErrorFriendGetPublicKey code = OCTToxErrorFriendGetPublicKeyFriendNotFound;
    NSString *description = @"Cannot get public key of a friend";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FRIEND_GET_PUBLIC_KEY_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FRIEND_GET_PUBLIC_KEY_FRIEND_NOT_FOUND:
            code = OCTToxErrorFriendGetPublicKeyFriendNotFound;
            failureReason = @"Friend not found";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorSetInfo:(TOX_ERR_SET_INFO)cError
{
    if (! error || (cError == TOX_ERR_SET_INFO_OK)) {
        return NO;
    }

    OCTToxErrorSetInfoCode code = OCTToxErrorSetInfoCodeUnknow;
    NSString *description = @"Cannot set user info";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_SET_INFO_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_SET_INFO_NULL:
            code = OCTToxErrorSetInfoCodeUnknow;
            failureReason = @"Unknown error occured";
            break;
        case TOX_ERR_SET_INFO_TOO_LONG:
            code = OCTToxErrorSetInfoCodeTooLong;
            failureReason = @"Specified string is too long";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFriendGetLastOnline:(TOX_ERR_FRIEND_GET_LAST_ONLINE)cError
{
    if (! error || (cError == TOX_ERR_FRIEND_GET_LAST_ONLINE_OK)) {
        return NO;
    }

    OCTToxErrorFriendGetLastOnline code;
    NSString *description = @"Cannot get last online of a friend";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FRIEND_GET_LAST_ONLINE_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FRIEND_GET_LAST_ONLINE_FRIEND_NOT_FOUND:
            code = OCTToxErrorFriendGetLastOnlineFriendNotFound;
            failureReason = @"Friend not found";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFriendQuery:(TOX_ERR_FRIEND_QUERY)cError
{
    if (! error || (cError == TOX_ERR_FRIEND_QUERY_OK)) {
        return NO;
    }

    OCTToxErrorFriendQuery code = OCTToxErrorFriendQueryUnknown;
    NSString *description = @"Cannot perform friend query";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FRIEND_QUERY_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FRIEND_QUERY_NULL:
            code = OCTToxErrorFriendQueryUnknown;
            failureReason = @"Unknown error occured";
            break;
        case TOX_ERR_FRIEND_QUERY_FRIEND_NOT_FOUND:
            code = OCTToxErrorFriendQueryFriendNotFound;
            failureReason = @"Friend not found";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorSetTyping:(TOX_ERR_SET_TYPING)cError
{
    if (! error || (cError == TOX_ERR_SET_TYPING_OK)) {
        return NO;
    }

    OCTToxErrorSetTyping code = OCTToxErrorSetTypingFriendNotFound;
    NSString *description = @"Cannot set typing status for a friend";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_SET_TYPING_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_SET_TYPING_FRIEND_NOT_FOUND:
            code = OCTToxErrorSetTypingFriendNotFound;
            failureReason = @"Friend not found";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFriendSendMessage:(TOX_ERR_FRIEND_SEND_MESSAGE)cError
{
    if (! error || (cError == TOX_ERR_FRIEND_SEND_MESSAGE_OK)) {
        return NO;
    }

    OCTToxErrorFriendSendMessage code = OCTToxErrorFriendSendMessageUnknown;
    NSString *description = @"Cannot send message to a friend";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FRIEND_SEND_MESSAGE_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FRIEND_SEND_MESSAGE_NULL:
            code = OCTToxErrorFriendSendMessageUnknown;
            failureReason = @"Unknown error occured";
            break;
        case TOX_ERR_FRIEND_SEND_MESSAGE_FRIEND_NOT_FOUND:
            code = OCTToxErrorFriendSendMessageFriendNotFound;
            failureReason = @"Friend not found";
            break;
        case TOX_ERR_FRIEND_SEND_MESSAGE_FRIEND_NOT_CONNECTED:
            code = OCTToxErrorFriendSendMessageFriendNotConnected;
            failureReason = @"Friend not connected";
            break;
        case TOX_ERR_FRIEND_SEND_MESSAGE_SENDQ:
            code = OCTToxErrorFriendSendMessageAlloc;
            failureReason = @"Allocation error";
            break;
        case TOX_ERR_FRIEND_SEND_MESSAGE_TOO_LONG:
            code = OCTToxErrorFriendSendMessageTooLong;
            failureReason = @"Message is too long";
            break;
        case TOX_ERR_FRIEND_SEND_MESSAGE_EMPTY:
            code = OCTToxErrorFriendSendMessageEmpty;
            failureReason = @"Message is empty";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFileControl:(TOX_ERR_FILE_CONTROL)cError
{
    if (! error || (cError == TOX_ERR_FILE_CONTROL_OK)) {
        return NO;
    }

    OCTToxErrorFileControl code;
    NSString *description = @"Cannot send file control to a friend";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FILE_CONTROL_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FILE_CONTROL_FRIEND_NOT_FOUND:
            code = OCTToxErrorFileControlFriendNotFound;
            failureReason = @"Friend not found";
            break;
        case TOX_ERR_FILE_CONTROL_FRIEND_NOT_CONNECTED:
            code = OCTToxErrorFileControlFriendNotConnected;
            failureReason = @"Friend is not connected";
            break;
        case TOX_ERR_FILE_CONTROL_NOT_FOUND:
            code = OCTToxErrorFileControlNotFound;
            failureReason = @"No file transfer with given file number found";
            break;
        case TOX_ERR_FILE_CONTROL_NOT_PAUSED:
            code = OCTToxErrorFileControlNotPaused;
            failureReason = @"Resume was send, but file transfer if running normally";
            break;
        case TOX_ERR_FILE_CONTROL_DENIED:
            code = OCTToxErrorFileControlDenied;
            failureReason = @"Cannot resume, file transfer was paused by the other party.";
            break;
        case TOX_ERR_FILE_CONTROL_ALREADY_PAUSED:
            code = OCTToxErrorFileControlAlreadyPaused;
            failureReason = @"File is already paused";
            break;
        case TOX_ERR_FILE_CONTROL_SENDQ:
            code = OCTToxErrorFileControlSendq;
            failureReason = @"Packet queue is full";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFileSeek:(TOX_ERR_FILE_SEEK)cError
{
    if (! error || (cError == TOX_ERR_FILE_SEEK_OK)) {
        return NO;
    }

    OCTToxErrorFileSeek code;
    NSString *description = @"Cannot perform file seek";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FILE_SEEK_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FILE_SEEK_FRIEND_NOT_FOUND:
            code = OCTToxErrorFileSeekFriendNotFound;
            failureReason = @"Friend not found";
            break;
        case TOX_ERR_FILE_SEEK_FRIEND_NOT_CONNECTED:
            code = OCTToxErrorFileSeekFriendNotConnected;
            failureReason = @"Friend is not connected";
            break;
        case TOX_ERR_FILE_SEEK_NOT_FOUND:
            code = OCTToxErrorFileSeekNotFound;
            failureReason = @"No file transfer with given file number found";
            break;
        case TOX_ERR_FILE_SEEK_DENIED:
            code = OCTToxErrorFileSeekDenied;
            failureReason = @"File was not in a state where it could be seeked";
            break;
        case TOX_ERR_FILE_SEEK_INVALID_POSITION:
            code = OCTToxErrorFileSeekInvalidPosition;
            failureReason = @"Seek position was invalid";
            break;
        case TOX_ERR_FILE_SEEK_SENDQ:
            code = OCTToxErrorFileSeekSendq;
            failureReason = @"Packet queue is full";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFileGet:(TOX_ERR_FILE_GET)cError
{
    if (! error || (cError == TOX_ERR_FILE_GET_OK)) {
        return NO;
    }

    OCTToxErrorFileGet code;
    NSString *description = @"Cannot get file id";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FILE_GET_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FILE_GET_NULL:
            code = OCTToxErrorFileGetInternal;
            failureReason = @"Interval error";
            break;
        case TOX_ERR_FILE_GET_FRIEND_NOT_FOUND:
            code = OCTToxErrorFileGetFriendNotFound;
            failureReason = @"Friend not found";
            break;
        case TOX_ERR_FILE_GET_NOT_FOUND:
            code = OCTToxErrorFileGetNotFound;
            failureReason = @"No file transfer with given file number found";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFileSend:(TOX_ERR_FILE_SEND)cError
{
    if (! error || (cError == TOX_ERR_FILE_SEND_OK)) {
        return NO;
    }

    OCTToxErrorFileSend code;
    NSString *description = @"Cannot send file";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FILE_SEND_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FILE_SEND_NULL:
            code = OCTToxErrorFileSendUnknown;
            failureReason = @"Unknown error occured";
            break;
        case TOX_ERR_FILE_SEND_FRIEND_NOT_FOUND:
            code = OCTToxErrorFileSendFriendNotFound;
            failureReason = @"Friend not found";
            break;
        case TOX_ERR_FILE_SEND_FRIEND_NOT_CONNECTED:
            code = OCTToxErrorFileSendFriendNotConnected;
            failureReason = @"Friend not connected";
            break;
        case TOX_ERR_FILE_SEND_NAME_TOO_LONG:
            code = OCTToxErrorFileSendNameTooLong;
            failureReason = @"File name is too long";
            break;
        case TOX_ERR_FILE_SEND_TOO_MANY:
            code = OCTToxErrorFileSendTooMany;
            failureReason = @"Too many ongoing transfers with friend";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorFileSendChunk:(TOX_ERR_FILE_SEND_CHUNK)cError
{
    if (! error || (cError == TOX_ERR_FILE_SEND_CHUNK_OK)) {
        return NO;
    }

    OCTToxErrorFileSendChunk code;
    NSString *description = @"Cannot send chunk of file";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_FILE_SEND_CHUNK_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_FILE_SEND_CHUNK_NULL:
            code = OCTToxErrorFileSendChunkUnknown;
            failureReason = @"Unknown error occured";
            break;
        case TOX_ERR_FILE_SEND_CHUNK_FRIEND_NOT_FOUND:
            code = OCTToxErrorFileSendChunkFriendNotFound;
            failureReason = @"Friend not found";
            break;
        case TOX_ERR_FILE_SEND_CHUNK_FRIEND_NOT_CONNECTED:
            code = OCTToxErrorFileSendChunkFriendNotConnected;
            failureReason = @"Friend not connected";
            break;
        case TOX_ERR_FILE_SEND_CHUNK_NOT_FOUND:
            code = OCTToxErrorFileSendChunkNotFound;
            failureReason = @"No file transfer with given file number found";
            break;
        case TOX_ERR_FILE_SEND_CHUNK_NOT_TRANSFERRING:
            code = OCTToxErrorFileSendChunkNotTransferring;
            failureReason = @"Wrong file transferring state";
            break;
        case TOX_ERR_FILE_SEND_CHUNK_INVALID_LENGTH:
            code = OCTToxErrorFileSendChunkInvalidLength;
            failureReason = @"Invalid chunk length";
            break;
        case TOX_ERR_FILE_SEND_CHUNK_SENDQ:
            code = OCTToxErrorFileSendChunkSendq;
            failureReason = @"Packet queue is full";
            break;
        case TOX_ERR_FILE_SEND_CHUNK_WRONG_POSITION:
            code = OCTToxErrorFileSendChunkWrongPosition;
            failureReason = @"Wrong position in file";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupNew:(Tox_Err_Group_New)cError
{
    if (! error || (cError == TOX_ERR_GROUP_NEW_OK)) {
        return NO;
    }

    OCTToxErrorGroupNew code = OCTToxErrorGroupNewUnknown;
    NSString *description = @"Cannot create group";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_NEW_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_NEW_TOO_LONG:
            code = OCTToxErrorGroupNewTooLong;
            failureReason = @"Group or peer name is too long";
            break;
        case TOX_ERR_GROUP_NEW_EMPTY:
            code = OCTToxErrorGroupNewEmpty;
            failureReason = @"Group or peer name is empty";
            break;
        case TOX_ERR_GROUP_NEW_INIT:
            code = OCTToxErrorGroupNewInit;
            failureReason = @"Group failed to initialize";
            break;
        case TOX_ERR_GROUP_NEW_STATE:
            code = OCTToxErrorGroupNewState;
            failureReason = @"Group state failed to initialize";
            break;
        case TOX_ERR_GROUP_NEW_ANNOUNCE:
            code = OCTToxErrorGroupNewAnnounce;
            failureReason = @"Group failed to announce to the DHT";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupJoin:(Tox_Err_Group_Join)cError
{
    if (! error || (cError == TOX_ERR_GROUP_JOIN_OK)) {
        return NO;
    }

    OCTToxErrorGroupJoin code = OCTToxErrorGroupJoinUnknown;
    NSString *description = @"Cannot join group";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_JOIN_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_JOIN_INIT:
            code = OCTToxErrorGroupJoinInit;
            failureReason = @"Group failed to initialize";
            break;
        case TOX_ERR_GROUP_JOIN_BAD_CHAT_ID:
            code = OCTToxErrorGroupJoinBadChatId;
            failureReason = @"Invalid chat ID or duplicate group session";
            break;
        case TOX_ERR_GROUP_JOIN_EMPTY:
            code = OCTToxErrorGroupJoinEmpty;
            failureReason = @"Peer name is empty";
            break;
        case TOX_ERR_GROUP_JOIN_TOO_LONG:
            code = OCTToxErrorGroupJoinTooLong;
            failureReason = @"Peer name is too long";
            break;
        case TOX_ERR_GROUP_JOIN_PASSWORD:
            code = OCTToxErrorGroupJoinPassword;
            failureReason = @"Invalid group password";
            break;
        case TOX_ERR_GROUP_JOIN_CORE:
            code = OCTToxErrorGroupJoinCore;
            failureReason = @"Core error while joining group";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupInviteAccept:(Tox_Err_Group_Invite_Accept)cError
{
    if (! error || (cError == TOX_ERR_GROUP_INVITE_ACCEPT_OK)) {
        return NO;
    }

    OCTToxErrorGroupInviteAccept code = OCTToxErrorGroupInviteAcceptUnknown;
    NSString *description = @"Cannot accept group invite";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_INVITE_ACCEPT_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_INVITE_ACCEPT_BAD_INVITE:
            code = OCTToxErrorGroupInviteAcceptBadInvite;
            failureReason = @"Invite data is invalid or expired";
            break;
        case TOX_ERR_GROUP_INVITE_ACCEPT_INIT_FAILED:
            code = OCTToxErrorGroupInviteAcceptInitFailed;
            failureReason = @"Group failed to initialize";
            break;
        case TOX_ERR_GROUP_INVITE_ACCEPT_TOO_LONG:
            code = OCTToxErrorGroupInviteAcceptTooLong;
            failureReason = @"Peer name is too long";
            break;
        case TOX_ERR_GROUP_INVITE_ACCEPT_EMPTY:
            code = OCTToxErrorGroupInviteAcceptEmpty;
            failureReason = @"Peer name is empty";
            break;
        case TOX_ERR_GROUP_INVITE_ACCEPT_PASSWORD:
            code = OCTToxErrorGroupInviteAcceptPassword;
            failureReason = @"Invalid group password";
            break;
        case TOX_ERR_GROUP_INVITE_ACCEPT_CORE:
            code = OCTToxErrorGroupInviteAcceptCore;
            failureReason = @"Core error while accepting invite";
            break;
        case TOX_ERR_GROUP_INVITE_ACCEPT_FAIL_SEND:
            code = OCTToxErrorGroupInviteAcceptFailSend;
            failureReason = @"Invite accept packet failed to send";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupInviteFriend:(Tox_Err_Group_Invite_Friend)cError
{
    if (! error || (cError == TOX_ERR_GROUP_INVITE_FRIEND_OK)) {
        return NO;
    }

    OCTToxErrorGroupInviteFriend code = OCTToxErrorGroupInviteFriendUnknown;
    NSString *description = @"Cannot invite friend to group";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_INVITE_FRIEND_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_INVITE_FRIEND_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupInviteFriendGroupNotFound;
            failureReason = @"Group not found";
            break;
        case TOX_ERR_GROUP_INVITE_FRIEND_FRIEND_NOT_FOUND:
            code = OCTToxErrorGroupInviteFriendFriendNotFound;
            failureReason = @"Friend not found";
            break;
        case TOX_ERR_GROUP_INVITE_FRIEND_INVITE_FAIL:
            code = OCTToxErrorGroupInviteFriendInviteFail;
            failureReason = @"Failed to create invite packet";
            break;
        case TOX_ERR_GROUP_INVITE_FRIEND_FAIL_SEND:
            code = OCTToxErrorGroupInviteFriendFailSend;
            failureReason = @"Invite packet failed to send";
            break;
        case TOX_ERR_GROUP_INVITE_FRIEND_DISCONNECTED:
            code = OCTToxErrorGroupInviteFriendDisconnected;
            failureReason = @"Group is disconnected";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupLeave:(Tox_Err_Group_Leave)cError
{
    if (! error || (cError == TOX_ERR_GROUP_LEAVE_OK)) {
        return NO;
    }

    OCTToxErrorGroupLeave code = OCTToxErrorGroupLeaveUnknown;
    NSString *description = @"Cannot leave group";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_LEAVE_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_LEAVE_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupLeaveGroupNotFound;
            failureReason = @"Group not found";
            break;
        case TOX_ERR_GROUP_LEAVE_TOO_LONG:
            code = OCTToxErrorGroupLeaveTooLong;
            failureReason = @"Parting message is too long";
            break;
        case TOX_ERR_GROUP_LEAVE_FAIL_SEND:
            code = OCTToxErrorGroupLeaveFailSend;
            failureReason = @"Parting packet failed to send";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupSendMessage:(Tox_Err_Group_Send_Message)cError
{
    if (! error || (cError == TOX_ERR_GROUP_SEND_MESSAGE_OK)) {
        return NO;
    }

    OCTToxErrorGroupSendMessage code = OCTToxErrorGroupSendMessageUnknown;
    NSString *description = @"Cannot send group message";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_SEND_MESSAGE_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_SEND_MESSAGE_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupSendMessageGroupNotFound;
            failureReason = @"Group not found";
            break;
        case TOX_ERR_GROUP_SEND_MESSAGE_TOO_LONG:
            code = OCTToxErrorGroupSendMessageTooLong;
            failureReason = @"Message is too long";
            break;
        case TOX_ERR_GROUP_SEND_MESSAGE_EMPTY:
            code = OCTToxErrorGroupSendMessageEmpty;
            failureReason = @"Message is empty";
            break;
        case TOX_ERR_GROUP_SEND_MESSAGE_BAD_TYPE:
            code = OCTToxErrorGroupSendMessageBadType;
            failureReason = @"Invalid message type";
            break;
        case TOX_ERR_GROUP_SEND_MESSAGE_PERMISSIONS:
            code = OCTToxErrorGroupSendMessagePermissions;
            failureReason = @"Insufficient permissions";
            break;
        case TOX_ERR_GROUP_SEND_MESSAGE_FAIL_SEND:
            code = OCTToxErrorGroupSendMessageFailSend;
            failureReason = @"Packet failed to send";
            break;
        case TOX_ERR_GROUP_SEND_MESSAGE_DISCONNECTED:
            code = OCTToxErrorGroupSendMessageDisconnected;
            failureReason = @"Group is disconnected";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupSendCustomPacket:(Tox_Err_Group_Send_Custom_Packet)cError
{
    if (! error || (cError == TOX_ERR_GROUP_SEND_CUSTOM_PACKET_OK)) {
        return NO;
    }

    OCTToxErrorGroupSendCustomPacket code = OCTToxErrorGroupSendCustomPacketUnknown;
    NSString *description = @"Cannot send group custom packet";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_SEND_CUSTOM_PACKET_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_SEND_CUSTOM_PACKET_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupSendCustomPacketGroupNotFound;
            failureReason = @"Group not found";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PACKET_TOO_LONG:
            code = OCTToxErrorGroupSendCustomPacketTooLong;
            failureReason = @"Packet is too long";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PACKET_EMPTY:
            code = OCTToxErrorGroupSendCustomPacketEmpty;
            failureReason = @"Packet is empty";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PACKET_PERMISSIONS:
            code = OCTToxErrorGroupSendCustomPacketPermissions;
            failureReason = @"Insufficient permissions";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PACKET_DISCONNECTED:
            code = OCTToxErrorGroupSendCustomPacketDisconnected;
            failureReason = @"Group is disconnected";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupSendCustomPrivatePacket:(Tox_Err_Group_Send_Custom_Private_Packet)cError
{
    if (! error || (cError == TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_OK)) {
        return NO;
    }

    OCTToxErrorGroupSendCustomPacket code = OCTToxErrorGroupSendCustomPacketUnknown;
    NSString *description = @"Cannot send group custom private packet";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupSendCustomPacketGroupNotFound;
            failureReason = @"Group not found";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_TOO_LONG:
            code = OCTToxErrorGroupSendCustomPacketTooLong;
            failureReason = @"Packet is too long";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_EMPTY:
            code = OCTToxErrorGroupSendCustomPacketEmpty;
            failureReason = @"Packet is empty";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_PEER_NOT_FOUND:
            code = OCTToxErrorGroupSendCustomPacketGroupNotFound;
            failureReason = @"Peer not found";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_PERMISSIONS:
            code = OCTToxErrorGroupSendCustomPacketPermissions;
            failureReason = @"Insufficient permissions";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_FAIL_SEND:
            code = OCTToxErrorGroupSendCustomPacketUnknown;
            failureReason = @"Send failed";
            break;
        case TOX_ERR_GROUP_SEND_CUSTOM_PRIVATE_PACKET_DISCONNECTED:
            code = OCTToxErrorGroupSendCustomPacketDisconnected;
            failureReason = @"Group is disconnected";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupModKickPeer:(Tox_Err_Group_Mod_Kick_Peer)cError
{
    if (! error || (cError == TOX_ERR_GROUP_MOD_KICK_PEER_OK)) {
        return NO;
    }

    OCTToxErrorGroupKickPeer code = OCTToxErrorGroupKickPeerUnknown;
    NSString *description = @"Cannot kick group peer";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_MOD_KICK_PEER_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_MOD_KICK_PEER_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupKickPeerGroupNotFound;
            failureReason = @"Group not found";
            break;
        case TOX_ERR_GROUP_MOD_KICK_PEER_PEER_NOT_FOUND:
            code = OCTToxErrorGroupKickPeerPeerNotFound;
            failureReason = @"Peer not found";
            break;
        case TOX_ERR_GROUP_MOD_KICK_PEER_PERMISSIONS:
            code = OCTToxErrorGroupKickPeerPermissions;
            failureReason = @"Insufficient permissions";
            break;
        case TOX_ERR_GROUP_MOD_KICK_PEER_FAIL_SEND:
            code = OCTToxErrorGroupKickPeerFailSend;
            failureReason = @"Kick failed to send";
            break;
        case TOX_ERR_GROUP_MOD_KICK_PEER_FAIL_ACTION:
            code = OCTToxErrorGroupKickPeerFailSend;
            failureReason = @"Kick action failed";
            break;
        case TOX_ERR_GROUP_MOD_KICK_PEER_SELF:
            code = OCTToxErrorGroupKickPeerPermissions;
            failureReason = @"Cannot kick yourself";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupModSetRole:(Tox_Err_Group_Mod_Set_Role)cError
{
    if (! error || (cError == TOX_ERR_GROUP_MOD_SET_ROLE_OK)) {
        return NO;
    }

    OCTToxErrorGroupSetRole code = OCTToxErrorGroupSetRoleUnknown;
    NSString *description = @"Cannot set group peer role";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_MOD_SET_ROLE_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_MOD_SET_ROLE_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupSetRoleGroupNotFound;
            failureReason = @"Group not found";
            break;
        case TOX_ERR_GROUP_MOD_SET_ROLE_PEER_NOT_FOUND:
            code = OCTToxErrorGroupSetRolePeerNotFound;
            failureReason = @"Peer not found";
            break;
        case TOX_ERR_GROUP_MOD_SET_ROLE_PERMISSIONS:
            code = OCTToxErrorGroupSetRolePermissions;
            failureReason = @"Insufficient permissions";
            break;
        case TOX_ERR_GROUP_MOD_SET_ROLE_ASSIGNMENT:
            code = OCTToxErrorGroupSetRoleAssignment;
            failureReason = @"Invalid role assignment";
            break;
        case TOX_ERR_GROUP_MOD_SET_ROLE_FAIL_ACTION:
            code = OCTToxErrorGroupSetRoleFailAction;
            failureReason = @"Role change failed";
            break;
        case TOX_ERR_GROUP_MOD_SET_ROLE_SELF:
            code = OCTToxErrorGroupSetRoleSelf;
            failureReason = @"Cannot change your own role";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupTopicSet:(Tox_Err_Group_Topic_Set)cError
{
    if (! error || (cError == TOX_ERR_GROUP_TOPIC_SET_OK)) {
        return NO;
    }

    OCTToxErrorGroupTopicSet code = OCTToxErrorGroupTopicSetUnknown;

    switch (cError) {
        case TOX_ERR_GROUP_TOPIC_SET_OK:
            return NO;
        case TOX_ERR_GROUP_TOPIC_SET_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupTopicSetGroupNotFound;
            break;
        case TOX_ERR_GROUP_TOPIC_SET_TOO_LONG:
            code = OCTToxErrorGroupTopicSetTooLong;
            break;
        case TOX_ERR_GROUP_TOPIC_SET_PERMISSIONS:
            code = OCTToxErrorGroupTopicSetPermissions;
            break;
        case TOX_ERR_GROUP_TOPIC_SET_FAIL_CREATE:
            code = OCTToxErrorGroupTopicSetFailCreate;
            break;
        case TOX_ERR_GROUP_TOPIC_SET_FAIL_SEND:
            code = OCTToxErrorGroupTopicSetFailSend;
            break;
        case TOX_ERR_GROUP_TOPIC_SET_DISCONNECTED:
            code = OCTToxErrorGroupTopicSetDisconnected;
            break;
    }

    *error = [OCTTox createErrorWithCode:code
                             description:@"Cannot set group topic"
                           failureReason:nil];
    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupFounderSetPassword:(Tox_Err_Group_Founder_Set_Password)cError
{
    if (! error || (cError == TOX_ERR_GROUP_FOUNDER_SET_PASSWORD_OK)) {
        return NO;
    }

    OCTToxErrorGroupFounderSetPassword code = OCTToxErrorGroupFounderSetPasswordUnknown;

    switch (cError) {
        case TOX_ERR_GROUP_FOUNDER_SET_PASSWORD_OK:
            return NO;
        case TOX_ERR_GROUP_FOUNDER_SET_PASSWORD_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupFounderSetPasswordGroupNotFound;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PASSWORD_PERMISSIONS:
            code = OCTToxErrorGroupFounderSetPasswordPermissions;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PASSWORD_TOO_LONG:
            code = OCTToxErrorGroupFounderSetPasswordTooLong;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PASSWORD_FAIL_SEND:
            code = OCTToxErrorGroupFounderSetPasswordFailSend;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PASSWORD_MALLOC:
            code = OCTToxErrorGroupFounderSetPasswordMalloc;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PASSWORD_DISCONNECTED:
            code = OCTToxErrorGroupFounderSetPasswordDisconnected;
            break;
    }

    *error = [OCTTox createErrorWithCode:code
                             description:@"Cannot set group password"
                           failureReason:nil];
    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupFounderSetTopicLock:(Tox_Err_Group_Founder_Set_Topic_Lock)cError
{
    if (! error || (cError == TOX_ERR_GROUP_FOUNDER_SET_TOPIC_LOCK_OK)) {
        return NO;
    }

    OCTToxErrorGroupFounderSetTopicLock code = OCTToxErrorGroupFounderSetTopicLockUnknown;

    switch (cError) {
        case TOX_ERR_GROUP_FOUNDER_SET_TOPIC_LOCK_OK:
            return NO;
        case TOX_ERR_GROUP_FOUNDER_SET_TOPIC_LOCK_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupFounderSetTopicLockGroupNotFound;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_TOPIC_LOCK_INVALID:
            code = OCTToxErrorGroupFounderSetTopicLockInvalid;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_TOPIC_LOCK_PERMISSIONS:
            code = OCTToxErrorGroupFounderSetTopicLockPermissions;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_TOPIC_LOCK_FAIL_SET:
            code = OCTToxErrorGroupFounderSetTopicLockFailSet;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_TOPIC_LOCK_FAIL_SEND:
            code = OCTToxErrorGroupFounderSetTopicLockFailSend;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_TOPIC_LOCK_DISCONNECTED:
            code = OCTToxErrorGroupFounderSetTopicLockDisconnected;
            break;
    }

    *error = [OCTTox createErrorWithCode:code
                             description:@"Cannot set group topic lock"
                           failureReason:nil];
    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupFounderSetPeerLimit:(Tox_Err_Group_Founder_Set_Peer_Limit)cError
{
    if (! error || (cError == TOX_ERR_GROUP_FOUNDER_SET_PEER_LIMIT_OK)) {
        return NO;
    }

    OCTToxErrorGroupFounderSetPeerLimit code = OCTToxErrorGroupFounderSetPeerLimitUnknown;

    switch (cError) {
        case TOX_ERR_GROUP_FOUNDER_SET_PEER_LIMIT_OK:
            return NO;
        case TOX_ERR_GROUP_FOUNDER_SET_PEER_LIMIT_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupFounderSetPeerLimitGroupNotFound;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PEER_LIMIT_PERMISSIONS:
            code = OCTToxErrorGroupFounderSetPeerLimitPermissions;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PEER_LIMIT_FAIL_SET:
            code = OCTToxErrorGroupFounderSetPeerLimitFailSet;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PEER_LIMIT_FAIL_SEND:
            code = OCTToxErrorGroupFounderSetPeerLimitFailSend;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PEER_LIMIT_DISCONNECTED:
            code = OCTToxErrorGroupFounderSetPeerLimitDisconnected;
            break;
    }

    *error = [OCTTox createErrorWithCode:code
                             description:@"Cannot set group peer limit"
                           failureReason:nil];
    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupFounderSetVoiceState:(Tox_Err_Group_Founder_Set_Voice_State)cError
{
    if (! error || (cError == TOX_ERR_GROUP_FOUNDER_SET_VOICE_STATE_OK)) {
        return NO;
    }

    OCTToxErrorGroupFounderSetVoiceState code = OCTToxErrorGroupFounderSetVoiceStateUnknown;

    switch (cError) {
        case TOX_ERR_GROUP_FOUNDER_SET_VOICE_STATE_OK:
            return NO;
        case TOX_ERR_GROUP_FOUNDER_SET_VOICE_STATE_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupFounderSetVoiceStateGroupNotFound;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_VOICE_STATE_PERMISSIONS:
            code = OCTToxErrorGroupFounderSetVoiceStatePermissions;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_VOICE_STATE_FAIL_SET:
            code = OCTToxErrorGroupFounderSetVoiceStateFailSet;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_VOICE_STATE_FAIL_SEND:
            code = OCTToxErrorGroupFounderSetVoiceStateFailSend;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_VOICE_STATE_DISCONNECTED:
            code = OCTToxErrorGroupFounderSetVoiceStateDisconnected;
            break;
    }

    *error = [OCTTox createErrorWithCode:code
                             description:@"Cannot set group voice state"
                           failureReason:nil];
    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupFounderSetPrivacyState:(Tox_Err_Group_Founder_Set_Privacy_State)cError
{
    if (! error || (cError == TOX_ERR_GROUP_FOUNDER_SET_PRIVACY_STATE_OK)) {
        return NO;
    }

    OCTToxErrorGroupFounderSetPrivacyState code = OCTToxErrorGroupFounderSetPrivacyStateUnknown;

    switch (cError) {
        case TOX_ERR_GROUP_FOUNDER_SET_PRIVACY_STATE_OK:
            return NO;
        case TOX_ERR_GROUP_FOUNDER_SET_PRIVACY_STATE_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupFounderSetPrivacyStateGroupNotFound;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PRIVACY_STATE_PERMISSIONS:
            code = OCTToxErrorGroupFounderSetPrivacyStatePermissions;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PRIVACY_STATE_FAIL_SET:
            code = OCTToxErrorGroupFounderSetPrivacyStateFailSet;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PRIVACY_STATE_FAIL_SEND:
            code = OCTToxErrorGroupFounderSetPrivacyStateFailSend;
            break;
        case TOX_ERR_GROUP_FOUNDER_SET_PRIVACY_STATE_DISCONNECTED:
            code = OCTToxErrorGroupFounderSetPrivacyStateDisconnected;
            break;
    }

    *error = [OCTTox createErrorWithCode:code
                             description:@"Cannot set group privacy state"
                           failureReason:nil];
    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupSendPrivateMessage:(Tox_Err_Group_Send_Private_Message)cError
{
    if (! error || (cError == TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_OK)) {
        return NO;
    }

    OCTToxErrorGroupSendPrivateMessage code = OCTToxErrorGroupSendPrivateMessageUnknown;

    switch (cError) {
        case TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_OK:
            return NO;
        case TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupSendPrivateMessageGroupNotFound;
            break;
        case TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_PEER_NOT_FOUND:
            code = OCTToxErrorGroupSendPrivateMessagePeerNotFound;
            break;
        case TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_TOO_LONG:
            code = OCTToxErrorGroupSendPrivateMessageTooLong;
            break;
        case TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_EMPTY:
            code = OCTToxErrorGroupSendPrivateMessageEmpty;
            break;
        case TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_PERMISSIONS:
            code = OCTToxErrorGroupSendPrivateMessagePeerNotFound;
            break;
        case TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_FAIL_SEND:
            code = OCTToxErrorGroupSendPrivateMessageFailSend;
            break;
        case TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_DISCONNECTED:
            code = OCTToxErrorGroupSendPrivateMessageDisconnected;
            break;
        case TOX_ERR_GROUP_SEND_PRIVATE_MESSAGE_BAD_TYPE:
            code = OCTToxErrorGroupSendPrivateMessageBadType;
            break;
    }

    *error = [OCTTox createErrorWithCode:code
                             description:@"Cannot send group private message"
                           failureReason:nil];
    return YES;
}

- (BOOL)fillError:(NSError **)error withCErrorGroupStateQueries:(Tox_Err_Group_State_Queries)cError
{
    if (! error || (cError == TOX_ERR_GROUP_STATE_QUERIES_OK)) {
        return NO;
    }

    OCTToxErrorGroupStateQueries code = OCTToxErrorGroupStateQueriesUnknown;
    NSString *description = @"Cannot query group state";
    NSString *failureReason = nil;

    switch (cError) {
        case TOX_ERR_GROUP_STATE_QUERIES_OK:
            NSAssert(NO, @"We shouldn't be here");
            return NO;
        case TOX_ERR_GROUP_STATE_QUERIES_GROUP_NOT_FOUND:
            code = OCTToxErrorGroupStateQueriesGroupNotFound;
            failureReason = @"Group not found";
            break;
    }

    *error = [OCTTox createErrorWithCode:code description:description failureReason:failureReason];

    return YES;
}

+ (NSError *)createErrorWithCode:(NSUInteger)code
                     description:(NSString *)description
                   failureReason:(NSString *)failureReason
{
    NSMutableDictionary *userInfo = [NSMutableDictionary new];

    if (description) {
        userInfo[NSLocalizedDescriptionKey] = description;
    }

    if (failureReason) {
        userInfo[NSLocalizedFailureReasonErrorKey] = failureReason;
    }

    return [NSError errorWithDomain:kOCTToxErrorDomain code:code userInfo:userInfo];
}

+ (NSString *)binToHexString:(uint8_t *)bin length:(NSUInteger)length
{
    NSMutableString *string = [NSMutableString stringWithCapacity:length];

    for (NSUInteger idx = 0; idx < length; ++idx) {
        [string appendFormat:@"%02X", bin[idx]];
    }

    return [string copy];
}

// You are responsible for freeing the return value!
+ (uint8_t *)hexStringToBin:(NSString *)string
{
    if (string.length == 0 || (string.length % 2) != 0) {
        return NULL;
    }

    NSCharacterSet *nonHex = [[NSCharacterSet characterSetWithCharactersInString:@"0123456789abcdefABCDEF"] invertedSet];
    if ([string rangeOfCharacterFromSet:nonHex].location != NSNotFound) {
        return NULL;
    }

    const char *hex_string = string.UTF8String;
    if (hex_string == NULL) {
        return NULL;
    }

    size_t i, len = strlen(hex_string) / 2;
    uint8_t *ret = malloc(len);
    if (ret == NULL) {
        return NULL;
    }

    char *pos = (char *)hex_string;

    for (i = 0; i < len; ++i, pos += 2) {
        unsigned int byte = 0;
        if (sscanf(pos, "%2x", &byte) != 1) {
            free(ret);
            return NULL;
        }
        ret[i] = (uint8_t)byte;
    }

    return ret;
}

@end

#pragma mark -  Callbacks

void logCallback(Tox *tox,
                 TOX_LOG_LEVEL level,
                 const char *file,
                 uint32_t line,
                 const char *func,
                 const char *message,
                 void *user_data)
{
    switch (level) {
        case TOX_LOG_LEVEL_TRACE:
            OCTLogCCVerbose(@"TRACE: <toxcore: %s:%u, %s> %s", file, line, func, message);
            break;
        case TOX_LOG_LEVEL_DEBUG:
            OCTLogCCDebug(@"DEBUG: <toxcore: %s:%u, %s> %s", file, line, func, message);
            break;
        case TOX_LOG_LEVEL_INFO:
            OCTLogCCInfo(@"INFO: <toxcore: %s:%u, %s> %s", file, line, func, message);
            break;
        case TOX_LOG_LEVEL_WARNING:
            OCTLogCCWarn(@"WARNING: <toxcore: %s:%u, %s> %s", file, line, func, message);
            break;
        case TOX_LOG_LEVEL_ERROR:
            OCTLogCCError(@"ERROR: <toxcore: %s:%u, %s> %s", file, line, func, message);
            break;
    }
}

void connectionStatusCallback(Tox *cTox, TOX_CONNECTION cStatus, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    OCTToxConnectionStatus status = [tox userConnectionStatusFromCUserStatus:cStatus];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"connectionStatusCallback with status %lu", tox, (unsigned long)status);

        if ([tox.delegate respondsToSelector:@selector(tox:connectionStatus:)]) {
            [tox.delegate tox:tox connectionStatus:status];
        }
    });
}

void friendNameCallback(Tox *cTox, uint32_t friendNumber, const uint8_t *cName, size_t length, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    NSString *name = [NSString stringWithCString:(const char *)cName encoding:NSUTF8StringEncoding];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"nameChangeCallback with name %@, friend number %d", tox, name, friendNumber);

        if ([tox.delegate respondsToSelector:@selector(tox:friendNameUpdate:friendNumber:)]) {
            [tox.delegate tox:tox friendNameUpdate:name friendNumber:friendNumber];
        }
    });
}

void friendStatusMessageCallback(Tox *cTox, uint32_t friendNumber, const uint8_t *cMessage, size_t length, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    NSString *message = [NSString stringWithCString:(const char *)cMessage encoding:NSUTF8StringEncoding];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"statusMessageCallback with status message %@, friend number %d", tox, message, friendNumber);

        if ([tox.delegate respondsToSelector:@selector(tox:friendStatusMessageUpdate:friendNumber:)]) {
            [tox.delegate tox:tox friendStatusMessageUpdate:message friendNumber:friendNumber];
        }
    });
}

void friendStatusCallback(Tox *cTox, uint32_t friendNumber, TOX_USER_STATUS cStatus, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    OCTToxUserStatus status = [tox userStatusFromCUserStatus:cStatus];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"userStatusCallback with status %lu, friend number %d", tox, (unsigned long)status, friendNumber);

        if ([tox.delegate respondsToSelector:@selector(tox:friendStatusUpdate:friendNumber:)]) {
            [tox.delegate tox:tox friendStatusUpdate:status friendNumber:friendNumber];
        }
    });
}

void friendConnectionStatusCallback(Tox *cTox, uint32_t friendNumber, TOX_CONNECTION cStatus, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    OCTToxConnectionStatus status = [tox userConnectionStatusFromCUserStatus:cStatus];

    OCTLogCInfo(@"connectionStatusCallback with status %lu, friendNumber %d", tox, (unsigned long)status, friendNumber);

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:friendConnectionStatusChanged:friendNumber:)]) {
            [tox.delegate tox:tox friendConnectionStatusChanged:status friendNumber:friendNumber];
        }
    });
}

void friendTypingCallback(Tox *cTox, uint32_t friendNumber, bool isTyping, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    OCTLogCInfo(@"typingChangeCallback with isTyping %d, friend number %d", tox, isTyping, friendNumber);

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:friendIsTypingUpdate:friendNumber:)]) {
            [tox.delegate tox:tox friendIsTypingUpdate:(BOOL)isTyping friendNumber:friendNumber];
        }
    });
}

void friendReadReceiptCallback(Tox *cTox, uint32_t friendNumber, uint32_t messageId, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    OCTLogCInfo(@"readReceiptCallback with message id %d, friendNumber %d", tox, messageId, friendNumber);

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:messageDelivered:friendNumber:)]) {
            [tox.delegate tox:tox messageDelivered:messageId friendNumber:friendNumber];
        }
    });
}

void friendRequestCallback(Tox *cTox, const uint8_t *cPublicKey, const uint8_t *cMessage, size_t length, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    NSString *publicKey = [OCTTox binToHexString:(uint8_t *)cPublicKey length:TOX_PUBLIC_KEY_SIZE];
    NSString *message = [[NSString alloc] initWithBytes:cMessage length:length encoding:NSUTF8StringEncoding];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"friendRequestCallback with publicKey %@, message %@", tox, publicKey, message);

        if ([tox.delegate respondsToSelector:@selector(tox:friendRequestWithMessage:publicKey:)]) {
            [tox.delegate tox:tox friendRequestWithMessage:message publicKey:publicKey];
        }
    });
}

void friendMessageCallback(
    Tox *cTox,
    uint32_t friendNumber,
    TOX_MESSAGE_TYPE cType,
    const uint8_t *cMessage,
    size_t length,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    // HINT: invalid UTF-8 will make realm manager crash later, or if length is shorter than bytes in cMessage
    // so check at least for NULL bytes and shorten length accordingly

    size_t textLength = length;

    if ((cMessage) && (length > (TOX_MSGV3_MSGID_LENGTH + TOX_MSGV3_TIMESTAMP_LENGTH + TOX_MSGV3_GUARD)))
    {
        int pos = length - (TOX_MSGV3_MSGID_LENGTH + TOX_MSGV3_TIMESTAMP_LENGTH + TOX_MSGV3_GUARD);
        uint8_t g1 = *(cMessage + pos);
        uint8_t g2 = *(cMessage + pos + 1);

        if ((g1 == 0) && (g2 == 0))
        {
            textLength = (size_t)pos;
        }
    }

    uint8_t *newcMessage = calloc(1, textLength + 1);
    if (!newcMessage)
    {
        // HINT: we cant allocate the new buffer, so we must ignore this incoming message
        return;
    }

    size_t newLength = 0;
    for (size_t i = 0; i < textLength; i++)
    {
        if (*(cMessage + i) != 0)
        {
            newLength++;
        }
        else
        {
            break;
        }
    }

    memcpy(newcMessage, cMessage, (size_t)newLength);

    if (newLength < 1)
    {
        // HINT: message seems to contain nothing before the first NULL byte, so discard it
        free(newcMessage);
        return;
    }

    // KHANDAQ (#161): use the TEXT length here. The old code did newLength++ ("for the NULL
    // byte") before initWithBytes:, so every received message got a trailing U+0000 appended.
    // Replying to such a message embedded that NUL into the reply markup preview, and the
    // peer's receive path (the first-NUL scan above) then truncated the reply right after the
    // preview — the reply body arrived empty ("пустые ответы").
    NSString *message = [[NSString alloc] initWithBytes:newcMessage length:newLength encoding:NSUTF8StringEncoding];
    free(newcMessage);

    if (!message)
    {
        // HINT: message seems to contain invalid UTF-8
        //       instead use a dummy message "__"
        message = @"__";
    }


    // HINT: msgV3 ------------------------------------------------
    // HINT: msgV3 ------------------------------------------------
    // HINT: msgV3 ------------------------------------------------
    int need_free = 0;
    uint32_t msgv3_timstamp_int = 0;
    char *message_v3_hash_hexstr = NULL;

    if ((cMessage) && (length > (TOX_MSGV3_MSGID_LENGTH + TOX_MSGV3_TIMESTAMP_LENGTH + TOX_MSGV3_GUARD)))
    {
        int pos = length - (TOX_MSGV3_MSGID_LENGTH + TOX_MSGV3_TIMESTAMP_LENGTH + TOX_MSGV3_GUARD);

        // bytes at guard position
        uint8_t g1 = *(cMessage + pos);
        uint8_t g2 = *(cMessage + pos + 1);

        // check for the msgv3 guard
        if ((g1 == 0) && (g2 == 0))
        {
            uint8_t *message_v3_hash_bin = calloc(1, TOX_MSGV3_MSGID_LENGTH);
            if (!message_v3_hash_bin)
            {
                OCTLogCInfo(@"friendMessageCallback:friend_message_cb:could not allocate buffer for hash: incoming message discarded", tox);
                return;
            }

            uint8_t *message_v3_timestamp_bin = calloc(1, TOX_MSGV3_TIMESTAMP_LENGTH);
            if (!message_v3_timestamp_bin)
            {
                OCTLogCInfo(@"friendMessageCallback:friend_message_cb:could not allocate buffer for timestamp: incoming message discarded", tox);
                free(message_v3_hash_bin);
                return;
            }

            need_free = 1;
            memcpy(message_v3_hash_bin, (cMessage + pos + TOX_MSGV3_GUARD), TOX_MSGV3_MSGID_LENGTH);
            memcpy(message_v3_timestamp_bin, (cMessage + pos + TOX_MSGV3_GUARD + TOX_MSGV3_MSGID_LENGTH), TOX_MSGV3_TIMESTAMP_LENGTH);

            message_v3_hash_hexstr = calloc(1, (TOX_MSGV3_MSGID_LENGTH * 2) + 1);
            if (message_v3_hash_hexstr)
            {
                bin_to_hex((const char *)message_v3_hash_bin, (size_t)TOX_MSGV3_MSGID_LENGTH, message_v3_hash_hexstr);
                const uint8_t *p = (uint8_t *)(message_v3_timestamp_bin);
                p += xnet_unpack_u32(p, &msgv3_timstamp_int);
                // OCTLogCInfo(@"mmm:friendMessageCallback:friend_message_cb:hash=%s ts=%d", tox, message_v3_hash_hexstr, msgv3_timstamp_int);
            }

            if (need_free == 1)
            {
                free(message_v3_hash_bin);
                free(message_v3_timestamp_bin);
            }
        }
    }
    // HINT: msgV3 ------------------------------------------------
    // HINT: msgV3 ------------------------------------------------
    // HINT: msgV3 ------------------------------------------------

    NSString *msgv3HashHexStr = nil;
    if (message_v3_hash_hexstr)
    {
        msgv3HashHexStr = [[NSString alloc] initWithBytes:message_v3_hash_hexstr length:(TOX_MSGV3_MSGID_LENGTH * 2) encoding:NSUTF8StringEncoding];
        free(message_v3_hash_hexstr);
        OCTLogCInfo(@"friendMessageCallback with friend message %@", tox, msgv3HashHexStr);
    }

    if (cType == TOX_MESSAGE_TYPE_HIGH_LEVEL_ACK)
    {
        // HINT: this message is not a normal message, but a msgV3 high level ACK.
        //       we do not save it in the database, nor show it in the chat window.
        if (msgv3HashHexStr != nil)
        {
            // HINT: set isDelivered status to true for the message with this hex hash.

            dispatch_async(dispatch_get_main_queue(), ^{
                OCTLogCInfo(@"friendMessageCallback received level ACK %@", tox, msgv3HashHexStr);

                if ([tox.delegate respondsToSelector:@selector(tox:friendHighLevelACK:friendNumber:msgv3HashHex:sendTimestamp:)]) {
                    [tox.delegate tox:tox friendHighLevelACK:message friendNumber:friendNumber
                                msgv3HashHex:msgv3HashHexStr sendTimestamp:msgv3_timstamp_int];
                }
            });

        }

        return;
    }
    else
    {
        if (msgv3HashHexStr != nil)
        {
            // HINT: msgV3 message reveived
            // friend must have msgV3 capability, set it in the database
            dispatch_async(dispatch_get_main_queue(), ^{
                if ([tox.delegate respondsToSelector:@selector(tox:friendSetMsgv3Capability:friendNumber:)]) {
                    [tox.delegate tox:tox friendSetMsgv3Capability:YES friendNumber:friendNumber];
                }
                OCTLogCInfo(@"friendMessageCallback msgV3 YES", tox);
            });
        }
        else
        {
            // HINT: old msg version recevied
            // friend does not msgV3 capability, clear it in the database
            dispatch_async(dispatch_get_main_queue(), ^{
                if ([tox.delegate respondsToSelector:@selector(tox:friendSetMsgv3Capability:friendNumber:)]) {
                    [tox.delegate tox:tox friendSetMsgv3Capability:NO friendNumber:friendNumber];
                }
                OCTLogCInfo(@"friendMessageCallback msgV3 --NO--", tox);
            });
        }

        OCTToxMessageType type = [tox messageTypeFromCMessageType:cType];

        dispatch_async(dispatch_get_main_queue(), ^{
            // OCTLogCInfo(@"friendMessageCallback with message %@, friend number %d", tox, message, friendNumber);
            // OCTLogCInfo(@"friendMessageCallback with friend number %d len %d newlen %d", tox, friendNumber, length, newLength);
            // OCTLogCInfo(@"friendMessageCallback with friend message %@", tox, msgv3HashHexStr);

            // HINT: save message to database
            if ([tox.delegate respondsToSelector:@selector(tox:friendMessage:type:friendNumber:msgv3HashHex:sendTimestamp:)]) {
                [tox.delegate tox:tox friendMessage:message type:type friendNumber:friendNumber
                            msgv3HashHex:msgv3HashHexStr sendTimestamp:msgv3_timstamp_int];
            }

            // HINT: now send msgV3 high level ACK
            if ([tox.delegate respondsToSelector:@selector(tox:sendFriendHighlevelACK:friendNumber:msgv3HashHex:sendTimestamp:)]) {
                NSString *message = @"_";
                [tox.delegate tox:tox sendFriendHighlevelACK:message friendNumber:friendNumber
                            msgv3HashHex:msgv3HashHexStr sendTimestamp:msgv3_timstamp_int];
            }

        });
    }
}

void friendLosslessPacketCallback(
    Tox *cTox,
    uint32_t friendNumber,
    const uint8_t *data,
    size_t length,
    void *userData)
{
    if ((length <= 1) || (length >= 300)) {
        return;
    }

    OCTTox *tox = (__bridge OCTTox *)(userData);
    uint8_t pktType = data[0];

    if (pktType == 184) {
        if (length != (2 + TOX_GROUP_CHAT_ID_SIZE) || data[1] != 1) {
            return;
        }

        NSData *chatIdData = [NSData dataWithBytes:(data + 2) length:TOX_GROUP_CHAT_ID_SIZE];

        dispatch_async(dispatch_get_main_queue(), ^{
            if ([tox.delegate respondsToSelector:@selector(tox:friendGroupInviteRequestFromFriendNumber:chatIdData:)]) {
                [tox.delegate tox:tox friendGroupInviteRequestFromFriendNumber:friendNumber chatIdData:chatIdData];
            }
        });

        return;
    }

    if (pktType == 186) {
        // KHANDAQ (#208): 1:1 message EDIT ("KQ" family, byte-parity with Android HelperMessageEdit).
        // Header 40B: [0]=186 [1..2]='K','Q' [3]=ver(1) [4..35]=msgv3 hash 32B [36..39]=edit-ts u32 BE
        // [40..]=new text UTF-8 (variable).
        if (length < 40 || data[1] != 'K' || data[2] != 'Q' || data[3] != 1) {
            return;
        }
        NSMutableString *hashHex = [NSMutableString stringWithCapacity:64];
        for (int i = 4; i < 36; i++) {
            [hashHex appendFormat:@"%02X", data[i]];
        }
        uint32_t editTs = ((uint32_t)data[36] << 24) | ((uint32_t)data[37] << 16)
                        | ((uint32_t)data[38] << 8) | (uint32_t)data[39];
        NSString *newText = @"";
        if (length > 40) {
            newText = [[NSString alloc] initWithBytes:(data + 40) length:(length - 40) encoding:NSUTF8StringEncoding];
            if (newText == nil) {
                return;
            }
        }

        NSString *newTextCopy = newText;
        dispatch_async(dispatch_get_main_queue(), ^{
            if ([tox.delegate respondsToSelector:@selector(tox:friendMessageEditWithMsgv3Hash:newText:editTs:friendNumber:)]) {
                [tox.delegate tox:tox friendMessageEditWithMsgv3Hash:hashHex newText:newTextCopy editTs:editTs friendNumber:friendNumber];
            }
        });

        return;
    }

    if (pktType == 187) {
        // KHANDAQ (#179/#193): delete-for-both of an own 1:1 message ("KQ" family, mirrors Android
        // HelperMessageDelete). TEXT (40B): [0]=187 [1..2]='K','Q' [3]=ver(1) [4..35]=msgv3 hash
        // [36..39]=ts. FILE (41B): [4]=anchor_type(2) [5..36]=tox file_id 32B [37..40]=ts.
        if ((length != 40 && length != 41) || data[1] != 'K' || data[2] != 'Q' || data[3] != 1) {
            return;
        }
        const BOOL isFile = (length == 41 && data[4] == 2);
        const int anchorStart = isFile ? 5 : 4;

        NSMutableString *anchorHex = [NSMutableString stringWithCapacity:64];
        for (int i = anchorStart; i < anchorStart + 32; i++) {
            [anchorHex appendFormat:@"%02X", data[i]];
        }

        dispatch_async(dispatch_get_main_queue(), ^{
            if (isFile) {
                if ([tox.delegate respondsToSelector:@selector(tox:friendMessageDeleteWithFileIdHex:friendNumber:)]) {
                    [tox.delegate tox:tox friendMessageDeleteWithFileIdHex:anchorHex friendNumber:friendNumber];
                }
            }
            else if ([tox.delegate respondsToSelector:@selector(tox:friendMessageDeleteWithMsgv3Hash:friendNumber:)]) {
                [tox.delegate tox:tox friendMessageDeleteWithMsgv3Hash:anchorHex friendNumber:friendNumber];
            }
        });

        return;
    }

    if (pktType == 188) {
        // KHANDAQ (#192): message reaction ("KQ" family, mirrors Android HelperMessageReaction):
        // [0]=188 [1..2]='K','Q' [3]=ver(1) [4]=anchor_type(1=msgv3 hash text, 2=tox file_id) [5..36]=anchor 32B
        // [37..40]=ts u32 BE [41]=action(1=add,0=remove) [42]=emoji len (1..16) [43..]=emoji UTF-8
        uint8_t anchorType = (length >= 5) ? data[4] : 0;
        if (length < 44 || data[1] != 'K' || data[2] != 'Q' || data[3] != 1
            || (anchorType != 1 && anchorType != 2)) {
            return;
        }
        BOOL add = (data[41] == 1);
        NSUInteger emLen = data[42];
        if (emLen < 1 || emLen > 16 || length < 43 + emLen) {
            return;
        }
        NSString *emoji = [[NSString alloc] initWithBytes:(data + 43) length:emLen encoding:NSUTF8StringEncoding];
        if (emoji == nil) {
            return;
        }

        NSMutableString *hashHex = [NSMutableString stringWithCapacity:64];
        for (int i = 5; i < 37; i++) {
            [hashHex appendFormat:@"%02X", data[i]];
        }

        dispatch_async(dispatch_get_main_queue(), ^{
            if (anchorType == 2) {
                if ([tox.delegate respondsToSelector:@selector(tox:friendFileReactionWithFileIdHex:emoji:add:friendNumber:)]) {
                    [tox.delegate tox:tox friendFileReactionWithFileIdHex:hashHex emoji:emoji add:add friendNumber:friendNumber];
                }
            }
            else if ([tox.delegate respondsToSelector:@selector(tox:friendMessageReactionWithMsgv3Hash:emoji:add:friendNumber:)]) {
                [tox.delegate tox:tox friendMessageReactionWithMsgv3Hash:hashHex emoji:emoji add:add friendNumber:friendNumber];
            }
        });

        return;
    }

    if (pktType != 181) {
        return;
    }

    NSData *lossless_bytes = [NSData dataWithBytes:data length:length];

    if (lossless_bytes == nil) {
        return;
    }

    NSString *pushTokenString = [[NSString alloc] initWithBytes:(data + 1)
                                                         length:(length - 1)
                                                       encoding:NSUTF8StringEncoding];

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:friendPushTokenUpdate:friendNumber:)]) {
            [tox.delegate tox:tox friendPushTokenUpdate:pushTokenString friendNumber:friendNumber];
        }
    });
}

void fileReceiveControlCallback(Tox *cTox, uint32_t friendNumber, OCTToxFileNumber fileNumber, TOX_FILE_CONTROL cControl, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    OCTToxFileControl control = [tox fileControlFromCFileControl:cControl];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"fileReceiveControlCallback with friendNumber %d fileNumber %d controlType %lu",
                    tox, friendNumber, fileNumber, (unsigned long)control);

        if ([tox.delegate respondsToSelector:@selector(tox:fileReceiveControl:friendNumber:fileNumber:)]) {
            [tox.delegate tox:tox fileReceiveControl:control friendNumber:friendNumber fileNumber:fileNumber];
        }
    });
}

void fileChunkRequestCallback(Tox *cTox, uint32_t friendNumber, OCTToxFileNumber fileNumber, uint64_t position, size_t length, void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    dispatch_once(&sOCTFileTransferQueueOnceToken, ^{
        sOCTFileTransferQueue = dispatch_queue_create("me.dvor.objcTox.fileTransferQueue", DISPATCH_QUEUE_SERIAL);
    });

    dispatch_async(sOCTFileTransferQueue, ^{
        if ([tox.delegate respondsToSelector:@selector(tox:fileChunkRequestForFileNumber:friendNumber:position:length:)]) {
            [tox.delegate tox:tox fileChunkRequestForFileNumber:fileNumber
                 friendNumber:friendNumber
                     position:position
                       length:length];
        }
    });
}

void fileReceiveCallback(
    Tox *cTox,
    uint32_t friendNumber,
    OCTToxFileNumber fileNumber,
    TOX_FILE_KIND cKind,
    uint64_t fileSize,
    const uint8_t *cFileName,
    size_t fileNameLength,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    OCTToxFileKind kind;

    switch (cKind) {
        case TOX_FILE_KIND_DATA:
            kind = OCTToxFileKindData;
            break;
        case TOX_FILE_KIND_AVATAR:
            kind = OCTToxFileKindAvatar;
            break;
    }

    NSString *fileName = [[NSString alloc] initWithBytes:cFileName length:fileNameLength encoding:NSUTF8StringEncoding];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"fileReceiveCallback with friendNumber %d fileNumber %d kind %ld fileSize %llu fileName %@",
                    tox, friendNumber, fileNumber, (long)kind, fileSize, fileName);

        if ([tox.delegate respondsToSelector:@selector(tox:fileReceiveForFileNumber:friendNumber:kind:fileSize:fileName:)]) {
            [tox.delegate tox:tox fileReceiveForFileNumber:fileNumber
                 friendNumber:friendNumber
                         kind:kind
                     fileSize:fileSize
                     fileName:fileName];
        }
    });
}

void fileReceiveChunkCallback(
    Tox *cTox,
    uint32_t friendNumber,
    OCTToxFileNumber fileNumber,
    uint64_t position,
    const uint8_t *cData,
    size_t length,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    NSData *chunk = nil;

    if (length) {
        chunk = [NSData dataWithBytes:cData length:length];
    }

    dispatch_once(&sOCTFileTransferQueueOnceToken, ^{
        sOCTFileTransferQueue = dispatch_queue_create("me.dvor.objcTox.fileTransferQueue", DISPATCH_QUEUE_SERIAL);
    });

    dispatch_async(sOCTFileTransferQueue, ^{
        if ([tox.delegate respondsToSelector:@selector(tox:fileReceiveChunk:fileNumber:friendNumber:position:)]) {
            [tox.delegate tox:tox fileReceiveChunk:chunk fileNumber:fileNumber friendNumber:friendNumber position:position];
        }
    });
}

static NSString *OCTToxUTF8StringFromBytes(const uint8_t *bytes, size_t length)
{
    if (! bytes || length == 0) {
        return @"";
    }

    NSString *string = [[NSString alloc] initWithBytes:bytes length:length encoding:NSUTF8StringEncoding];
    return string ?: @"__";
}

void groupMessageCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t peerId,
    Tox_Message_Type cType,
    const uint8_t *cMessage,
    size_t length,
    uint32_t messageId,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    NSString *message = OCTToxUTF8StringFromBytes(cMessage, length);
    OCTToxMessageType type = [tox messageTypeFromCMessageType:(TOX_MESSAGE_TYPE)cType];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"groupMessageCallback group=%u peer=%u msgId=%u", tox, groupNumber, peerId, messageId);

        if ([tox.delegate respondsToSelector:@selector(tox:groupMessage:type:groupNumber:peerId:messageId:)]) {
            [tox.delegate tox:tox groupMessage:message type:type groupNumber:groupNumber peerId:peerId messageId:messageId];
        }
    });
}

void groupConnectionStatusCallback(
    Tox *cTox,
    uint32_t groupNumber,
    int32_t status,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"groupConnectionStatusCallback group=%u status=%d", tox, groupNumber, status);

        if ([tox.delegate respondsToSelector:@selector(tox:groupConnectionStatusChanged:groupNumber:)]) {
            [tox.delegate tox:tox groupConnectionStatusChanged:status groupNumber:groupNumber];
        }
    });
}

void groupPeerJoinCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t peerId,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"groupPeerJoinCallback group=%u peer=%u", tox, groupNumber, peerId);

        if ([tox.delegate respondsToSelector:@selector(tox:groupPeerJoinWithGroupNumber:peerId:)]) {
            [tox.delegate tox:tox groupPeerJoinWithGroupNumber:groupNumber peerId:peerId];
        }
    });
}

void groupPeerNameCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t peerId,
    const uint8_t *cName,
    size_t length,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    NSString *name = OCTToxUTF8StringFromBytes(cName, length);

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"groupPeerNameCallback group=%u peer=%u name=%@", tox, groupNumber, peerId, name);

        if ([tox.delegate respondsToSelector:@selector(tox:groupPeerNameUpdate:groupNumber:peerId:)]) {
            [tox.delegate tox:tox groupPeerNameUpdate:name groupNumber:groupNumber peerId:peerId];
        }
    });
}

void groupInviteCallback(
    Tox *cTox,
    uint32_t friendNumber,
    const uint8_t *inviteData,
    size_t inviteDataLength,
    const uint8_t *groupName,
    size_t groupNameLength,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    NSData *data = [NSData dataWithBytes:inviteData length:inviteDataLength];
    NSString *name = OCTToxUTF8StringFromBytes(groupName, groupNameLength);

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"groupInviteCallback friend=%u groupName=%@", tox, friendNumber, name);

        if ([tox.delegate respondsToSelector:@selector(tox:groupInviteFromFriendNumber:inviteData:groupName:)]) {
            [tox.delegate tox:tox groupInviteFromFriendNumber:friendNumber inviteData:data groupName:name];
        }
    });
}

void groupPeerExitCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t peerId,
    Tox_Group_Exit_Type exitType,
    const uint8_t *cName,
    size_t nameLength,
    const uint8_t *cPartMessage,
    size_t partMessageLength,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    NSString *name = OCTToxUTF8StringFromBytes(cName, nameLength);
    NSString *partMessage = partMessageLength > 0 ? OCTToxUTF8StringFromBytes(cPartMessage, partMessageLength) : nil;
    OCTToxGroupExitType type = [tox groupExitTypeFromCExitType:exitType];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"groupPeerExitCallback group=%u peer=%u exitType=%ld", tox, groupNumber, peerId, (long)type);

        if ([tox.delegate respondsToSelector:@selector(tox:groupPeerExitWithGroupNumber:peerId:exitType:name:partMessage:)]) {
            [tox.delegate tox:tox groupPeerExitWithGroupNumber:groupNumber peerId:peerId exitType:type name:name partMessage:partMessage];
        }
    });
}

void groupCustomPacketCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t peerId,
    const uint8_t *data,
    size_t length,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    NSData *packet = [NSData dataWithBytes:data length:length];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"groupCustomPacketCallback group=%u peer=%u bytes=%zu", tox, groupNumber, peerId, length);

        if ([tox.delegate respondsToSelector:@selector(tox:groupCustomPacketWithGroupNumber:peerId:data:)]) {
            [tox.delegate tox:tox groupCustomPacketWithGroupNumber:groupNumber peerId:peerId data:packet];
        }
    });
}

void groupCustomPrivatePacketCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t peerId,
    const uint8_t *data,
    size_t length,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    NSData *packet = [NSData dataWithBytes:data length:length];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"groupCustomPrivatePacketCallback group=%u peer=%u bytes=%zu", tox, groupNumber, peerId, length);

        if ([tox.delegate respondsToSelector:@selector(tox:groupCustomPrivatePacketWithGroupNumber:peerId:data:)]) {
            [tox.delegate tox:tox groupCustomPrivatePacketWithGroupNumber:groupNumber peerId:peerId data:packet];
        }
    });
}

void groupModerationCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t sourcePeerId,
    uint32_t targetPeerId,
    Tox_Group_Mod_Event modEvent,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    OCTToxGroupModEvent event = [tox groupModEventFromCEvent:modEvent];

    dispatch_async(dispatch_get_main_queue(), ^{
        OCTLogCInfo(@"groupModerationCallback group=%u source=%u target=%u event=%ld",
                    tox, groupNumber, sourcePeerId, targetPeerId, (long)event);

        if ([tox.delegate respondsToSelector:@selector(tox:groupModerationWithGroupNumber:sourcePeerId:targetPeerId:event:)]) {
            [tox.delegate tox:tox groupModerationWithGroupNumber:groupNumber
                   sourcePeerId:sourcePeerId
                   targetPeerId:targetPeerId
                          event:event];
        }
    });
}

void groupTopicCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t peerId,
    const uint8_t *topic,
    size_t length,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    NSString *topicString = OCTToxUTF8StringFromBytes(topic, length);

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:groupTopicUpdate:groupNumber:peerId:)]) {
            [tox.delegate tox:tox groupTopicUpdate:topicString groupNumber:groupNumber peerId:peerId];
        }
    });
}

void groupPasswordCallback(
    Tox *cTox,
    uint32_t groupNumber,
    const uint8_t *password,
    size_t length,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    NSString *passwordString = length > 0 ? OCTToxUTF8StringFromBytes(password, length) : nil;

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:groupPasswordUpdate:groupNumber:)]) {
            [tox.delegate tox:tox groupPasswordUpdate:passwordString groupNumber:groupNumber];
        }
    });
}

void groupTopicLockCallback(
    Tox *cTox,
    uint32_t groupNumber,
    Tox_Group_Topic_Lock topicLock,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    OCTToxGroupTopicLock lock = [tox groupTopicLockFromCLock:topicLock];

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:groupTopicLockUpdate:groupNumber:)]) {
            [tox.delegate tox:tox groupTopicLockUpdate:lock groupNumber:groupNumber];
        }
    });
}

void groupPeerLimitCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t peerLimit,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:groupPeerLimitUpdate:groupNumber:)]) {
            [tox.delegate tox:tox groupPeerLimitUpdate:(uint16_t)peerLimit groupNumber:groupNumber];
        }
    });
}

void groupPrivacyStateCallback(
    Tox *cTox,
    uint32_t groupNumber,
    Tox_Group_Privacy_State privacyState,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    OCTToxGroupPrivacyState state = [tox groupPrivacyStateFromCPrivacyState:privacyState];

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:groupPrivacyStateUpdate:groupNumber:)]) {
            [tox.delegate tox:tox groupPrivacyStateUpdate:state groupNumber:groupNumber];
        }
    });
}

void groupVoiceStateCallback(
    Tox *cTox,
    uint32_t groupNumber,
    Tox_Group_Voice_State voiceState,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    OCTToxGroupVoiceState state = [tox groupVoiceStateFromCVoiceState:voiceState];

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:groupVoiceStateUpdate:groupNumber:)]) {
            [tox.delegate tox:tox groupVoiceStateUpdate:state groupNumber:groupNumber];
        }
    });
}

void groupJoinFailCallback(
    Tox *cTox,
    uint32_t groupNumber,
    Tox_Group_Join_Fail failType,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    OCTToxGroupJoinFail fail = [tox groupJoinFailFromCFail:failType];

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:groupJoinFail:groupNumber:)]) {
            [tox.delegate tox:tox groupJoinFail:fail groupNumber:groupNumber];
        }
    });
}

void groupPrivateMessageCallback(
    Tox *cTox,
    uint32_t groupNumber,
    uint32_t peerId,
    Tox_Message_Type type,
    const uint8_t *message,
    size_t length,
    void *userData)
{
    OCTTox *tox = (__bridge OCTTox *)(userData);
    NSString *text = OCTToxUTF8StringFromBytes(message, length);
    OCTToxMessageType messageType = [tox messageTypeFromCMessageType:(TOX_MESSAGE_TYPE)type];

    dispatch_async(dispatch_get_main_queue(), ^{
        if ([tox.delegate respondsToSelector:@selector(tox:groupPrivateMessage:type:groupNumber:peerId:)]) {
            [tox.delegate tox:tox groupPrivateMessage:text type:messageType groupNumber:groupNumber peerId:peerId];
        }
    });
}
