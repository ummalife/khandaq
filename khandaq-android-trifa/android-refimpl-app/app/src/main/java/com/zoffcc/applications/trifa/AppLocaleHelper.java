package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.yariksoffice.lingver.Lingver;

import java.util.Locale;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/**
 * Applies user-selected app locale and restarts the UI so cached strings (e.g. drawer) refresh.
 */
public final class AppLocaleHelper
{
    private static final String TAG = "trifa.AppLocaleHelper";

    private AppLocaleHelper()
    {
    }

    /** Persist locale, sync AppCompat per-app language API, then restart to MainActivity. */
    public static void applyLocaleAndRestart(final Activity activity, final String langCode)
    {
        if (langCode == null || langCode.isEmpty())
        {
            return;
        }

        final String code = langCode.trim();
        Log.i(TAG, "applyLocaleAndRestart:" + code);

        if ("_default_".equals(code))
        {
            if (Lingver.getInstance().isFollowingSystemLocale())
            {
                return;
            }
            applyFollowSystem(activity);
        }
        else
        {
            final Locale target = localeForCode(code);
            if (!Lingver.getInstance().isFollowingSystemLocale() && localesMatch(Lingver.getInstance().getLocale(), target))
            {
                return;
            }
            applyFixedLocale(activity, target, code);
        }

        restartToMain(activity);
    }

    /** Keep AppCompat per-app locale in sync with Lingver after process start. */
    public static void syncAppCompatLocalesFromLingver()
    {
        if (Lingver.getInstance().isFollowingSystemLocale())
        {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
        }
        else
        {
            final Locale locale = Lingver.getInstance().getLocale();
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.create(locale));
        }
    }

    public static String toLanguageTag(final String langCode)
    {
        switch (langCode)
        {
            case "zh-rCN":
                return "zh-CN";
            case "pt-rBR":
                return "pt-BR";
            default:
                return langCode;
        }
    }

    public static Locale localeForCode(final String langCode)
    {
        switch (langCode)
        {
            case "en":
                return Locale.ENGLISH;
            case "de":
                return Locale.GERMAN;
            case "zh-rCN":
                return Locale.SIMPLIFIED_CHINESE;
            case "pt-rBR":
                return localeFromTag("pt-BR");
            default:
                return localeFromTag(toLanguageTag(langCode));
        }
    }

    private static void applyFollowSystem(final Context context)
    {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList());
        Lingver.getInstance().setFollowSystemLocale(context);
    }

    private static void applyFixedLocale(final Context context, final Locale locale, final String langCode)
    {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(toLanguageTag(langCode)));
        Lingver.getInstance().setLocale(context, locale);
    }

    private static Locale localeFromTag(final String tag)
    {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        {
            return Locale.forLanguageTag(tag);
        }
        final String[] parts = tag.split("-", 2);
        if (parts.length >= 2)
        {
            return new Locale(parts[0], parts[1]);
        }
        return new Locale(parts[0]);
    }

    private static boolean localesMatch(final Locale a, final Locale b)
    {
        return a.getLanguage().equals(b.getLanguage()) && a.getCountry().equals(b.getCountry());
    }

    static void restartToMain(final Activity activity)
    {
        final Intent intent = new Intent(activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finishAffinity();
    }
}
