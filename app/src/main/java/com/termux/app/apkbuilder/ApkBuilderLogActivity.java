package com.termux.app.apkbuilder;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.termux.R;

/**
 * TermuxMod: shows a live-streamed log for a headless (no visible terminal)
 * build/import run — see {@link ApkBuilderRunner}. This is the "V2" screen:
 * the user never needs to touch a terminal for this flow, only this Activity.
 */
public class ApkBuilderLogActivity extends AppCompatActivity implements ApkBuilderRunner.Listener {

    public static final String EXTRA_SCRIPT_PATH = "com.termux.app.apkbuilder.EXTRA_SCRIPT_PATH";
    public static final String EXTRA_ACTION = "com.termux.app.apkbuilder.EXTRA_ACTION";
    public static final String EXTRA_PROJECT_PATH = "com.termux.app.apkbuilder.EXTRA_PROJECT_PATH";
    public static final String EXTRA_TITLE = "com.termux.app.apkbuilder.EXTRA_TITLE";

    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder mLogBuffer = new StringBuilder();

    private TextView mLogText;
    private ScrollView mLogScroll;
    private TextView mStatusText;
    private ProgressBar mProgressBar;
    private MaterialButton mStopButton;
    private MaterialButton mCloseButton;

    private ApkBuilderRunner mRunner;
    private boolean mFinished = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apk_builder_log);

        Toolbar toolbar = findViewById(R.id.apk_builder_log_toolbar);
        setSupportActionBar(toolbar);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        toolbar.setTitle(title != null ? title : getString(R.string.apk_builder_log_title));

        mLogText = findViewById(R.id.apk_builder_log_text);
        mLogScroll = findViewById(R.id.apk_builder_log_scroll);
        mStatusText = findViewById(R.id.apk_builder_log_status_text);
        mProgressBar = findViewById(R.id.apk_builder_log_progress);
        mStopButton = findViewById(R.id.apk_builder_log_stop_button);
        mCloseButton = findViewById(R.id.apk_builder_log_close_button);

        mStopButton.setOnClickListener(v -> {
            if (mRunner != null) mRunner.stop();
            mStopButton.setEnabled(false);
            mStatusText.setText(R.string.apk_builder_log_status_stopping);
        });
        mCloseButton.setOnClickListener(v -> finish());

        String scriptPath = getIntent().getStringExtra(EXTRA_SCRIPT_PATH);
        String action = getIntent().getStringExtra(EXTRA_ACTION);
        String projectPath = getIntent().getStringExtra(EXTRA_PROJECT_PATH);

        if (scriptPath == null || action == null) {
            appendLine("[TermuxMod] Missing script path or action, aborting.", true);
            onExited(-1);
            return;
        }

        mRunner = new ApkBuilderRunner(this);
        mRunner.run(this, scriptPath, action, projectPath);
    }

    // TermuxMod: the script prints ANSI color/cursor escape codes meant for a real
    // terminal (which interprets them into colors/redraws). This plain TextView
    // can't interpret them, so without stripping they show up as garbage like
    // "␛[0;36m1␛[0m" — this regex removes SGR color codes and cursor-control
    // sequences, leaving clean plain text.
    private static final java.util.regex.Pattern ANSI_PATTERN =
        java.util.regex.Pattern.compile("\u001B\\[[0-9;?]*[a-zA-Z]");

    private static String stripAnsi(String line) {
        return ANSI_PATTERN.matcher(line).replaceAll("");
    }

    private void appendLine(String line, boolean isStderr) {
        String clean = stripAnsi(line);
        if (clean.isEmpty()) return; // Many cursor-control-only lines become empty after stripping.
        mLogBuffer.append(clean).append('\n');
        mLogText.setText(mLogBuffer);
        // Keep the view scrolled to the latest output.
        mLogScroll.post(() -> mLogScroll.fullScroll(android.view.View.FOCUS_DOWN));
    }

    @Override
    public void onLine(String line, boolean isStderr) {
        mMainHandler.post(() -> appendLine(line, isStderr));
    }

    @Override
    public void onExited(int exitCode) {
        mMainHandler.post(() -> {
            mFinished = true;
            mProgressBar.setVisibility(android.view.View.GONE);
            mStopButton.setEnabled(false);
            mCloseButton.setEnabled(true);
            if (exitCode == 0) {
                mStatusText.setText(R.string.apk_builder_log_status_success);
            } else {
                mStatusText.setText(getString(R.string.apk_builder_log_status_failed, exitCode));
            }
        });
    }

    @Override
    public void onFailedToStart() {
        mMainHandler.post(() -> {
            appendLine("[TermuxMod] Failed to start the process.", true);
            onExited(-1);
        });
    }

    @Override
    public void onBackPressed() {
        if (!mFinished) {
            // Don't let a stray back-press silently orphan a running build with no
            // way back to this screen's log; require an explicit Stop first.
            return;
        }
        super.onBackPressed();
    }
}
