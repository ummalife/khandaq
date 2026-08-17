package com.zoffcc.applications.trifa;

import com.vanniktech.emoji.EmojiProvider;
import com.vanniktech.emoji.emoji.Emoji;
import com.vanniktech.emoji.emoji.EmojiCategory;
import com.vanniktech.emoji.google.GoogleEmojiProvider;

import java.util.ArrayList;
import java.util.List;

/** Google emoji set with Khandaq-specific filtering (see {@link KhandaqEmojiFilter}). */
public final class KhandaqGoogleEmojiProvider implements EmojiProvider
{
    private static final GoogleEmojiProvider DELEGATE = new GoogleEmojiProvider();

    /**
     * Categories whose contents are, essentially in their entirety, depictions of living beings.
     * Only dropped when the user asks for it — see {@link KhandaqEmojiFilter#hideLivingBeings()}.
     */
    private static boolean isLivingBeingCategory(final EmojiCategory category)
    {
        final String name = category.getClass().getSimpleName();
        return name.startsWith("SmileysAndPeopleCategory") || name.startsWith("AnimalsAndNatureCategory");
    }

    @Override
    public EmojiCategory[] getCategories()
    {
        final EmojiCategory[] original = DELEGATE.getCategories();
        final List<EmojiCategory> out = new ArrayList<>(original.length);

        final boolean hideLiving = KhandaqEmojiFilter.hideLivingBeings();

        for (final EmojiCategory category : original)
        {
            if (category == null)
            {
                continue;
            }

            // Dropping a whole category rather than emptying it: an EmojiCategory with zero emojis
            // still gets a tab in the picker, and a tab that opens onto nothing reads as a bug.
            if (hideLiving && isLivingBeingCategory(category))
            {
                continue;
            }

            out.add(new FilteredEmojiCategory(category, KhandaqEmojiFilter::isBlocked));
        }

        return out.toArray(new EmojiCategory[0]);
    }

    /**
     * Kept as a named method because the flag category used to be the only filtered one and the
     * rainbow-flag test is worth being able to point at on its own.
     */
    static boolean isBlockedFlagEmoji(final Emoji emoji)
    {
        return KhandaqEmojiFilter.isBlocked(emoji);
    }
}
