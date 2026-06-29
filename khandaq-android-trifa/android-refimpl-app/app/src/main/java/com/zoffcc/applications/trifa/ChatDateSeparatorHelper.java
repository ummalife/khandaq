package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

/** Theme-aware inline and floating chat date separators (Telegram-style). */
final class ChatDateSeparatorHelper
{
    interface DateHeaderSource
    {
        boolean shouldShowDateHeader(int position);

        String getDateHeaderText(int position);
    }

    private ChatDateSeparatorHelper()
    {
    }

    static void applyTheme(final TextView label)
    {
        if (label == null)
        {
            return;
        }
        final Context context = label.getContext();
        label.setTextColor(ContextCompat.getColor(context, R.color.tg_date_separator_text));
        label.setBackground(ContextCompat.getDrawable(context, R.drawable.rounded_date_bg));
    }

    static void bindInlineDateHeader(final View itemView, final int position, final DateHeaderSource source)
    {
        if (itemView == null)
        {
            return;
        }

        final ViewGroup dateRow = itemView.findViewById(R.id.message_text_date);
        final TextView dateLabel = itemView.findViewById(R.id.message_text_date_string);
        if (dateRow == null || dateLabel == null)
        {
            return;
        }

        dateRow.setVisibility(View.GONE);
        if (position == RecyclerView.NO_POSITION || source == null)
        {
            return;
        }

        if (source.shouldShowDateHeader(position))
        {
            dateLabel.setText(source.getDateHeaderText(position));
            applyTheme(dateLabel);
            dateRow.setVisibility(View.VISIBLE);
        }
    }
}
