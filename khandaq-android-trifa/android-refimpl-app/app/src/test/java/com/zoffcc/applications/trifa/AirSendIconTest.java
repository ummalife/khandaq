package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.widget.ImageButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import androidx.core.widget.ImageViewCompat;
import androidx.test.core.app.ApplicationProvider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (0.2.60): the send button drew its arrow black on the ink plate, a black square (owner's feedback,
 * Android). The button clears its image tint for send mode, and the ImageView mutates the icon then: the first
 * mutate() of a LayerDrawable makes its layers again from their constant states, which hold neither a colour filter
 * nor, once cleared, a tint. AirChatPanel.sendIcon mutates the icon before it colours the arrow.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class AirSendIconTest
{
    private static final String JAVA = "src/main/java/com/zoffcc/applications/trifa/";

    /** The icon as the send button shows it: ChatInputBarHelper clears the image tint, then sets the icon. */
    private static LayerDrawable shownBySendButton(final LayerDrawable icon)
    {
        final Context context = ApplicationProvider.getApplicationContext();
        final ImageButton button = new ImageButton(context);
        // The microphone's tint, then send mode (ChatInputBarHelper.applyIconTint(button, true)).
        ImageViewCompat.setImageTintList(button, ColorStateList.valueOf(Color.GRAY));
        ImageViewCompat.setImageTintList(button, null);
        button.setImageDrawable(icon);
        return (LayerDrawable) button.getDrawable();
    }

    /** The plate and the arrow, black on black as the arrow's own colour is. */
    private static LayerDrawable plateWithArrow()
    {
        final GradientDrawable plate = new GradientDrawable();
        plate.setColor(Color.BLACK);
        final GradientDrawable arrow = new GradientDrawable();
        arrow.setColor(Color.BLACK);
        return new LayerDrawable(new Drawable[]{plate, arrow});
    }

    @Test
    public void a_filter_on_a_layer_of_an_icon_not_yet_mutated_is_lost_on_the_button()
    {
        final LayerDrawable icon = plateWithArrow();
        icon.getDrawable(1).setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        assertNull("the black arrow", shownBySendButton(icon).getDrawable(1).getColorFilter());
    }

    @Test
    public void a_filter_set_once_the_icon_was_mutated_stays_on_the_button()
    {
        final LayerDrawable icon = plateWithArrow();
        icon.mutate();
        final PorterDuffColorFilter onInk = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        icon.getDrawable(1).setColorFilter(onInk);
        assertSame(onInk, shownBySendButton(icon).getDrawable(1).getColorFilter());
    }

    @Test
    public void the_send_icon_is_mutated_before_its_arrow_is_coloured()
    {
        final String source = read(JAVA + "AirChatPanel.java");
        final int start = source.indexOf("static Drawable sendIcon(final Context context)");
        assertTrue(start >= 0);
        final String body = source.substring(start, source.indexOf("\n    }\n", start));
        final int mutated = body.indexOf("layers.mutate();");
        final int coloured = body.indexOf("layers.getDrawable(1).setColorFilter(");
        assertTrue(body, mutated >= 0 && coloured > mutated);
        assertFalse(body, body.contains("arrow.setColorFilter("));
        assertFalse(body, body.contains("setTint(arrow"));
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
                    throw new AssertionError(name, e);
                }
            }
        }
        throw new AssertionError(name + " not found from " + new File("").getAbsolutePath());
    }
}
