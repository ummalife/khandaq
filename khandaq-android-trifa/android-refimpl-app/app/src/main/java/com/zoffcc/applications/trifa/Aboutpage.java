/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 */

package com.zoffcc.applications.trifa;

import org.khandaq.messenger.BuildConfig;
import org.khandaq.messenger.KhandaqSupport;
import org.khandaq.messenger.R;

import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.util.Log;
import android.widget.TextView;

import com.zoffcc.applications.logging.Logging;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.text.BidiFormatter;
import androidx.core.text.TextDirectionHeuristicsCompat;
import androidx.core.view.ViewCompat;

import static com.zoffcc.applications.trifa.HelperGeneric.display_toast_with_context;
import static com.zoffcc.applications.trifa.HelperGeneric.get_trifa_build_str;
import static com.zoffcc.applications.trifa.MainActivity.main_activity_s;

/**
 * Settings > About (E18). Since redesign stage 5 it is drawn as the other sections of Settings, in the
 * order of SettingsAboutController on iOS: the version and the build, the site and the owner, then
 * toxcore's version, then the licences; after them what only Android has here, the support rows, and in
 * a debug build the build details. Rows that only say a value are read as text, not as buttons.
 */
public class Aboutpage extends AirScreenActivity implements Logging.AsyncResponse
{
    private static final String TAG = "trifa.Aboutpage";

    ProgressDialog progressDialog2 = null;
    /** Send crash report: a refusal stands under it. */
    private AirSettingRow crashRow;

    @Override
    protected int screenTitle()
    {
        return R.string.settings_about_app;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        addVersionSections();
        addLegalSection();
        addSupportSection();
        // KHANDAQ (redesign, E18): the build details belong to debug builds only; seven taps on the
        // version no longer open them in a release (design/DESIGN.md, "Settings on the phone").
        if (BuildConfig.DEBUG)
        {
            addDebugSection();
        }
    }

    /** The version, the build, the site and the owner; then toxcore's version, as on iOS. */
    private void addVersionSections()
    {
        final String build = get_trifa_build_str();
        final AirSettingRow version = valueRow(R.string.air_about_version, BuildConfig.VERSION_NAME,
                                               BuildConfig.VERSION_NAME);
        final AirSettingRow buildRow = valueRow(R.string.air_about_build, AirAboutText.breakableAtSeparators(build),
                                                build);

        final AirSettingRow website = new AirSettingRow(this, getString(R.string.Aboutpage_website), true);
        website.setValue(ltr("khandaq.org"));
        website.setOnClickListener(v -> openUrl(KhandaqSupport.WEBSITE_URL));

        // KHANDAQ (18.08): credit the owner/developer on the same openUrl() path as the website and
        // licenses rows, so the link opens in the system browser and a missing browser stays swallowed.
        final AirSettingRow credit = new AirSettingRow(this, getString(R.string.about_credit_owner), true);
        credit.setValue(ltr("1sa.me"));
        credit.setOnClickListener(v -> openUrl(KhandaqSupport.DEVELOPER_URL));

        AirScreenKit.addSection(column, null, null, AirScreenKit.plateOf(this, version, buildRow, website, credit));

        final String toxcore = toxcoreVersion();
        if (toxcore != null)
        {
            AirScreenKit.addSection(column, null, null,
                                    AirScreenKit.plateOf(this, valueRow(R.string.air_about_toxcore_version, toxcore,
                                                                        toxcore)));
        }
    }

    /** The licences and what Tox is, with the licence of Khandaq itself under them. */
    private void addLegalSection()
    {
        final AirSettingRow licences = new AirSettingRow(this, getString(R.string.Aboutpage_opensource), true);
        licences.setOnClickListener(v -> openUrl(KhandaqSupport.LICENSES_URL));
        final AirSettingRow tox = new AirSettingRow(this, getString(R.string.Aboutpage_6), true);
        tox.setOnClickListener(v -> openUrl(KhandaqSupport.TOX_INFO_URL));
        AirScreenKit.addSection(column, getString(R.string.about_section_legal), getString(R.string.Aboutpage_license),
                                AirScreenKit.plateOf(this, licences, tox));
    }

    /**
     * Android only: the crash report, the diagnostics for support and the feedback address. The two rows
     * that hand over to a mail app carry the chevron; copying stays on this screen and has none.
     */
    private void addSupportSection()
    {
        crashRow = new AirSettingRow(this, getString(R.string.Aboutpage_2), true)
                .detail(getString(R.string.about_crash_report_subtitle));
        crashRow.setOnClickListener(v -> sendCrashReport());

        final AirSettingRow diagnostics = new AirSettingRow(this, getString(R.string.about_copy_diagnostics), false)
                .detail(getString(R.string.about_copy_diagnostics_subtitle));
        diagnostics.setOnClickListener(v -> copyDiagnostics());

        final AirSettingRow feedback = new AirSettingRow(this, getString(R.string.about_send_feedback), true)
                .detail(ltr(KhandaqSupport.FEEDBACK_EMAIL));
        feedback.setOnClickListener(v -> sendFeedbackEmail());

        AirScreenKit.addSection(column, getString(R.string.about_section_support), null,
                                AirScreenKit.plateOf(this, crashRow, diagnostics, feedback));
    }

    /** Debug builds only: what the diagnostics say with the commits, and a row for each commit. */
    private void addDebugSection()
    {
        // Each line takes its own direction, so a translated line reads right to left and the Latin ones
        // keep their order; the words stay selectable for support, as before.
        final TextView details = AirScreenKit.text(this, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_2);
        details.setTextIsSelectable(true);
        details.setText(buildDiagnosticsText(true));

        final String gitHash = BuildConfig.GitHash;
        final String jniHash = MainActivity.getNativeLibGITHASH();
        final String toxcoreHash = MainActivity.getNativeLibTOXGITHASH();
        AirScreenKit.addSection(column, getString(R.string.about_section_debug), null, details,
                                AirScreenKit.plateOf(this,
                                                     commitRow(R.string.about_upstream_commit, gitHash,
                                                               "https://github.com/zoff99/ToxAndroidRefImpl/commit/"
                                                               + gitHash),
                                                     commitRow(R.string.about_jni_commit, jniHash,
                                                               "https://github.com/zoff99/ToxAndroidRefImpl/commit/"
                                                               + jniHash),
                                                     commitRow(R.string.about_toxcore_commit, toxcoreHash,
                                                               "https://github.com/zoff99/c-toxcore/commit/"
                                                               + toxcoreHash)));
    }

    private AirSettingRow commitRow(@StringRes final int title, final String hash, final String url)
    {
        final AirSettingRow row = new AirSettingRow(this, getString(title), true).detail(ltr(hash));
        row.setOnClickListener(v -> openUrl(url));
        return row;
    }

    /**
     * A row that only says a value, as valueRow on iOS: no chevron, not a button to TalkBack, its title
     * and value read as one; {@code spoken} is the value without the line breaks of {@code shown}.
     */
    private AirSettingRow valueRow(@StringRes final int title, final String shown, final String spoken)
    {
        final AirSettingRow row = new AirSettingRow(this, getString(title), false);
        row.setValue(ltr(shown));
        row.setClickable(false);
        row.setFocusable(false);
        ViewCompat.setAccessibilityDelegate(row, null);
        ViewCompat.setScreenReaderFocusable(row, true);
        row.setContentDescription(getString(title) + ". " + spoken);
        return row;
    }

    /** A Latin value (a version, a host, an address) keeps its order inside a right-to-left line. */
    private static String ltr(final String value)
    {
        return BidiFormatter.getInstance().unicodeWrap(value == null ? "" : value, TextDirectionHeuristicsCompat.LTR);
    }

    /** toxcore's version, or null when the native library does not answer. */
    @Nullable
    private static String toxcoreVersion()
    {
        try
        {
            return AirAboutText.toxcoreVersion(MainActivity.tox_version_major(), MainActivity.tox_version_minor(),
                                               MainActivity.tox_version_patch());
        }
        catch (Throwable e)
        {
            Log.i(TAG, "toxcoreVersion:EE1:" + e.getMessage());
            return null;
        }
    }

    private void openUrl(String url)
    {
        try
        {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        }
        catch (Exception e)
        {
            Log.i(TAG, "openUrl:EE1:" + e.getMessage());
        }
    }

    private void sendFeedbackEmail()
    {
        try
        {
            Intent emailIntent = new Intent(Intent.ACTION_SENDTO,
                    Uri.fromParts("mailto", KhandaqSupport.FEEDBACK_EMAIL, null));
            emailIntent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_feedback_subject));
            startActivity(Intent.createChooser(emailIntent, getString(R.string.about_send_feedback)));
        }
        catch (Exception e)
        {
            Log.i(TAG, "sendFeedbackEmail:EE1:" + e.getMessage());
        }
    }

    private void sendCrashReport()
    {
        try
        {
            progressDialog2 = ProgressDialog.show(this, "", getString(R.string.Aboutpage_4));
            progressDialog2.setCanceledOnTouchOutside(false);
            progressDialog2.setOnCancelListener(new DialogInterface.OnCancelListener()
            {
                @Override
                public void onCancel(DialogInterface dialog)
                {
                }
            });

            Logging x = new Logging();
            Logging.delegate = Aboutpage.this;
            x.new PopulateLogcatAsyncTask(getApplicationContext()).execute();
        }
        catch (Exception e)
        {
            Log.i(TAG, "sendCrashReport:EE1:" + e.getMessage());
        }
    }

    private void copyDiagnostics()
    {
        try
        {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null)
            {
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("Khandaq diagnostics", buildDiagnosticsText(false)));
            display_toast_with_context(this, getString(R.string.about_copy_diagnostics_done), false, 0);
        }
        catch (Exception e)
        {
            Log.i(TAG, "copyDiagnostics:EE1:" + e.getMessage());
        }
    }

    private String buildDiagnosticsText(boolean includeCommits)
    {
        String abis = getPrimaryAbi();
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.Aboutpage_5a)).append('\n');
        sb.append(getString(R.string.about_version_format, BuildConfig.VERSION_NAME, get_trifa_build_str())).append('\n');
        sb.append(getString(R.string.Aboutpage_protocol)).append('\n');
        sb.append(getString(R.string.Aboutpage_license)).append('\n');
        sb.append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append('\n');
        sb.append("CPU ABI: ").append(abis).append('\n');

        if (includeCommits)
        {
            sb.append('\n');
            sb.append("BuildType: ").append(BuildConfig.BUILD_TYPE).append('\n');
            sb.append("Source commit: ").append(BuildConfig.GitHash).append('\n');
            sb.append("JNI: ").append(MainActivity.getNativeLibGITHASH()).append('\n');
            sb.append("c-toxcore: ").append(MainActivity.getNativeLibTOXGITHASH()).append('\n');
            sb.append(getString(R.string.Aboutpage_upstream)).append('\n');
        }

        return sb.toString();
    }

    private static String getPrimaryAbi()
    {
        try
        {
            return Build.SUPPORTED_ABIS[0];
        }
        catch (Exception e)
        {
            try
            {
                return Build.CPU_ABI;
            }
            catch (Exception e2)
            {
                return "??";
            }
        }
    }

    @Override
    public void processFinish(String output_part1)
    {
        String output = output_part1 + System.getProperty("line.separator") + System.getProperty("line.separator") +
                        "LastStackTrace:" + System.getProperty("line.separator") +
                        MainApplication.last_stack_trace_as_string;
        MainApplication.last_stack_trace_as_string = "";

        String DATA_DEBUG_DIR = new File(Environment.getExternalStorageDirectory().getAbsolutePath() +
                                         "/trifa/crashes").toString();

        String date = new SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.GERMAN).format(new Date());
        String full_file_name = DATA_DEBUG_DIR + "/crash_" + date + ".txt";
        String full_file_name_suppl = DATA_DEBUG_DIR + "/crash_single.txt";
        String feedback_text = "If there is no file attached, please attach:\n" + full_file_name +
                               "\nto this email.";

        Logging.writeToFile(output, Aboutpage.this, full_file_name);

        try
        {
            new Handler().post(new Runnable()
            {
                @Override
                public void run()
                {
                    if (progressDialog2 != null)
                    {
                        progressDialog2.dismiss();
                    }
                }
            });
        }
        catch (Exception ee)
        {
        }

        try
        {
            if (main_activity_s != null)
            {
                main_activity_s.sendEmailWithAttachment(this,
                        KhandaqSupport.FEEDBACK_EMAIL,
                        getString(R.string.Aboutpage_0) + " (a:" + Build.VERSION.SDK_INT + ")",
                        feedback_text,
                        full_file_name,
                        full_file_name_suppl);
            }
            else
            {
                // E21: the refusal stands under the row that was tapped, a toast only without one.
                final String words = getString(R.string.about_crash_report_unavailable);
                if (!AirUi.sayUnder(crashRow, words, false))
                {
                    display_toast_with_context(this, words, true, 0);
                }
            }
        }
        catch (Exception ee3)
        {
            Log.i(TAG, "processFinish:EE1:" + ee3.getMessage());
        }
    }
}
