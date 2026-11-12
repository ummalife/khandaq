package com.zoffcc.applications.trifa;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.AccessibilityDelegateCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.widget.TextViewCompat;

import com.zoffcc.applications.sorm.FriendList;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (redesign, E11): New contact, as AirNewContactController on iOS and design/DESIGN.md "New contact
 * and requests on the phone". The ID field cleans what is pasted and shows it in groups of four; the
 * ruler under it counts key, antispam and check, the check computed for real; the verdict says what is
 * wrong or what happens next; "Send request" waits on a bar above the keyboard with the reason it is off.
 * After sending, the screen says the request is on its way and turns to "Request accepted" when the
 * person comes online. Replaces the fork's add-contact screen.
 */
public final class AirNewContactActivity extends AirScreenActivity
{
    /** An ID, or the start of one, to fill the field with (the command line hands it over). */
    static final String EXTRA_ID = "org.khandaq.messenger.NEW_CONTACT_ID";

    private static final long WATCH_MS = 2000L;
    private static final int NAME_CHECKS = 15;
    private static final String STATE_SENT = "air_nc_sent_key";

    private EditText idField;
    private EditText messageField;
    private TextView paste;
    private TextView clear;
    private AirIdRulerView ruler;
    private TextView verdictText;
    private TextView verdictLink;
    private View verdictLinkLine;
    private TextView messageError;
    /** E21: why a scan gave no ID (no camera, no ID in the code), under the Scan row. */
    private TextView scanNote;
    private TextView why;
    private TextView sendError;
    private TextView send;
    private LinearLayout bottom;

    private String ownAddress = "";
    private AirNewContactModel.Verdict verdict;
    private boolean formatting = false;
    /** The face the ID field shows now: mono for a typed ID, the body face for the hint (null: not set yet). */
    private Boolean idFieldMono = null;
    private String lastHex = "";

    @Nullable
    private String sentKey;
    private AirPathView sentPath;
    private TextView sentTitle;
    private TextView sentText;
    private boolean accepted = false;
    /** After "Request accepted" the name follows a moment later; it is looked for this many more times. */
    private int nameChecksLeft = NAME_CHECKS;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable watch = this::watchFriend;

    private final ActivityResultLauncher<String> cameraPermission =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted)
                {
                    launchScan();
                }
                else
                {
                    sayAtScan(R.string.air_nc_scan_needs_camera, false);
                }
            });

    private final ActivityResultLauncher<Intent> scanner =
            registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null)
                {
                    applyQr(result.getData().getStringExtra(QrScanActivity.EXTRA_RESULT));
                    return;
                }
                final String error = result.getData() == null
                                     ? null : result.getData().getStringExtra(QrScanActivity.EXTRA_ERROR);
                if (QrScanActivity.ERROR_PERMISSION.equals(error))
                {
                    sayAtScan(R.string.air_nc_scan_needs_camera, false);
                }
                else if (QrScanActivity.ERROR_CAMERA.equals(error))
                {
                    sayAtScan(R.string.air_nc_scan_camera_failed, true);
                }
            });

    private final ActivityResultLauncher<String> photoPicker =
            registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
                if (uri == null)
                {
                    return;
                }
                QrImageDecoder.decodeFromUri(this, uri, new QrImageDecoder.Callback()
                {
                    @Override
                    public void onSuccess(final String value)
                    {
                        runOnUiThread(() -> applyQr(value));
                    }

                    @Override
                    public void onFailure()
                    {
                        runOnUiThread(() -> applyQr(null));
                    }
                });
            });

    @Override
    protected int screenTitle()
    {
        return R.string.air_new_contact;
    }

    @Override
    protected void onCreate(@Nullable final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        ownAddress = TRIFAGlobals.global_my_toxid == null ? "" : TRIFAGlobals.global_my_toxid;
        buildForm();
        buildSendBar();
        final String sent = savedInstanceState == null ? null : savedInstanceState.getString(STATE_SENT);
        if (sent != null)
        {
            showSent(sent, false);
            return;
        }
        final String prefill = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ID);
        final boolean empty = TextUtils.isEmpty(prefill);
        // An ID handed over is checked as if pasted; an empty field takes the keyboard at once.
        setId(AirNewContactModel.clean(prefill), !empty);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                     | (empty ? WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                                              : WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN));
        if (empty)
        {
            idField.requestFocus();
        }
    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState)
    {
        super.onSaveInstanceState(outState);
        if (sentKey != null)
        {
            outState.putString(STATE_SENT, sentKey);
        }
    }

    @Override
    protected void onResume()
    {
        super.onResume();
        if (sentKey != null && (!accepted || nameChecksLeft > 0))
        {
            handler.removeCallbacks(watch);
            handler.post(watch);
        }
    }

    @Override
    protected void onPause()
    {
        handler.removeCallbacks(watch);
        super.onPause();
    }

    // ---- the form ----

    private void buildForm()
    {
        final Resources res = getResources();
        final TextView idLabel = AirScreenKit.heading(this, getString(R.string.air_nc_id_label));
        // Visible-password text keeps the keyboard from suggesting words in an ID.
        idField = field(R.style.TextAppearance_KQ_MonoEntry,
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                        getString(R.string.air_nc_id_placeholder), res.getDimensionPixelSize(R.dimen.kq_size_id_field));
        // The heading names the field for TalkBack: an edit text reads its text, not a description.
        idField.setId(R.id.air_nc_id_field);
        idLabel.setLabelFor(idField.getId());
        idField.setHorizontallyScrolling(false);
        idField.setMaxLines(Integer.MAX_VALUE);
        idField.setImeOptions(EditorInfo.IME_ACTION_DONE);
        // The ID runs left to right in every language, like the ruler under it.
        idField.setTextDirection(View.TEXT_DIRECTION_LTR);
        idField.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        applyIdFieldFace(idField.getText());
        idField.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after)
            {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count)
            {
            }

            @Override
            public void afterTextChanged(final Editable editable)
            {
                applyIdFieldFace(editable);
                if (!formatting)
                {
                    regroup(editable);
                }
            }
        });

        paste = quietButton(getString(R.string.air_nc_paste), R.color.kq_ink);
        paste.setOnClickListener(v -> pasteId());
        clear = quietButton(getString(R.string.air_nc_clear), R.color.kq_ink);
        clear.setOnClickListener(v -> {
            setId("", false);
            idField.requestFocus();
        });
        final LinearLayout fieldActions = new LinearLayout(this);
        fieldActions.setOrientation(LinearLayout.HORIZONTAL);
        fieldActions.setGravity(Gravity.END);
        fieldActions.addView(paste);
        fieldActions.addView(clear);

        ruler = new AirIdRulerView(this);
        verdictText = AirScreenKit.text(this, R.style.TextAppearance_KQ_Caption, R.color.kq_ink_2);
        verdictLink = quietButton(getString(R.string.air_nc_open_chat), R.color.kq_ink);
        verdictLink.setOnClickListener(v -> followVerdict());
        verdictLinkLine = AirScreenKit.leading(verdictLink);

        final View scan = AirScreenKit.iconButton(this, getString(R.string.air_nc_scan), R.drawable.kq_ic_scan);
        scan.setOnClickListener(v -> scanQr());
        final View photo = AirScreenKit.button(this, getString(R.string.air_nc_scan_photo), false);
        photo.setOnClickListener(v -> photoPicker.launch("image/*"));
        final View scanRow = AirScreenKit.buttonRow(this, scan, photo);
        scanNote = AirScreenKit.note(this, "");
        scanNote.setVisibility(View.GONE);

        AirScreenKit.addSection(column, null, null, idLabel, idField, fieldActions, ruler, verdictText,
                                verdictLinkLine, scanRow, scanNote);

        messageField = field(R.style.TextAppearance_KQ_Body,
                             InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                             | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                             getString(R.string.air_nc_greeting), res.getDimensionPixelSize(R.dimen.kq_size_field));
        messageField.setMaxLines(6);
        messageField.setId(R.id.air_nc_message_field);
        final TextView messageLabel = AirScreenKit.heading(this, getString(R.string.air_nc_message_label));
        messageLabel.setLabelFor(messageField.getId());
        messageField.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after)
            {
            }

            @Override
            public void onTextChanged(final CharSequence s, final int start, final int before, final int count)
            {
            }

            @Override
            public void afterTextChanged(final Editable editable)
            {
                refreshVerdict(false);
            }
        });
        messageError = AirScreenKit.text(this, R.style.TextAppearance_KQ_Caption, R.color.kq_danger);
        messageError.setText(R.string.air_nc_message_too_long);
        messageError.setVisibility(View.GONE);
        AirScreenKit.addSection(column, null, getString(R.string.air_nc_message_note), messageLabel, messageField,
                                messageError);

        final String own = AirNewContactModel.clean(ownAddress);
        final TextView ownId = AirScreenKit.text(this, R.style.TextAppearance_KQ_Mono, R.color.kq_ink_2);
        ownId.setTextDirection(View.TEXT_DIRECTION_LTR);
        ownId.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
        ownId.setText(AirConversationModel.keyLines(own, 4));
        ownId.setContentDescription(getString(R.string.air_nc_own_title) + ": " + AirConversationModel.keyLines(own, 0));
        final View copy = AirScreenKit.iconButton(this, getString(R.string.air_nc_copy), R.drawable.kq_ic_copy);
        copy.setOnClickListener(v -> {
            final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && !own.isEmpty())
            {
                clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.air_nc_own_title), own));
                toast(R.string.air_nc_copied);
            }
        });
        AirScreenKit.addSection(column, getString(R.string.air_nc_own_title), getString(R.string.air_nc_own_note),
                                ownId, AirScreenKit.leading(copy));
    }

    private void buildSendBar()
    {
        bottom = findViewById(R.id.air_screen_bottom);
        why = AirScreenKit.note(this, "");
        sendError = AirScreenKit.text(this, R.style.TextAppearance_KQ_Caption, R.color.kq_danger);
        sendError.setVisibility(View.GONE);
        send = AirScreenKit.button(this, getString(R.string.air_nc_send), true);
        send.setOnClickListener(v -> sendRequest());
        // TalkBack hears why the button is off as its hint, as VoiceOver does on iOS.
        ViewCompat.setAccessibilityDelegate(send, new AccessibilityDelegateCompat()
        {
            @Override
            public void onInitializeAccessibilityNodeInfo(final View host, final AccessibilityNodeInfoCompat info)
            {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(Button.class.getName());
                if (!host.isEnabled() && why.getVisibility() == View.VISIBLE)
                {
                    info.setHintText(why.getText());
                }
            }
        });
        final int gap = getResources().getDimensionPixelSize(R.dimen.kq_space_6);
        final LinearLayout.LayoutParams errorParams = AirScreenKit.matchWidth();
        errorParams.bottomMargin = gap;
        bottom.addView(sendError, errorParams);
        bottom.addView(send, AirScreenKit.matchWidth());
        final LinearLayout.LayoutParams whyParams = AirScreenKit.matchWidth();
        whyParams.topMargin = gap;
        bottom.addView(why, whyParams);
        bottom.setVisibility(View.VISIBLE);
    }

    private EditText field(final int appearance, final int inputType, final CharSequence hint, final int minHeight)
    {
        final EditText field = new EditText(this);
        // The input type first: a password variation sets the system monospace, the appearance then wins.
        field.setInputType(inputType);
        TextViewCompat.setTextAppearance(field, appearance);
        field.setTextColor(AirUi.color(this, R.color.kq_ink));
        field.setHintTextColor(AirUi.color(this, R.color.kq_ink_3));
        field.setHint(hint);
        field.setBackgroundResource(R.drawable.air_bg_input);
        final int pad = getResources().getDimensionPixelSize(R.dimen.kq_space_12);
        field.setPaddingRelative(pad, pad, pad, pad);
        field.setMinHeight(minHeight);
        field.setGravity(Gravity.TOP | Gravity.START);
        return field;
    }

    /**
     * Mono only for the ID itself (the brief: mono for IDs and keys); the hint is a sentence, so the empty
     * field shows it in the body face.
     */
    private void applyIdFieldFace(final CharSequence text)
    {
        final boolean mono = text != null && text.length() > 0;
        if (idFieldMono != null && idFieldMono == mono)
        {
            return;
        }
        idFieldMono = mono;
        TextViewCompat.setTextAppearance(idField, mono ? R.style.TextAppearance_KQ_MonoEntry
                                                       : R.style.TextAppearance_KQ_Body);
    }

    private TextView quietButton(final CharSequence label, final int colorRes)
    {
        final TextView button = AirScreenKit.text(this, R.style.TextAppearance_KQ_Label, colorRes);
        button.setText(label);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setMinHeight(getResources().getDimensionPixelSize(R.dimen.kq_size_target_android));
        final int side = getResources().getDimensionPixelSize(R.dimen.kq_space_8);
        button.setBackgroundResource(R.drawable.air_bg_icon_button);
        button.setPaddingRelative(side, 0, side, 0);
        button.setClickable(true);
        button.setFocusable(true);
        AirUi.markAsButton(button);
        return button;
    }

    // ---- the ID ----

    /** Keeps the field in groups of four while typing, the caret after the same character as before. */
    private void regroup(final Editable editable)
    {
        final String raw = editable.toString();
        final int caret = Math.max(0, Math.min(idField.getSelectionEnd(), raw.length()));
        final String hex = AirNewContactModel.clean(raw);
        int before = 0;
        for (int i = 0; i < caret; i++)
        {
            if (Character.digit(raw.charAt(i), 16) >= 0)
            {
                before++;
            }
        }
        final String grouped = AirNewContactModel.grouped(hex);
        if (!grouped.equals(raw))
        {
            formatting = true;
            editable.replace(0, editable.length(), grouped);
            formatting = false;
            idField.setSelection(caretOffset(Math.min(before, hex.length()), hex.length(), grouped.length()));
        }
        refreshVerdict(true);
    }

    private void setId(final String hex, final boolean fromUser)
    {
        final String grouped = AirNewContactModel.grouped(hex);
        formatting = true;
        idField.setText(grouped);
        formatting = false;
        idField.setSelection(grouped.length());
        refreshVerdict(fromUser);
    }

    /** Where the caret goes in the grouped text after this many characters of the ID. */
    static int caretOffset(final int hexBefore, final int hexTotal, final int groupedLength)
    {
        int offset = hexBefore + Math.max(0, (hexBefore - 1) / 4);
        if (hexBefore > 0 && hexBefore % 4 == 0 && hexBefore < hexTotal)
        {
            offset++;
        }
        return Math.min(offset, groupedLength);
    }

    private void pasteId()
    {
        final ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip() || clipboard.getPrimaryClip() == null
            || clipboard.getPrimaryClip().getItemCount() == 0)
        {
            return;
        }
        final CharSequence text = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this);
        if (!TextUtils.isEmpty(text))
        {
            setId(AirNewContactModel.clean(text.toString()), true);
        }
    }

    private void scanQr()
    {
        endScanNote();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        {
            launchScan();
        }
        else
        {
            cameraPermission.launch(Manifest.permission.CAMERA);
        }
    }

    private void launchScan()
    {
        scanner.launch(new Intent(this, QrScanActivity.class));
    }

    private void applyQr(@Nullable final String text)
    {
        final String hex = AirNewContactModel.clean(text);
        if (hex.length() != AirNewContactModel.LENGTH)
        {
            sayAtScan(R.string.air_nc_wrong_qr, false);
            return;
        }
        setId(hex, true);
    }

    /** E21: a scan that gave no ID says why under the Scan row, until the ID changes or a new scan starts. */
    private void sayAtScan(final int words, final boolean failed)
    {
        if (scanNote == null || isFinishing())
        {
            return;
        }
        AirUi.sayOnLine(scanNote, getString(words), failed);
    }

    private void endScanNote()
    {
        if (scanNote != null && scanNote.getVisibility() != View.GONE)
        {
            AirUi.endLine(scanNote);
        }
    }

    // ---- the verdict ----

    private AirNewContactModel.Verdict decide(final String hex)
    {
        return AirNewContactModel.Verdict.of(hex, ownAddress,
                                             key -> HelperFriend.lookup_friendlist_by_pubkey(key) != null,
                                             AirNewContactActivity::hasRequest,
                                             AirBlockList::isBlocked);
    }

    private static boolean hasRequest(final String publicKey)
    {
        for (AirRequestStore.Pending pending : AirRequestStore.snapshot())
        {
            if (publicKey.equalsIgnoreCase(pending.publicKey))
            {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    private void refreshVerdict(final boolean fromUser)
    {
        if (idField == null || send == null)
        {
            return;
        }
        final String hex = AirNewContactModel.clean(idField.getText().toString());
        if (!hex.equals(lastHex))
        {
            // An error from the last attempt belongs to that ID.
            sendError.setVisibility(View.GONE);
            endScanNote();
            lastHex = hex;
        }
        final AirNewContactModel.Verdict previous = verdict;
        verdict = decide(hex);
        paste.setVisibility(hex.isEmpty() ? View.VISIBLE : View.GONE);
        clear.setVisibility(hex.isEmpty() ? View.GONE : View.VISIBLE);
        ruler.update(hex.length(), verdict.checkState(), previous != null);
        if (fromUser && verdict.kind == AirNewContactModel.Verdict.Kind.BAD_CHECK
            && (previous == null || previous.kind != AirNewContactModel.Verdict.Kind.BAD_CHECK))
        {
            ruler.shakeCheck();
        }

        int link = 0;
        final String words;
        final int color;
        switch (verdict.kind)
        {
            case EMPTY:
                words = getString(R.string.air_nc_verdict_empty);
                color = R.color.kq_ink_3;
                break;
            case PARTIAL:
                words = getResources().getQuantityString(R.plurals.air_nc_verdict_left, verdict.left, verdict.left);
                color = R.color.kq_ink_2;
                break;
            case BAD_CHECK:
                words = getString(R.string.air_nc_verdict_bad);
                color = R.color.kq_danger;
                break;
            case OWN:
                words = getString(R.string.air_nc_verdict_own);
                color = R.color.kq_danger;
                break;
            case CONTACT:
                words = getString(R.string.air_nc_verdict_contact_format, contactName(verdict.publicKey));
                color = R.color.kq_ink_2;
                link = R.string.air_nc_open_chat;
                break;
            case THEY_ASKED:
                words = getString(R.string.air_nc_verdict_they_asked);
                color = R.color.kq_ink_2;
                link = R.string.air_nc_open_requests;
                break;
            case BLOCKED:
                words = getString(R.string.air_nc_verdict_blocked);
                color = R.color.kq_ink_2;
                break;
            default:
                words = getString(R.string.air_nc_verdict_ok);
                color = R.color.kq_live;
                break;
        }
        verdictText.setText(AirUi.localDigits(this, words));
        verdictText.setTextColor(AirUi.color(this, color));
        verdictLinkLine.setVisibility(link == 0 ? View.GONE : View.VISIBLE);
        if (link != 0)
        {
            verdictLink.setText(link);
        }

        final boolean tooLong = AirNewContactModel.messageBytes(messageField.getText().toString())
                                > AirNewContactModel.MAX_MESSAGE_BYTES;
        messageError.setVisibility(tooLong ? View.VISIBLE : View.GONE);
        final boolean ready = verdict.address() != null && !tooLong;
        send.setEnabled(ready);
        final int reason;
        switch (verdict.kind)
        {
            case OWN:
                reason = R.string.air_nc_why_own;
                break;
            case BAD_CHECK:
                reason = R.string.air_nc_why_fix;
                break;
            case CONTACT:
                reason = R.string.air_nc_why_contact;
                break;
            case THEY_ASKED:
                reason = R.string.air_nc_why_they_asked;
                break;
            case OK:
            case BLOCKED:
                reason = tooLong ? R.string.air_nc_message_too_long : 0;
                break;
            default:
                reason = R.string.air_nc_why_whole;
                break;
        }
        why.setVisibility(ready || reason == 0 ? View.GONE : View.VISIBLE);
        if (reason != 0)
        {
            why.setText(AirUi.localDigits(this, getString(reason)));
        }
        if (fromUser && previous != null && group(previous) != group(verdict) && group(verdict) != 0)
        {
            verdictText.announceForAccessibility(words);
        }
    }

    /** The verdicts TalkBack announces when one turns into another: not every character typed. */
    private static int group(final AirNewContactModel.Verdict verdict)
    {
        switch (verdict.kind)
        {
            case EMPTY:
            case PARTIAL:
                return 0;
            case BAD_CHECK:
                return 1;
            case OWN:
                return 2;
            case CONTACT:
            case THEY_ASKED:
                return 3;
            default:
                return 4;
        }
    }

    private String contactName(@Nullable final String publicKey)
    {
        final FriendList friend = publicKey == null ? null : HelperFriend.lookup_friendlist_by_pubkey(publicKey);
        if (friend == null)
        {
            return getString(R.string.air_nc_them);
        }
        final String alias = friend.alias_name == null ? "" : friend.alias_name.trim();
        if (!alias.isEmpty())
        {
            return alias;
        }
        final String name = friend.name == null ? "" : friend.name.trim();
        return name.isEmpty() || HelperFriend.is_placeholder_friend_name(name, publicKey)
               ? getString(R.string.air_nc_them) : name;
    }

    private void followVerdict()
    {
        if (verdict == null)
        {
            return;
        }
        if (verdict.kind == AirNewContactModel.Verdict.Kind.CONTACT && verdict.publicKey != null)
        {
            FriendListHolder.show_messagelist_acticvity_for_friend(this, verdict.publicKey);
            finish();
        }
        else if (verdict.kind == AirNewContactModel.Verdict.Kind.THEY_ASKED)
        {
            final Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            intent.putExtra(MainActivity.EXTRA_OPEN_REQUESTS, true);
            startActivity(intent);
            finish();
        }
    }

    // ---- sending ----

    @SuppressWarnings("deprecation")
    private void sendRequest()
    {
        refreshVerdict(false);
        final String address = verdict == null ? null : verdict.address();
        if (address == null || !send.isEnabled())
        {
            return;
        }
        final String message = AirNewContactModel.requestMessage(messageField.getText().toString(),
                                                                 getString(R.string.air_nc_greeting));
        final String error = HelperFriend.send_friend_request(address, message);
        if (error != null)
        {
            // Said where the action is, not in a dialog (E21).
            sendError.setText(error);
            sendError.setVisibility(View.VISIBLE);
            sendError.announceForAccessibility(error);
            return;
        }
        showSent(address.substring(0, AirNewContactModel.KEY_LENGTH), true);
    }

    @SuppressWarnings("deprecation")
    private void showSent(final String key, final boolean animated)
    {
        sentKey = key;
        bottom.setVisibility(View.GONE);
        final View focused = getCurrentFocus();
        if (focused != null)
        {
            final android.view.inputmethod.InputMethodManager keyboard =
                    (android.view.inputmethod.InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null)
            {
                keyboard.hideSoftInputFromWindow(focused.getWindowToken(), 0);
            }
            focused.clearFocus();
        }
        column.removeAllViews();
        final String them = getString(R.string.air_nc_them);
        sentPath = new AirPathView(this);
        sentPath.configure(AirConversationModel.LinkState.NONE, "?", them);
        sentPath.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        sentPath.setContentDescription(getString(R.string.air_nc_path_label_format, them));
        sentTitle = AirScreenKit.text(this, R.style.TextAppearance_KQ_Heading, R.color.kq_ink);
        sentTitle.setText(R.string.air_nc_sent_title);
        ViewCompat.setAccessibilityHeading(sentTitle, true);
        sentText = AirScreenKit.body(this, getString(R.string.air_nc_sent_text));
        final TextView done = AirScreenKit.button(this, getString(R.string.air_nc_done), true);
        done.setOnClickListener(v -> finish());
        AirScreenKit.addSection(column, null, null, sentPath, sentTitle, sentText, done);
        if (animated)
        {
            column.setAlpha(0f);
            column.animate().alpha(1f).setDuration(AirUi.duration(this, R.integer.kq_dur_reveal)).start();
            sentTitle.announceForAccessibility(getString(R.string.air_nc_sent_title));
        }
        handler.removeCallbacks(watch);
        handler.postDelayed(watch, WATCH_MS);
    }

    /** While the screen is open, the line turns solid when the person accepts and comes online. */
    @SuppressWarnings("deprecation")
    private void watchFriend()
    {
        if (sentKey == null || isFinishing() || (accepted && nameChecksLeft <= 0))
        {
            return;
        }
        final FriendList friend = HelperFriend.lookup_friendlist_by_pubkey(sentKey);
        if (!accepted && (friend == null || friend.TOX_CONNECTION == AirModel.TOX_CONNECTION_NONE))
        {
            handler.postDelayed(watch, WATCH_MS);
            return;
        }
        final boolean first = !accepted;
        accepted = true;
        final String name = contactName(sentKey);
        // The name comes with the contact's first packets, often a moment after the connection: until
        // then "them" stands in, and the screen is filled again once it is there.
        final boolean nameKnown = !name.equals(getString(R.string.air_nc_them));
        nameChecksLeft = nameKnown ? 0 : nameChecksLeft - 1;
        if (nameKnown || first)
        {
            final int connection = friend == null ? AirModel.TOX_CONNECTION_NONE : friend.TOX_CONNECTION;
            sentPath.configure(connection == AirModel.TOX_CONNECTION_UDP
                               ? AirConversationModel.LinkState.DIRECT : AirConversationModel.LinkState.RELAY,
                               AirModel.Initials.from(name), name);
            sentPath.setContentDescription(getString(R.string.air_nc_path_live_format, name));
            sentTitle.setText(R.string.air_nc_accepted_title);
            sentText.setText(getString(R.string.air_nc_accepted_text_format, name));
        }
        if (first)
        {
            sentTitle.announceForAccessibility(getString(R.string.air_nc_accepted_title));
        }
        if (nameChecksLeft > 0)
        {
            handler.postDelayed(watch, WATCH_MS);
        }
    }
}
