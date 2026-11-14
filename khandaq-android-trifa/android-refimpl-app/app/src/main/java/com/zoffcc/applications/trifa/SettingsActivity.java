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

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.database.DataSetObserver;
import android.graphics.drawable.Drawable;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.DialogPreference;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceManager;
import android.preference.PreferenceScreen;
import android.preference.RingtonePreference;
import android.preference.SwitchPreference;
import android.preference.TwoStatePreference;
import android.text.InputType;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.mikepenz.fontawesome_typeface_library.FontAwesome;
import com.mikepenz.iconics.IconicsDrawable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;

import static com.zoffcc.applications.trifa.HelperGeneric.IPisValid;
import static com.zoffcc.applications.trifa.HelperGeneric.isIPPortValid;
import static com.zoffcc.applications.trifa.HelperGeneric.is_valid_tox_public_key;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_TCP_IP;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_TCP_KEYHEX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_TCP_PORT;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_UDP_IP;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_UDP_KEYHEX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF_KEY_CUSTOM_BOOTSTRAP_UDP_PORT;

public class SettingsActivity extends AppCompatPreferenceActivity
{
    private static final String TAG = "trifa.SettingsActivity";
    private Toolbar settingsToolbar;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onPostCreate(Bundle savedInstanceState)
    {
        super.onPostCreate(savedInstanceState);

        LinearLayout root = (LinearLayout) findViewById(android.R.id.list).getParent().getParent().getParent();
        settingsToolbar = (Toolbar) LayoutInflater.from(this).inflate(R.layout.settings_toolbar, root, false);
        root.addView(settingsToolbar, 0); // insert at top
        settingsToolbar.setNavigationOnClickListener(new View.OnClickListener()
        {
            @Override
            public void onClick(View v)
            {
                navigateUp();
            }
        });
        updateSettingsToolbarTitle();
    }

    @Override
    public void onBackPressed()
    {
        navigateUp();
    }

    private void navigateUp()
    {
        if (onIsMultiPane())
        {
            finish();
            return;
        }

        if (getFragmentManager().getBackStackEntryCount() > 0)
        {
            getFragmentManager().popBackStackImmediate();
            updateSettingsToolbarTitle();
            return;
        }

        finish();
    }

    private void updateSettingsToolbarTitle()
    {
        if (settingsToolbar == null)
        {
            return;
        }

        settingsToolbar.setTitle(R.string.settings_toolbar_title);
    }

    private static Preference.OnPreferenceChangeListener sBindPreferenceSummaryToValueListener = new Preference.OnPreferenceChangeListener()
    {
        @Override
        public boolean onPreferenceChange(Preference preference, Object value)
        {
            String stringValue = value.toString();

            if (preference instanceof ListPreference)
            {
                // For list preferences, look up the correct display value in
                // the preference's 'entries' list.
                ListPreference listPreference = (ListPreference) preference;
                int index = listPreference.findIndexOfValue(stringValue);

                // Set the summary to reflect the new value.
                preference.setSummary(index >= 0 ? listPreference.getEntries()[index] : null);

            }
            else if (preference instanceof RingtonePreference)
            {
                // For ringtone preferences, look up the correct display value
                // using RingtoneManager.
                if (TextUtils.isEmpty(stringValue))
                {
                    // Empty values correspond to 'silent' (no ringtone).
                    preference.setSummary(R.string.pref_ringtone_silent);

                }
                else
                {
                    Ringtone ringtone = RingtoneManager.getRingtone(preference.getContext(), Uri.parse(stringValue));

                    if (ringtone == null)
                    {
                        // Clear the summary if there was a lookup error.
                        preference.setSummary(null);
                    }
                    else
                    {
                        // Set the summary to reflect the new ringtone display
                        // name.
                        String name = ringtone.getTitle(preference.getContext());
                        preference.setSummary(name);
                    }
                }

            }
            else
            {
                // For all other preferences, set the summary to the value's
                // simple string representation.
                preference.setSummary(stringValue);
            }
            return true;
        }
    };

    /**
     * Helper method to determine if the device has an extra-large screen. For
     * example, 10" tablets are extra-large.
     */
    private static boolean isXLargeTablet(Context context)
    {
        return (context.getResources().getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK) >=
               Configuration.SCREENLAYOUT_SIZE_XLARGE;
    }

    private static void bindPreferenceSummaryToValue(Preference preference)
    {
        // Set the listener to watch for value changes.
        preference.setOnPreferenceChangeListener(sBindPreferenceSummaryToValueListener);

        // Trigger the listener immediately with the preference's
        // current value.
        sBindPreferenceSummaryToValueListener.onPreferenceChange(preference,
                                                                 PreferenceManager.getDefaultSharedPreferences(
                                                                         preference.getContext()).getString(
                                                                         preference.getKey(), ""));
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean onIsMultiPane()
    {
        return isXLargeTablet(this);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public void onBuildHeaders(List<Header> target)
    {
        loadHeadersFromResource(R.xml.pref_headers, target);
    }

    protected boolean isValidFragment(String fragmentName)
    {
        return PreferenceFragment.class.getName().equals(fragmentName) ||
               GeneralPreferenceFragment.class.getName().equals(fragmentName) ||
               NotificationPreferenceFragment.class.getName().equals(fragmentName);
    }

    /**
     * Advanced settings. KHANDAQ (redesign, stage 3b): the preferences of pref_general.xml stay the model,
     * with their keys, defaults (written on the first open, as before), dependencies and listeners; the
     * screen draws them as Air rows on plates (AirScreenKit, AirSettingRow) instead of the old list, and
     * every tap goes through the preference the way its own row did: the change listener first, then the
     * value, so a listener may still refuse it and the dependents follow it.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static class GeneralPreferenceFragment extends PreferenceFragment
    {
        /**
         * The rows by task, as Settings sorts its own (DESIGN.md, "Settings on the phone"). A key not listed
         * keeps its category of pref_general.xml, so a preference added there later still shows.
         */
        private static final int[] SECTION_TITLES = {R.string.air_settings_appearance, R.string.air_privacy_title,
                                                     R.string.air_pref_section_chats, R.string.air_pref_section_files,
                                                     R.string.air_pref_section_call_sound,
                                                     R.string.air_pref_section_call_video,
                                                     R.string.air_settings_network, R.string.air_pref_section_system};
        private static final String[][] SECTION_KEYS = {
                {"dark_mode_pref", "global_font_size", "compact_friendlist"},
                {"use_incognito_keyboard", "window_security"},
                {"message_paging_num_msgs_per_page", "conference_show_system_messages", "toxirc_muted_peers_01",
                 "toxirc_muted_peers_02", "toxirc_muted_peers_03"},
                {"auto_accept_image", "auto_accept_video", "auto_accept_all_upto",
                 "allow_file_sharing_to_trifa_via_intent", "allow_open_encrypted_file_via_intent"},
                {"speakerphone_tweak", "allow_screen_off_in_audio_call", "gainprocessing_active", "mic_gain_factor",
                 "mic_gain_factor_toggle", "rnnoise_active", "higher_audio_quality", "min_audio_samplingrate_out"},
                {"video_uv_reversed", "camera_get_preview_format", "use_H264_hw_encoding", "video_cam_resolution",
                 "h264_encoder_use_intra_refresh", "fps_half", "set_fps"},
                {"udp_enabled", "force_udp_only", "local_discovery_enabled", "orbot_enabled", "network_diagnostics"},
                {"start_on_boot"}};
        /**
         * Preferences that keep their key, default, listener and dependents but get no row here: the theme,
         * which Settings already sets, and switches that change nothing in this build, whose rows would
         * promise what they do not do. Taking a key out of this list brings its row back in its section.
         */
        private static final String[] NOT_DRAWN_KEYS = {
                // Settings, Theme writes dark_mode_pref too, through ThemeTransitionHelper.applyThemeChoice.
                "dark_mode_pref",
                // MainActivity reads it into PREF__compact_friendlist, which no list reads.
                "compact_friendlist",
                // MainActivity reads it into PREF__speakerphone_tweak, which nothing reads.
                "speakerphone_tweak",
                // MainActivity puts SAMPLE_RATE_FIXED into PREF__min_audio_samplingrate_out right after reading it.
                "min_audio_samplingrate_out",
                // MainActivity reads it into PREF__U_keep_nospam, and nothing acts on it: the warning icon it
                // drove (top_imageview2) went with the old header, and since QA live F16 the antispam changes
                // only when the user asks (HelperFriend.add_friend_to_system).
                "U_keep_nospam",
                // create_tox (jni-c-toxcore.c) sets UDP and LAN discovery from the Tor choice alone.
                "udp_enabled", "local_discovery_enabled"};
        /** The debug-only category of switches that weaken the profile: its rows read in the danger colour. */
        private static final String UNSAFE_CATEGORY = "cat_dev_unsafe";
        /** A SeekBarPreference, a class the SDK hides: its int is read and written as that preference does. */
        private static final String MIC_GAIN_KEY = "mic_gain_factor";
        /** The range of pref_general.xml, which MainActivity clamps the gain to as well. */
        private static final int MIC_GAIN_MIN = 1;
        private static final int MIC_GAIN_MAX = 30;

        /** Brings each Air row in step with its preference: the value, the words, enabled or not. */
        private final List<Runnable> airRows = new ArrayList<>();
        private final Runnable airRefresh = this::refreshAirRows;
        /** Every change of a preference (its value, summary, a dependency) reaches the list's adapter. */
        private final DataSetObserver airObserver = new DataSetObserver()
        {
            @Override
            public void onChanged()
            {
                postAirRefresh();
            }

            @Override
            public void onInvalidated()
            {
                postAirRefresh();
            }
        };
        @Nullable
        private ListAdapter observedAdapter;
        @Nullable
        private LinearLayout airColumn;
        /** The pick, edit or gain dialog on screen, closed with the view. */
        @Nullable
        private AlertDialog airDialog;

        @Override
        public void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_general);

            final Preference prefNetworkDiag = findPreference("network_diagnostics");
            if (prefNetworkDiag != null)
            {
                prefNetworkDiag.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener()
                {
                    @Override
                    public boolean onPreferenceClick(Preference preference)
                    {
                        startActivity(new android.content.Intent(getActivity(), NetworkDiagnosticsActivity.class));
                        return true;
                    }
                });
            }

            final SwitchPreference pref_keepnpspam = (SwitchPreference) findPreference("U_keep_nospam");

            if (pref_keepnpspam.isChecked() == true)
            {
                try
                {
                    final Drawable d1 = new IconicsDrawable(pref_keepnpspam.getContext()).
                            icon(FontAwesome.Icon.faw_exclamation_circle).
                            color(getResources().getColor(R.color.md_red_700)).sizeDp(100);
                    pref_keepnpspam.setIcon(d1);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }
            else
            {
                try
                {
                    pref_keepnpspam.setIcon(null);
                }
                catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            pref_keepnpspam.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener()
            {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue)
                {
                    // Here you can enable/disable whatever you need to
                    if (newValue == (Object) true)
                    {
                        try
                        {
                            final Drawable d1 = new IconicsDrawable(preference.getContext()).
                                    icon(FontAwesome.Icon.faw_exclamation_circle).
                                    color(getResources().getColor(R.color.md_red_600)).
                                    sizeDp(100);
                            preference.setIcon(d1);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                    else
                    {
                        try
                        {
                            preference.setIcon(null);
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                    return true;
                }
            });

            final SwitchPreference pref_startonboot = (SwitchPreference) findPreference("start_on_boot");

            pref_startonboot.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener()
            {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue)
                {
                    if (newValue == (Object) true)
                    {
                        try
                        {
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q)
                            {
                                // KHANDAQ (redesign, stage 3b): the old words sent people to "Display over
                                // other apps", a permission the app no longer holds (AndroidManifest.xml,
                                // M-01), so on Android 10 and later nothing can start the app after a
                                // restart; the dialog now says that in the app's language.
                                final AlertDialog dialog = new AlertDialog.Builder(preference.getContext())
                                        .setTitle(R.string.air_pref_boot_title)
                                        .setMessage(R.string.air_pref_boot_message)
                                        .setPositiveButton(R.string.air_pref_ok, null)
                                        .create();
                                AirUi.showDialog(preference.getContext(), dialog, false);
                            }
                        }
                        catch (Exception e)
                        {
                            e.printStackTrace();
                        }
                    }
                    else
                    {
                    }
                    return true;
                }
            });

            Preference pref_custom_bootstrap_nodes = (Preference) findPreference("custom_bootstrap_nodes");

            final SharedPreferences settings = change_custombootstrapnode_summary_text(pref_custom_bootstrap_nodes);

            pref_custom_bootstrap_nodes.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener()
            {
                @Override
                public boolean onPreferenceClick(Preference preference)
                {
                    LayoutInflater li = LayoutInflater.from(preference.getContext());
                    View promptsView = li.inflate(R.layout.custom_bootstrap_prompt, null);

                    AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(preference.getContext());

                    alertDialogBuilder.setView(promptsView);
                    final EditText edit_bootstrap_udp_ip = (EditText) promptsView.findViewById(
                            R.id.edit_bootstrap_udp_ip);
                    final EditText edit_bootstrap_udp_port = (EditText) promptsView.findViewById(
                            R.id.edit_bootstrap_udp_port);
                    final EditText edit_bootstrap_udp_keyhex = (EditText) promptsView.findViewById(
                            R.id.edit_bootstrap_udp_keyhex);
                    final EditText edit_bootstrap_tcp_ip = (EditText) promptsView.findViewById(
                            R.id.edit_bootstrap_tcp_ip);
                    final EditText edit_bootstrap_tcp_port = (EditText) promptsView.findViewById(
                            R.id.edit_bootstrap_tcp_port);
                    final EditText edit_bootstrap_tcp_keyhex = (EditText) promptsView.findViewById(
                            R.id.edit_bootstrap_tcp_keyhex);

                    alertDialogBuilder.setCancelable(false).setPositiveButton("OK",
                                                                              new DialogInterface.OnClickListener()
                                                                              {
                                                                                  public void onClick(DialogInterface dialog, int id)
                                                                                  {
                                                                                      try
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_UDP_IP,
                                                                                                  edit_bootstrap_udp_ip.getText().toString()).apply();
                                                                                      }
                                                                                      catch (Exception e)
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_UDP_IP,
                                                                                                  "").apply();
                                                                                      }

                                                                                      try
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_UDP_PORT,
                                                                                                  edit_bootstrap_udp_port.getText().toString()).apply();
                                                                                      }
                                                                                      catch (Exception e)
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_UDP_PORT,
                                                                                                  "").apply();
                                                                                      }

                                                                                      try
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_UDP_KEYHEX,
                                                                                                  edit_bootstrap_udp_keyhex.getText().toString().toUpperCase()).apply();
                                                                                      }
                                                                                      catch (Exception e)
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_UDP_KEYHEX,
                                                                                                  "").apply();
                                                                                      }

                                                                                      try
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_TCP_IP,
                                                                                                  edit_bootstrap_tcp_ip.getText().toString()).apply();
                                                                                      }
                                                                                      catch (Exception e)
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_TCP_IP,
                                                                                                  "").apply();
                                                                                      }

                                                                                      try
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_TCP_PORT,
                                                                                                  edit_bootstrap_tcp_port.getText().toString()).apply();
                                                                                      }
                                                                                      catch (Exception e)
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_TCP_PORT,
                                                                                                  "").apply();
                                                                                      }

                                                                                      try
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_TCP_KEYHEX,
                                                                                                  edit_bootstrap_tcp_keyhex.getText().toString().toUpperCase()).apply();
                                                                                      }
                                                                                      catch (Exception e)
                                                                                      {
                                                                                          settings.edit().putString(
                                                                                                  PREF_KEY_CUSTOM_BOOTSTRAP_TCP_KEYHEX,
                                                                                                  "").apply();
                                                                                      }

                                                                                      try
                                                                                      {
                                                                                          change_custombootstrapnode_summary_text(
                                                                                                  pref_custom_bootstrap_nodes);
                                                                                      }
                                                                                      catch (Exception e)
                                                                                      {
                                                                                      }
                                                                                  }
                                                                              }).setNegativeButton("Cancel",
                                                                                                   new DialogInterface.OnClickListener()
                                                                                                   {
                                                                                                       public void onClick(DialogInterface dialog, int id)
                                                                                                       {
                                                                                                           dialog.cancel();
                                                                                                       }
                                                                                                   });

                    AlertDialog alertDialog = alertDialogBuilder.create();
                    alertDialog.show();
                    return true;
                }
            });

            final ListPreference pref_dark_mode_pref = (ListPreference) findPreference("dark_mode_pref");

            pref_dark_mode_pref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener()
            {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue)
                {
                    try
                    {
                        final String value = (String) newValue;
                        // The choice is stored before any recreate, so the new activity reads it, not the
                        // previous theme. As the Settings tab's theme segment does, a choice that leaves the
                        // window as it is takes no snapshot that a later recreate would cross-fade over.
                        if (getActivity() != null)
                        {
                            ThemeTransitionHelper.applyThemeChoice(getActivity(), value);
                        }
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                    return true;
                }
            });

            // KHANDAQ: developer-only categories (network / ! unsafe ! / ! eXperimental !) expose raw
            // bootstrap & experimental toggles not meant for end users. Hidden in release builds (kept
            // in debug). Done at the END of onCreate so the findPreference setup above still runs.
            if (!org.khandaq.messenger.BuildConfig.DEBUG)
            {
                final android.preference.PreferenceScreen prefScreen = getPreferenceScreen();
                if (prefScreen != null)
                {
                    for (final String catKey : new String[]{"cat_dev_network", "cat_dev_unsafe",
                                                            "cat_dev_experimental"})
                    {
                        final Preference devCat = findPreference(catKey);
                        if (devCat != null)
                        {
                            prefScreen.removePreference(devCat);
                        }
                    }
                }
            }
        }

        /**
         * The Air column instead of the framework's list. That list (android:id/list) stays in the layout,
         * hidden: binding the preferences to it is what connects each dependent to its dependency.
         */
        @Override
        public View onCreateView(final LayoutInflater inflater, @Nullable final ViewGroup container,
                                 @Nullable final Bundle savedInstanceState)
        {
            final View view = inflater.inflate(R.layout.air_pref_screen, container, false);
            airColumn = view.findViewById(R.id.air_pref_column);
            return view;
        }

        @Override
        public void onActivityCreated(final Bundle savedInstanceState)
        {
            // The preferences are bound here, so the rows are built after it.
            super.onActivityCreated(savedInstanceState);
            buildAirRows();
        }

        @Override
        public void onResume()
        {
            super.onResume();
            refreshAirRows();
        }

        @Override
        public void onDestroyView()
        {
            if (observedAdapter != null)
            {
                observedAdapter.unregisterDataSetObserver(airObserver);
                observedAdapter = null;
            }
            if (airColumn != null)
            {
                airColumn.removeCallbacks(airRefresh);
                airColumn = null;
            }
            if (airDialog != null)
            {
                airDialog.dismiss();
                airDialog = null;
            }
            airRows.clear();
            super.onDestroyView();
        }

        /** The screen's heading, then the sections by task, then any category the sections do not take. */
        private void buildAirRows()
        {
            final LinearLayout column = airColumn;
            final PreferenceScreen screen = getPreferenceScreen();
            if (column == null || screen == null)
            {
                return;
            }
            final Context context = column.getContext();
            column.removeAllViews();
            airRows.clear();

            final TextView heading = AirScreenKit.text(context, R.style.TextAppearance_KQ_Heading, R.color.kq_ink);
            heading.setText(R.string.settings_advanced);
            ViewCompat.setAccessibilityHeading(heading, true);
            column.addView(heading, AirScreenKit.matchWidth());

            final Map<String, Preference> byKey = new HashMap<>();
            final Map<PreferenceGroup, List<Preference>> byCategory = new LinkedHashMap<>();
            collect(screen, byKey, byCategory);
            for (int section = 0; section < SECTION_KEYS.length; section++)
            {
                final List<View> rows = new ArrayList<>();
                for (final String key : SECTION_KEYS[section])
                {
                    final Preference preference = byKey.get(key);
                    if (preference != null)
                    {
                        rows.add(rowFor(context, preference, false));
                    }
                }
                addPlate(column, getString(SECTION_TITLES[section]), rows);
            }
            for (final Map.Entry<PreferenceGroup, List<Preference>> category : byCategory.entrySet())
            {
                final PreferenceGroup group = category.getKey();
                final boolean unsafe = UNSAFE_CATEGORY.equals(group.getKey());
                final List<View> rows = new ArrayList<>();
                for (final Preference preference : category.getValue())
                {
                    rows.add(rowFor(context, preference, unsafe));
                }
                addPlate(column, group == screen ? null : group.getTitle(), rows);
            }

            final ListAdapter adapter = screen.getRootAdapter();
            if (adapter != observedAdapter)
            {
                if (observedAdapter != null)
                {
                    observedAdapter.unregisterDataSetObserver(airObserver);
                }
                adapter.registerDataSetObserver(airObserver);
                observedAdapter = adapter;
            }
            refreshAirRows();
        }

        /** The preferences of a group, those a section lists by key and the others under their category. */
        private static void collect(final PreferenceGroup group, final Map<String, Preference> byKey,
                                    final Map<PreferenceGroup, List<Preference>> byCategory)
        {
            for (int i = 0; i < group.getPreferenceCount(); i++)
            {
                final Preference preference = group.getPreference(i);
                if (preference instanceof PreferenceGroup && !(preference instanceof PreferenceScreen))
                {
                    collect((PreferenceGroup) preference, byKey, byCategory);
                }
                else if (preference.getKey() != null && notDrawn(preference.getKey()))
                {
                    // No row: the preference stays in the tree, so its default and listener work as before.
                }
                else if (preference.getKey() != null && inSection(preference.getKey()))
                {
                    byKey.put(preference.getKey(), preference);
                }
                else
                {
                    List<Preference> rows = byCategory.get(group);
                    if (rows == null)
                    {
                        rows = new ArrayList<>();
                        byCategory.put(group, rows);
                    }
                    rows.add(preference);
                }
            }
        }

        private static boolean notDrawn(final String key)
        {
            for (final String hidden : NOT_DRAWN_KEYS)
            {
                if (hidden.equals(key))
                {
                    return true;
                }
            }
            return false;
        }

        private static boolean inSection(final String key)
        {
            for (final String[] keys : SECTION_KEYS)
            {
                for (final String listed : keys)
                {
                    if (listed.equals(key))
                    {
                        return true;
                    }
                }
            }
            return false;
        }

        private static void addPlate(final LinearLayout column, @Nullable final CharSequence title,
                                     final List<View> rows)
        {
            if (!rows.isEmpty())
            {
                AirScreenKit.addSection(column, title, null,
                                        AirScreenKit.plateOf(column.getContext(), rows.toArray(new View[0])));
            }
        }

        private View rowFor(final Context context, final Preference preference, final boolean unsafe)
        {
            if (preference instanceof TwoStatePreference)
            {
                return switchRow(context, (TwoStatePreference) preference, unsafe);
            }
            if (preference instanceof ListPreference)
            {
                return listRow(context, (ListPreference) preference, unsafe);
            }
            if (preference instanceof EditTextPreference)
            {
                return textRow(context, (EditTextPreference) preference, unsafe);
            }
            if (MIC_GAIN_KEY.equals(preference.getKey()))
            {
                return gainRow(context, preference, unsafe);
            }
            return actionRow(context, preference, unsafe);
        }

        private static AirSettingRow newRow(final Context context, final Preference preference, final boolean opens,
                                            final boolean unsafe)
        {
            final AirSettingRow row = new AirSettingRow(context, preference.getTitle(), opens);
            if (unsafe)
            {
                row.danger();
            }
            return row;
        }

        /** A switch the whole row toggles, through the change listener as the old row did. */
        private View switchRow(final Context context, final TwoStatePreference preference, final boolean unsafe)
        {
            final AirSettingRow row = newRow(context, preference, false, unsafe);
            final SwitchCompat toggle = row.withSwitch(preference.isChecked(), null);
            row.setOnClickListener(v -> {
                if (!takesTaps(preference))
                {
                    return;
                }
                final boolean next = !preference.isChecked();
                if (accepts(preference, next))
                {
                    preference.setChecked(next);
                }
                afterClick(preference);
                refreshAirRows();
            });
            airRows.add(() -> {
                // Only a change moves the knob: setting the same state again would still start its animator.
                if (toggle.isChecked() != preference.isChecked())
                {
                    toggle.setChecked(preference.isChecked());
                }
                row.detail(switchSummary(preference));
                showEnabled(row, preference.isEnabled());
            });
            return row;
        }

        /** The chosen entry at the trailing side; a tap opens the choice. */
        private View listRow(final Context context, final ListPreference preference, final boolean unsafe)
        {
            final AirSettingRow row = newRow(context, preference, true, unsafe);
            row.setOnClickListener(v -> {
                if (takesTaps(preference))
                {
                    pick(preference);
                    afterClick(preference);
                }
            });
            airRows.add(() -> {
                final CharSequence entry = preference.getEntry();
                row.setValue(entry == null ? null : entry.toString().replace('\n', ' '));
                row.detail(preference.getSummary());
                showEnabled(row, preference.isEnabled());
            });
            return row;
        }

        /** The text at the trailing side, its digits in the app's language when it is a number. */
        private View textRow(final Context context, final EditTextPreference preference, final boolean unsafe)
        {
            final AirSettingRow row = newRow(context, preference, true, unsafe);
            final boolean number = (preference.getEditText().getInputType() & InputType.TYPE_MASK_CLASS)
                                   == InputType.TYPE_CLASS_NUMBER;
            row.setOnClickListener(v -> {
                if (takesTaps(preference))
                {
                    edit(preference);
                    afterClick(preference);
                }
            });
            airRows.add(() -> {
                final String text = preference.getText();
                row.setValue(text == null || !number ? text : AirUi.localDigits(context, text));
                row.detail(preference.getSummary());
                showEnabled(row, preference.isEnabled());
            });
            return row;
        }

        /** The microphone gain, a SeekBarPreference: its number at the trailing side, a slider on a tap. */
        private View gainRow(final Context context, final Preference preference, final boolean unsafe)
        {
            final AirSettingRow row = newRow(context, preference, true, unsafe);
            row.setOnClickListener(v -> {
                if (takesTaps(preference))
                {
                    adjustGain(preference);
                    afterClick(preference);
                }
            });
            airRows.add(() -> {
                row.setValue(AirUi.localDigits(context, String.valueOf(gainOf(preference))));
                row.detail(preference.getSummary());
                showEnabled(row, preference.isEnabled());
            });
            return row;
        }

        /** A preference that only acts on a tap: its click listener, as network diagnostics. */
        private View actionRow(final Context context, final Preference preference, final boolean unsafe)
        {
            final AirSettingRow row = newRow(context, preference, true, unsafe);
            row.setOnClickListener(v -> {
                if (takesTaps(preference))
                {
                    afterClick(preference);
                    refreshAirRows();
                }
            });
            airRows.add(() -> {
                row.detail(preference.getSummary());
                showEnabled(row, preference.isEnabled());
            });
            return row;
        }

        /** The choice of a list, as radio rows; picking one closes it. */
        private void pick(final ListPreference preference)
        {
            final Activity activity = getActivity();
            final CharSequence[] entries = preference.getEntries();
            final CharSequence[] values = preference.getEntryValues();
            if (activity == null || entries == null || values == null)
            {
                return;
            }
            final Resources res = activity.getResources();
            final int current = preference.findIndexOfValue(preference.getValue());
            final LinearLayout choices = new LinearLayout(activity);
            choices.setOrientation(LinearLayout.VERTICAL);
            choices.setPaddingRelative(0, res.getDimensionPixelSize(R.dimen.kq_space_8), 0, 0);
            final ScrollView scroll = new ScrollView(activity);
            scroll.addView(choices, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                                 ViewGroup.LayoutParams.WRAP_CONTENT));
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(dialogTitle(preference))
                    .setView(scroll)
                    .setNegativeButton(R.string.cancel, null)
                    .create();
            final int side = res.getDimensionPixelSize(R.dimen.kq_space_24);
            final int count = Math.min(entries.length, values.length);
            for (int i = 0; i < count; i++)
            {
                final LinearLayout choice = AirScreenKit.choiceRow(activity, entries[i], null, side);
                AirScreenKit.applyChoice(choice, i == current);
                final String value = values[i].toString();
                final boolean chosen = i == current;
                choice.setOnClickListener(v -> {
                    dialog.dismiss();
                    // The old list called the listener for the entry already chosen too; nothing changes then.
                    if (!chosen && accepts(preference, value))
                    {
                        preference.setValue(value);
                    }
                    refreshAirRows();
                });
                choices.addView(choice, AirScreenKit.matchWidth());
            }
            show(dialog);
        }

        /** The text in a field that keeps the preference's input type and length limit. */
        private void edit(final EditTextPreference preference)
        {
            final Activity activity = getActivity();
            if (activity == null)
            {
                return;
            }
            final EditText source = preference.getEditText();
            final EditText input = dialogField(activity);
            // The input type sets one line or several, as the old field had it.
            input.setInputType(source.getInputType());
            input.setFilters(source.getFilters());
            input.setText(preference.getText());
            input.setSelection(input.length());
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(dialogTitle(preference))
                    .setView(dialogBox(activity, input))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.air_tray_save, (d, which) -> {
                        final String value = String.valueOf(input.getText());
                        if (accepts(preference, value))
                        {
                            preference.setText(value);
                        }
                        refreshAirRows();
                    })
                    .create();
            final Window window = dialog.getWindow();
            if (window != null)
            {
                window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
            dialog.setCanceledOnTouchOutside(false);
            show(dialog);
            input.requestFocus();
        }

        /** The gain on a slider from 1 to 30, its number above it; Save stores it as the old slider did. */
        private void adjustGain(final Preference preference)
        {
            final Activity activity = getActivity();
            if (activity == null)
            {
                return;
            }
            final Resources res = activity.getResources();
            final TextView number = AirScreenKit.text(activity, R.style.TextAppearance_KQ_Heading, R.color.kq_ink);
            number.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            final SeekBar slider = new SeekBar(activity);
            slider.setMin(MIC_GAIN_MIN);
            slider.setMax(MIC_GAIN_MAX);
            slider.setProgress(gainOf(preference));
            slider.setProgressTintList(ColorStateList.valueOf(AirUi.color(activity, R.color.kq_switch_on)));
            slider.setProgressBackgroundTintList(
                    ColorStateList.valueOf(AirUi.color(activity, R.color.kq_line_strong)));
            slider.setThumbTintList(ColorStateList.valueOf(AirUi.color(activity, R.color.kq_ink)));
            slider.setContentDescription(preference.getTitle());
            showGain(number, slider, slider.getProgress());
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener()
            {
                @Override
                public void onProgressChanged(final SeekBar seekBar, final int progress, final boolean fromUser)
                {
                    showGain(number, slider, progress);
                }

                @Override
                public void onStartTrackingTouch(final SeekBar seekBar)
                {
                }

                @Override
                public void onStopTrackingTouch(final SeekBar seekBar)
                {
                }
            });
            final LinearLayout box = new LinearLayout(activity);
            box.setOrientation(LinearLayout.VERTICAL);
            box.addView(number, AirScreenKit.matchWidth());
            final LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, res.getDimensionPixelSize(R.dimen.kq_size_target_android));
            sliderParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_8);
            box.addView(slider, sliderParams);
            final AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(preference.getTitle())
                    .setView(dialogBox(activity, box))
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton(R.string.air_tray_save, (d, which) -> {
                        final int value = Math.max(MIC_GAIN_MIN, Math.min(MIC_GAIN_MAX, slider.getProgress()));
                        final SharedPreferences prefs = preference.getSharedPreferences();
                        if (prefs != null && accepts(preference, value))
                        {
                            prefs.edit().putInt(preference.getKey(), value).apply();
                        }
                        refreshAirRows();
                    })
                    .create();
            show(dialog);
        }

        private static void showGain(final TextView number, final SeekBar slider, final int value)
        {
            final String text = AirUi.localDigits(number.getContext(), String.valueOf(value));
            number.setText(text);
            ViewCompat.setStateDescription(slider, text);
        }

        /** The gain as stored, kept to its range: 1 when nothing, or not a number, is stored. */
        private static int gainOf(final Preference preference)
        {
            int value = MIC_GAIN_MIN;
            final SharedPreferences prefs = preference.getSharedPreferences();
            if (prefs != null)
            {
                try
                {
                    value = prefs.getInt(preference.getKey(), MIC_GAIN_MIN);
                }
                catch (ClassCastException e)
                {
                    value = MIC_GAIN_MIN;
                }
            }
            return Math.max(MIC_GAIN_MIN, Math.min(MIC_GAIN_MAX, value));
        }

        /** As Preference.performClick: an off or unselectable preference takes no taps. */
        private static boolean takesTaps(final Preference preference)
        {
            return preference.isEnabled() && preference.isSelectable();
        }

        /** As Preference.callChangeListener: the listener may refuse the value. */
        private static boolean accepts(final Preference preference, final Object value)
        {
            final Preference.OnPreferenceChangeListener listener = preference.getOnPreferenceChangeListener();
            return listener == null || listener.onPreferenceChange(preference, value);
        }

        /** The rest of Preference.performClick: the click listener, the tree, then the preference's intent. */
        private void afterClick(final Preference preference)
        {
            final Preference.OnPreferenceClickListener click = preference.getOnPreferenceClickListener();
            if (click != null && click.onPreferenceClick(preference))
            {
                return;
            }
            final PreferenceScreen screen = getPreferenceScreen();
            if (screen != null && onPreferenceTreeClick(screen, preference))
            {
                return;
            }
            final Intent intent = preference.getIntent();
            if (intent != null)
            {
                preference.getContext().startActivity(intent);
            }
        }

        /** The words of a switch as the old row chose them: the on or off summary when set, else the summary. */
        @Nullable
        private static CharSequence switchSummary(final TwoStatePreference preference)
        {
            final CharSequence state = preference.isChecked() ? preference.getSummaryOn() : preference.getSummaryOff();
            return TextUtils.isEmpty(state) ? preference.getSummary() : state;
        }

        private static CharSequence dialogTitle(final DialogPreference preference)
        {
            final CharSequence title = preference.getDialogTitle();
            return title != null ? title : preference.getTitle();
        }

        /**
         * A row whose preference is off (its dependency not met) takes no taps and its words turn to the
         * quiet ink: every text of the row gets that colour for its disabled state, once.
         */
        private static void showEnabled(final View view, final boolean enabled)
        {
            if (view instanceof TextView)
            {
                final TextView text = (TextView) view;
                final ColorStateList colors = text.getTextColors();
                if (!colors.isStateful())
                {
                    text.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                                                         new int[]{AirUi.color(view.getContext(), R.color.kq_ink_3),
                                                                   colors.getDefaultColor()}));
                }
            }
            view.setEnabled(enabled);
            if (view instanceof ViewGroup)
            {
                final ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++)
                {
                    showEnabled(group.getChildAt(i), enabled);
                }
            }
        }

        /** The field of a dialog as the chat's rename dialog draws it (AirChatPanel keeps its own private). */
        private static EditText dialogField(final Context context)
        {
            final Resources res = context.getResources();
            final EditText input = new EditText(context);
            TextViewCompat.setTextAppearance(input, R.style.TextAppearance_KQ_Body);
            input.setTextColor(AirUi.color(context, R.color.kq_ink));
            input.setHintTextColor(AirUi.color(context, R.color.kq_ink_3));
            input.setBackgroundResource(R.drawable.air_bg_field);
            final int side = res.getDimensionPixelSize(R.dimen.kq_space_12);
            final int top = res.getDimensionPixelSize(R.dimen.kq_space_8);
            input.setPaddingRelative(side, top, side, top);
            input.setMinHeight(res.getDimensionPixelSize(R.dimen.kq_size_field));
            input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            return input;
        }

        /** The dialog's own side margins around a custom view. */
        private static View dialogBox(final Context context, final View content)
        {
            final Resources res = context.getResources();
            final FrameLayout box = new FrameLayout(context);
            final int side = res.getDimensionPixelSize(R.dimen.kq_space_24);
            box.setPaddingRelative(side, res.getDimensionPixelSize(R.dimen.kq_space_8), side, 0);
            box.addView(content, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                              ViewGroup.LayoutParams.WRAP_CONTENT));
            return box;
        }

        private void show(final AlertDialog dialog)
        {
            final Activity activity = getActivity();
            if (activity == null || activity.isFinishing())
            {
                return;
            }
            if (airDialog != null)
            {
                airDialog.dismiss();
            }
            airDialog = dialog;
            AirUi.showDialog(activity, dialog, false);
        }

        private void refreshAirRows()
        {
            if (airColumn == null)
            {
                return;
            }
            for (final Runnable row : airRows)
            {
                row.run();
            }
        }

        /** Many changes in a row (a value and its dependents) bring one refresh. */
        private void postAirRefresh()
        {
            final LinearLayout column = airColumn;
            if (column != null)
            {
                column.removeCallbacks(airRefresh);
                column.post(airRefresh);
            }
        }
    }

    @NonNull
    private static SharedPreferences change_custombootstrapnode_summary_text(Preference pref_custom_bootstrap_nodes)
    {
        pref_custom_bootstrap_nodes.setSummary("using default bootstrap nodes.");

        final SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(
                pref_custom_bootstrap_nodes.getContext());
        final String bs_udp_ip = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_UDP_IP, "");
        final String bs_udp_port = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_UDP_PORT, "");
        final String bs_udp_keyhex = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_UDP_KEYHEX, "");
        final String bs_tcp_ip = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_TCP_IP, "");
        final String bs_tcp_port = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_TCP_PORT, "");
        final String bs_tcp_keyhex = settings.getString(PREF_KEY_CUSTOM_BOOTSTRAP_TCP_KEYHEX, "");

        boolean udp_valid = false;
        boolean tcp_valid = false;

        if ((bs_udp_ip.length() > 0) && (bs_udp_port.length() > 0) && (IPisValid(bs_udp_ip)) &&
            (isIPPortValid(bs_udp_port)) && (is_valid_tox_public_key(bs_udp_keyhex)))
        {
            udp_valid = true;
        }

        if ((bs_tcp_ip.length() > 0) && (bs_tcp_port.length() > 0) && (IPisValid(bs_tcp_ip)) &&
            (isIPPortValid(bs_tcp_port)) && (is_valid_tox_public_key(bs_tcp_keyhex)))
        {
            tcp_valid = true;
        }

        if (udp_valid)
        {
            pref_custom_bootstrap_nodes.setSummary(
                    "using custom bootstrapnode:\nfor UDP: " + bs_udp_ip + ":" + bs_udp_port + "\nKEY: " +
                    bs_udp_keyhex);
        }

        if (tcp_valid)
        {
            if (!udp_valid)
            {
                pref_custom_bootstrap_nodes.setSummary(
                        "using custom bootstrapnode:\nfor TCP: " + bs_tcp_ip + ":" + bs_tcp_port + "\nKEY: " +
                        bs_tcp_keyhex);
            }
            else
            {
                pref_custom_bootstrap_nodes.setSummary(
                        "using custom bootstrapnode:\nfor TCP: " + bs_tcp_ip + ":" + bs_tcp_port + "\nKEY: " +
                        bs_tcp_keyhex);
            }
        }
        return settings;
    }

    @TargetApi(Build.VERSION_CODES.HONEYCOMB)
    public static class NotificationPreferenceFragment extends PreferenceFragment
    {
        @Override
        public void onCreate(Bundle savedInstanceState)
        {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.pref_notification);

            final Preference ringtonePref = findPreference("notifications_new_message_ringtone");
            if (ringtonePref != null)
            {
                ringtonePref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener()
                {
                    @Override
                    public boolean onPreferenceClick(Preference preference)
                    {
                        startActivity(new Intent(getActivity(), NotificationRingtoneSettingsActivity.class));
                        return true;
                    }
                });
                refreshRingtoneSummary();
            }
        }

        @Override
        public void onResume()
        {
            super.onResume();
            refreshRingtoneSummary();
        }

        private void refreshRingtoneSummary()
        {
            final Preference ringtonePref = findPreference("notifications_new_message_ringtone");
            if (ringtonePref == null)
            {
                return;
            }

            try
            {
                final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(
                        ringtonePref.getContext());
                ringtonePref.setSummary(NotificationRingtoneSettingsActivity.ringtoneSummary(
                        ringtonePref.getContext(), prefs));
            }
            catch (Exception ignored)
            {
            }
        }
    }
}
