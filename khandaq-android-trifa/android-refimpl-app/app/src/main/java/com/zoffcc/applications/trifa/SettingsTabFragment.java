package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.net.Uri;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;

import java.util.List;

import com.zoffcc.applications.trifa.ToxProfileImportHelper.ImportMode;

/**
 * KHANDAQ Settings (redesign, E18): sections by task, as design/redesign/02-plan.md lays them out:
 * appearance, notifications, privacy, profile transfer, network and about. Rows are AirSettingRow on
 * plates (AirScreenKit), built in code; every switch and picker reads and writes the same prefs as
 * before, so the change is in the look and the order, not in what a setting does.
 */
public class SettingsTabFragment extends Fragment
        implements AirBlockList.Listener, AirBlockList.SnapshotCallback
{
    private static final String TAG = "trifa.SettingsTab";

    /** The dark_mode_pref values in the order of the theme segment: as in system, light, dark. */
    private static final String[] THEME_VALUES = {"0", "2", "1"};

    private View settingsContent;
    private View prefsContainer;
    @Nullable
    private AirSettingRow blockedRow;
    /** KHANDAQ (redesign, E18): the network state in words, opening the Network screen. */
    private AirSettingRow connectionRow;
    /** The title the Connection row shows now, so the poll writes the row only when the state moves. */
    private int shownConnectionTitle = 0;
    private boolean viewGone = true;

    // KHANDAQ (#2): profile export/import run their own SAF pickers from Settings directly, so the
    // maintenance screen no longer flashes in between. Launchers must be registered before the
    // fragment reaches STARTED, so they are registered in onCreate().
    private ActivityResultLauncher<String[]> importProfileLauncher;
    private ActivityResultLauncher<String> exportProfileLauncher;
    // KHANDAQ (re-audit 2026-08-21, R-06): this row was the one place a normal user met "export", and
    // it went straight to the raw private key. It now offers the encrypted backup first, so the
    // encrypted container is what the ordinary migration/backup workflow actually produces.
    private ActivityResultLauncher<String> backupCreateLauncher;
    private char[] pendingBackupPassphrase;
    private PlaintextExportGate exportGate;
    /** E21: the plate of Export and Import; what came of a move stands under it. */
    @Nullable
    private View transferPlate;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        importProfileLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri ->
        {
            final AppCompatActivity a = (AppCompatActivity) getActivity();
            if (a != null && uri != null)
            {
                ToxProfileImportHelper.handlePickedUri(a, uri, ImportMode.REPLACE_EXISTING, null, transferPlate);
            }
        });

        exportProfileLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/octet-stream"), uri ->
                {
                    final AppCompatActivity a = (AppCompatActivity) getActivity();
                    if (a != null && uri != null)
                    {
                        ToxProfileImportHelper.handleExportDestination(a, uri, transferPlate);
                    }
                });

        // KHANDAQ (re-audit 2026-08-21, R-06): destination picker for the encrypted .kbk backup, the
        // new default of this row. Same plumbing as ExportActivity's.
        backupCreateLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument(PasswordBackupHelper.MIME_TYPE), uri ->
                {
                    final char[] pp = pendingBackupPassphrase;
                    pendingBackupPassphrase = null;
                    final Activity a = getActivity();
                    if (uri != null && pp != null && a != null)
                    {
                        PasswordBackupHelper.performBackup(a, uri, pp, transferPlate);
                    }
                    else if (pp != null)
                    {
                        java.util.Arrays.fill(pp, '\0');   // picker cancelled - never leave it in memory
                    }
                });

        exportGate = new PlaintextExportGate(this, () -> getActivity());
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
        final LinearLayout column = view.findViewById(R.id.settings_tab_column);
        final Context context = view.getContext();
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        AirScreenKit.addSection(column, getString(R.string.air_settings_appearance), null,
                                AirScreenKit.plateOf(context, themeRow(context, prefs), languageRow(context),
                                                     livingBeingEmojiRow(context, prefs)));

        final AirSettingRow preview = new AirSettingRow(context, getString(R.string.settings_notif_preview), false);
        preview.withSwitch(prefs.getBoolean("notification_show_content", false),
                           (button, checked) -> prefs.edit().putBoolean("notification_show_content", checked).apply());
        AirScreenKit.addSection(column, getString(R.string.air_settings_notifications), null,
                                AirScreenKit.plateOf(context, preview));

        // Privacy: the keys blocked on this device (design/DESIGN.md, "Block, on every client").
        blockedRow = new AirSettingRow(context, getString(R.string.air_blocked_title), true);
        blockedRow.setOnClickListener(v -> startActivity(new Intent(context, AirBlockedActivity.class)));
        AirScreenKit.addSection(column, getString(R.string.air_privacy_title),
                                getString(R.string.air_blocked_section_note), AirScreenKit.plateOf(context, blockedRow));

        transferPlate = AirScreenKit.plateOf(context,
                                             navigationRow(context, R.string.settings_export_profile,
                                                           this::startProfileExport),
                                             navigationRow(context, R.string.settings_import_profile,
                                                           this::startProfileImport));
        AirScreenKit.addSection(column, getString(R.string.air_settings_transfer),
                                getString(R.string.settings_import_subtitle), transferPlate);

        shownConnectionTitle = 0;
        connectionRow = navigationRow(context, R.string.air_settings_connection, () -> {
            final Activity activity = getActivity();
            if (activity != null)
            {
                AirNetworkActivity.open(activity);
            }
        });
        AirScreenKit.addSection(column, getString(R.string.air_settings_network), null,
                                AirScreenKit.plateOf(context, connectionRow, attachmentDownloadRow(context, prefs),
                                                     navigationRow(context, R.string.settings_advanced,
                                                                   this::showAdvanced)));

        AirScreenKit.addSection(column, getString(R.string.air_settings_about), null,
                                AirScreenKit.plateOf(context,
                                                     navigationRow(context, R.string.settings_faq, this::openFaq),
                                                     navigationRow(context, R.string.settings_about_app, () -> {
                                                         final Activity activity = getActivity();
                                                         if (activity != null)
                                                         {
                                                             activity.startActivity(new Intent(activity, Aboutpage.class));
                                                         }
                                                     })));

        viewGone = false;
        AirBlockList.addListener(this);
        AirBlockList.snapshotAsync(this);
    }

    @Override
    public void onResume()
    {
        super.onResume();
        followConnection(!isHidden());
    }

    @Override
    public void onPause()
    {
        followConnection(false);
        super.onPause();
    }

    /** The tabs are shown and hidden, not resumed, as the bottom bar switches between them. */
    @Override
    public void onHiddenChanged(final boolean hidden)
    {
        super.onHiddenChanged(hidden);
        followConnection(!hidden && isResumed());
    }

    /**
     * The Connection row follows the node while Settings is on screen, at the pace of the Network
     * screen: right after a start it says "connecting" and turns to "online" without a reopen.
     */
    private void followConnection(final boolean follow)
    {
        connectionHandler.removeCallbacks(connectionPoll);
        if (follow)
        {
            connectionPoll.run();
        }
    }

    private final android.os.Handler connectionHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private final Runnable connectionPoll = new Runnable()
    {
        @Override
        public void run()
        {
            if (connectionRow == null || !isAdded())
            {
                return;
            }
            final int title = AirNetworkActivity.titleOf(AirNetworkActivity.stateNow());
            if (title != shownConnectionTitle)
            {
                shownConnectionTitle = title;
                connectionRow.setValue(getString(title));
            }
            connectionHandler.postDelayed(this, AirNetworkActivity.POLL_MS);
        }
    };

    @Override
    public void onDestroyView()
    {
        followConnection(false);
        viewGone = true;
        AirBlockList.removeListener(this);
        blockedRow = null;
        connectionRow = null;
        transferPlate = null;
        super.onDestroyView();
    }

    @Override
    public void onAirBlockListChanged()
    {
        AirBlockList.snapshotAsync(this);
    }

    @Override
    public void onAirBlockListSnapshot(final List<AirBlockList.Entry> entries)
    {
        if (viewGone || blockedRow == null || !isAdded())
        {
            return;
        }
        blockedRow.setValue(entries.isEmpty() ? getString(R.string.air_blocked_none)
                                              : AirUi.countText(requireContext(), entries.size()));
    }

    /** A row that opens something; nothing opens during a call, as before. */
    private AirSettingRow navigationRow(final Context context, @StringRes final int title, final Runnable action)
    {
        final AirSettingRow row = new AirSettingRow(context, getString(title), true);
        row.setOnClickListener(v -> {
            if (Callstate.state == 0)
            {
                action.run();
            }
        });
        return row;
    }

    /**
     * The theme: as in system, light or dark, as in system unless chosen otherwise (E18). The window
     * cross-fades into the new theme without a restart.
     */
    private View themeRow(final Context context, final SharedPreferences prefs)
    {
        final Resources res = getResources();
        final LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_16);
        row.setPaddingRelative(side, res.getDimensionPixelSize(R.dimen.kq_space_12), side,
                               res.getDimensionPixelSize(R.dimen.kq_space_4));
        final TextView title = AirScreenKit.text(context, R.style.TextAppearance_KQ_Body, R.color.kq_ink);
        title.setText(R.string.air_settings_theme);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(title, AirScreenKit.matchWidth());
        final String current = prefs.getString("dark_mode_pref", "0");
        int selected = 0;
        for (int i = 0; i < THEME_VALUES.length; i++)
        {
            if (THEME_VALUES[i].equals(current))
            {
                selected = i;
            }
        }
        final CharSequence[] labels = {getString(R.string.air_theme_system), getString(R.string.air_theme_light),
                                       getString(R.string.air_theme_dark)};
        // TalkBack: "Theme: Light", not a bare "Light". Three radio rows when the segment does not fit.
        final LinearLayout segment = AirScreenKit.segment(context, getString(R.string.air_settings_theme), labels,
                                                          selected, index -> {
            final Activity activity = getActivity();
            if (activity != null && Callstate.state == 0)
            {
                ThemeTransitionHelper.applyThemeChoice(activity, THEME_VALUES[index]);
            }
        });
        final LinearLayout.LayoutParams params = AirScreenKit.matchWidth();
        params.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_4);
        row.addView(segment, params);
        return row;
    }

    // KHANDAQ (#248): the language picker sits next to the theme, the first place anyone looks.
    // It used to live on the maintenance screen behind several taps, listing 16 half-translated
    // languages; we now ship four and name each one in its own script.
    private static final String[] LANG_NATIVE_NAMES = {"English", "Русский", "العربية", "中文"};

    private AirSettingRow languageRow(final Context context)
    {
        final AirSettingRow row = new AirSettingRow(context, getString(R.string.settings_language), true);
        row.setValue(nativeNameForCurrentLanguage());

        row.setOnClickListener(v ->
        {
            if (Callstate.state != 0)
            {
                return; // never restart the UI mid-call
            }
            final int current = indexOfCurrentLanguage();
            final AlertDialog dialog = new AlertDialog.Builder(requireContext())
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
                            // The app comes back on this screen, already in the new language.
                            AppLocaleHelper.applyLocaleAndRestart(a, AppLocaleHelper.SUPPORTED_LANG_CODES[which], true);
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .create();
            AirUi.showDialog(requireContext(), dialog, false);
        });
        return row;
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

    // KHANDAQ (Figma "Attachment download"): picker Never / Wi-Fi only / Always, bound to the global
    // attachment_download_mode pref (also applied live to MainActivity.PREF__attachment_download_mode).
    private AirSettingRow attachmentDownloadRow(final Context context, final SharedPreferences prefs)
    {
        final AirSettingRow row = new AirSettingRow(context, getString(R.string.settings_attachment_download), true);
        final int[] modeLabels = {R.string.settings_dl_never, R.string.settings_dl_wifi, R.string.settings_dl_always};
        row.setValue(getString(modeLabels[clampDownloadMode(prefs.getInt("attachment_download_mode", 2))]));
        row.setOnClickListener(v ->
        {
            if (Callstate.state != 0)
            {
                return;
            }
            final int current = clampDownloadMode(prefs.getInt("attachment_download_mode", 2));
            final CharSequence[] items = {getString(R.string.settings_dl_never), getString(R.string.settings_dl_wifi),
                                          getString(R.string.settings_dl_always)};
            final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.settings_attachment_download)
                    .setSingleChoiceItems(items, current, (d, which) ->
                    {
                        prefs.edit().putInt("attachment_download_mode", which).apply();
                        MainActivity.PREF__attachment_download_mode = which;
                        row.setValue(getString(modeLabels[which]));
                        d.dismiss();
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .create();
            AirUi.showDialog(requireContext(), dialog, false);
        });
        return row;
    }

    private int clampDownloadMode(final int m)
    {
        return (m < 0 || m > 2) ? 2 : m;
    }

    /**
     * KHANDAQ (user request 17.08): the "hide emoji of living beings" switch.
     *
     * Separate from bindPrefSwitch because the flag alone changes nothing: EmojiManager is a
     * singleton that holds the category array handed to install(), so the picker would keep offering
     * the old set until the process restarted. Reinstalling the provider rebuilds it from the current
     * flag. Any picker already on screen keeps its old grid; it is rebuilt next time it opens, which
     * is why the row carries no "restart the app" wording: it does not need one.
     */
    private AirSettingRow livingBeingEmojiRow(final Context context, final SharedPreferences prefs)
    {
        final AirSettingRow row = new AirSettingRow(context, getString(R.string.settings_hide_living_emoji), false);
        row.detail(getString(R.string.settings_hide_living_emoji_hint));
        row.withSwitch(prefs.getBoolean("hide_living_being_emoji", false), (buttonView, isChecked) ->
        {
            prefs.edit().putBoolean("hide_living_being_emoji", isChecked).apply();
            MainActivity.PREF__hide_living_being_emoji = isChecked;
            try
            {
                com.vanniktech.emoji.EmojiManager.install(new KhandaqGoogleEmojiProvider());
            }
            catch (Exception e)
            {
                Log.w(TAG, "hide_living_being_emoji:reinstall:EE:" + e.getMessage());
            }
        });
        return row;
    }

    // KHANDAQ (#2): export runs from Settings directly, with no maintenance screen in
    // between. (R-06 put a format choice in front of the picker; the picker is still launched here.)
    /**
     * KHANDAQ (re-audit 2026-08-21, R-06): the choice, with the encrypted container as the default
     * action. The raw .tox file is still reachable - other Tox clients read nothing else - but it is
     * now the labelled alternative and it costs a warning plus the device credential.
     */
    private void startProfileExport()
    {
        final AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity == null || Callstate.state != 0 || exportProfileLauncher == null
            || backupCreateLauncher == null || exportGate == null)
        {
            return;
        }

        // E21: a new try takes the words of the last one away, and the choice comes from below: the
        // encrypted backup is the verb, the plain file the other way on under it.
        AirUi.endUnder(transferPlate);
        final AirSheet sheet = AirSheet.create(activity, getString(R.string.settings_export_choose_title),
                                               getString(R.string.settings_export_choose_message));
        if (sheet == null)
        {
            return;
        }
        sheet.verbThen(getString(R.string.air_transfer_backup_verb), false, () -> startEncryptedBackup(activity));
        sheet.otherVerb(getString(R.string.air_transfer_plain_verb),
                        () -> ToxProfileImportHelper.promptExportSavedata(exportGate,
                                () -> exportProfileLauncher.launch(ToxProfileImportHelper.EXPORT_SUGGESTED_FILENAME)));
        sheet.cancel(null);
        sheet.show();
    }

    /** KHANDAQ (re-audit 2026-08-21, R-06): passphrase, then destination, then the .kbk container. */
    private void startEncryptedBackup(final AppCompatActivity activity)
    {
        PasswordBackupHelper.promptForBackupPassphrase(activity, passphrase ->
        {
            pendingBackupPassphrase = passphrase;
            backupCreateLauncher.launch(PasswordBackupHelper.SUGGESTED_FILENAME);
        });
    }

    // KHANDAQ (#2): import runs the replace-confirm dialog then the SAF OpenDocument picker directly.
    private void startProfileImport()
    {
        final AppCompatActivity activity = (AppCompatActivity) getActivity();
        if (activity == null || Callstate.state != 0 || importProfileLauncher == null)
        {
            return;
        }
        AirUi.endUnder(transferPlate);
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

    /**
     * Advanced settings (stage 3b: Air rows over the old preferences). A theme chosen there recreates the
     * activity, which brings the old Advanced back without its container on screen; that one goes first,
     * so a single Back leaves Advanced again.
     */
    private void showAdvanced()
    {
        if (!isAdded())
        {
            return;
        }
        try
        {
            requireActivity().getFragmentManager().popBackStackImmediate(
                    "settings_tab_pref", android.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        catch (Exception e)
        {
            Log.w(TAG, "showAdvanced: an earlier Advanced stays on the back stack: " + e.getMessage());
        }
        showPreferenceFragment(new SettingsActivity.GeneralPreferenceFragment());
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
