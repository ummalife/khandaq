package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultCaller;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import org.khandaq.messenger.R;

/**
 * KHANDAQ (re-audit 2026-08-21, R-06) - the single door in front of every path that writes a secret
 * out of the app in the clear.
 *
 * <p>What sits behind that door is not "a file with some settings". The .tox savedata IS the Tox
 * private key: whoever holds a copy is that account - they can sign in as the user, receive what is
 * sent to them and write as them. It carries no password, and there is nothing to revoke afterwards.
 * The chat bundle is the message database re-encrypted with an empty key, i.e. plaintext history,
 * and the "reveal passwords" screen prints the database key on the display. Before this class, the
 * most exposed of those - Settings then Export profile - was one tap plus one confirm dialog away
 * from writing the private key into Downloads, which is exactly where cloud sync, file managers and
 * support-bundle collectors look.</p>
 *
 * <p>The raw exports are deliberately NOT deleted. Other Tox clients import a plain savedata file
 * and nothing else, so removing the path would strand anyone migrating away - a previous review
 * round already recorded that reasoning in {@link ExportActivity}. What changes is the cost of
 * reaching it:</p>
 *
 * <ol>
 *   <li>the encrypted backup ({@link PasswordBackupHelper}, .kbk) is the default action everywhere a
 *       normal user meets export, and the raw route is the labelled alternative;</li>
 *   <li>a warning that says plainly what the file is, rather than "Tox ID and contacts";</li>
 *   <li>device re-authentication - lock-screen PIN, pattern, password or biometric - so a phone
 *       handed over unlocked, or picked up mid-session, cannot walk the identity out;</li>
 *   <li>a one-shot authorisation token that {@link ToxProfileImportHelper#handleExportDestination}
 *       requires. A future caller that forgets the dialog does not get a quiet plaintext write, it
 *       gets a refusal - the check cannot be bypassed by adding a new entry point.</li>
 * </ol>
 *
 * <p>Devices with no secure lock screen cannot re-authenticate at all; there, the user is made to
 * type a confirmation word instead. That is weaker on purpose and it is the honest ceiling: on such
 * a device anyone holding the phone already has the app unlocked, so blocking the export outright
 * would cost those users their migration path and buy nothing.</p>
 *
 * <p>One instance per host (activity or fragment) - it registers an activity-result launcher, so it
 * must be constructed from onCreate() before the host reaches STARTED.</p>
 */
final class PlaintextExportGate
{
    private static final String TAG = "trifa.PlaintextGate";

    /**
     * How long an authorisation stays usable. It has to outlive the Storage-Access-Framework picker
     * the caller opens next - the user may go browsing for a folder, create one, switch to Drive -
     * but not outlive the export it was granted for.
     */
    private static final long AUTHORISED_WINDOW_MS = 5L * 60L * 1000L;

    /** Supplies the host activity, which a fragment only has while it is attached. */
    interface HostActivity
    {
        @Nullable
        Activity get();
    }

    /**
     * Monotonic deadline of the current authorisation, or 0. {@link SystemClock#elapsedRealtime()}
     * rather than wall clock: a timezone change or an NTP step must not be able to extend it. Static
     * because a grant belongs to the process, and the process dying revokes it - the right default.
     */
    private static long authorisedUntilElapsedMs = 0L;

    private final HostActivity host;
    private final ActivityResultLauncher<Intent> credentialLauncher;
    private Runnable pendingAction;

    PlaintextExportGate(@NonNull final ActivityResultCaller caller, @NonNull final HostActivity host)
    {
        this.host = host;
        this.credentialLauncher = caller.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result ->
                {
                    final Runnable action = pendingAction;
                    pendingAction = null;

                    if (action != null && result != null && result.getResultCode() == Activity.RESULT_OK)
                    {
                        authorise();
                        action.run();
                        return;
                    }

                    // Cancelled, or the wrong credential. Say so: a silently dropped tap reads as a bug
                    // and invites the user to keep pressing.
                    final Activity a = host.get();
                    if (a != null)
                    {
                        Toast.makeText(a, R.string.plaintext_export_not_authenticated, Toast.LENGTH_LONG).show();
                    }
                });
    }

    /**
     * Warn, re-authenticate, then run onGranted. The resource-id form is the one to use; it keeps
     * callers from shipping an untranslated literal.
     */
    void require(final int titleRes, final int messageRes, final int confirmRes,
                 @NonNull final Runnable onGranted)
    {
        final Activity a = host.get();
        if (a == null)
        {
            return;
        }
        require(a.getString(titleRes), a.getString(messageRes), a.getString(confirmRes), onGranted);
    }

    /** As above, for the callers whose message is formatted with a path at runtime. */
    void require(@NonNull final CharSequence title, @NonNull final CharSequence message,
                 @NonNull final CharSequence confirm, @NonNull final Runnable onGranted)
    {
        final Activity a = host.get();
        if (a == null)
        {
            return;
        }

        new AlertDialog.Builder(a)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(confirm, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface dialog, int which)
                    {
                        reauthenticate(onGranted);
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void reauthenticate(@NonNull final Runnable onGranted)
    {
        final Activity a = host.get();
        if (a == null)
        {
            return;
        }

        // createConfirmDeviceCredentialIntent is API 21+, which is this app's minSdk, but it still
        // returns null when the device has no credential set - and isKeyguardSecure() can disagree
        // with it on some OEM builds, so the null check below is what decides, not the flag.
        KeyguardManager km = null;
        try
        {
            km = (KeyguardManager) a.getSystemService(Context.KEYGUARD_SERVICE);
        }
        catch (Throwable e)
        {
            Log.w(TAG, "no KeyguardManager: " + e.getMessage());
        }

        if (km != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
        {
            Intent confirm = null;
            try
            {
                confirm = km.createConfirmDeviceCredentialIntent(
                        a.getString(R.string.plaintext_export_reauth_title),
                        a.getString(R.string.plaintext_export_reauth_description));
            }
            catch (Throwable e)
            {
                Log.w(TAG, "createConfirmDeviceCredentialIntent failed: " + e.getMessage());
            }

            if (confirm != null)
            {
                pendingAction = onGranted;
                try
                {
                    credentialLauncher.launch(confirm);
                    return;
                }
                catch (Throwable e)
                {
                    // Nothing can handle it (stripped OEM keyguard). Fall through rather than leave
                    // the user on a dead button.
                    pendingAction = null;
                    Log.w(TAG, "cannot launch credential confirmation: " + e.getMessage());
                }
            }
        }

        promptTypedConfirmation(a, onGranted);
    }

    /**
     * Fallback for a device with no lock screen: make the user type the confirmation word. This is
     * not authentication and does not pretend to be - it is a deliberate speed bump against the tap
     * made by mistake, on a device where there is no secret to prove knowledge of.
     */
    private void promptTypedConfirmation(@NonNull final Activity a, @NonNull final Runnable onGranted)
    {
        final String word = a.getString(R.string.plaintext_export_typed_confirm_word);

        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        final int pad = (int) (a.getResources().getDisplayMetrics().density * 20);
        box.setPadding(pad, pad / 2, pad, 0);

        final TextView explain = new TextView(a);
        explain.setText(a.getString(R.string.plaintext_export_typed_confirm_message, word));

        final EditText input = new EditText(a);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(word);

        box.addView(explain);
        box.addView(input);

        final AlertDialog dialog = new AlertDialog.Builder(a)
                .setTitle(R.string.plaintext_export_reauth_title)
                .setView(box)
                .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener()
                {
                    @Override
                    public void onClick(DialogInterface d, int which)
                    {
                        authorise();
                        onGranted.run();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d ->
        {
            final Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            ok.setEnabled(false);
            input.addTextChangedListener(new TextWatcher()
            {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after)
                {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count)
                {
                }

                @Override
                public void afterTextChanged(Editable s)
                {
                    ok.setEnabled(word.contentEquals(s.toString().trim()));
                }
            });
        });

        dialog.show();
    }

    private static void authorise()
    {
        authorisedUntilElapsedMs = SystemClock.elapsedRealtime() + AUTHORISED_WINDOW_MS;
    }

    /**
     * Spend the authorisation. Returns true at most once per grant, so a destination picker that
     * somehow delivers twice cannot turn one confirmation into two plaintext copies.
     */
    static boolean consumeAuthorisation()
    {
        final long until = authorisedUntilElapsedMs;
        authorisedUntilElapsedMs = 0L;
        return until != 0L && SystemClock.elapsedRealtime() < until;
    }

    /** Drop any outstanding grant - for a flow abandoned before it writes. */
    static void clearAuthorisation()
    {
        authorisedUntilElapsedMs = 0L;
    }
}
