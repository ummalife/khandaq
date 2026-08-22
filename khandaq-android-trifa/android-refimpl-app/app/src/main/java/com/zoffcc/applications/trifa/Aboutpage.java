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
import android.view.View;
import android.widget.TextView;

import com.zoffcc.applications.logging.Logging;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import static com.zoffcc.applications.trifa.HelperGeneric.display_toast_with_context;
import static com.zoffcc.applications.trifa.HelperGeneric.get_trifa_build_str;
import static com.zoffcc.applications.trifa.MainActivity.main_activity_s;

public class Aboutpage extends AppCompatActivity implements Logging.AsyncResponse
{
    private static final String TAG = "trifa.Aboutpage";
    private static final int DEBUG_TAP_COUNT = 7;

    ProgressDialog progressDialog2 = null;
    private int versionTapCount = 0;
    private boolean debugSectionUnlocked = false;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        HelperToolbar.enableUpNavigation(this, toolbar);

        bindHeader();
        bindActionRows();
        configureDebugSection(BuildConfig.DEBUG);
    }

    private void bindHeader()
    {
        TextView versionView = findViewById(R.id.about_version);
        String versionText = getString(R.string.about_version_format,
                BuildConfig.VERSION_NAME,
                get_trifa_build_str());
        versionView.setText(versionText);
        versionView.setOnClickListener(v -> onVersionTapped());
    }

    private void onVersionTapped()
    {
        versionTapCount++;
        if (debugSectionUnlocked || versionTapCount < DEBUG_TAP_COUNT)
        {
            return;
        }

        debugSectionUnlocked = true;
        configureDebugSection(true);
        display_toast_with_context(this, getString(R.string.about_debug_unlocked), false, 0);
    }

    private void bindActionRows()
    {
        bindActionRow(findViewById(R.id.about_crash_report_row),
                getString(R.string.Aboutpage_2),
                getString(R.string.about_crash_report_subtitle),
                this::sendCrashReport);

        bindActionRow(findViewById(R.id.about_copy_diagnostics_row),
                getString(R.string.about_copy_diagnostics),
                getString(R.string.about_copy_diagnostics_subtitle),
                this::copyDiagnostics);

        bindActionRow(findViewById(R.id.about_feedback_row),
                getString(R.string.about_send_feedback),
                KhandaqSupport.FEEDBACK_EMAIL,
                this::sendFeedbackEmail);

        bindActionRow(findViewById(R.id.about_website_row),
                getString(R.string.Aboutpage_website),
                "khandaq.org",
                () -> openUrl(KhandaqSupport.WEBSITE_URL));

        // KHANDAQ (18.08): credit the owner/developer on the same openUrl() path as the website and
        // licenses rows, so the link opens in the system browser and a missing browser stays swallowed.
        bindActionRow(findViewById(R.id.about_developer_row),
                getString(R.string.about_credit_owner),
                "1sa.me",
                () -> openUrl(KhandaqSupport.DEVELOPER_URL));

        bindActionRow(findViewById(R.id.about_licenses_row),
                getString(R.string.Aboutpage_opensource),
                null,
                () -> openUrl(KhandaqSupport.LICENSES_URL));

        bindActionRow(findViewById(R.id.about_tox_row),
                getString(R.string.Aboutpage_6),
                null,
                () -> openUrl(KhandaqSupport.TOX_INFO_URL));

        String gitHash = BuildConfig.GitHash;
        bindActionRow(findViewById(R.id.about_upstream_row),
                getString(R.string.about_upstream_commit),
                gitHash,
                () -> openUrl("https://github.com/zoff99/ToxAndroidRefImpl/commit/" + gitHash));

        String jniHash = MainActivity.getNativeLibGITHASH();
        bindActionRow(findViewById(R.id.about_jni_row),
                getString(R.string.about_jni_commit),
                jniHash,
                () -> openUrl("https://github.com/zoff99/ToxAndroidRefImpl/commit/" + jniHash));

        String toxcoreHash = MainActivity.getNativeLibTOXGITHASH();
        bindActionRow(findViewById(R.id.about_toxcore_row),
                getString(R.string.about_toxcore_commit),
                toxcoreHash,
                () -> openUrl("https://github.com/zoff99/c-toxcore/commit/" + toxcoreHash));
    }

    private void configureDebugSection(boolean visible)
    {
        View debugSection = findViewById(R.id.about_debug_section);
        debugSection.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (!visible)
        {
            return;
        }

        TextView details = findViewById(R.id.about_debug_details);
        details.setText(buildDiagnosticsText(true));
    }

    private void bindActionRow(View row, String title, String subtitle, Runnable action)
    {
        TextView titleView = row.findViewById(R.id.group_info_action_title);
        TextView subtitleView = row.findViewById(R.id.group_info_action_subtitle);
        titleView.setText(title);
        if (subtitle == null || subtitle.isEmpty())
        {
            subtitleView.setVisibility(View.GONE);
        }
        else
        {
            subtitleView.setVisibility(View.VISIBLE);
            subtitleView.setText(subtitle);
        }
        row.setOnClickListener(v -> action.run());
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
                display_toast_with_context(this, getString(R.string.about_crash_report_unavailable), true, 0);
            }
        }
        catch (Exception ee3)
        {
            Log.i(TAG, "processFinish:EE1:" + ee3.getMessage());
        }
    }
}
