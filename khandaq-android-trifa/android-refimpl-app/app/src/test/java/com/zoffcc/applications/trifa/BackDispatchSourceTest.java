package com.zoffcc.applications.trifa;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.56): Back on Android 16. An app that targets it gets no onBackPressed call and no KEYCODE_BACK:
 * Back goes to the OnBackPressedDispatcher, or in a framework activity to an OnBackInvokedCallback, and with
 * no callback on, the system takes it. The screens are Android classes, so these pin the move in the source,
 * as AirSheetKeyboardTest does.
 */
public class BackDispatchSourceTest
{
    private static final String MAIN = "src/main/";
    private static final String JAVA = MAIN + "java/com/zoffcc/applications/trifa/";
    /** The framework activities (no AndroidX dispatcher): onBackPressed stays for Android 12 and older. */
    private static final List<String> FRAMEWORK_BACK = Arrays.asList("IOBrowser.java", "SettingsActivity.java");

    @Test
    public void the_app_takes_back_callbacks_on_android_13_and_newer() throws Exception
    {
        final String manifest = read(MAIN + "AndroidManifest.xml");
        final int application = manifest.indexOf("<application");
        final String tag = manifest.substring(application, manifest.indexOf('>', application));
        assertTrue(tag.contains("android:enableOnBackInvokedCallback=\"true\""));
    }

    @Test
    public void only_the_framework_activities_keep_onBackPressed_and_nothing_reads_the_back_key() throws Exception
    {
        final List<String> overriding = new ArrayList<>();
        for (final Path path : javaSources())
        {
            final String source = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            assertFalse(path.toString(), source.contains("KEYCODE_BACK"));
            if (source.contains("void onBackPressed()"))
            {
                overriding.add(path.getFileName().toString());
            }
        }
        overriding.sort(String::compareTo);
        assertEquals(FRAMEWORK_BACK, overriding);
        for (final String name : FRAMEWORK_BACK)
        {
            assertTrue(name, read(JAVA + name).contains("new FrameworkBackCallback(this, this::"));
        }
    }

    @Test
    public void the_main_screen_takes_back_only_with_something_to_step_back_from() throws Exception
    {
        final String main = read(JAVA + "MainActivity.java");
        assertTrue(main.contains("private final OnBackPressedCallback mainBackCallback = new OnBackPressedCallback(false)"));
        assertTrue(main.contains("getOnBackPressedDispatcher().addCallback(this, mainBackCallback);"));
        assertTrue(method(main, "private void switchMainTab(").contains("updateMainBackCallback();"));
        assertTrue(method(main, "protected void onResume()").contains("updateMainBackCallback();"));
        // Air's search and filter, which Back ends, switch it on and off.
        final String air = read(JAVA + "AirFragment.java");
        assertTrue(method(air, "private void updateSearching(").contains("updateBackCallback();"));
        assertTrue(method(air, "private void applyFilter(").contains("updateBackCallback();"));
        assertTrue(method(air, "boolean hasBackToHandle()").contains("searching || filter != AirModel.Filter.ALL"));
    }

    @Test
    public void the_other_screens_take_back_while_it_has_something_to_close() throws Exception
    {
        for (final String name : new String[]{"GroupMessageListActivity.java", "ConferenceMessageListActivity.java",
                                              "ConferenceAudioActivity.java"})
        {
            assertTrue(name, read(JAVA + name).contains("DrawerBackHelper.attach(this, "));
        }
        final String drawer = read(JAVA + "DrawerBackHelper.java");
        assertTrue(drawer.contains("new OnBackPressedCallback(false)"));
        assertTrue(drawer.contains("callback.setEnabled(true);") && drawer.contains("callback.setEnabled(false);"));

        final String preview = read(JAVA + "MediaSendPreviewActivity.java");
        assertTrue(preview.contains("emojiBackCallback = new OnBackPressedCallback(false)"));
        assertTrue(preview.contains("emojiBackCallback.setEnabled(true);"));
        assertTrue(preview.contains("emojiBackCallback.setEnabled(false);"));

        final String onboarding = read(JAVA + "OnboardingActivity.java");
        assertTrue(method(onboarding, "private void showSlide(").contains("slideBackCallback.setEnabled(index > 0);"));

        assertTrue(method(read(JAVA + "IOBrowser.java"), "public void getFileList(")
                           .contains("folderBack.setWanted(!at_root_dir);"));
        assertTrue(read(JAVA + "SettingsActivity.java").contains(
                "pageBack.setWanted(!onIsMultiPane() && getFragmentManager().getBackStackEntryCount() > 0);"));
    }

    @Test
    public void the_call_screen_and_the_password_setup_still_keep_back() throws Exception
    {
        for (final String name : new String[]{"CallingActivity.java", "SetPasswordActivity.java"})
        {
            final String create = method(read(JAVA + name), "protected void onCreate(");
            final int added = create.indexOf("getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true)");
            assertTrue(name, added > create.indexOf("super.onCreate(savedInstanceState);"));
        }
    }

    private static List<Path> javaSources() throws IOException
    {
        final File dir = file(MAIN + "java");
        try (Stream<Path> paths = Files.walk(dir.toPath()))
        {
            return paths.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
        }
    }

    /** The text of the method that starts with signature, up to its closing brace at class level. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start >= 0);
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String read(final String name) throws IOException
    {
        return new String(Files.readAllBytes(file(name).toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static File file(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.exists())
            {
                return f;
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}
