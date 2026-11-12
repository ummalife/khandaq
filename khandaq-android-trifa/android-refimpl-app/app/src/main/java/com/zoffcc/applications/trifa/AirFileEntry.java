package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.widget.ImageViewCompat;

import com.zoffcc.applications.sorm.FileDB;
import com.zoffcc.applications.sorm.Filetransfer;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/**
 * KHANDAQ (redesign, stage 3b): a file, a photo, a video or a voice note as an entry of the transcript
 * (design/DESIGN.md, "Files, voice and calls on the phone"), the same row as a text: the time in its column,
 * the author over the first entry of a run, the glyph, the name and the size, the 6dp bar while it moves,
 * one line of state in words and the actions in words under it; ours take the mark at the far end. A photo
 * or a video stands in a box of its own shape before its picture is there, the caption under it; a voice
 * note is its player.
 *
 * The row holders and the transfer helpers keep their work: the state is read from the message and its
 * transfer as they are, and every action calls what the old buttons called. This class only draws, after
 * them: {@link #bindDirect} and {@link #bindGroup} once the adapter has bound a row, and the state again on
 * every progress tick ({@link #applyDirectState}, {@link #applyGroupState}).
 */
final class AirFileEntry
{
    enum State
    {
        WAITING,
        MOVING,
        PAUSED,
        FAILED,
        DONE
    }

    /** Accept and Decline of a file of theirs that waits for us: the waiting holder's own work. */
    interface AcceptActions
    {
        void accept();

        void decline(View anchor);
    }

    /** "Save to the phone" and, once saved, Share of a finished file of theirs: the holder's own work. */
    interface CopyActions
    {
        void save(View anchor);

        boolean canShare();

        void share(View anchor);
    }

    private static final float PHOTO_ASPECT = 4f / 3f;
    private static final float VIDEO_ASPECT = 16f / 9f;
    private static final float MIN_ASPECT = 3f / 4f;
    private static final float MAX_ASPECT = 16f / 9f;
    private static final int BAR_MAX = 1000;
    /** From this text size on, the actions stand one under the other so that each keeps its words. */
    private static final float STACKED_ACTIONS_FONT_SCALE = 1.3f;

    private static final int[] LEGACY = {R.id.ft_buttons_container, R.id.ft_progressbar,
                                         R.id.ft_export_button_container, R.id.m_status, R.id.img_avatar,
                                         R.id.img_corner, R.id.m_text, R.id.layout_peer_name_container,
                                         R.id.ft_transfer_overlay, R.id.ft_media_info_bar, R.id.ft_file_error,
                                         R.id.ft_file_cancel, R.id.ft_file_retry};

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** The shape of a picture by its path, read once from its header: width over height as shown. */
    private static final LruCache<String, Float> ASPECTS = new LruCache<>(256);
    private static final Set<String> READING = ConcurrentHashMap.newKeySet();
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(work -> {
        final Thread thread = new Thread(work, "air-file-aspect");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private static final ViewOutlineProvider ROUNDED = new ViewOutlineProvider()
    {
        @Override
        public void getOutline(final View view, final Outline outline)
        {
            outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                                 view.getResources().getDimension(R.dimen.kq_radius_card));
        }
    };

    private AirFileEntry()
    {
    }

    static boolean isEntry(@Nullable final View itemRoot)
    {
        return itemRoot != null && itemRoot.findViewById(R.id.air_file_body) != null;
    }

    static void setAcceptActions(@Nullable final View itemRoot, @Nullable final AcceptActions actions)
    {
        if (itemRoot != null)
        {
            itemRoot.setTag(R.id.air_file_accept_actions, actions);
        }
    }

    static void setCopyActions(@Nullable final View itemRoot, @Nullable final CopyActions actions)
    {
        if (itemRoot != null)
        {
            itemRoot.setTag(R.id.air_file_copy_actions, actions);
        }
    }

    /** Declining a file of theirs asks from below first, with the verb on the button. */
    static void askDecline(final View anchor, final Runnable decline)
    {
        final Context context = anchor.getContext();
        AirQuestion.ask(context, context.getString(R.string.air_file_decline_title),
                        context.getString(R.string.air_file_decline_message),
                        context.getString(R.string.air_file_decline), true, decline);
    }

    /** A copy outside the app is not encrypted: it is said before the copy is made. */
    static void askExport(final View anchor, final Runnable export)
    {
        final Context context = anchor.getContext();
        AirQuestion.ask(context, context.getString(R.string.air_file_export_title),
                        context.getString(R.string.air_file_export_message),
                        context.getString(R.string.air_file_export_action), false, export);
    }

    // ---- a conversation with one person ----

    /** After the row holder and the caption: the frame of the entry, then its state. */
    static void bindDirect(@Nullable final View itemRoot, @Nullable final Message m, final int position)
    {
        if (!isEntry(itemRoot) || m == null)
        {
            return;
        }
        final Context context = itemRoot.getContext();
        final boolean outgoing = m.direction == 1;
        String full = "";
        int color = AirUi.color(context, R.color.kq_ink_2);
        if (!FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey))
        {
            if (outgoing)
            {
                full = context.getString(R.string.air_you);
            }
            else
            {
                full = personName(m);
                color = AirUi.toneColor(context, AirModel.Tone.index(m.tox_friendpubkey));
            }
        }
        final String shown = (full.isEmpty() || !AirTranscript.startsDirectRun(context, m, position)) ? null
                             : (outgoing ? full : AirConversationModel.shortName(full));
        frame(new Views(itemRoot), AirTranscript.timeText(context, m, outgoing), full, shown, color);
        applyDirectState(context, itemRoot, m, outgoing);
    }

    /** The state in words, read from the message and its transfer; also on every progress tick. */
    static void applyDirectState(@Nullable final Context context, @Nullable final View itemRoot,
                                 @Nullable final Message m, final boolean outgoing)
    {
        if (context == null || !isEntry(itemRoot) || m == null)
        {
            return;
        }
        final Entry e = new Entry();
        e.outgoing = outgoing;
        e.voice = HelperFiletransfer.isAudioMessage(context, m);
        final String mime = HelperFiletransfer.guess_message_file_mime_type(context, m);
        e.video = mime != null && mime.startsWith("video/");
        e.picture = e.video || (mime != null && mime.startsWith("image/"));
        e.name = ChatMediaHelper.messageMediaDisplayLabel(context, m);
        e.path = m.filename_fullpath;
        e.state = directState(m, outgoing, HelperFiletransfer.is_message_file_complete(m));
        e.total = ChatFileBubbleHelper.resolveMessageFileSize(m);
        if (e.total <= 0)
        {
            // A finished file lives in the encrypted store, where java.io.File cannot see it; its row
            // in the file table still holds the size.
            e.total = storedSize(m);
        }
        if (e.state == State.MOVING || e.state == State.PAUSED)
        {
            final Filetransfer ft = transferOf(m);
            if (ft != null)
            {
                if (ft.filesize > 0)
                {
                    e.total = ft.filesize;
                }
                e.done = Math.max(0L, Math.min(ft.current_position, e.total));
            }
        }

        final boolean online = HelperFiletransfer.is_dm_sender_online(m.tox_friendpubkey);
        final ChatTransferProgressHelper.Callbacks calls = ChatTransferProgressHelper.directCallbacks(context, itemRoot,
                                                                                                     m, outgoing);
        switch (e.state)
        {
            case WAITING:
                if (outgoing)
                {
                    final String name = AirConversationModel.shortName(personName(m));
                    e.note = context.getString(online ? R.string.air_file_waits_accept_format
                                                      : R.string.air_file_waits_online_format, name);
                    e.cancel = view -> calls.onCancel();
                }
                else
                {
                    final Object tag = itemRoot.getTag(R.id.air_file_accept_actions);
                    final AcceptActions actions = tag instanceof AcceptActions ? (AcceptActions) tag : null;
                    e.accept = view -> {
                        if (actions != null)
                        {
                            actions.accept();
                        }
                        else
                        {
                            calls.onRetry();
                        }
                    };
                    e.decline = view -> {
                        if (actions != null)
                        {
                            actions.decline(view);
                        }
                        else
                        {
                            askDecline(view, calls::onCancel);
                        }
                    };
                }
                break;
            case MOVING:
                if (!outgoing && !online)
                {
                    e.note = context.getString(R.string.file_transfer_sender_offline);
                }
                else
                {
                    sayLeft(context, e);
                }
                e.cancel = view -> calls.onCancel();
                break;
            case PAUSED:
                e.note = context.getString(R.string.air_file_paused);
                e.bar = e.total > 0;
                e.cancel = view -> calls.onCancel();
                break;
            case FAILED:
                if (!outgoing && !online)
                {
                    e.note = context.getString(R.string.file_transfer_sender_offline);
                }
                else
                {
                    sayFailed(context, e);
                    e.retry = view -> calls.onRetry();
                }
                break;
            case DONE:
            default:
                offerCopy(itemRoot, e);
                break;
        }
        e.status = (!outgoing || FavoritesChatHelper.isFavoritesChat(m.tox_friendpubkey))
                   ? AirConversationModel.EntryStatus.NONE : directStatus(e.state, online);
        render(context, new Views(itemRoot), e);
    }

    /**
     * A finished transfer is a cancelled one with the whole file: ours once their side holds it all
     * (filedb_id), theirs once it is complete here. A paused one not accepted yet waits for an answer.
     */
    private static State directState(final Message m, final boolean outgoing, final boolean complete)
    {
        if (m.state == TOX_FILE_CONTROL_CANCEL.value)
        {
            if (outgoing)
            {
                return m.filedb_id != -1 ? State.DONE : State.FAILED;
            }
            return complete ? State.DONE : State.FAILED;
        }
        if (m.state == TOX_FILE_CONTROL_PAUSE.value)
        {
            return m.ft_accepted ? State.PAUSED : State.WAITING;
        }
        if (!outgoing && complete && m.filedb_id != -1)
        {
            return State.DONE;
        }
        return State.MOVING;
    }

    private static AirConversationModel.EntryStatus directStatus(final State state, final boolean online)
    {
        switch (state)
        {
            case DONE:
                return AirConversationModel.EntryStatus.DELIVERED;
            case WAITING:
                return online ? AirConversationModel.EntryStatus.SENDING : AirConversationModel.EntryStatus.TRANSIT;
            case FAILED:
                return AirConversationModel.EntryStatus.FAILED;
            case MOVING:
            case PAUSED:
            default:
                return AirConversationModel.EntryStatus.SENDING;
        }
    }

    // ---- a group ----

    /**
     * After the row holder and the caption: the frame of a group's file entry. The author is the name the
     * holder settled on (it keeps the rules on relayed history), with the founder's or a moderator's role.
     */
    static void bindGroup(@Nullable final View itemRoot, @Nullable final GroupMessage m, final int position)
    {
        if (!isEntry(itemRoot) || m == null)
        {
            return;
        }
        final Context context = itemRoot.getContext();
        final boolean outgoing = m.direction == 1;
        final Views views = new Views(itemRoot);
        String full;
        int color = AirUi.color(context, R.color.kq_ink_2);
        if (outgoing)
        {
            full = context.getString(R.string.air_you);
        }
        else
        {
            final CharSequence named = views.peerName == null ? null : views.peerName.getText();
            final String name = named == null ? "" : named.toString().trim();
            full = name;
            if (!name.isEmpty() && !name.equals(context.getString(R.string.group_msg_relayed_unattributed)))
            {
                final int role = authorRole(m);
                if (role == AirGroupModel.ROLE_FOUNDER || role == AirGroupModel.ROLE_MODERATOR)
                {
                    full = context.getString(R.string.air_group_author_role_format, name,
                                             AirGroupPanel.roleName(context, role));
                }
                final String key = m.tox_group_peer_pubkey;
                color = AirUi.toneColor(context, AirModel.Tone.index(TextUtils.isEmpty(key) ? name : key));
            }
        }
        final String shown = (full.isEmpty() || !AirTranscript.startsGroupRunFor(m, position)) ? null : full;
        frame(views, AirUi.localDigits(context, HelperGeneric.format_group_message_time(m, outgoing)), full, shown,
              color);
        // The state came with the transfer helper during this bind; drawn again over what the frame reset.
        final Object stamp = itemRoot.getTag(R.id.air_file_group_snapshot);
        if (stamp instanceof GroupStamp && ((GroupStamp) stamp).messageId == m.id)
        {
            applyGroupState(context, itemRoot, m, outgoing, ((GroupStamp) stamp).snap);
        }
    }

    /** The state of a group's file in words, from the transfer helper's reading of it. */
    static void applyGroupState(@Nullable final Context context, @Nullable final View itemRoot,
                                @Nullable final GroupMessage m, final boolean outgoing,
                                @Nullable final ChatTransferProgressHelper.Snapshot snap)
    {
        if (context == null || !isEntry(itemRoot) || m == null || snap == null)
        {
            return;
        }
        itemRoot.setTag(R.id.air_file_group_snapshot, new GroupStamp(m.id, snap));
        final Entry e = new Entry();
        e.outgoing = outgoing;
        e.voice = HelperFiletransfer.isGroupAudioMessage(context, m);
        e.video = HelperFiletransfer.isGroupVideoMessage(context, m);
        e.picture = e.video || HelperFiletransfer.isGroupImageMessage(context, m);
        e.name = ChatMediaHelper.groupMessageMediaDisplayLabel(context, m);
        e.path = m.filename_fullpath;
        e.total = snap.bytesTotal > 0 ? snap.bytesTotal : Math.max(0L, m.filesize);
        e.done = Math.max(0L, Math.min(snap.bytesDone, e.total));
        final ChatTransferProgressHelper.Callbacks calls = ChatTransferProgressHelper.groupCallbacks(context, m,
                                                                                                    outgoing);
        switch (snap.phase)
        {
            case PENDING:
                e.state = State.WAITING;
                e.note = context.getString(outgoing ? R.string.air_file_waits_group
                                                    : R.string.file_transfer_sender_offline);
                if (outgoing)
                {
                    e.cancel = view -> calls.onCancel();
                }
                break;
            case TRANSFERRING:
                e.state = State.MOVING;
                sayLeft(context, e);
                if (outgoing)
                {
                    e.cancel = view -> calls.onCancel();
                }
                break;
            case FAILED:
                if (!outgoing && e.done <= 0L
                    && !HelperGroup.ngc_incoming_download_allowed(m.group_identifier, m.msg_id_hash))
                {
                    // Automatic download is off: the file waits until it is accepted.
                    e.state = State.WAITING;
                    e.accept = view -> calls.onRetry();
                }
                else
                {
                    e.state = State.FAILED;
                    sayFailed(context, e);
                    e.retry = view -> calls.onRetry();
                }
                break;
            case COMPLETE:
            case IDLE:
            default:
                e.state = State.DONE;
                offerCopy(itemRoot, e);
                break;
        }
        e.status = outgoing ? groupStatus(e.state) : AirConversationModel.EntryStatus.NONE;
        render(context, new Views(itemRoot), e);
    }

    /** Ours in a group: the transit mark while the group is away, a clock while it leaves, one check after. */
    private static AirConversationModel.EntryStatus groupStatus(final State state)
    {
        switch (state)
        {
            case WAITING:
                return AirConversationModel.EntryStatus.TRANSIT;
            case FAILED:
                return AirConversationModel.EntryStatus.FAILED;
            case DONE:
                return AirConversationModel.EntryStatus.SENT;
            case MOVING:
            case PAUSED:
            default:
                return AirConversationModel.EntryStatus.SENDING;
        }
    }

    private static int authorRole(final GroupMessage m)
    {
        try
        {
            final long groupNum = HelperGroup.tox_group_by_groupid__wrapper(m.group_identifier);
            if (groupNum >= 0 && m.tox_group_peer_pubkey != null)
            {
                final long peerId = MainActivity.tox_group_peer_by_public_key(groupNum, m.tox_group_peer_pubkey);
                if (peerId >= 0)
                {
                    final int role = MainActivity.tox_group_peer_get_role(groupNum, peerId);
                    if (AirGroupModel.isKnownRole(role))
                    {
                        return role;
                    }
                }
            }
        }
        catch (Throwable ignored)
        {
            // Not in the peer list now: the stored role below.
        }
        return AirGroupModel.isKnownRole(m.tox_group_peer_role) ? m.tox_group_peer_role : -1;
    }

    // ---- words ----

    private static void sayLeft(final Context context, final Entry e)
    {
        if (e.total > 0)
        {
            e.note = context.getString(R.string.air_file_left_format,
                                       AirByteSize.text(context, Math.max(0L, e.total - e.done)));
            e.bar = true;
        }
    }

    private static void sayFailed(final Context context, final Entry e)
    {
        e.note = context.getString(e.outgoing ? R.string.air_file_failed_outgoing : R.string.air_file_failed_incoming);
        e.noteDanger = true;
    }

    /** A finished file of theirs keeps its old Save and Share; a voice note is only listened to. */
    private static void offerCopy(final View itemRoot, final Entry e)
    {
        if (e.outgoing || e.voice)
        {
            return;
        }
        final Object tag = itemRoot.getTag(R.id.air_file_copy_actions);
        if (tag instanceof CopyActions)
        {
            final CopyActions copy = (CopyActions) tag;
            e.save = copy::save;
            if (copy.canShare())
            {
                e.share = copy::share;
            }
        }
    }

    private static String personName(final Message m)
    {
        final String key = AirTranscript.personKey;
        final String name = AirTranscript.personName;
        if (key != null && key.equals(m.tox_friendpubkey) && !TextUtils.isEmpty(name))
        {
            return name;
        }
        try
        {
            final String found = HelperFriend.get_friend_name_from_pubkey(m.tox_friendpubkey);
            return found == null ? "" : found;
        }
        catch (Exception e)
        {
            return "";
        }
    }

    @Nullable
    /** The size the file table recorded for a finished transfer, or 0. */
    private static long storedSize(final Message m)
    {
        if (m.filedb_id <= 0)
        {
            return 0L;
        }
        try
        {
            final FileDB file = orma.selectFromFileDB().idEq(m.filedb_id).get(0);
            return file == null ? 0L : Math.max(0L, file.filesize);
        }
        catch (Exception e)
        {
            return 0L;
        }
    }

    private static Filetransfer transferOf(final Message m)
    {
        if (m.filetransfer_id <= 0)
        {
            return null;
        }
        try
        {
            return orma.selectFromFiletransfer().idEq(m.filetransfer_id).get(0);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    // ---- drawing ----

    /** The time column, the day mark lined up with the content, the author, and none of the old chrome. */
    private static void frame(final Views v, final CharSequence time, final String fullAuthor,
                              @Nullable final String shownAuthor, final int authorColor)
    {
        final Context context = v.root.getContext();
        final Resources res = context.getResources();
        int column = res.getDimensionPixelSize(R.dimen.kq_size_time_column_phone);
        if (v.time != null)
        {
            // The bubble's time style comes off: a caption in tabular figures, in the third ink.
            v.time.setTextAppearance(R.style.TextAppearance_KQ_Caption);
            v.time.setTextColor(AirUi.color(context, R.color.kq_ink_3));
            v.time.setFontFeatureSettings("tnum");
            v.time.setBackground(null);
            v.time.setPadding(0, 0, 0, 0);
            v.time.setGravity(Gravity.START | Gravity.TOP);
            v.time.setText(time);
            v.time.setVisibility(View.VISIBLE);
            column = AirTranscript.timeColumnWidth(v.time);
            final ViewGroup.LayoutParams params = v.time.getLayoutParams();
            if (params != null && params.width != column)
            {
                params.width = column;
                v.time.setLayoutParams(params);
            }
        }
        if (v.dayRow != null)
        {
            final int start = res.getDimensionPixelSize(R.dimen.kq_space_16) + column
                              + res.getDimensionPixelSize(R.dimen.kq_space_10);
            if (v.dayRow.getPaddingStart() != start)
            {
                v.dayRow.setPaddingRelative(start, v.dayRow.getPaddingTop(), v.dayRow.getPaddingEnd(),
                                            v.dayRow.getPaddingBottom());
            }
            v.dayRow.setBackground(null);
        }
        AirTranscript.styleDayMark(v.dayLabel);
        if (v.author != null)
        {
            v.author.setText(shownAuthor);
            v.author.setTextColor(authorColor);
            v.author.setVisibility(shownAuthor != null ? View.VISIBLE : View.GONE);
        }
        v.root.setTag(R.id.air_file_spoken_author, fullAuthor);
        if (v.caption != null)
        {
            v.caption.setTextColor(AirUi.color(context, R.color.kq_ink));
            v.caption.setBackground(null);
        }
        hideLegacy(v);
        firstLine(v, res);
    }

    private static void render(final Context context, final Views v, final Entry e)
    {
        final Resources res = context.getResources();
        hideLegacy(v);
        if (v.previewContainer != null)
        {
            v.previewContainer.setVisibility(View.VISIBLE);
        }
        // A photo of theirs shows once it is here; until then the entry says the file and what it waits for.
        if (v.preview != null && (!e.picture || (!e.outgoing && e.state != State.DONE)))
        {
            v.preview.setVisibility(View.GONE);
        }
        final boolean pictured = visible(v.preview);
        final boolean playable = visible(v.player);
        // Under a photo or a voice note the first line goes: the preview already says what the file is.
        final boolean head = !pictured && !playable;

        if (v.head != null)
        {
            v.head.setVisibility(head ? View.VISIBLE : View.GONE);
        }
        if (v.icon != null)
        {
            v.icon.setImageResource(e.voice ? R.drawable.kq_ic_mic
                                            : (e.video ? R.drawable.kq_ic_video : R.drawable.kq_ic_file));
            ImageViewCompat.setImageTintList(v.icon, ColorStateList.valueOf(AirUi.color(context, R.color.kq_ink_2)));
        }
        if (v.name != null)
        {
            v.name.setText(e.name);
            v.name.setTextColor(AirUi.color(context, R.color.kq_ink));
        }
        if (v.size != null)
        {
            v.size.setText(e.total > 0 ? AirByteSize.text(context, e.total) : null);
            v.size.setTextColor(AirUi.color(context, R.color.kq_ink_3));
            v.size.setVisibility(e.total > 0 ? View.VISIBLE : View.GONE);
        }
        if (v.bar != null)
        {
            if (e.bar && e.total > 0)
            {
                // No animation between ticks: the bar is where the bytes are.
                v.bar.setMax(BAR_MAX);
                v.bar.setProgress((int) Math.min(BAR_MAX, (BAR_MAX * e.done) / e.total));
                v.bar.setVisibility(View.VISIBLE);
            }
            else
            {
                v.bar.setVisibility(View.GONE);
            }
        }
        if (v.note != null)
        {
            final boolean says = !TextUtils.isEmpty(e.note);
            v.note.setText(says ? e.note : null);
            v.note.setTextColor(AirUi.color(context, e.noteDanger ? R.color.kq_danger : R.color.kq_ink_3));
            v.note.setVisibility(says ? View.VISIBLE : View.GONE);
        }

        if (v.retry != null)
        {
            v.retry.setText(e.outgoing ? R.string.air_file_retry_outgoing : R.string.air_file_retry_incoming);
        }
        boolean offers = action(v.accept, e.accept);
        offers |= action(v.retry, e.retry);
        offers |= action(v.decline, e.decline);
        offers |= action(v.cancel, e.cancel);
        offers |= action(v.save, e.save);
        offers |= action(v.share, e.share);
        if (v.actions != null)
        {
            v.actions.setVisibility(offers ? View.VISIBLE : View.GONE);
            if (v.actions instanceof LinearLayout)
            {
                ((LinearLayout) v.actions).setOrientation(
                        res.getConfiguration().fontScale >= STACKED_ACTIONS_FONT_SCALE ? LinearLayout.VERTICAL
                                                                                       : LinearLayout.HORIZONTAL);
            }
        }

        if (v.row != null)
        {
            v.row.setBackground(null);
            v.row.setPadding(0, 0, 0, 0);
            v.row.setAlpha(1f);
            final boolean says = head || visible(v.bar) || visible(v.note) || offers;
            v.row.setVisibility(says ? View.VISIBLE : View.GONE);
            setTopMargin(v.row, head ? 0 : res.getDimensionPixelSize(R.dimen.kq_space_6));
        }

        if (pictured)
        {
            box(v, e);
        }

        if (v.mark != null)
        {
            if (e.status == AirConversationModel.EntryStatus.NONE)
            {
                v.mark.setVisibility(View.INVISIBLE);
            }
            else
            {
                v.mark.animate().cancel();
                AirTranscript.setMarkIcon(v.mark, e.status);
                v.mark.setAlpha(AirTranscript.markAlpha(e.status));
                v.mark.setVisibility(View.VISIBLE);
            }
        }

        firstLine(v, res);
        describe(context, v, e);
    }

    /** An action in words: shown with its work, or hidden without one. True when shown. */
    private static boolean action(@Nullable final TextView view, @Nullable final View.OnClickListener work)
    {
        if (view == null)
        {
            return false;
        }
        view.setOnClickListener(work);
        view.setVisibility(work != null ? View.VISIBLE : View.GONE);
        return work != null;
    }

    private static void hideLegacy(final Views v)
    {
        for (final int id : LEGACY)
        {
            final View old = v.root.findViewById(id);
            if (old != null)
            {
                old.setVisibility(View.GONE);
            }
        }
        if (v.bubble != null)
        {
            v.bubble.setBackground(null);
            v.bubble.setPadding(0, 0, 0, 0);
            v.bubble.setElevation(0f);
            v.bubble.setClipToOutline(false);
            final ViewGroup.LayoutParams params = v.bubble.getLayoutParams();
            if (params instanceof LinearLayout.LayoutParams)
            {
                // The old outgoing photo moved the bubble to the far side; an entry keeps its column.
                final LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) params;
                if (p.weight != 1f || p.width != 0 || p.leftMargin != 0 || p.rightMargin != 0
                    || p.getMarginStart() != 0 || p.getMarginEnd() != 0)
                {
                    p.weight = 1f;
                    p.width = 0;
                    p.leftMargin = 0;
                    p.rightMargin = 0;
                    p.setMarginStart(0);
                    p.setMarginEnd(0);
                    v.bubble.setLayoutParams(p);
                }
            }
        }
        if (v.fileRow instanceof LinearLayout)
        {
            ((LinearLayout) v.fileRow).setGravity(Gravity.START | Gravity.TOP);
        }
    }

    /** The time and the mark sit on the first line: the author's, the file's, the player's or the photo's top. */
    private static void firstLine(final Views v, final Resources res)
    {
        final int line;
        if (visible(v.author))
        {
            line = res.getDimensionPixelSize(R.dimen.kq_line_label);
        }
        else if (visible(v.player))
        {
            line = res.getDimensionPixelSize(R.dimen.kq_size_target_android);
        }
        else if (visible(v.preview))
        {
            line = res.getDimensionPixelSize(R.dimen.kq_line_caption);
        }
        else
        {
            line = Math.max(res.getDimensionPixelSize(R.dimen.kq_line_label),
                            res.getDimensionPixelSize(R.dimen.kq_size_icon));
        }
        setTopMargin(v.time, Math.max(0, (line - res.getDimensionPixelSize(R.dimen.kq_line_caption)) / 2));
        setTopMargin(v.mark, Math.max(0, (line - res.getDimensionPixelSize(R.dimen.kq_size_mark)) / 2));
    }

    /** One sentence for TalkBack: who, when, what, how big, the state, and the mark of our own. */
    private static void describe(final Context context, final Views v, final Entry e)
    {
        if (v.container == null)
        {
            return;
        }
        final StringBuilder sentence = new StringBuilder();
        final Object author = v.root.getTag(R.id.air_file_spoken_author);
        append(sentence, author instanceof String ? (String) author : null);
        append(sentence, v.time == null ? null : v.time.getText());
        append(sentence, e.voice && visible(v.player) ? context.getString(R.string.air_voice_spoken) : e.name);
        append(sentence, e.total > 0 ? AirByteSize.text(context, e.total) : null);
        append(sentence, e.note);
        append(sentence, AirTranscript.statusWord(context, e.status));
        if (visible(v.caption))
        {
            append(sentence, v.caption.getText());
        }
        v.container.setContentDescription(sentence.toString());
    }

    private static void append(final StringBuilder sentence, @Nullable final CharSequence part)
    {
        if (part == null || part.toString().trim().isEmpty())
        {
            return;
        }
        if (sentence.length() > 0)
        {
            sentence.append(", ");
        }
        sentence.append(part);
    }

    // ---- the photo box ----

    /**
     * A photo or a video stands in a box of its own shape, on the still sunken plate, before its picture
     * comes: the shape is read once from the file's header (turned by its EXIF orientation) off the main
     * thread; until then, and for a video, the box has the usual shape of one.
     */
    private static void box(final Views v, final Entry e)
    {
        final View preview = v.preview;
        if (preview == null)
        {
            return;
        }
        final View box = boxOf(preview);
        preview.setBackgroundResource(R.drawable.air_file_photo_ground);
        if (preview instanceof ImageView)
        {
            ((ImageView) preview).setScaleType(ImageView.ScaleType.CENTER_CROP);
        }
        preview.setOutlineProvider(ROUNDED);
        preview.setClipToOutline(true);
        if (box != preview)
        {
            box.setOutlineProvider(ROUNDED);
            box.setClipToOutline(true);
        }
        final int column = v.time != null ? AirTranscript.timeColumnWidth(v.time)
                                          : preview.getResources().getDimensionPixelSize(
                                                  R.dimen.kq_size_time_column_phone);
        final String key = e.path == null ? "" : e.path;
        preview.setTag(R.id.air_file_aspect_key, key);
        final Float known = key.isEmpty() ? null : ASPECTS.get(key);
        if (known != null)
        {
            size(preview, known, column);
            return;
        }
        size(preview, e.video ? VIDEO_ASPECT : PHOTO_ASPECT, column);
        if (e.video || key.isEmpty() || !READING.add(key))
        {
            return;
        }
        final Context app = preview.getContext().getApplicationContext();
        READER.execute(() -> {
            float read = 0f;
            try
            {
                read = readAspect(app, key);
            }
            finally
            {
                READING.remove(key);
            }
            ASPECTS.put(key, read > 0f ? read : PHOTO_ASPECT);
            if (read > 0f)
            {
                final float found = read;
                MAIN.post(() -> {
                    if (key.equals(preview.getTag(R.id.air_file_aspect_key)))
                    {
                        size(preview, found, column);
                    }
                });
            }
        });
    }

    /** The preview, or the frame the video chrome or the transfer overlay put around it. */
    private static View boxOf(final View preview)
    {
        final ViewParent parent = preview.getParent();
        return parent instanceof FrameLayout ? (View) parent : preview;
    }

    private static void size(final View preview, final float shape, final int column)
    {
        final View box = boxOf(preview);
        final Context context = box.getContext();
        final Resources res = context.getResources();
        final float aspect = Math.max(MIN_ASPECT, Math.min(MAX_ASPECT, shape));
        final int room = Math.round(res.getConfiguration().screenWidthDp * res.getDisplayMetrics().density)
                         - 2 * res.getDimensionPixelSize(R.dimen.kq_space_16)
                         - 2 * res.getDimensionPixelSize(R.dimen.kq_space_10) - column
                         - res.getDimensionPixelSize(R.dimen.kq_size_mark);
        int width = Math.max(1, Math.min(ChatBubbleUiHelper.media_thumb_max_w_px(context), room));
        int height = Math.max(1, Math.round(width / aspect));
        final int highest = ChatBubbleUiHelper.media_thumb_max_h_px(context);
        if (height > highest)
        {
            height = highest;
            width = Math.max(1, Math.round(height * aspect));
        }
        final ViewGroup.LayoutParams params = box.getLayoutParams();
        if (params != null && (params.width != width || params.height != height))
        {
            params.width = width;
            params.height = height;
            box.setLayoutParams(params);
        }
        if (box != preview)
        {
            final ViewGroup.LayoutParams inner = preview.getLayoutParams();
            if (inner != null && (inner.width != ViewGroup.LayoutParams.MATCH_PARENT
                                  || inner.height != ViewGroup.LayoutParams.MATCH_PARENT))
            {
                inner.width = ViewGroup.LayoutParams.MATCH_PARENT;
                inner.height = ViewGroup.LayoutParams.MATCH_PARENT;
                preview.setLayoutParams(inner);
            }
        }
    }

    /** Width over height as the picture is shown, or 0 when the header cannot be read. */
    private static float readAspect(final Context context, final String path)
    {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = open(context, path))
        {
            if (in == null)
            {
                return 0f;
            }
            BitmapFactory.decodeStream(in, null, bounds);
        }
        catch (Exception | OutOfMemoryError e)
        {
            return 0f;
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0)
        {
            return 0f;
        }
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = open(context, path))
        {
            if (in != null)
            {
                orientation = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,
                                                                    ExifInterface.ORIENTATION_NORMAL);
            }
        }
        catch (Exception | OutOfMemoryError ignored)
        {
            // No orientation: as stored.
        }
        final boolean turned = orientation == ExifInterface.ORIENTATION_ROTATE_90
                               || orientation == ExifInterface.ORIENTATION_ROTATE_270
                               || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                               || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
        final float width = turned ? bounds.outHeight : bounds.outWidth;
        final float height = turned ? bounds.outWidth : bounds.outHeight;
        return width / height;
    }

    @Nullable
    private static InputStream open(final Context context, final String path) throws Exception
    {
        if (path.startsWith("content://"))
        {
            final InputStream in = context.getContentResolver().openInputStream(Uri.parse(path));
            return in == null ? null : new BufferedInputStream(in);
        }
        if (MainActivity.VFS_ENCRYPT && HelperFiletransfer.isIocipherVirtualPath(path))
        {
            return new BufferedInputStream(
                    new info.guardianproject.iocipher.FileInputStream(new info.guardianproject.iocipher.File(path)));
        }
        return new BufferedInputStream(new java.io.FileInputStream(path));
    }

    // ---- small things ----

    private static boolean visible(@Nullable final View view)
    {
        return view != null && view.getVisibility() == View.VISIBLE;
    }

    private static void setTopMargin(@Nullable final View view, final int top)
    {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams))
        {
            return;
        }
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) view.getLayoutParams();
        if (params.topMargin != top)
        {
            params.topMargin = top;
            view.setLayoutParams(params);
        }
    }

    /** What an entry says, worked out from the message and its transfer. */
    private static final class Entry
    {
        State state = State.DONE;
        boolean outgoing;
        boolean voice;
        boolean video;
        /** A photo or a video: shown in the box. */
        boolean picture;
        long total;
        long done;
        CharSequence name = "";
        @Nullable
        String path;
        @Nullable
        CharSequence note;
        boolean noteDanger;
        boolean bar;
        @Nullable
        View.OnClickListener accept;
        @Nullable
        View.OnClickListener decline;
        @Nullable
        View.OnClickListener cancel;
        @Nullable
        View.OnClickListener retry;
        @Nullable
        View.OnClickListener save;
        @Nullable
        View.OnClickListener share;
        AirConversationModel.EntryStatus status = AirConversationModel.EntryStatus.NONE;
    }

    /** The transfer helper's last reading of a group's file, kept on the row for the frame after it. */
    private static final class GroupStamp
    {
        final long messageId;
        final ChatTransferProgressHelper.Snapshot snap;

        GroupStamp(final long messageId, final ChatTransferProgressHelper.Snapshot snap)
        {
            this.messageId = messageId;
            this.snap = snap;
        }
    }

    private static final class Views
    {
        final View root;
        @Nullable
        final View container;
        @Nullable
        final TextView time;
        @Nullable
        final View dayRow;
        @Nullable
        final TextView dayLabel;
        @Nullable
        final TextView author;
        @Nullable
        final TextView peerName;
        @Nullable
        final View fileRow;
        @Nullable
        final ViewGroup bubble;
        @Nullable
        final ImageView mark;
        @Nullable
        final View previewContainer;
        @Nullable
        final View preview;
        @Nullable
        final View player;
        @Nullable
        final TextView caption;
        @Nullable
        final View row;
        @Nullable
        final View head;
        @Nullable
        final ImageView icon;
        @Nullable
        final TextView name;
        @Nullable
        final TextView size;
        @Nullable
        final ProgressBar bar;
        @Nullable
        final TextView note;
        @Nullable
        final View actions;
        @Nullable
        final TextView accept;
        @Nullable
        final TextView retry;
        @Nullable
        final TextView decline;
        @Nullable
        final TextView cancel;
        @Nullable
        final TextView save;
        @Nullable
        final TextView share;

        Views(final View root)
        {
            this.root = root;
            container = root.findViewById(R.id.layout_message_container);
            time = root.findViewById(R.id.date_time);
            dayRow = root.findViewById(R.id.message_text_date);
            dayLabel = root.findViewById(R.id.message_text_date_string);
            author = root.findViewById(R.id.air_file_author);
            peerName = root.findViewById(R.id.peer_name_text);
            fileRow = root.findViewById(R.id.air_file_row);
            ViewGroup found = root.findViewById(R.id.ft_incoming_rounded_bg);
            if (found == null)
            {
                found = root.findViewById(R.id.ft_outgoing_rounded_bg);
            }
            if (found == null)
            {
                found = root.findViewById(R.id.m_container);
            }
            bubble = found;
            mark = root.findViewById(R.id.m_icon);
            previewContainer = root.findViewById(R.id.ft_preview_container);
            preview = root.findViewById(R.id.ft_preview_image);
            player = root.findViewById(R.id.ft_audio_player);
            caption = root.findViewById(R.id.ft_caption_text);
            row = root.findViewById(R.id.ft_file_attachment_row);
            head = root.findViewById(R.id.air_file_head);
            icon = root.findViewById(R.id.ft_file_icon);
            name = root.findViewById(R.id.ft_file_name);
            size = root.findViewById(R.id.ft_file_size);
            bar = root.findViewById(R.id.ft_file_progress);
            note = root.findViewById(R.id.ft_file_progress_detail);
            actions = root.findViewById(R.id.air_file_actions);
            accept = root.findViewById(R.id.air_file_accept);
            retry = root.findViewById(R.id.air_file_retry);
            decline = root.findViewById(R.id.air_file_decline);
            cancel = root.findViewById(R.id.air_file_cancel);
            save = root.findViewById(R.id.air_file_save);
            share = root.findViewById(R.id.air_file_share);
        }
    }
}
