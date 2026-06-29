package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

public final class ListEmptyStateHelper
{
    public static final int KIND_CHATS = 0;
    public static final int KIND_GROUPS = 1;
    public static final int KIND_FAVORITES = 2;
    public static final int KIND_CONTACTS = 3;

    private final View root;
    private final ImageView iconView;
    private final TextView titleView;
    private final TextView hintView;
    private final TextView actionView;
    private final TextView actionView2;

    @Nullable
    private Runnable actionListener;
    @Nullable
    private Runnable actionListener2;

    public ListEmptyStateHelper(@Nullable final View root)
    {
        this.root = root;
        if (root == null)
        {
            iconView = null;
            titleView = null;
            hintView = null;
            actionView = null;
            actionView2 = null;
            return;
        }

        iconView = root.findViewById(R.id.list_empty_icon);
        titleView = root.findViewById(R.id.list_empty_title);
        hintView = root.findViewById(R.id.list_empty_hint);
        actionView = root.findViewById(R.id.list_empty_action);
        actionView.setOnClickListener(v -> {
            if (actionListener != null)
            {
                actionListener.run();
            }
        });
        actionView2 = root.findViewById(R.id.list_empty_action_2);
        if (actionView2 != null)
        {
            actionView2.setOnClickListener(v -> {
                if (actionListener2 != null)
                {
                    actionListener2.run();
                }
            });
        }
    }

    public void setOnActionClickListener(@Nullable final Runnable listener)
    {
        actionListener = listener;
    }

    public void setOnAction2ClickListener(@Nullable final Runnable listener)
    {
        actionListener2 = listener;
    }

    public void setVisible(final boolean visible)
    {
        if (root != null)
        {
            root.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    public void bind(final int kind)
    {
        if (root == null)
        {
            return;
        }

        @DrawableRes final int iconRes;
        @StringRes final int titleRes;
        @StringRes final int hintRes;
        @StringRes final int actionRes;
        @StringRes final int action2Res; // KHANDAQ: optional 2nd action (contacts: "Показать QR-код")
        final boolean showAction;

        switch (kind)
        {
            case KIND_GROUPS:
                iconRes = R.drawable.ic_empty_groups;
                titleRes = R.string.empty_state_groups_title;
                hintRes = R.string.empty_state_groups_hint;
                actionRes = R.string.empty_state_groups_action;
                action2Res = 0;
                showAction = true;
                break;
            case KIND_FAVORITES:
                iconRes = R.drawable.ic_empty_favorites;
                titleRes = R.string.empty_state_favorites_title;
                hintRes = R.string.empty_state_favorites_hint;
                actionRes = 0;
                action2Res = 0;
                showAction = false;
                break;
            case KIND_CONTACTS:
                iconRes = R.drawable.ic_empty_contacts;
                titleRes = R.string.empty_state_contacts_title;
                hintRes = R.string.empty_state_contacts_hint;
                actionRes = R.string.empty_state_contacts_action; // "Копировать MyID"
                action2Res = R.string.profile_show_qr;             // "Показать QR-код"
                showAction = true;
                break;
            case KIND_CHATS:
            default:
                iconRes = R.drawable.ic_empty_chats;
                titleRes = R.string.empty_state_chats_title;
                hintRes = R.string.empty_state_chats_hint;
                actionRes = R.string.empty_state_chats_action;
                action2Res = 0;
                showAction = true;
                break;
        }

        iconView.setImageDrawable(ContextCompat.getDrawable(root.getContext(), iconRes));
        titleView.setText(titleRes);
        hintView.setText(hintRes);

        if (showAction && actionRes != 0)
        {
            actionView.setText(actionRes);
            actionView.setVisibility(View.VISIBLE);
        }
        else
        {
            actionView.setVisibility(View.GONE);
        }

        if (actionView2 != null)
        {
            if (action2Res != 0)
            {
                actionView2.setText(action2Res);
                actionView2.setVisibility(View.VISIBLE);
            }
            else
            {
                actionView2.setVisibility(View.GONE);
            }
        }
    }
}
