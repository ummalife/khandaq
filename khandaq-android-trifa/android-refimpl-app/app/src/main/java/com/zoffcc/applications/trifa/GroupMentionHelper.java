package com.zoffcc.applications.trifa;

import android.content.Context;
import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.luseen.autolinklibrary.AutoLinkMode;
import com.luseen.autolinklibrary.AutoLinkOnClickListener;
import com.luseen.autolinklibrary.EmojiTextViewLinks;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.zoffcc.applications.trifa.HelperGroup.collect_group_members_for_display;
import static com.zoffcc.applications.trifa.HelperGroup.dedupe_group_members_for_display;
import static com.zoffcc.applications.trifa.HelperGroup.tox_group_by_groupid__wrapper;
import static com.zoffcc.applications.trifa.MainActivity.tox_group_self_get_public_key;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TOXURL_PATTERN;

/** Encode/decode @mention metadata in group message text (wire + local DB). */
final class GroupMentionHelper
{
    static final String MENTION_BLOCK_PREFIX = "[KQ|m|";
    static final String MENTION_BLOCK_SUFFIX = "][KQ/m]";

    /**
     * KHANDAQ (26.11.2026): a mention is "@" and a handle in any script, as handles are made from names in
     * any script (mentionHandleFromName). It was ASCII only, so a Cyrillic or Arabic handle picked from the
     * list went out without the mention block: no mark and no notification on the other side.
     */
    static final Pattern MENTION_TOKEN = Pattern.compile("@([\\p{L}\\p{M}\\p{N}_]+)");

    static final class MentionEntry
    {
        public String pubkey;
        public String handle;
    }

    static final class ParsedGroupText
    {
        @Nullable
        public MessageReplyHelper.ReplyMeta reply;
        public List<MentionEntry> mentions = new ArrayList<>();
        public String bodyText = "";
    }

    private GroupMentionHelper()
    {
    }

    static ParsedGroupText parse(final String rawText)
    {
        final ParsedGroupText parsed = new ParsedGroupText();
        final MessageReplyHelper.ParsedMessage replyParsed = MessageReplyHelper.parse(rawText);
        parsed.reply = replyParsed.reply;

        String body = replyParsed.bodyText == null ? "" : replyParsed.bodyText;
        if (body.startsWith(MENTION_BLOCK_PREFIX))
        {
            final int blockEnd = body.indexOf(MENTION_BLOCK_SUFFIX);
            if (blockEnd > 0)
            {
                final String payload = body.substring(MENTION_BLOCK_PREFIX.length(), blockEnd);
                parsed.mentions = parseMentionPayload(payload);
                final int bodyStart = blockEnd + MENTION_BLOCK_SUFFIX.length();
                body = bodyStart < body.length() ? trimLeading(body.substring(bodyStart)) : "";
            }
            else
            {
                final int lineEnd = body.indexOf('\n');
                body = lineEnd >= 0 ? trimLeading(body.substring(lineEnd + 1)) : "";
            }
        }

        parsed.bodyText = body;
        return parsed;
    }

    /** KHANDAQ (#T5): clean a stored raw message text for the clipboard — strips the reply header
     *  ([KQ|…][KQ/end]) and the @mention block ([KQ|m|…][KQ/m]) so Copy yields only the visible text
     *  instead of leaking the raw wire markers (which then paste back into the input field). */
    static String cleanForClipboard(final String rawText)
    {
        if (rawText == null)
        {
            return "";
        }
        final String body = parse(rawText).bodyText;
        return body == null ? rawText : body;
    }

    static String encodeMentionsBlock(final String bodyText, final List<MentionEntry> mentions)
    {
        if (mentions == null || mentions.isEmpty())
        {
            return bodyText == null ? "" : bodyText;
        }

        final StringBuilder sb = new StringBuilder();
        sb.append(MENTION_BLOCK_PREFIX);
        for (int i = 0; i < mentions.size(); i++)
        {
            if (i > 0)
            {
                sb.append(';');
            }
            final MentionEntry entry = mentions.get(i);
            sb.append(sanitizeField(entry.pubkey)).append(':').append(sanitizeField(entry.handle));
        }
        sb.append(MENTION_BLOCK_SUFFIX).append('\n');
        sb.append(bodyText == null ? "" : bodyText);
        return sb.toString();
    }

    static List<MentionEntry> collectMentionsForOutgoing(final String bodyText, final String groupIdentifier)
    {
        final List<MentionEntry> result = new ArrayList<>();
        if (TextUtils.isEmpty(bodyText) || TextUtils.isEmpty(groupIdentifier))
        {
            return result;
        }

        // KHANDAQ (ANR, 2026-08-26): the member walk below enumerates every peer in the group,
        // with JNI and SQLCipher behind it, and it ran on the main thread for every outgoing group
        // message - including the overwhelming majority that mention nobody. MENTION_TOKEN starts
        // with a literal '@' and so cannot match without one, which makes this guard exactly
        // equivalent to running the walk and finding nothing.
        if (bodyText.indexOf('@') < 0)
        {
            return result;
        }

        final List<HelperGroup.GroupMemberDisplay> members =
                dedupe_group_members_for_display(collect_group_members_for_display(groupIdentifier));
        final Map<String, String> handles = mentionHandles(members);
        final Matcher matcher = MENTION_TOKEN.matcher(bodyText);
        final Set<String> seenPubkeys = new HashSet<>();

        while (matcher.find())
        {
            final String token = matcher.group(1);
            if (token == null || token.isEmpty())
            {
                continue;
            }

            final String pubkeyKey = keyOfHandle(handles, token);
            if (pubkeyKey == null)
            {
                continue;
            }
            if (seenPubkeys.contains(pubkeyKey))
            {
                continue;
            }
            seenPubkeys.add(pubkeyKey);

            final MentionEntry entry = new MentionEntry();
            entry.pubkey = pubkeyKey;
            entry.handle = token;
            result.add(entry);
        }

        return result;
    }

    /** Key characters a handle two members would share gets: 4, and 8 when that still leaves two alike. */
    private static final int SHORT_KEY_DIGITS = 4;
    private static final int LONG_KEY_DIGITS = 8;

    /**
     * KHANDAQ (26.11.2026): the handle of every member, by the member's key in upper case, made as iOS and
     * desktop make them (DESIGN-group-contacts-private-mentions.md): the handle of the name, else "u" and the
     * first 8 characters of the key. A handle two members would share, in any case of its letters, gets "_" and
     * the first 4 characters of the key for each of them, and 8 when that still leaves two alike.
     *
     * The first member used to keep the bare handle and the next one got the suffix, so the handle followed
     * the order of the list. And the list, the inserted text and the mention block each made their own: the
     * list offered "@Ali" for both twins, the second one picked went out as "@Ali", and the block named the first.
     * One map, in no order, names everyone alike on every client and in every place here.
     */
    static Map<String, String> mentionHandles(@Nullable final List<HelperGroup.GroupMemberDisplay> members)
    {
        final Map<String, String> base = new LinkedHashMap<>();
        if (members != null)
        {
            for (final HelperGroup.GroupMemberDisplay member : members)
            {
                final String key = keyOf(member);
                if (key == null || base.containsKey(key))
                {
                    continue;
                }
                final String handle = mentionHandleFromName(member.name);
                base.put(key, handle.isEmpty() ? "u" + head(key, LONG_KEY_DIGITS) : handle);
            }
        }
        final Map<String, String> handles = new LinkedHashMap<>(base);
        for (final int digits : new int[]{SHORT_KEY_DIGITS, LONG_KEY_DIGITS})
        {
            final Map<String, Integer> uses = new HashMap<>();
            for (final String handle : handles.values())
            {
                final String folded = handle.toLowerCase(Locale.ROOT);
                final Integer seen = uses.get(folded);
                uses.put(folded, seen == null ? 1 : seen + 1);
            }
            for (final Map.Entry<String, String> entry : handles.entrySet())
            {
                final Integer seen = uses.get(entry.getValue().toLowerCase(Locale.ROOT));
                if (seen != null && seen > 1)
                {
                    entry.setValue(base.get(entry.getKey()) + "_" + head(entry.getKey(), digits));
                }
            }
        }
        return handles;
    }

    /** The handle of this member in that map, or the handle of their name when the map does not have them. */
    static String handleOf(final Map<String, String> handles, final HelperGroup.GroupMemberDisplay member)
    {
        final String key = keyOf(member);
        final String handle = key == null ? null : handles.get(key);
        if (handle != null)
        {
            return handle;
        }
        final String fromName = member == null ? "" : mentionHandleFromName(member.name);
        return fromName.isEmpty() ? "user" : fromName;
    }

    /** The key whose handle the token is, without regard to case; of two alike, the lower key, as on iOS. */
    @Nullable
    static String keyOfHandle(final Map<String, String> handles, @Nullable final String token)
    {
        if (token == null || token.isEmpty())
        {
            return null;
        }
        for (final Map.Entry<String, String> entry : new TreeMap<>(handles).entrySet())
        {
            if (entry.getValue().equalsIgnoreCase(token))
            {
                return entry.getKey();
            }
        }
        return null;
    }

    @Nullable
    private static String keyOf(@Nullable final HelperGroup.GroupMemberDisplay member)
    {
        if (member == null || member.pubkey == null)
        {
            return null;
        }
        final String key = member.pubkey.trim().toUpperCase(Locale.ROOT);
        return key.isEmpty() ? null : key;
    }

    private static String head(final String key, final int digits)
    {
        return key.length() <= digits ? key : key.substring(0, digits);
    }

    static String mentionHandleFromName(@Nullable final String displayName)
    {
        if (displayName == null || displayName.isEmpty())
        {
            return "";
        }

        String stripped = displayName;
        final int paren = stripped.indexOf(" (");
        if (paren > 0)
        {
            stripped = stripped.substring(0, paren);
        }

        final StringBuilder sb = new StringBuilder();
        boolean prevUnderscore = false;
        for (int i = 0; i < stripped.length(); i++)
        {
            final char c = stripped.charAt(i);
            if (Character.isLetterOrDigit(c))
            {
                sb.append(c);
                prevUnderscore = false;
            }
            else if (isCombiningMark(c) && sb.length() > 0 && !prevUnderscore)
            {
                // KHANDAQ (26.11.2026): the mark that belongs to the letter before it stays, so an Indic
                // name keeps its vowel signs and an Arabic one its harakat.
                sb.append(c);
            }
            else if ((c == ' ' || c == '-' || c == '.') && !prevUnderscore && sb.length() > 0)
            {
                sb.append('_');
                prevUnderscore = true;
            }
        }

        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '_')
        {
            sb.deleteCharAt(sb.length() - 1);
        }
        return sb.toString();
    }

    private static boolean isCombiningMark(final char c)
    {
        final int type = Character.getType(c);
        return type == Character.NON_SPACING_MARK || type == Character.COMBINING_SPACING_MARK
               || type == Character.ENCLOSING_MARK;
    }

    static String insertMentionToken(final String handle)
    {
        return "@" + handle + " ";
    }

    /**
     * KHANDAQ (26.11.2026): a text whose block does not name us mentions us by a whole @token that is our handle,
     * or the handle of our name: a sender that made handles in the order of its list wrote a twin's bare handle,
     * and an older one sent a Cyrillic mention with no block at all. A token the block already gave to someone
     * else is theirs, and "@Ali" is not found inside "@Alina" any more.
     */
    static boolean mentionsSelfByText(final ParsedGroupText parsed, final String selfHandle,
                                      @Nullable final String selfNameHandle)
    {
        if (parsed == null || parsed.bodyText == null || selfHandle == null || selfHandle.isEmpty())
        {
            return false;
        }
        final Set<String> taken = new HashSet<>();
        for (final MentionEntry entry : parsed.mentions)
        {
            if (entry.handle != null)
            {
                taken.add(entry.handle.toLowerCase(Locale.ROOT));
            }
        }
        final Matcher tokens = MENTION_TOKEN.matcher(parsed.bodyText);
        while (tokens.find())
        {
            final String token = tokens.group(1);
            if (taken.contains(token.toLowerCase(Locale.ROOT)))
            {
                continue;
            }
            if (token.equalsIgnoreCase(selfHandle)
                    || (selfNameHandle != null && !selfNameHandle.isEmpty() && token.equalsIgnoreCase(selfNameHandle)))
            {
                return true;
            }
        }
        return false;
    }

    static boolean isSelfMentioned(final String rawText, final String groupIdentifier)
    {
        if (TextUtils.isEmpty(rawText) || TextUtils.isEmpty(groupIdentifier))
        {
            return false;
        }

        try
        {
            final String selfPubkey = tox_group_self_get_public_key(
                    tox_group_by_groupid__wrapper(groupIdentifier)).toUpperCase(Locale.ENGLISH);
            if (TextUtils.isEmpty(selfPubkey))
            {
                return false;
            }

            final ParsedGroupText parsed = parse(rawText);
            for (int i = 0; i < parsed.mentions.size(); i++)
            {
                final MentionEntry entry = parsed.mentions.get(i);
                if (entry.pubkey != null && entry.pubkey.equalsIgnoreCase(selfPubkey))
                {
                    return true;
                }
            }

            final List<HelperGroup.GroupMemberDisplay> members =
                    dedupe_group_members_for_display(collect_group_members_for_display(groupIdentifier));
            HelperGroup.GroupMemberDisplay selfMember = null;
            for (int i = 0; i < members.size(); i++)
            {
                if (members.get(i) != null && members.get(i).self)
                {
                    selfMember = members.get(i);
                    break;
                }
            }
            if (selfMember == null)
            {
                return false;
            }

            return mentionsSelfByText(parsed, handleOf(mentionHandles(members), selfMember),
                                      mentionHandleFromName(selfMember.name));
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static String notificationPreviewText(final String rawText)
    {
        final ParsedGroupText parsed = parse(rawText);
        if (parsed.bodyText == null || parsed.bodyText.isEmpty())
        {
            return "";
        }
        return parsed.bodyText.replace('\n', ' ').trim();
    }

    static void bindMessageText(final EmojiTextViewLinks textView,
                                final ParsedGroupText parsed,
                                final String groupIdentifier,
                                final Context context,
                                @Nullable final String searchHighlight,
                                final AutoLinkOnClickListener linkListener)
    {
        if (textView == null || parsed == null)
        {
            return;
        }

        final String body = parsed.bodyText == null ? "" : parsed.bodyText;
        textView.setCustomRegex(TOXURL_PATTERN);
        // KHANDAQ (redesign, stage 5): a mention in ink, bold, as iOS draws it (link-text is ink there); the old
        // dark green vanished on the dark theme, on the selected ground of a message that mentions you.
        textView.setMentionModeColor(ContextCompat.getColor(context, R.color.kq_ink));
        textView.addAutoLinkMode(AutoLinkMode.MODE_URL, AutoLinkMode.MODE_EMAIL, AutoLinkMode.MODE_HASHTAG,
                AutoLinkMode.MODE_MENTION, AutoLinkMode.MODE_CUSTOM);

        if (searchHighlight != null && !searchHighlight.isEmpty())
        {
            textView.setAutoLinkTextHighlight(body, searchHighlight);
        }
        else
        {
            textView.setAutoLinkText(body);
        }

        applyMentionSpans(textView, body, parsed.mentions, groupIdentifier, context);
        textView.setAutoLinkOnClickListener(linkListener);
        textView.setOnClickListener(null);
        textView.setClickable(true);
        textView.setLongClickable(true);
    }

    static void openMentionFromMatchedText(final Context context, final String groupIdentifier,
                                           final String matchedText)
    {
        if (context == null || groupIdentifier == null || matchedText == null)
        {
            return;
        }

        final String handle = matchedText.replaceFirst("^\\s", "").replaceFirst("^@", "").trim();
        if (handle.isEmpty())
        {
            return;
        }

        final List<HelperGroup.GroupMemberDisplay> members =
                dedupe_group_members_for_display(collect_group_members_for_display(groupIdentifier));
        final String key = keyOfHandle(mentionHandles(members), handle);
        if (key == null)
        {
            return;
        }
        for (final HelperGroup.GroupMemberDisplay member : members)
        {
            if (key.equals(keyOf(member)))
            {
                GroupMessageListActivity.open_group_peer_info_activity(context, member.pubkey, groupIdentifier);
                return;
            }
        }
    }

    private static void applyMentionSpans(final EmojiTextViewLinks textView,
                                          final String body,
                                          final List<MentionEntry> mentions,
                                          final String groupIdentifier,
                                          final Context context)
    {
        if (body == null || body.isEmpty())
        {
            return;
        }

        List<MentionEntry> effectiveMentions = mentions;
        if ((effectiveMentions == null || effectiveMentions.isEmpty()) && groupIdentifier != null)
        {
            effectiveMentions = collectMentionsForOutgoing(body, groupIdentifier);
        }

        if (effectiveMentions == null || effectiveMentions.isEmpty())
        {
            return;
        }

        final CharSequence current = textView.getText();
        if (!(current instanceof Spannable))
        {
            return;
        }

        final Spannable spannable = (Spannable) current;
        final int mentionColor = ContextCompat.getColor(context, R.color.kq_ink);

        for (int i = 0; i < effectiveMentions.size(); i++)
        {
            final MentionEntry entry = effectiveMentions.get(i);
            if (entry == null || entry.handle == null)
            {
                continue;
            }

            final String token = "@" + entry.handle;
            int searchFrom = 0;
            while (searchFrom < body.length())
            {
                final int index = body.indexOf(token, searchFrom);
                if (index < 0)
                {
                    break;
                }

                final int end = index + token.length();
                spannable.setSpan(new StyleSpan(Typeface.BOLD), index, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                spannable.setSpan(new ForegroundColorSpan(mentionColor), index, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                searchFrom = end;
            }
        }
    }

    private static List<MentionEntry> parseMentionPayload(final String payload)
    {
        final List<MentionEntry> mentions = new ArrayList<>();
        if (payload == null || payload.isEmpty())
        {
            return mentions;
        }

        final String[] parts = payload.split(";");
        for (int i = 0; i < parts.length; i++)
        {
            final String part = parts[i];
            final int colon = part.indexOf(':');
            if (colon <= 0)
            {
                continue;
            }
            final MentionEntry entry = new MentionEntry();
            entry.pubkey = part.substring(0, colon).toUpperCase(Locale.ENGLISH);
            entry.handle = part.substring(colon + 1);
            mentions.add(entry);
        }
        return mentions;
    }

    private static String sanitizeField(@Nullable final String value)
    {
        if (value == null)
        {
            return "";
        }
        return value.replace("|", "").replace(";", "").replace(":", "").replace("\n", " ").trim();
    }

    private static String trimLeading(final String text)
    {
        int i = 0;
        while (i < text.length() && (text.charAt(i) == '\n' || text.charAt(i) == '\r'))
        {
            i++;
        }
        return text.substring(i);
    }
}
