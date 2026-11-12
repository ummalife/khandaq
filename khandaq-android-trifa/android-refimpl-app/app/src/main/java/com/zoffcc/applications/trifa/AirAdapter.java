package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.MetricAffectingSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import org.khandaq.messenger.R;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * KHANDAQ (redesign): the Air list. Rows come from {@link AirModel.ListBuilder}; DiffUtil turns a
 * new snapshot into moves and in-place rebinds, so a person going online slides to "Online now"
 * and nothing blinks (change animations are off).
 */
final class AirAdapter extends ListAdapter<AirModel.Row, RecyclerView.ViewHolder>
{
    interface Listener
    {
        void onOpen(AirModel.Entry entry);

        /** Long press: the row's action menu, anchored to the row. */
        boolean onMenu(View anchor, AirModel.Entry entry);

        void onOpenMessage(AirModel.MessageHit hit);

        void onAccept(AirModel.Request request);

        void onDecline(AirModel.Request request);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_REQUEST = 1;
    private static final int TYPE_ENTRY = 2;
    private static final int TYPE_MESSAGE = 3;

    private static final DiffUtil.ItemCallback<AirModel.Row> DIFF = new DiffUtil.ItemCallback<AirModel.Row>()
    {
        @Override
        public boolean areItemsTheSame(@NonNull final AirModel.Row first, @NonNull final AirModel.Row second)
        {
            return first.id().equals(second.id());
        }

        @Override
        public boolean areContentsTheSame(@NonNull final AirModel.Row first, @NonNull final AirModel.Row second)
        {
            return first.sameContent(second);
        }
    };

    private final Listener listener;
    private Map<String, ChatBubbleUiHelper.AvatarMeta> photos = Collections.emptyMap();
    /** Entries by id: a found message borrows its chat's tile. */
    private Map<String, AirModel.Entry> entries = Collections.emptyMap();
    private String query = "";

    AirAdapter(final Listener listener)
    {
        super(DIFF);
        this.listener = listener;
    }

    /** Call before {@link #submitList}: rows bound for the new list read these. */
    void setContext(final Map<String, ChatBubbleUiHelper.AvatarMeta> photos, final Map<String, AirModel.Entry> entries)
    {
        this.photos = photos;
        this.entries = entries;
    }

    /** The highlighted words in found messages follow the line; rebinds only those rows. */
    void setQuery(final String next)
    {
        final String value = next == null ? "" : next.trim();
        if (value.equals(query))
        {
            return;
        }
        query = value;
        final List<AirModel.Row> rows = getCurrentList();
        for (int i = 0; i < rows.size(); i++)
        {
            if (rows.get(i).type == AirModel.Row.Type.MESSAGE)
            {
                notifyItemChanged(i);
            }
        }
    }

    @Override
    public int getItemViewType(final int position)
    {
        switch (getItem(position).type)
        {
            case HEADER:
                return TYPE_HEADER;
            case REQUEST:
                return TYPE_REQUEST;
            case MESSAGE:
                return TYPE_MESSAGE;
            case ENTRY:
            default:
                return TYPE_ENTRY;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType)
    {
        final LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType)
        {
            case TYPE_HEADER:
                return new HeaderHolder(inflater.inflate(R.layout.air_section_header, parent, false));
            case TYPE_REQUEST:
                return new RequestHolder(inflater.inflate(R.layout.air_request_card, parent, false));
            case TYPE_MESSAGE:
                return new MessageHolder(inflater.inflate(R.layout.air_message_row, parent, false));
            case TYPE_ENTRY:
            default:
                return new EntryHolder(inflater.inflate(R.layout.air_row, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, final int position)
    {
        final AirModel.Row row = getItem(position);
        if (holder instanceof HeaderHolder)
        {
            ((HeaderHolder) holder).bind(row);
        }
        else if (holder instanceof RequestHolder)
        {
            ((RequestHolder) holder).bind(row.item.request);
        }
        else if (holder instanceof MessageHolder)
        {
            ((MessageHolder) holder).bind(row.item.message);
        }
        else if (holder instanceof EntryHolder)
        {
            ((EntryHolder) holder).bind(row.item.entry);
        }
    }

    @Override
    public void onViewRecycled(@NonNull final RecyclerView.ViewHolder holder)
    {
        super.onViewRecycled(holder);
        // A recycled row must not keep a photo load aimed at it.
        if (holder instanceof EntryHolder)
        {
            clearPhoto(((EntryHolder) holder).tile);
        }
        else if (holder instanceof MessageHolder)
        {
            clearPhoto(((MessageHolder) holder).tile);
        }
        holder.itemView.animate().cancel();
        holder.itemView.setAlpha(1f);
        holder.itemView.setTranslationY(0f);
    }

    private void bindTile(final AirTileView tile, final AirModel.Entry entry)
    {
        tile.bind(AirUi.tileKind(entry), entry.title, entry.tone);
        final ChatBubbleUiHelper.AvatarMeta photo = entry.photoKey == null ? null : photos.get(entry.photoKey);
        if (photo != null && photo.loadable)
        {
            tile.showPhoto(true);
            ChatBubbleUiHelper.load_peer_photo(tile.getContext(), photo, tile.photoView());
        }
        else
        {
            clearPhoto(tile);
        }
    }

    private static void clearPhoto(final AirTileView tile)
    {
        try
        {
            GlideApp.with(tile.getContext()).clear(tile.photoView());
        }
        catch (Throwable ignored)
        {
            // The activity is going away; nothing to clear.
        }
        tile.photoView().setImageDrawable(null);
        tile.showPhoto(false);
    }

    private static void setMarginStart(final View view, final int px)
    {
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.getMarginStart() != px)
        {
            params.setMarginStart(px);
            view.setLayoutParams(params);
        }
    }

    /** A plain span that switches the face only, so it works on API 26 with font resources. */
    static final class FaceSpan extends MetricAffectingSpan
    {
        private final Typeface face;

        FaceSpan(final Typeface face)
        {
            this.face = face;
        }

        @Override
        public void updateDrawState(final TextPaint paint)
        {
            apply(paint);
        }

        @Override
        public void updateMeasureState(@NonNull final TextPaint paint)
        {
            apply(paint);
        }

        private void apply(final Paint paint)
        {
            if (face != null)
            {
                paint.setTypeface(face);
            }
        }
    }

    static Typeface strongFace(final Context context)
    {
        final boolean arabic = "ar".equals(AirUi.locale(context).getLanguage());
        return ResourcesCompat.getFont(context, arabic ? R.font.ibm_plex_sans_arabic_semibold : R.font.ibm_plex_sans_semibold);
    }

    // ---- holders ----

    final class HeaderHolder extends RecyclerView.ViewHolder
    {
        final TextView title;
        final AirRollingLabel count;
        private AirModel.SectionKind section;

        HeaderHolder(final View view)
        {
            super(view);
            title = view.findViewById(R.id.air_header_title);
            count = view.findViewById(R.id.air_header_count);
            count.configure(R.style.TextAppearance_KQ_Label, AirUi.color(view.getContext(), R.color.kq_ink_3));
        }

        void bind(final AirModel.Row row)
        {
            final Context context = itemView.getContext();
            final boolean sameSection = row.section == section;
            section = row.section;
            title.setText(sectionTitle(row.section));
            // The number rolls only when it changes under the same heading, not when a holder is reused.
            count.setCount(row.count, AirUi.countText(context, row.count), sameSection);
            itemView.setContentDescription(title.getText() + ", " + AirUi.countText(context, row.count));
        }

        private int sectionTitle(final AirModel.SectionKind kind)
        {
            switch (kind)
            {
                case LIVE:
                    return R.string.air_section_live;
                case AWAY:
                    return R.string.air_section_away;
                case SAVED:
                    return R.string.air_section_saved;
                case MESSAGES:
                    return R.string.air_section_messages;
                case REQUESTS:
                default:
                    return R.string.air_filter_requests;
            }
        }
    }

    final class RequestHolder extends RecyclerView.ViewHolder
    {
        final TextView tag;
        final TextView time;
        final TextView message;
        final TextView key;
        final TextView accept;
        final TextView decline;
        private AirModel.Request request;

        RequestHolder(final View view)
        {
            super(view);
            tag = view.findViewById(R.id.air_request_tag);
            time = view.findViewById(R.id.air_request_time);
            message = view.findViewById(R.id.air_request_message);
            key = view.findViewById(R.id.air_request_key);
            accept = view.findViewById(R.id.air_request_accept);
            decline = view.findViewById(R.id.air_request_decline);
            AirUi.markAsButton(accept);
            AirUi.markAsButton(decline);
            accept.setOnClickListener(v -> {
                if (request != null)
                {
                    listener.onAccept(request);
                }
            });
            decline.setOnClickListener(v -> {
                if (request != null)
                {
                    listener.onDecline(request);
                }
            });
        }

        void bind(final AirModel.Request next)
        {
            request = next;
            final Context context = itemView.getContext();
            time.setText(next.timeText);
            time.setVisibility(next.timeText.isEmpty() ? View.GONE : View.VISIBLE);
            if (next.kind == AirModel.Request.Kind.GROUP_INVITE)
            {
                bindInvite(context, next);
                return;
            }
            tag.setText(R.string.air_request_tag);
            accept.setText(R.string.friend_request_accept);
            TextViewCompat.setTextAppearance(message, R.style.TextAppearance_KQ_Body);
            message.setTextColor(AirUi.color(context, R.color.kq_ink));
            message.setText(next.message);
            message.setVisibility(next.message.isEmpty() ? View.GONE : View.VISIBLE);

            final String grouped = AirModel.groupedKey(next.publicKey);
            final String line = context.getString(R.string.air_request_key_format, grouped);
            final SpannableString styled = new SpannableString(line);
            final int at = line.indexOf(grouped);
            if (at >= 0 && !grouped.isEmpty())
            {
                final int end = at + grouped.length();
                styled.setSpan(new FaceSpan(ResourcesCompat.getFont(context, R.font.ibm_plex_mono_medium)), at, end,
                               Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new ForegroundColorSpan(AirUi.color(context, R.color.kq_ink_2)), at, end,
                               Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            key.setText(styled);
            // TalkBack reads the key as one string, without the visual grouping.
            key.setContentDescription(context.getString(R.string.air_request_key_format,
                                                        grouped.replace(" ", "")));
        }

        /**
         * An invitation names the group and the contact who invites; while they are offline it says that
         * joining waits for them, since toxcore joins through them (E12).
         */
        private void bindInvite(final Context context, final AirModel.Request next)
        {
            tag.setText(R.string.air_invite_tag);
            accept.setText(R.string.air_invite_join);
            TextViewCompat.setTextAppearance(message, R.style.TextAppearance_KQ_Air_BodyStrong);
            message.setTextColor(AirUi.color(context, R.color.kq_ink));
            message.setText(next.groupName);
            message.setVisibility(next.groupName.isEmpty() ? View.GONE : View.VISIBLE);

            String line = context.getString(R.string.air_invite_from_format, next.inviterName);
            if (!next.inviterOnline)
            {
                line = line + " " + context.getString(R.string.air_invite_wait_format, next.inviterName);
            }
            final SpannableString styled = new SpannableString(line);
            final String name = next.inviterName;
            int at = name.isEmpty() ? -1 : line.indexOf(name);
            while (at >= 0)
            {
                styled.setSpan(new ForegroundColorSpan(AirUi.color(context, R.color.kq_ink_2)), at, at + name.length(),
                               Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                at = line.indexOf(name, at + name.length());
            }
            key.setText(styled);
            key.setContentDescription(line);
        }
    }

    final class EntryHolder extends RecyclerView.ViewHolder
    {
        final AirTileView tile;
        final TextView name;
        final View reach;
        final View reachDot;
        final TextView reachText;
        final TextView time;
        final ImageView delivery;
        final TextView preview;
        final View transit;
        final TextView transitText;
        final TextView unread;
        private AirModel.Entry entry;

        EntryHolder(final View view)
        {
            super(view);
            tile = view.findViewById(R.id.air_row_tile);
            name = view.findViewById(R.id.air_row_name);
            reach = view.findViewById(R.id.air_row_reach);
            reachDot = view.findViewById(R.id.air_row_reach_dot);
            reachText = view.findViewById(R.id.air_row_reach_text);
            time = view.findViewById(R.id.air_row_time);
            delivery = view.findViewById(R.id.air_row_delivery);
            preview = view.findViewById(R.id.air_row_preview);
            transit = view.findViewById(R.id.air_row_transit);
            transitText = view.findViewById(R.id.air_row_transit_text);
            unread = view.findViewById(R.id.air_row_unread);
            time.setFontFeatureSettings("tnum");
            transitText.setFontFeatureSettings("tnum");
            unread.setFontFeatureSettings("tnum");
            view.setOnClickListener(v -> {
                if (entry != null)
                {
                    listener.onOpen(entry);
                }
            });
            view.setOnLongClickListener(v -> entry != null && listener.onMenu(v, entry));
        }

        void bind(final AirModel.Entry next)
        {
            entry = next;
            final Context context = itemView.getContext();
            bindTile(tile, next);
            name.setText(next.title);

            final String reachWords = AirUi.reachText(context, next.reach);
            if (reachWords.isEmpty())
            {
                reach.setVisibility(View.GONE);
            }
            else
            {
                reach.setVisibility(View.VISIBLE);
                reachText.setText(reachWords);
                final boolean live = next.reach.isLive();
                reachDot.setVisibility(live ? View.VISIBLE : View.GONE);
                reachText.setTextColor(AirUi.color(context, live ? R.color.kq_live : R.color.kq_ink_3));
            }

            final boolean hasTime = !next.timeText.isEmpty();
            time.setText(next.timeText);
            time.setVisibility(hasTime ? View.VISIBLE : View.GONE);

            final boolean hasDelivery = next.lastDelivery != null;
            if (hasDelivery)
            {
                delivery.setVisibility(View.VISIBLE);
                final int icon;
                final int tint;
                switch (next.lastDelivery)
                {
                    case TRANSIT:
                        icon = R.drawable.kq_ic_clock;
                        tint = R.color.kq_transit;
                        break;
                    case DELIVERED:
                        icon = R.drawable.kq_ic_check_double;
                        tint = R.color.kq_ink;
                        break;
                    case SENT:
                    default:
                        icon = R.drawable.kq_ic_check;
                        tint = R.color.kq_ink_3;
                        break;
                }
                delivery.setImageResource(icon);
                ImageViewCompat.setImageTintList(delivery, ColorStateList.valueOf(AirUi.color(context, tint)));
            }
            else
            {
                delivery.setVisibility(View.GONE);
            }
            final int space4 = context.getResources().getDimensionPixelSize(R.dimen.kq_space_4);
            final int space8 = context.getResources().getDimensionPixelSize(R.dimen.kq_space_8);
            setMarginStart(delivery, hasTime ? space8 : 0);
            setMarginStart(preview, hasDelivery ? space4 : hasTime ? space8 : 0);

            preview.setText(next.preview);
            preview.setVisibility(next.preview.isEmpty() ? View.GONE : View.VISIBLE);
            preview.setTextColor(AirUi.color(context, next.previewStyle == AirModel.PreviewStyle.HINT
                                                      ? R.color.kq_ink_3 : R.color.kq_ink_2));

            if (next.transitCount > 0)
            {
                transit.setVisibility(View.VISIBLE);
                transitText.setText(AirUi.countText(context, next.transitCount));
            }
            else
            {
                transit.setVisibility(View.GONE);
            }
            if (next.unreadCount > 0)
            {
                unread.setVisibility(View.VISIBLE);
                unread.setText(AirUi.countText(context, next.unreadCount));
            }
            else
            {
                unread.setVisibility(View.GONE);
            }

            itemView.setContentDescription(AirUi.rowDescription(context, next));
        }
    }

    final class MessageHolder extends RecyclerView.ViewHolder
    {
        final AirTileView tile;
        final TextView title;
        final TextView time;
        final TextView snippet;
        private AirModel.MessageHit hit;

        MessageHolder(final View view)
        {
            super(view);
            tile = view.findViewById(R.id.air_message_tile);
            title = view.findViewById(R.id.air_message_title);
            time = view.findViewById(R.id.air_message_time);
            snippet = view.findViewById(R.id.air_message_snippet);
            time.setFontFeatureSettings("tnum");
            view.setOnClickListener(v -> {
                if (hit != null)
                {
                    listener.onOpenMessage(hit);
                }
            });
        }

        void bind(final AirModel.MessageHit next)
        {
            hit = next;
            final Context context = itemView.getContext();
            final AirModel.Entry chat = entries.get(next.chatId);
            if (chat != null)
            {
                bindTile(tile, chat);
            }
            else
            {
                tile.bind(AirTileView.Kind.PERSON, next.title, AirModel.Tone.index(next.chatId));
                clearPhoto(tile);
            }
            title.setText(next.title);
            time.setText(next.timeText);
            time.setVisibility(next.timeText.isEmpty() ? View.GONE : View.VISIBLE);

            final SpannableString styled = new SpannableString(next.snippet);
            final Typeface strong = strongFace(context);
            final int ink = AirUi.color(context, R.color.kq_ink);
            for (final int[] range : AirModel.matchRanges(next.snippet, query))
            {
                styled.setSpan(new FaceSpan(strong), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new ForegroundColorSpan(ink), range[0], range[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            snippet.setText(styled);
            itemView.setContentDescription(next.title + ", " + next.snippet
                                           + (next.timeText.isEmpty() ? "" : ", " + next.timeText));
        }
    }

    // ---- the empty state under the list ----

    interface EmptyListener
    {
        void onEmptyAction(AirModel.EmptyState state);
    }

    /** One row under the list when the filter or the line has nothing; saved messages can stay above it. */
    static final class EmptyFooter extends RecyclerView.Adapter<EmptyFooter.Holder>
    {
        private final EmptyListener listener;
        private AirModel.EmptyState state;

        EmptyFooter(final EmptyListener listener)
        {
            this.listener = listener;
        }

        void setState(final AirModel.EmptyState next)
        {
            if (next == state)
            {
                return;
            }
            final AirModel.EmptyState previous = state;
            state = next;
            if (previous == null)
            {
                notifyItemInserted(0);
            }
            else if (next == null)
            {
                notifyItemRemoved(0);
            }
            else
            {
                notifyItemChanged(0);
            }
        }

        AirModel.EmptyState state()
        {
            return state;
        }

        @Override
        public int getItemCount()
        {
            return state == null ? 0 : 1;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType)
        {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.air_empty, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, final int position)
        {
            holder.bind(state);
        }

        final class Holder extends RecyclerView.ViewHolder
        {
            final TextView title;
            final TextView body;
            final TextView action;

            Holder(final View view)
            {
                super(view);
                title = view.findViewById(R.id.air_empty_title);
                body = view.findViewById(R.id.air_empty_body);
                action = view.findViewById(R.id.air_empty_action);
                AirUi.markAsButton(action);
                action.setOnClickListener(v -> {
                    if (state != null)
                    {
                        listener.onEmptyAction(state);
                    }
                });
            }

            void bind(final AirModel.EmptyState next)
            {
                switch (next)
                {
                    case NOTHING_YET:
                        title.setText(R.string.air_empty_nothing_title);
                        body.setText(R.string.air_empty_nothing_body);
                        action.setText(R.string.air_new_contact);
                        break;
                    case NO_MATCH:
                        title.setText(R.string.air_empty_no_match_title);
                        body.setText(R.string.air_empty_no_match_body);
                        action.setText(R.string.cancel);
                        break;
                    case NO_REQUESTS:
                        title.setText(R.string.air_empty_no_requests_title);
                        body.setText(R.string.air_empty_no_requests_body);
                        action.setText(R.string.air_empty_show_all);
                        break;
                    case NO_TRANSIT:
                        title.setText(R.string.air_empty_no_transit_title);
                        body.setText(R.string.air_empty_no_transit_body);
                        action.setText(R.string.air_empty_show_all);
                        break;
                    case NO_FAVORITES:
                    default:
                        title.setText(R.string.air_empty_no_favorites_title);
                        body.setText(R.string.air_empty_no_favorites_body);
                        action.setText(R.string.air_empty_show_all);
                        break;
                }
            }
        }
    }

    /** Callback of the row "New contact with this ID". */
    interface CommandIdListener
    {
        void onCommandIdRow(String hex);
    }

    /**
     * The row "New contact with this ID" over the list (E3), there while the command line holds the start
     * of an ID: 12 to 75 hex characters with a digit. Its note says what is missing or wrong so far.
     */
    static final class CommandIdRow extends RecyclerView.Adapter<CommandIdRow.Holder>
    {
        private final CommandIdListener listener;
        private String hex;
        private String note = "";

        CommandIdRow(final CommandIdListener listener)
        {
            this.listener = listener;
        }

        /** Shows the row for this start of an ID with its note, or hides it for null. */
        void show(final String nextHex, final String nextNote)
        {
            final boolean was = hex != null;
            final String text = nextNote == null ? "" : nextNote;
            if (was && nextHex != null && nextHex.equals(hex) && text.equals(note))
            {
                return;
            }
            hex = nextHex;
            note = text;
            if (was && nextHex == null)
            {
                notifyItemRemoved(0);
            }
            else if (!was && nextHex != null)
            {
                notifyItemInserted(0);
            }
            else if (nextHex != null)
            {
                notifyItemChanged(0);
            }
        }

        boolean shown()
        {
            return hex != null;
        }

        @Override
        public int getItemCount()
        {
            return hex == null ? 0 : 1;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType)
        {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.air_command_id_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, final int position)
        {
            holder.note.setText(note);
            holder.itemView.setContentDescription(
                    holder.itemView.getContext().getString(R.string.air_cmd_new_contact) + ", " + note);
        }

        final class Holder extends RecyclerView.ViewHolder
        {
            final TextView note;

            Holder(final View view)
            {
                super(view);
                note = view.findViewById(R.id.air_command_id_note);
                final AirTileView tile = view.findViewById(R.id.air_command_id_tile);
                tile.bind(AirTileView.Kind.PENDING, "", 0);
                AirUi.markAsButton(view);
                view.setOnClickListener(v -> {
                    if (hex != null)
                    {
                        listener.onCommandIdRow(hex);
                    }
                });
            }
        }
    }
}
