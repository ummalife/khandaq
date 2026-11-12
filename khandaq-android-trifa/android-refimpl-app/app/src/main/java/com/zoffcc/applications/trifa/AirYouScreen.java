package com.zoffcc.applications.trifa;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, E17): the You screen, as ProfileMainController on iOS (design/DESIGN.md, "You on
 * the phone"). First the QR code of your ID on a white card, with Share ID and Copy ID; then the photo,
 * the name and the status, edited where they stand; Details with the ID in its three named parts,
 * Change antispam and Password
 * and PIN; Log out with what it means; deleting the profile set apart at the bottom. Built from
 * AirScreenKit as Settings is; ProfileContentFragment keeps the work (pickers, saving, logging out) and
 * answers the actions.
 */
final class AirYouScreen
{
    private static final String TAG = "trifa.AirYouScreen";

    /** What the controls of the screen ask of the fragment that owns the profile. */
    interface Actions
    {
        void shareId();

        void copyId();

        void changePhoto(boolean hasPhoto);

        /** The name field lost the focus or Done was pressed; the text as it stands. */
        void commitName(String text);

        /** The status field lost the focus or Done was pressed; the text as it stands. */
        void commitStatus(String text);

        void changeAntispam();

        void openSecurity();

        void logOut();

        void deleteProfile();
    }

    /** A Tox ID in hex digits: the public key, then the antispam, then the check. */
    private static final int KEY_DIGITS = 64;
    private static final int ANTISPAM_DIGITS = 8;
    private static final int ID_DIGITS = 76;
    /** A name or a status wraps to this many lines and scrolls inside the field beyond them. */
    private static final int FIELD_MAX_LINES = 4;

    /** What a field hands over when its editing ends. */
    private interface Commit
    {
        void commit(String text);
    }

    private final Context context;
    private final LinearLayout column;
    private final ImageView qr;
    private final AirTileView photo;
    private final TextView photoNote;
    private final EditText nameField;
    private final EditText statusField;
    private final TextView keyPart;
    private final TextView antispamPart;
    private final TextView checkPart;
    private boolean hasPhoto;
    @Nullable
    private String shownId;
    @Nullable
    private ProfileTabAvatarHelper.OwnPhoto shownPhoto;
    @Nullable
    private String shownPhotoName;

    AirYouScreen(final LinearLayout column, final Actions actions, final boolean passwordSet)
    {
        this.column = column;
        context = column.getContext();
        final Resources res = context.getResources();
        // Done in a field hands the focus here, so the field ends its editing without the first
        // focusable view of the window taking it; the column itself is nothing to TalkBack. Out of
        // touch mode (a hardware keyboard) Android would draw its focus highlight over the whole
        // column, so it has none.
        column.setFocusableInTouchMode(true);
        column.setDefaultFocusHighlightEnabled(false);
        column.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        // 1. The QR code of your ID, and sharing it.
        qr = new ImageView(context);
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setContentDescription(context.getString(R.string.air_you_qr) + ". " + context.getString(R.string.air_you_qr_hint));
        final SquareCard card = new SquareCard(context, res.getDimensionPixelSize(R.dimen.air_you_qr_max));
        final GradientDrawable plate = new GradientDrawable();
        // Dark modules on white in both themes: what cameras read best. The hairline sets it off the light ground.
        plate.setColor(AirUi.color(context, R.color.kq_fixed_white));
        plate.setCornerRadius(res.getDimension(R.dimen.kq_radius_card));
        plate.setStroke(res.getDimensionPixelSize(R.dimen.kq_stroke_hairline), AirUi.color(context, R.color.kq_line));
        card.setBackground(plate);
        final int cardPad = res.getDimensionPixelSize(R.dimen.kq_space_16);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);
        card.addView(qr, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                                                      ViewGroup.LayoutParams.MATCH_PARENT));
        final FrameLayout qrHolder = new FrameLayout(context);
        qrHolder.addView(card, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                            ViewGroup.LayoutParams.WRAP_CONTENT,
                                                            Gravity.CENTER_HORIZONTAL));
        final TextView share = AirScreenKit.button(context, context.getString(R.string.air_you_share), true);
        share.setOnClickListener(v -> actions.shareId());
        final TextView copy = AirScreenKit.button(context, context.getString(R.string.air_you_copy), false);
        copy.setOnClickListener(v -> actions.copyId());
        AirScreenKit.addSection(column, null, null, qrHolder, AirScreenKit.buttonRow(context, share, copy));

        // 2. Photo, name and status, edited where they stand. The photo is a button beside the name.
        photo = new AirTileView(context);
        final FrameLayout photoButton = new FrameLayout(context);
        final int photoSide = res.getDimensionPixelSize(R.dimen.kq_space_64);
        photoButton.addView(photo, new FrameLayout.LayoutParams(photoSide, photoSide));
        photoButton.setForeground(context.getDrawable(R.drawable.air_fg_press_veil));
        photoButton.setClickable(true);
        photoButton.setFocusable(true);
        photoButton.setContentDescription(context.getString(R.string.air_you_photo) + ". "
                                          + context.getString(R.string.air_you_photo_hint));
        AirUi.markAsButton(photoButton);
        photoButton.setOnClickListener(v -> {
            endPhotoNote();
            actions.changePhoto(hasPhoto);
        });
        // A name is not prose: no spell-check underlines and no word suggestions under it. The status
        // keeps them.
        nameField = field(R.string.air_you_name_placeholder, ToxVars.TOX_MAX_NAME_LENGTH,
                          InputType.TYPE_TEXT_VARIATION_PERSON_NAME | InputType.TYPE_TEXT_FLAG_CAP_WORDS
                          | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, actions::commitName);
        statusField = field(R.string.air_you_status_placeholder, ToxVars.TOX_MAX_STATUS_MESSAGE_LENGTH,
                            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, actions::commitStatus);
        final LinearLayout nameRow = new LinearLayout(context);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.BOTTOM);
        nameRow.addView(photoButton, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                                   ViewGroup.LayoutParams.WRAP_CONTENT));
        final LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nameParams.setMarginStart(res.getDimensionPixelSize(R.dimen.kq_space_12));
        nameRow.addView(labelled(R.string.air_you_name, nameField), nameParams);
        // E21: a photo that could not be set is said here, under it, until the next try.
        photoNote = AirScreenKit.note(context, "");
        photoNote.setVisibility(View.GONE);
        AirScreenKit.addSection(column, null, null, nameRow, photoNote,
                                labelled(R.string.air_you_status, statusField));

        // 3. Details: the ID in its parts, the antispam and the protection of the profile.
        final LinearLayout parts = AirScreenKit.plate(context);
        parts.setPaddingRelative(cardPad, cardPad, cardPad, cardPad);
        keyPart = addPart(parts, R.string.air_you_part_key);
        antispamPart = addPart(parts, R.string.air_you_part_antispam);
        checkPart = addPart(parts, R.string.air_you_part_check);
        // Until Tox is up there is no ID: the parts say so.
        setPart(keyPart, R.string.air_you_part_key, null);
        setPart(antispamPart, R.string.air_you_part_antispam, null);
        setPart(checkPart, R.string.air_you_part_check, null);
        final AirSettingRow antispam = new AirSettingRow(context, context.getString(R.string.air_you_antispam), false);
        antispam.setOnClickListener(v -> actions.changeAntispam());
        final AirSettingRow security = new AirSettingRow(context, context.getString(R.string.air_you_security), true);
        security.setOnClickListener(v -> actions.openSecurity());
        AirScreenKit.addSection(column, context.getString(R.string.air_you_details), null, parts,
                                AirScreenKit.plateOf(context, antispam, security));

        // 4. Log out, with what it means said before it is pressed.
        final TextView logout = AirScreenKit.button(context, context.getString(R.string.air_you_logout), false);
        logout.setMaxLines(3);
        logout.setOnClickListener(v -> actions.logOut());
        AirScreenKit.addSection(column, null, context.getString(logoutMeaning(passwordSet)), logout);

        // 5. Deleting the profile, set apart at the very bottom.
        final TextView delete = AirScreenKit.button(context, context.getString(R.string.air_you_delete), false);
        delete.setTextColor(AirUi.color(context, R.color.kq_danger));
        delete.setMaxLines(3);
        delete.setOnClickListener(v -> actions.deleteProfile());
        final LinearLayout deleteSection = AirScreenKit.addSection(column, null, null, delete);
        ((LinearLayout.LayoutParams) deleteSection.getLayoutParams()).topMargin =
                res.getDimensionPixelSize(R.dimen.kq_space_48);
    }

    /**
     * What logging out means for this profile: with a password it waits for that password; without one
     * the start screen leads back into it for anyone with the phone.
     */
    @StringRes
    static int logoutMeaning(final boolean passwordSet)
    {
        return passwordSet ? R.string.air_you_logout_message : R.string.air_you_logout_message_no_password;
    }

    /** E21: why the photo did not change, under the photo in the danger colour. */
    void sayPhotoFailure(final CharSequence text)
    {
        AirUi.sayOnLine(photoNote, text, true);
    }

    void endPhotoNote()
    {
        AirUi.endLine(photoNote);
    }

    /** The QR code and the three parts of the ID; null until Tox is up and there is an ID to show. */
    void bindId(@Nullable final String toxId)
    {
        if (TextUtils.equals(toxId, shownId))
        {
            return;
        }
        shownId = toxId;
        if (toxId == null || toxId.length() < ID_DIGITS)
        {
            // No QR at all rather than a placeholder someone could scan.
            qr.setImageDrawable(null);
            setPart(keyPart, R.string.air_you_part_key, null);
            setPart(antispamPart, R.string.air_you_part_antispam, null);
            setPart(checkPart, R.string.air_you_part_check, null);
            return;
        }
        Bitmap bitmap = null;
        try
        {
            // The same content as before the redesign, so every scanner that read it still does.
            bitmap = ProfileContentFragment.encodeAsBitmap("tox:" + toxId);
        }
        catch (Exception e)
        {
            Log.i(TAG, "qr:EE:" + e.getMessage());
        }
        if (bitmap != null)
        {
            // Square modules: drawn without smoothing when the card scales the code up.
            final BitmapDrawable drawable = new BitmapDrawable(context.getResources(), bitmap);
            drawable.setFilterBitmap(false);
            drawable.setAntiAlias(false);
            qr.setImageDrawable(drawable);
        }
        else
        {
            qr.setImageDrawable(null);
        }
        final String id = toxId.toUpperCase(java.util.Locale.ROOT);
        setPart(keyPart, R.string.air_you_part_key, id.substring(0, KEY_DIGITS));
        setPart(antispamPart, R.string.air_you_part_antispam, id.substring(KEY_DIGITS, KEY_DIGITS + ANTISPAM_DIGITS));
        setPart(checkPart, R.string.air_you_part_check, id.substring(KEY_DIGITS + ANTISPAM_DIGITS, ID_DIGITS));
    }

    /** The name and the status as Tox has them; a field being edited keeps what is typed in it. */
    void bindProfile(@Nullable final String name, @Nullable final String status)
    {
        setIfIdle(nameField, name);
        setIfIdle(statusField, status);
        // The tile carries our initials under the photo: they follow the name.
        bindPhoto(shownPhoto, name);
    }

    /** Our photo over the ink tile with our initials, as in the "you" block of the Air screen. */
    void bindPhoto(@Nullable final ProfileTabAvatarHelper.OwnPhoto own, @Nullable final String name)
    {
        final String title = TextUtils.isEmpty(name) ? context.getString(R.string.air_you) : name;
        if (TextUtils.equals(title, shownPhotoName) && java.util.Objects.equals(own, shownPhoto)
            && hasPhoto == (own != null))
        {
            return;
        }
        photo.bind(AirTileView.Kind.ME, title, 0);
        photo.showPhoto(own != null);
        ProfileTabAvatarHelper.loadOwnPhotoInto(photo.photoView(), context, own);
        hasPhoto = own != null;
        shownPhoto = own;
        shownPhotoName = title;
    }

    /**
     * Ends the editing of a field, which commits it: Done, leaving the tab or the screen. A tap on the
     * screen outside the fields ends it the same way, since the column then takes the focus.
     */
    void finishEditing()
    {
        if (nameField.hasFocus() || statusField.hasFocus())
        {
            column.requestFocus();
        }
    }

    private void hideKeyboard()
    {
        final InputMethodManager keyboard = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null)
        {
            keyboard.hideSoftInputFromWindow(column.getWindowToken(), 0);
        }
    }

    private static void setIfIdle(final EditText field, @Nullable final String text)
    {
        final String next = text == null ? "" : text;
        if (!field.hasFocus() && !TextUtils.equals(field.getText(), next))
        {
            field.setText(next);
        }
    }

    /**
     * A framed one-line field, sunken at rest and raised with an ink line while it has the focus, that
     * keeps to what Tox carries and hands its text over when the editing ends, as AirYouField on iOS.
     */
    private EditText field(@StringRes final int placeholder, final int maxBytes, final int textFlags,
                           final Commit commit)
    {
        final Resources res = context.getResources();
        final EditText input = new EditText(context);
        input.setId(View.generateViewId());
        TextViewCompat.setTextAppearance(input, R.style.TextAppearance_KQ_Body);
        input.setTextColor(AirUi.color(context, R.color.kq_ink));
        input.setHintTextColor(AirUi.color(context, R.color.kq_ink_3));
        input.setHint(placeholder);
        input.setBackgroundResource(R.drawable.air_bg_field);
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_12);
        final int vertical = res.getDimensionPixelSize(R.dimen.kq_space_8);
        input.setPaddingRelative(side, vertical, side, vertical);
        input.setMinHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android));
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        // One paragraph: without the multi-line flag the text stays one line of Tox text and Enter stays
        // the Done action, but it wraps instead of scrolling sideways, so a long status or a large font
        // keeps the whole text, and the whole placeholder, in view.
        input.setInputType(InputType.TYPE_CLASS_TEXT | textFlags);
        input.setHorizontallyScrolling(false);
        input.setMaxLines(FIELD_MAX_LINES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setFilters(new InputFilter[]{new AirUtf8Limit(maxBytes)});
        input.setOnEditorActionListener((v, actionId, event) -> {
            // Enter on a hardware keyboard comes as IME_NULL with its key event; without this it
            // would move the focus on to the next field instead of ending the editing.
            final boolean enterKey = event != null && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                                       || event.getKeyCode() == KeyEvent.KEYCODE_NUMPAD_ENTER);
            if (actionId != EditorInfo.IME_ACTION_DONE && !enterKey)
            {
                return false;
            }
            finishEditing();
            return true;
        });
        input.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus)
            {
                return;
            }
            // The keyboard stays only when the focus went on to the other field.
            if (!nameField.hasFocus() && !statusField.hasFocus())
            {
                hideKeyboard();
            }
            commit.commit(input.getText().toString());
        });
        return input;
    }

    /** A field under its label, which TalkBack reads as the name of the field. */
    private LinearLayout labelled(@StringRes final int label, final EditText field)
    {
        final LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView heading = AirScreenKit.heading(context, context.getString(label));
        heading.setLabelFor(field.getId());
        box.addView(heading, AirScreenKit.matchWidth());
        final LinearLayout.LayoutParams fieldParams = AirScreenKit.matchWidth();
        fieldParams.topMargin = context.getResources().getDimensionPixelSize(R.dimen.kq_space_8);
        box.addView(field, fieldParams);
        return box;
    }

    private TextView addPart(final LinearLayout parts, @StringRes final int title)
    {
        final Resources res = context.getResources();
        final LinearLayout part = new LinearLayout(context);
        part.setOrientation(LinearLayout.VERTICAL);
        // TalkBack reads the name of the part and its digits as one.
        ViewCompat.setScreenReaderFocusable(part, true);
        final TextView caption = AirScreenKit.text(context, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_3);
        caption.setText(title);
        caption.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        part.addView(caption, AirScreenKit.matchWidth());
        final TextView value = AirScreenKit.text(context, R.style.TextAppearance_KQ_Mono, R.color.kq_ink);
        // Left to right in every language, as the ID is typed and read out.
        value.setTextDirection(View.TEXT_DIRECTION_LTR);
        value.setTextIsSelectable(false);
        final LinearLayout.LayoutParams valueParams = AirScreenKit.matchWidth();
        valueParams.topMargin = res.getDimensionPixelSize(R.dimen.kq_space_2);
        part.addView(value, valueParams);
        final LinearLayout.LayoutParams partParams = AirScreenKit.matchWidth();
        partParams.topMargin = parts.getChildCount() == 0 ? 0 : res.getDimensionPixelSize(R.dimen.kq_space_12);
        parts.addView(part, partParams);
        return value;
    }

    private void setPart(final TextView value, @StringRes final int title, @Nullable final String digits)
    {
        if (digits == null)
        {
            value.setText(R.string.myid_not_ready);
            value.setContentDescription(null);
            return;
        }
        value.setText(AirConversationModel.keyLines(digits, 4));
        value.setContentDescription(context.getString(title) + ": " + AirConversationModel.keyLines(digits, 0));
    }

    /** The QR card: a square as wide as its place allows, and no wider than the design's maximum. */
    @SuppressLint("ViewConstructor")
    private static final class SquareCard extends FrameLayout
    {
        private final int maxSide;

        SquareCard(final Context context, final int maxSide)
        {
            super(context);
            this.maxSide = maxSide;
        }

        @Override
        protected void onMeasure(final int widthMeasureSpec, final int heightMeasureSpec)
        {
            final int available = MeasureSpec.getSize(widthMeasureSpec);
            final int side = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? maxSide
                                                                                               : Math.min(available, maxSide);
            final int exact = MeasureSpec.makeMeasureSpec(side, MeasureSpec.EXACTLY);
            super.onMeasure(exact, exact);
        }
    }
}
