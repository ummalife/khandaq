/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.net.Uri;
import android.os.Bundle;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.signature.ObjectKey;
import com.github.chrisbanes.photoview.PhotoView;

import androidx.appcompat.app.AppCompatActivity;

import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;

public class ImageviewerActivity_SD extends AppCompatActivity
{
    private static final String TAG = "trifa.ImageviewerActySD";

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_imageviewer);

        ChatMediaHelper.bindMediaOverlay(this);

        String image_filename = getIntent().getStringExtra("image_filename");
        String storage_frame_work = getIntent().getStringExtra("storage_frame_work");
        if (storage_frame_work == null)
        {
            storage_frame_work = "0";
        }

        if (image_filename == null || image_filename.isEmpty())
        {
            HelperGeneric.display_toast(getString(R.string.chat_opening_file_failed), false, 0);
            finish();
            return;
        }

        if (!storage_frame_work.equals("1")
                && !HelperFiletransfer.isMediaFileReadyToView(image_filename, false, null))
        {
            HelperGeneric.display_toast(getString(R.string.chat_media_still_downloading), false, 0);
            finish();
            return;
        }

        final PhotoView photoView = (PhotoView) findViewById(R.id.big_image);
        photoView.setImageResource(R.drawable.round_loading_animation);

        String image_cache_key = getIntent().getStringExtra("image_cache_key");
        if ((image_cache_key == null) || (image_cache_key.isEmpty()))
        {
            image_cache_key = image_filename;
        }
        final ObjectKey glide_signature = new ObjectKey(image_cache_key);

        if (VFS_ENCRYPT)
        {
            if (storage_frame_work.equals("0"))
            {
                final String image_filename_ = image_filename;
                try
                {
                    if (!HelperFiletransfer.isIocipherVirtualPath(image_filename_))
                    {
                        final java.io.File plainFile = new java.io.File(image_filename_);
                        if (!plainFile.isFile() || plainFile.length() <= 0L)
                        {
                            HelperGeneric.display_toast(getString(R.string.chat_opening_file_failed), false, 0);
                            finish();
                            return;
                        }

                        final RequestOptions plainOptions = new RequestOptions().
                                signature(glide_signature).
                                diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                                skipMemoryCache(false);

                        GlideApp.
                                with(this).
                                load(plainFile).
                                apply(plainOptions).
                                placeholder(R.drawable.round_loading_animation).
                                error(R.drawable.round_loading_animation).
                                listener(new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>()
                                {
                                    @Override
                                    public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e,
                                                                Object model,
                                                                com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                                boolean isFirstResource)
                                    {
                                        HelperGeneric.display_toast(getString(R.string.chat_opening_file_failed), false, 0);
                                        finish();
                                        return false;
                                    }

                                    @Override
                                    public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                                                   Object model,
                                                                   com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                                                   com.bumptech.glide.load.DataSource dataSource,
                                                                   boolean isFirstResource)
                                    {
                                        return false;
                                    }
                                }).
                                into(photoView);
                        return;
                    }

                    final info.guardianproject.iocipher.File vfsFile =
                            new info.guardianproject.iocipher.File(image_filename_);
                    if (!vfsFile.exists() || vfsFile.length() <= 0L)
                    {
                        HelperGeneric.display_toast(getString(R.string.chat_media_still_downloading), false, 0);
                        finish();
                        return;
                    }

                    RequestOptions req_options = new RequestOptions().
                            signature(glide_signature).
                            diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                            skipMemoryCache(false);

                    GlideApp.
                            with(this).
                            load(vfsFile).
                            apply(req_options).
                            placeholder(R.drawable.round_loading_animation).
                            listener(imageLoadListener()).
                            into(photoView);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.display_toast(getString(R.string.chat_opening_file_failed), false, 0);
                    finish();
                }
            }
            else
            {
                try
                {
                    final Uri uri = Uri.parse(image_filename);
                    RequestOptions req_options = new RequestOptions().
                            signature(glide_signature).
                            diskCacheStrategy(DiskCacheStrategy.NONE).
                            skipMemoryCache(true);

                    GlideApp.
                            with(this).
                            load(uri).
                            apply(req_options).
                            placeholder(R.drawable.round_loading_animation).
                            listener(imageLoadListener()).
                            into(photoView);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                    HelperGeneric.display_toast(getString(R.string.chat_opening_file_failed), false, 0);
                    finish();
                }
            }
        }
        else
        {
            final java.io.File f2 = new java.io.File(image_filename);
            if (!f2.exists() || f2.length() <= 0L)
            {
                HelperGeneric.display_toast(getString(R.string.chat_opening_file_failed), false, 0);
                finish();
                return;
            }

            try
            {
                RequestOptions req_options = new RequestOptions().
                        signature(glide_signature).
                        diskCacheStrategy(DiskCacheStrategy.NONE).
                        skipMemoryCache(true);

                GlideApp.
                        with(this).
                        load(f2).
                        apply(req_options).
                        placeholder(R.drawable.round_loading_animation).
                        listener(imageLoadListener()).
                        into(photoView);
            }
            catch (Exception e)
            {
                e.printStackTrace();
                HelperGeneric.display_toast(getString(R.string.chat_opening_file_failed), false, 0);
                finish();
            }
        }
    }

    private com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> imageLoadListener()
    {
        return new com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable>()
        {
            @Override
            public boolean onLoadFailed(com.bumptech.glide.load.engine.GlideException e,
                                        Object model,
                                        com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                        boolean isFirstResource)
            {
                HelperGeneric.display_toast(getString(R.string.chat_opening_file_failed), false, 0);
                finish();
                return false;
            }

            @Override
            public boolean onResourceReady(android.graphics.drawable.Drawable resource,
                                           Object model,
                                           com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable> target,
                                           com.bumptech.glide.load.DataSource dataSource,
                                           boolean isFirstResource)
            {
                return false;
            }
        };
    }
}
