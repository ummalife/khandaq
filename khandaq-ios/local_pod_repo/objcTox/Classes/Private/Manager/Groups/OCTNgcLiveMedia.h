// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

#include <stddef.h>

/**
 * KHANDAQ (gc IPv6 MTU): the longest live media packet of a group (NGC audio and video) that goes lossy; a longer one
 * goes lossless. It is toxcore's MAX_GC_PACKET_CHUNK_SIZE, the largest group packet toxcore sends in one piece: a
 * lossy packet cannot be split into chunks, and toxcore refuses a longer one
 * (TOX_ERR_GROUP_SEND_CUSTOM_PACKET_TOO_LONG), so the frame would be lost. Since 0.2.57 toxcore sends chunks of
 * 1150 bytes, not 1372, so that a group packet fits the IPv6 minimum MTU: 1150 + 82 bytes of wrapping are 1232 bytes
 * of UDP payload, 1280 as an IPv6 packet. Android's ToxVars.MAX_GC_LOSSY_PACKET_SIZE is the same, and
 * scripts/check-gc-ipv6-mtu.py keeps both equal to toxcore's.
 */
static const size_t kOCTNgcMaxLossyPacketLength = 1150;
