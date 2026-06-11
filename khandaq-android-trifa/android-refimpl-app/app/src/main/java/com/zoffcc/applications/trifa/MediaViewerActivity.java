package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;

import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.github.chrisbanes.photoview.PhotoView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;

public class MediaViewerActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.MediaViewerActy";

    private PhotoView photoView;
    private PlayerView playerView;
    private ProgressBar loadingView;
    private ExoPlayer exoPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_media_viewer);

        photoView = findViewById(R.id.media_photo_view);
        playerView = findViewById(R.id.media_player_view);
        loadingView = findViewById(R.id.media_loading);

        final String mode = getIntent().getStringExtra(ChatMediaHelper.EXTRA_MODE);
        final String vfsPath = getIntent().getStringExtra(ChatMediaHelper.EXTRA_VFS_PATH);
        final String exportPath = getIntent().getStringExtra(ChatMediaHelper.EXTRA_EXPORT_PATH);
        final boolean storageFramework = "1".equals(getIntent().getStringExtra(ChatMediaHelper.EXTRA_STORAGE_FRAMEWORK));
        final String mimeType = getIntent().getStringExtra(ChatMediaHelper.EXTRA_MIME_TYPE);

        if (ChatMediaHelper.MODE_VIDEO.equals(mode))
        {
            showVideo(vfsPath, exportPath, storageFramework, mimeType);
        }
        else
        {
            showImage(vfsPath, exportPath, storageFramework);
        }
    }

    private void showImage(final String vfsPath, final String exportPath, final boolean storageFramework)
    {
        photoView.setVisibility(View.VISIBLE);
        playerView.setVisibility(View.GONE);
        photoView.setImageResource(R.drawable.round_loading_animation);

        if (storageFramework)
        {
            GlideApp.
                    with(this).
                    load(Uri.parse(vfsPath)).
                    diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                    placeholder(R.drawable.round_loading_animation).
                    into(photoView);
            return;
        }

        if ((exportPath != null) && (!exportPath.isEmpty()))
        {
            GlideApp.
                    with(this).
                    load(new java.io.File(exportPath)).
                    diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                    placeholder(R.drawable.round_loading_animation).
                    into(photoView);
            return;
        }

        if (VFS_ENCRYPT)
        {
            info.guardianproject.iocipher.File vfsFile = new info.guardianproject.iocipher.File(vfsPath);
            GlideApp.
                    with(this).
                    load(vfsFile).
                    diskCacheStrategy(DiskCacheStrategy.RESOURCE).
                    apply(new RequestOptions()).
                    placeholder(R.drawable.round_loading_animation).
                    into(photoView);
        }
    }

    @OptIn(markerClass = UnstableApi.class)
    private void showVideo(final String vfsPath, final String exportPath, final boolean storageFramework,
                           final String mimeType)
    {
        photoView.setVisibility(View.GONE);
        playerView.setVisibility(View.VISIBLE);
        loadingView.setVisibility(View.VISIBLE);

        final Handler mainHandler = new Handler(Looper.getMainLooper());
        new Thread()
        {
            @Override
            public void run()
            {
                try
                {
                    final Uri playbackUri = ChatMediaHelper.resolvePlaybackUri(MediaViewerActivity.this, vfsPath,
                                                                               exportPath, storageFramework, mimeType);
                    mainHandler.post(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            startVideoPlayback(playbackUri, mimeType);
                        }
                    });
                }
                catch (Exception e)
                {
                    Log.i(TAG, "showVideo:resolvePlaybackUri:EE:" + e.getMessage());
                    mainHandler.post(new Runnable()
                    {
                        @Override
                        public void run()
                        {
                            loadingView.setVisibility(View.GONE);
                            HelperGeneric.display_toast("opening file failed", false, 0);
                            finish();
                        }
                    });
                }
            }
        }.start();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void startVideoPlayback(Uri playbackUri, String mimeType)
    {
        loadingView.setVisibility(View.GONE);
        final DefaultDataSource.Factory dataSourceFactory = new DefaultDataSource.Factory(this);
        exoPlayer = new ExoPlayer.Builder(this).
                setMediaSourceFactory(new DefaultMediaSourceFactory(dataSourceFactory)).
                build();
        playerView.setPlayer(exoPlayer);

        final MediaItem.Builder itemBuilder = new MediaItem.Builder().setUri(playbackUri);
        if ((mimeType != null) && (!mimeType.isEmpty()) && (!"application/octet-stream".equals(mimeType)))
        {
            itemBuilder.setMimeType(mimeType);
        }

        exoPlayer.setMediaItem(itemBuilder.build());
        exoPlayer.setPlayWhenReady(true);
        exoPlayer.prepare();
        exoPlayer.addListener(new Player.Listener()
        {
            @Override
            public void onPlaybackStateChanged(int playbackState)
            {
                if (playbackState == Player.STATE_ENDED)
                {
                    exoPlayer.seekTo(0);
                    exoPlayer.setPlayWhenReady(false);
                }
            }

            @Override
            public void onPlayerError(PlaybackException error)
            {
                Log.i(TAG, "onPlayerError:" + error.getMessage());
                HelperGeneric.display_toast("opening file failed", false, 0);
                finish();
            }
        });
    }

    @Override
    protected void onStop()
    {
        if (exoPlayer != null)
        {
            exoPlayer.setPlayWhenReady(false);
        }
        super.onStop();
    }

    @Override
    protected void onDestroy()
    {
        if (exoPlayer != null)
        {
            exoPlayer.release();
            exoPlayer = null;
        }
        super.onDestroy();
    }
}
