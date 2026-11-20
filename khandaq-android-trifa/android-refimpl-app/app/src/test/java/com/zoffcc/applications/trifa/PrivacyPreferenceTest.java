package com.zoffcc.applications.trifa;

import com.zoffcc.applications.sorm.Message;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (testers' ideas, 0.2.67): Settings > Privacy has Send read receipts and Show that I'm typing, both on
 * by default. Off, the seen packet (189) never goes, also not one that waited for the person; off, toxcore is
 * never told that we type. The receipts of others still show. The parts that need a screen or toxcore are
 * pinned in the source, as HelperMessageSeenTest does.
 */
public class PrivacyPreferenceTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";
    private static final String FRIEND = "A1B2C3D4E5F60718293A4B5C6D7E8F90A1B2C3D4E5F60718293A4B5C6D7E8F90";
    private static final String ID_A = "00112233445566778899AABBCCDDEEFF0123456789ABCDEFFEDCBA9876543210";
    private static final String ID_B = "B0B1B2B3B4B5B6B7B8B9BABBBCBDBEBFC0C1C2C3C4C5C6C7C8C9CACBCCCDCECF";
    private static final long NOW_SEC = 1700000000L;

    private boolean seenBefore;
    private boolean typingBefore;

    @Before
    public void setUp()
    {
        seenBefore = PrivacyPreference.sendsSeenReceipts;
        typingBefore = PrivacyPreference.sendsTyping;
    }

    @After
    public void tearDown()
    {
        PrivacyPreference.sendsSeenReceipts = seenBefore;
        PrivacyPreference.sendsTyping = typingBefore;
    }

    @Test
    public void both_switches_are_on_for_a_new_install_and_keep_what_the_person_chose()
    {
        final MemoryPreferences settings = new MemoryPreferences();
        PrivacyPreference.sendsSeenReceipts = false;
        PrivacyPreference.sendsTyping = false;
        PrivacyPreference.load(settings);
        assertTrue(PrivacyPreference.sendsSeenReceipts);
        assertTrue(PrivacyPreference.sendsTyping);

        PrivacyPreference.setSeenReceipts(settings, false);
        assertFalse(PrivacyPreference.sendsSeenReceipts);
        assertEquals(Boolean.FALSE, settings.values.get(PrivacyPreference.KEY_SEEN_RECEIPTS));
        PrivacyPreference.setTyping(settings, false);
        assertFalse(PrivacyPreference.sendsTyping);
        assertEquals(Boolean.FALSE, settings.values.get(PrivacyPreference.KEY_TYPING));

        // The next start reads them back.
        PrivacyPreference.sendsSeenReceipts = true;
        PrivacyPreference.sendsTyping = true;
        PrivacyPreference.load(settings);
        assertFalse(PrivacyPreference.sendsSeenReceipts);
        assertFalse(PrivacyPreference.sendsTyping);

        // A value of another type is read as the default instead of taking the start down.
        settings.values.put(PrivacyPreference.KEY_SEEN_RECEIPTS, "no");
        PrivacyPreference.load(settings);
        assertTrue(PrivacyPreference.sendsSeenReceipts);
    }

    @Test
    public void the_keys_are_new_and_not_the_older_delivery_receipt_switch()
    {
        assertEquals("pref_send_seen_receipts", PrivacyPreference.KEY_SEEN_RECEIPTS);
        assertEquals("pref_send_typing", PrivacyPreference.KEY_TYPING);
        // pref_send_read_receipts gates the msgV2 delivery receipt, which the sender's resends wait for.
        assertNotEquals("pref_send_read_receipts", PrivacyPreference.KEY_SEEN_RECEIPTS);
        assertTrue(PrivacyPreference.DEFAULT);
        assertTrue(PrivacyPreference.concerns(PrivacyPreference.KEY_SEEN_RECEIPTS));
        assertTrue(PrivacyPreference.concerns(PrivacyPreference.KEY_TYPING));
        assertTrue(PrivacyPreference.concerns(null));
        assertFalse(PrivacyPreference.concerns("pref_send_read_receipts"));
        assertFalse(PrivacyPreference.concerns("dark_mode_pref"));
    }

    @Test
    public void with_read_receipts_off_nothing_goes_and_once_on_the_newest_item_does()
    {
        final MapStore store = new MapStore();
        final FakePort port = new FakePort();
        final HelperMessageSeen.Reader reader = new HelperMessageSeen.Reader(store, port);

        PrivacyPreference.sendsSeenReceipts = false;
        reader.offer(FRIEND, candidate(ID_A, 1000L, 1L), NOW_SEC);
        reader.onFriendOnline(FRIEND);
        assertEquals(0, port.attempts);
        assertTrue(store.values.isEmpty());

        PrivacyPreference.sendsSeenReceipts = true;
        reader.offer(FRIEND, candidate(ID_A, 1000L, 1L), NOW_SEC + 1);
        assertEquals(1, port.sent.size());
        assertEquals(1, port.attempts);
    }

    @Test
    public void an_anchor_that_waited_for_the_person_is_dropped_once_receipts_are_off()
    {
        final MapStore store = new MapStore();
        final FakePort offline = new FakePort();
        offline.online = false;
        PrivacyPreference.sendsSeenReceipts = true;
        new HelperMessageSeen.Reader(store, offline).offer(FRIEND, candidate(ID_B, 2000L, 2L), NOW_SEC);
        assertTrue(store.values.containsKey(HelperMessageSeen.PENDING_KEY_PREFIX + FRIEND));

        // Turned off; the app starts again and the person connects.
        PrivacyPreference.sendsSeenReceipts = false;
        final FakePort later = new FakePort();
        final HelperMessageSeen.Reader restarted = new HelperMessageSeen.Reader(store, later);
        restarted.onFriendOnline(FRIEND);
        assertEquals(0, later.attempts);
        assertFalse(store.values.containsKey(HelperMessageSeen.PENDING_KEY_PREFIX + FRIEND));

        // Turned on again, nothing old comes back.
        PrivacyPreference.sendsSeenReceipts = true;
        restarted.onFriendOnline(FRIEND);
        assertEquals(0, later.attempts);
    }

    @Test
    public void the_receipts_of_others_still_show_with_ours_off()
    {
        PrivacyPreference.sendsSeenReceipts = false;
        final MapStore store = new MapStore();
        final HelperMessageSeen.Watermarks watermarks = new HelperMessageSeen.Watermarks(store);
        assertTrue(watermarks.advance(FRIEND, 2000L));
        assertEquals(2000L, watermarks.get(FRIEND));

        // The sender's side never asks the switch.
        final String seen = read(JAVA + "HelperMessageSeen.java");
        assertFalse(method(seen, "static void handleIncomingFriendSeen(").contains("PrivacyPreference"));
        assertFalse(method(seen, "private static void onSeen(").contains("PrivacyPreference"));
        assertFalse(method(seen, "static boolean coveredBySeen(").contains("PrivacyPreference"));
        // The reader asks it before it looks at the database at all.
        assertTrue(method(seen, "private static void lookNow(").contains("!PrivacyPreference.sendsSeenReceipts"));
    }

    @Test
    public void typing_is_sent_only_from_the_field_of_a_conversation_and_only_with_the_switch_on()
    {
        final String chat = read(JAVA + "MessageListActivity.java");
        final int watcher = chat.indexOf("public void afterTextChanged(Editable s)");
        assertTrue(watcher > 0);
        final int guard = chat.indexOf("if (!PrivacyPreference.sendsTyping)", watcher);
        final int firstTyping = chat.indexOf("global_typing = 1;", watcher);
        assertTrue(guard > watcher && guard < firstTyping);
        assertTrue(chat.substring(guard, firstTyping).contains("return;"));
        // The two stops send only after a start: they check global_typing == 1 first.
        final String stop = method(chat, "void stop_self_typing_indicator()");
        assertTrue(stop.indexOf("if (global_typing == 1)") < stop.indexOf("tox_self_set_typing("));

        // No other screen tells toxcore that we type (groups have no typing state).
        final File dir = file(JAVA);
        final File[] sources = dir.listFiles((d, n) -> n.endsWith(".java"));
        assertNotNull(sources);
        final List<String> callers = new ArrayList<>();
        for (final File source : sources)
        {
            final String text = read(JAVA + source.getName());
            if (text.contains("tox_self_set_typing(") && !source.getName().equals("MainActivity.java"))
            {
                callers.add(source.getName());
            }
        }
        assertEquals(List.of("MessageListActivity.java"), callers);
        assertFalse(read(JAVA + "MainActivity.java").replace("public static native int tox_self_set_typing(", "")
                                                      .contains("tox_self_set_typing("));
    }

    @Test
    public void settings_privacy_has_both_switches_and_the_app_loads_them_first()
    {
        final String settings = read(JAVA + "SettingsTabFragment.java");
        assertTrue(settings.contains("R.string.air_privacy_read_receipts)"));
        assertTrue(settings.contains("R.string.air_privacy_read_receipts_note"));
        assertTrue(settings.contains("PrivacyPreference.setSeenReceipts(prefs, checked)"));
        assertTrue(settings.contains("R.string.air_privacy_typing"));
        assertTrue(settings.contains("PrivacyPreference.setTyping(prefs, checked)"));
        assertTrue(settings.indexOf("air_privacy_read_receipts") < settings.indexOf("R.string.air_blocked_title"));

        final String app = read(JAVA + "MainApplication.java");
        assertTrue(app.indexOf("PrivacyPreference.load(sp);") < app.indexOf("sp.registerOnSharedPreferenceChangeListener"));
        assertTrue(app.contains("if (PrivacyPreference.concerns(key))"));
    }

    @Test
    public void the_words_are_there_in_all_four_languages()
    {
        final String[][] expected = {
                {"values", "Send read receipts", "read their messages", "typing"},
                {"values-ru", "Отправлять отчёты о прочтении", "прочитали", "печатаю"},
                {"values-ar", "إشعارات القراءة", "قرأت", "أكتب"},
                {"values-zh-rCN", "已读回执", "已读", "正在输入"},
        };
        for (final String[] language : expected)
        {
            final String strings = read(RES + language[0] + "/strings_air_settings.xml");
            assertTrue(language[0], string(strings, "air_privacy_read_receipts", language[0]).contains(language[1]));
            assertTrue(language[0], string(strings, "air_privacy_read_receipts_note", language[0]).contains(language[2]));
            assertTrue(language[0], string(strings, "air_privacy_typing", language[0]).contains(language[3]));
        }
    }

    private static HelperMessageSeen.Candidate candidate(final String id, final long sent, final long rowId)
    {
        final Message m = new Message();
        m.id = rowId;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.direction = 0;
        m.tox_friendpubkey = FRIEND;
        m.msg_idv3_hash = id;
        m.sent_timestamp = sent;
        m.text = "hi";
        final HelperMessageSeen.Candidate c = HelperMessageSeen.Candidate.of(m);
        assertNotNull(c);
        return c;
    }

    private static String string(final String strings, final String name, final String language)
    {
        final Matcher found = Pattern.compile("<string name=\"" + name + "\">([^<]*)</string>").matcher(strings);
        assertTrue(language + " has no " + name, found.find());
        return found.group(1);
    }

    /** The text of the method that starts with signature, up to its closing brace at class level. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static File file(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.exists())
            {
                return f;
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }

    private static String read(final String name)
    {
        try
        {
            return new String(Files.readAllBytes(file(name).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
        catch (IOException e)
        {
            throw new AssertionError(name + ": " + e.getMessage());
        }
    }

    /** g_opts, as a map. */
    private static final class MapStore implements HelperMessageSeen.Store
    {
        final Map<String, String> values = new HashMap<>();

        @Override
        public boolean ready()
        {
            return true;
        }

        @Override
        public String get(final String key)
        {
            return values.get(key);
        }

        @Override
        public void set(final String key, final String value)
        {
            values.put(key, value);
        }

        @Override
        public void delete(final String key)
        {
            values.remove(key);
        }
    }

    /** toxcore and the clock; timers are kept and never run. */
    private static final class FakePort implements HelperMessageSeen.Port
    {
        boolean online = true;
        int attempts = 0;
        final List<byte[]> sent = new ArrayList<>();

        @Override
        public boolean connected(final String publicKey)
        {
            return online;
        }

        @Override
        public boolean send(final String publicKey, final byte[] packet)
        {
            attempts++;
            sent.add(packet);
            return true;
        }

        @Override
        public long now()
        {
            return 50_000L;
        }

        @Override
        public void schedule(final Runnable work, final long delayMs)
        {
        }

        @Override
        public void log(final String line)
        {
        }
    }
}
