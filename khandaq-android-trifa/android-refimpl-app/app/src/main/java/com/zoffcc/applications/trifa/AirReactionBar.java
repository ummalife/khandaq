package com.zoffcc.applications.trifa;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (testers' ideas, 0.2.67): the seven quick reactions over a message held down in a chat or a group,
 * while the selection bar is up. A tap puts that reaction on the message, or takes it off when it is already
 * ours, as a pick in the React dialog of the menu does, and ends the selection; React stays in the menu.
 * <p>
 * The bar stands above the message, below it when the message is at the top of the log, and goes with it as
 * the log scrolls. It is there only while one message that can take a reaction is selected: a second message
 * selected takes it away, and ToolbarActionMode asks again after every change of the selection.
 */
final class AirReactionBar
{
    private static final String TAG = "trifa.AirReactionBar";
    static final long NONE = -1L;

    private final Context context;
    private LinearLayout bar = null;
    private RecyclerView list = null;
    private ViewTreeObserver observer = null;
    private long messageId = NONE;
    private boolean group = false;
    private Runnable close = null;
    private int barHeight = 0;
    private int gap = 0;
    private int margin = 0;
    private int target = 0;
    private int padding = 0;

    /** The bar goes with its message: placed again before each frame while it is up. */
    private final ViewTreeObserver.OnPreDrawListener follow = () -> {
        place();
        return true;
    };

    AirReactionBar(final Context context)
    {
        this.context = context;
    }

    /**
     * After a change of the selection: the bar over the one selected message when it can take a reaction
     * ({@code canReact}, what decides React in the menu), no bar otherwise. {@code close} ends the selection.
     */
    void update(final boolean canReact, final Runnable close)
    {
        this.close = close;
        try
        {
            final boolean direct = MainActivity.selected_messages.size() == 1
                                   && MainActivity.selected_group_messages.isEmpty();
            final boolean inGroup = MainActivity.selected_group_messages.size() == 1
                                    && MainActivity.selected_messages.isEmpty();
            final Activity activity = AirSheet.activityOf(context);
            final View found = activity == null ? null : activity.findViewById(R.id.msg_rv_list);
            if (!canReact || !(direct || inGroup) || !(found instanceof RecyclerView)
                || !(found.getParent() instanceof RelativeLayout)
                || !logOf(((RecyclerView) found).getAdapter(), inGroup))
            {
                dismiss();
                return;
            }
            final long id = inGroup ? MainActivity.selected_group_messages.get(0)
                                    : MainActivity.selected_messages.get(0);
            if (bar == null || list != found || messageId != id || group != inGroup)
            {
                show((RecyclerView) found, id, inGroup);
            }
            place();
        }
        catch (Exception e)
        {
            Log.i(TAG, "update:EE:" + e.getMessage());
            dismiss();
        }
    }

    /** The selection ended (or no longer fits the bar): the bar goes. */
    void dismiss()
    {
        try
        {
            final ViewTreeObserver watched = observer;
            if (watched != null && watched.isAlive())
            {
                watched.removeOnPreDrawListener(follow);
            }
            else if (list != null)
            {
                list.getViewTreeObserver().removeOnPreDrawListener(follow);
            }
            if (bar != null && bar.getParent() instanceof ViewGroup)
            {
                ((ViewGroup) bar.getParent()).removeView(bar);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "dismiss:EE:" + e.getMessage());
        }
        observer = null;
        bar = null;
        list = null;
        messageId = NONE;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void show(final RecyclerView log, final long id, final boolean inGroup)
    {
        dismiss();
        final Resources res = context.getResources();
        target = res.getDimensionPixelSize(R.dimen.kq_size_target_android);
        padding = res.getDimensionPixelSize(R.dimen.kq_space_4);
        gap = res.getDimensionPixelSize(R.dimen.kq_space_4);
        margin = res.getDimensionPixelSize(R.dimen.kq_space_8);
        barHeight = target + 2 * padding;
        final String own = HelperMessageReaction.ownEmojiOf(inGroup, id);

        final LinearLayout plate = new LinearLayout(log.getContext());
        plate.setOrientation(LinearLayout.HORIZONTAL);
        plate.setGravity(Gravity.CENTER_VERTICAL);
        plate.setBackgroundResource(R.drawable.air_bg_reaction_bar);
        plate.setPadding(padding, padding, padding, padding);
        // A tap on the pill around the emoji is the bar's, not the message's under it.
        plate.setOnTouchListener((v, event) -> true);
        // Above the jump button of the log, which has a little elevation, yet without a shadow: in the design only
        // a toast, a dialog and a sheet cast one.
        plate.setOutlineProvider(null);
        plate.setElevation(res.getDimension(R.dimen.kq_space_4));
        ViewCompat.setAccessibilityPaneTitle(plate, context.getString(R.string.chat_react_action));
        for (final String emoji : HelperMessageReaction.QUICK_REACTIONS)
        {
            final TextView cell = new TextView(plate.getContext());
            cell.setText(emoji);
            // A glyph: it keeps its size while the text grows, as the icons do.
            cell.setTextSize(TypedValue.COMPLEX_UNIT_PX, res.getDimension(R.dimen.air_reaction_bar_emoji));
            cell.setTextColor(AirUi.color(context, R.color.kq_ink));
            cell.setGravity(Gravity.CENTER);
            cell.setMaxLines(1);
            cell.setIncludeFontPadding(false);
            final boolean ours = emoji.equals(own);
            cell.setSelected(ours);
            cell.setBackgroundResource(ours ? R.drawable.air_bg_reaction_own : R.drawable.air_bg_icon_button);
            cell.setClickable(true);
            cell.setFocusable(true);
            AirUi.markAsToggle(cell);
            cell.setOnClickListener(v -> pick(emoji));
            plate.addView(cell, new LinearLayout.LayoutParams(0, target, 1f));
        }

        final RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                width(HelperMessageReaction.QUICK_REACTIONS.length, target, padding, log.getWidth() - 2 * margin),
                barHeight);
        params.addRule(RelativeLayout.ALIGN_PARENT_TOP);
        params.addRule(RelativeLayout.CENTER_HORIZONTAL);
        plate.setVisibility(View.INVISIBLE);
        ((ViewGroup) log.getParent()).addView(plate, params);

        bar = plate;
        list = log;
        messageId = id;
        group = inGroup;
        observer = log.getViewTreeObserver();
        observer.addOnPreDrawListener(follow);
    }

    private void pick(final String emoji)
    {
        final long id = messageId;
        if (id == NONE)
        {
            return;
        }
        HelperMessageReaction.toggleOwnReaction(group, id, emoji);
        final Runnable end = close;
        if (end != null)
        {
            end.run();
        }
    }

    /** Puts the bar over its message, or out of sight while the message is scrolled away. */
    private void place()
    {
        final RecyclerView log = list;
        final LinearLayout plate = bar;
        if (log == null || plate == null)
        {
            return;
        }
        final int wide = width(HelperMessageReaction.QUICK_REACTIONS.length, target, padding,
                               log.getWidth() - 2 * margin);
        final ViewGroup.LayoutParams params = plate.getLayoutParams();
        if (params != null && params.width != wide)
        {
            params.width = wide;
            plate.setLayoutParams(params);
        }
        final View row = rowOf(log, messageId, group);
        final int rowTop = row == null ? 0 : row.getTop() + Math.round(row.getTranslationY());
        final int rowBottom = row == null ? 0 : row.getBottom() + Math.round(row.getTranslationY());
        if (row == null || !shows(rowTop, rowBottom, log.getHeight()))
        {
            if (plate.getVisibility() != View.INVISIBLE)
            {
                plate.setVisibility(View.INVISIBLE);
            }
            return;
        }
        final float y = log.getTop() + top(rowTop, rowBottom, barHeight, gap, log.getHeight());
        if (plate.getTranslationY() != y)
        {
            plate.setTranslationY(y);
        }
        if (plate.getVisibility() != View.VISIBLE)
        {
            plate.setVisibility(View.VISIBLE);
        }
    }

    /** The row of the log showing the message {@code id}, null while it is scrolled away. */
    private static View rowOf(final RecyclerView log, final long id, final boolean inGroup)
    {
        final RecyclerView.Adapter<?> adapter = log.getAdapter();
        for (int i = 0; i < log.getChildCount(); i++)
        {
            final View child = log.getChildAt(i);
            final int position = log.getChildAdapterPosition(child);
            if (position != RecyclerView.NO_POSITION && idAt(adapter, position, inGroup) == id)
            {
                return child;
            }
        }
        return null;
    }

    /** Whether the log is the one of the selection: a conversation's for a 1:1 message, a group's for a group's. */
    private static boolean logOf(final RecyclerView.Adapter<?> adapter, final boolean inGroup)
    {
        return inGroup ? adapter instanceof GroupMessagelistAdapter : adapter instanceof MessagelistAdapter;
    }

    private static long idAt(final RecyclerView.Adapter<?> adapter, final int position, final boolean inGroup)
    {
        if (inGroup && adapter instanceof GroupMessagelistAdapter)
        {
            final com.zoffcc.applications.sorm.GroupMessage m =
                    ((GroupMessagelistAdapter) adapter).getMessageAtPosition(position);
            return m == null ? NONE : m.id;
        }
        if (!inGroup && adapter instanceof MessagelistAdapter)
        {
            final com.zoffcc.applications.sorm.Message m = ((MessagelistAdapter) adapter).getMessageAtPosition(position);
            return m == null ? NONE : m.id;
        }
        return NONE;
    }

    /**
     * Where the top of the bar stands in the log: {@code gap} above the message, {@code gap} below it when there
     * is no room above (the message is at the top of the log), at the top of the log when it fits neither way
     * (the message fills the log).
     */
    static int top(final int rowTop, final int rowBottom, final int barHeight, final int gap, final int logHeight)
    {
        final int above = rowTop - gap - barHeight;
        if (above >= 0)
        {
            return above;
        }
        final int below = rowBottom + gap;
        if (below + barHeight <= logHeight)
        {
            return below;
        }
        return Math.max(0, Math.min(gap, logHeight - barHeight));
    }

    /** Whether some of the message shows in the log, so the bar has something to stand by. */
    static boolean shows(final int rowTop, final int rowBottom, final int logHeight)
    {
        return rowBottom > 0 && rowTop < logHeight && rowBottom > rowTop;
    }

    /**
     * How wide the bar is: a touch target for each emoji and the padding on both sides, or all the room there is
     * when that is less (a narrow phone), the emoji then sharing it evenly.
     */
    static int width(final int count, final int target, final int padding, final int room)
    {
        final int natural = count * target + 2 * padding;
        return room > 0 ? Math.min(natural, room) : natural;
    }
}
