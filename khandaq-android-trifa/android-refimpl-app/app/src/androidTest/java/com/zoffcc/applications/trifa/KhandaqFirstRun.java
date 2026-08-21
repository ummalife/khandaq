package com.zoffcc.applications.trifa;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.platform.app.InstrumentationRegistry;

import java.util.List;

/**
 * KHANDAQ — get a device test past first-run so it has an open profile to work against.
 *
 * <p>WHY THIS EXISTS. Everything worth testing on a device lives in the encrypted profile database:
 * g_opts, the HSK directory, the signed-history verdict store. A freshly installed build has none of
 * it — it lands on {@code OnboardingActivity} and waits for a human. Instrumentation runs have no
 * human, so {@code NgcHskStoreDeviceTest} skipped all six of its cases on every run, and any new
 * device test would skip too. A suite that always skips is not coverage; it is a green tick over
 * nothing.
 *
 * <p>HOW. The first-run flow was walked by hand on an emulator and is exactly four steps: three
 * onboarding slides, a profile name, "skip password", and declining the battery-optimisation prompt.
 * Every control is addressed by its RESOURCE ID, so a layout change moves the tap with it and a
 * RENAMED id fails loudly instead of tapping empty space.
 *
 * <p>WHY THE ACCESSIBILITY TREE AND NOT `uiautomator dump`. The obvious approach — shelling out to
 * `uiautomator dump` and parsing bounds — cannot work from inside an instrumentation test: the test
 * process already holds the single permitted {@link UiAutomation} connection, so the shell tool
 * cannot get one and the dump comes back empty. That is why the first version of this class silently
 * skipped every test. Reading {@link UiAutomation#getRootInActiveWindow()} directly uses the
 * connection we already have, and clicking through {@link AccessibilityNodeInfo} needs no
 * coordinates at all.
 *
 * <p>{@code FLAG_REPORT_VIEW_IDS} has to be set explicitly: without it the accessibility layer does
 * not expose resource ids, and every lookup below returns nothing.
 *
 * <p>Everything here is idempotent: with a profile already open it returns immediately.
 */
final class KhandaqFirstRun
{
    private static final String PKG = "com.khandaq.messenger";
    private static final int PROFILE_WAIT_SEC = 90;

    private KhandaqFirstRun()
    {
    }

    /** True when g_opts is usable, i.e. the profile database is open. */
    static boolean profileIsOpen()
    {
        try
        {
            HelperGeneric.set_g_opts("kqfirstrun_probe", "1");
            return "1".equals(HelperGeneric.get_g_opts("kqfirstrun_probe"));
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    /**
     * Launch the app and walk first-run until the profile database is open.
     *
     * @return true if there is an open profile by the time this returns.
     */
    static boolean ensureProfile()
    {
        if (profileIsOpen())
        {
            return true;
        }

        final Instrumentation instr = InstrumentationRegistry.getInstrumentation();
        enableViewIdReporting(instr.getUiAutomation());
        launchApp(instr.getTargetContext());

        // Three onboarding slides today. Written to stop when the button is gone rather than to
        // count to three, so adding or removing a slide does not break it.
        for (int i = 0; i < 6; i++)
        {
            if (!click("onboarding_btn_primary"))
            {
                break;
            }
            settle();
        }

        if (waitFor("create_profile_name_field") != null)
        {
            setText("create_profile_name_field", "qa");
            click("create_profile_next_button");
            settle();
        }

        // "Skip password" — the passwordless path is the default a user gets by tapping through, and
        // it is the branch that derives the database key itself, so it is the one worth exercising.
        if (waitFor("skip_button") != null)
        {
            click("skip_button");
            settle();
        }

        // Battery-optimisation prompt. button2 is "NO"; declining keeps the run self-contained,
        // because "OK, TAKE ME THERE" leaves the app for a system settings screen.
        if (waitForAny("button2", "android:id/button2") != null)
        {
            if (!click("button2"))
            {
                clickFull("android:id/button2");
            }
            settle();
        }

        for (int i = 0; i < PROFILE_WAIT_SEC; i++)
        {
            if (profileIsOpen())
            {
                return true;
            }
            sleep(1000L);
        }
        return profileIsOpen();
    }

    // --------------------------------------------------------------------------------- plumbing

    private static void enableViewIdReporting(final UiAutomation ui)
    {
        try
        {
            final AccessibilityServiceInfo info = ui.getServiceInfo();
            if (info != null)
            {
                info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
                ui.setServiceInfo(info);
            }
        }
        catch (Throwable ignored)
        {
        }
    }

    private static void launchApp(final Context ctx)
    {
        try
        {
            final Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
            if (launch != null)
            {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(launch);
            }
        }
        catch (Throwable ignored)
        {
        }
        settle();
    }

    /** First node with this resource id, searching the app package and then the platform. */
    private static AccessibilityNodeInfo find(final String id)
    {
        final String full = id.contains(":") ? id : PKG + ":id/" + id;
        try
        {
            final AccessibilityNodeInfo root =
                    InstrumentationRegistry.getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root == null)
            {
                return null;
            }
            final List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByViewId(full);
            return (hits == null || hits.isEmpty()) ? null : hits.get(0);
        }
        catch (Throwable e)
        {
            return null;
        }
    }

    private static AccessibilityNodeInfo waitFor(final String id)
    {
        for (int i = 0; i < 24; i++)
        {
            final AccessibilityNodeInfo n = find(id);
            if (n != null)
            {
                return n;
            }
            sleep(500L);
        }
        return null;
    }

    private static AccessibilityNodeInfo waitForAny(final String... ids)
    {
        for (int i = 0; i < 24; i++)
        {
            for (final String id : ids)
            {
                final AccessibilityNodeInfo n = find(id);
                if (n != null)
                {
                    return n;
                }
            }
            sleep(500L);
        }
        return null;
    }

    private static boolean click(final String id)
    {
        return clickNode(find(id));
    }

    private static boolean clickFull(final String fullId)
    {
        return clickNode(find(fullId));
    }

    /**
     * Click the node, or the nearest clickable ancestor. A Button is clickable itself; a TextView
     * inside a clickable row is not, and dispatching to it does nothing at all.
     */
    private static boolean clickNode(AccessibilityNodeInfo node)
    {
        for (int depth = 0; node != null && depth < 6; depth++)
        {
            if (node.isClickable() && node.isEnabled())
            {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            node = node.getParent();
        }
        return false;
    }

    private static void setText(final String id, final String text)
    {
        final AccessibilityNodeInfo node = find(id);
        if (node == null)
        {
            return;
        }
        final Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
        {
            // Older or unusual editors ignore ACTION_SET_TEXT; focusing and typing is the fallback.
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            InstrumentationRegistry.getInstrumentation().sendStringSync(text);
        }
    }

    private static void settle()
    {
        sleep(1500L);
    }

    private static void sleep(final long ms)
    {
        try
        {
            Thread.sleep(ms);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }
}
