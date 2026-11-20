package com.zoffcc.applications.trifa;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (testers' ideas, 0.2.67): a chat opened with unread messages opens at the first of them, under
 * "Unread messages", and no longer at its end. iOS draws the same mark (ChatMovableDateCell, the transcript's
 * unread mark): the words in ink where the text starts, over that entry. The entry stands in the middle of the
 * log when it fits, as iOS scrolls to it, and the jump button takes the person on to the latest as before.
 * The mark stays where it is until the chat is left: messages that come in while it is open do not move it,
 * and a screen made again (a new theme or text size) keeps it.
 *
 * What was unread is read before the chat clears it (is_new is reset on every open and resume and never drawn
 * in a row): when the chat opens, and when it comes back while it has no mark yet. The first row of the log
 * among those carries the mark, so the order of the log (a group sorts by the later of sent and received) and
 * rows the log leaves out cannot misplace it. The mark is this decoration, drawn above that row, so no row
 * layout and no adapter position changes; TalkBack hears it once, as the chat opens there.
 */
final class AirUnreadMark extends RecyclerView.ItemDecoration
{
    static final long NONE = -1L;
    private static final String STATE_CHAT = "air_unread_mark_chat";
    private static final String STATE_ROW = "air_unread_mark_row";
    /** Layouts of the window to wait for the row the log opens at, before giving up on it. */
    private static final int LAYOUT_TRIES = 4;

    /** The id of the message at a position of the log; {@link #NONE} where there is none. */
    interface Rows
    {
        long idAt(int position);
    }

    private final Rows rows;
    /** What was unread when the chat opened, until a load of the log finds the first of it. */
    private Set<Long> unread = null;
    /** The message the mark stands over. */
    private long anchorId = NONE;

    private TextView label = null;
    private int labelWidth = -1;
    private int labelStart = -1;
    private int labelDirection = -1;

    AirUnreadMark(final Rows rows)
    {
        this.rows = rows;
    }

    // ---- what was unread ----

    /** The unread messages of a conversation with one person, by id. Read before the chat clears them. */
    static Set<Long> unreadOfFriend(final String friendPubkey)
    {
        final Set<Long> ids = new HashSet<>();
        if ((orma == null) || (friendPubkey == null))
        {
            return ids;
        }
        try
        {
            for (final Message m : orma.selectFromMessage().tox_friendpubkeyEq(friendPubkey).
                    is_newEq(true).directionEq(0).toList())
            {
                if (m != null)
                {
                    ids.add(m.id);
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return ids;
    }

    /** The unread messages of a group, by id. Read before the chat clears them. */
    static Set<Long> unreadOfGroup(final String groupIdentifier)
    {
        final Set<Long> ids = new HashSet<>();
        if ((orma == null) || (groupIdentifier == null))
        {
            return ids;
        }
        try
        {
            for (final GroupMessage m : orma.selectFromGroupMessage().
                    group_identifierEq(groupIdentifier.toLowerCase()).is_newEq(true).directionEq(0).toList())
            {
                if (m != null)
                {
                    ids.add(m.id);
                }
            }
        }
        catch (Exception ignored)
        {
        }
        return ids;
    }

    /** Keeps what was unread as the chat opened or came back, while the chat has no mark yet. */
    void capture(final Collection<Long> unreadIds)
    {
        if ((anchorId != NONE) || (unread != null) || (unreadIds == null) || unreadIds.isEmpty())
        {
            return;
        }
        unread = new HashSet<>(unreadIds);
    }

    /**
     * After a load of a log of {@code count} rows: the first row that was unread takes the mark. Once per
     * capture; its position, or -1 when there is nothing to mark.
     */
    int resolve(final int count)
    {
        if (unread == null)
        {
            return -1;
        }
        final int position = firstUnreadPosition(rows, count, unread);
        unread = null;
        if (position >= 0)
        {
            anchorId = rows.idAt(position);
        }
        return position;
    }

    /**
     * After a load of the log: puts the mark over the first row that was unread and opens the log there, then
     * runs {@code after} (null for nothing) once the log stands there. True when it did.
     */
    boolean place(final RecyclerView list, final Runnable after)
    {
        if ((unread == null) || (list == null) || (list.getAdapter() == null))
        {
            return false;
        }
        final int position = resolve(list.getAdapter().getItemCount());
        if (position < 0)
        {
            return false;
        }
        list.invalidateItemDecorations();
        openAt(list, position, after);
        return true;
    }

    long anchorId()
    {
        return anchorId;
    }

    /** The first position of the log whose message was unread; -1 for none. */
    static int firstUnreadPosition(final Rows rows, final int count, final Set<Long> unread)
    {
        if ((rows == null) || (unread == null) || unread.isEmpty())
        {
            return -1;
        }
        for (int position = 0; position < count; position++)
        {
            if (unread.contains(rows.idAt(position)))
            {
                return position;
            }
        }
        return -1;
    }

    /** Where the top of the opened row goes in a log {@code room} high: its middle in the middle, or the top. */
    static int openedTop(final int room, final int rowHeight)
    {
        return Math.max(0, (room - rowHeight) / 2);
    }

    // ---- a screen made again ----

    void save(final Bundle state, final String chat)
    {
        if ((state != null) && (chat != null) && (anchorId != NONE))
        {
            state.putString(STATE_CHAT, chat);
            state.putLong(STATE_ROW, anchorId);
        }
    }

    /** The mark of the same chat, kept by {@link #save} when its screen was made again. */
    void restore(final Bundle state, final String chat)
    {
        if ((state == null) || (chat == null) || (anchorId != NONE))
        {
            return;
        }
        if (chat.equalsIgnoreCase(state.getString(STATE_CHAT)))
        {
            anchorId = state.getLong(STATE_ROW, NONE);
        }
    }

    // ---- the log ----

    private void openAt(final RecyclerView list, final int position, final Runnable after)
    {
        final RecyclerView.LayoutManager manager = list.getLayoutManager();
        if (!(manager instanceof LinearLayoutManager))
        {
            return;
        }
        ((LinearLayoutManager) manager).scrollToPositionWithOffset(position, 0);
        // The next layout brings the row in; once it is done, the row is centred, found by its message, since a
        // reload in between can move it.
        list.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener()
        {
            private int tries = 0;

            @Override
            public void onGlobalLayout()
            {
                final View row = rowOf(list);
                if ((row == null) && (++tries < LAYOUT_TRIES))
                {
                    return;
                }
                list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (row == null)
                {
                    return;
                }
                final int room = list.getHeight() - list.getPaddingTop() - list.getPaddingBottom();
                final int top = manager.getDecoratedTop(row);
                final int target = list.getPaddingTop() + openedTop(room, manager.getDecoratedMeasuredHeight(row));
                if (top != target)
                {
                    list.scrollBy(0, top - target);
                }
                list.announceForAccessibility(list.getContext().getString(R.string.air_unread_mark));
                if (after != null)
                {
                    after.run();
                }
            }
        });
    }

    /** The row on screen that carries the mark, or null. */
    private View rowOf(final RecyclerView list)
    {
        for (int i = 0; i < list.getChildCount(); i++)
        {
            final View child = list.getChildAt(i);
            if (carries(list, child))
            {
                return child;
            }
        }
        return null;
    }

    private boolean carries(final RecyclerView list, final View row)
    {
        if (anchorId == NONE)
        {
            return false;
        }
        final int position = list.getChildAdapterPosition(row);
        return (position != RecyclerView.NO_POSITION) && (rows.idAt(position) == anchorId);
    }

    // ---- the mark ----

    @Override
    public void getItemOffsets(@NonNull final Rect outRect, @NonNull final View view, @NonNull final RecyclerView parent,
                               @NonNull final RecyclerView.State state)
    {
        outRect.setEmpty();
        if (!carries(parent, view))
        {
            return;
        }
        final TextView mark = label(parent, view);
        if (mark != null)
        {
            outRect.top = mark.getMeasuredHeight();
        }
    }

    @Override
    public void onDraw(@NonNull final Canvas canvas, @NonNull final RecyclerView parent,
                       @NonNull final RecyclerView.State state)
    {
        if (anchorId == NONE)
        {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++)
        {
            final View row = parent.getChildAt(i);
            if (!carries(parent, row))
            {
                continue;
            }
            final TextView mark = label(parent, row);
            if (mark == null)
            {
                continue;
            }
            final RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) row.getLayoutParams();
            // Above the row, moving with it up and down; a swipe to reply moves the row alone, as on iOS.
            final float top = row.getTop() - params.topMargin - mark.getMeasuredHeight() + row.getTranslationY();
            final int saved = canvas.save();
            canvas.translate(parent.getPaddingLeft(), top);
            if (row.getAlpha() < 1f)
            {
                canvas.saveLayerAlpha(0f, 0f, mark.getWidth(), mark.getHeight(), Math.round(row.getAlpha() * 255f));
            }
            mark.draw(canvas);
            canvas.restoreToCount(saved);
        }
    }

    /** The words, laid out for the width of the log and the text column of the row they head. */
    private TextView label(final RecyclerView parent, final View row)
    {
        if (label == null)
        {
            label = (TextView) LayoutInflater.from(parent.getContext()).inflate(R.layout.air_unread_mark, parent, false);
        }
        int width = parent.getWidth();
        if (width <= 0)
        {
            width = parent.getMeasuredWidth();
        }
        width -= parent.getPaddingLeft() + parent.getPaddingRight();
        if (width <= 0)
        {
            return null;
        }
        final int start = textStart(row);
        final int direction = parent.getLayoutDirection();
        if ((width != labelWidth) || (start != labelStart) || (direction != labelDirection))
        {
            label.setLayoutDirection(direction);
            label.setPaddingRelative(start, label.getPaddingTop(),
                                     parent.getResources().getDimensionPixelSize(R.dimen.kq_space_16),
                                     label.getPaddingBottom());
            label.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                          View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            label.layout(0, 0, label.getMeasuredWidth(), label.getMeasuredHeight());
            labelWidth = width;
            labelStart = start;
            labelDirection = direction;
        }
        return label;
    }

    /** Where the text of the row starts, as its day mark stands (AirTranscript widens it with the text size). */
    private static int textStart(final View row)
    {
        final View day = row.findViewById(R.id.message_text_date);
        if (day != null)
        {
            return day.getPaddingStart();
        }
        return row.getResources().getDimensionPixelSize(R.dimen.air_entry_text_start);
    }
}
