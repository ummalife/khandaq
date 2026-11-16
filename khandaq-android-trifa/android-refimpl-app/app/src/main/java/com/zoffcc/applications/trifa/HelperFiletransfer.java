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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
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
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;

import androidx.core.content.FileProvider;

import static android.webkit.MimeTypeMap.getFileExtensionFromUrl;
import static com.zoffcc.applications.trifa.HelperFriend.friend_call_push_url;
import static com.zoffcc.applications.trifa.HelperFriend.is_friend_online_real;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.dp2px;
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
import static com.zoffcc.applications.trifa.TRIFAGlobals.AUTO_ACCEPT_FT_SMALL_FILE_MAX_SIZE_IN_MB;
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

    // KHANDAQ (Figma "Загрузка вложений" / #120): global attachment auto-download gate.
    // 0=Никогда (never), 1=Только Wi-Fi (wifi-only), 2=Всегда (always = default, unchanged behavior).
    // Applies to BOTH the 1:1 (this file) and the NGC group path (HelperGroup.group_custom_packet_cb).
    public static boolean attachment_auto_download_allowed()
    {
        final int mode = MainActivity.PREF__attachment_download_mode;
        if (mode == 0)
        {
            return false;
        }
        if (mode == 1)
        {
            return is_on_wifi();
        }
        return true;
    }

    private static boolean is_on_wifi()
    {
        try
        {
            final Context ctx = MainActivity.context_s;
            if (ctx == null)
            {
                return false;
            }
            final ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null)
            {
                return false;
            }
            final Network n = cm.getActiveNetwork();
            if (n == null)
            {
                return false;
            }
            final NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            return (caps != null) && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        }
        catch (Exception e)
        {
            return false;
        }
    }

    public static boolean check_auto_accept_incoming_filetransfer(Message message)
    {
        try
        {
            if (get_filetransfer_state_from_id(message.filetransfer_id) != TOX_FILE_CONTROL_PAUSE.value)
            {
                return false;
            }

            if (!auto_download_allows(message))
            {
                return false;
            }

            return accept_paused_incoming_filetransfer(message);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }

        return false;
    }

    /**
     * KHANDAQ (0.2.57, SPEC-network part 3): whether a 1:1 file of theirs downloads by itself now, by the switches
     * of Settings and the attachment download mode (AutoDownloadPreference). A file downloads by itself only so;
     * the switches used to be passed over (ensure_incoming_dm_file_accepted accepted every file), so turning
     * them off did nothing.
     */
    static boolean auto_download_allows(final Message message)
    {
        if ((message == null) || (message.filetransfer_id <= 0))
        {
            return false;
        }
        final String name = get_filetransfer_filename_from_id(message.filetransfer_id);
        final String mime = (name == null) ? null : guess_mime_type_from_filename(name.toLowerCase(Locale.ROOT));
        final boolean voice = isVoiceMessagePath(name) || ((mime != null) && mime.startsWith("audio/"));
        final int mode = MainActivity.PREF__attachment_download_mode;
        return AutoDownloadPreference.allows(mode, (mode == 1) && is_on_wifi(), PREF__auto_accept_image,
                                             PREF__auto_accept_video, PREF__auto_accept_all_upto,
                                             get_filetransfer_filesize_from_id(message.filetransfer_id), mime, voice);
    }

    /** After a new offer of theirs: the same rule as check_auto_accept_incoming_filetransfer, kept for its callers. */
    static void ensure_incoming_dm_file_accepted(final Message message)
    {
        if (message == null || message.filetransfer_id <= 0)
        {
            return;
        }

        try
        {
            check_auto_accept_incoming_filetransfer(message);
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

    /**
     * The name an incoming group file is STORED under, as opposed to the one shown to the user.
     *
     * KHANDAQ (security): a peer picks the filename in a group transfer and it arrives unfiltered —
     * resolveDisplayFilename() reads it out of the packet and only trims whitespace. That name used to
     * be written to GroupMessage.file_name, which several call sites concatenate into a path:
     * send_ngch_syncfile() opens path_name + "/" + file_name and sends the bytes to a peer, and the
     * export action passes it as both source and destination of a copy. A name containing ../ therefore
     * escaped the group's directory in both directions.
     *
     * The assembly already carries the sanitised form; fall back to sanitising in place if it does not.
     * Display is unaffected — the original name stays in the message text.
     */
    static String sanitised_stored_filename(final NgcGroupFileTransfer.IncomingAssembly asm,
                                            final String display_name)
    {
        if ((asm != null) && (asm.filename != null) && !asm.filename.isEmpty())
        {
            return asm.filename;
        }
        if ((display_name == null) || display_name.isEmpty())
        {
            return "file.bin";
        }
        return TrifaSetPatternActivity.filter_out_specials_from_filepath(display_name);
    }

    /**
     * Join a directory and a stored filename, refusing anything that would leave the directory.
     *
     * KHANDAQ (security, defence in depth): sanitising at the point of storage is the real fix, but rows
     * written by earlier versions are still in the database with a raw name in them, and a future caller
     * may build a path from a name nobody filtered. Callers that read a file and hand its contents to
     * someone else check here first.
     *
     * Returns null when the result is not inside {@code dir}.
     */
    static String path_inside_dir_or_null(final String dir, final String file_name)
    {
        if ((dir == null) || (file_name == null) || file_name.isEmpty())
        {
            return null;
        }
        // No separator may appear in a stored name, and no dot-reference either.
        if (file_name.contains("/") || file_name.contains("\\")
            || file_name.equals(".") || file_name.equals(".."))
        {
            return null;
        }
        final String base = dir.endsWith("/") ? dir : dir + "/";
        return base + file_name;
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
                    // KHANDAQ (0.2.57): a receiver that lost the transfer in a restart is offered it again.
                    if (outgoing_kill_means_lost_transfer(f))
                    {
                        try_start_next_queued_outgoing_file(f.tox_public_key_string);
                        return;
                    }
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

    static synchronized void set_all_filetransfers_inactive()
    {
        try
        {
            // KHANDAQ (0.2.57, SPEC-network 1.1, 1.5): toxcore starts with no transfers, and the 1:1 files under way
            // in the last run are not cancelled for it any more. Ours go back to the queue and are offered again
            // with the same file id; theirs keep their bytes in the encrypted store and wait for the sender's next
            // offer, which goes on from those bytes.
            final List<Filetransfer> rows = transfers_left_from_last_run();
            final List<Filetransfer> kept = new ArrayList<>();
            final List<Message> kept_messages = new ArrayList<>();
            for (Filetransfer f : rows)
            {
                final Message m = message_of_filetransfer(f);
                if (resumable_after_restart(f, m))
                {
                    kept.add(f);
                    kept_messages.add(m);
                }
                else if ((f.file_number != -1) && !((m != null) && incoming_cancelled_by_person(m, f)))
                {
                    // A file of theirs the person cancelled keeps its cancelled row, without its bytes: a later
                    // offer of it then waits for a tap (resume_incoming_offer).
                    // Log.i(TAG, "set_all_filetransfers_inactive:cancel:id=" + f.tox_file_id_hex + " filename=" + f.file_name);
                    cancel_filetransfer_f((Filetransfer) f);
                }
            }

            orma.updateFiletransfer().
                    file_number(-1).
                    state(TOX_FILE_CONTROL_CANCEL.value).
                    execute();

            for (int i = 0; i < kept.size(); i++)
            {
                final Filetransfer f = kept.get(i);
                final Message m = kept_messages.get(i);
                if (f.direction == TRIFA_FT_DIRECTION_OUTGOING.value)
                {
                    requeue_cut_off_outgoing(f, m.id, "start");
                }
                else
                {
                    final boolean accepted = m.ft_accepted || f.ft_accepted;
                    orma.updateFiletransfer().idEq(f.id).
                            state(accepted ? TOX_FILE_CONTROL_RESUME.value : TOX_FILE_CONTROL_PAUSE.value).
                            execute();
                }
            }
            if (!kept.isEmpty())
            {
                NetworkDiagnosticsLog.log("file_resume", "set_all_filetransfers_inactive:kept=" + kept.size());
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            Log.i(TAG, "set_all_filetransfers_inactive:EE:" + e.getMessage());
        }
    }

    /** The rows toxcore had a transfer for in the last run, and the files that waited for the connection (no number). */
    private static List<Filetransfer> transfers_left_from_last_run()
    {
        final List<Filetransfer> rows = new ArrayList<>();
        rows.addAll(orma.selectFromFiletransfer().file_numberNotEq(-1).toList());
        rows.addAll(orma.selectFromFiletransfer().file_numberEq(-1).stateNotEq(TOX_FILE_CONTROL_CANCEL.value).toList());
        return rows;
    }

    /**
     * KHANDAQ (0.2.57, SPEC-network 1.5): the files of theirs under way that go on after this start, the ones
     * set_all_filetransfers_inactive keeps, as FileResume.tempFileKey gives their paths. Their bytes are in the
     * temp dir, and the start's cleanup of it (HelperGeneric.cleanup_temp_dirs) leaves them: the sender's next
     * offer goes on from them. The same rows either side of set_all_filetransfers_inactive, which only cancels
     * the others; both are synchronized, since that one cancels every row for a moment before it gives the kept
     * ones their state back, and the cleanup runs on its own thread. Null when the rows could not be read.
     */
    static synchronized Set<String> incoming_files_kept_after_restart()
    {
        try
        {
            final Set<String> kept = new HashSet<>();
            for (Filetransfer f : transfers_left_from_last_run())
            {
                if ((f.direction == TRIFA_FT_DIRECTION_INCOMING.value) && (f.path_name != null)
                    && (f.file_name != null) && resumable_after_restart(f, message_of_filetransfer(f)))
                {
                    kept.add(FileResume.tempFileKey(f.path_name + "/" + f.file_name));
                }
            }
            return kept;
        }
        catch (Exception e)
        {
            Log.w(TAG, "incoming_files_kept_after_restart:EE:" + e.getMessage());
            return null;
        }
    }

    /** The 1:1 file entry a transfer row belongs to, or null. */
    private static Message message_of_filetransfer(final Filetransfer f)
    {
        try
        {
            final List<Message> rows = orma.selectFromMessage().filetransfer_idEq(f.id).toList();
            if ((rows == null) || rows.isEmpty())
            {
                return null;
            }
            final Message m = rows.get(0);
            final boolean outgoing = f.direction == TRIFA_FT_DIRECTION_OUTGOING.value;
            if ((m.TRIFA_MESSAGE_TYPE != TRIFA_MSG_FILE.value) || (outgoing != (m.direction == 1))
                || (m.tox_friendpubkey == null) || !m.tox_friendpubkey.equals(f.tox_public_key_string))
            {
                return null;
            }
            return m;
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /** A 1:1 file under way that goes on after the app starts again (set_all_filetransfers_inactive). */
    private static boolean resumable_after_restart(final Filetransfer f, final Message m)
    {
        return (m != null) && ((f.kind == TOX_FILE_KIND_DATA.value) || (f.kind == TOX_FILE_KIND_FTV2.value))
               && (f.filesize > 0) && (m.filedb_id == -1) && (m.state != TOX_FILE_CONTROL_CANCEL.value)
               && !m.ft_outgoing_preparing
               && ((f.state != TOX_FILE_CONTROL_CANCEL.value) || (f.file_number == -1));
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
            // KHANDAQ (0.2.57): path and name were swapped, so no stream of the file was ever found and closed.
            String path_name = get_filetransfer_path_name_from_id(m.filetransfer_id);
            String file_name = get_filetransfer_filename_from_id(m.filetransfer_id);
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
            // KHANDAQ (0.2.56): only a complete copy is offered; the copy queues its file once it is complete.
            if (!OutgoingFilePreparation.isReadyToOffer(m.ft_outgoing_preparing,
                                                        OutgoingFilePreparation.ACTIVE.isRunning(m.id)))
            {
                Log.i(TAG, "start_outgoing_ft:copy not complete mid=" + m.id);
                return;
            }

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

            // KHANDAQ (0.2.57): Retry after the receiver killed the transfer, or the cancel of an older build at
            // start, left the message without its transfer row; it is made again from the copy the message names.
            final Filetransfer ft = outgoing_filetransfer_for_offer(m);
            if (ft == null)
            {
                Log.i(TAG, "start_outgoing_ft:no transfer row and no copy mid=" + m.id);
                return;
            }

            Log.i(TAG, "MM2MM:8:ft.filesize=" + ft.filesize + " ftid=" + ft.id + " ft.mid=" + ft.message_id + " mid=" +
                       m.id);

            // ------ DEBUG ------
            // Log.i(TAG, "MM2MM:8a:ft full=" + ft);
            // ------ DEBUG ------

            ByteBuffer file_id_buffer = ByteBuffer.allocateDirect(TOX_FILE_ID_LENGTH);

            // KHANDAQ (0.2.57, SPEC-network 1.1): one Tox file id for the file's whole life. The first offer makes
            // it and stores it as the message's anchor (reactions, deletes, read receipts); every later offer (the
            // friend back online, a restart, the stall checks, Retry) passes the same id, so that the receiver
            // knows the file and goes on from the bytes it holds.
            final String earlier_id_hex = earlier_outgoing_file_id(m, ft);
            final byte[] earlier_id = FileResume.fileIdBytes(earlier_id_hex);
            if (earlier_id != null)
            {
                file_id_buffer.put(earlier_id);
                file_id_buffer.rewind();
            }
            else
            {
                MainActivity.tox_messagev3_get_new_message_id(file_id_buffer);
            }

            final String file_id_buffer_hex = HelperGeneric.bytesToHex(file_id_buffer.array(),
                                                                       file_id_buffer.arrayOffset(),
                                                                       file_id_buffer.limit()).toUpperCase();
            Log.i(TAG, "TOX_FILE_ID_LENGTH=" + TOX_FILE_ID_LENGTH + " file_id_buffer_hex=" + file_id_buffer_hex);
            if (earlier_id != null)
            {
                NetworkDiagnosticsLog.log("file_resume", "start_outgoing_ft:offered again mid=" + m.id
                                                         + " ftid=" + ft.id + " id=" + file_id_buffer_hex);
            }
            ft.tox_file_id_hex = file_id_buffer_hex;
            if (!file_id_buffer_hex.equals(m.ft_id_anchor_hex))
            {
                // KHANDAQ (#192): durable reaction anchor for this 1:1 file, it survives FT-row deletion.
                m.ft_id_anchor_hex = file_id_buffer_hex;
                orma.updateMessage().idEq(m.id).ft_id_anchor_hex(file_id_buffer_hex).execute();
            }
            // KHANDAQ (0.2.57): a row offered before (cut off, or cancelled and sent again) is a new transfer of
            // toxcore: not accepted yet, and not cancelled, since chunk requests skip a cancelled row. Its
            // position stays: it is the progress shown until the receiver says where to go on.
            ft.state = TOX_FILE_CONTROL_PAUSE.value;
            ft.ft_accepted = false;

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
                orma.updateFiletransfer().idEq(ft.id).ft_accepted(false).execute();
                // KHANDAQ (0.2.57): the lookups by friend and file number take the newest row with the number, and
                // a row offered again can be older than one a cut left with it.
                unbind_other_filetransfers(ft.tox_public_key_string, file_number, ft.id);
                outgoing_offered_at.put(m.id, System.currentTimeMillis());
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
        friend_online_since.put(pubkey, System.currentTimeMillis());
        FileTransferDebug.logPeerConnectionStatus(tox_friend_by_public_key__wrapper(pubkey));
        try_start_next_queued_outgoing_file(pubkey);
        TrifaToxService.wakeup_tox_thread();
        // KHANDAQ (0.2.57, SPEC-network 1.7): a file of theirs the cut stopped and that its sender does not offer
        // again reads as stopped once the wait is over; nothing else draws its entry again then.
        final Handler handler = MainActivity.main_handler_s != null ? MainActivity.main_handler_s
                                                                    : new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> redraw_incoming_files_waiting_for(pubkey),
                            FileResume.INCOMING_WAIT_AFTER_ONLINE_MS + 1000L);
    }

    // ---- KHANDAQ (0.2.57, SPEC-network part 1): a 1:1 file goes on after a network change ----

    /** When each friend last came online, while online (the lost transfer rule, the wait of a file of theirs). */
    private static final ConcurrentHashMap<String, Long> friend_online_since = new ConcurrentHashMap<>();
    /** When this process last offered each message's file. */
    private static final ConcurrentHashMap<Long, Long> outgoing_offered_at = new ConcurrentHashMap<>();
    /** The connection (its online time) in which a message was offered again after the receiver lost it. */
    private static final ConcurrentHashMap<Long, Long> outgoing_offered_again_after_loss = new ConcurrentHashMap<>();
    /** Transfers of a file that is whole here already, by friend and file number: their bytes are not written. */
    private static final Set<String> incoming_already_here = ConcurrentHashMap.newKeySet();
    /** When the person asked again for a file of theirs that no transfer carries (retry_incoming_filetransfer). */
    private static final ConcurrentHashMap<Long, Long> incoming_wait_restarted_at = new ConcurrentHashMap<>();

    /** How long this friend has been online again, or -1 while offline or not known. */
    static long friend_online_for_ms(final String pubkey)
    {
        final Long since = (pubkey == null) ? null : friend_online_since.get(pubkey);
        return (since == null) ? -1L : Math.max(0L, System.currentTimeMillis() - since);
    }

    private static String already_here_key(final String pubkey, final long file_number)
    {
        return pubkey + ":" + file_number;
    }

    /**
     * The friend went offline (MainActivity, friend connection). toxcore broke their DATA transfers without a
     * word (break_files) and keeps their FTV2 ones. Ours go back to the queue with their progress and are offered
     * again, with the same file id, once the friend is back; theirs keep their bytes on disk and wait for the
     * sender's next offer, which goes on from those bytes (SPEC-network 1.2, 1.5).
     */
    static void on_friend_connection_lost(final String pubkey)
    {
        if ((pubkey == null) || pubkey.isEmpty() || (orma == null))
        {
            return;
        }
        friend_online_since.remove(pubkey);
        final String prefix = pubkey + ":";
        incoming_already_here.removeIf(key -> key.startsWith(prefix));
        try
        {
            final List<Filetransfer> bound = orma.selectFromFiletransfer().
                    tox_public_key_stringEq(pubkey).
                    file_numberNotEq(-1).
                    stateNotEq(TOX_FILE_CONTROL_CANCEL.value).
                    toList();
            if (bound == null)
            {
                return;
            }
            for (Filetransfer f : bound)
            {
                if ((f.kind != TOX_FILE_KIND_DATA.value) && (f.kind != TOX_FILE_KIND_FTV2.value))
                {
                    continue;
                }
                final long mid = message_id_of_filetransfer(f);
                if (f.direction == TRIFA_FT_DIRECTION_INCOMING.value)
                {
                    // The bytes so far are on disk now: they are where the next offer goes on from.
                    HelperGeneric.flush_close_vfs_write_cache(f.path_name + "/" + f.file_name);
                    if (f.kind == TOX_FILE_KIND_DATA.value)
                    {
                        orma.updateFiletransfer().idEq(f.id).file_number(-1).execute();
                    }
                    NetworkDiagnosticsLog.log("file_resume", "on_friend_connection_lost:incoming waits ftid=" + f.id
                                                             + " kind=" + f.kind + " pos=" + f.current_position + "/"
                                                             + f.filesize);
                }
                else if (f.kind == TOX_FILE_KIND_DATA.value)
                {
                    requeue_cut_off_outgoing(f, mid, "friend offline");
                }
                if (mid > 0)
                {
                    update_single_message_from_messge_id(mid, true);
                }
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "on_friend_connection_lost:EE:" + e.getMessage());
        }
    }

    /** Ours, cut off: back to the queue, its bytes sent kept as its progress, to be offered again. */
    private static void requeue_cut_off_outgoing(final Filetransfer f, final long mid, final String why)
    {
        orma.updateFiletransfer().idEq(f.id).
                file_number(-1).
                state(TOX_FILE_CONTROL_PAUSE.value).
                ft_accepted(false).
                ft_outgoing_started(false).
                execute();
        remove_ft_from_cache(f);
        if (mid > 0)
        {
            orma.updateMessage().idEq(mid).
                    state(TOX_FILE_CONTROL_PAUSE.value).
                    ft_accepted(false).
                    ft_outgoing_started(false).
                    ft_outgoing_queued(true).
                    execute();
        }
        NetworkDiagnosticsLog.log("file_resume", "requeue_cut_off_outgoing:" + why + " ftid=" + f.id + " mid=" + mid
                                                 + " pos=" + f.current_position + "/" + f.filesize);
    }

    private static long message_id_of_filetransfer(final Filetransfer f)
    {
        if (f.message_id > 0)
        {
            return f.message_id;
        }
        try
        {
            final List<Message> rows = orma.selectFromMessage().filetransfer_idEq(f.id).toList();
            return ((rows == null) || rows.isEmpty()) ? -1L : rows.get(0).id;
        }
        catch (Exception e)
        {
            return -1L;
        }
    }

    /**
     * The receiver killed our transfer. Right after the friend came back, for a transfer offered before that, it
     * means its app started again while toxcore kept our FTV2 transfer, and its new toxcore answered our next data
     * with a kill (FileResume.LOST_TRANSFER_WINDOW_MS): the file goes back to the queue and is offered again, once.
     */
    private static boolean outgoing_kill_means_lost_transfer(final Filetransfer f)
    {
        final long mid = message_id_of_filetransfer(f);
        if (mid <= 0)
        {
            return false;
        }
        final Long since = friend_online_since.get(f.tox_public_key_string);
        final Long offered = outgoing_offered_at.get(mid);
        final Long again = outgoing_offered_again_after_loss.get(mid);
        final long since_ms = (since == null) ? 0L : since;
        if (!FileResume.killMeansLostTransfer(System.currentTimeMillis(), since_ms, (offered == null) ? 0L : offered,
                                              (again != null) && (again == since_ms)))
        {
            return false;
        }
        outgoing_offered_again_after_loss.put(mid, since_ms);
        requeue_cut_off_outgoing(f, mid, "lost by the receiver");
        update_single_message_from_messge_id(mid, true);
        return true;
    }

    /** Takes the number off every other row of this friend that has it; see start_outgoing_ft. */
    private static void unbind_other_filetransfers(final String pubkey, final long file_number, final long keep_id)
    {
        try
        {
            orma.updateFiletransfer().
                    tox_public_key_stringEq(pubkey).
                    file_numberEq(file_number).
                    idNotEq(keep_id).
                    file_number(-1).
                    execute();
        }
        catch (Exception e)
        {
            Log.w(TAG, "unbind_other_filetransfers:EE:" + e.getMessage());
        }
    }

    /**
     * The row a message's file is offered from. Retry after the receiver's cancel, or after an older build's
     * start, finds the message without one: it is made again from the copy the message names.
     */
    private static Filetransfer outgoing_filetransfer_for_offer(final Message m)
    {
        if (m.filetransfer_id > 0)
        {
            final List<Filetransfer> rows = orma.selectFromFiletransfer().idEq(m.filetransfer_id).toList();
            if ((rows != null) && !rows.isEmpty())
            {
                return rows.get(0);
            }
        }
        if ((m.filename_fullpath == null) || m.filename_fullpath.isEmpty())
        {
            return null;
        }
        final File copy = new File(m.filename_fullpath);
        if (!copy.isFile() || (copy.length() <= 0) || (copy.getParentFile() == null))
        {
            return null;
        }
        final Filetransfer f = new Filetransfer();
        f.tox_public_key_string = m.tox_friendpubkey;
        f.direction = TRIFA_FT_DIRECTION_OUTGOING.value;
        f.file_number = -1;
        f.kind = TOX_FILE_KIND_DATA.value;
        f.state = TOX_FILE_CONTROL_PAUSE.value;
        f.path_name = copy.getParentFile().getAbsolutePath();
        f.file_name = copy.getName();
        f.filesize = copy.length();
        f.ft_accepted = false;
        f.ft_outgoing_started = false;
        f.current_position = 0;
        f.storage_frame_work = false;
        f.message_id = m.id;
        f.tox_file_id_hex = m.ft_id_anchor_hex;
        f.id = insert_into_filetransfer_db(f);
        if (f.id <= 0)
        {
            return null;
        }
        m.filetransfer_id = f.id;
        orma.updateMessage().idEq(m.id).filetransfer_id(f.id).execute();
        NetworkDiagnosticsLog.log("file_resume", "outgoing_filetransfer_for_offer:made again mid=" + m.id
                                                 + " ftid=" + f.id);
        return f;
    }

    /** The Tox file id an earlier offer gave this message's file, or null before its first offer. */
    private static String earlier_outgoing_file_id(final Message m, final Filetransfer ft)
    {
        final String anchor = FileResume.reusableFileId(m.ft_id_anchor_hex);
        return (anchor != null) ? anchor : FileResume.reusableFileId(ft.tox_file_id_hex);
    }

    /**
     * An incoming 1:1 offer (MainActivity, file_recv, DATA and FTV2). When an entry of this friend has the same
     * file id (in either case) and size, no new entry comes (SPEC-network 1.4): a whole file there takes the
     * last byte again, silently, so that both ends finish; a partial one binds this transfer and tells toxcore
     * where to go on before it accepts, or waits for a tap as the rule says. Returns false for a new entry.
     */
    static boolean resume_incoming_offer(final long friend_number, final long file_number, final int kind,
                                         final long file_size, final String offered_id_hex, final String offered_name)
    {
        if (orma == null)
        {
            return false;
        }
        try
        {
            final String pubkey = HelperFriend.tox_friend_get_public_key__wrapper(friend_number);
            // toxcore gives a number to a new offer only, so a row of this friend that still has it is stale; the
            // lookups by friend and file number would take it for this transfer.
            unbind_other_filetransfers(pubkey, file_number, -1L);
            final String id = FileResume.reusableFileId(offered_id_hex);
            if (id == null)
            {
                return false;
            }
            final Message m = find_incoming_file_message(pubkey, id, file_size);
            if (m == null)
            {
                return false;
            }

            final Filetransfer earlier = transfer_row(m.filetransfer_id);
            if (m.filedb_id != -1)
            {
                final FileResume.Decision d = FileResume.decide(true, true, -1L, file_size);
                incoming_already_here.add(already_here_key(pubkey, file_number));
                final int seek = (d.seekTo >= 0) ? MainActivity.tox_file_seek(friend_number, file_number, d.seekTo) : 0;
                final int accept = tox_file_control(friend_number, file_number, TOX_FILE_CONTROL_RESUME.value);
                NetworkDiagnosticsLog.log("file_resume", "resume_incoming_offer:already here mid=" + m.id + " " + d
                                                         + " seek=" + seek + " accept=" + accept);
                return true;
            }

            final boolean refused = incoming_cancelled_by_person(m, earlier);
            final boolean accepted_before = !refused && (m.ft_accepted || ((earlier != null) && earlier.ft_accepted));
            Filetransfer f = earlier;
            if (f == null)
            {
                // Its row went with an earlier cancel or failure, and its bytes with it: a new row, the same entry.
                f = new Filetransfer();
                f.tox_public_key_string = pubkey;
                f.direction = TRIFA_FT_DIRECTION_INCOMING.value;
                f.path_name = VFS_PREFIX + VFS_TMP_FILE_DIR + "/" + pubkey + "/";
                f.file_name = get_incoming_filetransfer_local_filename(offered_name, pubkey);
                f.ft_outgoing_started = false;
                f.message_id = m.id;
                f.file_number = -1;
                f.id = insert_into_filetransfer_db(f);
                if (f.id <= 0)
                {
                    return false;
                }
            }
            else if ((f.file_number >= 0) && (f.file_number != file_number) && (f.state != TOX_FILE_CONTROL_CANCEL.value))
            {
                // Its sender gave up the transfer this one replaces (an FTV2 one toxcore keeps through a cut); the
                // number is still that transfer's, since every new offer takes its number off other rows.
                tox_file_control(friend_number, f.file_number, TOX_FILE_CONTROL_CANCEL.value);
            }

            // A stream still open from the cut writes its bytes now, and they are counted.
            HelperGeneric.flush_close_vfs_write_cache(f.path_name + "/" + f.file_name);
            if (refused)
            {
                // The person cancelled it: its bytes do not stay (SPEC-network 1.5), and it waits for a tap.
                delete_filetransfer_tmpfile(f.id);
            }
            final long partial = refused ? -1L : get_vfs_file_length(f.path_name, f.file_name);
            final FileResume.Decision d = FileResume.decide(true, false, partial, file_size);
            if ((d.step == FileResume.Step.FROM_START) && (partial > file_size))
            {
                delete_filetransfer_tmpfile(f.id);
            }

            f.file_number = file_number;
            f.kind = kind;
            f.filesize = file_size;
            f.tox_file_id_hex = id;
            f.state = TOX_FILE_CONTROL_PAUSE.value;
            f.current_position = (d.step == FileResume.Step.RESUME) ? d.seekTo : 0L;
            f.message_id = m.id;
            update_filetransfer_db_full(f);
            orma.updateFiletransfer().idEq(f.id).ft_accepted(false).execute();
            orma.updateMessage().idEq(m.id).
                    filetransfer_id(f.id).
                    filetransfer_kind(kind).
                    state(TOX_FILE_CONTROL_PAUSE.value).
                    ft_accepted(accepted_before).
                    execute();

            final Message current = (Message) orma.selectFromMessage().idEq(m.id).get(0);
            final boolean by_itself = FileResume.acceptsByItself(accepted_before, refused,
                                                                 auto_download_allows(current));
            NetworkDiagnosticsLog.log("file_resume", "resume_incoming_offer:mid=" + m.id + " ftid=" + f.id + " " + d
                                                     + " partial=" + partial + "/" + file_size + " kind=" + kind
                                                     + " accepted_before=" + accepted_before + " refused=" + refused
                                                     + " by_itself=" + by_itself);
            if (by_itself)
            {
                accept_paused_incoming_filetransfer(current);
            }
            update_single_message_from_messge_id(m.id, true);
            return true;
        }
        catch (Exception e)
        {
            Log.w(TAG, "resume_incoming_offer:EE:" + e.getMessage());
            return false;
        }
    }

    /** A chunk of a file that is whole here already: not written; its end forgets the transfer. */
    static boolean incoming_chunk_of_file_already_here(final long friend_number, final long file_number,
                                                       final long length)
    {
        if (incoming_already_here.isEmpty())
        {
            return false;
        }
        final String key = already_here_key(HelperFriend.tox_friend_get_public_key__wrapper(friend_number),
                                            file_number);
        if (!incoming_already_here.contains(key))
        {
            return false;
        }
        if (length == 0)
        {
            incoming_already_here.remove(key);
            Log.i(TAG, "incoming_chunk_of_file_already_here:done " + key);
        }
        return true;
    }

    /** The control of a transfer of a file that is whole here already; true when it was one. */
    static boolean forget_incoming_already_here(final long friend_number, final long file_number)
    {
        return !incoming_already_here.isEmpty() && incoming_already_here.remove(
                already_here_key(HelperFriend.tox_friend_get_public_key__wrapper(friend_number), file_number));
    }

    private static Message find_incoming_file_message(final String pubkey, final String id, final long size)
    {
        for (final String variant : new String[]{id, id.toLowerCase(Locale.ROOT)})
        {
            final List<Message> found = orma.selectFromMessage().
                    tox_friendpubkeyEq(pubkey).
                    directionEq(0).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    ft_id_anchor_hexEq(variant).
                    orderByIdDesc().
                    toList();
            if (found == null)
            {
                continue;
            }
            for (final Message m : found)
            {
                if (FileResume.sameFile(m.ft_id_anchor_hex, known_incoming_size(m), id, size))
                {
                    return m;
                }
            }
        }
        return null;
    }

    /** The size an incoming file entry was offered with: its row's, the finished file's, or its text's. */
    private static long known_incoming_size(final Message m)
    {
        final Filetransfer f = transfer_row(m.filetransfer_id);
        if ((f != null) && (f.filesize > 0))
        {
            return f.filesize;
        }
        if (m.filedb_id > 0)
        {
            try
            {
                final List<FileDB> rows = orma.selectFromFileDB().idEq(m.filedb_id).toList();
                if ((rows != null) && !rows.isEmpty() && (rows.get(0).filesize > 0))
                {
                    return rows.get(0).filesize;
                }
            }
            catch (Exception ignored)
            {
            }
        }
        return FileResume.sizeFromIncomingText(m.text);
    }

    private static Filetransfer transfer_row(final long filetransfer_id)
    {
        if (filetransfer_id <= 0)
        {
            return null;
        }
        try
        {
            final List<Filetransfer> rows = orma.selectFromFiletransfer().idEq(filetransfer_id).toList();
            return ((rows == null) || rows.isEmpty()) ? null : rows.get(0);
        }
        catch (Exception e)
        {
            return null;
        }
    }

    /**
     * A file of theirs the person cancelled: cancelled with its row kept (cancel_incoming_filetransfer and the
     * row holders). A failure or the sender's cancel take the row away.
     */
    private static boolean incoming_cancelled_by_person(final Message m, final Filetransfer f)
    {
        return (m.state == TOX_FILE_CONTROL_CANCEL.value) && (m.filedb_id == -1) && (f != null)
               && (f.state == TOX_FILE_CONTROL_CANCEL.value);
    }

    /**
     * What the entry of a 1:1 file says while its connection is gone (SPEC-network 1.6), read from the message
     * and its row as they are now.
     */
    static FileResume.Wait direct_file_wait(final Message m, final Filetransfer f, final boolean outgoing,
                                            final boolean online)
    {
        if ((m == null) || (m.state == TOX_FILE_CONTROL_CANCEL.value) || (m.filedb_id != -1))
        {
            return FileResume.Wait.NONE;
        }
        if (outgoing)
        {
            final boolean cut_off = (m.state == TOX_FILE_CONTROL_PAUSE.value) && !m.ft_outgoing_started
                                    && (f != null) && (f.current_position > 0);
            return FileResume.outgoingWait(online, m.ft_outgoing_started, cut_off);
        }
        final boolean under_way = m.ft_accepted || (m.state == TOX_FILE_CONTROL_RESUME.value);
        long online_for = friend_online_for_ms(m.tox_friendpubkey);
        final Long asked_again = incoming_wait_restarted_at.get(m.id);
        if (asked_again != null)
        {
            final long since_asked = Math.max(0L, System.currentTimeMillis() - asked_again);
            online_for = (online_for < 0) ? since_asked : Math.min(online_for, since_asked);
        }
        return FileResume.incomingWait(online, (online_for < 0) ? Long.MAX_VALUE : online_for, under_way,
                                       (f != null) && (f.file_number >= 0));
    }

    /** Draws again the files of theirs that wait for this friend's next offer (see on_friend_connection_available). */
    private static void redraw_incoming_files_waiting_for(final String pubkey)
    {
        try
        {
            final List<Message> waiting = orma.selectFromMessage().
                    tox_friendpubkeyEq(pubkey).
                    directionEq(0).
                    TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                    filedb_idEq(-1).
                    stateNotEq(TOX_FILE_CONTROL_CANCEL.value).
                    toList();
            if (waiting == null)
            {
                return;
            }
            for (final Message m : waiting)
            {
                final Filetransfer f = transfer_row(m.filetransfer_id);
                if ((f == null) || (f.file_number < 0))
                {
                    update_single_message_from_messge_id(m.id, true);
                }
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "redraw_incoming_files_waiting_for:EE:" + e.getMessage());
        }
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
                    ft_outgoing_preparingEq(false).
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

            // KHANDAQ (0.2.56): a copy still being written is neither offered nor queued; it queues itself.
            if (!OutgoingFilePreparation.isReadyToOffer(m.ft_outgoing_preparing,
                                                        OutgoingFilePreparation.ACTIVE.isRunning(m.id)))
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

        String new_fake_filename_prefix = outgoing_copy_prefix(filepath);

        // KHANDAQ (QA live, F4): each copy gets a folder of its own under the outgoing folder and keeps the
        // picked name in it. The copy used to lie in the outgoing folder itself as "<4 hex>_<name>" (a random
        // number in front when that was taken), and that name went to the peer as the file's name: iOS and
        // the desktop showed "9ded_photo.png". Rows written before keep their flat paths, which every reader
        // takes whole (filename_fullpath, or path_name and file_name), and delete_outgoing_copy leaves the
        // outgoing folder itself in place.
        File file = null;
        try
        {
            file = claim_outgoing_copy(SD_CARD_FILES_OUTGOING_WRAPPER_DIR, new_fake_filename_prefix, filename);
            if (file == null)
            {
                Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:005");
                return null;
            }

            ret.filepath_wrapped = file.getParentFile().getAbsolutePath();
            // The name as made safe for the copy (outgoing_copy_file_name): the copy lies under it and goes out
            // with it.
            ret.filename_wrapped = file.getName();

            // now write that contents of the virtual file to the actual file on SD card ----------------
            // Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:filepath=" + filepath);
            InputStream in = open_outgoing_copy_source(filepath, filename);
            // KHANDAQ (audit #21): openInputStream can return null for some providers.
            if (in == null)
            {
                Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:in==null");
                delete_outgoing_copy(file.getAbsolutePath());
                return null;
            }
            // KHANDAQ (audit #21): close BOTH streams in a finally. Previously a mid-copy failure (storage
            // full, provider dies) skipped the close calls -> the content-provider fd + the output fd leaked;
            // repeated failures exhaust the per-process fd limit (EMFILE) and break all file I/O until restart.
            BufferedOutputStream out = null;
            try
            {
                out = new BufferedOutputStream(new FileOutputStream(file));

                // KHANDAQ (perf): 256KB chunks instead of 4KB — far fewer read/write syscalls.
                final int chunk_size = 256 * 1024;
                byte[] buffer = new byte[chunk_size];
                int read;
                while ((read = in.read(buffer)) != -1)
                {
                    out.write(buffer, 0, read);
                }
                out.flush();
            }
            finally
            {
                try { in.close(); } catch (Exception ignored) {}
                if (out != null) { try { out.close(); } catch (Exception ignored) {} }
            }
            // now write that contents of the virtual file to the actual file on SD card ----------------

            ret.file_size_wrapped = file.length();

            if (ret.file_size_wrapped < 1)
            {
                delete_outgoing_copy(file.getAbsolutePath());
                Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:006");
                return null;
            }

            return ret;
        }
        catch (Exception e)
        {
            Log.i(TAG, "copy_outgoing_file_to_sdcard_dir:007:" + e.getMessage());
            e.printStackTrace();
            // KHANDAQ (QA live, F4): a copy cut short (storage full, the provider gone) does not stay behind.
            if (file != null)
            {
                delete_outgoing_copy(file.getAbsolutePath());
            }
            return null;
        }
    }

    /** The folder name the copies of a picked file start from (claim_outgoing_copy): four hex of its path's hash. */
    static String outgoing_copy_prefix(final String filepath)
    {
        return bytesToHex(TrifaSetPatternActivity.
                sha256(TrifaSetPatternActivity.StringToBytes2(filepath))).
                substring(1, 5).toLowerCase();
    }

    /**
     * The bytes of a file picked to send: through the content resolver for its address, else the file
     * [filepath]/[filename]. Null when the provider gives none (audit #21).
     */
    static InputStream open_outgoing_copy_source(final String filepath, final String filename)
            throws java.io.IOException
    {
        try
        {
            return context_s.getContentResolver().openInputStream(Uri.parse(filepath));
        }
        catch (Exception e)
        {
            return new java.io.FileInputStream(new File(filepath + "/" + filename));
        }
    }

    /**
     * KHANDAQ (QA live, F4): the file of a new copy, created empty in a folder of its own under the outgoing
     * folder: [prefix]/[name], or [prefix]_1/[name] and on when that path is taken (the same file sent twice,
     * two at once), so that no two copies share a path. The name is made safe first (outgoing_copy_file_name),
     * and a copy is made only where its path stays in the outgoing folder (outgoing_copy_target). Null when
     * 5000 are taken.
     */
    static File claim_outgoing_copy(final String outgoingDir, final String prefix, final String name)
            throws java.io.IOException
    {
        if ((outgoingDir == null) || outgoingDir.isEmpty())
        {
            return null;
        }
        final String safe_name = outgoing_copy_file_name(name, prefix);
        for (int attempt = 0; attempt <= 5000; attempt++)
        {
            final File file = outgoing_copy_target(outgoingDir, outgoing_copy_dir_name(prefix, attempt), safe_name);
            if (file == null)
            {
                // With the name made safe, only a folder that links elsewhere gets here; the next one is tried.
                continue;
            }
            final File dir = file.getParentFile();
            try
            {
                if (create_outgoing_copy_file(dir, file))
                {
                    return file;
                }
            }
            catch (java.io.IOException e)
            {
                remove_outgoing_copy_dir(dir, outgoingDir);
                throw e;
            }
        }
        return null;
    }

    /**
     * KHANDAQ (QA live, F4): [outgoing]/[folder]/[name] as a file, or null when that path leads anywhere else,
     * by "..", a separator or a link. Checked on the normalized path, which is the one stored and shown, and
     * again with links resolved (is_inside_outgoing_dir), which is where the bytes would go.
     */
    private static File outgoing_copy_target(final String outgoingDir, final String folder, final String name)
    {
        final Path base = Paths.get(outgoingDir).normalize();
        final Path target = base.resolve(folder).resolve(name).normalize();
        if (!target.startsWith(base))
        {
            return null;
        }
        if (target.getNameCount() != base.getNameCount() + 2)
        {
            return null;
        }
        final File file = target.toFile();
        if (!is_inside_outgoing_dir(file, outgoingDir))
        {
            return null;
        }
        return file;
    }

    /**
     * KHANDAQ (QA live, F4): whether the file lies in the outgoing folder, both with all links resolved. A path
     * that cannot be resolved does not.
     */
    private static boolean is_inside_outgoing_dir(final File file, final String outgoingDir)
    {
        try
        {
            final String real_dir = new File(outgoingDir).getCanonicalPath();
            return file.getCanonicalPath().startsWith(real_dir + File.separator);
        }
        catch (java.io.IOException e)
        {
            return false;
        }
    }

    /**
     * KHANDAQ (QA live, F4): creates the copy's file empty; false when a copy is there already, or a copy
     * from before the folders has the folder's name ("9ded_1" for a file named "1"). The folder is made
     * again when the last copy in it was removed in between (delete_outgoing_copy).
     */
    private static boolean create_outgoing_copy_file(final File dir, final File file) throws java.io.IOException
    {
        for (int tries = 0; ; tries++)
        {
            dir.mkdirs();
            if (dir.isFile())
            {
                return false;
            }
            try
            {
                return file.createNewFile();
            }
            catch (java.io.IOException e)
            {
                if (dir.isDirectory() || (tries >= 2))
                {
                    throw e;
                }
            }
        }
    }

    /**
     * KHANDAQ (QA live, F4): the longest name a copy may have, in UTF-8 bytes: the longest file name Android's
     * file systems take, and the longest name toxcore sends with a file.
     */
    static final int OUTGOING_COPY_NAME_MAX_BYTES = TOX_MAX_FILENAME_LENGTH;
    /** An extension longer than this, dot included, is not kept apart when a name is cut. */
    private static final int OUTGOING_COPY_EXTENSION_MAX_CHARS = 16;

    /**
     * KHANDAQ (QA live, F4): the name a copy keeps on disk and goes out with: the picked name, made safe for
     * a path. The name comes from another app (a provider's display name, the photo picker, a share), so it
     * may hold anything. Path separators and control characters become "_". Leading dots go, so "." and ".."
     * are nothing, and any other ".." becomes "_". A name over OUTGOING_COPY_NAME_MAX_BYTES is cut, keeping
     * its extension. A name with nothing left is the prefix and "_", the form every copy had before.
     */
    static String outgoing_copy_file_name(final String filename, final String prefix)
    {
        final String picked = (filename == null) ? "" : filename;
        final StringBuilder safe = new StringBuilder(picked.length());
        for (int i = 0; i < picked.length(); i++)
        {
            final char c = picked.charAt(i);
            if ((safe.length() == 0) && (c == '.'))
            {
                continue;
            }
            safe.append(((c == '/') || (c == '\\') || (c == ':') || Character.isISOControl(c)) ? '_' : c);
        }
        final String name = cut_outgoing_copy_name(safe.toString().replace("..", "_"));
        if (name.trim().isEmpty())
        {
            return prefix + "_";
        }
        return name;
    }

    /** The name cut to OUTGOING_COPY_NAME_MAX_BYTES after a whole character, its extension kept when short. */
    private static String cut_outgoing_copy_name(final String name)
    {
        if (AirUtf8Limit.utf8Length(name, 0, name.length()) <= OUTGOING_COPY_NAME_MAX_BYTES)
        {
            return name;
        }
        final int dot = name.lastIndexOf('.');
        final String extension = ((dot > 0) && ((name.length() - dot) <= OUTGOING_COPY_EXTENSION_MAX_CHARS))
                                 ? name.substring(dot) : "";
        final String stem = name.substring(0, name.length() - extension.length());
        final int room = OUTGOING_COPY_NAME_MAX_BYTES - AirUtf8Limit.utf8Length(extension, 0, extension.length());
        int end = AirUtf8Limit.fittingChars(stem, room);
        // A dot left at the end of the cut would stand next to the extension's own, a ".." again.
        while ((end > 0) && !extension.isEmpty() && (stem.charAt(end - 1) == '.'))
        {
            end--;
        }
        return stem.substring(0, end) + extension;
    }

    /** KHANDAQ (QA live, F4): the folder of a copy under the outgoing folder; the next one when it is taken. */
    static String outgoing_copy_dir_name(final String prefix, final int attempt)
    {
        return attempt <= 0 ? prefix : prefix + "_" + attempt;
    }

    /**
     * KHANDAQ (QA live, F4): removes this app's copy of a file we sent, and the folder of its own it lies in
     * (copy_outgoing_file_to_sdcard_dir) once that is empty. A copy from before those folders lies in the
     * outgoing folder itself, which stays. Anything outside the outgoing folder is left alone.
     */
    static boolean delete_outgoing_copy(final String fullpath)
    {
        return delete_outgoing_copy(fullpath, SD_CARD_FILES_OUTGOING_WRAPPER_DIR);
    }

    /** As above, with the outgoing folder given (MainActivity sets it with a slash at the end). */
    static boolean delete_outgoing_copy(final String fullpath, final String outgoingDir)
    {
        if ((fullpath == null) || (outgoingDir == null) || outgoingDir.isEmpty())
        {
            return false;
        }
        try
        {
            final Path base = Paths.get(outgoingDir).normalize();
            final Path target = Paths.get(fullpath).normalize();
            // Normalized first: a path that only starts with the outgoing folder's name ("outgoing/../x") is
            // not in it. The folder itself is never one copy.
            if (!target.startsWith(base))
            {
                return false;
            }
            if (target.getNameCount() <= base.getNameCount())
            {
                return false;
            }
            final File file = target.toFile();
            // Nor is a place a link leads to.
            if (!is_inside_outgoing_dir(file, outgoingDir))
            {
                return false;
            }
            final boolean deleted = file.delete();
            final File dir = file.getParentFile();
            if (dir != null)
            {
                remove_outgoing_copy_dir(dir, outgoingDir);
            }
            return deleted;
        }
        catch (Exception e)
        {
            Log.i(TAG, "delete_outgoing_copy:EE:" + e.getMessage());
            return false;
        }
    }

    /** KHANDAQ (QA live, F4): removes the folder of a copy once nothing is left in it; never the outgoing folder. */
    private static void remove_outgoing_copy_dir(final File dir, final String outgoingDir)
    {
        try
        {
            final File parent = dir.getParentFile();
            if ((parent != null) && new File(outgoingDir).getCanonicalFile().equals(parent.getCanonicalFile()))
            {
                // Removing a folder fails while anything is in it, so another copy there stays with its folder.
                //noinspection ResultOfMethodCallIgnored
                dir.delete();
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "remove_outgoing_copy_dir:EE:" + e.getMessage());
        }
    }

    /**
     * KHANDAQ (0.2.56): removes a copy for sending that was not completed: its part file
     * (OutgoingFilePreparation.partFileFor), its reserved place, then its folder once that is empty.
     * Anything outside the outgoing folder is left alone.
     */
    static void delete_unfinished_outgoing_copy(final String fullpath, final long messageId)
    {
        delete_unfinished_outgoing_copy(fullpath, messageId, SD_CARD_FILES_OUTGOING_WRAPPER_DIR);
    }

    /** As above, with the outgoing folder given. */
    static void delete_unfinished_outgoing_copy(final String fullpath, final long messageId,
                                                final String outgoingDir)
    {
        if (fullpath == null)
        {
            return;
        }
        delete_outgoing_copy(OutgoingFilePreparation.partFileFor(new File(fullpath), messageId).getAbsolutePath(),
                             outgoingDir);
        delete_outgoing_copy(fullpath, outgoingDir);
    }

    /**
     * KHANDAQ (0.2.56): an outgoing file is deleted while its copy for sending may still be written. A copy
     * that runs is told to stop and removes its part file itself; the part of one cut short by the end of
     * the app goes here. The reserved place is the caller's to remove.
     */
    static void stop_outgoing_copy(final Message m)
    {
        if ((m == null) || OutgoingFilePreparation.ACTIVE.cancel(m.id) || !m.ft_outgoing_preparing
            || (m.filename_fullpath == null))
        {
            return;
        }
        delete_outgoing_copy(OutgoingFilePreparation.partFileFor(new File(m.filename_fullpath), m.id).getAbsolutePath());
    }

    /**
     * KHANDAQ (0.2.56): Cancel on an outgoing file whose copy for sending is still being written; it has no
     * transfer yet. The copy stops and the entry goes with its rows. A running copy removes its part file
     * itself, and its end the rest (MessageListActivity.end_prepared_outgoing_file); for one cut short
     * before, all of it goes here. On the main thread.
     */
    static void cancel_outgoing_file_preparation(final Message m)
    {
        if (!OutgoingFilePreparation.ACTIVE.cancel(m.id))
        {
            delete_unfinished_outgoing_copy(m.filename_fullpath, m.id);
        }
        drop_outgoing_file_rows(m);
    }

    /** KHANDAQ (0.2.56): an outgoing file's entry and its transfer row go, and its entry leaves the chat. On the main thread. */
    static void drop_outgoing_file_rows(final Message m)
    {
        try
        {
            if (m.filetransfer_id > 0L)
            {
                delete_filetransfers_from_id(m.filetransfer_id);
            }
            orma.deleteFromMessage().idEq(m.id).execute();
        }
        catch (Exception e)
        {
            Log.i(TAG, "drop_outgoing_file_rows:EE:" + e.getMessage());
        }
        AirRepository.requestRefresh();
        final MessageListFragment fragment = MainActivity.message_list_fragment;
        if ((fragment != null) && (fragment.adapter != null))
        {
            fragment.adapter.remove_item(m);
        }
    }

    /**
     * KHANDAQ (0.2.56): at start, the outgoing files whose copy for sending was still being written when the
     * app stopped. The picked file's address is not stored, so the copy cannot go on: the half copy and the
     * entry go. Posted to the main thread, where copies begin and end, so that one running now stays.
     */
    static void discard_unfinished_outgoing_copies()
    {
        final Handler handler = MainActivity.main_handler_s != null
                ? MainActivity.main_handler_s
                : new Handler(Looper.getMainLooper());
        handler.post(() -> {
            try
            {
                final List<Message> unfinished = orma.selectFromMessage().
                        directionEq(1).
                        TRIFA_MESSAGE_TYPEEq(TRIFA_MSG_FILE.value).
                        ft_outgoing_preparingEq(true).
                        toList();
                if (unfinished == null)
                {
                    return;
                }
                for (final Message m : unfinished)
                {
                    if (OutgoingFilePreparation.ACTIVE.isRunning(m.id))
                    {
                        continue;
                    }
                    Log.i(TAG, "discard_unfinished_outgoing_copies:mid=" + m.id);
                    delete_unfinished_outgoing_copy(m.filename_fullpath, m.id);
                    drop_outgoing_file_rows(m);
                }
            }
            catch (Exception e)
            {
                Log.i(TAG, "discard_unfinished_outgoing_copies:EE:" + e.getMessage());
            }
        });
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
            // E21: said under the field of the conversation the file was opened from.
            AirComposerNotice.say(context_s.getString(R.string.chat_opening_file_failed), true);
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
            // E21: said under the field of the conversation the file was opened from, in its language.
            AirComposerNotice.say(context.getString(R.string.chat_opening_file_failed), true);
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
            // KHANDAQ (security): a stored name written by an older version may still be the peer's raw
            // one, so this join is refused if it would step outside the chat's directory — otherwise a
            // crafted name makes the player open an unrelated file.
            final String combined = path_inside_dir_or_null(message.path_name, message.file_name);
            if ((combined != null) && isMediaFileReadyForPlayback(combined))
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
                    ChatMediaHelper.mediaOpenTouchListener(context, message, null,
                            itemView.findViewById(R.id.layout_message_container)));
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
        // KHANDAQ (QA live, F4): the system Photo Picker names a photo after its gallery id; the peer gets the
        // gallery's name of it instead (qa-stripes-android.png, not 1000000022.png) when this app may read it.
        final String galleryName = photo_picker_gallery_name(context, uri, name);
        if (galleryName != null)
        {
            name = galleryName;
        }
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

    /** KHANDAQ (QA live, F4): the provider the system Photo Picker serves this device's own photos from. */
    private static final String PHOTO_PICKER_LOCAL_AUTHORITY = "com.android.providers.media.photopicker";
    /** KHANDAQ (QA live, F4): uids per Android user (UserHandle.PER_USER_RANGE), for the user of this app. */
    private static final int PER_USER_UID_RANGE = 100000;

    /**
     * KHANDAQ (QA live, F4): the name the gallery has for a photo or a video picked from this device with the
     * system Photo Picker. The picker keeps the file itself hidden and names every pick after its gallery id
     * ("1000000022.png"); the gallery answers with the real name when this app may read photos and videos.
     * Null for any other uri, when the picker gave a real name, and when the gallery does not answer.
     */
    private static String photo_picker_gallery_name(final Context context, final Uri uri, final String pickerName)
    {
        if ((context == null) || (uri == null))
        {
            return null;
        }
        try
        {
            final String mediaId = photo_picker_local_media_id(uri.getAuthority(), uri.getPathSegments(),
                                                               android.os.Process.myUid() / PER_USER_UID_RANGE);
            if (mediaId == null)
            {
                return null;
            }
            if ((pickerName != null) && (!pickerName.isEmpty())
                && (!is_photo_picker_placeholder_name(pickerName, mediaId)))
            {
                return null;
            }
            try (Cursor cur = context.getContentResolver().query(MediaStore.Files.getContentUri("external"),
                                                                 new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                                                                 MediaStore.MediaColumns._ID + "=?",
                                                                 new String[]{mediaId}, null))
            {
                if ((cur != null) && cur.moveToFirst())
                {
                    final int idx = cur.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    if ((idx >= 0) && (!cur.isNull(idx)))
                    {
                        final String name = cur.getString(idx);
                        if ((name != null) && (!name.trim().isEmpty()))
                        {
                            return name;
                        }
                    }
                }
            }
        }
        catch (Exception e)
        {
            Log.i(TAG, "photo_picker_gallery_name:EE:" + e.getMessage());
        }
        return null;
    }

    /**
     * KHANDAQ (QA live, F4): the gallery id of a pick from this device's own photos with the system Photo
     * Picker: content://media/picker/[user]/com.android.providers.media.photopicker/media/[id], with
     * picker_get_content in place of picker when the picker stood in for a GET_CONTENT. Null for any other
     * uri, a photo from a cloud provider or another user's among them.
     */
    static String photo_picker_local_media_id(final String authority, final List<String> segments, final int userId)
    {
        if ((!"media".equals(authority)) || (segments == null) || (segments.size() != 5))
        {
            return null;
        }
        final String root = segments.get(0);
        if (((!"picker".equals(root)) && (!"picker_get_content".equals(root)))
            || (!String.valueOf(userId).equals(segments.get(1)))
            || (!PHOTO_PICKER_LOCAL_AUTHORITY.equals(segments.get(2)))
            || (!"media".equals(segments.get(3))))
        {
            return null;
        }
        final String id = segments.get(4);
        if ((id == null) || id.isEmpty() || (id.length() > 18))
        {
            return null;
        }
        for (int i = 0; i < id.length(); i++)
        {
            if ((id.charAt(i) < '0') || (id.charAt(i) > '9'))
            {
                return null;
            }
        }
        return id;
    }

    /** KHANDAQ (QA live, F4): the Photo Picker's own name for a pick: its gallery id, with an extension or not. */
    static boolean is_photo_picker_placeholder_name(final String name, final String mediaId)
    {
        if ((name == null) || (mediaId == null) || mediaId.isEmpty())
        {
            return false;
        }
        return name.equals(mediaId) || name.startsWith(mediaId + ".");
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

    private static boolean accept_paused_incoming_filetransfer(Message message)
    {
        final Filetransfer f = transfer_row(message.filetransfer_id);
        if ((f == null) || (f.state != TOX_FILE_CONTROL_PAUSE.value))
        {
            return false;
        }
        if (f.file_number < 0)
        {
            // KHANDAQ (0.2.57): no transfer carries it now (the cut); its sender's next offer finds it again.
            return false;
        }

        final long friendNum = tox_friend_by_public_key__wrapper(message.tox_friendpubkey);
        set_filetransfer_accepted_from_id(message.filetransfer_id);
        set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_RESUME.value);
        set_message_accepted_from_id(message.id);
        set_message_state_from_id(message.id, TOX_FILE_CONTROL_RESUME.value);
        FileTransferDebug.logFileControlResume(friendNum, f.file_number);
        seek_then_resume_incoming(friendNum, f, !f.ft_accepted);
        update_single_message_from_messge_id(message.id, true);
        return true;
    }

    /**
     * KHANDAQ (0.2.57, SPEC-network 1.4): the accept of a file of theirs. toxcore takes a seek only before the
     * accept, so an offer bound to the bytes already here (resume_incoming_offer) is told where to go on first.
     * A transfer accepted before only gets the resume, the nudge it always was. Nothing writes to a transfer not
     * accepted yet, and the bind closed any stream of its file, so its bytes on disk are all there is.
     */
    private static void seek_then_resume_incoming(final long friend_number, final Filetransfer f,
                                                  final boolean first_accept)
    {
        if (first_accept && (f.current_position > 0))
        {
            final long partial = get_vfs_file_length(f.path_name, f.file_name);
            final long seek_to = FileResume.seekBeforeAccept(f.current_position, partial, f.filesize);
            final boolean sought = (seek_to > 0)
                                   && (MainActivity.tox_file_seek(friend_number, f.file_number, seek_to) == 0);
            NetworkDiagnosticsLog.log("file_resume", "seek_then_resume_incoming:ftid=" + f.id + " at="
                                                     + f.current_position + " partial=" + partial + " seek=" + seek_to
                                                     + " ok=" + sought);
            if (!sought)
            {
                // The bytes here no longer reach the point it was bound at, or toxcore did not take the seek: the
                // file comes from its first byte, and its first write empties the partial file.
                orma.updateFiletransfer().idEq(f.id).current_position(0).execute();
            }
        }
        tox_file_control(friend_number, f.file_number, TOX_FILE_CONTROL_RESUME.value);
    }

    /**
     * The Accept of a file of theirs (the waiting row's button). Also with no transfer carrying it now: the tap
     * is kept, and the sender's next offer goes on by itself.
     */
    static void accept_incoming_filetransfer_by_person(final Message message)
    {
        if ((message == null) || (message.filetransfer_id <= 0))
        {
            return;
        }
        final Filetransfer f = transfer_row(message.filetransfer_id);
        if ((f == null) || (f.state == TOX_FILE_CONTROL_CANCEL.value))
        {
            return;
        }
        if (f.file_number < 0)
        {
            set_message_accepted_from_id(message.id);
            update_single_message_from_messge_id(message.id, true);
            return;
        }
        if (f.state != TOX_FILE_CONTROL_PAUSE.value)
        {
            set_filetransfer_state_from_id(message.filetransfer_id, TOX_FILE_CONTROL_PAUSE.value);
        }
        accept_paused_incoming_filetransfer(message);
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
            MainActivity.main_handler_s.post(() -> resume_incoming_filetransfer_by_itself(message));
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
                    resume_incoming_filetransfer_by_itself(m);
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
                    file_numberNotEq(-1).
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

                    resume_incoming_filetransfer_by_itself((Message) orma.selectFromMessage().idEq(msg_id).get(0));
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

    /**
     * KHANDAQ (0.2.57): the retries that run by themselves (the friend back, the row drawn, the app in front).
     * They nudge a transfer that was accepted, as before, and accept one not accepted yet only when the
     * automatic download allows it: they used to accept every file, so its switches did nothing. A file no
     * transfer carries now waits for its sender's next offer (resume_incoming_offer).
     */
    static void resume_incoming_filetransfer_by_itself(final Message message)
    {
        if (message == null)
        {
            return;
        }
        try
        {
            final Message current = (Message) orma.selectFromMessage().idEq(message.id).get(0);
            if (!incoming_retry_applies(current.filedb_id, current.filetransfer_id)
                || (current.state == TOX_FILE_CONTROL_CANCEL.value))
            {
                return;
            }
            final Filetransfer f = transfer_row(current.filetransfer_id);
            if ((f == null) || (f.file_number < 0) || (f.state == TOX_FILE_CONTROL_CANCEL.value))
            {
                return;
            }
            final boolean accepted = current.ft_accepted || f.ft_accepted;
            if (!accepted)
            {
                if (auto_download_allows(current))
                {
                    accept_paused_incoming_filetransfer(current);
                }
                return;
            }
            if (accept_paused_incoming_filetransfer(current))
            {
                return;
            }
            tox_file_control(tox_friend_by_public_key__wrapper(current.tox_friendpubkey), f.file_number,
                             TOX_FILE_CONTROL_RESUME.value);
            if (current.state != TOX_FILE_CONTROL_RESUME.value)
            {
                set_filetransfer_state_from_id(f.id, TOX_FILE_CONTROL_RESUME.value);
                set_message_state_from_id(current.id, TOX_FILE_CONTROL_RESUME.value);
                update_single_message_from_messge_id(current.id, true);
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "resume_incoming_filetransfer_by_itself:EE:" + e.getMessage());
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
            // KHANDAQ (0.2.57, SPEC-network 1.5): the bytes of a file kept for the next offer go when the person
            // cancels it. Its row stays, cancelled and without the number toxcore gives to its next transfer: that
            // is how a later offer of it knows the person said no.
            delete_filetransfer_tmpfile(message.filetransfer_id);
            orma.updateFiletransfer().idEq(message.filetransfer_id).file_number(-1).execute();
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
        // KHANDAQ (0.2.56): a file whose copy for sending is still being written has no transfer to cancel. The
        // entry may have been drawn before its copy was complete, so it is read again: a copy completed in
        // between is a transfer by now and is cancelled as one.
        if (message.ft_outgoing_preparing || OutgoingFilePreparation.ACTIVE.isRunning(message.id))
        {
            Message current = null;
            try
            {
                final List<Message> rows = orma.selectFromMessage().idEq(message.id).toList();
                current = ((rows == null) || rows.isEmpty()) ? null : rows.get(0);
            }
            catch (Exception ignored)
            {
            }
            if ((current == null) || current.ft_outgoing_preparing
                || OutgoingFilePreparation.ACTIVE.isRunning(message.id))
            {
                cancel_outgoing_file_preparation(current != null ? current : message);
                return;
            }
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

    /**
     * KHANDAQ (QA live, F3): a file of theirs that is here. Its row in the file table comes only with the whole
     * file (MainActivity, file_recv_chunk) and goes with a failed one (fail_incoming_filetransfer), and its
     * transfer is over by then. A retry that ran late used to set such a file back to RESUME
     * (retry_incoming_filetransfer), and that state stays in the database: the file is still here.
     */
    static boolean incoming_file_is_here(final int state, final long filedb_id, final long filetransfer_id)
    {
        return (filedb_id != -1) && ((state == TOX_FILE_CONTROL_CANCEL.value) || (filetransfer_id <= 0));
    }

    /** KHANDAQ (QA live, F3): a retry of a file of theirs has a transfer to resume and no whole file yet. */
    static boolean incoming_retry_applies(final long filedb_id, final long filetransfer_id)
    {
        return (filetransfer_id > 0) && (filedb_id == -1);
    }

    static void retry_incoming_filetransfer(final Message message)
    {
        if (message == null)
        {
            return;
        }
        try
        {
            // KHANDAQ (QA live, F3): the retry works on the message as it is now, not as it was when the retry
            // was asked for. A row drawn while the file comes posts one (schedule_auto_retry_dm_incoming_file),
            // and a small file can be whole before it runs: with the transfer of the old copy, the finished file
            // was set back to RESUME for good, and its row lost "Save to phone", also after the chat was opened
            // again (QA: a TXT that came right after a PNG and a PDF).
            final Message current = (Message) orma.selectFromMessage().idEq(message.id).get(0);
            if (!incoming_retry_applies(current.filedb_id, current.filetransfer_id))
            {
                Log.i(TAG, "retry_incoming_filetransfer:nothing_to_retry:" + current.id);
                return;
            }
            final Filetransfer f = transfer_row(current.filetransfer_id);
            if ((f == null) || (f.file_number < 0) || (f.state == TOX_FILE_CONTROL_CANCEL.value)
                || (current.state == TOX_FILE_CONTROL_CANCEL.value))
            {
                // KHANDAQ (0.2.57): no transfer here to take up again (the cut, or a cancel). The file is wanted:
                // its sender's next offer of it is accepted and goes on by itself (resume_incoming_offer), and the
                // entry waits for that offer again; a resume of a cancelled transfer only spun forever.
                if (f != null)
                {
                    orma.updateFiletransfer().idEq(f.id).file_number(-1).state(TOX_FILE_CONTROL_PAUSE.value).execute();
                }
                orma.updateMessage().idEq(current.id).state(TOX_FILE_CONTROL_PAUSE.value).ft_accepted(true).execute();
                incoming_wait_restarted_at.put(current.id, System.currentTimeMillis());
                final Handler handler = MainActivity.main_handler_s != null ? MainActivity.main_handler_s
                                                                            : new Handler(Looper.getMainLooper());
                handler.postDelayed(() -> update_single_message_from_messge_id(current.id, true),
                                    FileResume.INCOMING_WAIT_AFTER_ONLINE_MS + 1000L);
                update_single_message_from_messge_id(current.id, true);
                Log.i(TAG, "retry_incoming_filetransfer:waits for the next offer mid=" + current.id);
                return;
            }
            if (accept_paused_incoming_filetransfer(current))
            {
                update_single_message_from_messge_id(current.id, true);
                return;
            }
            tox_file_control(tox_friend_by_public_key__wrapper(current.tox_friendpubkey),
                             get_filetransfer_filenum_from_id(current.filetransfer_id), TOX_FILE_CONTROL_RESUME.value);
            set_filetransfer_state_from_id(current.filetransfer_id, TOX_FILE_CONTROL_RESUME.value);
            set_message_state_from_id(current.id, TOX_FILE_CONTROL_RESUME.value);
            update_single_message_from_messge_id(current.id, true);
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
