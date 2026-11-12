package com.zoffcc.applications.trifa;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.icu.text.ListFormatter;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.ImageViewCompat;
import androidx.core.widget.TextViewCompat;
import androidx.fragment.app.Fragment;

import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.zoffcc.applications.trifa.TRIFAGlobals.LOGFRIEND_ON_STARTUP_DONE_DB_KEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOGFRIEND_TOXID_DB_KEY;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LOG_FRIEND_TOXID;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;

/**
 * KHANDAQ (redesign, stage 2): everything around the transcript of the conversation with one person
 * (02-plan.md, screens 5, 6, 7, 10 and 16), as the Air parts of ChatPrivateController on iOS: the header
 * and the presence band under it, the "In transit" tray, the line under the field with its counter, and
 * the dossier. MessageListActivity owns the screen and calls in at its lifecycle points.
 *
 * <p>What the panel shows is read on its own worker thread ({@link Snapshot}) and drawn on the main
 * thread. It hears of changes through {@link AirRepository#chatWatcher}, which every message, receipt and
 * friend change already notifies, through the contact marks, and once every 30 seconds for the words
 * that age ("offline for 25 minutes").
 *
 * <p>While the person is away, our texts without a receipt wait in the tray, not in the log
 * ({@link AirTranscript#isTrayRow}); when they come online the tray empties, one row 90 ms after the
 * other, and the texts rise into the log in the same order.
 */
final class AirChatPanel implements AirDossier.Listener, AirContactMarks.Listener
{
    private static final String TAG = "trifa.AirChatPanel";

    /**
     * The tray edits and cancels a queued text, and the service loop sends one, only while holding this
     * lock, each reading the row again first: a text is changed either before it leaves or not at all.
     */
    static final Object UNSENT_LOCK = new Object();

    private static final long READ_DEBOUNCE_MS = 150L;
    /** The words that age with the clock, and the day strip's current hour. */
    private static final long TICK_MS = 30_000L;
    /** The tray's list stops growing at this share of the screen and scrolls, as on iOS. */
    private static final float TRAY_MAX_SCREEN_SHARE = 0.28f;
    /** A guard on the tray's query, which normally returns a handful of rows. */
    private static final int TRAY_ROWS_MAX = 200;
    private static final int KEY_LENGTH = 64;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object WORKER_LOCK = new Object();
    private static Handler worker = null;

    // ---- what the worker reads ----

    /** One of our texts in the tray, with the words a row shows. */
    private static final class TrayItem
    {
        final long id;
        /** The text as the row shows it: without the reply header, a location as its coordinates. */
        final String text;
        /** What the editor starts with. */
        final String body;
        final String time;
        /** Never handed to toxcore: no other device can hold a copy yet. */
        final boolean neverLeft;
        final boolean editable;

        TrayItem(final long id, final String text, final String body, final String time, final boolean neverLeft,
                 final boolean editable)
        {
            this.id = id;
            this.text = text;
            this.body = body;
            this.time = time;
            this.neverLeft = neverLeft;
            this.editable = editable;
        }

        @Override
        public boolean equals(final Object other)
        {
            if (!(other instanceof TrayItem))
            {
                return false;
            }
            final TrayItem that = (TrayItem) other;
            return id == that.id && neverLeft == that.neverLeft && editable == that.editable
                   && text.equals(that.text) && body.equals(that.body) && time.equals(that.time);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(id, text, body, time, neverLeft, editable);
        }
    }

    /** Everything the panel shows about the person, read in one go off the main thread. */
    private static final class Snapshot
    {
        /** False once the contact was removed: no dossier, no tray, no line under the field. */
        final boolean exists;
        final String name;
        /** Their own name when a nickname hides it; empty otherwise. */
        final String ownName;
        final String status;
        /** The nickname, empty when there is none. */
        final String alias;
        final AirModel.Reach reach;
        final Long onlineSince;
        final Set<Long> hours;
        final long verifiedMs;
        final boolean hasPush;
        final boolean muted;
        final ChatBubbleUiHelper.AvatarMeta photo;
        /** Online, and their app puts long texts back together: a long text goes whole, in pieces. */
        final boolean canChunk;
        final List<TrayItem> tray;

        Snapshot(final boolean exists, final String name, final String ownName, final String status,
                 final String alias, final AirModel.Reach reach, final Long onlineSince, final Set<Long> hours,
                 final long verifiedMs, final boolean hasPush, final boolean muted,
                 final ChatBubbleUiHelper.AvatarMeta photo, final boolean canChunk, final List<TrayItem> tray)
        {
            this.exists = exists;
            this.name = name == null ? "" : name;
            this.ownName = ownName == null ? "" : ownName;
            this.status = status == null ? "" : status;
            this.alias = alias == null ? "" : alias;
            this.reach = reach == null ? AirModel.Reach.AWAY_LONG_AGO : reach;
            this.onlineSince = onlineSince;
            this.hours = hours == null ? Collections.<Long>emptySet() : hours;
            this.verifiedMs = verifiedMs;
            this.hasPush = hasPush;
            this.muted = muted;
            this.photo = photo;
            this.canChunk = canChunk;
            this.tray = tray == null ? Collections.<TrayItem>emptyList() : tray;
        }

        boolean sameAs(final Snapshot other)
        {
            return other != null && exists == other.exists && name.equals(other.name)
                   && ownName.equals(other.ownName) && status.equals(other.status) && alias.equals(other.alias)
                   && reach.equals(other.reach) && Objects.equals(onlineSince, other.onlineSince)
                   && hours.equals(other.hours) && verifiedMs == other.verifiedMs && hasPush == other.hasPush
                   && muted == other.muted && canChunk == other.canChunk && samePhoto(photo, other.photo)
                   && tray.equals(other.tray);
        }

        private static boolean samePhoto(final ChatBubbleUiHelper.AvatarMeta a, final ChatBubbleUiHelper.AvatarMeta b)
        {
            if (a == b)
            {
                return true;
            }
            return a != null && b != null && a.loadable == b.loadable && a.updateTs == b.updateTs
                   && Objects.equals(a.path, b.path) && Objects.equals(a.filename, b.filename);
        }
    }

    // ---- views ----

    private final MessageListActivity activity;
    /** Upper case, as the activity keeps it; null when the chat could not tell whose it is. */
    private final String publicKey;
    private final boolean saved;
    private final Interpolator easeOut;

    private final View root;
    private final Toolbar toolbar;
    private final View profileTap;
    private final AirTileView tile;
    private final TextView name;
    private final View chevron;
    private final ImageButton phone;
    private final ImageButton video;
    private final View band;
    private final AirLinkMarkView linkMark;
    private final TextView presence;
    private final AirDayStripView strip;
    private final TextView stripHint;
    private final View conversation;
    private final LinearLayout tray;
    private final View trayHead;
    private final ImageView trayClock;
    private final TextView trayTitle;
    private final ImageView trayChevron;
    private final TextView trayDetail;
    private final AirCappedScrollView trayScroll;
    private final LinearLayout trayItems;
    private final View composer;
    private final EditText field;
    private final ImageButton micSend;
    private final View hintRow;
    private final View hintDot;
    private final TextView hint;
    /** E21: what the composer refused or what failed to send, on the line of the hint for a while. */
    private final AirComposerNotice notice;
    private final TextView counter;
    private final AirDossier dossier;
    private final OnBackPressedCallback backCallback;
    private final int trayBottomMargin;

    // ---- state (main thread) ----

    private final boolean friendExists;
    private Snapshot shown = null;
    /** The dossier unfolds once the first snapshot is drawn (openDossierWhenShown). */
    private boolean dossierOnFirstShow = false;
    /** Whether the header opens the dossier, as last set; null before the first bind. */
    private Boolean headerOpens = null;
    /** Set by the activity when the header leaves the name too little room for the chevron as well. */
    private boolean chevronDropped = false;
    private String trayHeadAction = null;
    private boolean resumed = false;
    private boolean destroyed = false;
    private String headerTileTitle = null;
    private String headerPhotoKey = null;
    private boolean[] shownStrip = null;
    private int shownStripHour = -1;
    private boolean shownStripLive = false;
    private List<Long> trayIds = new ArrayList<>();
    private final Map<Long, TrayItem> trayItemsById = new HashMap<>();
    private final Map<Long, View> trayRows = new HashMap<>();
    private String trayDetailText = "";
    private boolean trayExpanded = false;
    private boolean trayDeparting = false;
    private ValueAnimator trayHeight = null;
    private boolean keyboardUp = false;
    private boolean replyActive = false;
    private boolean editActive = false;

    private final AtomicBoolean readScheduled = new AtomicBoolean(false);
    /** AirRepository calls it on whatever thread changed something; it only schedules a read. */
    private final Runnable watcher = this::scheduleRead;
    private final Runnable read = this::readOnWorker;
    private final Runnable tick = new Runnable()
    {
        @Override
        public void run()
        {
            if (!resumed || destroyed)
            {
                return;
            }
            scheduleRead();
            MAIN.postDelayed(this, TICK_MS);
        }
    };
    private final Runnable foldTray = this::foldDepartedTray;
    private final Runnable finishDepart = this::finishDepart;

    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::onLayoutPass;

    private final TextWatcher fieldWatcher = new TextWatcher()
    {
        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after)
        {
        }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count)
        {
        }

        @Override
        public void afterTextChanged(final Editable s)
        {
            // A change of the words answers a refusal: the line says who gets the message again.
            notice.end();
            updateCounter();
            describeSendButton();
        }
    };

    AirChatPanel(final MessageListActivity activity, final String publicKey)
    {
        this.activity = activity;
        this.publicKey = TextUtils.isEmpty(publicKey) ? null : publicKey.toUpperCase(Locale.ROOT);
        saved = FavoritesChatHelper.isFavoritesChat(this.publicKey);
        easeOut = AnimationUtils.loadInterpolator(activity, R.interpolator.kq_ease_out);

        root = activity.findViewById(android.R.id.content);
        toolbar = activity.findViewById(R.id.toolbar);
        profileTap = activity.findViewById(R.id.ml_header_profile_tap);
        tile = activity.findViewById(R.id.air_chat_tile);
        name = activity.findViewById(R.id.ml_maintext);
        // One line that ends in an ellipsis: with maxLines alone a name cut at a space shows its first word
        // and nothing after it, as if that were the whole name.
        name.setSingleLine(true);
        chevron = activity.findViewById(R.id.air_chat_chevron);
        phone = activity.findViewById(R.id.ml_phone_icon);
        video = activity.findViewById(R.id.ml_video_icon);
        band = activity.findViewById(R.id.air_chat_band);
        linkMark = activity.findViewById(R.id.air_chat_link);
        presence = activity.findViewById(R.id.air_chat_presence);
        strip = activity.findViewById(R.id.air_chat_strip);
        stripHint = activity.findViewById(R.id.air_chat_strip_hint);
        conversation = activity.findViewById(R.id.air_chat_conversation);
        tray = activity.findViewById(R.id.air_tray);
        trayHead = activity.findViewById(R.id.air_tray_head);
        trayClock = activity.findViewById(R.id.air_tray_clock);
        trayTitle = activity.findViewById(R.id.air_tray_title);
        trayChevron = activity.findViewById(R.id.air_tray_chevron);
        trayDetail = activity.findViewById(R.id.air_tray_detail);
        trayScroll = activity.findViewById(R.id.air_tray_scroll);
        trayItems = activity.findViewById(R.id.air_tray_items);
        composer = activity.findViewById(R.id.emoji_bar);
        field = activity.findViewById(R.id.ml_new_message);
        micSend = activity.findViewById(R.id.ml_button_recaudio);
        hintRow = activity.findViewById(R.id.air_compose_hint_row);
        hintDot = activity.findViewById(R.id.air_compose_dot);
        hint = activity.findViewById(R.id.air_compose_hint);
        counter = activity.findViewById(R.id.air_compose_counter);
        notice = new AirComposerNotice(hint, hintDot, this::redrawComposeHint);
        trayBottomMargin = ((ViewGroup.MarginLayoutParams) tray.getLayoutParams()).bottomMargin;

        // Both before the activity binds its helpers: the microphone keeps the design's tint between the
        // send arrow's turns, and the reply and edit bar takes the design's colours.
        micSend.setTag(R.id.air_mic_tint, ContextCompat.getColorStateList(activity, R.color.air_icon_button_tint));
        final View replyBar = activity.findViewById(R.id.chat_reply_preview_bar);
        if (replyBar != null)
        {
            replyBar.setTag(R.id.air_reply_style, Boolean.TRUE);
        }

        dossier = (saved || this.publicKey == null) ? null : new AirDossier(activity, this);
        backCallback = new OnBackPressedCallback(false)
        {
            @Override
            public void handleOnBackPressed()
            {
                closeDossier(true);
            }
        };
        activity.getOnBackPressedDispatcher().addCallback(activity, backCallback);

        // Before the transcript loads its first page: whose texts wait in the tray instead of the log.
        FriendList friend = null;
        boolean online = false;
        if (!saved && this.publicKey != null)
        {
            try
            {
                friend = HelperFriend.main_get_friend(this.publicKey);
                online = friend != null && AirRepository.friendReachNow(friend).isLive();
            }
            catch (Exception e)
            {
                Log.i(TAG, "friend:EE:" + e.getMessage());
            }
        }
        friendExists = saved || friend != null;
        AirTranscript.personKey = this.publicKey;
        AirTranscript.personName = saved ? "" : displayName(friend, this.publicKey);
        AirTranscript.personOnline = online;
    }

    // ---- lifecycle, called by MessageListActivity ----

    /** At the end of the activity's onCreate, once its own helpers are bound. */
    void start()
    {
        styleWindow(activity, root);
        styleHeader();
        styleComposer(activity, composer, field, micSend);
        styleReplyBar(activity);
        styleVoiceBar(activity, MessageListActivity.voiceRecordingUiHelper);

        // The band and the line under the field each read as one sentence.
        presence.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        stripHint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hint.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        counter.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        hint.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
        ViewCompat.setScreenReaderFocusable(hintRow, true);
        // A line longer than the row wraps rather than being cut (a long name or translation, a large text
        // size), as the iOS line does (AirComposeHintView).
        hint.setMaxLines(Integer.MAX_VALUE);

        AirUi.markAsButton(band);
        band.setOnClickListener(v -> toggleDossier());
        AirUi.markAsButton(trayHead);
        trayHead.setOnClickListener(v -> setTrayExpanded(!trayExpanded, true));
        trayScroll.setMaxHeight(Math.round(activity.getResources().getDisplayMetrics().heightPixels
                                           * TRAY_MAX_SCREEN_SHARE));
        if (dossier != null)
        {
            AirUi.markAsButton(profileTap);
        }

        field.addTextChangedListener(fieldWatcher);
        root.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
        describeSendButton();

        // The first look is read here, before the transcript's first page and the first frame, so the band,
        // the tray and the line under the field do not pop in a moment later.
        Snapshot first = null;
        try
        {
            first = read(activity, publicKey, saved);
        }
        catch (Throwable e)
        {
            Log.i(TAG, "start:read:EE:" + e.getMessage());
        }
        if (first != null)
        {
            apply(first);
        }
        else
        {
            bindHeaderOnly();
            scheduleRead();
        }
    }

    void onResume()
    {
        resumed = true;
        if (shown != null)
        {
            AirTranscript.personKey = publicKey;
            AirTranscript.personName = shown.name;
            AirTranscript.personOnline = shown.reach.isLive();
        }
        AirRepository.chatWatcher = watcher;
        AirContactMarks.addListener(this);
        MAIN.removeCallbacks(tick);
        MAIN.postDelayed(tick, TICK_MS);
        scheduleRead();
        // The button follows what the field holds now: a draft put back while away shows the arrow.
        ChatInputBarHelper.updateMicSendIcon(micSend, micIcon(activity), sendIcon(activity),
                                             HelperGeneric.has_sendable_chat_text(field.getText()));
        describeSendButton();
        // What a viewer or a picker in front could not do, said here now that this conversation is back.
        AirComposerNotice.deliverOnReturn(this::sayAtComposer);
    }

    void onPause()
    {
        resumed = false;
        if (AirRepository.chatWatcher == watcher)
        {
            AirRepository.chatWatcher = null;
        }
        AirContactMarks.removeListener(this);
        MAIN.removeCallbacks(tick);
    }

    void onDestroy()
    {
        onPause();
        destroyed = true;
        notice.release();
        MAIN.removeCallbacks(foldTray);
        MAIN.removeCallbacks(finishDepart);
        if (trayHeight != null)
        {
            trayHeight.cancel();
            trayHeight = null;
        }
        for (final View row : trayRows.values())
        {
            row.animate().cancel();
        }
        field.removeTextChangedListener(fieldWatcher);
        root.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        backCallback.remove();
        if (dossier != null)
        {
            dossier.release();
        }
        if (publicKey != null && publicKey.equals(AirTranscript.personKey))
        {
            AirTranscript.personKey = null;
            AirTranscript.personName = "";
            AirTranscript.personOnline = false;
        }
    }

    /** The person's presence or typing changed (toxcore's callbacks for the open chat). */
    void requestUpdate()
    {
        scheduleRead();
    }

    /** MessageListActivity.friend_is_typing changed: the presence words follow at once. */
    void onTypingChanged()
    {
        if (shown != null && !destroyed)
        {
            bindHeader(shown);
            bindBand(shown);
        }
    }

    boolean hasDossier()
    {
        return dossier != null && (shown == null ? friendExists : shown.exists);
    }

    /** The name still opens the dossier without its chevron (MessageListActivity.fitHeaderToName). */
    void setChevronDropped(final boolean dropped)
    {
        chevronDropped = dropped;
        chevron.setVisibility(hasDossier() && !dropped ? View.VISIBLE : View.GONE);
    }

    /**
     * KHANDAQ (redesign, E10): unfold the dossier as soon as the conversation has been read (Information in
     * a row's menu opens the chat this way); at once when it already has been.
     */
    void openDossierWhenShown()
    {
        if (shown != null)
        {
            dossierOnFirstShow = false;
            openDossier();
        }
        else
        {
            dossierOnFirstShow = true;
        }
    }

    void toggleDossier()
    {
        if (dossier == null)
        {
            return;
        }
        if (dossier.isOpen())
        {
            closeDossier(true);
        }
        else
        {
            openDossier();
        }
    }

    void closeDossier(final boolean animated)
    {
        if (dossier == null || !dossier.isOpen())
        {
            return;
        }
        dossier.close(animated);
        backCallback.setEnabled(false);
        AirDossier.describeToggle(profileTap, false);
        AirDossier.describeToggle(band, false);
    }

    /** Esc closes the dossier, as on iOS with a keyboard. True when the key was taken. */
    boolean handleKey(final KeyEvent event)
    {
        if (event.getKeyCode() != KeyEvent.KEYCODE_ESCAPE || dossier == null || !dossier.isOpen())
        {
            return false;
        }
        if (event.getAction() == KeyEvent.ACTION_UP)
        {
            closeDossier(true);
        }
        return true;
    }

    private void openDossier()
    {
        if (!hasDossier() || shown == null || dossier.isOpen())
        {
            return;
        }
        hideKeyboard();
        // A focused composer keeps the keyboard a dossier dialog (rename) raised after that dialog closes,
        // and the keyboard then types into the field behind the dossier; with no editor focused, it goes.
        field.clearFocus();
        if (activity.emojiPopup != null)
        {
            activity.emojiPopup.dismiss();
        }
        dossier.bind(dossierContent(shown));
        dossier.open();
        backCallback.setEnabled(true);
        AirDossier.describeToggle(profileTap, true);
        AirDossier.describeToggle(band, true);
    }

    // ---- reading ----

    private static Handler worker()
    {
        synchronized (WORKER_LOCK)
        {
            if (worker == null)
            {
                final HandlerThread thread = new HandlerThread("air-chat", Process.THREAD_PRIORITY_BACKGROUND);
                thread.start();
                worker = new Handler(thread.getLooper());
            }
            return worker;
        }
    }

    private void scheduleRead()
    {
        if (destroyed)
        {
            return;
        }
        if (readScheduled.compareAndSet(false, true))
        {
            worker().postDelayed(read, READ_DEBOUNCE_MS);
        }
    }

    private void readOnWorker()
    {
        readScheduled.set(false);
        if (destroyed)
        {
            return;
        }
        final Snapshot next;
        try
        {
            next = read(activity, publicKey, saved);
        }
        catch (Throwable e)
        {
            // A half-read database (a profile switching, a migration) must never take the chat down.
            Log.i(TAG, "read:EE:" + e.getMessage());
            return;
        }
        MAIN.post(() -> apply(next));
    }

    private static Snapshot read(final Context context, final String key, final boolean saved)
    {
        if (saved)
        {
            return new Snapshot(true, FavoritesChatHelper.displayName(context), "", "", "", AirModel.Reach.LOCAL,
                                null, null, 0L, false, false, null, false, null);
        }
        final FriendList friend = (key == null || TrifaToxService.orma == null) ? null : HelperFriend.main_get_friend(key);
        if (friend == null)
        {
            return new Snapshot(false, displayName(null, key), "", "", "", AirModel.Reach.AWAY_LONG_AGO, null,
                                null, 0L, false, false, null, false, null);
        }
        final String shownName = displayName(friend, key);
        String ownName = "";
        if (!TextUtils.isEmpty(friend.name) && !HelperFriend.is_placeholder_friend_name(friend.name, key)
            && !friend.name.equals(shownName))
        {
            ownName = friend.name;
        }
        final AirModel.Reach reach = AirRepository.friendReachNow(friend);
        final boolean live = reach.isLive();
        boolean canChunk = false;
        if (live)
        {
            final long friendNumber = HelperFriend.tox_friend_by_public_key__wrapper(key);
            canChunk = friendNumber >= 0 && MessageChunker.canReassemble(friendNumber);
        }
        return new Snapshot(true, shownName, ownName, friend.status_message == null ? "" : friend.status_message.trim(),
                            friend.alias_name, reach, AirContactMarks.onlineSince(key), AirContactMarks.hours(key),
                            AirContactMarks.verifiedMs(key), !TextUtils.isEmpty(friend.push_url),
                            friend.notification_silent, ChatBubbleUiHelper.peer_avatar_meta(friend), canChunk,
                            live ? null : readTray(key));
    }

    private static String displayName(final FriendList friend, final String key)
    {
        final String fromList = HelperFriend.display_name_from_friendlist(friend, key);
        if (!TextUtils.isEmpty(fromList))
        {
            return fromList;
        }
        return key == null ? "" : HelperFriend.peer_pubkey_short_id(key);
    }

    /** Our texts that wait for the person, oldest first (AirRepository.isWaitingDirectText). */
    private static List<TrayItem> readTray(final String key)
    {
        final List<Message> rows = TrifaToxService.orma.selectFromMessage().tox_friendpubkeyEq(key).directionEq(1)
                .TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_TYPE_TEXT.value).readEq(false)
                .orderBySent_timestampDesc().limit(TRAY_ROWS_MAX).toList();
        if (rows == null || rows.isEmpty())
        {
            return Collections.emptyList();
        }
        final long now = System.currentTimeMillis();
        final List<TrayItem> items = new ArrayList<>(rows.size());
        for (int i = rows.size() - 1; i >= 0; i--)
        {
            final Message m = rows.get(i);
            if (!AirRepository.isWaitingDirectText(m) || TextUtils.isEmpty(m.text))
            {
                continue;
            }
            final MessageReplyHelper.ParsedMessage parsed = MessageReplyHelper.parse(m.text);
            final String body = parsed.bodyText == null ? "" : parsed.bodyText;
            final double[] location = HelperLocationMessage.parse(body);
            final String shownText = location != null
                                     ? String.format(Locale.ROOT, "%.5f, %.5f", location[0], location[1]) : body;
            final boolean neverLeft = neverLeft(m);
            // As in the log: no reply (the quote would be lost) and no location. A text that left once is
            // corrected on the person's device too, by the rules of a correction.
            final boolean editable = parsed.reply == null && location == null
                                     && (neverLeft || HelperMessageEdit.directEditBlockReason(m, now) == 0);
            items.add(new TrayItem(m.id, shownText, body, HelperGeneric.format_chat_message_time(m, true), neverLeft,
                                   editable));
        }
        return items;
    }

    /** Never handed to toxcore: no message number and no msgV3 id. */
    private static boolean neverLeft(final Message m)
    {
        return m.message_id < 0L && (m.msg_idv3_hash == null || m.msg_idv3_hash.length() < KEY_LENGTH);
    }

    // ---- drawing ----

    private void apply(final Snapshot next)
    {
        if (destroyed || next == null)
        {
            return;
        }
        final Snapshot before = shown;
        final boolean same = before != null && before.sameAs(next);
        shown = next;
        final boolean online = next.reach.isLive();
        final boolean onlineChanged = before == null ? online != AirTranscript.personOnline
                                                     : online != before.reach.isLive();
        final boolean nameChanged = before == null ? !TextUtils.equals(AirTranscript.personName, next.name)
                                                   : !next.name.equals(before.name);
        AirTranscript.personKey = publicKey;
        AirTranscript.personName = next.name;
        AirTranscript.personOnline = online;

        // Cheap, and each change-guarded: the day strip's hour moves on even when nothing else did.
        bindHeader(next);
        bindBand(next);
        bindComposeHint(next);
        updateCounter();
        if (dossierOnFirstShow)
        {
            dossierOnFirstShow = false;
            openDossier();
        }
        if (same)
        {
            return;
        }
        boolean reloaded = false;
        if (!trayDeparting)
        {
            reloaded = bindTray(next, before != null);
        }
        if (onlineChanged && !reloaded)
        {
            // The log takes the tray's texts back, or hands them to it, and our marks follow the person.
            reloadLog();
        }
        else if (nameChanged && !reloaded)
        {
            refreshEntries();
        }
        if (dossier != null && dossier.isOpen())
        {
            dossier.bind(dossierContent(next));
        }
    }

    /** Before the first read: at least the name the activity already knows. */
    private void bindHeaderOnly()
    {
        final String title = saved ? FavoritesChatHelper.displayName(activity) : AirTranscript.personName;
        setText(name, title);
        tile.bind(saved ? AirTileView.Kind.SAVED : AirTileView.Kind.PERSON, title, AirModel.Tone.index(publicKey));
        chevron.setVisibility(hasDossier() && !chevronDropped ? View.VISIBLE : View.GONE);
        band.setVisibility(View.GONE);
    }

    private void bindHeader(final Snapshot s)
    {
        setText(name, s.name);
        if (saved)
        {
            if (tile.kind() != AirTileView.Kind.SAVED)
            {
                tile.bind(AirTileView.Kind.SAVED, s.name, 0);
            }
        }
        else
        {
            if (!TextUtils.equals(headerTileTitle, s.name) || tile.kind() != AirTileView.Kind.PERSON)
            {
                headerTileTitle = s.name;
                tile.bind(AirTileView.Kind.PERSON, s.name, AirModel.Tone.index(publicKey));
                headerPhotoKey = null;
            }
            final String photoKey = photoKey(s.photo);
            if (!TextUtils.equals(headerPhotoKey, photoKey))
            {
                headerPhotoKey = photoKey;
                if (photoKey != null)
                {
                    tile.showPhoto(true);
                    ChatBubbleUiHelper.load_peer_photo(activity, s.photo, tile.photoView());
                }
                else
                {
                    tile.showPhoto(false);
                    try
                    {
                        GlideApp.with(activity).clear(tile.photoView());
                    }
                    catch (Exception ignored)
                    {
                    }
                }
            }
        }

        final String spokenPresence = presenceText(s);
        setDescription(profileTap, spokenPresence.isEmpty() ? s.name : s.name + ". " + spokenPresence);
        final boolean opens = hasDossier();
        if (headerOpens == null || headerOpens != opens)
        {
            // Notes to yourself, and a contact removed meanwhile: the name is a heading, not a button.
            headerOpens = opens;
            chevron.setVisibility(opens && !chevronDropped ? View.VISIBLE : View.GONE);
            profileTap.setClickable(opens);
            ViewCompat.setAccessibilityHeading(profileTap, !opens);
            if (opens)
            {
                AirDossier.describeToggle(profileTap, dossier.isOpen());
            }
            else
            {
                ViewCompat.replaceAccessibilityAction(profileTap,
                        AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, null, null);
            }
        }
    }

    private static String photoKey(final ChatBubbleUiHelper.AvatarMeta photo)
    {
        return photo != null && photo.loadable ? photo.path + "/" + photo.filename + "@" + photo.updateTs : null;
    }

    /** The band under the header: the link mark, the presence line and the last 24 hours. */
    private void bindBand(final Snapshot s)
    {
        if (saved || !s.exists)
        {
            band.setVisibility(View.GONE);
            return;
        }
        final long now = System.currentTimeMillis();
        final AirConversationModel.LinkState link = AirConversationModel.LinkState.of(s.reach);
        final boolean live = s.reach.isLive();
        final AirConversationModel.DayStrip dayStrip = AirConversationModel.DayStrip.make(s.hours, now,
                                                                                          TimeZone.getDefault());
        final String presenceWords = presenceText(s);
        linkMark.setState(link);
        setText(presence, presenceWords);
        if (shownStrip == null || !Arrays.equals(shownStrip, dayStrip.online) || shownStripHour != dayStrip.nowHour
            || shownStripLive != live)
        {
            shownStrip = dayStrip.online.clone();
            shownStripHour = dayStrip.nowHour;
            shownStripLive = live;
            strip.setStrip(dayStrip, live);
        }
        setText(stripHint, stripHintText(s.hours, dayStrip, now));
        final StringBuilder spoken = new StringBuilder();
        appendSentence(spoken, presenceWords);
        // Online, the presence line already names the link ("online since 9:40, direct link"); TalkBack
        // said it twice.
        final AirConversationModel.PresenceLine.Kind presenceKind = AirConversationModel.PresenceLine.of(
                s.reach, s.onlineSince, MessageListActivity.friend_is_typing).kind;
        if (presenceKind != AirConversationModel.PresenceLine.Kind.DIRECT
            && presenceKind != AirConversationModel.PresenceLine.Kind.DIRECT_SINCE
            && presenceKind != AirConversationModel.PresenceLine.Kind.RELAY
            && presenceKind != AirConversationModel.PresenceLine.Kind.RELAY_SINCE)
        {
            appendSentence(spoken, linkSpoken(link));
        }
        appendSentence(spoken, activity.getString(R.string.air_day_title) + ": " + stripSpoken(dayStrip, live));
        setDescription(band, spoken.toString());
        if (band.getVisibility() != View.VISIBLE)
        {
            // Opening and closing the dossier update it from then on.
            AirDossier.describeToggle(band, dossier != null && dossier.isOpen());
            band.setVisibility(View.VISIBLE);
        }
    }

    /** "online since 9:40, direct link", "offline for 25 minutes", "typing"; empty for notes to yourself. */
    private String presenceText(final Snapshot s)
    {
        if (saved || !s.exists)
        {
            return "";
        }
        final AirConversationModel.PresenceLine line = AirConversationModel.PresenceLine.of(
                s.reach, s.onlineSince, MessageListActivity.friend_is_typing);
        switch (line.kind)
        {
            case TYPING:
                return activity.getString(R.string.air_presence_typing);
            case DIRECT_SINCE:
                return activity.getString(R.string.air_presence_direct_since_format,
                                          AirUi.localDigits(activity, HelperGeneric.short_time_format(line.sinceMs)));
            case DIRECT:
                return activity.getString(R.string.air_presence_direct);
            case RELAY_SINCE:
                return activity.getString(R.string.air_presence_relay_since_format,
                                          AirUi.localDigits(activity, HelperGeneric.short_time_format(line.sinceMs)));
            case RELAY:
                return activity.getString(R.string.air_presence_relay);
            case PENDING:
                return activity.getString(R.string.air_presence_pending);
            case CONNECTING:
                return activity.getString(R.string.air_reach_connecting);
            case AWAY:
                return awayText(line.away);
            case EMPTY:
            default:
                return "";
        }
    }

    private String awayText(final AirModel.AwayWords words)
    {
        if (words == null)
        {
            return activity.getString(R.string.air_presence_away_long);
        }
        final int amount = (int) Math.max(1L, Math.min(Integer.MAX_VALUE, words.amount));
        switch (words.unit)
        {
            case JUST_NOW:
                return activity.getString(R.string.air_presence_away_just_now);
            case MINUTES:
                return quantity(R.plurals.air_presence_away_minutes, amount);
            case HOURS:
                return quantity(R.plurals.air_presence_away_hours, amount);
            case DAYS:
                return quantity(R.plurals.air_presence_away_days, amount);
            case LONG_AGO:
            default:
                return activity.getString(R.string.air_presence_away_long);
        }
    }

    private String linkSpoken(final AirConversationModel.LinkState link)
    {
        switch (link)
        {
            case DIRECT:
                return activity.getString(R.string.air_link_direct);
            case RELAY:
                return activity.getString(R.string.air_link_relay);
            case NONE:
            default:
                return activity.getString(R.string.air_link_none);
        }
    }

    /** The day strip for TalkBack: "online 7:00 to 9:00 and 21:00 to 22:00. Online now". */
    private String stripSpoken(final AirConversationModel.DayStrip dayStrip, final boolean live)
    {
        final List<String> parts = rangeTexts(dayStrip);
        String text;
        if (parts.isEmpty())
        {
            text = activity.getString(R.string.air_day_a11y_none);
        }
        else
        {
            String joined;
            try
            {
                joined = ListFormatter.getInstance(AirUi.locale(activity)).format(parts);
            }
            catch (Exception e)
            {
                joined = TextUtils.join(", ", parts);
            }
            text = activity.getString(R.string.air_day_a11y_format, joined);
        }
        if (live)
        {
            text += ". " + activity.getString(R.string.air_day_now_online);
        }
        return text;
    }

    /** Beside the strip: when they are usually online, or the hours of the last day, or why it is empty. */
    private String stripHintText(final Set<Long> hours, final AirConversationModel.DayStrip dayStrip, final long now)
    {
        final int[] best = AirConversationModel.bestHours(hours, now, TimeZone.getDefault());
        // "%d:00" and the "24" of the words: one script of digits in the line.
        switch (AirConversationModel.DayHint.of(best, dayStrip))
        {
            case BEST:
                return AirUi.localDigits(activity, activity.getString(R.string.air_day_best_format, best[0], best[1]));
            case RANGES:
                return AirUi.localDigits(activity, activity.getString(R.string.air_day_hint_ranges_format,
                                                                      TextUtils.join(", ", rangeTexts(dayStrip))));
            case EMPTY:
            default:
                return activity.getString(R.string.air_day_empty);
        }
    }

    private List<String> rangeTexts(final AirConversationModel.DayStrip dayStrip)
    {
        final List<String> parts = new ArrayList<>();
        for (final int[] range : dayStrip.ranges())
        {
            parts.add(activity.getString(R.string.air_day_range_format, range[0], range[1]));
        }
        return parts;
    }

    // ---- the line under the field ----

    /**
     * E21: a refusal ("The file is over 4 GB") or a failed send ("Not sent: ..."), on the line under the
     * field; false when this conversation has no field on screen to say it under.
     */
    boolean sayAtComposer(final CharSequence text, final boolean failed)
    {
        return sayAtComposer(text, failed, null);
    }

    /** {@link #sayAtComposer} for a failure with a code for support, opened from Details under the field. */
    boolean sayAtComposer(final CharSequence text, final boolean failed,
                          @androidx.annotation.Nullable final String code)
    {
        if (destroyed || composer.getVisibility() != View.VISIBLE)
        {
            return false;
        }
        notice.show(text, failed, code);
        updateHintRow();
        return true;
    }

    /** The line's own words once a notice is over. */
    private void redrawComposeHint()
    {
        final Snapshot s = shown;
        if (s != null)
        {
            updateCounter();
            bindComposeHint(s);
        }
        else
        {
            setText(hint, "");
            hintDot.setVisibility(View.GONE);
            updateHintRow();
        }
    }

    private void bindComposeHint(final Snapshot s)
    {
        if (notice.isShown())
        {
            // The notice has the line until it ends; it draws the hint again then.
            return;
        }
        final AirConversationModel.ComposeHint composeHint = AirConversationModel.ComposeHint.make(saved, s.exists,
                                                                                                  s.reach);
        final String shortName = AirConversationModel.shortName(s.name);
        final String text;
        switch (composeHint.kind)
        {
            case SAVED:
                text = activity.getString(R.string.air_compose_hint_saved);
                break;
            case LIVE:
                text = activity.getString(R.string.air_compose_hint_live_format, shortName);
                break;
            case WAIT:
                text = activity.getString(R.string.air_compose_hint_wait_format, shortName);
                break;
            case EMPTY:
            default:
                text = "";
                break;
        }
        setText(hint, text);
        switch (composeHint.dot)
        {
            case LIVE:
                showDot(R.color.kq_live);
                break;
            case TRANSIT:
                showDot(R.color.kq_transit);
                break;
            case NONE:
            default:
                hintDot.setVisibility(View.GONE);
                break;
        }
        updateHintRow();
    }

    private void showDot(final int colorRes)
    {
        ViewCompat.setBackgroundTintList(hintDot, ColorStateList.valueOf(AirUi.color(activity, colorRes)));
        hintDot.setVisibility(View.VISIBLE);
    }

    /**
     * The room left in the message once only a tenth of it remains. A long text to someone online whose
     * app puts pieces back together goes whole, so it has no limit; a correction holds less than a message.
     */
    private void updateCounter()
    {
        final Snapshot s = shown;
        String text = null;
        boolean over = false;
        if (!saved && s != null && s.exists)
        {
            final String body = HelperGeneric.normalize_chat_input_text(String.valueOf(field.getText()));
            if (!body.isEmpty())
            {
                final boolean edit = ChatReplyPreviewController.isEditActive();
                Integer remaining = null;
                if (edit)
                {
                    remaining = AirConversationModel.composeRemaining(utf8Length(body),
                                                                      HelperMessageEdit.MAX_EDIT_TEXT_BYTES);
                }
                else if (!s.canChunk)
                {
                    // The reply header travels in the same message.
                    remaining = AirConversationModel.composeRemaining(utf8Length(
                            MessageReplyHelper.encode(ChatReplyPreviewController.peekPending(), body)));
                }
                if (remaining != null)
                {
                    final int left = remaining;
                    over = left < 0;
                    text = left >= 0 ? quantity(R.plurals.air_compose_bytes_left, left)
                                     : quantity(edit ? R.plurals.air_compose_bytes_over_edit
                                                     : R.plurals.air_compose_bytes_over, -left);
                }
            }
        }
        if (text == null)
        {
            if (counter.getVisibility() != View.GONE)
            {
                counter.setVisibility(View.GONE);
            }
        }
        else
        {
            setText(counter, text);
            counter.setTextColor(AirUi.color(activity, over ? R.color.kq_danger : R.color.kq_ink_3));
            if (counter.getVisibility() != View.VISIBLE)
            {
                counter.setVisibility(View.VISIBLE);
            }
        }
        giveCounterTheLine(over);
        updateHintRow();
    }

    /**
     * Over the limit the counter takes the line under the field: it is then the one thing to act on.
     * Beside the hint a long sentence was clipped, because a weighted hint makes LinearLayout measure
     * the counter as if the row were empty.
     */
    private void giveCounterTheLine(final boolean over)
    {
        hint.setVisibility(over && !notice.isShown() ? View.GONE : View.VISIBLE);
        if (over)
        {
            hintDot.setVisibility(View.GONE);
        }
        else if (shown != null)
        {
            bindComposeHint(shown);
        }
        fitCounter(counter, over);
    }

    /** The counter fills the line when it has it alone, and keeps to its words beside the hint. */
    static void fitCounter(final TextView counter, final boolean alone)
    {
        final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) counter.getLayoutParams();
        final int width = alone ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT;
        final float weight = alone ? 1f : 0f;
        if (params.width != width || params.weight != weight)
        {
            params.width = width;
            params.weight = weight;
            counter.setLayoutParams(params);
        }
    }

    private void updateHintRow()
    {
        final boolean hasHint = hint.getVisibility() == View.VISIBLE && hint.getText().length() > 0;
        final boolean hasCounter = counter.getVisibility() == View.VISIBLE;
        final int visibility = hasHint || hasCounter ? View.VISIBLE : View.GONE;
        if (hintRow.getVisibility() != visibility)
        {
            hintRow.setVisibility(visibility);
        }
        final StringBuilder spoken = new StringBuilder();
        if (hasHint)
        {
            appendSentence(spoken, hint.getText().toString());
        }
        if (hasCounter)
        {
            appendSentence(spoken, counter.getText().toString());
        }
        setDescription(hintRow, spoken.toString());
    }

    private void describeSendButton()
    {
        setDescription(micSend, activity.getString(HelperGeneric.has_sendable_chat_text(field.getText())
                                                   ? R.string.air_compose_send : R.string.air_compose_voice));
    }

    /** Layout passes: the keyboard coming and going, and the reply or edit bar opening and closing. */
    private void onLayoutPass()
    {
        if (destroyed)
        {
            return;
        }
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(root);
        final boolean ime = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
        if (ime != keyboardUp)
        {
            keyboardUp = ime;
            // The words under the tray's heading step aside while the keyboard is up, as on iOS.
            if (!trayDeparting)
            {
                applyTrayExpanded(false);
            }
        }
        final boolean edit = ChatReplyPreviewController.isEditActive();
        final boolean reply = ChatReplyPreviewController.peekPending() != null;
        if (edit != editActive || reply != replyActive)
        {
            editActive = edit;
            replyActive = reply;
            updateCounter();
        }
    }

    // ---- the tray ----

    /**
     * Brings the tray in line with the messages. Returns true when it started to empty, which also
     * reloads the log.
     */
    private boolean bindTray(final Snapshot s, final boolean animate)
    {
        final List<TrayItem> items = s.tray;
        final List<Long> ids = new ArrayList<>(items.size());
        for (final TrayItem item : items)
        {
            ids.add(item.id);
        }
        if (animate && ids.isEmpty() && !trayIds.isEmpty() && s.reach.isLive() && tray.getVisibility() == View.VISIBLE
            && tray.isAttachedToWindow())
        {
            departTray();
            return true;
        }

        trayDetailText = trayDetail(s);
        final Set<Long> before = new HashSet<>(trayIds);
        final boolean idsChanged = !ids.equals(trayIds);
        trayIds = ids;
        trayItemsById.clear();
        for (final TrayItem item : items)
        {
            trayItemsById.put(item.id, item);
        }
        final boolean shownBefore = tray.getVisibility() == View.VISIBLE;
        final boolean showNow = !ids.isEmpty();
        final Runnable change = () -> {
            fillTrayRows(items, animate && shownBefore && trayExpanded, before);
            if (!showNow)
            {
                trayExpanded = false;
            }
            tray.setVisibility(showNow ? View.VISIBLE : View.GONE);
            applyTrayExpanded(false);
        };
        // The height moves when the tray comes or goes, or when its open list gains or loses a row.
        changeTray(change, animate && (shownBefore != showNow || (trayExpanded && idsChanged)));
        return false;
    }

    private String trayDetail(final Snapshot s)
    {
        if (saved || !s.exists)
        {
            return "";
        }
        return activity.getString(s.hasPush ? R.string.air_tray_wait_push_format : R.string.air_tray_wait_format,
                                  AirConversationModel.shortName(s.name));
    }

    private void fillTrayRows(final List<TrayItem> items, final boolean riseNew, final Set<Long> before)
    {
        final Map<Long, View> kept = new HashMap<>();
        trayItems.removeAllViews();
        final float rise = activity.getResources().getDimension(R.dimen.air_reveal_rise);
        for (final TrayItem item : items)
        {
            View row = trayRows.get(item.id);
            if (row == null)
            {
                row = newTrayRow(item.id);
            }
            bindTrayRow(row, item);
            row.animate().cancel();
            row.setAlpha(1f);
            row.setTranslationY(0f);
            trayItems.addView(row);
            kept.put(item.id, row);
            if (riseNew && !before.contains(item.id) && !AirUi.reducedMotion())
            {
                // A text just sent while the person is away rises into the open tray, as into the log.
                row.setAlpha(0f);
                row.setTranslationY(rise);
                row.animate().alpha(1f).translationY(0f).setInterpolator(easeOut)
                   .setDuration(activity.getResources().getInteger(R.integer.kq_dur_state)).start();
            }
        }
        trayRows.clear();
        trayRows.putAll(kept);
    }

    private View newTrayRow(final long id)
    {
        final View row = LayoutInflater.from(activity).inflate(R.layout.air_tray_item, trayItems, false);
        AirUi.markAsButton(row);
        row.setOnClickListener(v -> showTrayActions(id));
        return row;
    }

    private void bindTrayRow(final View row, final TrayItem item)
    {
        final TextView time = row.findViewById(R.id.air_tray_item_time);
        final TextView text = row.findViewById(R.id.air_tray_item_text);
        final ImageView mark = row.findViewById(R.id.air_tray_item_mark);
        setText(time, AirUi.localDigits(activity, item.time));
        final int column = AirTranscript.timeColumnWidth(time);
        final ViewGroup.LayoutParams timeParams = time.getLayoutParams();
        if (timeParams.width != column)
        {
            timeParams.width = column;
            time.setLayoutParams(timeParams);
        }
        setText(text, item.text);
        AirTranscript.setMarkIcon(mark, AirConversationModel.EntryStatus.TRANSIT);
        mark.setAlpha(AirTranscript.markAlpha(AirConversationModel.EntryStatus.TRANSIT));

        final StringBuilder spoken = new StringBuilder();
        appendPart(spoken, item.text);
        appendPart(spoken, item.time);
        appendPart(spoken, AirTranscript.statusWord(activity, AirConversationModel.EntryStatus.TRANSIT));
        appendSentence(spoken, activity.getString(R.string.air_tray_item_hint));
        setDescription(row, spoken.toString());

        // The same two actions without the dialog, as custom actions.
        final Object previous = row.getTag(R.id.air_tray_actions);
        if (previous instanceof int[])
        {
            for (final int actionId : (int[]) previous)
            {
                ViewCompat.removeAccessibilityAction(row, actionId);
            }
        }
        final List<Integer> added = new ArrayList<>(2);
        final long id = item.id;
        if (item.editable)
        {
            added.add(ViewCompat.addAccessibilityAction(row, activity.getString(R.string.air_tray_edit), (view, arguments) -> {
                final TrayItem current = trayItemsById.get(id);
                if (current != null && !trayDeparting)
                {
                    editTrayItem(current);
                }
                return true;
            }));
        }
        added.add(ViewCompat.addAccessibilityAction(row, activity.getString(R.string.air_tray_cancel), (view, arguments) -> {
            if (!trayDeparting)
            {
                cancelTrayItem(id);
            }
            return true;
        }));
        final int[] ids = new int[added.size()];
        for (int i = 0; i < ids.length; i++)
        {
            ids[i] = added.get(i);
        }
        row.setTag(R.id.air_tray_actions, ids);
    }

    /** The heading, the words under it and the list follow the open state; the chevron turns over. */
    private void applyTrayExpanded(final boolean turnChevron)
    {
        final int count = trayIds.size();
        final String title = trayExpanded ? quantity(R.plurals.air_tray_head_format, count)
                                          : activity.getString(R.string.air_transit_format, count);
        setText(trayTitle, title);
        trayClock.setVisibility(trayExpanded ? View.GONE : View.VISIBLE);
        setText(trayDetail, trayDetailText);
        trayDetail.setVisibility(trayExpanded && !keyboardUp && !trayDetailText.isEmpty() ? View.VISIBLE : View.GONE);
        trayScroll.setVisibility(trayExpanded && count > 0 ? View.VISIBLE : View.GONE);
        final float rotation = trayExpanded ? 180f : 0f;
        trayChevron.animate().cancel();
        if (turnChevron && !AirUi.reducedMotion())
        {
            trayChevron.animate().rotation(rotation).setInterpolator(easeOut)
                       .setDuration(activity.getResources().getInteger(R.integer.kq_dur_state)).start();
        }
        else
        {
            trayChevron.setRotation(rotation);
        }
        setDescription(trayHead, trayExpanded && !trayDetailText.isEmpty() ? title + ". " + trayDetailText : title);
        final String action = activity.getString(trayExpanded ? R.string.air_tray_collapse_hint
                                                              : R.string.air_tray_expand_hint);
        if (!action.equals(trayHeadAction))
        {
            trayHeadAction = action;
            ViewCompat.replaceAccessibilityAction(trayHead,
                    AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK, action, null);
        }
    }

    private void setTrayExpanded(final boolean expanded, final boolean animate)
    {
        if (trayDeparting || expanded == trayExpanded || trayIds.isEmpty())
        {
            return;
        }
        trayExpanded = expanded;
        changeTray(() -> applyTrayExpanded(animate), animate);
    }

    /**
     * Runs {@code change} on the tray and moves its height from where it was to where the change leaves it,
     * over the layout duration; at once under reduced motion.
     */
    private void changeTray(final Runnable change, final boolean animate)
    {
        final boolean wasShown = tray.getVisibility() == View.VISIBLE;
        final int from = wasShown ? tray.getHeight() : 0;
        if (trayHeight != null)
        {
            final ValueAnimator running = trayHeight;
            trayHeight = null;
            running.cancel();
        }
        setTrayLayout(ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        change.run();
        final boolean shownNow = tray.getVisibility() == View.VISIBLE;
        if (!animate || AirUi.reducedMotion() || !tray.isAttachedToWindow() || conversation.getWidth() <= 0
            || (!wasShown && !shownNow))
        {
            return;
        }
        final int to = shownNow ? measureTray() : 0;
        if (to == from)
        {
            return;
        }
        tray.setVisibility(View.VISIBLE);
        final boolean grows = !wasShown;
        setTrayLayout(from, grows ? 0f : 1f);
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(activity.getResources().getInteger(R.integer.kq_dur_layout));
        animator.setInterpolator(easeOut);
        animator.addUpdateListener(a -> {
            final float f = (float) a.getAnimatedValue();
            final float margin = grows ? f : (shownNow ? 1f : 1f - f);
            setTrayLayout(Math.round(from + (to - from) * f), margin);
        });
        animator.addListener(new AnimatorListenerAdapter()
        {
            private boolean cancelled = false;

            @Override
            public void onAnimationCancel(final Animator animation)
            {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(final Animator animation)
            {
                if (trayHeight == animator)
                {
                    trayHeight = null;
                }
                if (cancelled || destroyed)
                {
                    return;
                }
                if (!shownNow)
                {
                    tray.setVisibility(View.GONE);
                }
                setTrayLayout(ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            }
        });
        trayHeight = animator;
        animator.start();
    }

    private void setTrayLayout(final int height, final float marginFraction)
    {
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tray.getLayoutParams();
        final int margin = Math.round(trayBottomMargin * Math.max(0f, Math.min(1f, marginFraction)));
        if (params.height != height || params.bottomMargin != margin)
        {
            params.height = height;
            params.bottomMargin = margin;
            tray.setLayoutParams(params);
        }
    }

    private int measureTray()
    {
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) tray.getLayoutParams();
        final int width = Math.max(0, conversation.getWidth() - params.getMarginStart() - params.getMarginEnd());
        tray.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                     View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return tray.getMeasuredHeight();
    }

    /**
     * The person came online: the rows leave the tray one by one, 90 ms apart, fading up, and rise into the
     * log in the same order; then the tray folds. Under reduced motion they go together, without moving.
     */
    private void departTray()
    {
        final List<Long> leaving = new ArrayList<>(trayIds);
        trayDeparting = true;
        trayHead.setEnabled(false);
        trayIds = new ArrayList<>();
        for (final Long id : leaving)
        {
            AirTranscript.markArrival(activity, id);
        }
        reloadLog();

        final Resources res = activity.getResources();
        final boolean reduce = AirUi.reducedMotion();
        final long step = reduce ? 0L : res.getInteger(R.integer.kq_dur_stagger);
        final long duration = AirUi.duration(activity, R.integer.kq_dur_state);
        final float rise = res.getDimension(R.dimen.air_reveal_rise);
        int index = 0;
        // A folded tray shows only its heading: it folds away at once, the log shows the texts rising.
        if (trayExpanded)
        {
            for (final Long id : leaving)
            {
                final View row = trayRows.get(id);
                if (row == null)
                {
                    continue;
                }
                row.animate().cancel();
                row.animate().alpha(0f).translationY(reduce ? 0f : -rise).setStartDelay(step * index)
                   .setDuration(duration).setInterpolator(easeOut).start();
                index++;
            }
        }
        MAIN.postDelayed(foldTray, index == 0 ? 0L : step * (index - 1) + duration);
        announce(R.string.air_tray_departed);
    }

    private void foldDepartedTray()
    {
        if (destroyed)
        {
            return;
        }
        changeTray(() -> tray.setVisibility(View.GONE), true);
        // A frame after the fold, so the next tray (the person may already be gone again) starts clean.
        MAIN.postDelayed(finishDepart, AirUi.duration(activity, R.integer.kq_dur_layout) + 16L);
    }

    private void finishDepart()
    {
        if (destroyed)
        {
            return;
        }
        trayDeparting = false;
        trayHead.setEnabled(true);
        trayExpanded = false;
        for (final View row : trayRows.values())
        {
            row.animate().cancel();
        }
        trayRows.clear();
        trayItemsById.clear();
        trayItems.removeAllViews();
        applyTrayExpanded(false);
        if (shown != null)
        {
            bindTray(shown, true);
        }
    }

    private void showTrayActions(final long id)
    {
        final TrayItem item = trayItemsById.get(id);
        if (item == null || trayDeparting)
        {
            return;
        }
        final List<CharSequence> labels = new ArrayList<>(2);
        final List<Runnable> actions = new ArrayList<>(2);
        if (item.editable)
        {
            labels.add(activity.getString(R.string.air_tray_edit));
            actions.add(() -> editTrayItem(item));
        }
        final SpannableString cancel = new SpannableString(activity.getString(R.string.air_tray_cancel));
        cancel.setSpan(new ForegroundColorSpan(AirUi.color(activity, R.color.kq_danger)), 0, cancel.length(),
                       Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        labels.add(cancel);
        actions.add(() -> cancelTrayItem(id));
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setItems(labels.toArray(new CharSequence[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(R.string.cancel, null)
                .create();
        showDialog(dialog, false);
    }

    private void editTrayItem(final TrayItem item)
    {
        final EditText input = dialogField(item.body, true);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.air_tray_edit_title)
                .setView(dialogBox(input))
                .setPositiveButton(R.string.air_tray_save, null)
                .setNegativeButton(R.string.cancel, (d, which) -> hideKeyboardFrom(input))
                .create();
        dialog.setOnShowListener(d -> {
            final Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(v -> {
                final String text = HelperGeneric.normalize_chat_input_text(String.valueOf(input.getText()));
                if (text.trim().isEmpty())
                {
                    return;
                }
                // A text that never left goes as a new message; one that left goes as a correction.
                final int limit = item.neverLeft ? AirConversationModel.COMPOSE_LIMIT_BYTES
                                                 : HelperMessageEdit.MAX_EDIT_TEXT_BYTES;
                final int over = utf8Length(text) - limit;
                if (over > 0)
                {
                    input.setError(quantity(R.plurals.air_compose_bytes_over_edit, over));
                    return;
                }
                hideKeyboardFrom(input);
                dialog.dismiss();
                saveTrayEdit(item.id, text);
            });
        });
        showKeyboardWith(dialog);
        showDialog(dialog, false);
        input.requestFocus();
    }

    private void saveTrayEdit(final long id, final String text)
    {
        final String key = publicKey;
        worker().post(() -> {
            int problem = 0;
            try
            {
                synchronized (UNSENT_LOCK)
                {
                    final Message m = readMessage(id);
                    if (m == null || m.read || !AirRepository.isWaitingDirectText(m))
                    {
                        problem = R.string.air_tray_already_left;
                    }
                    else if (neverLeft(m))
                    {
                        // Still on this device only: the service loop sends whatever the row holds when it
                        // takes it, under the same lock. The person gets it as written, not as a correction.
                        TrifaToxService.orma.updateMessage().idEq(id).text(text).execute();
                    }
                    else
                    {
                        final int blocked = HelperMessageEdit.directEditBlockReason(m, System.currentTimeMillis());
                        if (blocked != 0)
                        {
                            problem = blocked;
                        }
                        else if (!HelperMessageEdit.sendOwnFriendEdit(
                                HelperFriend.tox_friend_by_public_key__wrapper(key), m, text))
                        {
                            problem = R.string.chat_edit_failed;
                        }
                    }
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "trayEdit:EE:" + e.getMessage());
                problem = R.string.chat_edit_failed;
            }
            AirRepository.requestRefresh();
            final int message = problem;
            if (message != 0)
            {
                MAIN.post(() -> sayTrayProblem(message));
            }
        });
    }

    private void cancelTrayItem(final long id)
    {
        worker().post(() -> {
            Message removed = null;
            int problem = 0;
            try
            {
                synchronized (UNSENT_LOCK)
                {
                    final Message m = readMessage(id);
                    if (m != null && m.read)
                    {
                        problem = R.string.air_tray_already_left;
                    }
                    else if (m != null)
                    {
                        // Gone here, and taken back from the person's device if a copy ever went out.
                        if (!neverLeft(m))
                        {
                            HelperMessageDelete.sendOwnFriendDelete(m);
                        }
                        TrifaToxService.orma.deleteFromMessage().idEq(id).execute();
                        removed = m;
                    }
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "trayCancel:EE:" + e.getMessage());
            }
            AirRepository.requestRefresh();
            if (removed != null)
            {
                try
                {
                    HelperFriend.update_single_friend_in_friendlist_view(
                            HelperFriend.main_get_friend(removed.tox_friendpubkey));
                }
                catch (Exception ignored)
                {
                }
            }
            final Message gone = removed;
            final int message = problem;
            MAIN.post(() -> {
                if (gone != null)
                {
                    // The person may have come online meanwhile and the text stood in the log already.
                    final MessageListFragment fragment = fragment();
                    if (fragment != null && fragment.adapter != null)
                    {
                        fragment.adapter.remove_item(gone);
                    }
                }
                if (message != 0)
                {
                    sayTrayProblem(message);
                }
            });
        });
    }

    private static Message readMessage(final long id)
    {
        if (TrifaToxService.orma == null)
        {
            return null;
        }
        final List<Message> rows = TrifaToxService.orma.selectFromMessage().idEq(id).toList();
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    // ---- statics for the activity and the transcript ----

    /**
     * Stores a text as unsent (message id -1) without touching toxcore, for the service loop to send once
     * our own node and the person are online. Returns the row id, or -1 when nothing could be stored.
     */
    static long queueUnsentText(final String friendPublicKey, final String text)
    {
        if (TrifaToxService.orma == null || TextUtils.isEmpty(friendPublicKey) || TextUtils.isEmpty(text))
        {
            return -1L;
        }
        final Message m = new Message();
        m.tox_friendpubkey = friendPublicKey;
        m.direction = 1;
        m.TOX_MESSAGE_TYPE = 0;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.rcvd_timestamp = 0L;
        m.is_new = false;
        m.sent_timestamp = System.currentTimeMillis();
        m.read = false;
        m.text = text;
        m.msg_version = 0;
        m.resend_count = 0;
        m.sent_push = 0;
        m.msg_idv3_hash = "";
        m.msg_id_hash = "";
        m.raw_msgv2_bytes = "";
        m.message_id = -1L;
        final long rowId = HelperMessage.insert_into_message_db(m, true);
        m.id = rowId;
        return rowId;
    }

    /**
     * "Retry" on a text the delivery watchdog gave up on. With the person online it goes again now; with
     * them away it returns to the tray with a fresh resend budget and leaves with the rest when they come
     * online. The row is read again first: it may have been delivered meanwhile.
     */
    static void retryEntry(final Context context, final Message entry)
    {
        if (context == null || entry == null || entry.id <= 0L)
        {
            return;
        }
        final Context app = context.getApplicationContext();
        final long id = entry.id;
        worker().post(() -> {
            try
            {
                final Message m = readMessage(id);
                if (m == null)
                {
                    return;
                }
                if (!MessageDeliveryRetryHelper.isDirectFailed(m))
                {
                    HelperMessage.update_single_message(m, true);
                    return;
                }
                final long friendNumber = HelperFriend.tox_friend_by_public_key__wrapper(m.tox_friendpubkey);
                if (friendNumber >= 0 && HelperFriend.is_friend_online_real(friendNumber) != 0)
                {
                    MessageDeliveryRetryHelper.retryDirectMessage(app, m);
                    return;
                }
                TrifaToxService.orma.updateMessage().idEq(id).send_retries(0).resend_count(1).execute();
                m.send_retries = 0;
                m.resend_count = 1;
                if (!HelperFriend.is_self_tox_connected_to_dht())
                {
                    TrifaToxService.requestManualReconnect();
                }
                HelperMessage.update_single_message(m, true);
                AirRepository.requestRefresh();
            }
            catch (Exception e)
            {
                Log.i(TAG, "retryEntry:EE:" + e.getMessage());
            }
        });
    }

    /**
     * The send button: the ink plate with the arrow, as tall as the field. The arrow keeps its own size,
     * the size of the microphone it replaces, and sits in the middle of the plate.
     */
    static Drawable sendIcon(final Context context)
    {
        final int side = context.getResources().getDimensionPixelSize(R.dimen.kq_size_field);
        final GradientDrawable plate = inkPlate(context);
        plate.setSize(side, side);
        final Drawable arrow = DrawableCompat.wrap(
                Objects.requireNonNull(ContextCompat.getDrawable(context, R.drawable.kq_ic_arrow_up)).mutate());
        DrawableCompat.setTint(arrow, AirUi.color(context, R.color.kq_on_ink));
        final LayerDrawable layers = new LayerDrawable(new Drawable[]{plate, arrow});
        layers.setLayerSize(1, Math.min(side, arrow.getIntrinsicWidth()), Math.min(side, arrow.getIntrinsicHeight()));
        layers.setLayerGravity(1, Gravity.CENTER);
        return layers;
    }

    /** The microphone; its tint comes from the button (R.id.air_mic_tint). */
    static Drawable micIcon(final Context context)
    {
        return Objects.requireNonNull(ContextCompat.getDrawable(context, R.drawable.kq_ic_mic)).mutate();
    }

    private static GradientDrawable inkPlate(final Context context)
    {
        final GradientDrawable plate = new GradientDrawable();
        plate.setShape(GradientDrawable.RECTANGLE);
        plate.setColor(AirUi.color(context, R.color.kq_ink));
        plate.setCornerRadius(context.getResources().getDimension(R.dimen.kq_radius_control));
        return plate;
    }

    // ---- the look of the parts the activity binds ----

    /** The bars and the ground of a conversation: the surface, with dark icons in the light theme. */
    @SuppressWarnings("deprecation")
    static void styleWindow(final Activity activity, final View root)
    {
        final int surface = AirUi.color(activity, R.color.kq_surface);
        final boolean night = HelperGeneric.is_nightmode_active(activity);
        final Window window = activity.getWindow();
        window.setStatusBarColor(surface);
        window.setNavigationBarColor(surface);
        final WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(window, window.getDecorView());
        bars.setAppearanceLightStatusBars(!night);
        bars.setAppearanceLightNavigationBars(!night);
        root.setBackgroundColor(surface);
    }

    /** The header in the design's colours; MessageListActivity.apply_chat_header calls it. */
    void styleHeader()
    {
        final int ink = AirUi.color(activity, R.color.kq_ink);
        toolbar.setBackgroundColor(AirUi.color(activity, R.color.kq_surface));
        final Drawable navigation = toolbar.getNavigationIcon();
        if (navigation != null)
        {
            DrawableCompat.setTint(navigation.mutate(), ink);
        }
        final Drawable overflow = toolbar.getOverflowIcon();
        if (overflow != null)
        {
            DrawableCompat.setTint(overflow.mutate(), ink);
        }
        final ActionBar bar = activity.getSupportActionBar();
        if (bar != null)
        {
            bar.setDisplayShowTitleEnabled(false);
        }
        name.setTextColor(ink);
        profileTap.setBackgroundResource(R.drawable.air_bg_icon_button);
        final ColorStateList iconTint = ContextCompat.getColorStateList(activity, R.color.air_icon_button_tint);
        phone.setImageResource(R.drawable.kq_ic_phone);
        ImageViewCompat.setImageTintList(phone, iconTint);
        video.setImageResource(R.drawable.kq_ic_video);
        ImageViewCompat.setImageTintList(video, iconTint);
    }

    /** The composer of a conversation, one person's or a group's, over what the activity bound. */
    static void styleComposer(final Activity activity, final View composer, final TextView field, final View micSend)
    {
        composer.setBackgroundResource(R.drawable.air_bg_composer);
        field.setTextColor(AirUi.color(activity, R.color.kq_ink));
        field.setHintTextColor(AirUi.color(activity, R.color.kq_ink_3));
        field.setLineSpacing(0f, 1f);
        TextViewCompat.setLineHeight(field, activity.getResources().getDimensionPixelSize(R.dimen.kq_line_body));
        micSend.setBackgroundResource(R.drawable.air_bg_composer_button);
        final View emoji = activity.findViewById(R.id.insert_emoji);
        if (emoji != null)
        {
            emoji.setContentDescription(activity.getString(R.string.emoji));
        }
    }

    /** No coloured stripe at the side: a sunken plate with the design's words and close button. */
    static void styleReplyBar(final Activity activity)
    {
        final View bar = activity.findViewById(R.id.chat_reply_preview_bar);
        if (bar == null)
        {
            return;
        }
        final Resources res = activity.getResources();
        bar.setPaddingRelative(res.getDimensionPixelSize(R.dimen.kq_space_16), res.getDimensionPixelSize(R.dimen.kq_space_6),
                               res.getDimensionPixelSize(R.dimen.kq_space_4), res.getDimensionPixelSize(R.dimen.kq_space_6));
        final View accent = activity.findViewById(R.id.chat_reply_accent_bar);
        if (accent != null)
        {
            accent.setVisibility(View.GONE);
        }
        final TextView header = activity.findViewById(R.id.chat_reply_header);
        if (header != null)
        {
            TextViewCompat.setTextAppearance(header, R.style.TextAppearance_KQ_Label);
            header.setTextColor(AirUi.color(activity, R.color.kq_ink));
        }
        final TextView preview = activity.findViewById(R.id.chat_reply_preview_text);
        if (preview != null)
        {
            TextViewCompat.setTextAppearance(preview, R.style.TextAppearance_KQ_Caption);
            preview.setTextColor(AirUi.color(activity, R.color.kq_ink_2));
        }
        final ImageButton close = activity.findViewById(R.id.chat_reply_cancel);
        if (close != null)
        {
            close.setImageResource(R.drawable.kq_ic_x);
            close.setBackgroundResource(R.drawable.air_bg_icon_button);
            close.setScaleType(ImageView.ScaleType.CENTER);
            close.setPadding(0, 0, 0, 0);
            ImageViewCompat.setImageTintList(close, ContextCompat.getColorStateList(activity,
                                                                                  R.color.air_icon_button_tint));
        }
    }

    /** The recording bar: a still red dot, the time in ink, "Cancel" in the second ink, the ink plate. */
    static void styleVoiceBar(final Activity activity, final ChatVoiceRecordingUiHelper helper)
    {
        if (helper != null)
        {
            helper.airStyle = true;
        }
        final View bar = activity.findViewById(R.id.voice_recording_bar);
        if (bar == null)
        {
            return;
        }
        bar.setBackgroundColor(AirUi.color(activity, R.color.kq_surface));
        final View emoji = activity.findViewById(R.id.voice_recording_emoji);
        if (emoji != null)
        {
            emoji.setVisibility(View.GONE);
        }
        final View dot = activity.findViewById(R.id.voice_recording_dot);
        if (dot != null)
        {
            dot.setBackgroundResource(R.drawable.air_bg_dot);
            ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(AirUi.color(activity, R.color.kq_danger)));
            final ViewGroup.LayoutParams params = dot.getLayoutParams();
            final int side = activity.getResources().getDimensionPixelSize(R.dimen.kq_size_dot);
            params.width = side;
            params.height = side;
            dot.setLayoutParams(params);
        }
        final TextView timer = activity.findViewById(R.id.voice_recording_timer);
        if (timer != null)
        {
            TextViewCompat.setTextAppearance(timer, R.style.TextAppearance_KQ_Body);
            timer.setFontFeatureSettings("tnum");
            timer.setTextColor(AirUi.color(activity, R.color.kq_ink));
        }
        final TextView cancel = activity.findViewById(R.id.voice_recording_cancel);
        if (cancel != null)
        {
            TextViewCompat.setTextAppearance(cancel, R.style.TextAppearance_KQ_Label);
            cancel.setTextColor(AirUi.color(activity, R.color.kq_ink_2));
            cancel.setBackgroundResource(R.drawable.air_bg_icon_button);
        }
        final ImageButton send = activity.findViewById(R.id.voice_recording_send);
        if (send != null)
        {
            // A 48dp touch target around the plate the height of the field, as the send button.
            final Resources res = activity.getResources();
            final int target = res.getDimensionPixelSize(R.dimen.kq_size_target_android);
            final int inset = Math.max(0, (target - res.getDimensionPixelSize(R.dimen.kq_size_field)) / 2);
            send.setBackground(new InsetDrawable(inkPlate(activity), inset));
            send.setImageResource(R.drawable.kq_ic_mic);
            send.setScaleType(ImageView.ScaleType.CENTER);
            send.setPadding(0, 0, 0, 0);
            ImageViewCompat.setImageTintList(send, ColorStateList.valueOf(AirUi.color(activity, R.color.kq_on_ink)));
            final ViewGroup.LayoutParams params = send.getLayoutParams();
            params.width = target;
            params.height = target;
            send.setLayoutParams(params);
            if (send.getParent() instanceof View)
            {
                ((View) send.getParent()).setBackground(null);
            }
        }
    }

    // ---- the dossier ----

    private AirDossier.Content dossierContent(final Snapshot s)
    {
        final long now = System.currentTimeMillis();
        final String shortName = AirConversationModel.shortName(s.name);
        final AirConversationModel.LinkState link = AirConversationModel.LinkState.of(s.reach);
        final String linkText;
        switch (link)
        {
            case DIRECT:
                linkText = activity.getString(R.string.air_dossier_link_direct);
                break;
            case RELAY:
                linkText = activity.getString(R.string.air_dossier_link_relay);
                break;
            case NONE:
            default:
                linkText = activity.getString(R.string.air_dossier_link_none);
                break;
        }
        final StringBuilder statusLines = new StringBuilder(s.ownName);
        if (!s.status.isEmpty())
        {
            if (statusLines.length() > 0)
            {
                statusLines.append('\n');
            }
            statusLines.append(s.status);
        }
        final boolean live = s.reach.isLive();
        final AirConversationModel.DayStrip dayStrip = AirConversationModel.DayStrip.make(s.hours, now,
                                                                                          TimeZone.getDefault());
        final int[] best = AirConversationModel.bestHours(s.hours, now, TimeZone.getDefault());
        final String bestText = best == null ? "" : AirUi.localDigits(
                activity, activity.getString(R.string.air_dossier_best_format, best[0], best[1]));
        final String notifications = s.hasPush ? activity.getString(R.string.air_dossier_push_on_format, shortName)
                                               : activity.getString(R.string.air_dossier_push_off);
        final String verification = s.verifiedMs > 0L
                                    ? activity.getString(R.string.air_dossier_verified_format, dayText(s.verifiedMs))
                                    : activity.getString(R.string.air_dossier_not_verified);
        return new AirDossier.Content(s.name, statusLines.toString(), AirModel.Tone.index(publicKey), s.photo, link,
                                      linkText, dayStrip, live, stripSpoken(dayStrip, live), bestText, notifications,
                                      s.muted, verification, s.verifiedMs > 0L, publicKey);
    }

    /** The month in words, as the day marks write it: "11 November 2026" reads the same everywhere. */
    private String dayText(final long ms)
    {
        final Locale locale = AirUi.locale(activity);
        return new SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "dMMMMy"), locale).format(new Date(ms));
    }

    @Override
    public void onDossierCloseRequested()
    {
        closeDossier(true);
    }

    @Override
    public void onDossierMute(final boolean muted)
    {
        final String key = publicKey;
        worker().post(() -> {
            try
            {
                // The contact's own notification switch, which the notifications already obey.
                TrifaToxService.orma.updateFriendList().tox_public_key_stringEq(key).notification_silent(muted).execute();
                final FriendListFragment list = MainActivity.friend_list_fragment;
                if (list != null)
                {
                    list.add_all_friends_clear(0);
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "mute:EE:" + e.getMessage());
            }
            AirRepository.requestRefresh();
        });
    }

    @Override
    public void onDossierVerify()
    {
        if (publicKey == null)
        {
            return;
        }
        final LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(dialogText(AirUi.localDigits(activity, activity.getString(R.string.air_verify_message)),
                                   R.style.TextAppearance_KQ_Body, R.color.kq_ink_2));
        final TextView key = dialogText(AirConversationModel.keyLines(publicKey, 4), R.style.TextAppearance_KQ_Mono,
                                        R.color.kq_ink);
        key.setTextDirection(View.TEXT_DIRECTION_LTR);
        key.setContentDescription(activity.getString(R.string.air_dossier_key_title) + ": "
                                  + TextUtils.join(" ", AirConversationModel.keyGroups(publicKey)));
        final LinearLayout.LayoutParams keyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        keyParams.topMargin = activity.getResources().getDimensionPixelSize(R.dimen.kq_space_12);
        content.addView(key, keyParams);
        final String marked = publicKey;
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.air_verify_title)
                .setView(dialogBox(content))
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.air_verify_match, (d, which) -> worker().post(
                        () -> AirContactMarks.setVerified(marked, System.currentTimeMillis())))
                .create();
        showDialog(dialog, false);
    }

    @Override
    public void onDossierUnverify()
    {
        final String key = publicKey;
        if (key != null)
        {
            worker().post(() -> AirContactMarks.setVerified(key, 0L));
        }
    }

    @Override
    public void onDossierCopyKey()
    {
        final ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || publicKey == null)
        {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.air_dossier_key_title), publicKey));
        // From Android 13 the system shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        {
            toast(R.string.air_dossier_key_copied);
        }
        else
        {
            announce(R.string.air_dossier_key_copied);
        }
    }

    @Override
    public void onDossierShowQr()
    {
        final Snapshot s = shown;
        final String key = publicKey;
        if (s == null || key == null)
        {
            return;
        }
        final String heading = activity.getString(R.string.air_qr_key_heading_format,
                                                  AirConversationModel.shortName(s.name));
        worker().post(() -> {
            Bitmap bitmap = null;
            try
            {
                bitmap = ProfileContentFragment.encodeAsBitmap(key);
            }
            catch (Exception e)
            {
                Log.i(TAG, "qr:EE:" + e.getMessage());
            }
            final Bitmap qr = bitmap;
            if (qr != null)
            {
                MAIN.post(() -> showQr(qr, heading, key));
            }
        });
    }

    private void showQr(final Bitmap qr, final String heading, final String key)
    {
        if (destroyed || activity.isFinishing())
        {
            return;
        }
        AirKeyQr.showBitmap(activity, qr, heading, AirUi.localDigits(activity, activity.getString(R.string.air_qr_key_subtitle)),
                            key);
    }

    @Override
    public void onDossierRename()
    {
        final Snapshot s = shown;
        final String key = publicKey;
        if (s == null || key == null)
        {
            return;
        }
        final EditText input = dialogField(s.alias, false);
        // The name an empty field brings back: theirs, under the nickname or in its place.
        input.setHint(s.alias.isEmpty() ? s.name : s.ownName);
        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.air_dossier_rename_title)
                .setView(dialogBox(input))
                .setNegativeButton(R.string.cancel, (d, which) -> hideKeyboardFrom(input))
                .setPositiveButton(R.string.air_tray_save, (d, which) -> {
                    hideKeyboardFrom(input);
                    // An empty nickname brings their own name back.
                    final String alias = String.valueOf(input.getText()).trim();
                    worker().post(() -> {
                        try
                        {
                            TrifaToxService.orma.updateFriendList().tox_public_key_stringEq(key).alias_name(alias)
                                    .execute();
                            final FriendListFragment list = MainActivity.friend_list_fragment;
                            if (list != null)
                            {
                                list.add_all_friends_clear(0);
                            }
                        }
                        catch (Exception e)
                        {
                            Log.i(TAG, "rename:EE:" + e.getMessage());
                        }
                        AirRepository.requestRefresh();
                    });
                })
                .create();
        showKeyboardWith(dialog);
        showDialog(dialog, false);
        input.requestFocus();
    }

    @Override
    public void onDossierFiles()
    {
        closeDossier(true);
        activity.show_all_attachments();
    }

    @Override
    public void onDossierRemove()
    {
        final Snapshot s = shown;
        final String key = publicKey;
        if (s == null || key == null)
        {
            return;
        }
        // E21: asked from below, the verb on the button in the danger colour.
        AirQuestion.ask(activity, activity.getString(R.string.air_dossier_remove_title_format, s.name),
                        activity.getString(R.string.air_dossier_remove_message),
                        activity.getString(R.string.air_dossier_remove), true, () -> removeContact(key));
    }

    @Override
    public void onDossierBlock()
    {
        final Snapshot s = shown;
        final String key = publicKey;
        if (s == null || key == null)
        {
            return;
        }
        // E21: asked from below, the verb on the button in the danger colour.
        AirQuestion.ask(activity, activity.getString(R.string.air_dossier_block_title_format, s.name),
                        activity.getString(R.string.air_dossier_block_message),
                        activity.getString(R.string.air_dossier_block), true, () -> {
                    // The key goes on the list first, so a request that lands while the contact is being
                    // removed is already dropped; then the contact goes the way Remove takes it.
                    AirBlockList.block(key, System.currentTimeMillis());
                    AirRequestStore.remove(key);
                    removeContact(key);
                });
    }

    /** As the contacts list removes one: messages, files and the contact itself, then the chat closes. */
    private void removeContact(final String key)
    {
        closeDossier(false);
        // Leaving with an empty field, so onPause stores no draft for a contact that is gone.
        HelperGeneric.clear_chat_input_field(field);
        ChatDraftHelper.clear_friend_draft(key);
        if (LOG_FRIEND_TOXID != null && key.equalsIgnoreCase(LOG_FRIEND_TOXID))
        {
            HelperGeneric.del_g_opts(LOGFRIEND_ON_STARTUP_DONE_DB_KEY);
            HelperGeneric.del_g_opts(LOGFRIEND_TOXID_DB_KEY);
        }
        FriendListHolder.delete_friend_now(key);
        activity.finish();
    }

    @Override
    public void onAirContactMarksChanged()
    {
        scheduleRead();
    }

    // ---- small helpers ----

    private MessageListFragment fragment()
    {
        final Fragment found = activity.getSupportFragmentManager().findFragmentById(R.id.ml_fragment);
        return found instanceof MessageListFragment ? (MessageListFragment) found : null;
    }

    private void reloadLog()
    {
        final MessageListFragment current = fragment();
        if (current != null)
        {
            current.update_all_messages_async(false, true);
        }
    }

    /** The authors over the runs follow a new name without reloading the log. */
    private void refreshEntries()
    {
        final MessageListFragment current = fragment();
        if (current != null)
        {
            current.refresh_air_entries();
        }
    }

    private void hideKeyboard()
    {
        final InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null)
        {
            input.hideSoftInputFromWindow(field.getWindowToken(), 0);
        }
    }

    /**
     * A dialog with a field opens with the keyboard. The conversation's window keeps a keyboard it did not
     * ask for (it is STATE_HIDDEN, and while the dossier is open no field there has focus), so the dialog
     * takes the keyboard down itself before it closes (hideKeyboardFrom in its buttons; Back closes the
     * keyboard first), and a tap outside does not close it, as with an alert with a field on iOS.
     */
    private static void showKeyboardWith(final AlertDialog dialog)
    {
        final Window window = dialog.getWindow();
        if (window != null)
        {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.setCanceledOnTouchOutside(false);
    }

    /** Takes the keyboard down from the dialog field it serves, while that dialog is still showing. */
    private void hideKeyboardFrom(final View dialogField)
    {
        final InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (input != null)
        {
            input.hideSoftInputFromWindow(dialogField.getWindowToken(), 0);
        }
    }

    /** Shows a dialog with its buttons in the design's inks: danger for a destructive answer. */
    private void showDialog(final AlertDialog dialog, final boolean destructive)
    {
        if (destroyed || activity.isFinishing())
        {
            return;
        }
        AirUi.showDialog(activity, dialog, destructive);
    }

    private EditText dialogField(final String text, final boolean multiLine)
    {
        final Resources res = activity.getResources();
        final EditText input = new EditText(activity);
        TextViewCompat.setTextAppearance(input, R.style.TextAppearance_KQ_Body);
        input.setTextColor(AirUi.color(activity, R.color.kq_ink));
        input.setHintTextColor(AirUi.color(activity, R.color.kq_ink_3));
        input.setBackgroundResource(R.drawable.air_bg_field);
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_12);
        final int top = res.getDimensionPixelSize(R.dimen.kq_space_8);
        input.setPaddingRelative(side, top, side, top);
        input.setMinHeight(res.getDimensionPixelSize(R.dimen.kq_size_field));
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (multiLine)
        {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                               | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            input.setMaxLines(6);
        }
        else
        {
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        }
        input.setText(text == null ? "" : text);
        input.setSelection(input.length());
        return input;
    }

    private TextView dialogText(final String text, final int appearance, final int colorRes)
    {
        final TextView view = new TextView(activity);
        TextViewCompat.setTextAppearance(view, appearance);
        view.setTextColor(AirUi.color(activity, colorRes));
        view.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        view.setText(text);
        return view;
    }

    /** The dialog's own side margins around a custom view. */
    private View dialogBox(final View content)
    {
        final Resources res = activity.getResources();
        final FrameLayout box = new FrameLayout(activity);
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_24);
        box.setPaddingRelative(side, res.getDimensionPixelSize(R.dimen.kq_space_8), side, 0);
        box.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                          ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void toast(final int message)
    {
        if (!destroyed)
        {
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * E21: an edit or a cancel of a tray entry that did not happen is said on the line under the field,
     * below the tray: "already left" is the rule, an edit that did not reach the person a failure. Only
     * with no field on screen does it come as a toast.
     */
    private void sayTrayProblem(final int message)
    {
        if (destroyed)
        {
            return;
        }
        if (!sayAtComposer(activity.getString(message), message == R.string.chat_edit_failed))
        {
            toast(message);
        }
    }

    @SuppressWarnings("deprecation")
    private void announce(final int message)
    {
        root.announceForAccessibility(activity.getString(message));
    }

    private String quantity(final int plural, final int count)
    {
        return activity.getResources().getQuantityString(plural, count, count);
    }

    private static int utf8Length(final String text)
    {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    /** Sets a text only when it differs, so an unchanged read neither lays out nor speaks. */
    private static void setText(final TextView view, final CharSequence text)
    {
        if (!TextUtils.equals(view.getText(), text))
        {
            view.setText(text);
        }
    }

    private static void setDescription(final View view, final CharSequence text)
    {
        if (!TextUtils.equals(view.getContentDescription(), text))
        {
            view.setContentDescription(text);
        }
    }

    private static void appendSentence(final StringBuilder sentence, final String part)
    {
        if (part == null || part.trim().isEmpty())
        {
            return;
        }
        if (sentence.length() > 0)
        {
            sentence.append(". ");
        }
        sentence.append(part);
    }

    private static void appendPart(final StringBuilder sentence, final String part)
    {
        if (part == null || part.trim().isEmpty())
        {
            return;
        }
        if (sentence.length() > 0)
        {
            sentence.append(", ");
        }
        sentence.append(part);
    }
}
