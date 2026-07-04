package com.zoffcc.applications.trifa;

import org.khandaq.messenger.BuildConfig;

import org.khandaq.messenger.R;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.annotation.TargetApi;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import static com.zoffcc.applications.trifa.MainActivity.getRandomString;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LEN_TRIFA_AUTOGEN_PASSWORD;
import static com.zoffcc.applications.trifa.TRIFAGlobals.LEN_TRIFA_MANUAL_PASSWORD_MIN_LEN;
import static com.zoffcc.applications.trifa.TRIFAGlobals.PREF__DB_secrect_key__user_hash;
import static com.zoffcc.applications.trifa.ToxProfileImportHelper.ImportMode;

public class SetPasswordActivity extends AppCompatActivity
{
    private static final String TAG = "trifa.SetPasswordActy";

    private check_user_password_criteria mAuthTask = null;

    // UI references.
    private TextView githash_text;
    private EditText mPasswordView1;
    private EditText mPasswordView2;
    private View mProgressView;
    private View mLoginFormView;

    private SharedPreferences settings;

    private final ActivityResultLauncher<String[]> importProfileLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::onImportProfilePicked);

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "onCreate");

        setContentView(R.layout.activity_set_password);

        settings = PreferenceManager.getDefaultSharedPreferences(this);

        if (DbSecretKeyStorage.hasExistingUserDatabase(this))
        {
            Log.w(TAG, "existing DB detected — redirecting to CheckPassword");
            startActivity(new Intent(this, CheckPasswordActivity.class));
            finish();
            return;
        }

        githash_text = findViewById(R.id.githash_text);

        mPasswordView1 = (EditText) findViewById(R.id.password_1);
        mPasswordView1.setOnEditorActionListener(new TextView.OnEditorActionListener()
        {
            @Override
            public boolean onEditorAction(TextView textView, int id, KeyEvent keyEvent)
            {
                return true;
            }
        });

        mPasswordView2 = (EditText) findViewById(R.id.password_2);
        mPasswordView2.setOnEditorActionListener(new TextView.OnEditorActionListener()
        {
            @Override
            public boolean onEditorAction(TextView textView, int id, KeyEvent keyEvent)
            {
                return true;
            }
        });

        Button SignInButton = (Button) findViewById(R.id.set_button);
        SignInButton.setOnClickListener(new OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                check_password_validity();
            }
        });

        Button SkipButton = (Button) findViewById(R.id.skip_button);
        SkipButton.setOnClickListener(new OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                auto_create_password();
                settings.edit().putBoolean("PW_SET_SCREEN_DONE", true).commit();
                // ok open main activity
                Intent main_act = new Intent(SetPasswordActivity.this, MainActivity.class);
                startActivity(main_act);
                finish();
            }
        });

        Button importProfileButton = (Button) findViewById(R.id.import_profile_button);
        importProfileButton.setOnClickListener(new OnClickListener()
        {
            @Override
            public void onClick(View view)
            {
                ToxProfileImportHelper.showFirstLaunchImportInfo(SetPasswordActivity.this, new Runnable()
                {
                    @Override
                    public void run()
                    {
                        importProfileLauncher.launch(ToxProfileImportHelper.TOX_IMPORT_MIME_TYPES);
                    }
                });
            }
        });

        // KHANDAQ: arrived from the "Импорт профиля" instructions screen → open the .tox picker
        // directly (the instructions screen already replaced the first-launch info dialog).
        if (getIntent() != null && getIntent().getBooleanExtra("KHANDAQ_AUTOSTART_IMPORT", false))
        {
            importProfileLauncher.launch(ToxProfileImportHelper.TOX_IMPORT_MIME_TYPES);
        }

        // KHANDAQ: app-bar back → return to the onboarding intro.
        ImageView backButton = (ImageView) findViewById(R.id.back_button);
        if (backButton != null)
        {
            backButton.setOnClickListener(new OnClickListener()
            {
                @Override
                public void onClick(View view)
                {
                    startActivity(new Intent(SetPasswordActivity.this, OnboardingActivity.class));
                    finish();
                }
            });
        }


        mLoginFormView = findViewById(R.id.login_form);
        mProgressView = findViewById(R.id.login_progress);

        try
        {
            githash_text.setText("build: ????????");
        }
        catch(Exception ignored)
        {
        }

        String abis = "??";
        try
        {
            abis = Build.SUPPORTED_ABIS[0];
        }
        catch(Exception e)
        {
            try
            {
                abis = Build.CPU_ABI;
            }
            catch(Exception e2)
            {
            }
        }

        try
        {
            githash_text.setText("build: " + BuildConfig.GitHash +
                                 "\n" + "API: " + Build.VERSION.SDK_INT + "            " +
                                 "\n" + "ABI: " + abis + "            ");
        }
        catch(Exception ignored)
        {
        }
    }

    private void onImportProfilePicked(final Uri uri)
    {
        if (uri == null)
        {
            return;
        }

        showProgress(true);

        // KHANDAQ: run the import I/O AND the PBKDF2 DB-key derivation (auto_create_password) OFF the
        // UI thread. On first launch these execute before MainActivity, and doing them on the main
        // thread froze the app right after import — long enough to ANR / be killed by the system
        // ("выкидывает после импорта"), worst on Android 16 (scoped-storage reads + SQLCipher key
        // setup). The UI transition is posted back to the main thread once the work completes.
        new Thread(new Runnable()
        {
            @Override
            public void run()
            {
                boolean ok = false;
                try
                {
                    final java.io.File staging = new java.io.File(getCacheDir(), "import_savedata.tox");
                    if (ToxProfileImportHelper.copyUriToFile(SetPasswordActivity.this, uri, staging))
                    {
                        HelperGeneric.io_file_copy(staging,
                                ToxProfileImportHelper.savedataDestination(SetPasswordActivity.this));
                        staging.delete();
                        auto_create_password();
                        settings.edit().putBoolean("PW_SET_SCREEN_DONE", true).commit();
                        ok = true;
                    }
                }
                catch (Exception e)
                {
                    Log.e(TAG, "first-launch import failed: " + e.getMessage());
                    ok = false;
                }

                final boolean success = ok;
                runOnUiThread(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        if (isFinishing() || isDestroyed())
                        {
                            return;
                        }
                        showProgress(false);
                        if (success)
                        {
                            startActivity(new Intent(SetPasswordActivity.this, MainActivity.class));
                            finish();
                        }
                        else
                        {
                            ToxProfileImportHelper.showImportError(SetPasswordActivity.this,
                                    getString(R.string.settings_import_tox_profile_failed));
                        }
                    }
                });
            }
        }).start();
    }

    void auto_create_password()
    {
        // this does NOT need to be crpytographically secure, since it will be saved cleartext
        // it is just used if the user does not want to set a real password

        // create new key -------------
        String key = getRandomString(LEN_TRIFA_AUTOGEN_PASSWORD);
        DbSecretKeyStorage.saveAutoGeneratedKey(this, key);
        // create new key -------------
    }

    private void check_password_validity()
    {
        Log.i(TAG, "attemptLogin");

        if (mAuthTask != null)
        {
            return;
        }

        // Reset errors.
        mPasswordView1.setError(null);
        mPasswordView2.setError(null);

        // Store values at the time of the login attempt.
        String password1 = mPasswordView1.getText().toString();
        String password2 = mPasswordView2.getText().toString();

        boolean cancel = false;
        View focusView = null;

        // Check for a valid password, if the user entered one.
        if (TextUtils.isEmpty(password1))
        {
            mPasswordView1.setError(this.getString(R.string.set_password_message_empty));
            focusView = mPasswordView1;
            cancel = true;
        }

        if (!isPasswordValid(password1))
        {
            mPasswordView1.setError(this.getString(R.string.set_password_message_password_invalid));
            focusView = mPasswordView1;
            cancel = true;
        }

        if (TextUtils.isEmpty(password2))
        {
            mPasswordView2.setError(this.getString(R.string.set_password_message_empty));
            focusView = mPasswordView2;
            cancel = true;
        }

        if (!isPasswordValid(password2))
        {
            mPasswordView2.setError(this.getString(R.string.set_password_message_password_invalid));
            focusView = mPasswordView2;
            cancel = true;
        }

        if (!TextUtils.equals(password1, password2))
        {
            mPasswordView2.setError(this.getString(R.string.set_password_message_password_dont_match));
            focusView = mPasswordView2;
            cancel = true;
        }

        if (cancel)
        {
            // There was an error; don't attempt login and focus the first
            // form field with an error.
            focusView.requestFocus();
        }
        else
        {
            // Show a progress spinner, and kick off a background task to
            // perform the user password criteria check.
            showProgress(true);
            mAuthTask = new check_user_password_criteria(password1, password2);
            mAuthTask.execute((Void) null);
        }
    }

    static boolean isPasswordValid(String password)
    {
        if (password.length() < LEN_TRIFA_MANUAL_PASSWORD_MIN_LEN)
        {
            return false;
        }

        boolean hasLetter = false;
        boolean hasDigit = false;

        for (int i = 0; i < password.length(); i++)
        {
            char c = password.charAt(i);
            if (Character.isLetter(c))
            {
                hasLetter = true;
            }
            else if (Character.isDigit(c))
            {
                hasDigit = true;
            }
        }

        return hasLetter && hasDigit;
    }

    /**
     * Shows the progress UI and hides the login form.
     */
    @TargetApi(Build.VERSION_CODES.HONEYCOMB_MR2)
    private void showProgress(final boolean show)
    {
        // On Honeycomb MR2 we have the ViewPropertyAnimator APIs, which allow
        // for very easy animations. If available, use these APIs to fade-in
        // the progress spinner.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB_MR2)
        {
            int shortAnimTime = getResources().getInteger(android.R.integer.config_shortAnimTime);

            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
            mLoginFormView.animate().setDuration(shortAnimTime).alpha(show ? 0 : 1).setListener(
                    new AnimatorListenerAdapter()
                    {
                        @Override
                        public void onAnimationEnd(Animator animation)
                        {
                            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
                        }
                    });

            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mProgressView.animate().setDuration(shortAnimTime).alpha(show ? 1 : 0).setListener(
                    new AnimatorListenerAdapter()
                    {
                        @Override
                        public void onAnimationEnd(Animator animation)
                        {
                            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
                        }
                    });
        }
        else
        {
            // The ViewPropertyAnimator APIs are not available, so simply show
            // and hide the relevant UI components.
            mProgressView.setVisibility(show ? View.VISIBLE : View.GONE);
            mLoginFormView.setVisibility(show ? View.GONE : View.VISIBLE);
        }
    }


    /**
     * Represents an asynchronous login/registration task used to authenticate
     * the user.
     */
    public class check_user_password_criteria extends AsyncTask<Void, Void, Boolean>
    {

        private final String mPassword1;
        private final String mPassword2;

        check_user_password_criteria(String password1, String password2)
        {
            mPassword1 = password1;
            mPassword2 = password2;
        }

        @Override
        protected Boolean doInBackground(Void... params)
        {
            // just in case, check here again if both passwords actually match
            if (!TextUtils.equals(mPassword1, mPassword2))
            {
                return false;
            }

            String try_password_hash;
            try
            {
                try_password_hash = DbSecretKeyStorage.deriveManualPasswordHash(
                        SetPasswordActivity.this, mPassword1);
            }
            catch (Exception e)
            {
                return false;
            }

            // remember hash ---------------
            PREF__DB_secrect_key__user_hash = try_password_hash;
            // remember hash ---------------

            return true;
        }

        @Override
        protected void onPostExecute(final Boolean success)
        {
            mAuthTask = null;
            showProgress(false);

            if (success)
            {
                DbSecretKeyStorage.clearAutoGeneratedKey(SetPasswordActivity.this);
                settings.edit().putBoolean("PW_SET_SCREEN_DONE", true).commit();
                // ok open main activity
                Intent main_act = new Intent(SetPasswordActivity.this, MainActivity.class);
                startActivity(main_act);
                finish();
            }
            else
            {
                mPasswordView1.setError(getString(R.string.set_password_setup_failed));
                mPasswordView1.requestFocus();
            }
        }

        @Override
        protected void onCancelled()
        {
            mAuthTask = null;
            showProgress(false);
        }
    }

    @Override
    public void onBackPressed()
    {
        // super.onBackPressed();
        // do nothing!!
    }
}
