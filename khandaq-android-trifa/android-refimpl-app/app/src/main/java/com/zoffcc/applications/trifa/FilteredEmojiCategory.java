package com.zoffcc.applications.trifa;

import com.vanniktech.emoji.emoji.Emoji;
import com.vanniktech.emoji.emoji.EmojiCategory;

import java.util.ArrayList;
import java.util.List;

/** Wraps an emoji category and removes blocked emojis from the picker grid. */
final class FilteredEmojiCategory implements EmojiCategory
{
    interface EmojiBlocker
    {
        boolean shouldBlock(Emoji emoji);
    }

    private final EmojiCategory delegate;
    private final EmojiBlocker blocker;
    private Emoji[] cached;

    FilteredEmojiCategory(final EmojiCategory delegate, final EmojiBlocker blocker)
    {
        this.delegate = delegate;
        this.blocker = blocker;
    }

    @Override
    public Emoji[] getEmojis()
    {
        if (cached == null)
        {
            final List<Emoji> kept = new ArrayList<>();
            for (final Emoji emoji : delegate.getEmojis())
            {
                if (emoji != null && !blocker.shouldBlock(emoji))
                {
                    kept.add(emoji);
                }
            }
            cached = kept.toArray(new Emoji[0]);
        }
        return cached;
    }

    @Override
    public int getIcon()
    {
        return delegate.getIcon();
    }

    @Override
    public int getCategoryName()
    {
        return delegate.getCategoryName();
    }
}
