package com.zoffcc.applications.trifa;

import org.khandaq.messenger.R;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class NetworkDiagnosticsActivity extends AppCompatActivity
{
    private TextView textView;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_network_diagnostics);

        final Toolbar toolbar = findViewById(R.id.network_diag_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null)
        {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.network_diag_title);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        textView = findViewById(R.id.network_diag_text);
        final Button refresh = findViewById(R.id.network_diag_refresh);
        final Button share = findViewById(R.id.network_diag_share);

        refresh.setOnClickListener(v -> refreshContent());
        share.setOnClickListener(v -> {
            final android.content.Intent intent = NetworkDiagnosticsLog.createShareIntent(this);
            if (intent != null)
            {
                startActivity(android.content.Intent.createChooser(intent, getString(R.string.network_diag_export)));
            }
        });

        refreshContent();
    }

    private void refreshContent()
    {
        final String content = "--- event log ---\n"
                + NetworkDiagnosticsLog.snapshot()
                + "\n--- snapshot ---\n"
                + NetworkDiagnosticsCollector.collect(this);
        textView.setText(content);
    }
}
