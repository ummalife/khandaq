package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.GroupMessage;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * KHANDAQ (tester 21.11, 8256/8261): the insert every receive path ends in refuses a copy of a message deleted here.
 * A unit test has no database (TrifaToxService.orma is null), so a row that is not refused goes on to orma and
 * throws: only the refusal keeps the copy out. GroupMessageTombstonesTest has the rules and the wiring of each path.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class GroupMessageTombstonesInsertTest
{
    private static final String GROUP = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String AMINA = "56A1ADE4B65B86BCD51CC73E2CD4E542179F47959FE3E0E21B4B0ACDADE51855";
    private static final String YUSUF = "7F3A91C05D2E8B4416A9C3E07D5F2B8164E0A3C9D7B25F1E8A4C6D0B3F9E2A71";
    private static final String LOCATION = "khandaq-location:41.00824,28.97836";
    private static final String TRANSFER = "9C1F0E2D3B4A59687766554433221100FFEEDDCCBBAA99887766554433221100";

    private GroupMessageTombstones.Store appStore;

    @Before
    public void useAStoreOfItsOwn()
    {
        appStore = HelperMessageDelete.GROUP_TOMBSTONES;
        final Map<String, String> rows = new HashMap<>();
        HelperMessageDelete.GROUP_TOMBSTONES = new GroupMessageTombstones.Store(new GroupMessageTombstones.Rows()
        {
            @Override
            public boolean ready()
            {
                return true;
            }

            @Override
            public String get(final String key)
            {
                return rows.get(key);
            }

            @Override
            public void put(final String key, final String value)
            {
                rows.put(key, value);
            }

            @Override
            public void remove(final String key)
            {
                rows.remove(key);
            }
        });
    }

    @After
    public void putTheAppsStoreBack()
    {
        HelperMessageDelete.GROUP_TOMBSTONES = appStore;
    }

    private static GroupMessage received(final String author, final String messageId, final String text)
    {
        final GroupMessage m = new GroupMessage();
        m.direction = 0;
        m.group_identifier = GROUP;
        m.tox_group_peer_pubkey = author;
        m.message_id_tox = messageId;
        m.text = text;
        m.private_message = 0;
        m.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT.value;
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NONE.value;
        m.sent_timestamp = System.currentTimeMillis();
        return m;
    }

    private static GroupMessage fromHistory(final String author, final String messageId, final String text)
    {
        final GroupMessage m = received(author, messageId, text);
        m.was_synced = true;
        m.TRIFA_SYNC_TYPE = TRIFAGlobals.TRIFA_SYNC_TYPE.TRIFA_SYNC_TYPE_NGC_PEERS.value;
        return m;
    }

    @Test
    public void a_copy_of_a_location_deleted_here_is_not_stored_again()
    {
        assertNull(TrifaToxService.orma);
        final GroupMessage location = received(AMINA, "a1b2c3d4", LOCATION);
        HelperMessageDelete.rememberDeletedGroupMessage(location);

        // What the next join's history sync brings, from Amina's key and from a relay that names Yusuf's.
        assertEquals(-1L, HelperGroup.insert_into_group_message_db(fromHistory(AMINA, "a1b2c3d4", LOCATION), false));
        assertEquals(-1L, HelperGroup.insert_into_group_message_db(fromHistory(YUSUF, "a1b2c3d4", LOCATION), false));
        // The same place shared again is a new message: it goes on to the database (none here).
        assertThrows(NullPointerException.class,
                     () -> HelperGroup.insert_into_group_message_db(received(AMINA, "0e0f1011", LOCATION), false));
    }

    @Test
    public void a_copy_of_a_file_deleted_here_is_not_stored_again()
    {
        final GroupMessage photo = received(AMINA, "", "photo.jpg");
        photo.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value;
        photo.msg_id_hash = TRANSFER;
        photo.file_name = "photo.jpg";
        HelperMessageDelete.rememberDeletedGroupMessage(photo);

        final GroupMessage again = received(YUSUF, "", "photo.jpg");
        again.TRIFA_MESSAGE_TYPE = TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE.value;
        again.msg_id_hash = TRANSFER;
        again.file_name = "photo.jpg";
        assertEquals(-1L, HelperGroup.insert_into_group_message_db(again, false));
    }

    @Test
    public void our_own_rows_are_never_refused()
    {
        final GroupMessage location = received(AMINA, "a1b2c3d4", LOCATION);
        HelperMessageDelete.rememberDeletedGroupMessage(location);
        final GroupMessage own = fromHistory(AMINA, "a1b2c3d4", LOCATION);
        own.direction = 1;
        assertThrows(NullPointerException.class, () -> HelperGroup.insert_into_group_message_db(own, false));
    }
}
