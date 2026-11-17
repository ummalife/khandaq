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

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

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

    /** Air: where our photo lives and a cache key that changes with the photo. */
    static final class OwnPhoto
    {
        final String path;
        final String signature;
        /** What a stat of the file showed when its content was digested. */
        private final long length;
        private final long modified;

        private OwnPhoto(final String path, final String signature, final long length, final long modified)
        {
            this.path = path;
            this.signature = signature;
            this.length = length;
            this.modified = modified;
        }

        /**
         * KHANDAQ (0.2.57): the path never changes when the user picks a new photo, so the key carries the
         * length and a digest of the content. Not the mtime: IOCipher moves it when the file is only read,
         * and our photo is read each time it is sent to a contact who comes online and each time Glide
         * decodes it. With the mtime in the key, Air's "you" tile took each of those reads for a new photo,
         * dropped the one it showed and decoded the file again: the photo blinked.
         */
        static OwnPhoto of(final String path, final long length, final long modified, final byte[] digest)
        {
            final StringBuilder key = new StringBuilder(path).append('_').append(length).append('_');
            for (final byte b : digest)
            {
                key.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return new OwnPhoto(path, key.toString(), length, modified);
        }

        /** The SHA-256 of a photo's bytes. */
        static byte[] digest(final InputStream in) throws IOException
        {
            final MessageDigest digest;
            try
            {
                digest = MessageDigest.getInstance("SHA-256");
            }
            catch (NoSuchAlgorithmException e)
            {
                throw new IOException(e);
            }
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1)
            {
                digest.update(buffer, 0, read);
            }
            return digest.digest();
        }

        /** Whether a stat of the file still shows what it showed when this photo was read: no new read needed. */
        boolean sameStat(final String otherPath, final long otherLength, final long otherModified)
        {
            return path.equals(otherPath) && length == otherLength && modified == otherModified;
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

    /** Air: the photo worked out last. While a stat of the file still matches it, the file is not read again. */
    private static volatile OwnPhoto lastOwnPhoto;

    /** Air: our photo, or null when there is none. Reads the database and the file: off the main thread. */
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
            final long length = avatarFile.exists() ? avatarFile.length() : 0L;
            if (length <= 0)
            {
                return null;
            }
            final OwnPhoto known = lastOwnPhoto;
            if (known != null && known.sameStat(fname, length, avatarFile.lastModified()))
            {
                return known;
            }
            final byte[] digest;
            try (InputStream in = new info.guardianproject.iocipher.FileInputStream(avatarFile))
            {
                digest = OwnPhoto.digest(in);
            }
            catch (IOException e)
            {
                // A read that failed says nothing about the photo: the tile keeps the one it shows.
                return known != null && known.path.equals(fname) ? known : null;
            }
            // This read moved the mtime too, so the stat to match from now on is the one taken after it.
            final OwnPhoto photo = OwnPhoto.of(fname, length, avatarFile.lastModified(), digest);
            lastOwnPhoto = photo;
            return photo;
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
