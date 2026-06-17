package com.zoffcc.applications.trifa;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarItemView;

import org.khandaq.messenger.R;

import de.hdodenhof.circleimageview.CircleImageView;

import static com.zoffcc.applications.trifa.HelperGeneric.get_vfs_image_filename_own_avatar;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.MainActivity.main_activity_s;

/**
 * Shows the user's profile photo on the Profile bottom-nav tab (Telegram-style).
 */
final class ProfileTabAvatarHelper
{
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int AVATAR_VIEW_ID = View.generateViewId();

    private ProfileTabAvatarHelper()
    {
    }

    static void updateAsync()
    {
        mainHandler.post(ProfileTabAvatarHelper::updateNow);
    }

    static boolean hasOwnProfileAvatar()
    {
        if (!VFS_ENCRYPT)
        {
            return false;
        }

        try
        {
            final String fname = get_vfs_image_filename_own_avatar();
            if (fname == null)
            {
                return false;
            }

            final info.guardianproject.iocipher.File avatarFile = new info.guardianproject.iocipher.File(fname);
            return avatarFile.exists() && avatarFile.length() > 0;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    private static void updateNow()
    {
        final MainActivity activity = main_activity_s;
        if (activity == null || !activity.main_activity_resumed || activity.isFinishing())
        {
            return;
        }

        final BottomNavigationView nav = activity.findViewById(R.id.bottom_navigation);
        if (nav == null)
        {
            return;
        }

        nav.post(() -> applyAvatarOverlay(activity, nav));
    }

    private static void applyAvatarOverlay(final MainActivity activity, final BottomNavigationView nav)
    {
        try
        {
            final View tabView = nav.findViewById(R.id.bottom_nav_profile);
            if (!(tabView instanceof NavigationBarItemView))
            {
                return;
            }

            final NavigationBarItemView itemView = (NavigationBarItemView) tabView;
            ProfileTabConnectionIndicator.updateAsync();

            final ViewGroup iconContainer = itemView.findViewById(
                    com.google.android.material.R.id.navigation_bar_item_icon_container);
            final ImageView iconView = itemView.findViewById(
                    com.google.android.material.R.id.navigation_bar_item_icon_view);
            if (iconContainer == null || iconView == null)
            {
                return;
            }

            final float density = activity.getResources().getDisplayMetrics().density;
            final int avatarSize = (int) (26f * density);

            CircleImageView avatarView = iconContainer.findViewById(AVATAR_VIEW_ID);
            if (avatarView == null)
            {
                avatarView = new CircleImageView(activity);
                avatarView.setId(AVATAR_VIEW_ID);
                avatarView.setBorderWidth(0);
                final android.widget.FrameLayout.LayoutParams lp =
                        new android.widget.FrameLayout.LayoutParams(avatarSize, avatarSize);
                lp.gravity = android.view.Gravity.CENTER;
                iconContainer.addView(avatarView, lp);
            }
            else
            {
                final ViewGroup.LayoutParams existing = avatarView.getLayoutParams();
                existing.width = avatarSize;
                existing.height = avatarSize;
                avatarView.setLayoutParams(existing);
            }

            if (hasOwnProfileAvatar())
            {
                iconView.setVisibility(View.INVISIBLE);
                avatarView.setVisibility(View.VISIBLE);
                loadOwnAvatarInto(avatarView, activity);
            }
            else
            {
                iconView.setVisibility(View.VISIBLE);
                avatarView.setVisibility(View.GONE);
                GlideApp.with(activity).clear(avatarView);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void loadOwnAvatarInto(final CircleImageView avatarView, final Context context)
    {
        try
        {
            final String fname = get_vfs_image_filename_own_avatar();
            if (fname == null)
            {
                return;
            }

            final info.guardianproject.iocipher.File avatarFile = new info.guardianproject.iocipher.File(fname);
            if (!avatarFile.exists() || avatarFile.length() <= 0)
            {
                return;
            }

            GlideApp.with(context).clear(avatarView);
            final RequestOptions glideOptions = new RequestOptions().fitCenter().circleCrop();
            GlideApp.with(context).load(avatarFile).diskCacheStrategy(DiskCacheStrategy.RESOURCE).skipMemoryCache(false)
                    .apply(glideOptions).into(avatarView);
        }
        catch (Exception ignored)
        {
        }
    }
}
