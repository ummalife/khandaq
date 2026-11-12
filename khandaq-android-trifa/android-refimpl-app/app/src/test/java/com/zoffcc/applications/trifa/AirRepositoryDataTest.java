package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.zoffcc.applications.trifa.AirModel.Entry;
import static com.zoffcc.applications.trifa.AirModel.EntryKind;
import static com.zoffcc.applications.trifa.AirModel.NetworkState;
import static com.zoffcc.applications.trifa.AirModel.PreviewStyle;
import static com.zoffcc.applications.trifa.AirModel.Reach;
import static com.zoffcc.applications.trifa.AirModel.Request;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (redesign): Air redraws only when the data would draw a different screen. Group
 * maintenance asks for a refresh about once a second with nothing new, and redrawing for each of
 * those kept the window from ever going idle (TalkBack and UI automation both wait for idle).
 */
public class AirRepositoryDataTest
{
    @Test
    public void fresh_copies_of_the_same_rows_draw_the_same_screen()
    {
        assertTrue(data(rows("5 min"), photos(10L)).sameScreen(data(rows("5 min"), photos(10L))));
    }

    @Test
    public void a_changed_row_is_a_new_screen()
    {
        assertFalse(data(rows("5 min"), photos(10L)).sameScreen(data(rows("6 min"), photos(10L))));
    }

    @Test
    public void a_new_photo_is_a_new_screen()
    {
        assertFalse(data(rows("5 min"), photos(10L)).sameScreen(data(rows("5 min"), photos(11L))));
        assertFalse(data(rows("5 min"), photos(10L)).sameScreen(
                data(rows("5 min"), Collections.<String, ChatBubbleUiHelper.AvatarMeta>emptyMap())));
    }

    @Test
    public void the_header_counts_too()
    {
        final AirRepository.Data base = data(rows("5 min"), photos(10L));
        assertFalse(base.sameScreen(new AirRepository.Data(rows("5 min"), requests(), NetworkState.RELAY, "Bilal",
                                                           null, photos(10L), sources(), true)));
        assertFalse(base.sameScreen(new AirRepository.Data(rows("5 min"), requests(), NetworkState.DIRECT, "Bila",
                                                           null, photos(10L), sources(), true)));
        assertFalse(base.sameScreen(new AirRepository.Data(rows("5 min"), Collections.<Request>emptyList(),
                                                           NetworkState.DIRECT, "Bilal", null, photos(10L),
                                                           sources(), true)));
    }

    @Test
    public void the_first_read_is_always_drawn()
    {
        assertFalse(data(rows("5 min"), photos(10L)).sameScreen(AirRepository.Data.EMPTY));
        assertFalse(AirRepository.Data.EMPTY.sameScreen(null));
    }

    private static AirRepository.Data data(final List<Entry> entries,
                                           final Map<String, ChatBubbleUiHelper.AvatarMeta> photos)
    {
        return new AirRepository.Data(entries, requests(), NetworkState.DIRECT, "Bilal", null, photos, sources(),
                                      true);
    }

    private static List<Entry> rows(final String timeText)
    {
        return Arrays.asList(
                new Entry("friend-a", EntryKind.DIRECT, "Amina", Reach.DIRECT, "salam", PreviewStyle.MESSAGE, null,
                          timeText, 300L, 0, 1, false, "photo-a", AirModel.Tone.index("friend-a")),
                new Entry("group-b", EntryKind.GROUP, "Study circle", Reach.away(3600), "", PreviewStyle.MESSAGE,
                          null, "", 200L, 0, 0, true, null, AirModel.Tone.index("group-b")));
    }

    private static List<Request> requests()
    {
        return Collections.singletonList(new Request("ab12", "hello", "now", 1000L));
    }

    private static Map<String, ChatBubbleUiHelper.AvatarMeta> photos(final long updatedAt)
    {
        final Map<String, ChatBubbleUiHelper.AvatarMeta> photos = new HashMap<>();
        photos.put("photo-a", new ChatBubbleUiHelper.AvatarMeta(true, "/vfs/a", "a.png", updatedAt));
        return photos;
    }

    private static Map<String, AirRepository.Source> sources()
    {
        return Collections.emptyMap();
    }
}
