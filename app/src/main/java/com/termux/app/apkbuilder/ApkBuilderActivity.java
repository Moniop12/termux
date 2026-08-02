package com.termux.app.apkbuilder;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.TermuxService;
import com.termux.app.filebrowser.FileBrowserActivity;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * TermuxMod: a native front-end for the user's own APK-builder shell script
 * (e.g. "TERMUX APK BUILDER PRO" style scripts). Lets them pick the script
 * once and the project folder via the file browser instead of typing paths,
 * then launches the build in a visible Termux terminal session (V1 scope —
 * terminal stays open so progress/output can be seen).
 *
 * How the automation works without touching the user's script at all: their
 * script already supports "save last project" + "press Enter to reuse it" at
 * its own interactive menu (a state file holding the last picked path). We
 * write the picked folder into that same state file before launching, so the
 * only thing left to do inside the terminal is press "1"/"2" for
 * Debug/Release then Enter to reuse the project — no folder navigation or
 * typing needed anymore.
 *
 * Note: this does NOT auto-press those keys for you. Android's
 * ACTION_SERVICE_EXECUTE stdin extra only feeds input to the headless
 * "app shell" runner, not to an interactive terminal session — there is no
 * working way to script keystrokes into a real pty from here. Full
 * hands-off automation (zero terminal interaction) needs the headless
 * runner + a native progress/log screen instead of a terminal, which is a
 * separate, bigger change (V2).
 */
public class ApkBuilderActivity extends AppCompatActivity {

    // TermuxMod: matches the state file convention used by the builder script
    // (APP_STATE_DIR="$HOME/.termux-apk-builder", LAST_PROJECT_FILE="$APP_STATE_DIR/last_project.txt").
    // If your script uses a different location, change this constant.
    private static final String STATE_DIR_NAME = ".termux-apk-builder";
    private static final String LAST_PROJECT_FILE_NAME = "last_project.txt";

    private TermuxAppSharedPreferences mPreferences;
    private TextView mScriptPathText;
    private TextView mProjectPathText;
    private MaterialButton mBuildDebugButton;
    private MaterialButton mBuildReleaseButton;

    private String mScriptPath;
    private String mProjectPath;

    private final ActivityResultLauncher<Intent> mPickScriptLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            String path = result.getData().getStringExtra(FileBrowserActivity.RESULT_EXTRA_PATH);
            if (path == null) return;
            mScriptPath = path;
            mPreferences.setApkBuilderScriptPath(path);
            refreshUi();
        });

    private final ActivityResultLauncher<Intent> mPickProjectLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
            String path = result.getData().getStringExtra(FileBrowserActivity.RESULT_EXTRA_PATH);
            if (path == null) return;
            mProjectPath = path;
            refreshUi();
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_apk_builder);

        Toolbar toolbar = findViewById(R.id.apk_builder_toolbar);
        setSupportActionBar(toolbar);

        mPreferences = TermuxAppSharedPreferences.build(this);

        mScriptPathText = findViewById(R.id.apk_builder_script_path);
        mProjectPathText = findViewById(R.id.apk_builder_project_path);
        mBuildDebugButton = findViewById(R.id.apk_builder_build_debug);
        mBuildReleaseButton = findViewById(R.id.apk_builder_build_release);

        findViewById(R.id.apk_builder_pick_script).setOnClickListener(v -> {
            Intent intent = new Intent(this, FileBrowserActivity.class);
            intent.putExtra(FileBrowserActivity.EXTRA_PICK_MODE, FileBrowserActivity.PICK_MODE_FILE);
            mPickScriptLauncher.launch(intent);
        });

        findViewById(R.id.apk_builder_pick_project).setOnClickListener(v -> {
            Intent intent = new Intent(this, FileBrowserActivity.class);
            intent.putExtra(FileBrowserActivity.EXTRA_PICK_MODE, FileBrowserActivity.PICK_MODE_FOLDER);
            mPickProjectLauncher.launch(intent);
        });

        mBuildDebugButton.setOnClickListener(v -> startBuild("debug"));
        mBuildReleaseButton.setOnClickListener(v -> startBuild("release"));

        mScriptPath = mPreferences.getApkBuilderScriptPath();
        refreshUi();
    }

    private void refreshUi() {
        mScriptPathText.setText(mScriptPath != null ? mScriptPath : getString(R.string.apk_builder_not_selected));
        mProjectPathText.setText(mProjectPath != null ? mProjectPath : getString(R.string.apk_builder_not_selected));

        boolean canBuild = mScriptPath != null && new File(mScriptPath).isFile() && mProjectPath != null;
        mBuildDebugButton.setEnabled(canBuild);
        mBuildReleaseButton.setEnabled(canBuild);
    }

    /**
     * Writes the picked project path into the script's own "last project" state
     * file, then launches the script in a visible terminal session. The user
     * still needs to tap "1"/"2" (Debug/Release) then Enter inside the terminal
     * — see the class javadoc for why that can't be automated in this (V1) mode.
     * The script itself is not modified in any way.
     */
    private void startBuild(String buildType) {
        if (mScriptPath == null || mProjectPath == null) return;

        File stateDir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, STATE_DIR_NAME);
        if (!stateDir.exists() && !stateDir.mkdirs()) {
            Toast.makeText(this, R.string.apk_builder_state_dir_failed, Toast.LENGTH_LONG).show();
            return;
        }
        File lastProjectFile = new File(stateDir, LAST_PROJECT_FILE_NAME);
        try (PrintWriter writer = new PrintWriter(lastProjectFile, "UTF-8")) {
            writer.println(mProjectPath);
        } catch (IOException e) {
            Toast.makeText(this, getString(R.string.apk_builder_state_write_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }

        String bashPath = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash";
        Intent execIntent = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE, Uri.parse("file://" + bashPath), this, TermuxService.class);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, new String[]{mScriptPath});
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, TermuxConstants.TERMUX_HOME_DIR_PATH);
        execIntent.putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, "APK Builder (" + buildType + ")");

        Toast.makeText(this, "debug".equals(buildType)
            ? R.string.apk_builder_toast_press_1
            : R.string.apk_builder_toast_press_2, Toast.LENGTH_LONG).show();

        try {
            startService(execIntent);
            finish(); // Let TermuxActivity come to foreground and show the terminal.
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
