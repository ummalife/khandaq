package com.zoffcc.applications.trifa;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (testers' ideas, 0.2.67): the first page of the first launch takes a photo with the name, picked and cropped
 * as on You; it waits in the app's files and becomes our photo once Tox runs with the new profile (AirFirstPhoto).
 */
public class AirFirstPhotoTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";
    private static final String RES = "src/main/res/";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void a_crop_takes_the_place_of_the_photo_and_goes() throws IOException
    {
        final File crop = folder.newFile("crop.jpg");
        final File photo = new File(folder.getRoot(), "photo.jpg");
        Files.write(crop.toPath(), new byte[]{1, 2, 3});
        assertTrue(AirFirstPhoto.moveInto(crop, photo));
        assertFalse(crop.exists());
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(photo.toPath()));

        // Another crop replaces it.
        final File again = folder.newFile("again.jpg");
        Files.write(again.toPath(), new byte[]{4, 5});
        assertTrue(AirFirstPhoto.moveInto(again, photo));
        assertFalse(again.exists());
        assertArrayEquals(new byte[]{4, 5}, Files.readAllBytes(photo.toPath()));
    }

    @Test
    public void an_empty_or_missing_crop_is_no_photo_and_leaves_nothing_behind() throws IOException
    {
        final File photo = new File(folder.getRoot(), "photo.jpg");
        final File empty = folder.newFile("empty.jpg");
        assertFalse(AirFirstPhoto.moveInto(empty, photo));
        assertFalse(empty.exists());
        assertFalse(photo.exists());
        assertFalse(AirFirstPhoto.moveInto(new File(folder.getRoot(), "none.jpg"), photo));
        assertFalse(AirFirstPhoto.usable(photo));
        assertFalse(AirFirstPhoto.usable(null));
        assertFalse(AirFirstPhoto.usable(folder.getRoot()));
    }

    @Test
    public void the_tile_stands_at_the_start_of_the_name_and_offers_the_pick_and_crop_of_you()
    {
        final String first = read(JAVA + "AirFirstLaunchActivity.java");
        final String page = method(first, "private View buildNamePage()");
        final int tile = page.indexOf("nameRow.addView(photoButton,");
        assertTrue(tile > 0);
        assertTrue(page.indexOf("nameRow.addView(nameField, nameParams);") > tile);
        assertTrue(page.contains("page.addView(nameRow, spaced(R.dimen.kq_space_24));"));
        assertTrue(page.contains("photoButton.setOnClickListener(v -> offerPhoto());"));
        assertTrue(page.contains("AirUi.markAsButton(photoButton);"));
        // Empty, a quiet plus; with a photo, the photo, and TalkBack hears which.
        final String show = method(first, "private void showPhoto()");
        assertTrue(show.contains("photoTile.bind(AirTileView.Kind.ADD_PHOTO, \"\", 0);"));
        assertTrue(show.contains("photoTile.showPhoto(true);"));
        assertTrue(show.contains("R.string.air_first_photo_add"));
        assertTrue(show.contains("R.string.air_you_photo_hint"));
        // The picker, the image check and the crop of the You screen.
        assertTrue(first.contains("pickPhoto.launch(ProfileContentFragment.avatar_picker_intent());"));
        assertTrue(first.contains("ProfileContentFragment.picked_avatar_is_image(this, picked)"));
        assertTrue(first.contains("cropPhoto.launch(ProfileContentFragment.avatar_crop_intent(this, picked, Uri.fromFile(crop)));"));
        // With a photo, another one or none, as on You.
        final String offer = method(first, "private void offerPhoto()");
        assertTrue(offer.contains("R.string.air_you_photo_choose"));
        assertTrue(offer.contains("R.string.air_you_photo_remove"));
        assertTrue(offer.contains("AirFirstPhoto.forget(this);"));

        final String profile = read(JAVA + "ProfileContentFragment.java");
        assertTrue(method(profile, "private void launchAvatarPicker()").contains("final Intent intent = avatar_picker_intent();"));
        assertTrue(method(profile, "private void launch_avatar_crop(final Uri source)")
                           .contains("startActivityForResult(avatar_crop_intent(requireContext(), source, dest),"));
        final String crop = method(profile, "static Intent avatar_crop_intent(final Context context, final Uri source, final Uri dest)");
        assertTrue(crop.contains(".withAspectRatio(1f, 1f)"));
        assertTrue(crop.contains("crop_intent.setClass(context, AvatarCropActivity.class);"));
    }

    @Test
    public void the_photo_can_be_skipped_and_waits_for_the_new_profile_only()
    {
        final String first = read(JAVA + "AirFirstLaunchActivity.java");
        // Next asks only for the name.
        assertFalse(method(first, "private void mainTapped()").contains("Photo"));
        // A profile made here takes the photo that waits; one moved in does not; a fresh first launch forgets it.
        assertTrue(method(first, "private void finishCreate(")
                           .contains(".putBoolean(AirFirstPhoto.PREF_PENDING, AirFirstPhoto.has(this))"));
        assertTrue(method(first, "private void moveProfileIn()").contains("AirFirstPhoto.forget(app);"));
        final String create = method(first, "protected void onCreate(@Nullable final Bundle savedInstanceState)");
        final int existing = create.indexOf("if (DbSecretKeyStorage.hasExistingUserDatabase(this))");
        final int forget = create.indexOf("AirFirstPhoto.forget(this);");
        assertTrue(existing > 0 && forget > existing);
        assertTrue(create.substring(existing, forget).contains("if (savedInstanceState == null)"));
        // Deleting the profile forgets it too.
        final String wipe = method(read(JAVA + "DbSecretKeyStorage.java"), "static void wipeUserProfile(final Context context)");
        assertTrue(wipe.contains(".remove(AirFirstPhoto.PREF_PENDING)"));
        assertTrue(wipe.contains("deleteRecursive(AirFirstPhoto.file(context));"));
    }

    @Test
    public void the_photo_becomes_ours_once_tox_runs_with_the_profile_through_the_pipeline_of_you()
    {
        final String service = read(JAVA + "TrifaToxService.java");
        final int name = service.indexOf("DbSecretKeyStorage.rememberProfileName(context_s, global_my_name);");
        assertTrue(name > 0);
        assertTrue(service.indexOf("AirFirstPhoto.applySoon(context_s);") > name);

        final String photo = read(JAVA + "AirFirstPhoto.java");
        final String apply = method(photo, "private static synchronized void apply(final Context context)");
        final int ready = apply.indexOf("TrifaToxService.vfs == null || TrifaToxService.orma == null");
        final int save = apply.indexOf("ProfileContentFragment.save_own_avatar(context, Uri.fromFile(photo));");
        assertTrue(ready > 0 && save > ready);
        assertTrue(apply.indexOf("forget(context);", save) > save);
        assertTrue(apply.contains("new Handler(Looper.getMainLooper()).post(MainActivity::update_main_profile_bar);"));
        // Off the Tox thread, and nothing to do on most starts.
        final String soon = method(photo, "static void applySoon(@Nullable final Context context)");
        final int asked = soon.indexOf("getBoolean(PREF_PENDING, false)");
        assertTrue(asked > 0 && soon.indexOf("new Thread(() -> apply(app), \"first_launch_photo\").start();") > asked);

        // The You screen saves the same way and says what happened on its own screen.
        final String profile = read(JAVA + "ProfileContentFragment.java");
        final String you = method(profile, "private void apply_avatar_from_uri(final Uri uri)");
        final int saved = you.indexOf("final int failure = save_own_avatar(requireContext(), uri);");
        assertTrue(saved > 0);
        assertTrue(you.indexOf("send_avatar_to_all_friends();") > saved);
        assertTrue(you.contains("Toast.makeText(requireContext(), getString(R.string.profile_avatar_saved_toast)"));
        final int start = profile.indexOf("static int save_own_avatar(final Context context, final Uri uri)");
        assertTrue(start > 0);
        final String pipeline = profile.substring(start, profile.indexOf("static Bitmap decode_downsampled_bitmap(", start));
        assertFalse(pipeline.contains("sayPhotoFailure("));
        assertFalse(pipeline.contains("requireContext()"));
        assertTrue(pipeline.contains("set_g_opts(\"VFS_OWN_AVATAR_FNAME\", vfs_avatar_path);"));
    }

    @Test
    public void the_empty_tile_is_a_plus_that_never_shows_a_photo_or_initials()
    {
        final String tile = read(JAVA + "AirTileView.java");
        final int add = tile.indexOf("case ADD_PHOTO:");
        assertTrue(add > 0);
        assertTrue(tile.indexOf("icon.setImageResource(R.drawable.kq_ic_plus);", add) > add);
        assertTrue(tile.contains("return pending() || kind == Kind.SAVED || kind == Kind.ADD_PHOTO;"));
        assertTrue(method(tile, "void showPhoto(final boolean shown)").contains("photoShown = shown && !glyphOnly();"));
    }

    @Test
    public void the_tile_speaks_in_all_four_languages()
    {
        final String[][] expected = {
                {"values", "Add a photo (optional)"},
                {"values-ru", "Добавить фото (необязательно)"},
                {"values-ar", "إضافة صورة (اختياري)"},
                {"values-zh-rCN", "添加头像（可选）"},
        };
        for (final String[] language : expected)
        {
            final String start = read(RES + language[0] + "/strings_air_start.xml");
            final Matcher found = Pattern.compile("<string name=\"air_first_photo_add\">([^<]*)</string>").matcher(start);
            assertTrue(language[0], found.find());
            assertEquals(language[0], language[1], found.group(1));
            final String air = read(RES + language[0] + "/strings_air.xml");
            for (final String reused : new String[]{"air_you_photo", "air_you_photo_hint", "air_you_photo_choose",
                                                    "air_you_photo_remove"})
            {
                assertTrue(language[0] + " " + reused, air.contains("<string name=\"" + reused + "\">"));
            }
        }
    }

    /** The body of the method whose declaration starts with {@code signature}. */
    private static String method(final String source, final String signature)
    {
        final int start = source.indexOf(signature);
        assertTrue(signature, start > 0);
        assertEquals(signature + " is declared once", start, source.lastIndexOf(signature));
        final int end = source.indexOf("\n    }\n", start);
        assertTrue(signature, end > start);
        return source.substring(start, end);
    }

    private static String read(final String name)
    {
        // Gradle runs unit tests from the module directory; an IDE may use the project root.
        for (final String root : new String[]{"", "app/"})
        {
            final File f = new File(root + name);
            if (f.isFile())
            {
                try
                {
                    return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
                }
                catch (IOException e)
                {
                    throw new AssertionError(name + ": " + e.getMessage());
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}
