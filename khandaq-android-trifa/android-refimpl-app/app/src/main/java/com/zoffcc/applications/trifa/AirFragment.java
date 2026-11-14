package com.zoffcc.applications.trifa;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.animation.AnimationUtils;
import android.view.animation.Interpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.ViewCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ConcatAdapter;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.snackbar.Snackbar;

import org.khandaq.messenger.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * KHANDAQ (redesign, design/redesign/02-plan.md, screens 1 and 2): the phone's one main screen.
 * You and your node at the top, the command line, the filter pills, then one list of requests,
 * people, groups and saved messages split by who can be reached right now.
 */
public class AirFragment extends Fragment
        implements AirRepository.Listener, AirRequestStore.Listener, AirInviteStore.Listener, AirAdapter.Listener,
                   AirAdapter.EmptyListener, AirAdapter.CommandIdListener
{
    private static final String STATE_FILTER = "air_filter";
    private static final long TICK_MS = 60_000L;
    private static final long SEARCH_DEBOUNCE_MS = 150L;
    private static final int UNDO_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<AirModel.Filter, Pill> pills = new EnumMap<>(AirModel.Filter.class);

    private View topRow;
    private View you;
    private AirTileView youTile;
    private TextView youName;
    private View netDot;
    private TextView netText;
    private EditText command;
    private ImageButton commandClear;
    /**
     * KHANDAQ (redesign stage 4, E19): set by "Add the first person" on the "Your ID is ready" page, so
     * Air comes back with the command line in focus and the keyboard up.
     */
    static volatile boolean focusCommandOnResume = false;
    private TextView cancel;
    private HorizontalScrollView pillsScroll;
    private RecyclerView list;
    private AirAdapter adapter;
    private AirAdapter.EmptyFooter footer;
    private AirAdapter.CommandIdRow commandIdRow;
    /** KHANDAQ (redesign, E3): "Join the group with this key" for a group key or a group link on the line. */
    private GroupKeyRow groupKeyRow;
    /** The group key on the command line now, or null; linked when it came as a group link, never a person's key. */
    @Nullable
    private String groupKey;
    private boolean groupKeyLinked;
    /** The key being joined now (its row says so, and a second tap starts nothing), and the one whose join failed. */
    @Nullable
    private String joiningKey;
    @Nullable
    private String failedKey;
    /** "Paste ID" in the empty, focused command line while the clipboard holds text; it reads it only on a tap. */
    private TextView commandPaste;
    /** Why Paste ID put nothing on the line, under it until the line changes. */
    private TextView commandRefusal;
    @Nullable
    private ClipboardManager clipboard;
    private final ClipboardManager.OnPrimaryClipChangedListener clipChanged = this::updatePasteButton;
    /** Back from another app where an ID was copied: the clipboard may hold text now. */
    private final ViewTreeObserver.OnWindowFocusChangeListener windowFocus = hasFocus -> {
        if (hasFocus)
        {
            updatePasteButton();
        }
    };
    private DefaultItemAnimator itemAnimator;
    private Interpolator easeOut;

    private AirRepository.Data data = AirRepository.Data.EMPTY;
    private List<AirModel.MessageHit> hits = Collections.emptyList();
    private String hitsQuery = "";
    private AirModel.Filter filter = AirModel.Filter.ALL;
    private String query = "";
    private boolean searching;
    private boolean revealed;
    /** Rows are still arriving one after another; the list's own animations wait until they are in. */
    private boolean revealSettling;
    private ValueAnimator foldAnimator;
    private ProfileTabAvatarHelper.OwnPhoto boundPhoto;
    private String boundName;
    private AirModel.NetworkState boundNetwork;
    private AirModel.NetworkState boundDescriptionNetwork;
    private Snackbar undoBar;
    /** Cards declined while their toast still offers Undo: hidden, not yet removed (E12). */
    private final Set<String> declining = new HashSet<>();

    private final Runnable tick = new Runnable()
    {
        @Override
        public void run()
        {
            // Times and "3 min ago" age by themselves; nothing else tells the list to change.
            AirRepository.requestRefreshNow();
            handler.postDelayed(this, TICK_MS);
        }
    };

    private final Runnable search = () -> AirRepository.searchMessages(query);

    private static final class Pill
    {
        final View root;
        final TextView title;
        final AirRollingLabel count;

        Pill(final View root)
        {
            this.root = root;
            this.title = root.findViewById(R.id.air_pill_title);
            this.count = root.findViewById(R.id.air_pill_count);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull final LayoutInflater inflater, @Nullable final ViewGroup container,
                             @Nullable final Bundle savedInstanceState)
    {
        final View root = inflater.inflate(R.layout.fragment_air, container, false);
        final Context context = root.getContext();
        easeOut = AnimationUtils.loadInterpolator(context, R.interpolator.kq_ease_out);
        if (savedInstanceState != null)
        {
            filter = AirModel.Filter.fromName(savedInstanceState.getString(STATE_FILTER, ""));
        }

        topRow = root.findViewById(R.id.air_top_row);
        you = root.findViewById(R.id.air_you);
        youTile = root.findViewById(R.id.air_you_tile);
        youName = root.findViewById(R.id.air_you_name);
        netDot = root.findViewById(R.id.air_net_dot);
        netText = root.findViewById(R.id.air_net_text);
        command = root.findViewById(R.id.air_command);
        // At the largest font the hint does not fit on its line: it ends in an ellipsis, not after a word.
        AirUi.fitHint(command, R.string.air_command_placeholder);
        commandClear = root.findViewById(R.id.air_command_clear);
        cancel = root.findViewById(R.id.air_cancel);
        pillsScroll = root.findViewById(R.id.air_pills_scroll);
        list = root.findViewById(R.id.air_list);

        AirUi.markAsButton(you);
        AirUi.markAsButton(cancel);
        ViewCompat.replaceAccessibilityAction(you, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                                              getString(R.string.air_you_hint), null);
        you.setOnClickListener(v -> {
            final MainActivity activity = mainActivity();
            if (activity != null)
            {
                activity.open_profile_activity();
            }
        });
        // KHANDAQ (redesign, E4): the line under the name opens the state of our node in words.
        root.findViewById(R.id.air_net_row).setOnClickListener(v -> AirNetworkActivity.open(requireContext()));
        ViewCompat.addAccessibilityAction(you, getString(R.string.air_settings_network), (view, arguments) -> {
            AirNetworkActivity.open(requireContext());
            return true;
        });
        root.findViewById(R.id.air_settings).setOnClickListener(v -> {
            final MainActivity activity = mainActivity();
            if (activity != null)
            {
                activity.open_settings_screen();
            }
        });
        root.findViewById(R.id.air_create).setOnClickListener(v -> showCreateSheet());

        setUpCommandLine();
        setUpPills(inflater, root.findViewById(R.id.air_pills));
        setUpList(context);
        bindYou();
        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle outState)
    {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_FILTER, filter.accessibilityName);
    }

    @Override
    public void onStart()
    {
        super.onStart();
        configureItemAnimator();
        AirRequestStore.addListener(this);
        AirInviteStore.addListener(this);
        AirRepository.start(requireContext(), this);
        handler.removeCallbacks(tick);
        handler.postDelayed(tick, TICK_MS);
        if (clipboard != null)
        {
            clipboard.addPrimaryClipChangedListener(clipChanged);
        }
        updatePasteButton();
    }

    @Override
    public void onResume()
    {
        super.onResume();
        if (focusCommandOnResume && command != null && !isHidden())
        {
            focusCommandOnResume = false;
            // The window gets the focus back only after "Your ID is ready" has gone; the keyboard waits for it.
            AirStepKit.showKeyboard(command);
        }
    }

    @Override
    public void onStop()
    {
        super.onStop();
        AirRepository.stop(this);
        AirRequestStore.removeListener(this);
        AirInviteStore.removeListener(this);
        handler.removeCallbacks(tick);
        handler.removeCallbacks(search);
        if (clipboard != null)
        {
            clipboard.removePrimaryClipChangedListener(clipChanged);
        }
    }

    @Override
    public void onDestroyView()
    {
        super.onDestroyView();
        if (foldAnimator != null)
        {
            foldAnimator.cancel();
            foldAnimator = null;
        }
        if (undoBar != null)
        {
            undoBar.dismiss();
            undoBar = null;
        }
        list = null;
        adapter = null;
        footer = null;
        commandIdRow = null;
        groupKeyRow = null;
        revealSettling = false;
        pills.clear();
        // New views start blank: the next data must bind everything again, even if it is the same.
        data = AirRepository.Data.EMPTY;
        boundName = null;
        boundPhoto = null;
        boundNetwork = null;
        boundDescriptionNetwork = null;
    }

    @Override
    public void onHiddenChanged(final boolean hidden)
    {
        super.onHiddenChanged(hidden);
        if (!hidden)
        {
            AirRepository.requestRefreshNow();
        }
        else if (command != null && command.hasFocus())
        {
            command.clearFocus();
            hideKeyboard();
        }
    }

    /** Back on Air: an active search or filter goes first, the way iOS's Cancel does. */
    boolean handleBackPress()
    {
        if (searching)
        {
            cancelSearch();
            return true;
        }
        if (filter != AirModel.Filter.ALL)
        {
            applyFilter(AirModel.Filter.ALL);
            return true;
        }
        return false;
    }

    /** New contact's "Open Requests": the Requests filter, a search ended first. */
    void showRequests()
    {
        if (searching && command != null)
        {
            cancelSearch();
        }
        applyFilter(AirModel.Filter.REQUESTS);
    }

    @Nullable
    private MainActivity mainActivity()
    {
        return getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
    }

    // ---- header ----

    /**
     * The "you" block. Data arrives often (every message, every file tick), so only what changed is
     * touched: setting the same text again would still tell TalkBack the screen changed.
     */
    private void bindYou()
    {
        if (you == null || !isAdded())
        {
            return;
        }
        final Context context = requireContext();
        final AirRepository.Data current = data;
        final String name = current.selfName.isEmpty() ? getString(R.string.air_you) : current.selfName;
        final boolean nameChanged = !name.equals(boundName);
        final boolean photoChanged = !Objects.equals(current.selfPhoto, boundPhoto);
        if (nameChanged || photoChanged)
        {
            youName.setText(name);
            youTile.bind(AirTileView.Kind.ME, name, 0);
            if (current.selfPhoto != null)
            {
                youTile.showPhoto(true);
                if (photoChanged)
                {
                    ProfileTabAvatarHelper.loadOwnPhotoInto(youTile.photoView(), context, current.selfPhoto);
                }
            }
            else if (boundPhoto != null)
            {
                ProfileTabAvatarHelper.loadOwnPhotoInto(youTile.photoView(), context, null);
            }
            boundName = name;
            boundPhoto = current.selfPhoto;
        }

        final AirModel.NetworkState network = current.network;
        if (network != boundNetwork)
        {
            bindNetwork(context, network, boundNetwork != null);
            boundNetwork = network;
        }
        if (nameChanged || network != boundDescriptionNetwork)
        {
            you.setContentDescription(getString(R.string.air_you) + ", " + name + ", "
                                      + AirUi.networkText(context, network));
            boundDescriptionNetwork = network;
        }
    }

    private void bindNetwork(final Context context, final AirModel.NetworkState network, final boolean animated)
    {
        final String words = AirUi.networkText(context, network);
        final int dotColor;
        final int textColor;
        switch (network)
        {
            case DIRECT:
            case RELAY:
                dotColor = R.color.kq_live;
                textColor = R.color.kq_ink_2;
                break;
            case OFFLINE:
                dotColor = R.color.kq_danger;
                textColor = R.color.kq_danger;
                break;
            case CONNECTING:
            default:
                dotColor = R.color.kq_ink_3;
                textColor = R.color.kq_ink_2;
                break;
        }
        ViewCompat.setBackgroundTintList(netDot, ColorStateList.valueOf(AirUi.color(context, dotColor)));
        netText.animate().cancel();
        if (animated && !AirUi.reducedMotion() && netText.isLaidOut())
        {
            // The words change with a cross-fade; the dot just takes its new colour.
            final long half = AirUi.duration(context, R.integer.kq_dur_state) / 2;
            netText.animate().alpha(0f).setDuration(half).setInterpolator(easeOut).withEndAction(() -> {
                netText.setText(words);
                netText.setTextColor(AirUi.color(context, textColor));
                netText.animate().alpha(1f).setDuration(half).setInterpolator(easeOut).start();
            }).start();
        }
        else
        {
            netText.setAlpha(1f);
            netText.setText(words);
            netText.setTextColor(AirUi.color(context, textColor));
        }
    }

    // ---- command line ----

    private void setUpCommandLine()
    {
        command.addTextChangedListener(new TextWatcher()
        {
            @Override
            public void beforeTextChanged(final CharSequence text, final int start, final int count, final int after)
            {
            }

            @Override
            public void onTextChanged(final CharSequence text, final int start, final int before, final int count)
            {
            }

            @Override
            public void afterTextChanged(final Editable text)
            {
                onCommandChanged(text.toString());
            }
        });
        command.setOnEditorActionListener((view, actionId, event) -> {
            final boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                                  && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || enter)
            {
                // "Find" closes the keyboard and keeps the results.
                command.clearFocus();
                hideKeyboard();
                return true;
            }
            return false;
        });
        command.setOnFocusChangeListener((view, hasFocus) -> {
            updateSearching(true);
            updatePasteButton();
            if (!hasFocus)
            {
                hideCommandRefusal();
            }
        });
        commandClear.setOnClickListener(v -> command.setText(""));
        cancel.setOnClickListener(v -> cancelSearch());
        setUpPaste();
    }

    /**
     * KHANDAQ (redesign, E3): "Paste ID" in the command line, as Paste under the field of New contact.
     * Android tells whether the clipboard holds text without handing the text over, so the button stands
     * only then, and the text is read on the tap, never before it. When it holds no ID, the line says so
     * under itself.
     */
    private void setUpPaste()
    {
        final Context context = command.getContext();
        final Resources res = context.getResources();
        final ViewGroup box = (ViewGroup) commandClear.getParent();
        commandPaste = AirScreenKit.text(context, R.style.TextAppearance_KQ_Label, R.color.kq_ink);
        commandPaste.setText(R.string.air_cmd_paste_id);
        commandPaste.setBackgroundResource(R.drawable.air_bg_icon_button);
        commandPaste.setGravity(Gravity.CENTER);
        commandPaste.setSingleLine(true);
        commandPaste.setMinHeight(res.getDimensionPixelSize(R.dimen.kq_size_target_android));
        final int side = res.getDimensionPixelSize(R.dimen.kq_space_8);
        commandPaste.setPaddingRelative(side, 0, side, 0);
        commandPaste.setClickable(true);
        commandPaste.setFocusable(true);
        commandPaste.setVisibility(View.GONE);
        AirUi.markAsButton(commandPaste);
        commandPaste.setOnClickListener(v -> pasteId());
        box.addView(commandPaste, box.indexOfChild(commandClear),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                                                  ViewGroup.LayoutParams.WRAP_CONTENT));

        // The refusal stands under the line it is about, above the pills.
        final View row = (View) box.getParent();
        final ViewGroup header = (ViewGroup) row.getParent();
        commandRefusal = AirScreenKit.text(context, R.style.TextAppearance_KQ_Caption, R.color.kq_danger);
        final int gutter = res.getDimensionPixelSize(R.dimen.kq_space_16);
        commandRefusal.setPaddingRelative(gutter, res.getDimensionPixelSize(R.dimen.kq_space_4), gutter, 0);
        commandRefusal.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        commandRefusal.setVisibility(View.GONE);
        header.addView(commandRefusal, header.indexOfChild(row) + 1, AirScreenKit.matchWidth());

        clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        // The window's observer, not the view's: it is the one that outlives this view, so it is left again.
        command.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener()
        {
            @Override
            public void onViewAttachedToWindow(final View view)
            {
                view.getViewTreeObserver().addOnWindowFocusChangeListener(windowFocus);
            }

            @Override
            public void onViewDetachedFromWindow(final View view)
            {
                view.getViewTreeObserver().removeOnWindowFocusChangeListener(windowFocus);
            }
        });
    }

    /** Paste ID stands in the empty, focused line while the clipboard holds text. */
    private void updatePasteButton()
    {
        if (commandPaste == null || command == null)
        {
            return;
        }
        final boolean show = command.hasFocus() && command.length() == 0 && clipboardHasText();
        commandPaste.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    /** Whether the clipboard holds text, from its description alone: the text itself is not read here. */
    private boolean clipboardHasText()
    {
        if (clipboard == null)
        {
            return false;
        }
        try
        {
            if (!clipboard.hasPrimaryClip())
            {
                return false;
            }
            final ClipDescription description = clipboard.getPrimaryClipDescription();
            return description != null && description.hasMimeType("text/*");
        }
        catch (RuntimeException e)
        {
            // Without the input focus the clipboard is not ours to look at: nothing to paste then.
            return false;
        }
    }

    /** The tap on Paste ID: the clipboard is read now, and an ID, a group key or a group link goes on the line. */
    private void pasteId()
    {
        final Context context = getContext();
        if (context == null || command == null)
        {
            return;
        }
        CharSequence text = null;
        try
        {
            if (clipboard != null && clipboard.hasPrimaryClip())
            {
                final ClipData clip = clipboard.getPrimaryClip();
                if (clip != null && clip.getItemCount() > 0)
                {
                    text = clip.getItemAt(0).coerceToText(context);
                }
            }
        }
        catch (RuntimeException e)
        {
            text = null;
        }
        final String line = AirNewContactModel.pastedCommand(text, ShareActivity::parseKhandaqGroupId);
        if (line == null)
        {
            showCommandRefusal(R.string.air_cmd_paste_none);
            return;
        }
        command.setText(line);
        command.setSelection(command.length());
    }

    private void showCommandRefusal(final int words)
    {
        if (commandRefusal == null)
        {
            return;
        }
        commandRefusal.setText(words);
        commandRefusal.setVisibility(View.VISIBLE);
    }

    private void hideCommandRefusal()
    {
        if (commandRefusal != null && commandRefusal.getVisibility() != View.GONE)
        {
            commandRefusal.setVisibility(View.GONE);
        }
    }

    private void onCommandChanged(final String raw)
    {
        commandClear.setVisibility(raw.isEmpty() ? View.GONE : View.VISIBLE);
        hideCommandRefusal();
        updatePasteButton();
        // KHANDAQ (redesign, E3): a group link ("khandaq:group:" and the key, the QR of a group dossier) is a
        // group and nothing else: the line offers to join it, not a contact with that ID, and searches nothing.
        final String linkedKey = ShareActivity.parseKhandaqGroupId(raw.trim());
        if (linkedKey != null)
        {
            showCommandIdRow(null);
            showGroupKeyRow(linkedKey, true);
            setQuery("");
            return;
        }
        final AirModel.Command parsed = AirModel.Command.parse(raw);
        if (parsed.type == AirModel.Command.Type.CONTACT_ADDRESS)
        {
            showGroupKeyRow(null, false);
            // A whole ID: it is a new contact, not a search. Leave the line clean behind us.
            final String address = parsed.value;
            handler.post(() -> {
                if (command == null)
                {
                    return;
                }
                command.setText("");
                command.clearFocus();
                hideKeyboard();
                final MainActivity activity = mainActivity();
                if (activity != null)
                {
                    activity.open_add_friend(address);
                }
            });
            return;
        }
        final String hex = parsed.type == AirModel.Command.Type.PARTIAL_ADDRESS ? parsed.value : null;
        showCommandIdRow(hex);
        // 64 characters are the length of a group key too: the line offers that group as well.
        showGroupKeyRow(hex != null && hex.length() == AirNewContactModel.KEY_LENGTH ? hex : null, false);
        setQuery(parsed.type == AirModel.Command.Type.QUERY || parsed.type == AirModel.Command.Type.PARTIAL_ADDRESS
                 ? parsed.value : "");
    }

    /** The row "New contact with this ID" for the start of an ID, with what is missing or wrong so far. */
    private void showCommandIdRow(@Nullable final String hex)
    {
        if (commandIdRow == null)
        {
            return;
        }
        if (hex == null)
        {
            commandIdRow.show(null, null);
            return;
        }
        final String own = TRIFAGlobals.global_my_toxid == null ? "" : TRIFAGlobals.global_my_toxid;
        final String note;
        switch (AirNewContactModel.commandNote(hex, own, key -> HelperFriend.lookup_friendlist_by_pubkey(key) != null))
        {
            case LEFT:
                final int left = Math.max(0, AirNewContactModel.LENGTH - hex.length());
                note = getResources().getQuantityString(R.plurals.air_cmd_id_left, left, left);
                break;
            case BAD:
                note = getString(R.string.air_cmd_id_bad);
                break;
            case OWN:
                note = getString(R.string.air_cmd_id_own);
                break;
            case CONTACT:
                note = getString(R.string.air_cmd_id_contact);
                break;
            default:
                note = getString(R.string.air_cmd_id_ok);
                break;
        }
        commandIdRow.show(hex, AirUi.localDigits(requireContext(), note));
    }

    @Override
    public void onCommandIdRow(final String hex)
    {
        cancelSearch();
        final MainActivity activity = mainActivity();
        if (activity != null)
        {
            activity.open_add_friend(hex);
        }
    }

    /**
     * KHANDAQ (redesign, E3): "Join the group with this key" for a group key or link on the command line, or
     * no row for null. A bare 64-character key that is ours or a contact's is a person's, not a group's.
     */
    private void showGroupKeyRow(@Nullable final String key, final boolean linked)
    {
        if (key == null || !key.equals(failedKey))
        {
            failedKey = null;
        }
        groupKey = key;
        groupKeyLinked = linked;
        refreshGroupKeyRow();
    }

    /** The row's words follow the key: our membership, a join under way or failed, the contacts online now. */
    private void refreshGroupKeyRow()
    {
        if (groupKeyRow == null || !isAdded())
        {
            return;
        }
        final String key = groupKey;
        if (key == null || (!groupKeyLinked && isPersonKey(key)))
        {
            groupKeyRow.show(null, "", "", false, false);
            return;
        }
        final String title;
        final String note;
        boolean failed = false;
        final boolean member = HelperGroup.is_member(key);
        if (member)
        {
            final String name = groupTitle(key);
            title = name.isEmpty() ? getString(R.string.air_cmd_group_open) : name;
            note = getString(R.string.air_cmd_group_member);
        }
        else
        {
            title = getString(R.string.air_cmd_join_group);
            if (key.equals(joiningKey))
            {
                note = getString(R.string.air_cmd_group_joining);
            }
            else if (key.equals(failedKey))
            {
                note = getString(R.string.air_cmd_group_failed);
                failed = true;
            }
            else
            {
                // Joining by key asks the contacts online for an invitation (HelperGroup, packet 184). With
                // nobody online it waits for one, and the row says so before the tap.
                final int online = contactsOnline();
                note = online > 0
                       ? getResources().getQuantityString(R.plurals.air_cmd_group_note_online, online, online)
                       : getString(R.string.air_cmd_group_note_nobody);
            }
        }
        groupKeyRow.show(key, title, AirUi.localDigits(requireContext(), note), failed, member);
    }

    /** Our own public key or a contact's: the start of an ID, never a group to join. */
    private static boolean isPersonKey(final String key)
    {
        final String own = TRIFAGlobals.global_my_toxid;
        if (own != null && own.length() >= AirNewContactModel.KEY_LENGTH
            && own.substring(0, AirNewContactModel.KEY_LENGTH).equalsIgnoreCase(key))
        {
            return true;
        }
        return HelperFriend.lookup_friendlist_by_pubkey(key) != null;
    }

    /** The contacts online now: a join by key asks them to let us in. */
    private int contactsOnline()
    {
        int online = 0;
        for (final AirModel.Entry entry : data.entries)
        {
            if ((entry.kind == AirModel.EntryKind.DIRECT || entry.kind == AirModel.EntryKind.CONTACT)
                && entry.reach != null && entry.reach.isLive())
            {
                online++;
            }
        }
        return online;
    }

    /** The group's name as the list shows it, or "" while the list does not have the group. */
    private String groupTitle(final String key)
    {
        for (final AirModel.Entry entry : data.entries)
        {
            final AirRepository.Source source = data.sources.get(entry.id);
            if (source != null && source.group != null && key.equalsIgnoreCase(source.group.group_identifier))
            {
                return entry.title == null ? "" : entry.title;
            }
        }
        return "";
    }

    /** The tap on the group row: a group we are in opens; any other is joined by its key, off the main thread. */
    private void onGroupKeyRow(final String key)
    {
        final Context context = getContext();
        if (context == null)
        {
            return;
        }
        if (HelperGroup.is_member(key))
        {
            cancelSearch();
            GroupListHolder.open_group_chat(context, key.toLowerCase(Locale.ROOT));
            return;
        }
        if (key.equals(joiningKey))
        {
            return;
        }
        joiningKey = key;
        failedKey = null;
        refreshGroupKeyRow();
        // Android's join by ID (HelperGroup.join_public_group_by_id): a cold join, then the contacts online
        // are asked for an invitation and the first one back is taken ("KHANDAQ (join by ID)" in
        // MainActivity and GroupInviteAcceptPolicy). It waits for the node, so it runs off the main thread.
        new Thread(() -> {
            final boolean joined = HelperGroup.join_public_group_by_id(key, null, false);
            handler.post(() -> onGroupJoined(key, joined));
        }, "air-join-group").start();
    }

    private void onGroupJoined(final String key, final boolean joined)
    {
        if (key.equals(joiningKey))
        {
            joiningKey = null;
        }
        if (!joined)
        {
            failedKey = key;
            refreshGroupKeyRow();
            return;
        }
        final Context context = getContext();
        if (context == null || !isResumed() || isHidden())
        {
            // Joined while Air is not in front: the group waits in the list, nothing opens over another screen.
            refreshGroupKeyRow();
            return;
        }
        if (key.equals(groupKey))
        {
            cancelSearch();
        }
        GroupListHolder.open_group_chat(context, key.toLowerCase(Locale.ROOT));
    }

    private void setQuery(final String next)
    {
        if (next.equals(query))
        {
            return;
        }
        query = next;
        handler.removeCallbacks(search);
        if (query.isEmpty())
        {
            hits = Collections.emptyList();
            hitsQuery = "";
            AirRepository.searchMessages("");
        }
        else
        {
            handler.postDelayed(search, SEARCH_DEBOUNCE_MS);
        }
        updateSearching(true);
        render();
        if (list != null)
        {
            list.scrollToPosition(0);
        }
    }

    private void cancelSearch()
    {
        command.setText("");
        command.clearFocus();
        hideKeyboard();
        setQuery("");
        updateSearching(true);
    }

    private void hideKeyboard()
    {
        final Context context = getContext();
        if (context == null || command == null)
        {
            return;
        }
        final InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null)
        {
            imm.hideSoftInputFromWindow(command.getWindowToken(), 0);
        }
    }

    /** Searching is a focused line or a query on it: the "you" row folds away and Cancel comes in. */
    private void updateSearching(final boolean animated)
    {
        final boolean next = command.hasFocus() || !query.isEmpty();
        if (next == searching)
        {
            return;
        }
        searching = next;
        cancel.setVisibility(searching ? View.VISIBLE : View.GONE);
        foldTopRow(searching, animated);
    }

    private void foldTopRow(final boolean fold, final boolean animated)
    {
        if (foldAnimator != null)
        {
            foldAnimator.cancel();
            foldAnimator = null;
        }
        final ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) topRow.getLayoutParams();
        final int margin = getResources().getDimensionPixelSize(R.dimen.kq_space_4);
        final int width = ((View) topRow.getParent()).getWidth();
        final int from = topRow.getHeight();
        final int to;
        if (fold)
        {
            to = 0;
        }
        else
        {
            topRow.measure(View.MeasureSpec.makeMeasureSpec(Math.max(0, width), View.MeasureSpec.EXACTLY),
                           View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            to = topRow.getMeasuredHeight();
        }

        if (!animated || !topRow.isLaidOut() || width == 0)
        {
            applyFold(params, fold, fold ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, fold ? 0 : margin, fold ? 0f : 1f);
            return;
        }
        if (AirUi.reducedMotion())
        {
            // No sliding: the row is replaced at once and only fades, briefly.
            applyFold(params, fold, fold ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, fold ? 0 : margin, 0f);
            if (!fold)
            {
                topRow.animate().alpha(1f).setDuration(AirUi.duration(requireContext(), R.integer.kq_dur_state)).start();
            }
            return;
        }

        topRow.setVisibility(View.VISIBLE);
        final float alphaFrom = topRow.getAlpha();
        final float alphaTo = fold ? 0f : 1f;
        final int marginFrom = params.topMargin;
        final int marginTo = fold ? 0 : margin;
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(AirUi.duration(requireContext(), R.integer.kq_dur_layout));
        animator.setInterpolator(easeOut);
        animator.addUpdateListener(animation -> {
            final float t = (float) animation.getAnimatedValue();
            params.height = Math.round(from + (to - from) * t);
            params.topMargin = Math.round(marginFrom + (marginTo - marginFrom) * t);
            topRow.setLayoutParams(params);
            topRow.setAlpha(alphaFrom + (alphaTo - alphaFrom) * t);
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter()
        {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(final android.animation.Animator animation)
            {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(final android.animation.Animator animation)
            {
                if (!cancelled)
                {
                    applyFold(params, fold, fold ? 0 : ViewGroup.LayoutParams.WRAP_CONTENT, marginTo, alphaTo);
                }
            }
        });
        foldAnimator = animator;
        animator.start();
    }

    private void applyFold(final ViewGroup.MarginLayoutParams params, final boolean fold, final int height,
                           final int margin, final float alpha)
    {
        params.height = height;
        params.topMargin = margin;
        topRow.setLayoutParams(params);
        topRow.setAlpha(alpha);
        // Folded, the row leaves the accessibility tree and focus order too.
        topRow.setVisibility(fold ? View.INVISIBLE : View.VISIBLE);
    }

    // ---- pills ----

    private void setUpPills(final LayoutInflater inflater, final LinearLayout container)
    {
        container.removeAllViews();
        final Context context = container.getContext();
        for (final AirModel.Filter pillFilter : AirModel.Filter.PILLS)
        {
            final View view = inflater.inflate(R.layout.air_pill, container, false);
            final Pill pill = new Pill(view);
            pill.title.setText(pillTitle(pillFilter));
            pill.count.configure(R.style.TextAppearance_KQ_Air_CaptionStrong, AirUi.color(context, R.color.kq_ink));
            AirUi.markAsToggle(view);
            view.setOnClickListener(v -> applyFilter(filter == pillFilter ? AirModel.Filter.ALL : pillFilter));
            view.setTag(pillFilter.accessibilityName);
            container.addView(view);
            pills.put(pillFilter, pill);
        }
        bindPills(new AirModel.Counts(0, 0), false);
    }

    private static int pillTitle(final AirModel.Filter pillFilter)
    {
        switch (pillFilter)
        {
            case REQUESTS:
                return R.string.air_filter_requests;
            case TRANSIT:
                return R.string.air_filter_transit;
            case FAVORITES:
            default:
                // The key iOS uses for this pill; its words differ from the saved chat's name in every language.
                return R.string.chat_filter_favorites;
        }
    }

    private void bindPills(final AirModel.Counts counts, final boolean animated)
    {
        final Context context = getContext();
        if (context == null)
        {
            return;
        }
        for (final Map.Entry<AirModel.Filter, Pill> item : pills.entrySet())
        {
            final AirModel.Filter pillFilter = item.getKey();
            final Pill pill = item.getValue();
            final int count = pillFilter == AirModel.Filter.REQUESTS ? counts.requests
                              : pillFilter == AirModel.Filter.TRANSIT ? counts.transit : 0;
            final boolean selected = filter == pillFilter;
            pill.root.setSelected(selected);

            final int badge;
            final int badgeText;
            if (selected)
            {
                badge = R.color.kq_ink_badge;
                badgeText = R.color.kq_on_ink;
            }
            else if (pillFilter == AirModel.Filter.TRANSIT)
            {
                badge = R.color.kq_transit_soft;
                badgeText = R.color.kq_transit;
            }
            else
            {
                badge = R.color.kq_sunken;
                badgeText = R.color.kq_ink;
            }
            ViewCompat.setBackgroundTintList(pill.count, ColorStateList.valueOf(AirUi.color(context, badge)));
            pill.count.setTextColor(AirUi.color(context, badgeText));
            final String shown = AirUi.countText(context, count);
            if (count > 0)
            {
                final boolean wasVisible = pill.count.getVisibility() == View.VISIBLE;
                pill.count.setVisibility(View.VISIBLE);
                pill.count.setCount(count, shown, animated && wasVisible);
            }
            else
            {
                pill.count.setVisibility(View.GONE);
                pill.count.setCount(0, shown, false);
            }
            pill.root.setContentDescription(pill.title.getText() + (count > 0 ? ", " + shown : ""));
        }
    }

    private void applyFilter(final AirModel.Filter next)
    {
        if (next == filter)
        {
            return;
        }
        filter = next;
        render();
        if (list != null)
        {
            list.scrollToPosition(0);
        }
        final Pill pill = pills.get(next);
        if (pill != null)
        {
            // The scroller brings the chosen pill fully into view.
            pill.root.post(() -> pill.root.requestRectangleOnScreen(
                    new Rect(0, 0, pill.root.getWidth(), pill.root.getHeight()), false));
        }
        else if (pillsScroll != null)
        {
            final boolean rtl = pillsScroll.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            pillsScroll.smoothScrollTo(rtl ? pillsScroll.getChildAt(0).getWidth() : 0, 0);
        }
    }

    // ---- list ----

    private void setUpList(final Context context)
    {
        adapter = new AirAdapter(this);
        footer = new AirAdapter.EmptyFooter(this);
        commandIdRow = new AirAdapter.CommandIdRow(this);
        groupKeyRow = new GroupKeyRow(this::onGroupKeyRow);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(new ConcatAdapter(commandIdRow, groupKeyRow, adapter, footer));
        itemAnimator = new DefaultItemAnimator();
        // A changed row rebinds in place: no cross-fade, so nothing blinks.
        itemAnimator.setSupportsChangeAnimations(false);
        configureItemAnimator();
        // Replaces RecyclerView's built-in animator, which cross-fades every changed row.
        list.setItemAnimator(itemAnimator);
        list.addOnItemTouchListener(new FilterSwipe(context));
    }

    private void configureItemAnimator()
    {
        if (itemAnimator == null || getContext() == null)
        {
            return;
        }
        final Context context = requireContext();
        final boolean reduced = AirUi.reducedMotion();
        // Reduced motion: rows jump to their new place; arrivals and departures only fade, briefly.
        itemAnimator.setMoveDuration(reduced ? 0L : AirUi.duration(context, R.integer.kq_dur_layout));
        itemAnimator.setAddDuration(AirUi.duration(context, R.integer.kq_dur_state));
        itemAnimator.setRemoveDuration(AirUi.duration(context, R.integer.kq_dur_state));
        itemAnimator.setChangeDuration(0L);
    }

    private void render()
    {
        if (adapter == null || !isAdded())
        {
            return;
        }
        bindYou();
        final AirRepository.Data current = data;
        if (!current.loaded)
        {
            // Nothing read yet: no "nobody here" before the database has answered.
            return;
        }
        final String needle = query.trim();
        final List<AirModel.MessageHit> visibleHits = !needle.isEmpty() && needle.equals(hitsQuery)
                                                      ? hits : Collections.<AirModel.MessageHit>emptyList();
        final List<AirModel.Request> requests = new ArrayList<>(current.requests.size());
        for (final AirModel.Request request : current.requests)
        {
            if (!declining.contains(request.id))
            {
                requests.add(request);
            }
        }
        final AirModel.Snapshot snapshot = AirModel.ListBuilder.build(current.entries, requests, visibleHits,
                                                                      filter, needle);
        final Map<String, AirModel.Entry> byId = new HashMap<>();
        for (final AirModel.Entry entry : current.entries)
        {
            byId.put(entry.id, entry);
        }
        adapter.setContext(current.photos, byId);

        final boolean firstLoad = !revealed && !snapshot.rows.isEmpty();
        if (firstLoad)
        {
            // The first load is staggered by hand; the item animator would fade the same rows in.
            list.setItemAnimator(null);
        }
        else if (list.getItemAnimator() == null && revealed && !revealSettling)
        {
            list.setItemAnimator(itemAnimator);
        }
        // A list at its top stays at its top when rows move: a contact coming online moves above the rows
        // in view, and the layout would keep the old first row in place, leaving the new top and its
        // section heading above the edge.
        final boolean atTop = list != null && !list.canScrollVertically(-1);
        adapter.submitList(snapshot.rows, () -> {
            if (adapter == null)
            {
                return;
            }
            adapter.setQuery(needle);
            if (atTop && list != null)
            {
                list.scrollToPosition(0);
            }
            if (firstLoad && !revealed)
            {
                revealed = true;
                revealOnFirstDraw();
            }
        });
        // While "New contact with this ID" or the group row stands over the list, "nothing found" says nothing new.
        final boolean idRow = (commandIdRow != null && commandIdRow.shown())
                              || (groupKeyRow != null && groupKeyRow.shown());
        footer.setState(idRow && snapshot.emptyState == AirModel.EmptyState.NO_MATCH ? null : snapshot.emptyState);
        bindPills(snapshot.counts, true);
    }

    /** Rows come in one after another, once, on the first load only. */
    private void revealOnFirstDraw()
    {
        final RecyclerView target = list;
        if (target == null)
        {
            return;
        }
        revealSettling = true;
        target.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener()
        {
            @Override
            public boolean onPreDraw()
            {
                target.getViewTreeObserver().removeOnPreDrawListener(this);
                long settled = 0L;
                if (!AirUi.reducedMotion())
                {
                    final Context context = target.getContext();
                    final float rise = context.getResources().getDimension(R.dimen.air_reveal_rise);
                    final long duration = AirUi.duration(context, R.integer.kq_dur_state);
                    final long step = context.getResources().getInteger(R.integer.kq_dur_stagger);
                    for (int i = 0; i < target.getChildCount(); i++)
                    {
                        final View child = target.getChildAt(i);
                        final long delay = Math.min(i, 5) * step;
                        settled = Math.max(settled, delay + duration);
                        child.setAlpha(0f);
                        child.setTranslationY(rise);
                        child.animate().alpha(1f).translationY(0f).setStartDelay(delay).setDuration(duration)
                                .setInterpolator(easeOut).setListener(new RevealEnd(child)).start();
                    }
                }
                // The list's own animations come back once every row has arrived; until then an update
                // simply rebinds in place instead of cutting a row's arrival short.
                target.postDelayed(() -> {
                    if (list == target)
                    {
                        revealSettling = false;
                        target.setItemAnimator(itemAnimator);
                    }
                }, settled);
                return true;
            }
        });
    }

    /**
     * Whatever ends a row's arrival, the row is left fully in place, and the view's animator forgets the
     * arrival's delay and listener (both stick to a ViewPropertyAnimator and would leak into the list's
     * own moves and fades).
     */
    private static final class RevealEnd extends android.animation.AnimatorListenerAdapter
    {
        private final View row;

        RevealEnd(final View row)
        {
            this.row = row;
        }

        @Override
        public void onAnimationCancel(final android.animation.Animator animation)
        {
            settle();
        }

        @Override
        public void onAnimationEnd(final android.animation.Animator animation)
        {
            settle();
        }

        private void settle()
        {
            row.setAlpha(1f);
            row.setTranslationY(0f);
            row.animate().setStartDelay(0L).setListener(null);
        }
    }

    /**
     * A sideways swipe on the list moves to the neighbouring filter, as on iOS. Never while
     * searching, never past either end, and only when the gesture is clearly horizontal.
     */
    private final class FilterSwipe implements RecyclerView.OnItemTouchListener
    {
        private final float minDistance;
        private final float minVelocity;
        private final int touchSlop;
        private VelocityTracker tracker;
        private float downX;
        private float downY;

        FilterSwipe(final Context context)
        {
            minDistance = context.getResources().getDimension(R.dimen.air_swipe_distance);
            minVelocity = context.getResources().getDimension(R.dimen.air_swipe_velocity);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        }

        @Override
        public boolean onInterceptTouchEvent(@NonNull final RecyclerView view, @NonNull final MotionEvent event)
        {
            switch (event.getActionMasked())
            {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    if (tracker != null)
                    {
                        tracker.recycle();
                    }
                    tracker = VelocityTracker.obtain();
                    tracker.addMovement(event);
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (tracker != null)
                    {
                        tracker.addMovement(event);
                    }
                    break;
                case MotionEvent.ACTION_UP:
                    if (tracker != null)
                    {
                        tracker.addMovement(event);
                        tracker.computeCurrentVelocity(1000);
                        finish(event.getX() - downX, event.getY() - downY, tracker.getXVelocity());
                    }
                    release();
                    break;
                case MotionEvent.ACTION_CANCEL:
                    release();
                    break;
                default:
                    break;
            }
            // Never take the gesture: taps, long presses and scrolling stay with the list.
            return false;
        }

        private void finish(final float dx, final float dy, final float vx)
        {
            if (searching || Math.abs(dx) < touchSlop)
            {
                return;
            }
            final boolean far = Math.abs(dx) >= minDistance || Math.abs(vx) >= minVelocity;
            if (!far || Math.abs(dx) <= Math.abs(dy) * 1.5f)
            {
                return;
            }
            final boolean rtl = list != null && list.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            final boolean swipingLeft = dx < 0;
            final AirModel.Filter next = AirModel.neighbourFilter(filter, swipingLeft != rtl ? 1 : -1);
            if (next != null)
            {
                applyFilter(next);
            }
        }

        private void release()
        {
            if (tracker != null)
            {
                tracker.recycle();
                tracker = null;
            }
        }

        @Override
        public void onTouchEvent(@NonNull final RecyclerView view, @NonNull final MotionEvent event)
        {
        }

        @Override
        public void onRequestDisallowInterceptTouchEvent(final boolean disallowIntercept)
        {
        }
    }

    // ---- data ----

    @Override
    public void onAirData(final AirRepository.Data next)
    {
        final boolean changed = !next.sameScreen(data);
        // Kept even when nothing on screen changes: its sources are what a tap opens.
        data = next;
        if (changed)
        {
            render();
        }
        if (groupKey != null)
        {
            // Who is online moves the group row's words; so does a join that has just gone through.
            refreshGroupKeyRow();
        }
    }

    @Override
    public void onAirMessageHits(final String forQuery, final List<AirModel.MessageHit> found)
    {
        if (!forQuery.equals(query.trim()))
        {
            return;
        }
        hits = found;
        hitsQuery = forQuery;
        render();
    }

    @Override
    public void onAirRequestsChanged()
    {
        AirRepository.requestRefresh();
    }

    @Override
    public void onAirInvitesChanged()
    {
        AirRepository.requestRefresh();
    }

    // ---- rows ----

    @Override
    public void onOpen(final AirModel.Entry entry)
    {
        openChat(entry.id);
    }

    @Override
    public void onOpenMessage(final AirModel.MessageHit hit)
    {
        openChat(hit.chatId);
    }

    private void openChat(final String id)
    {
        final Context context = getContext();
        if (context == null)
        {
            return;
        }
        if (AirRepository.SAVED_ID.equals(id))
        {
            FavoritesChatHelper.openChat(context);
            return;
        }
        final AirRepository.Source source = data.sources.get(id);
        if (source == null)
        {
            return;
        }
        if (source.friend != null)
        {
            FriendListHolder.show_messagelist_acticvity_for_friend(context, source.friend.tox_public_key_string);
        }
        else if (source.group != null)
        {
            GroupListHolder.open_group_chat(context, source.group.group_identifier);
        }
        else if (source.conference != null)
        {
            ConferenceListHolder.open_conference(context, source.conference);
        }
    }

    @Override
    public boolean onMenu(final View anchor, final AirModel.Entry entry)
    {
        final AirRepository.Source source = data.sources.get(entry.id);
        if (source == null)
        {
            return false;
        }
        if (source.friend != null)
        {
            return FriendListHolder.show_row_menu(anchor, source.friend);
        }
        if (source.group != null)
        {
            return GroupListHolder.show_row_menu(anchor, source.group);
        }
        if (source.conference != null)
        {
            return ConferenceListHolder.show_row_menu(anchor, source.conference);
        }
        return false;
    }

    @Override
    public void onAccept(final AirModel.Request request)
    {
        if (request.kind == AirModel.Request.Kind.GROUP_INVITE)
        {
            joinInvite(request);
            return;
        }
        // KHANDAQ (QA live, F2): marked first, so the new contact never reads as our own pending request.
        AirRequestStore.markAccepted(request.publicKey);
        HelperFriend.add_friend_to_system(request.publicKey, false, null);
        AirRequestStore.remove(request.publicKey);
        AirRepository.requestRefresh();
    }

    /**
     * Join goes through the contact who invites (toxcore's rule): while they are offline the card says so
     * and stays. Joined, the card goes and the group opens, as on iOS.
     */
    private void joinInvite(final AirModel.Request request)
    {
        final View root = getView();
        if (root == null)
        {
            return;
        }
        if (!request.inviterOnline)
        {
            AirToast.show(root, getString(R.string.air_invite_wait_format, request.inviterName));
            return;
        }
        final String id = request.id;
        new Thread(() -> {
            final AirInviteStore.Invite invite = AirInviteStore.find(id);
            final String joined = invite == null ? null : MainActivity.join_invite(invite);
            handler.post(() -> {
                if (joined != null)
                {
                    AirInviteStore.remove(id);
                    final android.app.Activity activity = getActivity();
                    if (activity != null && invite.kind == AirInviteStore.Kind.GROUP)
                    {
                        GroupListHolder.open_group_chat(activity, joined);
                    }
                    return;
                }
                final View current = getView();
                if (current != null)
                {
                    AirToast.show(current, getString(R.string.air_invite_join_failed));
                }
            });
        }, "air-invite-join").start();
    }

    /**
     * No question first (E12): the card leaves at once and a toast offers Undo for five seconds. The
     * request or invitation is removed only when the toast goes without Undo, also when another toast
     * takes its place; if the app is closed in those seconds, the card is back next time. Nothing is sent
     * to the other side.
     */
    @Override
    public void onDecline(final AirModel.Request request)
    {
        final View root = getView();
        if (root == null || declining.contains(request.id))
        {
            return;
        }
        final String id = request.id;
        final boolean invite = request.kind == AirModel.Request.Kind.GROUP_INVITE;
        final String publicKey = request.publicKey;
        declining.add(id);
        render();
        if (undoBar != null)
        {
            undoBar.dismiss();
        }
        undoBar = AirToast.make(root, getString(invite ? R.string.air_invite_declined : R.string.air_request_declined),
                                UNDO_MS)
                .setAction(R.string.air_undo, v -> { })
                .addCallback(new Snackbar.Callback()
                {
                    @Override
                    public void onDismissed(final Snackbar bar, final int event)
                    {
                        declining.remove(id);
                        if (event != DISMISS_EVENT_ACTION)
                        {
                            if (invite)
                            {
                                AirInviteStore.remove(id);
                            }
                            else
                            {
                                AirRequestStore.remove(publicKey);
                            }
                        }
                        if (undoBar == bar)
                        {
                            undoBar = null;
                        }
                        render();
                    }
                });
        undoBar.show();
    }

    @Override
    public void onEmptyAction(final AirModel.EmptyState state)
    {
        switch (state)
        {
            case NOTHING_YET:
            {
                final MainActivity activity = mainActivity();
                if (activity != null)
                {
                    activity.open_add_friend(null);
                }
                break;
            }
            case NO_MATCH:
                cancelSearch();
                break;
            default:
                applyFilter(AirModel.Filter.ALL);
                break;
        }
    }

    // ---- create sheet ----

    private void showCreateSheet()
    {
        final Context context = getContext();
        final MainActivity activity = mainActivity();
        if (context == null || activity == null)
        {
            return;
        }
        final BottomSheetDialog sheet = new BottomSheetDialog(context, R.style.ThemeOverlay_KQ_Air_Sheet);
        final View content = LayoutInflater.from(sheet.getContext()).inflate(R.layout.air_create_sheet, null, false);
        bindSheetAction(sheet, content, R.id.air_create_contact, () -> activity.open_add_friend(null));
        bindSheetAction(sheet, content, R.id.air_create_group, () -> activity.show_create_private_group(null));
        sheet.setContentView(content);
        final android.view.Window window = sheet.getWindow();
        if (window != null)
        {
            // The sheet's own window: its navigation bar continues the sheet instead of a black band.
            window.setNavigationBarColor(AirUi.color(context, R.color.kq_raised));
            androidx.core.view.WindowCompat.getInsetsController(window, window.getDecorView())
                    .setAppearanceLightNavigationBars(!HelperGeneric.is_nightmode_active(context));
        }
        sheet.show();
    }

    private static void bindSheetAction(final BottomSheetDialog sheet, final View content, final int id,
                                        final Runnable action)
    {
        final View row = content.findViewById(id);
        AirUi.markAsButton(row);
        row.setOnClickListener(v -> {
            // The sheet goes first, then the next screen comes in over Air.
            sheet.setOnDismissListener(dialog -> action.run());
            sheet.dismiss();
        });
    }

    // ---- the group key row (E3) ----

    /**
     * KHANDAQ (redesign, E3): the row over the list for a group key or a group link on the command line,
     * under "New contact with this ID" when both apply: the group's tile, "Join the group with this key"
     * (or the group's name when we are in it) and what the join will do. The row of AirAdapter.CommandIdRow
     * with its own words.
     */
    private static final class GroupKeyRow extends RecyclerView.Adapter<GroupKeyRow.Holder>
    {
        interface Listener
        {
            void onGroupKeyRow(String key);
        }

        private final Listener listener;
        @Nullable
        private String key;
        private String title = "";
        private String note = "";
        private boolean failed;
        private boolean member;

        GroupKeyRow(final Listener listener)
        {
            this.listener = listener;
        }

        /** Shows the row for this key with its words, or hides it for null. */
        void show(@Nullable final String nextKey, final String nextTitle, final String nextNote, final boolean nextFailed,
                  final boolean nextMember)
        {
            final boolean was = key != null;
            if (was && nextKey != null && nextKey.equals(key) && nextTitle.equals(title) && nextNote.equals(note)
                && nextFailed == failed && nextMember == member)
            {
                return;
            }
            key = nextKey;
            title = nextTitle;
            note = nextNote;
            failed = nextFailed;
            member = nextMember;
            if (was && nextKey == null)
            {
                notifyItemRemoved(0);
            }
            else if (!was && nextKey != null)
            {
                notifyItemInserted(0);
            }
            else if (nextKey != null)
            {
                notifyItemChanged(0);
            }
        }

        boolean shown()
        {
            return key != null;
        }

        @Override
        public int getItemCount()
        {
            return key == null ? 0 : 1;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull final ViewGroup parent, final int viewType)
        {
            return new Holder(LayoutInflater.from(parent.getContext()).inflate(R.layout.air_command_id_row, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull final Holder holder, final int position)
        {
            final Context context = holder.itemView.getContext();
            if (holder.title != null)
            {
                holder.title.setText(title);
            }
            holder.note.setText(note);
            holder.note.setTextColor(AirUi.color(context, failed ? R.color.kq_danger : R.color.kq_ink_3));
            // A group we are in has its own tile, as in the list; one to join is dashed with the group glyph.
            if (member && key != null)
            {
                holder.tile.bind(AirTileView.Kind.GROUP, title, AirModel.Tone.index(key));
            }
            else
            {
                holder.tile.bind(AirTileView.Kind.PENDING_GROUP, "", 0);
            }
            holder.itemView.setContentDescription(title + ", " + note);
        }

        final class Holder extends RecyclerView.ViewHolder
        {
            @Nullable
            final TextView title;
            final TextView note;
            final AirTileView tile;

            Holder(final View view)
            {
                super(view);
                note = view.findViewById(R.id.air_command_id_note);
                // What the join will do may take three lines in a long language.
                note.setMaxLines(3);
                title = titleNextTo(note);
                tile = view.findViewById(R.id.air_command_id_tile);
                AirUi.markAsButton(view);
                view.setOnClickListener(v -> {
                    if (key != null)
                    {
                        listener.onGroupKeyRow(key);
                    }
                });
            }
        }

        /** The title of air_command_id_row: the other text beside its note. */
        @Nullable
        private static TextView titleNextTo(final TextView note)
        {
            final ViewGroup texts = (ViewGroup) note.getParent();
            for (int i = 0; i < texts.getChildCount(); i++)
            {
                final View child = texts.getChildAt(i);
                if (child != note && child instanceof TextView)
                {
                    return (TextView) child;
                }
            }
            return null;
        }
    }
}
