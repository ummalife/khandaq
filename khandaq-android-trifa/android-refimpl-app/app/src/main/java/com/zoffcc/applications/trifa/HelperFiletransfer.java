/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2020 Zoff <zoff@zoff.cc>
 * <p>
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * version 2 as published by the Free Software Foundation.
 * <p>
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * <p>
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the
 * Free Software Foundation, Inc., 51 Franklin Street, Fifth Floor,
 * Boston, MA  02110-1301, USA.
 */

package com.zoffcc.applications.trifa;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LongSparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.documentfile.provider.DocumentFile;

import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.zoffcc.applications.sorm.FileDB;
import com.zoffcc.applications.sorm.Filetransfer;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;
import com.zoffcc.applications.trifa.MessageListActivity.outgoing_file_wrapped;

import org.khandaq.messenger.R;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;

import androidx.core.content.FileProvider;

import static android.webkit.MimeTypeMap.getFileExtensionFromUrl;
import static com.zoffcc.applications.trifa.HelperFriend.friend_call_push_url;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online_real;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
import static com.zoffcc.applications.trifa.HelperGeneric.get_fileExt;
import static com.zoffcc.applications.trifa.HelperGeneric.set_message_accepted_from_id;
import static com.zoffcc.applications.trifa.HelperMessage.set_message_queueing_from_id;
import static com.zoffcc.applications.trifa.HelperMessage.set_message_start_sending_from_id;
import static com.zoffcc.applications.trifa.HelperMessage.set_message_state_from_id;
import static com.zoffcc.applications.trifa.HelperMessage.update_message_in_db_filetransfer_kind;
import static com.zoffcc.applications.trifa.HelperMessage.update_single_message_from_messge_id;
import static com.zoffcc.applications.trifa.Identicon.bytesToHex;
import static com.zoffcc.applications.trifa.MainActivity.PREF__auto_accept_all_upto;
import static com.zoffcc.applications.trifa.MainActivity.PREF__auto_accept_image;
import static com.zoffcc.applications.trifa.MainActivity.PREF__auto_accept_video;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_FILES_EXPORT_DIR;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_FILES_OUTGOING_WRAPPER_DIR;
import static com.zoffcc.applications.trifa.MainActivity.context_s;
import static com.zoffcc.applications.trifa.MainActivity.tox_file_control;
import static com.zoffcc.applications.trifa.MainActivity.tox_file_send;
import static com.zoffcc.applications.trifa.MainActivity.tox_friend_get_capabilities;
import static com.zoffcc.applications.trifa.TRIFAGlobals.AUTO_ACCEPT_FT_MAX_ANYKIND_SIZE_IN_MB;
import static com.zoffcc.applications.trifa.TRIFAGlobals.AUTO_ACCEPT_FT_MAX_IMAGE_SIZE_IN_MB;
import static com.zoffcc.applications.trifa.TRIFAGlobals.AUTO_ACCEPT_FT_MAX_VIDEO_SIZE_IN_MB;
import static com.zoffcc.applications.trifa.TRIFAGlobals.AUTO_ACCEPT_FT_SMALL_FILE_MAX_SIZE_IN_MB;
import static com.zoffcc.applications.trifa.TRIFAGlobals.KHANDAQ_MAX_FILE_TRANSFER_BYTES;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_INCOMING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_OUTGOING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.UINT32_MAX_JAVA;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_FILE_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_PREFIX;
import static com.zoffcc.applications.trifa.TRIFAGlobals.VFS_TMP_FILE_DIR;
import static com.zoffcc.applications.trifa.TRIFAGlobals.cache_ft_fis_saf;
import static com.zoffcc.applications.trifa.TRIFAGlobals.cache_ft_fos;
import static com.zoffcc.applications.trifa.ToxVars.TOX_CAPABILITY_DECODE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_PAUSE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_RESUME;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_ID_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MAX_FILENAME_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_DATA;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_FTV2;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

public class HelperFiletransfer
{
    private static final String TAG = "trifa.Hlp.Filetransfer";

    public static boolean check_auto_accept_incoming_filetransfer(Message message)
    {
        try
        {
            if (get_filetransfer_state_from_id(message.filetransfer_id) != TOX_FILE_CONTROL_PAUSE.value)
            {
                return false;
            }

            if (should_force_auto_accept_incoming(message))
            {
                return accept_paused_incoming_filetransfer(message);
            }

            String mimeType = guess_mime_type_from_filename(
                    get_filetransfer_filename_from_id(message.filetransfer_id).toLowerCase());
            // Log.i(TAG, "check_auto_accept_incoming_filetransfer:mime-type=" + mimeType);

            if (mimeType != null)
            {
                if (PREF__auto_accept_image)
                {
                    if (get_filetransfer_filesize_from_id(message.filetransfer_id) <=
                        (long) AUTO_ACCEPT_FT_MAX_IMAGE_SIZE_IN_MB * 1024 *
                        1024) // if file size is smaller than 12 MByte accept FT
                    {
                        if (mimeType.startsWith("image/"))
                        {
                            if (get_filetransfer_state_from_id(message.filetransfer_id) == TOX_FILE_CONTROL_PAUSE.value)
                            {
                                // accept FT
                                set_filetransfer_accepted_from_id(message.filetransfer_id);
                                set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_RESUME.value);
                                set_message_accepted_from_id(message.id);
                                set_message_state_from_id(message.id, TOX_FILE_CONTROL_RESUME.value);
                                tox_file_control(tox_friend_by_public_key__wrapper(message.tox_friendpubkey),
                                                 get_filetransfer_filenum_from_id(message.filetransfer_id),
                                                 TOX_FILE_CONTROL_RESUME.value);

                                // update message view
                                update_single_message_from_messge_id(message.id, true);
                                // Log.i(TAG, "check_auto_accept_incoming_filetransfer:image:accepted");
                                return true;
                            }
                        }
                    }
                }

                if (PREF__auto_accept_video)
                {
                    if (get_filetransfer_filesize_from_id(message.filetransfer_id) <=
                        (long) AUTO_ACCEPT_FT_MAX_VIDEO_SIZE_IN_MB * 1024 *
                        1024) // if file size is smaller than 40 MByte accept FT
                    {
                        if (mimeType.startsWith("video/"))
                        {
                            if (get_filetransfer_state_from_id(message.filetransfer_id) == TOX_FILE_CONTROL_PAUSE.value)
                            {
                                // accept FT
                                set_filetransfer_accepted_from_id(message.filetransfer_id);
                                set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_RESUME.value);
                                set_message_accepted_from_id(message.id);
                                set_message_state_from_id(message.id, TOX_FILE_CONTROL_RESUME.value);
                                tox_file_control(tox_friend_by_public_key__wrapper(message.tox_friendpubkey),
                                                 get_filetransfer_filenum_from_id(message.filetransfer_id),
                                                 TOX_FILE_CONTROL_RESUME.value);

                                // update message view
                                update_single_message_from_messge_id(message.id, true);
                                // Log.i(TAG, "check_auto_accept_incoming_filetransfer:video:accepted");
                                return true;
                            }
                        }
                    }
                }
            }

            if (PREF__auto_accept_all_upto)
            {
                if (get_filetransfer_filesize_from_id(message.filetransfer_id) <=
                    (long) AUTO_ACCEPT_FT_MAX_ANYKIND_SIZE_IN_MB * 1024 *
                    1024) // if file size is smaller than 200 MByte accept FT
                {
                    if (get_filetransfer_state_from_id(message.filetransfer_id) == TOX_FILE_CONTROL_PAUSE.value)
                    {
                        // accept FT
                        set_filetransfer_accepted_from_id(message.filetransfer_id);
                        set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_RESUME.value);
                        set_message_accepted_from_id(message.id);
                        set_message_state_from_id(message.id, TOX_FILE_CONTROL_RESUME.value);
                        tox_file_control(tox_friend_by_public_key__wrapper(message.tox_friendpubkey),
                                         get_filetransfer_filenum_from_id(message.filetransfer_id),
                                         TOX_FILE_CONTROL_RESUME.value);

                        // update message view
                        update_single_message_from_messge_id(message.id, true);
                        // Log.i(TAG, "check_auto_accept_incoming_filetransfer:video:accepted");
                        return true;
                    }
                }
            }

        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return false;
    }

    /** Accept paused incoming DM file if auto-accept prefs did not resume it yet. */
    static void ensure_incoming_dm_file_accepted(final Message message)
    {
        if (message == null || message.filetransfer_id <= 0)
        {
            return;
        }

        try
        {
            if (get_filetransfer_state_from_id(message.filetransfer_id) != TOX_FILE_CONTROL_PAUSE.value)
            {
                return;
            }

            final long filesize = get_filetransfer_filesize_from_id(message.filetransfer_id);
            if ((filesize <= 0) || (filesize > KHANDAQ_MAX_FILE_TRANSFER_BYTES))
            {
                return;
            }

            accept_paused_incoming_filetransfer(message);
        }
        catch (Exception e)
        {
            Log.i(TAG, "ensure_incoming_dm_file_accepted:EE:" + e.getMessage());
        }
    }

    public static String get_incoming_filetransfer_local_filename(String incoming_filename, String friend_pubkey_str)
    {
        String result = TrifaSetPatternActivity.filter_out_specials_from_filepath(incoming_filename);
        String wanted_full_filename_path = VFS_PREFIX + VFS_FILE_DIR + "/" + friend_pubkey_str;

        // Log.i(TAG, "check_auto_accept_incoming_filetransfer:start=" + incoming_filename + " " + result + " " +
        //           wanted_full_filename_path);

        info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                wanted_full_filename_path + "/" + result);

        if (f1.exists())
        {
            Random random = new Random();
            long new_random_log = (long) random.nextInt() + (1L << 31);

            // Log.i(TAG, "check_auto_accept_incoming_filetransfer:new_random_log=" + new_random_log);

            String random_filename_addon = TrifaSetPatternActivity.filter_out_specials(
                    TrifaSetPatternActivity.bytesToString(TrifaSetPatternActivity.sha256(
                            TrifaSetPatternActivity.StringToBytes2("" + new_random_log))));

            // Log.i(TAG, "check_auto_accept_incoming_filetransfer:random_filename_addon=" + random_filename_addon);

            String extension = "";

            try
            {
                extension = result.substring(result.lastIndexOf("."));

                if (extension.equalsIgnoreCase("."))
                {
                    extension = "";
                }
            }
            catch (Exception e)
            {
                extension = "";
            }

            result = result + "_" + random_filename_addon + extension;

            // Log.i(TAG, "check_auto_accept_incoming_filetransfer:result=" + result);
        }

        return result;
    }

    public static long get_filetransfer_id_from_friendnum_and_filenum(long friend_number, long file_number)
    {
        return get_filetransfer_id_from_friendnum_and_filenum(friend_number, file_number, -1);
    }

    public static long get_outgoing_filetransfer_id_from_friendnum_and_filenum(long friend_number, long file_number)
    {
        return get_filetransfer_id_from_friendnum_and_filenum(friend_number, file_number,
                                                              TRIFA_FT_DIRECTION_OUTGOING.value);
    }

    static final class tox_send_filename
    {
        final String name;
        final long utf8_length;

        tox_send_filename(String name, long utf8_length)
        {
            this.name = name;
            this.utf8_length = utf8_length;
        }
    }

    static tox_send_filename tox_send_filename_for_filetransfer(final String file_name)
    {
        if (file_name == null)
        {
            return new tox_send_filename("", 0);
        }

        byte[] utf8 = file_name.getBytes(StandardCharsets.UTF_8);
        if (utf8.length <= TOX_MAX_FILENAME_LENGTH)
        {
            return new tox_send_filename(file_name, utf8.length);
        }

        byte[] truncated = Arrays.copyOf(utf8, TOX_MAX_FILENAME_LENGTH);
        return new tox_send_filename(new String(truncated, StandardCharsets.UTF_8), TOX_MAX_FILENAME_LENGTH);
    }

    public static long get_filetransfer_id_from_friendnum_and_filenum(long friend_number, long file_number,
                                                                      int direction)
    {
        try
        {
            if (direction >= 0)
            {
                return orma.selectFromFiletransfer().
                        tox_public_key_stringEq(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).
                        file_numberEq(file_number).
                        directionEq(direction).
                        orderByIdDesc().
                        toList().
                        get(0).id;
            }

            return orma.selectFromFiletransfer().
                    tox_public_key_stringEq(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).
                    file_numberEq(file_number).
                    orderByIdDesc().
                    toList().
                    get(0).id;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "get_filetransfer_id_from_friendnum_and_filenum:EE:" + e.getMessage());
            return -1;
        }
    }

    public static void delete_filetransfer_tmpfile(long friend_number, long file_number)
    {
        try
        {
            delete_filetransfer_tmpfile(orma.selectFromFiletransfer().tox_public_key_stringEq(
                    HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).file_numberEq(
                    file_number).get(0).id);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void delete_filetransfer_tmpfile(long filetransfer_id)
    {
        try
        {
            Filetransfer ft = (Filetransfer) orma.selectFromFiletransfer().idEq(filetransfer_id).get(0);

            if (MainActivity.VFS_ENCRYPT)
            {
                info.guardianproject.iocipher.File f1 = new info.guardianproject.iocipher.File(
                        VFS_PREFIX + VFS_TMP_FILE_DIR + "/" + ft.tox_public_key_string + "/" + ft.file_name);
                f1.delete();
            }
            else
            {
                java.io.File f1 = new java.io.File(
                        VFS_PREFIX + VFS_TMP_FILE_DIR + "/" + ft.tox_public_key_string + "/" + ft.file_name);
                f1.delete();
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void set_filetransfer_state_from_id(long filetransfer_id, int state)
    {
        try
        {
            orma.updateFiletransfer().idEq(filetransfer_id).state(state).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void set_filetransfer_start_sending_from_id(long filetransfer_id)
    {
        try
        {
            orma.updateFiletransfer().idEq(filetransfer_id).ft_outgoing_started(true).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void set_filetransfer_accepted_from_id(long filetransfer_id)
    {
        try
        {
            orma.updateFiletransfer().idEq(filetransfer_id).ft_accepted(true).
                    transfer_start_ts(System.currentTimeMillis()).
                    execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static String format_speed(float bytes_per_second) {
        try {
            if (bytes_per_second > 1024) {
                if (bytes_per_second > (1024 * 1024)) {
                    return String.format("%.2f MiB/s", bytes_per_second / (1024 * 1024));
                } else {
                    return String.format("%.2f kiB/s", bytes_per_second / 1024);
                }
            } else {
                return String.format("%.2f Bytes/s", bytes_per_second);
            }
        } catch (Exception e) {
        }
        return "? Bytes/s";
    }

    public static long get_filetransfer_filenum_from_id(long filetransfer_id)
    {
        try
        {
            if (orma.selectFromFiletransfer().idEq(filetransfer_id).count() == 1)
            {
                return orma.selectFromFiletransfer().idEq(filetransfer_id).get(0).file_number;
            }
            else
            {
                return -1;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
    }

    public static long get_filetransfer_filesize_from_id(long filetransfer_id)
    {
        try
        {
            if (orma.selectFromFiletransfer().idEq(filetransfer_id).count() == 1)
            {
                return orma.selectFromFiletransfer().idEq(filetransfer_id).get(0).filesize;
            }
            else
            {
                return -1;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return -1;
        }
    }

    public static long get_filetransfer_state_from_id(long filetransfer_id)
    {
        try
        {
            if (orma.selectFromFiletransfer().idEq(filetransfer_id).count() == 1)
            {
                return orma.selectFromFiletransfer().idEq(filetransfer_id).get(0).state;
            }
            else
            {
                return TOX_FILE_CONTROL_CANCEL.value;
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return TOX_FILE_CONTROL_CANCEL.value;
        }
    }

    public static String get_filetransfer_filename_from_id(long filetransfer_id)
    {
        try
        {
            if (orma.selectFromFiletransfer().idEq(filetransfer_id).count() == 1)
            {
                return orma.selectFromFiletransfer().idEq(filetransfer_id).get(0).file_name;
            }
            else
            {
                return "";
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "";
        }
    }

    public static String get_filetransfer_path_name_from_id(long filetransfer_id)
    {
        try
        {
            if (orma.selectFromFiletransfer().idEq(filetransfer_id).count() == 1)
            {
                return orma.selectFromFiletransfer().idEq(filetransfer_id).get(0).path_name;
            }
            else
            {
                return "";
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            return "";
        }
    }

    public static void set_filetransfer_for_message_from_friendnum_and_filenum(long friend_number, long file_number, long ft_id)
    {
        try
        {
            set_filetransfer_for_message_from_filetransfer_id(orma.selectFromFiletransfer().
                    tox_public_key_stringEq(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).
                    file_numberEq(file_number).
                    orderByIdDesc().
                    get(0).id, ft_id);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void set_filetransfer_for_message_from_filetransfer_id(long filetransfer_id, long ft_id)
    {
        try
        {
            orma.updateMessage().filetransfer_idEq(filetransfer_id).filetransfer_id(ft_id).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void delete_filetransfers_from_friendnum_and_filenum(long friend_number, long file_number)
    {
        try
        {
            long del_ft_id = orma.selectFromFiletransfer().
                    tox_public_key_stringEq(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).
                    file_numberEq(file_number).
                    orderByIdDesc().
                    get(0).id;
            // Log.i(TAG, "delete_ft:id=" + del_ft_id);
            delete_filetransfers_from_id(del_ft_id);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    public static void delete_filetransfers_from_id(long filetransfer_id)
    {
        try
        {
            // Log.i(TAG, "delete_ft:id=" + filetransfer_id);
            orma.deleteFromFiletransfer().idEq(filetransfer_id).execute();
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void cancel_filetransfer_f(final Filetransfer f)
    {
        try
        {
            if (f == null)
            {
                return;
            }

            long ft_id = f.id;
            long msg_id = HelperMessage.get_message_id_from_filetransfer_id(ft_id);

            if (f.direction == TRIFA_FT_DIRECTION_INCOMING.value)
            {
                if ((f.kind == TOX_FILE_KIND_DATA.value) || (f.kind == TOX_FILE_KIND_FTV2.value))
                {
                    delete_filetransfer_tmpfile(ft_id);
                    HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);
                    remove_vfs_ft_from_cache(f);
                    set_filetransfer_for_message_from_filetransfer_id(ft_id, -1);
                    delete_filetransfers_from_id(ft_id);
                    try
                    {
                        if (f.id != -1)
                        {
                            HelperMessage.update_single_message_from_messge_id(msg_id, true);
                        }
                    }
                    catch (Exception e)
                    {
                    }
                }
                else // avatar FT
                {
                    set_filetransfer_state_from_id(ft_id, TOX_FILE_CONTROL_CANCEL.value);
                    HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);
                    remove_vfs_ft_from_cache(f);
                    delete_filetransfer_tmpfile(ft_id);
                    delete_filetransfers_from_id(ft_id);
                }
            }
            else // outgoing FT
            {
                if ((f.kind == TOX_FILE_KIND_DATA.value) || (f.kind == TOX_FILE_KIND_FTV2.value))
                {
                    HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);
                    remove_ft_from_cache(f);
                    set_filetransfer_for_message_from_filetransfer_id(ft_id, -1);
                    delete_filetransfers_from_id(ft_id);
                    try
                    {
                        if (f.id != -1)
                        {
                            HelperMessage.update_single_message_from_messge_id(msg_id, true);
                        }
                    }
                    catch (Exception e)
                    {
                    }
                }
                else // avatar FT
                {
                    set_filetransfer_state_from_id(ft_id, TOX_FILE_CONTROL_CANCEL.value);
                    if (msg_id > -1)
                    {
                        HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);
                    }
                    remove_ft_from_cache(f);
                    delete_filetransfer_tmpfile(ft_id);
                    delete_filetransfers_from_id(ft_id);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void cancel_filetransfer(long friend_number, long file_number)
    {
        Filetransfer f = null;

        try
        {
            f = (Filetransfer) orma.selectFromFiletransfer().
                    file_numberEq(file_number).
                    tox_public_key_stringEq(HelperFriend.tox_friend_get_public_key__wrapper(friend_number)).
                    orderByIdDesc().
                    toList().get(0);

            log_filetransfer_cancel(friend_number, file_number, f, "peer_cancel_or_local");

            if (f.direction == TRIFA_FT_DIRECTION_INCOMING.value)
            {
                if ((f.kind == TOX_FILE_KIND_DATA.value) || (f.kind == TOX_FILE_KIND_FTV2.value))
                {
                    long ft_id = get_filetransfer_id_from_friendnum_and_filenum(friend_number, file_number);
                    long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(ft_id, friend_number);
                    // delete tmp file
                    delete_filetransfer_tmpfile(friend_number, file_number);
                    // set state for FT in message
                    HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);
                    remove_vfs_ft_from_cache(f);
                    // remove link to any message
                    set_filetransfer_for_message_from_friendnum_and_filenum(friend_number, file_number, -1);
                    // delete FT in DB
                    // Log.i(TAG, "FTFTFT:002");
                    delete_filetransfers_from_friendnum_and_filenum(friend_number, file_number);

                    // update UI
                    // TODO: updates all messages, this is bad
                    // update_all_messages_global(false);
                    try
                    {
                        if (f.id != -1)
                        {
                            HelperMessage.update_single_message_from_messge_id(msg_id, true);
                        }
                    }
                    catch (Exception e)
                    {
                    }
                }
                else // avatar FT
                {
                    long ft_id = get_filetransfer_id_from_friendnum_and_filenum(friend_number, file_number);
                    long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(ft_id, friend_number);
                    set_filetransfer_state_from_id(ft_id, TOX_FILE_CONTROL_CANCEL.value);
                    HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);
                    remove_vfs_ft_from_cache(f);
                    // delete tmp file
                    delete_filetransfer_tmpfile(friend_number, file_number);
                    // delete FT in DB
                    // Log.i(TAG, "FTFTFT:003");
                    delete_filetransfers_from_friendnum_and_filenum(friend_number, file_number);
                }
            }
            else // outgoing FT
            {
                if ((f.kind == TOX_FILE_KIND_DATA.value) || (f.kind == TOX_FILE_KIND_FTV2.value))
                {
                    long ft_id = get_filetransfer_id_from_friendnum_and_filenum(friend_number, file_number);
                    long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(ft_id, friend_number);
                    // set state for FT in message
                    HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);
                    remove_ft_from_cache(f);
                    // remove link to any message
                    set_filetransfer_for_message_from_friendnum_and_filenum(friend_number, file_number, -1);
                    // delete FT in DB
                    // Log.i(TAG, "FTFTFT:OGFT:002");
                    delete_filetransfers_from_friendnum_and_filenum(friend_number, file_number);

                    // update UI
                    try
                    {
                        if (f.id != -1)
                        {
                            HelperMessage.update_single_message_from_messge_id(msg_id, true);
                        }
                    }
                    catch (Exception e)
                    {
                    }
                }
                else // avatar FT
                {
                    long ft_id = get_filetransfer_id_from_friendnum_and_filenum(friend_number, file_number);
                    long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(ft_id, friend_number);
                    set_filetransfer_state_from_id(ft_id, TOX_FILE_CONTROL_CANCEL.value);

                    if (msg_id > -1)
                    {
                        HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);
                    }

                    remove_ft_from_cache(f);

                    // delete tmp file
                    delete_filetransfer_tmpfile(friend_number, file_number);
                    // delete FT in DB
                    // Log.i(TAG, "FTFTFT:OGFT:003");
                    delete_filetransfers_from_friendnum_and_filenum(friend_number, file_number);
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void set_all_filetransfers_inactive()
    {
        try
        {
            List<com.zoffcc.applications.sorm.Filetransfer> fts_active = orma.selectFromFiletransfer().file_numberNotEq(-1).toList();
            for (com.zoffcc.applications.sorm.Filetransfer f : fts_active)
            {
                // Log.i(TAG, "set_all_filetransfers_inactive:cancel:id=" + f.tox_file_id_hex + " filename=" + f.file_name);
                cancel_filetransfer_f((Filetransfer) f);
            }

            orma.updateFiletransfer().
                    file_number(-1).
                    state(TOX_FILE_CONTROL_CANCEL.value).
                    execute();
            Log.i(TAG, "set_all_filetransfers_inactive");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "set_all_filetransfers_inactive:EE:" + e.getMessage());
        }
    }

    static void update_filetransfer_db_current_position(final Filetransfer f)
    {
        orma.updateFiletransfer().
                tox_public_key_stringEq(f.tox_public_key_string).
                file_numberEq(f.file_number).
                current_position(f.current_position).
                execute();
    }

    static void update_filetransfer_db_full(final Filetransfer f)
    {
        orma.updateFiletransfer().
                idEq(f.id).
                tox_public_key_string(f.tox_public_key_string).
                direction(f.direction).
                file_number(f.file_number).
                kind(f.kind).
                state(f.state).
                path_name(f.path_name).
                message_id(f.message_id).
                file_name(f.file_name).
                fos_open(f.fos_open).
                filesize(f.filesize).
                current_position(f.current_position).
                tox_file_id_hex(f.tox_file_id_hex).
                execute();
    }

    static void update_filetransfer_db_full_from_id(final Filetransfer f, long fid)
    {
        orma.updateFiletransfer().
                idEq(fid).
                tox_public_key_string(f.tox_public_key_string).
                direction(f.direction).
                file_number(f.file_number).
                kind(f.kind).
                state(f.state).
                path_name(f.path_name).
                message_id(f.message_id).
                file_name(f.file_name).
                fos_open(f.fos_open).
                filesize(f.filesize).
                current_position(f.current_position).
                execute();
    }

    static void update_filetransfer_db_messageid_from_id(final Filetransfer f, long fid)
    {
        orma.updateFiletransfer().
                idEq(fid).
                message_id(f.message_id).
                execute();
    }

    static long insert_into_filetransfer_db(final Filetransfer f)
    {
        //Thread t = new Thread()
        //{
        //    @Override
        //    public void run()
        //    {
        try
        {
            long row_id = orma.insertIntoFiletransfer(f);
            return row_id;
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "insert_into_filetransfer_db:EE:" + e.getMessage());
            return -1;
        }

        //    }
        //};
        //t.start();
    }

    static void flush_and_close_vfs_ft_from_cache(Filetransfer f)
    {
        try
        {
            BufferedOutputStreamCustom fos = cache_ft_fos.get(f.path_name + "/" + f.file_name);
            if (fos != null)
            {
                fos.flush();
                fos.close();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static void remove_vfs_ft_from_cache(Filetransfer f)
    {
        try
        {
            BufferedOutputStreamCustom fos = cache_ft_fos.get(f.path_name + "/" + f.file_name);
            if (fos != null)
            {
                fos.close();
            }
        }
        catch (Exception ignored)
        {
        }
        // Log.i(TAG, "remove_vfs_ft_from_cache:f:" + f.path_name + "/" + f.file_name);
        cache_ft_fos.remove(f.path_name + "/" + f.file_name);
    }

    static void remove_vfs_ft_from_cache(Message m)
    {
        try
        {
            String path_name = get_filetransfer_filename_from_id(m.filetransfer_id);
            String file_name = get_filetransfer_path_name_from_id(m.filetransfer_id);
            BufferedOutputStreamCustom fos = cache_ft_fos.get(path_name + "/" + file_name);
            // Log.i(TAG, "remove_vfs_ft_from_cache:m:" + path_name + "/" + file_name);
            if (fos != null)
            {
                try
                {
                    fos.close();
                }
                catch (Exception e2)
                {
                }
            }
            cache_ft_fos.remove(path_name + "/" + file_name);
        }
        catch (Exception e2)
        {
        }
    }

    static void remove_ft_from_cache(Filetransfer f)
    {
        try
        {
            if (f.storage_frame_work)
            {
                // Log.i(TAG, "remove_ft_from_cache:" + f.path_name);
                try
                {
                    PositionInputStream fis = cache_ft_fis_saf.get(f.path_name);
                    if (fis != null)
                    {
                        fis.close();
                    }
                }
                catch (Exception e2)
                {
                }
                cache_ft_fis_saf.remove(f.path_name);
            }
        }
        catch (Exception e)
        {
        }
    }

    static void remove_ft_from_cache(Message m)
    {
        try
        {
            if (m.storage_frame_work)
            {
                // Log.i(TAG, "remove_ft_from_cache:" + m.filename_fullpath);
                try
                {
                    PositionInputStream fis = cache_ft_fis_saf.get(m.filename_fullpath);
                    if (fis != null)
                    {
                        fis.close();
                    }
                }
                catch (Exception e2)
                {
                }
                cache_ft_fis_saf.remove(m.filename_fullpath);
            }
        }
        catch (Exception e)
        {
        }
    }

    static void start_outgoing_ft(Message m)
    {
        try
        {
            if (!FileTransferConcurrencyGate.canStartOutgoingTransfer())
            {
                set_message_queueing_from_id(m.id, true);
                orma.updateMessage().idEq(m.id).ft_outgoing_queued(true).ft_outgoing_started(false).execute();
                update_single_message_from_messge_id(m.id, true);
                return;
            }

            if (has_active_outgoing_filetransfer_for_pubkey(m.tox_friendpubkey))
            {
                set_message_queueing_from_id(m.id, true);
                orma.updateMessage().idEq(m.id).ft_outgoing_queued(true).ft_outgoing_started(false).execute();
                update_single_message_from_messge_id(m.id, true);
                return;
            }

            set_message_queueing_from_id(m.id, false);

            Filetransfer ft = (Filetransfer) orma.selectFromFiletransfer().
                    idEq(m.filetransfer_id).
                    orderByIdDesc().get(0);

            Log.i(TAG, "MM2MM:8:ft.filesize=" + ft.filesize + " ftid=" + ft.id + " ft.mid=" + ft.message_id + " mid=" +
                       m.id);

            // ------ DEBUG ------
            // Log.i(TAG, "MM2MM:8a:ft full=" + ft);
            // ------ DEBUG ------

            ByteBuffer file_id_buffer = ByteBuffer.allocateDirect(TOX_FILE_ID_LENGTH);

            //if (TOX_CAPABILITY_DECODE(
            //        tox_friend_get_capabilities(tox_friend_by_public_key__wrapper(m.tox_friendpubkey))).ftv2)
            //{
            MainActivity.tox_messagev3_get_new_message_id(file_id_buffer);
            //}
            //else
            //{
            //    byte[] sha256_buf = TrifaSetPatternActivity.sha256(TrifaSetPatternActivity.StringToBytes2(
            //            "" + ft.path_name + ":" + ft.file_name + ":" + ft.filesize));
            //    file_id_buffer.put(sha256_buf);
            //}

            final String file_id_buffer_hex = HelperGeneric.bytesToHex(file_id_buffer.array(),
                                                                       file_id_buffer.arrayOffset(),
                                                                       file_id_buffer.limit()).toUpperCase();
            Log.i(TAG, "TOX_FILE_ID_LENGTH=" + TOX_FILE_ID_LENGTH + " file_id_buffer_hex=" + file_id_buffer_hex);
            ft.tox_file_id_hex = file_id_buffer_hex;

            final tox_send_filename send_name = tox_send_filename_for_filetransfer(ft.file_name);

            final long friendNum = tox_friend_by_public_key__wrapper(m.tox_friendpubkey);
            FileTransferDebug.logPeerConnectionStatus(friendNum);
            FileTransferDebug.logToxFileSendCall(friendNum, ft.filesize);

            long file_number = -1;
            if (TOX_CAPABILITY_DECODE(tox_friend_get_capabilities(friendNum)).ftv2)
            {
                file_number = tox_file_send(friendNum,
                                            ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_FTV2.value, ft.filesize, file_id_buffer,
                                            send_name.name, send_name.utf8_length);
                ft.kind = ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_FTV2.value;
                m.filetransfer_kind = TOX_FILE_KIND_FTV2.value;
            }
            else
            {
                file_number = tox_file_send(friendNum,
                                            ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_DATA.value, ft.filesize, file_id_buffer,
                                            send_name.name, send_name.utf8_length);
                ft.kind = ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_DATA.value;
                m.filetransfer_kind = TOX_FILE_KIND_DATA.value;
            }
            FileTransferDebug.logToxFileSendResult(file_number);

            update_message_in_db_filetransfer_kind(m);

            if (file_number < 0)
            {
                Log.i(TAG, "tox_file_send:EE:" + file_number + " — re-queue for later");

                orma.updateMessage().idEq(m.id).ft_outgoing_started(false).ft_outgoing_queued(true).execute();
                orma.updateFiletransfer().idEq(m.filetransfer_id).ft_outgoing_started(false).execute();
                update_single_message_from_messge_id(m.id, true);
                schedule_outgoing_ft_retry(m.id);
            }
            else
            {
                set_message_start_sending_from_id(m.id);
                set_filetransfer_start_sending_from_id(m.filetransfer_id);
                update_single_message_from_messge_id(m.id, true);

                Log.i(TAG, "MM2MM:9:new filenum=" + file_number);

                ft.file_number = file_number;
                update_filetransfer_db_full(ft);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    private static final LongSparseArray<Long> last_outgoing_kick_ts = new LongSparseArray<>();
    private static final long OUTGOING_FT_RETRY_DELAY_MS = 5000L;
    private static final long OUTGOING_FT_STALE_MS = 120_000L;
    private static final long OUTGOING_FT_STALE_LARGE_MS = 600_000L;

    static String describe_chunk_send_error(final int res)
    {
        switch (res)
        {
            case 0:
                return "OK";
            case -1:
                return "NULL";
            case -2:
                return "FRIEND_NOT_FOUND";
            case -3:
                return "FRIEND_NOT_CONNECTED";
            case -4:
                return "NOT_FOUND";
            case -5:
                return "NOT_TRANSFERRING";
            case -6:
                return "INVALID_LENGTH";
            case -7:
                return "SENDQ";
            case -8:
                return "WRONG_POSITION";
            case -21:
                return "NULL_BUFFER";
            default:
                return "ERR_" + res;
        }
    }

    static void handle_outgoing_chunk_send_result(final com.zoffcc.applications.sorm.Filetransfer ft,
                                                  final long friend_number, final long file_number,
                                                  final long position, final long length, final int res)
    {
        if (res == 0 || ft == null)
        {
            return;
        }

        Log.w(TAG, "outgoing_chunk_send:res=" + res + " (" + describe_chunk_send_error(res) + ") fn=" + friend_number
                + " filenum=" + file_number + " pos=" + position + " len=" + length + " ft_id=" + ft.id
                + " file=" + ft.file_name + " size=" + ft.filesize + " saf=" + ft.storage_frame_work);

        if (res == -3 || res == -7)
        {
            TrifaToxService.wakeup_tox_thread();
        }

        if (res == -3 || res == -4 || res == -5)
        {
            try
            {
                final long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(ft.id, friend_number);
                if (msg_id > 0)
                {
                    schedule_outgoing_ft_retry(msg_id);
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "handle_outgoing_chunk_send_result:retry:EE:" + e.getMessage());
            }
        }
    }

    static void log_filetransfer_cancel(final long friend_number, final long file_number,
                                        final com.zoffcc.applications.sorm.Filetransfer f, final String reason)
    {
        if (f == null)
        {
            Log.w(TAG, "filetransfer_cancel:reason=" + reason + " fn=" + friend_number + " filenum=" + file_number
                    + " ft=unknown");
            return;
        }

        Log.w(TAG, "filetransfer_cancel:reason=" + reason + " fn=" + friend_number + " filenum=" + file_number
                + " ft_id=" + f.id + " dir=" + f.direction + " state=" + f.state + " kind=" + f.kind
                + " file=" + f.file_name + " size=" + f.filesize + " pos=" + f.current_position
                + " saf=" + f.storage_frame_work);
    }

    private static long outgoing_ft_stale_ms_for(final Filetransfer ft)
    {
        if (ft == null)
        {
            return OUTGOING_FT_STALE_MS;
        }
        if (ft.filesize > 50_000_000L)
        {
            return OUTGOING_FT_STALE_LARGE_MS;
        }
        if (ft.filesize > 10_000_000L)
        {
            return OUTGOING_FT_STALE_MS * 2L;
        }
        return OUTGOING_FT_STALE_MS;
    }

    static boolean has_active_outgoing_filetransfer_for_pubkey(final String pubkey)
    {
        if (pubkey == null || pubkey.isEmpty())
        {
            return false;
        }

        try
        {
            final List<Message> active = orma.selectFromMessage().
                    tox_friendpubkeyEq(pubkey).
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    ft_outgoing_startedEq(true).
                    stateNotEq(TOX_FILE_CONTROL_CANCEL.value).
                    toList();
            if ((active == null) || active.isEmpty())
            {
                return false;
            }

            final long now = System.currentTimeMillis();
            for (Message m : active)
            {
                if (message_has_live_outgoing_transfer(m, now))
                {
                    return true;
                }
            }
            return false;
        }
        catch (Exception e)
        {
            Log.i(TAG, "has_active_outgoing_filetransfer_for_pubkey:EE:" + e.getMessage());
            return false;
        }
    }

    private static boolean message_has_live_outgoing_transfer(final Message m, final long now)
    {
        try
        {
            if (m.filetransfer_id <= 0L)
            {
                reset_stalled_outgoing_message(m);
                return false;
            }

            final Filetransfer ft = (Filetransfer) orma.selectFromFiletransfer().idEq(m.filetransfer_id).get(0);
            if (ft.state == TOX_FILE_CONTROL_CANCEL.value)
            {
                return false;
            }

            if ((ft.filesize > 0) && (ft.current_position >= ft.filesize))
            {
                return false;
            }

            if (ft.file_number < 0)
            {
                final long ageMs = now - Math.max(m.sent_timestamp_ms, m.sent_timestamp);
                if (ageMs > outgoing_ft_stale_ms_for(ft))
                {
                    reset_stalled_outgoing_message(m);
                    return false;
                }
            }

            return true;
        }
        catch (Exception e)
        {
            Log.i(TAG, "message_has_live_outgoing_transfer:EE:" + e.getMessage());
            return false;
        }
    }

    private static void reset_stalled_outgoing_message(final Message m)
    {
        try
        {
            orma.updateMessage().idEq(m.id).
                    ft_outgoing_started(false).
                    ft_outgoing_queued(true).
                    execute();
            if (m.filetransfer_id > 0L)
            {
                orma.updateFiletransfer().idEq(m.filetransfer_id).ft_outgoing_started(false).execute();
            }
            Log.i(TAG, "reset_stalled_outgoing_message:mid=" + m.id);
        }
        catch (Exception e)
        {
            Log.i(TAG, "reset_stalled_outgoing_message:EE:" + e.getMessage());
        }
    }

    static void kick_stalled_outgoing_send(final Message message)
    {
        if ((message == null) || (!message.ft_outgoing_queued) || message.ft_outgoing_started)
        {
            return;
        }
        if (message.state != TOX_FILE_CONTROL_PAUSE.value)
        {
            return;
        }

        final long now = System.currentTimeMillis();
        final Long last = last_outgoing_kick_ts.get(message.id);
        if ((last != null) && ((now - last) < 3000L))
        {
            return;
        }
        last_outgoing_kick_ts.put(message.id, now);

        final Handler handler = MainActivity.main_handler_s != null
                ? MainActivity.main_handler_s
                : new Handler(Looper.getMainLooper());
        handler.post(() -> queue_and_try_send_outgoing_file(message.id, false));
    }

    static void schedule_outgoing_ft_retry(final long messageId)
    {
        if (messageId <= 0L)
        {
            return;
        }

        final Handler handler = MainActivity.main_handler_s != null
                ? MainActivity.main_handler_s
                : new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> {
            try
            {
                final Message m = (Message) orma.selectFromMessage().idEq(messageId).get(0);
                if (m.ft_outgoing_queued && (!m.ft_outgoing_started) && (m.state == TOX_FILE_CONTROL_PAUSE.value))
                {
                    queue_and_try_send_outgoing_file(messageId, true);
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "schedule_outgoing_ft_retry:EE:" + e.getMessage());
            }
        }, OUTGOING_FT_RETRY_DELAY_MS);
    }

    static void on_friend_connection_available(final String pubkey)
    {
        if ((pubkey == null) || pubkey.isEmpty())
        {
            return;
        }
        FileTransferDebug.logPeerConnectionStatus(tox_friend_by_public_key__wrapper(pubkey));
        try_start_next_queued_outgoing_file(pubkey);
        TrifaToxService.wakeup_tox_thread();
    }

    static void try_start_next_queued_outgoing_file(final String pubkey)
    {
        if ((pubkey == null) || pubkey.isEmpty())
        {
            return;
        }

        try
        {
            if (has_active_outgoing_filetransfer_for_pubkey(pubkey))
            {
                return;
            }

            final List<Message> queued = orma.selectFromMessage().
                    tox_friendpubkeyEq(pubkey).
                    directionEq(1).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    ft_outgoing_queuedEq(true).
                    ft_outgoing_startedEq(false).
                    stateEq(TOX_FILE_CONTROL_PAUSE.value).
                    orderBySent_timestampAsc().
                    toList();

            if ((queued == null) || queued.isEmpty())
            {
                return;
            }

            queue_and_try_send_outgoing_file(queued.get(0).id, true);
        }
        catch (Exception e)
        {
            Log.i(TAG, "try_start_next_queued_outgoing_file:EE:" + e.getMessage());
        }
    }

    static boolean waitForOutgoingFileComplete(final long messageId, final long timeoutMs)
    {
        if (messageId <= 0L)
        {
            return false;
        }

        final long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline)
        {
            try
            {
                final Message m = (Message) orma.selectFromMessage().idEq(messageId).get(0);
                if (m.filedb_id > 0L)
                {
                    return true;
                }

                if (m.state == TOX_FILE_CONTROL_CANCEL.value)
                {
                    return false;
                }

                if (m.ft_outgoing_started)
                {
                    Thread.sleep(WAIT_STEP_MS_OUTGOING_FILE);
                    continue;
                }

                if (!has_active_outgoing_filetransfer_for_pubkey(m.tox_friendpubkey))
                {
                    return (m.filetransfer_id > 0L) || m.ft_outgoing_queued;
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "waitForOutgoingFileComplete:EE:" + e.getMessage());
                return false;
            }

            try
            {
                Thread.sleep(WAIT_STEP_MS_OUTGOING_FILE);
            }
            catch (InterruptedException ignored)
            {
            }
        }

        return false;
    }

    private static final long WAIT_STEP_MS_OUTGOING_FILE = 400L;

    static void queue_and_try_send_outgoing_file(long message_id, boolean update_view)
    {
        try
        {
            Message m = (Message) orma.selectFromMessage().idEq(message_id).get(0);

            if (m.ft_outgoing_started)
            {
                return;
            }

            if (has_active_outgoing_filetransfer_for_pubkey(m.tox_friendpubkey))
            {
                set_message_queueing_from_id(m.id, true);
                orma.updateMessage().idEq(m.id).ft_outgoing_queued(true).execute();
                if (update_view)
                {
                    update_single_message_from_messge_id(message_id, true);
                }
                return;
            }

            set_message_queueing_from_id(m.id, true);
            TrifaToxService.wakeup_tox_thread();

            if (m.sent_push < 1)
            {
                friend_call_push_url(m.tox_friendpubkey, m.sent_timestamp);
                orma.updateMessage().idEq(m.id).sent_push(1).execute();
            }

            final long friendNum = tox_friend_by_public_key__wrapper(m.tox_friendpubkey);
            final boolean peerOnlineReal = is_friend_online_real(friendNum) != 0;
            if (peerOnlineReal)
            {
                start_outgoing_ft(m);
            }
            else
            {
                orma.updateMessage().idEq(m.id).ft_outgoing_queued(true).ft_outgoing_started(false).execute();
            }
            FileTransferDebug.logQueueDecision(m.id, peerOnlineReal, !peerOnlineReal);

            if (update_view)
            {
                update_single_message_from_messge_id(message_id, true);
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "queue_and_try_send_outgoing_file:EE:" + e.getMessage());
        }
    }

    static String remove_bad_chars_from_outgoing_sdcard_filename(final String in)
    {
        try
        {
            return in.
                    replace("/", "_"). // / -> _
                    replace(":", "_"). // : -> _
                    replace("\n", "_"). // \n -> _
                    replace("\r", "_"). // \r -> _
                    replace("\t", "_"). // \t -> _
                    replace("..", "_"); // .. -> _
        }
        catch (Exception ignored)
        {
        }

        return null;
    }

    static long resolve_uri_file_size(final Context c, final Uri uri)
    {
        if ((c == null) || (uri == null))
        {
            return -1;
        }

        try
        {
            final DocumentFile documentFile = DocumentFile.fromSingleUri(c, uri);
            if (documentFile != null)
            {
                final long len = documentFile.length();
                if (len > 0)
                {
                    return len;
                }
            }
        }
        catch (Exception ignored)
        {
        }

        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null))
        {
            if (cur != null)
            {
                if (cur.moveToFirst())
                {
                    final int idx = cur.getColumnIndex(OpenableColumns.SIZE);
                    if ((idx >= 0) && (!cur.isNull(idx)))
                    {
                        final long sz = cur.getLong(idx);
                        if (sz > 0)
                        {
                            return sz;
                        }
                    }
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return -1;
    }

    static outgoing_file_wrapped copy_outgoing_file_to_sdcard_dir(final String filepath, final String filename, final long filesize)
    {
        outgoing_file_wrapped ret = new outgoing_file_wrapped();

        String new_fake_filename_prefix = bytesToHex(TrifaSetPatternActivity.
                sha256(TrifaSetPatternActivity.StringToBytes2(filepath))).
                substring(1, 5).toLowerCase();

        String filename_sd_card = remove_bad_chars_from_outgoing_sdcard_filename(
                new_fake_filename_prefix + "_" + filename);
        //Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:" + filename_sd_card + " : " + new_fake_filename_prefix + " : " +
        //           filepath + " : " + filename);

        if (filename_sd_card == null)
        {
            Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:003");
            return null;
        }

        try
        {

            File dir = new File(SD_CARD_FILES_OUTGOING_WRAPPER_DIR);
            dir.mkdirs();
            String filename2 = filename_sd_card;
            File file = new File(SD_CARD_FILES_OUTGOING_WRAPPER_DIR, filename2);

            long counter = 0;
            while (file.exists())
            {
                String extension = get_fileExt(filename_sd_card);
                if ((extension != null) && (extension.length() > 0))
                {
                    extension = "." + extension;
                }
                else
                {
                    extension = "";
                }

                //filename2 = remove_bad_chars_from_outgoing_sdcard_filename(
                //        filename_sd_card + "." + (long) ((Math.random() * 10000000d)) + extension);

                filename2 = (long) ((Math.random() * 1000000d)) + "_" + filename_sd_card;

                if (filename2 == null)
                {
                    Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:004");
                    return null;
                }

                file = new File(dir, filename2);
                counter++;

                // Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:" + filename2 + " " + SD_CARD_FILES_OUTGOING_WRAPPER_DIR);

                if (counter > 5000)
                {
                    Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:005");
                    return null;
                }
            }

            ret.filepath_wrapped = SD_CARD_FILES_OUTGOING_WRAPPER_DIR;
            ret.filename_wrapped = filename2;

            // now write that contents of the virtual file to the actual file on SD card ----------------
            // Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:filepath=" + filepath);
            InputStream in = null;
            try
            {
                 in = context_s.getContentResolver().openInputStream(Uri.parse(filepath));
            }
            catch(Exception e)
            {
                File fin_regular_file = new File(filepath + "/" +  filename);
                in = new java.io.FileInputStream(fin_regular_file);
            }
            BufferedOutputStream out = new BufferedOutputStream(
                    new FileOutputStream(SD_CARD_FILES_OUTGOING_WRAPPER_DIR + "/" + filename2));

            final int chunk_size = 4096;
            byte[] buffer = new byte[chunk_size];
            int read;
            while ((read = in.read(buffer)) != -1)
            {
                out.write(buffer, 0, read);
            }
            in.close();
            out.flush();
            out.close();
            // now write that contents of the virtual file to the actual file on SD card ----------------

            File file2 = new File(SD_CARD_FILES_OUTGOING_WRAPPER_DIR, filename2);
            ret.file_size_wrapped = file2.length();

            if (ret.file_size_wrapped < 1)
            {
                file2.delete();
                Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:006");
                return null;
            }

            return ret;
        }
        catch (Exception e)
        {
            Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:007:" + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    static String check_if_incoming_file_was_exported(final Message m)
    {
        try
        {
            final String export_filename = SD_CARD_FILES_EXPORT_DIR + "/" + m.tox_friendpubkey + "/";
            final FileDB file_ = (FileDB) orma.selectFromFileDB().idEq(m.filedb_id).get(0);
            java.io.File f = new java.io.File(export_filename + file_.file_name);
            if (f.exists() && (f.isFile()) && (f.canRead() && (f.length() > 0)))
            {
                return export_filename + file_.file_name;
            }
            else
            {
                // Log.i(TAG, "check_if_incoming_file_was_exported:null");
                return null;
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "check_if_incoming_file_was_exported:EE:" + e.getMessage());
            return null;
        }
    }

    static void share_local_file(final String filename_fullpath, final Context context)
    {
        Uri file_uri = FileProvider.getUriForFile(context, KhandaqProviders.STD_FILE_PROVIDER,
                                                  new java.io.File(filename_fullpath));

        Intent intent = new Intent(Intent.ACTION_SEND, file_uri);
        intent.putExtra(Intent.EXTRA_STREAM, file_uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        MimeTypeMap myMime = MimeTypeMap.getSingleton();
        String mimeType = myMime.getMimeTypeFromExtension(getFileExtensionFromUrl(filename_fullpath));
        if (mimeType == null)
        {
            mimeType = "application/octet-stream";
        }
        // Log.i(TAG, "share_local_file:mime type: " + mimeType);
        intent.setDataAndType(file_uri, mimeType);
        try
        {
            context.startActivity(Intent.createChooser(intent, "Share"));
        }
        catch (ActivityNotFoundException e)
        {
            Log.e(TAG, "share_local_file:No relevant Activity found", e);
        }
    }

    static void open_local_file(final String filename_fullpath, final Context context)
    {
        try
        {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                Uri file_uri = FileProvider.getUriForFile(context, KhandaqProviders.STD_FILE_PROVIDER,
                                                          new java.io.File(filename_fullpath));
                MimeTypeMap myMime = MimeTypeMap.getSingleton();
                String mimeType = myMime.getMimeTypeFromExtension(getFileExtensionFromUrl(filename_fullpath));
                Intent newIntent = new Intent(Intent.ACTION_VIEW);
                newIntent.setDataAndType(file_uri, mimeType);
                newIntent.setClipData(ClipData.newRawUri("", file_uri));
                newIntent.putExtra(Intent.EXTRA_STREAM, file_uri);
                newIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                // Log.i(TAG, "open_local_file:23:intent=" + newIntent);
                context.startActivity(newIntent);
            }
            else
            {
                MimeTypeMap myMime = MimeTypeMap.getSingleton();
                Intent newIntent = new Intent(Intent.ACTION_VIEW);
                String mimeType = myMime.getMimeTypeFromExtension(getFileExtensionFromUrl(filename_fullpath));
                Uri file_uri = Uri.parse(filename_fullpath);
                newIntent.setDataAndType(file_uri, mimeType);
                // Log.i(TAG, "open_local_file:lt23:intent=" + newIntent);
                context.startActivity(newIntent);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            display_toast(context_s.getString(R.string.chat_opening_file_failed), false, 0);
        }
    }

    static String display_name_from_message_text(String text)
    {
        if ((text == null) || (text.isEmpty()))
        {
            return null;
        }
        final int newline = text.indexOf('\n');
        final String name = (newline > 0) ? text.substring(0, newline) : text;
        return name.trim();
    }

    static String guess_mime_type_from_filename(String file_name)
    {
        if ((file_name == null) || (file_name.isEmpty()))
        {
            return "application/octet-stream";
        }

        try
        {
            final String guessed = URLConnection.guessContentTypeFromName(file_name.toLowerCase());
            if ((guessed != null) && (!guessed.isEmpty()))
            {
                return guessed;
            }
        }
        catch (Exception ignored)
        {
        }

        final String ext = getFileExtensionFromUrl(file_name);
        if ((ext != null) && (!ext.isEmpty()))
        {
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
            if ((mime != null) && (!mime.isEmpty()))
            {
                return mime;
            }
        }

        return "application/octet-stream";
    }

    static String guess_message_file_mime_type(Context context, Message message)
    {
        if (isVoiceMessage(message))
        {
            return "audio/mp4";
        }

        final String display_name = display_name_from_message_text(message.text);
        if ((display_name != null) && (!display_name.isEmpty()))
        {
            final String from_display = guess_mime_type_from_filename(display_name);
            if (!"application/octet-stream".equals(from_display))
            {
                return from_display;
            }
        }

        if (message.storage_frame_work)
        {
            try
            {
                final DocumentFile documentFile = DocumentFile.fromSingleUri(context,
                        Uri.parse(message.filename_fullpath));
                if ((documentFile != null) && (documentFile.getName() != null))
                {
                    final String from_uri_name = guess_mime_type_from_filename(documentFile.getName());
                    if (!"application/octet-stream".equals(from_uri_name))
                    {
                        return from_uri_name;
                    }
                }
            }
            catch (Exception ignored)
            {
            }
        }

        return guess_mime_type_from_filename(message.filename_fullpath);
    }

    static String guess_group_message_file_mime_type(final Context context, final GroupMessage message)
    {
        if (message == null)
        {
            return "application/octet-stream";
        }

        final String display_name = display_name_from_message_text(message.text);
        if ((display_name != null) && (!display_name.isEmpty()))
        {
            final String from_display = guess_mime_type_from_filename(display_name);
            if (!"application/octet-stream".equals(from_display))
            {
                return from_display;
            }
        }

        if ((message.file_name != null) && (!message.file_name.isEmpty()))
        {
            final String from_file_name = guess_mime_type_from_filename(message.file_name);
            if (!"application/octet-stream".equals(from_file_name))
            {
                return from_file_name;
            }
        }

        if (message.storage_frame_work)
        {
            try
            {
                final DocumentFile documentFile = DocumentFile.fromSingleUri(context,
                        Uri.parse(message.filename_fullpath));
                if ((documentFile != null) && (documentFile.getName() != null))
                {
                    final String from_uri_name = guess_mime_type_from_filename(documentFile.getName());
                    if (!"application/octet-stream".equals(from_uri_name))
                    {
                        return from_uri_name;
                    }
                }
            }
            catch (Exception ignored)
            {
            }
        }

        String mime = guess_mime_type_from_filename(message.filename_fullpath);
        if ("application/octet-stream".equals(mime)
                && HelperGroup.is_probable_video_file(message.file_name, null))
        {
            mime = guess_mime_type_from_filename(message.file_name);
            if ("application/octet-stream".equals(mime))
            {
                mime = "video/mp4";
            }
        }
        return mime;
    }

    static void open_outgoing_message_file(final Context context, final Message message)
    {
        ChatMediaHelper.openMessageMedia(context, message, null);
    }

    static void open_local_outgoing_file(final String filename_fullpath, final Context context)
    {
        open_local_outgoing_file_with_mime(filename_fullpath, context, null);
    }

    static void open_local_outgoing_file_with_mime(final String filename_fullpath, final Context context,
                                                   String mimeType)
    {
        try
        {
            if ((mimeType == null) || (mimeType.isEmpty()) || "application/octet-stream".equals(mimeType))
            {
                mimeType = guess_mime_type_from_filename(filename_fullpath);
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            {
                Uri file_uri = FileProvider.getUriForFile(context, KhandaqProviders.EXT2_PROVIDER,
                                                          new java.io.File(filename_fullpath));
                Intent newIntent = new Intent(Intent.ACTION_VIEW);
                newIntent.setDataAndType(file_uri, mimeType);
                newIntent.setClipData(ClipData.newRawUri("", file_uri));
                newIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(newIntent);
            }
            else
            {
                Intent newIntent = new Intent(Intent.ACTION_VIEW);
                Uri file_uri = Uri.parse(filename_fullpath);
                newIntent.setDataAndType(file_uri, mimeType);
                context.startActivity(newIntent);
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            display_toast("opening outgoing file failed", false, 0);
        }
    }

    static void save_group_incoming_file(String file_path_name, String file_name, byte[] data, long buffer_offset, long write_bytes)
    {
        try
        {
            if (buffer_offset >= 2100000L)
            {
                return;
            }
            if (write_bytes >= 2100000L)
            {
                return;
            }

            info.guardianproject.iocipher.File outfile = new info.guardianproject.iocipher.File(file_path_name + "/" + file_name);
            info.guardianproject.iocipher.FileOutputStream outputStream = new info.guardianproject.iocipher.FileOutputStream(outfile);
            outputStream.write(data, (int)buffer_offset, (int)write_bytes);
            outputStream.flush();
            outputStream.close();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "save_group_incoming_file:EE");
        }
    }

    static boolean isVoiceMessagePath(String path)
    {
        if ((path == null) || path.isEmpty())
        {
            return false;
        }
        return path.toLowerCase().contains(".file.m4a");
    }

    static boolean isIocipherVirtualPath(final String path)
    {
        return (path != null) && !path.isEmpty()
                && path.startsWith(TRIFAGlobals.VFS_PREFIX + TRIFAGlobals.VFS_FILE_DIR);
    }

    static boolean isMediaFileReadyForPlayback(final String path)
    {
        if (path == null || path.isEmpty())
        {
            return false;
        }

        if (path.startsWith("content://"))
        {
            return true;
        }

        try
        {
            if (!isIocipherVirtualPath(path))
            {
                final java.io.File plainFile = new java.io.File(path);
                if (plainFile.isFile() && plainFile.length() > 0L)
                {
                    return true;
                }
            }

            if (MainActivity.VFS_ENCRYPT)
            {
                final info.guardianproject.iocipher.File file = new info.guardianproject.iocipher.File(path);
                return file.exists() && file.length() > 0L;
            }

            final java.io.File file = new java.io.File(path);
            return file.isFile() && file.length() > 0L;
        }
        catch (Exception ignored)
        {
            return false;
        }
    }

    static boolean isMediaFileReadyToView(final String vfsPath, final boolean storageFramework,
                                          final String exportPath)
    {
        if (exportPath != null && !exportPath.isEmpty())
        {
            return isMediaFileReadyForPlayback(exportPath);
        }

        if (storageFramework)
        {
            return vfsPath != null && !vfsPath.isEmpty()
                    && (vfsPath.startsWith("content://") || isMediaFileReadyForPlayback(vfsPath));
        }

        return isMediaFileReadyForPlayback(vfsPath);
    }

    static long mediaFileLength(final String path)
    {
        if (path == null || path.isEmpty() || path.startsWith("content://"))
        {
            return -1L;
        }

        try
        {
            if (!isIocipherVirtualPath(path))
            {
                final java.io.File plainFile = new java.io.File(path);
                if (plainFile.isFile())
                {
                    return plainFile.length();
                }
            }

            if (MainActivity.VFS_ENCRYPT)
            {
                final info.guardianproject.iocipher.File file = new info.guardianproject.iocipher.File(path);
                return file.exists() ? file.length() : -1L;
            }

            final java.io.File file = new java.io.File(path);
            return file.isFile() ? file.length() : -1L;
        }
        catch (Exception ignored)
        {
            return -1L;
        }
    }

    static boolean isMessageMediaReady(final Message message, final String exportPath)
    {
        if (message == null)
        {
            return false;
        }

        if (isMediaFileReadyToView(message.filename_fullpath, message.storage_frame_work, exportPath))
        {
            return true;
        }

        return false;
    }

    static boolean isGroupMessageMediaReady(final GroupMessage message, final String exportPath)
    {
        if (message == null)
        {
            return false;
        }

        if (!isGroupMessageMediaOpenable(message, exportPath))
        {
            return false;
        }

        if (message.filesize > 0L)
        {
            final String mediaPath = resolveGroupMessageMediaPath(message);
            final long actualLength = mediaFileLength(mediaPath);
            if (actualLength >= 0L && actualLength < message.filesize)
            {
                return false;
            }
        }

        return true;
    }

    /** True when the on-disk file exists and can be shown (ignore in-progress byte counts). */
    static boolean isGroupMessageMediaOpenable(final GroupMessage message, final String exportPath)
    {
        if (message == null)
        {
            return false;
        }

        final String mediaPath = resolveGroupMessageMediaPath(message);
        return isMediaFileReadyToView(mediaPath, message.storage_frame_work, exportPath);
    }

    /** Resolve on-disk VFS path for a group media message (thumbnail and viewer must use the same path). */
    static String resolveGroupMessageMediaPath(final GroupMessage message)
    {
        if (message == null)
        {
            return null;
        }

        if ((message.filename_fullpath != null) && !message.filename_fullpath.isEmpty()
                && isMediaFileReadyForPlayback(message.filename_fullpath))
        {
            return message.filename_fullpath;
        }

        if ((message.path_name != null) && (message.file_name != null) && !message.file_name.isEmpty())
        {
            final String combined = message.path_name.endsWith("/")
                    ? message.path_name + message.file_name
                    : message.path_name + "/" + message.file_name;
            if (isMediaFileReadyForPlayback(combined))
            {
                return combined;
            }
        }

        return message.filename_fullpath;
    }

    static GroupMessage reloadGroupMessageFromDb(final GroupMessage message)
    {
        if ((message == null) || (message.id <= 0L) || (orma == null))
        {
            return message;
        }

        try
        {
            return (GroupMessage) orma.selectFromGroupMessage().idEq(message.id).get(0);
        }
        catch (Exception ignored)
        {
            return message;
        }
    }

    static boolean isMessageMediaDownloading(final Message message)
    {
        if (message == null)
        {
            return false;
        }

        if (message.id < 0)
        {
            return true;
        }

        if (message.TRIFA_MESSAGE_TYPE != TRIFA_MSG_FILE.value)
        {
            return false;
        }

        return !isMessageMediaReady(message, null);
    }

    static boolean isGroupMessageMediaDownloading(final GroupMessage message)
    {
        if (message == null)
        {
            return false;
        }

        if (message.id < 0)
        {
            return true;
        }

        if (message.TRIFA_MESSAGE_TYPE != TRIFA_MSG_FILE.value)
        {
            return false;
        }

        if (NgcGroupFileTransfer.shouldUseChunkedTransfer(message.filesize))
        {
            final int pct = HelperGroup.ngc_file_transfer_progress_percent(message.group_identifier,
                    message.msg_id_hash);
            if (pct >= 0 && pct < 100)
            {
                return true;
            }
        }

        return !isGroupMessageMediaReady(message, null);
    }

    static void safeRefreshAudioPlayer(final me.jagar.chatvoiceplayerlibrary.VoicePlayerView player,
                                       final String path)
    {
        if (player == null)
        {
            return;
        }

        if (!isMediaFileReadyForPlayback(path))
        {
            player.setVisibility(View.GONE);
            return;
        }

        try
        {
            player.setVisibility(View.VISIBLE);
            player.refreshPlayer(path);
            player.refreshVisualizer();
        }
        catch (Exception ignored)
        {
            player.setVisibility(View.GONE);
        }
    }

    static void safeRefreshAudioPlayer(final me.jagar.chatvoiceplayerlibrary.vVoicePlayerView player,
                                       final String path)
    {
        if (player == null)
        {
            return;
        }

        if (!isMediaFileReadyForPlayback(path))
        {
            player.setVisibility(View.GONE);
            return;
        }

        try
        {
            player.setVisibility(View.VISIBLE);
            player.refreshPlayer(path);
            player.refreshVisualizer();
        }
        catch (Exception ignored)
        {
            player.setVisibility(View.GONE);
        }
    }

    static boolean isVoiceMessage(Message message)
    {
        if (message == null)
        {
            return false;
        }
        if (isVoiceMessagePath(message.filename_fullpath))
        {
            return true;
        }
        return isVoiceMessagePath(display_name_from_message_text(message.text));
    }

    static boolean isAudioMessage(Context context, Message message)
    {
        if (isVoiceMessage(message))
        {
            return true;
        }
        final String mimeType = guess_message_file_mime_type(context, message);
        return (mimeType != null) && mimeType.startsWith("audio/");
    }

    static boolean isGroupVoiceMessage(final GroupMessage message)
    {
        if (message == null)
        {
            return false;
        }
        if (isVoiceMessagePath(message.filename_fullpath))
        {
            return true;
        }
        if (isVoiceMessagePath(message.file_name))
        {
            return true;
        }
        return isVoiceMessagePath(display_name_from_message_text(message.text));
    }

    static boolean isGroupAudioMessage(final Context context, final GroupMessage message)
    {
        if (isGroupVoiceMessage(message))
        {
            return true;
        }
        final String mimeType = guess_group_message_file_mime_type(context, message);
        return (mimeType != null) && mimeType.startsWith("audio/");
    }

    static boolean isGroupVideoMessage(final Context context, final GroupMessage message)
    {
        if (message == null)
        {
            return false;
        }
        final String mimeType = guess_group_message_file_mime_type(context, message);
        if ((mimeType != null) && mimeType.startsWith("video/"))
        {
            return true;
        }
        return HelperGroup.is_probable_video_file(message.file_name, mimeType);
    }

    static boolean isDirectVideoMessage(final Context context, final Message message)
    {
        if (message == null)
        {
            return false;
        }
        final String mimeType = guess_message_file_mime_type(context, message);
        if ((mimeType != null) && mimeType.startsWith("video/"))
        {
            return true;
        }
        return HelperGroup.is_probable_video_file(outgoingFileDisplayLabel(context, message), mimeType);
    }

    static boolean isDirectImageMessage(final Context context, final Message message)
    {
        if (message == null || isDirectVideoMessage(context, message) || isAudioMessage(context, message))
        {
            return false;
        }
        final String mimeType = guess_message_file_mime_type(context, message);
        return (mimeType != null) && mimeType.startsWith("image/");
    }

    /** Compact thumbnail bubble for outgoing image/video (no giant attachment icon). */
    static boolean bindOutgoingCompactMediaUi(final Context context, final View itemView, final Message message,
                                              final EmojiTextViewLinks textView, final ImageView imageView,
                                              final ViewGroup ft_preview_container, final ImageButton ft_preview_image,
                                              final ViewGroup ft_buttons_container,
                                              final com.daimajia.numberprogressbar.NumberProgressBar ft_progressbar,
                                              final me.jagar.chatvoiceplayerlibrary.VoicePlayerView ft_audio_player,
                                              final ImageButton button_ok, final ImageButton button_cancel,
                                              final boolean transferUiActive)
    {
        if ((context == null) || (message == null) || (itemView == null))
        {
            return false;
        }

        final boolean isVideo = isDirectVideoMessage(context, message);
        final boolean isImage = !isVideo && isDirectImageMessage(context, message);
        if (!isVideo && !isImage)
        {
            return false;
        }

        resetOutgoingFtAudioPlayer(ft_audio_player);
        textView.setVisibility(View.GONE);
        imageView.setVisibility(View.GONE);
        ft_audio_player.setVisibility(View.GONE);
        ft_progressbar.setVisibility(View.GONE);
        ft_preview_container.setVisibility(View.VISIBLE);
        ft_preview_image.setVisibility(View.VISIBLE);

        if (transferUiActive)
        {
            ft_buttons_container.setVisibility(View.GONE);
            button_ok.setVisibility(View.GONE);
            button_cancel.setVisibility(View.GONE);
        }

        ChatFileBubbleHelper.hideMediaChrome(itemView);

        if (isVideo)
        {
            ChatFileBubbleHelper.showMediaPreview(itemView, (int) dp2px(180));
            ChatMediaHelper.bindVideoPreview(context, message, null, ft_preview_image);
            final long fileSize = get_filetransfer_filesize_from_id(message.filetransfer_id);
            ChatFileBubbleHelper.bindVideoMediaChrome(context, itemView, fileSize > 0L ? fileSize : 0L);
            final View playOverlay = itemView.findViewById(R.id.ft_media_play_overlay);
            if (playOverlay != null)
            {
                playOverlay.setVisibility(transferUiActive ? View.GONE : View.VISIBLE);
            }
            final View infoBar = itemView.findViewById(R.id.ft_media_info_bar);
            if (infoBar != null)
            {
                infoBar.setVisibility(transferUiActive ? View.GONE : View.VISIBLE);
            }
        }
        else
        {
            ChatFileBubbleHelper.showMediaPreview(itemView, (int) dp2px(150));
            ChatMediaHelper.bindOutgoingImagePreview(context, message, ft_preview_image);
            ft_preview_image.setOnTouchListener(
                    ChatMediaHelper.mediaOpenTouchListener(context, message, null));
        }

        return true;
    }

    static boolean isGroupImageMessage(final Context context, final GroupMessage message)
    {
        if (message == null || isGroupVideoMessage(context, message) || isGroupAudioMessage(context, message))
        {
            return false;
        }
        final String mimeType = guess_group_message_file_mime_type(context, message);
        return (mimeType != null) && mimeType.startsWith("image/");
    }

    static boolean looksLikeInternalFtName(String name)
    {
        if ((name == null) || name.isEmpty())
        {
            return true;
        }
        if (name.contains(" bytes"))
        {
            return true;
        }
        if (isVoiceMessagePath(name))
        {
            return true;
        }
        if (name.length() >= 32 && name.matches("(?i)[0-9A-F]+"))
        {
            return true;
        }
        return (name.length() > 48) && name.contains("_");
    }

    static String basenameFromPath(String path)
    {
        if ((path == null) || path.isEmpty())
        {
            return null;
        }
        final int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return (slash >= 0) ? path.substring(slash + 1) : path;
    }

    static String outgoingFileDisplayLabel(Context context, Message message)
    {
        if (isVoiceMessage(message))
        {
            return context.getString(R.string.voice_message_label);
        }

        String name = display_name_from_message_text(message.text);
        if ((name != null) && (!name.isEmpty()) && (!looksLikeInternalFtName(name)))
        {
            return name;
        }

        name = basenameFromPath(message.filename_fullpath);
        if ((name != null) && (!name.isEmpty()) && (!looksLikeInternalFtName(name)))
        {
            return name;
        }

        return context.getString(R.string.chat_ft_generic_file);
    }

    static String resolve_attachment_display_name(final Context context, final Uri uri, final String preferredName)
    {
        String name = preferredName;
        if ((name == null) || name.isEmpty())
        {
            try
            {
                final DocumentFile documentFile = DocumentFile.fromSingleUri(context, uri);
                if (documentFile != null)
                {
                    name = documentFile.getName();
                }
            }
            catch (Exception ignored)
            {
            }
        }

        if ((name == null) || name.isEmpty())
        {
            try
            {
                name = uri.getLastPathSegment();
            }
            catch (Exception ignored)
            {
            }
        }

        if ((name == null) || name.isEmpty())
        {
            name = "file_" + System.currentTimeMillis();
        }

        return name;
    }

    static String buildOutgoingFileMessageText(Context context, String filename, long file_size)
    {
        if (isVoiceMessagePath(filename))
        {
            return context.getString(R.string.voice_message_label);
        }

        String displayName = basenameFromPath(filename);
        if ((displayName == null) || displayName.isEmpty())
        {
            displayName = filename;
        }
        if (looksLikeInternalFtName(displayName))
        {
            return context.getString(R.string.chat_ft_generic_file);
        }
        return displayName;
    }

    static String groupFileDisplayLabel(final Context context, final GroupMessage message)
    {
        if (message == null)
        {
            return context.getString(R.string.chat_ft_generic_file);
        }
        if (isGroupVoiceMessage(message))
        {
            return context.getString(R.string.voice_message_label);
        }

        String name = message.file_name;
        if ((name == null) || name.isEmpty() || looksLikeInternalFtName(name))
        {
            name = display_name_from_message_text(message.text);
        }
        if ((name == null) || name.isEmpty() || looksLikeInternalFtName(name))
        {
            name = basenameFromPath(message.filename_fullpath);
        }
        if ((name != null) && (!name.isEmpty()) && (!looksLikeInternalFtName(name)))
        {
            return name;
        }
        return context.getString(R.string.chat_ft_generic_file);
    }

    static boolean isSmallOutgoingFile(Message message)
    {
        if (message == null)
        {
            return false;
        }
        if (message.filetransfer_id == -1)
        {
            return false;
        }
        try
        {
            final long filesize = get_filetransfer_filesize_from_id(message.filetransfer_id);
            return (filesize > 0) &&
                   (filesize <= (long) AUTO_ACCEPT_FT_SMALL_FILE_MAX_SIZE_IN_MB * 1024L * 1024L);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    static String outgoingFtWaitingStatusLine(Context context, Message message)
    {
        if (isAudioMessage(context, message))
        {
            return null;
        }
        if (isSmallOutgoingFile(message))
        {
            return null;
        }
        return context.getString(R.string.chat_ft_status_waiting_friend_accept);
    }

    static void resetOutgoingFtAudioPlayer(me.jagar.chatvoiceplayerlibrary.VoicePlayerView ft_audio_player)
    {
        if (ft_audio_player == null)
        {
            return;
        }
        ft_audio_player.setVisibility(View.GONE);
        try
        {
            ft_audio_player.onPause();
        }
        catch (Exception ignored)
        {
        }
    }

    static void bindOutgoingCompactAudioUi(Context context, Message message, EmojiTextViewLinks textView,
                                           ImageView imageView, ViewGroup ft_preview_container,
                                           ImageButton ft_preview_image, ViewGroup ft_buttons_container,
                                           com.daimajia.numberprogressbar.NumberProgressBar ft_progressbar,
                                           me.jagar.chatvoiceplayerlibrary.VoicePlayerView ft_audio_player,
                                           ImageButton button_ok, ImageButton button_cancel,
                                           boolean showProgressBar, int progressPercent, boolean showCancelButton)
    {
        textView.setVisibility(View.GONE);
        imageView.setVisibility(View.GONE);

        ft_preview_image.setVisibility(View.GONE);
        ft_preview_image.setOnTouchListener(null);

        final ViewGroup.LayoutParams previewLp = ft_preview_container.getLayoutParams();
        previewLp.height = (int) dp2px(56);
        ft_preview_container.setLayoutParams(previewLp);
        ft_preview_container.setVisibility(View.VISIBLE);

        ft_audio_player.setVisibility(View.VISIBLE);
        HelperFiletransfer.safeRefreshAudioPlayer(ft_audio_player, message.filename_fullpath);

        // A voice message renders its own player — never show the inline cancel (red X) / ok buttons over it,
        // regardless of transfer state (was a big red X on outgoing voice notes to offline friends).
        button_ok.setVisibility(View.GONE);
        ft_buttons_container.setVisibility(View.GONE);
        button_cancel.setVisibility(View.GONE);

        if (showProgressBar)
        {
            ft_progressbar.setVisibility(View.VISIBLE);
            ft_progressbar.setMax(100);
            ft_progressbar.setProgress(progressPercent);
        }
        else
        {
            ft_progressbar.setVisibility(View.GONE);
        }
    }

    private static boolean should_force_auto_accept_incoming(Message message)
    {
        try
        {
            final String incoming_filename = get_filetransfer_filename_from_id(message.filetransfer_id);
            if (isVoiceMessagePath(incoming_filename))
            {
                return true;
            }

            final String mimeType = guess_mime_type_from_filename(incoming_filename);
            if ((mimeType != null) && mimeType.startsWith("audio/"))
            {
                return true;
            }

            final long filesize = get_filetransfer_filesize_from_id(message.filetransfer_id);
            return (filesize > 0) &&
                   (filesize <= (long) AUTO_ACCEPT_FT_SMALL_FILE_MAX_SIZE_IN_MB * 1024L * 1024L);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
        return false;
    }

    private static boolean accept_paused_incoming_filetransfer(Message message)
    {
        if (get_filetransfer_state_from_id(message.filetransfer_id) != TOX_FILE_CONTROL_PAUSE.value)
        {
            return false;
        }

        set_filetransfer_accepted_from_id(message.filetransfer_id);
        set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_RESUME.value);
        set_message_accepted_from_id(message.id);
        set_message_state_from_id(message.id, TOX_FILE_CONTROL_RESUME.value);
        final long friendNum = tox_friend_by_public_key__wrapper(message.tox_friendpubkey);
        final long fileNum = get_filetransfer_filenum_from_id(message.filetransfer_id);
        FileTransferDebug.logFileControlResume(friendNum, fileNum);
        tox_file_control(friendNum, fileNum, TOX_FILE_CONTROL_RESUME.value);
        update_single_message_from_messge_id(message.id, true);
        return true;
    }

    static long get_vfs_file_length(String path_name, String file_name)
    {
        try
        {
            if (MainActivity.VFS_ENCRYPT)
            {
                info.guardianproject.iocipher.File f = new info.guardianproject.iocipher.File(path_name + "/" + file_name);
                return f.length();
            }
            else
            {
                java.io.File f = new java.io.File(path_name + "/" + file_name);
                return f.length();
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "get_vfs_file_length:EE:" + e.getMessage());
            return -1L;
        }
    }

    static boolean verify_incoming_file_complete(Filetransfer f)
    {
        if (f == null)
        {
            return false;
        }

        final long actual = get_vfs_file_length(f.path_name, f.file_name);
        if (actual < 0)
        {
            return false;
        }

        return actual >= f.filesize;
    }

    static boolean is_message_file_complete(Message message)
    {
        if (message == null)
        {
            return false;
        }

        if (message.filedb_id == -1)
        {
            return isMediaFileReadyForPlayback(message.filename_fullpath);
        }

        try
        {
            final FileDB file_ = (FileDB) orma.selectFromFileDB().idEq(message.filedb_id).get(0);
            if ((file_.filesize <= 0) || (file_.path_name == null) || (file_.file_name == null))
            {
                return isMediaFileReadyForPlayback(message.filename_fullpath);
            }

            long actual = get_vfs_file_length(file_.path_name, file_.file_name);
            if (actual < 0 && message.filename_fullpath != null && !message.filename_fullpath.isEmpty())
            {
                actual = mediaFileLength(message.filename_fullpath);
            }
            if (actual >= 0 && actual >= file_.filesize)
            {
                return true;
            }

            return isMediaFileReadyForPlayback(message.filename_fullpath)
                    && mediaFileLength(message.filename_fullpath) >= file_.filesize;
        }
        catch (Exception e)
        {
            Log.i(TAG, "is_message_file_complete:EE:" + e.getMessage());
            return isMediaFileReadyForPlayback(message.filename_fullpath);
        }
    }

    static boolean is_dm_sender_online(final String friendPubkey)
    {
        if (friendPubkey == null)
        {
            return false;
        }
        final long friendNum = tox_friend_by_public_key__wrapper(friendPubkey);
        if (friendNum < 0)
        {
            return false;
        }
        return is_friend_online_real(friendNum) != 0;
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, Long> dm_file_auto_retry_ts =
            new java.util.concurrent.ConcurrentHashMap<>();

    private static final long DM_FILE_AUTO_RETRY_COOLDOWN_MS = 15_000L;

    static void schedule_auto_retry_dm_incoming_file(final Message message)
    {
        if ((message == null) || (message.tox_friendpubkey == null))
        {
            return;
        }
        if (!is_dm_sender_online(message.tox_friendpubkey))
        {
            return;
        }
        if (is_message_file_complete(message) || isMessageMediaReady(message, null))
        {
            return;
        }

        final String key = message.tox_friendpubkey + "|" + message.id;
        final long now = System.currentTimeMillis();
        final Long last = dm_file_auto_retry_ts.get(key);
        if ((last != null) && ((now - last) < DM_FILE_AUTO_RETRY_COOLDOWN_MS))
        {
            return;
        }
        dm_file_auto_retry_ts.put(key, now);

        if (MainActivity.main_handler_s != null)
        {
            MainActivity.main_handler_s.post(() -> retry_incoming_filetransfer(message));
        }
    }

    static void retry_pending_incoming_files_for_friend(final String friendPubkey)
    {
        if ((friendPubkey == null) || (orma == null))
        {
            return;
        }

        try
        {
            final List<Message> pending = orma.selectFromMessage().
                    tox_friendpubkeyEq(friendPubkey).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    directionEq(0).
                    toList();

            for (Message m : pending)
            {
                if (!is_message_file_complete(m) && !isMessageMediaReady(m, null))
                {
                    retry_incoming_filetransfer(m);
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "retry_pending_incoming_files_for_friend:EE:" + e.getMessage());
        }
    }

    public static void resume_stalled_incoming_filetransfers()
    {
        try
        {
            final List<Filetransfer> transfers = orma.selectFromFiletransfer().
                    directionEq(TRIFA_FT_DIRECTION_INCOMING.value).
                    stateNotEq(TOX_FILE_CONTROL_CANCEL.value).
                    toList();

            for (Filetransfer f : transfers)
            {
                if ((f.filesize <= 0) || (f.current_position >= f.filesize))
                {
                    continue;
                }

                if ((f.kind != TOX_FILE_KIND_DATA.value) && (f.kind != TOX_FILE_KIND_FTV2.value))
                {
                    continue;
                }

                try
                {
                    final long friend_number = tox_friend_by_public_key__wrapper(f.tox_public_key_string);
                    if (friend_number < 0)
                    {
                        continue;
                    }

                    final long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(f.id,
                                                                                                        friend_number);
                    if (msg_id <= 0)
                    {
                        continue;
                    }

                    if ((!f.ft_accepted) || (f.state == TOX_FILE_CONTROL_PAUSE.value))
                    {
                        final Message m = (Message) orma.selectFromMessage().idEq(msg_id).get(0);
                        if (accept_paused_incoming_filetransfer(m))
                        {
                            continue;
                        }
                    }

                    if ((f.state != TOX_FILE_CONTROL_PAUSE.value) && (f.state != TOX_FILE_CONTROL_RESUME.value))
                    {
                        continue;
                    }

                    tox_file_control(friend_number, f.file_number, TOX_FILE_CONTROL_RESUME.value);
                    if (f.state == TOX_FILE_CONTROL_PAUSE.value)
                    {
                        set_filetransfer_state_from_id(f.id, TOX_FILE_CONTROL_RESUME.value);
                        set_message_state_from_id(msg_id, TOX_FILE_CONTROL_RESUME.value);
                        update_single_message_from_messge_id(msg_id, false);
                    }
                }
                catch (Exception e)
                {
                    Log.i(TAG, "resume_stalled_incoming_filetransfers:ft=" + f.id + " EE:" + e.getMessage());
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "resume_stalled_incoming_filetransfers:EE:" + e.getMessage());
        }
    }

    static void fail_incoming_filetransfer(Filetransfer f, long friend_number, long file_number, String reason)
    {
        if (f == null)
        {
            return;
        }

        Log.w(TAG, "fail_incoming_filetransfer:" + f.file_name + " reason=" + reason);

        try
        {
            tox_file_control(friend_number, file_number, TOX_FILE_CONTROL_CANCEL.value);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            remove_vfs_ft_from_cache(f);
            delete_filetransfer_tmpfile(friend_number, file_number);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        try
        {
            long msg_id = HelperMessage.get_message_id_from_filetransfer_id_and_friendnum(f.id, friend_number);
            set_filetransfer_state_from_id(f.id, TOX_FILE_CONTROL_CANCEL.value);
            if (msg_id > -1)
            {
                HelperMessage.set_message_state_from_id(msg_id, TOX_FILE_CONTROL_CANCEL.value);
                HelperMessage.set_message_filedb_from_id(msg_id, -1);
                update_single_message_from_messge_id(msg_id, true);
            }
            delete_filetransfers_from_friendnum_and_filenum(friend_number, file_number);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }

    static void cancel_incoming_filetransfer(final Message message)
    {
        if (message == null || message.filetransfer_id <= 0)
        {
            return;
        }
        try
        {
            tox_file_control(tox_friend_by_public_key__wrapper(message.tox_friendpubkey),
                             get_filetransfer_filenum_from_id(message.filetransfer_id), TOX_FILE_CONTROL_CANCEL.value);
            set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_CANCEL.value);
            set_message_state_from_id(message.id, TOX_FILE_CONTROL_CANCEL.value);
            remove_vfs_ft_from_cache(message);
            update_single_message_from_messge_id(message.id, true);
        }
        catch (Exception ignored)
        {
        }
    }

    static void cancel_outgoing_filetransfer(final Message message)
    {
        if (message == null || message.filetransfer_id <= 0)
        {
            return;
        }
        try
        {
            tox_file_control(tox_friend_by_public_key__wrapper(message.tox_friendpubkey),
                             get_filetransfer_filenum_from_id(message.filetransfer_id), TOX_FILE_CONTROL_CANCEL.value);
            set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_CANCEL.value);
            set_message_state_from_id(message.id, TOX_FILE_CONTROL_CANCEL.value);
            remove_ft_from_cache(message);
            update_single_message_from_messge_id(message.id, true);
        }
        catch (Exception ignored)
        {
        }
    }

    static void retry_incoming_filetransfer(final Message message)
    {
        if (message == null)
        {
            return;
        }
        try
        {
            if (accept_paused_incoming_filetransfer(message))
            {
                update_single_message_from_messge_id(message.id, true);
                return;
            }
            if (message.filetransfer_id > 0)
            {
                tox_file_control(tox_friend_by_public_key__wrapper(message.tox_friendpubkey),
                                 get_filetransfer_filenum_from_id(message.filetransfer_id), TOX_FILE_CONTROL_RESUME.value);
                set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_RESUME.value);
                set_message_state_from_id(message.id, TOX_FILE_CONTROL_RESUME.value);
                update_single_message_from_messge_id(message.id, true);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static void retry_outgoing_filetransfer(final Message message)
    {
        if (message == null)
        {
            return;
        }
        try
        {
            orma.updateMessage().idEq(message.id).
                    state(TOX_FILE_CONTROL_PAUSE.value).
                    ft_outgoing_started(false).
                    ft_outgoing_queued(true).
                    ft_accepted(false).
                    filedb_id(-1).
                    execute();
            queue_and_try_send_outgoing_file(message.id, true);
        }
        catch (Exception ignored)
        {
        }
    }
}
