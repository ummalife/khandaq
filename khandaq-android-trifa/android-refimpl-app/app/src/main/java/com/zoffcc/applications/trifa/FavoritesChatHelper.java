package com.zoffcc.applications.trifa;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.widget.ImageView;

import androidx.preference.PreferenceManager;

import com.zoffcc.applications.sorm.FileDB;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import static com.zoffcc.applications.trifa.CombinedFriendsAndConferences.COMBINED_IS_FAVORITES;
import static com.zoffcc.applications.trifa.HelperFiletransfer.buildOutgoingFileMessageText;
import static com.zoffcc.applications.trifa.HelperFiletransfer.copy_outgoing_file_to_sdcard_dir;
import static com.zoffcc.applications.trifa.HelperFiletransfer.get_vfs_file_length;
import static com.zoffcc.applications.trifa.HelperGeneric.copy_vfs_file_to_real_file;
import static com.zoffcc.applications.trifa.HelperGeneric.display_toast;
import static com.zoffcc.applications.trifa.HelperGeneric.logI;
import static com.zoffcc.applications.trifa.HelperGeneric.normalize_chat_input_text;
import static com.zoffcc.applications.trifa.HelperGeneric.trim_to_utf8_length_bytes;
import static com.zoffcc.applications.trifa.HelperMessage.insert_into_message_db;
import static com.zoffcc.applications.trifa.MainActivity.friend_list_fragment;
import static com.zoffcc.applications.trifa.MainActivity.SD_CARD_TMP_DIR;
import static com.zoffcc.applications.trifa.MainActivity.VFS_ENCRYPT;
import static com.zoffcc.applications.trifa.MainActivity.tox_friend_delete;
import static com.zoffcc.applications.trifa.TRIFAGlobals.FT_OUTGOING_FILESIZE_FRIEND_MAX_TOTAL;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_FT_DIRECTION.TRIFA_FT_DIRECTION_OUTGOING;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_FILE;
import static com.zoffcc.applications.trifa.TRIFAGlobals.TRIFA_MSG_TYPE.TRIFA_MSG_TYPE_TEXT;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_CONTROL.TOX_FILE_CONTROL_CANCEL;
import static com.zoffcc.applications.trifa.ToxVars.TOX_FILE_KIND.TOX_FILE_KIND_DATA;
import static com.zoffcc.applications.trifa.TRIFAGlobals.global_my_toxid;
import static com.zoffcc.applications.trifa.ToxVars.TOX_MSGV3_MAX_MESSAGE_LENGTH;
import static com.zoffcc.applications.trifa.ToxVars.TOX_PUBLIC_KEY_SIZE;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.TrifaToxService.orma;

/** Built-in local-only "Saved Messages" chat (Избранное). */
final class FavoritesChatHelper
{
    private static final String TAG = "FavoritesChatHelper";
    static final String CHAT_ID = "__KHANDAQ_FAVORITES__";

    private static final String PREF_SELF_MIGRATED = "favorites_self_contact_migrated_v1";

    private FavoritesChatHelper()
    {
    }

    static boolean isFavoritesChat(final String pubkey)
    {
        return CHAT_ID.equalsIgnoreCase(pubkey == null ? "" : pubkey.trim());
    }

    static String displayName(final Context context)
    {
        return context.getString(R.string.favorites_chat_title);
    }

    static CombinedFriendsAndConferences buildListItem()
    {
        final CombinedFriendsAndConferences item = new CombinedFriendsAndConferences();
        item.is_friend = COMBINED_IS_FAVORITES;
        return item;
    }

    static void prependPinnedRow(final List<CombinedFriendsAndConferences> items)
    {
        if (items == null)
        {
            return;
        }

        for (int i = items.size() - 1; i >= 0; i--)
        {
            if (items.get(i).is_friend == COMBINED_IS_FAVORITES)
            {
                items.remove(i);
            }
        }
        items.add(0, buildListItem());
    }

    static void bindListAvatar(final ImageView avatar)
    {
        if (avatar == null)
        {
            return;
        }
        avatar.setImageResource(R.drawable.ic_favorites_chat_avatar);
    }

    static int unreadCount()
    {
        if (orma == null)
        {
            return 0;
        }
        try
        {
            return orma.selectFromMessage().tox_friendpubkeyEq(CHAT_ID).is_newEq(true).count();
        }
        catch (Exception ignored)
        {
            return 0;
        }
    }

    static void markAllRead()
    {
        if (orma == null)
        {
            return;
        }
        try
        {
            orma.updateMessage().tox_friendpubkeyEq(CHAT_ID).is_new(false).execute();
        }
        catch (Exception ignored)
        {
        }
    }

    static void openChat(final Context context)
    {
        final Intent intent = new Intent(context, MessageListActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.putExtra("friendnum", -1L);
        intent.putExtra("friend_pubkey", CHAT_ID);
        context.startActivity(intent);
    }

    static void ensureInitialized(final Context context)
    {
        if (orma == null || context == null)
        {
            return;
        }

        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getBoolean(PREF_SELF_MIGRATED, false))
        {
            return;
        }

        try
        {
            final String ownPubkey = ownPublicKeyHex();
            if (ownPubkey != null)
            {
                orma.updateMessage().tox_friendpubkeyEq(ownPubkey).tox_friendpubkey(CHAT_ID).execute();

                try
                {
                    orma.deleteFromFriendList().tox_public_key_stringEq(ownPubkey).execute();
                }
                catch (Exception ignored)
                {
                }

                final long friendnum = tox_friend_by_public_key__wrapper(ownPubkey);
                if (friendnum >= 0)
                {
                    try
                    {
                        tox_friend_delete(friendnum);
                    }
                    catch (Exception ignored)
                    {
                    }
                }

                prefs.edit().putBoolean(PREF_SELF_MIGRATED, true).apply();
            }
        }
        catch (Exception ignored)
        {
        }
    }

    static long sendTextMessage(final String rawText, final boolean updateMessageView)
    {
        if (orma == null)
        {
            return -1L;
        }

        final String msg = trim_to_utf8_length_bytes(
                normalize_chat_input_text(rawText),
                TOX_MSGV3_MAX_MESSAGE_LENGTH);
        if (TextUtils.isEmpty(msg))
        {
            return -1L;
        }

        final long now = System.currentTimeMillis();
        final Message m = new Message();
        m.tox_friendpubkey = CHAT_ID;
        m.direction = 1;
        m.TOX_MESSAGE_TYPE = 0;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_TYPE_TEXT.value;
        m.state = 1;
        m.sent_timestamp = now;
        m.sent_timestamp_ms = now;
        m.rcvd_timestamp = now / 1000L;
        m.rcvd_timestamp_ms = now;
        m.read = true;
        m.is_new = false;
        m.text = msg;
        m.message_id = 1;
        m.msg_version = 0;
        m.resend_count = 1;
        m.sent_push = 0;
        m.msg_idv3_hash = "";
        m.msg_id_hash = "";
        m.raw_msgv2_bytes = "";

        final long rowId = insert_into_message_db(m, updateMessageView);
        refreshChatList();
        return rowId;
    }

    /** Local-only file/voice note — no Tox file transfer. */
    static long sendLocalOutgoingFile(final Context context, final String filepath, final String filename,
                                      long fileSize, final boolean updateMessageView)
    {
        if ((orma == null) || (context == null) || TextUtils.isEmpty(filename))
        {
            logI(TAG, "sendLocalOutgoingFile:invalid args");
            return -1L;
        }

        if (fileSize < 1L)
        {
            fileSize = resolveSourceFileSize(context, filepath, filename);
        }
        if (fileSize < 1L)
        {
            logI(TAG, "sendLocalOutgoingFile:unknown size path=" + filepath + " name=" + filename);
            return -1L;
        }

        if (fileSize > FT_OUTGOING_FILESIZE_FRIEND_MAX_TOTAL)
        {
            display_toast(context.getString(R.string.chat_file_too_large), true, 100);
            return -1L;
        }

        final MessageListActivity.outgoing_file_wrapped wrapped =
                copy_outgoing_file_to_sdcard_dir(filepath, filename, fileSize);
        if (wrapped == null)
        {
            logI(TAG, "sendLocalOutgoingFile:copy failed path=" + filepath + " name=" + filename);
            return -1L;
        }

        final long rowId = insertLocalOutgoingFileMessage(context, wrapped, updateMessageView);
        logI(TAG, "sendLocalOutgoingFile:done rowId=" + rowId + " file=" + wrapped.filename_wrapped
                + " bytes=" + wrapped.file_size_wrapped);
        return rowId;
    }

    private static long insertLocalOutgoingFileMessage(final Context context,
                                                       final MessageListActivity.outgoing_file_wrapped wrapped,
                                                       final boolean updateMessageView)
    {
        final java.io.File storedFile = new java.io.File(wrapped.filepath_wrapped, wrapped.filename_wrapped);
        final String fullPath = storedFile.getAbsolutePath();

        final FileDB fileDb = new FileDB();
        fileDb.kind = TOX_FILE_KIND_DATA.value;
        fileDb.direction = TRIFA_FT_DIRECTION_OUTGOING.value;
        fileDb.tox_public_key_string = CHAT_ID;
        fileDb.path_name = wrapped.filepath_wrapped;
        fileDb.file_name = wrapped.filename_wrapped;
        fileDb.filesize = wrapped.file_size_wrapped;
        fileDb.is_in_VFS = false;
        final long filedbId = orma.insertIntoFileDB(fileDb);
        if (filedbId <= 0L)
        {
            return -1L;
        }

        final long now = System.currentTimeMillis();
        final Message m = new Message();
        m.tox_friendpubkey = CHAT_ID;
        m.direction = 1;
        m.TOX_MESSAGE_TYPE = 0;
        m.TRIFA_MESSAGE_TYPE = TRIFA_MSG_FILE.value;
        m.filetransfer_id = -1;
        m.filedb_id = filedbId;
        m.state = TOX_FILE_CONTROL_CANCEL.value;
        m.ft_accepted = true;
        m.ft_outgoing_started = true;
        m.ft_outgoing_queued = false;
        m.filename_fullpath = fullPath;
        m.sent_timestamp = now;
        m.sent_timestamp_ms = now;
        m.rcvd_timestamp = now / 1000L;
        m.rcvd_timestamp_ms = now;
        m.read = true;
        m.is_new = false;
        m.text = buildOutgoingFileMessageText(context, wrapped.filename_wrapped, wrapped.file_size_wrapped);
        m.storage_frame_work = false;
        m.sent_push = 0;
        m.filetransfer_kind = TOX_FILE_KIND_DATA.value;
        m.message_id = 1;
        m.msg_version = 0;
        m.resend_count = 1;
        m.msg_idv3_hash = "";
        m.msg_id_hash = "";
        m.raw_msgv2_bytes = "";

        final long rowId = insert_into_message_db(m, updateMessageView);
        refreshChatList();
        return rowId;
    }

    private static long resolveSourceFileSize(final Context context, final String filepath, final String filename)
    {
        if (TextUtils.isEmpty(filepath))
        {
            return -1L;
        }

        try
        {
            if (filepath.startsWith("content:"))
            {
                final android.net.Uri uri = android.net.Uri.parse(filepath);
                final androidx.documentfile.provider.DocumentFile documentFile =
                        androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri);
                if (documentFile != null)
                {
                    return documentFile.length();
                }
            }
            else
            {
                java.io.File file = new java.io.File(filepath, filename);
                if (!file.isFile())
                {
                    file = new java.io.File(filepath);
                }
                if (file.isFile())
                {
                    return file.length();
                }
            }
        }
        catch (Exception ignored)
        {
        }

        return -1L;
    }

    private static boolean shouldRefreshFavoritesMessageView()
    {
        return (MainActivity.message_list_activity != null)
                && isFavoritesChat(MainActivity.message_list_activity.get_friend_pubkey());
    }

    static void forwardTextToFavorites(final Context context, final String text)
    {
        if (TextUtils.isEmpty(text))
        {
            return;
        }

        final long rowId = sendTextMessage(text, false);
        if (rowId > 0)
        {
            display_toast(context.getString(R.string.forward_to_favorites_done), false, 300);
        }
    }

    static void forwardSelectedDirectMessages(final Context context)
    {
        forwardFromIds(context, MainActivity.selected_messages_text_only, true);
    }

    static void forwardSelectedGroupMessages(final Context context)
    {
        forwardFromIds(context, MainActivity.selected_group_messages_text_only, false);
    }

    private static void forwardFromIds(final Context context, final List<Long> ids, final boolean direct)
    {
        if (ids == null || ids.isEmpty() || orma == null)
        {
            return;
        }

        final ArrayList<Long> sorted = new ArrayList<>(ids);
        Collections.sort(sorted, new Comparator<Long>()
        {
            @Override
            public int compare(final Long o1, final Long o2)
            {
                return o1.compareTo(o2);
            }
        });

        final StringBuilder sb = new StringBuilder();
        int forwardedFiles = 0;
        final boolean refreshUi = shouldRefreshFavoritesMessageView();
        final Iterator<Long> it = sorted.iterator();
        while (it.hasNext())
        {
            try
            {
                final long id = it.next();
                if (direct)
                {
                    final Message message = orma.selectFromMessage().idEq(id).get(0);
                    if (message.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                    {
                        if (forwardDirectMessageFileToFavorites(context, message, refreshUi))
                        {
                            forwardedFiles++;
                        }
                        continue;
                    }
                    appendForwardText(sb, GroupMentionHelper.cleanForClipboard(message.text),
                            sourceLabelForDirect(message));
                }
                else
                {
                    final GroupMessage groupMessage = orma.selectFromGroupMessage().idEq(id).get(0);
                    if (groupMessage.TRIFA_MESSAGE_TYPE == TRIFA_MSG_FILE.value)
                    {
                        if (forwardGroupMessageFileToFavorites(context, groupMessage, refreshUi))
                        {
                            forwardedFiles++;
                        }
                        continue;
                    }
                    appendForwardText(sb, GroupMentionHelper.cleanForClipboard(groupMessage.text),
                            sourceLabelForGroup(groupMessage));
                }
            }
            catch (Exception ignored)
            {
            }
        }

        if (sb.length() > 0)
        {
            forwardTextToFavorites(context, sb.toString());
        }
        else if (forwardedFiles > 0)
        {
            display_toast(context.getString(R.string.forward_to_favorites_done), false, 300);
        }

        ids.clear();
    }

    private static void appendForwardText(final StringBuilder sb, final String text, final String sourceLabel)
    {
        if (TextUtils.isEmpty(text))
        {
            return;
        }
        if (sb.length() > 0)
        {
            sb.append("\n\n");
        }
        // KHANDAQ (#T6): Telegram-style source attribution so Saved shows WHO wrote it and from WHERE.
        if (!TextUtils.isEmpty(sourceLabel))
        {
            sb.append("↪ ").append(sourceLabel).append('\n');
        }
        sb.append(text);
    }

    /** KHANDAQ (#T6): "who + which chat" label for a forwarded 1:1 message (the contact's name). */
    private static String sourceLabelForDirect(final Message m)
    {
        try
        {
            final String name = HelperFriend.get_friend_name_from_pubkey(m.tox_friendpubkey);
            return (name != null && !name.trim().isEmpty()) ? name.trim() : null;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    /** KHANDAQ (#T6): "who · which group" label for a forwarded group message. */
    private static String sourceLabelForGroup(final GroupMessage m)
    {
        String sender = null;
        try
        {
            if (m.tox_group_peername != null && !m.tox_group_peername.trim().isEmpty())
            {
                sender = m.tox_group_peername.trim();
            }
        }
        catch (Exception ignored)
        {
        }
        String title = null;
        try
        {
            final String t = HelperGroup.get_effective_group_title(-1, m.group_identifier);
            if (t != null && !t.trim().isEmpty())
            {
                title = t.trim();
            }
        }
        catch (Exception ignored)
        {
        }
        if (sender != null && title != null)
        {
            return sender + " · " + title;
        }
        return sender != null ? sender : title;
    }

    private static boolean forwardDirectMessageFileToFavorites(final Context context, final Message message,
                                                             final boolean refreshUi)
    {
        final LocalFileSource source = resolveDirectMessageFileSource(message);
        if (source == null)
        {
            return false;
        }
        return sendLocalOutgoingFile(context, source.path, source.name, source.size, refreshUi) > 0L;
    }

    private static boolean forwardGroupMessageFileToFavorites(final Context context, final GroupMessage message,
                                                              final boolean refreshUi)
    {
        final LocalFileSource source = resolveGroupMessageFileSource(message);
        if (source == null)
        {
            return false;
        }
        return sendLocalOutgoingFile(context, source.path, source.name, source.size, refreshUi) > 0L;
    }

    private static final class LocalFileSource
    {
        final String path;
        final String name;
        final long size;

        LocalFileSource(final String path, final String name, final long size)
        {
            this.path = path;
            this.name = name;
            this.size = size;
        }
    }

    private static LocalFileSource resolveDirectMessageFileSource(final Message message)
    {
        if (message == null)
        {
            return null;
        }

        if (!TextUtils.isEmpty(message.filename_fullpath))
        {
            final java.io.File file = new java.io.File(message.filename_fullpath);
            if (file.isFile() && file.length() > 0L)
            {
                return new LocalFileSource(file.getParent(), file.getName(), file.length());
            }
        }

        if (message.filedb_id > 0L)
        {
            try
            {
                final FileDB fileDb = orma.selectFromFileDB().idEq(message.filedb_id).get(0);
                return resolveStoredFileSource(fileDb.path_name, fileDb.file_name, fileDb.filesize);
            }
            catch (Exception ignored)
            {
            }
        }

        return null;
    }

    private static LocalFileSource resolveGroupMessageFileSource(final GroupMessage message)
    {
        if (message == null)
        {
            return null;
        }

        if (!TextUtils.isEmpty(message.filename_fullpath))
        {
            final java.io.File file = new java.io.File(message.filename_fullpath);
            if (file.isFile() && file.length() > 0L)
            {
                return new LocalFileSource(file.getParent(), file.getName(), file.length());
            }
        }

        return resolveStoredFileSource(message.path_name, message.file_name, message.filesize);
    }

    private static LocalFileSource resolveStoredFileSource(final String pathName, final String fileName,
                                                           final long expectedSize)
    {
        if (TextUtils.isEmpty(pathName) || TextUtils.isEmpty(fileName))
        {
            return null;
        }

        final long actualSize = get_vfs_file_length(pathName, fileName);
        if (actualSize < 1L)
        {
            return null;
        }
        if ((expectedSize > 0L) && (actualSize < expectedSize))
        {
            return null;
        }

        if (VFS_ENCRYPT)
        {
            final String tmpName = copy_vfs_file_to_real_file(pathName, fileName, SD_CARD_TMP_DIR, "_fav");
            if (TextUtils.isEmpty(tmpName))
            {
                return null;
            }
            final java.io.File tmpFile = new java.io.File(SD_CARD_TMP_DIR, tmpName);
            if (!tmpFile.isFile() || tmpFile.length() < 1L)
            {
                return null;
            }
            return new LocalFileSource(tmpFile.getParent(), tmpFile.getName(), tmpFile.length());
        }

        return new LocalFileSource(pathName, fileName, actualSize);
    }

    private static void refreshChatList()
    {
        try
        {
            if (friend_list_fragment != null)
            {
                friend_list_fragment.add_all_friends_clear(0);
            }
        }
        catch (Exception ignored)
        {
        }
    }

    private static String ownPublicKeyHex()
    {
        if ((global_my_toxid == null) || (global_my_toxid.length() < (TOX_PUBLIC_KEY_SIZE * 2)))
        {
            return null;
        }
        return global_my_toxid.substring(0, (TOX_PUBLIC_KEY_SIZE * 2)).toUpperCase(Locale.ROOT);
    }
}
