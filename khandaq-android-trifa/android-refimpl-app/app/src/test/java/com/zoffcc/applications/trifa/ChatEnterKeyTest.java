package com.zoffcc.applications.trifa;

import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import org.junit.Test;

import static com.zoffcc.applications.trifa.ChatInputBarHelper.isSendKey;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * KHANDAQ (QA 02.10) — which Enter presses send a chat message.
 *
 * Every chat activity used to treat any Enter as "send". Samsung Keyboard delivers its Enter key as a
 * KEYCODE_ENTER event, so on Samsung phones the newline key sent the half-typed message and a
 * multi-line message could not be written at all. These pin the split: the soft keyboard inserts a
 * line break, a physical keyboard and an explicit IME send action still send.
 */
public class ChatEnterKeyTest
{
    private static final int SOFT_FLAGS = KeyEvent.FLAG_SOFT_KEYBOARD | KeyEvent.FLAG_KEEP_TOUCH_MODE;
    private static final int HARDWARE_DEVICE_ID = 7;

    @Test
    public void the_soft_keyboard_enter_key_is_a_line_break()
    {
        // InputMethodService.sendDownUpKeyEvents: virtual device id plus FLAG_SOFT_KEYBOARD.
        assertFalse(isSendKey(KeyEvent.KEYCODE_ENTER, false, SOFT_FLAGS, KeyCharacterMap.VIRTUAL_KEYBOARD, false));
    }

    @Test
    public void a_soft_enter_without_the_soft_flag_is_still_a_line_break()
    {
        // new KeyEvent(ACTION_DOWN, KEYCODE_ENTER) through InputConnection.sendKeyEvent: no flags,
        // but the device id is the virtual keyboard.
        assertFalse(isSendKey(KeyEvent.KEYCODE_ENTER, false, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, false));
    }

    @Test
    public void the_soft_flag_wins_even_on_a_real_device_id()
    {
        assertFalse(isSendKey(KeyEvent.KEYCODE_ENTER, false, SOFT_FLAGS, HARDWARE_DEVICE_ID, true));
    }

    @Test
    public void the_ime_send_action_sends()
    {
        // TextView.onEditorAction re-dispatches an IME action as Enter with FLAG_EDITOR_ACTION.
        assertTrue(isSendKey(KeyEvent.KEYCODE_ENTER, false, SOFT_FLAGS | KeyEvent.FLAG_EDITOR_ACTION,
                             KeyCharacterMap.VIRTUAL_KEYBOARD, false));
    }

    @Test
    public void a_physical_keyboard_enter_sends()
    {
        assertTrue(isSendKey(KeyEvent.KEYCODE_ENTER, false, 0, HARDWARE_DEVICE_ID, true));
        assertTrue(isSendKey(KeyEvent.KEYCODE_NUMPAD_ENTER, false, 0, HARDWARE_DEVICE_ID, true));
    }

    @Test
    public void shift_enter_is_a_line_break_everywhere()
    {
        assertFalse(isSendKey(KeyEvent.KEYCODE_ENTER, true, 0, HARDWARE_DEVICE_ID, true));
        assertFalse(isSendKey(KeyEvent.KEYCODE_ENTER, true, SOFT_FLAGS | KeyEvent.FLAG_EDITOR_ACTION,
                              KeyCharacterMap.VIRTUAL_KEYBOARD, false));
    }

    @Test
    public void a_non_alphabetic_device_does_not_send()
    {
        // A remote control or a numeric keypad reports a real device id but is not a typing keyboard.
        assertFalse(isSendKey(KeyEvent.KEYCODE_ENTER, false, 0, HARDWARE_DEVICE_ID, false));
    }

    @Test
    public void other_keys_never_send()
    {
        assertFalse(isSendKey(KeyEvent.KEYCODE_SPACE, false, 0, HARDWARE_DEVICE_ID, true));
        assertFalse(isSendKey(KeyEvent.KEYCODE_DPAD_CENTER, false, KeyEvent.FLAG_EDITOR_ACTION, HARDWARE_DEVICE_ID,
                              true));
    }
}
