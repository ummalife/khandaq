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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    private static final Pattern MENTION_TOKEN = Pattern.compile("@([A-Za-z0-9_]+)");

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

        final List<HelperGroup.GroupMemberDisplay> members =
                dedupe_group_members_for_display(collect_group_members_for_display(groupIdentifier));
        final Matcher matcher = MENTION_TOKEN.matcher(bodyText);
        final Set<String> seenPubkeys = new HashSet<>();

        while (matcher.find())
        {
            final String token = matcher.group(1);
            if (token == null || token.isEmpty())
            {
                continue;
            }

            final HelperGroup.GroupMemberDisplay matched = findMemberForMentionToken(members, token);
            if (matched == null || matched.pubkey == null)
            {
                continue;
            }

            final String pubkeyKey = matched.pubkey.toUpperCase(Locale.ENGLISH);
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

    private static HelperGroup.GroupMemberDisplay findMemberForMentionToken(
            final List<HelperGroup.GroupMemberDisplay> members, final String token)
    {
        if (members == null || token == null)
        {
            return null;
        }

        for (int i = 0; i < members.size(); i++)
        {
            final HelperGroup.GroupMemberDisplay member = members.get(i);
            if (member == null)
            {
                continue;
            }
            final String baseHandle = mentionHandleFromName(member.name);
            if (!baseHandle.isEmpty() && token.equalsIgnoreCase(baseHandle))
            {
                return member;
            }
        }

        for (int i = 0; i < members.size(); i++)
        {
            final HelperGroup.GroupMemberDisplay member = members.get(i);
            if (member == null || member.pubkey == null)
            {
                continue;
            }
            final Set<String> used = new HashSet<>();
            final String unique = uniqueHandle(member.name, member.pubkey, used, false);
            if (token.equalsIgnoreCase(unique))
            {
                return member;
            }
        }

        return null;
    }

    static String mentionHandleForMember(final HelperGroup.GroupMemberDisplay member,
                                         final Set<String> usedHandles,
                                         final boolean reserveHandle)
    {
        if (member == null)
        {
            return "user";
        }
        return uniqueHandle(member.name, member.pubkey, usedHandles, reserveHandle);
    }

    static String uniqueHandle(@Nullable final String displayName,
                               @Nullable final String pubkey,
                               final Set<String> usedHandles,
                               final boolean reserve)
    {
        String base = mentionHandleFromName(displayName);
        if (base.isEmpty() && pubkey != null && pubkey.length() >= 8)
        {
            base = "u" + pubkey.substring(0, 8);
        }
        if (base.isEmpty())
        {
            base = "user";
        }

        String candidate = base;
        int suffix = 2;
        while (usedHandles.contains(candidate.toLowerCase(Locale.ENGLISH)))
        {
            final String tail = (pubkey != null && pubkey.length() >= 4)
                    ? pubkey.substring(0, 4) : String.valueOf(suffix);
            candidate = base + "_" + tail;
            suffix++;
        }

        if (reserve)
        {
            usedHandles.add(candidate.toLowerCase(Locale.ENGLISH));
        }
        return candidate;
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

    static String insertMentionToken(final String handle)
    {
        return "@" + handle + " ";
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

            final Set<String> used = new HashSet<>();
            final String selfHandle = mentionHandleForMember(selfMember, used, false);
            return parsed.bodyText != null
                    && parsed.bodyText.toLowerCase(Locale.ENGLISH).contains("@" + selfHandle.toLowerCase(Locale.ENGLISH));
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
        textView.setMentionModeColor(ContextCompat.getColor(context, R.color.khandaq_primary));
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
        final HelperGroup.GroupMemberDisplay matched = findMemberForMentionToken(members, handle);
        if (matched != null && matched.pubkey != null)
        {
            GroupMessageListActivity.open_group_peer_info_activity(context, matched.pubkey, groupIdentifier);
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
        final int mentionColor = ContextCompat.getColor(context, R.color.khandaq_primary);

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
