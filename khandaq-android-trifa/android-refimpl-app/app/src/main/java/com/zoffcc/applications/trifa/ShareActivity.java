/**
 * [TRIfA], Java part of Tox Reference Implementation for Android
 * Copyright (C) 2017 Zoff <zoff@zoff.cc>
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

import org.khandaq.messenger.R;

import android.app.SearchManager;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.util.ArrayList;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import static com.zoffcc.applications.trifa.GroupMessageListActivity.add_attachment_ngc;
import static com.zoffcc.applications.trifa.HelperGeneric.collect_shared_text_from_intent;
import static com.zoffcc.applications.trifa.HelperFriend.tox_friend_by_public_key__wrapper;
import static com.zoffcc.applications.trifa.HelperGeneric.filter_out_non_hex_chars;
import static com.zoffcc.applications.trifa.HelperGeneric.normalize_tox_address;
import static com.zoffcc.applications.trifa.MainActivity.PREF__allow_file_sharing_to_trifa_via_intent;
import static com.zoffcc.applications.trifa.MainActivity.SelectFriendSingleActivity_ID;
import static com.zoffcc.applications.trifa.MessageListActivity.add_attachment;
import static com.zoffcc.applications.trifa.ToxVars.TOX_ADDRESS_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_GROUP_CHAT_ID_SIZE;
import static com.zoffcc.applications.trifa.ToxVars.TOX_PUBLIC_KEY_SIZE;

public class ShareActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.ShareActivity";

    TextView t1;
    Intent intent;
    String action;
    String type;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_share);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        HelperToolbar.enableUpNavigation(this, toolbar);

        t1 = (TextView) findViewById(R.id.text1);
        t1.setText("Share Content ...\nNot yet implemented via share.");

        HelperGeneric.logI(TAG, "onCreate");

        intent = getIntent();
        // HelperGeneric.logI(TAG, "onCreate:intent=" + intent);
        action = intent.getAction();
        type = intent.getType();

        if (!PREF__allow_file_sharing_to_trifa_via_intent)
        {
            return;
        }

        try
        {
            if (Intent.ACTION_SEARCH.equals(action))
            {
                String query = intent.getStringExtra(SearchManager.QUERY);
                // HelperGeneric.logI(TAG, "onCreate:query=" + query);
            }
            else if (Intent.ACTION_SEND.equals(action) && type != null)
            {
                if (("text/plain".equals(type)) && (intent.getStringExtra(Intent.EXTRA_TEXT) != null))
                {
                    Intent intent_friend_selection = new Intent(this, FriendSelectSingleActivity.class);
                    intent_friend_selection.putExtra("offline", 1);
                    intent_friend_selection.putExtra("ngc_groups", 1);
                    startActivityForResult(intent_friend_selection, SelectFriendSingleActivity_ID);
                }
                else
                {
                    Intent intent_friend_selection = new Intent(this, FriendSelectSingleActivity.class);
                    intent_friend_selection.putExtra("offline", 1);
                    intent_friend_selection.putExtra("ngc_groups", 1);
                    startActivityForResult(intent_friend_selection, SelectFriendSingleActivity_ID);
                }
            }
            else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null)
            {
                if ("text/plain".equals(type))
                {
                    Intent intent_friend_selection = new Intent(this, FriendSelectSingleActivity.class);
                    intent_friend_selection.putExtra("offline", 1);
                    intent_friend_selection.putExtra("ngc_groups", 1);
                    startActivityForResult(intent_friend_selection, SelectFriendSingleActivity_ID);
                }
                else
                {
                    Intent intent_friend_selection = new Intent(this, FriendSelectSingleActivity.class);
                    intent_friend_selection.putExtra("offline", 1);
                    intent_friend_selection.putExtra("ngc_groups", 1);
                    startActivityForResult(intent_friend_selection, SelectFriendSingleActivity_ID);
                }
            }
            else if (Intent.ACTION_VIEW.equals(action))
            {
                ClipData cdata = intent.getClipData();
                // HelperGeneric.logI(TAG, "onCreate:cdata=" + cdata);
                if (cdata != null)
                {
                    int item_count = cdata.getItemCount();
                    // HelperGeneric.logI(TAG, "onCreate:item_count=" + item_count);
                    // HelperGeneric.logI(TAG, "onCreate:getDescription=" + cdata.getDescription());
                }

                Uri data = intent.getData();
                // HelperGeneric.logI(TAG, "onCreate:data=" + data);
                String dataString = intent.getDataString();
                // HelperGeneric.logI(TAG, "onCreate:dataString=" + dataString);
                String shareWith = dataString.substring(dataString.lastIndexOf('/') + 1);
                // HelperGeneric.logI(TAG, "onCreate:shareWith=" + shareWith);

                // handle tox:......... URL
                if ((dataString != null) && (dataString.length() > 5) && (dataString.startsWith("tox:")))
                {
                    t1.setText(getString(R.string.share_id_label) + dataString.substring(4));

                    // TODO:
                    // check if app is running and unlocked -> otherwise start and unlock it
                    // then open "add" screen and fill in this ToxID
                    final String key_only = dataString.replaceFirst("tox:", "");
                    final String key_only_sanitzied = filter_out_non_hex_chars(key_only);
                    final String normalized_friend_address = normalize_tox_address(key_only_sanitzied);
                    if (normalized_friend_address != null)
                    {
                        handleToxFriendInvite(normalized_friend_address);
                    }
                    else if (key_only_sanitzied.length() == (TOX_GROUP_CHAT_ID_SIZE * 2))
                    {
                        handleToxNGCPublicGroupInvite(key_only_sanitzied);
                    }
                }
                else if (dataString != null)
                {
                    final String groupId = parseKhandaqGroupId(dataString);
                    if (groupId != null)
                    {
                        handleToxNGCPublicGroupInvite(groupId);
                    }
                }
            }
            else
            {
                ClipData cdata = intent.getClipData();
                // HelperGeneric.logI(TAG, "onCreate:cdata=" + cdata);
                if (cdata != null)
                {
                    int item_count = cdata.getItemCount();
                    // HelperGeneric.logI(TAG, "onCreate:item_count=" + item_count);
                    // HelperGeneric.logI(TAG, "onCreate:getDescription=" + cdata.getDescription());
                }

                Uri data = intent.getData();
                // HelperGeneric.logI(TAG, "onCreate:data=" + data);
                String dataString = intent.getDataString();
                // HelperGeneric.logI(TAG, "onCreate:dataString=" + dataString);
                try
                {
                    String shareWith = dataString.substring(dataString.lastIndexOf('/') + 1);
                    // HelperGeneric.logI(TAG, "onCreate:shareWith=" + shareWith);
                }
                catch (Exception e2)
                {
                }
            }
        }
        catch (Exception e)
        {
            e.printStackTrace();
            HelperGeneric.logI(TAG, "onCreate:EE:" + e.getMessage());
        }
    }

    @Override
    protected void onNewIntent(Intent intent)
    {
        super.onNewIntent(intent);
        // HelperGeneric.logI(TAG, "onNewIntent:intent=" + intent);
    }

    public void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);
        HelperGeneric.logI(TAG, "onActivityResult:intent=" + data);
        if (requestCode == SelectFriendSingleActivity_ID)
        {
            if (resultCode == RESULT_OK)
            {
                try
                {
                    String result_friend_pubkey = data.getData().toString();
                    HelperGeneric.logI(TAG, "onActivityResult:result_friend_pubkey=" + result_friend_pubkey);
                    if (result_friend_pubkey != null)
                    {
                        if (result_friend_pubkey.length() <= 2)
                        {
                            HelperGeneric.logI(TAG, "onActivityResult:result_friend_pubkey.length()=" + result_friend_pubkey.length());
                            return;
                        }

                        int item_type = Integer.parseInt(result_friend_pubkey.substring(0, 1));
                        String item_id = result_friend_pubkey.substring(2);

                        // HelperGeneric.logI(TAG, "type="+ type + " action=" + action + " item_type=" + item_type + " item_id="+item_id.length()+ " "+item_id);
                        // HelperGeneric.logI(TAG, "onActivityResult:intent:" + intent);
                        // HelperGeneric.logI(TAG, "onActivityResult:Intent.EXTRA_TEXT:" + intent.getStringExtra(Intent.EXTRA_TEXT));

                        if ((item_id.length() == TOX_PUBLIC_KEY_SIZE * 2) && (item_type == 0))
                        {
                            if (Intent.ACTION_SEND.equals(action) && type != null)
                            {
                                if (("text/plain".equals(type)) && (intent.getStringExtra(Intent.EXTRA_TEXT) != null))
                                {
                                    HelperGeneric.logI(TAG,"handle:001");
                                    handleSendText(intent, item_id);
                                }
                                else
                                {
                                    HelperGeneric.logI(TAG,"handle:002");
                                    handleSendImage(intent, item_id, 0);
                                }
                                return;
                            }
                            else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null)
                            {
                                if ("text/plain".equals(type))
                                {
                                    handleSendMultipleText(intent, item_id);
                                }
                                else if (type.startsWith("image/"))
                                {
                                    HelperGeneric.logI(TAG,"handle:003");
                                    handleSendMultipleImages(intent, item_id, 0);
                                }
                                else
                                {
                                    HelperGeneric.logI(TAG,"handle:004");
                                    handleSendMultipleImages(intent, item_id, 0);
                                }
                                return;
                            }
                        }
                        else if (item_type == 2)
                        {
                            if (Intent.ACTION_SEND.equals(action) && type != null)
                            {
                                if (("text/plain".equals(type)) && (intent.getStringExtra(Intent.EXTRA_TEXT) != null))
                                {
                                    handleSendTextToGroup(intent, item_id);
                                }
                                else
                                {
                                    HelperGeneric.logI(TAG,"handle:012");
                                    handleSendImage(intent, item_id, 2);
                                }
                                return;
                            }
                            else if (Intent.ACTION_SEND_MULTIPLE.equals(action) && type != null)
                            {
                                if ("text/plain".equals(type))
                                {
                                    handleSendMultipleTextToGroup(intent, item_id);
                                }
                                else if (type.startsWith("image/"))
                                {
                                    HelperGeneric.logI(TAG,"handle:013");
                                    handleSendMultipleImages(intent, item_id, 2);
                                }
                                else
                                {
                                    HelperGeneric.logI(TAG,"handle:014");
                                    handleSendMultipleImages(intent, item_id, 2);
                                }
                                return;
                            }
                        }
                    }
                }
                catch (Exception e)
                {
                    HelperGeneric.logI(TAG, "EE03:"+ e.getMessage());
                    e.printStackTrace();
                }
            }
        }
    }

    void handleToxFriendInvite(final String friend_pubkey)
    {
        HelperGeneric.logI(TAG, "handleToxFriendInvite:friend_pubkey=" + friend_pubkey);
        // ** // MessageListActivity.show_messagelist_for_friend(this, friend_pubkey);
        // close this share activity
        this.finish();
    }

    void handleToxNGCPublicGroupInvite(final String ngc_group_pubkey)
    {
        try
        {
            HelperGeneric.logI(TAG, "handleToxFriendInvite:ngc_group_pubkey=" + ngc_group_pubkey.substring(0, 5));
        }
        catch(Exception ignored)
        {
        }
        JoinPublicGroupActivity.show_join_public_group_activity(this, ngc_group_pubkey);
        // close this share activity
        this.finish();
    }

    @Nullable
    static String parseKhandaqGroupId(@Nullable String url)
    {
        if (url == null)
        {
            return null;
        }

        final String lower = url.toLowerCase();
        String hexPart = null;

        if (lower.startsWith("khandaq://group/"))
        {
            hexPart = url.substring("khandaq://group/".length());
        }
        else if (lower.startsWith("khandaq:group:"))
        {
            hexPart = url.substring("khandaq:group:".length());
        }

        if (hexPart == null)
        {
            return null;
        }

        final String sanitized = filter_out_non_hex_chars(hexPart);
        if (sanitized.length() == (TOX_GROUP_CHAT_ID_SIZE * 2))
        {
            return sanitized.toUpperCase();
        }

        return null;
    }

    void handleSendText(Intent intent, String friend_pubkey)
    {
        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (sharedText != null)
        {
            // HelperGeneric.logI(TAG, "handleSendText:sharedText=" + sharedText);
            MessageListActivity.show_messagelist_for_friend(this, friend_pubkey, sharedText);
            // close this share activity
            this.finish();
        }
    }

    void handleSendTextToGroup(Intent intent, String group_id)
    {
        final String sharedText = collect_shared_text_from_intent(intent);
        if (sharedText != null)
        {
            GroupMessageListActivity.show_messagelist_for_id(this, group_id, sharedText);
            this.finish();
        }
    }

    void handleSendMultipleText(Intent intent, String friend_pubkey)
    {
        final String sharedText = collect_shared_text_from_intent(intent);
        if (sharedText != null)
        {
            MessageListActivity.show_messagelist_for_friend(this, friend_pubkey, sharedText);
            this.finish();
        }
    }

    void handleSendMultipleTextToGroup(Intent intent, String group_id)
    {
        final String sharedText = collect_shared_text_from_intent(intent);
        if (sharedText != null)
        {
            GroupMessageListActivity.show_messagelist_for_id(this, group_id, sharedText);
            this.finish();
        }
    }

    // KHANDAQ (audit A27): a malicious app can ACTION_SEND us a URI pointing at OUR OWN private files
    // (a file:// path, or a content:// from our own FileProvider) — we'd then read + attach + exfiltrate
    // it to a chat. Only accept content:// URIs whose authority is NOT ours (i.e. a real foreign share).
    private boolean isUnsafeShareUri(Uri uri)
    {
        if (uri == null) { return true; }
        if (!"content".equalsIgnoreCase(uri.getScheme())) { return true; } // reject file:// and non-content
        final String auth = uri.getAuthority();
        if (auth != null)
        {
            final String pkg = getPackageName();
            if (auth.equals(pkg) || auth.startsWith(pkg + ".") || auth.toLowerCase().contains("khandaq"))
            {
                return true;
            }
        }
        return false;
    }

    void handleSendImage(Intent intent, String id, int type)
    {
        Uri imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (imageUri != null && !isUnsafeShareUri(imageUri))
        {
            Intent intent_fixup = new Intent();
            intent_fixup.setData(imageUri);
            if (type == 0)
            {
                // Intent { dat=content://com.android.providers.media.documents/document/image:12345 flg=0x43 }
                add_attachment(this, intent_fixup, intent, tox_friend_by_public_key__wrapper(id), false);
                MessageListActivity.show_messagelist_for_friend(this, id, null);
            }
            else if (type == 2)
            {
                add_attachment_ngc(this, intent_fixup, intent, id, false);
                GroupMessageListActivity.show_messagelist_for_id(this, id, null);
            }
            // close this share activity
            this.finish();
        }
    }

    void handleSendMultipleImages(Intent intent, String id, int type)
    {
        ArrayList<Uri> imageUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
        if (imageUris != null)
        {
            // KHANDAQ (audit A27): drop any URI that isn't a foreign content:// share (see isUnsafeShareUri).
            final ArrayList<Uri> safeUris = new ArrayList<>();
            for (Uri u : imageUris)
            {
                if (!isUnsafeShareUri(u)) { safeUris.add(u); }
            }
            imageUris = safeUris;
            if (imageUris.isEmpty()) { this.finish(); return; }
            if (type == 0)
            {
                MediaSendPreviewHelper.dispatchAttachments(this, imageUris,
                        MediaSendPreviewHelper.TARGET_FRIEND,
                        tox_friend_by_public_key__wrapper(id), null, false);
                MessageListActivity.show_messagelist_for_friend(this, id, null);
            }
            else if (type == 2)
            {
                MediaSendPreviewHelper.dispatchAttachments(this, imageUris,
                        MediaSendPreviewHelper.TARGET_GROUP, -1, id, false);
                GroupMessageListActivity.show_messagelist_for_id(this, id, null);
            }
            // close this share activity
            this.finish();
        }
    }
}
