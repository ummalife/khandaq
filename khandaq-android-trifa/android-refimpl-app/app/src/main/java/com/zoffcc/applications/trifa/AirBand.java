package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.app.Application;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.zoffcc.applications.sorm.FriendList;
import com.zoffcc.applications.sorm.GroupDB;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, E22): the in-app band (design/DESIGN.md, "In-app band on the phone"; NotificationWindow
 * on iOS). While a screen of the app is in front, a message from another chat, a contact request or an
 * invitation to a group comes as a band under the safe area instead of a system notification: the tile
 * the Air screen shows, the sender, then the text when previews are on, otherwise "New message". A tap
 * opens the chat, or Requests for a request and an invitation; a swipe up puts it away; it leaves by
 * itself after 4.5 s, or after the longer time the accessibility settings ask for.
 *
 * <p>The band is a view in the window of the screen in front, added only while it shows: it takes the
 * app's theme, never decides the system bars, and lies under the PIN lock, which is a screen of its own
 * above it. It comes down in the layout time and leaves in the state time; reduced motion shortens both
 * (AirUi.duration). Which events get a band is {@link AirBandRule}; this class knows the screens.
 */
final class AirBand
{
    private static final String TAG = "trifa.AirBand";

    /**
     * A screen that never shows the band and gets the system notification instead, like the PIN lock:
     * the sign-in and first-launch screens implement it.
     */
    interface NoBand
    {
    }

    /** What a band says and what a tap on it opens. Built off the main thread: it reads the database. */
    static final class Content
    {
        enum Opens
        {
            FRIEND,
            GROUP,
            /** An old conference, opened the way its system notification opens it. */
            OTHER_CHAT,
            /** Air with its Requests, where the request or the invitation waits as a card. */
            REQUESTS,
        }

        final String title;
        final String body;
        final AirTileView.Kind tileKind;
        final String tileTitle;
        final int tone;
        @Nullable
        final ChatBubbleUiHelper.AvatarMeta photo;
        final Opens opens;
        @Nullable
        final String chatKey;
        @StringRes
        final int hint;

        Content(final String title, final String body, final AirTileView.Kind tileKind, final String tileTitle,
                final int tone, @Nullable final ChatBubbleUiHelper.AvatarMeta photo, final Opens opens,
                @Nullable final String chatKey, @StringRes final int hint)
        {
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
            this.tileKind = tileKind;
            this.tileTitle = tileTitle == null ? "" : tileTitle;
            this.tone = tone;
            this.photo = photo;
            this.opens = opens;
            this.chatKey = chatKey;
            this.hint = hint;
        }

        /** What TalkBack says: the sender, then the text. */
        String spoken()
        {
            return body.isEmpty() ? title : title + ", " + body;
        }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // Lifecycle state: written on the main thread by the callbacks.
    private static WeakReference<Activity> resumed = new WeakReference<>(null);
    private static int started = 0;
    private static boolean restarting = false;
    /** A screen with content is in front; read off the main thread to skip building a band for nothing. */
    private static volatile boolean contentInFront = false;
    private static volatile long foregroundSinceMs = 0L;

    // The band up now, main thread only.
    @Nullable
    private static AirBandView band = null;
    @Nullable
    private static Activity bandHost = null;
    private static long stayMs = AirBandRule.BAND_MS;
    private static final Runnable LEAVE = () -> dismiss(true);

    private AirBand()
    {
    }

    /** Follows the screens of the app, from MainApplication. */
    static void register(final Application app)
    {
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks()
        {
            @Override
            public void onActivityStarted(@NonNull final Activity a)
            {
                // Coming back starts the grace; a screen recreated for a new theme or language does not.
                if (started == 0 && !restarting)
                {
                    foregroundSinceMs = System.currentTimeMillis();
                }
                restarting = false;
                started++;
            }

            @Override
            public void onActivityStopped(@NonNull final Activity a)
            {
                started = Math.max(0, started - 1);
                if (started == 0)
                {
                    restarting = a.isChangingConfigurations();
                }
            }

            @Override
            public void onActivityResumed(@NonNull final Activity a)
            {
                resumed = new WeakReference<>(a);
                contentInFront = screenOf(a) == AirBandRule.Screen.CONTENT;
            }

            @Override
            public void onActivityPaused(@NonNull final Activity a)
            {
                if (resumed.get() == a)
                {
                    resumed = new WeakReference<>(null);
                    contentInFront = false;
                }
                // The band belongs to the window of its screen: it goes with it, at once.
                if (bandHost == a)
                {
                    dismiss(false);
                }
            }

            @Override
            public void onActivityCreated(@NonNull final Activity a, @Nullable final Bundle b)
            {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull final Activity a, @NonNull final Bundle b)
            {
            }

            @Override
            public void onActivityDestroyed(@NonNull final Activity a)
            {
                if (bandHost == a)
                {
                    dismiss(false);
                }
            }
        });
        // A request or an invitation answered elsewhere takes its system notification with it.
        AirRequestStore.addListener(HelperMsgNotification::forget_answered_requests);
        AirInviteStore.addListener(HelperMsgNotification::forget_answered_requests);
    }

    /** True while a screen that could show a band is in front. Any thread. */
    static boolean mayShow()
    {
        return contentInFront;
    }

    // ---- where it goes (main thread) ----

    /** A message of the chat {@code key}, sent at {@code sentAtMs} (0 when unknown). Main thread. */
    static AirBandRule.Outcome messageOutcome(@Nullable final String key, final long sentAtMs)
    {
        final Activity front = resumed.get();
        final AirBandRule.Screen screen = screenOf(front);
        return AirBandRule.forMessage(screen, screen == AirBandRule.Screen.CONTENT && isLocked(front),
                                      isChatOnScreen(front, key), foregroundSinceMs, sentAtMs,
                                      System.currentTimeMillis());
    }

    /** A contact request or an invitation to a group. Main thread. */
    static AirBandRule.Outcome requestOutcome()
    {
        final Activity front = resumed.get();
        final AirBandRule.Screen screen = screenOf(front);
        return AirBandRule.forRequest(screen, screen == AirBandRule.Screen.CONTENT && isLocked(front),
                                      foregroundSinceMs, System.currentTimeMillis());
    }

    static AirBandRule.Screen screenOf(@Nullable final Activity a)
    {
        if (a == null || a.isFinishing() || a.isDestroyed())
        {
            return AirBandRule.Screen.NONE;
        }
        if (a instanceof CallingActivity || a instanceof CallingWaitingActivity)
        {
            return AirBandRule.Screen.CALL;
        }
        // The screens that keep the content hidden, and the crash screen, which is no place for it.
        if (a instanceof NoBand || a instanceof PinActivity || a instanceof CheckPasswordActivity
            || a instanceof SetPasswordActivity || a instanceof StartMainActivityWrapper
            || a instanceof OnboardingActivity || a instanceof CreateProfileActivity || a instanceof CrashActivity)
        {
            return AirBandRule.Screen.GATE;
        }
        return AirBandRule.Screen.CONTENT;
    }

    /** The app lock is up or owed, or the phone is locked: nobody may see a sender or a text now. */
    private static boolean isLocked(@Nullable final Activity a)
    {
        if (a == null)
        {
            return true;
        }
        if (AppLockHelper.isGateUp(a))
        {
            return true;
        }
        final KeyguardManager keyguard = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
        return keyguard != null && keyguard.isKeyguardLocked();
    }

    private static boolean isChatOnScreen(@Nullable final Activity a, @Nullable final String key)
    {
        if (a == null || key == null)
        {
            return false;
        }
        try
        {
            final String open;
            if (a instanceof MessageListActivity)
            {
                open = ((MessageListActivity) a).get_friend_pubkey();
            }
            else if (a instanceof GroupMessageListActivity)
            {
                open = ((GroupMessageListActivity) a).get_current_group_id();
            }
            else if (a instanceof ConferenceMessageListActivity)
            {
                open = ((ConferenceMessageListActivity) a).get_current_conf_id();
            }
            else
            {
                return false;
            }
            return open != null && key.equalsIgnoreCase(open);
        }
        catch (Throwable e)
        {
            return false;
        }
    }

    // ---- the band (main thread) ----

    /** Shows the band on the screen in front, in place of the one up now; false when it could not. */
    static boolean show(@Nullable final Content content)
    {
        final Activity host = resumed.get();
        if (content == null || host == null || screenOf(host) != AirBandRule.Screen.CONTENT || host.getWindow() == null)
        {
            return false;
        }
        final View decorView = host.getWindow().getDecorView();
        if (!(decorView instanceof FrameLayout))
        {
            return false;
        }
        final FrameLayout decor = (FrameLayout) decorView;
        dismiss(false);

        final Resources res = host.getResources();
        // Only the band up now answers; one on its way out is past opening or putting away.
        final AirBandView[] self = new AirBandView[1];
        final AirBandView view = new AirBandView(host, content, new AirBandView.Listener()
        {
            @Override
            public void onBandTap()
            {
                if (band != self[0])
                {
                    return;
                }
                dismiss(true);
                open(host, content);
            }

            @Override
            public void onBandDismiss()
            {
                if (band == self[0])
                {
                    dismiss(true);
                }
            }

            @Override
            public void onBandHeld(final boolean held)
            {
                if (band != self[0])
                {
                    return;
                }
                MAIN.removeCallbacks(LEAVE);
                if (!held)
                {
                    MAIN.postDelayed(LEAVE, stayMs);
                }
            }
        });
        self[0] = view;

        // Under the safe area: the status bar and a cutout at the top, the cutouts at the sides.
        Insets safe = Insets.NONE;
        final WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(decor);
        if (insets != null)
        {
            safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        }
        final int gap = res.getDimensionPixelSize(R.dimen.air_band_side_gap);
        final int room = decor.getWidth() - safe.left - safe.right - 2 * gap;
        final FrameLayout.LayoutParams params;
        if (room > 0)
        {
            final int width = Math.min(room, res.getDimensionPixelSize(R.dimen.air_band_max_width));
            params = new FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP | Gravity.LEFT);
            // Centred in the safe area; the band's own content still follows the language's direction.
            params.leftMargin = safe.left + gap + (room - width) / 2;
        }
        else
        {
            params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                  ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
            params.leftMargin = safe.left + gap;
            params.rightMargin = safe.right + gap;
        }
        params.topMargin = safe.top + res.getDimensionPixelSize(R.dimen.air_band_top_gap);
        decor.addView(view, params);
        band = view;
        bandHost = host;

        // It comes down from above the safe area once its height is known.
        view.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener()
        {
            @Override
            public boolean onPreDraw()
            {
                view.getViewTreeObserver().removeOnPreDrawListener(this);
                if (band != view)
                {
                    // Put away before it was ever drawn: it does not come down any more.
                    return true;
                }
                view.setTranslationY(-outOfSight(view));
                view.animate().translationY(0f).setDuration(AirUi.duration(host, R.integer.kq_dur_layout))
                    .setInterpolator(new DecelerateInterpolator()).start();
                view.announceForAccessibility(content.spoken());
                return true;
            }
        });

        stayMs = AirBandRule.stayMs(recommendedStayMs(host));
        MAIN.removeCallbacks(LEAVE);
        MAIN.postDelayed(LEAVE, stayMs);
        return true;
    }

    /** Puts the band away: up and out in the state time, or at once when its screen goes. */
    static void dismiss(final boolean animated)
    {
        MAIN.removeCallbacks(LEAVE);
        final AirBandView view = band;
        final Activity host = bandHost;
        band = null;
        bandHost = null;
        if (view == null)
        {
            return;
        }
        if (!animated || host == null || !view.isAttachedToWindow())
        {
            detach(view);
            return;
        }
        // On its way out it takes no touch: a finger would stop the animation that takes it away.
        view.setEnabled(false);
        view.animate().cancel();
        view.animate().translationY(-outOfSight(view)).alpha(0f)
            .setDuration(AirUi.duration(host, R.integer.kq_dur_state)).setInterpolator(new AccelerateInterpolator())
            .withEndAction(() -> detach(view)).start();
    }

    private static float outOfSight(final View view)
    {
        return view.getBottom() + view.getResources().getDimension(R.dimen.air_band_dismiss_distance);
    }

    private static void detach(final View view)
    {
        view.animate().cancel();
        final ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup)
        {
            ((ViewGroup) parent).removeView(view);
        }
    }

    /** The time a band stays for someone who asked the system for longer to read and act. */
    private static long recommendedStayMs(final Context context)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
        {
            return AirBandRule.BAND_MS;
        }
        final AccessibilityManager accessibility = (AccessibilityManager) context.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        if (accessibility == null)
        {
            return AirBandRule.BAND_MS;
        }
        return accessibility.getRecommendedTimeoutMillis((int) AirBandRule.BAND_MS,
                                                         AccessibilityManager.FLAG_CONTENT_TEXT
                                                         | AccessibilityManager.FLAG_CONTENT_ICONS
                                                         | AccessibilityManager.FLAG_CONTENT_CONTROLS);
    }

    /** What a tap opens: the chat, the way the Air list opens it, or Air with its Requests. */
    private static void open(final Activity host, final Content content)
    {
        if (host.isFinishing() || host.isDestroyed())
        {
            return;
        }
        try
        {
            switch (content.opens)
            {
                case FRIEND:
                    FriendListHolder.show_messagelist_acticvity_for_friend(host, content.chatKey);
                    break;
                case GROUP:
                    GroupListHolder.open_group_chat(host, content.chatKey);
                    break;
                case OTHER_CHAT:
                    HelperMsgNotification.open_chat_from_notification_key(host, content.chatKey);
                    break;
                case REQUESTS:
                default:
                    openRequests(host);
                    break;
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "open:EE:" + e.getMessage());
        }
    }

    /** Air with its Requests filter, as New contact's "Open Requests" does. */
    static void openRequests(final Context context)
    {
        final Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (!(context instanceof Activity))
        {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        intent.putExtra(MainActivity.EXTRA_OPEN_REQUESTS, true);
        context.startActivity(intent);
    }

    // ---- what it says (any thread; reads the database) ----

    /**
     * A message: the person's tile and name, or the group's with the member before the text, as the Air
     * list shows them. {@code title} and {@code text} are those of the system notification;
     * {@code groupPeerKey} is the member who wrote in a group, or null.
     */
    static Content forMessage(final Context context, @Nullable final String key, @Nullable final String title,
                              @Nullable final String text, @Nullable final String groupPeerKey)
    {
        final String safeKey = key == null ? "" : key;
        final String newMessage = context.getString(R.string.air_band_new_message);
        final boolean previews = MainActivity.PREF__notification_show_content;
        final String preview = HelperMsgNotification.trim_message_preview(text);
        final String said = preview.isEmpty() ? newMessage : preview;

        // A group first: it is one query, where a miss of the friend lookup costs three.
        final GroupDB group = group(safeKey);
        if (group == null)
        {
            final FriendList friend = HelperFriend.lookup_friendlist_by_pubkey(safeKey);
            if (friend != null)
            {
                String name = HelperFriend.display_name_from_friendlist(friend, safeKey);
                if (TextUtils.isEmpty(name))
                {
                    name = HelperMsgNotification.resolve_sender_label(title, safeKey);
                }
                return new Content(name, previews ? said : newMessage, AirTileView.Kind.PERSON, name,
                                   AirModel.Tone.index(safeKey), ChatBubbleUiHelper.peer_avatar_meta(friend),
                                   Content.Opens.FRIEND, friend.tox_public_key_string, R.string.air_band_hint_chat);
            }
        }
        else
        {
            final String groupId = group.group_identifier;
            final String name = !TextUtils.isEmpty(group.name) ? group.name
                                                                : (!TextUtils.isEmpty(title) ? title : shortId(groupId));
            String body = newMessage;
            if (previews)
            {
                String member = null;
                if (!TextUtils.isEmpty(groupPeerKey))
                {
                    try
                    {
                        member = HelperGroup.tox_group_peer_get_name__wrapper(groupId, groupPeerKey);
                    }
                    catch (Throwable ignored)
                    {
                        // The group may be gone from toxcore; the text still says enough.
                    }
                }
                body = TextUtils.isEmpty(member) ? said
                                                 : context.getString(R.string.air_band_sender_text_format, member, said);
            }
            return new Content(name, body, AirTileView.Kind.GROUP, name, AirModel.Tone.index(groupId), null,
                               Content.Opens.GROUP, groupId, R.string.air_band_hint_chat);
        }

        // An old conference: its name as the notification gives it.
        final String name = !TextUtils.isEmpty(title) ? title : shortId(safeKey);
        return new Content(name, previews ? said : newMessage, AirTileView.Kind.GROUP, name,
                           AirModel.Tone.index(safeKey), null, Content.Opens.OTHER_CHAT, safeKey,
                           R.string.air_band_hint_chat);
    }

    /** A request: its own kind, with the dashed tile, "Incoming contact request" and its words. */
    static Content forRequest(final Context context, final String publicKey, @Nullable final String message)
    {
        final String words = MainActivity.PREF__notification_show_content
                             ? HelperMsgNotification.trim_message_preview(message) : "";
        return new Content(context.getString(R.string.air_band_request_title), words, AirTileView.Kind.PENDING, "",
                           AirModel.Tone.index(publicKey), null, Content.Opens.REQUESTS, null,
                           R.string.air_band_hint_request);
    }

    /**
     * An invitation: who invites, then the group. The group's name is no message, so it shows with the
     * previews off too, as a group's name does in the title of its messages.
     */
    static Content forInvite(final Context context, final AirInviteStore.Invite invite,
                             @Nullable final FriendList inviter)
    {
        String name = inviter == null ? null : HelperFriend.display_name_from_friendlist(inviter, invite.inviterKey);
        if (TextUtils.isEmpty(name))
        {
            name = HelperFriend.peer_pubkey_short_id(invite.inviterKey);
        }
        final String body = TextUtils.isEmpty(invite.groupName)
                            ? context.getString(R.string.air_band_invite)
                            : context.getString(R.string.air_band_invite_format, invite.groupName);
        return new Content(name, body, AirTileView.Kind.PERSON, name, AirModel.Tone.index(invite.inviterKey),
                           inviter == null ? null : ChatBubbleUiHelper.peer_avatar_meta(inviter),
                           Content.Opens.REQUESTS, null, R.string.air_band_hint_invite);
    }

    @Nullable
    private static GroupDB group(final String key)
    {
        if (key.isEmpty() || TrifaToxService.orma == null)
        {
            return null;
        }
        try
        {
            final List<GroupDB> rows = TrifaToxService.orma.selectFromGroupDB()
                                                           .group_identifierEq(key.toLowerCase(Locale.ROOT)).limit(1)
                                                           .toList();
            if (rows != null && !rows.isEmpty())
            {
                return rows.get(0);
            }
        }
        catch (Throwable e)
        {
            Log.i(TAG, "group:EE:" + e.getMessage());
        }
        return null;
    }

    private static String shortId(final String key)
    {
        return key.substring(0, Math.min(8, key.length())).toUpperCase(Locale.ROOT);
    }
}
