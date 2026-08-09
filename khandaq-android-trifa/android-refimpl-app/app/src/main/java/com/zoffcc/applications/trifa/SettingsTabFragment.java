package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

import com.zoffcc.applications.trifa.ToxProfileImportHelper.ImportMode;

/**
 * KHANDAQ Settings tab (Figma 2031:11056): grouped-card list with inline toggles. Export/import,
 * advanced, FAQ and about are navigation rows; the dark-theme / notification-preview / group-system
 * switches read & write the existing prefs (no behaviour change vs the old preference screens).
 */
public class SettingsTabFragment extends Fragment
{
    private View settingsContent;
    private View prefsContainer;

    // KHANDAQ (#2): profile export/import run their own SAF pickers from Settings directly, so the
    // «Техническое обслуживание» screen no longer flashes in between. Launchers MUST be registered
    // before the fragment reaches STARTED → do it in onCreate().
    private ActivityResultLauncher<String[]> importProfileLauncher;
    private ActivityResultLauncher<String> exportProfileLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        importProfileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri ->
        {
            final AppCompatActivity a = (AppCompatActivity) getActivity();
            if (a != null && uri != null)
            {
                ToxProfileImportHelper.handlePickedUri(a, uri, ImportMode.REPLACE_EXISTING, null);
            }
        });

        exportProfileLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), uri ->
                {
                    final AppCompatActivity a = (AppCompatActivity) getActivity();
                    if (a != null && uri != null)
                    {
                        ToxProfileImportHelper.handleExportDestination(a, uri);
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        return inflater.inflate(R.layout.fragment_settings_tab, container, false);
    }

    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState)
    {
        super.onViewCreated(view, savedInstanceState);

        settingsContent = view.findViewById(R.id.settings_tab_content);
        prefsContainer = view.findViewById(R.id.settings_tab_prefs_container);

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());

        // --- navigation rows ---
        bindRow(view, R.id.setting_export, this::startProfileExport);
        bindRow(view, R.id.setting_import, this::startProfileImport);
        bindRow(view, R.id.setting_advanced,
                () -> showPreferenceFragment(new SettingsActivity.GeneralPreferenceFragment()));
        bindRow(view, R.id.setting_faq, this::openFaq);
        bindRow(view, R.id.setting_about, () -> {
            final MainActivity activity = (MainActivity) getActivity();
            if (activity != null && Callstate.state == 0)
            {
                activity.startActivity(new Intent(activity, Aboutpage.class));
            }
        });

        // --- inline toggles ---
        final SwitchMaterial darkSwitch = view.findViewById(R.id.switch_dark_theme);
        if (darkSwitch != null)
        {
            // Telegram-style: the row is always «Тёмная тема» (static label from the layout);
            // switch ON = dark active, OFF = light. Tester feedback: the old dynamic
            // current-theme label read inverted («Светлая тема» + OFF while light was active).
            darkSwitch.setChecked(isNightModeActive());
            darkSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener()
            {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked)
                {
                    if (isChecked == isNightModeActive())
                    {
                        return; // already in the requested mode
                    }
                    final MainActivity activity = (MainActivity) getActivity();
                    if (activity != null)
                    {
                        activity.toggleDarkModeFromToolbar();
                    }
                }
            });
        }

        bindLanguageRow(view);

        bindPrefSwitch(view, R.id.switch_notif_preview, prefs, "notification_show_content", false);
        // KHANDAQ: the 'Системные сообщения групп' toggle was removed from the UI (user request);
        // the pref keeps its default (hidden), no switch to bind.

        bindAttachmentDownloadRow(view, prefs);
    }

    // KHANDAQ (#248): language picker right under the theme switch — the first place anyone looks.
    // It used to live on the maintenance screen behind several taps, listing 16 half-translated
    // languages; we now ship four and name each one in its own script.
    private static final String[] LANG_NATIVE_NAMES = {"English", "Русский", "العربية", "中文"};

    private void bindLanguageRow(final View root)
    {
        final View row = root.findViewById(R.id.setting_language);
        final android.widget.TextView value = root.findViewById(R.id.setting_language_value);
        if ((row == null) || (value == null))
        {
            return;
        }

        value.setText(nativeNameForCurrentLanguage());

        row.setOnClickListener(v ->
        {
            if (Callstate.state != 0)
            {
                return; // never restart the UI mid-call
            }
            final int current = indexOfCurrentLanguage();
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_language_title)
                    .setSingleChoiceItems(LANG_NATIVE_NAMES, current, (d, which) ->
                    {
                        d.dismiss();
                        if (which == current)
                        {
                            return;
                        }
                        final androidx.appcompat.app.AppCompatActivity a =
                                (androidx.appcompat.app.AppCompatActivity) getActivity();
                        if (a != null)
                        {
                            AppLocaleHelper.applyLocaleAndRestart(a, AppLocaleHelper.SUPPORTED_LANG_CODES[which]);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private static int indexOfCurrentLanguage()
    {
        final String code = AppLocaleHelper.currentLangCode();
        for (int i = 0; i < AppLocaleHelper.SUPPORTED_LANG_CODES.length; i++)
        {
            if (AppLocaleHelper.SUPPORTED_LANG_CODES[i].equals(code)) { return i; }
        }
        return 0;
    }

    private static String nativeNameForCurrentLanguage()
    {
        return LANG_NATIVE_NAMES[indexOfCurrentLanguage()];
    }

    // KHANDAQ (Figma "Загрузка вложений"): picker Никогда / Только Wi-Fi / Всегда, bound to the global
    // attachment_download_mode pref (also applied live to MainActivity.PREF__attachment_download_mode).
    private void bindAttachmentDownloadRow(final View root, final SharedPreferences prefs)
    {
        final View row = root.findViewById(R.id.setting_attachment_download);
        final android.widget.TextView value = root.findViewById(R.id.setting_attachment_download_value);
        if (row == null || value == null)
        {
            return;
        }
        final int[] modeLabels = {R.string.settings_dl_never, R.string.settings_dl_wifi, R.string.settings_dl_always};
        value.setText(modeLabels[clampDownloadMode(prefs.getInt("attachment_download_mode", 2))]);
        row.setOnClickListener(v ->
        {
            if (Callstate.state != 0)
            {
                return;
            }
            final int current = clampDownloadMode(prefs.getInt("attachment_download_mode", 2));
            final CharSequence[] items = {getString(R.string.settings_dl_never), getString(R.string.settings_dl_wifi),
                                          getString(R.string.settings_dl_always)};
            new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_attachment_download)
                    .setSingleChoiceItems(items, current, (d, which) ->
                    {
                        prefs.edit().putInt("attachment_download_mode", which).apply();
                        MainActivity.PREF__attachment_download_mode = which;
                        value.setText(modeLabels[which]);
                        d.dismiss();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        });
    }

    private int clampDownloadMode(final int m)
    {
        return (m < 0 || m > 2) ? 2 : m;
    }

    private void bindRow(final View root, final int id, final Runnable action)
    {
        final View row = root.findViewById(id);
        if (row != null)
        {
            row.setOnClickListener(v -> {
                if (Callstate.state == 0)
                {
                    action.run();
                }
            });
        }
    }

    private void bindPrefSwitch(final View root, final int id, final SharedPreferences prefs,
                                final String key, final boolean def)
    {
        final SwitchMaterial sw = root.findViewById(id);
        if (sw == null)
        {
            return;
        }
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((buttonView, isChecked) ->
                prefs.edit().putBoolean(key, isChecked).apply());
    }

    private boolean isNightModeActive()
    {
        final int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    // KHANDAQ (#2): export runs the SAF CreateDocument picker straight from Settings (confirm dialog →
    // system file picker), no «Техническое обслуживание» screen in between.
    private void startProfileExport()
    {
        final AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity == null || Callstate.state != 0 || exportProfileLauncher == null)
        {
            return;
        }
        ToxProfileImportHelper.promptExportSavedata(activity,
                () -> exportProfileLauncher.launch(ToxProfileImportHelper.EXPORT_SUGGESTED_FILENAME));
    }

    // KHANDAQ (#2): import runs the replace-confirm dialog then the SAF OpenDocument picker directly.
    private void startProfileImport()
    {
        final AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity == null || Callstate.state != 0 || importProfileLauncher == null)
        {
            return;
        }
        ToxProfileImportHelper.showReplaceImportConfirmation(activity,
                () -> importProfileLauncher.launch(ToxProfileImportHelper.TOX_IMPORT_MIME_TYPES));
    }

    private void openFaq()
    {
        try
        {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://khandaq.org")));
        }
        catch (Exception ignored)
        {
        }
    }

    public boolean handleBackPress()
    {
        if (prefsContainer == null || settingsContent == null)
        {
            return false;
        }

        if (prefsContainer.getVisibility() != View.VISIBLE)
        {
            return false;
        }

        final android.app.FragmentManager fragmentManager = requireActivity().getFragmentManager();
        if (fragmentManager.getBackStackEntryCount() > 0)
        {
            fragmentManager.popBackStackImmediate();
            if (fragmentManager.getBackStackEntryCount() == 0)
            {
                showHeaderList();
            }
            return true;
        }

        showHeaderList();
        return true;
    }

    private void showPreferenceFragment(final android.app.Fragment fragment)
    {
        if (!isAdded())
        {
            return;
        }

        settingsContent.setVisibility(View.GONE);
        prefsContainer.setVisibility(View.VISIBLE);

        try
        {
            requireActivity().getFragmentManager().beginTransaction()
                    .replace(R.id.settings_tab_prefs_container, fragment)
                    .addToBackStack("settings_tab_pref")
                    .commitAllowingStateLoss();
        }
        catch (Exception e)
        {
            showHeaderList();
        }
    }

    private void showHeaderList()
    {
        settingsContent.setVisibility(View.VISIBLE);
        prefsContainer.setVisibility(View.GONE);
    }
}
