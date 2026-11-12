package com.zoffcc.applications.trifa;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * KHANDAQ (redesign stage 4, E20): what a plain Tox profile file says about itself before it is moved
 * in, so the person sees the name and the ID first. It reads the sections toxcore writes (state.c,
 * Messenger.c): the file starts with a zero word and the global cookie; each section is its length, its
 * type and the section cookie, little-endian. The NoSpam and keys section gives the ID, the name
 * section the name. Nothing is changed: the file is copied into place as it is.
 */
final class ToxSaveSummary
{
    static final int ID_HEX_LENGTH = 76;

    private static final int COOKIE_GLOBAL = 0x15ed1b1f;
    private static final int COOKIE_SECTION = 0x01ce;
    private static final int TYPE_NOSPAM_KEYS = 1;
    private static final int TYPE_NAME = 4;
    private static final int TYPE_END = 255;
    private static final int PUBLIC_KEY_SIZE = 32;
    private static final int NOSPAM_SIZE = 4;
    private static final int NOSPAM_KEYS_SIZE = NOSPAM_SIZE + 2 * PUBLIC_KEY_SIZE;
    private static final int HEADER_SIZE = 8;
    /** A name longer than toxcore takes is not a name toxcore wrote. */
    private static final int MAX_NAME_BYTES = 128;

    /** The name the profile carries; empty when it has none. */
    final String name;
    /** The 76 hex digits of the ID, upper case: the public key, the antispam and the check. */
    final String toxId;

    private ToxSaveSummary(final String name, final String toxId)
    {
        this.name = name;
        this.toxId = toxId;
    }

    /** The name and the ID of a plain profile file, or null when the bytes are no such file. */
    @Nullable
    static ToxSaveSummary read(@Nullable final byte[] data)
    {
        if (data == null || data.length < HEADER_SIZE || readInt(data, 0) != 0 || readInt(data, 4) != COOKIE_GLOBAL)
        {
            return null;
        }
        String name = "";
        String toxId = null;
        int at = HEADER_SIZE;
        while (data.length - at >= 8)
        {
            final long length = readInt(data, at) & 0xffffffffL;
            final int type = readShort(data, at + 4);
            final int cookie = readShort(data, at + 6);
            at += 8;
            if (cookie != COOKIE_SECTION || length > data.length - at)
            {
                // A garbled or cut section: the file is not one toxcore would load.
                return null;
            }
            final int size = (int) length;
            if (type == TYPE_NOSPAM_KEYS)
            {
                if (size != NOSPAM_KEYS_SIZE)
                {
                    return null;
                }
                toxId = address(data, at);
            }
            else if (type == TYPE_NAME && size <= MAX_NAME_BYTES)
            {
                name = new String(data, at, size, StandardCharsets.UTF_8).trim();
            }
            else if (type == TYPE_END)
            {
                break;
            }
            at += size;
        }
        return toxId == null ? null : new ToxSaveSummary(name, toxId);
    }

    /**
     * The ID as toxcore's getaddress builds it: the public key, the antispam in the byte order it is
     * stored, then the check, two bytes of an XOR over the first 36.
     */
    private static String address(final byte[] data, final int section)
    {
        final byte[] address = new byte[PUBLIC_KEY_SIZE + NOSPAM_SIZE + 2];
        System.arraycopy(data, section + NOSPAM_SIZE, address, 0, PUBLIC_KEY_SIZE);
        System.arraycopy(data, section, address, PUBLIC_KEY_SIZE, NOSPAM_SIZE);
        for (int i = 0; i < PUBLIC_KEY_SIZE + NOSPAM_SIZE; i++)
        {
            address[PUBLIC_KEY_SIZE + NOSPAM_SIZE + (i % 2)] ^= address[i];
        }
        final StringBuilder hex = new StringBuilder(ID_HEX_LENGTH);
        for (final byte b : address)
        {
            hex.append(String.format(Locale.ROOT, "%02X", b & 0xff));
        }
        return hex.toString();
    }

    private static int readInt(final byte[] data, final int at)
    {
        return (data[at] & 0xff) | ((data[at + 1] & 0xff) << 8) | ((data[at + 2] & 0xff) << 16)
               | ((data[at + 3] & 0xff) << 24);
    }

    private static int readShort(final byte[] data, final int at)
    {
        return (data[at] & 0xff) | ((data[at + 1] & 0xff) << 8);
    }
}
