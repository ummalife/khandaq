package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.SharedPreferences;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

import static android.content.Context.NOTIFICATION_SERVICE;

/**
 * Notification ringtone picker with volume control. Uses {@link ListView#CHOICE_MODE_SINGLE}
 * and system single-choice rows so the selection indicator stays in the list, not the toolbar.
 */
public class NotificationRingtoneSettingsActivity extends AppCompatActivity
{
    public static final String PREF_NOTIFICATION_VOLUME_PERCENT = "notifications_new_message_volume_percent";
    public static final String PREF_NOTIFICATION_RINGTONE = "notifications_new_message_ringtone";

    private static final int DEFAULT_VOLUME_PERCENT = 100;

    private SeekBar volumeSeekBar;
    private TextView volumeLabel;
    private ListView ringtoneList;
    private SharedPreferences prefs;

    private final List<RingtoneRow> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private int selectedIndex = 0;
    private Ringtone previewRingtone;
    private boolean applyingVolumeUi;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_notification_ringtone_settings);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);

        final Toolbar toolbar = findViewById(R.id.notification_ringtone_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null)
        {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        volumeSeekBar = findViewById(R.id.notification_volume_seekbar);
        volumeLabel = findViewById(R.id.notification_volume_label);
        ringtoneList = findViewById(R.id.notification_ringtone_list);

        loadRingtoneRows();
        bindRingtoneList();
        bindVolumeSlider();
        restoreSelectionFromPrefs();
    }

    @Override
    protected void onStop()
    {
        stopPreview();
        super.onStop();
    }

    private void loadRingtoneRows()
    {
        rows.clear();
        rows.add(new RingtoneRow(null, getString(R.string.pref_ringtone_silent)));

        try
        {
            final RingtoneManager manager = new RingtoneManager(this);
            manager.setType(RingtoneManager.TYPE_NOTIFICATION);
            final Cursor cursor = manager.getCursor();
            if (cursor != null)
            {
                try
                {
                    while (cursor.moveToNext())
                    {
                        final Uri uri = manager.getRingtoneUri(cursor.getPosition());
                        if (uri == null)
                        {
                            continue;
                        }
                        final String title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX);
                        rows.add(new RingtoneRow(uri, title != null ? title : uri.toString()));
                    }
                }
                finally
                {
                    cursor.close();
                }
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private void bindRingtoneList()
    {
        final List<String> labels = new ArrayList<>(rows.size());
        for (RingtoneRow row : rows)
        {
            labels.add(row.title);
        }

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_single_choice, labels);
        ringtoneList.setAdapter(adapter);
        ringtoneList.setOnItemClickListener(new AdapterView.OnItemClickListener()
        {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id)
            {
                selectRingtone(position, true);
            }
        });
    }

    private void bindVolumeSlider()
    {
        final int percent = readVolumePercent();
        applyingVolumeUi = true;
        volumeSeekBar.setProgress(percent);
        applyingVolumeUi = false;
        updateVolumeLabel(percent);

        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
        {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser)
            {
                if (fromUser)
                {
                    applyVolumePercent(progress, true);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar)
            {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar)
            {
                applyVolumePercent(seekBar.getProgress(), true);
            }
        });
    }

    private int readVolumePercent()
    {
        int percent = prefs.getInt(PREF_NOTIFICATION_VOLUME_PERCENT, -1);
        if ((percent >= 0) && (percent <= 100))
        {
            return percent;
        }

        try
        {
            final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null)
            {
                final int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                final int current = am.getStreamVolume(AudioManager.STREAM_NOTIFICATION);
                if (max > 0)
                {
                    percent = (current * 100) / max;
                    prefs.edit().putInt(PREF_NOTIFICATION_VOLUME_PERCENT, percent).apply();
                    return percent;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return DEFAULT_VOLUME_PERCENT;
    }

    private void applyVolumePercent(int percent, boolean persist)
    {
        percent = Math.max(0, Math.min(100, percent));
        updateVolumeLabel(percent);

        if (!applyingVolumeUi)
        {
            applyingVolumeUi = true;
            volumeSeekBar.setProgress(percent);
            applyingVolumeUi = false;
        }

        try
        {
            final AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
            if (am != null)
            {
                final int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                final int target = (percent * max + 50) / 100;
                am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, target, 0);
            }
        }
        catch (Exception ignored)
        {
        }

        if (persist)
        {
            prefs.edit().putInt(PREF_NOTIFICATION_VOLUME_PERCENT, percent).apply();
        }
    }

    private void updateVolumeLabel(final int percent)
    {
        volumeLabel.setText(getString(R.string.pref_title_notification_volume) + ": " + percent + "%");
    }

    private void restoreSelectionFromPrefs()
    {
        final String saved = prefs.getString(PREF_NOTIFICATION_RINGTONE, null);
        int index = 0;
        if (!TextUtils.isEmpty(saved))
        {
            final Uri savedUri = Uri.parse(saved);
            for (int i = 0; i < rows.size(); i++)
            {
                final Uri rowUri = rows.get(i).uri;
                if ((rowUri != null) && savedUri.equals(rowUri))
                {
                    index = i;
                    break;
                }
            }
        }
        selectRingtone(index, false);
    }

    private void selectRingtone(final int index, final boolean playPreview)
    {
        if ((index < 0) || (index >= rows.size()))
        {
            return;
        }

        selectedIndex = index;
        ringtoneList.setItemChecked(index, true);

        final RingtoneRow row = rows.get(index);
        if (row.uri == null)
        {
            prefs.edit().putString(PREF_NOTIFICATION_RINGTONE, "").apply();
        }
        else
        {
            prefs.edit().putString(PREF_NOTIFICATION_RINGTONE, row.uri.toString()).apply();
        }

        HelperMsgNotification.ensure_notification_channels(getApplicationContext(),
                (android.app.NotificationManager) getSystemService(NOTIFICATION_SERVICE));

        if (playPreview && (row.uri != null))
        {
            playPreview(row.uri);
        }
        else
        {
            stopPreview();
        }
    }

    private void playPreview(final Uri uri)
    {
        stopPreview();
        try
        {
            previewRingtone = RingtoneManager.getRingtone(this, uri);
            if (previewRingtone == null)
            {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            {
                previewRingtone.setAudioAttributes(HelperMsgNotification.notification_sound_attributes());
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            {
                final int percent = readVolumePercent();
                previewRingtone.setVolume(Math.max(0f, Math.min(1f, percent / 100f)));
            }

            previewRingtone.play();
        }
        catch (Exception ignored)
        {
        }
    }

    private void stopPreview()
    {
        try
        {
            if ((previewRingtone != null) && previewRingtone.isPlaying())
            {
                previewRingtone.stop();
            }
        }
        catch (Exception ignored)
        {
        }
        previewRingtone = null;
    }

    static String ringtoneSummary(final android.content.Context context, final SharedPreferences prefs)
    {
        final String saved = prefs.getString(PREF_NOTIFICATION_RINGTONE, null);
        if (TextUtils.isEmpty(saved))
        {
            return context.getString(R.string.pref_ringtone_silent);
        }

        try
        {
            final Ringtone ringtone = RingtoneManager.getRingtone(context, Uri.parse(saved));
            if (ringtone != null)
            {
                final String title = ringtone.getTitle(context);
                if (!TextUtils.isEmpty(title))
                {
                    return title;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return context.getString(R.string.pref_title_ringtone);
    }

    private static final class RingtoneRow
    {
        final Uri uri;
        final String title;

        RingtoneRow(final Uri uri, final String title)
        {
            this.uri = uri;
            this.title = title;
        }
    }
}
