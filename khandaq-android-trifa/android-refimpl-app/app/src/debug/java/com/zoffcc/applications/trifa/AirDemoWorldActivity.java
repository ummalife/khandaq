package com.zoffcc.applications.trifa;

import android.app.Activity;
import android.os.Bundle;

/**
 * KHANDAQ (redesign, stage 5): seeds or clears the made-up demo world (AirDemoWorld), debug builds only.
 * Started with adb, it hands the request to a background thread and closes at once; logcat tag
 * trifa.AirDemoWorld says "done" or why it refused. Extras: world (store, bulk or clear), lang (en or ru),
 * observer (false leaves out the group where you only read), contacts, thread and group_thread (the sizes of
 * the bulk world).
 */
public final class AirDemoWorldActivity extends Activity
{
    @Override
    protected void onCreate(final Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        AirDemoWorld.request(this, getIntent());
        finish();
    }
}
