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

        // KHANDAQ: do NOT touch iocipher.File until the VFS is actually mounted. Touching it earlier
        // runs OsConstants.<clinit> before the native lib is loaded → UnsatisfiedLinkError that
        // PERMANENTLY poisons the class (every later access throws NoClassDefFoundError) → broken
        // avatars/media for the whole process. vfs is non-null only after a successful mount.
        if (TrifaToxService.vfs == null)
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
        catch (Throwable ignored)
        {
            // KHANDAQ: VFS native lib may not be loaded/mounted yet on first launch
            // (UnsatisfiedLinkError is an Error, not an Exception) — treat as "no avatar".
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

    /** Air: where our photo lives and a cache key that changes with the file. */
    static final class OwnPhoto
    {
        final String path;
        final String signature;

        private OwnPhoto(final String path, final String signature)
        {
            this.path = path;
            this.signature = signature;
        }

        @Override
        public boolean equals(final Object other)
        {
            return other instanceof OwnPhoto && signature.equals(((OwnPhoto) other).signature);
        }

        @Override
        public int hashCode()
        {
            return signature.hashCode();
        }
    }

    /** Air: our photo, or null when there is none. Reads the database and stats the file: off the main thread. */
    static OwnPhoto ownPhoto()
    {
        if (!VFS_ENCRYPT || TrifaToxService.vfs == null)
        {
            return null;
        }
        try
        {
            final String fname = get_vfs_image_filename_own_avatar();
            if (fname == null)
            {
                return null;
            }
            final info.guardianproject.iocipher.File avatarFile = new info.guardianproject.iocipher.File(fname);
            if (!avatarFile.exists() || avatarFile.length() <= 0)
            {
                return null;
            }
            // The path never changes when the user picks a new photo, so the key carries length and mtime.
            return new OwnPhoto(fname, fname + "_" + avatarFile.length() + "_" + avatarFile.lastModified());
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }

    /** Air: loads {@link #ownPhoto()} into a square tile, cropped to fill it. */
    static void loadOwnPhotoInto(final ImageView target, final Context context, final OwnPhoto photo)
    {
        GlideApp.with(context).clear(target);
        if (photo == null || TrifaToxService.vfs == null)
        {
            target.setImageDrawable(null);
            return;
        }
        try
        {
            GlideApp.with(context).load(new info.guardianproject.iocipher.File(photo.path))
                    .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .signature(new com.bumptech.glide.signature.StringSignatureZ(photo.signature))
                    .centerCrop().into(target);
        }
        catch (Throwable ignored)
        {
            target.setImageDrawable(null);
        }
    }

    private static void loadOwnAvatarInto(final CircleImageView avatarView, final Context context)
    {
        if (TrifaToxService.vfs == null)
        {
            return;
        }

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
            // KHANDAQ: own avatar lives at a constant VFS path, so the RESOURCE disk cache would serve
            // the stale image after the user changes the photo — key the cache on length+mtime instead.
            final String sig = fname + "_" + avatarFile.length() + "_" + avatarFile.lastModified();
            GlideApp.with(context).load(avatarFile).diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                    .signature(new com.bumptech.glide.signature.StringSignatureZ(sig)).skipMemoryCache(false)
                    .apply(glideOptions).into(avatarView);
        }
        catch (Throwable ignored)
        {
        }
    }
}
