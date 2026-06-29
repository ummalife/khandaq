package com.zoffcc.applications.trifa;

import com.vanniktech.emoji.EmojiProvider;
import com.vanniktech.emoji.emoji.Emoji;
import com.vanniktech.emoji.emoji.EmojiCategory;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

/** Google emoji set with Khandaq-specific category filtering. */
public final class KhandaqGoogleEmojiProvider implements EmojiProvider
{
    private static final GoogleEmojiProvider DELEGATE = new GoogleEmojiProvider();

    @Override
    public EmojiCategory[] getCategories()
    {
        final EmojiCategory[] original = DELEGATE.getCategories();
        final EmojiCategory[] filtered = new EmojiCategory[original.length];
        for (int i = 0; i < original.length; i++)
        {
            filtered[i] = wrapCategory(original[i]);
        }
        return filtered;
    }

    private static EmojiCategory wrapCategory(final EmojiCategory category)
    {
        if (category == null)
        {
            return null;
        }

        if (category.getClass().getSimpleName().startsWith("FlagsCategory"))
        {
            return new FilteredEmojiCategory(category, KhandaqGoogleEmojiProvider::isBlockedFlagEmoji);
        }

        return category;
    }

    static boolean isBlockedFlagEmoji(final Emoji emoji)
    {
        if (emoji == null)
        {
            return true;
        }

        final String unicode = emoji.getUnicode();
        if (unicode == null || unicode.isEmpty())
        {
            return false;
        }

        // 🏳️‍🌈 rainbow pride flag (and variants without VS16)
        return unicode.contains("\uD83C\uDFF3\uFE0F\u200D\uD83C\uDF08")
                || unicode.contains("\uD83C\uDFF3\u200D\uD83C\uDF08");
    }
}
