package com.zoffcc.applications.trifa;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.luseen.autolinklibrary.AutoLinkMode;
import com.luseen.autolinklibrary.EmojiTextViewLinks;
import com.zoffcc.applications.sorm.GroupMessage;
import com.zoffcc.applications.sorm.Message;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.List;

import static com.zoffcc.applications.trifa.TRIFAGlobals.TOXURL_PATTERN;

/**
 * KHANDAQ (inbox #26, 27.11): the whole text of a message whose entry shows only its start (AirLongText), opened
 * by Read more under the entry. The text is read from the database by the message's id, off the main thread, so
 * the screen also comes back after the system has ended the app behind it. It is laid out in pieces in a list,
 * which lays out only the pieces on the screen, however long the text. Links open as in the conversation; Copy
 * takes the whole text.
 */
public final class AirLongTextActivity extends AirScreenActivity
{
    private static final String TAG = "trifa.AirLongText";

    static final String EXTRA_KIND = "air_long_text_kind";
    static final String EXTRA_MESSAGE_ID = "air_long_text_message_id";
    /** A message of the conversation with one person (or the saved messages): a Message row. */
    static final int KIND_DIRECT = 1;
    /** A message of a group: a GroupMessage row. */
    static final int KIND_GROUP = 2;

    private final Pieces pieces = new Pieces();
    private TextView copy;
    @Nullable
    private String wholeText = null;

    static void open(final Context context, final int kind, final long messageId)
    {
        final Intent intent = new Intent(context, AirLongTextActivity.class);
        intent.putExtra(EXTRA_KIND, kind);
        intent.putExtra(EXTRA_MESSAGE_ID, messageId);
        context.startActivity(intent);
    }

    @Override
    protected int screenTitle()
    {
        return R.string.air_long_text_title;
    }

    @Override
    protected int screenLayout()
    {
        return R.layout.air_long_text;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        final RecyclerView list = findViewById(R.id.air_long_text_list);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(pieces);
        keepTextMeasure(findViewById(R.id.air_screen_root), list);

        final LinearLayout bottom = findViewById(R.id.air_screen_bottom);
        copy = AirScreenKit.button(this, getString(R.string.layout___copy), false);
        copy.setOnClickListener(v -> copyText());
        copy.setEnabled(false);
        bottom.addView(copy, AirScreenKit.matchWidth());
        bottom.setVisibility(View.VISIBLE);

        final int kind = getIntent().getIntExtra(EXTRA_KIND, 0);
        final long messageId = getIntent().getLongExtra(EXTRA_MESSAGE_ID, -1L);
        final Context app = getApplicationContext();
        new Thread(() -> {
            final String text = readText(app, kind, messageId);
            final List<String> parts = AirLongText.pieces(text);
            runOnUiThread(() -> show(text, parts));
        }, "air-long-text").start();
    }

    private void show(@Nullable final String text, final List<String> parts)
    {
        if (isFinishing() || isDestroyed())
        {
            return;
        }
        if (text == null)
        {
            // The message is gone (deleted, or its database is closed): there is nothing to read.
            finish();
            return;
        }
        wholeText = text;
        pieces.set(parts);
        copy.setEnabled(true);
    }

    /** The text the entry of the message shows the start of, or null when the message cannot be read. */
    @Nullable
    private static String readText(final Context context, final int kind, final long messageId)
    {
        if (TrifaToxService.orma == null || messageId < 0)
        {
            return null;
        }
        try
        {
            if (kind == KIND_DIRECT)
            {
                final List<Message> rows = TrifaToxService.orma.selectFromMessage().idEq(messageId).toList();
                if (rows == null || rows.isEmpty() || rows.get(0).text == null)
                {
                    return null;
                }
                return MessageReplyHelper.parse(rows.get(0).text).bodyText;
            }
            if (kind == KIND_GROUP)
            {
                final List<GroupMessage> rows = TrifaToxService.orma.selectFromGroupMessage().idEq(messageId).toList();
                if (rows == null || rows.isEmpty())
                {
                    return null;
                }
                return GroupMentionHelper.parse(GroupMessageLayoutHelper.displayTextForMessage(context, rows.get(0)))
                        .bodyText;
            }
        }
        catch (Exception e)
        {
            Log.w(TAG, "readText:EE:" + e.getMessage());
        }
        return null;
    }

    private void copyText()
    {
        final String text = wholeText;
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (text == null || clipboard == null)
        {
            return;
        }
        try
        {
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.air_long_text_title), text));
        }
        catch (RuntimeException e)
        {
            // A text too large for the clipboard's binder call: the clipboard keeps what it had.
            Log.w(TAG, "copy:EE:" + e.getMessage());
            return;
        }
        // From Android 13 the system shows its own "copied" confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU)
        {
            toast(R.string.text_copied_to_clipboard);
        }
    }

    private static void openLink(final Context context, final AutoLinkMode mode, final String matched)
    {
        final String link = matched.replaceFirst("^\\s", "");
        if (mode == AutoLinkMode.MODE_URL)
        {
            AirLinkQuestion.openLink(context, link);
        }
        else if (mode == AutoLinkMode.MODE_EMAIL)
        {
            AirLinkQuestion.writeEmail(context, link);
        }
        else if (mode == AutoLinkMode.MODE_CUSTOM)
        {
            AirLinkQuestion.requestContact(context, link);
        }
    }

    /** The pieces of the text, each in the body face of an entry, at the size picked in the app's settings. */
    private static final class Pieces extends RecyclerView.Adapter<PieceHolder>
    {
        private final List<String> parts = new ArrayList<>();

        @SuppressLint("NotifyDataSetChanged")
        void set(final List<String> next)
        {
            parts.clear();
            parts.addAll(next);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public PieceHolder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType)
        {
            return new PieceHolder(parent.getContext());
        }

        @Override
        public void onBindViewHolder(@NonNull final PieceHolder holder, final int position)
        {
            holder.text.setAutoLinkText(parts.get(position));
        }

        @Override
        public int getItemCount()
        {
            return parts.size();
        }
    }

    private static final class PieceHolder extends RecyclerView.ViewHolder
    {
        final EmojiTextViewLinks text;

        PieceHolder(final Context context)
        {
            super(new EmojiTextViewLinks(context));
            text = (EmojiTextViewLinks) itemView;
            text.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                               ViewGroup.LayoutParams.WRAP_CONTENT));
            AirTranscript.bodyFace(text);
            AirTranscript.bodySize(text, null);
            text.setCustomRegex(TOXURL_PATTERN);
            text.addAutoLinkMode(AutoLinkMode.MODE_URL, AutoLinkMode.MODE_EMAIL, AutoLinkMode.MODE_CUSTOM);
            text.setAutoLinkOnClickListener((mode, matched) -> openLink(context, mode, matched));
        }
    }
}
