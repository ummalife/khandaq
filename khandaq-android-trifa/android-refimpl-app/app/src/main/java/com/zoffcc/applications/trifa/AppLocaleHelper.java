package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.app.Application;
import android.app.LocaleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.LocaleList;
import android.util.Log;

import com.yariksoffice.lingver.Lingver;

import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;
import androidx.preference.PreferenceManager;

/**
 * Applies user-selected app locale and restarts the UI so cached strings (e.g. drawer) refresh.
 */
public final class AppLocaleHelper
{
    private static final String TAG = "trifa.AppLocaleHelper";

    /** KHANDAQ (#248): the languages we actually ship translations for (see resConfigs in build.gradle).
     *  Order is the order shown in the picker. */
    public static final String[] SUPPORTED_LANG_CODES = {"en", "ru", "ar", "zh-rCN"};

    private AppLocaleHelper()
    {
    }

    /** KHANDAQ (#248): the locale a fresh install should start in — the phone's language when we speak
     *  it, English otherwise. Without this, an unsupported system language (say German) left the app
     *  believing it was German: strings fell back to English anyway, but the picker and the per-app
     *  language setting disagreed with what the user actually saw. */
    public static Locale startupLocaleFor(final Locale systemLocale)
    {
        try
        {
            if (systemLocale != null)
            {
                final String lang = systemLocale.getLanguage();
                for (final String code : SUPPORTED_LANG_CODES)
                {
                    if (localeForCode(code).getLanguage().equals(lang))
                    {
                        // Chinese: we ship Simplified only, so any zh variant maps to it.
                        return "zh".equals(lang) ? Locale.SIMPLIFIED_CHINESE : localeForCode(code);
                    }
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "startupLocaleFor:EE:" + e.getMessage());
        }
        return Locale.ENGLISH;
    }

    /** The code ("en"/"ru"/"ar"/"zh-rCN") currently in effect, for showing in the settings row. */
    public static String currentLangCode()
    {
        try
        {
            final Locale l = Lingver.getInstance().getLocale();
            final String lang = l.getLanguage();
            for (final String code : SUPPORTED_LANG_CODES)
            {
                if (localeForCode(code).getLanguage().equals(lang)) { return code; }
            }
        }
        catch (Exception ignored)
        {
        }
        return "en";
    }

    /** Persist locale, sync AppCompat per-app language API, then restart to MainActivity. */
    public static void applyLocaleAndRestart(final Activity activity, final String langCode)
    {
        applyLocaleAndRestart(activity, langCode, false);
    }

    /**
     * KHANDAQ (redesign, E18): as above; with reopenSettings the app comes back on Settings, so the
     * screen the language was changed on is the one that shows it at once.
     */
    public static void applyLocaleAndRestart(final Activity activity, final String langCode, final boolean reopenSettings)
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

        restartToMain(activity, reopenSettings);
    }

    /**
     * Set while the phone holds a language for Khandaq itself (per-app language), from its settings or from
     * the picker here, so that "System default" chosen there later can be told from a phone that never
     * held one.
     */
    private static final String PREF_APP_LOCALES_SEEN = "kq_app_locales_seen";

    /** The screens alive now, and those the language moved under: each of these is drawn again as it comes back. */
    private static final Set<Activity> openScreens = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<Activity> staleScreens = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * KHANDAQ (redesign stage 5): from Android 13 the phone's settings set the language of Khandaq itself
     * (res/xml/locales_config.xml), and the picker here writes that same setting. Lingver kept its own
     * stored choice over a change made in the phone's settings: at the next start, and at once on every
     * configuration change, so the phone named one language and Khandaq showed another. The phone's choice
     * is taken into Lingver here, as the picker would take it: its language when one is set, and the
     * phone's own language when "System default" is set after a language had been. A phone that never held
     * one for Khandaq leaves Lingver as it is (#248). Main thread; true when Lingver's language moved.
     */
    static boolean adoptPhoneAppLanguage(@NonNull final Context context)
    {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        {
            return false;
        }
        try
        {
            final LocaleManager manager = context.getSystemService(LocaleManager.class);
            if (manager == null)
            {
                return false;
            }
            final LocaleList chosen = manager.getApplicationLocales();
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            final boolean seen = prefs.getBoolean(PREF_APP_LOCALES_SEEN, false);
            final Lingver lingver = Lingver.getInstance();
            final Locale before = lingver.getLocale();
            if (chosen.isEmpty())
            {
                if (!seen)
                {
                    return false;
                }
                prefs.edit().remove(PREF_APP_LOCALES_SEEN).apply();
                if (lingver.isFollowingSystemLocale())
                {
                    return false;
                }
                Log.i(TAG, "adoptPhoneAppLanguage: system default");
                lingver.setFollowSystemLocale(context);
                return !localesMatch(before, lingver.getLocale());
            }
            if (!seen)
            {
                prefs.edit().putBoolean(PREF_APP_LOCALES_SEEN, true).apply();
            }
            final Locale locale = startupLocaleFor(chosen.get(0));
            final boolean same = localesMatch(before, locale);
            if (same && !lingver.isFollowingSystemLocale())
            {
                return false;
            }
            Log.i(TAG, "adoptPhoneAppLanguage: " + locale.toLanguageTag());
            lingver.setLocale(context, locale);
            return !same;
        }
        catch (Exception e)
        {
            Log.i(TAG, "adoptPhoneAppLanguage:EE:" + e.getMessage());
            return false;
        }
    }

    /** Lingver's language now, or null before Lingver is up. */
    @Nullable
    static Locale lingverLocaleOrNull()
    {
        try
        {
            return Lingver.getInstance().getLocale();
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * After a configuration change: when Lingver's language is no longer {@code before}, the screens open
     * now are drawn again in it as each comes back (they handle "locale" themselves, so nothing else
     * redraws them). A screen the system relaunches anyway, as for a change of writing direction, comes
     * back as a new one and is not drawn twice; one left in front (split screen) waits for its next return.
     */
    static void redrawIfLanguageMoved(@Nullable final Locale before)
    {
        final Locale after = lingverLocaleOrNull();
        if (before == null || after == null || localesMatch(before, after))
        {
            return;
        }
        Log.i(TAG, "redrawIfLanguageMoved: " + before.toLanguageTag() + " to " + after.toLanguageTag());
        staleScreens.addAll(openScreens);
    }

    /** A call keeps its screen; it takes the language with the next one. */
    private static boolean redrawable(@NonNull final Activity activity)
    {
        return !activity.isFinishing() && !(activity instanceof CallingActivity)
               && !(activity instanceof CallingWaitingActivity);
    }

    /** Follows the screens for {@link #redrawIfLanguageMoved}; once, from Application.onCreate. */
    static void registerRedraw(@NonNull final Application application)
    {
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks()
        {
            @Override
            public void onActivityCreated(@NonNull final Activity activity, @Nullable final Bundle state)
            {
                openScreens.add(activity);
            }

            @Override
            public void onActivityStarted(@NonNull final Activity activity)
            {
            }

            @Override
            public void onActivityResumed(@NonNull final Activity activity)
            {
                if (staleScreens.remove(activity) && redrawable(activity))
                {
                    activity.recreate();
                }
            }

            @Override
            public void onActivityPaused(@NonNull final Activity activity)
            {
            }

            @Override
            public void onActivityStopped(@NonNull final Activity activity)
            {
            }

            @Override
            public void onActivitySaveInstanceState(@NonNull final Activity activity, @NonNull final Bundle state)
            {
            }

            @Override
            public void onActivityDestroyed(@NonNull final Activity activity)
            {
                openScreens.remove(activity);
                staleScreens.remove(activity);
            }
        });
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
        restartToMain(activity, false);
    }

    static void restartToMain(final Activity activity, final boolean reopenSettings)
    {
        final Intent intent = new Intent(activity, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if (reopenSettings)
        {
            intent.putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true);
        }
        activity.startActivity(intent);
        activity.finishAffinity();
    }
}
