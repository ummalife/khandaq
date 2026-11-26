package com.zoffcc.applications.trifa;

import java.util.Locale;

import androidx.annotation.Nullable;

/**
 * KHANDAQ (26.11.2026): the wire format of a member of a group becoming a contact
 * (DESIGN-group-contacts-private-mentions.md). A member's key in a group is theirs in that group only
 * (toxcore makes a key pair per group), so it never adds a contact; the two members trade their Tox IDs
 * over the group's private channel instead, with the other's consent:
 * <pre>
 *   offer   66 77 88 11 34 35 | 01 | 61 | the sender's address (38) | nonce (8)
 *   accept  66 77 88 11 34 35 | 01 | 62 | the sender's address (38) | the offer's nonce (8)
 * </pre>
 * The one who accepts also sends a Tox contact request whose message starts with the marker
 * {@code [KQ|c|<nonce>]}, so the offer is answered even when the accept packet cannot travel.
 * Plain values only, so the JVM tests check every byte.
 */
final class GroupContactPackets
{
    static final byte VERSION = 0x01;
    static final byte PKT_OFFER = 0x61;
    static final byte PKT_ACCEPT = 0x62;

    static final int ADDRESS_BYTES = 38;
    static final int PUBLIC_KEY_BYTES = 32;
    static final int NONCE_BYTES = 8;
    private static final byte[] MAGIC = {0x66, 0x77, (byte) 0x88, 0x11, 0x34, 0x35};
    private static final int HEADER_BYTES = MAGIC.length + 2;
    static final int LENGTH = HEADER_BYTES + ADDRESS_BYTES + NONCE_BYTES;

    static final String MARKER_PREFIX = "[KQ|c|";
    static final String MARKER_SUFFIX = "]";
    private static final int NONCE_HEX = NONCE_BYTES * 2;

    private GroupContactPackets()
    {
    }

    /** An offer or an accept as it arrived, checked. */
    static final class Packet
    {
        final byte kind;
        final byte[] address;
        final byte[] nonce;

        private Packet(final byte kind, final byte[] address, final byte[] nonce)
        {
            this.kind = kind;
            this.address = address;
            this.nonce = nonce;
        }

        boolean isOffer()
        {
            return kind == PKT_OFFER;
        }

        /** The sender's whole Tox ID, 76 hex characters, upper case. */
        String addressHex()
        {
            return toHex(address, 0, ADDRESS_BYTES);
        }

        /** The sender's public key, the first 64 hex characters of the address. */
        String publicKeyHex()
        {
            return toHex(address, 0, PUBLIC_KEY_BYTES);
        }

        /** The offer's nonce, 16 hex characters, lower case: the form the request marker carries. */
        String nonceHex()
        {
            return toHex(nonce, 0, NONCE_BYTES).toLowerCase(Locale.ROOT);
        }
    }

    /** The packet of {@code kind} (offer or accept) for our address and the nonce; null for bad input. */
    @Nullable
    static byte[] build(final byte kind, @Nullable final byte[] address, @Nullable final byte[] nonce)
    {
        if ((kind != PKT_OFFER && kind != PKT_ACCEPT) || address == null || address.length != ADDRESS_BYTES
            || nonce == null || nonce.length != NONCE_BYTES || !checksumMatches(address))
        {
            return null;
        }
        final byte[] packet = new byte[LENGTH];
        System.arraycopy(MAGIC, 0, packet, 0, MAGIC.length);
        packet[MAGIC.length] = VERSION;
        packet[MAGIC.length + 1] = kind;
        System.arraycopy(address, 0, packet, HEADER_BYTES, ADDRESS_BYTES);
        System.arraycopy(nonce, 0, packet, HEADER_BYTES + ADDRESS_BYTES, NONCE_BYTES);
        return packet;
    }

    /**
     * The offer or accept in {@code data}, or null: any other length, magic, version or kind, and an
     * address whose checksum does not hold, are dropped.
     */
    @Nullable
    static Packet parse(@Nullable final byte[] data, final long length)
    {
        if (data == null || length != LENGTH || data.length < LENGTH)
        {
            return null;
        }
        for (int i = 0; i < MAGIC.length; i++)
        {
            if (data[i] != MAGIC[i])
            {
                return null;
            }
        }
        final byte kind = data[MAGIC.length + 1];
        if (data[MAGIC.length] != VERSION || (kind != PKT_OFFER && kind != PKT_ACCEPT))
        {
            return null;
        }
        final byte[] address = new byte[ADDRESS_BYTES];
        System.arraycopy(data, HEADER_BYTES, address, 0, ADDRESS_BYTES);
        if (!checksumMatches(address))
        {
            return null;
        }
        final byte[] nonce = new byte[NONCE_BYTES];
        System.arraycopy(data, HEADER_BYTES + ADDRESS_BYTES, nonce, 0, NONCE_BYTES);
        return new Packet(kind, address, nonce);
    }

    /** Whether this is one of our two packets, before parsing it: the dispatcher's cheap test. */
    static boolean isContactPacket(@Nullable final byte[] data, final long length)
    {
        if (data == null || length < HEADER_BYTES || data.length < HEADER_BYTES)
        {
            return false;
        }
        for (int i = 0; i < MAGIC.length; i++)
        {
            if (data[i] != MAGIC[i])
            {
                return false;
            }
        }
        return data[MAGIC.length] == VERSION
               && (data[MAGIC.length + 1] == PKT_OFFER || data[MAGIC.length + 1] == PKT_ACCEPT);
    }

    /** toxcore's address checksum: the XOR of the key and the nospam, two bytes at a time. */
    static boolean checksumMatches(@Nullable final byte[] address)
    {
        if (address == null || address.length != ADDRESS_BYTES)
        {
            return false;
        }
        byte first = 0;
        byte second = 0;
        for (int i = 0; i < ADDRESS_BYTES - 2; i += 2)
        {
            first ^= address[i];
            second ^= address[i + 1];
        }
        return first == address[ADDRESS_BYTES - 2] && second == address[ADDRESS_BYTES - 1];
    }

    /** The marker that starts the contact request of an accepted offer: {@code [KQ|c|<nonce>]}. */
    static String marker(final String nonceHex)
    {
        return MARKER_PREFIX + nonceHex.toLowerCase(Locale.ROOT) + MARKER_SUFFIX;
    }

    /** The whole message of that contact request: the marker, a line break, the words for the person. */
    static String requestMessage(final String nonceHex, @Nullable final String words)
    {
        final String said = words == null ? "" : words.trim();
        return said.isEmpty() ? marker(nonceHex) : marker(nonceHex) + "\n" + said;
    }

    /** The nonce a contact request's message starts with, 16 hex lower case, or null for any other message. */
    @Nullable
    static String nonceOfRequest(@Nullable final String message)
    {
        if (message == null || !message.startsWith(MARKER_PREFIX))
        {
            return null;
        }
        final int start = MARKER_PREFIX.length();
        final int end = start + NONCE_HEX;
        if (message.length() < end + MARKER_SUFFIX.length() || !message.startsWith(MARKER_SUFFIX, end))
        {
            return null;
        }
        final String nonce = message.substring(start, end);
        for (int i = 0; i < nonce.length(); i++)
        {
            if (Character.digit(nonce.charAt(i), 16) < 0)
            {
                return null;
            }
        }
        return nonce.toLowerCase(Locale.ROOT);
    }

    /** A contact request's words without the marker, for a request whose offer is not ours. */
    static String withoutMarker(@Nullable final String message)
    {
        if (message == null)
        {
            return "";
        }
        if (nonceOfRequest(message) == null)
        {
            return message;
        }
        String rest = message.substring(MARKER_PREFIX.length() + NONCE_HEX + MARKER_SUFFIX.length());
        if (rest.startsWith("\r\n"))
        {
            rest = rest.substring(2);
        }
        else if (rest.startsWith("\n"))
        {
            rest = rest.substring(1);
        }
        return rest;
    }

    /** The bytes of a hex string of exactly {@code bytes} bytes, or null. */
    @Nullable
    static byte[] fromHex(@Nullable final String hex, final int bytes)
    {
        if (hex == null || hex.length() != bytes * 2)
        {
            return null;
        }
        final byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++)
        {
            final int high = Character.digit(hex.charAt(2 * i), 16);
            final int low = Character.digit(hex.charAt(2 * i + 1), 16);
            if (high < 0 || low < 0)
            {
                return null;
            }
            out[i] = (byte) ((high << 4) | low);
        }
        return out;
    }

    static String toHex(final byte[] bytes, final int offset, final int count)
    {
        final char[] digits = "0123456789ABCDEF".toCharArray();
        final StringBuilder sb = new StringBuilder(count * 2);
        for (int i = offset; i < offset + count; i++)
        {
            sb.append(digits[(bytes[i] >> 4) & 0x0f]).append(digits[bytes[i] & 0x0f]);
        }
        return sb.toString();
    }
}
